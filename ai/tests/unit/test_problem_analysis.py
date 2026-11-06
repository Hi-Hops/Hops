"""문제별 검색 힌트(키워드·기능 후보): 정리 규칙, Inquiry 연결, 병합 검증, 외부 계약 불변.

Fake 모델이 낼 수 있는 나쁜 출력(없는 단어, 다른 시스템의 기능, 중복)을 지정해 코드 규칙을 확인한다.
실제 모델이 쓸 만한 키워드를 내는지는 이 테스트가 검증하지 않는다(실모델 측정 대상).
"""

import json

import pytest

from hops_ai.agents.inquiry.extraction import SYSTEM_PROMPT, InquiryExtraction
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import InquiryResult, ProblemAnalysis
from hops_ai.contracts.wire import AiResult, AiRunRequest
from hops_ai.harness.errors import OutputRejected
from hops_ai.rules.analysis import (
    MAX_FEATURE_CANDIDATES,
    MAX_KEYWORDS,
    MIN_FEATURE_CANDIDATES,
    allowed_feature_ids,
    feature_candidate_limit,
    normalize_keywords,
    select_feature_candidates,
    user_texts,
)
from hops_ai.supervisor.merge import merge_inquiry_result
from hops_ai.supervisor.state import create_initial_state
from tests.supervisor_fixtures import OTHER_SYSTEM, SYSTEM, reserve, running_state, uid
from tests.unit.test_inquiry_agent import (
    EA_SYMPTOM,
    extracted,
    first_message_request,
    run_agent,
    services_for,
)

VIEW, STATUS, ATTACH = uid(501), uid(502), uid(503)
LOGIN = uid(511)


def request_with_features(text: str = EA_SYMPTOM, *, access: list[str] | None = None):
    """전자결재에 기능 3개, 공통 로그인에 기능 1개가 있는 첫 문의 입력."""
    fields = first_message_request(text).model_dump(mode="json")
    fields["systems"][0]["features"] = [
        {"id": VIEW, "name": "문서 조회"},
        {"id": STATUS, "name": "결재 상태"},
        {"id": ATTACH, "name": "첨부 열람"},
    ]
    fields["systems"][1]["features"] = [{"id": LOGIN, "name": "로그인"}]
    if access is not None:
        fields["accessContext"]["systemIds"] = access
    return AiRunRequest.model_validate(fields)


def texts_of(text: str) -> list[str]:
    return user_texts(request_with_features(text))


# --- 키워드 정리 ---


def test_keywords_must_appear_in_the_user_message() -> None:
    texts = texts_of(EA_SYMPTOM)

    # "삭제"는 사용자가 말하지 않은 단어라 맞출 근거가 없다.
    assert normalize_keywords(["결재", "삭제", "문서"], texts) == ("결재", "문서")


def test_keyword_length_is_two_to_twenty_after_whitespace_cleanup() -> None:
    texts = texts_of("결재  문서가 안   보여요 " + "가" * 25)

    kept = normalize_keywords(["결", "  안   보여요 ", "가" * 21, "가" * 20, ""], texts)

    # 1자와 21자와 빈 값은 버리고, 안쪽 공백은 하나로 모아 원문(공백 정리 후)과 맞춘다.
    assert kept == ("안 보여요", "가" * 20)


def test_duplicate_keywords_are_removed_ignoring_case() -> None:
    texts = texts_of("SSO 로그인이 안 돼요")

    assert normalize_keywords(["SSO", "sso", "로그인", "로그인"], texts) == ("SSO", "로그인")


def test_keyword_count_is_capped() -> None:
    words = ["첨부파일", "열람", "오류", "결재", "문서", "조회", "화면"]
    texts = texts_of(" ".join(words))

    kept = normalize_keywords(words, texts)

    assert len(kept) == MAX_KEYWORDS == 5
    assert kept == tuple(words[:5])


def test_only_user_messages_count_as_the_source_of_keywords() -> None:
    request = first_message_request(extra_ai_message=True)

    # AI 메시지("어떤 문서인가요?")에만 있는 단어는 근거가 아니다.
    assert normalize_keywords(["어떤"], user_texts(request)) == ()


