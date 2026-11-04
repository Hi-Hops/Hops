package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.hihops.hops.agent.client.AgentRunClient;
import com.hihops.hops.agent.client.AgentRunClientException;
import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.Message;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실행 전달 흐름(권한 재확인 → 접수 → RUNNING → 폴링 → 결과 반영/실패/시간 초과)을 DB와 AI 서버 없이 확인한다.
 * AI 클라이언트와 저장소는 가짜이고 스레드 풀은 호출한 스레드에서 바로 실행한다.
 */
class AgentRunDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-04T01:00:00Z");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID runId = UUID.randomUUID();
    private final UUID inquiryId = UUID.randomUUID();

    private AgentRunRepository runs;
    private AgentRunClient client;
    private FakeHandler handler;
    private MutableClock clock;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        runs = mock(AgentRunRepository.class);
        client = mock(AgentRunClient.class);
        handler = new FakeHandler();
        clock = new MutableClock(NOW);
        run = newRun(AgentTask.INQUIRY_ANALYSIS);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
    }

    private AgentRun newRun(AgentTask task) {
        Inquiry inquiry = mock(Inquiry.class);
        when(inquiry.getId()).thenReturn(inquiryId);
        AgentRun created = new AgentRun(inquiry, mock(Message.class), 1, input(), "pending", "pending", "hops-system-1");
        ReflectionTestUtils.setField(created, "id", runId);
        if (task != AgentTask.INQUIRY_ANALYSIS) {
            ReflectionTestUtils.setField(created, "task", task);
        }
        return created;
    }

    private String input() {
        return "{\"contractVersion\":\"hops-system-1\",\"runId\":\"" + runId + "\",\"kind\":\"INQUIRY_ANALYSIS\","
                + "\"expiresAt\":\"" + NOW.plusSeconds(60) + "\"}";
    }

    private AgentRunDispatcher dispatcher(boolean enabled, TaskExecutor executor, AgentRunResultHandler... handlers) {
        return new AgentRunDispatcher(runs, client, List.of(handlers), mapper, executor,
                mock(PlatformTransactionManager.class), clock,
                new AgentDispatchProperties(enabled, Duration.ZERO, Duration.ofSeconds(5)));
    }

    private AgentRunDispatcher dispatcher() {
        return dispatcher(true, Runnable::run, handler);
    }

    private void queue(AgentRunDispatcher dispatcher) {
        dispatcher.queued(new AgentRunQueued(runId));
    }

    private JsonNode snapshot(String status, String resultJson, String errorCode) {
        return mapper.readTree("{\"contractVersion\":\"hops-system-1\",\"runId\":\"" + runId + "\",\"status\":\"" + status
                + "\",\"lastSeq\":0,\"result\":" + (resultJson == null ? "null" : resultJson) + ",\"error\":"
                + (errorCode == null ? "null"
                        : "{\"code\":\"" + errorCode + "\",\"message\":\"x\",\"retryable\":true}")
                + "}");
    }

    private String result(UUID forRun, UUID forInquiry, int version) {
        return "{\"contractVersion\":\"hops-system-1\",\"runId\":\"" + forRun + "\",\"kind\":\"INQUIRY_ANALYSIS\","
                + "\"inquiryId\":\"" + forInquiry + "\",\"inquiryVersion\":" + version
                + ",\"status\":\"SUCCEEDED\",\"answer\":\"안내드립니다\"}";
    }

    private String goodResult() {
        return result(runId, inquiryId, 1);
    }

    private static AgentRunClientException clientError(String code, boolean retryable, int status) {
        return new AgentRunClientException(code, retryable, status, null, "x", null);
    }

    @Test
    void SSE_최종_결과는_폴링과_같은_경로로_한번만_반영한다() {
        var streams = mock(AgentRunStream.class);
        var stream = mock(AgentRunStream.Handle.class);
        when(streams.open(runId)).thenReturn(stream);
        when(stream.result()).thenReturn(snapshot("SUCCEEDED", goodResult(), null));
        var dispatcher = dispatcher();
        ReflectionTestUtils.setField(dispatcher, "streams", streams);
        queue(dispatcher);
        assertThat(handler.applied).isEqualTo(1);
        assertThat(run.getRunStatus()).isEqualTo(RunStatus.SUCCEEDED);
        verify(client, never()).snapshot(any());
        verify(stream).close();
    }

    @Test
    void SSE가_최종_결과없이_끊겨도_snapshot으로_완료한다() {
        var streams = mock(AgentRunStream.class);
        var stream = mock(AgentRunStream.Handle.class);
        when(streams.open(runId)).thenReturn(stream);
        when(client.snapshot(runId.toString())).thenReturn(snapshot("SUCCEEDED", goodResult(), null));
        var dispatcher = dispatcher();
        ReflectionTestUtils.setField(dispatcher, "streams", streams);
        queue(dispatcher);
        assertThat(handler.applied).isEqualTo(1);
        verify(stream).close();
    }

    @Test
    void 접수한_실행이_성공하면_처리기로_결과를_반영하고_실행을_성공으로_끝낸다() {
        when(client.snapshot(runId.toString())).thenReturn(snapshot("RUNNING", null, null))
                .thenReturn(snapshot("SUCCEEDED", goodResult(), null));

        queue(dispatcher());

        verify(client).submit(any());
        assertThat(handler.verified).isEqualTo(1);
        assertThat(handler.applied).isEqualTo(1);
        assertThat(run.getRunStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getStartedAt()).isEqualTo(NOW);
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(run.getResult()).contains("안내드립니다");
        assertThat(run.getValidationResult()).isEqualTo("{\"checked\":true}");
        assertThat(run.getErrorCode()).isNull();
    }

    @Test
    void 전달을_끈_설정에서는_아무것도_하지_않는다() {
        queue(dispatcher(false, Runnable::run, handler));

        verify(client, never()).submit(any());
        assertThat(run.getRunStatus()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    void 스레드_풀이_거부하면_실행을_서비스_불가로_끝낸다() {
        TaskExecutor full = task -> {
            throw new TaskRejectedException("full");
        };

        queue(dispatcher(true, full, handler));

        verify(client, never()).submit(any());
        assertThat(run.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void 접수_전_권한_확인이_거부하면_AI에_보내지_않고_그_코드로_끝낸다() {
        handler.verifyFailure = new AgentResultRejectedException("AUTH_CONTEXT_INVALID", "권한 세대 변경");

        queue(dispatcher());

        verify(client, never()).submit(any());
        assertThat(run.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("AUTH_CONTEXT_INVALID");
    }

    @Test
    void 결과_처리기가_없는_작업_종류는_AI에_보내지_않고_실패로_끝낸다() {
        AgentRun curation = newRun(AgentTask.CURATION);
        when(runs.findById(runId)).thenReturn(Optional.of(curation));

        queue(dispatcher());

        verify(client, never()).submit(any());
        assertThat(curation.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(curation.getErrorCode()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void 같은_작업_종류의_처리기를_둘_등록하면_시작할_때_거부한다() {
        assertThatThrownBy(() -> dispatcher(true, Runnable::run, handler, new FakeHandler()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 이미_시작된_실행의_중복_이벤트는_무시한다() {
        run.start(NOW);

        queue(dispatcher());

        verify(client, never()).submit(any());
        assertThat(run.getRunStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(handler.verified).isZero();
    }

    @Test
    void 접수가_일시적으로_거절되면_서비스_불가로_끝낸다() {
        doThrow(clientError("RATE_LIMITED", true, 429)).when(client).submit(any());

        queue(dispatcher());

        verify(client, never()).snapshot(any());
        assertThat(run.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void 접수_충돌은_재시도_불가_계약_오류로_끝낸다() {
        doThrow(clientError("CONFLICT", false, 409)).when(client).submit(any());

        queue(dispatcher());

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("UPSTREAM_PROTOCOL_ERROR");
    }

    @Test
    void AI_서비스_토큰이_없으면_서비스_불가로_끝낸다() {
        doThrow(new IllegalStateException("토큰 없음")).when(client).submit(any());

        queue(dispatcher());

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void 폴링_중_일시_오류는_기한까지_다시_확인한다() {
        when(client.snapshot(runId.toString())).thenThrow(clientError("SERVICE_UNAVAILABLE", true, 0))
                .thenReturn(snapshot("SUCCEEDED", goodResult(), null));

        queue(dispatcher());

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(handler.applied).isEqualTo(1);
    }

    @Test
    void 폴링_중_재시도_불가_오류는_실행을_실패로_끝낸다() {
        when(client.snapshot(runId.toString())).thenThrow(clientError("NOT_FOUND", false, 404));

        queue(dispatcher());

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("UPSTREAM_PROTOCOL_ERROR");
    }

    @Test
    void 기한과_유예를_넘기면_시간_초과로_끝낸다() {
        when(client.snapshot(runId.toString())).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(30));
            return snapshot("RUNNING", null, null);
        });

        queue(dispatcher());

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(run.getErrorCode()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(handler.applied).isZero();
    }

    @Test
    void 기한과_유예를_넘긴_뒤_도착한_성공_결과는_반영하지_않고_시간_초과로_끝낸다() {
        // expiresAt 60초 + 유예 5초를 1초 넘긴 시각에 snapshot이 SUCCEEDED를 돌려준다
        when(client.snapshot(runId.toString())).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(66));
            return snapshot("SUCCEEDED", goodResult(), null);
        });

        queue(dispatcher());

        assertThat(handler.applied).isZero();
        assertThat(run.getRunStatus()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(run.getErrorCode()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(run.getResult()).isNull();
    }

    @Test
    void 기한과_유예가_정확히_끝나는_시각에_도착한_성공_결과는_반영한다() {
        when(client.snapshot(runId.toString())).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(65));
            return snapshot("SUCCEEDED", goodResult(), null);
        });

        queue(dispatcher());

        assertThat(handler.applied).isEqualTo(1);
        assertThat(run.getRunStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getErrorCode()).isNull();
    }

    @Test
    void AI가_실패로_끝내면_AI_오류_코드를_남기고_실패로_끝낸다() {
        when(client.snapshot(runId.toString())).thenReturn(snapshot("FAILED", null, "BUDGET_EXCEEDED"));

        queue(dispatcher());

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(run.getResult()).isNull();
    }

    @Test
    void 모르는_AI_오류_코드는_서비스_불가로_바꿔_남긴다() {
        when(client.snapshot(runId.toString())).thenReturn(snapshot("FAILED", null, "SOMETHING_NEW"));

        queue(dispatcher());

        assertThat(run.getErrorCode()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void AI가_시간_초과로_끝내면_시간_초과로_끝낸다() {
        when(client.snapshot(runId.toString())).thenReturn(snapshot("TIMED_OUT", null, "DEADLINE_EXCEEDED"));

        queue(dispatcher());

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.TIMED_OUT);
    }

    @Test
    void AI가_대체됐다고_하면_취소로_끝낸다() {
        when(client.snapshot(runId.toString())).thenReturn(snapshot("SUPERSEDED", null, null));

        queue(dispatcher());

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.getErrorCode()).isEqualTo("SUPERSEDED");
    }

    @Test
    void 모르는_AI_상태는_계약_오류로_끝낸다() {
        when(client.snapshot(runId.toString())).thenReturn(snapshot("WEIRD", null, null));

        queue(dispatcher());

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("UPSTREAM_PROTOCOL_ERROR");
    }

    @Test
    void 결과의_실행_ID가_다르면_처리기에_넘기지_않고_출력_오류로_끝낸다() {
        when(client.snapshot(runId.toString()))
                .thenReturn(snapshot("SUCCEEDED", result(UUID.randomUUID(), inquiryId, 1), null));

        queue(dispatcher());

        assertThat(handler.applied).isZero();
        assertThat(run.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("INVALID_OUTPUT");
    }

    @Test
    void 결과의_문의_버전이_다르면_처리기에_넘기지_않고_출력_오류로_끝낸다() {
        when(client.snapshot(runId.toString())).thenReturn(snapshot("SUCCEEDED", result(runId, inquiryId, 2), null));

        queue(dispatcher());

        assertThat(handler.applied).isZero();
        assertThat(run.getErrorCode()).isEqualTo("INVALID_OUTPUT");
    }

    @Test
    void 처리기가_늦은_결과로_판단하면_취소로_끝낸다() {
        handler.applyFailure = new AgentResultRejectedException("SUPERSEDED", "더 새 입력이 있다");
        when(client.snapshot(runId.toString())).thenReturn(snapshot("SUCCEEDED", goodResult(), null));

        queue(dispatcher());

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.getResult()).isNull();
    }

    @Test
    void 처리기가_결과를_거부하면_그_코드로_실패시키고_결과를_남기지_않는다() {
        handler.applyFailure = new AgentResultRejectedException("INVALID_OUTPUT", "허용되지 않은 식별자");
        when(client.snapshot(runId.toString())).thenReturn(snapshot("SUCCEEDED", goodResult(), null));

        queue(dispatcher());

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("INVALID_OUTPUT");
        assertThat(run.getResult()).isNull();
    }

    @Test
    void 처리기에서_예상하지_못한_오류가_나도_실행이_진행_상태로_남지_않는다() {
        handler.applyUnexpected = new IllegalStateException("DB 오류");
        when(client.snapshot(runId.toString())).thenReturn(snapshot("SUCCEEDED", goodResult(), null));

        queue(dispatcher());

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    /** 호출 횟수와 지정한 실패만 흉내 내는 가짜 처리기. */
    private static class FakeHandler implements AgentRunResultHandler {
        int verified;
        int applied;
        AgentResultRejectedException verifyFailure;
        AgentResultRejectedException applyFailure;
        RuntimeException applyUnexpected;

        @Override
        public AgentTask task() {
            return AgentTask.INQUIRY_ANALYSIS;
        }

        @Override
        public void verifyAccess(AgentRun run, JsonNode input) {
            verified++;
            if (verifyFailure != null) {
                throw verifyFailure;
            }
        }

        @Override
        public String apply(AgentRun run, JsonNode input, JsonNode result) {
            applied++;
            if (applyFailure != null) {
                throw applyFailure;
            }
            if (applyUnexpected != null) {
                throw applyUnexpected;
            }
            return "{\"checked\":true}";
        }
    }

    /** 테스트가 시간을 직접 흘려보내는 시계. */
    private static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
