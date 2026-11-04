"""권한 범위의 지식 검색과 직원 안내 제안을 다루는 역할. 원인 확정은 하지 않는다.

KnowledgeAgent는 연결 전 자리이고, 실제 구현은 SearchKnowledgeAgent다.
기본 registry(build_registry)는 연결 전 자리를 유지하고
실제 역할은 build_runtime_registry가 조립한다.
"""

from hops_ai.agents.base import PendingAgent
from hops_ai.agents.knowledge.agent import SearchKnowledgeAgent
from hops_ai.contracts.domain import AgentRole


class KnowledgeAgent(PendingAgent):
    role = AgentRole.KNOWLEDGE


__all__ = ["KnowledgeAgent", "SearchKnowledgeAgent"]
