-- 미들웨어 시간값 넷(S3c 스펙 §6.1). 현장 설정 한 세트에 칸을 더하며 버전 체계는 S2 그대로다.
-- 기존 행은 picasso 케이퍼빌리티 기본값(앞 폭 30, 뒤 폭 15, inDoubtGrace 60, stallWindow 300)으로 채운다.
-- UPDATE 로 채우지 않는다(덧붙이기 전용 트리거가 막는다). ADD COLUMN 의 DEFAULT 가 기존 행을 채우고 행 트리거에 걸리지 않는다.
-- DB 제약은 0 보다 큼만 둔다. 허용 범위는 운영 서비스의 사본 상수가 쥔다(범위를 바꿀 때마다 마이그레이션이 필요해지므로).
ALTER TABLE site_settings
    ADD COLUMN evidence_before_seconds INTEGER NOT NULL DEFAULT 30 CHECK (evidence_before_seconds > 0),
    ADD COLUMN evidence_after_seconds  INTEGER NOT NULL DEFAULT 15 CHECK (evidence_after_seconds > 0),
    ADD COLUMN in_doubt_grace_seconds  INTEGER NOT NULL DEFAULT 60 CHECK (in_doubt_grace_seconds > 0),
    ADD COLUMN stall_window_seconds    INTEGER NOT NULL DEFAULT 300 CHECK (stall_window_seconds > 0);

-- 기본값을 지운다. 새 칸을 빠뜨린 INSERT 가 조용히 기본값으로 들어가지 않고 실패한다.
ALTER TABLE site_settings
    ALTER COLUMN evidence_before_seconds DROP DEFAULT,
    ALTER COLUMN evidence_after_seconds DROP DEFAULT,
    ALTER COLUMN in_doubt_grace_seconds DROP DEFAULT,
    ALTER COLUMN stall_window_seconds DROP DEFAULT;

-- 실행 호스트가 읽는 현재 버전 뷰(S3c 스펙 §6.3, T7). 가장 큰 버전 한 행의 버전과 시간값 넷이다.
-- 호스트는 표가 아니라 이 뷰만 읽는다. 칸 이름과 형이 두 프로세스의 계약이며, 바꾸면 호스트의 상수도 바꿔야 한다.
CREATE VIEW site_timings_current AS
SELECT version, evidence_before_seconds, evidence_after_seconds, in_doubt_grace_seconds, stall_window_seconds
FROM site_settings
ORDER BY version DESC
LIMIT 1;
