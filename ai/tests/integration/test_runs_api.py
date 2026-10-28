"""Spring이 쓰는 경로(POST runs, GET runs/{runId}/events)를 HTTP로 검사한다.

요청은 팀 공용 Mock 예시를 그대로 쓰고, 응답은 서비스 간 계약 타입으로 검증한다.
Fake 모드(가짜 에이전트)라서 연결과 형식만 확인하며 분석 품질을 뜻하지 않는다.
"""

import json
from contextlib import contextmanager

import pytest
from fastapi.testclient import TestClient

from hops_ai.api.schemas import AiAccepted, AiProgress, AiSuccess, ApiErrorBody, ai_result_adapter
from hops_ai.main import create_app
from hops_ai.settings import Settings

PREFIX = "/internal/mock/v1"
RUN_ID = "00000000-0000-4000-8000-000000000300"
FOLLOW_UP_RUN_ID = "00000000-0000-4000-8000-000000000301"


@contextmanager
def server(**settings):
    with TestClient(create_app(Settings(_env_file=None, **settings))) as client:
        yield client


def parse_sse(text: str) -> list[dict[str, str]]:
    events = []
    for block in text.strip().split("\n\n"):
        fields = {}
        for line in block.splitlines():
            if line.startswith(":"):
                continue  # 주석(keep-alive)
            key, _, value = line.partition(": ")
            fields[key] = value
        if fields:
            events.append(fields)
    return events


def read_events(client, run_id: str, headers=None) -> tuple[list[dict[str, str]], str]:
    with client.stream("GET", f"{PREFIX}/runs/{run_id}/events", headers=headers) as response:
        assert response.status_code == 200
        text = "".join(response.iter_text())
    return parse_sse(text), text


def post(client, value) -> object:
    return client.post(f"{PREFIX}/runs", json=value)


def test_accepting_a_request_returns_the_mock_contracts_accepted_body(
    make_request_json, mock_cases
) -> None:
    with server() as client:
        response = post(client, make_request_json())

    assert response.status_code == 202
    assert response.json() == mock_cases["ai-accepted"]
    AiAccepted.model_validate(response.json())


def test_single_agent_run_sends_only_the_result(make_request_json) -> None:
    with server(agent_mode="single") as client:
        post(client, make_request_json())
        events, _ = read_events(client, RUN_ID)

    assert [event["event"] for event in events] == ["result"]
    assert events[0]["id"] == "1"
    result = ai_result_adapter.validate_python(json.loads(events[0]["data"]))
    assert isinstance(result, AiSuccess)
    assert (result.runId, result.inquiryVersion) == (RUN_ID, 1)
    assert [problem.action for problem in result.problems] == ["ASK_USER"]
    assert result.problems[0].question


def test_multi_agent_run_sends_progress_before_the_result(make_request_json) -> None:
    with server(agent_mode="multi") as client:
        post(client, make_request_json())
        events, _ = read_events(client, RUN_ID)

    assert [event["event"] for event in events] == ["progress", "progress", "result"]
    assert [event["id"] for event in events] == ["1", "2", "3"]
    first = AiProgress.model_validate(json.loads(events[0]["data"]))
    assert (first.payload.stepType, first.payload.status) == ("INTAKE", "RUNNING")
    assert isinstance(ai_result_adapter.validate_python(json.loads(events[2]["data"])), AiSuccess)


@pytest.mark.parametrize("agent_mode", ["single", "multi"])
def test_a_follow_up_gets_ticket_proposals_in_both_configurations(
    agent_mode, make_request_json
) -> None:
    with server(agent_mode=agent_mode) as client:
        post(client, make_request_json("ai-request-tickets"))
        events, _ = read_events(client, FOLLOW_UP_RUN_ID)

    result = ai_result_adapter.validate_python(json.loads(events[-1]["data"]))
    assert isinstance(result, AiSuccess)
    assert [problem.action for problem in result.problems] == ["PROPOSE_TICKETS"]
    assert [proposal.departmentCode for proposal in result.ticketProposals] == ["NETWORK"]
    assert [proposal.problemRef for proposal in result.ticketProposals] == [
        "00000000-0000-4000-8000-000000000201"
    ]


def test_a_problem_that_used_all_questions_goes_on_to_tickets(make_request_json) -> None:
    with server() as client:
        post(client, make_request_json("ai-request-cs"))
        events, _ = read_events(client, FOLLOW_UP_RUN_ID)

    result = ai_result_adapter.validate_python(json.loads(events[-1]["data"]))
    assert isinstance(result, AiSuccess)
    assert result.problems[0].questionCount == 3
    assert [proposal.departmentCode for proposal in result.ticketProposals] == ["CS"]


