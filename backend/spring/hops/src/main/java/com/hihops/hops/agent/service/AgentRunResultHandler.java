package com.hihops.hops.agent.service;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.domain.AgentTask;

import tools.jackson.databind.JsonNode;

/**
 * 작업 종류(AgentTask)별로 AI 실행 결과를 업무에 반영하는 확장점.
 *
 * <p>{@link AgentRunDispatcher}는 AI 서버로의 전달·상태 폴링·실행 종료만 맡고, 결과가 무슨 뜻이고 어디에 저장되는지는
 * 이 인터페이스의 구현이 정한다. 상담 분석은 AI 답변 메시지를, 사후 지식화(CURATION)는 사례 초안을 저장하는 식으로
 * 작업 종류마다 구현을 하나 추가하면 되고 dispatcher는 바꾸지 않는다. 등록된 구현이 없는 작업 종류의 실행은
 * dispatcher가 AI에 보내지 않고 실패로 끝낸다.
 *
 * <p>두 메서드는 모두 dispatcher가 연 DB 거래 안에서 불린다. 던지면 그 거래는 롤백되고 실행은 실패로 끝난다.
 */
public interface AgentRunResultHandler {

    /** 이 구현이 맡는 작업 종류. 작업 종류마다 구현은 하나만 등록할 수 있다. */
    AgentTask task();

    /**
     * AI에 보내기 직전, 접수 때 고정한 권한 문맥이 지금도 유효한지 확인한다(문의자 본인인지, 권한 세대가 같은지 등).
     *
     * @throws AgentResultRejectedException 더는 유효하지 않으면. 실행은 AI에 보내지 않고 그 코드로 끝난다
     */
    void verifyAccess(AgentRun run, JsonNode input);

    /**
     * AI가 SUCCEEDED로 끝낸 결과를 업무에 반영한다. 호출 전에 dispatcher가 결과의 contractVersion·runId·inquiryId·
     * inquiryVersion·kind가 실행과 같은지 이미 확인했다. 최신 입력 버전인지, 허용된 식별자만 썼는지 같은 업무 검증은 여기서 한다.
     *
     * @param input AI에 보낸 AiRunRequest(agent_run.input_snapshot)
     * @param result AiRunSnapshot.result(AiResult)
     * @return agent_run.validation_result에 남길 JSON 문자열. 검증 내용이 없으면 "{}"
     * @throws AgentResultRejectedException 결과를 반영할 수 없을 때. 늦게 도착한 이전 실행이면 코드 SUPERSEDED
     */
    String apply(AgentRun run, JsonNode input, JsonNode result);
}
