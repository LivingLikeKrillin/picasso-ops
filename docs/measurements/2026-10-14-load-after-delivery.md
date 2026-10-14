# 부하 측정 after — 핫스팟 2·5 — 2026-10-14

기준선: `2026-10-14-load-baseline.md`(커밋 `dd48061`). after: 브랜치 `perf/delivery-and-requests`(바탕 `76a49b5`), 같은 기계. 하네스 `load/`(이 브랜치에서 전달 행 시드·집기 문장·따라잡기 측정, 목록 질의 문자열을 바뀐 코드에 맞춤).

```
./gradlew :load:loadBench --args="delivery explain"
```

after 는 두 번 돌렸다. 표의 after 는 둘째 실행, 괄호는 첫째 실행.

| 세트 | 기준선 JDBC 왕복 SELECT 1 p50 / p95 / max ms | after 둘째 | after 첫째 |
|---|---|---|---|
| 결과 전달 | 1.48 / 1.86 / 5.00 | 0.96 / 1.27 / 1.58 | 3.15 / 9.43 / 23.0 |
| 목록·검색 | 1.59 / 1.99 / 2.46 | 1.27 / 1.76 / 2.34 | 1.64 / 2.14 / 2.80 |

## 핫스팟 2 — 결과 전달 (`WorkResultDelivery.deliver`)

| 결과 수 | 정상 p50 ms 기준선 | 정상 p50 ms after | 정상 p95 ms 기준선 | 정상 p95 ms after | 정상 max ms 기준선 | 정상 max ms after | 정상 DB 문장/회차 기준선 | after | 회차마다 결과 전체를 훑는 문장 DB ms/회차 기준선 | after | 첫 회차 ms 기준선 | after | 첫 회차 전송 수 기준선 | after |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 6.58 | 1.39 (7.06) | 7.98 | 1.62 (40.8) | 8.43 | 2.12 (53.4) | 2.00 | 1.00 | 2.43 | 0 | 172 | 157 (209) | 10 | 10 |
| 1000 | 24.6 | 1.69 (3.16) | 33.8 | 2.22 (5.25) | 37.6 | 2.66 (5.57) | 2.05 | 1.00 | 20.8 | 0 | 162 | 98.1 (160) | 10 | 10 |
| 10000 | 149 | 1.42 (3.34) | 244 | 1.95 (7.24) | 267 | 2.81 (9.64) | 2.15 | 1.00 | 160 | 0 | 479 | 86.8 (148) | 10 | 10 |
| 100000 | 1768 | 1.10 (1.74) | 2961 | 1.41 (2.69) | 3066 | 1.75 (2.80) | 4.00 | 1.05 | 1971 | 0 | 6351 | 79.0 (112) | 10 | 10 |

after 정상 회차의 문장은 집기(`WITH candidate AS … FOR UPDATE SKIP LOCKED`) 하나: DB ms/회차 0.04~0.05 (0.06~0.13), blocks/회차 1~4.

기동 뒤 한 번의 따라잡기(`catchUp`, after 에만 있음)

| 결과 수 | 빠진 행 없음 p50 ms (3회) | 빠진 행 없음 max ms | 전달 행 없는 예전 자료 ms (1회) | 그때 넣은 행 |
|---|---|---|---|---|
| 100 | 2.29 (5.84) | 3.59 (9.18) | 8.51 (33.4) | 100 |
| 1000 | 2.91 (7.00) | 4.26 (8.68) | 66.3 (144) | 1000 |
| 10000 | 7.68 (17.3) | 10.3 (17.5) | 501 (1287) | 10000 |
| 100000 | 55.1 (92.6) | 67.6 (115) | 3988 (8037) | 100000 |

EXPLAIN (ANALYZE, BUFFERS) 결과 100000 — 기준선 회차마다 `INSERT…SELECT` vs after 기동 뒤 따라잡기(빠진 행 없음, 첫째 실행)

