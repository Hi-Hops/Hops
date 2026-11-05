package com.hihops.hops.knowledge.service;

import java.util.UUID;

import com.hihops.hops.knowledge.domain.IndexStore;

/**
 * 저장된 사례 색인 요청을 AI에 전달하거나 확인하라는 신호. 요청을 저장한 거래가 commit된 뒤에만 처리한다.
 * 전달·확인은 그 시점의 현재 요청(jobId·attempt)과 같을 때만 반영한다.
 */
public record CaseIndexRequested(UUID caseId, IndexStore store, String jobId, int attempt, Mode mode) {
    public enum Mode {
        /** 저장한 요청을 접수시킨다 (승인 직후, 재시도 직후) */
        DELIVER,
        /** 같은 작업·attempt를 조회한다. 유효한 NOT_FOUND이면 같은 요청을 다시 접수한다 */
        CHECK
    }
}
