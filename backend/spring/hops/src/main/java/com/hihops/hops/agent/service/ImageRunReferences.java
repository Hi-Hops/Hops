package com.hihops.hops.agent.service;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.hihops.hops.agent.dto.AiRunRequest.ImageRef;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.attachment.AttachmentBindingService;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.MessagePayload;
import com.hihops.hops.inquiry.domain.SenderType;

import tools.jackson.databind.json.JsonMapper;

/** 실행 입력의 사용자 메시지에 연결된 이미지 참조만 고정한다. 바이트는 조회하지 않는다. */
@Component
class ImageRunReferences {
    // 문의 단위 첨부 한도와 같다. 실행 입력에는 문의의 모든 메시지가 들어간다.
    private static final int MAX_IMAGES = AttachmentBindingService.MAX_IMAGES_PER_INQUIRY;
    private static final long MAX_TOTAL_BYTES = 40L * 1024 * 1024;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final boolean enabled;

    ImageRunReferences(NamedParameterJdbcTemplate jdbc, JsonMapper jsonMapper,
            @Value("${hops.image-attachments.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.enabled = enabled;
    }

    Map<UUID, List<ImageRef>> forMessages(UUID ownerId, List<Message> messages,
            List<UUID> availableProblems, List<UUID> targetProblems, @Nullable Set<UUID> onlyImages) {
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
        if (onlyImages != null) {
            rows = rows.stream().filter(row -> onlyImages.contains(row.id())).toList();
            if (!new HashSet<>(rows.stream().map(Row::id).toList()).equals(onlyImages)) {
                throw new BusinessException(ErrorCode.INVALID_STATE, "분석할 이미지를 확인할 수 없습니다.");
            }
        }
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
        if (rows.isEmpty()) return Map.of();
        var selectionRows = jdbc.query("""
                SELECT t.attachment_id, t.problem_id,
                  COALESCE(a.owner_member_id = :owner AND i.requester_member_id = :owner AND i.deleted_at IS NULL
                    AND m.sender_type = 'USER' AND m.message_status = 'COMPLETED' AND m.deleted_at IS NULL
                    AND t.source_message_revision = m.revision AND t.analysis_revision = a.analysis_revision
                    AND h.source_message_revision = m.revision AND h.actor_member_id = :owner
                    AND h.reason = 'USER_SELECTED' AND h.problem_ids @> jsonb_build_array(t.problem_id::text)
                    AND jsonb_array_length(h.problem_ids) = (SELECT COUNT(*) FROM image_attachment_analysis_target x
                      WHERE x.attachment_id = t.attachment_id)
                    AND op.actor_member_id = :owner AND op.operation_state = 'SUCCEEDED'
                    AND p.inquiry_id = m.inquiry_id AND p.deleted_at IS NULL, false) AS valid
                FROM image_attachment_analysis_target t JOIN image_attachment a ON a.id = t.attachment_id
                JOIN message m ON m.id = a.message_id JOIN inquiry i ON i.id = m.inquiry_id
                LEFT JOIN image_attachment_analysis_history h ON h.attachment_id = t.attachment_id
                  AND h.analysis_revision = t.analysis_revision
                LEFT JOIN idempotency_key op ON op.operation_id = h.operation_id
                LEFT JOIN inquiry_problem p ON p.id = t.problem_id
                WHERE t.attachment_id IN (:ids) ORDER BY t.attachment_id, t.problem_id LIMIT 501
                """, Map.of("owner", ownerId, "ids", rows.stream().map(Row::id).toList()),
                (rs, row) -> new Selection(rs.getObject("attachment_id", UUID.class),
                        rs.getObject("problem_id", UUID.class), rs.getBoolean("valid")));
        if (selectionRows.size() > MAX_IMAGES * 100) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미지 분석 대상이 허용 범위를 벗어났습니다.");
        }
        Map<UUID, List<UUID>> selections = new HashMap<>();
        for (Selection selection : selectionRows) {
            if (!selection.valid()) {
                throw new BusinessException(ErrorCode.INVALID_STATE, "이미지 분석 대상을 확인할 수 없습니다.");
            }
            selections.computeIfAbsent(selection.attachmentId(), ignored -> new ArrayList<>()).add(selection.problemId());
        }
        if (selections.values().stream().anyMatch(ids -> ids.size() > 100)) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미지 분석 대상이 허용 범위를 벗어났습니다.");
        }
        Set<UUID> available = Set.copyOf(availableProblems);
        Set<UUID> targets = Set.copyOf(targetProblems);
        Map<UUID, List<ImageRef>> result = new HashMap<>();
        for (Row row : rows) {
            var selected = selections.getOrDefault(row.id(), List.of());
            List<UUID> scoped = selected.stream().filter(available::contains)
                    .filter(id -> targets.isEmpty() || targets.contains(id)).toList();
            // 명시적으로 다른 문제에 지정한 이미지를 이번 문제 분석에 섞지 않는다.
            if (!selected.isEmpty() && scoped.isEmpty()) continue;
            // 지정이 없으면 메시지의 문제를 따른다. 대상 밖 이미지를 넣으면 AI가 관찰을 연결하지 못해 실행 전체가 실패한다.
            if (selected.isEmpty() && !targets.isEmpty()
                    && problemsOf(allowed.get(row.messageId())).stream().noneMatch(targets::contains)) continue;
            result.computeIfAbsent(row.messageId(), ignored -> new ArrayList<>())
                    .add(new ImageRef(row.id(), row.digest(), scoped.isEmpty() ? null : scoped));
        }
        return result;
    }

    // 실행 입력의 InputMessage.problemIds와 같은 값이다 (AnalysisInputFactory.input).
    private List<UUID> problemsOf(Message message) {
        return jsonMapper.readValue(message.getPayload(), MessagePayload.class).problemIds(message.getRevision());
    }

    private record Row(UUID messageId, UUID id, UUID owner, String digest, int size) { }
    private record Selection(UUID attachmentId, UUID problemId, boolean valid) { }
}
