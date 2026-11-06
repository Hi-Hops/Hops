"""문제별 합법 행동 계산(5.3절). 모델·State 없이 순수 함수만 확인한다.

표의 행마다 읽히는 개별 테스트와, 입력 조합을 모두 열거하는 성질 테스트로 구성한다. 이 테스트의
통과는 모델이 좋은 행동을 고른다는 뜻이 아니라, 선택지에서 불가능한 행동이 빠지고 선택지가 비지
않는다는 뜻이다.
"""

from dataclasses import replace
from itertools import product

import pytest

from hops_ai.rules.legal_actions import (
    NON_TERMINAL,
    SAFE_DEFAULT_ORDER,
    TERMINAL,
    ProblemFacts,
    legal_actions,
    run_actions,
    safe_default,
)


def facts(**changes) -> ProblemFacts:
    """시스템이 정해진 검색 가능한 문제(아직 검색 전, 질문 가능 없음)를 기본으로 한다."""
    base = dict(
        out_of_scope=False,
        searchable=True,
        system_known=True,
        system_declared_unknown=False,
        search_state=None,
        research_remaining=False,
        new_query_available=False,
        verdict=None,
        employee_evidence=False,
        assessment_pending=False,
        assess_budget_ok=True,
        remaining_questions=5,
        askable=False,
        required_info_secured=False,
        direct_ticket_intent=False,
        unresolved_without_other_evidence=False,
    )
    return ProblemFacts(**{**base, **changes})


def searched(verdict, *, employee=True, state="FOUND", **changes) -> ProblemFacts:
    return facts(search_state=state, verdict=verdict, employee_evidence=employee, **changes)


# --- 실행 단위 ---


def test_the_first_iteration_of_a_run_can_only_analyze() -> None:
    assert run_actions(analyzed=False) == {"ANALYZE"}
    assert run_actions(analyzed=True) == frozenset()


def test_only_greetings_or_an_empty_analysis_can_reply_instead() -> None:
    """분석할 문의가 없거나 분석이 문제를 찾지 못한 실행은 대화 응답만 합법이다."""
    assert run_actions(analyzed=False, courtesy_only=True) == {"REPLY"}
    assert run_actions(analyzed=True, no_problem_found=True) == {"REPLY"}
    # 분석한 뒤에는 인사만 왔더라도 분석 결과가 정한다. 이미 분석했다면 다시 응답할 이유가 없다.
    assert run_actions(analyzed=True, courtesy_only=True) == frozenset()
    assert run_actions(analyzed=False, no_problem_found=True) == {"ANALYZE"}


# --- 범위 밖·시스템 미확정 ---


def test_an_out_of_scope_problem_can_only_be_closed() -> None:
    assert legal_actions(facts(out_of_scope=True, askable=True, searchable=True)) == {"CLOSE"}


def test_unknown_system_asks_while_it_can_and_holds_when_it_cannot() -> None:
    assert legal_actions(facts(system_known=False, askable=True)) == {"ASK"}
    assert legal_actions(facts(system_known=False, askable=False)) == {"HOLD"}
    assert legal_actions(facts(system_known=False, askable=True, remaining_questions=0)) == {"HOLD"}


def test_declared_unknown_system_may_hold_even_while_other_questions_remain() -> None:
    both = facts(system_known=False, system_declared_unknown=True, askable=True)

    assert legal_actions(both) == {"ASK", "HOLD"}
    assert legal_actions(replace(both, askable=False)) == {"HOLD"}


def test_unknown_system_never_searches_answers_or_tickets_whatever_the_assessment_says() -> None:
    sufficient = searched("SUFFICIENT", system_known=False, askable=True, direct_ticket_intent=True)

    assert legal_actions(sufficient) <= {"ASK", "HOLD"}


# --- 검색·평가·재검색 ---


def test_a_searchable_problem_with_a_known_system_must_search_before_answer_or_ticket_b() -> None:
    assert legal_actions(facts()) == {"SEARCH"}
    assert legal_actions(facts(askable=True)) == {"SEARCH", "ASK"}


def test_a_problem_whose_intent_has_no_search_kinds_never_searches() -> None:
    result = legal_actions(facts(searchable=False))

    assert "SEARCH" not in result and result == {"TICKET"}


def test_search_is_not_repeated_after_a_search_even_an_empty_or_failed_one() -> None:
    for state in ("FOUND", "EMPTY", "ERROR"):
        assert "SEARCH" not in legal_actions(facts(search_state=state))


def test_assessment_is_legal_only_for_found_results_that_are_pending_and_affordable() -> None:
    pending = facts(search_state="FOUND", assessment_pending=True)

    assert "ASSESS" in legal_actions(pending)
    assert "ASSESS" not in legal_actions(replace(pending, assess_budget_ok=False))
    assert "ASSESS" not in legal_actions(replace(pending, search_state="EMPTY"))
    assert "ASSESS" not in legal_actions(replace(pending, assessment_pending=False))


