"""여러 턴을 끝까지 재생하는 시나리오(#189 ⑤). 실제 에이전트·Supervisor와 Fake 모델을 쓴다.

사용자 모사가 답하고 Spring 대역이 다음 실행 입력을 만들며, 안전 성질(P1~P12)과 B10을
기록으로 검사한다.
Fake 모델은 응답 형식별 대본(`ByModelLLM`)대로 답하므로 이 테스트가 보이는 것은 모델 품질이 아니라
코드 규칙이 여러 턴에 걸쳐 같은 질문을 되풀이하지 않고, 근거가 없으면 담당자 검토로 끝내는지다.
실모델로 같은 시나리오를 본 기록은 evaluation/baselines/README.md에 있다.
"""

import json

from evaluation.harness.behavior import check_b10
from evaluation.harness.catalog import SYSTEMS
from evaluation.harness.checks import check_trace
from evaluation.harness.runner import RunConfig, run_scenario
from evaluation.harness.scenario import parse_scenario
from hops_ai.agents.inquiry.extraction import ExtractedProblem, InquiryExtraction, QuotedValue
from hops_ai.agents.knowledge.assessment import AssessmentDraft, DraftMissing
from hops_ai.agents.registry import build_registry
from hops_ai.agents.ticket.proposal import TicketDecision, TicketDecisions
from hops_ai.supervisor.selection import ActionSelection
from tests.doubles.llm import ByModelLLM
from tests.supervisor_fixtures import make_evidence, uid
from tests.unit.test_knowledge_agent import RecordingSource
from tests.unit.test_knowledge_assessment import hit
from tests.unit.test_relation_lookup import FakeRelations, check, question, symptom

EA = SYSTEMS[0]["id"]
BASE = {
    "caseFamily": "multi-turn",
    "split": "DEV",
    "origin": "SYNTHETIC",
    "revealRule": "ASKED_FIELD_ONLY",
}


def scenario(**changes):
    return parse_scenario({**BASE, "scenarioId": "S-multi-turn-001", **changes})


def evidence(number: int):
    return make_evidence(
        id=uid(300 + number),
        documentId=uid(310 + number),
        systemId=EA,
        title=f"전자결재 안내 {number}",
    )


# -- 대본 ---------------------------------------------------------------------------------------


def user_messages(request) -> list[dict]:
    payload = json.loads(request.messages[1].content)
    return [item for item in payload["messages"] if item["sender"] == "USER"]


def extraction_of(intent: str, phrases: dict[str, str], keywords: tuple[str, ...] = ()):
    """사용자 메시지에 실제로 있는 구절만 인용하는 추출. phrases는 필드 이름 → 찾을 구절이다.

    keywords는 관계 조회가 쓰는 검색 힌트이며 원문에 있는 구절이어야 남는다."""

    def script(request) -> InquiryExtraction:
        values: dict[str, QuotedValue | None] = {}
        for field, phrase in phrases.items():
            values[field] = next(
                (
                    QuotedValue(messageId=item["messageId"], quote=phrase)
                    for item in user_messages(request)
                    if phrase in item["text"]
                ),
                None,
            )
        base = {
            name: None
            for name in (
                "taskOrScreen",
                "currentWorkflow",
                "expectedBehavior",
                "actualBehavior",
                "occurredAt",
                "requestedAccess",
                "businessPurpose",
                "desiredChange",
            )
        }
        problem = ExtractedProblem(
            summary="전자결재 문서 문의",
            intent=intent,
            systemCode="ELECTRONIC_APPROVAL",
            unknownFields=[],
            keywords=list(keywords),
            featureCandidates=[],
            **{**base, **values},
        )
        return InquiryExtraction(problems=[problem])

    return script


def sufficient_with_the_first_evidence(request) -> AssessmentDraft:
    first = json.loads(request.messages[1].content)["evidence"][0]["id"]
    return AssessmentDraft(
        verdict="SUFFICIENT",
        supportingEvidenceIds=[first],
        answeredCheckItems=[],
        askedChecksAnswered=[],
        missingInformation=[],
        candidateCauses=[],
        nextChecks=[],
        rationale="근거가 문제를 직접 다룬다",
    )


