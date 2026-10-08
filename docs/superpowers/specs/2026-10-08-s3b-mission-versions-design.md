# picasso-ops S3b: 임무 버전 저장, 편집, 모의 실행, 활성화 설계

- 문서 상태: 설계 초안 (2026-10-08). 결정은 2026-10-08 사용자 결정(§2)
- 범위: picasso-ops 의 S3b. picasso 변경 없음
- 요청·응답 JSON 의 정확한 모양과 스파이크가 정한 세부(오류 이름, 모의 실행 실패 하위 범주 `SUBMISSION_REJECTED` 를 더한 다섯, `INPUT_UNKNOWN` 의 입력 `SAMPLE_ORDER` 등): S3b JSON 계약 `docs/superpowers/specs/2026-10-08-s3b-json-contract.md`(스파이크에서 정함). S3a JSON 계약의 §1(셀 대역 칸), §6(`GET /host/cell`), §7(이름 있는 신호), §8(상태 코드 표), §9.5(`GET /api/cell`), §9.6(상태 코드 요약), 공통 규칙의 JDBC 줄을 이 계약이 대체합니다
- 근거 문서: S3a 스펙 `docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md` §1·§12 · S3a JSON 계약 `docs/superpowers/specs/2026-10-08-s3a-json-contract.md` · P3 스펙 `docs/superpowers/specs/2026-10-08-p3-mission-definition-versions-design.md` §5·§6·§10 · picasso 운영 관리 화면 설계 제안 `picasso/docs/superpowers/specs/2026-10-07-ops-console-lifecycle-design.md`(서브모듈) §4·§6·§8·§10

## 1. 목적과 범위

운영 관리 화면 설계 제안 §10 의 입증 항목 3 은 임무 하나(PrepareSequencedRack)를 데이터 버전으로 다루는 흐름입니다. 모의 실행(mimic)을 거쳐 활성화하고, 도는 실행 중에 새 버전으로 전환하며, 옛 실행은 옛 버전으로 끝나야 합니다. 입증 항목 4 는 잘못된 변경 하나(신호 사양에 없는 신호 참조)가 활성화에서 거부되고, 그 거부가 부족한 조건과 후속 행동으로 보이는 것입니다(§8.4).

picasso P3 은 그 바탕이 되는 라이브러리를 지었습니다. 정의 스키마, 엄격 파서, 검증기 9종, 메모리 카탈로그, 설비 대기, 실행의 임무 버전 고정이 그것입니다. S3a 는 그 위에 실행 호스트와 작업 지시를 지었습니다. S3b 는 다시 그 위에 버전의 저장·편집·검증·모의 실행·활성화와 화면을 짓습니다.

S3b 가 입증하는 흐름은 다음과 같습니다. 엔지니어가 화면의 «임무·정책» 영역에서 PrepareSequencedRack 정의를 JSON 으로 편집해 초안으로 저장하고, 검증하고, 가상 기체로 모의 실행하고, 사유를 적어 활성화합니다. 활성 버전은 호스트 DB 에 남아 재기동 뒤에도 같은 번호입니다. 도는 실행 중에 새 버전을 활성화하면 새 작업 지시부터 새 버전을 쓰고 옛 실행은 옛 버전으로 끝납니다. 신호 사양에 없는 신호를 참조한 초안은 활성화에서 거부되고, 화면이 부족한 조건과 바로 갈 작업을 보입니다. 설비 대기 노드는 운영 영역의 셀 대역 표에서 신호를 켜면 풀립니다.

앞 문서와 달리 정한 것이 셋 있습니다. 운영 관리 화면 설계 제안 §6 은 «모의 실행을 시험으로 저장해 버전을 올릴 때 다시 돌린다» 이지만, S3b 는 활성화하려는 그 초안의 마지막 모의 실행만 봅니다. 같은 문서 §4 의 «명부는 활성화 권한에 건다» 는 S3b 에 명부가 없어 모드 검사만 합니다. P3 스펙 §10 은 «편집기용 스키마 파일은 S3 에서 정한다» 였고, S3b 는 스키마 파일을 만들지 않기로 정합니다(T4).

S3b 가 입증하지 않는 것은 8가지입니다.

| 입증하지 않는 것 | 내용 |
|---|---|
| 신호 사양의 화면 편집 | 다음 단계 |
| 캔버스 편집기 | 다음 단계(사용자 결정) |
| 운영자 보류 해소와 장애 주입 | S4 |
| 미들웨어 시간값 셋과 인시던트의 현장 설정 버전 | S3c |
| 인시던트에 실린 임무 버전의 화면 표시 | S4, 인시던트를 다룰 때 |
| PrepareSequencedRack 밖의 WorkMaster 편집과 새 WorkMaster | 작업 지시 폼과 호스트가 두 임무로 고정되어 있음 |
| 편집기용 JSON Schema 파일 | picasso 한계 «스키마 파일» 행이 오픈 항목으로 남음 |
| 바닥 소유(floors) 검사 | 호스트가 바닥 소유를 쥐지 않아 늘 통과 |

## 2. 결정 기록

### 2.1 사용자 결정 (2026-10-08)

