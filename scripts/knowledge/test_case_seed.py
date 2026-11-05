"""시드 해결 사례 카드 검증과 이력 체인 SQL 생성 테스트.

    uv run --with pytest --with pyyaml pytest scripts/knowledge
"""

from __future__ import annotations

import copy
import re
import uuid
from dataclasses import replace

import build_case_seed as builder
import cases
import corpus
import pytest


@pytest.fixture(scope="module")
def docs() -> list[corpus.Document]:
    return corpus.load_corpus()


@pytest.fixture(scope="module")
def real_cards() -> list[cases.CaseCard]:
    return cases.load_cards()


def mutated(card: cases.CaseCard, **changes: object) -> cases.CaseCard:
    """카드의 최상위 값을 바꾼 복사본. 파일명과 caseKey는 그대로 둔다."""
    data = copy.deepcopy(card.data)
    data.update(changes)
    return replace(card, data=data)


def errors_of(card: cases.CaseCard, docs: list[corpus.Document]) -> list[str]:
    return cases.validate_cards([card], docs)


def test_repository_cards_are_valid_and_match_canon(real_cards: list[cases.CaseCard], docs: list[corpus.Document]) -> None:
    assert len(real_cards) == 6
    assert cases.validate_cards(real_cards, docs) == []
    assert cases.check_cards_against_canon(real_cards) == []


def test_repository_case_seed_sql_is_up_to_date() -> None:
    assert builder.main(["--check"]) == 0


def test_ids_are_stable_and_pinned(real_cards: list[cases.CaseCard]) -> None:
    card = next(card for card in real_cards if card.key == "case-ea-doc-no-permission")
    assert card.id_for("case") == str(uuid.uuid5(uuid.NAMESPACE_URL, "hops-seed-case.example/case-ea-doc-no-permission/case"))
    # ID 규칙이 바뀌면 이미 적재된 DB와 어긋나므로 대표 값을 고정해 둔다.
    assert card.id_for("case") == "9f2bd51e-6892-5f75-8ab8-d30b2bf58e21"
    assert len({card.id_for(role) for role in ("inquiry", "msg1", "msg2", "ticket", "case")}) == 5


def test_system_id_follows_data_md_rule() -> None:
    assert builder.system_id("ELECTRONIC_APPROVAL") == "3320ce84-c47c-592c-b599-6e968e12b0a3"
    assert builder.system_id("FILE_SHARING") == "22d935d0-2ed8-58a7-a9b8-d73f717ed3c4"


def test_generated_sql_has_expected_rows_per_case(real_cards: list[cases.CaseCard]) -> None:
    sql = builder.render_sql(real_cards)
    counts = {
        "inquiry": 6, "message": 24, "agent_run": 18, "inquiry_problem": 6, "standard_request": 6, "ticket": 6,
        "ticket_assignment": 6, "ticket_work_log": 12, "ticket_event": 12, "resolved_case": 6,
        "knowledge_document": 6, "knowledge_document_version": 6,
    }
    for table, expected in counts.items():
        assert len(re.findall(rf"^INSERT INTO {table} \(", sql, re.MULTILINE)) == expected, table
    assert sql.count("ON CONFLICT DO NOTHING;") == sum(counts.values())


def test_seed_numbers_do_not_collide_with_real_number_formats(real_cards: list[cases.CaseCard]) -> None:
    sql = builder.render_sql(real_cards)
    for prefix in ("SEED-Q-", "SEED-T-", "SEED-C-"):
        assert len(set(re.findall(rf"'({prefix}\d{{3}})'", sql))) == 6
    assert not re.search(r"'Q-\d+'|'T-\d+'", sql)


def test_cases_are_approved_but_not_marked_as_indexed(real_cards: list[cases.CaseCard]) -> None:
    sql = builder.render_sql(real_cards)
    assert sql.count("'APPROVED', ") >= 6
    assert "'SUCCEEDED', 'SUCCEEDED'" not in sql
    assert sql.count("'PENDING', 'PENDING'") == 6
    assert sql.count("SYNTHETIC_PRESET") >= 12


def test_generated_sql_is_deterministic(real_cards: list[cases.CaseCard]) -> None:
    assert builder.render_sql(real_cards) == builder.render_sql(real_cards)


@pytest.mark.parametrize("seed_no", [1, 3, 6])
def test_case_document_content_follows_approval_writer(real_cards: list[cases.CaseCard], seed_no: int) -> None:
    card = next(card for card in real_cards if card.seed_no == seed_no)
    chain = builder.Chain(card)
    content = builder.case_document_content(chain)
    body = builder.approved_case(chain)
    # CaseApprovalWriter.content: 제목, 증상, 조치, 검증, 적용 조건을 빈 줄로 잇고 한계 목록을 줄로 잇는다.
    expected = "\n\n".join(body[f] for f in ("title", "problemStatement", "action", "verification", "applicability"))
    assert content.startswith(expected)
    assert content.endswith("\n".join(body["limitations"]))
    assert content == content.strip()
    assert "sym-" not in content and "cause-" not in content


