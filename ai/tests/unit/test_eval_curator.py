"""Curator 평가 하네스: 시나리오·정답 형식, 입력 대역, 재생기, 기록 저장, 배치 보고.

모델은 Fake다. 하네스가 올바르게 이어지는지만 보며 실제 모델 품질을 검증하지 않는다.
"""

import json
import shutil
from datetime import UTC, datetime
from pathlib import Path

import pytest

from evaluation.curator import validate as curator_validate
from evaluation.curator.candidates import load_catalog
from evaluation.curator.checks import check_behavior, check_trace
from evaluation.curator.loader import load_all, select
from evaluation.curator.pilot import (
    MAX_CALLS_PER_RUN,
    exit_code,
    run_batch,
)
from evaluation.curator.recheck import recheck, rejudge_behavior
from evaluation.curator.request import build_request
from evaluation.curator.runner import run_case
from evaluation.curator.scenario import CuratorScenarioError, parse_scenario
from evaluation.curator.trace_io import load_trace, save_trace
from hops_ai.agents.curator.drafting import CaseDraft, SourceQuote
from hops_ai.agents.curator.link import LinkChoice, LinkQuote, LinkSelection
from tests.doubles.llm import ByModelLLM

CATALOG = load_catalog()
KEYS = {key: node.id for key, node in CATALOG.items()}
PAIRS = load_all()
BY_FAMILY = {scenario.case_family: (scenario, gold) for scenario, gold in PAIRS}
NOW = datetime(2026, 11, 7, tzinfo=UTC)


def test_repository_scenarios_and_golds_pass_validation() -> None:
    assert curator_validate.validate() == []
    assert len(PAIRS) == 10
    assert sorted(s.split for s, _ in PAIRS) == ["DEV"] * 6 + ["TEST"] * 4


def test_every_scenario_builds_a_wire_valid_request_without_gold_or_card_case_text() -> None:
    for scenario, gold in PAIRS:
        request = build_request(scenario, CATALOG, now=NOW)
        text = request.model_dump_json()
        # 입력에는 정답의 기대 노드 키도, 카드의 사례 문구(case)도 들어가지 않는다
        for link in (*gold.expected_links, *gold.forbidden_links):
            assert link.target not in text, scenario.scenario_id
        assert request.kind == "CASE_DRAFT" and request.answerMessageId is None
        record = request.workRecord
        assert record.kind == "VERIFIED_RESOLUTION"
        assert record.confirmationState == scenario.confirmation_state
        assert {item.systemId for item in record.linkCandidates} == {request.systems[0].id}
        assert record.linkCandidates, scenario.scenario_id


def test_card_scenarios_take_input_from_the_card_but_never_the_case_or_relation() -> None:
    scenario, _ = BY_FAMILY["ea-doc-no-permission"]
    card = (
        Path(__file__).resolve().parents[3] / "docs/knowledge/cases/case-ea-doc-no-permission.yml"
    ).read_text(encoding="utf-8")
    request = build_request(scenario, CATALOG, now=NOW)
    assert len(request.messages) == 4
    assert request.workRecord.relatedRecords[0].kind == "PROGRESS"
    assert request.workRecord.action in card  # 처리 기록은 카드의 값 그대로
    # 카드의 사례 문구(case.title/applicability)는 입력에 없다
    assert "preconditions" not in request.model_dump_json()


def test_request_is_deterministic_for_ids_and_changes_only_the_deadline_with_the_clock() -> None:
    scenario, _ = BY_FAMILY["ea-failed-attempt"]
    first = build_request(scenario, CATALOG, now=NOW).model_dump(mode="json")
    again = build_request(scenario, CATALOG, now=NOW).model_dump(mode="json")
    later = build_request(scenario, CATALOG, now=datetime(2026, 11, 8, tzinfo=UTC)).model_dump(
        mode="json"
    )
    assert first == again
    later["expiresAt"] = first["expiresAt"]
    assert later == first


def test_authored_scenario_covers_the_process_context() -> None:
    scenario, _ = BY_FAMILY["ea-failed-attempt"]
    record = build_request(scenario, CATALOG, now=NOW).workRecord
    assert [item.kind for item in record.relatedRecords] == ["FAILED_ATTEMPT", "FAILED_ATTEMPT"]
    assert [item.state for item in record.resolutionHistory] == ["UNRESOLVED"]
    assert [item.kind for item in record.consultedKnowledge] == ["GUIDE"]
    request = build_request(scenario, CATALOG, now=NOW)
    assert request.accessContext.allowedDocumentScopes == ["EMPLOYEE", "SYSTEM_STAFF"]
    assert [item.accessScope for item in request.problems[0].standardRequest.knowledge] == [
        "EMPLOYEE"
    ]


