"""한 번의 실행(runId)에 필요한 정보와 취소 신호. Runtime이 만들며 모델이 수정할 수 없다."""

import asyncio
from dataclasses import dataclass, field
from datetime import UTC, datetime

from hops_ai.contracts.agent_1 import Actor, AgentContext, AgentRole
from hops_ai.harness.budget import Clock, RunBudget
from hops_ai.harness.errors import RunCancelled
from hops_ai.harness.policy import ToolPolicy
from hops_ai.settings import Settings


def format_utc(moment: datetime) -> str:
    """계약의 UTC 시각 표기(YYYY-MM-DDTHH:mm:ssZ)로 바꾼다."""
    return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


@dataclass
class RunContext:
    run_id: str
    inquiry_id: str
    inquiry_version: int
    actor: Actor
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
        actor: Actor,
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
            actor=actor,
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
        """취소됐거나 마감이 지났으면 예외를 낸다. 에이전트는 단계 사이에서 호출한다."""
        if self.is_cancelled:
            raise RunCancelled
        self.budget.check_deadline()

    def agent_context(self, role: AgentRole) -> AgentContext:
        """역할에 전달할 agent-1 context. 마감은 남은 시간 기준이라 역할마다 늘어나지 않는다."""
        return AgentContext(
            actor=self.actor,
            accessScopeRef=self.access_scope_ref,
            deadlineAt=format_utc(self.budget.effective_deadline_at()),
            allowedTools=self.tool_policy.allowed_tools(role),
        )
