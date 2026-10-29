"""가짜 에이전트. 모델 없이 정해진 규칙으로 결과를 만든다. 가상 규칙이며 분석 품질 검증용이 아니다.

규칙(Mock 시나리오와 같다):
- 첫 문의는 추가 질문(ASK_USER)을 하고, 이미 질문한 문제는 티켓 제안(PROPOSE_TICKETS)으로 넘어간다
- 티켓 제안은 문제의 분류에 맞는 가상 부서를 고른다
단일 에이전트와 멀티 에이전트의 가짜 구현이 같은 규칙을 쓰므로 같은 입력에 같은 결과가 나온다.
"""

from collections.abc import Sequence

from hops_ai.contracts.agent_1 import (
    CONTRACT_VERSION,
    AgentRequest,
    AgentResult,
    Category,
    Department,
    DiagnosisData,
    DiagnosisRequest,
    DiagnosisResult,
    Evidence,
    IntakeData,
    IntakeProblem,
    IntakeRequest,
    IntakeResult,
    KnowledgeData,
    KnowledgeRequest,
    KnowledgeResult,
    Message,
    Problem,
    Ref,
    TicketData,
    TicketProposal,
    TicketRequest,
    TicketResult,
)
from hops_ai.contracts.analysis import AnalysisInput, AnalysisOutcome
from hops_ai.services.shared import SharedServices

CATEGORY_DEPARTMENT: dict[str, str] = {
    "ACCOUNT_AUTH": "ACCOUNT_PERMISSION",
    "PERMISSION": "ACCOUNT_PERMISSION",
    "NETWORK_ACCESS": "NETWORK",
    "SOFTWARE": "SYSTEM",
    "BUSINESS_SERVICE_ERROR": "BACKEND",
    "OTHER": "CS",
}
KEYWORD_CATEGORY: list[tuple[str, Category]] = [
    ("vpn", "NETWORK_ACCESS"),
    ("네트워크", "NETWORK_ACCESS"),
    ("로그인", "ACCOUNT_AUTH"),
    ("비밀번호", "ACCOUNT_AUTH"),
    ("권한", "PERMISSION"),
]
ASK_QUESTION = "오류 코드나 메시지를 알려 주세요."
TICKET_ANSWER = "담당 부서에 연결할 내용을 정리했어요."


def message_evidence(message: Message) -> Evidence:
    return Evidence(
        id=f"msg-{message.messageId[-3:]}-r{message.revision}",
        kind="MESSAGE",
        sourceId=message.messageId,
        sourceVersion=str(message.revision),
        summary="가상 사용자 메시지 요약",
        observedAt=None,
    )


def guess_category(text: str) -> Category:
    lowered = text.lower()
    for keyword, category in KEYWORD_CATEGORY:
        if keyword in lowered:
            return category
    return "OTHER"


def fake_intake(
    messages: Sequence[Message], existing_problems: Sequence[Problem]
) -> tuple[str, list[IntakeProblem], list[Evidence]]:
    """(답변 초안, 문제 목록, 근거). 사용자 메시지가 없으면 빈 결과다."""
    user_messages = [message for message in messages if message.sender == "USER"]
    if not user_messages:
        return "", [], []
    evidence = message_evidence(user_messages[-1])
    if not existing_problems:
        problem = IntakeProblem(
            ref="new:p1",
            summary=user_messages[0].content[:40],
            category=guess_category(user_messages[0].content),
            questionCount=0,
            nextAction="ASK_USER",
            question=ASK_QUESTION,
            evidenceIds=[evidence.id],
        )
        return ASK_QUESTION, [problem], [evidence]
    problems = [
        IntakeProblem(
            ref=existing.ref,
            summary=existing.summary,
            category=existing.category,
            questionCount=existing.questionCount,
            nextAction="PROPOSE_TICKETS",
            question=None,
            evidenceIds=[evidence.id],
        )
        for existing in existing_problems
    ]
    return TICKET_ANSWER, problems, [evidence]


