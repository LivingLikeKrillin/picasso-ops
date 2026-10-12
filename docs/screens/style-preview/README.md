# 스타일 검토용 화면

2026년 10월 10일 스타일 및 로봇 도입 여정 개편. `*-mock.png`는 실제 React 화면에 API 대역 응답을 주입해 촬영했습니다. `*-fullstack.png`는 실제 Ops API와 E2E 스택에서 촬영했으며, 실물 로봇·PLC·MES 검증을 의미하지 않습니다. 기존 img/의 65장과 구분합니다.

- [로봇 첫 화면](robots-first-view-mock.png)
- [로봇과 연결 전체](robots-desktop-mock.png)
- [미션 목록](missions-desktop-mock.png)
- [운영과 인시던트](operations-desktop-mock.png)
- [현장 설정](site-desktop-mock.png)
- [모바일](robots-mobile-mock.png)
- [직전 값과 연결 실패](robots-stale-mock.png)

## 다시 촬영

ui에서 npm run build 후 npm run preview -- --host 127.0.0.1 --port 4179 --strictPort로 띄웁니다. 다른 터미널에서 ui/scripts/review-style.mjs를 Node로 실행합니다. 설치된 Playwright Chromium이 필요합니다. API 대역은 src/testing/fakeOps.ts의 기존 픽스처를 사용합니다. 스크립트는 6개 주 메뉴와 로봇의 3개 하위 화면·미션의 4개 하위 화면을 1440·1280·768·390px로 확인하고 브라우저 예외, 문서 전체 가로 넘침, 폰트 로딩을 검사합니다.

기능 회귀는 npm test -- --pool=threads --maxWorkers=1로 13개 파일 156개 통과했습니다. build와 lint도 통과했습니다. 후속 검증에서 실제 스택의 생애주기 E2E도 1개 종합 시나리오(4.8분)로 통과했습니다. 상세는 [도입 여정 검증](../onboarding-refresh.md)을 참고하세요. 이 이미지들은 API 대역 촬영본으로 유지합니다.

- [로봇 도입 준비 첫 화면](onboarding-first-view-mock.png)
- [로봇 도입 준비 전체](onboarding-desktop-mock.png)
- [로봇 도입 준비 모바일](onboarding-mobile-mock.png)
## 미션 관리 추가 화면

- [호출 연결과 입력 매핑: API 대역](mission-contract-desktop-mock.png)
- [실행 정의: API 대역](mission-definition-desktop-mock.png)
- [버전 이력: API 대역](mission-versions-desktop-mock.png)
- [모바일 호출 연결: API 대역](mission-contract-mobile-mock.png)
- [미션 목록: 실제 Ops API](missions-fullstack.png)
- [서버 입력 검증·변환: 실제 Ops API](mission-contract-fullstack.png)

실제 API 촬영은 E2E 스택이 실행 중일 때 `node ui/scripts/capture-missions.mjs`로 재현합니다. 기본 UI 주소는 http://127.0.0.1:4173 이며 OPS_UI_URL 환경 변수로 바꿀 수 있습니다. 요청 가로채기는 사용하지 않으며 미리보기만 호출합니다. 최신 변경 범위와 검증 결과는 [미션 관리 개편](../mission-refresh.md)을 참고하세요.

최신 호출 계약 구현 검증: UI 171개, 백엔드 258개, 전체 스택 E2E 1개(5.5분) 통과. 세부 범위는 [구현 기록](../mission-interface-implementation.md)을 참고하세요.

## 호출 계약 작성 화면

- `mission-interface-editor-mock.png`: API fixture 기반 데스크톱 작성 폼.
- `mission-contract-mobile-mock.png`: 작성 폼을 연 390px 화면.
- `mission-interface-version-fullstack.png`: 실제 DB에 저장된 asset-check/v1의 공개 상태와 매핑.
- `mission-interface-editor-fullstack.png`: 실제 Ops HTTP 매핑 검증 결과. 저장·작업 호출 없이 촬영.

1440/1280/768/390px에서 작성 폼을 포함해 가로 넘침과 브라우저 오류가 없는지 점검했습니다.

## 상위 주문 접수 화면

- `upstream-receipt-desktop-mock.png`, `upstream-receipt-mobile-mock.png`: 미배정 주문 상세, API 대역 응답으로 촬영.
- [접수 상세: 실제 Ops HTTP](upstream-receipt-fullstack.png) · [모바일: 실제 Ops HTTP](upstream-receipt-mobile-fullstack.png)
- `ui/scripts/capture-receipts.mjs`: 실제 전체 스택의 MES-E2E-204 접수와 물리 완료를 조회해 촬영. 요청 제출 없이 조회만 수행한다.

접수 상세를 연 1440/1280/768/390px 화면에서 브라우저 오류·문서 가로 넘침 없음, Pretendard 로딩 확인.

- upstream-result-ack-fullstack.png / upstream-result-ack-mobile-fullstack.png: 실제 Mock MES HTTP 결과 v1과 해당 버전의 ACK를 표시한 Ops 화면. capture-results.mjs로 촬영.

## 수동 작업 영속 접수

- [실제 Ops HTTP 수동 접수](manual-request-fullstack.png) · [390px 모바일](manual-request-mobile-fullstack.png)
- 전체 스택 E2E의 동일 요청 재확인 직후 촬영. 요청·실행 ID와 배정 기체는 실제 응답이다.
- [구현 및 검증 기록](../manual-receipts-2026-10-11.md): UI 180개, Ops 280개, 전체 스택 E2E 1개 통과.

## 상위 업무 중심 후속 화면

- [운영 개요: 실제 Ops HTTP](overview-fullstack.png)
- [390px 운영 개요: 실제 Ops HTTP](overview-mobile-fullstack.png)
- [전용 작업 요청과 거절 상세: 실제 Ops HTTP](workspace-requests-fullstack.png)

전체 스택 E2E가 실제 저장한 접수·호스트 관측·수집 상태로 촬영한다. 모바일 문서 가로 넘침을 검사하고 폰트 로딩 이후 촬영한다. `ui/scripts/capture-overview.mjs`는 스택 실행 중 개요를 조회만 하여 다시 찍는다. 임무 배포 변경·요청 전송·ACK를 수행하지 않는다. 최신 검증 수치와 범위는 [후속 구현 기록](../upstream-followups-2026-10-11.md)을 참고한다. 위의 과거 검사 수치는 단계별 기록이다.
