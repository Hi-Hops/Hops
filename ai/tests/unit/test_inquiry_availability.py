"""할 일이 없는 Inquiry 재호출을 선택지에서 빼는 규칙.

실제 모델 시험에서 Knowledge가 근거를 못 찾은 뒤 담당자 전달 정보가 이미 갖춰졌는데도
Supervisor가 Inquiry를 반복 호출해 모델 호출 한도를 넘겼다. 그 상태의 Inquiry는 같은 원문에서
같은 사실을 다시 뽑을 뿐이다. 할 일이 없음이 증명되는 경우만 빼며, 호출이 성립하는 역할 사이의
선택은 제한하지 않는다. 모델은 Fake이므로 실제 모델의 판단은 검증하지 않는다.
"""

import json

import pytest

from hops_ai.agents.inquiry.extraction import InquiryExtraction, QuotedValue
from hops_ai.agents.inquiry.readiness import get_handoff_readiness
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.rules.progress import get_problem_progress
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.supervisor.choices import compute_choices
from hops_ai.supervisor.graph import Supervisor
from tests.doubles.agents import runtime_registry, ticket_decisions
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import (
    MESSAGE,
    make_details,
    make_evidence,
    make_problem,
    make_request,
    running_state,
)
from tests.unit.test_handoff_readiness import how_to
from tests.unit.test_knowledge_empty_handoff import (
    empty_search,
    error_search,
    how_to_extraction,
    no_guide_request,
    services_for,
    state_after,
)
from tests.unit.test_knowledge_wiring import decision

COMPLETE = {"taskOrScreen": "결재선 변경", "expectedBehavior": "한꺼번에 바꾸고 싶어요"}


def roles_for(state) -> set[AgentRole]:
    agents = runtime_registry(FixtureKnowledgeSource())
    return set(compute_choices(state, agents).roles)


def test_inquiry_is_hidden_when_nothing_is_missing_after_an_empty_search() -> None:
    state = state_after(empty_search(), how_to(**COMPLETE))

    assert roles_for(state) == {AgentRole.TICKET}


def test_inquiry_stays_while_something_is_still_missing() -> None:
    state = state_after(empty_search(), how_to(taskOrScreen="결재선 변경"))

    assert AgentRole.INQUIRY in roles_for(state)


def test_inquiry_stays_before_the_search_even_with_complete_details() -> None:
    state = state_after(None, how_to(**COMPLETE))

    assert AgentRole.INQUIRY in roles_for(state)


def test_inquiry_stays_after_a_search_error() -> None:
    state = state_after(error_search(), how_to(**COMPLETE))

    assert AgentRole.INQUIRY in roles_for(state)


def test_inquiry_stays_when_another_kind_of_problem_is_still_unfinished() -> None:
    symptom = make_problem("new:2").model_copy(update={"details": make_details()})
    state = state_after(empty_search(), how_to(**COMPLETE), symptom)

    assert AgentRole.INQUIRY in roles_for(state)


def test_item_the_user_said_they_do_not_know_does_not_keep_inquiry_available() -> None:
    problem = how_to(taskOrScreen="결재선 변경").model_copy(update={"unknowns": ["기대한 동작"]})
    state = state_after(empty_search(), problem)

    assert AgentRole.INQUIRY not in roles_for(state)


def test_problem_without_a_system_keeps_inquiry_available() -> None:
    problem = how_to(**COMPLETE).model_copy(update={"reportedSystemCandidateId": None})
    state = state_after(empty_search(), problem)

    assert AgentRole.INQUIRY in roles_for(state)


def test_state_without_any_problem_keeps_inquiry_available() -> None:
    state = state_after(empty_search())

    assert AgentRole.INQUIRY in roles_for(state)


@pytest.mark.parametrize("processing_type", ["INVESTIGATION", "REVIEW_REQUIRED"])
def test_only_how_to_problems_count_as_ready(processing_type) -> None:
    problem = how_to(**COMPLETE).model_copy(update={"processingType": processing_type})
    state = state_after(empty_search(), problem)

    assert AgentRole.INQUIRY in roles_for(state)


