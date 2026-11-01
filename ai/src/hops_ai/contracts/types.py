"""내부 기술 자료형. model_dump 결과를 외부 전송 계약으로 취급하지 않는다."""

from datetime import datetime
from typing import Annotated

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, StrictInt, StrictStr


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


def _check_utc(value: str) -> str:
    if not value.endswith("Z"):
        raise ValueError("UTC 시각은 Z로 끝나야 한다")
    datetime.fromisoformat(value)
    return value


NonEmptyText = Annotated[StrictStr, Field(min_length=1)]
IsoDateTime = Annotated[StrictStr, AfterValidator(_check_utc)]

# 독립 문제마다 문의자에게 보낸 질문 묶음 수. 합산·저장은 서버 계약에서 검증한다.
QUESTION_LIMIT = 5
QuestionCount = Annotated[StrictInt, Field(ge=0, le=QUESTION_LIMIT)]
