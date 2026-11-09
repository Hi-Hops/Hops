"""Spring 전용 승인 사례 색인 작업 접수·조회(contracts/agents I1·I1.1)."""

from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request
from psycopg import Error as PsycopgError

from hops_ai.api.errors import ApiFault
from hops_ai.contracts.indexing import IndexApplySnapshot
from hops_ai.contracts.wire import AiError, WireUuid
from hops_ai.services.indexing.jobs import (
    IndexJobService,
    IndexRequestConflict,
    InvalidIndexRequest,
)

router = APIRouter(prefix="/internal/v2/index-jobs", tags=["internal index jobs"])


def get_index_jobs(request: Request) -> IndexJobService | None:
    return getattr(request.app.state, "index_jobs", None)


def _fault(status: int, code: str, message: str, *, retryable: bool) -> ApiFault:
    return ApiFault(status, AiError(code=code, message=message, retryable=retryable))


def _unavailable() -> ApiFault:
    return _fault(503, "INDEX_UNAVAILABLE", "색인 작업을 처리할 수 없습니다", retryable=True)


@router.post("", status_code=202, response_model=IndexApplySnapshot)
async def apply_index(
    request: Request, jobs: Annotated[IndexJobService | None, Depends(get_index_jobs)]
) -> IndexApplySnapshot:
    if jobs is None:
        raise _unavailable()
    try:
        body = await request.json()
    except ValueError:
        raise _fault(
            422, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다", retryable=False
        ) from None
    try:
        return await jobs.accept(body)
    except InvalidIndexRequest:
        raise _fault(
            422, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다", retryable=False
        ) from None
    except IndexRequestConflict:
        raise _fault(
            409, "CONFLICT", "같은 작업에 다른 내용의 요청입니다", retryable=False
        ) from None
    except PsycopgError:
        raise _unavailable() from None


@router.get("/{job_id}", response_model=IndexApplySnapshot)
async def get_index(
    job_id: WireUuid,
    attempt: Annotated[int, Query(gt=0, le=2_147_483_647)],
    jobs: Annotated[IndexJobService | None, Depends(get_index_jobs)],
) -> IndexApplySnapshot:
    if jobs is None:
        raise _unavailable()
    try:
        snapshot = await jobs.get(job_id, attempt)
    except PsycopgError:
        raise _unavailable() from None
    if snapshot is None:
        raise _fault(404, "NOT_FOUND", "색인 작업이 없습니다", retryable=False)
    return snapshot
