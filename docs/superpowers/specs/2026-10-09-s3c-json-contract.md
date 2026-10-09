# S3c JSON 계약: 현장 설정 시간값, 현재 버전 뷰, 실행 호스트 적용 상태와 인시던트 REST

S3c 설계 스펙(`docs/superpowers/specs/2026-10-09-s3c-site-timings-design.md`)을 구현한 운영 서비스와 실행 호스트가 실제로 내고 받는 모양이다. 화면과 통합 시험은 이것에 맞춘다. 코드 주석의 «S3c JSON 계약 §N» 은 이 문서의 절을 가리킨다. picasso 쪽 API 는 P5(picasso 머지 커밋 `195c1ee`, `SiteTimings`, `SiteTimingsSource`, `Intent` 의 새 칸. `Intent` 는 설정 버전 없이 `inDoubtGrace`·`stallWindow` 를 실으면 생성에서 거부한다)이며 이 문서는 그 위의 picasso-ops 모양만 적는다.

**S2 스펙 §6 과 앞 계약에서 이 문서가 대체하는 곳**

| 앞 자리 | 대체하는 이 문서의 절 | 바뀐 것 |
|---|---|---|
| S2 스펙 §6.1 `GET /api/site-settings` | §3 | 버전 행에 시간값 칸 넷, `range` 에 범위 여덟 칸, 새 칸 `hostTimings` |
| S2 스펙 §6.2 `PUT /api/site-settings` | §4 | `connectionThresholdSeconds` 가 필수에서 선택으로. 시간값 칸 넷(선택). 정수 아닌 값은 400 |
| S2 의 조작 기록 본문(`op`·`baseVersion`·`connectionThresholdSeconds`) | §6 | 값 다섯을 기준 버전으로 채워 늘 다섯 칸 |
| S3a JSON 계약 §2·§4 판정·제출 | §8.3 | 모양은 그대로. 미적용 동안 이유 문장 하나가 더해지고 제출은 늘 UNASSIGNED |

그 밖의 S3a·S3b 계약 절은 그대로다.

공통 규칙

- 모든 본문은 UTF-8 JSON 이다. 시각은 ISO-8601 UTC 문자열(`Instant.toString()`)이고 소수 초 자릿수는 고정이 아니다(`Instant.parse` 로 읽을 것). `null` 칸은 생략하지 않고 `null` 로 나온다.
- **시간값은 어디서나 초 단위 정수다.** picasso `Intent` 의 ISO-8601 문자열(60초면 `PT1M`)은 호스트 REST 에서 초 정수로 되돌려 낸다. 화면과 통합 시험은 ISO-8601 문자열을 보지 않는다.
- 시간값 칸 이름은 운영 서비스 본문, 호스트 적용 상태, 호스트 인시던트에서 같다: `evidenceBeforeSeconds`, `evidenceAfterSeconds`, `inDoubtGraceSeconds`, `stallWindowSeconds`. DB 칸 이름은 snake_case 다(§1).
- 호스트 시각(`appliedAt`, `lastReadAt`, 인시던트 `at`)은 호스트 시계다. 통합 시험과 호스트 시험에서는 현장의 가상 시계이므로 실제 시각과 다르다.

---

## 1. 운영 서비스 표와 현재 버전 뷰(DB 계약)

### 1.1 `ops.site_settings` 에 더한 칸(마이그레이션 `V4__site_timings.sql`)

| 칸 | 형 | 제약 | 기존 행(버전 1) 값 |
|---|---|---|---|
| `evidence_before_seconds` | `integer` | `NOT NULL`, `> 0` | 30 |
| `evidence_after_seconds` | `integer` | `NOT NULL`, `> 0` | 15 |
| `in_doubt_grace_seconds` | `integer` | `NOT NULL`, `> 0` | 60 |
| `stall_window_seconds` | `integer` | `NOT NULL`, `> 0` | 300 |

- `ADD COLUMN ... NOT NULL DEFAULT n` 으로 기존 행을 채운 뒤 `ALTER COLUMN ... DROP DEFAULT` 한다. `UPDATE` 는 없다(덧붙이기 전용 트리거). 그래서 새 칸을 빠뜨린 `INSERT` 는 not-null 위반으로 실패한다. 시험이 손으로 행을 넣을 때는 칸 아홉을 다 적을 것.
- DB 제약은 `> 0` 뿐이다. 허용 범위(§5)는 DB 가 보지 않는다.
- 마이그레이션 직후 버전 1 행은 `(1, 90, 30, 15, 60, 300)` 이다(연결 기준 시간 90초는 S2 그대로).

### 1.2 뷰 `ops.site_timings_current`

실행 호스트는 이 뷰만 읽는다. 표를 읽지 않는다. 칸 이름과 형, 순서가 두 프로세스의 계약이다.

| 순서 | 칸 | `information_schema.columns.data_type` |
|---|---|---|
| 1 | `version` | `bigint` |
| 2 | `evidence_before_seconds` | `integer` |
| 3 | `evidence_after_seconds` | `integer` |
| 4 | `in_doubt_grace_seconds` | `integer` |
| 5 | `stall_window_seconds` | `integer` |