async def test_supervisor_after_an_empty_search_no_longer_offers_inquiry_when_ready() -> None:
    request = no_guide_request()
    quote = QuotedValue(messageId=MESSAGE, quote="도장 이미지 등록")
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(desired=quote),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.TICKET),
        ticket_decisions("new:1"),
        decision("PROPOSE_TICKETS"),
    )
    agents = runtime_registry(FixtureKnowledgeSource())
    supervisor = Supervisor(agents, restrict_roles=True)

    result = await supervisor.analyze(request, services_for(request, llm))

    assert result.status == "SUCCEEDED", result.error
    after_empty = json.loads(llm.requests[3].messages[-1].content)
    assert after_empty["allowedRoles"] == ["TICKET"]
    assert after_empty["handoffReadiness"][0]["missingForHandoff"] == []
    # 검색 전과 첫 분석 전에는 Inquiry가 선택지에 있다.
    assert "INQUIRY" in json.loads(llm.requests[2].messages[-1].content)["allowedRoles"]
    assert "INQUIRY" in json.loads(llm.requests[0].messages[-1].content)["allowedRoles"]


# --- Inquiry가 이미 돌았고 모든 문제에 결과가 있으면 다시 부르지 않는다 ---


def with_inquiry_run(state, *, questions=(), guide=None):
    """Inquiry가 이미 한 번 돌았고 질문이나 안내가 State에 쌓인 모습."""
    from hops_ai.contracts.roles import InquiryResult
    from hops_ai.contracts.wire import QuestionProposal

    result = InquiryResult(
        invocationId="inv-i", problems=list(state["problems"].values()), questions=[]
    )
    asked = {
        (ref, f"inquiry-{ref.replace(':', '-')}-1"): QuestionProposal(
            problemRef=ref,
            bundleRef=f"inquiry-{ref.replace(':', '-')}-1",
            text="질문",
            requestedFields=["expectedBehavior"],
        )
        for ref in questions
    }
    return {
        **state,
        "roleResults": {**state["roleResults"], "inv-i": result},
        "questions": asked,
        "guide": guide,
    }


def test_inquiry_is_hidden_when_every_problem_already_has_an_outcome() -> None:
    from hops_ai.contracts.wire import GuideProposal

    base = state_after(
        None,
        how_to(taskOrScreen="문서 조회"),
        how_to(taskOrScreen="로그인").model_copy(
            update={"problemRef": "new:2", "processingType": "INVESTIGATION"}
        ),
    )
    guide = GuideProposal(messageText="안내", evidenceIds=[make_evidence().id], problemRef="new:1")
    state = with_inquiry_run(base, questions=["new:2"], guide=guide)

    assert AgentRole.INQUIRY not in roles_for(state)


def test_inquiry_stays_while_a_problem_still_has_no_outcome() -> None:
    base = state_after(
        None,
        how_to(taskOrScreen="문서 조회"),
        how_to(taskOrScreen="로그인").model_copy(
            update={"problemRef": "new:2", "processingType": "INVESTIGATION"}
        ),
    )
    state = with_inquiry_run(base, questions=["new:2"])

    assert AgentRole.INQUIRY in roles_for(state)


def test_inquiry_is_not_hidden_in_a_run_where_it_has_not_run_yet() -> None:
    # 기존 결과(저장된 티켓)만 있는 후속 실행의 첫 판단에서는 새 메시지를 분석해야 한다.
    state = running_state(make_request(outcome="STORED"))

    assert [item["outcome"] for item in get_problem_progress(state)] == ["EXISTING_WORK"]
    assert AgentRole.INQUIRY in roles_for(state)


@pytest.mark.parametrize("missing", [False, True])
def test_analyzed_investigation_only_keeps_inquiry_when_required_information_is_missing(missing):
    details = make_details().model_copy(
        update={
            "taskOrScreen": "로그인 화면",
            "expectedBehavior": "로그인 성공",
            "actualBehavior": "인증 오류",
            "occurredAt": None if missing else "오늘 오전 9시",
        }
    )
    problem = make_problem("new:1").model_copy(update={"details": details})
    state = with_inquiry_run(state_after(None, problem))
    assert (AgentRole.INQUIRY in roles_for(state)) == missing
    assert AgentRole.TICKET in roles_for(state)


def test_ready_investigation_does_not_hide_inquiry_for_another_unfinished_problem():
    ready = make_problem("new:1").model_copy(
        update={"details": make_details().model_copy(update={"occurredAt": "오늘 오전 9시"})}
    )
    state = with_inquiry_run(
        state_after(None, ready, how_to().model_copy(update={"problemRef": "new:2"}))
    )
    assert AgentRole.INQUIRY in roles_for(state)


