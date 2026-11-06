"""모델 판단 → 역할 실행·병합 → 재판단 → 최종화를 연결하는 실행 그래프.

그래프 State(`_GraphState`)가 한 run의 유일한 정본이다. 노드는 State를 받아 새 State를
반환하고, 예산·취소 정보는 노드 경계에서 실행기(SupervisorRuntime)가 State 키에 반영한다.
역할(Inquiry·Knowledge·Ticket·Curator)은 각각 노드이며 역할 호출과 병합 검증은 기존
`execute_role`을 그대로 쓴다.
"""

import logging
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass
from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from hops_ai.agents.base import Agent, pending_agents
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import InquiryResult
from hops_ai.contracts.wire import AiResult, AiRunRequest
from hops_ai.harness.errors import InputRejected, OutputRejected
from hops_ai.services.shared import SharedServices
from hops_ai.supervisor.choices import Choices, compute_choices
from hops_ai.supervisor.decision import SupervisorDecision, decide_next_action
from hops_ai.supervisor.execution import execute_role
from hops_ai.supervisor.finalize import finalize_result
from hops_ai.supervisor.runtime import (
    SupervisorRuntime,
    describe_failure_for_log,
    describe_run_failure,
)
from hops_ai.supervisor.state import SupervisorState, create_initial_state, transition_phase
from hops_ai.supervisor.validation import has_conversation_result

logger = logging.getLogger(__name__)

DECIDE = "decide"
FINISH = "finish"
# 역할 노드의 이름. 진행 이벤트·추적의 역할 이름(role.value)과 노드 이름을 한 곳에서 맞춘다.
ROLE_NODES: dict[AgentRole, str] = {role: role.value.lower() for role in AgentRole}


class _GraphState(TypedDict):
    """작업 State와 직전 판단·그 판단의 선택지를 함께 전달한다. 외부 API에 반환하지 않는다."""

    state: SupervisorState
    decision: SupervisorDecision | None
    choices: Choices | None


@dataclass
class _LastConfirmed:
    """그래프가 마지막으로 확정한 State. 노드가 아니라 그래프 출력에서만 갱신한다.

    노드 실패·취소·기한 초과 때 실패 결과를 이 State로 만든다. 노드가 자기 State 사본을
    따로 보관하지 않는다.
    """

    state: SupervisorState


