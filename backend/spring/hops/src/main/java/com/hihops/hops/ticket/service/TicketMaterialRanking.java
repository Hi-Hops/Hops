package com.hihops.hops.ticket.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import com.hihops.hops.ticket.dto.RecommendedCaseResponse.ComparisonStatus;
import com.hihops.hops.ticket.dto.TicketAssistanceResponse.Assessment;
import com.hihops.hops.ticket.dto.TicketAssistanceResponse.Material;
import com.hihops.hops.ticket.dto.TicketAssistanceResponse.GroupedCase;

/** 공개 검증을 통과한 후보만 업무 조건으로 정렬하고, 같은 조치의 대표를 고른다. */
final class TicketMaterialRanking {
    private TicketMaterialRanking() {}

    private static final Comparator<Material> ORDER = Comparator
            .comparingInt((Material m) -> m.assessment().mismatched() > 0 ? 1 : 0)
            .thenComparing(Comparator.comparingInt((Material m) -> m.assessment().matched()).reversed())
            .thenComparingInt(TicketMaterialRanking::criticalUnknown)
            .thenComparingInt(m -> m.assessment().unknown())
            .thenComparing(Comparator.comparingDouble(TicketMaterialRanking::score).reversed())
            .thenComparing(m -> m.evidence().id());

    // 분류 미확인인 '기능'과 달리, 나머지 적용 전 확인 항목은 조치의 전제다.
    static int criticalUnknown(Material material) {
        return (int) material.assessment().conditions().stream()
                .filter(c -> c.status() == ComparisonStatus.UNKNOWN)
                .filter(c -> !List.of("기능", "SYSTEM").contains(c.key())).count();
    }

    static List<Material> select(List<Material> candidates) {
        var groups = new ArrayList<List<Material>>();
        for (var candidate : candidates.stream().sorted(ORDER).toList()) {
            // 대표뿐 아니라 그룹 전체와 비교해, 조건이 빠진 사례를 거쳐 서로 다른 조건이 합쳐지지 않게 한다.
            var group = groups.stream().filter(g -> g.stream().allMatch(m -> sameSolution(m, candidate)))
                    .findFirst().orElse(null);
            if (group == null) {
                group = new ArrayList<>();
                groups.add(group);
            }
            group.add(candidate);
        }
        var result = new ArrayList<Material>();
        var counts = new java.util.HashMap<String, Integer>();
        for (var group : groups) {
            Material representative = group.getFirst();
            String kind = representative.evidence().kind();
            if (counts.getOrDefault(kind, 0) >= (kind.equals("CASE") ? 3 : 2)) continue;
            var a = representative.assessment();
            var reasons = new ArrayList<>(a.reasons());
            reasons.add("티켓 제목·요약·확인 사실·허용된 문의 원문을 함께 검색했습니다.");
            reasons.add("조건 불일치 여부 → 일치 조건 수 → 미확인 핵심 조건 수 → 전체 미확인 조건 수 → 통합 검색 점수 순으로 정렬합니다."
                    + " 이 자료의 핵심 미확인 조건은 " + criticalUnknown(representative) + "개입니다.");
            if (group.size() > 1) reasons.add("같은 조치이고 적용 조건이 충돌하지 않는 사례 " + group.size()
                    + "건을 묶어 조건 대조와 검색 점수 기준의 대표 사례를 표시했습니다.");
            result.add(new Material(representative.evidence(), new Assessment(a.relevance(), a.family(), reasons,
                    a.conditions(), a.quotePairs(), a.sections(), a.nextChecks(), a.matched(), a.unknown(), a.mismatched()),
                    representative.sourceCase(), group.size() > 1 ? group.stream()
                            .map(m -> new GroupedCase(m.evidence(), m.assessment(), m.sourceCase())).toList() : List.of()));
            counts.merge(kind, 1, Integer::sum);
        }
        return List.copyOf(result);
    }

    static boolean sameSolution(Material left, Material right) {
        var a = left.evidence(); var b = right.evidence();
        if (!a.kind().equals(b.kind()) || !Objects.equals(a.systemId(), b.systemId())
                || !Objects.equals(a.appliesToSystemVersion(), b.appliesToSystemVersion())
                || !Objects.equals(a.accessScope(), b.accessScope())) return false;
        if (!a.kind().equals("CASE")) return a.documentId().equals(b.documentId());
        if ((left.assessment().mismatched() > 0) != (right.assessment().mismatched() > 0)) return false;
        String action = actionKey(left), other = actionKey(right);
        if (action.isEmpty() || !action.equals(other)) return false;
        var ac = ApplicabilityConditions.current(List.of(a.applicability()));
        var bc = ApplicabilityConditions.current(List.of(b.applicability()));
        if (ac.entrySet().stream().anyMatch(e -> bc.containsKey(e.getKey()) && !e.getValue().equals(bc.get(e.getKey()))))
            return false;
        long shared = ac.entrySet().stream().filter(e -> !e.getKey().equals("기능")
                && e.getValue().equals(bc.get(e.getKey()))).count();
        return shared >= 2 || normalize(a.applicability()).equals(normalize(b.applicability()));
    }

    private static final Pattern ALL_STATE = Pattern.compile(
            "(?:조회\\s*상태|상태\\s*필터).{0,40}전체[’'\"”]?로\\s*(?:변경|바꾸|바꿔)");
    private static final Pattern OTHER_OR_UNCERTAIN_ACTION = Pattern.compile(
            "하지\\s*않|바꾸지|변경하지|예정|만약|경우|바꾸면|변경하면|대신"
            + "|(?:권한|참조).{0,20}(?:추가|부여|변경)"
            + "|기간.{0,15}(?:넓|확대|변경)|제목.{0,15}(?:삭제|지우|변경)");

    private static String actionKey(Material material) {
        // 원문 전체나 제목이 아니라 해시 검증된 승인 스냅샷의 실제 조치만 사용한다.
        String action = material.assessment().sections().stream().filter(s -> s.label().equals("실제 조치"))
                .map(s -> s.quote()).findFirst().orElse("");
        if (ALL_STATE.matcher(action).find() && !OTHER_OR_UNCERTAIN_ACTION.matcher(action).find())
            return "SEARCH_STATE_TO_ALL";
        // 지원하지 않는 표현은 의미가 같다고 추측하지 않고, 동일한 조치 원문만 합친다.
        return normalize(action);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
    }

    private static double score(Material material) {
        return material.evidence().recommendation() == null ? 0 : material.evidence().recommendation().score();
    }
}
