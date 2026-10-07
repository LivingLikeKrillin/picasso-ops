# picasso-ops S1: 골격과 로봇 생애주기 설계

- **문서 상태**: 승인된 설계 (2026-10-07). 같은 날 스펙 검토 뒤 확정한 결정 10 을 반영한 개정판입니다
- **범위**: picasso 선행 변경 P1 과 picasso-ops 의 S1a·S1b·S1c
- **근거 문서**: picasso 저장소의 `docs/superpowers/specs/2026-10-07-ops-console-lifecycle-design.md`(운영 관리 화면 설계 제안) · `docs/commissioning.md`(현장 배포 및 시운전 가이드) · `docs/orchestration.md` §8

---

## 1. 목적과 범위

picasso-ops 는 picasso 를 라이브러리로 쓰는 담는 측입니다. 자리는 `orchestration.md` §8 끝 문단이 정한 별도 배치 저장소이며, 로봇·임무·엔드포인트의 운영 가능성을 PoC 로 입증하는 것이 이 저장소의 일입니다. 실물 현장은 없고 보안과 인증은 생략합니다. 지키는 원칙은 하나입니다. 운영 중 변경은 코드 수정이 아니라 관리 화면에서 처리합니다.

전체는 4덩어리로 나눠 짓습니다. 각 덩어리는 근거 문서의 PoC 입증 항목 1~5 와 대응합니다.

| 덩어리 | 내용 | 입증 항목 |
|---|---|---|
| S1 | 골격과 로봇 생애주기 | 1 |
| S2 | 현장 값 데이터화 | 2 |
| S3 | 임무 판 | 3·4 |
| S4 | 장애 주입 | 5 |

이 문서가 다루는 순서는 P1(picasso PR) → S1a 골격 → S1b 로봇 생애주기 → S1c P1 을 화면에서 쓰기입니다. 각 단계에 들어가는 것과 완료 판정은 §3 에 있습니다. S1 이 끝난 뒤의 P2(picasso PR: 카탈로그 초기화, 프로파일 개정판 제출·시험·활성화, 바인딩 REST)와 S1d(바인딩, 사이트 명칭, 시운전 완료)는 다음 설계 문서에서 다룹니다. 이 문서는 그 자리가 있다는 것만 적습니다.

**S1 이 입증하는 것**은 한 흐름입니다. 기체를 화면에서 선언하고, 보고로 `CONFIRMED` 가 되고, 퇴역시키고, 퇴역 뒤의 보고를 감지하고, 복귀시킵니다. 이 모든 조작이 이력에 남고, 그 사이에 코드 수정이 없습니다.

S1 이 입증하지 않는 것도 같이 적어 둡니다. 근거 문서의 입증 항목 1 에는 «가동» 이 들어 있으나 S1 은 그것을 닫지 않습니다. 가동(작업 수행)은 S3 에서 닫힙니다. S1 이 항목 1 에서 닫는 것은 기체의 선언·보고·퇴역·복귀까지입니다. 바인딩, 사이트 명칭, 시운전 완료, 배정 가능은 S1d 의 몫입니다. 임무는 S3, 장애 주입은 S4 의 몫입니다. 로봇은 mimic 만 쓰며, 기종 어댑터와 어댑터 호스트는 범위 밖입니다. 그래서 S1 의 화면에서 기체가 `CONFIRMED` 로 보여도 그것이 시운전 완료나 배정 가능을 뜻하지는 않습니다(§7.3).

---

## 2. 결정 기록

2026-10-07 사용자 결정 9건과, 같은 날 스펙 검토 뒤 확정한 1건입니다. 합쳐 10건입니다.

| 결정 | 내용 |
|---|---|
| 1. 로봇 범위 | mimic 만 씁니다 |
| 2. picasso 가져오기 | git 서브모듈(고정 커밋) + Gradle `includeBuild`. 어느 picasso 커밋으로 입증했는지가 picasso-ops 커밋에 남습니다 |
| 3. registry 의 Postgres | docker compose 로 띄웁니다. 통합 시험은 Testcontainers 를 씁니다 |
| 4. 백엔드와 화면 | 운영 서비스 하나(Kotlin, Spring Boot)를 둡니다. 화면은 이 서비스만 부르고, 서비스가 운영자 토큰을 쥡니다. 화면은 React·Vite·TS 입니다. mimic 기체는 관리 화면이 띄우지 않습니다 |
| 5. GitHub | `LivingLikeKrillin/picasso-ops` 공개 저장소 |
| 6. 로봇·연결 영역 배치 | 목록 + 상세 패널 |
| 7. 막힘 판정 | 운영 서비스가 registry 의 기존 조회 결과로 계산합니다. picasso 에 집계 API 를 먼저 만들지 않습니다 |
| 8. 시운전 Step 2 의 REST 빈자리 | 어댑터 빌드 등록에 REST 가 없으므로, picasso 에 REST 를 더하는 PR(P1)을 먼저 냅니다 |
| 9. Fable·Codex 검토 뒤 | 바인딩·사이트 명칭은 S1 에서 빼고 P2·S1d 로 넘깁니다. S1 을 S1a·S1b·S1c 로 나눕니다 |
| 10. 스펙 검토(2026-10-07) 뒤 확정 | 퇴역 기체 포함 조회(§7.2), 조작 거절과 상태 막힘의 분리(§7.4), 단계별 완료 기준(§3), 사이트 id·기체 명부의 한 출처(§6), 수치 기본값(§6·§7.3) |

결정 2 는 재현 가능성을 위한 것입니다. picasso 가 바뀌어도 picasso-ops 의 어느 커밋이 어느 picasso 커밋을 보고 입증했는지를 서브모듈 포인터가 들고 있습니다. 결정 4 는 토큰 경계를 위한 것입니다. 화면이 registry 를 직접 부르면 운영자 토큰이 브라우저로 가야 하므로, 토큰을 쥐는 자리를 운영 서비스 하나로 모읍니다(§4). **결정 10 은 검토에서 드러난 빈자리를 닫은 것**이며, 항목마다 내용은 괄호 안의 절에 있습니다.

