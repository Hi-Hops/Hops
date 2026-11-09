-- 앱 계정 권한 부여. ai/migrations를 적용한 뒤 hops_ai_migrator로 hops_ai_runtime에 접속해 실행한다.
-- 새 마이그레이션이 테이블을 추가하면 이 파일에 필요한 최소 권한을 함께 추가하고 다시 실행한다.
-- 여러 번 실행해도 된다. hops_ai_migrator가 소유한 스키마·테이블·시퀀스·열에서 PUBLIC과 앱 계정의
-- 권한을 모두 거둔 뒤 아래 목록만 다시 부여한다. 다른 계정 소유의 객체는 건드리지 않으며 점검 명령이 찾아낸다.
\set ON_ERROR_STOP on

SELECT current_database() = 'hops_ai_runtime' AND current_user = 'hops_ai_migrator' AS expected_session
\gset
\if :expected_session
\else
\echo 'hops_ai_migrator로 hops_ai_runtime DB에 접속해 실행한다'
\quit 3
\endif

BEGIN;
DO $$
DECLARE
    item record;
    column_revoke text;
BEGIN
    -- PostgreSQL 15 미만에서 만든 DB의 public 기본 권한과, 예전에 앱 계정에 직접 준 스키마 권한을 거둔다.
    FOR item IN
        SELECT nspname FROM pg_namespace
        WHERE pg_get_userbyid(nspowner) IN (current_user, 'pg_database_owner')
          AND nspname NOT LIKE 'pg\_%' AND nspname <> 'information_schema'
    LOOP
        EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC, hops_ai_app', item.nspname);
    END LOOP;

    -- 테이블·시퀀스 권한과 별도로 저장되는 열 단위 권한까지 거둔다.
    FOR item IN
        SELECT c.oid, n.nspname, c.relname, c.relkind
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
          AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
          AND n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema'
    LOOP
        IF item.relkind = 'S' THEN
            EXECUTE format('REVOKE ALL ON SEQUENCE %I.%I FROM PUBLIC, hops_ai_app',
                           item.nspname, item.relname);
        ELSE
            EXECUTE format('REVOKE ALL ON TABLE %I.%I FROM PUBLIC, hops_ai_app',
                           item.nspname, item.relname);
            SELECT format('REVOKE ALL (%s) ON TABLE %I.%I FROM PUBLIC, hops_ai_app',
                          string_agg(quote_ident(attname), ', ' ORDER BY attnum),
                          item.nspname, item.relname)
            INTO column_revoke
            FROM pg_attribute
            WHERE attrelid = item.oid AND attnum > 0 AND NOT attisdropped
            HAVING count(*) > 0;
            IF column_revoke IS NOT NULL THEN
                EXECUTE column_revoke;
            END IF;
        END IF;
    END LOOP;
END $$;

GRANT USAGE ON SCHEMA public TO hops_ai_app;
-- RunLedger는 접수(INSERT), 상태·결과 갱신과 만료(UPDATE), 조회·복구(SELECT)만 한다. 삭제하지 않는다.
GRANT SELECT, INSERT, UPDATE ON public.ai_run_ledger TO hops_ai_app;
-- 색인 작업은 접수(INSERT), 상태 갱신(UPDATE), 조회(SELECT)만 한다. 삭제하지 않는다.
GRANT SELECT, INSERT, UPDATE ON public.ai_index_job TO hops_ai_app;
COMMIT;

SELECT grantee, table_name, string_agg(privilege_type, ', ' ORDER BY privilege_type) AS privileges
FROM information_schema.role_table_grants
WHERE table_schema = 'public' AND grantee = 'hops_ai_app'
GROUP BY grantee, table_name;