def test_assessment_that_cannot_be_afforded_does_not_trap_the_problem() -> None:
    broke = facts(search_state="FOUND", assessment_pending=True, assess_budget_ok=False)

    assert legal_actions(broke) == {"TICKET"}


def test_research_needs_a_weak_verdict_a_remaining_allowance_and_a_new_query() -> None:
    ready = searched("PARTIAL", research_remaining=True, new_query_available=True)

    assert legal_actions(ready) == {"RESEARCH"}
    for verdict in ("INSUFFICIENT", "NO_MATCH"):
        assert "RESEARCH" in legal_actions(replace(ready, verdict=verdict))
    assert "RESEARCH" not in legal_actions(replace(ready, verdict="SUFFICIENT"))
    assert "RESEARCH" not in legal_actions(replace(ready, research_remaining=False))
    assert "RESEARCH" not in legal_actions(replace(ready, new_query_available=False))
    assert "RESEARCH" not in legal_actions(replace(ready, search_state="ERROR"))


def test_an_empty_search_can_be_retried_with_a_different_query() -> None:
    empty = searched("NO_MATCH", state="EMPTY", research_remaining=True, new_query_available=True)

    assert legal_actions(empty) == {"RESEARCH"}
    assert legal_actions(replace(empty, research_remaining=False)) == {"TICKET"}


# --- 답변 ---


def test_answer_needs_a_sufficient_verdict_backed_by_employee_evidence() -> None:
    assert legal_actions(searched("SUFFICIENT")) == {"ANSWER"}
    assert legal_actions(searched("SUFFICIENT", employee=False)) == {"TICKET"}
    for verdict in (None, "PARTIAL", "INSUFFICIENT", "NO_MATCH"):
        assert "ANSWER" not in legal_actions(searched(verdict))


def test_a_sufficient_verdict_still_allows_asking_when_more_can_be_asked() -> None:
    # 모델이 실제로 고르는 갈림길이다: 답변할지, 더 물을지.
    assert legal_actions(searched("SUFFICIENT", askable=True)) == {"ANSWER", "ASK"}


def test_answer_is_not_legal_before_the_search_it_depends_on() -> None:
    inconsistent = facts(verdict="SUFFICIENT", employee_evidence=True)

    assert "ANSWER" not in legal_actions(inconsistent)


# --- 질문 ---


def test_ask_needs_remaining_questions_and_something_to_ask() -> None:
    assert "ASK" in legal_actions(searched("PARTIAL", askable=True))
    assert "ASK" not in legal_actions(searched("PARTIAL", askable=True, remaining_questions=0))
    assert "ASK" not in legal_actions(searched("PARTIAL", askable=False))


# --- 티켓 ---


def test_ticket_b_waits_until_asking_and_researching_are_exhausted() -> None:
    weak = searched("INSUFFICIENT")

    assert legal_actions(weak) == {"TICKET"}
    assert "TICKET" not in legal_actions(replace(weak, askable=True))
    assert "TICKET" not in legal_actions(
        replace(weak, research_remaining=True, new_query_available=True)
    )


def test_ticket_a_needs_a_direct_intent_with_the_required_information() -> None:
    ready = facts(searchable=False, direct_ticket_intent=True, required_info_secured=True)

    assert legal_actions(ready) == {"TICKET"}
    assert legal_actions(replace(ready, required_info_secured=False)) == {"TICKET"}  # (b)
    assert legal_actions(replace(ready, required_info_secured=False, askable=True)) == {"ASK"}


def test_ticket_a_does_not_wait_for_a_search_the_intent_could_have_made() -> None:
    # 권한 신청처럼 검색할 수 있는 의도도 필수 정보를 갖추면 검색 없이 티켓으로 갈 수 있다.
    ready = facts(searchable=True, direct_ticket_intent=True, required_info_secured=True)

    assert legal_actions(ready) == {"SEARCH", "TICKET"}


def test_ticket_c_after_an_unresolved_guide_without_other_evidence() -> None:
    unresolved = searched("SUFFICIENT", unresolved_without_other_evidence=True)

    assert legal_actions(unresolved) == {"ANSWER", "TICKET"}


# --- 문서의 필수 회귀 입력 ---


def test_regression_failed_assessment_and_exhausted_questions_end_in_a_ticket() -> None:
    # 현상 문의, 시스템 확정, 검색은 했으나 평가 실패, 질문 소진, 물을 필드 없음, 재검색 소진.
    result = legal_actions(
        facts(
            search_state="FOUND",
            verdict=None,
            remaining_questions=0,
            askable=False,
            research_remaining=False,
        )
    )

    assert result == {"TICKET"}


