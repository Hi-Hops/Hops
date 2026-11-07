package com.hihops.hops.inquiry.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveTextMaskerTest {
    @Test
    void 알려진_형식을_가리고_나머지_문맥은_유지한다() {
        assertThat(SensitiveTextMasker.mask("문의 test@example.com, 010-1234-5678, 900101-1234567 오류"))
                .isEqualTo("문의 [가림], [가림], [가림] 오류");
    }

    @Test
    void 일반_오류_코드와_인접한_긴_숫자를_변경하지_않는다() {
        assertThat(SensitiveTextMasker.mask("오류 코드 123456789012345, 404"))
                .isEqualTo("오류 코드 123456789012345, 404");
    }

    @Test
    void 명시된_비밀값을_가리되_비밀번호에_관한_일반_문장은_보존한다() {
        assertThat(SensitiveTextMasker.mask("비밀번호: test-secret, OTP=123456 비밀번호를 변경했어요"))
                .isEqualTo("비밀번호: *, OTP=* 비밀번호를 변경했어요");
        assertThat(SensitiveTextMasker.mask("비밀번호=x")).isEqualTo("비밀번호=*");
    }
}