def test_split_selection_keeps_test_scenarios_frozen() -> None:
    assert {s.split for s, _ in select(PAIRS, [], None, allow_test=False)} == {"DEV"}
    with pytest.raises(ValueError, match="동결"):
        select(PAIRS, [], "TEST", allow_test=False)
    with pytest.raises(ValueError, match="동결"):
        select(PAIRS, ["ea-injection"], None, allow_test=False)
    assert len(select(PAIRS, [], "TEST", allow_test=True)) == 4
    with pytest.raises(ValueError, match="알 수 없는"):
        select(PAIRS, ["nope"], None, allow_test=False)


# --- 검증기: 잘못된 파일을 잡는다 -------------------------------------------------


def copy_data(tmp_path: Path) -> tuple[Path, Path]:
    scenarios, golds = tmp_path / "scenarios", tmp_path / "golds"
    shutil.copytree(curator_validate.SCENARIO_DIR, scenarios)
    shutil.copytree(curator_validate.GOLD_DIR, golds)
    return scenarios, golds


def edit(path: Path, change) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    change(data)
    path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")


def errors_for(tmp_path: Path) -> list[str]:
    scenarios, golds = tmp_path / "scenarios", tmp_path / "golds"
    return curator_validate.validate(scenarios, golds)


def test_validate_rejects_gold_leaked_into_the_input(tmp_path: Path) -> None:
    scenarios, _ = copy_data(tmp_path)
    edit(
        scenarios / "C-ea-failed-attempt-001.json",
        lambda d: d["messages"][0].update(text="check-ea-filter-condition 때문에 안 보여요"),
    )
    assert any("정답 누출" in error for error in errors_for(tmp_path))


def test_validate_rejects_card_gold_that_disagrees_with_the_card(tmp_path: Path) -> None:
    _, golds = copy_data(tmp_path)
    edit(
        golds / "C-ea-doc-no-permission-001.json",
        lambda d: d["expectedLinks"].pop(),
    )
    assert any("relation과 다르다" in error for error in errors_for(tmp_path))


@pytest.mark.parametrize(
    ("file", "change", "expected"),
    [
        (
            "C-ea-failed-attempt-001.json",
            lambda d: d["expectedLinks"].append({"relation": "ADDRESSES", "target": "sym-nope"}),
            "카탈로그에 없다",
        ),
        (
            "C-ea-failed-attempt-001.json",
            lambda d: d["expectedLinks"].append(
                {"relation": "ADDRESSES", "target": "check-ea-filter-condition"}
            ),
            "관계 종류와 노드 종류",
        ),
        (
            "C-ea-failed-attempt-001.json",
            lambda d: d["expectedLinks"].append(
                {"relation": "USES_CHECK", "target": "check-login-browser-cookie"}
            ),
            "다른 시스템",
        ),
        (
            "C-ea-failed-attempt-001.json",
            lambda d: d["forbiddenLinks"].append(d["expectedLinks"][0]),
            "겹친다",
        ),
        (
            "C-ea-failed-attempt-001.json",
            lambda d: d.update(forbiddenPhrases=[]),
            "CB4를 고르려면",
        ),
        (
            "C-login-unresolved-001.json",
            lambda d: d.update(acceptedReusable=[True]),
            "미해결 기록의 정답",
        ),
        (
            "C-login-unresolved-001.json",
            lambda d: d.update(scenarioId="C-other-001"),
            "파일 이름이 scenarioId와 다르다",
        ),
    ],
)
def test_validate_rejects_inconsistent_golds(tmp_path: Path, file, change, expected) -> None:
    _, golds = copy_data(tmp_path)
    edit(golds / file, change)
    assert any(expected in error for error in errors_for(tmp_path)), errors_for(tmp_path)


def test_validate_rejects_missing_pairs_and_missing_required_situations(tmp_path: Path) -> None:
    scenarios, golds = copy_data(tmp_path)
    (golds / "C-login-unresolved-001.json").unlink()
    (scenarios / "C-ea-injection-001.json").unlink()
    (golds / "C-ea-injection-001.json").unlink()
    errors = errors_for(tmp_path)
    assert any("정답 파일이 없다" in error for error in errors)
    assert any("필수 상황이 없다: INJECTION" in error for error in errors)


