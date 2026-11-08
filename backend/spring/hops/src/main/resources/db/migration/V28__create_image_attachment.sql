-- 가림 확인을 거쳐 전송된 이미지만 저장한다. 이미지 바이트와 S3 위치는 이 테이블에 넣지 않는다.
-- 업로드 접수와 행 생성은 같은 거래, 외부 객체 저장은 거래 밖에서 수행한다.
CREATE TABLE image_attachment (
    id                UUID         PRIMARY KEY,
    owner_member_id   UUID         NOT NULL REFERENCES member (id),
    operation_id      UUID         NOT NULL UNIQUE REFERENCES idempotency_key (operation_id),
    input_sha256      VARCHAR(64)  NOT NULL,
    content_sha256    VARCHAR(64)  NOT NULL,
    byte_size         INT          NOT NULL,
    attachment_status VARCHAR(30)  NOT NULL,
    message_id        UUID         REFERENCES message (id),
    message_position  INT,
    link_revision     INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ,
    CONSTRAINT ck_image_attachment_input_digest CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_image_attachment_content_digest CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_image_attachment_size CHECK (byte_size BETWEEN 1 AND 8388608),
    CONSTRAINT ck_image_attachment_link_revision CHECK (link_revision >= 0),
    CONSTRAINT ck_image_attachment_state CHECK (
        (attachment_status = 'PENDING' AND message_id IS NULL AND message_position IS NULL AND expires_at IS NULL AND link_revision = 0)
        OR (attachment_status = 'READY' AND message_id IS NULL AND message_position IS NULL AND expires_at IS NOT NULL AND link_revision = 0)
        OR (attachment_status = 'BOUND' AND message_id IS NOT NULL AND message_position IS NOT NULL
            AND message_position BETWEEN 0 AND 4 AND expires_at IS NULL)
        OR (attachment_status = 'EXPIRED' AND message_id IS NULL AND message_position IS NULL AND expires_at IS NULL AND link_revision = 0)
    ),
    CONSTRAINT uk_image_attachment_message_position UNIQUE (message_id, message_position)
);

CREATE INDEX idx_image_attachment_owner ON image_attachment (owner_member_id, created_at);
CREATE INDEX idx_image_attachment_message ON image_attachment (message_id) WHERE message_id IS NOT NULL;
CREATE INDEX idx_image_attachment_expiry ON image_attachment (expires_at) WHERE attachment_status = 'READY';
