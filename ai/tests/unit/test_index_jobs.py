"""승인 사례 색인 작업의 접수·실행·조회(contracts/agents I1·I1.1). DB·Spring·저장소는 대역이다."""

import asyncio
import copy
from datetime import UTC, datetime, timedelta

import pytest

from hops_ai.contracts.indexing import CaseIndexSource
from hops_ai.persistence.index_jobs import IndexJob, IndexJobAcceptance, IndexJobConflict
from hops_ai.services.indexing.clients import IndexFailure
from hops_ai.services.indexing.digest import case_snapshot_digest, request_content_digest
from hops_ai.services.indexing.jobs import (
    IndexJobService,
    IndexRequestConflict,
    InvalidIndexRequest,
)

CASE_ID = "11111111-1111-4111-8111-111111111111"
JOB_ID = "22222222-2222-4222-8222-222222222222"
UUID_A = "33333333-3333-4333-8333-333333333333"
DOC_VERSION = "44444444-4444-4444-8444-444444444444"


def approved_case() -> dict:
    return {
        "workRecordId": UUID_A,
        "resolutionRequestId": UUID_A,
        "approvedBy": UUID_A,
        "approvedAt": "2026-11-09T00:39:20.683Z",
        "title": "상신 문서가 문서함에 보이지 않으면 상태 조건을 전체로 바꿔 조회",
        "action": "상태 조건을 전체로 바꿔 다시 조회하도록 안내했다.",
        "verification": "상태 전체로 조회해 문서가 보이는 것을 확인했다.",
        "applicability": "상태 조건을 승인으로 설정한 경우",
        "limitations": ["적용 시스템 버전이 확인되지 않았다."],
        "appliesToSystemVersion": None,
        "accessScope": "SYSTEM_STAFF",
        "origin": "SYNTHETIC",
        "confirmationOrigin": "HOPS_USER",
        "sourceMessageIds": [UUID_A],
        "entityLinks": [{"relation": "ADDRESSES", "targetId": "s-1", "evidenceIds": []}],
        "systemId": UUID_A,
        "confirmedCause": "결재 진행 중인 문서를 상태 승인으로 걸러 조회했다.",
        "problemStatement": "지난주 상신한 문서가 문서함에 안 보인다.",
        "caseType": "RESOLUTION",
    }


def request(store: str = "VECTOR", attempt: int = 1, case: dict | None = None) -> dict:
    body = case or approved_case()
    return {
        "contractVersion": "hops-system-1",
        "jobId": JOB_ID,
        "caseId": CASE_ID,
        "caseVersion": 1,
        "store": store,
        "attempt": attempt,
        "contentDigest": request_content_digest(CASE_ID, 1, store, body),
        "approvedCase": body,
    }


class MemoryJobs:
    """IndexJobStore와 같은 동작의 메모리 대역((jobId, attempt) 유일·조건부 상태 변경)."""

    def __init__(self) -> None:
        self.rows: dict[tuple[str, int], tuple[IndexJob, str]] = {}

    async def accept(self, **fields) -> IndexJobAcceptance:
        key = (fields["job_id"], fields["attempt"])
        if key in self.rows:
            job, digest = self.rows[key]
            if digest != fields["request_digest"]:
                raise IndexJobConflict("다른 요청")
            return IndexJobAcceptance(created=False, job=job)
        job = IndexJob(
            job_id=fields["job_id"],
            attempt=fields["attempt"],
            case_id=fields["case_id"],
            case_version=fields["case_version"],
            store=fields["store"],
            content_digest=fields["content_digest"],
            state="PENDING",
            error=None,
            updated_at=datetime.now(UTC),
        )
        self.rows[key] = (job, fields["request_digest"])
        return IndexJobAcceptance(created=True, job=job)

    async def get(self, job_id: str, attempt: int) -> IndexJob | None:
        found = self.rows.get((job_id, attempt))
        return found[0] if found else None

    async def transition(self, job_id, attempt, state, error=None, *, from_states):
        job, digest = self.rows[(job_id, attempt)]
        if job.state not in from_states:
            return None
        moved = IndexJob(
            **{**job.__dict__, "state": state, "error": error, "updated_at": datetime.now(UTC)}
        )
        self.rows[(job_id, attempt)] = (moved, digest)
        return moved

    def age(self, minutes: int) -> None:
        for key, (job, digest) in self.rows.items():
            older = job.updated_at - timedelta(minutes=minutes)
            self.rows[key] = (IndexJob(**{**job.__dict__, "updated_at": older}), digest)


