package com.hihops.hops.knowledge.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hihops.hops.auth.service.AuthService;
import com.hihops.hops.global.dto.PageResponse;
import com.hihops.hops.global.exception.*;
import com.hihops.hops.global.idempotency.*;
import com.hihops.hops.itsystem.repository.ItSystemQueryRepository;
import com.hihops.hops.knowledge.domain.*;
import com.hihops.hops.knowledge.dto.CaseCommands.*;
import com.hihops.hops.knowledge.repository.*;
import com.hihops.hops.ticket.domain.TicketStatus;
import com.hihops.hops.ticket.domain.WorkLogType;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.repository.TicketEventRepository;
import com.hihops.hops.ticket.service.TicketStoredData;
import com.hihops.hops.user.dto.UserSummaryResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class CaseService {
    private final CaseAccess access;
    private final AuthService auth;
    private final ItSystemQueryRepository systems;
    private final ResolvedCaseRepository cases;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentVersionRepository versions;
    private final TicketEventRepository events;
    private final TicketStoredData data;
    private final IdempotencyService idempotency;
    private final JsonMapper mapper;
    private final CaseJson json;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    @Transactional(readOnly = true)
    public PageResponse<JsonNode> list(Jwt jwt, UUID systemId, CaseStatus state, int page, int size) {
        var actor = auth.currentMember(jwt);
        var allowed = systems.findActiveIdsByPrimaryOwnerId(actor.getId());
        if (allowed.isEmpty() || systemId != null && !allowed.contains(systemId)) throw new BusinessException(ErrorCode.ACCESS_DENIED);
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ErrorCode.INVALID_INPUT);
        return PageResponse.of(cases.findVisible(systemId == null ? allowed : List.of(systemId), state, PageRequest.of(page, size)), value -> {
            var result = mapper.createObjectNode();
            result.put("id", value.getId().toString()).put("title", value.getTitle())
                    .put("systemId", value.getItSystem().getId().toString()).put("latestCaseVersion", value.getVersionNo())
                    .put("latestState", value.getCaseStatus().name()).put("generationState", "SUCCEEDED")
                    .put("updatedAt", value.getUpdatedAt().toString());
            var published = result.putArray("publishedVersions");
            if (searchable(value)) published.add(value.getVersionNo());
            return result;
        });
    }

    @Transactional(readOnly = true)
    public JsonNode detail(Jwt jwt, UUID id, Integer version) {
        var value = access.check(jwt, id);
        if (version != null && version != value.getVersionNo()) throw new BusinessException(ErrorCode.NOT_FOUND);
        var resolution = resolution(value);
        var result = mapper.createObjectNode();
        var body = caseBody(value, resolution);
        body.put("id", id.toString()).put("caseVersion", value.getVersionNo()).put("state", value.getCaseStatus().name())
                .put("ticketId", value.getSourceWorkLog().getTicket().getId().toString())
                .put("problemId", value.getSourceWorkLog().getTicket().getInquiryProblem().getId().toString())
                .put("createdAt", value.getCreatedAt().toString());
        result.set("version", body);
        result.putNull("approval");
        var indexes = result.putArray("indexes");
        if (value.getCaseStatus() == CaseStatus.APPROVED) {
            result.set("approval", mapper.valueToTree(Map.of("caseId", id, "caseVersion", value.getVersionNo(),
                    "approvedBy", UserSummaryResponse.from(value.getApprovedBy()), "approvedAt", value.getApprovedAt())));
            var metadata = metadata(value);
            for (var store : Store.values()) {
                var request = storedRequest(value, metadata, store.name());
                var snapshot = metadata.path("indexResults").path(store.name());
                var index = mapper.createObjectNode();
                for (var key : List.of("jobId", "caseId", "caseVersion", "store", "attempt")) index.set(key, request.path(key));
                index.put("state", snapshot.path("state").asString("PENDING"));
                index.set("error", snapshot.path("error").isMissingNode() ? mapper.nullNode() : snapshot.path("error"));
                index.put("updatedAt", snapshot.path("updatedAt").asString(value.getApprovedAt().toString()));
                indexes.add(index);
            }
        }
        result.set("publication", mapper.valueToTree(Map.of("caseId", id, "caseVersion", value.getVersionNo(),
                "state", searchable(value) ? "SEARCHABLE" : "PRIVATE", "canSearch", searchable(value))));
        return result;
    }

    public CommandResult edit(Jwt jwt, UUID id, IdempotentRequest request, Edit command) {
        access.check(jwt, id);
        return idempotency.execute(request, operation -> {
            var value = access.lock(jwt, id);
            current(value, command.expectedCaseVersion());
            if (value.getCaseStatus() != CaseStatus.DRAFT) throw new BusinessException(ErrorCode.INVALID_STATE);
            var metadata = draftMetadata(value);
            metadata.set("limitations", mapper.valueToTree(command.limitations()));
            value.editDraft(command.title(), command.problemStatement(), command.action(), command.verification(),
                    command.applicability(), command.appliesToSystemVersion(),
                    command.accessScope() == Scope.EMPLOYEE ? AccessLevel.EMPLOYEE : AccessLevel.STAFF, metadata.toString());
            changed(value);
            return new ResourceRef(ResourceType.CASE, id);
        });
    }

    public CommandResult approve(Jwt jwt, UUID id, IdempotentRequest request, Approve command) {
        access.check(jwt, id);
        return idempotency.execute(request, operation -> {
            var value = access.lock(jwt, id);
            current(value, command.expectedCaseVersion());
            if (value.getCaseStatus() != CaseStatus.DRAFT) throw new BusinessException(ErrorCode.INVALID_STATE);
            var resolution = resolution(value);
            var work = value.getSourceWorkLog();
            if (!"RESOLVED".equals(resolution.state()) || work.getTicket().getTicketStatus() != TicketStatus.RESOLVED
                    || work.getVerificationResult() == null || work.getVerificationResult().isBlank()
                    || value.getCaseType() == CaseType.IMPLEMENTATION && work.getLogType() != WorkLogType.IMPLEMENTED
                    || value.getCaseType() == CaseType.RESOLUTION && work.getLogType() != WorkLogType.RESOLVED) {
                throw new BusinessException(ErrorCode.APPROVAL_PRECONDITION_FAILED);
            }
            value.approve(auth.currentMember(jwt), clock.instant());
            var body = caseBody(value, resolution);
            body.put("approvedBy", value.getApprovedBy().getId().toString()).put("approvedAt", value.getApprovedAt().toString());
            body.set("entityLinks", data.parse(value.getRelationCandidates()));
            var content = mapper.createObjectNode().put("caseId", id.toString()).put("caseVersion", value.getVersionNo());
            content.set("approvedCase", body);
            var metadata = mapper.createObjectNode().put("caseId", id.toString()).put("caseVersion", value.getVersionNo())
                    .put("origin", body.path("origin").asString()).put("applicability", value.getApplicability())
                    .put("appliesToSystemVersion", value.getAppliedVersion());
            var requests = metadata.putObject("indexRequests");
            metadata.putObject("indexResults");
            for (var store : Store.values()) {
                var requestBody = content.deepCopy().put("store", store.name());
                var digest = json.digest(requestBody);
                requestBody.put("contractVersion", "hops-system-1").put("jobId", UUID.randomUUID().toString())
                        .put("attempt", 1).put("contentDigest", digest);
                requests.set(store.name(), requestBody);
            }
            var document = new KnowledgeDocument(value.getTitle(), DocumentType.CASE, value.getItSystem(), value.getAccessLevel(),
                    mapper.writeValueAsString(Map.of("caseId", id)), DataSplit.RAG);
            document.approve();
            documents.saveAndFlush(document);
            var stored = new KnowledgeDocumentVersion(document, value.getVersionNo(), json.canonical(content),
                    json.digest(content), metadata.toString());
            stored.approve();
            versions.saveAndFlush(stored);
            cases.flush();
            changed(value);
            for (var store : Store.values()) publisher.publishEvent(new CaseIndexRequested(id, store.name(), jwt, false));
            return new ResourceRef(ResourceType.CASE, id);
        });
    }

    public CommandResult index(Jwt jwt, UUID id, IdempotentRequest request, Index command, boolean retry) {
        access.check(jwt, id);
        return idempotency.execute(request, operation -> {
            var value = access.lock(jwt, id);
            current(value, command.expectedCaseVersion());
            if (value.getCaseStatus() != CaseStatus.APPROVED) throw new BusinessException(ErrorCode.INVALID_STATE);
            var version = document(value);
            var metadata = data.parse(version.getSourceMetadata()).deepCopy();
            var stored = storedRequest(value, metadata, command.store().name());
            var status = status(value, command.store().name());
            if (retry ? status != IndexStatus.FAILED : status != IndexStatus.PENDING) throw new BusinessException(ErrorCode.INDEX_NOT_RETRYABLE);
            if (retry) {
                var next = stored.deepCopy().put("attempt", stored.path("attempt").asInt() + 1);
                ((ObjectNode) metadata.path("indexRequests")).set(command.store().name(), next);
                ((ObjectNode) metadata.path("indexResults")).remove(command.store().name());
                version.updateIndexMetadata(metadata.toString());
                value.recordIndex(command.store().name(), IndexStatus.PENDING);
            }
            changed(value);
            publisher.publishEvent(new CaseIndexRequested(id, command.store().name(), jwt, !retry));
            return new ResourceRef(ResourceType.CASE, id);
        });
    }

    public KnowledgeDocumentVersion document(ResolvedCase value) {
        return versions.findCaseVersion(value.getId().toString(), value.getVersionNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
    }
    public ObjectNode metadata(ResolvedCase value) {
        return (ObjectNode) data.parse(document(value).getSourceMetadata());
    }
    public ObjectNode storedRequest(ResolvedCase value, JsonNode metadata, String store) {
        var request = metadata.path("indexRequests").path(store);
        if (!request.isObject() || !"hops-system-1".equals(request.path("contractVersion").asString())
                || !value.getId().toString().equals(request.path("caseId").asString())
                || request.path("caseVersion").asInt() != value.getVersionNo() || !store.equals(request.path("store").asString())
                || request.path("attempt").asInt() < 1 || !request.path("approvedCase").isObject()) unavailable();
        try { UUID.fromString(request.path("jobId").asString()); } catch (RuntimeException exception) { unavailable(); }
        var digestBody = mapper.createObjectNode();
        for (var field : List.of("caseId", "caseVersion", "store", "approvedCase")) digestBody.set(field, request.path(field));
        if (!json.digest(digestBody).equals(request.path("contentDigest").asString())) unavailable();
        var version = document(value);
        var source = data.parse(version.getContent());
        if (!json.digest(source).equals(version.getContentDigest())
                || !source.path("approvedCase").equals(request.path("approvedCase"))) unavailable();
        return (ObjectNode) request;
    }
    public static IndexStatus status(ResolvedCase value, String store) {
        return "VECTOR".equals(store) ? value.getVectorStatus() : value.getGraphStatus();
    }
    private ObjectNode draftMetadata(ResolvedCase value) {
        var stored = data.parse(value.getConfirmedFacts());
        if (stored.isObject()) return (ObjectNode) stored.deepCopy();
        var result = mapper.createObjectNode(); result.set("facts", stored); return result;
    }
    private ObjectNode caseBody(ResolvedCase value, ResolutionRequestResponse resolution) {
        var result = mapper.createObjectNode();
        result.put("workRecordId", value.getSourceWorkLog().getId().toString()).put("resolutionRequestId", resolution.id().toString())
                .put("systemId", value.getItSystem().getId().toString()).put("caseType", value.getCaseType().name())
                .put("title", value.getTitle()).put("problemStatement", value.getProblemStatement())
                .put("confirmedCause", value.getSourceWorkLog().getConfirmedCause()).put("action", value.getActionDetail())
                .put("verification", value.getVerificationResult()).put("applicability", value.getApplicability() == null ? "미확인" : value.getApplicability())
                .put("appliesToSystemVersion", value.getAppliedVersion())
                .put("accessScope", value.getAccessLevel() == AccessLevel.STAFF ? "SYSTEM_STAFF" : "EMPLOYEE")
                .put("origin", value.getOriginType() == CaseOrigin.PRODUCTION ? "OPERATIONAL" : value.getOriginType().name())
                .put("confirmationOrigin", resolution.confirmationOrigin());
        var metadata = draftMetadata(value);
        result.set("limitations", metadata.path("limitations").isArray() ? metadata.path("limitations") : mapper.createArrayNode());
        result.set("sourceMessageIds", metadata.path("sourceMessageIds").isArray() ? metadata.path("sourceMessageIds") : mapper.createArrayNode());
        return result;
    }
    private ResolutionRequestResponse resolution(ResolvedCase value) {
        ResolutionRequestResponse found = null;
        for (var event : events.findByTicketIdOrderByCreatedAtAscIdAsc(value.getSourceWorkLog().getTicket().getId())) {
            if (event.getAfterData() == null) continue;
            var node = data.parse(event.getAfterData()).path("resolutionRequest");
            if (!node.isObject() || !value.getSourceWorkLog().getId().toString().equals(node.path("sourceId").asString())) continue;
            found = data.value(node, ResolutionRequestResponse.class);
        }
        if (found == null || !"WORK_RECORD".equals(found.source()) || found.sourceVersion() != 1
                || !value.getSourceWorkLog().getTicket().getId().equals(found.ticketId())
                || !value.getSourceWorkLog().getTicket().getInquiry().getId().equals(found.inquiryId())
                || !value.getSourceWorkLog().getTicket().getInquiryProblem().getId().equals(found.problemId())) unavailable();
        return found;
    }
    private boolean searchable(ResolvedCase value) {
        if (value.getCaseStatus() != CaseStatus.APPROVED || value.getVectorStatus() != IndexStatus.SUCCEEDED
                || value.getGraphStatus() != IndexStatus.SUCCEEDED) return false;
        return systems.findActivePrimaryOwnerId(value.getItSystem().getId())
                .map(member -> versions.isCurrentlyVisible(document(value).getId(), member)).orElse(false);
    }
    private static void current(ResolvedCase value, int expected) {
        if (value.getVersionNo() != expected) throw new BusinessException(ErrorCode.CASE_VERSION_CONFLICT);
    }
    private void changed(ResolvedCase value) { publisher.publishEvent(new CaseChanged(value.getId(), value.getVersionNo())); }
    private static void unavailable() { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
}
