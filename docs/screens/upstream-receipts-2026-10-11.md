# 상위 주문 영속 접수·중복 방지·실행 추적

> 최신 구현: [상위 업무 중심 후속 7개 구현 및 검증](upstream-followups-2026-10-11.md). 아래 내용은 해당 단계의 기록이다.

2026-10-11. 상위 시스템 중심 운영 계획의 접수 계층을 구현했다. 실제 MES·WMS 연동이 아닌 **단일 Mock MES 호출 주체를 사용하는 루프백 PoC**다.

## 구현 범위

- `(sourceSystemId, externalOrderId)` DB 유일 키와 JSON 키 정렬 기반 SHA-256으로 중복 접수를 판정한다. 계약 ID·버전·입력까지 같으면 기존 접수를 반환하고, 다르면 409 ORDER_CONFLICT다. 배열 순서와 값은 유지한다.
- receiptId(UUID)와 내부 jobOrderId(전체 UUID 포함)를 최초 한 번 생성한다. 동시 중복 요청의 승자만 호스트 제출을 수행한다.
- 호스트 호출 전에 접수를 커밋한다. 기존 두 미션의 **공개된 호출 계약 버전**으로 입력을 변환한다. 미배정과 업무 검증 거절도 영속 기록으로 남긴다.
- 서버에 설정한 Bearer 토큰이 호출 주체를 결정한다. 요청이 sourceSystemId를 지정하는 방식은 허용하지 않는다. 외부 조회는 같은 출처의 접수만 허용하며 다른 출처는 404다.
- 운영 화면 상단에 작업 요청 목록·검색·상태 필터·페이지 이동·접수 상세를 추가했다. 원본 입력·변환된 작업·제출 기록·호스트 인스턴스·실행 ID·현재 실행과 근거를 연결한다.
- 상세 조회에서 실제 실행을 관측하면 저장한다. 현재 조회 실패/실행 없음과 저장된 직전 관측을 구분한다. 전송 불명 주문도 일지 차단 없는 실행을 관측하면 ACCEPTED로 확인하며, **재전송하지 않는다**.

기존 수동 폼과 로컬 계약 invoke는 기존 응답 계약을 유지한다. 이 경로의 요청까지 새 영속 접수 목록에 편입한 것은 아니다. 화면에도 해당 범위를 명시했다.

## HTTP 계약

| 경로 | 의미 |
|---|---|
| POST /integration/v1/job-orders | Bearer 인증 후 접수. 최초 202, 동일 요청 재접수 200, 내용 충돌 409. 본문에 선택 칸 `replacesExternalOrderId` 를 실을 수 있다(같은 출처의 앞선 외부 주문을 대신하는 새 주문). 대체 관련 오류는 400 `BAD_REPLACES` 와 409 `ORIGINAL_NOT_FOUND`·`ORIGINAL_NOT_REPLACEABLE`·`ORIGINAL_ALREADY_REPLACED` 이다(자세한 것은 표 아래 문단). |
| GET /integration/v1/job-orders/{receiptId} | 같은 호출 주체의 접수·현재 실행·직전 관측 조회. 응답에 호스트 거부 사유 `rejection`, 호스트 미배정의 기체별 사유 `unassigned`, 대체 연결 `replaces`·`supersededBy` 도 싣는다(자세한 것은 아래 문단). |
| GET /api/work-requests?search=&state=&offset=0&limit=20 | 로컬 Ops 조회. 검색은 출처·외부 주문·내부 ID의 문자열 포함 검색. limit 1~100 |
| GET /api/work-requests/{receiptId} | 로컬 Ops 접수 상세 |

```json
{
  "externalOrderId": "MES-ORDER-204",
  "interfaceId": "asset-check",
  "interfaceVersion": 1,
  "input": { "assets": [{ "id": "T1", "location": "bay-7" }] }
}
```

대체 주문의 예다. 이 계약에서 입력의 출발 자리는 `presentation` 이며, 앞선 주문 `MES-SUPPLY-1` 은 `presentation` 이 빈 출발 자리 `RACK-204.S04` 여서 출발 결품이 되었고, 상태 조회의 대체 위치로 `SEQ-IN-02.BIN-A` 가 왔다. 상위 시스템이 대체 위치를 골라 입력의 출발 자리를 고친 새 주문을 `replacesExternalOrderId` 와 함께 내며, 운영 서비스는 앞선 주문을 고치지 않는다.

```json
{
  "externalOrderId": "MES-SUPPLY-2",
  "replacesExternalOrderId": "MES-SUPPLY-1",
  "interfaceId": "sequenced-supply",
  "interfaceVersion": 1,
  "input": { "slots": ["RACK-204.S01"], "material": "ENGINE-COVER-A", "presentation": "SEQ-IN-02.BIN-A" }
}
```

