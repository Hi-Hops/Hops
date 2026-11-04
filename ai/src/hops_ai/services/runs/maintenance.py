"""RunLedger 주기 작업. 중단된 다른 인스턴스의 run 복구와 보존 기간이 지난 결과 상세 만료.

앱 수명 동안 백그라운드에서 돈다. 한 번 실패해도 경고만 남기고 다음 주기에 다시 시도하며,
작업 실패가 앱을 멈추거나 준비 상태를 바꾸지 않는다. 준비 상태는 소유 세션 확인으로만 판단한다.
"""

import asyncio
import logging
from collections.abc import Awaitable, Callable
from typing import Protocol

logger = logging.getLogger(__name__)

EXPIRY_BATCH_SIZE = 100
# 한 주기에 DB를 오래 붙잡지 않도록 만료 배치 반복 횟수를 제한한다. 남은 건은 다음 주기에 처리한다.
MAX_EXPIRY_BATCHES = 10


class MaintainedLedger(Protocol):
    async def recover_abandoned(self) -> int: ...

    async def expire_details(self, limit: int = ...) -> int: ...


class LedgerMaintenance:
    def __init__(
        self,
        ledger: MaintainedLedger,
        *,
        recovery_interval: float,
        expiry_interval: float,
    ) -> None:
        self._ledger = ledger
        self._recovery_interval = recovery_interval
        self._expiry_interval = expiry_interval
        self._tasks: list[asyncio.Task[None]] = []

    def start(self) -> None:
        if self._tasks:
            raise RuntimeError("RunLedger 주기 작업이 이미 시작됐습니다")
        # 기동 시 복구는 RunLedger.open이 이미 한 번 수행하므로 첫 실행은 한 주기 뒤다.
        self._tasks = [
            asyncio.create_task(
                self._every(self._recovery_interval, self.recover_once, "중단 실행 복구")
            ),
            asyncio.create_task(
                self._every(self._expiry_interval, self.expire_once, "결과 상세 만료")
            ),
        ]

    async def stop(self) -> None:
        tasks, self._tasks = self._tasks, []
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    async def recover_once(self) -> int:
        return await self._ledger.recover_abandoned()

    async def expire_once(self) -> int:
        expired = 0
        for _ in range(MAX_EXPIRY_BATCHES):
            count = await self._ledger.expire_details(EXPIRY_BATCH_SIZE)
            expired += count
            if count < EXPIRY_BATCH_SIZE:
                break
        return expired

    async def _every(self, interval: float, job: Callable[[], Awaitable[int]], name: str) -> None:
        while True:
            await asyncio.sleep(interval)
            try:
                count = await job()
            except Exception as exc:  # noqa: BLE001 - 주기 작업 실패는 기록만 하고 다음 주기에 재시도한다.
                logger.warning("RunLedger %s 작업이 실패했습니다: %s", name, type(exc).__name__)
                continue
            if count:
                logger.info("RunLedger %s: %d건", name, count)
