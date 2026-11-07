-- 실패한 사례 생성의 수동 재시도(POST /case-generations/{id}/retries)는 같은 처리 기록에 새 실행을 접수한다.
-- AI 서버는 같은 runId를 다시 받으면 이전 결과를 재생하므로 실행 id를 재사용할 수 없다.
-- 테이블·컬럼은 그대로 두고 유일 인덱스만 "진행 중이거나 성공한 실행은 처리 기록당 하나"로 좁힌다. 실패한 실행은 이력으로 남는다.
-- 이미 적용된 V7은 고치지 않는다.
DROP INDEX uk_agent_run_curation_work_log;
CREATE UNIQUE INDEX uk_agent_run_curation_work_log ON agent_run (trigger_work_log_id)
    WHERE task = 'CURATION' AND run_status IN ('QUEUED', 'RUNNING', 'SUCCEEDED');
