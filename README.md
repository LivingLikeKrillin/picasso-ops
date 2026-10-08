# picasso-ops

picasso 를 라이브러리로 쓰는 담는 측 저장소입니다. 로봇, 임무, 엔드포인트의 운영 가능성을 PoC 로 입증합니다. 실제 하드웨어 현장은 없습니다. 보안과 인증은 생략합니다. 운영 중 변경은 코드 수정이 아니라 관리 화면에서 처리합니다.

지금 단계는 S3a 실행 호스트와 작업 지시입니다. S2 현장 설정 위에 올립니다. 서브모듈 `picasso` 는 P4 머지 커밋 `1e3f4ae` 를 가리킵니다. 그 버전에는 P2a 시험 실행기, P2b 리비전·바인딩 REST, P3 임무 정의 버전, P4 스트림 재부착과 mimic 엔진 잠금이 들어 있습니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 같은 영역 왼쪽에 프로파일 구역이 있습니다. 그 구역에서 엔지니어 모드는 기종 프로파일 리비전을 파일로 골라 제출하고, 시험을 요청하고, 활성화합니다. 시험 결과는 사람이 적지 않습니다. 런처가 띄운 시험 실행기가 적습니다. 기체 상세에서 엔지니어 모드는 빌드와 활성 리비전을 골라 바인딩하고, 사이트 명칭 등록을 기록합니다. 명칭 티칭은 화면 밖 현장 작업입니다. 기체 목록에 시운전 칸이 있습니다. 값은 완료, 미완, 퇴역입니다. 완료 조건은 셋입니다. 원장 상태가 `CONFIRMED` 이고 퇴역이 아니어야 합니다. 활성 바인딩이 있어야 합니다. 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 여야 합니다. 빠진 조건은 막힘으로 보입니다. 운영자 모드는 퇴역과 복귀를 합니다. 어댑터 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 현장·자원 영역에는 현장 설정 구역이 있습니다. 지금 버전, 연결 기준 시간, 허용 범위(60~3600초), 버전 이력을 보입니다. 엔지니어 모드는 연결 기준 시간을 바꿉니다. 바꿀 때는 사유가 필요합니다. 바꾸면 새 버전이 생깁니다. 다음 기체 목록 읽기부터 그 값으로 연결 칸과 막힘을 판정합니다. 재기동은 필요 없습니다. 운영자 모드는 바꾸지 못합니다. 막힘 카드는 어느 현장 설정 버전으로 판정했는지를 근거 버전으로 보입니다. 기체 상세에는 연결 판정 기준이 보입니다. 기준 초와 버전입니다. 운영 영역에서 운영자 모드는 작업 지시를 냅니다. 임무는 코드 정의 임무 2개(`InspectAsset`, `PrepareSequencedRack`)입니다. `InspectAsset` 은 점검 대상의 id 와 장소 이름을 넣습니다. `PrepareSequencedRack` 은 셀 대역의 슬롯과 자재를 고릅니다. 제시 자리는 자재에서 정해집니다. 폼을 채우면 기체별 배정 가능 표가 보입니다. 배정 가능 조건은 넷입니다. 시운전 완료, 연결 신선, 도는 실행 없음, 스킬 적합입니다. 앞의 둘은 운영 서비스가, 뒤의 둘은 실행 호스트가 판정합니다. 못 물어본 칸은 모름이고 모름이 있으면 배정 불가입니다. 운영 서비스는 배정 가능한 기체만 후보로 실행 호스트에 넘깁니다. 실행 호스트의 picasso 미들웨어가 후보 중 하나를 골라 mimic 기체에서 실행합니다. 엔지니어 모드는 작업 지시를 내지 못합니다. 실행 목록은 실행 상태, 단위 상태와 근거 등급, 작업 응답, 임무 버전을 보입니다. 코드 정의 임무의 임무 버전은 `코드 정의` 로 보입니다. 셀 대역 표는 제시 자리와 슬롯의 점유와 자재를 보입니다. 임무 버전 저장과 편집은 S3b, 미들웨어 시간값과 인시던트 기록의 현장 설정 버전은 S3c 의 몫입니다. S1 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. P2·S1d 설계 스펙은 `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` 에 있습니다. S2 설계 스펙은 `docs/superpowers/specs/2026-10-08-s2-site-settings-design.md` 에 있습니다. S3a 설계 스펙은 `docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.

## 구성

- `.env`: 사이트 id(`SITE_ID`), DB 접속값, 포트, 토큰을 둡니다. 로컬 PoC 값입니다. 런처, 실행 호스트, 운영 서비스, 시험, CI 가 이 파일 하나를 읽습니다.
- `picasso/`: picasso git 서브모듈입니다. 고정 커밋을 가리킵니다. Gradle includeBuild 로 가져옵니다. 읽기 전용입니다. picasso 쪽 변경은 picasso 저장소의 PR 로 냅니다.
- `site/`: 가짜 현장 런처입니다(Kotlin). registry, mimic 기체, 리비전 시험 실행기(`site-runner`), 셀 대역을 한 프로세스에서 띄웁니다. 기동 직후 mimic 가상 시계를 실제 시각까지 한 번 밉니다. 그 뒤 실제 1초마다 가상 시계를 실제 시각까지 따라잡게 밉니다. 그래서 가상 시각은 실제 시각보다 앞서지 않고 차이는 1초 이내입니다. mimic gRPC 는 `.env` 의 `MIMIC_GRPC_PORT` 에 엽니다. 실행 호스트가 이 포트에 붙습니다. 셀 대역은 셀 신호를 자동으로 내는 대역입니다. 제시 자리 `SEQ-IN-02.BIN-A` 는 늘 `ENGINE-COVER-A` 를 듭니다. 슬롯 `RACK-204.S01`~`S04` 는 처음에 비어 있습니다. 기체가 `pick_place` 를 성공하면 그 슬롯을 제시 자리의 자재로 채웁니다. 슬롯은 비우지 않습니다. 기체가 보고한 배치에서 채우므로 독립 설비 확인이 아닙니다. 셀 대역은 `.env` 의 `SITE_CELL_PORT` 에 루프백 `GET /cell` 로 냅니다. 실행기는 실제 1초마다 registry 에서 시험 요청을 집습니다. 실행기는 시험마다 자기 mimic 을 따로 띄웁니다. 그래서 현장 기체의 보고와 상태를 바꾸지 않습니다. 적재 토큰은 런처만 가집니다. 그래서 시험 결과를 적을 수 있는 것도 런처뿐입니다. 기체 명부는 `site/robots.json` 입니다. 기체 명부의 `site_names` 는 현장에서 기체에 티칭한 명칭입니다. 런처가 기동 때 기체에 넣습니다. Postgres 용 compose 는 `site/compose.yaml` 입니다. 손 기동 확인은 `site/smoke.sh` 입니다.
- `mission-host/`: 실행 호스트입니다(Kotlin, Spring Boot). picasso 미들웨어를 세워 mimic 기체에서 작업 지시를 실행합니다. 250ms 마다 셀 대역을 읽고 미들웨어를 pump 합니다. 메모리만 씁니다. 재기동하면 실행이 사라집니다. 토큰을 받지 않습니다. `.env` 의 `HOST_PORT` 에 루프백으로 엽니다. 호출자는 운영 서비스뿐입니다.
- `ops-service/`: 운영 서비스입니다(Kotlin, Spring Boot). 화면의 유일한 백엔드입니다. 운영자 토큰을 쥡니다. registry REST 와 실행 호스트 REST 를 부릅니다. picasso 모듈을 쓰지 않습니다.
- `ui/`: 관리 화면입니다(React, Vite, TypeScript). 운영 서비스만 부릅니다.
- `e2e/`: 통합 시험입니다. 한 JVM 에 Postgres, registry, mimic, 실행 호스트, 운영 서비스를 띄웁니다. 실행 호스트의 시계는 현장 가상 시계이고 시험이 시계를 직접 밉니다.
- `docs/`: 설계 스펙과 구현 계획입니다.

## 선행 도구

- Docker: 시험과 Postgres 에 씁니다.
- JDK 21 이상: 화면 시험(Playwright)과 `site/smoke.sh` 가 배포본을 이 JDK 로 바로 띄웁니다. Gradle 이 JDK 21 툴체인을 받습니다.
- Node 22

## 받기

```bash
git clone --recurse-submodules https://github.com/LivingLikeKrillin/picasso-ops.git
```

이미 받았으면 서브모듈만 채웁니다.

```bash
git submodule update --init
```

서브모듈이 없으면 첫 빌드가 버전 카탈로그 경로에서 멈춥니다.

Windows 에서 `Filename too long` 이 나면 다음 2개를 차례로 실행합니다.

```bash
git -C picasso config core.longpaths true
```

```bash
git -C picasso checkout -f
```

## 시험

```bash
./gradlew build
```

```bash
cd ui && npm ci && npm test
```

판정은 종료 코드로 하지 않습니다. `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 합니다.

