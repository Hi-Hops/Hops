"""공통 실행 파이프라인의 역할 연결과 real 모드 기동 조건을 확인한다.

외부 서비스는 호출하지 않는다.
"""

import base64
import json

import pytest

from hops_ai.agents.base import PendingAgent, pending_agents
from hops_ai.agents.registry import build_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import ComponentNotReady
from hops_ai.main import create_app
from hops_ai.services.llm.client import LLMResponse, LLMUsage
from hops_ai.services.runs.supervisor import SupervisorPipeline, implemented_roles
from hops_ai.services.tools.disabled import DisabledToolGateway
from hops_ai.settings import RunMode, Settings
from hops_ai.supervisor.bootstrap import create_run_services
from hops_ai.supervisor.decision import SupervisorDecision
from tests.supervisor_fixtures import make_request


class ImplementedRole:
    def __init__(self, role: AgentRole) -> None:
        self.role = role

    async def run(self, request, services):
        raise AssertionError("이 검사는 역할을 실행하지 않는다")


class RecordingLLM:
    """Supervisor 판단 요청의 허용 역할을 기록하고 종료를 제안한다."""

    def __init__(self) -> None:
        self.allowed_roles: list[list[str]] = []

    async def generate(self, request):
        context = json.loads(request.messages[-1].content)
        self.allowed_roles.append(context["allowedRoles"])
        decision = SupervisorDecision(nextAction="FINISH", role=None, publicSummary="종료합니다.")
        return LLMResponse(parsed=decision, usage=LLMUsage(10, 3), model_id="test-only")


def run_context(request) -> RunContext:
    services = create_run_services(
        request,
        settings=Settings(_env_file=None),
        access_scope_ref=f"test-scope:{request.runId}",
        llm=RecordingLLM(),
        tools=DisabledToolGateway(),
    )
    return services.runtime


def test_pending_roles_are_not_implemented() -> None:
    registry = {
        AgentRole.KNOWLEDGE: PendingAgent(AgentRole.KNOWLEDGE),
        AgentRole.INQUIRY: ImplementedRole(AgentRole.INQUIRY),
    }

    assert implemented_roles(registry) == {AgentRole.INQUIRY}


async def test_supervisor_only_sees_implemented_roles() -> None:
    request = make_request()
    llm = RecordingLLM()
    pipeline = SupervisorPipeline(
        llm,
        {
            AgentRole.INQUIRY: ImplementedRole(AgentRole.INQUIRY),
            AgentRole.KNOWLEDGE: PendingAgent(AgentRole.KNOWLEDGE),
        },
    )

    await pipeline.run(request, run_context(request))

    assert llm.allowed_roles == [["INQUIRY"]]


async def test_registry_without_implemented_roles_ends_safely_without_success() -> None:
    request = make_request()
    llm = RecordingLLM()

    result = await SupervisorPipeline(llm, pending_agents()).run(request, run_context(request))

    assert llm.allowed_roles and all(roles == [] for roles in llm.allowed_roles)
    assert result.status == "FAILED"
    assert result.answer is None
    assert result.problemProposals == result.questions == result.ticketProposals == []


def real_settings(**overrides) -> Settings:
    base = dict(mode=RunMode.REAL, _env_file=None)
    return Settings(**{**base, **overrides})


@pytest.mark.parametrize(
    "overrides",
    [
        {},  # 내부 API 비활성
        {"internal_api_enabled": False, "openai_api_key": "sk-test"},
        {"supervisor_model": "  "},
    ],
)
def test_real_mode_refuses_to_start_without_every_requirement(overrides) -> None:
    with pytest.raises(ComponentNotReady):
        create_app(real_settings(**overrides))


def test_real_mode_refuses_to_start_without_openai_key() -> None:
    settings = real_settings(
        internal_api_enabled=True,
        ai_service_token=base64.urlsafe_b64encode(bytes(range(32))).rstrip(b"=").decode(),
        runtime_dsn="postgresql://u:p@localhost/hops_ai_runtime",
    )

    with pytest.raises(ComponentNotReady):
        create_app(settings)


def test_real_mode_builds_the_app_when_every_requirement_is_set() -> None:
    settings = real_settings(
        internal_api_enabled=True,
        ai_service_token=base64.urlsafe_b64encode(bytes(range(32))).rstrip(b"=").decode(),
        runtime_dsn="postgresql://u:p@localhost/hops_ai_runtime",
        openai_api_key="sk-test",
        spring_base_url="http://spring:8080",
        spring_service_token="A" * 43,
    )

    app = create_app(settings)

    assert app.title == "Hops AI"


def test_default_pipeline_registry_is_the_built_registry() -> None:
    assert implemented_roles(build_registry()) == {
        AgentRole.INQUIRY,
        AgentRole.TICKET,
        AgentRole.CURATOR,
    }
