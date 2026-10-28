from fastapi import FastAPI

from hops_ai.api.routes import health
from hops_ai.settings import Settings


def create_app(settings: Settings | None = None) -> FastAPI:
    """FastAPI 앱을 만든다. 실행은 `uvicorn --factory hops_ai.main:create_app`으로 한다."""
    app = FastAPI(title="Hops AI")
    app.state.settings = settings or Settings()
    app.include_router(health.router)
    return app
