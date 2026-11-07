package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.hihops.hops.relation.service.RelationLinkCandidateReader;

/**
 * Curator 입력·결과의 상한 값이 에이전트 계약 문서와 Spring 코드에서 같은지 확인한다. AI도 같은 블록을 자기 코드와 대조한다
 * (ai/tests/unit/test_contract_limits.py). 한쪽만 값을 바꾸면 AI는 입력 검증에서, Spring은 결과 적용에서 전체를 거부하기 때문이다.
 * DB나 Spring 컨텍스트가 필요 없다.
 */
class CurationLimitsContractTest {
    private static final Path CONTRACT = Path.of("../../../contracts/agents/README.md");

    private Map<String, Integer> documented() throws IOException {
        assertThat(CONTRACT).as("계약 문서 위치(backend/spring/hops 기준)").exists();
        String text = Files.readString(CONTRACT, StandardCharsets.UTF_8);
        var block = Pattern.compile("```curator-limits\n(.*?)```", Pattern.DOTALL).matcher(text);
        assertThat(block.find()).as("curator-limits 블록").isTrue();
        Map<String, Integer> values = new HashMap<>();
        for (String line : block.group(1).strip().split("\n")) {
            var parts = line.split("=");
            values.put(parts[0], Integer.parseInt(parts[1]));
        }
        return values;
    }

    @Test
    void 입력에_싣는_맥락_상한은_계약_문서와_같다() throws IOException {
        var limits = documented();

        assertThat(CurationInputFactory.MAX_AI_MESSAGES).isEqualTo(limits.get("aiMessages"));
        assertThat(CurationInputFactory.MAX_RELATED_RECORDS).isEqualTo(limits.get("relatedRecords"));
        assertThat(CurationInputFactory.MAX_RESOLUTION_HISTORY).isEqualTo(limits.get("resolutionHistory"));
        assertThat(CurationInputFactory.MAX_CONSULTED_KNOWLEDGE).isEqualTo(limits.get("consultedKnowledge"));
        assertThat(CurationInputFactory.MAX_RECORD_TEXT).isEqualTo(limits.get("recordText"));
    }

    @Test
    void 연결_후보_상한은_계약_문서와_같다() throws IOException {
        var limits = documented();

        assertThat(RelationLinkCandidateReader.MAX_SYMPTOMS).isEqualTo(limits.get("symptomCandidates"));
        assertThat(RelationLinkCandidateReader.MAX_CHECK_ITEMS).isEqualTo(limits.get("checkCandidates"));
    }

    @Test
    void 결과_적용의_관계별_연결_상한은_계약_문서와_같다() throws IOException {
        var limits = documented();

        assertThat(CurationResultHandler.MAX_LINKS).containsEntry("APPLIES_TO", limits.get("maxApplies"))
                .containsEntry("ADDRESSES", limits.get("maxAddresses"))
                .containsEntry("USES_CHECK", limits.get("maxUsesCheck"))
                .hasSize(3);
    }
}
