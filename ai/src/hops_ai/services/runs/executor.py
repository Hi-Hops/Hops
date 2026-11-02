"""접수한 실행을 백그라운드에서 돌리고 검증한 최종 결과만 RunLedger에 고정한다.

실행 파이프라인(Supervisor)은 주입받는다. 파이프라인이 없으면 실행기를 만들지 않으며,
라우트는 접수 대신 503을 반환한다. 실행되지 않을 run을 QUEUED로 남기지 않기 위해서다.
"""

import asyncio
import logging
from dataclasses import replace
from datetime import datetime
from typing import Protocol
from uuid import UUID

from pydantic import ValidationError

from hops_ai.contracts.runtime import AgentErrorCode, ResultStatus
from hops_ai.contracts.wire import AiError, AiResult, AiRunRequest
from hops_ai.harness.budget import Clock, RunBudget
from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import describe_failure
from hops_ai.harness.policy import ToolPolicy
from hops_ai.persistence.run_ledger import RunAcceptance, RunLedger
from hops_ai.services.runs.admission import ExecutionLimits, RunAdmissionService
from hops_ai.settings import Settings

logger = logging.getLogger(__name__)

# 동시 실행 상한에 걸렸을 때 Spring에 알려 줄 대기 시간(초).
BUSY_RETRY_AFTER_SECONDS = 5

# 하네스 내부 오류 코드를 Spring과 주고받는 AiError 코드로 바꾼다.
_WIRE_CODES: dict[AgentErrorCode, str] = {
    "INTERNAL_ERROR": "SERVICE_UNAVAILABLE",
    "DEADLINE_EXCEEDED": "DEADLINE_EXCEEDED",
    "BUDGET_EXCEEDED": "BUDGET_EXCEEDED",
    "SUPERSEDED": "SUPERSEDED",
    "ACCESS_DENIED": "AUTH_CONTEXT_INVALID",
    "INVALID_INPUT": "INVALID_REQUEST",
    "INVALID_OUTPUT": "INVALID_OUTPUT",
    "MODEL_UNAVAILABLE": "MODEL_UNAVAILABLE",
    "MODEL_TIMEOUT": "MODEL_UNAVAILABLE",
    "RETRIEVAL_UNAVAILABLE": "SEARCH_UNAVAILABLE",
}
_WIRE_STATUS: dict[ResultStatus, str] = {
    "ERROR": "FAILED",
    "TIMED_OUT": "TIMED_OUT",
    "CANCELLED": "SUPERSEDED",
}


class RunPipeline(Protocol):
    """한 run의 역할 조합을 실행해 최종 AiResult를 만든다. 업무 저장은 하지 않는다."""

    async def run(self, request: AiRunRequest, context: RunContext) -> AiResult: ...


class ExecutorBusy(Exception):
    """동시 실행 상한에 걸려 새 run을 접수하지 않았다."""

    retry_after = BUSY_RETRY_AFTER_SECONDS


def server_ceilings(settings: Settings) -> ExecutionLimits:
    """Spring이 요청할 수 있는 실행 한도의 서버 상한."""
    return ExecutionLimits(
        maxModelCalls=settings.max_llm_calls,
        maxInputTokens=settings.max_run_input_tokens,
        maxOutputTokens=settings.max_llm_calls * settings.max_output_tokens,
        maxToolCalls=settings.max_tool_calls,
        # 재시도도 모델 호출 횟수에 포함되므로 첫 시도를 뺀 만큼이 상한이다.
        maxRetries=settings.max_llm_calls - 1,
    )


def failure_result(request: AiRunRequest, status: str, error: AiError) -> AiResult:
    """새 제안 없이 오류만 담은 종료 결과."""
    return AiResult(
        contractVersion="hops-system-1",
        runId=request.runId,
        kind=request.kind,
        inquiryId=request.inquiryId,
        inquiryVersion=request.inquiryVersion,
        status=status,
        answer=None,
        problemProposals=[],
        questions=[],
        guide=None,
        ticketProposals=[],
        caseProposal=None,
        evidence=[],
        error=error,
    )


def _failure_from_exception(request: AiRunRequest, error: BaseException) -> AiResult:
    failure = describe_failure(error)
    return failure_result(
        request,
        _WIRE_STATUS[failure.status],
        AiError(
            code=_WIRE_CODES[failure.error.code],
            message=failure.error.message,
            retryable=failure.error.retryable,
        ),
    )


def _invalid_output(request: AiRunRequest) -> AiResult:
    return failure_result(
        request,
        "FAILED",
        AiError(
            code="INVALID_OUTPUT",
            message="실행 결과가 계약 검증을 통과하지 못했습니다",
            retryable=False,
        ),
    )


