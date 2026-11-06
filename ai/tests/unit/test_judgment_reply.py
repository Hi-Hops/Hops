"""문제별 행동 경로(판정 모드)의 대화 응답(REPLY). 모델은 Fake다.

판정 모드는 항상 분석부터 시작하므로, 인사만 보낸 상담이 빈 추출로 실패하고 처리 중인 문제가 있을
때 인사가 이전 질문을 반복하게 만드는 문제(#224)가 이 경로에서 되살아난다. 기존 역할 단위 경로와
같이 확인된 인사·감사 표현만 있으면 분석 없이, 분석이 문제를 찾지 못하면 되묻기로 답하는지
확인한다. 두 경우 모두 합법 행동이 REPLY 하나뿐이라 모델을 부르지 않는다.
"""

import pytest

from hops_ai.agents.inquiry import ExtractionInquiryAgent
from hops_ai.agents.inquiry.extraction import InquiryExtraction, QuotedValue
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.harness.errors import OutputRejected
from hops_ai.services.runs.supervisor import SupervisorPipeline
from hops_ai.supervisor.decision import SupervisorDecision
from hops_ai.supervisor.decisions import apply_reply
from hops_ai.supervisor.execution import RoleRequest
from hops_ai.supervisor.merge import merge_inquiry_result
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import make_request, reserve, running_state
from tests.unit.test_inquiry_agent import (
    EA_SYMPTOM,
    extracted,
    first_message_request,
    services_for,
)
from tests.unit.test_knowledge_agent import RecordingSource
from tests.unit.test_knowledge_wiring import services_for as runtime_services_for
from tests.unit.test_problem_graph import REF, prompt_versions, run, select
from tests.unit.test_supervisor_reply import (
    CLARIFY_ANSWER,
    FOLLOW_UP_GREETING_ANSWER,
    GREETING_ANSWER,
    THANKS_ANSWER,
    pending_problem_conversation,
    with_extra_user_message,
)


@pytest.mark.parametrize(
    "message", ["안녕하세요", "안녕하세요!", "안녕", "안녕하세요, 반갑습니다", "Hello!"]
)
async def test_a_greeting_is_answered_without_any_model_call(message) -> None:
    result, llm = await run(first_message_request(message))

    assert result.status == "SUCCEEDED"
    assert result.answer == GREETING_ANSWER
    assert result.problemProposals == [] and result.questions == []
    assert result.ticketProposals == [] and result.guide is None and result.evidence == []
    assert result.requiresFollowUp is False
    assert llm.requests == [], "선택지가 하나뿐이면 Inquiry도 Supervisor 모델도 부르지 않는다"


async def test_a_greeting_with_an_existing_problem_leaves_it_unchanged() -> None:
    fields = make_request().model_dump(mode="json")
    fields["messages"][0]["content"] = "안녕하세요"

    result, llm = await run(AiRunRequest.model_validate(fields))

    assert result.answer == FOLLOW_UP_GREETING_ANSWER
    assert result.problemProposals == [] and result.questions == [] and result.evidence == []
    assert result.requiresFollowUp is False
    assert llm.requests == []


async def test_thanks_after_a_pending_problem_does_not_repeat_the_question() -> None:
    result, llm = await run(pending_problem_conversation("감사합니다!"))

    assert result.answer == THANKS_ANSWER
    assert result.problemProposals == [] and result.questions == []
    assert result.requiresFollowUp is False
    assert llm.requests == []


async def test_thanks_after_resolution_replies_instead_of_restating_the_result() -> None:
    fields = pending_problem_conversation("고맙습니다").model_dump(mode="json")
    fields["problems"][0]["state"] = "RESOLVED"

    result, llm = await run(AiRunRequest.model_validate(fields))

    assert result.answer == THANKS_ANSWER
    assert result.requiresFollowUp is False
    assert llm.requests == []


@pytest.mark.parametrize(
    "request_",
    [
        first_message_request("안녕하세요, 로그인이 안 돼요"),
        with_extra_user_message(
            first_message_request("로그인이 안 돼요", extra_ai_message=True), "안녕하세요"
        ),
        pending_problem_conversation("오늘 2시부터요", "안녕하세요"),
        pending_problem_conversation("안녕하세요 오늘 2시부터요"),
    ],
    ids=["mixed", "unanalyzed-before-greeting", "supplement-then-greeting", "mixed-supplement"],
)
async def test_content_to_analyze_still_goes_to_inquiry(request_) -> None:
    """인사가 섞여도 분석할 내용이 있으면 분석한다. 문제를 못 찾으면 되묻는다."""
    result, llm = await run(request_, InquiryExtraction(problems=[]))

    assert prompt_versions(llm) == ["inquiry-extraction-v6"]
    assert result.answer == CLARIFY_ANSWER
    assert result.requiresFollowUp is True


