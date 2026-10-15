-- 놓은 실행의 정착 본문 읽기(picasso Middleware.forget). 실행 호스트가 정착을 적고 실행을 picasso 메모리에서 놓은 뒤에도
-- 인자 없는 실행 목록과 좁힌 목록은 이 인스턴스의 그 실행을 정착 이벤트의 본문(V8)으로 싣는다. 인스턴스와 실행 id 로 가장 최근 정착을 찾는다.
CREATE INDEX execution_journal_event_settled_execution ON execution_journal_event (instance_id, execution_id, event_id) WHERE kind = 'SETTLED';
