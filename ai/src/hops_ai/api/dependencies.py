"""요청 처리에 필요한 객체를 앱에서 꺼내 준다. 서비스 간 인증은 합의 후 이곳에 추가한다."""

from typing import Annotated

from fastapi import Depends, Path, Request

from hops_ai.api.errors import ApiError
from hops_ai.contracts.types import UUID_PATTERN
from hops_ai.harness.runner import RunHandle, RunManager
from hops_ai.settings import Settings


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_runner(request: Request) -> RunManager:
    runner = getattr(request.app.state, "runner", None)
    if runner is None:
        raise ApiError(503, "SERVICE_UNAVAILABLE", "AI 서버가 아직 준비되지 않았다", retryable=True)
    return runner


RunId = Annotated[str, Path(pattern=f"^{UUID_PATTERN}$")]


def get_run_handle(run_id: RunId, runner: Annotated[RunManager, Depends(get_runner)]) -> RunHandle:
    """스트림을 시작하기 전에 실행을 찾는다. 시작한 뒤에는 상태 코드를 바꿀 수 없다."""
    handle = runner.get(run_id)
    if handle is None:
        raise ApiError(404, "NOT_FOUND", "알 수 없는 실행이다")
    return handle
