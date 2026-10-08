# picasso-ops S3a: 미들웨어 실행 호스트와 작업 지시 설계

- 문서 상태: 설계 초안 (2026-10-08). 결정은 2026-10-08 사용자 결정(§2)
- 범위: picasso-ops 의 S3a 와 picasso 의 P4
- 요청·응답 JSON 의 정확한 모양: `docs/superpowers/specs/2026-10-08-s3a-json-contract.md`(스파이크에서 정함). 스파이크가 정한 세부는 S3a 계획 `docs/superpowers/plans/2026-10-08-s3a-execution-host.md` 머리에 있습니다
- 근거 문서: P3 스펙(`docs/superpowers/specs/2026-10-08-p3-mission-definition-versions-design.md`) §10 · S2 스펙(`docs/superpowers/specs/2026-10-08-s2-site-settings-design.md`) §1 · picasso 운영 관리 화면 설계 제안(`docs/superpowers/specs/2026-10-07-ops-console-lifecycle-design.md`) §10

## 1. 목적과 범위

P3 스펙 §10 과 S2 스펙 §1 이 S3 로 넘긴 것은 미들웨어 실행 호스트, 임무 버전 저장과 활성화, JSON 편집기와 모의 실행, 도는 실행의 버전 표시, 배정 가능, 미들웨어 시간값 셋과 인시던트의 현장 설정 버전입니다. 사용자 결정으로 S3 는 셋으로 나눕니다. S3a 는 실행, S3b 는 편집, 그 뒤 S3c 는 시간값 셋과 인시던트의 현장 설정 버전이며, 장애 주입은 S4 입니다.

S3a 가 입증하는 흐름은 하나입니다. 운영자가 화면의 작업 지시 폼으로 코드 정의 임무(InspectAsset, PrepareSequencedRack)의 작업 지시를 냅니다. 운영 서비스가 기체마다 배정 가능 여부와 이유를 보이고, 배정 가능한 기체만 후보로 실행 호스트에 넘깁니다. 실행 호스트의 picasso 미들웨어가 후보 중 하나를 고르고(`assign`) mimic 기체에서 실행합니다. 화면의 실행 목록이 실행 상태와 단위 상태, 임무 버전(코드 정의는 «코드 정의»)을 보입니다. PrepareSequencedRack 은 셀 대역이 배치 직후 슬롯을 기대 자재로 채워 E2 근거로 끝납니다.

S3a 가 입증하지 않는 것은 7가지입니다.

| 입증하지 않는 것 | 내용 |
|---|---|
| 임무 버전 저장·편집·활성화·모의 실행·도는 중 전환 | S3b |
| 미들웨어 시간값 셋과 인시던트의 현장 설정 버전 | S3c |
| 셀 대역을 틀리게 하는 조작과 장애 주입 | S4 |
| 운영자 보류(`OPERATOR_HOLD`)의 해소와 실행 취소 | S4. S3a 에는 해소 수단이 없어 보류에 선 기체는 호스트를 재기동할 때까지 배정 불가입니다(§12) |
| DeliverContainer | 플릿 포트 구현이 없습니다. 화면에 내지 않습니다 |
| 상위 시스템으로 작업 응답 보내기 | MES 가 없습니다. 작업 응답은 호스트 아웃박스에 남고 화면에 보입니다 |
| 호스트 재기동 뒤 실행 복원 | 메모리만 씁니다 |

## 2. 결정 기록

2026-10-08 사용자 결정 6건입니다.

| 번호 | 결정 | 내용 |
|---|---|---|
| 결정 1 | 호스트 위치 | picasso-ops 의 새 모듈이며 별도 프로세스입니다. 운영 서비스는 picasso 를 쓰지 않는 경계(`checkNoPicassoOnMain`)를 유지하고, 화면의 유일한 백엔드로서 호스트 REST 를 부릅니다 |
| 결정 2 | 임무 버전 저장 | 실행 호스트가 소유합니다(같은 Postgres 의 자기 스키마, 추가 전용, 검증과 활성화 한 자리). S3b 에서 합니다 |
| 결정 3 | 작업 지시 출처 | 화면의 작업 지시 폼(운영자 모드)입니다. MES 대신 사람이 냅니다 |
| 결정 4 | 배정 | 미들웨어가 배정 가능 후보 중 `assign` 으로 고릅니다. 화면은 기체마다 배정 가능 여부와 이유를 보입니다 |
| 결정 5 | S3 나눔 | 실행 먼저(S3a), 편집 다음(S3b)입니다 |
| 결정 6 | 셀 신호 | 셀 대역이 자동으로 정상 응답합니다. 배치 직후 슬롯을 기대 자재로 채웁니다. 틀리게 하는 조작은 S4 의 화면 패널입니다 |

이 문서가 정한 기술 결정 5건입니다.

| 번호 | 결정 | 내용 | 근거 |
|---|---|---|---|
| T1 | 시계 | mimic 가상 시계를 기동 직후 실제 시각으로 한 번 밀고, 런처가 1초마다 실제 시각까지 따라잡게 밉니다. picasso 의 시계 코드는 바꾸지 않습니다 | 미들웨어는 E2 시간 윈도우의 기준 시각을 mimic 응답 헤더의 `state_as_of` 에서 가져오고 마감은 자기 `now()` 로 봅니다. 지금 mimic 은 EPOCH 에서 시작해 둘이 어긋납니다 |
| T2 | 스트림과 엔진 직렬화 | picasso PR(P4)로 셋을 고칩니다. `ClientRobotPort` 가 끊긴 `WatchTask` 스트림을 이어 붙이고, `TaskFollower` 를 스레드 안전하게 하고, mimic 서버가 RPC 와 시간 흘리기를 잠금 하나로 줄 세웁니다 | 스트림에 10초 기한이 걸리고 스킬 소요 시간은 12~45초인데 지금은 다시 붙지 않습니다. 받는 목록은 gRPC 스레드가 쓰고 pump 스레드가 읽는데 동기화가 없습니다. mimic 엔진에는 잠금이 없고, S3a 에서 처음으로 현장의 시계 스레드와 호스트의 gRPC 호출이 동시에 엔진을 만집니다(§5.3) |
| T3 | 배정 가능 판정 나눔 | 운영 서비스가 시운전 완료와 연결 신선을, 호스트가 스킬 적합과 도는 실행 없음을 판정합니다 | 시운전·연결 판정 코드와 기준 시간은 운영 서비스와 ops DB 에 있고, 스킬과 실행은 미들웨어 쪽에 있습니다. 관문(`AdmissionGate`)은 이 넷을 보지 않습니다 |
| T4 | 셀 대역 자리 | site 프로세스입니다. mimic 과 같은 프로세스에서 태스크 상태를 직접 읽고, 호스트에는 루프백 HTTP 로 냅니다 | 발행 경로를 감싸지 않으므로 S4 의 전송 장애 주입과 무관합니다(§6.3) |
| T5 | 호스트 모양 | Spring Boot(운영 서비스와 같은 관례), 잠금 하나, pump 주기 250ms, S3a 는 DB 없음 | 운영 서비스의 관례를 따르고, 미들웨어가 스레드와 잠금을 갖지 않으므로 호스트가 잠금 하나로 지킵니다(§4) |

