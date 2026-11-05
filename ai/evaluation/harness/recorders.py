"""런타임 코드를 바꾸지 않고 역할 호출과 모델 호출을 기록하는 래퍼."""

from pydantic import BaseModel

from evaluation.harness.budget import CallCapExceeded
from evaluation.harness.trace import RoleCall
from hops_ai.agents.base import Agent
from hops_ai.contracts.domain import AgentRole
from hops_ai.contracts.roles import KnowledgeResult
from hops_ai.harness.errors import RetrievalUnavailable
from hops_ai.services.llm.client import LLMClient, LLMRequest, LLMResponse
from hops_ai.supervisor.execution import RoleResponse


class CallLog:
    """한 번의 실행(run) 동안 쌓이는 기록."""

    def __init__(self) -> None:
        self.role_calls: list[RoleCall] = []
        self.model_calls = 0
        self.input_tokens = 0
        self.output_tokens = 0


class RecordingLLM:
    """모델 호출 횟수와 공급자가 보고한 토큰을 센다. 재시도도 별도 호출로 센다."""

    def __init__(self, inner: LLMClient, log: CallLog) -> None:
        self._inner = inner
        self._log = log

    async def generate[T: BaseModel](self, request: LLMRequest[T]) -> LLMResponse[T]:
        self._log.model_calls += 1
        try:
            response = await self._inner.generate(request)
        except CallCapExceeded:
            # 평가 상한이 막은 요청은 모델에 전달되지 않았으므로 호출로 세지 않는다
            self._log.model_calls -= 1
            raise
        usage = getattr(response, "usage", None)
        self._log.input_tokens += getattr(usage, "input_tokens", 0)
        self._log.output_tokens += getattr(usage, "output_tokens", 0)
        return response


class RecordingAgent:
    """역할 호출의 결과(검색 상태·근거)를 기록하고 결과는 그대로 돌려준다.

    `available_for` 같은 역할의 다른 속성은 원래 역할에 위임한다. 그래야 Supervisor의 선택지
    판단이 기록 래퍼 때문에 달라지지 않는다.
    """

    def __init__(self, inner: Agent, log: CallLog) -> None:
        self._inner = inner
        self._log = log
        self.role: AgentRole = inner.role

    def __getattr__(self, name: str):
        return getattr(self._inner, name)

    async def run(self, request, services):
        try:
            response = await self._inner.run(request, services)
        except RetrievalUnavailable:
            self._log.role_calls.append(
                RoleCall(self.role, search_state="ERROR", failed_with="RetrievalUnavailable")
            )
            raise
        except Exception as exc:
            self._log.role_calls.append(RoleCall(self.role, failed_with=type(exc).__name__))
            raise
        result = response.result if isinstance(response, RoleResponse) else response
        if isinstance(result, KnowledgeResult):
            verified = response.verified_evidence if isinstance(response, RoleResponse) else ()
            self._log.role_calls.append(
                RoleCall(
                    self.role,
                    problem_ref=result.problemRef,
                    search_state=result.search.state,
                    evidence=tuple(verified) or tuple(result.search.evidence),
                )
            )
        else:
            self._log.role_calls.append(RoleCall(self.role))
        return response
