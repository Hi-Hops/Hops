package com.hihops.hops.inquiry.attachment;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 이미지의 분석 대상만 관리하며 담당자 공개 관계는 변경하지 않는다. */
@Component
@RequiredArgsConstructor
public class ImageAnalysisTargets {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final JsonMapper mapper;

    /** 호출자는 문의 행을 잠그고 요청자의 멱등 명령을 접수한 뒤 같은 거래에서 호출한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(UUID actorId, UUID inquiryId, UUID attachmentId, UUID operationId,
            int expectedRevision, List<UUID> problemIds) {
        if (problemIds == null || problemIds.size() > 100 || problemIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(problemIds).size() != problemIds.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        var rows = jdbc.query("""
                SELECT a.analysis_revision, m.revision FROM image_attachment a
                JOIN message m ON m.id = a.message_id JOIN inquiry i ON i.id = m.inquiry_id
                WHERE a.id = ? AND a.owner_member_id = ? AND a.attachment_status = 'BOUND'
                  AND i.id = ? AND i.requester_member_id = ? AND i.deleted_at IS NULL
                  AND m.sender_type = 'USER' AND m.message_status = 'COMPLETED' AND m.deleted_at IS NULL
                FOR UPDATE OF a
                """, (rs, row) -> new Version(rs.getInt("analysis_revision"), rs.getInt("revision")),
                attachmentId, actorId, inquiryId, actorId);
        Version version = rows.stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (version.analysis() != expectedRevision) throw new BusinessException(ErrorCode.ATTACHMENT_ANALYSIS_VERSION_CONFLICT);
        if (!problemIds.isEmpty()) {
            var known = namedJdbc.queryForList("SELECT id FROM inquiry_problem WHERE inquiry_id = :inquiry "
                    + "AND deleted_at IS NULL AND id IN (:ids)", Map.of("inquiry", inquiryId, "ids", problemIds), UUID.class);
            if (!new HashSet<>(known).equals(new HashSet<>(problemIds))) throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        int next = Math.incrementExact(version.analysis());
        history(attachmentId, next, actorId, operationId, version.message(), "USER_SELECTED", problemIds);
        jdbc.update("DELETE FROM image_attachment_analysis_target WHERE attachment_id = ?", attachmentId);
        if (!problemIds.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO image_attachment_analysis_target (attachment_id, problem_id, analysis_revision, source_message_revision) "
                    + "VALUES (?, ?, ?, ?)", problemIds.stream().map(problem -> new Object[] {
                            attachmentId, problem, next, version.message() }).toList());
        }
        int changed = jdbc.update("UPDATE image_attachment SET analysis_revision = ? WHERE id = ? AND analysis_revision = ?",
                next, attachmentId, version.analysis());
        if (changed != 1) throw new BusinessException(ErrorCode.ATTACHMENT_ANALYSIS_VERSION_CONFLICT);
    }

    /** 메시지가 바뀌면 이전 분석 대상은 다시 선택할 때까지 사용하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateForRevision(UUID actorId, UUID messageId, int messageRevision, UUID operationId) {
        var rows = jdbc.query("""
                SELECT a.id, a.analysis_revision FROM image_attachment a
                WHERE a.message_id = ? AND a.owner_member_id = ? AND a.attachment_status = 'BOUND'
                  AND EXISTS (SELECT 1 FROM image_attachment_analysis_target t WHERE t.attachment_id = a.id)
                ORDER BY a.id FOR UPDATE OF a
                """, (rs, row) -> new AttachmentVersion(rs.getObject("id", UUID.class), rs.getInt("analysis_revision")),
                messageId, actorId);
        if (rows.isEmpty()) return;
        var ids = rows.stream().map(AttachmentVersion::id).toList();
        for (var row : rows) {
            history(row.id(), Math.incrementExact(row.revision()), actorId, operationId,
                    messageRevision, "MESSAGE_REVISED", List.of());
        }
        namedJdbc.update("DELETE FROM image_attachment_analysis_target WHERE attachment_id IN (:ids)", Map.of("ids", ids));
        namedJdbc.update("UPDATE image_attachment SET analysis_revision = analysis_revision + 1 WHERE id IN (:ids)", Map.of("ids", ids));
    }

    private void history(UUID attachmentId, int revision, UUID actorId, UUID operationId, int messageRevision,
            String reason, List<UUID> problemIds) {
        jdbc.update("INSERT INTO image_attachment_analysis_history (id, attachment_id, analysis_revision, actor_member_id, "
                + "operation_id, source_message_revision, reason, problem_ids) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), attachmentId, revision, actorId, operationId, messageRevision, reason,
                mapper.writeValueAsString(problemIds));
    }

    private record Version(int analysis, int message) { }
    private record AttachmentVersion(UUID id, int revision) { }
}
