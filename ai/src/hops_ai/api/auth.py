"""내부 실행 API의 요청 본문을 읽기 전 서비스 호출자를 확인한다."""

import hmac

from pydantic import SecretStr
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from hops_ai.contracts.wire import AiError


class ServiceAuthMiddleware:
    def __init__(self, app: ASGIApp, *, token: SecretStr) -> None:
        self.app = app
        self._token = token.get_secret_value().encode("ascii")

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        path = scope.get("path", "")
        if scope["type"] != "http" or not (
            path == "/internal/v2/runs" or path.startswith("/internal/v2/runs/")
        ):
            await self.app(scope, receive, send)
            return

        # 중복 Authorization은 프록시와 앱이 서로 다르게 해석할 수 있어 거부한다.
        values = [
            value for name, value in scope.get("headers", []) if name.lower() == b"authorization"
        ]
        scheme, separator, supplied = (
            values[0].partition(b" ") if len(values) == 1 else (b"", b"", b"")
        )
        if (
            scheme.lower() != b"bearer"
            or not separator
            or not supplied
            or not hmac.compare_digest(supplied, self._token)
        ):
            error = AiError(
                code="AUTH_CONTEXT_INVALID", message="서비스 인증에 실패했습니다", retryable=False
            )
            response = JSONResponse(
                status_code=401,
                content=error.model_dump(mode="json"),
                headers={"WWW-Authenticate": "Bearer"},
            )
            # receive를 호출하지 않아 인증 실패 요청의 본문을 읽지 않는다.
            await response(scope, receive, send)
            return
        await self.app(scope, receive, send)
