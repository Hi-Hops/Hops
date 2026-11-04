package com.hihops.hops.global.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

/**
 * 보존 기간이 지난 중복 요청 방지 기록을 정리하는지 실제 테스트 DB(hops_test)로 확인한다 (docs/ERD.md 6.1절 idempotency_key).
 *
 * <p>기록은 {@link IdempotencyService}로 만들고 expires_at만 과거로 옮긴다. 정리는 다른 테스트가 남긴 행도 건드릴 수 있어
 * 전체 개수가 아니라 이 테스트 회원의 행만 확인한다.
 *
 * <p>스케줄러 설정이 올라오는지도 보려고 정리를 켜되, 테스트 중에 돌지 않게 첫 실행을 하루 뒤로 미룬다.
 */
@SpringBootTest(properties = {
        "hops.idempotency.tombstone.enabled=true",
        "hops.idempotency.tombstone.initial-delay=PT24H",
        "hops.idempotency.tombstone.interval=PT24H"})
@ActiveProfiles({"db", "test"})
class IdempotencyTombstoneServiceTest {

    private static final String PATH = "/api/v2/inquiries";

    @Autowired
    private IdempotencyTombstoneService tombstoneService;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    private final UUID departmentId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final String marker = "tomb-" + UUID.randomUUID().toString().substring(0, 8);

    record SampleCommand(UUID requestId, String content) {
    }

    @BeforeEach
    void createFixtures() {
        jdbc.update("INSERT INTO department (id, code, name) VALUES (?, ?, '기록 정리 테스트 부서')",
                departmentId, "TOMB_" + departmentId.toString().substring(0, 20));
        jdbc.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                + "VALUES (?, ?, ?, 'unused-test-hash', '기록 정리 테스트 회원', ?)",
                memberId, memberId.toString(), memberId.toString(), departmentId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM test_sample WHERE name = ?", marker);
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id = ?", memberId);
        jdbc.update("DELETE FROM member WHERE id = ?", memberId);
        jdbc.update("DELETE FROM department WHERE id = ?", departmentId);
    }

