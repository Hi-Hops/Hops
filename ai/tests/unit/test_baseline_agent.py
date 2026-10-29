import asyncio

import pytest

from hops_ai.agents.base import Analyzer, run_analysis
from hops_ai.agents.baseline.agent import BaselineAgent
from hops_ai.agents.baseline.decision import Decision, FinalResult, ToolArgument
from hops_ai.contracts.agent_1 import Evidence, IntakeProblem, Problem, TicketProposal
from hops_ai.contracts.analysis import AnalysisFailure, AnalysisOutcome
from hops_ai.fakes.llm import FakeLLMClient
from hops_ai.fakes.retrieval import FakeRetriever
from hops_ai.fakes.tools import FakeToolGateway, fake_tool_ok
from hops_ai.harness.errors import RetrievalUnavailable, RunCancelled, TransientError
from hops_ai.harness.events import RunEvents
from hops_ai.harness.policy import ToolPolicy
from hops_ai.services.llm.client import LLMUsage
from hops_ai.services.retrieval.base import RetrievalFilter
from hops_ai.services.tools.base import ToolResult
from hops_ai.services.tools.guarded import GuardedToolGateway
from hops_ai.services.tools.registry import ToolRegistry, ToolSpec

PROMPT = "당신은 IT 문의를 분석한다. (테스트용 프롬프트)"
VERSION = "baseline.test-v1"
EXISTING = "00000000-0000-4000-8000-000000000201"
MESSAGE_EVIDENCE = Evidence(
    id="msg-601-r1",
    kind="MESSAGE",
    sourceId="00000000-0000-4000-8000-000000000601",
    sourceVersion="1",
    summary="가상 VPN 오류 문의",
    observedAt=None,
)
STATUS_TOOL = ToolSpec(
    name="read_service_status",
    description="가상 서비스 상태를 조회한다",
    required=frozenset({"serviceId"}),
)
LOGS_TOOL = ToolSpec(name="read_logs", description="가상 로그를 조회한다")
OBSERVED_AT = "2026-10-28T00:00:30Z"


# ------------------------------------------------------------------ 모델 판단 만들기


def search(query: str) -> Decision:
    return Decision(action="SEARCH", query=query, toolName=None, toolArguments=[], final=None)


def diagnose(tool: str = "read_service_status", **arguments: str) -> Decision:
    return Decision(
        action="DIAGNOSE",
        query=None,
        toolName=tool,
        toolArguments=[ToolArgument(name=name, value=value) for name, value in arguments.items()],
        final=None,
    )


def finish(final: FinalResult) -> Decision:
    return Decision(action="FINISH", query=None, toolName=None, toolArguments=[], final=final)


def asking(evidence_ids=("msg-601-r1",)) -> FinalResult:
    return FinalResult(
        answer="오류 코드를 알려 주세요.",
        problems=[
            IntakeProblem(
                ref="new:p1",
                summary="VPN 접속 오류",
                category="NETWORK_ACCESS",
                questionCount=0,
                nextAction="ASK_USER",
                question="오류 코드를 알려 주세요.",
                evidenceIds=list(evidence_ids),
            )
        ],
        ticketProposals=[],
    )


def proposing(evidence_ids=("msg-601-r1",), department="NETWORK") -> FinalResult:
    return FinalResult(
        answer="담당 부서에 연결할게요.",
        problems=[
            IntakeProblem(
                ref=EXISTING,
                summary="VPN 접속 오류",
                category="NETWORK_ACCESS",
                questionCount=1,
                nextAction="PROPOSE_TICKETS",
                question=None,
                evidenceIds=list(evidence_ids),
            )
        ],
        ticketProposals=[
            TicketProposal(
                ref="new:work-1",
                problemRef=EXISTING,
                workScope="VPN 연결 확인",
                title="VPN 접속 오류",
                description="가상 설명",
                category="NETWORK_ACCESS",
                departmentCode=department,
                purpose="INCIDENT",
                diagnosisSummary="가상 진단",
                handoffReason="가상 이관 사유",
                uncertainty=None,
                evidenceIds=list(evidence_ids),
            )
        ],
    )


def agent(**overrides) -> BaselineAgent:
    options = {"system_prompt": PROMPT, "prompt_version": VERSION}
    options.update(overrides)
    return BaselineAgent(**options)


@pytest.fixture
def first_inquiry(make_input):
    return make_input(evidence=[MESSAGE_EVIDENCE])


