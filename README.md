# picasso-ops

picasso 를 라이브러리로 쓰는 담는 측 저장소입니다. 로봇, 임무, 엔드포인트의 운영 가능성을 PoC 로 입증합니다. 실물 현장은 없습니다. 보안과 인증은 생략합니다. 운영 중 변경은 코드 수정이 아니라 관리 화면에서 처리합니다.

지금 단계는 S1c 어댑터 등록입니다. S1b 로봇 생애주기 위에 올립니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 운영자 모드는 퇴역과 복귀를 합니다. 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.

## 구성

- `.env`: 사이트 id(`SITE_ID`), DB 접속값, 포트, 토큰을 둡니다. 로컬 PoC 값입니다. 런처, 운영 서비스, 시험, CI 가 이 파일 하나를 읽습니다.
- `picasso/`: picasso git 서브모듈입니다. 고정 커밋을 가리킵니다. Gradle includeBuild 로 가져옵니다. 읽기 전용입니다. picasso 쪽 변경은 picasso 저장소의 PR 로 냅니다.
- `site/`: 가짜 현장 런처입니다(Kotlin). registry 와 mimic 기체를 한 프로세스에서 띄웁니다. 실제 1초마다 가상 1초를 진행합니다. 기체 명부는 `site/robots.json` 입니다. Postgres 용 compose 는 `site/compose.yaml` 입니다. 손 기동 확인은 `site/smoke.sh` 입니다.
- `ops-service/`: 운영 서비스입니다(Kotlin, Spring Boot). 화면의 유일한 백엔드입니다. 운영자 토큰을 쥡니다. registry REST 만 부릅니다.
- `ui/`: 관리 화면입니다(React, Vite, TypeScript). 운영 서비스만 부릅니다.
- `e2e/`: 통합 시험입니다. 한 JVM 에 Postgres, registry, mimic, 운영 서비스를 띄웁니다.
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
./gradlew :site:installDist :ops-service:installDist
```

```bash
cd ui && npx playwright install chromium
```

그다음 돌립니다.

```bash
cd ui && npx playwright test
```

Playwright 가 Postgres, 런처, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 1초에 가상 1초가 갑니다. 시험은 약 1~2분 걸립니다. Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.

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
./gradlew :site:installDist :ops-service:installDist
```

그다음 터미널 3개를 엽니다. 각 터미널에서 먼저 `.env` 를 읽습니다.

```bash
set -a; . ./.env; set +a
```

터미널 1에서 런처를 띄웁니다.

```bash
site/build/install/site/bin/site
```

터미널 2에서 운영 서비스를 띄웁니다.

```bash
env -u PICASSO_INGEST_TOKEN ops-service/build/install/ops-service/bin/ops-service
```

터미널 3에서 화면을 띄웁니다.

```bash
cd ui && npm ci && npm run dev
```

Gradle 의 run 작업 2개를 한 작업 트리에서 겹쳐 띄우지 않습니다.

## 알아 둘 것

- 토큰은 2개입니다. 적재 토큰은 site 만 받습니다. 운영 서비스는 운영자 토큰만 받습니다. 어느 토큰도 브라우저로 가지 않습니다.
- registry 와 운영 서비스는 `127.0.0.1` 에만 엽니다.
- 선언 전 mimic 의 생존 보고는 registry 가 거절합니다. 거절한 보고는 어디에도 남지 않습니다. 기체를 선언하면 보고가 붙습니다.
- registry 가 답하지 않으면 화면은 목록을 비우지 않습니다. 직전 값과 함께 모름을 보입니다.
- 손으로 띄운 compose 스택이 있으면 화면 시험이 시작할 때 그것을 볼륨째 내립니다. 같은 compose 프로젝트라서입니다. 데이터가 지워집니다. 남은 컨테이너는 `docker compose -f site/compose.yaml --env-file .env down -v` 로 걷습니다.
