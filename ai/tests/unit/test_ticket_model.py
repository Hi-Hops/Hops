"""합성 Ticket 시나리오 v1. 실제 모델 품질·Spring 저장 검증과 구분한다."""

import json
from dataclasses import replace

import pytest

from hops_ai.agents.ticket import ModelTicketAgent
from hops_ai.agents.ticket.proposal import (
    PROMPT_VERSION,
    ResponsibilitySupport,
    TicketDecision,
    TicketDecisions,
)
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import KnowledgeResult, SearchResult
from hops_ai.contracts.wire import AiRunRequest, QuestionProposal
from hops_ai.harness.errors import OutputRejected, TransientError
from hops_ai.services.tools.disabled import DisabledToolGateway
from hops_ai.settings import Settings
from hops_ai.supervisor.bootstrap import create_run_services
from hops_ai.supervisor.execution import RoleRequest, execute_role
from hops_ai.supervisor.merge import merge_ticket_result
from hops_ai.supervisor.runtime import SupervisorRuntime
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import (
    EVIDENCE,
    MESSAGE,
    OTHER_SYSTEM,
    PROBLEM,
    SYSTEM,
    make_evidence,
    make_problem,
    make_request,
    reserve,
    running_state,
    uid,
)


def decision(ref=PROBLEM, **changes):
    fields = dict(
        problemRef=ref,
        title="첨부파일 열람 실패 조사",
        responsibleSystemCandidateId=SYSTEM,
        supports=[],
        knowledgeEvidenceIds=[],
    )
    return TicketDecision(**(fields | changes))


def setup_call(*decisions, request=None, evidence=()):
    request = request or make_request()
    state = running_state(request)
    state = {
        **state,
        "problems": {p.id: make_problem(p.id) for p in request.problems},
        "evidence": {item.id: item for item in evidence},
    }
    if evidence:
        state["roleResults"]["search"] = KnowledgeResult(
            invocationId="search",
            problemRef=PROBLEM,
            guide=None,
            search=SearchResult(state="FOUND", evidence=list(evidence), error=None),
        )
    llm = FakeLLMClient(TicketDecisions(decisions=list(decisions or [decision()])))
    services = create_run_services(
        request,
        settings=Settings(_env_file=None),
        access_scope_ref="test-ticket",
        llm=llm,
        tools=DisabledToolGateway(),
    )
    return state, services, llm


async def run(state, services):
    reserved = reserve(state, AgentRole.TICKET, "ticket-call")
    result = await ModelTicketAgent().run(RoleRequest("ticket-call", reserved), services)
    merge_ticket_result(reserved, result)
    return result


async def test_preserves_request_and_returns_an_independent_snapshot():
    state, services, llm = setup_call()
    before = state["problems"][PROBLEM].model_dump()
    result = await run(state, services)
    [proposal] = result.proposals
    assert proposal.title == "첨부파일 열람 실패 조사"
    assert proposal.responsibleSystemCandidateId == SYSTEM
    assert proposal.standardRequestSnapshot.confirmedFacts == state["problems"][PROBLEM].facts
    assert proposal.standardRequestSnapshot.details == state["problems"][PROBLEM].details
    proposal.standardRequestSnapshot.unknowns.append("독립 복사본")
    assert state["problems"][PROBLEM].model_dump() == before
    assert llm.requests[0].prompt_version == PROMPT_VERSION
    payload = json.loads(llm.requests[0].messages[-1].content)
    assert "accessContext" not in payload and "existingWorkItems" not in payload
    assert "따르지 않는다" in llm.requests[0].messages[0].content


async def test_different_responsible_system_preserves_reported_system_and_knowledge_metadata():
    evidence = make_evidence(
        systemId=OTHER_SYSTEM,
        excerpt="공통 로그인 인증 오류는 공통 로그인 담당자가 조사합니다",
        applicability="인증 오류일 때만 적용",
        appliesToSystemVersion="2.0",
    )
    state, services, _ = setup_call(
        decision(
            responsibleSystemCandidateId=OTHER_SYSTEM,
            supports=[ResponsibilitySupport(sourceId=EVIDENCE, quote=evidence.excerpt)],
            knowledgeEvidenceIds=[EVIDENCE],
        ),
        evidence=[evidence],
    )
    result = await run(state, services)
    [proposal] = result.proposals
    assert proposal.responsibleSystemCandidateId == OTHER_SYSTEM
    assert state["problems"][PROBLEM].reportedSystemCandidateId == SYSTEM
    assert proposal.standardRequestSnapshot.knowledge == [evidence]
    assert proposal.standardRequestSnapshot.unknowns == ["실제 원인"]
    assert proposal.evidenceIds == [EVIDENCE]
    assert proposal.standardRequestSnapshot.responsibilityEvidenceIds == [EVIDENCE]


