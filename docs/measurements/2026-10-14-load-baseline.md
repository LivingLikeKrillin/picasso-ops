# 부하 기준선 — 2026-10-14

HA 권위 설계 §10(`docs/superpowers/specs/2026-10-14-ha-authority-design.md`)의 핫스팟 다섯. 측정 대상 커밋 `dd48061`(picasso 서브모듈 `4d6d768`). 하네스 `load/`.

## 실행

```
./gradlew :load:loadBench --args="all"            # 전체(약 9분)
./gradlew :load:loadBench --args="stations"       # 하나만: stations · delivery · pump · explain
./gradlew :load:loadBench --args="all quick"      # 작은 규모로 배선만 확인(약 1분)
```

결과 원문: `load/build/load/{environment,explain,delivery,stations,pump}.md`. `./gradlew build` 는 `load` 소스 세트를 컴파일하지도 돌리지도 않는다.

## 환경

| 항목 | 값 |
|---|---|
| 기계 | Intel Core Ultra 5 125H, 논리 CPU 18, RAM 31.6 GiB |
| OS | Windows 11 Pro 10.0.26200 |
| Docker | Docker Desktop 26.1.4 (WSL2 커널 5.15.146.1), VM CPU 18 · 메모리 15.4 GiB, 컨테이너 자원 제한 없음 |
| Postgres | postgres:16-alpine (16.15), `fsync=off`(Testcontainers 기본), `pg_stat_statements` track=top · track_utility=off, 그 밖 기본값(jit on) |
| JVM(측정) | OpenJDK 21.0.6 (Gradle 툴체인), `-Xmx4g`, 한 JVM 에 mimic·실행 호스트·운영 서비스·하네스 |
| JDBC 왕복 | 하네스 JVM → 컨테이너 `SELECT 1` p50 0.88~1.72 ms (세트마다 아래에 적음). Docker Desktop 포트 포워딩 경유 |
| Gradle | 9.7.1, 데몬 JDK 25.0.2 |

## 근거 대조 (§10 의 주장 ↔ 코드)

| 핫스팟 | 위치 | 코드 |
|---|---|---|
| 1 tick 주기 | ops-service/.../web/StationController.kt:32 | `@Scheduled(fixedDelayString="${ops.stations.poll-ms:2000}")` → `coordinator.tick()` |
| 1 전체 목록 | ops-service/.../stations/StationCoordinator.kt:24 | `stations()` = `SELECT * FROM ops.rf_station ORDER BY station_id` |
| 1 O(S²) | StationCoordinator.kt:72, :75, :106 | tick 마다 `stations()` 1회 + 스테이션마다 `stations().single{…}` 2회 → 2S+1 회 × S 행 |
| 1 호스트 조회 | StationCoordinator.kt:141 → StationPort.kt:89-93 → WorkRequests.kt:607-611 → :517-520 | RUNNING·WAITING_ROBOT 스테이션마다 `port.receipt` → `requests.result` → `detail` → `host.executions()` = `GET /host/executions` 전체 |
| 1 tick 당 호스트 읽기 | StationCoordinator.kt:69, :71 | `port.read()`(GET /host/cell) 1회, `port.permit()`(GET /host/work-permits) 1회 |
| 2 전달 주기 | ops-service/.../requests/WorkResultDelivery.kt:30-37 | 2초마다 `INSERT INTO ops.work_result_delivery … SELECT … FROM ops.work_result v JOIN ops.work_request r … ON CONFLICT DO NOTHING`, 그 뒤 많아야 10건 전송(:38) |
| 1·2 스케줄 풀 | ops-service/src/main/resources/ops-service.properties:29 | `spring.task.scheduling.pool.size=2` (주기 작업 셋: 스테이션 tick·결과 수집·결과 전달) |
| 3 pump 주기 | mission-host/.../MissionHost.kt:1096, :486-488 | `PUMP_PERIOD = 250ms`, `scheduleWithFixedDelay(::pumpOnce …)` |
| 3 실행 전체 | picasso/.../middleware/Middleware.kt:829-834 | `pump()` 가 `executions.values.forEach { pump(it) }` (정착한 실행도 남아 매번 돈다) |
| 3 인시던트·실행 훑기 | MissionHost.kt:820-826, :1009 | `recordAfterPump` 가 `incidents()` 전체 복사·`filter`, `executions()` `filter`; `journalBlocked()` 가 실행 전체 `filter` |
| 3 실행 목록 응답 | MissionHost.kt:717-731 | `GET /host/executions` 가 호스트 잠금 아래에서 실행마다 `responses.lastOrNull{…}` (실행 × 아웃박스) |
| 4 아웃박스 | Middleware.kt:179, :323, :2003, :2008-2011 | `outbox` 는 더하기만 함; `pending()` 은 매번 전체 `filter`; `ack` 은 `firstOrNull` 선형 탐색 |
| 4 한계 기록 | MissionHost.kt:388-390 | «ack 한 응답도 들고 있어 계속 자라는 것은 그대로 한계다(S3a 스펙 §12)» |
| 5 인덱스 | ops-service/src/main/resources/db/ops/V6__work_requests.sql:17-19 | PK(receipt_id), UNIQUE(job_order_id), UNIQUE(source_system_id,external_order_id), `work_request_received(received_at DESC,receipt_id)` |
| 5 목록·요약 | ops-service/.../requests/WorkRequests.kt:417-430, :431-438 | `list` = count + 쪽(strpos 검색, 미ACK 상관 부질의); `summary` = 상태별 count + 미ACK LATERAL + 최근 5 |
| 5 화면 주기 | ui/src/poll.ts:2, ui/src/components/OverviewArea.tsx:17-25, WorkRequests.tsx:49 | `POLL_MS = 5000`; 개요 화면이 5초마다 `/api/work-requests/summary`, 작업 요청 화면이 5초마다 목록 |

