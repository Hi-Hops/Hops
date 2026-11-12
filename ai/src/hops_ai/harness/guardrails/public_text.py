"""공개 텍스트의 비밀값 패턴 검사. 기존 형식·출처·권한·업무 검증은 호출 측에 둔다."""

from hops_ai.contracts.domain import AgentRole
from hops_ai.harness.errors import OutputRejected
from hops_ai.harness.guardrails.events import GuardrailStage, record_guardrail_event
from hops_ai.harness.guardrails.sensitive_data import detect_secret_patterns


def validate_public_text(
    text: str,
    *,
    stage: GuardrailStage,
    run_id: str,
    role: AgentRole | None = None,
) -> None:
    """위반을 로그에 남기고 기존 안전한 실패 경로로 전달한다. 본문은 고쳐서 반환하지 않는다."""
    findings = detect_secret_patterns(text)
    if not findings:
        return
    for kind in sorted({item.kind for item in findings}):
        record_guardrail_event(stage=stage, kind=kind, role=role, run_id=run_id)
    raise OutputRejected("공개 텍스트에 허용되지 않은 자격 증명 패턴이 있다")
