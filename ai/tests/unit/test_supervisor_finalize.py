"""최종 응답 본문·종료 고정·실패 결과와 상담/사례 분리 검증."""

from copy import deepcopy

import pytest
from pydantic import ValidationError

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import (
    CuratorResult,
    InquiryResult,
    KnowledgeResult,
    SearchResult,
    TicketResult,
)
from hops_ai.contracts.wire import AiResult, GuideProposal, QuestionProposal
from hops_ai.harness.errors import OutputRejected
from hops_ai.supervisor.finalize import compose_public_answer, finalize_result
from hops_ai.supervisor.merge import (
    merge_curator_result,
    merge_inquiry_result,
    merge_knowledge_result,
    merge_ticket_result,
)
from hops_ai.supervisor.state import transition_phase
from tests.supervisor_fixtures import (
    EVIDENCE,
    OTHER_SYSTEM,
    PROBLEM,
    SYSTEM,
    authorize,
    make_case,
    make_evidence,
    make_problem,
    make_request,
    make_ticket,
    reserve,
    running_state,
)


def with_question():
    """추가 질문을 반영한 상태를 만든다. 서버의 질문 횟수는 증가시키지 않는다."""
    question = QuestionProposal(
        problemRef=PROBLEM,
        bundleRef="q1",
        text="오류 문구를 알려 주세요",
        requestedFields=["actualBehavior"],
    )
    state = merge_inquiry_result(
        reserve(running_state(make_request(question_count=4)), AgentRole.INQUIRY, "i1"),
        InquiryResult(invocationId="i1", problems=[make_problem()], questions=[question]),
    )
    return state, question


def with_guide():
    """허용된 직원 가이드의 검색·병합을 마친 상태를 만든다."""
    evidence = make_evidence()
    guide = GuideProposal(
        problemRef=PROBLEM, messageText="파일 열람 권한을 확인해 주세요", evidenceIds=[EVIDENCE]
    )
    state = merge_knowledge_result(
        reserve(running_state(), AgentRole.KNOWLEDGE, "k1"),
        KnowledgeResult(
            invocationId="k1",
            search=SearchResult(state="FOUND", evidence=[evidence], error=None),
            guide=guide,
        ),
        authorized_refs=[authorize(evidence)],
        verified_evidence=[evidence],
    )
    return state, guide


def test_question_answer_is_built_once_without_counting_it_as_stored() -> None:
    """질문만 있어도 최종 본문을 만들되 서버 질문 횟수는 바꾸지 않는다."""
    state, question = with_question()
    final = finalize_result(state)
    assert state["phase"] == "RUNNING" and state["finalResult"] is None
    assert final["phase"] == "SUCCEEDED"
    assert final["finalResult"].answer == question.text
    assert final["finalResult"].questions == [question]
    assert final["input"].problems[0].questionCount == 4
    assert final["finalResult"].runId == state["input"].runId
    assert final["finalResult"].inquiryVersion == state["input"].inquiryVersion


def test_unverified_draft_does_not_enter_final_answer() -> None:
    """모델이 만든 저장·배정 성공 문구가 State에 있어도 최종 본문에 사용하지 않는다."""
    state, question = with_question()
    state["answer"] = "티켓이 생성되고 담당자 배정이 완료되었습니다."
    result = finalize_result(state)["finalResult"]
    assert result.answer == question.text
    assert not result.ticketProposals


def test_guide_body_is_included_once_and_does_not_confirm_resolution() -> None:
    """안내를 단일 본문에 포함하되 문의자 해결 확인이나 티켓 저장을 만들어내지 않는다."""
    state, guide = with_guide()
    result = finalize_result(state)["finalResult"]
    assert result.answer == guide.messageText
    assert result.guide == guide and not result.ticketProposals
    assert state["input"].problems[0].resolutionContext is None


