"""선순환 E2E 드라이버의 논리 검사. 서버·DB·모델 없이 대역으로 진행·판정·상한을 확인한다.

실제 Spring·PostgreSQL·AI·모델 동작은 검증하지 않는다. 그것은 실제 스택에 돌린 기록이 맡는다.
"""

import json
from collections.abc import Sequence
from pathlib import Path
from typing import Any

import httpx
import pytest

from evaluation.curator.candidates import CHECK_ITEM, SYMPTOM, node_id
from evaluation.flywheel import e2e
from evaluation.flywheel.bundle import load_bundle
from evaluation.flywheel.e2e import (
    BLOCKED,
    FAIL,
    INFO,
    PASS,
    BudgetExceeded,
    Context,
    Outcome,
    Recorder,
    case_needles,
    check_draft,
    drive_inquiry,
    evaluate_followup,
)
from evaluation.flywheel.e2e_api import Api, ApiError, wait_until
from evaluation.flywheel.e2e_observer import QuestionMessage, like_patterns

BUNDLE = load_bundle()
CASE_ID = "11111111-1111-4111-8111-111111111111"
CARD_CAUSE = BUNDLE.card["workLogs"][1]["confirmedCause"]
CARD_TITLE = BUNDLE.card["expectedCase"]["title"]


# wait_until, Api


def test_wait_until_returns_the_first_value_and_gives_up_on_time() -> None:
    now = [0.0]

    def clock() -> float:
        return now[0]

    def sleep(seconds: float) -> None:
        now[0] += seconds

    calls = iter([None, None, "ok"])
    assert wait_until(lambda: next(calls), timeout=10, sleep=sleep, clock=clock) == "ok"
    assert wait_until(lambda: None, timeout=3, interval=1, sleep=sleep, clock=clock) is None


def _api(handler) -> Api:
    client = httpx.Client(base_url="http://spring", transport=httpx.MockTransport(handler))
    return Api("http://spring", client=client, sleep=lambda _: None)


def test_command_sends_the_same_request_id_in_header_and_body_and_waits_for_pending() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/v2/auth/login":
            return httpx.Response(200, json={"accessToken": "t", "user": {}})
        if request.method == "POST":
            body = json.loads(request.content)
            seen["header"] = request.headers["Idempotency-Key"]
            seen["body"] = body["requestId"]
            return httpx.Response(
                202, json={"state": "PENDING", "operationId": "op-1", "resource": None}
            )
        return httpx.Response(
            200,
            json={"state": "SUCCEEDED", "operationId": "op-1", "resource": {"id": "r-1"}},
        )

    api = _api(handler)
    api.login("u", "pw")

    receipt = api.command("u", "/inquiries", {"content": "x"})

    assert seen["header"] == seen["body"]
    assert receipt["resource"]["id"] == "r-1"


def test_rejected_command_is_an_error_not_a_success() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/login"):
            return httpx.Response(200, json={"accessToken": "t", "user": {}})
        return httpx.Response(
            200, json={"state": "REJECTED", "operationId": "o", "resource": None, "error": "x"}
        )

    api = _api(handler)
    api.login("u", "pw")

    with pytest.raises(ApiError, match="REJECTED"):
        api.command("u", "/cases/x/approvals", {})


def test_failed_login_error_never_contains_the_password() -> None:
    api = _api(lambda request: httpx.Response(401, text="bad credentials secret-password"))

    with pytest.raises(ApiError) as error:
        api.login("u", "secret-password")

    assert "secret-password" not in str(error.value)


# 문의 진행 (대역)


class FakeObserver:
    def __init__(self, questions: list[QuestionMessage], runs: list[int] | None = None) -> None:
        self.questions = list(questions)
        self.runs = runs or [0]
        self.profile: bool | None = False
        self.mentions_result = {
            "related_knowledge": False,
            "run_result": False,
            "employee_message": False,
        }
        self.text = ""
        self.evidence_total = 3
        self.needles: list[str] = []

    def latest_question(self, inquiry_id: str, after_sequence: int) -> QuestionMessage | None:
        return self.questions.pop(0) if self.questions else None

    def run_total(self) -> int:
        return self.runs[0]

    def uses_internal_judgement(self, inquiry_id: str) -> bool | None:
        return self.profile

    def mentions(self, inquiry_id: str, needles: Sequence[str]) -> dict[str, bool]:
        self.needles = list(needles)
        return self.mentions_result

    def request_text(self, inquiry_id: str) -> str:
        return self.text

    def evidence_count(self, inquiry_id: str) -> int:
        return self.evidence_total


