-- 문의자가 확인한 이미지 전체의 공개 대상과 변경 이력을 분리한다.
-- 동일 문의·현재 문의자·메시지 차수 검사는 각 명령의 거래 안에서 수행한다.
CREATE TABLE image_attachment_problem_link_history (
    id                      UUID        PRIMARY KEY,
    attachment_id           UUID        NOT NULL REFERENCES image_attachment (id),
    link_revision           INT         NOT NULL CHECK (link_revision >= 1),
    actor_member_id         UUID        NOT NULL REFERENCES member (id),
    operation_id            UUID        NOT NULL REFERENCES idempotency_key (operation_id),
    source_message_revision INT         NOT NULL CHECK (source_message_revision >= 1),
    reason                  VARCHAR(30) NOT NULL CHECK (reason IN ('USER_CONFIRMED', 'MESSAGE_REVISED')),
    problem_ids             JSONB       NOT NULL CHECK (jsonb_typeof(problem_ids) = 'array'),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_image_problem_link_history_revision CHECK (
        reason <> 'MESSAGE_REVISED' OR jsonb_array_length(problem_ids) = 0
    ),
    CONSTRAINT uk_image_problem_link_history_revision UNIQUE (attachment_id, link_revision),
    CONSTRAINT uk_image_problem_link_history_operation UNIQUE (attachment_id, operation_id)
);

CREATE TABLE image_attachment_problem_link (
    attachment_id           UUID        NOT NULL REFERENCES image_attachment (id),
    problem_id              UUID        NOT NULL REFERENCES inquiry_problem (id),
    link_revision           INT         NOT NULL CHECK (link_revision >= 1),
    source_message_revision INT         NOT NULL CHECK (source_message_revision >= 1),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_image_attachment_problem_link PRIMARY KEY (attachment_id, problem_id),
    CONSTRAINT fk_image_problem_link_history FOREIGN KEY (attachment_id, link_revision)
        REFERENCES image_attachment_problem_link_history (attachment_id, link_revision)
);

CREATE INDEX idx_image_problem_link_problem ON image_attachment_problem_link (problem_id, attachment_id);
