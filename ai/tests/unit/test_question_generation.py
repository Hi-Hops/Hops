"""질문 문장을 모델이 쓰는 질문 행동. 모델은 Fake다.

Fake는 모델이 낼 수 있는 응답(좋은 문장, 규칙을 어기는 문장, 오류)을 정해 두고, 무엇을 물을지는
항상 코드가 정하고 실패해도 고정 문구로 질문이 나가는지를 확인한다. 문장이 실제로 좋은지는
검증하지 않는다.
"""

import json

import pytest

from evaluation.harness.catalog import SYSTEMS
from hops_ai.agents.inquiry import ExtractionInquiryAgent
from hops_ai.agents.inquiry import question_text as qt
from hops_ai.agents.inquiry.asking import priority_fields, question_plan
from hops_ai.agents.inquiry.forms import plan_next
from hops_ai.agents.inquiry.question_text import (
    QuestionDraft,
    RejectedQuestion,
    build_user_payload,
    validate_text,
)
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import Assessment, MissingInformation
from hops_ai.harness.errors import BudgetExceeded, OutputRejected, TransientError
from hops_ai.supervisor.execution import execute_role
from tests.supervisor_fixtures import EVIDENCE, PROBLEM
from tests.unit.test_inquiry_agent import services_for
from tests.unit.test_problem_decisions import base_state, request_for

FIXED_THREE = ["taskOrScreen", "expectedBehavior", "actualBehavior"]
NAMES = ["시스템가", "시스템나"]


def symptom_plan(known=None, **kwargs):
    return plan_next("OBSERVED_SYMPTOM", known or {}, system_known=True, **kwargs)


def agent(**kwargs):
    return ExtractionInquiryAgent(defer_questions=True, **kwargs)


async def ask(state, request, *outcomes, generate=True):
    services, llm = services_for(request, *outcomes)
    merged_agents = {AgentRole.INQUIRY: agent(generate_questions=generate)}
    merged = await execute_role(
        state, AgentRole.INQUIRY, services, merged_agents, targets=(PROBLEM,), action="ASK"
    )
    return merged, llm


def asked(merged):
    return next(iter(merged["questions"].values()))


# --- 필드 우선순위: 판정이 부족하다고 한 정보를 먼저 묻는다 ---


def test_priority_fields_come_first_and_the_rest_keep_their_order() -> None:
    plan = symptom_plan(priority=["actualBehavior"])

    assert plan.fields == ("actualBehavior", "taskOrScreen", "expectedBehavior")


def test_a_priority_field_outside_the_required_list_is_still_asked_if_it_is_a_form_field() -> None:
    plan = symptom_plan(priority=["currentWorkflow"])

    assert plan.fields == ("currentWorkflow", "taskOrScreen", "expectedBehavior")


def test_the_system_is_still_asked_first_whatever_the_priority() -> None:
    plan = plan_next("OBSERVED_SYMPTOM", {}, system_known=False, priority=["occurredAt"])

    assert plan.fields[0] == "system" and plan.fields[1] == "occurredAt"


def test_priority_ignores_known_unknown_asked_and_non_form_fields() -> None:
    plan = symptom_plan(
        {"taskOrScreen": "문서 조회"},
        unknown={"expectedBehavior"},
        asked={"actualBehavior"},
        priority=["taskOrScreen", "expectedBehavior", "actualBehavior", "nonsense", "system"],
    )

    assert plan.fields == ("occurredAt",)


def test_priority_never_turns_a_complete_form_into_a_question() -> None:
    known = {
        "taskOrScreen": "a",
        "expectedBehavior": "b",
        "actualBehavior": "c",
        "occurredAt": "d",
    }

    assert symptom_plan(known, priority=["currentWorkflow"]).action == "COMPLETE"


def test_the_fixed_prompts_follow_the_planned_fields() -> None:
    plan = symptom_plan(priority=["occurredAt"])

    assert len(plan.prompts) == len(plan.fields) == 3
    assert plan.prompts[0].startswith("언제")


def with_missing(state, *refs, kind="FIELD"):
    assessment = Assessment(
        problemRef=PROBLEM,
        verdict="PARTIAL",
        supportingEvidenceIds=[EVIDENCE],
        missingInformation=[MissingInformation(kind=kind, ref=ref) for ref in refs],
        candidateCauses=[],
        nextChecks=[],
        rationale="내부용",
    )
    return {**state, "assessments": {PROBLEM: assessment}}


