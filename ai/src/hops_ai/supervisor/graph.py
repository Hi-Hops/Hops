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

from hops_ai.agents.base import Agent, PendingAgent, pending_agents
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import InquiryResult
from hops_ai.contracts.wire import AiResult, AiRunRequest
from hops_ai.harness.errors import InputRejected, OutputRejected
from hops_ai.rules.legal_actions import legal_actions, run_actions
from hops_ai.services.shared import SharedServices
from hops_ai.supervisor.choices import Choices, compute_choices
from hops_ai.supervisor.decision import (
    SupervisorDecision,
    decide_next_action,
    validate_supervisor_decision,
)
from hops_ai.supervisor.decisions import apply_decision, apply_reply, prepare_decision
from hops_ai.supervisor.execution import execute_role
from hops_ai.supervisor.facts import pending_problem_refs, problem_facts
from hops_ai.supervisor.finalize import finalize_result
from hops_ai.supervisor.runtime import (
    SupervisorRuntime,
    describe_failure_for_log,
    describe_run_failure,
)
from hops_ai.supervisor.selection import choose_actions
from hops_ai.supervisor.state import SupervisorState, create_initial_state, transition_phase
from hops_ai.supervisor.validation import confirmed_courtesy, is_reply, reply_allowed

logger = logging.getLogger(__name__)

DECIDE = "decide"
FINISH = "finish"
# 문제별 행동 경로(판정 모드)의 노드 이름과 실행 단위 행동의 키.
SELECT = "select"
STEP = "step"
RUN_ACTION = "__run__"
REPLY_ACTION = "__reply__"
# 판정 모드 그래프의 안전망 반복 한도. 종료는 합법 행동의 규칙(같은 질의 금지, 재검색 상한, 종료
# 행동 확정)이 보장하고, 실제 호출 상한은 모델 호출 예산이 막는다.
PROBLEM_GRAPH_RECURSION_LIMIT = 60
# 역할 노드의 이름. 진행 이벤트·추적의 역할 이름(role.value)과 노드 이름을 한 곳에서 맞춘다.
ROLE_NODES: dict[AgentRole, str] = {role: role.value.lower() for role in AgentRole}


class _GraphState(TypedDict):
    """작업 State와 직전 판단·그 판단의 선택지를 함께 전달한다. 외부 API에 반환하지 않는다."""

    state: SupervisorState
    decision: SupervisorDecision | None
    choices: Choices | None


