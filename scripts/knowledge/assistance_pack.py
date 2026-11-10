"""전자결재 합성 사례팩. 기본 Flyway 시드와 분리하며 공유 DB의 기존 행을 초기화하지 않는다."""

import argparse
import json
import os
from dataclasses import replace
from urllib.parse import urlsplit
from uuid import UUID

import httpx
import psycopg
from build_case_seed import (
    Chain,
    case_document_content,
    case_version_digest,
    render_case,
)
from build_knowledge_seed import render_document
from cases import CaseCard, _validate_dialogue, _validate_request_and_logs, load_cards
from corpus import REPO_ROOT, SYSTEM_FEATURES, Document, load_corpus, validate_corpus
from load_search_demo import load_vectors, neo4j
from psycopg.rows import dict_row

PACK = REPO_ROOT / "docs/knowledge/packs/electronic-approval-v1"


def load_pack():
    cards = [
        CaseCard(row["caseKey"], PACK / "cases.json", row)
        for row in json.loads((PACK / "cases.json").read_text())["cases"]
    ]
    docs = [
        Document(row["key"], PACK / "documents.json", row["meta"], row["content"])
        for row in json.loads((PACK / "documents.json").read_text())
    ]
    return cards, docs


def validate(cards, docs):
    # JSON 묶음의 각 문서를 논리 문서명으로 검수한다. 저장되는 출처 경로는 실제 documents.json이다.
    errors = validate_corpus(
        [replace(doc, path=doc.path.with_name(doc.key + ".md")) for doc in docs]
    )
    known = {doc.key for doc in load_corpus()} | {doc.key for doc in docs}
    if len(cards) != 80 or len({c.key for c in cards}) != 80:
        errors.append("고유 사례가 80건이어야 한다")
    signatures = set()
    for card in cards:
        data = card.data
        errors.extend(_validate_dialogue(card) + _validate_request_and_logs(card))
        if data["feature"] not in SYSTEM_FEATURES[data["system"]]:
            errors.append(card.key + ": 시스템·기능 불일치")
        if not set(data["basis"]) <= known:
            errors.append(card.key + ": 근거 문서 없음")
        signature = (
            data["pack"]["family"],
            json.dumps(data["pack"]["conditions"], sort_keys=True),
            data["ticket"]["title"],
        )
        if signature in signatures:
            errors.append(card.key + ": 조건·처리 시나리오 중복")
        signatures.add(signature)
        for key, value in data["pack"]["conditions"].items():
            required = data["pack"].get("requirements", data["pack"]["conditions"])[key]
            if (
                f"{key}: {value}" not in data["dialogue"][2]["text"]
                or f"{key}: {required}" not in data["case"]["applicability"]
            ):
                errors.append(card.key + ": 조건의 원문 근거 누락")
        if data["pack"]["origin"] != "SYNTHETIC":
            errors.append(card.key + ": 합성 출처 누락")
    return errors


def seed_sql(cards, docs):
    return "\n".join(
        [
            *(render_document(doc) for doc in docs),
            *(render_case(card) for card in cards),
        ]
    )


def normalize_ticket_numbers(db, cards):
    """합성 카드의 기존 티켓만 공통 시퀀스로 번호를 부여한다. 재실행은 번호를 보존한다."""
    return db.execute(
        """WITH targets AS MATERIALIZED (
            SELECT t.id, t.ticket_number AS old_number FROM ticket t
            WHERE t.id=ANY(%s) AND t.ticket_number ~ '^(EA80|SEED)-T-[0-9]+$'
            AND EXISTS (
                SELECT 1 FROM ticket_work_log w JOIN resolved_case c ON c.source_work_log_id=w.id
                WHERE w.ticket_id=t.id AND c.origin_type='SYNTHETIC'
            )
            ORDER BY t.created_at,t.id FOR UPDATE OF t
        ), numbered AS MATERIALIZED (
            SELECT *,nextval('ticket_number_seq')::text AS digits FROM targets
        )
        UPDATE ticket t SET ticket_number='T-' || lpad(n.digits,greatest(16,length(n.digits)),'0')
        FROM numbered n WHERE t.id=n.id
        RETURNING t.id,n.old_number,t.ticket_number AS new_number""",
        ([UUID(Chain(card).id("ticket")) for card in cards],),
    ).fetchall()


