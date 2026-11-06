"""지식 코퍼스(docs/knowledge/corpus)를 읽고 검증한다.

DB 적재용 SQL 생성(build_knowledge_seed.py)과, 이후 관계 데이터 생성이 같은 읽기·검증을 쓴다.
검증 규칙의 출처는 docs/knowledge/DATA_FORMAT.md와 docs/knowledge/VIRTUAL_COMPANY.md(6, 6.1절)다.
"""

from __future__ import annotations

import hashlib
import re
import uuid
from dataclasses import dataclass
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
CORPUS_DIR = REPO_ROOT / "docs" / "knowledge" / "corpus"
CANON_PATH = REPO_ROOT / "docs" / "knowledge" / "VIRTUAL_COMPANY.md"

# 문서·버전 ID: uuid5(NAMESPACE_URL, 접두사 + documentKey). 시스템 ID(hops-system-1.example/...)와 같은 방식이다.
ID_NAMESPACE_PREFIX = "hops-knowledge.example/"

# 작성 단계 값 → Spring 저장 값 (docs/knowledge/DATA_FORMAT.md "값 대응")
KIND_TO_DOCUMENT_TYPE = {"GUIDE": "EMPLOYEE_GUIDE", "POLICY": "POLICY", "RUNBOOK": "STAFF_RUNBOOK"}
SCOPE_TO_ACCESS_LEVEL = {"EMPLOYEE": "EMPLOYEE", "SYSTEM_STAFF": "STAFF"}

SYSTEM_FEATURES = {
    "ELECTRONIC_APPROVAL": {"DOCUMENT_CREATE", "DOCUMENT_VIEW", "APPROVAL_STATUS", "ATTACHMENT_VIEW"},
    "COMMON_LOGIN": {"LOGIN_SSO", "ACCOUNT_ACCESS", "PERMISSION_REQUEST"},
    "FILE_SHARING": {"FILE_VIEW", "FILE_SHARE", "ACCESS_PERMISSION"},
}

REQUIRED_FIELDS = (
    "documentKey", "kind", "title", "accessScope", "version", "appliesToSystemVersion",
    "applicability", "dataSplit", "origin", "reviewStatus", "source",
)
DATA_SPLITS = {"RAG", "DEV", "EVAL"}
REVIEW_STATUSES = {"PENDING", "APPROVED"}
ORIGINS = {"SYNTHETIC", "DEMO_VERIFIED", "OPERATIONAL"}

# 종류별 본문 제목과 순서 (DATA_FORMAT.md "종류별 본문 요구")
BODY_HEADINGS = {
    "GUIDE": ["목적", "전제", "절차", "확인할 수 있는 결과", "적용 조건과 한계"],
    "POLICY": ["규칙", "적용 대상", "예외", "신청 절차", "근거"],
    "RUNBOOK": ["증상", "확인 항목", "가능 원인 후보", "결과 해석", "적용 조건과 한계"],
}

# 직원 문서에 있으면 안 되는 담당자 전용 문구(DATA_FORMAT.md "검수 항목"의 금지어 목록).
# 자동 검사는 이 단어가 있는지만 본다. 통과가 내용 검수를 대신하지 않는다.
EMPLOYEE_FORBIDDEN_TERMS = (
    "런북", "SYSTEM_STAFF", "sym-", "cause-", "check-",
    "원인", "쿠키", "보안 검사", "시각이 서버",
)

_FRONT_MATTER = re.compile(r"\A---\n(.*?)\n---\n(.*)\Z", re.DOTALL)


@dataclass(frozen=True)
class Document:
    key: str
    path: Path
    meta: dict
    content: str  # front matter를 뺀 본문. 줄바꿈 LF, 끝은 개행 한 개로 정규화한다.

    @property
    def digest(self) -> str:
        """승인 원문의 UTF-8 바이트 SHA-256(16진수 64자). knowledge_document_version.content_digest와 같다."""
        return hashlib.sha256(self.content.encode("utf-8")).hexdigest()

    @property
    def document_id(self) -> str:
        return str(uuid.uuid5(uuid.NAMESPACE_URL, ID_NAMESPACE_PREFIX + self.key))

    @property
    def version_id(self) -> str:
        return str(uuid.uuid5(uuid.NAMESPACE_URL, f"{ID_NAMESPACE_PREFIX}{self.key}/v{self.meta['version']}"))

    @property
    def relative_path(self) -> str:
        try:
            return self.path.relative_to(REPO_ROOT).as_posix()
        except ValueError:
            return self.path.as_posix()


def canonical_content(body: str) -> str:
    """본문을 줄바꿈 LF, 앞뒤 빈 줄 제거, 끝 개행 한 개로 맞춘다. digest는 이 문자열로 계산한다."""
    return body.replace("\r\n", "\n").replace("\r", "\n").strip("\n") + "\n"


