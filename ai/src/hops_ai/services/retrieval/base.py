"""검색 내부 인터페이스 초안. scope_ref는 모델이 아닌 검증된 서버 문맥에서 제공한다.

구현 시 검색 전 접근 필터와 반환 전 현재 권한·공개 상태 검사를 모두 수행한다.
사례는 동일 승인 버전의 양쪽 반영 성공을 확인해야 한다. 오류를 빈 검색 결과로 바꾸지 않는다.
"""

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

from hops_ai.contracts.evidence import Evidence


@dataclass(frozen=True)
class RetrievalQuery:
    text: str
    access_scope_ref: str
    top_k: int = 5


@dataclass(frozen=True)
class RetrievedChunk:
    chunk_id: str
    document_id: str
    document_version: str
    content: str
    evidence: Evidence
    score: float


class VectorRetriever(Protocol):
    async def search(self, query: RetrievalQuery) -> Sequence[RetrievedChunk]: ...


class GraphRetriever(Protocol):
    async def search(self, query: RetrievalQuery) -> Sequence[Evidence]:
        """근거 있는 관계를 반환한다. 연결 자체로 현재 원인을 확정하지 않는다."""
        ...
