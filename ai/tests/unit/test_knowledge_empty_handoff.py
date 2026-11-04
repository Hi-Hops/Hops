"""가이드를 찾지 못한(Knowledge EMPTY) 사용법 문제의 후속 처리.

EMPTY 뒤에는 Inquiry로 더 묻는 길과 Ticket으로 담당자 검토에 넘기는 길이 모두 유효하다.
어느 쪽을 고를지는 Supervisor의 판단이며 코드는 역할 선택을 제한하지 않는다.
모델은 Fake이므로 실제 모델의 판단 품질은 검증하지 않는다.
"""

import json
from copy import deepcopy

import pytest

from hops_ai.agents.inquiry.extraction import ExtractedProblem, InquiryExtraction, QuotedValue
from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.agents.registry import build_runtime_registry
from hops_ai.agents.ticket import RuleTicketAgent
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import KnowledgeResult, SearchError, SearchResult, TicketResult
from hops_ai.contracts.wire import AiRunRequest, ProblemProposal
from hops_ai.harness.errors import OutputRejected
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.services.retrieval.neo4j import Neo4jRetriever
from hops_ai.services.retrieval.pgvector import PgVectorRetriever
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.disabled import DisabledToolGateway
from hops_ai.settings import Settings
from hops_ai.supervisor.bootstrap import create_run_services
from hops_ai.supervisor.decision import SupervisorDecision
from hops_ai.supervisor.execution import RoleRequest
from hops_ai.supervisor.finalize import finalize_result
from hops_ai.supervisor.graph import Supervisor
from hops_ai.supervisor.merge import merge_ticket_result
from hops_ai.supervisor.state import transition_phase
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import (
    MESSAGE,
    SYSTEM,
    make_problem,
    make_ticket,
    reserve,
    running_state,
)
from tests.unit.test_knowledge_wiring import how_to_first_message

# 고정 가이드에 없는 사용법 질문이라 Knowledge가 EMPTY를 낸다.
NO_GUIDE_TEXT = "전자결재 도장 이미지 등록 방법을 알려주세요"


def services_for(request: AiRunRequest, llm: FakeLLMClient | None = None) -> SharedServices:
    return create_run_services(
        request,
        settings=Settings(_env_file=None),
        access_scope_ref=f"test-scope:{request.runId}",
        llm=llm or FakeLLMClient(),
        vector=PgVectorRetriever(),
        graph=Neo4jRetriever(),
        tools=DisabledToolGateway(),
    )


def empty_search(invocation: str = "inv-k") -> KnowledgeResult:
    return KnowledgeResult(
        invocationId=invocation,
        search=SearchResult(state="EMPTY", evidence=[], error=None),
        guide=None,
    )


def error_search(invocation: str = "inv-k") -> KnowledgeResult:
    error = SearchError(
        code="SEARCH_UNAVAILABLE",
        message="검색 시스템에 일시적으로 연결하지 못했다",
        requestId="00000000-0000-4000-8000-000000000001",
        retryable=True,
        fieldErrors=[],
    )
    return KnowledgeResult(
        invocationId=invocation,
        search=SearchResult(state="ERROR", evidence=[], error=error),
        guide=None,
    )


def how_to_problem(ref: str = "new:1", **changes) -> ProblemProposal:
    return make_problem(ref).model_copy(
        update={"intent": "HOW_TO", "processingType": "KNOWLEDGE_QA", **changes}
    )


def state_after(search: KnowledgeResult | None, *problems: ProblemProposal):
    """Inquiry가 문제를 만든 뒤 Knowledge 결과가 쌓인 RUNNING State."""
    request = how_to_first_message()
    state = running_state(request)
    results = {} if search is None else {search.invocationId: search}
    return {
        **state,
        "problems": {problem.problemRef: problem for problem in problems},
        "roleResults": results,
    }


async def run_ticket(state):
    reserved = reserve(state, AgentRole.TICKET, "inv-t")
    result = await RuleTicketAgent().run(
        RoleRequest("inv-t", reserved), services_for(state["input"])
    )
    return reserved, result


# --- Ticket: EMPTY 뒤의 사용법 문제를 담당자 검토 티켓으로 ---


