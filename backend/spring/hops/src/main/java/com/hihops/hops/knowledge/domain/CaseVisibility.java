package com.hihops.hops.knowledge.domain;

/**
 * 승인된 사례(CASE)를 검색 근거로 공개하는 조건. 설정 {@code hops.knowledge.case-visibility}로 고른다.
 *
 * <p>계약은 승인 사례가 Vector·Neo4j 양쪽에 반영(SUCCEEDED)돼야 검색에 쓴다고 정한다(INDEXED).
 * 1차 SQL 검색에는 실제 벡터·그래프 반영이 없으므로 승인된 스냅샷만으로 공개하는 규칙을 제안한다(APPROVED_SNAPSHOT).
 * 색인 상태는 어느 쪽에서도 SUCCEEDED로 바꾸지 않는다.
 */
public enum CaseVisibility {
    /** 1차 SQL 검색: 승인된 현재 사례 스냅샷이면 공개한다. 색인 상태는 조건이 아니다. */
    APPROVED_SNAPSHOT,
    /** 2차(계약 원안): 승인에 더해 vector_status·graph_status가 모두 SUCCEEDED여야 공개한다. */
    INDEXED;

    public boolean requiresIndexed() {
        return this == INDEXED;
    }
}
