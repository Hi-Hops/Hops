"""단일 에이전트 그래프의 상태. decide가 정한 행동을 실행 노드가 처리하고 결과가 쌓인다."""

from typing import TypedDict

from hops_ai.agents.baseline.decision import Decision
from hops_ai.contracts.agent_1 import Evidence
from hops_ai.contracts.analysis import AnalysisInput, AnalysisOutcome


class BaselineState(TypedDict, total=False):
    data: AnalysisInput
    decision: Decision
    observations: list[str]  # 검색·도구 결과를 모델에게 다시 보여 줄 문장들
    evidence: list[Evidence]  # 검색과 도구 조회에서 모은 근거
    performed: list[str]  # 이미 실행한 행동. 같은 요청을 반복해서 실행하지 않는다
    outcome: AnalysisOutcome
