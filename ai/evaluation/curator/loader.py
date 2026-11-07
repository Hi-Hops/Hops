"""시나리오와 정답을 함께 읽는다. 선택(분할·계열)도 여기서 한다."""

from pathlib import Path

from evaluation.curator.gold import CuratorGold, load_gold
from evaluation.curator.scenario import CARDS_DIR, CuratorScenario, load_scenario
from evaluation.curator.validate import GOLD_DIR, SCENARIO_DIR


def load_all(
    scenario_dir: Path = SCENARIO_DIR,
    gold_dir: Path = GOLD_DIR,
    cards_dir: Path = CARDS_DIR,
) -> list[tuple[CuratorScenario, CuratorGold]]:
    pairs = []
    for path in sorted(scenario_dir.glob("*.json")):
        scenario = load_scenario(path, cards_dir)
        pairs.append((scenario, load_gold(gold_dir / f"{scenario.scenario_id}.json")))
    return pairs


def select(
    pairs: list[tuple[CuratorScenario, CuratorGold]],
    case_families: list[str],
    split: str | None,
    *,
    allow_test: bool,
) -> list[tuple[CuratorScenario, CuratorGold]]:
    """계열 이름이나 분할로 고른다. 동결된 TEST는 허용 표시 없이 고를 수 없다."""
    known = {scenario.case_family for scenario, _ in pairs}
    unknown = [name for name in case_families if name not in known]
    if unknown:
        raise ValueError(f"알 수 없는 caseFamily: {unknown}")
    chosen = [
        (scenario, gold)
        for scenario, gold in pairs
        if (not case_families or scenario.case_family in case_families)
        and (split is None or scenario.split == split)
    ]
    if not case_families and split is None:
        chosen = [(s, g) for s, g in chosen if s.split == "DEV"]
    if any(scenario.split == "TEST" for scenario, _ in chosen) and not allow_test:
        raise ValueError("TEST 시나리오는 동결되어 있다. --allow-test 없이는 돌리지 않는다")
    return chosen