- 정의: `SELECT version, evidence_before_seconds, evidence_after_seconds, in_doubt_grace_seconds, stall_window_seconds FROM site_settings ORDER BY version DESC LIMIT 1`. 늘 한 행(가장 큰 버전)이다.
- 호스트 main 의 상수: `dev.picasso.ops.host.timings.SiteTimingsView`
  - `SiteTimingsView.NAME = "ops.site_timings_current"`
  - `SiteTimingsView.COLUMNS: List<SiteTimingsView.Column>`, `Column(name: String, type: String)`. 값은 위 표의 다섯 행 그대로(순서 포함, `type` 은 `data_type` 문자열)
  - `SiteTimingsView.SELECT = "SELECT version, evidence_before_seconds, evidence_after_seconds, in_doubt_grace_seconds, stall_window_seconds FROM ops.site_timings_current"`
- 통합 시험의 대조(스펙 §10 «대역 뷰와 실제 뷰의 칸이 같음»): `SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'ops' AND table_name = 'site_timings_current' ORDER BY ordinal_position` 의 결과가 `SiteTimingsView.COLUMNS.map { it.name to it.type }` 와 같아야 한다. e2e 는 `mission-host` 에 `testImplementation` 으로 의존하므로 `SiteTimingsView` 를 컴파일 시점에 볼 수 있다.
- 호스트 시험 세트(`HostBench`)의 대역 뷰: 띄울 때마다 `DROP SCHEMA IF EXISTS ops CASCADE` 뒤 `CREATE SCHEMA IF NOT EXISTS ops` 와 `CREATE VIEW ops.site_timings_current (version, evidence_before_seconds, ...) AS VALUES (1::bigint, 30::integer, 15::integer, 60::integer, 300::integer)` 를 상수에서 만든다. `HostBench(timings = null)` 은 뷰를 만들지 않는다.

---

## 2. 통합 시험 스택의 기동 순서

`E2eStack.start()` 는 ops·mission 스키마를 지운 뒤, **실행 호스트를 띄우기 전에** ops 스키마를 올린다.

```kotlin
OpsSchema.migrate(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)  // 올린 마이그레이션 수(Int)
```

- `dev.picasso.ops.service.store.OpsSchema.migrate(url: String, user: String, password: String): Int` 는 S3c 에서 더한 main 함수다. e2e 는 Flyway 형을 컴파일 시점에 보지 않으므로 이것을 부른다.
- 그래서 호스트는 기동 안의 첫 동기 읽기에서 버전 1 을 적용한 채로 뜬다(통합 시험 1단계 «기동 직후 호스트가 버전 1 을 적용»). 운영 서비스가 뒤에 뜨며 같은 마이그레이션을 다시 부르지만 올릴 것이 없다.
- 통합 시험 스택(`E2eStack`)은 이 순서를 지킨다. 기존 e2e 시험은 이 줄 없이도 운영 서비스가 뜬 뒤 1초 안에 호스트가 적용하므로 대개 통과하겠지만, 첫 작업 지시가 그 1초 안에 오면 UNASSIGNED 가 되는 경합이 있다.
- ops 마이그레이션은 이제 넷(`1`~`4`)이다. `SkeletonTest` 의 `ops.flyway_schema_history` 대조를 `listOf("1", "2", "3", "4")` 로 고쳤다. 그 밖의 기존 e2e 는 S3c 변경 위에서 그대로 통과한다.
- 수동 기동(README, Playwright)은 호스트가 운영 서비스보다 먼저 뜬다. 그 사이 호스트는 미적용이고(§8), 운영 서비스가 마이그레이션을 마친 뒤 1초 안에 적용한다.

---

## 3. `GET /api/site-settings`

모드 헤더와 관계없이 읽는다. 상태는 늘 200 이다(호스트가 닿지 않아도 200).

```json
{
  "current": {
    "version": 2,
    "connectionThresholdSeconds": 90,
    "evidenceBeforeSeconds": 30,
    "evidenceAfterSeconds": 15,
    "inDoubtGraceSeconds": 60,
    "stallWindowSeconds": 600,
    "mode": "ENGINEER",
    "user": "kim",
    "reason": "정체 표시 늦춤",
    "recordedAt": "2026-10-09T03:12:45.123456Z"
  },
  "range": {
    "minConnectionThresholdSeconds": 60,
    "maxConnectionThresholdSeconds": 3600,
    "minEvidenceBeforeSeconds": 5,
    "maxEvidenceBeforeSeconds": 120,
    "minEvidenceAfterSeconds": 5,
    "maxEvidenceAfterSeconds": 120,
    "minInDoubtGraceSeconds": 10,
    "maxInDoubtGraceSeconds": 600,
    "minStallWindowSeconds": 30,
    "maxStallWindowSeconds": 3600
  },
  "history": [
    { "version": 2, "connectionThresholdSeconds": 90, "evidenceBeforeSeconds": 30, "evidenceAfterSeconds": 15, "inDoubtGraceSeconds": 60, "stallWindowSeconds": 600, "mode": "ENGINEER", "user": "kim", "reason": "정체 표시 늦춤", "recordedAt": "2026-10-09T03:12:45.123456Z" },
    { "version": 1, "connectionThresholdSeconds": 90, "evidenceBeforeSeconds": 30, "evidenceAfterSeconds": 15, "inDoubtGraceSeconds": 60, "stallWindowSeconds": 300, "mode": "ENGINEER", "user": "system", "reason": "S1 설정값 이전", "recordedAt": "2026-10-09T03:10:00.000001Z" }
  ],
  "hostTimings": {
    "applied": { "version": 2, "evidenceBeforeSeconds": 30, "evidenceAfterSeconds": 15, "inDoubtGraceSeconds": 60, "stallWindowSeconds": 600 },
    "appliedAt": "2026-10-09T03:12:46.002Z",
    "lastReadAt": "2026-10-09T03:12:51.004Z",
    "readError": null,
    "rejected": null
  }
}
```

