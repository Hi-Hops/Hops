"""행동 성질 검사기(B7~B9). `docs/knowledge/EVALUATION.md`의 행동 성질 표를 따른다.

안전 성질(`checks.py`)과 다르게 한 번 어겨도 실패가 아니다. 모델의 선택이 섞이므로 반복 통과율로
본다. 그래서 안전 성질의 `CHECKS`에 넣지 않고 별도 목록으로 두며, 보고서에도 안전 위반과 따로
싣는다. 검사기는 실행 기록(`RunTrace`)만 보는 순수 함수이며 모델을 부르지 않는다.

시스템 이름·문서 키·시나리오 문구를 쓰지 않는다. 의도(`intent`)와 역할 호출의 기록만 본다.
"""

from collections.abc import Callable
from dataclasses import dataclass

from evaluation.harness.checks import Violation
from evaluation.harness.trace import AssessmentRecord, RunTrace, TurnTrace
from hops_ai.contracts.domain import AgentRole

# 검색이나 판정 없이 필요한 정보가 모이면 바로 담당자 티켓으로 가는 의도(정책 표를 가져오지 않고
# 평가 기준으로 직접 둔다. 정책 표가 바뀌어도 이 기준이 따라 바뀌지 않는다).
NO_SEARCH_INTENTS = frozenset({"FEATURE_REQUEST", "IMPROVEMENT_REQUEST"})
# 증상 문의. 검색하고 근거를 판정한 뒤에 안내나 티켓으로 가야 한다.
SYMPTOM_INTENTS = frozenset({"OBSERVED_SYMPTOM"})


@dataclass(frozen=True)
class BehaviorFinding(Violation):
    """행동 성질을 어긴 관찰. 안전 위반이 아니다."""


def _knowledge_calls(turn: TurnTrace, ref: str):
    return [
        call
        for call in turn.role_calls
        if call.role == AgentRole.KNOWLEDGE and call.problem_ref == ref
    ]


def check_b9(trace: RunTrace) -> list[Violation]:
    """B9. 검색이 필요한 문의는 검색·판정을 거치고, 필요 없는 문의는 거치지 않는다.

    - 기능 추가·개선 요청은 검색도 충분성 판정도 하지 않는다(지식으로 풀 문제가 아니다).
    - 증상 문의가 안내나 티켓으로 끝났으면 그 문제를 검색했고, 근거를 찾았다면 판정이 있다.
    """
    found: list[Violation] = []
    for index, turn in enumerate(trace.turns):
        result = turn.result
        if result is None or result.status != "SUCCEEDED":
            continue
        ticketed = {ticket.problemRef for ticket in result.ticketProposals}
        guided = {result.guide.problemRef} if result.guide is not None else set()
        for problem in result.problemProposals:
            calls = _knowledge_calls(turn, problem.problemRef)
            if problem.intent in NO_SEARCH_INTENTS:
                if calls:
                    found.append(BehaviorFinding("B9", index, f"{problem.intent} 문제를 검색했다"))
            elif problem.intent in SYMPTOM_INTENTS and problem.problemRef in (ticketed | guided):
                if not calls:
                    found.append(
                        BehaviorFinding("B9", index, "검색하지 않은 증상 문의가 종료 처리됐다")
                    )
                elif any(call.search_state == "FOUND" for call in calls) and not any(
                    call.assessment is not None for call in calls
                ):
                    found.append(
                        BehaviorFinding("B9", index, "근거를 찾고도 판정 없이 증상 문의가 끝났다")
                    )
    return found


def _last_assessments(turn: TurnTrace) -> dict[str, AssessmentRecord]:
    """그 실행에서 문제마다 마지막으로 낸 판정(같은 문제를 다시 검색하면 나중 것이 이긴다)."""
    latest: dict[str, AssessmentRecord] = {}
    for call in turn.role_calls:
        if call.role == AgentRole.KNOWLEDGE and call.assessment is not None:
            latest[call.assessment.problem_ref] = call.assessment
    return latest


