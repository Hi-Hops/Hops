"""선순환 묶음(문의 A·B·C)의 로더.

형식은 `docs/knowledge/DATA_FORMAT.md`의 "지식 선순환 검증 묶음"을 따른다.
묶음 하나는 다음으로 이뤄진다.
- `bundle.json`: 역할·문의자·담당자·공개 프로필
- `scenarios/`: 문의 A·B·C의 모델 입력
- `golds/`: 역할별 정답
- `cards/`: 사례 A가 만들어질 때 담당자가 남길 처리 기록과 좋은 사례의 모습

모두 AI 런타임이 읽지 못하는 `evaluation/` 아래에 있다. 사례 A는 시드가 아니므로
`docs/knowledge/cases/`에 두지 않는다.
"""

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from evaluation.harness.scenario import Scenario, ScenarioFormatError, parse_scenario

ROOT = Path(__file__).resolve().parent
ROLES = ("A", "B", "C")
PROFILES = ("DEFAULT", "INTERNAL_JUDGEMENT")
BUNDLE_SPLIT = "BUNDLE"
MANIFEST_KEYS = {"bundleId", "systemCode", "assignee", "observer", "card", "profiles", "roles"}


class BundleError(ValueError):
    pass


@dataclass(frozen=True)
class RoleEntry:
    role: str
    requester: str
    scenario: Scenario
    # 시나리오 JSON 원본(첫 문장 비교 같은 형식 검사용)
    scenario_raw: dict[str, Any]
    gold: dict[str, Any]


@dataclass(frozen=True)
class Bundle:
    bundle_id: str
    system_code: str
    assignee: str
    observer: str
    profiles: tuple[str, ...]
    card: dict[str, Any]
    roles: dict[str, RoleEntry]


def _json(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise BundleError(f"{path.name}: 읽을 수 없다: {error}") from error
    if not isinstance(data, dict):
        raise BundleError(f"{path.name}: 객체여야 한다")
    return data


def load_card(path: Path) -> dict[str, Any]:
    try:
        card = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as error:
        raise BundleError(f"{path.name}: 읽을 수 없다: {error}") from error
    if not isinstance(card, dict):
        raise BundleError(f"{path.name}: 객체여야 한다")
    return card


def load_bundle(root: Path = ROOT) -> Bundle:
    manifest = _json(root / "bundle.json")
    if set(manifest) != MANIFEST_KEYS:
        raise BundleError(f"bundle.json의 키가 다르다: {sorted(set(manifest) ^ MANIFEST_KEYS)}")
    if set(manifest["roles"]) != set(ROLES):
        raise BundleError(f"roles는 {ROLES}를 모두 가져야 한다")
    roles: dict[str, RoleEntry] = {}
    for role in ROLES:
        entry = manifest["roles"][role]
        scenario_id = entry["scenarioId"]
        raw = _json(root / "scenarios" / f"{scenario_id}.json")
        if raw.get("split") != BUNDLE_SPLIT:
            raise BundleError(f"{scenario_id}: split은 {BUNDLE_SPLIT}이어야 한다")
        try:
            # 하네스 시나리오 형식을 그대로 쓴다. split만 이 묶음의 값이라 검사를 위해 바꿔 넘긴다.
            scenario = parse_scenario({**raw, "split": "DEV"})
        except ScenarioFormatError as error:
            raise BundleError(f"{scenario_id}: {error}") from error
        gold = _json(root / "golds" / f"{scenario_id}.json")
        roles[role] = RoleEntry(role, entry["requester"], scenario, raw, gold)
    card = load_card(root / "cards" / f"{manifest['card']}.yml")
    return Bundle(
        bundle_id=manifest["bundleId"],
        system_code=manifest["systemCode"],
        assignee=manifest["assignee"],
        observer=manifest["observer"],
        profiles=tuple(manifest["profiles"]),
        card=card,
        roles=roles,
    )
