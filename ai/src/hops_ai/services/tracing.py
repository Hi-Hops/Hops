"""선택적으로 Langfuse에 실행 경로와 모델 호출을 기록한다."""

from contextlib import contextmanager

from pydantic import BaseModel

from hops_ai.services.llm.client import LLMClient, LLMRequest, LLMResponse


class RunTracing:
    def __init__(self, client=None, *, capture_content: bool = False) -> None:
        self.client = client
        self.capture_content = capture_content

    @contextmanager
    def observe(self, name: str, inputs: dict, *, generation: bool = False):
        if self.client is None:
            yield None
            return
        observation = self.client.start_as_current_observation(
            name=name, input=inputs, as_type="generation" if generation else "span"
        )
        span = observation.__enter__()
        try:
            yield span
        except BaseException as error:
            span.update(level="ERROR", status_message=type(error).__name__)
            raise
        finally:
            # SDK에 원본 예외나 스택을 넘기지 않는다.
            observation.__exit__(None, None, None)


class TracedLLMClient:
    def __init__(self, client: LLMClient, tracing: RunTracing) -> None:
        self.client = client
        self.tracing = tracing

    async def generate[T: BaseModel](self, request: LLMRequest[T]) -> LLMResponse[T]:
        capture_content = self.tracing.capture_content and not any(
            message.images for message in request.messages
        )
        inputs = {
            "promptVersion": request.prompt_version,
            "responseSchema": request.response_model.__name__,
            "messageCount": len(request.messages),
        }
        if capture_content:
            inputs["messages"] = [
                {"role": message.role, "content": message.content} for message in request.messages
            ]
        with self.tracing.observe("model.generate", inputs, generation=True) as span:
            response = await self.client.generate(request)
            if span is not None:
                span.update(
                    model=response.model_id,
                    output=response.parsed.model_dump(mode="json")
                    if capture_content
                    else {"schema": type(response.parsed).__name__, "parsed": True},
                    usage_details={
                        "input": response.usage.input_tokens,
                        "output": response.usage.output_tokens,
                    },
                )
            return response
