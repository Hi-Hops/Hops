"""Spring이 처리 기록을 저장한 뒤 만드는 CASE_DRAFT 입력을 흉내 내는 대역(`CurationInputFactory`).

시나리오(문의 전체 과정)에서 `AiRunRequest`를 만든다. 실제 Spring의 조회·권한 확인은 하지
않으므로 이 입력으로
잰 결과를 Spring 연동의 증거로 쓰지 않는다. 식별자는 시나리오 ID에서 정해지는 고정값이다.
입력에는 정답이나 사례
카드의 사례 문구·연결이 들어가지 않는다.
"""

from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any
from uuid import NAMESPACE_URL, uuid5

import yaml

from evaluation.curator.candidates import CatalogNode, candidates_for, system_id
from evaluation.curator.scenario import CuratorScenario
from evaluation.harness.catalog import SYSTEMS
from evaluation.harness.corpus_source import DEFAULT_CORPUS_DIR
from evaluation.harness.spring_stub import DEFAULT_LIMITS
from hops_ai.contracts.wire import AiRunRequest

BASE_TIME = datetime(2026, 11, 2, 0, 0, 0, tzinfo=UTC)
MAX_EXCERPT = 300
KNOWLEDGE_ID_PREFIX = "hops-knowledge.example/"


def _id(scenario: CuratorScenario, role: str) -> str:
    return str(uuid5(NAMESPACE_URL, f"hops-eval-curator.example/{scenario.scenario_id}/{role}"))


def _time(moment: datetime) -> str:
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def message_id(scenario: CuratorScenario, number: int) -> str:
    """메시지 번호(1부터)의 ID. 정답과 검사에서 같은 규칙으로 쓴다."""
    return _id(scenario, f"message/{number}")


def record_id(scenario: CuratorScenario, index: int) -> str:
    return _id(scenario, f"record/{index}")


def _document(key: str, corpus_dir: Path) -> tuple[dict[str, Any], str]:
    matches = list(corpus_dir.glob(f"*/{key}.md"))
    if len(matches) != 1:
        raise ValueError(f"상담 근거 문서를 찾을 수 없다: {key}")
    parts = matches[0].read_text(encoding="utf-8").split("---", 2)
    return yaml.safe_load(parts[1]), parts[2].strip()


def consulted_evidence(
    scenario: CuratorScenario, corpus_dir: Path = DEFAULT_CORPUS_DIR
) -> list[dict[str, Any]]:
    """상담에 쓴 문서를 `KnowledgeEvidence` 형태로 만든다(Spring이 현재 권한으로 다시 확인해
    보낸 값의 대역)."""
    system_ids = {system["code"]: system["id"] for system in SYSTEMS}
    items = []
    for reference in scenario.consulted_knowledge:
        key, _, version = reference.partition("@")
        meta, body = _document(key, corpus_dir)
        code = meta.get("systemCode")
        items.append(
            {
                "id": _id(scenario, f"evidence/{reference}"),
                "documentId": str(uuid5(NAMESPACE_URL, f"{KNOWLEDGE_ID_PREFIX}{key}")),
                "documentVersion": int(version or meta["version"]),
                "kind": meta["kind"],
                "title": meta["title"],
                "excerpt": " ".join(body.split())[:MAX_EXCERPT],
                "systemId": system_ids.get(code),
                "appliesToSystemVersion": meta.get("appliesToSystemVersion"),
                "applicability": meta["applicability"],
                "accessScope": meta["accessScope"],
                "origin": meta["origin"],
            }
        )
    return items


def _details(scenario: CuratorScenario) -> dict[str, Any]:
    names = (
        "taskOrScreen",
        "requestedAccess",
        "businessPurpose",
        "expectedBehavior",
        "actualBehavior",
        "occurredAt",
        "currentWorkflow",
        "desiredChange",
    )
    return {**{name: scenario.details.get(name) for name in names}, "acceptanceCriteria": []}


