-- AI 실행 단계: 실행 안에서 어느 에이전트가 무엇을 했는지 (docs/ERD.md "AI 실행 단계 agent_step")
-- 추가만 하는 기록 테이블이라 수정일시·삭제일시가 없다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE agent_step (
    id              UUID           PRIMARY KEY DEFAULT uuidv7(),
    agent_run_id    UUID           NOT NULL,
    -- 실행 상세 타임라인 순서 (1부터)
    sequence_no     INT            NOT NULL,
    -- SUPERVISOR / INQUERY / KNOWLEDGE / TICKET / CURATOR
    agent_type      VARCHAR(30)    NOT NULL,
    -- Vector 검색·Neo4j 관계 탐색·모델 호출 등 단계 종류 (값은 AI 서버가 정한다)
    step_type       VARCHAR(30)    NOT NULL,
    tool_name       VARCHAR(100),
    -- 단계 성공·실패·권한 거부 (값은 AI 서버가 정한다)
    step_status     VARCHAR(30)    NOT NULL,
    -- 채팅에 보여줄 진행 문구 (예: 문서 검색 중…)
    display_summary VARCHAR(300)   NOT NULL,
    -- 판단 근거 요약. 모델 내부 사고 과정은 저장하지 않는다
    reason_summary  VARCHAR(1000),
    -- 마스킹된 도구 입출력과 참고 문서
    step_data       JSONB,
    attempt_count   INT            NOT NULL DEFAULT 1,
    error_code      VARCHAR(50),
    started_at      TIMESTAMPTZ    NOT NULL,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_agent_step_run_sequence UNIQUE (agent_run_id, sequence_no),
    CONSTRAINT fk_agent_step_agent_run FOREIGN KEY (agent_run_id) REFERENCES agent_run (id),
    CONSTRAINT ck_agent_step_agent_type CHECK (agent_type IN ('SUPERVISOR', 'INQUERY', 'KNOWLEDGE', 'TICKET', 'CURATOR'))
);