## 3. 단계와 완료 기준

| 단계 | 내용 | 완료 기준 |
|---|---|---|
| P4 | picasso PR. `ClientRobotPort`·`TaskFollower`·`MimicServer` | 기한으로 끊긴 스트림 뒤에 태스크가 종료하면 다음 `watch` 가 종료를 돌려줍니다. 이어 붙인 목록의 `update_index` 가 증가만 합니다. 네트워크 채널에서 시간을 흘리는 스레드와 스트림을 여는 스레드가 함께 돌아도 엔진이 깨지지 않습니다. picasso 전체 시험 통과, 결함 주입이 지정 시험에 잡힙니다 |
| S3a | picasso-ops PR. 서브모듈을 P4 머지 커밋으로 올리고, site·`mission-host`·운영 서비스·화면을 더합니다 | 통합 시험에서 InspectAsset 작업 지시가 배정되어 끝나고, PrepareSequencedRack 작업 지시가 셀 대역 덕에 E2 로 끝납니다. 배정 불가 기체가 이유와 함께 보이고 후보에서 빠집니다. 운영자 모드가 아니면 제출이 403 입니다. Playwright 에서 작업 지시를 내면 실행 목록에 «코드 정의» 행이 보입니다 |

순서는 P4 머지 뒤 S3a 입니다.

## 4. 확인한 사실

picasso `8f0cc04` 와 picasso-ops `f7ca937` 기준입니다.

