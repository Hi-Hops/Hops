-- 승인 사례 색인 작업(contracts/agents I1·I1.1). (jobId, attempt)를 유일 키로 원자적으로 접수한다.
-- 같은 키·같은 요청은 기존 결과를 돌려주고, 다른 내용은 request_digest로 가려 409로 거부한다.
-- 요청 본문(승인 사례)은 보존하지 않는다. 반영에 필요한 원본은 실행 때 Spring에서 다시 읽는다.
CREATE TABLE ai_index_job (
    job_id UUID NOT NULL,
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    case_id UUID NOT NULL,
    case_version INTEGER NOT NULL CHECK (case_version > 0),
    store TEXT NOT NULL CHECK (store IN ('VECTOR', 'NEO4J')),
    content_digest CHAR(64) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    error JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (job_id, attempt),
    CONSTRAINT ai_index_job_error_matches_state CHECK (
        (state = 'SUCCEEDED' AND error IS NULL) OR (state = 'FAILED' AND error IS NOT NULL)
        OR state IN ('PENDING', 'RUNNING', 'UNKNOWN')
    )
);
