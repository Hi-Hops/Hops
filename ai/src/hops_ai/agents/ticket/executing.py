"""순수 모델 제안과 Spring 업무 실행을 분리한다. 모델은 저장 결과를 만들지 않는다."""

from typing import TYPE_CHECKING

from hops_ai.agents.ticket.model import ModelTicketAgent
from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.errors import ComponentNotReady, OutputRejected
from hops_ai.services.shared import SharedServices
from hops_ai.services.tickets import creation_command

if TYPE_CHECKING:
    from hops_ai.supervisor.execution import RoleRequest
    from hops_ai.supervisor.state import SupervisorState


class ExecutingTicketAgent:
    role = AgentRole.TICKET

    def available_for(self, state: "SupervisorState") -> bool:
        return ModelTicketAgent().available_for(state)

    async def run(self, call: "RoleRequest", services: SharedServices):
        from hops_ai.supervisor.execution import RoleResponse

        tools = services.ticket_tools
        if tools is None:
            raise ComponentNotReady("Spring 티켓 업무 도구")
        proposed = await ModelTicketAgent().run(call, services)
        # 한 문제의 잘못된 공개 구간을 나중에 발견해 불필요한 부분 저장을 만들지 않는다.
        commands = [
            creation_command(
                call.state["input"],
                call.state["problems"][p.problemRef].model_copy(
                    update={"processingType": p.processingType}
                ),
                p,
                proposed.messageQuotes.get(p.problemRef, []),
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