def build_request(
    scenario: CuratorScenario,
    catalog: dict[str, CatalogNode],
    *,
    now: datetime | None = None,
    include_candidates: bool = True,
) -> AiRunRequest:
    """시나리오의 CASE_DRAFT 입력. 원 기록은 마지막 처리 기록이고 앞선 기록은
    맥락(relatedRecords)이다."""
    sys_id = system_id(scenario.system_code)
    problem_id = _id(scenario, "problem")
    actor_id = _id(scenario, "actor")
    moment = now or datetime.now(UTC)
    messages = [
        {
            "id": message_id(scenario, number),
            "revision": 1,
            "sequenceNo": number,
            "sender": item.sender,
            "content": item.text,
            "problemIds": [problem_id],
            "createdAt": _time(BASE_TIME + timedelta(minutes=number)),
        }
        for number, item in enumerate(scenario.messages, start=1)
    ]
    knowledge = consulted_evidence(scenario)
    origin_index = len(scenario.records) - 1
    origin = scenario.records[origin_index]
    related = [
        {
            "id": record_id(scenario, index),
            "kind": record.kind,
            "action": record.action,
            "verification": record.verification,
            "confirmedCause": record.confirmed_cause,
            "appliesToSystemVersion": record.applies_to_system_version,
            "createdAt": _time(BASE_TIME + timedelta(hours=index + 1)),
        }
        for index, record in enumerate(scenario.records[:origin_index])
    ]
    history = [
        {
            "requestId": _id(scenario, f"resolution/{index}"),
            "source": entry.source,
            "sourceId": (
                message_id(scenario, 1) if entry.source == "GUIDE" else record_id(scenario, 0)
            ),
            "state": entry.state,
            "requestedAt": _time(BASE_TIME + timedelta(minutes=30 + index)),
            "respondedAt": _time(BASE_TIME + timedelta(minutes=40 + index)),
        }
        for index, entry in enumerate(scenario.resolution_history)
    ]
    link_candidates = (
        [
            {"id": node.id, "kind": node.kind, "label": node.label, "systemId": sys_id}
            for node in candidates_for(scenario.system_code, catalog)
        ]
        if include_candidates
        else []
    )
    questions = sum(1 for item in scenario.messages if item.sender == "AI")
    request = {
        "contractVersion": "hops-system-1",
        "runId": _id(scenario, "run"),
        "kind": "CASE_DRAFT",
        "inquiryId": _id(scenario, "inquiry"),
        "inquiryVersion": 1,
        "answerMessageId": None,
        "problemIds": [problem_id],
        "messages": messages,
        "problems": [
            {
                "id": problem_id,
                "summary": scenario.ticket_title,
                "intent": scenario.intent,
                "processingType": scenario.work_type,
                "reportedSystemId": sys_id,
                "reportedFeatureId": None,
                "state": "WAITING_CONFIRMATION",
                "questionCount": min(5, max(0, questions - 1)),
                "askedFields": [],
                "standardRequest": {
                    "summary": scenario.summary,
                    "confirmedFacts": [
                        {
                            "text": fact.text,
                            "sourceMessageIds": [message_id(scenario, n) for n in fact.messages],
                            "knowledgeEvidenceIds": [],
                        }
                        for fact in scenario.facts
                    ],
                    "assumptions": [],
                    "unknowns": list(scenario.unknowns),
                    "responsibleSystemCandidateId": sys_id,
                    "responsibilityEvidenceIds": [],
                    "details": _details(scenario),
                    # 실제 Spring은 티켓 요청서의 상담 근거를 범위로 걸러 싣는다. 대역은 상담
                    # 근거 전체를 싣는다.
                    "knowledge": knowledge,
                },
                "resolutionContext": None,
            }
        ],
        "systems": [dict(system) for system in SYSTEMS if system["code"] == scenario.system_code],
        "accessContext": {
            "actorId": actor_id,
            "purpose": "CASE_DRAFT",
            "systemIds": [sys_id],
            # 실제 Spring과 같다: 실행 주체(주담당자)가 볼 수 있는 범위. 사례 자체의 공개 범위는
            # 담당자 전용이다.
            "allowedDocumentScopes": ["EMPLOYEE", "SYSTEM_STAFF"],
            "policyRevision": 1,
        },
        "existingWorkItems": [
            {
                "workItemId": problem_id,
                "problemId": problem_id,
                "inquiryVersion": 1,
                "outcome": "STORED",
                "ticketId": _id(scenario, "ticket"),
            }
        ],
        "workRecord": {
            "id": record_id(scenario, origin_index),
            "ticketId": _id(scenario, "ticket"),
            "problemId": problem_id,
            "systemId": sys_id,
            "kind": origin.kind,
            "action": origin.action,
            "verification": origin.verification,
            "createdAt": _time(BASE_TIME + timedelta(hours=origin_index + 1)),
            "resolutionRequestId": _id(scenario, "resolution/current"),
            "confirmationState": scenario.confirmation_state,
            "confirmedCause": origin.confirmed_cause,
            "appliesToSystemVersion": origin.applies_to_system_version,
            "relatedRecords": related,
            "resolutionHistory": history,
            "consultedKnowledge": knowledge,
            "linkCandidates": link_candidates,
        },
        "expiresAt": _time(moment + timedelta(hours=1)),
        "limits": dict(DEFAULT_LIMITS),
    }
    return AiRunRequest.model_validate(request)
