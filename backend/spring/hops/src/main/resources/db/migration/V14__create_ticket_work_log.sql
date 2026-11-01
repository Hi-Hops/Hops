-- 티켓 처리 기록: 담당자가 한 조치·실패한 시도·해결·기능 구현 (docs/ERD.md "티켓 처리 기록 ticket_work_log")
-- 추가만 하는 기록 테이블이다. 고칠 내용은 새 기록으로 남긴다. 해결·구현 기록이 저장되면 Curator가 사례 초안을 만든다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE ticket_work_log (
    id                  UUID           PRIMARY KEY DEFAULT uuidv7(),
    ticket_id           UUID           NOT NULL,
    -- 누가 조치하고 기록했는지. 사례 작성자로도 이어진다
    author_member_id    UUID           NOT NULL,
    -- PROGRESS / FAILED_ATTEMPT / RESOLVED / IMPLEMENTED
    log_type            VARCHAR(30)    NOT NULL,
    action_detail       TEXT           NOT NULL,
    -- 담당자가 근거로 확인한 원인. 확인하지 못했으면 NULL이고 모델이 채우지 않는다
    confirmed_cause     TEXT,
    verification_result TEXT,
    -- 기능 구현이 반영된 시스템 버전
    applied_version     VARCHAR(100),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT fk_ticket_work_log_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
    CONSTRAINT fk_ticket_work_log_author FOREIGN KEY (author_member_id) REFERENCES member (id)
);

-- V7에서 컬럼만 만들어 둔 Curator 실행의 시작 처리 기록 FK를 이제 붙인다
ALTER TABLE agent_run
    ADD CONSTRAINT fk_agent_run_trigger_work_log FOREIGN KEY (trigger_work_log_id) REFERENCES ticket_work_log (id);
