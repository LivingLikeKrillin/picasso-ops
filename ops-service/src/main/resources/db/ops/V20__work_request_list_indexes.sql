-- Load baseline 2026-10-14 hotspot 5. Unacknowledged latest results read only unacknowledged versions; a fixed state filter and the
-- per-state count read the state index instead of every work request.
CREATE INDEX work_result_unacknowledged ON ops.work_result(receipt_id,version) WHERE acknowledged_at IS NULL;
CREATE INDEX work_request_state_received ON ops.work_request(state,received_at DESC,receipt_id);
