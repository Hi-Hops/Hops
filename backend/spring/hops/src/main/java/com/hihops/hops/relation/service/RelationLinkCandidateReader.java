package com.hihops.hops.relation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.hihops.hops.relation.repository.Neo4jRelationReader;
import com.hihops.hops.relation.repository.RelationSearchRepository;
import com.hihops.hops.relation.repository.RelationSearchRepository.Viewer;

import lombok.RequiredArgsConstructor;

/**
 * 사례 초안(Curator)이 연결할 수 있는 기존 노드. 해당 시스템의 승인된 증상과 확인 항목 중 실행 주체(현재 주담당자)에게 보이는 것만이다.
 * 원인은 연결 대상이 아니다. 새 노드를 만들거나 범위를 넓히지 않으며, AI가 낸 연결 대상을 결과 적용 때 다시 확인하는 데도 같은 규칙을 쓴다.
 */
@Service
@RequiredArgsConstructor
public class RelationLinkCandidateReader {
    /** AI wire(LinkCandidate)의 종류별 상한과 같다. 입력에 담는 후보에만 적용하고 결과 확인에는 적용하지 않는다. */
    public static final int MAX_SYMPTOMS = 30;
    public static final int MAX_CHECK_ITEMS = 40;
    private static final List<String> LEVELS = List.of("EMPLOYEE", "STAFF");

    private final RelationSearchRepository repository;
    private final Neo4jRelationReader graph;
    @Value("${hops.search.offline-sql:false}")
    private boolean offlineSql;

    /** @param kind SYMPTOM 또는 CHECK_ITEM (AI wire와 같은 이름) */
    public record Candidate(UUID id, String kind, String label, UUID systemId) {
    }

    /** 입력에 담을 후보. 종류별 상한까지 catalogKey 순이다. */
    public List<Candidate> candidates(UUID systemId, UUID memberId) {
        return limited(all(systemId, memberId));
    }

    /** 지금 연결할 수 있는 모든 노드(상한 없음). AI 결과의 연결 대상이 아직 유효한지 확인하는 데 쓴다. */
    public List<Candidate> currentlyLinkable(UUID systemId, UUID memberId) {
        return all(systemId, memberId);
    }

    private List<Candidate> all(UUID systemId, UUID memberId) {
        if (!offlineSql) {
            return graph.linkable(systemId, memberId).stream()
                    .map(row -> new Candidate(row.id(), row.kind(), row.label(), systemId)).toList();
        }
        var viewer = new Viewer(memberId, false);
        var symptoms = repository.findSymptoms(LEVELS, List.of(systemId), viewer).stream()
                .map(row -> new Candidate(row.id(), "SYMPTOM", row.label(), systemId));
        var checks = repository.findSystemCheckItems(systemId, LEVELS, viewer).stream()
                .map(row -> new Candidate(row.id(), "CHECK_ITEM", row.label(), systemId));
        return Stream.concat(symptoms, checks).toList();
    }

    private static List<Candidate> limited(List<Candidate> all) {
        var kept = new ArrayList<Candidate>();
        int symptoms = 0;
        int checks = 0;
        for (var candidate : all) {
            boolean symptom = "SYMPTOM".equals(candidate.kind());
            if (symptom ? symptoms >= MAX_SYMPTOMS : checks >= MAX_CHECK_ITEMS) continue;
            kept.add(candidate);
            if (symptom) symptoms++; else checks++;
        }
        return kept;
    }
}
