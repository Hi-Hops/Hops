package com.hihops.hops.inquiry.attachment;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.hihops.hops.inquiry.attachment.S3AttachmentStore.VersionCursor;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 한 번에 첨부 하나의 S3 버전 목록 한 페이지를 정리한다. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
class ImageAttachmentSweepService {
    private static final Logger log = LoggerFactory.getLogger(ImageAttachmentSweepService.class);

    record Cursor(@Nullable UUID after, @Nullable UUID current, @Nullable VersionCursor versions) { }
    record Step(@Nullable Cursor next, boolean cycleFinished, boolean failed) { }

    private final JdbcTemplate jdbc;
    private final ImageAttachmentCleanupService cleanup;

    Step sweepNext(@Nullable Cursor cursor) {
        UUID after = cursor == null ? null : cursor.after();
        UUID id = cursor == null ? null : cursor.current();
        VersionCursor versions = cursor == null ? null : cursor.versions();
        if (id == null) {
            List<UUID> ids = after == null
                    ? jdbc.queryForList("SELECT id FROM image_attachment WHERE attachment_status='EXPIRED' ORDER BY id LIMIT 1", UUID.class)
                    : jdbc.queryForList("SELECT id FROM image_attachment WHERE attachment_status='EXPIRED' AND id > ? ORDER BY id LIMIT 1", UUID.class, after);
            if (ids.isEmpty()) return new Step(null, true, false);
            id = ids.getFirst();
        }
        try {
            var page = cleanup.deleteVersionPage(id, versions);
            if (page.next() != null) {
                if (page.next().equals(versions)) {
                    log.warn("첨부 정리 페이지가 진행되지 않아 다음 순회에서 재시도합니다");
                    return new Step(new Cursor(id, null, null), false, true);
                }
                return new Step(new Cursor(after, id, page.next()), false, false);
            }
            return new Step(new Cursor(id, null, null), false, false);
        } catch (S3Exception | SdkClientException e) {
            log.warn("첨부 객체 정리 실패: 다음 순회에서 재시도합니다");
            return new Step(new Cursor(id, null, null), false, true);
        }
    }
}
