"""선순환 묶음(문의 A·B·C) 형식·누수 검사. 모델은 부르지 않고 검사기가 어긋남을 잡는지 본다."""

import json
import shutil
from collections.abc import Callable
from pathlib import Path

import pytest
import yaml

from evaluation.flywheel import validate as flywheel_validate
from evaluation.flywheel.bundle import ROOT, load_bundle

A = "F-ea-filter-first-001"
B = "F-ea-filter-rephrased-001"
C = "F-ea-doc-permission-decoy-001"
CARD = "case-ea-filter-narrow"


def _copy(tmp_path: Path) -> Path:
    target = tmp_path / "flywheel"
    shutil.copytree(ROOT, target, ignore=shutil.ignore_patterns("__pycache__", "*.py"))
    return target


def _edit_json(root: Path, relative: str, change: Callable[[dict], None]) -> None:
    path = root / relative
    data = json.loads(path.read_text(encoding="utf-8"))
    change(data)
    path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")


def _edit_card(root: Path, change: Callable[[dict], None]) -> None:
    path = root / "cards" / f"{CARD}.yml"
    card = yaml.safe_load(path.read_text(encoding="utf-8"))
    change(card)
    path.write_text(yaml.safe_dump(card, allow_unicode=True), encoding="utf-8")


def _errors(root: Path, **kwargs) -> list[str]:
    return flywheel_validate.validate(root, **kwargs)


def _contains(errors: list[str], text: str) -> bool:
    return any(text in error for error in errors)


def test_repository_bundle_passes_validation() -> None:
    assert flywheel_validate.validate() == []


def test_bundle_has_three_distinct_requesters_and_a_fourth_observer() -> None:
    bundle = load_bundle()

    requesters = [entry.requester for entry in bundle.roles.values()]

    assert len(set(requesters)) == 3
    assert bundle.observer not in requesters
    assert bundle.card["relation"]["cause"] == "cause-ea-filter-narrow"


def test_case_a_cause_is_not_in_any_seed_case() -> None:
    seed_causes = flywheel_validate._seed_card_causes(flywheel_validate.SEED_CARDS_DIR)

    assert load_bundle().card["relation"]["cause"] not in seed_causes


def test_case_a_card_is_not_where_the_seed_converter_reads() -> None:
    assert not (flywheel_validate.SEED_CARDS_DIR / f"{CARD}.yml").exists()


def test_case_a_cause_in_a_seed_case_is_rejected(tmp_path: Path) -> None:
    root = _copy(tmp_path)
    _edit_card(
        root,
        lambda card: card["relation"].update(
            cause="cause-ea-no-view-permission", checks=["check-ea-participation"]
        ),
    )

    errors = _errors(root)

    assert _contains(errors, "시드 사례")


def test_dangling_relation_node_is_rejected(tmp_path: Path) -> None:
    root = _copy(tmp_path)
    _edit_card(root, lambda card: card["relation"].update(symptom="sym-ea-no-such-symptom"))

    assert _contains(_errors(root), "sym-ea-no-such-symptom")


def test_check_that_does_not_belong_to_the_cause_is_rejected(tmp_path: Path) -> None:
    root = _copy(tmp_path)
    _edit_card(root, lambda card: card["relation"].update(checks=["check-ea-draft-date"]))

    assert _contains(_errors(root), "check-ea-draft-date")


def test_missing_resolved_record_field_is_rejected(tmp_path: Path) -> None:
    root = _copy(tmp_path)

    def drop(card: dict) -> None:
        card["workLogs"][1]["verification"] = ""

    _edit_card(root, drop)

    assert _contains(_errors(root), "verification")


def test_assignee_must_be_the_systems_primary_owner(tmp_path: Path) -> None:
    root = _copy(tmp_path)
    _edit_json(root, "bundle.json", lambda data: data.update(assignee="login_owner"))
    _edit_card(root, lambda card: card.update(assignee="login_owner"))

    assert _contains(_errors(root), "주담당자")


def test_requesters_must_differ_and_observer_must_be_a_fourth_person(tmp_path: Path) -> None:
    root = _copy(tmp_path)

    def same(data: dict) -> None:
        data["roles"]["B"]["requester"] = data["roles"]["A"]["requester"]
        data["observer"] = data["roles"]["C"]["requester"]

    _edit_json(root, "bundle.json", same)
    errors = _errors(root)

    assert _contains(errors, "문의자가 서로 달라야")
    assert _contains(errors, "관찰자")


