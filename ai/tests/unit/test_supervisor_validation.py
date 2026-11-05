"""문제별 준비 상태와 실행 완료 조건을 검사한다. 실제 모델 판단 평가는 아니다."""

from copy import deepcopy

import pytest

from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import KnowledgeResult, SearchResult
from hops_ai.contracts.wire import AiRunRequest, EvidenceStatement
from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.progress import get_problem_progress
from hops_ai.rules.proposals import validate_statements
from hops_ai.supervisor.validation import validate_completion
from tests.supervisor_fixtures import (
    MESSAGE,
    PROBLEM,
    make_case,
    make_problem,
    make_request,
    make_ticket,
    running_state,
    uid,
)

OTHER_PROBLEM = uid(11)


def source_state(*, message_problems=None, targets=None):
    """서로 독립된 기존 문제 두 개와 서버가 정한 메시지 연결을 준비한다."""
    data = make_request().model_dump(mode="json")
    data["problems"].append({**data["problems"][0], "id": OTHER_PROBLEM})
    data["problemIds"] = [PROBLEM, OTHER_PROBLEM] if targets is None else targets
    data["messages"][0]["problemIds"] = [PROBLEM] if message_problems is None else message_problems
    return running_state(AiRunRequest.model_validate(data))


def statement(source_id=MESSAGE):
    """의미 분석을 가정하지 않고 서버가 제공한 출처 연결만 검사할 문장을 만든다."""
    return EvidenceStatement(
        text="가상 사용자가 오류 현상을 보고함",
        sourceMessageIds=[source_id],
        knowledgeEvidenceIds=[],
    )


@pytest.mark.parametrize("confirmed", [False, True])
@pytest.mark.parametrize("problem_ref", [PROBLEM, OTHER_PROBLEM])
def test_existing_problem_uses_only_its_linked_message(problem_ref, confirmed) -> None:
    """A의 진술을 B의 확인 사실이나 추정으로 복사할 수 없다."""
    state = source_state()
    before = deepcopy(state)
    if problem_ref == PROBLEM:
        validate_statements(state, [statement()], confirmed=confirmed, problem_ref=problem_ref)
    else:
        with pytest.raises(OutputRejected):
            validate_statements(state, [statement()], confirmed=confirmed, problem_ref=problem_ref)
    assert state == before


@pytest.mark.parametrize("problem_ref", [PROBLEM, OTHER_PROBLEM])
def test_explicitly_shared_message_can_support_each_linked_problem(problem_ref) -> None:
    """서버가 A와 B 모두에 연결한 복합 원문은 두 문제의 출처로 사용할 수 있다."""
    state = source_state(message_problems=[PROBLEM, OTHER_PROBLEM])
    validate_statements(state, [statement()], confirmed=True, problem_ref=problem_ref)


def test_unlinked_message_cannot_be_assigned_to_an_existing_problem_by_the_model() -> None:
    """보완 대상이 정해지지 않은 원문을 기존 문제의 사실로 자동 배포하지 않는다."""
    state = source_state(message_problems=[])
    with pytest.raises(OutputRejected):
        validate_statements(state, [statement()], confirmed=True, problem_ref=PROBLEM)


@pytest.mark.parametrize("snapshot_field", ["confirmedFacts", "assumptions"])
@pytest.mark.parametrize("problem_ref", [PROBLEM, OTHER_PROBLEM, "new:separate"])
def test_historical_snapshot_sources_belong_only_to_the_original_problem(
    snapshot_field, problem_ref
) -> None:
    """실행 범위에서 빠진 과거 출처도 다른 문제의 스냅샷을 경유해 가져올 수 없다."""
    state = source_state()
    past_id = uid(501)
    snapshot = make_ticket().standardRequestSnapshot.model_copy(
        update={snapshot_field: [statement(past_id)]}
    )
    request = state["input"]
    request = request.model_copy(
        update={
            "problems": [
                request.problems[0].model_copy(update={"standardRequest": snapshot}),
                request.problems[1],
            ]
        }
    )
    state = running_state(request)
    if problem_ref == PROBLEM:
        validate_statements(state, [statement(past_id)], confirmed=True, problem_ref=problem_ref)
    else:
        with pytest.raises(OutputRejected):
            validate_statements(
                state, [statement(past_id)], confirmed=True, problem_ref=problem_ref
            )


def test_current_message_link_overrides_an_older_snapshot_reference() -> None:
    """현재 원문이 B에 연결됐으면 A의 과거 출처 참조만으로 A에서 재사용하지 못한다."""
    state = source_state(message_problems=[OTHER_PROBLEM])
    request = state["input"]
    request = request.model_copy(
        update={
            "problems": [
                request.problems[0].model_copy(
                    update={"standardRequest": make_ticket().standardRequestSnapshot}
                ),
                request.problems[1],
            ]
        }
    )
    state = running_state(request)
    with pytest.raises(OutputRejected):
        validate_statements(state, [statement()], confirmed=True, problem_ref=PROBLEM)
    validate_statements(state, [statement()], confirmed=True, problem_ref=OTHER_PROBLEM)


@pytest.mark.parametrize(
    "links,targets,allowed",
    [
        ([], [PROBLEM], True),
        ([PROBLEM], [PROBLEM], True),
        ([OTHER_PROBLEM], [PROBLEM], False),
        ([PROBLEM, OTHER_PROBLEM], [PROBLEM], False),
        ([PROBLEM, OTHER_PROBLEM], [], True),
    ],
)
def test_new_problem_split_uses_only_unlinked_or_target_problem_messages(links, targets, allowed):
    """신규 분리는 허용하지만 참고용 비대상 문제의 원문을 새 문제에 복사하지 않는다."""
    state = source_state(message_problems=links, targets=targets)
    if allowed:
        validate_statements(state, [statement()], confirmed=True, problem_ref="new:separate")
    else:
        with pytest.raises(OutputRejected):
            validate_statements(state, [statement()], confirmed=True, problem_ref="new:separate")


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
