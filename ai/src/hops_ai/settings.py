"""골격 모드와 공통 실행 예산. 역할별 모델·서비스 인증은 연결 단계에서 설정한다."""

import base64
import binascii
from enum import StrEnum

from psycopg import ProgrammingError
from psycopg.conninfo import conninfo_to_dict
from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class RunMode(StrEnum):
    SCAFFOLD = "scaffold"
    REAL = "real"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="HOPS_AI_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="forbid",
        frozen=True,
        populate_by_name=True,
        hide_input_in_errors=True,
    )

    mode: RunMode = RunMode.SCAFFOLD
    # 별도 활성화한 내부 API만 서비스 토큰과 AI 전용 DB를 요구한다.
    internal_api_enabled: bool = False
    runtime_dsn: SecretStr | None = None
    ai_service_token: SecretStr | None = Field(default=None, validation_alias="AI_SERVICE_TOKEN")
    # .env의 허용 키만 선언한다. 모델 호출·추적 클라이언트 연결은 별도 구현이다.
    openai_api_key: SecretStr | None = Field(default=None, validation_alias="OPENAI_API_KEY")
    langfuse_public_key: SecretStr | None = Field(
        default=None, validation_alias="LANGFUSE_PUBLIC_KEY"
    )
    langfuse_secret_key: SecretStr | None = Field(
        default=None, validation_alias="LANGFUSE_SECRET_KEY"
    )
    langfuse_base_url: str | None = Field(default=None, validation_alias="LANGFUSE_BASE_URL")
    # 초기 기술 기본값이며 실제 모델·비용 측정 후 검토한다.
    run_deadline_seconds: int = Field(default=60, gt=0)
    llm_call_timeout_seconds: int = Field(default=30, gt=0)
    max_llm_calls: int = Field(default=8, ge=1)
    # LLM 일시 오류에만 적용. 업무 저장·색인 반영 자동 재시도 설정이 아니다.
    max_auto_retries: int = Field(default=2, ge=0)
    max_output_tokens: int = Field(default=1500, gt=0)
    # Spring이 요청한 실행 한도의 서버 상한. 초과 요청은 접수하지 않는다. 실측 후 검토한다.
    max_run_input_tokens: int = Field(default=60000, gt=0)
    max_tool_calls: int = Field(default=10, gt=0)
    # 한 인스턴스가 동시에 실행하는 run 수. 넘으면 429와 Retry-After로 응답한다.
    max_concurrent_runs: int = Field(default=4, gt=0)

    @field_validator(
        "runtime_dsn",
        "ai_service_token",
        "openai_api_key",
        "langfuse_public_key",
        "langfuse_secret_key",
        "langfuse_base_url",
        mode="before",
    )
    @classmethod
    def _empty_optional_setting(cls, value: object) -> object:
        return None if value == "" else value

    @model_validator(mode="after")
    def _call_timeout_fits_in_deadline(self) -> "Settings":
        if self.llm_call_timeout_seconds > self.run_deadline_seconds:
            raise ValueError("llm_call_timeout_seconds는 run_deadline_seconds보다 클 수 없다")
        return self

    @model_validator(mode="after")
    def _internal_api_configuration(self) -> "Settings":
        if not self.internal_api_enabled:
            return self
        token = self.ai_service_token.get_secret_value() if self.ai_service_token else ""
        try:
            encoded = token.encode("ascii")
            decoded = base64.b64decode(
                encoded + b"=" * (-len(encoded) % 4), altchars=b"-_", validate=True
            )
            if base64.urlsafe_b64encode(decoded).rstrip(b"=") != encoded.rstrip(b"="):
                raise ValueError
        except (ValueError, UnicodeEncodeError, binascii.Error):
            raise ValueError("AI_SERVICE_TOKEN은 유효한 base64url 형식이어야 합니다") from None
        if len(decoded) < 32:
            raise ValueError("AI_SERVICE_TOKEN은 인코딩 전 최소 32바이트여야 합니다")

        dsn = self.runtime_dsn.get_secret_value() if self.runtime_dsn else ""
        try:
            dbname = conninfo_to_dict(dsn).get("dbname") if dsn else None
        except (ProgrammingError, ValueError):
            # DSN 원문과 드라이버 오류를 외부에 노출하지 않는다.
            raise ValueError("AI 런타임 DB 연결 설정이 올바르지 않습니다") from None
        if dbname != "hops_ai_runtime":
            raise ValueError("AI 런타임 DB는 hops_ai_runtime이어야 합니다")
        return self
