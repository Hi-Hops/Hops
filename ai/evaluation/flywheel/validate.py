"""선순환 묶음의 참조 무결성·필수 필드·누수 검사. 모델을 부르지 않는다.

검사하는 것: 묶음 파일의 짝과 형식, 문의자·담당자가 정본 직원 표와 맞는지, 사례 A의 증상·원인·
확인 항목이 코퍼스 런북의 선언과 맞는지, A의 원인이 시드 사례에 없는지, B가 A와 같은 원인이고 C가
같은 증상·다른 원인인지, 정답이 어휘 안에 있는지, 사례 A의 구체적 문장이 코퍼스·시드·런타임 코드·
다른 시나리오 입력에 새지 않았는지.
검사하지 않는 것: 문장이 정본의 동작에 맞는지, 정답이 타당한지(사람이 검수한다), 실제 모델·Spring
동작.

    uv run python -m evaluation.flywheel.validate
"""

import re
import sys
from collections.abc import Sequence
from pathlib import Path
from typing import Any

import yaml

from evaluation.curator.candidates import CHECK_ITEM, SYMPTOM, CatalogNode, load_catalog
from evaluation.flywheel.bundle import (
    PROFILES,
    ROLES,
    ROOT,
    Bundle,
    BundleError,
    load_bundle,
)
from evaluation.harness.catalog import SYSTEMS
from evaluation.harness.corpus_source import DEFAULT_CORPUS_DIR

REPO = Path(__file__).resolve().parents[3]
KNOWLEDGE_DOCS = REPO / "docs" / "knowledge"
SEED_CARDS_DIR = KNOWLEDGE_DOCS / "cases"
ROSTER_DOC = KNOWLEDGE_DOCS / "VIRTUAL_COMPANY.md"
# 사례 A의 문장이 새면 안 되는, 런타임이 읽는 위치와 다른 평가 입력들
LEAK_SCAN_ROOTS = (
    DEFAULT_CORPUS_DIR,
    KNOWLEDGE_DOCS / "relations",
    SEED_CARDS_DIR,
    REPO / "backend" / "spring" / "hops" / "src" / "main",
    REPO / "ai" / "src",
    REPO / "ai" / "evaluation" / "scenarios",
    REPO / "ai" / "evaluation" / "golds",
    REPO / "ai" / "evaluation" / "curator_scenarios",
    REPO / "ai" / "evaluation" / "curator_golds",
)
LEAK_SUFFIXES = {".md", ".yml", ".yaml", ".json", ".sql", ".py", ".java", ".properties", ".ts"}
NODE_KEY = re.compile(r"\b(sym|cause|check)-[a-z0-9-]+")
CASE_EVIDENCE = {"ABSENT", "CITED_AS_EVIDENCE", "NOT_CONFIRMED_AS_CAUSE"}
NEIGHBOR = {"ABSENT", "PRESENT", "ANY"}
CARD_KEYS = {
    "caseKey",
    "system",
    "feature",
    "assignee",
    "ticket",
    "workLogs",
    "expectedCase",
    "relation",
    "basis",
}
# B의 첫 문장이 A와 너무 같으면 "표현이 다른 후속 문의"가 아니다.
# 거친 안전장치이며 표현의 질을 판정하지 않는다.
MAX_FIRST_MESSAGE_OVERLAP = 0.6
SPLIT_WORDS = re.compile(r"[^\w]+")


def _front_matter(path: Path) -> dict[str, Any]:
    parts = path.read_text(encoding="utf-8").split("---", 2)
    if len(parts) < 3:
        return {}
    data = yaml.safe_load(parts[1])
    return data if isinstance(data, dict) else {}


def _declared_causes(corpus_dir: Path) -> dict[str, dict[str, Any]]:
    """원인 키 → {symptom, checks, document, system}. 승인된 런북·정책의 선언이 원본이다."""
    causes: dict[str, dict[str, Any]] = {}
    for path in sorted(corpus_dir.glob("*/*.md")):
        meta = _front_matter(path)
        if meta.get("reviewStatus") != "APPROVED":
            continue
        for symptom in meta.get("symptoms") or []:
            for cause in symptom.get("causes") or []:
                causes[cause["id"]] = {
                    "symptom": symptom["id"],
                    "checks": list(cause.get("checks") or []),
                    "document": meta.get("documentKey"),
                    "system": meta.get("systemCode"),
                }
    return causes


