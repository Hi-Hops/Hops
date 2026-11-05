"""시드 해결 사례 카드(docs/knowledge/cases/*.yml)를 읽고 검증한다.

카드 형식과 규칙은 docs/knowledge/DATA_FORMAT.md "시드 해결 사례 카드", 사례 목록은 VIRTUAL_COMPANY.md 7절이 정한다.
근거 문서와 증상·원인·확인 항목은 코퍼스(corpus.py)가 읽은 값과 맞춰 본다.
"""

from __future__ import annotations

import re
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import yaml
from corpus import (
    CANON_PATH,
    REPO_ROOT,
    SYSTEM_FEATURES,
    Document,
)

CASES_DIR = REPO_ROOT / "docs" / "knowledge" / "cases"
ID_NAMESPACE_PREFIX = "hops-seed-case.example/"

REQUESTERS = {"requester1", "requester2", "requester3", "requester4"}
# 정본 4.2절: 시스템마다 현재 주담당자는 한 명이다.
PRIMARY_OWNERS = {"ELECTRONIC_APPROVAL": "ea_owner", "COMMON_LOGIN": "login_owner", "FILE_SHARING": "file_owner"}
# 개발용 회원 seed(R__seed_dev_department_member.sql)의 고정 ID. 승인 스냅샷의 승인자 ID에 쓴다.
MEMBER_IDS = {
    "ea_owner": "a0000000-0000-7000-8000-000000000011",
    "login_owner": "a0000000-0000-7000-8000-000000000012",
    "file_owner": "a0000000-0000-7000-8000-000000000013",
}
INTENTS = {"HOW_TO", "ACCESS_REQUEST", "OBSERVED_SYMPTOM", "FEATURE_REQUEST", "IMPROVEMENT_REQUEST"}
WORK_TYPES = {"SERVICE_REQUEST", "INVESTIGATION", "INCIDENT", "CHANGE_REQUEST", "REVIEW_REQUIRED"}
DIALOGUE_SENDERS = ["USER", "AI", "USER", "AI"]
WORK_LOG_TYPES = ["PROGRESS", "RESOLVED"]

# 문의자에게 나가는 AI 메시지에 있으면 안 되는 문구. 확인 항목을 묻기 위해 쓰는 '쿠키 설정' 같은 표현은
# 직원 공개 질문이라 허용하되, 원인을 단정하거나 내부 ID를 노출하는 말은 막는다.
AI_FORBIDDEN_TERMS = ("런북", "SYSTEM_STAFF", "sym-", "cause-", "check-", "원인")

REQUIRED_FIELDS = (
    "caseKey", "caseVersion", "seedNo", "system", "feature", "occurredAt", "requester", "assignee", "inquiryTitle",
    "dialogue", "request", "ticket", "workLogs", "case", "relation", "basis",
)


@dataclass(frozen=True)
class CaseCard:
    key: str
    path: Path
    data: dict

    @property
    def seed_no(self) -> int:
        return int(self.data["seedNo"])

    @property
    def occurred_at(self) -> datetime:
        return datetime.fromisoformat(str(self.data["occurredAt"]))

    @property
    def relative_path(self) -> str:
        try:
            return self.path.relative_to(REPO_ROOT).as_posix()
        except ValueError:
            return self.path.as_posix()

    def id_for(self, role: str) -> str:
        """카드 안의 역할마다 고정된 UUID. 같은 카드는 어느 PC에서든 같은 ID가 된다."""
        return str(uuid.uuid5(uuid.NAMESPACE_URL, f"{ID_NAMESPACE_PREFIX}{self.key}/{role}"))


