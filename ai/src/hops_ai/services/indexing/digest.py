"""승인 사례 digest. Spring CanonicalJson(Jackson)과 같은 바이트로 직렬화한다.

규칙(contracts/agents I1): 키 오름차순, 공백 없음, Unicode는 escape하지 않음, 배열 순서 유지,
UTF-8 SHA-256 소문자 hex. 문자열 escape는 Jackson 기본값과 같다: `"`·`\\`와 제어 문자만 escape하고,
제어 문자 중 \\b \\t \\n \\f \\r은 짧은 표기, 나머지는 대문자 16진수 \\u00XX다.
Python json.dumps는 16진수를 소문자로 쓰므로 그대로 쓰지 않는다.
"""

import hashlib
from collections.abc import Mapping, Sequence
from typing import Any

_SHORT = {"\b": "\\b", "\t": "\\t", "\n": "\\n", "\f": "\\f", "\r": "\\r", '"': '\\"', "\\": "\\\\"}


def _string(value: str) -> str:
    out = ['"']
    for char in value:
        if char in _SHORT:
            out.append(_SHORT[char])
        elif ord(char) < 0x20:
            out.append(f"\\u{ord(char):04X}")
        else:
            out.append(char)
    out.append('"')
    return "".join(out)


def canonical(value: Any) -> str:
    """정규 JSON 문자열. 정수·문자열·불리언·null·객체·배열만 받는다(계약에 실수 없음)."""
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, str):
        return _string(value)
    if isinstance(value, Mapping):
        return (
            "{" + ",".join(f"{_string(key)}:{canonical(value[key])}" for key in sorted(value)) + "}"
        )
    if isinstance(value, Sequence):
        return "[" + ",".join(canonical(item) for item in value) + "]"
    raise TypeError(f"정규 JSON으로 만들 수 없는 값: {type(value).__name__}")


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def request_content_digest(
    case_id: str, case_version: int, store: str, approved_case: Mapping[str, Any]
) -> str:
    """저장소별 반영 요청의 contentDigest: {approvedCase, caseId, caseVersion, store}."""
    return sha256(
        canonical(
            {
                "caseId": case_id,
                "caseVersion": case_version,
                "store": store,
                "approvedCase": approved_case,
            }
        )
    )


def case_snapshot_digest(case_id: str, case_version: int, approved_case: Mapping[str, Any]) -> str:
    """승인 사례 검색 문서 버전의 content_digest: {approvedCase, caseId, caseVersion}."""
    return sha256(
        canonical({"caseId": case_id, "caseVersion": case_version, "approvedCase": approved_case})
    )
