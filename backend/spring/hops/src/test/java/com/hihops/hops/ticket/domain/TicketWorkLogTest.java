package com.hihops.hops.ticket.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 처리 기록을 만들 때 DB CHECK와 어긋나는 값을 바로 거절하는지 확인한다 (DB 없이 Entity 규칙만 본다).
 *
 * <p>ck_ticket_work_log_verified: 해결·구현 완료 기록에는 비어 있지 않은 검증 결과가 있어야 한다.
 */
class TicketWorkLogTest {

    @Test
    void 검증_결과_없이_해결_기록을_만들면_거절한다() {
        assertThatThrownBy(() -> new TicketWorkLog(null, null, WorkLogType.RESOLVED, "뷰어 캐시를 비움", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 공백뿐인_검증_결과로_구현_기록을_만들면_거절한다() {
        assertThatThrownBy(() -> new TicketWorkLog(null, null, WorkLogType.IMPLEMENTED, "버튼 추가", null, "  ", "1.2.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 진행_기록은_검증_결과_없이_만들_수_있다() {
        TicketWorkLog log = new TicketWorkLog(null, null, WorkLogType.PROGRESS, "로그 확인 중", null, null, null);

        assertThat(log.getVerificationResult()).isNull();
    }
}
