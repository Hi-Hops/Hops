"""Curator 역할: 검증 완료 처리 기록 하나를 재사용 사례 초안으로 제안한다.

사례 그래프 `collect → draft → link → validate`를 한 역할 안의 서브그래프로 실행한다.
- collect: 서버 입력을 확인한다(모델 없음). 문의자가 미해결로 답한 기록은 여기서
  사례 없음으로 끝난다.
- draft: 모델이 재사용 여부와 설명 문구만 정한다(drafting.py). 실제 조치·검증·원인·적용 버전은
  원 기록을, 공개 범위와 적용 시스템 연결은 서버가 준 접근 문맥과 원 기록을 그대로 쓴다. 문제 설명의
  출처는 원문 대조를 통과한 사용자 메시지만 남긴다. 해결되지 않은 시도는 코드가
  한계 문장으로 옮긴다.
- link: Spring이 준 후보(기존 증상·확인 항목) 안에서만 `ADDRESSES`·`USES_CHECK` 연결을 고른다
  (link.py). 후보가 없으면 모델을 부르지 않는다. 연결마다 근거 인용을 대조하고
  확인되지 않은 연결은 버린다.
- validate: 초안과 연결을 계약의 CaseProposal로 조립하고 기존 엔티티 목록과 함께 돌려준다.

reusable=false는 사례 없음이라는 정상 결과다. 모델 오류·형식 오류는 사례 없음이나 연결 없음으로
바꾸지 않고 그대로 실패시킨다(`link` 실패도 마찬가지다). 초안 저장·승인·검색 공개는 Spring과
담당자가 결정한다.
"""

from collections.abc import Awaitable, Callable
from typing import TypedDict

from langgraph.graph import END, START, StateGraph
from pydantic import ValidationError

from hops_ai.agents.curator import drafting, link
from hops_ai.agents.curator.drafting import (
    MAX_LIMITATIONS,
    MAX_TEXT_LENGTH,
    MAX_TITLE_LENGTH,
    CaseDraft,
    clean_limitations,
    failed_attempt_limitations,
    ground_sources,
)
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import CuratorResult
from hops_ai.contracts.state import RoleRequest, RoleResponse
from hops_ai.contracts.wire import (
    AiRunRequest,
    CaseProposal,
    EntityLink,
    LinkCandidate,
    WorkRecordContext,
)
from hops_ai.harness.errors import InputRejected, OutputRejected
from hops_ai.services.llm.client import LLMMessage, LLMRequest
from hops_ai.services.shared import SharedServices

# 담당자 처리 기록에서 나온 사례는 담당자에게만 공개한다. 문의자 공개 범위로 넓히지 않는다.
CASE_ACCESS_SCOPE = "SYSTEM_STAFF"


def no_reusable_case() -> CaseProposal:
    """계약의 사례 없음 결과. 오류가 아니다."""
    return CaseProposal(
        reusable=False,
        title=None,
        problemStatement=None,
        action=None,
        verification=None,
        applicability=None,
        limitations=[],
        appliesToSystemVersion=None,
        accessScope=None,
        sourceMessageIds=[],
        entityLinks=[],
    )


class _CaseFlow(TypedDict):
    """사례 그래프의 작업 State. 노드는 자기가 채우는 키만 반환한다. 외부에 반환하지 않는다."""

    invocation_id: str
    request: AiRunRequest
    record: WorkRecordContext | None
    draft: CaseDraft | None
    links: list[EntityLink]
    # 끝낸 결과. 채워지면 이후 노드를 건너뛴다(사례 없음).
    outcome: CuratorResult | RoleResponse | None


