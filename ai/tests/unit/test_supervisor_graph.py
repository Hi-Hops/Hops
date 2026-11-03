"""명시적인 모델·역할 대역으로 Supervisor 노드 연결과 실패 경계를 검사한다."""

import asyncio

import pytest

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import (
    CuratorResult,
    InquiryResult,
    KnowledgeResult,
    SearchResult,
    TicketResult,
)
from hops_ai.contracts.wire import GuideProposal, QuestionProposal
from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import ComponentNotReady, InputRejected
from hops_ai.services.llm.client import LLMMessage, LLMRequest, LLMResponse, LLMUsage
from hops_ai.services.llm.openai import OpenAIClient
from hops_ai.services.retrieval.neo4j import Neo4jRetriever
from hops_ai.services.retrieval.pgvector import PgVectorRetriever
from hops_ai.services.shared import SharedServices
from hops_ai.settings import Settings
from hops_ai.supervisor import graph as graph_module
from hops_ai.supervisor.decision import SupervisorDecision
from hops_ai.supervisor.execution import RoleRequest, RoleResponse
from hops_ai.supervisor.graph import Supervisor
from tests.supervisor_fixtures import (
    PROBLEM,
    SYSTEM,
    authorize,
    make_case,
    make_evidence,
    make_problem,
    make_request,
    make_ticket,
)


class ScriptedLLM:
    """테스트가 명시한 응답만 순서대로 반환한다. 실제 모델을 호출하지 않는다."""

    def __init__(self, *responses) -> None:
        self.responses = list(responses)
        self.requests = []

    async def generate(self, request):
        self.requests.append(request)
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        if callable(response):
            response = response(request)
        return LLMResponse(response, LLMUsage(10, 5), "test-only")


class UnusedTools:
    async def call(self, role, request):
        raise AssertionError("이 시나리오는 도구를 호출하지 않는다")


def services_for(request, model) -> SharedServices:
    """각 검사에 독립적인 실행 문맥과 명시적 대역을 준비한다."""
    runtime = RunContext.create(
        run_id=request.runId,
        inquiry_id=request.inquiryId,
        inquiry_version=request.inquiryVersion,
        access_scope_ref="test-authorized-scope",
        settings=Settings(_env_file=None),
    )
    return SharedServices(runtime, model, PgVectorRetriever(), Neo4jRetriever(), UnusedTools())


def decision(action="FINISH", role=None, summary="요청 내용을 정리했습니다."):
    return SupervisorDecision(nextAction=action, role=role, publicSummary=summary)


class InquiryStub:
    role = AgentRole.INQUIRY

    def __init__(self, *, ask=False, model=False) -> None:
        self.ask = ask
        self.model = model
        self.calls = 0

    async def run(self, request: RoleRequest, services: SharedServices):
        self.calls += 1
        if self.model:
            response = await services.llm.generate(
                LLMRequest(
                    [LLMMessage("system", request.invocation_id)],
                    InquiryResult,
                    100,
                    "test.inquiry",
                )
            )
            return response.parsed
        questions = (
            [
                QuestionProposal(
                    problemRef=PROBLEM,
                    bundleRef="bundle-1",
                    text="어떤 오류 문구가 나타나나요?",
                    requestedFields=["actualBehavior"],
                )
            ]
            if self.ask
            else []
        )
        return InquiryResult(
            invocationId=request.invocation_id, problems=[make_problem()], questions=questions
        )


class TicketStub:
    role = AgentRole.TICKET

    async def run(self, request, services):
        return TicketResult(invocationId=request.invocation_id, proposals=[make_ticket()])


class CuratorStub:
    role = AgentRole.CURATOR

    def __init__(self, *, reusable=False) -> None:
        self.reusable = reusable

    async def run(self, request, services):
        result = CuratorResult(
            invocationId=request.invocation_id, proposal=make_case(reusable=self.reusable)
        )
        if self.reusable:
            return RoleResponse(result, allowed_entity_ids={"APPLIES_TO": {SYSTEM}})
        return result


