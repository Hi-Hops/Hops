"""새 역할 골격만 등록한다. Fake·단일 모드·업무 성공 결과로 대체하지 않는다."""

from collections.abc import Mapping
from types import MappingProxyType

from hops_ai.agents.base import Agent
from hops_ai.agents.curator import CuratorAgent
from hops_ai.agents.inquiry import ExtractionInquiryAgent, InquiryAgent
from hops_ai.agents.knowledge import KnowledgeAgent
from hops_ai.agents.ticket import RuleTicketAgent, TicketAgent
from hops_ai.contracts.domain import AgentRole


def build_registry() -> Mapping[AgentRole, Agent]:
    return MappingProxyType(
        {
            AgentRole.INQUIRY: InquiryAgent(),
            AgentRole.KNOWLEDGE: KnowledgeAgent(),
            AgentRole.TICKET: TicketAgent(),
            AgentRole.CURATOR: CuratorAgent(),
        }
    )


def build_runtime_registry() -> Mapping[AgentRole, Agent]:
    """서버 실행에 쓰는 역할 조합. 구현이 끝난 역할만 실제 구현으로 바꾼다.

    아직 구현하지 않은 역할은 build_registry의 연결 전 자리(PendingAgent)로 남기며,
    SupervisorPipeline이 그런 역할은 Supervisor가 고르지 못하게 제한한다.
    역할 담당자는 자기 역할의 구현을 여기에 등록한다.
    """
    registry = dict(build_registry())
    registry[AgentRole.INQUIRY] = ExtractionInquiryAgent()
    registry[AgentRole.TICKET] = RuleTicketAgent()
    return MappingProxyType(registry)
