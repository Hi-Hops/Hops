package com.hihops.hops.ticket.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.DataSplit;
import com.hihops.hops.knowledge.domain.DocumentType;
import com.hihops.hops.knowledge.domain.KnowledgeDocument;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;
import com.hihops.hops.knowledge.domain.ReviewStatus;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentVersionRepository;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;
import com.hihops.hops.user.domain.Member;

import lombok.RequiredArgsConstructor;

/** SEC-09: 저장된 검색 근거에도 현재 원본·검수·권한·사례 공개 gate를 적용한다. DB 장애는 빈 근거로 숨기지 않는다. */
@Service
@RequiredArgsConstructor
public class TicketKnowledgeReader {
    private final KnowledgeDocumentVersionRepository versions;
    private final ItSystemRepository systems;
    private final TicketStoredData data;

    public List<KnowledgeEvidenceResponse> visible(String stored, Member member) {
        return data.list(stored, KnowledgeEvidenceResponse.class).stream()
                .map(value -> visible(value, member)).flatMap(Optional::stream).toList();
    }

    private Optional<KnowledgeEvidenceResponse> visible(KnowledgeEvidenceResponse evidence, Member member) {
        Optional<KnowledgeDocumentVersion> found;
        if ("CASE".equals(evidence.kind())) {
            // 승인·양쪽 색인·현재 버전은 아래 SQL gate에서 확인한다. 캐시된 CASE 상태로 판단하지 않는다.
            found = versions.findCaseVersion(evidence.documentId().toString(), evidence.documentVersion());
        } else {
            found = versions.findByKnowledgeDocumentIdAndVersionNo(evidence.documentId(), evidence.documentVersion());
        }
        if (found.isEmpty()) return Optional.empty();
        KnowledgeDocumentVersion version = found.get();
        if (!versions.isCurrentlyVisible(version.getId(), member.getId())) return Optional.empty();
        KnowledgeDocument document = version.getKnowledgeDocument();
        if (document.isDeleted() || document.getReviewStatus() != ReviewStatus.APPROVED
                || version.getReviewStatus() != ReviewStatus.APPROVED || document.getDataSplit() != DataSplit.RAG) {
            return Optional.empty();
        }
        UUID systemId = document.getItSystem() == null ? null : document.getItSystem().getId();
        if (document.getItSystem() != null && document.getItSystem().isDeleted()) return Optional.empty();
        String scope = document.getAccessLevel() == AccessLevel.STAFF ? "SYSTEM_STAFF" : "EMPLOYEE";
        String kind = switch (document.getDocumentType()) {
            case EMPLOYEE_GUIDE -> "GUIDE";
            case POLICY -> "POLICY";
            case STAFF_RUNBOOK -> "RUNBOOK";
            case CASE -> "CASE";
        };
        if ((systemId == null && document.getDocumentType() != DocumentType.POLICY)
                || !Objects.equals(systemId, evidence.systemId()) || !kind.equals(evidence.kind())
                || !scope.equals(evidence.accessScope())) return Optional.empty();
        if (document.getAccessLevel() == AccessLevel.STAFF
                && (systemId == null ? systems.findActiveIdsByPrimaryOwnerId(member.getId()).isEmpty()
                        : !systems.findActivePrimaryOwnerId(systemId).filter(member.getId()::equals).isPresent())) {
            return Optional.empty();
        }
        // 버전 본문과 digest가 맞고 그 본문에 실제로 있는 발췌만 허용한다.
        if (!sha256(version.getContent()).equals(version.getContentDigest())
                || !version.getContent().contains(evidence.excerpt())) return Optional.empty();
        var metadata = data.parse(version.getSourceMetadata());
        String origin = metadata.path("origin").asString(null);
        String applicability = metadata.path("applicability").asString(null);
        String appliesTo = metadata.path("appliesToSystemVersion").asString(null);
        if (!List.of("SYNTHETIC", "DEMO_VERIFIED", "OPERATIONAL").contains(origin == null ? "" : origin)
                || applicability == null || applicability.isBlank()) return Optional.empty();
        return Optional.of(new KnowledgeEvidenceResponse(evidence.id(), evidence.documentId(), evidence.documentVersion(),
                kind, document.getTitle(), evidence.excerpt(), systemId, appliesTo, applicability, scope, origin));
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없다", exception);
        }
    }
}
