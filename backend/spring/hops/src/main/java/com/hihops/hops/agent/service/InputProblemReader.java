package com.hihops.hops.agent.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hihops.hops.agent.dto.AiRunRequest.InputProblem;
import com.hihops.hops.agent.dto.AiRunRequest.StandardRequestBody;
import com.hihops.hops.inquiry.domain.CreationOutcome;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryProblem;
import com.hihops.hops.inquiry.domain.StandardRequest;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.StandardRequestRepository;
import com.hihops.hops.ticket.dto.StandardRequestResponse;
import com.hihops.hops.ticket.repository.TicketRepository;
import com.hihops.hops.ticket.service.TicketStandardRequestReader;

import lombok.RequiredArgsConstructor;

/**
 * 문의의 기존 문제를 AI 입력 InputProblem으로 바꾼다 (contracts/agents/README.md InputProblem, docs/DATA.md 11.2).
 *
 * <p>값의 출처는 ERD를 따른다: 진행 상태·질문 수는 inquiry_problem, 의도·처리 유형·시스템·확인 사실은 문제별 최신 적용 실행의
 * standard_request다 ("의도·처리 유형·시스템·확인 사실은 표준 요청서에 버전별로 두고 이 테이블에는 진행 상태만 둔다").
 * 요청서 본문은 티켓 화면과 같은 {@link TicketStandardRequestReader}로 읽고, 공개 범위는 문의자 기준으로 거른다.
 *
 * <p>상태 변환 (DATA 11.2): 새 사용자 입력으로 다시 분석하는 입력이라 CLARIFYING은 ANALYZING이다(질문에 답한 뒤라 응답 대기가 아니다).
 * SYSTEM_PENDING→WAITING_SYSTEM, GUIDED→WAITING_CONFIRMATION, RESOLVED_WITHOUT_TICKET→RESOLVED, OUT_OF_SCOPE는 그대로,
 * 티켓 생성이 진행·미확인·실패면 TICKET_PENDING, 티켓이 있으면 티켓 상태로 IN_SERVICE·WAITING_CONFIRMATION·RESOLVED다.
 *
 * <p>resolutionContext는 아직 투영하지 않아 null이다(해결 확인 요청 기능에서 채운다). 트랜잭션 안에서 부른다.
 */
@Component
@RequiredArgsConstructor
public class InputProblemReader {

    private final InquiryProblemRepository inquiryProblemRepository;
    private final StandardRequestRepository standardRequestRepository;
    private final TicketRepository ticketRepository;
    private final TicketStandardRequestReader standardRequestReader;

    /**
     * @throws IllegalStateException 적용된 표준 요청서가 없는 문제. 결과를 적용할 때마다 요청서를 남기는 규칙이 깨진 것이라 짐작해 채우지 않는다
     */
    public List<InputProblem> read(Inquiry inquiry) {
        List<InquiryProblem> problems = inquiryProblemRepository.findByInquiry_IdAndDeletedAtIsNullOrderByProblemNo(
                inquiry.getId());
        if (problems.isEmpty()) {
            return List.of();
        }
        Map<UUID, StandardRequest> latest = new LinkedHashMap<>();
        standardRequestRepository.findAppliedNewestFirst(problems.stream().map(InquiryProblem::getId).toList())
                .forEach(request -> latest.putIfAbsent(request.getInquiryProblem().getId(), request));
        return problems.stream().map(problem -> input(inquiry, problem, latest.get(problem.getId()))).toList();
    }

    private InputProblem input(Inquiry inquiry, InquiryProblem problem, StandardRequest request) {
        if (request == null) {
            throw new IllegalStateException("적용된 표준 요청서가 없는 문제다: " + problem.getId());
        }
        StandardRequestResponse body = standardRequestReader.read(request, inquiry.getRequester());
        return new InputProblem(problem.getId(), request.getSummary(), request.getIntent().name(),
                request.getWorkType().name(),
                request.getReportedSystem() == null ? null : request.getReportedSystem().getId(),
                request.getReportedFeature() == null ? null : request.getReportedFeature().getId(),
                stateOf(problem), problem.getClarifyCount(),
                new StandardRequestBody(body.summary(), body.confirmedFacts(), body.assumptions(), body.unknowns(),
                        body.responsibleSystemCandidateId(), body.responsibilityEvidenceIds(), body.details(),
                        body.knowledge()),
                null);
    }

    private String stateOf(InquiryProblem problem) {
        CreationOutcome outcome = problem.getCreationOutcome();
        if (outcome == CreationOutcome.PENDING || outcome == CreationOutcome.NOT_CREATED
                || outcome == CreationOutcome.UNKNOWN) {
            return "TICKET_PENDING";
        }
        return switch (problem.getProblemStatus()) {
            case CLARIFYING -> "ANALYZING";
            case SYSTEM_PENDING -> "WAITING_SYSTEM";
            case GUIDED -> "WAITING_CONFIRMATION";
            case RESOLVED_WITHOUT_TICKET -> "RESOLVED";
            case TICKET_FAILED -> "TICKET_PENDING";
            case OUT_OF_SCOPE -> "OUT_OF_SCOPE";
            case TICKET_CREATED -> ticketRepository.findByInquiryProblem_Id(problem.getId())
                    .filter(ticket -> !ticket.isDeleted())
                    .map(ticket -> switch (ticket.getTicketStatus()) {
                        case OPEN, IN_PROGRESS -> "IN_SERVICE";
                        case WAITING_CONFIRM -> "WAITING_CONFIRMATION";
                        case RESOLVED -> "RESOLVED";
                    })
                    .orElseThrow(() -> new IllegalStateException("티켓이 생성된 문제에 티켓이 없다: " + problem.getId()));
        };
    }
}
