package com.hihops.hops.knowledge.domain;

/**
 * 승인된 사례를 검색 저장소에 반영한 결과 (docs/ERD.md "해결 사례 resolved_case" vector_status·graph_status).
 *
 * <p>Vector와 Neo4j 두 저장소에 각각 따로 기록한다. 둘 다 SUCCEEDED일 때만 검색에 쓰고,
 * 한쪽이라도 FAILED면 양쪽 검색에서 모두 제외한다. 실패를 성공으로 바꾸지 않는다.
 */
public enum IndexStatus {
    /** 반영 대기 */
    PENDING,
    /** 반영 성공 */
    SUCCEEDED,
    /** 반영 실패 */
    FAILED
}
