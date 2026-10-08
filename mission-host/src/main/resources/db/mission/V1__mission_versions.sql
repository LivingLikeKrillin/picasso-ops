-- 임무 버전 저장(S3b 스펙 §6.1, T3). 표 셋 모두 덧붙이기만 한다. 시각은 DB 의 clock_timestamp() 다(운영 서비스 조작 기록과 같음).
-- 요청 id 는 운영 서비스가 넘긴 조작의 요청 id 다. 응답을 못 받은 운영 서비스가 그것으로 다시 찾는다(T9).

-- 초안. 자유롭다: 읽을 수 없는 문서도 저장한다. 정의는 받은 글자 그대로다.
CREATE TABLE draft (
    draft_id       BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    work_master_id TEXT        NOT NULL CHECK (work_master_id <> ''),
    definition     TEXT        NOT NULL,
    saved_by       TEXT        NOT NULL CHECK (saved_by <> ''),
    request_id     UUID        NOT NULL UNIQUE,
    saved_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 모의 실행. 검증을 지난 초안만 돈다. 결과는 통과 여부, 실패 하위 범주, 물리 상태, 단위별 상태와 근거 등급, 가상 경과 시간이다.
CREATE TABLE mock_run (
    mock_run_id BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    draft_id    BIGINT      NOT NULL REFERENCES draft (draft_id),
    passed      BOOLEAN     NOT NULL,
    result      JSONB       NOT NULL,
    request_id  UUID        NOT NULL UNIQUE,
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (finished_at >= started_at)
);

CREATE INDEX mock_run_draft ON mock_run (draft_id, mock_run_id);

-- 임무 버전. WorkMaster 마다 1부터 오른다. 활성 버전은 그 WorkMaster 의 가장 큰 번호다.
-- 정의는 활성화 때 검증을 지난 초안의 글자를 그대로 옮긴다. 기동 때 이 칸을 파싱해 카탈로그를 세운다(T1).
CREATE TABLE mission_version (
    work_master_id TEXT        NOT NULL CHECK (work_master_id <> ''),
    version        INTEGER     NOT NULL CHECK (version > 0),
    draft_id       BIGINT      NOT NULL REFERENCES draft (draft_id),
    definition     TEXT        NOT NULL,
    activated_by   TEXT        NOT NULL CHECK (activated_by <> ''),
    reason         TEXT        NOT NULL CHECK (reason <> ''),
    request_id     UUID        NOT NULL UNIQUE,
    activated_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (work_master_id, version)
);

-- 덧붙이기만 한다. 운영 서비스 조작 기록·현장 설정과 같이 스키마가 막는다.
CREATE FUNCTION mission_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '임무 버전 저장은 덧붙이기만 한다(% %)', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER draft_no_update_delete
    BEFORE UPDATE OR DELETE ON draft
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

-- 행 트리거는 TRUNCATE 에서 돌지 않는다. 통째로 비우는 것도 지우는 것이다.
CREATE TRIGGER draft_no_truncate
    BEFORE TRUNCATE ON draft
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER mock_run_no_update_delete
    BEFORE UPDATE OR DELETE ON mock_run
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER mock_run_no_truncate
    BEFORE TRUNCATE ON mock_run
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER mission_version_no_update_delete
    BEFORE UPDATE OR DELETE ON mission_version
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER mission_version_no_truncate
    BEFORE TRUNCATE ON mission_version
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();
