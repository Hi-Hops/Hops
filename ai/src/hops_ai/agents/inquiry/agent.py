"""Inquiry 역할: 문의 원문에서 사실을 추출하고 부족한 정보를 질문으로 만든다.

모델이 하는 일은 원문 구절 추출과 검색 힌트(키워드·기능 후보) 제안이다(extraction.py).
확보한 정보가 무엇인지는 원문 대조로, 무엇을 더 물을지는 양식 규칙(forms.py)으로 코드가 정한다.
검색 힌트는 사실이 아니라서 규칙(rules.analysis)으로 정리하고 질문·안내에 쓰지 않는다.
모델이 질문 내용·시스템·처리 유형을 정하지 못하므로 모델이나 프롬프트를 바꿔도
같은 규칙과 검증이 적용된다.

반환하는 InquiryResult는 제안일 뿐이다. 저장·배정은 Spring이 하고, 병합 검증은 Supervisor가 한다.
"""

from collections.abc import Mapping, Sequence
from typing import Literal

from hops_ai.agents.inquiry.asking import question_from_plan, question_plan
from hops_ai.agents.inquiry.extraction import (
    DETAIL_FIELDS,
    MAX_OUTPUT_TOKENS,
    MAX_PROBLEMS,
    PROMPT_VERSION,
    SYSTEM_PROMPT,
    ExtractedProblem,
    InquiryExtraction,
    build_user_payload,
    extraction_schema,
    ground,
    ground_unknown,
)
from hops_ai.agents.inquiry.forms import FIELD_LABELS, QuestionPlan, plan_next
from hops_ai.agents.inquiry.question_text import (
    MAX_OUTPUT_TOKENS as QUESTION_MAX_OUTPUT_TOKENS,
)
from hops_ai.agents.inquiry.question_text import (
    PROMPT_VERSION as QUESTION_PROMPT_VERSION,
)
from hops_ai.agents.inquiry.question_text import (
    RESERVED_CALLS as QUESTION_RESERVED_CALLS,
)
from hops_ai.agents.inquiry.question_text import (
    SYSTEM_PROMPT as QUESTION_SYSTEM_PROMPT,
)
from hops_ai.agents.inquiry.question_text import (
    QuestionDraft,
    RejectedQuestion,
    validate_text,
)
from hops_ai.agents.inquiry.question_text import (
    build_user_payload as build_question_payload,
)
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import InquiryResult, ProblemAnalysis
from hops_ai.contracts.state import RoleRequest
from hops_ai.contracts.types import QUESTION_LIMIT
from hops_ai.contracts.wire import (
    EvidenceStatement,
    InputProblem,
    Intent,
    ProblemProposal,
    ProcessingType,
    QuestionProposal,
    RequestDetails,
)
from hops_ai.harness.errors import OutputRejected, TransientError
from hops_ai.rules.analysis import (
    allowed_feature_ids,
    normalize_keywords,
    select_feature_candidates,
    user_texts,
)
from hops_ai.rules.intent_policy import is_guide_type, policy_for
from hops_ai.rules.progress import get_problem_message_ids, problems_without_guide, searched_queries
from hops_ai.rules.proposals import validate_problem_ref
from hops_ai.services.llm.client import LLMMessage, LLMRequest
from hops_ai.services.shared import SharedServices


