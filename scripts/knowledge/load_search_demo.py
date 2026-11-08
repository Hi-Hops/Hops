"""승인된 가상 지식 시드를 pgvector와 Neo4j에 수동 적재한다. 격리된 시연 DB 전용."""

import argparse
import hashlib
import math
import os
import re
import sys
from uuid import UUID

import httpx
import psycopg
from build_case_seed import Chain, case_document_content, case_version_digest
from build_relation_seed import build as validate_seed
from cases import load_cards
from corpus import load_corpus
from psycopg.rows import dict_row

MODEL = "text-embedding-3-small"
DIMENSIONS = 1536
NODES = {
    "Symptom": (
        "relation_symptom",
        "catalog_key, label, it_system_id, system_feature_id",
    ),
    "Cause": ("relation_cause", "catalog_key, label, it_system_id"),
    "CheckItem": (
        "relation_check_item",
        "catalog_key, label, it_system_id, employee_disclosable",
    ),
    "Question": (
        "relation_question_template",
        "catalog_key, check_item_id, question_text, employee_disclosable",
    ),
}
EDGES = {
    "MAY_HAVE_CAUSE": ("relation_symptom_cause", "symptom_id", "cause_id"),
    "CHECKED_BY": ("relation_cause_check", "cause_id", "check_item_id"),
    "ADDRESSES": ("relation_case_symptom", "resolved_case_id", "symptom_id"),
    "USES_CHECK": ("relation_case_check", "resolved_case_id", "check_item_id"),
}


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def passages(kind, body):
    """승인 본문의 연속 구간. 검색용 제목·적용 조건은 임베딩 입력에만 붙인다."""
    if kind == "POLICY":
        # 정책 규칙과 예외는 같은 판단 단위다.
        return [body.strip()]
    start = "절차" if kind == "EMPLOYEE_GUIDE" else "확인 항목"
    if kind in {"EMPLOYEE_GUIDE", "STAFF_RUNBOOK"}:
        match = re.search(rf"(?m)^## {start}$", body)
        if match:
            # 절차/확인 항목과 하단의 결과·적용 한계를 보존하고 앞의 맥락도 별도 검색한다.
            return [body[: match.start()].strip(), body[match.start() :].strip()]
    # 승인 사례는 증상, 조치, 검증, 적용 조건을 통째로 평가한다.
    return [body.strip()]


def load_documents(db):
    docs = {
        d.version_id: (d.digest, d.content)
        for d in load_corpus()
        if d.meta["reviewStatus"] == "APPROVED"
        and d.meta["dataSplit"] == "RAG"
        and d.meta["origin"] == "SYNTHETIC"
    }
    chains = [Chain(card) for card in load_cards()]
    docs.update(
        {
            c.id("doc-version"): (case_version_digest(c), case_document_content(c))
            for c in chains
        }
    )
    rows = db.execute(
        """
        SELECT v.id, v.content, v.content_digest, d.title, d.document_type,
               coalesce(v.source_metadata ->> 'applicability', c.applicability) AS applicability,
               CASE WHEN c.id IS NOT NULL THEN c.id END AS case_id
        FROM knowledge_document_version v JOIN knowledge_document d ON d.id = v.knowledge_document_id
        LEFT JOIN resolved_case c ON c.id = d.id AND d.document_type = 'CASE'
             AND c.version_no = v.version_no AND c.case_status = 'APPROVED' AND c.deleted_at IS NULL
             AND c.origin_type = 'SYNTHETIC' AND c.approved_at IS NOT NULL AND c.approved_by_member_id IS NOT NULL
        WHERE v.id = ANY(%s) AND d.is_synthetic AND d.deleted_at IS NULL
          AND d.review_status = 'APPROVED' AND d.data_split = 'RAG' AND v.review_status = 'APPROVED'
          AND (d.document_type <> 'CASE' OR c.id IS NOT NULL)
          AND v.version_no = (SELECT max(v2.version_no) FROM knowledge_document_version v2
              WHERE v2.knowledge_document_id = d.id AND v2.review_status = 'APPROVED')
        ORDER BY v.id
    """,
        ([UUID(doc) for doc in docs],),
    ).fetchall()
    if len(rows) != len(docs) or any(
        docs[str(row["id"])] != (row["content_digest"], row["content"]) for row in rows
    ):
        raise ValueError(
            "격리 DB의 최신 승인 시드 ID·해시·본문을 카탈로그와 일치시켜 주세요"
        )
    return rows


