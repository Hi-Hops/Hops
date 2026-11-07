"""안내 뒤 사용자가 미해결로 응답한 문제(#189 ④). 모델은 Fake다.

이미 안내한 근거로 다시 안내하지 않고, 다른 근거가 없으면 담당자 검토로 넘기는지를 규칙으로 본다.
다른 근거가 실제로 좋은 안내인지, 실제 모델이 이 규칙 안에서 잘 고르는지는 검증하지 않는다.
"""

from dataclasses import replace

import pytest

from evaluation.harness.behavior import check_b10
from evaluation.harness.checks import check_trace
from evaluation.harness.runner import RunConfig, run_scenario
from evaluation.harness.scenario import parse_scenario
from evaluation.harness.spring_stub import SpringStub
from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import InquiryResult, KnowledgeResult
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.rules.legal_actions import legal_actions
from hops_ai.rules.resolution import guided_documents, is_unresolved, without_guided
from hops_ai.supervisor.execution import execute_role
from hops_ai.supervisor.facts import problem_facts
from tests.eval_fixtures import ai_result, request_with, run_trace, turn
from tests.supervisor_fixtures import PROBLEM, make_details, make_evidence, running_state, uid
from tests.unit.test_eval_runner import (
    AGENTS,
    ScriptedInquiry,
    ScriptedKnowledge,
    decision,
    problem,
)
from tests.unit.test_eval_user_sim import SCENARIO
from tests.unit.test_knowledge_agent import RecordingSource, how_to_request
from tests.unit.test_knowledge_assessment import GUIDE, draft, hit, services_with

NEW_GUIDE = make_evidence(id=uid(80), documentId=uid(81), title="새 안내")
NEW_VERSION = make_evidence(documentVersion=2, id=uid(82))  # 같은 문서의 새 버전


def unresolved_request(
    guided=(GUIDE,), *, state: str | None = "UNRESOLVED", with_request: bool = True
) -> AiRunRequest:
    """안내 뒤 미해결 응답으로 접수한 재분석 입력. guided는 이미 안내한 근거다."""
    fields = how_to_request().model_dump(mode="json")
    target = fields["problems"][0]
    target["state"] = "ANALYZING"
    if state is not None:
        target["resolutionContext"] = {
            "requestId": str(uid(90)),
            "source": "GUIDE",
            "sourceId": str(uid(91)),
            "sourceVersion": 1,
            "state": state,
        }
    if with_request:
        target["standardRequest"] = {
            "summary": "문서 조회 방법 문의",
            "confirmedFacts": [],
            "assumptions": [],
            "unknowns": [],
            "responsibleSystemCandidateId": None,
            "responsibilityEvidenceIds": [],
            "details": make_details().model_dump(mode="json"),
            "knowledge": [item.model_dump(mode="json") for item in guided],
        }
    return AiRunRequest.model_validate(fields)


# -- 규칙 -------------------------------------------------------------------------------------


def test_only_a_problem_marked_unresolved_by_the_server_is_unresolved() -> None:
    assert is_unresolved(running_state(unresolved_request()), PROBLEM)
    assert not is_unresolved(running_state(unresolved_request(state="PENDING")), PROBLEM)
    assert not is_unresolved(running_state(unresolved_request(state=None)), PROBLEM)


def test_guided_documents_are_the_documents_and_versions_in_the_request() -> None:
    state = running_state(unresolved_request())

    assert guided_documents(state, PROBLEM) == {(GUIDE.documentId, 1)}


def test_nothing_is_excluded_without_a_standard_request_or_unresolved_mark() -> None:
    assert guided_documents(running_state(unresolved_request(with_request=False)), PROBLEM) == set()
    assert guided_documents(running_state(unresolved_request(state=None)), PROBLEM) == set()


def test_a_new_version_of_the_same_document_is_not_excluded() -> None:
    state = running_state(unresolved_request())

    kept = without_guided(state, PROBLEM, [GUIDE, NEW_VERSION, NEW_GUIDE], lambda item: item)

    assert kept == [NEW_VERSION, NEW_GUIDE]


# -- Knowledge 검색 ---------------------------------------------------------------------------


async def search(request: AiRunRequest, *hits, outcomes=()):
    services, llm = services_with(request, *outcomes)
    agents = {
        AgentRole.KNOWLEDGE: SearchKnowledgeAgent(RecordingSource(hits), assess_evidence=True)
    }
    state = await execute_role(running_state(request), AgentRole.KNOWLEDGE, services, agents)
    result = next(
        item for item in state["roleResults"].values() if isinstance(item, KnowledgeResult)
    )
    return state, result, llm


