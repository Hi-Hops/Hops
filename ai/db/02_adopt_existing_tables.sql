-- 선택 단계. 예전 안내대로 관리자 계정(hops 등) 소유로 만든 hops_ai_runtime이 이미 있을 때만 실행한다.
-- 01_roles_and_database.sql을 실행한 뒤, 관리자 계정으로 hops_ai_runtime에 접속해 실행한다.
-- 행 데이터와 인덱스는 그대로 두고 테이블 소유자만 hops_ai_migrator로 옮긴다. 여러 번 실행해도 된다.
\set ON_ERROR_STOP on

SELECT current_database() = 'hops_ai_runtime' AS in_runtime_db
\gset
\if :in_runtime_db
\else
\echo 'hops_ai_runtime DB에 접속해 실행한다'
\quit 3
\endif

-- PostgreSQL 16부터는 역할을 만든 관리자도 그 역할로 전환(SET)할 권한을 자동으로 갖지 않는다.
SELECT NOT pg_has_role(current_user, 'hops_ai_migrator',
           CASE WHEN current_setting('server_version_num')::int >= 160000 THEN 'SET' ELSE 'MEMBER' END)
       AS granted_migrator_for_setup
\gset
\if :granted_migrator_for_setup
GRANT hops_ai_migrator TO CURRENT_USER;
\endif

SELECT format('ALTER TABLE %I.%I OWNER TO hops_ai_migrator', schemaname, tablename)
FROM pg_tables
WHERE schemaname = 'public' AND tablename = 'ai_run_ledger' AND tableowner <> 'hops_ai_migrator'
\gexec

\if :granted_migrator_for_setup
REVOKE hops_ai_migrator FROM CURRENT_USER;
\endif

SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;
