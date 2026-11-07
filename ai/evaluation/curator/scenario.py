"""Curator 평가 시나리오와 정답의 형식·로더.

시나리오는 사례 한 건이 만들어지기까지의 **문의 전체 과정**(문답, 처리 기록, 해결 확인 이력,
상담 근거)이며
모델 입력이다. 정답(`curator_golds/`)은 별도 파일이고 AI 런타임이 읽지 못한다. 형식은 두 가지다.

- 카드 기반: `card`에 시드 사례 카드 키(`docs/knowledge/cases/{키}.yml`)를 적으면 문답·표준
요청·처리 기록을
  카드에서 읽는다. 카드의 사례 문구(`case`)와 연결(`relation`)은 입력에 쓰지 않는다. 정답 쪽 연결은
  카드의 `relation`과 같아야 한다(validate가 대조한다).
- 직접 작성: 입력 필드를 시나리오에 모두 쓴다.

`docs/knowledge/DATA_FORMAT.md`의 "Curator 평가 시나리오"를 따른다.
"""

import json
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from evaluation.harness.catalog import SYSTEMS

SPLITS = ("DEV", "TEST")
ORIGINS = ("SYNTHETIC",)
SENDERS = ("USER", "AI")
RECORD_KINDS = ("PROGRESS", "FAILED_ATTEMPT", "VERIFIED_RESOLUTION")
CONFIRMATION_STATES = ("PENDING", "RESOLVED", "UNRESOLVED")
HISTORY_SOURCES = ("GUIDE", "WORK_RECORD")
HISTORY_STATES = ("RESOLVED", "UNRESOLVED")
INTENTS = ("HOW_TO", "ACCESS_REQUEST", "OBSERVED_SYMPTOM", "FEATURE_REQUEST", "IMPROVEMENT_REQUEST")
WORK_TYPES = ("SERVICE_REQUEST", "INVESTIGATION", "INCIDENT", "CHANGE_REQUEST", "REVIEW_REQUIRED")
DETAIL_FIELDS = (
    "taskOrScreen",
    "requestedAccess",
    "businessPurpose",
    "expectedBehavior",
    "actualBehavior",
    "occurredAt",
    "currentWorkflow",
    "desiredChange",
)
SYSTEM_CODES = tuple(system["code"] for system in SYSTEMS)
CARDS_DIR = Path(__file__).resolve().parents[3] / "docs" / "knowledge" / "cases"
CARD_RECORD_KINDS = {"PROGRESS": "PROGRESS", "RESOLVED": "VERIFIED_RESOLUTION"}


class CuratorScenarioError(ValueError):
    pass


@dataclass(frozen=True)
class Message:
    sender: str
    text: str


@dataclass(frozen=True)
class Fact:
    text: str
    # 근거가 된 메시지 번호(1부터)
    messages: tuple[int, ...]


@dataclass(frozen=True)
class Record:
    kind: str
    action: str
    verification: str | None = None
    confirmed_cause: str | None = None
    applies_to_system_version: str | None = None


@dataclass(frozen=True)
class HistoryEntry:
    source: str
    state: str


@dataclass(frozen=True)
class CuratorScenario:
    scenario_id: str
    case_family: str
    split: str
    origin: str
    card: str | None
    system_code: str
    ticket_title: str
    intent: str
    work_type: str
    summary: str
    facts: tuple[Fact, ...]
    unknowns: tuple[str, ...]
    details: Mapping[str, str | None]
    messages: tuple[Message, ...]
    # 오래된 순. 마지막이 사례의 원 기록(VERIFIED_RESOLUTION)이다.
    records: tuple[Record, ...]
    confirmation_state: str
    resolution_history: tuple[HistoryEntry, ...]
    # `문서키@버전` 목록. 상담에 쓴 문서이며 입력에는 제목·종류·적용 조건이 들어간다.
    consulted_knowledge: tuple[str, ...]


def _require(data: Mapping[str, Any], keys: tuple[str, ...], where: str) -> None:
    missing = [key for key in keys if key not in data]
    if missing:
        raise CuratorScenarioError(f"{where}: 필수 필드가 없다 {missing}")


def _card(card_key: str, cards_dir: Path) -> dict[str, Any]:
    path = cards_dir / f"{card_key}.yml"
    if not path.is_file():
        raise CuratorScenarioError(f"사례 카드가 없다: {card_key}")
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise CuratorScenarioError(f"사례 카드 형식이 올바르지 않다: {card_key}")
    return data


def _from_card(card: Mapping[str, Any]) -> dict[str, Any]:
    """카드의 입력에 해당하는 부분만 시나리오 필드로 옮긴다. 사례 문구와 연결은 옮기지 않는다."""
    request = card["request"]
    return {
        "systemCode": card["system"],
        "ticketTitle": card["ticket"]["title"],
        "intent": request["intent"],
        "workType": request["workType"],
        "summary": request["summary"],
        "facts": [
            {"text": fact["text"], "messages": list(fact["messages"])} for fact in request["facts"]
        ],
        "unknowns": list(request.get("missing") or []),
        "details": dict(request.get("details") or {}),
        "messages": [{"sender": item["sender"], "text": item["text"]} for item in card["dialogue"]],
        "records": [
            {
                "kind": CARD_RECORD_KINDS[log["type"]],
                "action": log["action"],
                "verification": log.get("verification"),
                "confirmedCause": log.get("confirmedCause"),
            }
            for log in card["workLogs"]
        ],
    }


