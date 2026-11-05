"""하네스 실행기 스모크. 역할과 Supervisor 판단을 대본으로 고정해 재생·기록·검사가 이어지는지 본다.

이 테스트는 모델을 부르지 않는다. 통과가 AI의 동작 품질을 뜻하지 않는다.
"""

from evaluation.harness.catalog import SYSTEMS
from evaluation.harness.checks import check_trace
from evaluation.harness.runner import RunConfig, run_scenario
from evaluation.harness.scenario import parse_scenario
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import InquiryResult, KnowledgeResult, SearchResult
from hops_ai.contracts.wire import GuideProposal, ProblemProposal, QuestionProposal
from hops_ai.harness.errors import RetrievalUnavailable
from hops_ai.supervisor.decision import SupervisorDecision
from hops_ai.supervisor.execution import RoleResponse
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import authorize, make_details, make_evidence
from tests.unit.test_eval_user_sim import SCENARIO

EA = SYSTEMS[0]["id"]


def decision(action: str, role: AgentRole | None = None) -> SupervisorDecision:
    return SupervisorDecision(nextAction=action, role=role, publicSummary="문의를 확인합니다")


def problem(ref: str, request, message_id: str, quote: str) -> ProblemProposal:
    return ProblemProposal(
        problemRef=ref,
        summary="지난달 결재 문서 조회",
        intent="HOW_TO",
        processingType="KNOWLEDGE_QA",
        reportedSystemCandidateId=EA,
        facts=[
            {
                "text": f"하려던 업무·화면: {quote}",
                "sourceMessageIds": [message_id],
                "knowledgeEvidenceIds": [],
            }
        ],
        assumptions=[],
        unknowns=[],
        details=make_details(),
    )


class ScriptedInquiry:
    """첫 실행에는 질문을, 사용자가 답한 뒤에는 질문 없이 문제를 완성한다."""

    role = AgentRole.INQUIRY

    async def run(self, request, services):
        data = request.state["input"]
        if not data.problems:
            first = data.messages[0]
            return InquiryResult(
                invocationId=request.invocation_id,
                problems=[problem("new:1", data, first.id, first.content)],
                questions=[
                    QuestionProposal(
                        problemRef="new:1",
                        bundleRef="b1",
                        text="어떤 업무를 하려고 하셨나요?",
                        requestedFields=["taskOrScreen"],
                    )
                ],
            )
        # 이전 확인 사실은 병합 규칙상 그대로 이어받아야 하므로 먼저 두고 새 사실을 뒤에 더한다.
        reply = data.messages[-1]
        earlier = data.problems[0].standardRequest.confirmedFacts
        updated = problem(data.problems[0].id, data, reply.id, reply.content)
        updated = updated.model_copy(update={"facts": [*earlier, *updated.facts]})
        return InquiryResult(invocationId=request.invocation_id, problems=[updated], questions=[])


class ScriptedKnowledge:
    role = AgentRole.KNOWLEDGE

    async def run(self, request, services):
        problem_id = request.state["input"].problems[0].id
        item = make_evidence(systemId=EA)
        return RoleResponse(
            KnowledgeResult(
                invocationId=request.invocation_id,
                search=SearchResult(state="FOUND", evidence=[item], error=None),
                guide=GuideProposal(
                    problemRef=problem_id,
                    messageText="문서함에서 기간을 늘려 조회해 보세요.",
                    evidenceIds=[item.id],
                ),
                problemRef=problem_id,
                queryDigest="query-1",
            ),
            authorized_refs=(authorize(item),),
            verified_evidence=(item,),
        )


class UnavailableKnowledge:
    role = AgentRole.KNOWLEDGE

    async def run(self, request, services):
        raise RetrievalUnavailable()


AGENTS = {AgentRole.INQUIRY: ScriptedInquiry(), AgentRole.KNOWLEDGE: ScriptedKnowledge()}


async def test_two_turn_scenario_is_replayed_recorded_and_checked() -> None:
    scenario = parse_scenario(SCENARIO)
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("ASK_USER"),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("PROVIDE_GUIDE"),
    )

    trace = await run_scenario(scenario, llm=llm, agents=AGENTS)

    assert len(trace.turns) == 2
    first, second = trace.turns
    assert first.result.questions and first.user_reply
    assert "전자결재 문서 조회 화면" in first.user_reply
    assert first.ref_map and first.ref_map[0][0] == "new:1"
    assert [call.role for call in first.role_calls] == [AgentRole.INQUIRY]
    assert first.model_calls == 2
    assert second.request.problems[0].questionCount == 1
    assert second.result.guide is not None
    assert [(c.role, c.search_state) for c in second.role_calls] == [
        (AgentRole.INQUIRY, None),
        (AgentRole.KNOWLEDGE, "FOUND"),
    ]
    assert second.model_calls == 3
    assert check_trace(trace) == []


async def test_search_error_is_recorded_as_error_and_the_run_fails_safely() -> None:
    scenario = parse_scenario({**SCENARIO, "unknownFields": []})
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("ASK_USER"),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
    )
    agents = {AgentRole.INQUIRY: ScriptedInquiry(), AgentRole.KNOWLEDGE: UnavailableKnowledge()}

    trace = await run_scenario(scenario, llm=llm, agents=agents)

    last = trace.turns[-1]
    assert last.result.status == "FAILED"
    assert last.result.error.code == "SEARCH_UNAVAILABLE"
    assert [(c.role, c.search_state) for c in last.role_calls][-1] == (AgentRole.KNOWLEDGE, "ERROR")
    assert check_trace(trace) == []  # 오류를 근거 없음으로 바꾸지 않고 실패로 끝났다


class RepeatingInquiry(ScriptedInquiry):
    """사용자가 답한 뒤에도 같은 필드를 다시 묻는 잘못된 역할."""

    async def run(self, request, services):
        data = request.state["input"]
        if not data.problems:
            return await super().run(request, services)
        reply = data.messages[-1]
        earlier = data.problems[0].standardRequest.confirmedFacts
        updated = problem(data.problems[0].id, data, reply.id, reply.content)
        updated = updated.model_copy(update={"facts": [*earlier, *updated.facts]})
        return InquiryResult(
            invocationId=request.invocation_id,
            problems=[updated],
            questions=[
                QuestionProposal(
                    problemRef=data.problems[0].id,
                    bundleRef="b2",
                    text="어떤 업무를 하려고 하셨나요? 다시 알려 주세요.",
                    requestedFields=["taskOrScreen"],
                )
            ],
        )


async def test_repeated_question_through_the_real_pipeline_is_caught_as_p7() -> None:
    scenario = parse_scenario({**SCENARIO, "unknownFields": []})
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("ASK_USER"),
        decision("CALL_ROLE", AgentRole.INQUIRY),
        decision("ASK_USER"),
    )

    trace = await run_scenario(
        scenario,
        llm=llm,
        agents={AgentRole.INQUIRY: RepeatingInquiry()},
        config=RunConfig(max_turns=2),
    )

    violations = check_trace(trace)
    assert [(v.property_id, v.turn) for v in violations] == [("P7", 1)]