def _roster(doc: Path) -> tuple[set[str], dict[str, str]]:
    """정본 4.2절 표에서 (문의자 로그인 ID 집합, 시스템 코드 → 주담당자 로그인 ID)를 읽는다."""
    names = {system["name"]: system["code"] for system in SYSTEMS}
    requesters: set[str] = set()
    owners: dict[str, str] = {}
    for line in doc.read_text(encoding="utf-8").splitlines():
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) < 6 or not cells[2].startswith("`"):
            continue
        role, login, duty = cells[0], cells[2].strip("`"), cells[5]
        if role == "문의자":
            requesters.add(login)
        elif role == "담당자" and duty.endswith(" 주담당"):
            code = names.get(duty.removesuffix(" 주담당"))
            if code:
                owners[code] = login
    return requesters, owners


def _seed_card_causes(cards_dir: Path) -> dict[str, str]:
    """시드 사례 카드의 원인 키 → 카드 키."""
    found: dict[str, str] = {}
    for path in sorted(cards_dir.glob("*.yml")):
        card = yaml.safe_load(path.read_text(encoding="utf-8"))
        found[card["relation"]["cause"]] = card["caseKey"]
    return found


def _words(text: str) -> set[str]:
    return {word for word in SPLIT_WORDS.split(text.lower()) if len(word) >= 2}


def _overlap(first: str, second: str) -> float:
    a, b = _words(first), _words(second)
    return len(a & b) / len(a | b) if a | b else 1.0


def _scenario_texts(entry_raw: dict[str, Any]) -> list[str]:
    texts = [str(entry_raw.get("firstMessage", ""))]
    texts += [str(value) for value in (entry_raw.get("userKnowledge") or {}).values() if value]
    texts += [str(value) for value in (entry_raw.get("checkAnswers") or {}).values()]
    return texts


def leak_phrases(card: dict[str, Any]) -> list[str]:
    """사례 A에서만 나올 수 있는 구체적 문장. 런타임이나 다른 입력에 있으면 누수다."""
    phrases = [str(card["caseKey"]), str(card["expectedCase"]["title"])]
    for log in card["workLogs"]:
        if log["type"] == "RESOLVED":
            phrases += [log["action"], log["confirmedCause"], log["verification"]]
    return [phrase for phrase in phrases if phrase]


def _display(path: Path) -> Path:
    try:
        return path.relative_to(REPO)
    except ValueError:
        return path


def _files(roots: Sequence[Path]) -> list[Path]:
    return [
        path
        for root in roots
        if root.exists()
        for path in sorted(root.rglob("*"))
        if path.is_file() and path.suffix in LEAK_SUFFIXES and "build" not in path.parts
    ]


