"""합성 비밀값의 탐지·겹친 범위 마스킹. 실제 자격 증명은 사용하지 않는다."""

import pytest

from hops_ai.harness.guardrails.sensitive_data import (
    SecretKind,
    detect_secret_patterns,
    redact_for_log,
)


@pytest.mark.parametrize(
    ("text", "kind"),
    [
        ("sk-" + "a" * 24, SecretKind.OPENAI_KEY),
        ("sk-proj-" + "b" * 40, SecretKind.OPENAI_KEY),
        ("sk-svcacct-" + "c" * 40, SecretKind.OPENAI_KEY),
        ("bEaReR " + "d" * 24, SecretKind.BEARER_TOKEN),
        ("eyJ" + "a" * 10 + "." + "b" * 16 + "." + "c" * 16, SecretKind.JWT),
        ("-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----", SecretKind.PRIVATE_KEY),
        ("-----BEGIN RSA PRIVATE KEY-----\nfake", SecretKind.PRIVATE_KEY),
        ("-----BEGIN OPENSSH PRIVATE KEY-----\nfake", SecretKind.PRIVATE_KEY),
        ("-----BEGIN ENCRYPTED PRIVATE KEY-----\nfake", SecretKind.PRIVATE_KEY),
    ],
)
def test_known_patterns_are_found_without_retaining_the_value(text, kind):
    [finding] = detect_secret_patterns(text)

    assert finding.kind == kind
    assert (finding.start, finding.end) == (0, len(text))
    assert text not in repr(finding)
    assert redact_for_log("앞 " + text + " 뒤") == (
        "앞 [REDACTED]"
        if text.startswith("-----BEGIN") and "-----END" not in text
        else "앞 [REDACTED] 뒤"
    )


def test_overlapping_bearer_and_key_are_redacted_once():
    secret = "Bearer sk-proj-" + "x" * 30

    assert len(detect_secret_patterns(secret)) == 2
    assert redact_for_log(secret) == "[REDACTED]"


def test_multiple_secrets_keep_only_the_surrounding_text():
    text = "첫 " + "sk-" + "a" * 24 + " 둘 Bearer " + "b" * 24 + " 끝"

    assert redact_for_log(text) == "첫 [REDACTED] 둘 [REDACTED] 끝"
    assert redact_for_log(redact_for_log(text)) == redact_for_log(text)


@pytest.mark.parametrize(
    "text",
    [
        "",
        "비밀번호를 재설정하는 방법을 안내합니다.",
        "API 키를 채팅에 입력하지 마세요.",
        "Authorization: Bearer <token>",
        "Authorization: Bearer ${TOKEN}",
        "sk-...",
        "문서 조회 메뉴를 열어 주세요. https://example.com/help",
        "-----BEGIN CERTIFICATE-----\npublic\n-----END CERTIFICATE-----",
    ],
)
def test_normal_guidance_and_placeholders_are_unchanged(text):
    assert detect_secret_patterns(text) == []
    assert redact_for_log(text) == text
