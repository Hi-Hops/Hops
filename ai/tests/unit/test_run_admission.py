from datetime import UTC, datetime
from uuid import uuid4

import pytest
from pydantic import BaseModel

from hops_ai.persistence.run_ledger import input_digest
from hops_ai.services.runs.admission import AdmissionRejected, ExecutionLimits, RunAdmissionService


class InputLimits(BaseModel):
    maxModelCalls: int
    maxInputTokens: int
    maxOutputTokens: int
    maxToolCalls: int
    maxRetries: int


class InputRequest(BaseModel):
    runId: str
    expiresAt: str
    limits: InputLimits


class EmptyLedger:
    async def get(self, run_id):
        return None

    async def accept(self, run_id, payload):
        raise AssertionError("유효하지 않은 요청은 저장소에 접수하면 안 된다")


def test_digest_normalizes_object_order_but_preserves_array_order() -> None:
    assert input_digest({"a": "문의", "b": [1, 2]}) == input_digest({"b": [1, 2], "a": "문의"})
    assert input_digest({"a": "문의", "b": [1, 2]}) != input_digest({"a": "문의", "b": [2, 1]})
    with pytest.raises(ValueError):
        input_digest({"a": float("nan")})


@pytest.mark.parametrize(
    "expires_at,calls",
    [
        ("2026-11-01T00:00:00Z", 1),
        ("2026-11-02T01:00:00Z", 5),
        ("2026-11-02T01:00:00", 1),
    ],
)
async def test_invalid_new_requests_are_not_accepted(expires_at: str, calls: int) -> None:
    service = RunAdmissionService(
        EmptyLedger(),
        ExecutionLimits(4, 1000, 500, 3, 1),
        clock=lambda: datetime(2026, 11, 2, tzinfo=UTC),
    )
    request = InputRequest(
        runId=str(uuid4()),
        expiresAt=expires_at,
        limits=InputLimits(
            maxModelCalls=calls,
            maxInputTokens=1000,
            maxOutputTokens=500,
            maxToolCalls=3,
            maxRetries=1,
        ),
    )
    with pytest.raises(AdmissionRejected):
        await service.accept(request)
