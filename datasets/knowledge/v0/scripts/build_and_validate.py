#!/usr/bin/env python3
"""Knowledge dataset v0의 파생 청크·manifest를 만들고 공유 데이터 불변식을 검사한다."""

from __future__ import annotations

import csv
from collections import Counter
import hashlib
import json
import re
from datetime import date
from pathlib import Path
from typing import Any

try:
    from jsonschema import Draft202012Validator, FormatChecker
    from jsonschema.exceptions import SchemaError
except ImportError as exc:
    raise SystemExit(
        "ERROR: JSON Schema 검증에 jsonschema 패키지가 필요합니다. "
        "python3 -m pip install -r datasets/knowledge/v0/requirements.txt 를 먼저 실행하세요."
    ) from exc


ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ROOT.parents[2]
RUNBOOK_DIR = ROOT / "documents" / "staff_runbooks"
POLICY_DIR = ROOT / "documents" / "policies"
RENDERED_RUNBOOK_DIR = REPO_ROOT / "docs" / "knowledge" / "runbooks"
RENDERED_GUIDE_DIR = REPO_ROOT / "docs" / "knowledge" / "guides"
RENDERED_POLICY_DIR = REPO_ROOT / "docs" / "knowledge" / "policies"
RENDERED_REFERENCE_DIR = REPO_ROOT / "docs" / "knowledge" / "references"
GUIDE_DIR = ROOT / "documents" / "employee_guides"
CATALOG_DIR = ROOT / "catalogs"
CHUNK_DIR = ROOT / "chunks"
MANIFEST_DIR = ROOT / "manifests"
SCHEMA_DIR = ROOT / "schemas"

# v0 Markdown은 기존 JSON을 그대로 표시하므로, 문서만 따로 열어도
# 새 기획에 맞춘 정책으로 오인하지 않도록 전환 상태를 함께 렌더링한다.
V0_TRANSITION_NOTICE = (
    "> **PRD 1.44 전환 전 자료:** 이 문서는 기존 v0 데이터의 자동 생성본이다. "
    "파일명의 `.v1`은 해당 문서의 버전이며 새 기획용 데이터셋 v1을 뜻하지 않는다. "
    "부서 배정·질문 한도·해결 정책 등은 현재 기획과 다를 수 있으므로 "
    "새 기획의 검색 정본으로 사용하지 않는다. "
    "[새 정책](../policies/POLICY-PRD-1.44.md) · "
    "[자료 전환 검토](../DATA_MIGRATION_REVIEW.md)\n\n"
)
V0_REFERENCE_NOTICE = (
    "> **PRD 1.44 전환 전 자료:** 이 문서는 기존 v0 카탈로그의 자동 생성본이다. "
    "후보 시스템·담당 경계 등은 새 기획에 맞게 재검토해야 하며 "
    "과거 검수만으로 새 공통 데이터로 승인된 것은 아니다. "
    "[자료 전환 검토](../DATA_MIGRATION_REVIEW.md)\n\n"
)


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def json_path(parts: Any) -> str:
    """jsonschema 오류 경로를 사람이 찾기 쉬운 JSONPath 형태로 표시한다."""
    path = "$"
    for part in parts:
        if isinstance(part, int):
            path += f"[{part}]"
        elif re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", str(part)):
            path += f".{part}"
        else:
            path += f"[{json.dumps(str(part), ensure_ascii=False)}]"
    return path


def schema_instance_errors(
    instance: Any, schema: dict[str, Any], label: str,
) -> list[str]:
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    validation_errors = sorted(
        validator.iter_errors(instance),
        key=lambda error: (tuple(str(part) for part in error.absolute_path), error.message),
    )
    return [
        f"JSON Schema 위반: {label} {json_path(error.absolute_path)}: {error.message}"
        for error in validation_errors
    ]


