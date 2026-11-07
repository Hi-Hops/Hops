"""지식 선순환 E2E 드라이버: 문의 A → 처리 → 해결 확인 → 사례 A 승인 → 문의 B·C.

실행 중인 로컬 Spring(+AI, PostgreSQL)에 실제 사용자 계정으로 공개 API를 불러 선순환을 한 번
끝까지 돌리고, 단계마다 PASS / FAIL / BLOCKED / INFO를 기록한다. 이 단일 시나리오의 통과는 일반
성능이나 서비스 통합 검증 완료가 아니다. 모델 종류와 판정 설정은 실행하는 사람이 밝히고, 내부 판단
프로필은 실행 입력에서 읽어 기록한다.

    # 모델을 부르지 않는 점검(로그인, DB, 시드)
    uv run python -m evaluation.flywheel.e2e --preflight

    # 모델을 부르는 실행. 시작한 AI 실행 수(--max-runs)를 넘기 전에 멈춘다.
    uv run python -m evaluation.flywheel.e2e --run --model-label real:gpt-6-luna \\
        --assessment off --max-runs 24

환경변수: HOPS_E2E_BASE_URL(기본 http://127.0.0.1:8080), HOPS_E2E_DB_DSN(읽기 전용 관찰용
PostgreSQL), HOPS_E2E_PASSWORD(시드 계정 로그인 비밀값. 문서와 기록에 남기지 않는다).
"""

import argparse
import json
import os
import subprocess
import sys
from collections.abc import Sequence
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from evaluation.curator.candidates import CHECK_ITEM, SYMPTOM, node_id
from evaluation.flywheel.bundle import Bundle, RoleEntry, load_bundle
from evaluation.flywheel.e2e_api import Api, ApiError, wait_until
from evaluation.flywheel.e2e_observer import Observer
from evaluation.harness.user_sim import reply_to

REPO = Path(__file__).resolve().parents[3]
PASS, FAIL, BLOCKED, INFO = "PASS", "FAIL", "BLOCKED", "INFO"
RUN_TIMEOUT_SECONDS = 240.0
CURATOR_TIMEOUT_SECONDS = 240.0
MAX_TURNS = 8


class BudgetExceeded(RuntimeError):
    pass


@dataclass
class Step:
    id: str
    title: str
    status: str
    detail: str = ""
    data: dict[str, Any] = field(default_factory=dict)


class Recorder:
    def __init__(self) -> None:
        self.steps: list[Step] = []

    def add(self, step_id: str, title: str, status: str, detail: str = "", **data: Any) -> Step:
        step = Step(step_id, title, status, detail, data)
        self.steps.append(step)
        print(
            f"[{status:7}] {step_id} {title}" + (f" - {detail}" if detail else ""), file=sys.stderr
        )
        return step

    def count(self, status: str) -> int:
        return sum(1 for step in self.steps if step.status == status)


@dataclass(frozen=True)
class Proposal:
    """사용자 모사가 읽는 질문 요약. 실제 질문 묶음(questionBundles)에서 만든다."""

    problemRef: str
    requestedFields: list[str]


@dataclass
class Outcome:
    inquiry_id: str
    end: str
    turns: int
    problem_id: str | None = None
    ticket_id: str | None = None
    messages: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class Context:
    api: Api
    observer: Observer
    bundle: Bundle
    recorder: Recorder
    password: str
    max_runs: int
    run_baseline: int
    # 대화록은 중간에 오류가 나도 보고서에 남도록 실행 중에 여기에 쌓는다.
    transcripts: dict[str, list[dict[str, Any]]] = field(default_factory=dict)

    def check_budget(self) -> None:
        used = self.observer.run_total() - self.run_baseline
        if used >= self.max_runs:
            raise BudgetExceeded(f"시작한 AI 실행 {used}개가 상한 {self.max_runs}개에 닿았다")

    def runs_used(self) -> int:
        return self.observer.run_total() - self.run_baseline


# 문의 대화 진행


def _messages(ctx: Context, user: str, inquiry_id: str) -> list[dict[str, Any]]:
    page = ctx.api.get(user, f"/inquiries/{inquiry_id}/messages", size=100)
    return sorted(page["items"], key=lambda item: item["sequenceNo"])


