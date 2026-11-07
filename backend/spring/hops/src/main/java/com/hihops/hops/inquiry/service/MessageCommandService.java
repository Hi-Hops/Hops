package com.hihops.hops.inquiry.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
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
import com.hihops.hops.inquiry.domain.MessagePayload;
import com.hihops.hops.inquiry.domain.MessageRevision;
import com.hihops.hops.inquiry.domain.MessageStatus;
import com.hihops.hops.inquiry.domain.ProblemStateView;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.dto.MessageProblemLinkCommand;
import com.hihops.hops.inquiry.dto.MessageReviseCommand;
import com.hihops.hops.inquiry.dto.MessageSendCommand;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.inquiry.repository.MessageRevisionRepository;
import com.hihops.hops.itsystem.service.SystemQueryService;
import com.hihops.hops.realtime.dto.MessageStoredEvent;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.repository.TicketRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 메시지 전송·정정 (WIRE_CONTRACT 12.2 POST /inquiries/{inquiryId}/messages, .../messages/{messageId}/revisions, 12.8 1~2번).
 *
 * <p>정정({@link #revise})은 같은 메시지의 원문을 바꾸고 차수를 1 올린다. 바꾸기 전 원문은 message_revision에 보존하고,
 * ID·순번·처음 requestId는 그대로다. 그 뒤는 전송과 같이 입력 버전을 올리고 이전 실행을 대체해 새 실행을 접수하며,
 * 대상 문제는 그 메시지에 연결된 문제다. 메시지 차수가 다르면 409 MESSAGE_REVISION_CONFLICT,
 * 이 문의의 USER 메시지가 아니면 404다.
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
 * 이 문의에서 보완할 수 없는 problemIds(남의 문제·해결된 문제·지원 범위 밖 문제)는 400 INVALID_INPUT.
 * 업무 거절은 REJECTED로 기록돼 같은 키로 다시 보내도 같은 오류다.
 *
 * <p>보완 대상 (WIRE_CONTRACT 12.6, TARGET_CONTRACT 4절): 티켓이 배정된 문제가 있는데 대상을 고르지 않은 메시지는, 문제가 둘 이상이면
 * 어느 티켓에도 자동으로 붙이지 않고 pendingTarget으로 저장해 대상 선택을 기다린다(배정된 문제가 하나뿐이면 그 문제의 보완이다).
 * {@link #linkProblems}(problem-links)가 원문을 바꾸지 않고 대상을 확정하며 입력 버전을 1 올린다.
 * 대기 메시지는 대상이 정해질 때까지 AI 입력의 대화에서 빠지고 그 메시지 자체로는 분석하지 않는다. 다른 메시지의 분석은 막지 않는다.
 * 대상이 모두 이미 배정된 문제인 입력도 보완 정보일 뿐이라 자기 분석을 만들지 않는다(자동 새 티켓·재배정 없음).
 * 그래도 입력 버전은 올라가 진행 중이던 실행의 결과는 버려지므로, 그 실행의 대상은 새 실행으로 잇는다({@link #continueAnalysis}).
 * 배정된 문제가 없는데 대상을 고르지 않은 메시지는 실행 대상은 비워 분석이 정하게 두되, 직전 AI 질문·안내의 답으로 그 문제에 잇는다
 * ({@link #repliedProblems}). 잇지 않으면 AI가 그 답을 기존 문제의 출처로 쓸 수 없다.
 *
 * <p>저장·정정·대상 확정한 메시지는 {@link MessageStoredEvent}로 문의 구독(SSE)에 commit 뒤 알린다.
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
    private final MessageRevisionRepository messageRevisionRepository;
    private final TicketRepository ticketRepository;
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

    /** 트랜잭션 밖(Controller)에서 부른다. */
    public CommandResult revise(IdempotentRequest request, UUID inquiryId, UUID messageId, MessageReviseCommand command) {
        inquiryRepository.findByIdAndRequester_IdAndDeletedAtIsNull(inquiryId, request.actorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return idempotencyService.execute(request, operationId -> new ResourceRef(ResourceType.INQUIRY,
                reviseMessage(request.actorId(), inquiryId, messageId, command)));
    }

    /** 트랜잭션 밖(Controller)에서 부른다. */
    public CommandResult linkProblems(IdempotentRequest request, UUID inquiryId, UUID messageId,
            MessageProblemLinkCommand command) {
        inquiryRepository.findByIdAndRequester_IdAndDeletedAtIsNull(inquiryId, request.actorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return idempotencyService.execute(request, operationId -> new ResourceRef(ResourceType.INQUIRY,
                confirmTargets(request.actorId(), inquiryId, messageId, command)));
    }

    private UUID sendMessage(UUID requesterId, UUID inquiryId, MessageSendCommand command) {
        Inquiry inquiry = lockOpenInquiry(requesterId, inquiryId, command.expectedInquiryVersion());
        List<UUID> problemIds = command.problemIds().stream().distinct().toList();
        ProblemScope scope = scopeOf(inquiryId);
        if (!scope.linkable().containsAll(problemIds)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "보완할 수 있는 문제가 아닙니다.");
        }
        // 대상을 고르지 않았는데 배정된 문제가 있으면: 문제가 하나뿐이면 그 문제의 보완, 둘 이상이면 대상 선택을 기다린다
        boolean pending = false;
        if (problemIds.isEmpty() && !scope.assigned().isEmpty()) {
            if (scope.linkable().size() == 1) {
                problemIds = List.copyOf(scope.linkable());
            } else {
                pending = true;
            }
        }
        // 배정된 문제가 없으면 실행 대상은 비워 분석이 정하게 두고, 직전 AI 질문·안내의 답으로 문제에만 잇는다
        List<UUID> links = problemIds.isEmpty() && !pending ? repliedProblems(inquiryId, scope) : problemIds;

        inquiry.recordInput();
        int sequenceNo = Math.incrementExact(messageRepository.findLastSequenceNo(inquiryId));
        Message message = messageRepository.saveAndFlush(new Message(inquiry, sequenceNo, SenderType.USER,
                command.content(),
                UserMessagePayload.json(jsonMapper, command.requestId(), null, links, command.content(), pending),
                MessageStatus.COMPLETED));
        continueAnalysis(inquiry, message, problemIds, scope, command.requestId());
        eventPublisher.publishEvent(MessageStoredEvent.of(inquiryId, inquiry.getVersion(), message.getId(),
                command.requestId()));
        return inquiry.getId();
    }

    private UUID reviseMessage(UUID requesterId, UUID inquiryId, UUID messageId, MessageReviseCommand command) {
        Inquiry inquiry = lockOpenInquiry(requesterId, inquiryId, command.expectedInquiryVersion());
        Message message = userMessage(inquiryId, messageId);
        if (message.getRevision() != command.expectedMessageRevision()) {
            throw new BusinessException(ErrorCode.MESSAGE_REVISION_CONFLICT);
        }

        int previousRevision = message.getRevision();
        String previousContent = message.getContent();
        messageRevisionRepository.save(new MessageRevision(message, previousRevision, previousContent));
        message.revise(command.content(), UserMessagePayload.revised(jsonMapper, message.getPayload(), previousRevision,
                previousContent, Math.incrementExact(previousRevision), command.content(), command.requestId()));
        inquiry.recordInput();
        // 그 사이 삭제된 문제는 대상에서 뺀다. 대기 중인 메시지를 정정하면 계속 대상 선택을 기다린다
        ProblemScope scope = scopeOf(inquiryId);
        List<UUID> problemIds = jsonMapper.readValue(message.getPayload(), MessagePayload.class)
                .problemIds(message.getRevision()).stream().filter(scope.linkable()::contains).toList();
        continueAnalysis(inquiry, message, problemIds, scope, command.requestId());
        // 정정도 저장 알림이다. requestId는 이 정정 요청이다 (WIRE_CONTRACT 12.4 "저장 메시지·정정")
        eventPublisher.publishEvent(MessageStoredEvent.of(inquiryId, inquiry.getVersion(), message.getId(),
                command.requestId()));
        return inquiry.getId();
    }

    private UUID confirmTargets(UUID requesterId, UUID inquiryId, UUID messageId, MessageProblemLinkCommand command) {
        Inquiry inquiry = lockOpenInquiry(requesterId, inquiryId, command.expectedInquiryVersion());
        Message message = userMessage(inquiryId, messageId);
        if (!jsonMapper.readValue(message.getPayload(), MessagePayload.class).isPendingTarget()) {
            // 이미 연결된 메시지를 임의로 다시 잇지 않는다 (WIRE_CONTRACT 12.6)
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 보완 대상이 정해진 메시지입니다.");
        }
        List<UUID> problemIds = command.problemIds().stream().distinct().toList();
        ProblemScope scope = scopeOf(inquiryId);
        if (!scope.linkable().containsAll(problemIds)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "보완할 수 있는 문제가 아닙니다.");
        }

        message.updatePayload(UserMessagePayload.confirmed(jsonMapper, message.getPayload(), message.getRevision(),
                message.getContent(), problemIds));
        inquiry.recordInput();
        continueAnalysis(inquiry, message, problemIds, scope, command.requestId());
        // 원문은 그대로지만 문제 연결이 바뀌었으므로 다시 조회하게 알린다
        eventPublisher.publishEvent(MessageStoredEvent.of(inquiryId, inquiry.getVersion(), message.getId(),
                command.requestId()));
        return inquiry.getId();
    }

    /**
     * 대상 없이 보낸 메시지가 답하는 문제. 가장 최근 AI 메시지 뒤의 첫 사용자 메시지일 때만 정한다. 그 AI 메시지가
     * 질문(questionBundles)하거나 안내(guide)한 문제 중 아직 보완할 수 있는 문제이고, 그런 문제가 없으면 보완할 수 있는 문제가
     * 하나일 때만 그 문제다. 여럿이면 어느 문제인지 모르므로 잇지 않는다.
     *
     * <p>그 AI 메시지 뒤에 이미 사용자 메시지가 있으면 그 질문은 답을 받았으므로 잇지 않는다. AI 답변이 생성 중이거나 실패해 직전 AI
     * 메시지가 바뀌지 않은 사이 보낸 다른 이야기를 지난 질문의 문제에 묶지 않기 위해서다.
     *
     * <p>AI는 문제에 연결된 메시지만 그 문제의 사실 출처로 쓸 수 있다(AI rules/progress.py get_problem_message_ids). 질문에 답한
     * 메시지를 잇지 않으면 그 답을 인용한 분석이 출처 검증에서 거절돼 실행이 INVALID_OUTPUT으로 끝난다.
     */
    private List<UUID> repliedProblems(UUID inquiryId, ProblemScope scope) {
        Optional<Message> latestAnswer = messageRepository
                .findFirstByInquiry_IdAndSenderTypeAndMessageStatusAndDeletedAtIsNullOrderBySequenceNoDesc(
                        inquiryId, SenderType.AI, MessageStatus.COMPLETED);
        if (latestAnswer.isEmpty() || messageRepository.existsByInquiry_IdAndSenderTypeAndSequenceNoGreaterThanAndDeletedAtIsNull(
                inquiryId, SenderType.USER, latestAnswer.get().getSequenceNo())) {
            return List.of();
        }
        Set<UUID> replied = new LinkedHashSet<>();
        JsonNode payload = jsonMapper.readTree(latestAnswer.get().getPayload());
        payload.path("questionBundles").forEach(bundle -> addProblem(replied, bundle.path("problemId")));
        addProblem(replied, payload.path("guide").path("problemId"));
        replied.retainAll(scope.linkable());
        if (replied.isEmpty() && scope.linkable().size() == 1) {
            return List.copyOf(scope.linkable());
        }
        return List.copyOf(replied);
    }

    private static void addProblem(Set<UUID> problems, JsonNode id) {
        if (id.isString()) {
            try {
                problems.add(UUID.fromString(id.asString()));
            } catch (IllegalArgumentException ignored) {
                // 형식이 맞지 않는 예전 기록은 연결 근거로 쓰지 않는다
            }
        }
    }

    private Message userMessage(UUID inquiryId, UUID messageId) {
        return messageRepository.findById(messageId)
                .filter(found -> found.getInquiry().getId().equals(inquiryId) && !found.isDeleted()
                        && found.getSenderType() == SenderType.USER && found.getMessageStatus() == MessageStatus.COMPLETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /**
     * 이 문의에서 보완할 수 있는 문제와, 그중 티켓이 배정된 문제.
     * 해결됐거나(RESOLVED) 지원 범위 밖(OUT_OF_SCOPE)인 문제는 대상이 될 수 없다. 완료한 티켓·문제를 다시 열지 않는다
     * (TARGET_CONTRACT 4절 "완료 티켓의 이후 같은 현상은 새 문의 진입을 안내하고 재오픈하지 않는다"). 브라우저가 선택지에서 빼더라도 서버가 막는다.
     */
    private record ProblemScope(Set<UUID> linkable, Set<UUID> assigned) {
    }

    private ProblemScope scopeOf(UUID inquiryId) {
        Set<UUID> linkable = new HashSet<>();
        Set<UUID> assigned = new HashSet<>();
        for (InquiryProblem problem : inquiryProblemRepository.findByInquiry_IdAndDeletedAtIsNullOrderByProblemNo(inquiryId)) {
            Optional<Ticket> ticket = ticketRepository.findByInquiryProblem_Id(problem.getId()).filter(t -> !t.isDeleted());
            String state = ProblemStateView.of(problem, ticket, false);
            if ("RESOLVED".equals(state) || "OUT_OF_SCOPE".equals(state)) {
                continue;
            }
            linkable.add(problem.getId());
            if (ticket.isPresent()) {
                assigned.add(problem.getId());
            }
        }
        return new ProblemScope(linkable, assigned);
    }

    /**
     * 입력이 바뀐 뒤 분석을 이어 갈지 정한다. 진행 중인 이전 실행은 언제나 대체한다(입력 버전이 바뀌어 그 결과는 버려진다).
     * <ul>
     *   <li>이번 입력의 자기 분석: 시작 메시지가 대기 중이거나 대상이 모두 배정된 문제면 없다(보완 정보). 그 밖에는 이번 대상으로 분석한다.</li>
     *   <li>멈춘 분석(이번에 대체한 실행, 또는 대체만 되고 다시 접수되지 않은 현재 실행)이 있으면 그 대상을 새 실행에 합친다.
     *       보완 전용 입력 때문에 다른 문제의 분석이 사라지지 않게 한다. 그 사이 해결·범위 밖이 된 문제는 잇지 않는다.</li>
     * </ul>
     *
     * @param requestId 이 입력을 저장한 공개 명령. 새 실행을 접수하면 그 실행의 접수 명령으로 기록한다
     */
    private void continueAnalysis(Inquiry inquiry, Message trigger, List<UUID> problemIds, ProblemScope scope,
            UUID requestId) {
        Instant now = clock.instant();
        AgentRun previous = inquiry.getCurrentRun();
        Optional<List<UUID>> interrupted = interruptedTargets(previous);
        if (previous != null && isActive(previous)) {
            previous.finish(RunStatus.CANCELLED, now);
        }
        boolean pending = jsonMapper.readValue(trigger.getPayload(), MessagePayload.class).isPendingTarget();
        boolean ownAnalysis = !pending && (problemIds.isEmpty() || !scope.assigned().containsAll(problemIds));
        if (interrupted.isEmpty()) {
            if (ownAnalysis) {
                startAnalysis(inquiry, trigger, problemIds, now, requestId);
            }
            return;
        }
        // 대상 없이 하던 분석이 멈췄으면 대상 없이 다시 한다
        if (interrupted.get().isEmpty()) {
            startAnalysis(inquiry, trigger, ownAnalysis ? problemIds : List.of(), now, requestId);
            return;
        }
        Set<UUID> targets = new LinkedHashSet<>();
        if (ownAnalysis) {
            targets.addAll(problemIds);
        }
        interrupted.get().stream().filter(scope.linkable()::contains).forEach(targets::add);
        if (!targets.isEmpty()) {
            startAnalysis(inquiry, trigger, List.copyOf(targets), now, requestId);
        }
    }

    /**
     * 이번 입력으로 멈추는 분석의 대상 문제. 진행 중인 현재 실행, 또는 대체만 되고 다시 접수되지 않은 현재 실행이다
     * (새 실행을 접수하면 currentRun이 바뀌므로 대체된 실행이 현재 실행으로 남아 있으면 이어 가지 못한 것이다).
     * 비어 있으면 멈추는 분석이 없고, 빈 목록이면 대상 없이 하던 분석이다.
     */
    private Optional<List<UUID>> interruptedTargets(@Nullable AgentRun run) {
        if (run == null || (!isActive(run) && run.getRunStatus() != RunStatus.CANCELLED)) {
            return Optional.empty();
        }
        List<UUID> targets = new ArrayList<>();
        jsonMapper.readTree(run.getInputSnapshot()).path("problemIds")
                .forEach(id -> targets.add(UUID.fromString(id.asString())));
        return Optional.of(List.copyOf(targets));
    }

    private static boolean isActive(AgentRun run) {
        return run.getRunStatus() == RunStatus.QUEUED || run.getRunStatus() == RunStatus.RUNNING;
    }

    // 문의 행을 잠그고, 화면이 본 버전이며 아직 끝나지 않은 문의인지 확인한다
    private Inquiry lockOpenInquiry(UUID requesterId, UUID inquiryId, int expectedInquiryVersion) {
        Inquiry inquiry = inquiryRepository.findByIdAndDeletedAtIsNull(inquiryId)
                .filter(found -> found.getRequester().getId().equals(requesterId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (inquiry.getVersion() != expectedInquiryVersion) {
            throw new BusinessException(ErrorCode.INQUIRY_VERSION_CONFLICT);
        }
        if (isClosed(inquiry)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "종료된 문의입니다. 같은 현상은 새 문의로 접수해 주세요.");
        }
        return inquiry;
    }

    /**
     * 새 입력 버전의 실행을 접수한다: 새 실행·불변 입력 → currentRun 연결 → 시작 메시지에 접수 명령 기록 → commit 뒤 전달.
     * 대기 메시지(보완 대상 미정)는 대상이 정해질 때까지 AI 입력의 대화에서 뺀다.
     */
    private void startAnalysis(Inquiry inquiry, Message trigger, List<UUID> problemIds, Instant now, UUID requestId) {
        AgentRun run = agentRunRepository.saveAndFlush(new AgentRun(inquiry, trigger, inquiry.getVersion(), "{}",
                runProperties.modelVersion(), runProperties.promptVersion(), runProperties.schemaVersion()));
        List<Message> messages = messageRepository.findAllCompletedInOrder(inquiry.getId()).stream()
                .filter(message -> !jsonMapper.readValue(message.getPayload(), MessagePayload.class).isPendingTarget())
                .toList();
        run.prepareInput(analysisInputFactory.snapshot(run, inquiry, messages, inputProblemReader.read(inquiry, problemIds),
                problemIds, systemQueryService.findSupportedSystems(), now));
        trigger.updatePayload(UserMessagePayload.withRun(jsonMapper, trigger.getPayload(), run.getId(), requestId));
        inquiry.resumeAnalysis(run);
        eventPublisher.publishEvent(new AgentRunQueued(run.getId()));
    }

    private static boolean isClosed(Inquiry inquiry) {
        return inquiry.getInquiryStatus() == InquiryStatus.RESOLVED || inquiry.getInquiryStatus() == InquiryStatus.CLOSED;
    }
}
