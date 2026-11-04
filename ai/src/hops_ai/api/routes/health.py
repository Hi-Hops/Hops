import asyncio
import logging
from typing import Literal

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

router = APIRouter()
logger = logging.getLogger(__name__)

# 준비 확인은 컨테이너 상태 확인 주기보다 짧게 끝나야 한다. 모델·외부 서비스 호출은 넣지 않는다.
READY_CHECK_TIMEOUT_SECONDS = 2.0


class Ready(BaseModel):
    status: Literal["ready"]


class NotReady(BaseModel):
    status: Literal["not_ready"]
    reason: Literal["RUN_PIPELINE_DISABLED", "RUN_LEDGER_UNAVAILABLE"]


@router.get("/healthz")
async def healthz() -> dict[str, str]:
    """프로세스 생존이며 모델·검색·업무 연결 준비 완료를 뜻하지 않는다."""
    return {"status": "ok"}


@router.get(
    "/readyz",
    response_model=None,
    responses={
        200: {"model": Ready, "description": "업무 요청을 받을 준비가 됐다"},
        503: {"model": NotReady, "description": "업무 요청을 받을 준비가 되지 않았다"},
    },
)
async def readyz(request: Request) -> JSONResponse:
    """실행 접수가 열려 있고 RunLedger 소유 세션이 응답할 때만 업무 요청을 받을 준비가 됐다."""
    state = request.app.state
    ledger = getattr(state, "run_ledger", None)
    if ledger is None or getattr(state, "run_executor", None) is None:
        # scaffold이거나 실행 파이프라인이 연결되지 않았거나 종료 중이다.
        return _not_ready("RUN_PIPELINE_DISABLED")
    try:
        await asyncio.wait_for(ledger.ping(), READY_CHECK_TIMEOUT_SECONDS)
    except Exception as exc:  # noqa: BLE001 - 원인과 관계없이 준비되지 않음으로 응답한다.
        logger.warning("RunLedger 소유 세션을 확인하지 못했습니다: %s", type(exc).__name__)
        return _not_ready("RUN_LEDGER_UNAVAILABLE")
    return JSONResponse(status_code=200, content=Ready(status="ready").model_dump())


def _not_ready(reason: str) -> JSONResponse:
    # 문서화한 응답 모델로 만들어 OpenAPI 명세와 실제 본문이 어긋나지 않게 한다.
    body = NotReady(status="not_ready", reason=reason)
    return JSONResponse(status_code=503, content=body.model_dump())
