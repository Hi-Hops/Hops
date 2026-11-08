-- 관찰은 AI의 미확인 제안이다. 원본 이미지나 S3 경로는 저장하지 않는다.
-- 쓰기 경로는 실행 입력과 현재 권한·메시지·문제 관계를 별도로 확인해야 한다.
CREATE TABLE image_observation (
    id                    UUID         PRIMARY KEY,
    inquiry_id            UUID         NOT NULL REFERENCES inquiry (id),
    run_id                UUID         NOT NULL REFERENCES agent_run (id),
    observation_ref       VARCHAR(64)  NOT NULL,
    attachment_id         UUID         NOT NULL REFERENCES image_attachment (id),
    source_message_id     UUID         NOT NULL REFERENCES message (id),
    source_revision       INT          NOT NULL,
    problem_id            UUID         NOT NULL REFERENCES inquiry_problem (id),
    answer_message_id     UUID         NOT NULL REFERENCES message (id),
    observation_text      VARCHAR(400) NOT NULL,
    question_bundle_ref   VARCHAR(200),
    observation_state     VARCHAR(20)  NOT NULL DEFAULT 'UNCONFIRMED',
    observation_revision  INT          NOT NULL DEFAULT 1,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_image_observation_run_ref UNIQUE (run_id, observation_ref),
    CONSTRAINT ck_image_observation_ref CHECK (observation_ref ~ '^[A-Za-z0-9-]{1,64}$'),
    CONSTRAINT ck_image_observation_text CHECK (char_length(trim(observation_text)) > 0),
    CONSTRAINT ck_image_observation_source_revision CHECK (source_revision >= 1),
    CONSTRAINT ck_image_observation_state CHECK (
        (observation_state = 'UNCONFIRMED' AND observation_revision = 1)
        OR (observation_state IN ('CONFIRMED', 'INCORRECT', 'UNKNOWN')
            AND observation_revision >= 2 AND question_bundle_ref IS NOT NULL)
    ),
    CONSTRAINT ck_image_observation_question CHECK (
        question_bundle_ref IS NULL OR char_length(trim(question_bundle_ref)) > 0
    )
);

CREATE INDEX idx_image_observation_answer ON image_observation (answer_message_id);
CREATE INDEX idx_image_observation_inquiry ON image_observation (inquiry_id, created_at);
CREATE INDEX idx_image_observation_attachment ON image_observation (attachment_id);
CREATE INDEX idx_image_observation_problem ON image_observation (problem_id);

-- 응답 이력은 기존 멱등 작업과 연결한다. 상태 전이와 이력 추가는 같은 거래에서 수행한다.
CREATE TABLE image_observation_response (
    id                    UUID         PRIMARY KEY,
    observation_id        UUID         NOT NULL REFERENCES image_observation (id),
    observation_revision  INT          NOT NULL CHECK (observation_revision >= 2),
    operation_id          UUID         NOT NULL REFERENCES idempotency_key (operation_id),
    actor_member_id       UUID         NOT NULL REFERENCES member (id),
    decision              VARCHAR(20)  NOT NULL CHECK (decision IN ('CONFIRMED', 'INCORRECT', 'UNKNOWN')),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_image_observation_response_revision UNIQUE (observation_id, observation_revision),
    CONSTRAINT uk_image_observation_response_operation UNIQUE (observation_id, operation_id)
);