    @Test
    void 정리는_설정한_간격의_주기_작업으로_전용_스케줄러에_등록된다() {
        assertThat(context.containsBean(IdempotencyTombstoneScheduler.SCHEDULER)).isTrue();
        assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(scheduled -> scheduled.getTask())
                .filter(task -> task.toString().endsWith("IdempotencyTombstoneScheduler.tombstoneExpired")))
                .singleElement()
                .isInstanceOfSatisfying(FixedDelayTask.class, task -> {
                    assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(24));
                    assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofHours(24));
                });
    }

    @Test
    void 만료된_결과와_거절은_응답만_비우고_키_해시_작업_표식은_남긴다() {
        UUID succeeded = UUID.randomUUID();
        CommandReceipt receipt = idempotencyService.execute(request(succeeded, "정리될 성공"), this::work).receipt();
        UUID rejected = UUID.randomUUID();
        assertThatThrownBy(() -> idempotencyService.execute(request(rejected, "정리될 거절"), operationId -> {
            throw new BusinessException(ErrorCode.INQUIRY_VERSION_CONFLICT);
        })).isInstanceOf(BusinessException.class);
        Map<String, Object> before = record(succeeded);
        expire(succeeded);
        expire(rejected);

        tombstoneService.tombstoneExpired();

        for (UUID key : List.of(succeeded, rejected)) {
            Map<String, Object> after = record(key);
            assertThat(after.get("response_status")).isNull();
            assertThat(after.get("response_body")).isNull();
            assertThat(after.get("tombstoned_at")).isNotNull();
        }
        Map<String, Object> after = record(succeeded);
        for (String column : List.of("id", "idempotency_key", "request_hash", "http_method", "request_path",
                "operation_id", "operation_state", "resource_type", "resource_id")) {
            assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
        }
        assertThat(record(rejected).get("operation_state")).isEqualTo("REJECTED");
        // 실행 오류의 requestId 찾기는 resource 표식으로 하므로 정리 뒤에도 된다
        assertThat(idempotencyService.findRequestIdOf(receipt.resource())).contains(succeeded);
    }

    @Test
    void 정리한_기록을_같은_키로_다시_보내면_410이고_업무는_다시_실행하지_않는다() {
        UUID key = UUID.randomUUID();
        idempotencyService.execute(request(key, "응답이 유실된 문의"), this::work);
        expire(key);
        tombstoneService.tombstoneExpired();

        assertThatThrownBy(() -> idempotencyService.execute(request(key, "응답이 유실된 문의"), this::work))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.OPERATION_RESULT_EXPIRED));
        assertThat(businessRows()).isEqualTo(1);
    }

    @Test
    void 보존_기간_안의_기록과_접수_중인_기록은_그대로_둔다() {
        UUID fresh = UUID.randomUUID();
        idempotencyService.execute(request(fresh, "아직 보존 중"), this::work);
        UUID pending = UUID.randomUUID();
        idempotencyService.execute(request(pending, "접수 중"), this::work);
        jdbc.update("UPDATE idempotency_key SET operation_state = 'PENDING', response_status = NULL, response_body = NULL "
                + "WHERE actor_member_id = ? AND idempotency_key = ?", memberId, pending.toString());
        expire(pending);

        tombstoneService.tombstoneExpired();

        assertThat(record(fresh).get("tombstoned_at")).isNull();
        assertThat(record(fresh).get("response_body")).isNotNull();
        assertThat(record(pending).get("tombstoned_at")).isNull();
        assertThat(record(pending).get("operation_state")).isEqualTo("PENDING");
    }

    @Test
    void 이미_정리한_기록은_다시_건드리지_않는다() {
        UUID key = UUID.randomUUID();
        idempotencyService.execute(request(key, "한 번만 정리"), this::work);
        expire(key);
        tombstoneService.tombstoneExpired();
        Object first = record(key).get("tombstoned_at");

        tombstoneService.tombstoneExpired();

        assertThat(record(key).get("tombstoned_at")).isEqualTo(first);
    }

    @Test
    void 한_번에_정리하는_수보다_많아도_모두_정리한다() {
        Timestamp expired = Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < IdempotencyTombstoneService.BATCH_SIZE + 5; i++) {
            rows.add(new Object[] {memberId, UUID.randomUUID().toString(), UUID.randomUUID(), expired});
        }
        jdbc.batchUpdate("INSERT INTO idempotency_key (actor_member_id, contract_version, http_method, request_path, "
                + "idempotency_key, request_hash, operation_id, operation_state, response_status, response_body, expires_at) "
                + "VALUES (?, 'hops-system-1', 'POST', '/api/v2/inquiries', ?, repeat('0', 64), ?, 'SUCCEEDED', 200, "
                + "'{}'::jsonb, ?)", rows);

        assertThat(tombstoneService.tombstoneExpired()).isGreaterThanOrEqualTo(rows.size());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_key WHERE actor_member_id = ? "
                + "AND (tombstoned_at IS NULL OR response_body IS NOT NULL)", Integer.class, memberId)).isZero();
    }

    private ResourceRef work(UUID operationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO test_sample (id, name) VALUES (?, ?)", id, marker);
        return new ResourceRef(ResourceType.INQUIRY, id);
    }

    private IdempotentRequest request(UUID requestId, String content) {
        return new IdempotentRequest(memberId, requestId, "POST", IdempotentRequest.normalizePath(PATH),
                new SampleCommand(requestId, content));
    }

    private void expire(UUID key) {
        jdbc.update("UPDATE idempotency_key SET expires_at = now() - interval '1 minute' "
                + "WHERE actor_member_id = ? AND idempotency_key = ?", memberId, key.toString());
    }

    private Map<String, Object> record(UUID key) {
        return jdbc.queryForMap("SELECT * FROM idempotency_key WHERE actor_member_id = ? AND idempotency_key = ?",
                memberId, key.toString());
    }

    private int businessRows() {
        return jdbc.queryForObject("SELECT count(*) FROM test_sample WHERE name = ?", Integer.class, marker);
    }
}
