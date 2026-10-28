from typing import Any

import pytest
from pydantic import BaseModel

from hops_ai.fakes.llm import FakeLLMClient
from hops_ai.harness.budget import RunBudget
from hops_ai.harness.errors import LlmCallLimitExceeded, OutputRejected, TransientError
from hops_ai.services.llm.budgeted import BudgetedLLMClient
from hops_ai.services.llm.client import LLMClient, LLMMessage, LLMRequest, LLMUsage
from hops_ai.services.llm.usage import UsageRecorder


class Reply(BaseModel):
    text: str


class Other(BaseModel):
    number: int


def make_request(max_output_tokens: int = 500, prompt_version: str = "intake.v1") -> LLMRequest:
    return LLMRequest(
        messages=[LLMMessage(role="user", content="VPN 접속 오류가 나요.")],
        response_model=Reply,
        max_output_tokens=max_output_tokens,
        prompt_version=prompt_version,
    )


class Sleeper:
    def __init__(self, clock) -> None:
        self.clock = clock
        self.delays: list[float] = []

    async def __call__(self, delay: float) -> None:
        self.delays.append(delay)
        self.clock.advance(delay)


def budgeted(fake: FakeLLMClient, clock, make_settings, **overrides: Any):
    budget = RunBudget.start(make_settings(**overrides), clock=clock)
    usage = UsageRecorder()
    client = BudgetedLLMClient(fake, budget, usage, sleep=Sleeper(clock), rng=lambda: 1.0)
    return client, budget, usage


# ------------------------------------------------------------------ Fake LLM


async def test_fake_llm_returns_scripted_responses_in_order() -> None:
    fake = FakeLLMClient(Reply(text="첫째"), Reply(text="둘째"))

    first = await fake.generate(make_request())
    second = await fake.generate(make_request())

    assert (first.parsed.text, second.parsed.text) == ("첫째", "둘째")
    assert first.model_id == "fake-llm"
    assert len(fake.requests) == 2


async def test_fake_llm_raises_scripted_errors() -> None:
    fake = FakeLLMClient(TransientError(), OutputRejected())

    with pytest.raises(TransientError):
        await fake.generate(make_request())
    with pytest.raises(OutputRejected):
        await fake.generate(make_request())


async def test_fake_llm_refuses_calls_it_was_not_prepared_for() -> None:
    fake = FakeLLMClient(Reply(text="하나"))
    await fake.generate(make_request())

    with pytest.raises(AssertionError):
        await fake.generate(make_request())


async def test_fake_llm_rejects_a_response_of_the_wrong_type() -> None:
    fake = FakeLLMClient(Other(number=1))

    with pytest.raises(TypeError):
        await fake.generate(make_request())


async def test_fake_llm_can_repeat_the_last_response() -> None:
    fake = FakeLLMClient(Reply(text="반복"), repeat_last=True)

    texts = [(await fake.generate(make_request())).parsed.text for _ in range(3)]

    assert texts == ["반복"] * 3


def test_fake_llm_satisfies_the_client_interface() -> None:
    assert isinstance(FakeLLMClient(), LLMClient)


# ------------------------------------------------------------------ 예산 안의 호출


async def test_budgeted_client_returns_the_response_and_records_usage(clock, make_settings) -> None:
    fake = FakeLLMClient(
        Reply(text="분석 결과"), usage=LLMUsage(input_tokens=120, output_tokens=30)
    )
    client, budget, usage = budgeted(fake, clock, make_settings)

    response = await client.generate(make_request())

    assert response.parsed.text == "분석 결과"
    assert budget.llm_calls_used == 1
    summary = usage.summary()
    assert (summary.model_calls, summary.input_tokens, summary.output_tokens) == (1, 120, 30)
    assert summary.model_ids == ("fake-llm",)
    assert summary.prompt_versions == ("intake.v1",)


async def test_output_tokens_are_capped_by_the_budget(clock, make_settings) -> None:
    fake = FakeLLMClient(Reply(text="a"), Reply(text="b"))
    client, _, _ = budgeted(fake, clock, make_settings, max_output_tokens=1500)

    await client.generate(make_request(max_output_tokens=4000))
    await client.generate(make_request(max_output_tokens=500))

    assert [request.max_output_tokens for request in fake.requests] == [1500, 500]


async def test_transient_error_is_retried_and_only_the_success_is_recorded(
    clock, make_settings
) -> None:
    fake = FakeLLMClient(
        TransientError(),
        Reply(text="두 번째 시도"),
        usage=LLMUsage(input_tokens=10, output_tokens=5),
    )
    client, budget, usage = budgeted(fake, clock, make_settings)

    response = await client.generate(make_request())

    assert response.parsed.text == "두 번째 시도"
    assert len(fake.requests) == 2
    assert budget.llm_calls_used == 2  # 재시도도 호출 상한에 포함한다
    assert usage.summary().model_calls == 1


async def test_call_limit_stops_further_calls(clock, make_settings) -> None:
    fake = FakeLLMClient(Reply(text="x"), repeat_last=True)
    client, _, _ = budgeted(fake, clock, make_settings, max_llm_calls=2)

    await client.generate(make_request())
    await client.generate(make_request())

    with pytest.raises(LlmCallLimitExceeded):
        await client.generate(make_request())
    assert len(fake.requests) == 2


async def test_output_rejection_is_not_retried(clock, make_settings) -> None:
    fake = FakeLLMClient(OutputRejected(), Reply(text="무시됨"))
    client, budget, _ = budgeted(fake, clock, make_settings)

    with pytest.raises(OutputRejected):
        await client.generate(make_request())

    assert len(fake.requests) == 1
    assert budget.llm_calls_used == 1


def test_budgeted_client_satisfies_the_client_interface(clock, make_settings) -> None:
    client, _, _ = budgeted(FakeLLMClient(), clock, make_settings)

    assert isinstance(client, LLMClient)


# ------------------------------------------------------------------ 사용량 기록


def test_meta_has_null_model_fields_when_no_model_was_used() -> None:
    meta = UsageRecorder().to_meta(duration_ms=120)

    assert (meta.durationMs, meta.modelId, meta.promptVersion) == (120, None, None)


def test_meta_lists_every_distinct_model_and_prompt_version() -> None:
    usage = UsageRecorder()
    usage.record(model_id="m-a", prompt_version="intake.v1", usage=LLMUsage(10, 5))
    usage.record(model_id="m-a", prompt_version="ticket.v2", usage=LLMUsage(20, 8))
    usage.record(model_id="m-b", prompt_version="ticket.v2", usage=LLMUsage(1, 1))

    meta = usage.to_meta(duration_ms=900)
    summary = usage.summary()

    assert meta.modelId == "m-a+m-b"
    assert meta.promptVersion == "intake.v1+ticket.v2"
    assert (summary.model_calls, summary.input_tokens, summary.output_tokens) == (3, 31, 14)
