"""서버 실행에 쓰는 역할 조합을 한 곳에서 만든다. Fake나 업무 성공 결과로 대체하지 않는다."""

from collections.abc import Mapping
from types import MappingProxyType

from hops_ai.agents.base import Agent, PendingAgent
from hops_ai.agents.curator import ModelCuratorAgent
from hops_ai.agents.inquiry import ExtractionInquiryAgent
from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.agents.ticket import ModelTicketAgent
from hops_ai.agents.ticket.executing import ExecutingTicketAgent
from hops_ai.contracts.domain import AgentRole
from hops_ai.services.retrieval.knowledge_source import KnowledgeSource


def build_registry(
    knowledge_source: KnowledgeSource | None = None,
    *,
    execute_tickets: bool = False,
    assess_evidence: bool = False,
    defer_questions: bool = False,
) -> Mapping[AgentRole, Agent]:
    """서버 실행에 쓰는 역할 조합.

    knowledge_source를 주면 Knowledge를 연결하고, Inquiry도 사용법 문의에서 검색에 필요한
    정보만 확보하면 추가 질문 없이 Knowledge로 넘기도록 조립한다. 주지 않으면 Knowledge는
    연결 전 자리(PendingAgent)로 남고 Inquiry는 기존 양식 질문을 그대로 한다.
    SupervisorPipeline이 연결 전 자리는 Supervisor가 고르지 못하게 제한한다.

    Ticket은 execute_tickets=False면 제안만 만드는 ModelTicketAgent, True면 Spring 업무 도구로
    생성·확인까지 수행하는 ExecutingTicketAgent다. 어느 쪽인지 호출 측이 명시한다.

    assess_evidence가 참이면 Knowledge가 검색 뒤에 근거의 충분성 판정을 만든다(문제당 모델 1회).
    판정을 읽는 쪽(Supervisor의 행동 선택)이 연결되기 전까지는 기본 거짓이다.

    defer_questions가 참이면 Inquiry가 분석에서 질문을 만들지 않는다(문제별 행동 경로는 질문을
    별도 행동으로 만든다). 기본은 거짓이라 분석이 질문까지 만든다.
    """
    knowledge: Agent = (
        PendingAgent(AgentRole.KNOWLEDGE)
        if knowledge_source is None
        else SearchKnowledgeAgent(knowledge_source, assess_evidence=assess_evidence)
    )
    return MappingProxyType(
        {
            AgentRole.INQUIRY: ExtractionInquiryAgent(
                guide_first=knowledge_source is not None, defer_questions=defer_questions
            ),
            AgentRole.KNOWLEDGE: knowledge,
            AgentRole.TICKET: ExecutingTicketAgent() if execute_tickets else ModelTicketAgent(),
            AgentRole.CURATOR: ModelCuratorAgent(),
        }
    )
