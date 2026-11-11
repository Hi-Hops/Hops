package com.hihops.hops.agent.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.agent.dto.AiRunRequest;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.repository.InquiryRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문의 잠금 아래 한 문제의 생성과 전달 journal을 원자적으로 commit한다.
 * HTTP 재전송은 저장 결과만 반환하고 명시적 재시도만 횟수를 소비한다. 외부 서비스는 호출하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TicketCreationService {
    private static final String CONTRACT = RunChecks.CONTRACT;
    private final AgentRunRepository runs;
    private final InquiryRepository inquiries;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final TicketCreationValidator validator;
    private final EntityManager entities;
    private final Clock clock;

    @Transactional
    public TicketCreationResult create(UUID runId, TicketCreationCommand command) {
        Context ctx = context(runId);
        String ref = command.problem().problemRef();
        var previous = receipt(runId, ref);
        if (previous != null) {
            if (!mapper.readTree(previous.body()).equals(mapper.valueToTree(command))) conflict();
            return result(ctx, previous);
        }
        writable(ctx, command.inquiryVersion());
        validator.validate(command, ctx.input(), ctx.inquiry().getRequester());
        checkBudget(ctx);
        UUID problemId = problem(ctx, command);
        String body = mapper.writeValueAsString(command);
        jdbc.update("INSERT INTO agent_ticket_request(run_id, problem_ref, problem_id, request_body) VALUES (?, ?, ?, ?::jsonb)",
                runId, ref, problemId, body);
        var stored = receipt(runId, ref);
        // 이미 저장된 티켓은 재생성하지 않는다.
        if (!hasTicket(problemId)) attempt(ctx, stored, command);
        return result(ctx, stored);
    }

    @Transactional
    public TicketCreationResult get(UUID runId, String ref) {
        Context ctx = context(runId);
        var receipt = receipt(runId, ref);
        if (receipt == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return result(ctx, receipt);
    }

    @Transactional
    public TicketCreationResult retry(UUID runId, String ref, TicketCreationCommand.Retry command) {
        Context ctx = context(runId);
        var saved = receipt(runId, ref);
        if (saved == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        var previous = result(ctx, saved);
        if (command.inquiryVersion() != previous.inquiryVersion()) conflict();
        if (command.expectedAttempt() > saved.attempt()) conflict();
        // 결과 유실 뒤 같은 expectedAttempt 재전달은 읽기로 취급한다.
        if (command.expectedAttempt() < saved.attempt() || "STORED".equals(previous.outcome())) return previous;
        writable(ctx, command.inquiryVersion());
        if (!previous.retryable()) throw new BusinessException(ErrorCode.INVALID_STATE);
        checkBudget(ctx);
        var original = mapper.readValue(saved.body(), TicketCreationCommand.class);
        validator.validate(original, ctx.input(), ctx.inquiry().getRequester());
        jdbc.update("UPDATE agent_ticket_request SET attempt=attempt+1, updated_at=? WHERE run_id=? AND problem_ref=?",
                java.sql.Timestamp.from(clock.instant()), runId, ref);
        var next = receipt(runId, ref);
        attempt(ctx, next, original);
        return result(ctx, next);
    }

    /**
     * 문의자의 티켓 생성 재시도 (WI-02). 저장해 둔 AI 요청으로 같은 작업을 다시 만든다.
     *
     * <p>AI의 재시도와 달리 실행이 이미 끝났어도 가능하고 AI 재시도 횟수를 쓰지 않는다. 대상이 NOT_CREATED이고 문의 버전이
     * 같은지는 호출자가 문의 행 잠금 아래에서 확인한 뒤 부른다. 책임자가 여전히 없으면 작업은 NOT_CREATED로 남는다.
     *
     * @throws BusinessException WORK_ITEM_NOT_RETRYABLE 저장된 요청이 없을 때
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retryByRequester(UUID problemId, Inquiry inquiry) {
        var saved = jdbc.query("""
                SELECT run_id,problem_ref,problem_id,request_body::text,attempt FROM agent_ticket_request
                WHERE problem_id=? ORDER BY created_at DESC, run_id LIMIT 1
                """, (rs, n) -> new Saved(rs.getObject(1, UUID.class),
                        new Receipt(rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getInt(5))), problemId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.WORK_ITEM_NOT_RETRYABLE));
        AgentRun run = runs.findById(saved.runId()).orElseThrow(() -> new BusinessException(ErrorCode.WORK_ITEM_NOT_RETRYABLE));
        var ctx = new Context(run, inquiry, mapper.readValue(run.getInputSnapshot(), AiRunRequest.class));
        var original = mapper.readValue(saved.receipt().body(), TicketCreationCommand.class);
        validator.validate(original, ctx.input(), inquiry.getRequester());
        attempt(ctx, saved.receipt(), original);
    }

    private Context context(UUID runId) {
        AgentRun run = runs.findById(runId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Inquiry inquiry = inquiries.findByIdAndDeletedAtIsNull(run.getInquiry().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // 문의 잠금을 기다리는 사이 취소된 실행의 캐시를 쓰지 않는다.
        entities.refresh(run);
        AiRunRequest input = mapper.readValue(run.getInputSnapshot(), AiRunRequest.class);
        var member = inquiry.getRequester();
        var policies = jdbc.queryForList("""
                SELECT m.policy_revision FROM member m JOIN department d ON d.id=m.department_id
                WHERE m.id=? AND m.deleted_at IS NULL AND d.deleted_at IS NULL FOR SHARE OF m,d
                """, Integer.class, member.getId());
        RunChecks.checkAccess(runId, run, inquiry, input, policies);
        return new Context(run, inquiry, input);
    }

    private void writable(Context ctx, int version) {
        RunChecks.checkActive(ctx.run(), ctx.inquiry(), ctx.input(), version, clock);
    }

    private void checkBudget(Context ctx) {
        Integer used = jdbc.queryForObject("SELECT COALESCE(sum(attempt),0)::int FROM agent_ticket_request WHERE run_id=?",
                Integer.class, ctx.run().getId());
        if (used >= ctx.input().limits().maxToolCalls()) throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
    }

    private UUID problem(Context ctx, TicketCreationCommand command) {
        var p = command.problem();
        UUID id;
        if (p.problemRef().startsWith("new:")) {
            id = jdbc.queryForObject("""
                    INSERT INTO inquiry_problem(inquiry_id, problem_no, title, detail, problem_status, agent_run_id)
                    SELECT ?, COALESCE(max(problem_no),0)+1, ?, ?, 'TICKET_FAILED', ? FROM inquiry_problem WHERE inquiry_id=?
                    RETURNING id
                    """, UUID.class, ctx.inquiry().getId(), command.proposal().title(), p.summary(),
                    ctx.run().getId(), ctx.inquiry().getId());
        } else {
            id = UUID.fromString(p.problemRef());
            var states = jdbc.queryForList("SELECT problem_status FROM inquiry_problem WHERE id=? AND inquiry_id=? AND deleted_at IS NULL",
                    String.class, id, ctx.inquiry().getId());
            if (states.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
            if (hasTicket(id)) return id;
            String outcome = jdbc.queryForObject("SELECT creation_outcome FROM inquiry_problem WHERE id=?", String.class, id);
            if ("PENDING".equals(outcome) || "UNKNOWN".equals(outcome) || "STORED".equals(outcome)) {
                throw new BusinessException(ErrorCode.INVALID_STATE);
            }
            if (List.of("RESOLVED_WITHOUT_TICKET", "OUT_OF_SCOPE", "GUIDED", "TICKET_CREATED").contains(states.getFirst())) {
                throw new BusinessException(ErrorCode.INVALID_STATE);
            }
        }
        // 실제로 존재하는 활성 시스템만 저장한다. FK만으로 삭제 여부를 판단하지 않는다.
        for (UUID system : List.of(p.reportedSystemCandidateId(), command.proposal().responsibleSystemCandidateId())) {
            if (jdbc.queryForList("SELECT id FROM it_system WHERE id=? AND deleted_at IS NULL FOR SHARE", UUID.class, system).isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
        }
        var s = command.proposal().standardRequestSnapshot();
        UUID feature = ctx.input().problems().stream().filter(it -> it.id().toString().equals(p.problemRef()))
                .map(it -> it.reportedFeatureId()).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (feature == null) {
            var reported = ctx.input().systems().stream().filter(system -> system.id().equals(p.reportedSystemCandidateId())).findFirst();
            var catalog = reported.stream().flatMap(system -> system.features().stream()).toList();
            var sources = new java.util.HashMap<UUID, List<String>>();
            for (var link : command.messageLinks()) {
                ctx.input().messages().stream().filter(m -> m.id().equals(link.messageId())
                        && m.sender() == com.hihops.hops.inquiry.domain.SenderType.USER).findFirst().ifPresent(m ->
                    sources.put(m.id(), link.spans().stream().map(span -> m.content().substring(
                            m.content().offsetByCodePoints(0, span.start()), m.content().offsetByCodePoints(0, span.end()))).toList()));
            }
            var explicit = com.hihops.hops.itsystem.service.ExplicitFeatureEvidence.resolve(catalog, p.facts(), sources,
                    reported.map(system -> system.name()).orElse(null));
            if (explicit != null) feature = explicit.id();
        }
        if (feature != null && jdbc.queryForList("SELECT id FROM system_feature WHERE id=? AND it_system_id=? AND deleted_at IS NULL FOR SHARE",
                UUID.class, feature, p.reportedSystemCandidateId()).isEmpty()) throw new BusinessException(ErrorCode.INVALID_INPUT);
        jdbc.queryForObject("""
                INSERT INTO standard_request(inquiry_problem_id,inquiry_version,agent_run_id,intent,work_type,
                    reported_system_id,reported_feature_id,responsible_system_id,summary,system_candidates,confirmed_facts,assumptions,
                    missing_items,type_detail,related_knowledge)
                VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb) RETURNING id
                """, UUID.class, id, command.inquiryVersion(), ctx.run().getId(), p.intent().name(), p.processingType().name(),
                p.reportedSystemCandidateId(), feature, command.proposal().responsibleSystemCandidateId(), p.summary(),
                json(List.of(java.util.Map.of("systemId", s.responsibleSystemCandidateId(), "evidenceIds", responsibilityIds(command)))),
                json(p.facts()), json(p.assumptions()), json(p.unknowns()), json(p.details()), json(s.knowledge()));
        linkMessages(ctx, id, command);
        return id;
    }

    private void linkMessages(Context ctx, UUID problemId, TicketCreationCommand command) {
        for (var link : command.messageLinks()) {
            // 검증된 새 문제 연결만 현재 차수에 추가한다. 기존 payload와 다른 차수의 연결을 보존한다.
            jdbc.update("""
                    UPDATE message SET payload=jsonb_set(payload, '{problemLinks}',
                        COALESCE(payload->'problemLinks','[]'::jsonb) || jsonb_build_array(
                            jsonb_build_object('problemId',?::text,'revision',?,'spans',?::jsonb)), true)
                    WHERE id=? AND inquiry_id=? AND revision=? AND deleted_at IS NULL
                    AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE(payload->'problemLinks','[]'::jsonb)) link
                        WHERE link->>'problemId'=?::text AND (link->>'revision' IS NULL OR (link->>'revision')::int=?))
                    """, problemId, link.revision(), json(link.spans()), link.messageId(), ctx.inquiry().getId(), link.revision(), problemId, link.revision());
        }
    }

    private void attempt(Context ctx, Receipt receipt, TicketCreationCommand command) {
        UUID system = command.proposal().responsibleSystemCandidateId();
        // 시스템·담당자 변경과 생성이 서로 엇갈리지 않게 현재 관계를 행 잠금으로 고정한다.
        var owners = jdbc.queryForList("""
                SELECT m.id FROM it_system s JOIN member m ON m.id=s.primary_owner_member_id
                JOIN department d ON d.id=m.department_id
                WHERE s.id=? AND s.deleted_at IS NULL AND m.deleted_at IS NULL AND d.deleted_at IS NULL
                FOR SHARE OF s,m,d
                """, UUID.class, system);
        if (owners.size() != 1) {
            jdbc.update("UPDATE inquiry_problem SET problem_status='TICKET_FAILED', creation_outcome='NOT_CREATED', creation_input_version=?, ticket_error_code='ASSIGNMENT_UNAVAILABLE', updated_at=? WHERE id=?",
                    command.inquiryVersion(), java.sql.Timestamp.from(clock.instant()), receipt.problemId());
            ctx.inquiry().recordAnalysisResult();
            return;
        }
        UUID standardRequest = jdbc.queryForObject("SELECT id FROM standard_request WHERE inquiry_problem_id=? AND agent_run_id=? AND inquiry_version=?",
                UUID.class, receipt.problemId(), ctx.run().getId(), command.inquiryVersion());
        Long number = jdbc.queryForObject("SELECT nextval('ticket_number_seq')", Long.class);
        UUID ticketId = jdbc.queryForObject("""
                INSERT INTO ticket(ticket_number,inquiry_id,inquiry_problem_id,standard_request_id,title,detail,work_type,
                    responsible_system_id,assignee_member_id) VALUES (?,?,?,?,?,?,?,?,?) RETURNING id
                """, UUID.class, "T-%016d".formatted(number), ctx.inquiry().getId(), receipt.problemId(), standardRequest,
                command.proposal().title(), command.problem().summary(), command.proposal().processingType().name(), system, owners.getFirst());
        jdbc.update("""
                INSERT INTO ticket_assignment(ticket_id,it_system_id,assignee_member_id,source,reason,evidence,assigned_at)
                VALUES (?,?,?,'AUTO','Ticket 에이전트 제안 검증 후 자동 배정',?::jsonb,?)
                """, ticketId, system, owners.getFirst(), json(java.util.Map.of(
                        "messageIds", responsibilityIds(command).stream()
                                .filter(validator.allowedMessages(ctx.input(), receipt.ref())::contains).toList(),
                        "note", "책임 시스템 후보의 지식 근거와 적용 조건은 표준 요청서에 보존")), java.sql.Timestamp.from(clock.instant()));
        jdbc.update("UPDATE inquiry_problem SET problem_status='TICKET_CREATED', creation_outcome='STORED', creation_input_version=?, ticket_error_code=NULL, updated_at=? WHERE id=?",
                command.inquiryVersion(), java.sql.Timestamp.from(clock.instant()), receipt.problemId());
        ctx.inquiry().recordAnalysisResult();
    }

    private boolean hasTicket(UUID problemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ticket WHERE inquiry_problem_id=?)", Boolean.class, problemId));
    }

    private Receipt receipt(UUID runId, String ref) {
        return jdbc.query("SELECT problem_ref,problem_id,request_body::text,attempt FROM agent_ticket_request WHERE run_id=? AND problem_ref=?",
                (rs, n) -> new Receipt(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4)), runId, ref)
                .stream().findFirst().orElse(null);
    }

    private TicketCreationResult result(Context ctx, Receipt receipt) {
        int maximum = Math.min(3, 1 + Math.min(ctx.input().limits().maxRetries(), 2));
        int used = jdbc.queryForObject("SELECT COALESCE(sum(attempt),0)::int FROM agent_ticket_request WHERE run_id=?", Integer.class, ctx.run().getId());
        return jdbc.queryForObject("""
                SELECT p.creation_outcome,COALESCE(p.creation_input_version,s.inquiry_version),p.ticket_error_code,p.updated_at,
                    t.id,t.responsible_system_id,t.assignee_member_id,
                    (t.deleted_at IS NULL AND EXISTS(SELECT 1 FROM ticket_assignment a WHERE a.ticket_id=t.id
                        AND a.it_system_id=t.responsible_system_id AND a.assignee_member_id=t.assignee_member_id
                        AND a.ended_at IS NULL AND a.deleted_at IS NULL)) AS assigned
                FROM inquiry_problem p LEFT JOIN ticket t ON t.inquiry_problem_id=p.id
                LEFT JOIN standard_request s ON s.id=t.standard_request_id WHERE p.id=?
                """, (rs, n) -> {
                    String outcome = rs.getString(1);
                    UUID ticket = rs.getObject(5, UUID.class);
                    if (ticket != null && rs.getBoolean(8)) outcome = "STORED";
                    else if (ticket != null || "STORED".equals(outcome) || outcome == null) outcome = "UNKNOWN";
                    boolean stored = "STORED".equals(outcome);
                    boolean active = ctx.inquiry().getCurrentRun() != null
                            && ctx.inquiry().getCurrentRun().getId().equals(ctx.run().getId())
                            && List.of(RunStatus.QUEUED, RunStatus.RUNNING).contains(ctx.run().getRunStatus())
                            && ctx.inquiry().getVersion() == ctx.run().getInputVersion()
                            && clock.instant().isBefore(Instant.parse(ctx.input().expiresAt()));
                    return new TicketCreationResult(CONTRACT, ctx.run().getId(), receipt.ref(), receipt.problemId(), receipt.problemId(),
                            rs.getInt(2), outcome, stored ? ticket : null, stored ? rs.getObject(6, UUID.class) : null,
                            stored ? rs.getObject(7, UUID.class) : null, receipt.attempt(), maximum,
                            active && "NOT_CREATED".equals(outcome) && "ASSIGNMENT_UNAVAILABLE".equals(rs.getString(3))
                                    && receipt.attempt() < maximum && used < ctx.input().limits().maxToolCalls(),
                            stored ? null : rs.getString(3), rs.getTimestamp(4).toInstant());
                }, receipt.problemId());
    }

    private String json(Object value) { return mapper.writeValueAsString(value); }
    private List<UUID> responsibilityIds(TicketCreationCommand command) {
        var ids = new java.util.LinkedHashSet<>(command.proposal().evidenceIds());
        ids.addAll(command.proposal().standardRequestSnapshot().responsibilityEvidenceIds());
        return List.copyOf(ids);
    }
    private static void conflict() { throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED); }
    private record Context(AgentRun run, Inquiry inquiry, AiRunRequest input) { }
    private record Receipt(String ref, UUID problemId, String body, int attempt) { }
    private record Saved(UUID runId, Receipt receipt) { }
}
