-- 메시지 수정 이력: 사용자가 메시지를 고치기 전 원문 (docs/ERD.md "메시지 수정 이력 message_revision")
-- 추가만 하는 이력 테이블이라 수정일시·삭제일시가 없다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE message_revision (
    id          UUID          PRIMARY KEY DEFAULT uuidv7(),
    message_id  UUID          NOT NULL,
    -- 몇 번째 수정인지 (1부터)
    revision_no INT           NOT NULL,
    -- 고치기 전 원문
    content     TEXT          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_message_revision_message_revision UNIQUE (message_id, revision_no),
    CONSTRAINT fk_message_revision_message FOREIGN KEY (message_id) REFERENCES message (id)
);
