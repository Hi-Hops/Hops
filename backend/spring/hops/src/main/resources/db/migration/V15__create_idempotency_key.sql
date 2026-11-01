-- 중복 요청 방지 기록: 같은 변경 요청이 다시 와도 한 번만 반영하고 처음 결과를 돌려주려고 (docs/ERD.md "중복 요청 방지 기록 idempotency_key")
-- 업무 변경과 같은 트랜잭션으로 저장한다. 업무 저장이 롤백되면 이 기록도 남지 않는다.
-- 만료일시가 지난 행은 정리 작업이 실제로 지운다 (업무 기록이 아닌 기술 기록).
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE idempotency_key (
    id               UUID           PRIMARY KEY DEFAULT uuidv7(),
    -- 다른 사람이 같은 키를 보내도 남의 결과를 받지 않게 요청자별로 나눈다
    actor_member_id  UUID           NOT NULL,
    http_method      VARCHAR(10)    NOT NULL,
    request_path     VARCHAR(500)   NOT NULL,
    -- 프론트가 Idempotency-Key 헤더로 보낸 값
    idempotency_key  VARCHAR(100)   NOT NULL,
    -- 같은 키로 내용이 다른 요청이 오면 거절하려고 (본문 SHA-256, 16진수 64자)
    request_hash     VARCHAR(64)    NOT NULL,
    -- 처음 처리했을 때의 HTTP 상태와 응답을 그대로 다시 돌려준다
    response_status  INT            NOT NULL,
    response_body    JSONB          NOT NULL,
    -- 이 시각까지 같은 요청을 중복으로 판단 (생성 후 최소 24시간)
    expires_at       TIMESTAMPTZ    NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_idempotency_key_request UNIQUE (actor_member_id, http_method, request_path, idempotency_key),
    CONSTRAINT fk_idempotency_key_actor FOREIGN KEY (actor_member_id) REFERENCES member (id)
);