@pytest.fixture
def follow_up(make_input, make_message):
    return make_input(
        messages=[make_message("오류 코드는 809예요.", 603)],
        existing_problems=[
            Problem(
                ref=EXISTING, summary="VPN 접속 오류", category="NETWORK_ACCESS", questionCount=1
            )
        ],
        evidence=[MESSAGE_EVIDENCE],
    )


def user_text(llm: FakeLLMClient, index: int) -> str:
    return llm.requests[index].messages[1].content


# ------------------------------------------------------------------ 바로 끝내는 경우


async def test_a_decision_to_finish_becomes_the_analysis_outcome(
    make_services, first_inquiry
) -> None:
    llm = FakeLLMClient(finish(asking()))

    outcome = await agent().analyze(first_inquiry, make_services(llm=llm))

    assert isinstance(outcome, AnalysisOutcome)
    assert outcome.answer == "오류 코드를 알려 주세요."
    assert [item.nextAction for item in outcome.problems] == ["ASK_USER"]
    assert outcome.ticket_proposals == []
    assert [item.id for item in outcome.evidence] == ["msg-601-r1"]
    assert len(llm.requests) == 1


async def test_model_request_has_the_prompt_version_the_token_cap_and_the_case(
    make_services, first_inquiry
) -> None:
    llm = FakeLLMClient(finish(asking()))

    await agent().analyze(first_inquiry, make_services(llm=llm))

    [request] = llm.requests
    assert request.response_model is Decision
    assert request.prompt_version == VERSION
    assert request.max_output_tokens == 1500
    assert request.messages[0].content == PROMPT
    assert "VPN 접속 오류가 나요." in user_text(llm, 0)
    assert "NETWORK: 네트워크" in user_text(llm, 0)


async def test_remaining_model_calls_are_shown_to_the_model(make_services, first_inquiry) -> None:
    llm = FakeLLMClient(search("VPN"), finish(asking()))

    await agent().analyze(first_inquiry, make_services(llm=llm))

    assert "이번 호출을 포함해 8회" in user_text(llm, 0)
    assert "이번 호출을 포함해 7회" in user_text(llm, 1)


async def test_usage_is_recorded_for_every_decision(make_services, first_inquiry) -> None:
    llm = FakeLLMClient(
        search("VPN"), finish(asking()), usage=LLMUsage(input_tokens=100, output_tokens=20)
    )
    services = make_services(llm=llm)

    await agent().analyze(first_inquiry, services)

    summary = services.usage.summary()
    assert (summary.model_calls, summary.input_tokens, summary.output_tokens) == (2, 200, 40)
    assert summary.prompt_versions == (VERSION,)


# ------------------------------------------------------------------ 검색


async def test_search_results_are_shown_to_the_next_decision_and_usable_as_evidence(
    make_services, first_inquiry
) -> None:
    llm = FakeLLMClient(search("VPN"), finish(asking(("msg-601-r1", "doc-chunk-fake-guide-vpn-1"))))
    retriever = FakeRetriever()

    outcome = await agent().analyze(first_inquiry, make_services(llm=llm, retriever=retriever))

    assert len(retriever.queries) == 1
    assert "doc-chunk-fake-guide-vpn-1" in user_text(llm, 1)
    assert "VPN 접속 오류 직원 안내" in user_text(llm, 1)
    assert [item.id for item in outcome.evidence] == ["msg-601-r1", "doc-chunk-fake-guide-vpn-1"]
    document = outcome.evidence[1]
    assert (document.kind, document.sourceId, document.sourceVersion) == (
        "DOCUMENT",
        "FAKE-GUIDE-VPN",
        "v1",
    )


async def test_the_search_scope_is_set_by_the_server_not_the_model(
    make_services, first_inquiry
) -> None:
    retriever = FakeRetriever()

    await agent().analyze(
        first_inquiry,
        make_services(llm=FakeLLMClient(search("VPN"), finish(asking())), retriever=retriever),
    )
    await agent(search_mode="SUPPORT_DIAGNOSIS").analyze(
        first_inquiry,
        make_services(llm=FakeLLMClient(search("VPN"), finish(asking())), retriever=retriever),
    )

    assert [query.filter for query in retriever.queries] == [
        RetrievalFilter.for_mode("ANSWER"),
        RetrievalFilter.for_mode("SUPPORT_DIAGNOSIS"),
    ]


async def test_internal_documents_are_marked_so_the_model_does_not_quote_them(
    make_services, first_inquiry
) -> None:
    llm = FakeLLMClient(search("VPN 게이트웨이"), finish(asking()))

    await agent(search_mode="SUPPORT_DIAGNOSIS").analyze(first_inquiry, make_services(llm=llm))

    assert "내부 전용" in user_text(llm, 1)


