"""Supervisor가 한 번의 판단에서 고를 수 있는 선택지(합법한 역할과 종료 행동)를 계산한다.

선택지는 코드가 State에서 계산하고 모델은 그 안에서만 고른다. 역할이 "이 상태에서는 호출하지
말라"고 스스로 말하는 대신 그래프가 이 모듈로 합법한 전이를 정한다. 새 역할이나 제한을
추가할 때 이 파일의 규칙 표만 고친다. 같은 State에는 항상 같은 선택지가 나온다.
"""

from collections.abc import Callable, Mapping
from dataclasses import dataclass

from hops_ai.agents.base import Agent, PendingAgent
from hops_ai.agents.inquiry.readiness import (
    all_unfinished_ready_for_handoff,
    inquiry_has_nothing_left_to_do,
)
from hops_ai.agents.knowledge.agent import guidable_problem
from hops_ai.agents.ticket.model import ModelTicketAgent
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import InquiryResult
from hops_ai.contracts.state import SupervisorState
from hops_ai.supervisor.decision import allowed_actions
from hops_ai.supervisor.policy import get_allowed_roles


@dataclass(frozen=True)
class Choices:
    """한 번의 판단에서 허용되는 선택지.

    registered: 등록돼 있고 지금 State에서 호출이 성립하는 역할(실행 종류 정책을 적용하기 전).
    roles: registered 중 이번 실행 종류에서 허용되는 역할. 모델이 고를 수 있는 역할이다.
    actions: 모델이 고를 수 있는 행동. 고를 역할이 없으면 CALL_ROLE은 빠진다.
    """

    registered: frozenset[AgentRole]
    roles: frozenset[AgentRole]
    actions: tuple[str, ...]


def implemented_roles(agents: Mapping[AgentRole, Agent]) -> set[AgentRole]:
    """아직 연결하지 않은 자리(PendingAgent)를 뺀, 실제로 호출해도 되는 역할."""
    return {role for role, agent in agents.items() if not isinstance(agent, PendingAgent)}


def _inquiry_has_work(state: SupervisorState) -> bool:
    """Inquiry를 호출하면 새로 할 일이 있는지.

    - Knowledge가 근거를 찾지 못했고 남은 사용법 문제가 모두 담당자 전달 정보를 갖췄다면,
      다시 호출해도 같은 원문에서 같은 사실을 다시 뽑을 뿐 새 질문을 만들 수 없다.
    - Inquiry가 이미 돌았고 모든 문제에 결과가 준비됐다면, 질문을 만들 문제가 없고 이미 만든
      질문과 충돌할 위험만 있다.
    - 조사·신청 등 티켓 대상의 필수 정보가 갖춰졌거나 질문 한도에 도달했다면 같은 원문을
      다시 추출하지 않고 Ticket으로 전달한다. 다른 미처리 문제가 있으면 호출을 유지한다.
    - 이번 실행의 Inquiry가 문제를 하나도 찾지 못했다면 같은 원문을 다시 분석하지 않는다.
      Supervisor가 대화 응답(REPLY)으로 사용자에게 되묻는다.
    사용자가 새 메시지로 답한 다음 실행은 새 State라서 다시 호출할 수 있다.
    """
    if state["input"].kind != RunKind.INQUIRY_ANALYSIS:
        return True
    if any(
        isinstance(result, InquiryResult) and not result.problems
        for result in state["roleResults"].values()
    ):
        return False
    return not (all_unfinished_ready_for_handoff(state) or inquiry_has_nothing_left_to_do(state))


def _knowledge_has_target(state: SupervisorState) -> bool:
    """안내할 사용법 문제가 있으면 검색할 대상이 있다. 문제가 아직 없는 첫 판단에서는 없다."""
    return state["input"].kind == RunKind.INQUIRY_ANALYSIS and guidable_problem(state) is not None


def _ticket_has_target(state: SupervisorState) -> bool:
    """티켓으로 제안할 수 있는 문제가 하나라도 있는지. 추가 질문 대기 중인 문제는 제외한다."""
    return bool(ModelTicketAgent.eligible_problems(state))


# 역할별 호출 조건. 여기에 없는 역할은 등록돼 있으면 항상 호출할 수 있다.
_ROLE_RULES: Mapping[AgentRole, Callable[[SupervisorState], bool]] = {
    AgentRole.INQUIRY: _inquiry_has_work,
    AgentRole.KNOWLEDGE: _knowledge_has_target,
    AgentRole.TICKET: _ticket_has_target,
}


def legal_roles(
    state: SupervisorState, agents: Mapping[AgentRole, Agent], *, restrict: bool = True
) -> set[AgentRole]:
    """등록된 역할 중 지금 State에서 호출이 성립하는 역할.

    restrict가 거짓이면 연결 여부와 호출 조건을 따지지 않고 등록된 역할을 모두 돌려준다. 호출해
    봐야 거부될 역할(예: 문제가 아직 없는데 검색)을 모델이 고르다 실행이 실패하는 것을 막는
    사실 판단이며, 호출이 성립하는 역할 사이의 선택은 제한하지 않는다.
    """
    registered = set(agents)
    if not restrict:
        return registered
    roles = registered & implemented_roles(agents)
    return {role for role in roles if _ROLE_RULES.get(role, lambda _state: True)(state)}


def compute_choices(
    state: SupervisorState, agents: Mapping[AgentRole, Agent], *, restrict: bool = True
) -> Choices:
    """State에서 이번 판단의 합법한 역할과 종료 행동을 계산한다."""
    registered = legal_roles(state, agents, restrict=restrict)
    roles = registered & get_allowed_roles(RunKind(state["input"].kind))
    actions = tuple(action for action in allowed_actions(state) if roles or action != "CALL_ROLE")
    return Choices(frozenset(registered), frozenset(roles), actions)
