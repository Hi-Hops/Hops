package com.hihops.hops.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.hihops.hops.agent.service.AgentResultRejectedException;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.MessageStatus;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.repository.MessageRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 사실이 인용한 사용자 메시지를 문제에 잇는 규칙을 DB 없이 확인한다. 실제 저장은 InquiryQuestionFlowTest가 다룬다. */
class MessageProblemLinkerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID inquiryId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();
    private final UUID problemId = UUID.randomUUID();
    private final MessageRepository messages = mock(MessageRepository.class);
    private final MessageProblemLinker linker = new MessageProblemLinker(messages, mapper);
    private Inquiry inquiry;
    private Message message;

    @BeforeEach
    void setUp() {
        inquiry = mock(Inquiry.class);
        when(inquiry.getId()).thenReturn(inquiryId);
        message = mock(Message.class);
        when(message.getInquiry()).thenReturn(inquiry);
        when(message.getSenderType()).thenReturn(SenderType.USER);
        when(message.getMessageStatus()).thenReturn(MessageStatus.COMPLETED);
        when(message.getRevision()).thenReturn(2);
        when(message.getContent()).thenReturn("결재 문서가 안 열려요");
        when(messages.findById(messageId)).thenReturn(Optional.of(message));
    }

    private JsonNode linked() {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(message).updatePayload(captor.capture());
        return mapper.readTree(captor.getValue()).path("problemLinks");
    }

    @Test
    void 연결이_없으면_메시지_전체를_현재_차수로_잇는다() {
        when(message.getPayload()).thenReturn("{\"problemLinks\":[]}");

        linker.link(inquiry, messageId, problemId);

        JsonNode links = linked();
        assertThat(links.size()).isEqualTo(1);
        assertThat(links.get(0).path("problemId").asString()).isEqualTo(problemId.toString());
        assertThat(links.get(0).path("revision").asInt()).isEqualTo(2);
        assertThat(links.get(0).path("spans").get(0).path("end").asInt()).isEqualTo("결재 문서가 안 열려요".length());
    }

    @Test
    void 같은_차수의_연결이_이미_있으면_다시_잇지_않는다() {
        when(message.getPayload()).thenReturn(
                "{\"problemLinks\":[{\"problemId\":\"" + problemId + "\",\"revision\":2,\"spans\":[{\"start\":0,\"end\":3}]}]}");

        linker.link(inquiry, messageId, problemId);

        verify(message, never()).updatePayload(anyString());
    }

    @Test
    void 차수가_없는_예전_연결도_같은_연결로_본다() {
        // MessagePayload.problemIds·TicketCreationService.linkMessages와 같은 판정이다
        when(message.getPayload()).thenReturn(
                "{\"problemLinks\":[{\"problemId\":\"" + problemId + "\",\"spans\":[{\"start\":0,\"end\":3}]}]}");

        linker.link(inquiry, messageId, problemId);

        verify(message, never()).updatePayload(anyString());
    }

    @Test
    void 다른_차수의_연결만_있으면_현재_차수를_더한다() {
        when(message.getPayload()).thenReturn(
                "{\"problemLinks\":[{\"problemId\":\"" + problemId + "\",\"revision\":1,\"spans\":[{\"start\":0,\"end\":3}]}]}");

        linker.link(inquiry, messageId, problemId);

        assertThat(linked().size()).isEqualTo(2);
    }

    @Test
    void 사용자_메시지가_아니거나_다른_문의의_메시지는_거부한다() {
        when(message.getSenderType()).thenReturn(SenderType.AI);

        assertThatThrownBy(() -> linker.link(inquiry, messageId, problemId)).isInstanceOfSatisfying(
                AgentResultRejectedException.class, e -> assertThat(e.code()).isEqualTo("INVALID_OUTPUT"));
        verify(message, never()).updatePayload(anyString());
    }
}
