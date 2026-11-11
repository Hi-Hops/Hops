package com.hihops.hops.ticket.service;

import java.util.*;
import org.springframework.stereotype.Component;
import com.hihops.hops.ticket.dto.*;
import com.hihops.hops.ticket.dto.RecommendedCaseResponse.*;
import com.hihops.hops.ticket.dto.TicketAssistanceResponse.*;
import com.hihops.hops.ticket.dto.StandardRequestResponse.EvidenceStatementResponse;

/** 구조화된 조건 비교와 실제 인용 구간으로 업무 설명을 만든다. 모델 설명을 사실로 보충하지 않는다. */
@Component
public class TicketMaterialAssessment {
    public Assessment assess(RecommendedCaseResponse item, StandardRequestResponse request,
            List<RelatedMessageResponse> sources) {
        return assess(item, request, sources, null);
    }

    public Assessment assess(RecommendedCaseResponse item, StandardRequestResponse request,
            List<RelatedMessageResponse> sources, String recordedFeature) {
        return assess(item,request,sources,recordedFeature,Map.of());
    }

    public Assessment assess(RecommendedCaseResponse item, StandardRequestResponse request,
            List<RelatedMessageResponse> sources, String recordedFeature, Map<String,String> approvedParts) {
        var facts = request.confirmedFacts().stream().filter(f -> !f.sourceMessageIds().isEmpty()).map(EvidenceStatementResponse::text).toList();
        var actual = ApplicabilityConditions.current(facts);
        // 요약 과정에서 빠진 조건은 같은 문제로 공개가 허용된 USER 원문에서만 보완한다.
        var original = ApplicabilityConditions.current(sources.stream().map(RelatedMessageResponse::content).toList());
        original.forEach((field,value) -> {
            boolean explicitlyStated=facts.stream().anyMatch(f -> java.util.regex.Pattern
                    .compile("(?m)^\\s*"+java.util.regex.Pattern.quote(field)+"\\s*[:：]").matcher(f).find());
            if (!explicitlyStated) actual.putIfAbsent(field,value);
        });
        boolean featureMentioned = facts.stream().anyMatch(f -> java.util.regex.Pattern
                .compile("(?m)^\\s*기능\\s*[:：]").matcher(f).find());
        if(recordedFeature != null && !featureMentioned) actual.putIfAbsent("기능", recordedFeature);
        var expected = item.kind().equals("CASE")
                ? ApplicabilityConditions.current(List.of(item.applicability()))
                : ApplicabilityConditions.explicit(item.applicability());
        List<ConditionComparison> conditions = new ArrayList<>();
        List<QuotePair> pairs = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        for (var entry : expected.entrySet()) {
            String field = entry.getKey(), value = actual.get(field);
            var status = ComparisonStatus.valueOf(ApplicabilityConditions.compare(value, entry.getValue()));
            String conditionLine = lineContaining(item.applicability(), field + ":");
            if (conditionLine == null) conditionLine = quoteFor(field, entry.getValue(), item.applicability());
            if (conditionLine == null) continue;
            conditions.add(new ConditionComparison(field, field, status, value == null ? List.of() : List.of(value), conditionLine));
            if (status == ComparisonStatus.UNKNOWN) checks.add("‘" + field + "’ 항목을 먼저 확인해 주세요.");
            if (value != null) for (var source : sources) {
                String quote = lineContaining(source.content(), field + ":");
                if (quote == null) quote = quoteFor(field, value, source.content());
                if (quote != null && !(field.equals("기능")
                        ? com.hihops.hops.itsystem.service.ExplicitFeatureEvidence.namesFeature(quote, value)
                        : value.equals(ApplicabilityConditions.current(List.of(quote)).get(field)))) quote = null;
                int target = item.excerpt().indexOf(conditionLine);
                if (quote != null && target >= 0) {
                    int start = source.content().indexOf(quote);
                    pairs.add(new QuotePair(field, status.name(), source.id(), quote, start, start + quote.length(),
                            conditionLine, target, target + conditionLine.length()));
                    break;
                }
            }
        }
        if (conditions.isEmpty() && item.recommendation() != null) {
            conditions.addAll(item.recommendation().comparisons().stream()
                    .filter(c -> !c.key().equals("SYSTEM") && !c.key().equals("SYSTEM_VERSION")).toList());
        }
        int matched = (int) conditions.stream().filter(c -> c.status() == ComparisonStatus.MATCHED).count();
        int conflict = (int) conditions.stream().filter(c -> c.status() == ComparisonStatus.MISMATCHED).count();
        int unknown = (int) conditions.stream().filter(c -> c.status() == ComparisonStatus.UNKNOWN).count();
        String band = conflict > 0 ? "CONFLICT" : matched >= 2 && unknown == 0 ? "HIGH" : matched > 0 ? "MEDIUM" : "REFERENCE";
        List<String> reasons = new ArrayList<>();
        if (matched > 0) reasons.add(conditions.stream().filter(c -> c.status() == ComparisonStatus.MATCHED)
                .limit(4)
                .map(c -> c.label() + " ‘" + String.join(", ", c.currentEvidence()) + "’").reduce((a,b) -> a + ", " + b).orElse("")
                + " 조건이 현재 문의와 같습니다.");
        if (conflict > 0) reasons.add("현재 문의와 다른 조건이 있어 이 자료의 조치를 그대로 적용하면 안 됩니다.");
        if (unknown > 0) reasons.add("아직 확인되지 않은 적용 조건 " + unknown + "개가 있습니다.");
        if (matched == 0 && conflict == 0) reasons.add(switch (item.kind()) {
            case "GUIDE" -> "현재 화면의 정상 사용 절차와 조회 조건을 확인할 수 있는 매뉴얼입니다.";
            case "RUNBOOK" -> "원인을 확정하기 전에 확인할 항목과 분기별 점검 순서를 참고하세요.";
            case "POLICY" -> "허용 범위와 제한·예외 조건을 대조할 수 있는 정책입니다.";
            default -> "유사한 내용의 과거 사례입니다. 적용 조건을 먼저 대조해 주세요.";
        });
        if (matched > 0 && !item.kind().equals("CASE")) reasons.add(switch (item.kind()) {
            case "GUIDE" -> "현재 화면에서 진행할 정상 절차와 입력·조회 조건을 확인할 수 있습니다.";
            case "RUNBOOK" -> "아래 점검 순서로 가능한 원인을 좁히고, 결과별 다음 확인을 진행할 수 있습니다.";
            default -> "업무에 적용되는 제한과 예외 조건을 확인할 수 있습니다.";
        });
        if (checks.isEmpty()) checks.add("현재 문의의 조건과 아래 원문을 대조한 뒤 조치 결과를 직접 검증해 주세요.");
        String family = labelled(item.applicability(), "사례 유형");
        return new Assessment(band, family == null ? item.title() : family, reasons, conditions, pairs,
                sections(item, approvedParts), checks.stream().limit(4).toList(), matched, unknown, conflict);
    }

