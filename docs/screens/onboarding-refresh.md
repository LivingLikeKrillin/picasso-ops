# 로봇 도입 여정 개편 — 2026-10-10

## 메뉴와 진입점

메뉴는 운영 → 로봇 → 임무 → 현장 설정 → 이력 → 검증 도구 순서입니다. 첫 진입은 로봇 / 기체, 엔지니어 모드입니다. 장애 주입은 현장 설정에서 검증 도구로 분리했습니다.

로봇에는 기체, 도입 준비, 어댑터 관리, 프로파일 관리가 있습니다. **로봇 추가**를 누르면 도입 준비로 들어갑니다. 기존 유형의 기체는 기체 화면에서 바로 선언할 수도 있습니다.

## 지원하는 여정

| 도입 상황 | 준비 순서 |
|---|---|
| 기존 유형의 기체 추가 | 재사용할 어댑터·활성 프로파일 확인 → 기체 등록 → 바인딩 → 현장 명칭 → 시운전 → 운영에서 임무별 배정 조건 확인 |
| 새 모델 | 기존 어댑터 지원 범위 확인 → 프로파일 JSON 제출 → 시험 요청·결과 확인 → 활성화 → 기체 연결·현장 준비 |
| 새 제조사·프로토콜 | 화면 밖에서 어댑터 구현·배포 → 제품·빌드·현장 인스턴스 등록 → 모델 프로파일 검증 → 기체 연결·현장 준비 |

도입 준비의 버튼으로 각 관리 화면을 열고 상단의 도입 준비로 돌아옵니다. 기체를 선택하면 기체 확인·바인딩·현장 명칭의 서버 판정, 관측 시각, 담당자, 다음 조치를 봅니다. 실제 조작은 아래 기체 상세의 기존 폼에서 합니다. 상세로 이동 링크를 제공합니다. 등록 성공 또는 재조회로 적용 확인된 기체는 자동 선택합니다. 결과 모름·거절 시 자동 선택하지 않습니다.

## 판정과 책임 경계

- 제품/인스턴스 수, 활성 리비전 수는 카탈로그 정보입니다. 개별 기체의 호환성·통신 정상·준비 완료로 추정하지 않습니다.
- 시운전 세 조건은 API의 commissioning 값을 표시합니다. 없는 응답은 모름, 직전 값은 재확인 필요입니다. 수동 완료 체크는 없습니다.
- 현장 티칭, 어댑터 구현·배포는 화면 밖 작업입니다. 사람의 기록과 기체 응답은 상세에서 별도로 확인합니다.
- 시운전 완료 뒤에도 운영 화면의 실제 임무별 배정 판정이 필요합니다. 안내 버튼은 화면 이동만 하며 작업 지시를 보내지 않습니다.
- 기존 엔지니어/운영자 조작 제한과 서버 판정을 유지합니다.

## 입력 보존과 범위

로봇의 하위 화면과 다른 주 메뉴를 왕복해도 등록 입력, 도입 경로, 선택 기체, 어댑터·프로파일 폼을 브라우저 메모리에 유지합니다. 모드를 바꾸면 해당 모드에서 허용하는 폼만 표시합니다. 새로고침 후 복원, URL 직접 진입, 브라우저 뒤로가기 탐색은 아직 지원하지 않습니다. 운영·임무 영역의 입력 보존은 이번 변경 범위에 포함하지 않습니다.

## 검증과 촬영

UI 단위·통합 테스트 13개 파일 156개 통과. build, lint 통과. API 대역을 연결한 실제 React 브라우저 화면에서 6개 주 메뉴와 로봇의 3개 하위 화면을 1440/1280/768/390px로 확인하여 문서 전체 가로 넘침과 브라우저 예외가 없고 Pretendard 로딩을 확인했습니다.

사진은 [style-preview](style-preview/README.md)에 있습니다. 대역 응답으로 촬영했으며 실스택·실물 검증의 증거가 아닙니다. 생애주기 E2E와 실스택 스크린샷의 탐색 경로를 수정했습니다. 생애주기 E2E는 아래와 같이 전체 스택으로 통과했습니다. 실스택 65장 일괄 촬영은 재실행하지 않았습니다. 기존 img/의 65장은 이전 기준선입니다.

## 화면과 서버 API 대응