def load_cards(cases_dir: Path = CASES_DIR) -> list[CaseCard]:
    cards = []
    for path in sorted(cases_dir.glob("*.yml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
        if not isinstance(data, dict) or "caseKey" not in data:
            raise ValueError(f"{path}: caseKey가 없습니다.")
        cards.append(CaseCard(key=str(data["caseKey"]), path=path, data=data))
    return sorted(cards, key=lambda card: card.seed_no if "seedNo" in card.data else 0)


def validate_cards(cards: list[CaseCard], docs: list[Document]) -> list[str]:
    errors: list[str] = []
    doc_keys = {doc.key for doc in docs}
    relations = _relation_index(docs)
    seen_keys: set[str] = set()
    seen_numbers: set[int] = set()

    for card in cards:
        errors += _validate_card(card, doc_keys, relations)
        if card.key in seen_keys:
            errors.append(f"{card.key}: caseKey가 중복됩니다.")
        seen_keys.add(card.key)
        number = card.data.get("seedNo")
        if number in seen_numbers:
            errors.append(f"{card.key}: seedNo {number}이 중복됩니다.")
        seen_numbers.add(number)
    return errors


def _relation_index(docs: list[Document]) -> dict:
    """{symptom: {cause: {checks}}}. 코퍼스 front matter의 선언에서 만든다."""
    index: dict[str, dict[str, set[str]]] = {}
    for doc in docs:
        for symptom in doc.meta.get("symptoms") or []:
            causes = index.setdefault(symptom["id"], {})
            for cause in symptom["causes"]:
                causes[cause["id"]] = set(cause["checks"])
    return index


def _validate_card(card: CaseCard, doc_keys: set[str], relations: dict) -> list[str]:
    data, key = card.data, card.key
    errors: list[str] = []

    def bad(message: str) -> None:
        errors.append(f"{key}: {message}")

    for field in REQUIRED_FIELDS:
        if field not in data:
            bad(f"필수 필드 {field}가 없습니다.")
    if errors:
        return errors

    if card.path.stem != key:
        bad(f"파일명({card.path.stem})이 caseKey와 같아야 합니다.")
    if not key.startswith("case-"):
        bad("caseKey는 case- 로 시작해야 합니다.")
    if data["caseVersion"] != 1:
        bad("시드 사례의 caseVersion은 1입니다.")
    if not isinstance(data["seedNo"], int) or not 1 <= data["seedNo"] <= 99:
        bad("seedNo는 1~99의 정수여야 합니다.")
    system = data["system"]
    if system not in SYSTEM_FEATURES:
        bad(f"system {system!r}를 알 수 없습니다.")
        return errors
    if data["feature"] not in SYSTEM_FEATURES[system]:
        bad(f"기능 {data['feature']}이 {system}의 기능이 아닙니다.")
    if data["requester"] not in REQUESTERS:
        bad(f"requester {data['requester']!r}는 문의자 계정이 아닙니다.")
    if data["assignee"] != PRIMARY_OWNERS[system]:
        bad(f"assignee는 {system}의 주담당자 {PRIMARY_OWNERS[system]}이어야 합니다.")
    try:
        occurred = datetime.fromisoformat(str(data["occurredAt"]))
        if occurred.tzinfo is None:
            bad("occurredAt에 시간대(+09:00 등)가 없습니다.")
    except ValueError:
        bad("occurredAt이 ISO 시각이 아닙니다.")

    errors += _validate_dialogue(card)
    errors += _validate_request_and_logs(card)
    errors += _validate_relation(card, doc_keys, relations)
    return errors


def _validate_dialogue(card: CaseCard) -> list[str]:
    key, dialogue = card.key, card.data["dialogue"]
    errors: list[str] = []
    senders = [turn.get("sender") for turn in dialogue]
    if senders != DIALOGUE_SENDERS:
        errors.append(f"{key}: dialogue는 {DIALOGUE_SENDERS} 순서여야 합니다(현재 {senders}).")
        return errors
    for number, turn in enumerate(dialogue, start=1):
        if not str(turn.get("text", "")).strip():
            errors.append(f"{key}: dialogue {number}번 text가 비었습니다.")
        elif turn["sender"] == "AI":
            for term in AI_FORBIDDEN_TERMS:
                if term in turn["text"]:
                    errors.append(f"{key}: 문의자에게 나가는 AI 메시지({number}번)에 {term!r}가 있습니다.")
    return errors


def _validate_request_and_logs(card: CaseCard) -> list[str]:
    data, key = card.data, card.key
    errors: list[str] = []
    request, ticket, case = data["request"], data["ticket"], data["case"]

    if request.get("intent") not in INTENTS:
        errors.append(f"{key}: request.intent {request.get('intent')!r}가 올바르지 않습니다.")
    if request.get("workType") not in WORK_TYPES:
        errors.append(f"{key}: request.workType {request.get('workType')!r}가 올바르지 않습니다.")
    for fact in request.get("facts") or []:
        if not str(fact.get("text", "")).strip() or not set(fact.get("messages") or []) <= {1, 3} or not fact.get("messages"):
            errors.append(f"{key}: request.facts는 text와 USER 메시지 번호(1 또는 3)가 필요합니다.")
    for field in ("summary",):
        if not str(request.get(field, "")).strip():
            errors.append(f"{key}: request.{field}가 비었습니다.")
    for field in ("title", "detail"):
        if not str(ticket.get(field, "")).strip():
            errors.append(f"{key}: ticket.{field}가 비었습니다.")
    if len(str(ticket.get("title", ""))) > 200 or len(str(data["inquiryTitle"])) > 200:
        errors.append(f"{key}: 제목은 200자 이하여야 합니다.")

    logs = data["workLogs"]
    if [log.get("type") for log in logs] != WORK_LOG_TYPES:
        errors.append(f"{key}: workLogs는 {WORK_LOG_TYPES} 순서여야 합니다.")
    else:
        resolved = logs[1]
        for field in ("action", "confirmedCause", "verification"):
            if not str(resolved.get(field) or "").strip():
                errors.append(f"{key}: 해결 기록의 {field}가 비었습니다(검증하지 않은 조치는 해결로 남기지 않는다).")
        progress = logs[0]
        if not str(progress.get("action") or "").strip():
            errors.append(f"{key}: 진행 기록의 action이 비었습니다.")
        if progress.get("confirmedCause") or progress.get("verification"):
            errors.append(f"{key}: 진행 기록에는 확인된 원인과 검증 결과를 두지 않습니다.")

    for field in ("title", "problemStatement", "applicability"):
        if not str(case.get(field, "")).strip():
            errors.append(f"{key}: case.{field}가 비었습니다.")
    if len(str(case.get("title", ""))) > 200:
        errors.append(f"{key}: case.title은 200자 이하여야 합니다.")
    if not isinstance(case.get("limitations"), list) or not all(isinstance(x, str) and x.strip() for x in case["limitations"]):
        errors.append(f"{key}: case.limitations는 비어 있지 않은 문자열 목록이어야 합니다.")
    if not case.get("sourceMessages") or not set(case["sourceMessages"]) <= {1, 3}:
        errors.append(f"{key}: case.sourceMessages는 USER 메시지 번호(1, 3) 목록이어야 합니다.")
    return errors


def _validate_relation(card: CaseCard, doc_keys: set[str], relations: dict) -> list[str]:
    key, relation, basis = card.key, card.data["relation"], card.data["basis"]
    errors: list[str] = []
    symptom, cause, checks = relation.get("symptom"), relation.get("cause"), relation.get("checks") or []
    if symptom not in relations:
        errors.append(f"{key}: 증상 {symptom}이 코퍼스에 선언되어 있지 않습니다.")
    elif cause not in relations[symptom]:
        errors.append(f"{key}: 원인 {cause}이 증상 {symptom} 아래에 선언되어 있지 않습니다.")
    else:
        extra = set(checks) - relations[symptom][cause]
        if extra or not checks:
            errors.append(f"{key}: 확인 항목 {sorted(extra) or '없음'}이 원인 {cause}의 확인 항목이 아닙니다.")
    if not basis:
        errors.append(f"{key}: basis(근거 문서)가 비었습니다.")
    for doc_key in basis or []:
        if doc_key not in doc_keys:
            errors.append(f"{key}: 근거 문서 {doc_key}이 코퍼스에 없습니다.")
    return errors


def check_cards_against_canon(cards: list[CaseCard], canon_path: Path = CANON_PATH) -> list[str]:
    """카드가 정본 7.2절(사례 6건, 근거 위치)과 6.1절(사례 연결)과 같은지 검사한다."""
    text = canon_path.read_text(encoding="utf-8")
    section72 = text.split("### 7.2 사례 6건")[1].split("### 7.3")[0]
    section61 = text.split("### 6.1")[1].split("\n## 7.")[0]

    catalog: dict[str, set[str]] = {}
    for line in section72.splitlines():
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) >= 6 and re.fullmatch(r"`case-[a-z-]+`", cells[0]):
            catalog[cells[0].strip("`")] = set(re.findall(r"`([a-z-]+)`", cells[5]))

    links: dict[str, tuple[str, str, set[str]]] = {}
    for line in section61.splitlines():
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) >= 4 and re.fullmatch(r"`case-[a-z-]+`", cells[0]):
            links[cells[0].strip("`")] = (
                cells[1].strip("`"), cells[2].strip("`"), set(re.findall(r"`(check-[a-z0-9-]+)`", cells[3])),
            )

    errors: list[str] = []
    by_key = {card.key: card for card in cards}
    for key in sorted(set(catalog) - set(by_key)):
        errors.append(f"정본 7.2절에는 있지만 카드가 없습니다: {key}")
    for key in sorted(set(by_key) - set(catalog)):
        errors.append(f"카드는 있지만 정본 7.2절에 없습니다: {key}")
    for key, card in by_key.items():
        if key in catalog and set(card.data["basis"]) != catalog[key]:
            errors.append(f"{key}: basis가 정본 7.2절의 근거 위치와 다릅니다({sorted(card.data['basis'])} != {sorted(catalog[key])}).")
        if key in links:
            relation = card.data["relation"]
            expected = links[key]
            actual = (relation["symptom"], relation["cause"], set(relation["checks"]))
            if actual != expected:
                errors.append(f"{key}: 증상·원인·확인 항목이 정본 6.1절의 사례 연결과 다릅니다({actual} != {expected}).")
        else:
            errors.append(f"{key}: 정본 6.1절의 사례 연결에 없습니다.")
    return errors