# --- 기능 후보 ---


def test_feature_candidates_are_limited_to_the_confirmed_system() -> None:
    request = request_with_features()

    assert allowed_feature_ids(request, SYSTEM) == {VIEW, STATUS, ATTACH}
    assert allowed_feature_ids(request, OTHER_SYSTEM) == {LOGIN}


def test_no_feature_candidates_while_the_system_is_unknown_or_not_accessible() -> None:
    assert allowed_feature_ids(request_with_features(), None) == frozenset()
    # 접근할 수 없는 시스템의 기능은 관계 조회 API가 요청 전체를 거부하므로 후보로 쓰지 않는다.
    assert allowed_feature_ids(request_with_features(access=[OTHER_SYSTEM]), SYSTEM) == frozenset()


def test_feature_selection_drops_outsiders_and_duplicates_and_caps_the_count() -> None:
    allowed = frozenset({VIEW, STATUS, ATTACH})

    raw = [LOGIN, VIEW, VIEW, "not-a-feature", STATUS, ATTACH]

    assert select_feature_candidates(raw, allowed) == (VIEW, STATUS)


@pytest.mark.parametrize(
    ("features", "limit"), [(0, 1), (1, 1), (2, 1), (3, 2), (4, 2), (5, 2), (40, 2)]
)
def test_the_candidate_limit_grows_with_the_systems_feature_count(features, limit) -> None:
    assert feature_candidate_limit(features) == limit
    assert MIN_FEATURE_CANDIDATES <= limit <= MAX_FEATURE_CANDIDATES


def test_a_large_system_keeps_two_candidates_and_a_tiny_one_keeps_one() -> None:
    big = frozenset(f"f{n}" for n in range(12))
    tiny = frozenset({"only", "second"})

    assert select_feature_candidates([f"f{n}" for n in range(12)], big) == ("f0", "f1")
    assert select_feature_candidates(["second", "only"], tiny) == ("second",)


# --- Inquiry 연결 ---


async def test_inquiry_returns_cleaned_analysis_for_each_problem() -> None:
    request = request_with_features()

    result = await run_agent(
        request,
        extracted(
            keywords=["결재", "삭제", "문서", "결재"],
            featureCandidates=[LOGIN, VIEW, VIEW, "엉뚱한 값"],
        ),
    )

    [analysis] = result.analysis
    assert analysis.problemRef == "new:1"
    assert analysis.keywords == ["결재", "문서"]
    assert analysis.featureCandidates == [VIEW]


async def test_unknown_system_gives_no_feature_candidates() -> None:
    result = await run_agent(
        request_with_features(), extracted(systemCode=None, featureCandidates=[VIEW])
    )

    assert result.analysis[0].featureCandidates == []


async def test_model_input_lists_each_systems_features() -> None:
    request = request_with_features()
    services, llm = services_for(request, InquiryExtraction(problems=[extracted()]))
    from hops_ai.agents.inquiry import ExtractionInquiryAgent
    from hops_ai.supervisor.execution import RoleRequest

    state = reserve(running_state(request), AgentRole.INQUIRY, "inv-1")
    await ExtractionInquiryAgent().run(RoleRequest("inv-1", state), services)

    payload = json.loads(llm.requests[0].messages[1].content)
    ea = next(item for item in payload["systems"] if item["code"] == "ELECTRONIC_APPROVAL")
    assert {"id": VIEW, "name": "문서 조회"} in ea["features"]
    assert len(ea["features"]) == 3


def test_prompt_asks_for_keywords_from_the_message_and_features_from_the_confirmed_system() -> None:
    assert "keywords" in SYSTEM_PROMPT and "사용자 메시지에 실제로 있는 단어" in SYSTEM_PROMPT
    assert "featureCandidates" in SYSTEM_PROMPT and "빈 목록" in SYSTEM_PROMPT


def test_structured_output_schema_requires_the_new_fields() -> None:
    # OpenAI strict 구조화 출력은 모든 필드가 필수여야 한다.
    schema = InquiryExtraction.model_json_schema()["$defs"]["ExtractedProblem"]

    assert {"keywords", "featureCandidates"} <= set(schema["required"])


