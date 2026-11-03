"""Supervisor 단위 테스트용 합성 입력. 실제 권한·저장·모델 호출을 대신하지 않는다."""

from datetime import UTC, datetime, timedelta
from uuid import UUID

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import AuthorizedKnowledgeRef
from hops_ai.contracts.wire import (
    AiRunRequest,
    CaseProposal,
    KnowledgeEvidence,
    ProblemProposal,
    RequestDetails,
    StandardRequestBody,
    TicketProposal,
)
from hops_ai.supervisor.state import SupervisorState, create_initial_state, transition_phase


def uid(number: int) -> str:
    """가상 데이터에 사용할 재현 가능한 UUID를 만든다."""
    return str(UUID(int=number))


SYSTEM = uid(1)
OTHER_SYSTEM = uid(2)
PROBLEM = uid(10)
MESSAGE = uid(20)
DOCUMENT = uid(30)
EVIDENCE = uid(31)


def make_details() -> RequestDetails:
    """유형별 미확인 항목을 null로 보존한 요청 정보를 만든다."""
    return RequestDetails(
        taskOrScreen="첨부파일 열람",
        requestedAccess=None,
        businessPurpose=None,
        expectedBehavior=None,
        actualBehavior="파일이 열리지 않음",
        occurredAt=None,
        currentWorkflow=None,
        desiredChange=None,
        acceptanceCriteria=[],
    )


def make_request(
    *,
    kind: RunKind = RunKind.INQUIRY_ANALYSIS,
    question_count: int = 0,
    outcome: str | None = None,
) -> AiRunRequest:
    """상담 또는 처리 기록 기반 사례 실행에 필요한 최소 합성 요청을 만든다."""
    is_case = kind == RunKind.CASE_DRAFT
    expires_at = (datetime.now(UTC) + timedelta(hours=1)).isoformat().replace("+00:00", "Z")
    return AiRunRequest.model_validate(
        {
            "contractVersion": "hops-system-1",
            "runId": uid(100),
            "kind": kind,
            "inquiryId": uid(101),
            "inquiryVersion": 2,
            "answerMessageId": None if is_case else uid(102),
            "problemIds": [PROBLEM],
            "messages": [
                {
                    "id": MESSAGE,
                    "revision": 1,
                    "sequenceNo": 1,
                    "sender": "USER",
                    "content": "전자결재 첨부파일이 열리지 않아요",
                    "problemIds": [PROBLEM],
                    "createdAt": "2026-11-02T00:00:00Z",
                }
            ],
            "problems": [
                {
                    "id": PROBLEM,
                    "summary": "첨부파일 열람 실패",
                    "intent": "OBSERVED_SYMPTOM",
                    "processingType": "INVESTIGATION",
                    "reportedSystemId": SYSTEM,
                    "reportedFeatureId": None,
                    "state": "ANALYZING",
                    "questionCount": question_count,
                    "standardRequest": None,
                    "resolutionContext": None,
                }
            ],
            "systems": [
                {"id": SYSTEM, "code": "ELECTRONIC_APPROVAL", "name": "전자결재", "features": []},
                {"id": OTHER_SYSTEM, "code": "COMMON_LOGIN", "name": "공통 로그인", "features": []},
            ],
            "accessContext": {
                "actorId": uid(200),
                "purpose": "CASE_DRAFT" if is_case else "EMPLOYEE_HELP",
                "systemIds": [SYSTEM, OTHER_SYSTEM],
                "allowedDocumentScopes": ["EMPLOYEE"],
                "policyRevision": 1,
            },
            "existingWorkItems": []
            if outcome is None
            else [
                {
                    "workItemId": PROBLEM,
                    "problemId": PROBLEM,
                    "inquiryVersion": 1,
                    "outcome": outcome,
                    "ticketId": uid(300) if outcome == "STORED" else None,
                }
            ],
            "workRecord": None
            if not is_case
            else {
                "id": uid(301),
                "ticketId": uid(300),
                "problemId": PROBLEM,
                "systemId": SYSTEM,
                "kind": "VERIFIED_RESOLUTION",
                "action": "첨부파일 권한을 수정함",
                "verification": "열람 확인",
                "createdAt": "2026-11-02T00:00:00Z",
                "resolutionRequestId": uid(302),
                "confirmationState": "PENDING",
                "confirmedCause": None,
                "appliesToSystemVersion": "1.0",
            },
            "expiresAt": expires_at,
            "limits": {
                "maxModelCalls": 8,
                "maxInputTokens": 12000,
                "maxOutputTokens": 4000,
                "maxToolCalls": 10,
                "maxRetries": 2,
            },
        }
    )


