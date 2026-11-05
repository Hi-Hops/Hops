"""실제 원장 DB의 멱등 접수 검사. 저장소 반영은 명시한 대역과 별도로 검증한다."""

import asyncio
import os
from datetime import UTC, datetime
from uuid import UUID, uuid4

import httpx
import psycopg
import pytest
from fastapi import FastAPI
from psycopg.types.json import Jsonb
from pydantic import SecretStr

from hops_ai.api.auth import ServiceAuthMiddleware
from hops_ai.api.errors import install_error_handlers
from hops_ai.api.routes import index_jobs
from hops_ai.services.indexing.approved_case import canonical, sha256
from hops_ai.settings import Settings

DSN = os.getenv("HOPS_INDEX_TEST_DSN")
TOKEN = "a" * 43
pytestmark = pytest.mark.skipif(not DSN, reason="격리 색인 원장 테스트 DB 미설정")


def request_body(store="NEO4J"):
    body = {
        "workRecordId": str(uuid4()),
        "resolutionRequestId": str(uuid4()),
        "approvedBy": str(uuid4()),
        "approvedAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "title": "가상 사례",
        "action": "조치",
        "verification": "검증",
        "applicability": "조건",
        "limitations": ["가상 검증"],
        "appliesToSystemVersion": None,
        "accessScope": "SYSTEM_STAFF",
        "origin": "SYNTHETIC",
        "confirmationOrigin": "HOPS_USER",
        "sourceMessageIds": [],
        "entityLinks": [],
        "systemId": str(uuid4()),
        "confirmedCause": None,
        "problemStatement": "문제",
        "caseType": "RESOLUTION",
    }
    content = {"caseId": str(uuid4()), "caseVersion": 1, "store": store, "approvedCase": body}
    return {
        **content,
        "contractVersion": "hops-system-1",
        "jobId": str(uuid4()),
        "attempt": 1,
        "contentDigest": sha256(canonical(content)),
    }


@pytest.fixture
async def client():
    settings = Settings(_env_file=None, runtime_dsn=SecretStr(DSN))
    app = FastAPI()
    app.add_middleware(ServiceAuthMiddleware, token=SecretStr(TOKEN))
    install_error_handlers(app)
    app.include_router(index_jobs.index_router(settings))
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://test",
        headers={"Authorization": "Bearer " + TOKEN},
    ) as client:
        yield client


async def cleanup(request):
    async with await psycopg.AsyncConnection.connect(DSN, autocommit=True) as connection:
        await connection.execute("DELETE FROM ai_index_job WHERE job_id=%s", (request["jobId"],))


async def test_concurrent_replays_execute_once_and_conflicting_body_is_rejected(
    client, monkeypatch
):
    request = request_body()
    count = 0

    async def apply(*_args):
        nonlocal count
        count += 1
        await asyncio.sleep(0.05)

    monkeypatch.setattr(index_jobs, "_apply", apply)
    try:
        responses = await asyncio.gather(
            *(client.post("/internal/v2/index-jobs", json=request) for _ in range(5))
        )
        assert all(response.status_code == 202 for response in responses)
        assert count == 1
        replay = await client.post("/internal/v2/index-jobs", json=request)
        assert replay.json()["state"] == "SUCCEEDED"
        changed = {**request, "approvedCase": {**request["approvedCase"], "title": "다른 의도"}}
        changed["contentDigest"] = sha256(
            canonical(
                {key: changed[key] for key in ("caseId", "caseVersion", "store", "approvedCase")}
            )
        )
        assert (await client.post("/internal/v2/index-jobs", json=changed)).status_code == 409
    finally:
        await cleanup(request)


@pytest.mark.parametrize("failure", [httpx.ConnectError, httpx.ReadTimeout])
async def test_unknown_and_previously_running_jobs_are_never_executed_again(
    client, monkeypatch, failure
):
    request = request_body()
    count = 0

    async def apply(*_args):
        nonlocal count
        count += 1
        raise failure("synthetic unreachable store")

    monkeypatch.setattr(index_jobs, "_apply", apply)
    try:
        for _ in range(2):
            response = await client.post("/internal/v2/index-jobs", json=request)
            assert response.json()["state"] == "UNKNOWN"
        assert count == 1
        snapshot = response.json()
        snapshot["state"] = "RUNNING"
        async with await psycopg.AsyncConnection.connect(DSN, autocommit=True) as connection:
            await connection.execute(
                "UPDATE ai_index_job SET snapshot=%s WHERE job_id=%s",
                (Jsonb(snapshot), request["jobId"]),
            )
        assert (await client.post("/internal/v2/index-jobs", json=request)).json()[
            "state"
        ] == "RUNNING"
        assert count == 1
    finally:
        await cleanup(request)