| 번호 | 주제 | 결정 |
|---|---|---|
| 결정 1 | 모의 실행 | 실행 호스트가 프로세스 안에 별도 mimic 과 가상 시계를 띄워 초안 정의로 작업 지시 하나를 끝까지 돌리고, 결과를 그 초안에 붙여 저장합니다. 활성화 전에 그 초안의 통과한 모의 실행이 있어야 합니다. 현장 기체는 건드리지 않습니다 |
| 결정 2 | 신호 사양 | site 의 셀 대역 픽스처가 신호와 그 사양을 함께 선언합니다(현장 데이터). 실행 호스트가 그것을 읽어 검증합니다. 화면 편집은 다음 단계입니다 |
| 결정 3 | 신호 조작 | 운영 영역의 셀 대역 표에서 신호마다 켜고 끕니다. 사람이 PLC 역할을 하는 정상 조작이며 장애 주입이 아닙니다 |
| 결정 4 | 기한 뒤 동작 | 시연용 버전 2 의 설비 대기는 기한을 넘기면 ABORTED(실행 중단, 기체는 다시 배정 가능)입니다. 운영자 보류와 그 해소 화면은 S4 입니다 |

앞 단계에서 이어지는 결정은 다음과 같습니다.

- 임무 버전 저장은 실행 호스트가 소유합니다(S3a 결정 2: 같은 Postgres 의 자기 스키마, 추가 전용, 검증과 활성화 한 자리).
- 편집은 JSON 편집기이고 캔버스는 다음 단계입니다(10/8 사용자 결정).
- 화면은 앱 하나·역할 모드입니다.
- 초안은 자유롭고 활성화만 관문입니다(운영 관리 화면 설계 제안 §8.1).
- 활성화는 엔지니어가 합니다(§4).

### 2.2 기술 결정 (이 문서가 정함)

| 번호 | 주제 | 결정 | 근거 |
|---|---|---|---|
| T1 | 재기동 복원 | 호스트가 picasso `MissionCatalog` 를 직접 구현해 DB 의 활성 버전 번호를 그대로 `ActiveMission` 에 싣습니다. P3 스펙 §6.1 의 «메모리 카탈로그를 호스트가 쓰고 저장을 붙인다» 에서 벗어납니다 | picasso 의 `InMemoryMissionCatalog.activate` 는 번호를 지정할 수 없고 재기동하면 1부터 셉니다. `MissionDefinitionParser`·`MissionValidator`·`DefinedCapability` 는 공개라 picasso 변경이 필요 없습니다 |
| T2 | 활성화 자리 | 호스트 잠금 아래에서 합니다. 잠금 순서는 «호스트 잠금 → DB» 한 방향뿐이고, 초안 저장·모의 실행은 DB 연결이나 트랜잭션을 쥔 채 호스트 잠금을 기다리지 않습니다 | 판정과 배정 사이에 활성화가 끼면 한 제출 안에서 판정 계획과 실행 계획의 버전이 갈립니다. 반대 방향이 섞이면 요청이 몰릴 때 연결 풀이 바닥나 교착이 됩니다 |
| T3 | 저장 | 호스트 자기 스키마 `mission`, Flyway 위치 `db/mission`, Boot 의 Flyway 자동설정은 끈 채로 둡니다 | 운영 서비스 `OpsSchema` 관례를 따릅니다. 표 셋(초안, 모의 실행, 버전) 모두 추가 전용(트리거)입니다 |
| T4 | 편집기 | 새 의존 없는 textarea 와 서버 검증입니다. 편집기용 JSON Schema 파일은 만들지 않습니다 | 파서가 중복 키와 칸 문제를 JSON 경로로 한 번에 돌려줍니다(문법 오류는 첫 자리에서 멈춥니다). 비교는 활성 버전과 나란히 보이기로 합니다. 스키마 파일을 만들면 picasso 쪽에 파서와 같은 규칙인지 대조하는 시험이 따라야 합니다 |
| T5 | 활성화 행위자 | 엔지니어 모드만, 사유 필수입니다 | 운영 서비스 조작 기록에 남깁니다 |
| T6 | 편집 대상 | PrepareSequencedRack 하나입니다. 정의 안의 `workMasterId` 가 경로의 WorkMaster 와 다르면 UNREADABLE(경로 `$.workMasterId`)로 거부합니다 | 작업 지시 폼과 호스트가 두 임무로 고정이고, 요구 근거 E2 도 고정입니다. 검증기는 `workMasterId` 를 보지 않아 다른 WorkMaster 의 카탈로그가 바뀔 수 있습니다 |
| T7 | 검증 입력 | 신호 사양은 셀 대역 스냅숏의 신호 목록, 현장 스킬(`siteSkills`)은 운영 서비스가 넘긴 시운전 완료 기체들의 케이퍼빌리티 합, 바닥 소유는 `FloorOwnership.None` 입니다. 신호 사양이나 기체 하나의 케이퍼빌리티라도 모르면(스냅숏 없음, 못 물어봄) 거부 목록이 아니라 «못 읽음» 으로 응답합니다 | 호스트가 바닥 소유를 쥐지 않습니다. 모름을 없음으로 접으면 엉뚱한 SKILL_NOT_ON_SITE 가 납니다(3값 원칙) |
| T8 | 모의 실행 현장 | 이상적 현장입니다. 목적지 슬롯은 처음부터 점유이고 기대 자재를 내며(관측 시각 null, 읽은 순간), 이름 있는 신호는 대기 노드의 기대 값을 냅니다. 기체 실패 모드는 없습니다(모의 실행용 프로파일은 현장 humanoid 프로파일에서 실패 모드를 뺀 사본) | 모의 실행은 정의가 끝까지 도는지를 보며 현장 사실을 보증하지 않습니다(운영 관리 화면 설계 제안 §9) |
| T9 | 재조회 키 | 운영 서비스가 요청 id 를 호스트에 넘기고 호스트가 초안·모의 실행·버전 행에 저장합니다. 응답이 없으면 그 요청 id 로 다시 찾습니다. 신호 조작은 셀 대역 값을 다시 읽어 대조합니다 | S3a 의 재조회 키(작업 지시 id)에 해당하는 것이 초안 저장 전에는 없습니다 |
| T10 | 요청 제한 | 모의 실행 요청만 운영 서비스의 호스트 요청 제한을 따로 길게 두고(60초), 호스트의 모의 실행은 그보다 짧은 실제 시간 상한(30초)을 둡니다 | 운영 서비스의 호스트 요청 제한은 5초이고 모의 실행은 요청 안에서 동기로 돕니다 |