async def test_supervisor_after_a_guide_and_a_question_offers_no_inquiry() -> None:
    from tests.unit.test_composite_inquiry import _extracted, analyze

    result, llm = await analyze(
        "전자결재 문서 조회 방법을 알려주세요. 그리고 첨부파일이 열리지 않아요",
        [
            _extracted(
                "문서 조회 방법 문의", "HOW_TO", "ELECTRONIC_APPROVAL", task="문서 조회 방법"
            ),
            _extracted(
                "첨부파일 열람 실패",
                "OBSERVED_SYMPTOM",
                "ELECTRONIC_APPROVAL",
                task="첨부파일",
                actual="첨부파일이 열리지 않아요",
            ),
        ],
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("ASK_USER"),
    )

    assert result.status == "SUCCEEDED", result.error
    assert result.guide is not None and [q.problemRef for q in result.questions] == ["new:2"]
    after_search = json.loads(llm.requests[3].messages[-1].content)
    # 안내와 질문으로 모든 문제가 처리됐으므로 Inquiry·Knowledge·Ticket 모두 선택지에 없다.
    assert after_search["allowedRoles"] == []
    first = json.loads(llm.requests[0].messages[-1].content)
    assert "INQUIRY" in first["allowedRoles"]


# --- 질문 한도에 도달해 더 묻지 못하는 문제 (코드 리뷰 지적) ---


def at_question_limit_request(text: str = "전자결재 도장 이미지 등록 방법을 알려주세요"):
    """서버가 질문을 이미 5번 했다고 알린 기존 사용법 문제 하나."""
    from tests.unit.test_knowledge_agent import how_to_request

    fields = how_to_request(text, summary="도장 이미지 등록 방법 문의").model_dump(mode="json")
    fields["problems"][0]["questionCount"] = 5
    return AiRunRequest.model_validate(fields)


def test_inquiry_is_hidden_when_a_problem_cannot_be_asked_anything_more() -> None:
    from tests.supervisor_fixtures import PROBLEM

    request = at_question_limit_request()
    state = {
        **running_state(request),
        "roleResults": {"inv-k": empty_search(problem_ref=PROBLEM)},
    }

    [item] = get_handoff_readiness(state)
    # 전달에 필요한 항목이 아직 비었지만 질문 한도(5회)에 도달해 더 물을 수 없다.
    assert item["missingForHandoff"] and item["remainingQuestions"] == 0
    assert AgentRole.INQUIRY not in roles_for(state)
    assert AgentRole.TICKET in roles_for(state)


def test_inquiry_stays_while_questions_remain_for_the_missing_items() -> None:
    from tests.supervisor_fixtures import PROBLEM

    request = at_question_limit_request()
    fields = request.model_dump(mode="json")
    fields["problems"][0]["questionCount"] = 4
    state = {
        **running_state(AiRunRequest.model_validate(fields)),
        "roleResults": {"inv-k": empty_search(problem_ref=PROBLEM)},
    }

    [item] = get_handoff_readiness(state)
    assert item["missingForHandoff"] and item["remainingQuestions"] == 1
    assert AgentRole.INQUIRY in roles_for(state)


async def test_run_at_the_question_limit_ends_in_a_review_ticket() -> None:
    from tests.supervisor_fixtures import PROBLEM
    from tests.unit.test_composite_inquiry import _extracted
    from tests.unit.test_knowledge_empty_handoff import services_for as handoff_services

    request = at_question_limit_request()
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        InquiryExtraction(
            problems=[
                _extracted(
                    "도장 이미지 등록 방법 문의",
                    "HOW_TO",
                    "ELECTRONIC_APPROVAL",
                    task="도장 이미지 등록",
                )
            ]
        ),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("CALL_ROLE", AgentRole.TICKET),
        ticket_decisions(PROBLEM),
        decision("PROPOSE_TICKETS"),
    )
    agents = runtime_registry(FixtureKnowledgeSource())
    supervisor = Supervisor(agents, restrict_roles=True)

    result = await supervisor.analyze(request, handoff_services(request, llm))

    assert result.status == "SUCCEEDED", result.error
    assert [t.processingType for t in result.ticketProposals] == ["REVIEW_REQUIRED"]
    after_search = json.loads(llm.requests[3].messages[-1].content)
    # 검색 뒤 더 물을 수 없으므로 Inquiry와 이미 검색한 Knowledge가 모두 선택지에서 빠진다.
    assert after_search["allowedRoles"] == ["TICKET"]
