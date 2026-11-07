"""Curator를 시나리오마다 N회 재생하고 성질별 통과율을 보고하는 실행 도구.

pytest는 이 모듈의 `main`을 부르지 않는다. 실제 모델 호출은 사용자가 모델·호출 상한·비용을
승인한 뒤 직접
실행한다. 키는 환경변수 `OPENAI_API_KEY`로만 받는다.

    cd ai
    OPENAI_API_KEY=... uv run python -m evaluation.curator.pilot --split DEV --repeat 5 \
        --max-calls 80 --out /tmp/curator-dev.json --trace-dir /tmp/curator-traces

- 전체 모델 호출 상한(`--max-calls`)에 닿으면 호출을 멈추고 못 돈 실행을 건너뜀으로 센다.
  실행 하나가 쓸 수 있는 최대 호출(재시도 포함)이 남아 있을 때만 실행을 시작한다.
- `TEST` 시나리오는 동결 자료이므로 `--allow-test`를 주지 않으면 돌리지 않는다.
- 후보 노드는 코퍼스 선언에서 만든 평가 대역이다. 실제 Spring 조회가 아니다.
- 이 결과는 소수 표본이다. 일반 성능이나 서비스 통합 검증으로 쓰지 않는다.
"""

import argparse
import asyncio
import json
import os
import subprocess
import sys
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from evaluation.curator.candidates import CatalogNode, load_catalog
from evaluation.curator.checks import check_behavior, check_trace
from evaluation.curator.gold import CuratorGold
from evaluation.curator.loader import load_all, select
from evaluation.curator.runner import run_case
from evaluation.curator.scenario import CuratorScenario
from evaluation.curator.trace import CaseTrace
from evaluation.curator.trace_io import save_trace
from evaluation.harness.budget import CallCap, CappedLLM
from hops_ai.agents.curator import drafting, link
from hops_ai.services.llm.client import LLMClient

# 실행 하나가 쓸 수 있는 최대 모델 호출: 초안 1 + link 1, 각각 일시 오류 재시도 최대 2회(Spring
# 한도와 같다)
MAX_CALLS_PER_RUN = 6
PROMPT_VERSIONS = (drafting.PROMPT_VERSION, link.PROMPT_VERSION)
EVALUATION_ROOT = Path(__file__).resolve().parents[1]


def summarize_run(
    trace: CaseTrace,
    gold: CuratorGold,
    keys: dict[str, str],
    index: int,
) -> dict[str, Any]:
    """실행 하나의 보고. 판정에 쓴 값은 여기 있고 원본은 trace 파일에 있다."""
    result = trace.result
    violations = [
        {"property": item.property_id, "message": item.message} for item in check_trace(trace)
    ]
    findings, passed = check_behavior(trace, gold, keys)
    report: dict[str, Any] = {
        "run": index,
        "status": result.status if result else f"ERROR:{trace.error}",
        "modelCalls": trace.model_calls,
        "inputTokens": trace.input_tokens,
        "outputTokens": trace.output_tokens,
        "seconds": round(trace.elapsed_seconds, 1),
        "safetyViolations": violations,
        "behavior": passed,
        "behaviorFindings": [
            {"property": item.property_id, "message": item.message} for item in findings
        ],
    }
    if result is not None and result.error is not None:
        report["error"] = {"code": result.error.code, "message": result.error.message}
        if trace.failure_detail:
            report["failureDetail"] = trace.failure_detail
    proposal = result.caseProposal if result and result.status == "SUCCEEDED" else None
    if proposal is not None:
        by_id = {node: key for key, node in keys.items()}
        report["reusable"] = proposal.reusable
        if proposal.reusable:
            report["case"] = {
                "title": proposal.title,
                "problemStatement": proposal.problemStatement,
                "applicability": proposal.applicability,
                "limitations": list(proposal.limitations),
                "links": [
                    {
                        "relation": entry.relation,
                        "target": by_id.get(entry.targetId, entry.targetId),
                        "evidence": len(entry.evidenceIds),
                    }
                    for entry in proposal.entityLinks
                ],
            }
    return report


def summarize_scenario(
    scenario: CuratorScenario, gold: CuratorGold, runs: list[dict[str, Any]]
) -> dict[str, Any]:
    succeeded = [run for run in runs if run["status"] == "SUCCEEDED"]
    behavior: dict[str, dict[str, int]] = {}
    for run in succeeded:
        for name, ok in run["behavior"].items():
            counts = behavior.setdefault(name, {"passed": 0, "total": 0})
            counts["total"] += 1
            counts["passed"] += 1 if ok else 0
    return {
        "scenarioId": scenario.scenario_id,
        "caseFamily": scenario.case_family,
        "split": scenario.split,
        "runs": runs,
        "summary": {
            "runs": len(runs),
            "succeeded": len(succeeded),
            "failed": len(runs) - len(succeeded),
            "reusableRuns": sum(1 for run in succeeded if run.get("reusable")),
            "safetyViolationRuns": sum(1 for run in runs if run["safetyViolations"]),
            "behavior": behavior,
        },
    }


def aggregate(cap: CallCap, scenarios: list[dict[str, Any]], skipped: int) -> dict[str, Any]:
    runs = [run for entry in scenarios for run in entry["runs"]]
    return {
        "scenarios": scenarios,
        "totals": {
            "scenarios": len(scenarios),
            "runs": len(runs),
            "succeeded": sum(1 for run in runs if run["status"] == "SUCCEEDED"),
            "failed": sum(1 for run in runs if run["status"] != "SUCCEEDED"),
            "skipped": skipped,
            "modelCalls": sum(run["modelCalls"] for run in runs),
            "inputTokens": sum(run["inputTokens"] for run in runs),
            "outputTokens": sum(run["outputTokens"] for run in runs),
            "safetyViolations": sum(len(run["safetyViolations"]) for run in runs),
        },
        "totalCallCap": {"max": cap.max_calls, "used": cap.used, "exceeded": cap.exceeded},
        "promptVersionsSeen": sorted(cap.prompt_versions),
    }


