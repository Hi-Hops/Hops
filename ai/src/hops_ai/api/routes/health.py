from fastapi import APIRouter
from fastapi.responses import JSONResponse

router = APIRouter()


@router.get("/healthz")
async def healthz() -> dict[str, str]:
    """프로세스 생존이며 모델·검색·업무 연결 준비 완료를 뜻하지 않는다."""
    return {"status": "ok"}


@router.get("/readyz", status_code=503)
async def readyz() -> JSONResponse:
    """새 파이프라인이 아직 연결되지 않아 업무 요청을 받을 수 없다."""
    return JSONResponse(
        status_code=503,
        content={"status": "not_ready", "reason": "AI_PIPELINE_NOT_IMPLEMENTED"},
    )
