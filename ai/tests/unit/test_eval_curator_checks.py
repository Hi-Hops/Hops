"""Curator 성질 검사기(C1~C5, CB1~CB4). 검사기마다 정상 기록과 위반 기록을 모두 확인한다.

기록은 손으로 만든 합성 값이다. 실제 모델을 부르지 않으며 모델 품질을 검증하지 않는다.
"""

from datetime import UTC, datetime

import pytest

from evaluation.curator.candidates import load_catalog
from evaluation.curator.checks import (
    UNCONFIRMED_CAUSE_LINE,
    UNCONFIRMED_VERSION_LINE,
    check_behavior,
    check_c1,
    check_c2,
    check_c3,
    check_c4,
    check_c5,
    check_trace,
)
from evaluation.curator.gold import parse_gold
from evaluation.curator.request import build_request, message_id, record_id
from evaluation.curator.scenario import parse_scenario
from evaluation.curator.trace import CaseTrace
from hops_ai.contracts.wire import AiResult

CATALOG = load_catalog()
SYMPTOM = CATALOG["sym-ea-doc-not-listed"]
OTHER_SYMPTOM = CATALOG["sym-ea-attach-not-open"]
CHECK = CATALOG["check-ea-participation"]
OTHER_CHECK = CATALOG["check-ea-upload-time"]
OTHER_SYSTEM_CHECK = CATALOG["check-login-browser-cookie"]


def scenario(*, state: str = "PENDING", attempts: int = 1):
    records = [
        {"kind": "FAILED_ATTEMPT", "action": f"브라우저 캐시를 지워 봄 {index}"}
        for index in range(attempts)
    ]
    records.append(
        {
            "kind": "VERIFIED_RESOLUTION",
            "action": "기안자에게 참조 추가를 요청함",
            "verification": "문서가 목록에 나타남",
            "confirmedCause": "열람 권한이 없었다",
        }
    )
    return parse_scenario(
        {
            "scenarioId": "C-test-001",
            "caseFamily": "test",
            "split": "DEV",
            "origin": "SYNTHETIC",
            "confirmationState": state,
            "systemCode": "ELECTRONIC_APPROVAL",
            "ticketTitle": "문서가 보이지 않음",
            "intent": "OBSERVED_SYMPTOM",
            "workType": "INVESTIGATION",
            "summary": "다른 팀 문서가 문서함에 보이지 않는다",
            "facts": [{"text": "기안자가 아니다", "messages": [3]}],
            "unknowns": [],
            "details": {"actualBehavior": "목록에 없음"},
            "messages": [
                {"sender": "USER", "text": "다른 팀 문서가 문서함에 안 나와요."},
                {"sender": "AI", "text": "직접 기안하셨나요?"},
                {"sender": "USER", "text": "제가 기안한 문서는 아니에요."},
            ],
            "records": records,
        }
    )


def make_trace(*, state: str = "PENDING", attempts: int = 1, **proposal_changes) -> CaseTrace:
    sc = scenario(state=state, attempts=attempts)
    request = build_request(sc, CATALOG, now=datetime(2026, 11, 7, tzinfo=UTC))
    record = request.workRecord
    user = message_id(sc, 1)
    # 기록의 원인은 있고 적용 버전은 없으므로 코드가 적용 버전 미확인 문장을 붙인다
    limitations = [UNCONFIRMED_VERSION_LINE] + [
        f"시도했지만 해결되지 않은 조치: 브라우저 캐시를 지워 봄 {index}"
        for index in reversed(range(attempts))
    ][:2]
    proposal = {
        "reusable": True,
        "title": "문서가 보이지 않을 때 참조 추가",
        "problemStatement": "다른 팀 문서가 문서함에 보이지 않는다",
        "action": record.action,
        "verification": record.verification,
        "applicability": "열람 권한이 없는 경우에 한한다",
        "limitations": limitations,
        "appliesToSystemVersion": None,
        "accessScope": "SYSTEM_STAFF",
        "sourceMessageIds": [user],
        "entityLinks": [
            {"relation": "APPLIES_TO", "targetId": record.systemId, "evidenceIds": [user]},
            {"relation": "ADDRESSES", "targetId": SYMPTOM.id, "evidenceIds": [user]},
            {"relation": "USES_CHECK", "targetId": CHECK.id, "evidenceIds": [record.id]},
        ],
    } | proposal_changes
    result = AiResult.model_validate(
        {
            "contractVersion": "hops-system-1",
            "runId": request.runId,
            "kind": "CASE_DRAFT",
            "inquiryId": request.inquiryId,
            "inquiryVersion": 1,
            "status": "SUCCEEDED",
            "answer": None,
            "problemProposals": [],
            "questions": [],
            "guide": None,
            "ticketProposals": [],
            "caseProposal": proposal,
            "evidence": [],
            "error": None,
        }
    )
    return CaseTrace(scenario_id=sc.scenario_id, request=request, result=result, model_calls=2)


