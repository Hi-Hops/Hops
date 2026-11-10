"""차단 로그에는 고정된 어휘와 검증된 실행 ID만 남긴다. 원문·예외 인자는 받지 않는다."""

import logging
from enum import StrEnum
from uuid import UUID

from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.guardrails.sensitive_data import SecretKind

logger = logging.getLogger(__name__)


class GuardrailStage(StrEnum):
    QUESTION = "QUESTION"
    GUIDE = "GUIDE"
    FINAL_ANSWER = "FINAL_ANSWER"


def record_guardrail_event(
    *,
    stage: GuardrailStage,
    kind: SecretKind,
    run_id: str,
    role: AgentRole | None = None,
) -> None:
    """타입 힌트 외에도 허용 어휘를 확인해 자유 문자열이 로그에 유입되지 않게 한다."""
    stage_value = GuardrailStage(stage).value
    reason = SecretKind(kind).value
    role_value = AgentRole(role).value if role is not None else "SUPERVISOR"
    canonical_run_id = str(UUID(run_id))
    logger.warning(
        "guardrail_blocked rule=SEC-02 stage=%s role=%s run_id=%s reason=%s",
        stage_value,
        role_value,
        canonical_run_id,
        reason,
    )