async def test_an_empty_search_result_is_reported_as_no_result(
    make_services, first_inquiry
) -> None:
    llm = FakeLLMClient(search("프린터 토너"), finish(asking()))

    await agent().analyze(first_inquiry, make_services(llm=llm))

    assert "결과 없음" in user_text(llm, 1)


async def test_the_same_search_is_not_run_twice(make_services, first_inquiry) -> None:
    llm = FakeLLMClient(search("VPN"), search("VPN"), finish(asking()))
    retriever = FakeRetriever()

    await agent().analyze(first_inquiry, make_services(llm=llm, retriever=retriever))

    assert len(retriever.queries) == 1
    assert "이미 실행했다" in user_text(llm, 2)


async def test_search_outage_is_reported_not_treated_as_no_result(
    make_services, first_inquiry
) -> None:
    llm = FakeLLMClient(search("VPN"), finish(asking()))
    retriever = FakeRetriever(fail_with=RetrievalUnavailable())

    result = await run_analysis(agent(), first_inquiry, make_services(llm=llm, retriever=retriever))

    assert isinstance(result, AnalysisFailure)
    assert (result.error.code, result.error.retryable) == ("RETRIEVAL_UNAVAILABLE", True)


async def test_search_can_be_turned_off(make_services, first_inquiry) -> None:
    llm = FakeLLMClient(search("VPN"), finish(asking()))
    retriever = FakeRetriever()

    await agent(search_enabled=False).analyze(
        first_inquiry, make_services(llm=llm, retriever=retriever)
    )

    assert "SEARCH:" not in user_text(llm, 0)
    assert "- SEARCH" not in user_text(llm, 0)
    assert retriever.queries == []
    assert "사용할 수 없다" in user_text(llm, 1)


# ------------------------------------------------------------------ 도구


def guarded_tools(inner: FakeToolGateway, allowed: list[str]):
    policy = ToolPolicy.of({"DIAGNOSIS": allowed}) if allowed else ToolPolicy()
    gateway = GuardedToolGateway(inner, ToolRegistry(STATUS_TOOL, LOGS_TOOL), policy)
    return gateway, policy


def status_ok() -> ToolResult:
    return fake_tool_ok(
        "read_service_status",
        {"state": "DEGRADED"},
        result_id="vpn-gateway-status-0001",
        observed_at=OBSERVED_AT,
    )


async def test_tool_result_and_its_evidence_reach_the_next_decision_and_the_outcome(
    make_services, follow_up
) -> None:
    inner = FakeToolGateway({"read_service_status": status_ok()})
    tools, policy = guarded_tools(inner, ["read_service_status"])
    llm = FakeLLMClient(
        diagnose(serviceId="vpn-gateway"),
        finish(proposing(("msg-601-r1", "tool-vpn-gateway-status-0001"))),
    )

    outcome = await agent(tool_specs=[STATUS_TOOL, LOGS_TOOL]).analyze(
        follow_up, make_services(llm=llm, tools=tools, tool_policy=policy)
    )

    assert inner.calls[0][0] == "DIAGNOSIS"
    assert inner.calls[0][1].arguments == {"serviceId": "vpn-gateway"}
    assert "DEGRADED" in user_text(llm, 1)
    assert "tool-vpn-gateway-status-0001" in user_text(llm, 1)
    tool_evidence = [item for item in outcome.evidence if item.kind == "TOOL"]
    assert [item.observedAt for item in tool_evidence] == [OBSERVED_AT]


async def test_only_tools_the_policy_allows_are_offered_to_the_model(
    make_services, first_inquiry
) -> None:
    tools, policy = guarded_tools(FakeToolGateway(), ["read_service_status"])
    llm = FakeLLMClient(finish(asking()))

    await agent(tool_specs=[STATUS_TOOL, LOGS_TOOL]).analyze(
        first_inquiry, make_services(llm=llm, tools=tools, tool_policy=policy)
    )

    assert "read_service_status" in user_text(llm, 0)
    assert "read_logs" not in user_text(llm, 0)


async def test_with_an_empty_allow_list_no_tool_is_offered(make_services, first_inquiry) -> None:
    llm = FakeLLMClient(finish(asking()))

    await agent(tool_specs=[STATUS_TOOL, LOGS_TOOL]).analyze(first_inquiry, make_services(llm=llm))

    assert "DIAGNOSE" not in user_text(llm, 0)


