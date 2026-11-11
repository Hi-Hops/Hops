"""문제별 합법 행동 계산(설계 문서 5.3절). 순수 함수이며 State·모델·검색을 부르지 않는다.

코드가 문제마다 지금 할 수 있는 행동의 집합을 계산하고, Supervisor 모델은 그중에서 고른다.
집합이 하나뿐이면 모델을 부르지 않는다. 안전 불변식(근거 ID, 직원 공개 근거, 질문 한도, 시스템
미확정 처리)은 이 집합에서 불가능한 행동을 빼는 방식으로 강제한다. 모델의 판정은 입력일 뿐이다.

입력은 State에서 읽은 사실을 모은 `ProblemFacts`다. State를 읽어 이 값을 만드는 어댑터는 이 모듈이
아니라 결정 경로(supervisor)가 맡는다. 그래서 입력의 모든 조합을 열거해 규칙을 검사할 수 있다.

종료 행동(ANSWER, ASK, TICKET, HOLD, CLOSE)은 현재 실행을 끝내고, 비종료 행동(SEARCH, RESEARCH,
ASSESS)은 결과를 State에 더한 뒤 판단 루프로 돌아간다. 분석(ANALYZE)과 대화 응답(REPLY)은 문제가
생기기 전·문제가 없을 때의 실행 단위 행동이라 `run_actions`가 따로 정한다. 대화 응답은 현재 실행을
끝낸다.

검색 오류(ERROR)는 이 표 밖이다. 호출 측이 오류를 P2 규칙대로 안전하게 실패 처리해야 하며, 이
함수는 오류를 "검색은 했지만 판정이 없다"로 읽어 어떤 입력에도 집합이 비지 않게만 한다.
"""

from dataclasses import dataclass
from typing import Literal

type Action = Literal["SEARCH", "RESEARCH", "ASSESS", "ANSWER", "ASK", "TICKET", "HOLD", "CLOSE"]
type SearchState = Literal["FOUND", "EMPTY", "ERROR"]
type Verdict = Literal["SUFFICIENT", "PARTIAL", "INSUFFICIENT", "NO_MATCH"]

# 문제당 재검색 횟수 상한(첫 검색은 세지 않는다). 설계 문서 5.1절의 안이며 측정으로 확정한다.
RESEARCH_LIMIT = 2

NON_TERMINAL: frozenset[Action] = frozenset({"SEARCH", "RESEARCH", "ASSESS"})
TERMINAL: frozenset[Action] = frozenset({"ANSWER", "ASK", "TICKET", "HOLD", "CLOSE"})

# 행동 선택 모델이 실패했을 때 코드가 고르는 순서. 검색·평가는 안내의 전제이므로 먼저, 재검색은
# 비용이 드는 선택적 개선이라 다른 행동이 없을 때만 고른다.
SAFE_DEFAULT_ORDER: tuple[Action, ...] = (
    "SEARCH",
    "ASSESS",
    "ANSWER",
    "ASK",
    "TICKET",
    "HOLD",
    "CLOSE",
    "RESEARCH",
)


@dataclass(frozen=True)
class ProblemFacts:
    """한 문제의 합법 행동을 정하는 사실. 모든 값은 State에서 코드가 계산해 채운다.

    out_of_scope: 의도가 범위 밖이다.
    searchable: 그 의도의 정책 표 "검색할 지식 종류"가 비어 있지 않다.
    system_known: 보고 시스템이 정해졌다.
    system_declared_unknown: 사용자가 시스템을 모른다고 답했다.
    search_state: 이번 실행에서 가장 최근 검색의 상태. 검색하지 않았으면 None이다.
    research_remaining: 재검색 횟수가 문제당 상한에 아직 닿지 않았다.
    new_query_available: 이전과 다른 질의(키워드·기능 후보·검색 종류)를 만들 수 있다.
    verdict: 가장 최근 유효한 판정. 판정이 없으면(검색 생략·평가 불가·평가 전) None이다.
    employee_evidence: 판정이 인용한 근거 가운데 직원 공개(EMPLOYEE) 근거가 있다.
    assessment_pending: 검색 결과가 있는데 아직 평가하지 않았고 평가를 따로 호출할 수 있다.
        검색 호출이 평가까지 한 번에 하는 구성에서는 항상 거짓이다.
    assess_budget_ok: 평가를 호출할 모델 호출 예산이 남아 있다.
    remaining_questions: 그 문제의 남은 질문 횟수.
    askable: 물을 수 있는 필드(아직 모르고, 묻지 않았고, 모른다고 하지 않음) 또는 판정의 부족한
        정보가 있다.
    required_info_secured: 처리에 필요한 필수 정보를 확보했다.
    direct_ticket_intent: 평가 없이 필수 정보만 갖추면 티켓으로 가는 의도(권한·기능 추가·개선)다.
    unresolved_without_other_evidence: 안내 뒤 사용자가 미해결로 응답했고 다른 근거가 없다.
    """

    out_of_scope: bool
    searchable: bool
    system_known: bool
    system_declared_unknown: bool
    search_state: SearchState | None
    research_remaining: bool
    new_query_available: bool
    verdict: Verdict | None
    employee_evidence: bool
    assessment_pending: bool
    assess_budget_ok: bool
    remaining_questions: int
    askable: bool
    required_info_secured: bool
    direct_ticket_intent: bool
    unresolved_without_other_evidence: bool
    requested_staff_review: bool = False

    @property
    def can_ask(self) -> bool:
        """질문 횟수가 남아 있고 물을 것이 있다."""
        return self.remaining_questions > 0 and self.askable


