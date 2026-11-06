"""문제별 행동 경로(판정 모드)의 선택과 실행. 모델은 Fake다.

Fake는 모델이 낼 수 있는 응답(좋은 선택, 선택지 밖의 값, 오류)을 정해 두고, 코드가 합법 행동
안에서만 움직이고 실패에도 응답이 끝나는지를 확인한다. 실제 모델이 좋은 행동을 고르는지는
검증하지 않는다.
"""

import json

import pytest

from hops_ai.agents.inquiry.extraction import InquiryExtraction, QuotedValue
from hops_ai.agents.registry import build_registry
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.harness.errors import OutputRejected
from hops_ai.services.runs.supervisor import SupervisorPipeline
from hops_ai.supervisor.graph import Supervisor
from hops_ai.supervisor.selection import ActionSelection, selection_context
from tests.doubles.agents import ticket_decisions
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import MESSAGE, PROBLEM
from tests.unit.test_inquiry_agent import EA_SYMPTOM, extracted, first_message_request
from tests.unit.test_knowledge_agent import RecordingSource
from tests.unit.test_knowledge_assessment import DEFAULT_HITS, GUIDE, draft
from tests.unit.test_knowledge_wiring import services_for
from tests.unit.test_problem_decisions import base_state, with_assessment, with_guide

HOW_TO_TEXT = "전자결재 문서 조회 방법을 알려주세요"
REF = "new:1"


def select(**decisions: str) -> ActionSelection:
    return ActionSelection(decisions=decisions)


def judgment_supervisor(source=None, **kwargs) -> Supervisor:
    agents = build_registry(
        source or RecordingSource(DEFAULT_HITS), assess_evidence=True, defer_questions=True
    )
    return Supervisor(agents, restrict_roles=True, problem_actions=True, **kwargs)


def how_to(task="문서 조회", **changes):
    return extracted(
        intent="HOW_TO",
        actualBehavior=None,
        taskOrScreen=QuotedValue(messageId=MESSAGE, quote=task),
        **changes,
    )


async def run(request, *outcomes, source=None):
    llm = FakeLLMClient(*outcomes)
    result = await judgment_supervisor(source).analyze(request, services_for(request, llm))
    return result, llm


def prompt_versions(llm) -> list[str]:
    return [item.prompt_version for item in llm.requests]


# --- 선택지가 하나이면 모델을 부르지 않는다 ---


async def test_an_answerable_how_to_ends_with_the_guide_and_no_selection_call() -> None:
    request = first_message_request(HOW_TO_TEXT)

    result, llm = await run(
        request, InquiryExtraction(problems=[how_to()]), draft(supportingEvidenceIds=[GUIDE.id])
    )

    assert result.status == "SUCCEEDED"
    assert result.guide is not None and result.questions == [] and result.ticketProposals == []
    assert prompt_versions(llm) == ["inquiry-extraction-v6", "knowledge.assessment.v2"]


async def test_a_partial_verdict_with_something_to_ask_ends_in_the_question_not_the_guide() -> None:
    request = first_message_request(HOW_TO_TEXT)
    partial = draft(
        verdict="PARTIAL",
        missingInformation=[{"kind": "FIELD", "ref": "expectedBehavior"}],
    )

    result, llm = await run(request, InquiryExtraction(problems=[how_to()]), partial)

    assert result.guide is None, "안내를 만들 수 있었지만 판정이 불충분해 보내지 않는다"
    [question] = result.questions
    assert question.requestedFields == ["expectedBehavior"]
    assert len(llm.requests) == 2


async def test_a_problem_that_cannot_be_asked_or_searched_holds_for_the_system() -> None:
    request = first_message_request(HOW_TO_TEXT)
    fields = request.model_dump(mode="json")
    fields["problems"] = [
        {
            "id": "00000000-0000-0000-0000-0000000000aa",
            "summary": "시스템 미확인",
            "intent": "OBSERVED_SYMPTOM",
            "processingType": "INVESTIGATION",
            "reportedSystemId": None,
            "reportedFeatureId": None,
            "state": "ANALYZING",
            "questionCount": 5,
            "standardRequest": None,
            "resolutionContext": None,
        }
    ]
    fields["problemIds"] = ["00000000-0000-0000-0000-0000000000aa"]
    request = AiRunRequest.model_validate(fields)

    result, llm = await run(request, InquiryExtraction(problems=[extracted(systemCode=None)]))

    assert result.status == "SUCCEEDED"
    assert "보고 시스템을 확인하지 못했습니다" in result.answer
    assert result.questions == [] and result.guide is None and result.ticketProposals == []
    assert len(llm.requests) == 1


