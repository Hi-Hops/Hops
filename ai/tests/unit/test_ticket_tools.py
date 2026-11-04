"""티켓 REST 어댑터·업무 도구의 한도와 불확실성 처리. HTTP는 명시적인 대역이다."""

import asyncio
from dataclasses import replace

import httpx
import pytest
from pydantic import ValidationError

from hops_ai.agents.ticket.executing import ExecutingTicketAgent
from hops_ai.agents.ticket.proposal import TicketDecision, TicketDecisions
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import TicketResult
from hops_ai.contracts.wire import SpringTicketResult, TicketExecution
from hops_ai.harness.errors import (
    ComponentNotReady,
    DeadlineExceeded,
    OutputRejected,
    PolicyDenied,
    RunCancelled,
)
from hops_ai.harness.policy import ToolPolicy
from hops_ai.services.tickets import TICKET_TOOLS, TicketHTTPClient, creation_command
from hops_ai.settings import Settings
from hops_ai.supervisor.execution import execute_role
from hops_ai.supervisor.finalize import finalize_result
from hops_ai.supervisor.merge import merge_ticket_result
from hops_ai.supervisor.runtime import SupervisorRuntime
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import (
    MESSAGE,
    PROBLEM,
    SYSTEM,
    make_problem,
    make_request,
    reserve,
    running_state,
    uid,
)
from tests.unit.test_ticket_model import setup_call


def result(outcome="STORED", attempt=1, **changes):
    return {
        "contractVersion": "hops-system-1",
        "runId": uid(100),
        "problemRef": PROBLEM,
        "workItemId": PROBLEM,
        "problemId": PROBLEM,
        "inquiryVersion": 2,
        "outcome": outcome,
        "ticketId": uid(300) if outcome == "STORED" else None,
        "responsibleSystemId": SYSTEM if outcome == "STORED" else None,
        "assigneeId": uid(200) if outcome == "STORED" else None,
        "attempt": attempt,
        "maxAttempts": 3,
        "retryable": outcome == "NOT_CREATED" and attempt < 3,
        "errorCode": "ASSIGNMENT_UNAVAILABLE" if outcome == "NOT_CREATED" else None,
        "updatedAt": "2026-11-04T00:00:00Z",
        **changes,
    }


def prepare(*responses, settings=None, policy=None):
    request = make_request()
    settings = settings or Settings(
        _env_file=None,
        spring_base_url="http://spring:8080",
        spring_service_token="A" * 43,
        ticket_poll_interval_seconds=0,
    )
    state, services, _ = setup_call(request=request)
    services.runtime.tool_policy = policy or ToolPolicy.of({AgentRole.TICKET: TICKET_TOOLS})
    SupervisorRuntime(request, services)  # 서버·요청 한도를 같은 실행 예산에 연결한다.
    source = make_problem()
    source = source.model_copy(
        update={"facts": [source.facts[0].model_copy(update={"text": request.messages[0].content})]}
    )
    from tests.supervisor_fixtures import make_ticket

    proposal = make_ticket().model_copy(
        update={
            "standardRequestSnapshot": make_ticket().standardRequestSnapshot.model_copy(
                update={"confirmedFacts": source.facts}
            )
        }
    )
    command = creation_command(request, source, proposal, [])
    calls = []
    queue = list(responses)

    async def handler(http):
        calls.append(http)
        assert http.headers["Authorization"] == "Bearer " + "A" * 43
        assert http.url.host == "spring"
        value = queue.pop(0)
        if isinstance(value, Exception):
            raise value
        if callable(value):
            return await value(http)
        return httpx.Response(200, json=value) if isinstance(value, dict) else httpx.Response(value)

    session = TicketHTTPClient(settings, transport=httpx.MockTransport(handler)).bind(
        request, services.runtime
    )
    return session, command, calls, services


async def test_create_then_readback_and_reentry_reuses_verified_success():
    session, command, calls, services = prepare(result(), result())
    outcome = await session.execute(AgentRole.TICKET, command)
    assert outcome.result.outcome == "STORED"
    assert [r.method for r in calls] == ["POST", "GET"]
    assert services.runtime.budget.tool_calls_used == 2
    assert await session.execute(AgentRole.TICKET, command) == outcome
    assert len(calls) == 2


async def test_lost_post_response_queries_existing_work_without_another_create():
    session, command, calls, _ = prepare(httpx.ReadTimeout("synthetic"), result())
    assert (await session.execute(AgentRole.TICKET, command)).result.outcome == "STORED"
    assert [r.method for r in calls] == ["POST", "GET"]


