"""실제 이미지·네트워크 없이 내부 이미지 조회의 권한과 무결성을 확인한다."""

import hashlib
from uuid import uuid4

import httpx
import pytest

from hops_ai.contracts.wire import AiRunRequest, InputImageRef
from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import PolicyDenied, RetrievalUnavailable
from hops_ai.services.attachments import MAX_IMAGE_BYTES, SpringImageReader, read_run_images
from hops_ai.settings import Settings
from tests.supervisor_fixtures import make_request

PNG = b"\x89PNG\r\n\x1a\nfixture"
TOKEN = "A" * 43


def context_and_settings() -> tuple[RunContext, Settings]:
    settings = Settings(
        _env_file=None,
        spring_base_url="https://spring.example.test",
        spring_service_token=TOKEN,
    )
    return RunContext.create(
        run_id=str(uuid4()),
        inquiry_id=str(uuid4()),
        inquiry_version=2,
        access_scope_ref="fixture",
        settings=settings,
    ), settings


def ref(data: bytes = PNG) -> InputImageRef:
    return InputImageRef(attachmentId=str(uuid4()), digest=hashlib.sha256(data).hexdigest())


async def test_reads_only_expected_digest_with_service_auth() -> None:
    context, settings = context_and_settings()
    image = ref()

    def handle(request: httpx.Request) -> httpx.Response:
        assert (
            request.url.path
            == f"/internal/v2/runs/{context.run_id}/image-attachments/{image.attachmentId}"
        )
        assert request.url.params["inquiryVersion"] == "2"
        assert request.headers["authorization"] == "Bearer " + TOKEN
        return httpx.Response(200, content=PNG, headers={"content-type": "image/png"})

    reader = SpringImageReader(settings, transport=httpx.MockTransport(handle))
    assert await reader.read(context, image) == PNG
    assert context.budget.tool_calls_used == 1


@pytest.mark.parametrize("status", [401, 403])
async def test_denied_access_is_not_silently_replaced(status: int) -> None:
    context, settings = context_and_settings()
    reader = SpringImageReader(
        settings, transport=httpx.MockTransport(lambda _: httpx.Response(status))
    )
    with pytest.raises(PolicyDenied):
        await reader.read(context, ref())


@pytest.mark.parametrize(
    ("status", "content_type", "content"),
    [
        (404, "image/png", PNG),
        (200, "text/html", PNG),
        (200, "image/png", b"invalid"),
        (200, "image/png", PNG + b"different"),
        (200, "image/png", b"x" * (MAX_IMAGE_BYTES + 1)),
    ],
)
async def test_invalid_or_unavailable_image_is_failure(
    status: int, content_type: str, content: bytes
) -> None:
    context, settings = context_and_settings()
    reader = SpringImageReader(
        settings,
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                status, content=content, headers={"content-type": content_type}
            )
        ),
    )
    with pytest.raises(RetrievalUnavailable):
        await reader.read(context, ref())


async def test_multiple_images_are_loaded_as_one_run_input() -> None:
    first, second = ref(), ref()
    payload = make_request().model_dump(mode="json")
    payload["messages"][0]["imageAttachments"] = [
        first.model_dump(mode="json"),
        second.model_dump(mode="json"),
    ]
    request = AiRunRequest.model_validate(payload)
    _, settings = context_and_settings()
    context = RunContext.create(
        run_id=request.runId,
        inquiry_id=request.inquiryId,
        inquiry_version=request.inquiryVersion,
        access_scope_ref="fixture",
        settings=settings,
    )
    calls = 0

    def handle(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, content=PNG, headers={"content-type": "image/png"})

    images = await read_run_images(
        request, context, SpringImageReader(settings, transport=httpx.MockTransport(handle))
    )
    assert list(images) == [first.attachmentId, second.attachmentId]
    assert all(image.data == PNG for image in images.values())
    assert calls == 2


async def test_image_failure_does_not_return_partial_run_input() -> None:
    first, second = ref(), ref()
    payload = make_request().model_dump(mode="json")
    payload["messages"][0]["imageAttachments"] = [
        first.model_dump(mode="json"),
        second.model_dump(mode="json"),
    ]
    request = AiRunRequest.model_validate(payload)
    _, settings = context_and_settings()
    context = RunContext.create(
        run_id=request.runId,
        inquiry_id=request.inquiryId,
        inquiry_version=request.inquiryVersion,
        access_scope_ref="fixture",
        settings=settings,
    )

    def handle(http_request: httpx.Request) -> httpx.Response:
        if http_request.url.path.endswith(first.attachmentId):
            return httpx.Response(200, content=PNG, headers={"content-type": "image/png"})
        return httpx.Response(503)

    reader = SpringImageReader(settings, transport=httpx.MockTransport(handle))
    with pytest.raises(RetrievalUnavailable):
        await read_run_images(request, context, reader)
