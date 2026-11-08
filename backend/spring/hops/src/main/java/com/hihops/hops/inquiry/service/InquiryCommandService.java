package com.hihops.hops.inquiry.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hihops.hops.agent.domain.AgentRun;
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
import com.hihops.hops.global.validation.UserContentValidator;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.attachment.AttachmentBindingService;
import com.hihops.hops.inquiry.domain.InquiryStatus;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.MessageStatus;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.dto.InquiryCreateCommand;
import com.hihops.hops.inquiry.privacy.SensitiveTextMasker;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.itsystem.service.SystemQueryService;
import com.hihops.hops.realtime.dto.MessageStoredEvent;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.user.repository.MemberRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문의 생성 (WIRE_CONTRACT 12.2 POST /inquiries, 12.8 1~2번).
 *
 * <p>한 거래에서 문의(inquiryVersion=1)·첫 USER 메시지·상담 실행(QUEUED)·불변 실행 입력·currentRun 연결·멱등 결과를 저장한다.
 * 응답은 200 SUCCEEDED·resource=INQUIRY이며, 사용자 입력과 실행 접수의 저장이지 AI 답변 완료가 아니다.
 * 같은 요청 키로 다시 오면 처음 결과를 돌려주고 새 실행을 만들지 않는다 ({@link IdempotencyService}).
 *
 * <p>AI 서버 전달은 commit 뒤 {@link AgentRunQueued} 이벤트를 받는 쪽이 맡는다. 이 서비스는 AI를 부르지 않는다.
 * 첫 메시지 저장은 {@link MessageStoredEvent}로 문의 구독(SSE)에 commit 뒤 알린다.
 *
 * <p>reportedSystemId: 사용자가 고른 시스템은 첫 메시지 payload에만 남긴다. AiRunRequest에는 이 값을 담을 필드가 없고(InputProblem에만 있다),
 * 첫 분석에는 아직 문제가 없어 AI에 전달하지 않는다. 전달 방식은 계약 합의 뒤 정한다.
 */
@Service
@RequiredArgsConstructor
public class InquiryCommandService {

    private static final int MAX_TITLE_CODE_POINTS = 200; // inquiry.title VARCHAR(200)

    private final IdempotencyService idempotencyService;
    private final AttachmentBindingService attachmentBindingService;
    private final InquiryRepository inquiryRepository;
    private final MessageRepository messageRepository;
    private final AgentRunRepository agentRunRepository;
    private final MemberRepository memberRepository;
    private final ItSystemRepository itSystemRepository;
    private final SystemQueryService systemQueryService;
    private final InquiryNumberGenerator inquiryNumberGenerator;
    private final AnalysisInputFactory analysisInputFactory;
    private final AnalysisRunProperties runProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** 트랜잭션 밖(Controller)에서 부른다. */
    public CommandResult create(IdempotentRequest request, InquiryCreateCommand command) {
        InquiryCreateCommand masked = new InquiryCreateCommand(command.requestId(),
                SensitiveTextMasker.mask(command.content()), command.reportedSystemId(), command.attachmentIds());
        return idempotencyService.execute(request,
                operationId -> new ResourceRef(ResourceType.INQUIRY, createInquiry(request.actorId(), masked)));
    }

    private UUID createInquiry(UUID requesterId, InquiryCreateCommand command) {
        Member requester = memberRepository.findById(requesterId)
                .filter(member -> !member.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        UUID reportedSystemId = command.reportedSystemId();
        if (reportedSystemId != null
                && itSystemRepository.findById(reportedSystemId).filter(system -> !system.isDeleted()).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "선택한 시스템을 찾을 수 없습니다.");
        }

        Inquiry inquiry = inquiryRepository.save(new Inquiry(inquiryNumberGenerator.next(), requester,
                titleOf(command.content()), InquiryStatus.IN_PROGRESS));
        // 입력 snapshot에 저장 시각(createdAt)을 넣으려고 바로 flush한다
        Message first = messageRepository.saveAndFlush(new Message(inquiry, 1, SenderType.USER, command.content(),
                UserMessagePayload.json(jsonMapper, command.requestId(), reportedSystemId, List.of(), command.content()),
                MessageStatus.COMPLETED));
        attachmentBindingService.bind(requesterId, first.getId(), command.attachmentIds());
        // runId는 저장할 때 정해지므로 빈 입력으로 먼저 저장하고, 같은 거래 안에서 입력을 고정한다
        AgentRun run = agentRunRepository.saveAndFlush(new AgentRun(inquiry, first, inquiry.getVersion(), "{}",
                runProperties.modelVersion(), runProperties.promptVersion(), runProperties.schemaVersion()));
        run.prepareInput(analysisInputFactory.snapshot(run, inquiry, List.of(first), InputProblemReader.ProblemInputs.NONE, List.of(),
                systemQueryService.findSupportedSystems(), clock.instant()));
        inquiry.resumeAnalysis(run);
        eventPublisher.publishEvent(new AgentRunQueued(run.getId()));
        eventPublisher.publishEvent(MessageStoredEvent.of(inquiry.getId(), inquiry.getVersion(), first.getId(),
                command.requestId()));
        return inquiry.getId();
    }

    /**
     * 목록에 보여 줄 첫 제목. 첫 입력의 공백(줄바꿈 포함)을 한 칸으로 줄이고 200 코드 포인트까지 쓴다 (docs/ERD.md inquiry.title "첫 입력 → AI 요약 제목").
     * 원문은 메시지에 그대로 남으므로 제목만 다듬는다.
     */
    static String titleOf(String content) {
        StringBuilder title = new StringBuilder();
        boolean pendingSpace = false;
        int codePoints = 0;
        for (int codePoint : content.codePoints().toArray()) {
            if (UserContentValidator.isContractWhitespace(codePoint)) {
                pendingSpace = title.length() > 0;
                continue;
            }
            int needed = pendingSpace ? 2 : 1;
            if (codePoints + needed > MAX_TITLE_CODE_POINTS) {
                break;
            }
            if (pendingSpace) {
                title.append(' ');
                pendingSpace = false;
            }
            title.appendCodePoint(codePoint);
            codePoints += needed;
        }
        return title.toString();
    }
}
