"""실행에 고정된 이미지 참조를 서비스 인증으로 읽는다. 이미지 내용은 기록하지 않는다."""

import hashlib

import httpx

from hops_ai.contracts.wire import AiRunRequest, InputImageRef
from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import (
    ComponentNotReady,
    InputRejected,
    PolicyDenied,
    RetrievalUnavailable,
)
from hops_ai.services.llm.client import LLMImage
from hops_ai.settings import Settings

MAX_IMAGE_BYTES = 8 * 1024 * 1024
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class SpringImageReader:
    def __init__(self, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None):
        if not settings.spring_base_url or not settings.spring_service_token:
            raise ComponentNotReady(
                "Spring 이미지 조회 주소·인증",
                missing="HOPS_AI_SPRING_BASE_URL, SPRING_SERVICE_TOKEN",
            )
        self._settings = settings
        self._transport = transport

    async def read(self, context: RunContext, image: InputImageRef) -> bytes:
        context.ensure_active()
        context.budget.start_tool_call()
        timeout = min(
            self._settings.knowledge_http_timeout_seconds, context.budget.remaining_seconds()
        )
        context.ensure_active()
        path = f"/internal/v2/runs/{context.run_id}/image-attachments/{image.attachmentId}"
        try:
            async with httpx.AsyncClient(
                base_url=self._settings.spring_base_url,
                transport=self._transport,
                follow_redirects=False,
                trust_env=False,
                timeout=timeout,
                headers={
                    "Authorization": "Bearer "
                    + self._settings.spring_service_token.get_secret_value()
                },
            ) as client:
                async with client.stream(
                    "GET", path, params={"inquiryVersion": context.inquiry_version}
                ) as response:
                    if response.status_code in (401, 403):
                        raise PolicyDenied("이미지 조회 접근이 거부됐다")
                    if response.status_code != 200:
                        raise RetrievalUnavailable("이미지 조회에 실패했다")
                    if (
                        response.headers.get("content-type", "").split(";", 1)[0].strip()
                        != "image/png"
                    ):
                        raise RetrievalUnavailable("이미지 응답 형식이 올바르지 않다")
                    data = bytearray()
                    async for chunk in response.aiter_bytes():
                        context.ensure_active()
                        if len(chunk) > MAX_IMAGE_BYTES - len(data):
                            raise RetrievalUnavailable("이미지 크기가 허용 범위를 넘었다")
                        data.extend(chunk)
        except (httpx.TransportError, TimeoutError):
            raise RetrievalUnavailable("이미지 저장소에 연결하지 못했다") from None
        context.ensure_active()
        if not data.startswith(PNG_SIGNATURE) or hashlib.sha256(data).hexdigest() != image.digest:
            raise RetrievalUnavailable("이미지 무결성을 확인하지 못했다")
        return bytes(data)


async def read_run_images(
    request: AiRunRequest, context: RunContext, reader: SpringImageReader
) -> dict[str, LLMImage]:
    """입력 전체의 참조를 순서대로 확인한다. 하나라도 실패하면 일부 결과를 돌려주지 않는다."""
    if (
        request.kind != "INQUIRY_ANALYSIS"
        or request.runId != context.run_id
        or request.inquiryId != context.inquiry_id
        or request.inquiryVersion != context.inquiry_version
    ):
        raise InputRejected("이미지 조회의 실행 문맥이 일치하지 않습니다")
    images: dict[str, LLMImage] = {}
    for message in request.messages:
        for ref in message.imageAttachments:
            context.ensure_active()
            data = await reader.read(context, ref)
            images[ref.attachmentId] = LLMImage(media_type="image/png", data=data)
    return images
