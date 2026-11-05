"""시나리오·정답 파일의 형식과 정합성 검사. 모델을 부르지 않는다.

검사하는 것: 형식, 어휘(의도·종결 행동·판정 등), 시나리오와 정답의 모순(사용자가 답할 값이 없는
필수 필드 등), 근거 문서 키가 가상 기업 정본의 문서 카탈로그에 있는지.
검사하지 않는 것: 문장의 내용이 사실에 맞는지, 정답이 타당한지. 그것은 사람이 검수한다.

    uv run python -m evaluation.harness.validate
"""

import json
import re
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from evaluation.harness.catalog import SYSTEMS
from evaluation.harness.scenario import (
    KNOWLEDGE_FIELDS,
    Scenario,
    ScenarioFormatError,
    parse_scenario,
)

SYSTEM_FIELD = "system"
SYSTEM_CODE_FIELD = "systemCode"
# 질문이 요청하는 필드 이름. 시스템은 `system`이고 시나리오에서는 `systemCode`로 쓴다.
QUESTION_FIELDS = (SYSTEM_FIELD, *(name for name in KNOWLEDGE_FIELDS if name != SYSTEM_CODE_FIELD))
INTENTS = (
    "HOW_TO",
    "ACCESS_REQUEST",
    "OBSERVED_SYMPTOM",
    "FEATURE_REQUEST",
    "IMPROVEMENT_REQUEST",
    "UNKNOWN",
    "OUT_OF_SCOPE",
)
# 문제가 끝나는 상태. 호출 순서가 아니라 허용되는 끝 상태의 집합이다.
FINAL_ACTIONS = (
    "ANSWER_GUIDE",
    "PROPOSE_TICKET",
    "AWAIT_SYSTEM",
    "NOTICE_OUT_OF_SCOPE",
    "FAIL_RETRYABLE",
)
VERDICTS = ("SUFFICIENT", "PARTIAL", "INSUFFICIENT", "NO_MATCH")
BEHAVIOR_PROPERTIES = ("B1", "B2", "B3", "B4", "B5", "B6")
SYSTEM_CODES = tuple(system["code"] for system in SYSTEMS)
EVIDENCE_PATTERN = re.compile(
    r"^(?P<key>(guide|policy|runbook)-[a-z]+-[a-z0-9-]+)@(?P<version>[1-9]\d*)$"
)
SCENARIO_ID_PATTERN = re.compile(r"^S-(?P<family>[a-z0-9-]+)-001$")

GOLD_KEYS = {"scenarioId", "problems", "behaviorProperties"}
PROBLEM_KEYS = {
    "problemKey",
    "acceptedIntents",
    "reportedSystemCode",
    "responsibleSystemCodes",
    "requiredFields",
    "mustNotAskFields",
    "allowedFinalActions",
    "assessment",
    "relevantEvidence",
    "forbiddenEvidence",
    "afterFollowUp",
}


def _subset(name: str, values: Any, allowed: Sequence[str], errors: list[str]) -> list[str]:
    if not isinstance(values, list):
        errors.append(f"{name}은 목록이어야 한다")
        return []
    unknown = [value for value in values if value not in allowed]
    if unknown:
        errors.append(f"{name}에 허용되지 않는 값이 있다: {unknown}")
    if len(values) != len(set(map(str, values))):
        errors.append(f"{name}에 중복이 있다")
    return [value for value in values if value in allowed]


def _evidence(
    name: str, values: Any, document_keys: Mapping[str, str], errors: list[str]
) -> list[str]:
    if not isinstance(values, list):
        errors.append(f"{name}은 목록이어야 한다")
        return []
    for value in values:
        match = EVIDENCE_PATTERN.match(str(value))
        if not match:
            errors.append(f"{name}의 형식이 `문서키@버전`이 아니다: {value}")
        elif match["key"] not in document_keys:
            errors.append(f"{name}의 문서가 카탈로그에 없다: {value}")
    if len(values) != len(set(map(str, values))):
        errors.append(f"{name}에 중복이 있다")
    return [str(value) for value in values]


def _known_or_unknown(scenario: Scenario, field: str) -> bool:
    """사용자가 이 질문 필드에 답할 수 있는지(값이 있거나 모른다고 선언했는지)."""
    scenario_field = SYSTEM_CODE_FIELD if field == SYSTEM_FIELD else field
    return (
        bool(scenario.user_knowledge.get(scenario_field))
        or scenario_field in scenario.unknown_fields
    )


