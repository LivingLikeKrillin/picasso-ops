CREATE TABLE ops.result_cursor (
 singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
 producer_id UUID,
 sequence BIGINT NOT NULL DEFAULT 0 CHECK(sequence>=0),
 high_watermark BIGINT NOT NULL DEFAULT 0,
 updated_at TIMESTAMPTZ
);
INSERT INTO ops.result_cursor(singleton) VALUES(TRUE);
