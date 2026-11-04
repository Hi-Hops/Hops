"""인증·오류·미준비 경계 검사. 외부 모델과 DB는 호출하지 않는다."""

import asyncio
import time
from contextlib import contextmanager
from uuid import UUID, uuid4

import httpx
import psycopg
import pytest
from fastapi.testclient import TestClient

from hops_ai.api.routes import health
from hops_ai.main import create_app
from hops_ai.persistence.run_ledger import RunSnapshot
from hops_ai.settings import Settings
from tests.doubles.ledger import MemoryLedger
from tests.doubles.run_requests import expires_in, inquiry_request, succeeded

# 인증 검사용 고정 대역이며 실제 토큰이 아니다.
TEST_TOKEN = "A" * 43
TEST_DSN = "dbname=hops_ai_runtime user=test_user"


class LedgerDouble:
    def __init__(self) -> None:
        self.snapshot: RunSnapshot | None = None
        self.failure: Exception | None = None
        self.get_calls = 0
        self.opened = False

    async def __aenter__(self):
        self.opened = True
        return self

    async def __aexit__(self, *args):
        self.opened = False

    async def get(self, run_id: UUID) -> RunSnapshot | None:
        self.get_calls += 1
        if self.failure:
            raise self.failure
        if self.snapshot and self.snapshot.run_id == run_id:
            return self.snapshot
        return None


@pytest.fixture
def ledger() -> LedgerDouble:
    return LedgerDouble()


@pytest.fixture
def client(ledger: LedgerDouble):
    settings = Settings(
        _env_file=None,
        internal_api_enabled=True,
        runtime_dsn=TEST_DSN,
        ai_service_token=TEST_TOKEN,
    )
    with TestClient(create_app(settings, ledger=ledger)) as test_client:
        yield test_client
    assert not ledger.opened


def auth(token: str = TEST_TOKEN) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def test_internal_routes_are_disabled_by_default() -> None:
    with TestClient(create_app(Settings(_env_file=None))) as client:
        assert client.get(f"/internal/v2/runs/{uuid4()}", headers=auth()).status_code == 404


@pytest.mark.parametrize("header", [None, "", "Basic token", "Bearer", "Bearer wrong"])
def test_authentication_rejects_before_reading_ledger(client, ledger, header) -> None:
    headers = {} if header is None else {"Authorization": header}
    response = client.get(f"/internal/v2/runs/{uuid4()}", headers=headers)
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json() == {
        "code": "AUTH_CONTEXT_INVALID",
        "message": "서비스 인증에 실패했습니다",
        "retryable": False,
    }
    assert ledger.get_calls == 0


def test_non_ascii_header_is_rejected_safely(client, ledger) -> None:
    response = client.get(
        f"/internal/v2/runs/{uuid4()}", headers=[(b"authorization", b"Bearer \xe9")]
    )
    assert response.status_code == 401
    assert ledger.get_calls == 0


def test_valid_token_can_read_snapshot(client, ledger) -> None:
    run_id = uuid4()
    ledger.snapshot = RunSnapshot(run_id, "a" * 64, "QUEUED", 0, None)
    response = client.get(f"/internal/v2/runs/{run_id}", headers=auth())
    assert response.status_code == 200
    assert response.json() == {
        "contractVersion": "hops-system-1",
        "runId": str(run_id),
        "status": "QUEUED",
        "lastSeq": 0,
        "result": None,
        "error": None,
    }
    assert ledger.get_calls == 1


def test_valid_final_result_is_preserved_in_snapshot(client, ledger) -> None:
    run_id = uuid4()
    result = {
        "contractVersion": "hops-system-1",
        "runId": str(run_id),
        "kind": "INQUIRY_ANALYSIS",
        "inquiryId": str(uuid4()),
        "inquiryVersion": 1,
        "status": "SUCCEEDED",
        "answer": "안내",
        "problemProposals": [],
        "questions": [],
        "guide": None,
        "ticketProposals": [],
        "caseProposal": None,
        "evidence": [],
        "error": None,
    }
    ledger.snapshot = RunSnapshot(run_id, "a" * 64, "SUCCEEDED", 1, result)
    response = client.get(f"/internal/v2/runs/{run_id}", headers=auth())
    assert response.status_code == 200
    assert response.json()["result"] == result
    assert response.json()["error"] is None


