"""시나리오 하나를 `CASE_DRAFT` 실행으로 재생하고 기록(`CaseTrace`)을 돌려준다.

Spring이 하는 일(처리 기록 저장 뒤 입력 만들기)은 `request.py`의 대역이 하고, 이후는 운영과 같은
`SupervisorPipeline`·`ModelCuratorAgent`를 쓴다. 모델은 호출자가 주는 `llm`이며 이 모듈은 어떤
모델도 만들지
않는다. 실행 전 거부나 예외로 결과가 없으면 성공/실패 결과를 지어내지 않고 `error`에 예외 이름을
남긴다.
"""

import time
from datetime import datetime

from evaluation.curator.candidates import CatalogNode
from evaluation.curator.request import build_request
from evaluation.curator.scenario import CuratorScenario
from evaluation.curator.trace import CaseTrace
from evaluation.harness.recorders import CallLog, RecordingAgent, RecordingLLM
from hops_ai.agents.curator import ModelCuratorAgent
from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.context import RunContext
from hops_ai.services.llm.client import LLMClient
from hops_ai.services.runs.supervisor import SupervisorPipeline
from hops_ai.settings import Settings


async def run_case(
    scenario: CuratorScenario,
    catalog: dict[str, CatalogNode],
    *,
    llm: LLMClient,
    now: datetime | None = None,
    include_candidates: bool = True,
) -> CaseTrace:
    request = build_request(scenario, catalog, now=now, include_candidates=include_candidates)
    log = CallLog()
    agents = {AgentRole.CURATOR: RecordingAgent(ModelCuratorAgent(), log)}
    pipeline = SupervisorPipeline(RecordingLLM(llm, log), agents)
    context = RunContext.create(
        run_id=request.runId,
        inquiry_id=request.inquiryId,
        inquiry_version=request.inquiryVersion,
        access_scope_ref="curator-evaluation",
        settings=Settings(_env_file=None),
    )
    started = time.monotonic()
    error: str | None = None
    result = None
    try:
        result = await pipeline.run(request, context)
    except Exception as exc:  # 실행 전 거부 등. 성공/실패 결과를 만들어 내지 않는다.
        error = type(exc).__name__
    failed = next((call for call in log.role_calls if call.failed_with), None)
    return CaseTrace(
        scenario_id=scenario.scenario_id,
        request=request,
        result=result,
        model_calls=log.model_calls,
        input_tokens=log.input_tokens,
        output_tokens=log.output_tokens,
        elapsed_seconds=time.monotonic() - started,
        error=error,
        failure_detail=failed.failure_detail if failed else None,
    )