## 핫스팟 1 — 스테이션 tick

`StationCoordinator`·`OpsStationPort`·`WorkRequests` 운영 코드 그대로, 운영 서비스 빈으로 세움. 주기 tick 끔, 하네스가 `tick()` 직접 호출. 예열 3회 뒤 20회. idle = 점유 없음, active = 스테이션마다 RUNNING 작업 1(준비 참·완료 거짓, 상태 불변). 실행 호스트 실행 0. DB 문장 = pg_stat_statements 의 ops 스키마 문장.

- 세트(스테이션 1) — Postgres 16.15, JDBC 왕복 SELECT 1 p50 1.46 ms / p95 2.21 ms / max 3.18 ms
- 세트(스테이션 10) — Postgres 16.15, JDBC 왕복 SELECT 1 p50 1.57 ms / p95 1.95 ms / max 2.40 ms
- 세트(스테이션 50) — Postgres 16.15, JDBC 왕복 SELECT 1 p50 1.34 ms / p95 1.65 ms / max 2.03 ms
- 세트(스테이션 100) — Postgres 16.15, JDBC 왕복 SELECT 1 p50 1.07 ms / p95 1.44 ms / max 1.64 ms

| 스테이션 | 상태 | tick p50 ms | p95 ms | max ms | DB 문장/tick | DB rows/tick | rf_station 전체 읽기/tick | 그 rows/tick | DB 실행 ms/tick | 호스트 GET/tick (cell / work-permits / executions) | executions GET p50 ms | 측정 중 station_event 증가 | 측정 뒤 RUNNING 수 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | idle | 30.1 | 42.6 | 46.1 | 5.00 | 4.00 | 3.00 | 3.00 | 0.47 | 1.00 / 1.00 / 0.00 | - | 0 | 0 |
| 1 | active | 67.5 | 87.5 | 90.1 | 23.0 | 15.0 | 3.00 | 3.00 | 1.43 | 1.00 / 1.00 / 1.00 | 4.84 | 0 | 1 |
| 10 | idle | 130 | 162 | 224 | 41.0 | 220 | 21.0 | 210 | 2.87 | 1.00 / 1.00 / 0.00 | - | 0 | 0 |
| 10 | active | 474 | 545 | 678 | 221 | 330 | 21.0 | 210 | 14.4 | 1.00 / 1.00 / 10.0 | 4.01 | 0 | 10 |
| 50 | idle | 421 | 465 | 478 | 201 | 5100 | 101 | 5050 | 14.9 | 1.00 / 1.00 / 0.00 | - | 0 | 0 |
| 50 | active | 1541 | 1729 | 1862 | 1101 | 5650 | 101 | 5050 | 47.2 | 1.00 / 1.00 / 50.0 | 2.26 | 0 | 50 |
| 100 | idle | 563 | 636 | 657 | 401 | 20200 | 201 | 20100 | 38.7 | 1.00 / 1.00 / 0.00 | - | 0 | 0 |
| 100 | active | 3378 | 4021 | 4190 | 2201 | 21300 | 201 | 20100 | 122 | 1.00 / 1.00 / 100 | 2.02 | 0 | 100 |

