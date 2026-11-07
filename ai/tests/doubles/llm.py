"""가짜 LLM. 정해 둔 응답이나 오류를 차례로 돌려준다. 모델을 부르지 않으며 품질 검증용이 아니다."""

from collections.abc import Callable, Mapping, Sequence

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


class ByModelLLM:
    """응답 형식(response_model)별로 정해 둔 응답을 돌려주는 가짜 LLM. 호출 순서에 묶이지 않는다.

    여러 턴을 끝까지 재생하는 시나리오 테스트에 쓴다. 형식마다 값은 둘 중 하나다.
    - 함수: 요청을 받아 응답을 만든다(입력에 든 ID를 읽어 써야 할 때).
    - 목록: 호출할 때마다 차례로 꺼내 쓰고 마지막 하나는 계속 반복한다.
    준비하지 않은 형식을 부르면 실패한다. 품질 검증용이 아니다.
    """

    def __init__(
        self,
        scripts: Mapping[
            type[BaseModel], Callable[[LLMRequest], BaseModel] | Sequence[BaseModel | BaseException]
        ],
        *,
        model_id: str = "fake-llm",
        usage: LLMUsage = LLMUsage(input_tokens=0, output_tokens=0),  # noqa: B008
    ) -> None:
        self._scripts = {
            model: script if callable(script) else list(script) for model, script in scripts.items()
        }
        self._model_id = model_id
        self._usage = usage
        self.requests: list[LLMRequest] = []

    async def generate[T: BaseModel](self, request: LLMRequest[T]) -> LLMResponse[T]:
        self.requests.append(request)
        script = self._scripts.get(request.response_model)
        if script is None:
            raise AssertionError(f"Fake LLM에 {request.response_model.__name__} 응답이 없다")
        if callable(script):
            outcome = script(request)
        else:
            outcome = script[0] if len(script) == 1 else script.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        return LLMResponse(parsed=outcome, usage=self._usage, model_id=self._model_id)

    def calls(self, model: type[BaseModel]) -> int:
        return sum(1 for request in self.requests if request.response_model is model)
