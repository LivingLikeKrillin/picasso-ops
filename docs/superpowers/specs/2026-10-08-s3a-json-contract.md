# S3a JSON 계약: 실행 호스트 REST 와 현장 셀 대역 `/cell`

S3a 설계 스펙(`docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md`)을 구현한 site·mission-host 가 실제로 내고 받는 모양이다. 운영 서비스·화면·통합 시험은 이것에 맞춘다. 코드 주석의 «S3a JSON 계약 §N» 은 이 문서의 절을 가리킨다.

공통 규칙

- 모든 본문은 UTF-8 JSON 이다. 시각은 ISO-8601 UTC 문자열(`Instant.toString()`, 예 `"2026-10-08T00:01:05.250Z"`)이다. 소수 초 자릿수는 고정이 아니다(`Instant.parse` 로 읽을 것).
- `null` 칸은 생략하지 않고 `null` 로 나온다.
- 열거 값은 picasso 의 enum 이름 그대로다.
- 포트(루트 `.env`): `MIMIC_GRPC_PORT=8783`(mimic gRPC, 현장이 연다), `SITE_CELL_PORT=8784`(현장 셀 대역), `HOST_PORT=8785`(실행 호스트). 모두 `127.0.0.1` 루프백이다(mimic gRPC 만 모든 인터페이스).
- 실행 호스트 설정 키: `server.port`(기본 `${HOST_PORT}`), `host.mimic.port`(기본 `${MIMIC_GRPC_PORT}`), `host.cell.url`(기본 `http://127.0.0.1:${SITE_CELL_PORT}`, 경로 `/cell` 은 호스트가 붙인다). 시험은 실행 인자 `--server.port=0 --host.mimic.port=<site.mimicPort> --host.cell.url=http://127.0.0.1:<site.cellPort>` 로 덮는다.
- 실행 호스트 기동(같은 JVM): `MissionHostApplication.builder(HostClock { site.now() }).run(args...)`. 인자 없이 `builder()` 이면 실제 시각이다. 설정 파일 이름은 `mission-host` 이고 JDBC·Flyway 자동설정을 끄므로 registry·운영 서비스와 한 JVM 에 떠도 된다. 열린 포트는 `(context as WebServerApplicationContext).webServer.port`.
- 현장 쪽 Kotlin API: `Site.mimicPort`, `Site.cellPort`, `Site.cellSnapshot`, `Site.now()`, `Site.advance(by)`(시험용, 민 직후 셀 대역 훑기), `Site.advanceTo(t)`(앞으로만), `Site.teach(...)`. `SiteConfig` 에 `mimicPort`·`cellPort`(기본 0)·`cell`(기본 `CellFixture.STANDARD`)이 더해졌다.

---

## 1. 현장 셀 대역 `GET http://127.0.0.1:${SITE_CELL_PORT}/cell`

| 경우 | 상태 | 본문 |
|---|---|---|
| `GET /cell` | 200, `Content-Type: application/json` | 아래 |
| `/cell` 에 GET 아닌 방법 | 405, `Allow: GET` | 없음 |
| 다른 경로(`/cells`, `/cell/x` 등) | 404 | 없음 |

```json
{
  "presentations": [
    {"id": "SEQ-IN-02.BIN-A", "occupied": true, "material": "ENGINE-COVER-A", "observedAt": null}
  ],
  "slots": [
    {"id": "RACK-204.S01", "occupied": true,  "material": "ENGINE-COVER-A", "observedAt": "2026-10-08T00:01:05Z"},
    {"id": "RACK-204.S02", "occupied": false, "material": null,             "observedAt": null},
    {"id": "RACK-204.S03", "occupied": false, "material": null,             "observedAt": null},
    {"id": "RACK-204.S04", "occupied": false, "material": null,             "observedAt": null}
  ]
}
```