async def test_uncertain_retry_cannot_reuse_earlier_not_created_as_current_result():
    session, command, calls, _ = prepare(
        result("NOT_CREATED"),
        result("NOT_CREATED"),
        httpx.ReadTimeout("synthetic retry loss"),
        404,
        404,
        404,
    )
    outcome = await session.execute(AgentRole.TICKET, command)
    assert outcome.result is None and outcome.errorCode == "CREATION_RESULT_UNCONFIRMED"
    assert sum(request.method == "POST" for request in calls) == 2


async def test_stale_read_before_uncertain_retry_does_not_trigger_more_writes():
    session, command, calls, _ = prepare(
        result("NOT_CREATED"),
        result("NOT_CREATED"),
        httpx.ReadTimeout("synthetic retry loss"),
        result("NOT_CREATED"),
        result("NOT_CREATED"),
        result("NOT_CREATED"),
    )
    outcome = await session.execute(AgentRole.TICKET, command)
    assert outcome.result is None
    assert sum(request.method == "POST" for request in calls) == 2


async def test_unknown_or_404_never_proves_non_creation_and_queries_are_bounded():
    session, command, calls, _ = prepare(httpx.ReadTimeout("synthetic"), 404, 404, 404, 404)
    outcome = await session.execute(AgentRole.TICKET, command)
    assert outcome.result is None and outcome.errorCode == "CREATION_RESULT_UNCONFIRMED"
    assert [r.method for r in calls] == ["POST"] + ["GET"] * 4
    await session.execute(AgentRole.TICKET, command)
    assert len(calls) == 5


async def test_only_confirmed_not_created_retries_and_stops_after_three_attempts():
    session, command, calls, _ = prepare(
        result("NOT_CREATED"),
        result("NOT_CREATED"),
        result("NOT_CREATED", 2),
        result("NOT_CREATED", 2),
        result("NOT_CREATED", 3),
        result("NOT_CREATED", 3),
    )
    outcome = await session.execute(AgentRole.TICKET, command)
    assert outcome.result.attempt == 3 and not outcome.result.retryable
    assert len(calls) == 6
    assert [r.method for r in calls] == ["POST", "GET", "POST", "GET", "POST", "GET"]
    import json

    assert json.loads(calls[2].content)["expectedAttempt"] == 1
    assert json.loads(calls[4].content)["expectedAttempt"] == 2


async def test_tool_retries_share_the_run_retry_budget_with_model_retries():
    session, command, calls, services = prepare(
        result("NOT_CREATED"),
        result("NOT_CREATED"),
        result("NOT_CREATED", 2),
        result("NOT_CREATED", 2),
    )
    services.runtime.budget.retries_used = 1
    outcome = await session.execute(AgentRole.TICKET, command)
    assert outcome.result.attempt == 2 and outcome.errorCode == "CREATION_RETRY_LIMIT"
    assert services.runtime.budget.retries_used == 2 and len(calls) == 4


@pytest.mark.parametrize("state", ["PENDING", "UNKNOWN"])
async def test_pending_and_unknown_are_read_only(state):
    session, command, calls, _ = prepare(*(result(state) for _ in range(5)))
    outcome = await session.execute(AgentRole.TICKET, command)
    assert outcome.result.outcome == state
    assert sum(r.method == "POST" for r in calls) == 1


@pytest.mark.parametrize(
    "changes",
    [
        {"runId": uid(999)},
        {"problemId": uid(999)},
        {"inquiryVersion": 9},
        {"ticketId": None},
        {"responsibleSystemId": uid(999)},
        {"stored": True},
    ],
)
async def test_wrong_or_forged_remote_result_never_confirms_creation(changes):
    session, command, calls, _ = prepare(result(**changes))
    outcome = await session.execute(AgentRole.TICKET, command)
    assert outcome.result is None and outcome.errorCode == "UPSTREAM_PROTOCOL_ERROR"
    assert len(calls) == 1


@pytest.mark.parametrize("status", [401, 403])
async def test_auth_denial_is_final(status):
    session, command, calls, _ = prepare(status)
    with pytest.raises(PolicyDenied):
        await session.execute(AgentRole.TICKET, command)
    assert len(calls) == 1


async def test_non_retryable_400_does_not_retry_or_claim_not_created():
    session, command, calls, _ = prepare(400)
    outcome = await session.execute(AgentRole.TICKET, command)
    assert outcome.result is None and outcome.errorCode == "SPRING_REQUEST_REJECTED"
    assert len(calls) == 1


