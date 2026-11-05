# /// script
# requires-python = ">=3.13"
# dependencies = ["pyyaml>=6"]
# ///
"""관계 데이터(증상·원인·확인 항목·질문 문구와 그 연결, 승인 사례와의 연결)를 seed SQL로 만든다.

    uv run scripts/knowledge/build_relation_seed.py          # SQL 파일을 다시 만든다
    uv run scripts/knowledge/build_relation_seed.py --check  # 코퍼스·카드·정본과 SQL이 어긋났는지만 검사한다

원본은 코퍼스 런북·정책의 front matter 선언(증상·원인·확인 항목), docs/knowledge/relations/questions.yml(질문 문구),
docs/knowledge/cases/*.yml(사례 연결)이고, 결과는
backend/spring/hops/src/main/resources/db/seed/R__seed_dev_relations.sql 이다. 결과 파일은 직접 고치지 않는다.
"""

from __future__ import annotations

import argparse
import difflib
import sys
import uuid
from pathlib import Path

from build_case_seed import DEFAULT_OUTPUT as CASES_OUTPUT
from cases import check_cards_against_canon, load_cards, validate_cards
from corpus import (
    REPO_ROOT,
    Document,
    check_against_canon,
    load_corpus,
    validate_corpus,
)
from relations import (
    ID_NAMESPACE_PREFIX,
    Declarations,
    Question,
    collect_declarations,
    load_questions,
    validate_case_links,
    validate_questions,
)
from sqlutil import sql_string

DEFAULT_OUTPUT = CASES_OUTPUT.with_name("R__seed_dev_relations.sql")

# 공개 범위: 증상은 직원이 말하는 현상, 원인·확인 항목·그 연결·사례 연결은 담당자 전용(정본 6.1절)
SYMPTOM_SCOPE = "EMPLOYEE"
STAFF_SCOPE = "STAFF"

HEADER = """\
-- ⚠️ 로컬 개발용 가상 데이터 (실제 문의·실제 업무 지식이 아님)
-- ⚠️ 자동 생성 파일이다. 직접 고치지 않는다.
--    원본: 코퍼스 런북·정책의 front matter(증상·원인·확인 항목), docs/knowledge/relations/questions.yml(질문 문구),
--          docs/knowledge/cases/*.yml(사례 연결)
--    다시 만들기: uv run scripts/knowledge/build_relation_seed.py
--    어긋남 검사:  uv run scripts/knowledge/build_relation_seed.py --check
-- 값의 범위와 ID는 docs/knowledge/VIRTUAL_COMPANY.md 6.1절(관계 카탈로그)이 정한다.
-- db 프로필에서만 실행된다. 테스트 DB·운영에는 들어가지 않는다.
--
-- R__ 파일 실행 순서: department_member → it_system → knowledge → knowledge_cases → relations (이름 순).
-- 근거 문서 버전과 사례 문서 버전이 먼저 있어야 하므로 이 파일이 마지막이다.
--
-- ID는 카탈로그 ID에서 정해지는 고정값이다: uuid5(NAMESPACE_URL, "hops-relation.example/" + 종류 + "/" + 키).
-- 모든 행은 승인(APPROVED) 상태이며 근거 문서 버전을 가진다. 연결은 후보이며 원인을 확정하지 않는다.
--
-- 모든 INSERT는 ON CONFLICT DO NOTHING이라 이미 있는 행은 바꾸지 않는다. 원본을 고치면 팀원은 DB를 새로 만들어 적재한다
-- (backend/spring/hops/README.md "처음 상태로 되돌리기").
"""


def rel_id(kind: str, key: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"{ID_NAMESPACE_PREFIX}{kind}/{key}"))


def system_ref(code: str) -> str:
    return f"(SELECT id FROM it_system WHERE code = {sql_string(code)})"


def feature_ref(system_code: str, feature_code: str) -> str:
    return (
        "(SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id "
        f"WHERE s.code = {sql_string(system_code)} AND f.code = {sql_string(feature_code)})"
    )


def review(doc: Document) -> str:
    return doc.meta["reviewStatus"]


def insert(table: str, columns: list[str], rows: list[list[str]]) -> str:
    lines = ",\n".join("    (" + ", ".join(row) + ")" for row in rows)
    return f"INSERT INTO {table} ({', '.join(columns)})\nVALUES\n{lines}\nON CONFLICT DO NOTHING;\n"


