-- 판단 기록(picasso 한계 §15.221 · 판단 기록 영속, HA 설계 기록 §8 단계 0). 덧붙이기만 한다. 시각은 DB 의 clock_timestamp() 다.
-- 실행 호스트가 picasso Middleware.judgmentRecord(executionId) 를 받아 JudgmentRecordWire 로 적은 글자다. 수락, pump 뒤(판정·검증
-- 결과·정착), 운영자 판단 뒤에 받아 직전에 적은 글자와 다를 때만 한 행을 더한다. 기동 복원은 그 작업 지시의 가장 최근 행을
-- Middleware.resume 에 넘긴다.
-- record 는 JSON 이 아니라 TEXT 다. 받은 글자를 그대로 두고, 못 읽는 글자도 적힌 대로 남아 복원이 그것을 보고한다.
-- wire_version 은 적을 때의 JudgmentRecordWire.SCHEMA_VERSION 이다. 글자 안의 schemaVersion 과 같다.
CREATE TABLE execution_judgment_record (
    record_id    BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_order_id TEXT        NOT NULL REFERENCES execution_journal (job_order_id),
    instance_id  TEXT        NOT NULL CHECK (instance_id <> ''),
    execution_id TEXT        NOT NULL CHECK (execution_id <> ''),
    wire_version TEXT        NOT NULL CHECK (wire_version <> ''),
    record       TEXT        NOT NULL CHECK (record <> ''),
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX execution_judgment_record_order ON execution_judgment_record (job_order_id, record_id);

CREATE TRIGGER execution_judgment_record_no_update_delete
    BEFORE UPDATE OR DELETE ON execution_judgment_record
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER execution_judgment_record_no_truncate
    BEFORE TRUNCATE ON execution_judgment_record
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