def drive_inquiry(ctx: Context, entry: RoleEntry) -> Outcome:
    """문의를 접수하고, AI 질문에 사용자 모사로 답하며 티켓·안내로 끝날 때까지 진행한다."""
    api, observer, user = ctx.api, ctx.observer, entry.requester
    ctx.check_budget()
    receipt = api.command(
        user, "/inquiries", {"content": entry.scenario.first_message, "reportedSystemId": None}
    )
    inquiry_id = receipt["resource"]["id"]
    answered, turns = 0, 0

    def finish(end: str, problem: dict | None = None) -> Outcome:
        ticket = (problem or {}).get("ticket") or {}
        return Outcome(
            inquiry_id,
            end,
            turns,
            (problem or {}).get("id"),
            ticket.get("id"),
            _messages(ctx, user, inquiry_id),
        )

    while True:
        result = api.wait_run_settled(user, inquiry_id, timeout=RUN_TIMEOUT_SECONDS)
        if result is None:
            return finish("TIMEOUT")
        run = result.get("currentRun")
        if run and run["status"] in ("FAILED", "TIMED_OUT"):
            return finish("RUN_FAILED")
        problems = result["problems"]
        if problems and all(p.get("ticket") for p in problems):
            return finish("TICKET", problems[0])
        if problems and all(p.get("guide") or p.get("ticket") for p in problems):
            return finish("GUIDE", problems[0])
        question = observer.latest_question(inquiry_id, answered)
        if question is None:
            return finish("NO_QUESTION", problems[0] if problems else None)
        if turns >= MAX_TURNS:
            return finish("MAX_TURNS", problems[0] if problems else None)
        ctx.check_budget()
        turns += 1
        proposals = [Proposal(b["problemId"], list(b["requestedFields"])) for b in question.bundles]
        reply = reply_to(entry.scenario, proposals, turns)  # type: ignore[arg-type]
        previous = (run or {}).get("id")
        api.command(
            user,
            f"/inquiries/{inquiry_id}/messages",
            {
                "expectedInquiryVersion": result["inquiryVersion"],
                "content": reply.text,
                "problemIds": [],
            },
        )
        answered = question.sequence_no
        wait_until(
            lambda previous=previous: _new_run(api, user, inquiry_id, previous),
            timeout=20.0,
            interval=1.0,
        )


def _new_run(api: Api, user: str, inquiry_id: str, previous: str | None) -> bool | None:
    run = api.get(user, f"/inquiries/{inquiry_id}").get("currentRun")
    return True if run and run["id"] != previous else None


# 사례 A 만들기: 담당자 처리 → 해결 확인 → 승인


def _ticket_detail(ctx: Context, ticket_id: str) -> dict[str, Any]:
    return ctx.api.get(ctx.bundle.assignee, f"/tickets/{ticket_id}")


def save_work_records(ctx: Context, ticket_id: str) -> str | None:
    """카드의 처리 기록 두 건을 담당자 계정으로 저장하고 해결 확인 요청 ID를 돌려준다."""
    rec, assignee = ctx.recorder, ctx.bundle.assignee
    progress, resolved = ctx.bundle.card["workLogs"]
    for sid, title, kind, log in (
        ("A4", "담당자가 진행 기록을 저장", "PROGRESS", progress),
        ("A5", "담당자가 검증된 해결 기록을 저장", "VERIFIED_RESOLUTION", resolved),
    ):
        revision = _ticket_detail(ctx, ticket_id)["ticketRevision"]
        try:
            ctx.api.command(
                assignee,
                f"/tickets/{ticket_id}/work-records",
                {
                    "expectedTicketRevision": revision,
                    "kind": kind,
                    "action": log["action"],
                    "verification": log.get("verification"),
                    "confirmedCause": log.get("confirmedCause"),
                    "appliesToSystemVersion": None,
                },
            )
            rec.add(sid, title, PASS)
        except ApiError as error:
            rec.add(sid, title, FAIL, str(error))
            return None
    request = _ticket_detail(ctx, ticket_id).get("activeResolutionRequest")
    return request["id"] if request else None


