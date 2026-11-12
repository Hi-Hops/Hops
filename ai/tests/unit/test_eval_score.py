"""정답 자동 대조 채점. 저장된 기록과 손으로 만든 기록만 쓰며 모델을 부르지 않는다."""

import json
from pathlib import Path
from typing import Any

from evaluation.score import final_state, macro_f1, main, score_dirs, score_trace, summarize

BASELINES = Path(__file__).resolve().parents[2] / "evaluation" / "baselines"
SYSTEMS = [
    {"id": "sys-ea", "code": "ELECTRONIC_APPROVAL"},
    {"id": "sys-login", "code": "COMMON_LOGIN"},
]


def turn(
    *,
    problems: list[dict[str, Any]] | None = None,
    guide: str | None = None,
    tickets: list[str] | None = None,
    status: str = "SUCCEEDED",
    error: dict[str, Any] | None = None,
    roles: list[str] | None = None,
    ref_map: list[list[str]] | None = None,
    user_reply: str | None = None,
) -> dict[str, Any]:
    return {
        "request": {"systems": SYSTEMS},
        "result": {
            "status": status,
            "error": error,
            "problemProposals": problems or [],
            "guide": {"problemRef": guide} if guide else None,
            "ticketProposals": [{"problemRef": ref} for ref in tickets or []],
        },
        "role_calls": [{"role": role} for role in roles or []],
        "ref_map": ref_map or [],
        "user_reply": user_reply,
        "model_calls": 2,
        "elapsed_seconds": 1.5,
        "input_tokens": 100,
        "output_tokens": 10,
    }


def problem(ref: str, intent: str, system: str | None) -> dict[str, Any]:
    return {"problemRef": ref, "intent": intent, "reportedSystemCandidateId": system}


def gold_problem(key: str, intents: list[str], system: str | None, actions: list[str]) -> dict:
    return {
        "problemKey": key,
        "acceptedIntents": intents,
        "reportedSystemCode": system,
        "allowedFinalActions": actions,
    }


def trace(*turns: dict[str, Any]) -> dict[str, Any]:
    return {"scenario_id": "S-test-001", "turns": list(turns)}


def test_a_ticket_after_questions_is_scored_on_the_stored_problem_id() -> None:
    first = turn(
        problems=[problem("new:1", "OBSERVED_SYMPTOM", "sys-ea")],
        roles=["INQUIRY", "KNOWLEDGE", "INQUIRY"],
        ref_map=[["new:1", "p-1"]],
        user_reply="오늘 오전부터요.",
    )
    second = turn(
        problems=[problem("p-1", "OBSERVED_SYMPTOM", "sys-ea")],
        tickets=["p-1"],
        roles=["INQUIRY", "KNOWLEDGE", "TICKET"],
    )
    gold = {
        "problems": [
            gold_problem("p1", ["OBSERVED_SYMPTOM"], "ELECTRONIC_APPROVAL", ["PROPOSE_TICKET"])
        ]
    }

    scored = score_trace(trace(first, second), gold)

    assert scored["taskOk"] and scored["pathOk"] and scored["problemCountOk"]
    assert scored["rows"][0]["action"] == "PROPOSE_TICKET"
    assert (scored["turns"], scored["modelCalls"], scored["inputTokens"]) == (2, 4, 200)


def test_problems_are_matched_by_content_not_by_order() -> None:
    only = turn(
        problems=[
            problem("new:1", "ACCESS_REQUEST", "sys-login"),
            problem("new:2", "HOW_TO", "sys-ea"),
        ],
        guide="new:2",
        tickets=["new:1"],
        roles=["INQUIRY", "KNOWLEDGE", "TICKET"],
    )
    gold = {
        "problems": [
            gold_problem("p1", ["HOW_TO"], "ELECTRONIC_APPROVAL", ["ANSWER_GUIDE"]),
            gold_problem("p2", ["ACCESS_REQUEST"], "COMMON_LOGIN", ["PROPOSE_TICKET"]),
        ]
    }

    scored = score_trace(trace(only), gold)

    assert scored["taskOk"]
    assert [row["action"] for row in scored["rows"]] == ["ANSWER_GUIDE", "PROPOSE_TICKET"]


