import pytest
from fastapi.testclient import TestClient

from hops_ai.harness.errors import ComponentNotReady
from hops_ai.main import create_app
from hops_ai.settings import Settings


def test_scaffold_is_alive_but_not_ready_for_business_requests() -> None:
    settings = Settings(_env_file=None)
    with TestClient(create_app(settings)) as client:
        assert client.get("/healthz").json() == {"status": "ok"}
        response = client.get("/readyz")
        assert response.status_code == 503
        assert response.json() == {
            "status": "not_ready",
            "reason": "AI_PIPELINE_NOT_IMPLEMENTED",
        }
        assert client.app.state.settings is settings


def test_retired_runs_and_events_cannot_return_fake_business_success() -> None:
    with TestClient(create_app(Settings(_env_file=None))) as client:
        assert client.post("/internal/mock/v1/runs", json={}).status_code == 404
        assert client.get("/internal/mock/v1/runs/example/events").status_code == 404
        assert set(client.get("/openapi.json").json()["paths"]) == {"/healthz", "/readyz"}


def test_real_mode_has_no_automatic_fake_fallback() -> None:
    with pytest.raises(ComponentNotReady):
        create_app(Settings(_env_file=None, mode="real"))
