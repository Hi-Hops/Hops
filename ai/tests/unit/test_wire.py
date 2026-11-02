import pytest
from pydantic import ValidationError

from hops_ai.contracts.wire import (
    AcceptanceCriterion,
    AccessContext,
    AiAccepted,
    AiEvent,
    AiResult,
    AiRunRequest,
    AiRunSnapshot,
    CaseProposal,
    ExecutionLimits,
    ExistingWorkItem,
    InputMessage,
    KnowledgeEvidence,
    StandardRequestBody,
    WorkRecordContext,
)


def test_execution_limits_accepts_valid_values() -> None:
    # 테스트용 숫자이며 실제 서비스의 운영 기본값을 뜻하지 않는다.
    limits = ExecutionLimits(
        maxModelCalls=3,
        maxInputTokens=1000,
        maxOutputTokens=500,
        maxToolCalls=2,
        maxRetries=0,
    )

    assert limits.maxModelCalls == 3
    assert limits.maxRetries == 0


@pytest.mark.parametrize(
    "field_name, invalid_value",
    [
        ("maxModelCalls", 0),
        ("maxInputTokens", 0),
        ("maxOutputTokens", 0),
        ("maxToolCalls", 0),
        ("maxRetries", -1),
        ("maxModelCalls", "3"),
    ],
)
def test_execution_limits_rejects_invalid_values(
    field_name: str,
    invalid_value: object,
) -> None:
    payload: dict[str, object] = {
        "maxModelCalls": 3,
        "maxInputTokens": 1000,
        "maxOutputTokens": 500,
        "maxToolCalls": 2,
        "maxRetries": 0,
    }
    payload[field_name] = invalid_value

    # 허용되지 않은 숫자나 문자열은 DTO 생성 단계에서 거부해야 한다.
    with pytest.raises(ValidationError):
        ExecutionLimits.model_validate(payload)


def test_execution_limits_rejects_unknown_fields() -> None:
    payload = {
        "maxModelCalls": 3,
        "maxInputTokens": 1000,
        "maxOutputTokens": 500,
        "maxToolCalls": 2,
        "maxRetries": 0,
        "unexpectedField": 1,
    }

    with pytest.raises(ValidationError):
        ExecutionLimits.model_validate(payload)


def test_access_context_accepts_valid_values() -> None:
    context = AccessContext(
        actorId="90c34564-bb4c-447e-8e43-5de9068dd666",
        purpose="EMPLOYEE_HELP",
        systemIds=[
            "db8da008-cbb0-43e0-a313-7168168ecaf6",
        ],
        allowedDocumentScopes=["EMPLOYEE"],
        policyRevision=1,
    )

    assert context.purpose == "EMPLOYEE_HELP"
    assert context.policyRevision == 1


def test_access_context_rejects_unknown_purpose() -> None:
    payload = {
        "actorId": "90c34564-bb4c-447e-8e43-5de9068dd666",
        "purpose": "ADMIN_EVERYTHING",
        "systemIds": [],
        "allowedDocumentScopes": ["EMPLOYEE"],
        "policyRevision": 1,
    }

    with pytest.raises(ValidationError):
        AccessContext.model_validate(payload)


def test_access_context_rejects_noncanonical_uuid() -> None:
    payload = {
        # UUID는 소문자여야 하므로 대문자 입력은 거부해야 한다.
        "actorId": "90C34564-BB4C-447E-8E43-5DE9068DD666",
        "purpose": "EMPLOYEE_HELP",
        "systemIds": [],
        "allowedDocumentScopes": ["EMPLOYEE"],
        "policyRevision": 1,
    }

    with pytest.raises(ValidationError):
        AccessContext.model_validate(payload)


def test_stored_work_item_requires_ticket_id() -> None:
    payload = {
        "workItemId": "90c34564-bb4c-447e-8e43-5de9068dd666",
        "problemId": "90c34564-bb4c-447e-8e43-5de9068dd666",
        "inquiryVersion": 1,
        "outcome": "STORED",
        "ticketId": None,
    }

    with pytest.raises(ValidationError):
        ExistingWorkItem.model_validate(payload)


