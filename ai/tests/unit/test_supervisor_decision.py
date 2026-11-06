"""모델의 다음 행동을 실행 상태와 대조한다. 실제 LLM 품질 검증은 아니다."""

import json
from copy import deepcopy
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import KnowledgeResult, SearchResult
from hops_ai.contracts.wire import GuideProposal, QuestionProposal
from hops_ai.harness.errors import BudgetExceeded, OutputRejected, RunCancelled
from hops_ai.supervisor.decision import (
    SupervisorDecision,
    _decision_context,
    decide_next_action,
    validate_supervisor_decision,
)
from hops_ai.supervisor.merge import merge_knowledge_result
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import (
    EVIDENCE,
    PROBLEM,
    authorize,
    make_case,
    make_evidence,
    make_problem,
    make_request,
    make_ticket,
    reserve,
    running_state,
)


def decision(action="FINISH", role=None):
    return SupervisorDecision(
        nextAction=action, role=role, publicSummary="문의 내용을 정리했습니다"
    )


class Runtime:
    """판단 함수가 호출 전후 취소를 확인하는지 관찰하는 최소 대역."""

    def __init__(self):
        self.cancelled = False

    def ensure_active(self):
        if self.cancelled:
            raise RunCancelled


def services(llm):
    return SimpleNamespace(runtime=Runtime(), llm=llm)


@pytest.mark.parametrize("action", ["ASK_USER", "PROVIDE_GUIDE", "PROPOSE_TICKETS", "FINISH"])
def test_terminal_action_cannot_include_a_role(action):
    with pytest.raises(ValidationError):
        decision(action, AgentRole.INQUIRY)


def test_call_role_requires_a_role_and_forbids_extra_fields():
    with pytest.raises(ValidationError):
        decision("CALL_ROLE")
    with pytest.raises(ValidationError):
        SupervisorDecision(**decision().model_dump(), reasoning="내부 판단")


@pytest.mark.parametrize("action", ["ASK_USER", "PROVIDE_GUIDE", "PROPOSE_TICKETS", "FINISH"])
def test_missing_terminal_proposal_is_rejected(action):
    """제안이나 기존 처리 결과가 없는 문제를 성공한 실행으로 종료하지 않는다."""
    with pytest.raises(OutputRejected):
        validate_supervisor_decision(running_state(), decision(action))


def test_question_and_ticket_actions_require_actual_valid_proposals():
    state = running_state()
    question = QuestionProposal(
        problemRef=PROBLEM, bundleRef="q1", text="오류 문구가 무엇인가요?", requestedFields=[]
    )
    state["questions"][(PROBLEM, "q1")] = question
    validate_supervisor_decision(state, decision("ASK_USER"))

    state["questions"] = {}
    state["ticketProposals"][PROBLEM] = make_ticket()
    validate_supervisor_decision(state, decision("PROPOSE_TICKETS"))


def test_guide_action_checks_employee_evidence():
    evidence = make_evidence()
    state = merge_knowledge_result(
        reserve(running_state(), AgentRole.KNOWLEDGE, "k1"),
        KnowledgeResult(
            invocationId="k1",
            search=SearchResult(state="FOUND", evidence=[evidence], error=None),
            guide=GuideProposal(
                problemRef=PROBLEM, messageText="권한 확인 안내", evidenceIds=[EVIDENCE]
            ),
        ),
        authorized_refs=[authorize(evidence)],
        verified_evidence=[evidence],
    )
    validate_supervisor_decision(state, decision("PROVIDE_GUIDE"))
    state["evidence"] = {}
    with pytest.raises(OutputRejected):
        validate_supervisor_decision(state, decision("PROVIDE_GUIDE"))


def test_call_role_uses_kind_policy_and_does_not_modify_state():
    state = running_state()
    before = deepcopy(state)
    validate_supervisor_decision(state, decision("CALL_ROLE", AgentRole.INQUIRY))
    assert state == before
    with pytest.raises(OutputRejected):
        validate_supervisor_decision(state, decision("CALL_ROLE", AgentRole.CURATOR))


def test_case_finishes_only_with_a_case_proposal():
    state = running_state(make_request(kind=RunKind.CASE_DRAFT))
    validate_supervisor_decision(state, decision("CALL_ROLE", AgentRole.CURATOR))
    with pytest.raises(OutputRejected):
        validate_supervisor_decision(state, decision())
    state["caseProposal"] = make_case(reusable=False)
    validate_supervisor_decision(state, decision())
    with pytest.raises(OutputRejected):
        validate_supervisor_decision(state, decision("ASK_USER"))


