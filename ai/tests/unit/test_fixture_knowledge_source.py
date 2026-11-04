"""개발용 고정 자료 검색의 접근 범위·결정성·근거 형식 검사.

고정 데이터의 키워드 일치만 확인한다. 검색 품질이나 권한 서버(Spring) 연동을 검증하지 않는다.
"""

import hashlib

import pytest

from hops_ai.contracts.wire import KnowledgeEvidence
from hops_ai.services.retrieval.fixture_documents import FIXTURE_DOCUMENTS
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.services.retrieval.knowledge_source import KnowledgeQuery

# 가상 시스템 ID. 실제 DB의 ID가 아니다.
APPROVAL_ID = "11111111-1111-4111-8111-111111111111"
LOGIN_ID = "22222222-2222-4222-8222-222222222222"
FILES_ID = "33333333-3333-4333-8333-333333333333"
BY_CODE = {
    "ELECTRONIC_APPROVAL": APPROVAL_ID,
    "COMMON_LOGIN": LOGIN_ID,
    "FILE_SHARING": FILES_ID,
}


def make_query(
    text: str,
    *,
    scopes: tuple[str, ...] = ("EMPLOYEE",),
    systems: tuple[str, ...] = (APPROVAL_ID, LOGIN_ID, FILES_ID),
    top_k: int = 5,
) -> KnowledgeQuery:
    return KnowledgeQuery(
        text=text,
        allowed_scopes=frozenset(scopes),
        system_ids=frozenset(systems),
        system_ids_by_code=BY_CODE,
        top_k=top_k,
    )


async def test_how_to_question_finds_the_employee_guide() -> None:
    hits = await FixtureKnowledgeSource().search(make_query("전자결재 문서 조회 방법을 알려주세요"))

    assert [hit.evidence.title for hit in hits] == ["전자결재 문서 조회 방법"]
    evidence = hits[0].evidence
    assert evidence.kind == "GUIDE"
    assert evidence.origin == "SYNTHETIC"
    assert evidence.systemId == APPROVAL_ID
    assert evidence.accessScope == "EMPLOYEE"


async def test_staff_runbook_is_never_returned_to_employee_scope() -> None:
    hits = await FixtureKnowledgeSource().search(
        make_query("문서 조회가 안 돼요 안 보여요 미표시 점검")
    )

    assert all(hit.evidence.accessScope == "EMPLOYEE" for hit in hits)
    assert all(hit.evidence.kind != "RUNBOOK" for hit in hits)


async def test_staff_scope_can_read_runbook_in_its_system_only() -> None:
    source = FixtureKnowledgeSource()
    text = "문서 조회 미표시 점검"

    both = await source.search(make_query(text, scopes=("EMPLOYEE", "SYSTEM_STAFF")))
    other_system = await source.search(
        make_query(text, scopes=("EMPLOYEE", "SYSTEM_STAFF"), systems=(LOGIN_ID,))
    )

    assert any(hit.evidence.kind == "RUNBOOK" for hit in both)
    assert other_system == []


async def test_systems_outside_access_scope_are_excluded() -> None:
    hits = await FixtureKnowledgeSource().search(
        make_query("전자결재 문서 조회 방법", systems=(LOGIN_ID, FILES_ID))
    )

    assert hits == []


async def test_unknown_system_code_is_excluded_instead_of_guessed() -> None:
    query = KnowledgeQuery(
        text="전자결재 문서 조회 방법",
        allowed_scopes=frozenset({"EMPLOYEE"}),
        system_ids=frozenset({APPROVAL_ID}),
        system_ids_by_code={},
    )

    assert await FixtureKnowledgeSource().search(query) == []


async def test_no_match_is_an_empty_list() -> None:
    assert await FixtureKnowledgeSource().search(make_query("점심 메뉴 추천해 주세요")) == []


async def test_symptom_report_does_not_match_how_to_guides() -> None:
    hits = await FixtureKnowledgeSource().search(make_query("어제 올린 결재가 목록에 안 나와요"))

    assert hits == []


async def test_results_are_ordered_and_limited_deterministically() -> None:
    source = FixtureKnowledgeSource()
    query = make_query("문서 조회 방법과 공유 방법, 접근 신청 방법")

    first = await source.search(query)
    second = await source.search(query)
    limited = await source.search(make_query(query.text, top_k=1))

    assert [hit.evidence.id for hit in first] == [hit.evidence.id for hit in second]
    assert len(first) == 3
    assert [hit.score for hit in first] == sorted((hit.score for hit in first), reverse=True)
    assert len(limited) == 1
    assert limited[0].evidence.id == first[0].evidence.id


async def test_hit_carries_digest_of_the_approved_body_bytes() -> None:
    hits = await FixtureKnowledgeSource().search(make_query("전자결재 문서 조회 방법"))

    hit = hits[0]
    assert hit.reference.contentDigest == hashlib.sha256(hit.content.encode("utf-8")).hexdigest()
    assert hit.reference.documentId == hit.evidence.documentId
    assert hit.reference.documentVersion == hit.evidence.documentVersion
    assert hit.reference.accessScope == hit.evidence.accessScope
    assert hit.evidence.excerpt == hit.content
    # 공통 계약의 근거 형식을 그대로 통과한다.
    assert KnowledgeEvidence.model_validate(hit.evidence.model_dump(mode="json")) == hit.evidence


async def test_identifiers_do_not_change_between_searches() -> None:
    first = (await FixtureKnowledgeSource().search(make_query("전자결재 문서 조회 방법")))[0]
    second = (await FixtureKnowledgeSource().search(make_query("전자결재 문서 조회 방법")))[0]

    assert first.evidence == second.evidence


def test_fixture_documents_are_consistent() -> None:
    keys = [document.key for document in FIXTURE_DOCUMENTS]
    assert len(keys) == len(set(keys))
    # 담당자 전용 자료는 런북뿐이고, 직원 공개 자료는 가이드뿐이다.
    for document in FIXTURE_DOCUMENTS:
        assert (document.kind == "RUNBOOK") == (document.access_scope == "SYSTEM_STAFF")
        assert document.keywords


@pytest.mark.parametrize("text", ["", "   ", "\n"])
def test_blank_query_is_rejected(text: str) -> None:
    with pytest.raises(ValueError):
        make_query(text)


@pytest.mark.parametrize("top_k", [0, 6, -1])
def test_top_k_outside_contract_limit_is_rejected(top_k: int) -> None:
    with pytest.raises(ValueError):
        make_query("조회 방법", top_k=top_k)
