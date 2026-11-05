"""합성 요청과 명시적 Fake로 실행 제한을 검증한다. 실제 모델·도구는 호출하지 않는다."""

import asyncio
from dataclasses import replace
from datetime import timedelta

import pytest
from pydantic import BaseModel

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.evidence import Evidence
from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import (
    BudgetExceeded,
    CallTimeoutError,
    ComponentNotReady,
    DeadlineExceeded,
    InputRejected,
    OutputRejected,
    PolicyDenied,
    RetrievalUnavailable,
    RunCancelled,
    TransientError,
)
from hops_ai.harness.policy import ToolPolicy
from hops_ai.services.llm.budgeted import BudgetedLLMClient
from hops_ai.services.llm.client import LLMRequest, LLMResponse, LLMUsage
from hops_ai.services.llm.usage import UsageRecorder
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.base import ToolRequest, ToolResult
from hops_ai.services.tools.guarded import GuardedToolGateway
from hops_ai.services.tools.registry import ToolRegistry, ToolSpec
from hops_ai.supervisor.runtime import SupervisorRuntime, ToolUnavailable, describe_run_failure
from hops_ai.supervisor.state import create_initial_state
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import make_request


class Reply(BaseModel):
    text: str


class ToolDouble:
    def __init__(self, result=None) -> None:
        self.calls = 0
        self.result = result or ToolResult.ok(
            "read_status",
            {"state": "MOCK"},
            Evidence(
                id="tool:1",
                kind="TOOL",
                sourceId="mock-status",
                sourceVersion="1",
                summary="가상 조회",
                observedAt="2026-11-03T00:00:00Z",
                origin="MOCK",
            ),
        )

    async def call(self, role, request):
        self.calls += 1
        return self.result


def llm_request(tokens=100):
    return LLMRequest([], Reply, tokens, "test.runtime.v1")


def runtime_for(
    clock,
    make_settings,
    *,
    llm=None,
    tools=None,
    limits=None,
    policy=None,
    guarded=True,
    **settings,
):
    request = make_request()
    if limits:
        request = request.model_copy(
            update={
                "limits": request.limits.model_copy(update=limits),
            }
        )
    context = RunContext.create(
        run_id=request.runId,
        inquiry_id=request.inquiryId,
        inquiry_version=request.inquiryVersion,
        access_scope_ref="test-only",
        settings=make_settings(**settings),
        clock=clock,
        tool_policy=policy
        if policy is not None
        else ToolPolicy.of(
            {AgentRole.INQUIRY: {"read_status"}, AgentRole.KNOWLEDGE: {"read_status"}}
        ),
    )
    gateway = tools or ToolDouble()
    if guarded:
        gateway = GuardedToolGateway(
            gateway, ToolRegistry(ToolSpec("read_status", "가상 조회")), context.tool_policy
        )
    services = SharedServices(context, llm or FakeLLMClient(Reply(text="응답")), gateway)
    return SupervisorRuntime(request, services), create_initial_state(request), services


async def test_calls_and_reported_tokens_accumulate_across_roles(clock, make_settings):
    fake = FakeLLMClient(Reply(text="첫째"), Reply(text="둘째"), usage=LLMUsage(10, 4))
    runtime, state, _ = runtime_for(clock, make_settings, llm=fake)
    await runtime.services.llm.generate(llm_request())
    await runtime.services.llm.generate(llm_request())
    synced = runtime.sync_state(state)
    assert synced["usage"] == {
        "calls": 2,
        "inputTokens": 20,
        "outputTokens": 8,
        "toolCalls": 0,
        "retries": 0,
    }
    assert state["usage"]["calls"] == 0
    synced["usage"]["calls"] = 0
    assert runtime.sync_state(state)["usage"]["calls"] == 2


async def test_request_call_ceiling_stops_before_provider(clock, make_settings):
    fake = FakeLLMClient(Reply(text="응답"), repeat_last=True)
    runtime, state, _ = runtime_for(clock, make_settings, llm=fake, limits={"maxModelCalls": 1})
    await runtime.services.llm.generate(llm_request())
    with pytest.raises(BudgetExceeded):
        await runtime.services.llm.generate(llm_request())
    assert len(fake.requests) == 1
    assert runtime.sync_state(state)["usage"]["calls"] == 1