@pytest.mark.parametrize("store", ["VECTOR", "NEO4J"])
@pytest.mark.parametrize("status", [400, 401, 403, 404, 422, 429])
async def test_definite_http_rejection_is_failed_and_next_attempt_can_recover(
    client, monkeypatch, store, status
):
    request = request_body(store)
    calls = 0

    async def apply(*_args):
        nonlocal calls
        calls += 1
        if calls == 1:
            response = httpx.Response(
                status,
                request=httpx.Request("POST", "https://store.test/"),
                text="synthetic-private-response",
            )
            response.raise_for_status()

    headers = {}
    if store == "VECTOR":
        content = {key: request[key] for key in ("caseId", "caseVersion", "approvedCase")}
        headers = {
            "X-Hops-Document-Version": str(uuid4()),
            "X-Hops-Document-Digest": sha256(canonical(content)),
        }
    monkeypatch.setattr(index_jobs, "_apply", apply)
    try:
        first = await client.post("/internal/v2/index-jobs", json=request, headers=headers)
        assert first.status_code == 202
        assert first.json()["state"] == "FAILED"
        assert first.json()["error"]["retryable"] == (status == 429)
        assert "synthetic-private-response" not in first.text
        replay = await client.post("/internal/v2/index-jobs", json=request, headers=headers)
        assert replay.json() == first.json()
        assert calls == 1
        recovered = await client.post(
            "/internal/v2/index-jobs", json={**request, "attempt": 2}, headers=headers
        )
        assert recovered.json()["state"] == "SUCCEEDED"
        assert recovered.json()["jobId"] == request["jobId"]
        assert recovered.json()["contentDigest"] == request["contentDigest"]
        assert calls == 2
    finally:
        await cleanup(request)


@pytest.mark.parametrize("status", [408, 500, 502, 503, 504])
async def test_ambiguous_http_response_stays_unknown_and_is_not_reexecuted(
    client, monkeypatch, status
):
    request = request_body()
    calls = 0

    async def apply(*_args):
        nonlocal calls
        calls += 1
        httpx.Response(
            status, request=httpx.Request("POST", "https://store.test/")
        ).raise_for_status()

    monkeypatch.setattr(index_jobs, "_apply", apply)
    try:
        for _ in range(2):
            response = await client.post("/internal/v2/index-jobs", json=request)
            assert response.status_code == 202
            assert response.json()["state"] == "UNKNOWN"
        assert calls == 1
    finally:
        await cleanup(request)


async def test_auth_digest_schema_and_missing_job(client, monkeypatch):
    async def forbidden(*_args):
        pytest.fail("잘못된 요청은 저장소를 호출하면 안 됩니다")

    monkeypatch.setattr(index_jobs, "_apply", forbidden)
    request = request_body()
    assert (
        await client.post(
            "/internal/v2/index-jobs", json=request, headers={"Authorization": "Bearer invalid"}
        )
    ).status_code == 401
    assert (
        await client.post("/internal/v2/index-jobs", json={**request, "contentDigest": "0" * 64})
    ).status_code == 409
    invalid = {**request, "approvedCase": {**request["approvedCase"], "caseType": "OTHER"}}
    assert (await client.post("/internal/v2/index-jobs", json=invalid)).status_code == 422
    missing = await client.get(f"/internal/v2/index-jobs/{uuid4()}?attempt=1")
    assert missing.status_code == 404 and missing.json()["code"] == "NOT_FOUND"