| 칸 | 뜻 |
|---|---|
| `current` | 가장 큰 버전. `history[0]` 과 같다 |
| 버전 행 | S2 의 여섯 칸(`version`, `connectionThresholdSeconds`, `mode`, `user`, `reason`, `recordedAt`)에 시간값 넷을 더했다. 칸 순서는 위와 같다. `mode` 는 늘 `"ENGINEER"`(대문자, enum 이름) |
| `history` | 최신부터, 많아야 200 행 |
| `range` | 허용 범위(초, 양 끝 포함). S2 의 두 칸에 여덟 칸을 더했다. 평평한 한 객체다. 값은 §5 |
| `hostTimings` | 운영 서비스가 실행 호스트 `GET /host/site-timings`(§7)를 대신 읽은 **본문 그대로**. 호스트가 닿지 않거나(연결 실패, 시간 초과) 200 이 아니거나 본문이 객체가 아니면 `null` 이다 |

화면의 «실행 호스트 반영» 판정:

| `hostTimings` | 표시 |
|---|---|
| `null` | 모름(호스트가 닿지 않음) |
| 객체이고 `applied` 가 `null` | 미적용(호스트가 아직 현장 설정을 읽지 못함). `readError` 가 있으면 그 까닭 |
| 객체이고 `applied.version` 이 N | 실행 호스트 반영: 버전 N |
| `rejected` 가 객체 | 그 버전을 적용하지 않음(범위 밖)과 `reasons`. `applied` 와 같이 나올 수 있다 |

- 읽기 주기가 1초라 `current.version` 과 `hostTimings.applied.version` 은 잠깐 다를 수 있다. 화면은 둘을 따로 보인다.
- 운영 서비스의 호스트 읽기 제한은 연결 2초, 요청 5초(S3a 의 `HostClient` 그대로)다. 루프백에서 호스트가 꺼져 있으면 연결 거부로 곧바로 `null` 이다.

---

## 4. `PUT /api/site-settings`

헤더 `X-Ops-Mode: engineer`, `X-Ops-User: <사용자>`, `Content-Type: application/json`(다른 형은 415, 스프링).

```json
{
  "baseVersion": 1,
  "connectionThresholdSeconds": 90,
  "evidenceBeforeSeconds": 30,
  "evidenceAfterSeconds": 15,
  "inDoubtGraceSeconds": 60,
  "stallWindowSeconds": 600,
  "reason": "정체 표시 늦춤"
}
```

| 칸 | 필수 | 형 | 빠지면 |
|---|---|---|---|
| `baseVersion` | 예 | 정수 | 400 `SETTINGS_BAD_REQUEST` |
| `connectionThresholdSeconds` | 아니오(S2 에서는 필수) | 정수 | 기준 버전의 값 |
| `evidenceBeforeSeconds` | 아니오 | 정수 | 기준 버전의 값 |
| `evidenceAfterSeconds` | 아니오 | 정수 | 기준 버전의 값 |
| `inDoubtGraceSeconds` | 아니오 | 정수 | 기준 버전의 값 |
| `stallWindowSeconds` | 아니오 | 정수 | 기준 버전의 값 |
| `reason` | 예 | 문자열(앞뒤 공백을 지운 뒤 비지 않음) | 400 `REASON_REQUIRED` |

- **빠짐**: 칸이 없거나 값이 `null`. 빠진 칸은 기준 버전(= 그때의 현재 버전) 행의 값으로 채운다.
- **값 칸 다섯이 다 빠지면** 400 `SETTINGS_BAD_REQUEST`.
- 값 칸이 있는데 정수가 아니면(문자열 `"600"`, 소수 `30.5`, 참거짓, 객체) 빠진 것으로 보지 않고 400 `SETTINGS_BAD_REQUEST` 다. `baseVersion` 도 정수가 아니면 400 `SETTINGS_BAD_REQUEST` 다.
- 화면은 폼의 값 다섯을 늘 다 보낸다(스펙 §6.2).
- 같은 값으로 바꿔도 새 버전이 생긴다.

### 4.1 검사 순서

관문(모드·행위자) → 본문 → 범위 → 사유 → 기준 버전. 앞에서 걸리면 뒤는 보지 않는다. 기준 버전 충돌 말고는 모두 사전 거부라 조작 기록에 남지 않는다.

