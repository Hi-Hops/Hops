package com.hihops.hops.global.exception;

import java.util.Arrays;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * API 공통 오류 코드 목록. 각 항목은 HTTP 상태, 기본 안내 문구, 재시도 가능 여부를 가진다.
 *
 * <p>상태 구분은 docs/API.md §1을 따른다: 400 입력, 401 로그인, 403 권한, 404 없음, 409 충돌, 410 결과 만료, 429 제한, 503 일시 장애.
 * 405·415는 HTTP 표준 오류를 500으로 잘못 응답하지 않으려고, 500은 예상하지 못한 오류용으로 추가했다.
 *
 * <p>retryable은 같은 요청을 다시 보내면 성공할 수 있는 일시 오류(429, 503)에만 true로 둔다.
 * 기능에서 새 오류가 필요하면 여기에 항목을 추가한다 (예: INQUIRY_VERSION_CONFLICT).
 */
public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다.", false),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.", false),
    // 서명은 맞지만 만료된 Access. 프론트는 refresh 1회 후 같은 요청을 다시 보낸다 (WIRE_CONTRACT 12.3)
    ACCESS_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인 시간이 만료되었습니다.", false),
    // Refresh 쿠키가 없거나, 이미 쓰였거나, 로그아웃으로 폐기됨 → 프론트는 재로그인 (WIRE_CONTRACT 10절)
    REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요.", false),
    // 소속·역할이 바뀌어 기존 권한이 회수됨 → 재로그인 (WIRE_CONTRACT 12.3: 403)
    AUTH_CONTEXT_CHANGED(HttpStatus.FORBIDDEN, "소속 또는 권한이 변경되었습니다. 다시 로그인해 주세요.", false),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.", false),
    // 허용하지 않은 주소(Origin)에서 refresh·logout을 보냄. 다른 사이트의 몰래 요청을 막는다 (WIRE_CONTRACT 12.1)
    ORIGIN_DENIED(HttpStatus.FORBIDDEN, "허용되지 않은 주소에서 보낸 요청입니다.", false),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다.", false),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다.", false),
    CONFLICT(HttpStatus.CONFLICT, "다른 요청과 충돌했습니다. 최신 내용을 다시 불러와 주세요.", false),
    // 같은 Idempotency-Key로 다른 메서드·경로·본문을 보냄. 같은 키는 한 가지 업무 의도에만 쓴다 (WIRE_CONTRACT 12.1)
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "이미 다른 요청에 사용한 요청 키입니다.", false),
    // 같은 키의 상세 결과 보존 기간(24시간)이 지남. 새 키로 자동 재실행하지 않는다 (WIRE_CONTRACT 12.1, 12.3)
    OPERATION_RESULT_EXPIRED(HttpStatus.GONE, "요청 결과의 확인 기간이 지났습니다. 최신 내용을 다시 불러와 주세요.", false),
    // 보낸 expectedInquiryVersion이 현재 문의 버전과 다름. 최신 문의·대화를 다시 읽고 새 requestId로 보낸다 (WIRE_CONTRACT 10절)
    INQUIRY_VERSION_CONFLICT(HttpStatus.CONFLICT, "문의 내용이 바뀌었습니다. 최신 내용을 확인해 주세요.", false),
    // 보낸 expectedMessageRevision이 메시지의 현재 차수와 다름. 최신 원문과 비교한 뒤 다시 정정한다 (WIRE_CONTRACT 12.3)
    MESSAGE_REVISION_CONFLICT(HttpStatus.CONFLICT, "메시지가 이미 바뀌었습니다. 최신 원문과 비교해 주세요.", false),
    // 현재 실행이 아니거나 재분석할 수 없는 상태. 최신 실행 상태를 다시 조회한다 (WIRE_CONTRACT 10절)
    RUN_NOT_RETRYABLE(HttpStatus.CONFLICT, "지금은 다시 분석할 수 없습니다. 최신 상태를 확인해 주세요.", false),
    // 대상의 현재 상태에서 허용하지 않는 조작. 최신 상태를 다시 읽고 허용된 조작만 제공한다 (WIRE_CONTRACT 12.3)
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서는 요청을 처리할 수 없습니다.", false),
    TICKET_REVISION_CONFLICT(HttpStatus.CONFLICT, "티켓이 변경되었습니다. 최신 내용을 확인해 주세요.", false),
    // 보낸 expectedCaseVersion이 사례의 현재 버전과 다름. 최신 사례를 다시 읽고 비교한 뒤 새 requestId로 보낸다 (WIRE_CONTRACT 12.3)
    CASE_VERSION_CONFLICT(HttpStatus.CONFLICT, "사례가 변경되었습니다. 최신 내용을 확인해 주세요.", false),
    RESOLUTION_REVISION_CONFLICT(HttpStatus.CONFLICT, "이미 응답했거나 확인 요청이 변경되었습니다.", false),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 본문 형식입니다.", false),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.", true),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.", false),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.", true),
    // 아래는 실패한 AI 실행을 공개 Run 오류로 바꿀 때 쓴다 (WIRE_CONTRACT 12.8 끝). retryable=true는 수동 재분석을 제공한다는 뜻이다.
    // fromStatus(503)이 SERVICE_UNAVAILABLE을 고르도록 같은 상태 항목은 그 뒤에 둔다.
    AI_INVALID_OUTPUT(HttpStatus.SERVICE_UNAVAILABLE, "AI 응답을 확인하지 못했습니다. 다시 분석해 주세요.", true),
    MODEL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 모델을 일시적으로 사용할 수 없습니다. 잠시 후 다시 분석해 주세요.", true),
    SEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "자료 검색을 일시적으로 사용할 수 없습니다. 잠시 후 다시 분석해 주세요.", true),
    AI_RUN_STATE_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE, "분석 결과를 확인하지 못했습니다. 다시 분석해 주세요.", true),
    DEADLINE_EXCEEDED(HttpStatus.GATEWAY_TIMEOUT, "분석 시간이 초과되었습니다. 다시 분석해 주세요.", true);

    private final HttpStatus status;
    private final String message;
    private final boolean retryable;

    ErrorCode(HttpStatus status, String message, boolean retryable) {
        this.status = status;
        this.message = message;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }

    public boolean retryable() {
        return retryable;
    }

    /**
     * Spring이 정한 HTTP 상태(예: 없는 경로 404)를 공통 오류 코드로 바꾼다. {@link GlobalExceptionHandler}에서 사용한다.
     * 목록에 없는 상태는 400대면 INVALID_INPUT, 그 외는 INTERNAL_ERROR로 처리한다.
     */
    public static ErrorCode fromStatus(HttpStatusCode statusCode) {
        return Arrays.stream(values())
                .filter(code -> code.status.value() == statusCode.value())
                .findFirst()
                .orElse(statusCode.is4xxClientError() ? INVALID_INPUT : INTERNAL_ERROR);
    }
}
