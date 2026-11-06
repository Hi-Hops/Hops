"""한 문제의 다음 질문을 State에서 계산한다. 모델을 부르지 않으며 State를 수정하지 않는다.

문제별 행동 경로에서 질문은 분석과 별개의 행동(ASK)이다. 분석이 질문까지 만들면 검색 결과를 본 뒤
답변과 질문 중 무엇을 할지 고르기 전에 질문이 State에 남아, 같은 문제의 안내를 병합할 수 없기
때문이다. 이 모듈은 분석이 쓰던 양식 규칙(forms.plan_next)을 그대로 State의 문제에 적용한다.

- 가이드를 먼저 찾을 수 있는 의도(사용법)는 검색 전에는 검색에 필요한 필드만 요구한다.
- 검색을 했는데 직원에게 안내할 수 없으면 담당자 전달에 필요한 필드를 요구한다.
  안내할 수 있다는 것은 판정이 SUFFICIENT이고 그 판정이 인용한 근거로 안내가 만들어졌다는 뜻이다.
- 이미 물은 필드는 State에 기록이 없어 알 수 없으므로(멀티턴은 #189) 넘기지 않는다. 분석이 쓰던
  것과 같다.
"""

from collections.abc import Mapping

from hops_ai.agents.inquiry.extraction import DETAIL_FIELDS
from hops_ai.agents.inquiry.forms import FIELD_LABELS, QuestionPlan, plan_next
from hops_ai.contracts.state import SupervisorState
from hops_ai.contracts.wire import ProblemProposal, QuestionProposal
from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.assessment import allowed_fields, answerable


def _declared_unknown(problem: ProblemProposal) -> set[str]:
    names_by_label = {label: name for name, label in FIELD_LABELS.items()}
    return {names_by_label[label] for label in problem.unknowns if label in names_by_label}


def _known(problem: ProblemProposal) -> Mapping[str, str | None]:
    values = {name: getattr(problem.details, name) for name in DETAIL_FIELDS}
    return {
        name: value for name, value in values.items() if isinstance(value, str) and value.strip()
    }


def priority_fields(state: SupervisorState, ref: str) -> tuple[str, ...]:
    """Knowledge 판정이 부족하다고 한 양식 필드. 질문에서 먼저 묻는다(없으면 빈 튜플).

    판정의 부족한 정보는 검증을 거쳐 그 의도의 양식 필드만 담지만, 여기서도 한 번 더 거른다.
    """
    assessment = state["assessments"].get(ref)
    if assessment is None:
        return ()
    allowed = set(allowed_fields(state["problems"][ref].intent))
    return tuple(
        item.ref
        for item in assessment.missingInformation
        if item.kind == "FIELD" and item.ref in allowed
    )


def question_plan(state: SupervisorState, ref: str, *, searched: bool) -> QuestionPlan:
    """그 문제의 다음 질문 계획(ASK·COMPLETE·HANDOFF·OUT_OF_SCOPE).

    Args:
        searched: 이번 실행에서 그 문제를 검색했는지. 검색했는데 답할 수 없으면 담당자 전달 필드를
            요구한다.
    """
    problem = state["problems"][ref]
    original = next((item for item in state["input"].problems if item.id == ref), None)
    return plan_next(
        problem.intent,
        _known(problem),
        system_known=problem.reportedSystemCandidateId is not None,
        system_names=[system.name for system in state["input"].systems],
        question_count=original.questionCount if original is not None else 0,
        unknown=_declared_unknown(problem),
        guide_first=True,
        handoff=searched and not answerable(state, ref),
        priority=priority_fields(state, ref),
    )


def build_question(state: SupervisorState, ref: str, *, searched: bool) -> QuestionProposal:
    """질문할 수 있는 문제의 질문 하나(고정 문구). 질문할 수 없으면 거부한다."""
    return question_from_plan(state, ref, question_plan(state, ref, searched=searched))


def question_from_plan(
    state: SupervisorState, ref: str, plan: QuestionPlan, text: str | None = None
) -> QuestionProposal:
    """계획의 필드를 묻는 질문 하나. text를 주면 그 문장을, 아니면 고정 문구를 쓴다.

    필드와 묶음 식별자는 항상 계획이 정한다. 문장을 누가 썼든 무엇을 물을지는 코드가 정한다.
    """
    if plan.action != "ASK" or plan.text is None:
        raise OutputRejected("지금 질문할 수 있는 정보가 없는 문제다")
    original = next((item for item in state["input"].problems if item.id == ref), None)
    asked = original.questionCount if original is not None else 0
    return QuestionProposal(
        problemRef=ref,
        bundleRef=f"inquiry-{ref.replace(':', '-')}-{asked + 1}",
        text=text if text is not None else plan.text,
        requestedFields=list(plan.fields),
    )
