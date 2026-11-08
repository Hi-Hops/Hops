from uuid import uuid4

import pytest

from hops_ai.agents.inquiry.image_observations import (
    ImageObservation,
    ImageObservations,
    observe_images,
)
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.harness.errors import InputRejected, OutputRejected
from hops_ai.services.llm.client import LLMImage, LLMResponse, LLMUsage
from tests.supervisor_fixtures import make_request


class RecordingLLM:
    def __init__(self, observations: list[ImageObservation]) -> None:
        self.observations = observations
        self.request = None

    async def generate(self, request):
        self.request = request
        return LLMResponse(
            parsed=ImageObservations(observations=self.observations),
            usage=LLMUsage(10, 10),
            model_id="fixture",
        )


def image_request() -> tuple[AiRunRequest, dict[str, LLMImage]]:
    payload = make_request().model_dump(mode="json")
    ids = [str(uuid4()), str(uuid4())]
    payload["messages"][0]["imageAttachments"] = [
        {"attachmentId": image_id, "digest": "a" * 64} for image_id in ids
    ]
    images = {image_id: LLMImage(media_type="image/png", data=b"fixture") for image_id in ids}
    return AiRunRequest.model_validate(payload), images


async def test_image_observations_are_proposals_and_use_both_images() -> None:
    request, images = image_request()
    observations = [
        ImageObservation(attachmentId=image_id, text="오류 안내가 보입니다") for image_id in images
    ]
    llm = RecordingLLM(observations)
    assert await observe_images(request, images, llm) == observations
    assert llm.request is not None
    assert len(llm.request.messages) == 3
    assert [message.images[0] for message in llm.request.messages[1:]] == list(images.values())
    assert all(
        image_id in message.content
        for image_id, message in zip(images, llm.request.messages[1:], strict=True)
    )


async def test_missing_image_fails_before_model_call() -> None:
    request, images = image_request()
    images.pop(next(iter(images)))
    llm = RecordingLLM([])
    with pytest.raises(InputRejected):
        await observe_images(request, images, llm)
    assert llm.request is None


@pytest.mark.parametrize("bad", ["duplicate", "unknown", "empty"])
async def test_untrusted_image_observation_is_rejected(bad: str) -> None:
    request, images = image_request()
    image_id = next(iter(images)) if bad != "unknown" else str(uuid4())
    item = ImageObservation(attachmentId=image_id, text=" " if bad == "empty" else "오류 안내")
    llm = RecordingLLM([item, item] if bad == "duplicate" else [item])
    with pytest.raises(OutputRejected):
        await observe_images(request, images, llm)
