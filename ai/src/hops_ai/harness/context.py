"""하네스 실행 문맥. 권한 참조는 향후 Spring 인증 어댑터가 검증해 제공해야 한다."""

import asyncio
from dataclasses import dataclass, field
from datetime import datetime

from hops_ai.harness.budget import Clock, RunBudget
from hops_ai.harness.errors import RunCancelled
from hops_ai.harness.policy import ToolPolicy
from hops_ai.settings import Settings


@dataclass
class RunContext:
    run_id: str
    inquiry_id: str
    inquiry_version: int
    access_scope_ref: str
    budget: RunBudget
    tool_policy: ToolPolicy
    _cancelled: asyncio.Event = field(default_factory=asyncio.Event, repr=False)

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

    def ensure_active(self) -> None:
        if self.is_cancelled:
            raise RunCancelled
        self.budget.check_deadline()
