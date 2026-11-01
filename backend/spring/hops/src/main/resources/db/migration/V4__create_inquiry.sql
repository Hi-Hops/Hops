-- 문의: 대화 세션 1개 = 문의 1건 (docs/ERD.md "문의 inquiry")
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE TABLE inquiry (
    id                  UUID          PRIMARY KEY DEFAULT uuidv7(),
    -- 사람이 부르기 쉬운 문의 번호 (예: Q-0142)
    inquiry_number      VARCHAR(20)   NOT NULL,
    requester_member_id UUID          NOT NULL,
    title               VARCHAR(200)  NOT NULL,
    -- IN_PROGRESS / WAITING_USER / RESOLVED (CHECK 제약은 상태값 확정 후 추가)
    inquiry_status      VARCHAR(30)   NOT NULL,
    -- 메시지 추가·정정 때마다 1씩 늘린다. 예전 버전으로 분석한 늦은 AI 결과를 버리는 기준
    version             INT           NOT NULL DEFAULT 1,
    summary             TEXT,
    -- 요약이 대화의 몇 번째 메시지(message.sequence_no)까지 반영됐는지
    summary_until_seq   INT,
    summary_updated_at  TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uk_inquiry_inquiry_number UNIQUE (inquiry_number),
    CONSTRAINT fk_inquiry_requester FOREIGN KEY (requester_member_id) REFERENCES member (id)
);
