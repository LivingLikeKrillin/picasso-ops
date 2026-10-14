# 운영 보장 명세

이 문서는 외부 검토 요청에 따라 picasso-ops 가 현재 무엇을 보장하고 무엇을 보장하지 않는지, 그리고 해당 보장이 어떠한 배포 전제에서만 성립하는지를 한곳에 모아 설명합니다. 이 문서의 독자는 시연이나 배포를 준비하는 담당자와 검토자입니다. 본 문서는 기준 커밋 `511d5bc` 를 바탕으로 작성되었으며, 뒤이어 릴리스 조합, 배포 전제, 보장하는 것, 보장하지 않는 것, 더 읽을 곳을 차례로 다룹니다.

이 문서는 기준 커밋 `511d5bc` 로 작성되었고 그 뒤 작업 번호 신호와 스테이션 격리 변경(PR #26)이 있었으며, 공유 스테이션 관련 항목(공유 스테이션 해제 조건, 늦은 완료 신호 차단 항목, 현장 확인 해제 항목, 설비 완료 신호의 인과 항목)과 그 시험 이름은 이 변경을 반영합니다. 그 뒤 운영자 실행 취소와 잔여 파지 표시 변경(PR #29)이 서브모듈을 `79e074d`로 옮겼고, «부분 완료·잔여 파지 기체의 배정 제외» 항목, «현장 확인 후 해제의 실행 중단 미보장» 항목, «잔여 파지 표시의 범위» 항목과 릴리스 조합의 picasso 행이 이 변경을 반영합니다.

## 릴리스 조합

이 문서가 전제하는 저장소 커밋과 데이터 규격의 기준 조합은 다음과 같습니다.

| 저장소 | 커밋 | 이 조합에서의 역할 |
|---|---|---|
| picasso-ops | `511d5bc` (main, PR #24 머지, 2026-10-13) | 이 문서가 설명하는 운영 계층 |
| picasso(서브모듈) | `79e074d` (PR #91 머지, 2026-10-13) | 실행 호스트가 쓰는 미들웨어 라이브러리. 실행 호스트가 #91의 `PhysicalState.occupiesRobot`·`pumps`를 씀 |
| picasso-narrator | `130fe67` (main) | 설명 계층. PR #1(`ops.station.evidence/1` 읽기, `receiver/station.py`)이 머지되어 있고(머지 커밋 `391af47`) 자체 CI(`.github/workflows/ci.yml`)가 `130fe67` 에서 통과함 |
| picasso 내보내기 | 버전 6 | picasso 가 narrator 에 넘기는 내보내기 번들의 `manifest.schemaVersion` 은 `"6"` 입니다(`74e4d3d`·`79e074d`의 `handoff/narrator/run-1/manifest.json`). narrator 의 `BOUNDARY.md` 는 `"5"` 와 `"6"` 만 받고 모르는 버전이면 멈춥니다 |

시연이나 배포를 할 때는 이 네 값을 함께 밝혀야 합니다. 어느 하나라도 다르면 이 문서의 보장과 시험 기록은 그 조합에 대해 확인된 것이 아닙니다. narrator 의 설명은 Ops 상태를 바꾸지 않고(`explanationMayChangeState: false`), Ops 화면과 자동으로 이어져 있지 않습니다.

## 배포 전제

이 문서에서 설명하는 보장은 다음 배포 전제에서만 유효합니다.

- 운영 서비스 한 프로세스: 같은 DB 에 활성 운영 서비스는 하나여야 합니다. 스테이션 조정기의 `tick` 과 `rework` 는 JVM 의 `@Synchronized` 와 DB 행 잠금·advisory 잠금으로 직렬화됩니다. `tick` 이 `RETRYING`·`REWORKING` 상태를 만나면 앞 프로세스가 멈췄다고 읽고 `REVIEW_REQUIRED` 로 넘기는데, 이 판단은 조정기가 하나일 때만 맞습니다. 같은 DB 에 운영 서비스 둘을 띄우는 구성은 지원하지 않고 검증하지도 않았습니다. (코드: `ops-service/src/main/kotlin/dev/picasso/ops/service/stations/StationCoordinator.kt`)
- 격리된 PoC 망: 운영 서비스, 실행 호스트, registry, 셀 대역은 `127.0.0.1` 에만 바인딩되고 mimic gRPC 는 모든 인터페이스에 열립니다. 실행 호스트 REST 와 셀 대역에는 인증이 없습니다. «이 기계 밖에서 쓰지 않는다»는 `.env` 첫 줄 주석의 전제를 따릅니다.
- 모드 헤더는 인증이 아님: 화면 API 의 `X-Ops-Mode`·`X-Ops-User` 헤더는 역할과 사용자 이름을 고르는 값입니다. API 에 닿을 수 있는 사람은 누구나 이 헤더를 붙일 수 있습니다.
- 외부 주문 토큰 하나: 외부 주문 API 는 공유 Bearer 토큰 하나(`PICASSO_INTEGRATION_TOKEN`)와 설정 출처 하나로 상위 시스템을 식별합니다. 테넌트별 권한 체계(IAM)가 아닙니다. 토큰이 비면 API 가 꺼집니다.
- 콜백 재시도에 끝이 없음: 결과 콜백 전달은 시도 수 상한이나 영구 실패 분류 없이 계속 다시 보냅니다. 다음 시도까지의 대기는 2의 min(시도 수, 8)제곱 초라 최대 256초입니다(코드에는 300초 상한이 있지만 닿지 않음). 4xx 응답도 다시 보냅니다. 콜백 주소는 루프백 IP 리터럴 http 만 받습니다. (코드: `ops-service/src/main/kotlin/dev/picasso/ops/service/requests/WorkResultDelivery.kt`)

## 보장하는 것

아래 보장은 «배포 전제»를 만족할 때만 성립하며, 시험 메서드 이름은 그 보장을 지키는 단언이 있는 곳입니다. 코드 경로 접두 `ops-service/src/main/kotlin/dev/picasso/ops/service/`는 여기서 밝히고 아래 코드 항목에서는 `requests/WorkRequests.kt`처럼 줄여 씁니다. 시험 위치는 운영 서비스 시험이 `ops-service/src/test/kotlin/dev/picasso/ops/service/`, 통합 시험이 `e2e/src/test/kotlin/dev/picasso/ops/e2e/`, 화면 시험이 `ui/e2e/`입니다.

### 작업 요청 선행 저장

- 동작: 외부 주문, 호출 계약 호출, 화면 요청은 실행 호스트를 부르기 전에 `ops.work_request` 행을 독립 트랜잭션으로 커밋합니다. `(source_system_id, external_order_id)` 가 유일하고, 같은 키에 같은 본문(키 정렬 JSON 의 SHA-256)이면 기존 작업 요청을 돌려주며 다시 제출하지 않고, 다른 본문이면 409 `ORDER_CONFLICT` 입니다.
- 코드: `requests/WorkRequests.kt` 의 `receivePrepared`, `ops-service/src/main/resources/db/ops/V6__work_requests.sql` 의 유일 제약
- 시험: `WorkRequestsTest` 의 `같은 주문은 동일 접수와 내부 ID를 반환하고 저장소를 다시 만들어도 재전송하지 않는다`, `JSON 키 순서는 무관하고 다른 입력은 409이며 출처별 키는 독립적이다`, `동시 중복 접수는 호스트를 한 번만 부른다`, `접수 저장 실패 시 호스트는 호출하지 않는다`, 화면 시험 `lifecycle.spec.ts` 의 외부 주문 단계(토큰 없이 401, 새 외부 주문 202, 재전송 200, 다른 입력 409)

### 불확실한 결과 자동 재제출 방지

- 동작: 제출 결과를 모르면 `IN_DOUBT` 로 두고 재전송이나 재평가로 다시 보내지 않습니다. 미배정 재평가는 제출 기록과 실행 관측이 없는 `UNASSIGNED` 만 받습니다. 호스트가 그 작업 지시를 가지고 있음이 관측되면(결과 피드 이벤트나 실행 목록) `ACCEPTED` 로 올립니다. 호스트가 출발 미관측으로 답한 `UNASSIGNED`(호스트가 보내지 않았다고 답한 것)는 운영자가 출발 자리 확인을 기록할 때만 같은 작업 지시 id 로 다시 보냅니다. 시간이 지나거나 재조회해서는 다시 보내지 않습니다.
- 코드: `requests/WorkRequests.kt` 의 `dispatch`, `recover`, `collectEvents`, `detail`, `confirmSource`
- 시험: `WorkRequestsTest` 의 `응답 불명과 제출 중단 상태는 재접수로 다시 실행하지 않는다`, `전송 불명은 실행 관측으로 확정하고 호스트 단절 후에도 직전 관측을 보존한다`, `불명 접수와 오래된 상태는 재평가할 수 없다`, `출발 미관측이 아닌 자리와 낡은 조회와 운영자가 아닌 행위자의 확인은 기록하지 않고 다시 제출하지 않는다`, `자원 관리 작업의 출발 미관측은 자동 재평가 없이 점유를 유지하고 출발 자리 확인으로만 다시 제출한다`, `StationCoordinatorTest` 의 `응답 불명은 운영자 확인 전 해제나 재전송하지 않는다`, 화면 시험 `lifecycle.spec.ts` 의 결과 불명 단계(연결 단절, 운영 서비스와 실행 호스트를 재기동한 뒤에도 호스트 제출 수가 1 만 늘어 있음)

### 멈춘 작업 요청 정리

- 동작: `ops.requests.stale-seconds`(기본 300초)보다 오래 멈춘 `RECEIVED` 는 호스트에 보내지 않은 것이 확실하므로 `REJECTED` 로, `DISPATCHING` 은 보냈는지 모르므로 `IN_DOUBT` 로 닫습니다. 결과 수집 주기마다 수집보다 먼저 돌고, `WAITING_RESOURCE` 는 건드리지 않습니다.
- 코드: `requests/WorkRequests.kt` 의 `sweepStale`, `requests/WorkResultCollector.kt` 의 `collect`
- 시험: `WorkRequestsTest` 의 `오래 멈춘 접수 전 단계와 제출 중 단계만 정리하고 자원 대기는 두지 않는다`, `정리가 먼저 닫은 접수는 이어서 제출하거나 대기열에 넣지 않는다`, `결과 수집 주기는 정리 실패와 무관하게 결과를 모은다`, `접수 저장 뒤 예기치 않은 오류는 미전송 거절로 닫고 오류를 그대로 알린다`

### 작업 요청 시점 배포 고정

- 동작: 변환한 작업 지시에 받은 시점의 배포 버전과 임무 버전을 넣어 저장합니다. 대기열에서 나와 제출할 때는 저장본을 그대로 쓰고, 미배정 재평가는 다시 변환한 결과가 저장본과 다르면 409 `RECOVERY_INPUT_CHANGED` 로 막습니다. 실행 호스트는 작업 지시의 `missionVersion` 으로 임무 버전을 고릅니다.
- 코드: `joborders/JobOrderForm.kt` 의 `jobOrder`, `requests/WorkRequests.kt` 의 `dispatchQueued`, `recover`, `mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt`
- 시험: `MissionInterfacesTest` 의 `배포 이력은 불변이고 새 배포는 새 요청에만 적용된다`, 통합 시험 `ManufacturingScenarioTest`, `WorkRequestsTest` 의 `자원 준비 후 원래 접수와 고정 변환으로 한 번만 실행한다`, 실행 호스트 시험(`mission-host/src/test/kotlin/dev/picasso/ops/host/`) `MissionVersionsTest` 의 `배포 고정은 활성 버전 변경 뒤에도 원래 미션으로 실행하고 글로벌 활성 버전을 바꾸지 않는다`, `WorkRequestsTest` 의 `배포가 바뀐 뒤 미배정 재평가는 원래 배포로 제출하고 변환이 달라지면 RECOVERY_INPUT_CHANGED 로 막는다`

### 결과 버전 관리와 수신 확인

- 동작: 실행 호스트는 송신 처분 작업 응답을 빈 번호 없는 순번으로 outbox 에 남기고, 운영 서비스는 커서로 이어 읽어 작업 요청마다 결과 버전을 덧붙입니다. 한 쪽 안에서 하나라도 틀리면 그 쪽 전체를 저장하지 않습니다. 결과 버전은 코드가 덧붙이기만 하고 `acknowledged_at` 만 한 번 채웁니다. 이것은 DB 트리거가 아니라 코드 관례입니다. 수신 확인은 버전과 `resultHash` 가 모두 맞아야 하고 다르면 409 `RESULT_CONFLICT` 입니다. 콜백 HTTP 성공은 수신 확인이 아닙니다.
- 코드: `mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostRecords.kt` 의 `appendResult`, `requests/WorkRequests.kt` 의 `collectEvents`, `captureResult`, `acknowledge`, `requests/WorkResultDelivery.kt`
- 시험: `WorkRequestsTest` 의 `결과 재조회는 같은 불변 버전이며 호스트 단절과 저장소 재생성 후에도 조회한다`, `ACK는 버전과 해시에 묶이고 재작업의 최신 결과는 별도로 확인한다`, `외부 결과 API는 인증과 정확한 ACK 본문을 요구한다`, `durable cursor resumes after restart and never lets detail reorder backlog`, `invalid event and database failure roll back the whole page and cursor`, `concurrent durable collectors commit one set of versions`, `로컬 callback은 실패를 재전송하고 같은 버전 키와 내용을 유지하며 ACK와 분리한다`

### 공유 스테이션 해제 조건

- 동작: 로봇에 보낸 뒤의 스테이션 작업은 자동으로는 `COMPLETED` 가 될 때만 점유를 풉니다. 그 밖의 자동 해제는 로봇에 보내기 전의 준비 기한 초과(`TIMED_OUT`)와 제출 거부(`REJECTED`)뿐이고, 운영자의 현장 확인 후 해제(`MANUALLY_CLEARED`)는 물리 완료로 기록하지 않습니다. 새 완료 판정은 세 조건을 모두 만족해야 합니다. 첫째, 저장된 최신 결과의 물리 상태가 `PHYSICALLY_DONE` 이고 그 결과가 `RUNNING` 진입 때 적은 기준선(`result_baseline`)보다 새 버전이어야 합니다(관측 재개는 같은 실행을 다시 보므로 예외입니다). 둘째, 처리 주기마다 셀 신호를 읽은 시각(운영 서비스 시계)이 그 스테이션 작업의 `RUNNING` 진입 시각 `phase_at`(운영 서비스 시계)과 같거나 뒤인 읽기여야 하며 설비 완료 신호가 true 여야 합니다(신호의 `observedAt` 은 비교하지 않습니다). 셋째, 작업 번호 신호 쌍이 있는 스테이션은 같은 읽기의 반환 번호가 이번 작업 번호여야 합니다. 그리고 `RUNNING` 진입 길(처음 전송 전 준비 확인, 전송 전 미배정 재평가, 관측 재개, 재작업 반영, 출발 자리 확인 뒤 재제출)은 모두 그 직전에 완료 신호 false 를 요구합니다.
- 코드: `stations/StationCoordinator.kt` 의 `tick`, `observe`, `transition`, `confirmSource`, `ops-service/src/main/resources/db/ops/V16__station_result_baseline.sql`
- 시험: `StationCoordinatorTest` 의 `자원 점유는 직렬화되고 로봇 완료와 새로운 설비 완료 근거가 모두 필요하다`, `이전 완료 true와 로봇 수락만으로 점유를 풀지 않는다`, `처음 실행은 전송 전에 저장된 결과를 판단에 쓰지 않는다`, `재작업은 재작업 전에 저장된 물리 완료를 완료 근거로 쓰지 않는다`, `관측 재개는 같은 실행을 다시 보므로 확인 대기 중 저장된 물리 완료를 새 완료 신호와 함께 인정한다`, `현장 시계가 운영 시계보다 늦어도 전송 뒤 새로 켜진 완료 신호로 완료한다`, `실행 진입 전에 읽은 신호는 그 실행의 완료 근거로 쓰지 않는다`, `전송 전 미배정 재평가도 설비 완료 신호가 꺼져 있어야 실행으로 들어간다`, `WorkRequestsTest` 의 `공통 접수도 로봇 완료와 새 설비 완료를 모두 확인한 뒤 다음 요청을 허용한다`, `자원 관리 작업의 출발 미관측은 자동 재평가 없이 점유를 유지하고 출발 자리 확인으로만 다시 제출한다`(완료 신호가 true 면 409 이고 상태 그대로임을 확인), 통합 시험 `StationScenarioTest`, 화면 시험 `station.spec.ts`

### 해제한 이전 작업의 늦은 완료 신호 차단

- 동작: 작업 번호 신호 쌍(선택, TEXT 둘)이 있는 스테이션은 등록 칸 `jobSignal`(송신)과 `jobEchoSignal`(반환)을 씁니다. 운영 서비스가 로봇에 보내기 전에 스테이션 작업 id 를 작업 번호로 `jobSignal` 에 씁니다(운영 서비스에서 실행 호스트를 거쳐 현장으로 기록). 쓰기에 실패하면 로봇에 보내지 않고 `WAITING_READY` 에 남아 «작업 번호 쓰기 실패; 설비 신호 기록을 기다립니다» 사유로 다음 주기에 다시 씁니다. 설비는 받은 번호를 반환 신호에 쓴 뒤 완료 신호를 올립니다. 완료는 같은 읽기에서 완료 신호 true 이고 반환 번호가 이번 번호와 같아야 합니다. 완료 신호 true 인데 반환 번호가 다르면 «설비 완료 신호 수신, 작업 번호 불일치; 현장 확인이 필요합니다» 로 `REVIEW_REQUIRED` 로 전이하고 점유를 유지하며 근거에 `expected`, `echoed`, `signals` 를 남깁니다. 작업 번호 신호 쌍이 없는 스테이션은 로봇에 보낸 적이 있는 스테이션 작업(응답 불명 포함)을 운영자가 `CONFIRM_CLEAR` 로 해제하면 스테이션을 격리합니다(V17 의 `rf_station.quarantined_by`, `quarantined_at`). 다음 스테이션 작업은 점유해도 로봇에 보내지 않고 `WAITING_READY`(«스테이션 격리; 현장 확인과 격리 해제 판단을 기다립니다»)에 두며 이때 설비 준비 단계 기한은 멈춥니다. 운영자 판단 `CONFIRM_STATION_IDLE`(사유, 조회 버전 `expectedRevision`)이 격리를 풉니다. 격리된 스테이션을 점유한 `WAITING_READY` 작업이 아니면 409, 셀 신호를 읽지 못하면 503, 완료 신호가 켜져 있으면 409 를 반환합니다. 격리를 풀면 이력에 «현장 확인 후 격리 해제: {사유}» 를 남기고 단계 기한은 그 판단부터 다시 셉니다. 보내지 않은 작업의 종료(취소, 전송 전 시간 초과, 제출 거부)와 정상 완료는 격리하지 않습니다. `CONFIRM_CLEAR` 은 이제 그 시점의 셀 신호, 해제한 작업의 작업 요청 id, 저장된 최신 물리 상태와 결과 버전, 격리 여부를 이력 근거에 남깁니다. 연동 규격은 [공유 스테이션 설비 연동 요구 사양](station-equipment-requirements.md)을 따릅니다.
- 코드: `stations/StationCoordinator.kt` 의 `numbered`, `observe`, `decide`, `stations/StationPort.kt` 의 `OpsStationPort.read`, `OpsStationPort.write`, `ops-service/src/main/resources/db/ops/V17__station_job_number_quarantine.sql`
- 시험: `StationCoordinatorTest` 의 `작업 번호 송신·반환 신호는 둘 다 연결하거나 둘 다 비우고 현장에 선언된 TEXT 신호여야 한다`, `작업 번호 반환 스테이션은 해제한 이전 작업의 늦은 완료 신호를 다음 작업의 완료로 채택하지 않고 확인 필요로 넘긴다`, `작업 번호 반환 스테이션은 반환 번호가 이전 값이면 로봇 완료 뒤에 온 완료 신호도 채택하지 않는다`, `작업 번호 반환 스테이션은 이번 작업 번호와 완료 신호와 로봇 완료가 모두 있어야 완료한다`, `작업 번호 불일치로 확인 필요가 된 작업은 신호 초기화 뒤 관측을 재개하고 이번 번호로 완료한다`, `정상 완료한 이전 작업의 완료 신호를 설비가 다시 써도 반환 번호가 다르면 다음 작업의 완료로 채택하지 않는다`, `작업 번호 쓰기에 실패하면 로봇을 보내지 않고 설비 준비 대기에 남아 다음 처리에서 다시 쓴다`, `작업 번호 쓰기 실패가 이어져도 설비 준비 기한은 그대로라 전송 전에 시간 초과로 해제한다`, `작업 번호 반환이 없는 스테이션은 보낸 작업을 해제하면 격리하고 현장 확인 후 격리 해제 전에는 다음 작업을 보내지 않는다`, `격리 중 설비 준비 기한은 멈추고 격리 해제 판단부터 다시 센다`, `응답 불명으로 해제한 작업도 호스트에 닿았을 수 있어 스테이션을 격리한다`, `보내지 않은 작업의 종료와 계약 거부는 스테이션을 격리하지 않는다`, `격리 해제 판단은 격리된 스테이션을 점유한 설비 준비 대기 작업에서 운영자가 조회 버전으로 한다`, 통합 시험 `StationLateCompletionTest` 의 두 메서드(작업 번호 반환 스테이션 변형과 반환 없는 스테이션의 격리 변형)

### 필수 자원 우회 차단

- 동작: 임무에 필수 자원이 있으면 Ops 의 모든 작업 요청 경로(외부 주문, 호출 계약 호출, 화면 수동 요청, 기존 `POST /api/job-orders`, 스테이션 직접 요청, 미배정 재평가, 출발 자리 확인)가 같은 스테이션 대기열과 점유 확인을 지납니다. 제출 직전에도 권한을 다시 확인하고, 거부되면 보내지 않고 `REJECTED` 로 닫습니다. 재작업(`REWORK`)은 그 스테이션 작업이 점유자이고 `REVIEW_REQUIRED` 일 때만 넘기고, 실행 목록을 못 읽으면 막습니다. 필수 자원이 있는 작업 요청의 출발 자리 확인은 일반 작업 요청 API(`POST /api/work-requests/{id}/source-confirmations`)가 409 `RESOURCE_MANAGED` 로 막고, 스테이션 작업 경로(`POST /api/stations/runs/{id}/source-confirmations`)로만 받습니다.
- 코드: `stations/MissionResources.kt` 의 `admit`, `authorize`, `requests/WorkRequests.kt` 의 `dispatch`, `recover`, `confirmSource`, `stations/StationPort.kt` 의 `OpsStationPort.validate`, `StationReworkGuard`, `stations/StationCoordinator.kt` 의 `rework`, `confirmSource`
- 시험: `WorkRequestsTest` 의 `상위 주문 계약 호출 수동 직접 제출은 하나의 필수 자원 대기열을 사용한다`, `필수 자원 미배정은 일반 재평가 API로 우회하지 못한다`, `서로 다른 계약과 예전 배포도 같은 미션 자원 요구를 생략하지 못한다`, `등록 전 미배정 접수는 등록 뒤 일반 재평가로 자원 요구를 우회하지 못한다`, `제출 직전 자원 요구가 바뀌면 호스트에 보내지 않고 거절로 닫으며 오류를 알린다`, `자원 관리 작업의 출발 미관측은 자동 재평가 없이 점유를 유지하고 출발 자리 확인으로만 다시 제출한다`, `StationCoordinatorTest` 의 `해제 뒤 다른 작업이 점유한 스테이션에서는 이전 작업의 재작업을 호스트에 넘기지 않는다`, `점유 중이어도 확인 필요 상태가 아니면 재작업을 넘기지 않는다`, `확인 필요 상태여도 스테이션 점유가 없으면 재작업을 넘기지 않는다`, `실행 목록을 못 읽거나 실행이 없으면 재작업을 넘기지 않는다`, 통합 시험 `MixedResourceScenarioTest`

### 임무 잠금으로 등록과 재평가 직렬화

- 동작: 자원 요구 등록, 작업 요청의 대기열 입장, 미배정 재평가가 같은 임무 advisory 잠금(`pg_advisory_xact_lock(hashtext(임무 id))`)을 먼저 잡습니다. 그래서 재평가의 권한 확인과 `DISPATCHING` 커밋 사이에 자원 요구 등록이 끼어들 수 없습니다. 잠금 순서는 임무, 스테이션, 작업 요청 행입니다. 출발 자리 확인도 같은 임무 advisory 잠금을 먼저 잡고 그 뒤 작업 요청 행을 잠급니다.
- 코드: `stations/MissionResources.kt` 의 `lockMission`, `create`, `admit`, `requests/WorkRequests.kt` 의 `recover`, `confirmSource`
- 시험: `WorkRequestsTest` 의 `재평가 승인과 커밋 사이의 자원 요구 등록은 재평가 커밋을 기다리고 접수를 제출 중에 남기지 않는다`, `자원 요구는 운영자가 바꾸지 못하고 기존 불명 작업 중에는 등록하지 못한다`, `자원 요구 등록은 이 미션에서 아직 진행 중이거나 완료 근거가 없는 접수만 막는다`

### 부분 완료·잔여 파지 기체의 배정 제외

- 동작: 실행 호스트 판정의 도는 실행은 picasso `PhysicalState.occupiesRobot` 이라 부분 완료(`PARTIAL`) 실행의 기체에 새 작업 지시(같은 작업 지시 id 의 리비전 포함)를 배정하지 않습니다. 부분 완료는 운영자 실행 취소로 닫으며, 취소 행을 먼저 적고 미들웨어를 부르고 다음 pump 가 `ABORTED` 로 정착시킵니다. 정착할 때 마지막 로봇 단위(계획 순서)의 파지 관측이 `HOLDING` 이면 같은 트랜잭션에서 잔여 파지 표시를 적고, 사람이 빈손을 확인해 해소할 때까지 그 기체를 뺍니다. 표시는 재기동 뒤에도 DB 에서 다시 읽습니다. 취소와 해소의 승인자는 사람뿐입니다(DB `CHECK`). 이 표시는 실행 호스트의 운영 정책(ADR 41)이고 picasso 보장이 아닙니다(picasso 는 정착한 실행의 파지를 판단이 아니라 정보로 둡니다, §15.146).
- 코드: `mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt` 의 `judge`, `cancel`, `clearResidualHold`, `recordAfterPump`, `residualHoldUnit`, `restore`, `mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostRecords.kt` 의 `cancel`, `markResidualHold`, `openResidualHolds`, `clearResidualHold`, `mission-host/src/main/resources/db/mission/V5__execution_cancel_residual_hold.sql`, `mission-host/src/main/kotlin/dev/picasso/ops/host/web/ExecutionControlController.kt`, 운영 서비스 `executions/ExecutionControls.kt`, `web/ExecutionControlController.kt`
- 시험: 실행 호스트 시험 `MissionHostTest` 의 `부분 완료 실행의 기체는 도는 실행으로 판정과 제출에서 빠진다`, `ExecutionControlTest` 의 `빈손 부분 완료를 운영자가 취소하면 ABORTED 로 정착해 작업 응답이 나가고 표시 없이 기체가 다시 배정된다`, `든 채 끝난 부분 완료를 취소하면 정착 때 잔여 파지 표시가 서고 사람이 해소해야 기체가 다시 배정된다`, `든 채 실패로 정착한 실행의 표시는 재기동해도 기체를 빼고 해소도 재기동 뒤까지 남으며 표시는 작업 지시마다 하나다`, `잔여 파지 단위는 로봇 단위의 마지막 파지 관측이고 플릿 단위의 HOLDING 은 보지 않는다`, `MissionStoreTest` 의 `P0-b2 표 셋도 덧붙이기만 하고 승인자는 사람뿐이며 표시는 작업 지시마다 하나고 해소는 표시마다 하나다`, 운영 서비스 시험 `ExecutionControlsTest` 의 `취소는 승인자가 행위자이고 CANCELED 는 성공 그 밖의 결과와 호스트 4xx 는 거부로 남는다`, `빈손 확인은 승인자가 행위자이고 해소는 성공 404 409 는 거부이며 응답이 없으면 미해소 목록으로 대조한다`, 통합 시험 `FaultIncidentTest` 의 `단계 1 데이터 정의 버전으로 도는 pick_place 에 스킬 실패를 넣으면 단위가 FAILED 이고 GRASP_FAILED 인시던트가 두 버전과 함께 보인다`, Playwright `ui/e2e/lifecycle.spec.ts`

### 출발 미관측과 출발 결품의 분리

- 동작: 출발 미관측은 출발 결품과 접히지 않습니다. 실행 호스트, 운영 서비스, 화면 모두 칸과 문구가 따로입니다. 사람의 출발 자리 확인은 셀 관측이 모자란 자리(설비가 말이 없거나 «있다» 고만 한 자리)만 채우고 설비의 관측을 뒤집지 않습니다.
- 코드: `mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt` 의 `CellBandSignals.observe`, `mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt` 의 `SourceUnobservedView`, `host/HostClient.kt` 의 `HostSourceUnobserved`, `requests/WorkRequests.kt` 의 `pendingSources`, `ui/src/labels.ts` 의 `sourceUnobservedLabel`
- 시험: 실행 호스트 시험 `SourceObservationHostTest` 의 `사람의 출발 자리 확인을 실은 제출은 그 한 번만 셀 관측이 되어 관문을 지나고 다음 제출은 다시 설비만 본다`, `확인한 신원이 다르면 결품이고 설비가 비었다고 말하면 사람의 확인이 그 관측을 뒤집지 않는다`, `WorkRequestsTest` 의 `호스트 미배정의 출발 미관측은 결품과 따로 제출 기록과 미배정 칸에 남고 확인을 기다리는 출발 자리로 보인다`, 화면 시험 `WorkRequests.test.tsx` 의 `출발 미관측은 결품과 다른 확인 대기로 보이고 사람의 출발 자리 확인을 기록하면 같은 작업 요청을 다시 낸다`

## 보장하지 않는 것

picasso-ops 가 보장하지 않는 항목은 다음과 같습니다.

### 물리적 정확히 한 번

중복 방지는 작업 요청의 중복 방지이지 로봇 동작의 중복 방지가 아닙니다. 실행 호스트 재기동 복원은 같은 태스크 id 로 다시 붙지만, 로봇 쪽 태스크 기록의 지속성은 별개라 런처와 어댑터까지 재기동한 경우 같은 태스크 id 의 중복 물리 실행을 막는다고 보장하지 않습니다. 재작업(`REWORK`)은 로봇을 다시 움직이는 판단입니다. 운영자의 현장 확인 후 해제는 물리 완료 판정이 아닙니다.

### 안전

Ops 의 보류, 재작업, 현장 확인 후 해제, 격리 해제 판단과 스테이션이 쓰는 일반 설비 신호는 인증된 안전 정지나 안전 PLC, 장비 쪽 인터록을 대신하지 않습니다. 스테이션 준비·완료 신호와 작업 번호 송신·반환 신호에는 안전 신호를 연결할 수 없고(작업 번호 신호도 안전 아닌 TEXT 신호여야 등록되고 등록 시 거부), 격리 해제 판단 역시 안전 기능이 아닙니다. 셀 대역은 안전 신호 `guard_closed` 쓰기를 거부합니다. 실행 호스트를 직접 부르는 길은 Ops 의 자원 조정을 지나지 않습니다.

### 실제 하드웨어와 상용 연계

로봇은 picasso mimic(에뮬레이터), 설비는 셀 대역, 상위 시스템은 로컬 Mock MES 하나입니다. 실제 하드웨어, 실제 PLC, 상용 MES·WMS 연계는 검증하지 않았습니다.

### 여러 인스턴스

같은 DB 에 운영 서비스 여럿을 띄우는 구성은 지원하지 않고 검증하지 않았습니다(«배포 전제» 참고). 결과 수집 커서와 콜백 전달에는 동시 실행을 견디는 장치가 일부 있지만 스테이션 조정은 프로세스 하나를 전제합니다.

### 설비 완료 신호의 인과

완료 신호는 값과 마지막으로 쓰인 시각뿐이라 어느 작업 때문에 켜졌는지 증명하지 못합니다. 외부 검토 실험 A1 에서는 스테이션 작업 X 를 보냈고 로봇이 끝나지 않아 실행 기한 초과로 `REVIEW_REQUIRED` 가 되었으며 운영자가 `CONFIRM_CLEAR` 로 해제했습니다. 이 해제는 실행 호스트에서 X 의 실행을 멈추지 않습니다. 다음 스테이션 작업 Y 를 보낸 뒤 X 쪽 설비가 늦게 완료 신호를 올리면, 고치기 전에는 Y 의 완료로 받아들였습니다. 단위 시험과 실제 스택(mimic, 셀 대역, 실행 호스트, 운영 서비스)에서 재현했습니다. 고치기 전 완료 규칙은 현장 시계의 `observedAt` 과 운영 서비스 시계의 `dispatched_at` 을 비교했습니다. 작업 번호 신호 쌍이 있는 스테이션은 반환 번호로 막습니다. 쌍이 없는 스테이션은 보낸 작업의 현장 확인 해제 때 격리로 막습니다. 다만 쌍이 없는 스테이션에서 X 가 정상 완료한 뒤 X 쪽 설비가 완료 신호를 다시 true 로 쓰면, 그 사이 실행 중인 Y 의 로봇이 물리 완료일 때 Y 의 완료로 받아들일 수 있는 한계가 남습니다. 정상 완료는 격리하지 않으므로 격리도 생기지 않습니다. 둘을 가를 수 있는 것은 작업 번호 신호 쌍뿐입니다. 시험 `정상 완료는 격리하지 않고 이전 신호가 초기화되기 전에는 다음 작업을 보내지 않는다` 가 이 한계를 단언으로 적어 둡니다. 설비 연동 요구 사양은 [공유 스테이션 설비 연동 요구 사양](station-equipment-requirements.md)을 따릅니다.

### 현장 확인 후 해제의 실행 중단 미보장

`CONFIRM_CLEAR` 은 Ops 의 스테이션 점유만 풀고 묶인 작업 요청을 닫을 뿐, 실행 호스트의 실행이나 로봇 동작을 멈추지 않고 취소를 부르지도 않습니다. 해제한 작업의 로봇과 설비는 계속 움직이거나 늦게 신호를 쓸 수 있습니다. 그래서 작업 번호 신호 쌍이 없는 스테이션은 격리하고, 운영자가 설비가 비었고 이전 로봇이 멈췄는지 현장에서 확인해야 합니다. 실행을 멈추는 길은 이제 따로 있습니다: 운영 › 실행의 운영자 실행 취소(`CANCEL_EXECUTION`, `POST /api/executions/{id}/cancel`). 두 조작은 묶여 있지 않으므로 해제한 작업의 실행을 멈추려면 사람이 따로 취소해야 합니다. 취소도 다음 pump 가 하류를 읽고 `ABORTED` 로 확정하며 로봇의 물리 정지를 보장하는 안전 기능이 아닙니다.

### 결과 버전의 DB 불변

`ops.work_result` 의 불변은 코드 관례이고 DB 트리거로 막지 않습니다. DB 를 직접 고치는 경로는 막지 못합니다.

### 잔여 파지 표시의 범위

- 표시는 시각 순서가 아니라 계획 순서에서 파지 관측이 있는 마지막 로봇 단위의 관측으로 정합니다. 플릿 단위는 보지 않습니다. 관측이 «든 채» 가 아니면 실제로 들고 있어도 표시가 서지 않습니다.
- 부분 완료(`PARTIAL`)는 정착으로 적지 않으므로 그동안은 표시가 아니라 도는 실행으로 뺍니다. 표시는 정착할 때만 섭니다.
- 운영자 취소를 적은 뒤 다음 pump 가 반영하기 전에 실행 호스트가 재기동하면 다시 지은 실행(새 실행 id)에 취소를 다시 걸지 않습니다. 취소 행은 기록으로만 남습니다.
- 화면의 현장 확인 체크박스는 서버가 집행하지 않습니다. 빈손 확인은 사람이 현장에서 본 것을 적는 기록이고 로봇 센서로 확인하지 않습니다.
- 스킬이 취소를 받지 않으면(`CANCEL_UNSUPPORTED`) 미들웨어는 도는 단위를 끝까지 두고 다음 경계에서 멈춥니다. 호스트는 그 거부를 읽지 않아 화면에 보이지 않습니다.

## 더 읽을 곳

- [상세 참고 문서: 작업 요청과 결과 회신](reference.md#작업-요청과-결과-회신): 작업 요청 상태, 중복 방지, 정리, 결과 버전과 콜백
- [상세 참고 문서: 공유 스테이션 조정](reference.md#공유-스테이션-조정): 스테이션 작업 상태, 운영자 판단, 재작업 관문, 기준선
- [공유 스테이션 설비 연동 요구 사양](station-equipment-requirements.md): 설비가 내야 하는 준비·완료 신호, 작업 번호 신호 쌍 절차, 격리, 표준 근거를 설명합니다.
- [상세 참고 문서: 데이터와 재기동](reference.md#데이터와-재기동): 실행 호스트 복원과 재기동 중복
- [운영 리허설 절차](runbook.md): 운영 리허설 절차 둘(C1 운영 중 호출 계약 배포 변경과 되돌림, D1 실행 호스트·운영 서비스 재기동과 재개), 단계마다 확인할 곳, 기대하는 결과, 다를 때 할 일, 근거 시험을 설명합니다.
- [상세 참고 문서: 시험](reference.md#시험): 시험 수와 시험 구성
- [화면 설계서](screens/screen-design.md): 화면에서 이 상태와 판단이 보이는 자리
- [picasso 한계 레지스터](https://github.com/LivingLikeKrillin/picasso/blob/79e074d/docs/limits.md): 고정 커밋 `79e074d` 기준 picasso 의 오픈 항목과 한계