def normalize_record_numbers(db, cards):
    """기존 서비스의 문의·티켓·사례 번호 규칙을 합성 카드에도 적용한다."""
    tickets = normalize_ticket_numbers(db, cards)
    inquiries = db.execute(
        """WITH targets AS MATERIALIZED (
            SELECT i.id,i.inquiry_number AS old_number FROM inquiry i
            WHERE i.id=ANY(%s) AND i.inquiry_number ~ '^(EA80|SEED)-Q-[0-9]+$'
            AND EXISTS (
                SELECT 1 FROM ticket t JOIN ticket_work_log w ON w.ticket_id=t.id
                JOIN resolved_case c ON c.source_work_log_id=w.id
                WHERE t.inquiry_id=i.id AND c.origin_type='SYNTHETIC'
            )
            ORDER BY i.created_at,i.id FOR UPDATE OF i
        ), numbered AS MATERIALIZED (
            SELECT *,nextval('inquiry_number_seq')::text AS digits FROM targets
        )
        UPDATE inquiry i SET inquiry_number='Q-' || lpad(n.digits,greatest(8,length(n.digits)),'0')
        FROM numbered n WHERE i.id=n.id
        RETURNING i.id,n.old_number,i.inquiry_number AS new_number""",
        ([UUID(Chain(card).id("inquiry")) for card in cards],),
    ).fetchall()
    cases = db.execute(
        """WITH targets AS MATERIALIZED (
            SELECT c.id,c.case_number AS old_number,
                'C-' || right(replace(r.id::text,'-',''),16) AS new_number
            FROM resolved_case c JOIN agent_run r ON r.id=c.curator_run_id
            WHERE c.id=ANY(%s) AND c.origin_type='SYNTHETIC'
                AND c.case_number ~ '^(EA80|SEED)-C-[0-9]+$'
                AND r.task='CURATION'
            FOR UPDATE OF c
        )
        UPDATE resolved_case c SET case_number=n.new_number FROM targets n WHERE c.id=n.id
        RETURNING c.id,n.old_number,c.case_number AS new_number""",
        ([UUID(Chain(card).id("case")) for card in cards],),
    ).fetchall()
    db.execute(
        """UPDATE knowledge_document d
        SET source_data=jsonb_set(d.source_data,'{caseNumber}',to_jsonb(c.case_number))
        FROM resolved_case c WHERE d.id=c.id AND c.id=ANY(%s)
            AND d.document_type='CASE' AND d.is_synthetic AND c.origin_type='SYNTHETIC'
            AND d.source_data->>'caseNumber' IS DISTINCT FROM c.case_number""",
        ([UUID(Chain(card).id("case")) for card in cards],),
    )
    return {"inquiries": inquiries, "tickets": tickets, "cases": cases}


def sync_graph_case_numbers(db, client, cards):
    rows = db.execute(
        """SELECT id::text,case_number FROM resolved_case
        WHERE id=ANY(%s) AND origin_type='SYNTHETIC'""",
        ([UUID(Chain(card).id("case")) for card in cards],),
    ).fetchall()
    [result] = neo4j(
        client,
        [
            {
                "statement": """UNWIND $rows AS row
            MATCH (c:HopsP0:ApprovedCase {id:row.id})
            SET c.case_number=row.case_number RETURN count(c)""",
                "parameters": {"rows": rows},
            }
        ],
    )
    return result["data"][0]["row"][0]


def index_documents(db, cards, docs):
    expected = {doc.version_id: (doc.digest, doc.content) for doc in docs}
    for card in cards:
        chain = Chain(card)
        expected[chain.id("doc-version")] = (
            case_version_digest(chain),
            case_document_content(chain),
        )
    rows = db.execute(
        """SELECT v.id,v.content,v.content_digest,d.title,d.document_type,
        coalesce(v.source_metadata->>'applicability',c.applicability) AS applicability,
        c.id AS case_id,c.case_number,c.it_system_id,c.version_no,c.access_level
        FROM knowledge_document_version v JOIN knowledge_document d ON d.id=v.knowledge_document_id
        LEFT JOIN resolved_case c ON c.id=d.id AND d.document_type='CASE'
        WHERE v.id=ANY(%s) AND d.is_synthetic AND v.review_status='APPROVED'
        AND d.review_status='APPROVED' AND d.deleted_at IS NULL""",
        ([UUID(key) for key in expected],),
    ).fetchall()
    if len(rows) != len(expected) or any(
        expected[str(r["id"])] != (r["content_digest"], r["content"]) for r in rows
    ):
        raise ValueError("기존 버전과 승인 원문이 다르다. 새 데이터팩 버전이 필요하다")
    return rows


