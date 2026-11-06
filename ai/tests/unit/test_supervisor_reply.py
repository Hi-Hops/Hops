"""Supervisor가 문의 분석 없이 대화로 답하는 REPLY 경로.

Supervisor가 메시지의 의도를 판단한다. 인사만 있으면 GREETING, 의도를 알 수 없으면 CLARIFY로
답하고, 문의가 있으면 Inquiry에 분석을 맡긴다. 모델이 인사라고 해도 코드가 확인한 인사 표현이
아니면 되묻기로 낮춰 문의를 조용히 끝내지 않는다. 모델은 Fake이므로 실제 분류 정확도는
여기서 검증하지 않는다.
"""

import json

import pytest

from hops_ai.agents.inquiry import ExtractionInquiryAgent
from hops_ai.agents.inquiry.extraction import InquiryExtraction, QuotedValue
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.harness.errors import OutputRejected
from hops_ai.supervisor.decision import SupervisorDecision, validate_supervisor_decision
from hops_ai.supervisor.execution import RoleRequest
from hops_ai.supervisor.graph import Supervisor
from hops_ai.supervisor.merge import merge_inquiry_result
from hops_ai.supervisor.validation import only_greetings
from tests.supervisor_fixtures import make_request, reserve, running_state
from tests.unit.test_inquiry_agent import (
    EA_SYMPTOM,
    extracted,
    first_message_request,
    services_for,
)

GREETING_ANSWER = "안녕하세요. 어떤 IT 관련 문의가 있으신가요? 내용을 알려 주세요."
FOLLOW_UP_GREETING_ANSWER = "안녕하세요. 이어서 문의 내용을 알려 주세요."
CLARIFY_ANSWER = "보내주신 내용을 정확히 파악하지 못했어요. 문의 내용을 조금 더 알려주시겠어요?"


def reply(kind: str) -> SupervisorDecision:
    return SupervisorDecision(
        nextAction="REPLY", role=None, publicSummary="대화 응답", replyKind=kind
    )


def call_inquiry() -> SupervisorDecision:
    return SupervisorDecision(nextAction="CALL_ROLE", role=AgentRole.INQUIRY, publicSummary="분석")


async def analyze(request: AiRunRequest, *outcomes):
    services, llm = services_for(request, *outcomes)
    supervisor = Supervisor({AgentRole.INQUIRY: ExtractionInquiryAgent()}, restrict_roles=True)
    return await supervisor.analyze(request, services), llm


def with_extra_user_message(request: AiRunRequest, content: str, **changes) -> AiRunRequest:
    fields = request.model_dump(mode="json")
    last = fields["messages"][-1]
    fields["messages"].append(
        {
            **fields["messages"][0],
            "id": "00000000-0000-0000-0000-000000000098",
            "sequenceNo": last["sequenceNo"] + 1,
            "content": content,
            **changes,
        }
    )
    return AiRunRequest.model_validate(fields)


@pytest.mark.parametrize(
    "message", ["안녕하세요", "안녕하세요!", "안녕", "안녕하세요, 반갑습니다", "Hello!"]
)
async def test_greeting_is_answered_by_the_supervisor_without_inquiry(message) -> None:
    result, llm = await analyze(first_message_request(message), reply("GREETING"))

    assert result.status == "SUCCEEDED"
    assert result.answer == GREETING_ANSWER
    assert result.problemProposals == [] and result.questions == []
    assert result.ticketProposals == [] and result.guide is None
    assert result.requiresFollowUp is True
    # Supervisor 판단 한 번으로 끝나고 Inquiry 모델은 호출하지 않는다.
    assert len(llm.requests) == 1


async def test_greeting_with_existing_problem_keeps_it_unchanged() -> None:
    fields = make_request().model_dump(mode="json")
    fields["messages"][0]["content"] = "안녕하세요"
    request = AiRunRequest.model_validate(fields)

    result, _ = await analyze(request, reply("GREETING"))

    assert result.answer == FOLLOW_UP_GREETING_ANSWER
    assert result.problemProposals == [] and result.questions == [] and result.evidence == []
    assert result.requiresFollowUp is True


