"""pgvector 검색 어댑터 자리. 임베딩·스키마·공개/권한 검증은 구현 전이다."""

from collections.abc import Sequence

from hops_ai.harness.errors import ComponentNotReady
from hops_ai.services.retrieval.base import RetrievalQuery, RetrievedChunk


class PgVectorRetriever:
    async def search(self, query: RetrievalQuery) -> Sequence[RetrievedChunk]:
        raise ComponentNotReady("pgvector retrieval")
