package com.hihops.hops.agent.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.hihops.hops.agent.dto.AiRunRequest;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.RequestIntent;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.domain.WorkType;
import com.hihops.hops.ticket.service.TicketKnowledgeReader;
import com.hihops.hops.user.domain.Member;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** SEC-05/07/09: 모델의 참조·자료 권한·확인 상태를 저장 전에 다시 검사한다. */
@Component
@RequiredArgsConstructor
public class TicketCreationValidator {
    private final TicketKnowledgeReader knowledge;
    private final JsonMapper mapper;

    public void validate(TicketCreationCommand command, AiRunRequest input, Member requester) {
        var p = command.problem();
        var t = command.proposal();
        var s = t.standardRequestSnapshot();
        require(s != null && p.problemRef().equals(t.problemRef())
                && p.processingType() == t.processingType()
                && Set.of(WorkType.SERVICE_REQUEST, WorkType.INVESTIGATION,
                        WorkType.CHANGE_REQUEST, WorkType.REVIEW_REQUIRED).contains(p.processingType())
                && p.intent() != RequestIntent.OUT_OF_SCOPE);
        require(Objects.equals(t.responsibleSystemCandidateId(), s.responsibleSystemCandidateId())
                && p.summary().equals(s.summary()) && p.facts().equals(s.confirmedFacts())
                && p.assumptions().equals(s.assumptions()) && p.unknowns().equals(s.unknowns())
                && p.details().equals(s.details()) && s.knowledge() != null && s.responsibilityEvidenceIds() != null);
        Set<UUID> systems = new HashSet<>(input.systems().stream().map(it -> it.id()).toList());
        require(systems.contains(p.reportedSystemCandidateId()) && systems.contains(t.responsibleSystemCandidateId()));
        var original = input.problems().stream().filter(it -> it.id().toString().equals(p.problemRef())).findFirst();
        if (!p.problemRef().startsWith("new:")) {
            require(original.isPresent() && (input.problemIds().isEmpty()
                    || input.problemIds().contains(original.get().id())));
        }
        if (original.isPresent()) {
            var old = original.get();
            require(!Set.of("RESOLVED", "OUT_OF_SCOPE", "IN_SERVICE", "WAITING_CONFIRMATION").contains(old.state()));
            require(old.reportedSystemId() == null || old.reportedSystemId().equals(p.reportedSystemCandidateId()));
            if (old.standardRequest() != null) {
                require(p.facts().containsAll(old.standardRequest().confirmedFacts()));
                require(p.details().acceptanceCriteria().containsAll(old.standardRequest().details().acceptanceCriteria()
                        .stream().filter(c -> "CONFIRMED".equals(c.confirmation())).toList()));
            }
        }
        // 서버가 확인한 수용 조건만 CONFIRMED를 유지할 수 있다.
        for (var c : p.details().acceptanceCriteria()) {
            require(!"CONFIRMED".equals(c.confirmation()) || original.isPresent()
                    && original.get().standardRequest() != null
                    && original.get().standardRequest().details().acceptanceCriteria().contains(c));
        }
        Set<UUID> messageIds = allowedMessages(input, p.problemRef());
        require(s.knowledge().size() <= 5);
        var knowledgeIds = s.knowledge().stream().map(it -> it.id()).toList();
        require(new HashSet<>(knowledgeIds).size() == knowledgeIds.size());
        require(s.knowledge().stream().allMatch(it -> "EMPLOYEE".equals(it.accessScope())
                && (it.systemId() == null || input.accessContext().systemIds().contains(it.systemId()))));
        // digest·승인·현재 ACL 및 원문 발췌를 기존 검색 검증기로 대조한다. 필터링으로 조용히 누락시키지 않는다.
        require(knowledge.visible(mapper.writeValueAsString(s.knowledge()), requester).equals(s.knowledge()));
        for (var fact : p.facts()) {
            require(!fact.sourceMessageIds().isEmpty() || !fact.knowledgeEvidenceIds().isEmpty());
        }
        for (var fact : Stream.concat(p.facts().stream(), p.assumptions().stream()).toList()) {
            require(messageIds.containsAll(fact.sourceMessageIds()) && knowledgeIds.containsAll(fact.knowledgeEvidenceIds()));
        }
        Set<UUID> allowed = new HashSet<>(messageIds);
        allowed.addAll(knowledgeIds);
        for (var ids : List.of(t.evidenceIds(), s.responsibilityEvidenceIds())) {
            require(new HashSet<>(ids).size() == ids.size() && allowed.containsAll(ids));
        }
        require(p.reportedSystemCandidateId().equals(t.responsibleSystemCandidateId())
                || !t.evidenceIds().isEmpty() || !s.responsibilityEvidenceIds().isEmpty());
        Set<UUID> sourceIds = new HashSet<>();
        Stream.concat(p.facts().stream(), p.assumptions().stream()).forEach(f -> sourceIds.addAll(f.sourceMessageIds()));
        sourceIds.addAll(t.evidenceIds());
        sourceIds.addAll(s.responsibilityEvidenceIds());
        Set<UUID> requiredLinks = new HashSet<>();
        input.messages().stream().filter(m -> sourceIds.contains(m.id())).forEach(m -> requiredLinks.add(m.id()));
        require(command.messageLinks() != null && command.messageLinks().size() == requiredLinks.size());
        Set<UUID> linked = new HashSet<>();
        for (var link : command.messageLinks()) {
            require(link != null && linked.add(link.messageId()) && requiredLinks.contains(link.messageId())
                    && messageIds.contains(link.messageId()) && link.spans() != null && !link.spans().isEmpty());
            var message = input.messages().stream().filter(m -> m.id().equals(link.messageId())).findFirst().orElseThrow();
            require(link.revision() == message.revision());
            int length = message.content().codePointCount(0, message.content().length());
            int previousEnd = -1;
            for (var span : link.spans()) {
                require(span.start() >= 0 && span.start() >= previousEnd && span.start() < span.end() && span.end() <= length);
                previousEnd = span.end();
            }
        }
    }

    Set<UUID> allowedMessages(AiRunRequest input, String ref) {
        boolean isNew = ref.startsWith("new:");
        Set<UUID> targets = new HashSet<>(input.problemIds().isEmpty()
                ? input.problems().stream().map(it -> it.id()).toList() : input.problemIds());
        Set<UUID> allowed = new HashSet<>();
        Set<UUID> current = new HashSet<>();
        for (var m : input.messages()) {
            current.add(m.id());
            if (m.sender() == SenderType.USER && (isNew
                    ? m.problemIds().isEmpty() || targets.containsAll(m.problemIds())
                    : m.problemIds().stream().anyMatch(id -> id.toString().equals(ref)))) allowed.add(m.id());
        }
        input.problems().stream().filter(p -> p.id().toString().equals(ref) && p.standardRequest() != null)
                .forEach(p -> Stream.concat(p.standardRequest().confirmedFacts().stream(), p.standardRequest().assumptions().stream())
                        .flatMap(f -> f.sourceMessageIds().stream()).filter(id -> !current.contains(id)).forEach(allowed::add));
        return allowed;
    }

    static void require(boolean condition) {
        if (!condition) throw new BusinessException(ErrorCode.INVALID_INPUT);
    }
}
