package com.hihops.hops.agent.internal;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.hihops.hops.agent.dto.AiRunRequest;
import com.hihops.hops.global.exception.BusinessException;
import com.hihops.hops.global.exception.ErrorCode;
import com.hihops.hops.knowledge.domain.AccessLevel;

/**
 * 실행 입력(accessContext)에서 확정한 읽기 범위. 지식 검색과 관계 조회가 같은 규칙을 쓴다.
 * 요청은 이 범위를 좁힐 수만 있고 넓힐 수 없다.
 */
record RunScope(Set<AccessLevel> levels, Set<UUID> systemIds, UUID memberId) {

    static RunScope of(AiRunRequest input, UUID memberId) {
        Set<AccessLevel> levels = EnumSet.noneOf(AccessLevel.class);
        for (String scope : input.accessContext().allowedDocumentScopes()) {
            switch (scope) {
                case "EMPLOYEE" -> levels.add(AccessLevel.EMPLOYEE);
                case "SYSTEM_STAFF" -> levels.add(AccessLevel.STAFF);
                default -> throw new BusinessException(ErrorCode.ACCESS_DENIED);
            }
        }
        if (levels.isEmpty()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return new RunScope(levels, new LinkedHashSet<>(input.accessContext().systemIds()), memberId);
    }

    /** 담당자 전용 자료까지 읽는 실행(옵션 프로필)인지. */
    boolean includesStaff() {
        return levels.contains(AccessLevel.STAFF);
    }

    List<String> scopeNames() {
        return levels.stream().map(level -> level == AccessLevel.STAFF ? "SYSTEM_STAFF" : "EMPLOYEE").sorted().toList();
    }
}