---

## 3. 단계와 완료 기준

S1 은 결정 9 에 따라 3단계로 나눕니다. 단계마다 들어가는 것과 완료 판정을 적습니다. 완료 판정의 시험 구성은 §10 에 있습니다.

| 단계 | 들어가는 것 | 완료 판정 |
|---|---|---|
| S1a 골격 | 저장소, 서브모듈(`cd688ff` 고정), `includeBuild` 와 좌표 치환, picasso 버전 카탈로그(`picasso/gradle/libs.versions.toml`) 가져오기, docker compose Postgres, `site/` 런처(registry 스키마 Flyway, registry 기동, mimic 기동과 시간 진행, `.env` 의 `SITE_ID`), 운영 서비스 골격(registry 클라이언트, ops 스키마 Flyway, 조작 기록, `X-Actor`), 화면 골격(5영역 메뉴, 모드 전환, 이력 영역), CI | 통합 시험이 전체를 띄우고 운영 서비스의 기체 목록 조회가 빈 목록을 돌려줍니다. registry 를 멈추면 화면 전체 상태가 «모름» 이 됩니다. CI 가 초록입니다 |
| S1b 로봇 생애주기 | 기체 선언·퇴역·복귀 API 와 화면(목록 + 상세), 상태 막힘 판정, 조작 거절 대응 | 통합 시험과 Playwright 에서 선언(`CLAIMED`) → 보고(`CONFIRMED`) → 퇴역 → 퇴역 뒤 보고 감지 → 복귀 → 조작 기록 확인이 돕니다. 막힘·거절 판정 단위 시험이 결함 주입을 잡습니다 |
| S1c P1 소비 | P1 머지 뒤 서브모듈을 P1 머지 커밋으로 옮김, 어댑터 제품·빌드 등록과 목록, 어댑터 인스턴스 등록(`POST /operations/adapter-instances`, P1 의 빌드 id 를 씀) | 통합 시험에서 제품 등록 → 빌드 등록 → 인스턴스 등록 → 목록에 `UNTESTED` 표시가 돌고, P1 거절(400/404/409)이 대응표(§7.4)대로 보입니다 |

S1a·S1b 는 P1 머지를 기다리지 않고 서브모듈을 `cd688ff` 에 고정해 진행합니다. P1 이 머지되면 서브모듈 포인터를 그 머지 커밋으로 옮기는 커밋이 S1c 의 첫 커밋입니다. **판은 서브모듈 포인터가 정합니다.** 어느 picasso 커밋을 보고 입증했는지는 결정 2 대로 그 포인터에 남으며, 이 문서의 §11 사실 표도 포인터가 가리키는 커밋 기준으로 다시 확인합니다.

---

## 4. 저장소 구성과 경계

```
picasso-ops/
├─ .env            SITE_ID. 런처와 운영 서비스가 같이 읽습니다
├─ picasso/        git 서브모듈(고정 커밋), settings 에서 includeBuild
├─ ops-service/    Kotlin, Spring Boot. 화면의 유일한 백엔드
├─ ui/             React, Vite, TS
├─ site/           가짜 현장 런처(Kotlin), robots.json, docker compose(Postgres)
├─ e2e/            통합·E2E 시험
└─ docs/
```

Spring Boot 는 `.env` 를 스스로 읽지 않습니다. 런처와 운영 서비스를 띄우는 Gradle 실행 작업과 docker compose(`env_file`)가 `.env` 의 값을 환경 변수 `SITE_ID` 로 넘깁니다. 통합 시험은 같은 `.env` 를 읽어 속성으로 넣고, CI 도 같은 `.env` 를 씁니다.

**picasso 를 고치지 않습니다.** 서브모듈은 읽기만 하며, picasso 쪽에 필요한 변경은 picasso 저장소의 PR 로 냅니다. 이 문서의 P1 이 그 첫 PR 입니다(§5).

`includeBuild` 에는 의존 좌표 치환을 명시합니다. picasso 서브프로젝트에는 Gradle `group` 이 없어서(§11) 좌표를 자동으로 맞출 수 없기 때문입니다. 판은 picasso 의 `gradle/libs.versions.toml` 에 맞춥니다. picasso-ops 의 빌드는 이 카탈로그를 서브모듈 경로 `picasso/gradle/libs.versions.toml` 에서 가져오며, 따로 판을 적지 않습니다. Kotlin 2.4.20, Spring Boot 3.4.0 입니다. UI 는 Gradle 과 묶지 않고 npm 으로 따로 빌드합니다(§10).

운영 서비스는 registry REST 만 부릅니다. registry DB 에 직결하지 않습니다. 직결하면 운영자 토큰 경계를 우회하게 됩니다. mimic 과도 직접 이야기하지 않습니다. mimic 은 `site/` 가 띄우고 registry 로만 보고합니다(§6). 운영 서비스가 쓰는 DB 는 같은 Postgres 의 ops 스키마뿐이며, 그 스키마의 마이그레이션은 운영 서비스 자신이 맡습니다(§7.1).

토큰은 2개이며 쥐는 곳이 다릅니다.

| 토큰 | 쥐는 곳 |
|---|---|
| 운영자 토큰(`picasso.operator.token`) | 운영 서비스만 |
| 적재 토큰(`picasso.ingest.token`) | `site/` 의 mimic 만 |

어느 토큰도 브라우저에는 가지 않습니다. 인증을 생략하는 PoC 에서도 이 경계는 지킵니다.

---

## 5. 선행 picasso 변경 P1: 어댑터 제품·빌드 REST

