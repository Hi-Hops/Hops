"""Spring 내부 읽기 API로 승인된 지식·관계를 읽는 KnowledgeSource·RelationSource 구현(1차 SQL).

주소와 서비스 토큰은 설정에서만 읽고 모델의 인자를 받지 않는다.
접근 범위(공개 범위·시스템)는 요청이 아니라 Spring이 실행(runId)에서 확정하며,
이 클라이언트는 요청을 좁히기만 한다. 응답은 신뢰하지 않고 다시 검증한다:
실행·문제 일치, 근거 형식, 본문 digest(CONTENT 기준), 접근 범위·시스템 안의 근거인지.

- 검색 0건(NO_RESULT)은 빈 목록이다.
- 검색 실패(5xx·시간 초과·전송 오류·잘못된 응답)와 접근 범위를 넘은 요청(DENIED)은
  RetrievalUnavailable이며 빈 목록으로 바꾸지 않는다. 인증·권한 거부(401·403)는 PolicyDenied다.
- 호출은 실행 전체의 도구 호출 한도와 기한 안에서만 한다(RunLink.reserve_call). 재시도하지 않는다.
- 응답·오류 본문은 로그와 예외 메시지에 싣지 않는다.
"""

import hashlib
import logging
from collections.abc import Sequence
from typing import Literal

import httpx
from pydantic import BaseModel, ConfigDict, StrictBool, ValidationError

from hops_ai.contracts.roles import AuthorizedKnowledgeRef
from hops_ai.contracts.wire import AccessScope, KnowledgeEvidence, WireText
from hops_ai.harness.errors import (
    ComponentNotReady,
    InputRejected,
    PolicyDenied,
    RetrievalUnavailable,
)
from hops_ai.services.retrieval.knowledge_source import KnowledgeHit, KnowledgeQuery
from hops_ai.services.retrieval.relation_source import (
    RelationQuery,
    RelationResult,
    Symptom,
)
from hops_ai.settings import Settings

logger = logging.getLogger(__name__)

CONTRACT_VERSION = "hops-system-1"
MAX_QUERY_CHARS = 4000
MAX_REQUEST_SYSTEMS = 10
MAX_KNOWLEDGE_RESPONSE_BYTES = 262_144
MAX_RELATION_RESPONSE_BYTES = 131_072


class _Body(BaseModel):
    # Spring이 가산 필드를 더해도 받는다. 필요한 필드는 아래에서 검증한다.
    model_config = ConfigDict(extra="ignore", frozen=True)


class _Applied(_Body):
    accessScopes: list[AccessScope]
    internalJudgement: StrictBool


class _KnowledgeResult(_Body):
    evidence: KnowledgeEvidence
    documentKey: WireText
    content: WireText
    contentDigest: str
    digestBasis: Literal["CONTENT", "APPROVED_SNAPSHOT"]
    score: float


class _KnowledgeResponse(_Body):
    contractVersion: str
    runId: str
    problemRef: str
    outcome: Literal["FOUND", "NO_RESULT", "DENIED"]
    deniedReason: str | None = None
    applied: _Applied
    results: list[_KnowledgeResult]


class _RelationResponse(_Body):
    contractVersion: str
    runId: str
    problemRef: str
    outcome: Literal["FOUND", "NO_RESULT", "DENIED"]
    deniedReason: str | None = None
    applied: _Applied
    symptoms: list[Symptom]


