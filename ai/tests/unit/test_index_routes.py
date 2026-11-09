"""색인 작업 HTTP 경로의 상태 코드와 오류 본문(contracts/agents 반영 API 표)."""

import httpx
from fastapi import FastAPI

from hops_ai.api.errors import install_error_handlers
from hops_ai.api.routes import index_jobs as routes
from hops_ai.services.indexing.jobs import IndexJobService
from tests.unit.test_index_jobs import JOB_ID, MemoryJobs, Source, Store, request


def client(service: IndexJobService | None) -> httpx.AsyncClient:
    app = FastAPI()
    install_error_handlers(app)
    app.include_router(routes.router)
    app.state.index_jobs = service
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://ai")


def service() -> IndexJobService:
    store = Store()
    return IndexJobService(MemoryJobs(), Source(), {"VECTOR": store, "NEO4J": store})


async def test_acceptance_is_202_with_the_snapshot_and_reading_is_200() -> None:
    async with client(service()) as http:
        accepted = await http.post("/internal/v2/index-jobs", json=request())
        read = await http.get(f"/internal/v2/index-jobs/{JOB_ID}", params={"attempt": 1})

    assert accepted.status_code == 202
    assert accepted.json()["state"] == "SUCCEEDED" and accepted.json()["error"] is None
    assert read.status_code == 200 and read.json() == accepted.json()


async def test_errors_use_the_contract_codes() -> None:
    bad_digest = request() | {"contentDigest": "0" * 64}
    async with client(service()) as http:
        conflict = await http.post("/internal/v2/index-jobs", json=bad_digest)
        invalid = await http.post("/internal/v2/index-jobs", json={"jobId": JOB_ID})
        not_json = await http.post(
            "/internal/v2/index-jobs", content=b"{", headers={"Content-Type": "application/json"}
        )
        missing = await http.get(f"/internal/v2/index-jobs/{JOB_ID}", params={"attempt": 9})

    assert (conflict.status_code, conflict.json()["code"]) == (409, "CONFLICT")
    assert (invalid.status_code, invalid.json()["code"]) == (422, "INVALID_REQUEST")
    assert (not_json.status_code, not_json.json()["code"]) == (422, "INVALID_REQUEST")
    assert (missing.status_code, missing.json()["code"]) == (404, "NOT_FOUND")


async def test_without_index_settings_the_service_is_unavailable() -> None:
    async with client(None) as http:
        response = await http.post("/internal/v2/index-jobs", json=request())

    assert (response.status_code, response.json()["code"]) == (503, "INDEX_UNAVAILABLE")
    assert response.json()["retryable"] is True