def test_pending_work_item_rejects_ticket_id() -> None:
    payload = {
        "workItemId": "90c34564-bb4c-447e-8e43-5de9068dd666",
        "problemId": "90c34564-bb4c-447e-8e43-5de9068dd666",
        "inquiryVersion": 1,
        "outcome": "PENDING",
        "ticketId": "db8da008-cbb0-43e0-a313-7168168ecaf6",
    }

    with pytest.raises(ValidationError):
        ExistingWorkItem.model_validate(payload)


def test_work_item_requires_ticket_id_key_even_when_null() -> None:
    payload = {
        "workItemId": "90c34564-bb4c-447e-8e43-5de9068dd666",
        "problemId": "90c34564-bb4c-447e-8e43-5de9068dd666",
        "inquiryVersion": 1,
        "outcome": "PENDING",
        # ticketId 키가 빠져 있으므로 거부해야 한다.
    }

    with pytest.raises(ValidationError):
        ExistingWorkItem.model_validate(payload)


# 아래 데이터는 wire 형식 검증을 위한 테스트 fixture다.
RUN_ID = "90c34564-bb4c-447e-8e43-5de9068dd666"
OTHER_ID = "db8da008-cbb0-43e0-a313-7168168ecaf6"


def inquiry_request() -> dict:
    return {
        "contractVersion": "hops-system-1",
        "runId": RUN_ID,
        "kind": "INQUIRY_ANALYSIS",
        "inquiryId": OTHER_ID,
        "inquiryVersion": 1,
        "answerMessageId": RUN_ID,
        "problemIds": [],
        "messages": [],
        "problems": [],
        "systems": [],
        "existingWorkItems": [],
        "workRecord": None,
        "accessContext": {
            "actorId": RUN_ID,
            "purpose": "EMPLOYEE_HELP",
            "systemIds": [],
            "allowedDocumentScopes": ["EMPLOYEE"],
            "policyRevision": 1,
        },
        "expiresAt": "2026-11-02T01:00:00Z",
        "limits": {
            "maxModelCalls": 3,
            "maxInputTokens": 1000,
            "maxOutputTokens": 500,
            "maxToolCalls": 2,
            "maxRetries": 0,
        },
    }


def inquiry_result() -> dict:
    return {
        "contractVersion": "hops-system-1",
        "runId": RUN_ID,
        "kind": "INQUIRY_ANALYSIS",
        "inquiryId": OTHER_ID,
        "inquiryVersion": 1,
        "status": "SUCCEEDED",
        "answer": "어느 화면인가요?",
        "problemProposals": [],
        "questions": [
            {
                "problemRef": "new:one",
                "bundleRef": "bundle-1",
                "text": "어느 화면인가요?",
                "requestedFields": ["screen"],
            }
        ],
        "guide": None,
        "ticketProposals": [],
        "caseProposal": None,
        "evidence": [],
        "error": None,
    }


def test_inquiry_request_and_required_nullable_fields() -> None:
    payload = inquiry_request()
    assert AiRunRequest.model_validate(payload).workRecord is None
    del payload["workRecord"]
    with pytest.raises(ValidationError):
        AiRunRequest.model_validate(payload)


@pytest.mark.parametrize(
    "change",
    [
        {"answerMessageId": None},
        {"kind": "CASE_DRAFT"},
        {"expiresAt": "2026-11-02T01:00:00+00:00"},
        {"inquiryVersion": True},
    ],
)
def test_request_rejects_invalid_combinations(change: dict) -> None:
    with pytest.raises(ValidationError):
        AiRunRequest.model_validate(inquiry_request() | change)


def test_case_draft_requires_record_and_case_purpose() -> None:
    payload = inquiry_request()
    payload.update(
        kind="CASE_DRAFT",
        answerMessageId=None,
        workRecord={
            "id": RUN_ID,
            "ticketId": RUN_ID,
            "problemId": RUN_ID,
            "systemId": RUN_ID,
            "kind": "VERIFIED_RESOLUTION",
            "action": "조치",
            "verification": "확인",
            "createdAt": "2026-11-02T01:00:00Z",
            "resolutionRequestId": RUN_ID,
            "confirmationState": "PENDING",
            "confirmedCause": None,
            "appliesToSystemVersion": None,
        },
    )
    payload["accessContext"]["purpose"] = "CASE_DRAFT"
    assert AiRunRequest.model_validate(payload).kind == "CASE_DRAFT"
    payload["accessContext"]["purpose"] = "EMPLOYEE_HELP"
    with pytest.raises(ValidationError):
        AiRunRequest.model_validate(payload)