def fake_ticket_proposals(
    problems: Sequence[Problem],
    departments: Sequence[Department],
    cs_review_refs: Sequence[Ref],
    evidence_ids: Sequence[str],
) -> list[TicketProposal]:
    codes = {department.code for department in departments}

    def department_for(problem: Problem, cs_review: bool) -> str:
        wanted = "CS" if cs_review else CATEGORY_DEPARTMENT.get(problem.category or "OTHER", "CS")
        if wanted in codes:
            return wanted
        return "CS" if "CS" in codes else departments[0].code

    return [
        TicketProposal(
            ref=f"new:work-{index}",
            problemRef=problem.ref,
            workScope=problem.summary,
            title=problem.summary,
            description="가상 분석 결과입니다.",
            category=problem.category or "OTHER",
            departmentCode=department_for(problem, problem.ref in cs_review_refs),
            purpose="CS_REVIEW" if problem.ref in cs_review_refs else "INCIDENT",
            diagnosisSummary="가상 분석. 실제 진단 결과가 아닙니다.",
            handoffReason="가상 규칙에 따른 부서 제안",
            uncertainty=None,
            evidenceIds=list(evidence_ids),
        )
        for index, problem in enumerate(problems, start=1)
    ]


def _meta(services: SharedServices):
    return services.usage.to_meta(0)


def _common(request: AgentRequest, services: SharedServices) -> dict:
    return {
        "contractVersion": CONTRACT_VERSION,
        "runId": request.runId,
        "invocationId": request.invocationId,
        "inquiryId": request.inquiryId,
        "inquiryVersion": request.inquiryVersion,
        "status": "OK",
        "error": None,
        "meta": _meta(services),
    }


class FakeIntakeAgent:
    async def execute(self, request: AgentRequest, services: SharedServices) -> AgentResult:
        assert isinstance(request, IntakeRequest)
        answer, problems, evidence = fake_intake(
            request.payload.messages, request.payload.existingProblems
        )
        return IntakeResult(
            **_common(request, services),
            role="INTAKE",
            data=IntakeData(replyDraft=answer, problems=problems, resolutionSignals=[]),
            evidence=evidence,
        )


class FakeKnowledgeAgent:
    async def execute(self, request: AgentRequest, services: SharedServices) -> AgentResult:
        assert isinstance(request, KnowledgeRequest)
        return KnowledgeResult(
            **_common(request, services),
            role="KNOWLEDGE",
            data=KnowledgeData(
                problemRef=request.payload.problem.ref,
                answerDraft=None,
                sufficiency="INSUFFICIENT",
                evidenceIds=[],
                missingInformation=["오류가 발생한 메뉴와 오류 메시지"],
                suggestedNext="ASK_USER",
            ),
            evidence=[],
        )


class FakeDiagnosisAgent:
    async def execute(self, request: AgentRequest, services: SharedServices) -> AgentResult:
        assert isinstance(request, DiagnosisRequest)
        return DiagnosisResult(
            **_common(request, services),
            role="DIAGNOSIS",
            data=DiagnosisData(
                problemRef=request.payload.problem.ref,
                findings=[],
                causeCandidates=[],
                uncertainties=["가상 진단이라 원인을 확정하지 않는다"],
                disposition="UNDETERMINED",
                guidanceDraft=None,
                question=None,
            ),
            evidence=[],
        )


class FakeTicketAgent:
    async def execute(self, request: AgentRequest, services: SharedServices) -> AgentResult:
        assert isinstance(request, TicketRequest)
        payload = request.payload
        evidence_ids = [evidence.id for evidence in payload.evidence]
        return TicketResult(
            **_common(request, services),
            role="TICKET",
            data=TicketData(
                ticketProposals=fake_ticket_proposals(
                    payload.problems, payload.departments, payload.csReviewProblemRefs, evidence_ids
                ),
                unresolvedProblemRefs=[],
            ),
            evidence=list(payload.evidence),
        )


class FakeBaselineAgent:
    """가짜 단일 에이전트. 가짜 Intake·Ticket과 같은 규칙으로 한 번에 결과를 만든다."""

    async def analyze(self, data: AnalysisInput, services: SharedServices) -> AnalysisOutcome:
        services.runtime.ensure_active()
        answer, problems, evidence = fake_intake(data.messages, data.existing_problems)
        proposing = [
            Problem(
                ref=problem.ref,
                summary=problem.summary,
                category=problem.category,
                questionCount=problem.questionCount,
            )
            for problem in problems
            if problem.nextAction == "PROPOSE_TICKETS"
        ]
        proposals = fake_ticket_proposals(
            proposing, data.departments, [], [item.id for item in evidence]
        )
        return AnalysisOutcome(
            answer=answer, problems=problems, ticket_proposals=proposals, evidence=evidence
        )
