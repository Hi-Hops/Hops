-- 대화 메시지: 문의 안의 사용자·AI 메시지 (docs/ERD.md "대화 메시지 message", 6.1절)
-- 저장이 확정된 최종 메시지만 둔다. AI 답변은 실행 접수 때 예약한 answerMessageId를 이 표의 id로 써서 완성 후 한 번 저장한다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE message (
    id             UUID          PRIMARY KEY DEFAULT uuidv7(),
    inquiry_id     UUID          NOT NULL,
    -- 문의 안에서 보낸 순서 (1부터). 같은 시각이어도 순서를 보장한다
    sequence_no    INT           NOT NULL,
    -- USER / AI
    sender_type    VARCHAR(30)   NOT NULL,
    -- 현재 내용. 정정 전 원문은 message_revision에 남긴다 (최대 4,000자는 서버 입력 검증에서 막는다)
    content        TEXT          NOT NULL,
    -- 현재 내용이 몇 번째 차수인지 (1부터, 정정할 때마다 1씩 증가)
    revision       INT           NOT NULL DEFAULT 1,
    -- 요청 ID·실행 ID·문제 연결·질문 묶음·가이드 같은 서버 관리 메타데이터 (구조는 docs/DATA.md 11.3절)
    payload        JSONB         NOT NULL DEFAULT '{}',
    -- 답변 생성 중·완료·실패: GENERATING / COMPLETED / FAILED (신규 계약의 저장 행은 COMPLETED)
    message_status VARCHAR(30)   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ,
    CONSTRAINT uk_message_inquiry_sequence UNIQUE (inquiry_id, sequence_no),
    CONSTRAINT fk_message_inquiry FOREIGN KEY (inquiry_id) REFERENCES inquiry (id),
    CONSTRAINT ck_message_sender_type CHECK (sender_type IN ('USER', 'AI')),
    CONSTRAINT ck_message_status CHECK (message_status IN ('GENERATING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_message_revision CHECK (revision >= 1)
);
