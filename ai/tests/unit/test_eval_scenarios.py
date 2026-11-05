"""시나리오·정답 파일 검사. 정본 문서의 표와 어긋나지 않는지도 본다. 모델을 부르지 않는다."""

import copy
import json
from pathlib import Path
from typing import Any

import pytest

from evaluation.harness.scenario import ScenarioFormatError, load_scenario, parse_scenario
from evaluation.harness.user_sim import reply_to
from evaluation.harness.validate import (
    parse_document_catalog,
    parse_scenario_catalog,
    validate_files,
    validate_gold,
)
from hops_ai.contracts.wire import QuestionProposal
from tests.unit.test_eval_user_sim import SCENARIO

AI_ROOT = Path(__file__).resolve().parents[2]
SCENARIO_DIR = AI_ROOT / "evaluation" / "scenarios"
GOLD_DIR = AI_ROOT / "evaluation" / "golds"
CATALOG_MARKDOWN = (AI_ROOT.parent / "docs" / "knowledge" / "VIRTUAL_COMPANY.md").read_text(
    encoding="utf-8"
)
DOCUMENTS = parse_document_catalog(CATALOG_MARKDOWN)
CATALOG = parse_scenario_catalog(CATALOG_MARKDOWN)


def ask(*fields: str) -> QuestionProposal:
    return QuestionProposal(
        problemRef="new:1", bundleRef="b", text="질문", requestedFields=list(fields)
    )


def gold_of(name: str) -> dict[str, Any]:
    return json.loads((GOLD_DIR / f"{name}.json").read_text(encoding="utf-8"))


# ---- 정본 표와 파일의 정합성 -------------------------------------------------


def test_catalog_tables_are_parsed() -> None:
    assert len(CATALOG) == 20
    assert len(DOCUMENTS) == 16
    assert {kind for kind in DOCUMENTS.values()} == {"EMPLOYEE", "SYSTEM_STAFF"}


def test_scenario_files_match_the_catalog_table() -> None:
    files = {path.stem for path in SCENARIO_DIR.glob("*.json")}

    assert files == {f"S-{row['caseFamily']}-001" for row in CATALOG}
    assert {path.stem for path in GOLD_DIR.glob("*.json")} == files


def test_split_and_family_follow_the_catalog() -> None:
    for row in CATALOG:
        scenario = load_scenario(SCENARIO_DIR / f"S-{row['caseFamily']}-001.json")
        assert scenario.case_family == row["caseFamily"]
        assert scenario.split == row["split"], row["caseFamily"]
    splits = [row["split"] for row in CATALOG]
    assert (splits.count("DEV"), splits.count("TEST")) == (12, 8)


def test_catalog_evidence_documents_appear_in_the_gold() -> None:
    """카탈로그의 근거 문서는 정답에서 직원 문서는 관련 근거, 담당자 전용은 금지 근거로 나온다."""
    for row in CATALOG:
        gold = gold_of(f"S-{row['caseFamily']}-001")
        relevant = {item.split("@")[0] for p in gold["problems"] for item in p["relevantEvidence"]}
        forbidden = {
            item.split("@")[0] for p in gold["problems"] for item in p["forbiddenEvidence"]
        }
        for key in row["evidence"]:
            side = relevant if DOCUMENTS[key] == "EMPLOYEE" else forbidden
            assert key in side, f"{row['caseFamily']}: {key}"
        if not row["evidence"]:
            assert not relevant, f"{row['caseFamily']}: 카탈로그에 근거가 없는데 관련 근거가 있다"


def test_every_scenario_and_gold_passes_validation() -> None:
    results = validate_files(SCENARIO_DIR, GOLD_DIR, DOCUMENTS)

    assert len(results) == 20
    assert {name: errors for name, errors in results.items() if errors} == {}


