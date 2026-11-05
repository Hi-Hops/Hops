"""평가 하네스 단위 테스트용 합성 기록. 실제 모델·검색을 대신하지 않는다."""

from collections.abc import Sequence
from typing import Any

from evaluation.harness.trace import RoleCall, RunTrace, TurnTrace
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiResult, AiRunRequest, KnowledgeEvidence
from tests.supervisor_fixtures import SYSTEM, make_evidence, make_request, uid

EVIDENCE_B = uid(41)
STAFF_EVIDENCE = uid(51)


def request_with(
    messages: Sequence[tuple[str, str]] = (("m1", "문서가 안 보여요"),),
    *,
    max_calls: int = 8,
    max_retries: int = 1,
) -> AiRunRequest:
    """주어진 (ID 별칭, 내용) 메시지를 가진 상담 요청. ID는 별칭에서 만든 재현 가능한 UUID다."""
    base = make_request().model_dump(mode="json")
    base["messages"] = [
        {
            "id": message_id(alias),
            "revision": 1,
            "sequenceNo": index + 1,
            "sender": "USER",
            "content": content,
            "problemIds": [],
            "createdAt": "2026-11-02T00:00:00Z",
        }
        for index, (alias, content) in enumerate(messages)
    ]
    base["limits"]["maxModelCalls"] = max_calls
    base["limits"]["maxRetries"] = max_retries
    return AiRunRequest.model_validate(base)


def message_id(alias: str) -> str:
    return uid(1000 + sum(ord(char) for char in alias))


def evidence(number: int = 1, *, scope: str = "EMPLOYEE", **changes: Any) -> KnowledgeEvidence:
    fields = {
        "id": uid(40 + number),
        "documentId": uid(60 + number),
        "title": f"문서 제목 {number}",
        "excerpt": f"문서 발췌 {number}",
        "accessScope": scope,
        "systemId": SYSTEM,
    }
    if scope == "SYSTEM_STAFF":
        fields["kind"] = "RUNBOOK"
    return make_evidence(**{**fields, **changes})


def problem_dict(ref: str, facts: Sequence[dict[str, Any]] = (), ptype: str = "KNOWLEDGE_QA"):
    return {
        "problemRef": ref,
        "summary": "문서가 안 보임",
        "intent": "HOW_TO",
        "processingType": ptype,
        "reportedSystemCandidateId": SYSTEM,
        "facts": list(facts),
        "assumptions": [],
        "unknowns": [],
        "details": {
            "taskOrScreen": None,
            "requestedAccess": None,
            "businessPurpose": None,
            "expectedBehavior": None,
            "actualBehavior": None,
            "occurredAt": None,
            "currentWorkflow": None,
            "desiredChange": None,
            "acceptanceCriteria": [],
        },
    }


def fact(text: str, *message_aliases: str) -> dict[str, Any]:
    return fact_from(text, *(message_id(alias) for alias in message_aliases))


def fact_from(text: str, *message_ids: str) -> dict[str, Any]:
    """실제 메시지 ID를 출처로 가진 사실."""
    return {"text": text, "sourceMessageIds": list(message_ids), "knowledgeEvidenceIds": []}


def review_ticket(ref: str) -> dict[str, Any]:
    problem = problem_dict(ref, ptype="REVIEW_REQUIRED")
    return {
        "problemRef": ref,
        "title": "담당자 검토",
        "processingType": "REVIEW_REQUIRED",
        "responsibleSystemCandidateId": SYSTEM,
        "evidenceIds": [],
        "standardRequestSnapshot": {
            "summary": problem["summary"],
            "confirmedFacts": [],
            "assumptions": [],
            "unknowns": [],
            "responsibleSystemCandidateId": SYSTEM,
            "responsibilityEvidenceIds": [],
            "details": problem["details"],
            "knowledge": [],
        },
    }


def ai_result(
    request: AiRunRequest | None = None,
    *,
    answer: str = "안내드립니다.",
    problems: Sequence[dict[str, Any]] = (),
    questions: Sequence[dict[str, Any]] = (),
    guide: dict[str, Any] | None = None,
    tickets: Sequence[dict[str, Any]] = (),
    evidence_items: Sequence[KnowledgeEvidence] = (),
    status: str = "SUCCEEDED",
) -> AiResult:
    source = request or request_with()
    body: dict[str, Any] = {
        "contractVersion": "hops-system-1",
        "runId": source.runId,
        "kind": "INQUIRY_ANALYSIS",
        "inquiryId": source.inquiryId,
        "inquiryVersion": source.inquiryVersion,
        "status": status,
        "answer": answer if status == "SUCCEEDED" else None,
        "problemProposals": list(problems) if status == "SUCCEEDED" else [],
        "questions": list(questions) if status == "SUCCEEDED" else [],
        "guide": guide if status == "SUCCEEDED" else None,
        "ticketProposals": list(tickets) if status == "SUCCEEDED" else [],
        "caseProposal": None,
        "evidence": [item.model_dump(mode="json") for item in evidence_items]
        if status == "SUCCEEDED"
        else [],
        "error": None
        if status == "SUCCEEDED"
        else {"code": "SEARCH_UNAVAILABLE", "message": "검색 오류", "retryable": True},
    }
    return AiResult.model_validate(body)


def question(ref: str, text: str, *fields: str) -> dict[str, Any]:
    return {
        "problemRef": ref,
        "bundleRef": f"b-{ref}-{'-'.join(fields)}",
        "text": text,
        "requestedFields": list(fields),
    }


def knowledge_call(
    ref: str | None, state: str, items: Sequence[KnowledgeEvidence] = ()
) -> RoleCall:
    return RoleCall(AgentRole.KNOWLEDGE, problem_ref=ref, search_state=state, evidence=tuple(items))


def turn(
    request: AiRunRequest,
    result: AiResult | None,
    *,
    calls: Sequence[RoleCall] = (),
    model_calls: int = 1,
    unknown_after: Sequence[tuple[str, str]] = (),
    ref_map: Sequence[tuple[str, str]] = (),
) -> TurnTrace:
    return TurnTrace(
        request=request,
        result=result,
        role_calls=tuple(calls),
        model_calls=model_calls,
        unknown_after=tuple(unknown_after),
        ref_map=tuple(ref_map),
    )


def run_trace(*turns: TurnTrace, forbidden: Sequence[str] = ()) -> RunTrace:
    return RunTrace("S-test-001", tuple(turns), tuple(forbidden))