def make_problem(problem_ref: str = PROBLEM) -> ProblemProposal:
    """사용자 진술의 실제 입력 메시지를 출처로 가진 문제 제안을 만든다."""
    return ProblemProposal(
        problemRef=problem_ref,
        summary="첨부파일 열람 실패",
        intent="OBSERVED_SYMPTOM",
        processingType="INVESTIGATION",
        reportedSystemCandidateId=SYSTEM,
        facts=[
            {
                "text": "사용자가 첨부파일 열람 실패를 보고함",
                "sourceMessageIds": [MESSAGE],
                "knowledgeEvidenceIds": [],
            }
        ],
        assumptions=[],
        unknowns=["실제 원인"],
        details=make_details(),
    )


def make_evidence(**changes) -> KnowledgeEvidence:
    """현재 시스템의 직원 공개 가이드 근거를 만들고 지정한 필드를 교체한다."""
    fields = {
        "id": EVIDENCE,
        "documentId": DOCUMENT,
        "documentVersion": 1,
        "kind": "GUIDE",
        "title": "첨부파일 열람 안내",
        "excerpt": "파일 열람 권한을 확인합니다",
        "systemId": SYSTEM,
        "appliesToSystemVersion": None,
        "applicability": "첨부파일 열람",
        "accessScope": "EMPLOYEE",
        "origin": "SYNTHETIC",
    }
    return KnowledgeEvidence.model_validate({**fields, **changes})


def authorize(evidence: KnowledgeEvidence) -> AuthorizedKnowledgeRef:
    """권한 어댑터가 반환했다고 가정하는 테스트용 문서 참조를 만든다."""
    return AuthorizedKnowledgeRef(
        documentId=evidence.documentId,
        documentVersion=evidence.documentVersion,
        kind=evidence.kind,
        accessScope=evidence.accessScope,
        contentDigest="a" * 64,
    )


def make_ticket(problem_ref: str = PROBLEM, system: str = SYSTEM) -> TicketProposal:
    """실제 저장 ID를 포함하지 않는 문제별 티켓 제안을 만든다."""
    problem = make_problem(problem_ref)
    snapshot = StandardRequestBody(
        summary=problem.summary,
        confirmedFacts=problem.facts,
        assumptions=[],
        unknowns=problem.unknowns,
        responsibleSystemCandidateId=system,
        responsibilityEvidenceIds=[],
        details=problem.details,
        knowledge=[],
    )
    return TicketProposal(
        problemRef=problem_ref,
        title="첨부파일 열람 실패 조사",
        processingType="INVESTIGATION",
        responsibleSystemCandidateId=system,
        evidenceIds=[],
        standardRequestSnapshot=snapshot,
    )


def make_case(*, reusable: bool = True) -> CaseProposal:
    """처리 기록에 대응하는 사례 초안 또는 정상적인 사례 없음 결과를 만든다."""
    return CaseProposal(
        reusable=reusable,
        title="첨부파일 권한 조치" if reusable else None,
        action="첨부파일 권한을 수정함" if reusable else None,
        verification="열람 확인" if reusable else None,
        applicability="전자결재 첨부 열람" if reusable else None,
        limitations=[],
        appliesToSystemVersion="1.0" if reusable else None,
        accessScope="EMPLOYEE" if reusable else None,
        sourceMessageIds=[MESSAGE] if reusable else [],
        entityLinks=[{"relation": "APPLIES_TO", "targetId": SYSTEM, "evidenceIds": [MESSAGE]}]
        if reusable
        else [],
        problemStatement="첨부파일 열람 실패" if reusable else None,
    )


def running_state(request: AiRunRequest | None = None) -> SupervisorState:
    """유효 입력을 QUEUED에서 RUNNING으로 전이한다."""
    return transition_phase(create_initial_state(request or make_request()), "RUNNING")


def reserve(state: SupervisorState, role: AgentRole, invocation: str) -> SupervisorState:
    """그래프 실행기가 호출을 예약한 상황을 재현한다. 전문 역할 자체를 실행하지 않는다."""
    return {**state, "activeRole": role, "pendingCalls": state["pendingCalls"] | {invocation}}
