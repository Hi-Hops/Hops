"""실제 모델로 시나리오를 한 번씩 재생하고 호출·토큰·위반을 보고한다.

pytest는 이 모듈의 `main`을 부르지 않는다. 실제 모델 호출은 사용자가 상한과 모델을 승인한 뒤
직접 실행한다.

    cd ai
    # 시나리오 하나
    OPENAI_API_KEY=... uv run python -m evaluation.pilot ea-search-period --max-calls 30
    # 개발용 시나리오 전체를 한 번씩(전체 상한 150, 시나리오당 30)
    OPENAI_API_KEY=... uv run python -m evaluation.pilot --split DEV --max-calls 150

- 전체 모델 호출 상한(`--max-calls`)과 시나리오당 상한(`--per-scenario-calls`)에 닿으면 호출을
  멈추고 그 사실을 보고한다. 상한에 닿아 못 돌린 시나리오는 "SKIPPED"로 남긴다.
- `TEST` 시나리오는 동결 자료이므로 `--allow-test`를 주지 않으면 돌리지 않는다.
- 지식 검색은 개발용 고정 자료(`FIXTURE`)다. 가상 기업 문서(#191)가 없으므로 정답의
  `relevantEvidence`는 채점하지 않고, 기록만으로 판정되는 안전 성질만 본다.
- 어댑터(`OpenAIClient`)는 응답 저장을 요청한다. 시나리오는 합성 데이터이며 개인정보가 없다.
- 이 결과는 정리 전 구조의 시범 측정이다. 소수 실행이므로 일반 성능으로 보고하지 않는다.
"""

import argparse
import asyncio
import json
import os
import subprocess
import sys
from collections.abc import Callable, Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from evaluation.harness.behavior import check_behavior
from evaluation.harness.budget import CallCap, CappedLLM
from evaluation.harness.catalog import SYSTEMS
from evaluation.harness.checks import check_trace
from evaluation.harness.corpus_relations import CorpusRelationSource
from evaluation.harness.corpus_source import CorpusKnowledgeSource
from evaluation.harness.runner import RunConfig, run_scenario
from evaluation.harness.scenario import Scenario, load_scenario
from evaluation.harness.spring_stub import DEFAULT_LIMITS
from evaluation.harness.trace import RunTrace, TurnTrace
from evaluation.harness.trace_io import save_trace
from hops_ai.agents.base import Agent
from hops_ai.contracts.domain import AgentRole
from hops_ai.services.llm.client import LLMClient

SCENARIO_DIR = Path(__file__).resolve().parent / "scenarios"
SYSTEM_CODE_BY_ID = {system["id"]: system["code"] for system in SYSTEMS}
DEFAULT_PER_SCENARIO_CALLS = 30
# 시나리오 하나가 이 시간(초)을 넘으면 그때까지의 결과만 남기고 다음으로 넘어간다.
DEFAULT_SCENARIO_TIMEOUT = 240.0


def _problem_summary(proposal: Any) -> dict[str, Any]:
    """AI가 이번 실행에서 만든 문제 제안. 확보한 값과 미확인을 정답과 사람이 대조하는 데 쓴다."""
    details = proposal.details.model_dump(mode="json", exclude={"acceptanceCriteria"})
    return {
        "problemRef": proposal.problemRef,
        "intent": proposal.intent,
        "processingType": proposal.processingType,
        "reportedSystem": SYSTEM_CODE_BY_ID.get(proposal.reportedSystemCandidateId or ""),
        "details": {name: value for name, value in details.items() if value is not None},
        "facts": [fact.text for fact in proposal.facts],
        "unknowns": list(proposal.unknowns),
    }


