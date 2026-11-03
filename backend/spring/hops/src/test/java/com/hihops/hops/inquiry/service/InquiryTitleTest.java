package com.hihops.hops.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 첫 입력으로 만드는 문의 제목 ({@link InquiryCommandService#titleOf}). */
class InquiryTitleTest {

    @Test
    void 공백과_줄바꿈을_한_칸으로_줄이고_앞뒤를_뗀다() {
        assertThat(InquiryCommandService.titleOf("  결재 문서가\n\n안　열려요  ")).isEqualTo("결재 문서가 안 열려요");
    }

    @Test
    void 이백_코드_포인트까지만_쓴다() {
        assertThat(InquiryCommandService.titleOf("가".repeat(250))).hasSize(200);
        String emoji = "😀";
        String title = InquiryCommandService.titleOf(emoji.repeat(250));
        assertThat(title.codePointCount(0, title.length())).isEqualTo(200);
    }

    @Test
    void 경계에서_끝나는_공백은_붙이지_않는다() {
        String title = InquiryCommandService.titleOf("가".repeat(200) + " 나");
        assertThat(title).isEqualTo("가".repeat(200));
    }
}
