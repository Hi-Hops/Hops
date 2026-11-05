"""시나리오 형식과 로더. 형식은 `docs/knowledge/DATA_FORMAT.md`의 "기준 대화 시나리오"를 따른다.

시나리오는 모델 입력이다. 평가 정답은 이 파일에 두지 않고 `golds/`에 별도 파일로 둔다.
"""

import json
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

SPLITS = ("DEV", "TEST")
REVEAL_RULES = ("ASKED_FIELD_ONLY",)
# 사용자가 아는 사실로 쓸 수 있는 필드. 질문이 요청하는 필드 이름과 같다.
KNOWLEDGE_FIELDS = (
    "systemCode",
    "taskOrScreen",
    "currentWorkflow",
    "expectedBehavior",
    "actualBehavior",
    "occurredAt",
    "requestedAccess",
    "businessPurpose",
    "desiredChange",
)


class ScenarioFormatError(ValueError):
    pass


@dataclass(frozen=True)
class Scenario:
    scenario_id: str
    case_family: str
    split: str
    origin: str
    first_message: str
    # 사용자가 아는 사실. 질문을 받기 전에는 공개하지 않는다. 값은 사용자가 그대로 말할 문장이다.
    user_knowledge: Mapping[str, str | None]
    # 사용자가 "모른다"고 답할 필드.
    unknown_fields: tuple[str, ...]
    reveal_rule: str
    actor_role: str = "EMPLOYEE"


def parse_scenario(data: Mapping[str, Any]) -> Scenario:
    """시나리오 JSON을 검증해 Scenario로 만든다."""
    required = (
        "scenarioId",
        "caseFamily",
        "split",
        "origin",
        "firstMessage",
        "userKnowledge",
        "unknownFields",
        "revealRule",
    )
    missing = [key for key in required if key not in data]
    if missing:
        raise ScenarioFormatError(f"필수 필드가 없다: {missing}")
    if data["split"] not in SPLITS:
        raise ScenarioFormatError(f"split은 {SPLITS} 중 하나여야 한다: {data['split']}")
    if data["revealRule"] not in REVEAL_RULES:
        raise ScenarioFormatError(f"지원하지 않는 revealRule: {data['revealRule']}")
    if not str(data["firstMessage"]).strip():
        raise ScenarioFormatError("firstMessage가 비어 있다")
    knowledge = dict(data["userKnowledge"])
    unknown_keys = [key for key in knowledge if key not in KNOWLEDGE_FIELDS]
    if unknown_keys:
        raise ScenarioFormatError(f"알 수 없는 userKnowledge 필드: {unknown_keys}")
    unknown_fields = tuple(data["unknownFields"])
    bad = [name for name in unknown_fields if name not in KNOWLEDGE_FIELDS]
    if bad:
        raise ScenarioFormatError(f"알 수 없는 unknownFields: {bad}")
    overlap = [name for name in unknown_fields if knowledge.get(name)]
    if overlap:
        raise ScenarioFormatError(f"아는 사실과 모르는 필드가 겹친다: {overlap}")
    return Scenario(
        scenario_id=str(data["scenarioId"]),
        case_family=str(data["caseFamily"]),
        split=str(data["split"]),
        origin=str(data["origin"]),
        first_message=str(data["firstMessage"]),
        user_knowledge=knowledge,
        unknown_fields=unknown_fields,
        reveal_rule=str(data["revealRule"]),
        actor_role=str(data.get("actor", {}).get("role", "EMPLOYEE")),
    )


def load_scenario(path: Path) -> Scenario:
    return parse_scenario(json.loads(path.read_text(encoding="utf-8")))
