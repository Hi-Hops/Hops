"""권한 범위의 Vector·Neo4j 검색과 조건부 분석을 다룰 자리. 원인 확정은 하지 않는다."""

from hops_ai.agents.base import PendingAgent
from hops_ai.contracts.domain import AgentRole


class KnowledgeAgent(PendingAgent):
    role = AgentRole.KNOWLEDGE