화면 시험(Playwright)은 따로 돌립니다. 먼저 아래 2개를 한 번 합니다.

```bash
./gradlew :site:installDist :mission-host:installDist :ops-service:installDist
```

```bash
cd ui && npx playwright install chromium
```

그다음 돌립니다.

```bash
cd ui && npx playwright test
```

Playwright 가 Postgres, 런처, 실행 호스트, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 이어서 리비전 제출, 시험 요청, 실행기의 `TESTED` 기록, 활성화, 바인딩, 명칭 기록, `humanoid-01` 시운전 완료까지 돕니다. `quadruped-01` 의 명칭 기록이 기체가 아는 명칭 없음으로 막히는 것도 봅니다. 현장·자원 영역에서 연결 기준 시간을 120초로 바꿔 버전 2 와 이력 행을 봅니다. 운영자 모드에서는 바꾸지 못하는 것과 기체 상세의 연결 판정 기준이 버전 2 로 바뀐 것도 봅니다. 운영 영역에서 운영자 모드로 `InspectAsset` 작업 지시를 냅니다. 시운전 완료인 `humanoid-01` 만 배정 가능이고 배정됩니다. 실행 목록에 `코드 정의` 행이 보이고 실행이 `PHYSICALLY_DONE` 으로 끝납니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 시각을 따라갑니다. 시험은 약 3분 걸립니다. 로컬 실측은 3.1분입니다. Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.