| 순서 | 상황 | 상태 | 본문 |
|---|---|---|---|
| 1 | 행위자 헤더 없음·틀림 | 400 | `{"error":"ACTOR_REQUIRED","detail":"X-Ops-Mode·X-Ops-User 헤더가 없거나 틀리다"}` |
| 1 | 운영자 모드 | 403 | `{"error":"MODE_NOT_ALLOWED","detail":"이 조작은 engineer 모드에서 한다"}` |
| 2 | 본문이 JSON 객체가 아님, `baseVersion` 이 없거나 정수 아님 | 400 | `{"error":"SETTINGS_BAD_REQUEST","detail":"baseVersion 이 정수로 있어야 한다"}` |
| 2 | 값 칸이 정수 아님(칸 순서상 첫 칸) | 400 | `{"error":"SETTINGS_BAD_REQUEST","detail":"stallWindowSeconds 가 정수가 아니다"}` |
| 2 | 값 칸 다섯이 다 빠짐 | 400 | `{"error":"SETTINGS_BAD_REQUEST","detail":"값 칸(connectionThresholdSeconds, evidenceBeforeSeconds, evidenceAfterSeconds, inDoubtGraceSeconds, stallWindowSeconds) 중 하나 이상이 정수로 있어야 한다"}` |
| 3 | 실린 값이 범위 밖(하나 이상) | 400 | `{"error":"SETTING_OUT_OF_RANGE","detail":"<문장>[; <문장>...]"}` |
| 4 | 사유 없음·빈칸·공백뿐 | 400 | `{"error":"REASON_REQUIRED","detail":"변경 사유가 없다"}` |
| 5 | 기준 버전이 현재 버전이 아님 | 200 | `OperationOutcome` 의 `REJECTED`(§4.3) |
| 성공 | | 200 | `OperationOutcome` 의 `SUCCEEDED`(§4.2) |

범위 밖 문장(실린 칸만, 칸 순서 `connectionThresholdSeconds` → `evidenceBeforeSeconds` → `evidenceAfterSeconds` → `inDoubtGraceSeconds` → `stallWindowSeconds`, 여럿이면 `"; "` 로 잇는다):

```
<칸 이름> <값>초는 범위 밖이다. <하한>~<상한>초여야 한다
```

예: `inDoubtGraceSeconds 9초는 범위 밖이다. 10~600초여야 한다`, `evidenceBeforeSeconds 121초는 범위 밖이다. 5~120초여야 한다; stallWindowSeconds 29초는 범위 밖이다. 30~3600초여야 한다`. Int 를 넘는 정수(`9999999999`)도 범위 밖이다. 연결 기준 시간의 S2 문장(`연결 기준 시간 N초는 ...`)은 이 꼴로 바뀌었다.

범위 안의 칸과 범위 밖 칸이 섞이면 아무것도 넣지 않는다.

### 4.2 성공 `200`

```json
{"requestId":"2b9c...","result":"SUCCEEDED","confirmation":null,"rejection":null,"unauthorized":false,"registryStatus":null}
```

새 버전은 `baseVersion + 1` 이고, 새 행의 값은 실린 칸과 기준 버전 값으로 채운 칸의 합이다.

### 4.3 버전 충돌 `200`

```json
{
  "requestId": "7f1e...",
  "result": "REJECTED",
  "confirmation": null,
  "rejection": {
    "kind": "SETTINGS_VERSION_CONFLICT",
    "observed": "현재 버전 3",
    "expected": "기준 버전 1",
    "checkedAt": "2026-10-09T03:14:00.000Z",
    "owner": "ENGINEER",
    "inScreen": true,
    "action": "현재 값을 다시 읽고 다시",
    "target": null,
    "basisVersion": null
  },
  "unauthorized": false,
  "registryStatus": null
}
```

S2 그대로다. 아직 없는 버전을 기준으로 보내도(`baseVersion` 이 현재보다 큼) 같은 충돌이다.

---

## 5. 허용 범위(초, 양 끝 포함)

| 본문 칸 | 하한 | 상한 | 주인 | 운영 서비스 상수(`SiteSettingsRange`) | picasso 상수(`dev.picasso.middleware.SiteTimings`) |
|---|---|---|---|---|---|
| `connectionThresholdSeconds` | 60 | 3600 | 운영 서비스 | `MIN_/MAX_CONNECTION_THRESHOLD_SECONDS` | 없음 |
| `evidenceBeforeSeconds` | 5 | 120 | picasso | `MIN_/MAX_EVIDENCE_BEFORE_SECONDS` | `EVIDENCE_WINDOW_BEFORE_SECONDS` |
| `evidenceAfterSeconds` | 5 | 120 | picasso | `MIN_/MAX_EVIDENCE_AFTER_SECONDS` | `EVIDENCE_WINDOW_AFTER_SECONDS` |
| `inDoubtGraceSeconds` | 10 | 600 | picasso | `MIN_/MAX_IN_DOUBT_GRACE_SECONDS` | `IN_DOUBT_GRACE_SECONDS` |
| `stallWindowSeconds` | 30 | 3600 | picasso | `MIN_/MAX_STALL_WINDOW_SECONDS` | `STALL_WINDOW_SECONDS` |

