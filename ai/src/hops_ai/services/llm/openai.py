"""OpenAI Responses의 구조화 출력을 실행 예산 안에서 받는다.

- 이 어댑터는 스스로 재시도하지 않는다. 연결 장애·429·5xx는 TransientError로 알리고
  재시도·호출 상한·시간 제한은 BudgetedLLMClient가 맡는다(client.py 규약).
  요청 거절·형식 오류는 OutputRejected다.
- 응답 저장을 요청하고(store=True) JSON Schema strict 구조화 출력을 사용한다.
- 호출별 시간 제한은 BudgetedLLMClient가 실행 기한 안에서 적용한다.
  여기 둔 HTTP 제한은 마지막 안전망이다.
"""

from dataclasses import asdict

import httpx
from pydantic import BaseModel, ValidationError

from hops_ai.harness.errors import ComponentNotReady, OutputRejected, TransientError
from hops_ai.services.llm.client import LLMRequest, LLMResponse, LLMUsage

_BASE_URL = "https://api.openai.com/v1/"
_HTTP_TIMEOUT = httpx.Timeout(120.0, connect=10.0)


class OpenAIClient:
    def __init__(
        self,
        api_key: str | None = None,
        model: str | None = None,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        """키와 모델이 없으면 호출 때 ComponentNotReady를 낸다.

        transport는 테스트에서만 주입한다.
        """
        self._api_key = api_key
        self._model = model
        self._transport = transport

    async def generate[T: BaseModel](self, request: LLMRequest[T]) -> LLMResponse[T]:
        if not self._api_key or not self._model:
            raise ComponentNotReady("OpenAI client")
        payload = {
            "model": self._model,
            "input": [asdict(message) for message in request.messages],
            "reasoning": {"effort": "none"},
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "hops_result",
                    "strict": True,
                    "schema": request.response_schema or request.response_model.model_json_schema(),
                }
            },
        }
        async with httpx.AsyncClient(
            base_url=_BASE_URL,
            headers={"Authorization": f"Bearer {self._api_key}"},
            timeout=_HTTP_TIMEOUT,
            transport=self._transport,
        ) as client:
            if request.max_input_tokens is not None:
                count = await self._post(client, "responses/input_tokens", payload)
                tokens = count.get("input_tokens")
                if type(tokens) is not int or not 0 < tokens <= request.max_input_tokens:
                    raise OutputRejected("입력 토큰 한도 또는 토큰 계산 결과가 유효하지 않습니다")
            data = await self._post(
                client,
                "responses",
                {
                    **payload,
                    "store": True,
                    "max_output_tokens": request.max_output_tokens,
                },
            )
        try:
            if data["status"] != "completed":
                raise ValueError("incomplete")
            texts = [
                content["text"]
                for item in data["output"]
                if item["type"] == "message"
                for content in item["content"]
                if content["type"] == "output_text"
            ]
            parsed = request.response_model.model_validate_json("".join(texts))
            usage = LLMUsage(data["usage"]["input_tokens"], data["usage"]["output_tokens"])
            return LLMResponse(parsed=parsed, usage=usage, model_id=data["model"])
        except (KeyError, TypeError, ValueError, ValidationError) as exc:
            raise OutputRejected("OpenAI 응답을 검증할 수 없습니다") from exc

    @staticmethod
    async def _post(client: httpx.AsyncClient, path: str, payload: dict) -> dict:
        try:
            response = await client.post(path, json=payload)
        except httpx.TransportError as exc:
            raise TransientError("OpenAI 연결 실패") from exc
        if response.status_code == 429 or response.status_code >= 500:
            retry_after = response.headers.get("Retry-After", "")
            raise TransientError(
                "OpenAI 일시 오류",
                retry_after=float(retry_after) if retry_after.isdecimal() else None,
            )
        if not response.is_success:
            raise OutputRejected("OpenAI 요청이 거절됐습니다")
        try:
            body = response.json()
            if not isinstance(body, dict):
                raise ValueError("non-object")
            return body
        except ValueError as exc:
            raise OutputRejected("OpenAI 응답 형식이 올바르지 않습니다") from exc