def parse_document(path: Path) -> Document:
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    match = _FRONT_MATTER.match(text)
    if not match:
        raise ValueError(f"{path}: front matter(--- ... ---)가 없습니다.")
    meta = yaml.safe_load(match.group(1))
    if not isinstance(meta, dict) or "documentKey" not in meta:
        raise ValueError(f"{path}: front matter에 documentKey가 없습니다.")
    return Document(key=str(meta["documentKey"]), path=path, meta=meta, content=canonical_content(match.group(2)))


def load_corpus(corpus_dir: Path = CORPUS_DIR) -> list[Document]:
    """코퍼스를 documentKey 순으로 읽는다(생성 결과가 항상 같은 순서가 되도록)."""
    docs = [parse_document(path) for path in sorted(corpus_dir.rglob("*.md"))]
    return sorted(docs, key=lambda doc: doc.key)


def validate_corpus(docs: list[Document]) -> list[str]:
    """문서 단위 규칙과 문서 사이 참조 무결성을 검사해 오류 문장 목록을 돌려준다."""
    errors: list[str] = []
    seen_keys: set[str] = set()
    defined_checks: dict[str, str] = {}
    referenced_checks: set[str] = set()

    for doc in docs:
        meta, key = doc.meta, doc.key
        if key in seen_keys:
            errors.append(f"{key}: documentKey가 중복됩니다.")
        seen_keys.add(key)
        errors += _validate_document(doc)

        for check in meta.get("checks") or []:
            if check["id"] in defined_checks:
                errors.append(f"{key}: 확인 항목 {check['id']}가 {defined_checks[check['id']]}에도 정의되어 있습니다.")
            defined_checks[check["id"]] = key
        for symptom in meta.get("symptoms") or []:
            for cause in symptom["causes"]:
                referenced_checks.update(cause["checks"])

    for check_id in sorted(referenced_checks - set(defined_checks)):
        errors.append(f"정의되지 않은 확인 항목을 참조합니다: {check_id}")
    for check_id in sorted(set(defined_checks) - referenced_checks):
        errors.append(f"{defined_checks[check_id]}: 어느 원인도 쓰지 않는 확인 항목입니다: {check_id}")
    return errors


def _validate_document(doc: Document) -> list[str]:
    meta, key = doc.meta, doc.key
    errors: list[str] = []

    def bad(message: str) -> None:
        errors.append(f"{key}: {message}")

    for field in REQUIRED_FIELDS:
        if field not in meta:
            bad(f"필수 필드 {field}가 없습니다.")
    if errors:
        return errors

    kind = meta["kind"]
    if kind not in KIND_TO_DOCUMENT_TYPE:
        bad(f"kind {kind!r}는 {sorted(KIND_TO_DOCUMENT_TYPE)} 중 하나여야 합니다.")
        return errors
    if doc.path.stem != key:
        bad(f"파일명({doc.path.stem})이 documentKey와 같아야 합니다.")
    if not key.startswith(kind.lower() + "-"):
        bad(f"documentKey는 {kind.lower()}- 로 시작해야 합니다.")
    if meta["accessScope"] not in SCOPE_TO_ACCESS_LEVEL:
        bad(f"accessScope {meta['accessScope']!r}가 올바르지 않습니다.")
    if kind == "RUNBOOK" and meta["accessScope"] != "SYSTEM_STAFF":
        bad("런북의 accessScope는 SYSTEM_STAFF만 가능합니다.")
    if not isinstance(meta["version"], int) or meta["version"] < 1:
        bad("version은 1 이상의 정수여야 합니다.")
    if meta["dataSplit"] not in DATA_SPLITS:
        bad(f"dataSplit {meta['dataSplit']!r}가 올바르지 않습니다.")
    if meta["reviewStatus"] not in REVIEW_STATUSES:
        bad(f"reviewStatus {meta['reviewStatus']!r}가 올바르지 않습니다.")
    if meta["origin"] not in ORIGINS:
        bad(f"origin {meta['origin']!r}가 올바르지 않습니다.")
    system = meta.get("systemCode")
    if system is not None and system not in SYSTEM_FEATURES:
        bad(f"systemCode {system!r}를 알 수 없습니다.")
    if not str(meta["title"]).strip() or len(str(meta["title"])) > 200:
        bad("title은 비어 있지 않고 200자 이하여야 합니다.")
    applicability = meta["applicability"]
    if not isinstance(applicability, str) or not applicability.strip() or len(applicability) > 500:
        bad("applicability는 비어 있지 않은 500자 이하 문자열이어야 합니다(이 문서를 어디에 쓰고 어디에 쓰지 않는지).")
    elif meta["accessScope"] == "EMPLOYEE":
        # 적용 조건도 AI의 근거로 직원 응답까지 닿을 수 있으므로 본문과 같은 금지어 검사를 한다.
        for term in EMPLOYEE_FORBIDDEN_TERMS:
            if term in applicability:
                bad(f"직원 문서의 applicability에 담당자 전용 문구({term!r})가 있습니다.")

    headings = re.findall(r"^## (.+)$", doc.content, re.MULTILINE)
    if headings != BODY_HEADINGS[kind]:
        bad(f"본문 제목이 {BODY_HEADINGS[kind]} 순서여야 합니다(현재 {headings}).")
    if "$kb$" in doc.content:
        bad("본문에 SQL 인용 표지($kb$)가 들어 있습니다.")

    if meta["accessScope"] == "EMPLOYEE":
        for term in EMPLOYEE_FORBIDDEN_TERMS:
            if term in doc.content:
                bad(f"직원 문서에 담당자 전용 문구 {term!r}가 있습니다.")

    errors += _validate_relations(doc, system)
    return errors