class _SpringReader:
    """고정된 Spring origin에 서비스 토큰으로 POST하는 공통 부분."""

    def __init__(self, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None):
        if not settings.spring_base_url or not settings.spring_service_token:
            raise ComponentNotReady(
                "Spring 지식 검색 주소·인증",
                missing="HOPS_AI_SPRING_BASE_URL, SPRING_SERVICE_TOKEN",
            )
        self._settings = settings
        self._transport = transport

    async def _post(self, run, path: str, body: dict, limit_bytes: int) -> bytes:
        remaining = run.reserve_call()
        timeout = min(self._settings.knowledge_http_timeout_seconds, remaining)
        if timeout <= 0:
            raise RetrievalUnavailable("실행 기한이 남지 않았다")
        try:
            async with httpx.AsyncClient(
                base_url=self._settings.spring_base_url,
                transport=self._transport,
                follow_redirects=False,
                trust_env=False,
                timeout=timeout,
                headers={
                    "Authorization": "Bearer "
                    + self._settings.spring_service_token.get_secret_value()
                },
            ) as client:
                async with client.stream("POST", path, json=body) as response:
                    status = response.status_code
                    data = bytearray()
                    async for chunk in response.aiter_bytes():
                        data.extend(chunk)
                        if len(data) > limit_bytes:
                            raise RetrievalUnavailable("검색 응답이 한도를 넘었다")
        except (httpx.TransportError, TimeoutError):
            raise RetrievalUnavailable("검색 서비스에 연결하지 못했다") from None
        if status in (401, 403):
            raise PolicyDenied("Spring 검색 접근이 거부됐다")
        if status != 200:
            # 오류 본문은 신뢰할 수 없는 문자열이라 싣지 않는다.
            raise RetrievalUnavailable(f"검색 서비스가 HTTP {status}를 반환했다")
        return bytes(data)


def _check_envelope(response, run) -> None:
    if (
        response.contractVersion != CONTRACT_VERSION
        or response.runId != run.run_id
        or response.problemRef != run.problem_ref
    ):
        raise RetrievalUnavailable("다른 실행·문제의 검색 결과다")
    if response.outcome == "DENIED":
        # 접근 범위를 넘은 요청이다. 결과 없음으로 바꾸지 않는다.
        raise RetrievalUnavailable("검색 요청이 접근 범위를 넘었다")


class SpringKnowledgeSource(_SpringReader):
    """KnowledgeSource 구현. 질의에 실행 문맥(RunLink)이 있어야 한다."""

    async def search(self, query: KnowledgeQuery) -> Sequence[KnowledgeHit]:
        run = query.run
        if run is None:
            raise InputRejected("Spring 지식 검색에는 실행 문맥이 필요하다")
        body: dict = {
            "inquiryVersion": run.inquiry_version,
            "problemRef": run.problem_ref,
            "query": query.text[:MAX_QUERY_CHARS],
            "topK": query.top_k,
        }
        if query.system_ids:
            # 요청은 실행의 접근 시스템을 좁히기만 한다. 한도를 넘으면 앞쪽만 보내 더 좁게 찾는다.
            body["systemIds"] = sorted(query.system_ids)[:MAX_REQUEST_SYSTEMS]
        data = await self._post(
            run,
            f"/internal/v2/runs/{run.run_id}/knowledge-searches",
            body,
            MAX_KNOWLEDGE_RESPONSE_BYTES,
        )
        try:
            response = _KnowledgeResponse.model_validate_json(data)
        except ValidationError:
            raise RetrievalUnavailable("검색 응답 형식이 계약과 다르다") from None
        _check_envelope(response, run)
        if (response.outcome == "FOUND") != bool(response.results):
            raise RetrievalUnavailable("검색 상태와 결과 목록이 맞지 않는다")
        if len(response.results) > query.top_k:
            raise RetrievalUnavailable("요청한 건수보다 많은 결과다")
        hits = [_hit(item, query) for item in response.results]
        usable = [hit for hit in hits if hit.evidence.accessScope in query.allowed_scopes]
        if len(usable) != len(hits):
            # 내부 판단 읽기(옵션) 응답의 담당자 전용 자료다. 사용자 범위 밖이라 근거로 쓰지 않는다.
            logger.warning(
                "지식 검색 결과 %d건이 사용자 범위 밖이라 제외했다 internalJudgement=%s",
                len(hits) - len(usable),
                response.applied.internalJudgement,
            )
        if len({hit.evidence.id for hit in usable}) != len(usable):
            raise RetrievalUnavailable("검색 결과의 근거 ID가 중복됐다")
        return usable


