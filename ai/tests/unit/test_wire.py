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


def image_message() -> dict:
    return {
        "id": RUN_ID,
        "revision": 1,
        "sequenceNo": 1,
        "sender": "USER",
        "content": "화면 오류",
        "problemIds": [],
        "createdAt": "2026-11-02T00:00:00Z",
    }


def test_optional_image_refs_preserve_old_request_serialization() -> None:
    payload = inquiry_request()
    payload["messages"] = [image_message()]
    parsed = AiRunRequest.model_validate(payload)
    assert parsed.model_dump(mode="json")["messages"] == payload["messages"]
    image = {"attachmentId": OTHER_ID, "digest": "a" * 64}
    payload["messages"][0]["imageAttachments"] = [image]
    assert (
        AiRunRequest.model_validate(payload).model_dump(mode="json")["messages"]
        == payload["messages"]
    )


@pytest.mark.parametrize("change", ["duplicate", "digest", "sender", "too_many"])
def test_image_refs_reject_invalid_input(change: str) -> None:
    message = image_message()
    image = {"attachmentId": OTHER_ID, "digest": "a" * 64}
    message["imageAttachments"] = [image]
    if change == "duplicate":
        message["imageAttachments"] = [image, image]
    elif change == "digest":
        image["digest"] = "broken"
    elif change == "sender":
        message["sender"] = "AI"
    else:
        message["imageAttachments"] = [image] * 6
    payload = inquiry_request()
    payload["messages"] = [message]
    with pytest.raises(ValidationError):
        AiRunRequest.model_validate(payload)


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


def image_observation() -> dict:
    return {
        "observationRef": "observation-1",
        "attachmentId": OTHER_ID,
        "messageId": RUN_ID,
        "messageRevision": 1,
        "problemRef": "new:one",
        "text": "오류 화면이 보입니다",
        "questionRef": "bundle-1",
    }


def observation_result() -> dict:
    payload = inquiry_result()
    payload["problemProposals"] = [
        {
            "problemRef": "new:one",
            "summary": "화면 오류",
            "intent": "OBSERVED_SYMPTOM",
            "processingType": "INVESTIGATION",
            "reportedSystemCandidateId": None,
            "facts": [],
            "assumptions": [],
            "unknowns": [],
            "details": request_details(),
        }
    ]
    payload["imageObservations"] = [image_observation()]
    return payload


def test_image_observation_matches_run_input() -> None:
    payload = inquiry_request()
    payload["messages"] = [
        image_message() | {"imageAttachments": [{"attachmentId": OTHER_ID, "digest": "a" * 64}]}
    ]
    request = AiRunRequest.model_validate(payload)
    result = AiResult.model_validate(observation_result())
    result.validate_image_observations_for(request)
    assert result.model_dump(mode="json")["imageObservations"][0]["messageId"] == RUN_ID


@pytest.mark.parametrize(
    "field,value",
    [
        ("attachmentId", RUN_ID),
        ("messageId", OTHER_ID),
        ("messageRevision", 2),
        ("problemRef", "new:missing"),
    ],
)
def test_image_observation_rejects_mismatched_input(field: str, value: object) -> None:
    payload = inquiry_request()
    payload["messages"] = [
        image_message() | {"imageAttachments": [{"attachmentId": OTHER_ID, "digest": "a" * 64}]}
    ]
    result_payload = observation_result()
    result_payload["imageObservations"][0][field] = value
    if field == "problemRef":
        result_payload["imageObservations"][0]["questionRef"] = None
    with pytest.raises(ValueError):
        AiResult.model_validate(result_payload).validate_image_observations_for(
            AiRunRequest.model_validate(payload)
        )


@pytest.mark.parametrize("change", ["duplicate", "too_many_per_image", "question", "blank", "ref"])
def test_image_observation_rejects_invalid_results(change: str) -> None:
    payload = observation_result()
    observation = payload["imageObservations"][0]
    if change == "duplicate":
        payload["imageObservations"].append(observation.copy())
    elif change == "too_many_per_image":
        payload["imageObservations"] = [
            observation | {"observationRef": f"obs-{n}"} for n in range(4)
        ]
    elif change == "question":
        observation["questionRef"] = "other"
    elif change == "blank":
        observation["text"] = "  "
    else:
        observation["observationRef"] = "invalid ref"
    with pytest.raises(ValidationError):
        AiResult.model_validate(payload)


