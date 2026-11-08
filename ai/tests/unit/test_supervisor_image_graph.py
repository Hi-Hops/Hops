"""실제 외부 호출 없이 이미지 실행과 최종화를 함께 검증한다."""

import hashlib
from dataclasses import replace
from uuid import uuid4

import httpx
import pytest

from hops_ai.agents.inquiry.image_observations import ImageObservation, ImageObservations
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import InquiryResult
from hops_ai.contracts.wire import AiRunRequest, QuestionProposal
from hops_ai.harness.errors import InputRejected
from hops_ai.services.attachments import SpringImageReader
from hops_ai.services.tracing import RunTracing
from hops_ai.settings import Settings
from hops_ai.supervisor.graph import Supervisor
from tests.supervisor_fixtures import make_problem, make_request
from tests.unit.test_supervisor_graph import InquiryStub, ScriptedLLM, decision, services_for

PNG = b"\x89PNG\r\n\x1a\nfixture"


def image_fixture(*, problem_ids=None):
    payload = make_request(question_count=4).model_dump(mode="json")
    ids = [str(uuid4()), str(uuid4())]
    message = payload["messages"][0]
    if problem_ids is not None:
        message["problemIds"] = problem_ids
    message["imageAttachments"] = [
        {"attachmentId": image_id, "digest": hashlib.sha256(PNG).hexdigest()} for image_id in ids
    ]
    return AiRunRequest.model_validate(payload), ids


def reader(handler):
    settings = Settings(
        _env_file=None,
        spring_base_url="https://spring.example.test",
        spring_service_token="A" * 43,
    )
    return SpringImageReader(settings, transport=httpx.MockTransport(handler))


def ok(_: httpx.Request) -> httpx.Response:
    return httpx.Response(200, content=PNG, headers={"content-type": "image/png"})


async def test_image_observations_flow_through_graph_and_question_finalization() -> None:
    request, ids = image_fixture()
    model = ScriptedLLM(
        ImageObservations(
            observations=[
                ImageObservation(attachmentId=image_id, text="오류 안내") for image_id in ids
            ]
        ),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("ASK_USER", summary="추가 확인이 필요합니다."),
    )
    services = services_for(request, model)
    result = await Supervisor(
        {AgentRole.INQUIRY: InquiryStub(ask=True)}, image_reader=reader(ok)
    ).analyze(request, services)

    assert result.status == "SUCCEEDED"
    assert len(result.imageObservations) == 2
    assert len(result.questions) == 1
    assert result.answer is not None and result.questions[0].text in result.answer
    assert all(item.text in result.questions[0].text for item in result.imageObservations)
    assert all(
        item.questionRef == result.questions[0].bundleRef for item in result.imageObservations
    )
    assert all(item.problemRef == request.problems[0].id for item in result.imageObservations)
    assert all("오류 안내" not in item.text for item in result.problemProposals[0].facts)
    result.validate_image_observations_for(request)
    assert (services.runtime.budget.llm_calls_used, services.runtime.budget.tool_calls_used) == (
        3,
        2,
    )


async def test_missing_image_fails_before_any_model_or_inquiry_call() -> None:
    request, ids = image_fixture()
    model = ScriptedLLM()
    inquiry = InquiryStub(ask=True)

    def fail_second(http_request: httpx.Request) -> httpx.Response:
        return httpx.Response(503) if http_request.url.path.endswith(ids[1]) else ok(http_request)

    services = services_for(request, model)
    result = await Supervisor(
        {AgentRole.INQUIRY: inquiry}, image_reader=reader(fail_second)
    ).analyze(request, services)
    assert result.status == "FAILED"
    assert result.error is not None and result.error.code == "SEARCH_UNAVAILABLE"
    assert result.answer is None and not result.imageObservations
    assert not model.requests and inquiry.calls == 0


async def test_ambiguous_image_fails_instead_of_silently_discarding_observation() -> None:
    request, ids = image_fixture(problem_ids=[])
    model = ScriptedLLM(
        ImageObservations(observations=[ImageObservation(attachmentId=ids[0], text="오류 안내")]),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("ASK_USER"),
    )
    result = await Supervisor(
        {AgentRole.INQUIRY: InquiryStub(ask=True)}, image_reader=reader(ok)
    ).analyze(request, services_for(request, model))
    assert result.status == "FAILED"
    assert result.error is not None and result.error.code == "INVALID_OUTPUT"
    assert result.answer is None and result.questions == result.imageObservations == []


async def test_first_inquiry_with_one_sourced_new_problem_produces_confirmation() -> None:
    request, ids = image_fixture(problem_ids=[])
    payload = request.model_dump(mode="json")
    payload["problemIds"] = []
    payload["problems"] = []
    request = AiRunRequest.model_validate(payload)

    class NewInquiry:
        role = AgentRole.INQUIRY

        async def run(self, role_request, services):
            return InquiryResult(
                invocationId=role_request.invocation_id,
                problems=[make_problem("new:one")],
                questions=[
                    QuestionProposal(
                        problemRef="new:one",
                        bundleRef="first-question",
                        text="어느 화면인가요?",
                        requestedFields=["taskOrScreen"],
                    )
                ],
            )

    model = ScriptedLLM(
        ImageObservations(observations=[ImageObservation(attachmentId=ids[0], text="오류 안내")]),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("ASK_USER"),
    )
    result = await Supervisor({AgentRole.INQUIRY: NewInquiry()}, image_reader=reader(ok)).analyze(
        request, services_for(request, model)
    )
    assert result.status == "SUCCEEDED"
    assert result.imageObservations[0].problemRef == "new:one"
    assert result.imageObservations[0].questionRef == "first-question"
    assert result.answer is not None and "오류 안내" in result.answer
    result.validate_image_observations_for(request)


async def test_image_model_call_shares_budget_with_supervisor() -> None:
    request, ids = image_fixture()
    payload = request.model_dump(mode="json")
    payload["limits"]["maxModelCalls"] = 1
    request = AiRunRequest.model_validate(payload)
    model = ScriptedLLM(
        ImageObservations(observations=[ImageObservation(attachmentId=ids[0], text="오류 안내")])
    )
    result = await Supervisor(
        {AgentRole.INQUIRY: InquiryStub(ask=True)}, image_reader=reader(ok)
    ).analyze(request, services_for(request, model))
    assert result.status == "FAILED"
    assert result.error is not None and result.error.code == "BUDGET_EXCEEDED"
    assert result.answer is None and result.imageObservations == []
    assert len(model.requests) == 1


async def test_cancellation_during_image_read_produces_superseded_result() -> None:
    request, _ = image_fixture()
    model = ScriptedLLM()
    services = services_for(request, model)

    def cancel(http_request: httpx.Request) -> httpx.Response:
        services.runtime.cancel()
        return ok(http_request)

    result = await Supervisor(image_reader=reader(cancel)).analyze(request, services)
    assert result.status == "SUPERSEDED"
    assert result.error is not None and result.error.code == "SUPERSEDED"
    assert result.answer is None and not model.requests


@pytest.mark.parametrize("unsafe", ["missing-reader", "content-tracing", "ticket-tools"])
async def test_image_run_rejects_unsafe_service_configuration(unsafe: str) -> None:
    request, _ = image_fixture()
    services = services_for(request, ScriptedLLM())
    image_reader = reader(ok)
    if unsafe == "missing-reader":
        image_reader = None
    elif unsafe == "content-tracing":
        services = replace(services, tracing=RunTracing(capture_content=True))
    else:
        services = replace(services, ticket_tools=object())
    with pytest.raises(InputRejected):
        await Supervisor(image_reader=image_reader).analyze(request, services)
