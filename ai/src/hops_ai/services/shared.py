"""에이전트가 받는 공통 서비스 묶음. 실행마다 만들며 구현체를 바꿔도 에이전트 코드는 그대로다.

- llm은 실행 예산 안에서 호출하는 클라이언트다(BudgetedLLMClient).
- runtime으로 남은 시간과 취소 신호를 확인하고, usage에는 이 실행의 모델 사용량이 쌓인다.
- 이 묶음을 가졌다고 모든 도구를 쓸 수 있는 것은 아니다. 권한은 ToolPolicy와 Spring이 판단한다.
"""

from dataclasses import dataclass

from hops_ai.harness.context import RunContext
from hops_ai.services.llm.client import LLMClient
from hops_ai.services.llm.usage import UsageRecorder
from hops_ai.services.retrieval.base import Retriever
from hops_ai.services.tools.base import ToolGateway


@dataclass(frozen=True)
class SharedServices:
    llm: LLMClient
    retriever: Retriever
    tools: ToolGateway
    runtime: RunContext
    usage: UsageRecorder
