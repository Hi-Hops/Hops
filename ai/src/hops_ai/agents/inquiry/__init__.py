"""대화·독립 문제·의도·확인/미확인 정보·추가 질문과 표준 요청을 다루는 역할.

InquiryAgent는 연결 전 자리이고, 실제 구현은 ExtractionInquiryAgent다.
기본 registry(build_registry)는 연결 전 자리를 유지하고
실제 역할은 build_runtime_registry가 조립한다.
"""

from hops_ai.agents.base import PendingAgent
from hops_ai.agents.inquiry.agent import ExtractionInquiryAgent
from hops_ai.contracts.domain import AgentRole


class InquiryAgent(PendingAgent):
    role = AgentRole.INQUIRY


__all__ = ["ExtractionInquiryAgent", "InquiryAgent"]