def run_actions(
    *, analyzed: bool, courtesy_only: bool = False, no_problem_found: bool = False
) -> frozenset[str]:
    """실행 단위의 행동. 문제가 생기기 전이라 문제별 행동(`legal_actions`)이 정하지 못한다.

    - 아직 분석하지 않았으면 분석만 합법이다. 단 분석하지 않은 사용자 메시지가 확인된 인사·감사
      표현뿐이면(courtesy_only) 분석할 문의가 없으므로 대화 응답(REPLY)만 합법이다.
    - 분석했는데 문제를 하나도 찾지 못했으면(no_problem_found) 같은 분석을 반복하지 않고 대화
      응답(REPLY)으로 되묻는 것만 합법이다.
    - 그 밖에는 비어 있고, 이후 행동은 문제별 합법 행동이 정한다.
    """
    if analyzed:
        return frozenset({"REPLY"}) if no_problem_found else frozenset()
    return frozenset({"REPLY"}) if courtesy_only else frozenset({"ANALYZE"})


def legal_actions(facts: ProblemFacts) -> frozenset[Action]:
    """그 문제가 지금 할 수 있는 행동. 어떤 입력에도 비어 있지 않다.

    판단 순서는 5.3절과 같다.
    1. 범위 밖이면 범위 밖 하나뿐이다.
    2. 시스템이 정해지지 않았으면 질문이 가능할 때 질문, 사용자가 모른다고 했거나 질문을 더 할
       수 없으면 시스템 확인 대기(평가 유무와 무관).
    3. 시스템이 정해졌고 안내 뒤 미해결 응답에 안내할 다른 근거가 없으면 티켓 (c) 하나뿐이다.
       이 규칙은 안내(사용법) 문제에 적용되며, 이미 안내한 문서를 뺀 검색 결과가 비었거나 있어도
       판정이 안내할 수 없다고 한 경우를 모두 포함한다. 사용법 문의는 관계 확인 항목을 쓰지
       않으므로 확인 항목 질문과 겹치지 않는다.
    4. 시스템이 정해졌으면 선행 행동(검색·평가)이 남은 동안은 그것과 질문·바로 티켓만 가능하고,
       선행 행동이 끝나면 답변 조건을 만족할 때 답변, 질문이 가능할 때 질문, 재검색할 수 있으면
       재검색, 그 밖에 질문도 재검색도 더 할 수 없으면 티켓 (b)다.
    """
    if facts.out_of_scope:
        return frozenset({"CLOSE"})

    if not facts.system_known:
        actions: set[Action] = set()
        if facts.can_ask:
            actions.add("ASK")
        if facts.system_declared_unknown or not facts.can_ask:
            actions.add("HOLD")
        return frozenset(actions)

    # 5.3절 티켓 (c): 안내 뒤 미해결로 응답했는데 다른 근거가 없으면 담당자 검토뿐이다.
    # 같은 질문이나 재검색을 되풀이하지 않는다. 필수 정보가 비어 있어도 미확인으로 보존해 넘긴다.
    if facts.unresolved_without_other_evidence:
        return frozenset({"TICKET"})

    # 필수 정보와 첫 검색이 있고 문의자가 담당자 확인을 명시적으로 요청하면 접수를 늦추지 않는다.
    if (
        facts.requested_staff_review
        and facts.required_info_secured
        and facts.search_state in ("FOUND", "EMPTY")
    ):
        return frozenset({"TICKET"})

    actions = set()
    searched = facts.search_state is not None
    can_search = facts.searchable and not searched
    can_assess = (
        facts.search_state == "FOUND" and facts.assessment_pending and facts.assess_budget_ok
    )
    can_research = (
        facts.search_state in ("FOUND", "EMPTY")
        and facts.verdict in ("PARTIAL", "INSUFFICIENT", "NO_MATCH")
        and facts.research_remaining
        and facts.new_query_available
    )
    # 선행 행동이 남은 동안에는 판정에 기댄 종료 행동(답변·티켓 (b))을 고를 수 없다.
    prerequisite_pending = can_search or can_assess
    can_answer = (
        not prerequisite_pending and facts.verdict == "SUFFICIENT" and facts.employee_evidence
    )

    if can_search:
        actions.add("SEARCH")
    if can_assess:
        actions.add("ASSESS")
    if can_research:
        actions.add("RESEARCH")
    if can_answer:
        actions.add("ANSWER")
    if facts.can_ask:
        actions.add("ASK")
    if (
        (facts.direct_ticket_intent and facts.required_info_secured)
        or facts.unresolved_without_other_evidence
        or (not prerequisite_pending and not can_answer and not facts.can_ask and not can_research)
    ):
        actions.add("TICKET")
    return frozenset(actions)


def safe_default(actions: frozenset[Action]) -> Action:
    """행동 선택 모델이 실패했을 때 코드가 고르는 행동. 합법 행동이 없으면 호출 오류다."""
    for action in SAFE_DEFAULT_ORDER:
        if action in actions:
            return action
    raise ValueError("합법한 행동이 없다")
