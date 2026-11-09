package com.hihops.hops.inquiry.attachment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** 만료된 미결합 첨부를 정리 대상으로 확정한다. 객체 삭제는 수행하지 않는다. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
public class ImageAttachmentExpiryService {
    static final int BATCH_SIZE = 1000;

    private final JdbcTemplate jdbc;

    @Transactional
    public int expireBatch() {
        return jdbc.update("""
                WITH candidates AS (
                    SELECT id FROM image_attachment
                    WHERE attachment_status = 'READY' AND expires_at <= clock_timestamp()
                    ORDER BY expires_at, id
                    LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE image_attachment a SET attachment_status = 'EXPIRED', expires_at = NULL
                FROM candidates c
                WHERE a.id = c.id AND a.attachment_status = 'READY'
                  AND a.expires_at <= clock_timestamp()
                """, BATCH_SIZE);
    }
}
