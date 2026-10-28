"""Intake 결과를 보고 다음 경로를 고른다. 도메인 판단은 하지 않고 Intake의 제안을 따른다.

지금은 Intake → (Ticket) → 결과 조합만 연결한다. Knowledge·Diagnosis 경로(SEARCH·DIAGNOSE)는
역할을 연결하는 후속 단계에서 추가하며, 그 전에는 건너뛰지 않고 지원하지 않는 경로로 알린다.
"""

from typing import Literal

from hops_ai.contracts.agent_1 import IntakeResult, Problem
from hops_ai.harness.errors import UnsupportedRoute

PENDING_ROLES = {"SEARCH", "DIAGNOSE"}


def next_after_intake(result: IntakeResult) -> Literal["ticket", "merge"]:
    assert result.data is not None
    actions = {problem.nextAction for problem in result.data.problems}
    pending = actions & PENDING_ROLES
    if pending:
        raise UnsupportedRoute(f"{sorted(pending)} 경로는 후속 단계에서 연결한다")
    return "ticket" if "PROPOSE_TICKETS" in actions else "merge"


def problems_for_ticket(result: IntakeResult) -> list[Problem]:
    """티켓 제안이 필요한 문제만 Ticket 역할에 넘긴다. 질문 중·범위 밖 문제는 넘기지 않는다."""
    assert result.data is not None
    return [
        Problem(
            ref=problem.ref,
            summary=problem.summary,
            category=problem.category,
            questionCount=problem.questionCount,
        )
        for problem in result.data.problems
        if problem.nextAction == "PROPOSE_TICKETS"
    ]
