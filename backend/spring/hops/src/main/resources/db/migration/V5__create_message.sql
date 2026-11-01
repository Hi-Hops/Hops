-- 대화 메시지: 문의 안의 사용자·AI 메시지 (docs/ERD.md "대화 메시지 message")
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE message (
    id          UUID          PRIMARY KEY DEFAULT uuidv7(),
    inquiry_id  UUID          NOT NULL,
    -- 문의 안에서 보낸 순서 (1부터). 같은 시각이어도 순서를 보장한다
    sequence_no INT           NOT NULL,
    -- USER / AI
    sender_type VARCHAR(30)   NOT NULL,
    -- 현재 내용. 정정 전 원문은 message_revision에 남긴다 (최대 4,000자는 서버 입력 검증에서 막는다)
    content     TEXT          NOT NULL,
    -- 단계별 가이드·티켓 카드처럼 모양이 있는 답변 데이터 (내부 구조는 후속 계약에서 정한다)
    payload     JSONB,
    -- 답변 생성 중·완료·실패: GENERATING / COMPLETED / FAILED
    message_status VARCHAR(30) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uk_message_inquiry_sequence UNIQUE (inquiry_id, sequence_no),
    CONSTRAINT fk_message_inquiry FOREIGN KEY (inquiry_id) REFERENCES inquiry (id)
);
