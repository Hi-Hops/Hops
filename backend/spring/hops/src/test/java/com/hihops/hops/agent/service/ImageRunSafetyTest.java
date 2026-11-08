package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

class ImageRunSafetyTest {
    private final ImageRunSafety safety = new ImageRunSafety(JsonMapper.builder().build());

    private AgentRun run(String input, String result) {
        AgentRun run = mock(AgentRun.class);
        when(run.getInputSnapshot()).thenReturn(input);
        when(run.getResult()).thenReturn(result);
        return run;
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"messages\":[]}", "{\"messages\":[{}]}",
            "{\"messages\":[{\"imageAttachments\":[]}]}"})
    void 이전_입력과_이미지가_없는_실행은_허용한다(String input) {
        assertThatCode(() -> safety.requireTextOnly(run(input, "{\"imageObservations\":[]}"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"messages\":[{\"imageAttachments\":[{}]}]}",
            "{\"messages\":[{\"imageAttachments\":null}]}", "{\"messages\":null}",
            "{\"messages\":[null]}", "{invalid"})
    void 이미지나_형식이_불명확한_입력은_차단한다(String input) {
        assertThatThrownBy(() -> safety.requireTextOnly(run(input, null))).isInstanceOfSatisfying(
                BusinessException.class, e -> org.assertj.core.api.Assertions.assertThat(e.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"imageObservations\":[{}]}", "{\"imageObservations\":null}", "null"})
    void 이미지_관찰이_있거나_결과_형식이_불명확해도_차단한다(String result) {
        assertThatThrownBy(() -> safety.requireTextOnly(run("{}", result))).isInstanceOf(BusinessException.class);
    }
}
