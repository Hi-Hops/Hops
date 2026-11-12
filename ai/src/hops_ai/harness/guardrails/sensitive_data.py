"""명확한 비밀값 패턴만 탐지한다. 개인정보·인젝션·권한 판정기는 아니다.

탐지 결과는 종류와 문자열 위치뿐이며 원문을 보관하지 않는다. 일반적인 비밀번호
문구나 임의 문자열은 추측해 차단하지 않는다. 가려진 값·인코딩된 값의 탐지는 보장하지 않는다.
"""

import re
from dataclasses import dataclass
from enum import StrEnum


class SecretKind(StrEnum):
    OPENAI_KEY = "OPENAI_KEY"
    BEARER_TOKEN = "BEARER_TOKEN"
    JWT = "JWT"
    PRIVATE_KEY = "PRIVATE_KEY"


@dataclass(frozen=True)
class SecretFinding:
    kind: SecretKind
    start: int
    end: int


_PATTERNS = (
    # 키 본체는 20자 이상. 문서의 sk-... 같은 표기는 탐지하지 않는다.
    (SecretKind.OPENAI_KEY, re.compile(r"(?<![\w-])sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}")),
    # HTTP 자격 증명 형태만 검사한다. <token>, ${TOKEN} 같은 자리 표시는 제외한다.
    (
        SecretKind.BEARER_TOKEN,
        re.compile(r"\bBearer[ \t]+[A-Za-z0-9._~+/-]{8,}=*", re.IGNORECASE),
    ),
    (
        SecretKind.JWT,
        re.compile(r"(?<![\w-])eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"),
    ),
    (
        SecretKind.PRIVATE_KEY,
        re.compile(
            r"-----BEGIN (?P<label>(?:(?:RSA|EC|DSA|OPENSSH|ENCRYPTED) )?PRIVATE KEY)-----"
            r"[\s\S]*?(?:-----END (?P=label)-----|\Z)"
        ),
    ),
)


def detect_secret_patterns(text: str) -> list[SecretFinding]:
    """겹치는 패턴도 반환한다. 값 자체를 결과·예외·로그에 포함하지 않는다."""
    return sorted(
        (
            SecretFinding(kind, match.start(), match.end())
            for kind, pattern in _PATTERNS
            for match in pattern.finditer(text)
        ),
        key=lambda item: (item.start, item.end, item.kind),
    )


def redact_for_log(text: str) -> str:
    """알려진 패턴의 겹친 범위를 합쳐 마스킹한다. 자유 원문 로깅을 허가하지 않는다."""
    spans: list[tuple[int, int]] = []
    for finding in detect_secret_patterns(text):
        if spans and finding.start <= spans[-1][1]:
            spans[-1] = (spans[-1][0], max(spans[-1][1], finding.end))
        else:
            spans.append((finding.start, finding.end))
    parts: list[str] = []
    cursor = 0
    for start, end in spans:
        parts.extend((text[cursor:start], "[REDACTED]"))
        cursor = end
    parts.append(text[cursor:])
    return "".join(parts)
