-- 운영자 결함 감수(결함 감수·조치 승인 노출 설계 제안 §2.1). 덧붙이기만 한다. 시각은 DB 의 clock_timestamp() 다.
-- picasso Middleware.release 는 승인자와 사유를 받지 않는다. 실행 호스트가 사람 승인자를 판정하고, 운영자가 본 결함과 지금 막는 결함을
-- 호스트 잠금 아래에서 대조한 뒤 이 행을 먼저 적고 release 를 부른다. 못 적으면 release 를 부르지 않는다.
-- faults 는 감수한 막는 결함 전부다(호스트 FaultDetailView 모양의 JSON 배열, 비어 있지 않다). 실행 목록의 releases 칸이 작업 지시 id 로
-- 이 표를 읽으므로 재기동을 넘어 보인다.
-- request_id 는 운영 서비스가 실은 요청 id 다. 같은 id 는 두 번 적지 못한다(응답 없음 뒤 재조회가 이것으로 대조한다). 없으면 NULL 이다.
CREATE TABLE execution_fault_release (
    release_id    BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instance_id   TEXT        NOT NULL CHECK (instance_id <> ''),
    execution_id  TEXT        NOT NULL CHECK (execution_id <> ''),
    job_order_id  TEXT        NOT NULL REFERENCES execution_journal (job_order_id),
    faults        JSONB       NOT NULL CHECK (CASE WHEN jsonb_typeof(faults) = 'array' THEN jsonb_array_length(faults) > 0 ELSE false END),
    approver_id   TEXT        NOT NULL CHECK (approver_id <> ''),
    approver_kind TEXT        NOT NULL CHECK (approver_kind = 'PERSON'),
    reason        TEXT        NOT NULL CHECK (btrim(reason) <> ''),
    request_id    UUID        UNIQUE,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX execution_fault_release_order ON execution_fault_release (job_order_id, release_id);

-- 덧붙이기만 한다. V2 의 mission_record_append_only() 를 그대로 쓴다.
CREATE TRIGGER execution_fault_release_no_update_delete
    BEFORE UPDATE OR DELETE ON execution_fault_release
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER execution_fault_release_no_truncate
    BEFORE TRUNCATE ON execution_fault_release
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
