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
from hops_ai.services.retrieval.neo4j import Neo4jRetriever
from hops_ai.services.retrieval.pgvector import PgVectorRetriever
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.base import ToolGateway
from hops_ai.services.tools.disabled import DisabledToolGateway
from hops_ai.supervisor.graph import Supervisor


def implemented_roles(agents: Mapping[AgentRole, Agent]) -> set[AgentRole]:
    """아직 연결하지 않은 자리(PendingAgent)를 뺀, 실제로 호출해도 되는 역할."""
    return {role for role, agent in agents.items() if not isinstance(agent, PendingAgent)}


class SupervisorPipeline:
    def __init__(
        self,
        llm: LLMClient,
        agents: Mapping[AgentRole, Agent] | None = None,
        *,
        vector: VectorRetriever | None = None,
        graph: GraphRetriever | None = None,
        tools: ToolGateway | None = None,
    ) -> None:
        """llm은 원시 클라이언트다. 실행별 누적 예산 wrapper는 Supervisor가 적용한다.

        검색·도구를 주지 않으면 연결 전 자리(ComponentNotReady)와 거부 게이트웨이를 쓴다.
        """
        registry = dict(build_runtime_registry() if agents is None else agents)
        self._llm = llm
        self._vector = vector or PgVectorRetriever()
        self._graph = graph or Neo4jRetriever()
        self._tools = tools or DisabledToolGateway()
        callable_roles = implemented_roles(registry)
        self._supervisor = Supervisor(registry, available_roles=lambda _state: set(callable_roles))

    async def run(self, request: AiRunRequest, context: RunContext) -> AiResult:
        return await self._supervisor.analyze(
            request,
            SharedServices(
                runtime=context,
                llm=self._llm,
                vector=self._vector,
                graph=self._graph,
                tools=self._tools,
            ),
        )
