"""Supervisor 선택지 계산(supervisor.choices).

1) 변경 전 규칙(역할의 available_for + 파이프라인의 selectable_roles)을 그대로 옮긴 기준과
   같은 State에서 같은 선택지가 나오는지 비교한다.
2) 최근 fix로 막은 "이 상태에서 이 역할을 고르지 못하게" 상황을 하나씩 고정한다.
모델은 부르지 않는다. 통과가 AI의 동작 품질을 뜻하지 않는다.
"""

import itertools

import pytest

from hops_ai.agents.base import Agent
from hops_ai.agents.inquiry.readiness import (
    all_unfinished_ready_for_handoff,
    inquiry_has_nothing_left_to_do,
)
from hops_ai.agents.knowledge.agent import guidable_problem
from hops_ai.agents.registry import build_registry
from hops_ai.agents.ticket.model import ModelTicketAgent
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.wire import GuideProposal, QuestionProposal
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.supervisor.choices import Choices, compute_choices, implemented_roles
from hops_ai.supervisor.decision import decision_schema
from hops_ai.supervisor.policy import get_allowed_roles
from tests.supervisor_fixtures import (
    make_details,
    make_evidence,
    make_problem,
    make_request,
    running_state,
)
from tests.unit.test_handoff_readiness import how_to
from tests.unit.test_inquiry_availability import (
    COMPLETE,
    at_question_limit_request,
    with_inquiry_run,
)
from tests.unit.test_knowledge_empty_handoff import empty_search, error_search, state_after
from tests.unit.test_knowledge_wiring import how_to_first_message

KNOWLEDGE_CONNECTED = build_registry(FixtureKnowledgeSource())
KNOWLEDGE_PENDING = build_registry()


def legacy_roles(state, agents: dict[AgentRole, Agent]) -> set[AgentRole]:
    """변경 전 선택 규칙의 복사본: 역할이 말하는 available_for와 파이프라인의 selectable_roles.

    같은 계산을 역할별 메서드가 아니라 한 곳(supervisor.choices)이 하도록 옮겼으므로,
    이 기준과 결과가 같아야 한다.
    """
    kind = state["input"].kind
    roles = set(implemented_roles(agents))
    if AgentRole.INQUIRY in roles and kind == "INQUIRY_ANALYSIS":
        if all_unfinished_ready_for_handoff(state) or inquiry_has_nothing_left_to_do(state):
            roles.discard(AgentRole.INQUIRY)
    if AgentRole.KNOWLEDGE in roles and not (
        kind == RunKind.INQUIRY_ANALYSIS and guidable_problem(state) is not None
    ):
        roles.discard(AgentRole.KNOWLEDGE)
    if AgentRole.TICKET in roles and not ModelTicketAgent.eligible_problems(state):
        roles.discard(AgentRole.TICKET)
    return roles & set(agents) & get_allowed_roles(RunKind(kind))


def symptom(ref: str = "new:1"):
    return make_problem(ref).model_copy(update={"details": make_details()})


def corpus() -> list[tuple[str, dict]]:
    """여러 모양의 State. 문제 유무·유형, 검색 결과, Inquiry 실행, 안내·질문, 질문 한도, 사례."""
    problem_sets = {
        "no-problem": (),
        "how-to-complete": (how_to(**COMPLETE),),
        "how-to-partial": (how_to(taskOrScreen="결재선 변경"),),
        "how-to-no-system": (
            how_to(**COMPLETE).model_copy(update={"reportedSystemCandidateId": None}),
        ),
        "how-to-and-symptom": (how_to(**COMPLETE), symptom("new:2")),
        "symptom": (symptom(),),
    }
    searches = {"none": None, "empty": empty_search(), "error": error_search()}
    states = []
    for (pname, problems), (sname, search) in itertools.product(
        problem_sets.items(), searches.items()
    ):
        base = state_after(search, *problems)
        states.append((f"{pname}/{sname}", base))
        if problems:
            ref = next(iter(base["problems"]))
            states.append(
                (f"{pname}/{sname}/inquiry-ran-asked", with_inquiry_run(base, questions=[ref]))
            )
            guide = GuideProposal(
                messageText="안내", evidenceIds=[make_evidence().id], problemRef=ref
            )
            states.append(
                (f"{pname}/{sname}/inquiry-ran-guided", with_inquiry_run(base, guide=guide))
            )
    states.append(("first-message", running_state(how_to_first_message())))
    states.append(("existing-work", running_state(make_request(outcome="STORED"))))
    states.append(("question-limit", running_state(at_question_limit_request())))
    states.append(("case-draft", running_state(make_request(kind=RunKind.CASE_DRAFT))))
    return states


CORPUS = corpus()


@pytest.mark.parametrize("agents", [KNOWLEDGE_CONNECTED, KNOWLEDGE_PENDING], ids=["wired", "bare"])
@pytest.mark.parametrize(("label", "state"), CORPUS, ids=[label for label, _ in CORPUS])
def test_same_state_gives_the_same_roles_as_the_rules_before_the_move(label, state, agents) -> None:
    choices = compute_choices(state, dict(agents), restrict=True)

    assert set(choices.roles) == legacy_roles(state, dict(agents)), label


