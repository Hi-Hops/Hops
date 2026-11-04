package com.hihops.hops.agent.service;

/**
 * 결과 처리기가 실행을 AI에 보내지 않기로 했거나 결과를 업무에 반영하지 않기로 했음을 알린다.
 *
 * <p>code는 agent_run.error_code에 그대로 남는 AI 쪽 오류 코드다(AUTH_CONTEXT_INVALID·INVALID_OUTPUT·SUPERSEDED 등).
 * SUPERSEDED면 실행은 FAILED가 아니라 CANCELLED(공개 상태 SUPERSEDED)로 끝난다.
 */
public class AgentResultRejectedException extends RuntimeException {

    public static final String SUPERSEDED = "SUPERSEDED";

    private final String code;

    public AgentResultRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