def _turn_summary(index: int, turn: TurnTrace) -> dict[str, Any]:
    result = turn.result
    summary: dict[str, Any] = {
        "turn": index,
        "status": result.status if result is not None else f"ERROR:{turn.error}",
        "modelCalls": turn.model_calls,
        "inputTokens": turn.input_tokens,
        "outputTokens": turn.output_tokens,
        "seconds": round(turn.elapsed_seconds, 2),
        "roles": [
            f"{call.role.value}:{call.search_state}"
            if call.search_state
            else f"{call.role.value}{':FAILED' if call.failed_with else ''}"
            for call in turn.role_calls
        ],
    }
    failures = [
        {"role": call.role.value, "type": call.failed_with, "detail": call.failure_detail}
        for call in turn.role_calls
        if call.failed_with
    ]
    if failures:
        summary["roleFailures"] = failures
    relations = [
        {
            "problem": call.problem_ref,
            "state": call.relation_state,
            "checkItems": list(call.relation_check_ids),
        }
        for call in turn.role_calls
        if call.relation_state
    ]
    if relations:
        summary["relations"] = relations
    sources = [
        {"source": call.question_source, "fallback": call.question_fallback}
        for call in turn.role_calls
        if call.question_source
    ]
    if sources:
        summary["questionSources"] = sources
    if result is not None:
        summary["questions"] = [
            {"problemRef": q.problemRef, "fields": q.requestedFields, "text": q.text}
            for q in result.questions
        ]
        summary["problems"] = [_problem_summary(item) for item in result.problemProposals]
        summary["answer"] = result.answer
        summary["guide"] = result.guide.messageText if result.guide else None
        summary["ticketProposals"] = len(result.ticketProposals)
        summary["evidenceCount"] = len(result.evidence)
        summary["error"] = result.error.model_dump(mode="json") if result.error else None
    hints = _search_hints(turn)
    if hints:
        summary["searchHints"] = hints
    assessments = _assessments(turn)
    if assessments:
        summary["assessments"] = assessments
    summary["userReply"] = turn.user_reply
    return summary


def _assessments(turn: TurnTrace) -> dict[str, Any]:
    """검색 호출별 충분성 판정 요약. 평가를 하지 못한 검색도 센다.

    foundWithoutAssessment는 근거가 있는데 판정이 없는 검색 수다. 모델 오류, 검증 거부, 예산
    부족을 구분하지 않고 "평가 없음"으로 묶는다.
    """
    searches = [
        call
        for call in turn.role_calls
        if call.role == AgentRole.KNOWLEDGE and call.search_state in ("FOUND", "EMPTY")
    ]
    if not searches:
        return {}
    return {
        "items": [
            {
                "problemRef": call.assessment.problem_ref,
                "verdict": call.assessment.verdict,
                "supportingEvidence": len(call.assessment.supporting_evidence_ids),
                "missingInformation": [ref for _, ref in call.assessment.missing_information],
                "candidateCauses": len(call.assessment.candidate_causes),
                "rationale": call.assessment.rationale,
            }
            for call in searches
            if call.assessment is not None
        ],
        "foundWithoutAssessment": sum(
            1 for call in searches if call.search_state == "FOUND" and call.assessment is None
        ),
    }


def _search_hints(turn: TurnTrace) -> list[dict[str, Any]]:
    """문의 분석이 낸 검색 힌트를 사람이 읽기 쉽게 정리한다. 키워드 품질을 눈으로 확인하는 데 쓴다.

    모델 원본에서 규칙이 버린 값(없는 단어·허용 밖 기능 등)을 함께 보여 규칙이 얼마나 걸러냈는지
    알 수 있다. 기능은 ID 대신 이름으로 적는다.
    """
    names = {
        feature.id: feature.name for system in turn.request.systems for feature in system.features
    }
    hints = []
    for call in turn.role_calls:
        for item in call.analysis:
            hints.append(
                {
                    "problemRef": item.problem_ref,
                    "keywords": list(item.keywords),
                    "droppedKeywords": [k for k in item.raw_keywords if k not in item.keywords],
                    "features": [names.get(f, f) for f in item.feature_candidates],
                    "droppedFeatures": [
                        names.get(f, f)
                        for f in item.raw_feature_candidates
                        if f not in item.feature_candidates
                    ],
                }
            )
    return hints


def summarize(trace: RunTrace, cap: CallCap) -> dict[str, Any]:
    violations = check_trace(trace)
    behavior = check_behavior(trace)
    return {
        "scenarioId": trace.scenario_id,
        "callCap": {"max": cap.max_calls, "used": cap.used, "exceeded": cap.exceeded},
        "promptVersions": sorted(cap.prompt_versions),
        # 질문이 남은 채 최대 턴 수에서 잘렸으면 끝까지 재생한 결과가 아니다
        "truncated": trace.truncated,
        # 실행(턴) 중 요청의 maxModelCalls 같은 실행별 예산을 소진해 실패한 적이 있다
        "runBudgetExceeded": any(
            turn.result is not None
            and turn.result.error is not None
            and turn.result.error.code == "BUDGET_EXCEEDED"
            for turn in trace.turns
        ),
        "totals": {
            "turns": len(trace.turns),
            "modelCalls": sum(turn.model_calls for turn in trace.turns),
            "inputTokens": sum(turn.input_tokens for turn in trace.turns),
            "outputTokens": sum(turn.output_tokens for turn in trace.turns),
            "seconds": round(sum(turn.elapsed_seconds for turn in trace.turns), 2),
        },
        "turns": [_turn_summary(index, turn) for index, turn in enumerate(trace.turns, start=1)],
        "safetyViolations": [
            {"property": v.property_id, "turn": v.turn, "message": v.message} for v in violations
        ],
        # 행동 성질의 관찰. 안전 위반이 아니므로 종료 코드에 넣지 않고 반복 통과율로 본다
        "behaviorFindings": [
            {"property": v.property_id, "turn": v.turn, "message": v.message} for v in behavior
        ],
    }