async def run_batch(
    pairs: list[tuple[CuratorScenario, CuratorGold]],
    *,
    llm: LLMClient,
    repeat: int,
    max_calls: int,
    catalog: dict[str, CatalogNode] | None = None,
    on_start: Callable[[str, int], None] | None = None,
    on_trace: Callable[[CaseTrace, int], None] | None = None,
) -> dict[str, Any]:
    """시나리오마다 `repeat`회 재생한다. 전체 호출은 `max_calls`를 넘지 않는다."""
    nodes = catalog or load_catalog()
    keys = {key: node.id for key, node in nodes.items()}
    cap = CallCap(max_calls)
    capped = CappedLLM(llm, cap)
    reports: list[dict[str, Any]] = []
    skipped = 0
    for scenario, gold in pairs:
        runs: list[dict[str, Any]] = []
        for index in range(1, repeat + 1):
            if max_calls - cap.used < MAX_CALLS_PER_RUN:
                cap.exceeded = True
                skipped += 1
                continue
            if on_start is not None:
                on_start(scenario.scenario_id, index)
            trace = await run_case(scenario, nodes, llm=capped)
            if on_trace is not None:
                on_trace(trace, index)
            runs.append(summarize_run(trace, gold, keys, index))
        reports.append(summarize_scenario(scenario, gold, runs))
    return aggregate(cap, reports, skipped)


def run_metadata(model: str, repeat: int, max_calls: int, split: str | None) -> dict[str, Any]:
    def git(*args: str) -> str | None:
        try:
            done = subprocess.run(
                ["git", *args], cwd=EVALUATION_ROOT, capture_output=True, text=True, timeout=10
            )
        except (OSError, subprocess.SubprocessError):
            return None
        return done.stdout.strip() if done.returncode == 0 else None

    return {
        "startedAt": datetime.now(UTC).isoformat(timespec="seconds"),
        "gitCommit": git("rev-parse", "HEAD"),
        # 커밋되지 않은 변경이 있으면 그 코드로 돌린 것이므로 기록한다
        "gitDirty": bool(git("status", "--porcelain")),
        "model": model,
        "promptVersions": list(PROMPT_VERSIONS),
        "repeat": repeat,
        "maxCalls": max_calls,
        "split": split,
        "linkCandidates": "코퍼스 선언에서 만든 평가 대역(Spring 조회가 아니다)",
    }


def exit_code(report: dict[str, Any]) -> int:
    """안전 위반·실패한 실행·건너뜀·호출 상한 도달이 있으면 0이 아니다."""
    totals = report["totals"]
    incomplete = (
        totals["safetyViolations"]
        or totals["failed"]
        or totals["skipped"]
        or report["totalCallCap"]["exceeded"]
    )
    return 1 if incomplete else 0


def _log(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


def _build_real(model: str | None) -> tuple[LLMClient, str]:
    from hops_ai.services.llm.openai import OpenAIClient
    from hops_ai.settings import Settings

    key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not key:
        raise SystemExit("OPENAI_API_KEY 환경변수가 없다. 실제 모델을 호출하지 않고 끝낸다.")
    name = model or Settings(_env_file=None).supervisor_model
    return OpenAIClient(key, name), name


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Curator를 시나리오마다 N회 실제 모델로 재생한다")
    parser.add_argument("caseFamilies", nargs="*", help="예: ea-doc-no-permission")
    parser.add_argument("--split", choices=("DEV", "TEST"), default=None)
    parser.add_argument("--allow-test", action="store_true", help="동결된 TEST 시나리오를 허용")
    parser.add_argument("--repeat", type=int, default=5, help="시나리오당 반복 횟수")
    parser.add_argument("--max-calls", type=int, required=True, help="전체 모델 호출 상한")
    parser.add_argument("--model", default=None, help="기본값은 설정의 supervisor_model")
    parser.add_argument("--out", type=Path, default=None, help="보고서를 JSON 파일로도 저장")
    parser.add_argument("--trace-dir", type=Path, default=None, help="원본 실행 기록 저장 위치")
    args = parser.parse_args(argv)
    if args.repeat < 1 or args.max_calls < 1:
        parser.error("--repeat와 --max-calls는 1 이상이어야 한다")

    try:
        pairs = select(load_all(), args.caseFamilies, args.split, allow_test=args.allow_test)
    except ValueError as error:
        parser.error(str(error))
    llm, model = _build_real(args.model)
    run_info = run_metadata(model, args.repeat, args.max_calls, args.split)
    try:
        report = asyncio.run(
            run_batch(
                pairs,
                llm=llm,
                repeat=args.repeat,
                max_calls=args.max_calls,
                on_start=lambda sid, index: _log(f"시작: {sid} #{index}"),
                on_trace=(lambda trace, index: save_trace(trace, args.trace_dir, index))
                if args.trace_dir
                else None,
            )
        )
    except KeyboardInterrupt:
        _log("중단됨. 저장된 기록까지만 남는다.")
        return 130
    report["run"] = {**run_info, "finishedAt": datetime.now(UTC).isoformat(timespec="seconds")}
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.out:
        args.out.write_text(text + "\n", encoding="utf-8")
    return exit_code(report)


if __name__ == "__main__":
    sys.exit(main())
