"""저장소별 반영. Neo4j는 가짜 HTTP 전송으로 보낸 Cypher와 확인 흐름을 검사한다."""

import json

import httpx
import pytest

from hops_ai.contracts.indexing import CaseIndexSource, IndexApplyRequest
from hops_ai.services.indexing.clients import IndexFailure
from hops_ai.services.indexing.stores import GraphStore, embedding_input
from tests.unit.test_index_jobs import CASE_ID, DOC_VERSION, approved_case, request


def source() -> CaseIndexSource:
    return CaseIndexSource(
        contractVersion="hops-system-1",
        caseId=CASE_ID,
        caseVersion=1,
        documentVersionId=DOC_VERSION,
        title="제목",
        applicability="조건",
        content="본문",
        contentDigest="a" * 64,
    )


def graph_request(links: list[dict]) -> IndexApplyRequest:
    return IndexApplyRequest.model_validate(
        request("NEO4J", case=approved_case() | {"entityLinks": links})
    )


class Neo4j:
    """조회 문장의 ids 중 known에 있는 것만 있다고 답하고, 확인 문장에는 verify를 돌려준다."""

    def __init__(self, known: set[str], verify: list[int] | None = None) -> None:
        self.known = known
        self.verify = verify
        self.batches: list[list[dict]] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        statements = json.loads(request.content)["statements"]
        self.batches.append(statements)
        results = []
        for item in statements:
            text = item["statement"]
            if "RETURN t.id" in text:
                rows = [{"row": [i]} for i in item["parameters"]["ids"] if i in self.known]
            elif "RETURN count(DISTINCT c)" in text:
                rows = [{"row": self.verify}] if self.verify is not None else []
            else:
                rows = []
            results.append({"columns": [], "data": rows})
        return httpx.Response(200, json={"results": results, "errors": []})

    def store(self) -> GraphStore:
        return GraphStore(
            "http://neo4j:7474", "neo4j", "secret", transport=httpx.MockTransport(self.handler)
        )


LINKS = [
    {"relation": "ADDRESSES", "targetId": "s-1", "evidenceIds": []},
    {"relation": "USES_CHECK", "targetId": "k-1", "evidenceIds": []},
    {"relation": "USES_CHECK", "targetId": "k-1", "evidenceIds": []},
]


async def test_the_case_node_and_its_links_are_written_with_the_document_version_key() -> None:
    neo4j = Neo4j({"s-1", "k-1"}, verify=[1, 2])

    await neo4j.store().apply(graph_request(LINKS), source())

    lookup, write, verify = neo4j.batches
    assert len(lookup) == 3
    node = write[0]
    assert "MERGE (c:HopsP0:ApprovedCase {id: $caseId})" in node["statement"]
    assert node["parameters"] | {} == {
        "caseId": CASE_ID,
        "version": 1,
        "systemId": approved_case()["systemId"],
        # 계약의 SYSTEM_STAFF는 업무 DB 값 STAFF로 저장한다(Spring 관계 조회가 읽는 값)
        "level": "STAFF",
        "documentVersionId": DOC_VERSION,
    }
    # 이전 버전의 연결을 지우고 다시 만든다. 같은 대상은 한 번만 연결한다.
    assert "DELETE old" in node["statement"]
    relations = {
        statement["statement"].split("MERGE (c)-[r:")[1].split("]")[0]: statement["parameters"][
            "ids"
        ]
        for statement in write[1:]
    }
    assert relations == {"ADDRESSES": ["s-1"], "USES_CHECK": ["k-1"]}
    assert "RETURN count(DISTINCT c), count(r)" in verify[0]["statement"]


async def test_a_link_to_a_node_missing_from_the_graph_fails_without_writing() -> None:
    neo4j = Neo4j({"s-1"}, verify=[1, 2])

    with pytest.raises(IndexFailure) as failure:
        await neo4j.store().apply(graph_request(LINKS), source())

    assert failure.value.retryable is False
    assert len(neo4j.batches) == 1  # 조회만 하고 쓰지 않았다


async def test_a_write_that_cannot_be_read_back_is_not_success() -> None:
    neo4j = Neo4j({"s-1", "k-1"}, verify=[1, 1])

    with pytest.raises(IndexFailure) as failure:
        await neo4j.store().apply(graph_request(LINKS), source())

    assert failure.value.retryable is True


async def test_neo4j_errors_are_retryable_index_failures() -> None:
    def broken(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, json={"results": [], "errors": [{"code": "Neo.TransientError.General"}]}
        )

    store = GraphStore("http://neo4j:7474", "neo4j", "s", transport=httpx.MockTransport(broken))

    with pytest.raises(IndexFailure) as failure:
        await store.apply(graph_request(LINKS), source())

    assert (failure.value.code, failure.value.retryable) == ("INDEX_UNAVAILABLE", True)


def test_the_embedding_input_matches_the_demo_loader() -> None:
    # scripts/knowledge/load_search_demo.py와 같은 형식이어야
    # 시드와 새 사례가 같은 기준으로 검색된다
    assert embedding_input(source()) == "제목\n적용 조건: 조건\n\n본문"
