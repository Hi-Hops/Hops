-- 관계 데이터: 증상 → 가능 원인 → 확인 항목 → 직원용 질문 문구, 그리고 승인 사례와의 연결
-- (docs/ai/AGENT_ARCHITECTURE.md 8절 RelationSource의 1차 구현, docs/ERD.md "관계 데이터")
-- Neo4j는 2차에 같은 인터페이스 뒤로 교체하므로, 노드는 테이블, 관계는 연결 테이블로 1:1 대응시켰다.
-- 연결은 후보다. 연결만으로 현재 문의의 원인을 확정하지 않는다. 근거 없는 연결은 만들지 않으므로
-- 모든 행이 근거 문서 버전(source_document_version_id)을 반드시 가리킨다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.

-- 증상: 직원이 말하는 현상. 원인을 담지 않는다
CREATE TABLE relation_symptom (
    id                         UUID          PRIMARY KEY DEFAULT uuidv7(),
    -- 정본 카탈로그 ID (예: sym-ea-doc-not-listed). 문서·시드가 바뀌어도 같은 항목을 가리키려고
    catalog_key                VARCHAR(100)  NOT NULL,
    label                      VARCHAR(200)  NOT NULL,
    it_system_id               UUID          NOT NULL,
    -- 증상이 나타나는 기능
    system_feature_id          UUID          NOT NULL,
    -- 검색·질문 전에 걸러내는 공개 범위: EMPLOYEE / STAFF
    access_level               VARCHAR(30)   NOT NULL,
    -- 승인된 항목만 상담에 쓴다
    review_status              VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    -- 이 항목의 근거가 된 문서 버전 (문서 ID·버전은 문서 버전에서 읽는다)
    source_document_version_id UUID          NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at                 TIMESTAMPTZ,
    CONSTRAINT uk_relation_symptom_catalog_key UNIQUE (catalog_key),
    CONSTRAINT fk_relation_symptom_it_system FOREIGN KEY (it_system_id) REFERENCES it_system (id),
    CONSTRAINT fk_relation_symptom_system_feature FOREIGN KEY (system_feature_id) REFERENCES system_feature (id),
    CONSTRAINT fk_relation_symptom_source_version FOREIGN KEY (source_document_version_id) REFERENCES knowledge_document_version (id),
    CONSTRAINT ck_relation_symptom_access_level CHECK (access_level IN ('EMPLOYEE', 'STAFF')),
    CONSTRAINT ck_relation_symptom_review_status CHECK (review_status IN ('PENDING', 'APPROVED')),
    CONSTRAINT ck_relation_symptom_label CHECK (btrim(label) <> '')
);
-- PostgreSQL은 FK 컬럼에 인덱스를 자동으로 만들지 않는다. 시스템·기능으로 증상을 찾고 근거 문서에서 거꾸로 찾으려고 둔다.
CREATE INDEX idx_relation_symptom_it_system_id ON relation_symptom (it_system_id);
CREATE INDEX idx_relation_symptom_system_feature_id ON relation_symptom (system_feature_id);
CREATE INDEX idx_relation_symptom_source_version ON relation_symptom (source_document_version_id);

-- 가능 원인: 담당자 전용. 직원에게 이름이 나가지 않는다
CREATE TABLE relation_cause (
    id                         UUID          PRIMARY KEY DEFAULT uuidv7(),
    catalog_key                VARCHAR(100)  NOT NULL,
    label                      VARCHAR(200)  NOT NULL,
    it_system_id               UUID          NOT NULL,
    access_level               VARCHAR(30)   NOT NULL,
    review_status              VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    source_document_version_id UUID          NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at                 TIMESTAMPTZ,
    CONSTRAINT uk_relation_cause_catalog_key UNIQUE (catalog_key),
    CONSTRAINT fk_relation_cause_it_system FOREIGN KEY (it_system_id) REFERENCES it_system (id),
    CONSTRAINT fk_relation_cause_source_version FOREIGN KEY (source_document_version_id) REFERENCES knowledge_document_version (id),
    CONSTRAINT ck_relation_cause_access_level CHECK (access_level IN ('EMPLOYEE', 'STAFF')),
    CONSTRAINT ck_relation_cause_review_status CHECK (review_status IN ('PENDING', 'APPROVED')),
    CONSTRAINT ck_relation_cause_label CHECK (btrim(label) <> '')
);
CREATE INDEX idx_relation_cause_it_system_id ON relation_cause (it_system_id);
CREATE INDEX idx_relation_cause_source_version ON relation_cause (source_document_version_id);