현재 `registry/.../adapter/AdapterService.kt` 에는 `registerAdapter(vendor, name)` 과 `registerVersion(adapterId, version, contractSemver)` 가 있고 감사 기록도 남기지만, 이를 부르는 컨트롤러가 없습니다. 시험만 직접 부릅니다. 어댑터 인스턴스 등록은 실재하는 `adapter_version_id` 를 요구합니다. 기존 조회로는 이 빈자리를 메우지 못합니다. `/diag/software` 는 바인딩된 기체의 소프트웨어 대조이지 빌드 목록이 아니고, `/diag/adapter-instances` 는 인스턴스가 있어야 빌드가 보입니다. 화면이 어댑터 제품과 빌드를 등록하고 목록으로 볼 자리가 없는 것입니다.

P1 이 더하는 API 는 3개입니다.

| API | 결과 |
|---|---|
| `POST /operations/adapters` {vendor, name} | 201 새로 만듦, 200 이미 있음. 둘 다 `adapter_id` 를 돌려줍니다. 400 vendor 또는 name 이 비어 있음 |
| `POST /operations/adapters/{id}/versions` {version, contract_semver} | 201 새로 만듦 + `adapter_version_id`. 200 같은 내용 재요청(같은 id). 409 같은 버전에 다른 계약값. 400 SemVer 형식 오류. 404 없는 `adapterId` |
| `GET /operations/adapters` | 제품과 빌드 목록. 빌드마다 `conformance`(`UNTESTED` 등) |

3개 모두 운영자 토큰 뒤(`/operations/**`)에 두고, 조작 요청에는 `X-Actor` 를 필수로 합니다. 기존 `/operations/**` 조작 POST 와 같은 규칙입니다(§11).

`GET /operations/adapters` 의 응답 모양은 다음과 같습니다. 제품 1건 안에 빌드 목록이 들어갑니다.

```
[{ "adapter_id": 1, "vendor": "...", "name": "...",
   "versions": [{ "adapter_version_id": 10, "version": "...", "contract_semver": "...",
                  "conformance": "UNTESTED", "registered_at": "...", "registered_by": "..." }] }]
```

서비스의 결과 타입을 가릅니다. `registerVersion` 은 지금 SemVer 형식 오류와 중복이 `Rejected` 하나로 접히고, 없는 `adapterId` 는 FK 위반으로 500 이 됩니다. 위 표의 400·404·409 를 내려면 서비스가 이 3가지를 서로 다른 결과로 돌려줘야 하므로, 컨트롤러만 더하는 변경으로는 끝나지 않습니다. `registerAdapter` 도 결과 타입을 가릅니다. 지금은 `ON CONFLICT DO NOTHING` 뒤 `Long` 만 돌려줘 새로 만들었는지 이미 있었는지 구분하지 못합니다. 201/200 을 내려면 «새로 만듦 / 이미 있음» 을 돌려줘야 합니다. vendor·name 이 비면 400 입니다.

**옛 `registerVersion` 의 같은 버전 재등록 거절은 유지합니다.** 같은 내용 재요청에 200과 같은 id를 반환하는 멱등 동작은 새 조작 문인 `declareVersion` 과 REST에만 적용합니다. 옛 메서드는 시험·하네스가 호출하며, 이 호출자들은 멱등을 기대하지 않기 때문입니다. `AdapterLifecycleTest` 의 `같은 버전을 두 번 등록하면 거부한다` 는 고치지 않고 그대로 통과합니다. 옛 `registerVersion` 은 새 메서드에 위임하여 SQL 경로를 1개로 유지합니다. 위임에 따라 바뀐 동작은 3곳입니다. 없는 제품은 FK 예외 대신 거절하고, 빈 version 과 빈 actor 는 저장 대신 거절합니다.

`registerAdapter` 는 그대로 두고, «새로 만듦 / 이미 있음» 을 가르는 새 메서드 `declareAdapter` 를 추가합니다. 실측한 `registerAdapter(` 호출은 18곳이며 시험 파일 14개에 있고, 그중 harness 는 3개입니다. 로컬 표준 빌드가 `:harness:test` 를 제외하므로 반환형을 바꾸면 문제가 CI 에서만 드러날 수 있습니다. 이 결정은 2026-10-07 P1 계획과 PR #79의 구현 결과를 근거로 기록합니다.

적합성 기록(`recordConformance`) API 는 열지 않습니다. C-3 비목표이며, 화면은 `UNTESTED` 를 보여 주기만 합니다.

`docs/commissioning.md` 를 갱신하고 도장을 다시 찍습니다. `DocumentClaimsTest` 가 문서 전체에서 `/operations/` 경로를 GET 까지 대조하므로, 새 경로 3개가 문서에 없으면 게이트가 막습니다.

바깥 첫 소비자가 picasso-ops 라는 사실을 이 변경의 근거로 ADR 9 에 맞춰 기록합니다. 소비자 없이 열어 둔 REST 가 아니라, 이 문서의 S1c 가 그 소비자입니다. 기록 자리는 picasso 설계 일지(`docs/superpowers/specs/2026-09-05-picasso-design.md` §15)의 새 항목입니다.

서비스의 행 타입 `AdapterRow`·`AdapterVersionRow` 는 camelCase 를 사용하며, `GET /operations/adapters` 의 snake_case 응답 모양은 컨트롤러가 응답 DTO 로 정합니다. 409 본문에는 `error` 와 기존 값 `existing_contract_semver` 를 싣습니다. 계약 SemVer 문자열 정규화는 후보로 남깁니다. 현재는 정규화하지 않으므로 앞뒤 공백이나 앞자리 0처럼 뜻이 같은 다른 표기도 409로 처리합니다.

---

## 6. 가짜 현장 `site/`

