"""대화·독립 문제·의도·확인/미확인 정보·추가 질문과 표준 요청을 다룰 자리."""

from hops_ai.agents.base import PendingAgent
from hops_ai.contracts.domain import AgentRole


class InquiryAgent(PendingAgent):
    role = AgentRole.INQUIRY
