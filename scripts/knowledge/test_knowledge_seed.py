"""코퍼스 검증과 seed SQL 생성 테스트.

    uv run --with pytest --with pyyaml pytest scripts/knowledge
"""

from __future__ import annotations

import hashlib
import textwrap
import uuid
from pathlib import Path

import build_knowledge_seed as builder
import corpus
import pytest

GUIDE_BODY = "\n".join(f"## {heading}\n\n내용\n" for heading in corpus.BODY_HEADINGS["GUIDE"])
RUNBOOK_BODY = "\n".join(f"## {heading}\n\n`sym-a` `cause-a` `check-a` 내용\n" for heading in corpus.BODY_HEADINGS["RUNBOOK"])


def front_matter(**overrides: object) -> str:
    meta = {
        "documentKey": "guide-ea-sample",
        "kind": "GUIDE",
        "title": "예시",
        "systemCode": "ELECTRONIC_APPROVAL",
        "accessScope": "EMPLOYEE",
        "version": 1,
        "appliesToSystemVersion": "null",
        "applicability": "예시 문서의 적용 조건",
        "dataSplit": "RAG",
        "origin": "SYNTHETIC",
        "reviewStatus": "APPROVED",
    }
    meta.update(overrides)
    lines = [f"{key}: {value}" for key, value in meta.items() if value is not None]
    lines += ["source:", "  type: AUTHORED", "  reference: null"]
    return "\n".join(lines)


def write_doc(directory: Path, key: str, meta: str, body: str) -> Path:
    path = directory / f"{key}.md"
    path.write_text(f"---\n{meta}\n---\n\n{body}", encoding="utf-8")
    return path


def load_one(tmp_path: Path, key: str = "guide-ea-sample", body: str = GUIDE_BODY, **overrides: object) -> list[corpus.Document]:
    write_doc(tmp_path, key, front_matter(documentKey=key, **overrides), body)
    return corpus.load_corpus(tmp_path)


def test_document_id_follows_uuid5_rule_and_is_stable(tmp_path: Path) -> None:
    doc = load_one(tmp_path)[0]
    assert doc.document_id == str(uuid.uuid5(uuid.NAMESPACE_URL, "hops-knowledge.example/guide-ea-sample"))
    assert doc.version_id == str(uuid.uuid5(uuid.NAMESPACE_URL, "hops-knowledge.example/guide-ea-sample/v1"))
    assert doc.document_id != doc.version_id


def test_content_excludes_front_matter_and_normalizes_line_endings(tmp_path: Path) -> None:
    path = write_doc(tmp_path, "guide-ea-sample", front_matter(), GUIDE_BODY)
    path.write_bytes(path.read_bytes().replace(b"\n", b"\r\n"))
    doc = corpus.load_corpus(tmp_path)[0]
    assert "\r" not in doc.content
    assert not doc.content.startswith(("---", "\n"))
    assert doc.content.endswith("내용\n") and not doc.content.endswith("\n\n")
    assert doc.digest == hashlib.sha256(doc.content.encode("utf-8")).hexdigest()


def test_digest_changes_with_body_but_not_with_front_matter(tmp_path: Path) -> None:
    base = load_one(tmp_path)[0]
    other_dir = tmp_path / "other"
    other_dir.mkdir()
    same_body = load_one(other_dir, title="다른 제목")[0]
    assert same_body.digest == base.digest
    third = tmp_path / "third"
    third.mkdir()
    changed = load_one(third, body=GUIDE_BODY.replace("내용", "바뀐 내용", 1))[0]
    assert changed.digest != base.digest


def test_sql_escapes_quotes_and_links_system_by_code(tmp_path: Path) -> None:
    doc = load_one(tmp_path, title="사용자's 가이드")[0]
    sql = builder.render_document(doc)
    assert "'사용자''s 가이드'" in sql
    assert "(SELECT id FROM it_system WHERE code = 'ELECTRONIC_APPROVAL')" in sql
    assert "'EMPLOYEE_GUIDE'" in sql and "'EMPLOYEE'" in sql


def test_common_document_has_null_system(tmp_path: Path) -> None:
    doc = load_one(tmp_path, systemCode=None)[0]
    assert "NULL" in builder.render_document(doc)


def errors_for(tmp_path: Path, **kwargs: object) -> list[str]:
    return corpus.validate_corpus(load_one(tmp_path, **kwargs))


def test_valid_sample_has_no_errors(tmp_path: Path) -> None:
    assert errors_for(tmp_path) == []


@pytest.mark.parametrize(
    ("overrides", "expected"),
    [
        ({"accessScope": "OTHER"}, "accessScope"),
        ({"dataSplit": "TEST"}, "dataSplit"),
        ({"reviewStatus": "DONE"}, "reviewStatus"),
        ({"version": 0}, "version"),
        ({"systemCode": "UNKNOWN"}, "systemCode"),
    ],
)
def test_rejects_invalid_values(tmp_path: Path, overrides: dict, expected: str) -> None:
    assert any(expected in error for error in errors_for(tmp_path, **overrides))


@pytest.mark.parametrize("value", [None, '""', "x" * 501], ids=["missing", "empty", "too-long"])
def test_applicability_is_required_and_bounded(tmp_path: Path, value: str | None) -> None:
    errors = errors_for(tmp_path, applicability=value)
    assert any("applicability" in error for error in errors)


