-- Source confirmation round (V18): a confirmation rides only the chain in which the host keeps answering source-unobserved.
-- A dispatch that ends any other way (pre-dispatch or host UNASSIGNED without source-unobserved, ACCEPTED, REJECTED, IN_DOUBT)
-- stamps this in the same statement as its state; later dispatches carry only confirmations recorded after it. Null: never closed.
ALTER TABLE ops.work_request ADD COLUMN source_confirmations_closed_at TIMESTAMPTZ;