문장별(tick 당), 스테이션 100 · idle

| calls/회차 | rows/회차 | DB ms/회차 | blocks/회차 | 문장 |
|---|---|---|---|---|
| 201 | 20100 | 30.6 | 402 | `SELECT * FROM ops.rf_station ORDER BY station_id` |
| 100 | 100 | 5.17 | 300 | `SELECT station_id FROM ops.rf_station WHERE station_id=$1 FOR UPDATE` |
| 100 | 0.00 | 2.93 | 200 | `SELECT run_id FROM ops.station_run WHERE station_id=$1 AND state='QUEUED' ORDER BY created_at,run_id LIMIT 1` |

문장별(tick 당), 스테이션 100 · active (상위 12)

| calls/회차 | rows/회차 | DB ms/회차 | blocks/회차 | 문장 |
|---|---|---|---|---|
| 500 | 500 | 21.9 | 1000 | `SELECT * FROM ops.work_request WHERE receipt_id=$1 AND ($2 IS NULL OR source_system_id=$3)` |
| 300 | 300 | 12.7 | 600 | `SELECT * FROM ops.station_run WHERE run_id=$1` |
| 201 | 20100 | 39.3 | 603 | `SELECT * FROM ops.rf_station ORDER BY station_id` |
| 200 | 200 | 10.3 | 975 | `SELECT station_id FROM ops.rf_station WHERE station_id=$1 FOR UPDATE` |
| 200 | 0.00 | 3.94 | 400 | `SELECT * FROM ops.work_result WHERE receipt_id=$1 AND ($2 IS NULL OR version=$3) ORDER BY version DESC LIMIT 1` |
| 100 | 100 | 12.3 | 688 | `SELECT r.run_id,r.station_id,r.state,r.reason,s.owner, (SELECT count(*) FROM ops.station_run q WHERE q.station` |
| 100 | 0.00 | 4.72 | 294 | `SELECT r.receipt_id,r.external_order_id,r.state,s.created_at FROM ops.work_request_supersession s JOIN ops.wor` |
| 100 | 100 | 4.48 | 300 | `SELECT receipt_id FROM ops.work_request WHERE receipt_id=$1 FOR UPDATE` |
| 100 | 0.00 | 4.12 | 294 | `SELECT r.receipt_id,r.external_order_id,r.state,s.created_at FROM ops.work_request_supersession s JOIN ops.wor` |
| 100 | 0.00 | 3.73 | 200 | `SELECT request_id,source,identity,actor,reason,created_at,context FROM ops.work_request_source_confirmation WH` |
| 100 | 0.00 | 1.81 | 200 | `SELECT attempt_id,actor,reason,created_at FROM ops.work_request_recovery WHERE receipt_id=$1 ORDER BY created_` |
| 100 | 0.00 | 1.69 | 200 | `SELECT version,state,attempts,last_error,next_attempt_at,delivered_at FROM ops.work_result_delivery WHERE rece` |

### 1×3 — 실행 2000 이 쌓인 호스트에 대고 (기체 50 세트, 10회)

| 스테이션 | 상태 | tick p50 ms | p95 ms | max ms | DB 문장/tick | DB rows/tick | rf_station 전체 읽기/tick | 그 rows/tick | DB 실행 ms/tick | 호스트 GET/tick (cell / work-permits / executions) | executions GET p50 ms | 측정 중 station_event 증가 | 측정 뒤 RUNNING 수 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 50 | active (호스트 실행 2000) | 3418 | 4077 | 4077 | 1101 | 5650 | 101 | 5050 | 37.1 | 1.00 / 1.00 / 50.0 | 42.2 | 0 | 50 |

## 핫스팟 2 — 결과 전달

`WorkResultDelivery` 운영 코드 그대로, callback 은 같은 JVM 루프백 HTTP 200. 결과 행마다 work_request 1·work_result 1(source mock-mes, 스냅숏 약 1 KB), 결과 수는 한 세트에서 누적. 첫 회차 = 새 결과가 막 들어온 회차. 정상 = 전달 행 전부 DELIVERED 뒤 20회.