class Supervisor:
    """주입된 모델과 전문 역할로 한 run의 제안을 만든다.

    역할을 주입하지 않으면 모든 역할이 연결 전 자리라 실행은 안전한 실패로 끝난다.
    이 클래스가 테스트 대역이나 정해진 업무 답변으로 자동 대체하지는 않는다.
    실제 서비스 접수·중복 방지·결과 저장은 호출 측 RunLedger 연결이 담당한다.
    """

    def __init__(
        self,
        agents: Mapping[AgentRole, Agent] | None = None,
        *,
        restrict_roles: bool = False,
    ) -> None:
        """역할 목록을 복사한다. 역할의 호출 순서는 실행 중 모델이 선택한다.

        restrict_roles가 참이면 연결 전 자리(PendingAgent)와 지금 State에서 호출이 성립하지 않는
        역할을 선택지에서 뺀다(supervisor.choices). 거짓이면 등록된 역할을 모두 선택지로 둔다.
        """
        self._agents = dict(pending_agents() if agents is None else agents)
        self._restrict_roles = restrict_roles

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
        initial = create_initial_state(request)
        runtime = SupervisorRuntime(initial["input"], services)
        last = _LastConfirmed(initial)

        try:
            last.state = transition_phase(runtime.sync_state(initial), "RUNNING")
            # 각 순환은 모델 호출을 소비한다. 그래프 자체 한도도 호출 상한에 맞춘다.
            limit = min(request.limits.maxModelCalls, services.runtime.budget.max_llm_calls)
            await runtime.run(self._stream(runtime, last, 2 * limit + 4))
            # 비동기 실행의 취소·마감 검사를 마친 뒤 결과를 한 번만 고정한다.
            candidate = finalize_result(runtime.sync_state(last.state))
            # 본문·출처 검증에 걸린 시간도 서버의 더 짧은 실행 기한에 포함한다.
            # 검사에 실패하면 마지막 확정 State는 FINALIZING이므로 안전한 실패로 끝낼 수 있다.
            services.runtime.ensure_active()
            final = candidate
        except Exception as error:
            # 외부에는 고정된 안전한 오류만 나가므로
            # 운영자가 원인을 찾을 수 있게 서버 로그에 남긴다.
            logger.warning(
                "상담 실행 실패: runId=%s %s", request.runId, describe_failure_for_log(error)
            )
            phase, failure = describe_run_failure(error)
            final = transition_phase(runtime.sync_state(last.state), phase, error=failure)
            final = finalize_result(final)

        result = final["finalResult"]
        if result is None:
            raise OutputRejected("실행이 최종 결과 없이 끝났다")
        return result

    async def _stream(
        self, runtime: SupervisorRuntime, last: _LastConfirmed, recursion_limit: int
    ) -> None:
        """그래프를 끝까지 실행하며 단계마다 확정된 State를 기록한다."""
        graph = self._build_graph(runtime)
        first = _GraphState(state=last.state, decision=None, choices=None)
        async for values in graph.astream(
            first, config={"recursion_limit": recursion_limit}, stream_mode="values"
        ):
            last.state = values["state"]

    def _build_graph(self, runtime: SupervisorRuntime):
        """판단 → 역할 노드 → 판단을 반복하다 최종화로 끝나는 그래프를 이 run에 맞게 만든다."""

        async def decide(data: _GraphState) -> _GraphState:
            return await self._decide(runtime, data)

        async def finish(data: _GraphState) -> _GraphState:
            return await self._finish(runtime, data)

        graph = StateGraph(_GraphState)
        graph.add_node(DECIDE, decide)
        for role, name in ROLE_NODES.items():
            graph.add_node(name, self._role_node(runtime, role))
        graph.add_node(FINISH, finish)
        graph.add_edge(START, DECIDE)
        graph.add_conditional_edges(
            DECIDE, _route, {name: name for name in (*ROLE_NODES.values(), FINISH)}
        )
        for role, name in ROLE_NODES.items():
            if role == AgentRole.INQUIRY:
                graph.add_conditional_edges(
                    name,
                    lambda data: FINISH if has_conversation_result(data["state"]) else DECIDE,
                    {FINISH: FINISH, DECIDE: DECIDE},
                )
            else:
                graph.add_edge(name, DECIDE)
        graph.add_edge(FINISH, END)
        return graph.compile()

    async def _decide(self, runtime: SupervisorRuntime, data: _GraphState) -> _GraphState:
        """현재 상태로 다음 행동을 요청하고 검증된 판단만 기록한다."""
        state = _enter(runtime, data, "SUPERVISOR")
        choices = compute_choices(state, self._agents, restrict=self._restrict_roles)
        with runtime.services.tracing.observe(
            "SUPERVISOR", {"registeredRoles": sorted(role.value for role in choices.registered)}
        ) as span:
            decision = await decide_next_action(state, runtime.services, choices)
            if span is not None:
                span.update(
                    output={
                        "nextAction": decision.nextAction,
                        "role": decision.role.value if decision.role else None,
                    }
                )
        state = runtime.sync_state(state)
        state = {**state, "nextAction": decision.nextAction}
        # 모델의 공개 요약도 검증되지 않은 문구다. 최종 답변은 최종화 단계에서
        # 준비된 제안과 서버가 제공한 기존 상태를 기준으로 한 번만 구성한다.
        return _GraphState(state=state, decision=decision, choices=choices)

    def _role_node(
        self, runtime: SupervisorRuntime, role: AgentRole
    ) -> Callable[[_GraphState], Awaitable[_GraphState]]:
        """선택된 역할 하나를 실행하고 해당 역할의 병합 검사를 적용하는 노드."""

        async def run_role(data: _GraphState) -> _GraphState:
            decision = data["decision"]
            if decision is None or decision.role != role:
                raise OutputRejected("호출할 역할이 없다")
            state = _enter(runtime, data, role.value)
            with runtime.services.tracing.observe(
                role.value,
                {
                    "targetProblemCount": len(state["input"].problemIds),
                    "messageCount": len(state["input"].messages),
                },
            ) as span:
                merged = await execute_role(state, role, runtime.services, self._agents)
                if span is not None:
                    span.update(output=_role_span_output(runtime, role, state, merged))
            return _GraphState(state=runtime.sync_state(merged), decision=None, choices=None)

        return run_role

    async def _finish(self, runtime: SupervisorRuntime, data: _GraphState) -> _GraphState:
        """미완료 호출이 없는지 확인하고 최종화 준비 상태로 이동한다."""
        state = runtime.sync_state(data["state"])
        runtime.ensure_active()
        state = transition_phase(state, "FINALIZING")
        return _GraphState(state=state, decision=data["decision"], choices=data["choices"])


def _enter(runtime: SupervisorRuntime, data: _GraphState, label: str) -> SupervisorState:
    """노드 경계에서 실행기가 예산·취소를 State에 반영하고 고정 진행 문구를 발행한다."""
    state = runtime.sync_state(data["state"])
    runtime.services.runtime.report_progress(label)
    return state


def _route(data: _GraphState) -> str:
    """CALL_ROLE은 선택한 역할 노드로, 나머지 검증된 행동은 최종화로 보낸다."""
    decision = data["decision"]
    if decision is None:
        raise OutputRejected("다음 행동이 없다")
    if decision.nextAction != "CALL_ROLE":
        return FINISH
    choices = data["choices"]
    if decision.role is None or choices is None or decision.role not in choices.roles:
        raise OutputRejected("호출할 수 없는 역할이다")
    return ROLE_NODES[decision.role]


def _role_span_output(
    runtime: SupervisorRuntime,
    role: AgentRole,
    before: SupervisorState,
    merged: SupervisorState,
) -> dict:
    """역할 추적 span에 남길 요약. 본문은 content 기록이 켜진 Inquiry에서만 더한다."""
    output: dict = {
        "problemIntents": [item.intent for item in merged["problems"].values()],
        "questionCount": len(merged["questions"]),
        "ticketProposalCount": len(merged["ticketProposals"]),
    }
    if role == AgentRole.INQUIRY:
        output["kind"] = next(
            (
                result.kind
                for invocation, result in merged["roleResults"].items()
                if invocation not in before["roleResults"] and isinstance(result, InquiryResult)
            ),
            "PROBLEMS",
        )
    if runtime.services.tracing.capture_content and role == AgentRole.INQUIRY:
        output["questions"] = [
            item.text for key, item in merged["questions"].items() if key not in before["questions"]
        ]
        output["factsByProblem"] = {}
        for ref, problem in merged["problems"].items():
            previous = before["problems"].get(ref)
            prior_facts = previous.facts if previous is not None else []
            preserved = []
            added = []
            for fact in problem.facts:
                destination = preserved if fact in prior_facts else added
                destination.append(fact.model_dump(mode="json"))
            if preserved or added:
                output["factsByProblem"][ref] = {
                    "preservedFacts": preserved,
                    "addedFacts": added,
                }
    return output
