-- 사례 색인 상태에 RUNNING(원격 진행 중)과 UNKNOWN(원격 상태 미확인)을 추가한다.
-- 계약 IndexState(PENDING, RUNNING, SUCCEEDED, FAILED, UNKNOWN)와 맞춘다. 컬럼·기본값은 그대로이고 허용 값만 넓힌다.
ALTER TABLE resolved_case DROP CONSTRAINT ck_resolved_case_vector_status;
ALTER TABLE resolved_case DROP CONSTRAINT ck_resolved_case_graph_status;
ALTER TABLE resolved_case ADD CONSTRAINT ck_resolved_case_vector_status
    CHECK (vector_status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'));
ALTER TABLE resolved_case ADD CONSTRAINT ck_resolved_case_graph_status
    CHECK (graph_status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'));
