package com.hihops.hops.global.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 목록 조회에 쓰는 FK 컬럼에 인덱스가 있는지 확인한다.
 *
 * <p>PostgreSQL은 FK 컬럼에 인덱스를 자동으로 만들지 않는다. 없으면 "내 문의 목록", "담당 티켓 목록", "알림함"처럼
 * 사람·시스템·티켓 기준으로 찾는 조회가 행이 많아질수록 테이블 전체를 훑게 된다.
 * 유일 제약이 앞 컬럼으로 이미 인덱스를 만드는 경우(예: message의 inquiry_id)는 여기서 다시 만들지 않는다.
 */
@SpringBootTest
@ActiveProfiles({"db", "test"})
class ListQueryIndexTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 목록_조회용_FK_컬럼에_인덱스가_있다() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);

        assertThat(indexes).contains(
                "idx_inquiry_requester_member_id",
                "idx_agent_run_inquiry_id",
                "idx_ticket_inquiry_id",
                "idx_ticket_assignee_member_id",
                "idx_ticket_responsible_system_id",
                "idx_ticket_event_ticket_id",
                "idx_ticket_assignment_ticket_id",
                "idx_ticket_work_log_ticket_id",
                "idx_notification_recipient_member_id");
    }
}