POST 성공 본문은 `{ receipt, duplicate }`이며 Location은 외부 조회 주소다. **202는 접수 저장을 의미하며 배정·물리 완료를 뜻하지 않는다.** 정상 형식의 업무 요청은 검증 거절이어도 REJECTED 접수로 남는다. 형식 자체가 잘못된 요청은 400이며 접수하지 않는다. 본문은 64KiB 이하 JSON 객체다.

선택 칸 `replacesExternalOrderId`는 같은 출처의 앞선 `externalOrderId`이고 1~160자 문자열이며 자기 자신(이 요청의 `externalOrderId`)일 수 없다. 본문 칸 집합은 기존 넷이거나 그 넷에 이 칸을 더한 것이다. 그 밖의 칸 집합은 그대로 400 `BAD_ENVELOPE`이다. 문자열이 아니거나 빈 값이거나 161자 이상이거나 자기 자신이면 400 `BAD_REPLACES`다. 이 출처에 그 주문이 없으면 409 `ORIGINAL_NOT_FOUND`이며 다른 출처의 주문도 같은 답이다. 앞선 작업 요청이 `REJECTED`·`UNASSIGNED`가 아니거나, 실행 관측(`lastExecution`)이나 결과 버전이 있거나, 스테이션 작업이 아직 끝나지 않았다면(`COMPLETED`·`TIMED_OUT`·`REJECTED`·`MANUALLY_CLEARED`·`CANCELLED` 밖) 409 `ORIGINAL_NOT_REPLACEABLE`이다. 이미 다른 작업 요청이 그 주문을 대체했으면 409 `ORIGINAL_ALREADY_REPLACED`다. 이 거부들은 작업 요청을 남기지 않는다. 그래서 같은 새 `externalOrderId`로 고쳐 다시 보낼 수 있다. 같은 새 주문의 재전송은 연결 판정 없이 200 중복이다. 연결 칸은 본문 해시에 들어가므로 같은 ID에 연결 칸만 빼거나 바꾸면 409 `ORDER_CONFLICT`다.

접수 상태와 물리 상태를 분리한다. ACCEPTED 접수는 호스트 수락을 뜻한다. 물리 완료는 상세 execution.physicalState와 완료 근거를 별도로 해석한다. hostRead가 UNAVAILABLE면 현재 상태를 모른다. 연결 실패뿐 아니라 instanceId·executions 형식이 잘못된 응답도 모름으로 처리한다. receipt.lastExecution은 저장된 이전 관측이며 현재 상태로 대체하지 않는다.

외부 조회 `GET /integration/v1/job-orders/{receiptId}`의 본문은 운영 화면 상세(`GET /api/work-requests/{receiptId}`)와 같은 맵이다. `rejection{reason, remedy, remedyWithheld, alternativeLocations, sourceMissing, sourceUnobserved}`는 호스트가 거부한 `REJECTED`에만 있고 그 밖에는 null이다. `unassigned{refusals[{robotId, reason, alternativeLocations, sourceMissing, sourceUnobserved}]}`는 호스트가 낸 `UNASSIGNED`에만 있고 그 밖에는 null이다. `alternativeLocations`의 `[]`(그 자재를 든 다른 자리 없음)와 null(셀 무응답 또는 출발 결품 아님)은 JSON에서도 접히지 않는다(널 칸을 빼지 않는다). 칸 `replaces`, `supersededBy`는 늘 있다. 각각 `{receiptId, externalOrderId, state, linkedAt}`이거나 null이다. `state`는 읽는 때의 상대 작업 요청 상태다. `replaces`는 이 작업 요청이 대신한 앞선 작업 요청, `supersededBy`는 이 작업 요청을 대신한 새 작업 요청이다.

## 설정과 권한

운영 서비스 환경 변수 `PICASSO_INTEGRATION_TOKEN`과 `PICASSO_INTEGRATION_SOURCE`(기본 mock-mes)를 사용한다. 토큰이 비어 있으면 외부 API는 401이다. 토큰은 프런트엔드 설정·응답에 포함하지 않는다. 토큰 비교는 SHA-256 고정 길이 값의 constant-time 비교를 사용한다.

전체 스택 E2E는 run-dist.mjs의 명시적 `--mock-mes` 플래그에서만 시험 전용 자격 증명을 설정한다. 배포 기본값으로 활성화하지 않는다. Ops의 모드 헤더는 기존 로컬 관리 기능용이며 외부 인증을 대체하지 않는다. 다중 조직 IAM·토큰 순환·TLS를 갖춘 실제 공장 배포 인증은 후속 범위다.

## Mock MES 재현 클라이언트

`scripts/mock-mes.mjs`는 주문 JSON 파일을 한 번 제출하고 선택적으로 120초까지 상태를 조회한다. 제출 응답을 잃어도 자동 POST 재시도는 하지 않는다. 먼저 Ops에서 asset-check/v1 등의 계약을 생성·공개하고, 서버와 클라이언트에 같은 PICASSO_INTEGRATION_TOKEN을 설정한다.

