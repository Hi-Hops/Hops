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
    """단일 문제의 출처 메시지와 질문이 모두 있을 때만 확인 문구를 더한다.

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
            or counts[problem] >= QUESTION_LIMIT
            or problem not in by_problem
        ):
            unlinked.append(observation)
            continue
        index = by_problem[problem]
        question = updated[index]
        text = f"{question.text}\n\n화면에 ‘{observation.text}’가 보이나요?"
        if len(text) > 4000:
            unlinked.append(observation)
            continue
        updated[index] = QuestionProposal.model_validate({**question.model_dump(), "text": text})
        linked.append(
            ImageObservationProposal(
                observationRef=f"img-{observation.attachmentId}",
                attachmentId=observation.attachmentId,
                messageId=source.id,
                messageRevision=source.revision,
                problemRef=problem,
                text=observation.text,
                questionRef=question.bundleRef,
            )
        )
    return BoundImageQuestions(tuple(updated), tuple(linked), tuple(unlinked))
