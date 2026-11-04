package com.hihops.hops.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Java/Python이 같은 키 순서·Unicode 원문으로 계산하는 계약 SHA-256. */
@Component
@RequiredArgsConstructor
public class CaseJson {
    private final JsonMapper mapper;
    public String canonical(JsonNode node) { return mapper.writeValueAsString(sorted(node)); }
    private Object sorted(JsonNode node) {
        if (node.isObject()) {
            var result = new TreeMap<String, Object>();
            node.properties().forEach(entry -> result.put(entry.getKey(), sorted(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = new java.util.ArrayList<Object>();
            node.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return node;
    }
    public String digest(JsonNode node) { return sha256(canonical(node)); }
    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
