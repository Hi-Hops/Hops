"""한 문의의 분석 입력과 결과. 단일 에이전트와 Supervisor가 같은 경계를 쓴다.

서비스 간 계약(AiRequest·AiResult)과는 api/mappers.py에서 변환한다. 같은 입력과 같은 결과 형태를
쓰기 때문에 단일 에이전트와 멀티 에이전트를 같은 조건으로 비교할 수 있다.
"""

from dataclasses import dataclass

from hops_ai.contracts.agent_1 import (
    AgentError,
    Department,
    Evidence,
    IntakeProblem,
    Message,
    Problem,
    Ref,
    ResultStatus,
    TicketProposal,
    WorkItem,
)


@dataclass(frozen=True)
class AnalysisInput:
    run_id: str
    inquiry_id: str
    inquiry_version: int
    messages: list[Message]
    existing_problems: list[Problem]
    existing_work_items: list[WorkItem]
    departments: list[Department]
    evidence: list[Evidence]  # 입력으로 받은 근거. 모델은 이 안의 id만 가리킬 수 있다
    resolution_targets: list[Ref]


@dataclass(frozen=True)
class AnalysisOutcome:
    """분석이 끝났다는 뜻이다. 티켓 저장·배정 성공이 아니다."""

    answer: str
    problems: list[IntakeProblem]
    ticket_proposals: list[TicketProposal]
    evidence: list[Evidence]


@dataclass(frozen=True)
class AnalysisFailure:
    status: ResultStatus
    error: AgentError


AnalysisResult = AnalysisOutcome | AnalysisFailure
