"""도구·검색 내부 근거. 승인·권한 검증이나 Spring 전송 DTO를 대신하지 않는다."""

from typing import Literal

from pydantic import model_validator

from hops_ai.contracts.types import ContractModel, IsoDateTime, NonEmptyText


class Evidence(ContractModel):
    id: NonEmptyText
    kind: Literal["MESSAGE", "DOCUMENT", "TOOL"]
    sourceId: NonEmptyText
    sourceVersion: NonEmptyText
    summary: str
    observedAt: IsoDateTime | None
    origin: Literal["MOCK", "DEMO", "OPERATIONAL", "REFERENCE"]

    @model_validator(mode="after")
    def _tool_has_observation_time(self) -> "Evidence":
        if self.kind == "TOOL" and self.observedAt is None:
            raise ValueError("TOOL 근거에는 조회 시각이 필요하다")
        return self