def test_image_observation_rejects_failed_run_and_run_mismatch() -> None:
    payload = observation_result()
    payload.update(
        {
            "status": "FAILED",
            "answer": None,
            "questions": [],
            "problemProposals": [],
            "error": {"code": "INVALID_OUTPUT", "message": "실패", "retryable": False},
        }
    )
    payload["imageObservations"][0]["questionRef"] = None
    with pytest.raises(ValidationError):
        AiResult.model_validate(payload)

    result = AiResult.model_validate(observation_result())
    request_payload = inquiry_request()
    request_payload["inquiryVersion"] = 2
    with pytest.raises(ValueError, match="실행 문맥"):
        result.validate_image_observations_for(AiRunRequest.model_validate(request_payload))


def test_image_observations_absent_from_legacy_result_serialization() -> None:
    assert "imageObservations" not in AiResult.model_validate(inquiry_result()).model_dump(
        mode="json"
    )


@pytest.mark.parametrize(
    "bodies",
    [
        ("오류가 났나요?", "화면에서 오류가 났나요?"),
        ("화면에서 오류가 났나요?", "오류가 났나요?"),
        ("오류가 났나요?", "화면을 확인해 주세요.\n오류가 났나요?"),
    ],
)
def test_result_allows_questions_with_overlapping_text(bodies) -> None:
    """본문 빈도를 질문 횟수로 해석하지 않고 문제별 메타데이터를 검사한다."""
    payload = inquiry_result()
    first = payload["questions"][0] | {"text": bodies[0]}
    second = first | {"problemRef": "new:two", "bundleRef": "bundle-2", "text": bodies[1]}
    result = AiResult.model_validate(
        payload
        | {
            "questions": [first, second],
            "answer": f"문제 1\n{bodies[0]}\n\n문제 2\n{bodies[1]}",
        }
    )
    assert [question.text for question in result.questions] == list(bodies)


def test_follow_up_flag_is_backward_compatible_and_strict() -> None:
    payload = inquiry_result()
    assert AiResult.model_validate(payload).requiresFollowUp is False
    assert AiResult.model_validate(payload | {"requiresFollowUp": True}).requiresFollowUp is True
    for invalid in (None, 1, "true"):
        with pytest.raises(ValidationError):
            AiResult.model_validate(payload | {"requiresFollowUp": invalid})


def test_follow_up_flag_is_rejected_for_failed_result() -> None:
    failure = inquiry_result() | {
        "status": "FAILED",
        "answer": None,
        "problemProposals": [],
        "questions": [],
        "error": {"code": "INVALID_OUTPUT", "message": "실패", "retryable": False},
        "requiresFollowUp": True,
    }
    with pytest.raises(ValidationError):
        AiResult.model_validate(failure)


@pytest.mark.parametrize("missing", ["question", "guide"])
def test_result_still_rejects_missing_required_body(missing) -> None:
    """본문 검사를 단순화해도 질문·가이드 누락은 허용하지 않는다."""
    payload = inquiry_result()
    if missing == "question":
        payload["answer"] = "문의 내용을 확인했습니다."
    else:
        payload["guide"] = {
            "problemRef": "new:two",
            "messageText": "가이드를 확인하세요.",
            "evidenceIds": [RUN_ID],
        }
        payload["evidence"] = [knowledge_evidence()]
    with pytest.raises(ValidationError):
        AiResult.model_validate(payload)


def test_result_still_rejects_multiple_question_bundles_for_one_problem() -> None:
    """문구 포함과 별개로 문제당 질문 묶음 하나라는 제한을 유지한다."""
    payload = inquiry_result()
    payload["questions"].append(payload["questions"][0] | {"bundleRef": "another-bundle"})
    with pytest.raises(ValidationError):
        AiResult.model_validate(payload)


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