def test_a_merged_composite_inquiry_fails_on_problem_count() -> None:
    only = turn(
        problems=[problem("new:1", "OBSERVED_SYMPTOM", "sys-ea")],
        tickets=["new:1"],
        roles=["INQUIRY", "KNOWLEDGE", "TICKET"],
    )
    gold = {
        "problems": [
            gold_problem("p1", ["OBSERVED_SYMPTOM"], "COMMON_LOGIN", ["PROPOSE_TICKET"]),
            gold_problem("p2", ["OBSERVED_SYMPTOM"], "ELECTRONIC_APPROVAL", ["PROPOSE_TICKET"]),
        ]
    }

    scored = score_trace(trace(only), gold)

    assert not scored["taskOk"] and not scored["problemCountOk"]
    assert sorted(row["action"] for row in scored["rows"]) == ["MISSING", "PROPOSE_TICKET"]


def test_a_ticket_after_an_unresolved_guide_uses_the_follow_up_gold() -> None:
    follow_up = "안내대로 해 봤는데 해결되지 않았어요."
    first = turn(
        problems=[problem("new:1", "HOW_TO", "sys-ea")],
        guide="new:1",
        roles=["INQUIRY", "KNOWLEDGE"],
        ref_map=[["new:1", "p-1"]],
        user_reply=follow_up,
    )
    second = turn(
        problems=[problem("p-1", "HOW_TO", "sys-ea")],
        tickets=["p-1"],
        roles=["INQUIRY", "KNOWLEDGE", "TICKET"],
    )
    gold_one = gold_problem("p1", ["HOW_TO"], "ELECTRONIC_APPROVAL", ["ANSWER_GUIDE"])
    gold_one["afterFollowUp"] = {"allowedFinalActions": ["PROPOSE_TICKET", "ANSWER_GUIDE"]}
    gold = {"problems": [gold_one]}

    with_follow_up = score_trace(trace(first, second), gold, follow_up)
    without = score_trace(trace(first, second), gold)

    assert with_follow_up["taskOk"] and with_follow_up["pathOk"]
    # 후속 응답을 재생한 기록이 아니면 첫 허용 집합만 본다.
    assert not without["taskOk"]


def test_a_retryable_failure_without_problems_matches_fail_retryable() -> None:
    failed = turn(
        status="FAILED",
        error={"code": "SEARCH_UNAVAILABLE", "retryable": True},
        roles=["INQUIRY", "KNOWLEDGE"],
    )
    gold = {"problems": [gold_problem("p1", ["HOW_TO"], "ELECTRONIC_APPROVAL", ["FAIL_RETRYABLE"])]}

    scored = score_trace(trace(failed), gold)

    assert scored["taskOk"] and scored["problemCountOk"]
    assert scored["rows"][0]["intentOk"] is None
    assert final_state(trace(failed))["scenarioAction"] == "FAIL_RETRYABLE"


def test_search_is_not_expected_for_feature_requests_or_unknown_systems() -> None:
    feature = turn(
        problems=[problem("new:1", "FEATURE_REQUEST", "sys-ea")],
        tickets=["new:1"],
        roles=["INQUIRY", "TICKET"],
    )
    waiting = turn(problems=[problem("new:1", "OBSERVED_SYMPTOM", None)], roles=["INQUIRY"])

    feature_scored = score_trace(
        trace(feature),
        {
            "problems": [
                gold_problem("p1", ["FEATURE_REQUEST"], "ELECTRONIC_APPROVAL", ["PROPOSE_TICKET"])
            ]
        },
    )
    waiting_scored = score_trace(
        trace(waiting),
        {"problems": [gold_problem("p1", ["UNKNOWN", "OBSERVED_SYMPTOM"], None, ["AWAIT_SYSTEM"])]},
    )

    assert feature_scored["pathOk"] and waiting_scored["pathOk"]
    assert waiting_scored["rows"][0]["action"] == "AWAIT_SYSTEM"


def test_macro_f1_treats_an_accepted_set_as_one_truth() -> None:
    score, per_label = macro_f1(
        [
            ("HOW_TO", ["HOW_TO"]),
            ("HOW_TO", ["ACCESS_REQUEST"]),
            ("OBSERVED_SYMPTOM", ["UNKNOWN", "OBSERVED_SYMPTOM"]),
        ]
    )

    assert per_label == {"ACCESS_REQUEST": 0.0, "HOW_TO": 0.667, "OBSERVED_SYMPTOM": 1.0}
    assert score == 0.556


def test_stored_baselines_score_the_same_as_the_recorded_manual_comparison() -> None:
    """기준선 기록(baselines/README.md)에 사람이 대조해 적은 값과 같아야 한다."""
    dev = score_dirs([BASELINES / "2026-11-06-dev-judgment-relations-traces"])["total"]
    test = score_dirs([BASELINES / "2026-11-06-test-judgment-final-traces"])["total"]

    assert dev["taskSuccess"] == [12, 12] and dev["problemActionOk"] == [13, 13]
    assert test["taskSuccess"] == [6, 8] and test["intentAccuracy"] == [6, 8]
    assert test["problemCountOk"] == [7, 8] and test["turnsOk"] == [13, 13]
    assert (test["modelCalls"], test["inputTokens"], test["outputTokens"]) == (46, 94268, 7058)


