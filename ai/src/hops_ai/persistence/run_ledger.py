"""AI run 영속 접수와 소유자 생존 확인. 사용자 입력은 해시만 보존한다."""

import asyncio
import hashlib
import json
from dataclasses import dataclass
from typing import Any, Literal
from uuid import UUID, uuid4

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
    """객체 키 순서를 정규화하고 배열 순서를 유지한다. 원문은 보존하지 않는다."""
    encoded = json.dumps(
        payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _owner_lock_key(owner_id: UUID) -> int:
    # 충돌은 살아 있는 소유자로 간주해 복구를 보류하는 방향으로만 작동한다.
    digest = hashlib.sha256(b"hops-ai-run-owner:" + owner_id.bytes).digest()
    return int.from_bytes(digest[:8], byteorder="big", signed=True)


class RunLedger:
    """한 인스턴스는 한 소유자 세션을 유지하며 여러 run을 관리한다.

    세션 잠금과 접수·실행 변경은 같은 연결을 쓴다. 연결이 끊기면 잠금이
    풀리고 이전 소유자로 새 run을 접수하거나 늦은 결과를 저장할 수 없다.
    """

    def __init__(self, conninfo: str) -> None:
        self._conninfo = conninfo
        self._owner_id: UUID | None = None
        self._owner_conn: psycopg.AsyncConnection | None = None
        self._owner_mutex = asyncio.Lock()

    async def open(self) -> None:
        """호출자가 애플리케이션 수명에 맞춰 세션을 열고 종료해야 한다."""
        async with self._owner_mutex:
            if self._owner_conn is not None:
                raise InvalidRunTransition("실행 소유자 세션이 이미 열려 있습니다")
            owner_id = uuid4()
            conn = await psycopg.AsyncConnection.connect(
                self._conninfo, autocommit=True, row_factory=dict_row
            )
            try:
                cursor = await conn.execute(
                    "SELECT pg_try_advisory_lock(%s) AS acquired", (_owner_lock_key(owner_id),)
                )
                row = await cursor.fetchone()
                if row is None or not row["acquired"]:
                    raise InvalidRunTransition("실행 소유자 잠금을 확보하지 못했습니다")
            except BaseException:
                await conn.close()
                raise
            self._owner_id = owner_id
            self._owner_conn = conn
        try:
            await self.recover_abandoned()
        except BaseException:
            await self.close()
            raise

    async def close(self) -> None:
        async with self._owner_mutex:
            conn = self._owner_conn
            self._owner_conn = None
            self._owner_id = None
            if conn is not None:
                await conn.close()

    async def __aenter__(self) -> "RunLedger":
        await self.open()
        return self

    async def __aexit__(self, *args: object) -> None:
        await self.close()

    def _owner(self) -> tuple[psycopg.AsyncConnection, UUID]:
        if self._owner_conn is None or self._owner_conn.closed or self._owner_id is None:
            raise InvalidRunTransition("유효한 실행 소유자 세션이 없습니다")
        return self._owner_conn, self._owner_id

    async def accept(self, run_id: UUID, payload: dict[str, Any]) -> RunAcceptance:
        digest = input_digest(payload)
        async with self._owner_mutex:
            conn, owner_id = self._owner()
            async with conn.transaction():
                # 접수와 소유권 잠금은 같은 세션에 속한다.
                cursor = await conn.execute(
                    """INSERT INTO ai_run_ledger (run_id, input_digest, status, owner_id)
                       VALUES (%s, %s, 'QUEUED', %s) ON CONFLICT (run_id) DO NOTHING
                       RETURNING run_id, input_digest, status, last_seq, result""",
                    (run_id, digest, owner_id),
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
        async with self._owner_mutex:
            conn, owner_id = self._owner()
            cursor = await conn.execute(
                """UPDATE ai_run_ledger SET status = 'RUNNING', updated_at = CURRENT_TIMESTAMP
                   WHERE run_id = %s AND owner_id = %s AND status = 'QUEUED' RETURNING run_id""",
                (run_id, owner_id),
            )
            return await cursor.fetchone() is not None

    async def next_sequence(self, run_id: UUID) -> int:
        async with self._owner_mutex:
            conn, owner_id = self._owner()
            cursor = await conn.execute(
                """UPDATE ai_run_ledger SET last_seq = last_seq + 1,
                   updated_at = CURRENT_TIMESTAMP
                   WHERE run_id = %s AND owner_id = %s AND status = 'RUNNING'
                     AND last_seq < 2147483647 RETURNING last_seq""",
                (run_id, owner_id),
            )
            row = await cursor.fetchone()
            if row is None:
                raise InvalidRunTransition("진행 중인 실행의 순번을 발급할 수 없습니다")
            return row["last_seq"]

    async def finish(
        self, run_id: UUID, status: TerminalStatus, result: dict[str, Any], last_seq: int
    ) -> None:
        """검증된 최종 결과를 한 번 고정하고, 다른 소유자·늦은 결과는 거부한다."""
        if status not in ("SUCCEEDED", "FAILED", "TIMED_OUT", "SUPERSEDED"):
            raise ValueError("허용되지 않은 종료 상태입니다")
        if isinstance(last_seq, bool) or not 0 <= last_seq <= 2_147_483_647:
            raise ValueError("이벤트 순번이 올바르지 않습니다")
        if result.get("status") != status or result.get("runId") != str(run_id):
            raise ValueError("최종 결과의 실행 ID와 상태가 일치하지 않습니다")
        async with self._owner_mutex:
            conn, owner_id = self._owner()
            async with conn.transaction():
                cursor = await conn.execute(
                    """SELECT status, last_seq, result, owner_id FROM ai_run_ledger
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
                    row["owner_id"] != owner_id
                    or row["status"] not in ("QUEUED", "RUNNING")
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

    async def abandon(self, run_id: UUID) -> bool:
        """이 소유자가 끝내지 못한 run을 UNKNOWN으로 넘긴다. 결과를 추정해 저장하지 않는다.

        최종 결과 저장이 계속 실패할 때 run이 진행 상태로 방치되지 않게 한다.
        UNKNOWN인 run은 다시 실행하지 않으며 재분석은 Spring이 새 runId로 요청한다.
        """
        async with self._owner_mutex:
            conn, owner_id = self._owner()
            cursor = await conn.execute(
                """UPDATE ai_run_ledger SET status = 'UNKNOWN', updated_at = CURRENT_TIMESTAMP
                   WHERE run_id = %s AND owner_id = %s AND status IN ('QUEUED', 'RUNNING')
                   RETURNING run_id""",
                (run_id, owner_id),
            )
            return await cursor.fetchone() is not None

    async def recover_abandoned(self) -> int:
        """소유 세션의 잠금이 해제된 미완료 run만 UNKNOWN으로 전환한다.

        스캔 중 다른 인스턴스가 종료될 수 있으므로 이후 호출도 안전하게 허용한다.
        UNKNOWN인 실행은 재개하거나 같은 runId로 다시 실행하지 않는다.
        """
        recovered = 0
        after: UUID | None = None
        async with await psycopg.AsyncConnection.connect(
            self._conninfo, autocommit=True, row_factory=dict_row
        ) as conn:
            while True:
                cursor = await conn.execute(
                    """SELECT DISTINCT owner_id FROM ai_run_ledger
                       WHERE status IN ('QUEUED', 'RUNNING')
                         AND (%s::uuid IS NULL OR owner_id > %s::uuid)
                       ORDER BY owner_id LIMIT 100""",
                    (after, after),
                )
                owners = await cursor.fetchall()
                if not owners:
                    break
                for owner in owners:
                    owner_id = owner["owner_id"]
                    async with conn.transaction():
                        cursor = await conn.execute(
                            "SELECT pg_try_advisory_xact_lock(%s) AS acquired",
                            (_owner_lock_key(owner_id),),
                        )
                        row = await cursor.fetchone()
                        if row is None or not row["acquired"]:
                            continue
                        cursor = await conn.execute(
                            """UPDATE ai_run_ledger
                               SET status = 'UNKNOWN', updated_at = CURRENT_TIMESTAMP
                               WHERE owner_id = %s AND status IN ('QUEUED', 'RUNNING')""",
                            (owner_id,),
                        )
                        recovered += cursor.rowcount
                after = owners[-1]["owner_id"]
        return recovered

    async def ping(self) -> None:
        """소유 세션이 살아 있어 접수·결과 저장을 받을 수 있는지 확인한다. 아니면 예외를 낸다."""
        async with self._owner_mutex:
            conn, _ = self._owner()
            await conn.execute("SELECT 1")

    async def expire_details(self, limit: int = 100) -> int:
        """보존 기간(24시간)이 지난 최종 결과 상세를 오래된 순으로 최대 limit건 만료한다.

        expire_detail과 같이 ID·digest 표식은 남긴다.
        다른 인스턴스가 같은 행을 처리 중이면 건너뛴다.
        """
        if isinstance(limit, bool) or limit <= 0:
            raise ValueError("만료 처리 건수는 1 이상이어야 합니다")
        async with await psycopg.AsyncConnection.connect(self._conninfo) as conn:
            cursor = await conn.execute(
                """UPDATE ai_run_ledger
                   SET status = 'UNKNOWN', result = NULL, finished_at = NULL,
                       updated_at = CURRENT_TIMESTAMP
                   WHERE run_id IN (
                       SELECT run_id FROM ai_run_ledger
                       WHERE finished_at <= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                       ORDER BY finished_at LIMIT %s FOR UPDATE SKIP LOCKED
                   )""",
                (limit,),
            )
            return cursor.rowcount

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
