-- 정착 뒤 다시 돈 실행의 일지(S4b 스펙 §6.2·§6.3). 덧붙이기 전용은 그대로다. 시각은 DB 의 clock_timestamp() 다.
-- 정착한 실행은 같은 판의 보류 단위에 대한 운영자 판단이나 개정판 수락으로 다시 돌 수 있다. 일지가 첫 판과 정착만 들면 기동 복원이
-- 그 실행을 다시 짓지 않고 그 기체는 판정에서 빠지지 않는다. 그래서 받은 개정판과 «다시 돎» 을 적는다.

-- 받은 개정판. 이미 있던 실행의 revise 가 ACCEPTED 를 낸 판마다 한 행이다. 작업 지시는 execution_journal.job_order 와 같은 모양이다.
-- 기동 복원은 그 작업 지시의 가장 높은 판(없으면 일지 행의 작업 지시)으로 다시 짓는다. 임무 버전과 기체는 일지 행의 것이다
-- (revise 는 실행이 쥔 정의로 계획하고 이 호스트는 재할당을 부르지 않는다).
CREATE TABLE execution_journal_revision (
    revision_id  BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_order_id TEXT        NOT NULL REFERENCES execution_journal (job_order_id),
    version      INTEGER     NOT NULL,
    job_order    JSONB       NOT NULL,
    instance_id  TEXT        NOT NULL CHECK (instance_id <> ''),
    execution_id TEXT        NOT NULL CHECK (execution_id <> ''),
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (job_order_id, version)
);

CREATE TRIGGER execution_journal_revision_no_update_delete
    BEFORE UPDATE OR DELETE ON execution_journal_revision
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER execution_journal_revision_no_truncate
    BEFORE TRUNCATE ON execution_journal_revision
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();

-- REOPENED: 이 인스턴스가 정착을 적은 실행이 다시 돈다(SETTLED 뒤). execution_id 는 그 실행, detail 은 그때의 물리 상태다.
-- 실행 호스트가 그 실행의 다음 pump 전에 적는다. 일지 행의 SETTLED·REOPENED·GAVE_UP 가운데 마지막이 REOPENED 이거나 셋 다 없으면
-- 다시 짓기의 대상이고, 마지막이 GAVE_UP 이면 판정 제외 대상이다.
ALTER TABLE execution_journal_event DROP CONSTRAINT execution_journal_event_kind_check;
ALTER TABLE execution_journal_event ADD CONSTRAINT execution_journal_event_kind_check
    CHECK (kind IN ('RESTORED', 'DEFERRED', 'GAVE_UP', 'SETTLED', 'REOPENED'));

ALTER TABLE execution_journal_event DROP CONSTRAINT execution_journal_event_check;
ALTER TABLE execution_journal_event ADD CONSTRAINT execution_journal_event_execution_check
    CHECK ((kind IN ('RESTORED', 'SETTLED', 'REOPENED')) = (execution_id IS NOT NULL));
