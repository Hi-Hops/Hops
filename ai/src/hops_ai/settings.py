"""환경변수 기반 설정. 변수 이름은 HOPS_AI_ 접두사에 필드 이름을 대문자로 붙인 것이다."""

from enum import StrEnum

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class RunMode(StrEnum):
    FAKE = "fake"
    REAL = "real"


class AgentMode(StrEnum):
    SINGLE = "single"
    MULTI = "multi"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="HOPS_AI_",
        env_file=".env",
        extra="ignore",
        frozen=True,
    )

    mode: RunMode = RunMode.FAKE
    agent_mode: AgentMode = AgentMode.SINGLE

    # 실행 예산. 초기 기준값이며 실제 모델 측정 후 조정한다.
    run_deadline_seconds: int = Field(default=60, gt=0)
    llm_call_timeout_seconds: int = Field(default=30, gt=0)
    max_llm_calls_single: int = Field(default=3, ge=1)
    max_llm_calls_multi: int = Field(default=8, ge=1)
    max_auto_retries: int = Field(default=2, ge=0)
    max_output_tokens: int = Field(default=1500, gt=0)

    @model_validator(mode="after")
    def _call_timeout_fits_in_deadline(self) -> "Settings":
        if self.llm_call_timeout_seconds > self.run_deadline_seconds:
            raise ValueError("llm_call_timeout_seconds는 run_deadline_seconds보다 클 수 없다")
        return self

    @property
    def max_llm_calls(self) -> int:
        """현재 에이전트 구성에서 한 실행이 쓸 수 있는 모델 호출 상한. 자동 재시도도 포함한다."""
        if self.agent_mode is AgentMode.MULTI:
            return self.max_llm_calls_multi
        return self.max_llm_calls_single