- 운영 서비스 상수는 `dev.picasso.ops.service.settings.SiteSettingsRange` 의 `const val Int` 이고, `SiteSettingsRange.FIELDS: List<Pair<String, IntRange>>` 가 본문 칸 이름과 범위를 위 표의 순서로 든다. 운영 서비스 main 은 picasso 를 쓰지 못하므로(`checkNoPicassoOnMain`) 시간값 넷은 사본이다.
- picasso 상수는 `LongRange` `val` 이다. Kotlin 에서 `SiteTimings.STALL_WINDOW_SECONDS.first`·`.last`.
- 통합 시험 5단계의 대조: 시간값 넷마다 `SiteSettingsRange.FIELDS` 의 `IntRange` 의 `first`·`last` 가 picasso `LongRange` 의 `first`·`last` 와 같다. 또는 `GET /api/site-settings` 의 `range` 여덟 칸과 picasso 상수를 맞댄다. **e2e 의 컴파일 클래스패스에는 picasso 가 없다**(mission-host 는 picasso 를 `implementation` 으로 들고, registry testFixtures 는 picasso 를 `api` 로 내지 않는다. 런타임에만 있다). 그래서 `e2e/build.gradle.kts` 에 `testImplementation("dev.picasso:picasso")` 를 더했다(포함 빌드 치환이 이미 있다).
- picasso 기본값(30, 15, 60, 300)과 S2 의 90 은 모두 범위 안이다.

---

## 6. 조작 기록(`GET /api/operations` 의 행)

현장 설정 변경 행의 `target` 은 `"site-settings"`, `mode` 는 `"ENGINEER"`, `targetResponse` 는 `null`, `reason` 은 요청의 사유(앞뒤 공백 지움)다. `request` 칸은 **JSON 문자열**이다(S2 그대로). 그 안의 모양:

성공(`result: "SUCCEEDED"`): 값 다섯은 기준 버전으로 채운 값, 곧 새 버전 행의 값이다.

```json
{"op":"CHANGE_SITE_SETTINGS","baseVersion":1,"connectionThresholdSeconds":90,"evidenceBeforeSeconds":30,"evidenceAfterSeconds":15,"inDoubtGraceSeconds":60,"stallWindowSeconds":600}
```

버전 충돌(`result: "REJECTED"`): 기준 버전 행이 있으면(지난 버전) 그 행의 값으로 채운다. 없는 버전(현재보다 큰 기준)이면 실린 칸만 값이 있고 빠진 칸은 `null` 이다.

```json
{"op":"CHANGE_SITE_SETTINGS","baseVersion":9,"connectionThresholdSeconds":null,"evidenceBeforeSeconds":null,"evidenceAfterSeconds":20,"inDoubtGraceSeconds":null,"stallWindowSeconds":null}
```

- 칸은 늘 일곱이고 순서는 위와 같다(JSON 비교는 순서와 무관하게 할 것).
- 사전 거부(§4.1 의 400·403)는 행을 남기지 않는다.
- 화면 이력 표시(스펙 §8)는 버전 행(§3)에서 하고, 조작 기록 행은 S2 그대로 `result` 등을 쓴다.

---

## 7. 실행 호스트 `GET /host/site-timings`

루프백, 인증 없음. 늘 200.

```json
{
  "applied": {
    "version": 2,
    "evidenceBeforeSeconds": 30,
    "evidenceAfterSeconds": 15,
    "inDoubtGraceSeconds": 60,
    "stallWindowSeconds": 600
  },
  "appliedAt": "2026-10-09T03:12:46.002Z",
  "lastReadAt": "2026-10-09T03:12:51.004Z",
  "readError": null,
  "rejected": null
}
```

| 칸 | 형 | 뜻 |
|---|---|---|
| `applied` | 객체 또는 `null` | 적용한 버전과 값(초 정수). 다음 pump 부터 미들웨어가 쓴다. `null` 이면 **미적용**(첫 읽기가 아직 성공하지 않음) |
| `appliedAt` | 시각 또는 `null` | `applied` 를 받아들인 호스트 시각. 미적용이면 `null` |
| `lastReadAt` | 시각 또는 `null` | 마지막으로 읽기를 마친 호스트 시각. 성공과 실패를 가리지 않는다. 기동 안에서 한 번 읽으므로 기동 뒤에는 `null` 이 아니다 |
| `readError` | 문자열 또는 `null` | 마지막 읽기가 실패했으면 그 까닭(`<예외 이름>: <메시지>`, 뿌리 원인). 다음 읽기가 성공하면 `null` |
| `rejected` | 객체 또는 `null` | 마지막으로 읽은 행이 picasso 범위 밖이면 `{"version": <Long>, "reasons": [<문장>...]}`. 적용할 수 있는 행(같은 버전 포함)을 읽으면 `null` |

`rejected` 의 예와 `reasons` 문장(picasso `SiteTimings.problems()` 그대로, 칸 이름은 picasso 속성 이름):

```json
{"version": 4, "reasons": ["stallWindow: 30~3600 초 밖이다 (3601)"]}
```

문장 꼴: `<칸>: <하한>~<상한> 초 밖이다 (<초>)`, `siteSettingsVersion: 1 이상이어야 한다 (<값>)`. 칸 이름은 `evidenceWindowBefore`, `evidenceWindowAfter`, `inDoubtGrace`, `stallWindow`, `siteSettingsVersion` 이다(운영 서비스 본문 칸 이름과 다르다).

읽기 규칙(스펙 §7.1):

