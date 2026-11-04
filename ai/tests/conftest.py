"""업무 데이터·공용 Mock과 독립적인 하네스 테스트 환경."""

import os
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

from hops_ai.settings import Settings


@pytest.fixture(autouse=True)
def _isolate_hops_ai_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in list(os.environ):
        if name.startswith("HOPS_AI_") or name in {"AI_SERVICE_TOKEN", "SPRING_SERVICE_TOKEN"}:
            monkeypatch.delenv(name)


class FakeClock:
    def __init__(self) -> None:
        self._monotonic = 1000.0
        self._start = datetime(2026, 10, 28, tzinfo=UTC)

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
    def factory(**overrides: Any) -> Settings:
        return Settings(_env_file=None, **overrides)

    return factory