def test_repeats_are_collected_per_scenario_across_directories(tmp_path: Path, capsys) -> None:
    source = BASELINES / "2026-11-06-test-judgment-final-traces"
    names = ["S-out-of-scope-001.trace.json", "S-file-sharelink-howto-001.trace.json"]
    for folder in ("a", "b"):
        (tmp_path / folder).mkdir()
        for name in names:
            (tmp_path / folder / name).write_text((source / name).read_text(encoding="utf-8"))

    report = score_dirs([tmp_path / "a", tmp_path / "b"])

    assert report["repeats"] == {"S-file-sharelink-howto-001": [2, 2], "S-out-of-scope-001": [0, 2]}
    assert summarize([s for run in report["runs"] for s in run["scenarios"]]) == report["total"]
    assert main(["--json", str(tmp_path / "a")]) == 0
    assert json.loads(capsys.readouterr().out)["total"]["taskSuccess"] == [1, 2]
    assert main([str(tmp_path / "a"), str(tmp_path / "b")]) == 0
    assert "S-out-of-scope-001: 0/2" in capsys.readouterr().out
    assert main([]) == 2


def null_turn(error: str = "RuntimeError") -> dict[str, Any]:
    """pipeline이 예외로 끝나 결과가 없는 실행. runner가 `result=None`으로 남긴다."""
    return {
        "request": {"systems": SYSTEMS},
        "result": None,
        "role_calls": [{"role": "INQUIRY"}],
        "ref_map": [],
        "user_reply": None,
        "error": error,
        "model_calls": 1,
        "elapsed_seconds": 0.5,
        "input_tokens": 50,
        "output_tokens": 0,
    }


def test_the_same_temporary_ref_in_different_turns_is_two_problems() -> None:
    first = turn(
        problems=[problem("new:1", "HOW_TO", "sys-ea")],
        guide="new:1",
        roles=["INQUIRY", "KNOWLEDGE"],
        ref_map=[["new:1", "p-1"]],
        user_reply="권한도 신청하고 싶어요.",
    )
    second = turn(
        problems=[
            problem("p-1", "HOW_TO", "sys-ea"),
            problem("new:1", "ACCESS_REQUEST", "sys-login"),
        ],
        tickets=["new:1"],
        roles=["INQUIRY", "KNOWLEDGE", "TICKET"],
        ref_map=[["new:1", "p-2"]],
    )
    gold = {
        "problems": [
            gold_problem("p1", ["HOW_TO"], "ELECTRONIC_APPROVAL", ["ANSWER_GUIDE"]),
            gold_problem("p2", ["ACCESS_REQUEST"], "COMMON_LOGIN", ["PROPOSE_TICKET"]),
        ]
    }

    state = final_state(trace(first, second))
    scored = score_trace(trace(first, second), gold)

    assert [(p["key"], p["intent"], p["action"]) for p in state["problems"]] == [
        ("p-1", "HOW_TO", "ANSWER_GUIDE"),
        ("p-2", "ACCESS_REQUEST", "PROPOSE_TICKET"),
    ]
    assert scored["taskOk"] and scored["producedProblems"] == 2


def test_a_run_without_a_result_is_scored_as_a_failure() -> None:
    gold = {"problems": [gold_problem("p1", ["HOW_TO"], "ELECTRONIC_APPROVAL", ["ANSWER_GUIDE"])]}
    asked = turn(
        problems=[problem("new:1", "HOW_TO", "sys-ea")],
        roles=["INQUIRY"],
        ref_map=[["new:1", "p-1"]],
        user_reply="문서함이에요.",
    )

    only_crash = score_trace(trace(null_turn()), gold)
    crash_after_question = score_trace(trace(asked, null_turn("TimeoutError")), gold)

    assert not only_crash["taskOk"]
    assert (only_crash["status"], only_crash["error"]) == ("FAILED", "RuntimeError")
    assert (only_crash["turns"], only_crash["turnsOk"]) == (1, 0)
    assert only_crash["rows"][0]["action"] == "FAILED"
    assert not crash_after_question["taskOk"]
    assert crash_after_question["rows"][0]["action"] == "FAILED"
    assert (crash_after_question["turnsOk"], crash_after_question["error"]) == (1, "TimeoutError")


