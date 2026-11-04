"""AI 앱 계정의 런타임 DB 권한 점검. 운영자가 AI 컨테이너 안에서 직접 실행한다.

    docker exec hops-ai python -m hops_ai.persistence.check_runtime_db --business-dbname hops

HOPS_AI_RUNTIME_DSN(앱이 실제로 쓰는 연결 문자열)으로 접속해 다음을 확인한다.

- 런타임 DB: 관리자 속성이 없고 ai_run_ledger에 SELECT·INSERT·UPDATE만 있으며,
  삭제·테이블 생성·임시 테이블이 거부되고 RunLedger가 쓰는 세션 잠금을 잡을 수 있다.
- 업무 DB(선택): 같은 계정으로 접속했을 때 업무 테이블과 hops_knowledge에 아무 권한이 없다.
  검색 연동 전까지는 접속 자체가 거부돼도 통과로 본다.

기본 점검은 데이터를 바꾸지 않는다. 거부돼야 할 변경은 트랜잭션 안에서 시도하고 되돌린다.
--write-check를 주면 점검용 run 한 건을 접수·완료한다.
이 행은 24시간 뒤 일반 만료 절차로 상세가 지워진다.
연결 문자열·비밀번호·예외 상세는 출력하지 않는다.
"""

import argparse
import asyncio
import os
import sys
from dataclasses import dataclass
from uuid import uuid4

import psycopg
from psycopg.conninfo import conninfo_to_dict, make_conninfo

RUNTIME_DB = "hops_ai_runtime"
LEDGER_TABLE = "public.ai_run_ledger"
REQUIRED = ("SELECT", "INSERT", "UPDATE")
FORBIDDEN = ("DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")
# 업무 DB에서는 테이블·시퀀스에 줄 수 있는 모든 권한이 없어야 한다.
TABLE_PRIVILEGES = ("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")
SEQUENCE_PRIVILEGES = ("SELECT", "USAGE", "UPDATE")
# 열 단위로도 줄 수 있는 권한. 테이블 권한이 없어도 일부 열에만 부여됐을 수 있다.
COLUMN_PRIVILEGES = ("SELECT", "INSERT", "UPDATE", "REFERENCES")
# PostgreSQL 17에서 추가된 테이블 권한(VACUUM·ANALYZE 등 유지 작업).
MAINTAIN_SINCE = 170000
SYSTEM_SCHEMAS = ("pg_catalog", "information_schema", "pg_toast")


@dataclass(frozen=True)
class Check:
    name: str
    ok: bool
    detail: str = ""