| 상황 | `applied` | 그 밖 |
|---|---|---|
| 기동 안의 첫 읽기 성공 | 그 행 | 기동이 끝나면 이미 적용돼 있다 |
| 첫 읽기 실패(뷰 없음 등) | `null`(미적용) | `readError` 에 까닭. 1초마다 다시 읽는다. 기동은 멈추지 않는다 |
| 뷰가 행을 한 개가 아니게 냄 | 그대로 | `readError`: `ops.site_timings_current 가 행 N 개를 냈다(한 행이어야 한다)` |
| 기동 뒤 읽기 실패 | 마지막 값 유지 | `readError` 에 까닭. `Exception` 만이 아니라 `Error`(예: `LinkageError`)도 잡아 남기고 다음 주기에 다시 읽는다. `VirtualMachineError`(메모리 부족, 스택 넘침)만 다시 던지며, 그때 주기 읽기는 멈추고 마지막 적용 값이 남는다 |
| 범위 밖 행 | 마지막 값 유지 | `rejected` 에 버전과 이유, `readError` 는 `null` |
| 같은 버전 | 그대로(`appliedAt` 도 그대로) | `readError`·`rejected` 를 지운다 |
| 다른 버전이고 범위 안 | 그 행 | `appliedAt` 갱신 |

- 읽기 주기는 실제 시간이고 설정 키로 준다. 호스트 잠금 밖에서 읽는다. 통합 시험은 «수 초 안에 적용» 을 실제 시간 5초 안팎으로 기다리면 된다(가상 시계를 밀 필요 없음).

| 설정 키 | 기본값(`mission-host.properties`) | 뜻 |
|---|---|---|
| `host.site-timings.read-interval` | `PT1S` | 현장 시간값 뷰 읽기 주기(ISO-8601 기간). **1ms 이상**이어야 하며 아니면 첫 읽기 전에 기동이 멈춘다(`IllegalArgumentException`, 메시지에 `1ms 이상`). 스케줄러가 밀리초로 받아 1ms 미만은 0 으로 잘리기 때문이다. 기동 안의 첫 읽기는 주기와 관계없이 한 번 동기로 하고, 주기 읽기의 첫 회는 기동 뒤 이 주기만큼 지나서다 |

- 호스트 시험은 `HostBench(readInterval = Duration.ofHours(1))` 로 주기 읽기를 사실상 끄고 기동 안의 첫 읽기만 단언한다. 통합 시험 스택은 기본값을 쓴다.
- 모의 실행(`MockRunner`)은 현장 시간값을 쓰지 않는다(T10).

---

## 8. 실행 호스트 `GET /host/incidents`

### 8.1 요청

`GET /host/incidents` 또는 `GET /host/incidents?limit=N`. `limit` 은 1~500 의 정수이고 기본 50 이다. 그 밖(`0`, `501`, `x`)은 400:

```json
{"error":"BAD_REQUEST","detail":"limit 은 1~500 의 정수다: 0"}
```

### 8.2 응답 `200`

```json
{
  "instanceId": "mw-3f0c...",
  "total": 2,
  "incidents": [
    {
      "incidentId": "incident-2",
      "executionId": "exec-2",
      "jobOrderId": "JO-2",
      "robotId": "humanoid-01",
      "unitId": "rack-arrival",
      "at": "2026-10-09T03:20:05Z",
      "failureClass": "SIGNAL_DEADLINE",
      "route": "SIGNAL",
      "missionVersion": 1,
      "siteSettingsVersion": 3,
      "evidenceBeforeSeconds": 45,
      "evidenceAfterSeconds": 25,
      "inDoubtGraceSeconds": 120,
      "stallWindowSeconds": 900
    },
    {
      "incidentId": "incident-1",
      "executionId": "exec-1",
      "jobOrderId": "JO-1",
      "robotId": "humanoid-01",
      "unitId": "rack-arrival",
      "at": "2026-10-09T03:16:00Z",
      "failureClass": "SIGNAL_DEADLINE",
      "route": "SIGNAL",
      "missionVersion": 1,
      "siteSettingsVersion": 2,
      "evidenceBeforeSeconds": 40,
      "evidenceAfterSeconds": 20,
      "inDoubtGraceSeconds": 90,
      "stallWindowSeconds": 600
    }
  ]
}
```

| 칸 | 형 | 뜻 |
|---|---|---|
| `instanceId` | 문자열 | 미들웨어 인스턴스(`GET /host/executions` 의 것과 같다). 호스트를 다시 띄우면 바뀌고 인시던트는 비어 다시 시작한다(메모리에만 있다) |
| `total` | 정수 | 자르기 전 인시던트 수 |
| `incidents` | 배열 | **최신부터**(봉인 순서의 역순) 많아야 `limit` 개. `limit` 을 생략하면 50 개까지 |
| `incidentId` | 문자열 | `incident-<N>`, 인스턴스 안에서 1부터 |
| `executionId`·`jobOrderId`·`robotId`·`unitId` | 문자열 | 그 인시던트의 실행·작업 지시·기체·단위 |
| `at` | 시각 | 봉인 라운드의 미들웨어 시각(호스트 시계 = 통합 시험에서는 현장 가상 시계) |
| `failureClass` | 문자열 또는 `null` | 미들웨어 분류. 설비 대기 기한이면 `"SIGNAL_DEADLINE"` |
| `route` | 문자열 | `"ROBOT"`, `"FLEET"`, `"SIGNAL"`. 설비 대기 단위는 `"SIGNAL"` |
| `missionVersion` | 정수 또는 `null` | 임무 버전. 코드 정의면 `null` |
| `siteSettingsVersion` | 정수 또는 `null` | 봉인 라운드의 현장 설정 버전. 현장 시간값 없이 봉인했으면 `null`(실행 호스트에서는 미적용 동안 실행이 없으므로 실제로는 늘 값이 있다) |
| `evidenceBeforeSeconds`·`evidenceAfterSeconds` | 정수 | 봉인 라운드의 근거 윈도우 앞·뒤 폭. 늘 있다 |
| `inDoubtGraceSeconds`·`stallWindowSeconds` | 정수 또는 `null` | 봉인 라운드의 값. 현장 시간값 없이 봉인했으면 `null` |