def with_links(*links: dict, **changes) -> CaseTrace:
    base = make_trace()
    system = base.request.workRecord.systemId
    user = base.result.caseProposal.sourceMessageIds[0]
    head = {"relation": "APPLIES_TO", "targetId": system, "evidenceIds": [user]}
    return make_trace(entityLinks=[head, *links], **changes)


def link(relation: str, target: str, *evidence: str) -> dict:
    return {"relation": relation, "targetId": target, "evidenceIds": list(evidence)}


def test_good_trace_passes_every_safety_check() -> None:
    assert check_trace(make_trace()) == []
    assert check_trace(make_trace(attempts=0)) == []
    assert check_trace(make_trace(attempts=3)) == []


def test_failed_or_missing_results_are_not_judged() -> None:
    base = make_trace()
    assert check_trace(CaseTrace(base.scenario_id, base.request, None, 0, error="X")) == []
    failed = base.result.model_copy(update={"status": "FAILED"})
    assert check_trace(CaseTrace(base.scenario_id, base.request, failed, 1)) == []


def test_no_reusable_case_has_nothing_to_check() -> None:
    sc = scenario()
    base = make_trace()
    none = {
        "reusable": False,
        "title": None,
        "problemStatement": None,
        "action": None,
        "verification": None,
        "applicability": None,
        "limitations": [],
        "appliesToSystemVersion": None,
        "accessScope": None,
        "sourceMessageIds": [],
        "entityLinks": [],
    }
    result = AiResult.model_validate(base.result.model_dump(mode="json") | {"caseProposal": none})
    trace = CaseTrace(sc.scenario_id, base.request, result, 1)
    assert check_trace(trace) == []


@pytest.mark.parametrize(
    "changes",
    [
        {"action": "모델이 지어낸 조치"},
        {"verification": "모델이 지어낸 검증"},
        {"appliesToSystemVersion": "9.9"},
        {"accessScope": "EMPLOYEE"},
        {"limitations": []},  # 처리 기록의 실패 시도가 한계에서 빠졌다
        {"limitations": ["시도했지만 해결되지 않은 조치: 지어낸 시도"]},
        {
            "limitations": [
                "시도했지만 해결되지 않은 조치: 브라우저 캐시를 지워 봄 0",
                "시도했지만 해결되지 않은 조치: 브라우저 캐시를 지워 봄 0",
            ]
        },
    ],
)
def test_c1_detects_values_that_differ_from_the_work_record(changes: dict) -> None:
    trace = make_trace(**changes)
    assert [v.property_id for v in check_c1(trace)] and not check_c2(trace)


def test_c1_accepts_model_limitations_that_are_not_attempt_lines() -> None:
    trace = make_trace(
        limitations=[
            UNCONFIRMED_VERSION_LINE,
            "이 사례는 파일 형식과 관계없다",
            "시도했지만 해결되지 않은 조치: 브라우저 캐시를 지워 봄 0",
        ]
    )
    assert check_c1(trace) == []


def test_c1_expects_only_the_two_newest_failed_attempts_and_clips_long_text() -> None:
    trace = make_trace(
        attempts=3,
        limitations=[
            UNCONFIRMED_VERSION_LINE,
            "시도했지만 해결되지 않은 조치: 브라우저 캐시를 지워 봄 2",
            "시도했지만 해결되지 않은 조치: 브라우저 캐시를 지워 봄 1",
        ],
    )
    assert check_c1(trace) == []
    old_first = make_trace(
        attempts=3,
        limitations=[
            UNCONFIRMED_VERSION_LINE,
            "시도했지만 해결되지 않은 조치: 브라우저 캐시를 지워 봄 0",
            "시도했지만 해결되지 않은 조치: 브라우저 캐시를 지워 봄 1",
        ],
    )
    assert check_c1(old_first)


