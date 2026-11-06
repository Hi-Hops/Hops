# ruff: noqa: E501, B007
"""#189 체크포인트 스파이크. 결과와 해석은 docs/ai/CHECKPOINT_SPIKE.md에 있다.

LangGraph Postgres 체크포인터가 "ask로 끝난 실행의 상태를 저장하고 다음 메시지의 새 실행이 같은
thread에서 이어받는" 방식(docs/ai/AGENT_ARCHITECTURE.md 10절)에 맞는지, 통과 조건 5개를 한 번씩 실행해
확인한다. 운영 코드가 아니고 pytest가 모으지 않는다(testpaths=tests). 모델·네트워크 호출은 없다.

설계 규칙(재개 때 체크포인트를 이어받을지 버릴지)은 이 파일의 `restore` 노드에 최소로 옮겼다. 검증 대상은
그 규칙의 정책이 아니라 LangGraph와 Postgres 체크포인터가 그 규칙을 받쳐 주는지다.

실행 (저장소 루트에서 `docker compose up -d postgres` 뒤, 의존성은 임시로만 더한다):

    cd ai && uv run --with langgraph-checkpoint-postgres python -m spikes.checkpoint_spike

DB는 로컬 개발 PostgreSQL의 hops_ai_runtime 안에 임시 스키마(checkpoint_spike)와 임시 역할(spike_app)을
만들고 끝나면 지운다. 접속 정보는 ai/.env의 HOPS_AI_RUNTIME_DSN을 쓰되 호스트만 localhost로 바꾼다
(SPIKE_ADMIN_DSN으로 덮어쓸 수 있다). 비밀번호는 출력하지 않는다.
"""

import asyncio
import hashlib
import logging
import secrets
import sys
import warnings
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any, TypedDict
from uuid import uuid4

import psycopg
from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from langgraph.graph import END, START, StateGraph
from psycopg.conninfo import conninfo_to_dict, make_conninfo

from hops_ai.persistence.run_ledger import RunLedger

AI_DIR = Path(__file__).resolve().parent.parent
SCHEMA = "checkpoint_spike"
ROLE = "spike_app"
SENTINEL = "SENTINEL-본문-9f3a"
DATA_TABLES = ("checkpoints", "checkpoint_blobs", "checkpoint_writes")
NODES = ["restore", "analyze", "retrieve", "assess", "route"]
REQUIRED = ["system", "taskOrScreen"]

results: list[tuple[str, str, bool, str]] = []


def check(cond: str, name: str, ok: bool, evidence: str) -> None:
    results.append((cond, name, ok, evidence))
    tag = "PASS" if ok else ("참고-미통과" if cond == "참고" else "FAIL")
    print(f"[{tag}] 조건{cond} {name}: {evidence}")


# -- 미니 그래프: 5절 판단 루프를 질문 이력 이어받기에 필요한 만큼만 옮긴 것 ------------------


class SpikeState(TypedDict, total=False):
    # 체크포인트에 남겨 이어받는 AI 내부 파생 상태. 메시지 본문은 두지 않는다(ID·revision·digest만).
    meta: dict[str, Any]
    analysis: dict[str, dict[str, str]]
    questions: list[dict[str, Any]]
    # 실행별 키. 새 실행마다 입력으로 초기화한다.
    runId: str
    calls: list[str]
    usage: dict[str, int]
    notes: dict[str, Any]
    outcome: dict[str, Any] | None


