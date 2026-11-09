"""저장소별 반영. 같은 승인 버전을 여러 번 반영해도 결과가 같다(upsert).

- VECTOR: 승인 사례 검색 문서 버전의 본문을 조각 1개로 임베딩해 hops_knowledge.knowledge_chunk에
  넣는다. 조각 규칙은 scripts/knowledge/load_search_demo.py와 같다(사례는 본문 전체가 한 조각,
  임베딩 입력은 제목·적용 조건·본문, content_digest는 문서 버전 digest,
  chunk_digest는 본문 SHA-256).
- NEO4J: ApprovedCase 노드와 승인 사례의 기존 노드 연결(ADDRESSES·USES_CHECK·APPLIES_TO)을 같은 문서
  버전 키로 넣는다. 연결 대상은 이미 승인된 기존 노드여야 하고 새 노드를 만들지 않는다.

반영 뒤 원격 저장소를 다시 읽어 확인한 경우에만 성공이다(contracts/agents I1).
"""

import hashlib
from collections.abc import Sequence
from typing import Any, Protocol
from uuid import UUID

import httpx
import psycopg
from psycopg.rows import dict_row

from hops_ai.contracts.indexing import CaseIndexSource, IndexApplyRequest
from hops_ai.services.indexing.clients import EMBEDDING_MODEL, IndexFailure

# Neo4j에는 업무 DB의 접근 수준 값을 그대로 둔다(relation_* 테이블과 같은 값).
_ACCESS_LEVEL = {"EMPLOYEE": "EMPLOYEE", "SYSTEM_STAFF": "STAFF"}
# 연결 종류별 대상 라벨. APPLIES_TO의 대상 System은 검수 상태를 갖지 않는다.
_TARGET_LABEL = {"ADDRESSES": "Symptom", "USES_CHECK": "CheckItem", "APPLIES_TO": "System"}
_TIMEOUT = httpx.Timeout(15.0, connect=5.0)


class Embeddings(Protocol):
    async def embed(self, inputs: Sequence[str]) -> list[str]: ...


def embedding_input(source: CaseIndexSource) -> str:
    return f"{source.title}\n적용 조건: {source.applicability or ''}\n\n{source.content}"


class VectorStore:
    def __init__(self, conninfo: str, embeddings: Embeddings) -> None:
        self._conninfo = conninfo
        self._embeddings = embeddings

    async def apply(self, request: IndexApplyRequest, source: CaseIndexSource) -> None:
        content = source.content.strip()
        if not content:
            raise IndexFailure("UPSTREAM_PROTOCOL_ERROR", "사례 본문이 비어 있다", retryable=False)
        [vector] = await self._embeddings.embed([embedding_input(source)])
        chunk_digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
        version_id = UUID(source.documentVersionId)
        try:
            async with await psycopg.AsyncConnection.connect(
                self._conninfo, row_factory=dict_row
            ) as conn:
                async with conn.transaction():
                    await conn.execute(
                        """
                        INSERT INTO hops_knowledge.knowledge_chunk
                          (knowledge_document_version_id, chunk_no, content, embedding,
                           content_digest, chunk_digest, embedding_model)
                        VALUES (%s, 1, %s, %s::vector, %s, %s, %s)
                        ON CONFLICT (knowledge_document_version_id, chunk_no) DO UPDATE
                          SET content = excluded.content, embedding = excluded.embedding,
                              content_digest = excluded.content_digest,
                              chunk_digest = excluded.chunk_digest,
                              embedding_model = excluded.embedding_model
                        """,
                        (
                            version_id,
                            content,
                            vector,
                            source.contentDigest,
                            chunk_digest,
                            EMBEDDING_MODEL,
                        ),
                    )
                    await conn.execute(
                        """
                        DELETE FROM hops_knowledge.knowledge_chunk
                        WHERE knowledge_document_version_id = %s AND chunk_no > 1
                        """,
                        (version_id,),
                    )
                cursor = await conn.execute(
                    """
                    SELECT chunk_no, content_digest, chunk_digest, embedding_model,
                           vector_dims(embedding) AS dims
                    FROM hops_knowledge.knowledge_chunk WHERE knowledge_document_version_id = %s
                    """,
                    (version_id,),
                )
                rows = await cursor.fetchall()
        except psycopg.Error:
            raise IndexFailure(
                "INDEX_UNAVAILABLE", "검색 조각 저장소에 반영하지 못했다", retryable=True
            ) from None
        expected = [
            {
                "chunk_no": 1,
                "content_digest": source.contentDigest,
                "chunk_digest": chunk_digest,
                "embedding_model": EMBEDDING_MODEL,
                "dims": 1536,
            }
        ]
        if [dict(row) for row in rows] != expected:
            raise IndexFailure(
                "INDEX_UNAVAILABLE", "반영 뒤 검색 조각을 확인하지 못했다", retryable=True
            )