async def test_output_request_uses_server_and_remaining_total_ceiling(clock, make_settings):
    fake = FakeLLMClient(Reply(text="응답"), usage=LLMUsage(1, 7), repeat_last=True)
    runtime, _, _ = runtime_for(
        clock, make_settings, llm=fake, max_output_tokens=8, limits={"maxOutputTokens": 10}
    )
    await runtime.services.llm.generate(llm_request(100))
    with pytest.raises(BudgetExceeded):
        await runtime.services.llm.generate(llm_request(100))
    assert [item.max_output_tokens for item in fake.requests] == [8, 3]


@pytest.mark.parametrize("usage", [LLMUsage(True, 1), LLMUsage(1, -1), LLMUsage(1.0, 1)])
async def test_invalid_usage_never_becomes_a_valid_result(clock, make_settings, usage):
    fake = FakeLLMClient(Reply(text="응답"), usage=usage)
    runtime, _, _ = runtime_for(clock, make_settings, llm=fake)
    with pytest.raises(OutputRejected):
        await runtime.services.llm.generate(llm_request())


async def test_input_token_overflow_is_recorded_and_result_is_rejected(clock, make_settings):
    fake = FakeLLMClient(Reply(text="응답"), usage=LLMUsage(20, 1))
    runtime, state, _ = runtime_for(clock, make_settings, llm=fake, limits={"maxInputTokens": 10})
    with pytest.raises(BudgetExceeded):
        await runtime.services.llm.generate(llm_request())
    assert runtime.sync_state(state)["usage"]["inputTokens"] == 20


async def test_retry_limit_is_shared_by_separate_generate_calls(clock, make_settings):
    fake = FakeLLMClient(
        TransientError(retry_after=0),
        Reply(text="성공"),
        TransientError(retry_after=0),
        Reply(text="호출하면 안 됨"),
    )
    runtime, state, _ = runtime_for(clock, make_settings, llm=fake, limits={"maxRetries": 1})
    await runtime.services.llm.generate(llm_request())
    with pytest.raises(TransientError):
        await runtime.services.llm.generate(llm_request())
    assert len(fake.requests) == 3
    assert runtime.sync_state(state)["usage"]["retries"] == 1
    assert runtime.sync_state(state)["usage"]["calls"] == 3


async def test_output_rejection_is_not_retried(clock, make_settings):
    fake = FakeLLMClient(OutputRejected(), Reply(text="호출하면 안 됨"))
    runtime, _, _ = runtime_for(clock, make_settings, llm=fake)
    with pytest.raises(OutputRejected):
        await runtime.services.llm.generate(llm_request())
    assert len(fake.requests) == 1


async def test_malformed_usage_is_an_output_error(clock, make_settings):
    class MalformedLLM:
        async def generate(self, request):
            return LLMResponse(Reply(text="응답"), None, "test-only")

    runtime, _, _ = runtime_for(clock, make_settings, llm=MalformedLLM())
    with pytest.raises(OutputRejected):
        await runtime.services.llm.generate(llm_request())


async def test_tool_calls_are_limited_across_roles(clock, make_settings):
    tools = ToolDouble()
    runtime, state, _ = runtime_for(clock, make_settings, tools=tools, limits={"maxToolCalls": 1})
    await runtime.services.tools.call(AgentRole.INQUIRY, ToolRequest("read_status", {}))
    with pytest.raises(BudgetExceeded):
        await runtime.services.tools.call(AgentRole.KNOWLEDGE, ToolRequest("read_status", {}))
    assert tools.calls == runtime.sync_state(state)["usage"]["toolCalls"] == 1


async def test_default_deny_policy_blocks_raw_tool_before_invocation(clock, make_settings):
    tools = ToolDouble()
    runtime, state, _ = runtime_for(
        clock, make_settings, tools=tools, policy=ToolPolicy(), guarded=False
    )
    with pytest.raises(PolicyDenied):
        await runtime.services.tools.call(AgentRole.INQUIRY, ToolRequest("read_status", {}))
    assert tools.calls == runtime.sync_state(state)["usage"]["toolCalls"] == 0


async def test_tool_requires_registry_and_argument_guard(clock, make_settings):
    tools = ToolDouble()
    runtime, state, _ = runtime_for(clock, make_settings, tools=tools, guarded=False)
    with pytest.raises(ComponentNotReady):
        await runtime.services.tools.call(AgentRole.INQUIRY, ToolRequest("read_status", {}))
    assert tools.calls == runtime.sync_state(state)["usage"]["toolCalls"] == 0


