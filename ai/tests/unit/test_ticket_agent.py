"""Ticket 역할의 제안 조립.

모델 응답은 Fake이며 품질을 검증하지 않는다. 제안은 Supervisor의 병합 검증을 그대로 통과해야 한다.
"""

import pytest

from hops_ai.agents.inquiry import ExtractionInquiryAgent
from hops_ai.agents.inquiry.extraction import ExtractedProblem, InquiryExtraction, QuotedValue
from hops_ai.agents.registry import build_registry
from hops_ai.agents.ticket import ModelTicketAgent
from hops_ai.agents.ticket.proposal import TicketDecision, TicketDecisions
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiRunRequest, ProblemProposal
from hops_ai.harness.errors import OutputRejected
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.disabled import DisabledToolGateway
from hops_ai.settings import Settings
from hops_ai.supervisor.bootstrap import create_run_services
from hops_ai.supervisor.decision import SupervisorDecision
from hops_ai.supervisor.execution import RoleRequest
from hops_ai.supervisor.graph import Supervisor
from hops_ai.supervisor.merge import merge_ticket_result
from tests.doubles.agents import fake_ticket_decisions
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import (
    MESSAGE,
    SYSTEM,
    make_problem,
    make_request,
    reserve,
    running_state,
)


def new_problem_request(text: str = "전자결재 첨부파일이 열리지 않아요") -> AiRunRequest:
    """문제가 아직 없는 첫 문의 입력."""
    fields = make_request().model_dump(mode="json")
    fields["problemIds"] = []
    fields["problems"] = []
    fields["messages"][0].update(content=text, problemIds=[])
    return AiRunRequest.model_validate(fields)


def services_for(request: AiRunRequest, llm=None) -> SharedServices:
    return create_run_services(
        request,
        settings=Settings(_env_file=None),
        access_scope_ref=f"test-scope:{request.runId}",
        llm=llm or FakeLLMClient(),
        tools=DisabledToolGateway(),
    )


def state_with(*problems: ProblemProposal, request: AiRunRequest | None = None):
    request = request or new_problem_request()
    state = running_state(request)
    return {**state, "problems": {problem.problemRef: problem for problem in problems}}


def problem(ref: str = "new:1", **changes) -> ProblemProposal:
    return make_problem(ref).model_copy(update=changes)


async def propose(state) -> object:
    reserved = reserve(state, AgentRole.TICKET, "inv-t")
    request = state["input"]
    decisions = fake_ticket_decisions(state)
    llm = FakeLLMClient() if decisions is None else FakeLLMClient(decisions)
    result = await ModelTicketAgent().run(
        RoleRequest("inv-t", reserved), services_for(request, llm)
    )
    return reserved, result


async def test_confirmed_facts_unknowns_and_details_are_carried_over_unchanged() -> None:
    source = problem()
    reserved, result = await propose(state_with(source))

    [proposal] = result.proposals
    assert result.invocationId == "inv-t"
    assert proposal.problemRef == "new:1"
    assert proposal.processingType == source.processingType == "INVESTIGATION"
    assert proposal.responsibleSystemCandidateId == SYSTEM
    assert proposal.title == source.summary
    assert proposal.evidenceIds == []
    snapshot = proposal.standardRequestSnapshot
    assert snapshot.confirmedFacts == source.facts
    assert snapshot.assumptions == source.assumptions == []
    assert snapshot.unknowns == source.unknowns
    assert snapshot.details == source.details
    assert snapshot.responsibleSystemCandidateId == SYSTEM
    assert snapshot.responsibilityEvidenceIds == [] and snapshot.knowledge == []
    # Supervisor의 병합 검증을 그대로 통과한다
    merged = merge_ticket_result(reserved, result)
    assert "new:1" in merged["ticketProposals"]


@pytest.mark.parametrize(
    "processing_type", ["SERVICE_REQUEST", "INVESTIGATION", "CHANGE_REQUEST", "REVIEW_REQUIRED"]
)
async def test_every_ticketable_processing_type_is_proposed_and_accepted(processing_type) -> None:
    reserved, result = await propose(state_with(problem(processingType=processing_type)))

    assert result.proposals[0].processingType == processing_type
    merge_ticket_result(reserved, result)


