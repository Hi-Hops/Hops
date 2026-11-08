"""조회와 모델 관찰을 같은 실행 제한 안에서 검증한다. 실제 모델은 호출하지 않는다."""

import hashlib
from uuid import uuid4

import httpx
import pytest

from hops_ai.agents.inquiry.image_observations import ImageObservation, ImageObservations
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.harness.errors import RetrievalUnavailable, RunCancelled
from hops_ai.services.attachments import SpringImageReader
from hops_ai.services.llm.client import LLMResponse, LLMUsage
from hops_ai.services.tools.disabled import DisabledToolGateway
from hops_ai.settings import Settings
from hops_ai.supervisor.bootstrap import create_run_services
from hops_ai.supervisor.image_observations import observe_run_images
from hops_ai.supervisor.runtime import SupervisorRuntime
from tests.supervisor_fixtures import make_request

PNG = b"\x89PNG\r\n\x1a\nfixture"


class RecordingModel:
    def __init__(self, attachment_id: str) -> None:
        self.attachment_id = attachment_id
        self.requests = []

    async def generate(self, request):
        self.requests.append(request)
        return LLMResponse(
            parsed=ImageObservations(
                observations=[ImageObservation(attachmentId=self.attachment_id, text="오류 안내")]
            ),
            usage=LLMUsage(10, 4),
            model_id="fixture",
        )


def setup_execution():
    ids = [str(uuid4()), str(uuid4())]
    payload = make_request().model_dump(mode="json")
    payload["messages"][0]["imageAttachments"] = [
        {"attachmentId": image_id, "digest": hashlib.sha256(PNG).hexdigest()} for image_id in ids
    ]
    request = AiRunRequest.model_validate(payload)
    settings = Settings(
        _env_file=None,
        spring_base_url="https://spring.example.test",
        spring_service_token="A" * 43,
    )
    model = RecordingModel(ids[0])
    services = create_run_services(
        request,
        settings=settings,
        access_scope_ref="fixture",
        llm=model,
        tools=DisabledToolGateway(),
    )
    return ids, settings, model, SupervisorRuntime(request, services)


async def test_all_images_and_text_reach_model_under_shared_budget() -> None:
    ids, settings, model, runtime = setup_execution()
    reader = SpringImageReader(
        settings,
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, content=PNG, headers={"content-type": "image/png"})
        ),
    )

    observations = await runtime.run(observe_run_images(runtime, reader))

    assert [item.attachmentId for item in observations] == [ids[0]]
    assert len(model.requests) == 1
    messages = model.requests[0].messages
    assert len(messages) == 3
    assert all(runtime.request.messages[0].content in message.content for message in messages[1:])
    assert all(message.images[0].data == PNG for message in messages[1:])
    budget = runtime.services.runtime.budget
    assert (budget.tool_calls_used, budget.llm_calls_used) == (2, 1)
    assert (budget.input_tokens_used, budget.output_tokens_used) == (10, 4)


async def test_second_image_failure_does_not_start_model() -> None:
    ids, settings, model, runtime = setup_execution()

    def handle(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith(ids[0]):
            return httpx.Response(200, content=PNG, headers={"content-type": "image/png"})
        return httpx.Response(503)

    reader = SpringImageReader(settings, transport=httpx.MockTransport(handle))
    with pytest.raises(RetrievalUnavailable):
        await runtime.run(observe_run_images(runtime, reader))
    assert model.requests == []
    assert runtime.services.runtime.budget.tool_calls_used == 2
    assert runtime.services.runtime.budget.llm_calls_used == 0


async def test_cancel_during_image_read_stops_before_model() -> None:
    _, settings, model, runtime = setup_execution()

    def cancel(_: httpx.Request) -> httpx.Response:
        runtime.services.runtime.cancel()
        return httpx.Response(200, content=PNG, headers={"content-type": "image/png"})

    reader = SpringImageReader(settings, transport=httpx.MockTransport(cancel))
    with pytest.raises(RunCancelled):
        await runtime.run(observe_run_images(runtime, reader))
    assert model.requests == []