async def test_a_greeting_mixed_with_an_inquiry_is_analyzed() -> None:
    request = first_message_request(f"안녕하세요, {EA_SYMPTOM}")
    problem = extracted(
        actualBehavior=QuotedValue(messageId=request.messages[0].id, quote="결재 문서가 안 보여요")
    )

    result, llm = await run(request, InquiryExtraction(problems=[problem]), select(**{REF: "ASK"}))

    assert result.status == "SUCCEEDED"
    assert result.problemProposals[0].intent == "OBSERVED_SYMPTOM"
    assert prompt_versions(llm)[:2] == ["inquiry-extraction-v6", "supervisor.selection.v1"]
    assert result.questions, "문의가 있으면 인사가 섞여 있어도 분석해 문제별 행동을 고른다"
    assert result.requiresFollowUp is False


async def test_an_empty_analysis_is_clarified_instead_of_failing() -> None:
    result, llm = await run(
        first_message_request("혹시 시간 괜찮으세요?"), InquiryExtraction(problems=[])
    )

    assert result.status == "SUCCEEDED"
    assert result.answer == CLARIFY_ANSWER
    assert result.requiresFollowUp is True
    assert result.problemProposals == [] and result.evidence == []
    assert prompt_versions(llm) == ["inquiry-extraction-v6"], "같은 분석을 반복하지 않는다"


async def test_only_a_clarification_waits_for_the_user() -> None:
    thanks, _ = await run(pending_problem_conversation("감사합니다"))
    unclear, _ = await run(first_message_request("음..."), InquiryExtraction(problems=[]))

    assert thanks.requiresFollowUp is False
    assert unclear.requiresFollowUp is True


async def test_a_reply_cannot_close_a_run_whose_analysis_found_a_problem() -> None:
    request = first_message_request()
    state = reserve(running_state(request), AgentRole.INQUIRY, "inv-1")
    services, _ = services_for(request, InquiryExtraction(problems=[extracted()]))
    result = await ExtractionInquiryAgent().run(RoleRequest("inv-1", state), services)

    with pytest.raises(OutputRejected, match="대화 응답"):
        apply_reply(merge_inquiry_result(state, result))


# --- 설정으로 켠 실제 구성(SupervisorPipeline)을 통과한다 ---


def flag_on_pipeline(llm) -> SupervisorPipeline:
    """`HOPS_AI_KNOWLEDGE_ASSESSMENT`를 켠 main.py의 구성과 같다: 검색 연결과 판정 켜기만 준다."""
    return SupervisorPipeline(llm, knowledge_source=RecordingSource(), assess_evidence=True)


@pytest.mark.parametrize(
    ("request_", "answer", "waits"),
    [
        (first_message_request("안녕하세요"), GREETING_ANSWER, False),
        (pending_problem_conversation("안녕하세요"), FOLLOW_UP_GREETING_ANSWER, False),
        (pending_problem_conversation("감사합니다!"), THANKS_ANSWER, False),
    ],
    ids=["greeting", "greeting-with-pending-problem", "thanks-with-pending-problem"],
)
async def test_the_flag_on_configuration_replies_to_courtesy_without_a_model_call(
    request_, answer, waits
) -> None:
    llm = FakeLLMClient()
    pipeline = flag_on_pipeline(llm)
    assert pipeline._supervisor._problem_actions is True  # noqa: SLF001

    result = await pipeline.run(request_, runtime_services_for(request_, llm).runtime)

    assert result.status == "SUCCEEDED" and result.answer == answer
    assert result.requiresFollowUp is waits
    assert llm.requests == []


async def test_the_flag_on_configuration_clarifies_when_analysis_finds_nothing() -> None:
    request = first_message_request("혹시 시간 괜찮으세요?")
    llm = FakeLLMClient(InquiryExtraction(problems=[]))

    result = await flag_on_pipeline(llm).run(request, runtime_services_for(request, llm).runtime)

    assert result.status == "SUCCEEDED" and result.answer == CLARIFY_ANSWER
    assert result.requiresFollowUp is True


@pytest.mark.parametrize("message", ["안녕하세요", "감사합니다!"])
async def test_flag_on_and_flag_off_give_the_same_reply(message) -> None:
    """켜고 끄는 것이 인사·감사 응답의 내용을 바꾸지 않는다. 다른 점은 모델 호출 수뿐이다."""
    request = first_message_request(message)
    kind = "THANKS" if "감사" in message else "GREETING"
    off_llm = FakeLLMClient(
        SupervisorDecision(nextAction="REPLY", role=None, publicSummary="대화 응답", replyKind=kind)
    )
    on_llm = FakeLLMClient()

    off = await SupervisorPipeline(off_llm, knowledge_source=RecordingSource()).run(
        request, runtime_services_for(request, off_llm).runtime
    )
    on = await flag_on_pipeline(on_llm).run(request, runtime_services_for(request, on_llm).runtime)

    assert (on.status, on.answer, on.requiresFollowUp) == (
        off.status,
        off.answer,
        off.requiresFollowUp,
    )
    assert len(off_llm.requests) == 1 and on_llm.requests == []
