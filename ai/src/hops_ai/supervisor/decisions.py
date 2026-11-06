"""문제별로 고른 종료 행동을 State에 확정한다. 입력 State를 수정하지 않는다.

문제별 행동 경로에서 한 문제의 결과는 그 문제가 가진 산출물이 아니라 고른 종료 행동이 정한다
(`rules.progress.get_problem_progress`). 그런데 최종 응답(`finalize`)은 State의 질문·안내·티켓을
그대로 싣고, 그 본문이 모두 답변에 들어 있어야 통과한다. 그래서 행동을 확정할 때 **고르지 않은
행동의 산출물을 그 문제에서 치운다**. 예를 들어 판정이 충분해 답변을 골랐다면 이미 만든 질문은
보내지 않는다.

확정은 산출물이 준비된 뒤에 한다. 질문 행동은 질문이, 답변은 안내가, 티켓은 티켓 제안이 있어야 한다.
대기와 범위 밖은 산출물이 없다.
"""

from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.progress import DECISION_OUTCOMES
from hops_ai.rules.proposals import validate_problem_ref
from hops_ai.supervisor.state import SupervisorState


def apply_decision(state: SupervisorState, ref: str, action: str) -> SupervisorState:
    """그 문제의 종료 행동을 확정하고 고르지 않은 행동의 산출물을 치운 새 State를 돌려준다.

    Raises:
        OutputRejected: 종료 행동이 아니거나, 이미 다른 행동을 확정했거나, 그 행동에 필요한 산출물이
            없는 경우.
    """
    if action not in DECISION_OUTCOMES:
        raise OutputRejected("종료 행동이 아니다")
    validate_problem_ref(state, ref)
    previous = state["decisions"].get(ref)
    if previous is not None and previous != action:
        raise OutputRejected("이미 다른 종료 행동을 확정한 문제다")

    guide = state["guide"]
    has_question = any(key[0] == ref for key in state["questions"])
    has_guide = guide is not None and guide.problemRef == ref
    has_ticket = ref in state["ticketProposals"]
    if (
        (action == "ASK" and not has_question)
        or (action == "ANSWER" and not has_guide)
        or (action == "TICKET" and not has_ticket)
    ):
        raise OutputRejected("고른 행동에 필요한 산출물이 아직 없다")

    questions = {
        key: item for key, item in state["questions"].items() if key[0] != ref or action == "ASK"
    }
    tickets = {
        key: item
        for key, item in state["ticketProposals"].items()
        if key != ref or action == "TICKET"
    }
    return {
        **state,
        "decisions": {**state["decisions"], ref: action},
        "questions": questions,
        "guide": guide if not has_guide or action == "ANSWER" else None,
        "ticketProposals": tickets,
    }