def test_the_result_is_the_last_event_and_korean_text_is_not_escaped(make_request_json) -> None:
    with server(agent_mode="multi") as client:
        post(client, make_request_json())
        events, text = read_events(client, RUN_ID)

    assert events[-1]["event"] == "result"
    assert [event["event"] for event in events].count("result") == 1
    assert "오류 코드나 메시지를 알려 주세요." in text  # \uXXXX로 바뀌지 않았다


def test_the_stream_can_be_resumed_after_the_last_received_event(make_request_json) -> None:
    with server(agent_mode="multi") as client:
        post(client, make_request_json())
        events, _ = read_events(client, RUN_ID, headers={"Last-Event-ID": "2"})
        everything, _ = read_events(client, RUN_ID, headers={"Last-Event-ID": "garbage"})

    assert [event["id"] for event in events] == ["3"]
    assert [event["id"] for event in everything] == ["1", "2", "3"]


def test_a_repeated_request_is_accepted_again_but_executed_once(make_request_json) -> None:
    with server(agent_mode="multi") as client:
        first = post(client, make_request_json())
        second = post(client, make_request_json())
        events, _ = read_events(client, RUN_ID)

    assert first.status_code == second.status_code == 202
    assert first.json() == second.json()
    assert [event["event"] for event in events] == ["progress", "progress", "result"]


def test_the_same_run_id_for_another_version_is_a_conflict(make_request_json) -> None:
    with server() as client:
        post(client, make_request_json())
        response = post(client, make_request_json(inquiryVersion=2))

    assert response.status_code == 409
    body = ApiErrorBody.model_validate(response.json())
    assert (body.code, body.retryable) == ("IDEMPOTENCY_KEY_REUSED", False)


def test_an_unknown_run_is_not_found() -> None:
    with server() as client:
        response = client.get(f"{PREFIX}/runs/{RUN_ID}/events")

    assert response.status_code == 404
    assert ApiErrorBody.model_validate(response.json()).code == "NOT_FOUND"


def test_a_malformed_run_id_is_an_input_error() -> None:
    with server() as client:
        response = client.get(f"{PREFIX}/runs/not-a-uuid/events")

    assert response.status_code == 400
    assert ApiErrorBody.model_validate(response.json()).code == "INVALID_INPUT"


@pytest.mark.parametrize(
    "mutate",
    [
        lambda value: value.update(secretNote="비밀번호는 hunter2"),
        lambda value: value.pop("messages"),
        lambda value: value.update(inquiryVersion=0),
        lambda value: value["messages"][0].update(content=12345),
    ],
    ids=["unknown-field", "missing-field", "bad-version", "bad-type"],
)
def test_an_invalid_body_is_rejected_without_echoing_what_was_sent(
    make_request_json, mutate
) -> None:
    value = make_request_json()
    mutate(value)
    with server() as client:
        response = post(client, value)

    assert response.status_code == 400
    assert ApiErrorBody.model_validate(response.json()).code == "INVALID_INPUT"
    assert "hunter2" not in response.text
    assert "VPN 접속 오류가 나요" not in response.text


def test_an_expired_deadline_gives_a_timed_out_result(make_request_json) -> None:
    with server() as client:
        post(client, make_request_json(deadline_seconds=-5))
        events, _ = read_events(client, RUN_ID)

    result = ai_result_adapter.validate_python(json.loads(events[-1]["data"]))
    assert (result.status, result.error.code) == ("TIMED_OUT", "DEADLINE_EXCEEDED")


def test_the_path_prefix_can_be_changed_by_setting(make_request_json) -> None:
    with server(api_prefix="/internal/v1") as client:
        moved = client.post("/internal/v1/runs", json=make_request_json())
        old = client.post(f"{PREFIX}/runs", json=make_request_json())

    assert moved.status_code == 202
    assert old.status_code == 404


def test_the_server_is_not_ready_before_it_has_started(make_request_json) -> None:
    client = TestClient(create_app(Settings(_env_file=None)))  # 시작 절차(lifespan)를 거치지 않는다

    response = post(client, make_request_json())

    assert response.status_code == 503
    body = ApiErrorBody.model_validate(response.json())
    assert (body.code, body.retryable) == ("SERVICE_UNAVAILABLE", True)
    assert client.get("/healthz").status_code == 200  # 생존 확인은 그대로 된다


def test_real_mode_refuses_to_start_instead_of_silently_using_fakes() -> None:
    with pytest.raises(NotImplementedError), server(mode="real"):
        pass
