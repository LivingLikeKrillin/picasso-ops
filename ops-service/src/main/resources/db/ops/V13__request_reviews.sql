CREATE TABLE ops.work_request_review (
 request_id UUID PRIMARY KEY,
 receipt_id UUID NOT NULL REFERENCES ops.work_request(receipt_id),
 actor TEXT NOT NULL,
 observation TEXT NOT NULL CHECK(observation IN ('NOT_CONFIRMED','NOT_STARTED','IN_PROGRESS','COMPLETED')),
 reason TEXT NOT NULL,
 expected_updated_at TEXT NOT NULL,
 context JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER work_request_review_immutable BEFORE UPDATE OR DELETE ON ops.work_request_review
 FOR EACH ROW EXECUTE FUNCTION ops.protect_interface_deployment();