def test_c2_detects_invalid_evidence() -> None:
    sc = scenario()
    user = message_id(sc, 1)
    ai = message_id(sc, 2)
    base = make_trace()
    system = base.request.workRecord.systemId
    record = base.request.workRecord.id
    head = link("APPLIES_TO", system, user)
    cases = [
        {"sourceMessageIds": []},  # 출처 없음
        {"sourceMessageIds": [ai]},  # AI 메시지는 사례 출처가 아니다
        {"sourceMessageIds": [user, user]},
        {"sourceMessageIds": ["00000000-0000-4000-8000-000000000001"]},
        {"entityLinks": [link("APPLIES_TO", system)]},  # 근거 없음
        {"entityLinks": [link("APPLIES_TO", system, user, user)]},  # 근거 중복
        {"entityLinks": [link("APPLIES_TO", system, "00000000-0000-4000-8000-000000000001")]},
    ]
    for changes in cases:
        assert check_c2(make_trace(**changes)), changes
    # AI 메시지와 처리 기록은 증상 연결의 근거로 쓸 수 있다
    good = make_trace(entityLinks=[head, link("ADDRESSES", SYMPTOM.id, ai, record)])
    assert check_c2(good) == []
    # 확인했다는 연결(USES_CHECK)은 AI가 물은 메시지를 근거로 할 수 없다
    asked = make_trace(entityLinks=[head, link("USES_CHECK", CHECK.id, ai, record)])
    assert any("AI 메시지" in v.message for v in check_c2(asked))
    assert (
        check_c2(make_trace(entityLinks=[head, link("USES_CHECK", CHECK.id, record, user)])) == []
    )


@pytest.mark.parametrize(
    "bad",
    [
        link("ADDRESSES", "00000000-0000-4000-8000-0000000000aa", "E"),  # 후보에 없는 노드
        link("ADDRESSES", CHECK.id, "E"),  # 관계 종류와 노드 종류가 맞지 않음
        link("USES_CHECK", SYMPTOM.id, "E"),
        link("APPLIES_TO", SYMPTOM.id, "E"),  # 시스템이 아님
    ],
)
def test_c3_detects_targets_outside_the_server_candidates(bad: dict) -> None:
    user = make_trace().result.caseProposal.sourceMessageIds[0]
    bad = bad | {"evidenceIds": [user]}
    trace = make_trace(
        entityLinks=[link("APPLIES_TO", make_trace().request.workRecord.systemId, user), bad]
    )
    assert [v.property_id for v in check_c3(trace)] == ["C3"]


def test_c3_accepts_candidates_of_the_right_kind_and_rejects_other_system_nodes() -> None:
    user = make_trace().result.caseProposal.sourceMessageIds[0]
    other_system = with_links(link("USES_CHECK", OTHER_SYSTEM_CHECK.id, user))
    assert [v.property_id for v in check_c3(other_system)] == ["C3"]
    assert check_c3(with_links(link("ADDRESSES", OTHER_SYMPTOM.id, user))) == []


def test_c4_detects_unknown_relation_types_duplicates_and_excess() -> None:
    user = make_trace().result.caseProposal.sourceMessageIds[0]
    system = make_trace().request.workRecord.systemId
    head = link("APPLIES_TO", system, user)
    checks = [
        node
        for node in CATALOG.values()
        if node.kind == "CHECK_ITEM" and node.system_code == "ELECTRONIC_APPROVAL"
    ]
    # 계약 모델은 다른 관계 유형을 만들지 못하므로 모델을 우회해 검사기만 확인한다
    forged = make_trace().result.caseProposal.model_construct(
        **(make_trace().result.caseProposal.__dict__ | {"entityLinks": []})
    )
    forged_link = type(
        "L", (), {"relation": "MAY_HAVE_CAUSE", "targetId": "x", "evidenceIds": ["e"]}
    )
    forged = forged.model_copy(update={"entityLinks": [forged_link]})
    result = make_trace().result.model_copy(update={"caseProposal": forged})
    trace = CaseTrace("C-test-001", make_trace().request, result, 1)
    messages = [v.message for v in check_c4(trace)]
    assert any("계약에 없는 관계 유형" in message for message in messages)

    duplicate = make_trace(entityLinks=[head, head])
    assert any("반복" in v.message for v in check_c4(duplicate))
    no_system = make_trace(entityLinks=[link("ADDRESSES", SYMPTOM.id, user)])
    assert any("APPLIES_TO" in v.message for v in check_c4(no_system))
    many = make_trace(
        entityLinks=[head] + [link("USES_CHECK", node.id, user) for node in checks[:6]]
    )
    assert any("상한" in v.message for v in check_c4(many))
    assert check_c4(make_trace()) == []


