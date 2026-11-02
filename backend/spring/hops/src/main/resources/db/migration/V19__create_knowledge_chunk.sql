-- 검색 조각: 문서를 검색하기 좋게 잘게 나눈 조각과 임베딩 (docs/ERD.md "검색 조각 knowledge_chunk", 6.1절)
-- 추가만 하는 기록 테이블이다. Vector 저장소는 pgvector이고, 같은 DB의 hops_knowledge 스키마에 둬 업무 테이블과 FK를 유지한다.
-- AI 서버 전용 계정에 이 스키마의 조각 쓰기 권한만 주는 작업은 계정 구성 때 별도로 한다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.

-- pgvector 확장. trusted 확장이 아니라 슈퍼유저만 만들 수 있다.
-- 로컬은 슈퍼유저로 미리 한 번 켜 두면(Spring README "pgvector") 여기서는 "이미 있음"으로 넘어간다.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS hops_knowledge;

CREATE TABLE hops_knowledge.knowledge_chunk (
    id                            UUID          PRIMARY KEY DEFAULT uuidv7(),
    -- 어느 문서 버전에서 잘라낸 조각인지 (답변 근거 출처 추적)
    knowledge_document_version_id UUID          NOT NULL,
    -- 문서 안에서 몇 번째 조각인지 (1부터)
    chunk_no                      INT           NOT NULL,
    -- 검색되면 AI에게 넘길 본문
    content                       TEXT          NOT NULL,
    -- 의미가 비슷한 조각을 찾는 임베딩. 차원 수는 임베딩 모델을 정할 때 함께 정한다 (그 전에는 차원 제한 없음)
    embedding                     vector        NOT NULL,
    -- 잘라낸 원문 버전의 content_digest와 이 조각 본문의 SHA-256. 모델 입력 전에 원문과 대조한다
    content_digest                CHAR(64)      NOT NULL,
    chunk_digest                  CHAR(64)      NOT NULL,
    created_at                    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_knowledge_chunk_version_chunk UNIQUE (knowledge_document_version_id, chunk_no),
    CONSTRAINT fk_knowledge_chunk_document_version FOREIGN KEY (knowledge_document_version_id)
        REFERENCES public.knowledge_document_version (id)
);