## 띄우기

한 번에 확인할 때는 다음을 실행합니다. 이 스크립트는 Postgres 를 띄웁니다. 런처와 운영 서비스를 배포본으로 띄웁니다. 기체 하나를 선언해 `CONFIRMED` 가 되는지 봅니다. 그 뒤 모두 정리합니다.

```bash
bash site/smoke.sh
```

오래 띄울 때는 먼저 Postgres 와 배포본을 준비합니다.

```bash
docker compose -f site/compose.yaml --env-file .env up -d --wait
```

```bash
./gradlew :site:installDist :mission-host:installDist :ops-service:installDist
```

그다음 터미널 4개를 엽니다. 각 터미널에서 먼저 `.env` 를 읽습니다.

```bash
set -a; . ./.env; set +a
```

터미널 1에서 런처를 띄웁니다.

```bash
site/build/install/site/bin/site
```

터미널 2에서 실행 호스트를 띄웁니다.

```bash
env -u PICASSO_INGEST_TOKEN -u PICASSO_OPERATOR_TOKEN mission-host/build/install/mission-host/bin/mission-host
```

터미널 3에서 운영 서비스를 띄웁니다.

```bash
env -u PICASSO_INGEST_TOKEN ops-service/build/install/ops-service/bin/ops-service
```

터미널 4에서 화면을 띄웁니다.

```bash
cd ui && npm ci && npm run dev
```

Gradle 의 run 작업 2개를 한 작업 트리에서 겹쳐 띄우지 않습니다.

## 알아 둘 것

- 토큰은 2개입니다. 적재 토큰은 site 만 받습니다. 운영 서비스는 운영자 토큰만 받습니다. 어느 토큰도 브라우저로 가지 않습니다.
- registry, 실행 호스트, 운영 서비스, 셀 대역은 `127.0.0.1` 에만 엽니다. mimic gRPC 는 picasso 가 주소를 정하므로 모든 인터페이스에 열립니다. 인증 없는 기체 제어 API 표면이고 포트가 `.env` 로 고정됩니다.
- 실행 호스트 REST 는 인증이 없습니다. 같은 기계의 다른 프로세스는 운영자 모드 검사 없이 작업 지시를 낼 수 있습니다.
- `pick_place` 에는 실패 모드가 있습니다(단위 하나에 약 6%). 기체별 시드로 추첨하므로 런처로 PrepareSequencedRack 을 돌리면 슬롯 넷짜리 작업 지시는 약 22% 가 어딘가에서 실패하고, 실패는 실행 목록에 보입니다. 화면 시험은 실패 모드가 없는 InspectAsset 만 돌립니다.
- 운영자 보류를 푸는 수단이 아직 없습니다. 보류에 선 실행의 기체는 실행 호스트를 재기동할 때까지 배정 불가입니다.
- 선언 전 mimic 의 생존 보고는 registry 가 거절합니다. 거절한 보고는 어디에도 남지 않습니다. 기체를 선언하면 보고가 붙습니다.
- registry 가 답하지 않으면 화면은 목록을 비우지 않습니다. 직전 값과 함께 모름을 보입니다.
- 손으로 띄운 compose 스택이 있으면 화면 시험이 시작할 때 그것을 볼륨째 내립니다. 같은 compose 프로젝트라서입니다. 데이터가 지워집니다. 남은 컨테이너는 `docker compose -f site/compose.yaml --env-file .env down -v` 로 걷습니다.
- `site/robots.json` 에서 `humanoid-01` 은 `dock-3` 와 `bay-7` 을 티칭했고, `quadruped-01` 은 티칭하지 않았습니다. 그래서 `quadruped-01` 은 명칭을 기록하면 기체가 아는 명칭 없음으로 막힙니다.
- registry 는 명칭의 개수만 대조합니다. 이름 자체는 대조하지 않습니다.
- 시험 결과는 사람이 적지 않습니다. 적재 토큰은 런처만 가지므로 런처가 띄운 시험 실행기만 적습니다.
