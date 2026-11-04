"""Inquiry 역할: 문의 원문에서 사실을 추출하고 부족한 정보를 질문으로 만든다.

모델이 하는 일은 원문 구절 추출뿐이다(extraction.py). 확보한 정보가 무엇인지는
원문 대조로, 무엇을 더 물을지는 양식 규칙(forms.py)으로 코드가 정한다.
모델이 질문 내용·시스템·처리 유형을 정하지 못하므로 모델이나 프롬프트를 바꿔도
같은 규칙과 검증이 적용된다.

반환하는 InquiryResult는 제안일 뿐이다. 저장·배정은 Spring이 하고, 병합 검증은 Supervisor가 한다.
"""

from collections.abc import Mapping, Sequence
from typing import TYPE_CHECKING

from hops_ai.agents.inquiry.extraction import (
    DETAIL_FIELDS,
    MAX_OUTPUT_TOKENS,
    MAX_PROBLEMS,
    PROMPT_VERSION,
    SYSTEM_PROMPT,
    ExtractedProblem,
    InquiryExtraction,
    build_user_payload,
    ground,
    ground_unknown,
)
from hops_ai.agents.inquiry.forms import FIELD_LABELS, plan_next
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import InquiryResult
from hops_ai.contracts.wire import (
    EvidenceStatement,
    InputProblem,
    Intent,
    ProblemProposal,
    ProcessingType,
    QuestionProposal,
    RequestDetails,
)
from hops_ai.harness.errors import OutputRejected
from hops_ai.services.llm.client import LLMMessage, LLMRequest
from hops_ai.services.shared import SharedServices

if TYPE_CHECKING:
    # supervisor 패키지가 registry를 통해 이 모듈을 다시 불러오므로 타입 검사 때만 가져온다.
    from hops_ai.supervisor.execution import RoleRequest
    from hops_ai.supervisor.state import SupervisorState

# 의도에서 규칙으로 정하는 처리 유형. 모델이 정하지 않으며 새 장애(INCIDENT)는 확정하지 않는다.
PROCESSING_TYPES: Mapping[str, ProcessingType] = {
    "HOW_TO": "KNOWLEDGE_QA",
    "ACCESS_REQUEST": "SERVICE_REQUEST",
    "OBSERVED_SYMPTOM": "INVESTIGATION",
    "FEATURE_REQUEST": "CHANGE_REQUEST",
    "IMPROVEMENT_REQUEST": "CHANGE_REQUEST",
    "UNKNOWN": "REVIEW_REQUIRED",
    "OUT_OF_SCOPE": "OUT_OF_SCOPE",
}


