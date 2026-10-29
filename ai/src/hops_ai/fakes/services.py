"""Fake 모드의 서비스 묶음. 외부 연결 없이 가짜 LLM·검색·도구를 쓴다. 품질 검증용이 아니다."""

from hops_ai.fakes.llm import FakeLLMClient
from hops_ai.fakes.retrieval import FakeRetriever
from hops_ai.fakes.tools import FakeToolGateway
from hops_ai.harness.context import RunContext
from hops_ai.services.llm.budgeted import BudgetedLLMClient
from hops_ai.services.llm.usage import UsageRecorder
from hops_ai.services.shared import SharedServices
from hops_ai.services.tools.guarded import GuardedToolGateway
from hops_ai.services.tools.registry import ToolRegistry


def build_fake_services(runtime: RunContext, usage: UsageRecorder) -> SharedServices:
    """실행마다 새로 만든다. 가짜 에이전트는 모델을 부르지 않아 가짜 LLM에 준비된 응답이 없다."""
    return SharedServices(
        llm=BudgetedLLMClient(FakeLLMClient(), runtime.budget, usage),
        retriever=FakeRetriever(),
        tools=GuardedToolGateway(FakeToolGateway(), ToolRegistry(), runtime.tool_policy),
        runtime=runtime,
        usage=usage,
    )
