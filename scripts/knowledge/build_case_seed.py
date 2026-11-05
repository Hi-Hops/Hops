# /// script
# requires-python = ">=3.13"
# dependencies = ["pyyaml>=6"]
# ///
"""시드 해결 사례 카드(YAML)를 과거 처리 이력 체인과 사례를 담은 seed SQL로 바꾼다.

    uv run scripts/knowledge/build_case_seed.py          # SQL 파일을 다시 만든다
    uv run scripts/knowledge/build_case_seed.py --check  # 카드·코퍼스·정본과 SQL이 어긋났는지만 검사한다

원본은 docs/knowledge/cases/*.yml 이고, 결과는
backend/spring/hops/src/main/resources/db/seed/R__seed_dev_knowledge_cases.sql 이다. 결과 파일은 직접 고치지 않는다.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import sys
import uuid
from datetime import UTC, timedelta
from pathlib import Path

from build_knowledge_seed import DEFAULT_OUTPUT as KNOWLEDGE_OUTPUT
from cases import (
    MEMBER_IDS,
    CaseCard,
    check_cards_against_canon,
    load_cards,
    validate_cards,
)
from corpus import REPO_ROOT, check_against_canon, load_corpus, validate_corpus
from sqlutil import sql_json, sql_nullable, sql_string, sql_timestamp

DEFAULT_OUTPUT = KNOWLEDGE_OUTPUT.with_name("R__seed_dev_knowledge_cases.sql")
CONTRACT_VERSION = "hops-system-1"
SEED_MODEL_VERSION = "seed-synthetic"

HEADER = """\
-- ⚠️ 로컬 개발용 가상 데이터 (실제 문의·실제 처리 기록이 아님)
-- ⚠️ 자동 생성 파일이다. 직접 고치지 않는다. 원본: docs/knowledge/cases/*.yml (사례 카드)
--    다시 만들기: uv run scripts/knowledge/build_case_seed.py
--    어긋남 검사:  uv run scripts/knowledge/build_case_seed.py --check
-- 값의 정본은 docs/knowledge/VIRTUAL_COMPANY.md 7절(시드 해결 사례)이다.
-- db 프로필에서만 실행된다. 테스트 DB·운영에는 들어가지 않는다.
--
-- R__ 파일 실행 순서: department_member → it_system → knowledge → knowledge_cases (이름 순).
--
-- 사례 한 건은 과거에 해결됐다고 가정한 처리 이력 체인이다. resolved_case.source_work_log_id가 필수이고
-- 사례 조회 API가 큐레이터 실행과 해결 확인 이벤트를 읽기 때문에 아래 행을 모두 만든다.
--   문의 → 메시지 4 → 상담 실행 2 → 문제 → 표준 요청 → 티켓 → 배정 → 처리 기록 2 → 티켓 이벤트 2
--   → 큐레이터 실행 → 사례 → 지식 문서(CASE) → 문서 버전
-- 번호는 SEED- 접두사로 실제 번호(Q-00000001, T-0000000000000001)와 구분한다.
-- 해결 확인은 합성 설정이라 confirmationOrigin = SYNTHETIC_PRESET 으로 표시한다.
--
-- 사례는 승인(APPROVED)이고 지식 문서(CASE) 스냅샷은 실제 승인 거래(CaseApprovalWriter)와 같은 형식이다: 본문, 승인 스냅샷의
-- digest, 저장소별 반영 요청(indexRequests)을 보존한다. 반영 요청은 저장만 했고 AI에 전달하지 않았다(승인 직후 전달 전 상태).
-- 그래서 vector_status·graph_status는 PENDING이다. Vector·Neo4j에 색인하지 않았으므로 SUCCEEDED로 적는 것은 사실이 아니다.
-- 현재 공개 판정은 둘 다 SUCCEEDED를 요구하므로 아직 검색에는 나오지 않는다(docs/knowledge/VIRTUAL_COMPANY.md 10절 열린 문제 1).
--
-- 모든 INSERT는 ON CONFLICT DO NOTHING이라 이미 있는 행은 바꾸지 않는다. 카드를 고치면 팀원은 DB를 새로 만들어 적재한다
-- (backend/spring/hops/README.md "처음 상태로 되돌리기").
"""


def system_id(code: str) -> str:
    """docs/DATA.md 2절: uuid5(NAMESPACE_URL, "hops-system-1.example/" + SystemCode)."""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"hops-system-1.example/{code}"))


def member_ref(login_id: str) -> str:
    return f"(SELECT id FROM member WHERE login_id = {sql_string(login_id)})"


def system_ref(code: str) -> str:
    return f"(SELECT id FROM it_system WHERE code = {sql_string(code)})"


def feature_ref(system_code: str, feature_code: str) -> str:
    return (
        "(SELECT f.id FROM system_feature f JOIN it_system s ON s.id = f.it_system_id "
        f"WHERE s.code = {sql_string(system_code)} AND f.code = {sql_string(feature_code)})"
    )


def instant(value) -> str:
    """Jackson이 Instant를 쓰는 형식(UTC, 초 단위)."""
    return value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class Chain:
    """카드 한 장에서 정해지는 ID와 시각."""

    def __init__(self, card: CaseCard) -> None:
        self.card = card
        self.data = card.data
        t0 = card.occurred_at
        n = card.seed_no
        self.inquiry_number = f"SEED-Q-{n:03d}"
        self.ticket_number = f"SEED-T-{n:03d}"
        self.case_number = f"SEED-C-{n:03d}"
        self.ids = {role: card.id_for(role) for role in (
            "inquiry", "msg1", "msg2", "msg3", "msg4", "run-a", "run-b", "run-curation", "problem", "standard-request",
            "ticket", "assignment", "log-progress", "log-resolved", "event-request", "event-response",
            "resolution-request", "case", "doc-version", "req1", "req3",
        )}
        self.t = {
            "msg1": t0, "run-a-start": t0 + timedelta(seconds=2), "run-a-end": t0 + timedelta(seconds=20),
            "msg2": t0 + timedelta(seconds=20), "msg3": t0 + timedelta(minutes=3),
            "run-b-start": t0 + timedelta(minutes=3, seconds=2), "run-b-end": t0 + timedelta(minutes=3, seconds=40),
            "msg4": t0 + timedelta(minutes=3, seconds=40), "ticket": t0 + timedelta(minutes=3, seconds=40),
            "progress": t0 + timedelta(hours=1), "resolved": t0 + timedelta(hours=2),
            "cur-start": t0 + timedelta(hours=2, seconds=5), "cur-end": t0 + timedelta(hours=2, minutes=1),
            "confirm": t0 + timedelta(hours=3), "approved": t0 + timedelta(days=1),
        }

    def id(self, role: str) -> str:
        return self.ids[role]

    @property
    def system(self) -> str:
        return self.data["system"]

    @property
    def route(self) -> str:
        return "REQUEST" if self.data["request"]["intent"] == "ACCESS_REQUEST" else "SYMPTOM"

    def message_id(self, ordinal: int) -> str:
        return self.ids[f"msg{ordinal}"]

    def source_message_ids(self) -> list[str]:
        return [self.message_id(n) for n in self.data["case"]["sourceMessages"]]


def message_payload(chain: Chain, ordinal: int) -> dict:
    turn = chain.data["dialogue"][ordinal - 1]
    base = {
        "contractVersion": CONTRACT_VERSION, "guide": None, "pendingTarget": False,
        "questionBundles": [], "problemLinks": [],
    }
    if turn["sender"] == "USER":
        length = len(turn["text"])
        request_role = "req1" if ordinal == 1 else "req3"
        return base | {
            "requestId": chain.id(request_role), "answerRunId": None,
            "problemLinks": [{"problemId": chain.id("problem"), "revision": 1, "spans": [{"start": 0, "end": length}]}],
            "reportedSystemId": system_id(chain.system) if ordinal == 1 else None,
            "revisionRequestIds": {}, "runRequestIds": {},
        }
    run_role = "run-a" if ordinal == 2 else "run-b"
    return base | {"requestId": None, "answerRunId": chain.id(run_role)}


def resolution_request(chain: Chain, *, responded: bool) -> dict:
    resolved = chain.data["workLogs"][1]
    return {
        "id": chain.id("resolution-request"), "inquiryId": chain.id("inquiry"), "problemId": chain.id("problem"),
        "ticketId": chain.id("ticket"), "source": "WORK_RECORD", "sourceId": chain.id("log-resolved"),
        "sourceVersion": 1, "requestRevision": 2 if responded else 1, "state": "RESOLVED" if responded else "PENDING",
        "resultSummary": resolved["action"], "confirmationOrigin": "SYNTHETIC_PRESET",
        "createdAt": instant(chain.t["resolved"]),
        "respondedAt": instant(chain.t["confirm"]) if responded else None,
    }


def evidence_statements(chain: Chain) -> list[dict]:
    return [
        {"text": fact["text"], "sourceMessageIds": [chain.message_id(n) for n in fact["messages"]], "knowledgeEvidenceIds": []}
        for fact in chain.data["request"].get("facts") or []
    ]


def canonical(value: object) -> str:
    """키 오름차순·공백 없는 JSON. Unicode는 escape하지 않고 배열 순서를 유지한다(CaseApprovalWriter.canonical과 같다)."""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def approved_case(chain: Chain) -> dict:
    """승인 시점 스냅샷(ApprovedCaseBody). CaseApprovalWriter.approvedCase와 같은 필드다."""
    d, case, resolved = chain.data, chain.data["case"], chain.data["workLogs"][1]
    return {
        "workRecordId": chain.id("log-resolved"),
        "resolutionRequestId": chain.id("resolution-request"),
        "approvedBy": MEMBER_IDS[d["assignee"]],
        "approvedAt": instant(chain.t["approved"]),
        "title": case["title"],
        "action": resolved["action"],
        "verification": resolved["verification"],
        "applicability": case["applicability"],
        "limitations": list(case["limitations"]),
        "appliesToSystemVersion": None,
        "accessScope": "SYSTEM_STAFF",
        "origin": "SYNTHETIC",
        "confirmationOrigin": "SYNTHETIC_PRESET",
        "sourceMessageIds": chain.source_message_ids(),
        "entityLinks": [{"relation": "APPLIES_TO", "targetId": system_id(chain.system), "evidenceIds": chain.source_message_ids()}],
        "systemId": system_id(chain.system),
        "confirmedCause": resolved["confirmedCause"],
        "problemStatement": case["problemStatement"],
        "caseType": "RESOLUTION",
    }


def case_document_content(chain: Chain) -> str:
    """사례 스냅샷(CASE 지식 문서)의 본문. 검색 조각의 원문이며 CaseApprovalWriter.content와 같은 순서다."""
    body = approved_case(chain)
    text = "".join(f"{body[field]}\n\n" for field in ("title", "problemStatement", "action", "verification", "applicability"))
    if body["limitations"]:
        text += "\n".join(body["limitations"])
    return text.strip()


def index_requests(chain: Chain) -> dict:
    """승인 거래가 저장하는 저장소별 반영 요청(IndexApplyRequest). 전달은 하지 않았고 jobId는 카드에서 정해지는 고정값이다."""
    body, case_id = approved_case(chain), chain.id("case")
    requests = {}
    for store in ("VECTOR", "NEO4J"):
        subject = {"caseId": case_id, "caseVersion": 1, "store": store, "approvedCase": body}
        requests[store] = {
            "contractVersion": CONTRACT_VERSION, "jobId": chain.card.id_for(f"index-job-{store.lower()}"),
            "caseId": case_id, "caseVersion": 1, "store": store, "attempt": 1,
            "contentDigest": sha256(canonical(subject)), "approvedCase": body,
        }
    return requests


def case_version_digest(chain: Chain) -> str:
    """문서 버전의 content_digest. 승인 스냅샷 전체의 digest이며 본문 digest와 다르다(CaseApprovalWriter와 같다)."""
    return sha256(canonical({"caseId": chain.id("case"), "caseVersion": 1, "approvedCase": approved_case(chain)}))


def render_case(card: CaseCard) -> str:
    c = Chain(card)
    d, req, tk, case = card.data, card.data["request"], card.data["ticket"], card.data["case"]
    progress, resolved = d["workLogs"]
    sys_code, feature = d["system"], d["feature"]
    sid = system_id(sys_code)
    t = c.t
    content = case_document_content(c)
    digest = case_version_digest(c)
    run_snapshot = {"seed": True, "note": "합성 시드. 실제 AI 실행이 아니다."}
    details = dict(req.get("details") or {}) | {"acceptanceCriteria": []}
    stmts: list[str] = [f"-- ===== {card.key} ({c.inquiry_number}, {c.ticket_number}, {c.case_number}) ====="]

    def add(sql: str) -> None:
        stmts.append(sql.rstrip().rstrip(";") + "\nON CONFLICT DO NOTHING;")

    add(f"""INSERT INTO inquiry (id, inquiry_number, requester_member_id, title, inquiry_status, version, current_run_id, result_revision, created_at, updated_at)
VALUES ({sql_string(c.id('inquiry'))}, {sql_string(c.inquiry_number)}, {member_ref(d['requester'])}, {sql_string(d['inquiryTitle'])},
        'RESOLVED', 2, NULL, 3, {sql_timestamp(t['msg1'])}, {sql_timestamp(t['approved'])})""")

    def message(ordinal: int) -> None:
        turn = d["dialogue"][ordinal - 1]
        stamp = t[f"msg{ordinal}"]
        add(f"""INSERT INTO message (id, inquiry_id, sequence_no, sender_type, content, revision, payload, message_status, created_at, updated_at)
VALUES ({sql_string(c.message_id(ordinal))}, {sql_string(c.id('inquiry'))}, {ordinal}, {sql_string(turn['sender'])}, {sql_string(turn['text'])},
        1, {sql_json(message_payload(c, ordinal))}, 'COMPLETED', {sql_timestamp(stamp)}, {sql_timestamp(stamp)})""")

    def analysis_run(role: str, trigger_ordinal: int, input_version: int, start: str, end: str) -> None:
        # 상담 실행의 입력에는 예약한 답변 메시지 ID(answerMessageId)가 고정된다(docs/DATA.md 11.3).
        snapshot = run_snapshot | {"answerMessageId": c.message_id(trigger_ordinal + 1)}
        add(f"""INSERT INTO agent_run (id, inquiry_id, trigger_message_id, task, route, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ({sql_string(c.id(role))}, {sql_string(c.id('inquiry'))}, {sql_string(c.message_id(trigger_ordinal))}, 'INQUIRY_ANALYSIS', {sql_string(c.route)},
        'SUCCEEDED', {input_version}, {sql_json(snapshot)}, {sql_string(SEED_MODEL_VERSION)}, 'seed', {sql_string(CONTRACT_VERSION)},
        {sql_json({'seed': True, 'applied': True, 'resources': []})}, {sql_timestamp(t[start])}, {sql_timestamp(t[end])}, {sql_timestamp(t[start])}, {sql_timestamp(t[end])})""")

    message(1)
    analysis_run("run-a", 1, 1, "run-a-start", "run-a-end")
    message(2)
    message(3)
    analysis_run("run-b", 3, 2, "run-b-start", "run-b-end")
    message(4)

    add(f"""INSERT INTO inquiry_problem (id, inquiry_id, problem_no, title, detail, problem_status, clarify_count, creation_outcome, creation_input_version, agent_run_id, created_at, updated_at)
VALUES ({sql_string(c.id('problem'))}, {sql_string(c.id('inquiry'))}, 1, {sql_string(tk['title'])}, {sql_string(req['summary'])},
        'TICKET_CREATED', 1, 'STORED', 2, {sql_string(c.id('run-b'))}, {sql_timestamp(t['ticket'])}, {sql_timestamp(t['ticket'])})""")

    candidates = [{"systemId": sid, "evidenceIds": [c.message_id(1)]}]
    add(f"""INSERT INTO standard_request (id, inquiry_problem_id, inquiry_version, agent_run_id, intent, work_type, reported_system_id, reported_feature_id, responsible_system_id, system_candidates, summary, confirmed_facts, assumptions, missing_items, type_detail, related_knowledge, created_at)
VALUES ({sql_string(c.id('standard-request'))}, {sql_string(c.id('problem'))}, 2, {sql_string(c.id('run-b'))}, {sql_string(req['intent'])}, {sql_string(req['workType'])},
        {system_ref(sys_code)}, {feature_ref(sys_code, feature)}, {system_ref(sys_code)}, {sql_json(candidates)}, {sql_string(req['summary'])},
        {sql_json(evidence_statements(c))}, '[]'::jsonb, {sql_json(req.get('missing') or [])}, {sql_json(details)}, '[]'::jsonb, {sql_timestamp(t['ticket'])})""")

    add(f"""INSERT INTO ticket (id, ticket_number, inquiry_id, inquiry_problem_id, standard_request_id, title, detail, work_type, ticket_status, responsible_system_id, assignee_member_id, confirm_requested_at, resolved_at, version, created_at, updated_at)
VALUES ({sql_string(c.id('ticket'))}, {sql_string(c.ticket_number)}, {sql_string(c.id('inquiry'))}, {sql_string(c.id('problem'))}, {sql_string(c.id('standard-request'))},
        {sql_string(tk['title'])}, {sql_string(tk['detail'])}, {sql_string(req['workType'])}, 'RESOLVED', {system_ref(sys_code)}, {member_ref(d['assignee'])},
        {sql_timestamp(t['resolved'])}, {sql_timestamp(t['confirm'])}, 3, {sql_timestamp(t['ticket'])}, {sql_timestamp(t['confirm'])})""")

    add(f"""INSERT INTO ticket_assignment (id, ticket_id, it_system_id, assignee_member_id, source, reason, evidence, assigned_at, created_at, updated_at)
VALUES ({sql_string(c.id('assignment'))}, {sql_string(c.id('ticket'))}, {system_ref(sys_code)}, {member_ref(d['assignee'])}, 'AUTO',
        '책임 시스템의 현재 주담당자에게 자동 배정(합성 시드)', {sql_json({'messageIds': [c.message_id(1), c.message_id(3)], 'note': None})},
        {sql_timestamp(t['ticket'])}, {sql_timestamp(t['ticket'])}, {sql_timestamp(t['ticket'])})""")

    add(f"""INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ({sql_string(c.id('log-progress'))}, {sql_string(c.id('ticket'))}, {member_ref(d['assignee'])}, 'PROGRESS', {sql_string(progress['action'])},
        NULL, NULL, NULL, {sql_timestamp(t['progress'])})""")
    add(f"""INSERT INTO ticket_work_log (id, ticket_id, author_member_id, log_type, action_detail, confirmed_cause, verification_result, applied_version, created_at)
VALUES ({sql_string(c.id('log-resolved'))}, {sql_string(c.id('ticket'))}, {member_ref(d['assignee'])}, 'RESOLVED', {sql_string(resolved['action'])},
        {sql_string(resolved['confirmedCause'])}, {sql_string(resolved['verification'])}, NULL, {sql_timestamp(t['resolved'])})""")

    pending = resolution_request(c, responded=False)
    answered = resolution_request(c, responded=True)
    add(f"""INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ({sql_string(c.id('event-request'))}, {sql_string(c.id('ticket'))}, 'STATUS_CHANGED', 'MEMBER', {member_ref(d['assignee'])},
        {sql_json({'state': 'IN_PROGRESS'})}, {sql_json({'state': 'WAITING_CONFIRM', 'resolutionRequest': pending})}, {sql_timestamp(t['resolved'])})""")
    add(f"""INSERT INTO ticket_event (id, ticket_id, event_type, actor_type, actor_member_id, before_data, after_data, created_at)
VALUES ({sql_string(c.id('event-response'))}, {sql_string(c.id('ticket'))}, 'USER_CONFIRMED', 'MEMBER', {member_ref(d['requester'])},
        {sql_json({'resolutionRequest': pending})}, {sql_json({'resolutionRequest': answered})}, {sql_timestamp(t['confirm'])})""")

    curation_result = {"seed": True, "caseProposal": {"reusable": True}}
    curation_validation = {
        "seed": True, "applied": True, "resources": [{"type": "CASE", "id": c.id("case")}], "caseGenerationState": "SUCCEEDED",
    }
    add(f"""INSERT INTO agent_run (id, inquiry_id, ticket_id, trigger_work_log_id, task, run_status, input_version, input_snapshot, model_version, prompt_version, schema_version, result, validation_result, started_at, completed_at, created_at, updated_at)
VALUES ({sql_string(c.id('run-curation'))}, {sql_string(c.id('inquiry'))}, {sql_string(c.id('ticket'))}, {sql_string(c.id('log-resolved'))}, 'CURATION',
        'SUCCEEDED', 2, {sql_json(run_snapshot)}, {sql_string(SEED_MODEL_VERSION)}, 'seed', {sql_string(CONTRACT_VERSION)},
        {sql_json(curation_result)}, {sql_json(curation_validation)}, {sql_timestamp(t['cur-start'])}, {sql_timestamp(t['cur-end'])}, {sql_timestamp(t['cur-start'])}, {sql_timestamp(t['cur-end'])})""")

    confirmed_facts = {
        "facts": evidence_statements(c), "limitations": case["limitations"],
        "sourceMessageIds": c.source_message_ids(), "knowledge": [],
    }
    entity_links = approved_case(c)["entityLinks"]
    add(f"""INSERT INTO resolved_case (id, case_number, version_no, source_work_log_id, it_system_id, system_feature_id, case_type, title, problem_statement, preconditions, confirmed_facts, confirmed_cause, action_detail, verification_result, applicability, applied_version, access_level, origin_type, case_status, curator_run_id, relation_candidates, approved_by_member_id, approved_at, vector_status, graph_status, created_at, updated_at)
VALUES ({sql_string(c.id('case'))}, {sql_string(c.case_number)}, 1, {sql_string(c.id('log-resolved'))}, {system_ref(sys_code)}, {feature_ref(sys_code, feature)}, 'RESOLUTION',
        {sql_string(case['title'])}, {sql_string(case['problemStatement'])}, {sql_nullable(case.get('preconditions'))}, {sql_json(confirmed_facts)},
        {sql_string(resolved['confirmedCause'])}, {sql_string(resolved['action'])}, {sql_string(resolved['verification'])}, {sql_string(case['applicability'])},
        NULL, 'STAFF', 'SYNTHETIC', 'APPROVED', {sql_string(c.id('run-curation'))}, {sql_json(entity_links)}, {member_ref(d['assignee'])},
        {sql_timestamp(t['approved'])}, 'PENDING', 'PENDING', {sql_timestamp(t['cur-end'])}, {sql_timestamp(t['approved'])})""")

    source_data = {"caseId": c.id("case"), "caseNumber": c.case_number, "cardPath": card.relative_path, "origin": "SYNTHETIC"}
    source_metadata = {"caseId": c.id("case"), "caseVersion": 1, "indexRequests": index_requests(c)}
    # 문서 ID는 resolved_case.id와 같다(docs/DATA.md 11.3).
    add(f"""INSERT INTO knowledge_document (id, title, document_type, it_system_id, access_level, source_data, data_split, review_status, is_synthetic, created_at, updated_at)
VALUES ({sql_string(c.id('case'))}, {sql_string(case['title'])}, 'CASE', {system_ref(sys_code)}, 'STAFF', {sql_json(source_data)}, 'RAG', 'APPROVED', true,
        {sql_timestamp(t['approved'])}, {sql_timestamp(t['approved'])})""")
    add(f"""INSERT INTO knowledge_document_version (id, knowledge_document_id, version_no, content, content_digest, source_metadata, review_status, created_at)
VALUES ({sql_string(c.id('doc-version'))}, {sql_string(c.id('case'))}, 1,
        $kb${content}$kb$,
        {sql_string(digest)}, {sql_json(source_metadata)}, 'APPROVED', {sql_timestamp(t['approved'])})""")

    stmts.append(f"UPDATE inquiry SET current_run_id = {sql_string(c.id('run-b'))} WHERE id = {sql_string(c.id('inquiry'))} AND current_run_id IS NULL;")
    return "\n".join(stmts) + "\n"


def render_sql(cards: list[CaseCard]) -> str:
    return HEADER + "\n" + "\n".join(render_case(card) for card in cards)


def build() -> tuple[str | None, list[str]]:
    """검증을 통과하면 (SQL, [])를, 아니면 (None, 오류 목록)을 돌려준다."""
    docs = load_corpus()
    cards = load_cards()
    errors = validate_corpus(docs) + check_against_canon(docs) + validate_cards(cards, docs) + check_cards_against_canon(cards)
    return (None if errors else render_sql(cards)), errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="파일을 쓰지 않고 카드·코퍼스·정본·SQL이 같은지 검사한다.")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="결과 SQL 경로")
    args = parser.parse_args(argv)

    sql, errors = build()
    if sql is None:
        print(f"사례 카드 검증 오류 {len(errors)}건:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1

    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if current != sql:
            print("R__seed_dev_knowledge_cases.sql이 카드와 다릅니다. 아래 명령으로 다시 만들어 함께 커밋하세요.", file=sys.stderr)
            print("  uv run scripts/knowledge/build_case_seed.py", file=sys.stderr)
            diff = difflib.unified_diff(current.splitlines(), sql.splitlines(), "현재 파일", "카드에서 만든 결과", lineterm="", n=0)
            for line in list(diff)[:40]:
                print(line, file=sys.stderr)
            return 1
        print("사례 카드, 코퍼스, 정본, seed SQL이 일치합니다.")
        return 0

    args.output.write_text(sql, encoding="utf-8")
    print(f"{args.output.relative_to(REPO_ROOT)} 을(를) 만들었습니다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
