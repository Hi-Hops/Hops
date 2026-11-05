"""Spring 내부 읽기 API 클라이언트(SpringKnowledgeSource·SpringRelationSource).

HTTP는 httpx MockTransport 대역이다. 실제 Spring·DB·검색 품질은 검증하지 않는다.
검색 0건과 검색 실패의 구분, 인증, 응답 재검증, 도구 호출 예약을 확인한다.
"""

import hashlib
import json
import logging

import httpx
import pytest

from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.errors import (
    BudgetExceeded,
    ComponentNotReady,
    InputRejected,
    PolicyDenied,
    RetrievalUnavailable,
)
from hops_ai.harness.policy import ToolPolicy
from hops_ai.services.retrieval.knowledge_source import (
    KNOWLEDGE_TOOLS,
    SEARCH_KNOWLEDGE,
    KnowledgeQuery,
    RunLink,
)
from hops_ai.services.retrieval.relation_source import RelationQuery
from hops_ai.services.retrieval.spring_source import SpringKnowledgeSource, SpringRelationSource
from hops_ai.settings import Settings
from hops_ai.supervisor.execution import execute_role
from tests.supervisor_fixtures import OTHER_SYSTEM, PROBLEM, SYSTEM, make_evidence, uid
from tests.unit.test_knowledge_agent import how_to_request, running_state, services_for

TOKEN = "dGVzdC10b2tlbi1mb3ItdGlja2V0LWNyZWF0aW9uLWFwaQ"
RUN = uid(900)
CONTENT = "문서를 찾는 방법입니다.\n1. 문서 조회를 선택합니다."


def settings(**changes) -> Settings:
    return Settings(
        _env_file=None,
        spring_base_url="http://spring.test",
        spring_service_token=TOKEN,
        **changes,
    )


class Reserve:
    """도구 호출 예약 대역. 호출 횟수를 세고 정해진 오류를 낼 수 있다."""

    def __init__(self, remaining: float = 30.0, error: Exception | None = None) -> None:
        self.calls = 0
        self.remaining = remaining
        self.error = error

    def __call__(self) -> float:
        self.calls += 1
        if self.error is not None:
            raise self.error
        return self.remaining


def link(reserve: Reserve | None = None, problem: str = PROBLEM) -> RunLink:
    return RunLink(RUN, 1, problem, reserve or Reserve())


def knowledge_query(**changes) -> KnowledgeQuery:
    fields = dict(
        text="전자결재에서 지난 문서를 찾는 방법",
        allowed_scopes=frozenset({"EMPLOYEE"}),
        system_ids=frozenset({SYSTEM}),
        system_ids_by_code={},
        top_k=5,
        run=link(),
    )
    fields.update(changes)
    return KnowledgeQuery(**fields)


def result_item(content: str = CONTENT, **evidence_changes) -> dict:
    evidence = make_evidence(excerpt=content, **evidence_changes).model_dump(mode="json")
    return {
        "evidence": evidence,
        "documentKey": "guide-test",
        "content": content,
        "contentDigest": hashlib.sha256(content.encode("utf-8")).hexdigest(),
        "digestBasis": "CONTENT",
        "score": 0.75,
        "caseIndexStatus": None,
    }


def knowledge_body(outcome="FOUND", results=None, **changes) -> dict:
    body = {
        "contractVersion": "hops-system-1",
        "runId": RUN,
        "problemRef": PROBLEM,
        "outcome": outcome,
        "deniedReason": None,
        "applied": {
            "accessScopes": ["EMPLOYEE"],
            "kinds": ["GUIDE", "POLICY"],
            "systemIds": [SYSTEM],
            "topK": 5,
            "internalJudgement": False,
        },
        "results": [result_item()] if results is None and outcome == "FOUND" else (results or []),
    }
    body.update(changes)
    return body


def source_returning(status: int = 200, body=None, *, captured: list | None = None, **kwargs):
    def handler(request: httpx.Request) -> httpx.Response:
        if captured is not None:
            captured.append(request)
        if isinstance(body, Exception):
            raise body
        content = body if isinstance(body, bytes) else json.dumps(body).encode()
        return httpx.Response(status, content=content, headers={"content-type": "application/json"})

    return SpringKnowledgeSource(settings(**kwargs), transport=httpx.MockTransport(handler))


# ---- 요청


