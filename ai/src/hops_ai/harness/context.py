"""하네스 실행 문맥. 권한 참조는 향후 Spring 인증 어댑터가 검증해 제공해야 한다."""

import asyncio
from dataclasses import dataclass, field
from datetime import datetime

from hops_ai.harness.budget import Clock, RunBudget
from hops_ai.harness.errors import InputRejected, RunCancelled
from hops_ai.harness.policy import ToolPolicy
from hops_ai.services.runs.events import Role, RunEvents
from hops_ai.settings import Settings


@dataclass
class RunContext:
    run_id: str
    inquiry_id: str
    inquiry_version: int
    access_scope_ref: str
    budget: RunBudget
    tool_policy: ToolPolicy
    events: RunEvents | None = field(default=None, repr=False)
    _cancelled: asyncio.Event = field(default_factory=asyncio.Event, repr=False)
    _execution_claimed: bool = field(default=False, init=False, repr=False)

    @classmethod
    def create(
        cls,
        *,
        run_id: str,
        inquiry_id: str,
        inquiry_version: int,
        access_scope_ref: str,
        settings: Settings,
        deadline_at: datetime | None = None,
        tool_policy: ToolPolicy | None = None,
        clock: Clock | None = None,
    ) -> "RunContext":
        return cls(
            run_id=run_id,
            inquiry_id=inquiry_id,
            inquiry_version=inquiry_version,
            access_scope_ref=access_scope_ref,
            budget=RunBudget.start(settings, deadline_at=deadline_at, clock=clock),
            tool_policy=tool_policy or ToolPolicy(),
        )

    @property
    def is_cancelled(self) -> bool:
        return self._cancelled.is_set()

    def cancel(self) -> None:
        self._cancelled.set()

    async def wait_cancelled(self) -> None:
        """취소 신호를 기다린다. 실행기는 모델·역할 응답을 기다리는 중에도 이 신호를 감시한다."""
        await self._cancelled.wait()

    def claim_execution(self) -> None:
        """이 문맥을 한 실행에만 연결한다. 영속 runId 중복 검사는 RunLedger가 별도로 맡는다."""
        if self._execution_claimed:
            raise InputRejected("이미 실행에 사용한 문맥이다")
        self._execution_claimed = True

    def ensure_active(self) -> None:
        if self.is_cancelled:
            raise RunCancelled
        self.budget.check_deadline()

    def report_progress(self, role: Role, summary: str | None = None) -> None:
        self.ensure_active()
        if self.events is not None:
            self.events.progress(role, summary)
