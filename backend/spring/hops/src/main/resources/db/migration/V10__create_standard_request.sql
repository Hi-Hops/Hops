-- 표준 요청서: 문제별 대화를 담당자가 바로 일할 수 있게 정리한 요청서 (docs/ERD.md "표준 요청서 standard_request", 6.1절)
-- 추가만 하는 불변 스냅샷이다. 같은 문의 버전이라도 새 AI 실행이면 새 행을 추가하고 옛 스냅샷을 덮어쓰지 않는다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE standard_request (
    id                    UUID          PRIMARY KEY DEFAULT uuidv7(),
    inquiry_problem_id    UUID          NOT NULL,
    -- 어느 문의 버전까지의 대화로 정리했는지
    inquiry_version       INT           NOT NULL,
    -- 이 요청서를 만든 AI 실행 (출처 보존을 위해 필수)
    agent_run_id          UUID          NOT NULL,
    -- HOW_TO / ACCESS_REQUEST / OBSERVED_SYMPTOM / FEATURE_REQUEST / IMPROVEMENT_REQUEST / UNKNOWN / OUT_OF_SCOPE
    intent                VARCHAR(30)   NOT NULL,
    -- KNOWLEDGE_QA / SERVICE_REQUEST / INVESTIGATION / INCIDENT / CHANGE_REQUEST / REVIEW_REQUIRED / OUT_OF_SCOPE
    work_type             VARCHAR(30)   NOT NULL,
    -- 사용자가 현상을 겪은 시스템·기능 (모르면 NULL)
    reported_system_id    UUID,
    reported_feature_id   UUID,
    -- 이 요청을 맡을 시스템. Spring이 이 시스템의 주담당자에게 배정한다 (확인 전이면 NULL)
    responsible_system_id UUID,
    -- 시스템을 확정하기 전 AI가 본 후보와 근거
    system_candidates     JSONB         NOT NULL DEFAULT '[]',
    summary               TEXT          NOT NULL,
    -- 사용자가 직접 말한 사실 / AI 추정 / 아직 받지 못한 정보를 섞지 않고 나눠 둔다
    confirmed_facts       JSONB         NOT NULL DEFAULT '[]',
    assumptions           JSONB         NOT NULL DEFAULT '[]',
    missing_items         JSONB         NOT NULL DEFAULT '[]',
    -- 의도마다 다른 항목 (현상: 기대/실제 동작, 기능 요청: AS-IS·TO-BE 등)
    type_detail           JSONB         NOT NULL DEFAULT '{}',
    related_knowledge     JSONB         NOT NULL DEFAULT '[]',
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_standard_request_problem_version_run UNIQUE (inquiry_problem_id, inquiry_version, agent_run_id),
    CONSTRAINT fk_standard_request_inquiry_problem FOREIGN KEY (inquiry_problem_id) REFERENCES inquiry_problem (id),
    CONSTRAINT fk_standard_request_agent_run FOREIGN KEY (agent_run_id) REFERENCES agent_run (id),
    CONSTRAINT fk_standard_request_reported_system FOREIGN KEY (reported_system_id) REFERENCES it_system (id),
    CONSTRAINT fk_standard_request_reported_feature FOREIGN KEY (reported_feature_id) REFERENCES system_feature (id),
    CONSTRAINT fk_standard_request_responsible_system FOREIGN KEY (responsible_system_id) REFERENCES it_system (id),
    CONSTRAINT ck_standard_request_intent CHECK (intent IN ('HOW_TO', 'ACCESS_REQUEST', 'OBSERVED_SYMPTOM',
        'FEATURE_REQUEST', 'IMPROVEMENT_REQUEST', 'UNKNOWN', 'OUT_OF_SCOPE')),
    CONSTRAINT ck_standard_request_work_type CHECK (work_type IN ('KNOWLEDGE_QA', 'SERVICE_REQUEST', 'INVESTIGATION',
        'INCIDENT', 'CHANGE_REQUEST', 'REVIEW_REQUIRED', 'OUT_OF_SCOPE'))
);
