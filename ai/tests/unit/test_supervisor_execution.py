"""전문 역할 호출 예약·독립 스냅샷·검증 자료·늦은 결과 차단을 확인한다."""

from collections.abc import Callable
from copy import deepcopy
from dataclasses import dataclass
from uuid import UUID

import pytest

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import CuratorResult, InquiryResult, KnowledgeResult, TicketResult
from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import (
    ComponentNotReady,
    DeadlineExceeded,
    OutputRejected,
    RetrievalUnavailable,
    RunCancelled,
)
from hops_ai.services.shared import SharedServices
from hops_ai.supervisor.execution import RoleRequest, RoleResponse, execute_role
from tests.supervisor_fixtures import (
    EVIDENCE,
    PROBLEM,
    SYSTEM,
    authorize,
    make_case,
    make_evidence,
    make_problem,
    make_request,
    make_ticket,
    running_state,
    uid,
)


@dataclass
class StubAgent:
    """서버 역할 어댑터의 반환값을 명시적으로 지정하는 테스트 대역."""

    role: AgentRole
    result_factory: Callable[[RoleRequest], object]
    calls: int = 0

    async def run(self, request: RoleRequest, services: SharedServices) -> object:
        self.calls += 1
        return self.result_factory(request)


@pytest.fixture
def services(clock, make_settings) -> SharedServices:
    """실제 외부 연결 없이 취소·기한만 검사할 공유 서비스를 만든다."""
    runtime = RunContext.create(
        run_id=uid(100),
        inquiry_id=uid(101),
        inquiry_version=2,
        access_scope_ref="test-scope",
        settings=make_settings(),
        clock=clock,
    )
    return SharedServices(runtime=runtime, llm=None, vector=None, graph=None, tools=None)


async def test_inquiry_reserves_call_and_keeps_original_snapshot(services) -> None:
    """역할의 로컬 수정은 입력/사용량에 반영되지 않고 검증된 제안만 병합된다."""
    state = running_state()
    original = deepcopy(state)

    def respond(request):
        assert str(UUID(request.invocation_id)) == request.invocation_id
        assert request.state["pendingCalls"] == {request.invocation_id}
        assert request.state["activeRole"] == AgentRole.INQUIRY
        request.state["input"].messages.clear()
        request.state["usage"]["calls"] = 999
        request.state["problems"].clear()
        return InquiryResult(
            invocationId=request.invocation_id, problems=[make_problem()], questions=[]
        )

    agent = StubAgent(AgentRole.INQUIRY, respond)
    result = await execute_role(state, AgentRole.INQUIRY, services, {agent.role: agent})
    assert state == original
    assert result["input"] == state["input"]
    assert result["usage"] == state["usage"]
    assert result["problems"][PROBLEM] == make_problem()
    assert result["activeRole"] is None and not result["pendingCalls"]
    assert len(result["roleResults"]) == 1 and agent.calls == 1


async def test_ticket_response_uses_existing_merge_guards(services) -> None:
    agent = StubAgent(
        AgentRole.TICKET,
        lambda request: TicketResult(invocationId=request.invocation_id, proposals=[make_ticket()]),
    )
    state = running_state()
    result = await execute_role(state, agent.role, services, {agent.role: agent})
    assert result["ticketProposals"][PROBLEM] == make_ticket()
    assert not state["ticketProposals"]
    with pytest.raises(OutputRejected):
        await execute_role(
            running_state(make_request(outcome="STORED")), agent.role, services, {agent.role: agent}
        )


@pytest.mark.parametrize("action", ["raw_json", "wrong_type", "wrong_id"])
async def test_unexpected_role_result_is_rejected_atomically(services, action) -> None:
    state = running_state()
    original = deepcopy(state)

    def respond(request):
        if action == "raw_json":
            return {"invocationId": request.invocation_id, "problems": [], "questions": []}
        if action == "wrong_type":
            return TicketResult(invocationId=request.invocation_id, proposals=[])
        return InquiryResult(invocationId="model-generated-id", problems=[], questions=[])

    agent = StubAgent(AgentRole.INQUIRY, respond)
    with pytest.raises(OutputRejected):
        await execute_role(state, agent.role, services, {agent.role: agent})
    assert state == original and agent.calls == 1