def test_identical_questions_share_one_body_but_keep_problem_metadata() -> None:
    """같은 질문을 두 문제에 표시할 때 본문만 공유하고 문제별 질문은 보존한다."""
    state, first = with_question()
    second = first.model_copy(
        update={
            "problemRef": "new:second",
            "bundleRef": "q2",
            "requestedFields": ["actualBehavior", "occurredAt"],
        }
    )
    state = merge_inquiry_result(
        reserve(state, AgentRole.INQUIRY, "i2"),
        InquiryResult(invocationId="i2", problems=[make_problem("new:second")], questions=[second]),
    )
    before = deepcopy(state)

    result = finalize_result(state)["finalResult"]

    assert result.status == "SUCCEEDED"
    assert result.answer == f"문제 1 · 문제 2에 각각 답해 주세요.\n{first.text}"
    assert result.answer.count(first.text) == 1
    assert result.questions == [first, second]
    assert AiResult.model_validate(result.model_dump(mode="json")) == result
    assert state["input"].problems[0].questionCount == 4
    assert state == before


@pytest.mark.parametrize(
    "bodies",
    [
        ("오류가 났나요?", "화면에서 오류가 났나요?"),
        ("화면에서 오류가 났나요?", "오류가 났나요?"),
        ("오류가 났나요?", "화면을 확인해 주세요.\n오류가 났나요?"),
    ],
)
def test_overlapping_question_texts_keep_each_problem_section(bodies) -> None:
    """문구가 포함 관계여도 두 질문을 각각 조립하고 메타데이터를 보존한다."""
    state, first = with_question()
    first = first.model_copy(update={"text": bodies[0]})
    second = first.model_copy(
        update={"problemRef": "new:second", "bundleRef": "q2", "text": bodies[1]}
    )
    state["questions"][(PROBLEM, "q1")] = first
    state = merge_inquiry_result(
        reserve(state, AgentRole.INQUIRY, "i2"),
        InquiryResult(invocationId="i2", problems=[make_problem("new:second")], questions=[second]),
    )
    before = deepcopy(state)

    result = finalize_result(state)["finalResult"]

    assert result.status == "SUCCEEDED"
    assert result.answer == f"문제 1\n{bodies[0]}\n\n문제 2\n{bodies[1]}"
    assert result.questions == [first, second]
    assert AiResult.model_validate(result.model_dump(mode="json")) == result
    assert state["input"].problems[0].questionCount == 4
    assert state == before


def test_question_inside_another_problems_guide_does_not_count_as_a_duplicate() -> None:
    """가이드 안의 같은 문구가 있어도 질문과 가이드 항목을 따로 보존한다."""
    state, question = with_question()
    state["problems"]["new:second"] = make_problem("new:second")
    state["evidence"][EVIDENCE] = make_evidence()
    guide = GuideProposal(
        problemRef="new:second",
        messageText=f"두 번째 문제의 확인 안내입니다.\n{question.text}",
        evidenceIds=[EVIDENCE],
    )
    state["guide"] = guide

    result = finalize_result(state)["finalResult"]

    assert result.status == "SUCCEEDED"
    assert result.answer == f"문제 1\n{question.text}\n\n문제 2\n{guide.messageText}"
    assert result.questions == [question]
    assert result.guide == guide


