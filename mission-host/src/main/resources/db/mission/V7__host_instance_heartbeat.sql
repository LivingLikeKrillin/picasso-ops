-- 실행 호스트 인스턴스와 심장박동(HA 설계 기록 §7·§8 단계 0). 인스턴스는 미들웨어가 뜬 한 번(`instanceId`)이고 일지의 instance_id 와 같다.
-- 인스턴스마다 한 행이고 그 인스턴스만 고친다. 덧붙이기 전용 표가 아니다(심장박동마다 행을 더하면 끝없이 자란다).
-- 시각은 모두 DB 의 clock_timestamp() 다. 애플리케이션 시계를 쓰지 않는다(설계 기록 §4.3).
--
-- 기동 때 한 행을 넣고, 주기마다 last_seen_at 과 expires_at(= 그때 + 만료 폭)을 고치며, 정상 종료 때 stopped_at 을 적는다.
-- 살아 있음 = 행이 있고 stopped_at 이 NULL 이며 expires_at 이 지금보다 뒤. 행이 없으면(이 표 이전의 인스턴스 포함) 살아 있지 않다.
-- 리스가 아니다: 세대도 펜싱도 없다. 만료 뒤 깨어난 프로세스를 막지 않는다(단계 1 의 리스 행이 맡는다).
CREATE TABLE host_instance (
    instance_id  TEXT        PRIMARY KEY CHECK (instance_id <> ''),
    started_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at   TIMESTAMPTZ NOT NULL,
    stopped_at   TIMESTAMPTZ,
    CHECK (expires_at >= last_seen_at OR stopped_at IS NOT NULL)
);
