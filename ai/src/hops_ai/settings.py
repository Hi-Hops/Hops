"""골격 모드와 공통 실행 예산. 역할별 모델·서비스 인증은 연결 단계에서 설정한다."""

from enum import StrEnum

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class RunMode(StrEnum):
    SCAFFOLD = "scaffold"
    REAL = "real"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="HOPS_AI_",
        env_file=".env",
        extra="forbid",
        frozen=True,
    )

    mode: RunMode = RunMode.SCAFFOLD
    # 초기 기술 기본값이며 실제 모델·비용 측정 후 검토한다.
    run_deadline_seconds: int = Field(default=60, gt=0)
    llm_call_timeout_seconds: int = Field(default=30, gt=0)
    max_llm_calls: int = Field(default=8, ge=1)
    # LLM 일시 오류에만 적용. 업무 저장·색인 반영 자동 재시도 설정이 아니다.
    max_auto_retries: int = Field(default=2, ge=0)
    max_output_tokens: int = Field(default=1500, gt=0)

    @model_validator(mode="after")
    def _call_timeout_fits_in_deadline(self) -> "Settings":
        if self.llm_call_timeout_seconds > self.run_deadline_seconds:
            raise ValueError("llm_call_timeout_seconds는 run_deadline_seconds보다 클 수 없다")
        return self
