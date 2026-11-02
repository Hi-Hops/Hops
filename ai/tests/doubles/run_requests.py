"""실행 접수 검증에서 사용하는 가상 wire 요청."""

from uuid import uuid4

from hops_ai.contracts.wire import AiRunRequest


def inquiry_request(
    *, expires_at: str = "2026-11-02T01:00:00Z", max_calls: int = 4
) -> AiRunRequest:
    return AiRunRequest.model_validate(
        {
            "contractVersion": "hops-system-1",
            "runId": str(uuid4()),
            "kind": "INQUIRY_ANALYSIS",
            "inquiryId": str(uuid4()),
            "inquiryVersion": 1,
            "answerMessageId": str(uuid4()),
            "problemIds": [],
            "messages": [],
            "problems": [],
            "systems": [],
            "accessContext": {
                "actorId": str(uuid4()),
                "purpose": "EMPLOYEE_HELP",
                "systemIds": [],
                "allowedDocumentScopes": ["EMPLOYEE"],
                "policyRevision": 1,
            },
            "existingWorkItems": [],
            "workRecord": None,
            "expiresAt": expires_at,
            "limits": {
                "maxModelCalls": max_calls,
                "maxInputTokens": 1000,
                "maxOutputTokens": 500,
                "maxToolCalls": 3,
                "maxRetries": 1,
            },
        }
    )
