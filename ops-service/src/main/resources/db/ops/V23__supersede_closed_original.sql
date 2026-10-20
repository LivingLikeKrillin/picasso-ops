-- Upstream supersession of a request an operator closed (defect 14). An ACCEPTED original whose latest result version carries a host closure
-- (the operator closed an execution the host could not restore) can be replaced like one that ended without executing. The link then records
-- the result version that closed it; links of REJECTED and UNASSIGNED originals still have none. The table's trigger blocks UPDATE and
-- DELETE only, so adding a nullable column is safe. IS NOT NULL is spelled out: a CHECK that evaluates to NULL passes.
ALTER TABLE ops.work_request_supersession DROP CONSTRAINT work_request_supersession_original_state_check;
ALTER TABLE ops.work_request_supersession ADD COLUMN original_result_version INTEGER;
ALTER TABLE ops.work_request_supersession ADD CONSTRAINT work_request_supersession_original_state_check CHECK (
 (original_state IN ('REJECTED','UNASSIGNED') AND original_result_version IS NULL)
 OR (original_state = 'ACCEPTED' AND original_result_version IS NOT NULL AND original_result_version > 0));
