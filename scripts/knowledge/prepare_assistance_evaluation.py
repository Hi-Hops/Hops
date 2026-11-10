"""실제 임베딩 캐시와 격리 API 평가용 SQL. 정답 자료는 검색 색인에 넣지 않는다."""

import json
import os
from pathlib import Path

root = Path(__file__).resolve().parents[2]
import argparse

parser = argparse.ArgumentParser(
    description="별도 로컬 DB의 합성 코퍼스와 독립 질의용 실제 임베딩 평가 스냅샷을 준비한다."
)
parser.add_argument("--out", type=Path, default=root / ".local/assistance")
args = parser.parse_args()
out = args.out.resolve()
out.mkdir(parents=True, exist_ok=True)
import httpx
import psycopg
from assistance_pack import index_documents, load_pack, seed_sql
from build_case_seed import Chain
from load_search_demo import embeddings
from psycopg.rows import dict_row

cards, docs = load_pack()
bykey = {c.key: c for c in cards}
queries = json.loads(
    (root / "ai/evaluation/recommendations/electronic-approval-v1.json").read_text()
)["queries"]
frames = []
inputs = []
for q in queries:
    c = Chain(bykey[q["sourceCaseKey"]])
    title = q["query"].split("\n")[0]
    feature = q["expectedConditions"].get("기능", "")
    context = "\n".join(dict.fromkeys([title, feature, *q["facts"]]))
    frames.append(
        dict(
            q,
            ticketId=c.id("ticket"),
            standardId=c.id("standard-request"),
            messageId=c.id("msg3"),
            title=title,
            feature=feature,
            context=context,
        )
    )
    inputs.extend([title, context])
cachepath = out / "query-vectors.json"
cache = json.loads(cachepath.read_text()) if cachepath.exists() else {}
missing = list(dict.fromkeys(x for x in inputs if x not in cache))
assert len(cache) + len(missing) + 96 <= 500
if missing:
    with httpx.Client(timeout=90) as client:
        for start in range(0, len(missing), 32):
            batch = missing[start : start + 32]
            vecs = embeddings(client, batch)
            cache.update(zip(batch, vecs))
            cachepath.write_text(json.dumps(cache, ensure_ascii=False))
            print(
                "query embedding inputs cached:",
                min(start + 32, len(missing)),
                flush=True,
            )
(out / "frames.json").write_text(json.dumps(frames, ensure_ascii=False))
with psycopg.connect(os.environ["HOPS_SEARCH_DSN"], row_factory=dict_row) as db:
    rows = index_documents(db, cards, docs)
    version_ids = [r["id"] for r in rows]
    chunks = db.execute(
        "SELECT knowledge_document_version_id,chunk_no,content,embedding::text AS vector,content_digest,chunk_digest,embedding_model FROM hops_knowledge.knowledge_chunk WHERE knowledge_document_version_id=ANY(%s)",
        (version_ids,),
    ).fetchall()
from sqlutil import sql_string

sql = seed_sql(cards, docs)
sql += "\nUPDATE resolved_case SET vector_status='SUCCEEDED', graph_status='SUCCEEDED' WHERE case_number LIKE 'EA80-C-%';\n"
for r in chunks:
    vals = [
        str(r["knowledge_document_version_id"]),
        r["chunk_no"],
        r["content"],
        r["vector"],
        r["content_digest"],
        r["chunk_digest"],
        r["embedding_model"],
    ]
    sql += (
        "INSERT INTO hops_knowledge.knowledge_chunk(knowledge_document_version_id,chunk_no,content,embedding,content_digest,chunk_digest,embedding_model) VALUES("
        + ",".join(
            str(v)
            if isinstance(v, int)
            else sql_string(v) + ("::vector" if i == 3 else "")
            for i, v in enumerate(vals)
        )
        + ") ON CONFLICT DO NOTHING;\n"
    )
(out / "evaluation-index.sql").write_text(sql)
print(
    "evaluation corpus:",
    len(cards),
    "cases,",
    len(docs),
    "documents,",
    len(chunks),
    "real embedding chunks; query inputs",
    len(cache),
)
