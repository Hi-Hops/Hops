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
from hops_ai.supervisor.validation import confirmed_courtesy, reply_allowed


def prepare_decision(state: SupervisorState, ref: str, action: str) -> SupervisorState:
    """고르지 않은 행동의 산출물을 그 문제에서 치운다. 행동은 아직 확정하지 않는다.

    질문과 티켓은 같은 문제에 안내·다른 산출물이 있으면 병합 검증이 거부하므로, 그 행동의 역할을
    부르기 전에 먼저 치워야 한다. 확정(`apply_decision`)은 역할이 산출물을 만든 뒤에 한다.
    """
    if action not in DECISION_OUTCOMES:
        raise OutputRejected("종료 행동이 아니다")
    validate_problem_ref(state, ref)
    guide = state["guide"]
    has_guide = guide is not None and guide.problemRef == ref
    return {
        **state,
        "questions": {
            key: item
            for key, item in state["questions"].items()
            if key[0] != ref or action == "ASK"
        },
        "guide": guide if not has_guide or action == "ANSWER" else None,
        "ticketProposals": {
            key: item
            for key, item in state["ticketProposals"].items()
            if key != ref or action == "TICKET"
        },
    }


def apply_reply(state: SupervisorState) -> SupervisorState:
    """문의 분석 없이 대화로 답하는 실행 단위 종료 행동(REPLY)을 확정한다.

    응답 종류는 모델이 아니라 코드가 정한다. 분석하지 않은 메시지가 확인된 인사·감사 표현뿐이면
    그 종류(GREETING/THANKS)이고, 아니면 되묻기(CLARIFY)다. 문제별 행동이 아니라서 `decisions`를
    쓰지 않고, 역할 단위 경로와 같이 `nextAction`·`replyKind`로 기록한다. 최종화가 이를 읽어
    고정 문구로 답한다.

    Raises:
        OutputRejected: 질문·안내·티켓 제안이 있거나 Inquiry가 문제를 찾은 상담이라 대화 응답으로
            끝낼 수 없는 경우.
    """
    if not reply_allowed(state):
        raise OutputRejected("대화 응답으로 끝낼 수 없는 상담이다")
    return {
        **state,
        "nextAction": "REPLY",
        "replyKind": confirmed_courtesy(state) or "CLARIFY",
    }


def apply_decision(state: SupervisorState, ref: str, action: str) -> SupervisorState:
    """그 문제의 종료 행동을 확정하고 고르지 않은 행동의 산출물을 치운 새 State를 돌려준다.

    Raises:
        OutputRejected: 종료 행동이 아니거나, 이미 다른 행동을 확정했거나, 그 행동에 필요한 산출물이
            없는 경우.
    """
    previous = state["decisions"].get(ref)
    if previous is not None and previous != action:
        raise OutputRejected("이미 다른 종료 행동을 확정한 문제다")
    pruned = prepare_decision(state, ref, action)
    guide = pruned["guide"]
    if (
        (action == "ASK" and not any(key[0] == ref for key in pruned["questions"]))
        or (action == "ANSWER" and not (guide is not None and guide.problemRef == ref))
        or (action == "TICKET" and ref not in pruned["ticketProposals"])
    ):
        raise OutputRejected("고른 행동에 필요한 산출물이 아직 없다")
    return {**pruned, "decisions": {**state["decisions"], ref: action}}
