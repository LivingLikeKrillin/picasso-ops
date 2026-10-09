# 화면 스크린숏 목록

`ui/screens/capture.spec.ts` 가 `npx playwright test -c playwright.screens.config.ts` 한 번으로 찍고 이 목록을 쓴다. 손으로 고치지 않는다.

- 화면 크기 1440×900, 배율 1, 밝은 색 구성. 파일 65개, 합계 3681 KiB, 그 가운데 경로 대역(`-mock`) 9개
- 번호는 찍은 순서이고 흐름(빈 상태 → 기체·어댑터·프로파일 → 현장 설정·장애 주입 → 작업 지시 → 임무 → 인시던트·판단 → 송신 기록 → 대역 상태 → registry 불통)을 따른다
- 전체 페이지는 `전체`, 구역만 찍은 것은 그 구역(region) 이름이다
- `-mock` 은 page.route 로 운영 서비스 응답을 바꿔 찍은 것이다. 바꾼 엔드포인트와 바탕을 «만든 방법» 에 적는다
- «보이는 주요 문구» 는 찍기 전에 화면에 있다고 단언한 문구다

| 파일 | 영역 | 구역 | 모드 | 상태 | 만든 방법 | 보이는 주요 문구 |
|---|---|---|---|---|---|---|
| `01-robots-overview-empty.png` | 로봇·연결 | 전체 | 엔지니어 | 빈 상태(기체·어댑터·리비전 없음) | 실제 스택 | «registry 응답 확인», «선언된 기체가 없습니다», «등록된 인스턴스가 없습니다», «제출된 리비전이 없습니다», «기체를 고르면 원장 상태와 연결이 여기에 보입니다.» |
| `02-robots-overview-empty-operator.png` | 로봇·연결 | 전체 | 운영자 | 빈 상태, 운영자 모드(선언·등록 폼 없음) | 실제 스택 | «선언은 엔지니어 모드에서 합니다», «어댑터 등록은 엔지니어 모드에서 합니다», «프로파일 관리는 엔지니어 모드에서 합니다» |
| `03-site-overview-initial.png` | 현장·자원 | 전체 | 엔지니어 | 처음 상태(현장 설정 버전 1, 기체 없음) | 실제 스택 | «현재 버전», «실행 호스트 반영: 버전 1», «결과 판정 값», «정체 표시», «장애를 넣을 기체가 없습니다» |
| `04-missions-overview-initial.png` | 임무·정책 | 전체 | 엔지니어 | 처음 상태(코드 정의, 버전·초안 없음) | 실제 스택 | «PrepareSequencedRack», «코드 정의», «활성화한 버전이 없습니다. 코드 정의로 돕니다», «저장한 초안이 없습니다», «데이터 정의 템플릿 불러오기» |
| `05-operations-overview-empty.png` | 운영 | 전체 | 운영자 | 빈 상태(실행·인시던트·송신 기록 없음) | 실제 스택 | «폼을 채우면 기체별 배정 가능을 봅니다», «실행이 없습니다», «인시던트가 없습니다», «이전 인스턴스의 인시던트가 없습니다», «송신 기록이 없습니다», «rack_present» |
| `06-history-overview-empty.png` | 이력 | 전체 | 공통 | 빈 상태 | 실제 스택 | «조작 기록이 없습니다» |
| `07-robots-detail-unbound.png` | 로봇·연결 | 상세 | 엔지니어 | 선언 직후 알림, 바인딩 없음 막힘 카드, 시운전 미완 | 실제 스택 | «humanoid-01 선언: 반영됨», «CONFIRMED», «바인딩 없음», «활성 바인딩 없음», «시운전: 미완», «퇴역과 복귀는 운영자 모드에서 합니다» |
| `08-robots-detail-retired-blocked.png` | 로봇·연결 | 상세 | 운영자 | 퇴역 뒤 보고 막힘 카드, 복귀 버튼 | 실제 스택 | «humanoid-01 퇴역: 반영됨», «RETIRED», «퇴역 뒤 보고», «운영자(화면 안): 현장에서 기체를 내리거나 복귀», «복귀» |
| `09-robots-notice-build-rejected.png` | 로봇·연결 | 상세 | 엔지니어 | 조작 거절 알림과 발견 카드(같은 빌드 버전에 다른 계약값) | 실제 스택 | «acme/fleet 1.0.0 빌드 선언: 거절됨», «관측값과 기대값», «해결 담당» |
| `10-robots-adapters-populated.png` | 로봇·연결 | 어댑터 | 엔지니어 | 제품·빌드·인스턴스 등록 뒤 | 실제 스택 | «fleet-gw-01», «UNTESTED», «acme/fleet», «1.0.0» |
| `11-robots-overview-activation-rejected.png` | 로봇·연결 | 전체 | 엔지니어 | 시험 전 활성화 거절 알림과 발견 카드, 리비전 VALIDATED | 실제 스택 | «picasso-ref/humanoid-a#2 활성화: 거절됨», «관측값과 기대값», «요청 없음» |
| `12-robots-profiles-populated.png` | 로봇·연결 | 프로파일 | 엔지니어 | 리비전 둘 시험 통과·활성화 뒤 | 실제 스택 | «ACTIVE», «PASS (site-runner)», «picasso-ref/humanoid-a», «picasso-ref/quadruped-b» |
| `13-robots-detail-bound-unnamed.png` | 로봇·연결 | 상세 | 엔지니어 | 바인딩 뒤 명칭 기록 없음 막힘 | 실제 스택 | «humanoid-01 바인딩: 반영됨», «명칭 기록 없음», «명칭 등록 기록», «요구 키» |
| `14-robots-detail-commissioned.png` | 로봇·연결 | 상세 | 엔지니어 | 시운전 완료, 막힘 없음 | 실제 스택 | «시운전: 완료», «막힘 없음», «acme/fleet 1.0.0», «picasso-ref/humanoid-a#2», «[v] 활성 바인딩» |
| `15-robots-detail-names-contradicted.png` | 로봇·연결 | 상세 | 엔지니어 | 시운전 미완, 기체가 아는 명칭 없음 막힘 카드 | 실제 스택 | «시운전: 미완», «기체가 아는 명칭 없음», «현장(화면 밖): 현장에서 명칭 티칭을 다시» |
| `16-robots-overview-populated.png` | 로봇·연결 | 전체 | 엔지니어 | 기체 둘·어댑터·리비전을 채운 뒤 | 실제 스택 | «humanoid-01», «quadruped-01», «fleet-gw-01», «시운전: 완료» |
| `17-robots-overview-populated-operator.png` | 로봇·연결 | 전체 | 운영자 | 채운 상태, 운영자 모드(퇴역 폼, 등록 폼 없음) | 실제 스택 | «선언은 엔지니어 모드에서 합니다», «퇴역 사유», «프로파일 관리는 엔지니어 모드에서 합니다» |
| `18-site-settings-out-of-range.png` | 현장·자원 | 현장 설정 변경 | 엔지니어 | 범위 밖 값이라 보내지 않음 | 실제 스택 | «연결 기준 시간은», «초의 정수여야 합니다», «허용 범위» |
| `19-site-settings-reason-missing.png` | 현장·자원 | 현장 설정 변경 | 엔지니어 | 사유 없음이라 보내지 않음 | 실제 스택 | «변경 사유를 넣으십시오» |
| `20-site-overview-changed.png` | 현장·자원 | 전체 | 엔지니어 | 변경 반영 알림, 버전 3, 버전 이력 세 줄 | 실제 스택 | «stallWindow 600초로 변경: 반영됨», «실행 호스트 반영: 버전 3», «연결 기준 늘림», «정체 표시 늦춤» |
| `21-site-settings-version-conflict.png` | 현장·자원 | 현장 설정 | 엔지니어 | 기준 버전이 낡아 거절된 알림과 발견 카드 | 실제 스택 | «stallWindow 300초로 변경: 거절됨», «관측값과 기대값», «다른 화면에서 먼저 기록» |
| `22-site-fault-reason-missing.png` | 현장·자원 | 장애 주입 | 엔지니어 | 사유 없음이라 보내지 않음 | 실제 스택 | «장애 주입 사유를 넣으십시오», «스킬 실패(진행 중 태스크)» |
| `23-site-fault-rejected.png` | 현장·자원 | 장애 주입 | 엔지니어 | 진행 중 태스크가 없어 현장이 거부 | 실제 스택 | «humanoid-01 스킬 실패: 현장이 거부함», «진행 중 태스크 없음» |
| `24-site-fault-connection-accepted.png` | 현장·자원 | 장애 주입 | 엔지니어 | 연결 상태 장애 받아들임(연결 상태 칸이 더 보임) | 실제 스택 | «quadruped-01 연결 상태 OFFLINE: 받아들임(연결 상태 OFFLINE)», «연결 상태» |
| `25-site-overview-operator.png` | 현장·자원 | 전체 | 운영자 | 운영자 모드(변경·장애 주입 폼 없음) | 실제 스택 | «현장 설정 변경은 엔지니어 모드에서 합니다», «장애 주입은 엔지니어 모드에서 합니다», «실행 호스트 반영: 버전 4» |
| `26-operations-order-incomplete.png` | 운영 | 작업 지시·배정 가능 | 운영자 | 덜 채운 폼이라 보내지 않음, 배정 가능 묻지 않음 | 실제 스택 | «점검 대상마다 대상 id 와 장소 이름을 넣으십시오», «폼을 채우면 기체별 배정 가능을 봅니다» |
| `27-operations-eligibility-table.png` | 운영 | 배정 가능 | 운영자 | 기체별 배정 가능 판정(가능 하나, 불가 하나) | 실제 스택 | «판정 시각», «가능», «불가», «시운전이 끝나지 않았다» |
| `28-operations-order-engineer.png` | 운영 | 작업 지시·배정 가능 | 엔지니어 | 엔지니어 모드(입력·판정은 보이고 제출 버튼 없음) | 실제 스택 | «작업 지시는 운영자 모드에서 냅니다», «판정 시각» |
| `29-operations-order-assigned.png` | 운영 | 작업 지시 | 운영자 | 제출 결과 배정됨 | 실제 스택 | «InspectAsset 작업 지시: 배정됨», «작업 지시 id», «실행 id», «배정된 기체» |
| `30-operations-eligibility-busy.png` | 운영 | 배정 가능 | 운영자 | 도는 실행이 있어 모두 불가 | 실제 스택 | «도는 실행이 있다», «불가» |
| `31-operations-order-unassigned.png` | 운영 | 작업 지시 | 운영자 | 배정 가능한 기체가 없어 배정하지 못함 | 실제 스택 | «InspectAsset 작업 지시: 막힘(배정 가능한 기체 없음)» |
| `32-operations-executions-running.png` | 운영 | 실행 | 운영자 | 코드 정의 임무 실행 중 | 실제 스택 | «실행 호스트 인스턴스», «마지막 pump», «InspectAsset», «코드 정의», «: RUNNING» |
| `33-missions-validate-refused.png` | 임무·정책 | 임무 편집 | 엔지니어 | 현장에 없는 스킬로 고친 정의의 검증 거부와 발견 카드 | 실제 스택 | «초안 1 검증: 거부됨», «관측값과 기대값», «해결 담당» |
| `34-missions-activate-mock-run-required.png` | 임무·정책 | 임무 편집 | 엔지니어 | 모의 실행 없이 활성화해 막힘 | 실제 스택 | «초안 2 활성화: 막힘. 이 초안에 통과한 모의 실행이 없습니다. 모의 실행을 먼저 하십시오», «대상: 초안 2, 마지막 모의 실행 없음» |
| `35-missions-mock-run-passed.png` | 임무·정책 | 임무 편집 | 엔지니어 | 모의 실행 통과 보고(표본 작업 지시, 단위 표) | 실제 스택 | «초안 2 모의 실행: 통과», «표본 작업 지시», «가상 경과», «물리 상태» |
| `36-missions-activate-reason-missing.png` | 임무·정책 | 임무 편집 | 엔지니어 | 사유 없음이라 보내지 않음 | 실제 스택 | «활성화 사유를 넣으십시오» |
| `37-missions-overview-active.png` | 임무·정책 | 전체 | 엔지니어 | 활성화 뒤(버전 1 활성, 초안 둘) | 실제 스택 | «초안 2 활성화: 버전 1 활성화됨. 다음 작업 지시부터 이 버전을 씁니다», «버전 1 (활성)», «활성: 버전 1, 편집기와 같음» |
| `38-missions-drafts-mock-run-detail.png` | 임무·정책 | 초안 | 엔지니어 | 초안 목록, 마지막 모의 실행 펼침 | 실제 스택 | «초안 1», «초안 2», «통과», «초안 2 열기» |
| `39-missions-overview-operator.png` | 임무·정책 | 전체 | 운영자 | 운영자 모드(편집기 없음, 활성 정의 읽기만) | 실제 스택 | «임무 편집은 엔지니어 모드에서 합니다», «버전 1 (활성)» |
| `40-operations-cell-signal-on.png` | 운영 | 셀 대역 | 운영자 | 신호 켜기 반영 알림 | 실제 스택 | «rack_present 켜기: 반영됨(값 true)», «셀 대역: 슬롯은 기체가 보고한 배치로 채웁니다», «제시 자리», «슬롯» |
| `41-site-fault-skill-accepted.png` | 현장·자원 | 장애 주입 | 엔지니어 | 진행 중 태스크에 스킬 실패 받아들임 | 실제 스택 | «humanoid-01 스킬 실패: 받아들임(태스크 JO-20261009-d4d42d0d#RACK-204.S01, RETRIABLE)» |
| `42-operations-incidents-failed.png` | 운영 | 인시던트 | 운영자 | 실패 인시던트(판단 대상 아님)와 상세(관측, 결함, 근거 윈도우) | 실제 스택 | «GRASP_FAILED», «판단 대상 아님», «관측(봉인 때 기록)», «결함», «근거 윈도우», «버전 4», «버전 1» |
| `43-operations-executions-failed.png` | 운영 | 실행 | 운영자 | 데이터 정의 버전 1 실행의 단위 실패 | 실제 스택 | «RACK-204.S01 pick_place: FAILED», «버전 1», «PHYSICALLY_DONE» |
| `44-operations-incidents-held.png` | 운영 | 인시던트 | 운영자 | 보류 중 인시던트(미해결) 줄 강조 | 실제 스택 | «SIGNAL_DEADLINE», «보류 중», «미해결», «GRASP_FAILED» |
| `45-operations-executions-held.png` | 운영 | 실행 | 운영자 | 설비 대기 기한이 지나 운영자 보류에 선 실행 | 실제 스택 | «OPERATOR_HOLD», «rack-arrival equipment_wait: OPERATOR_HOLD», «운영자 개입 필요», «버전 2» |
| `46-operations-incident-detail-held.png` | 운영 | 인시던트 상세 | 운영자 | 보류 중 상세와 운영자 판단 폼 | 실제 스택 | «보류를 판단합니다. 판단자는 local 입니다», «판단 사유», «완료 확인», «재작업», «SIGNAL_DEADLINE» |
| `47-operations-incident-detail-held-engineer.png` | 운영 | 인시던트 상세 | 엔지니어 | 보류 중 상세, 엔지니어 모드라 판단 폼 없음 | 실제 스택 | «보류 중입니다. 운영자 판단은 운영자 모드에서 합니다» |
| `48-operations-incident-decision-reason-missing.png` | 운영 | 운영자 판단 | 운영자 | 사유 없음이라 보내지 않음 | 실제 스택 | «판단 사유를 넣으십시오» |
| `49-operations-incident-decided.png` | 운영 | 인시던트 | 운영자 | 판단 결과 알림, 판단됨, 사람의 판단 구역 | 실제 스택 | «exec-3/rack-arrival 재작업: 판단이 섰습니다(incident-2)», «판단됨», «사람이 판단함: local, », «결정 재작업(REWORK)» |
| `50-operations-job-responses-filtered.png` | 운영 | 작업 응답 송신 기록 | 운영자 | 보류 작업 지시 하나로 고름 | 실제 스택 | «그 가운데 재기동 중복 0건», «JO-20261009-6da7189e», «송신», «필요» |
| `51-operations-job-responses-all.png` | 운영 | 작업 응답 송신 기록 | 운영자 | 전체 작업 지시 | 실제 스택 | «송신 기록», «그 가운데 재기동 중복 0건», «송신» |
| `52-operations-overview-operator.png` | 운영 | 전체 | 운영자 | 흐름을 돈 뒤 운영 영역 전체 | 실제 스택 | «작업 지시 내기», «실행 호스트 인스턴스», «판단됨», «rack_present» |
| `53-operations-overview-engineer.png` | 운영 | 전체 | 엔지니어 | 흐름을 돈 뒤 운영 영역 전체, 엔지니어 모드 | 실제 스택 | «작업 지시는 운영자 모드에서 냅니다» |
| `54-history-overview-populated.png` | 이력 | 전체 | 공통 | 흐름을 돈 뒤 조작 기록 | 실제 스택 | «운영자/local», «엔지니어/local», «엔지니어/kim», «정비», «랙 재배치 뒤 재작업», «REJECTED» |
| `55-operations-executions-restored-mock.png` | 운영 | 실행 | 운영자 | 재기동 복원 보고(다시 지음 1, 미룸 1, 포기 1)와 «이전 exec-k» 행 | 경로 대역: GET /api/executions 를 S4b 계약 H1 모양(restore, restoredFrom)으로. 바탕은 같은 실행의 실제 실행 목록, 미룸·포기 행과 사유는 계약 문장으로 지음 | «재기동: 이전 인스턴스의 실행 1건을 다시 지었습니다», «JO-20261009-6da7189e(humanoid-01): 이전 exec-3 → exec-1», «미룬 실행 1건. 실행 호스트가 다시 시도하며 그동안 그 기체는 배정에서 빠집니다», «포기한 실행 1건. 다시 짓지 않습니다. 기체의 남은 태스크를 운영자가 확인하십시오», «exec-1 (이전 exec-3)» |
| `56-operations-incidents-earlier-mock.png` | 운영 | 인시던트 | 운영자 | 재기동 뒤 새 인스턴스의 보류와 이전 인스턴스 표, 이전 인스턴스 사본 상세(판단 폼 없음) | 경로 대역: GET /api/incidents 를 S4b 계약 H3 모양(earlierTotal, earlier)으로, GET /api/incidents/{id}?instanceId= 를 H4 사본 모양으로. 바탕은 같은 실행의 실제 인시던트 목록과 상세 | «이전 인스턴스», «재기동 앞 인스턴스의 인시던트 2건 가운데 최신 2건. 읽기 전용이며 여기서 판단하지 않습니다», «incident-2 상세(이전 인스턴스 mw-33ca837f-f021-4a1d-85c4-ea8015e487c5)», «이전 인스턴스의 사본입니다. 읽기 전용이며 판단하지 않습니다», «모름(실행 없음)» |
| `57-operations-job-responses-duplicate-mock.png` | 운영 | 작업 응답 송신 기록 | 운영자 | 새 인스턴스의 첫 보류 응답이 재기동 중복 | 경로 대역: GET /api/job-responses 를 S4b 계약 H6 모양으로. 바탕은 같은 실행의 실제 송신 기록, 맨 위 RESTART_DUPLICATE 행을 더함 | «재기동 중복(송신 안 함)», «그 가운데 재기동 중복 1건», «mw-18389c12-a72f-42ee-875e-0a67cd002832» |
| `58-operations-eligibility-restore-blocked-mock.png` | 운영 | 배정 가능 | 운영자 | 복원 못 한 실행이 있는 기체를 배정에서 뺌 | 경로 대역: POST /api/job-orders/eligibility 의 실제 응답에 S4b 계약 H2 이유 문장을 더함 | «복원 못 한 실행이 있다: JO-20261009-2e85767c», «도는 실행이 있다: exec-1» |
| `59-operations-overview-restarted-mock.png` | 운영 | 전체 | 운영자 | 실행 호스트 재기동 뒤 운영 영역 전체 | 경로 대역: GET /api/executions·/api/incidents·/api/incidents/{id}·/api/job-responses, POST /api/job-orders/eligibility | «재기동: 이전 인스턴스의 실행 1건을 다시 지었습니다», «재기동 중복(송신 안 함)», «보류 중» |
| `60-operations-overview-host-down-mock.png` | 운영 | 전체 | 운영자 | 실행 호스트 불통, 구역마다 직전 값 | 경로 대역: GET /api/executions·/api/cell·/api/incidents·/api/job-responses·/api/missions/*, POST /api/job-orders/eligibility 를 503 HOST_SILENT 로(S3a·S4a·S4b 계약). 앞서 읽은 실제 값이 직전 값으로 남음 | «직전 값입니다. 실행 호스트 불통: 실행 호스트가 답하지 않는다: java.net.ConnectException», «직전 값입니다 (운영 서비스 응답 503)», «registry 응답 확인» |
| `61-missions-overview-host-down-mock.png` | 임무·정책 | 전체 | 운영자 | 실행 호스트 불통에 영역을 처음 열어 모름 | 경로 대역: GET /api/executions·/api/cell·/api/incidents·/api/job-responses·/api/missions/*, POST /api/job-orders/eligibility 를 503 HOST_SILENT 로(S3a·S4a·S4b 계약). 앞서 읽은 실제 값이 직전 값으로 남음 | «모름: 임무 버전을 아직 읽지 못했습니다 (실행 호스트가 답하지 않는다: java.net.ConnectException)», «모름: 활성 버전을 아직 읽지 못했습니다» |
| `62-site-settings-host-down-mock.png` | 현장·자원 | 현장 설정 | 운영자 | 실행 호스트 반영 모름 | 경로 대역: GET /api/site-settings 의 실제 응답에서 hostTimings 를 null 로(S3c 계약 §3, 호스트 불통) | «실행 호스트 반영: 모름», «현재 버전» |
| `63-robots-overview-ops-unreachable-mock.png` | 로봇·연결 | 전체 | 엔지니어 | 운영 서비스 불통, 전체 상태 모름과 직전 값 | 경로 대역: /api/** 요청을 끊음(connection refused). 앞서 읽은 실제 값이 직전 값으로 남음 | «모름: 운영 서비스에 닿지 않습니다», «직전 값입니다» |
| `64-robots-overview-registry-down.png` | 로봇·연결 | 전체 | 엔지니어 | registry 불통, 전체 상태 모름과 목록마다 직전 값 | 실제 스택: 런처(registry·mimic 프로세스)를 끔 | «모름: registry 가 답하지 않습니다. 해결 담당 엔지니어, registry 상태 확인», «직전 값입니다», «humanoid-01», «fleet-gw-01» |
| `65-operations-eligibility-registry-down.png` | 운영 | 배정 가능 | 운영자 | registry 불통 중의 배정 가능 판정 | 실제 스택: 런처(registry·mimic 프로세스)를 끔 | «판정 시각 2026-10-09T14:35:45.056349100Z», «불가» |
