import pytest

from hops_ai.contracts.agent_1 import Evidence
from hops_ai.fakes.tools import FakeToolGateway, fake_tool_ok
from hops_ai.harness.policy import ToolPolicy
from hops_ai.services.tools.base import ToolGateway, ToolRequest, ToolResult
from hops_ai.services.tools.guarded import GuardedToolGateway
from hops_ai.services.tools.registry import ToolRegistry, ToolSpec

STATUS_TOOL = ToolSpec(
    name="read_service_status",
    description="가상 서비스 상태를 조회한다",
    required=frozenset({"serviceId"}),
    optional=frozenset({"detail"}),
)
OBSERVED_AT = "2026-10-28T00:00:30Z"


def ok_result() -> ToolResult:
    return fake_tool_ok(
        "read_service_status",
        {"state": "DEGRADED"},
        result_id="vpn-gateway-status-0001",
        observed_at=OBSERVED_AT,
    )


def guarded(inner: FakeToolGateway) -> GuardedToolGateway:
    return GuardedToolGateway(
        inner,
        ToolRegistry(STATUS_TOOL),
        ToolPolicy.of({"DIAGNOSIS": ["read_service_status"]}),
    )


def status_request(**arguments) -> ToolRequest:
    return ToolRequest("read_service_status", arguments or {"serviceId": "vpn-gateway"})


# ------------------------------------------------------------------ ToolResult 규칙


def test_ok_result_carries_data_and_a_tool_evidence_with_observation_time() -> None:
    result = ok_result()

    assert result.status == "OK"
    assert result.data == {"state": "DEGRADED"}
    assert result.evidence is not None
    assert result.evidence.kind == "TOOL"
    assert result.evidence.observedAt == OBSERVED_AT


def test_ok_result_without_evidence_is_rejected() -> None:
    with pytest.raises(ValueError):
        ToolResult("OK", "read_service_status", {"state": "UP"}, None, None)


def test_ok_result_with_a_non_tool_evidence_is_rejected() -> None:
    document = Evidence(
        id="doc-1",
        kind="DOCUMENT",
        sourceId="RB-1",
        sourceVersion="v1",
        summary="문서",
        observedAt=None,
    )

    with pytest.raises(ValueError):
        ToolResult.ok("read_service_status", {"state": "UP"}, document)


def test_tool_evidence_without_observation_time_cannot_be_built() -> None:
    with pytest.raises(ValueError):
        Evidence(
            id="tool-1",
            kind="TOOL",
            sourceId="r-1",
            sourceVersion="s-1",
            summary="조회",
            observedAt=None,
        )


@pytest.mark.parametrize("factory", [ToolResult.denied, ToolResult.timeout, ToolResult.error])
def test_failed_results_carry_neither_data_nor_evidence(factory) -> None:
    result = factory("read_service_status", "안내 문구")

    assert result.status != "OK"
    assert result.data is None
    assert result.evidence is None
    assert result.message == "안내 문구"


def test_a_failed_result_cannot_be_dressed_up_as_having_data() -> None:
    with pytest.raises(ValueError):
        ToolResult("ERROR", "read_service_status", {"state": "UP"}, None, "실패")


# ------------------------------------------------------------------ 등록과 인자 규칙


def test_registry_lists_names_and_rejects_duplicates() -> None:
    registry = ToolRegistry(STATUS_TOOL)

    assert registry.names == {"read_service_status"}
    assert registry.get("read_service_status") is STATUS_TOOL
    assert registry.get("restart_service") is None
    with pytest.raises(ValueError):
        ToolRegistry(STATUS_TOOL, STATUS_TOOL)


def test_argument_rules_require_known_and_complete_arguments() -> None:
    assert STATUS_TOOL.argument_problem({"serviceId": "vpn"}) is None
    assert STATUS_TOOL.argument_problem({"serviceId": "vpn", "detail": True}) is None
    assert STATUS_TOOL.argument_problem({}) is not None
    assert STATUS_TOOL.argument_problem({"serviceId": "vpn", "command": "rm -rf"}) is not None


# ------------------------------------------------------------------ 호출 앞단 검사


async def test_valid_call_reaches_the_inner_gateway() -> None:
    inner = FakeToolGateway({"read_service_status": ok_result()})

    result = await guarded(inner).call("DIAGNOSIS", status_request())

    assert result.status == "OK"
    assert len(inner.calls) == 1
    assert inner.calls[0][0] == "DIAGNOSIS"


async def test_unregistered_tool_is_denied_without_calling_the_inner_gateway() -> None:
    inner = FakeToolGateway()

    result = await guarded(inner).call("DIAGNOSIS", ToolRequest("restart_service", {}))

    assert result.status == "DENIED"
    assert inner.calls == []


async def test_tool_not_allowed_for_the_role_is_denied_without_calling_the_inner_gateway() -> None:
    inner = FakeToolGateway({"read_service_status": ok_result()})

    result = await guarded(inner).call("INTAKE", status_request())

    assert result.status == "DENIED"
    assert inner.calls == []


async def test_denial_message_does_not_name_the_tool_or_role_details() -> None:
    result = await guarded(FakeToolGateway()).call("INTAKE", status_request())

    assert "INTAKE" not in (result.message or "")


@pytest.mark.parametrize(
    "arguments",
    [{}, {"serviceId": "vpn", "command": "rm -rf"}],
    ids=["missing-required", "unknown-argument"],
)
async def test_invalid_arguments_are_reported_as_an_error_without_calling_the_inner_gateway(
    arguments,
) -> None:
    inner = FakeToolGateway({"read_service_status": ok_result()})

    result = await guarded(inner).call("DIAGNOSIS", ToolRequest("read_service_status", arguments))

    assert result.status == "ERROR"
    assert inner.calls == []


async def test_denied_result_is_not_worked_around_by_trying_other_tools() -> None:
    inner = FakeToolGateway({"read_service_status": ok_result()})
    gateway = guarded(inner)

    result = await gateway.call("INTAKE", status_request())

    assert result.status == "DENIED"
    assert inner.calls == []  # 다른 도구로 바꿔 부르지 않는다


async def test_inner_denial_and_failures_pass_through_unchanged() -> None:
    inner = FakeToolGateway(
        {"read_service_status": ToolResult.timeout("read_service_status", "조회 시간 초과")}
    )

    result = await guarded(inner).call("DIAGNOSIS", status_request())

    assert result.status == "TIMEOUT"
    assert result.evidence is None


# ------------------------------------------------------------------ Fake 도구


async def test_fake_gateway_returns_scripted_results_and_records_calls() -> None:
    inner = FakeToolGateway({"read_service_status": ok_result()})

    result = await inner.call("DIAGNOSIS", status_request())

    assert result == ok_result()
    assert inner.calls == [("DIAGNOSIS", status_request())]


async def test_fake_gateway_supports_computed_responses() -> None:
    inner = FakeToolGateway(
        {
            "read_service_status": lambda request: fake_tool_ok(
                request.tool_name,
                {"serviceId": request.arguments["serviceId"]},
                result_id="r-1",
                observed_at=OBSERVED_AT,
            )
        }
    )

    result = await inner.call("DIAGNOSIS", status_request(serviceId="mail"))

    assert result.data == {"serviceId": "mail"}


async def test_fake_gateway_reports_an_error_for_unprepared_tools() -> None:
    result = await FakeToolGateway().call("DIAGNOSIS", status_request())

    assert result.status == "ERROR"


def test_gateways_satisfy_the_gateway_interface() -> None:
    inner = FakeToolGateway()

    assert isinstance(inner, ToolGateway)
    assert isinstance(guarded(inner), ToolGateway)
