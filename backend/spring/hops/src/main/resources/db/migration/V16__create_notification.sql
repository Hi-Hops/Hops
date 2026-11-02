-- 알림: 서비스 내부 알림함 (docs/ERD.md "알림 notification")
-- 추가만 하는 기록 테이블이다. 알림을 읽었다고 해결 확인이나 처리 완료로 보지 않는다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE notification (
    id                  UUID           PRIMARY KEY DEFAULT uuidv7(),
    -- 알림을 받을 사람. 본인 알림만 보이게 한다
    recipient_member_id UUID           NOT NULL,
    -- 누르면 이동할 문의(문의자 알림) / 티켓(담당자 알림)
    inquiry_id          UUID,
    ticket_id           UUID,
    -- TICKET_ASSIGNED / CONFIRM_REQUESTED / RESULT_NOTIFIED / CASE_APPROVAL_REQUESTED
    notification_type   VARCHAR(30)    NOT NULL,
    title               VARCHAR(200)   NOT NULL,
    -- 번호·요약·링크
    payload             JSONB          NOT NULL,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_member_id) REFERENCES member (id),
    CONSTRAINT fk_notification_inquiry FOREIGN KEY (inquiry_id) REFERENCES inquiry (id),
    CONSTRAINT fk_notification_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
    CONSTRAINT ck_notification_type CHECK (notification_type IN ('TICKET_ASSIGNED', 'CONFIRM_REQUESTED',
        'RESULT_NOTIFIED', 'CASE_APPROVAL_REQUESTED'))
);

-- PostgreSQL은 FK 컬럼에 인덱스를 자동으로 만들지 않는다. 본인 알림함을 수신자로 찾으려고 둔다.
CREATE INDEX idx_notification_recipient_member_id ON notification (recipient_member_id);
