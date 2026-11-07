"""Curator 평가 실행의 기록. 검사기는 이 기록만 보고 판정한다."""

from dataclasses import dataclass

from hops_ai.contracts.wire import AiResult, AiRunRequest


@dataclass(frozen=True)
class CaseTrace:
    """사례 초안 실행 한 번의 기록.

    request는 하네스(Spring 대역)가 만든 CASE_DRAFT 입력이고, result는 그 실행의 최종 결과다.
    결과 없이 예외로 끝났다면 result가 None이고 error에 예외 이름이 있다.
    """

    scenario_id: str
    request: AiRunRequest
    result: AiResult | None
    model_calls: int
    # 공급자가 보고한 토큰 합계. 실패한 원격 호출의 미보고 사용량은 포함하지 않는다.
    input_tokens: int = 0
    output_tokens: int = 0
    elapsed_seconds: float = 0.0
    error: str | None = None
    # 역할이 하네스 오류로 실패했다면 그 사유 문구(200자까지). 어느 검증이 거부했는지 구분하려고
    # 남긴다.
    failure_detail: str | None = None
