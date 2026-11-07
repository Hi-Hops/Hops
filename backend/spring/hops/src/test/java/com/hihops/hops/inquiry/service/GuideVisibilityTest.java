package com.hihops.hops.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.service.GuideVisibility.GuideView;

/** 숨김 보기가 안내 부분을 바꾸고, 안내 부분을 찾지 못하면 본문 전체를 바꾸는지(새지 않는 쪽). */
class GuideVisibilityTest {

    private static Message message(String content) {
        Message message = mock(Message.class);
        when(message.getContent()).thenReturn(content);
        return message;
    }

    @Test
    void 보이는_안내는_본문을_그대로_둔다() {
        assertThat(new GuideView("안내", List.of(), false).contentOf(message("문제 1\n안내"))).isEqualTo("문제 1\n안내");
    }

    @Test
    void 숨기는_안내는_안내_부분만_바꾼다() {
        assertThat(new GuideView("안내 문단", List.of(), true).contentOf(message("문제 1\n안내 문단\n\n문제 2\n질문")))
                .isEqualTo("문제 1\n" + GuideVisibility.HIDDEN_NOTICE + "\n\n문제 2\n질문");
    }

    @Test
    void 본문에서_안내를_글자_그대로_찾지_못하면_본문_전체를_바꾼다() {
        assertThat(new GuideView("안내 문단\n둘째 줄", List.of(), true).contentOf(message("안내 문단\r\n둘째 줄")))
                .isEqualTo(GuideVisibility.HIDDEN_NOTICE);
        assertThat(new GuideView("", List.of(), true).contentOf(message("본문"))).isEqualTo(GuideVisibility.HIDDEN_NOTICE);
    }
}