async def test_empty_search_lets_a_how_to_problem_go_to_staff_review() -> None:
    reserved, result = await run_ticket(state_after(empty_search(), how_to_problem()))

    [proposal] = result.proposals
    assert proposal.processingType == "REVIEW_REQUIRED"
    assert proposal.responsibleSystemCandidateId == SYSTEM
    merged = merge_ticket_result(reserved, result)
    # 최종 결과에서 문제와 티켓의 처리 유형이 같다.
    assert merged["problems"]["new:1"].processingType == "REVIEW_REQUIRED"
    assert merged["problems"]["new:1"].intent == "HOW_TO"


@pytest.mark.parametrize("search", [None, "error"], ids=["not searched", "search error"])
async def test_how_to_is_not_ticketed_without_an_empty_search(search) -> None:
    result = error_search() if search == "error" else None

    with pytest.raises(OutputRejected):
        await run_ticket(state_after(result, how_to_problem()))


async def test_empty_search_never_ticketes_a_problem_without_a_confirmed_system() -> None:
    with pytest.raises(OutputRejected):
        await run_ticket(
            state_after(empty_search(), how_to_problem(reportedSystemCandidateId=None))
        )


async def test_other_problem_types_keep_their_own_processing_type() -> None:
    problem = how_to_problem(intent="OBSERVED_SYMPTOM", processingType="INVESTIGATION")

    _, result = await run_ticket(state_after(empty_search(), problem))

    assert result.proposals[0].processingType == "INVESTIGATION"


# --- 병합 검증: 예외는 EMPTY 뒤 KNOWLEDGE_QA → REVIEW_REQUIRED 하나뿐 ---


def ticket_for(processing_type: str):
    ticket = make_ticket("new:1")
    return ticket.model_copy(update={"processingType": processing_type})


@pytest.mark.parametrize("processing_type", ["INVESTIGATION", "SERVICE_REQUEST", "CHANGE_REQUEST"])
def test_only_review_required_is_accepted_for_a_how_to_after_empty(processing_type) -> None:
    state = reserve(state_after(empty_search(), how_to_problem()), AgentRole.TICKET, "t1")
    before = deepcopy(state)

    with pytest.raises(OutputRejected, match="처리 유형"):
        merge_ticket_result(
            state, TicketResult(invocationId="t1", proposals=[ticket_for(processing_type)])
        )

    assert state == before


@pytest.mark.parametrize("search", [None, "error"], ids=["not searched", "search error"])
def test_review_required_for_a_how_to_is_rejected_unless_the_search_was_empty(search) -> None:
    result = error_search() if search == "error" else None
    state = reserve(state_after(result, how_to_problem()), AgentRole.TICKET, "t1")

    with pytest.raises(OutputRejected, match="처리 유형"):
        merge_ticket_result(
            state, TicketResult(invocationId="t1", proposals=[ticket_for("REVIEW_REQUIRED")])
        )


def test_the_exception_does_not_apply_to_other_processing_types() -> None:
    problem = how_to_problem(intent="OBSERVED_SYMPTOM", processingType="INVESTIGATION")
    state = reserve(state_after(empty_search(), problem), AgentRole.TICKET, "t1")

    with pytest.raises(OutputRejected, match="처리 유형"):
        merge_ticket_result(
            state, TicketResult(invocationId="t1", proposals=[ticket_for("REVIEW_REQUIRED")])
        )


def test_handoff_result_passes_final_validation() -> None:
    state = reserve(state_after(empty_search(), how_to_problem()), AgentRole.TICKET, "t1")
    merged = merge_ticket_result(
        state, TicketResult(invocationId="t1", proposals=[ticket_for("REVIEW_REQUIRED")])
    )
    merged = transition_phase(merged, "FINALIZING")

    final = finalize_result(merged)["finalResult"]

    assert final.status == "SUCCEEDED"
    assert final.ticketProposals[0].processingType == "REVIEW_REQUIRED"
    assert final.problemProposals[0].processingType == "REVIEW_REQUIRED"


# --- Supervisor 전체 경로: 같은 EMPTY 상태에서 두 길이 모두 유효 ---


def decision(action: str, role: AgentRole | None = None) -> SupervisorDecision:
    return SupervisorDecision(nextAction=action, role=role, publicSummary="진행합니다.")


