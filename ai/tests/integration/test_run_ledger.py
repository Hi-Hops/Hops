"""명시한 테스트 전용 PostgreSQL에서만 실행하며 실제 운영 DB를 수정하지 않는다."""

import asyncio
import os
import sys
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
import pytest
from httpx import ASGITransport, AsyncClient
from psycopg import sql
from psycopg.conninfo import conninfo_to_dict, make_conninfo

from hops_ai.main import create_app
from hops_ai.persistence.run_ledger import InvalidRunTransition, RunConflict, RunLedger
from hops_ai.services.runs.admission import AdmissionRejected, ExecutionLimits, RunAdmissionService
from hops_ai.services.runs.executor import RunExecutor, server_ceilings
from hops_ai.settings import Settings
from tests.doubles.run_requests import expires_in, inquiry_request, succeeded


@pytest.fixture
async def ledger() -> AsyncIterator[RunLedger]:
    conninfo = os.environ.get("HOPS_LEDGER_TEST_DSN")
    if not conninfo:
        pytest.skip("테스트 전용 HOPS_LEDGER_TEST_DSN이 설정되지 않았다")
    if conninfo_to_dict(conninfo).get("dbname") != "hops_ai_runtime_test":
        pytest.fail("RunLedger 통합 테스트는 hops_ai_runtime_test DB에서만 실행한다")
    migrations = Path(__file__).parents[2] / "migrations"
    async with await psycopg.AsyncConnection.connect(conninfo) as conn:
        await conn.execute((migrations / "001_run_ledger.sql").read_text(encoding="utf-8"))
        cursor = await conn.execute(
            """SELECT EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'ai_run_ledger'
                 AND column_name = 'owner_id')"""
        )
        if not (await cursor.fetchone())[0]:
            await conn.execute((migrations / "002_run_owner.sql").read_text(encoding="utf-8"))
    async with RunLedger(conninfo) as instance:
        yield instance


async def test_same_run_is_atomic_and_conflicting_input_is_rejected(ledger: RunLedger) -> None:
    run_id = uuid4()
    payload = {"message": "가상 문의", "items": [1, 2]}
    async with RunLedger(ledger._conninfo) as other:
        responses = await asyncio.gather(
            *(instance.accept(run_id, payload) for instance in (ledger, other) for _ in range(6))
        )
        owner = ledger if any(r.created for r in responses[:6]) else other
        assert sum(await asyncio.gather(*(owner.start(run_id) for _ in range(8)))) == 1
    assert sum(response.created for response in responses) == 1
    assert all(response.snapshot.status == "QUEUED" for response in responses)
    assert len({response.snapshot.input_digest for response in responses}) == 1
    assert not (await ledger.accept(run_id, {"items": [1, 2], "message": "가상 문의"})).created
    with pytest.raises(RunConflict):
        await ledger.accept(run_id, {"message": "다른 문의", "items": [1, 2]})


async def test_final_result_is_immutable_and_unknown_blocks_late_result(ledger: RunLedger) -> None:
    run_id = uuid4()
    await ledger.accept(run_id, {"message": "가상 문의"})
    assert await ledger.start(run_id)
    result = {"runId": str(run_id), "status": "SUCCEEDED", "answer": "안내"}
    await ledger.finish(run_id, "SUCCEEDED", result, 2)
    await ledger.finish(run_id, "SUCCEEDED", result, 2)
    with pytest.raises(RunConflict):
        await ledger.finish(run_id, "SUCCEEDED", result | {"answer": "다른 안내"}, 2)
    snapshot = await ledger.get(run_id)
    assert snapshot is not None and snapshot.result == result and snapshot.last_seq == 2
    interrupted = uuid4()
    await ledger.accept(interrupted, {"message": "중단된 가상 문의"})
    await ledger.start(interrupted)
    await ledger.close()
    # 재시작한 새 인스턴스가 잠금이 풀린 이전 실행을 자동 복구한다.
    async with RunLedger(ledger._conninfo) as restarted:
        assert not (await restarted.accept(interrupted, {"message": "중단된 가상 문의"})).created
        with pytest.raises(InvalidRunTransition):
            await restarted.finish(
                interrupted, "SUCCEEDED", result | {"runId": str(interrupted)}, 1
            )
        assert (await restarted.get(interrupted)).status == "UNKNOWN"
        assert (await restarted.get(run_id)).status == "SUCCEEDED"


