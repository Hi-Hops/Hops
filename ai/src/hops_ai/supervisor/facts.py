"""State에서 한 문제의 합법 행동 입력(`ProblemFacts`)을 만든다. State를 수정하지 않는다.

합법 행동 규칙 자체는 `rules.legal_actions`의 순수 함수이고, 이 모듈은 그 입력을 State에서 읽는
어댑터다. 값마다 읽는 곳을 정해 두어 규칙과 State 사이의 해석이 한 곳에만 있게 한다.

- 질문 가능 여부는 분석이 쓰던 양식 규칙(`agents.inquiry.asking.question_plan`)으로 계산한다. 그래서
  합법 행동에 질문이 있으면 ASK 행동이 실제로 질문을 만들 수 있다.
- 답변은 판정이 SUFFICIENT이고 그 판정이 인용한 근거로 안내가 만들어졌을 때만 가능하다(안내문은
  승인된 직원 공개 가이드 본문으로만 조립한다).
- 평가는 검색 호출 안에서 함께 끝나므로 따로 호출할 평가는 없다(assessment_pending은 거짓).
- 안내 뒤 미해결 응답(5.3절 티켓 (c))은 멀티턴(#189)이 만든다. 그 전까지는 거짓이다.
"""

from hops_ai.agents.inquiry.asking import answerable, question_plan
from hops_ai.agents.inquiry.forms import FIELD_LABELS, SYSTEM
from hops_ai.agents.knowledge.agent import next_query, research_used
from hops_ai.contracts.roles import KnowledgeResult
from hops_ai.contracts.types import QUESTION_LIMIT
from hops_ai.rules.intent_policy import policy_for, search_kinds
from hops_ai.rules.legal_actions import RESEARCH_LIMIT, ProblemFacts
from hops_ai.rules.progress import get_problem_progress
from hops_ai.supervisor.state import SupervisorState


def pending_problem_refs(state: SupervisorState) -> list[str]:
    """아직 결과가 없는 대상 문제. 종료 행동을 확정했거나 서버가 결과를 준 문제는 빠진다."""
    return [item["problemRef"] for item in get_problem_progress(state) if item["outcome"] is None]


def _latest_search_state(state: SupervisorState, ref: str) -> str | None:
    states = [
        result.search.state
        for result in state["roleResults"].values()
        if isinstance(result, KnowledgeResult) and result.problemRef == ref
    ]
    return states[-1] if states else None


def problem_facts(state: SupervisorState, ref: str) -> ProblemFacts:
    """그 문제의 합법 행동 입력."""
    request = state["input"]
    problem = state["problems"][ref]
    original = next((item for item in request.problems if item.id == ref), None)
    scopes = request.accessContext.allowedDocumentScopes
    search_state = _latest_search_state(state, ref)
    assessment = state["assessments"].get(ref)
    plan = question_plan(state, ref, searched=search_state is not None)
    return ProblemFacts(
        out_of_scope=(
            problem.intent == "OUT_OF_SCOPE"
            or problem.processingType == "OUT_OF_SCOPE"
            or (original is not None and original.state == "OUT_OF_SCOPE")
        ),
        searchable=bool(search_kinds(problem.intent, scopes)),
        system_known=problem.reportedSystemCandidateId is not None,
        system_declared_unknown=FIELD_LABELS[SYSTEM] in problem.unknowns,
        search_state=search_state,
        research_remaining=research_used(state, ref) < RESEARCH_LIMIT,
        new_query_available=next_query(state, problem) is not None,
        verdict=assessment.verdict if assessment is not None else None,
        employee_evidence=answerable(state, ref),
        assessment_pending=False,
        assess_budget_ok=True,
        remaining_questions=QUESTION_LIMIT - (original.questionCount if original else 0),
        askable=plan.action == "ASK",
        required_info_secured=plan.action == "COMPLETE",
        direct_ticket_intent=policy_for(problem.intent).ticket_when_ready,
        unresolved_without_other_evidence=False,
    )
