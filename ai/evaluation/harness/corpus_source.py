"""평가용 코퍼스 검색원: `docs/knowledge/corpus`의 문서를 Spring 검색과 비슷하게 찾는다.

판정 모드(문제별 행동 경로) 측정에서 검색이 실제처럼 "증상 문의에는 맞는 문서가 안 잡히거나
일부만 잡히는" 상황을 만들기 위한 하네스 전용 대역이다. 운영 코드가 아니며 `hops_ai`에서
불러오지 않는다. 고정 자료 대역(`FixtureKnowledgeSource`)과 달리 키워드 목록 없이 문서 본문을
직접 찾는다.

Spring과의 일치가 아니라 근사다. 맞춘 것: 검색어 토큰화(`KnowledgeQueryTokens`: 글자·숫자
단위 분리, 2자 미만·불용어 제거, 흔한 조사 제거, 최대 20개), 제목·본문 부분 문자열 일치,
제목 가중 점수, 필요 일치 수(토큰 3개 이상이면 2개), 접근 범위·시스템·종류 선필터. 맞추지 않은
것: DB 조회, 현재 권한 재확인, digest 재검증, 문서 버전 이력. 그래서 이 검색원으로 잰 결과를
실제 검색 품질이나 Spring 동작의 증거로 쓰지 않는다.
"""

import hashlib
import re
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

from hops_ai.contracts.roles import AuthorizedKnowledgeRef
from hops_ai.contracts.wire import KnowledgeEvidence
from hops_ai.services.retrieval.knowledge_source import KnowledgeHit, KnowledgeQuery

DEFAULT_CORPUS_DIR = Path(__file__).resolve().parents[3] / "docs" / "knowledge" / "corpus"

MAX_TOKENS = 20
TITLE_WEIGHT = 3
CONTENT_WEIGHT = 1
_NAMESPACE = "hops-ai-evaluation-corpus"
_SPLIT = re.compile(r"[^\w]+|_+")
# 긴 조사부터 확인한다(Spring과 같은 목록·순서).
_PARTICLES = (
    "에서는",
    "으로는",
    "에서",
    "으로",
    "에게",
    "까지",
    "부터",
    "이나",
    "하고",
    "라도",
    "이며",
    "이고",
    "에는",
    "에도",
    "은",
    "는",
    "이",
    "가",
    "을",
    "를",
    "의",
    "에",
    "로",
    "와",
    "과",
    "도",
    "만",
)
_STOPWORDS = frozenset(
    (
        "어떻게",
        "어떤",
        "무엇",
        "어디",
        "하나요",
        "합니다",
        "있나요",
        "있는",
        "있습니다",
        "하는",
        "되나요",
        "해야",
        "해요",
        "주세요",
        "싶어요",
        "싶습니다",
        "대해",
        "대한",
        "관련",
        "그리고",
        "그런데",
        "또는",
        "제가",
        "저는",
        "저희",
        "알려",
        "알고",
    )
)


class CorpusFormatError(ValueError):
    pass


@dataclass(frozen=True)
class CorpusDocument:
    key: str
    kind: str
    system_code: str
    access_scope: str
    title: str
    applicability: str
    version: int
    body: str


def query_tokens(query: str) -> list[str]:
    """검색어를 토큰으로 바꾼다. Spring `KnowledgeQueryTokens.of`와 같은 규칙의 근사."""
    tokens: dict[str, None] = {}
    for raw in _SPLIT.split(query.lower()):
        if len(raw) < 2 or raw in _STOPWORDS:
            continue
        token = _stem(raw)
        if len(token) >= 2 and token not in _STOPWORDS:
            tokens[token] = None
        if len(tokens) >= MAX_TOKENS:
            break
    return list(tokens)


def _stem(token: str) -> str:
    for particle in _PARTICLES:
        if token.endswith(particle) and len(token) - len(particle) >= 2:
            return token[: -len(particle)]
    return token