def test_the_assessments_missing_fields_become_the_question_priority() -> None:
    request = request_for()
    state = with_missing(base_state(request), "occurredAt")

    assert priority_fields(state, PROBLEM) == ("occurredAt",)
    assert question_plan(state, PROBLEM, searched=True).fields[0] == "occurredAt"


def test_without_an_assessment_the_question_follows_the_form_order() -> None:
    request = request_for()

    assert priority_fields(base_state(request), PROBLEM) == ()
    assert list(question_plan(base_state(request), PROBLEM, searched=False).fields) == FIXED_THREE


def test_check_items_are_not_question_priorities_yet() -> None:
    request = request_for()
    state = with_missing(base_state(request), "check-x", kind="CHECK_ITEM")

    assert priority_fields(state, PROBLEM) == ()


# --- 모델이 쓴 문장: 쓰이는 경우 ---


async def test_a_valid_model_sentence_is_used_but_the_fields_stay_the_codes() -> None:
    request = request_for()
    sentence = "말씀하신 상황을 확인하려고 합니다. 어떤 화면에서 어떻게 보이는지 알려 주시겠어요?"

    merged, llm = await ask(base_state(request), request, QuestionDraft(text=sentence))

    question = asked(merged)
    assert question.text == sentence
    assert question.requestedFields == FIXED_THREE
    assert question.bundleRef == f"inquiry-{PROBLEM}-1"
    [call] = llm.requests
    assert call.prompt_version == "inquiry.question.v1" and call.response_model is QuestionDraft


async def test_the_model_receives_the_planned_fields_and_the_users_words_only() -> None:
    request = request_for()
    state = with_missing(base_state(request), "occurredAt")

    _, llm = await ask(state, request, QuestionDraft(text="언제부터 그랬나요?"))

    payload = json.loads(llm.requests[0].messages[1].content)
    assert [item["field"] for item in payload["toAsk"]][0] == "occurredAt"
    assert set(payload) == {"problem", "toAsk", "systems", "remainingQuestions"}
    assert payload["remainingQuestions"] == 5
    assert payload["systems"] == []
    assert "rationale" not in json.dumps(payload) and "내부용" not in json.dumps(payload)


def test_the_system_names_are_given_only_when_the_system_is_being_asked() -> None:
    request = request_for(system=None)
    state = base_state(request)
    problem = state["problems"][PROBLEM]
    plan = question_plan(state, PROBLEM, searched=False)

    with_system = json.loads(build_user_payload(problem, plan, NAMES, 5))
    without = json.loads(build_user_payload(problem, symptom_plan(), NAMES, 5))

    assert plan.fields[0] == "system" and with_system["systems"] == NAMES
    assert without["systems"] == []


# --- 대체: 실패해도 질문은 고정 문구로 나간다 ---


@pytest.mark.parametrize(
    ("outcome", "reason"),
    [
        (QuestionDraft(text=""), "INVALID:EMPTY"),
        (QuestionDraft(text="가" * 500), "INVALID:TOO_LONG"),
        (QuestionDraft(text="\n".join(["질문"] * 8)), "INVALID:TOO_MANY_LINES"),
        (QuestionDraft(text="https://example.com 에서 확인해 주세요"), "INVALID:LINK_OR_ID"),
        (QuestionDraft(text="비밀번호를 알려 주세요"), "INVALID:SENSITIVE_REQUEST"),
        (OutputRejected("거부"), "MODEL_ERROR"),
        (TransientError("연결"), "MODEL_ERROR"),
    ],
)
async def test_a_failed_or_rule_breaking_sentence_falls_back_to_the_fixed_text(
    outcome, reason
) -> None:
    request = request_for()
    plan = question_plan(base_state(request), PROBLEM, searched=False)

    merged, llm = await ask(base_state(request), request, outcome)

    question = asked(merged)
    assert question.text == plan.text and question.requestedFields == list(plan.fields)
    assert len(llm.requests) == 1
    assert reason  # 사유 코드는 결과 기록으로 확인한다(아래 테스트)


async def test_the_result_records_who_wrote_the_sentence_and_why_it_fell_back() -> None:
    request = request_for()
    services, _ = services_for(request, QuestionDraft(text="비밀번호는 무엇인가요?"))
    state = base_state(request)
    from hops_ai.contracts.state import RoleRequest

    result = await agent(generate_questions=True).run(
        RoleRequest("inv-1", state, targets=(PROBLEM,), action="ASK"), services
    )

    assert result.questionSource == "FIXED_FALLBACK"
    assert result.questionFallback == "INVALID:SENSITIVE_REQUEST"
    assert "비밀번호" not in (result.questionFallback or "")