def how_to_extraction(*, desired: QuotedValue | None = None) -> InquiryExtraction:
    return InquiryExtraction(
        problems=[
            ExtractedProblem(
                summary="전자결재 도장 이미지 등록 방법 문의",
                intent="HOW_TO",
                systemCode="ELECTRONIC_APPROVAL",
                taskOrScreen=QuotedValue(messageId=MESSAGE, quote="도장 이미지 등록"),
                currentWorkflow=None,
                expectedBehavior=desired,
                actualBehavior=None,
                occurredAt=None,
                requestedAccess=None,
                businessPurpose=None,
                desiredChange=None,
                unknownFields=[],
            )
        ]
    )


def no_guide_request() -> AiRunRequest:
    fields = how_to_first_message().model_dump(mode="json")
    fields["messages"][0]["content"] = NO_GUIDE_TEXT
    return AiRunRequest.model_validate(fields)


def registry():
    return build_runtime_registry(FixtureKnowledgeSource())


async def test_supervisor_may_hand_the_unanswerable_how_to_to_staff() -> None:
    request = no_guide_request()
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.TICKET),
        decision("PROPOSE_TICKETS"),
    )

    result = await Supervisor(registry()).analyze(request, services_for(request, llm))

    assert result.status == "SUCCEEDED"
    assert result.guide is None and result.questions == []
    [ticket] = result.ticketProposals
    assert ticket.processingType == "REVIEW_REQUIRED"
    assert ticket.responsibleSystemCandidateId == SYSTEM
    assert result.problemProposals[0].processingType == "REVIEW_REQUIRED"
    assert "티켓 제안" in result.answer

    # EMPTY 직후 Supervisor가 본 선택지: 어떤 역할도 막지 않고 검색 결과 없음을 알려 준다.
    after_empty = llm.requests[3]
    context = json.loads(after_empty.messages[-1].content)
    assert context["allowedRoles"] == ["INQUIRY", "KNOWLEDGE", "TICKET"]
    assert "CALL_ROLE" in context["allowedActions"]
    assert any(call.get("searchState") == "EMPTY" for call in context["completedCalls"])
    guidance = after_empty.messages[0].content
    assert "EMPTY" in guidance and "Inquiry로 질문" in guidance and "Ticket으로" in guidance
    assert after_empty.prompt_version == "supervisor.decision.v6"


async def test_supervisor_may_instead_ask_more_before_handing_over() -> None:
    request = no_guide_request()
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(),
        decision("ASK_USER"),
    )

    result = await Supervisor(registry()).analyze(request, services_for(request, llm))

    assert result.status == "SUCCEEDED"
    [question] = result.questions
    assert question.requestedFields == ["expectedBehavior"]
    assert "원하시는 결과" in result.answer
    assert result.ticketProposals == [] and result.guide is None


async def test_after_the_user_answers_the_form_is_complete_and_it_can_be_ticketed() -> None:
    request = no_guide_request()
    fields = request.model_dump(mode="json")
    fields["messages"][0]["content"] = NO_GUIDE_TEXT + " 도장 이미지로 결재를 마치고 싶어요"
    request = AiRunRequest.model_validate(fields)
    desired = QuotedValue(messageId=MESSAGE, quote="결재를 마치고 싶어요")
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(desired=desired),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(desired=desired),
        decision("CALL_ROLE", AgentRole.TICKET),
        decision("PROPOSE_TICKETS"),
    )

    result = await Supervisor(registry()).analyze(request, services_for(request, llm))

    assert result.questions == []
    [ticket] = result.ticketProposals
    assert ticket.processingType == "REVIEW_REQUIRED"
    assert "결재를 마치고 싶어요" in ticket.standardRequestSnapshot.details.expectedBehavior


async def test_search_error_ends_the_run_without_handing_over() -> None:
    class Broken:
        async def search(self, query):
            from hops_ai.harness.errors import RetrievalUnavailable

            raise RetrievalUnavailable

    request = no_guide_request()
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
    )
    agents = dict(registry())
    agents[AgentRole.KNOWLEDGE] = SearchKnowledgeAgent(Broken())

    result = await Supervisor(agents).analyze(request, services_for(request, llm))

    assert result.status == "FAILED"
    assert result.error is not None and result.error.code == "SEARCH_UNAVAILABLE"
    assert result.ticketProposals == []
