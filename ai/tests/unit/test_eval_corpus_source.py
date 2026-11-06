"""평가용 코퍼스 검색원. Spring 검색 규칙의 근사이며 실검색 품질을 뜻하지 않는다.

문서 키·시스템 이름은 합성 문서로 만들어 쓰고, 실제 코퍼스는 접근 범위 경계와 읽기 성공만 본다.
"""

import pytest

from evaluation.harness.corpus_source import (
    CorpusDocument,
    CorpusFormatError,
    CorpusKnowledgeSource,
    load_corpus,
    parse_document,
    query_tokens,
)
from hops_ai.services.retrieval.knowledge_source import KnowledgeQuery
from tests.supervisor_fixtures import uid

SYSTEM_A = uid(701)
SYSTEM_B = uid(702)
BY_CODE = {"SYS_A": SYSTEM_A, "SYS_B": SYSTEM_B}


def doc(key: str, *, title="제목", body="본문", kind="GUIDE", scope="EMPLOYEE", system="SYS_A"):
    return CorpusDocument(
        key=key,
        kind=kind,
        system_code=system,
        access_scope=scope,
        title=title,
        applicability="적용 범위",
        version=1,
        body=body,
    )


def query(text, *, scopes=("EMPLOYEE",), systems=(SYSTEM_A,), kinds=None, top_k=5):
    return KnowledgeQuery(
        text=text,
        allowed_scopes=frozenset(scopes),
        system_ids=frozenset(systems),
        system_ids_by_code=BY_CODE,
        top_k=top_k,
        kinds=frozenset(kinds) if kinds else None,
    )


async def search(documents, **kwargs):
    return await CorpusKnowledgeSource(documents).search(query(**kwargs))


# --- 토큰화 ---


def test_tokens_drop_particles_stopwords_short_words_and_duplicates() -> None:
    assert query_tokens("첨부파일이 열리지 않아요 어떻게 해야 하나요 첨부파일") == [
        "첨부파일",
        "열리지",
        "않아요",
    ]
    assert query_tokens("a 가 문서에서 문서") == ["문서"]


def test_tokens_split_on_non_letters_and_cap_at_twenty() -> None:
    assert query_tokens("로그인/SSO,오류") == ["로그인", "sso", "오류"]
    words = " ".join(f"단어{number}" for number in range(30))
    assert len(query_tokens(words)) == 20


def test_a_stem_shorter_than_two_characters_keeps_the_particle() -> None:
    assert query_tokens("문의") == ["문의"]  # "의"를 떼면 한 글자가 남아 떼지 않는다


# --- 검색 규칙 ---


async def test_title_match_outranks_body_only_match() -> None:
    documents = [
        doc("body-only", title="다른 제목", body="결재선 안내"),
        doc("title", title="결재선 보기", body="없음"),
    ]

    hits = await search(documents, text="결재선")

    assert [hit.evidence.title for hit in hits] == ["결재선 보기", "다른 제목"]
    assert hits[0].score > hits[1].score


async def test_three_or_more_tokens_need_two_matches_but_fewer_need_one() -> None:
    documents = [doc("a", body="결재선 안내")]

    assert await search(documents, text="결재선 첨부 이력") == []
    assert len(await search(documents, text="결재선 첨부")) == 1


async def test_ties_break_by_document_key_and_top_k_limits_results() -> None:
    documents = [
        doc("b", title="제목 B", body="같은 단어"),
        doc("a", title="제목 A", body="같은 단어"),
        doc("c", title="제목 C", body="같은 단어"),
    ]

    hits = await search(documents, text="단어", top_k=2)

    assert [hit.evidence.title for hit in hits] == ["제목 A", "제목 B"]