async def test_request_uses_the_fixed_origin_service_token_and_narrowing_fields_only() -> None:
    captured: list[httpx.Request] = []
    source = source_returning(200, knowledge_body(), captured=captured)

    await source.search(knowledge_query())

    request = captured[0]
    assert request.method == "POST"
    assert str(request.url) == f"http://spring.test/internal/v2/runs/{RUN}/knowledge-searches"
    assert request.headers["authorization"] == f"Bearer {TOKEN}"
    body = json.loads(request.content)
    assert body == {
        "inquiryVersion": 1,
        "problemRef": PROBLEM,
        "query": "전자결재에서 지난 문서를 찾는 방법",
        "topK": 5,
        "systemIds": [SYSTEM],
    }
    # 접근 범위·목적·사용자는 요청에 싣지 않는다. Spring이 실행에서 확정한다.
    assert not {"accessScope", "purpose", "allowedDocumentScopes", "retrievalScopes"} & set(body)


async def test_query_text_and_system_list_are_capped_to_the_contract_limits() -> None:
    captured: list[httpx.Request] = []
    source = source_returning(200, knowledge_body("NO_RESULT"), captured=captured)
    systems = frozenset(uid(1000 + number) for number in range(15))

    await source.search(knowledge_query(text="가" * 5000, system_ids=systems))

    body = json.loads(captured[0].content)
    assert len(body["query"]) == 4000
    assert body["systemIds"] == sorted(systems)[:10]


async def test_each_search_reserves_exactly_one_tool_call_before_the_request() -> None:
    reserve = Reserve()
    source = source_returning(200, knowledge_body())

    await source.search(knowledge_query(run=link(reserve)))

    assert reserve.calls == 1


async def test_exhausted_budget_stops_before_any_http_call() -> None:
    captured: list[httpx.Request] = []
    reserve = Reserve(error=BudgetExceeded())
    source = source_returning(200, knowledge_body(), captured=captured)

    with pytest.raises(BudgetExceeded):
        await source.search(knowledge_query(run=link(reserve)))

    assert captured == []


async def test_no_time_left_is_a_retrieval_failure_without_a_request() -> None:
    captured: list[httpx.Request] = []
    source = source_returning(200, knowledge_body(), captured=captured)

    with pytest.raises(RetrievalUnavailable):
        await source.search(knowledge_query(run=link(Reserve(remaining=0.0))))

    assert captured == []


async def test_search_without_a_run_link_is_rejected_loudly() -> None:
    with pytest.raises(InputRejected):
        await source_returning(200, knowledge_body()).search(knowledge_query(run=None))


def test_source_needs_the_spring_address_and_token() -> None:
    with pytest.raises(ComponentNotReady):
        SpringKnowledgeSource(Settings(_env_file=None))
    with pytest.raises(ComponentNotReady):
        SpringRelationSource(Settings(_env_file=None, spring_base_url="http://spring.test"))


# ---- 결과 변환


async def test_found_becomes_hits_with_the_authorized_reference_and_approved_content() -> None:
    hits = await source_returning(200, knowledge_body()).search(knowledge_query())

    assert len(hits) == 1
    hit = hits[0]
    assert hit.content == CONTENT
    assert hit.score == 0.75
    assert hit.evidence.accessScope == "EMPLOYEE" and hit.evidence.kind == "GUIDE"
    assert hit.reference.documentId == hit.evidence.documentId
    assert hit.reference.contentDigest == hashlib.sha256(CONTENT.encode("utf-8")).hexdigest()


async def test_no_result_is_an_empty_list() -> None:
    hits = await source_returning(200, knowledge_body("NO_RESULT")).search(knowledge_query())

    assert list(hits) == []


async def test_unknown_response_fields_are_ignored() -> None:
    body = knowledge_body()
    body["futureField"] = {"a": 1}
    body["results"][0]["extra"] = True

    assert len(await source_returning(200, body).search(knowledge_query())) == 1


async def test_snapshot_digest_of_a_case_is_carried_without_recomputing() -> None:
    item = result_item(kind="CASE", accessScope="SYSTEM_STAFF", origin="SYNTHETIC", systemId=SYSTEM)
    item["digestBasis"] = "APPROVED_SNAPSHOT"
    item["contentDigest"] = "b" * 64
    query = knowledge_query(allowed_scopes=frozenset({"EMPLOYEE", "SYSTEM_STAFF"}))

    hits = await source_returning(200, knowledge_body(results=[item])).search(query)

    assert hits[0].reference.contentDigest == "b" * 64


# ---- 검색 실패는 빈 목록이 아니다


async def test_denied_is_a_retrieval_failure_not_an_empty_list() -> None:
    body = knowledge_body("DENIED", deniedReason="KIND_NOT_ALLOWED")

    with pytest.raises(RetrievalUnavailable):
        await source_returning(200, body).search(knowledge_query())


