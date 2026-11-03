"""공통 도구 wrapper의 누적 한도·기본 거부·실패 분류를 검사한다."""

import asyncio
from datetime import timedelta

import pytest

from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import BudgetExceeded, ComponentNotReady, PolicyDenied, RunCancelled
from hops_ai.harness.policy import ToolPolicy
from hops_ai.services.tools.base import ToolRequest, ToolResult
from hops_ai.services.tools.budgeted import BudgetedToolGateway, ToolUnavailable
from hops_ai.services.tools.guarded import GuardedToolGateway
from hops_ai.services.tools.registry import ToolRegistry, ToolSpec
from tests.doubles.tools import FakeToolGateway, fake_tool_ok


def gateway_for(clock, make_settings, *, outcome=None, guarded=True, allowed=True):
    """허용 도구 하나와 호출을 관찰할 명시적 어댑터를 준비한다."""
    policy = ToolPolicy.of({AgentRole.INQUIRY: {"read_status"}}) if allowed else ToolPolicy()
    context = RunContext.create(
        run_id="test-run",
        inquiry_id="test-inquiry",
        inquiry_version=1,
        access_scope_ref="test-scope",
        settings=make_settings(),
        tool_policy=policy,
        clock=clock,
    )
    context.budget.constrain(
        max_model_calls=8,
        max_input_tokens=1000,
        max_total_output_tokens=1000,
        max_tool_calls=1,
        max_run_retries=2,
        deadline_at=clock.now() + timedelta(seconds=60),
    )
    result = outcome or fake_tool_ok(
        "read_status", {"state": "MOCK"}, result_id="1", observed_at="2026-11-03T00:00:00Z"
    )
    inner = FakeToolGateway({"read_status": result})
    guarded_inner = (
        GuardedToolGateway(inner, ToolRegistry(ToolSpec("read_status", "가상 조회")), policy)
        if guarded
        else inner
    )
    return BudgetedToolGateway(guarded_inner, context), context, inner


async def test_success_reserves_one_call_and_stops_at_total_limit(clock, make_settings):
    gateway, context, inner = gateway_for(clock, make_settings)
    request = ToolRequest("read_status", {})
    assert (await gateway.call(AgentRole.INQUIRY, request)).status == "OK"
    with pytest.raises(BudgetExceeded):
        await gateway.call(AgentRole.INQUIRY, request)
    assert context.budget.tool_calls_used == len(inner.calls) == 1


async def test_default_deny_blocks_raw_adapter_before_invocation(clock, make_settings):
    gateway, context, inner = gateway_for(clock, make_settings, guarded=False, allowed=False)
    with pytest.raises(PolicyDenied):
        await gateway.call(AgentRole.INQUIRY, ToolRequest("read_status", {}))
    assert context.budget.tool_calls_used == len(inner.calls) == 0


async def test_allowed_tool_still_requires_registration_and_argument_guard(clock, make_settings):
    gateway, _, inner = gateway_for(clock, make_settings, guarded=False)
    with pytest.raises(ComponentNotReady):
        await gateway.call(AgentRole.INQUIRY, ToolRequest("read_status", {}))
    assert inner.calls == []


async def test_bad_arguments_do_not_reach_raw_adapter(clock, make_settings):
    gateway, _, inner = gateway_for(clock, make_settings)
    with pytest.raises(ToolUnavailable):
        await gateway.call(AgentRole.INQUIRY, ToolRequest("read_status", {"extra": 1}))
    assert inner.calls == []


@pytest.mark.parametrize(
    "status,error",
    [("DENIED", PolicyDenied), ("ERROR", ToolUnavailable), ("TIMEOUT", ToolUnavailable)],
)
async def test_failure_status_is_not_a_normal_result(clock, make_settings, status, error):
    gateway, context, _ = gateway_for(
        clock, make_settings, outcome=ToolResult(status, "read_status", None, None, "가상 실패")
    )
    with pytest.raises(error):
        await gateway.call(AgentRole.INQUIRY, ToolRequest("read_status", {}))
    assert context.budget.tool_calls_used == 1


async def test_raw_tool_exception_is_classified_without_exposing_details(clock, make_settings):
    def broken(request):
        raise RuntimeError("private adapter details")

    gateway, _, _ = gateway_for(clock, make_settings, outcome=broken)
    with pytest.raises(ToolUnavailable) as raised:
        await gateway.call(AgentRole.INQUIRY, ToolRequest("read_status", {}))
    assert "private adapter details" not in str(raised.value)


async def test_external_task_cancellation_is_not_converted_to_tool_failure(clock, make_settings):
    class CancelledGateway(GuardedToolGateway):
        async def call(self, role, request):
            raise asyncio.CancelledError

    _, context, inner = gateway_for(clock, make_settings)
    gateway = BudgetedToolGateway(
        CancelledGateway(inner, ToolRegistry(), context.tool_policy), context
    )
    with pytest.raises(asyncio.CancelledError):
        await gateway.call(AgentRole.INQUIRY, ToolRequest("read_status", {}))


async def test_late_success_after_context_cancel_is_discarded(clock, make_settings):
    supplied = {}

    def cancelled_response(request):
        supplied["context"].cancel()
        return fake_tool_ok(
            "read_status", {"state": "MOCK"}, result_id="2", observed_at="2026-11-03T00:00:00Z"
        )

    gateway, context, _ = gateway_for(clock, make_settings, outcome=cancelled_response)
    supplied["context"] = context
    with pytest.raises(RunCancelled):
        await gateway.call(AgentRole.INQUIRY, ToolRequest("read_status", {}))
