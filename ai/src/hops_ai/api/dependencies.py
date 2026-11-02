"""Spring 서비스 인증과 내부 API 의존성."""

import hmac
from typing import Annotated

from fastapi import Header, Request

from hops_ai.api.errors import ApiFault
from hops_ai.contracts.wire import AiError
from hops_ai.persistence.run_ledger import RunLedger


async def require_service_auth(
    request: Request,
    authorization: Annotated[str | None, Header()] = None,
) -> None:
    token = request.app.state.settings.ai_service_token
    # 활성화된 프로필은 시작 전 설정을 검증한다. 실패 시 인증을 우회하지 않는다.
    scheme, separator, supplied = (authorization or "").partition(" ")
    if (
        token is None
        or scheme.lower() != "bearer"
        or not separator
        or not supplied
        or not hmac.compare_digest(
            supplied.encode("utf-8"), token.get_secret_value().encode("ascii")
        )
    ):
        raise ApiFault(
            401,
            AiError(
                code="AUTH_CONTEXT_INVALID", message="서비스 인증에 실패했습니다", retryable=False
            ),
        )


def get_run_ledger(request: Request) -> RunLedger:
    return request.app.state.run_ledger
