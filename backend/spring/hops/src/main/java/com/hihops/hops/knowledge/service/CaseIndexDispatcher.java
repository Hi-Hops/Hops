package com.hihops.hops.knowledge.service;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import com.hihops.hops.knowledge.domain.*;
import com.hihops.hops.ticket.service.TicketStoredData;
import org.springframework.context.ApplicationEventPublisher;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** commit 후 전달. 새 프로세스가 자동 복원하지 않으며 보존한 요청은 담당자 확인으로만 재전달한다. */
@Service
public class CaseIndexDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CaseIndexDispatcher.class);
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final CaseAccess access;
    private final CaseService cases;
    private final CaseIndexClient client;
    private final TicketStoredData data;
    private final JsonMapper mapper;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate transactions;
    public CaseIndexDispatcher(CaseAccess access, CaseService cases, CaseIndexClient client, TicketStoredData data,
            JsonMapper mapper, Clock clock, ApplicationEventPublisher publisher, PlatformTransactionManager manager) {
        this.access = access; this.cases = cases; this.client = client; this.data = data; this.mapper = mapper;
        this.clock = clock; this.publisher = publisher;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @TransactionalEventListener
    public void requested(CaseIndexRequested event) { executor.execute(() -> dispatch(event, System.nanoTime(), !event.check())); }
    private void dispatch(CaseIndexRequested event, long started, boolean send) {
        ObjectNode request = null;
        try {
            request = current(event);
            if (request == null) return;
            var response = send ? submit(event, request) : client.send(request, true);
            if (response.notFound()) {
                // 권한과 현재 시도가 바뀌었으면 재전달하지 않는다.
                var current = current(event);
                if (current == null || !current.equals(request)) return;
                response = submit(event, request);
            }
            apply(event, request, response.snapshot());
            if (!event.check() && List.of("PENDING", "RUNNING").contains(response.snapshot().path("state").asString())) {
                if (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30)) {
                    executor.schedule(() -> dispatch(event, started, false), 5, TimeUnit.SECONDS);
                } else unknown(event, request);
            }
        } catch (RuntimeException exception) {
            log.warn("사례 색인 결과 미확인: {}", exception.getClass().getSimpleName());
            if (request != null) {
                try { unknown(event, request); } catch (RuntimeException ignored) { /* 승인과 보존 요청은 유지한다. */ }
            }
        }
    }
    private ObjectNode current(CaseIndexRequested event) {
        return transactions.execute(transaction -> {
            var value = access.lock(event.actor(), event.caseId());
            if (value.getCaseStatus() != CaseStatus.APPROVED || CaseService.status(value, event.store()) != IndexStatus.PENDING) return null;
            return cases.storedRequest(value, cases.metadata(value), event.store()).deepCopy();
        });
    }
    private record DocumentRef(java.util.UUID id, String digest) {}
    private CaseIndexClient.Response submit(CaseIndexRequested event, ObjectNode request) {
        var ref = transactions.execute(transaction -> {
            var value = access.lock(event.actor(), event.caseId());
            if (CaseService.status(value, event.store()) != IndexStatus.PENDING
                    || !cases.storedRequest(value, cases.metadata(value), event.store()).equals(request)) {
                throw new IllegalStateException("현재 색인 요청이 변경되었습니다");
            }
            var document = cases.document(value);
            return new DocumentRef(document.getId(), document.getContentDigest());
        });
        return client.submit(request, ref.id(), ref.digest());
    }
    private void unknown(CaseIndexRequested event, JsonNode request) {
        var snapshot = request.deepCopy();
        ((ObjectNode) snapshot).remove("approvedCase");
        ((ObjectNode) snapshot).put("state", "UNKNOWN").putNull("error").put("updatedAt", clock.instant().toString());
        apply(event, request, snapshot);
    }
    private void apply(CaseIndexRequested event, JsonNode request, JsonNode snapshot) {
        transactions.executeWithoutResult(transaction -> {
            var value = access.lock(event.actor(), event.caseId());
            var version = cases.document(value);
            var metadata = (ObjectNode) data.parse(version.getSourceMetadata());
            var current = cases.storedRequest(value, metadata, event.store());
            if (!current.equals(request) || CaseService.status(value, event.store()) != IndexStatus.PENDING
                    || !CaseIndexClient.valid(current, snapshot)) return;
            String state = snapshot.path("state").asString();
            var publicState = mapper.createObjectNode().put("state", state).put("updatedAt", snapshot.path("updatedAt").asString());
            // 외부 오류 원문은 공개 DTO와 저장 로그에 싣지 않는다.
            if ("FAILED".equals(state)) publicState.set("error", mapper.valueToTree(
                    com.hihops.hops.global.exception.ErrorResponse.of(com.hihops.hops.global.exception.ErrorCode.SEARCH_UNAVAILABLE,
                            java.util.UUID.randomUUID().toString())));
            else publicState.putNull("error");
            ((ObjectNode) metadata.path("indexResults")).set(event.store(), publicState);
            version.updateIndexMetadata(metadata.toString());
            value.recordIndex(event.store(), switch (state) {
                case "SUCCEEDED" -> IndexStatus.SUCCEEDED;
                case "FAILED" -> IndexStatus.FAILED;
                default -> IndexStatus.PENDING;
            });
            publisher.publishEvent(new CaseChanged(value.getId(), value.getVersionNo()));
        });
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
