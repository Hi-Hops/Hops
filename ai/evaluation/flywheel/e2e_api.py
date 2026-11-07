"""선순환 E2E가 Spring 공개 API를 부르는 얇은 클라이언트.

실제 사용자처럼 로그인한 계정의 토큰으로 `/api/v2` 경로를 부른다. 변경 명령은 Idempotency-Key와 본문
requestId를 같은 값으로 보내고, 접수만 된 명령(PENDING)은 작업이 끝날 때까지 기다린다.
비밀값(비밀번호, 토큰)은 출력이나 기록에 남기지 않는다.
"""

import time
import uuid
from collections.abc import Callable
from typing import Any

import httpx

API = "/api/v2"
DONE_RUN_STATUSES = {"SUCCEEDED", "FAILED", "TIMED_OUT"}


class ApiError(RuntimeError):
    def __init__(self, status: int, path: str, body: str) -> None:
        super().__init__(f"{status} {path}: {body[:300]}")
        self.status = status
        self.path = path


def wait_until[T](
    probe: Callable[[], T | None],
    *,
    timeout: float,
    interval: float = 1.0,
    sleep: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
) -> T | None:
    """probe가 None이 아닌 값을 낼 때까지 기다린다. 시간이 지나면 None."""
    deadline = clock() + timeout
    while True:
        value = probe()
        if value is not None:
            return value
        if clock() >= deadline:
            return None
        sleep(interval)


class Api:
    def __init__(
        self,
        base_url: str,
        *,
        client: httpx.Client | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._http = client or httpx.Client(base_url=base_url, timeout=30.0)
        self._tokens: dict[str, str] = {}
        self._sleep = sleep

    def login(self, login_id: str, password: str) -> dict[str, Any]:
        response = self._http.post(
            f"{API}/auth/login", json={"loginId": login_id, "password": password}
        )
        if response.status_code != 200:
            raise ApiError(response.status_code, "/auth/login", "로그인하지 못했다")
        body = response.json()
        self._tokens[login_id] = body["accessToken"]
        return body["user"]

    def _headers(self, login_id: str, extra: dict[str, str] | None = None) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._tokens[login_id]}", **(extra or {})}

    def get(self, login_id: str, path: str, **params: Any) -> Any:
        response = self._http.get(f"{API}{path}", headers=self._headers(login_id), params=params)
        if response.status_code != 200:
            raise ApiError(response.status_code, path, response.text)
        return response.json()

    def status_of(self, login_id: str, path: str) -> int:
        """본문을 보지 않고 상태 코드만 본다(접근 거부 확인용)."""
        return self._http.get(f"{API}{path}", headers=self._headers(login_id)).status_code

    def command(
        self, login_id: str, path: str, body: dict[str, Any], *, timeout: float = 60.0
    ) -> dict:
        """변경 명령. 접수(202)면 끝날 때까지 기다리고, 성공 영수증(resource 포함)을 돌려준다."""
        request_id = str(uuid.uuid4())
        payload = {"requestId": request_id, **body}
        headers = self._headers(login_id, {"Idempotency-Key": request_id})
        response = self._http.post(f"{API}{path}", headers=headers, json=payload)
        if response.status_code not in (200, 201, 202):
            raise ApiError(response.status_code, path, response.text)
        receipt = response.json()

        def settled() -> dict | None:
            if receipt["state"] == "SUCCEEDED":
                return receipt
            if receipt["state"] != "PENDING":
                # REJECTED·UNKNOWN은 성공으로 보지 않는다. UNKNOWN은 저장 결과를 따로 확인해야 한다.
                raise ApiError(
                    response.status_code, path, f"{receipt['state']}: {receipt.get('error')}"
                )
            receipt.update(self.get(login_id, f"/operations/{receipt['operationId']}"))
            if receipt["state"] not in ("PENDING", "SUCCEEDED"):
                raise ApiError(
                    response.status_code, path, f"{receipt['state']}: {receipt.get('error')}"
                )
            return receipt if receipt["state"] == "SUCCEEDED" else None

        done = wait_until(settled, timeout=timeout, interval=1.0, sleep=self._sleep)
        if done is None:
            raise ApiError(response.status_code, path, "명령이 제한 시간 안에 끝나지 않았다")
        return done

    def wait_run_settled(self, login_id: str, inquiry_id: str, *, timeout: float) -> dict | None:
        """문의의 현재 실행이 끝나거나 없을 때까지 기다리고 문의 결과를 돌려준다."""

        def probe() -> dict | None:
            result = self.get(login_id, f"/inquiries/{inquiry_id}")
            run = result.get("currentRun")
            return result if run is None or run["status"] in DONE_RUN_STATUSES else None

        return wait_until(probe, timeout=timeout, interval=2.0, sleep=self._sleep)
