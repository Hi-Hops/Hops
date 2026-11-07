package com.hihops.hops.agent.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.hihops.hops.agent.dto.AiRunRequest;
import com.hihops.hops.agent.dto.AiRunRequest.*;
import com.hihops.hops.inquiry.domain.RequestIntent;
import com.hihops.hops.inquiry.domain.WorkType;
import com.hihops.hops.itsystem.dto.SupportedSystemResponse;
import com.hihops.hops.itsystem.dto.SystemCode;
import com.hihops.hops.ticket.dto.StandardRequestResponse.*;
import com.hihops.hops.ticket.service.TicketKnowledgeReader;
import com.hihops.hops.user.domain.Member;
import com.hihops.hops.global.exception.BusinessException;
import tools.jackson.databind.json.JsonMapper;

class TicketKnowledgeScopeTest {
    final UUID system = UUID.randomUUID();
    final UUID evidenceId = UUID.randomUUID();
    final Member requester = mock(Member.class);
    final TicketKnowledgeReader reader = mock(TicketKnowledgeReader.class);
    final TicketCreationValidator validator = new TicketCreationValidator(reader, JsonMapper.builder().build());
    final RequestDetailsResponse details = new RequestDetailsResponse(null, null, null, null, null, null, null, null, List.of());
    final KnowledgeEvidenceResponse evidence = new KnowledgeEvidenceResponse(evidenceId, UUID.randomUUID(), 1,
            "GUIDE", "가상 근거", "가상 발췌", system, null, "가상 조건", "EMPLOYEE", "SYNTHETIC");

    StandardRequestBody snapshot() {
        return new StandardRequestBody("가상 문제", List.of(), List.of(), List.of(), system, List.of(), details, List.of(evidence));
    }
    AiRunRequest input() {
        // 다른 문제에 이미 저장된 동일 근거가 있어도 현재 문제의 허용 목록은 아니다.
        return new AiRunRequest("hops-system-1", UUID.randomUUID(), "ANALYZE", UUID.randomUUID(), 1, null,
                List.of(), List.of(), List.of(new InputProblem(UUID.randomUUID(), "다른 문제", "OBSERVED_SYMPTOM",
                "INVESTIGATION", system, null, "ANALYZING", 0, List.of(), snapshot(), null)),
                List.of(new SupportedSystemResponse(system, SystemCode.COMMON_LOGIN, "공통 로그인", List.of())),
                new AccessContext(UUID.randomUUID(), "INQUIRY", List.of(system), List.of("EMPLOYEE"), 1),
                List.of(), null, null, null);
    }
    TicketCreationCommand command(TicketCreationCommand.KnowledgeScope scope) {
        return new TicketCreationCommand(1,
                new TicketCreationCommand.Problem("new:target", "가상 문제", RequestIntent.OBSERVED_SYMPTOM,
                        WorkType.INVESTIGATION, system, List.of(), List.of(), List.of(), details),
                new TicketCreationCommand.Proposal("new:target", "가상 제목", WorkType.INVESTIGATION,
                        system, List.of(), snapshot()), List.of(), scope);
    }
    @Test
    void 다른_문제의_근거는_공개자료여도_거절한다() {
        for (var scope : List.of(new TicketCreationCommand.KnowledgeScope("new:target", List.of()),
                new TicketCreationCommand.KnowledgeScope("new:other", List.of(evidenceId)))) {
            assertThatThrownBy(() -> validator.validate(command(scope), input(), requester))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> validator.validate(command(null), input(), requester))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(reader);
    }
    @Test
    void 대상_문제의_근거도_현재_공개_검증을_통과해야_한다() {
        when(reader.visible(anyString(), eq(requester))).thenReturn(List.of(evidence));
        var cmd = command(new TicketCreationCommand.KnowledgeScope("new:target", List.of(evidenceId)));
        validator.validate(cmd, input(), requester);
        when(reader.visible(anyString(), eq(requester))).thenReturn(List.of());
        assertThatThrownBy(() -> validator.validate(cmd, input(), requester)).isInstanceOf(BusinessException.class);
    }
}
