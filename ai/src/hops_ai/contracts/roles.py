"""역할별 내부 결과. invocationId는 모델이 아니라 실행기가 발급한다."""

from typing import Annotated, Literal

from pydantic import Field, StrictBool, model_validator

from hops_ai.contracts.types import ContractModel
from hops_ai.contracts.wire import (
    AccessScope,
    CaseProposal,
    GuideProposal,
    KnowledgeEvidence,
    ProblemProposal,
    ProblemRef,
    QuestionProposal,
    TicketExecution,
    TicketProposal,
    Version,
    WireModel,
    WireText,
    WireUuid,
)


class FieldError(ContractModel):
    """검색 오류의 필드 참조."""

    field: WireText
    code: WireText


class SearchError(ContractModel):
    """DATA_TYPES의 ApiError 형태. 원시 검색 예외 대신 안전한 값만 담는다."""

    code: WireText
    message: WireText
    requestId: WireUuid
    retryable: StrictBool
    fieldErrors: list[FieldError]


class SearchResult(ContractModel):
    """검색 성공·0건·오류를 서로 다른 상태로 보존한다."""

    state: Literal["FOUND", "EMPTY", "ERROR"]
    evidence: list[KnowledgeEvidence]
    error: SearchError | None

    @model_validator(mode="after")
    def check_search(self) -> "SearchResult":
        """검색 상태와 근거·오류 조합이 일치하는지 확인한다."""
        if self.state == "FOUND" and (not self.evidence or self.error is not None):
            raise ValueError("FOUND는 근거 필수·오류 없음이다")
        if self.state == "EMPTY" and (self.evidence or self.error is not None):
            raise ValueError("EMPTY는 근거와 오류가 없다")
        if self.state == "ERROR" and (self.error is None or self.evidence):
            raise ValueError("ERROR는 오류 필수·근거 없음이다")
        return self


class ProblemAnalysis(ContractModel):
    """문제 하나의 검색 힌트. 사실이 아니며 사용자 응답·질문·티켓에 쓰지 않는다.

    keywords: 검색에 쓸 핵심어. 사용자 메시지에 실제로 있는 구절이다.
    featureCandidates: 관련 기능 ID. 문제의 시스템이 확정됐을 때 그 시스템의 기능만이다.
    개수·길이·출처 규칙은 rules.analysis가 에이전트 정리와 병합 검증에 함께 쓴다.
    """

    problemRef: ProblemRef
    keywords: list[WireText]
    featureCandidates: list[WireUuid]


class InquiryResult(ContractModel):
    """문의 분석에서 제안한 문제와 질문 묶음, 문제별 검색 힌트."""

    invocationId: WireText
    problems: list[ProblemProposal]
    questions: list[QuestionProposal]
    analysis: list[ProblemAnalysis] = Field(default_factory=list)
    # 질문 행동에서 문장을 누가 썼는지. 평가가 모델 문장과 대체 문구를 구분하려고 남기는 기록이며
    # 병합과 최종 결과에는 쓰지 않는다. 질문 행동이 아니면 비어 있다.
    questionSource: Literal["FIXED", "MODEL", "FIXED_FALLBACK"] | None = None
    # FIXED_FALLBACK일 때의 사유 코드(BUDGET, MODEL_ERROR, INVALID:<규칙>). 출력 내용은 담지 않는다.
    questionFallback: str | None = None


class MissingInformation(ContractModel):
    """판정에 더 필요한 정보. 닫힌 어휘다: 양식 필드 이름 또는 관계 확인 항목 ID만 담는다."""

    kind: Literal["FIELD", "CHECK_ITEM"]
    ref: WireText


class CandidateCause(ContractModel):
    """근거에 적힌 가능 원인. 현재 문의의 원인으로 확정하지 않는다(confirmed는 항상 거짓)."""

    text: WireText
    evidenceIds: list[WireUuid]
    confirmed: bool


class NextCheck(ContractModel):
    """다음에 확인할 관계 확인 항목."""

    checkItemId: WireText


class Assessment(ContractModel):
    """한 문제에 대한 검색 근거의 충분성 판정. 모델의 제안이며 내부 전용이다.

    직원 응답·질문·티켓에 그대로 쓰지 않는다. rationale은 사용자에게 보이지 않는다.
    개수·출처·공개 범위 규칙은 rules.assessment가 에이전트와 병합 단계에서 함께 검증한다.
    """

    problemRef: ProblemRef
    verdict: Literal["SUFFICIENT", "PARTIAL", "INSUFFICIENT", "NO_MATCH"]
    supportingEvidenceIds: list[WireUuid]
    missingInformation: list[MissingInformation]
    candidateCauses: list[CandidateCause]
    nextChecks: list[NextCheck]
    rationale: WireText


