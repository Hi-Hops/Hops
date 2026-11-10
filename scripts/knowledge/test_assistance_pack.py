import json
from collections import Counter

from assistance_pack import PACK, load_pack, seed_sql, validate
from corpus import REPO_ROOT


def test_pack_80_and_opt_in_sql():
    cards, docs = load_pack()
    assert validate(cards, docs) == []
    assert Counter(c.data["system"] for c in cards) == {
        "ELECTRONIC_APPROVAL": 76,
        "COMMON_LOGIN": 4,
    }
    assert len(docs) == 8
    sql = seed_sql(cards, docs)
    assert "DELETE FROM" not in sql and "TRUNCATE" not in sql
    assert sql.count("INSERT INTO resolved_case (") == 80
    assert sql.count("SYNTHETIC_PRESET") >= 80


def test_evaluation_not_indexed_and_splits_balanced():
    payload = json.loads(
        (
            REPO_ROOT / "ai/evaluation/recommendations/electronic-approval-v1.json"
        ).read_text()
    )
    queries = payload["queries"]
    assert len({q["id"] for q in queries}) == 80
    assert Counter(q["split"] for q in queries) == {"DEV": 40, "TEST": 40}
    assert len({q["sourceCaseKey"] for q in queries}) == 80
    for split in ["DEV", "TEST"]:
        assert len({q["mode"] for q in queries if q["split"] == split}) == 4
    assert all(q["goldOnly"] for q in queries)
    assert not list(PACK.glob("*eval*"))
