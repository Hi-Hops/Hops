"""승인 문서 버전에 묶인 CASE 청크를 pgvector에 한 번 저장한다."""

import math
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from typing import Any
from uuid import UUID

import httpx
from psycopg import AsyncConnection

from hops_ai.services.indexing.approved_case import canonical, sha256, verified_case_body
from hops_ai.services.indexing.chunks import case_chunks

EmbedMany = Callable[[Sequence[str]], Awaitable[Sequence[Sequence[float]]]]

_READ = """
SELECT chunk_no, content, content_digest, chunk_digest, vector_dims(embedding)
FROM hops_knowledge.knowledge_chunk
WHERE knowledge_document_version_id = %s ORDER BY chunk_no
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
        return (await self.many([content]))[0]

    async def many(self, contents: Sequence[str]) -> Sequence[Sequence[float]]:
        response = await self._client.post(
            "embeddings",
            json={
                "model": self._model,
                "input": list(contents),
                "dimensions": self._dimensions,
                "encoding_format": "float",
            },
        )
        response.raise_for_status()
        body = response.json()
        data = body["data"]
        if (
            body["model"] != self._model
            or len(data) != len(contents)
            or any(type(item["index"]) is not int for item in data)
            or {item["index"] for item in data} != set(range(len(contents)))
        ):
            raise ValueError("승인 CASE 임베딩 응답이 요청과 일치하지 않습니다")
        return [item["embedding"] for item in sorted(data, key=lambda item: item["index"])]


class PgVectorCaseIndex:
    def __init__(
        self,
        connection: AsyncConnection,
        embed: EmbedMany,
        dimensions: int,
        model: str,
        chunk_tokens: int = 2000,
    ) -> None:
        self._connection = connection
        self._embed = embed
        self._dimensions = dimensions
        self._model = model
        self._chunk_tokens = chunk_tokens

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

        params = (document.document_version_id,)
        async with self._connection.transaction():
            rows = await (await self._connection.execute(_READ, params)).fetchall()
            if rows:
                self._verify(rows, content, digest)
                return

        chunks = case_chunks(content, self._model, self._chunk_tokens)
        embeddings = []
        # 청크당 2000 이하·한 배치 32개 이하로 단일 입력과 요청 합계 상한을 함께 지킨다.
        for offset in range(0, len(chunks), 32):
            batch = chunks[offset : offset + 32]
            vectors = await self._embed(batch)
            if len(vectors) != len(batch):
                raise ValueError("청크와 임베딩 수가 일치하지 않습니다")
            for embedding in vectors:
                if (
                    len(embedding) != self._dimensions
                    or not any(embedding)
                    or any(
                        type(item) not in (int, float) or not math.isfinite(item)
                        for item in embedding
                    )
                ):
                    raise ValueError("승인 CASE 임베딩의 차원 또는 값이 유효하지 않습니다")
            embeddings.extend(vectors)
        async with self._connection.transaction():
            for number, (chunk, embedding) in enumerate(zip(chunks, embeddings, strict=True), 1):
                vector = "[" + ",".join(str(item) for item in embedding) + "]"
                await self._connection.execute(
                    """
                INSERT INTO hops_knowledge.knowledge_chunk (
                    knowledge_document_version_id, chunk_no, content, embedding,
                    content_digest, chunk_digest
                )
                VALUES (%s, %s, %s, %s::vector, %s, %s)
                ON CONFLICT (knowledge_document_version_id, chunk_no) DO NOTHING
                """,
                    (document.document_version_id, number, chunk, vector, digest, sha256(chunk)),
                )
            rows = await (await self._connection.execute(_READ, params)).fetchall()
            self._verify(rows, content, digest)

    def _verify(self, rows: Sequence[tuple], content: str, digest: str) -> None:
        if "".join(row[1] for row in rows) != content or any(
            row[0] != number
            or row[2] != digest
            or row[3] != sha256(row[1])
            or row[4] != self._dimensions
            for number, row in enumerate(rows, 1)
        ):
            raise ValueError("저장된 청크가 승인 문서 버전과 일치하지 않습니다")
