"""Neo4j의 승인 CASE 버전 반영. 검색 공개 결정은 호출자가 별도로 수행한다."""

from typing import Any
from uuid import UUID

import httpx

from hops_ai.services.indexing.approved_case import verified_case_body

_UPSERT = """
MATCH (system:System {id: $systemId})
CALL (system) {
    OPTIONAL MATCH (symptom:Symptom) WHERE symptom.id IN $symptoms
    RETURN collect(symptom) AS symptoms
}
CALL (system) {
    OPTIONAL MATCH (item:Check) WHERE item.id IN $checks
    RETURN collect(item) AS checks
}
WITH system, symptoms, checks
WHERE size(symptoms) = size($symptoms) AND size(checks) = size($checks)
MERGE (entry:Case {caseId: $caseId, caseVersion: $caseVersion})
ON CREATE SET entry.contentDigest = $contentDigest, entry.systemId = $systemId,
              entry.accessScope = $accessScope
WITH system, symptoms, checks, entry
WHERE entry.contentDigest = $contentDigest
MERGE (entry)-[:APPLIES_TO {sourceCaseVersion: $caseVersion}]->(system)
FOREACH (symptom IN symptoms |
    MERGE (entry)-[:ADDRESSES {sourceCaseVersion: $caseVersion}]->(symptom))
FOREACH (item IN checks |
    MERGE (entry)-[:USES_CHECK {sourceCaseVersion: $caseVersion}]->(item))
RETURN entry.caseId, entry.caseVersion
"""


class Neo4jCaseIndex:
    """고정 Cypher로 승인 버전과 카탈로그의 기존 노드만 연결한다."""

    def __init__(self, client: httpx.AsyncClient) -> None:
        self._client = client

    async def apply(self, request: dict[str, Any]) -> None:
        approved = verified_case_body(request, "NEO4J")
        case_id = approved["caseId"]
        version = approved["caseVersion"]
        body = approved["approvedCase"]
        system_id = str(UUID(body["systemId"]))
        digest = request["contentDigest"]

        targets: dict[str, set[str]] = {"ADDRESSES": set(), "USES_CHECK": set()}
        for link in body["entityLinks"]:
            relation, target = link["relation"], link["targetId"]
            if relation == "APPLIES_TO" and target == system_id:
                continue
            if relation not in targets or not isinstance(target, str) or not target.strip():
                raise ValueError("허용되지 않은 사례 관계입니다")
            targets[relation].add(target)

        response = await self._client.post(
            "/db/neo4j/query/v2",
            json={
                "statement": _UPSERT,
                "parameters": {
                    "caseId": case_id,
                    "caseVersion": version,
                    "contentDigest": digest,
                    "systemId": system_id,
                    "accessScope": body["accessScope"],
                    "symptoms": sorted(targets["ADDRESSES"]),
                    "checks": sorted(targets["USES_CHECK"]),
                },
            },
        )
        response.raise_for_status()
        result = response.json()
        if result.get("errors") or result.get("data", {}).get("values") != [[case_id, version]]:
            raise ValueError("그래프 반영 실패, 대상 미등록 또는 승인 버전 충돌")
