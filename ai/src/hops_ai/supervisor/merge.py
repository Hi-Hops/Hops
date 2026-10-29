"""역할 결과를 하나의 분석 결과로 조합한다. 내부 nextAction을 외부로 그대로 보내지 않는다."""

from collections.abc import Iterable

from hops_ai.contracts.agent_1 import Evidence, IntakeResult, TicketResult
from hops_ai.contracts.analysis import AnalysisOutcome
from hops_ai.harness.errors import OutputRejected


def dedupe_evidence(items: Iterable[Evidence]) -> list[Evidence]:
    """같은 id의 근거는 처음 것만 남긴다. 순서는 유지한다."""
    seen: dict[str, Evidence] = {}
    for item in items:
        seen.setdefault(item.id, item)
    return list(seen.values())


def merge_outcome(intake: IntakeResult, ticket: TicketResult | None) -> AnalysisOutcome:
    assert intake.data is not None
    proposals = []
    evidence = list(intake.evidence)
    if ticket is not None:
        assert ticket.data is not None
        if ticket.data.unresolvedProblemRefs:
            # 제안을 만들지 못한 문제를 성공처럼 숨기지 않는다. 서비스 간 계약에는 담을 필드가 없다.
            raise OutputRejected(
                f"티켓 제안을 만들지 못한 문제: {ticket.data.unresolvedProblemRefs}"
            )
        proposals = list(ticket.data.ticketProposals)
        evidence += ticket.evidence
    return AnalysisOutcome(
        answer=intake.data.replyDraft,
        problems=list(intake.data.problems),
        ticket_proposals=proposals,
        evidence=dedupe_evidence(evidence),
    )
