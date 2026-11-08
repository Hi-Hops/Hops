"""이미지 관찰이 명확한 문의 문제와 질문에만 연결되는지 확인한다."""

from uuid import uuid4

import pytest

from hops_ai.agents.inquiry.image_observations import ImageObservation
from hops_ai.contracts.wire import AiResult, AiRunRequest, QuestionProposal
from hops_ai.harness.errors import OutputRejected
from hops_ai.supervisor.image_questions import bind_image_questions
from tests.supervisor_fixtures import PROBLEM, make_problem, make_request


def fixture(*, problem_ids=None, question_count=0):
    payload = make_request(question_count=question_count).model_dump(mode="json")
    ids = [str(uuid4()), str(uuid4())]
    message = payload["messages"][0]
    if problem_ids is not None:
        message["problemIds"] = problem_ids
    message["imageAttachments"] = [
        {"attachmentId": image_id, "digest": "a" * 64} for image_id in ids
    ]
    request = AiRunRequest.model_validate(payload)
    question = QuestionProposal(
        problemRef=PROBLEM,
        bundleRef="bundle-1",
        text="어느 화면에서 문제가 발생했나요?",
        requestedFields=["screen"],
    )
    observations = [
        ImageObservation(attachmentId=image_id, text=f"오류 안내 {index}")
        for index, image_id in enumerate(ids, 1)
    ]
    return request, question, observations


def test_two_images_share_existing_question_bundle_and_keep_source() -> None:
    request, question, observations = fixture()
    bound = bind_image_questions(request, [question], observations)

    assert not bound.unlinked
    assert len(bound.questions) == 1
    assert bound.questions[0].bundleRef == question.bundleRef
    assert bound.questions[0].requestedFields == question.requestedFields
    assert question.text in bound.questions[0].text
    for item, source in zip(bound.observations, observations, strict=True):
        assert item.attachmentId == source.attachmentId
        assert item.messageId == request.messages[0].id
        assert item.messageRevision == request.messages[0].revision
        assert item.problemRef == PROBLEM
        assert item.questionRef == question.bundleRef
        assert item.text in bound.questions[0].text
    assert question.text == "어느 화면에서 문제가 발생했나요?"
    result = AiResult.model_validate(
        {
            "contractVersion": request.contractVersion,
            "runId": request.runId,
            "kind": request.kind,
            "inquiryId": request.inquiryId,
            "inquiryVersion": request.inquiryVersion,
            "status": "SUCCEEDED",
            "answer": bound.questions[0].text,
            "problemProposals": [make_problem().model_dump()],
            "questions": [item.model_dump() for item in bound.questions],
            "guide": None,
            "ticketProposals": [],
            "caseProposal": None,
            "evidence": [],
            "error": None,
            "imageObservations": [item.model_dump() for item in bound.observations],
        }
    )
    result.validate_image_observations_for(request)


@pytest.mark.parametrize("problem_ids", [[], [PROBLEM, str(uuid4())]])
def test_missing_or_ambiguous_problem_keeps_observations_unlinked(problem_ids) -> None:
    request, question, observations = fixture(problem_ids=problem_ids)
    bound = bind_image_questions(request, [question], observations)
    assert bound.questions == (question,)
    assert bound.observations == ()
    assert bound.unlinked == tuple(observations)


def test_question_limit_and_missing_question_keep_observations_unlinked() -> None:
    request, question, observations = fixture(question_count=5)
    assert bind_image_questions(request, [question], observations).unlinked == tuple(observations)
    request, _, observations = fixture()
    assert bind_image_questions(request, [], observations).unlinked == tuple(observations)


def test_question_too_long_is_not_silently_truncated() -> None:
    request, question, observations = fixture()
    question = question.model_copy(update={"text": "가" * 4000})
    bound = bind_image_questions(request, [question], observations)
    assert bound.questions == (question,)
    assert bound.unlinked == tuple(observations)


def test_unknown_image_and_duplicate_observation_are_rejected() -> None:
    request, question, observations = fixture()
    unknown = ImageObservation(attachmentId=str(uuid4()), text="오류 안내")
    for invalid in ([unknown], [observations[0], observations[0]]):
        with pytest.raises(OutputRejected):
            bind_image_questions(request, [question], invalid)


def test_duplicate_problem_questions_are_rejected() -> None:
    request, question, observations = fixture()
    with pytest.raises(OutputRejected):
        bind_image_questions(request, [question, question], observations)
