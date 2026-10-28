"""실행 이벤트를 SSE로 보낸다. 전송 형식과 유휴 시 keep-alive는 FastAPI의 SSE 지원이 처리한다.

이벤트 이름은 서비스 간 계약(WIRE_CONTRACT 7절)을 따른다: progress · answer.delta · result.
id는 이벤트 번호이며 연결이 끊긴 뒤 Last-Event-ID로 이어 받는 데 쓴다.
"""

from fastapi.sse import ServerSentEvent

from hops_ai.api.schemas import AiDelta, AiFailure, AiProgress, AiSuccess
from hops_ai.harness.events import AiEvent


def event_name(event: AiEvent) -> str:
    if isinstance(event, AiProgress):
        return "progress"
    if isinstance(event, AiDelta):
        return "answer.delta"
    assert isinstance(event, AiSuccess | AiFailure)
    return "result"


def to_sse(event_id: int, event: AiEvent) -> ServerSentEvent:
    return ServerSentEvent(data=event, event=event_name(event), id=str(event_id))


def parse_last_event_id(value: str | None) -> int:
    """잘못된 값은 처음부터 다시 받는 것으로 본다. 중복은 받는 쪽이 eventId로 걸러낸다."""
    try:
        parsed = int(value) if value is not None else 0
    except ValueError:
        return 0
    return max(parsed, 0)
