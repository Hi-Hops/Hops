"""처리 기록 기반 사례 초안·기존 엔티티 연결 후보를 제안하는 사후 실행 역할.

CuratorAgent는 연결 전 자리이고, 실제 구현은 ModelCuratorAgent다.
기본 registry(build_registry)는 연결 전 자리를 유지하고
실제 역할은 build_runtime_registry가 조립한다.
"""

from hops_ai.agents.base import PendingAgent
from hops_ai.agents.curator.agent import ModelCuratorAgent
from hops_ai.contracts.domain import AgentRole


class CuratorAgent(PendingAgent):
    role = AgentRole.CURATOR


__all__ = ["CuratorAgent", "ModelCuratorAgent"]