@pytest.mark.parametrize("change", ["queued", "active", "pending", "disallowed"])
async def test_invalid_state_or_role_never_starts_agent(services, change) -> None:
    state = running_state()
    role = AgentRole.INQUIRY
    if change == "queued":
        state = {**state, "phase": "QUEUED"}
    elif change == "active":
        state = {**state, "activeRole": "SUPERVISOR"}
    elif change == "pending":
        state = {**state, "pendingCalls": {"another-call"}}
    else:
        role = AgentRole.CURATOR
    agent = StubAgent(role, lambda request: None)
    with pytest.raises(OutputRejected):
        await execute_role(state, role, services, {role: agent})
    assert agent.calls == 0


async def test_missing_and_mismatched_registration_are_rejected(services) -> None:
    with pytest.raises(ComponentNotReady):
        await execute_role(running_state(), AgentRole.INQUIRY, services, {})
    agent = StubAgent(AgentRole.TICKET, lambda request: None)
    with pytest.raises(OutputRejected):
        await execute_role(running_state(), AgentRole.INQUIRY, services, {AgentRole.INQUIRY: agent})
    assert agent.calls == 0


@pytest.mark.parametrize("late", [False, True])
async def test_cancellation_blocks_start_or_late_result(services, late) -> None:
    state = running_state()

    def respond(request):
        services.runtime.cancel()
        return InquiryResult(invocationId=request.invocation_id, problems=[], questions=[])

    agent = StubAgent(AgentRole.INQUIRY, respond)
    if not late:
        services.runtime.cancel()
    with pytest.raises(RunCancelled):
        await execute_role(state, agent.role, services, {agent.role: agent})
    assert agent.calls == int(late)
    assert not state["roleResults"] and not state["pendingCalls"]


async def test_late_result_after_runtime_deadline_is_rejected(services, clock) -> None:
    def respond(request):
        clock.advance(60)
        return InquiryResult(invocationId=request.invocation_id, problems=[], questions=[])

    agent = StubAgent(AgentRole.INQUIRY, respond)
    with pytest.raises(DeadlineExceeded):
        await execute_role(running_state(), agent.role, services, {agent.role: agent})


@pytest.mark.parametrize("metadata", ["raw", "authorization_only", "evidence_only", "verified"])
async def test_knowledge_found_requires_authorization_and_verified_evidence(
    services, metadata
) -> None:
    """허용 문서 참조만으로는 부족하며 어댑터가 검증한 정확한 근거도 필요하다."""
    evidence = make_evidence()

    def respond(request):
        result = KnowledgeResult(
            invocationId=request.invocation_id,
            search={"state": "FOUND", "evidence": [evidence], "error": None},
            guide=None,
        )
        if metadata == "raw":
            return result
        return RoleResponse(
            result,
            authorized_refs=() if metadata == "evidence_only" else (authorize(evidence),),
            verified_evidence=() if metadata == "authorization_only" else (evidence,),
        )

    agent = StubAgent(AgentRole.KNOWLEDGE, respond)
    state = running_state()
    if metadata == "verified":
        result = await execute_role(state, agent.role, services, {agent.role: agent})
        assert result["evidence"][EVIDENCE] == evidence
    else:
        with pytest.raises(OutputRejected):
            await execute_role(state, agent.role, services, {agent.role: agent})
    assert not state["evidence"]


@pytest.mark.parametrize("field", ["excerpt", "title", "id"])
async def test_model_cannot_replace_verified_knowledge_fields(services, field) -> None:
    """허용된 문서 버전이 같아도 모델이 근거의 본문·메타데이터를 바꾸면 거부한다."""
    evidence = make_evidence()
    changed = evidence.model_copy(
        update={field: uid(999) if field == "id" else "모델이 새로 작성한 합성 문구"}
    )
    agent = StubAgent(
        AgentRole.KNOWLEDGE,
        lambda request: RoleResponse(
            KnowledgeResult(
                invocationId=request.invocation_id,
                search={"state": "FOUND", "evidence": [changed], "error": None},
                guide=None,
            ),
            authorized_refs=(authorize(evidence),),
            verified_evidence=(evidence,),
        ),
    )
    state = running_state()
    before = deepcopy(state)
    with pytest.raises(OutputRejected):
        await execute_role(state, agent.role, services, {agent.role: agent})
    assert state == before


@pytest.mark.parametrize("shape", ["list", "model_json"])
async def test_verified_evidence_requires_python_model_tuple(services, shape) -> None:
    """검증된 근거를 닮은 모델 JSON이나 임의 컨테이너를 신뢰 자료로 받지 않는다."""
    evidence = make_evidence()
    metadata = [evidence] if shape == "list" else (evidence.model_dump(mode="json"),)
    agent = StubAgent(
        AgentRole.KNOWLEDGE,
        lambda request: RoleResponse(
            KnowledgeResult(
                invocationId=request.invocation_id,
                search={"state": "FOUND", "evidence": [evidence], "error": None},
                guide=None,
            ),
            authorized_refs=(authorize(evidence),),
            verified_evidence=metadata,
        ),
    )
    with pytest.raises(OutputRejected):
        await execute_role(running_state(), agent.role, services, {agent.role: agent})


