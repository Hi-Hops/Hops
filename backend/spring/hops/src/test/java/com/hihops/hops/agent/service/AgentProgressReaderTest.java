package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.inquiry.domain.Inquiry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentProgressReaderTest {
    private final UUID runId = UUID.randomUUID();
    private final UUID inquiryId = UUID.randomUUID();
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentRun run = mock(AgentRun.class);
    private final Inquiry inquiry = mock(Inquiry.class);
    private final AgentProgressReader reader = new AgentProgressReader(runs);

    @BeforeEach
    void setUp() {
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(run.getId()).thenReturn(runId);
        when(run.getTask()).thenReturn(AgentTask.INQUIRY_ANALYSIS);
        when(run.getRunStatus()).thenReturn(RunStatus.RUNNING);
        when(run.getInputVersion()).thenReturn(2);
        when(run.getInquiry()).thenReturn(inquiry);
        when(inquiry.getId()).thenReturn(inquiryId);
        when(inquiry.getCurrentRun()).thenReturn(run);
        when(inquiry.getVersion()).thenReturn(2);
    }

    private JsonNode event(String role) {
        return JsonMapper.builder().build().readTree("{\"seq\":3,\"payload\":{\"role\":\"" + role
                + "\",\"displaySummary\":\"문의 내용을 정리하고 있어요\"}}");
    }

    @Test
    void 현재_실행의_진행에_공개_ID와_문의_버전을_붙인다() {
        var result = reader.read(runId, event("INQUIRY"));
        assertThat(result).isNotNull();
        assertThat(result.eventId()).isNotNull();
        assertThat(result.inquiryId()).isEqualTo(inquiryId);
        assertThat(result.inquiryVersion()).isEqualTo(2);
        assertThat(result.stepNo()).isEqualTo(3);
    }

    @Test
    void Curator와_알수없는_역할은_직원에게_전달하지_않는다() {
        assertThat(reader.read(runId, event("CURATOR"))).isNull();
        assertThat(reader.read(runId, event("UNKNOWN"))).isNull();
    }

    @Test
    void 이전_버전이나_다른_현재_run과_종료_실행을_버린다() {
        when(inquiry.getVersion()).thenReturn(3);
        assertThat(reader.read(runId, event("INQUIRY"))).isNull();
        when(inquiry.getVersion()).thenReturn(2);
        when(inquiry.getCurrentRun()).thenReturn(mock(AgentRun.class));
        assertThat(reader.read(runId, event("INQUIRY"))).isNull();
        when(inquiry.getCurrentRun()).thenReturn(run);
        when(run.getRunStatus()).thenReturn(RunStatus.SUCCEEDED);
        assertThat(reader.read(runId, event("INQUIRY"))).isNull();
    }
}
