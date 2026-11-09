"""OpenAI Responses의 구조화 출력을 실행 예산 안에서 받는다.

- 이 어댑터는 스스로 재시도하지 않는다. 연결 장애·429·5xx는 TransientError로 알리고
  재시도·호출 상한·시간 제한은 BudgetedLLMClient가 맡는다(client.py 규약).
  요청 거절·형식 오류는 OutputRejected다.
- 응답 저장을 요청하고(store=True) JSON Schema strict 구조화 출력을 사용한다.
- 호출별 시간 제한은 BudgetedLLMClient가 실행 기한 안에서 적용한다.
  여기 둔 HTTP 제한은 마지막 안전망이다.
"""

from collections.abc import Mapping
from dataclasses import asdict

import httpx
from pydantic import BaseModel, ValidationError

from hops_ai.harness.errors import (
    ComponentNotReady,
    MalformedOutput,
    OutputRejected,
    TransientError,
)
from hops_ai.services.llm.client import LLMRequest, LLMResponse, LLMUsage
from hops_ai.services.llm.routing import model_for, validate_role_models

_BASE_URL = "https://api.openai.com/v1/"
_HTTP_TIMEOUT = httpx.Timeout(120.0, connect=10.0)


def _short(value: object) -> str:
    """API가 준 상태 이름을 로그에 안전하게 줄여 쓴다(이름이 아니면 유형만)."""
    return value[:40] if isinstance(value, str) else type(value).__name__


def _violations(error: ValidationError, limit: int = 5) -> str:
    """검증 오류의 필드 위치와 오류 유형만. 모델이 낸 값(input)은 쓰지 않는다."""
    items = [
        f"{'.'.join(str(part) for part in item['loc']) or '(전체)'}:{item['type']}"
        for item in error.errors(include_input=False, include_url=False)[:limit]
    ]
    more = error.error_count() - len(items)
    return ", ".join(items) + (f" 외 {more}건" if more > 0 else "")


class OpenAIClient:
    def __init__(
        self,
        api_key: str | None = None,
        model: str | None = None,
        *,
        role_models: Mapping[str, str] | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        """키와 모델이 없으면 호출 때 ComponentNotReady를 낸다.

        Args:
            model: 기본 모델. 배분이 없는 프롬프트 계열은 이 모델을 쓴다.
            role_models: 프롬프트 계열 → 모델(routing.PROMPT_FAMILIES). 모르는 계열은 거부한다.
            transport: 테스트에서만 주입한다.
        """
        self._api_key = api_key
        self._model = model
        self._role_models = validate_role_models(role_models or {})
        self._transport = transport

    async def generate[T: BaseModel](self, request: LLMRequest[T]) -> LLMResponse[T]:
        if not self._api_key or not self._model:
            raise ComponentNotReady("OpenAI client")
        model, effort = model_for(request.prompt_version, self._model, self._role_models)
        payload = {
            "model": model,
            "input": [asdict(message) for message in request.messages],
            "reasoning": {"effort": effort},
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
        return self._parse(request, data)

    @staticmethod
    def _parse[T: BaseModel](request: LLMRequest[T], data: dict) -> LLMResponse[T]:
        """응답을 읽는다. 실패하면 어느 단계에서 왜 실패했는지를 detail에 남긴다.

        detail은 로그용이며 사용자에게 가는 고정 문구(safe_message)는 그대로다. 모델이 낸 값이나
        응답 본문은 detail에 넣지 않는다. 필드 위치와 오류 유형, API가 알려 준 상태 이름만 쓴다.
        """
        try:
            status = data["status"]
            if status != "completed":
                reason = (data.get("incomplete_details") or {}).get("reason")
                raise OutputRejected(
                    f"OpenAI 응답이 완료되지 않았습니다(status={_short(status)}, "
                    f"reason={_short(reason)})"
                )
            contents = [
                content
                for item in data["output"]
                if item["type"] == "message"
                for content in item["content"]
            ]
            if any(content["type"] == "refusal" for content in contents):
                raise OutputRejected("모델이 응답을 거부했습니다")
            texts = [content["text"] for content in contents if content["type"] == "output_text"]
            usage = LLMUsage(data["usage"]["input_tokens"], data["usage"]["output_tokens"])
            model_id = data["model"]
        except (KeyError, TypeError, AttributeError) as exc:
            raise OutputRejected("OpenAI 응답 구조를 읽을 수 없습니다") from exc
        try:
            parsed = request.response_model.model_validate_json("".join(texts))
        except ValidationError as exc:
            raise MalformedOutput(
                f"모델 출력이 형식을 어겼습니다({_violations(exc)}, 출력 텍스트 {len(texts)}개)"
            ) from exc
        return LLMResponse(parsed=parsed, usage=usage, model_id=model_id)

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