def wait_for_draft(ctx: Context, ticket_id: str) -> dict[str, Any] | None:
    return wait_until(
        lambda: ctx.observer.case_for_ticket(ticket_id),
        timeout=CURATOR_TIMEOUT_SECONDS,
        interval=3.0,
    )


def check_draft(ctx: Context, draft: dict[str, Any]) -> None:
    """Curator 초안을 정답(expectedCase)과 대조한다. 흐름을 막지 않는 점검이다."""
    gold = ctx.bundle.roles["A"].gold["expectedCase"]
    card = ctx.bundle.card
    resolved = card["workLogs"][1]
    candidates = json.loads(draft["relation_candidates"] or "[]") or []
    found = {(item["relation"], item["targetId"]) for item in candidates}
    wanted = {
        (
            link["relation"],
            node_id(SYMPTOM if link["relation"] == "ADDRESSES" else CHECK_ITEM, link["target"]),
        )
        for link in gold["expectedLinks"]
    }
    forbidden = {
        (link["relation"], node_id(CHECK_ITEM, link["target"])) for link in gold["forbiddenLinks"]
    }
    problems = []
    if draft["confirmed_cause"] != resolved["confirmedCause"]:
        problems.append("확인된 원인이 처리 기록과 다르다")
    if draft["access_level"] != "STAFF":
        problems.append(f"공개 범위가 STAFF가 아니다: {draft['access_level']}")
    if not wanted <= found:
        problems.append("기대한 관계 연결이 일부 없다")
    if found & forbidden:
        problems.append("금지한 관계 연결이 있다")
    ctx.recorder.add(
        "A7",
        "사례 초안이 정답(원인·공개 범위·관계 연결)과 맞음",
        FAIL if problems else PASS,
        "; ".join(problems),
        links=sorted(f"{relation}:{target}" for relation, target in found),
    )


# 문의 B·C 평가


def case_needles(case: dict[str, Any]) -> list[str]:
    """상담 근거에서 사례 A를 알아보는 값들.

    상담 근거는 사례 번호(`resolved_case.id`)를 담지 않는다. 승인된 사례는 새 지식 문서가 되어
    근거에 그 문서 id(`documentId`)·제목·발췌로 나온다. 그래서 문서 id(승인 뒤에만 있음)와 사례의
    제목·확인된 원인 문장을 함께 찾고, 사례 번호도 혹시 모를 경우를 위해 넣는다.
    """
    keys = ("document_id", "title", "confirmed_cause", "id")
    return [str(case[key]) for key in keys if case.get(key)]


def evaluate_followup(
    ctx: Context,
    step_prefix: str,
    role: str,
    outcome: Outcome,
    case: dict[str, Any],
    *,
    approved: bool,
) -> str | None:
    """B·C 문의 결과를 정답과 대조하고 실행에서 읽은 내부 판단 프로필을 돌려준다."""
    rec, observer = ctx.recorder, ctx.observer
    gold = ctx.bundle.roles[role].gold
    label = f"{role} 문의({'승인 후' if approved else '승인 전'})"
    rec.add(
        f"{step_prefix}.1",
        f"{label} 진행: 티켓 제안으로 끝남",
        PASS if outcome.end == "TICKET" else FAIL,
        f"끝 상태 {outcome.end}, 질문 {outcome.turns}회",
    )
    visible = " ".join(
        message["content"] for message in outcome.messages if message["sender"] == "AI"
    )
    leaked = [p for p in gold["employeeVisibleMustNotContain"] if p in visible]
    rec.add(
        f"{step_prefix}.2",
        f"{label}: 직원이 보는 AI 메시지에 사례 A 문장이 없음",
        FAIL if leaked else PASS,
        f"{len(leaked)}건 노출" if leaked else "",
    )
    internal = observer.uses_internal_judgement(outcome.inquiry_id)
    profile = {None: None, False: "DEFAULT", True: "INTERNAL_JUDGEMENT"}[internal]
    mentions = observer.mentions(outcome.inquiry_id, case_needles(case))
    in_evidence = mentions["related_knowledge"] or mentions["run_result"]
    evidence_total = observer.evidence_count(outcome.inquiry_id)
    if not approved:
        expected = "ABSENT"
    elif profile is None:
        expected = None
    else:
        expected = gold["caseA"][profile]["caseEvidence"]
    status, detail = INFO, f"프로필 {profile}, 근거에 나옴={in_evidence}"
    if expected == "ABSENT":
        status = FAIL if in_evidence else PASS
    elif expected == "CITED_AS_EVIDENCE":
        status = PASS if in_evidence else FAIL
    if expected is not None and evidence_total == 0:
        # 근거가 하나도 없으면 사례 A가 없는 것이 아니라 상담이 지식을 찾지 않은 것일 수 있다.
        # 그런 "없음"을 통과로 세면 검사가 비어 있게 된다.
        status = BLOCKED
        detail += (
            ", 저장된 상담 근거가 0건이라 사례 A 유무를 판정할 수 없다(검색 미수행 또는 결과 없음)"
        )
    rec.add(
        f"{step_prefix}.3",
        f"{label}: 사례 A가 근거에 나옴 여부가 기대({expected})와 같음",
        status,
        detail,
        mentions=mentions,
    )
    if role == "C":
        text = observer.request_text(outcome.inquiry_id)
        phrases = [case["confirmed_cause"], case["title"]]
        copied = [p for p in phrases if p and p in text]
        rec.add(
            f"{step_prefix}.4",
            f"{label}: 사례 A의 원인·제목이 표준 요청·티켓에 원인으로 옮겨지지 않음",
            FAIL if copied else PASS,
            "원문 일치는 대리 검사이며 뜻의 확정 여부는 대화록을 사람이 읽어 확인한다",
        )
    return profile