@pytest.mark.parametrize("other_outcome", ["question", "guide", "ticket"])
def test_shared_question_keeps_other_problem_numbers_and_bodies(other_outcome) -> None:
    """공유 질문과 다른 문제의 질문·가이드·티켓이 섞여도 번호와 본문을 보존한다."""
    state, first = with_question()
    state["problems"]["new:second"] = make_problem("new:second")
    state["questions"][("new:second", "q2")] = first.model_copy(
        update={"problemRef": "new:second", "bundleRef": "q2"}
    )
    state["problems"]["new:third"] = make_problem("new:third")
    if other_outcome == "question":
        body = "세 번째 문제의 발생 시점을 알려 주세요."
        state["questions"][("new:third", "q3")] = QuestionProposal(
            problemRef="new:third", bundleRef="q3", text=body, requestedFields=["occurredAt"]
        )
    elif other_outcome == "guide":
        body = "세 번째 문제의 파일 열람 권한을 확인해 주세요."
        state["evidence"][EVIDENCE] = make_evidence()
        state["guide"] = GuideProposal(
            problemRef="new:third", messageText=body, evidenceIds=[EVIDENCE]
        )
    else:
        body = "담당자에게 전달할 티켓 제안을 준비했습니다."
        state["ticketProposals"]["new:third"] = make_ticket("new:third")

    result = finalize_result(state)["finalResult"]

    assert result.status == "SUCCEEDED"
    assert result.answer.startswith(f"문제 1 · 문제 2에 각각 답해 주세요.\n{first.text}")
    assert result.answer.count(first.text) == 1
    assert f"문제 3\n{body}" in result.answer
    assert len(result.questions) == (3 if other_outcome == "question" else 2)


def test_question_and_guide_with_identical_body_are_not_silently_combined() -> None:
    """질문과 가이드는 의미가 다르므로 문구가 같다는 이유로 하나로 합치지 않는다."""
    state, question = with_question()
    state["problems"]["new:second"] = make_problem("new:second")
    state["evidence"][EVIDENCE] = make_evidence()
    state["guide"] = GuideProposal(
        problemRef="new:second", messageText=question.text, evidenceIds=[EVIDENCE]
    )
    with pytest.raises(OutputRejected):
        finalize_result(state)


@pytest.mark.parametrize("reason", ["too_long", "blank", "surrogate"])
def test_invalid_required_body_is_rejected_without_changing_state(reason) -> None:
    """필수 본문의 길이·문자열 오류는 자동 보정하지 않고 거부한다."""
    state, question = with_question()
    if reason == "too_long":
        state["questions"][(PROBLEM, "q1")] = question.model_copy(update={"text": "가" * 4001})
    elif reason == "blank":
        state["questions"][(PROBLEM, "q1")] = question.model_copy(update={"text": " \ufeff"})
    else:
        state["questions"][(PROBLEM, "q1")] = question.model_copy(update={"text": "\ud800"})
    before = deepcopy(state)
    with pytest.raises((OutputRejected, ValidationError)):
        finalize_result(state)
    assert state == before


def test_missing_answer_and_pending_call_are_rejected() -> None:
    """공개 본문이 없거나 실행 중 역할이 남아 있으면 성공 결과를 만들지 않는다."""
    with pytest.raises(OutputRejected):
        finalize_result(running_state())
    pending = reserve(running_state(), AgentRole.INQUIRY, "i1")
    with pytest.raises(OutputRejected):
        finalize_result(pending)


def test_different_problems_can_have_question_and_ticket_in_one_result() -> None:
    """복합 문의의 서로 다른 문제에는 질문과 티켓 제안을 함께 반환할 수 있다."""
    state = merge_inquiry_result(
        reserve(running_state(), AgentRole.INQUIRY, "i1"),
        InquiryResult(
            invocationId="i1",
            problems=[make_problem("new:second")],
            questions=[
                QuestionProposal(
                    problemRef="new:second",
                    bundleRef="q2",
                    text="두 번째 문제의 화면은 무엇인가요?",
                    requestedFields=["taskOrScreen"],
                )
            ],
        ),
    )
    state = merge_ticket_result(
        reserve(state, AgentRole.TICKET, "t1"),
        TicketResult(invocationId="t1", proposals=[make_ticket()]),
    )
    result = finalize_result(state)["finalResult"]
    assert result.questions[0].problemRef == "new:second"
    assert result.ticketProposals[0].problemRef == PROBLEM
    assert result.answer.count(result.questions[0].text) == 1
    assert "티켓 제안을 준비했습니다" in result.answer
    assert "문제 1" in result.answer and "문제 2" in result.answer


