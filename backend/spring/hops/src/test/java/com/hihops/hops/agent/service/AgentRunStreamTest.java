package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import com.hihops.hops.agent.client.AgentRunEventClient;
import com.hihops.hops.realtime.dto.AgentProgressEvent;
import com.hihops.hops.realtime.service.RealtimeService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentRunStreamTest {
    private final UUID runId = UUID.randomUUID();
    private final AgentRunEventClient client = mock(AgentRunEventClient.class);
    private final AgentProgressReader progress = mock(AgentProgressReader.class);
    private final RealtimeService realtime = mock(RealtimeService.class);
    private final JsonMapper mapper = JsonMapper.builder().build();

    private AgentRunStream service(TaskExecutor executor) {
        return new AgentRunStream(client, progress, realtime, executor, mapper);
    }

    @Test
    void 진행을_전달하고_최종은_반영용으로_보관한뒤_입력을_닫는다() throws Exception {
        InputStream body = mock(InputStream.class);
        var event = mapper.readTree("{\"eventType\":\"progress\",\"seq\":1,\"payload\":{}}");
        var publicEvent = new AgentProgressEvent(UUID.randomUUID(), UUID.randomUUID(), 1, runId,
                1, "INQUIRY", "문의 확인 중");
        when(progress.read(runId, event)).thenReturn(publicEvent);
        doAnswer(call -> {
            Consumer<InputStream> opened = call.getArgument(1);
            Predicate<JsonNode> consume = call.getArgument(2);
            opened.accept(body);
            assertThat(consume.test(event)).isTrue();
            JsonNode result = mapper.readTree("{\"eventType\":\"result\",\"payload\":{\"runId\":\"" + runId
                    + "\",\"status\":\"SUCCEEDED\",\"error\":null}}");
            assertThat(consume.test(result)).isFalse();
            return null;
        }).when(client).read(eq(runId.toString()), any(), any());
        var handle = service(Runnable::run).open(runId);
        assertThat(handle.result().path("status").asString()).isEqualTo("SUCCEEDED");
        verify(realtime).agentProgress(publicEvent);
        verify(body).close();
    }

    @Test
    void 연결_오류와_풀_포화는_snapshot_복구를_막지_않는다() {
        doThrow(new IllegalStateException("대역 연결 오류")).when(client).read(any(), any(), any());
        assertThat(service(Runnable::run).open(runId).result()).isNull();
        assertThat(service(task -> { throw new TaskRejectedException("대역 포화"); }).open(runId).result()).isNull();
    }

    @Test
    void 종료한_실행의_대기중_수신작업은_연결하지_않는다() {
        AtomicReference<Runnable> task = new AtomicReference<>();
        var handle = service(task::set).open(runId);
        handle.close();
        task.get().run();
        verify(client, never()).read(any(), any(), any());
    }
}