async def test_question_finishes_this_run_and_appears_once_in_answer() -> None:
    request = make_request(question_count=4)
    model = ScriptedLLM(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("ASK_USER", summary="추가 확인이 필요합니다."),
    )
    inquiry = InquiryStub(ask=True)
    result = await Supervisor({AgentRole.INQUIRY: inquiry}).analyze(
        request, services_for(request, model)
    )
    assert result.status == "SUCCEEDED"
    assert len(result.questions) == 1
    assert result.answer.count(result.questions[0].text) == 1
    assert inquiry.calls == 1
    assert len(model.requests) == 2
    assert request.problems[0].questionCount == 4


async def test_supervisor_and_specialist_use_the_same_model_budget() -> None:
    request = make_request()

    def inquiry_response(llm_request):
        return InquiryResult(
            invocationId=llm_request.messages[0].content,
            problems=[make_problem()],
            questions=[
                QuestionProposal(
                    problemRef=PROBLEM,
                    bundleRef="budget-question",
                    text="어떤 오류 문구가 나타나나요?",
                    requestedFields=["actualBehavior"],
                )
            ],
        )

    model = ScriptedLLM(decision("CALL_ROLE", AgentRole.INQUIRY), inquiry_response, decision())
    services = services_for(request, model)
    result = await Supervisor({AgentRole.INQUIRY: InquiryStub(model=True)}).analyze(
        request, services
    )
    assert result.status == "SUCCEEDED"
    assert services.runtime.budget.llm_calls_used == 3
    assert [item.response_model for item in model.requests] == [
        SupervisorDecision,
        InquiryResult,
        SupervisorDecision,
    ]


async def test_inquiry_ticket_and_final_decision_are_connected() -> None:
    request = make_request()
    model = ScriptedLLM(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("CALL_ROLE", AgentRole.TICKET),
        decision("PROPOSE_TICKETS", summary="담당자 확인을 위한 요청을 정리했습니다."),
    )
    result = await Supervisor(
        {AgentRole.INQUIRY: InquiryStub(), AgentRole.TICKET: TicketStub()}
    ).analyze(request, services_for(request, model))
    assert result.status == "SUCCEEDED"
    assert len(result.ticketProposals) == 1
    assert result.caseProposal is None


@pytest.mark.parametrize("reusable", [False, True])
async def test_case_draft_finishes_without_chat_or_consultation_proposals(reusable) -> None:
    request = make_request(kind=RunKind.CASE_DRAFT)
    model = ScriptedLLM(decision("CALL_ROLE", AgentRole.CURATOR), decision())
    result = await Supervisor({AgentRole.CURATOR: CuratorStub(reusable=reusable)}).analyze(
        request, services_for(request, model)
    )
    assert result.status == "SUCCEEDED"
    assert result.kind == "CASE_DRAFT"
    assert result.caseProposal.reusable is reusable
    if reusable:
        assert result.caseProposal.entityLinks[0].targetId == SYSTEM
    assert result.answer is None
    assert result.problemProposals == result.questions == result.ticketProposals == []


async def test_authorized_guide_is_merged_and_included_once_in_answer() -> None:
    class KnowledgeStub:
        role = AgentRole.KNOWLEDGE

        async def run(self, request, services):
            evidence = make_evidence()
            return RoleResponse(
                result=KnowledgeResult(
                    invocationId=request.invocation_id,
                    search=SearchResult(state="FOUND", evidence=[evidence], error=None),
                    guide=GuideProposal(
                        problemRef=PROBLEM,
                        messageText="파일 열람 권한을 확인해 주세요.",
                        evidenceIds=[evidence.id],
                    ),
                ),
                authorized_refs=(authorize(evidence),),
                verified_evidence=(evidence,),
            )

    request = make_request()
    model = ScriptedLLM(decision("CALL_ROLE", AgentRole.KNOWLEDGE), decision("PROVIDE_GUIDE"))
    result = await Supervisor({AgentRole.KNOWLEDGE: KnowledgeStub()}).analyze(
        request, services_for(request, model)
    )
    assert result.status == "SUCCEEDED"
    assert result.answer.count(result.guide.messageText) == 1
    assert len(result.evidence) == 1
    assert result.ticketProposals == []


async def test_invalid_later_decision_discards_earlier_proposals() -> None:
    request = make_request()
    model = ScriptedLLM(
        decision("CALL_ROLE", AgentRole.INQUIRY), decision("CALL_ROLE", AgentRole.CURATOR)
    )
    result = await Supervisor({AgentRole.INQUIRY: InquiryStub(ask=True)}).analyze(
        request, services_for(request, model)
    )
    assert result.status == "FAILED"
    assert result.error.code == "INVALID_OUTPUT"
    assert result.answer is None
    assert result.problemProposals == result.questions == result.evidence == []


