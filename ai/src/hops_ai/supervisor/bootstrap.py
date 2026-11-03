"""주입받은 의존성과 요청별 실행 문맥을 묶는다. 외부 연결을 자동 생성하지 않는다."""

from datetime import datetime

from hops_ai.contracts.wire import AiRunRequest
from hops_ai.harness.context import RunContext
from hops_ai.harness.errors import InputRejected
from hops_ai.harness.policy import ToolPolicy
from hops_ai.services.llm.client import LLMClient
from hops_ai.services.retrieval.base import GraphRetriever, VectorRetriever
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.base import ToolGateway
from hops_ai.settings import Settings
from hops_ai.supervisor.state import create_initial_state


def create_run_services(
    request: AiRunRequest,
    *,
    settings: Settings,
    access_scope_ref: str,
    llm: LLMClient,
    vector: VectorRetriever,
    graph: GraphRetriever,
    tools: ToolGateway,
    tool_policy: ToolPolicy | None = None,
) -> SharedServices:
    """검증한 입력에 새 실행 문맥을 만들고 명시적으로 전달된 서비스를 묶는다.

    Args:
        request: Spring 또는 개발용 실행 코드가 준비한 hops-system-1 입력.
            초기 State와 같은 방식으로 DTO 형식·참조·기존 근거의 범위를 검증한다.
        settings: 서버 실행 상한. 요청 기한과 서버 기한 중 더 이른 값을 사용한다.
        access_scope_ref: 서버가 관리하는 접근 문맥의 불투명 참조. 빈 문자열은
            허용하지 않는다. 이 문자열 자체는 권한 증명이나 사용자 JWT가 아니다.
        llm: 호출자가 선택한 원시 LLM 클라이언트. 누적 예산 wrapper는 이후
            SupervisorRuntime이 적용하므로 이 함수는 모델을 호출하거나 감싸지 않는다.
        vector: 현재 접근 범위와 공개 상태를 강제할 Vector 검색 구현체.
        graph: 같은 조건을 강제할 Neo4j 관계 검색 구현체. LangGraph 실행 그래프와 구분한다.
        tools: 등록·인자 검사를 적용한 도구 구현체. 실제 호출 시 SupervisorRuntime이
            GuardedToolGateway 연결과 문맥의 역할별 허용 정책을 검사한다.
        tool_policy: 서버가 정한 역할별 도구 허용 목록. 생략하면 기본적으로 모두 거부한다.

    Returns:
        새 RunContext와 전달된 의존성을 가진 SharedServices. 호출마다 취소 신호와
        모델 호출 사용량이 독립적이며, 외부 클라이언트 객체의 수명은 호출자가 관리한다.

    Raises:
        InputRejected: 요청 모델·참조 관계 또는 접근 문맥 참조가 잘못된 경우.
        OutputRejected: 기존 지식 스냅샷이 요청의 접근 범위를 벗어나는 경우.
        ValidationError: 요청 DTO의 필수 필드·자료형·조건부 조합이 잘못된 경우.

    이 함수는 서비스 인증·최신 권한 확인·접수·영속 중복 방지를 수행하지 않는다.
    같은 runId를 새 문맥으로 다시 실행할지 결정하는 책임은 호출 측 RunLedger에 있다.
    만료된 요청은 실행기가 TIMED_OUT 결과로 처리할 수 있도록 지난 기한을 유지한다.
    """
    if not isinstance(request, AiRunRequest):
        raise InputRejected("실행 요청 모델이 필요하다")
    if not isinstance(access_scope_ref, str) or not access_scope_ref.strip():
        raise InputRejected("접근 문맥 참조는 비어 있지 않은 문자열이어야 한다")

    # 기존 초기화 검증을 재사용하여 서비스 구성과 Supervisor의 입력 기준을 맞춘다.
    snapshot = create_initial_state(request)["input"]
    runtime = RunContext.create(
        run_id=snapshot.runId,
        inquiry_id=snapshot.inquiryId,
        inquiry_version=snapshot.inquiryVersion,
        access_scope_ref=access_scope_ref,
        settings=settings,
        deadline_at=datetime.fromisoformat(snapshot.expiresAt),
        tool_policy=tool_policy,
    )
    return SharedServices(runtime=runtime, llm=llm, vector=vector, graph=graph, tools=tools)
