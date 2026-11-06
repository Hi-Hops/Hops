package com.hihops.hops.knowledge.dto;

import java.util.UUID;

/**
 * Publication (DATA_TYPES 신규 절). state는 승인·두 반영 성공의 전역 사실(색인 반영 상태), canSearch는 현재 사용자 권한까지 본 결과.
 *
 * <p>가산 필드(하위 호환): searchRule은 지식 검색이 사례를 공개하는 규칙(hops.knowledge.case-visibility)이고,
 * visibleToSearch는 그 규칙에서 이 사용자 권한으로 지금 지식 검색에 나오는지다. 1차 SQL 검색(APPROVED_SNAPSHOT)에서는
 * 색인 전이라 state=PRIVATE인 승인 사례도 visibleToSearch=true일 수 있다. 2차(INDEXED)에서는 state와 같은 조건이 된다.
 */
public record PublicationResponse(UUID caseId, int caseVersion, String state, boolean canSearch, String searchRule,
        boolean visibleToSearch) {
}
