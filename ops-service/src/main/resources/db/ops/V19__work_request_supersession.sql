-- Upstream supersession: the upstream system sent a new work request in place of one of its own that ended without executing
-- (REJECTED or UNASSIGNED, nothing observed running). The upstream chose the new content, e.g. a substitute source from the
-- shortage alternatives; ops never edits the original. Append-only, one per side. The original's state, detail and submission are unchanged.
CREATE TABLE ops.work_request_supersession (
 original_receipt_id UUID PRIMARY KEY REFERENCES ops.work_request(receipt_id),
 replacement_receipt_id UUID NOT NULL UNIQUE REFERENCES ops.work_request(receipt_id),
 source_system_id TEXT NOT NULL CHECK(source_system_id<>''),
 original_state TEXT NOT NULL CHECK(original_state IN ('REJECTED','UNASSIGNED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK(original_receipt_id<>replacement_receipt_id)
);
CREATE TRIGGER work_request_supersession_immutable BEFORE UPDATE OR DELETE ON ops.work_request_supersession
 FOR EACH ROW EXECUTE FUNCTION ops.protect_interface_deployment();