def test_unknown_run_and_invalid_id_are_distinct(client, ledger) -> None:
    response = client.get(f"/internal/v2/runs/{uuid4()}", headers=auth())
    assert response.status_code == 404
    assert response.json()["code"] == "NOT_FOUND"
    response = client.get("/internal/v2/runs/NOT-A-UUID", headers=auth())
    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_REQUEST"
    assert "NOT-A-UUID" not in response.text
    assert ledger.get_calls == 1


def test_storage_error_and_invalid_stored_result_are_safe(client, ledger, caplog) -> None:
    run_id = uuid4()
    ledger.failure = psycopg.OperationalError("PRIVATE-DATABASE-DETAIL")
    response = client.get(f"/internal/v2/runs/{run_id}", headers=auth())
    assert response.status_code == 503
    assert response.json()["code"] == "SERVICE_UNAVAILABLE"
    assert "PRIVATE-DATABASE-DETAIL" not in response.text
    ledger.failure = None
    ledger.snapshot = RunSnapshot(run_id, "a" * 64, "SUCCEEDED", 1, {"untrusted": "PRIVATE"})
    response = client.get(f"/internal/v2/runs/{run_id}", headers=auth())
    assert response.status_code == 503
    assert "PRIVATE" not in response.text
    assert "PRIVATE-DATABASE-DETAIL" not in caplog.text
    assert "PRIVATE" not in caplog.text


def test_enabled_profile_constructs_configured_ledger(monkeypatch, ledger) -> None:
    calls = []

    def create_ledger(dsn):
        calls.append(dsn)
        return ledger

    monkeypatch.setattr("hops_ai.main.RunLedger", create_ledger)
    settings = Settings(
        _env_file=None,
        internal_api_enabled=True,
        runtime_dsn=TEST_DSN,
        ai_service_token=TEST_TOKEN,
    )
    with TestClient(create_app(settings)) as client:
        assert calls == [TEST_DSN]
        assert ledger.opened
        assert client.get(f"/internal/v2/runs/{uuid4()}", headers=auth()).status_code == 404
    assert not ledger.opened


def test_post_validates_input_but_never_accepts_without_pipeline(client, ledger) -> None:
    valid_request = inquiry_request().model_dump(mode="json")
    response = client.post("/internal/v2/runs", json=valid_request, headers=auth())
    assert response.status_code == 503
    assert response.json()["code"] == "SERVICE_UNAVAILABLE"
    assert ledger.get_calls == 0
    valid_request["contractVersion"] = "PRIVATE-INVALID"
    response = client.post("/internal/v2/runs", json=valid_request, headers=auth())
    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_REQUEST"
    assert "PRIVATE-INVALID" not in response.text


def test_unimplemented_event_and_cancel_do_not_claim_success(client, ledger) -> None:
    run_id = uuid4()
    assert client.get(f"/internal/v2/runs/{run_id}/events", headers=auth()).status_code == 503
    assert client.post(f"/internal/v2/runs/{run_id}/cancel", headers=auth()).status_code == 503
    assert ledger.get_calls == 0


@pytest.mark.parametrize(
    "method,path",
    [
        ("POST", "/internal/v2/runs"),
        ("GET", "/internal/v2/runs/invalid/events"),
        ("POST", "/internal/v2/runs/invalid/cancel"),
    ],
)
def test_unauthorized_calls_do_not_reveal_validation_or_runtime_state(client, ledger, method, path):
    response = client.request(method, path, json={})
    assert response.status_code == 401
    assert response.json()["code"] == "AUTH_CONTEXT_INVALID"
    assert ledger.get_calls == 0


@pytest.mark.parametrize("body", [b"{", b"\xff"])
def test_unauthenticated_malformed_body_is_rejected_first(client, ledger, body) -> None:
    response = client.post(
        "/internal/v2/runs", content=body, headers={"Content-Type": "application/json"}
    )
    assert response.status_code == 401
    assert response.json()["code"] == "AUTH_CONTEXT_INVALID"
    assert ledger.get_calls == 0


def test_authenticated_malformed_body_still_receives_validation_error(client) -> None:
    response = client.post(
        "/internal/v2/runs", content=b"{", headers=auth() | {"Content-Type": "application/json"}
    )
    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_REQUEST"


