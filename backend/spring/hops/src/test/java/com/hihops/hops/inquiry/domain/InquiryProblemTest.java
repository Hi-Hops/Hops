package com.hihops.hops.inquiry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 문의 문제를 만들 때 DB CHECK와 어긋나는 값을 바로 거절하는지 확인한다 (DB 없이 Entity 규칙만 본다).
 *
 * <p>ck_inquiry_problem_resolved: 무티켓 해결(RESOLVED_WITHOUT_TICKET)이면 해결일시가 있어야 한다.
 * 해결일시는 문의자가 확인할 때 정해지므로, 처음부터 해결 상태로 만들 수는 없다.
 */
class InquiryProblemTest {

    @Test
    void 무티켓_해결_상태로는_처음부터_만들_수_없다() {
        assertThatThrownBy(() -> new InquiryProblem(null, 1, "결재 문서 열람 오류", null,
                ProblemStatus.RESOLVED_WITHOUT_TICKET, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 질문_중_상태로는_만들_수_있다() {
        InquiryProblem problem = new InquiryProblem(null, 1, "결재 문서 열람 오류", null, ProblemStatus.CLARIFYING, null);

        assertThat(problem.getProblemStatus()).isEqualTo(ProblemStatus.CLARIFYING);
        assertThat(problem.getResolvedAt()).isNull();
    }
}
