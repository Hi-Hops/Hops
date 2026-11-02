"""업무 실행을 노출하지 않는 골격 앱. 서비스 계약·구현 후 실행 API를 연결한다."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from hops_ai.api.routes import health
from hops_ai.harness.errors import ComponentNotReady
from hops_ai.persistence.run_ledger import RunLedger
from hops_ai.settings import RunMode, Settings


def create_app(settings: Settings | None = None, *, ledger: RunLedger | None = None) -> FastAPI:
    resolved = settings or Settings()
    if resolved.mode is RunMode.REAL:
        raise ComponentNotReady("real runtime")

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if ledger is None:
            yield
        else:
            # 잠금 확보와 중단 실행 복구가 실패하면 앱을 기동하지 않는다.
            async with ledger:
                app.state.run_ledger = ledger
                yield

    app = FastAPI(title="Hops AI", description="PRD 기반 AI 서버 골격", lifespan=lifespan)
    app.state.settings = resolved
    app.include_router(health.router)
    return app
