package com.hihops.hops.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 승인 사례 스냅샷의 정규 JSON과 SHA-256. 승인 거래(CaseApprovalWriter)가 digest를 만들 때와
 * 지식 검색이 저장된 digest를 다시 계산해 대조할 때 같은 규칙을 쓰려고 한 곳에 둔다.
 * 규칙: 키 오름차순, 공백 없음, Unicode는 escape하지 않음, 배열 순서 유지.
 */
final class CanonicalJson {
    private CanonicalJson() {
    }

    static String canonical(JsonMapper mapper, JsonNode node) {
        return mapper.writeValueAsString(sorted(mapper, node));
    }

    /** 사례 승인 때 content_digest의 대상이 되는 스냅샷: {approvedCase, caseId, caseVersion}. */
    static String caseSnapshotDigest(JsonMapper mapper, String caseId, int caseVersion, JsonNode approvedCase) {
        ObjectNode subject = mapper.createObjectNode();
        subject.put("caseId", caseId).put("caseVersion", caseVersion);
        subject.set("approvedCase", approvedCase);
        return sha256(canonical(mapper, subject));
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256은 모든 JDK가 제공한다", e);
        }
    }

    private static JsonNode sorted(JsonMapper mapper, JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            node.propertyNames().stream().sorted(Comparator.naturalOrder())
                    .forEach(name -> result.set(name, sorted(mapper, node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(item -> result.add(sorted(mapper, item)));
            return result;
        }
        return node;
    }
}