def embeddings(client, inputs):
    response = client.post(
        "https://api.openai.com/v1/embeddings",
        json={
            "model": MODEL,
            "dimensions": DIMENSIONS,
            "input": inputs,
        },
        headers={
            "Authorization": "Bearer " + os.environ["HOPS_SEARCH_EMBEDDING_API_KEY"]
        },
    )
    if response.status_code != 200:
        raise ValueError(f"임베딩 API HTTP {response.status_code}")
    payload = response.json()
    entries = sorted(payload["data"], key=lambda entry: entry["index"])
    if payload["model"] != MODEL or [item["index"] for item in entries] != list(
        range(len(inputs))
    ):
        raise ValueError("임베딩 API 모델 또는 응답 순서 불일치")
    vectors = [item["embedding"] for item in entries]
    if any(
        len(vector) != DIMENSIONS
        or not all(math.isfinite(x) for x in vector)
        or not any(vector)
        for vector in vectors
    ):
        raise ValueError("임베딩 차원 또는 값 불일치")
    return ["[" + ",".join(map(str, vector)) + "]" for vector in vectors]


def load_vectors(db, client, docs):
    chunks = []
    for doc in docs:
        for number, passage in enumerate(
            passages(doc["document_type"], doc["content"]), 1
        ):
            if passage:
                chunks.append((doc, number, passage))
    inputs = [
        f"{doc['title']}\n적용 조건: {doc['applicability'] or ''}\n\n{passage}"
        for doc, _, passage in chunks
    ]
    vectors = embeddings(client, inputs)
    with db.cursor() as cur:
        cur.executemany(
            """
            INSERT INTO hops_knowledge.knowledge_chunk
              (knowledge_document_version_id, chunk_no, content, embedding, content_digest, chunk_digest, embedding_model)
            VALUES (%s, %s, %s, %s::vector, %s, %s, %s)
            ON CONFLICT (knowledge_document_version_id, chunk_no) DO UPDATE
              SET content = excluded.content, embedding = excluded.embedding,
                  content_digest = excluded.content_digest, chunk_digest = excluded.chunk_digest,
                  embedding_model = excluded.embedding_model
            """,
            [
                (
                    doc["id"],
                    number,
                    passage,
                    vector,
                    doc["content_digest"],
                    sha(passage),
                    MODEL,
                )
                for (doc, number, passage), vector in zip(chunks, vectors, strict=True)
            ],
        )
    for doc in docs:
        db.execute(
            """
            DELETE FROM hops_knowledge.knowledge_chunk WHERE knowledge_document_version_id = %s
              AND embedding_model = %s AND chunk_no > %s
            """,
            (doc["id"], MODEL, len(passages(doc["document_type"], doc["content"]))),
        )
    return len(chunks)


def graph_rows(db, docs):
    ids = [doc["id"] for doc in docs]
    case_ids = [doc["case_id"] for doc in docs if doc["case_id"]]
    # 카탈로그의 3개 시스템·기능만 투영한다.
    systems = db.execute("""
        SELECT id, code, name FROM it_system WHERE deleted_at IS NULL
          AND code IN ('ELECTRONIC_APPROVAL', 'COMMON_LOGIN', 'FILE_SHARING')
    """).fetchall()
    features = db.execute(
        """
        SELECT id, code, name, it_system_id FROM system_feature
        WHERE deleted_at IS NULL AND it_system_id = ANY(%s)
    """,
        ([system["id"] for system in systems],),
    ).fetchall()
    nodes = {"System": systems, "Feature": features}
    properties = "review_status, access_level, source_document_version_id"
    for label, (table, columns) in NODES.items():
        nodes[label] = db.execute(
            f"""
            SELECT id, {columns}, {properties} FROM {table}
            WHERE deleted_at IS NULL AND source_document_version_id = ANY(%s)
        """,
            (ids,),
        ).fetchall()
    nodes["ApprovedCase"] = db.execute(
        """
        SELECT c.id, c.case_number, c.version_no, c.it_system_id, c.access_level,
               c.case_status AS review_status, v.id AS source_document_version_id
        FROM resolved_case c JOIN knowledge_document_version v ON v.knowledge_document_id = c.id
        WHERE c.id = ANY(%s) AND v.id = ANY(%s)
    """,
        (case_ids, ids),
    ).fetchall()
    edges = {}
    for name, (table, source, target) in EDGES.items():
        edges[name] = db.execute(
            f"""
            SELECT {source} AS source, {target} AS target, {properties} FROM {table}
            WHERE deleted_at IS NULL AND source_document_version_id = ANY(%s)
        """,
            (ids,),
        ).fetchall()
    for name, label, source in (
        ("HAS_FEATURE", "Feature", "it_system_id"),
        ("HAS_SYMPTOM", "Symptom", "system_feature_id"),
        ("HAS_QUESTION", "Question", "check_item_id"),
        ("HAS_CHECK_ITEM", "CheckItem", "it_system_id"),
    ):
        edges[name] = [
            {"source": node[source], "target": node["id"]} for node in nodes[label]
        ]
    node_ids = {row["id"] for rows in nodes.values() for row in rows}
    if len(node_ids) != sum(map(len, nodes.values())) or any(
        row["source"] not in node_ids or row["target"] not in node_ids
        for rows in edges.values()
        for row in rows
    ):
        raise ValueError("그래프 시드에 중복 또는 누락된 연결 대상이 있습니다")
    return nodes, edges


