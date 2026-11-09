"""하네스의 판정 모드 지원. 모델은 Fake이며 재생·기록·검사가 판정 모드로 이어지는지만 본다.

이 테스트 통과는 에이전트의 판단 품질을 뜻하지 않는다. 실제 모델 측정은 별도 단계에서 한다.
"""

from evaluation.harness.corpus_source import CorpusDocument, CorpusKnowledgeSource
from evaluation.harness.recorders import CallLog, RecordingAgent, RecordingLLM
from evaluation.harness.runner import RunConfig, run_scenario
from evaluation.harness.scenario import parse_scenario
from evaluation.harness.spring_stub import DEFAULT_LIMITS, SpringStub
from evaluation.pilot import CORPUS_SOURCE_NAME, FIXTURE_SOURCE_NAME, run_metadata, run_pilot
from hops_ai.agents.inquiry.extraction import InquiryExtraction, QuotedValue
from hops_ai.agents.registry import build_registry
from hops_ai.services.runs.supervisor import SupervisorPipeline
from hops_ai.supervisor.selection import ActionSelection
from tests.doubles.llm import FakeLLMClient
from tests.supervisor_fixtures import uid
from tests.unit.test_eval_user_sim import SCENARIO
from tests.unit.test_inquiry_agent import extracted
from tests.unit.test_knowledge_assessment import draft
from tests.unit.test_problem_graph import REF, select

DOCUMENT = CorpusDocument(
    key="synthetic-guide",
    kind="GUIDE",
    system_code="ELECTRONIC_APPROVAL",
    access_scope="EMPLOYEE",
    title="결재 문서 조회 방법",
    applicability="합성 문서의 적용 범위",
    version=1,
    body="결재 문서를 조회하는 화면과 조회 기간을 설명한다.",
)
# 합성 문서의 근거 ID. 검색원이 문서 키와 버전에서 결정적으로 만든다.
DOCUMENT_EVIDENCE_ID = CorpusKnowledgeSource._hit(DOCUMENT, uid(1), 1.0).evidence.id  # noqa: SLF001


def first_message_id(scenario) -> str:
    return SpringStub(scenario).first_request().messages[0].id


def symptom_llm(scenario) -> FakeLLMClient:
    """증상 문의 → 검색 전 갈림길에서 검색 → 부족한 정보가 있는 판정 → 질문.

    부족한 정보가 있고 다시 검색할 질의가 없으면 합법 행동은 질문 하나뿐이라 모델 선택이 없다.
    """
    problem = extracted(
        actualBehavior=QuotedValue(messageId=first_message_id(scenario), quote="안 보여요")
    )
    return FakeLLMClient(
        InquiryExtraction(problems=[problem]),
        select(**{REF: "SEARCH"}),
        draft(
            verdict="PARTIAL",
            supportingEvidenceIds=[DOCUMENT_EVIDENCE_ID],
            missingInformation=[{"kind": "FIELD", "ref": "expectedBehavior"}],
        ),
    )


def judgment_agents():
    return build_registry(
        CorpusKnowledgeSource([DOCUMENT]), assess_evidence=True, defer_questions=True
    )


async def test_judgment_replay_runs_the_problem_action_path_and_records_it() -> None:
    scenario = parse_scenario(SCENARIO)
    llm = symptom_llm(scenario)

    trace = await run_scenario(
        scenario,
        llm=llm,
        agents=judgment_agents(),
        config=RunConfig(max_turns=1, judgment=True),
    )

    [first] = trace.turns
    assert first.error is None and first.result is not None
    assert first.result.status == "SUCCEEDED"
    assert [item.prompt_version for item in llm.requests] == [
        "inquiry-extraction-v10",
        "supervisor.selection.v1",
        "knowledge.assessment.v5",
    ]
    assert first.model_calls == 3
    # 분석 → 검색·판정 → 고른 질문 행동을 Inquiry가 코드로 질문으로 만든다(모델 호출 없음)
    assert [call.role.value for call in first.role_calls] == ["INQUIRY", "KNOWLEDGE", "INQUIRY"]
    [question] = first.result.questions
    assert "expectedBehavior" in question.requestedFields


async def test_judgment_report_has_behavior_findings_and_no_safety_violations() -> None:
    scenario = parse_scenario(SCENARIO)

    report = await run_pilot(
        scenario,
        llm=symptom_llm(scenario),
        agents=judgment_agents(),
        max_calls=30,
        config=RunConfig(max_turns=1, judgment=True),
    )

    assert report["safetyViolations"] == []
    assert report["behaviorFindings"] == []
    assert report["totals"]["modelCalls"] == 3


async def test_without_the_judgment_flag_injected_agents_use_the_role_level_path() -> None:
    llm = FakeLLMClient(ActionSelection(decisions={}))
    pipeline = SupervisorPipeline(RecordingLLM(llm, CallLog()), {}, problem_actions=False)

    assert pipeline._supervisor._problem_actions is False  # noqa: SLF001


def test_pipeline_problem_actions_defaults_to_the_assessment_wiring_and_can_be_forced() -> None:
    llm = FakeLLMClient()
    agents = judgment_agents()
    recorded = {role: RecordingAgent(agent, CallLog()) for role, agent in agents.items()}

    default = SupervisorPipeline(llm, recorded)
    forced = SupervisorPipeline(llm, recorded, problem_actions=True)
    wired = SupervisorPipeline(llm, knowledge_source=CorpusKnowledgeSource(), assess_evidence=True)

    assert default._supervisor._problem_actions is False  # noqa: SLF001
    assert forced._supervisor._problem_actions is True  # noqa: SLF001
    assert wired._supervisor._problem_actions is True  # noqa: SLF001


def test_run_metadata_records_the_judgment_source_and_limits_actually_used() -> None:
    default = run_metadata("m")
    judged = run_metadata(
        "m", judgment=True, corpus=True, limits={**DEFAULT_LIMITS, "maxModelCalls": 20}
    )

    assert default["judgmentMode"] is False and default["knowledgeSource"] == FIXTURE_SOURCE_NAME
    assert default["runLimits"] == DEFAULT_LIMITS
    assert judged["judgmentMode"] is True and judged["knowledgeSource"] == CORPUS_SOURCE_NAME
    assert judged["runLimits"]["maxModelCalls"] == 20
    assert "실검색 아님" in judged["knowledgeSource"]
