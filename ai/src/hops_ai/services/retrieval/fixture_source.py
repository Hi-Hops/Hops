"""개발용 고정 자료를 검색하는 KnowledgeSource. 권한·digest 확인을 AI 안에서 흉내 낸다.

문서 ID·근거 ID는 문서 키에서 결정적으로 만든다. 시스템은 UUID를 고정하지 않고 요청이
알려 준 시스템 코드로 찾는다. 본문 digest는 UTF-8 원문 바이트의 SHA-256이다.
순위는 일치한 구절 수로 정하는 단순 규칙이며 의미 검색이 아니다.
"""

import hashlib
from collections.abc import Sequence
from uuid import NAMESPACE_URL, uuid5

from hops_ai.contracts.roles import AuthorizedKnowledgeRef
from hops_ai.contracts.wire import KnowledgeEvidence
from hops_ai.services.retrieval.fixture_documents import FIXTURE_DOCUMENTS, FixtureDocument
from hops_ai.services.retrieval.knowledge_source import KnowledgeHit, KnowledgeQuery

_NAMESPACE = "hops-ai-fixture"


def _normalize(text: str) -> str:
    return " ".join(text.split())


def _document_id(document: FixtureDocument) -> str:
    return str(uuid5(NAMESPACE_URL, f"{_NAMESPACE}:document:{document.key}"))


def _evidence_id(document: FixtureDocument) -> str:
    return str(uuid5(NAMESPACE_URL, f"{_NAMESPACE}:evidence:{document.key}:{document.version}"))


def _matched_keywords(document: FixtureDocument, text: str) -> int:
    return sum(1 for keyword in document.keywords if keyword in text)


class FixtureKnowledgeSource:
    def __init__(self, documents: Sequence[FixtureDocument] = FIXTURE_DOCUMENTS) -> None:
        self._documents = tuple(documents)

    async def search(self, query: KnowledgeQuery) -> Sequence[KnowledgeHit]:
        text = _normalize(query.text)
        ranked: list[tuple[int, str, KnowledgeHit]] = []
        for document in self._documents:
            system_id = query.system_ids_by_code.get(document.system_code)
            # 공개 범위와 시스템 접근 범위를 먼저 확인한다. 범위 밖 자료는 점수 계산도 하지 않는다.
            if (
                document.access_scope not in query.allowed_scopes
                or system_id is None
                or system_id not in query.system_ids
                or (query.kinds is not None and document.kind not in query.kinds)
            ):
                continue
            matched = _matched_keywords(document, text)
            if matched == 0:
                continue
            ranked.append((matched, document.key, self._hit(document, system_id, matched)))
        # 일치 수가 많은 순, 같으면 문서 키 순으로 항상 같은 결과를 만든다.
        ranked.sort(key=lambda item: (-item[0], item[1]))
        return [hit for _, _, hit in ranked[: query.top_k]]

    @staticmethod
    def _hit(document: FixtureDocument, system_id: str, matched: int) -> KnowledgeHit:
        digest = hashlib.sha256(document.body.encode("utf-8")).hexdigest()
        document_id = _document_id(document)
        evidence = KnowledgeEvidence(
            id=_evidence_id(document),
            documentId=document_id,
            documentVersion=document.version,
            kind=document.kind,
            title=document.title,
            excerpt=document.body,
            systemId=system_id,
            appliesToSystemVersion=None,
            applicability=document.applicability,
            accessScope=document.access_scope,
            origin="SYNTHETIC",
        )
        reference = AuthorizedKnowledgeRef(
            documentId=document_id,
            documentVersion=document.version,
            kind=document.kind,
            accessScope=document.access_scope,
            contentDigest=digest,
        )
        return KnowledgeHit(
            evidence=evidence,
            reference=reference,
            content=document.body,
            score=matched / len(document.keywords),
        )
