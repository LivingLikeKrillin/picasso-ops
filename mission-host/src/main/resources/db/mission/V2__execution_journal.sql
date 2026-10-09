-- 실행 일지, 송신 기록, 인시던트 사본(S4b 스펙 §6.1, T2·T6·T7). 표 다섯 모두 덧붙이기만 한다. 시각은 DB 의 clock_timestamp() 다.
-- 인스턴스는 미들웨어가 뜬 한 번(`instanceId`)이다. `exec-N`·`resp-N`·`incident-N` 은 인스턴스 안의 셈이라 늘 인스턴스와 짝짓는다.

-- 실행 일지. 새 실행으로 ACCEPTED 인 제출마다 한 행이다. 키는 작업 지시 id 다(운영 서비스는 작업 지시 id 를 다시 쓰지 않는다).
-- 작업 지시는 picasso JobOrder 의 칸 그대로의 JSON 이다. 임무 버전이 NULL 이면 코드 정의다.
-- journal_id 는 받은 순서다. 기동 복원은 이 순서로 다시 짓는다(T3).
CREATE TABLE execution_journal (
    journal_id     BIGINT      GENERATED ALWAYS AS IDENTITY UNIQUE,
    job_order_id   TEXT        PRIMARY KEY CHECK (job_order_id <> ''),
    robot_id       TEXT        NOT NULL CHECK (robot_id <> ''),
    job_order      JSONB       NOT NULL,
    work_master_id TEXT        NOT NULL CHECK (work_master_id <> ''),
    mission_version INTEGER   CHECK (mission_version > 0),
    instance_id    TEXT        NOT NULL CHECK (instance_id <> ''),
    execution_id   TEXT        NOT NULL CHECK (execution_id <> ''),
    received_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 일지 행 이후의 일. RESTORED·DEFERRED·GAVE_UP 은 기동 복원(T3), SETTLED 는 pump 뒤 기록(§6.2)이 적는다.
-- execution_id 는 RESTORED 면 새 실행, SETTLED 면 정착한 실행이다. detail 은 DEFERRED·GAVE_UP 의 사유, SETTLED 의 물리 상태다.
-- 정착도 포기도 없는 일지 행이 다시 짓기의 대상이다.
CREATE TABLE execution_journal_event (
    event_id     BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_order_id TEXT        NOT NULL REFERENCES execution_journal (job_order_id),
    kind         TEXT        NOT NULL CHECK (kind IN ('RESTORED', 'DEFERRED', 'GAVE_UP', 'SETTLED')),
    instance_id  TEXT        NOT NULL CHECK (instance_id <> ''),
    execution_id TEXT,
    detail       TEXT,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((kind IN ('RESTORED', 'SETTLED')) = (execution_id IS NOT NULL)),
    CHECK ((kind = 'RESTORED') = (detail IS NULL))
);

CREATE INDEX execution_journal_event_order ON execution_journal_event (job_order_id, event_id);

-- 송신 기록(T6). pending() 의 응답마다 한 행이다. 내용 키의 칸을 그대로 둔다. 단위 id 목록은 정렬한 JSON 배열이고,
-- incomplete_units 는 사유 없이 단위 id 만 든다. disposition 은 SENT(송신) 또는 RESTART_DUPLICATE(재기동 중복, 송신 안 함)다.
CREATE TABLE job_response_log (
    log_id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instance_id       TEXT        NOT NULL CHECK (instance_id <> ''),
    job_response_id   TEXT        NOT NULL CHECK (job_response_id <> ''),
    job_order_id      TEXT        NOT NULL CHECK (job_order_id <> ''),
    execution_id      TEXT        NOT NULL CHECK (execution_id <> ''),
    version           INTEGER     NOT NULL,
    physical_state    TEXT        NOT NULL,
    required_evidence TEXT        NOT NULL,
    reached_evidence  TEXT        NOT NULL,
    completed_units   JSONB       NOT NULL,
    unverified_units  JSONB       NOT NULL,
    incomplete_units  JSONB       NOT NULL,
    in_doubt_units    JSONB       NOT NULL,
    operator_required BOOLEAN     NOT NULL,
    residual_hold     TEXT        NOT NULL,
    blocked_by        JSONB       NOT NULL,
    disposition       TEXT        NOT NULL CHECK (disposition IN ('SENT', 'RESTART_DUPLICATE')),
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (instance_id, job_response_id)
);

CREATE INDEX job_response_log_order ON job_response_log (job_order_id, log_id);

-- 인시던트 사본(T7). 키는 (인스턴스, 인시던트 id)다. detail 은 봉인 뒤 처음 적을 때의 호스트 상세 본문(S4a 의 IncidentDetailView)이다.
-- JSONB 가 아니라 JSON 이다. 받은 글자를 그대로 두어 맵 칸(orderParameters, unitParameters 등)의 키 순서가 REST 와 같게 남는다.
-- copy_id 는 적은 순서다.
CREATE TABLE incident_copy (
    copy_id      BIGINT      GENERATED ALWAYS AS IDENTITY UNIQUE,
    instance_id  TEXT        NOT NULL CHECK (instance_id <> ''),
    incident_id  TEXT        NOT NULL CHECK (incident_id <> ''),
    execution_id TEXT        NOT NULL,
    job_order_id TEXT        NOT NULL,
    unit_id      TEXT        NOT NULL,
    detail       JSON        NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (instance_id, incident_id)
);

-- 사본의 판단. 봉인 때의 판단 칸은 낡으므로 따로 적는다. 인시던트 하나에 판단은 많아야 하나다(picasso IncidentLog).
CREATE TABLE incident_copy_resolution (
    instance_id     TEXT        NOT NULL,
    incident_id     TEXT        NOT NULL,
    decision        TEXT        NOT NULL CHECK (decision <> ''),
    decided_at      TIMESTAMPTZ NOT NULL,
    wall_clock_at   TIMESTAMPTZ NOT NULL,
    decided_by_id   TEXT        NOT NULL CHECK (decided_by_id <> ''),
    decided_by_kind TEXT        NOT NULL CHECK (decided_by_kind <> ''),
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (instance_id, incident_id),
    FOREIGN KEY (instance_id, incident_id) REFERENCES incident_copy (instance_id, incident_id)
);

-- 덧붙이기만 한다. V1 의 mission_append_only() 를 그대로 쓴다.
CREATE TRIGGER execution_journal_no_update_delete
    BEFORE UPDATE OR DELETE ON execution_journal
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER execution_journal_no_truncate
    BEFORE TRUNCATE ON execution_journal
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER execution_journal_event_no_update_delete
    BEFORE UPDATE OR DELETE ON execution_journal_event
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER execution_journal_event_no_truncate
    BEFORE TRUNCATE ON execution_journal_event
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER job_response_log_no_update_delete
    BEFORE UPDATE OR DELETE ON job_response_log
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER job_response_log_no_truncate
    BEFORE TRUNCATE ON job_response_log
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER incident_copy_no_update_delete
    BEFORE UPDATE OR DELETE ON incident_copy
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER incident_copy_no_truncate
    BEFORE TRUNCATE ON incident_copy
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER incident_copy_resolution_no_update_delete
    BEFORE UPDATE OR DELETE ON incident_copy_resolution
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER incident_copy_resolution_no_truncate
    BEFORE TRUNCATE ON incident_copy_resolution
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();
