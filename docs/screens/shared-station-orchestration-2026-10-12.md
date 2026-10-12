# 공유 스테이션 오케스트레이션

이 문서는 최초 스테이션 경로 구현을 기록한다. 이후 MES·계약 호출·수동·직접 제출로 강제 적용 범위를 확장한 내용은 [미션 필수 자원의 공통 접수 적용](mission-resource-enforcement-2026-10-12.md)을 참조한다.

## 구현 범위와 순서

1. 영속 자원 점유·준비 확인·완료 확인·해제
2. 예외·기한·전송 전 미배정의 제한 재평가
3. 공유 자원 화면·운영자 판단·변경 불가 이벤트 이력·Narrator 근거 설명 입력
4. DB 회귀·UI 회귀·실제 가상 현장 전체 스택·브라우저 검증

## 실행 계약

Ops의 별도 공유 스테이션 경로다. 이 경로로 접수한 작업 사이에서 스테이션별 한 작업만 점유한다. 기존 직접 호출·외부 제어까지 강제하는 현장 인터록은 아니다. 로봇과 설비의 안전 제어를 대체하지 않는다. core 서브모듈은 변경하지 않는다.

엔지니어는 stationId, name, readySignal, completeSignal, timeoutSeconds를 등록한다. 현장에 현재 선언된 일반 BOOLEAN 신호만 가능하다. 안전 신호는 허용하지 않는다. 기한은 5~3600초이며 현재 버전의 등록 설정은 고정된다.

운영자는 requestId UUID, stationId, interfaceId, interfaceVersion, expectedDeploymentVersion, input으로 작업을 요청한다. 요청 원문과 요청자, 고정 배포를 보관한다. 같은 ID의 원문 재접수는 현재 계약 상태가 바뀌어도 기존 작업을 반환한다. 같은 ID의 다른 입력은 거절한다. 화면은 응답 불명 요청을 같은 탭의 새로고침까지 보관한다.

대기열은 접수 시각·UUID 순서다. 자원 획득 후 준비 신호 true와 완료 신호 false가 모두 확인되어야 기존 영속 미션 접수로 제출한다. 작업이 로봇에 수락되었다고 자원을 해제하지 않는다. 저장된 JobResponse의 PHYSICALLY_DONE과 제출 이후 관측된 완료 신호 true가 모두 있어야 COMPLETED와 자원 해제를 기록한다. 다음 작업은 완료 신호가 false로 초기화될 때까지 기다린다.

## 상태와 복구

QUEUED → WAITING_READY → DISPATCHING → RUNNING → COMPLETED가 정상 경로다.

- 준비 단계 기한 초과: TIMED_OUT, 로봇 전송 전 자원 해제
- 접수 거절: REJECTED, 자원 해제
- 전송 전임이 확인된 UNASSIGNED: WAITING_ROBOT; 준비 조건을 재확인하고 5초 간격으로 최대 3회 기존 접수를 재평가한다. 고정된 원래 작업을 유지하며 새 작업 ID를 만들지 않는다.
- 진행 중 기한 초과·신호 모름·로봇 OPERATOR_HOLD/IN_DOUBT 등: REVIEW_REQUIRED, 점유 유지
- 제출·재평가 응답 불명: 재실행하지 않고 REVIEW_REQUIRED
- 재기동 시 DISPATCHING: 원래 요청 ID로 접수를 확인한다. WorkRequests는 이미 저장된 접수를 재전송하지 않는다.
- 재기동 시 RETRYING: 추가 재평가를 추측하지 않고 REVIEW_REQUIRED

자동 재할당은 호스트 전송 전 미배정에 한정한다. 시작 후 다른 기체로 넘기는 기능은 물리 중복 위험 때문에 제공하지 않는다. RUNNING의 기한은 단계 진입부터 센다. 미배정 재평가 성공 시 실행 단계 기한이 다시 시작하며 무제한 재평가는 없다.

운영자는 QUEUED 취소, REVIEW_REQUIRED의 관측 재개, 현장 확인 후 자원 해제를 수행할 수 있다. 조회 revision과 판단 사유가 필요하다. 수동 해제는 MANUALLY_CLEARED이며 물리 완료와 구분한다. 관측 재개는 로봇에 작업을 다시 보내지 않는다. 불명확한 접수의 실제 실행을 취소하거나 정지하는 명령은 이 기능에 포함하지 않는다.

## API와 근거

- GET /api/stations: 자원과 최근 100개 작업
- POST /api/stations: 자원 등록, engineer
- POST /api/stations/runs: 작업 접수, operator
- GET /api/stations/runs/{id}: 작업·이벤트
- POST /api/stations/runs/{id}/decision: expectedRevision, decision, reason
- GET /api/stations/runs/{id}/evidence: ops.station.evidence/1
- GET /api/stations/health: 백그라운드 조정기 상태