def _validate_problem(
    prefix: str,
    problem: Mapping[str, Any],
    scenario: Scenario,
    document_keys: Mapping[str, str],
    errors: list[str],
) -> None:
    extra = set(problem) - PROBLEM_KEYS
    if extra:
        errors.append(f"{prefix}: 알 수 없는 키 {sorted(extra)}")
    for key in sorted(PROBLEM_KEYS - {"afterFollowUp", "assessment"}):
        if key not in problem:
            errors.append(f"{prefix}: 필수 키가 없다 {key}")
    if "assessment" not in problem:
        errors.append(f"{prefix}: 필수 키가 없다 assessment(해당 없으면 null)")
    intents = _subset(f"{prefix}.acceptedIntents", problem.get("acceptedIntents"), INTENTS, errors)
    if not intents:
        errors.append(f"{prefix}: acceptedIntents가 비어 있다")
    reported = problem.get("reportedSystemCode")
    if reported is not None and reported not in SYSTEM_CODES:
        errors.append(f"{prefix}: 알 수 없는 reportedSystemCode {reported}")
    _subset(
        f"{prefix}.responsibleSystemCodes",
        problem.get("responsibleSystemCodes"),
        SYSTEM_CODES,
        errors,
    )
    required = _subset(
        f"{prefix}.requiredFields", problem.get("requiredFields"), QUESTION_FIELDS, errors
    )
    must_not = _subset(
        f"{prefix}.mustNotAskFields", problem.get("mustNotAskFields"), QUESTION_FIELDS, errors
    )
    both = sorted(set(required) & set(must_not))
    if both:
        errors.append(f"{prefix}: 필수이면서 묻지 않아야 하는 필드 {both}")
    for field in required:
        if not _known_or_unknown(scenario, field):
            errors.append(
                f"{prefix}: 필수 필드 {field}에 사용자가 답할 값이 없다(알거나 모른다고 선언)"
            )
    actions = _subset(
        f"{prefix}.allowedFinalActions", problem.get("allowedFinalActions"), FINAL_ACTIONS, errors
    )
    if not actions:
        errors.append(f"{prefix}: allowedFinalActions가 비어 있다")
    assessment = problem.get("assessment")
    if assessment is not None:
        if not isinstance(assessment, dict) or set(assessment) != {
            "acceptedVerdicts",
            "missingInformation",
        }:
            errors.append(
                f"{prefix}.assessment는 null이거나 acceptedVerdicts·missingInformation을 가진다"
            )
        else:
            if not _subset(
                f"{prefix}.assessment.acceptedVerdicts",
                assessment["acceptedVerdicts"],
                VERDICTS,
                errors,
            ):
                errors.append(f"{prefix}: acceptedVerdicts가 비어 있다")
            missing = _subset(
                f"{prefix}.assessment.missingInformation",
                assessment["missingInformation"],
                QUESTION_FIELDS,
                errors,
            )
            declared_unknown = {
                (SYSTEM_FIELD if name == SYSTEM_CODE_FIELD else name)
                for name in scenario.unknown_fields
            }
            lost = sorted((set(required) & declared_unknown) - set(missing))
            if lost:
                errors.append(
                    f"{prefix}: 사용자가 모른다고 한 필수 필드가 missingInformation에 없다 {lost}"
                )
    if "RETRIEVAL_ERROR" in scenario.faults and assessment is not None:
        errors.append(f"{prefix}: 검색 오류를 주입한 시나리오는 assessment가 null이어야 한다")
    relevant = _evidence(
        f"{prefix}.relevantEvidence", problem.get("relevantEvidence"), document_keys, errors
    )
    forbidden = _evidence(
        f"{prefix}.forbiddenEvidence", problem.get("forbiddenEvidence"), document_keys, errors
    )
    if set(relevant) & set(forbidden):
        errors.append(f"{prefix}: 관련 근거이면서 금지 근거인 문서가 있다")
    for item in relevant:
        match = EVIDENCE_PATTERN.match(item)
        if match and document_keys.get(match["key"]) == "SYSTEM_STAFF":
            errors.append(f"{prefix}: 담당자 전용 문서는 relevantEvidence에 둘 수 없다 {item}")
    follow = problem.get("afterFollowUp")
    if follow is not None:
        if not isinstance(follow, dict) or set(follow) != {
            "allowedFinalActions",
            "mustNotRepeatEvidence",
        }:
            errors.append(
                f"{prefix}.afterFollowUp은 allowedFinalActions·mustNotRepeatEvidence를 가진다"
            )
        else:
            if not _subset(
                f"{prefix}.afterFollowUp.allowedFinalActions",
                follow["allowedFinalActions"],
                FINAL_ACTIONS,
                errors,
            ):
                errors.append(f"{prefix}: afterFollowUp.allowedFinalActions가 비어 있다")
            _evidence(
                f"{prefix}.afterFollowUp.mustNotRepeatEvidence",
                follow["mustNotRepeatEvidence"],
                document_keys,
                errors,
            )