# 전체 흐름


def run_flywheel(ctx: Context) -> dict[str, Any]:
    rec, api, observer, bundle = ctx.recorder, ctx.api, ctx.observer, ctx.bundle
    role_a = bundle.roles["A"]
    outcome_a = drive_inquiry(ctx, role_a)
    rec.add(
        "A1",
        "문의 A 접수·대화가 티켓 생성으로 끝남",
        PASS if outcome_a.end == "TICKET" else FAIL,
        f"끝 상태 {outcome_a.end}, 질문 {outcome_a.turns}회",
    )
    transcripts = ctx.transcripts
    transcripts["A"] = outcome_a.messages
    if outcome_a.end != "TICKET" or not outcome_a.ticket_id:
        return {"transcripts": transcripts, "profile": None}
    ticket_id = outcome_a.ticket_id
    status = api.status_of(bundle.observer, f"/inquiries/{outcome_a.inquiry_id}")
    rec.add(
        "A2",
        "관찰자 계정은 남의 문의를 볼 수 없음",
        PASS if status in (403, 404) else FAIL,
        f"응답 {status}",
    )
    visible = [
        t for t in api.get(bundle.assignee, "/tickets", size=100)["items"] if t["id"] == ticket_id
    ]
    rec.add("A3", "담당자가 목록에서 티켓을 볼 수 있음", PASS if visible else FAIL)
    if not visible:
        return {"transcripts": transcripts, "profile": None}
    request_id = save_work_records(ctx, ticket_id)
    draft = wait_for_draft(ctx, ticket_id)
    if draft is None:
        runs = [
            r["run_status"] for r in observer.runs(outcome_a.inquiry_id) if r["task"] == "CURATION"
        ]
        rec.add(
            "A6",
            "Curator 사례 초안이 생성됨",
            FAIL,
            f"제한 시간 안에 초안이 없다. 실행 상태 {runs}",
        )
        return {"transcripts": transcripts, "profile": None}
    rec.add(
        "A6", "Curator 사례 초안이 생성됨", PASS, f"상태 {draft['case_status']}", caseId=draft["id"]
    )
    check_draft(ctx, draft)
    if request_id is None:
        rec.add("A8", "문의자 해결 확인 요청이 생김", FAIL)
        return {"transcripts": transcripts, "profile": None}
    try:
        api.command(
            role_a.requester,
            f"/resolution-requests/{request_id}/responses",
            {"expectedRequestRevision": 1, "decision": "RESOLVED"},
        )
        rec.add("A8", "문의자가 해결됨으로 확인", PASS)
    except ApiError as error:
        rec.add("A8", "문의자가 해결됨으로 확인", FAIL, str(error))
        return {"transcripts": transcripts, "profile": None}

    profiles: dict[str, str | None] = {}
    for role, prefix in (("B", "B0"), ("C", "C0")):
        outcome = drive_inquiry(ctx, bundle.roles[role])
        transcripts[f"{role}-before"] = outcome.messages
        profiles[f"{role}-before"] = evaluate_followup(
            ctx, prefix, role, outcome, draft, approved=False
        )

    # 초안을 읽은 뒤 B·C 문의에 수 분이 걸린다. 오래된 버전으로 승인하지 않게 직전에 다시 읽는다.
    version = observer.case_version(draft["id"])
    if version is None:
        version = draft["version_no"]
    elif version != draft["version_no"]:
        rec.add(
            "A8.1",
            "초안을 확인한 뒤 사례 버전이 바뀜",
            INFO,
            f"{draft['version_no']} → {version}. A7은 이전 버전 기준이다",
        )
    try:
        api.command(
            bundle.assignee,
            f"/cases/{draft['id']}/approvals",
            {"expectedCaseVersion": version},
        )
        rec.add("A9", "담당자가 사례 A를 승인", PASS)
    except ApiError as error:
        rec.add("A9", "담당자가 사례 A를 승인", FAIL, str(error))
        return {"transcripts": transcripts, "profile": None}
    document = observer.case_document(draft["id"])
    approved_doc = bool(document) and document["document_status"] == "APPROVED"
    rec.add("A10", "승인 사례가 검색 문서로 저장됨", PASS if approved_doc else FAIL, str(document))
    approved_case = {**draft, "document_id": (document or {}).get("document_id")}
    for store in ("VECTOR", "NEO4J"):
        try:
            api.command(
                bundle.assignee,
                f"/cases/{draft['id']}/index-checks",
                {"expectedCaseVersion": version, "store": store},
            )
            rec.add(
                f"A11.{store}",
                f"{store} 색인 확인 요청",
                INFO,
                "색인은 기본 비활성이라 PENDING일 수 있다",
            )
        except ApiError as error:
            rec.add(f"A11.{store}", f"{store} 색인 확인 요청", INFO, str(error))
    detail = api.get(bundle.assignee, f"/cases/{draft['id']}")
    publication = detail.get("publication") or {}
    rec.add(
        "A12",
        "승인 사례가 지식 검색에 공개됨(visibleToSearch)",
        PASS if publication.get("visibleToSearch") else FAIL,
        f"규칙 {publication.get('searchRule')}, 상태 {publication.get('state')}",
    )
    rows = observer.relation_case_rows(draft["id"])
    reflected = rows["symptoms"] > 0 and rows["checks"] > 0
    rec.add(
        "A13",
        "승인 사례의 관계가 관계 조회 테이블에 반영됨",
        PASS if reflected else BLOCKED,
        "" if reflected else "반영하는 코드가 없어 행이 0건이다(알려진 공백, 별도 이슈로 분리)",
        rows=rows,
    )
    for role, prefix in (("B", "B1"), ("C", "C1")):
        outcome = drive_inquiry(ctx, bundle.roles[role])
        transcripts[f"{role}-after"] = outcome.messages
        profiles[f"{role}-after"] = evaluate_followup(
            ctx, prefix, role, outcome, approved_case, approved=True
        )
    detected = {value for value in profiles.values() if value}
    return {"transcripts": transcripts, "profile": sorted(detected), "caseId": draft["id"]}