| 항목 | 기준선 | after |
|---|---|---|
| Execution Time ms | 1989.787 | 124.050 |
| Conflicting Tuples | 100000 | 0 |
| Buffers | shared hit=401349 read=13550 | shared hit=3528 |
| 계획 | Hash Join → Insert(충돌 검사 100000) | Hash Anti Join(work_result_pkey Index Only Scan × work_result_delivery Seq Scan) → Insert 0 |
| 실행 빈도 | 2초마다 | 프로세스 기동 뒤 한 번 |

## 핫스팟 5 — `work_request` 목록·검색 (`WorkRequests.list`·`summary`)

인덱스 after: 기준선 넷 + V20 `work_request_state_received(state,received_at DESC,receipt_id)`, `work_result_unacknowledged(receipt_id,version) WHERE acknowledged_at IS NULL`.

| 행 수 | 질의 | 기준선 Execution Time ms | after ms | 기준선 첫 스캔 노드 | after 첫 스캔 노드 |
|---|---|---|---|---|---|
| 10000 | list 기본 count | 4.762 | 2.161 (3.545) | Seq Scan on work_request | Index Only Scan using work_request_job_order_id_key |
| 10000 | list 상태 IN_DOUBT count | 4.070 | 0.237 (0.390) | Seq Scan on work_request | Index Only Scan using work_request_state_received |
| 10000 | list 상태 IN_DOUBT 1쪽 | 1.599 | 0.230 (0.254) | Index Scan using work_request_received | Index Scan using work_request_state_received |
| 10000 | list 미ACK count | 24.476 | 7.281 (11.786) | Seq Scan on work_request | Seq Scan on work_request (Hash Semi Join, work_result_unacknowledged) |
| 10000 | summary 상태별 | 7.576 | 3.028 (3.669) | Seq Scan on work_request | Index Only Scan using work_request_state_received |
| 10000 | summary 미ACK 결과 | 21.986 | 8.599 (8.153) | Seq Scan on work_request r | Seq Scan on work_request r (Hash Semi Join, work_result_unacknowledged) |
| 10000 | sweepStale(RECEIVED) | 6.649 | 0.808 (1.024) | Seq Scan on work_request | Index Scan using work_request_state_received |
| 100000 | list 기본 count | 31.972 | 16.945 (41.762) | Parallel Seq Scan on work_request | Index Only Scan using work_request_received |
| 100000 | list 기본 1쪽(50) | 0.109 | 0.095 (0.297) | Index Scan using work_request_received | 같음 |
| 100000 | list 깊은 쪽(OFFSET 50000) | 31.417 | 20.402 (51.976) | Index Scan using work_request_received | 같음 |
| 100000 | list 검색 count | 44.119 | 32.338 (99.184) | Parallel Seq Scan on work_request | 같음 |
| 100000 | list 검색 1쪽 | 139.684 | 115.962 (182.002) | Index Scan using work_request_received | 같음 |
| 100000 | list 상태 IN_DOUBT count | 31.261 | 0.718 (0.923) | Parallel Seq Scan on work_request | Index Only Scan using work_request_state_received |
| 100000 | list 상태 IN_DOUBT 1쪽 | 3.286 | 0.305 (0.148) | Index Scan using work_request_received | Index Scan using work_request_state_received |
| 100000 | list NEEDS_CONFIRMATION 1쪽 | 1.627 | 0.654 (0.946) | Index Scan using work_request_received | 같음 |
| 100000 | list 미ACK count | 478.078 | 71.675 (139.224) | Seq Scan on work_request (SubPlan × 70000, JIT 197 ms) | Seq Scan on work_request (Hash Semi Join, work_result_unacknowledged, JIT 없음) |
| 100000 | list 미ACK 1쪽 | 3.114 | 1.315 (2.027) | Index Scan using work_request_received | 같음 |
| 100000 | summary 상태별 | 99.059 | 25.203 (32.546) | Seq Scan on work_request | Index Only Scan using work_request_state_received |
| 100000 | summary 미ACK 결과 | 484.533 | 81.139 (123.812) | Seq Scan on work_request r (LATERAL × 70000) | Seq Scan on work_request r (Hash Semi Join, work_result_unacknowledged) |
| 100000 | summary 최근 5 | 0.110 | 0.078 (0.122) | Index Scan using work_request_received | 같음 |
| 100000 | sweepStale(RECEIVED) | 60.147 | 6.510 (7.861) | Seq Scan on work_request | Index Scan using work_request_state_received |
| 100000 | 결과 수집 job_order_id 조회 | 0.231 | 0.119 (0.162) | Index Scan using work_request_job_order_id_key | 같음 |