async def test_a_denied_tool_is_reported_and_never_treated_as_normal(
    make_services, follow_up
) -> None:
    inner = FakeToolGateway({"read_service_status": status_ok()})
    tools, policy = guarded_tools(inner, [])  # 허용 목록이 비어 있다
    llm = FakeLLMClient(diagnose(serviceId="vpn-gateway"), finish(proposing()))

    outcome = await agent(tool_specs=[STATUS_TOOL]).analyze(
        follow_up, make_services(llm=llm, tools=tools, tool_policy=policy)
    )

    assert inner.calls == []  # 실제 게이트웨이는 불리지 않았다
    assert "DENIED" in user_text(llm, 1)
    assert "정상이나 원인으로 해석하지 않는다" in user_text(llm, 1)
    assert [item.kind for item in outcome.evidence] == ["MESSAGE"]


async def test_a_failed_tool_call_gives_no_evidence(make_services, follow_up) -> None:
    inner = FakeToolGateway(
        {"read_service_status": ToolResult.timeout("read_service_status", "조회 시간 초과")}
    )
    tools, policy = guarded_tools(inner, ["read_service_status"])
    llm = FakeLLMClient(diagnose(serviceId="vpn-gateway"), finish(proposing()))

    outcome = await agent(tool_specs=[STATUS_TOOL]).analyze(
        follow_up, make_services(llm=llm, tools=tools, tool_policy=policy)
    )

    assert "TIMEOUT" in user_text(llm, 1)
    assert [item.kind for item in outcome.evidence] == ["MESSAGE"]


async def test_the_same_tool_call_is_not_run_twice(make_services, follow_up) -> None:
    inner = FakeToolGateway({"read_service_status": status_ok()})
    tools, policy = guarded_tools(inner, ["read_service_status"])
    llm = FakeLLMClient(
        diagnose(serviceId="vpn-gateway"),
        diagnose(serviceId="vpn-gateway"),
        finish(proposing()),
    )

    await agent(tool_specs=[STATUS_TOOL]).analyze(
        follow_up, make_services(llm=llm, tools=tools, tool_policy=policy)
    )

    assert len(inner.calls) == 1
    assert "이미 실행했다" in user_text(llm, 2)


async def test_duplicated_argument_names_are_not_sent_to_the_tool(make_services, follow_up) -> None:
    inner = FakeToolGateway({"read_service_status": status_ok()})
    tools, policy = guarded_tools(inner, ["read_service_status"])
    duplicated = Decision(
        action="DIAGNOSE",
        query=None,
        toolName="read_service_status",
        toolArguments=[
            ToolArgument(name="serviceId", value="vpn"),
            ToolArgument(name="serviceId", value="mail"),
        ],
        final=None,
    )
    llm = FakeLLMClient(duplicated, finish(proposing()))

    await agent(tool_specs=[STATUS_TOOL]).analyze(
        follow_up, make_services(llm=llm, tools=tools, tool_policy=policy)
    )

    assert inner.calls == []
    assert "중복" in user_text(llm, 1)


# ------------------------------------------------------------------ 결과 검증


async def test_an_invented_evidence_id_is_rejected(make_services, first_inquiry) -> None:
    llm = FakeLLMClient(finish(asking(("msg-601-r1", "doc-does-not-exist"))))

    result = await run_analysis(agent(), first_inquiry, make_services(llm=llm))

    assert isinstance(result, AnalysisFailure)
    assert result.error.code == "INVALID_OUTPUT"


async def test_a_department_outside_the_catalog_is_rejected(make_services, follow_up) -> None:
    llm = FakeLLMClient(finish(proposing(department="MARS")))

    result = await run_analysis(agent(), follow_up, make_services(llm=llm))

    assert isinstance(result, AnalysisFailure)
    assert result.error.code == "INVALID_OUTPUT"


async def test_a_changed_question_count_is_rejected(make_services, follow_up) -> None:
    final = proposing()
    tampered = final.problems[0].model_copy(update={"questionCount": 0})
    llm = FakeLLMClient(finish(final.model_copy(update={"problems": [tampered]})))

    result = await run_analysis(agent(), follow_up, make_services(llm=llm))

    assert result.error.code == "INVALID_OUTPUT"


# ------------------------------------------------------------------ 예산과 반복


async def test_the_loop_stops_at_the_model_call_limit(make_services, first_inquiry) -> None:
    queries = [search(f"질문 {index}") for index in range(10)]
    llm = FakeLLMClient(*queries)
    retriever = FakeRetriever()

    result = await run_analysis(
        agent(), first_inquiry, make_services(llm=llm, retriever=retriever, max_llm_calls=3)
    )

    assert isinstance(result, AnalysisFailure)
    assert result.error.code == "BUDGET_EXCEEDED"
    assert len(llm.requests) == 3
    assert len(retriever.queries) == 3


