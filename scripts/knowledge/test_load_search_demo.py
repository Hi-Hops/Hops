"""시연 그래프 재적재가 자동 색인한 승인 사례를 지우지 않는지 확인한다(Neo4j는 가짜 HTTP 전송).

    uv run --with pytest --with pyyaml --with httpx --with psycopg pytest scripts/knowledge
"""

from __future__ import annotations

import json

import httpx
import load_search_demo as loader
import pytest

SEED_CASE = "11111111-1111-4111-8111-111111111111"
AUTO_CASE = "22222222-2222-4222-8222-222222222222"


class Neo4j:
    """첫 요청(보존 조회)에는 자동 색인 사례 하나와 연결 둘을 돌려주고, 이후 요청은 기록한다."""

    def __init__(self, restored: int) -> None:
        self.restored = restored
        self.batches: list[list[dict]] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        statements = json.loads(request.content)["statements"]
        self.batches.append(statements)
        results = []
        for item in statements:
            text = item["statement"]
            if "WHERE NOT c.id IN $seed" in text:
                assert item["parameters"]["seed"] == [SEED_CASE]
                links = [
                    {"type": "ADDRESSES", "target": "s-1", "props": {"review_status": "APPROVED"}},
                    {"type": "USES_CHECK", "target": "k-1", "props": {"review_status": "APPROVED"}},
                ]
                rows = [{"row": [{"id": AUTO_CASE, "version_no": 1}, links]}]
            elif "RETURN count(r)" in text:
                count = min(len(item["parameters"]["rows"]), self.restored)
                self.restored -= count
                rows = [{"row": [count]}]
            else:
                rows = []
            results.append({"columns": [], "data": rows})
        return httpx.Response(200, json={"results": results, "errors": []})


@pytest.fixture(autouse=True)
def env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HOPS_SEARCH_NEO4J_URL", "http://neo4j:7474")
    monkeypatch.setenv("HOPS_NEO4J_PASSWORD", "secret")


def run(neo4j: Neo4j) -> tuple[int, int, int]:
    nodes = {"ApprovedCase": [{"id": SEED_CASE}], "Symptom": [{"id": "s-1"}]}
    with httpx.Client(transport=httpx.MockTransport(neo4j.handler)) as client:
        return loader.load_graph(client, nodes, {})


def test_auto_indexed_cases_are_restored_in_the_same_transaction() -> None:
    neo4j = Neo4j(restored=2)

    assert run(neo4j) == (1, 2, 2)

    constraint, lookup, replace = neo4j.batches
    texts = [item["statement"] for item in replace]
    # 지우기와 되살리기가 한 커밋 안에 있다(중간 실패 시 이전 그래프가 그대로 남는다)
    assert "DETACH DELETE" in texts[0]
    restore = next(item for item in replace if "CREATE (c:HopsP0:ApprovedCase)" in item["statement"])
    assert restore["parameters"]["cases"] == [{"id": AUTO_CASE, "version_no": 1}]
    assert sum("MERGE (c)-[r:ADDRESSES]" in text for text in texts) == 1
    assert sum("MERGE (c)-[r:USES_CHECK]" in text for text in texts) == 1


def test_links_whose_targets_vanished_are_reported() -> None:
    neo4j = Neo4j(restored=1)

    kept, restored, expected = run(neo4j)

    assert (kept, restored, expected) == (1, 1, 2)