자원 점유·전이·이벤트는 DB 트랜잭션과 자원 행 잠금으로 묶는다. 상세·근거 조회도 같은 자원 행을 잠가 상태 revision과 이벤트 이력을 일관되게 읽는다. 자원 등록자와 등록 시각도 보관한다. 네트워크 호출은 트랜잭션 밖이다. 이벤트는 DB 트리거로 수정·삭제·TRUNCATE를 거절한다. 제출 준비 및 완료 이벤트에는 신호 관측과 실행 결과 근거를 저장한다. 설명이 상태를 바꿀 수 없다는 authority 경계를 근거 JSON에 명시한다.

Narrator의 receiver.station은 이 JSON을 파일 입력으로 받아 기존 ask_once·Nexus 클라이언트·결과 분류기를 사용한다. 검증된 인용만 남기며 실패는 GENERATION_FAILED로 구분한다. 같은 근거 digest의 재처리는 추가 질의하지 않는다. 기존 Incident Bundle·진단 계약과 별도이며 Ops 화면에 LLM 설명을 자동 표시하거나 실행 API를 호출하지 않는다. 실제 Nexus 연결을 호출한 실측은 별도다.

## 검증 결과

- 공유 스테이션 DB 시험: 17개 통과. 동시 접수·조정기, 재기동, 응답 불명, 준비·실행 기한, 제한 재평가, 수동 해제, revision 충돌, 이벤트 수정·삭제 금지를 검증했다.
- 현장 시뮬레이터 회귀: 38개 통과. 새 일반 공정 신호 2개를 포함한다.
- UI 회귀: `npx vitest run --maxWorkers=2`로 22개 파일, 208개 시험 통과. 기본 동시 실행에서 기존 시험의 5초 기한 초과와 뒤따른 요청 수 검증 실패가 발생해 동시 실행 수를 줄였으며, 시험 제한 시간이나 기대값은 변경하지 않았다. production build와 lint도 통과했다.
- Narrator 회귀: 391 passed, 10 deselected. 신규 스테이션 시험 6개를 포함한다.
- JVM 전체 스택: StationScenarioTest 1개, ManufacturingScenarioTest 1개, MissionVersionTest 4개, 합계 6개 통과.
- 브라우저 전체 스택: station.spec.ts 1개 통과. 실제 Ops API로 기체 시운전 준비·계약 등록을 수행하고 화면으로 자원 등록·작업 요청을 했다. 두 미션의 순차 수행, 설비 완료 초기화 대기, 완료 근거와 390px 페이지 가로 넘침 없음을 확인했다.
- E2E 산출물 `build/reports/station-orchestration.json`을 Narrator에 입력해 COMPLETED·revision 전달과 동일 파일 재처리 시 질의 1회 유지를 확인했다. 외부 Nexus 대신 응답 대역을 사용했으며, 근거 없음은 NO_EVIDENCE였다.
- Ops main 클래스패스의 core 의존 금지 검사도 통과했다. 기존 Ops 회귀 305개는 앞선 광역 실행에서 통과했고, 이후 추가 감사 필드와 스테이션 조회 보완은 별도의 스테이션 집중 시험으로 재검증했다. Ops 검증은 기존 회귀와 새 스테이션 집중 시험을 분리 수행했다.

목록은 주기적으로 갱신한다. 선택 상세는 조회 시점의 근거이며, 판단 전 다시 조회하도록 안내하고 revision으로 오래된 판단을 거절한다. JSON 입력창은 초기 높이를 줄여 작업 상태까지의 이동 거리를 줄였다.

[데스크톱 캡처](style-preview/shared-station-fullstack.png) · [모바일 캡처](style-preview/shared-station-mobile-fullstack.png)

테스트는 Postgres·registry·mimic·가상 셀·mission-host·Ops·브라우저로 구성한다. 실물 로봇·PLC 시험 결과가 아니다.

## 제한

현장 신호는 참조 셀 시뮬레이터의 일반 공정 신호다. 제출 이후 시각 확인은 공통 시계 전제이며, 실물 설비의 requestId echo/fencing token 기반 핸드셰이크는 아직 없다. 점유에 자동 만료·강제 해제를 두지 않으며 불명확하면 유지한다. 직접 호출 경로의 자원 요구 강제, 다중 자원 동시 점유·교착 방지, 진행 중 로봇 간 물리 작업 인계는 후속 범위다. 이 구현을 현장 안전 기능이나 인증된 PLC 인터록으로 설명하지 않는다.