async def test_the_guide_already_given_is_not_found_again_and_costs_no_model_call() -> None:
    state, result, llm = await search(unresolved_request(), hit(GUIDE))

    assert result.search.state == "EMPTY" and result.search.error is None
    assert state["guide"] is None
    assert state["assessments"][PROBLEM].verdict == "NO_MATCH"
    assert llm.requests == []


async def test_a_different_document_is_still_found_and_can_be_guided() -> None:
    state, result, llm = await search(
        unresolved_request(),
        hit(GUIDE),
        hit(NEW_GUIDE),
        outcomes=(draft(supportingEvidenceIds=[NEW_GUIDE.id]),),
    )

    assert [item.id for item in result.search.evidence] == [NEW_GUIDE.id]
    assert state["guide"] is not None and state["guide"].evidenceIds == [NEW_GUIDE.id]
    assert len(llm.requests) == 1


async def test_without_the_unresolved_mark_the_same_guide_is_found_as_before() -> None:
    state, result, _ = await search(unresolved_request(state=None), hit(GUIDE), outcomes=(draft(),))

    assert [item.id for item in result.search.evidence] == [GUIDE.id]
    assert state["guide"] is not None


# -- 합법 행동 (c) ----------------------------------------------------------------------------


def searched_state(request: AiRunRequest, search_state: str):
    from tests.unit.test_knowledge_empty_handoff import empty_search, error_search

    state = running_state(request)
    item = (
        error_search(problem_ref=PROBLEM)
        if search_state == "ERROR"
        else empty_search(problem_ref=PROBLEM)
    )
    return {**state, "roleResults": {item.invocationId: item}}


def test_before_the_search_the_unresolved_problem_still_searches() -> None:
    facts = problem_facts(running_state(unresolved_request()), PROBLEM)

    assert facts.unresolved_without_other_evidence is False
    assert "SEARCH" in legal_actions(facts)


def test_after_a_search_with_nothing_new_the_only_legal_action_is_a_ticket() -> None:
    facts = problem_facts(searched_state(unresolved_request(), "EMPTY"), PROBLEM)

    assert facts.unresolved_without_other_evidence is True
    assert legal_actions(facts) == {"TICKET"}


def test_a_search_error_is_not_treated_as_nothing_new() -> None:
    facts = problem_facts(searched_state(unresolved_request(), "ERROR"), PROBLEM)

    assert facts.unresolved_without_other_evidence is False


def test_a_problem_that_is_not_unresolved_keeps_the_normal_rules() -> None:
    facts = problem_facts(searched_state(unresolved_request(state=None), "EMPTY"), PROBLEM)

    assert facts.unresolved_without_other_evidence is False
    assert "TICKET" in legal_actions(replace(facts, askable=False))


# -- 하네스: 미해결 응답 재생 ---------------------------------------------------------------------


def guided_turn():
    """첫 실행에서 문제를 만들고 안내한 결과와 그 입력."""
    harness = SpringStub(parse_scenario({**SCENARIO, "unknownFields": []}))
    request = harness.first_request()
    source = request.messages[0]
    proposal = problem("new:1", request, source.id, source.content)
    result = ai_result(
        request,
        answer="문서함에서 기간을 늘려 보세요.",
        problems=[proposal.model_dump(mode="json")],
        guide={
            "problemRef": "new:1",
            "messageText": "기간을 늘려 보세요.",
            "evidenceIds": [GUIDE.id],
        },
        evidence_items=[GUIDE],
    )
    return harness, request, result


def test_the_stub_builds_the_reanalysis_input_spring_would_send() -> None:
    harness, request, result = guided_turn()

    next_request, ref_map = harness.follow_up(result)

    [item] = next_request.problems
    assert item.id == ref_map[0][1] and next_request.problemIds == [item.id]
    assert item.state == "ANALYZING"
    assert item.resolutionContext is not None and item.resolutionContext.state == "UNRESOLVED"
    assert item.resolutionContext.source == "GUIDE"
    assert [(k.documentId, k.documentVersion) for k in item.standardRequest.knowledge] == [
        (GUIDE.documentId, GUIDE.documentVersion)
    ]
    assert (
        next_request.inquiryVersion == request.inquiryVersion
    )  # 미해결 응답은 입력 버전을 올리지 않는다
    assert (
        next_request.runId != request.runId
        and next_request.answerMessageId != request.answerMessageId
    )
    assert [m.sender for m in next_request.messages] == [
        "USER",
        "AI",
    ]  # 사용자 메시지가 늘지 않는다
    assert item.resolutionContext.sourceId == next_request.messages[-1].id


