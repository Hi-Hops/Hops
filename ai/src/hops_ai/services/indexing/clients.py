"""색인 작업이 부르는 외부 서비스: Spring 색인 원본 조회, OpenAI 임베딩.

실패는 IndexFailure로 알린다. 오류 본문은 신뢰할 수 없는 문자열이라 싣지 않는다.
"""

import math
from collections.abc import Sequence

import httpx
from pydantic import ValidationError

from hops_ai.contracts.indexing import CaseIndexSource

# Spring 검색이 질의를 임베딩하는 모델·차원과 같아야 한다(QueryEmbedding, V27).
EMBEDDING_MODEL = "text-embedding-3-small"
EMBEDDING_DIMENSIONS = 1536
_TIMEOUT = httpx.Timeout(15.0, connect=5.0)
_MAX_SOURCE_BYTES = 1_000_000


class IndexFailure(Exception):
    """반영을 끝내지 못했다. code는 AiError 코드, retryable은 수동 재시도로 나아질 수 있는지다."""

    def __init__(self, code: str, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable


class SpringCaseSource:
    """GET /internal/v2/cases/{caseId}/index-source. 고정 Spring origin과 서비스 토큰만 쓴다."""

    def __init__(
        self, base_url: str, token: str, *, transport: httpx.AsyncBaseTransport | None = None
    ) -> None:
        self._base_url = base_url
        self._token = token
        self._transport = transport

    async def get(self, case_id: str, case_version: int) -> CaseIndexSource:
        try:
            async with httpx.AsyncClient(
                base_url=self._base_url,
                transport=self._transport,
                follow_redirects=False,
                trust_env=False,
                timeout=_TIMEOUT,
                headers={"Authorization": "Bearer " + self._token},
            ) as client:
                response = await client.get(
                    f"/internal/v2/cases/{case_id}/index-source",
                    params={"caseVersion": case_version},
                )
        except httpx.TransportError:
            raise IndexFailure(
                "INDEX_UNAVAILABLE", "Spring 색인 원본을 읽지 못했다", retryable=True
            ) from None
        if response.status_code == 404:
            raise IndexFailure(
                "NOT_FOUND", "반영할 승인 사례 버전이 없거나 이미 반영이 끝났다", retryable=False
            )
        if response.status_code != 200 or len(response.content) > _MAX_SOURCE_BYTES:
            raise IndexFailure(
                "INDEX_UNAVAILABLE",
                f"Spring 색인 원본 조회가 HTTP {response.status_code}를 반환했다",
                retryable=True,
            )
        try:
            source = CaseIndexSource.model_validate_json(response.content)
        except ValidationError:
            raise IndexFailure(
                "UPSTREAM_PROTOCOL_ERROR", "Spring 색인 원본 형식이 올바르지 않다", retryable=False
            ) from None
        if source.caseId != case_id or source.caseVersion != case_version:
            raise IndexFailure(
                "UPSTREAM_PROTOCOL_ERROR", "다른 사례의 색인 원본을 받았다", retryable=False
            )
        return source


class OpenAIEmbeddings:
    """OpenAI 임베딩. 차원·값·순서를 검사하고 pgvector 문자열로 돌려준다."""

    def __init__(self, api_key: str, *, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._api_key = api_key
        self._transport = transport

    async def embed(self, inputs: Sequence[str]) -> list[str]:
        try:
            async with httpx.AsyncClient(
                transport=self._transport, trust_env=False, timeout=_TIMEOUT
            ) as client:
                response = await client.post(
                    "https://api.openai.com/v1/embeddings",
                    json={
                        "model": EMBEDDING_MODEL,
                        "dimensions": EMBEDDING_DIMENSIONS,
                        "input": list(inputs),
                    },
                    headers={"Authorization": "Bearer " + self._api_key},
                )
        except httpx.TransportError:
            raise IndexFailure(
                "INDEX_UNAVAILABLE", "임베딩 API에 연결하지 못했다", retryable=True
            ) from None
        if response.status_code != 200:
            raise IndexFailure(
                "INDEX_UNAVAILABLE",
                f"임베딩 API가 HTTP {response.status_code}를 반환했다",
                retryable=True,
            )
        try:
            payload = response.json()
            entries = sorted(payload["data"], key=lambda entry: entry["index"])
            model = payload["model"]
            vectors = [entry["embedding"] for entry in entries]
            indexes = [entry["index"] for entry in entries]
        except (ValueError, KeyError, TypeError):
            raise IndexFailure(
                "UPSTREAM_PROTOCOL_ERROR", "임베딩 응답 형식이 올바르지 않다", retryable=True
            ) from None
        if not str(model).startswith(EMBEDDING_MODEL) or indexes != list(range(len(inputs))):
            raise IndexFailure(
                "UPSTREAM_PROTOCOL_ERROR", "임베딩 모델 또는 응답 순서가 다르다", retryable=True
            )
        for vector in vectors:
            if (
                not isinstance(vector, list)
                or len(vector) != EMBEDDING_DIMENSIONS
                or not all(isinstance(x, int | float) and math.isfinite(x) for x in vector)
                or not any(vector)
            ):
                raise IndexFailure(
                    "UPSTREAM_PROTOCOL_ERROR", "임베딩 차원 또는 값이 올바르지 않다", retryable=True
                )
        return ["[" + ",".join(map(str, vector)) + "]" for vector in vectors]