class FakeApi:
    def __init__(self, results: list[dict[str, Any]]) -> None:
        self.results = list(results)
        self.sent: list[dict[str, Any]] = []

    def command(self, user: str, path: str, body: dict[str, Any], **_: Any) -> dict:
        self.sent.append({"path": path, **body})
        return {"state": "SUCCEEDED", "resource": {"id": "inq-1"}}

    def wait_run_settled(self, user: str, inquiry_id: str, **_: Any) -> dict | None:
        return self.results.pop(0) if self.results else None

    def get(self, user: str, path: str, **_: Any) -> Any:
        if path.endswith("/messages"):
            return {"items": [{"sequenceNo": 1, "sender": "USER", "content": "q"}]}
        return {"currentRun": {"id": "run-new", "status": "SUCCEEDED"}}


def _ctx(api: Any, observer: Any, max_runs: int = 10) -> Context:
    return Context(
        api=api,
        observer=observer,
        bundle=BUNDLE,
        recorder=Recorder(),
        password="x",
        max_runs=max_runs,
        run_baseline=0,
    )


def _result(problem: dict[str, Any], run_id: str = "run-1") -> dict[str, Any]:
    return {
        "inquiryVersion": 1,
        "currentRun": {"id": run_id, "status": "SUCCEEDED"},
        "problems": [problem],
    }


def test_drive_answers_the_asked_field_and_ends_when_a_ticket_exists() -> None:
    entry = BUNDLE.roles["B"]
    question = QuestionMessage(
        2,
        "어떤 조건으로 조회하셨나요?",
        [{"problemId": "p-1", "bundleRef": "b1", "requestedFields": ["check-ea-filter-condition"]}],
    )
    api = FakeApi([_result({"id": "p-1"}), _result({"id": "p-1", "ticket": {"id": "t-1"}})])
    ctx = _ctx(api, FakeObserver([question]))

    outcome = drive_inquiry(ctx, entry)

    assert outcome.end == "TICKET" and outcome.ticket_id == "t-1" and outcome.turns == 1
    reply = api.sent[1]
    assert reply["path"] == "/inquiries/inq-1/messages"
    assert reply["content"] == entry.scenario.check_answers["check-ea-filter-condition"]
    assert reply["expectedInquiryVersion"] == 1


def test_drive_stops_when_no_question_and_no_ticket() -> None:
    api = FakeApi([_result({"id": "p-1"})])

    outcome = drive_inquiry(_ctx(api, FakeObserver([])), BUNDLE.roles["B"])

    assert outcome.end == "NO_QUESTION"


def test_drive_reports_a_failed_run_and_a_timeout() -> None:
    failed = FakeApi(
        [{"inquiryVersion": 1, "currentRun": {"id": "r", "status": "FAILED"}, "problems": []}]
    )
    assert drive_inquiry(_ctx(failed, FakeObserver([])), BUNDLE.roles["B"]).end == "RUN_FAILED"
    assert drive_inquiry(_ctx(FakeApi([]), FakeObserver([])), BUNDLE.roles["B"]).end == "TIMEOUT"


def test_budget_stops_before_a_new_inquiry_starts() -> None:
    api = FakeApi([])
    ctx = _ctx(api, FakeObserver([], runs=[5]), max_runs=5)

    with pytest.raises(BudgetExceeded):
        drive_inquiry(ctx, BUNDLE.roles["B"])

    assert api.sent == []


def test_budget_stops_before_sending_an_answer() -> None:
    question = QuestionMessage(
        2, "q", [{"problemId": "p", "bundleRef": "b", "requestedFields": ["system"]}]
    )
    observer = FakeObserver([question])
    totals = iter([0, 1, 1, 1])  # 접수 전에는 여유가 있고, 답을 보내기 전에는 상한에 닿는다
    observer.run_total = lambda: next(totals)  # type: ignore[method-assign]
    api = FakeApi([_result({"id": "p"})])

    with pytest.raises(BudgetExceeded):
        drive_inquiry(_ctx(api, observer, max_runs=1), BUNDLE.roles["B"])

    assert len(api.sent) == 1  # 문의 접수만 나가고 답은 나가지 않았다


# 판정