-- 확인 항목: 원인을 가르는 질문거리. 직원이 스스로 답할 수 있는 항목만 employee_disclosable = true
CREATE TABLE relation_check_item (
    id                         UUID          PRIMARY KEY DEFAULT uuidv7(),
    catalog_key                VARCHAR(100)  NOT NULL,
    label                      VARCHAR(200)  NOT NULL,
    it_system_id               UUID          NOT NULL,
    -- 직원에게 질문으로 나갈 수 있는지. 담당자가 시스템 기록으로만 확인하는 항목은 false
    employee_disclosable       BOOLEAN       NOT NULL DEFAULT false,
    access_level               VARCHAR(30)   NOT NULL,
    review_status              VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    source_document_version_id UUID          NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at                 TIMESTAMPTZ,
    CONSTRAINT uk_relation_check_item_catalog_key UNIQUE (catalog_key),
    CONSTRAINT fk_relation_check_item_it_system FOREIGN KEY (it_system_id) REFERENCES it_system (id),
    CONSTRAINT fk_relation_check_item_source_version FOREIGN KEY (source_document_version_id) REFERENCES knowledge_document_version (id),
    CONSTRAINT ck_relation_check_item_access_level CHECK (access_level IN ('EMPLOYEE', 'STAFF')),
    CONSTRAINT ck_relation_check_item_review_status CHECK (review_status IN ('PENDING', 'APPROVED')),
    CONSTRAINT ck_relation_check_item_label CHECK (btrim(label) <> '')
);
CREATE INDEX idx_relation_check_item_it_system_id ON relation_check_item (it_system_id);
CREATE INDEX idx_relation_check_item_source_version ON relation_check_item (source_document_version_id);

-- 직원용 질문 문구: 확인 항목 하나에 여러 개를 둘 수 있다. 직원 상담은 승인되고 공개 표시가 있는 문구만 쓴다
CREATE TABLE relation_question_template (
    id                         UUID          PRIMARY KEY DEFAULT uuidv7(),
    catalog_key                VARCHAR(100)  NOT NULL,
    check_item_id              UUID          NOT NULL,
    question_text              VARCHAR(500)  NOT NULL,
    employee_disclosable       BOOLEAN       NOT NULL DEFAULT false,
    access_level               VARCHAR(30)   NOT NULL,
    review_status              VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    source_document_version_id UUID          NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at                 TIMESTAMPTZ,
    CONSTRAINT uk_relation_question_template_catalog_key UNIQUE (catalog_key),
    CONSTRAINT fk_relation_question_template_check_item FOREIGN KEY (check_item_id) REFERENCES relation_check_item (id),
    CONSTRAINT fk_relation_question_template_source_version FOREIGN KEY (source_document_version_id) REFERENCES knowledge_document_version (id),
    CONSTRAINT ck_relation_question_template_access_level CHECK (access_level IN ('EMPLOYEE', 'STAFF')),
    CONSTRAINT ck_relation_question_template_review_status CHECK (review_status IN ('PENDING', 'APPROVED')),
    CONSTRAINT ck_relation_question_template_text CHECK (btrim(question_text) <> ''),
    -- 직원에게 나갈 수 있는 문구는 직원 공개 범위여야 한다. 확인 항목의 공개 표시와의 일치는 테이블을 가로지르므로 시드 검사와 테스트가 확인한다
    CONSTRAINT ck_relation_question_template_disclosable CHECK (NOT employee_disclosable OR access_level = 'EMPLOYEE')
);
CREATE INDEX idx_relation_question_template_check_item_id ON relation_question_template (check_item_id);
CREATE INDEX idx_relation_question_template_source_version ON relation_question_template (source_document_version_id);

-- 증상 → 가능 원인 (MAY_HAVE_CAUSE)
CREATE TABLE relation_symptom_cause (
    id                         UUID          PRIMARY KEY DEFAULT uuidv7(),
    symptom_id                 UUID          NOT NULL,
    cause_id                   UUID          NOT NULL,
    access_level               VARCHAR(30)   NOT NULL,
    review_status              VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    source_document_version_id UUID          NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at                 TIMESTAMPTZ,
    CONSTRAINT uk_relation_symptom_cause UNIQUE (symptom_id, cause_id),
    CONSTRAINT fk_relation_symptom_cause_symptom FOREIGN KEY (symptom_id) REFERENCES relation_symptom (id),
    CONSTRAINT fk_relation_symptom_cause_cause FOREIGN KEY (cause_id) REFERENCES relation_cause (id),
    CONSTRAINT fk_relation_symptom_cause_source_version FOREIGN KEY (source_document_version_id) REFERENCES knowledge_document_version (id),
    CONSTRAINT ck_relation_symptom_cause_access_level CHECK (access_level IN ('EMPLOYEE', 'STAFF')),
    CONSTRAINT ck_relation_symptom_cause_review_status CHECK (review_status IN ('PENDING', 'APPROVED'))
);
-- 위 유일 제약은 symptom_id로 시작하므로 원인에서 거꾸로 찾는 인덱스만 따로 둔다
CREATE INDEX idx_relation_symptom_cause_cause_id ON relation_symptom_cause (cause_id);
CREATE INDEX idx_relation_symptom_cause_source_version ON relation_symptom_cause (source_document_version_id);

