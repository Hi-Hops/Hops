package com.hihops.hops.ticket.service;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.domain.WorkLogType;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.repository.TicketEventRepository;
import com.hihops.hops.ticket.repository.TicketWorkLogRepository;
import lombok.RequiredArgsConstructor;

/** 저장된 확인 요청의 원 기록과 현재 조회/응답 권한을 확인한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResolutionRequestReader {
    private final AuthService auth;
    private final TicketAuthorization authorization;
    private final TicketEventRepository events;
    private final TicketWorkLogRepository records;
    private final MessageRepository messages;
    private final InquiryRepository inquiries;
    private final InquiryProblemRepository problems;
    private final TicketStoredData data;
    public record Stored(ResolutionRequestResponse request, Ticket ticket, Message guide) { }

    public Stored stored(UUID id) {
        var event = events.findResolution(id);
        if (event.isPresent()) {
            var ticket = event.get().getTicket();
            if (ticket.isDeleted() || ticket.getInquiry().isDeleted() || ticket.getInquiryProblem().isDeleted()) notFound();
            var request = data.value(data.parse(event.get().getAfterData()).path("resolutionRequest"), ResolutionRequestResponse.class);
            var record = records.findById(request.sourceId()).orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
            if (!"WORK_RECORD".equals(request.source()) || request.sourceVersion() != 1
                    || !ticket.getId().equals(request.ticketId()) || !record.getTicket().getId().equals(ticket.getId())
                    || !ticket.getInquiry().getId().equals(request.inquiryId())
                    || !ticket.getInquiryProblem().getId().equals(request.problemId())
                    || (record.getLogType() != WorkLogType.RESOLVED
                        && record.getLogType() != WorkLogType.IMPLEMENTED)) unavailable();
            return new Stored(request, ticket, null);
        }
        var guide = messages.findResolution(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var request = data.value(data.parse(guide.getPayload()).path("resolutionRequest"), ResolutionRequestResponse.class);
        var problem = problems.findById(request.problemId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (guide.getInquiry().isDeleted() || problem.isDeleted()) notFound();
        if (!"GUIDE".equals(request.source()) || request.ticketId() != null || !request.sourceId().equals(guide.getId())
                || request.sourceVersion() != guide.getRevision() || guide.getSenderType() != SenderType.AI
                || !guide.getInquiry().getId().equals(request.inquiryId())
                || !problem.getInquiry().getId().equals(request.inquiryId())) unavailable();
        return new Stored(request, null, guide);
    }

    @Transactional(readOnly = true)
    public ResolutionRequestResponse get(Jwt jwt, UUID id) {
        var actor = auth.currentMember(jwt);
        var stored = stored(id);
        var inquiry = inquiries.findById(stored.request.inquiryId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!inquiry.getRequester().getId().equals(actor.getId())) {
            if (stored.ticket == null) throw new BusinessException(ErrorCode.ACCESS_DENIED);
            authorization.requireCurrentPrimary(actor, stored.ticket);
        }
        return stored.request;
    }

    @Transactional(readOnly = true)
    public void checkResponse(Jwt jwt, UUID id) {
        var actor = auth.currentMember(jwt);
        var request = stored(id).request;
        var inquiry = inquiries.findById(request.inquiryId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!inquiry.getRequester().getId().equals(actor.getId())) throw new BusinessException(ErrorCode.ACCESS_DENIED);
    }

    private static void unavailable() { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    private static void notFound() { throw new BusinessException(ErrorCode.NOT_FOUND); }
}