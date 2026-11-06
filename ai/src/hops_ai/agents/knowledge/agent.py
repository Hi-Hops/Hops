"""Knowledge 역할: 허용된 지식을 검색해 근거와 직원 안내를 제안한다.

검색은 모델을 호출하지 않는다. 질의는 그 문제 자신의 정보로 규칙에 따라 만든다. 문제 하나가
문의 전체의 주제일 때만 요약과 사용자 원문을 함께 쓰고, 한 문의에 다른 문제가 함께 있으면 그
문제의 단어로 검색이 오염되므로 원문과 모델이 쓴 요약은 쓰지 않고 검증된 상세 값만 쓴다.
검색 범위(공개 범위·시스템)는 서버가 확정한 접근 문맥에서만 가져온다. 안내문은 근거 문서의
승인된 본문을 그대로 쓰고 출처 제목만 덧붙인다. 모델이 문구를 새로 쓰지 않으므로 안내가
근거와 어긋나거나 근거 밖 내용을 담을 수 없다.

- 검색 0건(EMPTY)에는 안내를 만들지 않고, 검색 오류(ERROR)를 0건으로 바꾸지 않는다.
- 안내는 직원 공개 GUIDE 근거가 있을 때만 제안한다. 담당자 전용 자료는 근거에 포함될 수
  있어도 안내문에 쓰지 않는다.
- 호출에 대상 문제(`targets`)가 없으면(역할 단위 결정 경로) 안내로 끝나는 유형(사용법) 문제
  하나의 안내만 다루고 지식 종류는 제한하지 않는다. 대상이 있으면(문제별 행동 경로) 그 문제를
  정책 표의 검색 종류로 검색하며, 아직 하지 않은 질의를 하나 고른다. 첫 질의는 문제 자신의
  정보로 만든 기존 질의이고, 재검색은 ②의 키워드만, 키워드와 확인한 상세 값 순서다. 관계
  조회(Neo4j)는 포함하지 않는다.
- assess_evidence를 켜면 검색 뒤에 근거의 충분성 판정(Assessment)을 한 번 더 만든다. 결과가
  1건 이상일 때만 모델을 부르고(문제당 1회), 0건은 코드가 NO_MATCH로 확정한다. 검색 오류는
  평가하지 않는다. 모델 오류·검증 거부·예산 부족은 "평가 없음"으로 두고 검색 결과와 안내는
  그대로 돌려준다. 안내는 판정과 무관하게 지금처럼 승인 본문으로 조립한다(판정을 읽는 것은
  Supervisor의 행동 선택이다).

반환하는 결과는 제안이다. 권한 확인과 digest 확인은 검색 자료가 마쳤다고 알린 값이며,
Supervisor 병합 단계가 현재 접근 범위와 다시 대조한다.
"""

from dataclasses import replace
from uuid import uuid4

from pydantic import ValidationError

from hops_ai.agents.knowledge.assessment import (
    MAX_OUTPUT_TOKENS,
    PROMPT_VERSION,
    RESERVED_CALLS,
    SYSTEM_PROMPT,
    AssessmentDraft,
    build_user_payload,
    to_assessment,
)
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.contracts.roles import Assessment, KnowledgeResult, SearchError, SearchResult
from hops_ai.contracts.state import RoleRequest, RoleResponse, SupervisorState
from hops_ai.contracts.wire import GuideProposal, ProblemProposal
from hops_ai.harness.errors import OutputRejected, RetrievalUnavailable, TransientError
from hops_ai.rules.assessment import no_match_assessment, validate_assessment
from hops_ai.rules.intent_policy import is_guide_type, search_kinds
from hops_ai.rules.progress import get_problem_message_ids, get_problem_progress, searched_queries
from hops_ai.services.llm.client import LLMMessage, LLMRequest
from hops_ai.services.retrieval.knowledge_source import (
    MAX_TOP_K,
    SEARCH_KNOWLEDGE,
    KnowledgeHit,
    KnowledgeQuery,
    KnowledgeSource,
    RunLink,
)
from hops_ai.services.shared import SharedServices

