package com.hihops.hops.inquiry.attachment;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.idempotency.CommandResult;
import com.hihops.hops.global.idempotency.IdempotencyService;
import com.hihops.hops.global.idempotency.IdempotentRequest;
import com.hihops.hops.global.idempotency.OperationState;
import com.hihops.hops.global.idempotency.ResourceRef;
import com.hihops.hops.global.idempotency.ResourceType;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
class ImageAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(ImageAttachmentService.class);
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private final IdempotencyService idempotency;
    private final S3AttachmentStore store;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    ImageAttachmentService(IdempotencyService idempotency, S3AttachmentStore store, JdbcTemplate jdbc, Clock clock) {
        this.idempotency = idempotency;
        this.store = store;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    CommandResult upload(IdempotentRequest request, String mediaType, String encoded) {
        if (!"image/png".equals(mediaType) || encoded == null || encoded.length() > 6990508) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        byte[] input;
        byte[] clean;
        try {
            input = Base64.getDecoder().decode(encoded);
            if (input.length < PNG_SIGNATURE.length
                    || !Arrays.equals(PNG_SIGNATURE, Arrays.copyOf(input, PNG_SIGNATURE.length))) {
                throw new IllegalArgumentException("PNG 형식이 아닙니다");
            }
            clean = AttachmentImageSanitizer.sanitize(input);
        } catch (IllegalArgumentException | IOException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        String inputDigest = digest(input);
        String contentDigest = digest(clean);
        // 멱등 요청 본문과 저장 기록에는 이미지가 아니라 다이제스트만 남긴다.
        IdempotentRequest hashedRequest = new IdempotentRequest(request.actorId(), request.requestId(),
                request.httpMethod(), request.requestPath(), new UploadIdentity(mediaType, inputDigest));
        CommandResult reservation = idempotency.reserve(hashedRequest, operationId -> {
            UUID attachmentId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO image_attachment (id, owner_member_id, operation_id, input_sha256, content_sha256,
                                                  byte_size, attachment_status)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                    """, attachmentId, request.actorId(), operationId, inputDigest, contentDigest, clean.length);
            return new ResourceRef(ResourceType.IMAGE_ATTACHMENT, attachmentId);
        });
        if (reservation.receipt().state() != OperationState.PENDING) return reservation;
        ResourceRef resource = reservation.receipt().resource();
        if (resource == null || resource.type() != ResourceType.IMAGE_ATTACHMENT) {
            throw new IllegalStateException("첨부 접수 대상이 올바르지 않습니다");
        }
        S3AttachmentStore.SaveResult result;
        try {
            result = store.putOrVerify(resource.id(), clean);
        } catch (S3Exception e) {
            log.warn("첨부 저장 확인 실패 operationId={} status={}", reservation.receipt().operationId(), e.statusCode());
            return reservation;
        } catch (SdkClientException e) {
            log.warn("첨부 저장 연결 실패 operationId={}", reservation.receipt().operationId());
            return reservation;
        }
        if (result != S3AttachmentStore.SaveResult.STORED) {
            log.warn("첨부 저장 결과 미확정 operationId={} result={}", reservation.receipt().operationId(), result);
            return reservation;
        }
        return idempotency.completeReserved(hashedRequest, reservation.receipt().operationId(), () -> {
            int updated = jdbc.update("""
                    UPDATE image_attachment SET attachment_status = 'READY', expires_at = ?
                    WHERE id = ? AND owner_member_id = ? AND operation_id = ? AND attachment_status = 'PENDING'
                      AND input_sha256 = ? AND content_sha256 = ? AND byte_size = ?
                    """, java.sql.Timestamp.from(clock.instant().plus(Duration.ofHours(24))), resource.id(),
                    request.actorId(), reservation.receipt().operationId(), inputDigest, contentDigest, clean.length);
            if (updated != 1) throw new IllegalStateException("첨부 상태를 확정할 수 없습니다");
        });
    }

    AttachmentMetadata findOwned(UUID ownerId, UUID attachmentId) {
        return jdbc.query("""
                SELECT id, attachment_status, byte_size, created_at, expires_at, message_id, link_revision
                FROM image_attachment WHERE id = ? AND owner_member_id = ?
                """, (rs, row) -> {
                    String state = rs.getString("attachment_status");
                    var expires = rs.getTimestamp("expires_at");
                    if ("READY".equals(state) && expires != null && !clock.instant().isBefore(expires.toInstant())) {
                        state = "EXPIRED";
                        expires = null;
                    }
                    return new AttachmentMetadata(rs.getObject("id", UUID.class), state, "image/png",
                            rs.getInt("byte_size"), rs.getTimestamp("created_at").toInstant(),
                            expires == null ? null : expires.toInstant(), rs.getObject("message_id", UUID.class),
                            rs.getInt("link_revision"), List.of());
                }, attachmentId, ownerId).stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    private record UploadIdentity(String mediaType, String inputSha256) { }

    record AttachmentMetadata(UUID id, String state, String mediaType, int byteSize, java.time.Instant createdAt,
            java.time.Instant expiresAt, UUID messageId, int linkRevision, List<UUID> problemIds) { }
}