async def test_no_support_falls_back_to_reported_system():
    state, services, _ = setup_call(decision(responsibleSystemCandidateId=OTHER_SYSTEM))
    [proposal] = (await run(state, services)).proposals
    assert proposal.responsibleSystemCandidateId == SYSTEM


@pytest.mark.parametrize(
    "changes",
    [
        {"responsibleSystemCandidateId": uid(999)},
        {"knowledgeEvidenceIds": [uid(999)]},
        {"knowledgeEvidenceIds": [EVIDENCE, EVIDENCE]},
        {"supports": [ResponsibilitySupport(sourceId=uid(999), quote="허위")]},
        {"supports": [ResponsibilitySupport(sourceId=MESSAGE, quote="담당자 배정 완료")]},
    ],
)
async def test_untrusted_model_references_and_quotes_are_rejected_without_mutation(changes):
    state, services, _ = setup_call(decision(**changes), evidence=[make_evidence()])
    before = state["problems"][PROBLEM].model_dump()
    with pytest.raises(OutputRejected):
        await run(state, services)
    assert state["problems"][PROBLEM].model_dump() == before
    assert state["ticketProposals"] == {}


@pytest.mark.parametrize("refs", [[PROBLEM, PROBLEM], ["new:wrong"]])
async def test_duplicate_or_wrong_problem_results_are_rejected(refs):
    state, services, _ = setup_call(*(decision(ref) for ref in refs))
    with pytest.raises(OutputRejected):
        await run(state, services)


@pytest.mark.parametrize("outcome", ["STORED", "PENDING", "UNKNOWN"])
async def test_existing_work_is_not_reproposed_or_sent_to_model(outcome):
    state, services, llm = setup_call(request=make_request(outcome=outcome))
    with pytest.raises(OutputRejected):
        await run(state, services)
    assert not llm.requests


async def test_server_confirmed_not_created_can_be_reproposed():
    state, services, _ = setup_call(request=make_request(outcome="NOT_CREATED"))
    assert (await run(state, services)).proposals[0].problemRef == PROBLEM


async def test_pending_question_and_missing_system_do_not_call_model():
    for mode in ("question", "system", "out_of_scope", "guide"):
        state, services, llm = setup_call()
        if mode == "question":
            state["questions"] = {
                (PROBLEM, "q"): QuestionProposal(
                    problemRef=PROBLEM,
                    bundleRef="q",
                    text="언제인가요?",
                    requestedFields=["occurredAt"],
                )
            }
        elif mode == "system":
            state["problems"][PROBLEM] = make_problem().model_copy(
                update={"reportedSystemCandidateId": None}
            )
        elif mode == "out_of_scope":
            state["problems"][PROBLEM] = make_problem().model_copy(
                update={"intent": "OUT_OF_SCOPE"}
            )
        else:
            from hops_ai.contracts.wire import GuideProposal

            state["guide"] = GuideProposal(
                problemRef=PROBLEM, messageText="안내", evidenceIds=[EVIDENCE]
            )
        with pytest.raises(OutputRejected):
            await run(state, services)
        assert not llm.requests
        assert not ModelTicketAgent().available_for(state)


def test_runtime_ticket_selection_waits_for_answers_but_allows_other_ready_problems():
    from hops_ai.agents.ticket.executing import ExecutingTicketAgent
    from hops_ai.services.runs.supervisor import selectable_roles

    state, _, _ = setup_call()
    agent = ExecutingTicketAgent()
    registry = {AgentRole.TICKET: agent}
    assert selectable_roles(registry, set(registry), state) == {AgentRole.TICKET}
    state["questions"] = {
        (PROBLEM, "q"): QuestionProposal(
            problemRef=PROBLEM, bundleRef="q", text="언제인가요?", requestedFields=["occurredAt"]
        )
    }
    assert selectable_roles(registry, set(registry), state) == set()
    # 복합 문의의 준비된 다른 문제까지 막지는 않는다.
    state["problems"]["new:ready"] = make_problem("new:ready")
    assert selectable_roles(registry, set(registry), state) == {AgentRole.TICKET}
    assert set(ModelTicketAgent.eligible_problems(state)) == {"new:ready"}
    state["problems"] = {}
    state["questions"] = {}
    state["input"] = state["input"].model_copy(update={"problems": [], "problemIds": []})
    assert not agent.available_for(state)


