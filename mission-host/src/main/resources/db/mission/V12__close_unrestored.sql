-- 복원 못 한 실행의 운영자 종결(결함 14). 덧붙이기만 한다. 시각은 DB 의 clock_timestamp() 다.
-- 기동 복원이 GAVE_UP 이나 DEFERRED(스냅숏을 못 읽음)로 남긴 일지 행은 지금까지 나가는 길이 없었다. 사람 승인자가 사유와 함께 그 작업 지시를
-- 끝내면 일지 이벤트 CLOSED 하나와 이 표의 한 행을 적는다. 같은 트랜잭션에서 결과 아웃박스에 UNVERIFIED 종결 응답을 넣어 상위로 보낸다.

-- CLOSED: 운영자가 복원 못 한 실행을 종결했다. instance_id 는 종결한 인스턴스이고 execution_id 는 없다(DEFERRED·GAVE_UP 과 같다).
-- 종결한 (인스턴스, 실행) 쌍은 아래 표에 있어 exec-N 이 늘 자기 인스턴스와 짝지어진다. detail 은 근거(SNAPSHOT·ATTESTED)다.
-- 일지 행의 SETTLED·REOPENED·GAVE_UP·CLOSED 가운데 마지막이 CLOSED 이면 리빌드의 대상도 판정 제외 대상도 아니다.
ALTER TABLE execution_journal_event DROP CONSTRAINT execution_journal_event_kind_check;
ALTER TABLE execution_journal_event ADD CONSTRAINT execution_journal_event_kind_check
    CHECK (kind IN ('RESTORED', 'DEFERRED', 'GAVE_UP', 'SETTLED', 'REOPENED', 'CLOSED'));

-- 종결 한 행. 작업 지시마다 하나이고(job_order_id UNIQUE) 요청 id 는 한 번이다(request_id UNIQUE, 응답 없음 뒤 재조회가 이것으로 대조한다).
-- closed_instance_id·closed_execution_id 는 종결한 실행이다(복원 행의 바로 앞 인스턴스와 실행). restore_result·restore_reason 은 그때의 복원 결과다.
-- basis 는 SNAPSHOT(기체 스냅숏에 그 작업 지시의 살아 있는 태스크가 없다)이나 ATTESTED(운영자가 로봇이 멈춘 것을 현장에서 확인했다고 증언했다)다.
-- response_id 는 결과 아웃박스와 송신 기록에 낸 응답 id(`close-<event_id>`)다.
CREATE TABLE execution_close (
    close_id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id            BIGINT      NOT NULL UNIQUE REFERENCES execution_journal_event (event_id),
    job_order_id        TEXT        NOT NULL UNIQUE REFERENCES execution_journal (job_order_id),
    instance_id         TEXT        NOT NULL CHECK (instance_id <> ''),
    closed_instance_id  TEXT        NOT NULL CHECK (closed_instance_id <> ''),
    closed_execution_id TEXT        NOT NULL CHECK (closed_execution_id <> ''),
    robot_id            TEXT        NOT NULL CHECK (robot_id <> ''),
    restore_result      TEXT        NOT NULL CHECK (restore_result IN ('GAVE_UP', 'DEFERRED')),
    restore_reason      TEXT        NOT NULL CHECK (restore_reason <> ''),
    basis               TEXT        NOT NULL CHECK (basis IN ('SNAPSHOT', 'ATTESTED')),
    approver_id         TEXT        NOT NULL CHECK (approver_id <> ''),
    approver_kind       TEXT        NOT NULL CHECK (approver_kind = 'PERSON'),
    reason              TEXT        NOT NULL CHECK (btrim(reason) <> ''),
    request_id          UUID        NOT NULL UNIQUE,
    response_id         TEXT        NOT NULL CHECK (response_id <> ''),
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 덧붙이기만 한다. V2 의 mission_record_append_only() 를 그대로 쓴다.
CREATE TRIGGER execution_close_no_update_delete
    BEFORE UPDATE OR DELETE ON execution_close
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER execution_close_no_truncate
    BEFORE TRUNCATE ON execution_close
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