def insufficient_pointing_at_check_items(request) -> AssessmentDraft:
    """아직 묻지 않은 확인 항목을 앞에서 3개까지 지목한다(이미 물은 항목은 입력에서 빠져 있다)."""
    items = json.loads(request.messages[1].content)["checkItems"][:3]
    return AssessmentDraft(
        verdict="INSUFFICIENT",
        supportingEvidenceIds=[],
        answeredCheckItems=[],
        askedChecksAnswered=[],
        missingInformation=[DraftMissing(kind="CHECK_ITEM", ref=item["id"]) for item in items],
        candidateCauses=[],
        nextChecks=[],
        rationale="원인을 가르는 정보가 더 필요하다",
    )


def prefer(*order: str):
    """합법 행동 중 앞에 둔 것을 고르는 선택. 모델이 실제로 이렇게 고른다는 뜻이 아니다."""

    def script(request) -> ActionSelection:
        problems = json.loads(request.messages[1].content)["problems"]
        return ActionSelection(
            decisions={
                item["problemRef"]: next(
                    action for action in order if action in item["legalActions"]
                )
                for item in problems
            }
        )

    return script


def ticket_for_every_problem(request) -> TicketDecisions:
    problems = json.loads(request.messages[1].content)["problems"]
    return TicketDecisions(
        decisions=[
            TicketDecision(
                problemRef=item["problem"]["problemRef"],
                title="담당자 검토 요청",
                responsibleSystemCandidateId=EA,
                supports=[],
                knowledgeEvidenceIds=[],
            )
            for item in problems
        ]
    )


PRIORITY = ("SEARCH", "ASK", "ANSWER", "TICKET", "HOLD", "CLOSE", "RESEARCH")


def llm_for(extraction, assessment, **extra):
    return ByModelLLM(
        {
            InquiryExtraction: extraction,
            AssessmentDraft: assessment,
            ActionSelection: prefer(*PRIORITY),
            TicketDecisions: ticket_for_every_problem,
            **extra,
        }
    )


async def replay(scenario_, llm, source, *, relations=None, max_turns=6):
    agents = build_registry(
        source, assess_evidence=True, defer_questions=True, relation_source=relations
    )
    return await run_scenario(
        scenario_, llm=llm, agents=agents, config=RunConfig(judgment=True, max_turns=max_turns)
    )


def asked_in_order(trace) -> list[str]:
    return [
        name
        for turn_ in trace.turns
        if turn_.result is not None
        for q in turn_.result.questions
        for name in q.requestedFields
    ]


# -- 질문 이력: 답을 못 얻어도 같은 항목을 다시 묻지 않는다 -----------------------------------


async def test_a_user_who_cannot_answer_is_not_asked_the_same_fields_again() -> None:
    sc = scenario(
        firstMessage="전자결재에서 문서가 목록에 안 보여요",
        userKnowledge={"systemCode": "ELECTRONIC_APPROVAL"},
        unknownFields=["taskOrScreen", "expectedBehavior", "occurredAt"],
    )
    llm = llm_for(
        extraction_of("OBSERVED_SYMPTOM", {"actualBehavior": "안 보여요"}),
        lambda request: AssessmentDraft(
            verdict="INSUFFICIENT",
            supportingEvidenceIds=[],
            answeredCheckItems=[],
            askedChecksAnswered=[],
            missingInformation=[],
            candidateCauses=[],
            nextChecks=[],
            rationale="근거가 직접 다루지 않는다",
        ),
    )

    trace = await replay(sc, llm, RecordingSource([hit(evidence(1))]))

    assert len(trace.turns) == 2
    first, second = trace.turns
    assert asked_in_order(trace) == ["taskOrScreen", "expectedBehavior", "occurredAt"]
    # 두 번째 실행 입력에 Spring이 저장한 질문 횟수와 물은 항목이 돌아온다
    carried = second.request.problems[0]
    assert carried.questionCount == 1
    assert carried.askedFields == ["taskOrScreen", "expectedBehavior", "occurredAt"]
    # 더 물을 것이 없고 근거도 부족하니 질문 없이 담당자 검토로 끝난다
    assert second.result.questions == [] and len(second.result.ticketProposals) == 1
    assert first.result.ticketProposals == []
    assert check_trace(trace) == []