# 질의에 쓰는 최근 사용자 메시지 수. 오래된 대화가 최신 질문을 가리지 않게 한다.
MAX_QUERY_MESSAGES = 5


class SearchKnowledgeAgent:
    role = AgentRole.KNOWLEDGE

    def __init__(self, source: KnowledgeSource, *, assess_evidence: bool = False) -> None:
        """assess_evidence가 참이면 검색 결과의 충분성 판정을 함께 만든다(문제당 모델 1회)."""
        self._source = source
        self._assess_evidence = assess_evidence

    async def run(self, call: RoleRequest, services: SharedServices) -> RoleResponse:

        services.runtime.ensure_active()
        state = call.state
        if state["input"].kind != RunKind.INQUIRY_ANALYSIS:
            raise OutputRejected("이 Knowledge 구현은 상담 실행의 안내만 다룬다")
        if call.targets:
            target, query = _targeted_search(state, call.targets, services)
        else:
            target = _guide_target(state)
            query = _build_query(state, target, services)
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
            assessment=await self._assess(target, hits, services),
        )
        return RoleResponse(
            result=result,
            authorized_refs=tuple(hit.reference for hit in hits),
            verified_evidence=evidence,
        )

    async def _assess(
        self, target: ProblemProposal, hits: list[KnowledgeHit], services: SharedServices
    ) -> Assessment | None:
        """검색 근거의 충분성 판정. 만들지 못하면 None(평가 없음)이며 흐름을 막지 않는다.

        예산·취소·기한 예외(BudgetExceeded 등)는 평가 실패가 아니라 실행 실패이므로 그대로 전달한다.
        """
        if not self._assess_evidence:
            return None
        if not hits:
            return no_match_assessment(target.problemRef)
        budget = services.runtime.budget
        # 평가가 실행 예산을 다 써서 이후의 필수 호출(판단·질문·티켓)을 막지 않게 한다.
        if budget.max_llm_calls - budget.llm_calls_used < 1 + RESERVED_CALLS:
            return None
        try:
            response = await services.llm.generate(
                LLMRequest(
                    messages=[
                        LLMMessage("system", SYSTEM_PROMPT),
                        LLMMessage("user", build_user_payload(target, hits)),
                    ],
                    response_model=AssessmentDraft,
                    max_output_tokens=MAX_OUTPUT_TOKENS,
                    prompt_version=PROMPT_VERSION,
                )
            )
        except (OutputRejected, TransientError):
            return None
        services.runtime.ensure_active()
        try:
            assessment = to_assessment(target.problemRef, response.parsed)
            validate_assessment(
                assessment,
                intent=target.intent,
                search_state="FOUND",
                evidence=[hit.evidence for hit in hits],
            )
        except (ValidationError, OutputRejected):
            return None
        return assessment


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
        if not is_guide_type(problem.processingType) or not problem.reportedSystemCandidateId:
            continue
        digests = searched.get(problem.problemRef, set())
        # 지문이 없는 결과는 어떤 질의였는지 알 수 없어 다시 검색하지 않는다.
        if None in digests or _build_query(state, problem).digest() in digests:
            continue
        return problem
    return None


def _targeted_search(
    state: SupervisorState, targets: tuple[str, ...], services: SharedServices
) -> tuple[ProblemProposal, KnowledgeQuery]:
    """문제별 행동 경로의 검색 대상과 질의. 한 호출은 문제 하나를 다룬다."""
    if len(targets) != 1:
        raise OutputRejected("Knowledge 호출은 문제 하나를 대상으로 한다")
    problem = state["problems"].get(targets[0])
    if problem is None:
        raise OutputRejected("존재하지 않는 문제를 검색할 수 없다")
    if not problem.reportedSystemCandidateId:
        raise OutputRejected("보고 시스템이 정해지지 않은 문제는 검색할 수 없다")
    if not search_kinds(problem.intent, state["input"].accessContext.allowedDocumentScopes):
        raise OutputRejected("이 의도는 이번 실행에서 검색하지 않는다")
    query = next_query(state, problem, services)
    if query is None:
        raise OutputRejected("아직 하지 않은 새 질의가 없다")
    return problem, query