def case_proposal(*, reusable: bool = False) -> dict:
    """공통 DTO 검사용 사례 본문을 만든다. 실제 사례나 승인 결과가 아니다."""
    return {
        "reusable": reusable,
        "title": "해결 사례" if reusable else None,
        "action": "설정 변경" if reusable else None,
        "verification": "동작 확인" if reusable else None,
        "applicability": "전자결재" if reusable else None,
        "limitations": [],
        "appliesToSystemVersion": None,
        "accessScope": "SYSTEM_STAFF" if reusable else None,
        "sourceMessageIds": [RUN_ID] if reusable else [],
        "entityLinks": [],
        "problemStatement": "문서 조회 실패" if reusable else None,
    }


def test_case_proposal_reusability() -> None:
    payload = case_proposal()
    assert not CaseProposal.model_validate(payload).reusable
    with pytest.raises(ValidationError):
        CaseProposal.model_validate(payload | {"reusable": True})
    with pytest.raises(ValidationError):
        CaseProposal.model_validate(payload | {"title": "임의 제목"})


@pytest.mark.parametrize(
    "mutation", ["body_present", "links_present", "missing_body", "extra_field"]
)
def test_case_proposal_enforces_reusable_fields(mutation) -> None:
    """재사용 여부별 본문·출처 조합과 승인 필드의 임의 추가를 거부한다."""
    data = case_proposal(reusable=mutation == "missing_body")
    if mutation == "body_present":
        data["title"] = "재사용하지 않는 사례의 제목"
    elif mutation == "links_present":
        data["sourceMessageIds"] = [RUN_ID]
    elif mutation == "missing_body":
        data["action"] = None
    else:
        data["approved"] = True
    with pytest.raises(ValidationError):
        CaseProposal.model_validate(data)


def test_failed_result_cannot_carry_new_answer() -> None:
    """실패한 실행은 성공 문구나 새 답변을 반환할 수 없다."""
    payload = inquiry_result() | {
        "status": "FAILED",
        "answer": "성공했습니다",
        "questions": [],
        "error": {"code": "INVALID_OUTPUT", "message": "잘못된 출력", "retryable": False},
    }
    with pytest.raises(ValidationError):
        AiResult.model_validate(payload)


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
    proposal = case_proposal(reusable=True)
    payload = inquiry_result() | {
        "kind": "CASE_DRAFT",
        "answer": None,
        "questions": [],
        "caseProposal": proposal,
    }
    assert AiResult.model_validate(payload).caseProposal.reusable
    with pytest.raises(ValidationError):
        AiResult.model_validate(payload | {"requiresFollowUp": True})
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


# --- 문의 전체 과정 맥락(#198 ①): 처리 기록·해결 확인 이력·상담 근거 ---

_RECORD_BASE = {
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
}


def _uuid(index: int) -> str:
    return f"{index:08x}-0000-4000-8000-000000000000"


def _related(record_id: str = OTHER_ID, **changes) -> dict:
    return {
        "id": record_id,
        "kind": "FAILED_ATTEMPT",
        "action": "캐시를 지움",
        "verification": None,
        "confirmedCause": None,
        "appliesToSystemVersion": None,
        "createdAt": "2026-11-02T00:30:00Z",
    } | changes


def _history(request_id: str = OTHER_ID, **changes) -> dict:
    return {
        "requestId": request_id,
        "source": "GUIDE",
        "sourceId": RUN_ID,
        "state": "UNRESOLVED",
        "requestedAt": "2026-11-02T00:10:00Z",
        "respondedAt": "2026-11-02T00:20:00Z",
    } | changes


def _evidence(evidence_id: str = OTHER_ID) -> dict:
    return {
        "id": evidence_id,
        "documentId": RUN_ID,
        "documentVersion": 1,
        "kind": "GUIDE",
        "title": "첨부파일 안내",
        "excerpt": "첨부파일 열람 방법",
        "systemId": RUN_ID,
        "appliesToSystemVersion": None,
        "applicability": "전자결재",
        "accessScope": "EMPLOYEE",
        "origin": "SYNTHETIC",
    }


def test_work_record_without_process_context_keeps_empty_defaults() -> None:
    """이전 생산자는 새 필드를 보내지 않는다. 생략하면 빈 목록이다."""
    record = WorkRecordContext.model_validate(_RECORD_BASE)
    assert record.relatedRecords == []
    assert record.resolutionHistory == []
    assert record.consultedKnowledge == []


