package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.Inquiry;

import tools.jackson.databind.json.JsonMapper;

class ImageRunSafetyTest {
    private final ImageInputProvenanceGuard provenance = mock(ImageInputProvenanceGuard.class);
    private final ImageRunSafety safety = new ImageRunSafety(JsonMapper.builder().build(), provenance);

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

    @Test
    void AI_입력은_이미지_출처_전파를_검사한다() {
        var inquiryId = java.util.UUID.randomUUID();
        Inquiry inquiry = mock(Inquiry.class);
        when(inquiry.getId()).thenReturn(inquiryId);
        AgentRun run = run("{\"messages\":[{\"sender\":\"AI\",\"id\":\"" + java.util.UUID.randomUUID() + "\"}]}", null);
        when(run.getInquiry()).thenReturn(inquiry);
        assertThatThrownBy(() -> safety.requireTextOnly(run)).isInstanceOf(BusinessException.class);
        when(provenance.areRetrySafe(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        assertThatCode(() -> safety.requireTextOnly(run)).doesNotThrowAnyException();
    }

    @Test
    void 여러_티켓의_AI_입력은_한번에_출처를_검사한다() {
        Inquiry inquiry = mock(Inquiry.class);
        when(inquiry.getId()).thenReturn(java.util.UUID.randomUUID());
        AgentRun first = run("{\"messages\":[{\"sender\":\"AI\"}]}", null);
        AgentRun second = run("{\"messages\":[{\"sender\":\"AI\"}]}", null);
        when(first.getInquiry()).thenReturn(inquiry);
        when(second.getInquiry()).thenReturn(inquiry);
        when(provenance.areRetrySafe(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        safety.requireTextOnly(List.of(first, second));
        verify(provenance).areRetrySafe(org.mockito.ArgumentMatchers.any());
    }
}