- 세트 — Postgres 16.15, JDBC 왕복 SELECT 1 p50 1.48 ms / p95 1.86 ms / max 5.00 ms

| 결과 수 | 첫 회차 ms | 첫 회차 전송 수 | 전달 행 수 | 정상 p50 ms | 정상 p95 ms | 정상 max ms | 정상 DB 문장/회차 | INSERT…SELECT DB ms/회차 | INSERT…SELECT blocks/회차 | 첫 회차 DB 문장 |
|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 172 | 10 | 100 | 6.58 | 7.98 | 8.43 | 2.00 | 2.43 | 217 | 32.0 |
| 1000 | 162 | 10 | 1000 | 24.6 | 33.8 | 37.6 | 2.05 | 20.8 | 3152 | 31.0 |
| 10000 | 479 | 10 | 10000 | 149 | 244 | 267 | 2.15 | 160 | 31503 | 31.0 |
| 100000 | 6351 | 10 | 100000 | 1768 | 2961 | 3066 | 4.00 | 1971 | 414889 | 38.0 |

EXPLAIN (ANALYZE, BUFFERS) — 정상 상태 `INSERT…SELECT`, 결과 100000

```
Insert on work_result_delivery  (cost=16786.42..21468.93 rows=0 width=0) (actual time=1988.564..1988.566 rows=0 loops=1)
  Conflict Resolution: NOTHING
  Conflict Arbiter Indexes: work_result_delivery_pkey
  Tuples Inserted: 0
  Conflicting Tuples: 100000
  Buffers: shared hit=401349 read=13550
  ->  Hash Join  (cost=16786.42..21468.93 rows=100000 width=160) (actual time=111.275..298.210 rows=100000 loops=1)
        Hash Cond: (v.receipt_id = r.receipt_id)
        Buffers: shared hit=1339 read=13550
        ->  Index Only Scan using work_result_pkey on work_result v  (cost=0.42..3920.42 rows=100000 width=20) (actual time=0.037..46.365 rows=100000 loops=1)
              Heap Fetches: 0
              Buffers: shared hit=603
        ->  Hash  (cost=15536.00..15536.00 rows=100000 width=16) (actual time=110.629..110.630 rows=100000 loops=1)
              Buckets: 131072  Batches: 1  Memory Usage: 5712kB
              Buffers: shared hit=736 read=13550
              ->  Seq Scan on work_request r  (cost=0.00..15536.00 rows=100000 width=16) (actual time=0.035..76.135 rows=100000 loops=1)
                    Filter: (source_system_id = 'mock-mes'::text)
                    Buffers: shared hit=736 read=13550
Planning:
  Buffers: shared hit=356 read=9
Planning Time: 5.246 ms
Execution Time: 1989.787 ms
```

## 핫스팟 3·4 — 실행 호스트 pump 와 아웃박스

주기 pump 정지(`MissionHost.close`), 하네스가 `pumpOnce()` 직접 호출. 주기 = 기체마다 InspectAsset(E0, 대상 bay-7) 1건 제출 → 가상 시계 5초 밀기마다 pump 2회 → 전부 정착. idle = 정착 뒤 pump 30회. 미들웨어 크기는 반사로 읽음. GET /host/executions 는 10회.

- 세트(기체 1) — Postgres 16.15, JDBC 왕복 SELECT 1 p50 1.72 ms / p95 2.11 ms / max 2.35 ms
- 세트(기체 10) — Postgres 16.15, JDBC 왕복 SELECT 1 p50 0.93 ms / p95 1.24 ms / max 1.43 ms
- 세트(기체 50) — Postgres 16.15, JDBC 왕복 SELECT 1 p50 0.88 ms / p95 1.17 ms / max 1.41 ms

일하는 pump

| 기체 M | 실행 수 구간 | pump 수 | p50 ms | p95 ms | max ms | DB 문장/pump |
|---|---|---|---|---|---|---|
| 1 | 0→10 | 210 | 10.3 | 26.9 | 132 | 0.47 |
| 1 | 10→50 | 840 | 4.77 | 12.0 | 58.9 | 0.46 |
| 10 | 0→10 | 21 | 26.2 | 57.6 | 168 | 4.33 |
| 10 | 10→100 | 189 | 17.9 | 68.0 | 175 | 4.34 |
| 10 | 100→300 | 420 | 13.6 | 60.1 | 148 | 4.33 |
| 50 | 0→50 | 21 | 57.8 | 113 | 546 | 21.5 |
| 50 | 50→500 | 189 | 65.2 | 335 | 562 | 21.5 |
| 50 | 500→1000 | 210 | 62.8 | 257 | 618 | 21.5 |
| 50 | 1000→2000 | 412 | 53.7 | 190 | 466 | 21.9 |

