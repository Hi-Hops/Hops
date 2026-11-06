"""의도별 정책 표: 모든 의도를 담는지, 표를 만들기 전의 동작과 같은지, 문서 표와 같은지 확인한다."""

import re
from dataclasses import replace
from pathlib import Path
from typing import get_args

import pytest

from hops_ai.agents.inquiry.forms import (
    FIELD_PROMPTS,
    GUIDE_FIRST_FIELDS,
    HANDOFF_FIELDS,
    REQUIRED_FIELDS,
)
from hops_ai.contracts.wire import Intent, KnowledgeEvidence
from hops_ai.rules.intent_policy import (
    GUIDE_TYPES,
    INTENT_POLICIES,
    TICKETABLE_TYPES,
    is_guide_type,
    is_ticketable,
    policy_for,
    validate_policies,
)

INTENTS = set(get_args(Intent))
DOC = Path(__file__).resolve().parents[3] / "docs" / "knowledge" / "INQUIRY_FORMS.md"
BACKTICKED = re.compile(r"`([^`]+)`")

# 정책 표를 만들기 전에 코드에 흩어져 있던 값. 표가 같은 동작을 유지하는지 고정한다.
BEFORE_REQUIRED = {
    "HOW_TO": ("taskOrScreen", "currentWorkflow"),
    "ACCESS_REQUEST": ("requestedAccess", "businessPurpose"),
    "OBSERVED_SYMPTOM": ("taskOrScreen", "expectedBehavior", "actualBehavior", "occurredAt"),
    "FEATURE_REQUEST": ("currentWorkflow", "desiredChange"),
    "IMPROVEMENT_REQUEST": ("currentWorkflow", "desiredChange"),
    "UNKNOWN": ("taskOrScreen",),
}
BEFORE_GUIDE_FIRST = {"HOW_TO": ("taskOrScreen",)}
BEFORE_HANDOFF = {"HOW_TO": ("taskOrScreen", "expectedBehavior")}
BEFORE_PROCESSING_TYPES = {
    "HOW_TO": "KNOWLEDGE_QA",
    "ACCESS_REQUEST": "SERVICE_REQUEST",
    "OBSERVED_SYMPTOM": "INVESTIGATION",
    "FEATURE_REQUEST": "CHANGE_REQUEST",
    "IMPROVEMENT_REQUEST": "CHANGE_REQUEST",
    "UNKNOWN": "REVIEW_REQUIRED",
    "OUT_OF_SCOPE": "OUT_OF_SCOPE",
}
BEFORE_TICKETABLE = {"SERVICE_REQUEST", "INVESTIGATION", "CHANGE_REQUEST", "REVIEW_REQUIRED"}


def test_every_intent_has_exactly_one_policy() -> None:
    assert set(INTENT_POLICIES) == INTENTS


def test_missing_intent_fails_validation() -> None:
    policies = dict(INTENT_POLICIES)
    del policies["UNKNOWN"]

    with pytest.raises(ValueError, match="빠짐=\\['UNKNOWN'\\]"):
        validate_policies(policies)


def test_unknown_intent_fails_validation() -> None:
    policies = {**INTENT_POLICIES, "NEW_INTENT": INTENT_POLICIES["UNKNOWN"]}

    with pytest.raises(ValueError, match="NEW_INTENT"):
        validate_policies(policies)


def test_same_processing_type_must_have_same_flags() -> None:
    # 기능 추가와 개선은 같은 처리 유형이므로 한쪽만 티켓 불가로 둘 수 없다.
    policies = {
        **INTENT_POLICIES,
        "FEATURE_REQUEST": replace(INTENT_POLICIES["FEATURE_REQUEST"], ticketable=False),
    }

    with pytest.raises(ValueError, match="CHANGE_REQUEST"):
        validate_policies(policies)


def test_duplicate_required_field_fails_validation() -> None:
    policies = {
        **INTENT_POLICIES,
        "UNKNOWN": replace(INTENT_POLICIES["UNKNOWN"], required_fields=("taskOrScreen",) * 2),
    }

    with pytest.raises(ValueError, match="중복"):
        validate_policies(policies)


