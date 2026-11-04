"""RunLedger 주기 작업의 반복·실패 격리·종료 정리. DB에 연결하지 않는다."""

import asyncio
import logging

import psycopg
import pytest

from hops_ai.services.runs.maintenance import (
    EXPIRY_BATCH_SIZE,
    MAX_EXPIRY_BATCHES,
    LedgerMaintenance,
)


class CountingLedger:
    def __init__(self, *, expiry_batches: list[int] | None = None) -> None:
        self.recover_calls = 0
        self.expire_limits: list[int] = []
        self.recover_failures = 0
        self._expiry_batches = list(expiry_batches or [])

    async def recover_abandoned(self) -> int:
        self.recover_calls += 1
        if self.recover_failures:
            self.recover_failures -= 1
            raise psycopg.OperationalError("가상 DB 일시 오류")
        return 0

    async def expire_details(self, limit: int = 100) -> int:
        self.expire_limits.append(limit)
        return self._expiry_batches.pop(0) if self._expiry_batches else 0


async def wait_until(condition, timeout: float = 1.0) -> None:
    async def poll() -> None:
        while not condition():
            await asyncio.sleep(0.005)

    await asyncio.wait_for(poll(), timeout)


def maintenance(ledger, *, recovery: float = 0.01, expiry: float = 0.01) -> LedgerMaintenance:
    return LedgerMaintenance(ledger, recovery_interval=recovery, expiry_interval=expiry)


async def test_jobs_repeat_on_their_interval_until_stopped() -> None:
    ledger = CountingLedger()
    jobs = maintenance(ledger)

    jobs.start()
    await wait_until(lambda: ledger.recover_calls >= 3 and len(ledger.expire_limits) >= 3)
    await jobs.stop()
    calls = ledger.recover_calls
    await asyncio.sleep(0.05)

    assert ledger.recover_calls == calls


async def test_first_run_waits_one_interval_because_startup_already_recovered() -> None:
    ledger = CountingLedger()
    jobs = maintenance(ledger, recovery=10, expiry=10)

    jobs.start()
    await asyncio.sleep(0.05)
    await jobs.stop()

    assert ledger.recover_calls == 0
    assert ledger.expire_limits == []


async def test_failure_is_logged_and_the_next_interval_retries(caplog) -> None:
    ledger = CountingLedger()
    ledger.recover_failures = 2
    jobs = maintenance(ledger, expiry=10)

    with caplog.at_level(logging.WARNING, logger="hops_ai.services.runs.maintenance"):
        jobs.start()
        await wait_until(lambda: ledger.recover_calls >= 3)
        await jobs.stop()

    warnings = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert len(warnings) == 2
    # 예외 메시지(DB 주소 등)는 남기지 않고 종류만 남긴다.
    assert all("OperationalError" in w and "가상 DB" not in w for w in warnings)


async def test_stop_cancels_a_job_in_progress() -> None:
    started = asyncio.Event()

    class HangingLedger(CountingLedger):
        async def recover_abandoned(self) -> int:
            started.set()
            await asyncio.sleep(30)
            return 0

    jobs = maintenance(HangingLedger(), expiry=10)
    jobs.start()
    await asyncio.wait_for(started.wait(), 1)

    await asyncio.wait_for(jobs.stop(), 1)


async def test_starting_twice_is_rejected() -> None:
    jobs = maintenance(CountingLedger(), recovery=10, expiry=10)
    jobs.start()
    try:
        with pytest.raises(RuntimeError):
            jobs.start()
    finally:
        await jobs.stop()


async def test_expiry_drains_full_batches_and_stops_at_a_short_one() -> None:
    ledger = CountingLedger(expiry_batches=[EXPIRY_BATCH_SIZE, EXPIRY_BATCH_SIZE, 7, 99])

    expired = await maintenance(ledger).expire_once()

    assert expired == 2 * EXPIRY_BATCH_SIZE + 7
    assert ledger.expire_limits == [EXPIRY_BATCH_SIZE] * 3


async def test_expiry_leaves_a_large_backlog_for_the_next_interval() -> None:
    ledger = CountingLedger(expiry_batches=[EXPIRY_BATCH_SIZE] * (MAX_EXPIRY_BATCHES + 5))

    expired = await maintenance(ledger).expire_once()

    assert expired == MAX_EXPIRY_BATCHES * EXPIRY_BATCH_SIZE
    assert len(ledger.expire_limits) == MAX_EXPIRY_BATCHES