def test_rejects_staff_only_terms_in_employee_applicability(tmp_path: Path) -> None:
    errors = errors_for(tmp_path, applicability="쿠키 차단 원인에 쓴다")
    assert any("applicability" in error and "쿠키" in error for error in errors)


def test_applicability_is_written_to_the_version_metadata(tmp_path: Path) -> None:
    docs = load_one(tmp_path)
    assert '"applicability": "예시 문서의 적용 조건"' in builder.render_sql(docs)


def test_rejects_filename_that_differs_from_key(tmp_path: Path) -> None:
    write_doc(tmp_path, "other-name", front_matter(), GUIDE_BODY)
    assert any("파일명" in error for error in corpus.validate_corpus(corpus.load_corpus(tmp_path)))


def test_rejects_wrong_heading_order(tmp_path: Path) -> None:
    errors = errors_for(tmp_path, body="## 목적\n\n내용\n")
    assert any("본문 제목" in error for error in errors)


def test_rejects_staff_only_terms_in_employee_document(tmp_path: Path) -> None:
    body = GUIDE_BODY.replace("내용", "쿠키 차단이 원인입니다", 1)
    errors = errors_for(tmp_path, body=body)
    assert any("쿠키" in error for error in errors) and any("원인" in error for error in errors)


def test_runbook_must_be_staff_only(tmp_path: Path) -> None:
    errors = corpus.validate_corpus(load_one(tmp_path, key="runbook-ea-sample", kind="RUNBOOK", body=RUNBOOK_BODY))
    assert any("SYSTEM_STAFF" in error for error in errors)


def relation_meta(extra: str) -> str:
    return front_matter(documentKey="runbook-ea-sample", kind="RUNBOOK", accessScope="SYSTEM_STAFF") + "\n" + textwrap.dedent(extra)


RELATIONS_OK = """\
symptoms:
  - id: sym-a
    label: 증상
    featureCode: DOCUMENT_VIEW
    causes:
      - id: cause-a
        label: 원인
        checks: [check-a]
checks:
  - id: check-a
    label: 확인
    employeeDisclosable: true
"""


def runbook_errors(tmp_path: Path, relations: str, body: str = RUNBOOK_BODY) -> list[str]:
    write_doc(tmp_path, "runbook-ea-sample", relation_meta(relations), body)
    return corpus.validate_corpus(corpus.load_corpus(tmp_path))


def test_accepts_valid_relations(tmp_path: Path) -> None:
    assert runbook_errors(tmp_path, RELATIONS_OK) == []


def test_rejects_reference_to_undefined_check(tmp_path: Path) -> None:
    errors = runbook_errors(tmp_path, RELATIONS_OK.replace("[check-a]", "[check-a, check-missing]"))
    assert any("check-missing" in error for error in errors)


def test_rejects_defined_check_that_no_cause_uses(tmp_path: Path) -> None:
    extra = RELATIONS_OK + "  - id: check-unused\n    label: 안 씀\n    employeeDisclosable: false\n"
    body = RUNBOOK_BODY.replace("내용", "`check-unused` 내용", 1)
    assert any("check-unused" in error for error in runbook_errors(tmp_path, extra, body))


def test_rejects_feature_of_another_system(tmp_path: Path) -> None:
    errors = runbook_errors(tmp_path, RELATIONS_OK.replace("DOCUMENT_VIEW", "FILE_VIEW"))
    assert any("기능" in error for error in errors)


def test_rejects_declared_id_missing_from_body(tmp_path: Path) -> None:
    errors = runbook_errors(tmp_path, RELATIONS_OK, RUNBOOK_BODY.replace("`check-a`", "확인"))
    assert any("check-a" in error and "본문" in error for error in errors)


def test_rejects_duplicate_check_definition_across_documents(tmp_path: Path) -> None:
    write_doc(tmp_path, "runbook-ea-sample", relation_meta(RELATIONS_OK), RUNBOOK_BODY)
    other = relation_meta(RELATIONS_OK).replace("runbook-ea-sample", "runbook-ea-other")
    write_doc(tmp_path, "runbook-ea-other", other, RUNBOOK_BODY)
    errors = corpus.validate_corpus(corpus.load_corpus(tmp_path))
    assert any("정의되어 있습니다" in error for error in errors)


def test_check_mode_reports_drift(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    stale = tmp_path / "R__seed_dev_knowledge.sql"
    stale.write_text("-- 오래된 내용\n", encoding="utf-8")
    assert builder.main(["--check", "--output", str(stale)]) == 1
    assert "다시 만들어" in capsys.readouterr().err


# ---- 실제 저장소의 코퍼스 ----


def test_repository_corpus_is_valid_and_matches_canon() -> None:
    docs = corpus.load_corpus()
    assert len(docs) == 16
    assert corpus.validate_corpus(docs) == []
    assert corpus.check_against_canon(docs) == []


def test_repository_seed_sql_is_up_to_date() -> None:
    assert builder.main(["--check"]) == 0


def test_repository_ids_are_pinned() -> None:
    """ID 규칙이 바뀌면 이미 적재된 DB와 어긋나므로 대표 값을 고정해 둔다."""
    by_key = {doc.key: doc for doc in corpus.load_corpus()}
    assert by_key["guide-ea-document-search"].document_id == str(
        uuid.uuid5(uuid.NAMESPACE_URL, "hops-knowledge.example/guide-ea-document-search")
    )
    assert by_key["guide-ea-approval-status"].document_id == "bc670240-53a6-54a5-9a07-ca01e362be69"
