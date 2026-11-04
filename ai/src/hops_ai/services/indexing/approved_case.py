"""Spring 승인 스냅샷과 저장소별 요청에 쓰는 공통 식별·digest 규칙."""

import hashlib
import json
from typing import Any
from uuid import UUID


def canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def verified_case_body(request: dict[str, Any], store: str) -> dict[str, Any]:
    if request["contractVersion"] != "hops-system-1" or request["store"] != store:
        raise ValueError("요청의 계약 또는 반영 저장소가 일치하지 않습니다")
    case_id = str(UUID(request["caseId"]))
    version = request["caseVersion"]
    if type(version) is not int or version < 1:
        raise ValueError("유효한 승인 버전이 필요합니다")
    body = request["approvedCase"]
    if body["accessScope"] not in ("EMPLOYEE", "SYSTEM_STAFF"):
        raise ValueError("유효한 공개 범위가 필요합니다")
    value = {"caseId": case_id, "caseVersion": version, "approvedCase": body}
    if sha256(canonical({**value, "store": store})) != request["contentDigest"]:
        raise ValueError("승인 요청 본문 해시가 일치하지 않습니다")
    return value
