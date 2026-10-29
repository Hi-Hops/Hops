from datetime import UTC, datetime
from typing import get_args

import pytest

from hops_ai.api import schemas as wire
from hops_ai.api.mappers import ERROR_CODE_TO_WIRE, parse_utc, to_ai_result, to_analysis
from hops_ai.contracts.agent_1 import AgentError, AgentErrorCode, IntakeProblem, TicketProposal
from hops_ai.contracts.analysis import AnalysisFailure, AnalysisOutcome
from hops_ai.harness.errors import InputRejected, OutputRejected

RUN_ID = "00000000-0000-4000-8000-000000000301"
INQUIRY_ID = "00000000-0000-4000-8000-000000000200"


def intake_problem(action="PROPOSE_TICKETS") -> IntakeProblem:
    return IntakeProblem(
        ref="00000000-0000-4000-8000-000000000201",
        summary="VPN 접속 오류",
        category="NETWORK_ACCESS",
        questionCount=1,
        nextAction=action,
        question="더 알려 주세요." if action == "ASK_USER" else None,
        evidenceIds=["msg-603-r1"],
    )


def proposal() -> TicketProposal:
    return TicketProposal(
        ref="new:work-1",
        problemRef="00000000-0000-4000-8000-000000000201",
        workScope="VPN 연결 확인",
        title="VPN 접속 오류",
        description="가상 설명",
        category="NETWORK_ACCESS",
        departmentCode="NETWORK",
        purpose="INCIDENT",
        diagnosisSummary="가상 진단",
        handoffReason="가상 이관 사유",
        uncertainty=None,
        evidenceIds=["msg-603-r1"],
    )


# ------------------------------------------------------------------ 요청 → 분석 입력


def test_messages_keep_their_current_revision_and_the_sender_notation_is_converted(
    make_wire_request,
) -> None:
    mapped = to_analysis(make_wire_request("ai-request-tickets"))

    assert [message.sender for message in mapped.data.messages] == ["USER", "ASSISTANT", "USER"]
    assert [message.revision for message in mapped.data.messages] == [1, 1, 1]
    assert mapped.data.messages[2].messageId == "00000000-0000-4000-8000-000000000603"
    assert mapped.data.messages[2].content == "오류 코드는 809예요."


def test_existing_problems_use_their_stored_id_as_the_reference(make_wire_request) -> None:
    mapped = to_analysis(make_wire_request("ai-request-tickets"))

    [problem] = mapped.data.existing_problems
    assert problem.ref == "00000000-0000-4000-8000-000000000201"
    assert (problem.category, problem.questionCount) == ("NETWORK_ACCESS", 1)


def test_ids_and_the_run_context_come_from_the_request(make_wire_request) -> None:
    request = make_wire_request("ai-request-tickets")

    mapped = to_analysis(request)

    assert (mapped.data.run_id, mapped.data.inquiry_id, mapped.data.inquiry_version) == (
        request.runId,
        request.inquiryId,
        2,
    )
    assert mapped.actor.userId == request.actor.userId
    assert mapped.actor.roles == []
    assert mapped.access_scope_ref == "mock:owner:inquiry-200"
    assert mapped.data.resolution_targets == []


def test_departments_that_cannot_receive_tickets_are_not_offered(make_request_json) -> None:
    value = make_request_json()
    value["departments"][2]["isRoutable"] = False  # CS
    request = wire.AiRequest.model_validate(value)

    mapped = to_analysis(request)

    codes = [department.code for department in mapped.data.departments]
    assert "CS" not in codes
    assert len(codes) == len(request.departments) - 1


def test_evidence_is_carried_over(make_wire_request) -> None:
    mapped = to_analysis(make_wire_request())

    [evidence] = mapped.data.evidence
    assert (evidence.id, evidence.kind, evidence.sourceVersion) == ("msg-601-r1", "MESSAGE", "1")


def test_tool_evidence_without_an_observation_time_is_rejected(make_request_json) -> None:
    value = make_request_json()
    value["evidence"][0]["kind"] = "TOOL"
    request = wire.AiRequest.model_validate(value)  # 서비스 간 계약만으로는 통과한다

    with pytest.raises(InputRejected):
        to_analysis(request)


def test_work_items_are_carried_over(make_request_json) -> None:
    value = make_request_json()
    value["existingWorkItems"] = [
        {
            "ref": "00000000-0000-4000-8000-000000000401",
            "problemRef": "00000000-0000-4000-8000-000000000201",
            "workScope": "VPN 연결 확인",
            "status": "WAITING",
            "ticketId": None,
        }
    ]

    mapped = to_analysis(wire.AiRequest.model_validate(value))

    assert [item.workScope for item in mapped.data.existing_work_items] == ["VPN 연결 확인"]


