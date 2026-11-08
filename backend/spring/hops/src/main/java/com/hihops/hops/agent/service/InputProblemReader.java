package com.hihops.hops.agent.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.hihops.hops.agent.dto.AiRunRequest.ExistingWorkItem;
import com.hihops.hops.agent.dto.AiRunRequest.InputProblem;
import com.hihops.hops.agent.dto.AiRunRequest.StandardRequestBody;
import com.hihops.hops.inquiry.domain.CreationOutcome;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryProblem;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.ProblemStateView;
import com.hihops.hops.inquiry.domain.StandardRequest;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.inquiry.repository.StandardRequestRepository;
import com.hihops.hops.inquiry.service.ReportedSystemCorrections;
import com.hihops.hops.inquiry.service.ReportedSystemCorrections.Applied;
import com.hihops.hops.ticket.domain.Ticket;
import com.hihops.hops.ticket.dto.StandardRequestResponse;
import com.hihops.hops.ticket.dto.StandardRequestResponse.RequestDetailsResponse;
import com.hihops.hops.ticket.repository.TicketRepository;
import com.hihops.hops.ticket.service.TicketStandardRequestReader;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문의의 기존 문제를 AI 입력 InputProblem·ExistingWorkItem으로 바꾼다 (contracts/agents/README.md, docs/DATA.md 11.2).
 *
 * <p>값의 출처는 ERD를 따른다: 진행 상태·질문 수는 inquiry_problem, 의도·처리 유형·시스템·확인 사실은 문제별 최신 적용 실행의
 * standard_request다 ("의도·처리 유형·시스템·확인 사실은 표준 요청서에 버전별로 두고 이 테이블에는 진행 상태만 둔다").
 * 요청서 본문은 티켓 화면과 같은 {@link TicketStandardRequestReader}로 읽고, 공개 범위는 문의자 기준으로 거른다.
 *
 * <p>상태 변환 (DATA 11.2): CLARIFYING은 이번 메시지가 고른 대상 문제면 ANALYZING(답을 받았다), 아니면 WAITING_USER(아직 답을 기다린다)다.
 * 대상을 고르지 않은 메시지([])는 어느 문제의 답인지 분석이 정하므로 모두 WAITING_USER로 둔다. SYSTEM_PENDING→WAITING_SYSTEM, GUIDED→WAITING_CONFIRMATION, RESOLVED_WITHOUT_TICKET→RESOLVED, OUT_OF_SCOPE는 그대로,
 * 티켓 생성이 진행·미확인·실패면 TICKET_PENDING, 티켓이 있으면 티켓 상태로 IN_SERVICE·WAITING_CONFIRMATION·RESOLVED다.
 *
 * <p>티켓 생성 작업(creation_outcome이 있는 문제)은 ExistingWorkItem으로 함께 보낸다. 상태의 TICKET_PENDING은 DATA 11.2대로
 * PENDING·NOT_CREATED·UNKNOWN을 함께 뜻하고, 작업별 결과(outcome)와 STORED 티켓 ID로 구분한다.
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
    private final MessageRepository messageRepository;
    private final JsonMapper jsonMapper;
    private final ReportedSystemCorrections reportedSystemCorrections;

    /** AiRunRequest의 problems와 existingWorkItems. */
    public record ProblemInputs(List<InputProblem> problems, List<ExistingWorkItem> existingWorkItems) {

        /** 문제가 아직 없는 첫 분석 */
        public static final ProblemInputs NONE = new ProblemInputs(List.of(), List.of());
    }

    /**
     * @param targetProblemIds 이번 메시지가 고른 대상 문제
     * @throws IllegalStateException 적용된 표준 요청서가 없는 문제, 결과가 STORED인데 티켓이 없는 문제 등 저장 규칙이 깨진 경우.
     *         짐작해 채우지 않는다
     */
    public ProblemInputs read(Inquiry inquiry, Collection<UUID> targetProblemIds) {
        List<InquiryProblem> problems = inquiryProblemRepository.findByInquiry_IdAndDeletedAtIsNullOrderByProblemNo(
                inquiry.getId());
        if (problems.isEmpty()) {
            return ProblemInputs.NONE;
        }
        Map<UUID, StandardRequest> latest = new LinkedHashMap<>();
        standardRequestRepository.findAppliedNewestFirst(problems.stream().map(InquiryProblem::getId).toList())
                .forEach(request -> latest.putIfAbsent(request.getInquiryProblem().getId(), request));
        var corrections = reportedSystemCorrections.latest(inquiry.getId());
        Map<UUID, List<String>> asked = askedFields(inquiry.getId());
        List<InputProblem> inputs = new ArrayList<>();
        List<ExistingWorkItem> workItems = new ArrayList<>();
        for (InquiryProblem problem : problems) {
            Optional<Ticket> ticket = ticketRepository.findByInquiryProblem_Id(problem.getId())
                    .filter(found -> !found.isDeleted());
            inputs.add(input(inquiry, problem, latest.get(problem.getId()), ticket,
                    targetProblemIds.contains(problem.getId()), asked.getOrDefault(problem.getId(), List.of()),
                    corrections.get(problem.getId())));
            workItemOf(problem, ticket).ifPresent(workItems::add);
        }
        return new ProblemInputs(List.copyOf(inputs), List.copyOf(workItems));
    }

    /**
     * 문제별로 이미 물은 항목. 질문을 저장한 AI 메시지의 payload.questionBundles[].requestedFields를 보낸 순서대로 모으고
     * 중복은 한 번만 둔다. 답을 못 얻은 항목도 포함해, 같은 항목을 다시 묻지 않게 한다.
     */
    public Map<UUID, List<String>> askedFields(UUID inquiryId) {
        Map<UUID, LinkedHashSet<String>> asked = new LinkedHashMap<>();
        for (Message message : messageRepository.findAiMessagesWithQuestionBundles(inquiryId)) {
            for (JsonNode bundle : jsonMapper.readTree(message.getPayload()).path("questionBundles")) {
                String problemId = bundle.path("problemId").asString("");
                if (problemId.isEmpty()) continue;
                LinkedHashSet<String> fields = asked.computeIfAbsent(UUID.fromString(problemId), id -> new LinkedHashSet<>());
                bundle.path("requestedFields").forEach(field -> fields.add(field.asString()));
            }
        }
        Map<UUID, List<String>> result = new LinkedHashMap<>();
        asked.forEach((id, fields) -> result.put(id, List.copyOf(fields)));
        return result;
    }

    private InputProblem input(Inquiry inquiry, InquiryProblem problem, StandardRequest request, Optional<Ticket> ticket,
            boolean targeted, List<String> askedFields, @Nullable Applied correction) {
        if (request == null) {
            throw new IllegalStateException("적용된 표준 요청서가 없는 문제다: " + problem.getId());
        }
        if (correction != null && correction.inquiryVersion() > request.getInquiryVersion()) {
            // 이전 범위의 기능·분석·지식은 이력으로 보존하고 새 범위에서 다시 분석한다.
            // 문제당 질문 횟수와 질문 이력은 유지한다.
            String summary = correction.summary();
            var details = new RequestDetailsResponse(null, null, null, null, null, null, null, null, List.of());
            return new InputProblem(problem.getId(), summary, request.getIntent().name(), request.getWorkType().name(),
                    correction.systemId(), null, stateOf(problem, ticket, targeted), problem.getClarifyCount(), askedFields,
                    new StandardRequestBody(summary, List.of(), List.of(), List.of(), null, List.of(), details, List.of()),
                    null, correction.sourceMessageId());
        }
        StandardRequestResponse body = standardRequestReader.read(request, inquiry.getRequester());
        return new InputProblem(problem.getId(), request.getSummary(), request.getIntent().name(),
                request.getWorkType().name(),
                request.getReportedSystem() == null ? null : request.getReportedSystem().getId(),
                request.getReportedFeature() == null ? null : request.getReportedFeature().getId(),
                stateOf(problem, ticket, targeted), problem.getClarifyCount(), askedFields,
                new StandardRequestBody(body.summary(), body.confirmedFacts(), body.assumptions(), body.unknowns(),
                        body.responsibleSystemCandidateId(), body.responsibilityEvidenceIds(), body.details(),
                        body.knowledge()),
                null);
    }

    private static Optional<ExistingWorkItem> workItemOf(InquiryProblem problem, Optional<Ticket> ticket) {
        CreationOutcome outcome = problem.getCreationOutcome();
        if (outcome == null) {
            return Optional.empty();
        }
        if (problem.getCreationInputVersion() == null || (outcome == CreationOutcome.STORED) != ticket.isPresent()) {
            throw new IllegalStateException("티켓 생성 결과와 저장된 티켓이 맞지 않는다: " + problem.getId());
        }
        return Optional.of(new ExistingWorkItem(problem.getId(), problem.getId(), problem.getCreationInputVersion(),
                outcome.name(), outcome == CreationOutcome.STORED ? ticket.get().getId() : null));
    }

    // 이번 메시지가 고른 문제는 답을 받았고, 고르지 않은 질문 중 문제는 아직 답을 기다린다
    private static String stateOf(InquiryProblem problem, Optional<Ticket> ticket, boolean targeted) {
        return ProblemStateView.of(problem, ticket, !targeted);
    }
}
