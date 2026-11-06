"""문제별 종료 행동의 확정, 질문 행동 분리, 합법 행동 입력 어댑터.

모델은 Fake다(질문 행동은 모델을 부르지 않는다는 것도 확인한다). 선택 노드와 그래프 연결은
이 테스트의 범위가 아니다. 여기서는 선택이 State에서 문제의 결과를 정하고, 고르지 않은 행동의
산출물이 응답에 새지 않는지와 합법 행동의 입력이 State에서 올바르게 읽히는지를 본다.
"""

import pytest

from hops_ai.agents.inquiry import ExtractionInquiryAgent
from hops_ai.agents.inquiry.asking import answerable, build_question
from hops_ai.agents.inquiry.extraction import InquiryExtraction
from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import Assessment, KnowledgeResult, SearchResult
from hops_ai.contracts.wire import AiRunRequest, GuideProposal, QuestionProposal
from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.intent_policy import INTENT_POLICIES
from hops_ai.rules.legal_actions import legal_actions
from hops_ai.rules.progress import DECISION_OUTCOMES, get_problem_progress
from hops_ai.supervisor.decisions import apply_decision
from hops_ai.supervisor.execution import execute_role
from hops_ai.supervisor.facts import pending_problem_refs, problem_facts
from hops_ai.supervisor.finalize import finalize_result
from tests.supervisor_fixtures import (
    EVIDENCE,
    PROBLEM,
    SYSTEM,
    make_details,
    make_evidence,
    make_ticket,
    running_state,
)
from tests.unit.test_inquiry_agent import extracted, first_message_request, services_for
from tests.unit.test_knowledge_agent import RecordingSource, how_to_request
from tests.unit.test_knowledge_agent import services_for as knowledge_services


def request_for(
    intent="OBSERVED_SYMPTOM", processing="INVESTIGATION", *, system=SYSTEM, question_count=0
) -> AiRunRequest:
    fields = how_to_request().model_dump(mode="json")
    fields["problems"][0].update(
        intent=intent,
        processingType=processing,
        summary="문서 미표시",
        reportedSystemId=system,
        questionCount=question_count,
    )
    return AiRunRequest.model_validate(fields)


def base_state(request=None, *, details=None, unknowns=()):
    state = running_state(request or request_for())
    problem = state["problems"][PROBLEM]
    updates = {"unknowns": list(unknowns)}
    if details is not None:
        updates["details"] = details
    return {**state, "problems": {PROBLEM: problem.model_copy(update=updates)}}


QUESTION = QuestionProposal(
    problemRef=PROBLEM,
    bundleRef="bundle-1",
    text="어떤 화면에서 그러셨나요?",
    requestedFields=["taskOrScreen"],
)


def with_question(state):
    return {**state, "questions": {(PROBLEM, QUESTION.bundleRef): QUESTION}}


def with_guide(state):
    evidence = make_evidence()
    guide = GuideProposal(
        messageText="문서 조회는 메뉴에서 기간을 고릅니다",
        evidenceIds=[evidence.id],
        problemRef=PROBLEM,
    )
    return {**state, "evidence": {evidence.id: evidence}, "guide": guide}


def with_assessment(state, verdict="SUFFICIENT", cited=(EVIDENCE,)):
    assessment = Assessment(
        problemRef=PROBLEM,
        verdict=verdict,
        supportingEvidenceIds=list(cited),
        missingInformation=[],
        candidateCauses=[],
        nextChecks=[],
        rationale="내부용",
    )
    return {**state, "assessments": {PROBLEM: assessment}}


def with_ticket(state):
    return {**state, "ticketProposals": {PROBLEM: make_ticket()}}


def outcome_of(state):
    [item] = get_problem_progress(state)
    return item["outcome"]


# --- 진행 상태가 선택을 따른다 ---


def test_without_decisions_the_outcome_still_follows_the_artifacts() -> None:
    state = with_question(base_state())

    assert state["decisions"] == {}
    assert outcome_of(state) == "ASK_USER"
    assert outcome_of(base_state()) is None


@pytest.mark.parametrize(("action", "outcome"), list(DECISION_OUTCOMES.items()))
def test_a_decision_sets_the_outcome_whatever_artifacts_exist(action, outcome) -> None:
    state = {**with_question(with_guide(base_state())), "decisions": {PROBLEM: action}}

    assert outcome_of(state) == outcome


def test_decided_problems_are_no_longer_pending() -> None:
    state = base_state()
    assert pending_problem_refs(state) == [PROBLEM]

    assert pending_problem_refs({**state, "decisions": {PROBLEM: "HOLD"}}) == []


