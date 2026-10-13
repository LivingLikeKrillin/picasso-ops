-- 운영자 실행 취소와 잔여 파지 표시(P0-b2). 표 셋 모두 덧붙이기만 한다. 시각은 DB 의 clock_timestamp() 다.
-- 셋 다 운영 정책의 기록이다(ADR 41 «정책은 밖, 채택은 안»). picasso 는 정착한 실행의 파지를 판단이 아니라 정보로 둔다(§15.146).

-- 운영자 실행 취소. 실행 호스트가 미들웨어 cancel 을 부르기 직전에 적는다. 그 실행에 한 번뿐이다.
-- physical_state 는 취소를 건 때의 물리 상태다(부분 완료 PARTIAL 이 주된 대상). 승인자는 사람뿐이다.
CREATE TABLE execution_cancel (
    cancel_id      BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instance_id    TEXT        NOT NULL CHECK (instance_id <> ''),
    execution_id   TEXT        NOT NULL CHECK (execution_id <> ''),
    job_order_id   TEXT        NOT NULL REFERENCES execution_journal (job_order_id),
    physical_state TEXT        NOT NULL CHECK (physical_state <> ''),
    approver_id    TEXT        NOT NULL CHECK (approver_id <> ''),
    approver_kind  TEXT        NOT NULL CHECK (approver_kind = 'PERSON'),
    reason         TEXT        NOT NULL CHECK (btrim(reason) <> ''),
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (instance_id, execution_id)
);

-- 미해소 잔여 파지 표시. 실행이 정착할 때 경로가 로봇인 단위의 마지막 파지 관측이 HOLDING 이면 한 행이다(플릿 단위는 보지 않는다).
-- 작업 지시마다 많아야 하나다. 해소 행이 없는 동안 실행 호스트 판정이 그 기체를 배정에서 뺀다.
-- object_ref 는 관측이 이름을 싣지 않았으면 NULL 이다.
CREATE TABLE robot_residual_hold (
    marker_id    BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    robot_id     TEXT        NOT NULL CHECK (robot_id <> ''),
    job_order_id TEXT        NOT NULL UNIQUE REFERENCES execution_journal (job_order_id),
    execution_id TEXT        NOT NULL CHECK (execution_id <> ''),
    instance_id  TEXT        NOT NULL CHECK (instance_id <> ''),
    unit_id      TEXT        NOT NULL CHECK (unit_id <> ''),
    object_ref   TEXT        CHECK (object_ref <> ''),
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX robot_residual_hold_robot ON robot_residual_hold (robot_id, marker_id);

-- 표시의 해소. 사람이 기체가 빈손임을 현장에서 확인했다는 기록이다. 표시 하나에 많아야 하나다.
CREATE TABLE robot_residual_hold_clearing (
    marker_id     BIGINT      PRIMARY KEY REFERENCES robot_residual_hold (marker_id),
    approver_id   TEXT        NOT NULL CHECK (approver_id <> ''),
    approver_kind TEXT        NOT NULL CHECK (approver_kind = 'PERSON'),
    reason        TEXT        NOT NULL CHECK (btrim(reason) <> ''),
    cleared_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 덧붙이기만 한다. V2 의 mission_record_append_only() 를 그대로 쓴다.
CREATE TRIGGER execution_cancel_no_update_delete
    BEFORE UPDATE OR DELETE ON execution_cancel
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER execution_cancel_no_truncate
    BEFORE TRUNCATE ON execution_cancel
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER robot_residual_hold_no_update_delete
    BEFORE UPDATE OR DELETE ON robot_residual_hold
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER robot_residual_hold_no_truncate
    BEFORE TRUNCATE ON robot_residual_hold
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER robot_residual_hold_clearing_no_update_delete
    BEFORE UPDATE OR DELETE ON robot_residual_hold_clearing
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER robot_residual_hold_clearing_no_truncate
    BEFORE TRUNCATE ON robot_residual_hold_clearing
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
