package com.hihops.hops.agent.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.hihops.hops.agent.client.AgentRunClient;
import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.CaseOrigin;
import com.hihops.hops.knowledge.domain.CaseType;
import com.hihops.hops.knowledge.domain.ResolvedCase;
import com.hihops.hops.knowledge.repository.ResolvedCaseRepository;
import com.hihops.hops.ticket.domain.WorkLogType;
import com.hihops.hops.user.repository.MemberRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** commit 후 비동기 전달한다. AI 실패는 별도 거래에 기록해 원 업무를 보존한다. */
@Service
public class AgentRunDispatcher {
    private final AgentRunRepository runs;
    private final ResolvedCaseRepository cases;
    private final MemberRepository members;
    private final ItSystemRepository systems;
    private final AgentRunClient client;
    private final ObjectMapper mapper;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate transaction;
    private final boolean enabled;

    public AgentRunDispatcher(AgentRunRepository runs, ResolvedCaseRepository cases, MemberRepository members,
            ItSystemRepository systems, AgentRunClient client, ObjectMapper mapper, ThreadPoolTaskExecutor agentDispatchExecutor,
            PlatformTransactionManager manager, @Value("${hops.agent.dispatch-enabled:true}") boolean enabled) {
        this.runs=runs; this.cases=cases; this.members=members; this.systems=systems; this.client=client;
        this.mapper=mapper; this.executor=agentDispatchExecutor; this.enabled=enabled;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void queued(AgentRunQueued event) {
        if (!enabled) return;
        try { executor.execute(() -> deliver(event.runId())); }
        catch (TaskRejectedException exception) { fail(event.runId()); }
    }

    private void deliver(UUID id) {
        try {
            JsonNode input = transaction.execute(status -> {
                var run = runs.findById(id).orElseThrow();
                if (run.getRunStatus() != RunStatus.QUEUED) throw new IllegalStateException();
                var body = mapper.readTree(run.getInputSnapshot()); authorize(run, body); return body;
            });
            client.submit(input);
            transaction.executeWithoutResult(status -> runs.findById(id).orElseThrow().start(Instant.now()));
            Instant expires = Instant.parse(input.path("expiresAt").asString());
            while (Instant.now().isBefore(expires)) {
                var snapshot = client.snapshot(id.toString());
                if (List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "SUPERSEDED").contains(snapshot.path("status").asString())) {
                    transaction.executeWithoutResult(status -> complete(runs.findById(id).orElseThrow(), input, snapshot));
                    return;
                }
                if (!List.of("QUEUED", "RUNNING").contains(snapshot.path("status").asString())) throw new IllegalStateException();
                Thread.sleep(250);
            }
            fail(id);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); fail(id);
        } catch (RuntimeException exception) { fail(id); }
    }

    private void authorize(AgentRun run, JsonNode input) {
        var context = input.path("accessContext");
        var actor = members.findWithDepartmentAndRolesByIdAndDeletedAtIsNull(UUID.fromString(context.path("actorId").asString())).orElseThrow();
        if (actor.getDepartment().isDeleted() || actor.getPolicyRevision() != context.path("policyRevision").asInt()) throw new IllegalStateException();
        if (run.getTask() == AgentTask.CURATION) {
            var system = run.getTicket().getResponsibleSystem();
            if (!system.getId().toString().equals(input.path("workRecord").path("systemId").asString())
                    || !systems.findActivePrimaryOwnerId(system.getId()).filter(actor.getId()::equals).isPresent()) throw new IllegalStateException();
        } else if (!run.getInquiry().getRequester().getId().equals(actor.getId())) throw new IllegalStateException();
    }

    private void complete(AgentRun run, JsonNode input, JsonNode snapshot) {
        authorize(run, input);
        var result = snapshot.path("result");
        if (!"SUCCEEDED".equals(snapshot.path("status").asString())) throw new IllegalStateException();
        if (!"hops-system-1".equals(result.path("contractVersion").asString())
                || !run.getId().toString().equals(result.path("runId").asString())
                || !run.getInquiry().getId().toString().equals(result.path("inquiryId").asString())
                || run.getInputVersion() != result.path("inquiryVersion").asInt()
                || !input.path("kind").equals(result.path("kind")) || !"SUCCEEDED".equals(result.path("status").asString())) throw new IllegalStateException();
        var validation = mapper.createObjectNode();
        if (run.getTask() == AgentTask.CURATION) {
            var proposal = result.path("caseProposal");
            if (!proposal.path("reusable").isBoolean()) throw new IllegalStateException();
            if (!proposal.path("reusable").asBoolean()) {
                validation.put("caseGenerationState", "NO_REUSABLE_CASE");
            } else {
                for (String field : List.of("title", "action", "verification", "applicability", "problemStatement")) {
                    if (!proposal.path(field).isString() || proposal.path(field).asString().isBlank()) throw new IllegalStateException();
                }
                String title = proposal.path("title").asString();
                if (title.codePointCount(0, title.length()) > 200 || !List.of("EMPLOYEE", "SYSTEM_STAFF").contains(proposal.path("accessScope").asString())
                        || !proposal.path("sourceMessageIds").isArray() || !proposal.path("sourceMessageIds").isEmpty()) throw new IllegalStateException();
                var record = run.getTriggerWorkLog();
                var draft = new ResolvedCase("C-" + run.getId().toString().replace("-", "").substring(16), record,
                        record.getTicket().getResponsibleSystem(), null,
                        record.getLogType() == WorkLogType.IMPLEMENTED ? CaseType.IMPLEMENTATION : CaseType.RESOLUTION,
                        title, proposal.path("problemStatement").asString(), record.getActionDetail(), record.getVerificationResult(),
                        "EMPLOYEE".equals(proposal.path("accessScope").asString()) ? AccessLevel.EMPLOYEE : AccessLevel.STAFF, CaseOrigin.SYNTHETIC, run);
                draft.recordDraftMetadata(proposal.path("applicability").asString(), record.getConfirmedCause(), record.getAppliedVersion(), "[]");
                cases.save(draft);
            }
        }
        run.storeOutcome(result.toString(), validation.toString(), null);
        run.finish(RunStatus.SUCCEEDED, Instant.now());
    }

    private void fail(UUID id) {
        transaction.executeWithoutResult(status -> runs.findById(id).ifPresent(run -> {
            if (run.getRunStatus() == RunStatus.QUEUED || run.getRunStatus() == RunStatus.RUNNING) {
                run.storeOutcome(null, null, "SERVICE_UNAVAILABLE"); run.finish(RunStatus.FAILED, Instant.now());
            }
        }));
    }
}