async def test_recovery_preserves_live_other_owner_and_rejects_stale_writer(
    ledger: RunLedger,
) -> None:
    active = uuid4()
    await ledger.accept(active, {"message": "다른 서버의 실행"})
    await ledger.start(active)
    async with RunLedger(ledger._conninfo) as sibling:
        # 다른 인스턴스 시작·반복 복구는 살아 있는 소유자의 run을 유지한다.
        assert await sibling.recover_abandoned() == 0
        assert (await sibling.get(active)).status == "RUNNING"
        assert not await sibling.start(active)
        result = {"runId": str(active), "status": "SUCCEEDED", "answer": "안내"}
        with pytest.raises(InvalidRunTransition):
            await sibling.finish(active, "SUCCEEDED", result, 1)
        await ledger.close()
        assert await sibling.recover_abandoned() == 1
        assert (await sibling.get(active)).status == "UNKNOWN"
        with pytest.raises(InvalidRunTransition):
            await ledger.finish(active, "SUCCEEDED", result, 1)
        assert not (await sibling.accept(active, {"message": "다른 서버의 실행"})).created
        assert await sibling.recover_abandoned() == 0


async def test_detail_expiry_retains_id_and_digest(ledger: RunLedger) -> None:
    run_id = uuid4()
    payload = {"message": "가상 문의"}
    await ledger.accept(run_id, payload)
    await ledger.start(run_id)
    result = {"runId": str(run_id), "status": "SUCCEEDED", "answer": "안내"}
    await ledger.finish(run_id, "SUCCEEDED", result, 1)
    assert not await ledger.expire_detail(run_id)
    async with await psycopg.AsyncConnection.connect(ledger._conninfo) as conn:
        await conn.execute(
            "UPDATE ai_run_ledger SET finished_at = CURRENT_TIMESTAMP - INTERVAL '25 hours' "
            "WHERE run_id = %s",
            (run_id,),
        )
    assert await ledger.expire_detail(run_id)
    accepted = await ledger.accept(run_id, payload)
    assert not accepted.created and accepted.snapshot.status == "UNKNOWN"
    assert accepted.snapshot.result is None
    with pytest.raises(RunConflict):
        await ledger.accept(run_id, {"message": "다른 가상 문의"})


async def test_sequence_is_atomic_and_unavailable_after_termination(ledger: RunLedger) -> None:
    run_id = uuid4()
    await ledger.accept(run_id, {"message": "가상 문의"})
    with pytest.raises(InvalidRunTransition):
        await ledger.next_sequence(run_id)
    result = {"runId": str(run_id), "status": "SUCCEEDED", "answer": "안내"}
    with pytest.raises(InvalidRunTransition):
        await ledger.finish(run_id, "SUCCEEDED", result, 0)
    await ledger.start(run_id)
    sequences = await asyncio.gather(*(ledger.next_sequence(run_id) for _ in range(8)))
    assert sorted(sequences) == list(range(1, 9))
    with pytest.raises(InvalidRunTransition):
        await ledger.finish(run_id, "SUCCEEDED", result, 7)
    await ledger.finish(run_id, "SUCCEEDED", result, 8)
    with pytest.raises(InvalidRunTransition):
        await ledger.next_sequence(run_id)


async def test_admission_checks_limits_expiry_and_reuses_existing(ledger: RunLedger) -> None:
    ceilings = ExecutionLimits(4, 1000, 500, 3, 1)
    service = RunAdmissionService(ledger, ceilings, clock=lambda: datetime(2026, 11, 2, tzinfo=UTC))
    request = inquiry_request()
    assert (await service.accept(request)).created
    assert not (await service.accept(request)).created
    with pytest.raises(RunConflict):
        await service.accept(request.model_copy(update={"expiresAt": "2026-11-02T02:00:00Z"}))
    with pytest.raises(AdmissionRejected):
        await service.accept(
            request.model_copy(update={"runId": str(uuid4()), "expiresAt": "2026-11-01T00:00:00Z"})
        )
    invalid_limits = request.limits.model_copy(update={"maxModelCalls": 5})
    with pytest.raises(AdmissionRejected):
        await service.accept(
            request.model_copy(update={"runId": str(uuid4()), "limits": invalid_limits})
        )


