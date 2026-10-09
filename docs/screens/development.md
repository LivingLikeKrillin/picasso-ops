# picasso-ops 화면 개발 내역

관리 화면(`ui/`)이 어느 단계에서 무엇을 얻었는지, 어떤 파일이 무엇을 하는지, 어떻게 띄우고 시험하는지를 적습니다. 기준은 `main` = `9606364`(PR #14 머지)입니다. 파일 위치 표기는 `README.md` 의 «읽는 법» 을 따릅니다.

## 1. 단계와 PR

머지 커밋은 `git log --merges` 로 확인한 값입니다.

| PR | 머지 커밋 | 날짜 | 단계 | 화면 쪽 커밋 |
|---|---|---|---|---|
| #1 | `b4fcc56` | 10-07 | S1a 골격 | `3a51f29` feat(s1a): site·ops-service·ui·e2e 골격 |
| #2 | `d94a5e6` | 10-07 | S1b 생애주기 | `e0bdedb` feat(s1b): 로봇 선언·퇴역·복귀, 막힘·거부 판정, 기체 상세 화면, Playwright CI |
| #3 | `2a8c617` | 10-07 | S1c 어댑터 | `8d8d498` feat(s1c): 어댑터 제품·빌드·인스턴스 등록과 어댑터 화면, Playwright 어댑터 흐름 |
| #4, #5 | `3f00dfb`, `f84e095` | 10-08 | P2·S1d 스펙과 계획(문서) | 없음 |
| #6 | `694ea9e` | 10-08 | S1d 시운전 | `575efd2` feat(s1d): 리비전 제출·시험·활성화·바인딩·명칭 기록과 시운전 판정, 프로파일 화면 |
| #7 | `7254521` | 10-08 | 용어 정리 | `ffd2141` docs(terms): 용어집 대응표에 맞춰 문서와 화면 문구의 옛 이름 교체 |
| #8 | `16de942` | 10-08 | S2 현장 설정 | `b7a19b0` feat(s2): 현장 설정 버전 기록과 연결 기준 시간 변경, 막힘 근거 버전 |
| #9 | `f7ca937` | 10-08 | P3 설계(문서) | 없음 |
| #10 | `d16b19b` | 10-08 | S3a 실행 호스트 | `f0ecb10` feat(ui): 작업 지시 폼·배정 가능 표·실행 목록·셀 대역 표시, `1bdd52a` test(ui) |
| #11 | `b2cce34` | 10-09 | S3b 임무 버전 | `5c16418` feat(ui): 임무·정책 영역과 셀 대역 신호 조작 화면, `ba51d27` test(ui) |
| #12 | `e482c33` | 10-09 | S3c 현장 시간값 | `2e53ee1` feat(ui): 현장 설정 구역의 시간값 입력 넷과 실행 호스트 반영 표시, `15e00d8` test(ui) |
| #13 | `8ed1b91` | 10-09 | S4a 장애·인시던트 | `1e62341` feat(ui): 장애 주입 패널과 인시던트 구역, 보류 단위의 운영자 판단, `078ee30` test(ui) |
| #14 | `9606364` | 10-09 | S4b 재기동 복원 | `1a7356a` feat(ui): 재기동 복원 보고 띠, 이전 인스턴스 인시던트, 작업 응답 송신 기록 구역, `14db36d`·`39447c7` test(ui) |

S1b 의 커밋 제목 원문은 «거절 판정» 입니다. 용어집의 새 이름은 «거부» 입니다.

## 2. 단계별로 화면에 더한 것

| 단계 | 더한 화면 기능 | 설계 근거 |
|---|---|---|
| S1a (#1) | 껍데기, 다섯 영역 nav(로봇·연결과 이력만 열림), 모드 전환과 사용자, 전체 상태 띠, 기체 목록 뼈대, 이력 표 | S1 스펙 §8 |
| S1b (#2) | 기체 선언 폼, 기체 상세(상태 칸과 막힘 카드), 퇴역·복귀, 조작 결과 알림, 거부 카드, Playwright | S1 스펙 §8·§9 |
| S1c (#3) | 어댑터 구역(인스턴스 표, 제품·빌드 표, 등록 폼 셋) | S1 스펙 §8 |
| S1d (#6) | 프로파일 구역(카탈로그 줄, 리비전 표, 제출·시험 요청·활성화), 기체 목록 시운전 칸, 상세 카드 셋(바인딩, 사이트 명칭, 시운전) | P2·S1d 스펙 §9 |
| 용어 (#7) | 화면 문구의 옛 이름 교체(`CommissioningCards`, `ProfilesSection`, `labels`) | 용어집 §8 |
| S2 (#8) | 현장·자원 영역 열기, 현장 설정 구역(값, 범위, 이력, 변경 폼), 막힘 카드 «근거 버전», 기체 상세 «연결 판정 기준» | S2 스펙 §7 |
| S3a (#10) | 운영 영역 열기, 작업 지시 폼, 배정 가능 표, 실행 목록, 셀 대역 표, `poll.ts` | S3a 스펙 §9 |
| S3b (#11) | 임무·정책 영역 열기(편집기, 템플릿, 초안, 버전 이력, 모의 실행 보고), 셀 대역 신호 표와 켜기·끄기, CSS `.editor`·`pre`·`.actions` | S3b 스펙 §8 |
| S3c (#12) | 현장 설정에 시간값 넷(묶음 둘, 파급 문장, 폼 fieldset, 이력 열 넷), 실행 호스트 반영 줄 | S3c 스펙 §8, S3c 계약 §11 |
| S4a (#13) | 현장·자원에 장애 주입 구역, 운영에 인시던트 구역(목록, 상세, 사람의 판단, 운영자 판단), CSS `tr.held`·`.asserted`·`.observed` | S4a 스펙 §8, S4a 계약 §10 |
| S4b (#14) | 실행 목록 위 재기동 복원 보고 띠, «이전 exec-k» 표기, 이전 인스턴스 인시던트, 작업 응답 송신 기록 구역, CSS `.restore`·`.restored`·`tr.duplicate` | S4b 스펙 §8, S4b 계약 «화면 (ui)» |

스펙 파일은 `docs/superpowers/specs/` 에, 계획은 `docs/superpowers/plans/` 에 있습니다.

## 3. 파일별 이력

«만든 PR → 고친 PR» 은 `git log --first-parent main -- <파일>` 로 확인한 값입니다.

### 3.1 바탕 파일 (`ui/src/`)

| 파일 | 하는 일 | 만든 PR → 고친 PR | 시험 |
|---|---|---|---|
| `index.html` (`ui/`) | `lang="ko"`, 제목 «picasso-ops», 파비콘 `/favicon.svg` | #1 | 없음 |
| `main.tsx` | `StrictMode` 안에 `App` 을 그리고 `styles.css` 를 읽음 | #1 | 없음 |
| `App.tsx` | 머리, nav, 전체 상태 띠, 영역 전환. 다섯 조회를 5초마다 묶어 읽음 | #1 → #2, #3, #6, #8, #10, #11, #13 | `App.test.tsx` (#1 → #2, #7, #8, #10, #11) |
| `areas.ts` | 다섯 영역의 id, 버튼 글자, 열림 여부 | #1 → #8, #10, #11 | `App.test.tsx` |
| `labels.ts` | 거부·막힘 이름과 열거형 값을 화면 글자로 푸는 표 | #2 → #3, #6, #7, #8, #10, #11, #13, #14 | 영역 시험들 |
| `api.ts` | 운영 서비스 호출, 응답 타입, 결과 분류(`outcome`·`refused`·`unknown`) | #1 → #2, #3, #6, #8, #10, #11, #12, #13, #14 | 영역 시험들(가짜 서버 `testing/fakeOps.ts` #2 → #3, #6, #8, #10~#14) |
| `poll.ts` | 주기 상수 셋(`POLL_MS`, `ELIGIBILITY_DEBOUNCE_MS`, `SIGNAL_SETTLE_MS`) | #10 → #11 | `OperationsArea.test.tsx` |
| `jobOrderDraft.ts` | 작업 지시 폼의 처음 값과 덜 채운 폼 판정 | #10 | `OperationsArea.test.tsx` |
| `styles.css` | 전역 CSS 24행 | #1 → #2, #11, #13, #14 | 없음 |

### 3.2 컴포넌트 (`ui/src/components/`)

| 파일 | 하는 일 | 만든 PR → 고친 PR | 시험 |
|---|---|---|---|
| `ModeSwitch.tsx` | 모드 라디오 둘과 사용자 이름 칸 | #1 | `App.test.tsx` |
| `RegistryBanner.tsx` | 머리 아래 전체 상태 띠 | #1 | `App.test.tsx`, `RobotsArea.test.tsx` |
| `HistoryArea.tsx` | 이력 영역의 조작 기록 표 | #1 | `App.test.tsx` |
| `RobotsArea.tsx` | 로봇·연결 영역. 기체 목록, 어댑터, 프로파일, 상세의 2열 배치와 조작 결과 | #1 → #2, #3, #6, #8 | `RobotsArea.test.tsx` (#2 → #7) |
| `DeclareForm.tsx` | 기체 선언 폼 | #2 | `RobotsArea.test.tsx` |
| `RobotDetail.tsx` | 기체 상세(상태, 막힘, 카드 셋, 퇴역·복귀) | #2 → #6, #8 | `RobotsArea.test.tsx`, `SiteArea.test.tsx` |
| `FindingCard.tsx` | 막힘·거부 카드 | #2 → #8 | 여러 영역 시험 |
| `OutcomeNotice.tsx` | registry 조작과 현장 설정 변경의 결과 알림 | #2 → #3 | 여러 영역 시험 |
| `AdaptersSection.tsx` | 어댑터 구역의 표 둘과 폼 자리 | #3 | `AdaptersSection.test.tsx` (#3 → #7) |
| `AdapterForms.tsx` | 제품 선언, 빌드 선언, 인스턴스 등록 폼 | #3 | `AdaptersSection.test.tsx` |
| `ProfilesSection.tsx` | 프로파일 구역(카탈로그 줄, 리비전 표, 제출 폼) | #6 → #7 | `ProfilesSection.test.tsx` (#6 → #7) |
| `CommissioningCards.tsx` | 기체 상세의 바인딩, 사이트 명칭, 시운전 카드 | #6 → #7 | `CommissioningCards.test.tsx` (#6 → #7) |
| `SiteArea.tsx` | 현장 설정 구역(값, 묶음 둘, 변경 폼, 버전 이력) | #8 → #12 | `SiteArea.test.tsx` (#8 → #12) |
| `OperationsArea.tsx` | 운영 영역. 실행 호스트를 거치는 읽기와 여섯 구역 배치 | #10 → #11, #13, #14 | `OperationsArea.test.tsx` (#10 → #11) |
| `JobOrderFormView.tsx` | 작업 지시 폼 | #10 | `OperationsArea.test.tsx` |
| `EligibilityTable.tsx` | 기체별 배정 가능 표 | #10 | `OperationsArea.test.tsx` |
| `JobOrderNotice.tsx` | 작업 지시 제출 결과 | #10 → #13 | `OperationsArea.test.tsx` |
| `ExecutionList.tsx` | 실행 목록과 재기동 복원 보고 띠 | #10 → #14 | `OperationsArea.test.tsx`, `ExecutionRestore.test.tsx` (#14) |
| `CellBand.tsx` | 셀 대역 자리 표와 신호 표 | #10 → #11 | `OperationsArea.test.tsx` |
| `MissionsArea.tsx` | 임무·정책 영역(활성 버전, 편집, 버전 이력, 초안) | #11 | `MissionsArea.test.tsx` (#11 → #13) |
| `MockRunReport.tsx` | 모의 실행 보고 | #11 | `MissionsArea.test.tsx` |
| `MissionNotice.tsx` | 임무 조작 결과 | #11 → #13 | `MissionsArea.test.tsx` |
| `SignalNotice.tsx` | 신호 조작 결과 | #11 → #13 | `OperationsArea.test.tsx` |
| `FaultPanel.tsx` | 장애 주입 구역과 그 결과 알림 | #13 | `FaultPanel.test.tsx` (#13) |
| `IncidentSection.tsx` | 인시던트 목록, 이전 인스턴스, 상세, 운영자 판단과 그 결과 알림 | #13 → #14 | `IncidentSection.test.tsx` (#13 → #14) |
| `JobResponseLog.tsx` | 작업 응답 송신 기록 구역 | #14 | `JobResponseLog.test.tsx` (#14) |

전용 시험 파일 없이 영역 시험이 간접으로 보는 컴포넌트는 `AdapterForms`, `CellBand`, `DeclareForm`, `EligibilityTable`, `ExecutionList`(복원 띠만 전용), `FindingCard`, `HistoryArea`, `JobOrderFormView`, `JobOrderNotice`, `MissionNotice`, `MockRunReport`, `ModeSwitch`, `OutcomeNotice`, `RegistryBanner`, `RobotDetail`, `SignalNotice` 입니다.

Playwright 시험 `ui/e2e/lifecycle.spec.ts` 는 #2 에서 만들고 #3, #6, #7, #8, #10, #11, #12, #13, #14 에서 고쳤습니다. 시험은 하나(`PW:28`)이며 생애주기 전체를 한 번 돕니다.

## 4. 기술 스택

| 쓰임 | 패키지 | 버전(`ui/package.json`) |
|---|---|---|
| 런타임 | `react`, `react-dom` | ^19.2.8 |
| 언어 | `typescript` | ~6.0.2 |
| 번들·개발 서버 | `vite`, `@vitejs/plugin-react` | ^8.3.0, ^6.1.1 |
| 단위 시험 | `vitest`, `jsdom`, `@testing-library/react`, `@testing-library/user-event`, `@testing-library/jest-dom` | ^5.0.3, ^29.1.1, ^16.3.3, ^14.6.7, ^7.0.1 |
| 화면 시험 | `@playwright/test` | ^1.63.0 |
| 린트 | `oxlint` | ^1.81.0 |

런타임 의존성은 `react`, `react-dom` 둘뿐입니다. UI 라이브러리, 아이콘, CSS 프레임워크, 라우터, 상태 관리 라이브러리는 없습니다. 영역은 URL 이 아니라 `App` 의 `useState` 값입니다(`App.tsx:20`). 스타일은 `styles.css` 한 파일입니다.

npm 스크립트: `dev`(vite), `build`(`tsc -b && vite build`), `lint`(oxlint), `preview`(vite preview), `test`(`vitest run`). vitest 는 `src/**/*.test.{ts,tsx}` 만 돌고 `e2e/` 는 Playwright 몫입니다(`ui/vite.config.ts`).

## 5. 파일 지도

```
ui/
  index.html                 문서 껍데기(lang, title, favicon)
  public/favicon.svg         48×46 단색 SVG(#863bff)
  vite.config.ts             /api 프록시, vitest 설정
  playwright.config.ts       CI 의 화면 시험(./e2e)
  playwright.screens.config.ts  스크린숏 촬영(./screens), CI 에서 돌지 않음
  e2e/
    lifecycle.spec.ts        생애주기 시험 하나
    postgres.mjs, run-dist.mjs, teardown.ts  스택 띄우기와 정리
  screens/
    capture.spec.ts          스크린숏 촬영(docs/screens/img/)
  src/
    main.tsx  App.tsx  areas.ts  api.ts  labels.ts  poll.ts  jobOrderDraft.ts  styles.css
    setupTests.ts            jest-dom 연결
    App.test.tsx
    testing/fakeOps.ts       vitest 의 가짜 운영 서비스
    components/              컴포넌트 26개와 vitest 파일 11개
```

컴포넌트 트리:

```
App
  header: h1, nav(영역), ModeSwitch
  RegistryBanner
  main
    현장·자원: SiteArea(OutcomeNotice, FindingCard), FaultPanel
    로봇·연결: RobotsArea
      왼쪽: DeclareForm, AdaptersSection(AdapterForms), ProfilesSection
      오른쪽: OutcomeNotice(FindingCard), RobotDetail(FindingCard, CommissioningCards)
    임무·정책: MissionsArea(MissionNotice(FindingCard, MockRunReport), MockRunReport)
    운영: OperationsArea
      JobOrderNotice, JobOrderFormView | EligibilityTable
      ExecutionList
      IncidentSection
      셀 대역 구역: SignalNotice, CellBand
      JobResponseLog
    이력: HistoryArea
```

## 6. 운영 서비스와 말하는 법

- 화면은 운영 서비스의 `/api` 만 부릅니다. registry 와 실행 호스트의 주소와 토큰은 모릅니다(`ui/vite.config.ts:5`).
- vite 의 dev 서버와 preview 서버가 `/api` 를 `http://127.0.0.1:${OPS_PORT}` 로 넘깁니다. `OPS_PORT` 는 루트 `.env` 에서 읽고, 없으면 설정이 멈춥니다(`ui/vite.config.ts:8-14`).
- 모든 요청에 헤더 `X-Ops-Mode`(`engineer`|`operator`)와 `X-Ops-User`(사용자 이름)를 싣습니다(`api.ts:316`). 본문이 있는 요청은 `Content-Type: application/json` 입니다. 운영 서비스는 registry 로 갈 때 이 둘을 `X-Actor: <모드>/<사용자>` 로 바꿔 보냅니다(`ops-service/.../actor/Actor.kt:26`, `RegistryClient.kt:300`).
- 모드 집행은 운영 서비스도 합니다. 모드가 맞지 않는 조작은 403 `MODE_NOT_ALLOWED` 이고, 화면에는 «이 모드에서 할 수 없는 조작» 으로 보입니다(`labels.ts`).
- 쓰기 결과는 세 갈래로 나눕니다(`api.ts:350-371`). 2xx 는 `outcome`, 400·403 은 보내기 전에 막힌 `refused`, 그 밖의 상태 코드와 끊김은 `unknown`(결과 모름)입니다. 임무 조작은 4xx·503 을 `refused` 로 봅니다(`api.ts:789`).
- 실행 호스트를 거치는 읽기(`getHostJson`)는 실패하면 본문의 `detail` 을, 없으면 «운영 서비스 응답 ${status}» 를 오류로 씁니다(`api.ts:595-602`). 이 글자가 «직전 값입니다. 실행 호스트 불통: ${error}» 에 들어갑니다.

| 쓰임 | 메서드와 경로 | `api.ts` |
|---|---|---|
| 기체 목록 | GET `/api/robots` | 375 |
| 현장 설정 | GET·PUT `/api/site-settings` | 376-378 |
| 조작 기록 | GET `/api/operations` | 380 |
| 기체 선언 | POST `/api/robots` | 381-386 |
| 퇴역·복귀 | POST·DELETE `/api/robots/{id}/retirement` | 373, 387-390 |
| 어댑터 | GET·POST `/api/adapters`, POST `/api/adapters/{id}/versions`, POST `/api/adapter-instances` | 392-402 |
| 프로파일 | GET `/api/profiles`, POST `/api/profile-revisions`, POST `…/{id}/test-requests`, POST `…/{id}/activation` | 404-410 |
| 바인딩·명칭 기록 | POST `/api/robots/{id}/binding`, POST `/api/robots/{id}/site-names` | 412-414 |
| 실행·셀 대역 | GET `/api/executions`, GET `/api/cell` | 611-612 |
| 배정 가능·작업 지시 | POST `/api/job-orders/eligibility`, POST `/api/job-orders` | 614-616 |
| 임무 | GET `/api/missions/{wm}`, GET `/api/missions/templates/{wm}`, POST `…/drafts`, POST `…/drafts/{id}/validate`, `…/mock-run`, `…/activate` | 802-818 |
| 셀 신호 | POST `/api/cell/signals/{name}` | 820-821 |
| 인시던트 | GET `/api/incidents`, GET `/api/incidents/{id}?instanceId=` | 963-979 |
| 장애 주입 | POST `/api/faults` | 1040-1050 |
| 운영자 판단 | POST `/api/executions/{exec}/units/{unit}/resolve` | 1057-1066 |
| 송신 기록 | GET `/api/job-responses[?jobOrderId=]` | 1101-1105 |

읽는 주기는 `screen-design.md` §2.5 에 있습니다.

## 7. 로컬에서 띄우기

저장소 `README.md` 의 절을 따릅니다.

| 하려는 것 | README 절 | 명령 |
|---|---|---|
| 화면 단위 시험 | «시험» | `cd ui && npm ci && npm test` |
| 화면 시험(Playwright) | «시험» | `./gradlew :site:installDist :mission-host:installDist :ops-service:installDist` 한 번, `cd ui && npx playwright install chromium` 한 번, 그다음 `cd ui && npx playwright test` |
| 스택을 오래 띄우고 화면 보기 | «띄우기» | Postgres(`docker compose -f site/compose.yaml --env-file .env up -d --wait`), 배포본 설치, 터미널 넷에서 런처·실행 호스트·운영 서비스, 마지막에 `cd ui && npm ci && npm run dev` |
| 스크린숏 다시 찍기 | 이 묶음의 `README.md` | `cd ui && npx playwright test -c playwright.screens.config.ts` |

화면만 고칠 때는 vitest 가 가짜 운영 서비스(`src/testing/fakeOps.ts`)로 돌므로 스택이 필요 없습니다. 브라우저로 보려면 운영 서비스가 떠 있어야 합니다. 운영 서비스가 없으면 화면은 «모름: 운영 서비스에 닿지 않습니다» 띠를 보입니다.

## 8. 화면을 보는 CI

`.github/workflows/ci.yml` 의 job 셋 가운데 둘이 화면을 봅니다.

| job | 하는 일 | 화면과의 관계 |
|---|---|---|
| `gradle` | `./gradlew build`, 시험 XML 올림 | 화면을 보지 않습니다 |
| `ui` | Node 22, `ui/` 에서 `npm ci`, `npm test`(vitest), `npm run build`(`tsc -b` 타입 검사 포함) | vitest 전체와 타입 검사 |
| `playwright` | JDK 21, 배포본 셋 설치, `npx playwright install --with-deps chromium`, `npx playwright test`, `ui/playwright-report` 올림 | `./e2e/lifecycle.spec.ts` 하나 |

CI 는 `npm run lint`(oxlint)를 돌리지 않습니다. 스크린숏 촬영(`playwright.screens.config.ts`)도 돌지 않습니다. 저장소 규칙대로 판정은 종료 코드가 아니라 시험 보고의 실패 시험 이름으로 합니다(README «시험»).
