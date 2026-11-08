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


def test_question_limit_and_missing_question_preserve_unconfirmed_observations() -> None:
    request, question, observations = fixture(question_count=5)
    bound = bind_image_questions(request, [question], observations)
    assert bound.questions == (question,)
    assert not bound.unlinked
    assert all(item.questionRef is None for item in bound.observations)
    request, _, observations = fixture()
    bound = bind_image_questions(request, [], observations)
    assert not bound.unlinked
    assert all(item.questionRef is None for item in bound.observations)


def test_question_too_long_is_preserved_without_confirmation() -> None:
    request, question, observations = fixture()
    question = question.model_copy(update={"text": "가" * 4000})
    bound = bind_image_questions(request, [question], observations)
    assert bound.questions == (question,)
    assert not bound.unlinked
    assert all(item.questionRef is None for item in bound.observations)


def test_overflow_keeps_each_observation_in_the_same_problem() -> None:
    request, question, observations = fixture()
    question = question.model_copy(update={"text": "가" * 3970})
    bound = bind_image_questions(request, [question], observations)
    assert not bound.unlinked
    assert len(bound.observations) == 2
    assert bound.observations[0].questionRef == question.bundleRef
    assert bound.observations[1].questionRef is None
    assert all(item.problemRef == PROBLEM for item in bound.observations)


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


def test_image_for_untargeted_problem_is_not_used_in_targeted_run() -> None:
    request, _, observations = fixture()
    payload = request.model_dump(mode="json")
    other = str(uuid4())
    second = dict(payload["problems"][0], id=other)
    payload["problems"].append(second)
    payload["messages"][0]["problemIds"] = [other]
    request = AiRunRequest.model_validate(payload)
    bound = bind_image_questions(request, [], observations)
    assert bound.observations == ()
    assert bound.unlinked == tuple(observations)


def test_explicit_image_target_takes_precedence_over_ambiguous_message() -> None:
    request, question, observations = fixture()
    payload = request.model_dump(mode="json")
    other = str(uuid4())
    payload["problems"].append(dict(payload["problems"][0], id=other))
    payload["problemIds"] = [PROBLEM, other]
    payload["messages"][0]["problemIds"] = [PROBLEM, other]
    payload["messages"][0]["imageAttachments"][0]["analysisProblemIds"] = [PROBLEM]
    payload["messages"][0]["imageAttachments"][1]["analysisProblemIds"] = [other]
    request = AiRunRequest.model_validate(payload)
    other_question = question.model_copy(update={"problemRef": other, "bundleRef": "bundle-2"})
    bound = bind_image_questions(request, [question, other_question], observations)
    assert not bound.unlinked
    assert [item.problemRef for item in bound.observations] == [PROBLEM, other]
    assert [item.questionRef for item in bound.observations] == ["bundle-1", "bundle-2"]


@pytest.mark.parametrize("invalid", ["duplicate", "unknown", "outside-run"])
def test_image_analysis_targets_are_validated_against_input_scope(invalid: str) -> None:
    request, _, _ = fixture()
    payload = request.model_dump(mode="json")
    other = str(uuid4())
    payload["problems"].append(dict(payload["problems"][0], id=other))
    selected = {"duplicate": [PROBLEM, PROBLEM], "unknown": [str(uuid4())], "outside-run": [other]}
    payload["messages"][0]["imageAttachments"][0]["analysisProblemIds"] = selected[invalid]
    with pytest.raises(ValueError):
        AiRunRequest.model_validate(payload)


def test_observation_cannot_use_other_problem_than_image_selection() -> None:
    request, _, observations = fixture()
    payload = request.model_dump(mode="json")
    other = str(uuid4())
    payload["problems"].append(dict(payload["problems"][0], id=other))
    payload["problemIds"] = [PROBLEM, other]
    payload["messages"][0]["imageAttachments"][0]["analysisProblemIds"] = [PROBLEM]
    request = AiRunRequest.model_validate(payload)
    result = AiResult.model_validate(
        {
            "contractVersion": request.contractVersion,
            "runId": request.runId,
            "kind": request.kind,
            "inquiryId": request.inquiryId,
            "inquiryVersion": request.inquiryVersion,
            "status": "SUCCEEDED",
            "answer": "확인해 주세요.",
            "problemProposals": [],
            "questions": [],
            "guide": None,
            "ticketProposals": [],
            "caseProposal": None,
            "evidence": [],
            "error": None,
            "imageObservations": [
                {
                    "observationRef": "img-1",
                    "attachmentId": observations[0].attachmentId,
                    "messageId": request.messages[0].id,
                    "messageRevision": request.messages[0].revision,
                    "problemRef": other,
                    "text": "오류 안내",
                    "questionRef": None,
                }
            ],
        }
    )
    with pytest.raises(ValueError):
        result.validate_image_observations_for(request)


def test_first_inquiry_binds_only_single_new_problem_citing_image_message() -> None:
    request, question, observations = fixture(problem_ids=[])
    payload = request.model_dump(mode="json")
    payload["problems"] = []
    payload["problemIds"] = []
    request = AiRunRequest.model_validate(payload)
    proposal = make_problem("new:one")
    question = question.model_copy(update={"problemRef": "new:one"})

    bound = bind_image_questions(request, [question], observations, proposals=[proposal])

    assert not bound.unlinked
    assert all(item.problemRef == "new:one" for item in bound.observations)
    assert all(item.text in bound.questions[0].text for item in bound.observations)
    result = AiResult.model_validate(
        {
            "contractVersion": request.contractVersion,
            "runId": request.runId,
            "kind": request.kind,
            "inquiryId": request.inquiryId,
            "inquiryVersion": request.inquiryVersion,
            "status": "SUCCEEDED",
            "answer": bound.questions[0].text,
            "problemProposals": [proposal.model_dump()],
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


@pytest.mark.parametrize("case", ["no-source", "two-problems", "existing-problem"])
def test_first_inquiry_never_guesses_unconfirmed_problem(case: str) -> None:
    request, question, observations = fixture(problem_ids=[])
    payload = request.model_dump(mode="json")
    if case != "existing-problem":
        payload["problems"] = []
        payload["problemIds"] = []
    request = AiRunRequest.model_validate(payload)
    proposal = make_problem("new:one")
    if case == "no-source":
        proposal = proposal.model_copy(update={"facts": []})
    proposals = [proposal, make_problem("new:two")] if case == "two-problems" else [proposal]
    question = question.model_copy(update={"problemRef": "new:one"})
    bound = bind_image_questions(request, [question], observations, proposals=proposals)
    assert bound.observations == ()
    assert bound.unlinked == tuple(observations)