- 칸은 넷뿐이다: `id`(문자열), `occupied`(불리언), `material`(문자열 또는 null), `observedAt`(시각 문자열 또는 null).
- 고정 픽스처 `CellFixture.STANDARD`: 제시 자리 하나 `SEQ-IN-02.BIN-A` = `ENGINE-COVER-A`(늘 점유, `observedAt` 늘 null), 슬롯 `RACK-204.S01`~`S04`(이 순서). 슬롯은 처음에 전부 빈다.
- 슬롯이 채워지는 때: 현장이 시계를 민 직후(`advance`·`advanceTo`·런처 1초 틱) 같은 mimic 잠금 아래에서 새로 `SUCCEEDED` 가 된 `pick_place` 태스크를 찾아 `destination` 슬롯을 `occupied=true`, `material` = `object_id` 제시 자리의 자재, `observedAt` = 그 밀기 직후의 가상 시각(= 그 태스크 종료의 `state_as_of`)으로 채운다. 같은 태스크는 한 번만. `object_id` 가 제시 자리가 아니면 `material=null` 로 점유만 채운다. `destination` 이 픽스처 슬롯이 아니면 무시한다. 슬롯은 비워지지 않는다(재기동 전까지).

## 2. 실행 호스트 공통

- 기준 주소 `http://127.0.0.1:${HOST_PORT}`. 인증 없음.
- POST 의 `Content-Type` 은 `application/json` 만 받는다. 그 밖(`text/plain`, 폼 등)은 **415**(Spring 기본 오류 본문, 모양 보장 없음).
- 400 본문은 늘 이 모양이다(운영 서비스 `PreRejection` 과 같은 칸):

```json
{"error": "BAD_REQUEST", "detail": "jobOrderId 가 비어 있지 않은 문자열이 아니다"}
```

| `error` | 언제 |
|---|---|
| `UNKNOWN_WORK_MASTER` | `jobOrder.workMasterId` 가 `InspectAsset`·`PrepareSequencedRack` 이 아님(`DeliverContainer` 포함) |
| `BAD_REQUEST` | 본문이 JSON 객체가 아님, 칸 누락·형 틀림, `version < 1`, `requiredEvidence` 가 `E0`~`E3` 아님, 기체 목록 누락·빈 문자열 원소 |

판정 순서: 본문 객체 → `jobOrder` 객체 → `workMasterId` → 나머지 칸 → 기체 목록. 그래서 WorkMaster 가 틀리면 기체 목록이 없어도 `UNKNOWN_WORK_MASTER` 다.

### 2.1 작업 지시 본문 `jobOrder`

picasso `JobOrder` 와 같은 칸이다.

```json
{
  "jobOrderId": "JO-20261008-0001",
  "workMasterId": "PrepareSequencedRack",
  "version": 1,
  "requiredEvidence": "E2",
  "parameters": {},
  "materialRequirements": [{"materialDefinitionId": "ENGINE-COVER-A", "quantity": 2}],
  "equipmentRequirements": [
    {"id": "RACK-204.S01", "equipmentUse": "destination", "properties": {"material": "ENGINE-COVER-A"}},
    {"id": "RACK-204.S02", "equipmentUse": "destination", "properties": {"material": "ENGINE-COVER-A"}},
    {"id": "SEQ-IN-02.BIN-A", "equipmentUse": "source", "properties": {"material": "ENGINE-COVER-A"}}
  ]
}
```

InspectAsset 예:

```json
{
  "jobOrderId": "JO-20261008-0002",
  "workMasterId": "InspectAsset",
  "version": 1,
  "requiredEvidence": "E0",
  "parameters": {},
  "materialRequirements": [],
  "equipmentRequirements": [
    {"id": "T1", "equipmentUse": "inspection_target", "properties": {"location": "bay-7"}}
  ]
}
```

| 칸 | 형 | 필수 | 규칙 |
|---|---|---|---|
| `jobOrderId` | 문자열 | 예 | 비어 있지 않음 |
| `workMasterId` | 문자열 | 예 | `InspectAsset`·`PrepareSequencedRack` |
| `version` | 정수 | 예 | 1 이상(S3a 는 1 고정) |
| `requiredEvidence` | 문자열 | 예 | `E0`·`E1`·`E2`·`E3`. 호스트는 임무별 고정을 검사하지 않는다(운영 서비스가 InspectAsset `E0`, PrepareSequencedRack `E2` 로 넣는다). InspectAsset 에 `E2` 를 넣으면 REJECTED 다 |
| `parameters` | 객체(값은 문자열) | 아니오 | 없거나 null 이면 `{}` |
| `materialRequirements` | 배열 `{materialDefinitionId: 문자열, quantity: 0 이상 정수}` | 아니오 | 없거나 null 이면 `[]`. 비어 있지 않으면 자재별 수가 단위의 기대 자재 수와 같아야 미들웨어 관문을 지난다 |
| `equipmentRequirements` | 배열 `{id: 문자열, equipmentUse: 문자열, properties?: 객체(값은 문자열)}` | 아니오 | 없거나 null 이면 `[]` |

