-- 티켓 변경 이력: 생성·상태 변경·내용 정정·해결 확인 응답 (docs/ERD.md "티켓 변경 이력 ticket_event")
-- 추가만 하는 이력 테이블이다. 담당 시스템·담당자 변경은 ticket_assignment에 남기고 여기 넣지 않는다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE ticket_event (
    id              UUID           PRIMARY KEY DEFAULT uuidv7(),
    ticket_id       UUID           NOT NULL,
    -- CREATED / STATUS_CHANGED / WORK_TYPE_CHANGED / CONTENT_CORRECTED / SUPPLEMENT_ADDED / USER_CONFIRMED / USER_NOT_RESOLVED
    event_type      VARCHAR(30)    NOT NULL,
    -- 누가 바꿨는지: MEMBER / AI / SYSTEM
    actor_type      VARCHAR(30)    NOT NULL,
    -- 사람이 바꿨다면 누구인지 (감사 기록)
    actor_member_id UUID,
    -- AI가 바꿨다면 어느 실행인지
    agent_run_id    UUID,
    before_data     JSONB,
    after_data      JSONB,
    -- 정정·처리 유형 변경 이유 (사람이 정정할 때 필수 입력은 서버 검증에서 막는다)
    reason          VARCHAR(1000),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT fk_ticket_event_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
    CONSTRAINT fk_ticket_event_actor_member FOREIGN KEY (actor_member_id) REFERENCES member (id),
    CONSTRAINT fk_ticket_event_agent_run FOREIGN KEY (agent_run_id) REFERENCES agent_run (id)
);
