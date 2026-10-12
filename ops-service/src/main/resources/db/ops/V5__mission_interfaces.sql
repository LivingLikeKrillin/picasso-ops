CREATE TABLE mission_interface (
    interface_id TEXT NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    name TEXT NOT NULL,
    work_master_id TEXT NOT NULL CHECK (work_master_id IN ('InspectAsset', 'PrepareSequencedRack')),
    mapping JSONB NOT NULL,
    sample JSONB NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('DRAFT', 'ENABLED', 'DISABLED')),
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (interface_id, version)
);

CREATE FUNCTION mission_interface_definition_immutable() RETURNS trigger AS $$
BEGIN
    IF ROW(NEW.interface_id, NEW.version, NEW.name, NEW.work_master_id, NEW.mapping, NEW.sample, NEW.created_by, NEW.created_at)
       IS DISTINCT FROM ROW(OLD.interface_id, OLD.version, OLD.name, OLD.work_master_id, OLD.mapping, OLD.sample, OLD.created_by, OLD.created_at) THEN
        RAISE EXCEPTION '호출 계약 정의는 변경할 수 없다. 새 버전을 생성한다';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER mission_interface_immutable BEFORE UPDATE ON mission_interface
    FOR EACH ROW EXECUTE FUNCTION mission_interface_definition_immutable();
