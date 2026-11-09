"""승인 사례 색인 작업의 접수·실행·조회(contracts/agents I1·I1.1).

- 접수: 형식과 contentDigest를 검증하고 (jobId, attempt)로 한 번만 접수한다. 새로 접수했으면 접수
  요청 안에서 반영을 시작해 제한 시간까지 기다린다. Spring은 접수 응답의 상태를 그대로 저장하고
  이후에는
  담당자의 확인으로만 다시 조회하므로, 짧은 반영은 접수 응답에서 끝난 상태를 돌려준다. 시간을 넘기면
  RUNNING으로 응답하고 반영은 계속한다.
- 실행: Spring에서 색인 원본을 읽고 그 digest가 요청의 승인 본문으로 계산한 사례 digest와 같을 때만
  저장소에 반영한다. 반영 뒤 원격 저장소를 다시 읽어 확인해야 SUCCEEDED다.
- 조회: 오래 끝나지 않은 작업(재시작 등으로 실행이 사라진 경우)은 UNKNOWN이다. 실패나 성공으로
  확정하지 않는다.
"""

import asyncio
import logging
from collections.abc import Mapping
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from pydantic import ValidationError

from hops_ai.contracts.indexing import (
    CONTRACT_VERSION,
    CaseIndexSource,
    IndexApplyRequest,
    IndexApplySnapshot,
)
from hops_ai.contracts.wire import AiError
from hops_ai.persistence.index_jobs import IndexJob, IndexJobConflict, IndexJobStore
from hops_ai.services.indexing.clients import IndexFailure
from hops_ai.services.indexing.digest import (
    canonical,
    case_snapshot_digest,
    request_content_digest,
    sha256,
)

logger = logging.getLogger(__name__)


class CaseSource(Protocol):
    async def get(self, case_id: str, case_version: int) -> CaseIndexSource: ...


class Store(Protocol):
    async def apply(self, request: IndexApplyRequest, source: CaseIndexSource) -> None: ...


class InvalidIndexRequest(ValueError):
    """형식이 계약과 다르다(422)."""


class IndexRequestConflict(ValueError):
    """digest가 맞지 않거나 같은 키에 다른 내용이 왔다(409)."""


class IndexJobService:
    def __init__(
        self,
        jobs: IndexJobStore,
        source: CaseSource,
        stores: Mapping[str, Store],
        *,
        wait_seconds: float = 20.0,
        stale_after: timedelta = timedelta(minutes=5),
    ) -> None:
        self._jobs = jobs
        self._source = source
        self._stores = stores
        self._wait_seconds = wait_seconds
        self._stale_after = stale_after
        self._running: set[asyncio.Task[None]] = set()

    async def accept(self, body: Any) -> IndexApplySnapshot:
        try:
            request = IndexApplyRequest.model_validate(body)
        except ValidationError:
            raise InvalidIndexRequest("색인 요청 형식이 올바르지 않다") from None
        # digest는 받은 JSON 원문으로 계산한다(모델 직렬화는 원문과 다를 수 있다).
        expected = request_content_digest(
            request.caseId, request.caseVersion, request.store, body["approvedCase"]
        )
        if expected != request.contentDigest:
            raise IndexRequestConflict("contentDigest가 승인 본문과 맞지 않는다")
        try:
            acceptance = await self._jobs.accept(
                job_id=request.jobId,
                attempt=request.attempt,
                case_id=request.caseId,
                case_version=request.caseVersion,
                store=request.store,
                content_digest=request.contentDigest,
                request_digest=sha256(canonical(body)),
            )
        except IndexJobConflict:
            raise IndexRequestConflict("같은 작업·attempt에 다른 요청이 왔다") from None
        if acceptance.created:
            task = asyncio.create_task(self._run(request, body["approvedCase"]))
            self._running.add(task)
            task.add_done_callback(self._running.discard)
            try:
                await asyncio.wait_for(asyncio.shield(task), self._wait_seconds)
            except TimeoutError:
                pass
        job = await self._jobs.get(request.jobId, request.attempt)
        return self._snapshot(job if job is not None else acceptance.job)

    async def get(self, job_id: str, attempt: int) -> IndexApplySnapshot | None:
        job = await self._jobs.get(job_id, attempt)
        if job is None:
            return None
        if job.state in ("PENDING", "RUNNING") and self._is_stale(job):
            moved = await self._jobs.transition(
                job_id, attempt, "UNKNOWN", from_states=("PENDING", "RUNNING")
            )
            job = moved or await self._jobs.get(job_id, attempt) or job
        return self._snapshot(job)

    async def shutdown(self) -> None:
        """진행 중인 반영을 멈춘다. 남은 작업은 조회 때 UNKNOWN이 된다."""
        for task in list(self._running):
            task.cancel()
        await asyncio.gather(*self._running, return_exceptions=True)

    def _is_stale(self, job: IndexJob) -> bool:
        return datetime.now(UTC) - job.updated_at > self._stale_after

    async def _run(self, request: IndexApplyRequest, approved_case: Mapping[str, Any]) -> None:
        started = await self._jobs.transition(
            request.jobId, request.attempt, "RUNNING", from_states=("PENDING",)
        )
        if started is None:
            return
        try:
            source = await self._source.get(request.caseId, request.caseVersion)
            if source.contentDigest != case_snapshot_digest(
                request.caseId, request.caseVersion, approved_case
            ):
                raise IndexFailure(
                    "UPSTREAM_PROTOCOL_ERROR",
                    "Spring 색인 원본이 요청한 승인 본문과 다르다",
                    retryable=False,
                )
            await self._stores[request.store].apply(request, source)
        except IndexFailure as failure:
            logger.info(
                "사례 색인 실패 jobId=%s attempt=%s store=%s code=%s",
                request.jobId,
                request.attempt,
                request.store,
                failure.code,
            )
            await self._jobs.transition(
                request.jobId,
                request.attempt,
                "FAILED",
                AiError(
                    code=failure.code, message=failure.message, retryable=failure.retryable
                ).model_dump(mode="json"),
                from_states=("RUNNING",),
            )
            return
        except asyncio.CancelledError:
            raise
        except Exception:
            # 알 수 없는 오류는 반영 여부를 확정할 수 없다. 원인 상세는 로그에만 남긴다.
            logger.exception(
                "사례 색인 중 예상하지 못한 오류 jobId=%s store=%s", request.jobId, request.store
            )
            await self._jobs.transition(
                request.jobId, request.attempt, "UNKNOWN", from_states=("RUNNING",)
            )
            return
        logger.info(
            "사례 색인 성공 jobId=%s attempt=%s store=%s caseId=%s caseVersion=%s",
            request.jobId,
            request.attempt,
            request.store,
            request.caseId,
            request.caseVersion,
        )
        await self._jobs.transition(
            request.jobId, request.attempt, "SUCCEEDED", from_states=("RUNNING",)
        )

    @staticmethod
    def _snapshot(job: IndexJob) -> IndexApplySnapshot:
        return IndexApplySnapshot(
            contractVersion=CONTRACT_VERSION,
            jobId=job.job_id,
            caseId=job.case_id,
            caseVersion=job.case_version,
            store=job.store,
            attempt=job.attempt,
            state=job.state,
            contentDigest=job.content_digest,
            error=AiError.model_validate(job.error) if job.error is not None else None,
            updatedAt=job.updated_at.strftime("%Y-%m-%dT%H:%M:%S.")
            + f"{job.updated_at.microsecond // 1000:03d}Z",
        )
