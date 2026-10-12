CREATE TABLE ops.mission_profile (
 work_master_id TEXT PRIMARY KEY,
 profile JSONB NOT NULL,
 created_by TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER immutable_mission_profile BEFORE UPDATE OR DELETE ON ops.mission_profile
 FOR EACH ROW EXECUTE FUNCTION ops.protect_interface_deployment();

ALTER TABLE ops.mission_interface DROP CONSTRAINT mission_interface_work_master_id_check;
ALTER TABLE ops.mission_interface ADD CHECK (work_master_id ~ '^[A-Z][A-Za-z0-9]{0,63}$');
CREATE FUNCTION ops.registered_mission_interface() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN
  IF NEW.work_master_id NOT IN ('InspectAsset','PrepareSequencedRack') AND NOT EXISTS
   (SELECT 1 FROM ops.mission_profile WHERE work_master_id=NEW.work_master_id) THEN
   RAISE EXCEPTION 'mission input profile must be registered first';
  END IF;
  RETURN NEW;
 END $$;
CREATE TRIGGER registered_mission_interface BEFORE INSERT ON ops.mission_interface FOR EACH ROW EXECUTE FUNCTION ops.registered_mission_interface();
