"""Inquiry가 더 할 일이 있는지 양식 규칙으로 센다. 역할 선택을 직접 정하지 않는다.

Supervisor의 판단 문맥(handoffReadiness)과 Inquiry의 호출 가능 여부(supervisor.choices)가
같은 계산을 쓴다.
"""

from typing import TypedDict

from hops_ai.agents.inquiry.extraction import DETAIL_FIELDS
from hops_ai.agents.inquiry.forms import FIELD_LABELS, missing_fields
from hops_ai.contracts.roles import InquiryResult
from hops_ai.contracts.state import SupervisorState
from hops_ai.rules.intent_policy import is_guide_type, is_ticketable
from hops_ai.rules.progress import get_problem_progress, problems_without_guide


class HandoffReadiness(TypedDict):
    """가이드를 찾지 못한 사용법 문제가 담당자 전달에 필요한 정보를 갖췄는지의 읽기 전용 투영."""

    problemRef: str
    missingForHandoff: list[str]
    remainingQuestions: int


def _asked_fields(state: SupervisorState, ref: str) -> set[str]:
    """Spring이 저장한 질문 이력에서 그 문제에 이미 물은 항목. 새 문제나 이력이 없으면 비어 있다."""
    original = next((item for item in state["input"].problems if item.id == ref), None)
    return set(original.askedFields) if original is not None else set()


def get_handoff_readiness(state: SupervisorState) -> list[HandoffReadiness]:
    """Knowledge가 안내를 만들지 못한 뒤, 처리 결과가 없는 그 사용법 문제별로 계산한다.

    모델이 "정보가 충분한가"를 추측하지 않도록 코드가 양식 규칙(forms)으로 센 사실이다.
    missingForHandoff가 비어 있으면 담당자 전달에 필요한 항목이 모두 확보됐다는 뜻이다.
    역할 선택을 정하지 않으며, 사용자가 모른다고 답한 항목은 누락에서 뺀다.
    remainingQuestions는 더 물을 수 있는 질문 수다. 누락 항목이 모두 이미 물은 항목이면
    질문 한도가 남아도 같은 질문을 다시 할 수 없으므로 0이다.
    """
    no_guide = problems_without_guide(state)
    if not no_guide:
        return []

    names_by_label = {label: name for name, label in FIELD_LABELS.items()}
    readiness: list[HandoffReadiness] = []
    for item in get_problem_progress(state):
        problem = state["problems"][item["problemRef"]]
        if (
            item["outcome"] is not None
            or not is_guide_type(problem.processingType)
            or item["problemRef"] not in no_guide
        ):
            continue
        known = {name: getattr(problem.details, name) for name in DETAIL_FIELDS}
        declared_unknown = {
            names_by_label[label] for label in problem.unknowns if label in names_by_label
        }
        missing = missing_fields(
            problem.intent,
            known,
            system_known=problem.reportedSystemCandidateId is not None,
            unknown=declared_unknown,
            handoff=True,
        )
        asked = _asked_fields(state, item["problemRef"])
        can_ask = any(name not in asked for name in missing)
        readiness.append(
            HandoffReadiness(
                problemRef=item["problemRef"],
                missingForHandoff=missing,
                remainingQuestions=item["remainingQuestions"] if can_ask else 0,
            )
        )
    return readiness


def all_unfinished_ready_for_handoff(state: SupervisorState) -> bool:
    """남은 문제가 모두, 안내를 만들지 못한 사용법 문제이고 Inquiry가 더 할 일이 없는지.

    처리 결과가 없는 문제가 있고, 그 문제가 전부 검색했지만 안내를 만들지 못한 사용법 문제이며,
    각각 (1) 담당자 전달 정보가 갖춰졌거나 (2) 질문 한도에 도달해 더 물을 수 없을 때만 참이다.
    (2)에서는 정보가 비어 있어도 Inquiry가 질문 없이 담당자 전달(HANDOFF)만 반환하므로 다시
    불러도 문제가 처리되지 않은 채 남는다. 다른 유형의 미처리 문제나 아직 검색하지 않은 사용법
    문제가 하나라도 있거나, 정보가 비었고 물을 수 있는 질문이 남았으면 거짓이다. 이 경우가 아닌
    상태에서 Inquiry를 다시 불러도 같은 원문에서 같은 사실을 다시 뽑을 뿐 새 질문을 만들 수 없다.
    """
    unfinished = [
        item["problemRef"] for item in get_problem_progress(state) if item["outcome"] is None
    ]
    # 정보가 갖춰졌거나 질문 한도에 도달해 더 물을 수 없다.
    ready = {
        item["problemRef"]: not item["missingForHandoff"] or item["remainingQuestions"] <= 0
        for item in get_handoff_readiness(state)
    }
    return bool(unfinished) and all(ready.get(ref, False) for ref in unfinished)


def inquiry_has_nothing_left_to_do(state: SupervisorState) -> bool:
    """Inquiry가 돌았고, 모든 대상에 결과가 있거나 Ticket 전달 정보가 갖춰졌는지.

    이 상태에서 Inquiry를 다시 부르면 질문을 만들 문제가 없고, 이미 만든 질문과 다른 내용의 질문을
    같은 묶음에 다시 만들어 병합이 거부될 위험만 있다. 이 실행에서 Inquiry가 아직 돌지 않았다면
    (이전 실행의 기존 결과만 있는 후속 실행의 첫 판단 등) 새 메시지를 분석해야 하므로
    해당하지 않는다.
    """
    if not any(isinstance(result, InquiryResult) for result in state["roleResults"].values()):
        return False

    progress = get_problem_progress(state)
    names_by_label = {label: name for name, label in FIELD_LABELS.items()}
    for item in progress:
        if item["outcome"] is not None:
            continue
        problem = state["problems"][item["problemRef"]]
        # 분석이 끝난 조사·신청 등의 다음 단계는 Ticket이다. 같은 입력을 다시 추출해도
        # 필요한 정보가 늘지 않는다. 미검색 사용법이나 시스템 미확인 문제는 제외하지 않는다.
        if not is_ticketable(problem.processingType) or problem.reportedSystemCandidateId is None:
            return False
        missing = missing_fields(
            problem.intent,
            {name: getattr(problem.details, name) for name in DETAIL_FIELDS},
            system_known=True,
            unknown={
                names_by_label[label] for label in problem.unknowns if label in names_by_label
            },
        )
        asked = _asked_fields(state, item["problemRef"])
        # 누락 항목이 모두 이미 물은 것이면 다시 물을 수 없으므로 Inquiry가 더 할 일이 없다.
        if item["remainingQuestions"] > 0 and any(name not in asked for name in missing):
            return False
    return bool(progress)
