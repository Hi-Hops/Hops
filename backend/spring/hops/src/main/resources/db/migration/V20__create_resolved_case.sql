-- 해결 사례: 담당자의 해결·구현 기록으로 만든 사례 (docs/ERD.md "해결 사례 resolved_case", 6.2절)
-- Curator가 초안(DRAFT)을 만들고, 시스템 주담당자가 승인(APPROVED)한 뒤 Vector·Neo4j에 모두 반영(SUCCEEDED)돼야 검색에 쓴다.
-- 초안을 고치면 현재 행의 version_no를 올린다. 승인 뒤에는 내용을 고치지 않는다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE resolved_case (
    id                    UUID          PRIMARY KEY DEFAULT uuidv7(),
    -- 사람이 부르기 쉬운 사례 번호 (예: CASE-0001)
    case_number           VARCHAR(20)   NOT NULL,
    version_no            INT           NOT NULL DEFAULT 1,
    -- 어느 해결·구현 기록에서 만든 사례인지. 처리 기록 하나에서 사례는 최대 하나
    source_work_log_id    UUID          NOT NULL,
    it_system_id          UUID          NOT NULL,
    system_feature_id     UUID,
    -- RESOLUTION / IMPLEMENTATION
    case_type             VARCHAR(30)   NOT NULL,
    title                 VARCHAR(200)  NOT NULL,
    -- 해결 사례는 증상, 기능 구현 사례는 변경 목적
    problem_statement     TEXT          NOT NULL,
    preconditions         TEXT,
    confirmed_facts       JSONB         NOT NULL DEFAULT '[]',
    confirmed_cause       TEXT,
    action_detail         TEXT          NOT NULL,
    verification_result   TEXT          NOT NULL,
    -- 이 사례를 쓸 수 있는 조건과 쓸 수 없는 경우 (승인 때 반드시 확인)
    applicability         TEXT,
    applied_version       VARCHAR(100),
    -- EMPLOYEE / STAFF
    access_level          VARCHAR(30)   NOT NULL,
    -- SYNTHETIC / DEMO_VERIFIED / PRODUCTION
    origin_type           VARCHAR(30)   NOT NULL,
    -- DRAFT / APPROVED
    case_status           VARCHAR(30)   NOT NULL DEFAULT 'DRAFT',
    curator_run_id        UUID,
    duplicate_candidates  JSONB         NOT NULL DEFAULT '[]',
    relation_candidates   JSONB         NOT NULL DEFAULT '[]',
    approved_by_member_id UUID,
    approved_at           TIMESTAMPTZ,
    -- 승인 후 각 검색 저장소 반영 결과: PENDING / SUCCEEDED / FAILED (진행 중·결과 모름도 PENDING으로 비공개)
    vector_status         VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    graph_status          VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    graph_node_id         VARCHAR(100),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT uk_resolved_case_case_number UNIQUE (case_number),
    CONSTRAINT uk_resolved_case_source_work_log UNIQUE (source_work_log_id),
    CONSTRAINT fk_resolved_case_source_work_log FOREIGN KEY (source_work_log_id) REFERENCES ticket_work_log (id),
    CONSTRAINT fk_resolved_case_it_system FOREIGN KEY (it_system_id) REFERENCES it_system (id),
    CONSTRAINT fk_resolved_case_system_feature FOREIGN KEY (system_feature_id) REFERENCES system_feature (id),
    CONSTRAINT fk_resolved_case_curator_run FOREIGN KEY (curator_run_id) REFERENCES agent_run (id),
    CONSTRAINT fk_resolved_case_approved_by FOREIGN KEY (approved_by_member_id) REFERENCES member (id),
    CONSTRAINT ck_resolved_case_type CHECK (case_type IN ('RESOLUTION', 'IMPLEMENTATION')),
    CONSTRAINT ck_resolved_case_access_level CHECK (access_level IN ('EMPLOYEE', 'STAFF')),
    CONSTRAINT ck_resolved_case_origin_type CHECK (origin_type IN ('SYNTHETIC', 'DEMO_VERIFIED', 'PRODUCTION')),
    CONSTRAINT ck_resolved_case_status CHECK (case_status IN ('DRAFT', 'APPROVED')),
    CONSTRAINT ck_resolved_case_vector_status CHECK (vector_status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_resolved_case_graph_status CHECK (graph_status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    -- 승인(APPROVED)이면 승인자·승인일시가 둘 다 있고, 초안(DRAFT)이면 둘 다 없다
    CONSTRAINT ck_resolved_case_approval CHECK (
        (case_status = 'APPROVED' AND approved_by_member_id IS NOT NULL AND approved_at IS NOT NULL)
        OR (case_status = 'DRAFT' AND approved_by_member_id IS NULL AND approved_at IS NULL))
);
