# 미션 필수 자원의 공통 접수 적용

## 목적과 사용자 흐름

미션에 필요한 스테이션을 서버 설정으로 선언한다. MES 주문, 공개 계약 호출, 운영자 수동 작업과 직접 제출이 같은 영속 접수·자원 대기열을 사용한다. 호출 입력으로 자원을 생략하거나 다른 스테이션으로 대체하지 못한다.

1. 엔지니어가 공유 자원 메뉴에서 일반 공정 신호를 연결한 스테이션을 등록한다.
2. 미션 필수 자원에서 미션·스테이션·사유를 등록한다. 현재 미션당 스테이션 하나, 요구 revision 1을 불변으로 보관한다.
3. 호출 전 화면에서 필요한 자원을 확인하고 기존 작업 API로 요청한다.
4. 서버가 접수 ID·원래 변환 결과·배포·필수 자원을 저장한다. WAITING_RESOURCE는 접수 성공이며 로봇 전송 전 대기다.
5. 공유 스테이션 조정기가 자원을 확보하고 준비 true·완료 false를 확인한 뒤 같은 접수와 내부 작업 ID로 실행한다.
6. 로봇 물리 완료와 제출 이후 설비 완료를 함께 확인한 뒤 해제한다. 다음 접수는 설비 완료 신호 초기화를 기다린다.

## 접수와 복구의 책임 경계

자원 요구는 workMasterId에 적용되므로 같은 미션을 가리키는 다른 계약이나 과거 배포로 우회할 수 없다. 배포 이력을 소급 변경하지 않으며, 접수의 requiredStationId와 저장된 normalizedOrder를 고정한다. 대기 중 계약 공개 상태·배포가 바뀌어도 저장된 변환으로 실행한다.

자원 요구 등록과 접수의 요구 확정은 같은 미션 advisory transaction lock을 사용한다. 요구 등록은 RECEIVED 또는 해당 미션의 미완료·결과 불명 접수가 있으면 거절한다. 처리 중 요청에 제약을 뒤늦게 덧씌우지 않는다. 현재 요구는 변경·삭제할 수 없으며 DB에서도 제한한다.

접수와 자원 대기열 연결은 한 트랜잭션이다. 네트워크 실행은 커밋 뒤에 수행한다. 조정기는 실제 전송 전에 receipt와 점유 자원·제출 단계가 일치하는지 확인한다. 같은 접수의 중복 호출은 기존 상태를 반환하며 별도 자원 작업을 만들지 않는다.

WAITING_RESOURCE 접수는 조정기만 제출한다. 제출 직전 DISPATCHING을 커밋하며 재기동 시 같은 접수를 다시 보내지 않는다. 상태가 불명확하면 기존 스테이션 정책대로 REVIEW_REQUIRED와 점유 유지를 적용한다.

일반 recover API는 자원 관리 접수를 거절한다. 호스트 전송 전 미배정은 조정기의 제한 재평가만 허용하고, 그때도 기존 점유·접수·입력을 확인한다. 준비 시간 초과·대기 취소는 원래 접수에 REJECTED와 사유를 남긴다. 수동 자원 해제는 물리 완료와 구분한다.

## API 호환성과 화면

- GET /api/mission-resources: 미션 자원 요구 조회
- POST /api/mission-resources: engineer, workMasterId·stationId·reason
- 기존 integration/v1/job-orders, 계약 invoke, work-requests/manual: 기존 접수 응답에 requiredStationId와 WAITING_RESOURCE 추가
- GET /api/work-requests/{id}: resource에 스테이션·자원 작업 ID·현재 점유 작업·앞선 대기 수·대기 사유 추가
- 기존 POST /api/job-orders: 이제 영속 수동 접수를 거친다. 자원 대기는 202 접수 응답, 즉시 제출은 기존 200 결과를 반환한다. 이 예전 API는 요청 ID를 받지 않으므로 멱등 재접수에는 /api/work-requests/manual을 사용한다.

공유 자원 화면에 미션 요구 등록·불변 요구 목록을 추가했다. 수동 작업과 공개 계약 호출 전에는 필수 자원 안내를 표시한다. 계약 배포 화면도 미션의 자원 요구를 보여준다. 접수 상세에서 자원 대기·앞선 작업 수·현재 점유 작업과 공유 자원 화면 이동을 제공한다. 자원 관리 작업에는 일반 미배정 재평가 버튼을 표시하지 않는다. 필수 자원이 있는 계약은 현재 사용 가능한 로봇이 없어도 유효한 입력을 확인한 뒤 접수할 수 있다. 자원·설비 준비 후 실제 제출 단계에서 로봇 배정 조건을 다시 평가한다.

## 검증

- Ops 서버 회귀: 42개 테스트 클래스, 332개 테스트 통과. 필수 자원 적용, 중복 접수, 재기동, 동시 조정, 일반 복구 우회 차단, 완료 신호 초기화를 포함한다.
- UI 회귀: 23개 파일, 213개 테스트 통과. TypeScript·Vite 프로덕션 빌드와 lint 통과.
- 의존성 경계: `:ops-service:checkNoPicassoOnMain` 통과.
- 실제 스택 브라우저: `ui/e2e/station.spec.ts` 1개 통과. 화면에서 요구를 등록하고 스테이션 요청과 일반 계약 호출을 혼합해 순차 실행·설비 신호 초기화·완료 근거를 검증했다. 모바일 390px에서 문서 가로 넘침이 없음을 검사했다.
- JVM 전체 스택: `MixedResourceScenarioTest`가 MES·계약 invoke·수동·직접 제출 4개를 같은 자원에서 순차 실행하고 MES 재전송의 중복 방지와 새 설비 완료 신호를 검증했다. 함께 실행한 JobOrderTest 3개·MissionVersionTest 4개·StationScenarioTest 1개를 포함해 4개 클래스, 총 9개 테스트가 모두 통과했다.
- Narrator: 실제 `build/reports/mixed-resource-orchestration.json`을 `compose_station`으로 변환해 원본 유지·설명 전용 경계를 확인했다. 자원 근거 테스트 6개 통과. 외부 생성 API는 호출하지 않았다.

화면 캡처: [데스크톱](style-preview/mission-resource-fullstack.png), [모바일](style-preview/mission-resource-mobile-fullstack.png).

브라우저 시험은 모의 로봇·공정 신호와 실제 Ops API·PostgreSQL을 사용했다. 실물 로봇·PLC 검증 결과는 아니다.

브라우저 실행은 `cd ui` 후 `npx playwright test`(생애주기), `npx playwright test --config=playwright.station.config.ts`(공유 자원)로 분리한다. 생애주기 시험은 마지막에 registry를 중단하므로 두 시험은 각각 새 스택에서 실행한다. CI도 같은 순서로 별도 호출한다.

## 제한과 다음 범위

Ops가 제공하는 호출 경로에 적용한다. mission-host를 직접 호출하는 외부 시스템이나 독립 장비의 제어를 잠그는 물리 인터록은 아니다. 일반 공정 신호와 공통 시계를 사용하며 실물 PLC의 작업 ID echo·fencing token 기반 핸드셰이크는 후속 범위다. 다중 자원 동시 획득·교착 방지와 자원 요구 변경·마이그레이션도 별도 범위다.
