"""인증·오류·미준비 경계 검사. 외부 모델과 DB는 호출하지 않는다."""

from uuid import UUID, uuid4

import psycopg
import pytest
from fastapi.testclient import TestClient

from hops_ai.main import create_app
from hops_ai.persistence.run_ledger import RunSnapshot
from hops_ai.settings import Settings
from tests.doubles.run_requests import inquiry_request

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


def test_post_validates_input_but_never_accepts_without_executor(client, ledger) -> None:
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
