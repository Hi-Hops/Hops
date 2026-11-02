"""RunLedger와 같은 상태 전이 규칙을 메모리에서 흉내 내는 대역. DB에 연결하지 않는다."""

from dataclasses import dataclass, field
from typing import Any
from uuid import UUID

import psycopg

from hops_ai.persistence.run_ledger import (
    InvalidRunTransition,
    RunAcceptance,
    RunConflict,
    RunSnapshot,
    input_digest,
)

TERMINAL = ("SUCCEEDED", "FAILED", "TIMED_OUT", "SUPERSEDED")
SETTLED = (*TERMINAL, "UNKNOWN")


@dataclass
class _Row:
    digest: str
    status: str = "QUEUED"
    last_seq: int = 0
    result: dict[str, Any] | None = None


@dataclass
class MemoryLedger:
    rows: dict[UUID, _Row] = field(default_factory=dict)
    accept_failure: Exception | None = None
    # 남은 횟수만큼 DB 일시 오류를 낸다. 저장 재시도·UNKNOWN 전환 검사용이다.
    start_failures: int = 0
    finish_failures: int = 0
    finish_calls: int = 0
    opened: bool = False

    def _maybe_fail(self, name: str) -> None:
        remaining = getattr(self, name)
        if remaining > 0:
            setattr(self, name, remaining - 1)
            raise psycopg.OperationalError("가상 DB 일시 오류")

    async def __aenter__(self) -> "MemoryLedger":
        self.opened = True
        return self

    async def __aexit__(self, *args: object) -> None:
        self.opened = False

    def _snapshot(self, run_id: UUID) -> RunSnapshot:
        row = self.rows[run_id]
        return RunSnapshot(run_id, row.digest, row.status, row.last_seq, row.result)

    async def get(self, run_id: UUID) -> RunSnapshot | None:
        return self._snapshot(run_id) if run_id in self.rows else None

    async def accept(self, run_id: UUID, payload: dict[str, Any]) -> RunAcceptance:
        if self.accept_failure is not None:
            raise self.accept_failure
        digest = input_digest(payload)
        if run_id in self.rows:
            if self.rows[run_id].digest != digest:
                raise RunConflict("동일 runId의 입력이 다릅니다")
            return RunAcceptance(created=False, snapshot=self._snapshot(run_id))
        self.rows[run_id] = _Row(digest=digest)
        return RunAcceptance(created=True, snapshot=self._snapshot(run_id))

    async def start(self, run_id: UUID) -> bool:
        self._maybe_fail("start_failures")
        row = self.rows.get(run_id)
        if row is None or row.status != "QUEUED":
            return False
        row.status = "RUNNING"
        return True

    async def finish(
        self, run_id: UUID, status: str, result: dict[str, Any], last_seq: int
    ) -> None:
        self.finish_calls += 1
        self._maybe_fail("finish_failures")
        row = self.rows.get(run_id)
        if row is None or row.status in TERMINAL or last_seq < row.last_seq:
            raise InvalidRunTransition("종료할 수 없는 실행 상태 또는 순번입니다")
        if status == "SUCCEEDED" and row.status != "RUNNING":
            raise InvalidRunTransition("시작하지 않은 실행은 성공으로 끝낼 수 없습니다")
        if result.get("status") != status or result.get("runId") != str(run_id):
            raise ValueError("최종 결과의 실행 ID와 상태가 일치하지 않습니다")
        row.status, row.result, row.last_seq = status, result, last_seq

    async def abandon(self, run_id: UUID) -> bool:
        row = self.rows.get(run_id)
        if row is None or row.status not in ("QUEUED", "RUNNING"):
            return False
        row.status = "UNKNOWN"
        return True