```
node scripts/mock-mes.mjs order.json --poll
```

위 HTTP 계약의 JSON을 order.json에 저장한다. 기본 주소는 루트 .env의 OPS_PORT를 사용하며 MOCK_MES_BASE_URL로 바꿀 수 있다. 실제 작업을 제출하는 클라이언트이므로, 검증용 Mimic 환경에서 실행한다. 출력에는 인증 토큰을 포함하지 않는다.

## 실패·재기동 경계

| 상황 | 처리 |
|---|---|
| 최초 DB 저장 실패 | 호스트를 호출하지 않음 |
| 저장 직후 프로세스 중단 | RECEIVED 또는 DISPATCHING 유지. 중복 요청으로 다시 실행하지 않음 |
| 업무 입력·미공개 계약 거절 | REJECTED와 사유 보존 |
| 배정 후보 없음 | UNASSIGNED와 기체별 이유 보존. 자동 재배정 없음 |
| 호스트 응답 불명/감사 기록 실패 | IN_DOUBT 또는 마지막 저장 상태 유지. 조회로 확인하며 자동 재제출 없음 |
| 호스트 실행 관측 | 현재 실행을 저장하고 불명 접수의 수락 여부를 확인 |
| 호스트 단절/재기동 | 접수 ID 유지. 마지막 관측은 시각·인스턴스와 함께 직전 값으로 제공 |

자동 재제출을 하지 않으므로 중복 방지를 위해 처리 지속성 일부를 보수적으로 제한한다. 저장 직후 중단된 접수의 자동 복구 worker나 운영자 재개 API는 아직 없다. 사용자가 새 외부 주문 ID를 발급하면 별개 작업이므로, 결과 불명을 회피하려고 새 ID로 재요청해서는 안 된다. 물리 동작의 exactly-once 보장을 주장하지 않는다.

## 실행 일지 저장 실패 보완

기존 호스트는 middleware.assign 이후 journal 쓰기가 실패하면 메모리에 실행이 남아 다음 pump에서 명령이 나갈 수 있었다. 이제 journaled에 없는 실행이 있으면 pump의 새 명령 진행과 신규 제출·배정을 차단한다. 실행 조회의 journalBlockedExecutionIds와 Ops 경고로 확인한다. Ops 재조회도 이 실행을 CONFIRMED_APPLIED로 단정하지 않는다.

이는 호스트의 보수적인 진행 차단이다. 이미 로봇에 전달된 다른 작업의 물리 정지를 보장하는 안전 기능이 아니다. 저장 장애를 해결한 뒤 호스트를 재기동하고 접수·일지·실행 상태를 확인해야 한다. 메모리의 미저장 실행은 자동 재제출하지 않는다. 일지 커밋 응답 불명의 경우에는 저장된 일지가 재기동 복원 대상이 될 수 있다.

## 남은 전체 계획

외부 결과의 버전별 불변 스냅샷·업무 수신 확인(ACK), callback 재전송, 수동 요청의 접수 계층 통합, 운영 개요와 전용 작업 메뉴, 배포 미션 정의 버전 고정, 임의 미션 유형 생성은 후속 범위다. 현재 저장된 실행 관측은 결과 ACK 프로토콜을 대체하지 않는다.

## 검증

UI 전체 174개, Ops 백엔드 전체 268개, 호스트 전체 79개와 실제 HTTP 전체 스택 E2E 1개(6.2분)가 통과했다. 호스트 응답 형식 검사 보완 후 Ops 전체 테스트도 다시 통과했다. 동시 중복 접수, 내용 충돌, 저장소 재생성, 인증·출처 격리, 미배정·거절, 접수 저장 실패, 전송 불명 재접수 차단, 실행 재관측과 직전 값 보존을 검증한다. 전체 스택에서는 Mock MES가 실제 HTTP로 접수·중복 요청·충돌·업무 거절·실행 결과 조회를 수행하고 Ops 목록·상세와 대조한다.

[실제 Ops 접수 상세 화면](style-preview/upstream-receipt-fullstack.png) · [모바일 화면](style-preview/upstream-receipt-mobile-fullstack.png). 2026-10-11 KST에 API 대역 없이 촬영했다. 실제 MES·PLC·실물 로봇 검증을 의미하지 않는다.

Mock MES CLI는 Node 구문 검사와 --help 실행을 확인했다. 배포본 생성과 main의 Picasso 모듈 비의존 검사도 수행했다.

후속 구현: [불변 결과 버전과 수신 확인(ACK)](upstream-results-2026-10-11.md)을 추가했다. 위 내용은 접수 단계의 당시 범위이며, 최신 ACK 계약과 남은 항목은 후속 문서를 따른다.
