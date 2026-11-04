"""Inquiry·Knowledge 흐름을 Ticket 모델 응답과 독립해서 검사하는 역할 조합."""

from hops_ai.agents.registry import build_runtime_registry
from hops_ai.agents.ticket import RuleTicketAgent
from hops_ai.contracts.domain import AgentRole


def runtime_registry_with_rule_ticket(knowledge_source=None):
    # 제품은 모델 Ticket을 쓰며 해당 경로는 ticket_model/tools 및 연결 검사에서 검증한다.
    # 이 대역은 기존 Inquiry·Knowledge 시나리오의 모델 응답 수와 검증 범위를 유지한다.
    registry = dict(build_runtime_registry(knowledge_source))
    registry[AgentRole.TICKET] = RuleTicketAgent()
    return registry
