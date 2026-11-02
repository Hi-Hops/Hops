"""AI 서버 앱 조립. 실행 파이프라인이 주입된 경우에만 실행 접수를 연다."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from hops_ai.api.auth import ServiceAuthMiddleware
from hops_ai.api.errors import install_error_handlers
from hops_ai.api.routes import health, runs
from hops_ai.harness.budget import Clock
from hops_ai.harness.errors import ComponentNotReady
from hops_ai.persistence.run_ledger import RunLedger
from hops_ai.services.runs.admission import RunAdmissionService
from hops_ai.services.runs.executor import RunExecutor, RunPipeline, server_ceilings
from hops_ai.settings import RunMode, Settings


def create_app(
    settings: Settings | None = None,
    *,
    ledger: RunLedger | None = None,
    pipeline: RunPipeline | None = None,
    clock: Clock | None = None,
) -> FastAPI:
    resolved = settings or Settings()
    if resolved.mode is RunMode.REAL:
        raise ComponentNotReady("real runtime")
    resolved_ledger = ledger
    if resolved.internal_api_enabled and resolved_ledger is None:
        # 활성 프로필의 DSN과 서비스 토큰은 Settings에서 먼저 검증한다.
        if resolved.runtime_dsn is None:
            raise ComponentNotReady("AI runtime DB")
        resolved_ledger = RunLedger(resolved.runtime_dsn.get_secret_value())

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.run_executor = None
        if resolved_ledger is None:
            yield
            return
        # 잠금 확보와 중단 실행 복구가 실패하면 앱을 기동하지 않는다.
        async with resolved_ledger:
            app.state.run_ledger = resolved_ledger
            executor: RunExecutor | None = None
            if pipeline is not None:
                admission = RunAdmissionService(resolved_ledger, server_ceilings(resolved))
                executor = RunExecutor(resolved_ledger, admission, pipeline, resolved, clock=clock)
                app.state.run_executor = executor
            try:
                yield
            finally:
                # 소유 세션을 닫기 전에 실행을 멈춘다. 남은 run은 다음 복구에서 UNKNOWN이 된다.
                if executor is not None:
                    await executor.shutdown()

    app = FastAPI(title="Hops AI", description="PRD 기반 AI 서버 골격", lifespan=lifespan)
    app.state.settings = resolved
    app.include_router(health.router)
    if resolved.internal_api_enabled:
        if resolved.ai_service_token is None:
            raise ComponentNotReady("AI service token")
        app.add_middleware(ServiceAuthMiddleware, token=resolved.ai_service_token)
        install_error_handlers(app)
        app.include_router(runs.router)
    return app