def parse_scenario(data: Mapping[str, Any], cards_dir: Path = CARDS_DIR) -> CuratorScenario:
    """시나리오 JSON을 검증해 CuratorScenario로 만든다. 형식과 어휘만 보며 내용의 사실 여부는
    보지 않는다."""
    _require(data, ("scenarioId", "caseFamily", "split", "origin"), "시나리오")
    if data["split"] not in SPLITS:
        raise CuratorScenarioError(f"split은 {SPLITS} 중 하나여야 한다: {data['split']}")
    if data["origin"] not in ORIGINS:
        raise CuratorScenarioError(f"origin은 {ORIGINS} 중 하나여야 한다: {data['origin']}")
    card_key = data.get("card")
    body: Mapping[str, Any]
    if card_key is not None:
        extra = [key for key in data if key in _from_card_keys()]
        if extra:
            raise CuratorScenarioError(f"card와 함께 입력 필드를 쓸 수 없다: {extra}")
        body = _from_card(_card(str(card_key), cards_dir))
    else:
        body = data
        _require(
            body,
            (
                "systemCode",
                "ticketTitle",
                "intent",
                "workType",
                "summary",
                "facts",
                "unknowns",
                "details",
                "messages",
                "records",
            ),
            "직접 작성 시나리오",
        )
    if body["systemCode"] not in SYSTEM_CODES:
        raise CuratorScenarioError(f"알 수 없는 systemCode: {body['systemCode']}")
    if body["intent"] not in INTENTS or body["workType"] not in WORK_TYPES:
        raise CuratorScenarioError("intent 또는 workType이 어휘 밖이다")
    messages = tuple(_message(item) for item in body["messages"])
    if not any(item.sender == "USER" for item in messages):
        raise CuratorScenarioError("사용자 메시지가 하나도 없다")
    facts = tuple(_fact(item, len(messages)) for item in body["facts"])
    bad_details = [key for key in body["details"] if key not in DETAIL_FIELDS]
    if bad_details:
        raise CuratorScenarioError(f"알 수 없는 details 필드: {bad_details}")
    records = tuple(_record(item) for item in body["records"])
    if not records or records[-1].kind != "VERIFIED_RESOLUTION":
        raise CuratorScenarioError("마지막 처리 기록은 VERIFIED_RESOLUTION이어야 한다")
    if any(item.kind == "VERIFIED_RESOLUTION" and not item.verification for item in records):
        raise CuratorScenarioError("VERIFIED_RESOLUTION에는 검증 결과가 필요하다")
    state = str(data.get("confirmationState", "PENDING"))
    if state not in CONFIRMATION_STATES:
        raise CuratorScenarioError(f"confirmationState는 {CONFIRMATION_STATES} 중 하나여야 한다")
    history = tuple(_history(item) for item in data.get("resolutionHistory", []))
    consulted = tuple(str(item) for item in data.get("consultedKnowledge", []))
    if len(consulted) != len(set(consulted)):
        raise CuratorScenarioError("consultedKnowledge에 중복이 있다")
    return CuratorScenario(
        scenario_id=str(data["scenarioId"]),
        case_family=str(data["caseFamily"]),
        split=str(data["split"]),
        origin=str(data["origin"]),
        card=None if card_key is None else str(card_key),
        system_code=str(body["systemCode"]),
        ticket_title=str(body["ticketTitle"]),
        intent=str(body["intent"]),
        work_type=str(body["workType"]),
        summary=str(body["summary"]),
        facts=facts,
        unknowns=tuple(str(item) for item in body["unknowns"]),
        details=dict(body["details"]),
        messages=messages,
        records=records,
        confirmation_state=state,
        resolution_history=history,
        consulted_knowledge=consulted,
    )


def _from_card_keys() -> tuple[str, ...]:
    return (
        "systemCode",
        "ticketTitle",
        "intent",
        "workType",
        "summary",
        "facts",
        "unknowns",
        "details",
        "messages",
        "records",
    )


def _message(item: Mapping[str, Any]) -> Message:
    _require(item, ("sender", "text"), "messages")
    if item["sender"] not in SENDERS:
        raise CuratorScenarioError(f"messages.sender는 {SENDERS} 중 하나여야 한다")
    if not str(item["text"]).strip():
        raise CuratorScenarioError("messages.text가 비어 있다")
    return Message(str(item["sender"]), str(item["text"]))


def _fact(item: Mapping[str, Any], message_count: int) -> Fact:
    _require(item, ("text", "messages"), "facts")
    numbers = tuple(item["messages"])
    if not numbers or any(
        not isinstance(n, int) or isinstance(n, bool) or not 1 <= n <= message_count
        for n in numbers
    ):
        raise CuratorScenarioError(
            "facts.messages는 메시지 번호(1부터)의 비어 있지 않은 목록이어야 한다"
        )
    return Fact(str(item["text"]), numbers)


def _record(item: Mapping[str, Any]) -> Record:
    _require(item, ("kind", "action"), "records")
    if item["kind"] not in RECORD_KINDS:
        raise CuratorScenarioError(f"records.kind는 {RECORD_KINDS} 중 하나여야 한다")
    if not str(item["action"]).strip():
        raise CuratorScenarioError("records.action이 비어 있다")
    return Record(
        kind=str(item["kind"]),
        action=str(item["action"]),
        verification=item.get("verification"),
        confirmed_cause=item.get("confirmedCause"),
        applies_to_system_version=item.get("appliesToSystemVersion"),
    )


def _history(item: Mapping[str, Any]) -> HistoryEntry:
    _require(item, ("source", "state"), "resolutionHistory")
    if item["source"] not in HISTORY_SOURCES or item["state"] not in HISTORY_STATES:
        raise CuratorScenarioError("resolutionHistory의 source 또는 state가 어휘 밖이다")
    return HistoryEntry(str(item["source"]), str(item["state"]))


def load_scenario(path: Path, cards_dir: Path = CARDS_DIR) -> CuratorScenario:
    return parse_scenario(json.loads(path.read_text(encoding="utf-8")), cards_dir)
