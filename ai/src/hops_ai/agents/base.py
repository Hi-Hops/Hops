"""역할 에이전트 실행 규약과 공통 실행 보조.

- Agent: 역할 하나를 실행한다. agent-1의 execute(request, services) -> result 규약이다.
- Analyzer: 한 문의의 분석 전체를 맡는다. 단일 에이전트(baseline)와 Supervisor가 같은 규약을 따른다.
- execute_safely·run_analysis: 예상치 못한 예외를 안전한 실패 결과로 바꾼다. 취소(CancelledError)는
  그대로 전달한다.
"""

import asyncio
import logging
from typing import Protocol, runtime_checkable

from langgraph.errors import NodeCancelledError

from hops_ai.contracts.agent_1 import (
    CONTRACT_VERSION,
    AgentRequest,
    AgentResult,
    AgentRole,
    DiagnosisResult,
    IntakeResult,
    KnowledgeResult,
    TicketResult,
)
from hops_ai.contracts.analysis import (
    AnalysisFailure,
    AnalysisInput,
    AnalysisOutcome,
    AnalysisResult,
)
from hops_ai.harness.errors import HarnessError, OutputRejected, describe_failure
from hops_ai.services.shared import SharedServices

logger = logging.getLogger(__name__)


def _surface_cancellation(error: Exception) -> None:
    """LangGraph는 노드 안의 취소(CancelledError)를 일반 예외(NodeCancelledError)로 바꾼다.
    그대로 두면 취소가 실패 결과로 삼켜져 실행이 멈추지 않으므로 취소로 되돌려 전달한다."""
    if isinstance(error, NodeCancelledError):
        raise asyncio.CancelledError from error


RESULT_CLASSES = {
    "INTAKE": IntakeResult,
    "KNOWLEDGE": KnowledgeResult,
    "DIAGNOSIS": DiagnosisResult,
    "TICKET": TicketResult,
}


@runtime_checkable
class Agent(Protocol):
    async def execute(self, request: AgentRequest, services: SharedServices) -> AgentResult: ...


@runtime_checkable
class Analyzer(Protocol):
    async def analyze(self, data: AnalysisInput, services: SharedServices) -> AnalysisOutcome:
        """분석 결과를 돌려주거나, 실패하면 예외를 낸다(run_analysis가 안전한 실패로 바꾼다)."""
        ...


def _elapsed_ms(services: SharedServices, started: float) -> int:
    return round((services.runtime.budget.clock.monotonic() - started) * 1000)


def failure_result(
    request: AgentRequest, services: SharedServices, started: float, error: BaseException
) -> AgentResult:
    """요청과 같은 식별자를 가진 실패 결과. 원시 예외 내용은 담지 않는다."""
    failure = describe_failure(error)
    return RESULT_CLASSES[request.role](
        contractVersion=CONTRACT_VERSION,
        runId=request.runId,
        invocationId=request.invocationId,
        inquiryId=request.inquiryId,
        inquiryVersion=request.inquiryVersion,
        role=request.role,
        status=failure.status,
        data=None,
        error=failure.error,
        evidence=[],
        meta=services.usage.to_meta(_elapsed_ms(services, started)),
    )


def _mismatch(request: AgentRequest, result: AgentResult) -> str | None:
    """결과의 식별자와 역할은 요청과 같아야 한다(agent-1)."""
    for name in ("contractVersion", "runId", "invocationId", "inquiryId", "inquiryVersion", "role"):
        if name == "contractVersion":
            expected = CONTRACT_VERSION
        else:
            expected = getattr(request, name)
        if getattr(result, name) != expected:
            return f"결과의 {name}이 요청과 다르다"
    return None


def _log_failure(role: AgentRole, error: Exception) -> None:
    if isinstance(error, HarnessError):
        logger.info("%s 역할 실패: %s (%s)", role, error.code, error.detail or "상세 없음")
    else:
        logger.error("%s 역할에서 예상하지 못한 예외", role, exc_info=error)


async def execute_safely(
    agent: Agent, request: AgentRequest, services: SharedServices
) -> AgentResult:
    """역할을 실행하고, 예외나 요청과 어긋난 결과는 안전한 실패 결과로 바꾼다."""
    started = services.runtime.budget.clock.monotonic()
    try:
        result = await agent.execute(request, services)
    except Exception as error:  # CancelledError는 BaseException이라 여기서 잡히지 않는다
        _surface_cancellation(error)
        _log_failure(request.role, error)
        return failure_result(request, services, started, error)
    problem = _mismatch(request, result)
    if problem is not None:
        logger.warning("%s 역할 결과 거부: %s", request.role, problem)
        return failure_result(request, services, started, OutputRejected(problem))
    return result


async def run_analysis(
    analyzer: Analyzer, data: AnalysisInput, services: SharedServices
) -> AnalysisResult:
    """분석을 실행하고, 실패는 안전한 실패 결과로 바꾼다."""
    try:
        return await analyzer.analyze(data, services)
    except Exception as error:
        _surface_cancellation(error)
        if isinstance(error, HarnessError):
            logger.info("분석 실패: %s (%s)", error.code, error.detail or "상세 없음")
        else:
            logger.error("분석 중 예상하지 못한 예외", exc_info=error)
        failure = describe_failure(error)
        return AnalysisFailure(status=failure.status, error=failure.error)