async def test_an_out_of_scope_problem_is_closed_with_the_fixed_notice() -> None:
    request = first_message_request("오늘 점심 메뉴 추천해 주세요")

    result, llm = await run(
        request,
        InquiryExtraction(
            problems=[extracted(intent="OUT_OF_SCOPE", systemCode=None, actualBehavior=None)]
        ),
    )

    assert "지원 범위 밖" in result.answer
    assert len(llm.requests) == 1


# --- 갈림길에서만 모델이 고른다 ---


def symptom_run(*selections, assessment=None):
    """증상 문의: 검색 전에는 {검색, 질문}, 판정이 충분하면 {답변, 질문}이 선택지다."""
    return [
        InquiryExtraction(problems=[extracted()]),
        selections[0],
        assessment or draft(supportingEvidenceIds=[GUIDE.id]),
        *selections[1:],
    ]


async def test_the_model_picks_between_the_legal_actions_at_each_fork() -> None:
    request = first_message_request(EA_SYMPTOM)

    result, llm = await run(
        request, *symptom_run(select(**{REF: "SEARCH"}), select(**{REF: "ANSWER"}))
    )

    assert result.guide is not None and result.questions == []
    assert prompt_versions(llm) == [
        "inquiry-extraction-v6",
        "supervisor.selection.v1",
        "knowledge.assessment.v2",
        "supervisor.selection.v1",
    ]
    schema = llm.requests[1].response_schema["properties"]["decisions"]["properties"]
    assert schema == {REF: {"type": "string", "enum": ["ASK", "SEARCH"]}}


async def test_choosing_to_ask_after_a_guide_exists_drops_the_guide_and_asks() -> None:
    request = first_message_request(EA_SYMPTOM)

    result, _ = await run(request, *symptom_run(select(**{REF: "SEARCH"}), select(**{REF: "ASK"})))

    assert result.guide is None
    [question] = result.questions
    assert question.problemRef == REF and "안 보여요" not in question.text


async def test_the_model_can_ask_before_searching() -> None:
    request = first_message_request(EA_SYMPTOM)

    result, llm = await run(
        request, InquiryExtraction(problems=[extracted()]), select(**{REF: "ASK"})
    )

    assert len(result.questions) == 1 and result.guide is None
    assert prompt_versions(llm) == ["inquiry-extraction-v6", "supervisor.selection.v1"]


@pytest.mark.parametrize(
    "broken",
    [select(**{REF: "TICKET"}), select(**{REF: "NOPE"}), select(), OutputRejected()],
)
async def test_an_invalid_or_failed_selection_falls_back_to_the_safe_default(broken) -> None:
    request = first_message_request(EA_SYMPTOM)

    # 안전한 기본 순서는 검색·평가 먼저, 그다음 답변이다. 모델이 어떻게 실패해도 같은 결과로 끝난다.
    result, _ = await run(request, *symptom_run(broken, broken))

    assert result.status == "SUCCEEDED" and result.guide is not None and result.questions == []


async def test_a_selection_the_budget_cannot_afford_uses_the_safe_default_without_the_model() -> (
    None
):
    request = first_message_request(EA_SYMPTOM)
    fields = request.model_dump(mode="json")
    fields["limits"]["maxModelCalls"] = 2
    request = AiRunRequest.model_validate(fields)

    result, llm = await run(request, InquiryExtraction(problems=[extracted()]))

    assert result.status == "SUCCEEDED", "선택이 예산 초과로 실행을 실패시키지 않는다"
    assert prompt_versions(llm) == ["inquiry-extraction-v6"]
    assert len(result.questions) == 1, "검색도 평가도 못 했지만 물을 수 있는 정보로 끝난다"


# --- 티켓 ---


async def test_a_ready_access_request_can_go_straight_to_the_ticket() -> None:
    request = first_message_request("전자결재 읽기 권한이 필요합니다. 업무 확인용입니다")
    access = extracted(
        intent="ACCESS_REQUEST",
        actualBehavior=None,
        requestedAccess=QuotedValue(messageId=MESSAGE, quote="읽기 권한"),
        businessPurpose=QuotedValue(messageId=MESSAGE, quote="업무 확인용"),
    )

    result, llm = await run(
        request,
        InquiryExtraction(problems=[access]),
        select(**{REF: "TICKET"}),
        ticket_decisions(REF),
    )

    assert len(result.ticketProposals) == 1 and result.guide is None and result.questions == []
    assert prompt_versions(llm) == [
        "inquiry-extraction-v6",
        "supervisor.selection.v1",
        "ticket.proposal.v2",
    ]


