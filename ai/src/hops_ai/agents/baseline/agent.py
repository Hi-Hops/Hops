"""도구를 쓰는 단일 에이전트. 멀티 에이전트와 같은 서비스·예산·결과 검증으로 비교하는 기준선이다.

LangGraph 하나로 구성한다. LLM은 decide 노드 하나만 쓰고, 나머지 노드는 LLM 없이 실행한다.

    decide(LLM) ──┬─ search     검색 서비스 (접근 범위는 서버가 정함)
                  ├─ diagnose   읽기 도구 (허용 목록·인자 검증은 서버가 함)
                  └─ finalize   결과 조립과 검증
       ↑__________ search·diagnose 결과를 보고 다시 decide (모델 호출 상한 안에서)

문제 분리·분류·부서 선택 같은 판단은 decide의 구조화 출력(final)에 담긴다. finalize는 그 판단을
서버가 가진 사실(근거 ID·부서 코드·질문 횟수)과 대조할 뿐 새로 판단하지 않는다.
도구 허용 목록을 비우고 검색을 끄면 도구 없는 단일 호출 기준선이 된다.
프롬프트는 주입받는다. 실제 프롬프트는 모델을 연결하는 단계에서 넣는다.
"""

from collections.abc import Sequence
from typing import Any

from langchain_core.runnables import RunnableConfig
from langgraph.graph import END, START, StateGraph

from hops_ai.agents.baseline.decision import Decision
from hops_ai.agents.baseline.render import (
    observe_search,
    observe_tool,
    render_actions,
    render_case,
    render_observations,
)
from hops_ai.agents.baseline.state import BaselineState
from hops_ai.contracts.agent_1 import Evidence, KnowledgeMode
from hops_ai.contracts.analysis import AnalysisInput, AnalysisOutcome
from hops_ai.harness.events import RunEvents
from hops_ai.harness.guardrails.outcome import validate_outcome
from hops_ai.services.llm.client import LLMMessage, LLMRequest
from hops_ai.services.retrieval.base import RetrievalFilter, RetrievalQuery
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.base import ToolRequest, ToolResult
from hops_ai.services.tools.registry import ToolSpec

# 도구 권한은 진단 역할의 허용 목록을 따른다. 단일 에이전트는 별도 역할이 아니다.
TOOL_ROLE = "DIAGNOSIS"
ROUTES = {"SEARCH": "search", "DIAGNOSE": "diagnose", "FINISH": "finalize"}


