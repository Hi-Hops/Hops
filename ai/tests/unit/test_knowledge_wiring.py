"""Knowledge 연결(설정→레지스트리→파이프라인)과 사용법 문의의 안내 종료 경로.

모델은 Fake이고 검색은 개발용 고정 자료다. 실제 모델의 Supervisor 판단 품질이나 검색 품질은
검증하지 않는다. Supervisor의 역할 선택 순서는 대본으로 정해 두고, 이 코드가 그 순서에서
올바르게 동작하는지만 확인한다.
"""

import pytest

from hops_ai.agents.base import PendingAgent
from hops_ai.agents.inquiry import ExtractionInquiryAgent
from hops_ai.agents.inquiry.extraction import ExtractedProblem, InquiryExtraction, QuotedValue
from hops_ai.agents.knowledge import SearchKnowledgeAgent
from hops_ai.agents.registry import build_registry
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.wire import AiRunRequest
from hops_ai.services.retrieval.factory import knowledge_source_for
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.services.runs.supervisor import SupervisorPipeline
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.disabled import DisabledToolGateway
from hops_ai.settings import KnowledgeSourceKind, Settings
from hops_ai.supervisor.bootstrap import create_run_services
from hops_ai.supervisor.choices import implemented_roles
from hops_ai.supervisor.decision import SupervisorDecision
from hops_ai.supervisor.graph import Supervisor
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import MESSAGE, SYSTEM, make_request

HOW_TO_TEXT = "전자결재 문서 조회 방법을 알려주세요"


def how_to_first_message() -> AiRunRequest:
    """문제가 아직 없는 첫 사용법 문의."""
    fields = make_request().model_dump(mode="json")
    fields["problemIds"] = []
    fields["problems"] = []
    fields["messages"][0].update(content=HOW_TO_TEXT, problemIds=[])
    return AiRunRequest.model_validate(fields)


def how_to_extraction(*, task: str | None = "문서 조회") -> InquiryExtraction:
    return InquiryExtraction(
        problems=[
            ExtractedProblem(
                summary="전자결재 문서 조회 방법 문의",
                intent="HOW_TO",
                systemCode="ELECTRONIC_APPROVAL",
                taskOrScreen=QuotedValue(messageId=MESSAGE, quote=task) if task else None,
                currentWorkflow=None,
                expectedBehavior=None,
                actualBehavior=None,
                occurredAt=None,
                requestedAccess=None,
                businessPurpose=None,
                desiredChange=None,
                unknownFields=[],
            )
        ]
    )


def decision(action: str, role: AgentRole | None = None) -> SupervisorDecision:
    return SupervisorDecision(nextAction=action, role=role, publicSummary="진행합니다.")


def services_for(request: AiRunRequest, llm: FakeLLMClient) -> SharedServices:
    return create_run_services(
        request,
        settings=Settings(_env_file=None),
        access_scope_ref=f"test-scope:{request.runId}",
        llm=llm,
        tools=DisabledToolGateway(),
    )


# --- 설정과 레지스트리 ---


def test_source_is_disconnected_unless_the_fixture_is_chosen() -> None:
    assert knowledge_source_for(Settings(_env_file=None)) is None
    chosen = Settings(_env_file=None, knowledge_source=KnowledgeSourceKind.FIXTURE)
    assert isinstance(knowledge_source_for(chosen), FixtureKnowledgeSource)


def test_runtime_registry_connects_knowledge_only_when_a_source_is_given() -> None:
    disconnected = build_registry()
    connected = build_registry(FixtureKnowledgeSource())

    assert isinstance(disconnected[AgentRole.KNOWLEDGE], PendingAgent)
    assert implemented_roles(disconnected) == {
        AgentRole.INQUIRY,
        AgentRole.TICKET,
        AgentRole.CURATOR,
    }
    assert isinstance(connected[AgentRole.KNOWLEDGE], SearchKnowledgeAgent)
    assert isinstance(connected[AgentRole.INQUIRY], ExtractionInquiryAgent)
    assert implemented_roles(connected) == {
        AgentRole.INQUIRY,
        AgentRole.KNOWLEDGE,
        AgentRole.TICKET,
        AgentRole.CURATOR,
    }


