"""Curator 시나리오·정답 파일의 형식과 정합성 검사. 모델을 부르지 않는다.

검사하는 것: 형식·어휘, 시나리오와 정답의 짝, 정답의 노드 키가 카탈로그에 있고 종류·시스템이
맞는지, 카드 기반
시나리오의 기대 연결이 카드의 `relation`과 같은지, 입력에 정답 노드 키가 새지 않았는지, 상담
근거 문서가 코퍼스에
있는지, 필수 분포.
검사하지 않는 것: 문장의 내용이 사실에 맞는지, 정답이 타당한지. 그것은 사람이 검수한다.

    uv run python -m evaluation.curator.validate
"""

import re
import sys
from collections.abc import Sequence
from pathlib import Path

import yaml

from evaluation.curator.candidates import (
    CHECK_ITEM,
    SYMPTOM,
    CatalogNode,
    load_catalog,
)
from evaluation.curator.gold import CuratorGold, CuratorGoldError, load_gold
from evaluation.curator.scenario import (
    CARDS_DIR,
    CuratorScenario,
    CuratorScenarioError,
    load_scenario,
)
from evaluation.harness.corpus_source import DEFAULT_CORPUS_DIR

ROOT = Path(__file__).resolve().parents[1]
SCENARIO_DIR = ROOT / "curator_scenarios"
GOLD_DIR = ROOT / "curator_golds"
SCENARIO_ID_PATTERN = re.compile(r"^C-(?P<family>[a-z0-9-]+)-001$")
# 입력(시나리오)에 있으면 정답이 샌 것이다. 노드 키(sym-/cause-/check- 로 시작하는 카탈로그
# 식별자)다.
LEAK_PATTERN = re.compile(r"\b(sym|cause|check)-[a-z0-9-]+")
WANTED_KIND = {"ADDRESSES": SYMPTOM, "USES_CHECK": CHECK_ITEM}
# 필수 분포(계획): 아래 상황이 최소 한 번은 있어야 한다.
REQUIRED_SITUATIONS = ("UNRESOLVED", "FAILED_ATTEMPT", "CAUSE_UNKNOWN", "INJECTION")


def _texts(scenario: CuratorScenario) -> list[str]:
    parts = [scenario.ticket_title, scenario.summary, *scenario.unknowns]
    parts += [item.text for item in scenario.messages]
    parts += [fact.text for fact in scenario.facts]
    parts += [value for value in scenario.details.values() if value]
    for record in scenario.records:
        parts += [
            value for value in (record.action, record.verification, record.confirmed_cause) if value
        ]
    return parts


def _card_relation(scenario: CuratorScenario, cards_dir: Path) -> set[tuple[str, str]]:
    card = yaml.safe_load((cards_dir / f"{scenario.card}.yml").read_text(encoding="utf-8"))
    relation = card["relation"]
    expected = {("ADDRESSES", relation["symptom"])}
    expected |= {("USES_CHECK", key) for key in relation.get("checks") or []}
    return expected


def situations(scenario: CuratorScenario) -> set[str]:
    """시나리오가 다루는 상황 표시(필수 분포 확인용)."""
    found: set[str] = set()
    if scenario.confirmation_state == "UNRESOLVED":
        found.add("UNRESOLVED")
    if any(record.kind == "FAILED_ATTEMPT" for record in scenario.records):
        found.add("FAILED_ATTEMPT")
    if scenario.records[-1].confirmed_cause is None:
        found.add("CAUSE_UNKNOWN")
    text = "\n".join(_texts(scenario))
    if "지시" in text and ("무시" in text or "모두" in text):
        found.add("INJECTION")
    return found