class BaselineAgent:
    def __init__(
        self,
        *,
        system_prompt: str,
        prompt_version: str,
        tool_specs: Sequence[ToolSpec] = (),
        search_enabled: bool = True,
        search_mode: KnowledgeMode = "ANSWER",
        search_top_k: int = 3,
        events: RunEvents | None = None,
    ) -> None:
        self._system_prompt = system_prompt
        self._prompt_version = prompt_version
        self._tool_specs = {spec.name: spec for spec in tool_specs}
        self._search_enabled = search_enabled
        self._search_mode = search_mode  # 서버 설정이다. 모델이 고르지 않는다.
        self._search_top_k = search_top_k
        self._events = events
        self._graph = self._build()

    def with_events(self, events: RunEvents) -> "BaselineAgent":
        """이벤트는 실행마다 다르므로 같은 설정으로 이벤트만 붙인 새 에이전트를 만든다."""
        return BaselineAgent(
            system_prompt=self._system_prompt,
            prompt_version=self._prompt_version,
            tool_specs=list(self._tool_specs.values()),
            search_enabled=self._search_enabled,
            search_mode=self._search_mode,
            search_top_k=self._search_top_k,
            events=events,
        )

    async def analyze(self, data: AnalysisInput, services: SharedServices) -> AnalysisOutcome:
        budget = services.runtime.budget
        final = await self._graph.ainvoke(
            {"data": data, "observations": [], "evidence": [], "performed": []},
            config={
                "configurable": {"services": services},
                # 판단 한 번에 노드 둘(decide + 실행)이 돈다. 비용은 모델 호출 상한이 막는다.
                "recursion_limit": 2 * budget.max_llm_calls + 5,
            },
        )
        return final["outcome"]

    # ------------------------------------------------------------ 그래프 구성

    def _build(self) -> Any:
        graph = StateGraph(BaselineState)
        graph.add_node("decide", self._decide)
        graph.add_node("search", self._search)
        graph.add_node("diagnose", self._diagnose)
        graph.add_node("finalize", self._finalize)
        graph.add_edge(START, "decide")
        graph.add_conditional_edges("decide", lambda state: ROUTES[state["decision"].action])
        graph.add_edge("search", "decide")
        graph.add_edge("diagnose", "decide")
        graph.add_edge("finalize", END)
        return graph.compile()

    # ------------------------------------------------------------ 노드

    async def _decide(self, state: BaselineState, config: RunnableConfig) -> BaselineState:
        services = self._services(config)
        budget = services.runtime.budget
        remaining = budget.max_llm_calls - budget.llm_calls_used
        user_message = "\n\n".join(
            [
                render_case(state["data"]),
                render_actions(
                    search_enabled=self._search_enabled,
                    tools=self._available_tools(services),
                    remaining_calls=remaining,
                ),
                render_observations(state["observations"]),
            ]
        )
        response = await services.llm.generate(
            LLMRequest(
                messages=[
                    LLMMessage(role="system", content=self._system_prompt),
                    LLMMessage(role="user", content=user_message),
                ],
                response_model=Decision,
                max_output_tokens=budget.max_output_tokens,
                prompt_version=self._prompt_version,
            )
        )
        return {"decision": response.parsed}

    async def _search(self, state: BaselineState, config: RunnableConfig) -> BaselineState:
        services = self._services(config)
        query = state["decision"].query or ""
        key = f"SEARCH:{query}"
        if not self._search_enabled:
            return self._note(state, "검색은 사용할 수 없다. 다른 행동을 고르거나 FINISH 한다.")
        if key in state["performed"]:
            return self._note(
                state,
                f'같은 검색 "{query}"을 이미 실행했다. 이전 결과를 쓰거나 다른 행동을 고른다',
            )
        await self._progress("SEARCH", "관련 문서를 검색해요.")
        chunks = await services.retriever.search(
            RetrievalQuery(query, RetrievalFilter.for_mode(self._search_mode), self._search_top_k)
        )
        found = [
            Evidence(
                id=f"doc-{chunk.chunk_id}",
                kind="DOCUMENT",
                sourceId=chunk.artifact_id,
                sourceVersion=chunk.artifact_version,
                summary=chunk.title,
                observedAt=None,
            )
            for chunk in chunks
        ]
        return {
            "observations": [*state["observations"], *observe_search(query, chunks)],
            "evidence": [*state["evidence"], *found],
            "performed": [*state["performed"], key],
        }

    async def _diagnose(self, state: BaselineState, config: RunnableConfig) -> BaselineState:
        services = self._services(config)
        decision = state["decision"]
        name = decision.toolName or ""
        arguments = decision.arguments()
        key = f"DIAGNOSE:{name}:{sorted(arguments.items())}"
        if decision.has_duplicate_arguments():
            return self._note(state, f"도구 {name}의 인자 이름이 중복됐다. 인자를 다시 정한다.")
        if key in state["performed"]:
            return self._note(
                state,
                f"같은 도구 호출 {name}을 이미 실행했다. 이전 결과를 쓰거나 다른 행동을 고른다.",
            )
        await self._progress("DIAGNOSE", "상태를 조회해요.")
        result: ToolResult = await services.tools.call(TOOL_ROLE, ToolRequest(name, arguments))
        evidence = [result.evidence] if result.evidence is not None else []
        return {
            "observations": [*state["observations"], observe_tool(name, result)],
            "evidence": [*state["evidence"], *evidence],
            "performed": [*state["performed"], key],
        }

    async def _finalize(self, state: BaselineState, config: RunnableConfig) -> BaselineState:
        self._services(config)
        data, final = state["data"], state["decision"].final
        assert final is not None
        pool = {evidence.id: evidence for evidence in [*data.evidence, *state["evidence"]]}
        referenced: list[str] = []
        for evidence_id in [
            *(item for problem in final.problems for item in problem.evidenceIds),
            *(item for proposal in final.ticketProposals for item in proposal.evidenceIds),
        ]:
            if evidence_id not in referenced:
                referenced.append(evidence_id)
        outcome = AnalysisOutcome(
            answer=final.answer,
            problems=list(final.problems),
            ticket_proposals=list(final.ticketProposals),
            # 모은 근거 중 결과가 가리킨 것만 돌려준다. 모르는 ID는 빠지고 검증에서 거부된다.
            evidence=[pool[item] for item in referenced if item in pool],
        )
        validate_outcome(data, outcome)
        return {"outcome": outcome}

    # ------------------------------------------------------------ 보조

    @staticmethod
    def _services(config: RunnableConfig) -> SharedServices:
        services = config["configurable"]["services"]
        services.runtime.ensure_active()  # 취소됐거나 마감이 지났으면 단계를 시작하지 않는다
        return services

    def _available_tools(self, services: SharedServices) -> list[ToolSpec]:
        allowed = set(services.runtime.tool_policy.allowed_tools(TOOL_ROLE))
        return [spec for name, spec in self._tool_specs.items() if name in allowed]

    @staticmethod
    def _note(state: BaselineState, text: str) -> BaselineState:
        return {"observations": [*state["observations"], f"[안내] {text}"]}

    async def _progress(self, step_type: str, summary: str) -> None:
        if self._events is not None:
            await self._events.progress(step_type, summary, "RUNNING")
