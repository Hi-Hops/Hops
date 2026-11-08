package com.hihops.hops.inquiry.attachment;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 문의자에게 현재 문의에 결합된 첨부만 투영한다. */
@Service
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
public class AttachmentHistoryReader {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final S3AttachmentStore store;

    public AttachmentHistoryReader(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, S3AttachmentStore store) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.store = store;
    }

    public Map<UUID, List<AttachmentRef>> forMessages(UUID actorId, UUID inquiryId, List<UUID> messageIds) {
        if (messageIds.isEmpty()) return Map.of();
        return namedJdbc.query("""
                SELECT a.message_id, a.id, a.byte_size FROM image_attachment a
                JOIN message m ON m.id = a.message_id
                JOIN inquiry i ON i.id = m.inquiry_id
                WHERE a.attachment_status = 'BOUND' AND a.owner_member_id = :actorId
                  AND i.id = :inquiryId AND i.requester_member_id = :actorId AND i.deleted_at IS NULL
                  AND m.deleted_at IS NULL AND m.sender_type = 'USER' AND m.message_status = 'COMPLETED'
                  AND m.id IN (:messageIds)
                ORDER BY a.message_id, a.message_position
                """, Map.of("actorId", actorId, "inquiryId", inquiryId, "messageIds", messageIds),
                (rs, row) -> new MessageAttachment(rs.getObject("message_id", UUID.class),
                        new AttachmentRef(rs.getObject("id", UUID.class), "image/png", rs.getInt("byte_size"))))
                .stream().collect(Collectors.groupingBy(MessageAttachment::messageId,
                        Collectors.mapping(MessageAttachment::attachment, Collectors.toList())));
    }

    public byte[] content(UUID actorId, UUID inquiryId, UUID attachmentId) {
        StoredContent expected = readable(actorId, inquiryId, attachmentId);
        byte[] bytes;
        try {
            bytes = store.get(attachmentId);
        } catch (IOException | S3Exception | SdkClientException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (bytes.length != expected.size() || !digest(bytes).equals(expected.digest())) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (!expected.equals(readable(actorId, inquiryId, attachmentId))) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return bytes;
    }

    private StoredContent readable(UUID actorId, UUID inquiryId, UUID attachmentId) {
        return jdbc.query("""
                SELECT a.content_sha256, a.byte_size FROM image_attachment a
                JOIN message m ON m.id = a.message_id JOIN inquiry i ON i.id = m.inquiry_id
                WHERE a.id = ? AND a.attachment_status = 'BOUND' AND a.owner_member_id = ?
                  AND i.id = ? AND i.requester_member_id = ? AND i.deleted_at IS NULL
                  AND m.deleted_at IS NULL AND m.sender_type = 'USER' AND m.message_status = 'COMPLETED'
                """, (rs, row) -> new StoredContent(rs.getString("content_sha256"), rs.getInt("byte_size")),
                attachmentId, actorId, inquiryId, actorId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }

    public record AttachmentRef(UUID id, String mediaType, int byteSize) { }
    private record MessageAttachment(UUID messageId, AttachmentRef attachment) { }
    private record StoredContent(String digest, int size) { }
}
