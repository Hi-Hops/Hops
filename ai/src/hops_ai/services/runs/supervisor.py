"""실행 관리자가 발급한 문맥으로 공통 Supervisor를 실행하는 실행 파이프라인.

역할 조합은 생성자로 주입한다. 기본은 registry의 역할이며, 아직 구현되지 않은 역할(PendingAgent)은
Supervisor가 고르지 못하게 제한한다. 구현된 역할이 하나도 없으면 실행은 근거 없는 성공 대신
안전한 실패로 끝난다. 역할 담당자는 registry의 자기 역할만 구현하면
이 파이프라인을 바꾸지 않고 연결된다.
"""

from collections.abc import Mapping

from hops_ai.agents.base import Agent, PendingAgent
from hops_ai.agents.registry import build_runtime_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiResult, AiRunRequest
from hops_ai.harness.context import RunContext
from hops_ai.services.llm.client import LLMClient
from hops_ai.services.retrieval.base import GraphRetriever, VectorRetriever
from hops_ai.services.retrieval.knowledge_source import KnowledgeSource
from hops_ai.services.retrieval.neo4j import Neo4jRetriever
from hops_ai.services.retrieval.pgvector import PgVectorRetriever
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.base import ToolGateway
from hops_ai.services.tools.disabled import DisabledToolGateway
from hops_ai.services.tracing import RunTracing, TracedLLMClient
from hops_ai.supervisor.graph import Supervisor
from hops_ai.supervisor.state import SupervisorState


def implemented_roles(agents: Mapping[AgentRole, Agent]) -> set[AgentRole]:
    """아직 연결하지 않은 자리(PendingAgent)를 뺀, 실제로 호출해도 되는 역할."""
    return {role for role, agent in agents.items() if not isinstance(agent, PendingAgent)}


def selectable_roles(
    agents: Mapping[AgentRole, Agent], callable_roles: set[AgentRole], state: SupervisorState
) -> set[AgentRole]:
    """지금 State에서 호출이 성립하는 역할만 남긴다.

    역할이 `available_for(state)`를 제공하면 그 값이 거짓일 때 선택지에서 뺀다. 호출해 봐야
    거부될 역할(예: 문제가 아직 없는데 검색)을 모델이 고르다 실행이 실패하는 것을 막는 사실
    판단이며, 호출이 성립하는 역할 사이의 선택은 제한하지 않는다.
    """
    roles = set(callable_roles)
    for role in callable_roles:
        check = getattr(agents.get(role), "available_for", None)
        if callable(check) and not check(state):
            roles.discard(role)
    return roles


class SupervisorPipeline:
    def __init__(
        self,
        llm: LLMClient,
        agents: Mapping[AgentRole, Agent] | None = None,
        *,
        vector: VectorRetriever | None = None,
        graph: GraphRetriever | None = None,
        tools: ToolGateway | None = None,
        knowledge_source: KnowledgeSource | None = None,
        tracing: RunTracing | None = None,
    ) -> None:
        """llm은 원시 클라이언트다. 실행별 누적 예산 wrapper는 Supervisor가 적용한다.

        검색·도구를 주지 않으면 연결 전 자리(ComponentNotReady)와 거부 게이트웨이를 쓴다.
        knowledge_source는 기본 역할 조합(agents를 주지 않은 경우)에서만 Knowledge를 연결한다.
        """
        registry = dict(build_runtime_registry(knowledge_source) if agents is None else agents)
        self._tracing = tracing or RunTracing()
        self._llm = TracedLLMClient(llm, self._tracing) if self._tracing.client else llm
        self._vector = vector or PgVectorRetriever()
        self._graph = graph or Neo4jRetriever()
        self._tools = tools or DisabledToolGateway()
        callable_roles = implemented_roles(registry)
        self._supervisor = Supervisor(
            registry,
            available_roles=lambda state: selectable_roles(registry, callable_roles, state),
        )

    async def run(self, request: AiRunRequest, context: RunContext) -> AiResult:
        with self._tracing.observe(
            "hops.ai.run",
            {"kind": request.kind, "messageCount": len(request.messages)},
        ) as span:
            result = await self._supervisor.analyze(
                request,
                SharedServices(
                    runtime=context,
                    llm=self._llm,
                    vector=self._vector,
                    graph=self._graph,
                    tools=self._tools,
                    tracing=self._tracing,
                ),
            )
            if span is not None:
                output = {
                    "status": result.status,
                    "questionCount": len(result.questions),
                    "ticketProposalCount": len(result.ticketProposals),
                    "errorCode": result.error.code if result.error else None,
                }
                if self._tracing.capture_content:
                    output["answer"] = result.answer
                span.update(output=output)
            return result
