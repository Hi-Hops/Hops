package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.ticket.service.RelatedMessageProjector;

import tools.jackson.databind.json.JsonMapper;

class ImageInputProvenanceGuardTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MessageRepository messages = mock(MessageRepository.class);
    private final RelatedMessageProjector projector = mock(RelatedMessageProjector.class);
    private final ImageInputProvenanceGuard guard = new ImageInputProvenanceGuard(projector, messages);
    private final UUID inquiryId = UUID.randomUUID();
    private final UUID answerId = UUID.randomUUID();

    @Test
    void 이미지_유래_답변은_이미지_없는_새_실행에_넣지_않는다() {
        Message answer = answer(inquiryId);
        when(projector.restricted(List.of(answer))).thenReturn(Set.of(answerId));
        assertUnavailable(() -> guard.requireTextSafe(List.of(answer)));
    }

    @Test
    void 재시도도_같은_출처를_검사하고_일반_텍스트는_허용한다() {
        Message answer = answer(inquiryId);
        when(messages.findAllById(any())).thenReturn(List.of(answer));
        var snapshot = mapper.readTree("{\"messages\":[{\"sender\":\"AI\",\"id\":\"" + answerId + "\"}]}");
        when(projector.restricted(List.of(answer))).thenReturn(Set.of(answerId), Set.of());
        assertUnavailable(() -> guard.requireRetrySafe(snapshot, inquiryId));
        assertThatCode(() -> guard.requireRetrySafe(snapshot, inquiryId)).doesNotThrowAnyException();
    }

    @Test
    void 출처_메시지가_없거나_다른_문의에_있으면_재시도하지_않는다() {
        var snapshot = mapper.readTree("{\"messages\":[{\"sender\":\"AI\",\"id\":\"" + answerId + "\"}]}");
        Message otherInquiryAnswer = answer(UUID.randomUUID());
        when(messages.findAllById(any())).thenReturn(List.of(), List.of(otherInquiryAnswer));
        assertUnavailable(() -> guard.requireRetrySafe(snapshot, inquiryId));
        assertUnavailable(() -> guard.requireRetrySafe(snapshot, inquiryId));
    }

    @Test
    void 이미지가_포함된_재시도는_기존_이미지_실행_안전_경계를_따른다() {
        var snapshot = mapper.readTree("{\"messages\":[{\"sender\":\"USER\",\"imageAttachments\":[{}]}]}");
        assertThatCode(() -> guard.requireRetrySafe(snapshot, inquiryId)).doesNotThrowAnyException();
        verify(messages, never()).findAllById(any());
    }

    @Test
    void 손상된_입력은_이미지_없는_안전한_실행으로_간주하지_않는다() {
        for (String input : List.of("{}", "{\"messages\":null}", "{\"messages\":[null]}",
                "{\"messages\":[{\"sender\":\"AI\",\"id\":\"wrong\"}]}",
                "{\"messages\":[{\"sender\":\"USER\",\"imageAttachments\":null}]}")) {
            assertUnavailable(() -> guard.requireRetrySafe(mapper.readTree(input), inquiryId));
        }
    }

    private Message answer(UUID sourceInquiry) {
        Message answer = mock(Message.class);
        Inquiry inquiry = mock(Inquiry.class);
        when(inquiry.getId()).thenReturn(sourceInquiry);
        when(answer.getInquiry()).thenReturn(inquiry);
        when(answer.getSenderType()).thenReturn(SenderType.AI);
        return answer;
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(BusinessException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }
}
