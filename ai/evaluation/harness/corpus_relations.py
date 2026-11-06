"""평가용 관계 검색원: 코퍼스의 증상·확인 항목 선언과 질문 카드를 비슷하게 찾는다.

관계 데이터의 원본(`docs/knowledge/corpus` 런북·정책의 front matter 선언과
`relations/questions.yml`의 직원용 질문 문구)을 직접 읽는 하네스 전용 대역이다.
운영 코드가 아니며 `hops_ai`에서 불러오지 않는다. 실제 Spring 조회와 같지 않은 근사다.

맞춘 것: 점수(기능 후보 일치 0.5 + 0.5 × 키워드가 증상 이름에 든 비율), 점수 0 제외, 점수·카탈로그
키 순서, 건수 제한, 승인된 문서·직원 범위 처리(직원 범위에서는 직원 공개 확인 항목만, 이름과 원인은
없이, 승인된 질문 문구만). 맞추지 않은 것: DB 조회, 권한 재확인, 사례 이웃, 문서 버전 이력.
그래서 이 검색원으로 잰 결과를 Spring 관계 조회의 증거로 쓰지 않는다.
"""

from collections.abc import Mapping
from pathlib import Path
from typing import Any

import yaml

from evaluation.harness.catalog import SYSTEMS
from evaluation.harness.corpus_source import DEFAULT_CORPUS_DIR, query_tokens
from hops_ai.services.retrieval.relation_source import (
    Basis,
    CheckItem,
    Question,
    RelationQuery,
    RelationResult,
    Symptom,
)

DEFAULT_QUESTIONS = DEFAULT_CORPUS_DIR.parent / "relations" / "questions.yml"
# 기능 코드 → 하네스 카탈로그의 기능 이름(DB seed의 기능 코드와 같은 대응. VIRTUAL_COMPANY.md 5절)
FEATURE_NAMES = {
    "DOCUMENT_CREATE": "문서 작성",
    "DOCUMENT_VIEW": "문서 조회",
    "APPROVAL_STATUS": "결재 상태",
    "ATTACHMENT_VIEW": "첨부 열람",
    "LOGIN_SSO": "로그인·SSO",
    "ACCOUNT_ACCESS": "계정 접근",
    "PERMISSION_REQUEST": "권한 신청",
    "FILE_VIEW": "파일 열람",
    "FILE_SHARE": "파일 공유",
    "ACCESS_PERMISSION": "접근 권한",
}


class CorpusRelationError(ValueError):
    pass


def _front_matter(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    parts = text.split("---", 2)
    if len(parts) < 3 or parts[0].strip():
        raise CorpusRelationError(f"{path.name}: front matter가 없다")
    data = yaml.safe_load(parts[1])
    if not isinstance(data, dict):
        raise CorpusRelationError(f"{path.name}: front matter가 객체가 아니다")
    return data


def _system_ids() -> dict[str, str]:
    return {system["code"]: system["id"] for system in SYSTEMS}


def _feature_ids() -> dict[tuple[str, str], str]:
    names = {code: name for code, name in FEATURE_NAMES.items()}
    return {
        (system["code"], code): feature["id"]
        for system in SYSTEMS
        for feature in system["features"]
        for code, name in names.items()
        if feature["name"] == name
    }


def _questions(path: Path) -> dict[str, list[dict[str, Any]]]:
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    result: dict[str, list[dict[str, Any]]] = {}
    for entry in data.get("questions", []):
        result[entry["checkId"]] = [
            template
            for template in entry.get("templates", [])
            if template.get("employeeDisclosable") is True
            and template.get("reviewStatus") == "APPROVED"
        ]
    return result


class CorpusRelationSource:
    """직원 상담 범위의 관계 조회 대역. 담당자 범위 요청에는 쓰지 않는다."""

    def __init__(
        self,
        corpus_dir: Path = DEFAULT_CORPUS_DIR,
        questions_path: Path = DEFAULT_QUESTIONS,
    ) -> None:
        systems = _system_ids()
        features = _feature_ids()
        questions = _questions(questions_path)
        self._symptoms: list[Symptom] = []
        for path in sorted(corpus_dir.glob("*/*.md")):
            meta = _front_matter(path)
            if meta.get("reviewStatus") != "APPROVED" or meta.get("dataSplit") != "RAG":
                continue
            declared = meta.get("symptoms") or []
            if not declared:
                continue
            system_code = meta["systemCode"]
            checks = {item["id"]: item for item in meta.get("checks") or []}
            for item in declared:
                feature_id = features.get((system_code, item["featureCode"]))
                if feature_id is None:
                    raise CorpusRelationError(
                        f"{path.name}: 알 수 없는 기능 코드 {item['featureCode']}"
                    )
                self._symptoms.append(
                    self._symptom(
                        item, system_code, systems[system_code], feature_id, checks, questions
                    )
                )

    @staticmethod
    def _symptom(
        item: Mapping[str, Any],
        system_code: str,
        system_id: str,
        feature_id: str,
        checks: Mapping[str, Mapping[str, Any]],
        questions: Mapping[str, list[dict[str, Any]]],
    ) -> Symptom:
        check_ids: list[str] = []
        for cause in item.get("causes") or []:
            for check_id in cause.get("checks") or []:
                if check_id not in check_ids:
                    check_ids.append(check_id)
        views = []
        for check_id in check_ids:
            check = checks.get(check_id)
            # 직원 범위에는 직원 공개 확인 항목만, 이름 없이, 승인된 질문 문구와 함께 간다.
            if check is None or check.get("employeeDisclosable") is not True:
                continue
            templates = questions.get(check_id, [])
            if not templates:
                continue
            views.append(
                CheckItem(
                    id=check_id,
                    catalogKey=check_id,
                    label=None,
                    employeeDisclosable=True,
                    accessScope="EMPLOYEE",
                    basis=Basis(visible=True),
                    questions=[
                        Question(
                            id=template["key"],
                            catalogKey=template["key"],
                            text=template["text"],
                            basis=Basis(visible=True),
                        )
                        for template in templates
                    ],
                )
            )
        return Symptom(
            id=item["id"],
            catalogKey=item["id"],
            label=item["label"],
            systemId=system_id,
            featureId=feature_id,
            featureCode=item["featureCode"],
            accessScope="EMPLOYEE",
            basis=Basis(visible=True),
            score=0.0,
            checkItems=views,
            causes=None,
        )

    async def search(self, query: RelationQuery) -> RelationResult:
        tokens = query_tokens(" ".join(query.keywords))
        scored: list[tuple[float, Symptom]] = []
        for symptom in self._symptoms:
            if symptom.systemId not in query.system_ids:
                continue
            feature_match = symptom.featureId in query.feature_ids
            label = symptom.label.lower()
            matched = sum(1 for token in tokens if token in label)
            if not feature_match and matched == 0:
                continue
            ratio = matched / len(tokens) if tokens else 0.0
            score = (0.5 if feature_match else 0.0) + 0.5 * ratio
            scored.append((score, symptom.model_copy(update={"score": score})))
        scored.sort(key=lambda item: (-item[0], item[1].catalogKey))
        return RelationResult(
            symptoms=tuple(symptom for _, symptom in scored[: query.limit]),
            internal_judgement=False,
        )
