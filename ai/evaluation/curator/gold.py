"""Curator 평가 정답의 형식과 로더. 모델 입력과 AI 런타임에 들어가지 않는다."""

import json
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

RELATIONS = ("ADDRESSES", "USES_CHECK")
BEHAVIOR_PROPERTIES = ("CB1", "CB2", "CB3", "CB4")
GOLD_KEYS = {
    "scenarioId",
    "acceptedReusable",
    "expectedLinks",
    "forbiddenLinks",
    "forbiddenPhrases",
    "behaviorProperties",
}


class CuratorGoldError(ValueError):
    pass


@dataclass(frozen=True)
class GoldLink:
    relation: str
    # 노드 카탈로그 키(예: sym-ea-doc-not-listed). ID가 아니다.
    target: str


@dataclass(frozen=True)
class CuratorGold:
    scenario_id: str
    # 허용되는 reusable 값의 집합(하나의 정확한 값이 아니라 "이 중 하나면 된다")
    accepted_reusable: tuple[bool, ...]
    expected_links: tuple[GoldLink, ...]
    forbidden_links: tuple[GoldLink, ...]
    # 사례 문구(제목·문제 설명·적용 조건·한계)에 나오면 안 되는 구절
    forbidden_phrases: tuple[str, ...]
    behavior_properties: tuple[str, ...]


def _links(name: str, values: Any) -> tuple[GoldLink, ...]:
    if not isinstance(values, list):
        raise CuratorGoldError(f"{name}은 목록이어야 한다")
    links = []
    for item in values:
        if not isinstance(item, Mapping) or set(item) != {"relation", "target"}:
            raise CuratorGoldError(f"{name}의 항목은 relation과 target만 가진다")
        if item["relation"] not in RELATIONS:
            raise CuratorGoldError(f"{name}.relation은 {RELATIONS} 중 하나여야 한다")
        links.append(GoldLink(str(item["relation"]), str(item["target"])))
    if len(links) != len(set(links)):
        raise CuratorGoldError(f"{name}에 중복이 있다")
    return tuple(links)


def parse_gold(data: Mapping[str, Any]) -> CuratorGold:
    extra = set(data) - GOLD_KEYS
    if extra:
        raise CuratorGoldError(f"알 수 없는 키 {sorted(extra)}")
    missing = GOLD_KEYS - set(data)
    if missing:
        raise CuratorGoldError(f"필수 키가 없다 {sorted(missing)}")
    accepted = data["acceptedReusable"]
    if (
        not isinstance(accepted, list)
        or not accepted
        or any(not isinstance(item, bool) for item in accepted)
        or len(accepted) != len(set(accepted))
    ):
        raise CuratorGoldError("acceptedReusable은 중복 없는 비어 있지 않은 bool 목록이어야 한다")
    phrases = data["forbiddenPhrases"]
    if not isinstance(phrases, list) or any(not str(item).strip() for item in phrases):
        raise CuratorGoldError("forbiddenPhrases는 비어 있지 않은 문자열 목록이어야 한다")
    behavior = data["behaviorProperties"]
    if not isinstance(behavior, list) or any(item not in BEHAVIOR_PROPERTIES for item in behavior):
        raise CuratorGoldError(f"behaviorProperties는 {BEHAVIOR_PROPERTIES}의 부분집합이어야 한다")
    return CuratorGold(
        scenario_id=str(data["scenarioId"]),
        accepted_reusable=tuple(accepted),
        expected_links=_links("expectedLinks", data["expectedLinks"]),
        forbidden_links=_links("forbiddenLinks", data["forbiddenLinks"]),
        forbidden_phrases=tuple(str(item) for item in phrases),
        behavior_properties=tuple(behavior),
    )


def load_gold(path: Path) -> CuratorGold:
    return parse_gold(json.loads(path.read_text(encoding="utf-8")))
