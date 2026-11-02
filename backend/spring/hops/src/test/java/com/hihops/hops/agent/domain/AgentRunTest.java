package com.hihops.hops.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * AI 실행의 상태 전이를 확인한다 (DB 없이 Entity 규칙만 본다).
 *
 * <p>흐름: 만들면 QUEUED → start()로 RUNNING → finish()로 SUCCEEDED / FAILED / TIMED_OUT / CANCELLED.
 * DB의 ck_agent_run_timestamps와 같은 규칙이라, 이 메서드로만 상태를 바꾸면 저장할 때 CHECK에 걸리지 않는다.
 */
class AgentRunTest {

    private static final Instant STARTED = Instant.parse("2026-11-02T01:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-11-02T01:00:30Z");

    private AgentRun newRun() {
        return new AgentRun(null, null, 1, "{}", "gpt-test", "prompt-v1", "schema-v1");
    }

    @Test
    void 새_실행은_시작_전_대기_상태다() {
        AgentRun run = newRun();

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(run.getStartedAt()).isNull();
        assertThat(run.getCompletedAt()).isNull();
    }

    @Test
    void 시작하면_실행_중이_되고_시작_시각을_남긴다() {
        AgentRun run = newRun();

        run.start(STARTED);

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getStartedAt()).isEqualTo(STARTED);
    }

    @Test
    void 끝내면_결과와_종료_시각을_남긴다() {
        AgentRun run = newRun();
        run.start(STARTED);

        run.finish(RunStatus.SUCCEEDED, COMPLETED);

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getCompletedAt()).isEqualTo(COMPLETED);
    }

    @Test
    void 대기_중에도_취소로_끝낼_수_있다() {
        AgentRun run = newRun();

        run.finish(RunStatus.CANCELLED, COMPLETED);

        assertThat(run.getRunStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.getStartedAt()).isNull();
    }

    @Test
    void 시작하지_않은_실행은_성공으로_끝낼_수_없다() {
        AgentRun run = newRun();

        assertThatThrownBy(() -> run.finish(RunStatus.SUCCEEDED, COMPLETED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 이미_끝난_실행은_다시_시작하거나_끝낼_수_없다() {
        AgentRun run = newRun();
        run.start(STARTED);
        run.finish(RunStatus.FAILED, COMPLETED);

        assertThatThrownBy(() -> run.start(STARTED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.finish(RunStatus.SUCCEEDED, COMPLETED)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 끝내는_결과로_대기나_실행_중을_넘기면_거절한다() {
        AgentRun run = newRun();
        run.start(STARTED);

        assertThatThrownBy(() -> run.finish(RunStatus.RUNNING, COMPLETED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