## 3. 단계와 완료 기준

| 단계 | 산출물 | 완료 기준 |
|---|---|---|
| S3b | picasso-ops PR 하나. picasso 변경 없음 | 통합 시험에서 다음이 모두 통과합니다. PrepareSequencedRack 데이터 버전 1 을 초안 저장 → 검증 → 모의 실행 통과 → 활성화(버전 1). 신호 사양에 없는 신호를 참조한 초안의 활성화가 SIGNAL_NOT_IN_SPEC 로 거부. 버전 1 로 도는 실행(슬롯 S01·S02) 중에 버전 2(랙 도착 대기, 120초, ABORTED)를 활성화하고, 옛 실행이 대기 단위 없이 버전 1 로 종료한 뒤, 새 작업 지시(슬롯 S03·S04)가 버전 2 로 대기하고 셀 대역의 `rack_present` 를 켜면 진행해 종료. `pick_place` 를 가진 기체가 humanoid-01 하나라 버전 1 실행이 도는 동안 둘째 작업 지시는 배정할 수 없으므로 이 순서입니다. 셀 대역은 슬롯을 비우지 않으므로 두 작업 지시가 슬롯 넷을 나눠 씁니다. 대기 단위가 시작된 뒤 신호를 켜기 전에는 가상 시계를 120초 넘게 밀지 않습니다. 이 시나리오는 새 시험 클래스(새 스택)이고, 시드 0 에서 humanoid-01 이 `pick_place` 넷을 실패 모드 없이 끝내는지는 스파이크에서 확인합니다(S3a 의 `JobOrderTest` 처럼 순서가 추첨을 바꿀 수 있음). 호스트를 다시 띄워도 활성 버전 2. Playwright 에서 엔지니어가 화면으로 초안 저장·검증·모의 실행·활성화하면 버전 목록에 그 버전이 보입니다 |

## 4. 확인한 사실

picasso `1e3f4ae`, picasso-ops `d16b19b` 기준입니다.

