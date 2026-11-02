import pytest

from hops_ai.harness.errors import PolicyDenied
from hops_ai.harness.policy import ToolPolicy


def test_default_policy_denies_every_tool() -> None:
    policy = ToolPolicy()

    for role in ("INQUIRY", "KNOWLEDGE", "TICKET", "CURATOR"):
        assert policy.allowed_tools(role) == []
        with pytest.raises(PolicyDenied):
            policy.authorize(role, "read_service_status")


def test_allowed_tool_passes() -> None:
    policy = ToolPolicy.of({"KNOWLEDGE": ["read_service_status"]})

    policy.authorize("KNOWLEDGE", "read_service_status")


def test_tool_allowed_for_one_role_is_denied_for_the_others() -> None:
    policy = ToolPolicy.of({"KNOWLEDGE": ["read_service_status"]})

    with pytest.raises(PolicyDenied):
        policy.authorize("INQUIRY", "read_service_status")


def test_unlisted_tool_is_denied_even_if_the_role_has_other_tools() -> None:
    policy = ToolPolicy.of({"KNOWLEDGE": ["read_service_status"]})

    with pytest.raises(PolicyDenied):
        policy.authorize("KNOWLEDGE", "restart_service")


def test_allowed_tools_are_sorted_for_the_agent_context() -> None:
    policy = ToolPolicy.of({"KNOWLEDGE": ["read_metrics", "read_logs", "read_service_status"]})

    assert policy.allowed_tools("KNOWLEDGE") == ["read_logs", "read_metrics", "read_service_status"]


def test_denial_does_not_expose_the_tool_name_in_the_contract_message() -> None:
    error = PolicyDenied("KNOWLEDGE 역할은 restart_service 도구를 쓸 수 없다")

    assert "restart_service" not in error.safe_message
