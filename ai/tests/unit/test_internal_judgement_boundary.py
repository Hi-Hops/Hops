"""내부 판단 경계(옵션, 기본 꺼짐)와 기본 프로필의 고정.

기본 프로필: 직원 상담의 검색·State·모델 입력에는 직원 공개 자료만 있다(SEC-09).
옵션: Spring이 서버 설정으로만 accessContext.retrievalScopes를 채울 수 있고,
그 값은 사용자에게 닿을 수 있는 범위(allowedDocumentScopes)를 넓히지 않는다.
이 파일은 AI가 옵션 값을 받아도 직원 상담의 근거 검증과 검색 범위가 그대로임을 확인한다.
옵션을 평가에 쓰는 것은 별도 기능이다.
"""

import pytest
from pydantic import ValidationError

from hops_ai.contracts.wire import AccessContext, AiRunRequest
from hops_ai.harness.errors import InputRejected, OutputRejected
from hops_ai.rules.progress import validate_evidence_scope
from hops_ai.services.retrieval.fixture_source import FixtureKnowledgeSource
from hops_ai.supervisor.state import create_initial_state
from tests.supervisor_fixtures import make_evidence, make_request
from tests.unit.test_knowledge_agent import RecordingSource, how_to_request, run_through_executor

ACTOR = "90c34564-bb4c-447e-8e43-5de9068dd666"
SYSTEM_ID = "db8da008-cbb0-43e0-a313-7168168ecaf6"


def context(**changes) -> AccessContext:
    fields = {
        "actorId": ACTOR,
        "purpose": "EMPLOYEE_HELP",
        "systemIds": [SYSTEM_ID],
        "allowedDocumentScopes": ["EMPLOYEE"],
        "policyRevision": 1,
    }
    fields.update(changes)
    return AccessContext.model_validate(fields)


def request_with_retrieval(scopes: list[str] | None, **changes) -> AiRunRequest:
    payload = make_request().model_dump(mode="json")
    payload["accessContext"]["retrievalScopes"] = scopes
    payload["accessContext"].update(changes)
    return AiRunRequest.model_validate(payload)


# ---- 와이어


def test_default_profile_has_no_retrieval_scopes() -> None:
    assert context().retrievalScopes is None
    assert make_request().accessContext.retrievalScopes is None


def test_option_profile_is_accepted_and_does_not_widen_the_user_scope() -> None:
    option = context(retrievalScopes=["EMPLOYEE", "SYSTEM_STAFF"])

    assert option.retrievalScopes == ["EMPLOYEE", "SYSTEM_STAFF"]
    assert option.allowedDocumentScopes == ["EMPLOYEE"]


@pytest.mark.parametrize(
    "scopes",
    [[], ["SYSTEM_STAFF"], ["EMPLOYEE", "EMPLOYEE"], ["EMPLOYEE", "ADMIN"]],
    ids=["empty", "drops-user-scope", "duplicate", "unknown-value"],
)
def test_inconsistent_retrieval_scopes_are_rejected(scopes: list[str]) -> None:
    with pytest.raises(ValidationError):
        context(retrievalScopes=scopes)


def test_unknown_access_fields_are_still_rejected() -> None:
    with pytest.raises(ValidationError):
        context(retrievalScope=["EMPLOYEE"])


def test_only_employee_help_runs_may_carry_retrieval_scopes() -> None:
    accepted = request_with_retrieval(["EMPLOYEE", "SYSTEM_STAFF"])
    create_initial_state(accepted)

    staff_work = request_with_retrieval(
        ["EMPLOYEE", "SYSTEM_STAFF"],
        purpose="SYSTEM_WORK",
        allowedDocumentScopes=["EMPLOYEE", "SYSTEM_STAFF"],
    )
    with pytest.raises(InputRejected):
        create_initial_state(staff_work)


def test_employee_help_still_cannot_put_staff_in_the_user_scope() -> None:
    """옵션이 있어도 사용자 범위에 담당자 자료를 넣는 입력은 거부한다."""
    payload = make_request().model_dump(mode="json")
    payload["accessContext"]["allowedDocumentScopes"] = ["EMPLOYEE", "SYSTEM_STAFF"]
    payload["accessContext"]["retrievalScopes"] = ["EMPLOYEE", "SYSTEM_STAFF"]
    with pytest.raises(InputRejected):
        create_initial_state(AiRunRequest.model_validate(payload))


# ---- 근거 검증은 사용자 범위 기준이다


def test_staff_evidence_is_rejected_in_employee_help_even_with_retrieval_scopes() -> None:
    staff = make_evidence(accessScope="SYSTEM_STAFF", kind="RUNBOOK")
    for request in (make_request(), request_with_retrieval(["EMPLOYEE", "SYSTEM_STAFF"])):
        with pytest.raises(OutputRejected):
            validate_evidence_scope(request, staff)

    # 직원 공개 근거는 두 프로필 모두 통과한다.
    employee = make_evidence()
    validate_evidence_scope(request_with_retrieval(["EMPLOYEE", "SYSTEM_STAFF"]), employee)


async def test_search_scope_and_state_stay_employee_only_with_retrieval_scopes() -> None:
    """질의 공개 범위는 allowedDocumentScopes 그대로이고 담당자 자료는 State에 못 들어온다."""
    request = how_to_request("문서 조회 방법과 미표시 점검")
    payload = request.model_dump(mode="json")
    payload["accessContext"]["retrievalScopes"] = ["EMPLOYEE", "SYSTEM_STAFF"]
    option_request = AiRunRequest.model_validate(payload)

    source = RecordingSource()
    await run_through_executor(option_request, source)
    assert source.queries[0].allowed_scopes == frozenset({"EMPLOYEE"})

    state = await run_through_executor(option_request, FixtureKnowledgeSource())
    assert {item.accessScope for item in state["evidence"].values()} == {"EMPLOYEE"}
    assert all(item.kind != "RUNBOOK" for item in state["evidence"].values())
    guide = state["guide"]
    assert guide is not None and "점검 순서" not in guide.messageText


async def test_default_profile_search_never_returns_staff_material_to_an_employee() -> None:
    request = how_to_request("문서 조회 방법과 미표시 점검")

    state = await run_through_executor(request, FixtureKnowledgeSource())

    assert state["evidence"], "직원 공개 가이드는 찾아야 한다"
    assert {item.accessScope for item in state["evidence"].values()} == {"EMPLOYEE"}
    assert "점검 순서" not in (state["guide"].messageText if state["guide"] else "")
