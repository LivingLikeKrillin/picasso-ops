-- 정착한 실행의 이력(HA 설계 기록 §10). 화면의 «활성 + 최근 N, 이력은 페이지» 가 정착 실행을 미들웨어 메모리가 아니라 이 기록에서 읽는다.
-- 정착 이벤트(SETTLED)에 그때의 실행 본문(`GET /host/executions` 의 실행 한 줄, ExecutionView JSON)을 같이 적는다. 기체(재할당 뒤의 것),
-- 단위 상태와 근거, 마지막 작업 응답, 운영자 취소가 거기 든다. 이 마이그레이션 전에 적힌 SETTLED 행과 다른 이벤트는 NULL 이다.
-- JSONB 가 아니라 JSON 이다. 적은 글자를 그대로 두어 칸 순서가 REST 의 실행 한 줄과 같게 남는다(인시던트 사본과 같은 까닭).
-- 덧붙이기 전용은 그대로다(행 트리거는 UPDATE·DELETE 만 막는다).
ALTER TABLE execution_journal_event ADD COLUMN snapshot JSON;

ALTER TABLE execution_journal_event ADD CONSTRAINT execution_journal_event_snapshot_settled
    CHECK (snapshot IS NULL OR kind = 'SETTLED');

-- 정착 이력 페이지. event_id 가 정착 차례(커서)다.
CREATE INDEX execution_journal_event_settled ON execution_journal_event (event_id) WHERE kind = 'SETTLED';