def _outcome(end: str = "TICKET", ai_text: str = "확인해 보겠습니다.") -> Outcome:
    return Outcome(
        "inq-1",
        end,
        1,
        "p-1",
        "t-1",
        [{"sequenceNo": 1, "sender": "AI", "content": ai_text}],
    )


def _case() -> dict[str, Any]:
    return {"id": CASE_ID, "confirmed_cause": CARD_CAUSE, "title": CARD_TITLE}


def _statuses(ctx: Context) -> dict[str, str]:
    return {step.id: step.status for step in ctx.recorder.steps}


def _evaluate(
    role: str,
    *,
    approved: bool,
    profile: bool | None,
    found: bool,
    text: str = "",
    ai_text: str = "ok",
    evidence_total: int = 3,
):
    observer = FakeObserver([])
    observer.evidence_total = evidence_total
    observer.profile = profile
    observer.mentions_result = {
        "related_knowledge": found,
        "run_result": False,
        "employee_message": False,
    }
    observer.text = text
    ctx = _ctx(FakeApi([]), observer)
    detected = evaluate_followup(
        ctx, "X", role, _outcome(ai_text=ai_text), _case(), approved=approved
    )
    return detected, _statuses(ctx)


def test_before_approval_the_draft_must_not_appear_in_evidence() -> None:
    _, clean = _evaluate("B", approved=False, profile=True, found=False)
    _, leaked = _evaluate("B", approved=False, profile=True, found=True)

    assert clean["X.3"] == PASS
    assert leaked["X.3"] == FAIL


def test_default_profile_must_hide_the_staff_case_after_approval() -> None:
    profile, clean = _evaluate("B", approved=True, profile=False, found=False)
    _, leaked = _evaluate("B", approved=True, profile=False, found=True)

    assert profile == "DEFAULT"
    assert clean["X.3"] == PASS
    assert leaked["X.3"] == FAIL


def test_internal_profile_requires_b_to_cite_the_case_as_evidence() -> None:
    profile, cited = _evaluate("B", approved=True, profile=True, found=True)
    _, missing = _evaluate("B", approved=True, profile=True, found=False)

    assert profile == "INTERNAL_JUDGEMENT"
    assert cited["X.3"] == PASS
    assert missing["X.3"] == FAIL


@pytest.mark.parametrize("approved", [False, True])
def test_absence_without_any_stored_evidence_is_not_a_pass(approved: bool) -> None:
    """검색을 하지 않아 근거가 0건이면 '사례 A가 없음'은 통과가 아니라 판정 불가다."""
    _, statuses = _evaluate("B", approved=approved, profile=False, found=False, evidence_total=0)

    assert statuses["X.3"] == BLOCKED


def test_internal_profile_citation_is_blocked_not_failed_when_nothing_was_searched() -> None:
    _, statuses = _evaluate("B", approved=True, profile=True, found=False, evidence_total=0)

    assert statuses["X.3"] == BLOCKED


def test_unknown_profile_is_not_judged() -> None:
    profile, statuses = _evaluate("B", approved=True, profile=None, found=True)

    assert profile is None
    assert statuses["X.3"] == INFO


def test_case_sentence_in_the_employee_message_is_a_failure() -> None:
    _, statuses = _evaluate("B", approved=True, profile=False, found=False, ai_text=CARD_TITLE)

    assert statuses["X.2"] == FAIL


def test_a_follow_up_that_does_not_end_in_a_ticket_fails() -> None:
    ctx = _ctx(FakeApi([]), FakeObserver([]))

    evaluate_followup(ctx, "X", "B", _outcome(end="GUIDE"), _case(), approved=True)

    assert _statuses(ctx)["X.1"] == FAIL


def test_decoy_must_not_copy_the_case_cause_into_the_request() -> None:
    _, copied = _evaluate("C", approved=True, profile=True, found=True, text=CARD_CAUSE)
    _, clean = _evaluate("C", approved=True, profile=True, found=True, text="열람 권한 확인 필요")

    assert copied["X.4"] == FAIL
    assert clean["X.4"] == PASS


# Curator 초안 점검


def _draft(cause: str, access: str = "STAFF", links: list | None = None) -> dict[str, Any]:
    gold = BUNDLE.roles["A"].gold["expectedCase"]["expectedLinks"]
    chosen = (
        links
        if links is not None
        else [
            {
                "relation": link["relation"],
                "targetId": node_id(
                    SYMPTOM if link["relation"] == "ADDRESSES" else CHECK_ITEM, link["target"]
                ),
            }
            for link in gold
        ]
    )
    return {
        "confirmed_cause": cause,
        "access_level": access,
        "relation_candidates": json.dumps(chosen),
    }


