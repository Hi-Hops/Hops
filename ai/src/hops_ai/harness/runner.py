"""분석 실행의 생명주기. 요청을 받아 백그라운드로 실행하고 결과를 이벤트로 남긴다.

- 같은 runId 요청은 한 번만 실행한다(중복 수신). 같은 runId에 다른 문의·버전이 오면 충돌이다.
- 같은 문의에 더 높은 버전 요청이 오면 아직 끝나지 않은 낮은 버전 실행을 취소한다(대체). 대체된
  실행의 늦은 결과는 쓰지 않는다. 최종 판단은 Spring이 문의 버전으로 한다.
- 끝난 실행은 일정 시간 보관해 SSE를 다시 이어 받을 수 있게 한다.
- 어떤 경우에도 최종 결과는 한 번 내보낸다. 성공은 티켓 저장·배정 성공을 뜻하지 않는다.
"""

import asyncio
import logging
from collections.abc import Callable
from dataclasses import dataclass

from hops_ai.agents.base import run_analysis
from hops_ai.agents.registry import AgentRegistry, select_analyzer
from hops_ai.api import schemas as wire
from hops_ai.api.mappers import to_ai_result, to_analysis
from hops_ai.contracts.agent_1 import AgentError
from hops_ai.contracts.analysis import AnalysisFailure
from hops_ai.harness.budget import Clock, SystemClock
from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import RunCancelled, describe_failure
from hops_ai.harness.events import RunEvents
from hops_ai.harness.trace import NoopTracer, RunRecord, Tracer
from hops_ai.services.llm.usage import UsageRecorder
from hops_ai.services.shared import SharedServices
from hops_ai.settings import Settings

logger = logging.getLogger(__name__)

ServicesFactory = Callable[[RunContext, UsageRecorder], SharedServices]
DEFAULT_RETENTION_SECONDS = 600.0


class RunConflict(Exception):
    """같은 runId에 다른 문의 또는 버전이 왔다."""


@dataclass
class RunHandle:
    run_id: str
    inquiry_id: str
    inquiry_version: int
    events: RunEvents
    task: asyncio.Task | None = None
    finished_at: float | None = None


