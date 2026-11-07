"""사례를 연결할 수 있는 기존 증상·확인 항목의 평가용 카탈로그.

코퍼스 런북·정책 front matter의 선언(`symptoms`, `checks`)이 원본이고 ID는 Spring 시드와 같은
규칙(`uuid5(NAMESPACE_URL, "hops-relation.example/" + 종류 + "/" + 키)`)으로 만든다. 평가에서
Spring의 후보 조회(`RelationLinkCandidateReader`)를 대신하는 대역이며 실제 조회가 아니다.
시스템의 승인된 노드를 모두 후보로 준다(현재 규모에서는 종류별 상한 아래다).
"""

from dataclasses import dataclass
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

import yaml

from evaluation.harness.catalog import SYSTEMS
from evaluation.harness.corpus_source import DEFAULT_CORPUS_DIR

ID_NAMESPACE_PREFIX = "hops-relation.example/"
SYMPTOM = "SYMPTOM"
CHECK_ITEM = "CHECK_ITEM"
_KIND_NAMES = {SYMPTOM: "symptom", CHECK_ITEM: "check"}
_SYSTEM_IDS = {system["code"]: system["id"] for system in SYSTEMS}


class CatalogError(ValueError):
    pass


@dataclass(frozen=True)
class CatalogNode:
    key: str
    id: str
    kind: str
    label: str
    system_code: str


def node_id(kind: str, key: str) -> str:
    return str(uuid5(NAMESPACE_URL, f"{ID_NAMESPACE_PREFIX}{_KIND_NAMES[kind]}/{key}"))


def _front_matter(path: Path) -> dict:
    parts = path.read_text(encoding="utf-8").split("---", 2)
    if len(parts) < 3 or parts[0].strip():
        raise CatalogError(f"{path.name}: front matter가 없다")
    data = yaml.safe_load(parts[1])
    if not isinstance(data, dict):
        raise CatalogError(f"{path.name}: front matter가 객체가 아니다")
    return data


def load_catalog(corpus_dir: Path = DEFAULT_CORPUS_DIR) -> dict[str, CatalogNode]:
    """키 → 노드. 승인된 문서가 선언한 증상·확인 항목만 담는다."""
    nodes: dict[str, CatalogNode] = {}
    for path in sorted(corpus_dir.glob("*/*.md")):
        meta = _front_matter(path)
        if meta.get("reviewStatus") != "APPROVED" or meta.get("dataSplit") != "RAG":
            continue
        system_code = meta.get("systemCode")
        for kind, items in ((SYMPTOM, meta.get("symptoms")), (CHECK_ITEM, meta.get("checks"))):
            for item in items or []:
                key = item["id"]
                if key in nodes:
                    raise CatalogError(f"{key}: 같은 노드를 둘 이상의 문서가 선언했다")
                if system_code not in _SYSTEM_IDS:
                    raise CatalogError(f"{path.name}: 알 수 없는 시스템 {system_code}")
                nodes[key] = CatalogNode(key, node_id(kind, key), kind, item["label"], system_code)
    return nodes


def candidates_for(system_code: str, catalog: dict[str, CatalogNode]) -> list[CatalogNode]:
    """시스템의 후보. 종류(증상 먼저)와 키 순서로 고정한다."""
    return sorted(
        (node for node in catalog.values() if node.system_code == system_code),
        key=lambda node: (node.kind != SYMPTOM, node.key),
    )


def system_id(system_code: str) -> str:
    return _SYSTEM_IDS[system_code]
