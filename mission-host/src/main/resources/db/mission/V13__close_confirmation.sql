-- 증언 종결의 스냅숏 확인(결함 14 후속). 덧붙이기만 한다. 시각은 DB 의 clock_timestamp() 다.
-- 정지 증언(ATTESTED)으로 종결한 작업 지시는 기체 스냅숏이 읽히고 그 작업 지시의 살아 있는 태스크가 없다고 동의할 때까지 그 기체를 판정에서 뺀다.
-- 이 동의를 메모리에만 두면 재기동마다 지난 증언 종결이 전부 다시 올라와 기체를 다시 빼고 실행 목록에 다시 뜬다. 동의한 순간을 한 행으로 적는다.

-- 종결 행마다 많아야 하나다(close_id PRIMARY KEY). instance_id 는 동의를 본 인스턴스다. 같은 확인을 두 인스턴스가 보면 먼저 적은 쪽이 남는다.
CREATE TABLE execution_close_confirmed (
    close_id     BIGINT      PRIMARY KEY REFERENCES execution_close (close_id),
    instance_id  TEXT        NOT NULL CHECK (instance_id <> ''),
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 덧붙이기만 한다. V2 의 mission_record_append_only() 를 그대로 쓴다.
CREATE TRIGGER execution_close_confirmed_no_update_delete
    BEFORE UPDATE OR DELETE ON execution_close_confirmed
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER execution_close_confirmed_no_truncate
    BEFORE TRUNCATE ON execution_close_confirmed
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
