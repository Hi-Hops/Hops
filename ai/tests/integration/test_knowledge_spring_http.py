"""AI의 Spring 지식·관계 읽기 클라이언트 → 실제 Spring HTTP → PostgreSQL(#191 시드).

LLM만 명시적 대역이다. 전용 hops_knowledge_http_it DB에 개발용 시드(조직·직원·지식 문서·사례·관계)가
적재된 Spring이 떠 있고 HOPS_KNOWLEDGE_HTTP_TEST_* 환경변수를 준비할 때만 실행한다.
사용자 DB(hops)나 기존 로컬 서비스를 쓰지 않는다.
실제 OpenAI 모델·검색 품질·프론트 E2E는 검증하지 않는다. 모델 판단은 Fake 대본이다.
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
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import PolicyDenied, RetrievalUnavailable
from hops_ai.harness.policy import ToolPolicy
from hops_ai.services.retrieval.knowledge_source import (
    KNOWLEDGE_TOOLS,
    KnowledgeQuery,
    RunLink,
)
from hops_ai.services.retrieval.relation_source import RelationQuery
from hops_ai.services.retrieval.spring_source import SpringKnowledgeSource, SpringRelationSource
from hops_ai.services.runs.supervisor import SupervisorPipeline
from hops_ai.settings import Settings
from hops_ai.supervisor.decision import SupervisorDecision
from tests.doubles.llm import FakeLLMClient

ENV_PREFIX = "HOPS_KNOWLEDGE_HTTP_TEST_"
REQUESTER = "a0000000-0000-7000-8000-000000000101"
TEXT = "전자결재에서 지난 문서를 조회하는 방법을 알려주세요"


def bearer(secret: str, actor: str, department: str) -> str:
    def encode(data: bytes) -> str:
        return base64.urlsafe_b64encode(data).decode().rstrip("=")

    now = int(time.time())
    claims = dict(
        sub=actor,
        iss="hops",
        aud=["hops-api"],
        iat=now,
        exp=now + 600,
        policyRevision=1,
        departmentId=department,
        primarySystemIds=[],
        roles=[],
    )
    data = encode(b'{"alg":"HS256"}') + "." + encode(json.dumps(claims).encode())
    return data + "." + encode(hmac.new(secret.encode(), data.encode(), hashlib.sha256).digest())


@dataclass
class Spring:
    db: object
    settings: Settings
    request: AiRunRequest
    token: str

    def context(self) -> RunContext:
        context = RunContext.create(
            run_id=self.request.runId,
            inquiry_id=self.request.inquiryId,
            inquiry_version=self.request.inquiryVersion,
            access_scope_ref="knowledge-http-test",
            settings=self.settings,
        )
        context.tool_policy = ToolPolicy.of({AgentRole.KNOWLEDGE: KNOWLEDGE_TOOLS})
        return context

    def link(self, context: RunContext, problem_ref: str = "new:1") -> RunLink:
        def reserve() -> float:
            context.ensure_active()
            context.tool_policy.authorize(AgentRole.KNOWLEDGE, "search_knowledge")
            context.budget.start_tool_call()
            return context.budget.remaining_seconds()

        return RunLink(self.request.runId, self.request.inquiryVersion, problem_ref, reserve)

    def system_id(self, code: str) -> str:
        return str(self.db.execute("SELECT id FROM it_system WHERE code=%s", (code,)).fetchone()[0])


@pytest.fixture
async def spring():
    values = {
        name: os.getenv(ENV_PREFIX + name) for name in ("ORIGIN", "DSN", "TOKEN", "JWT_SECRET")
    }
    if not all(values.values()):
        pytest.skip("전용 Spring 지식 HTTP 검증 환경을 설정하지 않았다")
    if conninfo_to_dict(values["DSN"]).get("dbname") != "hops_knowledge_http_it":
        pytest.fail("지식 HTTP 검증은 hops_knowledge_http_it DB만 허용한다")
    settings = Settings(
        _env_file=None, spring_base_url=values["ORIGIN"], spring_service_token=values["TOKEN"]
    )
    db = psycopg.connect(values["DSN"], autocommit=True)
    inquiry = None
    try:
        department = str(
            db.execute("SELECT department_id FROM member WHERE id=%s", (REQUESTER,)).fetchone()[0]
        )
        system = str(
            db.execute("SELECT id FROM it_system WHERE code='ELECTRONIC_APPROVAL'").fetchone()[0]
        )
        token = bearer(values["JWT_SECRET"], REQUESTER, department)
        request_id = str(uuid4())
        async with httpx.AsyncClient(base_url=settings.spring_base_url, trust_env=False) as client:
            response = await client.post(
                "/api/v2/inquiries",
                headers={"Authorization": "Bearer " + token, "Idempotency-Key": request_id},
                json={"requestId": request_id, "content": TEXT, "reportedSystemId": system},
            )
        assert response.status_code == 200, response.text
        inquiry = response.json()["resource"]["id"]
        row = db.execute(
            "SELECT input_snapshot FROM agent_run WHERE inquiry_id=%s", (inquiry,)
        ).fetchone()
        yield Spring(db, settings, AiRunRequest.model_validate(row[0]), token)
    finally:
        if inquiry:
            db.execute("UPDATE inquiry SET current_run_id=NULL WHERE id=%s", (inquiry,))
            db.execute("DELETE FROM inquiry_problem WHERE inquiry_id=%s", (inquiry,))
            db.execute("DELETE FROM agent_run WHERE inquiry_id=%s", (inquiry,))
            db.execute("DELETE FROM message WHERE inquiry_id=%s", (inquiry,))
            db.execute("DELETE FROM inquiry WHERE id=%s", (inquiry,))
        db.execute("DELETE FROM idempotency_key WHERE actor_member_id=%s", (REQUESTER,))
        db.close()


def decision(action: str, role: AgentRole | None = None) -> SupervisorDecision:
    return SupervisorDecision(nextAction=action, role=role, publicSummary="진행합니다.")


def scripted_llm(request: AiRunRequest) -> FakeLLMClient:
    extracted = ExtractedProblem(
        summary="전자결재 지난 문서 조회 방법 문의",
        intent="HOW_TO",
        systemCode="ELECTRONIC_APPROVAL",
        taskOrScreen=QuotedValue(messageId=request.messages[0].id, quote="문서를 조회"),
        currentWorkflow=None,
        expectedBehavior=None,
        actualBehavior=None,
        occurredAt=None,
        requestedAccess=None,
        businessPurpose=None,
        desiredChange=None,
        unknownFields=[],
    )
    return FakeLLMClient(
        SupervisorDecision(
            nextAction="CALL_ROLE", role=AgentRole.INQUIRY, publicSummary="문의 확인"
        ),
        InquiryExtraction(kind="PROBLEMS", problems=[extracted]),
        SupervisorDecision(
            nextAction="CALL_ROLE", role=AgentRole.KNOWLEDGE, publicSummary="안내 검색"
        ),
        SupervisorDecision(nextAction="PROVIDE_GUIDE", role=None, publicSummary="안내"),
    )


def employee_query(spring: Spring, context: RunContext, text: str, **changes) -> KnowledgeQuery:
    system = spring.system_id("ELECTRONIC_APPROVAL")
    fields = dict(
        text=text,
        allowed_scopes=frozenset({"EMPLOYEE"}),
        system_ids=frozenset({system}),
        system_ids_by_code={},
        top_k=5,
        run=spring.link(context),
    )
    fields.update(changes)
    return KnowledgeQuery(**fields)


# ---- 전체 경로: DB 문서로 사용법 안내


async def test_how_to_guide_is_built_from_the_approved_document_in_the_database(spring):
    """ "가이드 있는 사용법" 시나리오. 안내문은 DB의 승인된 가이드 본문이다."""
    context = spring.context()
    source = SpringKnowledgeSource(spring.settings)
    pipeline = SupervisorPipeline(scripted_llm(spring.request), knowledge_source=source)

    final = await pipeline.run(spring.request, context)

    assert final.status == "SUCCEEDED", final.error
    assert final.guide is not None
    content = spring.db.execute(
        "SELECT v.content FROM knowledge_document_version v JOIN knowledge_document d "
        "ON d.id=v.knowledge_document_id WHERE d.source_data->>'documentKey'=%s",
        ("guide-ea-document-search",),
    ).fetchone()[0]
    # 안내문은 모델이 쓴 문장이 아니라 DB에 승인된 원문이다.
    assert "전체 문서" in content and "전체 문서" in final.answer
    assert final.questions == [] and final.ticketProposals == []
    kinds = {item.kind for item in final.evidence}
    assert kinds <= {"GUIDE", "POLICY"}
    assert {item.accessScope for item in final.evidence} == {"EMPLOYEE"}
    # Spring 호출은 실행 전체의 도구 호출 한도를 소비한다.
    assert context.budget.tool_calls_used == 1


# ---- 접근 범위와 0건·실패의 구분(실제 Spring)


async def test_employee_run_never_receives_staff_material(spring):
    context = spring.context()
    source = SpringKnowledgeSource(spring.settings)
    runbook_title = spring.db.execute(
        "SELECT title FROM knowledge_document WHERE source_data->>'documentKey'=%s",
        ("runbook-ea-document-not-listed",),
    ).fetchone()[0]

    hits = await source.search(employee_query(spring, context, runbook_title))

    assert all(hit.evidence.kind in ("GUIDE", "POLICY") for hit in hits)
    assert all(hit.evidence.accessScope == "EMPLOYEE" for hit in hits)
    assert not any("점검 순서" in hit.content for hit in hits)


async def test_no_match_is_empty_while_failures_are_not(spring):
    context = spring.context()

    none = await SpringKnowledgeSource(spring.settings).search(
        employee_query(spring, context, "zzzzqqqq wwwwxxxx")
    )
    assert list(none) == []

    wrong_token = Settings(
        _env_file=None,
        spring_base_url=spring.settings.spring_base_url,
        spring_service_token="d3JvbmctdG9rZW4td3JvbmctdG9rZW4td3JvbmctdG9rZW4",
    )
    with pytest.raises(PolicyDenied):
        await SpringKnowledgeSource(wrong_token).search(
            employee_query(spring, spring.context(), "전자결재 문서 조회")
        )

    unreachable = Settings(
        _env_file=None,
        spring_base_url="http://127.0.0.1:9",
        spring_service_token=spring.settings.spring_service_token,
    )
    with pytest.raises(RetrievalUnavailable):
        await SpringKnowledgeSource(unreachable).search(
            employee_query(spring, spring.context(), "전자결재 문서 조회")
        )

    # 문의가 바뀐 뒤의 오래된 실행은 읽을 수 없다(409). 빈 결과로 바뀌지 않는다.
    stale = employee_query(spring, spring.context(), "전자결재 문서 조회")
    stale = KnowledgeQuery(
        stale.text,
        stale.allowed_scopes,
        stale.system_ids,
        stale.system_ids_by_code,
        stale.top_k,
        RunLink(spring.request.runId, spring.request.inquiryVersion + 1, "new:1", lambda: 5.0),
    )
    with pytest.raises(RetrievalUnavailable):
        await SpringKnowledgeSource(spring.settings).search(stale)


# ---- 관계 조회(실제 Spring)


async def test_relation_search_returns_only_employee_askable_items(spring):
    context = spring.context()
    source = SpringRelationSource(spring.settings)
    query = RelationQuery(
        keywords=("문서함에 문서가 안 보여요",),
        feature_ids=frozenset(),
        system_ids=frozenset({spring.system_id("ELECTRONIC_APPROVAL")}),
        allowed_scopes=frozenset({"EMPLOYEE"}),
        limit=5,
        run=spring.link(context),
    )

    result = await source.search(query)

    assert result.internal_judgement is False
    symptom = next(item for item in result.symptoms if item.catalogKey == "sym-ea-doc-not-listed")
    assert symptom.causes is None
    assert symptom.checkItems and all(check.employeeDisclosable for check in symptom.checkItems)
    assert all(check.label is None and check.questions for check in symptom.checkItems)
    keys = {check.catalogKey for check in symptom.checkItems}
    assert "check-ea-view-permission-record" not in keys
