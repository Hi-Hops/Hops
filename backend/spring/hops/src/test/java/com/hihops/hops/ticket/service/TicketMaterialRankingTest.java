package com.hihops.hops.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.hihops.hops.ticket.dto.RecommendedCaseResponse;
import com.hihops.hops.ticket.dto.RecommendedCaseResponse.ComparisonStatus;
import com.hihops.hops.ticket.dto.RecommendedCaseResponse.ConditionComparison;
import com.hihops.hops.ticket.dto.RecommendedCaseResponse.Recommendation;
import com.hihops.hops.ticket.dto.TicketAssistanceResponse.Assessment;
import com.hihops.hops.ticket.dto.TicketAssistanceResponse.Material;
import com.hihops.hops.ticket.dto.TicketAssistanceResponse.Section;

class TicketMaterialRankingTest {
    private static final UUID SYSTEM = UUID.randomUUID();
    private static final String CONDITIONS = "기능: 문서 조회\n조회 상태: 승인\n기안자: 본인\n상신 여부: 완료\n조회 기간: 기안일 포함\n제목 검색어: 없음";
    private static final String ACTION = "문서함에서 조회 상태를 '전체'로 바꾸고 같은 기간으로 다시 조회했다.";

    @Test void 같은_조건일치수에서는_문서상태_미확인이_적은_사례가_검색점수보다_우선한다() {
        var fewerUnknown = material("조건 확인 충분", "다른 조치 A", CONDITIONS, .07, "기능");
        var moreUnknown = material("추가 확인 필요", "다른 조치 B", CONDITIONS + "\n문서 상태: 상신", .11, "기능", "문서 상태");
        var result = TicketMaterialRanking.select(List.of(moreUnknown, fewerUnknown));
        assertThat(result).extracting(m -> m.evidence().title()).containsExactly("조건 확인 충분", "추가 확인 필요");
        assertThat(result.getFirst().assessment().reasons()).anyMatch(s -> s.contains("핵심 미확인 조건은 0개"));
        assertThat(result.get(1).assessment().reasons()).anyMatch(s -> s.contains("핵심 미확인 조건은 1개"));
    }

    @Test void 조건대조가_같으면_사례ID나_입력순서로_새_사례를_우대하지_않는다() {
        var high = material("이전 사례", "조치 A", CONDITIONS, .16, "기능");
        var low = material("새 사례", "조치 B", CONDITIONS, .07, "기능");
        assertThat(TicketMaterialRanking.select(List.of(low, high))).extracting(m -> m.evidence().title())
                .containsExactly("이전 사례", "새 사례");
        assertThat(TicketMaterialRanking.select(List.of(high, low))).extracting(m -> m.evidence().title())
                .containsExactly("이전 사례", "새 사례");
    }

    @Test void 제목과_분류라벨이_달라도_같은_필터조치는_대표_하나로_묶는다() {
        var previous = material("지난 해결 사례", ACTION, CONDITIONS, .16, "기능");
        var seed = material("승인 조건에서 안 보임", "조회 상태를 승인에서 전체로 변경하고 다시 조회했습니다.",
                "사례 유형: 조회 상태 조건 불일치\n" + CONDITIONS + "\n문서 상태: 상신", .11, "기능", "문서 상태");
        var latest = material("방금 승인한 사례", "문서함 목록에서 조회 상태만 승인에서 전체로 바꿔 재조회했습니다.", CONDITIONS, .07, "기능");
        var source = new com.hihops.hops.ticket.dto.TicketAssistanceResponse.CaseSource("C-new-case", UUID.randomUUID(),
                "T-0000000000000095", java.time.Instant.parse("2026-11-10T15:22:08Z"));
        latest = new Material(latest.evidence(), latest.assessment(), source);
        var result = TicketMaterialRanking.select(List.of(seed, latest, previous));
        assertThat(result).singleElement().satisfies(m -> {
            assertThat(m.evidence().id()).isEqualTo(previous.evidence().id());
            assertThat(m.evidence().recommendation().score()).isEqualTo(.16);
            assertThat(m.assessment().reasons()).anyMatch(s -> s.contains("사례 3건을 묶어"));
            assertThat(m.groupedCases()).hasSize(3);
            assertThat(m.groupedCases()).filteredOn(c -> c.sourceCase() != null).singleElement().satisfies(c -> {
                assertThat(c.sourceCase()).isEqualTo(source);
                assertThat(c.evidence().title()).isEqualTo("방금 승인한 사례");
                assertThat(c.evidence().recommendation().score()).isEqualTo(.07);
                assertThat(c.assessment().sections().getFirst().quote()).contains("상태만 승인에서 전체로");
            });
        });
    }

