"""실제 loopback HTTP에서 실행 종료 전 progress가 도착함을 검증한다. 모델·DB는 대역이다."""

import asyncio
import json
import socket
import threading
import time

import httpx
import uvicorn

from hops_ai.main import create_app
from hops_ai.settings import Settings
from tests.doubles.ledger import MemoryLedger
from tests.doubles.run_requests import expires_in, inquiry_request, succeeded


def test_progress_arrives_before_final_and_final_survives_reconnection():
    token = "A" * 43
    begin, finish = threading.Event(), threading.Event()

    class Pipeline:
        async def run(self, request, context):
            while not begin.is_set():
                await asyncio.sleep(0.01)
            context.report_progress("INQUIRY")
            while not finish.is_set():
                await asyncio.sleep(0.01)
            return succeeded(request)

    app = create_app(
        Settings(
            _env_file=None,
            internal_api_enabled=True,
            runtime_dsn="dbname=hops_ai_runtime user=test_user",
            ai_service_token=token,
        ),
        ledger=MemoryLedger(),
        pipeline=Pipeline(),
    )
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        server = uvicorn.Server(uvicorn.Config(app, log_level="error"))
        thread = threading.Thread(target=lambda: server.run(sockets=[sock]), daemon=True)
        thread.start()
        try:
            for _ in range(500):
                if server.started:
                    break
                time.sleep(0.01)
            assert server.started
            url = f"http://127.0.0.1:{sock.getsockname()[1]}"
            req = inquiry_request(expires_at=expires_in(60))
            headers = {"Authorization": f"Bearer {token}"}
            with httpx.Client(base_url=url, headers=headers, timeout=5) as client:
                assert (
                    client.post("/internal/v2/runs", json=req.model_dump(mode="json")).status_code
                    == 202
                )
                path = f"/internal/v2/runs/{req.runId}"
                assert (
                    client.get(
                        path + "/events", headers={"Authorization": "Bearer wrong"}
                    ).status_code
                    == 401
                )
                with client.stream("GET", path + "/events") as response:
                    assert response.status_code == 200
                    begin.set()
                    events = (
                        json.loads(line[6:])
                        for line in response.iter_lines()
                        if line.startswith("data: ")
                    )
                    progress = next(events)
                    assert progress["eventType"] == "progress"
                    assert client.get(path).json()["status"] == "RUNNING"
                    finish.set()
                    final = next(events)
                    assert final["eventType"] == "result"
                    assert final["seq"] > progress["seq"]
                    assert client.get(path).json()["result"] == final["payload"]
                with client.stream("GET", path + "/events") as response:
                    restored = [
                        json.loads(line[6:])
                        for line in response.iter_lines()
                        if line.startswith("data: ")
                    ]
                    assert restored == [final]
        finally:
            begin.set()
            finish.set()
            server.should_exit = True
            thread.join(timeout=5)
            assert not thread.is_alive()
