"""모델 판단 → 역할 실행·병합 → 재판단 → 최종화를 연결하는 실행 그래프."""

import logging
from collections.abc import Callable, Mapping
from typing import Literal, TypedDict

from langgraph.graph import END, START, StateGraph

from hops_ai.agents.base import Agent
from hops_ai.agents.registry import build_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiResult, AiRunRequest
from hops_ai.harness.errors import InputRejected, OutputRejected
from hops_ai.services.shared import SharedServices
from hops_ai.supervisor.decision import SupervisorDecision, decide_next_action
from hops_ai.supervisor.execution import execute_role
from hops_ai.supervisor.finalize import finalize_result
from hops_ai.supervisor.runtime import (
    SupervisorRuntime,
    describe_failure_for_log,
    describe_run_failure,
)
from hops_ai.supervisor.state import SupervisorState, create_initial_state, transition_phase

logger = logging.getLogger(__name__)


class _GraphState(TypedDict):
    """작업 State와 직전 판단을 함께 전달한다. 외부 API에 반환하지 않는다."""

    state: SupervisorState
    decision: SupervisorDecision | None


class Supervisor:
    """주입된 모델과 전문 역할로 한 run의 제안을 만든다.

    기본 registry의 전문 역할과 OpenAI 어댑터는 아직 구현 전이다.
    이 클래스가 테스트 대역이나 정해진 업무 답변으로 자동 대체하지는 않는다.
    실제 서비스 접수·중복 방지·결과 저장은 호출 측 RunLedger 연결이 담당한다.
    """

    def __init__(
        self,
        agents: Mapping[AgentRole, Agent] | None = None,
        *,
        available_roles: Callable[[SupervisorState], set[AgentRole]] | None = None,
    ) -> None:
        """역할 목록을 복사한다. 역할의 호출 순서는 실행 중 모델이 선택한다."""
        self._agents = dict(build_registry() if agents is None else agents)
        self._available_roles = available_roles

    async def analyze(self, request: AiRunRequest, services: SharedServices) -> AiResult:
        """문의 분석 또는 사례 초안 실행을 최종 AiResult로 반환한다.

        Args:
            request: Spring이 준비한 실행 입력. 입력 관계는 시작 전에 다시 검증한다.
            services: 이 run 전용 RunContext와 원시 LLM·검색·검증된 도구 어댑터.
                모든 역할에는 같은 누적 예산을 적용한 서비스가 전달된다.

        Returns:
            검증된 최종 제안 또는 새 제안이 없는 안전한 실패 결과.
            ASK_USER도 현재 run을 끝내며, 답변 후 재분석은 Spring의 새 run이다.
            제안 완료는 티켓 저장·배정·해결·사례 승인을 의미하지 않는다.

        Raises:
            InputRejected / ValidationError: 입력이나 실행 문맥이 잘못된 경우.
                실행 전 거부이므로 성공/실패 결과를 임의로 만들지 않는다.
            asyncio.CancelledError: 호출 작업 자체가 취소된 경우. 서버 종료 등의
                취소를 삼키지 않으며 영속 상태 복구는 실행 관리자가 담당한다.
        """
        if not isinstance(request, AiRunRequest) or not isinstance(services, SharedServices):
            raise InputRejected("실행 요청과 공유 서비스가 필요하다")
        current = create_initial_state(request)
        runtime = SupervisorRuntime(current["input"], services)

        async def decide(data: _GraphState) -> _GraphState:
            """현재 상태로 다음 행동을 요청하고 검증된 판단만 기록한다."""
            nonlocal current
            current = runtime.sync_state(data["state"])
            roles = set(self._agents)
            if self._available_roles is not None:
                roles &= self._available_roles(current)
            decision = await decide_next_action(current, runtime.services, roles)
            current = runtime.sync_state(current)
            current = {**current, "nextAction": decision.nextAction}
            # 모델의 공개 요약도 검증되지 않은 문구다. 최종 답변은 최종화 단계에서
            # 준비된 제안과 서버가 제공한 기존 상태를 기준으로 한 번만 구성한다.
            return _GraphState(state=current, decision=decision)

        async def execute(data: _GraphState) -> _GraphState:
            """선택된 역할 하나를 실행하고 해당 역할의 병합 검사를 적용한다."""
            nonlocal current
            decision = data["decision"]
            if decision is None or decision.role is None:
                raise OutputRejected("호출할 역할이 없다")
            current = runtime.sync_state(data["state"])
            merged = await execute_role(current, decision.role, runtime.services, self._agents)
            current = runtime.sync_state(merged)
            return _GraphState(state=current, decision=None)

        async def finish(data: _GraphState) -> _GraphState:
            """미완료 호출이 없는지 확인하고 최종화 준비 상태로 이동한다."""
            nonlocal current
            current = runtime.sync_state(data["state"])
            runtime.ensure_active()
            current = transition_phase(current, "FINALIZING")
            return _GraphState(state=current, decision=data["decision"])

        def route(data: _GraphState) -> Literal["execute", "finish"]:
            """CALL_ROLE만 역할로 보내고 나머지 검증된 행동은 현재 run을 끝낸다."""
            decision = data["decision"]
            if decision is None:
                raise OutputRejected("다음 행동이 없다")
            return "execute" if decision.nextAction == "CALL_ROLE" else "finish"

        graph = StateGraph(_GraphState)
        graph.add_node("decide", decide)
        graph.add_node("execute", execute)
        graph.add_node("finish", finish)
        graph.add_edge(START, "decide")
        graph.add_conditional_edges("decide", route)
        graph.add_edge("execute", "decide")
        graph.add_edge("finish", END)

        try:
            current = transition_phase(runtime.sync_state(current), "RUNNING")
            # 각 순환은 모델 호출을 소비한다. 그래프 자체 한도도 호출 상한에 맞춘다.
            limit = min(request.limits.maxModelCalls, services.runtime.budget.max_llm_calls)
            await runtime.run(
                graph.compile().ainvoke(
                    _GraphState(state=current, decision=None),
                    config={"recursion_limit": 2 * limit + 4},
                )
            )
            # 비동기 실행의 취소·마감 검사를 마친 뒤 결과를 한 번만 고정한다.
            candidate = finalize_result(runtime.sync_state(current))
            # 본문·출처 검증에 걸린 시간도 서버의 더 짧은 실행 기한에 포함한다.
            # 검사에 실패하면 current는 FINALIZING이므로 안전한 실패로 끝낼 수 있다.
            services.runtime.ensure_active()
            current = candidate
        except Exception as error:
            # 외부에는 고정된 안전한 오류만 나가므로
            # 운영자가 원인을 찾을 수 있게 서버 로그에 남긴다.
            logger.warning(
                "상담 실행 실패: runId=%s %s", request.runId, describe_failure_for_log(error)
            )
            phase, failure = describe_run_failure(error)
            current = transition_phase(runtime.sync_state(current), phase, error=failure)
            current = finalize_result(current)

        result = current["finalResult"]
        if result is None:
            raise OutputRejected("실행이 최종 결과 없이 끝났다")
        return result
