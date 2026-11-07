package com.hihops.hops.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 안내문이 승인 본문의 문단으로만 만들어졌는지 확인하는 규칙. AI의 split_blocks와 같은 문단 경계를 쓴다. */
class GuideTextTest {

    private static final String CONTENT = "## 목적\n문서 조회를 안내한다.\n\n## 절차\n1. 메뉴를 연다.\n2. 기간을 고른다.\n\n\n## 참고\n보존 기간이 지나면 보이지 않는다.";
    private static final List<String> TITLES = List.of("문서 조회 안내");

    @Test
    void 본문_전체와_고른_문단과_출처_줄은_통과한다() {
        assertThat(GuideText.fromApproved(CONTENT, TITLES, List.of(CONTENT))).isTrue();
        assertThat(GuideText.fromApproved(CONTENT + "\n\n참고 자료: 문서 조회 안내", TITLES, List.of(CONTENT))).isTrue();
        assertThat(GuideText.fromApproved("## 절차\n1. 메뉴를 연다.\n2. 기간을 고른다.\n\n## 참고\n보존 기간이 지나면 보이지 않는다."
                + "\n\n참고 자료: 문서 조회 안내", TITLES, List.of(CONTENT))).isTrue();
        assertThat(GuideText.fromApproved(CONTENT.replace("\n", "\r\n"), TITLES, List.of(CONTENT))).isTrue();
    }

    @Test
    void 본문에_없는_문장이나_섞인_문단은_거부한다() {
        assertThat(GuideText.fromApproved("캐시를 지우면 해결돼요.", TITLES, List.of(CONTENT))).isFalse();
        // 승인 문단 안의 문장을 바꾸면 그 문단은 승인 문단과 같지 않다
        assertThat(GuideText.fromApproved("## 절차\n1. 설정을 연다.", TITLES, List.of(CONTENT))).isFalse();
        // 승인 문단의 일부만 떼어 내면 뜻이 바뀔 수 있어 문단 전체가 같아야 한다
        assertThat(GuideText.fromApproved("## 참고\n보존 기간이 지나면", TITLES, List.of(CONTENT))).isFalse();
        assertThat(GuideText.fromApproved("1. 메뉴를 연다.", TITLES, List.of(CONTENT))).isFalse();
        // 서로 다른 문단을 한 문단으로 붙이면 본문에 없는 조합이다
        assertThat(GuideText.fromApproved("문서 조회를 안내한다.\n## 절차", TITLES, List.of(CONTENT))).isFalse();
        // 인용하지 않은 제목의 출처 줄은 본문으로 보고 검사한다
        assertThat(GuideText.fromApproved("## 절차\n1. 메뉴를 연다.\n2. 기간을 고른다.\n\n참고 자료: 다른 문서", TITLES,
                List.of(CONTENT))).isFalse();
        assertThat(GuideText.fromApproved("", TITLES, List.of(CONTENT))).isFalse();
        assertThat(GuideText.fromApproved("참고 자료: 문서 조회 안내", TITLES, List.of(CONTENT))).isFalse();
    }

    @Test
    void 문단은_빈_줄로_나누고_앞뒤_공백을_뗀다() {
        assertThat(GuideText.blocks("  첫 줄\n둘째 줄  \n \n\n셋째\r\n")).containsExactly("첫 줄\n둘째 줄", "셋째");
    }
}