def test_required_situations_are_covered() -> None:
    """DATA_FORMAT의 필수 상황 중 시나리오로 만들 수 있는 것이 모두 있다."""
    scenarios = {
        row["caseFamily"]: load_scenario(SCENARIO_DIR / f"S-{row['caseFamily']}-001.json")
        for row in CATALOG
    }
    assert scenarios["ambiguous-first"].unknown_fields == ("systemCode",)
    assert scenarios["login-permission-purpose-unknown"].unknown_fields == ("businessPurpose",)
    assert scenarios["ea-search-time-unknown"].unknown_fields == ("occurredAt",)
    assert scenarios["system-correction"].corrections
    assert scenarios["guide-unresolved"].follow_up is not None
    assert scenarios["retrieval-fault"].faults == ("RETRIEVAL_ERROR",)
    composite = {name for name in scenarios if name.startswith("composite-")}
    for name in composite:
        assert len(gold_of(f"S-{name}-001")["problems"]) == 2


def test_dev_and_test_scenarios_do_not_share_a_family() -> None:
    families = [row["caseFamily"] for row in CATALOG]

    assert len(families) == len(set(families))


def test_user_sim_runs_on_every_scenario_without_error() -> None:
    for row in CATALOG:
        scenario = load_scenario(SCENARIO_DIR / f"S-{row['caseFamily']}-001.json")
        reply = reply_to(scenario, [ask("system", "taskOrScreen")])
        assert reply.text


# ---- 검증기가 어긋남을 실제로 잡는지 --------------------------------------------


def valid_pair() -> tuple[dict[str, Any], dict[str, Any]]:
    name = "S-ea-search-time-unknown-001"
    scenario = json.loads((SCENARIO_DIR / f"{name}.json").read_text(encoding="utf-8"))
    return scenario, gold_of(name)


def errors_for(gold: dict[str, Any]) -> list[str]:
    scenario, _ = valid_pair()
    return validate_gold(gold, parse_scenario(scenario), DOCUMENTS)


def test_validator_accepts_the_valid_pair() -> None:
    _, gold = valid_pair()

    assert errors_for(gold) == []


@pytest.mark.parametrize(
    ("mutate", "expected"),
    [
        (lambda g: g["problems"][0].update(acceptedIntents=["FIX_IT"]), "허용되지 않는 값"),
        (lambda g: g["problems"][0].update(allowedFinalActions=[]), "비어 있다"),
        (
            lambda g: g["problems"][0].update(relevantEvidence=["guide-ea-nothing@1"]),
            "카탈로그에 없다",
        ),
        (lambda g: g["problems"][0].update(relevantEvidence=["guide-ea-document-search"]), "형식"),
        (
            lambda g: g["problems"][0].update(
                relevantEvidence=["runbook-ea-document-not-listed@1"]
            ),
            "담당자 전용",
        ),
        (lambda g: g["problems"][0].update(mustNotAskFields=["taskOrScreen"]), "필수이면서"),
        (
            lambda g: g["problems"][0]["assessment"].update(missingInformation=[]),
            "missingInformation",
        ),
        (lambda g: g.update(scenarioId="S-other-001"), "scenarioId"),
        (lambda g: g.update(behaviorProperties=["B9"]), "허용되지 않는 값"),
        (lambda g: g["problems"][0].update(unexpected=1), "알 수 없는 키"),
        (lambda g: g["problems"][0].pop("assessment"), "assessment"),
        (
            lambda g: g["problems"][0].update(
                afterFollowUp={"allowedFinalActions": [], "mustNotRepeatEvidence": []}
            ),
            "afterFollowUp",
        ),
    ],
)
def test_validator_rejects_broken_gold(mutate: Any, expected: str) -> None:
    _, gold = valid_pair()
    broken = copy.deepcopy(gold)
    mutate(broken)

    assert any(expected in error for error in errors_for(broken)), errors_for(broken)


def test_validator_rejects_a_required_field_the_user_cannot_answer() -> None:
    scenario, gold = valid_pair()
    scenario["userKnowledge"].pop("expectedBehavior")  # 값도 없고 모른다고 선언하지도 않음

    errors = validate_gold(gold, parse_scenario(scenario), DOCUMENTS)

    assert any("expectedBehavior" in error and "답할 값" in error for error in errors)


def test_validator_requires_b6_when_the_scenario_has_a_correction() -> None:
    scenario, gold = valid_pair()
    scenario["corrections"] = [{"afterTurn": 1, "text": "정정합니다."}]

    errors = validate_gold(gold, parse_scenario(scenario), DOCUMENTS)

    assert any("B6" in error for error in errors)


