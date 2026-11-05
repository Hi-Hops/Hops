package com.hihops.hops.knowledge.domain;

/**
 * 승인된 사례를 검색 저장소에 반영한 결과 (docs/ERD.md "해결 사례 resolved_case" vector_status·graph_status, 계약 IndexState).
 *
 * <p>Vector와 Neo4j 두 저장소에 각각 따로 기록한다. 둘 다 SUCCEEDED일 때만 검색에 쓰고,
 * 한쪽이라도 아니면 양쪽 검색에서 모두 제외한다. UNKNOWN은 실패도 성공도 아니다. 먼저 확인하고 새 attempt를 만들지 않는다.
 */
public enum IndexStatus {
    /** 반영 대기 (요청 보존, 아직 접수 확인 전) */
    PENDING,
    /** 원격이 진행 중 */
    RUNNING,
    /** 반영 성공 */
    SUCCEEDED,
    /** 반영 실패 */
    FAILED,
    /** 원격 상태를 확인하지 못함 */
    UNKNOWN;

    /** 아직 끝나지 않아 확인·결과 반영을 받을 수 있는 상태. SUCCEEDED·FAILED는 확정이다. */
    public boolean isOpen() {
        return this == PENDING || this == RUNNING || this == UNKNOWN;
    }
}
