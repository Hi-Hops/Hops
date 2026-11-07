"""Curator 실행 기록(`CaseTrace`)의 저장과 복원.

모델을 다시 부르지 않고 고친 검사기로 재판정하려고 보존한다.

기록에는 합성 시나리오의 대화 전문이 들어가므로 합성 시나리오에서만 저장한다.
"""

import json
from pathlib import Path
from typing import Any

from evaluation.curator.trace import CaseTrace
from hops_ai.contracts.wire import AiResult, AiRunRequest

TRACE_FORMAT = 1


def trace_to_dict(trace: CaseTrace) -> dict[str, Any]:
    return {
        "format": TRACE_FORMAT,
        "scenario_id": trace.scenario_id,
        "request": trace.request.model_dump(mode="json"),
        "result": trace.result.model_dump(mode="json") if trace.result else None,
        "model_calls": trace.model_calls,
        "input_tokens": trace.input_tokens,
        "output_tokens": trace.output_tokens,
        "elapsed_seconds": trace.elapsed_seconds,
        "error": trace.error,
        "failure_detail": trace.failure_detail,
    }


def trace_from_dict(data: dict[str, Any]) -> CaseTrace:
    if data.get("format") != TRACE_FORMAT:
        raise ValueError(f"지원하지 않는 기록 형식: {data.get('format')}")
    result = data["result"]
    return CaseTrace(
        scenario_id=data["scenario_id"],
        request=AiRunRequest.model_validate(data["request"]),
        result=AiResult.model_validate(result) if result else None,
        model_calls=data["model_calls"],
        input_tokens=data["input_tokens"],
        output_tokens=data["output_tokens"],
        elapsed_seconds=data["elapsed_seconds"],
        error=data["error"],
        failure_detail=data["failure_detail"],
    )


def save_trace(trace: CaseTrace, directory: Path, run_index: int) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{trace.scenario_id}.run{run_index}.trace.json"
    path.write_text(
        json.dumps(trace_to_dict(trace), ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
    )
    return path


def load_trace(path: Path) -> CaseTrace:
    return trace_from_dict(json.loads(path.read_text(encoding="utf-8")))
