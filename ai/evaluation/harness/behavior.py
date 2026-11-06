"""행동 성질 검사기(B9~). `docs/knowledge/EVALUATION.md`의 행동 성질 표를 따른다.

안전 성질(`checks.py`)과 다르게 한 번 어겨도 실패가 아니다. 모델의 선택이 섞이므로 반복 통과율로
본다. 그래서 안전 성질의 `CHECKS`에 넣지 않고 별도 목록으로 두며, 보고서에도 안전 위반과 따로
싣는다. 검사기는 실행 기록(`RunTrace`)만 보는 순수 함수이며 모델을 부르지 않는다.

시스템 이름·문서 키·시나리오 문구를 쓰지 않는다. 의도(`intent`)와 역할 호출의 기록만 본다.
"""

from collections.abc import Callable
from dataclasses import dataclass

from evaluation.harness.checks import Violation
from evaluation.harness.trace import RunTrace, TurnTrace
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


BEHAVIOR_CHECKS: dict[str, Callable[[RunTrace], list[Violation]]] = {"B9": check_b9}


def check_behavior(trace: RunTrace) -> list[Violation]:
    """행동 성질을 모두 검사해 관찰을 모은다."""
    found: list[Violation] = []
    for check in BEHAVIOR_CHECKS.values():
        found.extend(check(trace))
    return found
