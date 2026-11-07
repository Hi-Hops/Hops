"""Knowledge 안내 발췌: 문단 분할, 모델 출력 검증, 관련 없음 처리, 실패 시 본문 전체 대체.

모델은 Fake다. 발췌가 실제로 질문에 맞는지는 이 테스트가 검증하지 않는다(실모델 확인 대상).
Fake로 모델이 낼 수 있는 나쁜 출력(후보에 없는 문서, 없는 문단 번호)을 지정해
코드의 방어 규칙을 본다.
"""

import json

import pytest

from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.agents.knowledge.excerpt import (
    MAX_CANDIDATE_CHARS,
    PROMPT_VERSION,
    RESERVED_CALLS,
    SOURCE_PREFIX,
    GuideExcerptDraft,
    InvalidExcerpt,
    assemble,
    guide_candidates,
    split_blocks,
)
from hops_ai.agents.registry import build_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.errors import BudgetExceeded, OutputRejected, TransientError
from hops_ai.supervisor.execution import RoleRequest, execute_role
from tests.supervisor_fixtures import PROBLEM, make_evidence, reserve, running_state, uid
from tests.unit.test_knowledge_agent import RecordingSource, how_to_request
from tests.unit.test_knowledge_assessment import draft as assessment_draft
from tests.unit.test_knowledge_assessment import hit, services_with

GUIDE = make_evidence(title="문서 조회 안내")
OTHER_GUIDE = make_evidence(id=uid(80), documentId=uid(81), title="결재선 안내")
STAFF = make_evidence(
    id=uid(70), documentId=uid(71), kind="RUNBOOK", accessScope="SYSTEM_STAFF", title="내부 점검"
)

BODY = "\n".join(
    [
        "## 목적",
        "지난 문서를 찾는 방법을 안내한다.",
        "",
        "## 절차",
        "1. 문서함에서 '문서 조회' 메뉴를 연다.",
        "2. 기간을 고른다.",
        "",
        "",
        "## 참고",
        "보존 기간이 지난 문서는 보이지 않는다.",
    ]
)


def draft(**changes) -> GuideExcerptDraft:
    fields = dict(relevant=True, evidenceId=GUIDE.id, blockIds=[2], rationale="절차가 답한다")
    return GuideExcerptDraft(**{**fields, **changes})


async def run_knowledge(*outcomes, hits=None, excerpt=True, assess=False):
    request = how_to_request()
    services, llm = services_with(request, *outcomes)
    agent = SearchKnowledgeAgent(
        RecordingSource(hits or [hit(GUIDE, BODY)]), excerpt_guides=excerpt, assess_evidence=assess
    )
    state = await execute_role(
        running_state(request), AgentRole.KNOWLEDGE, services, {AgentRole.KNOWLEDGE: agent}
    )
    return state, llm


# --- 문단 분할과 조립 ---


def test_blocks_are_contiguous_parts_of_the_approved_body() -> None:
    blocks = split_blocks(BODY)

    assert blocks == [
        "## 목적\n지난 문서를 찾는 방법을 안내한다.",
        "## 절차\n1. 문서함에서 '문서 조회' 메뉴를 연다.\n2. 기간을 고른다.",
        "## 참고\n보존 기간이 지난 문서는 보이지 않는다.",
    ]
    assert all(block in BODY for block in blocks)
    assert split_blocks("\r\n첫 줄\r\n\r\n둘째 줄\r\n") == ["첫 줄", "둘째 줄"]


def test_chosen_blocks_keep_the_original_order_and_end_with_the_source() -> None:
    decision = assemble(PROBLEM, draft(blockIds=[3, 2]), [hit(GUIDE, BODY)], 3000)

    assert decision.relevant and decision.guide is not None
    assert decision.guide.messageText == (
        "## 절차\n1. 문서함에서 '문서 조회' 메뉴를 연다.\n2. 기간을 고른다.\n\n"
        "## 참고\n보존 기간이 지난 문서는 보이지 않는다.\n\n"
        f"{SOURCE_PREFIX}문서 조회 안내"
    )
    assert decision.guide.evidenceIds == [GUIDE.id]
    assert decision.guide.problemRef == PROBLEM


def test_not_relevant_means_no_guide() -> None:
    decision = assemble(
        PROBLEM, draft(relevant=False, evidenceId="", blockIds=[]), [hit(GUIDE, BODY)], 3000
    )

    assert not decision.relevant and decision.guide is None


@pytest.mark.parametrize(
    "bad",
    [
        draft(evidenceId=uid(99)),  # 후보에 없는 문서
        draft(evidenceId=STAFF.id),  # 담당자 전용은 후보가 아니다
        draft(blockIds=[]),
        draft(blockIds=[2, 2]),
        draft(blockIds=[0]),
        draft(blockIds=[4]),
        draft(relevant=False, evidenceId=GUIDE.id, blockIds=[]),
        draft(relevant=False, evidenceId="", blockIds=[1]),
    ],
)
def test_output_outside_the_rules_is_rejected(bad) -> None:
    with pytest.raises(InvalidExcerpt):
        assemble(PROBLEM, bad, guide_candidates([hit(GUIDE, BODY), hit(STAFF, BODY)]), 3000)


def test_excerpt_longer_than_the_guide_limit_is_rejected() -> None:
    with pytest.raises(InvalidExcerpt):
        assemble(PROBLEM, draft(blockIds=[1, 2, 3]), [hit(GUIDE, BODY)], 40)