class ModelCuratorAgent:
    role = AgentRole.CURATOR

    async def run(
        self, call: RoleRequest, services: SharedServices
    ) -> CuratorResult | RoleResponse:

        services.runtime.ensure_active()
        graph = self._build_graph(services)
        flow = await graph.ainvoke(
            _CaseFlow(
                invocation_id=call.invocation_id,
                request=call.state["input"],
                record=None,
                draft=None,
                links=[],
                outcome=None,
            )
        )
        services.runtime.ensure_active()
        outcome = flow["outcome"]
        if outcome is None:
            raise OutputRejected("사례 그래프가 결과 없이 끝났다")
        return outcome

    def _build_graph(self, services: SharedServices):
        """collect → draft → link → validate. 사례 없음이 정해지면 나머지는 건너뛴다."""

        async def collect(flow: _CaseFlow) -> dict:
            record = _checked_record(flow["request"])
            if record.confirmationState == "UNRESOLVED":
                # 문의자가 해결되지 않았다고 답한 조치는 재사용 사례가 될 수 없다.
                # 모델을 부르지 않는다.
                return {"record": record, "outcome": _no_case(flow["invocation_id"])}
            return {"record": record}

        async def draft(flow: _CaseFlow) -> dict:
            result = await self._draft(flow["request"], flow["record"], services)
            if not result.reusable:
                return {"draft": result, "outcome": _no_case(flow["invocation_id"])}
            return {"draft": result}

        async def find_links(flow: _CaseFlow) -> dict:
            return {"links": await self._links(flow, services)}

        async def validate(flow: _CaseFlow) -> dict:
            proposal = _assemble(flow["draft"], flow["record"], flow["request"], flow["links"])
            return {
                "outcome": RoleResponse(
                    result=CuratorResult(invocationId=flow["invocation_id"], proposal=proposal),
                    allowed_entity_ids=_allowed_entities(flow["record"]),
                )
            }

        def traced(
            name: str, node: Callable[[_CaseFlow], Awaitable[dict]]
        ) -> Callable[[_CaseFlow], Awaitable[dict]]:
            async def run_node(flow: _CaseFlow) -> dict:
                with services.tracing.observe(f"CURATOR.{name}", {"node": name}):
                    return await node(flow)

            return run_node

        def next_or_end(target: str) -> Callable[[_CaseFlow], str]:
            return lambda flow: END if flow["outcome"] is not None else target

        graph = StateGraph(_CaseFlow)
        graph.add_node("collect", traced("collect", collect))
        graph.add_node("draft", traced("draft", draft))
        graph.add_node("link", traced("link", find_links))
        graph.add_node("validate", traced("validate", validate))
        graph.add_edge(START, "collect")
        graph.add_conditional_edges("collect", next_or_end("draft"), {"draft": "draft", END: END})
        graph.add_conditional_edges("draft", next_or_end("link"), {"link": "link", END: END})
        graph.add_edge("link", "validate")
        graph.add_edge("validate", END)
        return graph.compile()

    @staticmethod
    async def _draft(
        request: AiRunRequest, record: WorkRecordContext, services: SharedServices
    ) -> CaseDraft:
        system_name = _system_name(request, record)
        problem = next((item for item in request.problems if item.id == record.problemId), None)
        response = await services.llm.generate(
            LLMRequest(
                messages=[
                    LLMMessage("system", drafting.SYSTEM_PROMPT),
                    LLMMessage(
                        "user",
                        drafting.build_user_payload(record, system_name, problem, request.messages),
                    ),
                ],
                response_model=CaseDraft,
                max_output_tokens=drafting.MAX_OUTPUT_TOKENS,
                prompt_version=drafting.PROMPT_VERSION,
            )
        )
        return response.parsed

    @staticmethod
    async def _links(flow: _CaseFlow, services: SharedServices) -> list[EntityLink]:
        """재사용 가능한 초안의 증상·확인 항목 연결을 고른다. 후보가 없으면 모델을 부르지 않는다."""
        request, record, draft = flow["request"], flow["record"], flow["draft"]
        source_ids = ground_sources(draft.sources, request.messages)
        # APPLIES_TO는 대상이 원 기록의 시스템 하나로 정해져 있어 모델에 맡기지 않는다.
        links = [
            EntityLink(relation="APPLIES_TO", targetId=record.systemId, evidenceIds=source_ids)
        ]
        candidates: list[LinkCandidate] = list(record.linkCandidates)
        if not candidates or not source_ids:
            return links
        evidence = link.evidence_texts(record, request.messages)
        response = await services.llm.generate(
            LLMRequest(
                messages=[
                    LLMMessage("system", link.SYSTEM_PROMPT),
                    LLMMessage(
                        "user",
                        link.build_link_payload(
                            _system_name(request, record),
                            (draft.problemStatement or "").strip(),
                            (draft.applicability or "").strip(),
                            record,
                            candidates,
                            evidence,
                        ),
                    ),
                ],
                response_model=link.LinkSelection,
                max_output_tokens=link.MAX_OUTPUT_TOKENS,
                prompt_version=link.PROMPT_VERSION,
                response_schema=link.link_schema(
                    [item.id for item in candidates], [item.id for item in evidence]
                ),
            )
        )
        return links + link.ground_links(response.parsed, candidates, evidence)


