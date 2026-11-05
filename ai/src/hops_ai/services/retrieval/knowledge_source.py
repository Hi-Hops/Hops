"""Knowledge 역할이 쓰는 지식 검색 자료의 경계.

이 경계는 접근 범위 선필터와 현재 권한·원문 digest 확인을 끝낸 근거만 돌려준다.
개발용 고정 데이터 구현은 이 확인을 AI 안에서 흉내 내는 대역이다. 실검색 구현은
후보를 찾고 Spring 권한 확인을 거쳐 같은 형태로 돌려준다. pgvector·Neo4j 같은 검색 방식은
이 경계 뒤에서 교체한다.

검색 0건은 빈 목록이다. 검색 시스템 오류는 RetrievalUnavailable을 발생시키며
빈 목록으로 바꾸지 않는다.
"""

import hashlib
import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Protocol

from hops_ai.contracts.roles import AuthorizedKnowledgeRef
from hops_ai.contracts.wire import AccessScope, KnowledgeEvidence

MAX_TOP_K = 5


@dataclass(frozen=True)
class KnowledgeQuery:
    """서버가 확정한 접근 범위와 검색어. 접근 범위를 모델 출력으로 만들지 않는다."""

    text: str
    # 이번 실행에서 열람할 수 있는 문서 공개 범위
    allowed_scopes: frozenset[AccessScope]
    # 이번 실행에서 접근할 수 있는 시스템 ID
    system_ids: frozenset[str]
    # 지원 시스템의 안정적인 코드 → ID. 코드로 자료를 구분하는 구현이 ID를 찾는 데 쓴다.
    system_ids_by_code: Mapping[str, str]
    top_k: int = MAX_TOP_K

    def __post_init__(self) -> None:
        if not self.text.strip():
            raise ValueError("검색어가 비어 있다")
        if not 1 <= self.top_k <= MAX_TOP_K:
            raise ValueError("검색 결과 수는 1~5건이어야 한다")

    def digest(self) -> str:
        """같은 검색인지 비교하는 지문. 검색어와 접근 범위·시스템·건수가 같으면 같다."""
        canonical = json.dumps(
            {
                "text": self.text,
                "scopes": sorted(self.allowed_scopes),
                "systems": sorted(self.system_ids),
                "topK": self.top_k,
            },
            ensure_ascii=False,
            sort_keys=True,
        )
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class KnowledgeHit:
    """권한과 digest 확인을 마친 근거 하나와 그 근거의 승인된 본문."""

    evidence: KnowledgeEvidence
    reference: AuthorizedKnowledgeRef
    # 근거가 가리키는 승인된 본문. 안내문 구성에는 이 본문만 사용한다.
    content: str
    score: float


class KnowledgeSource(Protocol):
    async def search(self, query: KnowledgeQuery) -> Sequence[KnowledgeHit]:
        """점수가 높은 순으로 최대 top_k건을 반환한다. 0건이면 빈 목록이다."""
        ...
