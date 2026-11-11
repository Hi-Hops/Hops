package com.hihops.hops.itsystem.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.hihops.hops.itsystem.dto.SystemFeatureResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.EvidenceStatementResponse;

/** 같은 문제의 USER 원문에 명시된 화면·기능명만 카탈로그와 대조한다. 검색 후보는 쓰지 않는다. */
public final class ExplicitFeatureEvidence {
    private ExplicitFeatureEvidence() {}
    private static final Pattern LABEL = Pattern.compile("^(?:하려던 업무·화면|기능)\\s*[:：]\\s*(.+)$");
    private static final Pattern UNCERTAIN = Pattern.compile("아니|않|모르|미확인|불명|추정|예정|인가|맞나|인지|들었|일\\s*수|\\?");
    private static final Pattern QUOTED_CONTEXT = Pattern.compile("예시|인용|매뉴얼|안내|설명|[‘’'\"“”]");

    public static SystemFeatureResponse resolve(List<SystemFeatureResponse> catalog,
            List<EvidenceStatementResponse> facts, Map<UUID, List<String>> sources) {
        return resolve(catalog, facts, sources, null);
    }

    public static SystemFeatureResponse resolve(List<SystemFeatureResponse> catalog,
            List<EvidenceStatementResponse> facts, Map<UUID, List<String>> sources, String systemName) {
        var matches = new java.util.LinkedHashSet<SystemFeatureResponse>();
        for (var fact : facts) {
            var field = LABEL.matcher(fact.text());
            if (!field.matches()) continue;
            String quote = field.group(1).strip();
            boolean grounded = fact.sourceMessageIds().stream().flatMap(id -> sources.getOrDefault(id, List.of()).stream())
                    .anyMatch(source -> source.contains(quote));
            if (!grounded) continue;
            if (UNCERTAIN.matcher(quote).find()) return null;
            for (var feature : catalog) if (namesFeature(quote, feature.name())) matches.add(feature);
        }
        // 새 답변이 기존 화면 필드를 갱신하지 않아도, 같은 문제의 검증된 USER 구간에
        // "문서 조회 화면입니다"처럼 명확하게 적힌 진술은 추출 모델과 독립적으로 대조한다.
        var referenced = facts.stream().flatMap(f -> f.sourceMessageIds().stream()).collect(java.util.stream.Collectors.toSet());
        String prefix = systemName == null ? "" : "(?:" + term(systemName) + "\\s+)?";
        for (var source : sources.entrySet()) {
            if (!referenced.contains(source.getKey())) continue;
            for (var text : source.getValue()) for (String sentence : text.split("(?<=[.!?。])\\s*|\\n")) {
                for (var feature : catalog) {
                    String name = term(feature.name());
                    if (Pattern.compile(name + "\\s*(?:화면|기능)(?:이|가|은|는)?\\s*(?:아니|아닌|아닙|인가|인지|일\\s*수)")
                            .matcher(sentence).find()) return null;
                    if (!UNCERTAIN.matcher(sentence).find() && !QUOTED_CONTEXT.matcher(sentence).find() && Pattern.compile("^" + prefix + name
                            + "\\s*(?:화면|기능)(?:의\\s*[^.!?。\\n]{1,60})?(?:입니다|이에요|예요)[.!。]?$")
                            .matcher(sentence.strip()).matches()) matches.add(feature);
                }
            }
        }
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    public static boolean namesFeature(String quote, String name) {
        if (UNCERTAIN.matcher(quote).find()) return false;
        return Pattern.compile("(?<![가-힣a-zA-Z])" + term(name) + "(?=\\s*(?:화면|기능)|\\s*$)")
                .matcher(quote).find();
    }

    private static String term(String name) {
        return java.util.Arrays.stream(name.strip().split("\\s+")).map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("\\s*"));
    }
}