class ExtractionInquiryAgent:
    role = AgentRole.INQUIRY

    def __init__(self, *, guide_first: bool = False) -> None:
        """guide_first: 승인 가이드를 먼저 찾을 수 있을 때(Knowledge 연결) 참으로 둔다.

        사용법 문의에서 검색에 필요한 정보(시스템, 하려는 작업·화면)만 확보하면 추가 질문 없이
        Knowledge로 넘어간다. 거짓이면 기존 양식 질문을 그대로 한다.
        """
        self._guide_first = guide_first

    def available_for(self, state: "SupervisorState") -> bool:
        """지금 호출해도 할 일이 있는지.

        - Knowledge가 근거를 찾지 못했고 남은 사용법 문제가 모두 담당자 전달 정보를 갖췄다면,
          다시 호출해도 같은 원문에서 같은 사실을 다시 뽑을 뿐 새 질문을 만들 수 없다.
        - Inquiry가 이미 돌았고 모든 문제에 결과가 준비됐다면, 질문을 만들 문제가 없고 이미 만든
          질문과 충돌할 위험만 있다.
        사용자가 새 메시지로 답한 다음 실행은 새 State라서 다시 호출할 수 있다.
        """
        from hops_ai.supervisor.validation import (
            all_unfinished_ready_for_handoff,
            inquiry_has_nothing_left_to_do,
        )

        if state["input"].kind != "INQUIRY_ANALYSIS":
            return True
        return not (
            all_unfinished_ready_for_handoff(state) or inquiry_has_nothing_left_to_do(state)
        )

    async def run(self, call: "RoleRequest", services: SharedServices) -> InquiryResult:
        # supervisor 패키지는 이 모듈을 불러오는 registry를 거쳐 초기화되므로 호출 시점에 가져온다.
        from hops_ai.supervisor.validation import problems_without_guide

        services.runtime.ensure_active()
        request = call.state["input"]
        existing = list(request.problems)
        # Knowledge가 그 문제를 오류 없이 검색했지만 안내를 만들지 못했다면 사용법 문제는 안내 대신
        # 담당자 전달에 필요한 정보를 묻는다. 검색 오류는 여기에 해당하지 않는다.
        no_guide = problems_without_guide(call.state)
        extraction = await self._extract(call, services)
        services.runtime.ensure_active()
        if not extraction.problems:
            raise OutputRejected("추출 결과에 문제가 없다")

        messages = {message.id: message for message in request.messages}
        system_names = [system.name for system in request.systems]
        system_ids = {system.code: system.id for system in request.systems}
        problems: list[ProblemProposal] = []
        questions: list[QuestionProposal] = []
        for index, extracted in enumerate(extraction.problems[:MAX_PROBLEMS]):
            original = existing[index] if index < len(existing) else None
            ref = original.id if original is not None else f"new:{index + 1}"
            # 같은 실행에서 앞서 만든 같은 문제. 다시 호출돼도 이전 확인 사실을 이어받는다.
            previous = call.state["problems"].get(ref)
            problem, question = _assemble(
                ref,
                original,
                extracted,
                ground(extracted, messages),
                ground_unknown(extracted, messages),
                system_ids,
                system_names,
                guide_first=self._guide_first,
                knowledge_empty=ref in no_guide,
                previous=previous,
            )
            problems.append(problem)
            if question is not None:
                questions.append(question)
        return InquiryResult(
            invocationId=call.invocation_id, problems=problems, questions=questions
        )

    @staticmethod
    async def _extract(call: "RoleRequest", services: SharedServices) -> InquiryExtraction:
        request = call.state["input"]
        response = await services.llm.generate(
            LLMRequest(
                messages=[
                    LLMMessage("system", SYSTEM_PROMPT),
                    LLMMessage(
                        "user",
                        build_user_payload(
                            request.messages,
                            request.systems,
                            [(item.id, item.summary, item.intent) for item in request.problems],
                        ),
                    ),
                ],
                response_model=InquiryExtraction,
                max_output_tokens=MAX_OUTPUT_TOKENS,
                prompt_version=PROMPT_VERSION,
            )
        )
        return response.parsed


