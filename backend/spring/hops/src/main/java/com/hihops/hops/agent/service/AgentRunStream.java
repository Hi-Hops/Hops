package com.hihops.hops.agent.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import com.hihops.hops.agent.client.AgentRunEventClient;
import com.hihops.hops.realtime.service.RealtimeService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** SSE는 보조 수신 경로다. 최종 반영은 dispatcher 한 곳에서 snapshot과 함께 직렬 처리한다. */
@Component
public class AgentRunStream {
    private static final Logger log = LoggerFactory.getLogger(AgentRunStream.class);
    private final AgentRunEventClient client;
    private final AgentProgressReader progress;
    private final RealtimeService realtime;
    private final TaskExecutor executor;
    private final JsonMapper mapper;

    public AgentRunStream(AgentRunEventClient client, AgentProgressReader progress, RealtimeService realtime,
            @Qualifier(AgentDispatchConfig.AGENT_STREAM_EXECUTOR) TaskExecutor executor, JsonMapper mapper) {
        this.client = client;
        this.progress = progress;
        this.realtime = realtime;
        this.executor = executor;
        this.mapper = mapper;
    }

    public Handle open(UUID runId) {
        Handle handle = new Handle();
        try {
            executor.execute(() -> {
                try {
                    if (handle.closed.get()) return;
                    client.read(runId.toString(), handle::opened, event -> {
                        if (handle.closed.get()) return false;
                        if ("result".equals(event.path("eventType").asString(""))) {
                            JsonNode result = event.path("payload");
                            // 상세 결과·문의 버전 검증은 기존 dispatcher/업무 처리기가 수행한다.
                            if (!runId.toString().equals(result.path("runId").asString(""))) return false;
                            var snapshot = mapper.createObjectNode();
                            snapshot.put("status", result.path("status").asString(""));
                            snapshot.set("result", result);
                            snapshot.set("error", result.path("error"));
                            handle.result.compareAndSet(null, snapshot);
                            return false;
                        }
                        if ("progress".equals(event.path("eventType").asString(""))) {
                            var publicEvent = progress.read(runId, event);
                            if (publicEvent != null && !handle.closed.get()) realtime.agentProgress(publicEvent);
                        }
                        return true;
                    });
                } catch (RuntimeException failure) {
                    // 외부 응답·예외 원문에는 비밀값이 있을 수 있다. 폴링은 계속한다.
                    log.warn("AI 이벤트 수신 종료, snapshot으로 확인합니다: runId={}", runId);
                } finally {
                    handle.close();
                }
            });
        } catch (TaskRejectedException rejected) {
            handle.close();
            log.warn("AI 이벤트 수신 자원 부족, snapshot으로 확인합니다: runId={}", runId);
        }
        return handle;
    }

    public static final class Handle implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<InputStream> body = new AtomicReference<>();
        private final AtomicReference<JsonNode> result = new AtomicReference<>();

        public @Nullable JsonNode result() { return result.get(); }

        private void opened(InputStream stream) {
            body.set(stream);
            if (closed.get()) closeBody();
        }

        private void closeBody() {
            InputStream stream = body.getAndSet(null);
            if (stream != null) {
                try { stream.close(); }
                catch (IOException ignored) { /* snapshot으로 복구한다 */ }
            }
        }

        @Override
        public void close() {
            closed.set(true);
            closeBody();
        }
    }
}
