"""실행 종류별 허용 역할이 에이전트 계약 S4와 일치하는지 확인한다."""

import pytest

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.harness.errors import InputRejected, OutputRejected
from hops_ai.supervisor.policy import get_allowed_roles, validate_role_for_run


def test_inquiry_run_allows_consultation_roles_without_curator() -> None:
    """상담에서는 Inquiry·Knowledge·Ticket을 허용하고 Curator는 제외한다."""
    allowed_roles = get_allowed_roles(RunKind.INQUIRY_ANALYSIS)

    assert allowed_roles == {AgentRole.INQUIRY, AgentRole.KNOWLEDGE, AgentRole.TICKET}


def test_case_draft_run_allows_curator_and_knowledge_without_consultation_roles() -> None:
    """사례 초안에서는 Curator와 보조 검색을 위한 Knowledge만 허용한다."""
    allowed_roles = get_allowed_roles(RunKind.CASE_DRAFT)

    assert allowed_roles == {AgentRole.CURATOR, AgentRole.KNOWLEDGE}


def test_changing_returned_roles_does_not_change_policy() -> None:
    """호출자가 반환된 집합을 수정해도 다음 호출의 허용 목록은 바뀌지 않는다."""
    allowed_roles = get_allowed_roles(RunKind.INQUIRY_ANALYSIS)
    allowed_roles.add(AgentRole.CURATOR)

    assert AgentRole.CURATOR not in get_allowed_roles(RunKind.INQUIRY_ANALYSIS)


@pytest.mark.parametrize(
    "unvalidated_kind",
    [
        "INQUIRY_ANALYSIS",
        "CASE_DRAFT",
        "UNKNOWN",
        "inquiry_analysis",
        "",
        None,
        True,
        {},
        ["INQUIRY_ANALYSIS"],
    ],
)
def test_unvalidated_kind_is_rejected_without_a_default_route(unvalidated_kind) -> None:
    """일부러 잘못된 입력을 전달하여 어떤 역할 목록도 반환하지 않는지 확인한다."""
    with pytest.raises(InputRejected):
        get_allowed_roles(unvalidated_kind)


@pytest.mark.parametrize(
    "kind,role,allowed",
    [
        (RunKind.INQUIRY_ANALYSIS, AgentRole.INQUIRY, True),
        (RunKind.INQUIRY_ANALYSIS, AgentRole.KNOWLEDGE, True),
        (RunKind.INQUIRY_ANALYSIS, AgentRole.TICKET, True),
        (RunKind.INQUIRY_ANALYSIS, AgentRole.CURATOR, False),
        (RunKind.CASE_DRAFT, AgentRole.INQUIRY, False),
        (RunKind.CASE_DRAFT, AgentRole.KNOWLEDGE, True),
        (RunKind.CASE_DRAFT, AgentRole.TICKET, False),
        (RunKind.CASE_DRAFT, AgentRole.CURATOR, True),
    ],
)
def test_role_validation_follows_run_kind(kind, role, allowed) -> None:
    """역할 수나 호출 순서를 강제하지 않고 종류별 호출 경계만 검사한다."""
    if allowed:
        validate_role_for_run(kind, role)
    else:
        with pytest.raises(OutputRejected):
            validate_role_for_run(kind, role)


def test_raw_role_string_is_not_a_validated_role() -> None:
    """일반 문자열을 검증된 역할 enum으로 간주하지 않는다."""
    with pytest.raises(OutputRejected):
        validate_role_for_run(RunKind.INQUIRY_ANALYSIS, "INQUIRY")
