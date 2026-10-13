-- Optional job-number pair (TEXT signals): ops writes the run token before dispatch, the equipment returns it with its completion. Both or neither.
ALTER TABLE ops.rf_station ADD COLUMN job_signal VARCHAR(120) UNIQUE;
ALTER TABLE ops.rf_station ADD COLUMN job_echo_signal VARCHAR(120) UNIQUE;
ALTER TABLE ops.rf_station ADD CONSTRAINT station_job_pair CHECK((job_signal IS NULL)=(job_echo_signal IS NULL) AND (job_signal IS NULL OR job_signal<>job_echo_signal));
-- Without the pair a released run's late completion cannot be told apart: a dispatched run released on site confirmation quarantines the station
-- until an operator confirms it idle. No dispatch while set.
ALTER TABLE ops.rf_station ADD COLUMN quarantined_by UUID REFERENCES ops.station_run(run_id);
ALTER TABLE ops.rf_station ADD COLUMN quarantined_at TIMESTAMPTZ;
ALTER TABLE ops.rf_station ADD CONSTRAINT station_quarantine_pair CHECK((quarantined_by IS NULL)=(quarantined_at IS NULL));
