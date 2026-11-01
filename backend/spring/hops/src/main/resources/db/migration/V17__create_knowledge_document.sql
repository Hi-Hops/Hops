-- 지식 문서: 직원 가이드·정책·담당자 런북 (docs/ERD.md "지식 문서 knowledge_document")
-- 해결 사례는 resolved_case에 따로 둔다. 본문은 덮어쓰지 않고 knowledge_document_version에 버전별로 둔다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE knowledge_document (
    id            UUID          PRIMARY KEY DEFAULT uuidv7(),
    title         VARCHAR(200)  NOT NULL,
    -- EMPLOYEE_GUIDE / POLICY / STAFF_RUNBOOK
    document_type VARCHAR(30)   NOT NULL,
    -- 어느 시스템 이야기인지 (여러 시스템 공통 정책이면 NULL)
    it_system_id  UUID,
    -- 검색 전에 걸러내는 공개 범위: EMPLOYEE / STAFF
    access_level  VARCHAR(30)   NOT NULL,
    -- 어느 공개 데이터셋·레코드에서 만들었는지
    source_data   JSONB,
    -- 테스트 정답이 검색 자료에 섞이지 않게: RAG / DEV / EVAL
    data_split    VARCHAR(30)   NOT NULL,
    -- 사람이 검수한 문서(APPROVED)만 검색에 쓴다
    review_status VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    -- 가상 데이터와 실제 운영 기록을 구분한다
    is_synthetic  BOOLEAN       NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,
    CONSTRAINT fk_knowledge_document_it_system FOREIGN KEY (it_system_id) REFERENCES it_system (id)
);
