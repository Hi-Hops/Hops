"""계약 타입이 공통으로 쓰는 기본 타입과 기반 모델.

원본은 specs/contracts/DATA_TYPES.md(서비스 간 계약)와 contracts/agents/README.md(agent-1)다.
모든 필드는 필수이고 nullable은 키를 생략하지 않고 null을 보낸다.
"""

from datetime import datetime
from typing import Annotated, Literal

from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictInt,
    StrictStr,
    StringConstraints,
)

MAX_INT = 2_147_483_647
UUID_PATTERN = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
ISO_DATETIME_PATTERN = r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$"


class ContractModel(BaseModel):
    """JSON 계약 모델. 정의하지 않은 필드는 거부한다.

    필드 이름은 JSON 키(camelCase)와 같게 써서 model_dump()가 곧 전송 본문이 되게 한다.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)


def _check_calendar(value: str) -> str:
    try:
        datetime.fromisoformat(value)
    except ValueError as error:
        raise ValueError("존재하지 않는 날짜·시각이다") from error
    return value


Text = StrictStr
NonEmptyText = Annotated[StrictStr, Field(min_length=1)]
Flag = StrictBool

# 소문자 하이픈 UUID 문자열
Uuid = Annotated[str, StringConstraints(strict=True, pattern=f"^{UUID_PATTERN}$")]
# 기존 저장 항목의 UUID 또는 아직 저장되지 않은 항목의 `new:` 임시 참조
Ref = Annotated[
    str,
    StringConstraints(strict=True, pattern=rf"^(?:{UUID_PATTERN}|new:[a-zA-Z0-9_-]+)$"),
]
# UTC 날짜·시각. YYYY-MM-DDTHH:mm:ss[.소수초 1~6자리]Z
IsoDateTime = Annotated[
    str,
    StringConstraints(strict=True, pattern=ISO_DATETIME_PATTERN),
    AfterValidator(_check_calendar),
]

# 버전·순번처럼 1부터 시작하는 정수
Version = Annotated[StrictInt, Field(ge=1, le=MAX_INT)]
# 0부터 시작하는 정수
Count = Annotated[StrictInt, Field(ge=0, le=MAX_INT)]
# 같은 문제에 대한 추가 질문 횟수. 제품 정책상 최대 3회다.
QuestionCount = Annotated[StrictInt, Field(ge=0, le=3)]

Category = Literal[
    "ACCOUNT_AUTH",
    "PERMISSION",
    "NETWORK_ACCESS",
    "SOFTWARE",
    "BUSINESS_SERVICE_ERROR",
    "OTHER",
]
EvidenceKind = Literal["MESSAGE", "DOCUMENT", "TOOL"]
TicketPurpose = Literal["INCIDENT", "CS_REVIEW"]


class Evidence(ContractModel):
    """검증된 근거. 원문 전체나 접근 토큰은 넣지 않는다.

    서비스 간 계약과 agent-1이 같은 모양을 쓴다. agent-1은 TOOL 근거의 조회 시각 규칙을 더한다.
    """

    id: NonEmptyText
    kind: EvidenceKind
    sourceId: NonEmptyText
    sourceVersion: NonEmptyText
    summary: Text
    observedAt: IsoDateTime | None


class TicketProposal(ContractModel):
    """티켓 제안. 서비스 간 계약과 agent-1이 같은 모양을 쓴다.

    모델 제안일 뿐이며 저장·배정 성공을 뜻하지 않는다.
    """

    ref: Ref
    problemRef: Ref
    workScope: Text
    title: Text
    description: Text
    category: Category
    departmentCode: NonEmptyText
    purpose: TicketPurpose
    diagnosisSummary: Text
    handoffReason: Text
    uncertainty: Text | None
    evidenceIds: list[Text]
