"""Supervisor가 호출할 수 있는 역할을 정한다. 기준: 에이전트 계약 S4."""

from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.execution import RunKind
from hops_ai.harness.errors import InputRejected, OutputRejected


def get_allowed_roles(run_kind: RunKind) -> set[AgentRole]:
    """실행 종류에 맞는 에이전트 역할 목록을 반환한다.

    상담에서는 Inquiry·Knowledge·Ticket을 사용할 수 있다.
    사례 초안 생성에서는 Curator와 근거 검색을 위한 Knowledge를 사용할 수 있다.
    이 목록은 선택 가능한 범위이며, 모든 역할을 순서대로 호출하라는 뜻은 아니다.

    Args:
        run_kind: 입력 검증을 마친 실행 종류. RunKind 값으로 전달한다.

    Returns:
        허용된 역할의 집합. 매번 새 집합을 만들어 반환하므로, 반환값을 수정해도
        다음 호출에서 사용하는 허용 목록은 바뀌지 않는다.

    Raises:
        InputRejected: RunKind가 아닌 값이거나 지원하지 않는 실행 종류인 경우.

    실제 호출 전에는 권한과 남은 실행 예산도 별도로 검사해야 한다.
    """
    # 일반 문자열을 잘못 전달한 경우에는 역할을 선택하지 않고 오류를 낸다.
    if not isinstance(run_kind, RunKind):
        raise InputRejected("실행 종류는 RunKind로 전달해야 한다")

    if run_kind == RunKind.INQUIRY_ANALYSIS:
        return {AgentRole.INQUIRY, AgentRole.KNOWLEDGE, AgentRole.TICKET}

    if run_kind == RunKind.CASE_DRAFT:
        return {AgentRole.CURATOR, AgentRole.KNOWLEDGE}

    # 새 실행 종류가 추가돼도 허용할 역할을 정하기 전에는 실행하지 않는다.
    raise InputRejected("지원하지 않는 실행 종류다")


def validate_role_for_run(run_kind: RunKind, role: AgentRole) -> None:
    """선택한 역할이 이번 실행에서 허용되는지 검사한다.

    Args:
        run_kind: 최초 입력에서 검증한 실행 종류.
        role: Supervisor가 선택한 전문 역할.

    Raises:
        InputRejected: 실행 종류가 유효하지 않은 경우.
        OutputRejected: 역할 타입이 잘못됐거나 해당 실행에서 허용되지 않는 경우.

    이 검사는 역할 범위만 확인한다. 실행기는 호출 전에 상태·권한·예산도 검사해야 한다.
    """
    allowed_roles = get_allowed_roles(run_kind)
    if not isinstance(role, AgentRole) or role not in allowed_roles:
        raise OutputRejected("이번 실행에서 허용되지 않는 역할이다")