| 사실 | 위치 |
|---|---|
| `InMemoryMissionCatalog.activate(definitionJson, signals, floors, siteSkills)` 는 `Activated(workMasterId, missionVersion)` 또는 `Refused(refusals)` 를 냅니다. 번호는 WorkMaster 마다 메모리에서 1부터 세고, 거부는 번호를 쓰지 않습니다 | `picasso/src/main/kotlin/dev/picasso/middleware/mission/InMemoryMissionCatalog.kt:10-16,59-84` |
| `MissionCatalog` 는 `active(workMasterId): ActiveMission?` 하나이고, `ActiveMission(capability, missionVersion: Int?)` 입니다 | `picasso/.../middleware/Ports.kt:300,326` |
| `MissionRefusal(kind, nodeId, observed, expected, checkedAt, basisVersion)` 와 파생 칸 `owner`·`nextAction`, 종류 9개입니다. 파싱 실패 문제는 JSON 경로로 옵니다 | `picasso/.../mission/MissionValidator.kt:22-74`, `StrictJson.kt:15` |
| `SignalSpec(name, location, kind: BOOLEAN\|TEXT, safety)` 입니다 | `picasso/.../mission/SignalSpec.kt:13-21` |
| `DefinedCapability(definition).plan(order)` 는 검증을 거친 정의만 받습니다 | `DefinedCapability.kt:33-46` |
| 실행은 생성 때 쥔 임무 버전으로 끝나고 새 활성화는 새 작업 지시부터 적용됩니다. picasso 시험 `MissionVersionScenarioTest` 가 입증합니다 | `Middleware.kt:168-172,299-320,361-370` |
| picasso 시험 픽스처 `MissionFixtures` 가 PrepareSequencedRack 의 데이터 버전(버전 1 모양)과 랙 도착 대기를 둔 버전(버전 2 모양, 신호 `rack_present`, 대기 노드 `rack-arrival`), 신호 사양 셋(`rack_present` BOOLEAN 자리 있음, `guard_closed` BOOLEAN 안전, `lot_code` TEXT)을 듭니다. 시험 소스라 picasso-ops 가 쓸 수 없어 사본을 둡니다 | `picasso/src/test/.../mission/MissionFixtures.kt:13-75` |
| 호스트는 지금 DB 가 없고 JDBC·Flyway 자동설정을 이름으로 끕니다. `PICASSO_DB_*` 환경 변수는 이미 받습니다 | `mission-host/.../MissionHostApplication.kt:22-27`, 루트 `build.gradle.kts:49-53` |
| 셀 대역 `CellFixture`·`CellSnapshot` 에 신호 칸이 없고 `GET /cell` 만 있습니다. 호스트의 `CellBandSignals` 는 `signal(name)` 을 구현하지 않습니다 | `site/.../SiteCell.kt:19-43,98-150`, `mission-host/.../cell/CellBand.kt:66-89` |
| 운영 서비스 거부 카드 `Finding`(kind, observed, expected, checkedAt, owner, inScreen, action, target, basisVersion)은 `MissionRefusal` 과 칸이 거의 같습니다 | `ops-service/.../finding/Finding.kt:20-30`, 화면 `FindingCard` |
| 화면의 «임무·정책» 영역은 `ready: false` 입니다. 초안 → 시험 → 활성화 화면의 선례는 리비전 목록입니다 | `ui/src/areas.ts:13`, `ui/src/components/ProfilesSection.tsx:45-110` |
| 모의 실행 선례는 picasso 시험의 `Harness` + `InMemoryMissionCatalog` + `Middleware` 를 가상 시계로 돌리는 방식입니다(진행 루프 pump → advance → 짧은 sleep → pump). 이 조합은 시험 소스에만 있고, harness 모듈의 리비전 시험 실행기는 Middleware 를 쓰지 않습니다 | `picasso/src/test/.../MissionVersionScenarioTest.kt:47-55`, `harness/.../Harness.kt:35-37` |
| 운영 서비스의 호스트 요청 제한은 5초입니다 | `ops-service/.../host/HostClient.kt:194` |
| `pick_place` 를 가진 기체는 humanoid-01 하나이고, 호스트 판정은 도는 실행이 있는 기체를 통과시키지 않습니다 | `site/robots.json`, `mission-host/.../MissionHost.kt:248,256` |
| 검증기는 정의의 `workMasterId` 를 보지 않습니다 | `picasso/.../mission/MissionValidator.kt:98-112` |
| 화면의 `FindingCard` 는 `target` 을 기체 상세 링크로 그립니다. `Finding` 의 해결 담당은 SITE·OPERATOR·ENGINEER·NONE 넷과 `inScreen` 이고 `basisVersion` 은 `Long?` 입니다 | `ui/src/components/FindingCard.tsx:31-37`, `ops-service/.../finding/Finding.kt` |

## 5. site: 신호 사양과 이름 있는 신호

결정 2 에 따라 셀 대역 픽스처가 신호와 그 사양을 함께 선언합니다.

### 5.1 픽스처

`CellFixture` 에 신호 목록을 더합니다. 신호마다 이름, 자리(선택), 종류(BOOLEAN·TEXT), 안전 여부, 처음 값을 둡니다. 표준 픽스처는 다음 셋입니다.

| 이름 | 종류 | 자리 | 안전 | 처음 값 |
|---|---|---|---|---|
| `rack_present` | BOOLEAN | `RACK-204` | 아님 | `false` |
| `guard_closed` | BOOLEAN | 없음 | 안전 | `true` |
| `lot_code` | TEXT | 없음 | 아님 | 스파이크에서 정함 |

### 5.2 스냅숏

`GET /cell` 의 스냅숏에 신호 목록(이름, 자리, 종류, 안전, 값, 관측 시각)을 더합니다. 기존 칸은 그대로이므로 지금 호출자와 호환됩니다.

### 5.3 신호 조작

루프백 `POST /cell/signals/{name}` 의 본문은 `{"value": "..."}` 입니다. 값을 바꾸고 관측 시각을 지금 가상 시각으로 둡니다. BOOLEAN 은 `true`·`false` 만 받습니다. 모르는 이름은 404, 틀린 값은 400, 안전 신호는 쓰기를 거부합니다(오류 이름과 상태 코드는 S3b JSON 계약). 실제 안전 PLC 를 소프트웨어에서 쓸 수 없는 것과 같게 현장(PLC 대역)이 집행합니다(ADR 32). 잠금(`MimicServer.exclusive`) 아래에서 처리합니다. 응답은 바뀐 신호 하나입니다. 신호 값은 종류와 상관없이 늘 문자열입니다(`"true"`). picasso `NamedSignal.value` 가 문자열입니다.

## 6. 실행 호스트: 저장, 카탈로그, 검증, 모의 실행, 활성화

### 6.1 저장 (T3)

스키마는 `mission`, Flyway 위치는 `classpath:db/mission`, 마이그레이션 적용은 `HostSchema` 가 맡습니다(운영 서비스 `OpsSchema` 관례). DataSource 자동설정 제외는 풀고 Flyway 자동설정 제외는 유지합니다. main 에 jdbc·flyway·postgresql 의존을 더합니다.

