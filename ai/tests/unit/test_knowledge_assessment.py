"""검색 근거 충분성 판정(Assessment): 검증 규칙, Knowledge의 호출·실패 처리, 병합, 외부 계약 불변.

모델은 Fake다. 판정이 실제로 맞는지는 이 테스트가 검증하지 않는다(실모델 측정 대상).
Fake는 모델이 낼 수 있는 나쁜 출력(없는 근거 ID, 담당자 전용 근거로 SUFFICIENT, 확정된 원인)을
지정해 코드의 방어 규칙을 확인한다.
"""

import json

import pytest
from pydantic import ValidationError

from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.agents.knowledge.assessment import (
    PROMPT_VERSION,
    RESERVED_CALLS,
    AskedChecks,
    AssessmentDraft,
    DraftCause,
    DraftMissing,
    DraftNextCheck,
)
from hops_ai.agents.registry import build_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import (
    Assessment,
    CandidateCause,
    KnowledgeResult,
    MissingInformation,
    NextCheck,
)
from hops_ai.contracts.wire import AiResult
from hops_ai.harness.errors import (
    BudgetExceeded,
    OutputRejected,
    RetrievalUnavailable,
    TransientError,
)
from hops_ai.rules.assessment import allowed_fields, no_match_assessment, validate_assessment
from hops_ai.services.retrieval.knowledge_source import KnowledgeHit
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.disabled import DisabledToolGateway
from hops_ai.settings import Settings
from hops_ai.supervisor.bootstrap import create_run_services
from hops_ai.supervisor.execution import RoleRequest, execute_role
from hops_ai.supervisor.merge import merge_knowledge_result
from hops_ai.supervisor.state import create_initial_state
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import (
    PROBLEM,
    authorize,
    make_evidence,
    reserve,
    running_state,
    uid,
)
from tests.unit.test_knowledge_agent import RecordingSource, how_to_request

GUIDE = make_evidence(title="문서 조회 안내")
STAFF = make_evidence(
    id=uid(70), documentId=uid(71), kind="RUNBOOK", accessScope="SYSTEM_STAFF", title="내부 점검"
)
OTHER = uid(72)


def hit(evidence=GUIDE, content="문서 조회는 '문서 조회' 메뉴에서 기간을 고릅니다") -> KnowledgeHit:
    return KnowledgeHit(evidence=evidence, reference=authorize(evidence), content=content, score=1)


def draft(**changes) -> AssessmentDraft:
    fields = dict(
        verdict="SUFFICIENT",
        supportingEvidenceIds=[GUIDE.id],
        answeredCheckItems=[],
        askedChecksAnswered=[],
        missingInformation=[],
        candidateCauses=[],
        nextChecks=[],
        rationale="안내 본문이 질문에 답한다",
    )
    return AssessmentDraft(**{**fields, **changes})


def assessment(**changes) -> Assessment:
    fields = dict(
        problemRef=PROBLEM,
        verdict="SUFFICIENT",
        supportingEvidenceIds=[GUIDE.id],
        missingInformation=[],
        candidateCauses=[],
        nextChecks=[],
        rationale="안내 본문이 질문에 답한다",
    )
    return Assessment(**{**fields, **changes})


def check(a: Assessment, *, state="FOUND", evidence=(GUIDE, STAFF), **kwargs) -> None:
    validate_assessment(a, intent="HOW_TO", search_state=state, evidence=list(evidence), **kwargs)


# --- 검증 규칙 ---


def test_a_well_formed_sufficient_assessment_passes() -> None:
    check(assessment())


@pytest.mark.parametrize(
    "bad",
    [
        {"supportingEvidenceIds": [STAFF.id]},  # 담당자 전용 근거만으로는 SUFFICIENT가 될 수 없다
        {"supportingEvidenceIds": [OTHER]},  # 이번 검색 결과에 없는 근거
        {"supportingEvidenceIds": [GUIDE.id, GUIDE.id]},  # 중복
        {"supportingEvidenceIds": []},  # SUFFICIENT는 근거가 필요하다
        {"verdict": "PARTIAL", "supportingEvidenceIds": []},
        {"verdict": "NO_MATCH"},  # NO_MATCH는 근거 ID를 가질 수 없다
        {"rationale": "가" * 501},
    ],
)
def test_assessments_outside_the_rules_are_rejected(bad) -> None:
    with pytest.raises(OutputRejected):
        check(assessment(**bad))