- 값은 picasso `IncidentBundle` 과 그 `intent` 에서 옮긴다. ISO-8601 문자열(`PT1M`)은 초 정수로 되돌린다.
- 설정 버전과 시간값은 **봉인한 라운드**의 것이다. 대기 중에 설정을 바꾸고 호스트 적용(`/host/site-timings` 의 `applied.version`)을 확인한 뒤 기한을 넘기면 새 버전이 실린다(통합 시험 4단계). 설비 대기의 기한 판정 자체는 임무 정의의 `deadlineSeconds`(`ARRIVAL_WAIT` 템플릿 120초)로 하고 시간값을 쓰지 않는다.
- 번들은 호스트 잠금 아래에서 옮긴다.

### 8.3 미적용 동안의 판정과 제출(S3a JSON 계약 모양 그대로)

`applied` 가 `null` 인 동안:

- `POST /host/eligibility`: 기체마다 `passed: false` 이고 `reasons` 끝에 이 문장이 더해진다. `skillFit`·`missingSkills`·`runningExecutionId` 는 평소대로 판정한다.

  ```
  현장 시간값 미적용: 실행 호스트가 현장 설정을 아직 읽지 못했다
  ```

  (상수 `MissionHost.UNAPPLIED_REASON`.)
- `POST /host/job-orders`: 판정을 통과한 기체가 없어 `assign` 에 기체가 하나도 넘어가지 않으므로 늘 `{"result":"UNASSIGNED","executionId":null,"robotId":null,"rejectionReason":null,"refusals":[],"excluded":[<판정 행>...]}` 이다. 미들웨어는 기체마다 관문을 걸므로 요구 근거 등급을 넘는 작업 지시도 REJECTED 가 아니라 UNASSIGNED 다. 새 결과 값은 없다.
- 운영 서비스의 배정 가능 판정과 작업 지시 제출은 이 판정 행을 S3a 그대로 합치므로 화면에는 기체마다 위 문장이 이유로 보인다.
- 임무 초안·검증·모의 실행·활성화·요청 조회, 셀 신호 조작과 조회, 실행 목록, `/host/site-timings`, `/host/incidents` 는 미적용과 관계없이 동작한다.

---

## 9. 상태 코드 요약(S3c 에서 더하거나 바뀐 것)

| 엔드포인트 | 경우 | 상태 | `error` |
|---|---|---|---|
| `GET /api/site-settings` | 늘(호스트가 닿지 않아도) | 200 | |
| `PUT /api/site-settings` | 행위자 헤더 없음·틀림 | 400 | `ACTOR_REQUIRED` |
| | 운영자 모드 | 403 | `MODE_NOT_ALLOWED` |
| | JSON 이 아닌 Content-Type | 415 | (스프링 본문) |
| | 본문 객체 아님·`baseVersion` 없음·값 칸 정수 아님·값 칸 다섯 다 빠짐 | 400 | `SETTINGS_BAD_REQUEST` |
| | 실린 값 범위 밖 | 400 | `SETTING_OUT_OF_RANGE` |
| | 사유 없음 | 400 | `REASON_REQUIRED` |
| | 지난 기준 버전 | 200, `result: "REJECTED"`, `rejection.kind` | `SETTINGS_VERSION_CONFLICT` |
| | 성공 | 200, `result: "SUCCEEDED"` | |
| `GET /host/site-timings` | 늘 | 200 | |
| `GET /host/incidents` | `limit` 이 1~500 정수가 아님 | 400 | `BAD_REQUEST` |
| | 그 밖 | 200 | |

---

## 10. 구현에서 정한 것