def check_b7(trace: RunTrace) -> list[Violation]:
    """B7. 판정과 종결이 일관된다: 충분하면 안내로, 충분하지 않으면 안내로 끝나지 않는다.

    마지막 판정이 `SUFFICIENT`인데 질문 없이 티켓으로 끝났거나, `SUFFICIENT`가 아닌데 안내가
    나갔으면 관찰로 남긴다. 충분한데 꼭 필요한 정보가 비어 질문하는 경우는 합법이라 보지 않는다.
    """
    found: list[Violation] = []
    for index, turn in enumerate(trace.turns):
        result = turn.result
        if result is None or result.status != "SUCCEEDED":
            continue
        ticketed = {ticket.problemRef for ticket in result.ticketProposals}
        asked = {question.problemRef for question in result.questions}
        guided = result.guide.problemRef if result.guide is not None else None
        for ref, assessment in _last_assessments(turn).items():
            if assessment.verdict == "SUFFICIENT" and ref in ticketed and ref not in asked:
                found.append(
                    BehaviorFinding("B7", index, "충분하다는 판정인데 안내 없이 티켓으로 끝났다")
                )
            if assessment.verdict != "SUFFICIENT" and ref == guided:
                found.append(
                    BehaviorFinding("B7", index, f"{assessment.verdict} 판정인데 안내가 나갔다")
                )
    return found


def check_b8(trace: RunTrace) -> list[Violation]:
    """B8. 생성된 질문이 판정이 부족하다고 한 정보를 겨냥한다.

    판정의 부족한 정보(양식 필드 또는 관계 확인 항목)가 있는 문제에 질문이 나갔다면, 질문이 요청한
    항목에 그 중 하나 이상이 있어야 한다. 이미 확보했거나 모른다고 했거나 이미 물은 항목은 질문에서
    제외되므로 부족한 정보가 모두 그런 항목이면 겨냥할 수 없다(이 검사기는 그 경우도 관찰로 남긴다).
    """
    found: list[Violation] = []
    for index, turn in enumerate(trace.turns):
        result = turn.result
        if result is None or result.status != "SUCCEEDED":
            continue
        assessments = _last_assessments(turn)
        for question in result.questions:
            assessment = assessments.get(question.problemRef)
            missing = {ref for _, ref in assessment.missing_information} if assessment else set()
            if missing and not missing & set(question.requestedFields):
                found.append(
                    BehaviorFinding(
                        "B8", index, "질문이 판정의 부족한 정보를 하나도 요청하지 않았다"
                    )
                )
    return found


def check_b10(trace: RunTrace) -> list[Violation]:
    """B10. 안내 뒤 미해결로 응답한 문제에 같은 근거로 다시 안내하지 않는다.

    미해결 응답으로 접수한 실행(문제의 `resolutionContext`가 UNRESOLVED)에서, 이미 안내한 근거
    (`standardRequest.knowledge`)와 같은 문서·버전으로 안내가 나가면 관찰로 남긴다. 다른 문서나 새
    버전으로 안내하거나 담당자 검토로 넘기는 것은 위반이 아니다.
    """
    found: list[Violation] = []
    for index, turn in enumerate(trace.turns):
        result = turn.result
        if result is None or result.status != "SUCCEEDED" or result.guide is None:
            continue
        guide = result.guide
        problem = next((p for p in turn.request.problems if p.id == guide.problemRef), None)
        context = problem.resolutionContext if problem is not None else None
        if problem is None or context is None or context.state != "UNRESOLVED":
            continue
        guided = {
            (item.documentId, item.documentVersion)
            for item in (problem.standardRequest.knowledge if problem.standardRequest else [])
        }
        used = {
            (item.documentId, item.documentVersion)
            for item in result.evidence
            if item.id in guide.evidenceIds
        }
        if used & guided:
            found.append(
                BehaviorFinding("B10", index, "미해결 응답 뒤 이미 안내한 근거로 다시 안내했다")
            )
    return found


BEHAVIOR_CHECKS: dict[str, Callable[[RunTrace], list[Violation]]] = {
    "B7": check_b7,
    "B8": check_b8,
    "B10": check_b10,
    "B9": check_b9,
}


def check_behavior(trace: RunTrace) -> list[Violation]:
    """행동 성질을 모두 검사해 관찰을 모은다."""
    found: list[Violation] = []
    for check in BEHAVIOR_CHECKS.values():
        found.extend(check(trace))
    return found
