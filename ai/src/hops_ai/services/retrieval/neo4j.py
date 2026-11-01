"""Neo4j 관계 검색 어댑터 자리. 관계 스키마·권한·동일 승인 버전 검증은 구현 전이다."""

from collections.abc import Sequence

from hops_ai.contracts.evidence import Evidence
from hops_ai.harness.errors import ComponentNotReady
from hops_ai.services.retrieval.base import RetrievalQuery


class Neo4jRetriever:
    async def search(self, query: RetrievalQuery) -> Sequence[Evidence]:
        raise ComponentNotReady("Neo4j retrieval")