@pytest.mark.parametrize(
    "change",
    [
        lambda d: d.pop("messages"),
        lambda d: d.update(systemCode="NOPE"),
        lambda d: d["records"][-1].update(kind="PROGRESS"),
        lambda d: d["records"][-1].update(verification=None),
        lambda d: d.update(confirmationState="DONE"),
        lambda d: d["facts"][0].update(messages=[99]),
        lambda d: d["messages"].clear(),
        lambda d: d.update(card="case-ea-doc-no-permission"),
    ],
)
def test_scenario_parser_rejects_malformed_input(change) -> None:
    scenario_path = curator_validate.SCENARIO_DIR / "C-ea-failed-attempt-001.json"
    data = json.loads(scenario_path.read_text(encoding="utf-8"))
    change(data)
    with pytest.raises(CuratorScenarioError):
        parse_scenario(data)


# --- 재생기·기록·배치 ---------------------------------------------------------------


def scripted_llm(scenario, gold, *, link_error: BaseException | None = None) -> ByModelLLM:
    """정답을 따르는 Fake 모델. 입력의 메시지에서 인용을 그대로 옮긴다."""

    def draft(request):
        payload = json.loads(request.messages[1].content)
        user = next(item for item in payload["messages"] if item["sender"] == "USER")
        return CaseDraft(
            reusable=True,
            title="합성 사례",
            problemStatement="사용자가 겪은 현상",
            applicability="같은 증상일 때",
            limitations=["원인은 처리 기록에 따른다"],
            sources=[SourceQuote(messageId=user["messageId"], quote=user["text"][:6])],
        )

    def link(request):
        if link_error is not None:
            raise link_error
        payload = json.loads(request.messages[1].content)
        evidence = {item["id"]: item["text"] for item in payload["evidence"]}
        user_id = next(
            item["id"] for item in payload["evidence"] if item["source"] == "USER_MESSAGE"
        )
        record_id = next(
            item["id"] for item in payload["evidence"] if item["source"] == "WORK_RECORD"
        )
        links = []
        for expected in gold.expected_links:
            source = user_id if expected.relation == "ADDRESSES" else record_id
            links.append(
                LinkChoice(
                    relation=expected.relation,
                    targetId=KEYS[expected.target],
                    evidence=[LinkQuote(evidenceId=source, quote=evidence[source][:14])],
                )
            )
        return LinkSelection(links=links)

    return ByModelLLM({CaseDraft: draft, LinkSelection: link})


async def test_runner_replays_a_case_through_the_production_pipeline() -> None:
    scenario, gold = BY_FAMILY["ea-doc-no-permission"]
    llm = scripted_llm(scenario, gold)

    trace = await run_case(scenario, CATALOG, llm=llm)

    assert trace.error is None and trace.result.status == "SUCCEEDED"
    assert trace.model_calls == 2  # 초안 1 + link 1, Supervisor 모델 호출 없음
    assert [r.response_model for r in llm.requests] == [CaseDraft, LinkSelection]
    assert check_trace(trace) == []
    findings, passed = check_behavior(trace, gold, KEYS)
    assert findings == [] and passed == {"CB1": True, "CB2": True, "CB3": True, "CB4": True}


async def test_runner_records_a_model_failure_as_a_failed_result_not_a_pass() -> None:
    from hops_ai.harness.errors import TransientError

    scenario, gold = BY_FAMILY["ea-doc-no-permission"]
    llm = scripted_llm(scenario, gold, link_error=TransientError("연결 실패"))

    trace = await run_case(scenario, CATALOG, llm=llm)

    assert trace.result is not None and trace.result.status == "FAILED"
    assert trace.result.caseProposal is None
    assert check_trace(trace) == []  # 판정할 제안이 없다. 보고서가 실패로 센다
    assert check_behavior(trace, gold, KEYS) == ([], {})


async def test_unresolved_scenario_makes_no_model_call() -> None:
    scenario, gold = BY_FAMILY["login-unresolved"]
    llm = ByModelLLM({})

    trace = await run_case(scenario, CATALOG, llm=llm)

    assert trace.model_calls == 0 and llm.requests == []
    assert trace.result.status == "SUCCEEDED" and trace.result.caseProposal.reusable is False
    _, passed = check_behavior(trace, gold, KEYS)
    assert passed == {"CB1": True}


async def test_trace_round_trip_and_recheck(tmp_path: Path) -> None:
    scenario, gold = BY_FAMILY["file-link-expired"]
    trace = await run_case(scenario, CATALOG, llm=scripted_llm(scenario, gold))

    path = save_trace(trace, tmp_path, 1)
    loaded = load_trace(path)

    assert path.name == "C-file-link-expired-001.run1.trace.json"
    assert loaded.request == trace.request and loaded.result == trace.result
    assert recheck([tmp_path]) == {path.name: []}
    assert rejudge_behavior([tmp_path]) == {
        "C-file-link-expired-001": {
            name: {"passed": 1, "total": 1} for name in gold.behavior_properties
        }
    }


