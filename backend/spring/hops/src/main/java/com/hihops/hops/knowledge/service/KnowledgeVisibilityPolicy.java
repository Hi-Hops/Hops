package com.hihops.hops.knowledge.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hihops.hops.knowledge.domain.CaseVisibility;
import com.hihops.hops.knowledge.domain.ReadRule;

/**
 * 지식 근거의 공개 규칙 중 설정으로 바뀌는 부분. 지식 검색과 저장된 근거의 현재 공개 검증이 같은 값을 쓰게 한 곳에 둔다.
 * 한쪽만 다르면 검색에서 나온 근거를 티켓 생성·안내 저장 단계가 거부한다.
 */
@Component
public class KnowledgeVisibilityPolicy {
    private final CaseVisibility caseVisibility;

    public KnowledgeVisibilityPolicy(
            @Value("${hops.knowledge.case-visibility:APPROVED_SNAPSHOT}") CaseVisibility caseVisibility) {
        this.caseVisibility = caseVisibility;
    }

    public CaseVisibility caseVisibility() {
        return caseVisibility;
    }

    /** 사용자에게 닿을 수 있는 근거의 읽기 규칙(안내·티켓 근거의 현재 공개 검증 등). */
    public ReadRule userRule() {
        return ReadRule.user(caseVisibility.requiresIndexed());
    }

    /** 검색·관계 조회의 읽기 규칙. internalRead는 서버가 확정한 내부 판단 읽기일 때만 true다. */
    public ReadRule rule(boolean internalRead) {
        return internalRead ? ReadRule.internal(caseVisibility.requiresIndexed()) : userRule();
    }
}
