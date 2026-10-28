"""에이전트가 받는 공통 서비스 묶음. 구현체를 Fake로 바꿔도 에이전트 코드는 그대로다.

에이전트는 필요한 서비스만 쓰며 이 묶음을 가졌다고 모든 도구의 실행 권한이 있는 것은 아니다.
도구 권한은 ToolPolicy와 Spring이 판단한다.
"""

from dataclasses import dataclass

from hops_ai.services.llm.client import LLMClient
from hops_ai.services.retrieval.base import Retriever
from hops_ai.services.tools.base import ToolGateway


@dataclass(frozen=True)
class SharedServices:
    llm: LLMClient
    retriever: Retriever
    tools: ToolGateway