def test_case_a_sentence_in_follow_up_input_is_a_leak(tmp_path: Path) -> None:
    root = _copy(tmp_path)
    cause = load_bundle().card["workLogs"][1]["confirmedCause"]
    _edit_json(
        root,
        f"scenarios/{B}.json",
        lambda data: data["checkAnswers"].update({"check-ea-filter-condition": cause}),
    )

    assert _contains(_errors(root), "입력에 사례 A의 문장이 있다")


def test_node_key_in_inquiry_text_is_a_leak(tmp_path: Path) -> None:
    root = _copy(tmp_path)
    _edit_json(
        root,
        f"scenarios/{C}.json",
        lambda data: data.update(firstMessage="cause-ea-filter-narrow 때문인가요?"),
    )

    assert _contains(_errors(root), "노드 키")


def test_case_a_sentence_in_corpus_or_runtime_is_a_leak(tmp_path: Path) -> None:
    leaked = tmp_path / "scan"
    leaked.mkdir()
    (leaked / "guide.md").write_text(
        "안내: " + load_bundle().card["workLogs"][1]["confirmedCause"], encoding="utf-8"
    )

    errors = _errors(ROOT, leak_roots=(leaked,))

    assert _contains(errors, "사례 A의 문장이")


def test_clean_scan_root_has_no_leak(tmp_path: Path) -> None:
    clean = tmp_path / "scan"
    clean.mkdir()
    (clean / "guide.md").write_text("무관한 문서", encoding="utf-8")

    assert _errors(ROOT, leak_roots=(clean,)) == []


def test_default_profile_must_hide_the_staff_only_case(tmp_path: Path) -> None:
    root = _copy(tmp_path)
    _edit_json(
        root,
        f"golds/{B}.json",
        lambda data: data["caseA"]["DEFAULT"].update(caseEvidence="CITED_AS_EVIDENCE"),
    )

    assert _contains(_errors(root), "기본 프로필")


def test_decoy_must_not_share_the_cause_of_case_a(tmp_path: Path) -> None:
    root = _copy(tmp_path)
    _edit_json(
        root,
        f"golds/{C}.json",
        lambda data: data["truth"].update(cause="cause-ea-filter-narrow"),
    )

    assert _contains(_errors(root), "달라야")


def test_follow_up_with_the_same_text_is_not_rephrased(tmp_path: Path) -> None:
    root = _copy(tmp_path)
    first = json.loads((root / "scenarios" / f"{A}.json").read_text(encoding="utf-8"))[
        "firstMessage"
    ]
    _edit_json(root, f"scenarios/{B}.json", lambda data: data.update(firstMessage=first))

    assert _contains(_errors(root), "표현이 달라야")


def test_gold_must_not_forbid_text_missing_from_the_card(tmp_path: Path) -> None:
    root = _copy(tmp_path)
    _edit_json(
        root,
        f"golds/{B}.json",
        lambda data: data.update(employeeVisibleMustNotContain=["카드에 없는 문장"]),
    )

    assert _contains(_errors(root), "카드에 없다")


def test_gold_links_must_match_the_card(tmp_path: Path) -> None:
    root = _copy(tmp_path)
    _edit_json(
        root,
        f"golds/{A}.json",
        lambda data: data["expectedCase"].update(
            expectedLinks=[{"relation": "ADDRESSES", "target": "sym-ea-doc-not-listed"}]
        ),
    )

    assert _contains(_errors(root), "relation과 다르다")


def test_extra_or_missing_files_are_rejected(tmp_path: Path) -> None:
    root = _copy(tmp_path)
    (root / "golds" / f"{C}.json").unlink()

    assert _errors(root) != []


@pytest.mark.parametrize("name", [A, B, C])
def test_scenario_is_loadable_by_the_conversation_harness(name: str) -> None:
    bundle = load_bundle()
    entry = next(item for item in bundle.roles.values() if item.scenario.scenario_id == name)

    assert entry.scenario.reveal_rule == "ASKED_FIELD_ONLY"
    assert entry.scenario.user_knowledge["systemCode"] == "ELECTRONIC_APPROVAL"
