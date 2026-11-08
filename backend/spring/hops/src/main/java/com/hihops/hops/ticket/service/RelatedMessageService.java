package com.hihops.hops.ticket.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.inquiry.repository.MessageRevisionRepository;
import com.hihops.hops.ticket.dto.RelatedMessagePageResponse;
import com.hihops.hops.ticket.dto.RelatedMessageRequest;
import com.hihops.hops.ticket.dto.RelatedMessageResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelatedMessageService {
    private final TicketService tickets;
    private final MessageRepository messages;
    private final MessageRevisionRepository revisions;
    private final RelatedMessageProjector projector;

    @Transactional(readOnly = true)
    public RelatedMessagePageResponse list(Jwt jwt, UUID ticketId, RelatedMessageRequest request) {
        var ticket = tickets.requireStaffTicket(jwt, ticketId);
        int afterSequence = 0;
        if (request.cursor() != null) {
            UUID messageId = cursorMessage(ticketId, request.cursor());
            Message previous = messages.findByIdAndInquiryIdAndDeletedAtIsNull(messageId, ticket.getInquiry().getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
            if (projector.project(previous, ticket.getInquiryProblem().getId(), List.of(),
                    projector.restricted(List.of(previous))).isEmpty()) invalid();
            afterSequence = previous.getSequenceNo();
        }
        List<RelatedMessageResponse> visible = new ArrayList<>();
        int size = request.pageSize();
        // 공개 가능한 항목을 size+1개 찾을 때까지 읽는다. 비공개 구간 때문에 생긴 빈 배치를 마지막 페이지로 오인하지 않는다.
        while (visible.size() <= size) {
            List<Message> batch = messages.findCompletedAfter(ticket.getInquiry().getId(), afterSequence, PageRequest.of(0, 100));
            if (batch.isEmpty()) break;
            var histories = revisions.findByMessageIds(batch.stream().map(Message::getId).toList()).stream()
                    .collect(Collectors.groupingBy(old -> old.getMessage().getId()));
            var restricted = projector.restricted(batch);
            for (Message message : batch) {
                projector.project(message, ticket.getInquiryProblem().getId(), histories.getOrDefault(message.getId(), List.of()), restricted)
                        .ifPresent(visible::add);
                if (visible.size() > size) break;
            }
            afterSequence = batch.getLast().getSequenceNo();
            if (batch.size() < 100) break;
        }
        boolean more = visible.size() > size;
        List<RelatedMessageResponse> items = List.copyOf(visible.subList(0, Math.min(size, visible.size())));
        String next = more ? encode(ticketId, items.getLast().id()) : null;
        return new RelatedMessagePageResponse(items, next);
    }

    private static UUID cursorMessage(UUID ticketId, String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            String[] parts = decoded.split(":", -1);
            if (parts.length != 3 || !"1".equals(parts[0]) || !ticketId.toString().equals(parts[1])) {
                throw new IllegalArgumentException();
            }
            UUID id = UUID.fromString(parts[2]);
            if (!encode(ticketId, id).equals(cursor)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private static String encode(UUID ticketId, UUID messageId) {
        // 원 문의 전체의 sequenceNo는 넣지 않는다. ID를 바꿔도 현재 권한·문제 링크를 반드시 재검사한다.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("1:" + ticketId + ":" + messageId).getBytes(StandardCharsets.US_ASCII));
    }

    private static void invalid() {
        throw new BusinessException(ErrorCode.INVALID_INPUT);
    }
}
