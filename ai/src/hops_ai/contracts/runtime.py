"""하네스 내부 실패·사용량 자료형. HTTP·SSE 오류 스키마는 아직 확정하지 않았다."""

from typing import Literal

from hops_ai.contracts.types import ContractModel

ResultStatus = Literal["ERROR", "TIMED_OUT", "CANCELLED"]
AgentErrorCode = Literal[
    "INTERNAL_ERROR",
    "DEADLINE_EXCEEDED",
    "BUDGET_EXCEEDED",
    "SUPERSEDED",
    "ACCESS_DENIED",
    "INVALID_INPUT",
    "INVALID_OUTPUT",
    "MODEL_UNAVAILABLE",
    "MODEL_TIMEOUT",
    "RETRIEVAL_UNAVAILABLE",
]


class AgentError(ContractModel):
    code: AgentErrorCode
    message: str
    retryable: bool


class AgentMeta(ContractModel):
    durationMs: int
    modelId: str | None
    promptVersion: str | None
