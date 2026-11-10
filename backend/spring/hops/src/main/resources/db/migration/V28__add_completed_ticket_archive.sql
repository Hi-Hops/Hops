-- 완료 티켓의 목록 보관은 삭제와 구분한다. 승인 사례의 원 티켓·검증 이력을 보존한다.
ALTER TABLE ticket ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE ticket ADD CONSTRAINT ck_ticket_archive_completed
    CHECK (archived_at IS NULL OR ticket_status = 'RESOLVED');
