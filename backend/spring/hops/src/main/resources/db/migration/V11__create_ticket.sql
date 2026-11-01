-- 티켓: 책임 시스템의 주담당자가 처리할 일 1건 (docs/ERD.md "티켓 ticket")
-- 문제 하나에서 티켓은 최대 1개다 (inquiry_problem_id 중복 불가).
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE ticket (
    id                    UUID          PRIMARY KEY DEFAULT uuidv7(),
    -- 사람이 부르기 쉬운 티켓 번호 (예: TK-0001)
    ticket_number         VARCHAR(20)   NOT NULL,
    inquiry_id            UUID          NOT NULL,
    inquiry_problem_id    UUID          NOT NULL,
    -- 어느 버전의 표준 요청서로 만든 티켓인지
    standard_request_id   UUID          NOT NULL,
    title                 VARCHAR(200)  NOT NULL,
    detail                TEXT          NOT NULL,
    -- SERVICE_REQUEST / INVESTIGATION / INCIDENT / CHANGE_REQUEST / REVIEW_REQUIRED
    work_type             VARCHAR(30)   NOT NULL,
    -- OPEN / IN_PROGRESS / WAITING_CONFIRM / RESOLVED
    ticket_status         VARCHAR(30)   NOT NULL DEFAULT 'OPEN',
    -- 이 티켓을 맡은 시스템과 배정 시점의 주담당자. 나중에 주담당자가 바뀌어도 기존 담당은 그대로
    responsible_system_id UUID          NOT NULL,
    assignee_member_id    UUID          NOT NULL,
    confirm_requested_at  TIMESTAMPTZ,
    resolved_at           TIMESTAMPTZ,
    -- 두 사람이 동시에 고칠 때 늦은 쪽 변경을 거절하려고 (문의 버전과 다른 값, 0부터)
    version               INT           NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT uk_ticket_ticket_number UNIQUE (ticket_number),
    CONSTRAINT uk_ticket_inquiry_problem UNIQUE (inquiry_problem_id),
    CONSTRAINT fk_ticket_inquiry FOREIGN KEY (inquiry_id) REFERENCES inquiry (id),
    CONSTRAINT fk_ticket_inquiry_problem FOREIGN KEY (inquiry_problem_id) REFERENCES inquiry_problem (id),
    CONSTRAINT fk_ticket_standard_request FOREIGN KEY (standard_request_id) REFERENCES standard_request (id),
    CONSTRAINT fk_ticket_responsible_system FOREIGN KEY (responsible_system_id) REFERENCES it_system (id),
    CONSTRAINT fk_ticket_assignee FOREIGN KEY (assignee_member_id) REFERENCES member (id)
);

-- V7에서 컬럼만 만들어 둔 AI 실행의 대상 티켓 FK를 이제 붙인다 (Curator 실행이 처리 기록이 달린 티켓을 가리킴)
ALTER TABLE agent_run
    ADD CONSTRAINT fk_agent_run_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id);