def candidate_queries(
    state: SupervisorState, problem: ProblemProposal, services: SharedServices | None = None
) -> list[KnowledgeQuery]:
    """문제별 행동 경로가 쓸 질의 후보를 쓰는 순서대로 돌려준다. 검색하지 않는 의도는 비어 있다.

    첫 번째는 문제 자신의 정보로 만든 기존 질의(정책 표의 검색 종류를 더함)다. 이어서 ②의
    키워드만으로 만든 질의, 키워드와 확인한 상세 값을 함께 쓴 질의가 있다. 키워드가 없으면
    첫 번째뿐이다. 같은 지문의 질의는 한 번만 나온다.
    """
    request = state["input"]
    kinds = search_kinds(problem.intent, request.accessContext.allowedDocumentScopes)
    if not kinds:
        return []
    base = replace(_build_query(state, problem, services), kinds=kinds)
    queries = [base]
    keywords = _searchable_keywords(state, problem)
    if keywords:
        keyword_text = " ".join(keywords)
        queries.append(replace(base, text=keyword_text))
        details = _detail_values(problem)
        if details:
            queries.append(replace(base, text="\n".join([keyword_text, *details])))
    unique: dict[str, KnowledgeQuery] = {}
    for query in queries:
        unique.setdefault(query.digest(), query)
    return list(unique.values())


def next_query(
    state: SupervisorState, problem: ProblemProposal, services: SharedServices | None = None
) -> KnowledgeQuery | None:
    """이번 실행에서 그 문제에 아직 쓰지 않은 첫 질의 후보. 없으면 None이다.

    지문이 없는 이전 검색 결과가 있으면 어떤 질의였는지 알 수 없어 다시 검색하지 않는다.
    """
    searched = searched_queries(state).get(problem.problemRef, set())
    if None in searched:
        return None
    for query in candidate_queries(state, problem, services):
        if query.digest() not in searched:
            return query
    return None


def research_used(state: SupervisorState, problem_ref: str) -> int:
    """이번 실행에서 그 문제를 첫 검색 뒤에 다시 검색한 횟수."""
    return max(0, len(searched_queries(state).get(problem_ref, ())) - 1)


def _searchable_keywords(state: SupervisorState, problem: ProblemProposal) -> list[str]:
    """②의 키워드에서 시스템 이름·코드를 뺀 것. 시스템은 검색 범위로 따로 좁히므로 질의에 넣으면
    점수 분모만 키운다. 시스템 이름이 통째로 들어 있는 키워드를 뺀다."""
    analysis = state["analysis"].get(problem.problemRef)
    if analysis is None:
        return []
    names = {name for system in state["input"].systems for name in (system.name, system.code)}
    return [word for word in analysis.keywords if not any(name in word for name in names)]


def _guide_target(state: SupervisorState) -> ProblemProposal:
    """안내할 문제를 고른다. 상태에는 안내가 하나뿐이므로 이미 있으면 거부한다."""
    if state["guide"] is not None:
        raise OutputRejected("이미 안내가 준비되어 더 만들 수 없다")
    target = guidable_problem(state)
    if target is None:
        # 빈 결과로 성공 처리하면 Supervisor가 같은 호출을 반복한다. 대상이 없다고 알린다.
        raise OutputRejected("안내할 수 있는 사용법 문제가 없다")
    return target


def _build_query(
    state: SupervisorState, target: ProblemProposal, services: SharedServices | None = None
) -> KnowledgeQuery:
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
        run=_run_link(request, target, services),
    )


def _run_link(request, target: ProblemProposal, services: SharedServices | None) -> RunLink | None:
    """Spring 내부 API 검색에 쓸 실행 문맥. 한 번 부를 때마다 도구 호출 한도 1회를 소비한다."""
    if services is None:
        return None
    runtime = services.runtime

    def reserve_call() -> float:
        runtime.ensure_active()
        runtime.tool_policy.authorize(AgentRole.KNOWLEDGE, SEARCH_KNOWLEDGE)
        runtime.budget.start_tool_call()
        return runtime.budget.remaining_seconds()

    return RunLink(request.runId, request.inquiryVersion, target.problemRef, reserve_call)


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