    public static String labelled(String text, String label) {
        String line = lineContaining(text, label + ":");
        return line == null ? null : line.substring(line.indexOf(':') + 1).strip();
    }
    private static String lineContaining(String text, String term) {
        return text.lines().filter(line -> line.contains(term)).findFirst().orElse(null);
    }
    private static String sentenceContaining(String text, String term) {
        return Arrays.stream(text.split("(?<=[.!?。])\\s+|\\n")).filter(s -> s.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))).findFirst().orElse(null);
    }
    private static String quoteFor(String field, String value, String text) {
        if(field.equals("조회 기간")) return Arrays.stream(text.split("\\n|(?<=[.!?。])\\s+"))
                .filter(s -> value.equals(ApplicabilityConditions.current(List.of(s)).get(field))).findFirst().orElse(null);
        if(field.equals("기안자") && value.equals("본인")) return Arrays.stream(text.split("\\n|(?<=[.!?。])\\s+"))
                .filter(s -> s.matches(".*(?:제가|직접|본인).*(?:기안|상신).*" )).findFirst().orElse(null);
        if(field.equals("상신 여부"))return Arrays.stream(text.split("\\n|(?<=[.!?。])\\s+"))
                .filter(s -> s.matches(".*상신(?:했|까지 (?:했|완료)|한(?:\\s|문서)| 완료|을 완료).*" )).findFirst().orElse(null);
        if(field.equals("조회 상태"))return Arrays.stream(text.split("\\n|(?<=[.!?。])\\s+"))
                .filter(s -> (s.contains("조회 상태")||s.contains("상태 필터"))&&s.contains(value)).findFirst().orElse(null);
        if(field.equals("제목 검색어") && value.equals("없음")) return Arrays.stream(text.split("\\n|(?<=[.!?。])\\s+"))
                .filter(s -> s.matches(".*(?:키워드|검색어).*(?:없|비워|입력하지 않|입력 안).*" )).findFirst().orElse(null);
        if(value.equals("없음")||value.equals("타인")||value.equals("본인")||value.equals("완료"))return null;
        return sentenceContaining(text,value);
    }
    private static List<Section> sections(RecommendedCaseResponse item, Map<String,String> approvedParts) {
        List<Section> result = new ArrayList<>();
        String body = item.excerpt();
        if (item.kind().equals("CASE") && !approvedParts.isEmpty()) {
            String[] labels = {"사례 제목", "과거 증상", "실제 조치", "검증 결과"};
            String[] fields = {"title", "problemStatement", "action", "verification"};
            int offset = 0;
            for (int i = 0; i < 4; i++) {
                String part=approvedParts.get(fields[i]);
                if(part==null || part.isBlank())continue;
                int start = body.indexOf(part, offset);
                if(start<0)throw new com.hihops.hops.knowledge.service.KnowledgeEvidenceAssembler.IntegrityException("승인된 사례 필드의 원문 위치를 확인하지 못했습니다");
                result.add(new Section(labels[i], part, start, start + part.length()));
                offset = start + part.length();
            }
        } else if(!item.kind().equals("CASE")) {
            var matcher = java.util.regex.Pattern.compile("(?m)^## (.+)$").matcher(body);
            List<Integer> starts = new ArrayList<>(); List<String> labels = new ArrayList<>();
            while (matcher.find()) { starts.add(matcher.end()); labels.add(matcher.group(1)); }
            for (int i = 0; i < starts.size(); i++) {
                int end = i+1 < starts.size() ? body.lastIndexOf("## ", starts.get(i+1)) : body.length();
                int start = starts.get(i);
                while (start < end && Character.isWhitespace(body.charAt(start))) start++;
                while (end > start && Character.isWhitespace(body.charAt(end-1))) end--;
                if (end > start) result.add(new Section(labels.get(i), body.substring(start, end), start, end));
            }
        }
        return result;
    }
}
