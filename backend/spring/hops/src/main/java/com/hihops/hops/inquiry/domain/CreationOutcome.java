package com.hihops.hops.inquiry.domain;

/**
 * 문제의 티켓 생성 작업 결과 (docs/ERD.md 6.1절 inquiry_problem.creation_outcome). 작업이 없으면 null이다.
 *
 * <p>UNKNOWN(저장됐는지 모름)을 실패로 단정하지 않는다. 시간 초과·404만으로 NOT_CREATED를 만들지 않고 먼저 저장 결과를 확인한다.
 * STORED일 때만 같은 문제의 티켓이 있다.
 */
public enum CreationOutcome {
    /** 생성 진행 중 */
    PENDING,
    /** 티켓 저장 확인 */
    STORED,
    /** 만들지 않았음이 서버에서 확인됨 (같은 문제로 다시 시도할 수 있다) */
    NOT_CREATED,
    /** 저장 여부를 아직 모름 */
    UNKNOWN
}
