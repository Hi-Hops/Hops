"""관계 데이터(증상·원인·확인 항목·질문 문구)를 모으고 검증한다.

증상·원인·확인 항목과 그 연결은 코퍼스 런북·정책의 front matter 선언이 단일 원본이고(corpus.py),
직원용 질문 문구는 관계 카드(docs/knowledge/relations/questions.yml)에 있다.
형식은 docs/knowledge/DATA_FORMAT.md "증상·원인·확인 항목 선언"과 "관계 카드", 범위는 VIRTUAL_COMPANY.md 6.1절이다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import yaml
from cases import AI_FORBIDDEN_TERMS, CaseCard
from corpus import REPO_ROOT, REVIEW_STATUSES, Document

QUESTIONS_PATH = REPO_ROOT / "docs" / "knowledge" / "relations" / "questions.yml"
ID_NAMESPACE_PREFIX = "hops-relation.example/"
MAX_QUESTION_LENGTH = 300
QUESTION_KEY = re.compile(r"q-[a-z0-9-]+-\d{2}")


@dataclass(frozen=True)
class Symptom:
    key: str
    label: str
    feature_code: str
    doc: Document


@dataclass(frozen=True)
class Cause:
    key: str
    label: str
    symptom_key: str
    check_keys: tuple[str, ...]
    doc: Document


@dataclass(frozen=True)
class CheckItem:
    key: str
    label: str
    employee_disclosable: bool
    doc: Document


@dataclass(frozen=True)
class Question:
    key: str
    check_key: str
    text: str
    employee_disclosable: bool
    review_status: str


@dataclass(frozen=True)
class Declarations:
    symptoms: list[Symptom]
    causes: list[Cause]
    checks: list[CheckItem]

    def check(self, key: str) -> CheckItem:
        return next(check for check in self.checks if check.key == key)


def collect_declarations(docs: list[Document]) -> Declarations:
    """코퍼스 front matter 선언을 문서 순서대로 모은다."""
    symptoms, causes, checks = [], [], []
    for doc in docs:
        for symptom in doc.meta.get("symptoms") or []:
            symptoms.append(Symptom(symptom["id"], symptom["label"], symptom["featureCode"], doc))
            for cause in symptom["causes"]:
                causes.append(Cause(cause["id"], cause["label"], symptom["id"], tuple(cause["checks"]), doc))
        for check in doc.meta.get("checks") or []:
            checks.append(CheckItem(check["id"], check["label"], bool(check["employeeDisclosable"]), doc))
    return Declarations(symptoms, causes, checks)


def load_questions(path: Path = QUESTIONS_PATH) -> list[Question]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise TypeError(f"{path}: 최상위가 매핑이 아닙니다.")
    if not isinstance(data.get("questions"), list):
        raise TypeError(f"{path}: questions 목록이 없습니다.")
    questions = []
    for group in data["questions"]:
        for template in group.get("templates") or []:
            questions.append(
                Question(
                    key=str(template.get("key")),
                    check_key=str(group.get("checkId")),
                    text=str(template.get("text", "")),
                    employee_disclosable=template.get("employeeDisclosable"),
                    review_status=str(template.get("reviewStatus")),
                )
            )
        if not group.get("templates"):
            questions.append(Question("", str(group.get("checkId")), "", False, ""))  # 검증이 잡도록 빈 값으로 둔다
    return questions


def validate_questions(questions: list[Question], declarations: Declarations) -> list[str]:
    errors: list[str] = []
    checks = {check.key: check for check in declarations.checks}
    seen: set[str] = set()
    per_check: dict[str, int] = {}

    for question in questions:
        if not question.key:
            errors.append(f"{question.check_key}: templates가 비었습니다.")
            continue
        key = question.key
        if key in seen:
            errors.append(f"{key}: 질문 키가 중복됩니다.")
        seen.add(key)
        check = checks.get(question.check_key)
        if check is None:
            errors.append(f"{key}: 확인 항목 {question.check_key}이 코퍼스에 선언되어 있지 않습니다.")
            continue
        per_check[check.key] = per_check.get(check.key, 0) + 1
        expected_prefix = "q-" + check.key.removeprefix("check-") + "-"
        if not QUESTION_KEY.fullmatch(key) or not key.startswith(expected_prefix):
            errors.append(f"{key}: 질문 키는 {expected_prefix}NN 형식이어야 합니다.")
        if not isinstance(question.employee_disclosable, bool):
            errors.append(f"{key}: employeeDisclosable은 true/false여야 합니다.")
        elif question.employee_disclosable and not check.employee_disclosable:
            errors.append(f"{key}: 직원 공개가 아닌 확인 항목 {check.key}에 직원 공개 질문을 둘 수 없습니다.")
        if question.review_status not in REVIEW_STATUSES:
            errors.append(f"{key}: reviewStatus {question.review_status!r}가 올바르지 않습니다.")
        text = question.text.strip()
        if not text or len(text) > MAX_QUESTION_LENGTH:
            errors.append(f"{key}: 질문은 비어 있지 않고 {MAX_QUESTION_LENGTH}자 이하여야 합니다.")
        for term in AI_FORBIDDEN_TERMS:
            if term in text:
                errors.append(f"{key}: 직원 질문에 담당자 전용 문구 {term!r}가 있습니다.")

    for check in declarations.checks:
        count = per_check.get(check.key, 0)
        if check.employee_disclosable and count == 0:
            errors.append(f"{check.key}: 직원 공개 확인 항목인데 질문 문구가 없습니다.")
        if not check.employee_disclosable and count:
            errors.append(f"{check.key}: 직원 공개가 아닌 확인 항목에 질문 문구가 있습니다.")
    return errors


def validate_case_links(cards: list[CaseCard], declarations: Declarations) -> list[str]:
    """사례 카드의 증상·확인 항목이 선언된 것인지 검사한다(연결 테이블의 대상이므로)."""
    errors: list[str] = []
    symptoms = {symptom.key for symptom in declarations.symptoms}
    checks = {check.key for check in declarations.checks}
    for card in cards:
        relation = card.data["relation"]
        if relation["symptom"] not in symptoms:
            errors.append(f"{card.key}: 증상 {relation['symptom']}이 선언되어 있지 않습니다.")
        for check_key in relation["checks"]:
            if check_key not in checks:
                errors.append(f"{card.key}: 확인 항목 {check_key}이 선언되어 있지 않습니다.")
    return errors