def digest(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()[:16]


def msg(message_id: str, revision: int, content: str, sender: str = "USER") -> dict[str, Any]:
    return {
        "id": message_id,
        "revision": revision,
        "sender": sender,
        "content": content,
        "digest": digest(content),
    }


def request(
    run_id: str,
    version: int,
    policy: int,
    messages: list[dict[str, Any]],
    asked: list | None = None,
) -> dict[str, Any]:
    return {
        "runId": run_id,
        "inquiryVersion": version,
        "policyRevision": policy,
        "messages": messages,
        "askedFromSpring": asked or [],
    }


def extract(content: str) -> dict[str, str]:
    """가짜 분석. '시스템=값', '화면=값' 표기만 읽는다. 모델을 부르지 않는다."""
    found: dict[str, str] = {}
    for token in content.split():
        for marker, field in (("시스템=", "system"), ("화면=", "taskOrScreen")):
            if token.startswith(marker):
                found[field] = token[len(marker) :]
    return found


async def enter(name: str, state: SpikeState, config: RunnableConfig) -> dict[str, Any]:
    hook = config["configurable"].get("hooks", {}).get(name)
    if hook is not None:
        await hook()
    return {
        "calls": [*state.get("calls", []), name],
        "usage": {"calls": state.get("usage", {}).get("calls", 0) + 1},
    }


async def restore(state: SpikeState, config: RunnableConfig) -> dict[str, Any]:
    """10절 규칙: 요청 스냅샷이 우선하고, 맞지 않는 체크포인트 값은 버린다."""
    req = config["configurable"]["request"]
    update = await enter("restore", state, config)
    notes: dict[str, Any] = {}
    meta = state.get("meta")
    analysis = dict(state.get("analysis") or {})
    questions = list(state.get("questions") or [])
    refs = {m["id"]: [m["revision"], m["digest"]] for m in req["messages"]}
    if meta is None:
        notes["start"] = "fresh"
    elif meta["policyRevision"] != req["policyRevision"] or any(
        message_id not in refs for message_id in meta["refs"]
    ):
        analysis, questions = {}, list(req["askedFromSpring"])
        notes["discarded"] = True
        notes["restoredFromSpring"] = bool(questions)
    else:
        for message_id, saved in meta["refs"].items():
            if refs[message_id] != saved:
                analysis.pop(message_id, None)
                notes.setdefault("dropped", []).append(message_id)
    update.update(
        meta={
            "inquiryVersion": req["inquiryVersion"],
            "policyRevision": req["policyRevision"],
            "refs": refs,
        },
        analysis=analysis,
        questions=questions,
        notes=notes,
    )
    return update


async def analyze(state: SpikeState, config: RunnableConfig) -> dict[str, Any]:
    update = await enter("analyze", state, config)
    messages = config["configurable"]["request"]["messages"]
    update["analysis"] = {m["id"]: extract(m["content"]) for m in messages if m["sender"] == "USER"}
    return update


async def retrieve(state: SpikeState, config: RunnableConfig) -> dict[str, Any]:
    return await enter("retrieve", state, config)


async def assess(state: SpikeState, config: RunnableConfig) -> dict[str, Any]:
    return await enter("assess", state, config)


async def route(state: SpikeState, config: RunnableConfig) -> dict[str, Any]:
    update = await enter("route", state, config)
    known: dict[str, str] = {}
    for fields in state["analysis"].values():
        known.update(fields)
    asked = {name for bundle in state["questions"] for name in bundle["fields"]}
    missing = [name for name in REQUIRED if name not in known]
    askable = [name for name in missing if name not in asked]
    questions = list(state["questions"])
    if not missing:
        update["outcome"] = {"action": "answer", "known": known}
    elif askable:
        bundle = {"bundleRef": f"b{len(questions) + 1}", "fields": askable[:1]}
        questions.append(bundle)
        update["outcome"] = {"action": "ask", "fields": bundle["fields"]}
    else:
        update["outcome"] = {"action": "handoff", "missing": missing}
    update["questions"] = questions
    return update


def build_graph(saver: AsyncPostgresSaver):
    graph = StateGraph(SpikeState)
    for name, fn in zip(NODES, (restore, analyze, retrieve, assess, route), strict=True):
        graph.add_node(name, fn)
    graph.add_edge(START, "restore")
    for left, right in zip(NODES, NODES[1:], strict=False):
        graph.add_edge(left, right)
    graph.add_edge("route", END)
    return graph.compile(checkpointer=saver)


class StaleRunError(Exception):
    """이미 다른 실행에 밀려난 실행이 체크포인트를 쓰려 했다."""


class GuardedSaver(AsyncPostgresSaver):
    """10절의 쓰기 보호(compare-and-set)를 라이브러리 바깥에서 덧댈 수 있는지 보는 래퍼.

    `current`는 접수 지점(Spring 입력 대체·RunLedger)이 정하는 '이 thread의 현재 실행'을 흉내 낸다.
    """

    current: dict[str, str] = {}

    def _guard(self, config: RunnableConfig) -> None:
        conf = config["configurable"]
        run_id = conf.get("guard_run_id")
        if run_id is not None and self.current.get(conf["thread_id"]) != run_id:
            raise StaleRunError(run_id)

    async def aput(self, config, checkpoint, metadata, new_versions):  # type: ignore[override]
        self._guard(config)
        return await super().aput(config, checkpoint, metadata, new_versions)

    async def aput_writes(self, config, writes, task_id, task_path=""):  # type: ignore[override]
        self._guard(config)
        return await super().aput_writes(config, writes, task_id, task_path)


def fresh_input(run_id: str) -> SpikeState:
    """새 실행의 입력. 실행별 키를 비워 이전 실행의 값이 새지 않게 한다."""
    return {"runId": run_id, "calls": [], "usage": {"calls": 0}, "notes": {}, "outcome": None}


def run_config(
    thread: str,
    req: dict[str, Any],
    hooks: dict[str, Callable[[], Awaitable[None]]] | None = None,
    guard: bool = False,
) -> RunnableConfig:
    conf: dict[str, Any] = {"thread_id": thread, "request": req, "hooks": hooks or {}}
    if guard:
        conf["guard_run_id"] = req["runId"]
    return {"configurable": conf}


async def invoke(graph, thread: str, req: dict[str, Any], **kwargs: Any) -> SpikeState:
    return await graph.ainvoke(fresh_input(req["runId"]), config=run_config(thread, req, **kwargs))


# -- DB 준비 ------------------------------------------------------------------------------


def admin_dsn() -> str:
    import os

    explicit = os.environ.get("SPIKE_ADMIN_DSN")
    if explicit:
        return explicit
    for line in (AI_DIR / ".env").read_text().splitlines():
        if line.startswith("HOPS_AI_RUNTIME_DSN="):
            value = line.split("=", 1)[1].strip().strip("\"'")
            params = conninfo_to_dict(value)
            params["host"] = "localhost"
            params.pop("hostaddr", None)
            return make_conninfo(**params)
    raise SystemExit("HOPS_AI_RUNTIME_DSN 또는 SPIKE_ADMIN_DSN이 필요하다")


def scoped(dsn: str, **overrides: str) -> str:
    return make_conninfo(dsn, options=f"-c search_path={SCHEMA}", **overrides)


async def scalar(conn: psycopg.AsyncConnection, sql: str, *args: Any) -> Any:
    cursor = await conn.execute(sql, args)
    row = await cursor.fetchone()
    return None if row is None else row[0]


async def count_rows(conn: psycopg.AsyncConnection, thread: str) -> dict[str, int]:
    return {
        table: await scalar(conn, f"SELECT count(*) FROM {table} WHERE thread_id = %s", thread)
        for table in DATA_TABLES
    }


async def latest_values(graph, thread: str) -> SpikeState:
    snapshot = await graph.aget_state({"configurable": {"thread_id": thread}})
    return snapshot.values


# -- 조건 1~2 -------------------------------------------------------------------------------


async def cond_1_2(graph, admin: psycopg.AsyncConnection) -> None:
    thread = f"inq-{uuid4()}"
    m1 = msg("m1", 1, f"결재 문서가 안 열려요 {SENTINEL}")
    out1 = await invoke(graph, thread, request(str(uuid4()), 1, 1, [m1]))
    rows = await count_rows(admin, thread)
    check(
        "1",
        "ask로 끝난 실행의 상태가 Postgres에 저장된다",
        out1["outcome"] == {"action": "ask", "fields": ["system"]}
        and rows["checkpoints"] > 0
        and rows["checkpoint_blobs"] > 0,
        f"outcome={out1['outcome']} rows={rows}",
    )

    # 질문 뒤 AI 메시지(m2)와 사용자 답(m3)이 저장돼 inquiryVersion이 1 → 3으로 오른다.
    m2 = msg("m2", 1, "어느 시스템인가요?", sender="AI")
    m3 = msg("m3", 1, "시스템=전자결재")
    out2 = await invoke(graph, thread, request(str(uuid4()), 3, 1, [m1, m2, m3]))
    check(
        "1",
        "같은 thread 재호출에서 노드가 다시 실행되고 최신 응답이 반영된다",
        out2["calls"] == NODES
        and out2["analysis"]["m3"] == {"system": "전자결재"}
        and out2["usage"]["calls"] == len(NODES),
        f"calls={out2['calls']} analysis.m3={out2['analysis']['m3']} usage={out2['usage']}",
    )
    asked = [bundle["fields"] for bundle in out2["questions"]]
    check(
        "1",
        "이전 질문 이력이 유지되고 이미 물은 필드를 다시 묻지 않는다",
        asked == [["system"], ["taskOrScreen"]] and out2["outcome"]["fields"] == ["taskOrScreen"],
        f"questions={asked} outcome={out2['outcome']}",
    )
    check(
        "2",
        "inquiryVersion이 올라도(1→3) 이력이 유지된다",
        out2["meta"]["inquiryVersion"] == 3 and len(out2["questions"]) == 2,
        f"meta.inquiryVersion={out2['meta']['inquiryVersion']}",
    )

    # 사용자 답(m5)으로 다시 호출한다. 실행별 키가 새 실행 기준인지도 본다.
    m4 = msg("m4", 1, "화면이 궁금하시군요", sender="AI")
    m5 = msg("m5", 1, "화면=결재함")
    out3 = await invoke(graph, thread, request(str(uuid4()), 5, 1, [m1, m2, m3, m4, m5]))
    check(
        "1",
        "세 번째 실행에서 답변으로 끝나고 이력 2건이 유지된다",
        out3["outcome"]["action"] == "answer"
        and len(out3["questions"]) == 2
        and out3["usage"]["calls"] == len(NODES),
        f"outcome={out3['outcome']} questions={len(out3['questions'])} usage={out3['usage']}",
    )

    # 메시지 정정: m1이 revision 2로 바뀐다(시스템 값을 정정). 파생 값은 정정된 메시지 기준으로만 다시 나온다.
    thread2 = f"inq-{uuid4()}"
    a1 = msg("m1", 1, "시스템=전자결재")
    await invoke(graph, thread2, request(str(uuid4()), 1, 1, [a1]))  # taskOrScreen을 묻는다
    a1_fixed = msg("m1", 2, "시스템=전자메일")
    a3 = msg("m3", 1, "화면=받은편지함")
    out4 = await invoke(graph, thread2, request(str(uuid4()), 4, 1, [a1_fixed, a3]))
    check(
        "2",
        "메시지 정정 뒤 정정된 메시지의 값으로 다시 계산되고 질문 이력은 유지된다",
        out4["notes"].get("dropped") == ["m1"]
        and out4["analysis"]["m1"] == {"system": "전자메일"}
        and out4["outcome"]["action"] == "answer"
        and len(out4["questions"]) == 1,
        f"notes={out4['notes']} analysis.m1={out4['analysis']['m1']}",
    )

    # policyRevision이 달라지면 체크포인트 전체를 버리고, 이력은 Spring이 준 값에서 복원한다.
    thread3 = f"inq-{uuid4()}"
    b1 = msg("m1", 1, "시스템=전자결재")
    await invoke(graph, thread3, request(str(uuid4()), 1, 1, [b1]))
    spring_history = [{"bundleRef": "b1", "fields": ["taskOrScreen"]}]
    out5 = await invoke(graph, thread3, request(str(uuid4()), 3, 2, [b1], spring_history))
    check(
        "2",
        "policyRevision 불일치 시 체크포인트를 버리고 Spring 이력에서 복원해 같은 질문을 반복하지 않는다",
        out5["notes"] == {"discarded": True, "restoredFromSpring": True}
        and out5["outcome"]["action"] == "handoff",
        f"notes={out5['notes']} outcome={out5['outcome']}",
    )
    # 같은 상황에서 Spring 이력이 없으면 같은 질문을 다시 한다(복원 근거 없음 → 경로 B가 필요한 이유).
    thread4 = f"inq-{uuid4()}"
    await invoke(graph, thread4, request(str(uuid4()), 1, 1, [b1]))
    out6 = await invoke(graph, thread4, request(str(uuid4()), 3, 2, [b1]))
    check(
        "2",
        "(대조) Spring 이력이 없으면 무효화 뒤 같은 질문을 반복한다",
        out6["outcome"] == {"action": "ask", "fields": ["taskOrScreen"]},
        f"outcome={out6['outcome']}",
    )


# -- 조건 3 -------------------------------------------------------------------------------


async def cond_3(
    plain_graph,
    guarded_graph,
    guarded_saver: GuardedSaver,
    admin: psycopg.AsyncConnection,
    ledger_dsn: str,
) -> None:
    # (a) 같은 runId 재접수는 RunLedger가 막는다. 그래프를 한 번만 부른다.
    thread = f"inq-{uuid4()}"
    run_id = uuid4()
    invoked = 0
    async with RunLedger(ledger_dsn) as ledger:
        for _ in range(2):
            accepted = await ledger.accept(run_id, {"inquiryVersion": 1})
            if accepted.created:
                invoked += 1
                await invoke(plain_graph, thread, request(str(run_id), 1, 1, [msg("m1", 1, "x")]))
        again = await ledger.accept(run_id, {"inquiryVersion": 1})
    check(
        "3",
        "같은 runId 재접수는 RunLedger가 막아 그래프가 한 번만 실행된다",
        invoked == 1 and again.created is False,
        f"graph 실행 {invoked}회, 세 번째 접수 created={again.created}",
    )

    # (b) 취소·기한 초과로 중간에 멈춘 실행 뒤 새 실행이 정상 시작한다.
    for label, stopper in (("취소로", "cancel"), ("기한 초과로", "timeout")):
        thread = f"inq-{uuid4()}"
        m1 = msg("m1", 1, "시스템=전자결재")
        await invoke(plain_graph, thread, request(str(uuid4()), 1, 1, [m1]))  # 질문 1건 저장

        started = asyncio.Event()

        async def stall(started: asyncio.Event = started) -> None:
            started.set()
            await asyncio.Event().wait()

        slow = asyncio.create_task(
            invoke(
                plain_graph,
                thread,
                request(str(uuid4()), 3, 1, [m1, msg("m3", 1, "x")]),
                hooks={"retrieve": stall},
            )
        )
        await started.wait()
        if stopper == "cancel":
            slow.cancel()
            try:
                await slow
            except asyncio.CancelledError:
                pass
        else:
            try:
                await asyncio.wait_for(slow, timeout=0.2)
            except TimeoutError:
                pass
        snapshot = await plain_graph.aget_state({"configurable": {"thread_id": thread}})
        pending = tuple(snapshot.next)
        m3 = msg("m3", 1, "화면=결재함")
        out = await invoke(plain_graph, thread, request(str(uuid4()), 3, 1, [m1, m3]))
        check(
            "3",
            f"{label} 멈춘 실행 뒤 새 실행이 처음부터 다시 돌고 실행별 키가 새로 시작한다",
            out["calls"] == NODES
            and out["usage"]["calls"] == len(NODES)
            and out["outcome"]["action"] == "answer"
            and len(out["questions"]) == 1,
            f"멈춘 시점 대기 노드={pending} 새 실행 calls={out['calls']} usage={out['usage']}",
        )

    # (c) 취소보다 늦게 끝나는 이전 실행이 최신 체크포인트를 덮어쓰는가 — 보호 없음 / 보호 있음.
    for label, graph, guard in (
        ("보호 없음", plain_graph, False),
        ("보호 있음", guarded_graph, True),
    ):
        thread = f"inq-{uuid4()}"
        old_run, new_run = str(uuid4()), str(uuid4())
        m1 = msg("m1", 1, "시스템=전자결재")
        reached, release = asyncio.Event(), asyncio.Event()

        async def hold(reached: asyncio.Event = reached, release: asyncio.Event = release) -> None:
            reached.set()
            await release.wait()

        GuardedSaver.current[thread] = old_run
        old = asyncio.create_task(
            invoke(graph, thread, request(old_run, 1, 1, [m1]), hooks={"assess": hold}, guard=guard)
        )
        await reached.wait()
        GuardedSaver.current[thread] = new_run  # 새 입력이 이전 실행을 SUPERSEDED로 만든다
        m3 = msg("m3", 1, "화면=결재함")
        await invoke(graph, thread, request(new_run, 3, 1, [m1, m3]), guard=guard)
        release.set()
        stale_error = None
        try:
            await old
        except StaleRunError as error:
            stale_error = error
        latest = await latest_values(graph, thread)
        overwritten = latest["runId"] == old_run or latest["meta"]["inquiryVersion"] == 1
        if guard:
            check(
                "3",
                "쓰기 보호(래퍼)가 있으면 늦게 끝난 이전 실행이 최신 체크포인트를 덮어쓰지 못한다",
                not overwritten and stale_error is not None,
                f"최신 runId={'새 실행' if latest['runId'] == new_run else '이전 실행'} "
                f"inquiryVersion={latest['meta']['inquiryVersion']} 이전 실행 중단={stale_error is not None}",
            )
        else:
            check(
                "3",
                "(대조) 쓰기 보호가 없으면 늦게 끝난 이전 실행이 최신 체크포인트를 덮어쓴다",
                overwritten,
                f"최신 runId={'새 실행' if latest['runId'] == new_run else '이전 실행'} "
                f"inquiryVersion={latest['meta']['inquiryVersion']} (덮어쓰면 재개가 낡은 상태를 읽는다)",
            )
    _ = guarded_saver, admin


# -- 조건 4 -------------------------------------------------------------------------------


async def cond_4(
    graph,
    app_saver: AsyncPostgresSaver,
    admin: psycopg.AsyncConnection,
    app_dsn: str,
    hops_dsn: str,
) -> None:
    thread_old, thread_new = f"inq-{uuid4()}", f"inq-{uuid4()}"
    for thread in (thread_old, thread_new):
        await invoke(
            graph,
            thread,
            request(str(uuid4()), 1, 1, [msg("m1", 1, f"시스템=전자결재 {SENTINEL}")]),
        )

    # 본문 복제 금지: 메시지 본문(SENTINEL)이 체크포인트 어느 표에도 없어야 한다.
    needle = SENTINEL.encode()
    hits = {
        "checkpoints": await scalar(
            admin,
            "SELECT count(*) FROM checkpoints WHERE checkpoint::text LIKE %s OR metadata::text LIKE %s",
            f"%{SENTINEL}%",
            f"%{SENTINEL}%",
        ),
        "checkpoint_blobs": await scalar(
            admin,
            "SELECT count(*) FROM checkpoint_blobs WHERE position(%s::bytea in blob) > 0",
            needle,
        ),
        "checkpoint_writes": await scalar(
            admin,
            "SELECT count(*) FROM checkpoint_writes WHERE position(%s::bytea in blob) > 0",
            needle,
        ),
    }
    check(
        "4",
        "메시지 본문이 체크포인트 표에 복제되지 않는다(ID·digest만)",
        sum(hits.values()) == 0,
        f"본문 발견 건수={hits}",
    )

    # 보관 기간: 라이브러리에 TTL이 없다. 마지막 체크포인트 시각(checkpoint->>'ts') 기준으로 직접 지운다.
    await admin.execute(
        "UPDATE checkpoints SET checkpoint = jsonb_set(checkpoint, '{ts}', to_jsonb(%s::text)) WHERE thread_id = %s",
        ("2020-01-01T00:00:00+00:00", thread_old),
    )
    async with await psycopg.AsyncConnection.connect(app_dsn, autocommit=True) as app:
        deleted = {}
        for table in DATA_TABLES[::-1]:
            cursor = await app.execute(
                f"DELETE FROM {table} WHERE thread_id IN (SELECT thread_id FROM checkpoints "
                "GROUP BY thread_id HAVING max((checkpoint->>'ts')::timestamptz) < now() - interval '24 hours')"
            )
            deleted[table] = cursor.rowcount
    check(
        "4",
        "보관 기간(최소 24시간)이 지난 thread만 앱 계정으로 지울 수 있다",
        sum(deleted.values()) > 0
        and sum((await count_rows(admin, thread_old)).values()) == 0
        and (await count_rows(admin, thread_new))["checkpoints"] > 0,
        f"삭제 행={deleted} 남은 thread 행={await count_rows(admin, thread_new)}",
    )
    # 문의 종료 시 삭제.
    await app_saver.adelete_thread(thread_new)
    check(
        "4",
        "문의 종료 시 thread 전체를 삭제할 수 있다(adelete_thread)",
        sum((await count_rows(admin, thread_new)).values()) == 0,
        f"삭제 뒤 행={await count_rows(admin, thread_new)}",
    )

    # 접근 권한: 앱 계정은 체크포인트 표 밖을 읽지 못하고 표를 만들지 못한다.
    denied = {}
    async with await psycopg.AsyncConnection.connect(app_dsn, autocommit=True) as app:
        for label, sql in (
            ("runtime DB의 ai_run_ledger 조회", "SELECT 1 FROM public.ai_run_ledger LIMIT 1"),
            ("스키마에 표 생성", "CREATE TABLE spike_forbidden (id int)"),
        ):
            try:
                await app.execute(sql)
                denied[label] = False
            except psycopg.errors.InsufficientPrivilege:
                denied[label] = True
    try:
        async with await psycopg.AsyncConnection.connect(hops_dsn, autocommit=True) as business:
            await business.execute("SELECT 1 FROM inquiry LIMIT 1")
        denied["업무 DB hops의 inquiry 조회"] = False
    except psycopg.errors.InsufficientPrivilege:
        denied["업무 DB hops의 inquiry 조회"] = True
    check(
        "4",
        "앱 계정은 체크포인트 표 밖을 읽거나 표를 만들 수 없다",
        all(denied.values()),
        f"거부 여부={denied}",
    )


# -- 실제 State 직렬화 --------------------------------------------------------------------------


def real_state_serialization() -> None:
    sys.path.insert(0, str(AI_DIR))
    from hops_ai.supervisor.state import create_initial_state
    from tests.doubles.run_requests import inquiry_request

    state = create_initial_state(inquiry_request())
    serde = JsonPlusSerializer()
    records: list[logging.LogRecord] = []

    class Capture(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            records.append(record)

    root = logging.getLogger()
    handler = Capture(level=logging.WARNING)
    root.addHandler(handler)
    try:
        with warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter("always")
            kind, blob = serde.dumps_typed(state)
            back = serde.loads_typed((kind, blob))
    finally:
        root.removeHandler(handler)
    same_input = back["input"] == state["input"] if isinstance(back, dict) else None
    check(
        "참고",
        "실제 SupervisorState(AiRunRequest 포함)를 체크포인트 직렬화기로 왕복할 수 있다",
        isinstance(back, dict) and same_input is True and not caught and not records,
        f"형식={kind} 크기={len(blob)}B input 동일={same_input} 경고={len(caught)} 로그경고={len(records)}"
        + "".join(f" [{record.getMessage()[:120]}]" for record in records[:2]),
    )


# -- 실행 ---------------------------------------------------------------------------------------


async def main() -> int:
    admin_url = admin_dsn()
    app_password = secrets.token_urlsafe(24)
    async with await psycopg.AsyncConnection.connect(admin_url, autocommit=True) as admin:
        await admin.execute(f"DROP SCHEMA IF EXISTS {SCHEMA} CASCADE")
        if await scalar(admin, "SELECT 1 FROM pg_roles WHERE rolname = %s", ROLE):
            await admin.execute(f"DROP OWNED BY {ROLE}")
        await admin.execute(f"DROP ROLE IF EXISTS {ROLE}")
        db = await scalar(admin, "SELECT current_database()")
        await admin.execute(f"CREATE SCHEMA {SCHEMA}")
        await admin.execute(
            f"CREATE ROLE {ROLE} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '{app_password}'"
        )
        await admin.execute(f"GRANT CONNECT ON DATABASE {db} TO {ROLE}")
        await admin.execute(f"GRANT USAGE ON SCHEMA {SCHEMA} TO {ROLE}")
        await admin.execute(f"SET search_path = {SCHEMA}")
        for name in ("001_run_ledger.sql", "002_run_owner.sql"):
            await admin.execute((AI_DIR / "migrations" / name).read_text())

        scoped_admin = scoped(admin_url)
        app_url = scoped(admin_url, user=ROLE, password=app_password)
        hops_url = make_conninfo(admin_url, user=ROLE, password=app_password, dbname="hops")
        try:
            # 스키마 준비는 관리자(마이그레이터 역할)가 한다. 앱 계정은 표를 만들 수 없어야 한다.
            async with AsyncPostgresSaver.from_conn_string(scoped_admin) as migrator:
                await migrator.setup()
            await admin.execute(
                f"GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA {SCHEMA} TO {ROLE}"
            )

            async with (
                AsyncPostgresSaver.from_conn_string(app_url) as app_saver,
                GuardedSaver.from_conn_string(app_url) as guarded_saver,
            ):
                plain_graph = build_graph(app_saver)
                guarded_graph = build_graph(guarded_saver)
                async with await psycopg.AsyncConnection.connect(
                    scoped_admin, autocommit=True
                ) as scoped_conn:
                    await cond_1_2(plain_graph, scoped_conn)
                    await cond_3(
                        plain_graph, guarded_graph, guarded_saver, scoped_conn, scoped_admin
                    )
                    await cond_4(plain_graph, app_saver, scoped_conn, app_url, hops_url)
            real_state_serialization()
        finally:
            await admin.execute(f"DROP SCHEMA IF EXISTS {SCHEMA} CASCADE")
            await admin.execute(f"DROP OWNED BY {ROLE}")
            await admin.execute(f"DROP ROLE IF EXISTS {ROLE}")

    failed = [item for item in results if not item[2] and item[0] != "참고"]
    print(f"\n검사 {len(results)}건 중 통과 {len(results) - len(failed)}건, 실패 {len(failed)}건")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
