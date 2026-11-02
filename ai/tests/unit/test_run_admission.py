from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from hops_ai.persistence.run_ledger import input_digest
from hops_ai.services.runs.admission import (
    AdmissionExpired,
    AdmissionRejected,
    ExecutionLimits,
    LimitsExceeded,
    RunAdmissionService,
)
from tests.doubles.run_requests import inquiry_request


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
    "expires_at,calls,reason",
    [
        ("2026-11-01T00:00:00Z", 1, AdmissionExpired),
        ("2026-11-02T01:00:00Z", 5, LimitsExceeded),
    ],
)
async def test_invalid_new_requests_are_not_accepted(
    expires_at: str, calls: int, reason: type[AdmissionRejected]
) -> None:
    service = RunAdmissionService(
        EmptyLedger(),
        ExecutionLimits(4, 1000, 500, 3, 1),
        clock=lambda: datetime(2026, 11, 2, tzinfo=UTC),
    )
    with pytest.raises(reason):
        await service.accept(inquiry_request(expires_at=expires_at, max_calls=calls))


async def test_mutated_nested_dto_is_validated_again_before_acceptance() -> None:
    service = RunAdmissionService(
        EmptyLedger(),
        ExecutionLimits(4, 1000, 500, 3, 1),
        clock=lambda: datetime(2026, 11, 2, tzinfo=UTC),
    )
    request = inquiry_request()
    request.accessContext.systemIds.append("invalid-uuid")
    with pytest.raises(ValidationError):
        await service.accept(request)
