package com.hihops.hops.inquiry.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;
import com.hihops.hops.agent.service.AgentResultRejectedException;
import com.hihops.hops.agent.service.AgentRunResultHandler;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.repository.InquiryRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.realtime.dto.MessageStoredEvent;
import com.hihops.hops.user.domain.Member;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 상담 분석(INQUIRY_ANALYSIS) 결과를 업무에 반영한다: AI 답변 메시지 저장과 실행 종료의 업무 쪽 반영
 * (WIRE_CONTRACT 12.8 6번, contracts/agents/README.md).
 *
 * <p>AI 결과는 제안일 뿐이라 Spring이 현재 상태와 다시 대조한다. 접수 뒤 권한이 바뀌었거나, 더 새 입력·실행이 생겼거나,
 * 답변 형식이 계약과 다르면 저장하지 않는다. 늦게 온 이전 실행의 결과는 최신 문의에 반영하지 않는다.
 *
 * <p>답변과 검증된 가이드의 대상 문제·표준 요청·해결 확인 요청을 함께 저장한다. 가이드 이외 문제 분리·질문 묶음·티켓 제안은
 * 해당 업무 저장이 구현되기 전까지 {@code validation_result.unapplied}에 남긴다. AI 결과 원문은 agent_run.result에 있다.
 */
@Component
@RequiredArgsConstructor
public class InquiryAnswerHandler implements AgentRunResultHandler {

    private static final int MAX_ANSWER_CODE_POINTS = 4_000; // AiResult.answer = WireContent(1~4,000 코드 포인트)
    private static final List<String> LIST_FIELDS = List.of("problemProposals", "questions", "ticketProposals", "evidence");
    private static final List<String> OBJECT_FIELDS = List.of("guide", "caseProposal");

    private final InquiryRepository inquiryRepository;
    private final MessageRepository messageRepository;
    private final AiAnswerMessageWriter answerWriter;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final GuideResultWriter guides;
    private final InquiryProblemRepository problems;

    @Override
    public AgentTask task() {
        return AgentTask.INQUIRY_ANALYSIS;
    }

    @Override
    public void verifyAccess(AgentRun run, JsonNode input) {
        checkAccess(run.getInquiry(), input);
    }

    /**
     * 접수 때 AI 입력에 고정한 권한 문맥(문의자·policyRevision)이 지금도 유효한지 확인한다.
     * AI에 보내기 전({@link #verifyAccess})과 결과를 쓰기 직전({@link #apply}) 모두 부른다.
     * AI가 답하는 동안 권한 세대가 바뀔 수 있고, 결과 적용은 최신 policyRevision을 다시 검증해야 한다
     * (contracts/agents/README.md, docs/SECURITY.md SEC-01).
     */
    private static void checkAccess(Inquiry inquiry, JsonNode input) {
        Member requester = inquiry.getRequester();
        JsonNode access = input.path("accessContext");
        boolean valid = !inquiry.isDeleted() && !requester.isDeleted()
                && requester.getId().toString().equals(access.path("actorId").asString(""))
                && requester.getPolicyRevision() == access.path("policyRevision").asInt(-1);
        if (!valid) {
            // 접수 뒤 문의자의 권한 세대가 바뀌었거나 삭제됐다. 오래된 허용을 재사용하지 않는다.
            throw new AgentResultRejectedException("AUTH_CONTEXT_INVALID", "접수 때의 권한 문맥이 더는 유효하지 않습니다");
        }
    }

