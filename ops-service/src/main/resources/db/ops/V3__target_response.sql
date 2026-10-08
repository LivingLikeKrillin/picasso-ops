-- 조작 기록의 응답 칸 이름을 넓힌다(S3a 스펙 §8). S3a 부터 registry 가 아닌 실행 호스트의 응답도 이 칸에 남는다.
-- 칸 수와 덧붙이기 전용 트리거는 그대로다. RENAME 은 행을 고치지 않으므로 행 트리거에 걸리지 않는다.
ALTER TABLE operation_log RENAME COLUMN registry_response TO target_response;
