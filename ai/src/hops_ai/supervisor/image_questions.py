"""이미지 관찰을 출처가 분명한 문제의 질문 묶음에만 연결한다."""

from dataclasses import dataclass

from hops_ai.agents.inquiry.image_observations import ImageObservation
from hops_ai.contracts.types import QUESTION_LIMIT
from hops_ai.contracts.wire import (
    AiRunRequest,
    ImageObservationProposal,
    ProblemProposal,
    QuestionProposal,
)
from hops_ai.harness.errors import OutputRejected


@dataclass(frozen=True)
class BoundImageQuestions:
    questions: tuple[QuestionProposal, ...]
    observations: tuple[ImageObservationProposal, ...]
    unlinked: tuple[ImageObservation, ...]


def bind_image_questions(
    request: AiRunRequest,
    questions: list[QuestionProposal],
    observations: list[ImageObservation],
    *,
    proposals: list[ProblemProposal] | None = None,
) -> BoundImageQuestions:
    """문제를 확인할 수 있으면 질문 또는 미확인 관찰로 연결한다.

    연결하지 못한 관찰은 버리지 않고 unlinked로 돌려준다. 호출자는 최종화 전에 이를
    안전하게 처리해야 한다. 반환값은 아직 사용자 확인이나 담당자 공개를 뜻하지 않는다.
    """
    by_attachment = {
        image.attachmentId: message
        for message in request.messages
        for image in message.imageAttachments
    }
    observation_ids = [item.attachmentId for item in observations]
    if len(observation_ids) != len(set(observation_ids)) or not set(observation_ids).issubset(
        by_attachment
    ):
        raise OutputRejected("관찰의 이미지 출처를 확인할 수 없습니다")
    counts = {problem.id: problem.questionCount for problem in request.problems}
    existing_problem_ids = set(counts)
    new_problem = None
    if not request.problems and proposals is not None and len(proposals) == 1:
        only = proposals[0]
        if only.problemRef.startswith("new:"):
            new_problem = only
    by_problem = {question.problemRef: index for index, question in enumerate(questions)}
    if len(by_problem) != len(questions):
        raise OutputRejected("문제마다 하나의 질문 묶음만 허용합니다")

    updated = list(questions)
    linked: list[ImageObservationProposal] = []
    unlinked: list[ImageObservation] = []
    for observation in observations:
        source = by_attachment[observation.attachmentId]
        problem_ids = set(source.problemIds)
        problem = next(iter(problem_ids)) if len(problem_ids) == 1 else None
        if (
            not problem_ids
            and new_problem is not None
            and any(source.id in fact.sourceMessageIds for fact in new_problem.facts)
        ):
            problem = new_problem.problemRef
            counts[problem] = 0
        if (
            problem is None
            or problem not in counts
            or (
                not problem.startswith("new:")
                and request.problemIds
                and problem not in request.problemIds
            )
        ):
            unlinked.append(observation)
            continue
        question_ref = None
        if counts[problem] < QUESTION_LIMIT and problem in by_problem:
            index = by_problem[problem]
            question = updated[index]
            text = f"{question.text}\n\n화면에 ‘{observation.text}’가 보이나요?"
            if len(text) <= 4000:
                updated[index] = QuestionProposal.model_validate(
                    {**question.model_dump(), "text": text}
                )
                question_ref = question.bundleRef
        # 새 문제는 질문 없이 저장되지 않을 수 있어 실제 ID가 보장되는 기존 문제만 보류한다.
        if question_ref is None and problem not in existing_problem_ids:
            unlinked.append(observation)
            continue
        linked.append(
            ImageObservationProposal(
                observationRef=f"img-{observation.attachmentId}",
                attachmentId=observation.attachmentId,
                messageId=source.id,
                messageRevision=source.revision,
                problemRef=problem,
                text=observation.text,
                questionRef=question_ref,
            )
        )
    return BoundImageQuestions(tuple(updated), tuple(linked), tuple(unlinked))
