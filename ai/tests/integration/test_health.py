from fastapi.testclient import TestClient

from hops_ai.main import create_app
from hops_ai.settings import Settings


def test_healthz_returns_ok() -> None:
    client = TestClient(create_app(Settings(_env_file=None)))

    response = client.get("/healthz")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_app_keeps_the_settings_it_was_created_with() -> None:
    settings = Settings(_env_file=None, agent_mode="multi")

    app = create_app(settings)

    assert app.state.settings is settings