mimic CLI 를 그대로 쓰지 않고 Kotlin 런처 하나가 코드로 띄웁니다. 이유는 §11 에서 확인한 사실 3가지입니다. CLI 는 기동 때 생존 보고를 1회 보낸 뒤 시간을 진행시키는 루프가 없습니다. `Negotiate` 를 부르는 소비자가 없으면 핸드셰이크 보고도 0건입니다. registry 는 Spring Boot 플러그인이 없어 실행 jar 가 없고, 기동 때 Flyway 마이그레이션을 돌리지 않습니다. 이 3가지를 그대로 두면 S1 의 흐름에서 «보고로 `CONFIRMED`» 와 «퇴역 뒤 보고 감지» 가 성립하지 않습니다.

런처가 의존하는 picasso 모듈은 `mimic`, `uplink`, `registry` 의 3개입니다. registry 보고는 `uplink` 의 `RegistryLink` 로 나갑니다.

런처가 하는 일은 순서대로 4가지입니다.

| 순서 | 일 |
|---|---|
| ① | Postgres 에 registry 스키마를 Flyway 로 마이그레이션합니다. registry 스키마의 마이그레이션은 런처의 몫이며, ops 스키마는 운영 서비스가 맡습니다(§7.1) |
| ② | registry 를 클래스패스로 기동합니다(`RegistryApplicationKt`). DB 접속값, 토큰 2개, 프로파일 스키마 경로 `picasso.profile.schema` 를 넘깁니다 |
| ③ | `site/robots.json` 을 읽어 mimic N대를 가상 시계로 기동하고, 실제 1초마다 가상 1초를 진행시켜(1:1) 생존 보고가 계속 나가게 합니다 |
| ④ | 사이트 id 를 루트 `.env` 의 `SITE_ID` 에서 읽고 기체 선언·어댑터 인스턴스·mimic 이 모두 그 값을 씁니다 |

③ 에서 가상 시계를 쓰는 이유는 mimic 의 실시간 시계(`RealClock`)가 `advance` 에서 오류를 내기 때문입니다(`mimic/.../engine/Clock.kt`). 런처는 mimic 을 가상 시계로 띄우고 실제 1초마다 가상 1초를 진행시킵니다. 상태 발행(`publishStateIfDue`)은 가상 시각 기준으로 `publish_interval.max_seconds` 30초 간격이고, registry 는 생존 보고 시각을 자기 벽시계로 찍습니다. 비율이 1:1 이어야 실제 보고 간격이 30초가 되고, §7.3 의 기준 90초가 그 3배로 성립합니다. 비율을 바꾸면 §7.3 의 기준도 다시 정합니다.

사이트 id 와 기체 명부는 **한 출처에서 나옵니다**. 루트 `.env` 의 `SITE_ID` 는 런처와 운영 서비스가 같이 읽습니다. `site/robots.json` 은 기체마다 `robot_id`, `serial`, 프로파일 경로를 적은 파일이며, 런처가 이 파일을 읽어 mimic 을 띄우고, 운영자와 통합 시험도 이 파일을 보고 기체를 선언합니다. 현장으로 치면 기체 명판에 해당합니다.

④ 는 registry 가 사이트 id 일치를 대조하지 않기 때문에 둡니다. 대조하는 쪽이 없으니 값이 갈라져도 알 길이 없고, 한 출처에서 나오게 하는 것이 S1 에서 할 수 있는 전부입니다. 운영 서비스 쪽의 사전 거절은 §9 에 있습니다.

프로파일은 picasso 의 `profile/profiles/humanoid-a.json` 과 `profile/profiles/quadruped-b.json` 을 씁니다. 2개 모두 `publish_interval.max_seconds` 가 30 이며, 스키마는 `profile/schema/capability-profile.schema.json` 입니다. 이 30초가 §7.3 의 연결 기준 시간 기본값의 근거입니다.

카탈로그 초기화는 P2 뒤에 합니다. S1 의 런처는 카탈로그를 건드리지 않습니다.

선언 전 mimic 의 생존 보고는 registry 가 «등록되지 않은 기체» 로 거절하며 registry 에 남지 않습니다. 이 거절은 `site/` 로그에서만 보입니다. 운영 서비스는 registry 만 보므로 이 거절을 알 수 없고, 화면에는 보고 0회로만 나타납니다. 이것을 registry 에 남기는 일은 §12 의 변경 후보입니다.

---

## 7. 운영 서비스

### 7.1 조작 기록과 ops 스키마

운영 서비스는 모든 조작을 ops 스키마에 남깁니다. 같은 Postgres 의 별도 스키마이며 registry 스키마와 섞지 않습니다. 칸은 9개입니다. 요청 id, 모드(엔지니어/운영자), 사용자, 대상, 요청 내용, 사유, 결과(성공/거절/응답 없음), registry 응답, 시각입니다.

**조작 기록은 덧붙이기만 합니다.** 고치지 않고 삭제하지 않습니다. «응답 없음» 뒤 재조회 결과는 같은 요청 id 로 새 행을 붙이며, 그 행의 결과 칸은 «확인: 반영됨 / 반영 안 됨» 입니다. 처음 남긴 «응답 없음» 행은 그대로 둡니다. 칸 이름 목록은 위 9칸 그대로이며 재조회 행이라고 칸을 더하지 않습니다.

ops 스키마는 운영 서비스가 기동 때 Flyway 로 마이그레이션합니다. 위치는 `classpath:db/ops`, 스키마는 `ops` 이며, Flyway 이력 테이블도 `ops` 스키마 안에 둡니다. registry jar 의 마이그레이션은 `classpath:db/migration` 에 있으므로, 위치를 나눠야 서로의 마이그레이션을 집어 오지 않습니다. 운영 서비스의 설정 파일 이름은 `ops-service`(`spring.config.name`)로 두어 registry 의 `application.properties` 와 가리지 않게 합니다. registry 스키마는 `site/` 런처가 마이그레이션하며(§6 ①), 운영 서비스는 registry 스키마에 손대지 않습니다.

