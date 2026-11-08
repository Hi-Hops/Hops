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
        jdbc.update("DELETE FROM image_attachment WHERE owner_member_id IN (?, ?)", owner, other);
        jdbc.update("UPDATE inquiry SET current_run_id = NULL WHERE requester_member_id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM agent_run WHERE inquiry_id IN (SELECT id FROM inquiry WHERE requester_member_id IN (?, ?))", owner, other);
        jdbc.update("DELETE FROM message WHERE inquiry_id IN (SELECT id FROM inquiry WHERE requester_member_id IN (?, ?))", owner, other);
        jdbc.update("DELETE FROM inquiry WHERE requester_member_id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM department WHERE id = ?", department);
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