def test_a_crashed_trace_does_not_stop_the_report_for_other_traces(tmp_path: Path) -> None:
    source = BASELINES / "2026-11-06-test-judgment-final-traces"
    good = "S-file-sharelink-howto-001.trace.json"
    (tmp_path / good).write_text((source / good).read_text(encoding="utf-8"), encoding="utf-8")
    crashed = {"scenario_id": "S-out-of-scope-001", "turns": [null_turn()]}
    (tmp_path / "S-out-of-scope-001.trace.json").write_text(json.dumps(crashed), encoding="utf-8")

    report = score_dirs([tmp_path])

    assert report["total"]["taskSuccess"] == [1, 2]
    assert report["repeats"] == {"S-file-sharelink-howto-001": [1, 1], "S-out-of-scope-001": [0, 1]}
    assert main([str(tmp_path)]) == 0


def test_an_earlier_guide_does_not_pass_what_happens_after_an_unresolved_reply() -> None:
    follow_up = "안내대로 해 봤는데 해결되지 않았어요."
    gold_one = gold_problem("p1", ["HOW_TO"], "ELECTRONIC_APPROVAL", ["ANSWER_GUIDE"])
    gold_one["afterFollowUp"] = {"allowedFinalActions": ["PROPOSE_TICKET", "ANSWER_GUIDE"]}
    gold = {"problems": [gold_one]}
    guided = turn(
        problems=[problem("new:1", "HOW_TO", "sys-ea")],
        guide="new:1",
        roles=["INQUIRY", "KNOWLEDGE"],
        ref_map=[["new:1", "p-1"]],
        user_reply=follow_up,
    )
    search_failed = turn(
        status="FAILED",
        error={"code": "SEARCH_UNAVAILABLE", "retryable": True},
        roles=["INQUIRY", "KNOWLEDGE"],
    )
    only_asked = turn(problems=[problem("p-1", "HOW_TO", "sys-ea")], roles=["INQUIRY"])
    guided_again = turn(
        problems=[problem("p-1", "HOW_TO", "sys-ea")], guide="p-1", roles=["INQUIRY", "KNOWLEDGE"]
    )

    failed = score_trace(trace(guided, search_failed), gold, follow_up)
    asked = score_trace(trace(guided, only_asked), gold, follow_up)
    cut_off = score_trace(trace(guided), gold, follow_up)
    crashed = score_trace(trace(guided, null_turn()), gold, follow_up)
    answered = score_trace(trace(guided, guided_again), gold, follow_up)

    assert (failed["taskOk"], failed["rows"][0]["action"]) == (False, "FAIL_RETRYABLE")
    assert (asked["taskOk"], asked["rows"][0]["action"]) == (False, "AWAIT_ANSWER")
    assert (cut_off["taskOk"], cut_off["rows"][0]["action"]) == (False, "AWAIT_ANSWER")
    assert (crashed["taskOk"], crashed["rows"][0]["action"]) == (False, "FAILED")
    assert (answered["taskOk"], answered["rows"][0]["action"]) == (True, "ANSWER_GUIDE")
    assert summarize([failed, asked, cut_off, crashed])["tokensPerSuccess"] is None


def test_question_and_ending_counts_show_what_asking_more_bought() -> None:
    asked = turn(
        problems=[problem("new:1", "OBSERVED_SYMPTOM", "sys-ea")],
        roles=["INQUIRY", "KNOWLEDGE", "INQUIRY"],
        ref_map=[["new:1", "p-1"]],
        user_reply="임시 저장만 해 둔 문서예요.",
    )
    asked["result"]["questions"] = [
        {"problemRef": "new:1", "requestedFields": ["occurredAt", "check-ea-submit-state"]}
    ]
    guided = turn(
        problems=[problem("p-1", "OBSERVED_SYMPTOM", "sys-ea")],
        guide="p-1",
        roles=["INQUIRY", "KNOWLEDGE"],
    )
    gold = {
        "problems": [
            gold_problem(
                "p1",
                ["OBSERVED_SYMPTOM"],
                "ELECTRONIC_APPROVAL",
                ["PROPOSE_TICKET", "ANSWER_GUIDE"],
            )
        ]
    }

    scored = score_trace(trace(asked, guided), gold)
    summary = summarize([scored])

    assert (scored["questionBundles"], scored["checkItemsAsked"]) == (1, 1)
    assert (summary["questionBundles"], summary["checkItemsAsked"]) == (1, 1)
    assert (summary["guideEndings"], summary["ticketEndings"]) == (1, 0)
    assert summary["taskSuccess"] == [1, 1]
