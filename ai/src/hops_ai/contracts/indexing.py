"""승인 사례 색인 작업의 전송 형식(contracts/agents S2 IndexApplyRequest·IndexApplySnapshot).

Spring이 승인 거래에서 만든 요청을 그대로 받는다. digest는 받은 JSON 원문으로 다시 계산하므로
이 모델은 형식 검증만 하고 직렬화에 쓰지 않는다(services.indexing.digest).
"""

from typing import Annotated, Literal

from pydantic import Field, StrictStr

from hops_ai.contracts.wire import (
    AccessScope,
    AiError,
    EntityLink,
    SystemVersion,
    Title,
    Version,
    WireModel,
    WireText,
    WireTime,
    WireUuid,
)

CONTRACT_VERSION = "hops-system-1"
IndexStore = Literal["VECTOR", "NEO4J"]
IndexState = Literal["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "UNKNOWN"]
Sha256 = Annotated[StrictStr, Field(pattern=r"^[0-9a-f]{64}$")]


class ApprovedCaseBody(WireModel):
    workRecordId: WireUuid
    resolutionRequestId: WireUuid
    approvedBy: WireUuid
    approvedAt: WireTime
    title: Title
    action: WireText
    verification: WireText
    applicability: WireText
    limitations: list[WireText]
    appliesToSystemVersion: SystemVersion | None
    accessScope: AccessScope
    origin: Literal["SYNTHETIC", "DEMO_VERIFIED", "OPERATIONAL"]
    confirmationOrigin: Literal["SYNTHETIC_PRESET", "HOPS_USER"]
    sourceMessageIds: list[WireUuid]
    entityLinks: list[EntityLink]
    systemId: WireUuid
    confirmedCause: WireText | None
    problemStatement: WireText
    caseType: Literal["RESOLUTION", "IMPLEMENTATION"]


class IndexApplyRequest(WireModel):
    contractVersion: Literal["hops-system-1"]
    jobId: WireUuid
    caseId: WireUuid
    caseVersion: Version
    store: IndexStore
    attempt: Version
    contentDigest: Sha256
    approvedCase: ApprovedCaseBody


class IndexApplySnapshot(WireModel):
    contractVersion: Literal["hops-system-1"]
    jobId: WireUuid
    caseId: WireUuid
    caseVersion: Version
    store: IndexStore
    attempt: Version
    state: IndexState
    contentDigest: Sha256
    error: AiError | None
    updatedAt: WireTime


class CaseIndexSource(WireModel):
    """Spring이 돌려준 승인 사례 색인 원본(GET /internal/v2/cases/{caseId}/index-source)."""

    contractVersion: Literal["hops-system-1"]
    caseId: WireUuid
    caseVersion: Version
    documentVersionId: WireUuid
    title: StrictStr
    applicability: StrictStr | None
    content: StrictStr
    contentDigest: Sha256