# --- 결정 확정과 산출물 정리 ---


def test_answer_drops_a_question_made_earlier_and_keeps_the_guide() -> None:
    state = with_question(with_guide(base_state()))

    decided = apply_decision(state, PROBLEM, "ANSWER")

    assert decided["decisions"] == {PROBLEM: "ANSWER"}
    assert decided["questions"] == {}
    assert decided["guide"] is not None
    assert state["questions"] and state["decisions"] == {}, "입력 State는 바뀌지 않는다"


def test_ask_drops_a_guide_and_a_ticket_made_earlier_and_keeps_the_question() -> None:
    state = with_ticket(with_question(with_guide(base_state())))

    decided = apply_decision(state, PROBLEM, "ASK")

    assert decided["guide"] is None and decided["ticketProposals"] == {}
    assert list(decided["questions"]) == [(PROBLEM, "bundle-1")]


def test_ticket_drops_the_question_and_the_guide() -> None:
    decided = apply_decision(
        with_ticket(with_question(with_guide(base_state()))), PROBLEM, "TICKET"
    )

    assert decided["questions"] == {} and decided["guide"] is None
    assert list(decided["ticketProposals"]) == [PROBLEM]


@pytest.mark.parametrize("action", ["HOLD", "CLOSE"])
def test_hold_and_close_need_no_artifact_and_drop_every_artifact(action) -> None:
    decided = apply_decision(with_ticket(with_question(with_guide(base_state()))), PROBLEM, action)

    assert decided["questions"] == {} and decided["guide"] is None
    assert decided["ticketProposals"] == {}


@pytest.mark.parametrize(
    ("action", "state"),
    [("ASK", base_state()), ("ANSWER", base_state()), ("TICKET", base_state())],
)
def test_an_action_whose_artifact_does_not_exist_is_rejected(action, state) -> None:
    with pytest.raises(OutputRejected):
        apply_decision(state, PROBLEM, action)


def test_only_terminal_actions_on_existing_problems_and_only_once_are_accepted() -> None:
    with pytest.raises(OutputRejected):
        apply_decision(base_state(), PROBLEM, "SEARCH")
    with pytest.raises(OutputRejected):
        apply_decision(base_state(), "new:9", "HOLD")
    held = apply_decision(base_state(), PROBLEM, "HOLD")
    assert apply_decision(held, PROBLEM, "HOLD") == held
    with pytest.raises(OutputRejected):
        apply_decision(held, PROBLEM, "CLOSE")


# --- 최종 응답이 선택을 따른다 ---


def test_the_final_result_carries_only_what_the_chosen_action_made() -> None:
    state = apply_decision(with_question(with_guide(base_state())), PROBLEM, "ANSWER")

    result = finalize_result(state)["finalResult"]

    assert result.status == "SUCCEEDED"
    assert result.guide is not None and result.questions == []
    assert "기간을 고릅니다" in result.answer and "어떤 화면" not in result.answer


def test_the_final_result_of_a_question_does_not_carry_the_guide() -> None:
    state = apply_decision(with_question(with_guide(base_state())), PROBLEM, "ASK")

    result = finalize_result(state)["finalResult"]

    assert result.guide is None and len(result.questions) == 1
    assert "어떤 화면" in result.answer and "기간을 고릅니다" not in result.answer


def test_hold_and_close_end_with_their_fixed_notices_and_no_proposals() -> None:
    held = finalize_result(apply_decision(base_state(), PROBLEM, "HOLD"))["finalResult"]
    closed = finalize_result(apply_decision(base_state(), PROBLEM, "CLOSE"))["finalResult"]

    assert "보고 시스템을 확인하지 못했습니다" in held.answer
    assert "지원 범위 밖" in closed.answer
    for result in (held, closed):
        assert result.questions == [] and result.guide is None and result.ticketProposals == []


def test_a_run_with_an_undecided_problem_still_cannot_finish() -> None:
    with pytest.raises(OutputRejected):
        finalize_result(base_state())


# --- 분석과 질문의 분리 ---


async def test_analysis_without_deferral_still_makes_the_question_as_before() -> None:
    request = first_message_request()
    services, _ = services_for(request, InquiryExtraction(problems=[extracted()]))
    agents = {AgentRole.INQUIRY: ExtractionInquiryAgent()}

    merged = await execute_role(running_state(request), AgentRole.INQUIRY, services, agents)

    assert len(merged["questions"]) == 1