단위 id 겹침(대상·슬롯 중복, `<id>.travel` 충돌)은 호스트가 검사하지 않는다. 운영 서비스가 400 으로 막는다(스펙 §7.5).

### 2.2 호스트 판정 행 `HostEligibility`

```json
{
  "robotId": "quadruped-01",
  "skillFit": "MISSING",
  "missingSkills": ["pick_place"],
  "runningExecutionId": null,
  "passed": false,
  "reasons": ["모자란 스킬: pick_place"]
}
```

| 칸 | 값 |
|---|---|
| `skillFit` | `FIT`(경로가 로봇인 단위의 스킬이 기체 선언에 다 있음) · `MISSING`(모자람) · `UNKNOWN`(기체 케이퍼빌리티를 못 물어봄, 예: mimic 이 모르는 기체, mimic 불통) |
| `missingSkills` | `MISSING` 일 때 모자란 스킬(이름순). 그 밖에는 `[]` |
| `runningExecutionId` | 이 기체에서 물리 상태가 정착하지 않은 실행 id. 없으면 null. `OPERATOR_HOLD` 도 도는 실행이다 |
| `passed` | `skillFit == FIT` 이고 `runningExecutionId == null` |
| `reasons` | 통과 못 한 이유(한국어, 화면 표시용). 순서: 스킬(`모자란 스킬: a, b` 또는 `기체 케이퍼빌리티를 못 물어봤다`), 그다음 `도는 실행이 있다: exec-N`. 통과면 `[]` |

## 3. `POST /host/eligibility`

요청:

```json
{"jobOrder": { ...2.1... }, "robotIds": ["humanoid-01", "quadruped-01"]}
```

- `robotIds` 필수, 비어 있지 않은 문자열 배열(빈 배열 허용 → 빈 결과).

응답 200:

```json
{"robots": [ {HostEligibility}, ... ]}
```

- 요청 순서대로, 겹친 id 는 한 번만.
- 호스트는 시운전·연결을 보지 않는다. 운영 서비스가 합친다.

## 4. `POST /host/job-orders`

요청:

```json
{"jobOrder": { ...2.1... }, "candidates": ["quadruped-01", "humanoid-01"]}
```

- `candidates` 필수, 비어 있지 않은 문자열 배열.
- 호스트가 같은 판정을 다시 해 `passed` 인 기체만 `Middleware.assign` 에 넘긴다. 통과한 기체가 없어도 `assign` 을 빈 목록으로 부른다(결과 UNASSIGNED, `refusals` 빈 배열).

응답 200(결과가 무엇이든 200 이다. 상태 코드로 결과를 가르지 말 것):

```json
{
  "result": "ACCEPTED",
  "executionId": "exec-1",
  "robotId": "humanoid-01",
  "rejectionReason": null,
  "refusals": [],
  "excluded": [ {HostEligibility of quadruped-01} ]
}
```

| `result` | 미들웨어 | `executionId`·`robotId` | `rejectionReason` | `refusals` |
|---|---|---|---|---|
| `ACCEPTED` | `Submission.Accepted` | 새 실행 | null | `[]` |
| `IDEMPOTENT` | `Submission.Idempotent`(같은 `jobOrderId`·같은 `version`. 그 실행이 끝났고 후보가 판정을 통과해야 `assign` 까지 간다) | 기존 실행과 그 기체 | null | `[]` |
| `REJECTED` | `Submission.Rejected`(요구 근거 등급 초과, 관문 거부 등) | null | 사유(한국어) | `[]` |
| `UNASSIGNED` | `Unassigned` | null | null | `[{"robotId": "...", "reason": "..."}]`, `assign` 이 기체마다 낸 관문 사유. 호스트가 먼저 뺀 기체는 여기 없다 |

- `excluded`: 호스트가 판정에서 빼 `assign` 에 넘기지 않은 기체의 판정 행(2.2). 결과와 무관하게 늘 있다(없으면 `[]`).
- 같은 `jobOrderId` 를 그 실행이 도는 중에 다시 내면, 그 기체가 `runningExecutionId` 로 빠져 UNASSIGNED 다.