idle pump 와 크기

| 기체 M | 실행 E | 아웃박스(responses) | pending | 인시던트 | idle p50 ms | p95 ms | max ms | DB 문장/pump | pending() p50 µs | incidents() p50 µs | GET /host/executions p50 ms | p95 ms | max ms | 그 응답 bytes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 10 | 10 | 0 | 0 | 1.79 | 2.93 | 3.12 | 0.00 | 2.90 | 1.80 | 4.92 | 81.2 | 81.2 | 7270 |
| 1 | 50 | 50 | 0 | 0 | 0.74 | 1.09 | 1.41 | 0.00 | 2.30 | 1.00 | 5.95 | 7.59 | 7.59 | 35710 |
| 10 | 10 | 10 | 0 | 0 | 1.14 | 1.40 | 1.64 | 0.00 | 1.50 | 1.30 | 3.31 | 18.0 | 18.0 | 7270 |
| 10 | 100 | 100 | 0 | 0 | 0.71 | 1.01 | 1.35 | 0.00 | 1.20 | 0.60 | 5.80 | 6.62 | 6.62 | 71263 |
| 10 | 300 | 300 | 0 | 0 | 0.60 | 0.90 | 1.36 | 0.00 | 1.80 | 0.80 | 6.29 | 8.41 | 8.41 | 214063 |
| 50 | 50 | 50 | 0 | 0 | 0.85 | 1.43 | 1.75 | 0.00 | 2.30 | 1.20 | 2.61 | 15.9 | 15.9 | 35710 |
| 50 | 500 | 500 | 0 | 0 | 0.88 | 1.11 | 1.45 | 0.00 | 3.90 | 1.20 | 8.07 | 10.2 | 10.2 | 356863 |
| 50 | 1000 | 1000 | 0 | 0 | 0.83 | 1.16 | 1.61 | 0.00 | 7.10 | 1.10 | 16.1 | 22.2 | 22.2 | 713866 |
| 50 | 2000 | 2000 | 0 | 0 | 0.88 | 1.23 | 2.00 | 0.00 | 8.90 | 0.90 | 37.9 | 43.6 | 43.6 | 1430866 |

idle pump 의 DB 문장: 0 (기체 50 · 실행 2000, pg_stat_statements 전체).

## 핫스팟 5 — `work_request` 목록·검색

시드: 상태 ACCEPTED 85%·REJECTED 5%·UNASSIGNED 5%·RECEIVED 3%·IN_DOUBT 2%, source mock-mes 70%·ops:manual 30%, payload·normalized_order 각 약 0.8 KB, 전체의 60%(모두 mock-mes)에 결과 1판(그 가운데 80% ACK). VACUUM ANALYZE 뒤 같은 질의 3회 중 마지막. 질의는 `WorkRequests.kt` 문자열에 값을 펼친 것. «실측» 행은 운영 코드 메서드 10회.

- 세트 — Postgres 16.15, JDBC 왕복 SELECT 1 p50 1.59 ms / p95 1.99 ms / max 2.46 ms