async def check_runtime(conninfo: str) -> list[Check]:
    checks: list[Check] = []
    async with await psycopg.AsyncConnection.connect(conninfo, autocommit=True) as conn:
        row = await (
            await conn.execute(
                """SELECT current_database(), current_user, r.rolsuper, r.rolcreatedb,
                          r.rolcreaterole, r.rolreplication, r.rolbypassrls
                   FROM pg_roles r WHERE r.rolname = current_user"""
            )
        ).fetchone()
        database, user, *attributes = row
        checks.append(Check("런타임 DB 이름", database == RUNTIME_DB, database))
        checks.append(Check("관리자 속성 없음", not any(attributes), user))

        memberships = await (
            await conn.execute(
                """SELECT g.rolname FROM pg_auth_members m
                   JOIN pg_roles g ON g.oid = m.roleid
                   WHERE m.member = (SELECT oid FROM pg_roles WHERE rolname = current_user)"""
            )
        ).fetchall()
        names = ", ".join(name for (name,) in memberships)
        checks.append(Check("다른 역할 권한을 물려받지 않음", not memberships, names))

        for privilege in (*REQUIRED, *FORBIDDEN, *_newer_privileges(conn)):
            expected = privilege in REQUIRED
            # 거부돼야 하는 권한은 일부 열에만 부여된 경우도 찾는다.
            function = (
                "has_any_column_privilege"
                if not expected and privilege in COLUMN_PRIVILEGES
                else "has_table_privilege"
            )
            granted = await _scalar(
                conn, f"SELECT {function}(current_user, %s, %s)", (LEDGER_TABLE, privilege)
            )
            label = f"ai_run_ledger {privilege} {'허용' if expected else '거부'}"
            checks.append(Check(label, granted == expected))
        for privilege in ("CREATE", "TEMPORARY"):
            granted = await _scalar(
                conn,
                "SELECT has_database_privilege(current_user, current_database(), %s)",
                (privilege,),
            )
            checks.append(Check(f"런타임 DB {privilege} 거부", not granted))
        granted = await _scalar(
            conn, "SELECT has_schema_privilege(current_user, 'public', 'CREATE')"
        )
        checks.append(Check("public 스키마 CREATE 거부", not granted))

        checks.append(
            await _denied(
                conn,
                "실제 DELETE 거부",
                "DELETE FROM public.ai_run_ledger WHERE run_id = %s",
                (uuid4(),),
            )
        )
        checks.append(
            await _denied(
                conn,
                "실제 테이블 생성 거부",
                "CREATE TABLE public.hops_ai_privilege_check (id integer)",
                None,
            )
        )

        # RunLedger의 소유자 잠금과 같은 세션 잠금을 잡고 바로 놓는다.
        key = uuid4().int % (2**63)
        acquired = await _scalar(conn, "SELECT pg_try_advisory_lock(%s)", (key,))
        if acquired:
            await conn.execute("SELECT pg_advisory_unlock(%s)", (key,))
        checks.append(Check("세션 잠금 사용 가능", bool(acquired)))
    return checks


async def check_ledger_write(conninfo: str) -> list[Check]:
    """점검용 run 한 건으로 RunLedger 접수·시작·종료·재기동 조회를 확인한다."""
    from hops_ai.persistence.run_ledger import RunLedger

    run_id = uuid4()
    result = {"runId": str(run_id), "status": "SUCCEEDED", "check": "hops-ai-runtime-db"}
    try:
        async with RunLedger(conninfo) as ledger:
            created = (await ledger.accept(run_id, {"check": "hops-ai-runtime-db"})).created
            started = await ledger.start(run_id)
            await ledger.finish(run_id, "SUCCEEDED", result, 0)
        async with RunLedger(conninfo) as restarted:
            snapshot = await restarted.get(run_id)
    except Exception as exc:  # noqa: BLE001 - 점검 결과로만 보고한다.
        return [Check("RunLedger 접수·완료·재기동 조회", False, type(exc).__name__)]
    ok = created and started and snapshot is not None and snapshot.status == "SUCCEEDED"
    return [Check("RunLedger 접수·완료·재기동 조회", ok, str(run_id))]


