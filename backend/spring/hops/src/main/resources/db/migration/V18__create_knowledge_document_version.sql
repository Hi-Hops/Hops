-- 문서 버전: 문서 내용을 덮어쓰지 않고 버전별로 보관 (docs/ERD.md "문서 버전 knowledge_document_version", 6.1절)
-- 추가만 하는 기록 테이블이다. 승인 원문·출처·적용 조건을 버전마다 고정한다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE knowledge_document_version (
    id                    UUID          PRIMARY KEY DEFAULT uuidv7(),
    knowledge_document_id UUID          NOT NULL,
    -- 몇 번째 버전인지 (1부터). 예전 답변이 참고한 버전을 남기려고
    version_no            INT           NOT NULL,
    content               TEXT          NOT NULL,
    -- 승인 원문의 UTF-8 바이트 SHA-256 (16진수 64자). 검색 조각이 같은 원문에서 나왔는지 대조한다
    content_digest        CHAR(64)      NOT NULL,
    -- origin·출처·적용 조건. 사례(CASE) 문서면 caseId·caseVersion·indexRequests도 둔다 (docs/DATA.md 11.3절)
    source_metadata       JSONB         NOT NULL,
    -- 이 버전의 검수 상태. 승인된 버전만 검색에 쓴다
    review_status         VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_knowledge_document_version_document_version UNIQUE (knowledge_document_id, version_no),
    CONSTRAINT fk_knowledge_document_version_document FOREIGN KEY (knowledge_document_id) REFERENCES knowledge_document (id),
    CONSTRAINT ck_knowledge_document_version_review_status CHECK (review_status IN ('PENDING', 'APPROVED'))
);