async def run_pilot(
    scenario: Scenario,
    *,
    llm: LLMClient,
    agents: Mapping[AgentRole, Agent],
    max_calls: int,
    config: RunConfig | None = None,
    timeout_seconds: float | None = None,
    on_turn: Callable[[int, TurnTrace], None] | None = None,
    on_trace: Callable[[RunTrace], None] | None = None,
) -> dict[str, Any]:
    """전체 호출 상한을 적용해 시나리오를 재생하고 보고서(dict)를 돌려준다.

    `timeout_seconds`를 넘기면 그 시점까지의 턴만 보고하고 `timedOut`을 남긴다. 진행 중이던 호출은
    상한에는 이미 계산되어 있으나 토큰은 알 수 없다.
    """
    cap = CallCap(max_calls)
    turns: list[TurnTrace] = []

    def collect(index: int, turn: TurnTrace) -> None:
        turns.append(turn)
        if on_turn is not None:
            on_turn(index, turn)

    run = run_scenario(
        scenario, llm=CappedLLM(llm, cap), agents=agents, config=config, on_turn=collect
    )
    timed_out = False
    try:
        trace = await asyncio.wait_for(run, timeout_seconds)
    except TimeoutError:
        timed_out = True
        trace = RunTrace(scenario_id=scenario.scenario_id, turns=tuple(turns))

    if on_trace is not None:
        on_trace(trace)  # 보고서 요약 전에 원본 기록을 넘긴다(저장용)
    report = summarize(trace, cap)
    if timed_out:
        report["timedOut"] = timeout_seconds
        # 시간 초과로 취소된 진행 중 호출은 끝난 턴의 기록에 없다. 시도 수의 정본은 callCap.used다.
        report["totals"]["modelCallsInFlight"] = cap.used - report["totals"]["modelCalls"]
    return report


class FaultyKnowledgeSource:
    """검색 서비스 오류를 주입한다(시나리오 `faults`의 `RETRIEVAL_ERROR`)."""

    async def search(self, query):
        from hops_ai.harness.errors import RetrievalUnavailable

        raise RetrievalUnavailable()


def _count_questions(reports: list[dict[str, Any]], source: str) -> int:
    return sum(
        1
        for report in reports
        for turn in report["turns"]
        for item in turn.get("questionSources", [])
        if item["source"] == source
    )


def aggregate(total: CallCap, reports: list[dict[str, Any]]) -> dict[str, Any]:
    ran = [report for report in reports if "skipped" not in report]
    return {
        "totalCallCap": {"max": total.max_calls, "used": total.used, "exceeded": total.exceeded},
        "promptVersions": sorted({v for r in ran for v in r["promptVersions"]}),
        "totals": {
            "scenarios": len(ran),
            "skipped": len(reports) - len(ran),
            "modelCalls": sum(r["totals"]["modelCalls"] for r in ran),
            "inputTokens": sum(r["totals"]["inputTokens"] for r in ran),
            "outputTokens": sum(r["totals"]["outputTokens"] for r in ran),
            "seconds": round(sum(r["totals"]["seconds"] for r in ran), 2),
            "safetyViolations": sum(len(r["safetyViolations"]) for r in ran),
            # 모델이 쓴 질문 문장 수와, 실패하거나 규칙을 어겨 고정 문구로 대체된 횟수
            "modelQuestions": _count_questions(ran, "MODEL"),
            "questionFallbacks": _count_questions(ran, "FIXED_FALLBACK"),
            "behaviorFindings": sum(len(r["behaviorFindings"]) for r in ran),
            "scenariosHittingTheirCap": [r["scenarioId"] for r in ran if r["callCap"]["exceeded"]],
            "scenariosTimedOut": [r["scenarioId"] for r in ran if "timedOut" in r],
            "scenariosTruncated": [r["scenarioId"] for r in ran if r["truncated"]],
            "scenariosOverRunBudget": [r["scenarioId"] for r in ran if r["runBudgetExceeded"]],
            # 시간 초과로 취소된 호출. 시도 수의 정본은 totalCallCap.used다
            "modelCallsInFlight": sum(r["totals"].get("modelCallsInFlight", 0) for r in ran),
        },
        "scenarios": reports,
    }


