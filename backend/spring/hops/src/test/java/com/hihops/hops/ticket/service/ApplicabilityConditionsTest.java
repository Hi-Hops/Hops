package com.hihops.hops.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApplicabilityConditionsTest {
    @Test void 조회_상태의_연결형_서술도_원문에서_대조한다() {
        for (String text : List.of("조회 상태는 승인이고, 조회 기간에는 오늘 기안일이 포함됩니다.",
                "조회 상태는 승인이며 제목 검색어는 없습니다."))
            assertThat(ApplicabilityConditions.current(List.of(text))).as(text).containsEntry("조회 상태", "승인");
        for (String text : List.of("조회 상태는 승인이고 전체는 아닙니다.", "조회 상태는 승인이고 반려일 수도 있어요?"))
            assertThat(ApplicabilityConditions.current(List.of(text))).as(text).doesNotContainKey("조회 상태");
    }

    @Test void 자연어로_확인한_기안일_포함과_제외를_구분한다() {
        assertThat(ApplicabilityConditions.current(List.of("조회 기간에는 오늘 기안일이 포함됩니다.")))
                .containsEntry("조회 기간", "기안일 포함");
        assertThat(ApplicabilityConditions.current(List.of("기안일이 조회 기간 안에 있어요.")))
                .containsEntry("조회 기간", "기안일 포함");
        for (String text : List.of("기안일이 조회 기간 밖이에요.", "조회 기간에 기안일이 포함되지 않아요."))
            assertThat(ApplicabilityConditions.current(List.of(text))).containsEntry("조회 기간", "기안일 미포함");
    }

    @Test void 질문_추정_조건문_충돌과_명시적_미확인은_충족으로_판정하지_않는다() {
        for (String text : List.of("조회 기간에 기안일이 포함돼요?", "조회 기간에 기안일이 포함되는지 모르겠어요.",
                "조회 기간에 기안일이 포함되도록 해 주세요.", "조회 기간에 기안일이 포함된 경우 다시 조회하세요.",
                "조회 기간은 오늘 기안일을 포함하도록 설정해야 해요.",
                "조회 기간은 최근 3개월이에요."))
            assertThat(ApplicabilityConditions.current(List.of(text))).as(text).doesNotContainKey("조회 기간");
        assertThat(ApplicabilityConditions.current(List.of("조회 기간에 기안일이 포함됩니다.", "기안일이 조회 기간 밖이에요.")))
                .doesNotContainKey("조회 기간");
        assertThat(ApplicabilityConditions.current(List.of("조회 기간: 미확인", "조회 기간에 기안일이 포함됩니다.")))
                .doesNotContainKey("조회 기간");
        assertThat(ApplicabilityConditions.current(List.of("제목은 모르겠어요. 조회 기간에 기안일이 포함됩니다.")))
                .containsEntry("조회 기간", "기안일 포함");
    }
}
