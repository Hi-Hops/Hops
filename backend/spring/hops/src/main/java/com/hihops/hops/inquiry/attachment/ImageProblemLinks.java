package com.hihops.hops.inquiry.attachment;

import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.realtime.dto.TicketChangedEvent;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 문의 행 잠금과 같은 거래 안에서 이미지 공개 관계와 이력을 변경한다. */
@Component
@RequiredArgsConstructor
public class ImageProblemLinks {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final JsonMapper mapper;
    private final ApplicationEventPublisher events;

    public Source requireOwned(UUID actorId, UUID inquiryId, UUID attachmentId, boolean lock) {
        var rows = jdbc.query("""
                SELECT a.message_id, a.link_revision, m.revision FROM image_attachment a
                JOIN message m ON m.id = a.message_id JOIN inquiry i ON i.id = m.inquiry_id
                WHERE a.id = ? AND a.owner_member_id = ? AND a.attachment_status = 'BOUND'
                  AND i.id = ? AND i.requester_member_id = ? AND i.deleted_at IS NULL
                  AND m.sender_type = 'USER' AND m.message_status = 'COMPLETED' AND m.deleted_at IS NULL
                """ + (lock ? " FOR UPDATE OF a" : ""), (rs, row) -> new Source(
                        rs.getObject("message_id", UUID.class), rs.getInt("revision"), rs.getInt("link_revision")),
                attachmentId, actorId, inquiryId, actorId);
        return rows.stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(UUID actorId, UUID inquiryId, UUID attachmentId, UUID operationId, Source source,
            int expectedRevision, List<UUID> problemIds) {
        if (problemIds.size() > 100 || problemIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(problemIds).size() != problemIds.size()) throw new BusinessException(ErrorCode.INVALID_INPUT);
        if (source.linkRevision() != expectedRevision) throw new BusinessException(ErrorCode.ATTACHMENT_LINK_VERSION_CONFLICT);
        if (!problemIds.isEmpty()) {
            var known = namedJdbc.queryForList("SELECT id FROM inquiry_problem WHERE inquiry_id = :inquiry "
                    + "AND deleted_at IS NULL AND id IN (:ids)", Map.of("inquiry", inquiryId, "ids", problemIds), UUID.class);
            if (!new HashSet<>(known).equals(new HashSet<>(problemIds))) throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        Set<UUID> changedProblems = new HashSet<>(problemIds);
        changedProblems.addAll(jdbc.queryForList("SELECT problem_id FROM image_attachment_problem_link WHERE attachment_id = ?",
                UUID.class, attachmentId));
        int next = Math.incrementExact(source.linkRevision());
        history(attachmentId, next, actorId, operationId, source.messageRevision(), "USER_CONFIRMED", problemIds);
        jdbc.update("DELETE FROM image_attachment_problem_link WHERE attachment_id = ?", attachmentId);
        if (!problemIds.isEmpty()) {
            List<Object[]> rows = problemIds.stream().map(problem -> new Object[] {
                    attachmentId, problem, next, source.messageRevision() }).toList();
            jdbc.batchUpdate("INSERT INTO image_attachment_problem_link (attachment_id, problem_id, link_revision, source_message_revision) "
                    + "VALUES (?, ?, ?, ?)", rows);
        }
        int changed = jdbc.update("UPDATE image_attachment SET link_revision = ? WHERE id = ? AND link_revision = ?",
                next, attachmentId, source.linkRevision());
        if (changed != 1) throw new BusinessException(ErrorCode.ATTACHMENT_LINK_VERSION_CONFLICT);
        notifyTickets(inquiryId, changedProblems);
    }

    /** 텍스트 정정 시 기존 이미지 공개 범위는 자동 보류하고 원래 이미지는 유지한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateForRevision(UUID actorId, UUID inquiryId, UUID messageId, int newMessageRevision, UUID operationId) {
        var rows = jdbc.query("""
                SELECT a.id, a.link_revision FROM image_attachment a
                WHERE a.message_id = ? AND a.owner_member_id = ? AND a.attachment_status = 'BOUND'
                  AND EXISTS (SELECT 1 FROM image_attachment_problem_link l WHERE l.attachment_id = a.id)
                ORDER BY a.id FOR UPDATE OF a
                """, (rs, row) -> new LinkVersion(rs.getObject("id", UUID.class), rs.getInt("link_revision")),
                messageId, actorId);
        if (rows.isEmpty()) return;
        var ids = rows.stream().map(LinkVersion::attachmentId).toList();
        var changedProblems = namedJdbc.queryForList("SELECT DISTINCT problem_id FROM image_attachment_problem_link WHERE attachment_id IN (:ids)",
                Map.of("ids", ids), UUID.class);
        List<Object[]> historyRows = rows.stream().map(row -> new Object[] { UUID.randomUUID(), row.attachmentId(),
                Math.incrementExact(row.revision()), actorId, operationId, newMessageRevision }).toList();
        jdbc.batchUpdate("INSERT INTO image_attachment_problem_link_history (id, attachment_id, link_revision, actor_member_id, "
                + "operation_id, source_message_revision, reason, problem_ids) VALUES (?, ?, ?, ?, ?, ?, 'MESSAGE_REVISED', '[]'::jsonb)",
                historyRows);
        namedJdbc.update("DELETE FROM image_attachment_problem_link WHERE attachment_id IN (:ids)", Map.of("ids", ids));
        namedJdbc.update("UPDATE image_attachment SET link_revision = link_revision + 1 WHERE id IN (:ids)", Map.of("ids", ids));
        notifyTickets(inquiryId, changedProblems);
    }

    private void notifyTickets(UUID inquiryId, Collection<UUID> problemIds) {
        if (problemIds.isEmpty()) return;
        var notifications = namedJdbc.query("SELECT id, responsible_system_id FROM ticket WHERE inquiry_id = :inquiry "
                + "AND inquiry_problem_id IN (:ids) AND deleted_at IS NULL",
                Map.of("inquiry", inquiryId, "ids", problemIds), (rs, row) -> new TicketChangedEvent(
                        rs.getObject("id", UUID.class), List.of(rs.getObject("responsible_system_id", UUID.class))));
        notifications.forEach(events::publishEvent);
    }

    private void history(UUID attachmentId, int revision, UUID actorId, UUID operationId, int messageRevision,
            String reason, List<UUID> problemIds) {
        jdbc.update("INSERT INTO image_attachment_problem_link_history (id, attachment_id, link_revision, actor_member_id, "
                + "operation_id, source_message_revision, reason, problem_ids) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), attachmentId, revision, actorId, operationId, messageRevision, reason,
                mapper.writeValueAsString(problemIds));
    }

    public record Source(UUID messageId, int messageRevision, int linkRevision) { }
    private record LinkVersion(UUID attachmentId, int revision) { }
}
