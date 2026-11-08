package com.hihops.hops.inquiry.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hihops.hops.agent.service.AgentResultRejectedException;
import com.hihops.hops.inquiry.service.ImageObservationWriter;
import com.hihops.hops.inquiry.service.ImageObservationProblemResolver;
import com.hihops.hops.inquiry.service.GuideResultWriter;
import com.hihops.hops.inquiry.service.ProblemQuestionWriter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 격리 PostgreSQL에서 관찰과 사용자 응답의 보존 제약을 확인한다. */
@SpringBootTest
@ActiveProfiles({"db", "test"})
class ImageObservationSchemaTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ImageObservationWriter observationWriter;
    @Autowired ImageObservationProblemResolver problemResolver;
    @Autowired ImageAnalysisTargets analysisTargets;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JsonMapper mapper;
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
        jdbc.update("DELETE FROM image_attachment_analysis_target WHERE attachment_id = ?", image);
        jdbc.update("DELETE FROM image_attachment_analysis_history WHERE attachment_id = ?", image);
        jdbc.update("DELETE FROM image_attachment_problem_link WHERE attachment_id = ?", image);
        jdbc.update("DELETE FROM image_attachment_problem_link_history WHERE attachment_id = ?", image);
        jdbc.update("DELETE FROM image_attachment WHERE id = ?", image);
        jdbc.update("DELETE FROM idempotency_key WHERE operation_id IN (?, ?)", operation, responseOperation);
        jdbc.update("DELETE FROM inquiry_problem WHERE id = ?", problem);
        jdbc.update("DELETE FROM agent_run WHERE id = ?", run);
        jdbc.update("DELETE FROM message WHERE inquiry_id = ?", inquiry);
        jdbc.update("DELETE FROM inquiry WHERE id = ?", inquiry);
        jdbc.update("DELETE FROM member WHERE id = ?", member);
        jdbc.update("DELETE FROM department WHERE id = ?", department);
    }

    @Test
    void 분석_대상은_공개_관계와_별도로_저장하고_정정하면_이력과_함께_보류한다() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> analysisTargets.replace(
                member, inquiry, image, responseOperation, 0, List.of(problem)));
        assertThat(jdbc.queryForList("SELECT problem_id FROM image_attachment_analysis_target WHERE attachment_id=?", UUID.class, image))
                .containsExactly(problem);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_problem_link WHERE attachment_id=?", Integer.class, image)).isZero();
        assertThat(jdbc.queryForObject("SELECT link_revision FROM image_attachment WHERE id=?", Integer.class, image)).isZero();

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE message SET revision=2 WHERE id=?", userMessage);
            analysisTargets.invalidateForRevision(member, userMessage, 2, operation);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_analysis_target WHERE attachment_id=?", Integer.class, image)).isZero();
        assertThat(jdbc.queryForList("SELECT reason FROM image_attachment_analysis_history WHERE attachment_id=? ORDER BY analysis_revision", String.class, image))
                .containsExactly("USER_SELECTED", "MESSAGE_REVISED");
        assertThat(jdbc.queryForObject("SELECT analysis_revision FROM image_attachment WHERE id=?", Integer.class, image)).isEqualTo(2);
    }

    @Test
    void 분석_대상은_소유자_문의_문제_차수와_거래를_검증한다() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> analysisTargets.replace(
                UUID.randomUUID(), inquiry, image, responseOperation, 0, List.of(problem))))
                .isInstanceOfSatisfying(com.hihops.hops.global.exception.BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(com.hihops.hops.global.exception.ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> analysisTargets.replace(
                member, UUID.randomUUID(), image, responseOperation, 0, List.of(problem))))
                .isInstanceOf(com.hihops.hops.global.exception.BusinessException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> analysisTargets.replace(
                member, inquiry, image, responseOperation, 0, List.of(UUID.randomUUID()))))
                .isInstanceOf(com.hihops.hops.global.exception.BusinessException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> analysisTargets.replace(
                member, inquiry, image, responseOperation, 0, List.of(problem, problem))))
                .isInstanceOf(com.hihops.hops.global.exception.BusinessException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            analysisTargets.replace(member, inquiry, image, responseOperation, 0, List.of(problem));
            throw new IllegalStateException("fixture rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_analysis_history WHERE attachment_id=?", Integer.class, image)).isZero();
        new TransactionTemplate(transactions).executeWithoutResult(status -> analysisTargets.replace(
                member, inquiry, image, responseOperation, 0, List.of(problem)));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> analysisTargets.replace(
                member, inquiry, image, operation, 0, List.of())))
                .isInstanceOfSatisfying(com.hihops.hops.global.exception.BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(com.hihops.hops.global.exception.ErrorCode.ATTACHMENT_LINK_VERSION_CONFLICT));
    }

    @Test
    void 분석_대상을_비워도_공개_대상은_변경하지_않는다() {
        jdbc.update("INSERT INTO image_attachment_problem_link_history (id, attachment_id, link_revision, actor_member_id, operation_id, source_message_revision, reason, problem_ids) "
                + "VALUES (?, ?, 1, ?, ?, 1, 'USER_CONFIRMED', ?::jsonb)", UUID.randomUUID(), image, member, responseOperation,
                mapper.writeValueAsString(List.of(problem)));
        jdbc.update("INSERT INTO image_attachment_problem_link (attachment_id, problem_id, link_revision, source_message_revision) VALUES (?, ?, 1, 1)", image, problem);
        new TransactionTemplate(transactions).executeWithoutResult(status -> analysisTargets.replace(
                member, inquiry, image, responseOperation, 0, List.of(problem)));
        new TransactionTemplate(transactions).executeWithoutResult(status -> analysisTargets.replace(
                member, inquiry, image, operation, 1, List.of()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_analysis_target WHERE attachment_id=?", Integer.class, image)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_problem_link WHERE attachment_id=?", Integer.class, image)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT analysis_revision FROM image_attachment WHERE id=?", Integer.class, image)).isEqualTo(2);
    }

    @Test
    void 같은_문의의_여러_문제를_분석_대상으로_선택해도_공개하지_않는다() {
        UUID second = UUID.randomUUID();
        jdbc.update("INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, problem_status) VALUES (?, ?, 2, '테스트2', 'CLARIFYING')", second, inquiry);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> analysisTargets.replace(
                    member, inquiry, image, responseOperation, 0, List.of(problem, second)));
            assertThat(jdbc.queryForList("SELECT problem_id FROM image_attachment_analysis_target WHERE attachment_id=?", UUID.class, image))
                    .containsExactlyInAnyOrder(problem, second);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_problem_link WHERE attachment_id=?", Integer.class, image)).isZero();
        } finally {
            jdbc.update("DELETE FROM image_attachment_analysis_target WHERE problem_id=?", second);
            jdbc.update("DELETE FROM inquiry_problem WHERE id=?", second);
        }
    }

    @Test
    void 이미지_문제_연결은_확인_이력과_차수에_연결되고_중복을_거부한다() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO image_attachment_problem_link (attachment_id, problem_id, link_revision, source_message_revision) VALUES (?, ?, 1, 1)", image, problem))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO image_attachment_problem_link_history (id, attachment_id, link_revision, actor_member_id, operation_id, source_message_revision, reason, problem_ids) "
                + "VALUES (?, ?, 1, ?, ?, 1, 'USER_CONFIRMED', ?::jsonb)", UUID.randomUUID(), image, member, responseOperation,
                mapper.writeValueAsString(List.of(problem)));
        jdbc.update("INSERT INTO image_attachment_problem_link (attachment_id, problem_id, link_revision, source_message_revision) VALUES (?, ?, 1, 1)", image, problem);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO image_attachment_problem_link (attachment_id, problem_id, link_revision, source_message_revision) VALUES (?, ?, 1, 1)", image, problem))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO image_attachment_problem_link_history (id, attachment_id, link_revision, actor_member_id, operation_id, source_message_revision, reason, problem_ids) "
                + "VALUES (?, ?, 2, ?, ?, 2, 'MESSAGE_REVISED', ?::jsonb)", UUID.randomUUID(), image, member, operation,
                mapper.writeValueAsString(List.of(problem)))).isInstanceOf(DataIntegrityViolationException.class);
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

    private JsonNode input(String digest, int revision) {
        return mapper.valueToTree(Map.of(
                "messages", List.of(Map.of("id", userMessage.toString(), "revision", revision, "sender", "USER",
                        "imageAttachments", List.of(Map.of("attachmentId", image.toString(), "digest", digest)))),
                "problems", List.of(Map.of("id", problem.toString()))));
    }

    private JsonNode result(String observationText, String answer) {
        return mapper.valueToTree(Map.of("answer", answer,
                "questions", List.of(Map.of("problemRef", problem.toString(), "bundleRef", "bundle-1", "text", answer)),
                "imageObservations", List.of(Map.of("observationRef", "obs-1", "attachmentId", image.toString(),
                        "messageId", userMessage.toString(), "messageRevision", 1, "problemRef", problem.toString(),
                        "text", observationText, "questionRef", "bundle-1"))));
    }

    private void storedAnswer(String answer) {
        String payload = mapper.writeValueAsString(Map.of("questionBundles", List.of(
                Map.of("problemId", problem.toString(), "bundleRef", "bundle-1"))));
        jdbc.update("UPDATE message SET content = ?, payload = ?::jsonb WHERE id = ?", answer, payload, answerMessage);
    }

    private int store(JsonNode input, JsonNode result) {
        Integer count = new TransactionTemplate(transactions).execute(status -> observationWriter.store(run, inquiry,
                member, answerMessage, input, result, Map.of(problem.toString(), problem)));
        return count == null ? -1 : count;
    }

    private Map<String, UUID> resolve(JsonNode input, JsonNode result, GuideResultWriter.Applied guide,
            ProblemQuestionWriter.Applied stored) {
        return new TransactionTemplate(transactions).execute(status ->
                problemResolver.resolve(run, inquiry, input, result, guide, stored));
    }

    private JsonNode resultForProblem(String ref) {
        var result = (tools.jackson.databind.node.ObjectNode) result("오류 화면", "오류 화면이 보입니다. 맞나요?");
        ((tools.jackson.databind.node.ObjectNode) result.path("imageObservations").get(0)).put("problemRef", ref);
        ((tools.jackson.databind.node.ObjectNode) result.path("questions").get(0)).put("problemRef", ref);
        result.putArray("problemProposals").addObject().put("problemRef", ref);
        return result;
    }

    @Test
    void 기존_문제와_새_질문_문제_및_안내_문제의_참조를_실제_ID로_해석한다() {
        JsonNode input = input("b".repeat(64), 1);
        assertThat(resolve(input, resultForProblem(problem.toString()), null, ProblemQuestionWriter.Applied.NONE))
                .containsExactly(Map.entry(problem.toString(), problem));
        var stored = new ProblemQuestionWriter.Applied(java.util.Set.of("new:question"), 1, 0,
                Map.of("new:question", problem));
        assertThat(resolve(input, resultForProblem("new:question"), null, stored))
                .containsExactly(Map.entry("new:question", problem));
        assertThat(resolve(input, resultForProblem("new:guide"),
                new GuideResultWriter.Applied("new:guide", problem, 1), ProblemQuestionWriter.Applied.NONE))
                .containsExactly(Map.entry("new:guide", problem));
    }

    @Test
    void 실행_대상이_아닌_문제의_이미지_관찰은_해석하지_않는다() {
        var input = (tools.jackson.databind.node.ObjectNode) input("b".repeat(64), 1);
        input.putArray("problemIds").add(UUID.randomUUID().toString());
        assertThatThrownBy(() -> resolve(input, resultForProblem(problem.toString()), null,
                ProblemQuestionWriter.Applied.NONE)).isInstanceOf(AgentResultRejectedException.class);
    }

    @Test
    void 같은_실행의_티켓_저장_기록으로_문제_참조를_해석한다() {
        jdbc.update("INSERT INTO agent_ticket_request (run_id, problem_ref, problem_id, request_body) "
                + "VALUES (?, 'new:ticket', ?, '{}'::jsonb)", run, problem);
        try {
            assertThat(resolve(input("b".repeat(64), 1), resultForProblem("new:ticket"), null,
                    ProblemQuestionWriter.Applied.NONE)).containsExactly(Map.entry("new:ticket", problem));
        } finally {
            jdbc.update("DELETE FROM agent_ticket_request WHERE run_id = ?", run);
        }
    }

    @Test
    void 다른_실행의_티켓_참조를_재사용하지_않는다() {
        UUID otherRun = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_run (id, inquiry_id, task, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version) "
                + "VALUES (?, ?, 'INQUIRY_ANALYSIS', 'QUEUED', 1, '{}', 'test', 'test', 'test')", otherRun, inquiry);
        jdbc.update("INSERT INTO agent_ticket_request (run_id, problem_ref, problem_id, request_body) "
                + "VALUES (?, 'new:ticket', ?, '{}'::jsonb)", otherRun, problem);
        try {
            assertThatThrownBy(() -> resolve(input("b".repeat(64), 1), resultForProblem("new:ticket"), null,
                    ProblemQuestionWriter.Applied.NONE)).isInstanceOf(AgentResultRejectedException.class);
        } finally {
            jdbc.update("DELETE FROM agent_ticket_request WHERE run_id = ?", otherRun);
            jdbc.update("DELETE FROM agent_run WHERE id = ?", otherRun);
        }
    }

    @Test
    void 없는_참조나_다른_문의의_문제_또는_충돌하는_참조를_거부한다() {
        JsonNode input = input("b".repeat(64), 1);
        JsonNode result = resultForProblem("new:missing");
        assertThatThrownBy(() -> resolve(input, result, null, ProblemQuestionWriter.Applied.NONE))
                .isInstanceOf(AgentResultRejectedException.class);
        var missing = new ProblemQuestionWriter.Applied(java.util.Set.of(), 0, 0,
                Map.of("new:missing", UUID.randomUUID()));
        assertThatThrownBy(() -> resolve(input, result, null, missing)).isInstanceOf(AgentResultRejectedException.class);
        UUID otherInquiry = UUID.randomUUID();
        UUID otherProblem = UUID.randomUUID();
        jdbc.update("INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status) VALUES (?, ?, ?, '테스트', 'IN_PROGRESS')",
                otherInquiry, otherInquiry.toString().substring(0, 8), member);
        jdbc.update("INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, problem_status) VALUES (?, ?, 1, '테스트', 'CLARIFYING')",
                otherProblem, otherInquiry);
        try {
            var other = new ProblemQuestionWriter.Applied(java.util.Set.of(), 0, 0, Map.of("new:missing", otherProblem));
            assertThatThrownBy(() -> resolve(input, result, null, other)).isInstanceOf(AgentResultRejectedException.class);
            jdbc.update("INSERT INTO agent_ticket_request (run_id, problem_ref, problem_id, request_body) VALUES (?, 'new:missing', ?, '{}'::jsonb)", run, otherProblem);
            assertThatThrownBy(() -> resolve(input, result, null, ProblemQuestionWriter.Applied.NONE))
                    .isInstanceOf(AgentResultRejectedException.class);
        } finally {
            jdbc.update("DELETE FROM agent_ticket_request WHERE run_id = ?", run);
            jdbc.update("DELETE FROM inquiry_problem WHERE id = ?", otherProblem);
            jdbc.update("DELETE FROM inquiry WHERE id = ?", otherInquiry);
        }
        var conflicting = new ProblemQuestionWriter.Applied(java.util.Set.of(), 0, 0,
                Map.of("new:missing", problem));
        assertThatThrownBy(() -> resolve(input, result, new GuideResultWriter.Applied("new:missing", UUID.randomUUID(), 0), conflicting))
                .isInstanceOf(AgentResultRejectedException.class);
    }

    @Test
    void 문제_참조_해석_뒤_관찰_저장은_같은_거래에서_롤백된다() {
        String answer = "오류 화면이 보입니다. 맞나요?";
        storedAnswer(answer);
        JsonNode input = input("b".repeat(64), 1);
        JsonNode result = resultForProblem("new:question");
        var stored = new ProblemQuestionWriter.Applied(java.util.Set.of("new:question"), 1, 0,
                Map.of("new:question", problem));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            observationWriter.storeResolved(run, inquiry, member, answerMessage, input, result, null, stored);
            throw new IllegalStateException("fixture rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_observation WHERE run_id = ?", Integer.class, run)).isZero();
    }

    @Test
    void 새_문제_참조를_해석한_뒤_실제_ID로_관찰을_저장하고_중복_참조는_거부한다() {
        String answer = "오류 화면이 보입니다. 맞나요?";
        storedAnswer(answer);
        JsonNode input = input("b".repeat(64), 1);
        var result = (tools.jackson.databind.node.ObjectNode) resultForProblem("new:question");
        var stored = new ProblemQuestionWriter.Applied(java.util.Set.of("new:question"), 1, 0,
                Map.of("new:question", problem));
        Integer count = new TransactionTemplate(transactions).execute(status -> observationWriter.storeResolved(
                run, inquiry, member, answerMessage, input, result, null, stored));
        assertThat(count).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT problem_id FROM image_observation WHERE run_id = ?", UUID.class, run))
                .isEqualTo(problem);
        jdbc.update("DELETE FROM image_observation WHERE run_id = ?", run);
        var observations = (tools.jackson.databind.node.ArrayNode) result.path("imageObservations");
        observations.add(observations.get(0).deepCopy());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> observationWriter.storeResolved(
                run, inquiry, member, answerMessage, input, result, null, stored))).isInstanceOf(AgentResultRejectedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_observation WHERE run_id = ?", Integer.class, run)).isZero();
    }

    @Test
    void 이미지_출처와_저장된_확인_질문이_일치하면_관찰을_저장한다() {
        String answer = "오류 화면이 보입니다. 맞나요?";
        storedAnswer(answer);
        assertThat(store(input("b".repeat(64), 1), result("오류 화면", answer))).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT observation_state, source_message_id, problem_id, question_bundle_ref "
                + "FROM image_observation WHERE run_id = ?", run)).containsEntry("observation_state", "UNCONFIRMED")
                .containsEntry("source_message_id", userMessage).containsEntry("problem_id", problem)
                .containsEntry("question_bundle_ref", "bundle-1");
    }

    @Test
    void 이미지_무결성이나_메시지_차수가_다르면_저장하지_않는다() {
        String answer = "오류 화면이 보입니다. 맞나요?";
        storedAnswer(answer);
        JsonNode result = result("오류 화면", answer);
        assertThatThrownBy(() -> store(input("c".repeat(64), 1), result)).isInstanceOf(AgentResultRejectedException.class);
        assertThatThrownBy(() -> store(input("b".repeat(64), 2), result)).isInstanceOf(AgentResultRejectedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_observation WHERE run_id = ?", Integer.class, run)).isZero();
    }

    @Test
    void 결과_다른_필드의_민감정보나_관찰과_관계없는_질문을_거부한다() {
        String answer = "오류 화면이 보입니다. 맞나요?";
        storedAnswer(answer);
        JsonNode sensitive = result("오류 화면", answer + " test@example.com");
        assertThatThrownBy(() -> store(input("b".repeat(64), 1), sensitive)).isInstanceOf(AgentResultRejectedException.class);
        var sensitiveKey = (tools.jackson.databind.node.ObjectNode) result("오류 화면", answer);
        sensitiveKey.put("test@example.com", "fixture");
        assertThatThrownBy(() -> store(input("b".repeat(64), 1), sensitiveKey)).isInstanceOf(AgentResultRejectedException.class);
        assertThatThrownBy(() -> store(input("b".repeat(64), 1), result("다른 관찰", answer)))
                .isInstanceOf(AgentResultRejectedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_observation WHERE run_id = ?", Integer.class, run)).isZero();
    }

    @Test
    void 이미지_소유자나_저장된_질문이_다르면_거부한다() {
        String answer = "오류 화면이 보입니다. 맞나요?";
        storedAnswer(answer);
        JsonNode request = input("b".repeat(64), 1);
        JsonNode result = result("오류 화면", answer);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> observationWriter.store(
                run, inquiry, UUID.randomUUID(), answerMessage, request, result, Map.of(problem.toString(), problem))))
                .isInstanceOf(AgentResultRejectedException.class);
        jdbc.update("UPDATE message SET payload = '{}'::jsonb WHERE id = ?", answerMessage);
        assertThatThrownBy(() -> store(request, result)).isInstanceOf(AgentResultRejectedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_observation WHERE run_id = ?", Integer.class, run)).isZero();
    }

    @Test
    void 관찰이_없어도_이미지_실행_결과의_민감정보를_거부한다() {
        JsonNode result = mapper.valueToTree(Map.of("answer", "test@example.com", "imageObservations", List.of()));
        assertThatThrownBy(() -> store(input("b".repeat(64), 1), result))
                .isInstanceOf(AgentResultRejectedException.class);
    }

    @Test
    void 확인_질문이_없는_관찰도_미확인으로만_저장한다() {
        String answer = "확인이 필요합니다.";
        storedAnswer(answer);
        var result = (tools.jackson.databind.node.ObjectNode) result("오류 화면", answer);
        ((tools.jackson.databind.node.ObjectNode) result.path("imageObservations").get(0)).putNull("questionRef");
        assertThat(store(input("b".repeat(64), 1), result)).isEqualTo(1);
        var stored = jdbc.queryForMap("SELECT observation_state, question_bundle_ref FROM image_observation WHERE run_id = ?", run);
        assertThat(stored).containsEntry("observation_state", "UNCONFIRMED").containsEntry("question_bundle_ref", null);
    }

    @Test
    void 결과_거래가_롤백되면_이미_저장한_관찰도_남지_않는다() {
        String answer = "오류 화면이 보입니다. 맞나요?";
        storedAnswer(answer);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            observationWriter.store(run, inquiry, member, answerMessage, input("b".repeat(64), 1),
                    result("오류 화면", answer), Map.of(problem.toString(), problem));
            throw new IllegalStateException("fixture rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_observation WHERE run_id = ?", Integer.class, run)).isZero();
    }
}
