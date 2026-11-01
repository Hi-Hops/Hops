-- 문서 버전: 문서 내용을 덮어쓰지 않고 버전별로 보관 (docs/ERD.md "문서 버전 knowledge_document_version")
-- 추가만 하는 기록 테이블이다. 평가는 검수해 고정한 버전으로 한다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE knowledge_document_version (
    id                    UUID          PRIMARY KEY DEFAULT uuidv7(),
    knowledge_document_id UUID          NOT NULL,
    -- 몇 번째 버전인지 (1부터). 예전 답변이 참고한 버전을 남기려고
    version_no            INT           NOT NULL,
    content               TEXT          NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_knowledge_document_version_document_version UNIQUE (knowledge_document_id, version_no),
    CONSTRAINT fk_knowledge_document_version_document FOREIGN KEY (knowledge_document_id) REFERENCES knowledge_document (id)
);
