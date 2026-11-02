"""검증된 실행 입력의 기한·한도를 확인하고 RunLedger에 접수한다.

이 서비스는 작업을 실행하거나 HTTP 202 응답을 만들지 않는다.
"""

from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID

from hops_ai.contracts.wire import AiRunRequest
from hops_ai.persistence.run_ledger import RunAcceptance, RunConflict, RunLedger, input_digest


@dataclass(frozen=True)
class ExecutionLimits:
    maxModelCalls: int
    maxInputTokens: int
    maxOutputTokens: int
    maxToolCalls: int
    maxRetries: int


class AdmissionRejected(ValueError):
    """새 실행의 기한 또는 요청 한도가 서버 기준을 충족하지 않는다."""


class RunAdmissionService:
    def __init__(
        self,
        ledger: RunLedger,
        ceilings: ExecutionLimits,
        *,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._ledger = ledger
        self._ceilings = ceilings
        self._clock = clock or (lambda: datetime.now(UTC))
        self._validate_limits(ceilings)

    @staticmethod
    def _validate_limits(limits: ExecutionLimits) -> None:
        for name in ExecutionLimits.__dataclass_fields__:
            value = getattr(limits, name)
            minimum = 0 if name == "maxRetries" else 1
            if type(value) is not int or value < minimum:
                raise AdmissionRejected("실행 한도는 허용된 정수 범위여야 합니다")

    async def accept(self, request: AiRunRequest) -> RunAcceptance:
        # DTO의 중첩 목록은 변경 가능하므로 저장할 입력을 다시 검증해 고정한다.
        request = AiRunRequest.model_validate(request.model_dump(mode="json"))
        payload = request.model_dump(mode="json")
        run_id = UUID(request.runId)

        # 재접수는 기존 입력과 결과를 재확인한다. 기존 실행을 다시 시작하지 않는다.
        existing = await self._ledger.get(run_id)
        if existing is not None:
            if existing.input_digest != input_digest(payload):
                raise RunConflict("동일 runId의 입력이 다릅니다")
            return RunAcceptance(created=False, snapshot=existing)

        try:
            expiry = datetime.fromisoformat(request.expiresAt)
        except ValueError as exc:
            raise AdmissionRejected("만료 시각이 올바르지 않습니다") from exc
        now = self._clock()
        if expiry.utcoffset() is None or now.utcoffset() is None or expiry <= now:
            raise AdmissionRejected("만료된 실행은 접수할 수 없습니다")

        limits = ExecutionLimits(
            **{name: getattr(request.limits, name) for name in ExecutionLimits.__dataclass_fields__}
        )
        self._validate_limits(limits)
        if any(
            getattr(limits, name) > getattr(self._ceilings, name)
            for name in ExecutionLimits.__dataclass_fields__
        ):
            raise AdmissionRejected("요청 실행 한도가 서버 허용 범위를 초과했습니다")
        # 실제 원자적 접수와 경쟁 요청의 digest 비교는 DB 유일 제약이 담당한다.
        return await self._ledger.accept(run_id, payload)