async def test_app_start_recovers_runs_after_process_crash(ledger: RunLedger) -> None:
    active = uuid4()
    await ledger.accept(active, {"message": "다른 인스턴스"})
    await ledger.start(active)
    abandoned = uuid4()
    queued = uuid4()
    # 별도 프로세스가 접수와 실행을 완료한 직후 정리 없이 종료한다.
    script = """
import asyncio, os, sys
from uuid import UUID
from hops_ai.persistence.run_ledger import RunLedger
async def run():
    ledger = RunLedger(os.environ['HOPS_LEDGER_TEST_DSN'])
    await ledger.open()
    await ledger.accept(UUID(sys.argv[1]), {'message': '중단된 실행'})
    await ledger.start(UUID(sys.argv[1]))
    await ledger.accept(UUID(sys.argv[2]), {'message': '중단된 대기'})
    os._exit(0)
asyncio.run(run())
"""
    process = await asyncio.create_subprocess_exec(
        sys.executable,
        "-c",
        script,
        str(abandoned),
        str(queued),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    _, stderr = await process.communicate()
    assert process.returncode == 0, stderr.decode()
    restarted_ledger = RunLedger(ledger._conninfo)
    app = create_app(Settings(_env_file=None), ledger=restarted_ledger)
    async with app.router.lifespan_context(app):
        assert app.state.run_ledger is restarted_ledger
        assert (await restarted_ledger.get(abandoned)).status == "UNKNOWN"
        assert (await restarted_ledger.get(queued)).status == "UNKNOWN"
        assert (await restarted_ledger.get(active)).status == "RUNNING"
        assert not await restarted_ledger.start(abandoned)
        assert not (await restarted_ledger.accept(abandoned, {"message": "중단된 실행"})).created
    with pytest.raises(InvalidRunTransition):
        await restarted_ledger.start(active)


async def test_owner_migration_preserves_legacy_results_and_marks_unowned_runs_unknown(
    ledger: RunLedger,
) -> None:
    migrations = Path(__file__).parents[2] / "migrations"
    schema = "ledger_test_" + uuid4().hex
    async with await psycopg.AsyncConnection.connect(ledger._conninfo, autocommit=True) as conn:
        await conn.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
        try:
            await conn.execute(sql.SQL("SET search_path TO {}").format(sql.Identifier(schema)))
            await conn.execute((migrations / "001_run_ledger.sql").read_text(encoding="utf-8"))
            pending, completed = uuid4(), uuid4()
            await conn.execute(
                "INSERT INTO ai_run_ledger(run_id, input_digest, status) "
                "VALUES (%s, %s, 'RUNNING')",
                (pending, "a" * 64),
            )
            await conn.execute(
                """INSERT INTO ai_run_ledger(run_id, input_digest, status, result, finished_at)
                   VALUES (%s, %s, 'SUCCEEDED', '{}'::jsonb, CURRENT_TIMESTAMP)""",
                (completed, "b" * 64),
            )
            await conn.execute((migrations / "002_run_owner.sql").read_text(encoding="utf-8"))
            cursor = await conn.execute(
                "SELECT status, input_digest, result FROM ai_run_ledger WHERE run_id = %s",
                (pending,),
            )
            assert await cursor.fetchone() == ("UNKNOWN", "a" * 64, None)
            cursor = await conn.execute(
                "SELECT status, input_digest, result FROM ai_run_ledger WHERE run_id = %s",
                (completed,),
            )
            assert await cursor.fetchone() == ("SUCCEEDED", "b" * 64, {})
            with pytest.raises(psycopg.errors.CheckViolation):
                await conn.execute(
                    "INSERT INTO ai_run_ledger(run_id, input_digest, status) "
                    "VALUES (%s, %s, 'QUEUED')",
                    (uuid4(), "c" * 64),
                )
        finally:
            await conn.execute("SET search_path TO public")
            await conn.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))


