"""Spring에 돌려주는 오류 응답. 서비스 간 계약의 ApiErrorBody 형태를 쓴다.

요청 본문이나 검증 오류의 입력값은 응답에 담지 않는다(대화 내용이 섞일 수 있다).
"""

import uuid

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from hops_ai.api.schemas import ApiErrorBody, ErrorCode


class ApiError(Exception):
    def __init__(
        self,
        status_code: int,
        code: ErrorCode,
        message: str,
        *,
        retryable: bool = False,
        request_id: str | None = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.body = ApiErrorBody(
            code=code,
            message=message,
            requestId=request_id or str(uuid.uuid4()),
            retryable=retryable,
        )


async def handle_api_error(request: Request, error: ApiError) -> JSONResponse:
    return JSONResponse(status_code=error.status_code, content=error.body.model_dump(mode="json"))


async def handle_validation_error(request: Request, error: RequestValidationError) -> JSONResponse:
    return await handle_api_error(
        request, ApiError(400, "INVALID_INPUT", "요청 형식이 올바르지 않다")
    )
