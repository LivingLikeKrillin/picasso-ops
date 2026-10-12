CREATE TABLE ops.interface_deployment (
 interface_id TEXT NOT NULL,
 interface_version INTEGER NOT NULL,
 deployment_version INTEGER NOT NULL,
 mission_version TEXT NOT NULL CHECK(mission_version='CODE' OR mission_version ~ '^[1-9][0-9]*$'),
 created_by TEXT NOT NULL,
 reason TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(interface_id,interface_version,deployment_version),
 FOREIGN KEY(interface_id,interface_version) REFERENCES ops.mission_interface(interface_id,version)
);
INSERT INTO ops.interface_deployment(interface_id,interface_version,deployment_version,mission_version,created_by,reason)
 SELECT interface_id,version,1,'CODE','migration','초기 코드 정의 고정; 데이터 정의는 명시적 배포 필요' FROM ops.mission_interface;
CREATE FUNCTION ops.protect_interface_deployment() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN RAISE EXCEPTION 'interface deployment is immutable'; END $$;
CREATE TRIGGER protect_interface_deployment BEFORE UPDATE OR DELETE ON ops.interface_deployment FOR EACH ROW EXECUTE FUNCTION ops.protect_interface_deployment();