표는 셋이며 모두 추가 전용입니다(UPDATE·DELETE·TRUNCATE 를 막는 트리거, 운영 서비스 V1·V2 관례).

| 표 | 칸 | 비고 |
|---|---|---|
| 초안 | 초안 id, WorkMaster, 정의 문자열, 저장한 사람, 요청 id, 저장 시각 | 초안은 자유롭습니다. 읽을 수 없는 문서도 저장됩니다 |
| 모의 실행 | 모의 실행 id, 초안 id, 통과 여부, 결과(JSON), 요청 id, 시작·끝 시각 | |
| 버전 | WorkMaster, 버전 번호(WorkMaster 마다 1부터), 초안 id, 정의 문자열, 활성화한 사람, 사유, 요청 id, 시각 | 키는 (WorkMaster, 버전) |

정확한 칸 이름과 형은 스파이크에서 정합니다. 시각은 DB 의 `clock_timestamp()` 입니다(운영 서비스 조작 기록과 같음). 호스트 시험은 registry 시험 픽스처의 `PostgresSupport` 로 Postgres 를 붙입니다. 그래서 S3a JSON 계약 공통 규칙의 «JDBC·Flyway 자동설정을 끄므로 한 JVM 에 떠도 된다» 는 «Flyway 자동설정만 끈다» 로 바뀝니다.

### 6.2 카탈로그 (T1)

호스트가 `MissionCatalog` 를 구현합니다. WorkMaster 마다 가장 높은 버전의 정의를 파싱해 `DefinedCapability` 로 들고 `ActiveMission(capability, version)` 을 냅니다. 버전이 없으면 코드 정의(`MissionCatalog.codeCapabilities()`, 버전 null)를 냅니다.

기동 때 DB 에서 읽어 올립니다. 이때 다시 검증하지 않습니다(활성화 때 검증을 지난 정의입니다). 저장된 정의를 파싱하지 못하면 호스트 기동을 멈추고 이유를 남깁니다. 활성화 때는 호스트 잠금 아래에서 `DefinedCapability` 를 먼저 만들고, 버전 행을 넣고, 마지막에 카탈로그를 바꿉니다(T2). 읽을 수 없는 문서를 거부로 바꾸는 부분은 picasso `InMemoryMissionCatalog` 의 그 자리를 그대로 옮깁니다(파서는 문제 문자열 목록만 줍니다).

### 6.3 검증 (T7)

- 입력: 정의 문자열, 기체 id 목록(운영 서비스가 넘긴 시운전 완료 기체).
- 신호 사양: 마지막 셀 스냅숏의 신호 목록.
- 현장 스킬: 그 기체들의 `RobotPort.capabilities` 스킬 합.
- 바닥 소유: `FloorOwnership.None`.
- 출력: 통과 또는 거부 목록(종류, 노드 id, 관측, 기대, 확인 시각, 해결 담당, 바로 갈 작업).

검증 전에 정의 안의 `workMasterId` 와 경로의 WorkMaster 를 대조합니다(T6). 셀 스냅숏이 없으면 «신호 사양을 못 읽음», 기체 하나라도 케이퍼빌리티를 못 물어보면 «현장 스킬을 못 읽음» 으로 응답하고 검증하지 않습니다(T7). 정확한 오류 형식은 S3b JSON 계약에서 정합니다.

### 6.4 모의 실행 (결정 1, T8)

검증을 통과한 초안만 돌립니다. 통과하지 못한 초안은 거부 목록을 그대로 돌려줍니다.

호스트 프로세스 안에 별도 mimic(harness 의 `Harness`, 가상 시계, 기체 하나, 프로파일은 현장 humanoid 기체의 것에서 실패 모드만 뺀 사본)과 별도 미들웨어를 띄웁니다. mimic 엔진 잠금은 인스턴스마다 따로라 현장 mimic(다른 프로세스)·호스트 미들웨어·호스트 잠금과 섞이지 않습니다. 하네스에 적재 지점과 핸드셰이크 보고를 붙이지 않아 registry 에 아무것도 닿지 않습니다. 프로파일과 스키마 경로는 호스트 설정 키로 두고 저장소 루트 기준으로 풉니다(site 의 `SiteConfig` 관례, `:mission-host:run` 은 루트에서 돕니다). 모의 실행용 프로파일은 현장 humanoid 프로파일에서 실패 모드를 뺀 사본입니다(T8). 하네스가 경로를 받으므로 jar 안 리소스가 아니라 저장소 파일 `mission-host/mock-run/humanoid-a.json` 이고 설정 키로 가리킵니다. `:mission-host:run` 이 저장소 루트에서 돌게 하고(site 의 `run` 처럼 `workingDir`), 통합 시험과 호스트 시험은 경로를 실행 인자로 넘깁니다. 모의 실행 미들웨어의 `now` 는 하네스의 가상 시계입니다(어긋나면 E2 시간 윈도우가 갈립니다).

작업 지시는 마지막 셀 스냅숏의 슬롯과 제시 자리로 만든 표본입니다(호스트는 site 모듈의 픽스처를 모릅니다). 요구 근거는 E2, `materialRequirements` 는 자재별 슬롯 수로 관문의 정합 검사를 통과하게 합니다. 슬롯 수와 정확한 모양은 스파이크에서 정합니다.