@pytest.mark.parametrize(
    "change", [{"phase": "QUEUED"}, {"activeRole": AgentRole.INQUIRY}, {"pendingCalls": {"i1"}}]
)
def test_pending_or_inactive_execution_cannot_decide(change):
    with pytest.raises(OutputRejected):
        validate_supervisor_decision({**running_state(), **change}, decision())


def test_constructed_invalid_decision_does_not_bypass_schema_validation():
    forged = SupervisorDecision.model_construct(
        nextAction="DELETE", role=None, publicSummary="요약"
    )
    with pytest.raises(OutputRejected):
        validate_supervisor_decision(running_state(), forged)


async def test_decision_uses_structured_llm_and_remaining_output_tokens():
    state = running_state()
    state["usage"]["outputTokens"] = 3500
    before = deepcopy(state)
    llm = FakeLLMClient(decision("CALL_ROLE", AgentRole.INQUIRY))

    result = await decide_next_action(state, services(llm))

    assert result.role == AgentRole.INQUIRY
    assert state == before
    sent = llm.requests[0]
    assert sent.response_model is SupervisorDecision
    assert sent.prompt_version == "supervisor.decision.v10"
    assert sent.max_output_tokens == 500
    context = json.loads(sent.messages[1].content)
    assert context["allowedRoles"] == ["INQUIRY", "KNOWLEDGE", "TICKET"]
    assert context["problems"][0]["remainingQuestions"] == 5
    assert context["allowedActions"] == ["CALL_ROLE", "REPLY"]


async def test_routing_prompt_omits_staff_knowledge_and_analysis_body():
    state = running_state()
    internal = make_evidence(accessScope="SYSTEM_STAFF", excerpt="STAFF_CONTENT_SENTINEL")
    state["evidence"][EVIDENCE] = internal
    state["roleResults"]["k1"] = KnowledgeResult(
        invocationId="k1",
        search=SearchResult(state="FOUND", evidence=[internal], error=None),
        guide=None,
    )
    llm = FakeLLMClient(decision("CALL_ROLE", AgentRole.KNOWLEDGE))

    await decide_next_action(state, services(llm))

    context = llm.requests[0].messages[1].content
    assert "STAFF_CONTENT_SENTINEL" not in context
    assert "evidence" not in json.loads(context)
    assert json.loads(context)["completedCalls"] == [
        {"invocationId": "k1", "role": "KNOWLEDGE", "searchState": "FOUND"}
    ]


def test_readiness_distinguishes_missing_details_without_exposing_analysis_text():
    """필드 준비 여부와 미확인 개수는 구분하되 분석 문자열 자체는 투영하지 않는다."""
    state = running_state()
    before = deepcopy(state)
    initial = _decision_context(state)
    problem = make_problem().model_copy(update={"unknowns": ["UNKNOWN_CONTENT_SENTINEL"]})
    state["problems"][PROBLEM] = problem

    context = _decision_context(state)

    assert initial["problemProgress"][0]["availableDetails"] == []
    progress = context["problemProgress"][0]
    assert progress["problemRef"] == PROBLEM
    assert progress["unknownCount"] == 1
    assert set(progress["availableDetails"]) == {"taskOrScreen", "actualBehavior"}
    assert progress["remainingQuestions"] == 5 and progress["outcome"] is None
    assert "UNKNOWN_CONTENT_SENTINEL" not in json.dumps(context)
    assert "첨부파일 열람" not in json.dumps(progress, ensure_ascii=False)
    assert initial != context
    assert before["problems"][PROBLEM].details.taskOrScreen is None


def test_investigation_ticket_can_finish_with_unknown_cause():
    """미확인 원인을 남긴 유효한 조사 제안은 불필요한 질문 루프로 보내지 않는다."""
    state = running_state()
    state["problems"][PROBLEM] = make_problem()
    state["ticketProposals"][PROBLEM] = make_ticket()

    context = _decision_context(state)

    assert context["problemProgress"][0]["unknownCount"] == 1
    assert context["allowedActions"] == ["CALL_ROLE", "FINISH", "PROPOSE_TICKETS"]
    validate_supervisor_decision(state, decision("PROPOSE_TICKETS"))


