package com.hihops.hops.global.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 사용자 입력 본문 규칙 (DATA_TYPES 신규 절 UserContent). */
class UserContentValidatorTest {

    private final UserContentValidator validator = new UserContentValidator();

    @Test
    void 앞뒤_공백이_있어도_내용이_있으면_통과한다() {
        assertThat(valid("  결재 문서가 안 열려요\n")).isTrue();
        assertThat(valid("a")).isTrue();
    }

    @Test
    void 계약의_공백만_있으면_거부한다() {
        assertThat(valid("")).isFalse();
        assertThat(valid(" \t\n\u000B\f\r")).isFalse();
        assertThat(valid("\u0085        　﻿")).isFalse();
    }

    @Test
    void 계약_공백이_아닌_보이지_않는_문자는_내용으로_본다() {
        // U+200B(폭 없는 공백)는 계약 공백 집합에 없다
        assertThat(valid("​")).isTrue();
    }

    @Test
    void 길이는_코드_포인트로_센다() {
        String emoji = "😀"; // UTF-16 두 칸, 코드 포인트 하나
        assertThat(valid(emoji.repeat(4000))).isTrue();
        assertThat(valid(emoji.repeat(4001))).isFalse();
        assertThat(valid("가".repeat(4000))).isTrue();
        assertThat(valid("가".repeat(4001))).isFalse();
    }

    @Test
    void 단독_surrogate는_거부한다() {
        assertThat(valid("문의\uD83D")).isFalse();
        assertThat(valid("\uDE00문의")).isFalse();
    }

    @Test
    void DB에_저장할_수_없는_NUL은_거부한다() {
        assertThat(valid("a\u0000b")).isFalse();
        assertThat(valid("\u0000")).isFalse();
    }

    @Test
    void null은_NotNull이_맡도록_통과시킨다() {
        assertThat(valid(null)).isTrue();
    }

    private boolean valid(String value) {
        return validator.isValid(value, null);
    }
}
