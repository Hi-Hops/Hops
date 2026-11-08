package com.hihops.hops.agent.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.hihops.hops.agent.dto.AiRunRequest.ImageRef;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.attachment.AttachmentBindingService;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.SenderType;

/** 실행 입력의 사용자 메시지에 연결된 이미지 참조만 고정한다. 바이트는 조회하지 않는다. */
@Component
class ImageRunReferences {
    // 문의 단위 첨부 한도와 같다. 실행 입력에는 문의의 모든 메시지가 들어간다.
    private static final int MAX_IMAGES = AttachmentBindingService.MAX_IMAGES_PER_INQUIRY;
    private static final long MAX_TOTAL_BYTES = 40L * 1024 * 1024;
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean enabled;

    ImageRunReferences(NamedParameterJdbcTemplate jdbc,
            @Value("${hops.image-attachments.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    Map<UUID, List<ImageRef>> forMessages(UUID ownerId, List<Message> messages) {
        if (!enabled || messages.isEmpty()) return Map.of();
        Map<UUID, Message> allowed = messages.stream().collect(Collectors.toMap(Message::getId, message -> message));
        var rows = jdbc.query("""
                SELECT message_id, id, owner_member_id, content_sha256, byte_size FROM image_attachment
                WHERE attachment_status = 'BOUND' AND message_id IN (:messageIds)
                ORDER BY message_id, message_position
                """, Map.of("messageIds", allowed.keySet()), (rs, row) -> new Row(
                        rs.getObject("message_id", UUID.class), rs.getObject("id", UUID.class),
                        rs.getObject("owner_member_id", UUID.class), rs.getString("content_sha256"),
                        rs.getInt("byte_size")));
        if (rows.size() > MAX_IMAGES || rows.stream().mapToLong(Row::size).sum() > MAX_TOTAL_BYTES
                || new HashSet<>(rows.stream().map(Row::id).toList()).size() != rows.size()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "분석할 이미지가 허용 범위를 벗어났습니다.");
        }
        for (Row row : rows) {
            Message message = allowed.get(row.messageId());
            if (message == null || message.getSenderType() != SenderType.USER || !ownerId.equals(row.owner())
                    || row.size() <= 0 || row.size() > 8 * 1024 * 1024
                    || row.digest() == null || !row.digest().matches("[0-9a-f]{64}")) {
                throw new BusinessException(ErrorCode.INVALID_STATE, "분석할 이미지의 상태를 확인할 수 없습니다.");
            }
        }
        return rows.stream().collect(Collectors.groupingBy(Row::messageId,
                Collectors.mapping(row -> new ImageRef(row.id(), row.digest()), Collectors.toList())));
    }

    private record Row(UUID messageId, UUID id, UUID owner, String digest, int size) { }
}
