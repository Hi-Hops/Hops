package com.hihops.hops.knowledge.domain;

/**
 * 지식 문서·근거를 읽을 때 적용하는 현재 공개 규칙. boolean 두 개를 나란히 넘기다 순서를 바꾸는 실수를 막으려고 이름 있는 값으로 묶는다.
 *
 * <p>requireIndexedCase: 승인된 사례(CASE)에 Vector·Neo4j 반영 SUCCEEDED를 요구할지(설정 hops.knowledge.case-visibility).
 * internalRead: 내부 판단 읽기(옵션 프로필)이면 담당자 전용 문서의 주담당자 조건을 적용하지 않는다.
 * **사용자에게 닿는 근거에는 internalRead=true를 쓰지 않는다.** 그래서 {@link #user}가 기본 진입점이다.
 */
public record ReadRule(boolean requireIndexedCase, boolean internalRead) {

    /** 사용자에게 닿을 수 있는 근거의 규칙. 담당자 전용 문서는 해당 시스템 주담당자에게만 공개한다. */
    public static ReadRule user(boolean requireIndexedCase) {
        return new ReadRule(requireIndexedCase, false);
    }

    /** 내부 판단 읽기의 규칙. 모델 입력으로만 쓰며 사용자 응답·저장 근거에 쓰지 않는다. */
    public static ReadRule internal(boolean requireIndexedCase) {
        return new ReadRule(requireIndexedCase, true);
    }
}
