package com.hihops.hops.inquiry.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

import tools.jackson.databind.json.JsonMapper;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest(properties = {"hops.image-attachments.message-link-enabled=true",
        "hops.image-attachments.enabled=true", "hops.image-attachments.bucket=test-private",
        "SPRING_SERVICE_TOKEN=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
class AttachmentBindingControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired ImageAnalysisTargets analysisTargets;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean S3Client s3Client;
    @MockitoBean S3AttachmentStore store;
    private final UUID department = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO department (id, code, name) VALUES (?, ?, '첨부 결합 테스트')", department,
                "IB_" + department.toString().substring(0, 8));
        for (UUID id : List.of(owner, other)) {
            jdbc.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                    + "VALUES (?, ?, ?, 'fixture', '테스트', ?)", id, id.toString(), id.toString(), department);
        }
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM image_attachment_analysis_target WHERE attachment_id IN (SELECT id FROM image_attachment WHERE owner_member_id IN (?, ?))", owner, other);
        jdbc.update("DELETE FROM image_attachment_analysis_history WHERE attachment_id IN (SELECT id FROM image_attachment WHERE owner_member_id IN (?, ?))", owner, other);
        jdbc.update("DELETE FROM image_attachment_problem_link WHERE attachment_id IN (SELECT id FROM image_attachment WHERE owner_member_id IN (?, ?))", owner, other);
        jdbc.update("DELETE FROM image_attachment_problem_link_history WHERE attachment_id IN (SELECT id FROM image_attachment WHERE owner_member_id IN (?, ?))", owner, other);
        jdbc.update("DELETE FROM image_attachment WHERE owner_member_id IN (?, ?)", owner, other);
        jdbc.update("UPDATE inquiry SET current_run_id = NULL WHERE requester_member_id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM message_revision WHERE message_id IN (SELECT id FROM message WHERE inquiry_id IN (SELECT id FROM inquiry WHERE requester_member_id IN (?, ?)))", owner, other);
        jdbc.update("DELETE FROM standard_request WHERE inquiry_problem_id IN (SELECT id FROM inquiry_problem WHERE inquiry_id IN (SELECT id FROM inquiry WHERE requester_member_id IN (?, ?)))", owner, other);
        jdbc.update("DELETE FROM inquiry_problem WHERE inquiry_id IN (SELECT id FROM inquiry WHERE requester_member_id IN (?, ?))", owner, other);
        jdbc.update("DELETE FROM agent_run WHERE inquiry_id IN (SELECT id FROM inquiry WHERE requester_member_id IN (?, ?))", owner, other);
        jdbc.update("DELETE FROM message WHERE inquiry_id IN (SELECT id FROM inquiry WHERE requester_member_id IN (?, ?))", owner, other);
        jdbc.update("DELETE FROM inquiry WHERE requester_member_id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM department WHERE id = ?", department);
    }

    private UUID createWithImage(UUID id) throws Exception {
        UUID request = UUID.randomUUID();
        String response = create(owner, request, createBody(request, List.of(id))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(response).path("resource").path("id").asString());
    }

    private void finishCurrent(UUID inquiry) {
        jdbc.update("UPDATE agent_run SET run_status='SUCCEEDED', started_at=now(), completed_at=now() "
                + "WHERE id=(SELECT current_run_id FROM inquiry WHERE id=?)", inquiry);
    }

    private UUID problem(UUID inquiry, int number) {
        UUID id = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_run (id,inquiry_id,task,run_status,input_version,input_snapshot,model_version,prompt_version,schema_version,started_at,completed_at) "
                + "VALUES (?,?,'INQUIRY_ANALYSIS','SUCCEEDED',1,'{}','test','test','test',now(),now())", run, inquiry);
        jdbc.update("INSERT INTO inquiry_problem (id,inquiry_id,problem_no,title,problem_status) VALUES (?,?,?,'테스트','CLARIFYING')", id, inquiry, number);
        String details = "{\"taskOrScreen\":null,\"requestedAccess\":null,\"businessPurpose\":null,\"expectedBehavior\":null,\"actualBehavior\":null,\"occurredAt\":null,\"currentWorkflow\":null,\"desiredChange\":null,\"acceptanceCriteria\":[]}";
        jdbc.update("INSERT INTO standard_request (inquiry_problem_id,inquiry_version,agent_run_id,intent,work_type,summary,type_detail) "
                + "VALUES (?,1,?,'OBSERVED_SYMPTOM','INVESTIGATION','테스트',?::jsonb)", id, run, details);
        return id;
    }

    private ResultActions link(UUID actor, UUID inquiry, UUID image, UUID request, int inquiryVersion,
            int linkRevision, List<UUID> problems, boolean confirmed) throws Exception {
        return mvc.perform(post("/api/v2/inquiries/{inquiry}/image-attachments/{image}/problem-links", inquiry, image)
                .with(jwt().jwt(token -> token.subject(actor.toString())))
                .header("Idempotency-Key", request).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("requestId", request, "expectedInquiryVersion", inquiryVersion,
                        "expectedLinkRevision", linkRevision, "problemIds", problems, "confirmImageDisclosure", confirmed))));
    }

    @Test
    void 문의자가_이미지_공개_대상을_확인하면_이력과_차수를_저장하고_재전송은_재사용한다() throws Exception {
        UUID image = attachment(owner, "READY", false);
        UUID inquiry = createWithImage(image);
        finishCurrent(inquiry);
        UUID first = problem(inquiry, 1);
        UUID second = problem(inquiry, 2);
        UUID key = UUID.randomUUID();
        link(owner, inquiry, image, key, 1, 0, List.of(first, second), true).andExpect(status().isOk());
        link(owner, inquiry, image, key, 1, 0, List.of(first, second), true).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT version FROM inquiry WHERE id=?", Integer.class, inquiry)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_problem_link_history WHERE attachment_id=?", Integer.class, image)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT problem_id FROM image_attachment_problem_link WHERE attachment_id=?", UUID.class, image))
                .containsExactlyInAnyOrder(first, second);
        mvc.perform(get("/api/v2/image-attachments/{id}", image).with(jwt().jwt(token -> token.subject(owner.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.linkRevision").value(1))
                .andExpect(jsonPath("$.problemIds.length()").value(2));
        link(owner, inquiry, image, key, 1, 0, List.of(first), true).andExpect(status().isConflict());
    }

    @Test
    void 공개_확인과_본인_소유_같은_문의_차수를_검사한다() throws Exception {
        UUID image = attachment(owner, "READY", false);
        UUID inquiry = createWithImage(image);
        finishCurrent(inquiry);
        UUID problem = problem(inquiry, 1);
        UUID key = UUID.randomUUID();
        link(other, inquiry, image, key, 1, 0, List.of(problem), true).andExpect(status().isNotFound());
        key = UUID.randomUUID();
        link(owner, inquiry, image, key, 1, 0, List.of(problem), false).andExpect(status().isBadRequest());
        key = UUID.randomUUID();
        link(owner, inquiry, image, key, 1, 0, List.of(problem, problem), true).andExpect(status().isBadRequest());
        key = UUID.randomUUID();
        link(owner, inquiry, image, key, 1, 0, List.of(UUID.randomUUID()), true).andExpect(status().isBadRequest());
        key = UUID.randomUUID();
        link(owner, inquiry, image, key, 2, 0, List.of(problem), true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INQUIRY_VERSION_CONFLICT"));
        key = UUID.randomUUID();
        link(owner, inquiry, image, key, 1, 1, List.of(problem), true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_LINK_VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_problem_link_history WHERE attachment_id=?", Integer.class, image)).isZero();
    }

    @Test
    void 같은_문의자의_다른_문의에_속한_이미지나_문제도_연결하지_않는다() throws Exception {
        UUID image = attachment(owner, "READY", false);
        UUID inquiry = createWithImage(image);
        finishCurrent(inquiry);
        UUID ownProblem = problem(inquiry, 1);
        UUID createKey = UUID.randomUUID();
        String receipt = create(owner, createKey, createBody(createKey, null)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID otherInquiry = UUID.fromString(mapper.readTree(receipt).path("resource").path("id").asString());
        UUID otherProblem = problem(otherInquiry, 1);
        link(owner, inquiry, image, UUID.randomUUID(), 1, 0, List.of(otherProblem), true).andExpect(status().isBadRequest());
        link(owner, otherInquiry, image, UUID.randomUUID(), 1, 0, List.of(ownProblem), true).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_problem_link_history WHERE attachment_id=?", Integer.class, image)).isZero();
    }

    @Test
    void 공개_철회와_메시지_정정은_연결을_보류하고_이미지와_이력을_보존한다() throws Exception {
        UUID image = attachment(owner, "READY", false);
        UUID inquiry = createWithImage(image);
        finishCurrent(inquiry);
        UUID problem = problem(inquiry, 1);
        UUID key = UUID.randomUUID();
        link(owner, inquiry, image, key, 1, 0, List.of(problem), true).andExpect(status().isOk());
        UUID operation = jdbc.queryForObject("SELECT operation_id FROM idempotency_key WHERE actor_member_id=? AND idempotency_key=?",
                UUID.class, owner, key.toString());
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status ->
                analysisTargets.replace(owner, inquiry, image, operation, 0, List.of(problem)));
        UUID message = jdbc.queryForObject("SELECT message_id FROM image_attachment WHERE id=?", UUID.class, image);
        UUID revise = UUID.randomUUID();
        mvc.perform(post("/api/v2/inquiries/{inquiry}/messages/{message}/revisions", inquiry, message)
                .with(jwt().jwt(token -> token.subject(owner.toString()))).header("Idempotency-Key", revise)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("requestId", revise,
                        "expectedInquiryVersion", 2, "expectedMessageRevision", 1, "content", "정정한 화면 설명"))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_problem_link WHERE attachment_id=?", Integer.class, image)).isZero();
        assertThat(jdbc.queryForList("SELECT reason FROM image_attachment_problem_link_history WHERE attachment_id=? ORDER BY link_revision", String.class, image))
                .containsExactly("USER_CONFIRMED", "MESSAGE_REVISED");
        assertThat(jdbc.queryForObject("SELECT link_revision FROM image_attachment WHERE id=?", Integer.class, image)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_analysis_target WHERE attachment_id=?", Integer.class, image)).isZero();
        assertThat(jdbc.queryForList("SELECT reason FROM image_attachment_analysis_history WHERE attachment_id=? ORDER BY analysis_revision", String.class, image))
                .containsExactly("USER_SELECTED", "MESSAGE_REVISED");
        assertThat(state(image)).isEqualTo("BOUND");
    }

    @Test
    void 빈_문제_목록은_공개를_철회하고_이전_선택을_이력에_남긴다() throws Exception {
        UUID image = attachment(owner, "READY", false);
        UUID inquiry = createWithImage(image);
        finishCurrent(inquiry);
        UUID problem = problem(inquiry, 1);
        link(owner, inquiry, image, UUID.randomUUID(), 1, 0, List.of(problem), true).andExpect(status().isOk());
        link(owner, inquiry, image, UUID.randomUUID(), 2, 1, List.of(), false).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_problem_link WHERE attachment_id=?", Integer.class, image)).isZero();
        var history = jdbc.queryForList("SELECT problem_ids::text FROM image_attachment_problem_link_history WHERE attachment_id=? ORDER BY link_revision", String.class, image);
        assertThat(history).hasSize(2);
        assertThat(mapper.readTree(history.get(0)).get(0).asString()).isEqualTo(problem.toString());
        assertThat(mapper.readTree(history.get(1))).isEmpty();
        mvc.perform(get("/api/v2/image-attachments/{id}", image).with(jwt().jwt(token -> token.subject(owner.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.linkRevision").value(2))
                .andExpect(jsonPath("$.problemIds").isEmpty());
    }

    @Test
    void 공개_변경은_진행_중인_분석을_같은_대상으로_이어가고_티켓을_만들지_않는다() throws Exception {
        UUID image = attachment(owner, "READY", false);
        UUID inquiry = createWithImage(image);
        UUID oldRun = jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE id=?", UUID.class, inquiry);
        UUID key = UUID.randomUUID();
        link(owner, inquiry, image, key, 1, 0, List.of(), false).andExpect(status().isOk());
        UUID next = jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE id=?", UUID.class, inquiry);
        assertThat(next).isNotEqualTo(oldRun);
        assertThat(jdbc.queryForObject("SELECT run_status FROM agent_run WHERE id=?", String.class, oldRun)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT input_version FROM agent_run WHERE id=?", Integer.class, next)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE inquiry_id=?", Integer.class, inquiry)).isZero();
    }

    @Test
    void 공개_변경으로_새_문제_분석을_재개할_때_기존_원문_범위를_유지한다() throws Exception {
        UUID image = attachment(owner, "READY", false);
        UUID inquiry = createWithImage(image);
        UUID original = jdbc.queryForObject("SELECT message_id FROM image_attachment WHERE id=?", UUID.class, image);
        UUID unrelated = UUID.randomUUID();
        jdbc.update("INSERT INTO message (id,inquiry_id,sequence_no,sender_type,content,message_status) "
                + "VALUES (?,?,2,'USER','다른 문제','COMPLETED')", unrelated, inquiry);

        link(owner, inquiry, image, UUID.randomUUID(), 1, 0, List.of(), false).andExpect(status().isOk());

        var input = mapper.readTree(jdbc.queryForObject("SELECT r.input_snapshot::text FROM inquiry i "
                + "JOIN agent_run r ON r.id=i.current_run_id WHERE i.id=?", String.class, inquiry));
        assertThat(input.path("problems").isEmpty()).isTrue();
        assertThat(input.path("messages").size()).isEqualTo(1);
        assertThat(input.path("messages").get(0).path("id").asString()).isEqualTo(original.toString());
    }

    @Test
    void 진행_중인_분석의_대상_문제도_공개_범위_변경_후_유지한다() throws Exception {
        UUID image = attachment(owner, "READY", false);
        UUID inquiry = createWithImage(image);
        UUID problem = problem(inquiry, 1);
        UUID oldRun = jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE id=?", UUID.class, inquiry);
        jdbc.update("UPDATE agent_run SET input_snapshot=jsonb_set(input_snapshot,'{problemIds}',?::jsonb) WHERE id=?",
                mapper.writeValueAsString(List.of(problem)), oldRun);
        link(owner, inquiry, image, UUID.randomUUID(), 1, 0, List.of(problem), true).andExpect(status().isOk());
        UUID next = jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE id=?", UUID.class, inquiry);
        var snapshot = mapper.readTree(jdbc.queryForObject("SELECT input_snapshot::text FROM agent_run WHERE id=?", String.class, next));
        assertThat(snapshot.path("problemIds").get(0).asString()).isEqualTo(problem.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket WHERE inquiry_id=?", Integer.class, inquiry)).isZero();
    }

    @Test
    void 동시_공개_변경은_한_요청만_반영하고_이력을_중복하지_않는다() throws Exception {
        UUID image = attachment(owner, "READY", false);
        UUID inquiry = createWithImage(image);
        finishCurrent(inquiry);
        UUID problem = problem(inquiry, 1);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls = java.util.stream.IntStream.range(0, 2).mapToObj(n -> pool.submit(() -> {
                start.await();
                return link(owner, inquiry, image, UUID.randomUUID(), 1, 0, List.of(problem), true)
                        .andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            assertThat(List.of(calls.get(0).get(10, TimeUnit.SECONDS), calls.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_problem_link_history WHERE attachment_id=?", Integer.class, image)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_problem_link WHERE attachment_id=?", Integer.class, image)).isEqualTo(1);
    }

    @Test
    void 첫_문의에_여러_이미지를_순서대로_연결하고_같은_요청은_재사용한다() throws Exception {
        UUID first = attachment(owner, "READY", false);
        UUID second = attachment(owner, "READY", false);
        UUID request = UUID.randomUUID();
        Map<String, Object> body = createBody(request, List.of(second, first));
        String response = create(owner, request, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        create(owner, request, body).andExpect(status().isOk());
        UUID inquiry = UUID.fromString(mapper.readTree(response).path("resource").path("id").asString());
        List<UUID> ordered = jdbc.queryForList("SELECT a.id FROM image_attachment a JOIN message m ON m.id = a.message_id "
                + "WHERE m.inquiry_id = ? ORDER BY a.message_position", UUID.class, inquiry);
        assertThat(ordered).containsExactly(second, first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiry WHERE requester_member_id = ?", Integer.class, owner)).isEqualTo(1);
        create(owner, UUID.randomUUID(), createBody(UUID.randomUUID(), List.of(first))).andExpect(status().isBadRequest());
        UUID retryKey = UUID.randomUUID();
        create(owner, retryKey, createBody(retryKey, List.of(first))).andExpect(status().isConflict());
    }

    @Test
    void 다른_사용자_첨부가_섞이면_모든_연결과_문의_생성을_취소한다() throws Exception {
        UUID mine = attachment(owner, "READY", false);
        UUID theirs = attachment(other, "READY", false);
        UUID request = UUID.randomUUID();
        create(owner, request, createBody(request, List.of(mine, theirs))).andExpect(status().isNotFound());
        assertThat(state(mine)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiry WHERE requester_member_id = ?", Integer.class, owner)).isZero();
    }

    @Test
    void 후속_메시지는_준비된_첨부만_연결하며_만료되거나_중복된_목록은_거부한다() throws Exception {
        UUID createKey = UUID.randomUUID();
        String response = create(owner, createKey, createBody(createKey, null)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID inquiry = UUID.fromString(mapper.readTree(response).path("resource").path("id").asString());
        UUID good = attachment(owner, "READY", false);
        UUID expired = attachment(owner, "READY", true);
        UUID pending = attachment(owner, "PENDING", false);
        UUID firstKey = UUID.randomUUID();
        send(owner, inquiry, firstKey, List.of(good, expired)).andExpect(status().isConflict());
        assertThat(state(good)).isEqualTo("READY");
        UUID pendingKey = UUID.randomUUID();
        send(owner, inquiry, pendingKey, List.of(good, pending)).andExpect(status().isConflict());
        assertThat(state(good)).isEqualTo("READY");
        UUID duplicateKey = UUID.randomUUID();
        send(owner, inquiry, duplicateKey, List.of(good, good)).andExpect(status().isBadRequest());
        UUID nullKey = UUID.randomUUID();
        send(owner, inquiry, nullKey, java.util.Arrays.asList(good, null)).andExpect(status().isBadRequest());
        UUID limitKey = UUID.randomUUID();
        send(owner, inquiry, limitKey, List.of(good, good, good, good, good, good)).andExpect(status().isBadRequest());
        UUID validKey = UUID.randomUUID();
        send(owner, inquiry, validKey, List.of(good)).andExpect(status().isOk());
        send(owner, inquiry, validKey, List.of(good)).andExpect(status().isOk());
        assertThat(state(good)).isEqualTo("BOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ?", Integer.class, inquiry)).isEqualTo(2);
    }

    @Test
    void 이미지는_메시지가_아니라_문의_전체에서_5장까지만_연결한다() throws Exception {
        UUID createKey = UUID.randomUUID();
        List<UUID> initial = List.of(attachment(owner, "READY", false), attachment(owner, "READY", false),
                attachment(owner, "READY", false));
        String response = create(owner, createKey, createBody(createKey, initial)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID inquiry = UUID.fromString(mapper.readTree(response).path("resource").path("id").asString());
        List<UUID> tooMany = List.of(attachment(owner, "READY", false), attachment(owner, "READY", false),
                attachment(owner, "READY", false));
        send(owner, inquiry, UUID.randomUUID(), tooMany).andExpect(status().isBadRequest());
        assertThat(tooMany).allSatisfy(id -> assertThat(state(id)).isEqualTo("READY"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message WHERE inquiry_id = ?", Integer.class, inquiry)).isEqualTo(1);
        send(owner, inquiry, UUID.randomUUID(), tooMany.subList(0, 2)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment a JOIN message m ON m.id = a.message_id "
                + "WHERE m.inquiry_id = ? AND a.attachment_status = 'BOUND'", Integer.class, inquiry)).isEqualTo(5);
    }

    @Test
    void 기존_본문의_해시는_유지하고_빈_목록은_같은_요청으로_취급한다() throws Exception {
        UUID request = UUID.randomUUID();
        Map<String, Object> body = createBody(request, null);
        create(owner, request, body).andExpect(status().isOk());
        record Legacy(UUID requestId, String content, UUID reportedSystemId) { }
        String oldHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(new Legacy(request, "화면 오류", null))));
        assertThat(jdbc.queryForObject("SELECT request_hash FROM idempotency_key WHERE actor_member_id = ?",
                String.class, owner)).isEqualTo(oldHash);
        body.put("attachmentIds", List.of());
        create(owner, request, body).andExpect(status().isOk());
        body.put("attachmentIds", null);
        create(owner, request, body).andExpect(status().isBadRequest());
    }

    @Test
    void 기존_후속_메시지_해시도_유지한다() throws Exception {
        UUID createKey = UUID.randomUUID();
        String response = create(owner, createKey, createBody(createKey, null)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID inquiry = UUID.fromString(mapper.readTree(response).path("resource").path("id").asString());
        UUID request = UUID.randomUUID();
        Map<String, Object> body = new HashMap<>(Map.of("requestId", request, "expectedInquiryVersion", 1,
                "content", "추가 화면", "problemIds", List.of()));
        var base = post("/api/v2/inquiries/" + inquiry + "/messages").with(jwt().jwt(token -> token.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", request);
        mvc.perform(base.content(mapper.writeValueAsBytes(body))).andExpect(status().isOk());
        record Legacy(UUID requestId, Integer expectedInquiryVersion, String content, List<UUID> problemIds) { }
        String oldHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(new Legacy(request, 1, "추가 화면", List.of()))));
        assertThat(jdbc.queryForObject("SELECT request_hash FROM idempotency_key WHERE actor_member_id = ? AND idempotency_key = ?",
                String.class, owner, request.toString())).isEqualTo(oldHash);
        body.put("attachmentIds", List.of());
        mvc.perform(base.content(mapper.writeValueAsBytes(body))).andExpect(status().isOk());
        body.put("attachmentIds", null);
        mvc.perform(base.content(mapper.writeValueAsBytes(body))).andExpect(status().isBadRequest());
    }

    @Test
    void 같은_이미지를_두_문의에_동시에_연결하면_하나만_성공한다() throws Exception {
        UUID id = attachment(owner, "READY", false);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> create(owner, a, createBody(a, List.of(id))).andReturn().getResponse().getStatus());
            var second = executor.submit(() -> create(owner, b, createBody(b, List.of(id))).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiry WHERE requester_member_id = ?", Integer.class, owner)).isEqualTo(1);
        assertThat(state(id)).isEqualTo("BOUND");
    }

    @Test
    void 문의자는_연결된_이미지만_무결성_검사_후_조회한다() throws Exception {
        UUID id = attachment(owner, "READY", false);
        byte[] png = {1, 2, 3};
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        jdbc.update("UPDATE image_attachment SET content_sha256 = ? WHERE id = ?", digest, id);
        UUID request = UUID.randomUUID();
        String response = create(owner, request, createBody(request, List.of(id))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID inquiry = UUID.fromString(mapper.readTree(response).path("resource").path("id").asString());
        String path = "/api/v2/inquiries/" + inquiry + "/image-attachments/" + id + "/content";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt().jwt(token -> token.subject(other.toString())))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/inquiries/" + UUID.randomUUID() + "/image-attachments/" + id + "/content")
                .with(jwt().jwt(token -> token.subject(owner.toString())))).andExpect(status().isNotFound());
        verify(store, never()).get(any());
        mvc.perform(get("/api/v2/inquiries/" + inquiry + "/messages")
                .with(jwt().jwt(token -> token.subject(owner.toString())))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].attachments[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].attachments[0].byteSize").value(3));
        when(store.get(id)).thenReturn(png, new byte[]{3, 2, 1});
        mvc.perform(get(path).with(jwt().jwt(token -> token.subject(owner.toString())))).andExpect(status().isOk())
                .andExpect(content().bytes(png)).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get(path).with(jwt().jwt(token -> token.subject(owner.toString())))).andExpect(status().isServiceUnavailable());
    }

    @Test
    void 저장소를_읽는_사이_문의_권한이_없어지면_이미지를_반환하지_않는다() throws Exception {
        UUID id = attachment(owner, "READY", false);
        byte[] png = {1, 2, 3};
        jdbc.update("UPDATE image_attachment SET content_sha256 = ? WHERE id = ?",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)), id);
        UUID request = UUID.randomUUID();
        String response = create(owner, request, createBody(request, List.of(id))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID inquiry = UUID.fromString(mapper.readTree(response).path("resource").path("id").asString());
        when(store.get(id)).thenAnswer(invocation -> {
            jdbc.update("UPDATE inquiry SET deleted_at = now() WHERE id = ?", inquiry);
            return png;
        });
        mvc.perform(get("/api/v2/inquiries/" + inquiry + "/image-attachments/" + id + "/content")
                .with(jwt().jwt(token -> token.subject(owner.toString())))).andExpect(status().isNotFound());
    }

    @Test
    void 내부_이미지_조회는_서비스_인증과_실행_참조_버전_무결성을_확인한다() throws Exception {
        UUID id = attachment(owner, "READY", false);
        byte[] png = {1, 2, 3};
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        jdbc.update("UPDATE image_attachment SET content_sha256 = ? WHERE id = ?", digest, id);
        UUID request = UUID.randomUUID();
        String response = create(owner, request, createBody(request, List.of(id))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID inquiry = UUID.fromString(mapper.readTree(response).path("resource").path("id").asString());
        UUID run = jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE id = ?", UUID.class, inquiry);
        String snapshot = jdbc.queryForObject("SELECT input_snapshot::text FROM agent_run WHERE id = ?", String.class, run);
        assertThat(snapshot).contains(id.toString(), digest).doesNotContain("AQID");
        String path = "/internal/v2/runs/" + run + "/image-attachments/" + id + "?inquiryVersion=1";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt().jwt(token -> token.subject(owner.toString())))).andExpect(status().isUnauthorized());
        String auth = "Bearer " + "A".repeat(43);
        mvc.perform(get("/internal/v2/runs/" + run + "/image-attachments/" + UUID.randomUUID() + "?inquiryVersion=1")
                .header("Authorization", auth)).andExpect(status().isNotFound());
        verify(store, never()).get(any());
        when(store.get(id)).thenReturn(png, new byte[]{3, 2, 1});
        mvc.perform(get(path).header("Authorization", auth)).andExpect(status().isOk()).andExpect(content().bytes(png))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get(path).header("Authorization", auth)).andExpect(status().isServiceUnavailable());
        jdbc.update("UPDATE message SET revision = 2 WHERE inquiry_id = ?", inquiry);
        mvc.perform(get(path).header("Authorization", auth)).andExpect(status().isNotFound());
    }

    @Test
    void 내부_이미지를_읽는_동안_문의_버전이_바뀌면_바이트를_반환하지_않는다() throws Exception {
        UUID id = attachment(owner, "READY", false);
        byte[] png = {1, 2, 3};
        jdbc.update("UPDATE image_attachment SET content_sha256 = ? WHERE id = ?",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)), id);
        UUID request = UUID.randomUUID();
        String response = create(owner, request, createBody(request, List.of(id))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID inquiry = UUID.fromString(mapper.readTree(response).path("resource").path("id").asString());
        UUID run = jdbc.queryForObject("SELECT current_run_id FROM inquiry WHERE id = ?", UUID.class, inquiry);
        when(store.get(id)).thenAnswer(invocation -> {
            jdbc.update("UPDATE inquiry SET version = version + 1 WHERE id = ?", inquiry);
            return png;
        });
        mvc.perform(get("/internal/v2/runs/" + run + "/image-attachments/" + id + "?inquiryVersion=1")
                .header("Authorization", "Bearer " + "A".repeat(43))).andExpect(status().isConflict());
    }

    private UUID attachment(UUID actor, String state, boolean expired) {
        UUID operation = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO idempotency_key (actor_member_id, contract_version, http_method, request_path,
                  idempotency_key, request_hash, operation_id, operation_state, expires_at)
                VALUES (?, 'hops-system-1', 'POST', '/api/v2/image-attachments', ?, ?, ?, 'PENDING', now() + interval '1 day')
                """, actor, UUID.randomUUID().toString(), "a".repeat(64), operation);
        jdbc.update("""
                INSERT INTO image_attachment (id, owner_member_id, operation_id, input_sha256, content_sha256,
                   byte_size, attachment_status, expires_at)
                VALUES (?, ?, ?, ?, ?, 3, ?, CASE WHEN ? = 'PENDING' THEN NULL
                    WHEN ? THEN now() - interval '1 second' ELSE now() + interval '1 day' END)
                """, id, actor, operation, "a".repeat(64), "b".repeat(64), state, state, expired);
        return id;
    }

    private String state(UUID id) {
        return jdbc.queryForObject("SELECT attachment_status FROM image_attachment WHERE id = ?", String.class, id);
    }

    private Map<String, Object> createBody(UUID request, List<UUID> ids) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", request);
        body.put("content", "화면 오류");
        body.put("reportedSystemId", null);
        if (ids != null) body.put("attachmentIds", ids);
        return body;
    }

    private ResultActions create(UUID actor, UUID request, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v2/inquiries").with(jwt().jwt(token -> token.subject(actor.toString())))
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", request).content(mapper.writeValueAsBytes(body)));
    }

    private ResultActions send(UUID actor, UUID inquiry, UUID request, List<UUID> ids) throws Exception {
        return mvc.perform(post("/api/v2/inquiries/" + inquiry + "/messages")
                .with(jwt().jwt(token -> token.subject(actor.toString())))
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", request)
                .content(mapper.writeValueAsBytes(Map.of("requestId", request, "expectedInquiryVersion", 1,
                        "content", "추가 화면", "problemIds", List.of(), "attachmentIds", ids))));
    }
}
