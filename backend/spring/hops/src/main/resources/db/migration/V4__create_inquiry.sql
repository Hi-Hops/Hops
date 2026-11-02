-- 문의: 대화 세션 1개 = 문의 1건 (docs/ERD.md "문의 inquiry", 6.1절)
-- current_run_id의 FK는 agent_run을 만드는 V7에서 ALTER TABLE로 붙인다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE inquiry (
    id                  UUID          PRIMARY KEY DEFAULT uuidv7(),
    -- 사람이 부르기 쉬운 문의 번호 (예: Q-0142)
    inquiry_number      VARCHAR(20)   NOT NULL,
    requester_member_id UUID          NOT NULL,
    title               VARCHAR(200)  NOT NULL,
    -- CLOSED는 모든 문제가 범위 밖이고 범위 안내를 저장한 문의에만 쓴다 (무응답 자동 종료 아님)
    inquiry_status      VARCHAR(30)   NOT NULL,
    -- 메시지 추가·정정 때마다 1씩 늘린다. 예전 버전으로 분석한 늦은 AI 결과를 버리는 기준
    version             INT           NOT NULL DEFAULT 1,
    -- 지금 이 문의를 분석 중인(마지막으로 시작한) 상담 실행. Curator 실행은 가리키지 않는다
    current_run_id      UUID,
    -- AI 결과를 적용할 때마다 1씩 늘린다. 현재 입력 버전과 둘 다 맞는 결과만 적용한다
    result_revision     INT           NOT NULL DEFAULT 1,
    summary             TEXT,
    -- 요약이 대화의 몇 번째 메시지(message.sequence_no)까지 반영됐는지
    summary_until_seq   INT,
    summary_updated_at  TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uk_inquiry_inquiry_number UNIQUE (inquiry_number),
    CONSTRAINT fk_inquiry_requester FOREIGN KEY (requester_member_id) REFERENCES member (id),
    CONSTRAINT ck_inquiry_status CHECK (inquiry_status IN ('IN_PROGRESS', 'WAITING_USER', 'RESOLVED', 'CLOSED'))
);

-- PostgreSQL은 FK 컬럼에 인덱스를 자동으로 만들지 않는다. "내 문의 목록"을 문의자로 찾으려고 둔다.
CREATE INDEX idx_inquiry_requester_member_id ON inquiry (requester_member_id);