def test_c5_requires_no_reusable_case_for_an_unresolved_record() -> None:
    assert [v.property_id for v in check_c5(make_trace(state="UNRESOLVED"))] == ["C5"]
    assert check_c5(make_trace(state="PENDING")) == []
    assert check_c5(make_trace(state="RESOLVED")) == []


def gold(**changes):
    base = {
        "scenarioId": "C-test-001",
        "acceptedReusable": [True],
        "expectedLinks": [
            {"relation": "ADDRESSES", "target": "sym-ea-doc-not-listed"},
            {"relation": "USES_CHECK", "target": "check-ea-participation"},
        ],
        "forbiddenLinks": [{"relation": "USES_CHECK", "target": "check-ea-upload-time"}],
        "forbiddenPhrases": ["보안 검사"],
        "behaviorProperties": ["CB1", "CB2", "CB3", "CB4"],
    }
    return parse_gold(base | changes)


IDS = {key: node.id for key, node in CATALOG.items()}


def test_behavior_all_pass_for_the_expected_result() -> None:
    findings, passed = check_behavior(make_trace(), gold(), IDS)
    assert findings == [] and passed == {"CB1": True, "CB2": True, "CB3": True, "CB4": True}


def test_behavior_reports_each_deviation_without_failing_the_run() -> None:
    user = make_trace().result.caseProposal.sourceMessageIds[0]
    trace = with_links(
        link("USES_CHECK", OTHER_CHECK.id, user),
        applicability="보안 검사 중인 경우",
    )
    findings, passed = check_behavior(trace, gold(), IDS)
    assert passed == {"CB1": True, "CB2": False, "CB3": False, "CB4": False}
    assert {f.property_id for f in findings} == {"CB2", "CB3", "CB4"}
    assert check_trace(trace) == []  # 행동 성질은 안전 성질 판정에 들어가지 않는다


def test_behavior_cb1_follows_the_accepted_set_and_skips_other_checks_without_a_case() -> None:
    base = make_trace()
    none = {
        "reusable": False,
        "title": None,
        "problemStatement": None,
        "action": None,
        "verification": None,
        "applicability": None,
        "limitations": [],
        "appliesToSystemVersion": None,
        "accessScope": None,
        "sourceMessageIds": [],
        "entityLinks": [],
    }
    result = AiResult.model_validate(base.result.model_dump(mode="json") | {"caseProposal": none})
    trace = CaseTrace(base.scenario_id, base.request, result, 1)
    findings, passed = check_behavior(trace, gold(), IDS)
    assert passed == {"CB1": False} and [f.property_id for f in findings] == ["CB1"]
    _, accepted = check_behavior(trace, gold(acceptedReusable=[True, False]), IDS)
    assert accepted == {"CB1": True}


def test_behavior_only_reports_the_properties_the_gold_selects() -> None:
    _, passed = check_behavior(make_trace(), gold(behaviorProperties=["CB1"]), IDS)
    assert passed == {"CB1": True}


def test_behavior_is_skipped_for_runs_without_a_result() -> None:
    base = make_trace()
    assert check_behavior(CaseTrace(base.scenario_id, base.request, None, 0), gold(), IDS) == (
        [],
        {},
    )
    assert record_id(scenario(), 0) != record_id(scenario(), 1)


def test_c1_requires_the_unconfirmed_fact_lines_the_record_implies() -> None:
    # 기록에 적용 버전이 없는데 미확인 문장이 빠졌다
    assert check_c1(make_trace(limitations=[])) != []
    missing = make_trace(limitations=["시도했지만 해결되지 않은 조치: 브라우저 캐시를 지워 봄 0"])
    assert any("미확인" in v.message for v in check_c1(missing))
    # 기록에 없는 원인 미확인 문장은 지어낸 것이다(이 기록은 원인이 있다)
    invented = make_trace(
        limitations=[
            UNCONFIRMED_CAUSE_LINE,
            UNCONFIRMED_VERSION_LINE,
            "시도했지만 해결되지 않은 조치: 브라우저 캐시를 지워 봄 0",
        ]
    )
    assert any("미확인" in v.message for v in check_c1(invented))
    # 중복도 어긋남이다
    twice = make_trace(
        limitations=[
            UNCONFIRMED_VERSION_LINE,
            UNCONFIRMED_VERSION_LINE,
            "시도했지만 해결되지 않은 조치: 브라우저 캐시를 지워 봄 0",
        ]
    )
    assert any("미확인" in v.message for v in check_c1(twice))
