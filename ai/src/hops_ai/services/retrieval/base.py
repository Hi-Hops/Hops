"""지식 검색 인터페이스. 검색 전략(Hybrid·반복·GraphRAG)은 구현체가 정한다.

접근 범위 필터는 검색 **전에** 적용한다. 순위를 매긴 뒤에 거르면 허용되지 않은 문서가 상위를
차지해 결과가 비거나 허용 문서가 밀려난다. 필터는 Spring이 검증한 문맥에서 만들며 모델 출력에서
만들지 않는다.
"""

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Literal, Protocol, runtime_checkable

from hops_ai.contracts.agent_1 import KnowledgeMode

AccessScope = Literal["EMPLOYEE", "STAFF"]
ExposurePolicy = Literal["QUOTABLE", "INTERNAL_ONLY", "NEVER_EXPOSE"]


@dataclass(frozen=True)
class RetrievalFilter:
    access_scopes: frozenset[AccessScope]
    exposure_policies: frozenset[ExposurePolicy]

    @classmethod
    def for_mode(cls, mode: KnowledgeMode) -> "RetrievalFilter":
        """실행 모드별 기본 범위. NEVER_EXPOSE는 어느 모드에서도 검색하지 않는다.

        - ANSWER: 직원에게 보여 줄 답변이라 직원용 문서 중 인용 가능한 것만
        - SUPPORT_DIAGNOSIS: 담당자 진단 근거라 STAFF 문서와 내부 전용 문서까지.
          STAFF 범위는 Spring이 사용자 역할을 검증한 경우에만 호출한다.
        """
        if mode == "ANSWER":
            return cls(frozenset({"EMPLOYEE"}), frozenset({"QUOTABLE"}))
        return cls(
            frozenset({"EMPLOYEE", "STAFF"}),
            frozenset({"QUOTABLE", "INTERNAL_ONLY"}),
        )


@dataclass(frozen=True)
class RetrievalQuery:
    text: str
    filter: RetrievalFilter
    top_k: int = 5


@dataclass(frozen=True)
class RetrievedChunk:
    chunk_id: str
    artifact_id: str  # 문서 ID
    artifact_version: str
    title: str
    content: str
    access_scope: AccessScope
    exposure_policy: ExposurePolicy
    score: float


@runtime_checkable
class Retriever(Protocol):
    async def search(self, query: RetrievalQuery) -> Sequence[RetrievedChunk]:
        """점수가 높은 순서로 돌려준다. 결과가 없으면 빈 목록이며 오류가 아니다.

        검색 시스템 장애는 RetrievalUnavailable로 알려 '결과 없음'과 구분한다.
        """
        ...