# 점검과 실행


def preflight(ctx: Context) -> None:
    rec = ctx.recorder
    users = [entry.requester for entry in ctx.bundle.roles.values()]
    users += [ctx.bundle.observer, ctx.bundle.assignee]
    for login_id in users:
        try:
            ctx.api.login(login_id, ctx.password)
            rec.add(f"P1.{login_id}", f"{login_id} 로그인", PASS)
        except ApiError as error:
            rec.add(f"P1.{login_id}", f"{login_id} 로그인", FAIL, str(error))
    counts = ctx.observer.counts()
    ok = counts["members"] >= 8 and counts["cases"] >= 6 and counts["symptoms"] >= 5
    rec.add("P2", "시드(직원·시드 사례·관계)가 들어 있음", PASS if ok else FAIL, str(counts))


def _runs_used_or_none(ctx: Context) -> int | None:
    """보고서를 쓰는 시점에 DB가 끊겨 있어도 보고서 자체는 남기려고 실패하면 None으로 둔다."""
    try:
        return ctx.runs_used()
    except Exception:
        return None


def _git_revision() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            capture_output=True,
            text=True,
            cwd=REPO,
            check=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="지식 선순환 E2E 드라이버")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true", help="모델을 부르지 않는 점검만 한다")
    mode.add_argument("--run", action="store_true", help="선순환을 끝까지 실행한다(모델 호출)")
    parser.add_argument("--max-runs", type=int, default=24, help="시작할 AI 실행 수 상한")
    parser.add_argument("--model-label", help="--run에 필수. 예: real:gpt-6-luna 또는 fake")
    parser.add_argument(
        "--assessment", choices=("on", "off"), help="--run에 필수. 충분성 판정 설정"
    )
    parser.add_argument("--note", default="", help="기록에 남길 메모(비밀값 금지)")
    parser.add_argument("--out", type=Path, help="결과 JSON 경로(기본 .local/flywheel-e2e/)")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    if args.run and not (args.model_label and args.assessment):
        print("--run에는 --model-label과 --assessment가 필요하다", file=sys.stderr)
        return 2
    for name in ("HOPS_E2E_DB_DSN", "HOPS_E2E_PASSWORD"):
        if not os.environ.get(name):
            print(f"환경변수 {name}가 필요하다", file=sys.stderr)
            return 2
    observer = Observer(os.environ["HOPS_E2E_DB_DSN"])
    try:
        return _execute(args, observer)
    finally:
        observer.close()


