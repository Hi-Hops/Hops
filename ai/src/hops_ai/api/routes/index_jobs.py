"""승인 CASE 색인 요청을 AI 런타임 원장과 저장소 어댑터에 연결한다."""

from datetime import UTC, datetime
from typing import Annotated, Literal
from uuid import UUID

import httpx
import psycopg
from fastapi import APIRouter, Header, Query
from psycopg.types.json import Jsonb
from pydantic import Field, StrictInt

from hops_ai.api.errors import ApiFault
from hops_ai.contracts.wire import (
    AiError,
    EntityLink,
    WireContent,
    WireModel,
    WireText,
    WireTime,
    WireUuid,
)
from hops_ai.services.indexing.approved_case import canonical, sha256, verified_case_body
from hops_ai.services.indexing.neo4j import Neo4jCaseIndex
from hops_ai.services.indexing.pgvector import (
    ApprovedDocumentVersion,
    OpenAICaseEmbedder,
    PgVectorCaseIndex,
)
from hops_ai.settings import Settings


class ApprovedCase(WireModel):
    workRecordId: WireUuid
    resolutionRequestId: WireUuid
    approvedBy: WireUuid
    approvedAt: WireTime
    title: Annotated[WireText, Field(max_length=200)]
    action: WireContent
    verification: WireContent
    applicability: WireContent
    limitations: list[WireText] = Field(max_length=100)
    appliesToSystemVersion: WireText | None
    accessScope: Literal["EMPLOYEE", "SYSTEM_STAFF"]
    origin: Literal["SYNTHETIC", "DEMO_VERIFIED", "OPERATIONAL"]
    confirmationOrigin: Literal["SYNTHETIC_PRESET", "HOPS_USER"]
    sourceMessageIds: list[WireUuid] = Field(max_length=100)
    entityLinks: list[EntityLink] = Field(max_length=100)
    systemId: WireUuid
    confirmedCause: WireContent | None
    problemStatement: WireContent
    caseType: Literal["RESOLUTION", "IMPLEMENTATION"]


class IndexRequest(WireModel):
    contractVersion: Literal["hops-system-1"]
    jobId: WireUuid
    caseId: WireUuid
    caseVersion: Annotated[StrictInt, Field(gt=0)]
    store: Literal["VECTOR", "NEO4J"]
    attempt: Annotated[StrictInt, Field(gt=0)]
    contentDigest: Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
    approvedCase: ApprovedCase


def _fault(status: int, code, message: str) -> ApiFault:
    return ApiFault(status, AiError(code=code, message=message, retryable=status == 503))


def _snapshot(request: IndexRequest, state: str, error: AiError | None = None) -> dict:
    return {
        **request.model_dump(exclude={"approvedCase"}),
        "state": state,
        "error": error.model_dump(mode="json") if error else None,
        "updatedAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
    }


async def _apply(settings: Settings, request: dict, document: ApprovedDocumentVersion | None):
    if request["store"] == "NEO4J":
        async with httpx.AsyncClient(
            base_url=settings.index_neo4j_url,
            auth=("neo4j", settings.index_neo4j_password.get_secret_value()),
            timeout=15,
        ) as client:
            await Neo4jCaseIndex(client).apply(request)
        return
    async with (
        await psycopg.AsyncConnection.connect(
            settings.index_vector_dsn.get_secret_value(), autocommit=True
        ) as connection,
        httpx.AsyncClient(
            base_url="https://api.openai.com/v1/",
            headers={"Authorization": "Bearer " + settings.openai_api_key.get_secret_value()},
            timeout=20,
        ) as client,
    ):
        await PgVectorCaseIndex(
            connection,
            OpenAICaseEmbedder(
                client, settings.index_embedding_model, settings.index_embedding_dimensions
            ),
            settings.index_embedding_dimensions,
        ).apply(request, document)


