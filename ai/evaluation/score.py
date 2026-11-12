"""저장된 실행 기록을 평가 정답과 대조해 채점한다. 모델을 부르지 않는다.

    cd ai
    uv run python -m evaluation.score evaluation/baselines/2026-11-06-test-judgment-final-traces
    uv run python -m evaluation.score --json <trace 디렉터리> [<trace 디렉터리> ...]

`evaluation.recheck`가 안전 성질을 다시 판정한다면, 이 도구는 사람이 보고서를 읽어 하던 정답 대조를
코드로 한다. 정답(`golds/`)의 허용 집합과 비교해 문제별 의도·보고 시스템·종결 행동, 문제 분리,
검색·티켓 역할의 호출 여부를 본다. 디렉터리를 여러 개 주면 같은 시나리오의 반복 결과도 모아 준다.

행동 채점이므로 불일치가 있어도 종료 코드는 0이다. 소수 표본의 결과이며 일반 성능이 아니다.
"""

import json
import sys
from collections import Counter, defaultdict
from itertools import permutations
from pathlib import Path
from typing import Any

EVALUATION = Path(__file__).resolve().parent
NO_SEARCH_ACTIONS = {"AWAIT_SYSTEM", "NOTICE_OUT_OF_SCOPE"}
NO_SEARCH_INTENTS = {"FEATURE_REQUEST", "IMPROVEMENT_REQUEST"}


def _load(folder: str, scenario_id: str) -> dict[str, Any]:
    return json.loads((EVALUATION / folder / f"{scenario_id}.json").read_text(encoding="utf-8"))


def final_state(trace: dict[str, Any], follow_up_text: str | None = None) -> dict[str, Any]:
    """실행 기록에서 문제별 의도·보고 시스템·종결 행동을 뽑는다.

    문제는 처음 나온 순서로 두고 값은 가장 최근 제안을 쓴다. 임시 참조(`new:1`)는 실행(턴)마다 새로
    붙으므로 그 턴의 `ref_map`으로만 저장된 문제 ID에 맞춘다(`harness/checks.py`의 `_canonical`과
    같다). 안내와 티켓 제안은 그 문제의 종결이며 둘 다 있으면 뒤에 나온 것이 종결이다.

    안내 뒤 미해결 응답(`followUp`)이 재생된 문제는 다시 열린 것이다. 그 뒤의 실행에서 나온 종결만
    `afterAction`으로 따로 두고, 그 뒤가 실패했거나 끝나지 않았으면 앞선 안내를 종결로 쓰지 않는다.
    결과 없이 예외로 끝난 실행(`result: null`)은 재시도할 수 없는 실패로 본다.
    """
    turns = trace["turns"]
    results = [turn.get("result") or {} for turn in turns]
    maps = [dict(turn.get("ref_map") or []) for turn in turns]
    follow_index = next(
        (
            index
            for index, turn in enumerate(turns)
            if follow_up_text and turn.get("user_reply") == follow_up_text
        ),
        None,
    )

    systems = {s["id"]: s["code"] for s in turns[-1]["request"]["systems"]}
    order: list[str] = []
    info: dict[str, dict[str, Any]] = {}
    actions: dict[str, str] = {}
    after_actions: dict[str, str] = {}
    guided_before_follow_up: set[str] = set()
    for index, result in enumerate(results):
        mapping = maps[index]
        reopened = follow_index is not None and index > follow_index
        for proposal in result.get("problemProposals") or []:
            key = mapping.get(proposal["problemRef"], proposal["problemRef"])
            if key not in info:
                order.append(key)
            info[key] = {
                "intent": proposal.get("intent"),
                "system": systems.get(proposal.get("reportedSystemCandidateId")),
            }
        terminal: list[tuple[str, str]] = []
        guide = result.get("guide")
        if guide and guide.get("problemRef"):
            terminal.append((guide["problemRef"], "ANSWER_GUIDE"))
        terminal += [
            (ticket["problemRef"], "PROPOSE_TICKET")
            for ticket in result.get("ticketProposals") or []
        ]
        for ref, action in terminal:
            key = mapping.get(ref, ref)
            actions[key] = action
            if reopened:
                after_actions[key] = action
            elif action == "ANSWER_GUIDE" and follow_index is not None:
                guided_before_follow_up.add(key)

    last = results[-1]
    failed = last.get("status") != "SUCCEEDED"
    error = last.get("error") or {}
    fail_action = "FAIL_RETRYABLE" if error.get("retryable") else "FAILED"
    unfinished = fail_action if failed else "AWAIT_ANSWER"
    problems = []
    for key in order:
        reopened_problem = key in guided_before_follow_up
        if key in actions:
            action = actions[key]
        elif info[key]["intent"] == "OUT_OF_SCOPE":
            action = "NOTICE_OUT_OF_SCOPE"
        elif failed:
            action = fail_action
        elif info[key]["system"] is None:
            action = "AWAIT_SYSTEM"
        else:
            action = "AWAIT_ANSWER"
        problems.append(
            {
                "key": key,
                **info[key],
                "action": after_actions.get(key, unfinished) if reopened_problem else action,
                "reopened": reopened_problem,
            }
        )
    return {
        "problems": problems,
        # 문제를 하나도 만들지 못하고 끝난 실행(검색 오류 주입 등)의 종결
        "scenarioAction": None if order else (fail_action if failed else "NONE"),
        "followUpReplayed": follow_index is not None,
        "status": last.get("status") or "FAILED",
        "error": error.get("code") or (turns[-1].get("error") if failed else None),
        "roles": [call["role"] for turn in turns for call in turn.get("role_calls") or []],
        "turns": len(turns),
        "turnsOk": sum(1 for result in results if result.get("status") == "SUCCEEDED"),
        "modelCalls": sum(turn.get("model_calls", 0) for turn in turns),
        "seconds": sum(turn.get("elapsed_seconds", 0.0) for turn in turns),
        "inputTokens": sum(turn.get("input_tokens", 0) for turn in turns),
        "outputTokens": sum(turn.get("output_tokens", 0) for turn in turns),
    }


