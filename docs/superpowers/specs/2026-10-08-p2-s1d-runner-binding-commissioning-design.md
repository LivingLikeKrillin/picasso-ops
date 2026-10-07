# picasso-ops P2·S1d: 시험 실행기, 개정판과 바인딩, 시운전 설계

- 문서 상태: 설계 초안 (2026-10-08). 결정은 2026-10-07 사용자 결정(§2)
- 범위: picasso 선행 변경 P2a·P2b 와 picasso-ops 의 S1d
- 근거 문서: picasso-ops `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md`(이하 S1 스펙) · picasso `docs/superpowers/specs/2026-09-05-picasso-design.md`(이하 설계 문서) §3.2·§8.3·§8.4·§10·§12 · picasso `docs/commissioning.md` · picasso `docs/superpowers/specs/2026-10-07-ops-console-lifecycle-design.md`(운영 관리 화면 설계 제안)

## 1. 목적과 범위

S1 스펙 §1 은 P2(카탈로그 초기화, 프로파일 개정판 제출·시험·활성화, 바인딩 REST)와 S1d(바인딩, 사이트 명칭, 시운전 완료)를 «다음 설계 문서» 로 넘겼습니다. 이 문서가 그 설계 문서입니다.

S1 은 2026-10-07 에 닫혔습니다(P1 → S1a → S1b → S1c, picasso-ops PR #1~#3). S1 이 입증한 것은 기체의 선언·보고·퇴역·복귀입니다.

S1d 가 입증하는 것은 한 흐름입니다. 엔지니어가 화면에서 기종 프로파일 개정판을 제출합니다. 시험 실행기가 그 개정판을 실제로 시험합니다. 엔지니어가 활성화하고 기체에 바인딩합니다. 사이트 명칭 등록을 기록합니다. 기체가 명칭을 안다고 답하면 그 기체가 «시운전 완료» 가 됩니다. 그 사이에 코드 수정이 없습니다.

지키는 원칙은 S1 과 같습니다. 운영 중 변경은 코드 수정이 아니라 관리 화면에서 처리합니다. picasso 를 고치지 않고, 필요한 변경은 picasso 저장소의 PR 로 냅니다.

S1d 가 입증하지 않는 것은 4가지입니다.

| 입증하지 않는 것 | 내용 |
|---|---|
| «배정 가능» | registry 에도 미들웨어에도 그 개념이 없습니다. 미들웨어는 registry 를 읽지 않고 기체에 능력을 직접 묻습니다. 배정은 미들웨어의 작업 수락이 정하므로 임무가 들어오는 S3 에서 다룹니다(결정 5). S1 스펙 §1 은 «배정 가능» 을 S1d 의 몫으로 적었습니다. 이 문서가 그것을 S3 로 옮깁니다. |
| 바인딩이 기체에 닿는 것 | mimic 은 바인딩을 가져가지 않고 자기 프로파일 파일로 돕니다. registry 는 핸드셰이크를 바인딩과 대조하지 않습니다. 설계 문서 §8.4 ④(기체가 폴링으로 바인딩 변경을 감지)는 구현되지 않았습니다. 그래서 S1d 의 바인딩은 registry 장부의 기록입니다. 기체 쪽 반영은 S3 의 판 전환과 함께 다룹니다(§12). |
| 실물 적합성 | 어댑터 적합성은 `UNTESTED` 로 남습니다(설계 문서 §9.7 ④, C-3 비목표). |
| 사이트 명칭의 오타 | registry 는 기체가 명칭을 1개 이상 아는지만 대조합니다(picasso 한계 §15.129). 요구 키가 2개여도 기체가 이름 1개를 알면 통과합니다. |

## 2. 결정 기록

2026-10-07 사용자 결정 8건입니다.

| 결정 | 내용 |
|---|---|
| 1. 시험 단계 | 진짜 실행기를 짓습니다. 사람이 화면에서 PASS 를 적는 길은 택하지 않았습니다. 실행기가 없다는 사실을 한계로 두지 않고 picasso 에 짓습니다. |
| 2. NEGATIVE 의 뜻 | 프로파일이 못 한다고 적은 요청이 정해진 거절 코드로 거절되는지 봅니다. 근거는 설계 문서 §10.4 ③ «프로토콜 한계 집행» 과 §12.2 #7 입니다. picasso 용어집의 «네거티브 테스트» 는 게이트 역검증(`gate/negative/`)이라 개정판 시험과 다릅니다. |
| 3. 카탈로그 초기화 | registry 기동 때 합니다. 설계 문서 §8.3 ④ 가 «계약 메타데이터를 초기 구동 시 upsert 한다» 고 이미 정했습니다. 코드가 그것을 하지 않았을 뿐입니다. |
| 4. 실행기의 토큰 | 적재 토큰입니다. 기계가 관측해 보고하는 길이라 기체·어댑터와 같은 문을 씁니다. 운영 서비스는 적재 토큰이 없으므로 화면에서 시험 결과를 적을 길이 없습니다. «실행기만 결과를 적는다» 를 토큰 경계가 지킵니다. |
| 5. «배정 가능» | S1d 에서 빼고 S3 로 옮깁니다. |
| 6. 시운전 완료 판정 | 세 조건이 모두 맞아야 합니다. 원장 상태가 `CONFIRMED` 이고 퇴역이 아닙니다. 활성 바인딩이 있습니다. 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 입니다. 소프트웨어 대조(`/diag/software`)와 어댑터 적합성은 막지 않고 보이기만 합니다. |
| 7. 나눔과 순서 | P2a(시험 실행기) → P2b(개정판·바인딩 REST) → S1d 순서입니다. P2a 를 먼저 하는 것은 가장 큰 미지수이기 때문입니다. P2a·P2b 는 picasso PR 2개, S1d 는 picasso-ops PR 1개입니다. |
| 8. 실행기 설계 | 흐름(§5), 시험 3종의 내용, 파라미터 생성, 완주 판정, 자리를 §5 그대로 둡니다. |

## 3. 단계와 완료 기준

| 단계 | 들어가는 것 | 완료 판정 |
|---|---|---|
| P2a 시험 실행기 | registry: 시험 요청에 «끝남» 칸(마이그레이션 1개), 운영자 REST «시험 요청», 적재 토큰 뒤 «요청 집기»·«결과 보고» REST. harness: 운영 코드의 실행기와 시험 3종. ADR 49 | registry 와 실행기를 함께 띄운 통합 시험에서 `humanoid-a`·`quadruped-b` 개정판이 요청 → 집기 → 3종 PASS → `TESTED` 로 갑니다. 스위트마다 결함 주입이 그 스위트를 FAIL 로 만듭니다. FAIL 이 하나라도 있으면 개정판은 `VALIDATED` 에 머물고 활성화가 거절됩니다. `DocumentClaimsTest` 가 통과합니다 |
| P2b 개정판·바인딩 REST | registry 기동 때 카탈로그 동기화, 스킬 종류 조회, 개정판 제출·목록, 활성화, 바인딩 REST, 바인딩의 막는 장치, `/diag/bindings` 행에 빌드 id·바인딩한 이·명칭 기록과 보고 칸, mimic 기동 경로에서 기체가 아는 명칭 넣기, `commissioning.md` 갱신 | 표면 시험이 응답 코드를 전부 검증합니다. 기존 시험이 고치지 않고 통과합니다. `DocumentClaimsTest` 가 통과합니다 |
| S1d 바인딩·명칭·시운전 | 서브모듈 이동, `site/` 의 실행기 기동과 명칭 티칭 흉내, 운영 서비스의 조작 5종과 거절 대응, 시운전 판정과 막힘 4종, 화면의 «프로파일» 구역과 상세 카드 3개 | 통합 시험과 Playwright 에서 제출 → 시험 요청 → 실행기 3종 PASS(`TESTED`) → 활성화 → 바인딩 → 명칭 기록 → 시운전 완료가 돕니다. 명칭을 티칭하지 않은 기체는 `SITE_NAMES_CONTRADICTED` 로 막히고, 현장에서 다시 티칭하면 풀립니다(통합 시험). 거절은 대응표(§8)대로 보입니다 |

판은 S1 처럼 서브모듈 포인터가 정합니다. S1d 의 첫 작업이 서브모듈을 P2b 머지 커밋으로 옮기는 것입니다.

## 4. picasso 에서 확인한 사실

아래 사실은 picasso 2026-10-07 main `6b1a255`(서브모듈이 가리키는 커밋)에서 확인했습니다. P2a·P2b 가 이 가운데 무엇을 바꾸는지는 «P2 에서» 칸에 적습니다.

| 사실 | 근거 | P2 에서 |
|---|---|---|
| 카탈로그 동기화(`SkillTypeSync`)를 부르는 곳이 시험 소스뿐입니다. 그래서 운영 registry 의 `skill_type` 은 비어 있습니다 | `registry/.../revision/SkillTypeSync.kt` | P2b 가 기동 때 부릅니다 |
| `skill_type` 이 비면 개정판 제출이 선언 스킬을 조용히 건너뜁니다. 그 결과 사이트 명칭 요구 집합이 비고, 바인딩의 계약 semver 검사도 비교할 것이 없습니다. 주석의 «그 부재는 바인딩이 보고 막는다» 는 사실이 아닙니다 | `registry/.../revision/RevisionService.kt` 의 `insertSkills`, `binding/BindingService.kt` 의 `requiredSemvers` | P2b 의 기동 동기화로 닫고 주석을 고칩니다 |
| 개정판 제출·시험 결과 기록·활성화·바인딩에 REST 가 없습니다. `RevisionService`·`TestRequestService` 는 빈(bean)도 없습니다 | `registry/.../web/*.kt`, `web/RegistryApplication.kt` | P2a·P2b 가 엽니다 |
| 시험 스위트 3개(`CONTRACT`·`NEGATIVE`·`DETERMINISM`)는 이름뿐입니다. `harness` 의 `Suite` 열거형을 쓰는 코드가 없고, 요청을 집어 가는 폴러도 없습니다. 마이그레이션 주석이 «폴링 고리(harness 쪽)는 3a-2다» 로 미뤄 두었습니다 | `harness/.../ContractSuite.kt`, `registry/src/main/resources/db/migration/V2__testing.sql` | P2a 가 짓습니다 |
| 시험 요청에 «끝남» 칸이 없습니다. 집은 요청은 15분 만료 뒤 다시 집히므로, 폴러가 있었다면 같은 요청을 끝없이 다시 돌렸을 것입니다 | `V2__testing.sql`, `testing/TestRequestService.kt` | P2a 가 칸을 더합니다 |
| `recordTestRun` 은 요청 id 와 상세 칸을 채우지 않습니다 | `BindingService.kt` | P2a 의 보고가 채웁니다 |
| 바인딩은 없는 기체를 FK 위반 500 으로 냅니다. 퇴역 기체도 바인딩합니다. 같은 조합의 재바인딩은 새 행을 만들어 멱등이 아니며, 사이트 명칭 등록 기록이 바인딩 행에 있어 «미등록» 으로 돌아갑니다. 동시 첫 바인딩은 유일 색인 위반 500 입니다 | `BindingService.kt` 의 `bind`, `V1__core.sql` 의 `robot_binding_one_active`, `V10__site_names.sql` | P2b 가 막습니다 |
| 사이트 명칭 기록 REST 는 이미 있습니다. 활성 바인딩이 없으면 404, 등록할 명칭이 없으면 409 입니다 | `web/OperationsController.kt`, `binding/SiteNameRegistration.kt` | 그대로 씁니다 |
| 사이트 명칭 상태는 5값입니다(`NOT_REQUIRED`·`UNREGISTERED`·`CLAIMED`·`CONFIRMED`·`CONTRADICTED`). 사람의 기록과 기체의 답(생존 보고에 실리는 명칭 개수)을 함께 봅니다. `CONFIRMED` 는 기록이 있고 기체가 이름 1개 이상을 안다고 답한 것입니다 | `SiteNameRegistration.kt` 의 `statusOf` | 그대로 씁니다. `/diag/bindings` 행의 KDoc 이 3값으로 적은 것은 P2b 가 고칩니다 |
| 요구 명칭은 바인딩한 개정판의 스킬에서 유도됩니다. `humanoid-a` 는 `destination`·`location`, `quadruped-b` 는 `location` 입니다 | `SiteNameRegistration.kt` 의 `requiredIn`, `contracts/proto/picasso/v1/skill_catalog.proto` 의 `is_site_reference` | 그대로입니다 |
| mimic 의 기체가 아는 명칭 기본값은 빈 목록입니다. 그래서 그대로 명칭을 기록하면 `CONTRADICTED` 가 됩니다. `MimicCli.start` 경로로는 이 값을 바꿀 수 없습니다(제어 서버를 띄우지 않고, `Started` 가 기체 인스턴스를 내지 않습니다) | `mimic/.../RobotInstance.kt`, `mimic/.../cli/Main.kt` | P2b 가 길을 냅니다 |
| 퇴역은 바인딩을 풀지 않습니다. 그래서 `GET /catalog` 의 가용 기체 수에 퇴역 기체가 들어갑니다. `commissioning.md` 는 퇴역하면 «가용 자원 목록» 에서 빠진다고 적어 코드와 다릅니다 | `binding/RobotRegistration.kt`, `catalog/SiteCatalog.kt`, `docs/commissioning.md` | 고치지 않습니다. S1d 는 `/catalog` 를 읽지 않습니다(§12 후보). 문서 문장은 코드에 맞춥니다 |
| 핸드셰이크는 클라이언트가 `Negotiate` 를 부를 때만 보고됩니다. 어댑터 빌드 칸이 없고, registry 는 바인딩과 대조하지 않습니다. mimic CLI 경로는 바인딩을 가져가지 않습니다 | `uplink/.../report/HandshakeReporter.kt`, `registry/.../ingest/HandshakeIngestService.kt`, `mimic/.../transport/RobotRegistry.kt` | 고치지 않습니다(§12) |
| 미들웨어의 작업 수락(`Middleware.admits`)은 registry 의 카탈로그·바인딩·생존·퇴역을 읽지 않습니다 | `picasso/src/main/.../AdmissionGate.kt` | 해당 없음(결정 5) |
| 계약 semver 는 `0.9.0` 이며 `ContractIdentity.semver` 가 읽습니다. 계약 기술자 `picasso.desc` 는 `contracts` jar 안에 있어 picasso-ops 의 포함 빌드에서도 읽힙니다 | `contracts/build.gradle.kts`, `contracts/.../wire/ContractIdentity.kt` | P2b 의 기동 동기화가 씁니다 |
| `Harness` 는 프로파일을 파일 경로로만 받습니다. 스키마 검사를 건너뛰지 않으려는 것입니다 | `harness/.../Harness.kt` | P2a 는 후보 문서를 임시 파일로 써서 넘깁니다 |
| 투영 함수는 `CapabilityProjection.of` 입니다. `harness` 운영 코드는 `:capability` 에 의존하지 않습니다 | `capability/.../CapabilityProjection.kt`, `harness/build.gradle.kts` | P2a 가 의존을 더합니다 |
| 같은 JVM 에서 mimic 을 두 번 띄우면 `session_id` 와 그것을 잇는 `event_id` 가 다릅니다. JVM 전역 카운터가 들어가기 때문입니다 | `mimic/.../RobotInstance.kt`, `transport/EventStream.kt` | P2a 의 DETERMINISM 이 비교 전에 정규화합니다 |
| `harness` 운영 코드는 `:registry` 에 의존하지 않습니다. 설계 문서 §3.2 가 `harness ⇢ registry` 를 런타임 접근으로 두었기 때문입니다 | `harness/build.gradle.kts` 주석 | 지킵니다. 실행기는 HTTP 로만 registry 에 닿습니다 |

## 5. P2a: 시험 실행기

설계 문서 §3.2 순환 방지 규칙 1 이 모양을 정했습니다. registry 는 harness 를 부르지 않습니다. 시험 요청은 `revision_test_request` 행으로 쌓이고, harness 가 폴링으로 집어 가서 돌리고 결과를 보고합니다(설계 문서 §8.4 ②). P2a 는 이 미룬 자리(3a-2)를 짓습니다.

### 5.1 흐름

흐름은 다음과 같습니다.

1. 엔지니어가 시험을 요청합니다. `POST /operations/profile-revisions/{id}/test-requests` 이며, 운영자 토큰과 `X-Actor` 가 필수입니다. `DRAFT`·`REVOKED` 개정판은 409, 없는 개정판은 404 입니다. 끝나지 않은 요청이 이미 있으면 그 요청을 200 으로 돌려주고, 새로 만들면 201 입니다(멱등). 감사 `TEST_REQUEST` 는 기존 그대로입니다.
2. 실행기가 요청을 집습니다. `POST /ingest/test-requests/claim` 이며, 적재 토큰을 쓰고 본문에 실행기 이름을 넣습니다. 답은 요청 id, 개정판 id, 집은 시각(`claimed_at`), 후보 문서(JSON)입니다. 집을 요청이 없으면 204 입니다. 끝난 요청은 집지 않습니다. 집는 규칙(가장 오래된 것, `FOR UPDATE SKIP LOCKED`, 15분 만료)은 기존 `TestRequestService.claim` 그대로입니다.
3. 실행기가 후보 문서를 임시 파일로 써서 기존 `Harness` 에 넘깁니다. 이 경로는 스키마 검사를 그대로 거칩니다. mimic 은 프로세스 안(in-process), 가상 시계, 고정 시드로 뜹니다. 시험 3종을 돕니다.
4. 실행기가 결과 셋을 한 번에 보고합니다. `POST /ingest/test-requests/{requestId}/results` 이며, 적재 토큰을 쓰고 본문은 실행기 이름, 그 집은 시각(`claimed_at`), 스위트 3개의 결과(PASS/FAIL)와 상세입니다. registry 는 요청의 집은 실행기와 집은 시각이 둘 다 같고 아직 끝나지 않은 요청에만 결과를 받습니다. 같은 이름으로 다시 뜬 실행기가 다시 집은 요청에 죽은 실행기의 늦은 보고가 섞이지 않게 하려는 것입니다. 만료 시각이 지났어도 다른 실행기가 다시 집기 전이면 받습니다. 결과는 실제로 돌린 것이고, 만료는 막힌 요청을 풀려는 것이지 결과를 무효로 하려는 것이 아니기 때문입니다. registry 는 한 트랜잭션에서 실행 3행을 남기고(요청 id 와 상세 포함) 요청을 끝남으로 표시합니다. 셋 다 PASS 이고 상태가 `VALIDATED` 이면 `TESTED` 로 올립니다(기존 `recordTestRun` 규칙, 감사 `PROFILE_REVISION_TESTED`). 집은 실행기나 집은 시각이 다르거나 이미 끝난 요청이면 409, 스위트 3개가 다 없거나 모르는 값이면 400, 없는 요청이면 404 입니다.
5. 실행기가 보고 전에 죽으면 15분 만료 뒤 다른 실행기가 다시 집습니다.

| API | 토큰 | 결과 |
|---|---|---|
| `POST /operations/profile-revisions/{id}/test-requests` | 운영자 토큰, `X-Actor` 필수 | 201 새 요청. 200 끝나지 않은 요청이 이미 있음(그 요청, 멱등). 404 없는 개정판. 409 `DRAFT`·`REVOKED` 개정판 |
| `POST /ingest/test-requests/claim` | 적재 토큰 | 200 요청 id, 개정판 id, 집은 시각(`claimed_at`), 후보 문서(JSON). 204 집을 요청 없음 |
| `POST /ingest/test-requests/{requestId}/results` | 적재 토큰 | 200 실행 3행 기록과 요청 끝남. 400 스위트 3개가 다 없거나 모르는 값. 404 없는 요청. 409 집은 실행기나 집은 시각이 다르거나 이미 끝난 요청 |

### 5.2 실행기의 오류 처리

| 상황 | 처리 |
|---|---|
| 후보 문서를 `Harness` 가 적재 단계에서 거절함 | 세 스위트를 모두 FAIL 로 보고하고 상세에 거절 사유를 적습니다. 같은 요청이 15분마다 다시 집히지 않고 화면에 FAIL 이 보여야 하기 때문입니다 |
| 태스크가 가상 시간 상한 안에 종착에 닿지 않음 | 그 시나리오를 FAIL 로 적습니다. 상한 값은 P2a 계획에서 정합니다 |
| 스위트 실행 중 예외 | 그 스위트를 FAIL 로 적고 상세에 예외 종류와 메시지를 적습니다 |
| 집기에서 registry 무응답·5xx | 폴링 간격 뒤 다시 집습니다 |
| 집기·보고에서 401 | 토큰 설정 오류로 로그에 남기고 폴링을 계속합니다 |
| 보고의 응답을 못 받음 | 같은 보고를 최대 3번 다시 보냅니다. 다시 보낸 보고가 409 «이미 끝난 요청» 이면 앞 보고가 반영된 것으로 보고 끝냅니다. 3번 모두 실패하면 포기하고, 요청은 만료 뒤 다시 집힙니다 |

### 5.3 스키마 변경

스키마 변경은 마이그레이션 1개(`V16`)입니다. `revision_test_request` 에 `completed_at` 칸 1개를 더합니다. 개정판마다 끝나지 않은 요청이 1개만 있도록 부분 유일 색인 1개를 더합니다. 동시에 두 요청이 와도 열린 요청이 하나로 남게 하려는 것입니다. 집기는 `completed_at IS NULL` 만 봅니다.

### 5.4 시험 3종

시험 3종은 다음과 같습니다.

| 스위트 | 근거 | 확인하는 것 |
|---|---|---|
| `CONTRACT` | 설계 문서 §12.1 «계약 테스트(mimic ↔ client)», §9.7 ③ | (1) 선언한 스킬 전부와 `REQUIRED` 선택 필드로 협상하면 수락됩니다. (2) 능력 조회(`GetCapabilities`)의 능력이 `CapabilityProjection.of(문서)` 와 같습니다(§12.2 #10). (3) 선언한 스킬마다 새 기체에서 태스크가 수락되고 종착에 닿습니다(§12.2 #1). 종착은 성공이거나 프로파일이 선언한 실패입니다 |
| `NEGATIVE` | 설계 문서 §10.4 ③, §12.2 #7 | 프로파일이 못 한다고 적은 것마다 탐침 1개를 보내고, 정해진 코드로 거절되는지 봅니다(아래 표) |
| `DETERMINISM` | 설계 문서 §12.1 «결정론적 검증», 같은 절 «시드와 가상 시계로 동일 이벤트 시퀀스 재현성 100%» | `CONTRACT` (3)의 태스크 시나리오를 같은 시드로 두 번, 각각 새 `Harness` 에서 돕니다. 비교 대상은 이벤트의 종류와 순서, 가상 시각(`occurred_at`), 진행률, 종착 상태입니다. `session_id`·`event_id` 는 비교 전에 정규화합니다(§4) |

NEGATIVE 탐침은 다음과 같습니다.

| 프로파일이 적은 것 | 탐침 | 기대 거절 코드 |
|---|---|---|
| 선언하지 않은 카탈로그 스킬 | 그 스킬로 태스크 시작 | `SKILL_ABSENT` |
| 스킬의 필수 키 | 필수 키를 빼고 시작 | `PARAMETER_INVALID` |
| 숫자 범위(`min_value`·`max_value`) | 범위 밖 값 | `PARAMETER_INVALID` |
| 문자열 `max_length` | 한도를 넘는 문자열 | `PARAMETER_INVALID` |
| ENUM `allowed_values` | 목록 밖 값 | `PARAMETER_INVALID` |
| `cancel_support: NO` | 그 스킬의 태스크 취소 | `CANCEL_UNSUPPORTED` |
| `pause_support: NO` | 그 스킬의 태스크 일시정지 | `PAUSE_UNSUPPORTED` |
| `REQUIRED` 선택 필드 | 그 필드 없이 협상 | `REQUIRED_OPTIONAL_MISSING` |

탐침은 프로파일이 선언한 것에만 보냅니다. `UNKNOWN` 지원은 시도를 허용하므로 탐침을 보내지 않습니다. 필수 키 탐침은 어느 프로파일에나 붙습니다. 카탈로그의 스킬은 모두 필수 키를 갖기 때문입니다. 그래서 검사할 것이 없어 그냥 통과하는 경우는 없습니다. 거절 코드는 계약 `common.proto` 의 `RejectionCode` 입니다.

스위트마다 보고하는 상세는 JSON 이며 `revision_test_run.detail`(JSONB)에 그대로 들어갑니다. 모양은 `{"checks": <돌린 검사 수>, "failures": [{"check": <검사 식별자>, "expected": ..., "observed": ...}]}` 입니다. 검사 식별자는 `CONTRACT.capabilities`, `NEGATIVE.cancel_unsupported:pick_place` 처럼 스위트와 검사와 대상을 잇습니다. PASS 도 `checks` 를 실어 검사가 실제로 돌았음을 보입니다. 화면은 FAIL 이면 `failures` 를 펼쳐 보입니다.

### 5.5 파라미터 생성과 완주 판정

파라미터 생성입니다. `ContractSuite` 에는 «능력을 보고 값을 고르기 시작하면 그것이 곧 기종 분기» 라는 원칙이 있습니다. 같은 클라이언트 코드로 이기종을 다룬다는 원칙입니다(완료 기준 A-1). 실행기는 임의의 프로파일을 시험해야 하므로 계약 카탈로그의 필수 키와 형, 프로파일의 범위·길이·허용 값에서 «최소 유효값» 을 만듭니다. 생성기는 클라이언트가 아니라 시험 데이터를 만드는 쪽이므로 그 원칙과 부딪히지 않습니다. ADR 49 에 이 경계를 적습니다.

완주 판정입니다. `humanoid-a` 의 `pick_place` 는 실패율을 선언합니다(0.05, 0.01). 그래서 «성공» 이 아니라 «종착(성공 또는 선언된 실패)» 을 기준으로 둡니다. 선언된 실패는 계약상 올바른 결과이기 때문입니다. 스킬마다 새 기체에서 돌리는 것은 앞 스킬이 남긴 상태(예: 쥔 물체)가 다음 스킬의 전제를 바꾸지 않게 하려는 것입니다.

### 5.6 자리

실행기 코드는 `harness` 의 운영 코드에 둡니다. registry 에는 HTTP 로만 닿습니다. HTTP 는 `uplink` 와 같은 JDK `HttpClient` 입니다.

`harness` 운영 코드는 `:registry` 에 의존하지 않습니다(§4). `:capability` 의존만 하나 더합니다.

picasso 에는 실행기를 상주시키는 `main` 을 두지 않습니다. 첫 소비자인 picasso-ops `site/` 가 프로세스 안에서 띄웁니다(ADR 9). 상주 `main` 은 다른 소비자가 생길 때 엽니다(§12).

시험은 가상 시계라 실제 시간이 짧습니다. 수치는 P2a 계획에서 잽니다.

### 5.7 picasso 문서

ADR 49 를 씁니다. 시험 3종의 뜻, NEGATIVE 의 정의, 파라미터 생성기의 경계, 실행기의 자리를 적습니다. 설계 문서 §15 에 새 항목을 더합니다(바깥 첫 소비자 picasso-ops, ADR 9). `commissioning.md` REST 표에 새 경로 3개를 더합니다. `DocumentClaimsTest` 는 문서의 `/operations/` 와 `/ingest/` 경로를 컨트롤러의 매핑 철자 그대로 대조합니다. `harness` 의 새 의존 `:capability` 는 `docs/architecture.md` §4b 의 모듈 의존 표에도 더합니다. 시험 수가 늘므로 `CLAUDE.md` 와 `docs/verification.md` 의 시험 수를 고치고 도장을 다시 찍습니다. P1 때와 같은 자리입니다.

## 6. P2b: 개정판·바인딩 REST

### 6.1 카탈로그 기동 동기화

registry 가 기동할 때 `SkillTypeSync` 를 자기 계약 기술자(`/picasso.desc`)와 `ContractIdentity.semver` 로 부릅니다. 설계 문서 §8.3 ④ 그대로입니다.

동기화는 멱등입니다. 처음 본 스킬의 `introduced_in_semver` 를 덮지 않는 기존 규칙도 그대로입니다. 감사 `SKILL_TYPE_SYNC` 가 기동마다 1행 남습니다. 감사 `SKILL_TYPE_SYNC` 의 행위자는 `registry` 입니다.

계약 기술자를 읽지 못하면 기동을 거부합니다. 빈 카탈로그로 뜨면 제출이 스킬을 조용히 건너뛰는 상태(§4)로 돌아가기 때문입니다.

registry 는 스키마 마이그레이션을 기동 때 돌리지 않습니다(마이그레이션은 런처의 몫, S1 스펙 §6). 동기화는 스키마가 있다는 전제로 돕니다.

기존 표면 시험은 공유 DB 에 `skill_type` 행과 감사 행이 더 생기는 영향을 받을 수 있습니다. registry 를 띄우는 기존 표면 시험 5개 파일에 미치는 영향은 P2b 계획에서 실측하고, «기존 시험은 고치지 않고 통과» 를 지키는 방법을 정합니다.

### 6.2 REST

모두 `/operations` 이하라 운영자 토큰 뒤이고, 조작 POST 는 `X-Actor` 필수입니다(기존 규칙).

| API | 결과 |
|---|---|
| `GET /operations/skill-types` | 스킬 종류 목록(이름, major, 처음 본 계약 semver, 사이트 명칭 키)과 지금 계약 semver |
| `POST /operations/profile-revisions` 본문은 프로파일 문서 JSON 그대로 | 201 저장: 개정판 id, 번호, 상태(`VALIDATED` 또는 `DRAFT`), 사유 목록. 검증에 실패해도 저장하는 기존 규칙 그대로라 `DRAFT` 도 201 입니다. 200 같은 기종·같은 번호·같은 문서 해시의 재제출(같은 id, 멱등). 409 번호가 단조 증가하지 않음(같은 번호에 다른 문서 포함), 본문에 지금 최대 번호. 400 문서를 읽을 수 없음 |
| `GET /operations/profile-revisions` | 개정판 목록: id, 기종(vendor/model), 번호, 상태, 사유, 문서 해시, 제출한 이·시각, 활성화한 이·시각, 스위트별 최신 결과(결과, 시각, 실행 주체, 상세), 최신 시험 요청 1건(끝났든 아니든): 요청 id, 요청한 이·시각, 집은 실행기·집은 시각·만료 시각, 끝난 시각 |
| `POST /operations/profile-revisions/{id}/activation` | 200 활성화(대체된 개정판 id). 200 이미 `ACTIVE`(멱등, 아무것도 바꾸지 않음). 409 거절: 상태가 활성화 가능(`TESTED`·`SUPERSEDED`)이 아니거나 스위트 최신 결과가 모두 PASS 가 아님, 본문에 상태와 스위트별 최신 결과. 404 없는 개정판 |
| `POST /operations/robots/{robotId}/binding` 본문 `adapter_version_id`, `profile_revision_id`, 선택 `reason` | 201 바인딩(새 바인딩 id, 해제한 이전 바인딩 id). 200 같은 조합이 이미 활성(행을 그대로 두고 사이트 명칭 기록도 유지, 멱등). 404 본문 `reason` 으로 가름: `UNKNOWN_ROBOT`, `UNKNOWN_REVISION`, `UNKNOWN_BUILD`. 409 본문 `reason` 으로 가름: `ROBOT_RETIRED`, `REVISION_NOT_ACTIVE`, `CONTRACT_TOO_OLD` |

검사 순서는 기체 있음 → 퇴역 아님 → 개정판 있음 → 빌드 있음 → 개정판 활성 → 계약 semver 입니다. 바인딩은 기체 행을 잠그고 진행하므로 같은 기체의 동시 요청은 차례로 처리됩니다(유일 색인 위반 500 이 나지 않습니다). `CONTRACT_TOO_OLD` 는 지금 코드처럼 개정판 스킬의 최초 semver 와 빌드의 계약 semver 의 크기만 비교합니다. 설계 문서 §9.1 의 major 일치 조건은 P2b 가 더하지 않고 §12 후보에 둡니다.

P2a 의 `POST /operations/profile-revisions/{id}/test-requests` 도 같은 문 뒤에 있습니다.

### 6.3 서비스 결과 타입

P1 과 같은 방식입니다. 옛 메서드와 그 시험은 그대로 두고, REST 가 쓰는 결과를 값으로 가르는 새 메서드를 더합니다. 바인딩 거절은 지금 사유 문자열 하나라 409 의 `reason` 을 낼 수 없기 때문입니다. 제출의 멱등과 활성화의 멱등도 새 메서드에만 둡니다.

바인딩 행 진단(`GET /diag/bindings`)의 행에 칸을 더합니다. 더하는 칸은 빌드 id(`adapterVersionId`), 바인딩한 이·시각(`boundBy`·`boundAt`), 명칭 기록한 이·시각(`siteNamesRegisteredBy`·`siteNamesRegisteredAt`), 기체의 명칭 보고(`siteNamesReportedAt`, `siteNamesCount`, `siteNamesUnsupported`)입니다. 지금 행은 어댑터 이름과 버전만 내므로, 운영 서비스가 재조회로 «요청한 빌드로 바인딩됨» 을 판정할 수 없습니다. S1 스펙 §9 의 인스턴스 재조회가 제품 이름과 버전으로만 맞대야 했던 것과 같은 빈자리입니다. 나머지 칸은 §9 의 «바인딩» 카드와 «사이트 명칭» 카드가 «사람이 기록함» 과 «기체가 답함» 을 나눠 보이기 위한 것입니다. 응답에 칸을 더하는 변경이며 기존 칸은 바꾸지 않습니다.

### 6.4 mimic 의 명칭 입력

`MimicCli.start` 가 돌려주는 `Started` 에서 기체 id 로 기체 인스턴스를 찾을 수 있게 합니다. picasso-ops `site/` 가 기체가 아는 명칭(`knownSiteNames`)을 넣는 자리입니다. 현장의 명칭 티칭(`commissioning.md` Step 3)을 흉내 내는 자리이며, 제어 서버(gRPC)를 띄우지 않습니다.

기체가 아는 명칭 요약은 생존 보고마다 실립니다. 바꾼 값은 다음 상태 발행부터 registry 에 닿습니다. `knownSiteNames` 는 런처나 시험의 스레드가 쓰고 발행 스레드가 읽으므로 P2b 가 스레드 사이에 값이 보이게 합니다(`@Volatile` 등).

### 6.5 함께 고치는 것

| 자리 | 고치는 것 |
|---|---|
| `RevisionService` 주석 | «바인딩이 보고 막는다» 는 사실이 아닙니다(§4) |
| `/diag/bindings` 행 KDoc | 명칭 상태를 3값으로 적었습니다. 실제는 5값입니다 |
| `RobotRegistration` 의 `RetiredAlready` 메시지 | 기체 id 대신 `$robotId` 를 글자 그대로 찍습니다. 퇴역 시각도 글자 그대로 찍습니다 |
| `OperationsController` 의 모르는 기체 메시지 | 기체 id 대신 `${outcome.robotId}` 를 글자 그대로 찍습니다 |

### 6.6 `commissioning.md`

10단계 표에 개정판 제출·시험·활성화를 Step 2b, 바인딩을 Step 6b 로 넣습니다. 번호를 다시 매기지 않으므로 «Step 0~9» 를 가리키는 다른 문서는 고치지 않습니다. REST 표에 새 경로를 더합니다. 표에서 `/ingest/handshake` 를 «어댑터 빌드 및 바인딩된 프로파일 정보 보고» 로 적은 것은 코드(어댑터 빌드 칸 없음, 바인딩 대조 없음)에 맞춥니다. 퇴역이 «가용 자원 목록» 에서 빼는 것처럼 적은 문장도 코드에 맞춥니다. 2절 끝의 «시운전 완료 검증은 모든 기체가 `CONFIRMED`» 문장을 결정 6 의 세 조건으로 고칩니다. 퇴역 기체가 `/catalog` 에 세어지는 것은 한계 대장에 적습니다. 도장을 다시 찍습니다.

### 6.7 picasso 문서

P2b 도 ADR 9 에 따라 설계 문서 §15 에 새 항목을 적습니다(바깥 첫 소비자 picasso-ops S1d). `commissioning.md`·`CLAUDE.md`·`docs/verification.md` 를 고치고 도장을 다시 찍습니다.

## 7. 가짜 현장 `site/` 의 변경

서브모듈을 P2b 머지 커밋으로 옮깁니다. 포함 빌드의 좌표 치환에 `dev.picasso:harness` 를 더합니다.

런처가 시험 실행기를 같은 프로세스에서 띄웁니다. 적재 토큰은 `site/` 만 가지므로(S1 스펙 §4) 실행기도 `site/` 에 있습니다. 실행기가 쓰는 mimic 은 현장 기체(mimic N대)와 따로 뜨는 프로세스 안 mimic 입니다.

실행기는 `Site.start` 안에서 뜨므로 통합 시험의 스택도 같은 실행기를 씁니다. `Site.close` 가 실행기를 멈춥니다. `stopRegistry` 뒤에는 실행기가 집기 실패를 로그에 남기며 폴링을 계속합니다.

| 런처가 실행기에 넘기는 값 | 값 |
|---|---|
| registry 주소 | 런처의 registry 주소 |
| 적재 토큰 | `site/` 의 적재 토큰 |
| 프로파일 스키마 경로 | 프로파일 스키마 경로 |
| 실행기 이름 | `site-runner` |
| 폴링 간격 | 실제 1초 |

`site/robots.json` 에 기체마다 `site_names`(현장에서 티칭한 명칭)를 적습니다. 런처가 기동 때 그 값을 기체 인스턴스에 넣습니다.

| 기체 | `site_names` | 뜻 |
|---|---|---|
| `humanoid-01` | `["dock-3", "bay-7"]` | 티칭함 |
| `quadruped-01` | 빈 목록 | 티칭 안 함 |

registry 는 기체가 명칭을 1개 이상 아는지만 대조하므로 이름 값 자체는 대조되지 않습니다(§1). `quadruped-01` 의 빈 목록은 «사람은 기록했는데 기체는 모른다»(`CONTRADICTED`)를 화면에서 보이려는 것입니다.

통합 시험은 `Site` 로 실행 중에 명칭을 바꿔 현장 재티칭을 흉내 냅니다.

명칭 티칭은 화면 밖 작업입니다. 화면은 이 작업이 있다는 것과 어디서 하는지를 보이고 완료를 대신 체크하지 않습니다(S1 스펙 §8 과 같은 규칙).

## 8. 운영 서비스

### 8.1 읽기

더하는 registry 읽기는 4개입니다.

| registry 읽기 | 내용 |
|---|---|
| `GET /operations/skill-types` | 카탈로그 |
| `GET /operations/profile-revisions` | 개정판 목록 |
| `GET /diag/bindings?site=<SITE_ID>` | 바인딩, 명칭 상태와 요구 키 포함, 이력 제외 |
| `GET /diag/software?site=<SITE_ID>` | 소프트웨어 대조 |

화면 API 는 `GET /api/profiles`(카탈로그 요약과 개정판 목록)를 더합니다. 기체 목록 응답(`GET /api/robots`)에 기체별 바인딩, 명칭 상태, 시운전 칸, 소프트웨어 대조를 더합니다.

기체 목록은 `/diag/robots` → `/diag/bindings` → `/diag/software` 를 함께 읽고 셋 다 읽혀야 새 값으로 바꿉니다. 하나라도 못 읽으면 직전 값입니다. S1 스펙 §7.2 의 어댑터 목록 규칙과 같은 이유입니다. 다른 시각에 읽은 것을 섞으면 서로 맞지 않는 행이 보일 수 있기 때문입니다. 운영자 토큰 확인(S1 스펙 §7.2)은 그대로입니다.

프로파일 읽기는 관문 안이라 토큰이 틀리면 `REGISTRY_UNAUTHORIZED` 이고 직전 값입니다(S1 스펙 §8 의 어댑터 목록과 같습니다).

화면은 기체 목록, 어댑터 목록, 프로파일 목록, 조작 기록을 한 번에 읽습니다. 넷 중 하나라도 운영 서비스가 답하지 않으면 넷 다 «직전 값» 입니다(S1 스펙 §8 의 규칙을 넷으로 넓힘).

### 8.2 조작

| 조작 | 화면 API | registry | 모드 | 조작 기록 대상 칸 |
|---|---|---|---|---|
| 개정판 제출 | `POST /api/profile-revisions`(본문은 문서 JSON) | `POST /operations/profile-revisions` | 엔지니어 | `profile <vendor>/<model>#<revision>`(registry 감사 subject 와 같은 꼴) |
| 시험 요청 | `POST /api/profile-revisions/{id}/test-requests` | P2a | 엔지니어 | `revision <profile_revision_id>` |
| 활성화 | `POST /api/profile-revisions/{id}/activation` | P2b | 엔지니어 | `revision <profile_revision_id>` |
| 바인딩 | `POST /api/robots/{robotId}/binding` | P2b | 엔지니어 | `robot <robot_id>` |
| 명칭 기록 | `POST /api/robots/{robotId}/site-names` | `POST /operations/site-names?robot=` | 엔지니어 | `robot <robot_id>` |

다섯 다 엔지니어 모드이며 아니면 403 입니다. 사전 거절은 S1 규칙 그대로이고 조작 기록에 남지 않습니다. 바인딩 본문의 빌드 id·개정판 id 가 없으면 400(`BINDING_TARGET_REQUIRED`)입니다. 널이 안 되는 정수로 받으면 빈칸이 0 으로 읽혀 registry 까지 가기 때문입니다(S1 스펙 §9 의 `BUILD_REQUIRED` 와 같습니다). 제출 본문은 JSON 이어야 하고(415), 비어 있으면 400 입니다. 운영 서비스는 제출 본문을 바이트 그대로 registry 에 넘기고 다시 직렬화하지 않습니다. 재조회 판정(§8.4)의 문서 해시가 registry 와 같은 값(본문 바이트의 SHA-256)이어야 하기 때문입니다. 운영 서비스는 조작 기록의 대상 칸을 채우려고 `vendor`·`model`·`revision` 세 칸만 읽고, 읽지 못하면 대상 칸을 `profile ?` 로 남깁니다. 문서의 옳고 그름은 registry 가 판정합니다.

### 8.3 조작 거절 대응표

| 조작 | 응답 | 가르는 본문 | 종류 | 해결 담당 | 화면 | 후속 행동 |
|---|---|---|---|---|---|---|
| 개정판 제출 | 400 | 없음 | `PROFILE_UNREADABLE` | 엔지니어 | 안 | 문서를 고쳐서 다시 |
| 개정판 제출 | 409 | 없음 | `REVISION_NOT_MONOTONIC` | 엔지니어 | 안 | 번호를 올려 다시. 관측값에 지금 최대 번호 |
| 시험 요청·활성화 | 404 | 없음 | `UNKNOWN_REVISION` | 엔지니어 | 안 | 목록 새로 읽기 |
| 시험 요청 | 409 | 없음 | `REVISION_NOT_TESTABLE` | 엔지니어 | 안 | 문서를 고쳐 새 번호로 제출 |
| 활성화 | 409 | 없음 | `ACTIVATION_REFUSED` | 엔지니어 | 안 | 시험 요청 또는 시험 결과 확인. 관측값에 상태와 스위트별 최신 결과 |
| 바인딩 | 404 | `reason: UNKNOWN_ROBOT` | `UNKNOWN_ROBOT` | 엔지니어 | 안 | 목록 새로 읽기 |
| 바인딩 | 404 | `reason: UNKNOWN_REVISION` | `UNKNOWN_REVISION` | 엔지니어 | 안 | 프로파일 목록 새로 읽기 |
| 바인딩 | 404 | `reason: UNKNOWN_BUILD` | `UNKNOWN_BUILD` | 엔지니어 | 안 | 빌드 목록 새로 읽기 |
| 바인딩 | 409 | `reason: REVISION_NOT_ACTIVE` | `REVISION_NOT_ACTIVE` | 엔지니어 | 안 | 활성 개정판 고르기 |
| 바인딩 | 409 | `reason: ROBOT_RETIRED` | `ROBOT_RETIRED` | 운영자 | 안 | 복귀 뒤 다시 |
| 바인딩 | 409 | `reason: CONTRACT_TOO_OLD` | `CONTRACT_TOO_OLD` | 엔지니어 | 안 | 계약 semver 가 높은 빌드 고르기 |
| 명칭 기록 | 404 | 없음 | `NO_ACTIVE_BINDING` | 엔지니어 | 안 | 바인딩 먼저 |
| 명칭 기록 | 409 | 없음 | `NOTHING_TO_REGISTER` | 없음 | 안 | 고칠 것 없음(이 기체의 스킬은 명칭을 쓰지 않음) |
| 위 조작 | 표에 없는 응답 | 없음 | `UNCLASSIFIED` | 엔지니어 | 밖 | registry 응답 조사 |

409 는 S1 처럼 본문으로 가르며 하나로 다루지 않습니다. 제출의 `DRAFT` 저장(201)은 거절이 아닙니다. 화면은 «저장됨: 검증 실패» 와 사유를 보입니다. 개정판 거절의 바로 가기는 없습니다. 프로파일 구역이 같은 영역에 늘 보이기 때문입니다. 바인딩·명칭 거절의 바로 가기는 그 기체입니다.

### 8.4 응답 없음 뒤 재조회의 반영 판정

| 조작 | 반영됨 |
|---|---|
| 개정판 제출 | 목록에 그 기종·번호가 같은 문서 해시로 있음 |
| 시험 요청 | 그 개정판에 끝나지 않은 요청이 있거나, 최신 요청이 이 조작을 보낸 시각 뒤에 만들어짐 |
| 활성화 | 그 개정판이 `ACTIVE` |
| 바인딩 | `/diag/bindings` 에서 그 기체의 활성 바인딩이 요청한 빌드 id 와 개정판 id |
| 명칭 기록 | 그 기체의 명칭 상태가 `UNREGISTERED` 가 아님 |

시험 요청을 두 갈래로 판정하는 것은 실행기가 가상 시계로 돌아 1초 뒤 재조회 때 이미 끝났을 수 있고, 멱등 200 은 조작 전에 만든 열린 요청을 돌려주기 때문입니다.

확인 행의 `registry_response` 칸에는 재조회에서 본 값을 남깁니다. 제출은 개정판 id 와 상태, 시험 요청은 요청 시각, 활성화는 상태, 바인딩은 빌드 id 와 개정판 id, 명칭 기록은 명칭 상태입니다. S1 스펙 §9 와 같은 규칙입니다.

### 8.5 시운전 판정과 상태 막힘

«시운전» 칸 값은 «완료», «미완», «퇴역» 3개입니다. «완료» 는 결정 6 의 세 조건이 모두 맞을 때입니다. 판정 시각은 목록을 읽은 시각입니다(S1 스펙 §7.3).

연결 칸과 합치지 않습니다. 시운전 완료는 갖춘 조건이고 연결은 지금의 보고입니다. 시운전이 완료여도 연결이 오래됨일 수 있습니다.

새 상태 막힘은 4종입니다.

| 종류 | 조건 | 해결 담당 | 화면 | 후속 행동 |
|---|---|---|---|---|
| `UNBOUND` | 퇴역 아닌 기체에 활성 바인딩 없음 | 엔지니어 | 안 | 빌드와 활성 개정판을 골라 바인딩 |
| `SITE_NAMES_UNREGISTERED` | 명칭 상태 `UNREGISTERED` | 엔지니어 | 안 | 현장 티칭을 확인한 뒤 명칭 기록 |
| `SITE_NAMES_UNANSWERED` | 명칭 상태 `CLAIMED`(기록은 있고 기체가 아직 답하지 않음) | 현장 | 밖 | 기체 보고 확인 |
| `SITE_NAMES_CONTRADICTED` | 명칭 상태 `CONTRADICTED`(기록은 있는데 기체가 아는 명칭이 없음) | 현장 | 밖 | 현장에서 명칭 티칭을 다시. 다시 티칭하면 기록은 그대로 둔 채 다음 보고로 풀림. 기체가 명칭을 지원하지 않는다고 답했으면(`siteNamesUnsupported`) 다시 티칭하지 않고 그 기종의 프로파일과 명칭 기록을 확인(엔지니어) |

막힘 칸 5개(종류, 관측값과 기대값, 마지막 확인 시각, 해결 담당, 바로 갈 링크)는 S1 스펙 §7.4 그대로입니다.

시운전 미완이면 무엇이 빠졌는지는 이 4종과 S1 의 `AWAITING_FIRST_REPORT` 가 보입니다.

## 9. 화면

로봇·연결 영역 왼쪽은 기체 목록, «어댑터» 구역(S1c), 그 아래 새 «프로파일» 구역입니다. 기체 목록에 «시운전» 칸을 더합니다.

«프로파일» 구역은 다음과 같습니다.

| 요소 | 내용 |
|---|---|
| 카탈로그 한 줄 | «스킬 N종, 계약 0.9.0». 기동 때 동기화되므로 화면에 동기화 버튼은 없습니다 |
| 개정판 목록 칸 | 기종, 번호, 상태, 스위트 3종의 최신 결과, 시험 요청 상태(«대기», «실행 중», «만료», «끝남»), 활성화한 이와 시각. 결과에는 실행 주체(예: `site-runner`)를 같이 보입니다. FAIL 이면 상세를 펼쳐 봅니다. `DRAFT` 이면 사유를 보입니다 |
| 엔지니어 모드 | 제출 폼(문서 파일 고르기), 개정판마다 «시험 요청»·«활성화» 버튼 |
| 운영자 모드 | «프로파일 관리는 엔지니어 모드에서 합니다» 를 적습니다 |

시험 요청 상태는 4값입니다. «대기» 는 아직 안 집힌 것입니다. «실행 중» 은 집혔고 만료 전인 것입니다. «만료» 는 집혔으나 만료 시각이 지나 다시 집히기를 기다리는 것입니다. «끝남» 은 끝난 것입니다. 판정은 운영 서비스가 목록을 읽은 시각과 만료 시각을 비교해 합니다. PoC 에서 registry 와 운영 서비스는 같은 기계에 있습니다.

기체 상세(오른쪽)에 카드 3개를 더합니다.

| 카드 | 내용 |
|---|---|
| «바인딩» | 빌드(제품 이름과 버전), 개정판(기종과 번호), 바인딩한 이·시각. 엔지니어 모드에서 빌드(어댑터 목록에서)와 활성 개정판(프로파일 목록에서)을 골라 바인딩하는 폼 |
| «사이트 명칭» | 명칭 상태, 요구 키. «사람이 기록함» 과 «기체가 답함» 을 나눠 보입니다. 엔지니어 모드에서 «명칭 등록 기록» 버튼. 명칭 티칭은 화면 밖 현장 작업이라고 적습니다 |
| «시운전» | 세 조건의 체크 목록. 조건마다 근거 조회(`/diag/robots`, `/diag/bindings`)를 적습니다. 소프트웨어 대조(일치, 불일치, 보고 없음)와 어댑터 적합성(`UNTESTED`)은 막지 않는 참고로 보입니다 |

운영 관리 화면 설계 제안 §8.1 의 단계 카드 4칸(무엇이 빠졌는가, 누가 채우는가, 근거, 다음 행동)이 막힘 카드와 같은 모양입니다.

«시운전 완료» 와 «배정 가능» 을 섞지 않습니다. 화면은 «배정 가능» 을 쓰지 않습니다(결정 5).

시험 진행 상태는 화면의 기존 폴링으로 갱신합니다.

조작 결과 알림은 대상과 조작을 함께 적습니다(예: «picasso-ref/humanoid-a#2 시험 요청: 반영됨», «humanoid-01 바인딩: 반영됨»).

## 10. 오류 처리

S1 스펙 §9 의 규칙을 그대로 씁니다. 결과는 알 수 있음·없음·모름 3값입니다. 응답 없음(또는 5xx)이면 1초 뒤 한 번 재조회하고 같은 요청 id 로 확인 행을 붙입니다. 재조회로 반영이 확인되면 재시도하지 않습니다. 사전 거절은 조작 기록에 남지 않습니다.

다섯 조작 모두 멱등입니다.

| 조작 | 멱등 규칙 |
|---|---|
| 개정판 제출 | 같은 문서면 같은 id |
| 시험 요청 | 끝나지 않은 요청이 있으면 그 요청 |
| 활성화 | 이미 `ACTIVE` 면 그대로 |
| 바인딩 | 같은 조합이면 그대로(명칭 기록 유지) |
| 명칭 기록 | 기존 REST 가 기록 시각만 새로 씀 |

시험 요청이 오래 «대기» 이면 실행기가 돌지 않는 것입니다. 화면은 요청 시각과 집힌 시각을 그대로 보입니다. 실행기가 집은 뒤 죽으면 15분 만료 뒤 다시 집힙니다. 화면은 이것을 막힘 종류로 만들지 않습니다. 운영 서비스는 실행기의 상태를 직접 알 수단이 없기 때문입니다.

스위트가 FAIL 이면 개정판은 `VALIDATED` 에 머물고 활성화는 409(`ACTIVATION_REFUSED`)입니다. 다시 시험을 요청하면 새 결과가 최신이 됩니다. 옛 FAIL 이 뒤의 PASS 를 덮지 않는 것은 registry 의 기존 규칙입니다.

실행기가 쓰는 프로세스 안 mimic 은 현장 기체와 따로 뜹니다. 시험이 현장 기체의 보고와 상태를 바꾸지 않습니다.

## 11. 시험

| 대상 | 시험 |
|---|---|
| P2a registry | 서비스·표면 시험입니다. 요청 201·200(멱등)·404·409(`DRAFT`), 집기 200·204·끝난 요청 안 집음·적재 토큰 없으면 401, 보고 200·400·404·409(다른 실행기, 이미 끝남), 셋 다 PASS 면 `TESTED`·하나라도 FAIL 이면 `VALIDATED` 유지, 실행 3행의 요청 id 와 상세 |
| P2a harness | `humanoid-a`·`quadruped-b` 로 3종 PASS. 결함 주입: mimic 의 거절을 끄면 `NEGATIVE` FAIL, 능력 응답을 투영과 다르게 하면 `CONTRACT` FAIL, `DETERMINISM` 결함 주입은 `humanoid-a` 의 두 번째 실행 시드를 먼 값(`987654321`)으로 바꿉니다. 그러면 `DETERMINISM` 이 FAIL 입니다. `humanoid-a` 의 `pick_place` 가 작업 시간 지터(`jitter_ratio` `0.1`)를 선언하므로 시드의 첫 난수가 바뀌면 가상 시각과 진행률이 달라지기 때문입니다. 이웃한 작은 시드(예: `0` 과 `1`)는 쓰지 않습니다. `java.util.Random` 은 이웃한 작은 시드의 첫 난수가 거의 같기 때문입니다. 시드 `0` 부터 `6` 까지는 모두 `pick_place` 를 48초째 끝내 자취가 같습니다. 그래서 이웃 시드로 바꾸는 주입은 등가 변이입니다. 지터가 없는 프로파일(`quadruped-b`)은 시드를 바꿔도 같은 결과가 나옵니다. 실행기와 registry 를 함께 띄운 통합 시험(harness 시험 소스는 기존대로 `:registry` 를 시험 의존으로 씁니다) |
| P2b registry | 표면 시험이 §6 의 응답 코드를 전부 검증합니다. 기동 동기화 뒤 `skill_type` 이 계약과 같습니다. 바인딩의 막는 장치(없는 기체 404, 퇴역 409, 같은 조합 200 과 명칭 기록 유지). 기존 시험은 고치지 않고 통과합니다. `DocumentClaimsTest` 통과 |
| S1d 판정(ops-service) | 시운전 판정과 새 막힘 4종의 표 형식 단위 시험, §8.3 대응표의 행마다 거절 판정, §8.4 반영 판정. 결함 주입으로 확인합니다 |
| S1d 통합(e2e) | 한 JVM 에서 S1 스택에 실행기를 더해 제출 → 시험 요청 → 실행기 3종 PASS → 활성화 → 바인딩 → 명칭 기록 → 시운전 완료(`humanoid-01`). `quadruped-01` 은 명칭 기록 뒤 `SITE_NAMES_CONTRADICTED`, `Site` 로 재티칭하면 `CONFIRMED` 와 시운전 완료. 거절 대응표의 행들, 사전 거절이 기록에 안 남음, 조작 기록과 registry 감사 기록의 행위자 |
| 화면 | vitest 컴포넌트 시험. Playwright 생애주기 시험을 넓혀 화면에서 제출(파일 고르기) → 시험 요청 → `TESTED` 표시 → 활성화 → 바인딩 → 명칭 기록 → «시운전 완료» 를 1회 돕니다. `quadruped-01` 의 `SITE_NAMES_CONTRADICTED` 카드도 봅니다. CI 의 job 3개는 그대로입니다 |

결함 주입은 S1 처럼 코드를 바꿔 넣고 되돌립니다. 시험 전용 이음새를 두지 않습니다(예: mimic 의 미지원 거절 제거로 `NEGATIVE`, 능력 응답 변형으로 `CONTRACT`).

판정은 S1 처럼 시험 결과 XML 의 실패 시험 이름으로 합니다. 결함 주입에서 시험이 잡았다고 말하려면 어느 시험이 빨개졌는지가 XML 에 있어야 합니다.

## 12. 한계와 다음 단계

다음은 S2(현장 값 데이터화), S3(임무 판과 배정), S4(장애 주입)입니다.

S1d 가 남기는 한계는 다음과 같습니다.

| 한계 | 이유 | 닫는 자리 |
|---|---|---|
| 바인딩이 기체에 닿지 않습니다 | mimic CLI 는 바인딩을 가져가지 않고, registry 는 핸드셰이크를 바인딩과 대조하지 않습니다 | S3 의 판 전환(설계 문서 §8.4 ④) |
| «배정 가능» 이 없습니다 | 미들웨어가 registry 를 읽지 않습니다 | S3 |
| 사이트 명칭은 기체가 명칭을 1개 이상 아는지만 대조합니다 | registry 가 명칭 목록을 갖지 않습니다(ADR 35) | picasso §15.129 |
| 시험은 mimic 위의 계약 적합성입니다 | 실물 적합성은 C-3 비목표입니다 | 해당 없음 |
| 실행기에 상주 `main` 이 없습니다 | 첫 소비자가 `site/` 안에서 띄웁니다 | 다른 소비자가 생길 때 |

picasso 변경 후보는 다음과 같습니다. 결정이 아니며 소비자가 붙을 때 ADR 9 에 따라 짓습니다.

| 후보 | 비고 |
|---|---|
| 퇴역 기체를 `/catalog` 가용 수에서 빼기 | 지금은 퇴역이 바인딩을 풀지 않습니다(§4) |
| mimic 의 레지스트리 연동 모드와 후보 개정판 검증 모드(설계 문서 §10.2) | S3 의 판 전환과 함께 |
| 핸드셰이크와 바인딩의 대조 | 어댑터 빌드 칸이 계약에 없습니다 |
| 시험 요청의 취소 | 지금은 만료만 있습니다 |
| 바인딩의 major 일치 검사(설계 문서 §9.1) | 지금은 최초 semver 크기만 비교합니다 |

S1 스펙 §12 의 후보 2개(선언 안 된 기체의 보고 거절 기록, 사이트 id 일치 대조)는 그대로 남습니다.
