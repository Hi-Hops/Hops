"""승인 사례의 Neo4j 반영을 읽기 전용 Cypher로 관찰한다."""

import os
from typing import Any
from urllib.parse import quote

import httpx


class GraphUnavailable(Exception):
    """접속이나 응답을 확인할 수 없다. 원격 오류 본문은 기록하지 않는다."""


def read_case(case_id: str) -> dict[str, Any]:
    url = os.environ.get("HOPS_E2E_NEO4J_URL")
    username = os.environ.get("HOPS_E2E_NEO4J_USERNAME")
    password = os.environ.get("HOPS_E2E_NEO4J_PASSWORD")
    if not url or not username or not password:
        raise GraphUnavailable("Neo4j 읽기 전용 접속 설정이 없다")
    database = quote(os.environ.get("HOPS_E2E_NEO4J_DATABASE", "neo4j"), safe="")
    statement = """
        MATCH (c:HopsP0:ApprovedCase {id: $caseId})
        OPTIONAL MATCH (c)-[r:ADDRESSES|USES_CHECK|APPLIES_TO]->(t)
        RETURN properties(c), collect({kind: type(r), target: t.id, labels: labels(t),
               review: r.review_status, access: r.access_level,
               document: r.source_document_version_id})
    """
    try:
        with httpx.Client(timeout=10, trust_env=False) as client:
            response = client.post(
                f"{url.rstrip('/')}/db/{database}/tx/commit",
                auth=(username, password),
                json={"statements": [{"statement": statement, "parameters": {"caseId": case_id}}]},
            )
            response.raise_for_status()
            body = response.json()
        if body["errors"] or len(body["results"]) != 1:
            raise GraphUnavailable("Neo4j 조회가 실패했다")
        rows = body["results"][0]["data"]
        if not isinstance(rows, list):
            raise ValueError
        if not rows:
            return {"nodes": []}
        return {"nodes": [row["row"] for row in rows]}
    except (httpx.HTTPError, KeyError, TypeError, ValueError):
        raise GraphUnavailable("Neo4j 접속 또는 응답 확인에 실패했다") from None