def test_draft_check_passes_for_the_expected_cause_scope_and_links() -> None:
    ctx = _ctx(FakeApi([]), FakeObserver([]))

    check_draft(ctx, _draft(CARD_CAUSE))

    assert _statuses(ctx)["A7"] == PASS


@pytest.mark.parametrize(
    "draft",
    [
        _draft("모델이 만든 다른 원인"),
        _draft(CARD_CAUSE, access="EMPLOYEE"),
        _draft(CARD_CAUSE, links=[]),
    ],
)
def test_draft_check_fails_for_wrong_cause_scope_or_missing_links(draft: dict[str, Any]) -> None:
    ctx = _ctx(FakeApi([]), FakeObserver([]))

    check_draft(ctx, draft)

    assert _statuses(ctx)["A7"] == FAIL


# 실행 인자


def test_run_mode_requires_declared_model_and_assessment(
    capsys: pytest.CaptureFixture[str],
) -> None:
    assert e2e.main(["--run"]) == 2
    assert "--model-label" in capsys.readouterr().err


def test_missing_environment_is_refused(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("HOPS_E2E_DB_DSN", raising=False)
    monkeypatch.delenv("HOPS_E2E_PASSWORD", raising=False)

    assert e2e.main(["--preflight"]) == 2


def test_blocked_is_not_counted_as_a_failure() -> None:
    recorder = Recorder()
    recorder.add("A13", "관계 반영", BLOCKED, "공백")
    recorder.add("A1", "접수", PASS)

    assert recorder.count(FAIL) == 0 and recorder.count(BLOCKED) == 1


# 사례 A를 근거에서 알아보는 값

DOCUMENT_ID = "22222222-2222-4222-8222-222222222222"


def test_needles_include_the_knowledge_document_id_not_only_the_case_id() -> None:
    """상담 근거는 지식 문서 id·제목·발췌를 담고 사례 번호를 담지 않는다."""
    needles = case_needles({**_case(), "document_id": DOCUMENT_ID})

    assert DOCUMENT_ID in needles
    assert CARD_TITLE in needles and CARD_CAUSE in needles


def test_needles_before_approval_have_no_document_id_and_skip_empty_values() -> None:
    needles = case_needles({**_case(), "document_id": None, "title": ""})

    assert needles == [CARD_CAUSE, CASE_ID]


def test_followup_searches_evidence_with_the_document_id_after_approval() -> None:
    observer = FakeObserver([])
    ctx = _ctx(FakeApi([]), observer)
    case = {**_case(), "document_id": DOCUMENT_ID}

    evaluate_followup(ctx, "X", "B", _outcome(), case, approved=True)

    assert DOCUMENT_ID in observer.needles


def test_like_patterns_escape_wildcards_and_drop_empty_values() -> None:
    assert like_patterns(["a%b", "c_d", "", "e\\f"]) == ["%a\\%b%", "%c\\_d%", "%e\\\\f%"]


# 오류가 나도 보고서는 남는다


class _Boom:
    """로그인에서 ApiError가 아닌 예외를 내는 API 대역(예: 전송 오류)."""

    def __init__(self, *_: Any, **__: Any) -> None:
        pass

    def login(self, *_: Any) -> None:
        raise httpx.ConnectError("연결 끊김")


class _Seeded(FakeObserver):
    closed = False

    def __init__(self, *_: Any) -> None:
        super().__init__([])

    def counts(self) -> dict[str, int]:
        return {"members": 8, "cases": 6, "symptoms": 5}

    def close(self) -> None:
        type(self).closed = True


def test_unexpected_error_still_writes_the_report_and_closes_the_observer(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("HOPS_E2E_DB_DSN", "postgresql://unused")
    monkeypatch.setenv("HOPS_E2E_PASSWORD", "pw")
    monkeypatch.setattr(e2e, "Observer", _Seeded)
    monkeypatch.setattr(e2e, "Api", _Boom)
    out = tmp_path / "report.json"

    code = e2e.main(["--preflight", "--out", str(out)])

    report = json.loads(out.read_text(encoding="utf-8"))
    assert code == 1
    assert any(
        step["id"] == "ERROR" and "ConnectError" in step["detail"] for step in report["steps"]
    )
    assert _Seeded.closed
