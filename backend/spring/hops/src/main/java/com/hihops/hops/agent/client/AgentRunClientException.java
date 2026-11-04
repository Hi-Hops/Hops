package com.hihops.hops.agent.client;

import org.jspecify.annotations.Nullable;

/**
 * AI 서버 호출이 실패한 이유. 호출 측(dispatcher)이 실행을 어떻게 끝낼지 정하는 데 필요한 값만 담는다.
 *
 * <p>{@code code}는 AI 서버가 오류 본문으로 준 AiError.code이거나, 서버 응답을 받지 못했을 때 이 클라이언트가 정한
 * SERVICE_UNAVAILABLE(연결·시간 초과)·UPSTREAM_PROTOCOL_ERROR(계약과 다른 응답)다. 모두 {@code agent_run.error_code}에
 * 그대로 남길 수 있는 AI 쪽 코드 집합이며, 공개 오류 코드로의 변환은 RunSummaryReader가 맡는다.
 */
public class AgentRunClientException extends RuntimeException {

    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String UPSTREAM_PROTOCOL_ERROR = "UPSTREAM_PROTOCOL_ERROR";

    private final String code;
    private final boolean retryable;
    private final int httpStatus;
    private final @Nullable Integer retryAfterSeconds;

    /**
     * @param httpStatus 응답을 받지 못했으면 0
     * @param retryAfterSeconds 429 응답의 Retry-After. 없으면 null
     */
    public AgentRunClientException(String code, boolean retryable, int httpStatus, @Nullable Integer retryAfterSeconds,
            String message, @Nullable Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public @Nullable Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