def _judge(gold: dict[str, Any], produced: dict[str, Any] | None, state: dict[str, Any]) -> dict:
    row: dict[str, Any] = {
        "problemKey": gold["problemKey"],
        "goldIntents": gold["acceptedIntents"],
        "goldSystem": gold["reportedSystemCode"],
        "goldActions": gold["allowedFinalActions"],
    }
    if produced is None:
        action = state["scenarioAction"] or "MISSING"
        return {
            **row,
            "intent": None,
            "system": None,
            "action": action,
            "intentOk": None,
            "systemOk": None,
            "actionOk": action in gold["allowedFinalActions"],
        }
    after = gold.get("afterFollowUp")
    if after and produced["reopened"]:
        # 안내가 먼저 나갔고, 미해결 응답 뒤에 실제로 나온 종결을 afterFollowUp으로 본다.
        action_ok = (
            "ANSWER_GUIDE" in gold["allowedFinalActions"]
            and produced["action"] in after["allowedFinalActions"]
        )
        row["goldActions"] = ["ANSWER_GUIDE 뒤 " + "|".join(after["allowedFinalActions"])]
    else:
        action_ok = produced["action"] in gold["allowedFinalActions"]
    return {
        **row,
        "intent": produced["intent"],
        "system": produced["system"],
        "action": produced["action"],
        "intentOk": produced["intent"] in gold["acceptedIntents"],
        "systemOk": produced["system"] == gold["reportedSystemCode"],
        "actionOk": action_ok,
    }


def _expects_search(gold: dict[str, Any]) -> bool:
    """경로 정책: 시스템 미확인·범위 밖과 기능 추가·개선 요청은 검색하지 않고 나머지는 검색한다."""
    if set(gold["allowedFinalActions"]) <= NO_SEARCH_ACTIONS:
        return False
    return not set(gold["acceptedIntents"]) <= NO_SEARCH_INTENTS