def _execute(args: argparse.Namespace, observer: Observer) -> int:
    ctx = Context(
        api=Api(os.environ.get("HOPS_E2E_BASE_URL", "http://127.0.0.1:8080")),
        observer=observer,
        bundle=load_bundle(),
        recorder=Recorder(),
        password=os.environ["HOPS_E2E_PASSWORD"],
        max_runs=args.max_runs,
        run_baseline=observer.run_total(),
    )
    started = datetime.now(UTC)
    extra: dict[str, Any] = {}
    try:
        preflight(ctx)
        if args.run and ctx.recorder.count(FAIL) == 0:
            extra = run_flywheel(ctx)
        elif args.run:
            ctx.recorder.add("RUN", "점검 실패로 실행하지 않음", FAIL)
    except BudgetExceeded as error:
        ctx.recorder.add("BUDGET", "AI 실행 수 상한", FAIL, str(error))
    except ApiError as error:
        ctx.recorder.add("ERROR", "예기치 않은 API 오류", FAIL, str(error))
    except Exception as error:  # 모델 비용이 든 실행의 기록을 어떤 오류로도 잃지 않는다.
        ctx.recorder.add(
            "ERROR", "예기치 않은 오류", FAIL, f"{type(error).__name__}: {error}"[:300]
        )
    report = {
        "bundleId": ctx.bundle.bundle_id,
        "mode": "run" if args.run else "preflight",
        "startedAt": started.isoformat(),
        "finishedAt": datetime.now(UTC).isoformat(),
        "gitRevision": _git_revision(),
        "declared": {
            "modelLabel": args.model_label,
            "assessment": args.assessment,
            "note": args.note,
        },
        "aiRunsStarted": _runs_used_or_none(ctx),
        "maxRuns": args.max_runs,
        "totals": {s: ctx.recorder.count(s) for s in (PASS, FAIL, BLOCKED, INFO)},
        "steps": [asdict(step) for step in ctx.recorder.steps],
        "transcripts": ctx.transcripts,
        **extra,
    }
    out = (
        args.out
        or REPO / ".local" / "flywheel-e2e" / f"{started:%Y%m%dT%H%M%SZ}-{report['mode']}.json"
    )
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    print(f"결과: {out}  {report['totals']}  AI 실행 {report['aiRunsStarted']}개", file=sys.stderr)
    return 1 if ctx.recorder.count(FAIL) else 0


if __name__ == "__main__":
    sys.exit(main())
