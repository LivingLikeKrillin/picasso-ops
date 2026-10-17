# 미션 호출 계약 구현 — 저장·공개·실행 연결

> 최신 구현: [상위 업무 중심 후속 7개 구현 및 검증](upstream-followups-2026-10-11.md). 아래 내용은 해당 단계의 기록이다.

2026-10-10. 기존 미션 관리 1차 화면에 실제 영속 저장·버전별 입력 매핑·공개 상태·계약 호출을 추가했다. 현재 지원 업무는 InspectAsset과 PrepareSequencedRack이다.

## 사용자 여정

미션 관리 → 호출 연결 → 호출 계약 만들기 → 대상 미션 선택 → 입력 원본 경로 지정 → 샘플 매핑 검증 → 계약 초안 저장 → 공개 사유와 함께 공개 → 운영자 모드에서 계약 호출 → 운영 실행 목록에서 내부 jobOrderId 추적.

호출 식별자와 양의 정수 버전이 계약의 키다. 같은 키를 덮어쓰지 않는다. 선택한 계약의 ‘새 버전으로 복제’는 기존 매핑·샘플을 가져오고 현재 조회된 최대 버전 다음 번호를 제안한다. 동시 생성 충돌은 서버가 409로 거절한다. 작성 폼 접기와 미션 내부 탭 이동은 입력을 보존한다.

## 입력 연결과 버전 정책

- 매핑은 내부 업무 입력 필드 → 원본 JSON Pointer다. 예: slots → /order/slots.
- InspectAsset은 targets 전체 객체 목록을 연결한다. PrepareSequencedRack은 slots 목록, material 문자열, presentation 문자열을 연결한다.
- 고정된 허용 대상 필드만 지정할 수 있다. 스크립트 실행·임의 내부 필드 쓰기·외부 입력의 workMasterId 덮어쓰기는 허용하지 않는다.
- 샘플과 실제 호출 모두 기존 JobOrderForm의 자료형·길이·단위 중복 검사를 사용한다. 저장 시 샘플 검증도 수행한다.
- 계약 버전의 정의·샘플·대상 미션은 불변이다. DB 트리거도 덮어쓰기를 막는다. 변경은 새 버전 생성이다.
- 실행 정의 버전 정책은 **ACTIVE_AT_SUBMISSION**이다. 제출 시 호스트가 활성 미션 정의를 선택한다. 계약 버전에 특정 실행 정의 버전을 고정하는 기능은 아직 없다.
- 배정과 실행은 기존 JobOrderOperations/MissionHost 흐름을 사용한다. 매핑 미리보기와 초안 저장은 실행을 발생시키지 않는다.

## 공개 상태

DRAFT → ENABLED → DISABLED → ENABLED.

공개/중지는 엔지니어 모드와 사유가 필요하다. 변경 요청에 expectedStatus를 포함하며 현재 DB 상태와 다르면 409다. 상태 변경과 감사 기록은 같은 트랜잭션이다. 감사 기록 실패 시 계약 생성/상태 변경도 롤백한다.

공개 중지는 이후 계약 호출을 막는다. 이미 접수된 작업을 취소하지 않는다. 다른 계약 버전의 공개 상태는 독립적이다.

## API

| API | 기능 | 모드 |
|---|---|---|
| GET /api/mission-interfaces | 저장된 계약 버전 목록 | 기존 Ops 읽기 정책 |
| POST /api/mission-interfaces/preview | 저장 전 샘플 매핑·JobOrder 변환 | 부작용 없는 미리보기 |
| POST /api/mission-interfaces | 검증된 새 버전 초안 저장 | engineer |
| POST /api/mission-interfaces/{id}/{version}/state | expectedStatus 기반 공개/중지 | engineer |
| POST /api/mission-interfaces/{id}/{version}/invoke | 공개 계약 입력을 실제 작업 지시로 제출 | operator |

등록/미리보기 본문 예:

```json
{
  "interfaceId": "asset-check",
  "version": 1,
  "name": "MES 자산 점검",
  "workMasterId": "InspectAsset",
  "mapping": { "targets": "/assets" },
  "sample": { "assets": [{ "id": "T1", "location": "bay-7" }] }
}
```

공개 요청 예:

```json
{ "expectedStatus": "DRAFT", "status": "ENABLED", "reason": "대상 목록 매핑 검증 완료" }
```

invoke 본문은 계약의 원본 입력이다:

```json
{ "assets": [{ "id": "T1", "location": "bay-7" }] }
```

호출 응답은 interfaceId, interfaceVersion, missionVersionPolicy와 기존 submission(JobOrderOutcome)을 포함한다. submission.result와 confirmation/outcome을 해석해야 하며 HTTP 200을 작업 완료로 해석하지 않는다.

실제 호출의 기존 조작 기록에는 interfaceRef(예: asset-check/v1), interfaceInput(원본 입력), 변환된 jobOrder와 후보 목록을 함께 남긴다. 실행 목록·송신 기록은 응답의 jobOrderId로 연결한다.

## 저장과 실패 경계

Flyway V5__mission_interfaces.sql이 ops.mission_interface를 추가한다. 새 저장소 인스턴스에서도 정의와 공개 상태를 읽을 수 있음을 PostgreSQL 테스트로 검증한다. 이 테스트는 프로세스 재기동 E2E와는 구분한다.

- 같은 계약·버전 생성: 409 INTERFACE_VERSION_EXISTS.
- 오래된 공개 상태 변경: 409 INTERFACE_STATE_CONFLICT.
- 미공개 계약 호출: 409 INTERFACE_NOT_ENABLED.
- 없는 계약: 404 INTERFACE_NOT_FOUND.
- 잘못된 포인터/필드/샘플: 400. 실제 업무 검증 거절은 기존 오류 코드를 유지한다.
- JSON 본문은 64KiB 이하다. 엔지니어/운영자 쓰기는 기존 X-Ops-Mode·X-Ops-User 게이트와 application/json 제한을 따른다.
- 저장·공개 실패 시 입력과 사유를 유지한다. 응답 불명은 실제 상태를 다시 조회하도록 안내한다.
- 호출 응답 불명 시 화면에서 재호출을 막고 실행 이력 확인을 안내한다. 4xx로 확인된 거절은 입력 수정 후 다시 시도할 수 있다.

## 현재 범위

여기서 공개는 **로컬 Ops PoC에서 해당 버전의 invoke 경로를 사용 가능하게 하는 것**이다. 기존 mode 헤더는 서비스 인증 수단이 아니다. 외부 MES/WMS용 인증·원천 주문 식별·영속 접수·중복 방지·결과 수신 확인은 상위 접수 계층의 후속 구현 범위다.

각 invoke는 기존과 같이 새 내부 작업 ID를 생성한다. 미배정 사전 거절은 기존 경로의 정책을 따른다. 외부 주문에 대한 exactly-once·자동 재시도·전 요청 영속 추적을 제공한다고 주장하지 않는다. 호스트 journal/복원 신뢰성 보완도 전체 상위 연계 계획의 선행 검증 항목으로 남아 있다.

임의 새 WorkMaster나 범용 런타임 스키마 생성, 슬롯 목록의 요소별 임의 객체 재구성, 실행 정의 버전 고정 배포는 이번 범위가 아니다.

## 검증 기록

- UI 전체 171개 통과(단일 worker 재검증). 추가 계약 테스트 7개: 매핑 변경/미리보기, 저장·공개, 상태 충돌, 공개 계약 호출, 미공개 차단, 중복 저장 거절, 버전 복제·입력 보존 통과.
- 백엔드 전체 258개 통과. 신규 매핑·영속 저장·권한·롤백 검증 13개 포함.
- TypeScript/Vite 빌드, 린트, ops-service 배포본 생성, main의 Picasso 모듈 비의존 검사 통과.
- 전체 스택 E2E 1개 종합 시나리오 통과(5.5분). 계약 작성→검증→저장→공개→실제 호출→Mimic 물리 완료→목록 재조회→공개 중지→409 호출 거절과 기존 도입·미션 활성화·장애 주입·운영자 판단·연결 단절 흐름을 함께 검증했다. 첫 실행에서 발견한 대상 미션 선택 요소의 접근성 이름을 보완한 뒤 재실행했다.
- 실제 Ops API로 [저장된 공개 계약](style-preview/mission-interface-version-fullstack.png)과 [샘플 매핑 검증](style-preview/mission-interface-editor-fullstack.png)을 촬영했다. 실물 MES/PLC/로봇 검증은 아니다.
- 1440/1280/768/390px에서 작성 폼을 포함한 화면의 브라우저 오류·가로 넘침 없음, Pretendard 로딩 확인. 작성 폼의 fieldset 배치를 보완했다.
