package com.hihops.hops.inquiry.attachment;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 저장소 호출은 권한 확인 거래 밖에서 수행하고 반환 전에 현재 권한을 다시 검사한다. */
@Service
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
class StaffImageContentService {
    private final StaffImageAccess access;
    private final S3AttachmentStore store;

    public List<StaffImageAccess.AttachmentRef> list(Jwt jwt, UUID ticketId) {
        var before = access.list(jwt, ticketId);
        if (!before.equals(access.list(jwt, ticketId))) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        return before.stream().map(item -> new StaffImageAccess.AttachmentRef(item.id(), "image/png", item.byteSize())).toList();
    }

    public byte[] content(Jwt jwt, UUID ticketId, UUID attachmentId) {
        var before = access.readable(jwt, ticketId, attachmentId);
        byte[] bytes;
        try {
            bytes = store.get(attachmentId);
        } catch (IOException | S3Exception | SdkClientException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (bytes.length != before.size() || !digest(bytes).equals(before.digest())) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (!before.equals(access.readable(jwt, ticketId, attachmentId))) throw new BusinessException(ErrorCode.NOT_FOUND);
        return bytes;
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }
}
