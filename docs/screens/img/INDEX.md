# 화면 스크린숏 목록

`ui/screens/capture.spec.ts` 가 `npx playwright test -c playwright.screens.config.ts` 한 번으로 찍고 이 목록을 쓴다. 손으로 고치지 않는다.

- 대표 21장. 그 밖의 상태는 화면 설계서 본문에 글로 적는다
- 1440×900(모바일은 390×844), 배율 1, 밝은 색 구성
- 방법: 실제 스택 = 로컬 전체 스택(Postgres, 런처, 실행 호스트, 운영 서비스, Mock MES 콜백, host-link 프록시). 대역 = page.route 로 운영 서비스 응답을 바꿈(파일 이름 끝 `-mock`)
- 단언 문구: 찍기 전에 화면에 보인다고 단언한 문구

| 파일 | 영역 | 모드 | 상태 | 크기·범위 | 방법 | 단언 문구 |
|---|---|---|---|---|---|---|
| `01-robots-overview.png` | 로봇 | 엔지니어 | 도입 준비 탭, 기체 둘 선언, quadruped-01 준비 현황 | 1440px · 전체 페이지 | 실제 스택 | «연결 준비부터 첫 작업까지», «quadruped-01 준비 현황», «다음 조치: 아래 상세의 명칭 상태», «humanoid-01» |
| `02-robots-detail-commissioned.png` | 로봇 | 엔지니어 | humanoid-01 바인딩·명칭 기록 뒤 시운전 완료 | 1440px · 구역 | 실제 스택 | «시운전: 완료», «막힘 없음», «기준 120초, 현장 설정 버전 2» |
| `03-missions-overview.png` | 임무 | 엔지니어 | 임무 목록 패널(지원 임무 카드) | 1440px · 전체 페이지 | 실제 스택 | «지원 임무», «실행 정의 편집 지원», «코드 정의 · 읽기 전용», «PrepareSequencedRack», «InspectAsset» |
| `04-missions-mock-run-passed.png` | 임무 | 엔지니어 | 실행 정의 패널, 초안 1 검증 통과 뒤 모의 실행 통과(활성화 전) | 1440px · 전체 페이지 | 실제 스택 | «초안 1 모의 실행: 통과», «PrepareSequencedRack» |
| `05-missions-interface-deployed.png` | 임무 | 엔지니어 | 호출 계약 asset-check v1 공개, 저장된 매핑, 실행 배포 v2와 배포 이력 | 1440px · 구역 | 실제 스택 | «MES 자산 점검 · v1», «대상 임무: InspectAsset», «실행 배포 · v2», «초기 코드 정의 명시적 고정» |
| `06-overview.png` | 운영 개요 | 운영자 | 작업 요청 4건 뒤 지표 카드, 호스트 실행 관측, 결과 자동 수집, 최근 작업 요청 | 1440px · 전체 페이지 | 실제 스택 | «총 작업 요청», «작업 요청 거부», «수집 중», «미해결 인시던트», «MES-E2E-204» |
| `07-requests-list.png` | 작업 요청 | 운영자 | 외부 주문 둘(수락·거부), 운영자 수동, 호출 계약 요청이 섞인 목록 | 1440px · 전체 페이지 | 실제 스택 | «검색 결과 4건», «MES-E2E-204», «MES-E2E-BAD», «운영자 수동», «호출 계약 · 운영자», «거부» |
| `08-requests-detail-result-ack.png` | 작업 요청 | 운영자 | 외부 주문 상세, 결과 v1 저장·전달(시도 2회)·수신 확인 | 1440px · 구역 | 실제 스택 | «MES-E2E-204 · 호스트 수락», «v1 수신 확인됨», «DELIVERED», «요청 시 고정된 배포», «PHYSICALLY_DONE» |
| `09-requests-in-doubt.png` | 작업 요청 | 운영자 | 외부 주문 MES-RESTART-1 전송 결과 불명(호스트 연결 단절 중), 현장 확인 기록 폼 | 1440px · 구역 | 실제 스택: host-link 프록시가 실행 호스트의 수락 응답 뒤 연결을 끊음(cut-after-submit) | «MES-RESTART-1 · 전송 결과 불명», «새 제출은 차단됩니다», «현장 확인 기록 저장» |
| `10-stations-queue.png` | 공유 자원 | 운영자 | inspection-1 점유 작업 실행 중, 다음 작업 요청 자원 대기 | 1440px · 전체 페이지 | 실제 스택 | «공유 검사 스테이션», «InspectAsset → inspection-1», «실행·설비 완료 대기», «자원 대기», «점유 작업 보기» |
| `11-stations-review-required.png` | 공유 자원 | 운영자 | 단계 기한 초과로 현장 확인 필요, 판단 근거 입력·확인 체크(보내기 전) | 1440px · 구역 | 실제 스택 | «현장 확인 필요 · 작업 시간 초과», «현장 확인 후 자원 해제», «재실행 없이 관측 재개» |
| `12-operations-executions.png` | 운영 | 운영자 | 수동 작업 요청(InspectAsset) 배정 직후, 수동 작업 요청 추적과 실행 목록 | 1440px · 전체 페이지 | 실제 스택 | «InspectAsset 작업 지시: 배정됨», «작업 요청 기록 저장됨», «<작업 지시 id>», «코드 정의» |
| `13-operations-incident-hold.png` | 운영 | 운영자 | 설비 대기 기한 초과로 보류 중인 인시던트 상세와 보류 해소 폼(사유 입력, 보내기 전) | 1440px · 구역 | 실제 스택 | «SIGNAL_DEADLINE», «보류 중», «보류 해소 방식(완료 확인 또는 재작업)을 결정합니다. 결정자는 local입니다.», «사람의 판단 없음. 아래 값은 모두 관측입니다» |
| `14-operations-restart-restored-mock.png` | 운영 | 운영자 | 실행 호스트 재기동 뒤 복원 보고와 «이전 exec-k» 행 | 1440px · 구역 | 대역: GET /api/executions 를 재기동 뒤 모양(restore, restoredFrom)으로. 바탕은 같은 실행의 실제 PrepareSequencedRack 실행 | «재기동: 이전 인스턴스의 실행 1건을 다시 지었습니다», «<작업 지시 id>(humanoid-01): 이전 <실행 id> → exec-1», «exec-1 (이전 <실행 id>)» |
| `15-site-settings.png` | 현장 설정 | 엔지니어 | 버전 2 반영, 변경 폼과 버전 이력 | 1440px · 전체 페이지 | 실제 스택 | «연결 기준 시간 120초로 변경: 반영됨», «실행 호스트 반영: 버전 2», «연결 기준 늘림» |
| `16-history.png` | 이력 | 운영자 | 흐름을 돈 뒤 조작 기록 | 1440px · 전체 페이지 | 실제 스택 | «조작 기록», «운영자/local», «엔지니어/local», «랙 재배치 뒤 재작업», «연결 기준 늘림» |
| `17-validation-fault.png` | 검증 도구 | 엔지니어 | quadruped-01 연결 상태 OFFLINE 주입 받아들임 | 1440px · 구역 | 실제 스택 | «quadruped-01 연결 상태 OFFLINE: 받아들임(연결 상태 OFFLINE)», «장애 넣기» |
| `18-validation-comparison.png` | 검증 도구 | 엔지니어 | 호출 계약 요청(배포 v2)과 외부 주문 MES-E2E-204(배포 v3) 비교 | 1440px · 구역 | 실제 스택 | «계약 또는 실행 배포가 다릅니다.», «호출 계약과 실행 근거 비교», «MES-E2E-204», «PHYSICALLY_DONE» |
| `19-robots-registry-down.png` | 로봇 | 엔지니어 | registry 불통, 전체 상태 모름과 기체 목록 직전 값 | 1440px · 전체 페이지 | 실제 스택: 런처(registry·mimic 프로세스)를 끔 | «모름: registry 가 답하지 않습니다», «직전 값입니다», «humanoid-01», «quadruped-01» |
| `20-overview-mobile.png` | 운영 개요 | 운영자 | 06 과 같은 상태, 390px | 390px · 구역 | 실제 스택 | «총 작업 요청», «작업 요청 거부», «수집 중», «미해결 인시던트», «MES-E2E-204» |
| `21-requests-detail-mobile.png` | 작업 요청 | 운영자 | 08 과 같은 상세, 390px | 390px · 구역 | 실제 스택 | «MES-E2E-204 · 호스트 수락», «v1 수신 확인됨», «DELIVERED», «요청 시 고정된 배포», «PHYSICALLY_DONE» |
