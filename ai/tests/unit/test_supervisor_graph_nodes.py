"""Supervisor 그래프의 노드 구조. 역할이 노드로 보이고 진행 이벤트는 노드 경계에서 나간다."""

from hops_ai.agents.registry import build_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.services.runs.events import SUMMARIES, RunEventHub, RunEvents
from hops_ai.services.runs.supervisor import implemented_roles, selectable_roles
from hops_ai.services.tools.disabled import DisabledToolGateway
from hops_ai.settings import Settings
from hops_ai.supervisor.bootstrap import create_run_services
from hops_ai.supervisor.graph import DECIDE, FINISH, ROLE_NODES, Supervisor
from hops_ai.supervisor.runtime import SupervisorRuntime
from tests.doubles.agents import ticket_decisions
from tests.doubles.llm import FakeLLMClient
from tests.unit.test_knowledge_empty_handoff import how_to_extraction, no_guide_request
from tests.unit.test_knowledge_wiring import decision


def services_with_events(request, llm):
    services = create_run_services(
        request,
        settings=Settings(_env_file=None),
        access_scope_ref="graph-nodes",
        llm=llm,
        tools=DisabledToolGateway(),
    )
    hub = RunEventHub()
    services.runtime.events = RunEvents(request.runId, hub)
    return services, hub


def supervisor() -> Supervisor:
    agents = dict(build_registry(FixtureKnowledgeSource()))
    callable_roles = implemented_roles(agents)
    return Supervisor(
        agents, available_roles=lambda state: selectable_roles(agents, callable_roles, state)
    )


def drain(queue) -> list[tuple[str, str]]:
    events = []
    while not queue.empty():
        event = queue.get_nowait()
        events.append((event.payload.role, event.payload.displaySummary))
    return events


def test_every_role_is_a_node_of_the_graph() -> None:
    request = no_guide_request()
    services, _ = services_with_events(request, FakeLLMClient())
    graph = supervisor()._build_graph(SupervisorRuntime(request, services))

    nodes = set(graph.get_graph().nodes)

    assert {DECIDE, FINISH, *ROLE_NODES.values()} <= nodes
    assert set(ROLE_NODES) == set(AgentRole)


async def test_progress_events_are_sent_at_node_boundaries_with_fixed_summaries() -> None:
    request = no_guide_request()
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.TICKET),
        ticket_decisions("new:1"),
        decision("PROPOSE_TICKETS"),
    )
    services, hub = services_with_events(request, llm)

    with hub.subscribe(request.runId) as queue:
        result = await supervisor().analyze(request, services)
        events = drain(queue)

    assert result.status == "SUCCEEDED", result.error
    roles = [
        "SUPERVISOR",
        "INQUIRY",
        "SUPERVISOR",
        "KNOWLEDGE",
        "SUPERVISOR",
        "TICKET",
        "SUPERVISOR",
    ]
    assert [role for role, _ in events] == roles
    assert all(summary == SUMMARIES[role] for role, summary in events)


async def test_cancelled_run_stops_before_any_role_node_and_ends_superseded() -> None:
    request = no_guide_request()

    class CancellingLLM(FakeLLMClient):
        async def generate(self, request):
            response = await super().generate(request)
            services.runtime.cancel()
            return response

    llm = CancellingLLM(decision("CALL_ROLE", AgentRole.INQUIRY), how_to_extraction())
    services, hub = services_with_events(request, llm)

    with hub.subscribe(request.runId) as queue:
        result = await supervisor().analyze(request, services)
        events = drain(queue)

    assert result.status == "SUPERSEDED"
    assert result.error.code == "SUPERSEDED"
    assert [role for role, _ in events] == ["SUPERVISOR"]
    assert result.questions == result.ticketProposals == result.problemProposals == []


async def test_failure_in_a_role_node_returns_a_safe_failure_without_new_proposals() -> None:
    request = no_guide_request()
    # 두 번째 모델 호출에 준비된 응답이 없어 Inquiry 노드가 실패한다.
    llm = FakeLLMClient(decision("CALL_ROLE", AgentRole.INQUIRY))
    services, hub = services_with_events(request, llm)

    with hub.subscribe(request.runId) as queue:
        result = await supervisor().analyze(request, services)
        events = drain(queue)

    assert result.status == "FAILED"
    assert result.error.code == "SERVICE_UNAVAILABLE"
    assert [role for role, _ in events] == ["SUPERVISOR", "INQUIRY"]
    assert result.answer is None
    assert result.questions == result.ticketProposals == result.problemProposals == []
