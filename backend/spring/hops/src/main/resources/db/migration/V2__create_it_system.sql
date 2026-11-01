-- 시스템: 문의를 받는 업무시스템과 시스템별 주담당자 (docs/ERD.md "시스템 it_system")
-- 문의는 부서가 아니라 이 테이블의 주담당자에게 배정한다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE it_system (
    id                      UUID          PRIMARY KEY DEFAULT uuidv7(),
    -- AI가 시스템 후보를 제안할 때 쓰는 코드 (예: E_APPROVAL)
    code                    VARCHAR(30)   NOT NULL,
    name                    VARCHAR(100)  NOT NULL,
    -- 사용자가 시스템을 고르지 않았을 때 AI가 문의 내용과 비교하는 지원 범위 설명
    description             TEXT,
    -- 시스템당 주담당자(PRIMARY) 1명. 한 회원이 여러 시스템을 맡을 수 있다
    primary_owner_member_id UUID          NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at              TIMESTAMPTZ,
    CONSTRAINT uk_it_system_code UNIQUE (code),
    CONSTRAINT fk_it_system_primary_owner FOREIGN KEY (primary_owner_member_id) REFERENCES member (id)
);
