-- 현장 설정 버전(S2 스펙 §5). 한 행이 설정 한 세트의 스냅샷이고, 값을 하나만 바꿔도 새 버전이 된다.
-- 현재 버전은 가장 큰 version 이다. 같은 기준 버전 위의 동시 변경은 기본 키가 하나만 받는다.
CREATE TABLE site_settings (
    version                      BIGINT      PRIMARY KEY CHECK (version > 0),
    connection_threshold_seconds INTEGER     NOT NULL CHECK (connection_threshold_seconds > 0),
    mode                         TEXT        NOT NULL CHECK (mode = 'ENGINEER'),
    actor_user                   TEXT        NOT NULL CHECK (actor_user <> ''),
    reason                       TEXT        NOT NULL CHECK (reason <> ''),
    recorded_at                  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 덧붙이기만 한다. 조작 기록과 같이 스키마가 막는다.
CREATE FUNCTION site_settings_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '현장 설정은 덧붙이기만 한다(%)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER site_settings_no_update_delete
    BEFORE UPDATE OR DELETE ON site_settings
    FOR EACH ROW EXECUTE FUNCTION site_settings_append_only();

CREATE TRIGGER site_settings_no_truncate
    BEFORE TRUNCATE ON site_settings
    FOR EACH STATEMENT EXECUTE FUNCTION site_settings_append_only();

-- S1 에서 설정 파일에 있던 값(ops.connection.threshold=90s)을 버전 1 로 옮긴다.
INSERT INTO site_settings (version, connection_threshold_seconds, mode, actor_user, reason)
VALUES (1, 90, 'ENGINEER', 'system', 'S1 설정값 이전');