class _ProblemGraphState(TypedDict):
    """판정 모드 그래프의 작업 State와 이번 반복에서 고른 문제별 행동. 외부에 반환하지 않는다."""

    state: SupervisorState
    plan: dict[str, str] | None


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
        problem_actions: bool = False,
    ) -> None:
        """역할 목록을 복사한다. 역할의 호출 순서는 실행 중 모델이 선택한다.

        restrict_roles가 참이면 연결 전 자리(PendingAgent)와 지금 State에서 호출이 성립하지 않는
        역할을 선택지에서 뺀다(supervisor.choices). 거짓이면 등록된 역할을 모두 선택지로 둔다.
        """
        self._agents = dict(pending_agents() if agents is None else agents)
        self._restrict_roles = restrict_roles
        # 참이면 상담 실행을 문제별 행동 경로로 돈다(판정 모드). 사례 초안은 기존 경로 그대로다.
        self._problem_actions = problem_actions

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
        # 판정 모드는 상담 실행에서만 쓴다. 사례 초안은 Curator를 쓰는 기존 경로다.
        judgment = self._problem_actions and request.kind == RunKind.INQUIRY_ANALYSIS
        initial = create_initial_state(request, judgment=judgment)
        runtime = SupervisorRuntime(initial["input"], services)
        last = _LastConfirmed(initial)

        try:
            last.state = transition_phase(runtime.sync_state(initial), "RUNNING")
            # 각 순환은 모델 호출을 소비한다. 그래프 자체 한도도 호출 상한에 맞춘다.
            limit = min(request.limits.maxModelCalls, services.runtime.budget.max_llm_calls)
            recursion_limit = PROBLEM_GRAPH_RECURSION_LIMIT if judgment else 2 * limit + 4
            await runtime.run(self._stream(runtime, last, recursion_limit, judgment=judgment))
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
        usage = final["usage"]
        logger.info(
            "실행 종료 runId=%s status=%s modelCalls=%d toolCalls=%d",
            request.runId,
            result.status,
            usage["calls"],
            usage["toolCalls"],
        )
        return result

    async def _stream(
        self,
        runtime: SupervisorRuntime,
        last: _LastConfirmed,
        recursion_limit: int,
        *,
        judgment: bool = False,
    ) -> None:
        """그래프를 끝까지 실행하며 단계마다 확정된 State를 기록한다."""
        if judgment:
            graph = self._build_problem_graph(runtime)
            first: _GraphState | _ProblemGraphState = _ProblemGraphState(
                state=last.state, plan=None
            )
        else:
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
        for name in ROLE_NODES.values():
            graph.add_edge(name, DECIDE)
        graph.add_edge(FINISH, END)
        return graph.compile()

    async def _decide(self, runtime: SupervisorRuntime, data: _GraphState) -> _GraphState:
        """현재 상태로 다음 행동을 요청하고 검증된 판단만 기록한다."""
        state = _enter(runtime, data, "SUPERVISOR")
        choices = compute_choices(state, self._agents, restrict=self._restrict_roles)
        forced = self._forced_case_decision(state)
        with runtime.services.tracing.observe(
            "SUPERVISOR",
            {
                "registeredRoles": sorted(role.value for role in choices.registered),
                # 사례 실행은 선택지가 하나라 모델 없이 코드가 정한다(같은 검증을 거친다)
                "forced": forced is not None,
            },
        ) as span:
            if forced is not None:
                validate_supervisor_decision(state, forced)
                decision = forced
            else:
                decision = await decide_next_action(state, runtime.services, choices)
            if span is not None:
                span.update(
                    output={
                        "nextAction": decision.nextAction,
                        "role": decision.role.value if decision.role else None,
                        "replyKind": decision.replyKind,
                    }
                )
        state = runtime.sync_state(state)
        state = {**state, "nextAction": decision.nextAction, "replyKind": decision.replyKind}
        # 모델의 공개 요약도 검증되지 않은 문구다. 최종 답변은 최종화 단계에서
        # 준비된 제안과 서버가 제공한 기존 상태를 기준으로 한 번만 구성한다.
        return _GraphState(state=state, decision=decision, choices=choices)

    def _forced_case_decision(self, state: SupervisorState) -> SupervisorDecision | None:
        """사례 실행에서 선택지가 하나뿐인 판단. Curator가 연결돼 있을 때만 모델 없이 정한다.

        사례 제안이 없으면 Curator를 호출하고, 있으면 끝낸다. 상담 실행과 Curator가 연결되지 않은
        구성은 기존대로 모델이 판단한다(연결 전 역할 호출은 안전한 실패로 끝난다).
        """
        if state["input"].kind != RunKind.CASE_DRAFT:
            return None
        curator = self._agents.get(AgentRole.CURATOR)
        if curator is None or isinstance(curator, PendingAgent):
            return None
        if state["caseProposal"] is None:
            return SupervisorDecision(
                nextAction="CALL_ROLE",
                role=AgentRole.CURATOR,
                publicSummary="사례 초안을 정리합니다.",
            )
        return SupervisorDecision(
            nextAction="FINISH", role=None, publicSummary="사례 초안 정리를 마칩니다."
        )

    def _role_node(
        self, runtime: SupervisorRuntime, role: AgentRole
    ) -> Callable[[_GraphState], Awaitable[_GraphState]]:
        """선택된 역할 하나를 실행하고 해당 역할의 병합 검사를 적용하는 노드."""

        async def run_role(data: _GraphState) -> _GraphState:
            decision = data["decision"]
            if decision is None or decision.role != role:
                raise OutputRejected("호출할 역할이 없다")
            merged = await self._call_role(runtime, data["state"], role)
            return _GraphState(state=merged, decision=None, choices=None)

        return run_role

    async def _call_role(
        self,
        runtime: SupervisorRuntime,
        state: SupervisorState,
        role: AgentRole,
        *,
        targets: tuple[str, ...] = (),
        action: str | None = None,
    ) -> SupervisorState:
        """역할 하나를 호출하고 병합 검증을 거친 새 State를 돌려준다. 두 경로가 함께 쓴다."""
        state = _enter_state(runtime, state, role.value)
        with runtime.services.tracing.observe(
            role.value,
            {
                "targetProblemCount": len(state["input"].problemIds),
                "messageCount": len(state["input"].messages),
            },
        ) as span:
            merged = await execute_role(
                state, role, runtime.services, self._agents, targets=targets, action=action
            )
            if span is not None:
                span.update(output=_role_span_output(runtime, role, state, merged))
        return runtime.sync_state(merged)

    def _build_problem_graph(self, runtime: SupervisorRuntime):
        """문제별 행동 경로(판정 모드)의 그래프. 선택과 실행을 종료 행동이 확정될 때까지 반복한다.

        선택(select)은 결과가 없는 문제마다 합법 행동을 계산해 하나씩 고르고, 실행(step)은 고른
        행동을 한 번씩 실행한다. 모든 문제가 종료 행동을 확정하면 마무리(finish)로 간다.
        """

        async def select(data: _ProblemGraphState) -> _ProblemGraphState:
            state = _enter_state(runtime, data["state"], "SUPERVISOR")
            plan = await self._plan(runtime, state)
            return _ProblemGraphState(state=runtime.sync_state(state), plan=plan)

        async def step(data: _ProblemGraphState) -> _ProblemGraphState:
            state = await self._execute_plan(runtime, data["state"], data["plan"] or {})
            return _ProblemGraphState(state=state, plan=None)

        async def finish(data: _ProblemGraphState) -> _ProblemGraphState:
            state = runtime.sync_state(data["state"])
            runtime.ensure_active()
            return _ProblemGraphState(state=transition_phase(state, "FINALIZING"), plan=None)

        def route(data: _ProblemGraphState) -> str:
            return STEP if data["plan"] else FINISH

        graph = StateGraph(_ProblemGraphState)
        graph.add_node(SELECT, select)
        graph.add_node(STEP, step)
        graph.add_node(FINISH, finish)
        graph.add_edge(START, SELECT)
        graph.add_conditional_edges(SELECT, route, {STEP: STEP, FINISH: FINISH})
        graph.add_edge(STEP, SELECT)
        graph.add_edge(FINISH, END)
        return graph.compile()

    async def _plan(self, runtime: SupervisorRuntime, state: SupervisorState) -> dict[str, str]:
        """이번 반복에서 할 행동. 실행 단위 행동(분석·대화 응답)이 먼저, 모두 확정하면 비어 있다.

        분석하지 않은 메시지가 확인된 인사·감사뿐이면 분석 없이 대화로 답하고, 분석이 문제를 하나도
        찾지 못했으면 되묻는다. 둘 다 합법 행동이 하나뿐이라 모델을 부르지 않는다.
        """
        if is_reply(state):
            return {}
        analyzed = any(isinstance(item, InquiryResult) for item in state["roleResults"].values())
        replyable = reply_allowed(state)
        run = run_actions(
            analyzed=analyzed,
            courtesy_only=replyable and confirmed_courtesy(state) is not None,
            no_problem_found=replyable,
        )
        if "REPLY" in run:
            return {REPLY_ACTION: "REPLY"}
        if "ANALYZE" in run:
            return {RUN_ACTION: "ANALYZE"}
        pending = pending_problem_refs(state)
        if not pending:
            return {}
        options = {ref: legal_actions(problem_facts(state, ref)) for ref in pending}
        # 정정 직후에는 기존 범위의 판단을 재사용할 수 없으므로 새 범위를 먼저 검색한다.
        for problem in state["input"].problems:
            if problem.reportedSystemCorrectionMessageId and "SEARCH" in options.get(
                problem.id, ()
            ):
                options[problem.id] = frozenset({"SEARCH"})
        with runtime.services.tracing.observe(
            "SUPERVISOR", {"pendingProblemCount": len(pending)}
        ) as span:
            chosen = await choose_actions(state, runtime.services, options)
            if span is not None:
                span.update(output={"actions": dict(chosen)})
        return dict(chosen)

    async def _execute_plan(
        self, runtime: SupervisorRuntime, state: SupervisorState, plan: Mapping[str, str]
    ) -> SupervisorState:
        """고른 행동을 실행한다. 검색·질문처럼 역할이 필요한 행동은 역할을 부르고, 답변·대기·범위
        밖은 모델 없이 확정한다. 티켓은 고른 문제를 모아 한 번에 제안한다."""
        if RUN_ACTION in plan:
            return await self._call_role(runtime, state, AgentRole.INQUIRY)
        if REPLY_ACTION in plan:
            return runtime.sync_state(apply_reply(state))
        tickets: list[str] = []
        for ref, action in plan.items():
            if action in ("SEARCH", "RESEARCH"):
                state = await self._call_role(runtime, state, AgentRole.KNOWLEDGE, targets=(ref,))
            elif action == "ASK":
                # 질문이 같은 문제의 안내와 함께 있으면 병합이 거부하므로 먼저 안내를 치운다.
                state = prepare_decision(state, ref, "ASK")
                state = await self._call_role(
                    runtime, state, AgentRole.INQUIRY, targets=(ref,), action="ASK"
                )
                state = apply_decision(state, ref, "ASK")
            elif action == "TICKET":
                tickets.append(ref)
            elif action in ("ANSWER", "HOLD", "CLOSE"):
                state = apply_decision(state, ref, action)
            else:
                raise OutputRejected("실행할 수 없는 행동이다")
        if tickets:
            for ref in tickets:
                state = prepare_decision(state, ref, "TICKET")
            state = await self._call_role(runtime, state, AgentRole.TICKET, targets=tuple(tickets))
            for ref in tickets:
                state = apply_decision(state, ref, "TICKET")
        return runtime.sync_state(state)

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


def _enter_state(runtime: SupervisorRuntime, state: SupervisorState, label: str) -> SupervisorState:
    """`_enter`와 같되 그래프 State가 아니라 작업 State를 직접 받는다."""
    synced = runtime.sync_state(state)
    runtime.services.runtime.report_progress(label)
    return synced


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
        # 검색 힌트의 품질을 측정에서 확인할 수 있게 남긴다. 사용자 응답에는 쓰지 않는다.
        output["analysisByProblem"] = {
            ref: {"keywords": item.keywords, "featureCandidates": item.featureCandidates}
            for ref, item in merged["analysis"].items()
        }
    return output
