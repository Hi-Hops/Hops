-- AI 실행 원장 DB의 저장소별 반영 결과. Spring 업무 상태와 분리한다.
CREATE TABLE IF NOT EXISTS ai_index_job (
    job_id UUID NOT NULL,
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    request JSONB NOT NULL,
    document_version_id UUID,
    snapshot JSONB NOT NULL,
    PRIMARY KEY (job_id, attempt)
);
