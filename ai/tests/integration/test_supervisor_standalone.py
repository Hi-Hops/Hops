"""주입한 모델·역할과 요청별 문맥의 조립 검사. 실제 외부 서비스는 호출하지 않는다."""

import asyncio
import json
from uuid import UUID

import pytest

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import InquiryResult
from hops_ai.contracts.wire import AiRunRequest, ProblemProposal, QuestionProposal
from hops_ai.harness.policy import ToolPolicy
from hops_ai.services.llm.client import LLMMessage, LLMRequest, LLMResponse, LLMUsage
from hops_ai.services.retrieval.neo4j import Neo4jRetriever
from hops_ai.services.retrieval.pgvector import PgVectorRetriever
from hops_ai.services.runs.admission import RunAdmissionService
from hops_ai.services.runs.executor import RunExecutor, server_ceilings
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.guarded import GuardedToolGateway
from hops_ai.services.tools.registry import ToolRegistry
from hops_ai.settings import Settings
from hops_ai.supervisor.bootstrap import create_run_services
from hops_ai.supervisor.decision import SupervisorDecision
from hops_ai.supervisor.graph import Supervisor
from tests.doubles.ledger import MemoryLedger
from tests.supervisor_fixtures import make_problem, make_request, uid


def inquiry_result(data: dict) -> InquiryResult:
    """전달된 호출 ID·원문 ID를 유지하는 합성 전문 결과를 만든다."""
    problem = make_problem(data["problemRef"]).model_dump(mode="json")
    problem["facts"][0]["sourceMessageIds"] = [data["messageId"]]
    return InquiryResult(
        invocationId=data["invocationId"],
        problems=[ProblemProposal.model_validate(problem)],
        questions=[
            QuestionProposal(
                problemRef=data["problemRef"],
                bundleRef="test-question",
                text=f"{data['label']}의 오류 문구를 알려 주세요.",
                requestedFields=["actualBehavior"],
            )
        ],
    )


class ContextualLLM:
    """호출 문맥으로 명시적 테스트 응답을 만들어 동시 실행에서도 순서에 의존하지 않는다."""

    def __init__(self) -> None:
        self.calls = []

    async def generate(self, request):
        self.calls.append(request)
        # 두 run이 같은 원시 클라이언트를 공유해도 실행 상태는 각 문맥에 남아야 한다.
        await asyncio.sleep(0)
        data = json.loads(request.messages[-1].content)
        if request.response_model is SupervisorDecision:
            first_call = not data["completedCalls"]
            parsed = SupervisorDecision(
                nextAction="CALL_ROLE" if first_call else "ASK_USER",
                role=AgentRole.INQUIRY if first_call else None,
                publicSummary="문의 내용을 확인하고 있습니다.",
            )
        else:
            assert request.response_model is InquiryResult
            parsed = inquiry_result(data)
        return LLMResponse(parsed=parsed, usage=LLMUsage(10, 3), model_id="test-only")


class InjectedInquiry:
    """동일한 역할 인터페이스로 직접 결과 또는 주입된 LLM 결과를 돌려주는 대역."""

    role = AgentRole.INQUIRY

    def __init__(self, *, use_model=True) -> None:
        self.use_model = use_model
        self.contexts = []

    async def run(self, request, services):
        self.contexts.append((request.state["input"].runId, services))
        original = request.state["input"]
        data = {
            "invocationId": request.invocation_id,
            "problemRef": original.problems[0].id,
            "messageId": original.messages[0].id,
            "label": original.messages[0].content,
        }
        if not self.use_model:
            return inquiry_result(data)
        response = await services.llm.generate(
            LLMRequest(
                messages=[LLMMessage("user", json.dumps(data, ensure_ascii=False))],
                response_model=InquiryResult,
                max_output_tokens=100,
                prompt_version="test.injected-inquiry",
            )
        )
        return response.parsed


class UnusedTool:
    async def call(self, role, request):
        raise AssertionError("이 검사는 실제 도구를 호출하지 않는다")


def compose(request, llm):
    """외부 연결 없이 공용 조립 함수로 독립적인 run 의존성을 준비한다."""
    policy = ToolPolicy()
    return create_run_services(
        request,
        settings=Settings(_env_file=None),
        access_scope_ref=f"test-scope:{request.runId}",
        llm=llm,
        vector=PgVectorRetriever(),
        graph=Neo4jRetriever(),
        tools=GuardedToolGateway(UnusedTool(), ToolRegistry(), policy),
        tool_policy=policy,
    )