def neo4j(client, statements):
    url = os.environ["HOPS_SEARCH_NEO4J_URL"].rstrip("/")
    database = os.environ.get("HOPS_SEARCH_NEO4J_DATABASE", "neo4j")
    response = client.post(
        f"{url}/db/{database}/tx/commit",
        json={"statements": statements},
        auth=(
            os.environ.get("HOPS_NEO4J_USERNAME", "neo4j"),
            os.environ["HOPS_NEO4J_PASSWORD"],
        ),
    )
    if response.status_code != 200:
        raise ValueError(f"Neo4j HTTP {response.status_code}")
    errors = response.json()["errors"]
    if errors:
        raise ValueError(
            "Neo4j 트랜잭션 실패 " + ", ".join(error["code"] for error in errors)
        )


def statement(cypher, rows=None):
    return {
        "statement": cypher,
        "parameters": {
            "rows": [
                {k: str(v) if isinstance(v, UUID) else v for k, v in row.items()}
                for row in (rows or [])
            ]
        },
    }


def load_graph(client, nodes, edges):
    neo4j(
        client,
        [
            statement(
                "CREATE CONSTRAINT hops_p0_id IF NOT EXISTS "
                "FOR (n:HopsP0) REQUIRE n.id IS UNIQUE"
            )
        ],
    )
    # P0 관리 라벨의 이전 스냅샷만 한 트랜잭션에서 교체한다. 다른 라벨은 건드리지 않는다.
    statements = [statement("MATCH (n:HopsP0) DETACH DELETE n")]
    for label, rows in nodes.items():
        statements.append(
            statement(
                f"""
            UNWIND $rows AS row CREATE (n:HopsP0:{label}) SET n = row
        """,
                rows,
            )
        )
    for name, rows in edges.items():
        statements.append(
            statement(
                f"""
            UNWIND $rows AS row
            MATCH (a:HopsP0 {{id: row.source}}), (b:HopsP0 {{id: row.target}})
            MERGE (a)-[r:{name}]->(b) SET r = row
        """,
                rows,
            )
        )
    neo4j(client, statements)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--isolated-demo",
        required=True,
        action="store_true",
        help="별도 시연 PG·Neo4j DB를 대상으로 P0 스냅샷을 갱신",
    )
    parser.parse_args()
    _, errors = validate_seed()
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    try:
        with (
            psycopg.connect(os.environ["HOPS_SEARCH_DSN"], row_factory=dict_row) as db,
            httpx.Client(timeout=30) as client,
        ):
            db.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            # 두 저장소를 갱신하는 시연 적재가 동시에 실행되지 않게 한다.
            locked = db.execute(
                "SELECT pg_try_advisory_xact_lock(244, 1) AS locked"
            ).fetchone()
            if not locked["locked"]:
                raise ValueError("시연 색인 적재가 이미 진행 중입니다")
            docs = load_documents(db)
            nodes, edges = graph_rows(db, docs)
            chunk_count = load_vectors(db, client, docs)
            load_graph(client, nodes, edges)
            # 사전 검수된 기존 시연 사례만, 실제 양쪽 반영이 완료된 뒤 공개 상태를 갱신한다.
            db.execute(
                """
                UPDATE resolved_case SET vector_status = 'SUCCEEDED', graph_status = 'SUCCEEDED'
                WHERE id = ANY(%s) AND origin_type = 'SYNTHETIC' AND case_status = 'APPROVED'
                """,
                ([doc["case_id"] for doc in docs if doc["case_id"]],),
            )
            db.commit()
            print(f"문서 버전 {len(docs)}개, {MODEL}/{DIMENSIONS} 청크 {chunk_count}개")
            print(
                f"Neo4j 노드 {sum(map(len, nodes.values()))}개, 관계 {sum(map(len, edges.values()))}개"
            )
    except (KeyError, ValueError, httpx.HTTPError, psycopg.Error) as exc:
        # DSN·외부 응답 본문·승인 원문이 오류 로그에 들어가지 않게 한다.
        detail = str(exc) if type(exc) is ValueError else type(exc).__name__
        print(f"시연 색인 적재 실패 ({detail})", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