| 행 수 | 질의 | Execution Time ms | 첫 스캔 노드 | 최상위 Buffers |
|---|---|---|---|---|
| 10000 | list 기본 count | 4.762 | Seq Scan on work_request | shared hit=2000 |
| 10000 | list 기본 1쪽(50) | 0.162 | Index Scan using work_request_received on work_request | shared hit=12 |
| 10000 | list 깊은 쪽(OFFSET 5000) | 4.209 | Index Scan using work_request_received on work_request | shared hit=1036 |
| 10000 | list 검색 count | 6.205 | Seq Scan on work_request | shared hit=2000 |
| 10000 | list 검색 1쪽 | 5.727 | Index Scan using work_request_received on work_request | shared hit=2051 |
| 10000 | list 상태 IN_DOUBT count | 4.070 | Seq Scan on work_request | shared hit=2000 |
| 10000 | list 상태 IN_DOUBT 1쪽 | 1.599 | Index Scan using work_request_received on work_request | shared hit=514 |
| 10000 | list NEEDS_CONFIRMATION 1쪽 | 0.542 | Index Scan using work_request_received on work_request | shared hit=206 |
| 10000 | list 미ACK count | 24.476 | Seq Scan on work_request | shared hit=22000 |
| 10000 | list 미ACK 1쪽 | 1.352 | Index Scan using work_request_received on work_request | shared hit=1095 |
| 10000 | summary 상태별 | 7.576 | Seq Scan on work_request | shared hit=2000 |
| 10000 | summary 미ACK 결과 | 21.986 | Seq Scan on work_request r | shared hit=22000 |
| 10000 | summary 최근 5 | 0.085 | Index Scan using work_request_received on work_request | shared hit=3 |
| 10000 | sweepStale(RECEIVED) | 6.649 | Seq Scan on work_request | shared hit=2000 |
| 10000 | 결과 수집 job_order_id 조회 | 0.113 | Index Scan using work_request_job_order_id_key on work_request | shared hit=3 |
| 10000 | 실측 WorkRequests.list("", null, 0, 50) (10회) | p50 14.4 / p95 15.8 / max 15.8 | - | - |
| 10000 | 실측 WorkRequests.list("EXT-…", null, 0, 50) (10회) | p50 15.6 / p95 20.1 / max 20.1 | - | - |
| 10000 | 실측 WorkRequests.list("", null, 0, 50, unacknowledged) (10회) | p50 31.6 / p95 38.8 / max 38.8 | - | - |
| 10000 | 실측 WorkRequests.summary() (10회) | p50 25.3 / p95 34.9 / max 34.9 | - | - |
| 100000 | list 기본 count | 31.972 | Parallel Seq Scan on work_request | shared hit=14364 read=5636 |
| 100000 | list 기본 1쪽(50) | 0.109 | Index Scan using work_request_received on work_request | shared hit=13 |
| 100000 | list 깊은 쪽(OFFSET 50000) | 31.417 | Index Scan using work_request_received on work_request | shared hit=10173 read=92 written=3 |
| 100000 | list 검색 count | 44.119 | Parallel Seq Scan on work_request | shared hit=15095 read=4905 |
| 100000 | list 검색 1쪽 | 139.684 | Index Scan using work_request_received on work_request | shared hit=8 read=20493 written=5 |
| 100000 | list 상태 IN_DOUBT count | 31.261 | Parallel Seq Scan on work_request | shared hit=15896 read=4104 |
| 100000 | list 상태 IN_DOUBT 1쪽 | 3.286 | Index Scan using work_request_received on work_request | shared hit=515 |
| 100000 | list NEEDS_CONFIRMATION 1쪽 | 1.627 | Index Scan using work_request_received on work_request | shared hit=207 |
| 100000 | list 미ACK count | 478.078 | Seq Scan on work_request | shared hit=284796 read=5204 |
| 100000 | list 미ACK 1쪽 | 3.114 | Index Scan using work_request_received on work_request | shared hit=1443 |
| 100000 | summary 상태별 | 99.059 | Seq Scan on work_request | shared hit=14822 read=5178 written=1 |
| 100000 | summary 미ACK 결과 | 484.533 | Seq Scan on work_request r | shared hit=284824 read=5176 |
| 100000 | summary 최근 5 | 0.110 | Index Scan using work_request_received on work_request | shared hit=4 |
| 100000 | sweepStale(RECEIVED) | 60.147 | Seq Scan on work_request | shared hit=14830 read=5170 |
| 100000 | 결과 수집 job_order_id 조회 | 0.231 | Index Scan using work_request_job_order_id_key on work_request | shared hit=4 |
| 100000 | 실측 WorkRequests.list("", null, 0, 50) (10회) | p50 44.2 / p95 52.0 / max 52.0 | - | - |
| 100000 | 실측 WorkRequests.list("EXT-…", null, 0, 50) (10회) | p50 135 / p95 144 / max 144 | - | - |
| 100000 | 실측 WorkRequests.list("", null, 0, 50, unacknowledged) (10회) | p50 318 / p95 357 / max 357 | - | - |
| 100000 | 실측 WorkRequests.summary() (10회) | p50 352 / p95 409 / max 409 | - | - |

100000 행 — list 미ACK count

