"""실행기 접수·상태 전이·안전한 실패 검사. 모델·DB는 호출하지 않는다."""

import asyncio
from uuid import UUID

import pytest

from hops_ai.contracts.wire import AiRunRequest
from hops_ai.harness.errors import LlmCallLimitExceeded, RetrievalUnavailable, RunCancelled
from hops_ai.persistence.run_ledger import RunConflict
from hops_ai.services.runs.admission import RunAdmissionService
from hops_ai.services.runs.executor import ExecutorBusy, RunExecutor, server_ceilings
from hops_ai.settings import Settings
from tests.doubles.ledger import TERMINAL, MemoryLedger
from tests.doubles.run_requests import expires_in, inquiry_request, succeeded


class Pipeline:
    """정해 둔 동작만 수행한다. 업무 판단이나 성공 결과를 스스로 만들지 않는다."""

    def __init__(self, behaviour) -> None:
        self.behaviour = behaviour
        self.calls = 0
        self.contexts = []

    async def run(self, request, context):
        self.calls += 1
        self.contexts.append(context)
        return await self.behaviour(request)


def make_executor(pipeline, **settings) -> tuple[RunExecutor, MemoryLedger]:
    resolved = Settings(_env_file=None, **settings)
    ledger = MemoryLedger()
    admission = RunAdmissionService(ledger, server_ceilings(resolved))
    return RunExecutor(ledger, admission, pipeline, resolved), ledger


def request(**overrides) -> AiRunRequest:
    return inquiry_request(expires_at=overrides.pop("expires_at", expires_in(60)), **overrides)


async def finished(ledger: MemoryLedger, run_id: str) -> dict:
    for _ in range(200):
        snapshot = await ledger.get(UUID(run_id))
        if snapshot is not None and snapshot.status in TERMINAL:
            return snapshot.result
        await asyncio.sleep(0.01)
    raise AssertionError("실행이 종료 상태가 되지 않았다")


async def test_new_run_is_executed_and_checked_result_is_stored() -> None:
    async def ok(req):
        return succeeded(req)

    pipeline = Pipeline(ok)
    executor, ledger = make_executor(pipeline)
    req = request()
    acceptance = await executor.submit(req)
    assert acceptance.created
    assert acceptance.snapshot.status == "QUEUED"
    result = await finished(ledger, req.runId)
    assert result["status"] == "SUCCEEDED"
    assert result["answer"] == "가상 안내 문구"
    assert pipeline.calls == 1


async def test_same_input_replay_does_not_execute_again() -> None:
    async def ok(req):
        return succeeded(req)

    pipeline = Pipeline(ok)
    executor, ledger = make_executor(pipeline)
    req = request()
    await executor.submit(req)
    await finished(ledger, req.runId)
    replay = await executor.submit(req)
    assert not replay.created
    assert replay.snapshot.status == "SUCCEEDED"
    assert pipeline.calls == 1


async def test_same_run_id_with_different_input_is_a_conflict() -> None:
    async def ok(req):
        return succeeded(req)

    executor, _ = make_executor(Pipeline(ok))
    req = request()
    await executor.submit(req)
    changed = AiRunRequest.model_validate(req.model_dump(mode="json") | {"inquiryVersion": 2})
    with pytest.raises(RunConflict):
        await executor.submit(changed)


@pytest.mark.parametrize(
    "error,status,code",
    [
        (LlmCallLimitExceeded(), "FAILED", "BUDGET_EXCEEDED"),
        (RetrievalUnavailable(), "FAILED", "SEARCH_UNAVAILABLE"),
        (RunCancelled(), "SUPERSEDED", "SUPERSEDED"),
    ],
)
async def test_harness_failures_become_wire_errors(error, status, code) -> None:
    async def fail(req):
        raise error

    executor, ledger = make_executor(Pipeline(fail))
    req = request()
    await executor.submit(req)
    result = await finished(ledger, req.runId)
    assert result["status"] == status
    assert result["error"]["code"] == code
    assert result["answer"] is None
    assert result["problemProposals"] == []


async def test_unexpected_error_does_not_leak_details(caplog) -> None:
    async def fail(req):
        raise RuntimeError("PRIVATE-문의-원문")

    executor, ledger = make_executor(Pipeline(fail))
    req = request()
    await executor.submit(req)
    result = await finished(ledger, req.runId)
    assert result["status"] == "FAILED"
    assert result["error"]["code"] == "SERVICE_UNAVAILABLE"
    assert "PRIVATE" not in str(result)
    assert "PRIVATE" not in caplog.text


async def test_run_that_outlives_its_deadline_times_out() -> None:
    async def slow(req):
        await asyncio.sleep(5)
        return succeeded(req)

    executor, ledger = make_executor(Pipeline(slow))
    req = request(expires_at=expires_in(0.2))
    await executor.submit(req)
    result = await finished(ledger, req.runId)
    assert result["status"] == "TIMED_OUT"
    assert result["error"]["code"] == "DEADLINE_EXCEEDED"


@pytest.mark.parametrize("bad", ["other-run", "not-a-result"])
async def test_result_for_another_run_or_wrong_type_is_invalid_output(bad) -> None:
    other = request()

    async def wrong(req):
        return succeeded(req, run_id=other.runId) if bad == "other-run" else {"status": "OK"}

    executor, ledger = make_executor(Pipeline(wrong))
    req = request()
    await executor.submit(req)
    result = await finished(ledger, req.runId)
    assert result["status"] == "FAILED"
    assert result["error"]["code"] == "INVALID_OUTPUT"
    assert result["runId"] == req.runId


async def test_budget_uses_the_smaller_of_server_and_request_limits() -> None:
    async def ok(req):
        return succeeded(req)

    pipeline = Pipeline(ok)
    executor, ledger = make_executor(pipeline, max_llm_calls=8, max_auto_retries=2)
    req = request(max_calls=3)
    await executor.submit(req)
    await finished(ledger, req.runId)
    budget = pipeline.contexts[0].budget
    assert budget.max_llm_calls == 3
    assert budget.max_auto_retries == 1  # 요청 maxRetries=1
    assert pipeline.contexts[0].access_scope_ref == req.runId


async def test_busy_executor_rejects_new_runs_but_allows_replay() -> None:
    release = asyncio.Event()

    async def blocked(req):
        await release.wait()
        return succeeded(req)

    executor, ledger = make_executor(Pipeline(blocked), max_concurrent_runs=1)
    first = request()
    await executor.submit(first)
    with pytest.raises(ExecutorBusy):
        await executor.submit(request())
    assert not (await executor.submit(first)).created
    release.set()
    assert (await finished(ledger, first.runId))["status"] == "SUCCEEDED"
    assert executor.active_runs == 0


async def test_shutdown_stops_runs_without_storing_a_result() -> None:
    async def blocked(req):
        await asyncio.Event().wait()

    executor, ledger = make_executor(Pipeline(blocked))
    req = request()
    await executor.submit(req)
    await asyncio.sleep(0.05)
    await executor.shutdown()
    snapshot = await ledger.get(UUID(req.runId))
    assert snapshot.status == "RUNNING"
    assert ledger.finish_calls == 0
    assert executor.active_runs == 0


def test_server_ceilings_follow_settings() -> None:
    ceilings = server_ceilings(Settings(_env_file=None))
    assert ceilings.maxModelCalls == 8
    assert ceilings.maxOutputTokens == 8 * 1500
    assert ceilings.maxRetries == 7
    assert ceilings.maxInputTokens == 60000
    assert ceilings.maxToolCalls == 10
