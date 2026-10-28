"""실행 기록. 실행이 끝날 때 결과·사용량·소요 시간을 한 건으로 남긴다.

대화 내용·답변·근거 본문 같은 사용자 데이터는 기록하지 않는다. 식별자와 수치만 담는다.
외부 추적 도구(Langfuse 등)는 같은 인터페이스로 후속 단계에서 연결한다.
"""

import json
import logging
from dataclasses import asdict, dataclass
from typing import Protocol, runtime_checkable

logger = logging.getLogger("hops_ai.run")


@dataclass(frozen=True)
class RunRecord:
    run_id: str
    inquiry_id: str
    inquiry_version: int
    mode: str
    agent_mode: str
    status: str  # OK | ERROR | TIMED_OUT | CANCELLED
    error_code: str | None
    duration_ms: int
    llm_calls_used: int  # 실패·재시도한 시도를 포함한 모델 호출 시도 수
    input_tokens: int
    output_tokens: int
    model_ids: tuple[str, ...]
    prompt_versions: tuple[str, ...]
    event_count: int


@runtime_checkable
class Tracer(Protocol):
    def record_run(self, record: RunRecord) -> None: ...


class NoopTracer:
    def record_run(self, record: RunRecord) -> None:
        return None


class LoggingTracer:
    def record_run(self, record: RunRecord) -> None:
        logger.info("run_finished %s", json.dumps(asdict(record), ensure_ascii=False))