@pytest.mark.parametrize(
    "failure",
    [
        psycopg.errors.InsufficientPrivilege,
        psycopg.errors.UndefinedTable,
        psycopg.errors.UndefinedColumn,
        psycopg.errors.CheckViolation,
        psycopg.errors.InvalidSchemaName,
        psycopg.errors.SerializationFailure,
        psycopg.errors.DeadlockDetected,
    ],
)
async def test_sql_rejection_is_failed_and_next_attempt_can_recover(client, monkeypatch, failure):
    request = request_body("VECTOR")
    content = {key: request[key] for key in ("caseId", "caseVersion", "approvedCase")}
    headers = {
        "X-Hops-Document-Version": str(uuid4()),
        "X-Hops-Document-Digest": sha256(canonical(content)),
    }
    calls = 0

    async def apply(*_args):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise failure("synthetic-private-sql-details")

    monkeypatch.setattr(index_jobs, "_apply", apply)
    try:
        first = await client.post("/internal/v2/index-jobs", json=request, headers=headers)
        assert first.status_code == 202
        assert first.json()["state"] == "FAILED"
        assert "synthetic-private-sql-details" not in first.text
        replay = await client.post("/internal/v2/index-jobs", json=request, headers=headers)
        assert replay.json() == first.json()
        assert calls == 1
        recovered = await client.post(
            "/internal/v2/index-jobs", json={**request, "attempt": 2}, headers=headers
        )
        assert recovered.json()["state"] == "SUCCEEDED"
        assert recovered.json()["jobId"] == request["jobId"]
        assert recovered.json()["contentDigest"] == request["contentDigest"]
        assert calls == 2
    finally:
        await cleanup(request)


@pytest.mark.parametrize(
    "failure",
    [
        psycopg.OperationalError,
        psycopg.errors.ConnectionFailure,
        psycopg.errors.TransactionResolutionUnknown,
        psycopg.errors.StatementCompletionUnknown,
        psycopg.errors.AdminShutdown,
        psycopg.errors.QueryCanceled,
    ],
)
async def test_ambiguous_sql_failure_stays_unknown_without_reexecution(
    client, monkeypatch, failure
):
    request = request_body()
    calls = 0

    async def apply(*_args):
        nonlocal calls
        calls += 1
        raise failure("synthetic-private-sql-details")

    monkeypatch.setattr(index_jobs, "_apply", apply)
    try:
        for _ in range(2):
            response = await client.post("/internal/v2/index-jobs", json=request)
            assert response.status_code == 202
            assert response.json()["state"] == "UNKNOWN"
            assert "synthetic-private-sql-details" not in response.text
        assert calls == 1
    finally:
        await cleanup(request)


async def test_real_postgres_server_rejection_is_recorded_as_failed(client, monkeypatch):
    request = request_body()

    async def apply(*_args):
        async with await psycopg.AsyncConnection.connect(DSN) as connection:
            await connection.execute("SELECT * FROM case170_deliberately_missing_table")

    monkeypatch.setattr(index_jobs, "_apply", apply)
    try:
        response = await client.post("/internal/v2/index-jobs", json=request)
        assert response.status_code == 202
        assert response.json()["state"] == "FAILED"
        assert "case170_deliberately_missing_table" not in response.text
    finally:
        await cleanup(request)


async def test_vector_requires_original_document_reference(client):
    request = request_body("VECTOR")
    response = await client.post("/internal/v2/index-jobs", json=request)
    assert response.status_code == 422


@pytest.mark.parametrize("field", ["problemStatement", "action", "verification", "applicability"])
@pytest.mark.parametrize("character", ["가", "😀"])
@pytest.mark.parametrize("length", [4000, 4001])
async def test_case_content_boundary_matches_spring_code_points(
    client, monkeypatch, field, character, length
):
    request = request_body()
    request["approvedCase"][field] = character * length
    request["contentDigest"] = sha256(
        canonical({key: request[key] for key in ("caseId", "caseVersion", "store", "approvedCase")})
    )
    calls = 0

    async def apply(_settings, payload, _document):
        nonlocal calls
        calls += 1
        assert payload["approvedCase"][field] == character * length

    monkeypatch.setattr(index_jobs, "_apply", apply)
    try:
        response = await client.post("/internal/v2/index-jobs", json=request)
        assert response.status_code == (202 if length == 4000 else 422)
        assert calls == (1 if length == 4000 else 0)
        if length == 4000:
            assert response.json()["state"] == "SUCCEEDED"
        else:
            async with await psycopg.AsyncConnection.connect(DSN) as connection:
                row = await (
                    await connection.execute(
                        "SELECT count(*) FROM ai_index_job WHERE job_id=%s", (request["jobId"],)
                    )
                ).fetchone()
                assert row[0] == 0
    finally:
        await cleanup(request)