async def run_batch(
    scenarios: list[Scenario],
    *,
    llm: LLMClient,
    agents_for: Callable[[Scenario], Mapping[AgentRole, Agent]],
    max_calls: int,
    per_scenario_calls: int = DEFAULT_PER_SCENARIO_CALLS,
    scenario_timeout: float | None = DEFAULT_SCENARIO_TIMEOUT,
    config: RunConfig | None = None,
    on_start: Callable[[int, int, Scenario], None] | None = None,
    on_turn: Callable[[str, int, TurnTrace], None] | None = None,
    on_report: Callable[[dict[str, Any]], None] | None = None,
    on_trace: Callable[[RunTrace], None] | None = None,
) -> dict[str, Any]:
    """시나리오를 차례로 한 번씩 재생한다. 전체 호출은 `max_calls`를 넘지 않는다.

    콜백은 진행 출력과 중간 저장용이다. 시나리오가 하나 끝날 때마다 `on_report`가 불린다.
    """
    total = CallCap(max_calls)
    reports: list[dict[str, Any]] = []

    def finish(report: dict[str, Any]) -> None:
        reports.append(report)
        if on_report is not None:
            on_report(report)

    for number, scenario in enumerate(scenarios, start=1):
        remaining = total.max_calls - total.used
        if remaining < 1:
            total.exceeded = True
            finish({"scenarioId": scenario.scenario_id, "skipped": "TOTAL_CAP_REACHED"})
            continue
        if on_start is not None:
            on_start(number, len(scenarios), scenario)
        scenario_id = scenario.scenario_id
        report = await run_pilot(
            scenario,
            llm=llm,
            agents=agents_for(scenario),
            max_calls=min(per_scenario_calls, remaining),
            config=config,
            timeout_seconds=scenario_timeout,
            on_turn=(lambda i, t, sid=scenario_id: on_turn(sid, i, t)) if on_turn else None,
            on_trace=on_trace,
        )
        total.used += report["callCap"]["used"]
        if scenario.follow_up is not None:
            report["notReplayed"] = ["followUp"]  # 재생은 멀티턴 작업(#189)에서 지원한다
        finish(report)
    return aggregate(total, reports)


def _build_real(
    model: str | None,
    *,
    judgment: bool = False,
    corpus: bool = False,
    fixed_questions: bool = False,
    relations: bool = False,
) -> tuple[LLMClient, Callable[[Scenario], Mapping[AgentRole, Agent]], str]:
    from hops_ai.agents.registry import build_registry
    from hops_ai.services.llm.openai import OpenAIClient
    from hops_ai.services.retrieval.factory import knowledge_source_for
    from hops_ai.settings import KnowledgeSourceKind, Settings

    key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not key:
        raise SystemExit("OPENAI_API_KEY 환경변수가 없다. 실제 모델을 호출하지 않고 끝낸다.")
    settings = Settings(_env_file=None, knowledge_source=KnowledgeSourceKind.FIXTURE)

    def agents_for(scenario: Scenario) -> Mapping[AgentRole, Agent]:
        # 판정 모드는 질문을 Inquiry가 미뤘다가 코드가 고른 행동으로 묻게 한다(defer_questions).
        # 역할 조합을 주입하면 파이프라인이 이 구성을 대신 해 주지 않으므로 여기서 맞춘다.
        options = {
            "assess_evidence": True,
            "defer_questions": judgment,
            "generate_questions": judgment and not fixed_questions,
        }
        if "RETRIEVAL_ERROR" in scenario.faults:
            return build_registry(FaultyKnowledgeSource(), **options)
        source = CorpusKnowledgeSource() if corpus else knowledge_source_for(settings)
        relation_source = CorpusRelationSource() if relations else None
        return build_registry(source, relation_source=relation_source, **options)

    return (
        OpenAIClient(key, model or settings.supervisor_model),
        agents_for,
        (model or settings.supervisor_model),
    )


