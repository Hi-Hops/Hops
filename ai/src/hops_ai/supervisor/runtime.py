"""공통 실행 제한을 Supervisor 요청·State·wire 오류에 연결하는 어댑터."""

from collections.abc import Awaitable
from dataclasses import replace
from datetime import datetime

from pydantic import ValidationError

from hops_ai.contracts.wire import AiError, AiRunRequest
from hops_ai.harness.errors import (
    BudgetExceeded,
    ComponentNotReady,
    DeadlineExceeded,
    InputRejected,
    OutputRejected,
    PolicyDenied,
    RetrievalUnavailable,
    RunCancelled,
    TransientError,
)
from hops_ai.harness.execution import RunExecution
from hops_ai.services.llm.budgeted import BudgetedLLMClient
from hops_ai.services.llm.usage import UsageRecorder
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.budgeted import BudgetedToolGateway, ToolUnavailable
from hops_ai.supervisor.state import RunPhase, SupervisorState, Usage


class SupervisorRuntime:
    """공통 실행기를 한 Supervisor run에 연결한다.

    시간·호출·토큰·재시도 계산은 RunBudget과 공통 wrapper가 담당한다.
    이 클래스는 요청의 ID와 한도를 연결하고, 사용량을 State 형태로 옮긴다.
    같은 RunContext 재사용은 RunExecution이 거부하며 영속 중복 접수는 RunLedger 책임이다.
    """

    def __init__(self, request: AiRunRequest, services: SharedServices) -> None:
        """요청과 실행 문맥을 대조하고 공통 예산·LLM·도구 wrapper를 연결한다.

        Args:
            request: 초기 State에서 검증한 실행 입력. 모델이 실행 한도를 변경하지 못한다.
            services: 동일 run/문의/입력 버전의 문맥과 원시 LLM·검색·검증된 도구.
                클라이언트의 생성과 종료는 호출 측이 관리한다.

        Raises:
            InputRejected: 문맥 식별자가 다르거나 이미 사용한 예산·LLM wrapper를 넘긴 경우.
        """
        context = services.runtime
        if (context.run_id, context.inquiry_id, context.inquiry_version) != (
            request.runId,
            request.inquiryId,
            request.inquiryVersion,
        ):
            raise InputRejected("실행 문맥과 요청의 식별자가 다르다")
        if isinstance(services.llm, BudgetedLLMClient):
            raise InputRejected("모델 예산 wrapper를 중복 연결할 수 없다")
        budget = context.budget
        if any(
            (
                budget.llm_calls_used,
                budget.input_tokens_used,
                budget.output_tokens_used,
                budget.tool_calls_used,
                budget.retries_used,
            )
        ):
            raise InputRejected("사용한 실행 예산을 새 실행에 연결할 수 없다")

        # 재사용 문맥은 한도를 바꾸기 전에 거부하여 진행 중인 실행 예산을 보존한다.
        execution = RunExecution(context)
        # wire 필드를 공통 실행기의 숫자 한도로 변환하는 책임만 여기 둔다.
        limits = request.limits
        budget.constrain(
            max_model_calls=limits.maxModelCalls,
            max_input_tokens=limits.maxInputTokens,
            max_total_output_tokens=limits.maxOutputTokens,
            max_tool_calls=limits.maxToolCalls,
            max_run_retries=limits.maxRetries,
            deadline_at=datetime.fromisoformat(request.expiresAt),
        )
        self._request = request
        self._context = context
        self._execution = execution
        self.services = replace(
            services,
            llm=BudgetedLLMClient(
                services.llm,
                budget,
                UsageRecorder(),
                ensure_active=self._execution.ensure_active,
            ),
            tools=BudgetedToolGateway(
                services.tools,
                context,
                ensure_active=self._execution.ensure_active,
            ),
        )

    def ensure_active(self) -> None:
        """공통 실행기의 취소·기한·누적 한도 검사를 호출한다."""
        self._execution.ensure_active()

    def sync_state(self, state: SupervisorState) -> SupervisorState:
        """공통 예산의 현재 사용량과 취소 여부를 새 State에 복사한다.

        실패 결과 작성에도 필요하므로 종료·기한 초과 뒤에도 호출할 수 있다.
        State나 역할이 제공한 사용량으로 실제 예산을 덮어쓰지 않는다.
        """
        if state["input"] != self._request:
            raise InputRejected("다른 입력의 State를 동기화할 수 없다")
        budget = self._context.budget
        cancelled = self._context.is_cancelled or state["cancellation"]["cancelled"]
        return {
            **state,
            "usage": Usage(
                calls=budget.llm_calls_used,
                inputTokens=budget.input_tokens_used,
                outputTokens=budget.output_tokens_used,
                toolCalls=budget.tool_calls_used,
                retries=budget.retries_used,
            ),
            "cancellation": {
                "cancelled": cancelled,
                "reason": "SUPERSEDED" if cancelled else None,
            },
        }

    async def run[T](self, operation: Awaitable[T]) -> T:
        """그래프 대기를 공통 실행기에 맡긴다. 취소·기한·정리 방식은 harness가 관리한다."""
        return await self._execution.run(operation)


def describe_run_failure(error: Exception) -> tuple[RunPhase, AiError]:
    """내부 실패를 hops-system-1의 종료 상태와 고정된 안전한 오류로 변환한다.

    예외의 detail·str(error)는 사용하지 않는다. 하네스의 이전 코드나 원격 오류의
    문자열을 그대로 wire로 전달하지 않고, 알려진 실패 종류만 명시적으로 대응한다.
    """
    phase: RunPhase = "FAILED"
    if isinstance(error, RunCancelled):
        phase, code, message, retryable = (
            "SUPERSEDED",
            "SUPERSEDED",
            "새 입력 또는 취소로 실행이 중단됐다",
            False,
        )
    elif isinstance(error, DeadlineExceeded):
        phase, code, message, retryable = (
            "TIMED_OUT",
            "DEADLINE_EXCEEDED",
            "실행 기한을 초과했다",
            False,
        )
    elif isinstance(error, BudgetExceeded):
        code, message, retryable = "BUDGET_EXCEEDED", "실행 예산을 초과했다", False
    elif isinstance(error, PolicyDenied):
        code, message, retryable = "AUTH_CONTEXT_INVALID", "접근 문맥을 확인할 수 없다", False
    elif isinstance(error, RetrievalUnavailable):
        code, message, retryable = "SEARCH_UNAVAILABLE", "검색 서비스를 사용할 수 없다", True
    elif isinstance(error, ToolUnavailable):
        code, message, retryable = "TOOL_UNAVAILABLE", "조회 도구를 사용할 수 없다", True
    elif isinstance(error, TransientError):
        code, message, retryable = "MODEL_UNAVAILABLE", "모델 응답을 받을 수 없다", True
    elif isinstance(error, InputRejected):
        code, message, retryable = "INVALID_REQUEST", "실행 입력이 허용 기준과 다르다", False
    elif isinstance(error, (OutputRejected, ValidationError)):
        code, message, retryable = "INVALID_OUTPUT", "실행 결과를 적용할 수 없다", False
    elif isinstance(error, ComponentNotReady):
        code, message, retryable = "SERVICE_UNAVAILABLE", "실행 구성 요소가 준비되지 않았다", False
    else:
        code, message, retryable = "SERVICE_UNAVAILABLE", "실행을 완료할 수 없다", False
    return phase, AiError(code=code, message=message, retryable=retryable)
