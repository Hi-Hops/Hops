package com.hihops.hops.knowledge.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.CaseOrigin;
import com.hihops.hops.knowledge.domain.KnowledgeDocument;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;
import com.hihops.hops.knowledge.domain.ResolvedCase;
import com.hihops.hops.knowledge.repository.ResolvedCaseRepository;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 저장된 승인 문서 버전에서 AI에 줄 근거({@link KnowledgeEvidenceResponse})를 한 가지 규칙으로 조립한다.
 * 지식 검색과 저장된 근거의 현재 공개 검증(TicketKnowledgeReader)이 같은 규칙을 쓴다.
 *
 * <ul>
 *   <li>문서 종류·공개 범위·시스템 값은 문서 행에서 읽는다.</li>
 *   <li>CASE의 출처·적용 조건·적용 시스템 버전은 사례 행에서 읽는다. 문서 버전 메타데이터에는 사례 ID·버전·반영 요청만 있다.</li>
 *   <li>가이드·정책·런북의 적용 조건은 메타데이터에 있으면 그대로 쓰고, 없으면 구조화된 값(시스템·종류·시스템 버전)으로만 조립한다.
 *       절차나 사실은 지어내지 않는다.</li>
 *   <li>저장된 digest가 원문(또는 CASE는 승인 스냅샷)과 맞지 않으면 {@link IntegrityException}이다. 조용히 빼지 않는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class KnowledgeEvidenceAssembler {
    public static final String DIGEST_CONTENT = "CONTENT";
    public static final String DIGEST_APPROVED_SNAPSHOT = "APPROVED_SNAPSHOT";

    private final ResolvedCaseRepository cases;
    private final JsonMapper mapper;

    /** 저장된 digest와 원문이 어긋난 문서 버전. 데이터 무결성 문제이며 결과 없음이 아니다. */
    public static class IntegrityException extends RuntimeException {
        public IntegrityException(String message) {
            super(message);
        }
    }

    /** CASE가 저장한 Vector·Neo4j 반영 상태. 값을 바꾸지 않고 그대로 노출한다. */
    public record CaseIndexStatus(String vector, String graph) {
    }

    public record CaseProvenance(String caseNumber, UUID ticketId, String ticketNumber, Instant approvedAt) {}

    public record Assembled(KnowledgeEvidenceResponse evidence, String documentKey, String content, String contentDigest,
            String digestBasis, CaseIndexStatus caseIndexStatus, Map<String,String> approvedParts, CaseProvenance caseProvenance) {
        public Assembled(KnowledgeEvidenceResponse evidence, String documentKey, String content, String contentDigest,
                String digestBasis, CaseIndexStatus caseIndexStatus, Map<String,String> approvedParts) {
            this(evidence, documentKey, content, contentDigest, digestBasis, caseIndexStatus, approvedParts, null);
        }
        public Assembled(KnowledgeEvidenceResponse evidence, String documentKey, String content, String contentDigest,
                String digestBasis, CaseIndexStatus caseIndexStatus) {
            this(evidence, documentKey, content, contentDigest, digestBasis, caseIndexStatus, Map.of());
        }
    }

    /**
     * @return 근거로 쓸 수 있으면 조립 결과, 필수 값(적용 조건·출처 사례)이 없어 공개할 수 없으면 비어 있다
     * @throws IntegrityException 저장된 digest가 원문과 맞지 않을 때
     */
    public Optional<Assembled> assemble(KnowledgeDocumentVersion version) {
        KnowledgeDocument document = version.getKnowledgeDocument();
        JsonNode metadata = mapper.readTree(version.getSourceMetadata());
        UUID systemId = document.getItSystem() == null ? null : document.getItSystem().getId();
        String kind = switch (document.getDocumentType()) {
            case EMPLOYEE_GUIDE -> "GUIDE";
            case POLICY -> "POLICY";
            case STAFF_RUNBOOK -> "RUNBOOK";
            case CASE -> "CASE";
        };
        String scope = document.getAccessLevel() == AccessLevel.STAFF ? "SYSTEM_STAFF" : "EMPLOYEE";
        return "CASE".equals(kind)
                ? assembleCase(version, document, metadata, systemId, scope)
                : assembleDocument(version, document, metadata, systemId, kind, scope);
    }

    private Optional<Assembled> assembleDocument(KnowledgeDocumentVersion version, KnowledgeDocument document,
            JsonNode metadata, UUID systemId, String kind, String scope) {
        if (!CanonicalJson.sha256(version.getContent()).equals(version.getContentDigest())) {
            throw new IntegrityException("문서 원문의 digest가 저장된 값과 다릅니다");
        }
        String appliesTo = text(metadata.path("appliesToSystemVersion"));
        String origin = text(metadata.path("origin"));
        if (!List.of("SYNTHETIC", "DEMO_VERIFIED", "OPERATIONAL").contains(origin == null ? "" : origin)) {
            origin = document.isSynthetic() ? "SYNTHETIC" : "OPERATIONAL";
        }
        String applicability = text(metadata.path("applicability"));
        if (applicability == null) {
            applicability = derivedApplicability(document, kind, appliesTo);
        }
        String documentKey = documentKey(document);
        var evidence = new KnowledgeEvidenceResponse(version.getId(), document.getId(), version.getVersionNo(), kind,
                document.getTitle(), version.getContent(), systemId, appliesTo, applicability, scope, origin);
        return Optional.of(new Assembled(evidence, documentKey == null ? document.getId().toString() : documentKey,
                version.getContent(), version.getContentDigest(), DIGEST_CONTENT, null));
    }

    /** 문서 키(source_data.documentKey). 사례 문서처럼 없으면 null이다. */
    public String documentKey(KnowledgeDocument document) {
        return text(mapper.readTree(document.getSourceData() == null ? "{}" : document.getSourceData()).path("documentKey"));
    }

    private Optional<Assembled> assembleCase(KnowledgeDocumentVersion version, KnowledgeDocument document,
            JsonNode metadata, UUID systemId, String scope) {
        String caseIdText = text(metadata.path("caseId"));
        JsonNode caseVersionNode = metadata.path("caseVersion");
        if (caseIdText == null || !caseVersionNode.isInt() || systemId == null) {
            return Optional.empty();
        }
        UUID caseId;
        try {
            caseId = UUID.fromString(caseIdText);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Optional<ResolvedCase> found = cases.findById(caseId);
        if (found.isEmpty() || found.get().getVersionNo() != caseVersionNode.asInt()) {
            return Optional.empty();
        }
        ResolvedCase resolved = found.get();
        // 적용 조건이 없는 사례는 어떤 문제에 써도 되는지 알 수 없어 공개하지 않는다.
        String applicability = resolved.getApplicability() == null || resolved.getApplicability().isBlank()
                ? null : resolved.getApplicability();
        if (applicability == null) {
            return Optional.empty();
        }
        JsonNode approvedCase = metadata.path("indexRequests").path("VECTOR").path("approvedCase");
        if (!approvedCase.isObject() || !CanonicalJson
                .caseSnapshotDigest(mapper, caseIdText, caseVersionNode.asInt(), approvedCase).equals(version.getContentDigest())) {
            throw new IntegrityException("사례 승인 스냅샷의 digest가 저장된 값과 다릅니다");
        }
        if (!CaseApprovalWriter.content(approvedCase).equals(version.getContent())
                || !approvedCase.path("applicability").asString().equals(applicability)) {
            throw new IntegrityException("사례 원문·적용 조건이 승인 스냅샷과 다릅니다");
        }
        Map<String,String> parts = new LinkedHashMap<>();
        for (String field : List.of("title", "problemStatement", "action", "verification")) parts.put(field, approvedCase.path(field).asString());
        String origin = resolved.getOriginType() == CaseOrigin.PRODUCTION ? "OPERATIONAL" : resolved.getOriginType().name();
        var evidence = new KnowledgeEvidenceResponse(version.getId(), caseId, resolved.getVersionNo(), "CASE",
                document.getTitle(), version.getContent(), systemId, resolved.getAppliedVersion(), applicability, scope, origin);
        var sourceTicket = resolved.getSourceWorkLog().getTicket();
        return Optional.of(new Assembled(evidence, resolved.getCaseNumber(), version.getContent(), version.getContentDigest(),
                DIGEST_APPROVED_SNAPSHOT,
                new CaseIndexStatus(resolved.getVectorStatus().name(), resolved.getGraphStatus().name()), Map.copyOf(parts),
                new CaseProvenance(resolved.getCaseNumber(), sourceTicket.getId(), sourceTicket.getTicketNumber(), resolved.getApprovedAt())));
    }

    private static String derivedApplicability(KnowledgeDocument document, String kind, String appliesTo) {
        String system = document.getItSystem() == null ? "공통" : document.getItSystem().getName();
        String label = switch (kind) {
            case "GUIDE" -> "직원 가이드";
            case "POLICY" -> "정책";
            default -> "담당자 런북";
        };
        return system + " " + label + ". " + (appliesTo == null ? "시스템 버전 미지정" : "적용 시스템 버전 " + appliesTo);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isString()) {
            return null;
        }
        String value = node.asString();
        return value.isBlank() ? null : value;
    }
}
