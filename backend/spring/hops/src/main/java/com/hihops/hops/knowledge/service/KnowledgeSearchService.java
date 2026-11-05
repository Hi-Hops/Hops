package com.hihops.hops.knowledge.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hihops.hops.knowledge.domain.AccessLevel;
import com.hihops.hops.knowledge.domain.DocumentType;
import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentVersionRepository;
import com.hihops.hops.knowledge.repository.KnowledgeSearchRepository;
import com.hihops.hops.knowledge.service.KnowledgeEvidenceAssembler.Assembled;

import lombok.RequiredArgsConstructor;

/**
 * 1차 지식 검색(임베딩 없는 SQL). 호출 쪽이 확정한 접근 범위 안에서 후보를 찾고, 후보마다 현재 공개 판정을 다시 한 뒤
 * 점수 순으로 돌려준다. 접근 범위·시스템을 정하지 않으며 요청에서 받은 값을 넓히지 않는다.
 *
 * <p>점수: 토큰마다 제목에 있으면 3점, 원문에 있으면 1점(합산)을 토큰 수 × 4로 나눈 값이다. 토큰이 3개 이상이면 서로 다른
 * 토큰이 2개 이상 맞아야 한다(흔한 한 단어로 모든 문서가 걸리지 않게). 같은 점수는 documentKey 순이다.
 * 의미 검색이 아니라 단순 규칙이며 개선은 2차 검색의 몫이다.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeSearchService {
    private static final int TITLE_WEIGHT = 3;
    private static final int CONTENT_WEIGHT = 1;

    private final KnowledgeSearchRepository candidates;
    private final KnowledgeDocumentVersionRepository versions;
    private final KnowledgeEvidenceAssembler assembler;
    private final KnowledgeVisibilityPolicy visibility;

    /** 검색 조건. 모든 값은 서버가 확정한 범위다. */
    public record Query(List<String> tokens, Set<AccessLevel> accessLevels, Set<DocumentType> documentTypes,
            Set<UUID> systemIds, UUID memberId, int topK) {
    }

    public record Hit(Assembled assembled, double score) {
    }

    /** @throws KnowledgeEvidenceAssembler.IntegrityException 저장된 digest가 원문과 다를 때. 결과 없음으로 바꾸지 않는다 */
    public List<Hit> search(Query query) {
        List<String> patterns = query.tokens().stream().map(token -> "%" + token + "%").toList();
        List<UUID> ids = candidates.findCandidateVersionIds(
                query.accessLevels().stream().map(Enum::name).toList(),
                query.documentTypes().stream().map(Enum::name).toList(), query.systemIds(), patterns);
        int required = query.tokens().size() >= 3 ? 2 : 1;
        List<Hit> hits = new ArrayList<>();
        for (UUID id : ids) {
            // 반환 전 재검증: 현재 DB 기준의 공개 판정 한 가지를 모든 후보에 적용한다.
            if (!versions.isCurrentlyVisible(id, query.memberId(), visibility.requireIndexedCase())) {
                continue;
            }
            KnowledgeDocumentVersion version = versions.findById(id).orElse(null);
            if (version == null) {
                continue;
            }
            assembler.assemble(version).ifPresent(assembled -> {
                double score = score(query.tokens(), required, assembled);
                if (score > 0) {
                    hits.add(new Hit(assembled, score));
                }
            });
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(hit -> hit.assembled().documentKey()));
        return hits.size() > query.topK() ? List.copyOf(hits.subList(0, query.topK())) : List.copyOf(hits);
    }

    private static double score(List<String> tokens, int required, Assembled assembled) {
        String title = assembled.evidence().title().toLowerCase(Locale.ROOT);
        String content = assembled.content().toLowerCase(Locale.ROOT);
        int raw = 0;
        int matched = 0;
        for (String token : tokens) {
            boolean inTitle = title.contains(token);
            boolean inContent = content.contains(token);
            if (inTitle || inContent) {
                matched++;
            }
            raw += (inTitle ? TITLE_WEIGHT : 0) + (inContent ? CONTENT_WEIGHT : 0);
        }
        if (matched < required) {
            return 0;
        }
        return Math.min(1.0, (double) raw / (tokens.size() * (TITLE_WEIGHT + CONTENT_WEIGHT)));
    }
}
