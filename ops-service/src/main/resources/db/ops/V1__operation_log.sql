-- 조작 기록(스펙 §7.1). 칸은 9개다. 재조회 행도 같은 9칸을 쓴다.
CREATE TABLE operation_log (
    request_id        UUID        NOT NULL,
    mode              TEXT        NOT NULL CHECK (mode IN ('ENGINEER', 'OPERATOR')),
    actor_user        TEXT        NOT NULL CHECK (actor_user <> ''),
    target            TEXT        NOT NULL,
    request           JSONB       NOT NULL,
    reason            TEXT,
    result            TEXT        NOT NULL CHECK (result IN (
                          'SUCCEEDED', 'REJECTED', 'NO_RESPONSE',
                          'CONFIRMED_APPLIED', 'CONFIRMED_NOT_APPLIED')),
    registry_response JSONB,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX operation_log_request_id ON operation_log (request_id);

-- 덧붙이기만 한다. 고치지 않고 삭제하지 않는다(스펙 §7.1). 코드의 약속이 아니라 스키마가 막는다.
CREATE FUNCTION operation_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '조작 기록은 덧붙이기만 한다(%)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER operation_log_no_update_delete
    BEFORE UPDATE OR DELETE ON operation_log
    FOR EACH ROW EXECUTE FUNCTION operation_log_append_only();

-- 행 트리거는 TRUNCATE 에서 돌지 않는다. 통째로 비우는 것도 지우는 것이다.
CREATE TRIGGER operation_log_no_truncate
    BEFORE TRUNCATE ON operation_log
    FOR EACH STATEMENT EXECUTE FUNCTION operation_log_append_only();