async def test_limited_role_can_recover_without_business_database_access(ledger: RunLedger) -> None:
    conninfo = os.environ.get("HOPS_LEDGER_LIMITED_TEST_DSN")
    if not conninfo:
        pytest.skip("제한된 테스트 계정의 연결 문자열이 설정되지 않았다")
    if conninfo_to_dict(conninfo).get("dbname") != "hops_ai_runtime_test":
        pytest.fail("제한 계정 테스트는 hops_ai_runtime_test DB에서만 실행한다")

    async with await psycopg.AsyncConnection.connect(conninfo) as conn:
        cursor = await conn.execute(
            """SELECT current_user, r.rolsuper, r.rolcreatedb, r.rolcreaterole,
                      has_table_privilege(current_user, 'public.ai_run_ledger', 'SELECT'),
                      has_table_privilege(current_user, 'public.ai_run_ledger', 'INSERT'),
                      has_table_privilege(current_user, 'public.ai_run_ledger', 'UPDATE'),
                      has_table_privilege(current_user, 'public.ai_run_ledger', 'DELETE'),
                      has_schema_privilege(current_user, 'public', 'CREATE'),
                      has_database_privilege(current_user, 'hops_business_test', 'CONNECT')
               FROM pg_roles r WHERE r.rolname = current_user"""
        )
        permissions = await cursor.fetchone()
        assert permissions == (
            "hops_ai_test",
            False,
            False,
            False,
            True,
            True,
            True,
            False,
            False,
            False,
        )

    # 별도 업무 DB에 실제 접속을 시도해 권한 거부를 확인한다.
    business_conninfo = make_conninfo(conninfo, dbname="hops_business_test")
    with pytest.raises(psycopg.OperationalError, match="permission denied"):
        await psycopg.AsyncConnection.connect(business_conninfo)

    with pytest.raises(psycopg.errors.InsufficientPrivilege):
        async with await psycopg.AsyncConnection.connect(conninfo) as conn:
            await conn.execute("DELETE FROM public.ai_run_ledger WHERE run_id = %s", (uuid4(),))
    with pytest.raises(psycopg.errors.InsufficientPrivilege):
        async with await psycopg.AsyncConnection.connect(conninfo) as conn:
            await conn.execute("CREATE TABLE public.hops_ai_privilege_test (id integer)")

    run_id = uuid4()
    payload = {"message": "제한 계정 테스트"}
    async with RunLedger(conninfo) as owner:
        assert (await owner.accept(run_id, payload)).created
        assert await owner.start(run_id)
    async with RunLedger(conninfo) as restarted:
        assert (await restarted.get(run_id)).status == "UNKNOWN"
        assert not (await restarted.accept(run_id, payload)).created


async def test_internal_snapshot_reads_persisted_and_recovered_run(ledger: RunLedger) -> None:
    run_id = uuid4()
    await ledger.accept(run_id, {"message": "가상 문의"})
    await ledger.start(run_id)
    settings = Settings(
        _env_file=None,
        internal_api_enabled=True,
        runtime_dsn="dbname=hops_ai_runtime user=test_user",
        ai_service_token="A" * 43,
    )
    app = create_app(settings, ledger=RunLedger(ledger._conninfo))
    async with app.router.lifespan_context(app):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            path = f"/internal/v2/runs/{run_id}"
            assert (await client.get(path)).status_code == 401
            response = await client.get(path, headers={"Authorization": f"Bearer {'A' * 43}"})
            assert response.status_code == 200
            assert response.json()["status"] == "RUNNING"
            await ledger.close()
            assert await app.state.run_ledger.recover_abandoned() == 1
            response = await client.get(path, headers={"Authorization": f"Bearer {'A' * 43}"})
            assert response.json()["status"] == "UNKNOWN"


async def test_executor_stores_result_and_shutdown_leaves_run_for_recovery(
    ledger: RunLedger,
) -> None:
    settings = Settings(_env_file=None)
    admission = RunAdmissionService(ledger, server_ceilings(settings))

    class Succeeding:
        async def run(self, request, context):
            return succeeded(request)

    class Blocking:
        async def run(self, request, context):
            await asyncio.Event().wait()

    done = inquiry_request(expires_at=expires_in(60))
    assert (await RunExecutor(ledger, admission, Succeeding(), settings).submit(done)).created
    for _ in range(200):
        snapshot = await ledger.get(UUID(done.runId))
        if snapshot is not None and snapshot.status == "SUCCEEDED":
            break
        await asyncio.sleep(0.01)
    assert snapshot.status == "SUCCEEDED"
    assert snapshot.result["answer"] == "가상 안내 문구"

    # 종료 중 멈춘 실행은 결과 없이 남고, 재기동한 소유자가 UNKNOWN으로 복구한다.
    blocked_executor = RunExecutor(ledger, admission, Blocking(), settings)
    interrupted = inquiry_request(expires_at=expires_in(60))
    await blocked_executor.submit(interrupted)
    await asyncio.sleep(0.05)
    await blocked_executor.shutdown()
    assert (await ledger.get(UUID(interrupted.runId))).status == "RUNNING"
    await ledger.close()
    async with RunLedger(ledger._conninfo) as restarted:
        assert (await restarted.get(UUID(interrupted.runId))).status == "UNKNOWN"
        assert (await restarted.get(UUID(done.runId))).status == "SUCCEEDED"