| 스펙 자리 | 정한 것 |
|---|---|
| T3 범위 수치 | 제안 그대로 확정: 앞 폭 5~120, 뒤 폭 5~120, `inDoubtGrace` 10~600, `stallWindow` 30~3600(P5 상수와 같음) |
| T6 본문 칸 이름 | `evidenceBeforeSeconds`, `evidenceAfterSeconds`, `inDoubtGraceSeconds`, `stallWindowSeconds`. `range` 는 평평한 `min<칸>`·`max<칸>` |
| T6 빠짐의 뜻 | 칸 없음과 `null` 은 빠짐, 정수 아닌 값은 400 |
| T6 범위 밖 메시지 | 실린 칸만, 칸마다 `<칸> <값>초는 범위 밖이다. <하한>~<상한>초여야 한다`, `"; "` 로 잇기. S2 의 연결 기준 시간 문장도 이 꼴로 |
| T6 충돌의 조작 기록 | 기준 버전 행이 있으면 그 값, 없으면 빠진 칸 `null` |
| 호스트 반영의 전달 | `GET /api/site-settings` 의 `hostTimings` 에 호스트 본문 그대로, 모르면 `null`. 별도 엔드포인트를 두지 않음 |
| T8 적용 시각 | 호스트 시계(통합 시험은 가상 시계). 같은 버전은 다시 적용하지 않음. 버전이 다르면 작아도 적용 |
| T8 읽기 주기 | 설정 키 `host.site-timings.read-interval`(기본 `PT1S`, 하한 1ms). 주기 작업은 `Throwable` 을 잡아 `readError` 에 남기고 이어 간다(`VirtualMachineError` 만 다시 던짐). 상수로 두었더니 기동이 1초를 넘기면 주기 읽기가 첫 읽기를 대신해 기동 안 동기 읽기를 결정적으로 시험할 수 없었다 |
| T8 미적용 제출 | 판정 이유 하나로 막는다. 기체 없는 `assign` 이 사유 없는 UNASSIGNED 를 내므로 제출에 따로 단락을 두지 않는다(두었더니 등가 변이였다) |
| T9 인시던트 목록 | 기본 50, 상한 500, `total` 칸, 시간값은 초 정수, 칸 이름은 운영 서비스 본문과 같게 |
| 통합 시험 스택 | `OpsSchema.migrate(url, user, password)` 를 호스트 기동 앞에 |
| S3b `basisVersion`(임무 검증 결과) | **채우지 않는다(늘 `null` 그대로).** 검증과 모의 실행은 현장 시간값을 쓰지 않으므로(T10) `Finding.basisVersion` 의 뜻(«이 판정이 기댄 현장 설정 버전»)대로 `null` 이 맞다 |

---

## 11. 화면이 기대는 것

API 모양은 바꾸지 않았다. 화면이 정한 것과 Playwright 가 쓸 문구는 다음과 같다.

| 자리 | 정한 것 |
|---|---|
| 폼 값 | 값 다섯을 현재 버전 값으로 채워 보인다. 처음 고치는 순간(값 칸이든 사유 칸이든 먼저 고친 것) 기준 버전과 그 버전의 값 다섯을 고정하고(S2 의 기준 버전 고정과 같음), PUT 에 다섯을 다 싣는다. Playwright `fill` 은 칸을 비우고 넣으므로 S2 단계 그대로 동작한다 |
| 입력 이름 | `연결 기준 시간(초)`, `근거 윈도우 앞 폭(초)`, `근거 윈도우 뒤 폭(초)`, `inDoubtGrace(초)`, `stallWindow(초)`, `변경 사유`. 칸 옆에 `허용 범위 <하한>~<상한>초`(`range` 에서) |
| 묶음 | 표시 구역(region)과 폼 fieldset(group) 모두 이름이 `결과 판정 값`(앞 폭, 뒤 폭, inDoubtGrace)과 `정체 표시`(stallWindow). 묶음마다 파급 문구 하나 |
| 범위 밖 | 보내지 않고 알림(`role=alert`)에 칸마다 `<주어> <하한>~<상한>초의 정수여야 합니다` 를 `; ` 로 잇는다. 연결 기준 시간은 S2 문구 `연결 기준 시간은 60~3600초의 정수여야 합니다` 그대로 |
| 결과 문구 | 기준 버전에서 바뀐 칸만 칸 순서대로 `<칸 이름> <값>초` 를 `, ` 로 잇고 `로 변경` 을 붙인다. 예: `inDoubtGrace 90초, stallWindow 600초로 변경: 반영됨`. 연결 기준 시간만 바꾸면 `연결 기준 시간 120초로 변경: 반영됨`(S2 그대로). 바뀐 칸이 없으면 `현장 설정 같은 값으로 새 버전 기록` |
| 이력 표 | 기존 여섯 칸 뒤에 `근거 윈도우 앞 폭`, `근거 윈도우 뒤 폭`, `inDoubtGrace`, `stallWindow` 열(값은 `<n>초`). 행 이름은 `2 120초 local 엔지니어 연결 기준 늘림 <시각> 30초 15초 60초 300초` 꼴이라 S2 의 `^2 120초 local 엔지니어 …` 정규식이 그대로 맞는다 |
| 실행 호스트 반영 | 현재 버전 dl 아래 한 줄(`<p>`). `hostTimings` 가 `null` 이면 `실행 호스트 반영: 모름`, `applied` 가 `null` 이면 `실행 호스트 반영: 미적용`, 아니면 `실행 호스트 반영: 버전 N`. `readError` 가 있으면 `실행 호스트 읽기 실패: <readError>`, `rejected` 가 있으면 `실행 호스트가 적용하지 않은 버전 <v>(범위 밖): <reasons 를 "; " 로 이음>` 을 각 한 줄로 더한다. `appliedAt`·`lastReadAt` 과 `applied` 의 값 넷은 보이지 않는다 |
| 칸이 없을 때 | `hostTimings`, `applied`, `readError`, `rejected` 가 없거나(undefined) null 이면 같은 처리. 시험 대역의 옛 모양도 모름으로 보인다 |
| 운영자 모드 | S2 그대로 폼 대신 `현장 설정 변경은 엔지니어 모드에서 합니다`. 값, 묶음, 실행 호스트 반영은 운영자 모드에서도 보인다 |
