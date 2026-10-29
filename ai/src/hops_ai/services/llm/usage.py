"""한 실행의 모델 사용량 기록. 호출 수·토큰·사용한 모델과 프롬프트 버전을 모은다.

비용은 모델 단가가 정해진 뒤 토큰 수로 계산한다. 여기서는 토큰까지만 기록한다.
"""

from dataclasses import dataclass, field

from hops_ai.contracts.agent_1 import AgentMeta
from hops_ai.services.llm.client import LLMUsage


@dataclass(frozen=True)
class UsageSummary:
    model_calls: int  # 응답을 받은 호출 수. 실패한 시도는 예산(RunBudget)이 센다
    input_tokens: int
    output_tokens: int
    model_ids: tuple[str, ...]
    prompt_versions: tuple[str, ...]


@dataclass
class UsageRecorder:
    _calls: list[tuple[str, str, LLMUsage]] = field(default_factory=list)

    def record(self, *, model_id: str, prompt_version: str, usage: LLMUsage) -> None:
        self._calls.append((model_id, prompt_version, usage))

    def summary(self) -> UsageSummary:
        return UsageSummary(
            model_calls=len(self._calls),
            input_tokens=sum(usage.input_tokens for _, _, usage in self._calls),
            output_tokens=sum(usage.output_tokens for _, _, usage in self._calls),
            model_ids=tuple(sorted({model for model, _, _ in self._calls})),
            prompt_versions=tuple(sorted({version for _, version, _ in self._calls})),
        )

    def to_meta(self, duration_ms: int) -> AgentMeta:
        """agent-1 결과의 meta. 모델을 쓰지 않았으면 modelId·promptVersion은 null이다."""
        summary = self.summary()
        return AgentMeta(
            durationMs=duration_ms,
            modelId="+".join(summary.model_ids) or None,
            promptVersion="+".join(summary.prompt_versions) or None,
        )
