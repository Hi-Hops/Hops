"""색인 작업 저장소와 pgvector 반영. 명시한 테스트 전용 PostgreSQL에서만 실행한다.

- HOPS_LEDGER_TEST_DSN(hops_ai_runtime_test): ai_index_job 접수의 원자성·충돌·조건부 상태 변경
- HOPS_INDEX_SEARCH_TEST_DSN(hops_search_test, pgvector 필요): 검색 조각 upsert와 확인
"""

import asyncio
import hashlib
import os
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
import pytest
from psycopg.conninfo import conninfo_to_dict

from hops_ai.contracts.indexing import CaseIndexSource, IndexApplyRequest
from hops_ai.persistence.index_jobs import IndexJobConflict, IndexJobStore
from hops_ai.services.indexing.clients import EMBEDDING_MODEL
from hops_ai.services.indexing.stores import VectorStore
from tests.unit.test_index_jobs import CASE_ID, request

MIGRATIONS = Path(__file__).parents[2] / "migrations"


@pytest.fixture
async def jobs() -> IndexJobStore:
    conninfo = os.environ.get("HOPS_LEDGER_TEST_DSN")
    if not conninfo:
        pytest.skip("테스트 전용 HOPS_LEDGER_TEST_DSN이 설정되지 않았다")
    if conninfo_to_dict(conninfo).get("dbname") != "hops_ai_runtime_test":
        pytest.fail("색인 작업 통합 테스트는 hops_ai_runtime_test DB에서만 실행한다")
    async with await psycopg.AsyncConnection.connect(conninfo, autocommit=True) as conn:
        cursor = await conn.execute("SELECT to_regclass('ai_index_job') IS NOT NULL")
        if not (await cursor.fetchone())[0]:
            await conn.execute((MIGRATIONS / "003_index_jobs.sql").read_text(encoding="utf-8"))
    return IndexJobStore(conninfo)


def fields(job_id: str, digest: str = "r" * 64) -> dict:
    return {
        "job_id": job_id,
        "attempt": 1,
        "case_id": CASE_ID,
        "case_version": 1,
        "store": "VECTOR",
        "content_digest": "c" * 64,
        "request_digest": hashlib.sha256(digest.encode()).hexdigest(),
    }


async def test_concurrent_acceptance_of_one_key_creates_one_job(jobs: IndexJobStore) -> None:
    job_id = str(uuid4())

    results = await asyncio.gather(*(jobs.accept(**fields(job_id)) for _ in range(5)))

    assert sum(result.created for result in results) == 1
    assert {result.job.state for result in results} == {"PENDING"}


async def test_a_different_request_under_an_existing_key_is_a_conflict(jobs: IndexJobStore) -> None:
    job_id = str(uuid4())
    await jobs.accept(**fields(job_id))

    with pytest.raises(IndexJobConflict):
        await jobs.accept(**fields(job_id, digest="other"))


async def test_a_state_moves_only_from_the_expected_states(jobs: IndexJobStore) -> None:
    job_id = str(uuid4())
    await jobs.accept(**fields(job_id))

    running = await jobs.transition(job_id, 1, "RUNNING", from_states=("PENDING",))
    done = await jobs.transition(job_id, 1, "SUCCEEDED", from_states=("RUNNING",))
    late = await jobs.transition(
        job_id,
        1,
        "FAILED",
        {"code": "INDEX_UNAVAILABLE", "message": "늦은 실패", "retryable": True},
        from_states=("RUNNING",),
    )

    assert running is not None and done is not None and done.state == "SUCCEEDED"
    # 끝난 작업을 늦은 결과가 덮지 않는다
    assert late is None
    stored = await jobs.get(job_id, 1)
    assert stored is not None and stored.state == "SUCCEEDED" and stored.error is None