@pytest.mark.parametrize("reusable", [True, False])
def test_case_result_has_no_consultation_fields(reusable) -> None:
    """CASE_DRAFT의 성공·재사용 없음 결과 모두 상담 답변과 제안 목록을 비워 둔다."""
    state = merge_curator_result(
        reserve(running_state(make_request(kind=RunKind.CASE_DRAFT)), AgentRole.CURATOR, "c1"),
        CuratorResult(invocationId="c1", proposal=make_case(reusable=reusable)),
        allowed_entity_ids={"APPLIES_TO": {SYSTEM}},
    )
    result = finalize_result(state)["finalResult"]
    assert result.caseProposal.reusable is reusable
    assert result.answer is result.guide is None
    assert result.problemProposals == result.questions == result.ticketProposals == []


def test_case_result_requires_curator_result_and_forbids_chat_answer() -> None:
    """사례 생성 전에는 성공할 수 없고 사례 실행의 상담 본문도 거부한다."""
    state = running_state(make_request(kind=RunKind.CASE_DRAFT))
    with pytest.raises(OutputRejected):
        finalize_result(state)
    state = merge_curator_result(
        reserve(state, AgentRole.CURATOR, "c1"),
        CuratorResult(invocationId="c1", proposal=make_case(reusable=False)),
        allowed_entity_ids={},
    )
    with pytest.raises(OutputRejected):
        finalize_result({**state, "answer": "상담 답변"})
    with pytest.raises(OutputRejected):
        compose_public_answer(state)


@pytest.mark.parametrize("phase", ["FAILED", "TIMED_OUT", "SUPERSEDED"])
def test_failure_result_has_no_new_proposals(phase) -> None:
    """내부에 부분 결과가 남아 있어도 실패한 전체 실행의 새 제안으로 반환하지 않는다."""
    state, _ = with_question()
    result = finalize_result(transition_phase(state, phase))["finalResult"]
    assert result.status == phase and result.error is not None
    assert result.answer is result.guide is result.caseProposal is None
    assert (
        result.problemProposals
        == result.questions
        == result.ticketProposals
        == result.evidence
        == []
    )


def test_final_result_is_reused_but_not_replaced() -> None:
    """같은 최종화를 반복해도 결과는 고정하며 늦은 새 본문은 거부한다."""
    state, _ = with_question()
    final = finalize_result(state)
    assert finalize_result(final) is final
    with pytest.raises(TypeError):
        finalize_result(final, answer="다른 최종 본문")
    with pytest.raises(OutputRejected):
        transition_phase(final, "RUNNING")


def test_final_validation_rechecks_question_limit_and_evidence_scope() -> None:
    """병합 후 상태가 잘못 바뀌어도 최종화 시 불변식을 다시 검사한다."""
    state, question = with_question()
    state["questions"][(PROBLEM, "q2")] = question.model_copy(update={"bundleRef": "q2"})
    with pytest.raises(OutputRejected):
        finalize_result(state)
    state, _ = with_guide()
    state["evidence"][EVIDENCE] = make_evidence(accessScope="SYSTEM_STAFF")
    with pytest.raises(OutputRejected):
        finalize_result(state)


@pytest.mark.parametrize("changed_system", [None, OTHER_SYSTEM])
def test_final_validation_rejects_changed_server_reported_system(changed_system) -> None:
    """병합 이후 보고 시스템이 변조돼도 질문 답변을 성공 결과로 확정하지 않는다."""
    state, _ = with_question()
    state["problems"][PROBLEM] = state["problems"][PROBLEM].model_copy(
        update={"reportedSystemCandidateId": changed_system}
    )
    before = deepcopy(state)
    with pytest.raises(OutputRejected, match="보고 시스템"):
        finalize_result(state)
    assert state == before


