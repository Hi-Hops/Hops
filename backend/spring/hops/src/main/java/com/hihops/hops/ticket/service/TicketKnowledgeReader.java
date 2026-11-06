package com.hihops.hops.ticket.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hihops.hops.itsystem.repository.ItSystemQueryRepository;
import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.DataSplit;
import com.hihops.hops.knowledge.domain.DocumentType;
import com.hihops.hops.knowledge.domain.KnowledgeDocument;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;
import com.hihops.hops.knowledge.domain.ReviewStatus;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentVersionRepository;
import com.hihops.hops.knowledge.service.KnowledgeEvidenceAssembler;
import com.hihops.hops.knowledge.service.KnowledgeVisibilityPolicy;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;
import com.hihops.hops.user.domain.Member;

import lombok.RequiredArgsConstructor;

/** SEC-09: 저장된 검색 근거에도 현재 원본·검수·권한·사례 공개 gate를 적용한다. DB 장애는 빈 근거로 숨기지 않는다. */
@Service
@RequiredArgsConstructor
public class TicketKnowledgeReader {
    private final KnowledgeDocumentVersionRepository versions;
    private final ItSystemQueryRepository systems;
    private final TicketStoredData data;
    private final KnowledgeVisibilityPolicy visibility;
    private final KnowledgeEvidenceAssembler assembler;

    public List<KnowledgeEvidenceResponse> visible(String stored, Member member) {
        return data.list(stored, KnowledgeEvidenceResponse.class).stream()
                .map(value -> visible(value, member)).flatMap(Optional::stream).toList();
    }

    private Optional<KnowledgeEvidenceResponse> visible(KnowledgeEvidenceResponse evidence, Member member) {
        Optional<KnowledgeDocumentVersion> found;
        if ("CASE".equals(evidence.kind())) {
            // 승인·현재 버전·색인(설정 hops.knowledge.case-visibility에 따라)은 아래 SQL gate에서 확인한다. 캐시된 CASE 상태로 판단하지 않는다.
            found = versions.findCaseVersion(evidence.documentId().toString(), evidence.documentVersion());
        } else {
            found = versions.findByKnowledgeDocumentIdAndVersionNo(evidence.documentId(), evidence.documentVersion());
        }
        if (found.isEmpty()) return Optional.empty();
        KnowledgeDocumentVersion version = found.get();
        if (!versions.isCurrentlyVisible(version.getId(), member.getId(), visibility.userRule())) return Optional.empty();
        KnowledgeDocument document = version.getKnowledgeDocument();
        if (document.isDeleted() || document.getReviewStatus() != ReviewStatus.APPROVED
                || version.getReviewStatus() != ReviewStatus.APPROVED || document.getDataSplit() != DataSplit.RAG) {
            return Optional.empty();
        }
        if (document.getItSystem() != null && document.getItSystem().isDeleted()) return Optional.empty();
        // 종류·공개 범위·출처·적용 조건은 검색과 같은 조립 규칙으로 다시 만들고 digest도 함께 대조한다.
        Optional<KnowledgeEvidenceAssembler.Assembled> assembled;
        try {
            assembled = assembler.assemble(version);
        } catch (KnowledgeEvidenceAssembler.IntegrityException exception) {
            return Optional.empty();
        }
        if (assembled.isEmpty()) return Optional.empty();
        KnowledgeEvidenceResponse built = assembled.get().evidence();
        UUID systemId = built.systemId();
        if ((systemId == null && document.getDocumentType() != DocumentType.POLICY)
                || !Objects.equals(systemId, evidence.systemId()) || !built.kind().equals(evidence.kind())
                || !built.accessScope().equals(evidence.accessScope())) return Optional.empty();
        if (document.getAccessLevel() == AccessLevel.STAFF
                && (systemId == null ? systems.findActiveIdsByPrimaryOwnerId(member.getId()).isEmpty()
                        : !systems.findActivePrimaryOwnerId(systemId).filter(member.getId()::equals).isPresent())) {
            return Optional.empty();
        }
        // 버전 본문에 실제로 있는 발췌만 허용한다.
        if (!version.getContent().contains(evidence.excerpt())) return Optional.empty();
        return Optional.of(new KnowledgeEvidenceResponse(evidence.id(), built.documentId(), built.documentVersion(),
                built.kind(), built.title(), evidence.excerpt(), systemId, built.appliesToSystemVersion(),
                built.applicability(), built.accessScope(), built.origin()));
    }
}
