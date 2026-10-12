CREATE TABLE mission.result_producer (
 singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
 producer_id UUID NOT NULL,
 last_sequence BIGINT NOT NULL DEFAULT 0 CHECK(last_sequence>=0)
);
INSERT INTO mission.result_producer(singleton,producer_id) VALUES(TRUE,gen_random_uuid());
CREATE TABLE mission.result_outbox (
 sequence BIGINT PRIMARY KEY CHECK(sequence>0),
 instance_id TEXT NOT NULL,
 response_id TEXT NOT NULL,
 snapshot JSONB NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(instance_id,response_id)
);
CREATE TRIGGER result_outbox_append_only BEFORE UPDATE OR DELETE ON mission.result_outbox
 FOR EACH ROW EXECUTE FUNCTION mission.mission_record_append_only();
CREATE TRIGGER result_outbox_no_truncate BEFORE TRUNCATE ON mission.result_outbox
 FOR EACH STATEMENT EXECUTE FUNCTION mission.mission_record_append_only();