async def test_an_insufficient_guide_candidate_may_still_be_handed_to_the_staff() -> None:
    request = first_message_request(HOW_TO_TEXT)
    insufficient = draft(verdict="INSUFFICIENT", supportingEvidenceIds=[])
    # 사용법 문제가 가이드를 찾았지만 판정이 불충분하다. 물을 정보가 남아 있으면 질문이 먼저다.
    asked, _ = await run(request, InquiryExtraction(problems=[how_to()]), insufficient)
    assert len(asked.questions) == 1

    # 질문을 더 할 수 없으면 담당자 검토로 넘어가고 최종 응답에는 쓰지 않을 안내가 실리지 않는다.
    fields = request.model_dump(mode="json")
    fields["problems"] = [
        {
            "id": "00000000-0000-0000-0000-0000000000bb",
            "summary": "문서 조회 방법",
            "intent": "HOW_TO",
            "processingType": "KNOWLEDGE_QA",
            "reportedSystemId": request.systems[0].id,
            "reportedFeatureId": None,
            "state": "ANALYZING",
            "questionCount": 5,
            "standardRequest": None,
            "resolutionContext": None,
        }
    ]
    fields["problemIds"] = ["00000000-0000-0000-0000-0000000000bb"]
    # 기존 문제의 확인 사실은 그 문제에 연결된 메시지에서만 가져올 수 있다.
    fields["messages"][0]["problemIds"] = ["00000000-0000-0000-0000-0000000000bb"]
    request = AiRunRequest.model_validate(fields)
    ref = "00000000-0000-0000-0000-0000000000bb"

    result, _ = await run(
        request, InquiryExtraction(problems=[how_to()]), insufficient, ticket_decisions(ref)
    )

    assert result.guide is None and result.questions == []
    [ticket] = result.ticketProposals
    assert ticket.processingType == "REVIEW_REQUIRED"


# --- 여러 문제 ---


async def test_problems_end_with_different_actions_in_one_run() -> None:
    request = first_message_request("점심 메뉴를 추천해 주세요. 그리고 문서가 안 보여요")
    closed = extracted(intent="OUT_OF_SCOPE", systemCode=None, actualBehavior=None)
    unknown_system = extracted(
        systemCode=None, actualBehavior=QuotedValue(messageId=MESSAGE, quote="문서가 안 보여요")
    )

    result, llm = await run(request, InquiryExtraction(problems=[closed, unknown_system]))

    assert len(llm.requests) == 1
    assert "지원 범위 밖" in result.answer
    [question] = result.questions
    assert question.problemRef == "new:2" and "system" in question.requestedFields


async def test_a_second_answerable_problem_never_overwrites_the_first_guide() -> None:
    request = first_message_request("문서 조회 방법과 결재 문서 조회 화면 사용법을 알려주세요")
    first, second = how_to("문서 조회"), how_to("결재 문서 조회")

    result, _ = await run(
        request,
        InquiryExtraction(problems=[first, second]),
        draft(supportingEvidenceIds=[GUIDE.id]),
        draft(supportingEvidenceIds=[GUIDE.id]),
    )

    # 응답 계약이 안내를 하나만 허용한다. 첫 문제가 안내를 받고 둘째는 질문으로 간다.
    assert result.guide is not None and result.guide.problemRef == REF
    assert [question.problemRef for question in result.questions] == ["new:2"]


# --- 연결 ---


def test_the_pipeline_turns_the_problem_path_on_only_with_its_own_registry() -> None:
    llm = FakeLLMClient()
    source = RecordingSource()

    on = SupervisorPipeline(llm, knowledge_source=source, assess_evidence=True)
    off = SupervisorPipeline(llm, knowledge_source=source)
    injected = SupervisorPipeline(llm, agents=dict(build_registry(source)), assess_evidence=True)

    no_search = SupervisorPipeline(llm, assess_evidence=True)

    assert on._supervisor._problem_actions is True
    assert off._supervisor._problem_actions is False
    assert injected._supervisor._problem_actions is False
    assert no_search._supervisor._problem_actions is False, (
        "검색이 없으면 문제별 경로를 쓰지 않는다"
    )


def test_selection_context_carries_no_user_text_and_no_assessment_rationale() -> None:
    state = with_assessment(with_guide(base_state()))

    context = selection_context(state, {PROBLEM: frozenset({"ANSWER", "ASK"})})

    text = json.dumps(context, ensure_ascii=False)
    assert "내부용" not in text, "판정 이유는 모델 입력에 싣지 않는다"
    assert "알려주세요" not in text, "사용자 원문은 싣지 않는다"
    [item] = context["problems"]
    assert item["legalActions"] == ["ANSWER", "ASK"] and item["verdict"] == "SUFFICIENT"