| 사실 | 위치 |
|---|---|
| `Middleware` 생성자는 `robots: RobotPort` 만 필수이고 나머지는 기본값입니다. `capabilities` 와 `missions` 를 둘 다 주면 생성에 실패합니다. `missions` 는 private 이라 호스트가 카탈로그 참조를 따로 듭니다 | `picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt:44-117` |
| 미들웨어는 스레드가 없고, 운영 배치에서는 스케줄러가 `pump()` 를 돌린다고 적혀 있습니다. 잠금이 없습니다. 시험 소스 `ScenarioHost` 는 잠금 하나 아래에서 시계를 밀고 150ms 마다 pump 합니다 | `Middleware.kt:27-30`, `picasso/src/test/kotlin/dev/picasso/middleware/host/ScenarioHost.kt:88-137` |
| `Submission` 은 넷입니다: `Accepted`, `Idempotent`, `Rejected(reason, remedy, ...)`, `Unassigned(refusals)`. 배정은 `assign(order, candidates)` 입니다 | `Middleware.kt:243-267,383`, `Assignment.kt:65` |
| 작업 응답은 아웃박스입니다: `pending()`·`responses()`·`ack(id)`. 실행 하나에 작업 응답이 여러 번 날 수 있습니다. `JobResponse` 에 임무 버전 칸이 없습니다 | `Middleware.kt:275-277`, `Model.kt:360-391` |
| `Execution` 의 `executionId`·`order`·`robotId`·`missionVersion`·`units`(단위마다 `state`·`reached`)·`physicalState` 는 공개입니다. 실행은 가변 객체입니다 | `Middleware.kt:162-241` |
| 코드 정의는 버전이 `null` 입니다. `MissionCatalog.codeCapabilities()` 는 PrepareSequencedRack·DeliverContainer·InspectAsset 입니다. `MissionCatalog.active(workMasterId)` 가 케이퍼빌리티를 줍니다 | `Ports.kt:260-268`, `mission/InMemoryMissionCatalog.kt:37-49` |
| `instanceId` 는 무작위이고, `exec-N` 은 재기동하면 1부터 시작합니다 | `Middleware.kt:97-103` |
| 관문 `AdmissionGate.admits` 는 작업 지시 정합·연쇄·점유·바닥·작업 구역만 봅니다. 스킬·시운전·연결은 보지 않습니다. 정합 검사는 `materialRequirements` 가 비어 있지 않으면 자재별 수가 단위의 기대 자재 수와 같아야 통과입니다 | `AdmissionGate.kt:155-170,246-252` |
| `PicassoClient.follow` 는 스트림에 `withDeadlineAfter(deadlineSeconds)`(기본 10초)를 겁니다. `TaskFollower.received` 는 일반 `mutableListOf` 입니다. `ClientRobotPort.watch` 는 `followers.getOrPut(taskId){ follow(from = 0) }.updates` 라 다시 붙지 않습니다 | `client/src/main/kotlin/dev/picasso/client/PicassoClient.kt:63,195-205,237-256`, `Ports.kt:91-92` |
| 미들웨어는 `watch` 가 매번 누적 전체 목록을 준다고 전제하고 마지막 원소만 상태로 옮깁니다. 중복은 무해합니다 | `Middleware.kt:808-813` |
| `WatchTaskResponse` 에는 거부 칸이 없습니다. 스트림의 실패는 gRPC 상태로 `onError` 에 옵니다 | `contracts/proto/picasso/v1/task.proto:174` |
| mimic `WatchTask` 는 `from_update_index` 부터 재생하고, 종료면 보낸 뒤 닫습니다. 로그 크기와 같은 인덱스는 허용하고, 큰 것은 `OUT_OF_RANGE` 입니다 | `mimic/.../TaskServiceImpl.kt:143-181`, `TaskLog.kt:82-88` |
| mimic 엔진에는 잠금이 없습니다. 열린 스트림 표는 일반 `mutableMapOf`, 태스크 표는 `LinkedHashMap`, `VirtualClock.instant` 는 일반 `var` 입니다. 모든 태스크 RPC 가 들어올 때 `settle()` 을 부릅니다 | `TaskServiceImpl.kt:50,193-208`, `TaskHost.kt:187`, `Clock.kt:28-35` |
| 스킬 소요 시간은 `navigate_to 20s`, `pick_place 45s`, `inspect 12s` 이고 ±10% 지터가 있습니다. `pick_place` 에는 실패 모드(GRASP_FAILED 0.05, PAYLOAD_LOST 0.01)가 있고 기체별 시드로 추첨합니다 | `profile/profiles/humanoid-a.json`, `TaskHost.kt:393` |
| E2 판정: `doneAt` 은 mimic 응답 헤더 `state_as_of` 입니다. 신호 시각이 `[doneAt - 30s, doneAt + 15s]` 안이고 점유이면서 자재가 기대와 같으면 E2 완료입니다. 윈도우 안 신호를 마감보다 먼저 보며, 마감(`doneAt + 15s`)을 미들웨어 `now()` 가 넘으면 UNVERIFIED 입니다 | `Middleware.kt:1298-1344,1707-1708`, `LogicalCapability.kt:21` |
| mimic CLI 는 `VirtualClock(Instant.EPOCH)` 에서 시작합니다. `MimicServer.advance(d)` 는 시계 전진·정착·상태 발행을 한 번에 하며, 한 번에 크게 밀어도 됩니다 | `mimic/.../cli/Main.kt:165`, `MimicServer.kt:42-46` |
| registry 의 `lastReportedAt` 은 registry 수신 시각이고 헤더 시각을 읽지 않습니다. 그래서 mimic 시계를 밀어도 registry 판정은 그대로입니다 | `registry/.../LivenessService.kt:112` |
| PrepareSequencedRack 은 `equipmentRequirements` 의 `destination`(슬롯, `material` 속성)과 `source`(제시 자리, `material` 속성)로 단위를 만듭니다. 기체에 가는 파라미터는 `object_id`(제시 자리)·`destination`(슬롯)·`verify_grasp` 입니다 | `LogicalCapability.kt:101-138` |
| InspectAsset 은 `inspection_target` 마다 `navigate_to(location)`·`inspect(target)` 두 단위를 만듭니다. 대상의 `id` 가 `inspect` 단위의 id 이자 `target` 파라미터(64자 한도)이고, 이동 단위의 id 는 `<id>.travel` 입니다. 단위 id 가 같으면 태스크 id(`jobOrderId#unitId`)가 같아져 같은 핸들로 접힙니다(`Middleware.kt:1179`). `location` 속성이 갈 곳입니다. `item` 속성은 계획에 쓰이지 않습니다. 최고 근거는 E0 입니다 | `LogicalCapability.kt:239-284` |
| `navigate_to.location` 과 `pick_place.destination` 은 사이트 명칭 파라미터(`is_site_reference`)입니다. mimic 은 StartTask 에서 명칭을 검사하지 않습니다 | `skill_catalog.proto:146,167` |
| mimic 기체의 태스크와 그 파라미터·상태는 같은 프로세스에서 `instance.tasks`(`TaskHost`)의 `TaskRuntime.machine.parameters`·`machine.state` 로 읽을 수 있습니다 | `mimic/.../RobotInstance.kt:269`, `TaskHost.kt:189-192`, `TaskMachine.kt:83` |
| picasso-ops site 의 mimic gRPC 포트는 0(무작위)이고 밖에 알리지 않습니다. mimic gRPC 는 모든 인터페이스에 열립니다 | `site/.../Site.kt:65-66,85` |
| 운영 서비스의 시운전 판정과 연결 판정(`Connection` 은 `FRESH`·`STALE`·`NO_REPORT`), 기준 시간. registry 가 안 닿으면 `RobotListService` 는 직전 목록을 돌려주고 연결은 그 목록을 읽은 시각으로 판정합니다 | `Commissioning.kt:38-55`, `Blockers.kt:10,27-30`, `RobotListService.kt:86-93,121-135`, `ops.site_settings` |
| 조작 기록 `operation_log` 에는 `target`·`result`(SUCCEEDED·REJECTED·NO_RESPONSE·CONFIRMED_APPLIED·CONFIRMED_NOT_APPLIED)·`registry_response` 칸이 있습니다. 응답이 없으면 재조회해 확인 행을 덧붙이는 관례가 있고, 상태를 바꾸는 쪽에 닿지 않은 요청은 기록하지 않습니다 | `V1__operation_log.sql`, `OperationRunner.kt:24-35,60-65,93-106`, `Guard.kt:8-11` |
| 화면 «운영» 영역은 `ready: false` 입니다 | `ui/src/areas.ts:14` |
| picasso-ops 서브모듈은 `41beedb` 입니다. `41beedb..8f0cc04` 에서 registry·mimic·harness·client·contracts·capability·uplink 는 바뀌지 않아, 서브모듈을 올려도 기존 모듈은 그대로입니다 | picasso 이력 |

## 5. P4: picasso 의 스트림 이어 붙이기와 엔진 직렬화

### 5.1 `TaskFollower`

받은 목록을 잠금으로 지킵니다. `updates` 는 잠금 안에서 복사합니다. `completed` 와 `error` 는 `@Volatile` 입니다. 끝났는지(`completed` 또는 `error`) 묻는 속성 `ended` 를 하나 둡니다.

### 5.2 `ClientRobotPort.watch`

태스크마다 누적 목록과 지금 팔로워를 듭니다. 부를 때마다 팔로워가 새로 받은 것을 누적 목록에 붙입니다. 팔로워가 끝났는데 누적 목록의 마지막 원소가 종료(`SUCCEEDED`·`FAILED`·`CANCELLED`·`CANCELLED_RECOVERY_FAILED`)가 아니면, 마지막 원소의 `header.update_index + 1`(받은 것이 없으면 0)부터 새로 `follow` 합니다. 새로 붙은 팔로워가 곧바로 받은 것도 같은 호출에서 옮깁니다. 돌려주는 것은 누적 목록의 복사입니다. 미들웨어 코드는 바뀌지 않습니다. 누적 전체 목록 전제가 그대로 성립하기 때문입니다.

- 종료 판정은 마지막 원소의 `state` 하나로 합니다. 스트림의 실패는 기한 초과든 `NOT_FOUND`·`OUT_OF_RANGE` 든 모두 «닫혔다» 로 읽어 다음 `watch` 에서 다시 붙습니다.
- 종료 집합 넷은 미들웨어의 `isTerminal`(RETRIABLE·NEEDS_INTERVENTION 포함, `Middleware.kt:1713-1717`)보다 좁습니다. 미들웨어는 그 상태 뒤로 `watch` 를 부르지 않으므로 차이는 무해합니다.
- 태스크별 표는 일반 맵입니다. 호출자는 미들웨어 하나이고 호스트 잠금 아래에서 부른다고 전제합니다(KDoc 에 적습니다).

