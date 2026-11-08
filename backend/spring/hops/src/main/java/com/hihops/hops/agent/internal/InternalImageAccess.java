package com.hihops.hops.agent.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.agent.dto.AiRunRequest.ImageRef;
import com.hihops.hops.agent.dto.AiRunRequest.InputMessage;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.SenderType;

/** 현재 실행과 고정된 참조의 접근을 확인한다. 외부 저장소 호출 전에 거래를 끝낸다. */
@Service
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
class InternalImageAccess {
    private final InternalRunAccess runAccess;
    private final JdbcTemplate jdbc;

    InternalImageAccess(InternalRunAccess runAccess, JdbcTemplate jdbc) {
        this.runAccess = runAccess;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ContentRef requireReadable(UUID runId, int inquiryVersion, UUID attachmentId) {
        var access = runAccess.active(runId, inquiryVersion);
        List<ReferencedMessage> matches = new ArrayList<>();
        for (InputMessage message : access.input().messages()) {
            if (message.imageAttachments() == null) continue;
            for (ImageRef image : message.imageAttachments()) {
                if (attachmentId.equals(image.attachmentId())) matches.add(new ReferencedMessage(message, image));
            }
        }
        if (matches.size() != 1 || matches.getFirst().message().sender() != SenderType.USER) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        ReferencedMessage source = matches.getFirst();
        String digest = source.image().digest();
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new BusinessException(ErrorCode.INVALID_STATE);
        return jdbc.query("""
                SELECT a.content_sha256, a.byte_size FROM image_attachment a
                JOIN message m ON m.id = a.message_id
                WHERE a.id = ? AND a.attachment_status = 'BOUND' AND a.owner_member_id = ?
                  AND m.id = ? AND m.inquiry_id = ? AND m.revision = ? AND m.sender_type = 'USER'
                  AND m.message_status = 'COMPLETED' AND m.deleted_at IS NULL AND a.content_sha256 = ?
                """, (rs, row) -> new ContentRef(rs.getString("content_sha256"), rs.getInt("byte_size")),
                attachmentId, access.inquiry().getRequester().getId(), source.message().id(),
                access.inquiry().getId(), source.message().revision(), digest)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    record ContentRef(String digest, int size) { }
    private record ReferencedMessage(InputMessage message, ImageRef image) { }
}
