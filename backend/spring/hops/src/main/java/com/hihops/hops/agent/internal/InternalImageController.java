package com.hihops.hops.agent.internal;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.attachment.S3AttachmentStore;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 서비스 인증과 실행의 현재 권한·버전·고정 참조를 검증한 뒤 이미지 바이트를 전달한다. */
@RestController
@RequestMapping("/internal/v2/runs/{runId}/image-attachments")
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
class InternalImageController {
    private final InternalImageAccess access;
    private final S3AttachmentStore store;

    InternalImageController(InternalImageAccess access, S3AttachmentStore store) {
        this.access = access;
        this.store = store;
    }

    @GetMapping("/{attachmentId}")
    ResponseEntity<byte[]> content(@PathVariable UUID runId, @PathVariable UUID attachmentId,
            @RequestParam int inquiryVersion) {
        if (inquiryVersion < 1) throw new BusinessException(ErrorCode.INVALID_INPUT);
        var expected = access.requireReadable(runId, inquiryVersion, attachmentId);
        byte[] bytes;
        try {
            bytes = store.get(attachmentId);
        } catch (IOException | S3Exception | SdkClientException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (bytes.length != expected.size() || !digest(bytes).equals(expected.digest())) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (!expected.equals(access.requireReadable(runId, inquiryVersion, attachmentId))) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }
}
