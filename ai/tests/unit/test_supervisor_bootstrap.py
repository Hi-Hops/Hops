"""외부 연결 없이 요청별 의존성 구성을 검사한다. 실제 권한·모델 품질 검증은 별도다."""

from datetime import UTC, datetime, timedelta

import pytest
from pydantic import ValidationError

from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.errors import DeadlineExceeded, InputRejected, PolicyDenied
from hops_ai.harness.policy import ToolPolicy
from hops_ai.services.tools.guarded import GuardedToolGateway
from hops_ai.services.tools.registry import ToolRegistry
from hops_ai.supervisor.bootstrap import create_run_services
from tests.doubles.llm import FakeLLMClient
from tests.doubles.tools import FakeToolGateway
from tests.supervisor_fixtures import (
    make_request,
    uid,
)


class UnusedRetrieval:
    """구성 시 검색을 실행하면 즉시 실패하는 명시적 대역."""

    async def search(self, query):
        raise AssertionError("서비스 구성 중 검색해서는 안 된다")


def dependencies():
    """모든 의존성을 호출자가 직접 지정한다. 서버 기본 대역으로 사용하지 않는다."""
    return {
        "llm": FakeLLMClient(),
        "vector": UnusedRetrieval(),
        "graph": UnusedRetrieval(),
        "tools": GuardedToolGateway(FakeToolGateway(), ToolRegistry(), ToolPolicy()),
    }


def test_factory_preserves_injected_clients_and_request_identity(make_settings):
    request = make_request()
    supplied = dependencies()
    services = create_run_services(
        request,
        settings=make_settings(),
        access_scope_ref="server-owned-scope-ref",
        **supplied,
    )
    assert services.runtime.run_id == request.runId
    assert services.runtime.inquiry_id == request.inquiryId
    assert services.runtime.inquiry_version == request.inquiryVersion
    assert services.runtime.access_scope_ref == "server-owned-scope-ref"
    assert all(getattr(services, name) is client for name, client in supplied.items())
    assert supplied["llm"].requests == []


def test_each_call_creates_independent_budget_and_cancellation(make_settings):
    request = make_request()
    arguments = {
        "settings": make_settings(),
        "access_scope_ref": "server-owned-scope-ref",
        **dependencies(),
    }
    first = create_run_services(request, **arguments)
    second = create_run_services(request, **arguments)
    first.runtime.budget.start_llm_call()
    first.runtime.cancel()
    assert first.runtime is not second.runtime
    assert first.runtime.budget is not second.runtime.budget
    assert first.runtime.is_cancelled
    assert not second.runtime.is_cancelled
    assert second.runtime.budget.llm_calls_used == 0


def test_request_deadline_is_used_when_earlier_than_server(make_settings):
    expires = datetime.now(UTC) + timedelta(seconds=5)
    request = make_request().model_copy(
        update={"expiresAt": expires.isoformat().replace("+00:00", "Z")}
    )
    services = create_run_services(
        request, settings=make_settings(), access_scope_ref="server-ref", **dependencies()
    )
    effective = services.runtime.budget.effective_deadline_at()
    assert abs((effective - expires).total_seconds()) < 0.1


def test_server_deadline_is_used_when_earlier_than_request(make_settings):
    services = create_run_services(
        make_request(),
        settings=make_settings(run_deadline_seconds=3, llm_call_timeout_seconds=2),
        access_scope_ref="server-ref",
        **dependencies(),
    )
    assert 2 < services.runtime.budget.remaining_seconds() <= 3


def test_expired_request_stays_expired_without_starting_model(make_settings):
    expires = datetime.now(UTC) - timedelta(seconds=1)
    request = make_request().model_copy(
        update={"expiresAt": expires.isoformat().replace("+00:00", "Z")}
    )
    supplied = dependencies()
    services = create_run_services(
        request, settings=make_settings(), access_scope_ref="server-ref", **supplied
    )
    with pytest.raises(DeadlineExceeded):
        services.runtime.ensure_active()
    assert supplied["llm"].requests == []


@pytest.mark.parametrize("reference", ["", " \t\n", None, 12])
def test_missing_scope_reference_is_rejected(make_settings, reference):
    with pytest.raises(InputRejected):
        create_run_services(
            make_request(),
            settings=make_settings(),
            access_scope_ref=reference,
            **dependencies(),
        )


def test_plain_dictionary_is_not_accepted_as_validated_request(make_settings):
    with pytest.raises(InputRejected):
        create_run_services(
            make_request().model_dump(mode="json"),
            settings=make_settings(),
            access_scope_ref="server-ref",
            **dependencies(),
        )


def test_request_model_copy_cannot_bypass_wire_validation(make_settings):
    request = make_request().model_copy(update={"inquiryVersion": 0})
    with pytest.raises(ValidationError):
        create_run_services(
            request, settings=make_settings(), access_scope_ref="server-ref", **dependencies()
        )


def test_unknown_problem_reference_is_rejected_before_execution(make_settings):
    request = make_request().model_copy(update={"problemIds": [uid(999)]})
    with pytest.raises(InputRejected):
        create_run_services(
            request, settings=make_settings(), access_scope_ref="server-ref", **dependencies()
        )


def test_default_tool_policy_denies_every_role(make_settings):
    services = create_run_services(
        make_request(), settings=make_settings(), access_scope_ref="server-ref", **dependencies()
    )
    for role in AgentRole:
        with pytest.raises(PolicyDenied):
            services.runtime.tool_policy.authorize(role, "read_status")


def test_explicit_tool_policy_is_preserved(make_settings):
    policy = ToolPolicy.of({AgentRole.KNOWLEDGE: {"read_status"}})
    services = create_run_services(
        make_request(),
        settings=make_settings(),
        access_scope_ref="server-ref",
        tool_policy=policy,
        **dependencies(),
    )
    assert services.runtime.tool_policy is policy
    services.runtime.tool_policy.authorize(AgentRole.KNOWLEDGE, "read_status")
    with pytest.raises(PolicyDenied):
        services.runtime.tool_policy.authorize(AgentRole.INQUIRY, "read_status")