async def test_analysis_with_deferral_makes_the_problem_but_no_question() -> None:
    request = first_message_request()
    services, _ = services_for(request, InquiryExtraction(problems=[extracted()]))
    agents = {AgentRole.INQUIRY: ExtractionInquiryAgent(defer_questions=True)}

    merged = await execute_role(running_state(request), AgentRole.INQUIRY, services, agents)

    assert merged["questions"] == {}
    assert merged["problems"]["new:1"].details.actualBehavior == "결재 문서가 안 보여요"


async def ask(state, request, *, targets=(PROBLEM,)):
    services, llm = services_for(request)  # 모델 응답이 없다: 호출하면 실패한다
    agents = {AgentRole.INQUIRY: ExtractionInquiryAgent(defer_questions=True)}
    merged = await execute_role(
        state, AgentRole.INQUIRY, services, agents, targets=tuple(targets), action="ASK"
    )
    return merged, llm


async def test_the_ask_action_builds_one_question_without_calling_the_model() -> None:
    request = request_for()

    merged, llm = await ask(base_state(request), request)

    assert llm.requests == []
    [question] = merged["questions"].values()
    assert question.problemRef == PROBLEM
    assert question.requestedFields == ["taskOrScreen", "expectedBehavior", "actualBehavior"]
    assert question.bundleRef == f"inquiry-{PROBLEM}-1"


async def test_the_ask_action_counts_from_the_servers_question_count() -> None:
    request = request_for(question_count=2)

    merged, _ = await ask(base_state(request), request)

    assert next(iter(merged["questions"].values())).bundleRef == f"inquiry-{PROBLEM}-3"


async def test_the_ask_action_is_rejected_when_there_is_nothing_to_ask() -> None:
    complete = make_details().model_copy(
        update={
            "taskOrScreen": "문서 조회",
            "expectedBehavior": "목록",
            "actualBehavior": "안 보임",
            "occurredAt": "어제",
        }
    )
    request = request_for()
    exhausted = request_for(question_count=5)

    with pytest.raises(OutputRejected):
        await ask(base_state(request, details=complete), request)
    with pytest.raises(OutputRejected):
        await ask(base_state(exhausted), exhausted)
    with pytest.raises(OutputRejected):
        await ask(base_state(request), request, targets=(PROBLEM, "new:1"))


# --- 합법 행동 입력 어댑터 ---


def test_facts_of_a_fresh_symptom_with_a_known_system() -> None:
    facts = problem_facts(base_state(), PROBLEM)

    assert (facts.out_of_scope, facts.searchable, facts.system_known) == (False, True, True)
    assert facts.search_state is None and facts.verdict is None
    assert (facts.askable, facts.required_info_secured) == (True, False)
    assert facts.remaining_questions == 5 and facts.direct_ticket_intent is False
    assert legal_actions(facts) == {"SEARCH", "ASK"}


def test_facts_reflect_the_servers_question_count_and_the_intent_policy() -> None:
    exhausted = problem_facts(base_state(request_for(question_count=5)), PROBLEM)
    access = problem_facts(base_state(request_for("ACCESS_REQUEST", "SERVICE_REQUEST")), PROBLEM)

    assert exhausted.remaining_questions == 0 and exhausted.askable is False
    assert access.direct_ticket_intent is True and access.searchable is True
    assert (
        problem_facts(
            base_state(request_for("HOW_TO", "KNOWLEDGE_QA")), PROBLEM
        ).direct_ticket_intent
        is False
    )


def test_facts_of_an_intent_that_never_searches_or_is_out_of_scope() -> None:
    feature = problem_facts(base_state(request_for("FEATURE_REQUEST", "CHANGE_REQUEST")), PROBLEM)
    closed = problem_facts(base_state(request_for("OUT_OF_SCOPE", "OUT_OF_SCOPE")), PROBLEM)

    assert feature.searchable is False and feature.direct_ticket_intent is True
    assert closed.out_of_scope is True
    assert legal_actions(closed) == {"CLOSE"}


def test_facts_of_an_unknown_system_and_a_declared_unknown_one() -> None:
    unknown = base_state(request_for(system=None))
    declared = base_state(request_for(system=None), unknowns=["보고 시스템"])

    assert problem_facts(unknown, PROBLEM).system_known is False
    assert problem_facts(unknown, PROBLEM).system_declared_unknown is False
    assert problem_facts(declared, PROBLEM).system_declared_unknown is True
    assert legal_actions(problem_facts(declared, PROBLEM)) == {"ASK", "HOLD"}


