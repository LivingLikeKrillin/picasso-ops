CREATE TABLE ops.work_result_delivery (
 receipt_id UUID NOT NULL,
 version INTEGER NOT NULL,
 target_url TEXT NOT NULL,
 state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','IN_FLIGHT','RETRY','DELIVERED')),
 attempts INTEGER NOT NULL DEFAULT 0,
 claim_token UUID,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 last_error TEXT,
 delivered_at TIMESTAMPTZ,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(receipt_id,version),
 FOREIGN KEY(receipt_id,version) REFERENCES ops.work_result(receipt_id,version)
);
CREATE INDEX work_result_delivery_due ON ops.work_result_delivery(next_attempt_at) WHERE state<>'DELIVERED';
