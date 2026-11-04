"""런타임 DB 권한 점검 명령의 실패 보고.

실제 권한 점검은 일회용 PostgreSQL에서 확인한다(ai/db/README.md).
"""

import socket

import pytest

from hops_ai.persistence import check_runtime_db

SECRET = "not-a-real-password-85"


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


@pytest.mark.parametrize(
    ("dsn", "expected"),
    [
        (None, "FAIL HOPS_AI_RUNTIME_DSN 설정"),
        ("   ", "FAIL HOPS_AI_RUNTIME_DSN 설정"),
        (f"host=127.0.0.1 password={SECRET} = broken", "FAIL HOPS_AI_RUNTIME_DSN 형식"),
    ],
)
def test_missing_or_malformed_dsn_fails_without_echoing_it(monkeypatch, capsys, dsn, expected):
    if dsn is None:
        monkeypatch.delenv("HOPS_AI_RUNTIME_DSN", raising=False)
    else:
        monkeypatch.setenv("HOPS_AI_RUNTIME_DSN", dsn)

    code = check_runtime_db.main([])

    out = capsys.readouterr().out
    assert code == 1
    assert expected in out
    assert SECRET not in out


def test_unreachable_database_fails_without_echoing_the_dsn(monkeypatch, capsys):
    dsn = (
        f"postgresql://hops_ai_app:{SECRET}@127.0.0.1:{free_port()}/hops_ai_runtime"
        "?connect_timeout=2"
    )
    monkeypatch.setenv("HOPS_AI_RUNTIME_DSN", dsn)

    code = check_runtime_db.main(["--business-dbname", "hops", "--write-check"])

    out = capsys.readouterr().out
    assert code == 1
    assert "FAIL 런타임 DB 접속 (OperationalError)" in out
    assert SECRET not in out and "127.0.0.1" not in out
