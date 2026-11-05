"""문제당 질문 한도의 경계. 한도 숫자는 contracts.types.QUESTION_LIMIT 하나가 정본이다."""

import pytest

from hops_ai.agents.inquiry.forms import MAX_QUESTIONS_PER_PROBLEM
from hops_ai.contracts.types import QUESTION_LIMIT
from hops_ai.contracts.wire import QuestionProposal
from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.progress import get_problem_progress
from hops_ai.supervisor.decision import _decision_context
from hops_ai.supervisor.validation import validate_questions
from tests.supervisor_fixtures import PROBLEM, make_request, running_state


def state_with_count(count: int, *, system_known: bool = True):
    state = running_state(make_request(question_count=count))
    if system_known:
        return state
    problem = state["problems"][PROBLEM].model_copy(update={"reportedSystemCandidateId": None})
    return {**state, "problems": {PROBLEM: problem}}


def with_question(state):
    question = QuestionProposal(
        problemRef=PROBLEM,
        bundleRef="q-1",
        text="언제부터 그랬나요?",
        requestedFields=["occurredAt"],
    )
    return {**state, "questions": {(PROBLEM, "q-1"): question}}


def test_the_limit_has_a_single_source() -> None:
    assert QUESTION_LIMIT == MAX_QUESTIONS_PER_PROBLEM == 5


@pytest.mark.parametrize(("count", "remaining"), [(0, 5), (4, 1), (5, 0)])
def test_remaining_questions_follow_the_limit(count, remaining) -> None:
    state = state_with_count(count)

    [progress] = get_problem_progress(state)
    assert progress["remainingQuestions"] == remaining
    [problem] = _decision_context(state)["problems"]
    assert problem["remainingQuestions"] == remaining


def test_a_question_is_accepted_below_the_limit_and_rejected_at_it() -> None:
    validate_questions(with_question(state_with_count(QUESTION_LIMIT - 1)))

    with pytest.raises(OutputRejected, match="질문 한도"):
        validate_questions(with_question(state_with_count(QUESTION_LIMIT)))


def test_unknown_system_waits_only_after_the_limit() -> None:
    below = get_problem_progress(state_with_count(QUESTION_LIMIT - 1, system_known=False))
    at_limit = get_problem_progress(state_with_count(QUESTION_LIMIT, system_known=False))

    assert below[0]["outcome"] is None
    assert at_limit[0]["outcome"] == "WAITING_SYSTEM"