async def test_batch_reports_per_scenario_rates_and_respects_the_call_cap() -> None:
    pairs = [BY_FAMILY["ea-doc-no-permission"], BY_FAMILY["login-unresolved"]]

    class PerScenario:
        """시나리오별 Fake. 요청의 문제 ID로 어느 시나리오인지 가려 정답을 따른다."""

        def __init__(self) -> None:
            self.inner = {s.scenario_id: scripted_llm(s, g) for s, g in pairs}
            self.requests = []

        async def generate(self, request):
            payload = json.loads(request.messages[1].content)
            owner = (
                "C-ea-doc-no-permission-001"
                if "문서함" in json.dumps(payload, ensure_ascii=False)
                else "C-login-unresolved-001"
            )
            return await self.inner[owner].generate(request)

    report = await run_batch(pairs, llm=PerScenario(), repeat=2, max_calls=100, catalog=CATALOG)

    totals = report["totals"]
    assert totals["runs"] == 4 and totals["succeeded"] == 4 and totals["failed"] == 0
    assert (
        totals["modelCalls"] == 4 and totals["safetyViolations"] == 0
    )  # 2회 × 2호출 + 미해결 0호출
    first = report["scenarios"][0]
    assert first["summary"]["behavior"]["CB2"] == {"passed": 2, "total": 2}
    links = first["runs"][0]["case"]["links"]
    assert [(item["relation"], item["target"]) for item in links[1:]] == [
        ("ADDRESSES", "sym-ea-doc-not-listed"),
        ("USES_CHECK", "check-ea-participation"),
        ("USES_CHECK", "check-ea-view-permission-record"),
    ]
    assert links[0]["relation"] == "APPLIES_TO"
    assert report["totalCallCap"]["exceeded"] is False
    assert exit_code(report) == 0


async def test_batch_stops_before_a_run_that_could_exceed_the_cap() -> None:
    scenario, gold = BY_FAMILY["ea-doc-no-permission"]
    llm = scripted_llm(scenario, gold)

    report = await run_batch(
        [(scenario, gold)], llm=llm, repeat=5, max_calls=MAX_CALLS_PER_RUN + 1, catalog=CATALOG
    )

    totals = report["totals"]
    assert totals["runs"] == 1 and totals["skipped"] == 4  # 첫 실행 뒤 남은 호출이 부족하다
    assert report["totalCallCap"]["exceeded"] is True and exit_code(report) == 1
    assert totals["modelCalls"] <= MAX_CALLS_PER_RUN + 1


async def test_batch_exit_code_flags_safety_violations_and_failed_runs() -> None:
    scenario, gold = BY_FAMILY["ea-doc-no-permission"]

    def bad_draft(request):
        payload = json.loads(request.messages[1].content)
        user = next(item for item in payload["messages"] if item["sender"] == "USER")
        return CaseDraft(
            reusable=True,
            title="t",
            problemStatement="p",
            applicability="a",
            limitations=[],
            sources=[SourceQuote(messageId=user["messageId"], quote=user["text"][:6])],
        )

    llm = ByModelLLM({CaseDraft: bad_draft, LinkSelection: [LinkSelection(links=[])]})
    report = await run_batch([(scenario, gold)], llm=llm, repeat=1, max_calls=20, catalog=CATALOG)
    # 연결이 비어도 안전 성질 위반은 아니다. 기대 연결 부재는 행동 성질(CB2)에서만 관찰한다.
    assert report["totals"]["safetyViolations"] == 0
    behavior = report["scenarios"][0]["summary"]["behavior"]
    assert behavior["CB2"] == {"passed": 0, "total": 1}
    assert exit_code(report) == 0

    failing = ByModelLLM({})
    report = await run_batch(
        [(scenario, gold)], llm=failing, repeat=1, max_calls=20, catalog=CATALOG
    )
    assert report["totals"]["failed"] == 1 and exit_code(report) == 1


async def test_case_with_employee_scope_consultation_evidence_completes_end_to_end() -> None:
    """직원 공개 상담 근거가 요청서에 있는 사례도 끝까지 돈다(#198 범위 결함 회귀)."""
    scenario, gold = BY_FAMILY["ea-failed-attempt"]

    trace = await run_case(scenario, CATALOG, llm=scripted_llm(scenario, gold))

    assert trace.result.status == "SUCCEEDED" and trace.result.caseProposal.reusable
    assert [item.accessScope for item in trace.result.evidence] == ["EMPLOYEE"]
    assert check_trace(trace) == []
