"""Knowledge 역할: 허용된 지식을 검색해 근거와 직원 안내를 제안한다.

이 구현은 모델을 호출하지 않는다. 질의는 문제 요약과 사용자 원문으로 규칙에 따라 만들고,
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

from typing import TYPE_CHECKING
from uuid import uuid4

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import KnowledgeResult, SearchError, SearchResult
from hops_ai.contracts.wire import GuideProposal, ProblemProposal
from hops_ai.harness.errors import OutputRejected, RetrievalUnavailable
from hops_ai.services.retrieval.knowledge_source import (
    MAX_TOP_K,
    KnowledgeHit,
    KnowledgeQuery,
    KnowledgeSource,
)
from hops_ai.services.shared import SharedServices

if TYPE_CHECKING:
    # supervisor 패키지가 registry를 통해 이 모듈을 다시 불러오므로 타입 검사 때만 가져온다.
    from hops_ai.supervisor.execution import RoleRequest, RoleResponse
    from hops_ai.supervisor.state import SupervisorState

# 질의에 쓰는 최근 사용자 메시지 수. 오래된 대화가 최신 질문을 가리지 않게 한다.
MAX_QUERY_MESSAGES = 5


class SearchKnowledgeAgent:
    role = AgentRole.KNOWLEDGE

    def __init__(self, source: KnowledgeSource) -> None:
        self._source = source

    async def run(self, call: "RoleRequest", services: SharedServices) -> "RoleResponse":
        # supervisor 패키지는 이 모듈을 불러오는 registry를 거쳐 초기화되므로 호출 시점에 가져온다.
        from hops_ai.supervisor.execution import RoleResponse

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
            return RoleResponse(result=_error_result(call.invocation_id))
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
        )
        return RoleResponse(
            result=result,
            authorized_refs=tuple(hit.reference for hit in hits),
            verified_evidence=evidence,
        )


def _guide_target(state: "SupervisorState") -> ProblemProposal:
    """안내할 문제를 고른다. 상태에는 안내가 하나뿐이므로 이미 있으면 거부한다."""
    from hops_ai.supervisor.validation import get_problem_progress

    if state["guide"] is not None:
        raise OutputRejected("이미 안내가 준비되어 더 만들 수 없다")
    for item in get_problem_progress(state):
        if item["outcome"] is not None:
            continue
        problem = state["problems"][item["problemRef"]]
        if problem.processingType == "KNOWLEDGE_QA" and problem.reportedSystemCandidateId:
            return problem
    # 빈 결과로 성공 처리하면 Supervisor가 같은 호출을 반복한다. 대상이 없다고 알린다.
    raise OutputRejected("안내할 수 있는 사용법 문제가 없다")


def _build_query(state: "SupervisorState", target: ProblemProposal) -> KnowledgeQuery:
    from hops_ai.supervisor.validation import get_problem_message_ids

    request = state["input"]
    access = request.accessContext
    message_ids = get_problem_message_ids(state, target.problemRef)
    user_messages = sorted(
        (
            message
            for message in request.messages
            if message.sender == "USER" and message.id in message_ids
        ),
        key=lambda message: message.sequenceNo,
    )
    parts = [target.summary, *(message.content for message in user_messages[-MAX_QUERY_MESSAGES:])]

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


def _error_result(invocation_id: str) -> KnowledgeResult:
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
    )