def test_regression_failed_assessment_unknown_system_and_exhausted_questions_hold() -> None:
    # 시스템 미확정, 평가 실패, questionCount=5(남은 0), 물을 필드 없음, 명시적 모름 응답 없음.
    result = legal_actions(
        facts(
            system_known=False,
            search_state=None,
            verdict=None,
            remaining_questions=0,
            askable=False,
            system_declared_unknown=False,
        )
    )

    assert result == {"HOLD"}


# --- 모델 실패 시 기본 선택 ---


def test_safe_default_prefers_prerequisites_then_terminal_actions_then_research() -> None:
    assert SAFE_DEFAULT_ORDER == (
        "SEARCH",
        "ASSESS",
        "ANSWER",
        "ASK",
        "TICKET",
        "HOLD",
        "CLOSE",
        "RESEARCH",
    )
    assert safe_default(frozenset({"SEARCH", "ASK"})) == "SEARCH"
    assert safe_default(frozenset({"ANSWER", "ASK"})) == "ANSWER"
    assert safe_default(frozenset({"ASK", "TICKET"})) == "ASK"
    assert safe_default(frozenset({"ASK", "HOLD"})) == "ASK"
    assert safe_default(frozenset({"RESEARCH", "ASK"})) == "ASK"
    assert safe_default(frozenset({"RESEARCH"})) == "RESEARCH"


def test_safe_default_covers_every_action_and_refuses_an_empty_choice() -> None:
    assert set(SAFE_DEFAULT_ORDER) == NON_TERMINAL | TERMINAL
    with pytest.raises(ValueError):
        safe_default(frozenset())


# --- 입력 조합 전수 ---

DOMAINS = {
    "out_of_scope": (False, True),
    "searchable": (False, True),
    "system_known": (False, True),
    "system_declared_unknown": (False, True),
    "search_state": (None, "FOUND", "EMPTY", "ERROR"),
    "research_remaining": (False, True),
    "new_query_available": (False, True),
    "verdict": (None, "SUFFICIENT", "PARTIAL", "INSUFFICIENT", "NO_MATCH"),
    "employee_evidence": (False, True),
    "assessment_pending": (False, True),
    "assess_budget_ok": (False, True),
    "remaining_questions": (0, 1),
    "askable": (False, True),
    "required_info_secured": (False, True),
    "direct_ticket_intent": (False, True),
    "unresolved_without_other_evidence": (False, True),
}


def all_facts():
    names = list(DOMAINS)
    for values in product(*DOMAINS.values()):
        yield ProblemFacts(**dict(zip(names, values, strict=True)))


def test_every_input_combination_has_a_legal_action_and_obeys_the_rules() -> None:
    count = 0
    for f in all_facts():
        count += 1
        actions = legal_actions(f)
        where = f"{f}"

        assert actions, f"선택지가 비었다: {where}"
        if f.out_of_scope:
            assert actions == {"CLOSE"}, where
            continue
        assert "CLOSE" not in actions, where
        if "ANSWER" in actions:
            assert f.system_known and f.verdict == "SUFFICIENT" and f.employee_evidence, where
            assert f.search_state is not None or not f.searchable, where
        if "ASK" in actions:
            assert f.remaining_questions > 0 and f.askable, where
        if "HOLD" in actions:
            assert not f.system_known, where
            assert f.system_declared_unknown or not f.can_ask, where
        if not f.system_known:
            assert actions <= {"ASK", "HOLD"}, where
            continue
        assert "HOLD" not in actions, where
        if "SEARCH" in actions:
            assert f.searchable and f.search_state is None, where
        if "ASSESS" in actions:
            assert f.search_state == "FOUND" and f.assessment_pending, where
            assert f.assess_budget_ok, where
        if "RESEARCH" in actions:
            assert f.research_remaining and f.new_query_available, where
            assert f.verdict in ("PARTIAL", "INSUFFICIENT", "NO_MATCH"), where
            assert f.search_state in ("FOUND", "EMPTY"), where
        # 선행 행동(검색·평가)이 남은 동안 판정에 기댄 종료 행동은 없다.
        if actions & {"SEARCH", "ASSESS"}:
            assert "ANSWER" not in actions, where
        if "TICKET" in actions and not (
            (f.direct_ticket_intent and f.required_info_secured)
            or f.unresolved_without_other_evidence
        ):
            # 티켓 (b): 답변·질문·재검색·선행 행동이 모두 없다.
            assert not actions & {"ANSWER", "ASK", "RESEARCH", "SEARCH", "ASSESS"}, where
    assert count == 2 * 2 * 2 * 2 * 4 * 2 * 2 * 5 * 2 * 2 * 2 * 2 * 2 * 2 * 2 * 2


def test_the_function_is_pure_and_total_on_a_repeated_input() -> None:
    sample = searched("PARTIAL", askable=True, research_remaining=True, new_query_available=True)

    assert legal_actions(sample) == legal_actions(sample) == {"ASK", "RESEARCH"}