class ExtractionInquiryAgent:
    role = AgentRole.INQUIRY

    def __init__(
        self,
        *,
        guide_first: bool = False,
        defer_questions: bool = False,
        generate_questions: bool = False,
    ) -> None:
        """guide_first: 승인 가이드를 먼저 찾을 수 있을 때(Knowledge 연결) 참으로 둔다.

        사용법 문의에서 검색에 필요한 정보(시스템, 하려는 작업·화면)만 확보하면 추가 질문 없이
        Knowledge로 넘어간다. 거짓이면 기존 양식 질문을 그대로 한다.
        """
        self._guide_first = guide_first
        self._defer_questions = defer_questions
        self._generate_questions = generate_questions

    async def run(self, call: RoleRequest, services: SharedServices) -> InquiryResult:

        services.runtime.ensure_active()
        if call.action == "ASK":
            return await self._ask(call, services)
        request = call.state["input"]
        existing = {item.id: item for item in request.problems}
        targets = set(request.problemIds) or existing.keys()
        # Knowledge가 그 문제를 오류 없이 검색했지만 안내를 만들지 못했다면 사용법 문제는 안내 대신
        # 담당자 전달에 필요한 정보를 묻는다. 검색 오류는 여기에 해당하지 않는다.
        no_guide = problems_without_guide(call.state)
        extraction = await self._extract(call, services)
        services.runtime.ensure_active()
        if not extraction.problems:
            # 문의로 볼 내용을 찾지 못했다. 문제를 지어내지 않고 빈 결과를 돌려주면
            # Supervisor가 대화 응답(REPLY)으로 사용자에게 다시 묻는다.
            return InquiryResult(invocationId=call.invocation_id, problems=[], questions=[])

        messages = {message.id: message for message in request.messages}
        system_names = [system.name for system in request.systems]
        system_ids = {system.code: system.id for system in request.systems}
        problems: list[ProblemProposal] = []
        questions: list[QuestionProposal] = []
        analysis: list[ProblemAnalysis] = []
        texts = user_texts(request)
        new_count = 0
        seen_existing: set[str] = set()
        for extracted in extraction.problems[:MAX_PROBLEMS]:
            grounded = ground(extracted, messages)
            unknown = ground_unknown(extracted, messages)
            cited = {message_id for message_id, _ in grounded.values()} | set(unknown.values())
            ref = extracted.problemRef
            if "problemRef" not in extracted.model_fields_set:
                # 이전 추출 형식에서만 원문의 연결로 기존 문제를 찾는다. 명시적 null은 신규 문제다.
                linked = [
                    set(messages[message_id].problemIds)
                    for message_id in cited
                    if messages[message_id].problemIds
                ]
                candidates = set.intersection(*linked) & targets if linked else targets
                if linked and len(candidates) != 1:
                    continue
                if len(candidates) == 1:
                    ref = next(iter(candidates))
            original = None
            if ref is not None:
                if ref not in existing:
                    raise OutputRejected("추출 결과에 알 수 없는 기존 문제 ID가 있다")
                validate_problem_ref(call.state, ref)
                original = existing[ref]
            else:
                if existing and not cited:
                    continue
                ref = f"new:{new_count + 1}"
            allowed = get_problem_message_ids(call.state, ref)
            if cited - allowed:
                # 다른 문제에 연결된 답변이나 미연결 원문을 기존 문제에 넣지 않는다.
                continue
            if original is None:
                new_count += 1
            else:
                if ref in seen_existing:
                    continue
                seen_existing.add(ref)
            # 같은 실행에서 앞서 만든 같은 문제. 다시 호출돼도 이전 확인 사실을 이어받는다.
            previous = call.state["problems"].get(ref)
            problem, question = _assemble(
                ref,
                original,
                extracted,
                grounded,
                set(unknown),
                system_ids,
                system_names,
                guide_first=self._guide_first,
                knowledge_empty=ref in no_guide,
                previous=previous,
            )
            problems.append(problem)
            # 질문을 미루면(문제별 행동 경로) 분석은 문제와 검색 힌트만 만든다. 질문은 ASK 행동이
            # 고른 뒤에 만든다.
            if question is not None and not self._defer_questions:
                questions.append(question)
            # 검색 힌트는 사실이 아니라서 인용 대조 대신 규칙으로 정리한다. 기능 후보는 코드가 정한
            # 문제의 시스템(서버 확인값 포함)을 기준으로 거른다. 모델이 시스템을 바꾸지 못한다.
            analysis.append(
                ProblemAnalysis(
                    problemRef=ref,
                    keywords=list(normalize_keywords(extracted.keywords, texts)),
                    featureCandidates=list(
                        select_feature_candidates(
                            extracted.featureCandidates,
                            allowed_feature_ids(request, problem.reportedSystemCandidateId),
                        )
                    ),
                )
            )
        return InquiryResult(
            invocationId=call.invocation_id,
            problems=problems,
            questions=questions,
            analysis=analysis,
        )

    async def _ask(self, call: RoleRequest, services: SharedServices) -> InquiryResult:
        """대상 문제 하나의 질문을 만든다. 무엇을 물을지는 항상 양식 규칙이 정한다.

        generate_questions가 켜져 있으면 문장을 모델이 쓰고, 실패하거나 규칙을 어기면 고정 문구를
        쓴다. 예산·취소·기한 예외는 질문 실패가 아니라 실행 실패라서 그대로 전달한다.
        """
        if len(call.targets) != 1:
            raise OutputRejected("질문 행동은 문제 하나를 대상으로 한다")
        ref = call.targets[0]
        # 검색했는지는 이번 실행의 검색 결과가 그 문제에 있는지로 안다.
        searched = ref in searched_queries(call.state)
        plan = question_plan(call.state, ref, searched=searched)
        text: str | None = None
        source: Literal["FIXED", "MODEL", "FIXED_FALLBACK"] = "FIXED"
        fallback: str | None = None
        # 정정 직후에는 이전 시스템이 적힌 원문을 재서술하지 않고 새 범위의 승인 문구를 쓴다.
        corrected = any(
            problem.id == ref and problem.reportedSystemCorrectionMessageId
            for problem in call.state["input"].problems
        )
        if self._generate_questions and plan.action == "ASK" and not corrected:
            text, fallback = await self._write_question(call, ref, plan, services)
            source = "MODEL" if text is not None else "FIXED_FALLBACK"
        question = question_from_plan(call.state, ref, plan, text)
        return InquiryResult(
            invocationId=call.invocation_id,
            problems=[],
            questions=[question],
            analysis=[],
            questionSource=source,
            questionFallback=fallback,
        )

    @staticmethod
    async def _write_question(
        call: RoleRequest, ref: str, plan: QuestionPlan, services: SharedServices
    ) -> tuple[str | None, str | None]:
        """모델이 쓴 질문 문장과 대체 사유. 문장을 못 얻으면 (None, 사유 코드)다."""
        state = call.state
        budget = services.runtime.budget
        remaining_output = state["input"].limits.maxOutputTokens - state["usage"]["outputTokens"]
        if (
            budget.max_llm_calls - budget.llm_calls_used < 1 + QUESTION_RESERVED_CALLS
            or remaining_output <= 0
        ):
            return None, "BUDGET"
        original = next((item for item in state["input"].problems if item.id == ref), None)
        asked = original.questionCount if original is not None else 0
        system_names = [system.name for system in state["input"].systems]
        try:
            response = await services.llm.generate(
                LLMRequest(
                    messages=[
                        LLMMessage("system", QUESTION_SYSTEM_PROMPT),
                        LLMMessage(
                            "user",
                            build_question_payload(
                                state["problems"][ref], plan, system_names, QUESTION_LIMIT - asked
                            ),
                        ),
                    ],
                    response_model=QuestionDraft,
                    max_output_tokens=min(QUESTION_MAX_OUTPUT_TOKENS, remaining_output),
                    prompt_version=QUESTION_PROMPT_VERSION,
                )
            )
        except (OutputRejected, TransientError):
            return None, "MODEL_ERROR"
        services.runtime.ensure_active()
        try:
            return validate_text(response.parsed.text, plan, system_names), None
        except RejectedQuestion as rejected:
            return None, f"INVALID:{rejected.code}"

    @staticmethod
    async def _extract(call: RoleRequest, services: SharedServices) -> InquiryExtraction:
        request = call.state["input"]
        response = await services.llm.generate(
            LLMRequest(
                messages=[
                    LLMMessage("system", SYSTEM_PROMPT),
                    LLMMessage("user", build_user_payload(request)),
                ],
                response_model=InquiryExtraction,
                response_schema=extraction_schema(
                    [system.code for system in request.systems],
                    request.problemIds or [item.id for item in request.problems],
                ),
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
        # 처리 유형은 의도별 정책 표가 정한다. 모델이 정하지 않는다.
        processing_type = policy_for(intent).processing_type
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
        asked=original.askedFields if original is not None else (),
        unknown=declared_unknown,
        guide_first=guide_first,
        handoff=knowledge_empty and is_guide_type(processing_type),
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

    이전 값은 같은 실행에서 앞서 병합된 값과 서버가 보존한 표준 요청서의 값이다. 이번 추출이
    같은 필드를 놓쳐도 확보한 정보로 본다. **실제 값이 있는 필드만** 담는다. 값이 비어 있는
    필드까지 키로 넣으면 "이미 안다"로 취급돼, 사용자가 모른다고 한 항목이 모름으로 인정되지
    않고 다시 질문된다(`declared_unknown`은 키 존재로 확보 여부를 본다).
    """
    known: dict[str, str | None] = {}
    if previous is not None:
        known.update(_filled(previous.details))
    if original is not None and original.standardRequest is not None:
        known.update(_filled(original.standardRequest.details))
    known.update({name: quote for name, (_, quote) in grounded.items()})
    return known


def _filled(details: RequestDetails) -> dict[str, str]:
    """상세 값 중 비어 있지 않은 문자열만 필드 이름으로 모은다."""
    values = {name: getattr(details, name) for name in DETAIL_FIELDS}
    return {
        name: value for name, value in values.items() if isinstance(value, str) and value.strip()
    }


def _kept_criteria(original: InputProblem | None) -> list:
    """수용 조건은 서버가 확인한 것만 그대로 보존한다. 모델이 새로 만들지 않는다."""
    if original is None or original.standardRequest is None:
        return []
    return list(original.standardRequest.details.acceptanceCriteria)


def _fallback_summary(grounded: Mapping[str, tuple[str, str]]) -> str:
    for _, quote in grounded.values():
        return quote
    return "문의 내용 확인 필요"
