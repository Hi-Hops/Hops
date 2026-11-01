"""처리 기록 기반 사례 초안·중복·기존 엔티티 연결 후보를 제안할 사후 실행 자리."""

from hops_ai.agents.base import PendingAgent
from hops_ai.contracts.domain import AgentRole


class CuratorAgent(PendingAgent):
    role = AgentRole.CURATOR
