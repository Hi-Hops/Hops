-- 업무 DB의 스키마·테이블 소유자가 실행한다. AI 런타임 계정과 별도의 전용 역할이다.
-- 기존 데이터와 PUBLIC 정책은 변경하지 않는다. 과도한 공통 권한이 있으면 적용을 거부한다.
BEGIN;
DO $$
DECLARE
    role_id oid;
BEGIN
    IF to_regclass('hops_knowledge.knowledge_chunk') IS NULL
            OR to_regclass('public.knowledge_document_version') IS NULL THEN
        RAISE EXCEPTION 'Spring migration이 적용된 업무 DB에서 실행해야 합니다';
    END IF;
    SELECT oid INTO role_id FROM pg_roles WHERE rolname='hops_ai_indexer'
        AND rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
        AND NOT rolinherit AND NOT rolreplication AND NOT rolbypassrls;
    IF role_id IS NULL OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=role_id)
            OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                       WHERE c.relowner=role_id AND n.nspname IN ('public','hops_knowledge'))
            OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=role_id) THEN
        RAISE EXCEPTION '소유권·역할 상속 없는 전용 최소 권한 indexer를 먼저 준비해야 합니다';
    END IF;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO hops_ai_indexer', current_database());
END $$;
REVOKE ALL ON ALL TABLES IN SCHEMA public, hops_knowledge FROM hops_ai_indexer;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public, hops_knowledge FROM hops_ai_indexer;
REVOKE ALL ON SCHEMA public, hops_knowledge FROM hops_ai_indexer;
DO $$
DECLARE item record;
BEGIN
    FOR item IN
        SELECT n.nspname,c.relname,string_agg(quote_ident(a.attname),',' ORDER BY a.attnum) AS columns
        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
        WHERE n.nspname IN ('public','hops_knowledge') AND c.relkind IN ('r','p','v','m','f')
        GROUP BY n.nspname,c.relname
    LOOP
        EXECUTE format('REVOKE ALL (%s) ON TABLE %I.%I FROM hops_ai_indexer',
                       item.columns,item.nspname,item.relname);
    END LOOP;
END $$;
GRANT USAGE ON SCHEMA hops_knowledge TO hops_ai_indexer;
GRANT SELECT, INSERT ON hops_knowledge.knowledge_chunk TO hops_ai_indexer;
DO $$
DECLARE item record;
BEGIN
    IF has_schema_privilege('hops_ai_indexer','public','CREATE')
            OR has_schema_privilege('hops_ai_indexer','hops_knowledge','CREATE') THEN
        RAISE EXCEPTION 'PUBLIC 정책을 포함한 스키마 CREATE 권한을 먼저 점검해야 합니다';
    END IF;
    FOR item IN SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m')
    LOOP
        IF has_table_privilege('hops_ai_indexer',item.oid,
                'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                OR has_any_column_privilege('hops_ai_indexer',item.oid,
                'SELECT,INSERT,UPDATE,REFERENCES') THEN
            RAISE EXCEPTION 'PUBLIC·열 단위 권한을 포함한 업무 원본 접근 권한을 먼저 점검해야 합니다';
        END IF;
    END LOOP;
END $$;
COMMIT;
