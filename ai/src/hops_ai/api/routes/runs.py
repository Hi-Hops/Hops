"""Spring 전용 실행 조회와 실행기 미준비 경계."""

import logging
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends
from psycopg import Error as PsycopgError
from pydantic import ValidationError

from hops_ai.api.dependencies import get_run_ledger
from hops_ai.api.errors import ApiFault
from hops_ai.contracts.wire import AiError, AiResult, AiRunRequest, AiRunSnapshot, WireUuid
from hops_ai.persistence.run_ledger import RunLedger

router = APIRouter(prefix="/internal/v2/runs", tags=["internal runs"])
logger = logging.getLogger(__name__)


def _not_ready() -> ApiFault:
    return ApiFault(
        503,
        AiError(
            code="SERVICE_UNAVAILABLE", message="AI 실행기가 준비되지 않았습니다", retryable=True
        ),
    )


@router.post("", status_code=503)
async def start_run(_request: AiRunRequest) -> None:
    # 실행 예약·Supervisor가 없으므로 RunLedger에 접수하지 않는다.
    raise _not_ready()


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