새 도입 안내는 기존 API를 조합하며 새 엔드포인트를 가정하지 않습니다.

| 화면 기능 | 운영 서비스 API | 구현 |
|---|---|---|
| 기체 목록·시운전·막힘 | GET /api/robots | ApiController, RobotListService, CommissioningJudge |
| 기체 등록 | POST /api/robots | RobotOperationsController |
| 어댑터 제품·빌드·인스턴스 조회 | GET /api/adapters | ApiController, AdapterListService |
| 제품 등록 | POST /api/adapters | AdapterOperationsController |
| 빌드 등록 | POST /api/adapters/{adapterId}/versions | AdapterOperationsController |
| 현장 인스턴스 등록 | POST /api/adapter-instances | AdapterOperationsController |
| 프로파일·시험 상태 조회 | GET /api/profiles | ApiController, ProfileListService |
| 프로파일 제출 | POST /api/profile-revisions | ProfileOperationsController |
| 시험 요청 | POST /api/profile-revisions/{id}/test-requests | ProfileOperationsController |
| 프로파일 활성화 | POST /api/profile-revisions/{id}/activation | ProfileOperationsController |
| 기체 바인딩 | POST /api/robots/{robotId}/binding | ProfileOperationsController |
| 현장 명칭 기록 | POST /api/robots/{robotId}/site-names | ProfileOperationsController |
| 임무별 배정 판정·작업 요청 | POST /api/job-orders/eligibility, POST /api/job-orders | JobOrderController |
| 장애 주입 | POST /api/faults | IncidentController |

시운전 세 조건은 서버가 원장 상태·활성 바인딩·명칭 상태에서 계산합니다. 도입 경로 선택과 다음 화면 이동은 프런트엔드 상태입니다. 별도의 온보딩 세션 저장, 어댑터 자동 구현·배포, 실물 현장 티칭 API는 이 기능에 포함하지 않습니다.

## 전체 스택 E2E 검증 — 2026-10-10

- 서버 배포본: JDK 21에서 `gradlew.bat --offline :site:installDist :mission-host:installDist :ops-service:installDist` 성공(2분 36초).
- UI 디렉터리에서 `npx playwright test --reporter=line`: **1 passed (4.8m)**. `test-results/.last-run.json`은 passed, failedTests는 빈 배열입니다.
- PostgreSQL 16, Registry·Mimic·셀 대역 런처, mission-host, ops-service, 빌드된 React UI와 Chromium을 실제로 기동했습니다. API 응답 대역을 사용하지 않았습니다. 로봇과 설비는 프로젝트의 Mimic·셀 대역입니다.
- 도입 경로 선택·관리 탭 왕복, 선언 후 자동 기체 선택, 기존 상태를 유지한 도입 준비 재개, 시운전 완료/명칭 미완 구분, 운영의 임무 배정 화면으로 이동을 검증했습니다.
- 제품·빌드·인스턴스 등록, 두 프로파일의 제출·시험·활성화, 두 기체 바인딩·명칭 기록, 퇴역·복귀·감사 이력, 현장 설정 저장/호스트 적용, InspectAsset 실행 완료, 데이터 임무 모의 실행·활성화를 검증했습니다.
- 검증 도구의 스킬 실패 주입, SIGNAL_DEADLINE 보류, 모드 제한, 운영자 재작업 판단, 재개 후 완료, 응답 송신 기록, Registry 중단 후 기체/어댑터/프로파일의 직전 값 표시를 검증했습니다.
- 주요 조회·조작 API 12개 경로에 대해 HTTP 200 응답을 확인하고, 정상 연계 구간에서 /api의 404 응답이 없음을 단언합니다. 응답 목록은 Playwright 첨부 데이터로 제공합니다(기본 HTML 리포터 사용 시 보고서에서 확인).
- 테스트용 Compose 프로젝트를 `picasso-ops-e2e`로 고정해 평소 사용하는 site 프로젝트의 컨테이너를 정리하지 않도록 변경했습니다. 종료 후 테스트 DB와 서비스 포트가 정리됨을 확인했습니다.

E2E 코드는 `ui/e2e/lifecycle.spec.ts`, 테스트 DB 준비/정리는 `ui/e2e/postgres.mjs`와 `ui/e2e/teardown.ts`에 있습니다. lint와 diff 공백 검증도 통과했습니다.
