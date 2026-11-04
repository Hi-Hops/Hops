-- 앱 계정 권한 부여. ai/migrations를 적용한 뒤 hops_ai_migrator로 hops_ai_runtime에 접속해 실행한다.
-- 새 마이그레이션이 테이블을 추가하면 이 파일에 필요한 최소 권한을 함께 추가하고 다시 실행한다.
-- 여러 번 실행해도 된다. 기존 권한을 지우고 아래 목록만 다시 부여한다.
\set ON_ERROR_STOP on

SELECT current_database() = 'hops_ai_runtime' AND current_user = 'hops_ai_migrator' AS expected_session
\gset
\if :expected_session
\else
\echo 'hops_ai_migrator로 hops_ai_runtime DB에 접속해 실행한다'
\quit 3
\endif

BEGIN;
-- PostgreSQL 15 미만에서 만든 DB도 같은 결과가 되도록 public 스키마의 기본 권한을 거둔다.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO hops_ai_app;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC, hops_ai_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC, hops_ai_app;
-- RunLedger는 접수(INSERT), 상태·결과 갱신과 만료(UPDATE), 조회·복구(SELECT)만 한다. 삭제하지 않는다.
GRANT SELECT, INSERT, UPDATE ON public.ai_run_ledger TO hops_ai_app;
COMMIT;

SELECT grantee, table_name, string_agg(privilege_type, ', ' ORDER BY privilege_type) AS privileges
FROM information_schema.role_table_grants
WHERE table_schema = 'public' AND grantee = 'hops_ai_app'
GROUP BY grantee, table_name;