async def test_other_role_and_unregistered_policy_make_no_http_requests():
    session, command, calls, _ = prepare(result())
    with pytest.raises(PolicyDenied):
        await session.execute(AgentRole.INQUIRY, command)
    assert not calls


async def test_tool_budget_exhaustion_preserves_last_verified_state():
    session, command, calls, services = prepare(result("NOT_CREATED"))
    services.runtime.budget.max_tool_calls = 1
    outcome = await session.execute(AgentRole.TICKET, command)
    assert outcome.result.outcome == "NOT_CREATED" and outcome.errorCode == "TOOL_BUDGET_EXHAUSTED"
    assert len(calls) == 1


async def test_cancelled_and_expired_run_do_not_call_spring():
    for cancelled in (True, False):
        session, command, calls, services = prepare(result())
        if cancelled:
            services.runtime.cancel()
        else:
            services.runtime.budget.deadline = 0
        with pytest.raises(RunCancelled if cancelled else DeadlineExceeded):
            await session.execute(AgentRole.TICKET, command)
        assert not calls


async def test_http_timeout_does_not_reset_execution_deadline():
    async def slow(http):
        await asyncio.sleep(1)
        return httpx.Response(200, json=result())

    settings = Settings(
        _env_file=None,
        spring_base_url="http://spring:8080",
        spring_service_token="A" * 43,
        ticket_http_timeout_seconds=0.01,
        ticket_max_queries=1,
        ticket_poll_interval_seconds=0,
    )
    session, command, calls, _ = prepare(slow, 404, settings=settings)
    outcome = await session.execute(AgentRole.TICKET, command)
    assert outcome.result is None and len(calls) == 2


def test_model_cannot_supply_a_business_success_without_adapter_verification():
    from tests.supervisor_fixtures import make_ticket

    state = reserve(running_state(), AgentRole.TICKET, "ticket")
    fake = TicketExecution(
        problemRef=PROBLEM, result=SpringTicketResult.model_validate(result()), errorCode=None
    )
    with pytest.raises(OutputRejected):
        merge_ticket_result(
            state,
            TicketResult(invocationId="ticket", proposals=[make_ticket()], creationResults=[fake]),
        )


async def test_executing_agent_returns_verified_results_and_public_confirmation():
    session, command, _, services = prepare(result(), result())
    request = make_request()
    state = running_state(request)
    from hops_ai.contracts.wire import ProblemProposal

    state["problems"][PROBLEM] = ProblemProposal.model_validate(command["problem"])
    services = replace(
        services,
        ticket_tools=session,
        llm=FakeLLMClient(
            TicketDecisions(
                decisions=[
                    TicketDecision(
                        problemRef=PROBLEM,
                        title="로그인 오류 조사",
                        responsibleSystemCandidateId=SYSTEM,
                        supports=[],
                        knowledgeEvidenceIds=[],
                    )
                ]
            )
        ),
    )
    merged = await execute_role(
        state, AgentRole.TICKET, services, {AgentRole.TICKET: ExecutingTicketAgent()}
    )
    final = finalize_result(merged)["finalResult"]
    assert final.ticketCreationResults[0].result.ticketId == uid(300)
    assert "티켓 생성과 담당자 배정이 확인되었습니다" in final.answer


def test_unquotable_source_fails_before_any_creation_and_korean_spans_use_codepoints():
    from tests.supervisor_fixtures import make_ticket

    request = make_request()
    with pytest.raises(OutputRejected):
        creation_command(request, make_problem(), make_ticket(), [])
    p = make_problem().model_copy(update={"facts": []})
    command = creation_command(
        request,
        p,
        make_ticket().model_copy(update={"evidenceIds": [MESSAGE]}),
        [{"sourceId": MESSAGE, "quote": "첨부파일"}],
    )
    [span] = command["messageLinks"][0]["spans"]
    assert request.messages[0].content[span["start"] : span["end"]] == "첨부파일"


@pytest.mark.parametrize(
    "origin", ["https://user:password@spring", "http://spring/path", "http://spring?token=bad"]
)
def test_origin_cannot_carry_paths_or_auth(origin):
    with pytest.raises(ValidationError):
        Settings(_env_file=None, spring_base_url=origin)


def test_missing_spring_config_does_not_enable_fake_storage():
    with pytest.raises(ComponentNotReady):
        TicketHTTPClient(Settings(_env_file=None))