class GraphStore:
    """Neo4j HTTP 트랜잭션 API(/db/{database}/tx/commit). Spring 관계 조회와 같은 방식이다."""

    def __init__(
        self,
        url: str,
        username: str,
        password: str,
        database: str = "neo4j",
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._endpoint = f"{url.rstrip('/')}/db/{database}/tx/commit"
        self._auth = (username, password)
        self._transport = transport

    async def _commit(self, statements: list[dict[str, Any]]) -> list[dict[str, Any]]:
        try:
            async with httpx.AsyncClient(
                transport=self._transport, trust_env=False, timeout=_TIMEOUT
            ) as client:
                response = await client.post(
                    self._endpoint, json={"statements": statements}, auth=self._auth
                )
        except httpx.TransportError:
            raise IndexFailure(
                "INDEX_UNAVAILABLE", "Neo4j에 연결하지 못했다", retryable=True
            ) from None
        if response.status_code != 200:
            raise IndexFailure(
                "INDEX_UNAVAILABLE",
                f"Neo4j가 HTTP {response.status_code}를 반환했다",
                retryable=True,
            )
        try:
            body = response.json()
            errors = body["errors"]
            results = body["results"]
        except (ValueError, KeyError, TypeError):
            raise IndexFailure(
                "UPSTREAM_PROTOCOL_ERROR", "Neo4j 응답 형식이 올바르지 않다", retryable=True
            ) from None
        if errors:
            raise IndexFailure("INDEX_UNAVAILABLE", "Neo4j 트랜잭션이 실패했다", retryable=True)
        return results

    async def apply(self, request: IndexApplyRequest, source: CaseIndexSource) -> None:
        case = request.approvedCase
        level = _ACCESS_LEVEL[case.accessScope]
        targets: dict[str, list[str]] = {relation: [] for relation in _TARGET_LABEL}
        for link in case.entityLinks:
            if link.targetId not in targets[link.relation]:
                targets[link.relation].append(link.targetId)
        # 연결 대상이 이미 승인된 기존 노드인지 먼저 확인한다.
        # 없는 대상으로 새 노드를 만들지 않는다.
        lookups = [
            {
                "statement": (
                    f"MATCH (t:HopsP0:{label}) WHERE t.id IN $ids"
                    + ("" if label == "System" else " AND t.review_status = 'APPROVED'")
                    + " RETURN t.id"
                ),
                "parameters": {"ids": targets[relation]},
            }
            for relation, label in _TARGET_LABEL.items()
        ]
        found = await self._commit(lookups)
        for (relation, _), result in zip(_TARGET_LABEL.items(), found, strict=True):
            present = {row["row"][0] for row in result.get("data", [])}
            if set(targets[relation]) - present:
                raise IndexFailure(
                    "INDEX_UNAVAILABLE",
                    f"승인 사례의 {relation} 연결 대상이 그래프에 없다",
                    retryable=False,
                )
        params = {
            "caseId": request.caseId,
            "version": request.caseVersion,
            "systemId": case.systemId,
            "level": level,
            "documentVersionId": source.documentVersionId,
        }
        statements: list[dict[str, Any]] = [
            {
                "statement": """
                    MERGE (c:HopsP0:ApprovedCase {id: $caseId})
                    SET c.version_no = $version, c.it_system_id = $systemId,
                        c.access_level = $level, c.review_status = 'APPROVED',
                        c.source_document_version_id = $documentVersionId
                    WITH c
                    OPTIONAL MATCH (c)-[old:ADDRESSES|USES_CHECK|APPLIES_TO]->()
                    DELETE old
                """,
                "parameters": params,
            }
        ]
        for relation, label in _TARGET_LABEL.items():
            if not targets[relation]:
                continue
            statements.append(
                {
                    "statement": f"""
                        MATCH (c:HopsP0:ApprovedCase {{id: $caseId}})
                        UNWIND $ids AS target
                        MATCH (t:HopsP0:{label} {{id: target}})
                        MERGE (c)-[r:{relation}]->(t)
                        SET r.review_status = 'APPROVED', r.access_level = $level,
                            r.source_document_version_id = $documentVersionId
                    """,
                    "parameters": {**params, "ids": targets[relation]},
                }
            )
        await self._commit(statements)
        [check] = await self._commit(
            [
                {
                    "statement": """
                        MATCH (c:HopsP0:ApprovedCase {id: $caseId})
                        WHERE c.version_no = $version
                          AND c.source_document_version_id = $documentVersionId
                          AND c.review_status = 'APPROVED'
                        OPTIONAL MATCH (c)-[r:ADDRESSES|USES_CHECK|APPLIES_TO]->()
                        WHERE r.source_document_version_id = $documentVersionId
                        RETURN count(DISTINCT c), count(r)
                    """,
                    "parameters": params,
                }
            ]
        )
        expected = [1, sum(len(ids) for ids in targets.values())]
        rows = [row["row"] for row in check.get("data", [])]
        if rows != [expected]:
            raise IndexFailure(
                "INDEX_UNAVAILABLE", "반영 뒤 그래프를 확인하지 못했다", retryable=True
            )