async def test_a_model_sentence_is_recorded_as_model_written() -> None:
    request = request_for()
    services, _ = services_for(request, QuestionDraft(text="어느 화면에서 그러셨나요?"))
    from hops_ai.contracts.state import RoleRequest

    result = await agent(generate_questions=True).run(
        RoleRequest("inv-1", base_state(request), targets=(PROBLEM,), action="ASK"), services
    )

    assert (result.questionSource, result.questionFallback) == ("MODEL", None)


async def test_without_the_switch_the_fixed_text_is_used_and_the_model_is_not_called() -> None:
    request = request_for()

    merged, llm = await ask(base_state(request), request, generate=False)

    assert llm.requests == []
    assert asked(merged).text == question_plan(base_state(request), PROBLEM, searched=False).text


async def test_an_exhausted_budget_uses_the_fixed_text_without_calling_the_model() -> None:
    request = request_for()
    services, llm = services_for(request, QuestionDraft(text="unused"))
    services.runtime.budget.max_llm_calls = 1  # 질문 호출 말고도 한 번을 남겨 둘 수 없다
    agents = {AgentRole.INQUIRY: agent(generate_questions=True)}

    merged = await execute_role(
        base_state(request), AgentRole.INQUIRY, services, agents, targets=(PROBLEM,), action="ASK"
    )

    assert llm.requests == []
    assert asked(merged).text == question_plan(base_state(request), PROBLEM, searched=False).text


async def test_a_run_failure_is_not_hidden_by_the_fallback() -> None:
    request = request_for()

    with pytest.raises(BudgetExceeded):
        await ask(base_state(request), request, BudgetExceeded("예산"))


# --- 검증 규칙 단독 ---


def test_a_system_question_must_show_every_supported_system_name() -> None:
    plan = plan_next("OBSERVED_SYMPTOM", {}, system_known=False)

    assert validate_text("어느 시스템인가요? 시스템가 / 시스템나", plan, NAMES)
    with pytest.raises(RejectedQuestion) as caught:
        validate_text("어느 시스템인가요? 시스템가", plan, NAMES)
    assert caught.value.code == "SYSTEM_NAMES_MISSING"


def test_the_sentence_is_trimmed_and_ordinary_text_passes() -> None:
    assert validate_text("  언제부터 그랬나요?  ", symptom_plan(), NAMES) == "언제부터 그랬나요?"


def test_the_question_prompt_has_no_dataset_specific_names() -> None:
    names = [value for system in SYSTEMS for value in (system["code"], system["name"])]
    assert qt.PROMPT_VERSION == "inquiry.question.v1"
    assert not [name for name in names if name in qt.SYSTEM_PROMPT]
    assert "guide-" not in qt.SYSTEM_PROMPT and "runbook-" not in qt.SYSTEM_PROMPT


# --- 판정 모드 그래프 안에서 ---


async def test_in_the_judgment_graph_the_chosen_ask_uses_the_model_sentence() -> None:
    from hops_ai.agents.inquiry.extraction import InquiryExtraction
    from hops_ai.agents.registry import build_registry
    from hops_ai.supervisor.graph import Supervisor
    from tests.doubles.llm import FakeLLMClient
    from tests.unit.test_inquiry_agent import EA_SYMPTOM, extracted, first_message_request
    from tests.unit.test_knowledge_agent import RecordingSource
    from tests.unit.test_knowledge_assessment import DEFAULT_HITS
    from tests.unit.test_knowledge_wiring import services_for as graph_services
    from tests.unit.test_problem_graph import REF, select

    request = first_message_request(EA_SYMPTOM)
    llm_outcomes = (
        InquiryExtraction(problems=[extracted()]),
        select(**{REF: "ASK"}),
        QuestionDraft(text="그 문서를 언제 마지막으로 보셨는지 알려 주시겠어요?"),
    )
    agents = build_registry(
        RecordingSource(DEFAULT_HITS),
        assess_evidence=True,
        defer_questions=True,
        generate_questions=True,
    )
    llm = FakeLLMClient(*llm_outcomes)
    services = graph_services(request, llm)
    result = await Supervisor(agents, restrict_roles=True, problem_actions=True).analyze(
        request, services
    )

    assert [call.prompt_version for call in llm.requests] == [
        "inquiry-extraction-v9",
        "supervisor.selection.v1",
        "inquiry.question.v1",
    ]
    [question] = result.questions
    assert question.text == "그 문서를 언제 마지막으로 보셨는지 알려 주시겠어요?"
    assert question.problemRef == REF and question.requestedFields
    assert result.answer == question.text