## 5. `GET /host/executions`

응답 200:

```json
{
  "instanceId": "mw-3f0c...",
  "pumpedAt": "2026-10-08T00:01:05.250Z",
  "executions": [
    {
      "executionId": "exec-1",
      "jobOrderId": "JO-20261008-0002",
      "workMasterId": "InspectAsset",
      "missionVersion": null,
      "robotId": "humanoid-01",
      "physicalState": "PHYSICALLY_DONE",
      "units": [
        {"unitId": "T1.travel", "skillType": "navigate_to", "state": "DONE", "reached": "E0"},
        {"unitId": "T1",        "skillType": "inspect",     "state": "DONE", "reached": "E0"}
      ],
      "jobResponse": {
        "jobResponseId": "resp-1",
        "version": 1,
        "physicalState": "PHYSICALLY_DONE",
        "requiredEvidence": "E0",
        "reachedEvidence": "E0",
        "completedUnits": ["T1.travel", "T1"],
        "unverifiedUnits": [],
        "incompleteUnits": {},
        "inDoubtUnits": [],
        "operatorRequired": false,
        "residualHold": "HOLD_KIND_EMPTY",
        "blockedBy": [],
        "connection": "CONNECTION_STATE_ONLINE"
      }
    }
  ]
}
```

| 칸 | 값 |
|---|---|
| `instanceId` | 미들웨어 인스턴스(`mw-<UUID>`). 호스트를 재기동하면 바뀌고 `exec-N` 은 1부터 다시 센다 |
| `pumpedAt` | 마지막 pump 를 **시작할 때**의 호스트 시계 값. 아직 한 번도 안 돌았으면 null. 통합 시험은 밀 때마다 `pumpedAt >= Site.now()` 를 기다린다. 다만 mimic 스트림 갱신은 gRPC 스레드로 비동기로 오므로, 그 pump 가 방금 민 전이를 못 볼 수 있다. 한 주기(250ms) 더 기다리거나 상태로 폴링할 것 |
| `executions` | 생성 순(`exec-1` 부터). 끝난 실행도 남는다 |
| `missionVersion` | 정수 또는 null. null 이면 코드 정의(화면 표시 «코드 정의»). S3a 는 늘 null |
| `physicalState` | `REQUESTED`·`ACCEPTED`·`RUNNING`·`PARTIAL`·`IN_DOUBT`·`OPERATOR_HOLD`·`PHYSICALLY_DONE`·`UNVERIFIED`·`FAILED`·`CANCELING`·`ABORTED`. 정착은 `PHYSICALLY_DONE`·`UNVERIFIED`·`FAILED`·`ABORTED`·`PARTIAL` |
| `units[].state` | `PENDING`·`IN_DOUBT`·`RUNNING`·`VERIFYING`·`OPERATOR_HOLD`·`DONE`·`UNVERIFIED`·`FAILED`·`ABORTED` |
| `units[].reached` | 근거 등급 `E0`~`E3`. PrepareSequencedRack 이 셀 대역으로 확인되면 `E2` |
| `units[].skillType` | `navigate_to`·`inspect`·`pick_place` |
| `jobResponse` | 그 실행의 **마지막** 작업 응답. 없으면 null. 실행 하나에 작업 응답이 여러 번 날 수 있다(호스트는 `ack` 하지 않는다) |
| `jobResponse.residualHold` | `HOLD_KIND_*` 이름 |
| `jobResponse.incompleteUnits` | 단위 id → 사유 문자열 객체 |
| `jobResponse.connection` | `CONNECTION_STATE_*` 이름 |

## 6. `GET /host/cell`

응답 200 늘:

```json
{"cell": {"presentations": [...], "slots": [...]}}
```

- `cell` 은 마지막 pump 가 현장 `/cell` 에서 읽은 스냅숏이며 모양은 1절 본문과 같다(칸 넷, 시각은 ISO 문자열).
- 현장 셀 대역이 안 닿거나(연결 실패, 1초 시간 초과, 200 아님, 모양 어긋남) 아직 pump 가 안 돌았으면 `{"cell": null}`(못 물어봄, 화면 «모름»). 이때 미들웨어의 셀 신호도 null 이라 PrepareSequencedRack 은 E2 를 못 얻고 마감 뒤 UNVERIFIED 가 된다.

