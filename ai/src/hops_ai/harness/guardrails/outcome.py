"""분석 결과의 결정론적 검증. 단일 에이전트와 Supervisor가 같은 규칙을 쓴다.

모델이 만든 값(근거 ID·부서 코드·문제 참조·질문 횟수)을 서버가 가진 사실과 대조한다. 모델의 말만으로
결과를 믿지 않으며, 어긋나면 OutputRejected로 알린다. 실제 저장·배정은 Spring이 다시 검증한다.
"""

from hops_ai.contracts.analysis import AnalysisInput, AnalysisOutcome
from hops_ai.contracts.types import QUESTION_LIMIT
from hops_ai.harness.errors import OutputRejected

# 분석이 끝난 결과에 남을 수 있는 처리 경로. SEARCH·DIAGNOSE는 진행 중에만 쓰는 내부 경로다.
FINAL_ACTIONS = {"ASK_USER", "PROPOSE_TICKETS", "OUT_OF_SCOPE"}


def validate_outcome(data: AnalysisInput, outcome: AnalysisOutcome) -> None:
    refs = [problem.ref for problem in outcome.problems]
    if len(set(refs)) != len(refs):
        raise OutputRejected("문제 ref가 중복된다")

    existing = {problem.ref: problem for problem in data.existing_problems}
    for problem in outcome.problems:
        if problem.nextAction not in FINAL_ACTIONS:
            raise OutputRejected(f"끝나지 않은 처리 경로가 결과에 남았다: {problem.nextAction}")
        if problem.ref.startswith("new:"):
            if problem.questionCount != 0:
                raise OutputRejected(f"새 문제의 질문 횟수는 0이어야 한다: {problem.ref}")
            continue
        known = existing.get(problem.ref)
        if known is None:
            raise OutputRejected(f"입력에 없는 문제 ref다: {problem.ref}")
        if problem.questionCount != known.questionCount:
            raise OutputRejected(f"질문 횟수는 서버 값을 바꿀 수 없다: {problem.ref}")
        if problem.nextAction == "ASK_USER" and known.questionCount >= QUESTION_LIMIT:
            raise OutputRejected(f"질문 한도를 넘겨 다시 질문한다: {problem.ref}")

    departments = {department.code for department in data.departments}
    proposing = {
        problem.ref for problem in outcome.problems if problem.nextAction == "PROPOSE_TICKETS"
    }
    proposal_refs = [proposal.ref for proposal in outcome.ticket_proposals]
    if len(set(proposal_refs)) != len(proposal_refs):
        raise OutputRejected("티켓 제안 ref가 중복된다")
    proposed = set()
    for proposal in outcome.ticket_proposals:
        if proposal.problemRef not in proposing:
            raise OutputRejected(f"티켓 제안 대상이 아닌 문제를 가리킨다: {proposal.problemRef}")
        if proposal.departmentCode not in departments:
            raise OutputRejected(f"부서 목록에 없는 부서 코드다: {proposal.departmentCode}")
        proposed.add(proposal.problemRef)
    if proposing - proposed:
        raise OutputRejected(f"티켓 제안이 없는 문제가 있다: {sorted(proposing - proposed)}")

    evidence_ids = {evidence.id for evidence in outcome.evidence}
    referenced = {
        evidence_id for problem in outcome.problems for evidence_id in problem.evidenceIds
    }
    referenced |= {
        evidence_id for proposal in outcome.ticket_proposals for evidence_id in proposal.evidenceIds
    }
    if referenced - evidence_ids:
        raise OutputRejected(f"결과에 없는 근거 ID를 가리킨다: {sorted(referenced - evidence_ids)}")
