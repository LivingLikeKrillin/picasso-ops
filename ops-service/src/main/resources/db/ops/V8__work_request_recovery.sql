CREATE TABLE ops.work_request_recovery (
 attempt_id UUID PRIMARY KEY,
 receipt_id UUID NOT NULL REFERENCES ops.work_request(receipt_id),
 actor TEXT NOT NULL,
 reason TEXT NOT NULL,
 expected_updated_at TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX work_request_recovery_receipt ON ops.work_request_recovery(receipt_id,created_at);