# --- 사용법 문의 전체 경로(Supervisor 대본) ---


async def test_how_to_question_ends_with_a_guide_without_asking_the_form_question() -> None:
    request = how_to_first_message()
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(),
        decision("CALL_ROLE", AgentRole.KNOWLEDGE),
        decision("PROVIDE_GUIDE"),
    )
    registry = build_registry(FixtureKnowledgeSource())

    result = await Supervisor(registry).analyze(request, services_for(request, llm))

    assert result.status == "SUCCEEDED"
    assert result.questions == [] and result.ticketProposals == []
    assert result.guide is not None
    assert "'문서 조회'를 선택합니다" in result.answer
    assert "어떻게 진행하고 계신가요" not in result.answer
    assert [item.kind for item in result.evidence] == ["GUIDE"]
    assert result.evidence[0].origin == "SYNTHETIC"
    assert result.problemProposals[0].reportedSystemCandidateId == SYSTEM
    assert len(llm.requests) == 4


async def test_unknown_task_is_asked_before_searching() -> None:
    request = how_to_first_message()
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(task=None),
        decision("ASK_USER"),
    )
    registry = build_registry(FixtureKnowledgeSource())

    result = await Supervisor(registry).analyze(request, services_for(request, llm))

    [question] = result.questions
    assert question.requestedFields == ["taskOrScreen"]
    assert result.guide is None


async def test_without_a_knowledge_source_the_existing_form_question_is_kept() -> None:
    request = how_to_first_message()
    llm = FakeLLMClient(
        decision("CALL_ROLE", AgentRole.INQUIRY),
        how_to_extraction(),
        decision("ASK_USER"),
    )

    result = await Supervisor(build_registry()).analyze(request, services_for(request, llm))

    [question] = result.questions
    assert question.requestedFields == ["currentWorkflow"]
    assert result.guide is None


async def test_pipeline_offers_knowledge_only_once_a_guidable_problem_exists() -> None:
    request = how_to_first_message()
    seen: list[list[str]] = []

    class Recorder(FakeLLMClient):
        async def generate(self, req):
            if req.response_model is SupervisorDecision:
                import json

                seen.append(json.loads(req.messages[-1].content)["allowedRoles"])
            return await super().generate(req)

    scripts = (
        (
            None,
            [decision("CALL_ROLE", AgentRole.INQUIRY), how_to_extraction(), decision("ASK_USER")],
        ),
        (
            FixtureKnowledgeSource(),
            [
                decision("CALL_ROLE", AgentRole.INQUIRY),
                how_to_extraction(),
                decision("CALL_ROLE", AgentRole.KNOWLEDGE),
                decision("PROVIDE_GUIDE"),
            ],
        ),
    )
    for source, script in scripts:
        seen.clear()
        llm = Recorder(*script)
        pipeline = SupervisorPipeline(llm, knowledge_source=source)
        runtime = services_for(request, llm).runtime

        result = await pipeline.run(request, runtime)

        assert result.status == "SUCCEEDED"
        # 문제가 아직 없는 첫 판단에서는 검색할 대상이 없어 Knowledge를 고를 수 없다.
        assert seen[0] == ["INQUIRY"]
        if source is None:
            assert all("KNOWLEDGE" not in roles for roles in seen)
        else:
            # Inquiry가 안내할 사용법 문제를 만든 뒤에는 선택지에 나타난다.
            assert seen[1] == ["INQUIRY", "KNOWLEDGE"]


@pytest.mark.parametrize("kind", list(KnowledgeSourceKind))
def test_every_setting_value_is_handled(kind) -> None:
    source = knowledge_source_for(Settings(_env_file=None, knowledge_source=kind))
    assert (source is not None) == (kind is KnowledgeSourceKind.FIXTURE)
