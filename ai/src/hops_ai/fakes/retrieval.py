"""가짜 검색. 메모리에 둔 가상 문서에서 단어가 겹치는 정도로 순위를 매긴다. 품질 검증용이 아니다.

실제 검색과 같이 접근 범위 필터를 순위를 매기기 전에 적용한다.
"""

from collections.abc import Sequence
from dataclasses import replace

from hops_ai.services.retrieval.base import RetrievalQuery, RetrievedChunk


def sample_chunks() -> list[RetrievedChunk]:
    """접근 범위가 서로 다른 가상 문서 3개. 내용은 실제 가이드·런북이 아니다."""
    return [
        RetrievedChunk(
            chunk_id="chunk-fake-guide-vpn-1",
            artifact_id="FAKE-GUIDE-VPN",
            artifact_version="v1",
            title="VPN 접속 오류 직원 안내",
            content="VPN 접속 오류가 나면 네트워크 연결과 계정 상태부터 확인합니다. (가상 데이터)",
            access_scope="EMPLOYEE",
            exposure_policy="QUOTABLE",
            score=0.0,
        ),
        RetrievedChunk(
            chunk_id="chunk-fake-runbook-vpn-1",
            artifact_id="FAKE-RUNBOOK-VPN",
            artifact_version="v1",
            title="VPN 연결 오류 점검 절차 담당자용 VPN 게이트웨이 VPN 인증 로그",
            content="VPN 게이트웨이 상태와 인증 로그를 비교합니다. (가상 데이터)",
            access_scope="STAFF",
            exposure_policy="INTERNAL_ONLY",
            score=0.0,
        ),
        RetrievedChunk(
            chunk_id="chunk-fake-risky-1",
            artifact_id="FAKE-RISKY",
            artifact_version="v1",
            title="위험 절차 VPN 인증 설정 삭제",
            content="VPN 인증 설정을 삭제하는 절차. 어느 모드에서도 검색 금지. (가상 데이터)",
            access_scope="STAFF",
            exposure_policy="NEVER_EXPOSE",
            score=0.0,
        ),
    ]


class FakeRetriever:
    def __init__(
        self,
        chunks: Sequence[RetrievedChunk] | None = None,
        *,
        fail_with: BaseException | None = None,
    ) -> None:
        self._chunks = list(sample_chunks() if chunks is None else chunks)
        self._fail_with = fail_with
        self.queries: list[RetrievalQuery] = []

    async def search(self, query: RetrievalQuery) -> list[RetrievedChunk]:
        self.queries.append(query)
        if self._fail_with is not None:
            raise self._fail_with
        # 접근 범위 필터를 순위보다 먼저 적용한다
        allowed = [
            chunk
            for chunk in self._chunks
            if chunk.access_scope in query.filter.access_scopes
            and chunk.exposure_policy in query.filter.exposure_policies
        ]
        words = query.text.lower().split()
        scored = []
        for chunk in allowed:
            haystack = f"{chunk.title} {chunk.content}".lower()
            score = float(sum(word in haystack for word in words))
            if score > 0:
                scored.append(replace(chunk, score=score))
        scored.sort(key=lambda chunk: (-chunk.score, chunk.chunk_id))
        return scored[: query.top_k]
