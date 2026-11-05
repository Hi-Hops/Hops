package com.hihops.hops.knowledge.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hihops.hops.knowledge.domain.CaseVisibility;

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

    /** {@code isCurrentlyVisible}의 requireIndexed 인자. */
    public boolean requireIndexedCase() {
        return caseVisibility.requiresIndexed();
    }
}
