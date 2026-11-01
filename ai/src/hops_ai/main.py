"""업무 실행을 노출하지 않는 골격 앱. 서비스 계약·구현 후 실행 API를 연결한다."""

from fastapi import FastAPI

from hops_ai.api.routes import health
from hops_ai.harness.errors import ComponentNotReady
from hops_ai.settings import RunMode, Settings


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved = settings or Settings()
    if resolved.mode is RunMode.REAL:
        raise ComponentNotReady("real runtime")

    app = FastAPI(title="Hops AI", description="PRD 기반 AI 서버 골격")
    app.state.settings = resolved
    app.include_router(health.router)
    return app
