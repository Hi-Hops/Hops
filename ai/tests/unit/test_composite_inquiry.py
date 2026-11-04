"""한 문의에 문제가 여럿일 때의 Knowledge 질의와 재검색 규칙.

한 문의의 문제들이 같은 원문을 나눠 쓰는데 원문 전체로 검색하면, 다른 문제의 단어가 질의에 섞여
자료가 없는 문제에 엉뚱한 안내가 붙었다(예: 도장 등록 질문에 문서 조회 가이드). 질의는 그 문제
자신의 정보로 만들고, 질의가 바뀌었을 때만 같은 문제를 다시 검색한다. 모델은 Fake이고 검색은
개발용 고정 자료 또는 대역이다.
"""

import json

import pytest

from hops_ai.agents.inquiry.extraction import ExtractedProblem, InquiryExtraction, QuotedValue
from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.agents.knowledge.agent import guidable_problem
from hops_ai.agents.registry import build_runtime_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import KnowledgeResult
from hops_ai.contracts.wire import AiRunRequest, RequestDetails
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.services.runs.supervisor import implemented_roles, selectable_roles
from hops_ai.supervisor.execution import execute_role
from hops_ai.supervisor.graph import Supervisor
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import MESSAGE, make_details
from tests.unit.test_knowledge_agent import RecordingSource
from tests.unit.test_knowledge_agent import services_for as knowledge_services
from tests.unit.test_knowledge_empty_handoff import how_to_problem, services_for
from tests.unit.test_knowledge_wiring import decision
from tests.unit.test_per_problem_handoff import _request_with_text, _with_problems

TWO_HOW_TOS = "전자결재 도장 이미지 등록 방법과 문서 조회 방법을 알려주세요"


def details(**values: str) -> RequestDetails:
    blank = {name: None for name in RequestDetails.model_fields if name != "acceptanceCriteria"}
    return make_details().model_copy(update={**blank, **values})


def problem(ref: str, summary: str, **values: str):
    return how_to_problem(ref).model_copy(update={"summary": summary, "details": details(**values)})


async def search_once(request: AiRunRequest, state, source: RecordingSource):
    agents = {AgentRole.KNOWLEDGE: SearchKnowledgeAgent(source)}
    return await execute_role(state, AgentRole.KNOWLEDGE, knowledge_services(request), agents)


# --- 질의는 그 문제 자신의 정보로 만든다 ---


async def test_query_of_a_problem_sharing_the_message_has_no_text_of_the_other_problem() -> None:
    request = _request_with_text(TWO_HOW_TOS)
    first = problem("new:1", "도장 이미지 등록 방법 문의", taskOrScreen="도장 이미지 등록")
    second = problem("new:2", "문서 조회 방법 문의", taskOrScreen="문서 조회 방법")
    source = RecordingSource()

    await search_once(request, _with_problems(request, first, second), source)

    [query] = source.queries
    assert query.text.split("\n") == ["도장 이미지 등록 방법 문의", "도장 이미지 등록"]
    assert "문서 조회" not in query.text


async def test_query_of_the_only_problem_still_uses_the_recent_messages() -> None:
    request = _request_with_text("전자결재 문서 조회 방법을 알려주세요")
    only = problem("new:1", "문서 조회 방법 문의", taskOrScreen="문서 조회")
    source = RecordingSource()

    await search_once(request, _with_problems(request, only), source)

    [query] = source.queries
    assert query.text.split("\n") == [
        "문서 조회 방법 문의",
        "문서 조회",
        "전자결재 문서 조회 방법을 알려주세요",
    ]


