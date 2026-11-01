-- 시스템 기능: 시스템 안의 기능 목록 (docs/ERD.md "시스템 기능 system_feature")
-- 기능 → 증상 → 원인 같은 관계는 Neo4j에 두고, 이 테이블은 기능 목록의 정본만 맡는다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE system_feature (
    id           UUID          PRIMARY KEY DEFAULT uuidv7(),
    it_system_id UUID          NOT NULL,
    -- AI가 기능을 제안할 때 쓰는 코드 (예: DOCUMENT_VIEW). 시스템마다 따로 매긴다
    code         VARCHAR(50)   NOT NULL,
    name         VARCHAR(100)  NOT NULL,
    -- 비슷한 기능끼리 책임 경계를 구분하는 설명
    description  TEXT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ,
    -- 같은 시스템에 같은 기능 코드를 두 번 두지 않는다 (다른 시스템이면 같은 코드 허용)
    CONSTRAINT uk_system_feature_system_code UNIQUE (it_system_id, code),
    CONSTRAINT fk_system_feature_it_system FOREIGN KEY (it_system_id) REFERENCES it_system (id)
);