def validate_gold(
    gold: Mapping[str, Any], scenario: Scenario, document_keys: Mapping[str, str]
) -> list[str]:
    """정답 하나를 검사해 오류 문장 목록을 돌려준다. 비어 있으면 통과다."""
    errors: list[str] = []
    extra = set(gold) - GOLD_KEYS
    if extra:
        errors.append(f"알 수 없는 키 {sorted(extra)}")
    if gold.get("scenarioId") != scenario.scenario_id:
        errors.append(
            f"scenarioId가 시나리오와 다르다: {gold.get('scenarioId')} != {scenario.scenario_id}"
        )
    problems = gold.get("problems")
    if not isinstance(problems, list) or not problems:
        errors.append("problems가 비어 있다")
        problems = []
    keys = [problem.get("problemKey") for problem in problems if isinstance(problem, dict)]
    if len(keys) != len(set(keys)):
        errors.append("problemKey가 중복이다")
    for index, problem in enumerate(problems):
        if not isinstance(problem, dict):
            errors.append(f"problems[{index}]이 객체가 아니다")
            continue
        _validate_problem(f"problems[{index}]", problem, scenario, document_keys, errors)
    behaviors = _subset(
        "behaviorProperties", gold.get("behaviorProperties"), BEHAVIOR_PROPERTIES, errors
    )
    if scenario.corrections and "B6" not in behaviors:
        errors.append("정정이 있는 시나리오는 behaviorProperties에 B6이 있어야 한다")
    has_follow_up = any(isinstance(item, dict) and item.get("afterFollowUp") for item in problems)
    if has_follow_up != (scenario.follow_up is not None):
        errors.append("afterFollowUp은 시나리오에 followUp이 있을 때만, 그리고 있으면 반드시 둔다")
    return errors


def validate_files(
    scenario_dir: Path, gold_dir: Path, document_keys: Mapping[str, str]
) -> dict[str, list[str]]:
    """디렉터리 단위 검사. 시나리오 ID별 오류 목록(없으면 빈 목록)을 돌려준다."""
    results: dict[str, list[str]] = {}
    scenario_files = {path.stem: path for path in sorted(scenario_dir.glob("*.json"))}
    gold_files = {path.stem: path for path in sorted(gold_dir.glob("*.json"))}
    for name in sorted(set(scenario_files) | set(gold_files)):
        errors: list[str] = []
        results[name] = errors
        if name not in scenario_files:
            errors.append("시나리오 파일이 없다")
            continue
        if name not in gold_files:
            errors.append("정답 파일이 없다")
            continue
        try:
            scenario = parse_scenario(json.loads(scenario_files[name].read_text(encoding="utf-8")))
        except (ScenarioFormatError, json.JSONDecodeError) as exc:
            errors.append(f"시나리오를 읽을 수 없다: {exc}")
            continue
        if scenario.scenario_id != name:
            errors.append(f"파일 이름이 scenarioId와 다르다: {name} != {scenario.scenario_id}")
        match = SCENARIO_ID_PATTERN.match(scenario.scenario_id)
        if not match or match["family"] != scenario.case_family:
            errors.append("scenarioId가 `S-{caseFamily}-001` 형식이 아니다")
        gold = json.loads(gold_files[name].read_text(encoding="utf-8"))
        errors.extend(validate_gold(gold, scenario, document_keys))
    return results


def parse_document_catalog(markdown: str) -> dict[str, str]:
    """가상 기업 정본 6절의 문서 표에서 `documentKey -> 공개 범위`를 읽는다."""
    keys: dict[str, str] = {}
    for line in markdown.splitlines():
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) >= 6 and re.fullmatch(
            r"`(guide|policy|runbook)-[a-z]+-[a-z0-9-]+`", cells[0]
        ):
            keys[cells[0].strip("`")] = cells[2]
    return keys


def parse_scenario_catalog(markdown: str) -> list[dict[str, Any]]:
    """가상 기업 정본 8절의 시나리오 표를 읽는다. `evidence`는 근거 문서 키 목록이다."""
    rows: list[dict[str, Any]] = []
    for line in markdown.splitlines():
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) == 8 and cells[0].isdigit() and cells[1].startswith("`"):
            rows.append(
                {
                    "number": int(cells[0]),
                    "caseFamily": cells[1].strip("`"),
                    "split": cells[7],
                    "evidence": re.findall(r"`([a-z]+-[a-z]+-[a-z0-9-]+)`", cells[6]),
                }
            )
    return rows


def main() -> int:
    ai_root = Path(__file__).resolve().parents[2]
    catalog = (ai_root.parent / "docs" / "knowledge" / "VIRTUAL_COMPANY.md").read_text(
        encoding="utf-8"
    )
    results = validate_files(
        ai_root / "evaluation" / "scenarios",
        ai_root / "evaluation" / "golds",
        parse_document_catalog(catalog),
    )
    failed = {name: errors for name, errors in results.items() if errors}
    for name, errors in failed.items():
        print(name)
        for error in errors:
            print(f"  - {error}")
    print(f"검사 {len(results)}건, 오류가 있는 시나리오 {len(failed)}건")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
