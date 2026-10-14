-- Ops service instances and their heartbeat (HA design record §7, §8 stage 0). One row per process, written only by that process.
-- Not append-only: a row per beat would grow without bound. Every time is the database clock (design record §4.3), never the ops clock.
-- Registered at start, last_seen_at/expires_at (= then + expiry) refreshed each period, stopped_at written on a clean shutdown.
-- Live = row present, stopped_at NULL and expires_at after now. No row (any instance before this table, or an unregistered one) is not live.
-- Not a lease: no generation, no fencing. A process that wakes after its expiry is not stopped by this table (the stage 1 lease row is).
CREATE TABLE ops.service_instance (
 instance_id VARCHAR(80) PRIMARY KEY CHECK(instance_id<>''),
 started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 last_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 expires_at TIMESTAMPTZ NOT NULL,
 stopped_at TIMESTAMPTZ
);
-- The instance that last wrote the run's state (enqueue, transition, hold). A RETRYING/REWORKING run is left by a stopped process only when
-- this instance is another one and its heartbeat expired. NULL for runs written before this column: no live instance holds them.
ALTER TABLE ops.station_run ADD COLUMN instance_id VARCHAR(80) CHECK(instance_id<>'');
