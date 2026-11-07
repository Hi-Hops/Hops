package com.hihops.hops.agent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
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
import com.hihops.hops.relation.service.RelationLinkCandidateReader;
import com.hihops.hops.ticket.domain.TicketWorkLog;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.repository.TicketEventRepository;
import com.hihops.hops.ticket.repository.TicketWorkLogRepository;
import com.hihops.hops.ticket.service.RelatedMessageProjector;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;
import com.hihops.hops.ticket.dto.WorkRecordResponse;
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

    /**
     * 사례 초안 실행이 읽을 수 있는 자료 범위. 실행 주체는 해당 시스템의 현재 주담당자라서 직원 공개 자료와 담당자 전용 자료를
     * 모두 볼 수 있다. 사례 자체의 공개 범위는 따로 {@code SYSTEM_STAFF}로 고정된다(Curator).
     * 시스템 범위({@code accessContext.systemIds})는 책임 시스템 하나로 좁게 유지하고, 그 밖의 시스템 근거는 입력에서 뺀다.
     */
    static final List<String> ALLOWED_SCOPES = List.of("EMPLOYEE", "SYSTEM_STAFF");

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
    private final RelationLinkCandidateReader linkCandidates;

    /**
     * AI wire의 WorkRecordContext. 앞 12개는 원 처리 기록이고 뒤 4개는 가산 필드다. 앞의 3개는 문의 전체 과정의 맥락(오래된 순,
     * 원 기록과 원 기록의 확인 요청은 제외)이고 linkCandidates는 사례를 연결할 수 있는 같은 시스템의 기존 증상·확인 항목이다.
     */
    public record WorkRecord(UUID id, UUID ticketId, UUID problemId, UUID systemId, String kind,
            String action, String verification, String confirmedCause, String appliesToSystemVersion,
            String createdAt, UUID resolutionRequestId, String confirmationState,
            List<RelatedRecord> relatedRecords, List<ResolutionEntry> resolutionHistory,
            List<KnowledgeEvidenceResponse> consultedKnowledge, List<RelationLinkCandidateReader.Candidate> linkCandidates) {}

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
        var inRange = inRange(system.getId());
        var keptKnowledge = standard.knowledge().stream().filter(inRange).toList();
        var body = scoped(standard, keptKnowledge, system.getId());
        var inputProblem = new InputProblem(problem.getId(), ticket.getTitle(), ticket.getStandardRequest().getIntent().name(),
                ticket.getWorkType().name(), ticket.getStandardRequest().getReportedSystem().getId(), null,
                "WAITING_CONFIRMATION", problem.getClarifyCount(), List.of(), body, null);
        var inquiryMessages = messages.findAllCompletedInOrder(ticket.getInquiry().getId());
        var work = new WorkRecord(record.getId(), ticket.getId(), problem.getId(), system.getId(),
                WorkRecordResponse.kind(record.getLogType()), record.getActionDetail(), record.getVerificationResult(),
                record.getConfirmedCause(), record.getAppliedVersion(), AiRunRequest.time(record.getCreatedAt()),
                confirmation.id(), confirmation.state(),
                relatedRecords(ticket, record), resolutionHistory(ticket, inquiryMessages, confirmation.id()),
                consultedKnowledge(problem.getId(), actor, inRange),
                linkCandidates.candidates(system.getId(), actor.getId()));
        var request = new AiRunRequest(properties.schemaVersion(), run.getId(), "CASE_DRAFT", ticket.getInquiry().getId(),
                run.getInputVersion(), null, List.of(problem.getId()), sourceMessages(ticket, inquiryMessages), List.of(inputProblem),
                List.of(systems.responses(List.of(system)).get(system.getId())),
                new AccessContext(actor.getId(), "CASE_DRAFT", List.of(system.getId()), ALLOWED_SCOPES, actor.getPolicyRevision()),
                List.of(new ExistingWorkItem(problem.getId(), problem.getId(), run.getInputVersion(), "STORED", ticket.getId())),
                work, AiRunRequest.time(now.plus(properties.deadline())), properties.limits());
        return mapper.writeValueAsString(request);
    }

    /**
     * 입력에 실을 수 있는 근거인지: 공개 범위가 이 실행의 {@link #ALLOWED_SCOPES} 안이고 시스템이 책임 시스템이거나
     * 시스템 없는 공통 정책이어야 한다. AI의 입력 검증과 결과 적용 확인이 같은 기준으로 근거를 대조하므로 어긋난 근거를
     * 보내면 사례 실행 전체가 거부된다. 보고 시스템이 책임 시스템과 다른 티켓의 보고 시스템 문서가 여기서 빠진다.
     */
    private static Predicate<KnowledgeEvidenceResponse> inRange(UUID systemId) {
        return item -> ALLOWED_SCOPES.contains(item.accessScope())
                && (item.systemId() == null ? "POLICY".equals(item.kind()) : systemId.equals(item.systemId()));
    }

    /**
     * 표준 요청서에서 범위 밖 근거와 그 근거를 가리키는 값을 뺀 본문. 사실이 범위 밖 근거를 인용하면 그 사실을 통째로 빼고
     * (일부 인용만 지우지 않는다. TicketStandardRequestReader가 보이지 않는 근거를 다루는 규칙과 같다), 책임 판단 근거에서도
     * 뺀다. 남겨 두면 AI가 입력에 없는 근거를 가리키는 참조로 보고 실행을 거부한다. 빠진 개수는 로그에 남기고 내용은 남기지 않는다.
     */
    private StandardRequestBody scoped(com.hihops.hops.ticket.dto.StandardRequestResponse standard,
            List<KnowledgeEvidenceResponse> kept, UUID systemId) {
        Set<UUID> keptIds = new HashSet<>();
        kept.forEach(item -> keptIds.add(item.id()));
        Set<UUID> droppedIds = new HashSet<>();
        standard.knowledge().forEach(item -> {
            if (!keptIds.contains(item.id())) droppedIds.add(item.id());
        });
        var facts = standard.confirmedFacts().stream().filter(item -> keptIds.containsAll(item.knowledgeEvidenceIds())).toList();
        var assumptions = standard.assumptions().stream().filter(item -> keptIds.containsAll(item.knowledgeEvidenceIds())).toList();
        var responsibility = standard.responsibilityEvidenceIds().stream().filter(id -> !droppedIds.contains(id)).toList();
        int dropped = droppedIds.size() + (standard.confirmedFacts().size() - facts.size())
                + (standard.assumptions().size() - assumptions.size());
        if (dropped > 0) {
            log.info("사례 초안 입력에서 책임 시스템 범위 밖 근거와 그 인용을 뺍니다: systemId={}, 근거 {}건, 사실 {}건",
                    systemId, droppedIds.size(),
                    (standard.confirmedFacts().size() - facts.size()) + (standard.assumptions().size() - assumptions.size()));
        }
        return new StandardRequestBody(standard.summary(), facts, assumptions, standard.unknowns(),
                standard.responsibleSystemCandidateId(), responsibility, standard.details(), kept);
    }

    /** 같은 티켓에서 원 기록보다 먼저 남긴 처리 기록. 가장 최근 MAX_RELATED_RECORDS개를 오래된 순으로 담는다. */
    private List<RelatedRecord> relatedRecords(Ticket ticket, TicketWorkLog origin) {
        var earlier = workLogs.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId()).stream()
                .filter(item -> !item.getId().equals(origin.getId()) && !item.getCreatedAt().isAfter(origin.getCreatedAt()))
                .toList();
        return earlier.subList(Math.max(0, earlier.size() - MAX_RELATED_RECORDS), earlier.size()).stream()
                // 조치 내용은 명령 입력에서 비어 있을 수 없다. 그래도 비어 있으면 문구를 지어내지 않고 그 기록을 뺀다.
                .filter(item -> clip(item.getActionDetail()) != null)
                .map(item -> new RelatedRecord(item.getId(), WorkRecordResponse.kind(item.getLogType()), clip(item.getActionDetail()),
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
     * 실행 주체의 현재 권한·승인 상태로 다시 확인하고 이 실행의 범위 안인 것만 담는다(TicketKnowledgeReader, inRange).
     * DB 장애는 빈 목록으로 숨기지 않는다.
     */
    private List<KnowledgeEvidenceResponse> consultedKnowledge(UUID problemId, Member actor,
            Predicate<KnowledgeEvidenceResponse> inRange) {
        Map<UUID, KnowledgeEvidenceResponse> byId = new LinkedHashMap<>();
        for (var request : standardRequestRows.findAppliedNewestFirst(List.of(problemId))) {
            for (var item : knowledge.visible(request.getRelatedKnowledge(), actor)) {
                if (inRange.test(item)) byId.putIfAbsent(item.id(), item);
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
