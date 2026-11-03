"""Supervisor 판단·종료 경계의 합성 회귀 시나리오 v1.

모델이 반환할 행동을 고정하여 코드의 허용·거부 결과를 평가한다. 실제 모델의
역할 선택 정확도나 실제 검색·Spring 저장까지 포함한 E2E 품질 측정은 아니다.
"""

from copy import deepcopy

import pytest

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import InquiryResult, KnowledgeResult, SearchResult, TicketResult
from hops_ai.contracts.wire import AiRunRequest, GuideProposal, QuestionProposal
from hops_ai.harness.context import RunContext
from hops_ai.services.retrieval.neo4j import Neo4jRetriever
from hops_ai.services.retrieval.pgvector import PgVectorRetriever
from hops_ai.services.shared import SharedServices
from hops_ai.settings import Settings
from hops_ai.supervisor.decision import SupervisorDecision
from hops_ai.supervisor.execution import RoleResponse
from hops_ai.supervisor.graph import Supervisor
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import (
    OTHER_SYSTEM,
    PROBLEM,
    authorize,
    make_evidence,
    make_problem,
    make_request,
    make_ticket,
    uid,
)


class UnusedTools:
    """테스트가 실제 도구로 빠져나가면 즉시 실패시킨다."""

    async def call(self, role, request):
        raise AssertionError("판단 회귀 시나리오는 외부 도구를 호출하지 않는다")


def choice(action="FINISH", role=None, summary="문의 내용을 확인합니다"):
    """판단 품질을 가정하지 않고, 검증할 모델의 선택을 명시한다."""
    return SupervisorDecision(nextAction=action, role=role, publicSummary=summary)


async def run_scenario(request, agents, *decisions):
    """실제 Supervisor 그래프와 공용 예산 wrapper를 명시적 대역으로 실행한다."""
    llm = FakeLLMClient(*decisions)
    runtime = RunContext.create(
        run_id=request.runId,
        inquiry_id=request.inquiryId,
        inquiry_version=request.inquiryVersion,
        access_scope_ref="synthetic-routing-evaluation",
        settings=Settings(_env_file=None),
    )
    services = SharedServices(runtime, llm, PgVectorRetriever(), Neo4jRetriever(), UnusedTools())
    return await Supervisor(agents).analyze(request, services)


class QuestionInquiry:
    """첫 번째 문제의 확인 질문만 반환하여 복합 문의의 부분 처리도 재현한다."""

    role = AgentRole.INQUIRY

    async def run(self, request, services):
        return InquiryResult(
            invocationId=request.invocation_id,
            problems=[make_problem()],
            questions=[
                QuestionProposal(
                    problemRef=PROBLEM,
                    bundleRef="routing-question",
                    text="화면에 표시되는 오류 문구를 알려 주세요.",
                    requestedFields=["actualBehavior"],
                )
            ],
        )


async def test_unhandled_problem_cannot_finish_with_a_success_summary() -> None:
    """성공처럼 보이는 요약만으로 미처리 문제를 종료할 수 없다."""
    result = await run_scenario(make_request(), {}, choice(summary="문의를 처리했습니다"))

    assert result.status == "FAILED"
    assert result.error.code == "INVALID_OUTPUT"
    assert result.answer is None
    assert result.problemProposals == result.questions == result.ticketProposals == []


@pytest.mark.parametrize(
    "unsupported_claim",
    [
        "티켓이 생성되고 담당자 배정이 완료되었습니다.",
        "문제가 해결되었습니다.",
        "사례 승인이 완료되었습니다.",
    ],
)
async def test_terminal_summary_cannot_introduce_business_success(unsupported_claim) -> None:
    """정상적인 역할 경로를 거쳐도 모델 요약은 최종 업무 상태의 근거가 아니다."""
    result = await run_scenario(
        make_request(),
        {AgentRole.INQUIRY: QuestionInquiry()},
        choice("CALL_ROLE", AgentRole.INQUIRY),
        choice("ASK_USER", summary=unsupported_claim),
    )

    assert result.status == "SUCCEEDED"
    assert unsupported_claim not in result.answer
    assert result.answer.count(result.questions[0].text) == 1
    assert result.ticketProposals == []


