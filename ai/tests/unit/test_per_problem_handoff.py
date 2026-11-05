"""안내를 만들지 못한 뒤의 담당자 전달이 검색한 문제에만 적용되는지.

코드 리뷰(PR #162)에서 지적된 두 결함의 회귀 확인이다.
- 한 문제의 검색 결과 없음이 검색하지 않은 문제까지 담당자 검토 티켓으로 만들었다.
- 직원 공개 가이드가 없는 검색 적중(예: 담당자용 런북만 걸림)은 EMPTY도 아니고 안내도 없어
  후속 경로가 하나도 열리지 않았다.
모델은 Fake이고 검색은 개발용 고정 자료 또는 대역이다.
"""

import json

import pytest

from hops_ai.agents.inquiry import ExtractionInquiryAgent
from hops_ai.agents.inquiry.extraction import ExtractedProblem, InquiryExtraction, QuotedValue
from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.agents.knowledge.agent import guidable_problem
from hops_ai.agents.registry import build_registry
from hops_ai.agents.ticket.proposal import TicketDecision, TicketDecisions
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import KnowledgeResult, SearchResult
from hops_ai.contracts.wire import AiRunRequest, GuideProposal
from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.progress import problems_without_guide
from hops_ai.rules.proposals import validate_ticket
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.services.runs.supervisor import implemented_roles, selectable_roles
from hops_ai.supervisor.execution import RoleRequest, execute_role
from hops_ai.supervisor.graph import Supervisor
from hops_ai.supervisor.merge import merge_knowledge_result
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import (
    MESSAGE,
    SYSTEM,
    authorize,
    make_evidence,
    make_request,
    reserve,
    running_state,
)
from tests.unit.test_knowledge_agent import RecordingSource
from tests.unit.test_knowledge_agent import services_for as knowledge_services
from tests.unit.test_knowledge_empty_handoff import (
    empty_search,
    error_search,
    how_to_problem,
    run_ticket,
    services_for,
    state_after,
)
from tests.unit.test_knowledge_wiring import decision


def found_without_guide(problem_ref: str | None = "new:1") -> KnowledgeResult:
    return KnowledgeResult(
        invocationId="inv-k",
        search=SearchResult(state="FOUND", evidence=[make_evidence()], error=None),
        guide=None,
        problemRef=problem_ref,
    )


# --- 어떤 문제가 안내를 만들지 못한 문제인가 ---


def test_empty_and_found_without_a_guide_both_count_for_the_searched_problem() -> None:
    assert problems_without_guide(state_after(empty_search(), how_to_problem())) == {"new:1"}
    assert problems_without_guide(state_after(found_without_guide(), how_to_problem())) == {"new:1"}


def test_search_error_a_missing_problem_ref_or_an_existing_guide_do_not_count() -> None:
    problem = how_to_problem()

    assert problems_without_guide(state_after(error_search(), problem)) == frozenset()
    assert problems_without_guide(state_after(empty_search(problem_ref=None), problem)) == (
        frozenset()
    )
    with_guide = KnowledgeResult(
        invocationId="inv-k",
        search=SearchResult(state="FOUND", evidence=[make_evidence()], error=None),
        guide=GuideProposal(
            messageText="안내", evidenceIds=[make_evidence().id], problemRef="new:1"
        ),
        problemRef="new:1",
    )
    assert problems_without_guide(state_after(with_guide, problem)) == frozenset()


# --- 검색하지 않은 문제는 티켓으로 넘어가지 않는다 ---


async def test_only_the_searched_problem_is_proposed_for_staff_review() -> None:
    state = state_after(empty_search(), how_to_problem("new:1"), how_to_problem("new:2"))

    _, result = await run_ticket(state)

    assert [proposal.problemRef for proposal in result.proposals] == ["new:1"]


async def test_merge_rejects_a_review_ticket_for_a_problem_that_was_not_searched() -> None:
    state = state_after(empty_search(), how_to_problem("new:1"), how_to_problem("new:2"))
    _, result = await run_ticket(state)
    [searched] = result.proposals
    unsearched = searched.model_copy(update={"problemRef": "new:2"})

    validate_ticket(state, searched)
    with pytest.raises(OutputRejected):
        validate_ticket(state, unsearched)


async def test_knowledge_searches_each_problem_once_then_has_nothing_left() -> None:
    request = _request_with_text("전자결재 도장 등록 방법과 로그인 방법을 알려주세요")
    source = RecordingSource()
    agents = {AgentRole.KNOWLEDGE: SearchKnowledgeAgent(source)}
    state = _with_problems(request, how_to_problem("new:1"), how_to_problem("new:2"))

    for _ in range(2):
        state = await execute_role(state, AgentRole.KNOWLEDGE, knowledge_services(request), agents)

    assert len(source.queries) == 2
    assert problems_without_guide(state) == {"new:1", "new:2"}
    assert guidable_problem(state) is None


