"""데이터셋에 맞춘 가정 제거(⑥): 시스템 코드와 기능 수, 문서 길이에 코드가 의존하지 않는다.

시스템 이름·코드·기능은 모두 가상의 설정이다. 실제 데이터(전자결재 등)의 이름은 쓰지 않는다.
모델은 Fake다. 설정이 달라져도 흐름이 같은 규칙으로 도는지만 본다.
"""

import copy
import json

import pytest
from pydantic import ValidationError

from hops_ai.agents.inquiry.extraction import (
    ExtractedProblem,
    InquiryExtraction,
    QuotedValue,
    extraction_schema,
)
from hops_ai.agents.registry import build_registry
from hops_ai.contracts.wire import AiRunRequest, SupportedSystem
from hops_ai.rules.analysis import allowed_feature_ids, select_feature_candidates
from hops_ai.supervisor.graph import Supervisor
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import MESSAGE, uid
from tests.unit.test_inquiry_agent import first_message_request
from tests.unit.test_knowledge_agent import RecordingSource
from tests.unit.test_knowledge_wiring import services_for
from tests.unit.test_problem_graph import REF, select

CODES = ["SYS_ALPHA", "SYS_BETA", "SYS_GAMMA", "SYS_DELTA", "SYS_EPSILON"]
NAMES = ["알파", "베타", "감마", "델타", "엡실론"]


def feature_ids(system_index: int, count: int) -> list[str]:
    return [uid(5000 + system_index * 100 + n) for n in range(count)]


def synthetic_request(text="알파 시스템에서 화면이 안 열려요", feature_counts=(8, 2, 3, 1, 0)):
    """시스템 5개(기능 수가 제각각)와 그 시스템을 모두 쓸 수 있는 문의."""
    fields = first_message_request(text).model_dump(mode="json")
    systems = []
    for index, (code, name, count) in enumerate(zip(CODES, NAMES, feature_counts, strict=True)):
        systems.append(
            {
                "id": uid(4000 + index),
                "code": code,
                "name": name,
                "features": [
                    {"id": fid, "name": f"{name} 기능 {n}"}
                    for n, fid in enumerate(feature_ids(index, count))
                ],
            }
        )
    fields["systems"] = systems
    fields["accessContext"]["systemIds"] = [system["id"] for system in systems]
    return AiRunRequest.model_validate(fields)


# --- 입력 계약: 코드 목록을 고정하지 않는다 ---


@pytest.mark.parametrize("code", ["SYS_ALPHA", "A1", "ELECTRONIC_APPROVAL", "X" * 64])
def test_any_well_formed_system_code_is_accepted(code) -> None:
    system = SupportedSystem(id=uid(1), code=code, name="이름", features=[])

    assert system.code == code


@pytest.mark.parametrize(
    "code", ["", "a", "lower_case", "1STARTS_WITH_DIGIT", "HAS SPACE", "X" * 65]
)
def test_a_malformed_system_code_is_rejected(code) -> None:
    with pytest.raises(ValidationError):
        SupportedSystem(id=uid(1), code=code, name="이름", features=[])


def test_a_request_with_five_made_up_systems_is_valid() -> None:
    request = synthetic_request()

    assert [system.code for system in request.systems] == CODES


# --- 추출 스키마: 허용 코드는 요청에서 온다 ---


def system_code_schema(schema):
    return schema["$defs"]["ExtractedProblem"]["properties"]["systemCode"]


def test_the_extraction_schema_allows_exactly_the_codes_of_the_request() -> None:
    schema = extraction_schema(CODES)

    options = system_code_schema(schema)["anyOf"]
    assert {"type": "null"} in options
    [coded] = [option for option in options if option.get("type") == "string"]
    assert coded["enum"] == sorted(CODES)


def test_without_any_system_the_extraction_schema_allows_only_null() -> None:
    assert system_code_schema(extraction_schema([])) == {"anyOf": [{"type": "null"}]}


def test_building_a_schema_does_not_change_the_models_default_schema() -> None:
    before = copy.deepcopy(InquiryExtraction.model_json_schema())

    extraction_schema(CODES)

    assert InquiryExtraction.model_json_schema() == before
    assert "enum" not in json.dumps(system_code_schema(before))


async def test_the_inquiry_call_sends_the_codes_of_this_request_as_its_schema() -> None:
    request = synthetic_request()
    problem = ExtractedProblem(
        **{**extracted_fields(), "systemCode": "SYS_ALPHA"},
    )
    llm = FakeLLMClient(InquiryExtraction(problems=[problem]), select(**{REF: "ASK"}))
    agents = build_registry(RecordingSource(), assess_evidence=True, defer_questions=True)

    await Supervisor(agents, restrict_roles=True, problem_actions=True).analyze(
        request, services_for(request, llm)
    )

    schema = llm.requests[0].response_schema
    [coded] = [o for o in system_code_schema(schema)["anyOf"] if o.get("type") == "string"]
    assert coded["enum"] == sorted(CODES)


def extracted_fields() -> dict:
    return {
        "summary": "화면이 열리지 않음",
        "intent": "OBSERVED_SYMPTOM",
        "systemCode": None,
        "taskOrScreen": None,
        "currentWorkflow": None,
        "expectedBehavior": None,
        "actualBehavior": QuotedValue(messageId=MESSAGE, quote="화면이 안 열려요"),
        "occurredAt": None,
        "requestedAccess": None,
        "businessPurpose": None,
        "desiredChange": None,
        "unknownFields": [],
        "keywords": [],
        "featureCandidates": [],
    }


# --- 흐름: 가상 시스템 코드로 시스템이 정해진다 ---


async def run_synthetic(code, *outcomes, request=None):
    request = request or synthetic_request()
    problem = ExtractedProblem(**{**extracted_fields(), "systemCode": code})
    llm = FakeLLMClient(InquiryExtraction(problems=[problem]), *outcomes)
    agents = build_registry(RecordingSource(), assess_evidence=True, defer_questions=True)
    result = await Supervisor(agents, restrict_roles=True, problem_actions=True).analyze(
        request, services_for(request, llm)
    )
    return request, result


async def test_a_made_up_system_code_resolves_to_that_systems_id() -> None:
    request, result = await run_synthetic("SYS_GAMMA", select(**{REF: "ASK"}))

    [proposal] = result.problemProposals
    gamma = next(system for system in request.systems if system.code == "SYS_GAMMA")
    assert proposal.reportedSystemCandidateId == gamma.id


async def test_a_code_outside_the_request_means_no_system_not_an_error() -> None:
    _, result = await run_synthetic("ELECTRONIC_APPROVAL")

    assert result.status == "SUCCEEDED"
    [proposal] = result.problemProposals
    assert proposal.reportedSystemCandidateId is None
    [question] = result.questions
    assert "system" in question.requestedFields
    assert all(name in question.text for name in NAMES), (
        "물을 때 요청의 시스템 이름을 모두 보여 준다"
    )


# --- 기능 후보 상한은 시스템의 기능 수에 따른다 ---


def test_the_feature_candidate_cap_follows_each_systems_feature_count() -> None:
    request = synthetic_request(feature_counts=(8, 2, 3, 1, 0))
    caps = []
    for system in request.systems:
        allowed = allowed_feature_ids(request, system.id)
        raw = [feature.id for feature in system.features]
        caps.append(len(select_feature_candidates(raw, allowed)))

    assert caps == [2, 1, 2, 1, 0]
