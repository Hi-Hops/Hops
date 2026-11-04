"""모델은 제목·책임 후보만 판단하고, 표준 요청의 사실과 출처는 코드가 보존한다."""

import json
from typing import TYPE_CHECKING

from pydantic import ValidationError

from hops_ai.agents.ticket.agent import TICKETABLE_TYPES
from hops_ai.agents.ticket.proposal import (
    MAX_OUTPUT_TOKENS,
    PROMPT_VERSION,
    SYSTEM_PROMPT,
    TicketDecisions,
)
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import TicketResult
from hops_ai.contracts.wire import ProblemProposal, StandardRequestBody, TicketProposal
from hops_ai.harness.errors import OutputRejected
from hops_ai.services.llm.client import LLMMessage, LLMRequest
from hops_ai.services.shared import SharedServices

if TYPE_CHECKING:
    from hops_ai.supervisor.execution import RoleRequest
    from hops_ai.supervisor.state import SupervisorState


class ModelTicketAgent:
    role = AgentRole.TICKET

    @staticmethod
    def eligible_problems(state: "SupervisorState") -> dict[str, ProblemProposal]:
        """실행과 Supervisor 선택지가 동일한 문제별 처리 가능 조건을 사용한다."""
        from hops_ai.supervisor.validation import get_problem_progress, problems_without_guide

        pending = {p["problemRef"] for p in get_problem_progress(state) if p["outcome"] is None}
        no_guide = problems_without_guide(state)
        return {
            ref: p
            for ref, p in state["problems"].items()
            if ref in pending
            and p.reportedSystemCandidateId is not None
            and (
                p.processingType in TICKETABLE_TYPES
                or (p.processingType == "KNOWLEDGE_QA" and ref in no_guide)
            )
        }

    def available_for(self, state: "SupervisorState") -> bool:
        return bool(self.eligible_problems(state))

    async def run(self, call: "RoleRequest", services: SharedServices) -> TicketResult:
        # registry의 순환 import를 피하고 동일한 Supervisor 검증을 재사용한다.
        from hops_ai.supervisor.merge import validate_ticket
        from hops_ai.supervisor.validation import (
            get_problem_knowledge,
            get_problem_message_ids,
            validate_evidence_scope,
        )

        services.runtime.ensure_active()
        state = call.state
        problems = self.eligible_problems(state)
        if not problems:
            raise OutputRejected("티켓으로 제안할 수 있는 문제가 없다")

        # 병합된 근거만 사용하며 모델 입력 전 접근 범위를 다시 검사한다.
        knowledge_by_problem = {ref: get_problem_knowledge(state, ref) for ref in problems}
        contexts = {}
        for ref, problem in problems.items():
            allowed = get_problem_message_ids(state, ref)
            for item in knowledge_by_problem[ref].values():
                validate_evidence_scope(state["input"], item)
            contexts[ref] = {
                "problem": problem.model_dump(mode="json"),
                "knowledge": [
                    item.model_dump(mode="json") for item in knowledge_by_problem[ref].values()
                ],
                "messages": [
                    {"id": m.id, "content": m.content}
                    for m in state["input"].messages
                    if m.id in allowed and m.sender == "USER"
                ],
            }
        response = await services.llm.generate(
            LLMRequest(
                messages=[
                    LLMMessage("system", SYSTEM_PROMPT),
                    LLMMessage(
                        "user",
                        json.dumps(
                            {
                                "systems": [
                                    s.model_dump(mode="json") for s in state["input"].systems
                                ],
                                "problems": list(contexts.values()),
                            },
                            ensure_ascii=False,
                        ),
                    ),
                ],
                response_model=TicketDecisions,
                max_output_tokens=MAX_OUTPUT_TOKENS,
                prompt_version=PROMPT_VERSION,
            )
        )
        services.runtime.ensure_active()
        try:
            decisions = TicketDecisions.model_validate(response.parsed.model_dump(mode="json"))
        except (AttributeError, ValidationError):
            raise OutputRejected("Ticket 모델 출력 형식이 계약과 다르다") from None
        refs = [d.problemRef for d in decisions.decisions]
        if len(refs) != len(set(refs)) or set(refs) != set(problems):
            raise OutputRejected("Ticket 결과의 대상 문제가 누락·중복되거나 입력과 다르다")
        systems = {s.id for s in state["input"].systems}
        proposals = []
        for decision in decisions.decisions:
            problem = problems[decision.problemRef]
            knowledge = knowledge_by_problem[decision.problemRef]
            if decision.responsibleSystemCandidateId not in systems:
                raise OutputRejected("책임 시스템이 지원 목록에 없다")
            messages = {m["id"]: m["content"] for m in contexts[decision.problemRef]["messages"]}
            sources = {**messages, **{key: item.excerpt for key, item in knowledge.items()}}
            ids = [support.sourceId for support in decision.supports]
            if len(ids) != len(set(ids)):
                raise OutputRejected("책임 근거가 중복됐다")
            for support in decision.supports:
                if (
                    support.sourceId not in sources
                    or support.quote not in sources[support.sourceId]
                ):
                    raise OutputRejected("책임 판단의 인용이 허용된 원문에 없다")
            selected = decision.knowledgeEvidenceIds
            if len(selected) != len(set(selected)) or not set(selected) <= knowledge.keys():
                raise OutputRejected("관련 지식 참조가 중복되거나 허용되지 않았다")
            # 근거 없는 다른 책임 후보는 최초 조사할 보고 시스템으로 되돌린다.
            system_id = (
                decision.responsibleSystemCandidateId if ids else problem.reportedSystemCandidateId
            )
            required = {
                evidence_id
                for fact in [*problem.facts, *problem.assumptions]
                for evidence_id in fact.knowledgeEvidenceIds
            }
            required.update(source_id for source_id in ids if source_id in knowledge)
            required.update(selected)
            original = next(
                (p for p in state["input"].problems if p.id == decision.problemRef), None
            )
            if original is not None and original.standardRequest is not None:
                required.update(item.id for item in original.standardRequest.knowledge)
            if not required <= knowledge.keys():
                raise OutputRejected("표준 요청의 지식 출처를 확인할 수 없다")
            proposal = TicketProposal(
                problemRef=problem.problemRef,
                title=decision.title,
                processingType=(
                    "REVIEW_REQUIRED"
                    if problem.processingType == "KNOWLEDGE_QA"
                    else problem.processingType
                ),
                responsibleSystemCandidateId=system_id,
                evidenceIds=ids,
                standardRequestSnapshot=StandardRequestBody(
                    summary=problem.summary,
                    confirmedFacts=problem.facts,
                    assumptions=problem.assumptions,
                    unknowns=problem.unknowns,
                    responsibleSystemCandidateId=system_id,
                    responsibilityEvidenceIds=ids,
                    details=problem.details,
                    knowledge=[item for key, item in knowledge.items() if key in required],
                ),
            )
            validate_ticket(state, proposal)
            proposals.append(proposal.model_copy(deep=True))
        return TicketResult(
            invocationId=call.invocation_id,
            proposals=proposals,
            messageQuotes={
                d.problemRef: [s.model_dump() for s in d.supports] for d in decisions.decisions
            },
        )
