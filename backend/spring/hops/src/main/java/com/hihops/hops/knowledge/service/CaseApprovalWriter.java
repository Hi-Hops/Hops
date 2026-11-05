package com.hihops.hops.knowledge.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.knowledge.domain.CaseOrigin;
import com.hihops.hops.knowledge.domain.DataSplit;
import com.hihops.hops.knowledge.domain.DocumentType;
import com.hihops.hops.knowledge.domain.IndexStore;
import com.hihops.hops.knowledge.domain.KnowledgeDocument;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;
import com.hihops.hops.knowledge.domain.ResolvedCase;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentRepository;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentVersionRepository;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.user.domain.Member;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 사례 승인의 저장 부분. 한 거래 안에서 사례 승인, 검색 문서 버전, 저장소별 반영 요청을 함께 만든다
 * (contracts/agents I1.1 "승인과 요청 보존"). 호출 쪽 거래가 실패하면 이 저장도 모두 취소된다.
 *
 * <p>반영 요청(IndexApplyRequest)은 knowledge_document_version.source_metadata.indexRequests의 VECTOR·NEO4J에 각각 전체를 남긴다.
 * digest는 {caseId, caseVersion, store, approvedCase}를 키 오름차순·공백 없는 JSON으로 직렬화한 UTF-8의 SHA-256이다.
 * 외부 호출은 하지 않는다. 승인 commit 뒤 전달·확인은 index-checks가 맡는다.
 */
@Component
@RequiredArgsConstructor
class CaseApprovalWriter {
    static final String CONTRACT_VERSION = "hops-system-1";
    static final String[] STORES = {"VECTOR", "NEO4J"};

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentVersionRepository versions;
    private final JsonMapper mapper;
    private final ApplicationEventPublisher events;

    void approve(ResolvedCase resolved, Member approver, ResolutionRequestResponse confirmation) {
        UUID caseId = resolved.getId();
        if (versions.findCaseVersion(caseId.toString(), resolved.getVersionNo()).isPresent()) {
            // 승인 전 사례에 검색 문서 버전이 있으면 저장 형식이 깨진 것이다. 덮어쓰거나 새로 만들지 않는다.
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        Instant approvedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        resolved.approve(approver, approvedAt);

        ObjectNode approvedCase = approvedCase(resolved, approver, confirmation, approvedAt);
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("caseId", caseId.toString());
        metadata.put("caseVersion", resolved.getVersionNo());
        ObjectNode requests = metadata.putObject("indexRequests");
        for (String store : STORES) {
            requests.set(store, indexRequest(resolved, store, approvedCase));
        }

        KnowledgeDocument document = new KnowledgeDocument(resolved.getTitle(), DocumentType.CASE, resolved.getItSystem(),
                resolved.getAccessLevel(), mapper.createObjectNode().put("caseId", caseId.toString()).toString(), DataSplit.RAG);
        document.approve(resolved.getOriginType() == CaseOrigin.SYNTHETIC);
        documents.save(document);

        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion(document, resolved.getVersionNo(),
                content(approvedCase), CanonicalJson.caseSnapshotDigest(mapper, caseId.toString(), resolved.getVersionNo(), approvedCase),
                metadata.toString());
        version.approve();
        versions.save(version);
        // 승인 거래가 commit된 뒤에만 저장한 요청을 AI에 전달한다. 거래가 취소되면 전달도 없다.
        for (String store : STORES) {
            events.publishEvent(new CaseIndexRequested(caseId, IndexStore.valueOf(store),
                    requests.path(store).path("jobId").asString(), 1, CaseIndexRequested.Mode.DELIVER));
        }
    }

    private ObjectNode approvedCase(ResolvedCase resolved, Member approver, ResolutionRequestResponse confirmation,
            Instant approvedAt) {
        JsonNode facts = mapper.readTree(resolved.getConfirmedFacts());
        if (!facts.isObject() || !facts.path("limitations").isArray() || !facts.path("sourceMessageIds").isArray()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        JsonNode relations = mapper.readTree(resolved.getRelationCandidates());
        if (!relations.isArray()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("workRecordId", resolved.getSourceWorkLog().getId().toString());
        body.put("resolutionRequestId", confirmation.id().toString());
        body.put("approvedBy", approver.getId().toString());
        body.put("approvedAt", approvedAt.toString());
        body.put("title", resolved.getTitle());
        body.put("action", resolved.getActionDetail());
        body.put("verification", resolved.getVerificationResult());
        body.put("applicability", resolved.getApplicability());
        body.set("limitations", facts.path("limitations"));
        body.put("appliesToSystemVersion", resolved.getAppliedVersion());
        body.put("accessScope", CaseQueryService.accessScope(resolved));
        body.put("origin", CaseQueryService.origin(resolved));
        body.put("confirmationOrigin", confirmation.confirmationOrigin());
        body.set("sourceMessageIds", facts.path("sourceMessageIds"));
        body.set("entityLinks", relations);
        body.put("systemId", resolved.getItSystem().getId().toString());
        body.put("confirmedCause", resolved.getConfirmedCause());
        body.put("problemStatement", resolved.getProblemStatement());
        body.put("caseType", resolved.getCaseType().name());
        return body;
    }

    private ObjectNode indexRequest(ResolvedCase resolved, String store, ObjectNode approvedCase) {
        ObjectNode subject = mapper.createObjectNode();
        subject.put("caseId", resolved.getId().toString()).put("caseVersion", resolved.getVersionNo()).put("store", store);
        subject.set("approvedCase", approvedCase);
        ObjectNode request = mapper.createObjectNode();
        request.put("contractVersion", CONTRACT_VERSION);
        request.put("jobId", UUID.randomUUID().toString());
        request.put("caseId", resolved.getId().toString());
        request.put("caseVersion", resolved.getVersionNo());
        request.put("store", store);
        request.put("attempt", 1);
        request.put("contentDigest", sha256(canonical(subject)));
        request.set("approvedCase", approvedCase);
        return request;
    }

    /** 검색 조각의 원문. 사례 본문 필드를 사람이 읽는 순서로 잇는다. */
    private static String content(ObjectNode body) {
        StringBuilder text = new StringBuilder();
        for (String field : new String[] {"title", "problemStatement", "action", "verification", "applicability"}) {
            text.append(body.path(field).asString()).append("\n\n");
        }
        var limitations = new ArrayList<String>();
        body.path("limitations").forEach(item -> limitations.add(item.asString()));
        if (!limitations.isEmpty()) text.append(String.join("\n", limitations));
        return text.toString().strip();
    }

    private String canonical(JsonNode node) {
        return CanonicalJson.canonical(mapper, node);
    }

    private static String sha256(String text) {
        return CanonicalJson.sha256(text);
    }
}
