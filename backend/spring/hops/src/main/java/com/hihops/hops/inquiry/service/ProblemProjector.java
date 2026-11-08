package com.hihops.hops.inquiry.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.RunStatus;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.global.exception.ErrorResponse;
import com.hihops.hops.global.filter.RequestIdFilter;
import com.hihops.hops.inquiry.domain.CreationOutcome;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryProblem;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.ProblemStateView;
import com.hihops.hops.inquiry.domain.StandardRequest;
import com.hihops.hops.inquiry.dto.ProblemResponse;
import com.hihops.hops.inquiry.dto.ProblemResponse.CreationResultResponse;
import com.hihops.hops.inquiry.dto.ProblemResponse.GuideAnswerResponse;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.inquiry.repository.StandardRequestRepository;
import com.hihops.hops.inquiry.service.ReportedSystemCorrections.Applied;
import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.dto.SupportedSystemResponse;
import com.hihops.hops.itsystem.service.ItSystemService;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.dto.ResolutionRequestResponse;
import com.hihops.hops.ticket.dto.TicketSummaryResponse;
import com.hihops.hops.ticket.repository.TicketRepository;
import com.hihops.hops.ticket.repository.TicketWorkLogRepository;
import com.hihops.hops.ticket.service.TicketProgressReader;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문의의 문제를 문의자 화면용 Problem으로 모은다 (DATA_TYPES 신규 절 Problem, InquiryResult.problems).
 *
 * <p>값의 출처는 AI 입력({@code InputProblemReader})과 같다: 진행 상태·질문 수는 inquiry_problem, 요약·의도·처리 유형·보고 시스템은
 * 문제별 최신 적용(SUCCEEDED) 실행의 standard_request다(docs/ERD.md). 그 밖의 필드는 저장된 업무 기록에서만 읽고 짐작하지 않는다.
 * <ul>
 *   <li>guide: 이 문제의 안내를 담은 최신 AI 메시지(payload.guide)와, 그 답변 실행 결과의 근거 중 guide.evidenceIds에 있고
 *       지금 문의자에게 공개 가능한 직원용(EMPLOYEE) 근거만 (docs/DATA.md 11.3, {@link GuideVisibility})</li>
 *   <li>creationResult: inquiry_problem.creation_outcome·creation_input_version·ticket_error_code (workItemId = problemId)</li>
 *   <li>ticket: 티켓 화면과 같은 {@link TicketSummaryResponse}</li>
 *   <li>activeResolutionRequest: 티켓이 있으면 티켓 화면과 같은 {@link TicketProgressReader}(ticket_event), 없으면 그 문제의 최신 안내
 *       메시지 payload.resolutionRequest(GUIDE 출처, 같은 ResolutionRequest 형식) (docs/DATA.md 확인 메타데이터)</li>
 * </ul>
 *
 * <p>상태: CLARIFYING은 질문을 했고(질문 수 1 이상) 지금 분석 중인 실행이 그 문제를 대상으로 삼지 않았으면 문의자의 답을 기다리는
 * WAITING_USER, 그 외 ANALYZING이다 (DATA 11.2). 대상은 현재 실행 입력의 problemIds로, AI 입력({@code InputProblemReader})과 같은 기준이다.
 * 담당자 내부 표준 요청서는 담지 않는다. 트랜잭션 안에서 부른다.
 */
@Component
@RequiredArgsConstructor
public class ProblemProjector {

    private final InquiryProblemRepository inquiryProblemRepository;
    private final StandardRequestRepository standardRequestRepository;
    private final MessageRepository messageRepository;
    private final GuideVisibility guideVisibility;
    private final TicketRepository ticketRepository;
    private final TicketWorkLogRepository workLogRepository;
    private final TicketProgressReader progressReader;
    private final ItSystemService itSystemService;
    private final JsonMapper jsonMapper;
    private final ReportedSystemCorrections reportedSystemCorrections;

    /**
     * @throws IllegalStateException 적용된 표준 요청서가 없는 문제, 티켓 생성 결과와 티켓이 어긋난 문제 등 저장 규칙이 깨진 경우
     */
    public List<ProblemResponse> project(Inquiry inquiry) {
        List<InquiryProblem> problems = inquiryProblemRepository.findByInquiry_IdAndDeletedAtIsNullOrderByProblemNo(
                inquiry.getId());
        if (problems.isEmpty()) {
            return List.of();
        }
        Map<UUID, StandardRequest> latest = new LinkedHashMap<>();
        standardRequestRepository.findAppliedNewestFirst(problems.stream().map(InquiryProblem::getId).toList())
                .forEach(request -> latest.putIfAbsent(request.getInquiryProblem().getId(), request));
        Set<String> analyzing = analyzingProblemIds(inquiry.getCurrentRun());
        var corrections = reportedSystemCorrections.latest(inquiry.getId());
        return problems.stream().map(problem -> project(inquiry, problem, latest.get(problem.getId()), analyzing,
                corrections.get(problem.getId()))).toList();
    }

    // 분석 중인 실행이 대상으로 삼은 문제. 실행이 없거나 끝났으면 없다. 대상 없이 접수한 실행은 어느 문제의 답인지 분석이 정한다.
    private Set<String> analyzingProblemIds(@Nullable AgentRun run) {
        if (run == null || (run.getRunStatus() != RunStatus.QUEUED && run.getRunStatus() != RunStatus.RUNNING)) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        jsonMapper.readTree(run.getInputSnapshot()).path("problemIds").forEach(id -> ids.add(id.asString("")));
        return ids;
    }

