package com.hihops.hops.inquiry.attachment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.agent.service.AgentDispatchProperties;

/** 메시지 저장과 같은 거래에서만 준비된 첨부를 연결한다. */
@Service
public class AttachmentBindingService {
    /** 분석 실행 하나가 받을 수 있는 이미지 수(ImageRunReferences, AI wire)와 같다. */
    public static final int MAX_IMAGES_PER_INQUIRY = 5;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final AgentDispatchProperties dispatchProperties;

    public AttachmentBindingService(JdbcTemplate jdbc, AgentDispatchProperties dispatchProperties,
            @Value("${hops.image-attachments.message-link-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.dispatchProperties = dispatchProperties;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(UUID ownerId, UUID messageId, @Nullable List<UUID> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return;
        if (attachmentIds.size() > MAX_IMAGES_PER_INQUIRY || attachmentIds.stream().anyMatch(id -> id == null)
                || attachmentIds.stream().distinct().count() != attachmentIds.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "첨부 이미지 목록이 올바르지 않습니다.");
        }
        // 이미지가 AI 입력에 아직 포함되지 않으므로 검증용 설정 없이 접수하지 않는다.
        if (!enabled || dispatchProperties.dispatchEnabled()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);

        // 실행 입력은 문의의 모든 메시지를 담으므로 한도도 문의 단위다. 후속 메시지는 문의 행을 잠근 뒤 호출된다.
        Integer bound = jdbc.queryForObject("""
                SELECT count(*) FROM image_attachment a JOIN message m ON m.id = a.message_id
                WHERE a.attachment_status = 'BOUND' AND m.deleted_at IS NULL
                  AND m.inquiry_id = (SELECT inquiry_id FROM message WHERE id = ?)
                """, Integer.class, messageId);
        if (bound == null || bound + attachmentIds.size() > MAX_IMAGES_PER_INQUIRY) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "한 문의에는 이미지를 최대 5장까지 첨부할 수 있습니다.");
        }

        Map<UUID, Integer> positions = new HashMap<>();
        for (int i = 0; i < attachmentIds.size(); i++) positions.put(attachmentIds.get(i), i);
        List<UUID> orderedIds = new ArrayList<>(attachmentIds);
        orderedIds.sort(UUID::compareTo);
        for (UUID id : orderedIds) {
            int updated = jdbc.update("""
                    UPDATE image_attachment SET attachment_status = 'BOUND', message_id = ?, message_position = ?,
                                                expires_at = NULL
                    WHERE id = ? AND owner_member_id = ? AND attachment_status = 'READY' AND expires_at > clock_timestamp()
                      AND EXISTS (SELECT 1 FROM message m JOIN inquiry i ON i.id = m.inquiry_id
                                  WHERE m.id = ? AND m.sender_type = 'USER' AND m.deleted_at IS NULL
                                    AND i.requester_member_id = ? AND i.deleted_at IS NULL)
                    """, messageId, positions.get(id), id, ownerId, messageId, ownerId);
            if (updated == 0) {
                Boolean owned = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM image_attachment "
                        + "WHERE id = ? AND owner_member_id = ?)", Boolean.class, id, ownerId);
                throw new BusinessException(Boolean.TRUE.equals(owned) ? ErrorCode.INVALID_STATE : ErrorCode.NOT_FOUND);
            }
        }
    }
}
