"""테스트용 역할 조합과 Ticket 모델 응답 도우미."""

from hops_ai.agents.registry import build_runtime_registry
from hops_ai.agents.ticket import ModelTicketAgent
from hops_ai.agents.ticket.proposal import TicketDecision, TicketDecisions
from tests.supervisor_fixtures import SYSTEM


def runtime_registry(knowledge_source=None):
    return dict(build_runtime_registry(knowledge_source))


def fake_ticket_decisions(state) -> TicketDecisions | None:
    """Ticket이 제안할 수 있는 문제마다 보고 시스템 그대로의 결정을 만든다. 없으면 None.

    모델 판단을 흉내 내는 대역이며 실제 모델 품질을 검증하지 않는다. 제목은 문제 요약이다.
    """
    eligible = ModelTicketAgent.eligible_problems(state)
    if not eligible:
        return None
    return TicketDecisions(
        decisions=[
            TicketDecision(
                problemRef=ref,
                title=problem.summary[:200],
                responsibleSystemCandidateId=problem.reportedSystemCandidateId,
                supports=[],
                knowledgeEvidenceIds=[],
            )
            for ref, problem in eligible.items()
        ]
    )


def ticket_decisions(*problem_refs: str) -> TicketDecisions:
    """지정한 문제마다 보고 시스템(SYSTEM) 그대로의 결정을 돌려주는 Fake 모델 응답."""
    return TicketDecisions(
        decisions=[
            TicketDecision(
                problemRef=ref,
                title="담당자 검토 요청",
                responsibleSystemCandidateId=SYSTEM,
                supports=[],
                knowledgeEvidenceIds=[],
            )
            for ref in problem_refs
        ]
    )
