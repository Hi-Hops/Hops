"""제품 Ticket 어댑터 → 실제 Spring HTTP → PostgreSQL. LLM만 명시적 대역이다.

전용 hops_ticket_tools_test DB와 HOPS_TICKET_HTTP_TEST_* 환경변수를 준비할 때만 실행한다.
사용자 DB·기존 로컬 서비스의 스키마/권한을 변경하지 않는다.
"""

import base64
import hashlib
import hmac
import json
import os
import time
from dataclasses import dataclass
from uuid import uuid4

import httpx
import psycopg
import pytest
from psycopg.conninfo import conninfo_to_dict

from hops_ai.agents.inquiry.extraction import ExtractedProblem, InquiryExtraction, QuotedValue
from hops_ai.agents.ticket.proposal import TicketDecision, TicketDecisions
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.harness.context import RunContext
from hops_ai.services.runs.supervisor import SupervisorPipeline
from hops_ai.services.tickets import TicketHTTPClient
from hops_ai.settings import Settings
from hops_ai.supervisor.decision import SupervisorDecision
from tests.doubles.llm import FakeLLMClient

ENV_PREFIX = "HOPS_TICKET_HTTP_TEST_"
TEXT = (
    "공통 로그인 로그인 화면에서 오류가 납니다. 오늘 오전부터 인증 오류입니다. "
    "로그인이 되어야 합니다. 현재 한 건씩 조회합니다. 일괄 조회를 원합니다."
)


def bearer(secret, actor, department):
    def encode(data):
        return base64.urlsafe_b64encode(data).decode().rstrip("=")

    now = int(time.time())
    claims = dict(
        sub=str(actor),
        iss="hops",
        aud=["hops-api"],
        iat=now,
        exp=now + 600,
        policyRevision=1,
        departmentId=str(department),
        primarySystemIds=[],
        roles=[],
    )
    data = encode(b'{"alg":"HS256"}') + "." + encode(json.dumps(claims).encode())
    return data + "." + encode(hmac.new(secret.encode(), data.encode(), hashlib.sha256).digest())


@dataclass
class SpringFixture:
    db: object
    settings: Settings
    request: AiRunRequest
    owner: str
    actor: str
    token: str

    def context(self, request=None):
        request = request or self.request
        return RunContext.create(
            run_id=request.runId,
            inquiry_id=request.inquiryId,
            inquiry_version=request.inquiryVersion,
            access_scope_ref="http-test",
            settings=self.settings,
        )

    def count(self):
        return self.db.execute(
            "SELECT count(*) FROM ticket WHERE inquiry_id=%s", (self.request.inquiryId,)
        ).fetchone()[0]


