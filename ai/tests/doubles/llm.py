"""가짜 LLM. 정해 둔 응답이나 오류를 차례로 돌려준다. 모델을 부르지 않으며 품질 검증용이 아니다."""

from pydantic import BaseModel

from hops_ai.services.llm.client import LLMRequest, LLMResponse, LLMUsage


class FakeLLMClient:
    def __init__(
        self,
        *outcomes: BaseModel | BaseException,
        model_id: str = "fake-llm",
        usage: LLMUsage = LLMUsage(input_tokens=0, output_tokens=0),  # noqa: B008
        repeat_last: bool = False,
    ) -> None:
        self._outcomes = list(outcomes)
        self._model_id = model_id
        self._usage = usage
        self._repeat_last = repeat_last
        self.requests: list[LLMRequest] = []

    async def generate[T: BaseModel](self, request: LLMRequest[T]) -> LLMResponse[T]:
        self.requests.append(request)
        if not self._outcomes:
            raise AssertionError("Fake LLM에 준비된 응답이 모자란다")
        outcome = self._outcomes[0] if self._repeat_last else self._outcomes.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        if not isinstance(outcome, request.response_model):
            raise TypeError(
                f"Fake LLM 응답은 {request.response_model.__name__}이어야 하는데 "
                f"{type(outcome).__name__}이다"
            )
        return LLMResponse(parsed=outcome, usage=self._usage, model_id=self._model_id)
