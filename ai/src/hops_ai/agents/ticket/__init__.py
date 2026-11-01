"""문제별 표준 요청·책임 시스템 후보·근거를 제안할 자리. 저장·배정 확정은 Spring이 한다."""

from hops_ai.agents.base import PendingAgent
from hops_ai.contracts.domain import AgentRole


class TicketAgent(PendingAgent):
    role = AgentRole.TICKET