def test_result_validates_answer_and_failure() -> None:
    payload = inquiry_result()
    assert AiResult.model_validate(payload).status == "SUCCEEDED"
    with pytest.raises(ValidationError):
        AiResult.model_validate(payload | {"answer": "다른 답변"})
    failure = payload | {
        "status": "FAILED",
        "answer": None,
        "questions": [],
        "error": {"code": "MODEL_UNAVAILABLE", "message": "실패", "retryable": True},
    }
    assert AiResult.model_validate(failure).status == "FAILED"
    with pytest.raises(ValidationError):
        AiResult.model_validate(failure | {"questions": payload["questions"]})


def test_snapshot_and_event_consistency() -> None:
    result = inquiry_result()
    snapshot = {
        "contractVersion": "hops-system-1",
        "runId": RUN_ID,
        "status": "SUCCEEDED",
        "lastSeq": 1,
        "result": result,
        "error": None,
    }
    assert AiRunSnapshot.model_validate(snapshot).status == "SUCCEEDED"
    with pytest.raises(ValidationError):
        AiRunSnapshot.model_validate(snapshot | {"status": "RUNNING"})
    event = {
        "contractVersion": "hops-system-1",
        "runId": RUN_ID,
        "seq": 1,
        "eventType": "result",
        "payload": result,
    }
    assert AiEvent.model_validate(event).eventType == "result"
    with pytest.raises(ValidationError):
        AiEvent.model_validate(event | {"eventType": "progress"})


def test_message_content_and_time_validation() -> None:
    payload = {
        "id": RUN_ID,
        "revision": 1,
        "sequenceNo": 1,
        "sender": "USER",
        "content": "  문의  ",
        "problemIds": [],
        "createdAt": "2026-11-02T01:00:00Z",
    }
    assert InputMessage.model_validate(payload).content == "  문의  "
    for content in ("\ufeff \n", "\ud800", "가" * 4001):
        with pytest.raises(ValidationError):
            InputMessage.model_validate(payload | {"content": content})


def test_case_proposal_reusability() -> None:
    payload = {
        "reusable": False,
        "title": None,
        "action": None,
        "verification": None,
        "applicability": None,
        "limitations": [],
        "appliesToSystemVersion": None,
        "accessScope": None,
        "sourceMessageIds": [],
        "entityLinks": [],
        "problemStatement": None,
    }
    assert not CaseProposal.model_validate(payload).reusable
    with pytest.raises(ValidationError):
        CaseProposal.model_validate(payload | {"reusable": True})
    with pytest.raises(ValidationError):
        CaseProposal.model_validate(payload | {"title": "임의 제목"})


def test_accepted_rejects_external_events_path() -> None:
    payload = {
        "contractVersion": "hops-system-1",
        "runId": RUN_ID,
        "status": "QUEUED",
        "eventsPath": f"/internal/v2/runs/{RUN_ID}/events",
    }
    assert AiAccepted.model_validate(payload).eventsPath == payload["eventsPath"]
    for path in ("https://example.com/events", "//example.com/events", "/events?token=secret"):
        with pytest.raises(ValidationError):
            AiAccepted.model_validate(payload | {"eventsPath": path})


def request_details() -> dict:
    return {
        "taskOrScreen": None,
        "requestedAccess": None,
        "businessPurpose": None,
        "expectedBehavior": None,
        "actualBehavior": None,
        "occurredAt": None,
        "currentWorkflow": None,
        "desiredChange": None,
        "acceptanceCriteria": [],
    }


def standard_request() -> dict:
    return {
        "summary": "문서 확인",
        "confirmedFacts": [],
        "assumptions": [],
        "unknowns": [],
        "responsibleSystemCandidateId": None,
        "responsibilityEvidenceIds": [],
        "details": request_details(),
        "knowledge": [],
    }