    @Test void 같은_분류라도_다른_조치는_합치지_않는다() {
        var first = material("같은 분류", "조회 기간을 넓혀 다시 조회했다.", CONDITIONS, .12);
        var second = material("같은 분류", "제목 검색어를 지우고 다시 조회했다.", CONDITIONS, .11);
        assertThat(TicketMaterialRanking.select(List.of(first, second))).hasSize(2);
    }

    @Test void 조건충돌은_동일조치라도_별도로_남긴다() {
        var approved = material("승인 조회", ACTION, CONDITIONS, .16);
        var rejected = material("반려 조회", ACTION, CONDITIONS.replace("조회 상태: 승인", "조회 상태: 반려"), .15);
        assertThat(TicketMaterialRanking.sameSolution(approved, rejected)).isFalse();
        assertThat(TicketMaterialRanking.select(List.of(approved, rejected))).hasSize(2);
    }

    @Test void 조건이_빠진_사례가_충돌하는_두_조건을_같은_그룹으로_연결하지_않는다() {
        var missing = material("문서 상태 없음", ACTION, CONDITIONS, .18);
        var submitted = material("상신", ACTION, CONDITIONS + "\n문서 상태: 상신", .16);
        var rejected = material("반려", ACTION, CONDITIONS + "\n문서 상태: 반려", .15);
        assertThat(TicketMaterialRanking.select(List.of(missing, submitted, rejected))).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"조회 상태를 전체로 바꾸지 않았습니다.", "조회 상태를 전체로 변경하지 않았습니다.",
            "조회 상태를 전체로 바꾸면 보일 예정입니다.", "조회 상태를 전체로 바꾸고 참조에 사용자를 추가했습니다.",
            "조회 상태를 전체로 바꾸고 조회 기간을 넓혔습니다."})
    void 미실행_가정이나_추가조치가_있는_기록은_단일_필터조치와_합치지_않는다(String action) {
        assertThat(TicketMaterialRanking.sameSolution(material("단일 조치", ACTION, CONDITIONS, .16),
                material("다른 조치", action, CONDITIONS, .15))).isFalse();
    }

    @Test void 다른_시스템이나_시스템버전은_같은_조치라도_합치지_않는다() {
        var original = material("기준", ACTION, CONDITIONS, .16);
        var other = material("다른 시스템", ACTION, CONDITIONS, .15);
        var e = other.evidence();
        other = new Material(new RecommendedCaseResponse(e.id(), e.documentId(), e.documentVersion(), e.kind(),
                e.title(), e.excerpt(), UUID.randomUUID(), e.appliesToSystemVersion(), e.applicability(),
                e.accessScope(), e.origin(), e.recommendation()), other.assessment());
        assertThat(TicketMaterialRanking.sameSolution(original, other)).isFalse();
        var differentVersion = new Material(new RecommendedCaseResponse(e.id(), e.documentId(), e.documentVersion(), e.kind(),
                e.title(), e.excerpt(), e.systemId(), "v2", e.applicability(), e.accessScope(), e.origin(), e.recommendation()), other.assessment());
        assertThat(TicketMaterialRanking.sameSolution(original, differentVersion)).isFalse();
    }

    @Test void 단어만_비슷한_조치를_합치지_않고_사례_상한은_유지한다() {
        var candidates = new ArrayList<Material>();
        for (int i = 0; i < 5; i++) candidates.add(material("자료 " + i, "독립 조치 " + i, CONDITIONS, .2 - i * .01));
        assertThat(TicketMaterialRanking.select(candidates)).extracting(m -> m.evidence().title())
                .containsExactly("자료 0", "자료 1", "자료 2");
    }

    private static Material material(String title, String action, String applicability, double score, String... unknown) {
        var unknownFields = List.of(unknown);
        var conditions = ApplicabilityConditions.current(List.of(applicability)).entrySet().stream().map(e ->
                new ConditionComparison(e.getKey(), e.getKey(), unknownFields.contains(e.getKey()) ? ComparisonStatus.UNKNOWN
                        : ComparisonStatus.MATCHED, unknownFields.contains(e.getKey()) ? List.<String>of() : List.of(e.getValue()),
                        e.getKey() + ": " + e.getValue())).toList();
        int matched = (int) conditions.stream().filter(c -> c.status() == ComparisonStatus.MATCHED).count();
        var evidence = new RecommendedCaseResponse(UUID.randomUUID(), UUID.randomUUID(), 1, "CASE", title,
                action, SYSTEM, null, applicability, "SYSTEM_STAFF", "SYNTHETIC",
                new Recommendation("문의 문맥", score, "WEIGHTED_RRF", 1, 1, 10.0, .7, List.of(), List.of(), List.of()));
        return new Material(evidence, new Assessment("MEDIUM", "같은 분류", List.of(), conditions, List.of(),
                List.of(new Section("실제 조치", action, 0, action.length())), List.of(), matched, unknown.length, 0));
    }
}