## 7. 셀 신호(호스트 안, 참고)

- `observe(자리)`: 스냅숏의 제시 자리·슬롯에서 같은 id 를 찾아 `SlotSignal(occupied, material, observedAt)`. 없는 자리는 null. 스냅숏이 없으면 null.
- `holding(자재)`: 그 자재를 든 **제시 자리**만(채운 슬롯은 안 냄). 스냅숏이 없으면 null.
- `signal(이름)`: 늘 null(S3b).

## 8. 상태 코드 요약

| 엔드포인트 | 200 | 400 | 415 |
|---|---|---|---|
| `POST /host/eligibility` | 판정 | `UNKNOWN_WORK_MASTER`·`BAD_REQUEST` | JSON 아님 |
| `POST /host/job-orders` | 결과 넷 전부 | `UNKNOWN_WORK_MASTER`·`BAD_REQUEST` | JSON 아님 |
| `GET /host/executions` | 늘 | 없음 | 없음 |
| `GET /host/cell` | 늘(`cell` null 가능) | 없음 | 없음 |
| 현장 `GET /cell` | 늘 | 없음(405·404 는 위 1절) | 없음 |

호스트가 안 닿으면(연결 실패) 운영 서비스가 503·NO_RESPONSE 로 다룬다(스펙 §8). 호스트 자신은 5xx 를 의도해 내지 않는다.

---

## 9. 운영 서비스 REST(화면이 부르는 것)

운영 서비스가 실제로 내고 받는 모양이다. 화면과 통합 시험은 이것에 맞춘다.

공통 규칙

- 기준 주소 `http://127.0.0.1:${OPS_PORT}`. 시각은 ISO-8601 UTC 문자열이고, `null` 칸은 생략하지 않는다(스프링 기본 Jackson).
- 호스트 주소 설정 키는 `ops.host.url` 이다. 속성 파일이 `http://127.0.0.1:${HOST_PORT}` 로 주고 기본값이 없다(빠지면 기동에서 멈춘다). 형식이 `http(s)://호스트[:포트]` 가 아니어도 기동에서 멈춘다. 통합 시험은 `--ops.host.url=http://127.0.0.1:<호스트 포트>` 로 덮는다. 덮지 않으면 `.env` 의 8785 를 보고, 거기 아무것도 없으면 호스트 불통으로 다룬다(기존 시험은 그대로 돈다).
- 운영 서비스가 호스트를 부르는 제한: 연결 2초, 요청 5초. registry(3초)보다 긴 것은 호스트가 잠금 아래에서 mimic gRPC 를 부르고, mimic 이 엔진 잠금 아래에서 registry 적재(요청 3초)를 기다릴 수 있어서다.
- POST 는 `Content-Type: application/json` 만 받는다. 그 밖은 **415**(스프링 기본 본문, 모양 보장 없음).
- 사전 거부(400·403·503)의 본문은 늘 `PreRejection` 이다: `{"error": "...", "detail": "..."}`. 사전 거부는 조작 기록에 남지 않는다.

### 9.1 폼 초안(두 POST 의 본문)

```json
{"workMasterId": "InspectAsset", "targets": [{"id": "T1", "location": "bay-7"}]}
```

```json
{"workMasterId": "PrepareSequencedRack", "slots": ["RACK-204.S01", "RACK-204.S02"],
 "material": "ENGINE-COVER-A", "presentation": "SEQ-IN-02.BIN-A"}
```

| 칸 | 규칙 |
|---|---|
| `workMasterId` | `InspectAsset`·`PrepareSequencedRack`. 그 밖(`DeliverContainer` 포함)은 400 `UNKNOWN_WORK_MASTER` |
| `targets` | InspectAsset 만 읽는다. 비어 있지 않은 배열, 원소는 `{id, location}`(둘 다 비어 있지 않은 문자열). `id` 는 64자 이하(기체 `inspect` 스킬의 `target` 파라미터 `max_length`, mimic 처럼 UTF-16 문자 수) |
| `slots` | PrepareSequencedRack 만 읽는다. 비어 있지 않은 배열, 원소는 비어 있지 않은 문자열 |
| `material`·`presentation` | PrepareSequencedRack 만 읽는다. 비어 있지 않은 문자열. 제시 자리는 화면이 `/api/cell` 의 `presentations` 에서 자재로 고른다 |

