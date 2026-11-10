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

    public static SystemFeatureResponse resolve(List<SystemFeatureResponse> catalog,
            List<EvidenceStatementResponse> facts, Map<UUID, List<String>> sources) {
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
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    public static boolean namesFeature(String quote, String name) {
        if (UNCERTAIN.matcher(quote).find()) return false;
        String term = java.util.Arrays.stream(name.strip().split("\\s+")).map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("\\s*"));
        return Pattern.compile("(?<![가-힣a-zA-Z])" + term + "(?=\\s*(?:화면|기능)|\\s*$)")
                .matcher(quote).find();
    }
}