@pytest.mark.parametrize("processing_type", ["KNOWLEDGE_QA", "OUT_OF_SCOPE"])
async def test_how_to_and_out_of_scope_problems_are_not_made_into_tickets(processing_type) -> None:
    with pytest.raises(OutputRejected):
        await propose(state_with(problem(processingType=processing_type)))


async def test_a_problem_without_a_confirmed_system_gets_no_ticket() -> None:
    with pytest.raises(OutputRejected):
        await propose(state_with(problem(reportedSystemCandidateId=None)))


async def test_only_problems_without_a_question_are_proposed() -> None:
    from hops_ai.contracts.wire import QuestionProposal

    asked = problem("new:1")
    ready = problem("new:2", summary="로그인 권한 신청", processingType="SERVICE_REQUEST")
    state = state_with(asked, ready)
    question = QuestionProposal(
        problemRef="new:1",
        bundleRef="q-1",
        text="언제부터 그랬나요?",
        requestedFields=["occurredAt"],
    )
    state = {**state, "questions": {("new:1", "q-1"): question}}

    reserved, result = await propose(state)

    assert [proposal.problemRef for proposal in result.proposals] == ["new:2"]
    merge_ticket_result(reserved, result)


async def test_problems_that_already_have_a_ticket_proposal_are_not_proposed_again() -> None:
    state = state_with(problem())
    _, first = await propose(state)
    state = {**state, "ticketProposals": {"new:1": first.proposals[0]}}

    with pytest.raises(OutputRejected):
        await propose(state)


async def test_supervisor_ends_a_complete_inquiry_with_a_ticket_proposal() -> None:
    request = new_problem_request(
        "전자결재 문서 조회가 안 돼요. 오늘 오전부터 0건이에요. 목록이 보여야 해요"
    )

    def quote(text: str) -> QuotedValue:
        return QuotedValue(messageId=MESSAGE, quote=text)

    complete = ExtractedProblem(
        summary="문서 조회 결과 0건",
        intent="OBSERVED_SYMPTOM",
        systemCode="ELECTRONIC_APPROVAL",
        taskOrScreen=quote("문서 조회"),
        currentWorkflow=None,
        expectedBehavior=quote("목록이 보여야 해요"),
        actualBehavior=quote("0건이에요"),
        occurredAt=quote("오늘 오전부터"),
        requestedAccess=None,
        businessPurpose=None,
        desiredChange=None,
        unknownFields=[],
        keywords=[],
        featureCandidates=[],
    )
    llm = FakeLLMClient(
        SupervisorDecision(
            nextAction="CALL_ROLE", role=AgentRole.INQUIRY, publicSummary="확인합니다."
        ),
        InquiryExtraction(problems=[complete]),
        SupervisorDecision(
            nextAction="CALL_ROLE", role=AgentRole.TICKET, publicSummary="정리합니다."
        ),
        TicketDecisions(
            decisions=[
                TicketDecision(
                    problemRef="new:1",
                    title="문서 조회 결과 0건 조사",
                    responsibleSystemCandidateId=SYSTEM,
                    supports=[],
                    knowledgeEvidenceIds=[],
                )
            ]
        ),
        SupervisorDecision(nextAction="PROPOSE_TICKETS", role=None, publicSummary="제안합니다."),
    )
    registry = build_registry()
    agents = {role: registry[role] for role in (AgentRole.INQUIRY, AgentRole.TICKET)}
    assert isinstance(agents[AgentRole.INQUIRY], ExtractionInquiryAgent)

    result = await Supervisor(agents).analyze(request, services_for(request, llm))

    assert result.status == "SUCCEEDED"
    assert result.questions == []
    [ticket] = result.ticketProposals
    assert ticket.processingType == "INVESTIGATION"
    assert ticket.responsibleSystemCandidateId == SYSTEM
    assert [fact.sourceMessageIds for fact in ticket.standardRequestSnapshot.confirmedFacts] == [
        [MESSAGE]
    ] * 4
    # 제안일 뿐이며 저장·배정 성공을 주장하지 않는다
    assert "접수 및 담당자 배정 결과는 별도 확인이 필요합니다" in result.answer
    assert len(llm.requests) == 5