```
Aggregate  (cost=866061.63..866061.64 rows=1 width=8) (actual time=450.533..450.536 rows=1 loops=1)
  Buffers: shared hit=284796 read=5204
  ->  Seq Scan on work_request  (cost=0.00..866000.00 rows=24652 width=0) (actual time=196.253..448.606 rows=10000 loops=1)
        Filter: ((source_system_id !~~ 'ops:%'::text) AND ((strpos(external_order_id, ''::text) > 0) OR (strpos(job_order_id, ''::text) > 0) OR (strpos(source_system_id, ''::text) > 0)) AND (SubPlan 1))
        Rows Removed by Filter: 90000
        Buffers: shared hit=284796 read=5204
        SubPlan 1
          ->  Limit  (cost=0.41..8.43 rows=1 width=5) (actual time=0.002..0.002 rows=1 loops=70000)
                Buffers: shared hit=270000
                ->  Index Scan Backward using work_result_pkey on work_result  (cost=0.41..8.43 rows=1 width=5) (actual time=0.002..0.002 rows=1 loops=70000)
                      Index Cond: (receipt_id = work_request.receipt_id)
                      Buffers: shared hit=270000
Planning:
  Buffers: shared hit=201
Planning Time: 1.973 ms
JIT:
  Functions: 12
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 1.055 ms, Inlining 77.063 ms, Optimization 64.620 ms, Emission 54.475 ms, Total 197.213 ms
Execution Time: 478.078 ms
```

100000 행 — list 검색 1쪽

```
Limit  (cost=0.42..36.82 rows=50 width=1821) (actual time=53.122..139.621 rows=1 loops=1)
  Buffers: shared hit=8 read=20493 written=5
  ->  Index Scan using work_request_received on work_request  (cost=0.42..51240.11 rows=70370 width=1821) (actual time=53.119..139.615 rows=1 loops=1)
        Filter: ((strpos(external_order_id, 'EXT-33333'::text) > 0) OR (strpos(job_order_id, 'EXT-33333'::text) > 0) OR (strpos(source_system_id, 'EXT-33333'::text) > 0))
        Rows Removed by Filter: 99999
        Buffers: shared hit=8 read=20493 written=5
Planning:
  Buffers: shared hit=181
Planning Time: 1.201 ms
Execution Time: 139.684 ms
```

100000 행 — list 깊은 쪽(OFFSET 50000)

```
Limit  (cost=36407.76..36444.16 rows=50 width=1821) (actual time=31.316..31.344 rows=50 loops=1)
  Buffers: shared hit=10173 read=92 written=3
  ->  Index Scan using work_request_received on work_request  (cost=0.42..51240.11 rows=70370 width=1821) (actual time=0.031..28.720 rows=50050 loops=1)
        Filter: ((strpos(external_order_id, ''::text) > 0) OR (strpos(job_order_id, ''::text) > 0) OR (strpos(source_system_id, ''::text) > 0))
        Buffers: shared hit=10173 read=92 written=3
Planning:
  Buffers: shared hit=186
Planning Time: 1.677 ms
Execution Time: 31.417 ms
```

100000 행 — summary 상태별

```
HashAggregate  (cost=21500.00..21500.05 rows=5 width=17) (actual time=98.791..98.795 rows=5 loops=1)
  Group Key: state
  Batches: 1  Memory Usage: 24kB
  Buffers: shared hit=14822 read=5178 written=1
  ->  Seq Scan on work_request  (cost=0.00..21000.00 rows=100000 width=9) (actual time=0.020..51.174 rows=100000 loops=1)
        Buffers: shared hit=14822 read=5178 written=1
Planning:
  Buffers: shared hit=140
Planning Time: 1.585 ms
Execution Time: 99.059 ms
```

## 재지 못한 것

- 인시던트가 쌓인 pump: 측정 중 인시던트 0. 인시던트를 내는 장애(`SKILL_EXECUTION_FAILED`)는 pick_place 태스크(랙 작업, E2 셀 검증)에만 걸리고 운영자 보류를 남겨 주기마다 판단이 필요하다.
- 실행마다 응답이 여럿인 아웃박스: InspectAsset E0 는 실행마다 응답 1건이라 아웃박스 = 실행 수.
- 운영 기계 수준의 JDBC 왕복: 이 기계는 Docker Desktop 경유 0.9~1.7 ms.
- 메모리(아웃박스·실행 보유량): mimic·운영 서비스가 같은 JVM 이라 힙 증가를 가르지 못함.
