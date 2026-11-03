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
    QuestionProposal,
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


class InquiryResult(ContractModel):
    """문의 분석에서 제안한 문제와 질문 묶음."""

    invocationId: WireText
    problems: list[ProblemProposal]
    questions: list[QuestionProposal]


class KnowledgeResult(ContractModel):
    """검색 결과와 선택 가능한 안내. 내부 자료를 공개 안내로 자동 변환하지 않는다."""

    invocationId: WireText
    search: SearchResult
    guide: GuideProposal | None


class TicketResult(ContractModel):
    """문제별 티켓 제안 목록."""

    invocationId: WireText
    proposals: list[TicketProposal]


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