registry 에는 `X-Actor: <모드>/<사용자>` 로 보냅니다. registry `audit_log` 의 `actor` 와 조작 기록을 맞대 볼 수 있게 하기 위해서입니다. 모드는 인증 없이 화면이 요청 헤더로 싣습니다.

### 7.2 읽기와 쓰기

| 방향 | API |
|---|---|
| 읽기 | `GET /diag/robots?retired=true&site=<SITE_ID>`(기체 목록, `status`, `lastReportedAt`, `reportingAfterRetirement` 등) · `GET /diag/adapter-instances` · P1 의 `GET /operations/adapters` |
| 쓰기 | P1 의 API 2개 · `POST /operations/adapter-instances`(선택 단계, 수동 선언 기체에는 필수가 아님) · `POST /operations/robots`(선언 → `CLAIMED`) · `POST`·`DELETE /operations/robots/{robotId}/retirement` |

기체 목록은 항상 `GET /diag/robots?retired=true&site=<SITE_ID>` 로 읽습니다. 기본값(`retired=false`)은 퇴역 기체를 빼므로, 그대로 부르면 퇴역 뒤 보고와 복귀가 보이지 않습니다(§11). S1 의 흐름에서 퇴역 뒤 보고 감지와 복귀는 퇴역 기체가 목록에 있어야 성립하므로 이 질의값은 고정입니다. `site` 값은 §6 의 `.env` `SITE_ID` 입니다.

이 쓰기들이 S1 의 조작 전부입니다. 결정 7 에 따라 막힘 판정은 읽기 3개의 결과에서 운영 서비스가 계산하며, 이를 위한 조회를 registry 에 새로 더하지 않습니다.

### 7.3 기체 상태 칸 2개

| 칸 | 값 | 출처 |
|---|---|---|
| 원장 상태 | `CLAIMED`/`DISCOVERED`/`CONFIRMED`/`RETIRED`/`UNREGISTERED` | registry `status` 그대로 |
| 연결 | 신선/오래됨/보고 없음 | `lastReportedAt` 과 운영 서비스 설정의 기준 시간 |

2칸을 합치지 않습니다. 원장 상태는 registry 가 정한 값을 그대로 보이고, 연결은 운영 서비스가 `lastReportedAt` 을 기준 시간과 대조해 계산합니다. 기준 시간은 S1 에서 운영 서비스 설정에 두고, S2 에서 데이터로 옮깁니다.

**연결 기준 시간 기본값은 90초입니다.** 프로파일 보고 주기 상한 30초(§6)의 3배입니다. registry 의 `DEFAULT_FRESHNESS` 24시간은 능력 축소 판정에 쓰는 값이라 이 연결 판정과 관계없습니다(§11). 2개 값은 쓰임이 다르므로 한쪽을 다른 쪽에 맞추지 않습니다.

용어를 정해 둡니다. 기체 `CONFIRMED` 는 «계약으로 답한 적이 있음» 을 뜻합니다. registry 는 생존 보고 행 존재로 판정하며 사이트 명칭과 무관합니다(§11). 과거에 한 번 답했다는 뜻이므로 지금 연결돼 있다는 뜻은 아니고, 그래서 연결을 따로 봅니다. «시운전 완료» 와 «배정 가능» 은 S1d 전까지 화면에 쓰지 않습니다.

### 7.4 막힘과 거절

막힘은 열거형이며, 종류를 가르는 기준은 후속 조치입니다(근거 문서 §8.4). 사유 문장만 주면 읽는 쪽이 문자열을 대조하게 되므로 종류를 값으로 둡니다. 결정 10 에 따라 **열거를 2개로 나눕니다.** 기체마다 계산하는 상태 막힘과, 조작 1건마다 registry 응답에서 옮기는 조작 거절입니다. 여기에 기체별이 아닌 화면 전체 상태 1개가 더 있습니다.

상태 막힘은 기체별이며 4종입니다.

| 종류 | 조건 | 해결 담당 | 후속 행동 |
|---|---|---|---|
| `AWAITING_FIRST_REPORT` | `CLAIMED` 인데 보고 0회 | 현장(화면 밖) | 기체·어댑터 기동과 사이트 id 확인 |
| `REPORT_STALE` | `CONFIRMED` 인데 마지막 보고가 기준보다 오래됨 | 현장(화면 밖) | 연결 확인 |
| `REPORTING_AFTER_RETIREMENT` | registry `reportingAfterRetirement` 참 | 운영자 | 현장에서 기체를 내리거나 복귀 |
| `UNREGISTERED_ROW` | 어느 문으로도 안 들어온 행 | 엔지니어 | 조사 |

앞의 2개는 화면 밖에서 풀립니다. 화면은 무엇이 부족한지와 누가 채우는지를 보입니다. `REPORTING_AFTER_RETIREMENT` 만 운영자가 화면 안에서 복귀로 풀 수 있습니다.

화면 전체 상태는 2종입니다. `REGISTRY_SILENT` 는 registry 가 답하지 않을 때이고, `REGISTRY_UNAUTHORIZED` 는 registry 가 401 로 답할 때입니다. 둘 다 기체별이 아니라 전역으로 표시합니다. `REGISTRY_SILENT` 에서 화면은 «없음» 이 아니라 «모름» 을 보이고, 해결 담당은 엔지니어, 후속 행동은 registry 상태 확인입니다. `REGISTRY_UNAUTHORIZED` 의 해결 담당은 엔지니어, 후속 행동은 운영자 토큰 설정 확인입니다. 기체 행마다 이 값을 반복하지 않습니다(§8).

조작 거절은 조작별입니다. registry 가 응답한 400/404/409 를 아래 대응표로 옮깁니다. 409 는 본문으로 가르며 하나로 다루지 않습니다.

