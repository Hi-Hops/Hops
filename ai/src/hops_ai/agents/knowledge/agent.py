"""Knowledge 역할: 허용된 지식을 검색해 근거와 직원 안내를 제안한다.

이 구현은 모델을 호출하지 않는다. 질의는 그 문제 자신의 정보로 규칙에 따라 만든다. 문제 하나가
문의 전체의 주제일 때만 요약과 사용자 원문을 함께 쓰고, 한 문의에 다른 문제가 함께 있으면 그
문제의 단어로 검색이 오염되므로 원문과 모델이 쓴 요약은 쓰지 않고 검증된 상세 값만 쓴다.
검색 범위(공개 범위·시스템)는 서버가 확정한 접근 문맥에서만 가져온다. 안내문은 근거 문서의
승인된 본문을 그대로 쓰고 출처 제목만 덧붙인다. 모델이 문구를 새로 쓰지 않으므로 안내가
근거와 어긋나거나 근거 밖 내용을 담을 수 없다.

- 검색 0건(EMPTY)에는 안내를 만들지 않고, 검색 오류(ERROR)를 0건으로 바꾸지 않는다.
- 안내는 직원 공개 GUIDE 근거가 있을 때만 제안한다. 담당자 전용 자료는 근거에 포함될 수
  있어도 안내문에 쓰지 않는다.
- 이 역할은 현재 상담 실행에서 사용법(KNOWLEDGE_QA) 문제 하나의 안내만 다룬다. 원인 후보나
  관계 조회(Neo4j)는 포함하지 않는다.

반환하는 결과는 제안이다. 권한 확인과 digest 확인은 검색 자료가 마쳤다고 알린 값이며,
Supervisor 병합 단계가 현재 접근 범위와 다시 대조한다.
"""

from uuid import uuid4

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import KnowledgeResult, SearchError, SearchResult
from hops_ai.contracts.state import RoleRequest, RoleResponse, SupervisorState
from hops_ai.contracts.wire import GuideProposal, ProblemProposal
from hops_ai.harness.errors import OutputRejected, RetrievalUnavailable
from hops_ai.rules.progress import get_problem_message_ids, get_problem_progress, searched_queries
from hops_ai.services.retrieval.knowledge_source import (
    MAX_TOP_K,
    KnowledgeHit,
    KnowledgeQuery,
    KnowledgeSource,
)
from hops_ai.services.shared import SharedServices

# 질의에 쓰는 최근 사용자 메시지 수. 오래된 대화가 최신 질문을 가리지 않게 한다.
MAX_QUERY_MESSAGES = 5


class SearchKnowledgeAgent:
    role = AgentRole.KNOWLEDGE

    def __init__(self, source: KnowledgeSource) -> None:
        self._source = source

    def available_for(self, state: SupervisorState) -> bool:
        """지금 호출해도 성립하는지. 안내할 사용법 문제가 없으면 검색할 대상이 없다."""
        return (
            state["input"].kind == RunKind.INQUIRY_ANALYSIS and guidable_problem(state) is not None
        )

    async def run(self, call: RoleRequest, services: SharedServices) -> RoleResponse:

        services.runtime.ensure_active()
        state = call.state
        if state["input"].kind != RunKind.INQUIRY_ANALYSIS:
            raise OutputRejected("이 Knowledge 구현은 상담 실행의 안내만 다룬다")
        target = _guide_target(state)
        query = _build_query(state, target)
        try:
            hits = list(await self._source.search(query))
        except RetrievalUnavailable:
            # 검색 오류를 0건으로 바꾸지 않는다. 원인 상세는 사용자·모델 입력에 싣지 않는다.
            return RoleResponse(
                result=_error_result(call.invocation_id, target.problemRef, query.digest())
            )
        services.runtime.ensure_active()

        evidence = tuple(hit.evidence for hit in hits)
        result = KnowledgeResult(
            invocationId=call.invocation_id,
            search=(
                SearchResult(state="FOUND", evidence=list(evidence), error=None)
                if hits
                else SearchResult(state="EMPTY", evidence=[], error=None)
            ),
            guide=_guide(target, hits),
            problemRef=target.problemRef,
            queryDigest=query.digest(),
        )
        return RoleResponse(
            result=result,
            authorized_refs=tuple(hit.reference for hit in hits),
            verified_evidence=evidence,
        )


def guidable_problem(state: SupervisorState) -> ProblemProposal | None:
    """안내를 만들 수 있는 문제 하나를 고른다. 없으면 None이다.

    이미 안내가 있거나, 처리 결과가 준비되지 않은 사용법 문제 중 보고 시스템이 정해졌고
    아직 검색하지 않은 것이 없으면 검색할 대상이 없다. 같은 문제를 같은 질의로 다시 검색해도
    같은 결과이므로, 이번 실행에서 같은 질의로 이미 검색한 문제는 고르지 않는다. 질의가 바뀌었다면
    (확인한 사실이 더해진 뒤 등) 다시 검색할 수 있다. 사용자의 새 메시지는 새 실행이다.
    Supervisor가 이 역할을 고를 수 있는지 판단하는 데도 쓴다.
    """

    if state["guide"] is not None:
        return None
    searched = searched_queries(state)
    for item in get_problem_progress(state):
        if item["outcome"] is not None:
            continue
        problem = state["problems"][item["problemRef"]]
        if problem.processingType != "KNOWLEDGE_QA" or not problem.reportedSystemCandidateId:
            continue
        digests = searched.get(problem.problemRef, set())
        # 지문이 없는 결과는 어떤 질의였는지 알 수 없어 다시 검색하지 않는다.
        if None in digests or _build_query(state, problem).digest() in digests:
            continue
        return problem
    return None


