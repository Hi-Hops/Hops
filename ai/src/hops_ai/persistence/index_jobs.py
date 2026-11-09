"""승인 사례 색인 작업의 영속 접수(contracts/agents I1.1 5번 "AI 멱등 접수").

(jobId, attempt)를 유일 키로 원자적으로 접수한다. 같은 키·같은 요청은 기존 작업을 돌려주고 실행을
추가하지 않는다. 같은 키에 다른 내용이면 IndexJobConflict다.
요청 본문은 보존하지 않고 digest만 둔다.
"""

from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb


class IndexJobConflict(ValueError):
    """이미 접수한 (jobId, attempt)에 다른 내용의 요청이 왔다."""


@dataclass(frozen=True)
class IndexJob:
    job_id: str
    attempt: int
    case_id: str
    case_version: int
    store: str
    content_digest: str
    state: str
    error: dict[str, Any] | None
    updated_at: datetime


@dataclass(frozen=True)
class IndexJobAcceptance:
    created: bool
    job: IndexJob


def _job(row: dict[str, Any]) -> IndexJob:
    return IndexJob(
        job_id=str(row["job_id"]),
        attempt=row["attempt"],
        case_id=str(row["case_id"]),
        case_version=row["case_version"],
        store=row["store"],
        content_digest=row["content_digest"],
        state=row["state"],
        error=row["error"],
        updated_at=row["updated_at"].astimezone(UTC),
    )


_COLUMNS = "job_id, attempt, case_id, case_version, store, content_digest, state, error, updated_at"


class IndexJobStore:
    """호출마다 짧은 연결을 연다. 색인은 승인 때만 생겨 빈도가 낮다."""

    def __init__(self, conninfo: str) -> None:
        self._conninfo = conninfo

    async def _connect(self) -> psycopg.AsyncConnection:
        return await psycopg.AsyncConnection.connect(
            self._conninfo, autocommit=True, row_factory=dict_row
        )

    async def accept(
        self,
        *,
        job_id: str,
        attempt: int,
        case_id: str,
        case_version: int,
        store: str,
        content_digest: str,
        request_digest: str,
    ) -> IndexJobAcceptance:
        async with await self._connect() as conn:
            cursor = await conn.execute(
                f"""
                INSERT INTO ai_index_job (job_id, attempt, case_id, case_version, store,
                    content_digest, request_digest, state)
                VALUES (%s, %s, %s, %s, %s, %s, %s, 'PENDING')
                ON CONFLICT (job_id, attempt) DO NOTHING
                RETURNING {_COLUMNS}
                """,
                (
                    UUID(job_id),
                    attempt,
                    UUID(case_id),
                    case_version,
                    store,
                    content_digest,
                    request_digest,
                ),
            )
            row = await cursor.fetchone()
            if row is not None:
                return IndexJobAcceptance(created=True, job=_job(row))
            cursor = await conn.execute(
                f"SELECT {_COLUMNS}, request_digest FROM ai_index_job"
                " WHERE job_id = %s AND attempt = %s",
                (UUID(job_id), attempt),
            )
            existing = await cursor.fetchone()
        if existing is None or existing["request_digest"] != request_digest:
            raise IndexJobConflict("같은 작업·attempt에 다른 요청이 왔다")
        return IndexJobAcceptance(created=False, job=_job(existing))

    async def get(self, job_id: str, attempt: int) -> IndexJob | None:
        async with await self._connect() as conn:
            cursor = await conn.execute(
                f"SELECT {_COLUMNS} FROM ai_index_job WHERE job_id = %s AND attempt = %s",
                (UUID(job_id), attempt),
            )
            row = await cursor.fetchone()
        return _job(row) if row is not None else None

    async def transition(
        self,
        job_id: str,
        attempt: int,
        state: str,
        error: dict[str, Any] | None = None,
        *,
        from_states: tuple[str, ...],
    ) -> IndexJob | None:
        """현재 상태가 from_states일 때만 바꾼다. 바뀌지 않았으면 None이다.

        늦은 결과가 이미 끝난 상태를 덮지 않게 한다.
        """
        async with await self._connect() as conn:
            cursor = await conn.execute(
                f"""
                UPDATE ai_index_job SET state = %s, error = %s, updated_at = CURRENT_TIMESTAMP
                WHERE job_id = %s AND attempt = %s AND state = ANY(%s)
                RETURNING {_COLUMNS}
                """,
                (
                    state,
                    Jsonb(error) if error is not None else None,
                    UUID(job_id),
                    attempt,
                    list(from_states),
                ),
            )
            row = await cursor.fetchone()
        return _job(row) if row is not None else None

    async def ping(self) -> None:
        async with await self._connect() as conn:
            await conn.execute("SELECT 1 FROM ai_index_job LIMIT 0")
