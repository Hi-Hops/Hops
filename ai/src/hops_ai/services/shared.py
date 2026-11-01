"""역할이 공유할 의존성. 실제 연결은 미구현이며 테스트 대역을 자동 주입하지 않는다."""

from dataclasses import dataclass

from hops_ai.harness.context import RunContext
from hops_ai.services.llm.client import LLMClient
from hops_ai.services.retrieval.base import GraphRetriever, VectorRetriever
from hops_ai.services.tools.base import ToolGateway


@dataclass(frozen=True)
class SharedServices:
    runtime: RunContext
    llm: LLMClient
    vector: VectorRetriever
    graph: GraphRetriever
    tools: ToolGateway
