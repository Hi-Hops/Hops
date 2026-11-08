package com.hihops.hops.relation.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.KnowledgeDocument;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentVersionRepository;
import com.hihops.hops.knowledge.service.KnowledgeEvidenceAssembler;
import com.hihops.hops.knowledge.service.KnowledgeVisibilityPolicy;
import com.hihops.hops.relation.repository.RelationSearchRepository;
import com.hihops.hops.relation.repository.RelationSearchRepository.CheckRow;
import com.hihops.hops.relation.repository.RelationSearchRepository.SymptomRow;
import com.hihops.hops.relation.service.RelationSearchResult.Basis;
import com.hihops.hops.relation.service.RelationSearchResult.CaseNeighbor;
import com.hihops.hops.relation.service.RelationSearchResult.Cause;
import com.hihops.hops.relation.service.RelationSearchResult.CheckItem;
import com.hihops.hops.relation.service.RelationSearchResult.Question;
import com.hihops.hops.relation.service.RelationSearchResult.Symptom;

import lombok.RequiredArgsConstructor;

/**
 * 관계 조회의 1차 SQL 구현(RelationSource). 호출 쪽이 확정한 접근 범위 안에서 증상을 찾고, 증상마다
 * 확인 항목·직원용 질문 문구(·담당자 범위이면 원인)와 승인 사례 이웃을 모은다. 범위를 정하거나 넓히지 않는다.
 *
 * <p>증상 점수: 기능 후보가 같으면 0.5, 키워드 토큰이 증상 이름에 든 비율의 절반. 일치 신호가 없으면 결과에 없다.
 * 같은 점수는 catalogKey 순이다. 연결은 후보이며 이 서비스는 원인을 확정하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RelationSearchService {
    private final RelationSearchRepository repository;
    private final KnowledgeDocumentVersionRepository versions;
    private final KnowledgeEvidenceAssembler assembler;
    private final KnowledgeVisibilityPolicy visibility;

    /** 모든 값은 서버가 확정한 범위다. includesStaff가 false이면 직원 전용 조회다. */
    public record Query(List<String> tokens, Set<UUID> featureIds, Set<AccessLevel> levels, boolean includesStaff,
            Set<UUID> systemIds, UUID memberId, boolean internalRead, int limit) {
    }

    /** 기능이 속한 시스템(없거나 삭제된 기능은 빠진다). 요청의 기능 후보가 접근 범위 안인지 확인하는 데 쓴다. */
    public Map<UUID, UUID> featureSystems(Collection<UUID> featureIds) {
        return repository.featureSystems(featureIds);
    }

    /** @throws KnowledgeEvidenceAssembler.IntegrityException 사례 문서의 저장된 digest가 어긋날 때. 결과 없음으로 바꾸지 않는다 */
    public List<Symptom> search(Query query) {
        List<String> levels = query.levels().stream().map(Enum::name).toList();
        var viewer = new RelationSearchRepository.Viewer(query.memberId(), query.internalRead());
        List<Scored> scored = new ArrayList<>();
        for (SymptomRow row : repository.findSymptoms(levels, query.systemIds(), viewer)) {
            double score = score(query, row);
            if (score > 0) {
                scored.add(new Scored(row, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(item -> item.row().catalogKey()));
        List<Scored> top = scored.size() > query.limit() ? scored.subList(0, query.limit()) : scored;
        if (top.isEmpty()) {
            return List.of();
        }
        List<UUID> symptomIds = top.stream().map(item -> item.row().id()).toList();

        var checks = repository.findCheckItems(symptomIds, query.includesStaff(), levels, viewer);
        var questions = repository.findQuestions(checks.stream().map(CheckRow::id).distinct().toList(), query.includesStaff(), levels, viewer);
        var causes = query.includesStaff() ? repository.findCauses(symptomIds, levels, viewer) : List.<RelationSearchRepository.CauseRow>of();
        var causeChecks = repository.findCauseChecks(causes.stream().map(RelationSearchRepository.CauseRow::id).distinct().toList());
        var caseLinks = repository.findCaseLinks(symptomIds, levels, viewer);
        Map<UUID, String> checkKeys = checks.stream().collect(Collectors.toMap(CheckRow::id, CheckRow::catalogKey, (a, b) -> a));
        Map<UUID, Basis> bases = new HashMap<>();

        List<Symptom> result = new ArrayList<>();
        for (Scored item : top) {
            SymptomRow row = item.row();
            List<CheckItem> checkItems = checks.stream().filter(check -> check.symptomId().equals(row.id())).map(check -> new CheckItem(
                    check.id(), check.catalogKey(), query.includesStaff() ? check.label() : null, check.employeeDisclosable(),
                    scope(check.accessLevel()), basis(bases, check.sourceVersionId(), viewer),
                    questions.stream().filter(question -> question.checkItemId().equals(check.id())).map(question -> new Question(
                            question.id(), question.catalogKey(), question.text(),
                            basis(bases, question.sourceVersionId(), viewer),
                            query.includesStaff() ? check.employeeDisclosable() && question.employeeDisclosable()
                                    && "EMPLOYEE".equals(question.accessLevel()) : null)).toList()))
                    // 직원 질문으로 나갈 수 있는 승인된 문구가 없는 확인 항목은 직원 응답에 나타나지 않는다.
                    .filter(check -> query.includesStaff() || !check.questions().isEmpty()).toList();
            List<Cause> causeItems = query.includesStaff()
                    ? causes.stream().filter(cause -> cause.symptomId().equals(row.id())).map(cause -> new Cause(cause.id(), cause.catalogKey(),
                            cause.label(), scope(cause.accessLevel()), basis(bases, cause.sourceVersionId(), viewer),
                            causeChecks.stream().filter(link -> link.causeId().equals(cause.id())).map(link -> checkKeys.get(link.checkItemId()))
                                    .filter(java.util.Objects::nonNull).distinct().sorted().toList())).toList()
                    : null;
            List<CaseNeighbor> neighbors = new ArrayList<>();
            for (var link : caseLinks) {
                if (link.symptomId().equals(row.id())) {
                    neighbor(link.caseId(), link.caseVersion(), viewer).ifPresent(neighbors::add);
                }
            }
            result.add(new Symptom(row.id(), row.catalogKey(), row.label(), row.systemId(), row.featureId(), row.featureCode(),
                    scope(row.accessLevel()), basis(bases, row.sourceVersionId(), viewer), item.score(), checkItems, causeItems, neighbors));
        }
        return result;
    }

    private record Scored(SymptomRow row, double score) {
    }

    private static double score(Query query, SymptomRow row) {
        boolean featureMatch = query.featureIds().contains(row.featureId());
        String label = row.label().toLowerCase(Locale.ROOT);
        long matched = query.tokens().stream().filter(label::contains).count();
        if (!featureMatch && matched == 0) {
            return 0;
        }
        double ratio = query.tokens().isEmpty() ? 0 : (double) matched / query.tokens().size();
        return (featureMatch ? 0.5 : 0) + 0.5 * ratio;
    }

    /** 승인 사례 이웃. 지식 검색과 같은 현재 공개 판정·조립 규칙을 쓴다. 공개할 수 없는 사례는 없는 것으로 본다. */
    private java.util.Optional<CaseNeighbor> neighbor(UUID caseId, int caseVersion, RelationSearchRepository.Viewer viewer) {
        return versions.findCaseVersion(caseId.toString(), caseVersion)
                .filter(version -> versions.isCurrentlyVisible(version.getId(), viewer.memberId(), visibility.rule(viewer.internalRead())))
                .flatMap(assembler::assemble)
                .map(assembled -> new CaseNeighbor(assembled.documentKey(), assembled.evidence(), assembled.caseIndexStatus()));
    }

    /** 근거 문서 버전이 이 실행에 지금 공개 가능할 때만 문서 정보를 싣는다. 직원에게 담당자 런북의 ID·키를 알리지 않는다. */
    private Basis basis(Map<UUID, Basis> cache, UUID versionId, RelationSearchRepository.Viewer viewer) {
        return cache.computeIfAbsent(versionId, id -> {
            if (!versions.isCurrentlyVisible(id, viewer.memberId(), visibility.rule(viewer.internalRead()))) {
                return Basis.hidden();
            }
            KnowledgeDocumentVersion version = versions.findById(id).orElse(null);
            if (version == null) {
                return Basis.hidden();
            }
            KnowledgeDocument document = version.getKnowledgeDocument();
            return new Basis(true, document.getId(), version.getVersionNo(), assembler.documentKey(document));
        });
    }

    private static String scope(String accessLevel) {
        return "STAFF".equals(accessLevel) ? "SYSTEM_STAFF" : "EMPLOYEE";
    }
}