# --- 병합 검증 ---


def merge_state(request: AiRunRequest):
    return reserve(running_state(request), AgentRole.INQUIRY, "inv-1")


async def valid_result(request: AiRunRequest) -> InquiryResult:
    return await run_agent(request, extracted(keywords=["결재", "문서"], featureCandidates=[VIEW]))


async def test_merge_keeps_the_analysis_in_state() -> None:
    request = request_with_features()
    result = await valid_result(request)

    merged = merge_inquiry_result(merge_state(request), result)

    assert merged["analysis"]["new:1"] == ProblemAnalysis(
        problemRef="new:1", keywords=["결재", "문서"], featureCandidates=[VIEW]
    )


@pytest.mark.parametrize(
    "bad",
    [
        {"featureCandidates": [LOGIN]},  # 다른 시스템의 기능
        {"featureCandidates": [uid(999)]},  # 입력에 없는 기능
        {"featureCandidates": [VIEW, STATUS, ATTACH]},  # 개수 초과
        {"featureCandidates": [VIEW, VIEW]},  # 중복
        {"keywords": ["삭제"]},  # 사용자 메시지에 없는 단어
        {"keywords": ["결"]},  # 너무 짧음
        {"keywords": ["결재", "결재"]},  # 중복
        {"keywords": ["첨부", "결재", "문서", "보여요", "안", "전자결재"]},  # 개수 초과
    ],
)
async def test_merge_rejects_analysis_the_agent_rules_would_not_produce(bad) -> None:
    request = request_with_features()
    result = await valid_result(request)
    tampered = result.model_copy(update={"analysis": [result.analysis[0].model_copy(update=bad)]})

    with pytest.raises(OutputRejected):
        merge_inquiry_result(merge_state(request), tampered)


async def test_merge_rejects_analysis_for_a_problem_not_in_the_result_or_duplicated() -> None:
    request = request_with_features()
    result = await valid_result(request)
    [item] = result.analysis

    stranger = result.model_copy(
        update={"analysis": [item.model_copy(update={"problemRef": "new:2"})]}
    )
    duplicated = result.model_copy(update={"analysis": [item, item]})

    for tampered in (stranger, duplicated):
        with pytest.raises(OutputRejected):
            merge_inquiry_result(merge_state(request), tampered)


async def test_an_empty_later_analysis_does_not_erase_earlier_hints() -> None:
    request = request_with_features()
    first = await valid_result(request)
    state = merge_inquiry_result(merge_state(request), first)
    empty = first.model_copy(
        update={
            "invocationId": "inv-2",
            "analysis": [ProblemAnalysis(problemRef="new:1", keywords=[], featureCandidates=[])],
        }
    )

    merged = merge_inquiry_result(reserve(state, AgentRole.INQUIRY, "inv-2"), empty)

    assert merged["analysis"]["new:1"].keywords == ["결재", "문서"]
    assert merged["analysis"]["new:1"].featureCandidates == [VIEW]


async def test_a_later_non_empty_analysis_replaces_the_earlier_one() -> None:
    request = request_with_features()
    first = await valid_result(request)
    state = merge_inquiry_result(merge_state(request), first)
    later = first.model_copy(
        update={
            "invocationId": "inv-2",
            "analysis": [
                ProblemAnalysis(problemRef="new:1", keywords=["보여요"], featureCandidates=[STATUS])
            ],
        }
    )

    merged = merge_inquiry_result(reserve(state, AgentRole.INQUIRY, "inv-2"), later)

    assert merged["analysis"]["new:1"].keywords == ["보여요"]
    assert merged["analysis"]["new:1"].featureCandidates == [STATUS]


# --- 상태와 외부 계약 ---


def test_initial_state_starts_without_analysis() -> None:
    assert create_initial_state(request_with_features())["analysis"] == {}


def test_search_hints_are_not_part_of_the_outgoing_result_contract() -> None:
    schema = json.dumps(AiResult.model_json_schema())

    assert "keywords" not in schema and "featureCandidates" not in schema