이상적 현장(T8)으로 돌립니다. 목적지 슬롯은 처음부터 점유이고 기대 자재를 내며 관측 시각은 null(읽은 순간)입니다. 제시 자리는 null(침묵, 관문 통과)입니다. 이름 있는 신호는 대기 노드의 기대 값을 냅니다. 그래서 대기 노드는 첫 pump 에서 완료됩니다. 같은 신호를 기대 값이 다른 두 대기 노드가 쓰면 이상적 값을 하나로 정할 수 없으므로 모의 실행을 실패로 저장합니다. 실패 이유는 하위 범주로 나눕니다: 정의(같은 신호에 다른 기대 값), 정착하지 않음(가상 시간 상한 안에 정착하지 않음), 실제 시간 상한(30초), 실행 실패(단위가 실패나 중단으로 정착).

가상 시간으로 빠르게 돌려 실행이 정착할 때까지 진행합니다(가상 시간 상한은 스파이크에서 정하고 설정으로 줄일 수 있게 둡니다). 통과는 실행이 모든 단위 완료로 정착한 것입니다. 결과(통과, 물리 상태, 단위별 상태와 근거 등급, 가상 경과 시간, 실패면 이유)를 모의 실행 표에 저장합니다.

요청 안에서 동기로 돕니다(실제 시간 몇 초, 상한 30초, T10). 검증 입력(셀 스냅숏, 기체 케이퍼빌리티)을 읽는 동안만 호스트 잠금을 짧게 잡고(`PicassoClient` 의 케이퍼빌리티 캐시가 잠금 밖에서 안전하지 않음), 하네스 실행은 잠금 밖입니다. mimic·harness 를 호스트 main 의존으로 들입니다. 진행 루프의 모양은 스파이크에서 정합니다.

### 6.5 활성화 (T2, T5)

호스트 잠금 아래에서 다음 순서로 합니다.

1. 정의를 다시 검증합니다(지금의 신호 사양과 현장 스킬).
2. 그 초안의 마지막 모의 실행이 통과인지 확인합니다.
3. 버전 번호 = 그 WorkMaster 의 최대 + 1 로 버전 행을 넣고 카탈로그를 바꿉니다.

결과는 넷입니다.

| 결과 | 뜻 |
|---|---|
| ACTIVATED(버전) | 활성화됨 |
| REFUSED(거부 목록) | 검증 거부 |
| MOCK_RUN_REQUIRED | 통과한 모의 실행 없음 |
| INPUT_UNKNOWN | 신호 사양이나 현장 스킬을 못 읽음(T7). 거부가 아니라 «모름» 이며 활성화하지 않음 |

검증과 모의 실행도 같은 «모름» 결과를 냅니다.

### 6.6 REST

루프백이며 호출자는 운영 서비스뿐입니다. POST 는 `application/json` 만 받습니다.

| 메서드와 경로 | 내용 |
|---|---|
| `GET /host/missions/{workMasterId}` | 활성 버전(없으면 코드 정의), 버전 목록, 최근 초안과 각 초안의 마지막 모의 실행 |
| `POST /host/missions/{workMasterId}/drafts` | 초안 저장(정의, 행위자, 요청 id) → 초안 id |
| `POST /host/missions/drafts/{draftId}/validate` | 검증(기체 id 목록) |
| `POST /host/missions/drafts/{draftId}/mock-run` | 모의 실행(기체 id 목록) |
| `POST /host/missions/drafts/{draftId}/activate` | 활성화(행위자, 사유, 기체 id 목록) |
| `GET /host/missions/templates/{workMasterId}` | 시작용 정의 둘. 코드 PrepareSequencedRack 을 옮긴 버전 1 모양의 데이터 정의와, 랙 도착 대기를 둔 버전 2 모양(신호 `rack_present`, 기대 `true`, 기한 120초). picasso `MissionFixtures` 의 사본을 호스트 리소스로 두고, 버전 2 모양은 사본에서 `onDeadline` 만 ABORTED 로 바꿉니다(픽스처 기본값은 OPERATOR_HOLD) |
| `POST /host/cell/signals/{name}` | site 로 전달. site 의 404·400·안전 신호 거부는 그대로, site 불통은 503 |
| `GET /host/missions/requests/{requestId}` | 그 요청 id 로 남은 행(초안·모의 실행·버전)과 그 id. 없으면 404, 아직 처리 중이면 409 `REQUEST_IN_PROGRESS`. 운영 서비스의 재조회용(T9) |
| `GET /host/cell` | S3a 그대로이되 신호 목록이 실립니다 |

셀 대역 클라이언트의 `signal(name)` 은 스냅숏의 신호에서 `NamedSignal(value, observedAt)` 을 냅니다. 스냅숏이 없거나 모르는 이름이면 null 입니다.

WorkMaster 는 PrepareSequencedRack 만 받습니다(T6). 그 밖은 400 입니다.

## 7. 운영 서비스

호스트 REST 를 전달하는 엔드포인트를 둡니다.

