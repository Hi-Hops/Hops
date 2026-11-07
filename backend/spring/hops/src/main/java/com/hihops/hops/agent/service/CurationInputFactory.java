package com.hihops.hops.agent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.dto.AiRunRequest;
import com.hihops.hops.agent.dto.AiRunRequest.AccessContext;
import com.hihops.hops.agent.dto.AiRunRequest.ExistingWorkItem;
import com.hihops.hops.agent.dto.AiRunRequest.InputProblem;
import com.hihops.hops.agent.dto.AiRunRequest.StandardRequestBody;
import com.hihops.hops.agent.dto.AiRunRequest.InputMessage;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.inquiry.repository.StandardRequestRepository;
import com.hihops.hops.itsystem.service.ItSystemService;
import com.hihops.hops.ticket.domain.TicketWorkLog;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.domain.WorkLogType;
import com.hihops.hops.ticket.repository.TicketEventRepository;
import com.hihops.hops.ticket.repository.TicketWorkLogRepository;
import com.hihops.hops.ticket.service.RelatedMessageProjector;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;
import com.hihops.hops.ticket.service.TicketKnowledgeReader;
import com.hihops.hops.ticket.service.TicketStandardRequestReader;
import com.hihops.hops.user.domain.Member;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원 처리 기록을 불변 CASE_DRAFT 입력으로 고정한다. 전달은 공통 Dispatcher가 담당한다.
 *
 * <p>입력은 원 기록 한 건이 아니라 해당 문제의 문의 전체 과정을 담는다(#198). 사용자 메시지와 AI 질문·답변,
 * 같은 티켓의 다른 처리 기록, 해결 확인 이력, 상담에 쓰인 근거다. 모두 이 문제에 연결된 구간만 담고 실행 주체의
 * 현재 권한으로 다시 확인한 값이며, 개수·길이 상한을 둔다. 사례의 조치·검증·원인·출처는 계속 원 기록과 사용자 원문만 쓴다.
 */
@Component
@RequiredArgsConstructor
public class CurationInputFactory {
    private static final Logger log = LoggerFactory.getLogger(CurationInputFactory.class);

    /** 맥락 입력의 상한. AI wire(WorkRecordContext)의 상한과 같다. 넘으면 오래된 것부터 뺀다. */
    static final int MAX_AI_MESSAGES = 30;
    static final int MAX_RELATED_RECORDS = 20;
    static final int MAX_RESOLUTION_HISTORY = 20;
    static final int MAX_CONSULTED_KNOWLEDGE = 10;
    /** 맥락 처리 기록의 문장 길이 상한(Unicode 코드 포인트). AI wire와 같다. */
    static final int MAX_RECORD_TEXT = 4000;

    private final AnalysisRunProperties properties;
    private final TicketStandardRequestReader standardRequests;
    private final ItSystemService systems;
    private final JsonMapper mapper;
    private final MessageRepository messages;
    private final RelatedMessageProjector relatedMessages;
    private final TicketWorkLogRepository workLogs;
    private final TicketEventRepository ticketEvents;
    private final StandardRequestRepository standardRequestRows;
    private final TicketKnowledgeReader knowledge;

    /**
     * AI wire의 WorkRecordContext. 앞 12개는 원 처리 기록이고 뒤 3개는 문의 전체 과정의 맥락이다(모두 가산 필드).
     * 맥락은 오래된 순이며 원 기록과 원 기록의 확인 요청은 포함하지 않는다.
     */
    public record WorkRecord(UUID id, UUID ticketId, UUID problemId, UUID systemId, String kind,
            String action, String verification, String confirmedCause, String appliesToSystemVersion,
            String createdAt, UUID resolutionRequestId, String confirmationState,
            List<RelatedRecord> relatedRecords, List<ResolutionEntry> resolutionHistory,
            List<KnowledgeEvidenceResponse> consultedKnowledge) {}

    /** 같은 티켓의 다른 처리 기록. 진행·실패 시도도 담지만 사례의 조치·검증·원인으로 쓰이지 않는다. */
    public record RelatedRecord(UUID id, String kind, String action, @Nullable String verification,
            @Nullable String confirmedCause, @Nullable String appliesToSystemVersion, String createdAt) {}

    /** 이 문제에 문의자에게 보낸 해결 확인 요청 한 건의 최신 상태. */
    public record ResolutionEntry(UUID requestId, String source, UUID sourceId, String state, String requestedAt,
            @Nullable String respondedAt) {}

    public String snapshot(AgentRun run, TicketWorkLog record, ResolutionRequestResponse confirmation,
            Member actor, Instant now) {
        var ticket = record.getTicket();
        var problem = ticket.getInquiryProblem();
        var system = ticket.getResponsibleSystem();
        var standard = standardRequests.read(ticket.getStandardRequest(), actor);
        var body = new StandardRequestBody(standard.summary(), standard.confirmedFacts(), standard.assumptions(),
                standard.unknowns(), standard.responsibleSystemCandidateId(), standard.responsibilityEvidenceIds(),
                standard.details(), standard.knowledge());
        var inputProblem = new InputProblem(problem.getId(), ticket.getTitle(), ticket.getStandardRequest().getIntent().name(),
                ticket.getWorkType().name(), ticket.getStandardRequest().getReportedSystem().getId(), null,
                "WAITING_CONFIRMATION", problem.getClarifyCount(), List.of(), body, null);
        var inquiryMessages = messages.findAllCompletedInOrder(ticket.getInquiry().getId());
        var work = new WorkRecord(record.getId(), ticket.getId(), problem.getId(), system.getId(),
                kind(record.getLogType()), record.getActionDetail(), record.getVerificationResult(),
                record.getConfirmedCause(), record.getAppliedVersion(), AiRunRequest.time(record.getCreatedAt()),
                confirmation.id(), confirmation.state(),
                relatedRecords(ticket, record), resolutionHistory(ticket, inquiryMessages, confirmation.id()),
                consultedKnowledge(problem.getId(), actor));
        var request = new AiRunRequest(properties.schemaVersion(), run.getId(), "CASE_DRAFT", ticket.getInquiry().getId(),
                run.getInputVersion(), null, List.of(problem.getId()), sourceMessages(ticket, inquiryMessages), List.of(inputProblem),
                List.of(systems.responses(List.of(system)).get(system.getId())),
                new AccessContext(actor.getId(), "CASE_DRAFT", List.of(system.getId()), List.of("SYSTEM_STAFF"), actor.getPolicyRevision()),
                List.of(new ExistingWorkItem(problem.getId(), problem.getId(), run.getInputVersion(), "STORED", ticket.getId())),
                work, AiRunRequest.time(now.plus(properties.deadline())), properties.limits());
        return mapper.writeValueAsString(request);
    }

    /** 계약의 처리 기록 종류 이름. */
    private static String kind(WorkLogType type) {
        return switch (type) {
            case PROGRESS -> "PROGRESS";
            case FAILED_ATTEMPT -> "FAILED_ATTEMPT";
            case RESOLVED -> "VERIFIED_RESOLUTION";
            case IMPLEMENTED -> "IMPLEMENTED_VERIFIED";
        };
    }

    /** 같은 티켓에서 원 기록보다 먼저 남긴 처리 기록. 가장 최근 MAX_RELATED_RECORDS개를 오래된 순으로 담는다. */
    private List<RelatedRecord> relatedRecords(Ticket ticket, TicketWorkLog origin) {
        var earlier = workLogs.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId()).stream()
                .filter(item -> !item.getId().equals(origin.getId()) && !item.getCreatedAt().isAfter(origin.getCreatedAt()))
                .toList();
        return earlier.subList(Math.max(0, earlier.size() - MAX_RELATED_RECORDS), earlier.size()).stream()
                // 조치 내용은 명령 입력에서 비어 있을 수 없다. 그래도 비어 있으면 문구를 지어내지 않고 그 기록을 뺀다.
                .filter(item -> clip(item.getActionDetail()) != null)
                .map(item -> new RelatedRecord(item.getId(), kind(item.getLogType()), clip(item.getActionDetail()),
                        clip(item.getVerificationResult()), clip(item.getConfirmedCause()), item.getAppliedVersion(),
                        AiRunRequest.time(item.getCreatedAt())))
                .toList();
    }

    /**
     * 이 문제의 해결 확인 이력. 안내에 대한 확인은 AI 메시지에, 처리 기록에 대한 확인은 티켓 이력에 저장돼 있어 둘을 합친다.
     * 같은 요청은 가장 나중 상태 하나만 남기고 원 기록의 확인 요청은 뺀다(workRecord.confirmationState가 현재 상태다).
     * 형식이 맞지 않는 옛 저장값은 사례 초안의 맥락일 뿐이므로 처리 기록 저장을 막지 않고 건너뛰되 로그에 남긴다.
     */
    private List<ResolutionEntry> resolutionHistory(Ticket ticket, List<Message> inquiryMessages, UUID currentRequest) {
        UUID problemId = ticket.getInquiryProblem().getId();
        Map<UUID, ResolutionRequestResponse> latest = new LinkedHashMap<>();
        for (var message : inquiryMessages) {
            if (message.getSenderType() == SenderType.AI) {
                collect(latest, mapper.readTree(message.getPayload()).path("resolutionRequest"), problemId);
            }
        }
        for (var event : ticketEvents.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId())) {
            if (event.getAfterData() != null) {
                collect(latest, mapper.readTree(event.getAfterData()).path("resolutionRequest"), problemId);
            }
        }
        latest.remove(currentRequest);
        var ordered = latest.values().stream()
                .sorted(Comparator.comparing(ResolutionRequestResponse::createdAt).thenComparing(ResolutionRequestResponse::id))
                .toList();
        return ordered.subList(Math.max(0, ordered.size() - MAX_RESOLUTION_HISTORY), ordered.size()).stream()
                .map(item -> new ResolutionEntry(item.id(), item.source(), item.sourceId(), item.state(),
                        AiRunRequest.time(item.createdAt()),
                        item.respondedAt() == null ? null : AiRunRequest.time(item.respondedAt())))
                .toList();
    }

    private void collect(Map<UUID, ResolutionRequestResponse> latest, tools.jackson.databind.JsonNode node, UUID problemId) {
        if (!node.isObject()) return;
        try {
            var request = mapper.treeToValue(node, ResolutionRequestResponse.class);
            if (request != null && problemId.equals(request.problemId())) {
                // 같은 요청의 나중 저장값(응답 반영)이 앞선 값을 대체한다. 저장 순서대로 읽으므로 마지막이 최신이다.
                latest.remove(request.id());
                latest.put(request.id(), request);
            }
        } catch (RuntimeException exception) {
            log.warn("해결 확인 이력을 읽지 못해 건너뜁니다: problemId={}, {}", problemId, exception.getClass().getSimpleName());
        }
    }

    /**
     * 이 문제의 상담에 쓰인 근거. 문제의 모든 적용된 요청서(안내 때와 티켓 생성 때)에서 최신 순으로 모으되
     * 실행 주체의 현재 권한·승인 상태로 다시 확인한 것만 담는다(TicketKnowledgeReader). DB 장애는 빈 목록으로 숨기지 않는다.
     */
    private List<KnowledgeEvidenceResponse> consultedKnowledge(UUID problemId, Member actor) {
        Map<UUID, KnowledgeEvidenceResponse> byId = new LinkedHashMap<>();
        for (var request : standardRequestRows.findAppliedNewestFirst(List.of(problemId))) {
            for (var item : knowledge.visible(request.getRelatedKnowledge(), actor)) {
                byId.putIfAbsent(item.id(), item);
            }
            if (byId.size() >= MAX_CONSULTED_KNOWLEDGE) break;
        }
        return byId.values().stream().limit(MAX_CONSULTED_KNOWLEDGE).toList();
    }

    /** 맥락 문장을 AI wire의 상한에 맞춘다. 비어 있는 값은 null로 보내 계약의 "공백만 있는 문자열 거부"에 걸리지 않게 한다. */
    private static @Nullable String clip(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        if (value.codePointCount(0, value.length()) <= MAX_RECORD_TEXT) return value;
        return value.substring(0, value.offsetByCodePoints(0, MAX_RECORD_TEXT));
    }

    /**
     * 전체 문의가 아니라 해당 문제에 연결된 구간만 실행 입력에 고정한다. 사용자 메시지는 모두, AI 질문·답변은 이 문제에
     * 연결된 구간만 가장 최근 MAX_AI_MESSAGES개를 담는다. 사례의 출처는 사용자 메시지만 허용하며 AI 메시지는 과정을 이해하는 맥락이다.
     */
    private List<InputMessage> sourceMessages(Ticket ticket, List<Message> inquiryMessages) {
        UUID problemId = ticket.getInquiryProblem().getId();
        var projected = inquiryMessages.stream()
                .filter(message -> !mapper.readTree(message.getPayload()).path("pendingTarget").asBoolean(false))
                .flatMap(message -> relatedMessages.project(message, problemId, List.of()).stream()
                        .map(visible -> new InputMessage(message.getId(), message.getRevision(), message.getSequenceNo(),
                                message.getSenderType(), visible.content(), List.of(problemId), AiRunRequest.time(message.getCreatedAt()))))
                .toList();
        long dropped = Math.max(0, projected.stream().filter(item -> item.sender() == SenderType.AI).count() - MAX_AI_MESSAGES);
        var kept = new ArrayList<InputMessage>(projected.size());
        for (var item : projected) {
            if (item.sender() == SenderType.AI && dropped > 0) {
                dropped--;
                continue;
            }
            kept.add(item);
        }
        return kept;
    }
}