async def test_invalid_tool_arguments_never_reach_adapter(clock, make_settings):
    tools = ToolDouble()
    runtime, _, _ = runtime_for(clock, make_settings, tools=tools)
    with pytest.raises(ToolUnavailable):
        await runtime.services.tools.call(
            AgentRole.INQUIRY, ToolRequest("read_status", {"extra": 1})
        )
    assert tools.calls == 0


@pytest.mark.parametrize(
    "status,error",
    [("DENIED", PolicyDenied), ("ERROR", ToolUnavailable), ("TIMEOUT", ToolUnavailable)],
)
async def test_tool_failure_is_not_an_empty_success(clock, make_settings, status, error):
    tools = ToolDouble(ToolResult(status, "read_status", None, None, "가상 실패"))
    runtime, state, _ = runtime_for(clock, make_settings, tools=tools)
    with pytest.raises(error):
        await runtime.services.tools.call(AgentRole.INQUIRY, ToolRequest("read_status", {}))
    assert runtime.sync_state(state)["usage"]["toolCalls"] == 1


async def test_cancel_interrupts_waiting_work_and_rejects_late_result(clock, make_settings):
    runtime, state, services = runtime_for(clock, make_settings)
    entered = asyncio.Event()
    stopped = asyncio.Event()

    async def waiting():
        entered.set()
        try:
            await asyncio.Event().wait()
        finally:
            stopped.set()

    task = asyncio.create_task(runtime.run(waiting()))
    await entered.wait()
    services.runtime.cancel()
    with pytest.raises(RunCancelled):
        await task
    assert stopped.is_set()
    assert runtime.sync_state(state)["cancellation"]["cancelled"]


@pytest.mark.parametrize(
    "interruption,expected",
    [
        ("cancel", RunCancelled),
        ("deadline", DeadlineExceeded),
        ("external", asyncio.CancelledError),
    ],
)
async def test_slow_adapter_cleanup_does_not_delay_run_termination(
    clock, make_settings, interruption, expected
):
    runtime, _, services = runtime_for(clock, make_settings)
    entered = asyncio.Event()
    cleanup_started = asyncio.Event()
    release = asyncio.Event()

    async def waiting():
        entered.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            # 취소는 받았지만 원격 연결 정리가 끝나지 않는 어댑터를 재현한다.
            cleanup_started.set()
            await release.wait()
            return "늦은 결과"

    if interruption == "deadline":
        services.runtime.budget.deadline = clock.monotonic() + 0.001
    worker = asyncio.create_task(waiting())
    execution = asyncio.create_task(runtime.run(worker))
    await entered.wait()
    if interruption == "cancel":
        services.runtime.cancel()
    elif interruption == "external":
        execution.cancel()

    try:
        with pytest.raises(expected):
            await asyncio.wait_for(asyncio.shield(execution), timeout=1)
        assert cleanup_started.is_set()
        assert not worker.done()
        # 늦은 정리 작업이 살아 있어도 종료된 runtime으로 새 호출을 시작하지 못한다.
        with pytest.raises((InputRejected, RunCancelled, DeadlineExceeded)):
            await runtime.services.llm.generate(llm_request())
    finally:
        release.set()
        await asyncio.gather(worker, execution, return_exceptions=True)


async def test_expired_request_stops_before_work_starts(clock, make_settings):
    runtime, _, services = runtime_for(clock, make_settings)
    services.runtime.budget.deadline = clock.monotonic() - 1
    called = False

    async def operation():
        nonlocal called
        called = True

    with pytest.raises(DeadlineExceeded):
        await runtime.run(operation())
    assert not called


async def test_whole_run_deadline_interrupts_waiting_work(clock, make_settings):
    runtime, _, services = runtime_for(clock, make_settings)
    services.runtime.budget.deadline = clock.monotonic() + 0.001

    with pytest.raises(DeadlineExceeded):
        await runtime.run(asyncio.Event().wait())


async def test_cancelled_and_reused_runtime_do_not_start_more_work(clock, make_settings):
    runtime, _, services = runtime_for(clock, make_settings)
    called = False

    async def operation():
        nonlocal called
        called = True

    services.runtime.cancel()
    with pytest.raises(RunCancelled):
        await runtime.run(operation())
    with pytest.raises(InputRejected):
        await runtime.run(operation())
    assert not called


