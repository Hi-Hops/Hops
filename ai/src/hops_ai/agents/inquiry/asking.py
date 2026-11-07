"""한 문제의 다음 질문을 State에서 계산한다. 모델을 부르지 않으며 State를 수정하지 않는다.

문제별 행동 경로에서 질문은 분석과 별개의 행동(ASK)이다. 분석이 질문까지 만들면 검색 결과를 본 뒤
답변과 질문 중 무엇을 할지 고르기 전에 질문이 State에 남아, 같은 문제의 안내를 병합할 수 없기
때문이다. 이 모듈은 분석이 쓰던 양식 규칙(forms.plan_next)을 그대로 State의 문제에 적용한다.

- 가이드를 먼저 찾을 수 있는 의도(사용법)는 검색 전에는 검색에 필요한 필드만 요구한다.
- 검색을 했는데 직원에게 안내할 수 없으면 담당자 전달에 필요한 필드를 요구한다.
  안내할 수 있다는 것은 판정이 SUFFICIENT이고 그 판정이 인용한 근거로 안내가 만들어졌다는 뜻이다.
- 이미 물은 필드는 Spring이 저장한 질문 이력(`InputProblem.askedFields`)에서 받아 다시 묻지 않는다.
  이 실행 안에서 새로 만든 질문은 실행을 끝내므로 같은 실행에서 이어 묻는 일은 없다.
"""

from collections.abc import Mapping

from hops_ai.agents.inquiry.extraction import DETAIL_FIELDS
from hops_ai.agents.inquiry.forms import (
    FIELD_LABELS,
    MAX_FIELDS_PER_QUESTION,
    MAX_QUESTIONS_PER_PROBLEM,
    SYSTEM,
    QuestionPlan,
    compose_from_prompts,
    plan_next,
)
from hops_ai.contracts.state import SupervisorState
from hops_ai.contracts.wire import ProblemProposal, QuestionProposal
from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.assessment import allowed_fields, answerable, approved_questions


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


def form_question_plan(state: SupervisorState, ref: str, *, searched: bool) -> QuestionPlan:
    """양식 필드만 보는 그 문제의 다음 질문 계획(ASK·COMPLETE·HANDOFF·OUT_OF_SCOPE).

    필수 정보를 갖췄는지(COMPLETE)는 이 계획으로 판단한다. 확인 항목 질문은 필수 정보가 아니다.

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
        asked=original.askedFields if original is not None else (),
        unknown=_declared_unknown(problem),
        guide_first=True,
        handoff=searched and not answerable(state, ref),
        priority=priority_fields(state, ref),
    )


def check_item_questions(state: SupervisorState, ref: str) -> list[tuple[str, str]]:
    """판정이 지목했고 아직 묻지 않은 확인 항목의 (ID, 직원에게 물을 승인된 질문 문구). 지목 순서.

    이번 실행의 관계 조회가 직원 공개로 승인된 항목으로 보여 준 것만 쓴다. 판정이 다른 항목을
    지목했거나 문구가 없으면 담지 않는다. 승인되지 않은 항목이 질문에 들어갈 길은 여기서 막힌다.
    """
    assessment = state["assessments"].get(ref)
    relations = state["relations"].get(ref)
    if assessment is None or relations is None:
        return []
    original = next((item for item in state["input"].problems if item.id == ref), None)
    asked = set(original.askedFields) if original is not None else set()
    approved = approved_questions(relations)
    chosen: dict[str, str] = {}
    for item in assessment.missingInformation:
        if item.kind == "CHECK_ITEM" and item.ref in approved and item.ref not in asked:
            chosen.setdefault(item.ref, approved[item.ref])
    return list(chosen.items())


def question_plan(state: SupervisorState, ref: str, *, searched: bool) -> QuestionPlan:
    """그 문제의 다음 질문 계획. 양식 필드 질문에 판정이 지목한 확인 항목 질문을 더한다.

    - 시스템을 아직 모르면 시스템을 먼저 묻고 확인 항목은 더하지 않는다.
    - 한 묶음은 항목 3개 이하다. 양식 필드가 남아 있으면 확인 항목에 자리를 하나 이상 남긴다
      (양식 필드 질문만으로 묶음이 차서 확인 항목이 계속 밀리는 일을 막는다).
    - 양식 필드가 더 없으면(COMPLETE) 또는 이미 다 물었으면(HANDOFF) 확인 항목만 묻는다.
    - 질문 한도에 닿았으면 더 묻지 않는다. 한도와 순서는 코드가 정하고 모델이 바꾸지 못한다.
    """
    plan = form_question_plan(state, ref, searched=searched)
    if plan.action == "OUT_OF_SCOPE" or SYSTEM in plan.fields:
        return plan
    original = next((item for item in state["input"].problems if item.id == ref), None)
    if (original.questionCount if original is not None else 0) >= MAX_QUESTIONS_PER_PROBLEM:
        return plan
    checks = check_item_questions(state, ref)
    if not checks:
        return plan
    forms = plan.fields[: MAX_FIELDS_PER_QUESTION - 1] if plan.action == "ASK" else ()
    form_prompts = plan.prompts[: len(forms)]
    chosen = checks[: MAX_FIELDS_PER_QUESTION - len(forms)]
    prompts = (*form_prompts, *(text for _, text in chosen))
    return QuestionPlan(
        "ASK",
        fields=(*forms, *(check_id for check_id, _ in chosen)),
        text=compose_from_prompts(prompts),
        unknown=plan.unknown,
        prompts=prompts,
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
