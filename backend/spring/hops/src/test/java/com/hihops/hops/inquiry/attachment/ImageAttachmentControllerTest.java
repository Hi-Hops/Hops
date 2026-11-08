package com.hihops.hops.inquiry.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

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

import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"hops.image-attachments.enabled=true", "hops.image-attachments.bucket=test-private"})
@AutoConfigureMockMvc
@ActiveProfiles({"db", "test"})
class ImageAttachmentControllerTest {
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
        jdbc.update("INSERT INTO department (id, code, name) VALUES (?, ?, '첨부 테스트')", department,
                "IMG_" + department.toString().substring(0, 8));
        for (UUID id : new UUID[]{owner, other}) {
            jdbc.update("INSERT INTO member (id, login_id, employee_no, password_hash, name, department_id) "
                    + "VALUES (?, ?, ?, 'fixture', '테스트', ?)", id, id.toString(), id.toString(), department);
        }
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM image_attachment WHERE owner_member_id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM idempotency_key WHERE actor_member_id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM member WHERE id IN (?, ?)", owner, other);
        jdbc.update("DELETE FROM department WHERE id = ?", department);
    }

    @Test
    void 저장_미확인_후_같은_키로_확인하며_소유자만_메타데이터를_조회한다() throws Exception {
        when(store.putOrVerify(any(), any())).thenReturn(S3AttachmentStore.SaveResult.UNKNOWN, S3AttachmentStore.SaveResult.STORED);
        UUID request = UUID.randomUUID();
        String body = body(request, image(0xff112233));
        String first = upload(owner, request, body).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PENDING")).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(first).path("resource").path("id").asString();
        upload(owner, request, body).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.resource.id").value(id));
        upload(owner, request, body).andExpect(status().isOk()).andExpect(jsonPath("$.resource.id").value(id));
        upload(owner, request, body(request, image(0xff445566))).andExpect(status().isConflict());
        mvc.perform(get("/api/v2/image-attachments/" + id).with(jwt().jwt(token -> token.subject(owner.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.problemIds").isEmpty());
        mvc.perform(get("/api/v2/image-attachments/" + id).with(jwt().jwt(token -> token.subject(other.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/image-attachments/" + id)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment WHERE owner_member_id = ?", Integer.class, owner))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT response_body::text FROM idempotency_key WHERE actor_member_id = ?",
                String.class, owner)).doesNotContain("dataBase64");
    }

    @Test
    void 인증되지_않은_요청과_위장_이미지는_저장하지_않는다() throws Exception {
        UUID request = UUID.randomUUID();
        String body = body(request, Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        upload(owner, request, body).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v2/image-attachments").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", request).content(body)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment", Integer.class)).isZero();
    }

    @Test
    void 요청_키가_다르거나_본문_크기를_초과하면_접수하지_않는다() throws Exception {
        UUID request = UUID.randomUUID();
        mvc.perform(post("/api/v2/image-attachments").with(jwt().jwt(token -> token.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID())
                .content(body(request, image(0xff112233)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v2/image-attachments").with(jwt().jwt(token -> token.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", request)
                .content(new byte[7 * 1024 * 1024 + 1])).andExpect(status().isPayloadTooLarge());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_key WHERE actor_member_id = ?", Integer.class, owner))
                .isZero();
    }

    @Test
    void 메시지_연결_실험_설정이_꺼져_있으면_첨부된_문의를_접수하지_않는다() throws Exception {
        when(store.putOrVerify(any(), any())).thenReturn(S3AttachmentStore.SaveResult.STORED);
        UUID uploadRequest = UUID.randomUUID();
        String result = upload(owner, uploadRequest, body(uploadRequest, image(0xff112233)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String attachmentId = mapper.readTree(result).path("resource").path("id").asString();
        UUID inquiryRequest = UUID.randomUUID();
        mvc.perform(post("/api/v2/inquiries").with(jwt().jwt(token -> token.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", inquiryRequest)
                .content(mapper.writeValueAsBytes(Map.of("requestId", inquiryRequest, "content", "오류 화면",
                        "attachmentIds", List.of(attachmentId)))))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inquiry WHERE requester_member_id = ?", Integer.class, owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT attachment_status FROM image_attachment WHERE owner_member_id = ?",
                String.class, owner)).isEqualTo("READY");
    }

    @Test
    void 같은_키가_동시에_와도_하나의_첨부와_영수증만_확정한다() throws Exception {
        CountDownLatch writes = new CountDownLatch(2);
        when(store.putOrVerify(any(), any())).thenAnswer(invocation -> {
            writes.countDown();
            if (!writes.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("동시 요청을 확인하지 못했습니다");
            return S3AttachmentStore.SaveResult.STORED;
        });
        UUID request = UUID.randomUUID();
        String body = body(request, image(0xff112233));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> upload(owner, request, body).andReturn().getResponse());
            var second = executor.submit(() -> upload(owner, request, body).andReturn().getResponse());
            var firstResponse = first.get(20, TimeUnit.SECONDS);
            var secondResponse = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResponse.getStatus()).isEqualTo(200);
            assertThat(secondResponse.getStatus()).isEqualTo(200);
            assertThat(mapper.readTree(firstResponse.getContentAsString()).path("resource").path("id").asString())
                    .isEqualTo(mapper.readTree(secondResponse.getContentAsString()).path("resource").path("id").asString());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment WHERE owner_member_id = ?", Integer.class, owner))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_key WHERE actor_member_id = ?", Integer.class, owner))
                .isEqualTo(1);
    }

    @Test
    void 객체_확인_후_DB_확정에_실패하면_성공_영수증을_남기지_않는다() throws Exception {
        when(store.putOrVerify(any(), any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            jdbc.update("UPDATE image_attachment SET content_sha256 = ? WHERE id = ?", "f".repeat(64), id);
            return S3AttachmentStore.SaveResult.STORED;
        });
        UUID request = UUID.randomUUID();
        upload(owner, request, body(request, image(0xff112233))).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT operation_state FROM idempotency_key WHERE actor_member_id = ?",
                String.class, owner)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attachment_status FROM image_attachment WHERE owner_member_id = ?",
                String.class, owner)).isEqualTo("PENDING");
    }

    private org.springframework.test.web.servlet.ResultActions upload(UUID actor, UUID request, String body) throws Exception {
        return mvc.perform(post("/api/v2/image-attachments").with(jwt().jwt(token -> token.subject(actor.toString())))
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", request).content(body));
    }

    private String body(UUID request, String encoded) {
        return mapper.writeValueAsString(new ImageAttachmentController.UploadCommand(request, "image/png", encoded));
    }

    private String image(int pixel) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, pixel);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