async def test_scope_system_and_kind_filters_apply_before_matching() -> None:
    documents = [
        doc("staff", scope="SYSTEM_STAFF", kind="RUNBOOK", body="증상 처리"),
        doc("other-system", system="SYS_B", body="증상 처리"),
        doc("policy", kind="POLICY", body="증상 처리"),
        doc("guide", body="증상 처리"),
    ]

    employee = await search(documents, text="증상 처리")
    staff = await search(documents, text="증상 처리", scopes=("EMPLOYEE", "SYSTEM_STAFF"))
    only_policy = await search(documents, text="증상 처리", kinds=("POLICY",))
    unreachable = await search(documents, text="증상 처리", systems=(uid(799),))

    assert {hit.evidence.kind for hit in employee} == {"GUIDE", "POLICY"}
    assert "RUNBOOK" in {hit.evidence.kind for hit in staff}
    assert {hit.evidence.kind for hit in only_policy} == {"POLICY"}
    assert unreachable == []
    assert all(hit.evidence.systemId == SYSTEM_A for hit in employee + staff)


async def test_a_hit_carries_the_body_digest_and_a_stable_evidence_id() -> None:
    [first] = await search([doc("a", body="내용 있음")], text="내용")
    [again] = await search([doc("a", body="내용 있음")], text="내용")

    assert first.content == "내용 있음" and first.evidence.excerpt == "내용 있음"
    assert len(first.reference.contentDigest) == 64
    assert first.evidence.id == again.evidence.id


async def test_a_query_with_no_usable_token_finds_nothing() -> None:
    assert await search([doc("a")], text="a 가") == []


# --- 문서 읽기 ---

TEXT = """---
documentKey: guide-x
kind: GUIDE
title: "제목: 부제"
systemCode: SYS_A
accessScope: EMPLOYEE
version: 3
applicability: null
reviewStatus: APPROVED
dataSplit: RAG
source:
  type: AUTHORED
---

## 본문
내용
"""


def test_front_matter_is_parsed_and_nested_keys_are_ignored() -> None:
    parsed = parse_document(TEXT)

    assert (parsed.key, parsed.kind, parsed.version) == ("guide-x", "GUIDE", 3)
    assert parsed.title == "제목: 부제"
    assert parsed.applicability == parsed.title  # null이면 제목으로 대신한다
    assert parsed.body.startswith("## 본문")


@pytest.mark.parametrize(
    "text",
    ["본문만 있다", "---\nkind: GUIDE\n", "---\nkind: GUIDE\n---\n본문"],
)
def test_a_document_without_a_complete_front_matter_is_rejected(text) -> None:
    with pytest.raises(CorpusFormatError):
        parse_document(text)


def test_only_approved_rag_documents_are_loaded(tmp_path) -> None:
    (tmp_path / "guides").mkdir()
    (tmp_path / "guides" / "ok.md").write_text(TEXT, encoding="utf-8")
    draft = TEXT.replace("documentKey: guide-x", "documentKey: guide-draft").replace(
        "reviewStatus: APPROVED", "reviewStatus: DRAFT"
    )
    held_out = TEXT.replace("documentKey: guide-x", "documentKey: guide-eval").replace(
        "dataSplit: RAG", "dataSplit: EVAL"
    )
    (tmp_path / "guides" / "draft.md").write_text(draft, encoding="utf-8")
    (tmp_path / "guides" / "eval.md").write_text(held_out, encoding="utf-8")

    assert [item.key for item in load_corpus(tmp_path)] == ["guide-x"]


def test_an_empty_corpus_directory_is_an_error(tmp_path) -> None:
    with pytest.raises(CorpusFormatError):
        load_corpus(tmp_path)


async def test_the_repository_corpus_loads_and_staff_runbooks_stay_out_of_employee_scope() -> None:
    documents = load_corpus()
    runbooks = [item for item in documents if item.kind == "RUNBOOK"]
    assert documents and runbooks
    assert all(item.access_scope == "SYSTEM_STAFF" for item in runbooks)
    by_code = {item.system_code: uid(800 + index) for index, item in enumerate(documents)}
    employee_query = KnowledgeQuery(
        text=" ".join(item.title for item in documents),
        allowed_scopes=frozenset({"EMPLOYEE"}),
        system_ids=frozenset(by_code.values()),
        system_ids_by_code=by_code,
    )

    hits = await CorpusKnowledgeSource(documents).search(employee_query)

    assert hits and all(hit.evidence.accessScope == "EMPLOYEE" for hit in hits)