def test_work_record_accepts_process_context() -> None:
    record = WorkRecordContext.model_validate(
        _RECORD_BASE
        | {
            "relatedRecords": [
                _related(),
                _related("11111111-1111-4111-8111-111111111111", kind="PROGRESS"),
            ],
            "resolutionHistory": [
                _history(),
                _history("22222222-2222-4222-8222-222222222222", respondedAt=None, state="PENDING"),
            ],
            "consultedKnowledge": [_evidence()],
        }
    )
    assert [item.kind for item in record.relatedRecords] == ["FAILED_ATTEMPT", "PROGRESS"]
    assert record.resolutionHistory[0].state == "UNRESOLVED"
    assert record.consultedKnowledge[0].kind == "GUIDE"


@pytest.mark.parametrize(
    "extra",
    [
        {"relatedRecords": [_related() | {"unknown": 1}]},
        {"resolutionHistory": [_history() | {"unknown": 1}]},
        {"relatedRecords": [_related(kind="UNKNOWN_KIND")]},
        {"resolutionHistory": [_history(state="DONE")]},
        {"relatedRecords": [_related(action="   ")]},
        {"relatedRecords": [_related(action="가" * 4001)]},
        {"relatedRecords": [_related(verification="가" * 4001)]},
        {"relatedRecords": [_related(RUN_ID)]},
        {"relatedRecords": [_related(), _related()]},
        {"resolutionHistory": [_history(), _history()]},
        {"resolutionHistory": [_history(RUN_ID)]},
        {"consultedKnowledge": [_evidence(), _evidence()]},
        {"relatedRecords": [_related(_uuid(index)) for index in range(21)]},
        {"resolutionHistory": [_history(_uuid(index)) for index in range(21)]},
        {"consultedKnowledge": [_evidence(_uuid(index)) for index in range(11)]},
    ],
)
def test_work_record_rejects_invalid_process_context(extra: dict) -> None:
    with pytest.raises(ValidationError):
        WorkRecordContext.model_validate(_RECORD_BASE | extra)


def test_inquiry_request_still_rejects_a_work_record_with_process_context() -> None:
    """맥락은 사례 실행(workRecord)에만 있다. 상담 실행에는 workRecord 자체를 둘 수 없다."""
    payload = inquiry_request()
    payload["workRecord"] = _RECORD_BASE | {"relatedRecords": [_related()]}
    with pytest.raises(ValidationError):
        AiRunRequest.model_validate(payload)


def _candidate(candidate_id: str = OTHER_ID, kind: str = "SYMPTOM", system: str = RUN_ID) -> dict:
    return {"id": candidate_id, "kind": kind, "label": "첨부파일이 열리지 않음", "systemId": system}


def test_work_record_link_candidates_default_to_empty_and_accept_valid_nodes() -> None:
    assert WorkRecordContext.model_validate(_RECORD_BASE).linkCandidates == []
    record = WorkRecordContext.model_validate(
        _RECORD_BASE | {"linkCandidates": [_candidate(), _candidate(_uuid(7), "CHECK_ITEM")]}
    )
    assert [item.kind for item in record.linkCandidates] == ["SYMPTOM", "CHECK_ITEM"]


@pytest.mark.parametrize(
    "candidates",
    [
        [_candidate(), _candidate()],  # 식별자 중복
        [_candidate(system=OTHER_ID)],  # 원 기록과 다른 시스템
        [_candidate(kind="CAUSE")],  # 원인은 연결 대상이 아니다
        [_candidate(_uuid(index)) for index in range(31)],  # 증상 상한 30
        [_candidate(_uuid(index), "CHECK_ITEM") for index in range(41)],  # 확인 항목 상한 40
        [_candidate() | {"label": "   "}],
        [_candidate() | {"unknown": 1}],
    ],
)
def test_work_record_rejects_invalid_link_candidates(candidates: list) -> None:
    with pytest.raises(ValidationError):
        WorkRecordContext.model_validate(_RECORD_BASE | {"linkCandidates": candidates})