class RelationQuestion(ContractModel):
    """확인 항목을 묻는 직원용 승인 질문 문구."""

    id: WireText
    text: WireText


class RelationCheck(ContractModel):
    """증상의 원인을 가르는 확인 항목. 직원 공개로 승인된 것만 담는다(원인 이름은 없다)."""

    id: WireText
    questions: list[RelationQuestion]


class RelationSymptom(ContractModel):
    id: WireText
    label: WireText
    checkItems: list[RelationCheck]


class RelationNeighbors(ContractModel):
    """한 문제의 관계 조회 결과. 연결은 후보일 뿐 현재 문의의 원인을 확정하지 않는다.

    state는 검색과 같은 규칙이다: 0건(EMPTY)과 조회 오류(ERROR)를 구분하며 오류를 0건으로 바꾸지
    않는다. 직원 상담에서는 직원 공개로 승인된 확인 항목과 보이는 질문 문구만 담는다.
    """

    state: Literal["FOUND", "EMPTY", "ERROR"]
    symptoms: list[RelationSymptom]


class KnowledgeResult(ContractModel):
    """검색 결과와 선택 가능한 안내. 내부 자료를 공개 안내로 자동 변환하지 않는다."""

    invocationId: WireText
    search: SearchResult
    guide: GuideProposal | None
    # 검색 근거의 충분성 판정. 평가를 하지 않았거나 실패·거부됐으면 None이다(평가 없음).
    # 평가 없음이 안내·티켓 경로를 막지 않는다. problemRef가 있는 결과에만 붙는다.
    assessment: Assessment | None = None
    # 이 검색이 다룬 문제. 비어 있으면 어느 문제의 결과인지 알 수 없어 문제별 판단(안내를 만들지
    # 못한 뒤의 담당자 전달 등)에 쓰지 않는다. 검색한 문제에만 그 결과가 적용된다.
    problemRef: ProblemRef | None = None
    # 이 검색에 쓴 질의의 지문. 같은 질의의 반복 검색을 피하는 데 쓰며, 비어 있으면 같은 문제를
    # 다시 검색하지 않는다. 질의가 바뀌었을 때(새 사실이 더해진 뒤)만 다시 검색할 수 있다.
    queryDigest: str | None = None
    # 안내 뒤 미해결 응답으로 이미 안내한 문서를 결과에서 뺐는데, 검색이 상한(top-k)을 가득
    # 채워 돌려줘서 그 문서들이 자리를 차지했을 수 있다. 참이면 "다른 근거가 없다"고 단정할 수
    # 없다(순위 밖의 다른 문서를 보지 못했을 수 있다). 제외한 것이 없거나 상한을 채우지 않았으면
    # 거짓이다.
    crowdedOut: bool = False
    # 이 검색과 함께 조회한 관계 데이터(확인 항목). 관계를 조회하지 않은 호출은 None이다.
    # problemRef가 있는 결과에만 붙는다.
    relations: RelationNeighbors | None = None


class TicketResult(ContractModel):
    """문제별 티켓 제안 목록."""

    invocationId: WireText
    proposals: list[TicketProposal]
    creationResults: list[TicketExecution] = Field(default_factory=list)
    # 모델이 실제 원문에서 인용한 책임 근거. 생성 어댑터의 구간 조립에만 사용한다.
    messageQuotes: dict[str, list[dict[str, str]]] = Field(default_factory=dict)


class CuratorResult(ContractModel):
    """처리 기록 하나에 대한 사례 초안 또는 재사용할 사례 없음."""

    invocationId: WireText
    proposal: CaseProposal


type RoleResult = InquiryResult | KnowledgeResult | TicketResult | CuratorResult


class AuthorizedKnowledgeRef(WireModel):
    """검색 어댑터가 현재 권한과 본문 digest를 확인한 문서 버전 참조.

    이 참조는 모델 출력이 아니라 신뢰된 어댑터가 Supervisor 병합 함수에 전달한다.
    공통 wire.py에 아직 없는 검색 권한 확인 결과의 내부 전달에만 사용한다.
    """

    documentId: WireUuid
    documentVersion: Version
    kind: Literal["GUIDE", "POLICY", "RUNBOOK", "CASE"]
    accessScope: AccessScope
    contentDigest: Annotated[WireText, Field(pattern=r"^[0-9a-f]{64}$")]