class Source:
    def __init__(self, digest: str | None = None) -> None:
        self.digest = digest
        self.calls = 0

    async def get(self, case_id: str, case_version: int) -> CaseIndexSource:
        self.calls += 1
        return CaseIndexSource(
            contractVersion="hops-system-1",
            caseId=case_id,
            caseVersion=case_version,
            caseNumber="RC-0001",
            documentVersionId=DOC_VERSION,
            title="제목",
            applicability="조건",
            content="본문",
            contentDigest=self.digest
            or case_snapshot_digest(case_id, case_version, approved_case()),
        )


class Store:
    def __init__(self, failure: IndexFailure | None = None, delay: float = 0) -> None:
        self.failure = failure
        self.delay = delay
        self.applied: list[str] = []

    async def apply(self, request, source) -> None:
        await asyncio.sleep(self.delay)
        if self.failure:
            raise self.failure
        self.applied.append(source.documentVersionId)


def service(
    jobs=None, source=None, store=None, wait=1.0
) -> tuple[IndexJobService, MemoryJobs, Store]:
    jobs = jobs or MemoryJobs()
    store = store or Store()
    return (
        IndexJobService(
            jobs, source or Source(), {"VECTOR": store, "NEO4J": store}, wait_seconds=wait
        ),
        jobs,
        store,
    )


async def test_a_quick_index_finishes_inside_the_acceptance_response() -> None:
    svc, _, store = service()

    snapshot = await svc.accept(request())

    assert snapshot.state == "SUCCEEDED" and snapshot.error is None
    assert (snapshot.jobId, snapshot.attempt, snapshot.store) == (JOB_ID, 1, "VECTOR")
    assert snapshot.contentDigest == request()["contentDigest"]
    assert store.applied == [DOC_VERSION]


async def test_the_same_request_again_returns_the_same_job_without_running_it_twice() -> None:
    svc, _, store = service()

    first = await svc.accept(request())
    again = await svc.accept(copy.deepcopy(request()))

    assert first.state == again.state == "SUCCEEDED"
    assert store.applied == [DOC_VERSION]


async def test_concurrent_identical_requests_run_once() -> None:
    svc, _, store = service(store=Store(delay=0.05))

    results = await asyncio.gather(svc.accept(request()), svc.accept(request()))

    assert len(store.applied) == 1
    assert {result.jobId for result in results} == {JOB_ID}


async def test_a_different_body_under_the_same_key_is_a_conflict() -> None:
    svc, _, _ = service()
    await svc.accept(request())
    changed = approved_case() | {"action": "다른 조치"}

    with pytest.raises(IndexRequestConflict):
        await svc.accept(request(case=changed))


async def test_a_digest_that_does_not_match_the_body_is_rejected_before_accepting() -> None:
    svc, jobs, _ = service()
    body = request()
    body["contentDigest"] = "0" * 64

    with pytest.raises(IndexRequestConflict):
        await svc.accept(body)
    assert jobs.rows == {}


@pytest.mark.parametrize(
    "change",
    [{"contractVersion": "old"}, {"store": "ELASTIC"}, {"attempt": 0}, {"extra": 1}],
)
async def test_malformed_requests_are_invalid(change) -> None:
    svc, jobs, _ = service()

    with pytest.raises(InvalidIndexRequest):
        await svc.accept(request() | change)
    assert jobs.rows == {}


async def test_a_source_that_belongs_to_another_approval_fails_without_writing() -> None:
    svc, _, store = service(source=Source(digest="f" * 64))

    snapshot = await svc.accept(request())

    assert snapshot.state == "FAILED"
    assert snapshot.error is not None and snapshot.error.code == "UPSTREAM_PROTOCOL_ERROR"
    assert store.applied == []


