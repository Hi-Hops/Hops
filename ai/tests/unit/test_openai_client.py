"""OpenAI 어댑터를 가짜 HTTP 전송으로 확인한다. 실제 OpenAI는 호출하지 않는다."""

import json

import httpx
import pytest
from pydantic import BaseModel

from hops_ai.harness.errors import ComponentNotReady, OutputRejected, TransientError
from hops_ai.services.llm.client import LLMMessage, LLMRequest
from hops_ai.services.llm.openai import OpenAIClient


class Reply(BaseModel):
    text: str


def make_request(max_input_tokens: int | None = None) -> LLMRequest:
    return LLMRequest(
        messages=[LLMMessage(role="user", content="전자결재 문서 조회 방법을 알려주세요.")],
        response_model=Reply,
        max_output_tokens=300,
        prompt_version="test.prompt.v1",
        max_input_tokens=max_input_tokens,
    )


def completed(text: str = '{"text": "기간을 확인해 보세요."}') -> dict:
    return {
        "status": "completed",
        "model": "gpt-test-2026",
        "output": [
            {"type": "reasoning", "content": []},
            {"type": "message", "content": [{"type": "output_text", "text": text}]},
        ],
        "usage": {"input_tokens": 120, "output_tokens": 18},
    }


class Server:
    """요청을 기록하고 경로별로 정해 둔 응답을 돌려주는 가짜 OpenAI."""

    def __init__(self, **responses: httpx.Response | Exception) -> None:
        self.responses = responses
        self.requests: list[httpx.Request] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        outcome = self.responses[request.url.path.removeprefix("/v1/").replace("/", "_")]
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    def client(
        self, api_key: str | None = "sk-test", model: str | None = "gpt-test"
    ) -> OpenAIClient:
        return OpenAIClient(api_key, model, transport=httpx.MockTransport(self.handler))


def json_response(body: dict, status: int = 200, headers: dict | None = None) -> httpx.Response:
    return httpx.Response(status, json=body, headers=headers)


async def test_structured_output_is_requested_strictly_and_parsed() -> None:
    server = Server(responses=json_response(completed()))

    response = await server.client().generate(make_request())

    assert response.parsed == Reply(text="기간을 확인해 보세요.")
    assert (response.usage.input_tokens, response.usage.output_tokens) == (120, 18)
    assert response.model_id == "gpt-test-2026"
    [request] = server.requests
    body = json.loads(request.content)
    assert request.headers["Authorization"] == "Bearer sk-test"
    assert body["model"] == "gpt-test"
    assert body["store"] is True
    assert body["max_output_tokens"] == 300
    assert body["text"]["format"]["type"] == "json_schema"
    assert body["text"]["format"]["strict"] is True
    assert body["text"]["format"]["schema"] == Reply.model_json_schema()
    assert body["input"] == [{"role": "user", "content": "전자결재 문서 조회 방법을 알려주세요."}]


async def test_input_token_limit_is_checked_before_generation() -> None:
    server = Server(
        responses_input_tokens=json_response({"input_tokens": 500}),
        responses=json_response(completed()),
    )

    with pytest.raises(OutputRejected):
        await server.client().generate(make_request(max_input_tokens=100))

    assert [request.url.path for request in server.requests] == ["/v1/responses/input_tokens"]


async def test_input_within_limit_proceeds_to_generation() -> None:
    server = Server(
        responses_input_tokens=json_response({"input_tokens": 50}),
        responses=json_response(completed()),
    )

    response = await server.client().generate(make_request(max_input_tokens=100))

    assert response.parsed.text
    assert [request.url.path for request in server.requests] == [
        "/v1/responses/input_tokens",
        "/v1/responses",
    ]


@pytest.mark.parametrize("count", [{"input_tokens": 0}, {"input_tokens": "50"}, {}])
async def test_unusable_token_count_is_rejected(count) -> None:
    server = Server(
        responses_input_tokens=json_response(count), responses=json_response(completed())
    )

    with pytest.raises(OutputRejected):
        await server.client().generate(make_request(max_input_tokens=100))


async def test_rate_limit_is_transient_and_keeps_retry_after() -> None:
    server = Server(responses=json_response({}, 429, {"Retry-After": "7"}))

    with pytest.raises(TransientError) as caught:
        await server.client().generate(make_request())

    assert caught.value.retry_after == 7.0


async def test_server_error_and_connection_failure_are_transient() -> None:
    with pytest.raises(TransientError):
        await Server(responses=json_response({}, 503)).client().generate(make_request())
    with pytest.raises(TransientError):
        await Server(responses=httpx.ConnectError("refused")).client().generate(make_request())


async def test_client_error_is_rejected_not_retried() -> None:
    with pytest.raises(OutputRejected):
        await Server(responses=json_response({"error": {}}, 400)).client().generate(make_request())


@pytest.mark.parametrize(
    "body",
    [
        {**completed(), "status": "incomplete"},
        completed("not json"),
        completed('{"unexpected": 1}'),
        {"status": "completed", "model": "m", "output": [], "usage": {}},
    ],
)
async def test_unusable_model_output_is_rejected(body) -> None:
    with pytest.raises(OutputRejected):
        await Server(responses=json_response(body)).client().generate(make_request())


async def test_non_json_response_is_rejected() -> None:
    server = Server(responses=httpx.Response(200, text="<html>bad gateway</html>"))

    with pytest.raises(OutputRejected):
        await server.client().generate(make_request())


@pytest.mark.parametrize(
    ("key", "model"), [(None, "gpt-test"), ("sk-test", None), ("", "gpt-test")]
)
async def test_missing_key_or_model_fails_without_any_request(key, model) -> None:
    server = Server()

    with pytest.raises(ComponentNotReady):
        await server.client(key, model).generate(make_request())

    assert server.requests == []


async def test_response_schema_override_is_sent_and_parsing_still_uses_the_model() -> None:
    server = Server(responses=json_response(completed()))
    restricted = {
        "type": "object",
        "properties": {"text": {"type": "string", "enum": ["기간을 확인해 보세요."]}},
        "required": ["text"],
        "additionalProperties": False,
    }
    request = LLMRequest(
        messages=[LLMMessage(role="user", content="질문")],
        response_model=Reply,
        max_output_tokens=300,
        prompt_version="test.prompt.v1",
        response_schema=restricted,
    )

    response = await server.client().generate(request)

    [sent] = server.requests
    assert json.loads(sent.content)["text"]["format"]["schema"] == restricted
    assert response.parsed == Reply(text="기간을 확인해 보세요.")
