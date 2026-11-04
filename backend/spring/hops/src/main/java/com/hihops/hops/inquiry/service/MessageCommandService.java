package com.hihops.hops.inquiry.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.agent.service.AgentRunQueued;
import com.hihops.hops.agent.service.AnalysisInputFactory;
import com.hihops.hops.agent.service.AnalysisRunProperties;
import com.hihops.hops.agent.service.InputProblemReader;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.idempotency.CommandResult;
import com.hihops.hops.global.idempotency.IdempotencyService;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.global.idempotency.ResourceRef;
import com.hihops.hops.global.idempotency.ResourceType;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryProblem;
import com.hihops.hops.inquiry.domain.InquiryStatus;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.MessageStatus;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.dto.MessageSendCommand;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.itsystem.service.SystemQueryService;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 메시지 전송 (WIRE_CONTRACT 12.2 POST /inquiries/{inquiryId}/messages, 12.8 1~2번).
 *
 * <p>문의 행을 잠그고 expectedInquiryVersion을 확인한 뒤 입력 버전을 1 올려 USER 메시지를 저장한다.
 * 진행 중인 문의면 같은 거래에서 이전 활성 실행(QUEUED·RUNNING)을 대체(CANCELLED, 외부 SUPERSEDED)하고,
 * 새 버전의 상담 실행을 접수한다(새 runId·answerMessageId, 불변 입력, currentRun 연결). 기존 티켓·질문 횟수는 유지한다.
 * 응답은 200 CommandReceipt·resource=INQUIRY이며 AI 답변 완료가 아니다. 같은 요청 키는 처음 결과를 돌려준다.
 *
 * <p>끝난 문의(RESOLVED·CLOSED)는 재오픈하지 않고 같은 현상을 새 문의로 받는다 (PRD 3.4, TARGET_CONTRACT 4절).
 * 해결 확인으로 상태가 바뀌어도 입력 버전은 그대로라 버전 검사만으로는 막히지 않으므로, 상태를 따로 보고 409 INVALID_STATE로 거절한다.
 *
 * <p>거절: 남의 문의·없는 문의 404, 버전이 다르면 409 INQUIRY_VERSION_CONFLICT, 끝난 문의는 409 INVALID_STATE,
 * 이 문의의 문제가 아닌 problemIds는 400 INVALID_INPUT.
 * 업무 거절은 REJECTED로 기록돼 같은 키로 다시 보내도 같은 오류다.
 *
 * <p>이전 실행의 AI 서버 취소 전파는 AI 쪽 취소 API(#93)가 준비되면 commit 뒤 이벤트로 연결한다. 지금은 Spring에서만 대체한다.
 */
@Service
@RequiredArgsConstructor
public class MessageCommandService {

    private final IdempotencyService idempotencyService;
    private final InquiryRepository inquiryRepository;
    private final InquiryProblemRepository inquiryProblemRepository;
    private final MessageRepository messageRepository;
    private final AgentRunRepository agentRunRepository;
    private final SystemQueryService systemQueryService;
    private final InputProblemReader inputProblemReader;
    private final AnalysisInputFactory analysisInputFactory;
    private final AnalysisRunProperties runProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** 트랜잭션 밖(Controller)에서 부른다. */
    public CommandResult send(IdempotentRequest request, UUID inquiryId, MessageSendCommand command) {
        // 같은 키 재확인도 현재 권한 검사 뒤에 한다 (WIRE_CONTRACT 12.1)
        inquiryRepository.findByIdAndRequester_IdAndDeletedAtIsNull(inquiryId, request.actorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return idempotencyService.execute(request,
                operationId -> new ResourceRef(ResourceType.INQUIRY, sendMessage(request.actorId(), inquiryId, command)));
    }

    private UUID sendMessage(UUID requesterId, UUID inquiryId, MessageSendCommand command) {
        Inquiry inquiry = inquiryRepository.findByIdAndDeletedAtIsNull(inquiryId)
                .filter(found -> found.getRequester().getId().equals(requesterId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (inquiry.getVersion() != command.expectedInquiryVersion()) {
            throw new BusinessException(ErrorCode.INQUIRY_VERSION_CONFLICT);
        }
        if (isClosed(inquiry)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "종료된 문의입니다. 같은 현상은 새 문의로 접수해 주세요.");
        }
        List<UUID> problemIds = command.problemIds().stream().distinct().toList();
        Set<UUID> known = inquiryProblemRepository.findByInquiry_IdAndDeletedAtIsNullOrderByProblemNo(inquiryId).stream()
                .map(InquiryProblem::getId).collect(Collectors.toSet());
        if (!known.containsAll(problemIds)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "선택한 문제를 찾을 수 없습니다.");
        }

        inquiry.recordInput();
        int sequenceNo = Math.incrementExact(messageRepository.findLastSequenceNo(inquiryId));
        Message message = messageRepository.saveAndFlush(new Message(inquiry, sequenceNo, SenderType.USER,
                command.content(), UserMessagePayload.json(jsonMapper, command.requestId(), null, problemIds, command.content()),
                MessageStatus.COMPLETED));

        Instant now = clock.instant();
        AgentRun previous = inquiry.getCurrentRun();
        if (previous != null
                && (previous.getRunStatus() == RunStatus.QUEUED || previous.getRunStatus() == RunStatus.RUNNING)) {
            previous.finish(RunStatus.CANCELLED, now);
        }
        AgentRun run = agentRunRepository.saveAndFlush(new AgentRun(inquiry, message, inquiry.getVersion(), "{}",
                runProperties.modelVersion(), runProperties.promptVersion(), runProperties.schemaVersion()));
        run.prepareInput(analysisInputFactory.snapshot(run, inquiry, messageRepository.findAllCompletedInOrder(inquiryId),
                inputProblemReader.read(inquiry, problemIds), problemIds, systemQueryService.findSupportedSystems(), now));
        inquiry.resumeAnalysis(run);
        eventPublisher.publishEvent(new AgentRunQueued(run.getId()));
        return inquiry.getId();
    }

    private static boolean isClosed(Inquiry inquiry) {
        return inquiry.getInquiryStatus() == InquiryStatus.RESOLVED || inquiry.getInquiryStatus() == InquiryStatus.CLOSED;
    }
}
