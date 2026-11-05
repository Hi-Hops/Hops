"""순수 모델 제안과 Spring 업무 실행을 분리한다. 모델은 저장 결과를 만들지 않는다."""

from hops_ai.agents.ticket.model import ModelTicketAgent
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.state import RoleRequest, RoleResponse
from hops_ai.harness.errors import ComponentNotReady, OutputRejected
from hops_ai.rules.progress import get_problem_knowledge
from hops_ai.services.shared import SharedServices
from hops_ai.services.tickets import creation_command


class ExecutingTicketAgent:
    role = AgentRole.TICKET

    def __init__(self) -> None:
        # 상태가 없는 제안 역할이므로 한 번만 만들어 선택지 계산과 실행에서 함께 쓴다.
        self._proposer = ModelTicketAgent()

    async def run(self, call: RoleRequest, services: SharedServices):

        tools = services.ticket_tools
        if tools is None:
            raise ComponentNotReady("Spring 티켓 업무 도구")
        proposed = await self._proposer.run(call, services)
        # 한 문제의 잘못된 공개 구간을 나중에 발견해 불필요한 부분 저장을 만들지 않는다.
        commands = [
            creation_command(
                call.state["input"],
                call.state["problems"][p.problemRef].model_copy(
                    update={"processingType": p.processingType}
                ),
                p,
                proposed.messageQuotes.get(p.problemRef, []),
                knowledge_ids=frozenset(get_problem_knowledge(call.state, p.problemRef)),
            )
            for p in proposed.proposals
        ]
        outcomes = [await tools.execute(self.role, command) for command in commands]
        verified = {item.problemRef: item for item in tools.verified_results}
        if any(item.problemRef not in verified for item in outcomes):
            raise OutputRejected("업무 도구가 검증한 티켓 결과가 없다")
        result = proposed.model_copy(update={"creationResults": outcomes}, deep=True)
        return RoleResponse(
            result=result,
            verified_ticket_results=tuple(verified[item.problemRef] for item in outcomes),
        )