-- 원인 → 원인을 가르는 확인 항목 (CHECKED_BY)
CREATE TABLE relation_cause_check (
    id                         UUID          PRIMARY KEY DEFAULT uuidv7(),
    cause_id                   UUID          NOT NULL,
    check_item_id              UUID          NOT NULL,
    access_level               VARCHAR(30)   NOT NULL,
    review_status              VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    source_document_version_id UUID          NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at                 TIMESTAMPTZ,
    CONSTRAINT uk_relation_cause_check UNIQUE (cause_id, check_item_id),
    CONSTRAINT fk_relation_cause_check_cause FOREIGN KEY (cause_id) REFERENCES relation_cause (id),
    CONSTRAINT fk_relation_cause_check_check_item FOREIGN KEY (check_item_id) REFERENCES relation_check_item (id),
    CONSTRAINT fk_relation_cause_check_source_version FOREIGN KEY (source_document_version_id) REFERENCES knowledge_document_version (id),
    CONSTRAINT ck_relation_cause_check_access_level CHECK (access_level IN ('EMPLOYEE', 'STAFF')),
    CONSTRAINT ck_relation_cause_check_review_status CHECK (review_status IN ('PENDING', 'APPROVED'))
);
CREATE INDEX idx_relation_cause_check_check_item_id ON relation_cause_check (check_item_id);
CREATE INDEX idx_relation_cause_check_source_version ON relation_cause_check (source_document_version_id);

-- 승인 사례 → 사례가 다룬 증상 (ADDRESSES). 근거는 그 사례의 CASE 문서 버전이다
CREATE TABLE relation_case_symptom (
    id                         UUID          PRIMARY KEY DEFAULT uuidv7(),
    resolved_case_id           UUID          NOT NULL,
    symptom_id                 UUID          NOT NULL,
    access_level               VARCHAR(30)   NOT NULL,
    review_status              VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    source_document_version_id UUID          NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at                 TIMESTAMPTZ,
    CONSTRAINT uk_relation_case_symptom UNIQUE (resolved_case_id, symptom_id),
    CONSTRAINT fk_relation_case_symptom_case FOREIGN KEY (resolved_case_id) REFERENCES resolved_case (id),
    CONSTRAINT fk_relation_case_symptom_symptom FOREIGN KEY (symptom_id) REFERENCES relation_symptom (id),
    CONSTRAINT fk_relation_case_symptom_source_version FOREIGN KEY (source_document_version_id) REFERENCES knowledge_document_version (id),
    CONSTRAINT ck_relation_case_symptom_access_level CHECK (access_level IN ('EMPLOYEE', 'STAFF')),
    CONSTRAINT ck_relation_case_symptom_review_status CHECK (review_status IN ('PENDING', 'APPROVED'))
);
CREATE INDEX idx_relation_case_symptom_symptom_id ON relation_case_symptom (symptom_id);
CREATE INDEX idx_relation_case_symptom_source_version ON relation_case_symptom (source_document_version_id);

-- 승인 사례 → 사례가 쓴 확인 항목 (USES_CHECK)
CREATE TABLE relation_case_check (
    id                         UUID          PRIMARY KEY DEFAULT uuidv7(),
    resolved_case_id           UUID          NOT NULL,
    check_item_id              UUID          NOT NULL,
    access_level               VARCHAR(30)   NOT NULL,
    review_status              VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    source_document_version_id UUID          NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at                 TIMESTAMPTZ,
    CONSTRAINT uk_relation_case_check UNIQUE (resolved_case_id, check_item_id),
    CONSTRAINT fk_relation_case_check_case FOREIGN KEY (resolved_case_id) REFERENCES resolved_case (id),
    CONSTRAINT fk_relation_case_check_check_item FOREIGN KEY (check_item_id) REFERENCES relation_check_item (id),
    CONSTRAINT fk_relation_case_check_source_version FOREIGN KEY (source_document_version_id) REFERENCES knowledge_document_version (id),
    CONSTRAINT ck_relation_case_check_access_level CHECK (access_level IN ('EMPLOYEE', 'STAFF')),
    CONSTRAINT ck_relation_case_check_review_status CHECK (review_status IN ('PENDING', 'APPROVED'))
);
CREATE INDEX idx_relation_case_check_check_item_id ON relation_case_check (check_item_id);
CREATE INDEX idx_relation_case_check_source_version ON relation_case_check (source_document_version_id);
