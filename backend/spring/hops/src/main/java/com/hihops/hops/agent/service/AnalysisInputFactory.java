package com.hihops.hops.agent.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.dto.AiRunRequest;
import com.hihops.hops.agent.dto.AiRunRequest.AccessContext;
import com.hihops.hops.agent.dto.AiRunRequest.InputMessage;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.MessagePayload;
import com.hihops.hops.inquiry.domain.ProblemStatus;
import com.hihops.hops.inquiry.domain.SenderType;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.itsystem.dto.SupportedSystemResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 상담 실행(INQUIRY_ANALYSIS)의 입력 snapshot을 만든다 (contracts/agents/README.md AiRunRequest, WIRE_CONTRACT 12.8 1번).
 *
 * <p>실행 접수 거래 안에서 runId가 정해진 뒤 부른다. 결과 JSON은 {@link AgentRun#prepareInput}으로 고정하고 다시 바꾸지 않는다.
 * answerMessageId도 여기서 발급한다. 최종 AI 메시지는 결과를 검증한 뒤 이 ID로 한 번만 저장한다 (docs/DATA.md 11.3).
 *
 * <p>권한 문맥: 문의자 상담이라 purpose=EMPLOYEE_HELP, 직원 공개 문서(EMPLOYEE)만 허용한다.
 * 직원은 지원 시스템 어느 것이든 물어볼 수 있으므로 systemIds는 지원 시스템 전체다.
 */
@Component
@RequiredArgsConstructor
public class AnalysisInputFactory {

    private static final String CONTRACT_VERSION = "hops-system-1";
    private static final String SCOPE_NOTICE = "지원 범위 밖의 문의입니다. 지원 시스템에 관한 문의를 이용해 주세요.";

    private final AnalysisRunProperties properties;
    private final JsonMapper jsonMapper;
    private final InquiryProblemRepository inquiryProblems;

    /**
     * @param run              저장해서 ID가 정해진 대기 실행
     * @param messages         AI에 보여 줄 저장된 메시지 (순번 순)
     * @param problems         기존 문제와 티켓 생성 작업 ({@link InputProblemReader}). 첫 분석이면 {@link InputProblemReader.ProblemInputs#NONE}
     * @param targetProblemIds 사용자가 고른 대상 문제. 빈 목록이면 분석이 대상을 정한다
     * @param systems          지원 시스템 목록 (GET /systems와 같은 값)
     * @param now              접수 시각. expiresAt = now + deadline
     */
    public String snapshot(AgentRun run, Inquiry inquiry, List<Message> messages, InputProblemReader.ProblemInputs problems,
            List<UUID> targetProblemIds, List<SupportedSystemResponse> systems, Instant now) {
        AccessContext access = accessOf(inquiry, systems);
        ExcludedScope excluded = excludedScope(inquiry);
        UUID triggerId = run.getTriggerMessage().getId();
        List<InputMessage> history = projectHistory(messages.stream().map(this::input), triggerId, excluded);
        AiRunRequest request = new AiRunRequest(CONTRACT_VERSION, run.getId(), "INQUIRY_ANALYSIS", inquiry.getId(),
                run.getInputVersion(), UUID.randomUUID(), List.copyOf(targetProblemIds),
                history, problems.problems(), systems, access,
                problems.existingWorkItems(), null,
                AiRunRequest.time(now.plus(properties.deadline())), properties.limits());
        return jsonMapper.writeValueAsString(request);
    }

    /**
     * 수동 재분석 입력. 실패한 실행과 같은 문의 버전이라 대화·문제 입력은 그대로 쓰고,
     * runId·answerMessageId·expiresAt·limits는 새로 정한다. 권한 문맥·시스템 목록은 지금 값으로 다시 만든다(그사이 권한이 바뀌었을 수 있다).
     *
     * @param failed 재분석할 실패 실행 (같은 문의 버전)
     * @param run    저장해서 ID가 정해진 새 대기 실행
     */
    public String retrySnapshot(AgentRun failed, AgentRun run, Inquiry inquiry, List<SupportedSystemResponse> systems,
            Instant now) {
        ObjectNode request = (ObjectNode) jsonMapper.readTree(failed.getInputSnapshot());
        request.put("runId", run.getId().toString());
        request.put("answerMessageId", UUID.randomUUID().toString());
        request.put("expiresAt", AiRunRequest.time(now.plus(properties.deadline())));
        request.set("limits", jsonMapper.valueToTree(properties.limits()));
        request.set("systems", jsonMapper.valueToTree(systems));
        request.set("accessContext", jsonMapper.valueToTree(accessOf(inquiry, systems)));
        ExcludedScope excluded = excludedScope(inquiry);
        if (!excluded.problemIds().isEmpty()) {
            UUID triggerId = run.getTriggerMessage().getId();
            List<InputMessage> history = projectHistory(request.path("messages").valueStream()
                    .map(message -> jsonMapper.treeToValue(message, InputMessage.class)), triggerId, excluded);
            request.set("messages", jsonMapper.valueToTree(history));
            for (String field : List.of("problems", "problemIds", "existingWorkItems")) {
                String key = "problems".equals(field) ? "id" : "problemId";
                request.set(field, jsonMapper.valueToTree(request.path(field).valueStream()
                        .filter(value -> !excluded.problemIds().contains(UUID.fromString(
                                value.isString() ? value.asString() : value.path(key).asString())))
                        .toList()));
            }
        }
        return jsonMapper.writeValueAsString(request);
    }

    private record ExcludedScope(Set<UUID> problemIds, Set<UUID> answers, Set<InputRevision> triggers) {
        boolean contains(InputMessage message) {
            return answers.contains(message.id())
                    || triggers.contains(new InputRevision(message.id(), message.revision()))
                    || (!message.problemIds().isEmpty() && problemIds.containsAll(message.problemIds()));
        }
    }

    private record InputRevision(UUID id, int revision) {
    }

    private ExcludedScope excludedScope(Inquiry inquiry) {
        var excluded = inquiryProblems.findByInquiry_IdAndProblemStatusAndDeletedAtIsNull(inquiry.getId(),
                ProblemStatus.OUT_OF_SCOPE);
        var problemIds = new HashSet<UUID>();
        var answers = new HashSet<UUID>();
        var triggers = new HashSet<InputRevision>();
        var visitedRuns = new HashSet<UUID>();
        for (var problem : excluded) {
            problemIds.add(problem.getId());
            var run = problem.getAgentRun();
            if (!visitedRuns.add(run.getId()) || run.getResult() == null || run.getTriggerMessage() == null) continue;
            JsonNode result = jsonMapper.readTree(run.getResult());
            JsonNode proposals = result.path("problemProposals");
            // 단독 범위 안내의 답변과 당시 입력 차수를 제외한다. 혼합 문의의 사용자 원문은 보존한다.
            if (!proposals.isArray() || proposals.isEmpty() || !proposals.valueStream().allMatch(
                    proposal -> "OUT_OF_SCOPE".equals(proposal.path("intent").asString()))) continue;
            JsonNode input = jsonMapper.readTree(run.getInputSnapshot());
            if (input.path("answerMessageId").isString()) {
                answers.add(UUID.fromString(input.path("answerMessageId").asString()));
            }
            UUID trigger = run.getTriggerMessage().getId();
            input.path("messages").valueStream()
                    .filter(message -> trigger.toString().equals(message.path("id").asString()))
                    .findFirst().ifPresent(message -> triggers.add(new InputRevision(trigger,
                            message.path("revision").asInt())));
        }
        return new ExcludedScope(problemIds, answers, triggers);
    }

    private static List<InputMessage> projectHistory(Stream<InputMessage> messages, UUID triggerId,
            ExcludedScope excluded) {
        return messages.filter(message -> message.id().equals(triggerId) || !excluded.contains(message))
                .map(message -> projectMessage(message, excluded.problemIds()))
                .filter(message -> !message.content().isBlank()).toList();
    }

    private static InputMessage projectMessage(InputMessage message, Set<UUID> excluded) {
        if (excluded.isEmpty()) return message;
        String content = message.content();
        if (message.sender() == SenderType.AI && content.contains(SCOPE_NOTICE)) {
            content = Arrays.stream(content.split("\n\n", -1))
                    .filter(part -> !SCOPE_NOTICE.equals(part.replaceFirst("^문제 [0-9]+\n", "").strip()))
                    .collect(Collectors.joining("\n\n"));
        }
        return new InputMessage(message.id(), message.revision(), message.sequenceNo(), message.sender(), content,
                message.problemIds().stream().filter(id -> !excluded.contains(id)).toList(), message.createdAt());
    }

    AccessContext accessOf(Inquiry inquiry, List<SupportedSystemResponse> systems) {
        // 내부 판단 경계(옵션)는 서버 설정이 켰을 때만 retrievalScopes를 싣는다. 꺼져 있으면 키 자체가 없다.
        List<String> retrievalScopes = properties.internalJudgement().enabled() ? List.of("EMPLOYEE", "SYSTEM_STAFF") : null;
        return new AccessContext(inquiry.getRequester().getId(), "EMPLOYEE_HELP",
                systems.stream().map(SupportedSystemResponse::id).toList(), List.of("EMPLOYEE"),
                inquiry.getRequester().getPolicyRevision(), retrievalScopes);
    }

    // 문제 연결은 서버가 확인해 payload.problemLinks에 둔 값이다 (docs/DATA.md 11.3)
    private InputMessage input(Message message) {
        List<UUID> problemIds = jsonMapper.readValue(message.getPayload(), MessagePayload.class)
                .problemIds(message.getRevision());
        return new InputMessage(message.getId(), message.getRevision(), message.getSequenceNo(), message.getSenderType(),
                message.getContent(), problemIds, AiRunRequest.time(message.getCreatedAt()));
    }
}