async def test_question_for_one_problem_does_not_complete_another_problem() -> None:
    """복합 문의의 첫 문제만 준비된 경우 현재 run 전체를 성공으로 끝내지 않는다."""
    fields = make_request().model_dump(mode="json")
    second_problem = {**fields["problems"][0], "id": uid(11), "summary": "별도 확인 문제"}
    fields["problems"].append(second_problem)
    fields["problemIds"].append(uid(11))
    fields["messages"][0]["problemIds"].append(uid(11))
    request = AiRunRequest.model_validate(fields)
    result = await run_scenario(
        request,
        {AgentRole.INQUIRY: QuestionInquiry()},
        choice("CALL_ROLE", AgentRole.INQUIRY),
        choice("ASK_USER"),
    )

    assert result.status == "FAILED"
    assert result.error.code == "INVALID_OUTPUT"
    assert result.answer is None
    assert result.questions == []


async def test_investigation_ticket_preserves_unknown_cause() -> None:
    """보고 시스템이 확인되면 실제 원인이 미확인이어도 조사 제안은 가능하다."""

    class InvestigationTicket:
        role = AgentRole.TICKET

        async def run(self, request, services):
            return TicketResult(invocationId=request.invocation_id, proposals=[make_ticket()])

    result = await run_scenario(
        make_request(),
        {AgentRole.TICKET: InvestigationTicket()},
        choice("CALL_ROLE", AgentRole.TICKET),
        choice("PROPOSE_TICKETS", summary="티켓 저장과 배정이 완료되었습니다"),
    )

    assert result.status == "SUCCEEDED"
    assert result.ticketProposals[0].processingType == "INVESTIGATION"
    assert result.ticketProposals[0].standardRequestSnapshot.unknowns == ["실제 원인"]
    assert "제안" in result.answer
    assert "배정이 완료" not in result.answer


async def test_role_metadata_cannot_expand_employee_access_scope() -> None:
    """역할의 허용 참조 목록이 있어도 Spring 입력의 공개 범위를 확장하지 못한다."""

    class StaffKnowledge:
        role = AgentRole.KNOWLEDGE

        async def run(self, request, services):
            evidence = make_evidence(accessScope="SYSTEM_STAFF", excerpt="STAFF_SENTINEL")
            return RoleResponse(
                KnowledgeResult(
                    invocationId=request.invocation_id,
                    search=SearchResult(state="FOUND", evidence=[evidence], error=None),
                    guide=None,
                ),
                authorized_refs=(authorize(evidence),),
                verified_evidence=(evidence,),
            )

    request = make_request()
    original = deepcopy(request)
    result = await run_scenario(
        request,
        {AgentRole.KNOWLEDGE: StaffKnowledge()},
        choice("CALL_ROLE", AgentRole.KNOWLEDGE),
    )

    assert result.status == "FAILED"
    assert result.error.code == "INVALID_OUTPUT"
    assert result.evidence == []
    assert "STAFF_SENTINEL" not in result.model_dump_json()
    assert request == original


async def test_rewritten_excerpt_cannot_reach_a_successful_final_result() -> None:
    """문서 참조가 허용돼도 검증된 원문과 다른 발췌문은 최종 결과에 포함하지 않는다."""

    class RewritingKnowledge:
        role = AgentRole.KNOWLEDGE

        async def run(self, request, services):
            verified = make_evidence()
            changed = verified.model_copy(update={"excerpt": "REWRITTEN_EVIDENCE_SENTINEL"})
            return RoleResponse(
                KnowledgeResult(
                    invocationId=request.invocation_id,
                    search=SearchResult(state="FOUND", evidence=[changed], error=None),
                    guide=GuideProposal(
                        problemRef=PROBLEM,
                        messageText="가이드 내용을 확인해 주세요.",
                        evidenceIds=[verified.id],
                    ),
                ),
                authorized_refs=(authorize(verified),),
                verified_evidence=(verified,),
            )

    result = await run_scenario(
        make_request(),
        {AgentRole.KNOWLEDGE: RewritingKnowledge()},
        choice("CALL_ROLE", AgentRole.KNOWLEDGE),
        choice("PROVIDE_GUIDE"),
    )

    assert result.status == "FAILED"
    assert result.error.code == "INVALID_OUTPUT"
    assert result.answer is result.guide is None
    assert result.evidence == []
    assert "REWRITTEN_EVIDENCE_SENTINEL" not in result.model_dump_json()