def test_partial_and_insufficient_may_cite_staff_only_evidence_but_not_sufficient() -> None:
    check(assessment(verdict="PARTIAL", supportingEvidenceIds=[STAFF.id]))
    check(assessment(verdict="INSUFFICIENT", supportingEvidenceIds=[]))


def test_empty_search_allows_no_match_with_only_approved_check_items() -> None:
    check(no_match_assessment(PROBLEM), state="EMPTY", evidence=())
    with_check = no_match_assessment(PROBLEM, ["check-1"])
    check(with_check, state="EMPTY", evidence=(), allowed_check_items=frozenset({"check-1"}))

    for bad in (
        with_check,
        assessment(verdict="SUFFICIENT", supportingEvidenceIds=[]),
        assessment(
            verdict="NO_MATCH",
            supportingEvidenceIds=[],
            missingInformation=[MissingInformation(kind="FIELD", ref="taskOrScreen")],
        ),
    ):
        with pytest.raises(OutputRejected):
            check(bad, state="EMPTY", evidence=())


def test_a_search_error_is_never_assessed() -> None:
    with pytest.raises(OutputRejected):
        check(no_match_assessment(PROBLEM), state="ERROR", evidence=())


def test_missing_information_is_a_closed_vocabulary() -> None:
    check(assessment(missingInformation=[MissingInformation(kind="FIELD", ref="taskOrScreen")]))

    for bad in (
        [MissingInformation(kind="FIELD", ref="어떤 화면에서 그러셨나요")],  # 자유 문장
        [MissingInformation(kind="FIELD", ref="occurredAt")],  # 이 의도가 묻지 않는 필드
        [MissingInformation(kind="CHECK_ITEM", ref="item-1")],  # 허용된 확인 항목이 없다
        [MissingInformation(kind="FIELD", ref="taskOrScreen")] * 2,  # 중복
        [MissingInformation(kind="FIELD", ref="taskOrScreen")] * 6,  # 개수
    ):
        with pytest.raises(OutputRejected):
            check(assessment(missingInformation=bad))


def test_check_items_are_accepted_only_from_the_allowed_set() -> None:
    allowed = frozenset({"item-1"})

    check(
        assessment(
            missingInformation=[MissingInformation(kind="CHECK_ITEM", ref="item-1")],
            nextChecks=[NextCheck(checkItemId="item-1")],
        ),
        allowed_check_items=allowed,
    )
    with pytest.raises(OutputRejected):
        check(assessment(nextChecks=[NextCheck(checkItemId="item-2")]), allowed_check_items=allowed)


@pytest.mark.parametrize(
    "cause",
    [
        CandidateCause(text="권한 부족", evidenceIds=[GUIDE.id], confirmed=True),  # 확정 금지
        CandidateCause(text="권한 부족", evidenceIds=[], confirmed=False),  # 근거 없음
        CandidateCause(text="권한 부족", evidenceIds=[OTHER], confirmed=False),  # 검색 밖 근거
        CandidateCause(text="가" * 201, evidenceIds=[GUIDE.id], confirmed=False),
    ],
)
def test_candidate_causes_are_never_confirmed_and_always_grounded(cause) -> None:
    with pytest.raises(OutputRejected):
        check(assessment(candidateCauses=[cause]))


def test_blank_text_is_refused_by_the_contract_type_itself() -> None:
    with pytest.raises(ValidationError):
        assessment(rationale="  ")
    with pytest.raises(ValidationError):
        CandidateCause(text=" ", evidenceIds=[GUIDE.id], confirmed=False)


def test_a_grounded_unconfirmed_cause_passes_but_not_more_than_three() -> None:
    ok = CandidateCause(text="조회 기간이 좁다", evidenceIds=[GUIDE.id], confirmed=False)

    check(assessment(candidateCauses=[ok]))
    with pytest.raises(OutputRejected):
        check(assessment(candidateCauses=[ok] * 4))


