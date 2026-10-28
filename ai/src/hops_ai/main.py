import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError

from hops_ai.agents.registry import build_registry
from hops_ai.api.errors import ApiError, handle_api_error, handle_validation_error
from hops_ai.api.routes import health, runs
from hops_ai.fakes.services import build_fake_services
from hops_ai.harness.runner import RunManager
from hops_ai.harness.trace import LoggingTracer, Tracer
from hops_ai.settings import Settings


def _configure_logging() -> None:
    """hops_ai 로그가 서버 출력에 보이게 한다. 여러 번 불러도 핸들러는 하나만 둔다."""
    logger = logging.getLogger("hops_ai")
    if not logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(levelname)s:     %(name)s %(message)s"))
        logger.addHandler(handler)
    logger.setLevel(logging.INFO)


def create_app(settings: Settings | None = None, *, tracer: Tracer | None = None) -> FastAPI:
    """FastAPI 앱을 만든다. 실행은 `uvicorn --factory hops_ai.main:create_app`으로 한다."""
    resolved = settings or Settings()
    _configure_logging()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        registry = build_registry(resolved)  # real 모드는 지원하지 않으므로 여기서 시작을 막는다
        runner = RunManager(
            resolved, registry, build_fake_services, tracer=tracer or LoggingTracer()
        )
        app.state.runner = runner
        try:
            yield
        finally:
            await runner.shutdown()

    app = FastAPI(title="Hops AI", lifespan=lifespan)
    app.state.settings = resolved
    app.add_exception_handler(ApiError, handle_api_error)
    app.add_exception_handler(RequestValidationError, handle_validation_error)
    app.include_router(health.router)
    app.include_router(runs.router, prefix=resolved.api_prefix)
    return app