def _checked_result(request: AiRunRequest, result: object) -> AiResult:
    """파이프라인 결과를 다시 검증하고 같은 run·문의·버전의 결과인지 확인한다."""
    if not isinstance(result, AiResult):
        return _invalid_output(request)
    try:
        checked = AiResult.model_validate(result.model_dump(mode="json"))
    except ValidationError:
        return _invalid_output(request)
    if (
        checked.runId != request.runId
        or checked.kind != request.kind
        or checked.inquiryId != request.inquiryId
        or checked.inquiryVersion != request.inquiryVersion
    ):
        return _invalid_output(request)
    return checked


class RunExecutor:
    def __init__(
        self,
        ledger: RunLedger,
        admission: RunAdmissionService,
        pipeline: RunPipeline,
        settings: Settings,
        *,
        clock: Clock | None = None,
    ) -> None:
        self._ledger = ledger
        self._admission = admission
        self._pipeline = pipeline
        self._settings = settings
        self._clock = clock
        self._tasks: dict[UUID, asyncio.Task[None]] = {}
        # 동시 실행 수 확인과 접수가 경쟁하지 않도록 한 번에 하나씩 처리한다.
        self._submit_lock = asyncio.Lock()

    @property
    def active_runs(self) -> int:
        return len(self._tasks)

    async def submit(self, request: AiRunRequest) -> RunAcceptance:
        """새 run이면 접수 후 실행을 시작한다. 같은 입력의 재전송은 기존 상태만 돌려준다."""
        # 중첩 목록은 변경 가능하므로 실행에 쓸 입력을 다시 검증해 고정한다.
        request = AiRunRequest.model_validate(request.model_dump(mode="json"))
        run_id = UUID(request.runId)
        async with self._submit_lock:
            if self.active_runs >= self._settings.max_concurrent_runs:
                # 이미 접수된 run의 재전송은 상한과 무관하게 기존 상태를 확인할 수 있다.
                if await self._ledger.get(run_id) is None:
                    raise ExecutorBusy
            acceptance = await self._admission.accept(request)
            if acceptance.created:
                task = asyncio.create_task(self._execute(request), name=f"hops-run-{run_id}")
                self._tasks[run_id] = task
                task.add_done_callback(lambda _task: self._tasks.pop(run_id, None))
            return acceptance

    async def shutdown(self) -> None:
        """진행 중인 실행을 멈춘다. 결과는 저장하지 않으며 다음 기동 복구에서 UNKNOWN이 된다."""
        tasks = list(self._tasks.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    def _context(self, request: AiRunRequest) -> RunContext:
        deadline_at = datetime.fromisoformat(request.expiresAt)
        budget = RunBudget.start(self._settings, deadline_at=deadline_at, clock=self._clock)
        # 서버 설정과 요청 한도 중 작은 쪽을 쓴다.
        # 토큰·도구 호출 누적 한도는 모델·도구를 연결할 때 적용한다.
        budget = replace(
            budget,
            max_llm_calls=min(budget.max_llm_calls, request.limits.maxModelCalls),
            max_auto_retries=min(budget.max_auto_retries, request.limits.maxRetries),
        )
        return RunContext(
            run_id=request.runId,
            inquiry_id=request.inquiryId,
            inquiry_version=request.inquiryVersion,
            # 검색 권한은 Spring이 runId로 저장한 실행 문맥을 기준으로 다시 확인한다.
            access_scope_ref=request.runId,
            budget=budget,
            # 역할별 허용 도구는 역할 구현 시 정한다. 그 전에는 어떤 도구도 허용하지 않는다.
            tool_policy=ToolPolicy(),
        )

    async def _execute(self, request: AiRunRequest) -> None:
        run_id = UUID(request.runId)
        try:
            if not await self._ledger.start(run_id):
                logger.warning("접수한 실행을 시작 상태로 바꾸지 못했습니다")
                return
            context = self._context(request)
            try:
                raw = await asyncio.wait_for(
                    self._pipeline.run(request, context),
                    timeout=context.budget.remaining_seconds(),
                )
                result = _checked_result(request, raw)
            except TimeoutError:
                result = failure_result(
                    request,
                    "TIMED_OUT",
                    AiError(
                        code="DEADLINE_EXCEEDED",
                        message="실행 제한 시간을 초과했습니다",
                        retryable=False,
                    ),
                )
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - 원시 예외를 안전한 실패로 바꾼다
                # 예외 메시지에 문의 원문이 담길 수 있어 종류만 기록한다.
                logger.warning("AI 실행이 실패했습니다: %s", type(exc).__name__)
                result = _failure_from_exception(request, exc)
            snapshot = await self._ledger.get(run_id)
            last_seq = snapshot.last_seq if snapshot is not None else 0
            await self._ledger.finish(
                run_id, result.status, result.model_dump(mode="json"), last_seq
            )
        except asyncio.CancelledError:
            # 종료 중 취소는 결과를 만들지 않는다. 소유 세션이 닫히면 복구가 UNKNOWN으로 바꾼다.
            raise
        except Exception as exc:  # noqa: BLE001 - 저장 실패가 이벤트 루프를 멈추지 않게 한다
            logger.warning("AI 실행 결과를 저장하지 못했습니다: %s", type(exc).__name__)