    private ProblemResponse project(Inquiry inquiry, InquiryProblem problem, @Nullable StandardRequest request,
            Set<String> analyzing, @Nullable Applied correction) {
        if (request == null) {
            throw new IllegalStateException("적용된 표준 요청서가 없는 문제다: " + problem.getId());
        }
        Optional<Ticket> ticket = ticketRepository.findByInquiryProblem_Id(problem.getId())
                .filter(found -> !found.isDeleted());
        boolean answered = analyzing.contains(problem.getId().toString());
        String state = ProblemStateView.of(problem, ticket, problem.getClarifyCount() > 0 && !answered);
        Optional<Message> guideMessage = messageRepository.findLatestGuide(inquiry.getId(), problem.getId());
        // 안내 근거는 한 번만 확인해 근거 목록과 확인 요약에 같이 쓴다
        Optional<GuideVisibility.GuideView> guideView = guideMessage.map(message -> guideVisibility.viewOf(inquiry, message));
        boolean corrected = correction != null && correction.inquiryVersion() > request.getInquiryVersion();
        boolean systemUnknown = "WAITING_SYSTEM".equals(state) && !corrected;
        UUID systemId = corrected ? correction.systemId()
                : request.getReportedSystem() == null ? null : request.getReportedSystem().getId();
        String summary = corrected ? correction.summary() : request.getSummary();
        return new ProblemResponse(problem.getId(), inquiry.getId(), summary, request.getIntent().name(),
                request.getWorkType().name(), systemUnknown ? null : systemId,
                systemUnknown || corrected || request.getReportedFeature() == null ? null : request.getReportedFeature().getId(),
                state, problem.getClarifyCount(), guideMessage.map(message -> new GuideAnswerResponse(message.getId(), guideView.orElseThrow().evidence())).orElse(null),
                creationOf(problem, ticket), ticket.map(this::summaryOf).orElse(null),
                ticket.isPresent() ? resolutionOf(ticket.get())
                        : guideMessage.map(message -> guideResolutionOf(inquiry, problem, message, guideView.orElseThrow())).orElse(null));
    }

    private @Nullable CreationResultResponse creationOf(InquiryProblem problem, Optional<Ticket> ticket) {
        CreationOutcome outcome = problem.getCreationOutcome();
        if (outcome == null) {
            return null;
        }
        if (problem.getCreationInputVersion() == null || (outcome == CreationOutcome.STORED) != ticket.isPresent()) {
            throw new IllegalStateException("티켓 생성 결과와 저장된 티켓이 맞지 않는다: " + problem.getId());
        }
        ErrorResponse error = outcome == CreationOutcome.STORED || problem.getTicketErrorCode() == null ? null
                : ErrorResponse.of(errorCodeOf(problem.getTicketErrorCode()), RequestIdFilter.currentRequestId());
        return new CreationResultResponse(problem.getId(), problem.getId(), problem.getCreationInputVersion(),
                outcome.name(), outcome == CreationOutcome.STORED ? ticket.get().getId() : null, error,
                problem.getUpdatedAt());
    }

    // 저장된 오류 코드가 공개 코드면 그대로, 아니면 일시 장애로 보인다. 내부 원인 문구는 내보내지 않는다.
    private static ErrorCode errorCodeOf(String stored) {
        return Arrays.stream(ErrorCode.values()).filter(code -> code.name().equals(stored)).findFirst()
                .orElse(ErrorCode.SERVICE_UNAVAILABLE);
    }

    // 티켓 화면(TicketService)과 같은 형식. 저장된 보고·책임 시스템과 담당자만 쓴다.
    private TicketSummaryResponse summaryOf(Ticket ticket) {
        ItSystem reported = ticket.getStandardRequest().getReportedSystem();
        if (reported == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        Map<UUID, SupportedSystemResponse> catalog = itSystemService.responses(List.of(reported,
                ticket.getResponsibleSystem()));
        return TicketSummaryResponse.of(ticket, catalog.get(reported.getId()),
                catalog.get(ticket.getResponsibleSystem().getId()));
    }

    private @Nullable ResolutionRequestResponse resolutionOf(Ticket ticket) {
        return progressReader.resolution(ticket, workLogRepository.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId()));
    }

    /**
     * 티켓 없이 안내한 문제의 해결 확인 요청. 안내 메시지 payload.resolutionRequest에 ResolutionRequest 형식으로 둔다
     * (티켓의 ticket_event.after_data.resolutionRequest와 같은 형식). 출처는 그 안내 메시지(GUIDE)여야 한다.
     *
     * @throws IllegalStateException 저장된 요청이 이 문의·문제·안내 메시지와 맞지 않을 때
     */
    private @Nullable ResolutionRequestResponse guideResolutionOf(Inquiry inquiry, InquiryProblem problem, Message message,
            GuideVisibility.GuideView view) {
        JsonNode stored = jsonMapper.readTree(message.getPayload()).path("resolutionRequest");
        if (stored.isMissingNode() || stored.isNull()) {
            return null;
        }
        ResolutionRequestResponse request = jsonMapper.treeToValue(stored, ResolutionRequestResponse.class);
        if (!"GUIDE".equals(request.source()) || !message.getId().equals(request.sourceId())
                || !problem.getId().equals(request.problemId()) || !inquiry.getId().equals(request.inquiryId())
                || request.ticketId() != null) {
            throw new IllegalStateException("안내 확인 요청이 안내 메시지와 맞지 않는다: " + message.getId());
        }
        return view.resolutionOf(request);
    }
}
