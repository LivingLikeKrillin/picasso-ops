CREATE TABLE ops.rf_station (
 station_id VARCHAR(64) PRIMARY KEY,
 name VARCHAR(120) NOT NULL,
 created_by VARCHAR(80) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 ready_signal VARCHAR(120) NOT NULL UNIQUE,
 complete_signal VARCHAR(120) NOT NULL UNIQUE,
 timeout_seconds INTEGER NOT NULL CHECK(timeout_seconds BETWEEN 5 AND 3600),
 owner UUID,
 CHECK(ready_signal <> complete_signal)
);
CREATE TABLE ops.station_run (
 run_id UUID PRIMARY KEY,
 station_id VARCHAR(64) NOT NULL REFERENCES ops.rf_station(station_id),
 payload JSONB NOT NULL,
 actor VARCHAR(80) NOT NULL,
 state VARCHAR(32) NOT NULL,
 reason TEXT NOT NULL,
 receipt_id UUID REFERENCES ops.work_request(receipt_id),
 attempt INTEGER NOT NULL DEFAULT 0,
 revision INTEGER NOT NULL DEFAULT 1,
 created_at TIMESTAMPTZ NOT NULL,
 phase_at TIMESTAMPTZ NOT NULL,
 dispatched_at TIMESTAMPTZ
);
CREATE INDEX station_run_queue ON ops.station_run(station_id,created_at,run_id);
CREATE TABLE ops.station_event (
 event_id BIGSERIAL PRIMARY KEY,
 run_id UUID NOT NULL REFERENCES ops.station_run(run_id),
 state VARCHAR(32) NOT NULL,
 reason TEXT NOT NULL,
 actor VARCHAR(80) NOT NULL,
 context JSONB NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX station_one_owner ON ops.rf_station(owner) WHERE owner IS NOT NULL;
CREATE TRIGGER station_event_immutable BEFORE UPDATE OR DELETE ON ops.station_event
 FOR EACH ROW EXECUTE FUNCTION ops.operation_log_append_only();
CREATE TRIGGER station_event_no_truncate BEFORE TRUNCATE ON ops.station_event
 FOR EACH STATEMENT EXECUTE FUNCTION ops.operation_log_append_only();
ALTER TABLE ops.rf_station ADD CONSTRAINT station_owner_run FOREIGN KEY(owner) REFERENCES ops.station_run(run_id);
