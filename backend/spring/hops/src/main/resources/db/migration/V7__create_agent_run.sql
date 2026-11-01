-- AI 실행: 상담 분석 또는 Curator 지식화 1회 실행 기록 (docs/ERD.md "AI 실행 agent_run")
-- ticket_id·trigger_work_log_id의 FK는 ticket·ticket_work_log 테이블을 만드는 마이그레이션에서 ALTER TABLE로 추가한다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE agent_run (
    -- Spring이 만들어 AI 서버에 넘기는 실행 번호라 DB 기본값을 두지 않는다
    id                  UUID          PRIMARY KEY,
    inquiry_id          UUID          NOT NULL,
    -- Curator 실행이면 처리 기록이 달린 티켓 (상담 실행이면 NULL)
    ticket_id           UUID,
    -- 상담 실행을 시작한 메시지 (Curator 실행이면 NULL)
    trigger_message_id  UUID,
    -- Curator 실행을 시작한 해결·구현 기록 (상담 실행이면 NULL)
    trigger_work_log_id UUID,
    -- INQUIRY_ANALYSIS / CURATION
    task                VARCHAR(30)   NOT NULL,
    -- Supervisor가 고른 상담 경로: GUIDE / REQUEST / SYMPTOM (Curator 실행이면 NULL)
    route               VARCHAR(30),
    -- RUNNING / SUCCEEDED / FAILED / TIMED_OUT / CANCELLED. 실행 성공은 업무 저장 성공이 아니다
    run_status          VARCHAR(30)   NOT NULL,
    -- 실행 당시 문의 버전. 결과가 늦게 와서 현재 버전과 다르면 버린다
    input_version       INT           NOT NULL,
    model_version       VARCHAR(100)  NOT NULL,
    prompt_version      VARCHAR(50)   NOT NULL,
    schema_version      VARCHAR(50)   NOT NULL,
    trace_id            VARCHAR(100),
    -- AI가 제안한 판단 (Spring 검증 전)
    result              JSONB,
    -- Spring이 검증·저장한 결과
    validation_result   JSONB,
    error_code          VARCHAR(50),
    started_at          TIMESTAMPTZ   NOT NULL,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_agent_run_inquiry FOREIGN KEY (inquiry_id) REFERENCES inquiry (id),
    CONSTRAINT fk_agent_run_trigger_message FOREIGN KEY (trigger_message_id) REFERENCES message (id)
);
