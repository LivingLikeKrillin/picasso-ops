# 부하 측정 after — 화면 실행 목록(활성 + 최근 N, 이력은 페이지) — 2026-10-15

| 세트 | 커밋 |
|---|---|
| 바탕 | `bb386cb` (`perf/tick-and-list`, picasso `faa8ccc`) |
| after | 브랜치 `perf/ui-execution-list` (바탕 `bb386cb`), 2026-10-15 |
| 바탕 열 | after 세트에서 인자 없는 `GET /api/executions` (바탕의 운영 화면·운영 개요 5초 주기 읽기, 이 브랜치에서 코드 바뀌지 않음) |

```
./gradlew :load:loadBench --args="list"
```

| 항목 | 값 |
|---|---|
| JVM | OpenJDK 64-Bit Server VM 21.0.6 |
| OS | Windows 11 10.0 amd64 |
| 논리 CPU(JVM) | 18 |
| 최대 힙 | 4096 MiB |
| 모드 | full |
| Postgres | 16.15 |
| JDBC 왕복 SELECT 1 p50 / p95 / max ms | 0.93 / 1.25 / 1.50 |
| 기체 | 50 |
| 검사점 실행 남은 실행 / 정착 | 0 / 2000, 0 / 10000 |
| 회수 | 읽기마다 10 |

## 화면 주기 읽기 (운영 서비스 `/api`)

| 실행 E | 화면 | 바탕 읽기 | 바탕 p50 ms | 바탕 bytes | after 읽기 | after p50 ms | after bytes | after 실행 줄 |
|---|---|---|---|---|---|---|---|---|
| 2000 | 운영 화면 (5초) | `/api/executions` | 65.0 | 1430866 | `/api/executions?recent=20` | 12.2 | 17456 | 20 |
| 2000 | 운영 개요 (5초) | `/api/executions` | 65.0 | 1430866 | `/api/executions/summary` | 5.39 | 296 | 0 |
| 10000 | 운영 화면 (5초) | `/api/executions` | 196 | 7166869 | `/api/executions?recent=20` | 14.2 | 17460 | 20 |
| 10000 | 운영 개요 (5초) | `/api/executions` | 196 | 7166869 | `/api/executions/summary` | 10.4 | 296 | 0 |

## 읽기 원문 (after 세트)

| 실행 E | 읽기 | p50 ms | p95 ms | max ms | 응답 bytes | 응답 실행 줄 |
|---|---|---|---|---|---|---|
| 2000 | `/api/executions` | 65.0 | 92.6 | 92.6 | 1430866 | 2000 |
| 2000 | `/api/executions?recent=20` | 12.2 | 13.8 | 13.8 | 17456 | 20 |
| 2000 | `/api/executions/summary` | 5.39 | 18.8 | 18.8 | 296 | 0 |
| 2000 | `/api/executions?before=…&limit=20` | 9.10 | 19.9 | 19.9 | 17269 | 20 |
| 10000 | `/api/executions` | 196 | 247 | 247 | 7166869 | 10000 |
| 10000 | `/api/executions?recent=20` | 14.2 | 15.7 | 15.7 | 17460 | 20 |
| 10000 | `/api/executions/summary` | 10.4 | 12.8 | 12.8 | 296 | 0 |
| 10000 | `/api/executions?before=…&limit=20` | 6.00 | 7.23 | 7.23 | 17269 | 20 |

## 실행 호스트 읽기 (같은 회차)

| 실행 E | 읽기 | p50 ms | p95 ms | max ms | 응답 bytes | 응답 실행 수 |
|---|---|---|---|---|---|---|
| 2000 | `/host/executions` | 23.2 | 59.8 | 59.8 | 1430866 | 2000 |
| 2000 | `/host/executions?jobOrderId=` 1 | 2.84 | 5.62 | 5.62 | 904 | 1 |
| 2000 | `/host/executions?jobOrderId=` 50 | 4.68 | 6.89 | 6.89 | 35959 | 50 |
| 10000 | `/host/executions` | 88.4 | 117 | 117 | 7166869 | 10000 |
| 10000 | `/host/executions?jobOrderId=` 1 | 6.30 | 6.97 | 6.97 | 904 | 1 |
| 10000 | `/host/executions?jobOrderId=` 50 | 5.31 | 7.33 | 7.33 | 36028 | 50 |

## 스테이션 tick (같은 회차)

| 스테이션 | 상태 | tick p50 ms | p95 ms | max ms | DB 문장/tick | executions GET p50 ms |
|---|---|---|---|---|---|---|
| 50 | active (호스트 실행 2000) | 64.6 | 73.4 | 73.4 | 52.0 | 4.14 |
| 50 | active (호스트 실행 10000) | 50.8 | 59.1 | 59.1 | 52.0 | 5.16 |