@pytest.mark.parametrize(
    "message", ["안녕하세요 로그인이 안 돼요", "안녕하세요! 도움이 필요해요", "점심 메뉴 추천해줘"]
)
async def test_wrong_greeting_decision_is_lowered_to_clarify(message) -> None:
    result, _ = await analyze(first_message_request(message), reply("GREETING"))

    assert result.answer == CLARIFY_ANSWER
    assert not result.problemProposals and not result.questions
    assert result.requiresFollowUp is True


async def test_greeting_does_not_hide_an_earlier_unanalyzed_message() -> None:
    request = with_extra_user_message(
        first_message_request("로그인이 안 돼요", extra_ai_message=True), "안녕하세요"
    )

    result, _ = await analyze(request, reply("GREETING"))

    assert result.answer == CLARIFY_ANSWER


async def test_greeting_does_not_hide_an_unlinked_message_when_a_problem_exists() -> None:
    fields = make_request().model_dump(mode="json")
    fields["messages"][0].update(content="로그인도 안 돼요", problemIds=[])
    request = with_extra_user_message(AiRunRequest.model_validate(fields), "안녕하세요")

    result, _ = await analyze(request, reply("GREETING"))

    assert result.answer == CLARIFY_ANSWER


async def test_unclear_message_is_clarified() -> None:
    result, _ = await analyze(first_message_request("음..."), reply("CLARIFY"))

    assert result.answer == CLARIFY_ANSWER
    assert result.requiresFollowUp is True


async def test_greeting_mixed_with_an_inquiry_is_analyzed_by_inquiry() -> None:
    text = f"안녕하세요, {EA_SYMPTOM}"
    request = first_message_request(text)
    problem = extracted(
        actualBehavior=QuotedValue(messageId=request.messages[0].id, quote="결재 문서가 안 보여요")
    )

    result, llm = await analyze(
        request,
        call_inquiry(),
        InquiryExtraction(problems=[problem]),
        SupervisorDecision(nextAction="ASK_USER", role=None, publicSummary="추가 정보 요청"),
    )

    assert result.status == "SUCCEEDED"
    assert result.problemProposals[0].intent == "OBSERVED_SYMPTOM"
    assert result.questions
    assert result.requiresFollowUp is False
    assert text in llm.requests[1].messages[1].content


async def test_empty_inquiry_result_is_clarified_instead_of_failing() -> None:
    """Supervisor가 인사를 Inquiry로 보냈고 문제가 없으면 실패 대신 되묻는다."""
    result, llm = await analyze(
        first_message_request("안녕하세요"),
        call_inquiry(),
        InquiryExtraction(problems=[]),
        reply("CLARIFY"),
    )

    assert result.status == "SUCCEEDED"
    assert result.answer == CLARIFY_ANSWER
    assert result.requiresFollowUp is True
    # 문제가 없었으므로 다음 판단에서는 Inquiry를 다시 고를 수 없다.
    assert "INQUIRY" not in json.loads(llm.requests[2].messages[1].content)["allowedRoles"]


async def test_reply_is_rejected_after_inquiry_found_a_problem() -> None:
    request = first_message_request()
    state = reserve(running_state(request), AgentRole.INQUIRY, "inv-1")
    services, _ = services_for(request, InquiryExtraction(problems=[extracted()]))
    result = await ExtractionInquiryAgent().run(RoleRequest("inv-1", state), services)
    merged = merge_inquiry_result(state, result)

    with pytest.raises(OutputRejected, match="대화 응답"):
        validate_supervisor_decision(merged, reply("GREETING"))


def test_reply_kind_belongs_only_to_reply() -> None:
    with pytest.raises(ValueError):
        SupervisorDecision(nextAction="REPLY", role=None, publicSummary="응답")
    with pytest.raises(ValueError):
        SupervisorDecision(
            nextAction="FINISH", role=None, publicSummary="종료", replyKind="GREETING"
        )


def test_case_draft_cannot_reply() -> None:
    state = running_state(make_request(kind="CASE_DRAFT"))

    with pytest.raises(OutputRejected):
        validate_supervisor_decision(state, reply("CLARIFY"))


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("안녕하세요", True),
        ("hi~", True),
        ("", False),
        ("안녕하세요 로그인", False),
        ("하이", False),
    ],
)
def test_only_greetings_accepts_listed_expressions(text, expected) -> None:
    assert only_greetings(text) is expected
