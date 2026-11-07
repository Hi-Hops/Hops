package com.hihops.hops.agent.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.dto.AiRunRequest.InputProblem;
import com.hihops.hops.agent.dto.AiRunRequest.StandardRequestBody;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryProblem;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.inquiry.repository.StandardRequestRepository;
import com.hihops.hops.itsystem.service.SystemQueryService;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.service.TicketStandardRequestReader;

import lombok.RequiredArgsConstructor;

/** 미해결 GUIDE의 같은 문제를 기존 상담 입력/예산/권한 생성기로 다시 접수한다. */
@Component
@RequiredArgsConstructor
public class GuideReanalysisInputFactory {
    private final AnalysisInputFactory analysisInputs;
    private final MessageRepository messages;
    private final SystemQueryService systems;
    private final StandardRequestRepository standardRequests;
    private final TicketStandardRequestReader standardReader;
    private final InputProblemReader problemReader;

    public String snapshot(AgentRun run, Inquiry inquiry, InquiryProblem problem,
            ResolutionRequestResponse response, Instant now) {
        var context = Map.of("requestId", response.id(), "source", "GUIDE", "sourceId", response.sourceId(),
                "sourceVersion", response.sourceVersion(), "state", "UNRESOLVED");
        var asked = problemReader.askedFields(inquiry.getId()).getOrDefault(problem.getId(), List.of());
        var target = new InputProblem(problem.getId(), problem.getTitle(), "UNKNOWN", "KNOWLEDGE_QA", null, null,
                "ANALYZING", problem.getClarifyCount(), asked, null, context);
        var saved = standardRequests.findAppliedNewestFirst(List.of(problem.getId())).stream().findFirst();
        if (saved.isPresent()) {
            var standard = saved.get();
            var visible = standardReader.read(standard, inquiry.getRequester());
            var body = new StandardRequestBody(visible.summary(), visible.confirmedFacts(), visible.assumptions(),
                    visible.unknowns(), visible.responsibleSystemCandidateId(), visible.responsibilityEvidenceIds(),
                    visible.details(), visible.knowledge());
            target = new InputProblem(problem.getId(), standard.getSummary(), standard.getIntent().name(),
                    standard.getWorkType().name(), standard.getReportedSystem() == null ? null : standard.getReportedSystem().getId(),
                    standard.getReportedFeature() == null ? null : standard.getReportedFeature().getId(),
                    "ANALYZING", problem.getClarifyCount(), asked, body, context);
        }
        return analysisInputs.snapshot(run, inquiry, messages.findAllCompletedInOrder(inquiry.getId()),
                new InputProblemReader.ProblemInputs(List.of(target), List.of()), List.of(problem.getId()),
                systems.findSupportedSystems(), now);
    }
}