async def test_repeated_role_calls_stop_at_cumulative_model_limit() -> None:
    original = make_request()
    request = original.model_copy(
        update={"limits": original.limits.model_copy(update={"maxModelCalls": 2})}
    )
    model = ScriptedLLM(
        decision("CALL_ROLE", AgentRole.INQUIRY), decision("CALL_ROLE", AgentRole.INQUIRY)
    )
    result = await Supervisor({AgentRole.INQUIRY: InquiryStub()}).analyze(
        request, services_for(request, model)
    )
    assert result.status == "FAILED"
    assert result.error.code == "BUDGET_EXCEEDED"
    assert len(model.requests) == 2
    assert result.problemProposals == []


async def test_cancel_during_role_drops_the_late_result() -> None:
    started = asyncio.Event()
    stopped = asyncio.Event()

    class WaitingInquiry:
        role = AgentRole.INQUIRY

        async def run(self, request, services):
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                stopped.set()

    request = make_request()
    services = services_for(request, ScriptedLLM(decision("CALL_ROLE", AgentRole.INQUIRY)))
    task = asyncio.create_task(
        Supervisor({AgentRole.INQUIRY: WaitingInquiry()}).analyze(request, services)
    )
    await asyncio.wait_for(started.wait(), timeout=2)
    services.runtime.cancel()
    result = await asyncio.wait_for(task, timeout=2)
    assert stopped.is_set()
    assert result.status == "SUPERSEDED"
    assert result.error.code == "SUPERSEDED"
    assert result.problemProposals == []


async def test_unimplemented_model_is_safe_failure_without_fake_fallback() -> None:
    request = make_request()
    result = await Supervisor().analyze(request, services_for(request, OpenAIClient()))
    assert result.status == "FAILED"
    assert result.error.code == "SERVICE_UNAVAILABLE"
    assert result.answer is None


async def test_expired_server_budget_stops_before_model_call() -> None:
    request = make_request()
    model = ScriptedLLM(decision())
    services = services_for(request, model)
    services.runtime.budget.deadline = services.runtime.budget.clock.monotonic() - 1
    result = await Supervisor().analyze(request, services)
    assert result.status == "TIMED_OUT"
    assert result.error.code == "DEADLINE_EXCEEDED"
    assert model.requests == []


async def test_server_deadline_during_final_validation_discards_success(monkeypatch) -> None:
    request = make_request(outcome="STORED")
    services = services_for(request, ScriptedLLM(decision()))
    original_finalize = graph_module.finalize_result

    def finish_after_deadline(state):
        candidate = original_finalize(state)
        if candidate["phase"] == "SUCCEEDED":
            # 최종 본문 검증 중 서버 마감이 지난 상황을 재현한다.
            services.runtime.budget.deadline = services.runtime.budget.clock.monotonic() - 1
        return candidate

    monkeypatch.setattr(graph_module, "finalize_result", finish_after_deadline)
    result = await Supervisor().analyze(request, services)
    assert result.status == "TIMED_OUT"
    assert result.answer is None
    assert result.problemProposals == []


async def test_external_task_cancellation_propagates_to_the_caller() -> None:
    started = asyncio.Event()
    stopped = asyncio.Event()

    class WaitingModel:
        async def generate(self, request):
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                stopped.set()

    request = make_request()
    task = asyncio.create_task(Supervisor().analyze(request, services_for(request, WaitingModel())))
    await asyncio.wait_for(started.wait(), timeout=2)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert stopped.is_set()


async def test_role_failure_does_not_expose_exception_detail() -> None:
    request = make_request()
    model = ScriptedLLM(ComponentNotReady("synthetic-private-detail"))
    result = await Supervisor().analyze(request, services_for(request, model))
    assert result.status == "FAILED"
    assert "synthetic-private-detail" not in result.model_dump_json()


async def test_context_cannot_be_reused_for_another_execution() -> None:
    request = make_request(outcome="STORED")
    services = services_for(request, ScriptedLLM(decision()))
    supervisor = Supervisor()
    assert (await supervisor.analyze(request, services)).status == "SUCCEEDED"
    with pytest.raises(InputRejected):
        await supervisor.analyze(request, services)
