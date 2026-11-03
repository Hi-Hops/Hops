"""문제별 준비 상태와 실행 완료 조건을 검사한다. 실제 모델 판단 평가는 아니다."""

from copy import deepcopy

import pytest

from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import KnowledgeResult, SearchResult
from hops_ai.harness.errors import OutputRejected
from hops_ai.supervisor.validation import get_problem_progress, validate_completion
from tests.supervisor_fixtures import (
    PROBLEM,
    make_case,
    make_problem,
    make_request,
    make_ticket,
    running_state,
    uid,
)


def test_progress_is_derived_without_mutating_or_copying_private_content() -> None:
    """없는 상세 정보와 미확인 항목을 구분하되 그 원문을 판단 입력으로 복사하지 않는다."""
    state = running_state()
    empty = get_problem_progress(state)[0]
    assert empty["outcome"] is None
    assert empty["availableDetails"] == []
    state["problems"][PROBLEM] = make_problem()
    before = deepcopy(state)
    progress = get_problem_progress(state)[0]
    assert progress["unknownCount"] == 1
    assert progress["availableDetails"] == ["taskOrScreen", "actualBehavior"]
    assert progress["remainingQuestions"] == 5
    assert "unknowns" not in progress and "details" not in progress
    assert state == before


@pytest.mark.parametrize("outcome", ["PENDING", "STORED", "UNKNOWN"])
def test_existing_work_is_an_announcable_result_without_recreating_it(outcome) -> None:
    state = running_state(make_request(outcome=outcome))
    validate_completion(state)
    progress = get_problem_progress(state)[0]
    assert progress["outcome"] == "EXISTING_WORK"
    assert progress["existingWorkOutcome"] == outcome


@pytest.mark.parametrize("outcome", [None, "NOT_CREATED"])
def test_no_work_or_not_created_does_not_complete_an_unhandled_problem(outcome) -> None:
    with pytest.raises(OutputRejected):
        validate_completion(running_state(make_request(outcome=outcome)))


@pytest.mark.parametrize("system_known", [False, True])
def test_question_limit_allows_only_unknown_system_to_wait(system_known) -> None:
    state = running_state(make_request(question_count=5))
    if not system_known:
        state["problems"][PROBLEM] = state["problems"][PROBLEM].model_copy(
            update={"reportedSystemCandidateId": None}
        )
        validate_completion(state)
        assert get_problem_progress(state)[0]["outcome"] == "WAITING_SYSTEM"
    else:
        # 시스템이 확인됐다면 질문 소진만으로 담당자 전달 준비를 생략할 수 없다.
        with pytest.raises(OutputRejected):
            validate_completion(state)


def test_unknown_system_before_limit_needs_a_question_or_other_result() -> None:
    state = running_state()
    state["problems"][PROBLEM] = state["problems"][PROBLEM].model_copy(
        update={"reportedSystemCandidateId": None}
    )
    with pytest.raises(OutputRejected):
        validate_completion(state)


@pytest.mark.parametrize("status", ["IN_SERVICE", "WAITING_CONFIRMATION", "RESOLVED"])
def test_existing_server_state_can_be_announced_without_claiming_a_new_transition(status) -> None:
    request = make_request()
    request = request.model_copy(
        update={"problems": [request.problems[0].model_copy(update={"state": status})]}
    )
    state = running_state(request)
    validate_completion(state)
    assert get_problem_progress(state)[0]["outcome"] == "EXISTING_STATE"


def test_scope_notice_and_investigation_with_unknown_cause_can_complete() -> None:
    state = running_state()
    state["problems"][PROBLEM] = make_problem().model_copy(update={"intent": "OUT_OF_SCOPE"})
    validate_completion(state)
    assert get_problem_progress(state)[0]["outcome"] == "OUT_OF_SCOPE"
    state["problems"][PROBLEM] = make_problem()
    state["ticketProposals"][PROBLEM] = make_ticket()
    validate_completion(state)
    assert get_problem_progress(state)[0]["outcome"] == "PROPOSE_TICKET"
    assert get_problem_progress(state)[0]["unknownCount"] == 1


def test_new_unhandled_problem_blocks_completion_but_unrelated_context_does_not() -> None:
    request = make_request(outcome="STORED")
    unrelated = uid(999)
    request = request.model_copy(
        update={
            "problems": [
                *request.problems,
                request.problems[0].model_copy(update={"id": unrelated}),
            ]
        }
    )
    state = running_state(request)
    assert [item["problemRef"] for item in get_problem_progress(state)] == [PROBLEM]
    state["problems"]["new:second"] = make_problem("new:second")
    with pytest.raises(OutputRejected):
        validate_completion(state)


def test_empty_target_set_and_missing_target_are_not_successful_results() -> None:
    state = running_state()
    state["problems"] = {}
    with pytest.raises(OutputRejected):
        validate_completion(state)
    state["input"] = state["input"].model_copy(update={"problemIds": [], "problems": []})
    with pytest.raises(OutputRejected):
        validate_completion(state)


def test_empty_search_alone_does_not_complete_a_problem() -> None:
    state = running_state()
    state["roleResults"]["k1"] = KnowledgeResult(
        invocationId="k1", search=SearchResult(state="EMPTY", evidence=[], error=None), guide=None
    )
    with pytest.raises(OutputRejected):
        validate_completion(state)


@pytest.mark.parametrize("reusable", [False, True])
def test_case_completion_requires_an_explicit_curator_result(reusable) -> None:
    state = running_state(make_request(kind=RunKind.CASE_DRAFT))
    with pytest.raises(OutputRejected):
        validate_completion(state)
    state["caseProposal"] = make_case(reusable=reusable)
    validate_completion(state)
