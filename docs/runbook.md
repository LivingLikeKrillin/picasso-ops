# 운영 리허설 절차

이 문서는 picasso-ops PoC 의 운영 리허설 절차 둘을 적습니다. C1 은 운영 중 호출 계약 배포 변경과 되돌림, D1 은 재기동과 재개입니다. 이 절차는 [운영 보장 명세](guarantees.md)의 [배포 전제](guarantees.md#배포-전제), 특히 같은 DB 에 활성 운영 서비스가 하나라는 전제에서만 맞습니다.

이것은 리허설 절차이지 안전 절차가 아닙니다. Ops 의 판단은 인증된 안전 정지나 안전 PLC, 장비 인터록을 대신하지 않습니다([안전](guarantees.md#안전)). 로봇은 picasso mimic(에뮬레이터), 설비는 셀 대역, 상위 시스템은 로컬 Mock MES 하나입니다.

## 공통 사항

- 단계마다 자동 동작의 근거 시험 이름을 붙입니다. 시험 위치는 운영 서비스 시험 `ops-service/src/test/kotlin/dev/picasso/ops/service/`, 실행 호스트 시험 `mission-host/src/test/kotlin/dev/picasso/ops/host/`, 통합 시험 `e2e/src/test/kotlin/dev/picasso/ops/e2e/`, 화면 시험 `ui/e2e/` 입니다. 코드 위치 접두 `ops-service/src/main/kotlin/dev/picasso/ops/service/` 는 줄여 `requests/WorkRequests.kt` 처럼 씁니다.
- 용어에서 배포는 같은 호출 계약 버전(`interfaceId`, `interfaceVersion`)의 `deploymentVersion` 입니다. 조합은 (호출 계약 버전, 배포 버전, 임무 버전) 셋입니다.
- 모드에서 배포 적용은 엔지니어 모드, 미배정 재평가·현장 확인 기록·스테이션 판단·실행 취소·기체 빈손 확인은 운영자 모드에서만 됩니다. API 는 `X-Ops-Mode`·`X-Ops-User` 헤더로 모드와 사용자를 고릅니다(인증 아님).
- 화면 상세 응답과 API 는 같은 값입니다: 작업 요청 상세는 `GET /api/work-requests/{id}` 입니다.

## C1 운영 중 호출 계약 배포 변경과 되돌림

같은 호출 계약 버전의 실행 배포를 v1(임무 버전 X)에서 v2(임무 버전 Y)로 바꾸고 다시 X 로 되돌리는 동안, 먼저 받은 작업 요청과 대기 중인 작업이 어떤 조합으로 나가는지 확인합니다.

### 사전 조건
- 같은 임무의 임무 버전 둘 X(이전), Y(새것)를 활성화해 둡니다. 화면에서는 임무 → «실행 정의» 에서 초안 저장, 검증, 모의 실행, 활성화를 거칩니다. 코드 정의 임무(`InspectAsset`, `PrepareSequencedRack`)는 `CODE` 로 배포할 수 있고, 새 임무는 숫자 임무 버전만 배포할 수 있습니다.
- 호출 계약 버전 하나를 만들고 배포 v1 = X 를 적용한 뒤 공개합니다. 배포가 없으면 공개가 409 `DEPLOYMENT_REQUIRED` 입니다. 화면에서는 임무 → «호출 연결» → «버전별 호출 계약» 에서 계약을 선택한 뒤 «실행 배포 · v1» 구역을 확인합니다. 배포 API 는 `POST /api/mission-interfaces/{id}/{version}/deploy`(본문 `missionVersion`, `reason`, `expectedDeploymentVersion`), 공개 API 는 `POST /api/mission-interfaces/{id}/{version}/state` 입니다.
- 리허설은 계약 경로로 합니다: 외부 주문 `POST /integration/v1/job-orders`(Bearer `PICASSO_INTEGRATION_TOKEN`, 예: `node scripts/mock-mes.mjs order.json --poll`) 또는 호출 계약 호출 `POST /api/mission-interfaces/{id}/{version}/invoke` 입니다. 화면 수동 요청 `POST /api/work-requests/manual` 과 `POST /api/job-orders` 는 배포를 싣지 않고 실행 호스트의 제출 시점 활성 임무 버전을 쓰므로 이 리허설 대상이 아닙니다.

### C1-1 현재 배포와 진행 중 작업 요청 확인
- 조작: 현재 배포와 진행 중인 작업 요청을 기록합니다.
- 확인할 곳: 임무 → «호출 연결» → 계약 → «실행 배포 · vN» 과 «불변 배포 이력», API `GET /api/mission-interfaces/{id}/{version}/deployments`(최신 것부터 `deploymentVersion`, `missionVersion`, `createdBy`, `reason`, `createdAt`) 입니다. 작업 요청 → «통합 작업 요청» 의 상태 필터, API `GET /api/work-requests?state=` 와 `GET /api/work-requests/summary` 입니다. 운영 → «실행» 표의 «임무 버전» 칸입니다.
- 기대하는 결과: 현재 배포가 v1 이고 임무 버전 X 입니다.
- 다르면: 배포가 없으면 사전 조건부터 다시 합니다.

### C1-2 v1 로 작업 요청 A 받기
- 조작: 외부 주문 A 를 보냅니다.
- 확인할 곳: 작업 요청 → «통합 작업 요청» 에서 A → «작업 요청 상세» 입니다. 구역 «요청 시 고정된 배포» 가 `receipt.normalizedOrder.deployment` 를 보입니다. «적용 임무 버전» 은 실행의 임무 버전이고 null 이면 «코드 정의» 로 보입니다. API 는 `GET /api/work-requests/{id}` 의 `receipt.normalizedOrder.deployment.deploymentVersion`, `receipt.normalizedOrder.missionVersion`, `receipt.jobOrderId`, `receipt.state` 입니다.
- 기대하는 결과: 새 외부 주문 응답 202 입니다. 배포 1, 임무 버전 X 입니다. 실행의 임무 버전도 X 입니다.
- 다르면: 상태가 `REJECTED` 이면 `detail` 을 읽습니다. 실행 호스트가 거부했으면 `detail` 이 «호스트 제출 거부: <사유>» 이고 화면은 detail 문장 대신 구역 «거부 사유» 를 보입니다(API `rejection`). 공개 전 계약이면 «공개된 계약만 호출할 수 있습니다»(409 `INTERFACE_NOT_ENABLED`) 이며 호스트에 보내지 않았습니다.
- 근거 시험: `MissionInterfacesTest` 의 `배포 이력은 불변이고 새 배포는 새 요청에만 적용된다`, 통합 시험 `ManufacturingScenarioTest`(작업 요청의 `normalizedOrder.missionVersion`), 실행 호스트 시험 `MissionVersionsTest` 의 `배포 고정은 활성 버전 변경 뒤에도 원래 미션으로 실행하고 글로벌 활성 버전을 바꾸지 않는다`(작업 지시의 `missionVersion` 으로 임무 버전을 고름) 입니다.

### C1-3 새 배포 v2 = Y 적용
- 조작: 엔지니어 모드에서 «배포할 임무 버전» 으로 Y 를 고르고 «배포 판단 사유» 를 적어 «새 실행 배포 적용» 을 누릅니다.
- 확인할 곳: «실행 배포 · v2», «불변 배포 이력» 에 v1·v2 입니다. 이력 → «조작 기록» 에 대상 `interface:{id}/v{version}` 과 사유가 있는 행입니다. API `GET /api/operations` 의 그 행 `request` 에 `"op":"INTERFACE_DEPLOY"` 가 있습니다.
- 기대하는 결과: 배포 v2 가 됩니다. v1 행은 그대로입니다(`ops.interface_deployment` 는 DB 트리거가 UPDATE·DELETE 를 막습니다). 배포는 DB 만 쓰고 실행 호스트를 부르지 않으므로 도는 실행과 활성 임무 버전은 바뀌지 않습니다.
- 다르면: 409 `STALE_DEPLOYMENT`(«배포 버전이 바뀌었습니다. 다시 조회하세요») 이면 그사이 다른 배포가 있었습니다. 이력을 다시 읽고 판단합니다. 응답을 못 받았으면 다시 누르기 전에 배포 이력부터 봅니다(화면 안내: «응답 불명 시 계약을 다시 조회하고 배포 이력을 확인하세요.»). 같은 기대 버전으로 다시 보내면 409 이므로 두 번 배포되지 않습니다.
- 근거 시험: `MissionInterfacesTest` 의 `배포 이력은 불변이고 새 배포는 새 요청에만 적용된다`(지난 기대 버전 409, UPDATE 거부) 입니다. `WorkRequestsTest` 의 `배포를 바꾸고 되돌려도 먼저 받은 작업 요청은 받은 때의 계약 배포 임무 버전을 유지하고 이력에 남긴다`(배포 조작마다 `INTERFACE_DEPLOY` 조작 기록 행과 사유) 입니다.

### C1-4 새 요청은 v2, 먼저 받은 A 는 v1 유지, A 재전송
- 조작: 외부 주문 B 를 보내고, A 의 상세를 다시 열고, A 를 같은 본문 그대로 다시 보냅니다.
- 확인할 곳: B 와 A 의 «작업 요청 상세» 입니다. 재전송 응답 코드, `receipt.receiptId`, `duplicate` 를 확인합니다. 운영 → «실행» 표에 새 실행이 생겼는지 확인합니다.
- 기대하는 결과: B 는 배포 2, 임무 버전 Y 입니다. A 는 배포 1, 임무 버전 X 그대로입니다. 재전송은 200, `duplicate` true, 같은 작업 요청 id 와 작업 지시 id 이며, 새 실행이 생기지 않습니다. 코드는 변환 전에 저장된 행을 돌려줍니다.
- 다르면: 재전송이 409 `ORDER_CONFLICT` 이면 본문이 한 칸이라도 다른 것입니다(호출 계약 호출이면 `expectedDeploymentVersion` 도 본문에 듭니다). 재전송이 아니라 새 요청이므로 새 요청 id 로 보냅니다. 호출 계약 호출에 `expectedDeploymentVersion` 1 을 실어 v2 뒤에 보내면 작업 요청이 `REJECTED`, `detail` «배포가 변경되었습니다. 배정 가능 여부를 다시 확인하세요» 이고 호스트에 보내지 않았습니다. «실행 전 배정 확인»(API `POST /api/mission-interfaces/{id}/{version}/eligibility`)을 다시 하고 새 요청 id 로 보냅니다.
- 근거 시험: `MissionInterfacesTest` 의 `배포가 달라진 새 호출은 제출하지 않고 같은 요청 재확인은 기존 접수를 반환한다`(배포 변경 뒤 같은 요청 200·같은 작업 요청, 지난 기대 배포는 `REJECTED`, 제출 1회), `WorkRequestsTest` 의 `같은 주문은 동일 접수와 내부 ID를 반환하고 저장소를 다시 만들어도 재전송하지 않는다`, 화면 시험 `lifecycle.spec.ts`(새 배포 v2 적용 뒤 계약 작업 요청이 `deploymentVersion` 2) 입니다. `WorkRequestsTest` 의 `배포를 바꾸고 되돌려도 먼저 받은 작업 요청은 받은 때의 계약 배포 임무 버전을 유지하고 이력에 남긴다`(배포 변경 뒤 같은 요청 재전송이 `duplicate` 이고 받은 때 배포·임무 버전 그대로, 제출 수 그대로) 입니다.

### C1-5 어느 정의가 어느 요청을 처리했는지
- 조작: A 와 B 의 기록을 대조합니다.
- 확인할 곳: «작업 요청 상세» → «외부 결과 · 수신 확인» → «불변 결과 스냅샷 · vN»(최신 결과 버전 하나만 보임)의 `deployment`, `missionVersion` 입니다. 결과 버전마다는 API `GET /integration/v1/job-orders/{id}/results/{version}`(Bearer, 외부 주문 출처만)의 `snapshot.deployment`, `snapshot.missionVersion`, `resultHash` 입니다. 이력 → «조작 기록» 에서 대상이 내부 작업 ID(`jobOrderId`)인 행, API `GET /api/operations` 의 `request` 에 `interfaceRef`, `jobOrder.deployment`, `jobOrder.missionVersion`, 그 행 `requestId` 가 작업 요청 id 입니다. 검증 도구 → «호출 계약과 실행 근거 비교» 에서 A 와 B 를 골라 «두 작업 요청 비교하기» 를 누릅니다.
- 기대하는 결과: A 는 배포 1·임무 버전 X, B 는 배포 2·임무 버전 Y 입니다. 비교 결과는 «계약 또는 실행 배포가 다릅니다.» 입니다.
- 다르면: 스냅샷 `deployment` 가 null 이면 배포 없는 경로(화면 수동 요청 등)로 받은 작업 요청입니다. «저장된 결과가 없습니다. 물리 상태만으로 결과를 확정하지 않습니다.» 이면 결과 수집을 기다립니다.
- 근거 시험: `MissionInterfacesTest` 의 `공개 계약 호출은 기존 작업 제출과 연결되고 매핑 버전과 원본을 감사 기록에 남긴다`(`interfaceRef`·`interfaceInput`), `WorkRequestsTest` 의 `같은 주문은 동일 접수와 내부 ID를 반환하고 저장소를 다시 만들어도 재전송하지 않는다`(조작 기록 `requestId` = 작업 요청 id), 화면 시험 `lifecycle.spec.ts`(비교 문구) 입니다. `WorkRequestsTest` 의 `배포를 바꾸고 되돌려도 먼저 받은 작업 요청은 받은 때의 계약 배포 임무 버전을 유지하고 이력에 남긴다`(결과 스냅샷의 `deployment`·`missionVersion`, 조작 기록 제출 행의 `jobOrder.deployment`·`missionVersion` 과 `requestId`) 입니다.

### C1-6 되돌림
- 조작: 엔지니어 모드에서 새 배포 v3 = X 를 사유와 함께 적용합니다. v2 를 지우거나 고치는 조작은 없습니다.
- 확인할 곳: «불변 배포 이력» 에 v1·v2·v3, «조작 기록» 의 새 행입니다. 외부 주문 C 를 보내 상세를 확인합니다. A 와 B 의 상세와 결과 스냅샷을 다시 읽습니다.
- 기대하는 결과: C 는 배포 3·임무 버전 X 입니다. A(배포 1·임무 버전 X), B(배포 2·임무 버전 Y)와 그 결과 버전·인시던트·조작 기록은 그대로입니다. 되돌림은 실행 호스트를 부르지 않으므로 이미 도는 실행과 끝난 물리 작업은 되돌리지 않습니다. 멈춰야 하면 인시던트 판단이나 현장 조치로 따로 합니다. 임무 정의 자체를 되돌리려면 같은 정의를 다시 활성화하며 이것은 새 임무 버전 번호(가장 큰 번호 + 1)가 됩니다. 계약 경로는 배포가 가리키는 번호만 쓰므로 활성화만으로 계약 작업 요청이 바뀌지 않습니다.
- 다르면: 409 `STALE_DEPLOYMENT` 이면 이력을 다시 읽습니다. `ops.work_result` 는 DB 트리거가 없으므로 결과를 SQL 로 고치지 않습니다([결과 버전의 DB 불변](guarantees.md#결과-버전의-db-불변)).
- 근거 시험: `MissionInterfacesTest` 의 `배포 이력은 불변이고 새 배포는 새 요청에만 적용된다`(되돌림 배포가 배포 3), 실행 호스트 시험 `MissionVersionsTest` 의 `재기동해도 활성 버전 번호가 같고 다음 활성화는 가장 큰 번호 더하기 1 이다`, `OperationLogTest` 의 `조작 기록은 고칠 수 없다`·`조작 기록은 지울 수 없다` 입니다. `WorkRequestsTest` 의 `배포를 바꾸고 되돌려도 먼저 받은 작업 요청은 받은 때의 계약 배포 임무 버전을 유지하고 이력에 남긴다`(되돌림 뒤 먼저 받은 작업 요청의 저장 작업 지시와 실패 결과 버전이 그대로, 배포 이력 넷이 모두 남음) 입니다.

### C1-7 대기 중 작업의 경로별 동작
- 조작: 배포를 바꾸기 전에 대기 중이던 작업을 경로별로 확인합니다.
- 확인할 곳: «작업 요청 상세» 의 상태와 `detail`, 공유 자원 «최근 작업과 대기 사유», API `GET /api/stations/runs/{id}/evidence` 입니다.
- 경로 1 필수 자원 대기 `WAITING_RESOURCE`(화면 «필수 자원·설비 대기»):
  - 기대하는 결과: 스테이션이 준비되면 저장한 작업 지시(`normalizedOrder`)를 그대로 제출합니다. 배포가 바뀌거나 계약이 공개 중단되어도 받은 때 조합입니다. 계약을 공개 중단해도 `WAITING_RESOURCE` 작업 요청은 이렇게 제출되지만, 같은 상황에서 `UNASSIGNED` 미배정 재평가는 409 `INTERFACE_NOT_ENABLED` 로 거부됩니다(경로 3). 두 경로가 다르게 동작합니다.
  - 근거 시험: `WorkRequestsTest` 의 `자원 준비 후 원래 접수와 고정 변환으로 한 번만 실행한다`(대기 중 공개 중단과 새 배포 뒤에도 제출본 = 저장본, 배포 1 유지) 입니다.
- 경로 2 공유 자원 «공유 스테이션 작업 요청» 으로 넣은 스테이션 작업(본문에 `expectedDeploymentVersion`, 제출 전에는 작업 요청이 없음):
  - 기대하는 결과: 넣을 때 배포를 확인하고(다르면 400 `STATION_INPUT`, «조회한 계약 배포가 변경되었습니다»), 제출 때 `expectedDeploymentVersion` 을 실어 호출 계약을 부릅니다. 그사이 배포가 바뀌면 작업 요청은 `REJECTED`(«배포가 변경되었습니다. 배정 가능 여부를 다시 확인하세요»), 스테이션 작업은 새 배포로 옮기지 않고 `REJECTED`(«계약 제출 거부: 배포가 변경되었습니다. 배정 가능 여부를 다시 확인하세요; 자원 해제»)로 닫히고 점유가 풀리며, 로봇에 보내지 않았으므로 격리되지 않습니다.
  - 다르면: 운영자는 현재 배포를 다시 조회해 새 요청 id 로 다시 넣습니다.
  - 근거 시험: `WorkRequestsTest` 의 `배포가 바뀌면 대기 중인 스테이션 작업은 새 배포로 옮기지 않고 거부로 닫고 점유를 푼다`(실제 `OpsStationPort` 로 작업 요청·스테이션 작업 `REJECTED`, 점유 해제, 격리 없음, 제출 없음) 입니다.
- 경로 3 미배정 `UNASSIGNED`(제출 기록과 실행 관측 없음) 재평가:
  - 기대하는 결과: 원래 입력을 다시 변환한 뒤 저장한 배포로 덮어써 받은 때 조합으로 보냅니다. 새 배포로 옮기지 않습니다. 새 정의로 보내려면 새 요청 id 로 새 작업 요청을 만듭니다. 화면에서는 «미배정 재평가», API 는 `POST /api/work-requests/{id}/recover`(본문 `requestId`, `reason`, `expectedUpdatedAt`) 입니다. 배포가 바뀐 것만으로는 `RECOVERY_INPUT_CHANGED` 가 나지 않습니다. 저장한 배포로 덮어쓴 뒤 비교하므로 이 코드는 같은 입력의 변환 자체가 달라졌을 때만 납니다.
  - 다르면: 다시 변환한 작업 지시가 저장본과 다르면(예: 운영 서비스 업그레이드로 변환 코드가 바뀐 경우) 409 `RECOVERY_INPUT_CHANGED`(«원래 변환 입력과 달라 재평가를 차단했습니다»)이고 상태는 `UNASSIGNED` 그대로, 제출 없음입니다. 이때는 새 요청 id 로 보냅니다. 계약이 공개 중단되었으면 409 `INTERFACE_NOT_ENABLED` 이고, 공개를 되돌리거나 다른 계약 버전으로 새 작업 요청을 만듭니다. 제출 기록이나 실행 관측이 있으면 409 `RECOVERY_NOT_SAFE` 입니다.
  - 근거 시험: `WorkRequestsTest` 의 `배포가 바뀐 뒤 미배정 재평가는 원래 배포로 제출하고 변환이 달라지면 RECOVERY_INPUT_CHANGED 로 막는다`(배포 변경 뒤 재평가가 배포 1 로 제출, 변환이 달라지면 409 `RECOVERY_INPUT_CHANGED` 이고 상태·`updatedAt` 그대로·재평가 기록 없음·제출 없음, 공개 중단 뒤 409 `INTERFACE_NOT_ENABLED`), `불명 접수와 오래된 상태는 재평가할 수 없다` 입니다.
- 경로 4 필수 자원이 있는 임무의 작업 요청:
  - 기대하는 결과: 일반 재평가가 409 `RESOURCE_MANAGED` 이고 공유 자원 화면에서만 다룹니다.
  - 근거 시험: `WorkRequestsTest` 의 `필수 자원 미배정은 일반 재평가 API로 우회하지 못한다` 입니다.

### C1 에서 확인하는 기록
| 기록 | 화면 | API | 알 수 있는 것 |
|---|---|---|---|
| 작업 요청 행 | 작업 요청 → «작업 요청 상세», «요청 시 고정된 배포» | `GET /api/work-requests/{id}` 의 `receipt.normalizedOrder` | 받은 때 배포 버전과 임무 버전 |
| 결과 버전 스냅샷 | «불변 결과 스냅샷 · vN» | `GET /integration/v1/job-orders/{id}/results/{version}` | 그 결과를 낸 실행의 `missionVersion` 과 작업 요청의 `deployment` |
| 배포 이력 | «불변 배포 이력» | `GET /api/mission-interfaces/{id}/{version}/deployments` | 배포마다 임무 버전, 적용한 사람, 사유 |
| 조작 기록 | 이력 → «조작 기록» | `GET /api/operations` | `INTERFACE_DEPLOY` 행(배포 조작과 사유), 제출 행의 `jobOrder.deployment`·`missionVersion` |
| 실행 | 운영 → «실행» 의 «임무 버전» | `GET /api/executions` | 실행 호스트가 실제로 쓴 임무 버전 |

이 절차를 위해 더하거나 넓힌 시험: `WorkRequestsTest` 의 `배포를 바꾸고 되돌려도 먼저 받은 작업 요청은 받은 때의 계약 배포 임무 버전을 유지하고 이력에 남긴다`, `배포가 바뀐 뒤 미배정 재평가는 원래 배포로 제출하고 변환이 달라지면 RECOVERY_INPUT_CHANGED 로 막는다`, `배포가 바뀌면 대기 중인 스테이션 작업은 새 배포로 옮기지 않고 거부로 닫고 점유를 푼다`, 그리고 넓힌 `자원 준비 후 원래 접수와 고정 변환으로 한 번만 실행한다`(대기 중 새 배포 뒤에도 배포 1 단언 추가).

## D1 재기동과 재개

실행 호스트 재기동과 운영 서비스 재기동을 따로 하고, 자동으로 이어지는 것과 사람이 판단해야 하는 것을 가릅니다. 코드 위치 접두 `ops-service/src/main/kotlin/dev/picasso/ops/service/` 는 생략합니다.

### 사전 조건
- README 의 순서로 스택을 띄워 둡니다. 실행 호스트는 터미널 2, 운영 서비스는 터미널 3 에서 저장소 루트 기준으로 돕니다. 실행 호스트 명령은 `env -u PICASSO_INGEST_TOKEN -u PICASSO_OPERATOR_TOKEN mission-host/build/install/mission-host/bin/mission-host` 입니다. 운영 서비스 명령은 `env -u PICASSO_INGEST_TOKEN ops-service/build/install/ops-service/bin/ops-service` 입니다(외부 주문을 쓰면 같은 터미널에 `PICASSO_INTEGRATION_TOKEN`, 콜백을 보려면 `PICASSO_LOCAL_CALLBACK_URL`·`PICASSO_LOCAL_CALLBACK_TOKEN`).
- 재기동 전에 도는 실행, `IN_DOUBT` 작업 요청, 운영자 보류에 선 실행, 공유 스테이션 작업이 하나씩 있으면 각 경로를 볼 수 있습니다.
- 시험 메모: 운영 서비스 재기동은 단위 시험에서 같은 DB 위에 서비스 객체를 새로 만드는 것으로 모사하고, 통합 시험 하네스에는 실행 호스트 재기동만 있습니다. 실제 프로세스를 내렸다 올리는 것은 사람 손 절차입니다.

### D1-0 재기동 전 기록
- 조작: 아래 값을 적어 둡니다.
- 확인할 곳: 운영 개요의 «호스트 실행 관측», «결과 자동 수집»(수집 커서와 확인한 마지막 번호), «최근 작업 요청» 입니다. API `GET /api/work-requests/summary`, `GET /api/work-requests/collector`(`state`, `cursor.sequence`, `cursor.highWatermark`, `cursor.producerId`, `cursor.mode`)입니다. 운영 → «실행» 의 «실행 호스트 인스턴스» 와 실행 id, API `GET /api/executions`(`instanceId`, `executions`, `journalBlockedExecutionIds`, `restore`)입니다. 운영 → «작업 응답 송신 기록», API `GET /api/job-responses?jobOrderId=` 입니다. 공유 자원 «최근 작업과 대기 사유»(점유 작업, 대기열, 격리), API `GET /api/stations` 입니다. 작업 요청 상태 필터 «제출 확인 필요 · 전체»(API `state=NEEDS_CONFIRMATION`, 곧 `RECEIVED`·`DISPATCHING`·`IN_DOUBT`)입니다.
- 기대하는 결과: 비교 기준이 남습니다.
- 다르면: 화면은 값을 읽지 못하면 «모름» 으로 표시합니다. 예: 운영 개요의 «수집 상태 모름», 운영 → 실행의 «모름: 실행 목록을 아직 읽지 못했습니다». 읽지 못한 값은 재기동 전후 비교의 기준이 될 수 없습니다. 그 값을 «모름» 으로 적어 두고 읽힌 값과 구분합니다. 재기동 뒤 그 항목은 전후 비교가 아니라 재기동 뒤 값만으로 판단합니다.

### D1-1 실행 호스트 재기동
- 조작: 터미널 2 의 실행 호스트를 멈추고 같은 명령으로 같은 DB 에 다시 띄웁니다.
- 확인할 곳: 운영 → «실행» 위의 복원 보고(문구 «다시 지었습니다», «미룬 실행», «포기한 실행»)와 실행 id 옆 이전 인스턴스 표시, «작업 응답 송신 기록» 의 처분 칸, 작업 요청 상세입니다.
- 기대하는 결과:
  - 자동으로 일어나는 것: 기동 때 pump 를 시작하기 전에 일지에서 정착하지 않은 행을 받은 순서와 받은 때 임무 버전으로 다시 짓습니다. 다시 지은 실행은 같은 태스크 id 로 다시 붙고 새 태스크를 내지 않습니다. 인스턴스 id 가 바뀌고 실행 id 는 `exec-1` 부터 다시 셉니다. 복원 결과는 `RESTORED`(다시 지음), `DEFERRED`(기체 스냅숏을 못 읽음, pump 마다 다시 시도, 그동안 그 기체는 배정에서 빠짐), `GAVE_UP`(그 밖의 거부, 다시 짓지 않음, 그 작업 지시의 태스크가 끝날 때까지 그 기체를 판정에서 뺌)입니다. 새 인스턴스에서 어떤 작업 지시의 첫 작업 응답이 가장 최근 송신 기록과 내용이 같으면 `RESTART_DUPLICATE`(화면 «재기동 중복(송신 안 함)»)로 적고 결과 outbox 에 넣지 않으므로 운영 서비스에 새 결과 버전이 생기지 않습니다. 운영 서비스는 `IN_DOUBT` 작업 요청의 작업 지시가 새 인스턴스 실행 목록에 보이면(일지 차단 실행이 아니면) `ACCEPTED` 로 올리고 `executionInstanceId` 를 새 인스턴스로 적습니다.
  - 일어나지 않는 것: `IN_DOUBT` 작업 요청을 자동으로 다시 제출하지 않습니다. 보류 판단을 다시 넣지 않습니다. 다시 선 보류는 사람이 다시 판단하고, 이전 인스턴스 id 를 실은 판단은 409 `INSTANCE_MISMATCH` 입니다. 이전 인스턴스의 인시던트와 판단은 `earlier` 에서 봅니다. 운영자 취소를 다시 걸지 않습니다(재기동 전에 `ABORTED` 로 정착하지 못한 취소는 다시 지은 실행에 걸리지 않고, 새 실행의 `cancel` 칸은 null; 그 실행이 아직 부분 완료면 새 실행 id 로 다시 취소). 이전 인스턴스 id 를 실은 취소도 409 `INSTANCE_MISMATCH` 입니다. 잔여 파지 표시는 그대로 남아 그 기체를 계속 뺍니다(자동으로 풀리지 않음). 근거 시험: `ExecutionControlTest` 의 `든 채 실패로 정착한 실행의 표시는 재기동해도 기체를 빼고 해소도 재기동 뒤까지 남으며 표시는 작업 지시마다 하나다`, `취소는 정착한 실행과 없는 실행을 받지 않고 다른 인스턴스는 409 이며 에이전트 승인자는 거부한다`.
- 다르면: 복원 중 DB 가 실패하면 기동이 멈추므로 DB 부터 확인합니다. «포기한 실행» 이 있으면 화면 안내대로 그 기체의 남은 태스크를 운영자가 확인합니다. «미룬 실행» 이 오래 남으면 그 기체의 상태(로봇 메뉴)를 확인합니다.
- 근거 시험: 실행 호스트 시험 `HostRestoreTest` 의 `도는 실행을 재기동하면 같은 작업 지시가 새 인스턴스의 실행으로 다시 서고 끝난 단위는 끝난 대로 새 태스크 없이 이어 끝까지 간다`, `재작업한 로봇 단위를 재기동하면 @rN 태스크에 다시 붙고 새 태스크를 내지 않는다`, `받은 뒤 다른 임무 버전을 활성화하고 재기동해도 받은 때의 임무 정의로 다시 짓는다`, `기체 스냅숏을 못 읽으면 미루고 그 기체를 판정에서 빼며 pump 마다 다시 시도해 읽히면 다시 짓고 그 밖의 거부는 포기한다`, `운영자 보류에 선 실행을 두 번 다시 띄워도 새 인스턴스의 첫 보류 응답은 재기동 중복이고 같은 인스턴스의 다음 보류는 송신이다`(결과 피드에 `RESTART_DUPLICATE` 응답이 들어가지 않고 피드 이벤트가 `SENT` 행과 하나씩 맞음) 입니다. `HostJournalTest` 의 `재기동 뒤 이전 인스턴스의 인시던트와 판단은 earlier 와 instanceId 상세에만 있고 그 인스턴스를 실은 판단은 409 다` 입니다. 통합 시험 `RestartRecoveryTest` 의 단계 2(태스크마다 `ACCEPTED` 한 번), 단계 4, 단계 6 입니다. 운영 서비스 시험 `WorkRequestsTest` 의 `전송 불명은 실행 관측으로 확정하고 호스트 단절 후에도 직전 관측을 보존한다`, `응답 불명과 제출 중단 상태는 재접수로 다시 실행하지 않는다`, `결과 불명 작업 요청은 재기동한 호스트에서 다시 지은 실행이 보일 때만 수락이 되고 일지 없는 실행은 사라져도 결과 불명으로 남는다`(재기동한 호스트에 다시 지은 실행이 보일 때만 `ACCEPTED`, 새 인스턴스 id 기록) 입니다.

### D1-2 운영 서비스 재기동
- 조작: 터미널 3 의 운영 서비스를 멈추고 같은 환경 변수로 다시 띄웁니다.
- 확인할 곳: 작업 요청 필터 «제출 확인 필요 · 전체» 와 각 상세의 `detail`, 운영 개요 «결과 자동 수집», 공유 자원 «최근 작업과 대기 사유» 와 «기록된 근거» 입니다.
- 기대하는 결과:
  - 자동으로 일어나는 것: 결과 수집 주기(기본 5초, `ops.results.poll-ms`)마다 수집보다 먼저 멈춘 행 정리가 돕니다. `ops.requests.stale-seconds`(기본 300초)보다 오래 멈춘 `RECEIVED` 는 `REJECTED`(«작업 요청 처리 중단; 호스트에 보내지 않았습니다. 새 요청 ID로 다시 보내십시오»), `DISPATCHING` 은 `IN_DOUBT`(«제출 도중 중단; 실행 관측과 현장 확인이 필요합니다»)로 닫습니다. `WAITING_RESOURCE` 는 건드리지 않습니다. 결과 수집은 `ops.result_cursor` 의 순번부터 이어 읽습니다. 같은 키의 재전송은 기존 작업 요청을 돌려주고 다시 제출하지 않습니다. `IN_DOUBT` 는 실행 목록에 그 작업 지시가 보이거나 결과 피드에 그 작업 지시의 이벤트가 오면 `ACCEPTED` 가 됩니다. 스테이션 점유, 대기열, 상태 이력은 그대로 이어집니다. 멈출 때 `RETRYING` 이던 스테이션 작업은 `REVIEW_REQUIRED`(«재평가 중 프로세스 중단; 실행 관측과 현장 확인 필요»), `REWORKING` 이던 것은 `REVIEW_REQUIRED`(«재작업 전달 중 프로세스 중단; 반영 여부는 실행 관측과 현장 확인 필요»)가 되고 점유를 쥡니다. `DISPATCHING` 이던 스테이션 작업은 같은 키로 작업 요청을 확인할 뿐 새로 보내지 않습니다. 격리는 DB 에 남아 유지됩니다.
  - 일어나지 않는 것: `IN_DOUBT` 는 재전송, 재평가, 현장 확인 기록으로 바뀌지 않고 자동 재제출도 없습니다.
- 다르면: «결과 자동 수집» 이 «수집 실패 · 확인 필요» 로 남고 생산자 id 가 바뀌었으면(실행 호스트 DB 를 새로 만든 경우) 수집이 멈추고 커서를 유지합니다. 자동으로 풀리지 않으므로 사람이 대조합니다. 300초가 지나지 않은 `RECEIVED`·`DISPATCHING` 은 아직 정리 대상이 아니므로 기다립니다.
- 근거 시험: `WorkRequestsTest` 의 `오래 멈춘 접수 전 단계와 제출 중 단계만 정리하고 자원 대기는 두지 않는다`, `정리가 먼저 닫은 접수는 이어서 제출하거나 대기열에 넣지 않는다`, `결과 수집 주기는 정리 실패와 무관하게 결과를 모은다`, `durable cursor resumes after restart and never lets detail reorder backlog`(새 인스턴스가 같은 커서에서 이어 읽고 생산자 id 가 바뀌면 멈춤), `operator review is immutable idempotent fenced and never submits or completes work`, `공통 자원 접수는 재시작 후에도 같은 대기열과 순서를 유지한다` 입니다. `StationCoordinatorTest` 의 `재기동은 점유와 대기열과 이벤트를 보존한다`, `재평가 중 재기동은 자동 재전송 없이 현장 확인으로 넘긴다`, `재작업 전달 중 프로세스가 멈추면 재기동 뒤 확인 필요로 넘긴다`, `전송 기록 직후 재기동은 동일 키를 확인하고 추가 실행하지 않는다` 입니다. 화면 시험 `lifecycle.spec.ts` 의 결과 불명 단계(운영 서비스 재기동 뒤 수집 커서 순번 유지, 재전송 200 과 같은 작업 요청, 호스트 제출 수가 한 번만 늘어 있음, 실행 호스트 재기동 뒤 결과 피드 동일, 연결 복구 뒤 `ACCEPTED`)입니다. 결과 피드 이벤트로 `IN_DOUBT` 가 `ACCEPTED` 되는 경로만 단언하는 기존 시험은 없습니다(코드 `requests/WorkRequests.kt` 의 `collectEvents`).

### D1-3 결과 전달 중단과 재개
- 조작: 콜백을 받는 로컬 Mock MES 수신 쪽을 멈췄다가 다시 띄웁니다.
- 확인할 곳: «작업 요청 상세» 의 «로컬 Mock MES 결과 전달»(버전마다 상태, 시도 수, 오류, 다음 시도 시각), API `GET /api/work-requests/{id}` 의 `deliveries`(`version`, `state`, `attempts`, `lastError`, `nextAttemptAt`, `deliveredAt`), 작업 요청 필터 «최신 결과 수신 미확인»(API `result=unacknowledged`)입니다.
- 기대하는 결과: 수신 쪽이 멈춘 동안 `RETRY` 로 남고 연결 실패면 `lastError` 가 «로컬 연결 실패 또는 응답 불명», HTTP 오류면 «HTTP {코드}» 입니다. 다음 시도까지 2의 min(시도 수, 8)제곱 초(최대 256초)를 기다리고 시도 수 상한이 없으며 4xx 도 다시 보냅니다. 다시 띄우면 같은 키(`Idempotency-Key` 헤더 `{작업 요청 id}/v{버전}`)와 같은 본문으로 보내 `DELIVERED` 가 됩니다. 보내던 중 운영 서비스가 멈춘 `IN_FLIGHT` 행은 30초 임대가 끝난 뒤 다시 집힙니다. 전달 성공은 수신 확인(ACK)이 아닙니다. ACK 는 `POST /integration/v1/job-orders/{id}/results/{version}/ack` 에 그 버전의 `resultHash` 를 실어야 하고 다르면 409 `RESULT_CONFLICT` 입니다. 이전 버전 ACK 는 새 버전에 번지지 않습니다.
- 다르면: 콜백 주소는 루프백 IP 리터럴 http 만 받으므로 설정을 확인합니다. 전달이 끝나도 ACK 가 없으면 상위 시스템 쪽 수신 확인을 확인합니다.
- 근거 시험: `WorkRequestsTest` 의 `로컬 callback은 실패를 재전송하고 같은 버전 키와 내용을 유지하며 ACK와 분리한다`, `ACK는 버전과 해시에 묶이고 재작업의 최신 결과는 별도로 확인한다`, `외부 결과 API는 인증과 정확한 ACK 본문을 요구한다` 입니다. `WorkRequestsTest` 의 `전달 중 멈춘 결과와 닫힌 수신처는 임대가 끝난 뒤 같은 키로 버전마다 한 번 다시 보낸다`(닫힌 수신처의 `lastError` «로컬 연결 실패 또는 응답 불명», 임대가 끝난 `IN_FLIGHT` 행 재전달, 버전마다 같은 키로 한 번, 전달 뒤에도 수신 확인 없음) 입니다.

### D1-4 실행하지 않음과 결과 불명 가르기
- 조작: 재기동 뒤 확인 필요 작업 요청마다 아래 기준으로 가릅니다.
- 확인할 곳: «작업 요청 상세» 의 상태와 `detail`, «호스트 조회»(«조회됨» 또는 «모름 · 호스트 조회 실패», 곧 `hostRead` `UNAVAILABLE` 은 못 읽음이지 없음이 아님), «마지막으로 확인한 실행 · 직전 관측», «현장 확인 이력», «재평가 판단 이력», «원본 요청·변환·제출 기록»(`submission`), 운영 → «실행» 과 «작업 응답 송신 기록», 이력 → «조작 기록» 의 같은 요청 id 행, 공유 자원의 «기록된 근거», API `GET /api/stations/runs/{id}/evidence` 입니다.
- 기대하는 결과: 실행하지 않았다고 확정되는 경우는 실행 호스트가 거부한 `REJECTED`(`detail` 에 «호스트 제출 거부: …» 가 든 것), `REJECTED` 이면서 `detail` 에 «호스트에 보내지 않았습니다» 가 든 것, 호출 계약 변환에서 거부된 `REJECTED`(예: «배포가 변경되었습니다. 배정 가능 여부를 다시 확인하세요»), `submission` 과 실행 관측이 없는 `UNASSIGNED`, 스테이션 현장 확인 해제가 «이 작업 요청은 실행하지 않습니다» 로 닫은 것입니다. 이때만 새 요청 id 로 다시 보낼 수 있습니다. 결과 불명은 `IN_DOUBT`, `DISPATCHING`, 스테이션 사유 «호출 결과 불명; 새 요청을 보내지 않고 점유 유지» 입니다. 실행 목록에 없다는 것은 증거가 아닙니다. 응답 없음 뒤 재조회한 조작 기록 행이 `CONFIRMED_NOT_APPLIED` 여도 한 번의 조회 결과일 뿐이고 작업 요청은 `IN_DOUBT` 로 남습니다.
- 다르면: 결과 불명이면 «현장 확인과 인계» 에서 «현장 관측»(«현장 상태 미확인», «현장 미착수 확인», «현장 진행 중 확인», «현장 완료 관측»)과 근거를 남깁니다(API `POST /api/work-requests/{id}/reviews`, `observation` 은 `NOT_CONFIRMED`·`NOT_STARTED`·`IN_PROGRESS`·`COMPLETED`). 이 기록은 상태를 바꾸지 않고 제출하지 않습니다. 새로 보낼지는 현장 확인 뒤 사람이 정합니다.
- 근거 시험: `WorkRequestsTest` 의 `호스트 조회 실패를 실행 없음과 구분하며 검색과 페이지 범위를 제한한다`, `접수 저장 뒤 예기치 않은 오류는 미전송 거절로 닫고 오류를 그대로 알린다`, `operator review is immutable idempotent fenced and never submits or completes work`, `StationCoordinatorTest` 의 `현장 확인 해제는 묶인 접수를 미전송 거절 또는 결과 불명으로 닫는다` 입니다. `WorkRequestsTest` 의 `결과 불명 작업 요청은 재기동한 호스트에서 다시 지은 실행이 보일 때만 수락이 되고 일지 없는 실행은 사라져도 결과 불명으로 남는다`(조작 기록 `CONFIRMED_NOT_APPLIED` 뒤에도 `IN_DOUBT`, 재평가 409 `RECOVERY_NOT_SAFE`, 현장 확인 `NOT_STARTED` 기록 뒤에도 `IN_DOUBT`) 입니다.

### D1-5 새 명령을 막는 것과 푸는 길
| 막는 것 | 보이는 곳 | 푸는 길 | 근거 시험 |
|---|---|---|---|
| 실행 일지 저장 실패 | 제출이 500 `JOURNAL_WRITE_FAILED` 입니다. `journalBlockedExecutionIds`, 운영 → «실행» 경고 «실행 일지 저장 실패로 호스트의 명령 진행과 신규 배정이 차단되었습니다», 작업 요청 상세 «일지 저장 실패로 호스트의 새 명령 진행이 차단되었습니다.», 운영 개요 «일지 저장 차단» 입니다. pump 와 신규 제출이 막히고 로봇 태스크가 시작되지 않습니다. | 실행 호스트 재기동뿐입니다. 그 작업 지시는 일지에 없어 다시 짓지 않고, 그 작업 요청은 `IN_DOUBT` 로 남습니다(차단된 실행은 수락 근거로 쓰지 않습니다). | `HostJournalTest` 의 `일지 쓰기가 실패하면 제출은 500이고 pump와 신규 제출을 차단해 로봇 태스크를 시작하지 않는다`, `JobOrderOperationsTest` 의 `일지가 없는 메모리 실행을 반영 완료로 확정하지 않는다`, `WorkRequestsTest` 의 `일지 차단과 재작업 중 이전 응답은 결과를 발행하지 않는다`, `WorkRequestsTest` 의 `결과 불명 작업 요청은 재기동한 호스트에서 다시 지은 실행이 보일 때만 수락이 되고 일지 없는 실행은 사라져도 결과 불명으로 남는다` |
| 복원 못 한 기체 | 판정 사유 «복원 못 한 실행이 있다», «미룬 실행», «포기한 실행» 입니다. | `DEFERRED` 는 스냅숏이 읽히면 pump 가 다시 짓습니다. `GAVE_UP` 은 그 작업 지시의 태스크가 끝날 때까지 기다리며, 운영자가 남은 태스크를 확인합니다. | `HostRestoreTest` 의 `기체 스냅숏을 못 읽으면 미루고 그 기체를 판정에서 빼며 pump 마다 다시 시도해 읽히면 다시 짓고 그 밖의 거부는 포기한다`, `포기한 행의 작업 지시 태스크가 사람을 기다리는 NEEDS_INTERVENTION 이면 종료 상태가 아니라 그 기체를 계속 판정에서 뺀다` |
| 현장 시간값 미적용 | 실행 호스트 재기동 직후 첫 읽기 전에는 판정이 미적용 사유를 달고 제출은 `UNASSIGNED` 입니다. | 현장 설정에 «실행 호스트 반영: 버전 N» 이 보인 뒤 새 작업 요청을 보냅니다. | `SiteTimingsHostTest` 의 `첫 읽기 전에는 판정이 기체마다 미적용 이유를 더하고 제출은 사유 없는 UNASSIGNED 다` |
| 스테이션 `REVIEW_REQUIRED` | 점유를 쥐고 다음 작업을 보내지 않습니다. | 운영자 판단(API `POST /api/stations/runs/{id}/decision`, 본문 `expectedRevision`, `decision`, `reason`)을 합니다: «재실행 없이 관측 재개»(`RESUME_OBSERVATION`, 설비 완료 신호가 켜져 있으면 409), «현장 확인 후 자원 해제»(`CONFIRM_CLEAR`, 로봇에 보낸 작업이고 작업 번호 신호 쌍이 없으면 스테이션 격리), 또는 인시던트 판단의 재작업입니다. | `StationCoordinatorTest` 의 `재평가 중 재기동은 자동 재전송 없이 현장 확인으로 넘긴다`, `재작업 전달 중 프로세스가 멈추면 재기동 뒤 확인 필요로 넘긴다` |
| 스테이션 격리 | 다음 작업이 `WAITING_READY`(«스테이션 격리; 현장 확인과 격리 해제 판단을 기다립니다»), 설비 준비 기한이 멈춥니다. | 설비가 비었고 이전 로봇이 멈췄는지 현장 확인 뒤 «현장 확인 후 격리 해제»(`CONFIRM_STATION_IDLE`)를 합니다. 격리된 스테이션을 점유한 `WAITING_READY` 작업이 아니면 409, 셀 신호를 못 읽으면 503, 완료 신호가 켜져 있으면 409 입니다. | `StationCoordinatorTest` 의 `작업 번호 반환이 없는 스테이션은 보낸 작업을 해제하면 격리하고 현장 확인 후 격리 해제 전에는 다음 작업을 보내지 않는다`, `격리 해제 판단은 격리된 스테이션을 점유한 설비 준비 대기 작업에서 운영자가 조회 버전으로 한다` |
| 이전 인스턴스의 보류 판단 | 409 `INSTANCE_MISMATCH` 입니다. | 지금 인스턴스 id 로 다시 판단합니다. | `IncidentResolveTest` 의 `판단 요청에 instanceId 가 없으면 400 이고 지금 인스턴스가 아니면 409 INSTANCE_MISMATCH 이며 판단하지 않는다`, 통합 시험 `RestartRecoveryTest` 의 단계 6 |
| 부분 완료 실행 | 판정 사유 «도는 실행이 있다: exec-N», 실행 목록 물리 상태 `PARTIAL`. 같은 작업 지시의 리비전도 `UNASSIGNED`. | 현장 확인 뒤 운영 › 실행 «실행 취소»(운영자 모드, API `POST /api/executions/{id}/cancel`, 본문 `instanceId`·`reason`). 다음 pump 에 `ABORTED` 로 정착하고 `ABORTED` 작업 응답이 나감. 이미 정착했거나 취소를 건 실행은 `NOT_CANCELABLE`, 다른 인스턴스는 409. | `MissionHostTest` 의 `부분 완료 실행의 기체는 도는 실행으로 판정과 제출에서 빠진다`, `ExecutionControlTest` 의 `빈손 부분 완료를 운영자가 취소하면 ABORTED 로 정착해 작업 응답이 나가고 표시 없이 기체가 다시 배정된다` |
| 잔여 파지 표시 | 판정 사유 «잔여 파지 확인 필요: <작업 지시 id> <단위 id>», 운영 › 배정 가능 아래 «잔여 파지 확인» 표, API `GET /api/robots/residual-holds`. 재기동해도 남음. | 로봇 손·그리퍼가 비었는지 현장에서 직접 본 뒤 «기체 빈손 확인»(운영자 모드, API `POST /api/robots/{robotId}/residual-holds/{markerId}/clear`, 본문 `reason`). 없는 표시 404 `RESIDUAL_HOLD_NOT_FOUND`, 이미 해소 409 `RESIDUAL_HOLD_ALREADY_CLEARED`. | `ExecutionControlTest` 의 `든 채 끝난 부분 완료를 취소하면 정착 때 잔여 파지 표시가 서고 사람이 해소해야 기체가 다시 배정된다`, `든 채 실패로 정착한 실행의 표시는 재기동해도 기체를 빼고 해소도 재기동 뒤까지 남으며 표시는 작업 지시마다 하나다` |

### D1 에서 확인하는 기록
| 기록 | 화면 | API | 알 수 있는 것 |
|---|---|---|---|
| 작업 요청 상태와 상세 | 작업 요청 → «작업 요청 상세», 구역 «거부 사유» | `GET /api/work-requests/{id}` | `receipt.state`·`detail`, `hostRead`, `journalBlocked`, `executionInstanceId`, `reviews`, `recoveries`, `resource`, `rejection`(호스트가 거부한 `REJECTED` 만, 그 밖 null) |
| 실행 목록과 복원 보고 | 운영 → «실행» | `GET /api/executions` | 지금 인스턴스, 다시 지은 실행과 이전 인스턴스, `journalBlockedExecutionIds`, `restore`, `executions[].cancel`(이 인스턴스에서 건 운영자 취소: 승인자, 사유, 건 때 물리 상태, 기록 시각, 없으면 null). 화면은 물리 상태 칸 뒤 «(취소 요청: <승인자>, <사유>)». |
| 잔여 파지 표시 | 운영 → «배정 가능» 아래 «잔여 파지 확인» | `GET /api/robots/residual-holds` | 미해소 표시(`markerId`, `robotId`, `jobOrderId`, `executionId`, `instanceId`, `unitId`, `objectRef`, `recordedAt`). 해소한 표시는 목록에서 빠지고 해소 기록(승인자·사유·시각)은 호스트 표 `mission.robot_residual_hold_clearing` 과 조작 기록 `CONFIRM_ROBOT_EMPTY` 행에 있음. |
| 작업 응답 송신 기록 | 운영 → «작업 응답 송신 기록» | `GET /api/job-responses?jobOrderId=` | `SENT` 와 `RESTART_DUPLICATE` 처분 |
| 결과 버전과 전달 | «외부 결과 · 수신 확인», «로컬 Mock MES 결과 전달» | `GET /api/work-requests/{id}` 의 `latestResult`·`deliveries`, `GET /integration/v1/job-orders/{id}/results/{version}` | 결과 버전, `resultHash`, 전달 상태, 수신 확인 |
| 결과 수집 상태 | 운영 개요 «결과 자동 수집» | `GET /api/work-requests/collector` | 커서 순번, 마지막 번호, 생산자 id |
| 스테이션 작업 이벤트 | 공유 자원 «기록된 근거» | `GET /api/stations/runs/{id}/evidence` | 상태 이력과 사유, 판단자 |

이 절차를 위해 더하거나 넓힌 시험: `WorkRequestsTest` 의 `결과 불명 작업 요청은 재기동한 호스트에서 다시 지은 실행이 보일 때만 수락이 되고 일지 없는 실행은 사라져도 결과 불명으로 남는다`, `전달 중 멈춘 결과와 닫힌 수신처는 임대가 끝난 뒤 같은 키로 버전마다 한 번 다시 보낸다`, 그리고 넓힌 `HostRestoreTest` 의 `운영자 보류에 선 실행을 두 번 다시 띄워도 새 인스턴스의 첫 보류 응답은 재기동 중복이고 같은 인스턴스의 다음 보류는 송신이다`(결과 피드에서 재기동 중복 제외 단언 추가).
