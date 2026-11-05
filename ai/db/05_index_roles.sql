-- 업무 DB에서 관리자(CREATEROLE 권한 보유)가 실행한다. 비밀번호는 별도로 설정한다.
DO $$
BEGIN
    IF to_regclass('hops_knowledge.knowledge_chunk') IS NULL THEN
        RAISE EXCEPTION 'Spring migration이 적용된 업무 DB에서 실행해야 합니다';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hops_ai_indexer') THEN
        CREATE ROLE hops_ai_indexer LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOREPLICATION NOBYPASSRLS;
    END IF;
END $$;
