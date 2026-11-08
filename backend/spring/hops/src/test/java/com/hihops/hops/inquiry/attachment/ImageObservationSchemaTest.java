package com.hihops.hops.inquiry.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 격리 PostgreSQL에서 관찰과 사용자 응답의 보존 제약을 확인한다. */
@SpringBootTest
@ActiveProfiles({"db", "test"})
class ImageObservationSchemaTest {
    @Autowired JdbcTemplate jdbc;
    private final UUID department = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID inquiry = UUID.randomUUID();
    private final UUID userMessage = UUID.randomUUID();
    private final UUID answerMessage = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();
    private final UUID problem = UUID.randomUUID();
    private final UUID operation = UUID.randomUUID();
    private final UUID responseOperation = UUID.randomUUID();
    private final UUID image = UUID.randomUUID();
    private final UUID observation = UUID.randomUUID();

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO department (id, code, name) VALUES (?, ?, '테스트')", department, department.toString().substring(0, 8));
        jdbc.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) VALUES (?, ?, ?, 'fixture', '테스트', ?)",
                member, member.toString(), member.toString(), department);
        jdbc.update("INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status) VALUES (?, ?, ?, '테스트', 'IN_PROGRESS')",
                inquiry, inquiry.toString().substring(0, 8), member);
        jdbc.update("INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, message_status) VALUES (?, ?, 1, 'USER', '테스트', 'COMPLETED')", userMessage, inquiry);
        jdbc.update("INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, message_status) VALUES (?, ?, 2, 'AI', '테스트', 'COMPLETED')", answerMessage, inquiry);
        jdbc.update("INSERT INTO agent_run (id, inquiry_id, task, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version) "
                + "VALUES (?, ?, 'INQUIRY_ANALYSIS', 'QUEUED', 1, '{}', 'test', 'test', 'test')", run, inquiry);
        jdbc.update("INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, problem_status) VALUES (?, ?, 1, '테스트', 'CLARIFYING')", problem, inquiry);
        jdbc.update("INSERT INTO idempotency_key (actor_member_id, contract_version, http_method, request_path, idempotency_key, request_hash, operation_id, operation_state, expires_at) "
                + "VALUES (?, 'test', 'POST', '/test', ?, ?, ?, 'PENDING', now() + interval '1 day')", member, operation.toString(), "a".repeat(64), operation);
        jdbc.update("INSERT INTO idempotency_key (actor_member_id, contract_version, http_method, request_path, idempotency_key, request_hash, operation_id, operation_state, expires_at) "
                + "VALUES (?, 'test', 'POST', '/test/response', ?, ?, ?, 'PENDING', now() + interval '1 day')", member, responseOperation.toString(), "a".repeat(64), responseOperation);
        jdbc.update("INSERT INTO image_attachment (id, owner_member_id, operation_id, input_sha256, content_sha256, byte_size, attachment_status, message_id, message_position) "
                + "VALUES (?, ?, ?, ?, ?, 1, 'BOUND', ?, 0)", image, member, operation, "a".repeat(64), "b".repeat(64), userMessage);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM image_observation_response WHERE observation_id IN (SELECT id FROM image_observation WHERE inquiry_id = ?)", inquiry);
        jdbc.update("DELETE FROM image_observation WHERE inquiry_id = ?", inquiry);
        jdbc.update("DELETE FROM image_attachment WHERE id = ?", image);
        jdbc.update("DELETE FROM idempotency_key WHERE operation_id IN (?, ?)", operation, responseOperation);
        jdbc.update("DELETE FROM inquiry_problem WHERE id = ?", problem);
        jdbc.update("DELETE FROM agent_run WHERE id = ?", run);
        jdbc.update("DELETE FROM message WHERE inquiry_id = ?", inquiry);
        jdbc.update("DELETE FROM inquiry WHERE id = ?", inquiry);
        jdbc.update("DELETE FROM member WHERE id = ?", member);
        jdbc.update("DELETE FROM department WHERE id = ?", department);
    }

    private void insertObservation(UUID id, String ref, String text, String question) {
        jdbc.update("INSERT INTO image_observation (id, inquiry_id, run_id, observation_ref, attachment_id, source_message_id, "
                + "source_revision, problem_id, answer_message_id, observation_text, question_bundle_ref) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)",
                id, inquiry, run, ref, image, userMessage, problem, answerMessage, text, question);
    }

    @Test
    void 관찰은_미확인으로_시작하고_응답_이력의_중복을_막는다() {
        insertObservation(observation, "obs-1", "오류 화면", "bundle-1");
        assertThat(jdbc.queryForMap("SELECT observation_state, observation_revision FROM image_observation WHERE id = ?", observation))
                .containsEntry("observation_state", "UNCONFIRMED").containsEntry("observation_revision", 1);
        jdbc.update("UPDATE image_observation SET observation_state = 'CONFIRMED', observation_revision = 2 WHERE id = ?", observation);
        jdbc.update("INSERT INTO image_observation_response (id, observation_id, observation_revision, operation_id, actor_member_id, decision) VALUES (?, ?, 2, ?, ?, 'CONFIRMED')",
                UUID.randomUUID(), observation, responseOperation, member);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO image_observation_response (id, observation_id, observation_revision, operation_id, actor_member_id, decision) VALUES (?, ?, 2, ?, ?, 'CONFIRMED')",
                UUID.randomUUID(), observation, responseOperation, member)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 관찰의_형식과_응답_가능한_상태를_제한한다() {
        assertThatThrownBy(() -> insertObservation(observation, "잘못된 ref", "오류", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertObservation(observation, "obs-1", " ", null)).isInstanceOf(DataIntegrityViolationException.class);
        insertObservation(observation, "obs-1", "오류", null);
        assertThatThrownBy(() -> insertObservation(UUID.randomUUID(), "obs-1", "다른 관찰", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE image_observation SET observation_state = 'CONFIRMED', observation_revision = 2 WHERE id = ?", observation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