@pytest.mark.skipif(not os.getenv("HOPS_INDEX_VECTOR_TEST_DSN"), reason="격리 pgvector DB 미설정")
async def test_real_pgvector_upsert_verifies_original_digest_and_avoids_reembedding():
    from hops_ai.services.indexing.pgvector import ApprovedDocumentVersion, PgVectorCaseIndex

    request = request_body("VECTOR")
    content = {key: request[key] for key in ("caseId", "caseVersion", "approvedCase")}
    digest = sha256(canonical(content))
    document_id, version_id = uuid4(), uuid4()
    calls = 0

    async def embed(_content):
        nonlocal calls
        calls += 1
        return [1.0, 0.0, 0.0]  # 저장 경계용 합성 벡터. 실제 모델 품질 검사가 아니다.

    async with await psycopg.AsyncConnection.connect(
        os.environ["HOPS_INDEX_VECTOR_TEST_DSN"], autocommit=True
    ) as connection:
        await connection.execute(
            "INSERT INTO knowledge_document(id,title,document_type,access_level,"
            "data_split,review_status) "
            "VALUES (%s,'가상 색인','CASE','STAFF','RAG','APPROVED')",
            (document_id,),
        )
        await connection.execute(
            "INSERT INTO knowledge_document_version(id,knowledge_document_id,version_no,content,"
            "content_digest,source_metadata,review_status) VALUES (%s,%s,1,%s,%s,'{}','APPROVED')",
            (version_id, document_id, canonical(content), digest),
        )
        try:
            store = PgVectorCaseIndex(connection, embed, 3)
            document = ApprovedDocumentVersion(version_id, UUID(request["caseId"]), 1, digest)
            await store.apply(request, document)
            await store.apply(request, document)
            assert calls == 1
            row = await (
                await connection.execute(
                    "SELECT content_digest,chunk_digest,vector_dims(embedding) "
                    "FROM hops_knowledge.knowledge_chunk WHERE knowledge_document_version_id=%s",
                    (version_id,),
                )
            ).fetchone()
            assert row == (digest, digest, 3)
            with pytest.raises(ValueError):
                await store.apply(
                    request, ApprovedDocumentVersion(version_id, UUID(request["caseId"]), 2, digest)
                )
        finally:
            await connection.execute(
                "DELETE FROM hops_knowledge.knowledge_chunk WHERE knowledge_document_version_id=%s",
                (version_id,),
            )
            await connection.execute(
                "DELETE FROM knowledge_document_version WHERE id=%s", (version_id,)
            )
            await connection.execute("DELETE FROM knowledge_document WHERE id=%s", (document_id,))


@pytest.mark.skipif(not os.getenv("HOPS_INDEX_NEO4J_TEST_URL"), reason="격리 Neo4j 미설정")
async def test_real_neo4j_preserves_approved_version_and_requires_existing_nodes():
    from hops_ai.services.indexing.neo4j import Neo4jCaseIndex

    request = request_body()
    system_id = request["approvedCase"]["systemId"]
    async with httpx.AsyncClient(
        base_url=os.environ["HOPS_INDEX_NEO4J_TEST_URL"],
        auth=("neo4j", "case170-fixture"),
        timeout=30,
    ) as client:

        async def query(statement, parameters):
            response = await client.post(
                "/db/neo4j/query/v2", json={"statement": statement, "parameters": parameters}
            )
            response.raise_for_status()
            assert not response.json().get("errors")
            return response.json()["data"]["values"]

        await query("CREATE (:System {id:$id}) RETURN 1", {"id": system_id})
        try:
            store = Neo4jCaseIndex(client)
            await store.apply(request)
            await store.apply(request)
            assert await query(
                "MATCH (c:Case {caseId:$id}) RETURN count(c)", {"id": request["caseId"]}
            ) == [[1]]
            request["approvedCase"]["entityLinks"] = [
                {"relation": "USES_CHECK", "targetId": "unregistered", "evidenceIds": []}
            ]
            request["contentDigest"] = sha256(
                canonical(
                    {
                        key: request[key]
                        for key in ("caseId", "caseVersion", "store", "approvedCase")
                    }
                )
            )
            with pytest.raises(ValueError):
                await store.apply(request)
        finally:
            await query(
                "MATCH (c:Case {caseId:$id}) DETACH DELETE c RETURN 1", {"id": request["caseId"]}
            )
            await query("MATCH (s:System {id:$id}) DETACH DELETE s RETURN 1", {"id": system_id})
