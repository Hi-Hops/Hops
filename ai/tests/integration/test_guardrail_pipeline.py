"""실제 역할·최종화 연결의 검사. 모델·검색은 대역이며 외부 API/DB E2E는 아니다."""

from copy import deepcopy

import pytest

from hops_ai.agents.inquiry.question_text import QuestionDraft
from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.agents.knowledge.excerpt import GuideExcerptDraft
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.harness.errors import OutputRejected
from hops_ai.supervisor.execution import execute_role
from hops_ai.supervisor.finalize import finalize_result
from hops_ai.supervisor.graph import Supervisor
from tests.integration.test_supervisor_standalone import ContextualLLM, InjectedInquiry, compose
from tests.supervisor_fixtures import make_evidence, make_request, running_state
from tests.unit.test_inquiry_agent import extracted, first_message_request, run_agent
from tests.unit.test_knowledge_agent import RecordingSource, how_to_request
from tests.unit.test_knowledge_assessment import hit, services_with
from tests.unit.test_problem_decisions import base_state, request_for
from tests.unit.test_question_generation import ask, asked
from tests.unit.test_supervisor_finalize import with_guide, with_question

SECRET = "sk-proj-" + "x" * 30


async def test_model_question_is_blocked_before_it_can_be_merged(caplog):
    request = request_for()
    state = base_state(request)
    original = deepcopy(state)

    with pytest.raises(OutputRejected):
        await ask(state, request, QuestionDraft(text=f"화면을 확인해 주세요. {SECRET}"))

    assert state == original
    assert "stage=QUESTION" in caplog.text
    assert SECRET not in caplog.text


async def test_legacy_fixed_question_with_untrusted_system_name_is_blocked(caplog):
    fields = first_message_request().model_dump(mode="json")
    fields["systems"][0]["name"] = SECRET
    request = AiRunRequest.model_validate(fields)

    with pytest.raises(OutputRejected):
        await run_agent(request, extracted(systemCode=None))

    assert "stage=QUESTION" in caplog.text
    assert SECRET not in caplog.text


async def test_ask_fixed_question_is_also_checked_without_model_calls(caplog):
    fields = request_for(system=None).model_dump(mode="json")
    fields["systems"][0]["name"] = SECRET
    request = AiRunRequest.model_validate(fields)

    with pytest.raises(OutputRejected):
        await ask(base_state(request), request, generate=False)

    assert "stage=QUESTION" in caplog.text
    assert SECRET not in caplog.text


async def test_normal_question_preserves_fields_and_question_count():
    request = request_for()
    sentence = "어느 화면에서 어떤 동작을 하셨는지 알려 주세요."
    merged, llm = await ask(base_state(request), request, QuestionDraft(text=sentence))

    assert asked(merged).text == sentence
    assert asked(merged).requestedFields == ["taskOrScreen", "expectedBehavior", "actualBehavior"]
    assert merged["input"].problems[0].questionCount == 0
    assert len(llm.requests) == 1


@pytest.mark.parametrize("excerpt", [False, True])
@pytest.mark.parametrize("secret_in_title", [False, True])
async def test_guide_body_and_title_are_checked_in_full_and_excerpt_paths(
    excerpt, secret_in_title, caplog
):
    request = how_to_request()
    evidence = make_evidence(title=SECRET if secret_in_title else "문서 조회 안내")
    body = "문서 조회 메뉴를 여세요." if secret_in_title else f"문서 조회 메뉴를 여세요. {SECRET}"
    source = RecordingSource([hit(evidence, body)])
    draft = GuideExcerptDraft(relevant=True, evidenceId=evidence.id, blockIds=[1], rationale="절차")
    services, llm = services_with(request, draft)

    with pytest.raises(OutputRejected):
        await execute_role(
            running_state(request),
            AgentRole.KNOWLEDGE,
            services,
            {AgentRole.KNOWLEDGE: SearchKnowledgeAgent(source, excerpt_guides=excerpt)},
        )

    assert len(llm.requests) == int(excerpt)
    assert "stage=GUIDE" in caplog.text
    assert SECRET not in caplog.text


async def test_failed_excerpt_fallback_cannot_return_a_secret_body(caplog):
    request = how_to_request()
    evidence = make_evidence(title="문서 조회 안내")
    source = RecordingSource([hit(evidence, f"문서 조회 안내 {SECRET}")])
    # 발췌 모델 실패 시 승인 본문 전체를 쓰는 기존 경로도 검사 대상이다.
    services, llm = services_with(request, OutputRejected("합성 발췌 실패"))

    with pytest.raises(OutputRejected):
        await execute_role(
            running_state(request),
            AgentRole.KNOWLEDGE,
            services,
            {AgentRole.KNOWLEDGE: SearchKnowledgeAgent(source, excerpt_guides=True)},
        )

    assert len(llm.requests) == 1
    assert "stage=GUIDE" in caplog.text
    assert SECRET not in caplog.text


@pytest.mark.parametrize("kind", ["question", "guide"])
def test_final_answer_checks_injected_role_text_without_mutating_state(kind, caplog):
    state, proposal = with_question() if kind == "question" else with_guide()
    if kind == "question":
        replacement = proposal.model_copy(update={"text": f"오류 문구를 알려 주세요. {SECRET}"})
        state["questions"] = {key: replacement for key in state["questions"]}
    else:
        state["guide"] = proposal.model_copy(update={"messageText": f"문서 조회 안내 {SECRET}"})
    original = deepcopy(state)

    with pytest.raises(OutputRejected):
        finalize_result(state)

    assert state == original
    assert "stage=FINAL_ANSWER" in caplog.text
    assert SECRET not in caplog.text


@pytest.mark.parametrize("secret", [False, True])
async def test_supervisor_returns_safe_failure_or_normal_success(secret, caplog):
    fields = make_request().model_dump(mode="json")
    if secret:
        fields["messages"][0]["content"] += " " + SECRET
    request = AiRunRequest.model_validate(fields)
    result = await Supervisor({AgentRole.INQUIRY: InjectedInquiry(use_model=False)}).analyze(
        request, compose(request, ContextualLLM())
    )

    if secret:
        assert result.status == "FAILED"
        assert result.error.code == "INVALID_OUTPUT"
        assert result.answer is None
        assert result.questions == []
        assert result.guide is None
        assert result.ticketProposals == []
        assert result.problemProposals == []
        assert SECRET not in result.model_dump_json() + caplog.text
    else:
        assert result.status == "SUCCEEDED"
        assert result.answer == result.questions[0].text
        assert "guardrail_blocked" not in caplog.text