def test_approved_case_snapshot_has_all_fields_of_the_approval_writer(real_cards: list[cases.CaseCard]) -> None:
    body = builder.approved_case(builder.Chain(real_cards[0]))
    assert set(body) == {
        "workRecordId", "resolutionRequestId", "approvedBy", "approvedAt", "title", "action", "verification", "applicability",
        "limitations", "appliesToSystemVersion", "accessScope", "origin", "confirmationOrigin", "sourceMessageIds", "entityLinks",
        "systemId", "confirmedCause", "problemStatement", "caseType",
    }
    assert body["approvedBy"] == cases.MEMBER_IDS["ea_owner"]
    assert body["appliesToSystemVersion"] is None and body["confirmationOrigin"] == "SYNTHETIC_PRESET"


def test_index_requests_are_stored_for_both_stores_with_fixed_job_ids(real_cards: list[cases.CaseCard]) -> None:
    chain = builder.Chain(real_cards[0])
    requests = builder.index_requests(chain)
    assert set(requests) == {"VECTOR", "NEO4J"}
    assert requests["VECTOR"]["jobId"] != requests["NEO4J"]["jobId"]
    assert requests["VECTOR"]["contentDigest"] != requests["NEO4J"]["contentDigest"]
    for store, request in requests.items():
        assert request["store"] == store and request["attempt"] == 1 and request["caseVersion"] == 1
        assert request["caseId"] == chain.id("case")
    assert builder.index_requests(chain) == requests  # 결정적이다


def test_canonical_json_matches_the_java_rule() -> None:
    assert builder.canonical({"b": [1, {"d": None, "c": "한"}], "a": True}) == '{"a":true,"b":[1,{"c":"한","d":null}]}'


def test_rejects_wrong_dialogue_order(real_cards: list[cases.CaseCard], docs: list[corpus.Document]) -> None:
    card = mutated(real_cards[0], dialogue=real_cards[0].data["dialogue"][:3])
    assert any("dialogue" in error for error in errors_of(card, docs))


def test_rejects_cause_wording_in_ai_message(real_cards: list[cases.CaseCard], docs: list[corpus.Document]) -> None:
    dialogue = copy.deepcopy(real_cards[0].data["dialogue"])
    dialogue[1]["text"] = "원인은 열람 권한입니다."
    assert any("AI 메시지" in error for error in errors_of(mutated(real_cards[0], dialogue=dialogue), docs))


def test_rejects_assignee_who_is_not_the_primary_owner(real_cards: list[cases.CaseCard], docs: list[corpus.Document]) -> None:
    assert any("주담당자" in error for error in errors_of(mutated(real_cards[0], assignee="login_owner"), docs))


def test_rejects_non_requester_account(real_cards: list[cases.CaseCard], docs: list[corpus.Document]) -> None:
    assert any("문의자 계정" in error for error in errors_of(mutated(real_cards[0], requester="admin"), docs))


def test_rejects_cause_under_another_symptom(real_cards: list[cases.CaseCard], docs: list[corpus.Document]) -> None:
    relation = copy.deepcopy(real_cards[0].data["relation"])
    relation["cause"] = "cause-file-link-expired"
    assert any("선언되어 있지 않습니다" in error for error in errors_of(mutated(real_cards[0], relation=relation), docs))


def test_rejects_unknown_basis_document(real_cards: list[cases.CaseCard], docs: list[corpus.Document]) -> None:
    assert any("코퍼스에 없습니다" in error for error in errors_of(mutated(real_cards[0], basis=["runbook-none"]), docs))


def test_rejects_resolution_without_verification(real_cards: list[cases.CaseCard], docs: list[corpus.Document]) -> None:
    logs = copy.deepcopy(real_cards[0].data["workLogs"])
    logs[1]["verification"] = "  "
    assert any("verification" in error for error in errors_of(mutated(real_cards[0], workLogs=logs), docs))


def test_rejects_feature_of_another_system(real_cards: list[cases.CaseCard], docs: list[corpus.Document]) -> None:
    assert any("기능" in error for error in errors_of(mutated(real_cards[0], feature="FILE_VIEW"), docs))


def test_rejects_duplicate_seed_number(real_cards: list[cases.CaseCard], docs: list[corpus.Document]) -> None:
    twin = mutated(real_cards[1], seedNo=real_cards[0].data["seedNo"])
    assert any("seedNo" in error and "중복" in error for error in cases.validate_cards([real_cards[0], twin], docs))


def test_canon_check_reports_missing_card(real_cards: list[cases.CaseCard]) -> None:
    errors = cases.check_cards_against_canon(real_cards[:-1])
    assert any("카드가 없습니다" in error for error in errors)
