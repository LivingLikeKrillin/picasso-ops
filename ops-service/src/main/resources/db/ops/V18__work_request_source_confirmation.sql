-- Source confirmation: a person checked a source slot the host could not observe for this work request (picasso SourceUnobserved).
-- Append-only. Every later dispatch of the same work request carries its rows (latest per source) to the host as cell observations.
-- context is the host's unobserved answer the person confirmed against.
CREATE TABLE ops.work_request_source_confirmation (
 request_id UUID PRIMARY KEY,
 receipt_id UUID NOT NULL REFERENCES ops.work_request(receipt_id),
 source TEXT NOT NULL CHECK(source<>''),
 identity TEXT NOT NULL CHECK(btrim(identity)<>''),
 actor TEXT NOT NULL CHECK(actor<>''),
 confirmer_kind TEXT NOT NULL CHECK(confirmer_kind='PERSON'),
 reason TEXT NOT NULL CHECK(btrim(reason)<>''),
 expected_updated_at TEXT NOT NULL,
 context JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX work_request_source_confirmation_receipt ON ops.work_request_source_confirmation(receipt_id,created_at);
CREATE TRIGGER work_request_source_confirmation_immutable BEFORE UPDATE OR DELETE ON ops.work_request_source_confirmation
 FOR EACH ROW EXECUTE FUNCTION ops.protect_interface_deployment();
