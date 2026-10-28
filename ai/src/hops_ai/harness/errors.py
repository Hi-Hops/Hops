"""하네스가 실행 중 만나는 실패와 agent-1 오류로의 변환.

계약 오류에는 미리 정한 안전한 문구만 담는다. 원시 예외 메시지·비밀값·내부 사고 과정은 담지 않는다.
상세 내용(detail)은 로그용이다.
"""

from dataclasses import dataclass
from typing import ClassVar

from hops_ai.contracts.agent_1 import AgentError, AgentErrorCode, ResultStatus


class HarnessError(Exception):
    """계약 오류로 바꿀 수 있는 실패. 하위 클래스가 코드·상태·재시도 가능 여부를 정한다."""

    code: ClassVar[AgentErrorCode] = "INTERNAL_ERROR"
    status: ClassVar[ResultStatus] = "ERROR"
    retryable: ClassVar[bool] = False
    safe_message: ClassVar[str] = "내부 오류가 발생했다"

    def __init__(self, detail: str = "") -> None:
        super().__init__(detail or self.safe_message)
        self.detail = detail


class BudgetExceeded(HarnessError):
    """실행 예산(시간·호출 횟수)을 넘었다. 같은 실행에서 자동으로 반복하지 않는다."""


class DeadlineExceeded(BudgetExceeded):
    code = "DEADLINE_EXCEEDED"
    status = "TIMED_OUT"
    safe_message = "실행 제한 시간을 초과했다"


class LlmCallLimitExceeded(BudgetExceeded):
    code = "BUDGET_EXCEEDED"
    safe_message = "모델 호출 허용 횟수를 초과했다"


class RunCancelled(HarnessError):
    """새 문의 버전으로 대체되거나 취소됐다. 늦게 도착한 결과는 사용하지 않는다."""

    code = "SUPERSEDED"
    status = "CANCELLED"
    safe_message = "새 문의 버전 또는 취소 요청으로 실행이 중단됐다"


class PolicyDenied(HarnessError):
    """허용되지 않은 요청이다. 다른 도구나 경로로 우회하지 않는다."""

    code = "ACCESS_DENIED"
    safe_message = "허용되지 않은 요청이다"


class GuardViolation(HarnessError):
    """입력 또는 출력이 가드레일을 통과하지 못했다."""


class InputRejected(GuardViolation):
    code = "INVALID_INPUT"
    safe_message = "입력이 허용 기준을 충족하지 않는다"


class OutputRejected(GuardViolation):
    code = "INVALID_OUTPUT"
    safe_message = "모델 출력이 검증을 통과하지 못했다"


class TransientError(HarnessError):
    """일시 오류. 자동 재시도 대상이며 모델 연결 장애·시간 초과·속도 제한이 여기에 해당한다."""

    code = "MODEL_UNAVAILABLE"
    retryable = True
    safe_message = "모델에 일시적으로 연결하지 못했다"

    def __init__(self, detail: str = "", *, retry_after: float | None = None) -> None:
        super().__init__(detail)
        # 서버가 알려 준 대기 시간(초). 속도 제한(429) 응답의 Retry-After 등
        self.retry_after = retry_after


class CallTimeoutError(TransientError):
    code = "MODEL_TIMEOUT"
    safe_message = "모델 응답이 제한 시간을 넘었다"


class RetrievalUnavailable(HarnessError):
    """검색 시스템 장애. '검색 결과 없음'과 구분하며 모델 호출 상한과는 무관하다."""

    code = "RETRIEVAL_UNAVAILABLE"
    retryable = True
    safe_message = "검색 시스템에 일시적으로 연결하지 못했다"


@dataclass(frozen=True)
class Failure:
    status: ResultStatus
    error: AgentError


def describe_failure(error: BaseException) -> Failure:
    """예외를 결과의 status와 안전한 오류로 바꾼다. 알 수 없는 예외는 내부 오류로만 알린다."""
    if isinstance(error, HarnessError):
        return Failure(
            status=error.status,
            error=AgentError(
                code=error.code,
                message=error.safe_message,
                retryable=error.retryable,
            ),
        )
    return Failure(
        status=HarnessError.status,
        error=AgentError(
            code=HarnessError.code,
            message=HarnessError.safe_message,
            retryable=HarnessError.retryable,
        ),
    )
