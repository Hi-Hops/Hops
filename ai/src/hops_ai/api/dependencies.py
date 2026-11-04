"""내부 API 의존성."""

from fastapi import Request

from hops_ai.persistence.run_ledger import RunLedger
from hops_ai.services.runs.events import RunEventHub
from hops_ai.services.runs.executor import RunExecutor


def get_run_events(request: Request) -> RunEventHub:
    return request.app.state.run_events


def get_run_ledger(request: Request) -> RunLedger:
    return request.app.state.run_ledger


def get_run_executor(request: Request) -> RunExecutor | None:
    """실행 파이프라인이 연결되지 않았으면 None이다. 라우트는 이때 접수하지 않는다."""
    return getattr(request.app.state, "run_executor", None)
