package com.hihops.hops.agent.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.agent.repository.AgentRunRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * QUEUED로 접수된 실행을 commit 뒤에 AI 서버로 전달하고 끝날 때까지 상태를 확인해 실행을 마무리한다
 * (WIRE_CONTRACT 12.8 3번, 6번).
 *
 * <p>흐름: 접수 거래가 commit되면 {@link AgentRunQueued}를 받아 전용 스레드에서 ① 작업 종류의 처리기가 접수 때의 권한 문맥을
 * 다시 확인 → ② AI에 접수(submit) → ③ QUEUED→RUNNING → ④ snapshot을 폴링 → ⑤ 종료 상태에 맞춰 결과를 처리기로 반영하거나
 * 실패·시간 초과·대체로 끝낸다. 각 단계는 별도 거래이며 AI를 기다리는 동안 DB 거래나 연결을 잡지 않는다.
 *
 * <p>AI 응답이 없거나 계약과 다르거나 기한({@code expiresAt}+유예)을 넘기면 실행을 실패(또는 시간 초과)로 끝내고,
 * 성공으로 간주하지 않는다. 결과를 업무에 어떻게 반영하는지는 {@link AgentRunResultHandler}가 정한다.
 *
 * <p>이 클래스는 서버 재시작으로 중간에 끊긴 QUEUED·RUNNING 실행을 복구하지 않는다.
 */