@pytest.mark.parametrize(("label", "state"), CORPUS, ids=[label for label, _ in CORPUS])
def test_without_restriction_every_registered_role_of_the_run_kind_is_offered(label, state) -> None:
    agents = dict(KNOWLEDGE_PENDING)

    choices = compute_choices(state, agents, restrict=False)

    assert set(choices.roles) == set(agents) & get_allowed_roles(RunKind(state["input"].kind))


def test_the_corpus_covers_every_role_being_offered_and_withheld() -> None:
    offered = {
        role
        for _, state in CORPUS
        for role in compute_choices(state, dict(KNOWLEDGE_CONNECTED)).roles
    }
    withheld = {
        role
        for _, state in CORPUS
        for role in set(KNOWLEDGE_CONNECTED)
        - set(compute_choices(state, dict(KNOWLEDGE_CONNECTED)).roles)
    }

    assert {AgentRole.INQUIRY, AgentRole.KNOWLEDGE, AgentRole.TICKET, AgentRole.CURATOR} <= (
        offered | withheld
    )
    assert {AgentRole.INQUIRY, AgentRole.KNOWLEDGE, AgentRole.TICKET} <= offered
    assert {AgentRole.INQUIRY, AgentRole.KNOWLEDGE, AgentRole.TICKET} <= withheld


# --- 최근 fix 7건이 막은 상황. 이 상황에서 이 역할을 고를 수 없어야 한다 ---


def roles(state, agents=KNOWLEDGE_CONNECTED) -> set[AgentRole]:
    return set(compute_choices(state, dict(agents)).roles)


def test_bd05f8e_first_message_without_a_problem_does_not_offer_knowledge() -> None:
    state = running_state(how_to_first_message())

    assert roles(state) == {AgentRole.INQUIRY}


def test_0dac452_nothing_left_to_ask_after_an_empty_search_does_not_offer_inquiry() -> None:
    state = state_after(empty_search(), how_to(**COMPLETE))

    assert roles(state) == {AgentRole.TICKET}


def test_cad9dae_every_problem_with_an_outcome_does_not_offer_inquiry_again() -> None:
    base = state_after(
        None,
        how_to(taskOrScreen="문서 조회"),
        how_to(taskOrScreen="로그인").model_copy(
            update={"problemRef": "new:2", "processingType": "INVESTIGATION"}
        ),
    )
    guide = GuideProposal(messageText="안내", evidenceIds=[make_evidence().id], problemRef="new:1")
    state = with_inquiry_run(base, questions=["new:2"], guide=guide)

    assert roles(state) == set()


def test_af5ab24_a_problem_at_the_question_limit_does_not_offer_inquiry() -> None:
    state = {
        **running_state(at_question_limit_request()),
        "roleResults": {
            "inv-k": empty_search(problem_ref=next(iter(at_question_limit_request().problems)).id)
        },
    }

    assert AgentRole.INQUIRY not in roles(state)
    assert AgentRole.TICKET in roles(state)


def test_c4aa05d_a_ready_investigation_goes_to_ticket_instead_of_repeating_inquiry() -> None:
    details = make_details().model_copy(
        update={
            "taskOrScreen": "로그인 화면",
            "expectedBehavior": "로그인 성공",
            "actualBehavior": "인증 오류",
            "occurredAt": "오늘 오전 9시",
        }
    )
    ready = make_problem("new:1").model_copy(update={"details": details})
    state = with_inquiry_run(state_after(None, ready))

    assert AgentRole.INQUIRY not in roles(state)
    assert AgentRole.TICKET in roles(state)


def test_e753fb5_ticket_waits_while_a_question_is_pending_but_not_for_other_ready_problems() -> (
    None
):
    asked = symptom("new:1")
    state = state_after(None, asked)
    state = {
        **state,
        "questions": {
            ("new:1", "q"): QuestionProposal(
                problemRef="new:1",
                bundleRef="q",
                text="언제인가요?",
                requestedFields=["occurredAt"],
            )
        },
    }
    assert AgentRole.TICKET not in roles(state)

    with_other = state_after(None, asked, symptom("new:2"))
    with_other = {**with_other, "questions": state["questions"]}
    assert AgentRole.TICKET in roles(with_other)


def test_3f85ccf_the_decision_schema_lists_only_the_roles_of_the_choices() -> None:
    state = state_after(empty_search(), how_to(**COMPLETE))
    choices: Choices = compute_choices(state, dict(KNOWLEDGE_CONNECTED))

    schema = decision_schema(sorted(role.value for role in choices.roles))

    enum = [option for option in schema["properties"]["role"]["anyOf"] if "enum" in option]
    assert enum == [{"type": "string", "enum": ["TICKET"]}]
