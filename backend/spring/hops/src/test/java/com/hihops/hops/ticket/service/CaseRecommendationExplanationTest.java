package com.hihops.hops.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.hihops.hops.knowledge.service.KnowledgeEvidenceAssembler.Assembled;
import com.hihops.hops.knowledge.service.KnowledgeSearchService.Hit;
import com.hihops.hops.knowledge.service.KnowledgeSearchService.RankingEvidence;
import com.hihops.hops.ticket.dto.RecommendedCaseResponse.ComparisonStatus;
import com.hihops.hops.ticket.dto.RecommendedCaseResponse.ConditionComparison;
import com.hihops.hops.ticket.dto.StandardRequestResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.EvidenceStatementResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.KnowledgeEvidenceResponse;

class CaseRecommendationExplanationTest {
    private static final UUID SYSTEM = UUID.randomUUID();
    private final CaseRecommendationExplanation service = new CaseRecommendationExplanation();

    @Test void 문맥검색은_제목만_비교했다고_설명하지_않는다() {
        var result = service.explain("제목\n확인된 문의 원문", SYSTEM, "전자결재", request(List.of(), List.of()),
                hit(), CaseRecommendationExplanation.QueryScope.CONTEXT);
        assertThat(result.recommendation().reasons()).allMatch(s -> s.contains("제목·요약·확인 사실·허용된 문의 원문"));
        assertThat(result.recommendation().query()).isEqualTo("제목\n확인된 문의 원문");
    }

    @Test
    void F009_CONTRACT_07_실제_검색경로와_원점수를_보존하고_미확인조건을_구분한다() {
        var result = service.explain("상신 문서가 안 보임", SYSTEM, "전자결재",
                request(List.of("조회 상태는 ‘승인’으로 설정되어 있다."), List.of("추정 원인 비밀")), hit());
        var explanation = result.recommendation();
        assertThat(explanation.score()).isEqualTo(0.8 / 11 + 1.0 / 12);
        assertThat(explanation.query()).isEqualTo("상신 문서가 안 보임");
        assertThat(explanation.scoreMethod()).isEqualTo("WEIGHTED_RRF");
        assertThat(explanation.reasons()).anyMatch(text -> text.contains("일치 검색어: 문서"));
        assertThat(explanation.reasons()).anyMatch(text -> text.contains("의미 유사도") && text.contains("2위"));
        assertThat(comparison(result, "SYSTEM").status()).isEqualTo(ComparisonStatus.MATCHED);
        assertThat(comparison(result, "SEARCH_STATE").status()).isEqualTo(ComparisonStatus.MATCHED);
        assertThat(comparison(result, "APPLICABILITY").status()).isEqualTo(ComparisonStatus.UNKNOWN);
        assertThat(comparison(result, "SYSTEM_VERSION").status()).isEqualTo(ComparisonStatus.UNKNOWN);
        assertThat(explanation.comparisons().toString()).doesNotContain("추정 원인 비밀");
    }

    @Test
    void F009_CONTRACT_07_다른_조회상태는_불일치로_표시한다() {
        var result = service.explain("문서 안 보임", SYSTEM, "전자결재",
                request(List.of("조회 상태는 ‘전체’로 설정되어 있다."), List.of()), hit());
        assertThat(comparison(result, "SEARCH_STATE").status()).isEqualTo(ComparisonStatus.MISMATCHED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"문서의 실제 상태는 승인입니다.", "조회 상태는 승인인지 모르겠어요.",
            "조회 상태는 승인인 경우가 아닙니다.", "조회 상태는 승인 또는 전체입니다.",
            "조회 상태는 승인으로 설정되어 있었고 전체로 변경했습니다.", "조회 상태가 승인인가요?",
            "조회 상태는 승인일 수도 있습니다.", "조회 상태를 승인으로 선택하세요.",
            "조회 상태가 승인으로 설정되어 있어야 합니다."})
    void F009_CONTRACT_07_실제문서상태_부정_모름_변경이력_질문을_필터일치로_단정하지_않는다(String fact) {
        var result = service.explain("문서 안 보임", SYSTEM, "전자결재", request(List.of(fact), List.of()), hit());
        assertThat(comparison(result, "SEARCH_STATE").status()).isEqualTo(ComparisonStatus.UNKNOWN);
    }

    @Test
    void F009_CONTRACT_07_서로_다른_확인값은_미확인으로_남긴다() {
        var result = service.explain("문서 안 보임", SYSTEM, "전자결재", request(List.of(
                "조회 상태는 승인입니다.", "조회 상태는 전체입니다."), List.of()), hit());
        assertThat(comparison(result, "SEARCH_STATE").status()).isEqualTo(ComparisonStatus.UNKNOWN);
    }

    @Test
    void F009_CONTRACT_07_추정에만_있는_조회상태를_비교에_사용하지_않는다() {
        var result = service.explain("문서 안 보임", SYSTEM, "전자결재",
                request(List.of(), List.of("조회 상태는 승인입니다.")), hit());
        assertThat(comparison(result, "SEARCH_STATE").status()).isEqualTo(ComparisonStatus.UNKNOWN);
        assertThat(comparison(result, "SEARCH_STATE").currentEvidence()).isEmpty();
    }

    @Test
    void F009_CONTRACT_07_벡터만_기여하면_키워드_검색으로_설명하지_않는다() {
        var source = hit();
        var vectorOnly = new Hit(source.assembled(), 1.0 / 11,
                new RankingEvidence(null, 1, null, 0.8, List.of()));
        var result = service.explain("유사 현상", SYSTEM, "전자결재", request(List.of(), List.of()), vectorOnly);
        assertThat(result.recommendation().reasons()).hasSize(1).allMatch(text -> text.contains("의미 유사도"));
        assertThat(result.recommendation().keywordRank()).isNull();
    }

    private static ConditionComparison comparison(com.hihops.hops.ticket.dto.RecommendedCaseResponse response, String key) {
        return response.recommendation().comparisons().stream().filter(item -> item.key().equals(key)).findFirst().orElseThrow();
    }

    private static StandardRequestResponse request(List<String> facts, List<String> assumptions) {
        return new StandardRequestResponse(UUID.randomUUID(), UUID.randomUUID(), 1, "요약", statements(facts),
                statements(assumptions), List.of(), SYSTEM, List.of(), null, List.of());
    }

    private static List<EvidenceStatementResponse> statements(List<String> texts) {
        return texts.stream().map(text -> new EvidenceStatementResponse(text, List.of(UUID.randomUUID()), List.of())).toList();
    }

    private static Hit hit() {
        var evidence = new KnowledgeEvidenceResponse(UUID.randomUUID(), UUID.randomUUID(), 2, "CASE", "상신 문서 조회",
                "승인된 사례 원문", SYSTEM, null, "조회 상태가 ‘승인’으로 설정되어 있고, 본인이 상신한 문서에 적용한다.",
                "SYSTEM_STAFF", "SYNTHETIC");
        return new Hit(new Assembled(evidence, "case-test", evidence.excerpt(), "digest", "APPROVED_SNAPSHOT", null),
                0.8 / 11 + 1.0 / 12, new RankingEvidence(1, 2, 4.0, 0.8, List.of("문서")));
    }
}
