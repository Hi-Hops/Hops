from dataclasses import replace

import pytest

from hops_ai.fakes.retrieval import FakeRetriever, sample_chunks
from hops_ai.harness.errors import RetrievalUnavailable, describe_failure
from hops_ai.services.retrieval.base import RetrievalFilter, RetrievalQuery, Retriever


def ids(chunks) -> list[str]:
    return [chunk.chunk_id for chunk in chunks]


def test_answer_mode_allows_only_quotable_employee_documents() -> None:
    retrieval_filter = RetrievalFilter.for_mode("ANSWER")

    assert retrieval_filter.access_scopes == {"EMPLOYEE"}
    assert retrieval_filter.exposure_policies == {"QUOTABLE"}


def test_diagnosis_mode_adds_staff_and_internal_documents_but_never_dangerous_ones() -> None:
    retrieval_filter = RetrievalFilter.for_mode("SUPPORT_DIAGNOSIS")

    assert retrieval_filter.access_scopes == {"EMPLOYEE", "STAFF"}
    assert retrieval_filter.exposure_policies == {"QUOTABLE", "INTERNAL_ONLY"}
    assert "NEVER_EXPOSE" not in retrieval_filter.exposure_policies


async def test_answer_mode_never_returns_staff_or_dangerous_documents() -> None:
    retriever = FakeRetriever()

    found = await retriever.search(RetrievalQuery("VPN", RetrievalFilter.for_mode("ANSWER")))

    assert ids(found) == ["chunk-fake-guide-vpn-1"]


async def test_diagnosis_mode_adds_the_staff_runbook_but_not_the_dangerous_procedure() -> None:
    retriever = FakeRetriever()

    found = await retriever.search(
        RetrievalQuery("VPN", RetrievalFilter.for_mode("SUPPORT_DIAGNOSIS"))
    )

    assert set(ids(found)) == {"chunk-fake-guide-vpn-1", "chunk-fake-runbook-vpn-1"}
    assert "chunk-fake-risky-1" not in ids(found)


async def test_filter_is_applied_before_ranking_not_after() -> None:
    # 담당자 문서가 점수는 더 높다. 순위를 매긴 뒤에 거르면 top_k=1일 때 결과가 비게 된다.
    retriever = FakeRetriever()

    found = await retriever.search(
        RetrievalQuery("VPN 게이트웨이 인증 로그", RetrievalFilter.for_mode("ANSWER"), top_k=1)
    )

    assert ids(found) == ["chunk-fake-guide-vpn-1"]


async def test_results_are_ordered_by_score_and_limited_by_top_k() -> None:
    retriever = FakeRetriever()
    query_filter = RetrievalFilter.for_mode("SUPPORT_DIAGNOSIS")

    ranked = await retriever.search(RetrievalQuery("VPN 게이트웨이 인증 로그", query_filter))
    limited = await retriever.search(
        RetrievalQuery("VPN 게이트웨이 인증 로그", query_filter, top_k=1)
    )

    assert ids(ranked)[0] == "chunk-fake-runbook-vpn-1"
    assert [chunk.score for chunk in ranked] == sorted(
        (chunk.score for chunk in ranked), reverse=True
    )
    assert ids(limited) == ["chunk-fake-runbook-vpn-1"]


async def test_no_match_is_an_empty_list_not_an_error() -> None:
    retriever = FakeRetriever()

    found = await retriever.search(
        RetrievalQuery("프린터 토너", RetrievalFilter.for_mode("ANSWER"))
    )

    assert found == []


async def test_outage_is_reported_as_an_error_not_as_no_result() -> None:
    retriever = FakeRetriever(fail_with=RetrievalUnavailable())

    with pytest.raises(RetrievalUnavailable):
        await retriever.search(RetrievalQuery("VPN", RetrievalFilter.for_mode("ANSWER")))


async def test_retriever_does_not_mutate_the_stored_documents() -> None:
    chunks = sample_chunks()
    retriever = FakeRetriever(chunks)

    await retriever.search(RetrievalQuery("VPN", RetrievalFilter.for_mode("ANSWER")))

    assert chunks == sample_chunks()


async def test_custom_documents_can_be_supplied() -> None:
    custom = replace(sample_chunks()[0], chunk_id="chunk-custom-1", title="계정 잠금 안내")
    retriever = FakeRetriever([custom])

    found = await retriever.search(RetrievalQuery("계정", RetrievalFilter.for_mode("ANSWER")))

    assert ids(found) == ["chunk-custom-1"]


def test_fake_retriever_satisfies_the_retriever_interface() -> None:
    assert isinstance(FakeRetriever(), Retriever)


def test_retrieval_outage_is_retryable_but_not_a_model_retry_case() -> None:
    from hops_ai.harness.errors import TransientError

    failure = describe_failure(RetrievalUnavailable())

    assert (failure.status, failure.error.code, failure.error.retryable) == (
        "ERROR",
        "RETRIEVAL_UNAVAILABLE",
        True,
    )
    assert not issubclass(RetrievalUnavailable, TransientError)