### 5.3 mimic 엔진 직렬화

`MimicServer` 가 잠금 하나를 듭니다. 계약 서비스 셋(Skill·Task·Event)과 제어 채널(`ControlServer`)을 가로채기(`ServerInterceptor`)로 감싸, 호출의 시작과 모든 콜백(요청·반쯤 닫힘·취소·완료·준비)을 그 잠금 아래에서 돕니다. `advance`·`settle`·`step`·`push` 와 기동 때의 온라인 발행(`start`)도 같은 잠금 아래입니다. 같은 프로세스에서 엔진 상태를 읽는 쪽이 쓰도록 `exclusive { }` 를 공개합니다. 잠금은 재진입됩니다. 실제로 재진입하는 자리는 제어 채널의 `advanceClock` 이 `mimic.advance` 를 부르는 곳(`ControlServer.kt:153`)과 Site 의 `advanceTo` 가 `exclusive` 안에서 `advance` 를 부르는 곳입니다. 잠금 순서는 호스트 잠금에서 mimic 잠금으로, mimic 잠금에서 registry HTTP 로 한 방향뿐이어서 교착이 없습니다.

- 생존 보고와 태스크 관측은 registry 로 동기 HTTP 로 갑니다(`IngestBridge`, 연결 2초·요청 3초 제한). 생존 보고는 S1 부터 `advance` 안에서 돌았고, 태스크 관측은 S3a 에서 처음 쓰입니다. 새로 생긴 것은 그 HTTP 가 잠금 아래에 있어 RPC 가 그 뒤에 줄 선다는 점입니다. registry 장애가 StartTask 지연으로 번질 수 있습니다(§10).

### 5.4 picasso 문서

