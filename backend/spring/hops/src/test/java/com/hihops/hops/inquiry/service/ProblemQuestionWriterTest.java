package com.hihops.hops.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** 거부 로그가 가리키는 위치를 DB 없이 확인한다. 거부 사유 로그 전체는 InquiryQuestionFlowTest가 다룬다. */
class ProblemQuestionWriterTest {

    @Test
    void JDK_내부_프레임이_아니라_우리_코드의_첫_프레임을_위치로_쓴다() {
        try {
            UUID.fromString("not-a-uuid");
            throw new AssertionError("UUID 형식 오류가 나야 한다");
        } catch (IllegalArgumentException exception) {
            // 예외는 java.util.UUID 안에서 났지만 위치는 이 프로젝트의 호출 지점이어야 한다
            assertThat(exception.getStackTrace()[0].getClassName()).startsWith("java.");
            assertThat(ProblemQuestionWriter.appFrame(exception)).startsWith("ProblemQuestionWriterTest.");
        }
    }

    @Test
    void 프로젝트_프레임이_없으면_unknown이다() {
        var exception = new RuntimeException();
        exception.setStackTrace(new StackTraceElement[] {new StackTraceElement("java.util.UUID", "fromString", "UUID.java", 1)});

        assertThat(ProblemQuestionWriter.appFrame(exception)).isEqualTo("unknown");
    }
}
