# picasso-ops 화면 문서

picasso-ops 관리 화면(`ui/`)을 시각 디자이너에게 넘기려고 만든 문서 묶음입니다. 디자이너가 브랜드를 입히고 화면을 다듬을 때, 무엇을 마음대로 바꿔도 되고 무엇을 지키거나 시험과 함께 바꿔야 하는지를 한곳에서 볼 수 있게 했습니다.

## 기준 버전

| 항목 | 값 |
|---|---|
| picasso-ops `main` | `9606364` (PR #14 머지, S4b 재기동 복원) |
| 서브모듈 picasso | `74e4d3d` (picasso PR #86 머지, P6 `Middleware.resume`) |
| 스크린숏 촬영 스크립트 | 브랜치 `docs/screen-design` 의 `90def83` (`ui/screens/capture.spec.ts`, `ui/playwright.screens.config.ts`) |
| 스크린숏과 목록 | 같은 브랜치의 `466906a` (`docs/screens/img/*.png`, `docs/screens/img/INDEX.md`) |

문서의 모든 사실은 위 버전의 코드(`ui/src/**`), 시험(`ui/e2e/lifecycle.spec.ts`, `ui/src/**/*.test.tsx`), 스펙(`docs/superpowers/specs/`)에서 확인했습니다. 행 번호도 이 버전 기준입니다.

## 누가 읽는가

| 읽는 사람 | 먼저 볼 곳 |
|---|---|
| 시각 디자이너 | `screen-design.md` §1(제품과 사용자), §3(영역별 화면), §4(리터치 지침) |
| 화면을 고치는 개발자 | `screen-design.md` §4.2(지킬 것), §5(시험이 기대는 글자), `development.md` |
| 스펙을 대조하는 사람 | `screen-design.md` §6(알려진 어긋남) |

## 파일

| 파일 | 내용 |
|---|---|
| `README.md` | 이 문서. 묶음의 입구, 스크린숏 다시 찍는 법 |
| `screen-design.md` | 화면 설계서. 제품과 사용자, 전역 구조, 영역별 화면, 리터치 지침, 시험이 기대는 글자, 알려진 어긋남 |
| `development.md` | 화면 개발 내역. 단계와 PR, 컴포넌트 파일별 이력, 기술 스택, 파일 지도, 운영 서비스와 말하는 법, 로컬 실행, CI |
| `img/NN-*.png` | 스크린숏 65장 |
| `img/INDEX.md` | 스크린숏 목록. 파일마다 영역, 구역, 모드, 상태, 만든 방법, 보이는 주요 문구. 촬영 스크립트가 씁니다. 손으로 고치지 않습니다 |

## 읽는 법

- «…» 안의 글자는 화면이나 코드에 있는 글자 그대로입니다. `${…}` 는 코드가 값을 끼워 넣는 자리입니다.
- 파일 위치는 따로 적지 않으면 `ui/src/components/` 기준입니다. 예를 들어 `RobotsArea.tsx:45` 는 `ui/src/components/RobotsArea.tsx` 45행입니다. `App.tsx`, `api.ts`, `labels.ts`, `areas.ts`, `poll.ts`, `jobOrderDraft.ts`, `styles.css` 는 `ui/src/` 바로 아래에 있습니다.
- `PW:N` 은 `ui/e2e/lifecycle.spec.ts` N행입니다. `X.test:N` 은 그 vitest 파일 N행입니다.
- 화면 글자 가운데 용어집의 옛 이름(예 «거절»)이 남은 곳이 있습니다. 문서는 화면 글자를 그대로 옮기고 그 자리를 표시합니다(`screen-design.md` §6).
- 스크린숏 이름은 `NN-영역-구역-상태[-mock].png` 꼴입니다. 번호는 찍은 순서이고, 흐름(빈 상태 → 기체·어댑터·프로파일 → 현장 설정·장애 주입 → 작업 지시 → 임무 → 인시던트·판단 → 송신 기록 → 대역 상태 → registry 불통)을 따릅니다.

## 스크린숏

### 찍은 조건

| 항목 | 값 | 근거 |
|---|---|---|
| 화면 크기 | 1440×900, 배율 1 | `ui/playwright.screens.config.ts` |
| 색 구성 | 밝은 색(`colorScheme: 'light'`) | 같은 파일 |
| 언어 | `ko-KR` | 같은 파일 |
| 범위 | 전체 페이지(`fullPage`) 또는 구역(region) 하나 | `ui/screens/capture.spec.ts:51-63` |
| 확인 | 찍기 전에 그 화면에 있어야 할 문구를 단언합니다. 그 문구가 `INDEX.md` 의 «보이는 주요 문구» 칸입니다 | `capture.spec.ts:53` |

전체 페이지 스크린숏은 높이가 900을 넘을 수 있습니다(예 `53-operations-overview-engineer.png` 는 1440×3280).

### 대역 응답으로 그린 화면(`-mock`)

파일 이름이 `-mock.png` 로 끝나는 9장(55~63)은 실제 스택에서 만들기 어려운 상태라, Playwright 의 `page.route` 로 운영 서비스 응답을 바꿔 찍었습니다. 바탕은 같은 촬영에서 앞서 읽은 실제 응답이고, 바꾼 모양은 JSON 계약을 따릅니다. 바꾼 엔드포인트는 `INDEX.md` 의 «만든 방법» 칸에 적혀 있습니다.

| 파일 | 상태 | 대역으로 찍은 까닭 |
|---|---|---|
| 55 ~ 59 | 실행 호스트 재기동 뒤(복원 보고, «이전 exec-k», 이전 인스턴스 인시던트, 재기동 중복 송신 기록, 복원 못 한 실행의 배정 제외) | 재기동을 실제로 일으키지 않고 S4b JSON 계약 H1·H2·H3·H4·H6 모양으로 지었습니다 |
| 60, 61 | 실행 호스트 불통 | 호스트를 거치는 읽기를 503 `HOST_SILENT` 로 바꿨습니다(S3a·S4a·S4b 계약의 운영 서비스 절) |
| 62 | 실행 호스트 반영 모름 | `GET /api/site-settings` 응답의 `hostTimings` 를 `null` 로 바꿨습니다(S3c 계약 §3) |
| 63 | 운영 서비스 불통 | `/api/**` 요청을 끊었습니다(connection refused) |

`34-missions-activate-mock-run-required.png`, `35-missions-mock-run-passed.png`, `38-missions-drafts-mock-run-detail.png` 의 `mock-run` 은 화면 기능 «모의 실행» 의 이름입니다. 이 셋은 실제 스택에서 찍었습니다. registry 불통(64, 65)도 런처를 실제로 꺼서 찍었습니다.

### 다시 찍는 법

선행 도구는 저장소 `README.md` 의 «선행 도구» 절과 같습니다. Docker, JDK 21 이상, Node 22 가 필요합니다. 포트는 루트 `.env` 의 `OPS_PORT`, `REGISTRY_PORT`, `HOST_PORT` 를 읽습니다. 셋 가운데 하나라도 없으면 설정이 바로 멈춥니다(`playwright.screens.config.ts:7-9`).

1. 배포본을 만듭니다(저장소 루트).

```bash
./gradlew :site:installDist :mission-host:installDist :ops-service:installDist
```

2. 브라우저를 한 번 받습니다.

```bash
cd ui && npm ci && npx playwright install chromium
```

3. 찍습니다.

```bash
cd ui && npx playwright test -c playwright.screens.config.ts
```

이 설정은 `playwright.config.ts` 와 같은 스택(Postgres, 런처, 실행 호스트, 운영 서비스, 화면 `npm run build && npm run preview`)을 띄우고 시험 디렉터리만 `./screens` 로 바꿉니다. 작업자는 하나이고 시간 상한은 900초입니다. 촬영을 시작할 때 `docs/screens/img/` 의 PNG 를 모두 지우고(`capture.spec.ts:102-103`) 새로 찍은 뒤 `INDEX.md` 를 새로 씁니다(`capture.spec.ts:97`). CI 의 기본 `npx playwright test` 는 `./e2e` 만 돌리므로 이 촬영은 돌지 않습니다.

다시 찍으면 시각, 인스턴스 id, 작업 지시 id 같은 값이 바뀝니다. 문서 본문은 스크린숏의 이런 값에 기대지 않습니다.
