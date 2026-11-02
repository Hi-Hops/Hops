"""명시한 테스트 전용 PostgreSQL에서만 실행하며 실제 운영 DB를 수정하지 않는다."""

import asyncio
import os
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg.conninfo import conninfo_to_dict
from pydantic import BaseModel

from hops_ai.persistence.run_ledger import InvalidRunTransition, RunConflict, RunLedger
from hops_ai.services.runs.admission import AdmissionRejected, ExecutionLimits, RunAdmissionService


@pytest.fixture
async def ledger() -> RunLedger:
    conninfo = os.environ.get("HOPS_LEDGER_TEST_DSN")
    if not conninfo:
        pytest.skip("테스트 전용 HOPS_LEDGER_TEST_DSN이 설정되지 않았다")
    if conninfo_to_dict(conninfo).get("dbname") != "hops_ai_runtime_test":
        pytest.fail("RunLedger 통합 테스트는 hops_ai_runtime_test DB에서만 실행한다")
    migration = Path(__file__).parents[2] / "migrations" / "001_run_ledger.sql"
    async with await psycopg.AsyncConnection.connect(conninfo) as conn:
        await conn.execute(migration.read_text(encoding="utf-8"))
    return RunLedger(conninfo)


async def test_same_run_is_atomic_and_conflicting_input_is_rejected(ledger: RunLedger) -> None:
    run_id = uuid4()
    payload = {"message": "가상 문의", "items": [1, 2]}
    responses = await asyncio.gather(*(ledger.accept(run_id, payload) for _ in range(12)))
    assert sum(response.created for response in responses) == 1
    assert all(response.snapshot.status == "QUEUED" for response in responses)
    assert len({response.snapshot.input_digest for response in responses}) == 1
    assert not (await ledger.accept(run_id, {"items": [1, 2], "message": "가상 문의"})).created
    with pytest.raises(RunConflict):
        await ledger.accept(run_id, {"message": "다른 문의", "items": [1, 2]})
    assert sum(await asyncio.gather(*(ledger.start(run_id) for _ in range(8)))) == 1


async def test_final_result_is_immutable_and_unknown_blocks_late_result(ledger: RunLedger) -> None:
    run_id = uuid4()
    await ledger.accept(run_id, {"message": "가상 문의"})
    assert await ledger.start(run_id)
    result = {"runId": str(run_id), "status": "SUCCEEDED", "answer": "안내"}
    await ledger.finish(run_id, "SUCCEEDED", result, 2)
    await ledger.finish(run_id, "SUCCEEDED", result, 2)
    with pytest.raises(RunConflict):
        await ledger.finish(run_id, "SUCCEEDED", result | {"answer": "다른 안내"}, 2)
    snapshot = await ledger.get(run_id)
    assert snapshot is not None and snapshot.result == result and snapshot.last_seq == 2
    assert not await ledger.mark_unknown(run_id)

    interrupted = uuid4()
    await ledger.accept(interrupted, {"message": "중단된 가상 문의"})
    assert await ledger.mark_unknown(interrupted)
    assert not (await ledger.accept(interrupted, {"message": "중단된 가상 문의"})).created
    with pytest.raises(InvalidRunTransition):
        await ledger.finish(interrupted, "SUCCEEDED", result | {"runId": str(interrupted)}, 1)
    assert (await ledger.get(interrupted)).status == "UNKNOWN"


async def test_detail_expiry_retains_id_and_digest(ledger: RunLedger) -> None:
    run_id = uuid4()
    payload = {"message": "가상 문의"}
    await ledger.accept(run_id, payload)
    await ledger.start(run_id)
    result = {"runId": str(run_id), "status": "SUCCEEDED", "answer": "안내"}
    await ledger.finish(run_id, "SUCCEEDED", result, 1)
    assert not await ledger.expire_detail(run_id)
    async with await psycopg.AsyncConnection.connect(ledger._conninfo) as conn:
        await conn.execute(
            "UPDATE ai_run_ledger SET finished_at = CURRENT_TIMESTAMP - INTERVAL '25 hours' "
            "WHERE run_id = %s",
            (run_id,),
        )
    assert await ledger.expire_detail(run_id)
    accepted = await ledger.accept(run_id, payload)
    assert not accepted.created and accepted.snapshot.status == "UNKNOWN"
    assert accepted.snapshot.result is None
    with pytest.raises(RunConflict):
        await ledger.accept(run_id, {"message": "다른 가상 문의"})


async def test_sequence_is_atomic_and_unavailable_after_termination(ledger: RunLedger) -> None:
    run_id = uuid4()
    await ledger.accept(run_id, {"message": "가상 문의"})
    with pytest.raises(InvalidRunTransition):
        await ledger.next_sequence(run_id)
    result = {"runId": str(run_id), "status": "SUCCEEDED", "answer": "안내"}
    with pytest.raises(InvalidRunTransition):
        await ledger.finish(run_id, "SUCCEEDED", result, 0)
    await ledger.start(run_id)
    sequences = await asyncio.gather(*(ledger.next_sequence(run_id) for _ in range(8)))
    assert sorted(sequences) == list(range(1, 9))
    with pytest.raises(InvalidRunTransition):
        await ledger.finish(run_id, "SUCCEEDED", result, 7)
    await ledger.finish(run_id, "SUCCEEDED", result, 8)
    with pytest.raises(InvalidRunTransition):
        await ledger.next_sequence(run_id)


async def test_admission_checks_limits_expiry_and_reuses_existing(ledger: RunLedger) -> None:
    class Limits(BaseModel):
        maxModelCalls: int
        maxInputTokens: int
        maxOutputTokens: int
        maxToolCalls: int
        maxRetries: int

    class Request(BaseModel):
        runId: str
        expiresAt: str
        limits: Limits

    ceilings = ExecutionLimits(4, 1000, 500, 3, 1)
    service = RunAdmissionService(ledger, ceilings, clock=lambda: datetime(2026, 11, 2, tzinfo=UTC))
    request = Request(
        runId=str(uuid4()),
        expiresAt="2026-11-02T01:00:00Z",
        limits=Limits(
            maxModelCalls=4, maxInputTokens=1000, maxOutputTokens=500, maxToolCalls=3, maxRetries=1
        ),
    )
    assert (await service.accept(request)).created
    assert not (await service.accept(request)).created
    with pytest.raises(RunConflict):
        await service.accept(request.model_copy(update={"expiresAt": "2026-11-02T02:00:00Z"}))
    with pytest.raises(AdmissionRejected):
        await service.accept(
            request.model_copy(update={"runId": str(uuid4()), "expiresAt": "2026-11-01T00:00:00Z"})
        )
    invalid_limits = request.limits.model_copy(update={"maxModelCalls": 5})
    with pytest.raises(AdmissionRejected):
        await service.accept(
            request.model_copy(update={"runId": str(uuid4()), "limits": invalid_limits})
        )