def index_router(settings: Settings) -> APIRouter:
    router = APIRouter(prefix="/internal/v2/index-jobs")
    dsn = settings.runtime_dsn.get_secret_value()

    @router.post("", status_code=202)
    async def apply(
        request: IndexRequest,
        document_id: Annotated[UUID | None, Header(alias="X-Hops-Document-Version")] = None,
        document_digest: Annotated[str | None, Header(alias="X-Hops-Document-Digest")] = None,
    ) -> dict:
        payload = request.model_dump(mode="json")
        try:
            body = verified_case_body(payload, request.store)
        except (KeyError, TypeError, ValueError):
            raise _fault(409, "CONFLICT", "승인 요청의 본문 해시가 일치하지 않습니다") from None
        document = None
        if request.store == "VECTOR":
            if document_id is None or document_digest != sha256(canonical(body)):
                raise _fault(422, "INVALID_REQUEST", "승인 문서 버전과 해시가 필요합니다")
            document = ApprovedDocumentVersion(
                document_id, UUID(request.caseId), request.caseVersion, document_digest
            )
        elif document_id is not None or document_digest is not None:
            raise _fault(422, "INVALID_REQUEST", "Neo4j 요청은 문서 버전 참조를 사용하지 않습니다")

        key = (UUID(request.jobId), request.attempt)
        try:
            async with await psycopg.AsyncConnection.connect(dsn, autocommit=True) as connection:
                # 먼저 접수 상태를 확정해 다른 연결에서 조회할 수 있게 한다.
                await connection.execute(
                    """
                    INSERT INTO ai_index_job
                        (job_id, attempt, request, document_version_id, snapshot)
                    VALUES (%s, %s, %s, %s, %s) ON CONFLICT DO NOTHING
                    """,
                    (*key, Jsonb(payload), document_id, Jsonb(_snapshot(request, "PENDING"))),
                )
                read = """
                    SELECT request, document_version_id, snapshot FROM ai_index_job
                    WHERE job_id = %s AND attempt = %s
                """
                stored = await (await connection.execute(read, key)).fetchone()
                if stored[:2] != (payload, document_id):
                    raise _fault(409, "CONFLICT", "같은 색인 작업에 다른 요청이 저장돼 있습니다")
                if stored[2]["state"] != "PENDING":
                    return stored[2]

                # RUNNING을 먼저 commit해 프로세스 종료 후 같은 키의 재실행을 막는다.
                claimed = await (
                    await connection.execute(
                        "UPDATE ai_index_job SET snapshot=%s WHERE job_id=%s AND attempt=%s "
                        "AND snapshot->>'state'='PENDING' RETURNING snapshot",
                        (Jsonb(_snapshot(request, "RUNNING")), *key),
                    )
                ).fetchone()
                if claimed is None:
                    return (await (await connection.execute(read, key)).fetchone())[2]
                try:
                    await _apply(settings, payload, document)
                    snapshot = _snapshot(request, "SUCCEEDED")
                except (httpx.HTTPError, psycopg.Error):
                    snapshot = _snapshot(
                        request,
                        "UNKNOWN",
                        AiError(
                            code="INDEX_UNAVAILABLE",
                            message="저장소 반영 결과를 확인할 수 없습니다",
                            retryable=True,
                        ),
                    )
                except (KeyError, TypeError, ValueError):
                    snapshot = _snapshot(
                        request,
                        "FAILED",
                        AiError(
                            code="INVALID_OUTPUT",
                            message="승인 요청 또는 저장 결과가 유효하지 않습니다",
                            retryable=False,
                        ),
                    )
                await connection.execute(
                    "UPDATE ai_index_job SET snapshot = %s WHERE job_id = %s AND attempt = %s",
                    (Jsonb(snapshot), *key),
                )
                return snapshot
        except psycopg.Error:
            raise _fault(503, "INDEX_UNAVAILABLE", "색인 작업 원장에 접근할 수 없습니다") from None

    @router.get("/{job_id}")
    async def get(job_id: UUID, attempt: Annotated[int, Query(gt=0)]) -> dict:
        try:
            async with await psycopg.AsyncConnection.connect(dsn) as connection:
                row = await (
                    await connection.execute(
                        "SELECT snapshot FROM ai_index_job WHERE job_id = %s AND attempt = %s",
                        (job_id, attempt),
                    )
                ).fetchone()
        except psycopg.Error:
            raise _fault(503, "INDEX_UNAVAILABLE", "색인 작업 원장에 접근할 수 없습니다") from None
        if row is None:
            raise _fault(404, "NOT_FOUND", "색인 작업을 찾을 수 없습니다")
        return row[0]

    return router
