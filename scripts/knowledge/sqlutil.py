"""seed SQL 생성에 함께 쓰는 문자열 변환."""

from __future__ import annotations

import json
from datetime import datetime


def sql_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def sql_json(value: object) -> str:
    return sql_string(json.dumps(value, ensure_ascii=False, sort_keys=True)) + "::jsonb"


def sql_timestamp(value: datetime) -> str:
    return sql_string(value.isoformat()) + "::timestamptz"


def sql_nullable(value: str | None) -> str:
    return "NULL" if value is None else sql_string(value)