def score_trace(
    trace: dict[str, Any], gold: dict[str, Any], follow_up_text: str | None = None
) -> dict[str, Any]:
    """한 실행 기록을 정답과 대조한다.

    정답 문제(p1, p2)와 실행이 만든 문제는 순서가 아니라 가장 잘 맞는 대응으로 짝짓는다(보고 시스템,
    의도, 종결 행동 순으로 우선). 모델이 문제를 다른 순서로 내도 같은 문제끼리 비교하기 위해서다.
    """
    state = final_state(trace, follow_up_text)
    golds = gold["problems"]
    produced: list[dict[str, Any] | None] = list(state["problems"])
    produced += [None] * max(0, len(golds) - len(produced))
    rows: list[dict[str, Any]] = []
    best = -1
    for order in permutations(range(len(produced)), len(golds)):
        candidate = [
            _judge(g, produced[index], state) for g, index in zip(golds, order, strict=True)
        ]
        fit = sum(
            (row["systemOk"] is True) * 4 + (row["intentOk"] is True) * 2 + row["actionOk"]
            for row in candidate
        )
        if fit > best:
            rows, best = candidate, fit

    made = len(state["problems"])
    count_ok = made == len(golds) or (made == 0 and state["scenarioAction"] != "NONE")
    follow = state["followUpReplayed"]
    want_search = any(_expects_search(g) for g in golds)
    called_search = "KNOWLEDGE" in state["roles"]
    called_ticket = "TICKET" in state["roles"]
    path_checks = [want_search == called_search]
    if any(g["allowedFinalActions"] == ["PROPOSE_TICKET"] for g in golds):
        path_checks.append(called_ticket)
    if all(
        "PROPOSE_TICKET" not in g["allowedFinalActions"] and not (follow and g.get("afterFollowUp"))
        for g in golds
    ):
        path_checks.append(not called_ticket)
    return {
        "scenarioId": trace["scenario_id"],
        "taskOk": count_ok and all(row["actionOk"] for row in rows),
        "problemCountOk": count_ok,
        "producedProblems": made,
        "goldProblems": len(golds),
        "pathOk": all(path_checks),
        "rows": rows,
        **{
            key: state[key]
            for key in (
                "status",
                "error",
                "turns",
                "turnsOk",
                "modelCalls",
                "seconds",
                "inputTokens",
                "outputTokens",
            )
        },
    }


def macro_f1(pairs: list[tuple[str | None, list[str]]]) -> tuple[float, dict[str, float]]:
    """의도 분류의 Macro-F1.

    정답이 허용 집합이므로 예측이 집합 안이면 그 값을, 아니면 집합의 첫 값을 정답 라벨로 둔다.
    """
    tp: Counter[str] = Counter()
    fp: Counter[str] = Counter()
    fn: Counter[str] = Counter()
    for predicted, accepted in pairs:
        label = predicted or "NONE"
        truth = label if label in accepted else accepted[0]
        if label == truth:
            tp[truth] += 1
        else:
            fp[label] += 1
            fn[truth] += 1
    per: dict[str, float] = {}
    for label in sorted(set(tp) | set(fp) | set(fn)):
        precision = tp[label] / (tp[label] + fp[label]) if tp[label] + fp[label] else 0.0
        recall = tp[label] / (tp[label] + fn[label]) if tp[label] + fn[label] else 0.0
        total = precision + recall
        per[label] = round(2 * precision * recall / total, 3) if total else 0.0
    return (round(sum(per.values()) / len(per), 3) if per else 0.0), per