| 엔드포인트 | 모드 검사 | 비고 |
|---|---|---|
| `GET /api/missions/{workMasterId}`, 템플릿 읽기 | 없음 | |
| 초안 저장·검증·모의 실행·활성화 | 엔지니어 모드만(운영자 403) | 활성화는 사유 필수(빈 사유 400, 기존 `REASON_REQUIRED`). 모의 실행 요청만 호스트 요청 제한 60초(T10) |
| `POST /api/cell/signals/{name}` | 운영자·엔지니어 두 모드 모두 허용(헤더 없으면 400). 지금 `guarded` 는 모드 하나만 받으므로 두 모드를 받는 변형을 둡니다 | 운영 영역의 조작. 안전 신호는 화면에 버튼이 없고, 쓰기는 현장 대역이 거부하며 운영 서비스·호스트는 그 거부를 그대로 전달합니다(ADR 32: 안전 계통은 소프트웨어 계층에 통합하지 않음) |

검증·모의 실행·활성화에는 시운전 완료 기체 id 목록을 붙여 보냅니다(현장 스킬 계산용).

조작 기록은 초안 저장, 모의 실행, 활성화, 신호 조작을 남깁니다. 대상 문자열은 WorkMaster(초안 저장·모의 실행·활성화)와 신호 이름입니다. 응답이 없으면 `GET /host/missions/requests/{requestId}` 로 재조회합니다(T9). 호스트가 그 요청을 아직 처리 중이면(잠금을 기다리는 활성화, 도는 모의 실행) 재조회는 «없음» 이 아니라 409 `REQUEST_IN_PROGRESS` 이고, 운영 서비스는 «확인 못 함» 으로 둡니다. 재조회가 처리보다 먼저 와서 «반영 안 됨» 을 남기는 경합을 막습니다(S3a 의 공정 잠금과 같은 목적). 신호 조작은 `GET /host/cell` 의 값을 다시 읽어 대조합니다. 활성화 결과 매핑은 다음과 같습니다(S3a §8 관례).

| 호스트 결과 | 조작 기록 |
|---|---|
| ACTIVATED | SUCCEEDED |
| REFUSED, MOCK_RUN_REQUIRED, INPUT_UNKNOWN | REJECTED(호스트 본문에 결과 이름이 남음. 화면은 INPUT_UNKNOWN 을 거부 카드가 아니라 «모름» 으로 보임) |
| 호스트 불통 | NO_RESPONSE 와 재조회 |

호스트의 거부 목록은 화면의 `Finding` 모양으로 옮깁니다. 칸의 뜻이 달라 다음처럼 옮깁니다. `target` 은 null(화면이 기체 상세 링크로 그리므로), 노드 id 는 관측값 앞에 «노드 <id>:» 로 싣습니다. 해결 담당은 ENGINEER → ENGINEER(`inScreen` 참), OUTSIDE_CONSOLE → SITE(`inScreen` 거짓). `basisVersion` 은 `Long` 으로, `checkedAt` 은 호스트가 검증한 시각입니다. 거부 9종의 화면 이름을 화면의 종류 이름표에 더합니다.

## 8. 화면

«임무·정책» 영역을 엽니다(`ready: true`).

- WorkMaster 하나(PrepareSequencedRack)를 다룹니다. 활성 버전(없으면 «코드 정의»)과 버전 이력(번호, 활성화한 사람, 사유, 시각)을 보입니다.
- 편집기는 textarea 입니다. 처음 내용은 활성 버전의 정의 또는 템플릿입니다. 템플릿 둘을 불러오는 버튼을 둡니다. 활성 버전과 나란히 보는 비교를 둡니다.
- 버튼은 초안 저장, 검증, 모의 실행, 활성화(사유 입력)입니다. 엔지니어 모드에서만 보이고 운영자 모드에는 «임무 편집은 엔지니어 모드에서 합니다» 를 보입니다.
- 검증·활성화 거부는 거부 카드(`FindingCard`)로 보입니다. 모의 실행 결과는 단위별 표와 통과 여부로 보입니다.
- 운영 영역의 셀 대역 표에 신호 목록(이름, 값, 관측 시각)을 더합니다. 안전이 아닌 BOOLEAN 신호는 켜기·끄기 버튼을 둡니다(두 모드). 안전 신호는 값만 보입니다. 스냅숏이 없으면 신호를 «모름» 으로 보입니다.
- 실행 목록의 임무 버전 칸은 S3a 그대로입니다(null 이면 «코드 정의», 아니면 «버전 N»).

## 9. 오류 처리

| 상황 | 보이는 것 |
|---|---|
| 호스트가 안 닿음 | 임무 영역 읽기는 503 과 직전 값. 저장·모의 실행·활성화는 NO_RESPONSE 기록과 재조회 |
| 셀 대역이 안 닿음(스냅숏 없음) | 검증·모의 실행·활성화 불가(신호 사양을 못 읽음). 이름 있는 신호가 null 이라 대기 노드는 기한까지 기다림 |
| 기체 케이퍼빌리티를 못 물어봄 | 검증·모의 실행·활성화 불가(현장 스킬을 못 읽음, INPUT_UNKNOWN) |
| 안전 신호를 쓰려 함 | 현장 대역이 거부, 화면에는 버튼이 없음 |
| 정의의 WorkMaster 가 경로와 다름 | UNREADABLE(`$.workMasterId`) |
| 저장된 버전을 기동 때 파싱하지 못함 | 호스트 기동을 멈추고 이유를 남김 |
| 읽을 수 없는 초안 | 저장은 되고 검증이 UNREADABLE 과 JSON 경로 목록 |
| 통과한 모의 실행 없이 활성화 | MOCK_RUN_REQUIRED |
| 신호 사양에 없는 신호 | SIGNAL_NOT_IN_SPEC 거부 카드(해결 담당 엔지니어, 바로 갈 작업) |
| 모의 실행이 상한 안에 정착하지 않음 | 실패로 저장(이유: 정착하지 않음) |
| 대기 기한을 넘김(버전 2) | 실행 ABORTED, 작업 응답에 SIGNAL_DEADLINE, 기체 다시 배정 가능 |