- 그 임무가 쓰지 않는 칸은 무시한다.
- 400 `JOB_ORDER_BAD_REQUEST`: 본문이 JSON 객체가 아님, 칸 누락·형 틀림·빈 문자열·빈 배열, 대상 id 64자 초과(detail `대상 id 가 64자를 넘는다: 65자`).
- 400 `UNIT_ID_CONFLICT`: 단위 id 가 겹침. InspectAsset 의 단위 id 는 대상마다 `<id>.travel`·`<id>`, PrepareSequencedRack 은 슬롯 id 다. 대상 id 중복, 슬롯 중복, 대상 id 가 다른 대상의 `<id>.travel` 과 같은 경우가 걸린다. detail 예 `단위 id 가 겹친다: T1, T1.travel`(겹친 단위 id 이름순).
- 판정 순서: 본문 객체 → `workMasterId` → 그 임무의 칸 → 단위 id 겹침.
- 장소 이름·슬롯이 기체가 아는 명칭인지는 보지 않는다(스펙 §12).

운영 서비스가 만드는 작업 지시 본문은 2.1 절 그대로다. `version` 1, `parameters` `{}`, `requiredEvidence` 는 InspectAsset `E0`·PrepareSequencedRack `E2`, PrepareSequencedRack 의 `materialRequirements` 는 `[{materialDefinitionId: material, quantity: 슬롯 수}]`, 장비 요구는 슬롯마다 `destination` 을 폼 순서대로 두고 끝에 제시 자리 하나를 `source` 로 둔다(둘 다 `properties.material`). InspectAsset 은 대상마다 `inspection_target`(`properties.location`)이다.

### 9.2 `POST /api/job-orders/eligibility`

모드 헤더 없이 된다(부작용 없음). 본문은 9.1 폼. 호스트에는 작업 지시 id `JO-DRAFT` 로 묻는다.

응답 200:

```json
{
  "checkedAt": "2026-10-08T00:00:00Z",
  "registry": "OK",
  "robotsAsOf": "2026-10-08T00:00:00Z",
  "host": "OK",
  "robots": [
    {
      "robotId": "humanoid-01",
      "commissioning": "COMPLETE",
      "connection": "FRESH",
      "settingsVersion": 1,
      "host": {"robotId": "humanoid-01", "skillFit": "FIT", "missingSkills": [], "runningExecutionId": null, "passed": true, "reasons": []},
      "eligible": true,
      "reasons": []
    },
    {
      "robotId": "quadruped-01",
      "commissioning": "COMPLETE",
      "connection": "FRESH",
      "settingsVersion": 1,
      "host": {"robotId": "quadruped-01", "skillFit": "MISSING", "missingSkills": ["pick_place"], "runningExecutionId": null, "passed": false, "reasons": ["모자란 스킬: pick_place"]},
      "eligible": false,
      "reasons": ["모자란 스킬: pick_place"]
    }
  ]
}
```

| 칸 | 값 |
|---|---|
| `registry` | `OK`·`REGISTRY_SILENT`·`REGISTRY_UNAUTHORIZED`(`GET /api/robots` 의 것과 같다). `OK` 가 아니면 모든 행의 `commissioning`·`connection`·`settingsVersion` 이 null(«모름»). 직전 목록의 값을 대신 쓰지 않는다 |
| `robotsAsOf` | 기체 목록을 registry 에서 읽은 시각. `checkedAt` 과 다르면 직전 목록이다. 한 번도 못 읽었으면 null |
| `host` | `OK`·`HOST_SILENT`. `HOST_SILENT` 면 모든 행의 `host` 가 null(«모름») |
| `robots` | 그 사이트의 기체 목록 전부(퇴역 포함, `GET /api/robots` 와 같은 순서). 목록을 한 번도 못 읽었으면 null(«모름»), 기체가 없으면 `[]`. 둘 다 호스트를 부르지 않는다 |
| `robots[].commissioning` | `COMPLETE`·`INCOMPLETE`·`RETIRED`, 또는 null(모름) |
| `robots[].connection` | `FRESH`·`STALE`·`NO_REPORT`, 또는 null(모름) |
| `robots[].settingsVersion` | 연결 판정의 근거 현장 설정 버전. `connection` 이 null 이면 null |
| `robots[].host` | 호스트 판정 행(2.2 그대로), 또는 null(호스트 불통, 또는 호스트가 그 기체 행을 주지 않음). `skillFit: UNKNOWN` 은 호스트가 기체 케이퍼빌리티를 못 물어본 것이고 이것도 «모름» 이다 |
| `robots[].eligible` | `commissioning == COMPLETE` 이고 `connection == FRESH` 이고 `host.passed`. null 이 하나라도 있으면 거짓 |
| `robots[].reasons` | 배정 가능이 아닌 이유(화면 표시용). 순서: registry 모름 또는 시운전·연결, 그다음 호스트. 배정 가능이면 `[]` |

