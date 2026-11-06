"""실행 기록(`RunTrace`)의 저장과 복원.

모델을 다시 부르지 않고 같은 기록을 나중에 고친 검사기로 다시 판정하려고 보존한다. 보고서
(`pilot.summarize`)에는 판정에 필요한 원본(요청 메시지, 사실의 출처, 검색 근거)이 없다.
기록에는 시나리오 대화 전문이 들어가므로 합성 시나리오에서만 저장한다.
"""

import json
from pathlib import Path
from typing import Any

from evaluation.harness.trace import (
    AnalysisRecord,
    AssessmentRecord,
    RoleCall,
    RunTrace,
    TurnTrace,
)
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiResult, AiRunRequest, KnowledgeEvidence

TRACE_FORMAT = 1


def _role_call(call: RoleCall) -> dict[str, Any]:
    return {
        "role": call.role.value,
        "problem_ref": call.problem_ref,
        "search_state": call.search_state,
        "evidence": [item.model_dump(mode="json") for item in call.evidence],
        "failed_with": call.failed_with,
        "failure_detail": call.failure_detail,
        "analysis": [
            {
                "problem_ref": item.problem_ref,
                "keywords": list(item.keywords),
                "feature_candidates": list(item.feature_candidates),
                "raw_keywords": list(item.raw_keywords),
                "raw_feature_candidates": list(item.raw_feature_candidates),
            }
            for item in call.analysis
        ],
        "assessment": _assessment_dict(call.assessment),
        "question_source": call.question_source,
        "question_fallback": call.question_fallback,
    }


def _assessment_dict(item: AssessmentRecord | None) -> dict[str, Any] | None:
    if item is None:
        return None
    return {
        "problem_ref": item.problem_ref,
        "verdict": item.verdict,
        "supporting_evidence_ids": list(item.supporting_evidence_ids),
        "missing_information": [list(pair) for pair in item.missing_information],
        "candidate_causes": [list(pair) for pair in item.candidate_causes],
        "rationale": item.rationale,
    }


def _assessment(data: dict[str, Any] | None) -> AssessmentRecord | None:
    if data is None:
        return None
    return AssessmentRecord(
        problem_ref=data["problem_ref"],
        verdict=data["verdict"],
        supporting_evidence_ids=tuple(data["supporting_evidence_ids"]),
        missing_information=tuple((kind, ref) for kind, ref in data["missing_information"]),
        candidate_causes=tuple((text, confirmed) for text, confirmed in data["candidate_causes"]),
        rationale=data["rationale"],
    )


def _analysis(items: list[dict[str, Any]]) -> tuple[AnalysisRecord, ...]:
    return tuple(
        AnalysisRecord(
            problem_ref=item["problem_ref"],
            keywords=tuple(item["keywords"]),
            feature_candidates=tuple(item["feature_candidates"]),
            raw_keywords=tuple(item["raw_keywords"]),
            raw_feature_candidates=tuple(item["raw_feature_candidates"]),
        )
        for item in items
    )


def _turn(turn: TurnTrace) -> dict[str, Any]:
    return {
        "request": turn.request.model_dump(mode="json"),
        "result": turn.result.model_dump(mode="json") if turn.result is not None else None,
        "role_calls": [_role_call(call) for call in turn.role_calls],
        "model_calls": turn.model_calls,
        "error": turn.error,
        "user_reply": turn.user_reply,
        "unknown_after": [list(pair) for pair in turn.unknown_after],
        "ref_map": [list(pair) for pair in turn.ref_map],
        "input_tokens": turn.input_tokens,
        "output_tokens": turn.output_tokens,
        "elapsed_seconds": turn.elapsed_seconds,
    }


def trace_to_dict(trace: RunTrace) -> dict[str, Any]:
    return {
        "format": TRACE_FORMAT,
        "scenario_id": trace.scenario_id,
        "extra_forbidden_phrases": list(trace.extra_forbidden_phrases),
        "truncated": trace.truncated,
        "turns": [_turn(turn) for turn in trace.turns],
    }


def trace_from_dict(data: dict[str, Any]) -> RunTrace:
    if data.get("format") != TRACE_FORMAT:
        raise ValueError(f"지원하지 않는 trace 형식: {data.get('format')}")
    turns = []
    for item in data["turns"]:
        calls = tuple(
            RoleCall(
                role=AgentRole(call["role"]),
                problem_ref=call["problem_ref"],
                search_state=call["search_state"],
                evidence=tuple(KnowledgeEvidence.model_validate(e) for e in call["evidence"]),
                failed_with=call["failed_with"],
                # 사유를 기록하기 전에 저장한 trace에는 이 키가 없다.
                failure_detail=call.get("failure_detail"),
                # 검색 힌트를 기록하기 전에 저장한 trace에는 이 키가 없다. 형식 번호는 그대로다.
                analysis=_analysis(call.get("analysis", [])),
                # 판정을 기록하기 전에 저장한 trace에도 이 키가 없다.
                assessment=_assessment(call.get("assessment")),
                # 질문 문장의 출처를 기록하기 전에 저장한 trace에는 이 키가 없다.
                question_source=call.get("question_source"),
                question_fallback=call.get("question_fallback"),
            )
            for call in item["role_calls"]
        )
        result = item["result"]
        turns.append(
            TurnTrace(
                request=AiRunRequest.model_validate(item["request"]),
                result=AiResult.model_validate(result) if result is not None else None,
                role_calls=calls,
                model_calls=item["model_calls"],
                error=item["error"],
                user_reply=item["user_reply"],
                unknown_after=tuple((a, b) for a, b in item["unknown_after"]),
                ref_map=tuple((a, b) for a, b in item["ref_map"]),
                input_tokens=item["input_tokens"],
                output_tokens=item["output_tokens"],
                elapsed_seconds=item["elapsed_seconds"],
            )
        )
    return RunTrace(
        scenario_id=data["scenario_id"],
        turns=tuple(turns),
        extra_forbidden_phrases=tuple(data["extra_forbidden_phrases"]),
        truncated=data["truncated"],
    )


def save_trace(trace: RunTrace, directory: Path) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{trace.scenario_id}.trace.json"
    path.write_text(
        json.dumps(trace_to_dict(trace), ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
    )
    return path


def load_trace(path: Path) -> RunTrace:
    return trace_from_dict(json.loads(path.read_text(encoding="utf-8")))