def _hit(item: _KnowledgeResult, query: KnowledgeQuery) -> KnowledgeHit:
    evidence = item.evidence
    # 본문과 근거 발췌·digest를 모델 입력 전에 다시 대조한다.
    if evidence.excerpt not in item.content:
        raise RetrievalUnavailable("근거 발췌가 본문에 없다")
    if item.digestBasis == "CONTENT":
        if hashlib.sha256(item.content.encode("utf-8")).hexdigest() != item.contentDigest:
            raise RetrievalUnavailable("본문 digest가 일치하지 않는다")
    if evidence.systemId is not None and evidence.systemId not in query.system_ids:
        raise RetrievalUnavailable("요청한 시스템 밖의 근거다")
    try:
        reference = AuthorizedKnowledgeRef(
            documentId=evidence.documentId,
            documentVersion=evidence.documentVersion,
            kind=evidence.kind,
            accessScope=evidence.accessScope,
            contentDigest=item.contentDigest,
        )
    except ValidationError:
        raise RetrievalUnavailable("근거 참조 형식이 계약과 다르다") from None
    return KnowledgeHit(
        evidence=evidence, reference=reference, content=item.content, score=item.score
    )


class SpringRelationSource(_SpringReader):
    """RelationSource 구현. 직원 상담의 기본 프로필에서는 직원 공개 항목만 돌려받는다."""

    async def search(self, query: RelationQuery) -> RelationResult:
        run = query.run
        if run is None:
            raise InputRejected("Spring 관계 조회에는 실행 문맥이 필요하다")
        body: dict = {
            "inquiryVersion": run.inquiry_version,
            "problemRef": run.problem_ref,
            "limit": query.limit,
        }
        if query.keywords:
            body["keywords"] = [word[:50] for word in query.keywords][:20]
        if query.feature_ids:
            body["featureIds"] = sorted(query.feature_ids)[:10]
        if query.system_ids:
            body["systemIds"] = sorted(query.system_ids)[:MAX_REQUEST_SYSTEMS]
        data = await self._post(
            run,
            f"/internal/v2/runs/{run.run_id}/relation-searches",
            body,
            MAX_RELATION_RESPONSE_BYTES,
        )
        try:
            response = _RelationResponse.model_validate_json(data)
        except ValidationError:
            raise RetrievalUnavailable("관계 조회 응답 형식이 계약과 다르다") from None
        _check_envelope(response, run)
        if (response.outcome == "FOUND") != bool(response.symptoms):
            raise RetrievalUnavailable("조회 상태와 결과 목록이 맞지 않는다")
        if len(response.symptoms) > query.limit:
            raise RetrievalUnavailable("요청한 건수보다 많은 결과다")
        internal = response.applied.internalJudgement
        if "SYSTEM_STAFF" not in query.allowed_scopes and not internal:
            _require_employee_view(response.symptoms)
        return RelationResult(symptoms=tuple(response.symptoms), internal_judgement=internal)


def _require_employee_view(symptoms: Sequence[Symptom]) -> None:
    """직원 상담의 기본 프로필 응답에 담당자 전용 항목이 섞이면 계약 위반이다. 받지 않는다."""
    for symptom in symptoms:
        if symptom.accessScope != "EMPLOYEE" or symptom.causes is not None:
            raise RetrievalUnavailable("직원 범위 응답에 담당자 전용 항목이 있다")
        for check in symptom.checkItems:
            if not check.employeeDisclosable or check.label is not None:
                raise RetrievalUnavailable("직원 범위 응답에 담당자 전용 확인 항목이 있다")
            if any(question.text.strip() == "" for question in check.questions):
                raise RetrievalUnavailable("빈 질문 문구가 있다")