def _check_card(
    bundle: Bundle,
    catalog: dict[str, CatalogNode],
    causes: dict[str, dict[str, Any]],
    seed_causes: dict[str, str],
    corpus_dir: Path,
    errors: list[str],
) -> bool:
    """카드를 검사한다. 뒤 검사가 카드를 믿고 쓸 수 있으면 True."""
    card = bundle.card
    missing = CARD_KEYS - set(card)
    if missing:
        errors.append(f"카드에 없는 필드: {sorted(missing)}")
        return False
    if card["system"] != bundle.system_code:
        errors.append("카드의 system이 묶음의 systemCode와 다르다")
    if card["assignee"] != bundle.assignee:
        errors.append("카드의 assignee가 묶음의 assignee와 다르다")
    logs = card["workLogs"]
    if [log.get("type") for log in logs] != ["PROGRESS", "RESOLVED"]:
        errors.append("workLogs는 PROGRESS, RESOLVED 순서의 두 건이어야 한다")
    else:
        progress, resolved = logs
        if progress.get("confirmedCause") or progress.get("verification"):
            errors.append("PROGRESS 기록에는 확인된 원인과 검증 결과가 없어야 한다")
        for field in ("action", "confirmedCause", "verification"):
            if not str(resolved.get(field) or "").strip():
                errors.append(f"RESOLVED 기록의 {field}가 비어 있다")
    expected = card["expectedCase"]
    for field in ("title", "problemStatement", "applicability", "limitations"):
        if not expected.get(field):
            errors.append(f"expectedCase.{field}가 비어 있다")
    relation = card["relation"]
    cause = causes.get(relation.get("cause"))
    if cause is None:
        errors.append(f"원인 {relation.get('cause')}를 선언한 런북·정책이 없다")
        return False
    if cause["symptom"] != relation.get("symptom"):
        errors.append("카드의 증상이 원인을 선언한 증상과 다르다")
    if cause["system"] != card["system"]:
        errors.append("원인이 다른 시스템의 것이다")
    checks = list(relation.get("checks") or [])
    if not checks:
        errors.append("relation.checks가 비어 있다")
    for key in checks:
        if key not in cause["checks"]:
            errors.append(f"확인 항목 {key}가 원인 {relation['cause']}의 선언에 없다")
    for key, kind in [(relation.get("symptom"), SYMPTOM), *[(key, CHECK_ITEM) for key in checks]]:
        node = catalog.get(key)
        if node is None or node.kind != kind or node.system_code != card["system"]:
            errors.append(f"카탈로그에 없거나 종류·시스템이 맞지 않는 노드: {key}")
    if cause["document"] not in (card.get("basis") or []):
        errors.append(f"basis에 원인을 선언한 문서 {cause['document']}가 없다")
    for key in card.get("basis") or []:
        if not list(corpus_dir.glob(f"*/{key}.md")):
            errors.append(f"basis 문서를 찾을 수 없다: {key}")
    if relation.get("cause") in seed_causes:
        errors.append(
            f"사례 A의 원인이 시드 사례 {seed_causes[relation['cause']]}에 이미 있다"
            "(시드에 없는 원인이어야 한다)"
        )
    return True


def _check_gold_links(
    name: str,
    links: Any,
    catalog: dict[str, CatalogNode],
    system_code: str,
    errors: list[str],
) -> set[tuple[str, str]]:
    result: set[tuple[str, str]] = set()
    wanted = {"ADDRESSES": SYMPTOM, "USES_CHECK": CHECK_ITEM}
    for link in links or []:
        relation, target = link.get("relation"), link.get("target")
        node = catalog.get(target)
        if relation not in wanted:
            errors.append(f"{name}: 알 수 없는 관계 {relation}")
        elif node is None or node.kind != wanted[relation] or node.system_code != system_code:
            errors.append(f"{name}: 노드가 없거나 종류·시스템이 맞지 않는다: {target}")
        result.add((relation, target))
    return result


