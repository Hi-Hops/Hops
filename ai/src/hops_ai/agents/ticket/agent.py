"""Ticket 역할: 질문·안내가 필요 없는 문제를 담당자 티켓 제안으로 만든다.

제안 내용은 Inquiry가 원문 근거로 확인한 값을 그대로 옮긴다. 확인 사실·미확인 항목·상세
필드를 줄이거나 추정으로 채우지 않으므로 모델 호출이 필요 없다. 책임 시스템 후보는 서버가
확인한 보고 시스템 그대로이며, 시스템을 모르는 문제는 임의 담당자에게 배정하지 않고
제안하지 않는다(시스템 확인 대기는 Supervisor가 처리한다). 저장·배정은 제안이 아니라
Spring이 확정한다. 이 제안만으로 티켓이 만들어졌다고 말하지 않는다.

책임 시스템을 보고 시스템과 다르게 제안하는 판단(의존 관계, 과거 사례 근거)은 이 구현의
범위가 아니다. 그런 개선은 근거 ID와 함께 responsibleSystemCandidateId를 바꿔 이 역할
안에서 확장한다.
"""

from typing import TYPE_CHECKING

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import TicketResult
from hops_ai.contracts.wire import ProblemProposal, StandardRequestBody, TicketProposal
from hops_ai.harness.errors import OutputRejected
from hops_ai.services.shared import SharedServices

if TYPE_CHECKING:
    # supervisor 패키지가 registry를 통해 이 모듈을 다시 불러오므로 타입 검사 때만 가져온다.
    from hops_ai.supervisor.execution import RoleRequest

# 담당자가 처리하는 유형. 사용법(KNOWLEDGE_QA)은 안내로, 범위 밖은 범위 안내로 끝난다.
TICKETABLE_TYPES = frozenset(
    {"SERVICE_REQUEST", "INVESTIGATION", "CHANGE_REQUEST", "REVIEW_REQUIRED"}
)
MAX_TITLE_LENGTH = 200


class RuleTicketAgent:
    role = AgentRole.TICKET

    async def run(self, call: "RoleRequest", services: SharedServices) -> TicketResult:
        services.runtime.ensure_active()
        # supervisor 패키지는 이 모듈을 불러오는 registry를 거쳐 초기화되므로 호출 시점에 가져온다.
        from hops_ai.supervisor.validation import get_problem_progress, knowledge_found_nothing

        state = call.state
        pending = {
            item["problemRef"] for item in get_problem_progress(state) if item["outcome"] is None
        }
        # 사용법 문제는 가이드를 찾지 못한 경우(오류 아님)에만 담당자 검토로 넘길 수 있다.
        review_handoff = knowledge_found_nothing(state)
        proposals = [
            _propose(problem)
            for ref, problem in state["problems"].items()
            if ref in pending
            and problem.reportedSystemCandidateId is not None
            and (
                problem.processingType in TICKETABLE_TYPES
                or (problem.processingType == "KNOWLEDGE_QA" and review_handoff)
            )
        ]
        if not proposals:
            # 빈 결과로 성공 처리하면 Supervisor가 같은 호출을 반복한다.
            # 제안할 대상이 없다고 알린다.
            raise OutputRejected("티켓으로 제안할 수 있는 문제가 없다")
        return TicketResult(invocationId=call.invocation_id, proposals=proposals)


def _propose(problem: ProblemProposal) -> TicketProposal:
    system_id = problem.reportedSystemCandidateId
    # 안내로 끝나는 사용법은 티켓 유형이 없으므로 담당자 검토 유형으로 넘긴다.
    processing_type = (
        "REVIEW_REQUIRED" if problem.processingType == "KNOWLEDGE_QA" else problem.processingType
    )
    return TicketProposal(
        problemRef=problem.problemRef,
        title=problem.summary[:MAX_TITLE_LENGTH],
        processingType=processing_type,
        responsibleSystemCandidateId=system_id,
        evidenceIds=[],
        standardRequestSnapshot=StandardRequestBody(
            summary=problem.summary,
            confirmedFacts=problem.facts,
            assumptions=problem.assumptions,
            unknowns=problem.unknowns,
            responsibleSystemCandidateId=system_id,
            responsibilityEvidenceIds=[],
            details=problem.details,
            knowledge=[],
        ),
    )