def validate_canonical_json_schemas() -> list[str]:
    """스키마 자체와 스키마가 있는 모든 정본 JSON/JSONL을 검증한다."""
    errors: list[str] = []
    schemas: dict[str, dict[str, Any]] = {}
    for path in sorted(SCHEMA_DIR.glob("*.schema.json")):
        try:
            schema = load_json(path)
            Draft202012Validator.check_schema(schema)
            schemas[path.name] = schema
        except (json.JSONDecodeError, SchemaError) as exc:
            errors.append(f"JSON Schema 파일 오류: {path.relative_to(ROOT)}: {exc}")

    if errors:
        return errors

    canonical_targets = [
        (RUNBOOK_DIR.glob("*.json"), "staff_runbook.schema.json"),
        (GUIDE_DIR.glob("*.json"), "employee_guide.schema.json"),
        (POLICY_DIR.glob("*.json"), "knowledge_policy.schema.json"),
    ]
    for paths, schema_name in canonical_targets:
        schema = schemas[schema_name]
        for path in sorted(paths):
            try:
                instance = load_json(path)
            except json.JSONDecodeError as exc:
                errors.append(f"정본 JSON 구문 오류: {path.relative_to(ROOT)}: {exc}")
                continue
            errors.extend(schema_instance_errors(instance, schema, path.relative_to(ROOT).as_posix()))

    catalog_targets = {
        "it_glossary.json": "it_glossary.schema.json",
        "error_signals.json": "error_signal_catalog.schema.json",
        "incident_scenarios.json": "incident_scenario_catalog.schema.json",
    }
    for file_name, schema_name in catalog_targets.items():
        path = CATALOG_DIR / file_name
        try:
            instance = load_json(path)
        except json.JSONDecodeError as exc:
            errors.append(f"정본 JSON 구문 오류: {path.relative_to(ROOT)}: {exc}")
            continue
        errors.extend(schema_instance_errors(
            instance, schemas[schema_name], path.relative_to(ROOT).as_posix(),
        ))

    section_path = MANIFEST_DIR / "general_section_access.jsonl"
    section_schema = schemas["general_section_access.schema.json"]
    for line_no, line in enumerate(section_path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line:
            continue
        label = f"{section_path.relative_to(ROOT).as_posix()}:{line_no}"
        try:
            instance = json.loads(line)
        except json.JSONDecodeError as exc:
            errors.append(f"정본 JSONL 구문 오류: {label}: {exc}")
            continue
        errors.extend(schema_instance_errors(instance, section_schema, label))
    return errors


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def dump_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


def bullet(values: list[str]) -> str:
    return "\n".join(f"- {value}" for value in values)


def unique(values: list[str]) -> list[str]:
    return list(dict.fromkeys(values))


def diagnostic_markdown(steps: list[dict[str, Any]]) -> str:
    sections = []
    for step in steps:
        tool_state = "미등록 — 실행 불가" if step.get("tool_query") is None else step["tool_query"]["tool_id"]
        sections.append(
            f"### {step['step_id']} · {step['goal']}\n\n"
            f"- 조치: {step['action']}\n"
            f"- 도구: {tool_state}\n"
            f"- 기대 신호:\n{bullet(step['expected_signals'])}\n"
            f"- 일치 시: {step['on_match']}\n"
            f"- 불일치 시: {step['on_mismatch']}\n"
            f"- 근거: {', '.join(step['evidence_ids'])}"
        )
    return "\n\n".join(sections)


def cause_signal_definitions(
    causes: list[dict[str, Any]], signal_by_id: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    """원인 가설의 signal ID를 레지스트리 정의와 결합한다."""
    definitions = []
    for cause in causes:
        for role, field in (
            ("REQUIRED", "required_signal_ids"),
            ("CONTRADICTORY", "contradictory_signal_ids"),
        ):
            for signal_id in cause[field]:
                signal = signal_by_id[signal_id]
                definitions.append({
                    "cause_id": cause["cause_id"],
                    "role": role,
                    "signal_id": signal_id,
                    "name": signal["name"],
                    "kind": signal["kind"],
                    "match": signal["match"],
                    "meaning": signal["meaning"],
                    "confirmation_limit": signal["confirmation_limit"],
                    "component_candidates": signal["component_candidates"],
                })
    return definitions


def signal_definitions_markdown(
    cause: dict[str, Any], signal_by_id: dict[str, dict[str, Any]],
) -> str:
    rows = []
    for role, field in (
        ("필요", "required_signal_ids"),
        ("반대", "contradictory_signal_ids"),
    ):
        for signal_id in cause[field]:
            signal = signal_by_id[signal_id]
            rows.append(
                f"- {signal_id} [{role}] · {signal['name']} ({signal['kind']})\n"
                f"  - 매칭: {signal['match']}\n"
                f"  - 의미: {signal['meaning']}\n"
                f"  - 확정 제한: {signal['confirmation_limit']}"
            )
    return "\n".join(rows) or "- 없음"


def causes_markdown(
    causes: list[dict[str, Any]], signal_by_id: dict[str, dict[str, Any]],
) -> str:
    return "\n\n".join(
        f"### {cause['cause_id']} · {cause['description']}\n\n"
        f"- 필요 신호 ID: {', '.join(cause['required_signal_ids'])}\n"
        f"- 필요 신호:\n{bullet(cause['required_signals'])}\n"
        f"- 반대 신호 ID: {', '.join(cause['contradictory_signal_ids']) or '없음'}\n"
        f"- 반대 신호:\n{bullet(cause['contradictory_signals'])}\n"
        f"- 담당 부서: {cause['owner_department']}\n"
        f"- 근거: {', '.join(cause['evidence_ids'])}\n\n"
        f"#### 레지스트리에서 결합된 신호 정의\n\n"
        f"{signal_definitions_markdown(cause, signal_by_id)}"
        for cause in causes
    )


def guidance_markdown(runbook: dict[str, Any]) -> str:
    sections = [
        f"### {step['step_id']} · Agent 안내\n\n"
        f"- 조건: {step['when']}\n"
        f"- 안내: {step['instruction']}\n"
        f"- 예상 결과: {step['expected_user_result']}\n"
        f"- 미해결 시: {step['if_not_resolved']}\n"
        f"- 근거: {', '.join(step['evidence_ids'])}"
        for step in runbook["guidance_steps"]
    ]
    sections.append(
        "### Agent 처리 경계\n\n"
        f"- 허용 결과: {', '.join(runbook['agent_handling']['allowed_outcomes'])}\n"
        f"- 이관 사유: {', '.join(runbook['agent_handling']['handoff_reasons'])}\n"
        f"- 금지 작업: {', '.join(runbook['agent_handling']['prohibited_actions'])}\n"
        f"- 해결 확정 근거: {', '.join(runbook['agent_handling']['resolution_confirmation_sources'])}"
    )
    return "\n\n".join(sections)


def staff_resolution_markdown(runbook: dict[str, Any]) -> str:
    sections = []
    for step in runbook["staff_resolution_steps"]:
        sections.append(
            f"### {step['step_id']} · 사람 수행\n\n"
            f"- 조치: {step['instruction']}\n"
            f"- 승인 필요: {'예' if step['approval_required'] else '아니오'}\n"
            f"- 확인: {step['verification']}\n"
            f"- 롤백: {step.get('rollback') or '해당 없음'}\n"
            f"- 근거: {', '.join(step['evidence_ids'])}"
        )
    sections.extend([
        "### 완료 판정\n\n"
        f"성공 신호:\n{bullet(runbook['verification']['success_signals'])}\n\n"
        f"실패 신호:\n{bullet(runbook['verification']['failure_signals'])}",
        "### 이관\n\n"
        f"- 대상: {runbook['escalation']['target_department']}\n"
        f"- 조건:\n{bullet(runbook['escalation']['triggers'])}\n"
        f"- 첨부 근거:\n{bullet(runbook['escalation']['evidence_to_attach'])}",
    ])
    return "\n\n".join(sections)


def overview_markdown(runbook: dict[str, Any]) -> str:
    products = ", ".join(f"{item['name']} {item['version']}" for item in runbook["applicability"]["products"])
    symptoms = [
        f"### {symptom['symptom_id']}\n\n"
        f"사용자 표현:\n{bullet(symptom['user_phrases'])}\n\n"
        f"관측 신호:\n{bullet(symptom['observable_signals'])}"
        for symptom in runbook["symptoms"]
    ]
    return (
        f"- 서비스/컴포넌트: {runbook['service']['service_id']} / {runbook['service']['component_id']}\n"
        f"- 기본 담당: {runbook['ownership']['default_owner_department']}\n"
        f"- 적용 환경: {runbook['applicability']['environment']}\n"
        f"- 제품: {products}\n"
        f"- 문서 상태/인덱싱: {runbook['status']} / {runbook['access']['ingest_status']}\n\n"
        f"## 적용 가정\n\n{bullet(runbook['applicability']['assumptions'])}\n\n"
        f"## 범위 밖\n\n{bullet(runbook['applicability']['out_of_scope'])}\n\n"
        f"## 사용자 증상\n\n" + "\n\n".join(symptoms)
    )


def response_markdown(runbook: dict[str, Any]) -> str:
    return (
        f"## 안전한 사용자 안내\n\n{runbook['user_response']['safe_summary_template']}\n\n"
        f"## 노출 금지\n\n{bullet(runbook['user_response']['prohibited_details'])}"
    )


def make_runbook_chunk(
    runbook: dict[str, Any], source_path: Path, source_hash: str, chunk_no: int,
    section_id: str, title: str, content: str,
    signal_by_id: dict[str, dict[str, Any]], signal_catalog_hash: str, *,
    steps: list[dict[str, Any]] | None = None,
    causes: list[dict[str, Any]] | None = None, guidances: list[dict[str, Any]] | None = None,
    staff_resolutions: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    steps = steps or []
    causes = causes or []
    guidances = guidances or []
    staff_resolutions = staff_resolutions or []
    signal_definitions = cause_signal_definitions(causes, signal_by_id)
    signal_ids = unique([item["signal_id"] for item in signal_definitions])
    evidence_ids = unique([
        evidence_id
        for item in [*steps, *causes, *guidances, *staff_resolutions]
        for evidence_id in item.get("evidence_ids", [])
    ])
    if section_id in {"overview", "user_response"}:
        evidence_ids = list(runbook["provenance"]["source_evidence_ids"])
    risk_flags = ["INTERNAL_ONLY"]
    if any(step.get("tool_query") is None for step in steps):
        risk_flags.append("TOOL_CONTRACT_PENDING")
    return {
        "schema_version": "staff_runbook_chunk.v0",
        "chunk_id": f"{runbook['runbook_id']}:v{runbook['version_no']}:c{chunk_no:02d}",
        "runbook_id": runbook["runbook_id"],
        "document_type": runbook["document_type"],
        "version_no": runbook["version_no"],
        "chunk_no": chunk_no,
        "section_id": section_id,
        "title": title,
        "content": content,
        "content_sha256": sha256_bytes(content.encode("utf-8")),
        "source_content_sha256": source_hash,
        "signal_catalog_sha256": signal_catalog_hash if signal_definitions else None,
        "signal_ids": signal_ids,
        "signal_definitions": signal_definitions,
        "service_id": runbook["service"]["service_id"],
        "component_id": runbook["service"]["component_id"],
        "owner_department": runbook["ownership"]["default_owner_department"],
        "access_scope": runbook["access"]["access_scope"],
        "exposure_policy": runbook["access"]["exposure_policy"],
        "ingest_status": runbook["access"]["ingest_status"],
        "content_review_status": runbook["review"]["content_status"],
        "team_review_status": runbook["review"]["team_review_status"],
        "tool_binding_status": runbook["tool_binding"]["status"],
        "scenario_validation_status": runbook["scenario_validation"]["status"],
        "step_ids": [item["step_id"] for item in steps],
        "cause_ids": [item["cause_id"] for item in causes],
        "guidance_ids": [item["step_id"] for item in guidances],
        "staff_resolution_ids": [item["step_id"] for item in staff_resolutions],
        "evidence_ids": evidence_ids,
        "risk_flags": risk_flags,
        "source_path": source_path.relative_to(ROOT).as_posix(),
    }


def guide_chunks(path: Path, guide: dict[str, Any]) -> list[dict[str, Any]]:
    source_hash = sha256_bytes(path.read_bytes())
    overview = (
        f"제목: {guide['title']}\n"
        f"적용 상황: {'; '.join(guide['applies_when'])}\n"
        f"사용자 표현: {'; '.join(guide['user_phrases'])}\n"
        f"수집 정보: {'; '.join(guide['information_to_collect'])}"
    )
    steps = "\n".join(
        f"{item['order']}. {item['instruction']} 예상 결과: {item['expected_result']}"
        for item in guide["steps"]
    )
    action = (
        f"{steps}\n"
        f"중단 및 이관 조건: {'; '.join(guide['stop_and_handoff'])}\n"
        f"하지 말아야 할 일: {'; '.join(guide['do_not_do'])}"
    )
    rows = []
    for number, section, title, content in (
        (1, "overview", "적용 상황과 수집 정보", overview),
        (2, "actions", "사용자 조치와 이관 조건", action),
    ):
        rows.append({
            "schema_version": "employee_guide_chunk.v0",
            "chunk_id": f"{guide['guide_id']}:v{guide['version_no']}:c{number:02d}",
            "document_id": guide["guide_id"],
            "version_no": guide["version_no"],
            "chunk_no": number,
            "section_id": section,
            "title": title,
            "content": content,
            "content_sha256": sha256_bytes(content.encode("utf-8")),
            "source_content_sha256": source_hash,
            "document_type": "EMPLOYEE_GUIDE",
            "service_id": guide["service_id"],
            "access_scope": guide["access"]["access_scope"],
            "exposure_policy": guide["access"]["exposure_policy"],
            "ingest_status": guide["access"]["ingest_status"],
            "content_review_status": guide["review"]["content_status"],
            "team_review_status": guide["review"]["team_review_status"],
            "evidence_ids": guide["provenance"]["source_evidence_ids"],
            "source_policy_ids": guide["provenance"].get("source_policy_ids", []),
            "related_runbook_ids": guide["related_runbook_ids"],
            "source_path": path.relative_to(ROOT).as_posix(),
        })
    return rows


def render_guide_markdown(path: Path, guide: dict[str, Any]) -> None:
    """직원 가이드 JSON 정본을 팀 검토용 Markdown으로 렌더링한다."""
    source_hash = sha256_bytes(path.read_bytes())
    steps = "\n".join(
        f"{item['order']}. {item['instruction']}\n   - 예상 결과: {item['expected_result']}"
        for item in guide["steps"]
    )
    runbooks = ", ".join(guide["related_runbook_ids"]) or "없음"
    evidence = ", ".join(guide["provenance"]["source_evidence_ids"]) or "없음"
    policies = ", ".join(guide["provenance"].get("source_policy_ids", [])) or "없음"
    markdown = (
        "---\n"
        f"guide_id: {guide['guide_id']}\n"
        f"version_no: {guide['version_no']}\n"
        f"status: {guide['status']}\n"
        f"service_id: {guide['service_id']}\n"
        f"access_scope: {guide['access']['access_scope']}\n"
        f"exposure_policy: {guide['access']['exposure_policy']}\n"
        f"ingest_status: {guide['access']['ingest_status']}\n"
        f"content_review_status: {guide['review']['content_status']}\n"
        f"team_review_status: {guide['review']['team_review_status']}\n"
        f"source_content_sha256: {source_hash}\n"
        "---\n\n"
        f"{V0_TRANSITION_NOTICE}"
        f"# {guide['title']}\n\n"
        f"## 적용 상황\n\n{bullet(guide['applies_when'])}\n\n"
        f"## 사용자 표현\n\n{bullet(guide['user_phrases'])}\n\n"
        f"## 안전한 확인 순서\n\n{steps}\n\n"
        f"## 중단 및 이관 조건\n\n{bullet(guide['stop_and_handoff'])}\n\n"
        f"## 문의에 준비할 정보\n\n{bullet(guide['information_to_collect'])}\n\n"
        f"## 하지 말아야 할 일\n\n{bullet(guide['do_not_do'])}\n\n"
        f"## 계보\n\n"
        f"- 관련 런북: {runbooks}\n"
        f"- 공개 근거: {evidence}\n"
        f"- 프로젝트 정책: {policies}\n"
    )
    RENDERED_GUIDE_DIR.mkdir(parents=True, exist_ok=True)
    stem = f"{guide['guide_id']}.v{guide['version_no']}"
    (RENDERED_GUIDE_DIR / f"{stem}.md").write_text(markdown, encoding="utf-8")


def policy_rules_markdown(rules: list[dict[str, Any]]) -> str:
    sections = []
    for rule in sorted(rules, key=lambda item: item["priority"]):
        sections.append(
            f"### {rule['priority']} · {rule['rule_id']}\n\n"
            f"- 조건: {rule['trigger']}\n"
            f"- Agent 동작: {rule['agent_action']}\n"
            f"- 최종 권한: {rule['final_authority']}\n"
            f"- 강제 지점: {rule['enforcement']}\n"
            f"- 필수 근거:\n{bullet(rule['required_evidence'])}\n"
            f"- 사용자에게 보이는 동작: {rule['user_visible_behavior']}\n"
            f"- 금지 결과:\n{bullet(rule['prohibited_outcomes'])}"
        )
    return "\n\n".join(sections)


def policy_chunks(path: Path, policy: dict[str, Any]) -> list[dict[str, Any]]:
    source_hash = sha256_bytes(path.read_bytes())
    overview = (
        f"제목: {policy['title']}\n"
        f"목적: {policy['purpose']}\n"
        f"금지 사항:\n{bullet(policy['prohibitions'])}"
    )
    sections = (
        (1, "overview", "목적과 공통 금지 사항", overview, []),
        (
            2,
            "rules",
            "우선순위별 실행 규칙",
            policy_rules_markdown(policy["rules"]),
            [item["rule_id"] for item in policy["rules"]],
        ),
    )
    return [
        {
            "schema_version": "knowledge_policy_chunk.v0",
            "chunk_id": f"{policy['policy_id']}:v{policy['version_no']}:c{number:02d}",
            "policy_id": policy["policy_id"],
            "document_type": "POLICY",
            "version_no": policy["version_no"],
            "chunk_no": number,
            "section_id": section_id,
            "title": title,
            "content": content,
            "content_sha256": sha256_bytes(content.encode("utf-8")),
            "source_content_sha256": source_hash,
            "access_scope": policy["access"]["access_scope"],
            "exposure_policy": policy["access"]["exposure_policy"],
            "ingest_status": policy["access"]["ingest_status"],
            "content_review_status": policy["review"]["content_status"],
            "team_review_status": policy["review"]["team_review_status"],
            "rule_ids": rule_ids,
            "source_policy_ids": policy["provenance"]["source_policy_ids"],
            "source_path": path.relative_to(ROOT).as_posix(),
        }
        for number, section_id, title, content, rule_ids in sections
    ]


def render_policy_markdown(path: Path, policy: dict[str, Any]) -> None:
    """정책 JSON 정본을 팀 검토용 Markdown으로 렌더링한다."""
    source_hash = sha256_bytes(path.read_bytes())
    related = policy["related_artifacts"]
    markdown = (
        "---\n"
        f"policy_id: {policy['policy_id']}\n"
        f"version_no: {policy['version_no']}\n"
        f"status: {policy['status']}\n"
        f"access_scope: {policy['access']['access_scope']}\n"
        f"exposure_policy: {policy['access']['exposure_policy']}\n"
        f"ingest_status: {policy['access']['ingest_status']}\n"
        f"content_review_status: {policy['review']['content_status']}\n"
        f"team_review_status: {policy['review']['team_review_status']}\n"
        f"source_content_sha256: {source_hash}\n"
        "---\n\n"
        f"{V0_TRANSITION_NOTICE}"
        f"# {policy['title']}\n\n"
        f"## 목적\n\n{policy['purpose']}\n\n"
        f"## 규칙\n\n{policy_rules_markdown(policy['rules'])}\n\n"
        f"## 공통 금지 사항\n\n{bullet(policy['prohibitions'])}\n\n"
        "## 연결 대상\n\n"
        f"- 직원 가이드: {', '.join(related['guide_ids']) or '없음'}\n"
        f"- IT 런북: {', '.join(related['runbook_ids']) or '없음'}\n"
        f"- 평가 시나리오: {', '.join(related['scenario_ids']) or '없음'}\n\n"
        f"## 설계 근거\n\n{bullet(policy['provenance']['source_policy_ids'])}\n"
    )
    RENDERED_POLICY_DIR.mkdir(parents=True, exist_ok=True)
    stem = f"{policy['policy_id']}.v{policy['version_no']}"
    (RENDERED_POLICY_DIR / f"{stem}.md").write_text(markdown, encoding="utf-8")


def glossary_chunks(path: Path, catalog: dict[str, Any]) -> list[dict[str, Any]]:
    source_hash = sha256_bytes(path.read_bytes())
    rows = []
    for number, item in enumerate(catalog["terms"], start=1):
        aliases = ", ".join(item["aliases"]) if item["aliases"] else "없음"
        content = (
            f"용어: {item['term']}\n별칭: {aliases}\n정의: {item['definition']}\n"
            f"분류: {item['category']}\n관련 컴포넌트: {', '.join(item['related_components']) or '공통'}"
        )
        rows.append({
            "schema_version": "reference_chunk.v0",
            "chunk_id": f"GLOSSARY:v0:{item['term_id']}",
            "reference_id": item["term_id"],
            "chunk_no": number,
            "document_type": "REFERENCE",
            "reference_type": "IT_GLOSSARY",
            "title": item["term"],
            "content": content,
            "content_sha256": sha256_bytes(content.encode("utf-8")),
            "source_content_sha256": source_hash,
            "access_scope": "EMPLOYEE",
            "exposure_policy": "QUOTABLE",
            "ingest_status": "PENDING",
            "content_review_status": catalog["review"]["content_status"],
            "team_review_status": catalog["review"]["team_review_status"],
            "source_path": path.relative_to(ROOT).as_posix(),
        })
    return rows


def markdown_cell(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")


def render_glossary_catalog(catalog: dict[str, Any]) -> None:
    """IT 용어집 JSON 정본을 팀 검토용 표로 렌더링한다."""
    rows = []
    for item in catalog["terms"]:
        values = [
            item["term_id"], item["term"], ", ".join(item["aliases"]) or "없음",
            item["category"], item["definition"],
            ", ".join(item["related_components"]) or "공통",
        ]
        rows.append("| " + " | ".join(markdown_cell(value) for value in values) + " |")
    markdown = (
        "# IT 용어집 v0\n\n"
        f"{V0_REFERENCE_NOTICE}"
        "JSON 정본은 `datasets/knowledge/v0/catalogs/it_glossary.json`이다. 이 문서는 "
        "팀 검토용 자동 생성본이므로 직접 수정하지 않는다.\n\n"
        f"- 전체 {len(catalog['terms'])}건\n"
        "- 내용 검수: 데이터 담당자 확정\n"
        "- 팀 상태: 리뷰 대기\n"
        "- 공개 등급: 정의만 `EMPLOYEE/QUOTABLE`; 운영 명령·원시 로그·내부 설정은 포함하지 않는다.\n"
        "- RAG 적재: 팀 승인 전 `PENDING`\n\n"
        "| ID | 용어 | 별칭 | 분류 | 정의 | 관련 컴포넌트 |\n"
        "|---|---|---|---|---|---|\n"
        + "\n".join(rows)
        + "\n"
    )
    RENDERED_REFERENCE_DIR.mkdir(parents=True, exist_ok=True)
    (RENDERED_REFERENCE_DIR / "IT_GLOSSARY.md").write_text(markdown, encoding="utf-8")


def render_signal_catalog(catalog: dict[str, Any]) -> None:
    """오류·관측 신호 JSON 정본을 팀 검토용 표로 렌더링한다."""
    rows = []
    for item in catalog["signals"]:
        source_refs = ", ".join(item["source_refs"])
        values = [
            item["signal_id"], item["name"], item["kind"], item["match"],
            item["meaning"], item["confirmation_limit"],
            ", ".join(item["component_candidates"]), source_refs,
        ]
        rows.append("| " + " | ".join(markdown_cell(value) for value in values) + " |")
    markdown = (
        "# 오류·관측 신호 사전 v0\n\n"
        f"{V0_REFERENCE_NOTICE}"
        "JSON 정본은 `datasets/knowledge/v0/catalogs/error_signals.json`이다. 이 문서는 "
        "팀 검토용 자동 생성본이므로 직접 수정하지 않는다.\n\n"
        "- 전체 55건: PORTAL 27건, IDENTITY·VPN·FILE_SHARE 28건\n"
        "- 상태: 데이터 담당자 확정·팀 리뷰 대기\n"
        "- RAG 적재: 금지. Knowledge Agent가 런북의 signal ID를 해석할 때 조립하는 개발용 정본이다.\n"
        "- 각 신호는 원인 정답이 아니라 후보를 좁히는 관측값이다. `확정 제한`을 항상 함께 적용한다.\n\n"
        "| ID | 이름 | 종류 | 매칭 값 | 의미 | 확정 제한 | 후보 컴포넌트 | 근거 |\n"
        "|---|---|---|---|---|---|---|---|\n"
        + "\n".join(rows)
        + "\n"
    )
    RENDERED_REFERENCE_DIR.mkdir(parents=True, exist_ok=True)
    (RENDERED_REFERENCE_DIR / "ERROR_SIGNALS.md").write_text(markdown, encoding="utf-8")


def build_runbook_assets(
    path: Path, runbook: dict[str, Any], signal_by_id: dict[str, dict[str, Any]],
    signal_catalog_hash: str,
) -> list[dict[str, Any]]:
    """JSON 정본에서 팀 검토용 Markdown과 RAG 청크를 함께 생성한다."""
    source_hash = sha256_bytes(path.read_bytes())
    diagnosis = runbook["diagnostic_steps"]
    if len(diagnosis) < 2:
        raise ValueError(f"진단 청크 분리에 필요한 단계가 부족함: {runbook['runbook_id']}")
    split_at = min(3, len(diagnosis) - 1)
    sections = [
        ("overview", "적용 범위와 사용자 증상", overview_markdown(runbook), [], [], [], []),
        ("diagnosis_1", "진단 1: 요청·로그 연결", diagnostic_markdown(diagnosis[:split_at]), diagnosis[:split_at], [], [], []),
        ("diagnosis_2", "진단 2: 계층 구분과 근거 충분성", diagnostic_markdown(diagnosis[split_at:]), diagnosis[split_at:], [], [], []),
        (
            "causes", "원인 후보와 반대 신호",
            causes_markdown(runbook["cause_hypotheses"], signal_by_id),
            [], runbook["cause_hypotheses"], [], [],
        ),
        ("guidance", "Agent 사용자 안내와 처리 경계", guidance_markdown(runbook), [], [], runbook["guidance_steps"], []),
        ("staff_resolution", "이관 후 IT 담당자 조치·검증", staff_resolution_markdown(runbook), [], [], [], runbook["staff_resolution_steps"]),
        ("user_response", "사용자 안내 정책", response_markdown(runbook), [], [], [], []),
    ]
    chunks = [
        make_runbook_chunk(
            runbook, path, source_hash, index, section_id, title, content,
            signal_by_id, signal_catalog_hash,
            steps=steps, causes=causes, guidances=guidances,
            staff_resolutions=staff_resolutions,
        )
        for index, (section_id, title, content, steps, causes, guidances, staff_resolutions)
        in enumerate(sections, start=1)
    ]
    stem = f"{runbook['runbook_id']}.v{runbook['version_no']}"
    dump_jsonl(CHUNK_DIR / f"{stem}.chunks.jsonl", chunks)
    RENDERED_RUNBOOK_DIR.mkdir(parents=True, exist_ok=True)
    frontmatter = (
        "---\n"
        f"runbook_id: {runbook['runbook_id']}\n"
        f"version_no: {runbook['version_no']}\n"
        f"status: {runbook['status']}\n"
        f"access_scope: {runbook['access']['access_scope']}\n"
        f"exposure_policy: {runbook['access']['exposure_policy']}\n"
        f"ingest_status: {runbook['access']['ingest_status']}\n"
        f"content_review_status: {runbook['review']['content_status']}\n"
        f"team_review_status: {runbook['review']['team_review_status']}\n"
        f"tool_binding_status: {runbook['tool_binding']['status']}\n"
        f"scenario_validation_status: {runbook['scenario_validation']['status']}\n"
        f"source_content_sha256: {source_hash}\n"
        "---\n\n"
        f"{V0_TRANSITION_NOTICE}"
    )
    markdown = frontmatter + f"# {runbook['title']}\n\n" + "\n\n---\n\n".join(
        f"## {title}\n\n{content}" for _, title, content, _, _, _, _ in sections
    ) + "\n"
    (RENDERED_RUNBOOK_DIR / f"{stem}.md").write_text(markdown, encoding="utf-8")
    return chunks


def validate() -> dict[str, int]:
    errors: list[str] = []
    schema_errors = validate_canonical_json_schemas()
    if schema_errors:
        raise SystemExit("\n".join(f"ERROR: {item}" for item in schema_errors))

    with (MANIFEST_DIR / "portal_evidence_review.csv").open(encoding="utf-8-sig", newline="") as file:
        evidence = {row["evidence_id"]: row for row in csv.DictReader(file)}
    with (MANIFEST_DIR / "general_source_review.csv").open(encoding="utf-8", newline="") as file:
        general_source_rows = list(csv.DictReader(file))
    general_evidence = {row["evidence_id"]: row for row in general_source_rows}
    general_sections = [
        json.loads(line)
        for line in (MANIFEST_DIR / "general_section_access.jsonl").read_text(encoding="utf-8").splitlines()
        if line
    ]

    if len(general_source_rows) != 62 or len(general_evidence) != 62:
        errors.append("일반 공개 근거는 중복 없는 62건이어야 함")
    general_decisions = Counter(row["decision"] for row in general_source_rows)
    expected_general_decisions = {"include": 12, "hold": 4, "reject": 46}
    if dict(general_decisions) != expected_general_decisions:
        errors.append(
            f"일반 공개 근거 판정 건수 오류: expected={expected_general_decisions}, "
            f"actual={dict(general_decisions)}"
        )
    expected_use_status = {
        "include": "DERIVATION_APPROVED_TEAM_PENDING",
        "hold": "BLOCKED_HOLD",
        "reject": "EXCLUDED",
    }
    for row in general_source_rows:
        evidence_id = row["evidence_id"]
        if not re.fullmatch(r"GE-[0-9]{3}", evidence_id):
            errors.append(f"일반 공개 근거 ID 오류: {evidence_id}")
        if row["source_id"] != "support_articles":
            errors.append(f"일반 공개 근거 출처 오류: {evidence_id}")
        if not re.fullmatch(r"[0-9a-f]{40}", row["source_revision"]):
            errors.append(f"일반 공개 근거 revision 오류: {evidence_id}")
        if not re.fullmatch(r"[0-9a-f]{64}", row["content_sha256"]):
            errors.append(f"일반 공개 근거 hash 오류: {evidence_id}")
        if row["source_revision"] not in row["pinned_url"]:
            errors.append(f"commit 고정 URL 오류: {evidence_id}")
        if row["raw_rag_ingest_allowed"] != "false":
            errors.append(f"원문 RAG 적재 금지 오류: {evidence_id}")
        if row["access_scope"] != "STAFF" or row["exposure_policy"] != "INTERNAL_ONLY":
            errors.append(f"일반 공개 근거 기본 권한 오류: {evidence_id}")
        if row["use_status"] != expected_use_status.get(row["decision"]):
            errors.append(f"일반 공개 근거 사용 상태 오류: {evidence_id}")
        if row["decision"] == "hold" and not row["hold_reason"]:
            errors.append(f"hold 해제 조건 누락: {evidence_id}")
        if row["decision"] == "reject" and not row["reject_reason"]:
            errors.append(f"reject 사유 누락: {evidence_id}")
        if row["reviewer"] != "DATA_OWNER" or row["reviewed_at"] != "2026-10-26":
            errors.append(f"일반 공개 근거 사람 판정 정보 오류: {evidence_id}")

    if len(general_sections) != 82:
        errors.append("include 문서 섹션은 82건이어야 함")
    section_ids = [item["section_id"] for item in general_sections]
    if len(section_ids) != len(set(section_ids)):
        errors.append("일반 공개 근거 section_id 중복")
    for item in general_sections:
        section_id = item["section_id"]
        source_row = general_evidence.get(item["evidence_id"])
        if source_row is None or source_row["decision"] != "include":
            errors.append(f"include가 아닌 문서의 섹션 참조: {section_id}")
            continue
        if item["doc_id"] != source_row["doc_id"]:
            errors.append(f"섹션 문서 참조 오류: {section_id}")
        if item["source_revision"] != source_row["source_revision"]:
            errors.append(f"섹션 revision 오류: {section_id}")
        if item["content_sha256"] != source_row["content_sha256"]:
            errors.append(f"섹션 원문 hash 오류: {section_id}")
        if item["rag_ingest_allowed"] is not False:
            errors.append(f"원문 섹션 RAG 적재 금지 오류: {section_id}")
        if item["access_scope"] != "STAFF":
            errors.append(f"사람 미확정 섹션의 직원 공개 오류: {section_id}")
        if item["section_access_status"] != "DRAFT_DEFAULT_DENY" or item["section_human_confirmed"]:
            errors.append(f"섹션 기본 거부 상태 오류: {section_id}")
        if item["section_reviewer"] is not None or item["section_reviewed_at"] is not None:
            errors.append(f"미검수 섹션에 검수자 기록: {section_id}")
        if item["exposure_policy"] == "NEVER_EXPOSE" and item["employee_candidate"]:
            errors.append(f"NEVER_EXPOSE 섹션이 직원 후보임: {section_id}")
    if Counter(item["exposure_policy"] for item in general_sections) != {
        "INTERNAL_ONLY": 78,
        "NEVER_EXPOSE": 4,
    }:
        errors.append("일반 공개 근거 섹션 노출 정책 건수 오류")
    if sum(item["employee_candidate"] for item in general_sections) != 12:
        errors.append("직원 공개 검수 후보 섹션은 12건이어야 함")
    if sum(item["derivation_eligible"] for item in general_sections) != 81:
        errors.append("파생 문서 작성 후보 섹션은 81건이어야 함")
    include_general_ids = {
        row["evidence_id"] for row in general_source_rows if row["decision"] == "include"
    }
    if {item["evidence_id"] for item in general_sections} != include_general_ids:
        errors.append("include 문서와 섹션 manifest의 문서 집합 불일치")

    runbooks = []
    runbook_paths: dict[str, Path] = {}
    for path in sorted(RUNBOOK_DIR.glob("*.json")):
        item = json.loads(path.read_text(encoding="utf-8"))
        runbooks.append(item)
        runbook_paths[item["runbook_id"]] = path
        content_status = item["review"]["content_status"]
        if content_status == "OWNER_CONFIRMED":
            if item["review"]["reviewer"] != "DATA_OWNER" or not item["review"]["reviewed_at"]:
                errors.append(f"담당자 확정 정보 오류: {item['runbook_id']}")
        elif content_status == "PENDING":
            if item["review"]["reviewer"] is not None or item["review"]["reviewed_at"] is not None:
                errors.append(f"미확정 런북에 검수자 기록: {item['runbook_id']}")
        else:
            errors.append(f"사용 불가 런북 상태: {item['runbook_id']} -> {content_status}")
        if item["review"]["team_review_status"] != "PENDING":
            errors.append(f"공유 전 팀 상태 오류: {item['runbook_id']}")
        if item["access"]["ingest_status"] != "PENDING":
            errors.append(f"팀 승인 전 ingest 금지: {item['runbook_id']}")
        if item["tool_binding"]["status"] != "PENDING" or item["scenario_validation"]["status"] != "NOT_RUN":
            errors.append(f"Tool/시나리오 상태 오류: {item['runbook_id']}")
        provenance_ids = set(item["provenance"]["source_evidence_ids"])
        for evidence_id in provenance_ids:
            source_row = evidence.get(evidence_id) or general_evidence.get(evidence_id)
            if source_row is None or source_row["decision"] != "include":
                errors.append(f"승인되지 않은 근거: {item['runbook_id']} -> {evidence_id}")
        referenced_ids = {
            evidence_id
            for group in (
                item["diagnostic_steps"], item["cause_hypotheses"], item["guidance_steps"],
                item["staff_resolution_steps"],
            )
            for entry in group
            for evidence_id in entry["evidence_ids"]
        }
        if not referenced_ids.issubset(provenance_ids):
            errors.append(f"런북 단계 근거가 provenance 밖을 참조: {item['runbook_id']}")

    runbook_ids = {item["runbook_id"] for item in runbooks}
    if len(runbook_ids) != len(runbooks):
        errors.append("런북 ID 중복")
    expected_runbook_review = {"OWNER_CONFIRMED": 11}
    actual_runbook_review = dict(Counter(item["review"]["content_status"] for item in runbooks))
    if actual_runbook_review != expected_runbook_review:
        errors.append(
            f"런북 내용 검수 상태 건수 오류: expected={expected_runbook_review}, "
            f"actual={actual_runbook_review}"
        )
    guides = []
    allowed_policy_ids = {
        "PRD-1.2", "PRD-3.1", "PRD-3.2", "PRD-3.4", "PRD-3.5",
        "KNOWLEDGE-LIFECYCLE",
    }
    for path in sorted(GUIDE_DIR.glob("*.json")):
        item = json.loads(path.read_text(encoding="utf-8"))
        guides.append(item)
        if item["access"] != {"access_scope": "EMPLOYEE", "exposure_policy": "QUOTABLE", "ingest_status": "PENDING"}:
            errors.append(f"직원 가이드 권한 오류: {item['guide_id']}")
        if not set(item["related_runbook_ids"]).issubset(runbook_ids):
            errors.append(f"없는 런북 참조: {item['guide_id']}")
        if item["review"]["content_status"] != "OWNER_CONFIRMED":
            errors.append(f"직원 가이드 담당자 미확정: {item['guide_id']}")
        elif item["review"]["reviewer"] != "DATA_OWNER" or not item["review"]["reviewed_at"]:
            errors.append(f"직원 가이드 담당자 확정 정보 오류: {item['guide_id']}")
        if item["review"]["team_review_status"] != "PENDING":
            errors.append(f"직원 가이드 공유 전 팀 상태 오류: {item['guide_id']}")
        provenance = item["provenance"]
        if provenance["source_type"] == "SYNTHESIZED_FROM_PROJECT_POLICY":
            if provenance["source_evidence_ids"] or not provenance.get("source_policy_ids"):
                errors.append(f"프로젝트 정책 가이드 계보 오류: {item['guide_id']}")
            if not set(provenance.get("source_policy_ids", [])).issubset(allowed_policy_ids):
                errors.append(f"등록되지 않은 프로젝트 정책 참조: {item['guide_id']}")
        elif not provenance["source_evidence_ids"]:
            errors.append(f"공개 근거 파생 가이드의 근거 누락: {item['guide_id']}")
        for evidence_id in provenance["source_evidence_ids"]:
            source_row = evidence.get(evidence_id) or general_evidence.get(evidence_id)
            if source_row is None or source_row["decision"] != "include":
                errors.append(f"가이드의 승인되지 않은 근거: {item['guide_id']} -> {evidence_id}")
    expected_guide_provenance = {
        "SYNTHESIZED_FROM_APPROVED_SOURCE_EVIDENCE": 7,
        "SYNTHESIZED_FROM_APPROVED_RUNBOOK_EVIDENCE": 3,
        "SYNTHESIZED_FROM_PROJECT_POLICY": 3,
    }
    actual_guide_provenance = dict(Counter(item["provenance"]["source_type"] for item in guides))
    if actual_guide_provenance != expected_guide_provenance:
        errors.append(
            f"가이드 계보 건수 오류: expected={expected_guide_provenance}, "
            f"actual={actual_guide_provenance}"
        )
    if Counter(item["review"]["content_status"] for item in guides) != {"OWNER_CONFIRMED": 13}:
        errors.append("직원 가이드 13건은 모두 담당자 확정 상태여야 함")

    policies = []
    policy_paths: dict[str, Path] = {}
    allowed_policy_sources = {
        "PRD-2", "PRD-3.1", "PRD-3.2", "PRD-3.3", "PRD-3.4", "PRD-3.5", "PRD-4.4",
        "PRD-7", "PRD-10", "DATA-INQUIRY", "DATA-TICKET",
        "KNOWLEDGE-LIFECYCLE-3", "KNOWLEDGE-LIFECYCLE-4", "KNOWLEDGE-LIFECYCLE-5",
        "KNOWLEDGE-LIFECYCLE-6", "KNOWLEDGE-LIFECYCLE-7", "KNOWLEDGE-LIFECYCLE-8",
        "KNOWLEDGE-LIFECYCLE-9", "KNOWLEDGE-LIFECYCLE-10", "KNOWLEDGE-LIFECYCLE-12",
    }
    rule_ids: list[str] = []
    for path in sorted(POLICY_DIR.glob("*.json")):
        item = json.loads(path.read_text(encoding="utf-8"))
        policies.append(item)
        policy_paths[item["policy_id"]] = path
        if item["status"] != "DRAFT":
            errors.append(f"팀 승인 전 정책 상태 오류: {item['policy_id']}")
        if item["access"] != {"access_scope": "STAFF", "exposure_policy": "INTERNAL_ONLY", "ingest_status": "PENDING"}:
            errors.append(f"정책 권한 오류: {item['policy_id']}")
        if item["review"]["content_status"] != "OWNER_CONFIRMED":
            errors.append(f"정책 담당자 미확정: {item['policy_id']}")
        elif item["review"]["reviewer"] != "DATA_OWNER" or not item["review"]["reviewed_at"]:
            errors.append(f"정책 담당자 확정 정보 오류: {item['policy_id']}")
        if item["review"]["team_review_status"] != "PENDING":
            errors.append(f"정책 공유 전 팀 상태 오류: {item['policy_id']}")
        if not set(item["provenance"]["source_policy_ids"]).issubset(allowed_policy_sources):
            errors.append(f"정책의 등록되지 않은 설계 근거: {item['policy_id']}")
        priorities = [rule["priority"] for rule in item["rules"]]
        if len(priorities) != len(set(priorities)):
            errors.append(f"정책 내부 priority 중복: {item['policy_id']}")
        rule_ids.extend(rule["rule_id"] for rule in item["rules"])

    policy_ids = {item["policy_id"] for item in policies}
    if len(policy_ids) != len(policies):
        errors.append("정책 ID 중복")
    if len(rule_ids) != len(set(rule_ids)):
        errors.append("정책 rule_id 중복")

    glossary = json.loads((CATALOG_DIR / "it_glossary.json").read_text(encoding="utf-8"))
    signals = json.loads((CATALOG_DIR / "error_signals.json").read_text(encoding="utf-8"))
    scenario_catalog = json.loads((CATALOG_DIR / "incident_scenarios.json").read_text(encoding="utf-8"))
    virtual_company = json.loads((CATALOG_DIR / "virtual_company.json").read_text(encoding="utf-8"))
    if len({item["term_id"] for item in glossary["terms"]}) != len(glossary["terms"]):
        errors.append("용어 ID 중복")
    glossary_review = glossary.get("review", {})
    if glossary.get("status") != "OWNER_CONFIRMED_TEAM_REVIEW_PENDING":
        errors.append("용어집 공유 상태 오류")
    if (
        glossary_review.get("content_status") != "OWNER_CONFIRMED"
        or glossary_review.get("reviewer") != "DATA_OWNER"
        or not glossary_review.get("reviewed_at")
        or glossary_review.get("team_review_status") != "PENDING"
    ):
        errors.append("용어집 담당자 확정 또는 팀 리뷰 상태 오류")
    expected_term_ids = {f"TERM-{number:03d}" for number in range(1, 51)}
    actual_term_ids = {item["term_id"] for item in glossary["terms"]}
    if actual_term_ids != expected_term_ids:
        errors.append("용어집 ID는 TERM-001부터 TERM-050까지 연속이어야 함")
    normalized_terms: list[str] = []
    for item in glossary["terms"]:
        normalized_terms.extend(
            re.sub(r"[^0-9a-z가-힣]", "", value.casefold())
            for value in [item["term"], *item["aliases"]]
        )
    if len(normalized_terms) != len(set(normalized_terms)):
        errors.append("용어집 표제어 또는 별칭 중복")
    if len({item["signal_id"] for item in signals["signals"]}) != len(signals["signals"]):
        errors.append("신호 ID 중복")
    if signals.get("knowledge_ingest_allowed") is not False:
        errors.append("진단 신호 레지스트리는 RAG 적재 금지여야 함")
    signal_review = signals.get("review", {})
    decision_required_signal_ids = set(signal_review.get("decision_required_signal_ids", []))
    if not decision_required_signal_ids.issubset({item["signal_id"] for item in signals["signals"]}):
        errors.append("진단 신호 검수의 결정 필요 ID가 레지스트리에 없음")
    if signal_review.get("owner_review_status") == "PENDING" and (
        signal_review.get("owner_reviewer") is not None
        or signal_review.get("owner_reviewed_at") is not None
    ):
        errors.append("진단 신호 담당자 검수 대기 상태에 검수자 정보가 있음")
    if signal_review.get("owner_review_status") == "CONFIRMED" and (
        signal_review.get("owner_reviewer") != "DATA_OWNER"
        or not signal_review.get("owner_reviewed_at")
        or decision_required_signal_ids
    ):
        errors.append("진단 신호 담당자 확정 정보 또는 미결정 ID 오류")

    signal_by_id = {item["signal_id"]: item for item in signals["signals"]}
    component_ids = {
        component["component_id"] if isinstance(component, dict) else component
        for service in virtual_company["service_scope"]
        for component in service["components"]
    }
    for item in glossary["terms"]:
        unknown_components = set(item["related_components"]) - component_ids
        if unknown_components:
            errors.append(
                f"용어집의 등록되지 않은 컴포넌트: {item['term_id']} -> "
                f"{sorted(unknown_components)}"
            )
    for runbook in runbooks:
        runbook_id = runbook["runbook_id"]
        service_id = runbook["service"]["service_id"]
        component_id = runbook["service"]["component_id"]
        compatible_components = {component_id}
        if service_id == "PORTAL":
            compatible_components.update({"PORTAL_API", "PORTAL_DB"})
        for cause in runbook["cause_hypotheses"]:
            cause_ref = f"{runbook_id}/{cause['cause_id']}"
            required_ids = set(cause["required_signal_ids"])
            contradictory_ids = set(cause["contradictory_signal_ids"])
            missing_ids = (required_ids | contradictory_ids) - set(signal_by_id)
            if missing_ids:
                errors.append(f"런북의 없는 신호 참조: {cause_ref} -> {sorted(missing_ids)}")
            if required_ids & contradictory_ids:
                errors.append(f"필요·반대 신호 ID 중복: {cause_ref} -> {sorted(required_ids & contradictory_ids)}")
            for signal_id in required_ids | contradictory_ids:
                signal = signal_by_id.get(signal_id)
                if signal is not None and compatible_components.isdisjoint(signal["component_candidates"]):
                    errors.append(
                        f"런북 컴포넌트와 신호 후보 불일치: {cause_ref} -> "
                        f"{signal_id} ({component_id})"
                    )

    scenarios = scenario_catalog["scenarios"]
    scenario_ids = [item["scenario_id"] for item in scenarios]
    if len(scenario_ids) != len(set(scenario_ids)):
        errors.append("시나리오 ID 중복")
    if scenario_catalog.get("ingestion_policy") != "NEVER_INGEST":
        errors.append("시나리오 카탈로그는 RAG 적재 금지여야 함")
    if scenario_catalog.get("review", {}).get("owner_reviewer") != "DATA_OWNER":
        errors.append("시나리오 카탈로그 담당 검수자 오류")

    service_ids = {item["service_id"] for item in virtual_company["service_scope"]}
    component_ids: set[str] = set()
    for service in virtual_company["service_scope"]:
        for component in service["components"]:
            component_ids.add(component if isinstance(component, str) else component["component_id"])
    department_ids = {item["code"] for item in virtual_company["departments"]}
    for guide in guides:
        if guide["service_id"] not in service_ids | {"COMMON"}:
            errors.append(f"가이드의 없는 서비스 참조: {guide['guide_id']}")
    guide_ids = {item["guide_id"] for item in guides}
    include_evidence_ids = {
        evidence_id for evidence_id, row in evidence.items() if row["decision"] == "include"
    }

    valid_signal_refs = runbook_ids | set(scenario_ids)
    for signal in signals["signals"]:
        signal_id = signal["signal_id"]
        if not set(signal["component_candidates"]).issubset(component_ids):
            errors.append(f"오류 신호의 없는 컴포넌트 참조: {signal_id}")
        source_refs = set(signal["source_refs"])
        if not source_refs:
            errors.append(f"오류 신호의 근거 누락: {signal_id}")
        if not source_refs.issubset(valid_signal_refs):
            errors.append(f"오류 신호의 없는 근거 문서 참조: {signal_id}")

    for scenario in scenarios:
        scenario_id = scenario["scenario_id"]
        if scenario["knowledge_ingest_allowed"] is not False:
            errors.append(f"시나리오 정답 RAG 적재 금지 오류: {scenario_id}")
        if scenario["service_id"] is not None and scenario["service_id"] not in service_ids:
            errors.append(f"없는 서비스 참조: {scenario_id} -> {scenario['service_id']}")
        if not set(scenario["component_candidates"]).issubset(component_ids):
            errors.append(f"없는 컴포넌트 참조: {scenario_id}")
        if not set(scenario["expected"]["departments"]).issubset(department_ids):
            errors.append(f"없는 부서 참조: {scenario_id}")

        artifacts = scenario["artifacts"]
        if artifacts["guide_status"] == "OWNER_CONFIRMED" and not set(artifacts["guide_ids"]).issubset(guide_ids):
            errors.append(f"없는 담당자 확정 가이드 참조: {scenario_id}")
        if artifacts["runbook_status"] in {"AI_DRAFT", "OWNER_CONFIRMED"} and not set(artifacts["runbook_ids"]).issubset(runbook_ids):
            errors.append(f"없는 런북 참조: {scenario_id}")
        if artifacts["policy_status"] == "OWNER_CONFIRMED" and not set(artifacts["policy_ids"]).issubset(policy_ids):
            errors.append(f"없는 담당자 확정 정책 참조: {scenario_id}")
        portal_evidence_refs = {item for item in scenario["evidence_refs"] if item.startswith("PE-")}
        if not portal_evidence_refs.issubset(include_evidence_ids):
            errors.append(f"승인되지 않은 PORTAL 근거 참조: {scenario_id}")
        general_evidence_refs = {item for item in scenario["evidence_refs"] if item.startswith("GE-")}
        if not general_evidence_refs.issubset(general_evidence):
            errors.append(f"없는 일반 공개 근거 참조: {scenario_id}")
        general_reference_decisions = {
            general_evidence[item]["decision"] for item in general_evidence_refs
        }
        if scenario["evidence_status"] == "INCLUDE_CONFIRMED" and general_reference_decisions - {"include"}:
            errors.append(f"include 시나리오가 hold/reject 근거를 참조: {scenario_id}")
        if scenario["evidence_status"] == "HOLD" and general_reference_decisions != {"hold"}:
            errors.append(f"hold 시나리오 근거 상태 오류: {scenario_id}")
        if scenario["evidence_status"] == "MIXED" and general_reference_decisions != {"include", "hold"}:
            errors.append(f"mixed 시나리오 근거 상태 오류: {scenario_id}")

        scenario_type = scenario["scenario_type"]
        trigger = scenario["trigger"]
        if scenario_type == "KNOWLEDGE_ONLY":
            if trigger["type"] != "NONE" or scenario["reproduction_status"] != "NOT_REQUIRED":
                errors.append(f"Knowledge-only 재현 상태 오류: {scenario_id}")
        elif scenario_type == "DEMO_REPRODUCIBLE":
            if trigger["type"] != "DEMO_PORTAL" or scenario["reproduction_status"] != "NOT_RUN":
                errors.append(f"Demo Portal 재현 상태 오류: {scenario_id}")
        elif scenario_type == "WORKFLOW_EVAL":
            if trigger["type"] != "FIXTURE" or scenario["reproduction_status"] != "NOT_REQUIRED":
                errors.append(f"업무 흐름 fixture 상태 오류: {scenario_id}")

    scenario_type_counts = Counter(item["scenario_type"] for item in scenarios)
    expected_scenario_types = {
        "KNOWLEDGE_ONLY": 12,
        "DEMO_REPRODUCIBLE": 10,
        "WORKFLOW_EVAL": 12,
    }
    if dict(scenario_type_counts) != expected_scenario_types:
        errors.append(
            f"시나리오 유형 건수 오류: expected={expected_scenario_types}, "
            f"actual={dict(scenario_type_counts)}"
        )
    demo_scenarios = [item for item in scenarios if item["scenario_type"] == "DEMO_REPRODUCIBLE"]
    if sum(item["case_eligible"] for item in demo_scenarios) != 9:
        errors.append("Demo Portal CASE 후보는 9건이어야 함")
    pg_sleep = next((item for item in scenarios if item["scenario_id"] == "SCN-PORTAL-DB-PG-SLEEP"), None)
    if pg_sleep is None or pg_sleep["case_eligible"]:
        errors.append("pg_sleep 평가 트리거는 CASE 승격 금지여야 함")

    scenario_id_set = set(scenario_ids)
    for policy in policies:
        related = policy["related_artifacts"]
        if not set(related["guide_ids"]).issubset(guide_ids):
            errors.append(f"정책의 없는 가이드 참조: {policy['policy_id']}")
        if not set(related["runbook_ids"]).issubset(runbook_ids):
            errors.append(f"정책의 없는 런북 참조: {policy['policy_id']}")
        if not set(related["scenario_ids"]).issubset(scenario_id_set):
            errors.append(f"정책의 없는 시나리오 참조: {policy['policy_id']}")

    signal_catalog_hash = sha256_bytes((CATALOG_DIR / "error_signals.json").read_bytes())
    runbook_chunks: list[dict[str, Any]] = []
    for runbook in runbooks:
        runbook_chunks.extend(build_runbook_assets(
            runbook_paths[runbook["runbook_id"]], runbook,
            signal_by_id, signal_catalog_hash,
        ))
    runbook_by_id = {item["runbook_id"]: item for item in runbooks}
    for chunk in runbook_chunks:
        if chunk["section_id"] == "causes":
            runbook = runbook_by_id[chunk["runbook_id"]]
            expected_definitions = cause_signal_definitions(
                runbook["cause_hypotheses"], signal_by_id,
            )
            expected_signal_ids = unique([
                item["signal_id"] for item in expected_definitions
            ])
            if chunk["signal_definitions"] != expected_definitions:
                errors.append(f"런북 청크 신호 정의 결합 오류: {chunk['chunk_id']}")
            if chunk["signal_ids"] != expected_signal_ids:
                errors.append(f"런북 청크 신호 ID 결합 오류: {chunk['chunk_id']}")
            if chunk["signal_catalog_sha256"] != signal_catalog_hash:
                errors.append(f"런북 청크 신호 레지스트리 hash 오류: {chunk['chunk_id']}")
            for definition in expected_definitions:
                required_text = (
                    definition["signal_id"], definition["meaning"],
                    definition["confirmation_limit"],
                )
                if not all(value in chunk["content"] for value in required_text):
                    errors.append(
                        f"런북 청크 본문 신호 정의 누락: {chunk['chunk_id']} -> "
                        f"{definition['signal_id']}"
                    )
        elif (
            chunk["signal_catalog_sha256"] is not None
            or chunk["signal_ids"]
            or chunk["signal_definitions"]
        ):
            errors.append(f"원인 외 청크에 신호 정의 포함: {chunk['chunk_id']}")
    guide_chunk_rows: list[dict[str, Any]] = []
    for path in sorted(GUIDE_DIR.glob("*.json")):
        guide = json.loads(path.read_text(encoding="utf-8"))
        rows = guide_chunks(path, guide)
        render_guide_markdown(path, guide)
        dump_jsonl(CHUNK_DIR / f"{guide['guide_id']}.v{guide['version_no']}.chunks.jsonl", rows)
        guide_chunk_rows.extend(rows)
    policy_chunk_rows: list[dict[str, Any]] = []
    for policy in policies:
        path = policy_paths[policy["policy_id"]]
        rows = policy_chunks(path, policy)
        render_policy_markdown(path, policy)
        dump_jsonl(CHUNK_DIR / f"{policy['policy_id']}.v{policy['version_no']}.chunks.jsonl", rows)
        policy_chunk_rows.extend(rows)
    glossary_rows = glossary_chunks(CATALOG_DIR / "it_glossary.json", glossary)
    render_glossary_catalog(glossary)
    render_signal_catalog(signals)
    dump_jsonl(CHUNK_DIR / "IT-GLOSSARY.v0.chunks.jsonl", glossary_rows)

    all_chunks = [*runbook_chunks, *guide_chunk_rows, *policy_chunk_rows, *glossary_rows]
    chunk_schemas = {
        "staff_runbook_chunk.v0": load_json(SCHEMA_DIR / "staff_runbook_chunk.schema.json"),
        "employee_guide_chunk.v0": load_json(SCHEMA_DIR / "employee_guide_chunk.schema.json"),
        "knowledge_policy_chunk.v0": load_json(SCHEMA_DIR / "knowledge_policy_chunk.schema.json"),
        "reference_chunk.v0": load_json(SCHEMA_DIR / "reference_chunk.schema.json"),
    }
    chunk_ids = [item["chunk_id"] for item in all_chunks]
    if len(chunk_ids) != len(set(chunk_ids)):
        errors.append("전체 chunk_id 중복")
    for item in all_chunks:
        chunk_schema = chunk_schemas.get(item.get("schema_version"))
        if chunk_schema is None:
            errors.append(
                f"청크 JSON Schema 매핑 누락: {item.get('chunk_id', '<unknown>')} -> "
                f"{item.get('schema_version')}"
            )
        else:
            errors.extend(schema_instance_errors(
                item, chunk_schema, f"generated chunk {item.get('chunk_id', '<unknown>')}",
            ))
        if item["content_sha256"] != sha256_bytes(item["content"].encode("utf-8")):
            errors.append(f"청크 hash 오류: {item['chunk_id']}")
        if item["ingest_status"] != "PENDING":
            errors.append(f"팀 승인 전 ingest 상태 오류: {item['chunk_id']}")
        if "embedding" in item:
            errors.append(f"v0 공유 단계에 embedding 포함: {item['chunk_id']}")
        if item["schema_version"] == "staff_runbook_chunk.v0":
            expected_metadata = ("STAFF_RUNBOOK", "STAFF", "INTERNAL_ONLY")
        elif item["schema_version"] == "knowledge_policy_chunk.v0":
            expected_metadata = ("POLICY", "STAFF", "INTERNAL_ONLY")
        elif item.get("reference_type") == "IT_GLOSSARY":
            expected_metadata = ("REFERENCE", "EMPLOYEE", "QUOTABLE")
        else:
            expected_metadata = ("EMPLOYEE_GUIDE", "EMPLOYEE", "QUOTABLE")
        actual_metadata = (
            item.get("document_type"), item.get("access_scope"), item.get("exposure_policy")
        )
        if actual_metadata != expected_metadata:
            errors.append(
                f"청크 유형/권한 오류: {item['chunk_id']} "
                f"expected={expected_metadata}, actual={actual_metadata}"
            )

    expected = {
        "runbooks": 11,
        "guides": 13,
        "policies": 5,
        "glossary_terms": 50,
        "error_signals": 55,
        "general_source_reviews": 62,
        "general_source_sections": 82,
        "scenarios": 34,
        "chunks": 163,
    }
    actual = {
        "runbooks": len(runbooks),
        "guides": len(guides),
        "policies": len(policies),
        "glossary_terms": len(glossary["terms"]),
        "error_signals": len(signals["signals"]),
        "general_source_reviews": len(general_source_rows),
        "general_source_sections": len(general_sections),
        "scenarios": len(scenarios),
        "chunks": len(all_chunks),
    }
    if actual != expected:
        errors.append(f"건수 오류: expected={expected}, actual={actual}")
    if errors:
        raise SystemExit("\n".join(f"ERROR: {item}" for item in errors))
    return actual


def write_review_summary(counts: dict[str, int]) -> None:
    """자동 검수 결과와 아직 필요한 사람 검수를 혼동하지 않도록 기록한다."""
    summary = {
        "schema_version": "knowledge_chunk_review_summary.v0",
        "dataset_id": "HOPS-KNOWLEDGE-V0",
        "reviewed_at": str(date.today()),
        "automated_review": {
            "status": "PASS",
            "checks": [
                "expected_counts",
                "json_schema_meta_validation",
                "canonical_json_schema_validation",
                "generated_chunk_json_schema_validation",
                "unique_document_and_reference_ids",
                "unique_chunk_ids",
                "chunk_content_sha256",
                "approved_evidence_references",
                "access_and_exposure_metadata",
                "ingest_status_pending",
                "no_embedding_payload",
                "runbook_tool_and_scenario_status",
                "scenario_catalog_counts_and_references",
                "scenario_ground_truth_never_ingest",
                "policy_counts_rules_and_references",
                "general_source_review_counts_and_provenance",
                "general_source_section_default_deny",
                "glossary_owner_review_and_scope",
                "diagnostic_signal_registry_never_ingest",
                "runbook_signal_definition_composition",
            ],
            "counts": counts,
        },
        "semantic_review": {
            "staff_runbooks": "11_OWNER_CONFIRMED_TEAM_REVIEW_PENDING",
            "employee_guides": "13_OWNER_CONFIRMED_TEAM_REVIEW_PENDING_1_WIFI_HOLD",
            "knowledge_policies": "5_OWNER_CONFIRMED_TEAM_REVIEW_PENDING",
            "it_glossary": "50_OWNER_CONFIRMED_TEAM_REVIEW_PENDING",
            "error_signals": "55_OWNER_CONFIRMED_TEAM_REVIEW_PENDING",
            "incident_scenarios": "OWNER_CONFIRMED_TEAM_REVIEW_PENDING_NEVER_INGEST",
            "general_source_evidence": "OWNER_CONFIRMED_TEAM_REVIEW_PENDING_DERIVATION_ONLY",
            "general_section_access": "DRAFT_DEFAULT_DENY_HUMAN_REVIEW_PENDING",
        },
        "embedding_ready": False,
        "blocking_items": [
            "Wi-Fi EMPLOYEE_GUIDE 1건은 근거 hold 해제 전 작성 금지",
            "EMPLOYEE_GUIDE 13건 팀 리뷰",
            "STAFF_RUNBOOK 11건 팀 리뷰",
            "POLICY 5건 팀 리뷰",
            "IT 용어 50건 팀 리뷰",
            "오류·관측 신호 55건 팀 리뷰",
            "시나리오 카탈로그 34건 팀 리뷰",
            "일반 공개 근거 62건 팀 리뷰",
            "직원 공개 후보 섹션 12건 사람 검수",
            "전체 공유 데이터 팀 리뷰",
        ],
        "not_validated_yet": [
            "검색 품질",
            "Tool 출력과 런북 단계 연결",
            "Demo Portal 장애 재현",
            "실제 해결 CASE",
        ],
    }
    (MANIFEST_DIR / "chunk_review_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def write_manifest(counts: dict[str, int]) -> None:
    files = []
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file() or path.name == "manifest.json" or "__pycache__" in path.parts:
            continue
        files.append({
            "path": path.relative_to(ROOT).as_posix(),
            "sha256": sha256_bytes(path.read_bytes()),
            "size_bytes": path.stat().st_size,
        })
    manifest = {
        "schema_version": "knowledge_dataset_manifest.v0",
        "dataset_id": "HOPS-KNOWLEDGE-V0",
        "dataset_scope": "MULTI_SERVICE_WITH_PORTAL_CORE",
        "language": "ko",
        "generated_at": str(date.today()),
        "status": "MIXED_REVIEW_TEAM_PENDING",
        "embedding_status": "NOT_STARTED",
        "tool_binding_status": "PENDING",
        "scenario_validation_status": "NOT_RUN",
        "counts": counts,
        "files": files,
    }
    (ROOT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    CHUNK_DIR.mkdir(parents=True, exist_ok=True)
    counts = validate()
    write_review_summary(counts)
    write_manifest(counts)
    print(f"KNOWLEDGE DATASET V0 OK: {counts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