def _check_roles(
    bundle: Bundle,
    catalog: dict[str, CatalogNode],
    causes: dict[str, dict[str, Any]],
    seed_causes: dict[str, str],
    errors: list[str],
) -> None:
    card = bundle.card
    relation = card["relation"]
    phrases = leak_phrases(card)
    card_texts = [
        card["expectedCase"]["title"],
        *[
            log[field]
            for log in card["workLogs"]
            for field in ("action", "confirmedCause", "verification")
            if log.get(field)
        ],
    ]
    for role, entry in bundle.roles.items():
        sid = entry.scenario.scenario_id
        gold = entry.gold
        if gold.get("scenarioId") != sid or gold.get("bundleRole") != role:
            errors.append(f"{sid}: 정답의 scenarioId·bundleRole이 묶음과 다르다")
        if entry.scenario.user_knowledge.get("systemCode") != bundle.system_code:
            errors.append(f"{sid}: userKnowledge.systemCode가 묶음의 시스템과 다르다")
        for text in _scenario_texts(entry.scenario_raw):
            if NODE_KEY.search(text):
                errors.append(f"{sid}: 입력 문장에 카탈로그 노드 키가 있다(정답 누출)")
            for phrase in phrases:
                if phrase in text:
                    errors.append(f"{sid}: 입력에 사례 A의 문장이 있다: {phrase[:30]}")
        answers = entry.scenario.check_answers
        for key in relation["checks"]:
            if key not in answers:
                errors.append(f"{sid}: checkAnswers에 사례의 확인 항목 {key}가 없다")
        for key in answers:
            node = catalog.get(key)
            if node is None or node.kind != CHECK_ITEM or node.system_code != bundle.system_code:
                errors.append(f"{sid}: checkAnswers의 키가 확인 항목이 아니다: {key}")
        problem = gold.get("problem") or {}
        if problem.get("reportedSystemCode") != bundle.system_code:
            errors.append(f"{sid}: 정답의 reportedSystemCode가 묶음의 시스템과 다르다")
        if not problem.get("allowedFinalActions") or not problem.get("acceptedIntents"):
            errors.append(f"{sid}: 정답에 허용 종결·의도 집합이 없다")
        cause = (gold.get("truth") or {}).get("cause")
        declared = causes.get(cause)
        if declared is None:
            errors.append(f"{sid}: truth.cause를 선언한 문서가 없다: {cause}")
            continue
        if declared["symptom"] != relation["symptom"]:
            errors.append(f"{sid}: truth.cause가 사례 A와 다른 증상의 원인이다")
        if role in ("A", "B") and cause != relation["cause"]:
            errors.append(f"{sid}: A와 B의 원인은 카드의 원인과 같아야 한다")
        if role == "C":
            if cause == relation["cause"]:
                errors.append(f"{sid}: 방해 문의 C의 원인은 A와 달라야 한다")
            if seed_causes.get(cause) != gold.get("seedCaseMayAppear"):
                errors.append(f"{sid}: seedCaseMayAppear가 C의 원인을 가진 시드 사례와 다르다")
        if role == "A":
            expected = gold.get("expectedCase") or {}
            links = _check_gold_links(
                "expectedLinks", expected.get("expectedLinks"), catalog, bundle.system_code, errors
            )
            forbidden = _check_gold_links(
                "forbiddenLinks",
                expected.get("forbiddenLinks"),
                catalog,
                bundle.system_code,
                errors,
            )
            wanted = {("ADDRESSES", relation["symptom"])} | {
                ("USES_CHECK", key) for key in relation["checks"]
            }
            if links != wanted:
                errors.append(f"{sid}: expectedLinks가 카드의 relation과 다르다")
            if links & forbidden:
                errors.append(f"{sid}: 기대 연결과 금지 연결이 겹친다")
            if not expected.get("acceptedReusable"):
                errors.append(f"{sid}: acceptedReusable이 비어 있다")
            continue
        profiles = gold.get("caseA") or {}
        if set(profiles) != set(bundle.profiles):
            errors.append(f"{sid}: caseA의 프로필이 묶음의 profiles와 다르다")
        for profile in bundle.profiles:
            item = profiles.get(profile) or {}
            if (
                item.get("caseEvidence") not in CASE_EVIDENCE
                or item.get("relationCaseNeighbor") not in NEIGHBOR
            ):
                errors.append(f"{sid}: {profile}의 기대가 어휘 밖이다")
        default = profiles.get("DEFAULT") or {}
        if (
            default.get("caseEvidence") != "ABSENT"
            or default.get("relationCaseNeighbor") != "ABSENT"
        ):
            errors.append(
                f"{sid}: 기본 프로필에서 담당자 전용 사례 A는 직원 상담에 나오지 않아야 한다"
            )
        if (
            role == "B"
            and (profiles.get("INTERNAL_JUDGEMENT") or {}).get("caseEvidence")
            != "CITED_AS_EVIDENCE"
        ):
            errors.append(f"{sid}: B는 내부 판단 프로필에서 사례 A를 근거로 인용해야 한다")
        if (
            role == "C"
            and (profiles.get("INTERNAL_JUDGEMENT") or {}).get("caseEvidence")
            != "NOT_CONFIRMED_AS_CAUSE"
        ):
            errors.append(f"{sid}: C는 사례 A가 원인으로 확정되지 않아야 한다")
        must_not = gold.get("employeeVisibleMustNotContain") or []
        if not must_not:
            errors.append(f"{sid}: employeeVisibleMustNotContain이 비어 있다")
        for phrase in must_not:
            if not any(phrase in text for text in card_texts):
                errors.append(
                    f"{sid}: employeeVisibleMustNotContain 문장이 카드에 없다: {phrase[:30]}"
                )
    b_first = bundle.roles["B"].scenario_raw["firstMessage"]
    a_first = bundle.roles["A"].scenario_raw["firstMessage"]
    if _overlap(a_first, b_first) >= MAX_FIRST_MESSAGE_OVERLAP:
        errors.append("B의 첫 문장이 A와 거의 같다(표현이 달라야 한다)")