| 조작 | 응답 | 가르는 본문 | 종류 | 해결 담당 | 후속 행동 |
|---|---|---|---|---|---|
| 기체 선언 | 400 | - | 본문 오류 | 엔지니어 | 고쳐서 다시 |
| 기체 선언 | 409 | 본문에 `origin` | `WrongDoor` | 없음 | 고칠 것 없음(이미 다른 문으로 들어온 기체) |
| 기체 선언 | 409 | 본문 `status: RETIRED` | `RetiredAlready` | 운영자 | 복귀(화면 안) |
| 퇴역 | 400 | - | 사유 누락 등 | 운영자 | 사유를 넣어 다시 |
| 퇴역·복귀 | 404 | - | 모르는 기체 | 엔지니어 | 목록 새로 읽고 조사 |
| 제품·빌드 등록(P1) | 400 | - | 형식 오류 | 엔지니어 | 고쳐서 다시 |
| 빌드 등록(P1) | 404 | - | 없는 제품 | 엔지니어 | 제품 목록 새로 읽기 |
| 빌드 등록(P1) | 409 | - | 같은 버전에 다른 계약값 | 엔지니어 | 다른 버전 번호로 |
| 어댑터 인스턴스 등록 | 400 | - | 본문 오류(없는 빌드 id 등) | 엔지니어 | 고쳐서 다시 |

401 은 이 표에 넣지 않습니다. 401 은 운영 서비스의 운영자 토큰 설정이 registry 와 맞지 않는다는 뜻이므로, 조작별이 아니라 화면 전체 상태 `REGISTRY_UNAUTHORIZED` 로 보입니다. 해결 담당은 엔지니어, 후속 행동은 운영자 토큰 설정 확인입니다. 5xx 도 이 표에 넣지 않습니다. registry 가 응답했지만 반영 여부를 알 수 없으므로 «응답 없음» 과 같이 다루고, 재조회로 반영 여부를 맞춥니다(§9).

막힘마다 화면에 내는 칸은 5개입니다. 종류, 관측값과 기대값, 마지막 확인 시각, 해결 담당(화면 안/밖), 바로 갈 링크입니다. 마지막 확인 시각을 두는 이유는 그 판정이 언제 본 값으로 난 것인지를 같이 보이기 위해서입니다. 근거 문서 §8.4 의 «근거 판» 칸은 S1 에 판이 없어서 빼며 S2 에서 더합니다. 조작 거절도 같은 5칸으로 보이되, 관측값 자리에 registry 응답 코드와 가르는 본문이 들어갑니다.

---

## 8. 화면 (S1)

상단 메뉴는 대상별 5영역입니다. 현장·자원, 로봇·연결, 임무·정책, 운영, 이력. S1 에서 동작하는 것은 로봇·연결과 이력 2개이고, 나머지 3개 영역은 «다음 단계» 로 표시합니다.

모드 전환(엔지니어/운영자)은 인증 없이 둡니다. 등록과 어댑터 관련 조작은 엔지니어 모드에서, 퇴역·복귀는 운영자 모드에서 합니다. 모드는 §7.1 의 `X-Actor` 에 실려 registry 까지 갑니다.

| 영역 | 구성 |
|---|---|
| 로봇·연결 | 왼쪽 목록: 기체, 어댑터 인스턴스, 어댑터 제품·빌드. 오른쪽 상세: 원장 상태, 연결, 막힘 칸, 조작 버튼 |
| 이력 | 조작 기록 목록(행위자·사유·결과) |

결정 6 의 목록 + 상세 패널 배치입니다. 목록에서 기체 하나를 고르면 상세에 §7.3 의 상태 2칸과 §7.4 의 막힘 칸과 조작 버튼이 보입니다. 기체 목록은 §7.2 대로 퇴역 기체를 포함하므로, `RETIRED` 기체도 목록에 남고 상세에서 복귀 버튼이 보입니다.

**퇴역 사유는 화면에서 필수 입력입니다.** 사유 없이는 퇴역 요청을 보내지 않습니다. §7.4 의 퇴역 400(사유 누락 등)은 화면이 먼저 막는 자리이며, 그래도 registry 가 400 으로 답하면 대응표대로 보입니다. 사유는 §7.1 조작 기록의 사유 칸에 남습니다.

화면 전체 상태(`REGISTRY_SILENT`, `REGISTRY_UNAUTHORIZED`)는 상단 같은 자리에 보입니다. 기체 행이나 상세 패널에 기체별로 반복하지 않으며, 목록은 §9 대로 직전 값을 유지합니다.

화면 밖 작업을 화면이 명시합니다. 로봇 내부 지도와 웨이포인트 티칭(`commissioning.md` §2 Step 3)과 mimic 기동(`site/`)이 그것입니다. 화면은 이 작업이 있다는 것과 어디서 하는지를 보이고, 완료를 대신 체크하지 않습니다.

---

## 9. 오류 처리

결과는 3값입니다. 알 수 있음, 없음, 모름. registry 가 답하지 않은 것을 «없음» 으로 접지 않습니다.

| 상황 | 처리 |
|---|---|
| registry 무응답 | 목록을 비우지 않습니다. 직전 값과 «확인 불가, 시각» 을 보입니다. 화면 전체 상태는 `REGISTRY_SILENT` 입니다(§7.4) |
| 조작 요청이 응답 없이 끝남 | 조작 기록에 «응답 없음» 을 남기고, 이어서 registry 를 다시 조회해 반영 여부를 맞춥니다. 재조회 결과는 같은 요청 id 의 새 행으로 붙입니다(§7.1). 화면에는 «반영되었을 수 있음, 확인 중» 을 보입니다 |
| registry 거절(400/404/409) | §7.4 의 조작 거절 대응표로 옮깁니다 |
| 사이트 id 불일치 | 운영 서비스가 `.env` 의 `SITE_ID` 와 다른 값을 registry 에 보내기 전에 거절합니다 |

