-- AI 런타임 DB와 계정 준비. 관리자 계정으로 같은 PostgreSQL 인스턴스의 기존 DB(예: hops)에 접속해 실행한다.
-- 여러 번 실행해도 된다. 기존 DB·데이터를 지우지 않는다.
-- 비밀번호는 이 파일에 넣지 않는다. 실행한 뒤 psql의 \password 명령으로 각 계정에 설정한다.
--
--   hops_ai_migrator  hops_ai_runtime DB 소유자. ai/migrations 적용과 03_app_privileges.sql 실행에만 쓴다.
--   hops_ai_app       AI 서버 실행 계정(HOPS_AI_RUNTIME_DSN). ai_run_ledger의 SELECT·INSERT·UPDATE만 갖는다.
\set ON_ERROR_STOP on

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'hops_ai_migrator') THEN
        CREATE ROLE hops_ai_migrator
            LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'hops_ai_app') THEN
        CREATE ROLE hops_ai_app
            LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
    END IF;
END $$;

-- 같은 이름의 계정이 이미 넓은 속성이나 다른 역할 권한을 갖고 있으면 고치지 않고 멈춘다.
DO $$
DECLARE
    broad text;
BEGIN
    SELECT string_agg(rolname, ', ') INTO broad
    FROM pg_roles
    WHERE rolname IN ('hops_ai_migrator', 'hops_ai_app')
      AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR NOT rolcanlogin);
    IF broad IS NOT NULL THEN
        RAISE EXCEPTION '계정 속성이 최소 권한과 다르다: %. 관리자가 확인한 뒤 다시 실행한다', broad;
    END IF;
    SELECT string_agg(r.rolname || ' ∈ ' || g.rolname, ', ') INTO broad
    FROM pg_auth_members m
    JOIN pg_roles r ON r.oid = m.member
    JOIN pg_roles g ON g.oid = m.roleid
    WHERE r.rolname = 'hops_ai_app';
    IF broad IS NOT NULL THEN
        RAISE EXCEPTION 'hops_ai_app이 다른 역할에 속해 있다: %', broad;
    END IF;
END $$;

-- DB 소유자를 지정하려면 실행 계정이 해당 역할의 권한을 잠시 가져야 한다(RDS 관리자 포함).
-- 이미 그 역할에 속해 있던 관리자라면 끝에서 해제하지 않는다.
-- PostgreSQL 16부터는 역할을 만든 관리자도 그 역할로 전환(SET)할 권한을 자동으로 갖지 않는다.
SELECT NOT pg_has_role(current_user, 'hops_ai_migrator',
           CASE WHEN current_setting('server_version_num')::int >= 160000 THEN 'SET' ELSE 'MEMBER' END)
       AS granted_migrator_for_setup
\gset
\if :granted_migrator_for_setup
GRANT hops_ai_migrator TO CURRENT_USER;
\endif

SELECT 'CREATE DATABASE hops_ai_runtime OWNER hops_ai_migrator'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'hops_ai_runtime')
\gexec

-- 예전 안내대로 다른 계정 소유로 만든 DB는 데이터를 그대로 두고 소유자만 옮긴다.
-- 테이블 소유자는 관리자로 hops_ai_runtime에 접속해 02_adopt_existing_tables.sql로 옮긴다.
SELECT 'ALTER DATABASE hops_ai_runtime OWNER TO hops_ai_migrator'
FROM pg_database d
WHERE d.datname = 'hops_ai_runtime' AND pg_get_userbyid(d.datdba) <> 'hops_ai_migrator'
\gexec

-- 런타임 DB는 소유자(마이그레이션), 앱 계정, 이 스크립트를 실행한 관리자만 접속한다.
-- RDS 관리자는 슈퍼유저가 아니므로 PUBLIC 권한을 거둔 뒤에도 점검·02 단계를 위해 접속 권한을 남긴다.
-- 임시 테이블 생성 권한은 주지 않는다. 앱 계정에 예전에 직접 준 CREATE·TEMPORARY도 거둔 뒤 CONNECT만 다시 준다.
REVOKE ALL ON DATABASE hops_ai_runtime FROM PUBLIC, hops_ai_app;
GRANT CONNECT ON DATABASE hops_ai_runtime TO hops_ai_app;
GRANT CONNECT ON DATABASE hops_ai_runtime TO CURRENT_USER;

\if :granted_migrator_for_setup
REVOKE hops_ai_migrator FROM CURRENT_USER;
\endif

SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolcanlogin, rolinherit
FROM pg_roles WHERE rolname IN ('hops_ai_migrator', 'hops_ai_app')
ORDER BY rolname;
