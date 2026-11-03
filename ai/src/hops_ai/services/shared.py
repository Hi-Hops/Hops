"""호출 측이 준비한 공통 의존성. 연결의 생성·종료는 각 어댑터의 소유자가 관리한다."""

from dataclasses import dataclass

from hops_ai.harness.context import RunContext
from hops_ai.services.llm.client import LLMClient
from hops_ai.services.retrieval.base import GraphRetriever, VectorRetriever
from hops_ai.services.tools.base import ToolGateway


@dataclass(frozen=True)
class SharedServices:
    """한 run의 실행 문맥과 모델·검색·도구 인터페이스를 함께 전달한다.

    create_run_services가 요청별 RunContext를 생성하고 나머지 구현체는 그대로 주입한다.
    SupervisorRuntime은 이 객체를 복사해 누적 제한 wrapper를 적용한다.
    기본 서비스나 테스트 대역을 여기서 자동 생성하지 않는다.
    """

    runtime: RunContext
    llm: LLMClient
    vector: VectorRetriever
    graph: GraphRetriever
    tools: ToolGateway
