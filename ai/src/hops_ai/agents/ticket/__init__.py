"""문제별 표준 요청·책임 시스템 후보·근거를 제안하는 역할. 저장·배정 확정은 Spring이 한다.

TicketAgent는 연결 전 자리이고, 실제 구현은 ModelTicketAgent다.
기본 registry(build_registry)는 연결 전 자리를 유지하고
실제 역할은 build_runtime_registry가 조립한다.
"""

from hops_ai.agents.base import PendingAgent
from hops_ai.agents.ticket.model import ModelTicketAgent
from hops_ai.contracts.domain import AgentRole


class TicketAgent(PendingAgent):
    role = AgentRole.TICKET


__all__ = ["ModelTicketAgent", "TicketAgent"]
