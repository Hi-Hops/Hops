package com.hihops.hops.knowledge.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.internal.KnowledgeAuthorizationController.Command;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.exception.*;
import com.hihops.hops.itsystem.repository.ItSystemQueryRepository;
import com.hihops.hops.knowledge.domain.*;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentVersionRepository;
import com.hihops.hops.ticket.service.TicketStoredData;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class KnowledgeAuthorizationService {
    private final AgentRunRepository runs;
    private final AuthService auth;
    private final ItSystemQueryRepository systems;
    private final KnowledgeDocumentVersionRepository versions;
    private final TicketStoredData data;
    private final JsonMapper mapper;
    private final CaseJson json;

    @Transactional(readOnly = true)
    public JsonNode authorize(Command command) {
        var run = runs.findById(command.runId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var input = data.parse(run.getInputSnapshot());
        var context = input.path("accessContext");
        var actor = auth.currentMember(UUID.fromString(context.path("actorId").asString()));
        if (actor.getDepartment().isDeleted() || run.getInquiry().isDeleted() || run.getInputVersion() != run.getInquiry().getVersion()
                || command.policyRevision() != actor.getPolicyRevision()
                || command.policyRevision() != context.path("policyRevision").asInt()) denied();
        if (run.getTask() == AgentTask.INQUIRY_ANALYSIS) {
            if (run.getInquiry().getCurrentRun() == null || !run.getId().equals(run.getInquiry().getCurrentRun().getId())
                    || !actor.getId().equals(run.getInquiry().getRequester().getId())) denied();
        } else if (run.getTicket() == null || run.getTicket().isDeleted()
                || !systems.findActivePrimaryOwnerId(run.getTicket().getResponsibleSystem().getId()).filter(actor.getId()::equals).isPresent()) denied();
        var result = mapper.createObjectNode().put("contractVersion", "hops-system-1").put("requestId", command.requestId().toString())
                .put("runId", run.getId().toString()).put("policyRevision", command.policyRevision());
        var allowed = result.putArray("allowedRefs");
        var allowedSystems = data.list(context.path("systemIds"), UUID.class);
        var scopes = data.list(context.path("allowedDocumentScopes"), String.class);
        for (var candidate : command.candidates().stream().distinct().toList()) {
            var found = candidate.kind().name().equals("CASE")
                    ? versions.findCaseVersion(candidate.documentId().toString(), candidate.documentVersion())
                    : versions.findByKnowledgeDocumentIdAndVersionNo(candidate.documentId(), candidate.documentVersion());
            if (found.isEmpty()) continue;
            var version = found.get(); var document = version.getKnowledgeDocument();
            if (!versions.isCurrentlyVisible(version.getId(), actor.getId())) continue;
            var system = document.getItSystem();
            if (system == null ? document.getDocumentType() != DocumentType.POLICY : !allowedSystems.contains(system.getId())) continue;
            String kind = switch (document.getDocumentType()) {
                case CASE -> "CASE"; case EMPLOYEE_GUIDE -> "GUIDE"; case STAFF_RUNBOOK -> "RUNBOOK"; case POLICY -> "POLICY";
            };
            String scope = document.getAccessLevel() == AccessLevel.STAFF ? "SYSTEM_STAFF" : "EMPLOYEE";
            if (!candidate.kind().name().equals(kind) || !scopes.contains(scope)) continue;
            String digest = "CASE".equals(kind) ? json.digest(data.parse(version.getContent())) : CaseJson.sha256(version.getContent());
            if (!digest.equals(version.getContentDigest())) continue;
            allowed.addObject().put("documentId", candidate.documentId().toString()).put("documentVersion", candidate.documentVersion())
                    .put("kind", kind).put("accessScope", scope).put("contentDigest", digest);
        }
        return result;
    }
    private static void denied() { throw new BusinessException(ErrorCode.ACCESS_DENIED); }
}