async def test_unauthenticated_request_does_not_read_asgi_body(ledger: LedgerDouble) -> None:
    settings = Settings(
        _env_file=None, internal_api_enabled=True, runtime_dsn=TEST_DSN, ai_service_token=TEST_TOKEN
    )
    app = create_app(settings, ledger=ledger)
    received = 0
    messages = []

    async def receive():
        nonlocal received
        received += 1
        raise AssertionError("인증 실패 요청의 본문을 읽으면 안 된다")

    async def send(message):
        messages.append(message)

    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": "/internal/v2/runs",
        "raw_path": b"/internal/v2/runs",
        "query_string": b"",
        "root_path": "",
        "headers": [(b"content-type", b"application/json"), (b"content-length", b"100000000")],
        "client": ("127.0.0.1", 1234),
        "server": ("test", 80),
    }
    await app(scope, receive, send)
    assert received == 0
    assert messages[0]["type"] == "http.response.start"
    assert messages[0]["status"] == 401


def test_duplicate_authorization_headers_are_rejected(client, ledger) -> None:
    headers = [
        (b"authorization", f"Bearer {TEST_TOKEN}".encode()),
        (b"authorization", b"Bearer bad"),
    ]
    response = client.get(f"/internal/v2/runs/{uuid4()}", headers=headers)
    assert response.status_code == 401
    assert ledger.get_calls == 0


@pytest.mark.parametrize(
    "path", ["/internal", "/internal/v2/index-jobs", "/internal/v3/anything", "/internal/mock/v1"]
)
def test_every_internal_path_requires_service_auth(client, path) -> None:
    # 아직 없는 내부 경로도 인증 전에는 존재 여부를 드러내지 않는다.
    response = client.post(path, json={})
    assert response.status_code == 401
    assert response.json()["code"] == "AUTH_CONTEXT_INVALID"
    assert client.post(path, json={}, headers=auth()).status_code == 404


def test_internal_prefix_match_is_segment_based(client) -> None:
    assert client.get("/internalx").status_code == 404


def test_health_is_public_with_internal_auth_enabled(client) -> None:
    assert client.get("/healthz").status_code == 200
    # 조회만 열리고 실행 파이프라인이 없으면 업무 요청을 받을 준비가 아니다.
    response = client.get("/readyz")
    assert response.status_code == 503
    assert response.json() == {"status": "not_ready", "reason": "RUN_PIPELINE_DISABLED"}


# --- 실행 파이프라인이 연결된 접수 경로 ---


class SucceedingPipeline:
    async def run(self, request, context):
        return succeeded(request)


class BlockingPipeline:
    async def run(self, request, context):
        # 앱 종료 시 실행기가 취소한다.
        await asyncio.sleep(30)


@contextmanager
def run_client(pipeline, ledger: MemoryLedger | None = None, **settings):
    ledger = ledger or MemoryLedger()
    resolved = Settings(
        _env_file=None,
        internal_api_enabled=True,
        runtime_dsn=TEST_DSN,
        ai_service_token=TEST_TOKEN,
        **settings,
    )
    with TestClient(create_app(resolved, ledger=ledger, pipeline=pipeline)) as test_client:
        yield test_client, ledger
    assert not ledger.opened


def post_run(client: TestClient, request) -> httpx.Response:
    return client.post("/internal/v2/runs", json=request.model_dump(mode="json"), headers=auth())


def wait_for_terminal(client: TestClient, run_id: str) -> dict:
    for _ in range(200):
        body = client.get(f"/internal/v2/runs/{run_id}", headers=auth()).json()
        if body["status"] not in ("QUEUED", "RUNNING"):
            return body
        time.sleep(0.01)
    raise AssertionError("실행이 종료 상태가 되지 않았다")


def test_accepted_run_returns_202_and_finishes() -> None:
    with run_client(SucceedingPipeline()) as (client, _):
        request = inquiry_request(expires_at=expires_in(60))
        response = post_run(client, request)
        assert response.status_code == 202
        assert response.json() == {
            "contractVersion": "hops-system-1",
            "runId": request.runId,
            "status": "QUEUED",
            "eventsPath": f"/internal/v2/runs/{request.runId}/events",
        }
        snapshot = wait_for_terminal(client, request.runId)
        assert snapshot["status"] == "SUCCEEDED"
        assert snapshot["result"]["answer"] == "가상 안내 문구"
        assert snapshot["error"] is None


