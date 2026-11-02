"""내부 API에서 사용하는 안전한 계약 오류 응답."""

from fastapi import FastAPI, Request
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from hops_ai.contracts.wire import AiError


class ApiFault(Exception):
    def __init__(self, status_code: int, error: AiError, *, retry_after: int | None = None) -> None:
        super().__init__(error.code)
        self.status_code = status_code
        self.error = error
        self.retry_after = retry_after


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiFault)
    async def internal_fault_handler(_request: Request, exc: ApiFault) -> JSONResponse:
        headers: dict[str, str] = {}
        if exc.status_code == 401:
            headers["WWW-Authenticate"] = "Bearer"
        if exc.retry_after is not None:
            headers["Retry-After"] = str(exc.retry_after)
        return JSONResponse(
            status_code=exc.status_code, content=exc.error.model_dump(mode="json"), headers=headers
        )

    @app.exception_handler(RequestValidationError)
    async def validation_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        if not request.url.path.startswith("/internal/v2/"):
            return await request_validation_exception_handler(request, exc)
        # 원본 입력과 Pydantic 오류 세부 사항에 민감정보가 포함될 수 있다.
        error = AiError(
            code="INVALID_REQUEST", message="요청 형식이 올바르지 않습니다", retryable=False
        )
        return JSONResponse(status_code=422, content=error.model_dump(mode="json"))