async def test_a_run_that_uses_every_allowed_call_can_still_finish(
    make_services, first_inquiry
) -> None:
    # 기본 상한 8회: 검색 7번 뒤 8번째 판단에서 끝낸다. 그래프 단계 제한이 이를 막지 않는다.
    decisions = [search(f"질문 {index}") for index in range(7)] + [finish(asking())]
    llm = FakeLLMClient(*decisions)

    outcome = await agent().analyze(first_inquiry, make_services(llm=llm))

    assert isinstance(outcome, AnalysisOutcome)
    assert len(llm.requests) == 8


async def test_a_transient_model_error_is_retried_inside_the_budget(
    make_services, first_inquiry
) -> None:
    llm = FakeLLMClient(TransientError(), finish(asking()))
    services = make_services(llm=llm)

    outcome = await agent().analyze(first_inquiry, services)

    assert isinstance(outcome, AnalysisOutcome)
    assert services.runtime.budget.llm_calls_used == 2


async def test_a_cancelled_run_never_calls_the_model(make_services, first_inquiry) -> None:
    llm = FakeLLMClient(finish(asking()))
    services = make_services(llm=llm)
    services.runtime.cancel()

    with pytest.raises(RunCancelled):
        await agent().analyze(first_inquiry, services)

    assert llm.requests == []


async def test_deadline_passing_during_a_search_stops_the_next_decision(
    make_services, first_inquiry, clock
) -> None:
    class SlowRetriever(FakeRetriever):
        async def search(self, query):
            clock.advance(60)
            return await super().search(query)

    llm = FakeLLMClient(search("VPN"), finish(asking()))

    result = await run_analysis(
        agent(), first_inquiry, make_services(llm=llm, retriever=SlowRetriever())
    )

    assert isinstance(result, AnalysisFailure)
    assert result.status == "TIMED_OUT"
    assert len(llm.requests) == 1


async def test_cancellation_of_the_task_is_not_swallowed(make_services, first_inquiry) -> None:
    llm = FakeLLMClient(asyncio.CancelledError())

    with pytest.raises(asyncio.CancelledError):
        await run_analysis(agent(), first_inquiry, make_services(llm=llm))


# ------------------------------------------------------------------ 진행 이벤트와 규약


async def test_progress_events_are_sent_for_search_and_tool_steps(make_services, follow_up) -> None:
    inner = FakeToolGateway({"read_service_status": status_ok()})
    tools, policy = guarded_tools(inner, ["read_service_status"])
    events = RunEvents(
        run_id="00000000-0000-4000-8000-000000000001",
        inquiry_id="00000000-0000-4000-8000-000000000003",
        inquiry_version=1,
    )
    llm = FakeLLMClient(search("VPN"), diagnose(serviceId="vpn-gateway"), finish(proposing()))

    await (
        agent(tool_specs=[STATUS_TOOL])
        .with_events(events)
        .analyze(follow_up, make_services(llm=llm, tools=tools, tool_policy=policy))
    )

    assert [event.payload.stepType for _, event in events.history()] == ["SEARCH", "DIAGNOSE"]


def test_the_agent_satisfies_the_analyzer_interface() -> None:
    assert isinstance(agent(), Analyzer)


async def test_the_result_has_the_same_shape_as_the_multi_agent_result(
    make_services, first_inquiry
) -> None:
    from hops_ai.agents.registry import build_fake_registry
    from hops_ai.supervisor.graph import Supervisor

    single = await agent().analyze(
        first_inquiry, make_services(llm=FakeLLMClient(finish(asking())))
    )
    multi = await Supervisor(build_fake_registry().roles).analyze(first_inquiry, make_services())

    assert type(single) is type(multi) is AnalysisOutcome
    assert [item.nextAction for item in single.problems] == [
        item.nextAction for item in multi.problems
    ]


class HangingLLM:
    """응답하지 않는 모델. 실행을 중간에 취소하는 상황을 만든다."""

    def __init__(self) -> None:
        self.started = asyncio.Event()

    async def generate(self, request):
        self.started.set()
        await asyncio.Event().wait()


async def test_cancelling_the_task_while_waiting_for_the_model_stops_the_run(
    make_services, first_inquiry
) -> None:
    llm = HangingLLM()
    task = asyncio.create_task(run_analysis(agent(), first_inquiry, make_services(llm=llm)))
    await llm.started.wait()

    task.cancel()

    with pytest.raises(asyncio.CancelledError):
        await task
    assert task.cancelled()  # 취소가 실패 결과로 바뀌어 실행이 계속되지 않았다