def test_knowledge_result_must_name_an_existing_problem_and_match_its_guide() -> None:
    state = reserve(
        state_after(None, how_to_problem("new:1"), how_to_problem("new:2")),
        AgentRole.KNOWLEDGE,
        "inv-k",
    )

    with pytest.raises(OutputRejected):
        merge_knowledge_result(state, empty_search(problem_ref="new:9"), authorized_refs=[])

    evidence = make_evidence()
    mismatched = KnowledgeResult(
        invocationId="inv-k",
        search=SearchResult(state="FOUND", evidence=[evidence], error=None),
        guide=GuideProposal(messageText="안내", evidenceIds=[evidence.id], problemRef="new:2"),
        problemRef="new:1",
    )
    with pytest.raises(OutputRejected):
        merge_knowledge_result(
            state,
            mismatched,
            authorized_refs=[authorize(evidence)],
            verified_evidence=(evidence,),
        )


# --- Inquiry의 담당자 전달 질문도 검색한 문제에만 적용된다 ---


async def test_inquiry_asks_the_handoff_question_only_for_the_searched_problem() -> None:
    request = _request_with_text("전자결재 도장 등록 방법과 로그인 방법을 알려주세요")
    extraction = InquiryExtraction(
        problems=[
            _how_to("도장 등록 방법 문의", "ELECTRONIC_APPROVAL", "도장 등록"),
            _how_to("로그인 방법 문의", "COMMON_LOGIN", "로그인 방법"),
        ]
    )
    llm = FakeLLMClient(extraction)
    state = _with_problems(
        request, how_to_problem("new:1"), how_to_problem("new:2"), roleResults=empty_search()
    )
    reserved = reserve(state, AgentRole.INQUIRY, "inv-i")

    result = await ExtractionInquiryAgent(guide_first=True).run(
        RoleRequest("inv-i", reserved), services_for(request, llm)
    )

    # 검색한 첫 문제는 담당자 전달에 필요한 원하는 결과를 묻고, 검색하지 않은 둘째는 묻지 않는다.
    assert [(q.problemRef, q.requestedFields) for q in result.questions] == [
        ("new:1", ["expectedBehavior"])
    ]


# --- 안내를 만들 수 없는 검색 적중(담당자용 런북만 걸림) ---


async def test_a_staff_only_hit_ends_in_a_review_ticket_without_repeating_the_search() -> None:
    request = _request_with_text(
        "문서 미표시 점검 순서", purpose="SYSTEM_WORK", scopes=["EMPLOYEE", "SYSTEM_STAFF"]
    )
    quote = QuotedValue(messageId=MESSAGE, quote="문서 미표시 점검 순서")
    extraction = InquiryExtraction(
        problems=[
            ExtractedProblem(
                summary="미표시 점검 요청",
                intent="HOW_TO",
                systemCode="ELECTRONIC_APPROVAL",
                taskOrScreen=quote,
                currentWorkflow=None,
                expectedBehavior=quote,
                actualBehavior=None,
                occurredAt=None,
                requestedAccess=None,
                businessPurpose=None,
                desiredChange=None,
                unknownFields=[],
            )
        ]
    )
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        extraction,
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.TICKET),
        TicketDecisions(
            decisions=[
                TicketDecision(
                    problemRef="new:1",
                    title="도장 이미지 등록 검토",
                    responsibleSystemCandidateId=SYSTEM,
                    supports=[],
                    knowledgeEvidenceIds=[],
                )
            ]
        ),
        decision("PROPOSE_TICKETS"),
    )
    agents = build_registry(FixtureKnowledgeSource())
    roles = implemented_roles(agents)
    supervisor = Supervisor(agents, available_roles=lambda s: selectable_roles(agents, roles, s))

    result = await supervisor.analyze(request, services_for(request, llm))

    assert result.status == "SUCCEEDED", result.error
    assert result.guide is None
    [ticket] = result.ticketProposals
    assert ticket.processingType == "REVIEW_REQUIRED"
    assert [item.kind for item in result.evidence] == ["RUNBOOK"]

    after_search = json.loads(llm.requests[3].messages[-1].content)
    # 같은 검색을 다시 고를 수 없고, 정보가 갖춰져 Inquiry도 남지 않는다.
    assert after_search["allowedRoles"] == ["TICKET"]


# --- 입력 도우미 ---


def _request_with_text(
    text: str, *, purpose: str = "EMPLOYEE_HELP", scopes: list[str] | None = None
) -> AiRunRequest:
    fields = make_request().model_dump(mode="json")
    fields["problemIds"] = []
    fields["problems"] = []
    fields["messages"][0].update(content=text, problemIds=[])
    fields["accessContext"].update(purpose=purpose, allowedDocumentScopes=scopes or ["EMPLOYEE"])
    return AiRunRequest.model_validate(fields)


def _with_problems(request: AiRunRequest, *problems, roleResults: KnowledgeResult | None = None):
    state = running_state(request)
    results = {} if roleResults is None else {roleResults.invocationId: roleResults}
    return {
        **state,
        "problems": {problem.problemRef: problem for problem in problems},
        "roleResults": results,
    }


def _how_to(summary: str, system_code: str, task: str) -> ExtractedProblem:
    return ExtractedProblem(
        summary=summary,
        intent="HOW_TO",
        systemCode=system_code,
        taskOrScreen=QuotedValue(messageId=MESSAGE, quote=task),
        currentWorkflow=None,
        expectedBehavior=None,
        actualBehavior=None,
        occurredAt=None,
        requestedAccess=None,
        businessPurpose=None,
        desiredChange=None,
        unknownFields=[],
    )
