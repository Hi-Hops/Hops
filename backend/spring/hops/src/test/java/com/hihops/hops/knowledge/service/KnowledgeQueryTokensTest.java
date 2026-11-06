package com.hihops.hops.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class KnowledgeQueryTokensTest {

    @Test
    void 조사를_떼고_불용어와_한_글자를_뺀다() {
        assertThat(KnowledgeQueryTokens.of("전자결재에서 지난 문서를 찾는 방법을 어떻게 알려주세요"))
                .containsExactly("전자결재", "지난", "문서", "찾는", "방법", "알려주세요");
    }

    @Test
    void 어간이_두_글자_미만이_되면_조사를_떼지_않는다() {
        assertThat(KnowledgeQueryTokens.of("속도 온도 권한이")).containsExactly("속도", "온도", "권한");
    }

    @Test
    void 중복을_없애고_첫_등장_순서를_유지한다() {
        assertThat(KnowledgeQueryTokens.of("문서 로그인 문서를 로그인")).containsExactly("문서", "로그인");
    }

    @Test
    void 영문은_소문자로_숫자는_그대로_두고_특수문자로_나눈다() {
        assertThat(KnowledgeQueryTokens.of("SSO-Login 100%_test")).containsExactly("sso", "login", "100", "test");
    }

    @Test
    void LIKE_특수문자는_토큰에_남지_않는다() {
        for (String token : KnowledgeQueryTokens.of("%_\\ 50% 문서_조회 a\\b")) {
            assertThat(token).matches("[\\p{L}\\p{N}]+");
        }
    }

    @Test
    void 토큰은_최대_20개다() {
        String query = String.join(" ", IntStream.range(0, 50).mapToObj(i -> "토큰" + i).toList());
        assertThat(KnowledgeQueryTokens.of(query)).hasSize(KnowledgeQueryTokens.MAX_TOKENS);
    }

    @Test
    void 검색할_단어가_없으면_빈_목록이다() {
        assertThat(KnowledgeQueryTokens.of("어떻게 하나요 ? !")).isEmpty();
    }
}
