package com.hihops.hops.knowledge.domain;

/**
 * 사례 출처 (docs/ERD.md "해결 사례 resolved_case" origin_type).
 *
 * <p>사람이 승인한 합성 사례라도 운영 사례(PRODUCTION)로 표시하지 않는다.
 */
public enum CaseOrigin {
    /** 합성(가상) 데이터 */
    SYNTHETIC,
    /** 시연에서 검증한 사례 */
    DEMO_VERIFIED,
    /** 실제 운영 사례 */
    PRODUCTION
}