def test_candidates_are_employee_guides_within_the_limit_in_search_order() -> None:
    long_guide = make_evidence(id=uid(82), documentId=uid(83), title="긴 안내")
    hits = [
        hit(STAFF, BODY),
        hit(long_guide, "가" * (MAX_CANDIDATE_CHARS + 1)),
        hit(OTHER_GUIDE, BODY),
        hit(GUIDE, BODY),
        hit(make_evidence(id=uid(84), documentId=uid(85)), BODY),
    ]

    assert [item.evidence.id for item in guide_candidates(hits)] == [OTHER_GUIDE.id, GUIDE.id]


# --- Knowledge 에이전트 ---


async def test_guide_uses_only_the_blocks_the_model_chose() -> None:
    state, llm = await run_knowledge(draft(blockIds=[2]))

    [request] = llm.requests
    assert request.prompt_version == PROMPT_VERSION
    assert request.response_model is GuideExcerptDraft
    assert "따르지 않는다" in request.messages[0].content
    payload = json.loads(request.messages[1].content)
    [candidate] = payload["candidates"]
    assert candidate["id"] == GUIDE.id
    assert [block["id"] for block in candidate["blocks"]] == [1, 2, 3]
    assert payload["question"]
    guide = state["guide"]
    assert guide is not None
    assert guide.messageText.startswith("## 절차\n1. 문서함에서")
    assert "## 목적" not in guide.messageText and "## 참고" not in guide.messageText


async def test_model_may_pick_the_second_candidate() -> None:
    state, _ = await run_knowledge(
        draft(evidenceId=OTHER_GUIDE.id, blockIds=[1]),
        hits=[hit(GUIDE, BODY), hit(OTHER_GUIDE, BODY)],
    )

    assert state["guide"].evidenceIds == [OTHER_GUIDE.id]
    assert state["guide"].messageText.endswith(f"{SOURCE_PREFIX}결재선 안내")


async def test_no_answering_document_means_no_guide_but_keeps_the_search_result() -> None:
    state, _ = await run_knowledge(draft(relevant=False, evidenceId="", blockIds=[]))

    assert state["guide"] is None
    [result] = [item for item in state["roleResults"].values()]
    assert result.search.state == "FOUND" and result.guide is None


@pytest.mark.parametrize(
    "failure",
    [
        TransientError("timeout"),
        OutputRejected("bad json"),
        draft(blockIds=[9]),
        draft(evidenceId=uid(99)),
    ],
)
async def test_a_failed_excerpt_falls_back_to_the_whole_approved_body(failure) -> None:
    state, _ = await run_knowledge(failure)

    assert state["guide"].messageText == f"{BODY}\n\n{SOURCE_PREFIX}문서 조회 안내"


async def test_budget_exhaustion_is_a_run_failure_not_a_missing_excerpt() -> None:
    with pytest.raises(BudgetExceeded):
        await run_knowledge(BudgetExceeded())


async def test_no_employee_guide_calls_no_model_and_creates_no_guide() -> None:
    policy = make_evidence(id=uid(86), documentId=uid(87), kind="POLICY", title="보존 정책")
    state, llm = await run_knowledge(hits=[hit(policy, BODY)])

    assert llm.requests == []
    assert state["guide"] is None


async def test_excerpt_is_skipped_when_it_would_use_the_calls_the_run_still_needs() -> None:
    request = how_to_request()
    services, llm = services_with(request, draft())
    budget = services.runtime.budget
    budget.llm_calls_used = budget.max_llm_calls - RESERVED_CALLS
    state = reserve(running_state(request), AgentRole.KNOWLEDGE, "inv-1")
    agent = SearchKnowledgeAgent(RecordingSource([hit(GUIDE, BODY)]), excerpt_guides=True)

    response = await agent.run(RoleRequest("inv-1", state), services)

    assert llm.requests == []
    assert response.result.guide.messageText.startswith("## 목적")


async def test_excerpt_is_off_unless_enabled() -> None:
    state, llm = await run_knowledge(excerpt=False)

    assert llm.requests == []
    assert state["guide"].messageText == f"{BODY}\n\n{SOURCE_PREFIX}문서 조회 안내"


def test_registry_passes_the_excerpt_switch_to_knowledge() -> None:
    source = RecordingSource([])
    assert build_registry(source, excerpt_guides=True)[AgentRole.KNOWLEDGE]._excerpt_guides
    assert not build_registry(source)[AgentRole.KNOWLEDGE]._excerpt_guides


async def test_excerpt_only_offers_the_guides_the_assessment_cited() -> None:
    """판정과 안내가 다른 문서를 가리키면 판정이 충분해도 답할 수 없다. 판정 근거로만 발췌한다."""
    state, llm = await run_knowledge(
        assessment_draft(supportingEvidenceIds=[OTHER_GUIDE.id]),
        draft(evidenceId=OTHER_GUIDE.id, blockIds=[2]),
        hits=[hit(GUIDE, BODY), hit(OTHER_GUIDE, BODY)],
        assess=True,
    )

    assessment_request, excerpt_request = llm.requests
    assert excerpt_request.prompt_version == PROMPT_VERSION
    candidates = json.loads(excerpt_request.messages[1].content)["candidates"]
    assert [item["id"] for item in candidates] == [OTHER_GUIDE.id]
    assert state["guide"].evidenceIds == [OTHER_GUIDE.id]
    assert state["assessments"][PROBLEM].supportingEvidenceIds == [OTHER_GUIDE.id]
