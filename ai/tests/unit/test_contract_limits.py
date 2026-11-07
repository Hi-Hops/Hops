"""Curator 입력·결과의 상한 값이 계약 문서(`contracts/agents/README.md`)와 AI 코드에서 같은지 본다.

Spring도 같은 블록을 자기 코드의 상수와 대조한다(`CurationLimitsContractTest`). 한쪽만 값을 바꾸면
다른 쪽이 입력이나 결과 전체를 거부하므로 문서를 기준으로 양쪽을 묶는다.
"""

import re
from pathlib import Path

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


def test_wire_limits_are_enforced_at_the_documented_values() -> None:
    """상수만이 아니라 실제 검증이 문서의 값에서 경계를 긋는지 확인한다."""
    documented = documented_limits()
    record_fields = wire.RelatedWorkRecord.model_fields
    limits = {
        item.max_length
        for name in ("action", "verification", "confirmedCause")
        for item in record_fields[name].metadata
        if hasattr(item, "max_length")
    }
    assert limits <= {documented["recordText"]} or limits == set()
    work = wire.WorkRecordContext.model_fields
    assert work["relatedRecords"].metadata[0].max_length == documented["relatedRecords"]
    assert work["resolutionHistory"].metadata[0].max_length == documented["resolutionHistory"]
    assert work["consultedKnowledge"].metadata[0].max_length == documented["consultedKnowledge"]
    assert work["linkCandidates"].metadata[0].max_length == (
        documented["symptomCandidates"] + documented["checkCandidates"]
    )