def test_answer_needs_a_sufficient_verdict_and_a_guide_built_from_the_cited_evidence() -> None:
    state = with_assessment(with_guide(base_state(request_for("HOW_TO", "KNOWLEDGE_QA"))))

    assert answerable(state, PROBLEM) is True
    assert problem_facts(state, PROBLEM).employee_evidence is True
    other = make_evidence(id="00000000-0000-0000-0000-0000000000aa")
    assert (
        answerable(with_assessment(with_guide(base_state()), cited=(other.id,)), PROBLEM) is False
    )
    assert answerable(with_assessment(base_state()), PROBLEM) is False, "안내가 없으면 답할 수 없다"
    assert answerable(with_assessment(with_guide(base_state()), "PARTIAL"), PROBLEM) is False


async def searched_state(*hits, request=None):
    request = request or request_for("HOW_TO", "KNOWLEDGE_QA")
    agents = {AgentRole.KNOWLEDGE: SearchKnowledgeAgent(RecordingSource(hits))}
    return await execute_role(
        {**base_state(request), "analysis": {}},
        AgentRole.KNOWLEDGE,
        knowledge_services(request),
        agents,
        targets=(PROBLEM,),
    )


async def test_facts_after_a_search_know_the_state_and_switch_to_handoff_fields() -> None:
    request = request_for("HOW_TO", "KNOWLEDGE_QA")
    details = make_details().model_copy(
        update={"taskOrScreen": "문서 조회", "currentWorkflow": None}
    )
    before = problem_facts(base_state(request, details=details), PROBLEM)
    assert before.required_info_secured is True and before.askable is False  # 검색에 필요한 정보뿐

    state = await searched_state(request=request)
    state = {
        **state,
        "problems": {PROBLEM: state["problems"][PROBLEM].model_copy(update={"details": details})},
    }
    after = problem_facts(state, PROBLEM)

    assert after.search_state == "EMPTY" and after.searchable is True
    assert (
        after.askable is True and after.required_info_secured is False
    )  # 담당자 전달에 필요한 정보
    assert legal_actions(after) == {"ASK"}


async def test_the_latest_search_result_of_the_problem_is_the_one_that_counts() -> None:
    request = request_for("HOW_TO", "KNOWLEDGE_QA")
    state = await searched_state(request=request)
    assert problem_facts(state, PROBLEM).search_state == "EMPTY"
    later = KnowledgeResult(
        invocationId="later",
        search=SearchResult(state="FOUND", evidence=[make_evidence()], error=None),
        guide=None,
        problemRef=PROBLEM,
        queryDigest="digest-2",
    )

    state = {**state, "roleResults": {**state["roleResults"], "later": later}}

    assert problem_facts(state, PROBLEM).search_state == "FOUND"


# --- 질문 가능 여부와 질문 행동이 어긋나지 않는다 ---


@pytest.mark.parametrize(
    "state_factory",
    [
        lambda: base_state(),
        lambda: base_state(request_for(system=None)),
        lambda: base_state(request_for(question_count=5)),
        lambda: base_state(request_for("ACCESS_REQUEST", "SERVICE_REQUEST")),
        lambda: base_state(request_for("HOW_TO", "KNOWLEDGE_QA")),
        lambda: base_state(request_for("UNKNOWN", "REVIEW_REQUIRED")),
        lambda: base_state(request_for("FEATURE_REQUEST", "CHANGE_REQUEST")),
        lambda: base_state(
            request_for(),
            details=make_details().model_copy(
                update={
                    "taskOrScreen": "문서 조회",
                    "expectedBehavior": "목록",
                    "actualBehavior": "안 보임",
                    "occurredAt": "어제",
                }
            ),
        ),
    ],
)
def test_ask_is_legal_exactly_when_a_question_can_be_built(state_factory) -> None:
    state = state_factory()
    facts = problem_facts(state, PROBLEM)
    can_build = True
    try:
        build_question(state, PROBLEM, searched=facts.search_state is not None)
    except OutputRejected:
        can_build = False

    assert ("ASK" in legal_actions(facts)) == can_build


# --- 정책 표 ---


def test_direct_ticket_intents_are_access_feature_and_improvement_requests() -> None:
    direct = {intent for intent, policy in INTENT_POLICIES.items() if policy.ticket_when_ready}

    assert direct == {"ACCESS_REQUEST", "FEATURE_REQUEST", "IMPROVEMENT_REQUEST"}
