"""Intake 결과를 보고 다음 경로를 고른다. 라우팅 방식은 Router 인터페이스 뒤에 둔다.

- RuleRouter(기본): 역할이 구조화해 낸 제안(nextAction)을 전이표로 따른다. 같은 입력이면 같은
  경로이고 모델 호출을 쓰지 않는다. 도메인 판단은 하지 않는다.
- LLM 기반 라우터는 같은 인터페이스로 후속 단계에서 추가해 평가에서 비교한다. 어떤 구현이든
  고른 경로는 전이표(허용된 다음 단계)로 검증해야 한다.

지금은 Intake → (Ticket) → 결과 조합만 연결한다. Knowledge·Diagnosis 경로(SEARCH·DIAGNOSE)는
역할을 연결하는 후속 단계에서 추가하며, 그 전에는 건너뛰지 않고 지원하지 않는 경로로 알린다.
"""

from typing import Literal, Protocol, runtime_checkable

from hops_ai.contracts.agent_1 import IntakeResult, Problem
from hops_ai.harness.errors import UnsupportedRoute
from hops_ai.services.shared import SharedServices

Route = Literal["ticket", "merge"]
PENDING_ACTIONS = {"SEARCH", "DIAGNOSE"}


@runtime_checkable
class Router(Protocol):
    async def next_after_intake(self, result: IntakeResult, services: SharedServices) -> Route:
        """Intake 결과 다음에 갈 단계. 전이표에 없는 단계는 돌려주지 않는다."""
        ...


class RuleRouter:
    async def next_after_intake(self, result: IntakeResult, services: SharedServices) -> Route:
        assert result.data is not None
        actions = {problem.nextAction for problem in result.data.problems}
        pending = actions & PENDING_ACTIONS
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