@pytest.mark.parametrize("status", [400, 404, 409, 429, 500, 503])
async def test_http_failures_are_retrieval_failures(status: int) -> None:
    with pytest.raises(RetrievalUnavailable):
        await source_returning(status, {"code": "SEARCH_UNAVAILABLE"}).search(knowledge_query())


@pytest.mark.parametrize("status", [401, 403])
async def test_authentication_and_permission_failures_are_policy_denied(status: int) -> None:
    with pytest.raises(PolicyDenied):
        await source_returning(status, {"code": "ACCESS_DENIED"}).search(knowledge_query())


@pytest.mark.parametrize(
    "error",
    [httpx.ConnectError("refused"), httpx.ReadTimeout("slow"), httpx.RemoteProtocolError("bad")],
)
async def test_transport_failures_are_retrieval_failures(error: Exception) -> None:
    with pytest.raises(RetrievalUnavailable):
        await source_returning(200, error).search(knowledge_query())


async def test_redirect_is_not_followed_and_is_a_failure() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(302, headers={"location": "http://evil.test/"})

    source = SpringKnowledgeSource(settings(), transport=httpx.MockTransport(handler))

    with pytest.raises(RetrievalUnavailable):
        await source.search(knowledge_query())


async def test_oversized_response_is_a_failure() -> None:
    big = b"x" * 300_000

    with pytest.raises(RetrievalUnavailable):
        await source_returning(200, big).search(knowledge_query())


# ---- 응답은 신뢰하지 않고 다시 검증한다


@pytest.mark.parametrize(
    "change",
    [
        {"contractVersion": "other"},
        {"runId": uid(901)},
        {"problemRef": uid(11)},
        {"outcome": "UNKNOWN"},
        {"applied": {"accessScopes": ["EMPLOYEE"]}},
    ],
    ids=["version", "run", "problem", "outcome", "applied"],
)
async def test_mismatched_or_malformed_envelope_is_a_failure(change: dict) -> None:
    with pytest.raises(RetrievalUnavailable):
        await source_returning(200, knowledge_body(**change)).search(knowledge_query())


@pytest.mark.parametrize("raw", [b"not json", b"[]", b'{"outcome":"FOUND"}'])
async def test_unparseable_response_is_a_failure(raw: bytes) -> None:
    with pytest.raises(RetrievalUnavailable):
        await source_returning(200, raw).search(knowledge_query())


async def test_status_and_result_list_must_agree() -> None:
    with pytest.raises(RetrievalUnavailable):
        await source_returning(200, knowledge_body("FOUND", results=[])).search(knowledge_query())
    with pytest.raises(RetrievalUnavailable):
        await source_returning(200, knowledge_body("NO_RESULT", results=[result_item()])).search(
            knowledge_query()
        )


async def test_more_results_than_requested_is_a_failure() -> None:
    items = [result_item(id=uid(40 + n), documentId=uid(50 + n)) for n in range(3)]

    with pytest.raises(RetrievalUnavailable):
        await source_returning(200, knowledge_body(results=items)).search(knowledge_query(top_k=2))


async def test_body_that_does_not_match_its_digest_is_rejected_before_model_input() -> None:
    item = result_item()
    item["contentDigest"] = "0" * 64

    with pytest.raises(RetrievalUnavailable):
        await source_returning(200, knowledge_body(results=[item])).search(knowledge_query())


async def test_excerpt_must_be_inside_the_approved_body() -> None:
    item = result_item()
    item["evidence"]["excerpt"] = "본문에 없는 문장"

    with pytest.raises(RetrievalUnavailable):
        await source_returning(200, knowledge_body(results=[item])).search(knowledge_query())


async def test_evidence_outside_the_requested_systems_is_rejected() -> None:
    item = result_item(systemId=OTHER_SYSTEM)

    with pytest.raises(RetrievalUnavailable):
        await source_returning(200, knowledge_body(results=[item])).search(knowledge_query())


async def test_duplicate_evidence_ids_are_rejected() -> None:
    items = [result_item(), result_item()]

    with pytest.raises(RetrievalUnavailable):
        await source_returning(200, knowledge_body(results=items)).search(knowledge_query())