def knowledge_evidence() -> dict:
    return {
        "id": RUN_ID,
        "documentId": OTHER_ID,
        "documentVersion": 1,
        "kind": "GUIDE",
        "title": "사용 가이드",
        "excerpt": "확인 절차",
        "systemId": RUN_ID,
        "appliesToSystemVersion": None,
        "applicability": "가상 시스템",
        "accessScope": "EMPLOYEE",
        "origin": "SYNTHETIC",
    }


def test_nested_input_problem_and_system_validate() -> None:
    payload = inquiry_request()
    payload["systems"] = [
        {
            "id": RUN_ID,
            "code": "ELECTRONIC_APPROVAL",
            "name": "전자결재",
            "features": [{"id": OTHER_ID, "name": "문서조회"}],
        }
    ]
    payload["problems"] = [
        {
            "id": OTHER_ID,
            "summary": "문서 열람",
            "intent": "OBSERVED_SYMPTOM",
            "processingType": "INVESTIGATION",
            "reportedSystemId": RUN_ID,
            "reportedFeatureId": OTHER_ID,
            "state": "ANALYZING",
            "questionCount": 5,
            "standardRequest": standard_request(),
            "resolutionContext": {
                "requestId": RUN_ID,
                "source": "GUIDE",
                "sourceId": OTHER_ID,
                "sourceVersion": 1,
                "state": "UNRESOLVED",
            },
        }
    ]
    result = AiRunRequest.model_validate(payload)
    assert result.problems[0].questionCount == 5
    assert result.systems[0].features[0].name == "문서조회"
    payload["problems"][0]["questionCount"] = 6
    with pytest.raises(ValidationError):
        AiRunRequest.model_validate(payload)


@pytest.mark.parametrize(
    "confirmation,confirmed_by,valid",
    [
        ("PROPOSED", None, True),
        ("CONFIRMED", RUN_ID, True),
        ("PROPOSED", RUN_ID, False),
        ("CONFIRMED", None, False),
    ],
)
def test_acceptance_criterion_confirmation(confirmation, confirmed_by, valid) -> None:
    payload = {"text": "화면에서 확인", "confirmation": confirmation, "confirmedBy": confirmed_by}
    if valid:
        assert AcceptanceCriterion.model_validate(payload).confirmation == confirmation
    else:
        with pytest.raises(ValidationError):
            AcceptanceCriterion.model_validate(payload)


def test_knowledge_evidence_common_policy_and_version_limit() -> None:
    evidence = knowledge_evidence()
    assert KnowledgeEvidence.model_validate(evidence).systemId == RUN_ID
    with pytest.raises(ValidationError):
        KnowledgeEvidence.model_validate(evidence | {"systemId": None})
    assert KnowledgeEvidence.model_validate(evidence | {"systemId": None, "kind": "POLICY"})
    with pytest.raises(ValidationError):
        KnowledgeEvidence.model_validate(evidence | {"documentVersion": 2_147_483_648})
    with pytest.raises(ValidationError):
        KnowledgeEvidence.model_validate(evidence | {"appliesToSystemVersion": "v" * 101})


def test_confirmed_facts_need_sources_but_assumptions_do_not() -> None:
    fact = {"text": "확인 사실", "sourceMessageIds": [], "knowledgeEvidenceIds": []}
    payload = standard_request()
    with pytest.raises(ValidationError):
        StandardRequestBody.model_validate(payload | {"confirmedFacts": [fact]})
    assert StandardRequestBody.model_validate(payload | {"assumptions": [fact]})
    fact["sourceMessageIds"] = [RUN_ID]
    assert StandardRequestBody.model_validate(payload | {"confirmedFacts": [fact]})


def test_case_draft_result_validates_content_and_kind() -> None:
    proposal = {
        "reusable": True,
        "title": "해결 사례",
        "action": "설정 변경",
        "verification": "동작 확인",
        "applicability": "전자결재",
        "limitations": [],
        "appliesToSystemVersion": None,
        "accessScope": "SYSTEM_STAFF",
        "sourceMessageIds": [RUN_ID],
        "entityLinks": [],
        "problemStatement": "문서 조회 실패",
    }
    payload = inquiry_result() | {
        "kind": "CASE_DRAFT",
        "answer": None,
        "questions": [],
        "caseProposal": proposal,
    }
    assert AiResult.model_validate(payload).caseProposal.reusable
    with pytest.raises(ValidationError):
        AiResult.model_validate(payload | {"answer": "답변"})
    with pytest.raises(ValidationError):
        AiResult.model_validate(payload | {"caseProposal": proposal | {"problemStatement": None}})


