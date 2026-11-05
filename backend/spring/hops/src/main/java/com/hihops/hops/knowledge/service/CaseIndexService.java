package com.hihops.hops.knowledge.service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.hihops.hops.agent.client.AgentRunClient;
import com.hihops.hops.agent.client.AgentRunClientException;
import com.hihops.hops.agent.service.AgentDispatchConfig;
import com.hihops.hops.agent.service.AgentDispatchProperties;
import com.hihops.hops.knowledge.domain.CaseStatus;
import com.hihops.hops.knowledge.domain.IndexStatus;
import com.hihops.hops.knowledge.domain.IndexStore;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentVersionRepository;
import com.hihops.hops.knowledge.repository.ResolvedCaseRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 승인된 사례 색인 요청의 전달·확인 (contracts/agents I1.1). 요청 저장은 승인·재시도 거래가 맡고, 이 클래스는 commit 뒤에 일한다.
 *
 * <p>규칙:
 * <ul>
 *   <li>AI를 기다리는 동안 DB 거래·사례 행 잠금을 잡지 않는다. 호출은 전용 스레드에서 하고 결과 반영만 짧은 거래로 한다.
 *   <li>결과는 저장한 현재 요청(jobId·attempt·digest)과 같을 때만, 아직 끝나지 않은 상태(PENDING·RUNNING·UNKNOWN)에만 반영한다.
 *       늦게 온 이전 attempt 결과나 이미 확정된 상태를 덮어쓰지 않는다.
 *   <li>전달(DELIVER)이 실패해도 승인은 유지하고 상태를 바꾸지 않는다. 담당자의 확인(CHECK)이 복구 경로다.
 *   <li>확인(CHECK)은 먼저 같은 작업을 조회한다. 형식이 유효한 NOT_FOUND일 때만 보존한 같은 요청을 한 번 다시 접수하고,
 *       그 밖의 실패·불일치는 UNKNOWN으로 두며 다시 보내지 않는다. 새 jobId·attempt를 만들지 않는다.
 * </ul>
 */