운영 코드 메서드 실측(10회)

| 행 수 | 호출 | 기준선 p50 / p95 / max ms | after p50 / p95 / max ms |
|---|---|---|---|
| 10000 | `list("", null, 0, 50)` | 14.4 / 15.8 / 15.8 | 9.66 / 11.5 / 11.5 (10.1 / 14.3 / 14.3) |
| 10000 | `list("EXT-…", null, 0, 50)` | 15.6 / 20.1 / 20.1 | 13.5 / 15.1 / 15.1 (16.7 / 17.7 / 17.7) |
| 10000 | `list("", null, 0, 50, unacknowledged)` | 31.6 / 38.8 / 38.8 | 11.7 / 14.0 / 14.0 (15.9 / 18.6 / 18.6) |
| 10000 | `summary()` | 25.3 / 34.9 / 34.9 | 11.7 / 16.4 / 16.4 (13.3 / 14.4 / 14.4) |
| 100000 | `list("", null, 0, 50)` | 44.2 / 52.0 / 52.0 | 16.0 / 19.1 / 19.1 (27.0 / 56.3 / 56.3) |
| 100000 | `list("EXT-…", null, 0, 50)` | 135 / 144 / 144 | 133 / 143 / 143 (198 / 238 / 238) |
| 100000 | `list("", null, 0, 50, unacknowledged)` | 318 / 357 / 357 | 58.4 / 61.4 / 61.4 (92.7 / 117 / 117) |
| 100000 | `summary()` | 352 / 409 / 409 | 78.2 / 98.7 / 98.7 (134 / 164 / 164) |

after 100000 행 — list 미ACK count (둘째 실행)

```
Aggregate  (cost=24080.76..24080.77 rows=1 width=8) (actual time=71.213..71.220 rows=1 loops=1)
  Buffers: shared hit=15906 read=5149
  ->  Hash Semi Join  (cost=2582.13..24068.99 rows=4709 width=0) (actual time=21.757..70.337 rows=10000 loops=1)
        Hash Cond: (work_request.receipt_id = u.receipt_id)
        Buffers: shared hit=15906 read=5149
        ->  Seq Scan on work_request  (cost=0.00..21250.00 rows=70277 width=16) (actual time=0.024..36.483 rows=70000 loops=1)
              Filter: (source_system_id !~~ 'ops:%'::text)
              Rows Removed by Filter: 30000
              Buffers: shared hit=14851 read=5149
        ->  Hash  (cost=2498.37..2498.37 rows=6701 width=16) (actual time=21.675..21.680 rows=10000 loops=1)
              Buckets: 16384 (originally 8192)  Batches: 1 (originally 1)  Memory Usage: 597kB
              Buffers: shared hit=1055
              ->  Hash Right Anti Join  (cost=564.72..2498.37 rows=6701 width=16) (actual time=19.036..19.957 rows=10000 loops=1)
                    Hash Cond: (n.receipt_id = u.receipt_id)
                    Join Filter: (n.version > u.version)
                    Rows Removed by Join Filter: 10000
                    Buffers: shared hit=1055
                    ->  Seq Scan on work_result n  (cost=0.00..1583.00 rows=60000 width=20) (actual time=0.007..5.489 rows=60000 loops=1)
                          Buffers: shared hit=983
                    ->  Hash  (cost=439.06..439.06 rows=10052 width=20) (actual time=2.838..2.840 rows=10000 loops=1)
                          Buckets: 16384  Batches: 1  Memory Usage: 636kB
                          Buffers: shared hit=72
                          ->  Index Only Scan using work_result_unacknowledged on work_result u  (cost=0.29..439.06 rows=10052 width=20) (actual time=0.021..1.207 rows=10000 loops=1)
                                Heap Fetches: 0
                                Buffers: shared hit=72
Planning:
  Buffers: shared hit=475
Planning Time: 3.797 ms
Execution Time: 71.675 ms
```
