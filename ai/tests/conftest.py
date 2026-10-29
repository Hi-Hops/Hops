import copy
import json
import os
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest

from hops_ai.api.schemas import AiRequest
from hops_ai.contracts.agent_1 import Actor, Department, Evidence, Message, Problem
from hops_ai.contracts.analysis import AnalysisInput
from hops_ai.fakes.llm import FakeLLMClient
from hops_ai.fakes.retrieval import FakeRetriever
from hops_ai.fakes.tools import FakeToolGateway
from hops_ai.harness.context import RunContext
from hops_ai.services.llm.budgeted import BudgetedLLMClient
from hops_ai.services.llm.usage import UsageRecorder
from hops_ai.services.shared import SharedServices
from hops_ai.settings import Settings

RUN_ID = "00000000-0000-4000-8000-000000000001"
INQUIRY_ID = "00000000-0000-4000-8000-000000000003"
ACTOR = Actor(
    userId="00000000-0000-4000-8000-000000000100",
    departmentId="00000000-0000-4000-8000-000000000018",
    roles=[],
)


@pytest.fixture(autouse=True)
def _isolate_hops_ai_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """개발자 PC의 HOPS_AI_* 환경변수가 테스트 결과에 영향을 주지 않게 한다."""
    for name in list(os.environ):
        if name.startswith("HOPS_AI_"):
            monkeypatch.delenv(name)


class FakeClock:
    """시간을 직접 움직일 수 있는 시계. 마감·대기 시간 테스트가 실제로 기다리지 않게 한다."""

    def __init__(self) -> None:
        self._monotonic = 1000.0
        self._start = datetime(2026, 10, 28, 0, 0, 0, tzinfo=UTC)

    def monotonic(self) -> float:
        return self._monotonic

    def now(self) -> datetime:
        return self._start + timedelta(seconds=self._monotonic - 1000.0)

    def advance(self, seconds: float) -> None:
        self._monotonic += seconds


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def make_settings() -> Callable[..., Settings]:
    """개발자 PC의 .env를 읽지 않는 설정을 만든다. 바꿀 필드만 넘긴다."""

    def factory(**overrides: Any) -> Settings:
        return Settings(_env_file=None, **overrides)

    return factory


@pytest.fixture
def make_services(clock, make_settings) -> Callable[..., SharedServices]:
    """한 번의 실행에 쓰는 서비스 묶음. 기본은 모두 가짜이고 LLM은 예산 안에서 호출한다."""

    def factory(
        *,
        llm=None,
        retriever=None,
        tools=None,
        tool_policy=None,
        deadline_at: datetime | None = None,
        **settings_overrides: Any,
    ) -> SharedServices:
        runtime = RunContext.create(
            run_id=RUN_ID,
            inquiry_id=INQUIRY_ID,
            inquiry_version=1,
            actor=ACTOR,
            access_scope_ref="mock:owner:inquiry-3",
            settings=make_settings(**settings_overrides),
            deadline_at=deadline_at,
            tool_policy=tool_policy,
            clock=clock,
        )
        usage = UsageRecorder()

        async def sleep(seconds: float) -> None:
            clock.advance(seconds)

        return SharedServices(
            llm=BudgetedLLMClient(
                llm or FakeLLMClient(), runtime.budget, usage, sleep=sleep, rng=lambda: 1.0
            ),
            retriever=retriever or FakeRetriever(),
            tools=tools or FakeToolGateway(),
            runtime=runtime,
            usage=usage,
        )

    return factory


DEPARTMENTS = [
    Department(
        id=f"00000000-0000-4000-8000-0000000000{number}",
        code=code,
        name=name,
        responsibilities=[f"가상 {name} 처리"],
    )
    for number, code, name in [
        ("10", "ACCOUNT_PERMISSION", "계정 권한"),
        ("11", "NETWORK", "네트워크"),
        ("12", "CS", "CS"),
        ("14", "BACKEND", "backend"),
        ("17", "SYSTEM", "시스템"),
    ]
]


def user_message(content: str, number: int = 601, revision: int = 1) -> Message:
    return Message(
        messageId=f"00000000-0000-4000-8000-000000000{number}",
        revision=revision,
        sender="USER",
        content=content,
    )


@pytest.fixture
def departments() -> list[Department]:
    return list(DEPARTMENTS)


@pytest.fixture
def make_message() -> Callable[..., Message]:
    return user_message


@pytest.fixture
def make_input() -> Callable[..., AnalysisInput]:
    """분석 입력. 기본은 VPN 오류를 처음 문의한 상태다."""

    def factory(
        messages: list[Message] | None = None,
        existing_problems: list[Problem] | None = None,
        evidence: list[Evidence] | None = None,
    ) -> AnalysisInput:
        return AnalysisInput(
            run_id=RUN_ID,
            inquiry_id=INQUIRY_ID,
            inquiry_version=1,
            messages=messages or [user_message("VPN 접속 오류가 나요.")],
            existing_problems=existing_problems or [],
            existing_work_items=[],
            departments=list(DEPARTMENTS),
            evidence=evidence or [],
            resolution_targets=[],
        )

    return factory


MOCK_FIXTURES = Path(__file__).resolve().parents[2] / "specs" / "mocks" / "fixtures.json"


def future_deadline(seconds: int = 60) -> str:
    return (datetime.now(UTC) + timedelta(seconds=seconds)).strftime("%Y-%m-%dT%H:%M:%SZ")


@pytest.fixture
def mock_cases() -> dict[str, Any]:
    """팀 공용 Mock 예시를 id로 찾는다."""
    return {item["id"]: item["value"] for item in json.loads(MOCK_FIXTURES.read_text())["cases"]}


@pytest.fixture
def make_request_json(mock_cases) -> Callable[..., dict[str, Any]]:
    """팀 공용 Mock의 AiRequest 예시. 마감은 미래로 바꾸고 바꿀 필드만 넘긴다."""

    def factory(case: str = "ai-request", *, deadline_seconds: int = 60, **overrides: Any):
        value = copy.deepcopy(mock_cases[case])
        value["deadlineAt"] = future_deadline(deadline_seconds)
        value.update(overrides)
        return value

    return factory


@pytest.fixture
def make_wire_request(make_request_json) -> Callable[..., AiRequest]:
    def factory(case: str = "ai-request", **overrides: Any) -> AiRequest:
        return AiRequest.model_validate(make_request_json(case, **overrides))

    return factory