registry 거절은 거절 종류에 따라 다르게 보입니다. 이미 다른 문으로 들어온 기체(`WrongDoor`, 409)는 «고칠 것 없음» 입니다. 기체는 이미 registry 에 있고 운영자가 할 일이 없습니다. 같은 409 라도 본문이 `status: RETIRED` 이면 `RetiredAlready` 이며 운영자가 화면 안에서 복귀로 풉니다. **409 를 하나로 다루지 않습니다.** 본문 오류(400)는 «고쳐서 다시» 입니다. 코드가 같아도 종류와 담당이 다르므로 응답 코드만으로 접지 않고 §7.4 의 대응표를 거칩니다.

응답 없음은 거절과 다릅니다. 요청이 registry 에 닿았는지 모르는 상태이므로 조작 기록에는 «응답 없음» 으로 남기고, 다시 조회한 결과로 반영 여부를 채웁니다. 응답을 받지 못했다는 사실만으로 반영되지 않았다고 판단하지 않으며, 확인 전에는 성공으로도 다루지 않습니다. 없는 대상을 가리키는 404 는 registry 가 응답한 거절이므로 무응답과 섞지 않습니다.

선언·퇴역·복귀·P1 등록·어댑터 인스턴스 등록은 모두 멱등입니다. 같은 요청의 재시도가 같은 결과를 냅니다. 그래도 «응답 없음»(또는 5xx) 뒤에는 재시도 전에 먼저 재조회합니다. 재조회로 반영 여부가 확인되면 그 결과를 기록하고 끝내며, 반영되지 않았을 때만 재시도합니다. 재시도는 새 요청 id 를 받는 새 조작입니다.

재조회의 «반영됨» 판정 기준은 조작마다 다음과 같습니다.

| 조작 | 반영됨 판정 |
|---|---|
| 선언 | 기체 목록에 그 `robot_id` 가 있음 |
| 퇴역 | 그 기체가 `RETIRED` |
| 복귀 | 그 기체가 `RETIRED` 가 아님 |
| 제품·빌드 등록 | `GET /operations/adapters` 목록에 있음 |
| 어댑터 인스턴스 등록 | `GET /diag/adapter-instances` 목록에 있음 |

사이트 id 의 사전 거절은 §6 ④ 와 짝입니다. 화면에는 사이트 입력란이 없고, 운영 서비스가 `SITE_ID` 로 사이트 칸을 채워 registry 에 보냅니다. 사전 거절은 운영 서비스 API 를 화면 밖에서 직접 부르며 다른 사이트 값을 실은 요청에 걸립니다. registry 가 대조하지 않으므로 보내는 쪽에서 막습니다.

---

## 10. 시험

| 대상 | 시험 |
|---|---|
| P1(picasso) | 서비스 `AdapterDeclarationTest` 10개와 표면 `AdapterEndpointTest` 8개로 응답 코드 201·200·400·404·409를 검증합니다. 결함 주입 8건 모두 이름 있는 시험이 탐지합니다. `AdapterLifecycleTest` 의 `같은 버전을 두 번 등록하면 거부한다` 는 고치지 않고 통과합니다(§5). `DocumentClaimsTest` 도 통과합니다. |
| 막힘·거절 판정(ops-service) | 표 형식 단위 시험. 상태 막힘은 종류마다 경계(기준 시간 직전·직후)를 넣고, 조작 거절은 §7.4 대응표의 행마다 응답 코드와 본문을 넣습니다. 결함 주입으로 확인합니다 |
| 통합(e2e, S1a·S1b) | 한 JVM 에서 Testcontainers Postgres → registry 스키마 마이그레이션 → ops 스키마 마이그레이션 → registry → mimic 2대 → 운영 서비스 API 로 선언(`CLAIMED`) → 보고(`CONFIRMED`) → 퇴역 → 퇴역 뒤 보고 감지 → 복귀 → 조작 기록 확인. registry 를 멈췄을 때 «모름» 이 나오는지도 확인합니다 |
| 통합(e2e, S1c) | 같은 스택에서 제품 등록 → 빌드 등록 → 인스턴스 등록 → 목록에 `UNTESTED` 표시. P1 거절(400/404/409)이 대응표대로 보이는지 확인합니다 |
| 화면 | vitest 컴포넌트 시험. Playwright 로 실행 중인 전체 스택에서 위 흐름을 1회 돌립니다. Playwright 는 CI 에서도 돌립니다 |
| CI | GitHub Actions. 서브모듈까지 체크아웃하며 Ubuntu 러너에서 Docker 를 씁니다. JDK 21 입니다. Gradle 빌드와 npm 의 UI 빌드를 따로 돌리며, CI 가 2개 모두 돌립니다 |

통합 시험의 흐름은 §1 의 «S1 이 입증하는 것» 과 같습니다. 화면 시험은 같은 흐름을 브라우저에서 1회 돌리는 것이고, 조작 하나하나의 경계는 단위 시험이 맡습니다. S1c 통합 시험은 §3 의 S1c 완료 판정 그대로입니다.

**통합 시험의 Postgres 와 Flyway 는 registry 의 `testFixtures` 를 재사용합니다.** registry 의 `testFixtures` 는 Testcontainers 와 Flyway 를 api 로 내보냅니다(§11). 컨테이너 기동기를 picasso-ops 에 복사하지 않습니다. registry 의 `build.gradle.kts` 가 복사본을 «두 번째 진실» 로 막고 있으므로, 복사하면 picasso 쪽 규율과 어긋납니다. 통합 시험이 `site/` 런처와 같은 Flyway 로 registry 스키마를 올리고, 운영 서비스 기동으로 ops 스키마를 올린 뒤 흐름에 들어갑니다.