@pytest.mark.parametrize("outcome", ["PENDING", "STORED", "UNKNOWN"])
def test_existing_work_can_be_reported_without_recreating_ticket(outcome):
    """Spring이 전달한 저장 완료·확인 중·미확인 결과는 기존 결과 안내로 끝낼 수 있다."""
    state = running_state(make_request(outcome=outcome))
    assert _decision_context(state)["allowedActions"] == ["CALL_ROLE", "FINISH", "REPLY"]
    validate_supervisor_decision(state, decision())


def test_confirmed_not_created_work_is_still_unhandled():
    """미생성 확인은 처리 완료가 아니므로 필요한 역할 실행을 이어간다."""
    state = running_state(make_request(outcome="NOT_CREATED"))
    assert _decision_context(state)["allowedActions"] == ["CALL_ROLE", "REPLY"]
    with pytest.raises(OutputRejected):
        validate_supervisor_decision(state, decision())


def test_empty_search_is_not_a_completion_result():
    """검색 0건은 정상 검색 결과지만 질문·티켓 등 남은 처리를 대신하지 않는다."""
    state = merge_knowledge_result(
        reserve(running_state(), AgentRole.KNOWLEDGE, "k-empty"),
        KnowledgeResult(
            invocationId="k-empty",
            search=SearchResult(state="EMPTY", evidence=[], error=None),
            guide=None,
        ),
        authorized_refs=[],
    )
    assert _decision_context(state)["allowedActions"] == ["CALL_ROLE"]
    with pytest.raises(OutputRejected):
        validate_supervisor_decision(state, decision())


def test_terminal_action_waits_for_every_problem_in_a_compound_inquiry():
    """첫 문제의 질문만 준비됐으면 다른 신규 문제를 건너뛰고 종료할 수 없다."""
    state = running_state()
    state["questions"][(PROBLEM, "q1")] = QuestionProposal(
        problemRef=PROBLEM, bundleRef="q1", text="오류 문구를 알려 주세요", requestedFields=[]
    )
    second = "new:second"
    state["problems"][second] = make_problem(second)

    assert _decision_context(state)["allowedActions"] == ["CALL_ROLE"]
    for action in ("FINISH", "ASK_USER"):
        with pytest.raises(OutputRejected):
            validate_supervisor_decision(state, decision(action))

    state["ticketProposals"][second] = make_ticket(second)
    assert _decision_context(state)["allowedActions"] == [
        "CALL_ROLE",
        "FINISH",
        "ASK_USER",
        "PROPOSE_TICKETS",
    ]
    validate_supervisor_decision(state, decision("ASK_USER"))


async def test_private_guide_is_rejected_before_model_receives_context():
    """잘못 연결된 내부 안내는 종료 선택지에서 제외하는 데 그치지 않고 전송을 막는다."""
    state = running_state()
    state["evidence"][EVIDENCE] = make_evidence(accessScope="SYSTEM_STAFF")
    state["guide"] = GuideProposal(
        problemRef=PROBLEM, messageText="STAFF_GUIDE_SENTINEL", evidenceIds=[EVIDENCE]
    )
    llm = FakeLLMClient(decision("CALL_ROLE", AgentRole.INQUIRY))
    with pytest.raises(OutputRejected):
        await decide_next_action(state, services(llm))
    assert llm.requests == []


async def test_exhausted_output_budget_prevents_model_call():
    state = running_state()
    state["usage"]["outputTokens"] = state["input"].limits.maxOutputTokens
    llm = FakeLLMClient(decision())
    with pytest.raises(BudgetExceeded):
        await decide_next_action(state, services(llm))
    assert llm.requests == []


async def test_cancelled_runtime_prevents_model_call():
    llm = FakeLLMClient(decision())
    shared = services(llm)
    shared.runtime.cancelled = True
    with pytest.raises(RunCancelled):
        await decide_next_action(running_state(), shared)
    assert llm.requests == []


async def test_cancellation_after_model_response_discards_decision():
    shared = services(None)

    class CancellingLLM(FakeLLMClient):
        async def generate(self, request):
            result = await super().generate(request)
            shared.runtime.cancelled = True
            return result

    shared.llm = CancellingLLM(decision())
    with pytest.raises(RunCancelled):
        await decide_next_action(running_state(), shared)
