"""서비스 간 계약(wire)과 내부 타입을 서로 바꾼다. 계약이 달라져도 변경이 이 파일에 모인다.

- 입력: AiRequest → 분석 입력(agent-1 타입)
- 출력: 분석 결과 → AiSuccess / AiFailure

모델이 만든 값은 여기서도 믿지 않는다. 내부 전용 경로(SEARCH·DIAGNOSE)는 외부 결과에 나가지 않는다.
"""

from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Literal

from pydantic import ValidationError

from hops_ai.api import schemas as wire
from hops_ai.contracts import agent_1 as agent
from hops_ai.contracts.analysis import AnalysisFailure, AnalysisInput, AnalysisOutcome
from hops_ai.harness.errors import InputRejected, OutputRejected

EXTERNAL_ACTIONS = {"ASK_USER", "PROPOSE_TICKETS", "OUT_OF_SCOPE"}

# 메시지 발신자 표기: 서비스 간 계약은 USER|AI, agent-1은 USER|ASSISTANT
SENDER_FROM_WIRE: dict[str, Literal["USER", "ASSISTANT"]] = {"USER": "USER", "AI": "ASSISTANT"}

# agent-1 오류 코드를 서비스 간 계약의 ErrorCode로 바꾼다. 계약 리뷰 전 제안이다.
# - 모델 호출 1회의 시간 초과는 일시 장애로 보고 재시도 가능으로 둔다(실행 전체 마감 초과와 구분)
# - 새 버전으로 대체된 실행은 문의 버전 충돌로 알린다
# - 호출 상한 초과·내부 오류·출력 검증 실패는 기술 오류(SERVICE_UNAVAILABLE)이고 재시도하지 않는다
ERROR_CODE_TO_WIRE: dict[str, wire.ErrorCode] = {
    "INVALID_INPUT": "INVALID_INPUT",
    "INVALID_OUTPUT": "SERVICE_UNAVAILABLE",
    "MODEL_UNAVAILABLE": "MODEL_UNAVAILABLE",
    "MODEL_TIMEOUT": "MODEL_UNAVAILABLE",
    "RETRIEVAL_UNAVAILABLE": "SERVICE_UNAVAILABLE",
    "TOOL_FAILURE": "SERVICE_UNAVAILABLE",
    "ACCESS_DENIED": "ACCESS_DENIED",
    "DEADLINE_EXCEEDED": "DEADLINE_EXCEEDED",
    "BUDGET_EXCEEDED": "SERVICE_UNAVAILABLE",
    "SUPERSEDED": "INQUIRY_VERSION_CONFLICT",
    "INTERNAL_ERROR": "SERVICE_UNAVAILABLE",
}


@dataclass(frozen=True)
class MappedRequest:
    data: AnalysisInput
    actor: agent.Actor
    access_scope_ref: str
    deadline_at: datetime


def parse_utc(value: str) -> datetime:
    return datetime.fromisoformat(value).astimezone(UTC)


def to_analysis(request: wire.AiRequest) -> MappedRequest:
    """AiRequest를 분석 입력으로 바꾼다. 계약을 어긋난 값은 InputRejected로 알린다."""
    try:
        data = AnalysisInput(
            run_id=request.runId,
            inquiry_id=request.inquiryId,
            inquiry_version=request.inquiryVersion,
            messages=[
                agent.Message(
                    messageId=message.id,
                    revision=message.revision,
                    sender=SENDER_FROM_WIRE[message.sender],
                    content=message.content,
                )
                for message in request.messages
            ],
            existing_problems=[
                agent.Problem(
                    ref=problem.id,
                    summary=problem.summary,
                    category=problem.category,
                    questionCount=problem.questionCount,
                )
                for problem in request.existingProblems
            ],
            existing_work_items=[
                agent.WorkItem.model_validate(item.model_dump())
                for item in request.existingWorkItems
            ],
            # 티켓을 보낼 수 없는 부서(요청자 본인 부서 등)는 AI에게 후보로 주지 않는다
            departments=[
                agent.Department(
                    id=department.id,
                    code=department.code,
                    name=department.name,
                    responsibilities=department.responsibilities,
                )
                for department in request.departments
                if department.isRoutable
            ],
            evidence=[
                agent.Evidence.model_validate(item.model_dump()) for item in request.evidence
            ],
            resolution_targets=[],
        )
        actor = agent.Actor(
            userId=request.actor.userId,
            departmentId=request.actor.departmentId,
            roles=list(request.actor.roles),
        )
    except ValidationError as error:
        raise InputRejected(
            f"요청을 내부 형식으로 바꾸지 못했다: {error.error_count()}건"
        ) from error
    return MappedRequest(
        data=data,
        actor=actor,
        access_scope_ref=request.actor.accessScopeRef,
        deadline_at=parse_utc(request.deadlineAt),
    )


def to_ai_result(
    run_id: str,
    inquiry_id: str,
    inquiry_version: int,
    result: AnalysisOutcome | AnalysisFailure,
) -> wire.AiSuccess | wire.AiFailure:
    ids = {"runId": run_id, "inquiryId": inquiry_id, "inquiryVersion": inquiry_version}
    if isinstance(result, AnalysisFailure):
        return wire.AiFailure(
            schemaVersion=wire.SCHEMA_VERSION,
            **ids,
            status=result.status if result.status != "OK" else "ERROR",
            error=wire.ApiErrorBody(
                code=ERROR_CODE_TO_WIRE[result.error.code],
                message=result.error.message,
                requestId=run_id,
                retryable=result.error.retryable,
            ),
        )
    problems = []
    for problem in result.problems:
        if problem.nextAction not in EXTERNAL_ACTIONS:
            raise OutputRejected(f"외부로 보낼 수 없는 처리 경로다: {problem.nextAction}")
        problems.append(
            wire.AiProblem(
                ref=problem.ref,
                summary=problem.summary,
                category=problem.category,
                questionCount=problem.questionCount,
                action=problem.nextAction,
                question=problem.question,
                evidenceIds=problem.evidenceIds,
            )
        )
    return wire.AiSuccess(
        schemaVersion=wire.SCHEMA_VERSION,
        **ids,
        status="OK",
        answer=result.answer,
        problems=problems,
        ticketProposals=list(result.ticket_proposals),
        error=None,
    )
