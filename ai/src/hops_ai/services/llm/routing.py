"""모델 호출을 프롬프트 계열별 모델로 보낸다.

모든 호출은 프롬프트 버전(예: `knowledge.assessment.v5`)을 가진다. 버전 번호를 뺀 이름이 프롬프트
계열이고, 계열마다 다른 모델을 지정할 수 있다. 지정하지 않은 계열은 기본 모델을 쓴다.
모델은 `이름` 또는 `이름:reasoning강도`(예: `gpt-6.1-sol:low`)로 쓴다. 강도를 생략하면 none이다.
에이전트 코드는 모델을 고르지 않는다. 배분은 설정(`HOPS_AI_ROLE_MODELS`)으로만 바꾼다.
"""

import re
from collections.abc import Mapping

# 실행 코드가 쓰는 프롬프트 계열. 오타로 배분이 조용히 무시되지 않도록 설정 검증에 쓴다.
PROMPT_FAMILIES = frozenset(
    {
        "supervisor.decision",
        "supervisor.selection",
        "inquiry-extraction",
        "inquiry.question",
        "knowledge.assessment",
        "knowledge.guide-excerpt",
        "ticket.proposal",
        "curator-draft",
        "curator-link",
    }
)

_VERSION_SUFFIX = re.compile(r"[.-]v\d+$")

# OpenAI reasoning 강도. 모델마다 지원 값이 다르다(예: gpt-6.1-sol은 none을 받지 않는다).
REASONING_EFFORTS = frozenset({"none", "low", "medium", "high", "xhigh", "max"})
DEFAULT_EFFORT = "none"


def parse_model(spec: str) -> tuple[str, str]:
    """`모델` 또는 `모델:강도`를 (모델, reasoning 강도)로 나눈다. 강도가 없으면 none이다."""
    name, _, effort = spec.strip().partition(":")
    name, effort = name.strip(), (effort.strip() or DEFAULT_EFFORT)
    if not name:
        raise ValueError("모델 이름이 비어 있다")
    if effort not in REASONING_EFFORTS:
        raise ValueError(f"알 수 없는 reasoning 강도: {effort}")
    return name, effort


def prompt_family(prompt_version: str) -> str:
    """프롬프트 버전에서 버전 번호를 뺀 계열 이름. 예: `curator-draft-v4` → `curator-draft`."""
    return _VERSION_SUFFIX.sub("", prompt_version)


def validate_role_models(role_models: Mapping[str, str]) -> dict[str, str]:
    """계열별 모델 배분을 검증한다. 모르는 계열이나 빈 모델 이름은 거부한다."""
    unknown = sorted(set(role_models) - PROMPT_FAMILIES)
    if unknown:
        raise ValueError(f"알 수 없는 프롬프트 계열: {', '.join(unknown)}")
    empty = sorted(family for family, model in role_models.items() if not model.strip())
    if empty:
        raise ValueError(f"모델 이름이 비어 있는 프롬프트 계열: {', '.join(empty)}")
    for spec in role_models.values():
        parse_model(spec)
    return {family: model.strip() for family, model in role_models.items()}


def model_for(prompt_version: str, default: str, role_models: Mapping[str, str]) -> tuple[str, str]:
    """그 호출에 쓸 (모델, reasoning 강도). 계열에 배분이 없으면 기본 모델이다."""
    return parse_model(role_models.get(prompt_family(prompt_version), default))
