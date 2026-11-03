package com.hihops.hops.agent.dto;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.hihops.hops.global.exception.ErrorResponse;

/**
 * 상담 실행 요약 (DATA_TYPES 신규 절 RunSummary). GET /runs/{runId}와 InquiryResult.currentRun에 쓴다.
 *
 * <pre>
 * { "id": "UUID", "inquiryId": "UUID", "inquiryVersion": 1, "answerMessageId": "UUID", "status": "QUEUED", "error": null }
 * </pre>
 *
 * <p>만들 때는 {@code RunSummaryReader}를 쓴다. DB 상태·오류 코드를 공개 값으로 바꾸는 규칙이 거기 있다.
 *
 * @param answerMessageId 접수 때 예약한 최종 AI 메시지 ID. 실패하면 그 메시지가 없을 수 있다
 * @param status          QUEUED·RUNNING·SUCCEEDED·FAILED·TIMED_OUT·SUPERSEDED (DB CANCELLED는 SUPERSEDED)
 * @param error           FAILED·TIMED_OUT이면 필수, 그 밖에는 null
 */
public record RunSummaryResponse(UUID id, UUID inquiryId, int inquiryVersion, UUID answerMessageId, String status,
        @Nullable ErrorResponse error) {
}
