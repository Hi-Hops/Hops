"""증상 → 확인 항목 → 직원용 질문 문구 → 승인 사례 이웃을 읽는 관계 자료의 경계(RelationSource).

1차 구현은 Spring 내부 API(SQL)다. Neo4j는 같은 인터페이스 뒤로 2차에 교체한다.
연결은 후보일 뿐이며 현재 문의의 원인을 확정하지 않는다. 조회 0건은 빈 목록이고
조회 오류는 RetrievalUnavailable이다. 빈 목록으로 바꾸지 않는다.

이 경계는 접근 범위가 확정된 항목만 돌려준다. 직원 상담의 기본 프로필에서는 직원 공개로 표시된
확인 항목과 승인된 직원용 질문 문구만 있고 원인의 이름·담당자 전용 항목은 없다.
내부 판단 경계(옵션)가 켜진 실행의 결과(internal_judgement=True)에는
담당자 전용 항목이 있을 수 있으며 사용자 응답·질문에 쓰지 않는다.
이 값을 평가에 쓰는 일은 상담 평가 기능이 정한다.
"""

from dataclasses import dataclass
from typing import Protocol

from pydantic import BaseModel, ConfigDict, StrictBool

from hops_ai.contracts.wire import AccessScope
from hops_ai.services.retrieval.knowledge_source import RunLink

MAX_SYMPTOMS = 5


@dataclass(frozen=True)
class RelationQuery:
    """키워드·기능 후보·시스템으로 관계를 찾는 질의. 접근 범위는 서버가 정한다(모델 출력 아님)."""

    keywords: tuple[str, ...]
    feature_ids: frozenset[str]
    system_ids: frozenset[str]
    # 이번 실행에서 열람할 수 있는 공개 범위. 응답이 이 범위를 넘는지 확인하는 데 쓴다.
    allowed_scopes: frozenset[AccessScope]
    limit: int = MAX_SYMPTOMS
    run: RunLink | None = None

    def __post_init__(self) -> None:
        if not self.keywords and not self.feature_ids:
            raise ValueError("키워드나 기능 후보가 하나는 있어야 한다")
        if not 1 <= self.limit <= MAX_SYMPTOMS:
            raise ValueError("증상 수는 1~5건이어야 한다")


class _Item(BaseModel):
    # Spring이 가산 필드를 더해도 받는다. 필요한 필드는 아래에서 검증한다.
    model_config = ConfigDict(extra="ignore", frozen=True)


class Basis(_Item):
    visible: bool
    documentId: str | None = None
    documentVersion: int | None = None
    documentKey: str | None = None


class Question(_Item):
    id: str
    catalogKey: str
    text: str
    basis: Basis
    # 직원 전용 응답에서는 문구 자체가 이미 승인·공개 조건을 통과했다.
    employeeDisclosable: StrictBool | None = None


class CheckItem(_Item):
    id: str
    catalogKey: str
    # 담당자 범위 응답에만 있다. 직원 응답에는 없다.
    label: str | None = None
    employeeDisclosable: StrictBool
    accessScope: AccessScope
    basis: Basis
    questions: list[Question]


class Cause(_Item):
    id: str
    catalogKey: str
    label: str
    accessScope: AccessScope
    basis: Basis
    checkItemKeys: list[str]


class CaseNeighbor(_Item):
    documentKey: str
    caseIndexStatus: dict[str, str] | None = None


class Symptom(_Item):
    id: str
    catalogKey: str
    label: str
    systemId: str
    featureId: str
    featureCode: str
    accessScope: AccessScope
    basis: Basis
    score: float
    checkItems: list[CheckItem]
    # 직원 응답에는 없다(None). 담당자 범위 응답에만 있다.
    causes: list[Cause] | None = None
    cases: list[CaseNeighbor] = []


@dataclass(frozen=True)
class RelationResult:
    """employee_view는 모든 질문 문구가 직원 공개 조건을 통과한 응답임을 뜻한다."""

    symptoms: tuple[Symptom, ...]
    internal_judgement: bool
    employee_view: bool = False


class RelationSource(Protocol):
    async def search(self, query: RelationQuery) -> RelationResult:
        """점수가 높은 순으로 최대 limit건의 증상과 이웃을 돌려준다. 0건이면 증상이 빈 결과다."""
        ...
