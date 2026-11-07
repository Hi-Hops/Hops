"""시나리오 형식과 로더. 형식은 `docs/knowledge/DATA_FORMAT.md`의 "기준 대화 시나리오"를 따른다.

시나리오는 모델 입력이다. 평가 정답은 이 파일에 두지 않고 `golds/`에 별도 파일로 둔다.
"""

import json
from collections.abc import Mapping
from dataclasses import dataclass, field
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
# 안내를 받은 뒤 사용자가 보내는 해결 응답의 종류. 재생기 지원은 멀티턴 작업(#189)에서 한다.
FOLLOW_UP_RESOLUTIONS = ("UNRESOLVED",)
# 환경에 주입하는 결함. 재생기는 역할을 만들지 않으므로 호출자가 이 값을 보고 역할을 구성한다.
FAULTS = ("RETRIEVAL_ERROR",)


class ScenarioFormatError(ValueError):
    pass


@dataclass(frozen=True)
class Correction:
    """AI의 `after_turn`번째 질문에 대한 응답 끝에 덧붙는, 규칙으로 정해진 정정 문장."""

    after_turn: int
    text: str


@dataclass(frozen=True)
class FollowUp:
    """AI가 질문 없이 안내로 끝난 뒤 사용자가 보내는 응답. 재생기 지원은 #189에서 한다."""

    text: str
    resolution: str


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
    corrections: tuple[Correction, ...] = ()
    follow_up: FollowUp | None = None
    faults: tuple[str, ...] = ()
    # 확인 항목 ID → 사용자가 그 질문에 할 답. 없는 항목을 물으면 모른다고 답한다.
    check_answers: Mapping[str, str] = field(default_factory=dict)


def _parse_corrections(raw: Any) -> tuple[Correction, ...]:
    corrections = []
    for item in raw:
        after_turn, text = item.get("afterTurn"), str(item.get("text", "")).strip()
        if not isinstance(after_turn, int) or isinstance(after_turn, bool) or after_turn < 1:
            raise ScenarioFormatError("corrections.afterTurn은 1 이상의 정수여야 한다")
        if not text:
            raise ScenarioFormatError("corrections.text가 비어 있다")
        corrections.append(Correction(after_turn, text))
    turns = [item.after_turn for item in corrections]
    if len(turns) != len(set(turns)):
        raise ScenarioFormatError("같은 turn에 정정이 둘 이상 있다")
    return tuple(corrections)


def _parse_follow_up(raw: Any) -> FollowUp | None:
    if raw is None:
        return None
    text, resolution = str(raw.get("text", "")).strip(), raw.get("resolution")
    if not text:
        raise ScenarioFormatError("followUp.text가 비어 있다")
    if resolution not in FOLLOW_UP_RESOLUTIONS:
        raise ScenarioFormatError(f"followUp.resolution은 {FOLLOW_UP_RESOLUTIONS} 중 하나여야 한다")
    return FollowUp(text, resolution)


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
    faults = tuple(data.get("faults", []))
    bad_faults = [name for name in faults if name not in FAULTS]
    if bad_faults:
        raise ScenarioFormatError(f"알 수 없는 faults: {bad_faults}")
    check_answers = dict(data.get("checkAnswers", {}))
    bad_answers = [key for key, text in check_answers.items() if not str(text).strip()]
    if bad_answers:
        raise ScenarioFormatError(f"checkAnswers의 답이 비어 있다: {bad_answers}")
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
        corrections=_parse_corrections(data.get("corrections", [])),
        follow_up=_parse_follow_up(data.get("followUp")),
        faults=faults,
        check_answers={key: str(text).strip() for key, text in check_answers.items()},
    )


def load_scenario(path: Path) -> Scenario:
    return parse_scenario(json.loads(path.read_text(encoding="utf-8")))