def _validate_pair(
    scenario: CuratorScenario,
    gold: CuratorGold,
    catalog: dict[str, CatalogNode],
    cards_dir: Path,
    corpus_dir: Path,
    errors: list[str],
) -> None:
    prefix = scenario.scenario_id
    match = SCENARIO_ID_PATTERN.match(scenario.scenario_id)
    if not match or match["family"] != scenario.case_family:
        errors.append(f"{prefix}: scenarioId는 C-{{caseFamily}}-001이어야 한다")
    for text in _texts(scenario):
        leak = LEAK_PATTERN.search(text)
        if leak:
            errors.append(f"{prefix}: 입력에 카탈로그 노드 키가 있다(정답 누출): {leak[0]}")
    for reference in scenario.consulted_knowledge:
        key, _, version = reference.partition("@")
        if not version.isdigit() or not list(corpus_dir.glob(f"*/{key}.md")):
            errors.append(f"{prefix}: 상담 근거 문서를 찾을 수 없다: {reference}")
    if gold.scenario_id != scenario.scenario_id:
        errors.append(f"{prefix}: 정답의 scenarioId가 다르다: {gold.scenario_id}")
    for name, links in (
        ("expectedLinks", gold.expected_links),
        ("forbiddenLinks", gold.forbidden_links),
    ):
        for link in links:
            node = catalog.get(link.target)
            if node is None:
                errors.append(f"{prefix}: {name}의 노드가 카탈로그에 없다: {link.target}")
            elif node.kind != WANTED_KIND[link.relation]:
                errors.append(f"{prefix}: {name}의 관계 종류와 노드 종류가 다르다: {link.target}")
            elif node.system_code != scenario.system_code:
                errors.append(f"{prefix}: {name}의 노드가 다른 시스템의 것이다: {link.target}")
    if set(gold.expected_links) & set(gold.forbidden_links):
        errors.append(f"{prefix}: 기대 연결과 금지 연결이 겹친다")
    selected = set(gold.behavior_properties)
    if "CB2" in selected and not gold.expected_links:
        errors.append(f"{prefix}: CB2를 고르려면 expectedLinks가 필요하다")
    if "CB3" in selected and not gold.forbidden_links:
        errors.append(f"{prefix}: CB3을 고르려면 forbiddenLinks가 필요하다")
    if "CB4" in selected and not gold.forbidden_phrases:
        errors.append(f"{prefix}: CB4를 고르려면 forbiddenPhrases가 필요하다")
    if "CB1" not in selected:
        errors.append(f"{prefix}: CB1은 항상 고른다")
    if scenario.confirmation_state == "UNRESOLVED" and (
        gold.accepted_reusable != (False,) or gold.expected_links
    ):
        errors.append(f"{prefix}: 미해결 기록의 정답은 사례 없음(False)이고 기대 연결이 없다")
    if scenario.card is not None:
        actual = {(link.relation, link.target) for link in gold.expected_links}
        if actual != _card_relation(scenario, cards_dir):
            errors.append(f"{prefix}: expectedLinks가 카드 {scenario.card}의 relation과 다르다")


def validate(
    scenario_dir: Path = SCENARIO_DIR,
    gold_dir: Path = GOLD_DIR,
    cards_dir: Path = CARDS_DIR,
    corpus_dir: Path = DEFAULT_CORPUS_DIR,
    *,
    require_situations: bool = True,
) -> list[str]:
    errors: list[str] = []
    catalog = load_catalog(corpus_dir)
    scenarios: dict[str, CuratorScenario] = {}
    golds: dict[str, CuratorGold] = {}
    for path in sorted(scenario_dir.glob("*.json")):
        try:
            scenario = load_scenario(path, cards_dir)
        except (CuratorScenarioError, KeyError, ValueError) as error:
            errors.append(f"{path.name}: {error}")
            continue
        if path.stem != scenario.scenario_id:
            errors.append(f"{path.name}: 파일 이름이 scenarioId와 다르다")
        if scenario.scenario_id in scenarios:
            errors.append(f"{scenario.scenario_id}: 시나리오가 중복됐다")
        scenarios[scenario.scenario_id] = scenario
    for path in sorted(gold_dir.glob("*.json")):
        try:
            gold = load_gold(path)
        except (CuratorGoldError, KeyError, ValueError) as error:
            errors.append(f"{path.name}: {error}")
            continue
        if path.stem != gold.scenario_id:
            errors.append(f"{path.name}: 파일 이름이 scenarioId와 다르다")
        golds[gold.scenario_id] = gold
    for scenario_id in sorted(set(scenarios) - set(golds)):
        errors.append(f"{scenario_id}: 정답 파일이 없다")
    for scenario_id in sorted(set(golds) - set(scenarios)):
        errors.append(f"{scenario_id}: 시나리오 없는 정답 파일이다")
    families = [item.case_family for item in scenarios.values()]
    if len(families) != len(set(families)):
        errors.append("caseFamily가 중복됐다")
    for scenario_id in sorted(set(scenarios) & set(golds)):
        _validate_pair(
            scenarios[scenario_id], golds[scenario_id], catalog, cards_dir, corpus_dir, errors
        )
    if require_situations:
        covered: set[str] = set()
        for scenario in scenarios.values():
            covered |= situations(scenario)
        for situation in REQUIRED_SITUATIONS:
            if situation not in covered:
                errors.append(f"필수 상황이 없다: {situation}")
    return errors


def main(argv: Sequence[str] | None = None) -> int:
    errors = validate()
    scenarios = list(SCENARIO_DIR.glob("*.json"))
    for error in errors:
        print(f"- {error}")
    print(f"시나리오 {len(scenarios)}개, 오류 {len(errors)}건")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
