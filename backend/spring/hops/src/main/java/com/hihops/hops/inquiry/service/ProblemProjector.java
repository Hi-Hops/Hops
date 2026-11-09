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
import com.hihops.hops.ticket.repository.TicketAssignmentRepository;
import com.hihops.hops.ticket.repository.TicketRepository;
import com.hihops.hops.ticket.repository.TicketWorkLogRepository;
import com.hihops.hops.ticket.service.TicketProgressReader;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Component
@RequiredArgsConstructor
public class ProblemProjector {

    private final InquiryProblemRepository inquiryProblemRepository;
    private final StandardRequestRepository standardRequestRepository;
    private final MessageRepository messageRepository;
    private final GuideVisibility guideVisibility;
    private final TicketRepository ticketRepository;
    private final TicketAssignmentRepository assignmentRepository;
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
        Optional<Ticket> ticket = liveTicket(problem);
        boolean answered = analyzing.contains(problem.getId().toString());
        String state = ProblemStateView.of(problem, ticket, problem.getClarifyCount() > 0 && !answered);
        Optional<Message> guideMessage = messageRepository.findLatestGuide(inquiry.getId(), problem.getId());
        // 안내 근거는 한 번만 확인해 근거 목록과 확인 요약에 같이 쓴다
        Optional<GuideVisibility.GuideView> guideView = guideMessage.map(message -> guideVisibility.viewOf(inquiry, message));
        boolean corrected = correction != null && correction.inquiryVersion() > request.getInquiryVersion();
        boolean systemUnknown = "WAITING_SYSTEM".equals(state) && !corrected;
        UUID systemId = corrected ? correction.systemId()
                : request.getReportedSystem() == null ? null : request.getReportedSystem().getId();
        // 정정은 범위를 바꾸므로 요약도 바꾸지만, 문의자가 시스템을 고른 것(selection)은 빈 값을 채운 것이라 요약을 유지한다
        boolean replaced = corrected && !correction.isSelection();
        String summary = replaced ? correction.summary() : request.getSummary();
        return new ProblemResponse(problem.getId(), inquiry.getId(), summary, request.getIntent().name(),
                request.getWorkType().name(), systemUnknown ? null : systemId,
                systemUnknown || replaced || request.getReportedFeature() == null ? null : request.getReportedFeature().getId(),
                state, problem.getClarifyCount(), guideMessage.map(message -> new GuideAnswerResponse(message.getId(), guideView.orElseThrow().evidence())).orElse(null),
                creationOf(problem, ticket), ticket.map(this::summaryOf).orElse(null),
                ticket.isPresent() ? resolutionOf(ticket.get())
                        : guideMessage.map(message -> guideResolutionOf(inquiry, problem, message, guideView.orElseThrow())).orElse(null));
    }

    // 문제의 티켓(삭제된 것 제외). 문의 상세와 작업 조회가 같은 기준을 쓴다.
    private Optional<Ticket> liveTicket(InquiryProblem problem) {
        return ticketRepository.findByInquiryProblem_Id(problem.getId()).filter(found -> !found.isDeleted());
    }

    /**
     * 한 문제의 티켓 생성 작업 결과 (GET /work-items). 작업이 없으면 null.
     *
     * <p>문의 상세와 같은 형식이되 저장 결과와 티켓이 어긋나면 500이 아니라 복구해 돌려준다. 이 값은 "저장됐는지" 확인하려고
     * 다시 읽는 값이라 어긋남을 영구 오류로 막으면 확인 경로가 사라진다. 규칙은 내부 AI 조회
     * ({@code TicketCreationService.result})와 같다.
     * <ul>
     *   <li>삭제되지 않은 티켓에 저장된 책임 시스템·담당자와 같은 현재 배정이 있으면 STORED</li>
     *   <li>티켓 행이 있어도 그 외(삭제됨·배정 없음·배정 종료)면 UNKNOWN. 삭제된 행도 uk_ticket_inquiry_problem을 계속 차지해
     *       다시 만들 수 없으므로 NOT_CREATED로 보이면 안 된다</li>
     *   <li>티켓 행이 없으면 저장된 값, 단 STORED는 UNKNOWN</li>
     * </ul>
     * 정상 경로(TicketCreationService.attempt)는 티켓·배정·STORED를 한 거래로 쓰므로 보정은 수동 수정·부분 삭제 같은 데이터 손상
     * 때만 일어난다. 문의 상세는 같은 행에 500을 내므로 보정할 때마다 WARN을 남겨 운영자가 알 수 있게 한다.
     *
     * @throws IllegalStateException 입력 버전이 없는 등 결과 행 자체가 깨진 경우
     */
    public @Nullable CreationResultResponse creation(InquiryProblem problem) {
        CreationOutcome stored = problem.getCreationOutcome();
        if (stored == null) {
            return null;
        }
        Optional<Ticket> row = ticketRepository.findByInquiryProblem_Id(problem.getId());
        Optional<Ticket> ticket = row.filter(found -> !found.isDeleted()
                && assignmentRepository.existsByTicketIdAndItSystemIdAndAssigneeIdAndEndedAtIsNullAndDeletedAtIsNull(
                        found.getId(), found.getResponsibleSystem().getId(), found.getAssignee().getId()));
        CreationOutcome outcome = ticket.isPresent() ? CreationOutcome.STORED
                : row.isPresent() || stored == CreationOutcome.STORED ? CreationOutcome.UNKNOWN : stored;
        if (outcome != stored) {
            log.warn("티켓 생성 결과를 보정합니다: problemId={}, stored={}, served={}, ticketRow={}", problem.getId(), stored,
                    outcome, row.isPresent());
        }
        return creationResult(problem, outcome, ticket);
    }

    private @Nullable CreationResultResponse creationOf(InquiryProblem problem, Optional<Ticket> ticket) {
        CreationOutcome outcome = problem.getCreationOutcome();
        if (outcome == null) {
            return null;
        }
        if ((outcome == CreationOutcome.STORED) != ticket.isPresent()) {
            throw new IllegalStateException("티켓 생성 결과와 저장된 티켓이 맞지 않는다: " + problem.getId());
        }
        return creationResult(problem, outcome, ticket);
    }

    private CreationResultResponse creationResult(InquiryProblem problem, CreationOutcome outcome, Optional<Ticket> ticket) {
        if (problem.getCreationInputVersion() == null) {
            throw new IllegalStateException("티켓 생성 결과에 입력 버전이 없다: " + problem.getId());
        }
        ErrorResponse error = outcome == CreationOutcome.STORED || problem.getTicketErrorCode() == null ? null
                : ErrorResponse.of(errorCodeOf(problem.getTicketErrorCode()), RequestIdFilter.currentRequestId());
        return new CreationResultResponse(problem.getId(), problem.getId(), problem.getCreationInputVersion(),
                outcome.name(), outcome == CreationOutcome.STORED ? ticket.orElseThrow().getId() : null, error,
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
