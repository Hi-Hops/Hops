"""실행 관리자가 발급한 문맥으로 공통 Supervisor를 실행하는 실행 파이프라인.

역할 조합은 생성자로 주입한다. 기본은 registry의 역할이다. 아직 구현되지 않은 역할(PendingAgent)과
지금 State에서 호출이 성립하지 않는 역할은 Supervisor가 고르지 못하게 제한한다
(supervisor.choices). 구현된 역할이 하나도 없으면 실행은 근거 없는 성공 대신
안전한 실패로 끝난다. 역할 담당자는 registry의 자기 역할만 구현하면
이 파이프라인을 바꾸지 않고 연결된다.
"""

from collections.abc import Mapping

from hops_ai.agents.base import Agent
from hops_ai.agents.registry import build_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiResult, AiRunRequest
from hops_ai.harness.context import RunContext
from hops_ai.harness.policy import ToolPolicy
from hops_ai.services.llm.client import LLMClient
from hops_ai.services.retrieval.knowledge_source import KNOWLEDGE_TOOLS, KnowledgeSource
from hops_ai.services.shared import SharedServices
from hops_ai.services.tickets import TICKET_TOOLS, TicketHTTPClient
from hops_ai.services.tools.base import ToolGateway
from hops_ai.services.tools.disabled import DisabledToolGateway
from hops_ai.services.tracing import RunTracing, TracedLLMClient
from hops_ai.supervisor.graph import Supervisor


class SupervisorPipeline:
    def __init__(
        self,
        llm: LLMClient,
        agents: Mapping[AgentRole, Agent] | None = None,
        *,
        tools: ToolGateway | None = None,
        knowledge_source: KnowledgeSource | None = None,
        tracing: RunTracing | None = None,
        ticket_client: TicketHTTPClient | None = None,
    ) -> None:
        """llm은 원시 클라이언트다. 실행별 누적 예산 wrapper는 Supervisor가 적용한다.

        도구를 주지 않으면 거부 게이트웨이를 쓴다.
        knowledge_source는 기본 역할 조합(agents를 주지 않은 경우)에서만 Knowledge를 연결하며,
        없으면 Knowledge는 연결 전 자리로 남는다.
        """
        if agents is not None and ticket_client is not None:
            # 주입한 역할 조합의 TICKET을 몰래 바꾸지 않는다. 호출 측이 직접 넣는다.
            raise ValueError("agents와 ticket_client를 함께 줄 수 없다")
        registry = dict(
            build_registry(knowledge_source, execute_tickets=ticket_client is not None)
            if agents is None
            else agents
        )
        self._ticket_client = ticket_client
        self._knowledge_connected = agents is None and knowledge_source is not None
        self._tracing = tracing or RunTracing()
        self._llm = TracedLLMClient(llm, self._tracing) if self._tracing.client else llm
        self._tools = tools or DisabledToolGateway()
        self._supervisor = Supervisor(registry, restrict_roles=True)

    async def run(self, request: AiRunRequest, context: RunContext) -> AiResult:
        allowed = dict(context.tool_policy.allowed_by_role)
        if self._ticket_client is not None:
            allowed[AgentRole.TICKET] = TICKET_TOOLS
        if self._knowledge_connected:
            # Spring 내부 읽기 API 호출은 Knowledge 역할만 하며 실행 전체의 toolCalls를 소비한다.
            allowed[AgentRole.KNOWLEDGE] = KNOWLEDGE_TOOLS
        if allowed != context.tool_policy.allowed_by_role:
            context.tool_policy = ToolPolicy.of(allowed)
        ticket_tools = self._ticket_client.bind(request, context) if self._ticket_client else None
        with self._tracing.observe(
            "hops.ai.run",
            {"kind": request.kind, "messageCount": len(request.messages)},
        ) as span:
            result = await self._supervisor.analyze(
                request,
                SharedServices(
                    runtime=context,
                    llm=self._llm,
                    tools=self._tools,
                    tracing=self._tracing,
                    ticket_tools=ticket_tools,
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
