# /// script
# requires-python = ">=3.13"
# dependencies = ["pyyaml>=6"]
# ///
"""지식 코퍼스(Markdown)를 개발용 DB seed SQL로 바꾼다.

    uv run scripts/knowledge/build_knowledge_seed.py          # SQL 파일을 다시 만든다
    uv run scripts/knowledge/build_knowledge_seed.py --check  # 코퍼스·정본과 SQL이 어긋났는지만 검사한다(CI 용)

원본은 docs/knowledge/corpus/ 의 Markdown이고, 결과는
backend/spring/hops/src/main/resources/db/seed/R__seed_dev_knowledge.sql 이다. 결과 파일은 직접 고치지 않는다.
"""

from __future__ import annotations

import argparse
import difflib
import sys
from pathlib import Path

from corpus import (
    KIND_TO_DOCUMENT_TYPE,
    REPO_ROOT,
    SCOPE_TO_ACCESS_LEVEL,
    Document,
    check_against_canon,
    load_corpus,
    validate_corpus,
)
from sqlutil import sql_json, sql_string

DEFAULT_OUTPUT = (
    REPO_ROOT / "backend" / "spring" / "hops" / "src" / "main" / "resources" / "db" / "seed" / "R__seed_dev_knowledge.sql"
)

HEADER = """\
-- ⚠️ 로컬 개발용 가상 데이터 (실제 회사·실제 문서가 아님)
-- ⚠️ 자동 생성 파일이다. 직접 고치지 않는다. 원본: docs/knowledge/corpus/ (Markdown)
--    다시 만들기: uv run scripts/knowledge/build_knowledge_seed.py
--    어긋남 검사:  uv run scripts/knowledge/build_knowledge_seed.py --check
-- 값의 정본은 docs/knowledge/VIRTUAL_COMPANY.md 6절(지식 문서 카탈로그)이다.
-- db 프로필에서만 실행된다 (application-db.properties의 spring.flyway.locations). 테스트 DB·운영에는 들어가지 않는다.
--
-- R__ 파일 실행 순서: department_member → it_system → knowledge (이름 순). 시스템 문서는 it_system 뒤에 들어간다.
--
-- ID는 documentKey에서 정해지는 고정값이다: uuid5(NAMESPACE_URL, "hops-knowledge.example/" + documentKey).
-- 버전 ID는 documentKey 뒤에 "/v{version}"을 붙여 만든다. 팀원 모두의 PC에서 같은 문서 ID가 된다.
--
-- 모든 INSERT는 ON CONFLICT DO NOTHING이라 이미 있는 행은 바꾸지 않는다. 문서 내용을 고치면:
--   1) 원본의 version을 올리고 이 파일을 다시 만든다. 그러면 새 버전 행이 추가된다.
--   2) version을 올리지 않고 고치면 이미 적재된 DB에는 반영되지 않으므로, 팀원은 DB를 새로 만들어 적재한다.
--      (backend/spring/hops/README.md "처음 상태로 되돌리기")
--
-- 문서 본문(content)은 front matter를 뺀 Markdown이고, content_digest는 그 UTF-8 바이트의 SHA-256이다.
"""


def render_document(doc: Document) -> str:
    meta = doc.meta
    system = meta.get("systemCode")
    system_ref = f"(SELECT id FROM it_system WHERE code = {sql_string(system)})" if system else "NULL"
    source_data = {
        "documentKey": doc.key,
        "origin": meta["origin"],
        "corpusPath": doc.relative_path,
    }
    source_metadata = {
        "documentKey": doc.key,
        "origin": meta["origin"],
        "appliesToSystemVersion": meta["appliesToSystemVersion"],
        "applicability": meta["applicability"],
        "source": meta["source"],
    }
    return f"""\
-- {doc.key} (v{meta['version']})
INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic)
VALUES ({sql_string(doc.document_id)}, {sql_string(str(meta['title']))}, {sql_string(KIND_TO_DOCUMENT_TYPE[meta['kind']])},
        {system_ref}, {sql_string(SCOPE_TO_ACCESS_LEVEL[meta['accessScope']])},
        {sql_json(source_data)}, {sql_string(meta['dataSplit'])}, {sql_string(meta['reviewStatus'])}, {str(meta['origin'] != 'OPERATIONAL').lower()})
ON CONFLICT DO NOTHING;
INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status)
VALUES ({sql_string(doc.version_id)}, {sql_string(doc.document_id)}, {meta['version']},
        $kb${doc.content}$kb$,
        {sql_string(doc.digest)},
        {sql_json(source_metadata)}, {sql_string(meta['reviewStatus'])})
ON CONFLICT DO NOTHING;
"""


def render_sql(docs: list[Document]) -> str:
    return HEADER + "\n" + "\n".join(render_document(doc) for doc in docs)


def build() -> tuple[str | None, list[str]]:
    """검증을 통과하면 (SQL, [])를, 아니면 (None, 오류 목록)을 돌려준다."""
    docs = load_corpus()
    errors = validate_corpus(docs) + check_against_canon(docs)
    return (None if errors else render_sql(docs)), errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="파일을 쓰지 않고 코퍼스·정본·SQL이 같은지 검사한다.")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="결과 SQL 경로")
    args = parser.parse_args(argv)

    sql, errors = build()
    if sql is None:
        print(f"코퍼스 검증 오류 {len(errors)}건:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1

    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if current != sql:
            print("R__seed_dev_knowledge.sql이 코퍼스와 다릅니다. 아래 명령으로 다시 만들어 함께 커밋하세요.", file=sys.stderr)
            print("  uv run scripts/knowledge/build_knowledge_seed.py", file=sys.stderr)
            diff = difflib.unified_diff(current.splitlines(), sql.splitlines(), "현재 파일", "코퍼스에서 만든 결과", lineterm="", n=0)
            for line in list(diff)[:40]:
                print(line, file=sys.stderr)
            return 1
        print("코퍼스, 정본, seed SQL이 일치합니다.")
        return 0

    args.output.write_text(sql, encoding="utf-8")
    print(f"{args.output.relative_to(REPO_ROOT)} 을(를) 만들었습니다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
