import os

import pytest


@pytest.fixture(autouse=True)
def _isolate_hops_ai_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """개발자 PC의 HOPS_AI_* 환경변수가 테스트 결과에 영향을 주지 않게 한다."""
    for name in list(os.environ):
        if name.startswith("HOPS_AI_"):
            monkeypatch.delenv(name)