def _guide_target(state: SupervisorState) -> ProblemProposal:
    """안내할 문제를 고른다. 상태에는 안내가 하나뿐이므로 이미 있으면 거부한다."""
    if state["guide"] is not None:
        raise OutputRejected("이미 안내가 준비되어 더 만들 수 없다")
    target = guidable_problem(state)
    if target is None:
        # 빈 결과로 성공 처리하면 Supervisor가 같은 호출을 반복한다. 대상이 없다고 알린다.
        raise OutputRejected("안내할 수 있는 사용법 문제가 없다")
    return target


def _build_query(state: SupervisorState, target: ProblemProposal) -> KnowledgeQuery:
    request = state["input"]
    access = request.accessContext
    # 그 문제 자신의 정보: 확인한 상세 값(사용자 인용으로 검증됨)과 요약. 새 사실이 더해지면
    # 질의도 달라진다. 새 문제가 둘 이상이면 모델이 쓴 요약에도 다른 문제의 내용이 섞일 수 있어
    # (실제 모델이 두 문제를 한 요약에 적은 사례) 검증된 상세 값만 쓰고, 상세 값이 하나도 없을
    # 때에만 요약을 대신 쓴다. 문제가 하나이면 섞일 문제가 없어 요약과 원문을 함께 쓴다.
    details = _detail_values(target)
    if _messages_are_shared(state, target):
        parts = details or [target.summary]
    else:
        parts = [target.summary, *details, *_recent_user_messages(state, target)]

    # 공개 범위는 서버가 확정한 값 그대로 쓴다. 직원 상담에 담당자 범위가 섞인 입력은
    # 실행 입력 검증이 이미 거부한다.
    # 문제의 시스템으로 좁힌다. 접근 범위에 없는 시스템이면 아무것도 찾지 못한다.
    system_ids = {target.reportedSystemCandidateId} & set(access.systemIds)
    return KnowledgeQuery(
        text="\n".join(parts),
        allowed_scopes=frozenset(access.allowedDocumentScopes),
        system_ids=frozenset(system_ids),
        system_ids_by_code={system.code: system.id for system in request.systems},
        top_k=MAX_TOP_K,
    )


def _detail_values(problem: ProblemProposal) -> list[str]:
    """확인한 상세 값(문자열)만 모델 필드 순서대로. 값이 없는 항목은 건너뛴다."""
    return [
        value.strip()
        for name, value in problem.details
        if name != "acceptanceCriteria" and isinstance(value, str) and value.strip()
    ]


def _messages_are_shared(state: SupervisorState, target: ProblemProposal) -> bool:
    """새 문제가 다른 문제와 같은 원문을 나눠 쓰는지. 그러면 원문 전체는 이 문제의 질의가 아니다.

    서버가 연결한 기존 문제의 메시지는 그 문제 것이다. 새 문제가 둘 이상이면 어느 문장이 어느
    문제의 것인지 알 수 없어 다른 문제의 단어가 질의에 섞인다.
    """
    return target.problemRef.startswith("new:") and len(state["problems"]) > 1


def _recent_user_messages(state: SupervisorState, target: ProblemProposal) -> list[str]:

    request = state["input"]
    message_ids = get_problem_message_ids(state, target.problemRef)
    user_messages = sorted(
        (
            message
            for message in request.messages
            if message.sender == "USER" and message.id in message_ids
        ),
        key=lambda message: message.sequenceNo,
    )
    return [message.content for message in user_messages[-MAX_QUERY_MESSAGES:]]


def _guide(target: ProblemProposal, hits: list[KnowledgeHit]) -> GuideProposal | None:
    """직원 공개 가이드 근거가 있을 때만 그 승인 본문으로 안내를 구성한다."""
    for hit in hits:
        if hit.evidence.kind == "GUIDE" and hit.evidence.accessScope == "EMPLOYEE":
            return GuideProposal(
                messageText=f"{hit.content}\n\n참고 자료: {hit.evidence.title}",
                evidenceIds=[hit.evidence.id],
                problemRef=target.problemRef,
            )
    return None


def _error_result(invocation_id: str, problem_ref: str, query_digest: str) -> KnowledgeResult:
    return KnowledgeResult(
        invocationId=invocation_id,
        search=SearchResult(
            state="ERROR",
            evidence=[],
            error=SearchError(
                code="SEARCH_UNAVAILABLE",
                message="검색 시스템에 일시적으로 연결하지 못했다",
                requestId=str(uuid4()),
                retryable=True,
                fieldErrors=[],
            ),
        ),
        guide=None,
        problemRef=problem_ref,
        queryDigest=query_digest,
    )
