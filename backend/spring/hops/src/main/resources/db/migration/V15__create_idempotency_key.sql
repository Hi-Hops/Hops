-- 중복 요청 방지 기록: 같은 변경 요청을 한 번만 반영하고 결과를 다시 확인하려고 (docs/ERD.md "중복 요청 방지 기록 idempotency_key", 6.1절)
-- 업무 변경과 같은 트랜잭션으로 저장한다. 접수(PENDING) 기록은 남기고, 업무 반영과 최종 결과를 한 거래로 저장한다.
-- 만료 뒤에도 행을 지우지 않는다. 응답 본문만 비우고(tombstone) 키·해시·operation·resource 표식은 남겨 같은 키의 새 실행을 막는다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE idempotency_key (
    id               UUID           PRIMARY KEY DEFAULT uuidv7(),
    -- 다른 사람이 같은 키를 보내도 남의 결과를 받지 않게 요청자별로 나눈다
    actor_member_id  UUID           NOT NULL,
    -- 어느 계약의 요청인지 (예: hops-system-1)
    contract_version VARCHAR(50)    NOT NULL,
    -- 같은 키로 다른 메서드·경로를 보내면 거절하려고 처음 값을 고정한다
    http_method      VARCHAR(10)    NOT NULL,
    request_path     VARCHAR(500)   NOT NULL,
    -- 프론트가 Idempotency-Key 헤더로 보낸 값
    idempotency_key  VARCHAR(100)   NOT NULL,
    -- 같은 키로 내용이 다른 요청이 오면 거절하려고 (본문 SHA-256, 16진수 64자)
    request_hash     VARCHAR(64)    NOT NULL,
    -- 이 요청으로 시작한 업무 작업 ID (재확인 조회에 쓴다)
    operation_id     UUID           NOT NULL,
    -- PENDING / SUCCEEDED / REJECTED / UNKNOWN
    operation_state  VARCHAR(30)    NOT NULL,
    -- 만들어지거나 바뀐 업무 대상 (예: INQUIRY + 문의 ID)
    resource_type    VARCHAR(30),
    resource_id      UUID,
    -- 처음 처리한 HTTP 상태와 응답. 접수 중이거나 만료 정리(tombstone) 뒤에는 NULL
    response_status  INT,
    response_body    JSONB,
    -- 이 시각까지 상세 응답을 보존한다 (생성 후 최소 24시간)
    expires_at       TIMESTAMPTZ    NOT NULL,
    -- 만료돼 응답 본문을 비운 시각
    tombstoned_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_idempotency_key_actor_contract_key UNIQUE (actor_member_id, contract_version, idempotency_key),
    CONSTRAINT uk_idempotency_key_operation UNIQUE (operation_id),
    CONSTRAINT fk_idempotency_key_actor FOREIGN KEY (actor_member_id) REFERENCES member (id),
    CONSTRAINT ck_idempotency_key_operation_state CHECK (operation_state IN ('PENDING', 'SUCCEEDED', 'REJECTED', 'UNKNOWN'))
);
