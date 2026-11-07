"""OpenAI 어댑터를 가짜 HTTP 전송으로 확인한다. 실제 OpenAI는 호출하지 않는다."""

import json
from contextlib import contextmanager

import httpx
import pytest
from pydantic import BaseModel

from hops_ai.harness.errors import (
    ComponentNotReady,
    MalformedOutput,
    OutputRejected,
    TransientError,
)
from hops_ai.services.llm.client import LLMImage, LLMMessage, LLMRequest
from hops_ai.services.llm.openai import OpenAIClient
from hops_ai.services.tracing import RunTracing, TracedLLMClient


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


async def test_multiple_images_and_text_are_included_in_token_count_and_model_request() -> None:
    server = Server(
        responses_input_tokens=json_response({"input_tokens": 50}),
        responses=json_response(completed()),
    )
    request = LLMRequest(
        messages=[
            LLMMessage(
                "user",
                "오류 화면",
                (LLMImage("image/png", b"first"), LLMImage("image/jpeg", b"second")),
            )
        ],
        response_model=Reply,
        max_output_tokens=300,
        prompt_version="test.image.v1",
        max_input_tokens=100,
    )

    await server.client().generate(request)

    counted, generated = [json.loads(item.content) for item in server.requests]
    expected = [
        {
            "role": "user",
            "content": [
                {"type": "input_text", "text": "오류 화면"},
                {"type": "input_image", "image_url": "data:image/png;base64,Zmlyc3Q="},
                {"type": "input_image", "image_url": "data:image/jpeg;base64,c2Vjb25k"},
            ],
        }
    ]
    assert counted["input"] == generated["input"] == expected
    assert generated["store"] is False
    assert "first" not in repr(request)
    assert "second" not in repr(request)


async def test_image_on_system_message_is_rejected_before_network_request() -> None:
    server = Server()
    request = LLMRequest(
        messages=[LLMMessage("system", "지시", (LLMImage("image/png", b"image"),))],
        response_model=Reply,
        max_output_tokens=300,
        prompt_version="test.image.v1",
    )
    with pytest.raises(OutputRejected):
        await server.client().generate(request)
    assert server.requests == []


async def test_image_bytes_are_not_recorded_even_when_trace_content_is_enabled() -> None:
    class TraceClient:
        input: dict | None = None
        output: dict | None = None

        def update(self, **kwargs):
            self.output = kwargs.get("output")

        @contextmanager
        def start_as_current_observation(self, *, input: dict, **_kwargs):
            self.input = input
            yield self

    trace = TraceClient()
    server = Server(responses=json_response(completed()))
    request = LLMRequest(
        messages=[LLMMessage("user", "오류 화면", (LLMImage("image/png", b"private-image"),))],
        response_model=Reply,
        max_output_tokens=300,
        prompt_version="test.image.v1",
    )
    await TracedLLMClient(server.client(), RunTracing(trace, capture_content=True)).generate(
        request
    )
    assert trace.input is not None
    assert "private-image" not in repr(trace.input)
    assert "cHJpdmF0ZS1pbWFnZQ==" not in repr(trace.input)
    assert "messages" not in trace.input
    assert trace.output == {"schema": "Reply", "parsed": True}


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


# --- 실패 유형 구분: 응답 본문·모델이 낸 값은 사유에 넣지 않는다 ---


class Strict(BaseModel):
    count: int
    names: list[str]


def strict_request() -> LLMRequest:
    return LLMRequest(
        messages=[LLMMessage(role="user", content="질문")],
        response_model=Strict,
        max_output_tokens=300,
        prompt_version="test.prompt.v1",
    )


async def rejection(body: dict, request: LLMRequest | None = None) -> OutputRejected:
    server = Server(responses=json_response(body))
    with pytest.raises(OutputRejected) as caught:
        await server.client().generate(request or make_request())
    return caught.value


async def test_an_incomplete_response_names_its_status_and_reason() -> None:
    body = {
        **completed(),
        "status": "incomplete",
        "incomplete_details": {"reason": "max_output_tokens"},
    }

    error = await rejection(body)

    assert "완료되지 않았" in error.detail
    assert "status=incomplete" in error.detail and "reason=max_output_tokens" in error.detail


async def test_an_incomplete_response_without_a_reason_is_still_reported() -> None:
    error = await rejection({**completed(), "status": "incomplete"})

    assert "완료되지 않았" in error.detail and "reason=NoneType" in error.detail


async def test_a_refusal_is_reported_as_a_refusal() -> None:
    body = completed()
    body["output"] = [
        {"type": "message", "content": [{"type": "refusal", "refusal": "답할 수 없습니다"}]}
    ]

    error = await rejection(body)

    assert error.detail == "모델이 응답을 거부했습니다"


async def test_a_schema_violation_lists_field_and_error_type_but_not_the_values() -> None:
    secret = "홍길동의 비밀 값"
    body = completed(json.dumps({"count": secret, "names": [1, secret]}, ensure_ascii=False))

    error = await rejection(body, strict_request())

    assert "형식을 어겼" in error.detail
    assert "count:" in error.detail and "names.0:" in error.detail
    assert secret not in error.detail and secret not in str(error)


async def test_invalid_json_is_reported_as_a_format_violation() -> None:
    error = await rejection(completed("not json"))

    assert "형식을 어겼" in error.detail and "json_invalid" in error.detail


async def test_many_violations_are_counted_not_listed_in_full() -> None:
    body = completed(json.dumps({"count": "x", "names": [1, 2, 3, 4, 5, 6, 7]}))

    error = await rejection(body, strict_request())

    assert "외 " in error.detail and "건" in error.detail


async def test_a_response_with_missing_parts_is_reported_as_a_structure_problem() -> None:
    error = await rejection({"status": "completed", "model": "m", "output": [], "usage": {}})

    assert error.detail == "OpenAI 응답 구조를 읽을 수 없습니다"


async def test_the_contract_error_still_uses_the_fixed_message_not_the_detail() -> None:
    from hops_ai.harness.errors import describe_failure

    error = await rejection({**completed(), "status": "incomplete"})

    failure = describe_failure(error)
    assert failure.error.message == OutputRejected.safe_message
    assert "incomplete" not in failure.error.message


async def test_only_a_format_violation_is_marked_as_retriable() -> None:
    violation = await rejection(completed('{"unexpected": 1}'))
    refusal_body = completed()
    refusal_body["output"] = [{"type": "message", "content": [{"type": "refusal"}]}]
    refusal = await rejection(refusal_body)
    incomplete = await rejection({**completed(), "status": "incomplete"})
    structure = await rejection({"status": "completed", "model": "m", "output": [], "usage": {}})

    assert isinstance(violation, MalformedOutput)
    for other in (refusal, incomplete, structure):
        assert type(other) is OutputRejected