def test_final_result_does_not_share_nested_lists_with_working_state() -> None:
    """최종 결과를 만든 뒤 내부 문제 목록을 수정해도 확정 결과에는 반영하지 않는다."""
    state, _ = with_question()
    final = finalize_result(state)
    final["problems"][PROBLEM].facts.clear()
    assert len(final["finalResult"].problemProposals[0].facts) == 1


@pytest.mark.parametrize(
    ("outcome", "expected"),
    [
        ("STORED", "기존 티켓의 저장이 확인되었습니다."),
        ("PENDING", "기존 티켓의 저장 결과를 확인 중입니다."),
        ("UNKNOWN", "기존 티켓의 저장 여부가 아직 확인되지 않았습니다."),
    ],
)
def test_existing_work_answer_uses_server_outcome_only(outcome, expected) -> None:
    """서버의 저장 결과를 구분하고 기존 티켓의 담당자 배정까지 추정하지 않는다."""
    state = running_state(make_request(outcome=outcome))
    state["answer"] = "담당자 배정과 조치가 완료되었습니다."
    result = finalize_result(state)["finalResult"]
    assert result.answer == expected
    assert not result.ticketProposals


def test_not_created_work_does_not_supply_a_completion_result() -> None:
    """기존 업무가 미생성이라면 새 처리 제안 없이 완료 안내로 끝낼 수 없다."""
    with pytest.raises(OutputRejected):
        finalize_result(running_state(make_request(outcome="NOT_CREATED")))


@pytest.mark.parametrize(
    ("original_state", "expected"),
    [
        ("IN_SERVICE", "현재 문제는 처리 중 상태입니다."),
        ("WAITING_CONFIRMATION", "현재 문제는 해결 확인 대기 상태입니다."),
        ("RESOLVED", "현재 문제는 해결 완료로 기록되어 있습니다."),
    ],
)
def test_existing_problem_state_is_taken_from_server(original_state, expected) -> None:
    """서버가 전달한 진행·확인·해결 상태만 안내하고 모델의 새 완료 선언은 사용하지 않는다."""
    request = make_request()
    original = request.problems[0].model_copy(update={"state": original_state})
    state = running_state(request.model_copy(update={"problems": [original]}))
    assert finalize_result(state)["finalResult"].answer == expected


def test_out_of_scope_answer_does_not_echo_problem_summary() -> None:
    """범위 밖 안내에 모델의 자유 요약을 섞어 업무 완료를 선언하지 않는다."""
    state = running_state()
    state["problems"][PROBLEM] = make_problem().model_copy(
        update={
            "intent": "OUT_OF_SCOPE",
            "processingType": "OUT_OF_SCOPE",
            "summary": "티켓 생성과 담당자 배정이 완료되었습니다.",
        }
    )
    result = finalize_result(state)["finalResult"]
    assert result.answer == "지원 범위 밖의 문의입니다. 지원 시스템에 관한 문의를 이용해 주세요."


def test_system_selection_wait_does_not_add_a_sixth_question() -> None:
    """질문 5회 후 시스템 미확인은 화면 선택 안내만 반환하고 질문 수를 유지한다."""
    request = make_request(question_count=5)
    original = request.problems[0].model_copy(update={"reportedSystemId": None})
    state = running_state(request.model_copy(update={"problems": [original]}))
    result = finalize_result(state)["finalResult"]
    assert (
        result.answer == "보고 시스템을 확인하지 못했습니다. 화면에서 해당 시스템을 선택해 주세요."
    )
    assert result.questions == result.ticketProposals == []
    assert state["input"].problems[0].questionCount == 5


def test_free_answer_argument_is_not_an_alternate_public_output_path() -> None:
    """호출자가 임의 본문을 별도 인자로 공급하는 경로를 제공하지 않는다."""
    state, _ = with_question()
    with pytest.raises(TypeError):
        finalize_result(state, answer="담당자 배정이 완료되었습니다.")
