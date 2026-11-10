package com.hihops.hops.ticket.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.hihops.hops.knowledge.service.KnowledgeSearchService.Hit;
import com.hihops.hops.ticket.dto.RecommendedCaseResponse;
import com.hihops.hops.ticket.dto.RecommendedCaseResponse.ComparisonStatus;
import com.hihops.hops.ticket.dto.RecommendedCaseResponse.ConditionComparison;
import com.hihops.hops.ticket.dto.RecommendedCaseResponse.Recommendation;
import com.hihops.hops.ticket.dto.StandardRequestResponse;

/** 검색 경로는 실제 랭킹에서 설명하고, 적용 여부는 확인 사실과 승인 조건을 별도로 대조한다. */
@Component
public class CaseRecommendationExplanation {
    public enum QueryScope {
        TITLE("티켓 제목"), CONTEXT("티켓 제목·요약·확인 사실·허용된 문의 원문");
        private final String description;
        QueryScope(String description) { this.description = description; }
    }
    // 명시된 조회 필터만 읽는다. 문서의 실제 결재 상태나 '전체로 변경' 같은 조치와 섞지 않는다.
    private static final Pattern SEARCH_STATE = Pattern.compile(
            "(?:조회\\s*상태|상태\\s*필터)(?:가|는|를|:)?\\s*[‘'\"“]?"
            + "(승인|전체|상신|결재\\s*중|반려|회수)[’'\"”]?"
            + "(?=\\s*(?:으로\\s*(?:설정|선택)|로\\s*(?:설정|선택)|입니다|이에요|예요|이다|인|일|[,.;\\n]|$))");
    private static final Pattern UNCERTAIN = Pattern.compile(
            "아니|아닌|아닙|아닐|않|모르|미확인|불명|추정|추측|가능|혹은|또는|이거나|였|바꿨|변경했"
            + "|만약|맞나요|인가요|일\\s*수|일지도|겠|예정|아마|기대|어야|하세요|하십시오|권장|가정|\\?");

    public RecommendedCaseResponse explain(String query, UUID systemId, String systemName,
            StandardRequestResponse request, Hit hit) {
        return explain(query, systemId, systemName, request, hit, QueryScope.TITLE);
    }

    public RecommendedCaseResponse explain(String query, UUID systemId, String systemName,
            StandardRequestResponse request, Hit hit, QueryScope scope) {
        var evidence = hit.assembled().evidence();
        var ranking = hit.ranking();
        if (ranking == null) return RecommendedCaseResponse.of(evidence, null);
        List<String> reasons = new ArrayList<>();
        if (ranking.keywordRank() != null) {
            reasons.add(scope.description + "에서 추출한 단어와 자료 제목·본문을 대조한 키워드 검색에서 " + ranking.keywordRank() + "위입니다."
                    + (ranking.matchedKeywords().isEmpty() ? ""
                    : " 일치 검색어: " + String.join(", ", ranking.matchedKeywords())));
        }
        if (ranking.semanticRank() != null) {
            reasons.add(scope.description + "과 자료의 의미 유사도를 비교한 검색에서 " + ranking.semanticRank() + "위입니다.");
        }
        var facts = request.confirmedFacts().stream().map(item -> item.text()).toList();
        List<ConditionComparison> comparisons = new ArrayList<>();
        comparisons.add(new ConditionComparison("SYSTEM", "책임 시스템",
                Objects.equals(systemId, evidence.systemId()) ? ComparisonStatus.MATCHED : ComparisonStatus.MISMATCHED,
                List.of(systemName), Objects.equals(systemId, evidence.systemId()) ? systemName : "다른 시스템"));
        var expected = searchStates(List.of(evidence.applicability()));
        if (evidence.applicability().matches("(?s).*(?:조회\\s*상태|상태\\s*필터).*")) {
            var actual = searchStates(facts);
            var status = actual.size() == 1 && expected.size() == 1
                    ? (actual.getFirst().equals(expected.getFirst()) ? ComparisonStatus.MATCHED : ComparisonStatus.MISMATCHED)
                    : ComparisonStatus.UNKNOWN;
            comparisons.add(new ConditionComparison("SEARCH_STATE", "명시된 조회 상태", status,
                    facts.stream().filter(text -> SEARCH_STATE.matcher(text).find()).toList(), evidence.applicability()));
        }
        // 자유서술 전체를 키워드 일치만으로 충족 판정하지 않는다. 미지원 조건은 원문을 나란히 제공한다.
        comparisons.add(new ConditionComparison("APPLICABILITY", "나머지 적용 조건", ComparisonStatus.UNKNOWN,
                facts, evidence.applicability()));
        comparisons.add(new ConditionComparison("SYSTEM_VERSION", "적용 시스템 버전", ComparisonStatus.UNKNOWN,
                List.of(), evidence.appliesToSystemVersion() == null ? "사례의 적용 시스템 버전 미확인"
                        : evidence.appliesToSystemVersion()));
        return RecommendedCaseResponse.of(evidence, new Recommendation(query, hit.score(), "WEIGHTED_RRF",
                ranking.keywordRank(), ranking.semanticRank(), ranking.keywordScore(), ranking.semanticSimilarity(),
                ranking.matchedKeywords(), reasons, comparisons));
    }

    private static List<String> searchStates(List<String> texts) {
        List<String> values = new ArrayList<>();
        for (String text : texts) {
            // 부정·과거·가정·선택지가 있는 문장은 값 하나로 확정하지 않는다.
            if (UNCERTAIN.matcher(text).find()) {
                if (SEARCH_STATE.matcher(text).find()) return List.of();
                continue;
            }
            var matcher = SEARCH_STATE.matcher(text);
            while (matcher.find()) {
                String state = matcher.group(1).replaceAll("\\s+", "");
                if (!values.contains(state)) values.add(state);
            }
        }
        return values;
    }
}