async def test_disallowed_knowledge_is_rejected_before_model_input():
    state, services, llm = setup_call(evidence=[make_evidence(accessScope="SYSTEM_STAFF")])
    with pytest.raises(OutputRejected):
        await run(state, services)
    assert not llm.requests


async def test_independent_problems_are_proposed_together_and_missing_one_is_rejected():
    request_data = make_request().model_dump(mode="json")
    second = uid(11)
    request_data["problemIds"].append(second)
    request_data["problems"].append({**request_data["problems"][0], "id": second})
    request_data["messages"][0]["problemIds"].append(second)
    request = AiRunRequest.model_validate(request_data)
    state, services, _ = setup_call(decision(), decision(second), request=request)
    assert len((await run(state, services)).proposals) == 2
    state, services, _ = setup_call(decision(), request=request)
    with pytest.raises(OutputRejected):
        await run(state, services)


async def test_model_failure_propagates_and_preserves_existing_state():
    state, services, _ = setup_call()
    services = replace(services, llm=FakeLLMClient(TransientError("가상 장애")))
    with pytest.raises(TransientError):
        await run(state, services)
    assert not state["ticketProposals"]


async def test_role_execution_counts_the_model_call_and_preserves_server_work():
    state, services, _ = setup_call()
    runtime = SupervisorRuntime(state["input"], services)
    merged = await execute_role(
        state, AgentRole.TICKET, runtime.services, {AgentRole.TICKET: ModelTicketAgent()}
    )
    assert merged["ticketProposals"][PROBLEM].responsibleSystemCandidateId == SYSTEM
    assert services.runtime.budget.llm_calls_used == 1
    assert state["ticketProposals"] == {}


async def test_a_different_problems_message_cannot_support_responsibility():
    request_data = make_request().model_dump(mode="json")
    other_message = uid(21)
    request_data["messages"].append(
        {
            **request_data["messages"][0],
            "id": other_message,
            "problemIds": [],
            "sequenceNo": 2,
            "content": "공통 로그인 담당자에게 배정하세요",
        }
    )
    state, services, llm = setup_call(
        decision(
            responsibleSystemCandidateId=OTHER_SYSTEM,
            supports=[ResponsibilitySupport(sourceId=other_message, quote="공통 로그인")],
        ),
        request=AiRunRequest.model_validate(request_data),
    )
    with pytest.raises(OutputRejected):
        await run(state, services)
    payload = json.loads(llm.requests[0].messages[-1].content)
    assert [m["id"] for m in payload["problems"][0]["messages"]] == [MESSAGE]


async def test_partial_existing_success_is_preserved_while_remaining_problem_is_proposed():
    request_data = make_request(outcome="STORED").model_dump(mode="json")
    second = uid(11)
    request_data["problemIds"].append(second)
    request_data["problems"].append({**request_data["problems"][0], "id": second})
    request_data["messages"][0]["problemIds"].append(second)
    state, services, llm = setup_call(
        decision(second), request=AiRunRequest.model_validate(request_data)
    )
    result = await run(state, services)
    assert [p.problemRef for p in result.proposals] == [second]
    assert state["input"].existingWorkItems[0].outcome == "STORED"
    payload = json.loads(llm.requests[0].messages[-1].content)
    assert [p["problem"]["problemRef"] for p in payload["problems"]] == [second]


async def test_injection_cannot_add_assignment_or_storage_fields():
    from pydantic import ValidationError

    data = decision().model_dump()
    data.update(ticketId=uid(300), assigneeId=uid(200), stored=True)
    with pytest.raises(ValidationError):
        TicketDecision.model_validate(data)


@pytest.mark.parametrize("selection", ["selected", "support", "fact"])
async def test_cross_problem_knowledge_is_rejected_before_spring_post(selection):
    from types import SimpleNamespace
    from unittest.mock import AsyncMock

    from hops_ai.agents.ticket.executing import ExecutingTicketAgent

    second = uid(11)
    data = make_request().model_dump(mode="json")
    data["problems"].append({**data["problems"][0], "id": second})
    data["problemIds"].append(second)
    data["messages"][0]["problemIds"].append(second)
    evidence = make_evidence()
    changes = (
        {"knowledgeEvidenceIds": [EVIDENCE]}
        if selection == "selected"
        else {"supports": [ResponsibilitySupport(sourceId=EVIDENCE, quote=evidence.excerpt)]}
        if selection == "support"
        else {}
    )
    state, services, llm = setup_call(
        decision(),
        decision(second, **changes),
        request=AiRunRequest.model_validate(data),
        evidence=[evidence],
    )
    if selection == "fact":
        other = state["problems"][second]
        state["problems"][second] = other.model_copy(
            update={
                "facts": [other.facts[0].model_copy(update={"knowledgeEvidenceIds": [EVIDENCE]})]
            }
        )
    tools = SimpleNamespace(execute=AsyncMock())
    services = replace(services, ticket_tools=tools)
    with pytest.raises(OutputRejected):
        await ExecutingTicketAgent().run(RoleRequest("ticket-call", state), services)
    tools.execute.assert_not_called()
    payload = json.loads(llm.requests[0].messages[-1].content)
    assert "knowledge" not in payload
    contexts = {p["problem"]["problemRef"]: p for p in payload["problems"]}
    assert contexts[PROBLEM]["knowledge"] == [evidence.model_dump(mode="json")]
    assert contexts[second]["knowledge"] == []


