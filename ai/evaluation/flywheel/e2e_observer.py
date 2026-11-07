"""선순환 E2E의 DB 관찰자. 읽기 전용 연결로 공개 API가 보여주지 않는 값만 읽는다.

공개 API는 AI 질문이 요청한 필드(`payload.questionBundles`)나 상담에 쓴 근거 목록을 돌려주지 않는다.
사용자 모사와 인용 확인에는 이 값이 필요해서, 쓰기 권한이 없는 연결로 저장된 값을 읽는다.
업무 데이터를 바꾸지 않으며, 관찰한 값을 AI나 Spring의 입력으로 되돌리지 않는다.
"""

from dataclasses import dataclass
from typing import Any

import psycopg
from psycopg.rows import dict_row


@dataclass(frozen=True)
class QuestionMessage:
    sequence_no: int
    content: str
    bundles: list[dict[str, Any]]


class Observer:
    def __init__(self, dsn: str) -> None:
        # 세션 기본값을 읽기 전용으로 강제한다. 실수로도 업무 데이터를 바꿀 수 없다.
        self._conn = psycopg.connect(
            dsn,
            autocommit=True,
            row_factory=dict_row,
            options="-c default_transaction_read_only=on",
        )

    def close(self) -> None:
        self._conn.close()

    def _all(self, sql: str, params: Any = None) -> list[dict[str, Any]]:
        """params는 위치 인자면 튜플, 이름 인자면 딕셔너리 하나로 받는다."""
        return list(self._conn.execute(sql, params).fetchall())

    def _one(self, sql: str, params: Any = None) -> dict[str, Any] | None:
        rows = self._all(sql, params)
        return rows[0] if rows else None

    # 환경 확인

    def counts(self) -> dict[str, int]:
        row = self._one(
            """
            SELECT (SELECT count(*) FROM member) AS members,
                   (SELECT count(*) FROM resolved_case WHERE case_status = 'APPROVED') AS cases,
                   (SELECT count(*) FROM knowledge_document) AS documents,
                   (SELECT count(*) FROM relation_symptom) AS symptoms
            """
        )
        return {key: int(value) for key, value in (row or {}).items()}

    def run_total(self) -> int:
        row = self._one("SELECT count(*) AS n FROM agent_run")
        return int(row["n"]) if row else 0

    # 문의

    def ai_messages(self, inquiry_id: str) -> list[dict[str, Any]]:
        return self._all(
            """
            SELECT sequence_no, content, payload FROM message
            WHERE inquiry_id = %s AND sender_type = 'AI' AND deleted_at IS NULL
            ORDER BY sequence_no
            """,
            (inquiry_id,),
        )

    def latest_question(self, inquiry_id: str, after_sequence: int) -> QuestionMessage | None:
        for message in reversed(self.ai_messages(inquiry_id)):
            if message["sequence_no"] <= after_sequence:
                return None
            bundles = (message["payload"] or {}).get("questionBundles") or []
            if bundles:
                return QuestionMessage(message["sequence_no"], message["content"], bundles)
        return None

    def runs(self, inquiry_id: str) -> list[dict[str, Any]]:
        return self._all(
            """
            SELECT id, task, run_status, error_code, created_at,
                   (input_snapshot::text LIKE '%%"retrievalScopes"%%') AS has_retrieval_scopes
            FROM agent_run WHERE inquiry_id = %s ORDER BY created_at
            """,
            (inquiry_id,),
        )

    def uses_internal_judgement(self, inquiry_id: str) -> bool | None:
        """상담 실행 입력에 내부 판단 범위(retrievalScopes)가 실렸는지. 실행이 없으면 None."""
        analysis = [run for run in self.runs(inquiry_id) if run["task"] != "CASE_DRAFT"]
        if not analysis:
            return None
        return any(run["has_retrieval_scopes"] for run in analysis)

    def mentions(self, inquiry_id: str, needle: str) -> dict[str, bool]:
        """문의의 저장된 값 어디에 needle이 나오는지(상담 근거, 실행 결과, AI 메시지)."""
        row = self._one(
            """
            SELECT
              EXISTS (SELECT 1 FROM standard_request sr
                      JOIN inquiry_problem p ON p.id = sr.inquiry_problem_id
                      WHERE p.inquiry_id = %(i)s AND sr.related_knowledge::text LIKE %(n)s)
                AS related_knowledge,
              EXISTS (SELECT 1 FROM agent_run r
                      WHERE r.inquiry_id = %(i)s AND r.task <> 'CASE_DRAFT'
                        AND r.result::text LIKE %(n)s) AS run_result,
              EXISTS (SELECT 1 FROM message m
                      WHERE m.inquiry_id = %(i)s AND m.sender_type = 'AI'
                        AND (m.content LIKE %(n)s OR m.payload::text LIKE %(n)s))
                AS employee_message
            """,
            {"i": inquiry_id, "n": f"%{needle}%"},
        )
        return {key: bool(value) for key, value in (row or {}).items()}

    def evidence_count(self, inquiry_id: str) -> int:
        """문의에 저장된 상담 근거 수(표준 요청의 관련 지식 + 상담 실행 결과의 근거).

        0이면 지식 검색이 수행되지 않았거나 결과가 없었다. 어느 쪽인지는 이 값으로 구분하지 못한다.
        """
        row = self._one(
            """
            SELECT
              (SELECT COALESCE(SUM(jsonb_array_length(sr.related_knowledge)), 0)
                 FROM standard_request sr JOIN inquiry_problem p ON p.id = sr.inquiry_problem_id
                WHERE p.inquiry_id = %(i)s AND jsonb_typeof(sr.related_knowledge) = 'array')
              + (SELECT COALESCE(SUM(jsonb_array_length(r.result -> 'evidence')), 0)
                   FROM agent_run r
                  WHERE r.inquiry_id = %(i)s AND r.task <> 'CASE_DRAFT'
                    AND jsonb_typeof(r.result -> 'evidence') = 'array') AS total
            """,
            {"i": inquiry_id},
        )
        return int(row["total"]) if row else 0

    def request_text(self, inquiry_id: str) -> str:
        """표준 요청과 티켓의 저장된 문장 전체. 사례 문장이 원인으로 옮겨졌는지 볼 때 쓴다."""
        rows = self._all(
            """
            SELECT to_jsonb(sr)::text AS text FROM standard_request sr
            JOIN inquiry_problem p ON p.id = sr.inquiry_problem_id WHERE p.inquiry_id = %s
            UNION ALL
            SELECT to_jsonb(t)::text FROM ticket t WHERE t.inquiry_id = %s
            """,
            (inquiry_id, inquiry_id),
        )
        return "\n".join(row["text"] for row in rows)

    # 사례

    def case_for_ticket(self, ticket_id: str) -> dict[str, Any] | None:
        return self._one(
            """
            SELECT c.id::text AS id, c.version_no, c.case_status, c.confirmed_cause, c.title,
                   c.relation_candidates::text AS relation_candidates, c.access_level,
                   c.vector_status, c.graph_status, r.run_status AS curator_status
            FROM resolved_case c
            JOIN ticket_work_log w ON w.id = c.source_work_log_id
            LEFT JOIN agent_run r ON r.id = c.curator_run_id
            WHERE w.ticket_id = %s AND c.deleted_at IS NULL
            ORDER BY c.created_at DESC LIMIT 1
            """,
            (ticket_id,),
        )

    def case_document(self, case_id: str) -> dict[str, Any] | None:
        return self._one(
            """
            SELECT v.review_status AS version_status, d.review_status AS document_status
            FROM knowledge_document_version v
            JOIN knowledge_document d ON d.id = v.knowledge_document_id
            WHERE v.source_metadata::text LIKE %s ORDER BY v.created_at DESC LIMIT 1
            """,
            (f"%{case_id}%",),
        )

    def relation_case_rows(self, case_id: str) -> dict[str, int]:
        """승인 사례가 관계 테이블에 반영됐는지(증상·확인 항목 연결 행 수)."""
        row = self._one(
            """
            SELECT (SELECT count(*) FROM relation_case_symptom x
                    WHERE to_jsonb(x)::text LIKE %(n)s) AS symptoms,
                   (SELECT count(*) FROM relation_case_check x
                    WHERE to_jsonb(x)::text LIKE %(n)s) AS checks
            """,
            {"n": f"%{case_id}%"},
        )
        return {key: int(value) for key, value in (row or {}).items()}