async def test_a_store_failure_is_reported_with_its_code() -> None:
    failure = IndexFailure("INDEX_UNAVAILABLE", "Neo4j에 연결하지 못했다", retryable=True)
    svc, _, _ = service(store=Store(failure=failure))

    snapshot = await svc.accept(request(store="NEO4J"))

    assert snapshot.state == "FAILED"
    assert snapshot.error is not None
    assert (snapshot.error.code, snapshot.error.retryable) == ("INDEX_UNAVAILABLE", True)


async def test_a_slow_index_answers_running_and_finishes_later() -> None:
    svc, _, store = service(store=Store(delay=0.2), wait=0.01)

    accepted = await svc.accept(request())
    assert accepted.state in ("PENDING", "RUNNING")

    await asyncio.sleep(0.3)
    later = await svc.get(JOB_ID, 1)
    assert later is not None and later.state == "SUCCEEDED"
    assert store.applied == [DOC_VERSION]


async def test_a_job_nobody_is_running_any_more_fails_as_retryable() -> None:
    # 재시작 등으로 실행이 사라진 작업. 반영은 upsert라 다시 해도 같으므로 수동 재시도를 열어 둔다.
    jobs = MemoryJobs()
    svc, _, _ = service(jobs=jobs)
    await jobs.accept(
        job_id=JOB_ID,
        attempt=1,
        case_id=CASE_ID,
        case_version=1,
        store="VECTOR",
        content_digest=request()["contentDigest"],
        request_digest="d" * 64,
    )
    await jobs.transition(JOB_ID, 1, "RUNNING", from_states=("PENDING",))
    jobs.age(minutes=10)

    snapshot = await svc.get(JOB_ID, 1)

    assert snapshot is not None and snapshot.state == "FAILED"
    assert snapshot.error is not None
    assert (snapshot.error.code, snapshot.error.retryable) == ("INDEX_UNAVAILABLE", True)


async def test_a_long_job_still_running_here_is_not_judged_stale() -> None:
    jobs = MemoryJobs()
    svc, _, store = service(jobs=jobs, store=Store(delay=0.2), wait=0.01)
    await svc.accept(request())
    jobs.age(minutes=10)

    during = await svc.get(JOB_ID, 1)
    await asyncio.sleep(0.3)
    later = await svc.get(JOB_ID, 1)

    assert during is not None and during.state == "RUNNING"
    assert later is not None and later.state == "SUCCEEDED"
    assert store.applied == [DOC_VERSION]


async def test_an_unexpected_error_fails_as_retryable(caplog: pytest.LogCaptureFixture) -> None:
    class Broken(Store):
        async def apply(self, request, source) -> None:
            raise RuntimeError("가상의 민감한 원문")

    svc, _, _ = service(store=Broken())

    snapshot = await svc.accept(request())

    assert snapshot.state == "FAILED"
    assert snapshot.error is not None
    assert (snapshot.error.code, snapshot.error.retryable) == ("INDEX_UNAVAILABLE", True)
    assert "RuntimeError" in caplog.text
    assert "가상의 민감한 원문" not in caplog.text
    assert all(record.exc_info is None for record in caplog.records)


async def test_shutdown_fails_the_jobs_it_cancels_as_retryable() -> None:
    svc, _, _ = service(store=Store(delay=10), wait=0.01)
    await svc.accept(request())

    await svc.shutdown()

    snapshot = await svc.get(JOB_ID, 1)
    assert snapshot is not None and snapshot.state == "FAILED"
    assert snapshot.error is not None and snapshot.error.retryable is True


async def test_an_unknown_job_reads_as_none() -> None:
    svc, _, _ = service()

    assert await svc.get(JOB_ID, 7) is None


async def test_the_snapshot_time_is_utc_z_with_milliseconds() -> None:
    svc, _, _ = service()

    snapshot = await svc.accept(request())

    assert snapshot.updatedAt.endswith("Z") and len(snapshot.updatedAt) == len(
        "2026-11-09T00:39:20.683Z"
    )