    @Override
    public String apply(AgentRun run, JsonNode input, JsonNode result) {
        // 같은 문의의 변경은 문의 행 잠금으로 하나씩 처리한다(메시지 순번 발급 포함)
        Inquiry inquiry = inquiryRepository.findByIdAndDeletedAtIsNull(run.getInquiry().getId())
                .orElseThrow(() -> new AgentResultRejectedException(AgentResultRejectedException.SUPERSEDED,
                        "문의가 더는 유효하지 않습니다"));
        if (inquiry.getCurrentRun() == null || !inquiry.getCurrentRun().getId().equals(run.getId())
                || inquiry.getVersion() != run.getInputVersion()) {
            throw new AgentResultRejectedException(AgentResultRejectedException.SUPERSEDED,
                    "더 새 입력이나 실행이 있어 이 결과를 반영하지 않습니다");
        }
        // 늦은 결과는 위에서 SUPERSEDED로 걸러졌다. 현재 결과라면 쓰기 직전에 권한 세대를 다시 확인한다.
        checkAccess(inquiry, input);

        String answer = answerOf(result);
        UUID answerMessageId = reservedAnswerMessageId(input);
        if (messageRepository.existsById(answerMessageId)) {
            throw new AgentResultRejectedException("INVALID_OUTPUT", "예약한 답변 메시지 ID가 이미 사용됐습니다");
        }

        answerWriter.insert(inquiry.getId(), answerMessageId, run.getId(), answer);
        var guide = guides.apply(run, inquiry, answerMessageId, input, result);
        // 저장한 AI 답변을 문의 구독에 알린다. 결과 반영 거래가 commit된 뒤에만 전달된다
        eventPublisher.publishEvent(MessageStoredEvent.of(inquiry.getId(), inquiry.getVersion(), answerMessageId, null));
        completeResolvedInquiry(inquiry, result);
        return validationOf(result, guide);
    }

    private void completeResolvedInquiry(Inquiry inquiry, JsonNode result) {
        var current = problems.findByInquiry_IdAndDeletedAtIsNullOrderByProblemNo(inquiry.getId());
        if (current.isEmpty() || problems.countUnresolved(inquiry.getId()) != 0
                || result.path("questions").size() > 0 || result.path("ticketProposals").size() > 0) return;
        var known = current.stream().map(problem -> problem.getId().toString()).collect(java.util.stream.Collectors.toSet());
        for (var proposal : result.path("problemProposals")) {
            if (!known.contains(proposal.path("problemRef").asString())) return; // 미적용 새 문제를 완료로 숨기지 않는다.
        }
        inquiry.resolveAfterAnalysis();
    }

    private static String answerOf(JsonNode result) {
        JsonNode answer = result.path("answer");
        if (!answer.isString() || answer.asString().isBlank()
                || answer.asString().codePointCount(0, answer.asString().length()) > MAX_ANSWER_CODE_POINTS) {
            throw new AgentResultRejectedException("INVALID_OUTPUT", "답변이 없거나 형식이 올바르지 않습니다");
        }
        return answer.asString();
    }

    // 접수 때 Spring이 발급해 입력에 고정한 값이다. 읽을 수 없으면 저장 규칙이 깨진 것이다.
    private static UUID reservedAnswerMessageId(JsonNode input) {
        try {
            return UUID.fromString(input.path("answerMessageId").asString(""));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("실행 입력에 answerMessageId가 없습니다", exception);
        }
    }

    // 반영한 것과 받았지만 아직 저장하지 않는 AI 제안을 구분해 남긴다.
    private String validationOf(JsonNode result, GuideResultWriter.Applied guide) {
        List<String> unapplied = new ArrayList<>();
        for (String field : LIST_FIELDS) {
            if (result.path(field).isArray() && !result.path(field).isEmpty()) {
                if (guide != null && "problemProposals".equals(field) && result.path(field).size() == 1) continue;
                if (guide != null && "evidence".equals(field) && result.path(field).size() == guide.evidenceCount()) continue;
                unapplied.add(field);
            }
        }
        for (String field : OBJECT_FIELDS) {
            if (result.path(field).isObject()) {
                if (guide != null && "guide".equals(field)) continue;
                unapplied.add(field);
            }
        }
        var validation = jsonMapper.createObjectNode();
        validation.putArray("applied").add("answer");
        if (guide != null) {
            ((tools.jackson.databind.node.ArrayNode) validation.path("applied")).add("guide");
            if (result.path("problemProposals").size() == 1)
                ((tools.jackson.databind.node.ArrayNode) validation.path("applied")).add("problemProposals");
            if (result.path("evidence").size() == guide.evidenceCount())
                ((tools.jackson.databind.node.ArrayNode) validation.path("applied")).add("evidence");
            validation.put("appliedGuideProblemRef", guide.problemRef());
            validation.put("appliedGuideProblemId", guide.problemId().toString());
        }
        var ignored = validation.putArray("unapplied");
        unapplied.forEach(ignored::add);
        return validation.toString();
    }
}
