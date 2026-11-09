package com.hihops.hops.inquiry.attachment;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 비공개 버킷의 첨부 바이트 저장. 호출자가 현재 권한과 이미지 처리를 먼저 확인한다. */
public final class S3AttachmentStore {
    private static final int VERSION_PAGE_SIZE = 100;

    record VersionCursor(String keyMarker, String versionIdMarker) { }

    record VersionPage(int deletedVersions, VersionCursor next) { }
    public enum SaveResult {
        STORED,
        CONFLICT,
        UNKNOWN
    }

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
        validateSize(png);
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key(id))
                .contentType("image/png").ifNoneMatch("*").build(), RequestBody.fromBytes(png));
    }

    /**
     * 조건부 저장 뒤 실제 객체를 확인한다. 성공 응답이 유실됐거나 같은 객체가 먼저 저장된 경우에도
     * 같은 바이트가 읽혀야 STORED다. 조회가 안 되면 미저장으로 추정하지 않고 UNKNOWN을 반환한다.
     * DB의 접수·결합 상태와 재시도 허용 여부는 호출자가 별도로 관리해야 한다.
     */
    public SaveResult putOrVerify(UUID id, byte[] png) {
        validateSize(png);
        byte[] expected = png.clone();
        try {
            put(id, expected);
        } catch (S3Exception exception) {
            int status = exception.statusCode();
            if (status != 404 && status != 409 && status != 412 && status != 429 && status < 500) {
                throw exception;
            }
            return verifyExisting(id, expected);
        } catch (SdkClientException exception) {
            // 연결 실패나 응답 유실은 S3에 저장됐는지 알 수 없어 같은 ID의 객체를 확인한다.
            return verifyExisting(id, expected);
        }
        return verifyExisting(id, expected);
    }

    private static void validateSize(byte[] png) {
        Objects.requireNonNull(png);
        if (png.length == 0 || png.length > AttachmentImageSanitizer.MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("이미지 파일 크기가 허용 범위를 벗어났습니다");
        }
    }

    private SaveResult verifyExisting(UUID id, byte[] png) {
        try {
            return Arrays.equals(png, get(id)) ? SaveResult.STORED : SaveResult.CONFLICT;
        } catch (S3Exception | SdkClientException | IOException exception) {
            // 읽기 실패·객체 없음은 선행 쓰기가 나중에 확정될 가능성까지 배제하지 못한다.
            return SaveResult.UNKNOWN;
        }
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

    /** 한 페이지의 정확히 일치하는 객체 버전만 삭제한다. 호출자는 DB 정리 대상 여부를 확인해야 한다. */
    VersionPage deleteVersionPage(UUID id, VersionCursor cursor) {
        String objectKey = key(id);
        var request = ListObjectVersionsRequest.builder().bucket(bucket).prefix(objectKey).maxKeys(VERSION_PAGE_SIZE);
        if (cursor != null) request.keyMarker(cursor.keyMarker()).versionIdMarker(cursor.versionIdMarker());
        ListObjectVersionsResponse page = client.listObjectVersions(request.build());
        VersionCursor next = null;
        if (Boolean.TRUE.equals(page.isTruncated())) {
            if (page.nextKeyMarker() == null || page.nextVersionIdMarker() == null) {
                throw new IllegalStateException("첨부 버전 목록의 다음 페이지를 확인할 수 없습니다");
            }
            next = new VersionCursor(page.nextKeyMarker(), page.nextVersionIdMarker());
        }
        var versions = page.versions().stream().filter(version -> objectKey.equals(version.key())).toList();
        if (versions.stream().anyMatch(version -> version.versionId() == null || version.versionId().isBlank())) {
            throw new IllegalStateException("첨부 버전 식별자를 확인할 수 없습니다");
        }
        int deleted = 0;
        for (var version : versions) {
            // 다음 페이지 기준 버전은 유지한다. 이후 처음부터 다시 순회할 때 삭제한다.
            if (next != null && objectKey.equals(next.keyMarker()) && version.versionId().equals(next.versionIdMarker())) continue;
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey)
                    .versionId(version.versionId()).build());
            deleted++;
        }
        // 삭제 마커는 남긴다. 뒤 페이지의 이전 버전이 일시적으로 현재 객체가 되는 것을 막는다.
        return new VersionPage(deleted, next);
    }

    private static String key(UUID id) {
        return "chat-attachments/" + Objects.requireNonNull(id);
    }
}
