"""내부 API 의존성."""

from fastapi import Request

from hops_ai.persistence.run_ledger import RunLedger


def get_run_ledger(request: Request) -> RunLedger:
    return request.app.state.run_ledger
