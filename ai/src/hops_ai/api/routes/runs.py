"""Spring 전용 실행 접수·조회. 실행 파이프라인이 없으면 접수하지 않는다."""

import logging
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends
from psycopg import Error as PsycopgError
from pydantic import ValidationError

from hops_ai.api.dependencies import get_run_executor, get_run_ledger
from hops_ai.api.errors import ApiFault
from hops_ai.contracts.wire import (
    AiAccepted,
    AiError,
    AiResult,
    AiRunRequest,
    AiRunSnapshot,
    WireUuid,
)
from hops_ai.persistence.run_ledger import InvalidRunTransition, RunConflict, RunLedger
from hops_ai.services.runs.admission import AdmissionRejected, LimitsExceeded
from hops_ai.services.runs.executor import ExecutorBusy, RunExecutor

router = APIRouter(prefix="/internal/v2/runs", tags=["internal runs"])
logger = logging.getLogger(__name__)


def _not_ready() -> ApiFault:
    return ApiFault(
        503,
        AiError(
            code="SERVICE_UNAVAILABLE", message="AI 실행기가 준비되지 않았습니다", retryable=True
        ),
    )


def _fault(
    status: int, code: str, message: str, *, retryable: bool, retry_after: int | None = None
) -> ApiFault:
    return ApiFault(
        status,
        AiError(code=code, message=message, retryable=retryable),
        retry_after=retry_after,
    )


@router.post("", status_code=202, response_model=AiAccepted)
async def start_run(
    request: AiRunRequest,
    executor: Annotated[RunExecutor | None, Depends(get_run_executor)],
) -> AiAccepted:
    if executor is None:
        # 실행 파이프라인이 없으면 실행되지 않을 run을 접수하지 않는다.
        raise _not_ready()
    try:
        await executor.submit(request)
    except RunConflict:
        raise _fault(
            409, "CONFLICT", "같은 실행 ID에 다른 입력이 들어왔습니다", retryable=False
        ) from None
    except LimitsExceeded:
        raise _fault(
            429, "BUDGET_EXCEEDED", "요청한 실행 한도가 서버 허용 범위를 넘습니다", retryable=False
        ) from None
    except AdmissionRejected:
        # 기한이 지났거나 기한 형식이 올바르지 않다. 같은 입력으로는 다시 접수되지 않는다.
        raise _fault(
            422, "INVALID_REQUEST", "실행 기한이 올바르지 않습니다", retryable=False
        ) from None
    except ExecutorBusy as busy:
        raise _fault(
            429,
            "RATE_LIMITED",
            "동시 실행 한도에 도달했습니다",
            retryable=True,
            retry_after=busy.retry_after,
        ) from None
    except (PsycopgError, InvalidRunTransition):
        logger.warning("AI 실행 접수 저장소에 접근하지 못했습니다")
        raise _fault(
            503, "SERVICE_UNAVAILABLE", "실행을 접수할 수 없습니다", retryable=True
        ) from None
    # 같은 입력의 재전송도 같은 응답이다. 실제 상태는 snapshot으로 확인한다.
    return AiAccepted(
        contractVersion="hops-system-1",
        runId=request.runId,
        status="QUEUED",
        eventsPath=f"/internal/v2/runs/{request.runId}/events",
    )


@router.get("/{runId}", response_model=AiRunSnapshot)
async def get_run(
    runId: WireUuid, ledger: Annotated[RunLedger, Depends(get_run_ledger)]
) -> AiRunSnapshot:
    try:
        snapshot = await ledger.get(UUID(runId))
    except PsycopgError:
        logger.warning("AI 실행 상태 저장소 조회에 실패했습니다")
        raise ApiFault(
            503,
            AiError(
                code="SERVICE_UNAVAILABLE", message="실행 상태를 확인할 수 없습니다", retryable=True
            ),
        ) from None
    if snapshot is None:
        raise ApiFault(
            404, AiError(code="NOT_FOUND", message="실행을 찾을 수 없습니다", retryable=False)
        )
    try:
        result = AiResult.model_validate(snapshot.result) if snapshot.result is not None else None
        return AiRunSnapshot(
            contractVersion="hops-system-1",
            runId=str(snapshot.run_id),
            status=snapshot.status,
            lastSeq=snapshot.last_seq,
            result=result,
            error=result.error if result is not None else None,
        )
    except ValidationError:
        # 잘못된 저장 결과를 빈 결과나 성공으로 바꾸지 않는다.
        logger.warning("AI 실행 상태의 저장 형식이 올바르지 않습니다")
        raise ApiFault(
            503,
            AiError(
                code="SERVICE_UNAVAILABLE", message="실행 상태를 확인할 수 없습니다", retryable=True
            ),
        ) from None


@router.get("/{runId}/events", status_code=503)
async def get_run_events(runId: WireUuid) -> None:
    raise _not_ready()


@router.post("/{runId}/cancel", status_code=503)
async def cancel_run(runId: WireUuid) -> None:
    raise _not_ready()
