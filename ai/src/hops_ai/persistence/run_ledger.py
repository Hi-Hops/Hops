"""AI run의 영속 접수와 최종 결과. 사용자 입력은 해시만 보존한다."""

import hashlib
import json
from dataclasses import dataclass
from typing import Any, Literal
from uuid import UUID

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

RunStatus = Literal[
    "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "TIMED_OUT", "SUPERSEDED", "UNKNOWN"
]
TerminalStatus = Literal["SUCCEEDED", "FAILED", "TIMED_OUT", "SUPERSEDED"]


class RunConflict(ValueError):
    """이미 사용한 runId가 다른 입력 또는 결과와 함께 전달됐다."""


class InvalidRunTransition(ValueError):
    """현재 상태에서 허용하지 않는 실행 상태 변경이다."""


@dataclass(frozen=True)
class RunSnapshot:
    run_id: UUID
    input_digest: str
    status: RunStatus
    last_seq: int
    result: dict[str, Any] | None


@dataclass(frozen=True)
class RunAcceptance:
    created: bool
    snapshot: RunSnapshot


def input_digest(payload: dict[str, Any]) -> str:
    """키 순서는 정규화하고 배열 순서는 유지해 동일 JSON 입력을 비교한다.

    직렬화 형식은 AI 내부 비교용이다. 모델 입력 또는 원문을 로그에 남기지 않는다.
    """
    encoded = json.dumps(
        payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


class RunLedger:
    def __init__(self, conninfo: str) -> None:
        self._conninfo = conninfo

    async def accept(self, run_id: UUID, payload: dict[str, Any]) -> RunAcceptance:
        digest = input_digest(payload)
        async with await psycopg.AsyncConnection.connect(
            self._conninfo, row_factory=dict_row
        ) as conn:
            # 유일 제약이 동시에 도착한 같은 runId의 최초 접수를 직렬화한다.
            cursor = await conn.execute(
                """INSERT INTO ai_run_ledger (run_id, input_digest, status)
                   VALUES (%s, %s, 'QUEUED') ON CONFLICT (run_id) DO NOTHING
                   RETURNING run_id, input_digest, status, last_seq, result""",
                (run_id, digest),
            )
            row = await cursor.fetchone()
            created = row is not None
            if row is None:
                cursor = await conn.execute(
                    """SELECT run_id, input_digest, status, last_seq, result
                       FROM ai_run_ledger WHERE run_id = %s""",
                    (run_id,),
                )
                row = await cursor.fetchone()
                if row is None:
                    raise InvalidRunTransition("접수 기록을 확인할 수 없습니다")
                if row["input_digest"] != digest:
                    raise RunConflict("동일 runId의 입력이 다릅니다")
            return RunAcceptance(created=created, snapshot=RunSnapshot(**row))

    async def get(self, run_id: UUID) -> RunSnapshot | None:
        async with await psycopg.AsyncConnection.connect(
            self._conninfo, row_factory=dict_row
        ) as conn:
            cursor = await conn.execute(
                """SELECT run_id, input_digest, status, last_seq, result
                   FROM ai_run_ledger WHERE run_id = %s""",
                (run_id,),
            )
            row = await cursor.fetchone()
            return RunSnapshot(**row) if row is not None else None

    async def start(self, run_id: UUID) -> bool:
        """최초 실행자만 QUEUED를 RUNNING으로 전환한다."""
        async with await psycopg.AsyncConnection.connect(self._conninfo) as conn:
            cursor = await conn.execute(
                """UPDATE ai_run_ledger SET status = 'RUNNING', updated_at = CURRENT_TIMESTAMP
                   WHERE run_id = %s AND status = 'QUEUED' RETURNING run_id""",
                (run_id,),
            )
            return await cursor.fetchone() is not None

    async def next_sequence(self, run_id: UUID) -> int:
        """실행 중에만 이벤트 순번을 원자적으로 발급한다."""
        async with await psycopg.AsyncConnection.connect(self._conninfo) as conn:
            cursor = await conn.execute(
                """UPDATE ai_run_ledger SET last_seq = last_seq + 1,
                   updated_at = CURRENT_TIMESTAMP
                   WHERE run_id = %s AND status = 'RUNNING' AND last_seq < 2147483647
                   RETURNING last_seq""",
                (run_id,),
            )
            row = await cursor.fetchone()
            if row is None:
                raise InvalidRunTransition("진행 중인 실행의 순번을 발급할 수 없습니다")
            return row[0]

    async def finish(
        self, run_id: UUID, status: TerminalStatus, result: dict[str, Any], last_seq: int
    ) -> None:
        """검증된 최종 결과를 한 번 고정한다. 종료 후 다른 결과는 거부한다."""
        if status not in ("SUCCEEDED", "FAILED", "TIMED_OUT", "SUPERSEDED"):
            raise ValueError("허용되지 않은 종료 상태입니다")
        if isinstance(last_seq, bool) or not 0 <= last_seq <= 2_147_483_647:
            raise ValueError("이벤트 순번이 올바르지 않습니다")
        if result.get("status") != status or result.get("runId") != str(run_id):
            raise ValueError("최종 결과의 실행 ID와 상태가 일치하지 않습니다")
        async with await psycopg.AsyncConnection.connect(
            self._conninfo, row_factory=dict_row
        ) as conn:
            cursor = await conn.execute(
                """SELECT status, last_seq, result FROM ai_run_ledger
                   WHERE run_id = %s FOR UPDATE""",
                (run_id,),
            )
            row = await cursor.fetchone()
            if row is None:
                raise InvalidRunTransition("존재하지 않는 실행입니다")
            if row["status"] in ("SUCCEEDED", "FAILED", "TIMED_OUT", "SUPERSEDED"):
                if (
                    row["status"] == status
                    and row["result"] == result
                    and row["last_seq"] == last_seq
                ):
                    return
                raise RunConflict("이미 확정된 실행의 결과가 다릅니다")
            if (
                row["status"] not in ("QUEUED", "RUNNING")
                or (status == "SUCCEEDED" and row["status"] != "RUNNING")
                or last_seq < row["last_seq"]
            ):
                raise InvalidRunTransition("종료할 수 없는 실행 상태 또는 순번입니다")
            await conn.execute(
                """UPDATE ai_run_ledger SET status = %s, result = %s, last_seq = %s,
                   updated_at = CURRENT_TIMESTAMP, finished_at = CURRENT_TIMESTAMP
                   WHERE run_id = %s""",
                (status, Jsonb(result), last_seq, run_id),
            )

    async def mark_unknown(self, run_id: UUID) -> bool:
        """실행 소유자가 종료돼 복원할 수 없다고 확인된 run만 미확인으로 전환한다."""
        async with await psycopg.AsyncConnection.connect(self._conninfo) as conn:
            cursor = await conn.execute(
                """UPDATE ai_run_ledger SET status = 'UNKNOWN', updated_at = CURRENT_TIMESTAMP
                   WHERE run_id = %s AND status IN ('QUEUED', 'RUNNING') RETURNING run_id""",
                (run_id,),
            )
            return await cursor.fetchone() is not None

    async def expire_detail(self, run_id: UUID) -> bool:
        """최종 결과를 최소 24시간 보존한 뒤 ID·digest 표식은 유지한다."""
        async with await psycopg.AsyncConnection.connect(self._conninfo) as conn:
            cursor = await conn.execute(
                """UPDATE ai_run_ledger
                   SET status = 'UNKNOWN', result = NULL, finished_at = NULL,
                       updated_at = CURRENT_TIMESTAMP
                   WHERE run_id = %s AND finished_at <= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                   RETURNING run_id""",
                (run_id,),
            )
            return await cursor.fetchone() is not None
