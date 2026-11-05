"""관계 데이터(질문 카드 검증, 관계 seed SQL 생성) 테스트.

    uv run --with pytest --with pyyaml pytest scripts/knowledge
"""

from __future__ import annotations

import re
import uuid
from dataclasses import replace

import build_relation_seed as builder
import cases
import corpus
import pytest
import relations


@pytest.fixture(scope="module")
def docs() -> list[corpus.Document]:
    return corpus.load_corpus()


@pytest.fixture(scope="module")
def declarations(docs: list[corpus.Document]) -> relations.Declarations:
    return relations.collect_declarations(docs)


@pytest.fixture(scope="module")
def questions() -> list[relations.Question]:
    return relations.load_questions()


def test_repository_relations_are_valid(declarations: relations.Declarations, questions: list[relations.Question]) -> None:
    assert len(declarations.symptoms) == 5
    assert len(declarations.causes) == 15
    assert len(declarations.checks) == 21
    assert len(questions) == 20
    assert relations.validate_questions(questions, declarations) == []
    assert relations.validate_case_links(cases.load_cards(), declarations) == []


def test_repository_relation_seed_sql_is_up_to_date() -> None:
    assert builder.main(["--check"]) == 0


def test_generated_sql_has_expected_rows(docs: list[corpus.Document], declarations: relations.Declarations,
                                         questions: list[relations.Question]) -> None:
    sql = builder.render_sql(docs, declarations, questions, cases.load_cards())
    rows = {
        "relation_symptom": 5, "relation_cause": 15, "relation_check_item": 21, "relation_question_template": 20,
        "relation_symptom_cause": 15, "relation_cause_check": 25, "relation_case_symptom": 6, "relation_case_check": 11,
    }
    for table, expected in rows.items():
        block = sql.split(f"INSERT INTO {table} (")[1].split("ON CONFLICT DO NOTHING;")[0]
        assert len(re.findall(r"^    \('", block, re.MULTILINE)) == expected, table
    assert sql.count("ON CONFLICT DO NOTHING;") == len(rows)
    assert "'PENDING'" not in sql  # 모든 행이 승인 상태로 들어간다
    assert sql == builder.render_sql(docs, declarations, questions, cases.load_cards())  # 결정적이다


def test_ids_are_stable_and_pinned() -> None:
    assert builder.rel_id("symptom", "sym-ea-doc-not-listed") == str(
        uuid.uuid5(uuid.NAMESPACE_URL, "hops-relation.example/symptom/sym-ea-doc-not-listed")
    )
    assert builder.rel_id("symptom", "sym-ea-doc-not-listed") == "9700e62c-dfde-52a5-881d-8abbc634dced"
    assert builder.rel_id("symptom", "a") != builder.rel_id("cause", "a")


def test_only_disclosable_checks_with_questions_are_asked(declarations: relations.Declarations,
                                                          questions: list[relations.Question]) -> None:
    asked = {question.check_key for question in questions}
    assert asked == {check.key for check in declarations.checks if check.employee_disclosable}
    assert "check-ea-view-permission-record" not in asked


def with_question(questions: list[relations.Question], **changes: object) -> list[relations.Question]:
    return [replace(questions[0], **changes), *questions[1:]]


def test_rejects_question_for_unknown_check(declarations: relations.Declarations, questions: list[relations.Question]) -> None:
    errors = relations.validate_questions(with_question(questions, check_key="check-none"), declarations)
    assert any("선언되어 있지 않습니다" in error for error in errors)


def test_rejects_question_for_staff_only_check(declarations: relations.Declarations, questions: list[relations.Question]) -> None:
    staff_only = replace(
        questions[0], key="q-ea-view-permission-record-01", check_key="check-ea-view-permission-record", employee_disclosable=True
    )
    errors = relations.validate_questions([*questions, staff_only], declarations)
    assert any("직원 공개가 아닌 확인 항목" in error for error in errors)


def test_rejects_disclosable_check_without_question(declarations: relations.Declarations, questions: list[relations.Question]) -> None:
    errors = relations.validate_questions(questions[1:], declarations)
    assert any("질문 문구가 없습니다" in error for error in errors)


def test_rejects_staff_wording_in_question(declarations: relations.Declarations, questions: list[relations.Question]) -> None:
    errors = relations.validate_questions(with_question(questions, text="원인은 무엇인가요? cause-x"), declarations)
    assert any("원인" in error for error in errors) and any("cause-" in error for error in errors)


def test_rejects_duplicate_and_malformed_keys(declarations: relations.Declarations, questions: list[relations.Question]) -> None:
    duplicated = [*questions, replace(questions[0])]
    assert any("중복" in error for error in relations.validate_questions(duplicated, declarations))
    assert any("형식" in error for error in relations.validate_questions(with_question(questions, key="q-wrong"), declarations))


def test_rejects_invalid_review_status_and_flag(declarations: relations.Declarations, questions: list[relations.Question]) -> None:
    errors = relations.validate_questions(with_question(questions, review_status="DONE", employee_disclosable="yes"), declarations)
    assert any("reviewStatus" in error for error in errors) and any("true/false" in error for error in errors)


def test_rejects_blank_or_too_long_question(declarations: relations.Declarations, questions: list[relations.Question]) -> None:
    assert relations.validate_questions(with_question(questions, text="  "), declarations)
    assert relations.validate_questions(with_question(questions, text="가" * 301), declarations)


def test_rejects_case_card_with_undeclared_symptom(declarations: relations.Declarations) -> None:
    card = cases.load_cards()[0]
    data = {**card.data, "relation": {**card.data["relation"], "symptom": "sym-none"}}
    errors = relations.validate_case_links([replace(card, data=data)], declarations)
    assert any("sym-none" in error for error in errors)
