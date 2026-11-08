-- 분석 대상은 담당자에게 이미지를 공개하는 관계와 별도로 관리한다.
ALTER TABLE image_attachment ADD COLUMN analysis_revision INT NOT NULL DEFAULT 0
    CHECK (analysis_revision >= 0 AND (attachment_status = 'BOUND' OR analysis_revision = 0));

CREATE TABLE image_attachment_analysis_history (
    id UUID PRIMARY KEY,
    attachment_id UUID NOT NULL REFERENCES image_attachment (id),
    analysis_revision INT NOT NULL CHECK (analysis_revision >= 1),
    actor_member_id UUID NOT NULL REFERENCES member (id),
    operation_id UUID NOT NULL REFERENCES idempotency_key (operation_id),
    source_message_revision INT NOT NULL CHECK (source_message_revision >= 1),
    reason VARCHAR(30) NOT NULL CHECK (reason IN ('USER_SELECTED', 'MESSAGE_REVISED')),
    problem_ids JSONB NOT NULL CHECK (jsonb_typeof(problem_ids) = 'array'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_image_analysis_revised_empty CHECK (
        reason <> 'MESSAGE_REVISED' OR jsonb_array_length(problem_ids) = 0
    ),
    CONSTRAINT uk_image_analysis_history_revision UNIQUE (attachment_id, analysis_revision),
    CONSTRAINT uk_image_analysis_history_operation UNIQUE (attachment_id, operation_id)
);

CREATE TABLE image_attachment_analysis_target (
    attachment_id UUID NOT NULL REFERENCES image_attachment (id),
    problem_id UUID NOT NULL REFERENCES inquiry_problem (id),
    analysis_revision INT NOT NULL CHECK (analysis_revision >= 1),
    source_message_revision INT NOT NULL CHECK (source_message_revision >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_image_analysis_target PRIMARY KEY (attachment_id, problem_id),
    CONSTRAINT fk_image_analysis_target_history FOREIGN KEY (attachment_id, analysis_revision)
        REFERENCES image_attachment_analysis_history (attachment_id, analysis_revision)
);

CREATE INDEX idx_image_analysis_target_problem ON image_attachment_analysis_target (problem_id, attachment_id);
