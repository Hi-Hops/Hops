"""승인 문서 버전에 묶인 CASE 청크를 pgvector에 한 번 저장한다."""

import math
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from typing import Any
from uuid import UUID

import httpx
from psycopg import AsyncConnection

from hops_ai.services.indexing.approved_case import canonical, sha256, verified_case_body

Embed = Callable[[str], Awaitable[Sequence[float]]]

_READ = """
SELECT content, content_digest, chunk_digest, vector_dims(embedding)
FROM hops_knowledge.knowledge_chunk
WHERE knowledge_document_version_id = %s AND chunk_no = 1
"""


@dataclass(frozen=True)
class ApprovedDocumentVersion:
    """Spring이 승인된 원본에서 확인한 내부 문서 버전 참조."""

    document_version_id: UUID
    case_id: UUID
    case_version: int
    content_digest: str


class OpenAICaseEmbedder:
    def __init__(self, client: httpx.AsyncClient, model: str, dimensions: int) -> None:
        self._client = client
        self._model = model
        self._dimensions = dimensions

    async def __call__(self, content: str) -> Sequence[float]:
        response = await self._client.post(
            "embeddings",
            json={
                "model": self._model,
                "input": content,
                "dimensions": self._dimensions,
                "encoding_format": "float",
            },
        )
        response.raise_for_status()
        body = response.json()
        data = body["data"]
        if body["model"] != self._model or len(data) != 1 or data[0]["index"] != 0:
            raise ValueError("승인 CASE 임베딩 응답이 요청과 일치하지 않습니다")
        return data[0]["embedding"]


class PgVectorCaseIndex:
    def __init__(self, connection: AsyncConnection, embed: Embed, dimensions: int) -> None:
        self._connection = connection
        self._embed = embed
        self._dimensions = dimensions

    async def apply(self, request: dict[str, Any], document: ApprovedDocumentVersion) -> None:
        approved = verified_case_body(request, "VECTOR")
        content = canonical(approved)
        digest = sha256(content)
        if (
            approved["caseId"] != str(document.case_id)
            or approved["caseVersion"] != document.case_version
            or digest != document.content_digest
        ):
            raise ValueError("승인 CASE와 저장할 문서 버전이 일치하지 않습니다")

        # EA-02의 원문 전체를 한 청크로 보존하므로 원문과 청크 해시는 같다.
        expected = (content, digest, digest, self._dimensions)
        params = (document.document_version_id,)
        async with self._connection.transaction():
            row = await (await self._connection.execute(_READ, params)).fetchone()
            if row is not None:
                self._verify(row, expected)
                return

        embedding = await self._embed(content)
        if (
            len(embedding) != self._dimensions
            or any(type(item) not in (int, float) or not math.isfinite(item) for item in embedding)
            or not any(embedding)
        ):
            raise ValueError("승인 CASE 임베딩의 차원 또는 값이 유효하지 않습니다")
        vector = "[" + ",".join(str(item) for item in embedding) + "]"
        async with self._connection.transaction():
            await self._connection.execute(
                """
                INSERT INTO hops_knowledge.knowledge_chunk (
                    knowledge_document_version_id, chunk_no, content, embedding,
                    content_digest, chunk_digest
                )
                VALUES (%s, 1, %s, %s::vector, %s, %s)
                ON CONFLICT (knowledge_document_version_id, chunk_no) DO NOTHING
                """,
                (document.document_version_id, content, vector, digest, digest),
            )
            row = await (await self._connection.execute(_READ, params)).fetchone()
            self._verify(row, expected)

    @staticmethod
    def _verify(row: tuple | None, expected: tuple) -> None:
        if row != expected:
            raise ValueError("저장된 청크가 승인 문서 버전과 일치하지 않습니다")