def test_validator_requires_assessment_null_for_injected_retrieval_error() -> None:
    scenario, gold = valid_pair()
    scenario["faults"] = ["RETRIEVAL_ERROR"]

    errors = validate_gold(gold, parse_scenario(scenario), DOCUMENTS)

    assert any("assessment가 null" in error for error in errors)


def test_validator_ties_follow_up_between_scenario_and_gold() -> None:
    scenario, gold = valid_pair()
    scenario["followUp"] = {"text": "해결되지 않았어요.", "resolution": "UNRESOLVED"}

    errors = validate_gold(gold, parse_scenario(scenario), DOCUMENTS)

    assert any("afterFollowUp" in error for error in errors)


def test_directory_check_reports_missing_pairs(tmp_path: Path) -> None:
    scenarios, golds = tmp_path / "s", tmp_path / "g"
    scenarios.mkdir()
    golds.mkdir()
    name = "S-ea-search-time-unknown-001"
    (scenarios / f"{name}.json").write_text(
        (SCENARIO_DIR / f"{name}.json").read_text("utf-8"), "utf-8"
    )

    results = validate_files(scenarios, golds, DOCUMENTS)

    assert results[name] == ["정답 파일이 없다"]


# ---- 시나리오 형식 추가분 --------------------------------------------------------


def test_scenario_parses_corrections_follow_up_and_faults() -> None:
    data = dict(SCENARIO)
    data["corrections"] = [{"afterTurn": 1, "text": "정정합니다."}]
    data["followUp"] = {"text": "해결이 안 됐어요.", "resolution": "UNRESOLVED"}
    data["faults"] = ["RETRIEVAL_ERROR"]

    scenario = parse_scenario(data)

    assert scenario.corrections[0].after_turn == 1
    assert scenario.follow_up is not None and scenario.follow_up.resolution == "UNRESOLVED"
    assert scenario.faults == ("RETRIEVAL_ERROR",)


@pytest.mark.parametrize(
    "extra",
    [
        {"corrections": [{"afterTurn": 0, "text": "x"}]},
        {"corrections": [{"afterTurn": 1, "text": " "}]},
        {"corrections": [{"afterTurn": 1, "text": "a"}, {"afterTurn": 1, "text": "b"}]},
        {"followUp": {"text": "x", "resolution": "RESOLVED"}},
        {"followUp": {"text": "", "resolution": "UNRESOLVED"}},
        {"faults": ["MODEL_DOWN"]},
    ],
)
def test_scenario_rejects_bad_additions(extra: dict[str, Any]) -> None:
    with pytest.raises(ScenarioFormatError):
        parse_scenario({**SCENARIO, **extra})


def test_correction_is_appended_only_to_its_turn() -> None:
    data = {**SCENARIO, "corrections": [{"afterTurn": 2, "text": "아, 파일 공유였어요."}]}
    scenario = parse_scenario(data)

    first = reply_to(scenario, [ask("taskOrScreen")], turn=1)
    second = reply_to(scenario, [ask("expectedBehavior")], turn=2)

    assert "파일 공유였어요" not in first.text
    assert second.text.endswith("아, 파일 공유였어요.")


def test_unknown_system_is_declared_with_system_code_and_answered_as_unknown() -> None:
    data = {
        **SCENARIO,
        "userKnowledge": {**SCENARIO["userKnowledge"], "systemCode": None},
        "unknownFields": ["systemCode"],
    }
    scenario = parse_scenario(data)

    reply = reply_to(scenario, [ask("system")])

    assert "시스템" in reply.text and "모르겠어요" in reply.text
    assert reply.unknown == (("new:1", "system"),)


def test_validator_rejects_empty_follow_up_actions_when_the_scenario_has_a_follow_up() -> None:
    name = "S-guide-unresolved-001"
    scenario = load_scenario(SCENARIO_DIR / f"{name}.json")
    gold = gold_of(name)
    assert validate_gold(gold, scenario, DOCUMENTS) == []  # 기준: 원래 정답은 통과
    gold["problems"][0]["afterFollowUp"]["allowedFinalActions"] = []

    errors = validate_gold(gold, scenario, DOCUMENTS)

    assert any("afterFollowUp.allowedFinalActions가 비어 있다" in error for error in errors)