async def test_check_item_questions_run_over_turns_and_each_item_is_asked_once() -> None:
    sc = scenario(
        firstMessage="전자결재에서 문서가 목록에 안 보여요",
        userKnowledge={
            "systemCode": "ELECTRONIC_APPROVAL",
            "taskOrScreen": "문서함 화면에서 찾아요.",
            "expectedBehavior": "올린 문서가 보여야 해요.",
            "occurredAt": "어제부터 그래요.",
        },
        unknownFields=[],
        checkAnswers={"check-a": "기간은 최근 3개월이에요.", "check-c": "직접 올린 문서예요."},
    )
    checks = [
        check(key, questions=[question(text=f"{key}를 알려 주시겠어요?")])
        for key in ("check-a", "check-b", "check-c", "check-d")
    ]
    relations = FakeRelations(symptom(checks=checks).model_copy(update={"systemId": EA}))
    llm = llm_for(
        extraction_of(
            "OBSERVED_SYMPTOM",
            {
                "actualBehavior": "안 보여요",
                "taskOrScreen": "문서함 화면",
                "expectedBehavior": "올린 문서가 보여야 해요",
                "occurredAt": "어제부터",
            },
            keywords=("문서", "목록"),
        ),
        insufficient_pointing_at_check_items,
    )

    trace = await replay(sc, llm, RecordingSource([hit(evidence(1))]), relations=relations)

    assert len(trace.turns) == 4
    asked = asked_in_order(trace)
    # 확인 항목은 네 개 모두 한 번씩만 묻는다. 양식 필드는 첫 묶음에서 자리를 나눠 쓴다
    assert sorted(name for name in asked if name.startswith("check-")) == [
        "check-a",
        "check-b",
        "check-c",
        "check-d",
    ]
    assert len(asked) == len(set(asked))
    # 묶음은 양식 필드 둘과 확인 항목 하나로 시작하고(자리 보장), 항목이 바닥나면 질문 없이 끝난다
    assert [len(turn_.result.questions[0].requestedFields) for turn_ in trace.turns[:3]] == [
        3,
        3,
        1,
    ]
    last = trace.turns[-1]
    assert last.request.problems[0].questionCount == 3
    assert last.result.questions == [] and len(last.result.ticketProposals) == 1
    assert check_trace(trace) == []


# -- 안내 뒤 미해결 응답 -----------------------------------------------------------------

HOW_TO = {
    "firstMessage": "전자결재에서 지난 문서를 찾는 방법을 알려주세요.",
    "userKnowledge": {"systemCode": "ELECTRONIC_APPROVAL"},
    "unknownFields": [],
    "followUp": {"text": "안내대로 했는데 해결되지 않았어요.", "resolution": "UNRESOLVED"},
}


def how_to_extraction():
    return extraction_of("HOW_TO", {"taskOrScreen": "지난 문서를 찾는 방법"})


async def test_unresolved_after_a_guide_with_no_other_evidence_ends_in_a_staff_ticket() -> None:
    llm = llm_for(how_to_extraction(), sufficient_with_the_first_evidence)
    source = RecordingSource([hit(evidence(1))])

    trace = await replay(scenario(**HOW_TO), llm, source)

    assert len(trace.turns) == 2
    guided, reanalysis = trace.turns
    assert guided.result.guide is not None and guided.result.ticketProposals == []
    context = reanalysis.request.problems[0].resolutionContext
    assert context is not None and context.state == "UNRESOLVED"
    # 같은 가이드를 되풀이하지 않고 질문도 재검색도 없이 담당자 검토로 간다
    assert reanalysis.result.guide is None and reanalysis.result.questions == []
    assert len(reanalysis.result.ticketProposals) == 1
    assert len(source.queries) == 2
    assert llm.calls(AssessmentDraft) == 1  # 이미 안내한 근거만 있으면 판정 호출도 없다
    assert check_b10(trace) == [] and check_trace(trace) == []


async def test_unresolved_after_a_guide_with_another_document_guides_with_that_one() -> None:
    llm = llm_for(how_to_extraction(), sufficient_with_the_first_evidence)
    source = RecordingSource([hit(evidence(1)), hit(evidence(2))])

    trace = await replay(scenario(**HOW_TO), llm, source)

    assert len(trace.turns) == 2
    guided, reanalysis = trace.turns
    assert guided.result.guide.evidenceIds == [evidence(1).id]
    assert reanalysis.result.guide is not None
    assert reanalysis.result.guide.evidenceIds == [evidence(2).id]
    assert reanalysis.result.ticketProposals == []
    assert check_b10(trace) == [] and check_trace(trace) == []