def parse_document(text: str, name: str = "문서") -> CorpusDocument:
    """front matter(최상위 `키: 값` 줄)와 본문으로 나눈다. 필수 키가 없으면 오류다."""
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        raise CorpusFormatError(f"{name}: front matter가 없다")
    try:
        end = next(i for i in range(1, len(lines)) if lines[i].strip() == "---")
    except StopIteration:
        raise CorpusFormatError(f"{name}: front matter가 닫히지 않았다") from None
    meta: dict[str, str | None] = {}
    for line in lines[1:end]:
        if not line or line[0] in " \t#" or ":" not in line:
            continue  # 중첩 항목(source 등)은 쓰지 않는다
        key, _, value = line.partition(":")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        meta[key.strip()] = None if value in ("", "null") else value
    required = ("documentKey", "kind", "title", "systemCode", "accessScope", "version")
    missing = [key for key in required if not meta.get(key)]
    if missing:
        raise CorpusFormatError(f"{name}: 필수 키가 없다 {missing}")
    return CorpusDocument(
        key=str(meta["documentKey"]),
        kind=str(meta["kind"]),
        system_code=str(meta["systemCode"]),
        access_scope=str(meta["accessScope"]),
        title=str(meta["title"]),
        applicability=str(meta.get("applicability") or meta["title"]),
        version=int(str(meta["version"])),
        body="\n".join(lines[end + 1 :]).strip(),
    )


def load_corpus(directory: Path = DEFAULT_CORPUS_DIR) -> tuple[CorpusDocument, ...]:
    """검수 승인(APPROVED)된 RAG 문서만 읽는다. 문서 키 순으로 항상 같은 순서다."""
    documents = []
    for path in sorted(directory.glob("*/*.md")):
        text = path.read_text(encoding="utf-8")
        document = parse_document(text, path.name)
        meta_block = text.split("---", 2)[1]
        if "reviewStatus: APPROVED" in meta_block and "dataSplit: RAG" in meta_block:
            documents.append(document)
    if not documents:
        raise CorpusFormatError(f"코퍼스 문서가 없다: {directory}")
    return tuple(sorted(documents, key=lambda document: document.key))


def _score(tokens: Sequence[str], required: int, document: CorpusDocument) -> float:
    title, content = document.title.lower(), document.body.lower()
    raw = matched = 0
    for token in tokens:
        in_title, in_content = token in title, token in content
        matched += in_title or in_content
        raw += (TITLE_WEIGHT if in_title else 0) + (CONTENT_WEIGHT if in_content else 0)
    if matched < required:
        return 0.0
    return min(1.0, raw / (len(tokens) * (TITLE_WEIGHT + CONTENT_WEIGHT)))


class CorpusKnowledgeSource:
    def __init__(self, documents: Sequence[CorpusDocument] | None = None) -> None:
        self._documents = tuple(documents) if documents is not None else load_corpus()

    async def search(self, query: KnowledgeQuery) -> Sequence[KnowledgeHit]:
        tokens = query_tokens(query.text)
        if not tokens:
            return []
        required = 2 if len(tokens) >= 3 else 1
        ranked: list[tuple[float, str, KnowledgeHit]] = []
        for document in self._documents:
            system_id = query.system_ids_by_code.get(document.system_code)
            # 공개 범위·시스템·종류를 먼저 거른다. 범위 밖 자료는 점수 계산도 하지 않는다.
            if (
                document.access_scope not in query.allowed_scopes
                or system_id is None
                or system_id not in query.system_ids
                or (query.kinds is not None and document.kind not in query.kinds)
            ):
                continue
            score = _score(tokens, required, document)
            if score > 0:
                ranked.append((score, document.key, self._hit(document, system_id, score)))
        ranked.sort(key=lambda item: (-item[0], item[1]))
        return [hit for _, _, hit in ranked[: query.top_k]]

    @staticmethod
    def _hit(document: CorpusDocument, system_id: str, score: float) -> KnowledgeHit:
        document_id = str(uuid5(NAMESPACE_URL, f"{_NAMESPACE}:document:{document.key}"))
        evidence_id = str(
            uuid5(NAMESPACE_URL, f"{_NAMESPACE}:evidence:{document.key}:{document.version}")
        )
        evidence = KnowledgeEvidence(
            id=evidence_id,
            documentId=document_id,
            documentVersion=document.version,
            kind=document.kind,  # type: ignore[arg-type]
            title=document.title,
            excerpt=document.body,
            systemId=system_id,
            appliesToSystemVersion=None,
            applicability=document.applicability,
            accessScope=document.access_scope,  # type: ignore[arg-type]
            origin="SYNTHETIC",
        )
        reference = AuthorizedKnowledgeRef(
            documentId=document_id,
            documentVersion=document.version,
            kind=document.kind,  # type: ignore[arg-type]
            accessScope=document.access_scope,  # type: ignore[arg-type]
            contentDigest=hashlib.sha256(document.body.encode("utf-8")).hexdigest(),
        )
        return KnowledgeHit(
            evidence=evidence, reference=reference, content=document.body, score=score
        )
