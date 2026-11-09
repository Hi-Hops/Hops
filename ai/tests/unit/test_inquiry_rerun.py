"""같은 실행에서 Inquiry를 다시 호출해도 이전 확인 사실을 이어받는지.

실제 모델 시험에서 Knowledge가 근거를 찾지 못한 뒤 Inquiry를 다시 부르면, 모델이 이전 추출을
모르고 다시 추출하면서 인용 구절이 달라져 병합이 거부됐다(확인 사실을 누락하면서 새 사실로
교체). 모델은 Fake이지만 두 번째 응답을 첫 번째와 일부러 다르게 줘서 그 상황을 재현한다.
"""

from hops_ai.agents.inquiry.extraction import (
    ExtractedProblem,
    InquiryExtraction,
    QuotedValue,
    UnknownClaim,
)
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.supervisor.graph import Supervisor
from tests.doubles.agents import runtime_registry, ticket_decisions
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
                keywords=[],
                featureCandidates=[],
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
    registry = runtime_registry(FixtureKnowledgeSource())
    return await Supervisor(registry).analyze(request, services_for(request, llm))


async def test_second_call_with_different_quotes_keeps_the_earlier_facts() -> None:
    # 두 번째 추출은 하려는 작업 구절을 놓치고 다른 구절만 새로 뽑는다.
    llm = script(
        extraction(currentWorkflow=SECOND_QUOTE, expectedBehavior=SECOND_QUOTE),
        decision("CALL_ROLE", AgentRole.TICKET),
        ticket_decisions("new:1"),
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
        ticket_decisions("new:1"),
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
    # 문제를 나눌 때 각 요약에 다른 문제의 내용을 넣지 않는다.
    assert "각 summary에 다른 문제의 내용을 넣지 않는다" in SYSTEM_PROMPT
    assert PROMPT_VERSION == "inquiry-extraction-v9"


async def test_rerun_quote_that_only_shortens_an_earlier_one_adds_no_duplicate_fact() -> None:
    # 두 번째 추출이 같은 항목을 더 짧은 구절로 다시 뽑아도 사실이 늘지 않는다.
    llm = script(
        extraction(taskOrScreen="결재선을 한 번에 바꾸는", expectedBehavior=FIRST_QUOTE),
        decision("CALL_ROLE", AgentRole.TICKET),
        ticket_decisions("new:1"),
        decision("PROPOSE_TICKETS"),
    )

    result = await analyze(llm)

    assert result.status == "SUCCEEDED", result.error
    [problem] = result.problemProposals
    texts = [fact.text for fact in problem.facts]
    assert texts.count(f"하려던 업무·화면: {FIRST_QUOTE}") == 1
    assert not any(text.endswith("결재선을 한 번에 바꾸는") for text in texts)
    # 다른 항목의 새 사실은 그대로 더해진다.
    assert f"기대한 동작: {FIRST_QUOTE}" in texts


async def test_rerun_quote_that_adds_information_is_kept_next_to_the_earlier_fact() -> None:
    longer = f"{FIRST_QUOTE}을 알려주세요"
    llm = script(
        extraction(taskOrScreen=longer, expectedBehavior=SECOND_QUOTE),
        decision("CALL_ROLE", AgentRole.TICKET),
        ticket_decisions("new:1"),
        decision("PROPOSE_TICKETS"),
    )

    result = await analyze(llm)

    assert result.status == "SUCCEEDED", result.error
    [problem] = result.problemProposals
    texts = [fact.text for fact in problem.facts]
    assert f"하려던 업무·화면: {FIRST_QUOTE}" in texts
    assert f"하려던 업무·화면: {longer}" in texts


# --- 사용자가 모른다고 한 항목은 이전 값이 비어 있어도 다시 묻지 않는다 ---


def unknown_expected_claim(text: str) -> UnknownClaim:
    return UnknownClaim(field="expectedBehavior", messageId=MESSAGE, quote=text)


async def test_unknown_answer_is_kept_when_the_previous_proposal_has_a_null_field() -> None:
    from hops_ai.agents.inquiry import ExtractionInquiryAgent
    from hops_ai.supervisor.execution import RoleRequest
    from tests.supervisor_fixtures import reserve
    from tests.unit.test_composite_inquiry import problem
    from tests.unit.test_knowledge_empty_handoff import empty_search
    from tests.unit.test_per_problem_handoff import _request_with_text, _with_problems

    request = _request_with_text(
        "전자결재 도장 이미지 등록 방법을 알려주세요. 원하는 결과는 모르겠어요"
    )
    # 이전 제안은 expectedBehavior가 비어 있다. Knowledge가 근거를 찾지 못한 뒤의 재분석이다.
    previous = problem("new:1", "도장 이미지 등록 방법 문의", taskOrScreen="도장 이미지 등록")
    state = _with_problems(request, previous, roleResults=empty_search())
    extraction = InquiryExtraction(
        problems=[
            ExtractedProblem(
                summary="도장 이미지 등록 방법 문의",
                intent="HOW_TO",
                systemCode="ELECTRONIC_APPROVAL",
                taskOrScreen=QuotedValue(messageId=MESSAGE, quote="도장 이미지 등록"),
                currentWorkflow=None,
                expectedBehavior=None,
                actualBehavior=None,
                occurredAt=None,
                requestedAccess=None,
                businessPurpose=None,
                desiredChange=None,
                unknownFields=[unknown_expected_claim("모르겠어요")],
                keywords=[],
                featureCandidates=[],
            )
        ]
    )
    llm = FakeLLMClient(extraction)

    result = await ExtractionInquiryAgent(guide_first=True).run(
        RoleRequest("inv-i", reserve(state, AgentRole.INQUIRY, "inv-i")),
        services_for(request, llm),
    )

    # 모른다고 한 항목은 다시 묻지 않고 미확인으로 보존한다.
    assert result.questions == []
    assert "기대한 동작" in result.problems[0].unknowns


async def test_unknown_answer_is_kept_for_a_field_empty_in_the_servers_standard_request() -> None:
    from hops_ai.agents.inquiry import ExtractionInquiryAgent
    from hops_ai.supervisor.execution import RoleRequest
    from tests.supervisor_fixtures import SYSTEM, make_details, reserve, running_state

    fields = make_request().model_dump(mode="json")
    fields["messages"][0]["content"] = (
        "전자결재 첨부파일이 열리지 않아요. 원래 어떻게 되어야 하는지는 모르겠어요"
    )
    details = {**make_details().model_dump(mode="json"), "expectedBehavior": None}
    fields["problems"][0]["standardRequest"] = {
        "summary": "첨부파일 열람 실패",
        "confirmedFacts": [],
        "assumptions": [],
        "unknowns": [],
        "responsibleSystemCandidateId": SYSTEM,
        "responsibilityEvidenceIds": [],
        "details": details,
        "knowledge": [],
    }
    request = AiRunRequest.model_validate(fields)
    extraction = InquiryExtraction(
        problems=[
            ExtractedProblem(
                summary="첨부파일 열람 실패",
                intent="OBSERVED_SYMPTOM",
                systemCode="ELECTRONIC_APPROVAL",
                taskOrScreen=None,
                currentWorkflow=None,
                expectedBehavior=None,
                actualBehavior=None,
                occurredAt=None,
                requestedAccess=None,
                businessPurpose=None,
                desiredChange=None,
                unknownFields=[unknown_expected_claim("모르겠어요")],
                keywords=[],
                featureCandidates=[],
            )
        ]
    )
    llm = FakeLLMClient(extraction)
    state = reserve(running_state(request), AgentRole.INQUIRY, "inv-i")

    result = await ExtractionInquiryAgent().run(
        RoleRequest("inv-i", state), services_for(request, llm)
    )

    asked = {name for question in result.questions for name in question.requestedFields}
    assert "expectedBehavior" not in asked
    assert "기대한 동작" in result.problems[0].unknowns


async def test_unknown_answer_is_not_recorded_as_a_known_fact() -> None:
    from hops_ai.agents.inquiry import ExtractionInquiryAgent
    from hops_ai.supervisor.execution import RoleRequest
    from tests.supervisor_fixtures import reserve
    from tests.unit.test_composite_inquiry import problem
    from tests.unit.test_knowledge_empty_handoff import empty_search
    from tests.unit.test_per_problem_handoff import _request_with_text, _with_problems

    request = _request_with_text(
        "전자결재 도장 이미지 등록 방법을 알려주세요. 원하는 결과는 모르겠어요"
    )
    # 이전 제안은 expectedBehavior가 비어 있다. Knowledge가 근거를 찾지 못한 뒤의 재분석이다.
    previous = problem("new:1", "도장 이미지 등록 방법 문의", taskOrScreen="도장 이미지 등록")
    state = _with_problems(request, previous, roleResults=empty_search())
    extraction = InquiryExtraction(
        problems=[
            ExtractedProblem(
                summary="도장 이미지 등록 방법 문의",
                intent="HOW_TO",
                systemCode="ELECTRONIC_APPROVAL",
                taskOrScreen=QuotedValue(messageId=MESSAGE, quote="도장 이미지 등록"),
                currentWorkflow=None,
                expectedBehavior=QuotedValue(messageId=MESSAGE, quote="모르겠어요"),
                actualBehavior=None,
                occurredAt=None,
                requestedAccess=None,
                businessPurpose=None,
                desiredChange=None,
                unknownFields=[unknown_expected_claim("모르겠어요")],
                keywords=[],
                featureCandidates=[],
            )
        ]
    )
    llm = FakeLLMClient(extraction)

    result = await ExtractionInquiryAgent(guide_first=True).run(
        RoleRequest("inv-i", reserve(state, AgentRole.INQUIRY, "inv-i")),
        services_for(request, llm),
    )

    # 모른다고 한 항목은 다시 묻지 않고 미확인으로 보존한다.
    assert result.questions == []
    assert "기대한 동작" in result.problems[0].unknowns

    assert result.problems[0].details.expectedBehavior is None
    assert not any("모르겠어요" in fact.text for fact in result.problems[0].facts)


def test_unknown_claim_does_not_discard_other_information_in_same_message() -> None:
    from hops_ai.agents.inquiry.extraction import ground

    request = request_with_text()
    message = request.messages[0]
    item = extraction(taskOrScreen=FIRST_QUOTE).problems[0]
    item = item.model_copy(
        update={"unknownFields": [unknown_expected_claim("없는 구절 모르겠어요")]}
    )
    assert ground(item, {message.id: message})["taskOrScreen"] == (message.id, FIRST_QUOTE)


def test_unknown_claim_does_not_discard_a_different_known_field() -> None:
    from hops_ai.agents.inquiry.extraction import ground

    fields = request_with_text().model_dump(mode="json")
    fields["messages"][0]["content"] = "결재선을 바꾸려는데 원하는 결과는 모르겠어요"
    request = AiRunRequest.model_validate(fields)
    item = extraction(taskOrScreen="결재선을 바꾸려는데").problems[0]
    item = item.model_copy(update={"unknownFields": [unknown_expected_claim("모르겠어요")]})
    assert ground(item, {MESSAGE: request.messages[0]})["taskOrScreen"][1] == "결재선을 바꾸려는데"


def test_invalid_unknown_claim_does_not_override_valid_quote_in_same_field() -> None:
    from hops_ai.agents.inquiry.extraction import ground

    fields = request_with_text().model_dump(mode="json")
    fields["messages"][0]["content"] = "결재선을 바꾸고 싶어요"
    request = AiRunRequest.model_validate(fields)
    item = extraction(expectedBehavior="결재선을 바꾸고 싶어요").problems[0]
    item = item.model_copy(
        update={"unknownFields": [unknown_expected_claim("없는 내용은 모르겠어요")]}
    )
    assert (
        ground(item, {MESSAGE: request.messages[0]})["expectedBehavior"][1]
        == "결재선을 바꾸고 싶어요"
    )