이유 문구(그대로 나온다):

| 경우 | 문구 |
|---|---|
| registry 불통 | `registry 가 답하지 않아 시운전·연결을 모른다` |
| 운영자 토큰 불일치 | `운영자 토큰이 registry 와 맞지 않아 시운전·연결을 모른다` |
| 시운전 미완 | `시운전이 끝나지 않았다` |
| 퇴역 | `퇴역한 기체다` |
| 연결 오래됨 | `연결이 오래됐다(기준 90초, 현장 설정 버전 1)` |
| 보고 없음 | `생존 보고가 없다` |
| 호스트 불통 | `실행 호스트가 답하지 않아 스킬 적합·도는 실행을 모른다` |
| 호스트가 그 기체 행을 안 줌 | `실행 호스트가 이 기체를 판정하지 않았다` |
| 호스트 판정 | 2.2 의 `reasons` 그대로(`모자란 스킬: a, b`, `기체 케이퍼빌리티를 못 물어봤다`, `도는 실행이 있다: exec-N`) |

400 은 9.1 의 셋뿐이다. 403·`ACTOR_REQUIRED` 는 없다.

### 9.3 `POST /api/job-orders`

헤더 `X-Ops-Mode: operator`, `X-Ops-User: <[A-Za-z0-9._-]{1,64}>`. 본문은 9.1 폼.

| 순서 | 상태 | `error` | 언제 |
|---|---|---|---|
| 1 | 400 | `ACTOR_REQUIRED` | 두 헤더가 없거나 틀림 |
| 2 | 403 | `MODE_NOT_ALLOWED` | 엔지니어 모드. 본문이 깨져도 403 이 먼저다 |
| 3 | 400 | `JOB_ORDER_BAD_REQUEST`·`UNKNOWN_WORK_MASTER`·`UNIT_ID_CONFLICT` | 9.1 |
| 4 | 400 | `NO_ELIGIBLE_ROBOT` | 새 작업 지시 id 로 다시 판정해 `eligible` 인 기체가 없음. 호스트를 부르지 않는다. detail 은 `humanoid-01: 이유; 이유 / quadruped-01: 이유` 꼴. 기체 목록이 null 이면 `기체 목록을 아직 읽지 못했다`, 비었으면 `이 사이트에 기체가 없다` |
| 5 | 200 | 아래 | 호스트에 제출함. 호스트가 무엇을 답했든 200 이다 |

1~4 는 조작 기록에 남지 않는다. 5 만 남는다.

응답 200:

```json
{
  "requestId": "6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a",
  "jobOrderId": "JO-20261008-6f1c2a9e",
  "result": "SUCCEEDED",
  "confirmation": null,
  "outcome": {
    "result": "ACCEPTED",
    "executionId": "exec-1",
    "robotId": "humanoid-01",
    "rejectionReason": null,
    "refusals": [],
    "excluded": []
  }
}
```

- 작업 지시 id 는 `JO-<UTC yyyyMMdd>-<requestId 앞 8자>`(예 `JO-20261008-6f1c2a9e`). 응답 본문의 `jobOrderId` 칸에 늘 실리고(결과·`NO_RESPONSE` 무관), 실행 목록의 `jobOrderId` 와 조작 기록의 `target` 과 같다. 칸 순서는 `requestId`·`jobOrderId`·`result`·`confirmation`·`outcome` 이다.
- 후보는 판정에서 `eligible` 인 기체만, 판정 행 순서대로다. `outcome.excluded` 는 호스트가 다시 판정해 뺀 기체다(4절).

