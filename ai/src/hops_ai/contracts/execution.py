"""AI 실행 종류. 값은 에이전트 계약의 S2·S4를 따른다."""

from enum import StrEnum


class RunKind(StrEnum):
    """AI가 이번 실행에서 수행할 작업을 구분한다.

    상담과 사례 초안은 사용하는 입력과 호출 가능한 에이전트가 다르다.
    Spring에서 받은 실행 종류를 입력 검증 과정에서 이 타입으로 변환해 사용한다.
    """

    INQUIRY_ANALYSIS = "INQUIRY_ANALYSIS"  # 문의·추가 응답·정정 분석
    CASE_DRAFT = "CASE_DRAFT"  # 저장된 처리 기록으로 사례 초안 생성
