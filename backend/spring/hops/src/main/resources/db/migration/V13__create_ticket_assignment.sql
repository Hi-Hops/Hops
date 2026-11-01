-- 담당 배정 이력: 티켓이 어느 시스템의 어느 담당자에게 배정됐는지, 누가 왜 정정했는지 (docs/ERD.md "담당 배정 이력 ticket_assignment")
-- 티켓 생성 시 AUTO 1건을 같은 트랜잭션으로 저장한다. 수동 정정은 현재 배정을 끝내고(ended_at) 새 배정을 추가한다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE ticket_assignment (
    id                    UUID           PRIMARY KEY DEFAULT uuidv7(),
    ticket_id             UUID           NOT NULL,
    -- 배정 당시 맡은 시스템과 그 시스템의 주담당자
    it_system_id          UUID           NOT NULL,
    assignee_member_id    UUID           NOT NULL,
    -- AUTO / MANUAL_CORRECTION
    source                VARCHAR(30)    NOT NULL,
    -- 수동 정정한 사람 (자동 배정이면 NULL)
    assigned_by_member_id UUID,
    -- 자동 배정은 AI가 본 시스템 근거, 수동 정정은 정정 근거와 사유
    reason                VARCHAR(1000)  NOT NULL,
    assigned_at           TIMESTAMPTZ    NOT NULL,
    -- 정정으로 넘어간 시각. NULL이면 현재 배정
    ended_at              TIMESTAMPTZ,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT fk_ticket_assignment_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
    CONSTRAINT fk_ticket_assignment_it_system FOREIGN KEY (it_system_id) REFERENCES it_system (id),
    CONSTRAINT fk_ticket_assignment_assignee FOREIGN KEY (assignee_member_id) REFERENCES member (id),
    CONSTRAINT fk_ticket_assignment_assigned_by FOREIGN KEY (assigned_by_member_id) REFERENCES member (id)
);

-- 현재 배정(ended_at이 NULL)은 티켓당 1건 (ERD 규칙). 끝난 배정은 여러 건 쌓여도 된다.
-- 두 사람이 동시에 재배정해도 현재 담당이 둘이 되지 않게 DB가 막는다.
CREATE UNIQUE INDEX uk_ticket_assignment_current ON ticket_assignment (ticket_id) WHERE ended_at IS NULL;