def test_replay_is_accepted_again_and_conflict_is_409() -> None:
    with run_client(SucceedingPipeline()) as (client, _):
        request = inquiry_request(expires_at=expires_in(60))
        assert post_run(client, request).status_code == 202
        assert post_run(client, request).status_code == 202
        changed = request.model_dump(mode="json") | {"inquiryVersion": 2}
        response = client.post("/internal/v2/runs", json=changed, headers=auth())
        assert response.status_code == 409
        assert response.json()["code"] == "CONFLICT"


def test_expired_request_is_422_and_not_stored() -> None:
    with run_client(SucceedingPipeline()) as (client, ledger):
        response = post_run(client, inquiry_request(expires_at="2026-01-01T00:00:00Z"))
        assert response.status_code == 422
        assert response.json()["code"] == "INVALID_REQUEST"
        assert ledger.rows == {}


def test_limits_above_server_ceiling_are_429_without_retry_after() -> None:
    with run_client(SucceedingPipeline()) as (client, ledger):
        response = post_run(client, inquiry_request(expires_at=expires_in(60), max_calls=50))
        assert response.status_code == 429
        assert response.json() == {
            "code": "BUDGET_EXCEEDED",
            "message": "요청한 실행 한도가 서버 허용 범위를 넘습니다",
            "retryable": False,
        }
        assert "retry-after" not in response.headers
        assert ledger.rows == {}


def test_busy_server_is_429_with_retry_after() -> None:
    with run_client(BlockingPipeline(), max_concurrent_runs=1) as (client, ledger):
        assert post_run(client, inquiry_request(expires_at=expires_in(60))).status_code == 202
        response = post_run(client, inquiry_request(expires_at=expires_in(60)))
        assert response.status_code == 429
        assert response.json()["code"] == "RATE_LIMITED"
        assert response.json()["retryable"] is True
        assert response.headers["retry-after"] == "5"
        assert len(ledger.rows) == 1
    # 종료 시 실행 중인 run은 결과 없이 남고 다음 기동 복구가 UNKNOWN으로 바꾼다.
    assert [row.status for row in ledger.rows.values()] == ["RUNNING"]


def test_storage_failure_on_accept_is_safe_503() -> None:
    ledger = MemoryLedger(accept_failure=psycopg.OperationalError("PRIVATE-DATABASE-DETAIL"))
    with run_client(SucceedingPipeline(), ledger=ledger) as (client, _):
        response = post_run(client, inquiry_request(expires_at=expires_in(60)))
        assert response.status_code == 503
        assert response.json()["code"] == "SERVICE_UNAVAILABLE"
        assert "PRIVATE" not in response.text


# --- 준비 상태 ---


def test_ready_when_pipeline_is_connected_and_ledger_session_answers() -> None:
    with run_client(SucceedingPipeline()) as (client, _):
        response = client.get("/readyz")

    assert response.status_code == 200
    assert response.json() == {"status": "ready"}


def test_not_ready_when_ledger_session_is_lost(caplog) -> None:
    ledger = MemoryLedger(ping_failure=psycopg.OperationalError("server closed the connection"))

    with run_client(SucceedingPipeline(), ledger) as (client, _):
        response = client.get("/readyz")

    assert response.status_code == 503
    assert response.json() == {"status": "not_ready", "reason": "RUN_LEDGER_UNAVAILABLE"}
    assert "server closed" not in caplog.text


def test_not_ready_when_ledger_session_does_not_answer_in_time(monkeypatch) -> None:
    monkeypatch.setattr(health, "READY_CHECK_TIMEOUT_SECONDS", 0.01)
    ledger = MemoryLedger(ping_delay=1)

    with run_client(SucceedingPipeline(), ledger) as (client, _):
        response = client.get("/readyz")

    assert response.status_code == 503
    assert response.json()["reason"] == "RUN_LEDGER_UNAVAILABLE"


async def test_shutdown_closes_execution_and_ledger_and_is_no_longer_ready() -> None:
    ledger = MemoryLedger()
    resolved = Settings(
        _env_file=None,
        internal_api_enabled=True,
        runtime_dsn=TEST_DSN,
        ai_service_token=TEST_TOKEN,
    )
    app = create_app(resolved, ledger=ledger, pipeline=SucceedingPipeline())

    async with app.router.lifespan_context(app):
        assert app.state.run_executor is not None

    assert app.state.run_executor is None
    assert not ledger.opened
