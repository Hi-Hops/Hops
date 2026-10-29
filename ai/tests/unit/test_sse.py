import fastapi.routing
import pytest

from hops_ai.api.schemas import (
    SCHEMA_VERSION,
    AgentProgressPayload,
    AiDelta,
    AiFailure,
    AiProgress,
    AiSuccess,
    AnswerDeltaPayload,
    ApiErrorBody,
)
from hops_ai.api.sse import event_name, parse_last_event_id, to_sse

RUN_ID = "00000000-0000-4000-8000-000000000300"
INQUIRY_ID = "00000000-0000-4000-8000-000000000200"
IDS = {"runId": RUN_ID, "inquiryId": INQUIRY_ID, "inquiryVersion": 1}


def progress() -> AiProgress:
    return AiProgress(
        schemaVersion=SCHEMA_VERSION,
        **IDS,
        payload=AgentProgressPayload(
            stepNo=1, stepType="INTAKE", displaySummary="확인", status="RUNNING"
        ),
    )


def delta() -> AiDelta:
    return AiDelta(
        schemaVersion=SCHEMA_VERSION,
        **IDS,
        payload=AnswerDeltaPayload(
            messageId="00000000-0000-4000-8000-000000000605", seq=1, text="담당 "
        ),
    )


def success() -> AiSuccess:
    return AiSuccess(
        schemaVersion=SCHEMA_VERSION,
        **IDS,
        status="OK",
        answer="답",
        problems=[],
        ticketProposals=[],
        error=None,
    )


def failure() -> AiFailure:
    return AiFailure(
        schemaVersion=SCHEMA_VERSION,
        **IDS,
        status="TIMED_OUT",
        error=ApiErrorBody(
            code="DEADLINE_EXCEEDED", message="시간 초과", requestId=RUN_ID, retryable=False
        ),
    )


@pytest.mark.parametrize(
    ("event", "name"),
    [
        (progress(), "progress"),
        (delta(), "answer.delta"),
        (success(), "result"),
        (failure(), "result"),
    ],
)
def test_event_names_follow_the_mock_contract(event, name) -> None:
    assert event_name(event) == name


def test_sse_event_carries_the_number_as_id_and_the_event_as_data() -> None:
    event = progress()

    sse = to_sse(3, event)

    assert (sse.id, sse.event, sse.data) == ("3", "progress", event)


@pytest.mark.parametrize(
    ("header", "expected"),
    [(None, 0), ("0", 0), ("5", 5), ("abc", 0), ("-3", 0), ("", 0), ("1.5", 0)],
)
def test_last_event_id_is_parsed_defensively(header, expected) -> None:
    assert parse_last_event_id(header) == expected


def test_keep_alive_interval_is_shorter_than_typical_proxy_idle_timeouts() -> None:
    # FastAPI의 SSE 지원이 유휴 연결에 keep-alive를 보내는 간격이다. 버전을 올릴 때 확인한다.
    assert fastapi.routing._PING_INTERVAL <= 20
