"""도구의 기존 권한·인자 검사에 공통 실행 예산과 안전한 실패 처리를 더한다."""

from collections.abc import Callable

from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import (
    ComponentNotReady,
    HarnessError,
    OutputRejected,
    PolicyDenied,
)
from hops_ai.services.tools.base import ToolGateway, ToolRequest, ToolResult
from hops_ai.services.tools.guarded import GuardedToolGateway


class ToolUnavailable(HarnessError):
    """읽기 도구의 실패. 원시 응답을 직원 답변으로 전달하지 않는다."""

    retryable = True
    safe_message = "조회 도구에 일시적으로 연결하지 못했다"


class BudgetedToolGateway:
    """등록·권한·인자 검사를 보존하고 한 run의 도구 호출 수를 누적한다."""

    def __init__(
        self,
        inner: ToolGateway,
        context: RunContext,
        *,
        ensure_active: Callable[[], None] | None = None,
    ) -> None:
        """검증된 gateway와 실행 문맥을 받는다. 테스트 대역을 자동으로 연결하지 않는다."""
        self._inner = inner
        self._context = context
        self._check_execution = ensure_active

    def _ensure_active(self) -> None:
        """종료된 실행과 취소·기한·누적 한도 위반을 차단한다."""
        if self._check_execution is not None:
            self._check_execution()
        self._context.ensure_active()
        self._context.budget.check_limits()

    async def call(self, role: AgentRole, request: ToolRequest) -> ToolResult:
        """호출을 예약하고 도구 실패·권한 거부를 정상 조회와 구분한다.

        문맥의 기본 거부 정책은 원시 어댑터를 전달해도 유지한다. 등록·인자 검사 없는
        gateway는 실행하지 않는다. 도구를 자동 재시도하거나 다른 도구로 우회하지 않는다.
        """
        self._ensure_active()
        self._context.tool_policy.authorize(role, request.tool_name)
        if not isinstance(self._inner, GuardedToolGateway):
            raise ComponentNotReady("등록·인자 검증 도구 gateway")
        self._context.budget.start_tool_call()
        try:
            result = await self._inner.call(role, request)
        except HarnessError:
            raise
        except Exception:
            raise ToolUnavailable from None

        self._ensure_active()
        if not isinstance(result, ToolResult):
            raise OutputRejected("도구 응답 형식이 올바르지 않다")
        if result.status == "DENIED":
            raise PolicyDenied
        if result.status != "OK":
            raise ToolUnavailable
        return result