def test_allowed_fields_follow_the_intent_policy() -> None:
    assert allowed_fields("HOW_TO") == ("taskOrScreen", "currentWorkflow", "expectedBehavior")
    assert allowed_fields("OBSERVED_SYMPTOM") == (
        "taskOrScreen",
        "expectedBehavior",
        "actualBehavior",
        "occurredAt",
    )
    assert allowed_fields("OUT_OF_SCOPE") == ()


# --- Knowledge 에이전트 ---


def services_with(request, *outcomes) -> tuple[SharedServices, FakeLLMClient]:
    llm = FakeLLMClient(*outcomes)
    services = create_run_services(
        request,
        settings=Settings(_env_file=None),
        access_scope_ref=f"test-scope:{request.runId}",
        llm=llm,
        tools=DisabledToolGateway(),
    )
    return services, llm


DEFAULT_HITS = (hit(),)


async def test_identical_authorized_evidence_reuses_assessment_but_changed_content_does_not():
    request = how_to_request()
    services, llm = services_with(request, draft(), draft())
    target = running_state(request)["problems"][PROBLEM]
    agent = SearchKnowledgeAgent(RecordingSource(DEFAULT_HITS), assess_evidence=True)
    first = await agent._assess(target, [hit()], None, services, AskedChecks())
    repeated = await agent._assess(target, [hit()], None, services, AskedChecks())
    assert repeated == first and len(llm.requests) == 1
    await agent._assess(target, [hit(content="새 승인 원문")], None, services, AskedChecks())
    assert len(llm.requests) == 2


async def test_assessment_cache_does_not_cross_run_or_problem_boundaries():
    request = how_to_request()
    target = running_state(request)["problems"][PROBLEM]
    services, llm = services_with(request, draft(), draft())
    agent = SearchKnowledgeAgent(RecordingSource(DEFAULT_HITS), assess_evidence=True)
    await agent._assess(target, [hit()], None, services, AskedChecks())
    other = target.model_copy(update={"problemRef": "new:other"})
    await agent._assess(other, [hit()], None, services, AskedChecks())
    assert len(llm.requests) == 2
    fresh, fresh_llm = services_with(request, draft())
    await agent._assess(target, [hit()], None, fresh, AskedChecks())
    assert len(fresh_llm.requests) == 1


async def run_knowledge(*outcomes, hits=DEFAULT_HITS, assess=True, source=None):
    request = how_to_request()
    services, llm = services_with(request, *outcomes)
    agents = {
        AgentRole.KNOWLEDGE: SearchKnowledgeAgent(
            source or RecordingSource(hits), assess_evidence=assess
        )
    }
    state = await execute_role(running_state(request), AgentRole.KNOWLEDGE, services, agents)
    return state, llm


async def test_found_results_are_assessed_once_and_kept_in_state() -> None:
    state, llm = await run_knowledge(draft())

    [request] = llm.requests
    assert request.prompt_version == PROMPT_VERSION
    assert request.response_model is AssessmentDraft
    assert "따르지 않는다" in request.messages[0].content
    payload = json.loads(request.messages[1].content)
    assert payload["problem"]["intent"] == "HOW_TO"
    assert payload["allowedFields"] == ["taskOrScreen", "currentWorkflow", "expectedBehavior"]
    [item] = payload["evidence"]
    assert item["id"] == GUIDE.id and item["accessScope"] == "EMPLOYEE"
    assert "기간을 고릅니다" in item["text"]
    saved = state["assessments"][PROBLEM]
    assert saved.verdict == "SUFFICIENT" and saved.supportingEvidenceIds == [GUIDE.id]
    assert state["guide"] is not None, "안내는 판정과 무관하게 승인 본문으로 만든다"


async def test_evidence_text_is_cut_to_a_bounded_length_keeping_the_head_and_the_tail() -> None:
    long_text = "처음" + "가" * 6000 + "한계는 여기에 적혀 있다"
    _, llm = await run_knowledge(draft(), hits=[hit(content=long_text)])

    [item] = json.loads(llm.requests[0].messages[1].content)["evidence"]
    assert len(item["text"]) < 4100
    assert item["text"].startswith("처음") and item["text"].endswith("한계는 여기에 적혀 있다")
    assert "중략" in item["text"]