async def test_how_to_without_a_document_is_not_given_the_guide_of_another_problem() -> None:
    request = _request_with_text(TWO_HOW_TOS)
    first = problem("new:1", "도장 이미지 등록 방법 문의", taskOrScreen="도장 이미지 등록")
    second = problem("new:2", "문서 조회 방법 문의", taskOrScreen="문서 조회 방법")
    agents = {AgentRole.KNOWLEDGE: SearchKnowledgeAgent(FixtureKnowledgeSource())}

    state = await execute_role(
        _with_problems(request, first, second),
        AgentRole.KNOWLEDGE,
        knowledge_services(request),
        agents,
    )

    # 첫 문제(도장 등록)는 자료가 없어 안내 없이 EMPTY로 남고, 조회 가이드가 붙지 않는다.
    assert state["guide"] is None
    assert state["evidence"] == {}


# --- 질의가 같을 때만 같은 문제를 다시 검색하지 않는다 ---


async def test_result_records_the_query_digest_and_the_same_query_is_not_repeated() -> None:
    request = _request_with_text("전자결재 도장 이미지 등록 방법을 알려주세요")
    only = problem("new:1", "도장 이미지 등록 방법 문의", taskOrScreen="도장 이미지 등록")
    source = RecordingSource()

    state = await search_once(request, _with_problems(request, only), source)

    [result] = state["roleResults"].values()
    assert result.queryDigest == source.queries[0].digest()
    assert guidable_problem(state) is None


async def test_problem_can_be_searched_again_once_its_query_has_changed() -> None:
    request = _request_with_text("전자결재 도장 이미지 등록 방법을 알려주세요")
    only = problem("new:1", "도장 이미지 등록 방법 문의", taskOrScreen="도장 이미지 등록")
    state = await search_once(request, _with_problems(request, only), RecordingSource())
    assert guidable_problem(state) is None

    # Inquiry가 새 사실을 더해 질의가 달라졌다.
    richer = only.model_copy(
        update={
            "details": details(taskOrScreen="도장 이미지 등록", expectedBehavior="결재 문서에 찍기")
        }
    )
    changed = {**state, "problems": {"new:1": richer}}

    target = guidable_problem(changed)
    assert target is not None and target.problemRef == "new:1"


def test_result_without_a_digest_is_never_searched_again() -> None:
    request = _request_with_text("전자결재 도장 이미지 등록 방법을 알려주세요")
    only = problem("new:1", "도장 이미지 등록 방법 문의", taskOrScreen="도장 이미지 등록")
    legacy = KnowledgeResult.model_validate(
        {
            "invocationId": "inv-k",
            "search": {"state": "EMPTY", "evidence": [], "error": None},
            "guide": None,
            "problemRef": "new:1",
        }
    )
    state = _with_problems(request, only, roleResults=legacy)

    assert guidable_problem(state) is None


# --- 문의 조합별 전체 경로(Supervisor 대본) ---


def _quoted(text: str | None) -> QuotedValue | None:
    return QuotedValue(messageId=MESSAGE, quote=text) if text else None


def _extracted(summary, intent, system, *, task=None, actual=None) -> ExtractedProblem:
    return ExtractedProblem(
        summary=summary,
        intent=intent,
        systemCode=system,
        taskOrScreen=_quoted(task),
        currentWorkflow=None,
        expectedBehavior=None,
        actualBehavior=_quoted(actual),
        occurredAt=None,
        requestedAccess=None,
        businessPurpose=None,
        desiredChange=None,
        unknownFields=[],
    )


async def analyze(text: str, problems: list[ExtractedProblem], *script):
    request = _request_with_text(text)
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY), InquiryExtraction(problems=problems), *script
    )
    agents = build_runtime_registry(FixtureKnowledgeSource())
    roles = implemented_roles(agents)
    supervisor = Supervisor(agents, available_roles=lambda s: selectable_roles(agents, roles, s))
    return await supervisor.analyze(request, services_for(request, llm)), llm


