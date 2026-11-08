package com.hihops.hops.ticket.service;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.agent.service.ImageRunSafety;
import com.hihops.hops.inquiry.domain.MessageStatus;
import com.hihops.hops.inquiry.domain.StandardRequest;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.ticket.dto.StandardRequestResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.EvidenceStatementResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.RequestDetailsResponse;
import com.hihops.hops.user.domain.Member;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TicketStandardRequestReader {
    private final TicketStoredData data;
    private final TicketKnowledgeReader knowledge;
    private final MessageRepository messages;
    private final RelatedMessageProjector projector;
    private final ImageRunSafety imageSafety;

    public StandardRequestResponse read(StandardRequest value, Member member) {
        imageSafety.requireTextOnly(value.getAgentRun());
        var visibleKnowledge = knowledge.visible(value.getRelatedKnowledge(), member);
        var allowedIds = visibleKnowledge.stream().map(item -> item.id()).toList();
        var storedFacts = data.list(value.getConfirmedFacts(), EvidenceStatementResponse.class);
        var storedAssumptions = data.list(value.getAssumptions(), EvidenceStatementResponse.class);
        UUID candidate = value.getResponsibleSystem() == null ? null : value.getResponsibleSystem().getId();
        var candidates = data.parse(value.getSystemCandidates());
        List<UUID> evidence = List.of();
        if (!candidates.isArray()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        for (var item : candidates) {
            if (candidate != null && candidate.toString().equals(item.path("systemId").asString(null))) {
                evidence = data.list(item.path("evidenceIds"), UUID.class);
            }
        }
        var sourceIds = Stream.concat(storedFacts.stream(), storedAssumptions.stream())
                .flatMap(item -> item.sourceMessageIds().stream()).collect(Collectors.toCollection(HashSet::new));
        sourceIds.addAll(evidence);
        var candidatesByMessage = messages.findAllById(sourceIds);
        var restricted = projector.restricted(candidatesByMessage);
        var allowedMessages = candidatesByMessage.stream()
                .filter(message -> !message.isDeleted() && message.getMessageStatus() == MessageStatus.COMPLETED
                        && message.getInquiry().getId().equals(value.getInquiryProblem().getInquiry().getId()))
                .filter(message -> projector.project(message, value.getInquiryProblem().getId(), List.of(), restricted).isPresent())
                .map(message -> message.getId()).collect(Collectors.toSet());
        var facts = storedFacts.stream().filter(item -> allowedMessages.containsAll(item.sourceMessageIds())
                && allowedIds.containsAll(item.knowledgeEvidenceIds())
                && (!item.sourceMessageIds().isEmpty() || !item.knowledgeEvidenceIds().isEmpty())).toList();
        var assumptions = storedAssumptions.stream().filter(item -> allowedMessages.containsAll(item.sourceMessageIds())
                && allowedIds.containsAll(item.knowledgeEvidenceIds())).toList();
        evidence = evidence.stream().filter(id -> allowedMessages.contains(id) || allowedIds.contains(id)).toList();
        return new StandardRequestResponse(value.getId(), value.getInquiryProblem().getId(), value.getInquiryVersion(),
                value.getSummary(), facts, assumptions, data.list(value.getMissingItems(), String.class),
                candidate, evidence, data.value(data.parse(value.getTypeDetail()), RequestDetailsResponse.class), visibleKnowledge);
    }
}