def render_sql(docs: list[Document], declarations: Declarations, questions: list[Question], cards) -> str:
    symptoms, causes, checks = declarations.symptoms, declarations.causes, declarations.checks
    symptom_doc = {symptom.key: symptom.doc for symptom in symptoms}
    check_doc = {check.key: check.doc for check in checks}
    node_columns = ["id", "catalog_key", "label", "it_system_id", "access_level", "review_status", "source_document_version_id"]
    edge_tail = ["access_level", "review_status", "source_document_version_id"]
    parts: list[str] = [HEADER]

    def system_of(doc: Document) -> str:
        return system_ref(doc.meta["systemCode"])

    parts.append("-- 증상 (직원이 말하는 현상)\n" + insert(
        "relation_symptom", ["id", "catalog_key", "label", "it_system_id", "system_feature_id", "access_level", "review_status", "source_document_version_id"],
        [[sql_string(rel_id("symptom", s.key)), sql_string(s.key), sql_string(s.label), system_of(s.doc),
          feature_ref(s.doc.meta["systemCode"], s.feature_code), sql_string(SYMPTOM_SCOPE), sql_string(review(s.doc)),
          sql_string(s.doc.version_id)] for s in symptoms]))
    parts.append("-- 가능 원인 (담당자 전용)\n" + insert(
        "relation_cause", node_columns,
        [[sql_string(rel_id("cause", c.key)), sql_string(c.key), sql_string(c.label), system_of(c.doc),
          sql_string(STAFF_SCOPE), sql_string(review(c.doc)), sql_string(c.doc.version_id)] for c in causes]))
    parts.append("-- 확인 항목 (직원이 스스로 답할 수 있는 항목만 employee_disclosable = true)\n" + insert(
        "relation_check_item", ["id", "catalog_key", "label", "it_system_id", "employee_disclosable", "access_level", "review_status", "source_document_version_id"],
        [[sql_string(rel_id("check", c.key)), sql_string(c.key), sql_string(c.label), system_of(c.doc),
          str(c.employee_disclosable).lower(), sql_string(STAFF_SCOPE), sql_string(review(c.doc)), sql_string(c.doc.version_id)]
         for c in checks]))
    parts.append("-- 직원용 질문 문구 (근거는 그 확인 항목을 선언한 문서 버전)\n" + insert(
        "relation_question_template",
        ["id", "catalog_key", "check_item_id", "question_text", "employee_disclosable", "access_level", "review_status", "source_document_version_id"],
        [[sql_string(rel_id("question", q.key)), sql_string(q.key), sql_string(rel_id("check", q.check_key)), sql_string(q.text.strip()),
          str(q.employee_disclosable).lower(), sql_string(SYMPTOM_SCOPE if q.employee_disclosable else STAFF_SCOPE),
          sql_string(q.review_status), sql_string(check_doc[q.check_key].version_id)] for q in questions]))
    parts.append("-- 증상 → 가능 원인 (MAY_HAVE_CAUSE)\n" + insert(
        "relation_symptom_cause", ["id", "symptom_id", "cause_id", *edge_tail],
        [[sql_string(rel_id("symptom-cause", f"{c.symptom_key}/{c.key}")), sql_string(rel_id("symptom", c.symptom_key)),
          sql_string(rel_id("cause", c.key)), sql_string(STAFF_SCOPE), sql_string(review(c.doc)),
          sql_string(symptom_doc[c.symptom_key].version_id)] for c in causes]))
    parts.append("-- 원인 → 원인을 가르는 확인 항목 (CHECKED_BY)\n" + insert(
        "relation_cause_check", ["id", "cause_id", "check_item_id", *edge_tail],
        [[sql_string(rel_id("cause-check", f"{c.key}/{check_key}")), sql_string(rel_id("cause", c.key)),
          sql_string(rel_id("check", check_key)), sql_string(STAFF_SCOPE), sql_string(review(c.doc)),
          sql_string(c.doc.version_id)] for c in causes for check_key in c.check_keys]))

    case_symptoms, case_checks = [], []
    for card in cards:
        relation = card.data["relation"]
        case_id, version_id = card.id_for("case"), card.id_for("doc-version")
        case_symptoms.append([
            sql_string(rel_id("case-symptom", f"{card.key}/{relation['symptom']}")), sql_string(case_id),
            sql_string(rel_id("symptom", relation["symptom"])), sql_string(STAFF_SCOPE), sql_string("APPROVED"), sql_string(version_id)])
        for check_key in relation["checks"]:
            case_checks.append([
                sql_string(rel_id("case-check", f"{card.key}/{check_key}")), sql_string(case_id),
                sql_string(rel_id("check", check_key)), sql_string(STAFF_SCOPE), sql_string("APPROVED"), sql_string(version_id)])
    parts.append("-- 승인 사례 → 사례가 다룬 증상 (ADDRESSES). 근거는 그 사례의 CASE 문서 버전\n" + insert(
        "relation_case_symptom", ["id", "resolved_case_id", "symptom_id", *edge_tail], case_symptoms))
    parts.append("-- 승인 사례 → 사례가 쓴 확인 항목 (USES_CHECK)\n" + insert(
        "relation_case_check", ["id", "resolved_case_id", "check_item_id", *edge_tail], case_checks))
    return "\n".join(parts)


def build() -> tuple[str | None, list[str]]:
    """검증을 통과하면 (SQL, [])를, 아니면 (None, 오류 목록)을 돌려준다."""
    docs = load_corpus()
    cards = load_cards()
    declarations = collect_declarations(docs)
    questions = load_questions()
    errors = (
        validate_corpus(docs) + check_against_canon(docs) + validate_cards(cards, docs) + check_cards_against_canon(cards)
        + validate_questions(questions, declarations) + validate_case_links(cards, declarations)
    )
    return (None if errors else render_sql(docs, declarations, questions, cards)), errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="파일을 쓰지 않고 코퍼스·카드·정본·SQL이 같은지 검사한다.")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="결과 SQL 경로")
    args = parser.parse_args(argv)

    sql, errors = build()
    if sql is None:
        print(f"관계 데이터 검증 오류 {len(errors)}건:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1

    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if current != sql:
            print("R__seed_dev_relations.sql이 원본과 다릅니다. 아래 명령으로 다시 만들어 함께 커밋하세요.", file=sys.stderr)
            print("  uv run scripts/knowledge/build_relation_seed.py", file=sys.stderr)
            diff = difflib.unified_diff(current.splitlines(), sql.splitlines(), "현재 파일", "원본에서 만든 결과", lineterm="", n=0)
            for line in list(diff)[:40]:
                print(line, file=sys.stderr)
            return 1
        print("코퍼스, 질문 카드, 사례 카드, 정본, seed SQL이 일치합니다.")
        return 0

    args.output.write_text(sql, encoding="utf-8")
    print(f"{args.output.relative_to(REPO_ROOT)} 을(를) 만들었습니다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
