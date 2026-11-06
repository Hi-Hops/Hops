"""승인 본문이 길 때의 안내. 최종 답변은 4,000자 이내여야 해서, 길면 실행이 오류로 끝나지 않고
안내 없이 질문이나 담당자 검토로 간다. 모델은 Fake다."""

import pytest

from hops_ai.agents.inquiry.extraction import InquiryExtraction
from hops_ai.agents.knowledge.agent import MAX_GUIDE_CHARS, _guide
from hops_ai.contracts.wire import ProblemProposal
from tests.supervisor_fixtures import PROBLEM, make_details
from tests.unit.test_inquiry_agent import first_message_request
from tests.unit.test_knowledge_agent import RecordingSource
from tests.unit.test_knowledge_assessment import GUIDE, STAFF, draft, hit
from tests.unit.test_problem_graph import HOW_TO_TEXT, how_to, run

SUFFIX = "\n\n참고 자료: " + GUIDE.title


def target() -> ProblemProposal:
    return ProblemProposal(
        problemRef=PROBLEM,
        summary="문서 조회",
        intent="HOW_TO",
        processingType="KNOWLEDGE_QA",
        reportedSystemCandidateId=GUIDE.systemId,
        facts=[],
        assumptions=[],
        unknowns=[],
        details=make_details(),
    )


def test_a_guide_exactly_at_the_limit_is_built_with_the_verbatim_body() -> None:
    body = "가" * (MAX_GUIDE_CHARS - len(SUFFIX))

    guide = _guide(target(), [hit(content=body)])

    assert guide is not None and guide.messageText == body + SUFFIX


def test_a_guide_one_character_over_the_limit_is_not_built_and_is_never_cut() -> None:
    body = "가" * (MAX_GUIDE_CHARS - len(SUFFIX) + 1)

    assert _guide(target(), [hit(content=body)]) is None


def test_a_too_long_first_guide_is_not_replaced_by_a_later_one() -> None:
    other = GUIDE.model_copy(
        update={"id": "00000000-0000-0000-0000-0000000000e1", "title": "다른 안내"}
    )
    long_first = hit(content="가" * (MAX_GUIDE_CHARS + 10))
    short_second = hit(evidence=other, content="짧은 안내")

    assert _guide(target(), [long_first, short_second]) is None


def test_staff_only_hits_still_never_become_a_guide() -> None:
    assert _guide(target(), [hit(evidence=STAFF, content="짧음")]) is None


@pytest.mark.parametrize("size", [3_500, 3_950, 4_100, 12_000])
async def test_a_long_approved_guide_ends_the_run_safely_not_with_an_error(size) -> None:
    request = first_message_request(HOW_TO_TEXT)
    source = RecordingSource((hit(content="가" * size),))

    result, _ = await run(
        request,
        InquiryExtraction(problems=[how_to()]),
        draft(supportingEvidenceIds=[GUIDE.id]),
        source=source,
    )

    assert result.status == "SUCCEEDED", "긴 안내가 실행 전체를 INVALID_OUTPUT으로 만들지 않는다"
    assert result.guide is None
    assert len(result.answer or "") <= 4000
    assert result.questions or result.ticketProposals, "안내 대신 질문이나 담당자 검토로 간다"


async def test_a_guide_within_the_limit_still_answers_as_before() -> None:
    request = first_message_request(HOW_TO_TEXT)
    source = RecordingSource((hit(content="가" * 2_500),))

    result, _ = await run(
        request,
        InquiryExtraction(problems=[how_to()]),
        draft(supportingEvidenceIds=[GUIDE.id]),
        source=source,
    )

    assert result.guide is not None and result.questions == []
