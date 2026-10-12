CREATE TABLE ops.mission_resource_requirement (
 work_master_id VARCHAR(120) PRIMARY KEY,
 station_id VARCHAR(64) NOT NULL REFERENCES ops.rf_station(station_id),
 revision INTEGER NOT NULL DEFAULT 1 CHECK(revision=1),
 created_by VARCHAR(80) NOT NULL,
 reason TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER mission_resource_immutable BEFORE UPDATE OR DELETE ON ops.mission_resource_requirement
 FOR EACH ROW EXECUTE FUNCTION ops.operation_log_append_only();
CREATE TRIGGER mission_resource_no_truncate BEFORE TRUNCATE ON ops.mission_resource_requirement
 FOR EACH STATEMENT EXECUTE FUNCTION ops.operation_log_append_only();
ALTER TABLE ops.work_request ADD COLUMN required_station_id VARCHAR(64) REFERENCES ops.rf_station(station_id);
ALTER TABLE ops.work_request DROP CONSTRAINT work_request_state_check;
ALTER TABLE ops.work_request ADD CONSTRAINT work_request_state_check CHECK(state IN ('RECEIVED','WAITING_RESOURCE','DISPATCHING','ACCEPTED','REJECTED','UNASSIGNED','IN_DOUBT'));
