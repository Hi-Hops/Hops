-- 기존 실행기가 모두 중지된 상태에서 한 번 적용한다.
-- 이전 버전의 실행은 소유자 생존을 확인할 수 없으므로 UNKNOWN으로 보존한다.
ALTER TABLE ai_run_ledger ADD COLUMN owner_id UUID;
UPDATE ai_run_ledger SET status = 'UNKNOWN', updated_at = CURRENT_TIMESTAMP
WHERE status IN ('QUEUED', 'RUNNING') AND owner_id IS NULL;
ALTER TABLE ai_run_ledger ADD CONSTRAINT ai_run_ledger_active_owner_required
CHECK (status NOT IN ('QUEUED', 'RUNNING') OR owner_id IS NOT NULL);
CREATE INDEX ai_run_ledger_active_owner_idx ON ai_run_ledger (owner_id)
WHERE status IN ('QUEUED', 'RUNNING');