@Service
public class CaseIndexService {
    private static final Logger log = LoggerFactory.getLogger(CaseIndexService.class);
    private static final Set<String> STATES = Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "UNKNOWN");

    private final ResolvedCaseRepository cases;
    private final KnowledgeDocumentVersionRepository versions;
    private final AgentRunClient client;
    private final JsonMapper mapper;
    private final TaskExecutor executor;
    private final AgentDispatchProperties properties;
    private final TransactionTemplate transaction;

    public CaseIndexService(ResolvedCaseRepository cases, KnowledgeDocumentVersionRepository versions,
            AgentRunClient client, JsonMapper mapper,
            @Qualifier(AgentDispatchConfig.AGENT_DISPATCH_EXECUTOR) TaskExecutor executor,
            AgentDispatchProperties properties, PlatformTransactionManager manager) {
        this.cases = cases;
        this.versions = versions;
        this.client = client;
        this.mapper = mapper;
        this.executor = executor;
        this.properties = properties;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void requested(CaseIndexRequested event) {
        if (!properties.dispatchEnabled()) {
            return;
        }
        try {
            executor.execute(() -> run(event));
        } catch (TaskRejectedException exception) {
            // 요청은 저장돼 있다. 대기열이 찼을 뿐이므로 상태를 바꾸지 않고 담당자의 확인에 맡긴다.
            log.warn("색인 전달 대기열이 가득 찼습니다: caseId={}, store={}", event.caseId(), event.store());
        }
    }

    void run(CaseIndexRequested event) {
        try {
            if (event.mode() == CaseIndexRequested.Mode.DELIVER) {
                deliver(event);
            } else {
                check(event);
            }
        } catch (RuntimeException exception) {
            log.warn("사례 색인 처리에 실패했습니다: caseId={}, store={}, mode={}", event.caseId(), event.store(),
                    event.mode(), exception);
        }
    }

    private void deliver(CaseIndexRequested event) {
        JsonNode request = currentRequest(event).orElse(null);
        if (request == null) {
            return;
        }
        JsonNode snapshot;
        try {
            snapshot = client.applyIndex(request);
        } catch (AgentRunClientException exception) {
            log.warn("AI가 색인 접수를 받지 못했습니다: caseId={}, store={}, code={}", event.caseId(), event.store(),
                    exception.code());
            return;
        }
        if (matches(snapshot, request)) {
            apply(event, request, IndexStatus.valueOf(snapshot.path("state").asString()));
        }
    }

    private void check(CaseIndexRequested event) {
        JsonNode request = currentRequest(event).orElse(null);
        if (request == null) {
            return;
        }
        JsonNode snapshot;
        try {
            snapshot = client.indexSnapshot(request.path("jobId").asString(), request.path("attempt").asInt());
        } catch (AgentRunClientException exception) {
            if (!"NOT_FOUND".equals(exception.code()) || exception.httpStatus() != 404) {
                apply(event, request, IndexStatus.UNKNOWN);
                return;
            }
            // 조회 사이 권한·버전·현재 attempt가 바뀌었으면 중단한다. 같으면 저장한 요청 전체를 한 번 다시 접수한다.
            JsonNode again = currentRequest(event).orElse(null);
            if (again == null || !again.equals(request)) {
                return;
            }
            try {
                snapshot = client.applyIndex(request);
            } catch (AgentRunClientException redelivery) {
                apply(event, request, IndexStatus.UNKNOWN);
                return;
            }
        }
        apply(event, request, matches(snapshot, request) ? IndexStatus.valueOf(snapshot.path("state").asString())
                : IndexStatus.UNKNOWN);
    }

    /** 승인된 사례의 저장소별 현재 요청. 이벤트의 작업·attempt와 다르면(새 attempt가 생김) 없는 것으로 본다. */
    private Optional<JsonNode> currentRequest(CaseIndexRequested event) {
        return transaction.execute(status -> {
            var resolved = cases.findById(event.caseId()).filter(c -> c.getCaseStatus() == CaseStatus.APPROVED
                    && c.getDeletedAt() == null);
            if (resolved.isEmpty() || !resolved.get().indexStatus(event.store()).isOpen()) {
                return Optional.<JsonNode>empty();
            }
            return storedRequest(event.caseId(), resolved.get().getVersionNo(), event.store())
                    .filter(request -> event.jobId().equals(request.path("jobId").asString(""))
                            && request.path("attempt").asInt(-1) == event.attempt());
        });
    }

    private Optional<JsonNode> storedRequest(UUID caseId, int caseVersion, IndexStore store) {
        return versions.findCaseVersion(caseId.toString(), caseVersion).map(version -> mapper
                .readTree(version.getSourceMetadata()).path("indexRequests").path(store.name()))
                .filter(JsonNode::isObject);
    }

    private void apply(CaseIndexRequested event, JsonNode request, IndexStatus next) {
        transaction.executeWithoutResult(status -> {
            var locked = cases.findForUpdate(event.caseId()).filter(c -> c.getCaseStatus() == CaseStatus.APPROVED);
            if (locked.isEmpty()) {
                return;
            }
            var resolved = locked.get();
            // 잠근 뒤 현재 요청이 그대로여야 한다. 새 attempt가 저장됐으면 이전 결과는 버린다.
            boolean current = storedRequest(event.caseId(), resolved.getVersionNo(), event.store())
                    .filter(stored -> stored.equals(request)).isPresent();
            if (current && resolved.indexStatus(event.store()).isOpen()) {
                resolved.updateIndexStatus(event.store(), next);
            }
        });
    }

    /** 응답이 저장한 요청의 작업·버전·저장소·attempt·digest와 같고 계약 상태·오류 규칙을 지킬 때만 받는다. */
    private static boolean matches(JsonNode snapshot, JsonNode request) {
        if (snapshot == null || !snapshot.isObject() || !"hops-system-1".equals(snapshot.path("contractVersion").asString(""))) {
            return false;
        }
        for (String field : new String[] {"jobId", "caseId", "store", "contentDigest"}) {
            if (!request.path(field).asString("").equals(snapshot.path(field).asString(null))) {
                return false;
            }
        }
        String state = snapshot.path("state").asString("");
        return snapshot.path("caseVersion").asInt(-1) == request.path("caseVersion").asInt()
                && snapshot.path("attempt").asInt(-1) == request.path("attempt").asInt() && STATES.contains(state)
                && ("SUCCEEDED".equals(state) ? snapshot.path("error").isNull() : !"FAILED".equals(state)
                        || snapshot.path("error").isObject());
    }
}