@Service
public class AgentRunDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AgentRunDispatcher.class);

    private static final String CONTRACT_VERSION = "hops-system-1";
    private static final Duration FALLBACK_DEADLINE = Duration.ofSeconds(90);
    // 내부에서 쓰는 AI 쪽 오류 코드. agent_run.error_code에 그대로 남는다.
    private static final Set<String> KNOWN_CODES = Set.of("INVALID_OUTPUT", "SEARCH_UNAVAILABLE", "TOOL_UNAVAILABLE",
            "MODEL_UNAVAILABLE", "DEADLINE_EXCEEDED", "BUDGET_EXCEEDED", "AUTH_CONTEXT_INVALID",
            AgentResultRejectedException.SUPERSEDED, AgentRunClientException.SERVICE_UNAVAILABLE,
            AgentRunClientException.UPSTREAM_PROTOCOL_ERROR);

    private final AgentRunRepository runs;
    private final AgentRunClient client;
    private final Map<AgentTask, AgentRunResultHandler> handlers = new EnumMap<>(AgentTask.class);
    private final JsonMapper mapper;
    private final TaskExecutor executor;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AgentDispatchProperties properties;

    /** 처리기가 하나도 등록되지 않은 환경에서도 시작되도록 ObjectProvider로 받는다. */
    @Autowired
    public AgentRunDispatcher(AgentRunRepository runs, AgentRunClient client,
            ObjectProvider<AgentRunResultHandler> handlers, JsonMapper mapper,
            @Qualifier(AgentDispatchConfig.AGENT_DISPATCH_EXECUTOR) TaskExecutor executor,
            PlatformTransactionManager manager, Clock clock, AgentDispatchProperties properties) {
        this(runs, client, handlers.orderedStream().toList(), mapper, executor, manager, clock, properties);
    }

    AgentRunDispatcher(AgentRunRepository runs, AgentRunClient client, List<AgentRunResultHandler> handlers,
            JsonMapper mapper, TaskExecutor executor, PlatformTransactionManager manager, Clock clock,
            AgentDispatchProperties properties) {
        this.runs = runs;
        this.client = client;
        for (AgentRunResultHandler handler : handlers) {
            if (this.handlers.put(handler.task(), handler) != null) {
                throw new IllegalStateException("작업 종류의 결과 처리기는 하나만 등록할 수 있습니다: " + handler.task());
            }
        }
        this.mapper = mapper;
        this.executor = executor;
        this.clock = clock;
        this.properties = properties;
        // 요청 거래와 분리된 새 거래로 상태를 바꾼다. 한 단계가 실패해도 앞 단계의 기록은 남는다.
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void queued(AgentRunQueued event) {
        if (!properties.dispatchEnabled()) {
            return;
        }
        try {
            executor.execute(() -> deliver(event.runId()));
        } catch (TaskRejectedException exception) {
            log.warn("AI 전달 대기열이 가득 차 실행을 실패로 끝냅니다: runId={}", event.runId());
            finish(event.runId(), RunStatus.FAILED, AgentRunClientException.SERVICE_UNAVAILABLE);
        }
    }

    void deliver(UUID runId) {
        try {
            JsonNode input;
            try {
                input = transaction.execute(status -> prepare(runId));
            } catch (AgentResultRejectedException rejected) {
                finish(runId, statusOf(rejected.code()), rejected.code());
                return;
            }
            if (input == null) {
                return; // 이미 처리 중이거나 끝난 실행의 중복 이벤트
            }
            try {
                client.submit(input);
            } catch (AgentRunClientException exception) {
                log.warn("AI 서버가 실행 접수를 거절했습니다: runId={}, code={}, status={}", runId, exception.code(),
                        exception.httpStatus());
                finish(runId, RunStatus.FAILED, failureCode(exception));
                return;
            } catch (IllegalStateException exception) {
                log.error("AI 서버로 실행을 보낼 수 없습니다: runId={}, reason={}", runId, exception.getMessage());
                finish(runId, RunStatus.FAILED, AgentRunClientException.SERVICE_UNAVAILABLE);
                return;
            }
            start(runId);
            poll(runId, input);
        } catch (RuntimeException exception) {
            // 마지막 방어선. 어떤 예외든 실행이 QUEUED·RUNNING으로 영영 남지 않게 한다.
            log.error("AI 실행 전달 중 예기치 않은 오류: runId={}", runId, exception);
            finish(runId, RunStatus.FAILED, AgentRunClientException.SERVICE_UNAVAILABLE);
        }
    }

    // QUEUED 실행의 입력을 읽고 접수 때의 권한 문맥을 다시 확인한다. QUEUED가 아니면 null.
    private @Nullable JsonNode prepare(UUID runId) {
        AgentRun run = runs.findById(runId).orElseThrow(() -> new IllegalStateException("실행이 없습니다: " + runId));
        if (run.getRunStatus() != RunStatus.QUEUED) {
            return null;
        }
        AgentRunResultHandler handler = handlers.get(run.getTask());
        if (handler == null) {
            log.warn("결과 처리기가 없는 작업 종류의 실행입니다: runId={}, task={}", runId, run.getTask());
            throw new AgentResultRejectedException(AgentRunClientException.SERVICE_UNAVAILABLE,
                    "결과 처리기가 등록되지 않은 작업 종류입니다: " + run.getTask());
        }
        JsonNode input = mapper.readTree(run.getInputSnapshot());
        handler.verifyAccess(run, input);
        return input;
    }

    private void start(UUID runId) {
        try {
            transaction.executeWithoutResult(status -> runs.findById(runId).orElseThrow().start(clock.instant()));
        } catch (IllegalStateException exception) {
            // 이미 AI에 접수됐으므로 상태만 못 바꾼 경우다. 폴링에서 종료를 확인해 마무리한다.
            log.warn("실행을 RUNNING으로 바꾸지 못했습니다: runId={}, reason={}", runId, exception.getMessage());
        }
    }

    private void poll(UUID runId, JsonNode input) {
        Instant limit = deadlineOf(input).plus(properties.dispatchDeadlineGrace());
        while (true) {
            try {
                JsonNode snapshot = client.snapshot(runId.toString());
                if (settle(runId, input, snapshot, limit)) {
                    return;
                }
            } catch (AgentRunClientException exception) {
                if (!exception.retryable()) {
                    log.warn("AI 상태 조회가 실패했습니다: runId={}, code={}", runId, exception.code());
                    finish(runId, RunStatus.FAILED, failureCode(exception));
                    return;
                }
                // 일시 오류는 기한까지 다시 확인한다.
            }
            if (clock.instant().isAfter(limit)) {
                finish(runId, RunStatus.TIMED_OUT, "DEADLINE_EXCEEDED");
                return;
            }
            if (!pause()) {
                finish(runId, RunStatus.FAILED, AgentRunClientException.SERVICE_UNAVAILABLE);
                return;
            }
        }
    }

    // 실행을 끝냈으면 true, 아직 진행 중이면 false.
    private boolean settle(UUID runId, JsonNode input, JsonNode snapshot, Instant limit) {
        String status = snapshot.path("status").asString("");
        switch (status) {
            case "QUEUED", "RUNNING", "UNKNOWN":
                return false;
            case "SUCCEEDED":
                // 기한+유예를 넘겨 확인된 성공 결과는 반영하지 않고 시간 초과로 끝낸다.
                if (clock.instant().isAfter(limit)) {
                    finish(runId, RunStatus.TIMED_OUT, "DEADLINE_EXCEEDED");
                    return true;
                }
                applyResult(runId, input, snapshot.path("result"));
                return true;
            case "FAILED":
                finish(runId, RunStatus.FAILED, errorCodeOf(snapshot));
                return true;
            case "TIMED_OUT":
                finish(runId, RunStatus.TIMED_OUT, "DEADLINE_EXCEEDED");
                return true;
            case "SUPERSEDED":
                finish(runId, RunStatus.CANCELLED, AgentResultRejectedException.SUPERSEDED);
                return true;
            default:
                finish(runId, RunStatus.FAILED, AgentRunClientException.UPSTREAM_PROTOCOL_ERROR);
                return true;
        }
    }

    private void applyResult(UUID runId, JsonNode input, JsonNode result) {
        try {
            transaction.executeWithoutResult(status -> {
                AgentRun run = runs.findById(runId).orElseThrow(() -> new IllegalStateException("실행이 없습니다: " + runId));
                if (run.getRunStatus() != RunStatus.RUNNING) {
                    return; // 그사이 다른 경로로 끝난 실행에는 늦은 결과를 반영하지 않는다
                }
                if (!matchesRun(run, input, result)) {
                    throw new AgentResultRejectedException("INVALID_OUTPUT", "AI 결과가 실행과 일치하지 않습니다");
                }
                String validation = handlers.get(run.getTask()).apply(run, input, result);
                run.storeOutcome(result.toString(), validation, null);
                run.finish(RunStatus.SUCCEEDED, clock.instant());
            });
        } catch (AgentResultRejectedException rejected) {
            log.warn("AI 결과를 반영하지 않습니다: runId={}, code={}", runId, rejected.code());
            finish(runId, statusOf(rejected.code()), rejected.code());
        } catch (RuntimeException exception) {
            log.error("AI 결과 반영 중 오류: runId={}", runId, exception);
            finish(runId, RunStatus.FAILED, AgentRunClientException.SERVICE_UNAVAILABLE);
        }
    }

    // 결과가 이 실행의 것인지 계약 봉투만 대조한다(contractVersion·runId·문의·입력 버전·종류·성공 상태).
    private static boolean matchesRun(AgentRun run, JsonNode input, JsonNode result) {
        return result.isObject()
                && CONTRACT_VERSION.equals(result.path("contractVersion").asString(""))
                && run.getId().toString().equals(result.path("runId").asString(""))
                && run.getInquiry().getId().toString().equals(result.path("inquiryId").asString(""))
                && run.getInputVersion() == result.path("inquiryVersion").asInt(-1)
                && input.path("kind").asString("-").equals(result.path("kind").asString(""))
                && "SUCCEEDED".equals(result.path("status").asString(""));
    }

    // 실행을 끝낸다. 이미 끝났거나 없는 실행은 건드리지 않는다.
    private void finish(UUID runId, RunStatus outcome, String errorCode) {
        try {
            transaction.executeWithoutResult(status -> runs.findById(runId).ifPresent(run -> {
                if (run.getRunStatus() == RunStatus.QUEUED || run.getRunStatus() == RunStatus.RUNNING) {
                    run.storeOutcome(null, null, errorCode);
                    run.finish(outcome, clock.instant());
                }
            }));
        } catch (RuntimeException exception) {
            log.error("실행을 종료 상태로 바꾸지 못했습니다: runId={}, outcome={}", runId, outcome, exception);
        }
    }

    private static RunStatus statusOf(String code) {
        return AgentResultRejectedException.SUPERSEDED.equals(code) ? RunStatus.CANCELLED : RunStatus.FAILED;
    }

    // AI가 준 코드가 아는 값이면 그대로, 아니면 재시도 가능 여부로 서비스 불가/계약 위반으로 나눈다.
    private static String failureCode(AgentRunClientException exception) {
        if (KNOWN_CODES.contains(exception.code())) {
            return exception.code();
        }
        return exception.retryable() ? AgentRunClientException.SERVICE_UNAVAILABLE
                : AgentRunClientException.UPSTREAM_PROTOCOL_ERROR;
    }

    private static String errorCodeOf(JsonNode snapshot) {
        String code = snapshot.path("error").path("code").asString("");
        return KNOWN_CODES.contains(code) ? code : AgentRunClientException.SERVICE_UNAVAILABLE;
    }

    private Instant deadlineOf(JsonNode input) {
        try {
            return Instant.parse(input.path("expiresAt").asString(""));
        } catch (DateTimeParseException exception) {
            return clock.instant().plus(FALLBACK_DEADLINE);
        }
    }

    // 다음 확인까지 기다린다. 스레드가 중단되면 false.
    private boolean pause() {
        Duration pollInterval = properties.dispatchPollInterval();
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            return true;
        }
        try {
            Thread.sleep(pollInterval);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
