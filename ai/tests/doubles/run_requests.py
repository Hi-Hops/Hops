"""실행 접수 검증에서 사용하는 가상 wire 요청과 결과."""

from datetime import UTC, datetime, timedelta
from uuid import uuid4

from hops_ai.contracts.wire import AiResult, AiRunRequest


def expires_in(seconds: float) -> str:
    """현재 시각 기준 UTC Z 형식의 실행 기한."""
    moment = datetime.now(UTC) + timedelta(seconds=seconds)
    return moment.strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def succeeded(request: AiRunRequest, *, run_id: str | None = None) -> AiResult:
    """질문·티켓 없이 안내 문구만 담은 가상 상담 성공 결과."""
    return AiResult.model_validate(
        {
            "contractVersion": "hops-system-1",
            "runId": run_id or request.runId,
            "kind": request.kind,
            "inquiryId": request.inquiryId,
            "inquiryVersion": request.inquiryVersion,
            "status": "SUCCEEDED",
            "answer": "가상 안내 문구",
            "problemProposals": [],
            "questions": [],
            "guide": None,
            "ticketProposals": [],
            "caseProposal": None,
            "evidence": [],
            "error": None,
        }
    )


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