async def test_a_failed_state_requires_an_error(jobs: IndexJobStore) -> None:
    job_id = str(uuid4())
    await jobs.accept(**fields(job_id))

    with pytest.raises(psycopg.errors.CheckViolation):
        await jobs.transition(job_id, 1, "FAILED", None, from_states=("PENDING",))


class FixedEmbeddings:
    async def embed(self, inputs: list[str]) -> list[str]:
        return ["[" + ",".join(["0.5"] * 1536) + "]" for _ in inputs]


@pytest.fixture
async def search_dsn() -> str:
    conninfo = os.environ.get("HOPS_INDEX_SEARCH_TEST_DSN")
    if not conninfo:
        pytest.skip("테스트 전용 HOPS_INDEX_SEARCH_TEST_DSN이 설정되지 않았다")
    if conninfo_to_dict(conninfo).get("dbname") != "hops_search_test":
        pytest.fail("검색 조각 통합 테스트는 hops_search_test DB에서만 실행한다")
    async with await psycopg.AsyncConnection.connect(conninfo, autocommit=True) as conn:
        await conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
        await conn.execute("CREATE SCHEMA IF NOT EXISTS hops_knowledge")
        # Spring V19·V27의 열과 제약(FK 제외)을 따른다. FK 대상 업무 테이블은 이 테스트 범위 밖이다.
        await conn.execute(
            """
            CREATE TABLE IF NOT EXISTS hops_knowledge.knowledge_chunk (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                knowledge_document_version_id UUID NOT NULL,
                chunk_no INT NOT NULL,
                content TEXT NOT NULL,
                embedding vector NOT NULL,
                content_digest CHAR(64) NOT NULL,
                chunk_digest CHAR(64) NOT NULL,
                embedding_model VARCHAR(100),
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                CONSTRAINT uk_knowledge_chunk_version_chunk
                    UNIQUE (knowledge_document_version_id, chunk_no)
            )
            """
        )
    return conninfo


def case_source(version_id: str, content: str = "사례 본문") -> CaseIndexSource:
    return CaseIndexSource(
        contractVersion="hops-system-1",
        caseId=CASE_ID,
        caseVersion=1,
        caseNumber="RC-0001",
        documentVersionId=version_id,
        title="제목",
        applicability="조건",
        content=content,
        contentDigest="d" * 64,
    )


async def test_the_case_becomes_one_verified_chunk_and_reapplying_does_not_duplicate(
    search_dsn: str,
) -> None:
    version_id = str(uuid4())
    async with await psycopg.AsyncConnection.connect(search_dsn, autocommit=True) as conn:
        # 이전 반영이 남긴 두 번째 조각은 지운다(같은 승인 버전은 조각 1개)
        await conn.execute(
            """INSERT INTO hops_knowledge.knowledge_chunk
               (knowledge_document_version_id, chunk_no, content, embedding, content_digest,
                chunk_digest, embedding_model)
               VALUES (%s, 2, 'old', %s::vector, %s, %s, %s)""",
            (
                UUID(version_id),
                "[" + ",".join(["0.1"] * 1536) + "]",
                "d" * 64,
                "e" * 64,
                EMBEDDING_MODEL,
            ),
        )
    store = VectorStore(search_dsn, FixedEmbeddings())
    job = IndexApplyRequest.model_validate(request())

    await store.apply(job, case_source(version_id))
    await store.apply(job, case_source(version_id))

    async with await psycopg.AsyncConnection.connect(search_dsn) as conn:
        cursor = await conn.execute(
            """SELECT chunk_no, content, content_digest, chunk_digest, embedding_model,
                      vector_dims(embedding)
               FROM hops_knowledge.knowledge_chunk WHERE knowledge_document_version_id = %s""",
            (UUID(version_id),),
        )
        rows = await cursor.fetchall()
    assert rows == [
        (
            1,
            "사례 본문",
            "d" * 64,
            hashlib.sha256("사례 본문".encode()).hexdigest(),
            EMBEDDING_MODEL,
            1536,
        )
    ]