| 호스트가 한 것 | `result` | `confirmation` | `outcome` |
|---|---|---|---|
| 200 `ACCEPTED`·`IDEMPOTENT` | `SUCCEEDED` | null | 호스트 본문(4절) |
| 200 `REJECTED`·`UNASSIGNED` | `REJECTED` | null | 호스트 본문(4절) |
| 4xx(`{error, detail}`) | `REJECTED` | null | 운영 서비스가 옮긴 모양: `result: "REJECTED"`, `rejectionReason: "실행 호스트가 거부했다(HTTP 400): <error>: <detail>"`, 나머지 null·`[]`. 폼을 먼저 검사하므로 정상 흐름에서는 나오지 않는다 |
| 5xx, 연결 실패, 시간 초과, 200 인데 본문을 못 읽음 | `NO_RESPONSE` | 재조회에서 그 작업 지시 id 의 실행이 있으면 `CONFIRMED_APPLIED`, 없으면 `CONFIRMED_NOT_APPLIED`, 재조회도 못 읽으면 null | null |

- 재조회는 1초 뒤 `GET /host/executions` 한 번이다. 그래서 `NO_RESPONSE` 응답은 1초 남짓 늦게 온다(시간 초과면 5초에 1초를 더한다).
- `confirmation` 이 null 인 `NO_RESPONSE` 는 «반영되었을 수 있으나 확인하지 못했다. 실행 목록에서 확인하라» 다.

조작 기록(`GET /api/operations` 의 행):

| 칸 | 값 |
|---|---|
| `target` | 작업 지시 id |
| `mode`·`user` | `OPERATOR`·헤더의 사용자 |
| `request` | `{"op":"SUBMIT_JOB_ORDER","jobOrder":{2.1},"candidates":[...]}`(JSON 문자열) |
| `reason` | null |
| `result` | 위 표의 `result`. 재조회 행은 같은 `requestId` 의 새 행(`CONFIRMED_*`) |
| `targetResponse` | 호스트가 답했으면 `{"status": 200, "body": {...}}`(본문이 JSON 객체가 아니면 `body` 는 글자), 닿지 않았으면 `{"cause": "응답 없음: ConnectException"}`, 재조회 행은 `{"observed": {"instanceId", "executionId", "robotId", "physicalState"}}` 또는 `{"observed": null}`(JSON 문자열) |

**이름이 바뀐 칸**: `GET /api/operations` 행의 `registryResponse` 는 이제 `targetResponse` 다(V3 마이그레이션, DB 칸 `target_response`). 지금 화면(`ui/src/api.ts` 의 `OperationRecord`)은 이 칸을 읽지 않는다.

### 9.4 `GET /api/executions`

모드 헤더 없이 된다. 호스트 `GET /host/executions` 의 200 본문(5절)을 **그대로** 넘긴다(해석·변형 없음).

호스트가 안 닿으면(연결 실패, 5초 시간 초과, 200 아님, 객체 아님) **503**:

```json
{"error": "HOST_SILENT", "detail": "실행 호스트가 답하지 않는다: 응답 없음: ConnectException"}
```

### 9.5 `GET /api/cell`

모드 헤더 없이 된다. 호스트 `GET /host/cell` 의 200 본문(6절, `{"cell": {...}}` 또는 `{"cell": null}`)을 그대로 넘긴다. `{"cell": null}` 은 200 이다(호스트는 닿았고 현장 셀 대역을 못 읽음, 화면 «모름»). 호스트가 안 닿으면 9.4 와 같은 503.

### 9.6 상태 코드 요약

| 엔드포인트 | 200 | 400 | 403 | 415 | 503 |
|---|---|---|---|---|---|
| `POST /api/job-orders/eligibility` | 판정(registry·호스트 불통도 200, 칸이 null) | 폼 셋 | 없음 | JSON 아님 | 없음 |
| `POST /api/job-orders` | 제출함(결과는 본문) | `ACTOR_REQUIRED`·폼 셋·`NO_ELIGIBLE_ROBOT` | `MODE_NOT_ALLOWED` | JSON 아님 | 없음 |
| `GET /api/executions` | 호스트 본문 | 없음 | 없음 | 없음 | `HOST_SILENT` |
| `GET /api/cell` | 호스트 본문 | 없음 | 없음 | 없음 | `HOST_SILENT` |