def _check_people(
    bundle: Bundle, roster: tuple[set[str], dict[str, str]], errors: list[str]
) -> None:
    requesters, owners = roster
    used = [entry.requester for entry in bundle.roles.values()]
    if len(set(used)) != len(used):
        errors.append("A·B·C의 문의자가 서로 달라야 한다")
    for login in [*used, bundle.observer]:
        if login not in requesters:
            errors.append(f"정본 직원 표의 문의자가 아니다: {login}")
    if bundle.observer in used:
        errors.append(
            "관찰자(남의 문의를 볼 수 없는지 확인하는 계정)는 A·B·C의 문의자와 달라야 한다"
        )
    if owners.get(bundle.system_code) != bundle.assignee:
        errors.append(f"assignee {bundle.assignee}가 {bundle.system_code}의 주담당자가 아니다")


def _check_leaks(bundle: Bundle, roots: Sequence[Path], errors: list[str]) -> None:
    phrases = leak_phrases(bundle.card)
    for path in _files(roots):
        text = path.read_text(encoding="utf-8", errors="ignore")
        for phrase in phrases:
            if phrase in text:
                errors.append(f"사례 A의 문장이 {_display(path)}에 있다: {phrase[:30]}")


def _check_layout(root: Path, bundle: Bundle, errors: list[str]) -> None:
    ids = {entry.scenario.scenario_id for entry in bundle.roles.values()}
    for folder in ("scenarios", "golds"):
        names = {path.stem for path in (root / folder).glob("*.json")}
        if names != ids:
            errors.append(f"{folder}/의 파일이 묶음과 다르다: {sorted(names ^ ids)}")
    if bundle.card["caseKey"] != (root / "cards" / f"{bundle.card['caseKey']}.yml").stem:
        errors.append("카드 파일 이름이 caseKey와 다르다")


def validate(
    root: Path = ROOT,
    *,
    corpus_dir: Path = DEFAULT_CORPUS_DIR,
    seed_cards_dir: Path = SEED_CARDS_DIR,
    roster_doc: Path = ROSTER_DOC,
    leak_roots: Sequence[Path] = LEAK_SCAN_ROOTS,
) -> list[str]:
    try:
        bundle = load_bundle(root)
    except (BundleError, KeyError, OSError) as error:
        return [f"묶음을 읽을 수 없다: {error}"]
    errors: list[str] = []
    catalog = load_catalog(corpus_dir)
    causes = _declared_causes(corpus_dir)
    seed_causes = _seed_card_causes(seed_cards_dir)
    _check_layout(root, bundle, errors)
    card_usable = _check_card(bundle, catalog, causes, seed_causes, corpus_dir, errors)
    if card_usable:
        _check_roles(bundle, catalog, causes, seed_causes, errors)
        _check_leaks(bundle, leak_roots, errors)
    _check_people(bundle, _roster(roster_doc), errors)
    if set(bundle.profiles) - set(PROFILES) or not bundle.profiles:
        errors.append(f"profiles는 {PROFILES} 안이어야 한다")
    return errors


def main(argv: Sequence[str] | None = None) -> int:
    errors = validate()
    for error in errors:
        print(f"- {error}")
    print(f"역할 {len(ROLES)}개, 오류 {len(errors)}건")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
