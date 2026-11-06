package com.hihops.hops.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.service.AgentResultRejectedException;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.realtime.dto.MessageStoredEvent;
import com.hihops.hops.user.domain.Member;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 상담 결과 처리기의 판단을 DB 없이 확인한다. 실제 저장과 거래는 InquiryAnswerFlowTest가 다룬다. */
class InquiryAnswerHandlerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID runId = UUID.randomUUID();
    private final UUID inquiryId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID answerMessageId = UUID.randomUUID();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private InquiryRepository inquiries;
    private MessageRepository messages;
    private AiAnswerMessageWriter writer;
    private InquiryAnswerHandler handler;
    private Inquiry inquiry;
    private Member member;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        inquiries = mock(InquiryRepository.class);
        messages = mock(MessageRepository.class);
        writer = mock(AiAnswerMessageWriter.class);
        handler = new InquiryAnswerHandler(inquiries, messages, writer, mapper, events, mock(GuideResultWriter.class),
                mock(com.hihops.hops.inquiry.repository.InquiryProblemRepository.class));

        member = mock(Member.class);
        when(member.getId()).thenReturn(memberId);
        when(member.getPolicyRevision()).thenReturn(3);
        inquiry = mock(Inquiry.class);
        when(inquiry.getId()).thenReturn(inquiryId);
        when(inquiry.getRequester()).thenReturn(member);
        when(inquiry.getVersion()).thenReturn(1);
        run = mock(AgentRun.class);
        when(run.getId()).thenReturn(runId);
        when(run.getInquiry()).thenReturn(inquiry);
        when(run.getInputVersion()).thenReturn(1);
        when(run.getTask()).thenReturn(AgentTask.INQUIRY_ANALYSIS);
        when(inquiry.getCurrentRun()).thenReturn(run);
        when(inquiries.findByIdAndDeletedAtIsNull(inquiryId)).thenReturn(Optional.of(inquiry));
        when(messages.existsById(answerMessageId)).thenReturn(false);
    }

    private JsonNode input() {
        return mapper.readTree("{\"answerMessageId\":\"" + answerMessageId + "\",\"accessContext\":{\"actorId\":\""
                + memberId + "\",\"policyRevision\":3}}");
    }

    private JsonNode result(String extraFields) {
        return mapper.readTree("{\"answer\":\"전자결재 문서 조회에서 작성 기간을 확인해 보세요.\"" + extraFields + "}");
    }

    @Test
    void 처리기는_상담_분석_작업을_맡는다() {
        assertThat(handler.task()).isEqualTo(AgentTask.INQUIRY_ANALYSIS);
    }

    @Test
    void 접수_때와_같은_문의자와_권한_세대면_접근을_허용한다() {
        handler.verifyAccess(run, input());
    }

    @Test
    void 문의자가_다르면_접근을_거부한다() {
        JsonNode other = mapper.readTree("{\"accessContext\":{\"actorId\":\"" + UUID.randomUUID() + "\",\"policyRevision\":3}}");

        assertThatThrownBy(() -> handler.verifyAccess(run, other)).isInstanceOfSatisfying(
                AgentResultRejectedException.class, e -> assertThat(e.code()).isEqualTo("AUTH_CONTEXT_INVALID"));
    }

    @Test
    void 권한_세대가_바뀌었으면_접근을_거부한다() {
        when(member.getPolicyRevision()).thenReturn(4);

        assertThatThrownBy(() -> handler.verifyAccess(run, input())).isInstanceOfSatisfying(
                AgentResultRejectedException.class, e -> assertThat(e.code()).isEqualTo("AUTH_CONTEXT_INVALID"));
    }

    @Test
    void 삭제된_문의자나_문의는_접근을_거부한다() {
        when(member.isDeleted()).thenReturn(true);
        assertThatThrownBy(() -> handler.verifyAccess(run, input())).isInstanceOf(AgentResultRejectedException.class);

        when(member.isDeleted()).thenReturn(false);
        when(inquiry.isDeleted()).thenReturn(true);
        assertThatThrownBy(() -> handler.verifyAccess(run, input())).isInstanceOf(AgentResultRejectedException.class);
    }

    @Test
    void 정상_결과는_예약한_ID로_답변을_저장한다() {
        String validation = handler.apply(run, input(), result(""));

        verify(writer).insert(inquiryId, answerMessageId, runId, "전자결재 문서 조회에서 작성 기간을 확인해 보세요.");
        assertThat(mapper.readTree(validation).path("applied").get(0).asString()).isEqualTo("answer");
        assertThat(mapper.readTree(validation).path("unapplied")).isEmpty();
        // 저장한 AI 답변을 문의 구독에 알린다 (AI 메시지라 requestId는 없다)
        verify(events).publishEvent(argThat((Object event) -> event instanceof MessageStoredEvent stored
                && stored.inquiryId().equals(inquiryId) && stored.messageId().equals(answerMessageId)
                && stored.inquiryVersion() == 1 && stored.requestId() == null));
    }

    @Test
    void 추가_응답_표시가_boolean이_아니면_답변을_저장하지_않는다() {
        for (String invalid : new String[] {"null", "\"true\"", "1"}) {
            assertThatThrownBy(() -> handler.apply(run, input(), result(",\"requiresFollowUp\":" + invalid)))
                    .isInstanceOfSatisfying(AgentResultRejectedException.class,
                            e -> assertThat(e.code()).isEqualTo("INVALID_OUTPUT"));
        }
        verify(writer, never()).insert(any(), any(), any(), anyString());
    }

    @Test
    void 아직_저장하지_않는_AI_제안은_unapplied에_남기고_실패시키지_않는다() {
        String extra = ",\"questions\":[{\"text\":\"q\"}],\"ticketProposals\":[{\"title\":\"t\"}],\"problemProposals\":[],"
                + "\"guide\":null,\"caseProposal\":null,\"evidence\":[]";

        String validation = handler.apply(run, input(), result(extra));

        JsonNode unapplied = mapper.readTree(validation).path("unapplied");
        assertThat(unapplied).extracting(JsonNode::asString).containsExactlyInAnyOrder("questions", "ticketProposals");
        verify(writer).insert(any(), any(), any(), anyString());
    }

    @Test
    void 결과를_쓰기_직전에_권한_세대가_바뀌었으면_답변을_저장하지_않는다() {
        // 접수 때(policyRevision=3)는 유효했지만 AI가 답하는 동안 바뀌었다
        when(member.getPolicyRevision()).thenReturn(4);

        assertThatThrownBy(() -> handler.apply(run, input(), result(""))).isInstanceOfSatisfying(
                AgentResultRejectedException.class, e -> assertThat(e.code()).isEqualTo("AUTH_CONTEXT_INVALID"));
        verify(writer, never()).insert(any(), any(), any(), anyString());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void 결과를_쓰기_직전에_문의자가_삭제됐으면_답변을_저장하지_않는다() {
        when(member.isDeleted()).thenReturn(true);

        assertThatThrownBy(() -> handler.apply(run, input(), result(""))).isInstanceOfSatisfying(
                AgentResultRejectedException.class, e -> assertThat(e.code()).isEqualTo("AUTH_CONTEXT_INVALID"));
        verify(writer, never()).insert(any(), any(), any(), anyString());
    }

    @Test
    void 늦은_결과와_권한_변경이_겹치면_늦은_결과로_먼저_본다() {
        AgentRun newer = mock(AgentRun.class);
        when(newer.getId()).thenReturn(UUID.randomUUID());
        when(inquiry.getCurrentRun()).thenReturn(newer);
        when(member.getPolicyRevision()).thenReturn(4);

        assertThatThrownBy(() -> handler.apply(run, input(), result(""))).isInstanceOfSatisfying(
                AgentResultRejectedException.class, e -> assertThat(e.code()).isEqualTo("SUPERSEDED"));
    }

    @Test
    void 이_실행이_현재_실행이_아니면_늦은_결과로_보고_저장하지_않는다() {
        AgentRun newer = mock(AgentRun.class);
        when(newer.getId()).thenReturn(UUID.randomUUID());
        when(inquiry.getCurrentRun()).thenReturn(newer);

        assertThatThrownBy(() -> handler.apply(run, input(), result(""))).isInstanceOfSatisfying(
                AgentResultRejectedException.class, e -> assertThat(e.code()).isEqualTo("SUPERSEDED"));
        verify(writer, never()).insert(any(), any(), any(), anyString());
    }

    @Test
    void 현재_실행이_없으면_늦은_결과로_본다() {
        when(inquiry.getCurrentRun()).thenReturn(null);

        assertThatThrownBy(() -> handler.apply(run, input(), result(""))).isInstanceOfSatisfying(
                AgentResultRejectedException.class, e -> assertThat(e.code()).isEqualTo("SUPERSEDED"));
    }

    @Test
    void 문의_입력_버전이_접수_때와_다르면_늦은_결과로_본다() {
        when(inquiry.getVersion()).thenReturn(2);

        assertThatThrownBy(() -> handler.apply(run, input(), result(""))).isInstanceOfSatisfying(
                AgentResultRejectedException.class, e -> assertThat(e.code()).isEqualTo("SUPERSEDED"));
        verify(writer, never()).insert(any(), any(), any(), anyString());
    }

    @Test
    void 문의가_없거나_삭제됐으면_늦은_결과로_본다() {
        when(inquiries.findByIdAndDeletedAtIsNull(inquiryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.apply(run, input(), result(""))).isInstanceOfSatisfying(
                AgentResultRejectedException.class, e -> assertThat(e.code()).isEqualTo("SUPERSEDED"));
    }

    @Test
    void 답변이_없거나_비었거나_문자열이_아니면_출력_오류다() {
        for (String body : new String[] { "{}", "{\"answer\":null}", "{\"answer\":\"   \"}", "{\"answer\":123}" }) {
            assertThatThrownBy(() -> handler.apply(run, input(), mapper.readTree(body))).isInstanceOfSatisfying(
                    AgentResultRejectedException.class, e -> assertThat(e.code()).isEqualTo("INVALID_OUTPUT"));
        }
        verify(writer, never()).insert(any(), any(), any(), anyString());
    }

    @Test
    void 답변이_계약_길이_4000자를_넘으면_출력_오류다() {
        JsonNode tooLong = mapper.readTree("{\"answer\":\"" + "가".repeat(4_001) + "\"}");
        JsonNode atLimit = mapper.readTree("{\"answer\":\"" + "가".repeat(4_000) + "\"}");

        assertThatThrownBy(() -> handler.apply(run, input(), tooLong)).isInstanceOf(AgentResultRejectedException.class);
        handler.apply(run, input(), atLimit);
        verify(writer).insert(any(), any(), any(), anyString());
    }

    @Test
    void 예약한_답변_ID가_이미_쓰였으면_중복_저장하지_않는다() {
        when(messages.existsById(answerMessageId)).thenReturn(true);

        assertThatThrownBy(() -> handler.apply(run, input(), result(""))).isInstanceOfSatisfying(
                AgentResultRejectedException.class, e -> assertThat(e.code()).isEqualTo("INVALID_OUTPUT"));
        verify(writer, never()).insert(any(), any(), any(), anyString());
    }
}