@pytest.fixture
async def spring():
    values = {
        name: os.getenv(ENV_PREFIX + name) for name in ("ORIGIN", "DSN", "TOKEN", "JWT_SECRET")
    }
    if not all(values.values()):
        pytest.skip("전용 Spring HTTP 검증 환경을 설정하지 않았다")
    if conninfo_to_dict(values["DSN"]).get("dbname") != "hops_ticket_tools_test":
        pytest.fail("HTTP 검증은 hops_ticket_tools_test DB만 허용한다")
    settings = Settings(
        _env_file=None,
        spring_base_url=values["ORIGIN"],
        spring_service_token=values["TOKEN"],
        ticket_poll_interval_seconds=0,
    )
    department, actor, owner, system = [str(uuid4()) for _ in range(4)]
    db = psycopg.connect(values["DSN"], autocommit=True)
    inquiry = None
    try:
        db.execute(
            "INSERT INTO department(id,code,name) VALUES (%s,%s,'HTTP 가상 부서')",
            (department, "T149_" + department[:8]),
        )
        for member in (actor, owner):
            db.execute(
                "INSERT INTO member(id,login_id,employee_no,password_hash,name,department_id) "
                "VALUES (%s,%s,%s,'unused-test-hash','가상 직원',%s)",
                (member, member, member, department),
            )
        db.execute(
            "INSERT INTO it_system(id,code,name,primary_owner_member_id) "
            "VALUES (%s,'COMMON_LOGIN','공통 로그인',%s)",
            (system, owner),
        )
        token = bearer(values["JWT_SECRET"], actor, department)
        request_id = str(uuid4())
        async with httpx.AsyncClient(base_url=settings.spring_base_url, trust_env=False) as client:
            response = await client.post(
                "/api/v2/inquiries",
                headers={"Authorization": "Bearer " + token, "Idempotency-Key": request_id},
                json={"requestId": request_id, "content": TEXT, "reportedSystemId": system},
            )
        assert response.status_code == 200
        inquiry = response.json()["resource"]["id"]
        row = db.execute(
            "SELECT input_snapshot FROM agent_run WHERE inquiry_id=%s", (inquiry,)
        ).fetchone()
        request = AiRunRequest.model_validate(row[0])
        yield SpringFixture(db, settings, request, owner, actor, token)
    finally:
        if inquiry:
            db.execute("UPDATE inquiry SET current_run_id=NULL WHERE id=%s", (inquiry,))
            db.execute(
                "DELETE FROM ticket_assignment WHERE ticket_id IN "
                "(SELECT id FROM ticket WHERE inquiry_id=%s)",
                (inquiry,),
            )
            db.execute("DELETE FROM ticket WHERE inquiry_id=%s", (inquiry,))
            db.execute(
                "DELETE FROM agent_ticket_request WHERE run_id IN "
                "(SELECT id FROM agent_run WHERE inquiry_id=%s)",
                (inquiry,),
            )
            db.execute(
                "DELETE FROM standard_request WHERE inquiry_problem_id IN "
                "(SELECT id FROM inquiry_problem WHERE inquiry_id=%s)",
                (inquiry,),
            )
            db.execute("DELETE FROM inquiry_problem WHERE inquiry_id=%s", (inquiry,))
            db.execute("DELETE FROM agent_run WHERE inquiry_id=%s", (inquiry,))
            db.execute("DELETE FROM message WHERE inquiry_id=%s", (inquiry,))
            db.execute("DELETE FROM inquiry WHERE id=%s", (inquiry,))
        db.execute("DELETE FROM idempotency_key WHERE actor_member_id=%s", (actor,))
        db.execute("DELETE FROM it_system WHERE id=%s", (system,))
        db.execute("DELETE FROM member WHERE id IN (%s,%s)", (actor, owner))
        db.execute("DELETE FROM department WHERE id=%s", (department,))
        db.close()


def scripted_llm(request, *, compound=False):
    def quote(value):
        return QuotedValue(messageId=request.messages[0].id, quote=value)

    extracted = ExtractedProblem(
        summary="공통 로그인 오류 조사",
        intent="OBSERVED_SYMPTOM",
        systemCode="COMMON_LOGIN",
        taskOrScreen=quote("로그인 화면"),
        expectedBehavior=quote("로그인이 되어야 합니다"),
        actualBehavior=quote("인증 오류입니다"),
        occurredAt=quote("오늘 오전부터"),
        currentWorkflow=None,
        requestedAccess=None,
        businessPurpose=None,
        desiredChange=None,
        unknownFields=[],
        keywords=[],
        featureCandidates=[],
    )
    problems = [extracted]
    decisions = [
        TicketDecision(
            problemRef="new:1",
            title="로그인 오류 조사",
            responsibleSystemCandidateId=request.systems[0].id,
            supports=[],
            knowledgeEvidenceIds=[],
        )
    ]
    if compound:
        problems.append(
            extracted.model_copy(
                update={
                    "summary": "일괄 조회 기능 요청",
                    "intent": "FEATURE_REQUEST",
                    "taskOrScreen": None,
                    "expectedBehavior": None,
                    "actualBehavior": None,
                    "occurredAt": None,
                    "currentWorkflow": quote("현재 한 건씩 조회합니다"),
                    "desiredChange": quote("일괄 조회를 원합니다"),
                }
            )
        )
        decisions.append(
            decisions[0].model_copy(update={"problemRef": "new:2", "title": "일괄 조회 기능 요청"})
        )
    return FakeLLMClient(
        SupervisorDecision(
            nextAction="CALL_ROLE", role=AgentRole.INQUIRY, publicSummary="문의 확인"
        ),
        InquiryExtraction(problems=problems),
        SupervisorDecision(
            nextAction="CALL_ROLE", role=AgentRole.TICKET, publicSummary="티켓 처리"
        ),
        TicketDecisions(decisions=decisions),
        SupervisorDecision(nextAction="PROPOSE_TICKETS", role=None, publicSummary="처리 결과"),
    )