- 설계 문서 §15 변경 이력에 §15.210 한 항목을 더합니다.
- `docs/limits.md` 머리의 «번호가 209 까지 갔고» 를 210 으로 고칩니다. §15.176 행(«실제 호스트가 생길 때»)은 열린 채 두고, picasso-ops 의 실행 호스트가 승인 창구·플릿 포트 없이 선다는 문장을 붙입니다. 스트림 재부착에 해당하는 오픈 항목 행은 없습니다.
- `docs/verification.md` 의 표 3행 «실 회선 셋» 을 다섯으로 고치고 근거 열에 `mimic/StartedInstanceTest`(#81 이 더했는데 고쳐지지 않음)와 `harness/ConcurrentEngineTest` 를 더합니다.
- 시험 수가 5 늘어 `CLAUDE.md` 와 `docs/verification.md` 의 총수를 1,977 에서 1,982 로, 한계 행이 둘 늘어 `README.md` 의 오픈 항목 수를 70(내부 29)에서 72(내부 31)로 고칩니다. 넷 다 스탬프 문서라 `tools/stamp.py` 를 다시 돌립니다.
- 한계 행 둘(`§15.210 · 재부착`, `§15.210 · 적재 잠금`)을 내부 오픈 항목에 더합니다. 문구에 정답표 용어(`handoff/narrator/ground-truth.jsonl`)가 들어가면 `GroundTruthTest` 가 막습니다.
- 계약 버전과 내보내기 버전은 바뀌지 않습니다.
- 시험이 짧은 기한을 줄 수 있게 `Harness.client` 에 `deadlineSeconds` 인자(기본 10)를 더합니다. 기본값이 그대로라 기존 호출과 picasso-ops 의 사용은 바뀌지 않습니다.

### 5.5 시험

| 시험 | 자리 | 보는 것 |
|---|---|---|
| 기한으로 닫힌 스트림 뒤의 종료를 다음 watch 가 돌려준다 | `picasso/src/test/kotlin/dev/picasso/middleware/StreamResumeTest.kt` | 이어 붙이기 |
| 이어 붙인 목록의 갱신 번호는 늘기만 한다 | 같은 파일 | `+1` 과 중복 |
| 종료를 본 뒤에는 다시 붙지 않는다 | 같은 파일 | 종료 판정(서버가 받은 `WatchTaskRequest` 수로 셈) |
| 여러 스레드가 동시에 넣어도 팔로워는 하나도 잃지 않는다 | 같은 파일 | `TaskFollower` 잠금 |
| 시간을 흘리는 동안 다른 스레드가 스트림을 열어도 엔진이 깨지지 않는다 | `harness/src/test/kotlin/dev/picasso/harness/ConcurrentEngineTest.kt` | 엔진 직렬화(Netty 채널) |

`StreamResumeTest` 는 harness 위에서 스트림 기한을 1초로 준 `PicassoClient` 를 쓰고, 실제로 1초 넘게 기다려 스트림을 닫은 뒤 가상 시계를 밀어 `pick_place` 를 종료시킵니다. `ConcurrentEngineTest` 는 `MimicCli` 로 Netty 서버를 띄우고, 한 스레드가 10ms 씩 3,000번 시간을 흘리는 동안 다른 스레드가 같은 태스크에 스트림을 계속 엽니다.

결함 주입은 7건입니다. 이어 붙이기 제거, `+1` 제거, 종료 집합 비우기, 새 팔로워의 옮긴 수를 0 으로 안 돌림, `onNext` 의 잠금 제거, 서비스 가로채기 제거, `advance` 의 잠금 제거입니다. 스파이크에서 7건 모두 지정 시험이 잡았고, 가로채기 제거는 세 번 돌려 세 번 잡혔습니다.

## 6. site: 시계 맞춤, gRPC 포트, 셀 대역

### 6.1 시계(T1)

`Site` 가 기동 직후 mimic 가상 시계를 실제 시각까지 한 번 밉니다. `Site.now()` 로 가상 시각을 냅니다. `advanceTo(t)` 는 `t` 가 가상 시각보다 뒤일 때만 그 차이만큼 밉니다. 런처(`SiteLauncher`)는 1초마다 `advance(1s)` 대신 `advanceTo(Instant.now())` 를 부릅니다(`TICK` 주석도 고칩니다). 기존 `advance(by)` 는 시험용으로 남깁니다. 런처로 돌 때 가상 시각은 실제 시각보다 앞서지 않고 차이는 한 틱 이내입니다. 시험이 `advance(by)` 로 밀면 가상 시각이 실제 시각보다 앞서며, 그때는 호스트 시계도 Site 시계를 씁니다(§7.2).

`Site.now()`, 시계 밀기, 셀 대역의 훑기, `Site.teach`(기체가 아는 명칭 쓰기, 읽는 쪽이 `advance` 안의 발행)는 모두 `MimicServer.exclusive { }` 아래에서 돕니다. 셀 대역의 훑기는 슬롯 표를 불변 스냅숏으로 갈아 끼우고, `GET /cell` 처리 스레드는 그 스냅숏만 읽습니다. 가상 시계는 다른 스레드에서 읽을 때 최신 값이 보인다는 보장이 없으므로 잠금이 그 가시성도 맡습니다.

### 6.2 gRPC 포트

`.env` 에 `MIMIC_GRPC_PORT=8783` 을 더하고 런처의 Site 가 그 포트로 mimic 을 엽니다. 시험은 0(무작위)을 주고 `Site.mimicPort` 로 열린 포트를 읽습니다. mimic gRPC 는 picasso 가 주소를 정하므로 모든 인터페이스에 열린다는 점은 기존 그대로이며, 포트를 고정하면 인증 없는 기체 제어 표면의 위치가 정해진다는 점을 §12 에 적습니다.

### 6.3 셀 대역 `SiteCell`(결정 6, T4)

- 고정 픽스처: 제시 자리와 그 자재(예: `SEQ-IN-02.BIN-A` 에 `ENGINE-COVER-A`), 그리고 슬롯 목록(예: `RACK-204.S01`~`S04`). 제시 자리는 늘 점유이고 자재가 바뀌지 않습니다(공급이 끝나지 않습니다).
- 슬롯: 처음엔 비어 있습니다. Site 가 시계를 민 직후마다 같은 잠금 아래에서 기체들의 태스크를 훑어, 새로 `SUCCEEDED` 가 된 `pick_place` 를 찾으면 그 `destination` 슬롯을 «점유, 자재 = `object_id` 제시 자리의 자재, 관측 시각 = 지금 가상 시각» 으로 채웁니다. 같은 태스크는 한 번만 처리합니다. 셀 대역은 슬롯을 비우지 않습니다(§12).
- 훑기 방식을 고른 이유: 발행 경로를 감싸면 mimic 의 전송 장애 주입(S4)에 같이 걸립니다. 시계를 미는 쪽이 Site 이므로 민 직후에 읽는 것이 장애와 무관합니다.
- 내는 것: `.env` 의 `SITE_CELL_PORT=8784` 에 루프백 HTTP `GET /cell` 하나입니다. 응답은 제시 자리 목록과 슬롯 목록(자리 id, 점유, 자재, 관측 시각)입니다. JDK `HttpServer` 를 쓰고 Spring 은 쓰지 않습니다. 시험은 0 을 줍니다. `Site.close()` 가 이 서버도 닫습니다.
- 이것은 대역입니다. 슬롯 내용은 기체가 보고한 배치에서 오므로 독립 설비 확인이 아닙니다. 화면과 문서에 «셀 대역» 으로 적습니다.

PrepareSequencedRack 의 E2 가 서는 순서는 이렇습니다. `pick_place` 가 `SUCCEEDED` 가 된 그 밀기 안에서 슬롯이 채워지므로 슬롯의 관측 시각과 `state_as_of` 가 같은 가상 시각입니다. 호스트가 `SUCCEEDED` 를 본 pump 의 셀 스냅숏이 채우기 전 것이면 그 pump 의 확인은 비어 있고, 다음 pump(250ms 뒤)의 스냅숏에서 윈도우 안 신호로 E2 가 됩니다. 마감 15초보다 훨씬 짧습니다.

## 7. 실행 호스트 `mission-host`

### 7.1 모듈

새 Gradle 모듈 `mission-host` 입니다. Spring Boot(BOM 만, 운영 서비스 관례)이며 `spring.config.name=mission-host`, `server.address=127.0.0.1`, 포트는 `.env` 의 `HOST_PORT=8785` 입니다(시험은 0). `settings.gradle.kts` 의 includeBuild 치환에 `dev.picasso:picasso`·`dev.picasso:client` 를 더하고, `mission-host` 는 `client` 를 직접 의존합니다(`picasso` 가 `client` 를 `implementation` 으로 듭니다). `capability` 는 `Rejected.remedy` 를 옮기지 않으므로 S3a 에서 필요 없습니다. `checkNoPicassoOnMain` 은 운영 서비스에만 걸리고 이 모듈에는 걸지 않습니다. 루트 `build.gradle.kts` 의 환경 규칙에서 `mission-host` 는 적재 토큰과 운영자 토큰을 받지 않습니다.

### 7.2 미들웨어 세우기

`Middleware` 에는 다음 구성을 넘깁니다.

| 인자 | 구성 |
|---|---|
| `robots` | `ClientRobotPort(PicassoClient(채널, "mission-host"))` |
| `cell` | 셀 대역 클라이언트(§7.4) |
| `missions` | 코드 정의를 담은 `InMemoryMissionCatalog`. 호스트가 같은 참조를 스킬 적합 판정에 씁니다 |
| `now` | 호스트 시계 |

채널은 `127.0.0.1:MIMIC_GRPC_PORT` 평문입니다. 호스트 시계는 Spring 빈 `HostClock`(지금 시각 하나를 내는 함수형 인터페이스)이고 기본은 실제 시각입니다. 통합 시험은 이 빈을 `Site.now()` 로 바꿔 끼웁니다.

### 7.3 잠금과 pump

잠금은 하나입니다. pump, REST 의 제출·판정·조회가 모두 그 잠금 아래에서 돕니다. 실행은 가변 객체라 잠금 안에서 응답 객체로 복사합니다. pump 는 단일 스레드 스케줄러가 250ms 마다 돌립니다. 셀 스냅숏을 site 에서 한 번 읽고(실패하면 스냅숏 없음, 셀 신호 `null` = 못 물어봄), 잠금 아래 `pump()` 를 부릅니다. 판정이 잠금 아래에서 `GetCapabilities` 를 부를 수 있으나 `PicassoClient` 가 세대별로 캐시하므로 기체마다 처음 한 번입니다.

### 7.4 셀 대역 클라이언트

`CellSignals` 구현입니다. 스냅숏에서 자리 id 로 `observe` 에 응답합니다. 스냅숏에 없는 자리는 `null` 입니다. `holding(material)` 은 스냅숏에서 그 자재를 든 **제시 자리** 목록입니다(채운 슬롯을 대안 자리로 내지 않습니다). 스냅숏이 없으면 `observe`·`holding` 모두 `null`(못 물어봄)입니다. 제시 자리의 관측 시각은 `null`(읽은 순간)이고, 슬롯의 관측 시각은 채운 가상 시각입니다. 이름 있는 신호 `signal(name)` 은 S3a 에서 `null` 이며 S3b 에서 다룹니다.

### 7.5 작업 지시 본문

운영 서비스는 picasso 타입을 쓰지 않으므로 둘 사이의 본문은 JSON 으로 정합니다. 칸은 picasso `JobOrder` 와 같습니다.

| 칸 | 값 |
|---|---|
| `jobOrderId` | 운영 서비스가 만듭니다(`JO-` 접두) |
| `workMasterId` | `InspectAsset` 또는 `PrepareSequencedRack` |
| `version` | 1 고정 |
| `requiredEvidence` | 임무마다 고정: InspectAsset `E0`, PrepareSequencedRack `E2` |
| `parameters` | 빈 객체 |
| `materialRequirements` | PrepareSequencedRack 은 `[{materialDefinitionId: 자재, quantity: 슬롯 수}]`, InspectAsset 은 빈 목록 |
| `equipmentRequirements` | `[{id, equipmentUse, properties}]`. InspectAsset 은 대상마다 `{id: 대상 id, equipmentUse: "inspection_target", properties: {location: 장소 이름}}`. PrepareSequencedRack 은 슬롯마다 `{id: 슬롯, equipmentUse: "destination", properties: {material}}` 와 제시 자리 하나 `{id: 제시 자리, equipmentUse: "source", properties: {material}}` |

자재에서 제시 자리를 고르는 것은 화면입니다(셀 대역의 제시 자리 목록에서 자재를 고르면 그 자리가 정해집니다). 운영 서비스는 단위 id 가 겹치는 작업 지시를 400 으로 막습니다. 대상 id 목록 안의 중복, 슬롯 목록 안의 중복, 대상 id 가 다른 대상의 이동 단위 id(`<id>.travel`)와 같은 경우입니다. 장소 이름과 슬롯이 기체가 아는 명칭인지는 S3a 에서 검사하지 않습니다. mimic 이 받아 주기 때문이며 한계로 적습니다(§12).

### 7.6 REST

루프백이고 인증이 없습니다. 호출자는 운영 서비스뿐입니다. POST 는 `application/json` 만 받습니다(브라우저의 단순 요청을 막는 운영 서비스의 관례와 같음). 같은 기계의 다른 프로세스가 운영자 모드 검사 없이 작업 지시를 낼 수 있는 것은 한계입니다(§12).

| 엔드포인트 | 입력 | 출력 |
|---|---|---|
| `POST /host/eligibility` | 작업 지시 본문과 기체 id 목록 | 기체마다 스킬 적합(모자란 스킬 목록, 기체 케이퍼빌리티를 못 물어보면 «모름»), 도는 실행(있으면 실행 id) |
| `POST /host/job-orders` | 작업 지시 본문과 후보 목록 | 같은 판정을 다시 해 통과한 기체만 `assign` 에 넘깁니다. 출력은 결과(ACCEPTED·IDEMPOTENT·REJECTED·UNASSIGNED), 실행 id, 기체 id, 거부 사유, 기체별 미배정 사유, 호스트가 판정에서 뺀 기체와 이유 |
| `GET /host/executions` | 없음 | `instanceId`, 마지막 pump 의 시각 `pumpedAt`, 실행 목록 |
| `GET /host/cell` | 없음 | 마지막 셀 스냅숏(화면의 셀 표시와 폼의 자재·슬롯 선택용) |

- 스킬 적합: `MissionCatalog.active(workMasterId)` 의 케이퍼빌리티로 작업 지시를 `plan` 해, 경로가 `ROBOT` 인 단위의 `skillType` 이 기체가 선언한 스킬(`RobotPort.capabilities`)에 다 있는가입니다.
- 도는 실행: 그 기체의 실행 중 `physicalState` 가 정착하지 않은 것입니다.
- 실행 목록의 칸: 실행 id, 작업 지시 id, WorkMaster, 임무 버전(null 이면 코드 정의), 기체, 물리 상태, 단위 목록(단위 id, 스킬, 상태, 근거 등급), 작업 응답(그 실행의 마지막 작업 응답과 그 칸들, 없으면 null).
- WorkMaster 는 InspectAsset·PrepareSequencedRack 만 받습니다. 그 밖은 400 입니다.
- IDEMPOTENT 는 운영 서비스가 매번 새 id 를 만들어 실제로는 나오지 않습니다. 매핑은 두고, 호스트 시험에서 InspectAsset 실행이 끝난 뒤 같은 id 를 다시 내 확인합니다.

### 7.7 작업 응답 아웃박스

상위 시스템이 없어 `ack` 하지 않습니다. 실행 목록에 작업 응답을 보입니다. 아웃박스가 계속 자라는 것은 한계입니다(§12).

## 8. 운영 서비스

설정은 `ops.host.url` 이고 호스트 클라이언트는 하나입니다.

| 엔드포인트 | 모드 | 내용 |
|---|---|---|
| `POST /api/job-orders/eligibility` | 모드 검사 없음(읽기, `GET /api/robots` 와 같음) | 폼 초안을 받아 기체마다 시운전(완료 여부), 연결(`FRESH`·`STALE`·`NO_REPORT`, 근거 버전), 도는 실행, 스킬 적합, 배정 가능(넷 다 통과), 이유 목록을 돌려줍니다. 시운전·연결은 S1·S2 의 기존 판정을 그대로 씁니다. registry 가 안 닿으면(`registry != OK`) 시운전·연결 칸은 «모름» 이고, 호스트가 안 닿으면 호스트 판정 칸이 «모름» 입니다. 어느 쪽이든 «모름» 이 있으면 배정 가능은 거짓입니다 |
| `POST /api/job-orders` | 운영자 모드만. 엔지니어 모드는 403, 행위자 헤더가 없으면 400 `ACTOR_REQUIRED`(기존 관례) | 폼에서 작업 지시 본문을 만들고 배정 가능을 다시 판정해 후보를 넘깁니다. 후보가 없으면 호스트를 부르지 않고 400 `NO_ELIGIBLE_ROBOT`(기존 사전 거부 모양 `PreRejection(error, detail)`, detail 에 기체별 이유)을 돌려주며 기록하지 않습니다(상태를 바꾸는 쪽에 닿지 않은 요청은 조작이 아니라는 관례). 단위 id 겹침도 같은 모양의 400 입니다 |
| `GET /api/executions` | 모드 검사 없음 | 호스트 것을 그대로 전달합니다. 호스트가 안 닿으면 503 과 이유 |
| `GET /api/cell` | 모드 검사 없음 | 호스트 것을 그대로 전달합니다. 호스트가 안 닿으면 503 과 이유 |

조작 기록은 S2 처럼 직접 씁니다. `OperationRunner` 는 registry 쓰기와 HTTP 상태 코드로 결과를 가르는데 호스트는 200 본문에 결과를 싣기 때문입니다.

| 칸 | 값 |
|---|---|
| `target` | 작업 지시 id |
| `result` | ACCEPTED·IDEMPOTENT 는 SUCCEEDED, REJECTED·UNASSIGNED 는 REJECTED, 호스트가 안 닿음은 NO_RESPONSE |
| 응답 칸 | 호스트 응답 본문 |

호스트의 4xx 는 REJECTED, 5xx·연결 실패·시간 초과는 NO_RESPONSE 입니다(`OperationRunner` 와 같은 구분). NO_RESPONSE 뒤에는 기존 관례대로 기존 `requeryDelay` 만큼 기다린 뒤 재조회합니다. `GET /host/executions` 에서 그 작업 지시 id 의 실행이 있으면 CONFIRMED_APPLIED, 없으면 CONFIRMED_NOT_APPLIED 행을 덧붙입니다. 재조회도 안 닿으면 NO_RESPONSE 행만 남습니다.

제출의 200 응답 본문은 `{requestId, jobOrderId, result, confirmation, outcome}` 입니다. `result`·`confirmation` 은 조작 기록의 값이고, `outcome` 은 호스트 응답(결과, 실행 id, 기체 id, 거부 사유, 기체별 미배정 사유, 호스트가 판정에서 뺀 기체와 이유)이며 호스트가 안 닿았으면 null 입니다. 화면은 이 모양을 기존 `OperationOutcome` 과 따로 읽습니다.

응답 칸의 이름 `registry_response` 가 registry 를 전제하므로 V3 마이그레이션에서 `target_response` 로 바꾸고 코드의 이름도 함께 바꿉니다. S2 스펙이 S3 로 미룬 자리입니다.

## 9. 화면

«운영» 영역을 엽니다(`ready: true`). 세 부분입니다: 작업 지시 폼, 기체별 배정 가능 표, 실행 목록과 셀 대역 표시.

### 9.1 작업 지시 폼

임무를 고릅니다(InspectAsset·PrepareSequencedRack).

| 임무 | 입력 |
|---|---|
| InspectAsset | 점검 대상 목록(대상 id, 장소 이름). 장소 이름은 기체가 아는 명칭이어야 하나 화면이 검사하지 않습니다 |
| PrepareSequencedRack | 셀 대역의 슬롯 목록에서 고른 슬롯들과, 제시 자리 목록에서 고른 자재(제시 자리는 자재에서 정해짐) |

폼이 바뀔 때와 운영 영역 폴링 때마다 배정 가능 표를 다시 읽습니다(연결이 낡으면 표에 보이게). 제출은 운영자 모드에서만 보이고, 엔지니어 모드에는 «작업 지시는 운영자 모드에서 냅니다» 문구를 보입니다(기존 관례). 제출 결과는 배정된 기체와 실행 id, 또는 거부·미배정 사유(기체별)입니다.

### 9.2 실행 목록

실행 id, 작업 지시 id, 임무와 임무 버전(null 이면 «코드 정의»), 기체, 물리 상태, 단위 상태와 근거 등급, 작업 응답을 보입니다. 호스트 `instanceId` 를 머리에 보입니다. 셀 표시는 «셀 대역» 으로 적습니다.

### 9.3 폴링

실행 목록과 셀은 기존 다섯 조회의 `Promise.all` 밖에서 따로 읽습니다. 호스트가 멈춰도 기존 영역이 낡은 값으로 바뀌지 않게 하기 위해서입니다. «운영» 영역이 열려 있을 때만 읽습니다.

## 10. 오류 처리

| 상황 | 보이는 것 |
|---|---|
| 호스트가 안 닿음 | 호스트 판정 칸 «모름», 배정 가능 거짓, 제출은 NO_RESPONSE 로 기록하고 재조회, 실행 목록은 503 과 직전 값 표시 |
| registry 가 안 닿음 | 시운전·연결 칸 «모름», 배정 가능 거짓. mimic 의 태스크 관측 적재가 시간 초과까지 기다리므로 StartTask 가 늦어질 수 있음 |
| site 셀이 안 닿음 | 셀 신호 `null`(못 물어봄). PrepareSequencedRack 은 E2 를 못 얻어 마감 뒤 UNVERIFIED. 화면 셀 표시는 «모름» |
| 후보가 하나도 없음 | 400 `NO_ELIGIBLE_ROBOT`, detail 에 기체별 이유. 호스트를 부르지 않고 기록하지 않음 |
| 단위 id 겹침(대상·슬롯 중복, `.travel` 충돌) | 운영 서비스 400 |
| 모르는 WorkMaster | 운영 서비스의 폼 검사가 400 `UNKNOWN_WORK_MASTER`, 기록 없음. 호스트의 400 은 두 쪽의 임무 목록이 어긋날 때만 나며 운영 서비스는 REJECTED 로 기록 |
| mimic 스트림 끊김 | P4 가 다음 `watch` 에서 이어 붙입니다. 기체가 태스크를 모르는(`NOT_FOUND`) 것처럼 늘 실패하는 경우도 pump 마다 다시 붙으려 합니다(§12) |
| 실행이 운영자 보류에 섬 | 해소 수단이 없어 그 기체는 도는 실행이 있는 것으로 남아 배정 불가(§12) |
| 호스트 재기동 | 실행이 사라집니다. `instanceId` 가 바뀌어 화면이 구별합니다 |

## 11. 시험

| 무엇 | 어디서 | 결함 주입 |
|---|---|---|
| 스트림 이어 붙이기, 팔로워 잠금, 엔진 직렬화 | P4(picasso, §5.5) | §5.5 의 7건 |
| `advanceTo` 가 앞으로만 밈, 셀 대역이 `pick_place` 성공 뒤 슬롯을 채우고 같은 태스크를 두 번 채우지 않음, `holding` 이 제시 자리만, `GET /cell` 형식 | site | 관측 시각을 EPOCH 로, 이미 본 태스크 재처리 |
| 스킬 모자람·도는 실행 판정, 제출 결과 네 가지 매핑(IDEMPOTENT 는 같은 id 재제출), 실행 목록의 임무 버전 null, WorkMaster 400 | mission-host | 스킬 적합 반전, 도는 실행 무시 |
| 배정 가능 네 칸 합성, registry 불통이면 «모름», 운영자 모드 403, 후보 없음 400 `NO_ELIGIBLE_ROBOT`, 조작 기록 result 매핑과 재조회, 호스트 불통 NO_RESPONSE | 운영 서비스 | registry 불통을 신선으로 접기, 운영자 모드 검사 제거, result 매핑 뒤바꾸기 |
| InspectAsset 끝까지, PrepareSequencedRack 이 셀 대역으로 E2, 배정 불가 기체가 후보에서 빠짐 | 통합(e2e 모듈의 한 JVM 스택에 호스트를 더함) | 셀 대역 채우기 끄기(UNVERIFIED 로 끝나는지), 호스트의 스킬 적합 판정을 늘 적합으로 |
| 운영자 모드로 작업 지시를 내면 실행 목록에 «코드 정의» 행 | Playwright | 없음(통합 시험이 맡음) |

- 통합 시험의 준비: 기체가 «시운전 완료» 가 되려면 기체 선언, 어댑터·빌드 등록, 리비전 제출, 시험 요청(실행기가 실제 시간으로 돔), 활성화, 바인딩, 명칭 기록을 다 거쳐야 합니다(`CommissioningTest` 순서). 이 단계를 함수 하나로 묶은 공용 픽스처를 두고 통합 시험이 그것을 씁니다. 픽스처는 humanoid-01 과 quadruped-01 둘 다 시운전을 마칩니다(quadruped-01 은 `Site.teach` 로 명칭을 넣음). 그래야 PrepareSequencedRack 에서 스킬 적합 하나로만 빠지는 기체(`pick_place` 가 없는 quadruped-01)가 생기고, 통합 시험이 그 기체의 배정 가능 표 행(모자란 스킬 `pick_place`)을 단언해 스킬 적합 주입을 잡습니다. 연결 신선은 registry 수신 시각 기준이고 상태 발행은 `advance` 때만 나므로, 실행기가 실제 시간으로 기다리는 단계 뒤 픽스처는 마지막에 `advance` 로 생존 보고를 한 번 내고 끝납니다. 호스트가 제출 때 다시 거르는 것은 운영 서비스가 이미 거른 뒤라 통합 시험에서는 결과가 같으므로 호스트 시험이 맡습니다(후보에 맞지 않는 기체를 직접 넣음).
- 통합 시험의 시계: 호스트 시계를 Site 시계로 바꿔 끼웁니다. 호스트는 마지막 pump 의 시각(`pumpedAt`, 호스트 시계 값)을 `GET /host/executions` 에 냅니다. 시험은 밀 때마다 `pumpedAt` 이 `Site.now()` 이상이 될 때까지 기다리고, 그 뒤 한 주기(250ms)를 더 기다린 다음 기대하는 상태를 폴링합니다. mimic 스트림의 갱신은 gRPC 스레드로 비동기로 오므로 `pumpedAt` 만으로는 그 pump 가 방금 민 전이를 봤다는 보장이 없습니다. 마감(`doneAt + 15s`)은 단위를 끝낸 그 밀기 뒤의 가상 시각부터 세므로 한 번에 크게 밀어도 됩니다. 위험한 것은 끝낸 뒤 호스트가 채운 스냅숏으로 확인하기 전에 15초 넘게 더 미는 것이므로, 단위를 끝낸 밀기 뒤에는 그 단위가 DONE 이나 UNVERIFIED 로 보일 때까지 더 밀지 않습니다.
- 실패 모드: `pick_place` 의 실패 모드는 시드로 추첨됩니다. 통합 시험은 시드 0 에서 이 순서가 성공함을 스파이크에서 확인해 계획에 적습니다. 런처와 Playwright 의 실제 운용에서는 약 6% 가 실패하며, 그것은 실행 목록에 실패로 보입니다.
- Playwright: `ui/e2e/run-dist.mjs` 의 `MAIN` 과 이름 검사, `ui/playwright.config.ts` 의 webServer(site 와 ops-service 사이), CI 의 installDist 에 `mission-host` 를 더합니다. `playwright.config.ts` 의 `loadEnv` 접두사에 `HOST_` 를 더해 준비 URL 을 만들고, `run-dist.mjs` 의 오류 문구도 함께 고칩니다. S3a 단계는 `lifecycle.spec.ts` 에서 site 를 끄는 단계 앞에 넣습니다.
- 결함 주입은 시험 결과 XML 의 실패 시험 이름으로 판정합니다.

## 12. 한계와 다음 단계

- 호스트는 메모리만 씁니다. 재기동하면 실행이 사라집니다. 작업 응답 아웃박스는 `ack` 하지 않아 자라고, `ClientRobotPort` 의 태스크별 누적 목록도 지우지 않아 자랍니다.
- 운영자 보류를 푸는 수단이 없습니다. 보류에 선 실행의 기체는 호스트를 재기동할 때까지 배정 불가입니다. 해소와 취소 화면은 S4 입니다.
- 셀 대역은 기체 보고에서 슬롯을 채우므로 독립 확인이 아니고, 슬롯을 비우지 않습니다. 같은 슬롯을 다시 쓰는 작업 지시는 앞 배치의 관측이 남은 슬롯에 배치합니다.
- 장소 이름과 슬롯이 기체가 아는 명칭인지 검사하지 않습니다(mimic 이 받아 줌).
- mimic gRPC 는 모든 인터페이스에 열리고, 런처는 그 포트를 `.env` 로 고정합니다. 인증 없는 기체 제어 표면입니다.
- 호스트 REST 는 루프백·무인증이라 같은 기계의 다른 프로세스가 운영자 모드 검사 없이 작업 지시를 낼 수 있습니다.
- 배정 가능 판정은 제출 직전에 다시 하지만, 판정과 `assign` 사이의 연결 변화는 막지 않습니다.
- P4 의 재부착은 오류의 종류를 가르지 않습니다. 늘 실패하는 스트림은 pump 마다 다시 열리고, 그 태스크는 미들웨어의 진행 정체 판정(`stallWindow`)이 잡을 때까지 남습니다.
- mimic 의 태스크 관측 적재가 엔진 잠금 아래에서 동기 HTTP 로 돌아, registry 장애가 mimic 의 응답 지연으로 번집니다.
- 다음은 S3b(임무 버전 저장·JSON 편집기·검증·모의 실행·활성화·도는 중 전환, 이름 있는 신호를 셀 대역에), S3c, S4 입니다.