def test_policy_table_keeps_the_values_used_before_it_existed() -> None:
    assert {k: v for k, v in REQUIRED_FIELDS.items() if k != "OUT_OF_SCOPE"} == BEFORE_REQUIRED
    assert REQUIRED_FIELDS["OUT_OF_SCOPE"] == ()
    assert dict(GUIDE_FIRST_FIELDS) == BEFORE_GUIDE_FIRST
    assert dict(HANDOFF_FIELDS) == BEFORE_HANDOFF
    assert {i: p.processing_type for i, p in INTENT_POLICIES.items()} == BEFORE_PROCESSING_TYPES
    assert TICKETABLE_TYPES == BEFORE_TICKETABLE
    assert GUIDE_TYPES == {"KNOWLEDGE_QA"}


@pytest.mark.parametrize(
    ("processing_type", "ticketable", "guide"),
    [
        ("KNOWLEDGE_QA", False, True),
        ("SERVICE_REQUEST", True, False),
        ("INVESTIGATION", True, False),
        ("CHANGE_REQUEST", True, False),
        ("REVIEW_REQUIRED", True, False),
        # 어떤 의도도 확정하지 않는 유형과 범위 밖은 티켓으로 처리하지 않는다.
        ("INCIDENT", False, False),
        ("OUT_OF_SCOPE", False, False),
    ],
)
def test_processing_type_helpers(processing_type, ticketable, guide) -> None:
    assert is_ticketable(processing_type) is ticketable
    assert is_guide_type(processing_type) is guide


def test_every_policy_field_has_a_question_prompt() -> None:
    names = {
        name
        for policy in INTENT_POLICIES.values()
        for fields in (policy.required_fields, policy.guide_first_fields, policy.handoff_fields)
        for name in fields or ()
    }

    assert names <= set(FIELD_PROMPTS)


def test_knowledge_kinds_are_kinds_the_contract_knows() -> None:
    known = set(get_args(KnowledgeEvidence.model_fields["kind"].annotation))

    for policy in INTENT_POLICIES.values():
        assert policy.knowledge_kinds <= known


def test_intents_without_search_are_feature_improvement_unknown_and_out_of_scope() -> None:
    # 설계 문서: 기능 추가·개선·범위 밖은 검색하지 않고, 사용법·권한 신청·현상 문의는 검색한다.
    # 의도 불명은 문서에 정해진 값이 없어 검색하지 않는 쪽으로 둔 초안이다.
    searching = {intent for intent, p in INTENT_POLICIES.items() if p.knowledge_kinds}

    assert searching == {"HOW_TO", "ACCESS_REQUEST", "OBSERVED_SYMPTOM"}


def test_policy_for_returns_the_table_entry() -> None:
    assert policy_for("HOW_TO") is INTENT_POLICIES["HOW_TO"]


def _doc_rows() -> list[list[str]]:
    lines = DOC.read_text(encoding="utf-8").splitlines()
    return [
        [cell.strip() for cell in line.strip().strip("|").split("|")]
        for line in lines
        if line.startswith("|")
    ]


def test_doc_intent_table_matches_policy_table() -> None:
    documented = {}
    for cells in _doc_rows():
        first = BACKTICKED.search(cells[0])
        if first is None or first.group(1) not in INTENTS or len(cells) < 4:
            continue
        intent = first.group(1)
        assert intent not in documented, f"{intent}가 문서 표에 두 번 나온다"
        documented[intent] = (
            tuple(BACKTICKED.findall(cells[1])),
            frozenset(BACKTICKED.findall(cells[2])),
        )

    assert set(documented) == INTENTS
    for intent, (fields, kinds) in documented.items():
        policy = INTENT_POLICIES[intent]
        assert fields == policy.required_fields, intent
        assert kinds == policy.knowledge_kinds, intent


def test_doc_state_table_matches_guide_first_and_handoff_fields() -> None:
    rows = _doc_rows()
    guide_first = next(cells for cells in rows if cells[0].startswith("가이드를 먼저 찾을 수 있음"))
    handoff = next(cells for cells in rows if cells[0].startswith("가이드를 찾지 못해"))

    how_to = INTENT_POLICIES["HOW_TO"]
    assert tuple(BACKTICKED.findall(guide_first[1])) == how_to.guide_first_fields
    assert tuple(BACKTICKED.findall(handoff[1])) == how_to.handoff_fields