class NetworkFault(httpx.AsyncBaseTransport):
    """모든 패킷은 실제 Spring에 전달한다. 응답 유실/담당자 복구만 테스트에서 주입한다."""

    def __init__(self, hook):
        self.hook = hook
        self.calls = []

    async def handle_async_request(self, request):
        self.calls.append(request)
        async with httpx.AsyncHTTPTransport() as transport:
            response = await transport.handle_async_request(request)
            await response.aread()
        await self.hook(request, response)
        return response


async def run_ticket(spring, transport=None, settings=None, context=None, llm=None):
    client = TicketHTTPClient(settings or spring.settings, transport=transport)
    context = context or spring.context()
    final = await SupervisorPipeline(llm or scripted_llm(spring.request), ticket_client=client).run(
        spring.request, context
    )
    return final, context


async def test_pipeline_creates_and_confirms_real_ticket(spring):
    final, context = await run_ticket(spring)
    assert final.status == "SUCCEEDED"
    [execution] = final.ticketCreationResults
    assert execution.result.outcome == "STORED" and execution.result.assigneeId == spring.owner
    assert spring.count() == 1 and context.budget.tool_calls_used == 2
    assert "티켓 생성과 담당자 배정이 확인되었습니다" in final.answer
    source = spring.db.execute(
        "SELECT confirmed_facts FROM standard_request WHERE inquiry_problem_id=%s",
        (execution.result.problemId,),
    ).fetchone()[0]
    assert len(source) == 4


async def test_lost_creation_response_reads_saved_ticket_without_duplicate(spring):
    async def drop_post(request, response):
        if request.method == "POST":
            assert response.json()["outcome"] == "STORED"
            raise httpx.ReadTimeout("synthetic response loss")

    transport = NetworkFault(drop_post)
    final, _ = await run_ticket(spring, transport)
    assert final.status == "SUCCEEDED" and final.ticketCreationResults[0].result.outcome == "STORED"
    assert [request.method for request in transport.calls] == ["POST", "GET"]
    assert spring.count() == 1


async def test_confirmed_not_created_retries_after_owner_recovers(spring):
    spring.db.execute("UPDATE member SET deleted_at=now() WHERE id=%s", (spring.owner,))

    async def restore_on_read(request, response):
        if request.method == "GET" and response.json().get("outcome") == "NOT_CREATED":
            spring.db.execute("UPDATE member SET deleted_at=NULL WHERE id=%s", (spring.owner,))

    transport = NetworkFault(restore_on_read)
    final, context = await run_ticket(spring, transport)
    actual = final.ticketCreationResults[0].result
    assert final.status == "SUCCEEDED" and actual.outcome == "STORED" and actual.attempt == 2
    assert context.budget.tool_calls_used == 4 and spring.count() == 1


async def test_actual_retry_limit_is_three_attempts(spring):
    spring.db.execute("UPDATE member SET deleted_at=now() WHERE id=%s", (spring.owner,))
    final, context = await run_ticket(spring)
    actual = final.ticketCreationResults[0].result
    assert actual.outcome == "NOT_CREATED" and actual.attempt == 3 and not actual.retryable
    assert context.budget.tool_calls_used == 6 and spring.count() == 0
    assert "생성과 담당자 배정이 확인되었습니다" not in final.answer


async def test_partial_failure_keeps_the_first_ticket_and_both_real_outcomes(spring):
    async def fail_next_owner(request, response):
        if request.method == "GET" and response.json().get("problemRef") == "new:1":
            spring.db.execute("UPDATE member SET deleted_at=now() WHERE id=%s", (spring.owner,))

    final, context = await run_ticket(
        spring, NetworkFault(fail_next_owner), llm=scripted_llm(spring.request, compound=True)
    )
    assert final.status == "SUCCEEDED"
    assert [item.result.outcome for item in final.ticketCreationResults] == [
        "STORED",
        "NOT_CREATED",
    ]
    assert spring.count() == 1 and context.budget.tool_calls_used == 8
    assert "티켓 생성과 담당자 배정이 확인되었습니다" in final.answer
    assert "티켓이 생성되지 않았습니다" in final.answer