async def test_unexpected_tool_error_is_safe_tool_failure(clock, make_settings):
    class BrokenTool:
        async def call(self, role, request):
            raise RuntimeError("private adapter detail")

    runtime, _, _ = runtime_for(clock, make_settings, tools=BrokenTool())
    with pytest.raises(ToolUnavailable):
        await runtime.services.tools.call(AgentRole.INQUIRY, ToolRequest("read_status", {}))


def test_request_expiration_tightens_existing_server_deadline(clock, make_settings):
    _, state, services = runtime_for(clock, make_settings)
    # 별도 문맥으로 같은 요청보다 훨씬 이른 요청 기한을 적용한다.
    request = state["input"].model_copy(
        update={
            "expiresAt": (clock.now() + timedelta(seconds=5)).isoformat().replace("+00:00", "Z")
        }
    )
    fresh = RunContext.create(
        run_id=request.runId,
        inquiry_id=request.inquiryId,
        inquiry_version=request.inquiryVersion,
        access_scope_ref="test-only",
        settings=make_settings(),
        clock=clock,
    )
    SupervisorRuntime(request, replace(services, runtime=fresh))
    assert fresh.budget.remaining_seconds() == 5


def test_identity_mismatch_and_context_reuse_are_rejected(clock, make_settings):
    _, state, services = runtime_for(clock, make_settings)
    with pytest.raises(InputRejected):
        SupervisorRuntime(state["input"], services)
    services.runtime.inquiry_version += 1
    with pytest.raises(InputRejected):
        SupervisorRuntime(state["input"], services)


def test_prebudgeted_client_is_rejected(clock, make_settings):
    _, state, services = runtime_for(clock, make_settings)
    prebudgeted = BudgetedLLMClient(services.llm, services.runtime.budget, UsageRecorder())
    with pytest.raises(InputRejected):
        SupervisorRuntime(state["input"], replace(services, llm=prebudgeted))


def test_rejected_context_reuse_cannot_tighten_the_original_run_budget(clock, make_settings):
    """아직 호출하지 않은 문맥도 재사용 거부 전에 기존 예산을 바꾸지 않는다."""
    _, state, services = runtime_for(clock, make_settings)
    budget = services.runtime.budget
    original = (budget.max_llm_calls, budget.max_input_tokens, budget.deadline)
    request = state["input"].model_copy(
        update={
            "limits": state["input"].limits.model_copy(
                update={"maxModelCalls": 1, "maxInputTokens": 1}
            ),
            "expiresAt": (clock.now() + timedelta(seconds=1)).isoformat().replace("+00:00", "Z"),
        }
    )
    with pytest.raises(InputRejected):
        SupervisorRuntime(request, services)
    assert (budget.max_llm_calls, budget.max_input_tokens, budget.deadline) == original


@pytest.mark.parametrize(
    "error,status,code",
    [
        (DeadlineExceeded("private detail"), "TIMED_OUT", "DEADLINE_EXCEEDED"),
        (RunCancelled("private detail"), "SUPERSEDED", "SUPERSEDED"),
        (BudgetExceeded("private detail"), "FAILED", "BUDGET_EXCEEDED"),
        (PolicyDenied("private detail"), "FAILED", "AUTH_CONTEXT_INVALID"),
        (RetrievalUnavailable("private detail"), "FAILED", "SEARCH_UNAVAILABLE"),
        (CallTimeoutError("private detail"), "FAILED", "MODEL_UNAVAILABLE"),
        (ToolUnavailable("private detail"), "FAILED", "TOOL_UNAVAILABLE"),
        (InputRejected("private detail"), "FAILED", "INVALID_REQUEST"),
        (OutputRejected("private detail"), "FAILED", "INVALID_OUTPUT"),
        (ComponentNotReady("private detail"), "FAILED", "SERVICE_UNAVAILABLE"),
        (RuntimeError("private detail"), "FAILED", "SERVICE_UNAVAILABLE"),
    ],
)
def test_failure_mapping_uses_wire_codes_and_hides_details(error, status, code):
    phase, safe = describe_run_failure(error)
    assert (phase, safe.code) == (status, code)
    assert "private detail" not in safe.model_dump_json()