def select_scenarios(
    case_families: Sequence[str],
    split: str | None,
    *,
    allow_test: bool,
    directory: Path = SCENARIO_DIR,
) -> list[Scenario]:
    """실행할 시나리오를 고른다. 사건 계열 이름(여럿 가능, 준 순서), 또는 분할 전체(파일명 순)."""
    if bool(case_families) == (split is not None):
        raise SystemExit("사건 계열 이름과 --split 중 하나만 준다")
    if case_families:
        scenarios = [load_scenario(directory / f"S-{name}-001.json") for name in case_families]
    else:
        scenarios = [load_scenario(path) for path in sorted(directory.glob("*.json"))]
        scenarios = [item for item in scenarios if item.split == split]
    if any(item.split == "TEST" for item in scenarios) and not allow_test:
        raise SystemExit("TEST 시나리오는 동결 자료다. 최종 측정에서만 --allow-test로 돌린다.")
    return scenarios


FIXTURE_SOURCE_NAME = "FIXTURE(개발용 고정 자료, 가상 기업 문서 아님)"
CORPUS_SOURCE_NAME = (
    "CORPUS(docs/knowledge/corpus 본문을 Spring 검색 규칙으로 근사해 찾는 평가 대역, 실검색 아님)"
)


CORPUS_RELATION_SOURCE_NAME = (
    "CORPUS_RELATIONS(코퍼스 front matter의 증상·확인 항목과 질문 카드를 읽는 "
    "평가 대역, 실조회 아님)"
)


def run_metadata(
    model: str,
    directory: Path = SCENARIO_DIR,
    *,
    judgment: bool = False,
    corpus: bool = False,
    limits: Mapping[str, int] | None = None,
    model_questions: bool = False,
    relations: bool = False,
) -> dict[str, Any]:
    """실행 조건 기록. 같은 조건끼리만 결과를 비교하려고 남긴다(EVALUATION.md 반복 실행 절).

    판정 모드와 검색원, 실행별 한도가 결과를 바꾸므로 실제로 쓴 값을 그대로 적는다.
    """

    def git(*args: str) -> str | None:
        try:
            done = subprocess.run(
                ["git", *args], cwd=directory, capture_output=True, text=True, timeout=10
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
        "knowledgeSource": CORPUS_SOURCE_NAME if corpus else FIXTURE_SOURCE_NAME,
        "judgmentMode": judgment,
        # 판정 모드에서 질문 문장을 모델이 쓰는지(꺼져 있으면 고정 문구)
        "modelQuestions": model_questions,
        # 증상 문의의 검색이 관계 조회(확인 항목)를 더하는지. 켜면 코퍼스 선언을 읽는 평가 대역이다
        "relationSource": CORPUS_RELATION_SOURCE_NAME if relations else None,
        "runLimits": dict(limits or DEFAULT_LIMITS),
    }


def exit_code(report: dict[str, Any]) -> int:
    """안전 위반·호출 상한 도달(전체·시나리오별)·건너뜀·시간 초과·턴 절단이 있으면 0이 아니다."""
    totals = report["totals"]
    incomplete = (
        totals["safetyViolations"]
        or report["totalCallCap"]["exceeded"]
        or totals["skipped"]
        or totals["scenariosTimedOut"]
        or totals["scenariosTruncated"]
        or totals["scenariosHittingTheirCap"]
        or totals["scenariosOverRunBudget"]
    )
    return 1 if incomplete else 0


def finish_run_info(run_info: dict[str, Any], now: datetime | None = None) -> dict[str, Any]:
    """실행 전에 만든 조건 기록에 종료 시각을 더한다. 시작 시각은 바꾸지 않는다."""
    finished = (now or datetime.now(UTC)).isoformat(timespec="seconds")
    return {**run_info, "finishedAt": finished}


