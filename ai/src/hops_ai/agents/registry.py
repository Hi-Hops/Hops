"""새 역할 골격만 등록한다. Fake·단일 모드·업무 성공 결과로 대체하지 않는다."""

from collections.abc import Mapping
from types import MappingProxyType

from hops_ai.agents.base import Agent
from hops_ai.agents.curator import CuratorAgent
from hops_ai.agents.inquiry import InquiryAgent
from hops_ai.agents.knowledge import KnowledgeAgent
from hops_ai.agents.ticket import TicketAgent
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
