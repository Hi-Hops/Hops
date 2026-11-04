-- ai/migrations/003 적용 후 AI 런타임 DB에서 migrator로 실행한다.
-- 업무 DB와 hops_knowledge 권한은 이 역할에 부여하지 않는다.
GRANT SELECT, INSERT, UPDATE ON public.ai_index_job TO hops_ai_app;