async def check_business(conninfo: str, dbname: str) -> list[Check]:
    business = make_conninfo(conninfo, dbname=dbname)
    try:
        conn = await psycopg.AsyncConnection.connect(business, autocommit=True)
    except psycopg.OperationalError as exc:
        if "permission denied" in str(exc):
            return [Check(f"업무 DB {dbname} 접속 거부", True, "검색 연동 시 CONNECT 별도 부여")]
        return [Check(f"업무 DB {dbname} 접속", False, type(exc).__name__)]
    async with conn:
        table_privileges = ",".join((*TABLE_PRIVILEGES, *_newer_privileges(conn)))
        tables = await (
            await conn.execute(
                """SELECT n.nspname || '.' || c.relname FROM pg_class c
                   JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
                     AND n.nspname <> ALL(%s) AND n.nspname NOT LIKE 'pg_temp%%'
                     AND CASE WHEN c.relkind = 'S'
                              THEN has_sequence_privilege(current_user, c.oid, %s)
                              ELSE has_table_privilege(current_user, c.oid, %s)
                                   OR has_any_column_privilege(current_user, c.oid, %s) END
                   ORDER BY 1""",
                (
                    list(SYSTEM_SCHEMAS),
                    ",".join(SEQUENCE_PRIVILEGES),
                    table_privileges,
                    ",".join(COLUMN_PRIVILEGES),
                ),
            )
        ).fetchall()
        schemas = await (
            await conn.execute(
                """SELECT nspname FROM pg_namespace
                   WHERE nspname <> ALL(%s) AND nspname NOT LIKE 'pg_temp%%'
                     AND nspname NOT LIKE 'pg_toast%%'
                     AND has_schema_privilege(current_user, oid, 'CREATE')""",
                (list(SYSTEM_SCHEMAS),),
            )
        ).fetchall()
        knowledge = await _scalar(
            conn,
            """SELECT has_schema_privilege(current_user, 'hops_knowledge', 'USAGE')
               WHERE EXISTS (SELECT FROM pg_namespace WHERE nspname = 'hops_knowledge')""",
        )
    return [
        Check(
            f"업무 DB {dbname} 테이블·열·시퀀스 권한 없음",
            not tables,
            ", ".join(name for (name,) in tables[:5]) + (" 외" if len(tables) > 5 else ""),
        ),
        Check(f"업무 DB {dbname} 스키마 생성 거부", not schemas, ", ".join(n for (n,) in schemas)),
        # 검색 연동(#142) 전에는 hops_knowledge 권한도 없어야 한다. 연동 시 이 기대값을 바꾼다.
        Check("hops_knowledge 스키마 권한 없음", not knowledge),
    ]


def _newer_privileges(conn: psycopg.AsyncConnection) -> tuple[str, ...]:
    """서버 버전에 따라 추가로 거부돼야 하는 테이블 권한."""
    return ("MAINTAIN",) if conn.info.server_version >= MAINTAIN_SINCE else ()


async def _scalar(conn: psycopg.AsyncConnection, query: str, params=None):
    row = await (await conn.execute(query, params)).fetchone()
    return None if row is None else row[0]


async def _denied(conn: psycopg.AsyncConnection, name: str, query: str, params) -> Check:
    try:
        async with conn.transaction(force_rollback=True):
            await conn.execute(query, params)
    except psycopg.errors.InsufficientPrivilege:
        return Check(name, True)
    except psycopg.Error as exc:
        return Check(name, False, type(exc).__name__)
    return Check(name, False, "허용됨(되돌림)")


async def run(args: argparse.Namespace) -> list[Check]:
    conninfo = os.environ.get("HOPS_AI_RUNTIME_DSN", "").strip()
    if not conninfo:
        return [Check("HOPS_AI_RUNTIME_DSN 설정", False)]
    try:
        conninfo_to_dict(conninfo)
    except psycopg.ProgrammingError:
        return [Check("HOPS_AI_RUNTIME_DSN 형식", False)]
    try:
        checks = await check_runtime(conninfo)
    except psycopg.Error as exc:
        return [Check("런타임 DB 접속", False, type(exc).__name__)]
    if args.write_check:
        checks += await check_ledger_write(conninfo)
    if args.business_dbname:
        checks += await check_business(conninfo, args.business_dbname)
    return checks


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="AI 앱 계정의 런타임 DB 권한을 점검한다.")
    parser.add_argument("--business-dbname", help="같은 계정으로 권한이 없어야 하는 업무 DB 이름")
    parser.add_argument(
        "--write-check", action="store_true", help="점검용 run 한 건을 접수·완료해 쓰기 경로를 확인"
    )
    checks = asyncio.run(run(parser.parse_args(argv)))
    for check in checks:
        mark = "OK  " if check.ok else "FAIL"
        print(f"{mark} {check.name}" + (f" ({check.detail})" if check.detail else ""))
    failed = sum(not check.ok for check in checks)
    print(f"{len(checks) - failed}/{len(checks)} 통과")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
