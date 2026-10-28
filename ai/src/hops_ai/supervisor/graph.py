"""최상위 LangGraph. 경로와 순서를 조율하고 역할 결과를 조합한다.

Supervisor는 도메인 내용을 쓰지 않고 도구를 직접 쓰지 않는다. 역할은 서로를 직접 부르지 않고
Supervisor만 부른다. 실행 시간과 모델 호출 상한은 서비스 묶음의 예산을 모든 역할이 공유한다.
"""

import uuid
from collections.abc import Callable, Mapping
from typing import Any

from langchain_core.runnables import RunnableConfig
from langgraph.graph import END, START, StateGraph

from hops_ai.agents.base import Agent, execute_safely
from hops_ai.contracts.agent_1 import (
    CONTRACT_VERSION,
    AgentResult,
    AgentRole,
    IntakePayload,
    IntakeRequest,
    IntakeResult,
    TicketPayload,
    TicketRequest,
    TicketResult,
)
from hops_ai.contracts.analysis import AnalysisInput, AnalysisOutcome
from hops_ai.harness.errors import OutputRejected, RoleFailed
from hops_ai.harness.events import RunEvents
from hops_ai.harness.guardrails.outcome import validate_outcome
from hops_ai.services.shared import SharedServices
from hops_ai.supervisor.merge import dedupe_evidence, merge_outcome
from hops_ai.supervisor.router import Router, RuleRouter, problems_for_ticket
from hops_ai.supervisor.state import SupervisorState

# 그래프 단계 수 안전장치다. 모델 호출 비용은 실행 예산(RunBudget)이 막으며 이 값이 대신하지 않는다.
GRAPH_RECURSION_LIMIT = 25

STEP_SUMMARY = {
    "INTAKE": "문의 내용을 확인해요.",
    "TICKET": "담당 부서를 정리해요.",
}


class Supervisor:
    def __init__(
        self,
        agents: Mapping[AgentRole, Agent],
        *,
        router: Router | None = None,
        events: RunEvents | None = None,
        new_id: Callable[[], str] = lambda: str(uuid.uuid4()),
    ) -> None:
        self._agents = dict(agents)
        self._router = router or RuleRouter()
        self._events = events
        self._new_id = new_id
        self._graph = self._build()

    async def analyze(self, data: AnalysisInput, services: SharedServices) -> AnalysisOutcome:
        final = await self._graph.ainvoke(
            {"data": data},
            config={
                "configurable": {"services": services},
                "recursion_limit": GRAPH_RECURSION_LIMIT,
            },
        )
        return final["outcome"]

    # ------------------------------------------------------------ 그래프 구성

    def _build(self) -> Any:
        graph = StateGraph(SupervisorState)
        graph.add_node("intake", self._intake)
        graph.add_node("ticket", self._ticket)
        graph.add_node("merge", self._merge)
        graph.add_edge(START, "intake")
        graph.add_conditional_edges(
            "intake", lambda state: state["route"], {"ticket": "ticket", "merge": "merge"}
        )
        graph.add_edge("ticket", "merge")
        graph.add_edge("merge", END)
        return graph.compile()

    # ------------------------------------------------------------ 노드

    async def _intake(self, state: SupervisorState, config: RunnableConfig) -> SupervisorState:
        services = self._services(config)
        data = state["data"]
        request = IntakeRequest(
            contractVersion=CONTRACT_VERSION,
            runId=data.run_id,
            invocationId=self._new_id(),
            inquiryId=data.inquiry_id,
            inquiryVersion=data.inquiry_version,
            role="INTAKE",
            context=services.runtime.agent_context("INTAKE"),
            payload=IntakePayload(
                messages=data.messages,
                existingProblems=data.existing_problems,
                resolutionTargets=data.resolution_targets,
            ),
        )
        result = await self._run_role("INTAKE", request, services)
        assert isinstance(result, IntakeResult)
        route = await self._router.next_after_intake(result, services)
        return {"intake": result, "route": route}

    async def _ticket(self, state: SupervisorState, config: RunnableConfig) -> SupervisorState:
        services = self._services(config)
        data, intake = state["data"], state["intake"]
        request = TicketRequest(
            contractVersion=CONTRACT_VERSION,
            runId=data.run_id,
            invocationId=self._new_id(),
            inquiryId=data.inquiry_id,
            inquiryVersion=data.inquiry_version,
            role="TICKET",
            context=services.runtime.agent_context("TICKET"),
            payload=TicketPayload(
                problems=problems_for_ticket(intake),
                evidence=dedupe_evidence([*data.evidence, *intake.evidence]),
                diagnoses=[],
                departments=data.departments,
                existingWorkItems=data.existing_work_items,
                csReviewProblemRefs=[],
            ),
        )
        result = await self._run_role("TICKET", request, services)
        assert isinstance(result, TicketResult)
        return {"ticket": result}

    async def _merge(self, state: SupervisorState, config: RunnableConfig) -> SupervisorState:
        self._services(config).runtime.ensure_active()
        outcome = merge_outcome(state["intake"], state.get("ticket"))
        validate_outcome(state["data"], outcome)  # 단일 에이전트와 같은 검증 규칙을 쓴다
        return {"outcome": outcome}

    # ------------------------------------------------------------ 보조

    @staticmethod
    def _services(config: RunnableConfig) -> SharedServices:
        services = config["configurable"]["services"]
        services.runtime.ensure_active()  # 취소됐거나 마감이 지났으면 단계를 시작하지 않는다
        return services

    async def _run_role(
        self, role: AgentRole, request: Any, services: SharedServices
    ) -> AgentResult:
        agent = self._agents.get(role)
        if agent is None:
            raise OutputRejected(f"{role} 역할이 등록되어 있지 않다")
        await self._progress(role, "RUNNING")
        result = await execute_safely(agent, request, services)
        if result.status != "OK":
            assert result.error is not None
            raise RoleFailed(result.status, result.error)
        await self._progress(role, "DONE")
        return result

    async def _progress(self, role: AgentRole, status: str) -> None:
        if self._events is not None:
            await self._events.progress(role, STEP_SUMMARY[role], status)
