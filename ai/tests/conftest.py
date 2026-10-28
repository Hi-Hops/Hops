import os
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

from hops_ai.settings import Settings


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
