"""골격 모드와 공통 실행 예산. 역할별 모델·서비스 인증은 연결 단계에서 설정한다."""

import base64
import binascii
from enum import StrEnum
from urllib.parse import urlsplit

from psycopg import ProgrammingError
from psycopg.conninfo import conninfo_to_dict
from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class RunMode(StrEnum):
    SCAFFOLD = "scaffold"
    REAL = "real"


class KnowledgeSourceKind(StrEnum):
    """Knowledge 역할이 사용할 검색 자료. 기본은 연결하지 않는다."""

    NONE = "none"
    # 개발용 고정 데이터. 검색 품질 검증이나 운영 지식으로 쓰지 않는다.
    FIXTURE = "fixture"
    # Spring 내부 읽기 API로 DB의 승인 문서를 읽는다. Spring 주소와 서비스 토큰이 필요하다.
    SPRING = "spring"


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
    # 모델과 관측 설정은 AI 서비스에서만 읽는다.
    openai_api_key: SecretStr | None = Field(default=None, validation_alias="OPENAI_API_KEY")
    # real 모드에서 Supervisor와 역할이 쓰는 OpenAI 모델.
    # 비용 최소화를 기준으로 정하고 실측 후 검토한다.
    supervisor_model: str = "gpt-6-luna"
    ticket_tools_enabled: bool = True
    spring_base_url: str | None = None
    spring_service_token: SecretStr | None = Field(
        default=None, validation_alias="SPRING_SERVICE_TOKEN"
    )
    ticket_http_timeout_seconds: float = Field(default=5, gt=0, le=30)
    ticket_max_queries: int = Field(default=4, ge=1, le=5)
    ticket_max_attempts: int = Field(default=3, ge=1, le=3)
    ticket_poll_interval_seconds: float = Field(default=0.25, ge=0, le=2)
    # none이면 Knowledge 역할은 연결 전으로 남아 Supervisor가 고르지 못한다.
    knowledge_source: KnowledgeSourceKind = KnowledgeSourceKind.NONE
    # 켜면 상담을 문제별 행동 경로로 처리한다. Knowledge가 검색 뒤에 근거의 충분성 판정을 만들고
    # (검색 결과가 있는 문제마다 모델 1회), Inquiry는 분석과 질문을 나누고, Supervisor가 문제별로
    # 합법 행동 중 하나를 고른다. 아직 실모델로 측정하기 전이라 기본은 끈다.
    knowledge_assessment: bool = False
    # Spring 지식 검색·관계 조회 요청 한 번의 제한 시간. 남은 실행 기한보다 길게 기다리지 않는다.
    knowledge_http_timeout_seconds: float = Field(default=5, gt=0, le=30)
    langfuse_public_key: SecretStr | None = Field(
        default=None, validation_alias="LANGFUSE_PUBLIC_KEY"
    )
    langfuse_secret_key: SecretStr | None = Field(
        default=None, validation_alias="LANGFUSE_SECRET_KEY"
    )
    langfuse_base_url: str | None = Field(default=None, validation_alias="LANGFUSE_BASE_URL")
    langfuse_capture_content: bool = False
    ai_port: int | None = Field(default=None, validation_alias="AI_PORT")
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
    # RunLedger 주기 작업 간격. 중단된 다른 인스턴스의 run 복구와 보존 기간이 지난 결과 상세 만료.
    ledger_recovery_interval_seconds: float = Field(default=60, gt=0)
    ledger_expiry_interval_seconds: float = Field(default=3600, gt=0)

    @field_validator("spring_base_url", "spring_service_token", mode="before")
    @classmethod
    def _blank_spring_setting_is_unset(cls, value: object) -> object:
        # Compose는 값이 없으면 빈 문자열을 넣는다. 빈 값은 "형식이 틀린 값"이 아니라
        # "설정하지 않음"이며, real 모드에서 필요한데 비어 있으면 연결 시점에 설정 누락으로 알린다.
        if isinstance(value, str) and not value.strip():
            return None
        return value

    @model_validator(mode="after")
    def _call_timeout_fits_in_deadline(self) -> "Settings":
        if self.llm_call_timeout_seconds > self.run_deadline_seconds:
            raise ValueError("llm_call_timeout_seconds는 run_deadline_seconds보다 클 수 없다")
        return self

    @model_validator(mode="after")
    def _spring_configuration(self) -> "Settings":
        if self.spring_base_url is not None:
            url = urlsplit(self.spring_base_url)
            _ = url.port  # 숫자가 아닌 port도 시작 시 거부한다.
            if (
                url.scheme not in ("http", "https")
                or not url.hostname
                or url.username is not None
                or url.password is not None
                or url.path not in ("", "/")
                or url.query
                or url.fragment
                or any(char.isspace() for char in self.spring_base_url)
            ):
                raise ValueError(
                    "Spring 주소는 인증값·경로·query 없는 고정 HTTP origin이어야 합니다"
                )
        if self.spring_service_token is not None:
            token = self.spring_service_token.get_secret_value()
            try:
                decoded = base64.b64decode(
                    token + "=" * (-len(token) % 4), altchars=b"-_", validate=True
                )
                if (
                    len(decoded) < 32
                    or base64.urlsafe_b64encode(decoded).decode().rstrip("=") != token
                ):
                    raise ValueError
            except (ValueError, binascii.Error, UnicodeError):
                raise ValueError(
                    "SPRING_SERVICE_TOKEN은 최소 32바이트 난수의 base64url이어야 합니다"
                ) from None
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
