"""평가 하네스의 검색 힌트 기록: 모델 원본과 정리 결과를 함께 남기고 저장·복원·요약한다.

Fake 모델만 쓴다. 실제 모델이 쓸 만한 키워드를 내는지는 이 테스트가 검증하지 않는다.
"""

import json

from evaluation.harness.recorders import CallLog, RecordingAgent, RecordingLLM
from evaluation.harness.trace import AnalysisRecord, RoleCall
from evaluation.harness.trace_io import trace_from_dict, trace_to_dict
from evaluation.pilot import _turn_summary
from hops_ai.agents.inquiry import ExtractionInquiryAgent
from hops_ai.agents.inquiry.extraction import InquiryExtraction
from hops_ai.contracts.domain import AgentRole
from hops_ai.services.tools.disabled import DisabledToolGateway
from hops_ai.settings import Settings
from hops_ai.supervisor.bootstrap import create_run_services
from hops_ai.supervisor.execution import RoleRequest
from tests.doubles.llm import FakeLLMClient
from tests.eval_fixtures import run_trace, turn
from tests.supervisor_fixtures import reserve, running_state, uid
from tests.unit.test_inquiry_agent import extracted
from tests.unit.test_problem_analysis import VIEW, request_with_features

RECORD = AnalysisRecord(
    problem_ref="new:1",
    keywords=("결재", "문서"),
    feature_candidates=(VIEW,),
    raw_keywords=("결재", "삭제", "문서"),
    raw_feature_candidates=(VIEW, uid(999)),
)


async def record_inquiry(*problems) -> CallLog:
    request = request_with_features()
    log = CallLog()
    services = create_run_services(
        request,
        settings=Settings(_env_file=None),
        access_scope_ref="test-scope:hints",
        llm=RecordingLLM(FakeLLMClient(InquiryExtraction(problems=list(problems))), log),
        tools=DisabledToolGateway(),
    )
    state = reserve(running_state(request), AgentRole.INQUIRY, "inv-1")
    await RecordingAgent(ExtractionInquiryAgent(), log).run(RoleRequest("inv-1", state), services)
    return log


async def test_recorder_keeps_the_model_original_next_to_the_cleaned_hints() -> None:
    log = await record_inquiry(
        extracted(
            keywords=["결재", "삭제", "문서"],
            featureCandidates=[VIEW, uid(999)],
        )
    )

    [call] = log.role_calls
    [record] = call.analysis
    assert record == RECORD
    assert log.raw_hints == [], "짝지은 원본은 꺼내서 다음 호출과 섞이지 않는다"


async def test_recorder_pairs_each_problem_with_its_own_original() -> None:
    log = await record_inquiry(
        extracted(keywords=["결재"], featureCandidates=[VIEW]),
        extracted(keywords=["문서", "삭제"], featureCandidates=[]),
    )

    [call] = log.role_calls
    assert [(r.problem_ref, r.raw_keywords) for r in call.analysis] == [
        ("new:1", ("결재",)),
        ("new:2", ("문서", "삭제")),
    ]


def test_calls_of_other_roles_carry_no_analysis() -> None:
    assert RoleCall(AgentRole.KNOWLEDGE).analysis == ()


def test_analysis_survives_a_trace_round_trip() -> None:
    request = request_with_features()
    trace = run_trace(turn(request, None, calls=[RoleCall(AgentRole.INQUIRY, analysis=(RECORD,))]))

    restored = trace_from_dict(json.loads(json.dumps(trace_to_dict(trace), ensure_ascii=False)))

    assert restored == trace


def test_a_trace_saved_before_hints_were_recorded_still_loads() -> None:
    request = request_with_features()
    data = trace_to_dict(run_trace(turn(request, None, calls=[RoleCall(AgentRole.INQUIRY)])))
    for item in data["turns"]:
        for call in item["role_calls"]:
            del call["analysis"]

    restored = trace_from_dict(data)

    assert restored.turns[0].role_calls[0].analysis == ()


def test_pilot_summary_shows_kept_and_dropped_hints_with_feature_names() -> None:
    request = request_with_features()
    summary = _turn_summary(
        1, turn(request, None, calls=[RoleCall(AgentRole.INQUIRY, analysis=(RECORD,))])
    )

    assert summary["searchHints"] == [
        {
            "problemRef": "new:1",
            "keywords": ["결재", "문서"],
            "droppedKeywords": ["삭제"],
            "features": ["문서 조회"],
            "droppedFeatures": [uid(999)],
        }
    ]


def test_pilot_summary_has_no_hints_section_without_any() -> None:
    summary = _turn_summary(1, turn(request_with_features(), None))

    assert "searchHints" not in summary
