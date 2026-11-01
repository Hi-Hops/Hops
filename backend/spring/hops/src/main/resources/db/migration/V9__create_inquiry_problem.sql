-- 문의 문제: 복합 문의를 나눈 '문제' 단위 (docs/ERD.md "문의 문제 inquiry_problem")
-- 문제마다 추가 질문 횟수·티켓 생성·무티켓 해결 확인을 따로 관리한다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE inquiry_problem (
    id                   UUID          PRIMARY KEY DEFAULT uuidv7(),
    inquiry_id           UUID          NOT NULL,
    -- 한 문의 안에서 문제의 순서 (문제 1, 문제 2 …)
    problem_no           INT           NOT NULL,
    title                VARCHAR(200)  NOT NULL,
    detail               TEXT,
    -- CLARIFYING / SYSTEM_PENDING / GUIDED / RESOLVED_WITHOUT_TICKET / TICKET_CREATED / TICKET_FAILED / OUT_OF_SCOPE
    problem_status       VARCHAR(30)   NOT NULL,
    -- 문제당 질문 묶음 최대 5회를 지키려고 센다
    clarify_count        INT           NOT NULL DEFAULT 0,
    -- 티켓 생성 실패 원인. 성공한 티켓은 두고 실패한 문제만 다시 만들려고
    ticket_error_code    VARCHAR(50),
    -- 티켓 없이 안내한 뒤 해결 확인을 요청한 시각 / 문의자가 해결을 확인한 시각
    confirm_requested_at TIMESTAMPTZ,
    resolved_at          TIMESTAMPTZ,
    -- 이 문제를 판단한 AI 실행
    agent_run_id         UUID,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at           TIMESTAMPTZ,
    CONSTRAINT uk_inquiry_problem_inquiry_problem_no UNIQUE (inquiry_id, problem_no),
    CONSTRAINT fk_inquiry_problem_inquiry FOREIGN KEY (inquiry_id) REFERENCES inquiry (id),
    CONSTRAINT fk_inquiry_problem_agent_run FOREIGN KEY (agent_run_id) REFERENCES agent_run (id)
);
