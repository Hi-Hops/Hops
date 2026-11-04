-- 실행 내 new: 참조를 서버 문제 UUID와 연결하는 전달 journal. WorkItem 정본은 여전히 inquiry_problem이다.
CREATE TABLE agent_ticket_request (
    run_id UUID NOT NULL REFERENCES agent_run(id),
    problem_ref VARCHAR(100) NOT NULL,
    problem_id UUID NOT NULL REFERENCES inquiry_problem(id),
    request_body JSONB NOT NULL,
    attempt INT NOT NULL DEFAULT 1 CHECK (attempt BETWEEN 1 AND 3),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, problem_ref),
    UNIQUE (run_id, problem_id)
);
CREATE INDEX idx_agent_ticket_request_problem ON agent_ticket_request(problem_id);
CREATE SEQUENCE ticket_number_seq;
SELECT setval('ticket_number_seq', COALESCE(MAX(substring(ticket_number FROM 3)::bigint), 0) + 1, false)
FROM ticket WHERE ticket_number ~ '^T-[0-9]{1,16}$';
