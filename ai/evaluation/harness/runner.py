"""시나리오 하나를 끝까지 재생하고 기록(`RunTrace`)을 돌려준다.

AI가 질문하면 규칙 사용자가 답하고 Spring 대역이 다음 실행 입력을 만든다. 질문이 없거나 실행이
실패하면 끝난다. 모델은 호출자가 주는 `llm`이며, 이 모듈은 어떤 모델도 만들지 않는다.
"""

from collections.abc import Mapping
from dataclasses import dataclass

from evaluation.harness.recorders import CallLog, RecordingAgent, RecordingLLM
from evaluation.harness.scenario import Scenario
from evaluation.harness.spring_stub import SpringStub
from evaluation.harness.trace import RunTrace, TurnTrace
from evaluation.harness.user_sim import reply_to
from hops_ai.agents.base import Agent
from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.context import RunContext
from hops_ai.services.llm.client import LLMClient
from hops_ai.services.runs.supervisor import SupervisorPipeline
from hops_ai.settings import Settings


@dataclass(frozen=True)
class RunConfig:
    # 사용자 응답을 주고받는 최대 횟수. 문제당 질문 한도(5)보다 넉넉히 잡아 한도 위반도 관찰한다.
    max_turns: int = 7
    limits: Mapping[str, int] | None = None


async def run_scenario(
    scenario: Scenario,
    *,
    llm: LLMClient,
    agents: Mapping[AgentRole, Agent],
    config: RunConfig | None = None,
    extra_forbidden_phrases: tuple[str, ...] = (),
) -> RunTrace:
    """시나리오를 재생한다. 역할 구성(`agents`)은 호출자가 정한다(스크립트 역할 또는 실제 역할)."""
    cfg = config or RunConfig()
    stub = SpringStub(scenario, limits=dict(cfg.limits) if cfg.limits else None)
    request = stub.first_request()
    turns: list[TurnTrace] = []
    for _ in range(cfg.max_turns):
        log = CallLog()
        pipeline = SupervisorPipeline(
            RecordingLLM(llm, log),
            {role: RecordingAgent(agent, log) for role, agent in agents.items()},
        )
        context = RunContext.create(
            run_id=request.runId,
            inquiry_id=request.inquiryId,
            inquiry_version=request.inquiryVersion,
            access_scope_ref="evaluation-harness",
            settings=Settings(_env_file=None),
        )
        try:
            result = await pipeline.run(request, context)
        except Exception as exc:  # 실행 전 거부 등. 성공/실패 결과를 만들어 내지 않는다.
            turns.append(
                TurnTrace(
                    request=request,
                    result=None,
                    role_calls=tuple(log.role_calls),
                    model_calls=log.model_calls,
                    error=type(exc).__name__,
                )
            )
            break
        if result.status != "SUCCEEDED" or not result.questions:
            turns.append(
                TurnTrace(
                    request=request,
                    result=result,
                    role_calls=tuple(log.role_calls),
                    model_calls=log.model_calls,
                )
            )
            break
        reply = reply_to(scenario, result.questions)
        next_request, ref_map = stub.advance(result, reply.text)
        turns.append(
            TurnTrace(
                request=request,
                result=result,
                role_calls=tuple(log.role_calls),
                model_calls=log.model_calls,
                user_reply=reply.text,
                unknown_after=reply.unknown,
                ref_map=ref_map,
            )
        )
        request = next_request
    return RunTrace(
        scenario_id=scenario.scenario_id,
        turns=tuple(turns),
        extra_forbidden_phrases=extra_forbidden_phrases,
    )