async def test_unknown_after_real_http_losses_stops_reading_without_new_creation(spring):
    async def lose_every_response(request, response):
        raise httpx.ReadTimeout("synthetic loss after actual server response")

    transport = NetworkFault(lose_every_response)
    final, context = await run_ticket(spring, transport)
    assert final.status == "SUCCEEDED" and final.ticketCreationResults[0].result is None
    assert final.ticketCreationResults[0].errorCode == "CREATION_RESULT_UNCONFIRMED"
    assert [r.method for r in transport.calls] == ["POST"] + ["GET"] * 4
    assert context.budget.tool_calls_used == 5 and spring.count() == 1
    assert "티켓 저장 여부를 확인하지 못했습니다" in final.answer


async def test_cancellation_after_commit_preserves_saved_work_in_next_server_input(spring):
    context = spring.context()

    async def cancel_after_post(request, response):
        if request.method == "POST":
            context.cancel()

    final, _ = await run_ticket(spring, NetworkFault(cancel_after_post), context=context)
    assert final.status == "SUPERSEDED" and spring.count() == 1
    request_id = str(uuid4())
    async with httpx.AsyncClient(
        base_url=spring.settings.spring_base_url, trust_env=False
    ) as client:
        response = await client.post(
            f"/api/v2/inquiries/{spring.request.inquiryId}/messages",
            headers={"Authorization": "Bearer " + spring.token, "Idempotency-Key": request_id},
            json={
                "requestId": request_id,
                "expectedInquiryVersion": 1,
                "content": "추가 확인입니다",
                "problemIds": [],
            },
        )
    assert response.status_code == 200
    row = spring.db.execute(
        "SELECT input_snapshot FROM agent_run WHERE id="
        "(SELECT current_run_id FROM inquiry WHERE id=%s)",
        (spring.request.inquiryId,),
    ).fetchone()
    next_input = AiRunRequest.model_validate(row[0])
    assert next_input.existingWorkItems[0].outcome == "STORED"
    following = await SupervisorPipeline(
        FakeLLMClient(
            SupervisorDecision(nextAction="FINISH", role=None, publicSummary="기존 결과 확인")
        ),
        ticket_client=TicketHTTPClient(spring.settings),
    ).run(next_input, spring.context(next_input))
    assert following.status == "SUCCEEDED" and spring.count() == 1


async def test_service_auth_failure_is_not_retried_or_reported_as_created(spring):
    wrong = base64.urlsafe_b64encode(b"wrong-test-token" * 3).decode().rstrip("=")
    settings = spring.settings.model_copy(
        update={
            "spring_service_token": Settings(
                _env_file=None, spring_service_token=wrong
            ).spring_service_token
        }
    )
    final, context = await run_ticket(spring, settings=settings)
    assert final.status == "FAILED" and final.error.code == "AUTH_CONTEXT_INVALID"
    assert spring.count() == 0 and context.budget.tool_calls_used == 1


async def test_wrong_problem_knowledge_scope_is_rejected_by_real_spring(spring):
    class WrongScope(httpx.AsyncBaseTransport):
        def __init__(self):
            self.statuses = []

        async def handle_async_request(self, request):
            assert request.method == "POST"
            body = json.loads(request.content)
            assert body["knowledgeScope"]["problemRef"] == body["problem"]["problemRef"]
            body["knowledgeScope"]["problemRef"] = "new:another-problem"
            altered = httpx.Request(
                request.method,
                request.url,
                headers={k: v for k, v in request.headers.items() if k != "content-length"},
                json=body,
            )
            async with httpx.AsyncHTTPTransport() as transport:
                response = await transport.handle_async_request(altered)
                await response.aread()
            self.statuses.append(response.status_code)
            assert response.json()["code"] == "INVALID_INPUT"
            return response

    transport = WrongScope()
    final, _ = await run_ticket(spring, transport)
    assert transport.statuses == [400]
    assert spring.count() == 0
    assert not any(
        item.result and item.result.outcome == "STORED" for item in final.ticketCreationResults
    )
