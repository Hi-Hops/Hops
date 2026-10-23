-- 테스트 전용 테이블. BaseEntity가 ERD 공통 컬럼(id, created_at, updated_at, deleted_at)과 맞는지 확인한다.
-- 반복 실행 마이그레이션(R__)이라 실제 마이그레이션 번호(V1, V2 ...)와 충돌하지 않는다. 테스트 DB(hops_test)에만 생긴다.
CREATE TABLE IF NOT EXISTS test_sample (
    id         UUID         PRIMARY KEY DEFAULT uuidv7(),
    name       VARCHAR(50)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
