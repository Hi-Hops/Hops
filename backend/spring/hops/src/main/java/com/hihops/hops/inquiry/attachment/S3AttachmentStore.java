package com.hihops.hops.inquiry.attachment;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 비공개 버킷의 첨부 바이트 저장. 호출자가 현재 권한과 이미지 처리를 먼저 확인한다. */
public final class S3AttachmentStore {
    private final S3Client client;
    private final String bucket;

    public S3AttachmentStore(S3Client client, String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("첨부 저장 버킷이 필요합니다");
        }
        this.client = Objects.requireNonNull(client);
        this.bucket = bucket;
    }

    /** 같은 ID의 기존 객체는 덮어쓰지 않는다. 저장 결과가 미확인이면 호출자가 재조정해야 한다. */
    public void put(UUID id, byte[] png) {
        if (png.length == 0 || png.length > AttachmentImageSanitizer.MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("이미지 파일 크기가 허용 범위를 벗어났습니다");
        }
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key(id))
                .contentType("image/png").ifNoneMatch("*").build(), RequestBody.fromBytes(png));
    }

    /** 호출 전 문의·티켓 및 첨부 관계의 현재 권한을 검사해야 한다. */
    public byte[] get(UUID id) throws IOException {
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key(id)).build();
        try (ResponseInputStream<GetObjectResponse> response = client.getObject(request)) {
            Long length = response.response().contentLength();
            if (length == null || length <= 0 || length > AttachmentImageSanitizer.MAX_OUTPUT_BYTES) {
                response.abort();
                throw new IOException("저장된 이미지 크기가 허용 범위를 벗어났습니다");
            }
            byte[] bytes;
            try {
                bytes = response.readNBytes(AttachmentImageSanitizer.MAX_OUTPUT_BYTES + 1);
            } catch (IOException exception) {
                response.abort();
                throw exception;
            }
            if (bytes.length != length) {
                response.abort();
                throw new IOException("저장된 이미지 크기가 허용 범위를 벗어났습니다");
            }
            return bytes;
        }
    }

    private static String key(UUID id) {
        return "chat-attachments/" + Objects.requireNonNull(id);
    }
}