class RunManager:
    def __init__(
        self,
        settings: Settings,
        registry: AgentRegistry,
        services_factory: ServicesFactory,
        *,
        tracer: Tracer | None = None,
        clock: Clock | None = None,
        retention_seconds: float = DEFAULT_RETENTION_SECONDS,
    ) -> None:
        self._settings = settings
        self._registry = registry
        self._services_factory = services_factory
        self._tracer = tracer or NoopTracer()
        self._clock = clock or SystemClock()
        self._retention_seconds = retention_seconds
        self._runs: dict[str, RunHandle] = {}

    # ------------------------------------------------------------ 요청 접수

    def start(self, request: wire.AiRequest) -> wire.AiAccepted:
        self._purge_expired()
        existing = self._runs.get(request.runId)
        if existing is not None:
            if (existing.inquiry_id, existing.inquiry_version) != (
                request.inquiryId,
                request.inquiryVersion,
            ):
                raise RunConflict(request.runId)
            return self._accepted(request)  # 중복 수신. 새로 실행하지 않는다
        self._supersede_older(request)
        handle = RunHandle(
            run_id=request.runId,
            inquiry_id=request.inquiryId,
            inquiry_version=request.inquiryVersion,
            events=RunEvents(
                run_id=request.runId,
                inquiry_id=request.inquiryId,
                inquiry_version=request.inquiryVersion,
            ),
        )
        self._runs[request.runId] = handle
        handle.task = asyncio.create_task(self._run(handle, request))
        return self._accepted(request)

    def get(self, run_id: str) -> RunHandle | None:
        return self._runs.get(run_id)

    def active_count(self) -> int:
        return sum(1 for handle in self._runs.values() if handle.finished_at is None)

    async def shutdown(self) -> None:
        """서버를 내릴 때 끝나지 않은 실행을 취소하고 결과를 낸 뒤 기다린다."""
        tasks = [
            handle.task
            for handle in self._runs.values()
            if handle.task is not None and not handle.task.done()
        ]
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    # ------------------------------------------------------------ 실행

    async def _run(self, handle: RunHandle, request: wire.AiRequest) -> None:
        started = self._clock.monotonic()
        usage = UsageRecorder()
        runtime: RunContext | None = None
        result: wire.AiSuccess | wire.AiFailure | None = None
        try:
            mapped = to_analysis(request)
            runtime = RunContext.create(
                run_id=request.runId,
                inquiry_id=request.inquiryId,
                inquiry_version=request.inquiryVersion,
                actor=mapped.actor,
                access_scope_ref=mapped.access_scope_ref,
                settings=self._settings,
                deadline_at=mapped.deadline_at,
                clock=self._clock,
            )
            services = self._services_factory(runtime, usage)
            analyzer = select_analyzer(self._settings, self._registry, events=handle.events)
            analysis = await run_analysis(analyzer, mapped.data, services)
            result = self._to_wire(request, analysis)
        except asyncio.CancelledError:
            result = self._cancelled(request)
            raise
        except Exception as error:
            logger.exception("실행 %s를 시작하거나 마치지 못했다", request.runId)
            failure = describe_failure(error)
            result = self._to_wire(
                request, AnalysisFailure(status=failure.status, error=failure.error)
            )
        finally:
            await self._finish(handle, request, result, runtime, usage, started)

    def _to_wire(self, request: wire.AiRequest, analysis) -> wire.AiSuccess | wire.AiFailure:
        try:
            return to_ai_result(request.runId, request.inquiryId, request.inquiryVersion, analysis)
        except Exception as error:  # 외부로 보낼 수 없는 결과는 성공으로 내보내지 않는다
            logger.warning(
                "실행 %s의 결과를 변환하지 못했다: %s", request.runId, type(error).__name__
            )
            failure = describe_failure(error)
            return to_ai_result(
                request.runId,
                request.inquiryId,
                request.inquiryVersion,
                AnalysisFailure(status=failure.status, error=failure.error),
            )

    @staticmethod
    def _cancelled(request: wire.AiRequest) -> wire.AiFailure:
        error = AgentError(code="SUPERSEDED", message=RunCancelled.safe_message, retryable=False)
        result = to_ai_result(
            request.runId,
            request.inquiryId,
            request.inquiryVersion,
            AnalysisFailure(status="CANCELLED", error=error),
        )
        assert isinstance(result, wire.AiFailure)
        return result

    async def _finish(
        self,
        handle: RunHandle,
        request: wire.AiRequest,
        result: wire.AiSuccess | wire.AiFailure | None,
        runtime: RunContext | None,
        usage: UsageRecorder,
        started: float,
    ) -> None:
        if result is None:  # 도달하지 않아야 하는 경로. 결과 없이 끝내지 않는다
            result = self._to_wire(
                request,
                AnalysisFailure(status="ERROR", error=describe_failure(RuntimeError()).error),
            )
        await handle.events.finish(result)
        handle.finished_at = self._clock.monotonic()
        self._record(handle, request, result, runtime, usage, started)

    def _record(
        self,
        handle: RunHandle,
        request: wire.AiRequest,
        result: wire.AiSuccess | wire.AiFailure,
        runtime: RunContext | None,
        usage: UsageRecorder,
        started: float,
    ) -> None:
        summary = usage.summary()
        error_code = result.error.code if isinstance(result, wire.AiFailure) else None
        record = RunRecord(
            run_id=request.runId,
            inquiry_id=request.inquiryId,
            inquiry_version=request.inquiryVersion,
            mode=self._settings.mode.value,
            agent_mode=self._settings.agent_mode.value,
            status=result.status,
            error_code=error_code,
            duration_ms=round((self._clock.monotonic() - started) * 1000),
            llm_calls_used=runtime.budget.llm_calls_used if runtime is not None else 0,
            input_tokens=summary.input_tokens,
            output_tokens=summary.output_tokens,
            model_ids=summary.model_ids,
            prompt_versions=summary.prompt_versions,
            event_count=len(handle.events.history()),
        )
        try:
            self._tracer.record_run(record)
        except Exception:  # 기록 실패가 실행 결과를 바꾸지 않는다
            logger.exception("실행 기록을 남기지 못했다: %s", request.runId)

    # ------------------------------------------------------------ 보조

    @staticmethod
    def _accepted(request: wire.AiRequest) -> wire.AiAccepted:
        return wire.AiAccepted(
            schemaVersion=wire.SCHEMA_VERSION,
            runId=request.runId,
            inquiryId=request.inquiryId,
            inquiryVersion=request.inquiryVersion,
            status="ACCEPTED",
        )

    def _supersede_older(self, request: wire.AiRequest) -> None:
        for handle in self._runs.values():
            if (
                handle.inquiry_id == request.inquiryId
                and handle.inquiry_version < request.inquiryVersion
                and handle.finished_at is None
                and handle.task is not None
            ):
                handle.task.cancel()

    def _purge_expired(self) -> None:
        now = self._clock.monotonic()
        expired = [
            run_id
            for run_id, handle in self._runs.items()
            if handle.finished_at is not None and now - handle.finished_at > self._retention_seconds
        ]
        for run_id in expired:
            del self._runs[run_id]
