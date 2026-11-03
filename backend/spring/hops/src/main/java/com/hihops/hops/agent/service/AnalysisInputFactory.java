package com.hihops.hops.agent.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.dto.AiRunRequest;
import com.hihops.hops.agent.dto.AiRunRequest.AccessContext;
import com.hihops.hops.agent.dto.AiRunRequest.InputMessage;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.itsystem.dto.SupportedSystemResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

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

    private final AnalysisRunProperties properties;
    private final JsonMapper jsonMapper;

    /**
     * @param run      저장해서 ID가 정해진 대기 실행
     * @param messages AI에 보여 줄 저장된 메시지 (순번 순)
     * @param systems  지원 시스템 목록 (GET /systems와 같은 값)
     * @param now      접수 시각. expiresAt = now + deadline
     */
    public String snapshot(AgentRun run, Inquiry inquiry, List<Message> messages, List<SupportedSystemResponse> systems,
            Instant now) {
        AccessContext access = new AccessContext(inquiry.getRequester().getId(), "EMPLOYEE_HELP",
                systems.stream().map(SupportedSystemResponse::id).toList(), List.of("EMPLOYEE"),
                inquiry.getRequester().getPolicyRevision());
        AiRunRequest request = new AiRunRequest(CONTRACT_VERSION, run.getId(), "INQUIRY_ANALYSIS", inquiry.getId(),
                run.getInputVersion(), UUID.randomUUID(), List.of(), messages.stream().map(AnalysisInputFactory::input).toList(),
                List.of(), systems, access, List.of(), null, AiRunRequest.time(now.plus(properties.deadline())),
                properties.limits());
        return jsonMapper.writeValueAsString(request);
    }

    // 문제 연결은 아직 투영하지 않는다. 첫 메시지는 원래 연결된 문제가 없다.
    private static InputMessage input(Message message) {
        return new InputMessage(message.getId(), message.getRevision(), message.getSequenceNo(), message.getSenderType(),
                message.getContent(), List.of(), AiRunRequest.time(message.getCreatedAt()));
    }
}