def test_the_stub_refuses_to_replay_an_unresolved_reply_without_a_guide() -> None:
    harness = SpringStub(parse_scenario(SCENARIO))
    request = harness.first_request()

    with pytest.raises(ValueError):
        harness.follow_up(ai_result(request, answer="질문입니다."))


class FollowUpInquiry(ScriptedInquiry):
    """미해결 재분석에서는 이전 확인 사실을 그대로 이어받는다."""

    async def run(self, request, services):
        data = request.state["input"]
        if data.problems and data.problems[0].resolutionContext is not None:
            earlier = data.problems[0]
            kept = problem(earlier.id, data, data.messages[0].id, data.messages[0].content)
            kept = kept.model_copy(update={"facts": earlier.standardRequest.confirmedFacts})
            return InquiryResult(invocationId=request.invocation_id, problems=[kept], questions=[])
        return await super().run(request, services)


FOLLOW_UP_SCENARIO = {
    **SCENARIO,
    "unknownFields": [],
    "followUp": {"text": "안내대로 했는데 해결되지 않았어요.", "resolution": "UNRESOLVED"},
}


async def test_the_runner_replays_the_unresolved_reply_and_b10_flags_a_repeated_guide() -> None:
    llm = decisions_for_three_turns()
    agents = {AgentRole.INQUIRY: FollowUpInquiry(), AgentRole.KNOWLEDGE: ScriptedKnowledge()}

    trace = await run_scenario(
        parse_scenario(FOLLOW_UP_SCENARIO), llm=llm, agents=agents, config=RunConfig(max_turns=4)
    )

    assert len(trace.turns) == 3
    assert trace.turns[1].user_reply == "안내대로 했는데 해결되지 않았어요."
    third = trace.turns[2].request.problems[0]
    assert third.resolutionContext is not None and third.resolutionContext.state == "UNRESOLVED"
    # 같은 문서로 다시 안내한 대본이라 B10에 걸린다. 안전 성질은 위반이 아니다.
    assert [(f.property_id, f.turn) for f in check_b10(trace)] == [("B10", 2)]


def decisions_for_three_turns():
    from tests.doubles.llm import FakeLLMClient

    return FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("ASK_USER"),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("PROVIDE_GUIDE"),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("PROVIDE_GUIDE"),
    )


async def test_without_a_follow_up_in_the_scenario_the_run_ends_after_the_guide() -> None:
    llm = decisions_for_three_turns()

    trace = await run_scenario(
        parse_scenario({**SCENARIO, "unknownFields": []}), llm=llm, agents=AGENTS
    )

    assert len(trace.turns) == 2 and check_b10(trace) == []


# -- B10 ----------------------------------------------------------------------------------------


def unresolved_trace(guided, used, *, with_guide=True):
    request = unresolved_request(guided)
    guide = {"problemRef": PROBLEM, "messageText": "다시 안내", "evidenceIds": [used.id]}
    result = ai_result(
        request,
        answer="다시 안내",
        guide=guide if with_guide else None,
        evidence_items=[used] if with_guide else [],
    )
    return run_trace(turn(request, result))


def test_b10_flags_a_guide_from_the_same_document_and_version() -> None:
    assert [f.property_id for f in check_b10(unresolved_trace((GUIDE,), GUIDE))] == ["B10"]


def test_b10_accepts_a_different_document_a_new_version_or_no_guide() -> None:
    assert check_b10(unresolved_trace((GUIDE,), NEW_GUIDE)) == []
    assert check_b10(unresolved_trace((GUIDE,), NEW_VERSION)) == []
    assert check_b10(unresolved_trace((GUIDE,), GUIDE, with_guide=False)) == []


def test_b10_ignores_a_problem_that_was_not_marked_unresolved() -> None:
    request = request_with()
    result = ai_result(request, guide=None)

    assert check_b10(run_trace(turn(request, result))) == []
    assert check_trace(run_trace(turn(request, result))) == []
