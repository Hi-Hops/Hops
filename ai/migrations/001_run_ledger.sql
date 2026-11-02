-- AI 런타임 전용 DB에만 적용한다. Spring 업무 DB의 테이블과 분리한다.
CREATE TABLE IF NOT EXISTS ai_run_ledger (
    run_id UUID PRIMARY KEY,
    input_digest TEXT NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    status TEXT NOT NULL CHECK (status IN (
        'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'SUPERSEDED', 'UNKNOWN'
    )),
    last_seq INTEGER NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
    result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'SUPERSEDED')) = (result IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'SUPERSEDED')) = (finished_at IS NOT NULL))
);