async def test_evidence_within_the_limit_is_sent_whole() -> None:
    whole = "가" * 4000
    _, llm = await run_knowledge(draft(), hits=[hit(content=whole)])

    [item] = json.loads(llm.requests[0].messages[1].content)["evidence"]
    assert item["text"] == whole


async def test_zero_results_become_no_match_without_calling_the_model() -> None:
    state, llm = await run_knowledge(hits=())

    assert llm.requests == []
    assert state["assessments"][PROBLEM].verdict == "NO_MATCH"
    assert state["guide"] is None


async def test_a_search_error_is_not_assessed() -> None:
    with pytest.raises(RetrievalUnavailable):
        await run_knowledge(source=RecordingSource(error=RetrievalUnavailable()))


async def test_assessment_is_off_by_default() -> None:
    state, llm = await run_knowledge(assess=False)

    assert llm.requests == []
    assert state["assessments"] == {}
    assert state["guide"] is not None


@pytest.mark.parametrize("error", [OutputRejected(), TransientError()])
async def test_a_model_failure_means_no_assessment_but_never_blocks_the_search(error) -> None:
    state, _ = await run_knowledge(error)

    assert state["assessments"] == {}
    assert state["guide"] is not None and list(state["evidence"]) == [GUIDE.id]


@pytest.mark.parametrize(
    "bad",
    [
        draft(supportingEvidenceIds=[OTHER]),
        draft(verdict="NO_MATCH"),
        draft(missingInformation=[DraftMissing(kind="CHECK_ITEM", ref="item-1")]),
        draft(missingInformation=[DraftMissing(kind="FIELD", ref="자유 문장")]),
        draft(candidateCauses=[DraftCause(text="권한", evidenceIds=[GUIDE.id], confirmed=True)]),
        draft(nextChecks=[DraftNextCheck(checkItemId="item-1")]),
        draft(rationale="  "),  # 계약 형식(공백 금지)에서 걸린다
    ],
)
async def test_an_invalid_assessment_is_dropped_and_the_search_result_is_kept(bad) -> None:
    state, llm = await run_knowledge(bad)

    assert len(llm.requests) == 1
    assert state["assessments"] == {}
    assert state["guide"] is not None and list(state["evidence"]) == [GUIDE.id]


async def test_staff_only_evidence_cannot_make_a_sufficient_assessment() -> None:
    request = how_to_request(scopes=("EMPLOYEE", "SYSTEM_STAFF"), purpose="SYSTEM_WORK")
    services, _ = services_with(request, draft(supportingEvidenceIds=[STAFF.id]))
    agents = {
        AgentRole.KNOWLEDGE: SearchKnowledgeAgent(
            RecordingSource([hit(STAFF)]), assess_evidence=True
        )
    }

    state = await execute_role(running_state(request), AgentRole.KNOWLEDGE, services, agents)

    assert state["assessments"] == {}


async def test_budget_exhaustion_is_a_run_failure_not_a_missing_assessment() -> None:
    with pytest.raises(BudgetExceeded):
        await run_knowledge(BudgetExceeded())


async def test_assessment_is_skipped_when_it_would_use_the_calls_the_run_still_needs() -> None:
    request = how_to_request()
    services, llm = services_with(request, draft())
    budget = services.runtime.budget
    budget.llm_calls_used = budget.max_llm_calls - RESERVED_CALLS  # 평가 1회 + 예비 2회가 안 남음
    state = reserve(running_state(request), AgentRole.KNOWLEDGE, "inv-1")
    agent = SearchKnowledgeAgent(RecordingSource([hit()]), assess_evidence=True)

    response = await agent.run(RoleRequest("inv-1", state), services)

    assert llm.requests == []
    assert response.result.assessment is None
    assert response.result.guide is not None

    budget.llm_calls_used = budget.max_llm_calls - RESERVED_CALLS - 1  # 딱 맞게 남으면 평가한다
    state = reserve(running_state(request), AgentRole.KNOWLEDGE, "inv-2")
    response = await agent.run(RoleRequest("inv-2", state), services)
    assert response.result.assessment is not None