통합 시험은 한 JVM 에서 registry 와 운영 서비스를 각각 무작위 포트로 띄웁니다. registry 설정은 실행 인자로만 넣습니다. mimic 의 가상 시계는 시험이 직접 전진시켜, 실시간 대기 없이 보고를 만듭니다(§6 ③). registry `testFixtures` 를 포함 빌드로 쓸 수 있는지(좌표 치환과 testFixtures 의 맞물림)와 `PostgresSupport` 의 `reset()` 이 지우는 범위에 ops 스키마가 들어가는지는 S1a 의 첫 작업으로 먼저 확인합니다.

Playwright 는 실행 중인 전체 스택 앞에서 돕니다. 스택은 docker compose 의 Postgres, `site/` 런처(registry 와 mimic), 운영 서비스, Vite 미리보기 순으로 띄웁니다. 각 단계는 준비 확인(registry 와 운영 서비스의 응답, Vite 의 페이지 응답)을 거친 뒤 다음 단계로 갑니다. Playwright 는 1:1 시계(§6 ③)로 돌므로 보고를 기다리는 단계마다 최대 60초를 기다립니다. S1a 완료 판정(§3)의 «registry 를 멈추면 모름» 은 통합 시험(API)으로 보고, 화면 표시는 S1b 의 Playwright 가 봅니다.

판정은 종료 코드가 아니라 시험 결과 XML 의 실패 시험 이름으로 합니다. picasso 와 같은 규율입니다. 결함 주입에서 시험이 잡았다고 말하려면 어느 시험이 빨개졌는지가 XML 에 있어야 합니다.

---

## 11. 코드로 확인한 사실과 근거

picasso 저장소 2026-10-07 main `cd688ff` 기준입니다. 이 문서의 판단은 아래 사실에 기대며, picasso 가 바뀌면 다시 확인합니다. S1c 에서 서브모듈 포인터를 옮기면(§3) 그 커밋 기준으로 다시 확인합니다.

| 사실 | 근거 |
|---|---|
| 어댑터 제품·빌드 등록 컨트롤러 없음 | `registry/src/main/kotlin/dev/picasso/registry/adapter/AdapterService.kt`, `registry/.../web/OperationsController.kt` |
| `registerAdapter` 는 새로 만듦과 이미 있음을 구분하지 않음 | `AdapterService.kt` |
| 기체 `CONFIRMED` 는 `robot_liveness` 행 존재로 판정 | `registry/.../binding/RobotRegistration.kt` 의 `statusOf` |
| `/diag/robots` 의 `retired` 기본값 `false`(퇴역 제외) | `registry/.../web/DiagController.kt`, `RobotRegistration.kt` 의 `list` |
| 선언 409 는 `WrongDoor`(본문 `origin`)와 `RetiredAlready`(본문 `status: RETIRED`) 2종 | `OperationsController.kt` |
| 퇴역 400(사유 누락 등)·404(모르는 기체) | `OperationsController.kt` |
| 사이트 명칭 기록은 활성 바인딩이 없으면 404 | `registry/.../binding/SiteNameRegistration.kt`, `OperationsController.kt` |
| 프로파일 개정판·활성화·바인딩 REST 없음 | `registry/.../binding/BindingService.kt` 의 `bind` |
| 선언 안 된 기체의 생존 보고는 거절되고 남지 않음 | `registry/.../ingest/LivenessService.kt` |
| registry 는 기동 때 Flyway 를 돌리지 않음, Spring Boot 플러그인·`bootJar` 없음 | `registry/.../web/RegistryApplication.kt`, `registry/build.gradle.kts` |
| registry `testFixtures` 가 Testcontainers·Flyway 를 api 로 내보냄 | `registry/build.gradle.kts` |
| mimic CLI 에 시간 진행 루프 없음 | `mimic/src/main/kotlin/dev/picasso/mimic/cli/Main.kt` |
| mimic 의 실시간 시계는 `advance` 에서 오류를 냄(가상 시계만 전진) | `mimic/src/main/kotlin/dev/picasso/mimic/engine/Clock.kt` |
| 같은 버전 재등록 거절을 단언하는 기존 시험 | `registry/src/test/kotlin/dev/picasso/registry/AdapterLifecycleTest.kt` 의 `같은 버전을 두 번 등록하면 거부한다` |
| 프로파일 `humanoid-a.json`·`quadruped-b.json` 의 `publish_interval.max_seconds` 30 | `profile/profiles/` |
| 운영자 토큰 경로 `/operations/**`, 조작 POST 에 `X-Actor` 필수 | `registry/.../web/OperatorToken.kt`, `OperationsController.kt` |
| 서브프로젝트에 Gradle `group` 없음 | picasso 루트와 모듈 `build.gradle.kts` |
| 판: Kotlin 2.4.20, Spring Boot 3.4.0 | `gradle/libs.versions.toml` |
| registry 의 보고 신선도 기본값 24시간(능력 축소 판정용) | `registry/.../ledger/RobotObservability.kt` 의 `DEFAULT_FRESHNESS` |

---

## 12. 다음 단계와 picasso 변경 후보

다음은 P2 와 S1d 설계입니다. 바인딩, 사이트 명칭, 시운전 완료를 다룹니다. 이어서 S2~S4 로 갑니다. S2 에서는 §7.3 의 연결 기준 시간을 데이터로 옮기고 §7.4 의 «근거 판» 칸을 더합니다.

아래는 picasso 변경 후보이며 결정이 아닙니다. 소비자가 붙을 때 ADR 9 에 따라 짓습니다.

| 후보 | 비고 |
|---|---|
| 선언 안 된 기체의 보고 거절을 registry 에 남기기 | 지금은 `site/` 로그에서만 보입니다(§6) |
| 사이트 id 일치 대조 | 지금은 registry 가 대조하지 않아 `.env` 의 `SITE_ID` 한 출처와 운영 서비스의 사전 거절로 맞춥니다(§6·§9) |
| P2 의 카탈로그·개정판·바인딩 REST | S1d 의 선행 변경입니다(§1) |

앞의 2개는 S1 에서 picasso 밖에서 우회하는 자리입니다(§6·§9).