## 10. 시험

| 무엇 | 어디서 | 결함 주입 |
|---|---|---|
| 신호 픽스처와 스냅숏 칸, `POST /cell/signals` 의 값 검사·404·400·안전 신호 거부, 관측 시각 | site | BOOLEAN 값 검사 제거, 관측 시각 고정, 안전 신호 거부 제거 |
| 추가 전용 트리거, 재기동 뒤 같은 활성 버전 번호, 버전 없으면 코드 정의, 정의의 WorkMaster 대조 | 호스트 저장·카탈로그 | 기동 때 DB 를 안 읽음, 번호를 최대+1 이 아니게, WorkMaster 대조 제거 |
| SIGNAL_NOT_IN_SPEC, MOCK_RUN_REQUIRED, 활성화 뒤 새 제출만 새 버전 | 호스트 검증·활성화 | 모의 실행 관문 제거, 활성화를 잠금 밖에서(스파이크가 잡을 수 있으면), 신호 사양을 스냅숏 아닌 빈 목록으로 |
| 버전 1 모양 통과, 대기 노드 버전 2 모양 통과(이상적 현장), 가상 시간 상한을 줄였을 때 정착하지 않음으로 실패, 같은 신호에 다른 기대 값 실패 | 호스트 모의 실행 | 이상적 셀을 비움 |
| 엔지니어 모드만 403, 사유 필수 400, 결과 매핑(INPUT_UNKNOWN 포함), 요청 id 재조회, 거부 카드 변환 | 운영 서비스 | 모드 검사 제거, 사유 검사 제거, 재조회 대조 제거 |
| 엔지니어·운영자 분기, 거부 카드 표시, 모의 실행 결과 표, 신호 켜기·끄기, 버전 이력 | 화면 | 3~5건 |
| §3 S3b 완료 기준 전부. E2eStack 은 `mission` 스키마도 DROP 하고(트리거가 DELETE·TRUNCATE 를 막음) 호스트에 데이터 소스를 넘깁니다. 호스트만 다시 띄우는 길을 두되 같은 포트로 띄웁니다(운영 서비스는 기동 때 호스트 주소를 한 번 받음). 호스트에 모의 실행 프로파일·스키마 경로를 넘깁니다. 호스트 시험(`HostBench`)도 띄울 때마다 `mission` 스키마를 DROP 합니다 | 통합(e2e) | 셀 대역 신호 조작 무시(버전 2 가 기한까지 기다림) |
| §3 의 화면 단계 | Playwright | |

결함 주입은 시험 결과 XML(vitest 는 junit)의 실패 시험 이름으로 판정합니다.

## 11. 한계와 다음 단계

- 신호 사양은 셀 대역 픽스처의 코드 상수입니다. 화면 편집과 버전은 다음 단계입니다.
- 모의 실행은 이상적 현장을 가정합니다. 현장 사실(신호가 실제로 오는가, 자재가 맞는가)을 보증하지 않습니다.
- 바닥 소유를 검사하지 않습니다(`FloorOwnership.None`).
- 편집기용 JSON Schema 파일이 없습니다(picasso 한계 «스키마 파일» 행이 오픈 항목으로 남음).
- PrepareSequencedRack 하나만 편집하며 새 WorkMaster 는 없습니다.
- 모의 실행이 요청 안에서 동기로 돕니다(실제 시간 몇 초, 상한 30초 동안 요청이 열립니다).
- 모의 실행은 활성화하려는 초안의 마지막 결과만 봅니다. 버전을 올릴 때 앞 버전들의 모의 실행을 다시 돌리지 않습니다.
- 활성화 권한은 모드 검사뿐입니다. 명부(운영 관리 화면 설계 제안 §4)는 없습니다.
- 신호는 값, 관측 시각, «모름» 만 보입니다. 운영 관리 화면 설계 제안 §9 의 신호 품질(응답 없음, 단절, 불확실) 표시는 다음입니다.
- picasso 한계 «스키마 파일» 행의 해소 조건(«S3 에서 정함»)은 S3b 가 «만들지 않음» 으로 정해 낡습니다. picasso 변경은 이 범위 밖이므로 그 사실을 picasso 쪽에 넘길 메모로 남깁니다.
- 인시던트의 임무 버전 화면 표시와 운영자 보류 해소는 S4 입니다. 미들웨어 시간값 셋과 인시던트의 현장 설정 버전은 S3c 입니다.
- 다음은 S3c, S4, 그 뒤 캔버스 편집기와 신호 사양 편집입니다.