async def test_staff_material_is_not_used_for_an_employee_scope_query(caplog) -> None:
    """내부 판단 읽기(옵션) 응답의 담당자 자료는 사용자 범위 밖이라 근거로 쓰지 않는다."""
    staff = result_item(kind="RUNBOOK", accessScope="SYSTEM_STAFF", id=uid(41), documentId=uid(51))
    body = knowledge_body(results=[result_item(), staff])
    body["applied"]["internalJudgement"] = True
    body["applied"]["accessScopes"] = ["EMPLOYEE", "SYSTEM_STAFF"]

    with caplog.at_level(logging.WARNING):
        hits = await source_returning(200, body).search(knowledge_query())

    assert [hit.evidence.accessScope for hit in hits] == ["EMPLOYEE"]
    assert "사용자 범위 밖" in caplog.text
    # 로그에 본문·검색어를 싣지 않는다.
    assert CONTENT not in caplog.text and "전자결재" not in caplog.text


# ---- Knowledge 역할 연결


async def test_knowledge_agent_calls_spring_through_the_role_policy_and_tool_budget() -> None:
    request = how_to_request()
    captured: list[httpx.Request] = []
    run_id = request.runId
    body = knowledge_body()
    body["runId"] = run_id
    body["problemRef"] = request.problems[0].id
    source = source_returning(200, body, captured=captured)
    services = services_for(request)
    services.runtime.tool_policy = ToolPolicy.of({AgentRole.KNOWLEDGE: KNOWLEDGE_TOOLS})
    state = running_state(request)

    state = await execute_role(
        state, AgentRole.KNOWLEDGE, services, {AgentRole.KNOWLEDGE: SearchKnowledgeAgent(source)}
    )

    assert services.runtime.budget.tool_calls_used == 1
    assert len(captured) == 1 and captured[0].url.path.endswith("/knowledge-searches")
    assert state["guide"] is not None
    assert SEARCH_KNOWLEDGE in KNOWLEDGE_TOOLS


async def test_role_without_the_tool_permission_cannot_search_spring() -> None:
    request = how_to_request()
    captured: list[httpx.Request] = []
    source = source_returning(200, knowledge_body(), captured=captured)

    with pytest.raises(PolicyDenied):
        await execute_role(
            running_state(request),
            AgentRole.KNOWLEDGE,
            services_for(request),
            {AgentRole.KNOWLEDGE: SearchKnowledgeAgent(source)},
        )

    assert captured == []


# ---- 관계 조회


def relation_query(**changes) -> RelationQuery:
    fields = dict(
        keywords=("문서함에 문서가 안 보여요",),
        feature_ids=frozenset(),
        system_ids=frozenset({SYSTEM}),
        allowed_scopes=frozenset({"EMPLOYEE"}),
        limit=5,
        run=link(),
    )
    fields.update(changes)
    return RelationQuery(**fields)


def basis(visible=False) -> dict:
    return {"visible": visible}


def symptom_item(**changes) -> dict:
    item = {
        "id": uid(300),
        "catalogKey": "sym-test",
        "label": "문서가 문서함에 보이지 않음",
        "systemId": SYSTEM,
        "featureId": uid(301),
        "featureCode": "DOCUMENT_VIEW",
        "accessScope": "EMPLOYEE",
        "basis": basis(),
        "score": 0.5,
        "checkItems": [
            {
                "id": uid(310),
                "catalogKey": "check-test",
                "employeeDisclosable": True,
                "accessScope": "SYSTEM_STAFF",
                "basis": basis(),
                "questions": [
                    {
                        "id": uid(320),
                        "catalogKey": "q-test-01",
                        "text": "조회 기간을 어떻게 설정하셨나요?",
                        "basis": basis(),
                    }
                ],
            }
        ],
        "cases": [],
    }
    item.update(changes)
    return item


def relation_body(outcome="FOUND", symptoms=None, internal=False, **changes) -> dict:
    body = {
        "contractVersion": "hops-system-1",
        "runId": RUN,
        "problemRef": PROBLEM,
        "outcome": outcome,
        "deniedReason": None,
        "applied": {
            "accessScopes": ["EMPLOYEE", "SYSTEM_STAFF"] if internal else ["EMPLOYEE"],
            "systemIds": [SYSTEM],
            "limit": 5,
            "internalJudgement": internal,
        },
        "symptoms": [symptom_item()] if symptoms is None and outcome == "FOUND" else symptoms or [],
    }
    body.update(changes)
    return body


def relation_source(status: int = 200, body=None, captured=None) -> SpringRelationSource:
    def handler(request: httpx.Request) -> httpx.Response:
        if captured is not None:
            captured.append(request)
        if isinstance(body, Exception):
            raise body
        return httpx.Response(status, content=json.dumps(body).encode())

    return SpringRelationSource(settings(), transport=httpx.MockTransport(handler))