def _log(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="시나리오를 실제 모델로 한 번씩 재생한다")
    parser.add_argument("caseFamilies", nargs="*", help="예: ea-search-period (여러 개 가능)")
    parser.add_argument("--split", choices=("DEV", "TEST"), default=None)
    parser.add_argument("--allow-test", action="store_true", help="동결된 TEST 시나리오를 허용")
    parser.add_argument("--max-calls", type=int, required=True, help="전체 모델 호출 상한")
    parser.add_argument("--per-scenario-calls", type=int, default=DEFAULT_PER_SCENARIO_CALLS)
    parser.add_argument(
        "--scenario-timeout",
        type=float,
        default=DEFAULT_SCENARIO_TIMEOUT,
        help="시나리오당 제한(초)",
    )
    parser.add_argument("--model", default=None, help="기본값은 설정의 supervisor_model")
    parser.add_argument(
        "--judgment",
        action="store_true",
        help="문제별 행동 경로(판정 모드)로 돌린다. 기본은 역할 단위 결정 경로",
    )
    parser.add_argument(
        "--corpus",
        action="store_true",
        help="고정 자료 대신 docs/knowledge/corpus를 Spring 규칙으로 근사해 검색한다(평가 대역)",
    )
    parser.add_argument(
        "--relations",
        action="store_true",
        help="증상 문의의 검색에 관계 조회(확인 항목)를 더한다(코퍼스 선언을 읽는 평가 대역)",
    )
    parser.add_argument(
        "--fixed-questions",
        action="store_true",
        help="판정 모드에서도 질문 문장을 모델이 쓰지 않고 고정 문구를 쓴다(비교용)",
    )
    parser.add_argument(
        "--max-model-calls",
        type=int,
        default=None,
        help="실행(턴)별 모델 호출 한도. 기본은 Spring 설정과 같은 값(spring_stub.DEFAULT_LIMITS)",
    )
    parser.add_argument("--out", type=Path, default=None, help="보고서를 JSON 파일로도 저장")
    parser.add_argument(
        "--trace-dir",
        type=Path,
        default=None,
        help="시나리오마다 원본 실행 기록을 저장(나중에 evaluation.recheck로 재판정)",
    )
    args = parser.parse_args(argv)

    scenarios = select_scenarios(args.caseFamilies, args.split, allow_test=args.allow_test)
    llm, agents_for, model = _build_real(
        args.model,
        judgment=args.judgment,
        corpus=args.corpus,
        fixed_questions=args.fixed_questions,
        relations=args.relations,
    )
    limits = {
        **DEFAULT_LIMITS,
        **({"maxModelCalls": args.max_model_calls} if args.max_model_calls else {}),
    }
    config = RunConfig(limits=limits, judgment=args.judgment)
    done: list[dict[str, Any]] = []
    run_info = run_metadata(
        model,
        judgment=args.judgment,
        corpus=args.corpus,
        limits=limits,
        model_questions=args.judgment and not args.fixed_questions,
        relations=args.relations,
    )

    def save_partial(report: dict[str, Any]) -> None:
        done.append(report)
        if args.out:
            partial = {"partial": True, "run": run_info, "scenarios": done}
            args.out.write_text(json.dumps(partial, ensure_ascii=False, indent=2) + "\n", "utf-8")
        totals = report.get("totals")
        if totals:
            _log(
                f"  끝: {report['scenarioId']} 호출 {totals['modelCalls']} "
                f"토큰 {totals['inputTokens']}/{totals['outputTokens']} {totals['seconds']}초"
                f"{' [시간 초과]' if 'timedOut' in report else ''}"
                f"{' [호출 상한]' if report['callCap']['exceeded'] else ''}"
                f" 위반 {len(report['safetyViolations'])}"
            )
        else:
            _log(f"  건너뜀: {report['scenarioId']} ({report['skipped']})")

    def log_turn(scenario_id: str, index: int, turn: TurnTrace) -> None:
        status = turn.result.status if turn.result else f"ERROR:{turn.error}"
        _log(
            f"    턴 {index}: {status} 호출 {turn.model_calls} "
            f"토큰 {turn.input_tokens}/{turn.output_tokens} {turn.elapsed_seconds:.1f}초"
        )

    try:
        report = asyncio.run(
            run_batch(
                scenarios,
                llm=llm,
                agents_for=agents_for,
                max_calls=args.max_calls,
                per_scenario_calls=args.per_scenario_calls,
                scenario_timeout=args.scenario_timeout,
                config=config,
                on_start=lambda n, total, sc: _log(f"[{n}/{total}] 시작: {sc.scenario_id}"),
                on_turn=log_turn,
                on_report=save_partial,
                on_trace=(lambda trace: save_trace(trace, args.trace_dir))
                if args.trace_dir
                else None,
            )
        )
    except KeyboardInterrupt:
        _log("중단됨. 끝난 시나리오까지만 저장한다(진행 중이던 시나리오는 포함되지 않는다).")
        return 130
    report["run"] = finish_run_info(run_info)
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.out:
        args.out.write_text(text + "\n", encoding="utf-8")
    return exit_code(report)


if __name__ == "__main__":
    sys.exit(main())