async def test_how_to_and_a_symptom_answer_the_first_and_ask_about_the_second() -> None:
    result, _ = await analyze(
        "전자결재 문서 조회 방법을 알려주세요. 그리고 첨부파일이 열리지 않아요",
        [
            _extracted(
                "문서 조회 방법 문의", "HOW_TO", "ELECTRONIC_APPROVAL", task="문서 조회 방법"
            ),
            _extracted(
                "첨부파일 열람 실패",
                "OBSERVED_SYMPTOM",
                "ELECTRONIC_APPROVAL",
                task="첨부파일",
                actual="첨부파일이 열리지 않아요",
            ),
        ],
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("ASK_USER"),
    )

    assert result.status == "SUCCEEDED", result.error
    assert result.guide is not None and result.guide.problemRef == "new:1"
    assert [question.problemRef for question in result.questions] == ["new:2"]


async def test_how_to_without_a_document_beside_a_symptom_with_a_guide_keyword_gets_no_guide() -> (
    None
):
    result, _ = await analyze(
        "전자결재 도장 이미지 등록 방법을 알려주세요. 그리고 문서 조회가 안 돼요",
        [
            _extracted(
                "도장 이미지 등록 방법 문의",
                "HOW_TO",
                "ELECTRONIC_APPROVAL",
                task="도장 이미지 등록",
            ),
            _extracted(
                "문서 조회 불가",
                "OBSERVED_SYMPTOM",
                "ELECTRONIC_APPROVAL",
                task="문서 조회",
                actual="문서 조회가 안 돼요",
            ),
        ],
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.TICKET),
        decision("PROPOSE_TICKETS"),
    )

    assert result.status == "SUCCEEDED", result.error
    # 도장 등록 문제에 문서 조회 가이드가 붙지 않고, 자료가 없어 담당자 검토로 넘어간다.
    assert result.guide is None
    assert [(t.problemRef, t.processingType) for t in result.ticketProposals] == [
        ("new:1", "REVIEW_REQUIRED")
    ]
    assert [question.problemRef for question in result.questions] == ["new:2"]


async def test_two_how_tos_each_get_their_own_search_and_outcome() -> None:
    result, llm = await analyze(
        TWO_HOW_TOS,
        [
            _extracted(
                "도장 이미지 등록 방법 문의",
                "HOW_TO",
                "ELECTRONIC_APPROVAL",
                task="도장 이미지 등록",
            ),
            _extracted(
                "문서 조회 방법 문의", "HOW_TO", "ELECTRONIC_APPROVAL", task="문서 조회 방법"
            ),
        ],
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.TICKET),
        decision("PROPOSE_TICKETS"),
    )

    assert result.status == "SUCCEEDED", result.error
    # 자료가 있는 둘째 문제는 안내를, 자료가 없는 첫째 문제는 담당자 검토 티켓을 받는다.
    assert result.guide is not None and result.guide.problemRef == "new:2"
    assert [(t.problemRef, t.processingType) for t in result.ticketProposals] == [
        ("new:1", "REVIEW_REQUIRED")
    ]
    # 둘째 검색 직전에도 Knowledge가 선택지에 있다(첫 문제만 검색했다).
    before_second = json.loads(llm.requests[3].messages[-1].content)
    assert "KNOWLEDGE" in before_second["allowedRoles"]


@pytest.mark.xfail(
    strict=True,
    reason="공통 계약상 한 번의 답변에 안내는 하나다. 문제별 안내 계약이 정해지면 통과해야 한다",
)
async def test_two_how_tos_that_both_have_documents_are_each_guided() -> None:
    result, _ = await analyze(
        "전자결재 문서 조회 방법과 로그인 권한 신청 방법을 알려주세요",
        [
            _extracted(
                "문서 조회 방법 문의", "HOW_TO", "ELECTRONIC_APPROVAL", task="문서 조회 방법"
            ),
            _extracted("권한 신청 방법 문의", "HOW_TO", "COMMON_LOGIN", task="권한 신청 방법"),
        ],
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("PROVIDE_GUIDE"),
    )

    assert result.status == "SUCCEEDED", result.error
