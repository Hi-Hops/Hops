"""분석 실행 접수와 진행·결과 SSE. 경로의 접두사는 설정(HOPS_AI_API_PREFIX)이다.

- POST   {prefix}/runs                  AiRequest → 202 AiAccepted
- GET    {prefix}/runs/{runId}/events   SSE progress · answer.delta · result (result는 한 번)
브라우저는 이 서버에 직접 연결하지 않는다. Spring만 호출한다.
"""

from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import APIRouter, Depends, Header
from fastapi.sse import EventSourceResponse, ServerSentEvent

from hops_ai.api.dependencies import get_run_handle, get_runner
from hops_ai.api.errors import ApiError
from hops_ai.api.schemas import AiAccepted, AiRequest
from hops_ai.api.sse import parse_last_event_id, to_sse
from hops_ai.harness.runner import RunConflict, RunHandle, RunManager

router = APIRouter()


@router.post("/runs", status_code=202)
async def create_run(
    request: AiRequest, runner: Annotated[RunManager, Depends(get_runner)]
) -> AiAccepted:
    try:
        return runner.start(request)
    except RunConflict as error:
        raise ApiError(
            409, "IDEMPOTENCY_KEY_REUSED", "같은 runId로 다른 문의 또는 버전이 요청됐다"
        ) from error


@router.get("/runs/{run_id}/events", response_class=EventSourceResponse)
async def run_events(
    handle: Annotated[RunHandle, Depends(get_run_handle)],
    last_event_id: Annotated[str | None, Header()] = None,
) -> AsyncIterator[ServerSentEvent]:
    async for event_id, event in handle.events.stream(parse_last_event_id(last_event_id)):
        yield to_sse(event_id, event)
