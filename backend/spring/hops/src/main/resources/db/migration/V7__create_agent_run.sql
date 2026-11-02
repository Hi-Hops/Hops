-- AI 실행: 상담 분석 또는 Curator 지식화 1회 실행 기록 (docs/ERD.md "AI 실행 agent_run", 6.1절)
-- ticket_id·trigger_work_log_id의 FK는 V11(ticket)·V14(ticket_work_log)에서 ALTER TABLE로 붙인다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE agent_run (
    -- Spring이 만들어 AI 서버에 넘기는 실행 번호라 DB 기본값을 두지 않는다
    id                  UUID          PRIMARY KEY,
    inquiry_id          UUID          NOT NULL,
    -- Curator 실행이면 처리 기록이 달린 티켓 (상담 실행이면 NULL)
    ticket_id           UUID,
    -- 상담 실행을 시작한 메시지 (Curator 실행이면 NULL)
    trigger_message_id  UUID,
    -- Curator 실행을 시작한 해결·구현 기록 (Curator 실행이면 필수, 상담 실행이면 NULL)
    trigger_work_log_id UUID,
    -- INQUIRY_ANALYSIS / CURATION
    task                VARCHAR(30)   NOT NULL,
    -- Supervisor가 고른 상담 경로: GUIDE / REQUEST / SYMPTOM (Curator 실행이면 NULL)
    route               VARCHAR(30),
    -- QUEUED / RUNNING / SUCCEEDED / FAILED / TIMED_OUT / CANCELLED (CANCELLED는 밖으로 SUPERSEDED로 보낸다)
    run_status          VARCHAR(30)   NOT NULL,
    -- 실행 당시 문의 버전. 결과가 늦게 와서 현재 버전과 다르면 버린다
    input_version       INT           NOT NULL,
    -- AI 서버에 보낸 검증된 입력(AiRunRequest) 원본. 상담 실행의 answerMessageId도 여기 고정한다
    input_snapshot      JSONB         NOT NULL,
    model_version       VARCHAR(100)  NOT NULL,
    prompt_version      VARCHAR(50)   NOT NULL,
    schema_version      VARCHAR(50)   NOT NULL,
    trace_id            VARCHAR(100),
    -- AI가 제안한 판단 (Spring 검증 전)
    result              JSONB,
    -- Spring이 검증·저장한 결과
    validation_result   JSONB,
    error_code          VARCHAR(50),
    -- 실제로 시작한 시각. 대기(QUEUED) 중이면 NULL
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_agent_run_inquiry FOREIGN KEY (inquiry_id) REFERENCES inquiry (id),
    CONSTRAINT fk_agent_run_trigger_message FOREIGN KEY (trigger_message_id) REFERENCES message (id),
    CONSTRAINT ck_agent_run_task CHECK (task IN ('INQUIRY_ANALYSIS', 'CURATION')),
    CONSTRAINT ck_agent_run_route CHECK (route IN ('GUIDE', 'REQUEST', 'SYMPTOM')),
    CONSTRAINT ck_agent_run_status CHECK (run_status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')),
    -- Curator 실행은 시작한 처리 기록이 반드시 있고, 상담 실행에는 처리 기록이 없다
    CONSTRAINT ck_agent_run_trigger_work_log CHECK (
        (task = 'CURATION' AND trigger_work_log_id IS NOT NULL)
        OR (task = 'INQUIRY_ANALYSIS' AND trigger_work_log_id IS NULL)),
    -- 대기 중에는 시작·종료 시각이 없고, 실행 중에는 시작 시각만, 끝난 실행에는 종료 시각이 있다
    -- (대기 중에 취소·실패할 수 있어 끝난 실행의 시작 시각은 비어 있어도 된다)
    CONSTRAINT ck_agent_run_timestamps CHECK (
        (run_status = 'QUEUED' AND started_at IS NULL AND completed_at IS NULL)
        OR (run_status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL)
        OR (run_status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED') AND completed_at IS NOT NULL))
);

-- PostgreSQL은 FK 컬럼에 인덱스를 자동으로 만들지 않는다. 문의의 실행 이력을 찾으려고 둔다.
CREATE INDEX idx_agent_run_inquiry_id ON agent_run (inquiry_id);

-- 처리 기록 하나로 Curator 실행을 두 번 만들지 않는다
CREATE UNIQUE INDEX uk_agent_run_curation_work_log ON agent_run (trigger_work_log_id) WHERE task = 'CURATION';

-- V4에서 컬럼만 만들어 둔 문의의 현재 상담 실행 FK를 이제 붙인다
ALTER TABLE inquiry
    ADD CONSTRAINT fk_inquiry_current_run FOREIGN KEY (current_run_id) REFERENCES agent_run (id);