def summarize(scored: list[dict[str, Any]]) -> dict[str, Any]:
    """건수와 분모를 함께 낸다(`[맞은 수, 전체]`). 소수 표본을 비율만으로 보고하지 않기 위해서다."""
    rows = [row for item in scored for row in item["rows"]]
    intents = [row for row in rows if row["intentOk"] is not None]
    systems = [row for row in intents if row["goldSystem"] is not None]
    f1, per_label = macro_f1([(row["intent"], row["goldIntents"]) for row in intents])
    succeeded = sum(item["taskOk"] for item in scored)
    tokens = sum(item["inputTokens"] + item["outputTokens"] for item in scored)
    return {
        "scenarios": len(scored),
        "taskSuccess": [succeeded, len(scored)],
        "problemActionOk": [sum(row["actionOk"] for row in rows), len(rows)],
        "problemCountOk": [sum(item["problemCountOk"] for item in scored), len(scored)],
        "pathOk": [sum(item["pathOk"] for item in scored), len(scored)],
        "turnsOk": [sum(item["turnsOk"] for item in scored), sum(item["turns"] for item in scored)],
        "intentAccuracy": [sum(row["intentOk"] for row in intents), len(intents)],
        "intentMacroF1": f1,
        "intentF1PerLabel": per_label,
        "systemAccuracy": [sum(row["systemOk"] for row in systems), len(systems)],
        "modelCalls": sum(item["modelCalls"] for item in scored),
        "seconds": round(sum(item["seconds"] for item in scored), 1),
        "inputTokens": sum(item["inputTokens"] for item in scored),
        "outputTokens": sum(item["outputTokens"] for item in scored),
        "tokensPerSuccess": round(tokens / succeeded) if succeeded else None,
    }


def score_dirs(dirs: list[Path]) -> dict[str, Any]:
    """디렉터리마다 채점하고, 같은 시나리오가 여러 디렉터리에 있으면 반복 결과로 모은다."""
    runs = []
    repeats: dict[str, list[bool]] = defaultdict(list)
    everything: list[dict[str, Any]] = []
    for directory in dirs:
        scored = []
        for file in sorted(directory.glob("*.trace.json")):
            trace = json.loads(file.read_text(encoding="utf-8"))
            scenario_id = trace["scenario_id"]
            follow_up = (_load("scenarios", scenario_id).get("followUp") or {}).get("text")
            item = score_trace(trace, _load("golds", scenario_id), follow_up)
            scored.append(item)
            repeats[scenario_id].append(item["taskOk"])
        everything.extend(scored)
        runs.append({"dir": str(directory), "summary": summarize(scored), "scenarios": scored})
    return {
        "runs": runs,
        "total": summarize(everything),
        "repeats": {key: [sum(oks), len(oks)] for key, oks in sorted(repeats.items())},
    }


def _print_text(report: dict[str, Any]) -> None:
    for run in report["runs"]:
        print(f"\n## {run['dir']}")
        print(json.dumps(run["summary"], ensure_ascii=False))
        for item in run["scenarios"]:
            bad = [
                row
                for row in item["rows"]
                if not row["actionOk"] or row["intentOk"] is False or row["systemOk"] is False
            ]
            if not bad and item["problemCountOk"] and item["pathOk"]:
                continue
            print(
                f"  - {item['scenarioId']}: "
                f"문제 {item['producedProblems']}/{item['goldProblems']}, "
                f"상태 {item['status']} {item['error'] or ''}, 경로 일치 {item['pathOk']}"
            )
            for row in bad:
                print(
                    f"      {row['problemKey']}: 의도 {row['intent']} (정답 {row['goldIntents']}), "
                    f"시스템 {row['system']} (정답 {row['goldSystem']}), "
                    f"종결 {row['action']} (정답 {row['goldActions']})"
                )
    if len(report["runs"]) > 1:
        print("\n## 전체 합계")
        print(json.dumps(report["total"], ensure_ascii=False))
        print("\n## 시나리오별 반복 (성공 횟수/반복)")
        for scenario_id, (ok, total) in report["repeats"].items():
            mark = "" if ok in (0, total) else "  <- 회차마다 다름"
            print(f"  {scenario_id}: {ok}/{total}{mark}")


def main(argv: list[str] | None = None) -> int:
    args = list(argv if argv is not None else sys.argv[1:])
    as_json = "--json" in args
    dirs = [Path(item) for item in args if item != "--json"]
    if not dirs or any(not directory.is_dir() for directory in dirs):
        print("사용법: python -m evaluation.score [--json] <trace 디렉터리>...", file=sys.stderr)
        return 2
    report = score_dirs(dirs)
    if as_json:
        print(json.dumps(report, ensure_ascii=False))
    else:
        _print_text(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