async def test_reported_system_cannot_be_changed_to_skip_responsibility_evidence() -> None:
    """보고 시스템을 바꿔 책임 시스템의 근거 검사를 우회할 수 없다."""

    class SwitchingInquiry:
        role = AgentRole.INQUIRY

        async def run(self, request, services):
            return InquiryResult(
                invocationId=request.invocation_id,
                problems=[
                    make_problem().model_copy(update={"reportedSystemCandidateId": OTHER_SYSTEM})
                ],
                questions=[],
            )

    class UnjustifiedTicket:
        role = AgentRole.TICKET
        calls = 0

        async def run(self, request, services):
            self.calls += 1
            return TicketResult(
                invocationId=request.invocation_id,
                proposals=[make_ticket(system=OTHER_SYSTEM)],
            )

    request = make_request()
    original = request.model_copy(deep=True)
    ticket = UnjustifiedTicket()
    result = await run_scenario(
        request,
        {AgentRole.INQUIRY: SwitchingInquiry(), AgentRole.TICKET: ticket},
        choice("CALL_ROLE", AgentRole.INQUIRY),
        choice("CALL_ROLE", AgentRole.TICKET),
        choice("PROPOSE_TICKETS"),
    )

    assert result.status == "FAILED"
    assert result.error.code == "INVALID_OUTPUT"
    assert result.answer is None and result.ticketProposals == []
    assert ticket.calls == 0
    assert request == original


@pytest.mark.parametrize("role", [AgentRole.INQUIRY, AgentRole.TICKET])
async def test_another_problems_message_cannot_reach_final_facts_or_ticket(role) -> None:
    """원문이 같은 문의 안에 있어도 다른 기존 문제의 사실·티켓 출처로 복사하지 않는다."""
    fields = make_request().model_dump(mode="json")
    second_problem, second_message = uid(800), uid(801)
    fields["problemIds"] = [second_problem]
    fields["problems"].append(
        fields["problems"][0] | {"id": second_problem, "summary": "두 번째 문제"}
    )
    fields["messages"].append(
        fields["messages"][0]
        | {
            "id": second_message,
            "sequenceNo": 2,
            "problemIds": [second_problem],
            "content": "두 번째 문제의 원문",
        }
    )
    request = AiRunRequest.model_validate(fields)
    original = request.model_copy(deep=True)

    class CrossProblemAgent:
        async def run(self, request, services):
            if self.role == AgentRole.INQUIRY:
                return InquiryResult(
                    invocationId=request.invocation_id,
                    problems=[make_problem(second_problem)],
                    questions=[
                        QuestionProposal(
                            problemRef=second_problem,
                            bundleRef="second-question",
                            text="두 번째 문제의 화면을 알려 주세요.",
                            requestedFields=["taskOrScreen"],
                        )
                    ],
                )
            return TicketResult(
                invocationId=request.invocation_id, proposals=[make_ticket(second_problem)]
            )

    agent = CrossProblemAgent()
    agent.role = role
    result = await run_scenario(
        request,
        {role: agent},
        choice("CALL_ROLE", role),
        choice("ASK_USER" if role == AgentRole.INQUIRY else "PROPOSE_TICKETS"),
    )

    assert result.status == "FAILED"
    assert result.error.code == "INVALID_OUTPUT"
    assert result.answer is None
    assert result.problemProposals == result.questions == result.ticketProposals == []
    assert request == original
