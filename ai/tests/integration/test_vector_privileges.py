"""격리 업무 테스트 DB에서 실제 최소 권한·FK·청크 저장을 검사한다. 임베딩만 대역이다."""

import os
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
import tiktoken
from psycopg import conninfo, sql

from hops_ai.api.routes.index_jobs import ApprovedCase
from hops_ai.services.indexing.approved_case import canonical, sha256
from hops_ai.services.indexing.pgvector import ApprovedDocumentVersion, PgVectorCaseIndex

ADMIN_DSN = os.getenv("HOPS_INDEX_VECTOR_ADMIN_TEST_DSN")
pytestmark = pytest.mark.skipif(not ADMIN_DSN, reason="격리 최소 권한 Vector 테스트 DB 미설정")


async def test_large_case_chunks_with_actual_minimum_role_and_foreign_key():
    if not conninfo.conninfo_to_dict(ADMIN_DSN).get("dbname", "").endswith("_test"):
        pytest.fail("전용 _test DB만 허용합니다")
    ai_root = Path(__file__).resolve().parents[2]
    migrations = Path(
        os.getenv(
            "HOPS_INDEX_BUSINESS_MIGRATIONS",
            str(ai_root.parent / "backend/spring/hops/src/main/resources/db/migration"),
        )
    )
    role = "hops_index_test_" + uuid4().hex[:12]
    document_id, version_id, case_id = uuid4(), uuid4(), uuid4()
    approved = {
        "title": "가상 대형 사례",
        "systemId": str(uuid4()),
        "accessScope": "SYSTEM_STAFF",
        "problemStatement": "가" * 4000,
        "action": "나" * 4000,
        "verification": "다" * 4000,
        "applicability": "라" * 4000,
        "limitations": ["마" * 4000 for _ in range(18)],
        "workRecordId": str(uuid4()),
        "resolutionRequestId": str(uuid4()),
        "approvedBy": str(uuid4()),
        "approvedAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "confirmedCause": None,
        "appliesToSystemVersion": None,
        "origin": "SYNTHETIC",
        "confirmationOrigin": "HOPS_USER",
        "sourceMessageIds": [],
        "entityLinks": [],
        "caseType": "RESOLUTION",
    }
    ApprovedCase.model_validate(approved)
    body = {"caseId": str(case_id), "caseVersion": 1, "approvedCase": approved}
    content = canonical(body)
    digest = sha256(content)
    payload = {**body, "store": "VECTOR"}
    request = {
        **payload,
        "contractVersion": "hops-system-1",
        "contentDigest": sha256(canonical(payload)),
    }
    encoding = tiktoken.encoding_for_model("text-embedding-3-small")
    assert len(encoding.encode_ordinary(content)) > 8192
    calls = []

    async def embed(chunks):
        assert len(chunks) <= 32
        assert all(len(encoding.encode_ordinary(chunk)) <= 2000 for chunk in chunks)
        calls.append(list(chunks))
        return [[1.0, 0.0, 0.0] for _ in chunks]

    async with await psycopg.AsyncConnection.connect(ADMIN_DSN, autocommit=True) as admin:
        await admin.execute("CREATE EXTENSION IF NOT EXISTS vector")
        if (await (await admin.execute("SELECT to_regclass('knowledge_document')")).fetchone())[
            0
        ] is None:
            await admin.execute("CREATE TABLE IF NOT EXISTS it_system(id UUID PRIMARY KEY)")
            for name in (
                "V17__create_knowledge_document.sql",
                "V18__create_knowledge_document_version.sql",
                "V19__create_knowledge_chunk.sql",
            ):
                await admin.execute((migrations / name).read_text(encoding="utf-8"), prepare=False)
        await admin.execute(
            (ai_root / "db/05_index_roles.sql").read_text().replace("hops_ai_indexer", role)
        )
        try:
            privileges = (
                (ai_root / "db/06_vector_privileges.sql")
                .read_text()
                .replace("hops_ai_indexer", role)
            )
            await admin.execute(privileges, prepare=False)
            await admin.execute(privileges, prepare=False)  # 권한 절차의 재실행도 검증한다.
            await admin.execute(
                sql.SQL("ALTER ROLE {} PASSWORD {}").format(
                    sql.Identifier(role), sql.Literal("case170-fixture")
                )
            )
            await admin.execute(
                "INSERT INTO knowledge_document(id,title,document_type,access_level,"
                "data_split,review_status) VALUES (%s,'대형 사례','CASE','STAFF','RAG','APPROVED')",
                (document_id,),
            )
            await admin.execute(
                "INSERT INTO knowledge_document_version(id,knowledge_document_id,version_no,"
                "content,content_digest,source_metadata,review_status) "
                "VALUES (%s,%s,1,%s,%s,'{}','APPROVED')",
                (version_id, document_id, content, digest),
            )
            restricted_dsn = conninfo.make_conninfo(
                ADMIN_DSN, user=role, password="case170-fixture"
            )
            async with await psycopg.AsyncConnection.connect(
                restricted_dsn, autocommit=True
            ) as limited:
                store = PgVectorCaseIndex(limited, embed, 3, "text-embedding-3-small")
                reference = ApprovedDocumentVersion(version_id, case_id, 1, digest)
                await store.apply(request, reference)
                calls_before = len(calls)
                await store.apply(request, reference)
                assert len(calls) == calls_before
                rows = await (
                    await limited.execute(
                        "SELECT chunk_no,content,content_digest,chunk_digest "
                        "FROM hops_knowledge.knowledge_chunk "
                        "WHERE knowledge_document_version_id=%s "
                        "ORDER BY chunk_no",
                        (version_id,),
                    )
                ).fetchall()
                assert len(rows) > 1 and "".join(row[1] for row in rows) == content
                assert all(
                    row[0] == number and row[2] == digest and row[3] == sha256(row[1])
                    for number, row in enumerate(rows, 1)
                )
                for forbidden in (
                    "SELECT * FROM public.knowledge_document_version",
                    "INSERT INTO public.knowledge_document(title,document_type,"
                    "access_level,data_split) "
                    "VALUES ('forbidden','CASE','STAFF','RAG')",
                    "UPDATE public.knowledge_document_version SET content='changed'",
                    "DELETE FROM public.knowledge_document",
                    "UPDATE hops_knowledge.knowledge_chunk SET content='changed'",
                    "DELETE FROM hops_knowledge.knowledge_chunk",
                ):
                    with pytest.raises(psycopg.errors.InsufficientPrivilege):
                        await limited.execute(forbidden)
                with pytest.raises(psycopg.errors.ForeignKeyViolation):
                    await limited.execute(
                        "INSERT INTO hops_knowledge.knowledge_chunk(knowledge_document_version_id,"
                        "chunk_no,content,embedding,content_digest,chunk_digest) "
                        "VALUES (%s,1,'x','[1,0,0]',%s,%s)",
                        (uuid4(), digest, digest),
                    )
                await admin.execute(
                    "UPDATE hops_knowledge.knowledge_chunk SET chunk_digest=%s "
                    "WHERE knowledge_document_version_id=%s AND chunk_no=2",
                    ("0" * 64, version_id),
                )
                with pytest.raises(ValueError, match="승인 문서"):
                    await store.apply(request, reference)
                assert len(calls) == calls_before
        finally:
            await admin.execute(
                "DELETE FROM hops_knowledge.knowledge_chunk WHERE knowledge_document_version_id=%s",
                (version_id,),
            )
            await admin.execute("DELETE FROM knowledge_document_version WHERE id=%s", (version_id,))
            await admin.execute("DELETE FROM knowledge_document WHERE id=%s", (document_id,))
            await admin.execute(sql.SQL("DROP OWNED BY {}").format(sql.Identifier(role)))
            await admin.execute(sql.SQL("DROP ROLE {}").format(sql.Identifier(role)))
