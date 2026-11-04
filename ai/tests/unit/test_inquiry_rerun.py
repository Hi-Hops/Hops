"""같은 실행에서 Inquiry를 다시 호출해도 이전 확인 사실을 이어받는지.

실제 모델 시험에서 Knowledge가 근거를 찾지 못한 뒤 Inquiry를 다시 부르면, 모델이 이전 추출을
모르고 다시 추출하면서 인용 구절이 달라져 병합이 거부됐다(확인 사실을 누락하면서 새 사실로
교체). 모델은 Fake이지만 두 번째 응답을 첫 번째와 일부러 다르게 줘서 그 상황을 재현한다.
"""

from hops_ai.agents.inquiry.extraction import ExtractedProblem, InquiryExtraction, QuotedValue
from hops_ai.agents.registry import build_runtime_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.supervisor.graph import Supervisor
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import MESSAGE, make_request
from tests.unit.test_knowledge_wiring import decision, services_for

TEXT = (
    "전자결재에서 결재선을 한 번에 바꾸는 방법을 알려주세요. "
    "대기 문서 20건을 하나씩 열지 않고 바꾸고 싶어요"
)
FIRST_QUOTE = "결재선을 한 번에 바꾸는 방법"
SECOND_QUOTE = "대기 문서 20건을 하나씩 열지 않고 바꾸고 싶어요"


def request_with_text() -> AiRunRequest:
    fields = make_request().model_dump(mode="json")
    fields["problemIds"] = []
    fields["problems"] = []
    fields["messages"][0].update(content=TEXT, problemIds=[])
    return AiRunRequest.model_validate(fields)


def extraction(**quotes: str | None) -> InquiryExtraction:
    def quoted(name: str) -> QuotedValue | None:
        value = quotes.get(name)
        return QuotedValue(messageId=MESSAGE, quote=value) if value else None

    return InquiryExtraction(
        problems=[
            ExtractedProblem(
                summary="결재선 일괄 변경 방법 문의",
                intent="HOW_TO",
                systemCode="ELECTRONIC_APPROVAL",
                taskOrScreen=quoted("taskOrScreen"),
                currentWorkflow=quoted("currentWorkflow"),
                expectedBehavior=quoted("expectedBehavior"),
                actualBehavior=None,
                occurredAt=None,
                requestedAccess=None,
                businessPurpose=None,
                desiredChange=None,
                unknownFields=[],
            )
        ]
    )


def script(second: InquiryExtraction, *tail):
    return FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        extraction(taskOrScreen=FIRST_QUOTE),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        second,
        *tail,
    )


async def analyze(llm: FakeLLMClient):
    request = request_with_text()
    registry = build_runtime_registry(FixtureKnowledgeSource())
    return await Supervisor(registry).analyze(request, services_for(request, llm))


async def test_second_call_with_different_quotes_keeps_the_earlier_facts() -> None:
    # 두 번째 추출은 하려는 작업 구절을 놓치고 다른 구절만 새로 뽑는다.
    llm = script(
        extraction(currentWorkflow=SECOND_QUOTE, expectedBehavior=SECOND_QUOTE),
        decision("CALL_ROLE", AgentRole.TICKET),
        decision("PROPOSE_TICKETS"),
    )

    result = await analyze(llm)

    assert result.status == "SUCCEEDED", result.error
    [ticket] = result.ticketProposals
    assert ticket.processingType == "REVIEW_REQUIRED"
    [problem] = result.problemProposals
    texts = [fact.text for fact in problem.facts]
    assert any(FIRST_QUOTE in text for text in texts)
    assert any(SECOND_QUOTE in text for text in texts)


async def test_field_found_earlier_is_not_asked_again_when_the_rerun_misses_it() -> None:
    # 첫 추출에서 확인한 작업 구절을 두 번째 추출이 놓쳐도 다시 묻지 않는다.
    llm = script(
        extraction(expectedBehavior=SECOND_QUOTE),
        decision("CALL_ROLE", AgentRole.TICKET),
        decision("PROPOSE_TICKETS"),
    )

    result = await analyze(llm)

    assert result.status == "SUCCEEDED", result.error
    assert result.questions == []
    assert len(result.ticketProposals) == 1


async def test_rerun_that_finds_nothing_new_changes_nothing() -> None:
    llm = script(extraction(taskOrScreen=FIRST_QUOTE), decision("ASK_USER"))

    result = await analyze(llm)

    assert result.status == "SUCCEEDED", result.error
    [question] = result.questions
    assert question.requestedFields == ["expectedBehavior"]


def test_extraction_prompt_places_the_how_to_goal_in_expected_behavior() -> None:
    from hops_ai.agents.inquiry.extraction import PROMPT_VERSION, SYSTEM_PROMPT

    assert "expectedBehavior에" in SYSTEM_PROMPT and "HOW_TO" in SYSTEM_PROMPT
    assert PROMPT_VERSION == "inquiry-extraction-v4"
