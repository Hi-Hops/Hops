package com.hihops.hops.ticket.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.hihops.hops.user.domain.Member;

/**
 * 담당 배정을 만들 때 DB CHECK와 어긋나는 조합을 바로 거절하는지 확인한다 (DB 없이 Entity 규칙만 본다).
 *
 * <p>ck_ticket_assignment_assigned_by: 수동 정정일 때만 정정자가 있고, 자동 배정이면 정정자가 없다.
 */
class TicketAssignmentTest {

    private static final Instant ASSIGNED = Instant.parse("2026-11-02T01:00:00Z");
    private final Member corrector = new Member("owner", "20260001", "hash", "담당자", null);

    @Test
    void 자동_배정에_정정자를_넣으면_거절한다() {
        assertThatThrownBy(() -> new TicketAssignment(null, null, null, AssignmentSource.AUTO, corrector,
                "시스템 근거", ASSIGNED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 수동_정정에_정정자가_없으면_거절한다() {
        assertThatThrownBy(() -> new TicketAssignment(null, null, null, AssignmentSource.MANUAL_CORRECTION, null,
                "담당 시스템 정정", ASSIGNED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 수동_정정은_정정자와_함께_만든다() {
        TicketAssignment assignment = new TicketAssignment(null, null, null, AssignmentSource.MANUAL_CORRECTION,
                corrector, "담당 시스템 정정", ASSIGNED);

        assertThat(assignment.getAssignedBy()).isSameAs(corrector);
    }
}
