package com.hihops.hops.inquiry.attachment;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.hihops.hops.inquiry.attachment.S3AttachmentStore.VersionCursor;

import lombok.RequiredArgsConstructor;

/** 정리 대상으로 확정된 첨부의 S3 객체 버전을 한 페이지씩 삭제한다. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
class ImageAttachmentCleanupService {
    private final JdbcTemplate jdbc;
    private final S3AttachmentStore store;

    S3AttachmentStore.VersionPage deleteVersionPage(UUID id, @Nullable VersionCursor cursor) {
        Boolean expired = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM image_attachment
                    WHERE id = ? AND attachment_status = 'EXPIRED' AND message_id IS NULL)
                """, Boolean.class, id);
        if (!Boolean.TRUE.equals(expired)) throw new IllegalStateException("첨부가 정리 대상이 아닙니다");
        return store.deleteVersionPage(id, cursor);
    }
}
