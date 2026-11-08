package com.hihops.hops.inquiry.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hihops.hops.realtime.dto.TicketChangedEvent;
import com.hihops.hops.ticket.controller.TicketCommandFixture;

import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest(properties = {"hops.agent.dispatch-enabled=false", "hops.image-attachments.enabled=true",
        "hops.image-attachments.bucket=test-private"})
@RecordApplicationEvents
class StaffImageControllerTest extends TicketCommandFixture {
    @MockitoBean S3Client s3;
    @MockitoBean S3AttachmentStore storage;
    @Autowired ApplicationEvents events;
    private final UUID source = UUID.randomUUID();
    private final UUID image = UUID.randomUUID();
    private final UUID uploadOperation = UUID.randomUUID();
    private final byte[] bytes = {1, 2, 3, 4};

    @BeforeEach
    void imageFixture() throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        jdbc.update("INSERT INTO message(id,inquiry_id,sequence_no,sender_type,content,message_status) "
                + "VALUES (?,?,2,'USER','오류 화면 설명','COMPLETED')", source, inquiry);
        jdbc.update("INSERT INTO idempotency_key (actor_member_id, contract_version, http_method, request_path, idempotency_key, request_hash, operation_id, operation_state, expires_at) "
                + "VALUES (?, 'test', 'POST', '/upload', ?, ?, ?, 'PENDING', now() + interval '1 day')",
                requester, uploadOperation.toString(), "a".repeat(64), uploadOperation);
        jdbc.update("INSERT INTO image_attachment (id,owner_member_id,operation_id,input_sha256,content_sha256,byte_size,attachment_status,message_id,message_position) "
                + "VALUES (?,?,?,?,?,4,'BOUND',?,0)", image, requester, uploadOperation, "a".repeat(64), digest, source);
    }

    @AfterEach
    void imageCleanup() {
        jdbc.update("DELETE FROM image_attachment_problem_link WHERE attachment_id=?", image);
        jdbc.update("DELETE FROM image_attachment_problem_link_history WHERE attachment_id=?", image);
        jdbc.update("DELETE FROM image_attachment WHERE id=?", image);
    }

    private void share(UUID problemId) throws Exception {
        UUID request = UUID.randomUUID();
        var payload = mapper.valueToTree(Map.of("requestId", request, "expectedInquiryVersion", 1,
                "expectedLinkRevision", 0, "problemIds", List.of(problemId), "confirmImageDisclosure", true));
        mvc.perform(command(requester, "/inquiries/" + inquiry + "/image-attachments/" + image + "/problem-links", payload))
                .andExpect(status().isOk());
    }

    @Test
    void 문의자가_공개한_이미지만_현재_담당자에게_목록과_내용을_반환한다() throws Exception {
        when(storage.get(image)).thenReturn(bytes);
        mvc.perform(as(staff, get("/api/v2/tickets/{ticket}/image-attachments", ticket)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(staff, get("/api/v2/tickets/{ticket}/image-attachments/{image}/content", ticket, image)))
                .andExpect(status().isNotFound());
        verify(storage, never()).get(image);

        share(problem);
        assertThat(events.stream(TicketChangedEvent.class).anyMatch(event -> event.ticketId().equals(ticket)
                && event.systemIds().contains(system))).isTrue();
        mvc.perform(as(staff, get("/api/v2/tickets/{ticket}/image-attachments", ticket)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$[0].id").value(image.toString()));
        mvc.perform(as(staff, get("/api/v2/tickets/{ticket}/image-attachments/{image}/content", ticket, image)))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(as(other, get("/api/v2/tickets/{ticket}/image-attachments/{image}/content", ticket, image)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 담당자_변경과_메시지_정정은_이전_접근을_차단한다() throws Exception {
        when(storage.get(image)).thenReturn(bytes);
        share(problem);
        jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", other, system);
        mvc.perform(as(staff, get("/api/v2/tickets/{ticket}/image-attachments/{image}/content", ticket, image)))
                .andExpect(status().isNotFound());
        mvc.perform(as(other, get("/api/v2/tickets/{ticket}/image-attachments/{image}/content", ticket, image)))
                .andExpect(status().isOk());
        jdbc.update("UPDATE message SET revision=2 WHERE id=?", source);
        mvc.perform(as(other, get("/api/v2/tickets/{ticket}/image-attachments/{image}/content", ticket, image)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 저장소를_읽는_동안_공개가_철회되면_바이트를_반환하지_않는다() throws Exception {
        share(problem);
        when(storage.get(image)).thenAnswer(invocation -> {
            jdbc.update("DELETE FROM image_attachment_problem_link WHERE attachment_id=?", image);
            return bytes;
        });
        mvc.perform(as(staff, get("/api/v2/tickets/{ticket}/image-attachments/{image}/content", ticket, image)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 읽는_동안_같은_문제로_다시_공개돼도_이전_권한을_재사용하지_않는다() throws Exception {
        share(problem);
        when(storage.get(image)).thenAnswer(invocation -> {
            UUID request = UUID.randomUUID();
            var payload = mapper.valueToTree(Map.of("requestId", request, "expectedInquiryVersion", 2,
                    "expectedLinkRevision", 1, "problemIds", List.of(problem), "confirmImageDisclosure", true));
            mvc.perform(command(requester, "/inquiries/" + inquiry + "/image-attachments/" + image + "/problem-links", payload))
                    .andExpect(status().isOk());
            return bytes;
        });
        mvc.perform(as(staff, get("/api/v2/tickets/{ticket}/image-attachments/{image}/content", ticket, image)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 저장소를_읽는_동안_담당자가_바뀌면_이전_담당자에게_바이트를_반환하지_않는다() throws Exception {
        share(problem);
        when(storage.get(image)).thenAnswer(invocation -> {
            jdbc.update("UPDATE it_system SET primary_owner_member_id=? WHERE id=?", other, system);
            return bytes;
        });
        mvc.perform(as(staff, get("/api/v2/tickets/{ticket}/image-attachments/{image}/content", ticket, image)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_CONTEXT_CHANGED"));
    }

    @Test
    void 확인_이력과_현재_연결이_어긋나면_이미지를_숨긴다() throws Exception {
        share(problem);
        jdbc.update("UPDATE image_attachment_problem_link_history SET problem_ids='[]'::jsonb WHERE attachment_id=?", image);
        mvc.perform(as(staff, get("/api/v2/tickets/{ticket}/image-attachments", ticket)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(staff, get("/api/v2/tickets/{ticket}/image-attachments/{image}/content", ticket, image)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 저장_바이트의_무결성이_다르면_이미지를_반환하지_않는다() throws Exception {
        share(problem);
        when(storage.get(image)).thenReturn(new byte[] {4, 3, 2, 1});
        mvc.perform(as(staff, get("/api/v2/tickets/{ticket}/image-attachments/{image}/content", ticket, image)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void 다른_문제에만_공개된_이미지는_현재_티켓에_노출하지_않는다() throws Exception {
        UUID otherProblem = UUID.randomUUID();
        jdbc.update("INSERT INTO inquiry_problem(id,inquiry_id,problem_no,title,problem_status) VALUES (?,?,2,'다른 문제','CLARIFYING')", otherProblem, inquiry);
        share(otherProblem);
        mvc.perform(as(staff, get("/api/v2/tickets/{ticket}/image-attachments", ticket)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(staff, get("/api/v2/tickets/{ticket}/image-attachments/{image}/content", ticket, image)))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM image_attachment_problem_link WHERE attachment_id=?", Integer.class, image)).isEqualTo(1);
    }
}