@pytest.mark.parametrize("use_model", [False, True])
async def test_injected_role_implementation_and_model_complete_a_run(use_model) -> None:
    request = make_request(question_count=4)
    llm = ContextualLLM()
    services = compose(request, llm)
    inquiry = InjectedInquiry(use_model=use_model)

    result = await Supervisor({AgentRole.INQUIRY: inquiry}).analyze(request, services)

    assert result.status == "SUCCEEDED"
    assert len(result.questions) == 1
    assert result.answer.count(result.questions[0].text) == 1
    assert request.problems[0].questionCount == 4
    assert services.llm is llm
    assert len(inquiry.contexts) == 1
    _, role_services = inquiry.contexts[0]
    assert role_services.runtime is services.runtime
    assert role_services.llm is not llm
    assert services.runtime.budget.llm_calls_used == len(llm.calls) == (3 if use_model else 2)


async def test_one_supervisor_keeps_concurrent_run_inputs_budgets_and_results_separate() -> None:
    first = make_request()
    fields = first.model_dump(mode="json")
    fields.update(runId=uid(500), inquiryId=uid(501), answerMessageId=uid(502), inquiryVersion=3)
    fields["problemIds"] = [uid(510)]
    fields["problems"][0]["id"] = uid(510)
    fields["messages"][0].update(id=uid(520), problemIds=[uid(510)], content="두 번째 합성 문의")
    second = AiRunRequest.model_validate(fields)
    llm = ContextualLLM()
    inquiry = InjectedInquiry()
    supervisor = Supervisor({AgentRole.INQUIRY: inquiry})
    first_services, second_services = compose(first, llm), compose(second, llm)

    first_result, second_result = await asyncio.gather(
        supervisor.analyze(first, first_services),
        supervisor.analyze(second, second_services),
    )

    for request, result in ((first, first_result), (second, second_result)):
        assert result.status == "SUCCEEDED"
        assert (result.runId, result.inquiryId, result.inquiryVersion) == (
            request.runId,
            request.inquiryId,
            request.inquiryVersion,
        )
        assert [item.problemRef for item in result.problemProposals] == request.problemIds
        assert request.messages[0].content in result.questions[0].text
    assert second.messages[0].content not in first_result.answer
    assert first.messages[0].content not in second_result.answer
    assert first_services.runtime is not second_services.runtime
    assert first_services.runtime.budget.llm_calls_used == 3
    assert second_services.runtime.budget.llm_calls_used == 3
    assert len(llm.calls) == 6
    first_services.runtime.cancel()
    assert not second_services.runtime.is_cancelled


async def test_default_pending_role_returns_safe_failure_without_automatic_replacement() -> None:
    request = make_request()
    llm = ContextualLLM()

    result = await Supervisor().analyze(request, compose(request, llm))

    assert result.status == "FAILED"
    assert result.error.code == "SERVICE_UNAVAILABLE"
    assert result.answer is None
    assert result.problemProposals == result.questions == result.ticketProposals == []
    assert len(llm.calls) == 1


async def test_executor_context_reaches_supervisor_and_checked_result_is_stored() -> None:
    """실행 관리자가 만든 문맥을 재생성하지 않고 Supervisor에 연결한다.

    실제 모델·PostgreSQL·HTTP는 사용하지 않는다. 팀이 RunPipeline 어댑터를
    구현할 때 같은 문맥과 재시도 상한을 유지해야 한다는 연결 조건을 검사한다.
    """
    request = make_request()
    settings = Settings(_env_file=None)
    llm = ContextualLLM()
    inquiry = InjectedInquiry(use_model=False)
    supervisor = Supervisor({AgentRole.INQUIRY: inquiry})
    contexts = []

    class InjectedPipeline:
        async def run(self, request, context):
            contexts.append(context)
            services = SharedServices(
                runtime=context,
                llm=llm,
                vector=PgVectorRetriever(),
                graph=Neo4jRetriever(),
                tools=UnusedTool(),
            )
            return await supervisor.analyze(request, services)

    ledger = MemoryLedger()
    admission = RunAdmissionService(ledger, server_ceilings(settings))
    executor = RunExecutor(ledger, admission, InjectedPipeline(), settings)
    try:
        accepted = await executor.submit(request)
        assert accepted.created
        async with asyncio.timeout(2):
            while True:
                snapshot = await ledger.get(UUID(request.runId))
                if snapshot.result is not None:
                    break
                await asyncio.sleep(0)
        assert snapshot.status == "SUCCEEDED"
        assert snapshot.result["questions"][0]["problemRef"] == request.problemIds[0]
        assert snapshot.result["answer"].count(snapshot.result["questions"][0]["text"]) == 1
        assert inquiry.contexts[0][1].runtime is contexts[0]
        assert contexts[0].budget.llm_calls_used == 2
        assert contexts[0].budget.max_run_retries == request.limits.maxRetries
    finally:
        await executor.shutdown()