async def test_non_knowledge_role_cannot_supply_verified_evidence(services) -> None:
    """Inquiry가 검증 자료 봉투를 사용해 Knowledge의 근거 작성 권한을 얻지 못한다."""
    agent = StubAgent(
        AgentRole.INQUIRY,
        lambda request: RoleResponse(
            InquiryResult(invocationId=request.invocation_id, problems=[], questions=[]),
            verified_evidence=(make_evidence(),),
        ),
    )
    with pytest.raises(OutputRejected):
        await execute_role(running_state(), agent.role, services, {agent.role: agent})


@pytest.mark.parametrize("status", ["EMPTY", "ERROR"])
async def test_knowledge_empty_is_normal_but_error_is_failure(services, status) -> None:
    error = (
        {
            "code": "SEARCH_UNAVAILABLE",
            "message": "검색할 수 없다",
            "requestId": uid(800),
            "retryable": True,
            "fieldErrors": [],
        }
        if status == "ERROR"
        else None
    )
    agent = StubAgent(
        AgentRole.KNOWLEDGE,
        lambda request: KnowledgeResult(
            invocationId=request.invocation_id,
            search={"state": status, "evidence": [], "error": error},
            guide=None,
        ),
    )
    if status == "ERROR":
        with pytest.raises(RetrievalUnavailable):
            await execute_role(running_state(), agent.role, services, {agent.role: agent})
    else:
        result = await execute_role(running_state(), agent.role, services, {agent.role: agent})
        assert not result["evidence"] and len(result["roleResults"]) == 1


@pytest.mark.parametrize("mode", ["missing", "allowed", "not_reusable"])
async def test_curator_uses_trusted_existing_entities_only(services, mode) -> None:
    state = running_state(make_request(kind=RunKind.CASE_DRAFT))

    def respond(request):
        result = CuratorResult(
            invocationId=request.invocation_id, proposal=make_case(reusable=mode != "not_reusable")
        )
        if mode == "allowed":
            return RoleResponse(result, allowed_entity_ids={"APPLIES_TO": {SYSTEM}})
        return result

    agent = StubAgent(AgentRole.CURATOR, respond)
    if mode == "missing":
        with pytest.raises(OutputRejected):
            await execute_role(state, agent.role, services, {agent.role: agent})
    else:
        result = await execute_role(state, agent.role, services, {agent.role: agent})
        assert result["caseProposal"].reusable == (mode == "allowed")
    assert state["caseProposal"] is None


async def test_role_exception_is_not_retried_and_original_state_is_unchanged(services) -> None:
    def respond(request):
        raise RuntimeError("private provider error")

    state = running_state()
    original = deepcopy(state)
    agent = StubAgent(AgentRole.INQUIRY, respond)
    with pytest.raises(RuntimeError):
        await execute_role(state, agent.role, services, {agent.role: agent})
    assert agent.calls == 1 and state == original


async def test_model_json_cannot_supply_authorization_metadata(services) -> None:
    """권한 API 응답을 닮은 dict를 Python 어댑터의 검증 참조로 간주하지 않는다."""
    evidence = make_evidence()
    agent = StubAgent(
        AgentRole.KNOWLEDGE,
        lambda request: RoleResponse(
            KnowledgeResult(
                invocationId=request.invocation_id,
                search={"state": "FOUND", "evidence": [evidence], "error": None},
                guide=None,
            ),
            authorized_refs=(authorize(evidence).model_dump(mode="json"),),
        ),
    )
    with pytest.raises(OutputRejected):
        await execute_role(running_state(), agent.role, services, {agent.role: agent})


async def test_invalid_existing_entity_metadata_does_not_bypass_curator_guard(services) -> None:
    """기존 엔티티 허용 자료의 잘못된 구조를 빈 허용 목록으로 대체하지 않는다."""
    agent = StubAgent(
        AgentRole.CURATOR,
        lambda request: RoleResponse(
            CuratorResult(invocationId=request.invocation_id, proposal=make_case()),
            allowed_entity_ids={"APPLIES_TO": [SYSTEM]},
        ),
    )
    with pytest.raises(OutputRejected):
        await execute_role(
            running_state(make_request(kind=RunKind.CASE_DRAFT)),
            agent.role,
            services,
            {agent.role: agent},
        )