def _assemble(
    ref: str,
    original: InputProblem | None,
    extracted: ExtractedProblem,
    grounded: Mapping[str, tuple[str, str]],
    stated_unknown: set[str],
    system_ids: Mapping[str, str],
    system_names: Sequence[str],
    *,
    guide_first: bool = False,
    knowledge_empty: bool = False,
    previous: ProblemProposal | None = None,
) -> tuple[ProblemProposal, QuestionProposal | None]:
    """한 문제의 추출 결과를 제안으로 조립한다. 서버가 확정한 값은 모델이 바꾸지 못한다.

    previous: 같은 실행에서 앞서 병합된 같은 문제. 모델은 이전 추출을 모르고 매번 다시 추출하므로
        구절이 달라질 수 있다. 이전 확인 사실과 값은 코드가 그대로 이어받고 새로 확인된 것만 더한다.
    """
    if original is not None:
        # 이미 서버가 분류한 문제의 의도·처리 유형·보고 시스템은 유지하고 새 정보만 보강한다.
        intent: Intent = original.intent
        processing_type: ProcessingType = original.processingType
        system_id = original.reportedSystemId or system_ids.get(extracted.systemCode or "")
    else:
        intent = extracted.intent
        processing_type = PROCESSING_TYPES[intent]
        system_id = system_ids.get(extracted.systemCode or "")

    if previous is not None and system_id is None:
        system_id = previous.reportedSystemCandidateId
    known = _known_values(original, grounded, previous)
    # 사용자가 직접 모른다고 말한 것으로 원문에서 확인된 필드만 질문에서 뺀다.
    declared_unknown = {name for name in stated_unknown if name not in known}
    plan = plan_next(
        intent,
        known,
        system_known=system_id is not None,
        system_names=system_names,
        question_count=original.questionCount if original is not None else 0,
        unknown=declared_unknown,
        guide_first=guide_first,
        handoff=knowledge_empty and processing_type == "KNOWLEDGE_QA",
    )

    facts = _carry_facts(
        previous,
        [
            EvidenceStatement(
                text=f"{FIELD_LABELS[name]}: {quote}",
                sourceMessageIds=[message_id],
                knowledgeEvidenceIds=[],
            )
            for name, (message_id, quote) in grounded.items()
        ],
    )
    unknown_names = [name for name in (*declared_unknown, *plan.unknown) if name in FIELD_LABELS]
    unknowns = list(dict.fromkeys(FIELD_LABELS[name] for name in unknown_names))
    details = RequestDetails(
        **{name: known.get(name) for name in DETAIL_FIELDS},
        acceptanceCriteria=_kept_criteria(original),
    )
    problem = ProblemProposal(
        problemRef=ref,
        summary=extracted.summary.strip() or _fallback_summary(grounded),
        intent=intent,
        processingType=processing_type,
        reportedSystemCandidateId=system_id,
        facts=facts,
        assumptions=[],
        unknowns=unknowns,
        details=details,
    )
    question = None
    if plan.action == "ASK":
        asked = original.questionCount if original is not None else 0
        question = QuestionProposal(
            problemRef=ref,
            bundleRef=f"inquiry-{ref.replace(':', '-')}-{asked + 1}",
            text=plan.text,
            requestedFields=list(plan.fields),
        )
    return problem, question


def _carry_facts(
    previous: ProblemProposal | None, found: list[EvidenceStatement]
) -> list[EvidenceStatement]:
    """이전 확인 사실을 먼저 두고, 이번에 새로 확인된 사실만 뒤에 더한다.

    같은 항목·같은 메시지에서 이미 있는 구절의 일부일 뿐인 사실은 새 정보가 없으므로 더하지 않는다.
    이전보다 긴 구절은 정보가 늘어나므로 더하고, 이전 사실은 병합 규칙 때문에 항상 남긴다.
    """
    if previous is None:
        return found
    carried = list(previous.facts)
    return carried + [fact for fact in found if not _already_covered(fact, carried)]


def _already_covered(fact: EvidenceStatement, carried: Sequence[EvidenceStatement]) -> bool:
    label, _, quote = fact.text.partition(": ")
    for existing in carried:
        existing_label, _, existing_quote = existing.text.partition(": ")
        if (
            existing_label == label
            and existing.sourceMessageIds == fact.sourceMessageIds
            and existing.knowledgeEvidenceIds == fact.knowledgeEvidenceIds
            and quote.strip() in existing_quote
        ):
            return True
    return False


def _known_values(
    original: InputProblem | None,
    grounded: Mapping[str, tuple[str, str]],
    previous: ProblemProposal | None = None,
) -> dict[str, str | None]:
    """이전에 확보한 값 위에 이번 원문에서 확인한 값을 덮는다.

    이전 값은 같은 실행에서 앞서 병합된 값이다. 이번 추출이 같은 필드를 놓쳐도 확보한 정보로 본다.
    """
    known: dict[str, str | None] = {}
    if previous is not None:
        known.update({name: getattr(previous.details, name) for name in DETAIL_FIELDS})
    if original is not None and original.standardRequest is not None:
        previous = original.standardRequest.details
        known.update({name: getattr(previous, name) for name in DETAIL_FIELDS})
    known.update({name: quote for name, (_, quote) in grounded.items()})
    return known


def _kept_criteria(original: InputProblem | None) -> list:
    """수용 조건은 서버가 확인한 것만 그대로 보존한다. 모델이 새로 만들지 않는다."""
    if original is None or original.standardRequest is None:
        return []
    return list(original.standardRequest.details.acceptanceCriteria)


def _fallback_summary(grounded: Mapping[str, tuple[str, str]]) -> str:
    for _, quote in grounded.values():
        return quote
    return "문의 내용 확인 필요"