@pytest.mark.parametrize(
    "value",
    ["2026-10-28T00:01:00Z", "2026-10-28T00:01:00.123456Z"],
)
def test_deadline_is_parsed_as_utc(value) -> None:
    parsed = parse_utc(value)

    assert parsed.tzinfo is not None
    assert parsed.utcoffset().total_seconds() == 0
    assert parsed.replace(microsecond=0) == datetime(2026, 10, 28, 0, 1, 0, tzinfo=UTC)


# ------------------------------------------------------------------ 분석 결과 → 응답


def test_a_successful_outcome_becomes_ai_success() -> None:
    outcome = AnalysisOutcome(
        answer="담당 부서에 연결할게요.",
        problems=[intake_problem()],
        ticket_proposals=[proposal()],
        evidence=[],
    )

    result = to_ai_result(RUN_ID, INQUIRY_ID, 2, outcome)

    assert isinstance(result, wire.AiSuccess)
    assert (result.runId, result.inquiryId, result.inquiryVersion) == (RUN_ID, INQUIRY_ID, 2)
    assert result.status == "OK" and result.error is None
    [problem] = result.problems
    assert (problem.action, problem.ref, problem.questionCount) == (
        "PROPOSE_TICKETS",
        "00000000-0000-4000-8000-000000000201",
        1,
    )
    assert [item.departmentCode for item in result.ticketProposals] == ["NETWORK"]
    assert wire.ai_result_adapter.validate_python(result.model_dump(mode="json")) == result


def test_a_question_keeps_its_question_text() -> None:
    outcome = AnalysisOutcome(
        answer="오류 코드를 알려 주세요.",
        problems=[intake_problem("ASK_USER")],
        ticket_proposals=[],
        evidence=[],
    )

    result = to_ai_result(RUN_ID, INQUIRY_ID, 1, outcome)

    assert result.problems[0].action == "ASK_USER"
    assert result.problems[0].question == "더 알려 주세요."


@pytest.mark.parametrize("action", ["SEARCH", "DIAGNOSE"])
def test_internal_routes_are_never_sent_outside(action) -> None:
    outcome = AnalysisOutcome(
        answer="", problems=[intake_problem(action)], ticket_proposals=[], evidence=[]
    )

    with pytest.raises(OutputRejected):
        to_ai_result(RUN_ID, INQUIRY_ID, 1, outcome)


@pytest.mark.parametrize(
    ("agent_code", "wire_code"),
    [
        ("INVALID_INPUT", "INVALID_INPUT"),
        ("INVALID_OUTPUT", "SERVICE_UNAVAILABLE"),
        ("MODEL_UNAVAILABLE", "MODEL_UNAVAILABLE"),
        ("MODEL_TIMEOUT", "MODEL_UNAVAILABLE"),
        ("RETRIEVAL_UNAVAILABLE", "SERVICE_UNAVAILABLE"),
        ("TOOL_FAILURE", "SERVICE_UNAVAILABLE"),
        ("ACCESS_DENIED", "ACCESS_DENIED"),
        ("DEADLINE_EXCEEDED", "DEADLINE_EXCEEDED"),
        ("BUDGET_EXCEEDED", "SERVICE_UNAVAILABLE"),
        ("SUPERSEDED", "INQUIRY_VERSION_CONFLICT"),
        ("INTERNAL_ERROR", "SERVICE_UNAVAILABLE"),
    ],
)
def test_error_codes_are_converted_and_status_and_retryability_are_kept(
    agent_code, wire_code
) -> None:
    error = AgentError(code=agent_code, message="안전한 문구", retryable=True)

    result = to_ai_result(RUN_ID, INQUIRY_ID, 2, AnalysisFailure(status="TIMED_OUT", error=error))

    assert isinstance(result, wire.AiFailure)
    assert result.status == "TIMED_OUT"
    assert result.error.code == wire_code
    assert result.error.message == "안전한 문구"
    assert result.error.retryable is True
    assert result.error.requestId == RUN_ID


def test_every_agent_error_code_has_a_wire_code() -> None:
    assert set(get_args(AgentErrorCode)) == set(ERROR_CODE_TO_WIRE)


def test_a_failure_marked_ok_is_reported_as_an_error() -> None:
    error = AgentError(code="INTERNAL_ERROR", message="x", retryable=False)

    result = to_ai_result(RUN_ID, INQUIRY_ID, 1, AnalysisFailure(status="OK", error=error))

    assert result.status == "ERROR"
