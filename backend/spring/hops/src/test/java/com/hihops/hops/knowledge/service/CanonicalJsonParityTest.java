package com.hihops.hops.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI(ai/src/hops_ai/services/indexing/digest.py)와 같은 바이트로 직렬화하는지 고정 값으로 확인한다.
 * 같은 기대값을 AI 테스트(test_index_digest.py)도 검사한다. 한쪽 규칙이 바뀌면 두 테스트가 함께 깨진다.
 * 제어 문자(\u0001)는 Jackson이 대문자 16진수로 escape해 Python 기본 json.dumps와 다르다.
 */
class CanonicalJsonParityTest {
    static final String CASE_ID = "11111111-1111-4111-8111-111111111111";
    private final JsonMapper mapper = JsonMapper.builder().build();

    private ObjectNode approvedCase() {
        ObjectNode body = mapper.createObjectNode();
        body.put("title", "상신 문서 \"승인\" 필터");
        body.put("action", "줄1\n줄2\t탭");
        body.putArray("limitations").add("제어\u0001문자").add("역슬래시\\끝");
        body.putNull("appliesToSystemVersion");
        body.put("caseType", "RESOLUTION");
        body.putArray("entityLinks").addObject().put("relation", "ADDRESSES").put("targetId", "s-1")
                .putArray("evidenceIds");
        return body;
    }

    @Test
    void 정규_JSON과_사례_digest가_AI와_같다() {
        assertThat(CanonicalJson.canonical(mapper, approvedCase())).isEqualTo(
                "{\"action\":\"줄1\\n줄2\\t탭\",\"appliesToSystemVersion\":null,\"caseType\":\"RESOLUTION\","
                        + "\"entityLinks\":[{\"evidenceIds\":[],\"relation\":\"ADDRESSES\",\"targetId\":\"s-1\"}],"
                        + "\"limitations\":[\"제어\\u0001문자\",\"역슬래시\\\\끝\"],\"title\":\"상신 문서 \\\"승인\\\" 필터\"}");
        assertThat(CanonicalJson.caseSnapshotDigest(mapper, CASE_ID, 1, approvedCase()))
                .isEqualTo("ba5d2807d0013a6145b3557d970f601439ffea26510a4a135d21c17432f231b7");
    }

    @Test
    void 저장소별_요청_digest가_AI와_같다() {
        ObjectNode subject = mapper.createObjectNode();
        subject.put("caseId", CASE_ID).put("caseVersion", 1).put("store", "VECTOR");
        subject.set("approvedCase", approvedCase());

        assertThat(CanonicalJson.sha256(CanonicalJson.canonical(mapper, subject)))
                .isEqualTo("d03dadeca08c89a35d9593bc78a445f7230ddf523137a704ef14b4caeb2c672f");
    }
}
