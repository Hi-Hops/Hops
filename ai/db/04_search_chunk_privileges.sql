-- AI 앱 계정의 승인 사례 색인 권한(contracts/agents I1, docs/ERD.md "AI 계정은 hops_knowledge 조각 DML과
-- FK 검사용 REFERENCES만"). 업무 DB(hops)에서 knowledge_chunk 소유자(Spring Flyway 계정)로 실행한다.
-- 먼저 DB 소유자(RDS 마스터)로 다음을 별도 실행한다. 테이블 소유자에게는 DB 권한 부여 권한이 없을 수 있다.
--   GRANT CONNECT ON DATABASE hops TO hops_ai_app;
-- PUBLIC CONNECT를 거둔 환경에서는 아래 조각 권한만 부여해도 AI가 업무 DB에 접속할 수 없다.
-- 업무 원본 테이블의 읽기·쓰기 권한은 주지 않는다. 색인 원본은 Spring 내부 API로 받는다.
-- 여러 번 실행해도 된다.
\set ON_ERROR_STOP on

SELECT current_database() = 'hops' AS expected_database
\gset
\if :expected_database
\else
\echo '업무 DB(hops)에 접속해 실행한다'
\quit 3
\endif

BEGIN;
GRANT USAGE ON SCHEMA hops_knowledge TO hops_ai_app;
-- 같은 승인 버전의 조각을 upsert(INSERT … ON CONFLICT DO UPDATE)하고 남은 조각을 지우며 반영 뒤 다시 읽는다.
GRANT SELECT, INSERT, UPDATE, DELETE ON hops_knowledge.knowledge_chunk TO hops_ai_app;
-- 조각의 FK 대상 열만. 문서 버전 본문·메타데이터는 읽지 않는다.
GRANT REFERENCES (id) ON public.knowledge_document_version TO hops_ai_app;
COMMIT;

SELECT table_schema, table_name, string_agg(privilege_type, ', ' ORDER BY privilege_type) AS privileges
FROM information_schema.role_table_grants
WHERE grantee = 'hops_ai_app'
GROUP BY table_schema, table_name;
