package com.hihops.hops.agent.service;

import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.agent.repository.AgentRunRepository;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.realtime.dto.AgentProgressEvent;

import tools.jackson.databind.JsonNode;

/** 이전 입력·종료 실행·Curator 내부 진행을 문의자 채팅으로 전달하지 않는다. */
@Component
public class AgentProgressReader {
    private static final Set<String> ROLES = Set.of("SUPERVISOR", "INQUIRY", "KNOWLEDGE", "TICKET");
    private final AgentRunRepository runs;

    public AgentProgressReader(AgentRunRepository runs) {
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public @Nullable AgentProgressEvent read(UUID runId, JsonNode event) {
        var run = runs.findById(runId).orElse(null);
        if (run == null || run.getTask() != AgentTask.INQUIRY_ANALYSIS
                || run.getRunStatus() != RunStatus.RUNNING) return null;
        Inquiry inquiry = run.getInquiry();
        if (inquiry.isDeleted() || inquiry.getCurrentRun() == null
                || !runId.equals(inquiry.getCurrentRun().getId())
                || inquiry.getVersion() != run.getInputVersion()) return null;
        JsonNode payload = event.path("payload");
        String role = payload.path("role").asString("");
        String summary = payload.path("displaySummary").asString("");
        if (!ROLES.contains(role) || summary.isBlank() || summary.length() > 500) return null;
        return new AgentProgressEvent(UUID.randomUUID(), inquiry.getId(), run.getInputVersion(), runId,
                event.path("seq").asInt(), role, summary);
    }
}