# --- 병합 ---


async def agent_response(*outcomes):
    request = how_to_request()
    services, _ = services_with(request, *outcomes)
    state = reserve(running_state(request), AgentRole.KNOWLEDGE, "inv-1")
    agent = SearchKnowledgeAgent(RecordingSource([hit()]), assess_evidence=True)
    return state, await agent.run(RoleRequest("inv-1", state), services)


def merge(state, result: KnowledgeResult, response):
    return merge_knowledge_result(
        state,
        result,
        authorized_refs=list(response.authorized_refs),
        verified_evidence=response.verified_evidence,
    )


@pytest.mark.parametrize(
    "bad",
    [
        {"supportingEvidenceIds": [OTHER]},
        {"problemRef": "new:9"},
        {"verdict": "NO_MATCH"},
    ],
)
async def test_merge_rejects_an_assessment_the_agent_rules_would_not_produce(bad) -> None:
    state, response = await agent_response(draft())
    tampered = response.result.model_copy(
        update={"assessment": response.result.assessment.model_copy(update=bad)}
    )

    with pytest.raises(OutputRejected):
        merge(state, tampered, response)


async def test_merge_keeps_the_latest_valid_assessment_when_a_later_search_has_none() -> None:
    state, response = await agent_response(draft(verdict="PARTIAL"))
    state = merge(state, response.result, response)
    later = response.result.model_copy(update={"invocationId": "inv-2", "assessment": None})

    merged = merge(reserve(state, AgentRole.KNOWLEDGE, "inv-2"), later, response)

    assert merged["assessments"][PROBLEM].verdict == "PARTIAL"


async def test_a_newer_assessment_replaces_the_older_one_for_the_same_problem() -> None:
    state, response = await agent_response(draft(verdict="PARTIAL"))
    state = merge(state, response.result, response)
    newer = response.result.model_copy(
        update={
            "invocationId": "inv-2",
            "assessment": response.result.assessment.model_copy(update={"verdict": "SUFFICIENT"}),
        }
    )

    merged = merge(reserve(state, AgentRole.KNOWLEDGE, "inv-2"), newer, response)

    assert merged["assessments"][PROBLEM].verdict == "SUFFICIENT"


# --- 연결과 외부 계약 ---


def test_registry_and_settings_keep_assessment_off_unless_asked() -> None:
    source = RecordingSource()

    assert build_registry(source)[AgentRole.KNOWLEDGE]._assess_evidence is False
    assert (
        build_registry(source, assess_evidence=True)[AgentRole.KNOWLEDGE]._assess_evidence is True
    )
    assert Settings(_env_file=None).knowledge_assessment is False


def test_initial_state_has_no_assessments() -> None:
    assert create_initial_state(how_to_request())["assessments"] == {}


def test_assessments_are_not_part_of_the_outgoing_result_contract() -> None:
    schema = json.dumps(AiResult.model_json_schema())

    assert "assessment" not in schema.lower() and "verdict" not in schema


def test_draft_contract_models_keep_every_field_required_for_strict_output() -> None:
    schema = AssessmentDraft.model_json_schema()

    assert set(schema["required"]) == set(schema["properties"])


def test_the_assessment_prompt_states_general_sufficiency_principles_without_dataset_names() -> (
    None
):
    from evaluation.harness.catalog import SYSTEMS
    from hops_ai.agents.knowledge.assessment import PROMPT_VERSION, SYSTEM_PROMPT

    assert PROMPT_VERSION == "knowledge.assessment.v6"
    assert "증상이나 오류" in SYSTEM_PROMPT and "범위의 한계" in SYSTEM_PROMPT
    assert "CHECK_ITEM" in SYSTEM_PROMPT and "checkItems" in SYSTEM_PROMPT
    # 특정 시스템·문서·시나리오에 맞춘 문구를 넣지 않는다. 판정 규칙은 문제의 종류로만 구분한다.
    names = [value for system in SYSTEMS for value in (system["code"], system["name"])]
    assert not [name for name in names if name in SYSTEM_PROMPT]
    assert "guide-" not in SYSTEM_PROMPT and "runbook-" not in SYSTEM_PROMPT