def apply(db, client, cards, docs):
    db.execute("SELECT pg_advisory_xact_lock(244, 80)")
    db.execute(seed_sql(cards, docs))
    all_cards = load_cards() + cards
    normalize_record_numbers(db, all_cards)
    for card in cards:
        db.execute(
            """UPDATE knowledge_document SET source_data=source_data || %s::jsonb
            WHERE id=%s AND is_synthetic AND document_type='CASE'""",
            (
                json.dumps(
                    {
                        "caseKey": card.key,
                        "cardPath": card.relative_path,
                        "packId": "electronic-approval-v1",
                    }
                ),
                UUID(Chain(card).id("case")),
            ),
        )
    rows = index_documents(db, cards, docs)
    missing = [
        row
        for row in rows
        if not db.execute(
            """SELECT 1 FROM hops_knowledge.knowledge_chunk
        WHERE knowledge_document_version_id=%s AND content_digest=%s AND embedding_model='text-embedding-3-small'""",
            (row["id"], row["content_digest"]),
        ).fetchone()
    ]
    chunks = load_vectors(db, client, missing) if missing else 0
    cases = [
        {
            "id": str(r["case_id"]),
            "case_number": r["case_number"],
            "it_system_id": str(r["it_system_id"]),
            "version_no": r["version_no"],
            "source_document_version_id": str(r["id"]),
            "access_level": r["access_level"],
            "review_status": "APPROVED",
            "content_digest": r["content_digest"],
            "pack": "electronic-approval-v1",
        }
        for r in rows
        if r["case_id"]
    ]
    [result] = neo4j(
        client,
        [
            {
                "statement": """UNWIND $rows AS row
        MERGE (s:HopsP0:System {id:row.it_system_id})
        MERGE (c:HopsP0:ApprovedCase {id:row.id}) SET c += row
        MERGE (c)-[:APPLIES_TO]->(s) RETURN count(c)""",
                "parameters": {"rows": cases},
            }
        ],
    )
    if result["data"][0]["row"][0] != 80:
        raise ValueError("Neo4j 실제 반영 건수 불일치")
    sync_graph_case_numbers(db, client, all_cards)
    db.execute(
        """UPDATE resolved_case SET vector_status='SUCCEEDED',graph_status='SUCCEEDED'
        WHERE id=ANY(%s) AND origin_type='SYNTHETIC' AND case_status='APPROVED'""",
        ([UUID(row["id"]) for row in cases],),
    )
    return len(rows), chunks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--isolated-demo", action="store_true")
    args = parser.parse_args()
    cards, docs = load_pack()
    errors = validate(cards, docs)
    if errors:
        raise ValueError("\n".join(errors))
    print(f"합성 사례 {len(cards)}건 · 보강 문서 {len(docs)}건 검증")
    if args.apply:
        info = psycopg.conninfo.conninfo_to_dict(os.environ["HOPS_SEARCH_DSN"])
        graph = urlsplit(os.environ["HOPS_SEARCH_NEO4J_URL"])
        if (
            not args.isolated_demo
            or info.get("host") not in {"localhost", "127.0.0.1"}
            or info.get("port") not in {"15444", "25433"}
            or graph.hostname not in {"localhost", "127.0.0.1"}
        ):
            raise ValueError("허용된 별도 로컬 시연·테스트 저장소만 적재할 수 있다")
        with (
            psycopg.connect(os.environ["HOPS_SEARCH_DSN"], row_factory=dict_row) as db,
            httpx.Client(timeout=60) as client,
        ):
            count, chunks = apply(db, client, cards, docs)
            db.commit()
        print(f"문서 {count}건, 신규 임베딩 청크 {chunks}개, 실제 Neo4j 사례 80건 반영")


if __name__ == "__main__":
    main()
