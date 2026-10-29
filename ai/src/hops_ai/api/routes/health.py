from fastapi import APIRouter

router = APIRouter()


@router.get("/healthz")
async def healthz() -> dict[str, str]:
    """프로세스가 살아 있는지만 확인한다. 외부 연결 준비 여부(readyz)는 연결이 생길 때 추가한다."""
    return {"status": "ok"}