def _system_name(request: AiRunRequest, record: WorkRecordContext) -> str:
    return next(item.name for item in request.systems if item.id == record.systemId)


def _no_case(invocation_id: str) -> CuratorResult:
    return CuratorResult(invocationId=invocation_id, proposal=no_reusable_case())


def _allowed_entities(record: WorkRecordContext) -> dict[str, set[str]]:
    """연결할 수 있는 기존 엔티티. 시스템은 원 기록의 것, 증상·확인 항목은 서버가 준 후보다."""
    return {
        "APPLIES_TO": {record.systemId},
        "ADDRESSES": {item.id for item in record.linkCandidates if item.kind == "SYMPTOM"},
        "USES_CHECK": {item.id for item in record.linkCandidates if item.kind == "CHECK_ITEM"},
    }


def _checked_record(request: AiRunRequest) -> WorkRecordContext:
    """사례 실행에 필요한 서버 입력을 확인한다. 부족하면 모델을 부르기 전에 거부한다."""
    record = request.workRecord
    if record is None:
        raise InputRejected("사례 초안에는 원 처리 기록이 필요하다")
    if CASE_ACCESS_SCOPE not in request.accessContext.allowedDocumentScopes:
        raise InputRejected("담당자 공개 범위가 허용되지 않은 사례 실행이다")
    if record.systemId not in {item.id for item in request.systems}:
        raise InputRejected("원 처리 기록의 시스템이 지원 시스템 목록에 없다")
    if record.systemId not in request.accessContext.systemIds:
        raise InputRejected("원 처리 기록의 시스템에 접근 권한이 없다")
    return record


def _assemble(
    draft: CaseDraft,
    record: WorkRecordContext,
    request: AiRunRequest,
    links: list[EntityLink],
) -> CaseProposal:
    """모델 문구, 코드가 만든 한계, 검증된 연결, 원 기록 값을 합쳐 계약 형식 초안을 만든다."""
    title = _required_text(draft.title, "제목")[:MAX_TITLE_LENGTH].strip()
    problem_statement = _required_text(draft.problemStatement, "문제 설명")
    applicability = _required_text(draft.applicability, "적용 조건")
    try:
        model_limitations = clean_limitations(draft.limitations)
    except ValueError:
        raise OutputRejected("사례 제한 조건이 허용 길이를 넘었다") from None
    # 실패한 시도는 코드가 처리 기록 값으로 조립해 항상 남기고,
    # 모델이 쓴 한계는 남은 자리만큼만 담는다.
    attempts = failed_attempt_limitations(record)
    limitations = model_limitations[: max(0, MAX_LIMITATIONS - len(attempts))] + attempts
    source_ids = ground_sources(draft.sources, request.messages)
    if not source_ids:
        # 재사용 사례는 원문 출처를 담아야 한다. 확인되는 인용이 없으면 출력 오류다.
        raise OutputRejected("재사용 사례의 출처가 원문에서 확인되지 않는다")
    try:
        return CaseProposal(
            reusable=True,
            title=title,
            problemStatement=problem_statement,
            action=record.action,
            verification=record.verification,
            applicability=applicability,
            limitations=limitations,
            appliesToSystemVersion=record.appliesToSystemVersion,
            accessScope=CASE_ACCESS_SCOPE,
            sourceMessageIds=source_ids,
            entityLinks=links,
        )
    except ValidationError:
        raise OutputRejected("사례 초안이 계약 형식을 따르지 않는다") from None


def _required_text(value: str | None, name: str) -> str:
    """재사용 사례에 필요한 문구. 비어 있으면 사례 없음으로 바꾸지 않고 출력 오류로 본다."""
    text = (value or "").strip()
    if not text:
        raise OutputRejected(f"재사용 사례의 {name}이(가) 비어 있다")
    if len(text) > MAX_TEXT_LENGTH:
        raise OutputRejected(f"재사용 사례의 {name}이(가) 허용 길이를 넘었다")
    return text
