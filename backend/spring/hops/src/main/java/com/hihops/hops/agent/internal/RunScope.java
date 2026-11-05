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
 *
 * <p>기본 프로필은 allowedDocumentScopes를 읽기 범위로 쓴다. **내부 판단 경계(옵션)**는 서버 설정이 켜져 있고 실행 입력에
 * retrievalScopes가 있을 때만 그 범위로 읽으며, 이때 담당자 전용 자료를 주담당자 조건 없이 읽는다(internalRead).
 * 설정이 꺼져 있으면 실행 입력에 retrievalScopes가 남아 있어도 무시한다(설정이 꺼진 뒤 이어지는 실행도 기본 프로필로 읽는다).
 */
record RunScope(Set<AccessLevel> levels, Set<UUID> systemIds, UUID memberId, boolean internalRead) {

    static RunScope of(AiRunRequest input, UUID memberId, boolean internalJudgementEnabled) {
        List<String> allowed = input.accessContext().allowedDocumentScopes();
        List<String> retrieval = input.accessContext().retrievalScopes();
        boolean option = internalJudgementEnabled && retrieval != null;
        if (option && !retrieval.containsAll(allowed)) {
            // 읽기 범위는 사용자에게 닿을 수 있는 범위를 줄이지 못한다. 어긋난 입력은 받지 않는다.
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        Set<AccessLevel> levels = EnumSet.noneOf(AccessLevel.class);
        for (String scope : option ? retrieval : allowed) {
            switch (scope) {
                case "EMPLOYEE" -> levels.add(AccessLevel.EMPLOYEE);
                case "SYSTEM_STAFF" -> levels.add(AccessLevel.STAFF);
                default -> throw new BusinessException(ErrorCode.ACCESS_DENIED);
            }
        }
        if (levels.isEmpty()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        boolean internalRead = option && retrieval.contains("SYSTEM_STAFF") && !allowed.contains("SYSTEM_STAFF");
        return new RunScope(levels, new LinkedHashSet<>(input.accessContext().systemIds()), memberId, internalRead);
    }

    /** 담당자 전용 자료까지 읽는 실행(옵션 프로필 또는 담당자 범위)인지. */
    boolean includesStaff() {
        return levels.contains(AccessLevel.STAFF);
    }

    List<String> scopeNames() {
        return levels.stream().map(level -> level == AccessLevel.STAFF ? "SYSTEM_STAFF" : "EMPLOYEE").sorted().toList();
    }
}
