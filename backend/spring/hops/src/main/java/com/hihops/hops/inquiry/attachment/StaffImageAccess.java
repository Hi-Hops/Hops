package com.hihops.hops.inquiry.attachment;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.ticket.service.TicketService;
import com.hihops.hops.ticket.service.TicketService.ImageAccessScope;

import lombok.RequiredArgsConstructor;

/** 현재 티켓 담당 권한과 문의자가 확인한 이미지–문제 관계를 함께 확인한다. */
@Service
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
class StaffImageAccess {
    private final TicketService tickets;
    private final JdbcTemplate jdbc;
    private static final String LINKED = """
            FROM image_attachment a
            JOIN message m ON m.id = a.message_id JOIN inquiry i ON i.id = m.inquiry_id
            JOIN member requester ON requester.id = i.requester_member_id
            JOIN department d ON d.id = requester.department_id
            JOIN image_attachment_problem_link l ON l.attachment_id = a.id
            JOIN image_attachment_problem_link_history h ON h.attachment_id = l.attachment_id AND h.link_revision = l.link_revision
            JOIN idempotency_key op ON op.operation_id = h.operation_id
            JOIN inquiry_problem p ON p.id = l.problem_id
            WHERE i.id = ? AND p.id = ? AND p.inquiry_id = i.id AND p.deleted_at IS NULL
              AND i.deleted_at IS NULL AND requester.deleted_at IS NULL AND d.deleted_at IS NULL
              AND a.owner_member_id = i.requester_member_id AND a.attachment_status = 'BOUND'
              AND m.sender_type = 'USER' AND m.message_status = 'COMPLETED' AND m.deleted_at IS NULL
              AND m.revision = l.source_message_revision AND a.link_revision = l.link_revision
              AND h.source_message_revision = l.source_message_revision AND h.reason = 'USER_CONFIRMED'
              AND h.actor_member_id = i.requester_member_id AND op.actor_member_id = i.requester_member_id
              AND op.operation_state = 'SUCCEEDED' AND h.problem_ids @> jsonb_build_array(l.problem_id::text)
            """;

    @Transactional(readOnly = true)
    public List<LinkedRef> list(Jwt jwt, UUID ticketId) {
        ImageAccessScope scope = scope(jwt, ticketId);
        return jdbc.query("SELECT a.id, a.byte_size, l.link_revision, m.revision " + LINKED
                + " ORDER BY m.sequence_no, a.message_position",
                (rs, row) -> new LinkedRef(rs.getObject("id", UUID.class), rs.getInt("byte_size"),
                        rs.getInt("link_revision"), rs.getInt("revision")),
                scope.inquiryId(), scope.problemId());
    }

    @Transactional(readOnly = true)
    public ContentRef readable(Jwt jwt, UUID ticketId, UUID attachmentId) {
        ImageAccessScope scope = scope(jwt, ticketId);
        return jdbc.query("SELECT a.content_sha256, a.byte_size, l.link_revision, m.revision " + LINKED + " AND a.id = ?",
                (rs, row) -> new ContentRef(rs.getString("content_sha256"), rs.getInt("byte_size"),
                        rs.getInt("link_revision"), rs.getInt("revision")),
                scope.inquiryId(), scope.problemId(), attachmentId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private ImageAccessScope scope(Jwt jwt, UUID ticketId) {
        try {
            return tickets.requireStaffImageScope(jwt, ticketId);
        } catch (BusinessException exception) {
            if (exception.errorCode() == ErrorCode.ACCESS_DENIED) throw new BusinessException(ErrorCode.NOT_FOUND);
            throw exception;
        }
    }

    record AttachmentRef(UUID id, String mediaType, int byteSize) { }
    record LinkedRef(UUID id, int byteSize, int linkRevision, int messageRevision) { }
    record ContentRef(String digest, int size, int linkRevision, int messageRevision) { }
}
