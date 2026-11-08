package com.hihops.hops.knowledge.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.hihops.hops.knowledge.domain.KnowledgeDocumentVersion;
import com.hihops.hops.knowledge.repository.HybridKnowledgeRepository;
import com.hihops.hops.knowledge.repository.HybridKnowledgeRepository.RankedChunk;
import com.hihops.hops.knowledge.repository.KnowledgeDocumentVersionRepository;
import com.hihops.hops.knowledge.service.KnowledgeSearchService.Hit;
import com.hihops.hops.knowledge.service.KnowledgeSearchService.Query;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class HybridKnowledgeSearch {
    private static final Pattern CODE = Pattern.compile("(?<![a-z0-9])[a-z0-9]+(?:[-_][a-z0-9]+)+(?![a-z0-9])");
    private static final double MIN_SIMILARITY = 0.25;

    private final QueryEmbedding embedding;
    private final HybridKnowledgeRepository repository;
    private final KnowledgeDocumentVersionRepository versions;
    private final KnowledgeVisibilityPolicy visibility;
    private final KnowledgeEvidenceAssembler assembler;

    public List<Hit> search(String input, Query query) {
        return search(input, query, Mode.HYBRID);
    }

    enum Mode { KEYWORD, VECTOR, HYBRID }

    // 같은 후보·권한과 SQL을 고정 질의 비교에서도 사용한다.
    List<Hit> search(String input, Query query, Mode mode) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFC);
        List<String> codes = CODE.matcher(normalized.toLowerCase(Locale.ROOT)).results()
                .map(match -> match.group()).distinct().limit(KnowledgeQueryTokens.MAX_TOKENS).toList();
        String vector = mode == Mode.KEYWORD ? null : embedding.embed(normalized);
        var rule = visibility.rule(query.internalRead());
        var candidates = repository.rank(query, query.tokens(), codes, vector, rule.requireIndexedCase(),
                mode.name().toLowerCase(Locale.ROOT), MIN_SIMILARITY);
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 점수 계산 뒤 현재 승인·공개 범위를 한 번에 다시 조회한다. 시스템이 없으면 공통 정책만 통과한다.
        var currentVersions = versions.findLatestVisibleVersions(candidates.stream().map(RankedChunk::versionId).toList(),
                query.memberId(), rule.requireIndexedCase(), rule.internalRead(),
                query.accessLevels().stream().map(Enum::name).toList(), query.documentTypes().stream().map(Enum::name).toList(),
                query.systemIds().isEmpty() ? List.of("") : query.systemIds().stream().map(UUID::toString).toList())
                .stream().collect(Collectors.toMap(KnowledgeDocumentVersion::getId, Function.identity()));
        List<Hit> hits = new ArrayList<>();
        for (var candidate : candidates) {
            var version = currentVersions.get(candidate.versionId());
            if (version == null) {
                continue;
            }
            assembler.assemble(version).ifPresent(assembled -> {
                if (!candidate.digest().equals(assembled.contentDigest())
                        || !CanonicalJson.sha256(candidate.content()).equals(candidate.chunkDigest())
                        || !assembled.content().contains(candidate.content())) {
                    throw new KnowledgeEvidenceAssembler.IntegrityException("검색 청크가 승인 원문과 일치하지 않습니다");
                }
                hits.add(new Hit(assembled, candidate.score()));
            });
            if (hits.size() == query.topK()) {
                break;
            }
        }
        return List.copyOf(hits);
    }
}
