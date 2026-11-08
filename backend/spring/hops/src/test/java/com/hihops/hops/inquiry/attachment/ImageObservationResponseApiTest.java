package com.hihops.hops.inquiry.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"hops.image-attachments.enabled=true", "hops.image-attachments.bucket=test-private"})
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
class ImageObservationResponseApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @MockitoBean S3Client s3;
    @MockitoBean S3AttachmentStore storage;
    private final UUID department = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID inquiry = UUID.randomUUID();
    private final UUID source = UUID.randomUUID();
    private final UUID answer = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();
    private final UUID problem = UUID.randomUUID();
    private final UUID image = UUID.randomUUID();
    private final UUID uploadOperation = UUID.randomUUID();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO department (id, code, name) VALUES (?, ?, '테스트')", department, department.toString().substring(0, 8));
        for (UUID member : List.of(owner, other)) {
            jdbc.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) VALUES (?, ?, ?, 'fixture', '테스트', ?)",
                    member, member.toString(), member.toString(), department);
        }
        jdbc.update("INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status) VALUES (?, ?, ?, '테스트', 'IN_PROGRESS')",
                inquiry, inquiry.toString().substring(0, 8), owner);
        jdbc.update("INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, message_status) VALUES (?, ?, 1, 'USER', '테스트', 'COMPLETED')", source, inquiry);
        String payload = mapper.writeValueAsString(Map.of("answerRunId", run.toString(), "questionBundles",
                List.of(Map.of("problemId", problem.toString(), "bundleRef", "bundle-1"))));
        jdbc.update("INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, payload, message_status) VALUES (?, ?, 2, 'AI', '오류 화면인가요?', ?::jsonb, 'COMPLETED')", answer, inquiry, payload);
        jdbc.update("INSERT INTO agent_run (id, inquiry_id, task, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, started_at, completed_at) "
                + "VALUES (?, ?, 'INQUIRY_ANALYSIS', 'SUCCEEDED', 1, '{}', 'test', 'test', 'test', now(), now())", run, inquiry);
        jdbc.update("INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, problem_status) VALUES (?, ?, 1, '테스트', 'CLARIFYING')", problem, inquiry);
        jdbc.update("INSERT INTO idempotency_key (actor_member_id, contract_version, http_method, request_path, idempotency_key, request_hash, operation_id, operation_state, expires_at) "
                + "VALUES (?, 'test', 'POST', '/test', ?, ?, ?, 'PENDING', now() + interval '1 day')", owner, uploadOperation.toString(), "a".repeat(64), uploadOperation);
        jdbc.update("INSERT INTO image_attachment (id, owner_member_id, operation_id, input_sha256, content_sha256, byte_size, attachment_status, message_id, message_position) "
                + "VALUES (?, ?, ?, ?, ?, 1, 'BOUND', ?, 0)", image, owner, uploadOperation, "a".repeat(64), "b".repeat(64), source);
        for (UUID id : List.of(first, second)) {
            jdbc.update("INSERT INTO image_observation (id, inquiry_id, run_id, observation_ref, attachment_id, source_message_id, source_revision, problem_id, answer_message_id, observation_text, question_bundle_ref) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, '오류 화면', 'bundle-1')", id, inquiry, run, id.toString(), image, source, problem, answer);
        }
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM image_observation_response WHERE observation_id IN (?, ?)", first, second);
        jdbc.update("DELETE FROM image_observation WHERE inquiry_id = ?", inquiry);
        jdbc.update("DELETE FROM image_attachment WHERE id = ?", image);
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM inquiry_problem WHERE id = ?", problem);
        jdbc.update("DELETE FROM agent_run WHERE id = ?", run);
        jdbc.update("DELETE FROM message WHERE inquiry_id = ?", inquiry);
        jdbc.update("DELETE FROM inquiry WHERE id = ?", inquiry);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM department WHERE id = ?", department);
    }

    private Map<String, Object> response(UUID id, int revision, String decision) {
        return Map.of("observationId", id, "expectedObservationRevision", revision, "decision", decision);
    }

    private Map<String, Object> body(UUID request, int version, List<?> responses) {
        return Map.of("requestId", request, "expectedInquiryVersion", version, "responses", responses);
    }

    private ResultActions send(UUID actor, UUID request, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v2/inquiries/{id}/image-observation-responses", inquiry)
                .with(jwt().jwt(token -> token.subject(actor.toString())))
                .header("Idempotency-Key", request).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body)));
    }

    @Test
    void 여러_응답을_원자적으로_저장하고_같은_요청은_재사용한다() throws Exception {
        UUID request = UUID.randomUUID();
        var body = body(request, 1, List.of(response(first, 1, "CONFIRMED"), response(second, 1, "UNKNOWN")));
        send(owner, request, body).andExpect(status().isOk()).andExpect(jsonPath("$.resource.type").value("INQUIRY"));
        send(owner, request, body).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?", Integer.class, inquiry)).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT observation_state, observation_revision FROM image_observation WHERE id = ?", first))
                .containsEntry("observation_state", "CONFIRMED").containsEntry("observation_revision", 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_observation_response WHERE observation_id IN (?, ?)", Integer.class, first, second)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT observation_state FROM image_observation WHERE id = ?", String.class, second)).isEqualTo("UNKNOWN");
        send(owner, request, body(request, 1, List.of(response(first, 1, "INCORRECT"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 다른_사람과_없는_관찰은_404이고_일부도_저장하지_않는다() throws Exception {
        UUID key = UUID.randomUUID();
        send(other, key, body(key, 1, List.of(response(first, 1, "CONFIRMED")))).andExpect(status().isNotFound());
        key = UUID.randomUUID();
        send(owner, key, body(key, 1, List.of(response(first, 1, "CONFIRMED"), response(UUID.randomUUID(), 1, "UNKNOWN"))))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_observation_response", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?", Integer.class, inquiry)).isEqualTo(1);
    }

    @Test
    void 차수_충돌과_중복_응답은_전체_요청을_거부한다() throws Exception {
        UUID key = UUID.randomUUID();
        send(owner, key, body(key, 2, List.of(response(first, 1, "CONFIRMED"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"));
        key = UUID.randomUUID();
        send(owner, key, body(key, 1, List.of(response(first, 1, "CONFIRMED"), response(second, 2, "UNKNOWN"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OBSERVATION_VERSION_CONFLICT"));
        key = UUID.randomUUID();
        send(owner, key, body(key, 1, List.of(response(first, 1, "CONFIRMED"), response(first, 1, "UNKNOWN"))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_observation_response", Integer.class)).isZero();
    }

    @Test
    void 이미지_원문_차수가_달라지거나_확인_질문이_사라지면_응답할_수_없다() throws Exception {
        jdbc.update("UPDATE message SET revision = 2 WHERE id = ?", source);
        UUID key = UUID.randomUUID();
        send(owner, key, body(key, 1, List.of(response(first, 1, "CONFIRMED")))).andExpect(status().isNotFound());
        jdbc.update("UPDATE message SET revision = 1 WHERE id = ?", source);
        jdbc.update("UPDATE message SET payload = '{}'::jsonb WHERE id = ?", answer);
        key = UUID.randomUUID();
        send(owner, key, body(key, 1, List.of(response(first, 1, "CONFIRMED")))).andExpect(status().isNotFound());
    }

    @Test
    void 동시에_다른_응답을_보내면_하나만_저장한다() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var requests = List.of("CONFIRMED", "INCORRECT").stream().map(decision -> pool.submit(() -> {
                UUID key = UUID.randomUUID();
                start.await();
                return send(owner, key, body(key, 1, List.of(response(first, 1, decision))))
                        .andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            assertThat(List.of(requests.get(0).get(10, TimeUnit.SECONDS), requests.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_observation_response WHERE observation_id = ?", Integer.class, first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?", Integer.class, inquiry)).isEqualTo(2);
    }

    @Test
    void 최신_차수로_정정하면_이전_응답도_이력에_남긴다() throws Exception {
        UUID firstKey = UUID.randomUUID();
        send(owner, firstKey, body(firstKey, 1, List.of(response(first, 1, "CONFIRMED")))).andExpect(status().isOk());
        UUID nextKey = UUID.randomUUID();
        send(owner, nextKey, body(nextKey, 2, List.of(response(first, 2, "INCORRECT")))).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT decision FROM image_observation_response WHERE observation_id = ? ORDER BY observation_revision", String.class, first))
                .containsExactly("CONFIRMED", "INCORRECT");
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id = ?", Integer.class, inquiry)).isEqualTo(3);
    }

    @Test
    void 문의자_이력은_현재_확인_요청과_응답_상태를_보여준다() throws Exception {
        var request = get("/api/v2/inquiries/{id}/messages", inquiry).with(jwt().jwt(token -> token.subject(owner.toString())));
        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].imageObservationQuestions.length()").value(2))
                .andExpect(jsonPath("$.items[1].imageObservationQuestions[0].state").value("UNCONFIRMED"));
        mvc.perform(get("/api/v2/inquiries/{id}/messages", inquiry).with(jwt().jwt(token -> token.subject(other.toString()))))
                .andExpect(status().isNotFound());
        UUID key = UUID.randomUUID();
        send(owner, key, body(key, 1, List.of(response(first, 1, "CONFIRMED")))).andExpect(status().isOk());
        var page = mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var items = page.path("items").get(1).path("imageObservationQuestions");
        boolean confirmed = false;
        for (var item : items) {
            if (first.toString().equals(item.path("observationId").asString())) {
                confirmed = "CONFIRMED".equals(item.path("state").asString())
                        && item.path("observationRevision").asInt() == 2;
            }
        }
        assertThat(confirmed).isTrue();
    }

    @Test
    void 안내가_권한_검사로_숨겨지면_관찰도_숨기고_응답을_거부한다() throws Exception {
        String payload = mapper.writeValueAsString(Map.of("answerRunId", run.toString(), "questionBundles",
                List.of(Map.of("problemId", problem.toString(), "bundleRef", "bundle-1")),
                "guide", Map.of("text", "숨긴 안내", "evidenceIds", List.of())));
        jdbc.update("UPDATE message SET payload = ?::jsonb WHERE id = ?", payload, answer);
        mvc.perform(get("/api/v2/inquiries/{id}/messages", inquiry).with(jwt().jwt(token -> token.subject(owner.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[1].imageObservationQuestions").doesNotExist());
        UUID key = UUID.randomUUID();
        send(owner, key, body(key, 1, List.of(response(first, 1, "CONFIRMED")))).andExpect(status().isNotFound());
    }
}
