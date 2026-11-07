"""Curator 입력·결과의 상한 값이 계약 문서(`contracts/agents/README.md`)와 AI 코드에서 같은지 본다.

Spring도 같은 블록을 자기 코드의 상수와 대조한다(`CurationLimitsContractTest`). 한쪽만 값을 바꾸면
다른 쪽이 입력이나 결과 전체를 거부하므로 문서를 기준으로 양쪽을 묶는다.
"""

import re
from pathlib import Path

import pytest
from pydantic import ValidationError

from evaluation.curator import checks as evaluation_checks
from hops_ai.agents.curator import link
from hops_ai.contracts import wire

CONTRACT = Path(__file__).resolve().parents[3] / "contracts" / "agents" / "README.md"


def documented_limits() -> dict[str, int]:
    text = CONTRACT.read_text(encoding="utf-8")
    block = re.search(r"```curator-limits\n(.*?)```", text, re.DOTALL)
    assert block, "계약 문서에 curator-limits 블록이 없다"
    return {
        key: int(value)
        for key, value in (line.split("=") for line in block[1].strip().splitlines())
    }


def test_documented_limits_match_the_ai_constants() -> None:
    documented = documented_limits()

    assert documented["relatedRecords"] == wire.MAX_RELATED_RECORDS
    assert documented["resolutionHistory"] == wire.MAX_RESOLUTION_HISTORY
    assert documented["consultedKnowledge"] == wire.MAX_CONSULTED_KNOWLEDGE
    assert documented["symptomCandidates"] == wire.MAX_SYMPTOM_CANDIDATES
    assert documented["checkCandidates"] == wire.MAX_CHECK_CANDIDATES
    assert documented["recordText"] == wire.MAX_RECORD_TEXT
    assert documented["maxAddresses"] == link.MAX_ADDRESSES
    assert documented["maxUsesCheck"] == link.MAX_USES_CHECK


def test_documented_link_limits_match_the_evaluation_checker() -> None:
    documented = documented_limits()

    assert evaluation_checks.MAX_LINKS == {
        "APPLIES_TO": documented["maxApplies"],
        "ADDRESSES": documented["maxAddresses"],
        "USES_CHECK": documented["maxUsesCheck"],
    }


def max_length(field) -> int:
    """필드 정의에서 MaxLen 메타데이터를 찾는다. 없으면 실패한다(찾지 못한 채 통과하지 않는다)."""
    found = [item.max_length for item in field.metadata if hasattr(item, "max_length")]
    assert len(found) == 1, f"max_length를 찾지 못했다: {field}"
    return found[0]


def test_wire_list_limits_use_the_documented_values() -> None:
    documented = documented_limits()
    work = wire.WorkRecordContext.model_fields

    assert max_length(work["relatedRecords"]) == documented["relatedRecords"]
    assert max_length(work["resolutionHistory"]) == documented["resolutionHistory"]
    assert max_length(work["consultedKnowledge"]) == documented["consultedKnowledge"]
    assert max_length(work["linkCandidates"]) == (
        documented["symptomCandidates"] + documented["checkCandidates"]
    )


@pytest.mark.parametrize("field", ["action", "verification", "confirmedCause"])
def test_record_text_is_accepted_at_the_documented_length_and_rejected_one_over(field) -> None:
    """메타데이터 구조에 기대지 않고 실제 검증이 문서의 길이에서 경계를 긋는지 본다.

    `verification`·`confirmedCause`는 Optional이라 필드 메타데이터에 상한이 드러나지 않으므로
    직접 값을 넣어 확인한다.
    """
    limit = documented_limits()["recordText"]
    base = {
        "id": "90c34564-bb4c-447e-8e43-5de9068dd666",
        "kind": "FAILED_ATTEMPT",
        "action": "조치",
        "verification": None,
        "confirmedCause": None,
        "appliesToSystemVersion": None,
        "createdAt": "2026-11-02T00:30:00Z",
    }

    accepted = wire.RelatedWorkRecord.model_validate(base | {field: "가" * limit})
    assert len(getattr(accepted, field)) == limit
    with pytest.raises(ValidationError):
        wire.RelatedWorkRecord.model_validate(base | {field: "가" * (limit + 1)})
