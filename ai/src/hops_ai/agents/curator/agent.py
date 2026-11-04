"""Curator 역할: 검증 완료 처리 기록 하나를 재사용 사례 초안으로 제안한다.

모델은 재사용 여부와 설명 문구만 정한다(drafting.py). 실제 조치·검증·적용 버전은 원 기록을,
공개 범위와 적용 시스템 연결은 서버가 준 접근 문맥과 원 기록을 그대로 쓴다. 문제 설명의
출처는 원문 대조를 통과한 메시지만 남긴다.

reusable=false는 사례 없음이라는 정상 결과다. 모델 오류·형식 오류는 사례 없음으로 바꾸지 않고
그대로 실패시킨다. 초안 저장·승인·검색 공개는 Spring과 담당자가 결정한다.
"""

from typing import TYPE_CHECKING

from pydantic import ValidationError

from hops_ai.agents.curator.drafting import (
    MAX_OUTPUT_TOKENS,
    MAX_TEXT_LENGTH,
    MAX_TITLE_LENGTH,
    PROMPT_VERSION,
    SYSTEM_PROMPT,
    CaseDraft,
    build_user_payload,
    clean_limitations,
    ground_sources,
)
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import CuratorResult
from hops_ai.contracts.wire import AiRunRequest, CaseProposal, EntityLink, WorkRecordContext
from hops_ai.harness.errors import InputRejected, OutputRejected
from hops_ai.services.llm.client import LLMMessage, LLMRequest
from hops_ai.services.shared import SharedServices

if TYPE_CHECKING:
    # supervisor 패키지가 registry를 통해 이 모듈을 다시 불러오므로 타입 검사 때만 가져온다.
    from hops_ai.supervisor.execution import RoleRequest, RoleResponse

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


class ModelCuratorAgent:
    role = AgentRole.CURATOR

    async def run(
        self, call: "RoleRequest", services: SharedServices
    ) -> "CuratorResult | RoleResponse":
        from hops_ai.supervisor.execution import RoleResponse

        services.runtime.ensure_active()
        request = call.state["input"]
        record = _checked_record(request)
        if record.confirmationState == "UNRESOLVED":
            # 문의자가 해결되지 않았다고 답한 조치는 재사용 사례가 될 수 없다. 모델을 부르지 않는다.
            return CuratorResult(invocationId=call.invocation_id, proposal=no_reusable_case())

        draft = await self._draft(request, record, services)
        services.runtime.ensure_active()
        if not draft.reusable:
            return CuratorResult(invocationId=call.invocation_id, proposal=no_reusable_case())

        proposal = _assemble(draft, record, request)
        return RoleResponse(
            result=CuratorResult(invocationId=call.invocation_id, proposal=proposal),
            allowed_entity_ids={"APPLIES_TO": {record.systemId}},
        )

    @staticmethod
    async def _draft(
        request: AiRunRequest, record: WorkRecordContext, services: SharedServices
    ) -> CaseDraft:
        system_name = next(item.name for item in request.systems if item.id == record.systemId)
        problem = next((item for item in request.problems if item.id == record.problemId), None)
        response = await services.llm.generate(
            LLMRequest(
                messages=[
                    LLMMessage("system", SYSTEM_PROMPT),
                    LLMMessage(
                        "user",
                        build_user_payload(record, system_name, problem, request.messages),
                    ),
                ],
                response_model=CaseDraft,
                max_output_tokens=MAX_OUTPUT_TOKENS,
                prompt_version=PROMPT_VERSION,
            )
        )
        return response.parsed


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


def _assemble(draft: CaseDraft, record: WorkRecordContext, request: AiRunRequest) -> CaseProposal:
    """모델 문구와 원 기록 값을 합쳐 계약 형식의 사례 초안을 만든다."""
    title = _required_text(draft.title, "제목")[:MAX_TITLE_LENGTH].strip()
    problem_statement = _required_text(draft.problemStatement, "문제 설명")
    applicability = _required_text(draft.applicability, "적용 조건")
    try:
        limitations = clean_limitations(draft.limitations)
    except ValueError:
        raise OutputRejected("사례 제한 조건이 허용 길이를 넘었다") from None
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
            entityLinks=[
                EntityLink(relation="APPLIES_TO", targetId=record.systemId, evidenceIds=source_ids)
            ],
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
