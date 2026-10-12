CREATE TABLE ops.work_request (
 receipt_id UUID PRIMARY KEY,
 source_system_id TEXT NOT NULL,
 external_order_id TEXT NOT NULL,
 payload_hash TEXT NOT NULL,
 payload JSONB NOT NULL,
 job_order_id TEXT NOT NULL UNIQUE,
 state TEXT NOT NULL CHECK(state IN ('RECEIVED','DISPATCHING','ACCEPTED','REJECTED','UNASSIGNED','IN_DOUBT')),
 detail TEXT,
 normalized_order JSONB,
 submission JSONB,
 last_execution JSONB,
 execution_observed_at TIMESTAMPTZ,
 execution_instance_id TEXT,
 received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(source_system_id,external_order_id)
);
CREATE INDEX work_request_received ON ops.work_request(received_at DESC,receipt_id);
