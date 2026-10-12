CREATE TABLE ops.work_result (
 receipt_id UUID NOT NULL REFERENCES ops.work_request(receipt_id),
 version INTEGER NOT NULL CHECK(version > 0),
 instance_id TEXT NOT NULL,
 execution_id TEXT NOT NULL,
 response_id TEXT NOT NULL,
 response_version INTEGER NOT NULL CHECK(response_version > 0),
 snapshot JSONB NOT NULL,
 result_hash TEXT NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 acknowledged_at TIMESTAMPTZ,
 PRIMARY KEY(receipt_id,version),
 UNIQUE(receipt_id,instance_id,execution_id,response_id)
);