def test_ticket_and_question_for_same_problem_are_rejected() -> None:
    ticket = {
        "problemRef": "new:one",
        "title": "확인 요청",
        "processingType": "INVESTIGATION",
        "responsibleSystemCandidateId": RUN_ID,
        "evidenceIds": [],
        "standardRequestSnapshot": standard_request(),
    }
    payload = inquiry_result()
    with pytest.raises(ValidationError):
        AiResult.model_validate(payload | {"ticketProposals": [ticket]})
    assert AiResult.model_validate(
        payload | {"ticketProposals": [ticket | {"problemRef": "new:two"}]}
    )
    with pytest.raises(ValidationError):
        AiResult.model_validate(
            payload | {"ticketProposals": [ticket | {"processingType": "INCIDENT"}]}
        )


@pytest.mark.parametrize(
    "event_type,payload",
    [
        ("progress", {"role": "INQUIRY", "displaySummary": "문의 분석 중"}),
        ("answer.delta", {"messageId": RUN_ID, "chunkSeq": 1, "text": "확인합니다"}),
    ],
)
def test_progress_and_delta_event(event_type: str, payload: dict) -> None:
    event = {
        "contractVersion": "hops-system-1",
        "runId": RUN_ID,
        "seq": 1,
        "eventType": event_type,
        "payload": payload,
    }
    assert AiEvent.model_validate(event).eventType == event_type
    with pytest.raises(ValidationError):
        AiEvent.model_validate(event | {"seq": 0})


def test_failed_snapshot_requires_matching_error() -> None:
    error = {"code": "MODEL_UNAVAILABLE", "message": "일시 오류", "retryable": True}
    result = inquiry_result() | {
        "status": "FAILED",
        "answer": None,
        "questions": [],
        "error": error,
    }
    snapshot = {
        "contractVersion": "hops-system-1",
        "runId": RUN_ID,
        "status": "FAILED",
        "lastSeq": 1,
        "result": result,
        "error": error,
    }
    assert AiRunSnapshot.model_validate(snapshot).error.code == "MODEL_UNAVAILABLE"
    with pytest.raises(ValidationError):
        AiRunSnapshot.model_validate(snapshot | {"error": None})
    with pytest.raises(ValidationError):
        AiRunSnapshot.model_validate(snapshot | {"result": result | {"runId": OTHER_ID}})


def test_guide_requires_employee_evidence_in_result() -> None:
    guide = {"messageText": "가이드를 확인하세요", "evidenceIds": [RUN_ID], "problemRef": "new:two"}
    result = inquiry_result() | {
        "answer": "어느 화면인가요? 가이드를 확인하세요",
        "guide": guide,
        "evidence": [knowledge_evidence()],
    }
    assert AiResult.model_validate(result).guide is not None
    for evidence in ([], [knowledge_evidence() | {"accessScope": "SYSTEM_STAFF"}]):
        with pytest.raises(ValidationError):
            AiResult.model_validate(result | {"evidence": evidence})
    with pytest.raises(ValidationError):
        AiResult.model_validate(result | {"guide": guide | {"evidenceIds": []}})


def test_work_record_confirmed_cause_length() -> None:
    record = {
        "id": RUN_ID,
        "ticketId": RUN_ID,
        "problemId": RUN_ID,
        "systemId": RUN_ID,
        "kind": "VERIFIED_RESOLUTION",
        "action": "조치",
        "verification": "확인",
        "createdAt": "2026-11-02T01:00:00Z",
        "resolutionRequestId": RUN_ID,
        "confirmationState": "PENDING",
        "confirmedCause": "가" * 4000,
        "appliesToSystemVersion": None,
    }
    assert len(WorkRecordContext.model_validate(record).confirmedCause) == 4000
    with pytest.raises(ValidationError):
        WorkRecordContext.model_validate(record | {"confirmedCause": "가" * 4001})
