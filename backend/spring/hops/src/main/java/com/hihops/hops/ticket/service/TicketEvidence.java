package com.hihops.hops.ticket.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hihops.hops.inquiry.domain.MessageStatus;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.user.domain.Member;

import lombok.RequiredArgsConstructor;

/** 배정 이력과 정정 명령에 같은 문제별 근거 공개 정책을 적용한다. */
@Component
@RequiredArgsConstructor
public class TicketEvidence {
    private final MessageRepository messages;
    private final ItSystemRepository systems;
    private final RelatedMessageProjector projector;

    public List<UUID> visible(Ticket ticket, Member member, List<UUID> ids) {
        if (!systems.findActivePrimaryOwnerId(ticket.getResponsibleSystem().getId()).filter(member.getId()::equals).isPresent()) {
            return List.of();
        }
        var allowed = messages.findAllById(ids).stream()
                .filter(message -> !message.isDeleted() && message.getMessageStatus() == MessageStatus.COMPLETED
                        && message.getInquiry().getId().equals(ticket.getInquiry().getId()))
                .filter(message -> projector.project(message, ticket.getInquiryProblem().getId(), List.of()).isPresent())
                .map(message -> message.getId()).toList();
        return ids.stream().filter(allowed::contains).distinct().toList();
    }
}