def _validate_relations(doc: Document, system: str | None) -> list[str]:
    meta, key = doc.meta, doc.key
    errors: list[str] = []
    symptoms = meta.get("symptoms") or []
    checks = meta.get("checks") or []
    if (symptoms or checks) and meta["kind"] == "GUIDE":
        errors.append(f"{key}: 가이드는 증상·확인 항목을 선언하지 않습니다.")

    for check in checks:
        if not isinstance(check.get("employeeDisclosable"), bool):
            errors.append(f"{key}: {check.get('id')}의 employeeDisclosable은 true/false여야 합니다.")
    for symptom in symptoms:
        if system is None or symptom.get("featureCode") not in SYSTEM_FEATURES.get(system, set()):
            errors.append(f"{key}: {symptom['id']}의 기능 {symptom.get('featureCode')}이 systemCode의 기능이 아닙니다.")
        if not symptom["id"].startswith("sym-"):
            errors.append(f"{key}: 증상 ID {symptom['id']}는 sym- 로 시작해야 합니다.")
        for cause in symptom["causes"]:
            if not cause["id"].startswith("cause-"):
                errors.append(f"{key}: 원인 ID {cause['id']}는 cause- 로 시작해야 합니다.")
    for check in checks:
        if not check["id"].startswith("check-"):
            errors.append(f"{key}: 확인 항목 ID {check['id']}는 check- 로 시작해야 합니다.")
    if meta["kind"] == "RUNBOOK":
        ids = [s["id"] for s in symptoms] + [c["id"] for s in symptoms for c in s["causes"]] + [c["id"] for c in checks]
        for declared in ids:
            if f"`{declared}`" not in doc.content:
                errors.append(f"{key}: 선언한 {declared}가 본문에 적혀 있지 않습니다.")
    return errors


def check_against_canon(docs: list[Document], canon_path: Path = CANON_PATH) -> list[str]:
    """코퍼스가 정본(VIRTUAL_COMPANY.md 6절 카탈로그, 6.1절 관계 카탈로그)과 같은지 검사한다."""
    text = canon_path.read_text(encoding="utf-8")
    section6 = text.split("## 6. 지식 문서 카탈로그")[1].split("### 6.1")[0]
    section61 = text.split("### 6.1")[1].split("\n## 7.")[0]

    kind_names = {"가이드": "GUIDE", "정책": "POLICY", "런북": "RUNBOOK"}
    system_names = {"전자결재": "ELECTRONIC_APPROVAL", "공통 로그인": "COMMON_LOGIN", "파일 공유": "FILE_SHARING"}
    catalog: dict[str, tuple[str, str, str]] = {}
    for line in section6.splitlines():
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) >= 5 and re.fullmatch(r"`(?:guide|policy|runbook)-[a-z-]+`", cells[0]):
            catalog[cells[0].strip("`")] = (kind_names[cells[1]], cells[2], system_names[cells[3]])

    errors: list[str] = []
    by_key = {doc.key: doc for doc in docs}
    for key in sorted(set(catalog) - set(by_key)):
        errors.append(f"정본 카탈로그에는 있지만 코퍼스에 없습니다: {key}")
    for key in sorted(set(by_key) - set(catalog)):
        errors.append(f"코퍼스에는 있지만 정본 카탈로그에 없습니다: {key}")
    for key, (kind, scope, system) in catalog.items():
        doc = by_key.get(key)
        if doc is None:
            continue
        for field, expected in (("kind", kind), ("accessScope", scope), ("systemCode", system)):
            if doc.meta.get(field) != expected:
                errors.append(f"{key}: {field}이 정본({expected})과 다릅니다({doc.meta.get(field)}).")

    declared = {"sym": set(), "cause": set(), "check": set()}
    for doc in docs:
        for symptom in doc.meta.get("symptoms") or []:
            declared["sym"].add(symptom["id"])
            for cause in symptom["causes"]:
                declared["cause"].add(cause["id"])
        for check in doc.meta.get("checks") or []:
            declared["check"].add(check["id"])
    for prefix, ids in declared.items():
        expected = set(re.findall(rf"`({prefix}-[a-z0-9-]+)`", section61))
        for extra in sorted(ids - expected):
            errors.append(f"정본 6.1절에 없는 {prefix} ID가 코퍼스에 있습니다: {extra}")
        for missing in sorted(expected - ids):
            errors.append(f"정본 6.1절에 있는 {prefix} ID가 코퍼스에 없습니다: {missing}")
    return errors