async def test_relation_request_carries_keywords_features_and_narrowing_only() -> None:
    captured: list[httpx.Request] = []
    source = relation_source(200, relation_body(), captured)
    query = relation_query(feature_ids=frozenset({uid(301)}))

    await source.search(query)

    request = captured[0]
    assert str(request.url) == f"http://spring.test/internal/v2/runs/{RUN}/relation-searches"
    assert request.headers["authorization"] == f"Bearer {TOKEN}"
    assert json.loads(request.content) == {
        "inquiryVersion": 1,
        "problemRef": PROBLEM,
        "limit": 5,
        "keywords": ["문서함에 문서가 안 보여요"],
        "featureIds": [uid(301)],
        "systemIds": [SYSTEM],
    }


async def test_relation_found_returns_symptoms_with_askable_questions() -> None:
    result = await relation_source(200, relation_body()).search(relation_query())

    assert result.internal_judgement is False
    symptom = result.symptoms[0]
    assert symptom.catalogKey == "sym-test" and symptom.causes is None
    check = symptom.checkItems[0]
    assert check.employeeDisclosable and check.label is None
    assert check.questions[0].text == "조회 기간을 어떻게 설정하셨나요?"


async def test_relation_no_result_is_empty_and_failures_are_not() -> None:
    empty = await relation_source(200, relation_body("NO_RESULT")).search(relation_query())
    assert empty.symptoms == ()

    with pytest.raises(RetrievalUnavailable):
        await relation_source(
            200, relation_body("DENIED", deniedReason="FEATURE_NOT_ALLOWED")
        ).search(relation_query())
    for status in (400, 503):
        with pytest.raises(RetrievalUnavailable):
            await relation_source(status, {"code": "X"}).search(relation_query())
    with pytest.raises(PolicyDenied):
        await relation_source(403, {"code": "ACCESS_DENIED"}).search(relation_query())
    with pytest.raises(RetrievalUnavailable):
        await relation_source(200, httpx.ConnectError("refused")).search(relation_query())


async def test_relation_response_with_staff_items_for_an_employee_scope_is_rejected() -> None:
    cause = {
        "id": uid(330),
        "catalogKey": "cause-test",
        "label": "내부 원인",
        "accessScope": "SYSTEM_STAFF",
        "basis": basis(),
        "checkItemKeys": [],
    }
    leaking = [
        symptom_item(causes=[cause]),
        symptom_item(accessScope="SYSTEM_STAFF"),
    ]
    staff_check = symptom_item()
    staff_check["checkItems"][0]["employeeDisclosable"] = False
    leaking.append(staff_check)
    labelled = symptom_item()
    labelled["checkItems"][0]["label"] = "담당자 확인 항목"
    leaking.append(labelled)

    for item in leaking:
        with pytest.raises(RetrievalUnavailable):
            await relation_source(200, relation_body(symptoms=[item])).search(relation_query())


async def test_internal_judgement_result_is_flagged_and_keeps_staff_items() -> None:
    cause = {
        "id": uid(330),
        "catalogKey": "cause-test",
        "label": "내부 원인",
        "accessScope": "SYSTEM_STAFF",
        "basis": basis(True),
        "checkItemKeys": ["check-test"],
    }
    body = relation_body(symptoms=[symptom_item(causes=[cause])], internal=True)

    result = await relation_source(200, body).search(relation_query())

    assert result.internal_judgement is True
    assert result.symptoms[0].causes is not None


async def test_relation_envelope_and_counts_are_checked() -> None:
    for body in (
        relation_body(runId=uid(901)),
        relation_body(problemRef=uid(11)),
        relation_body("FOUND", symptoms=[]),
        relation_body("NO_RESULT", symptoms=[symptom_item()]),
        relation_body(symptoms=[symptom_item(), symptom_item(id=uid(302))]),
    ):
        with pytest.raises(RetrievalUnavailable):
            await relation_source(200, body).search(relation_query(limit=1))


async def test_relation_requires_run_link_and_reserves_one_call() -> None:
    with pytest.raises(InputRejected):
        await relation_source(200, relation_body()).search(relation_query(run=None))
    reserve = Reserve()
    await relation_source(200, relation_body()).search(relation_query(run=link(reserve)))
    assert reserve.calls == 1


def test_relation_query_needs_a_signal_and_a_valid_limit() -> None:
    with pytest.raises(ValueError):
        RelationQuery((), frozenset(), frozenset(), frozenset({"EMPLOYEE"}))
    with pytest.raises(ValueError):
        relation_query(limit=6)
