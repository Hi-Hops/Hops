"""승인 사례 digest가 Spring CanonicalJson(Jackson)과 같은 바이트인지 고정 값으로 확인한다.

같은 기대값을 Spring CanonicalJsonParityTest도 검사한다. 한쪽 규칙이 바뀌면 두 테스트가 함께 깨진다.
"""

import pytest

from hops_ai.services.indexing.digest import (
    canonical,
    case_snapshot_digest,
    request_content_digest,
)

CASE_ID = "11111111-1111-4111-8111-111111111111"
APPROVED_CASE = {
    "title": '상신 문서 "승인" 필터',
    "action": "줄1\n줄2\t탭",
    "limitations": ["제어\u0001문자", "역슬래시\\끝"],
    "appliesToSystemVersion": None,
    "caseType": "RESOLUTION",
    "entityLinks": [{"relation": "ADDRESSES", "targetId": "s-1", "evidenceIds": []}],
}


def test_canonical_json_matches_jackson() -> None:
    assert canonical(APPROVED_CASE) == (
        '{"action":"줄1\\n줄2\\t탭","appliesToSystemVersion":null,"caseType":"RESOLUTION",'
        '"entityLinks":[{"evidenceIds":[],"relation":"ADDRESSES","targetId":"s-1"}],'
        '"limitations":["제어\\u0001문자","역슬래시\\\\끝"],"title":"상신 문서 \\"승인\\" 필터"}'
    )


def test_digests_match_spring() -> None:
    assert (
        case_snapshot_digest(CASE_ID, 1, APPROVED_CASE)
        == "ba5d2807d0013a6145b3557d970f601439ffea26510a4a135d21c17432f231b7"
    )
    assert (
        request_content_digest(CASE_ID, 1, "VECTOR", APPROVED_CASE)
        == "d03dadeca08c89a35d9593bc78a445f7230ddf523137a704ef14b4caeb2c672f"
    )


def test_control_characters_use_uppercase_hex_like_jackson() -> None:
    # Python json.dumps는 \u001f처럼 소문자를 쓴다. Jackson과 다르면 digest가 어긋난다.
    assert canonical("\u001f\b\f\r") == '"\\u001F\\b\\f\\r"'


def test_floats_are_not_part_of_the_contract() -> None:
    with pytest.raises(TypeError):
        canonical({"score": 0.5})
