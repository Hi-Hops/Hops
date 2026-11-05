"""실행기가 전문 역할을 한 번 호출하고 검증된 결과만 State에 병합한다."""

from collections.abc import Mapping
from copy import deepcopy
from uuid import uuid4

from hops_ai.agents.base import Agent
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import (
    AuthorizedKnowledgeRef,
    CuratorResult,
    InquiryResult,
    KnowledgeResult,
    TicketResult,
)
from hops_ai.contracts.state import RoleRequest, RoleResponse
from hops_ai.contracts.wire import KnowledgeEvidence
from hops_ai.harness.errors import ComponentNotReady, OutputRejected
from hops_ai.services.shared import SharedServices
from hops_ai.supervisor.merge import (
    merge_curator_result,
    merge_inquiry_result,
    merge_knowledge_result,
    merge_ticket_result,
)
from hops_ai.supervisor.policy import validate_role_for_run
from hops_ai.supervisor.state import SupervisorState
from hops_ai.supervisor.validation import ensure_active


async def execute_role(
    state: SupervisorState,
    role: AgentRole,
    services: SharedServices,
    agents: Mapping[AgentRole, Agent],
) -> SupervisorState:
    """허용된 전문 역할 하나를 호출하고 해당 역할의 병합 검증을 적용한다.

    호출 예약과 결과 병합은 새 State에서 수행한다. 역할에 넘기는 스냅샷도 별도로
    복사하므로, 역할 실행이나 검증에 실패해도 전달받은 state는 바뀌지 않는다.
    이 함수는 역할의 호출 순서를 정하거나 실패한 역할을 자동 재실행하지 않는다.

    Args:
        state: 아직 활성 역할과 미완료 호출이 없는 RUNNING State.
        role: Supervisor 판단과 실행 종류별 정책을 통과할 전문 역할.
        services: 같은 run의 예산·취소 제한을 공유하는 서비스. 시간 제한과 누적 사용량은
            실행기의 서비스 wrapper가 관리하고, 전체 await 취소는 상위 실행기가 맡는다.
        agents: 서버가 등록한 역할 구현체. 모델이 등록 항목을 추가할 수 없다.

    Returns:
        검증된 역할 결과를 반영하고 호출 예약을 해제한 새 State.

    Raises:
        ComponentNotReady: 선택한 역할이 등록되지 않았거나 구현되지 않은 경우.
        OutputRejected: 실행 상태·등록 역할·결과 종류·호출 ID·병합 검증이 잘못된 경우.
        DeadlineExceeded / RunCancelled / BudgetExceeded: 실행 조건이 더 이상 유효하지 않음.
        RetrievalUnavailable: Knowledge가 검색 오류를 반환한 경우. EMPTY로 바꾸지 않는다.
    """
    if (
        state["phase"] != "RUNNING"
        or state["finalResult"] is not None
        or state["activeRole"] is not None
        or state["pendingCalls"]
    ):
        raise OutputRejected("다른 호출이 없는 실행 중 상태에서만 역할을 시작할 수 있다")
    ensure_active(state)
    services.runtime.ensure_active()
    validate_role_for_run(RunKind(state["input"].kind), role)
    agent = agents.get(role)
    if agent is None:
        raise ComponentNotReady(role.value)
    if not isinstance(agent.role, AgentRole) or agent.role != role:
        raise OutputRejected("등록된 에이전트의 역할이 호출 대상과 다르다")

    # 호출 식별자와 예약은 실행기가 소유한다. 전문 역할에는 별도 스냅샷만 넘긴다.
    invocation_id = str(uuid4())
    reserved = {**state, "activeRole": role, "pendingCalls": {invocation_id}}
    request = RoleRequest(invocation_id=invocation_id, state=deepcopy(reserved))
    response = await agent.run(request, services)

    # 취소·기한 뒤 늦게 도착한 결과를 병합하기 전에 다시 차단한다.
    services.runtime.ensure_active()
    ensure_active(reserved)
    if isinstance(response, RoleResponse):
        result = response.result
        authorized_refs = response.authorized_refs
        allowed_entity_ids = response.allowed_entity_ids
        verified_evidence = response.verified_evidence
        verified_tickets = response.verified_ticket_results
    else:
        result = response
        authorized_refs = ()
        allowed_entity_ids = None
        verified_evidence = ()
        verified_tickets = ()

    expected_type = {
        AgentRole.INQUIRY: InquiryResult,
        AgentRole.KNOWLEDGE: KnowledgeResult,
        AgentRole.TICKET: TicketResult,
        AgentRole.CURATOR: CuratorResult,
    }[role]
    if not isinstance(result, expected_type):
        raise OutputRejected("호출한 역할과 반환 결과의 종류가 다르다")
    if result.invocationId != invocation_id:
        raise OutputRejected("역할 결과의 호출 ID가 실행기 예약과 다르다")
    if authorized_refs and role != AgentRole.KNOWLEDGE:
        raise OutputRejected("검색 권한 확인 자료는 Knowledge 결과에만 전달할 수 있다")
    if not isinstance(verified_evidence, tuple) or any(
        not isinstance(item, KnowledgeEvidence) for item in verified_evidence
    ):
        raise OutputRejected("검증된 검색 근거 자료의 형식이 잘못됐다")
    if verified_evidence and role != AgentRole.KNOWLEDGE:
        raise OutputRejected("검증된 검색 근거는 Knowledge 결과에만 전달할 수 있다")
    if verified_tickets and role != AgentRole.TICKET:
        raise OutputRejected("티켓 도구 검증 결과는 Ticket 역할만 반환할 수 있다")
    if allowed_entity_ids is not None and role != AgentRole.CURATOR:
        raise OutputRejected("기존 관계 대상은 Curator 결과에만 전달할 수 있다")

    if isinstance(result, InquiryResult):
        return merge_inquiry_result(reserved, result)
    if isinstance(result, KnowledgeResult):
        if not isinstance(authorized_refs, tuple) or any(
            not isinstance(item, AuthorizedKnowledgeRef) for item in authorized_refs
        ):
            raise OutputRejected("검색 권한 확인 자료의 형식이 잘못됐다")
        return merge_knowledge_result(
            reserved,
            result,
            authorized_refs=list(authorized_refs),
            verified_evidence=verified_evidence,
        )
    if isinstance(result, TicketResult):
        return merge_ticket_result(reserved, result, verified_results=verified_tickets)

    # 재사용하지 않는 사례는 정상 결과다. 재사용 초안에는 어댑터 검증 자료가 필요하다.
    if result.proposal.reusable and allowed_entity_ids is None:
        raise OutputRejected("사례 초안의 기존 관계 대상이 검증되지 않았다")
    if allowed_entity_ids is not None and (
        not isinstance(allowed_entity_ids, Mapping)
        or any(
            not isinstance(relation, str)
            or not isinstance(ids, set)
            or any(not isinstance(item, str) for item in ids)
            for relation, ids in allowed_entity_ids.items()
        )
    ):
        raise OutputRejected("기존 관계 대상 검증 자료의 형식이 잘못됐다")
    return merge_curator_result(
        reserved,
        result,
        allowed_entity_ids={key: set(ids) for key, ids in (allowed_entity_ids or {}).items()},
    )