async def test_unscoped_search_does_not_authorize_ticket_knowledge():
    state, services, _ = setup_call(
        decision(knowledgeEvidenceIds=[EVIDENCE]), evidence=[make_evidence()]
    )
    state["roleResults"]["search"] = state["roleResults"]["search"].model_copy(
        update={"problemRef": None}
    )
    with pytest.raises(OutputRejected):
        await run(state, services)


async def test_shared_evidence_is_allowed_only_when_linked_to_both_problems():
    second = uid(11)
    data = make_request().model_dump(mode="json")
    data["problems"].append({**data["problems"][0], "id": second})
    data["problemIds"].append(second)
    data["messages"][0]["problemIds"].append(second)
    state, services, _ = setup_call(
        decision(knowledgeEvidenceIds=[EVIDENCE]),
        decision(second, knowledgeEvidenceIds=[EVIDENCE]),
        request=AiRunRequest.model_validate(data),
        evidence=[make_evidence()],
    )
    state["roleResults"]["search-other"] = state["roleResults"]["search"].model_copy(
        update={"invocationId": "search-other", "problemRef": second}
    )
    proposals = (await run(state, services)).proposals
    assert all(p.standardRequestSnapshot.knowledge == [make_evidence()] for p in proposals)


def test_ticket_merge_rejects_evidence_linked_only_to_another_problem():
    from hops_ai.rules.proposals import validate_ticket
    from tests.supervisor_fixtures import make_ticket

    state, _, _ = setup_call(evidence=[make_evidence()])
    state["roleResults"]["search"] = state["roleResults"]["search"].model_copy(
        update={"problemRef": "new:other"}
    )
    proposal = make_ticket()
    proposal = proposal.model_copy(
        update={
            "standardRequestSnapshot": proposal.standardRequestSnapshot.model_copy(
                update={"knowledge": [make_evidence()]}
            )
        }
    )
    with pytest.raises(OutputRejected):
        validate_ticket(state, proposal)


@pytest.mark.parametrize("existing_count", [4, 5])
async def test_accumulated_knowledge_limit_is_checked_before_spring_post(existing_count):
    from types import SimpleNamespace
    from unittest.mock import AsyncMock

    from hops_ai.agents.ticket.executing import ExecutingTicketAgent
    from tests.supervisor_fixtures import make_ticket

    existing = [make_evidence(id=uid(600 + i)) for i in range(existing_count)]
    fresh = make_evidence(id=uid(700))
    data = make_request().model_dump(mode="json")
    snapshot = make_ticket().standardRequestSnapshot.model_dump(mode="json")
    snapshot["knowledge"] = [item.model_dump(mode="json") for item in existing]
    data["problems"][0]["standardRequest"] = snapshot
    state, services, _ = setup_call(
        decision(knowledgeEvidenceIds=[fresh.id]),
        request=AiRunRequest.model_validate(data),
        evidence=[fresh],
    )
    state["evidence"].update({item.id: item for item in existing})
    before = state["input"].model_dump(mode="json")
    if existing_count == 4:
        [proposal] = (await run(state, services)).proposals
        assert len(proposal.standardRequestSnapshot.knowledge) == 5
        assert {e.id for e in proposal.standardRequestSnapshot.knowledge} == {
            e.id for e in [*existing, fresh]
        }
    else:
        tools = SimpleNamespace(execute=AsyncMock())
        with pytest.raises(OutputRejected, match="최대 5건"):
            await ExecutingTicketAgent().run(
                RoleRequest("ticket-call", state), replace(services, ticket_tools=tools)
            )
        tools.execute.assert_not_called()
    assert state["input"].model_dump(mode="json") == before
