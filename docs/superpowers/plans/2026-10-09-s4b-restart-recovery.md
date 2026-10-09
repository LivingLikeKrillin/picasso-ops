# S4b 호스트 재기동 뒤 실행 복원, 중복 명령·중복 작업 응답 막기 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking. 단, 이 계획은 묶음(Task 1·2 / Task 3 / Task 4 / Task 5 / Task 5b)마다 구현자 하나가 하고, 검토는 묶음이 끝난 뒤 컨트롤러가 기계 대조와 시험으로 한다.

**Goal:** 실행 호스트가 재기동해도 받은 작업 지시의 실행이 같은 작업 지시 id 로 다시 서고(기체에는 새 명령 없이 기존 태스크에 다시 붙음), 새 인스턴스가 이전 인스턴스가 이미 보낸 것과 같은 작업 응답을 다시 내보내지 않으며(송신 기록의 재기동 중복), 이전 인스턴스의 인시던트와 판단이 화면에 남고 이전 인스턴스를 실은 판단은 409 `INSTANCE_MISMATCH` 로 막힌다.

**Architecture:** 실행 호스트는 새 실행의 ACCEPTED 제출을 응답 전에 실행 일지(`mission.execution_journal`)에 적고, pump 뒤 같은 잠금 안의 한 트랜잭션으로 송신 기록·인시던트 사본·판단 행·정착 이벤트를 적은 뒤 `ack` 한다. 기동 때(`MissionHost.start`, pump 전) 정착·포기 없는 일지 행을 받은 순서대로 picasso `Middleware.resume`(P6, `74e4d3d`)에 넣어 받은 때의 임무 버전으로 다시 짓고 RESTORED·DEFERRED·GAVE_UP 을 일지 이벤트와 복원 보고로 남긴다. 운영 서비스는 `earlier`·`instanceId` 질의·송신 기록을 중계하고 판단 본문에 `instanceId` 를 싣는다. 화면은 복원 보고 띠, 이전 인스턴스 인시던트, 작업 응답 송신 기록 구역을 연다. picasso 는 서브모듈 포인터만 `195c1ee` 에서 `74e4d3d` 로 올린다.

**Tech Stack:** Kotlin, Spring Boot(BOM), Flyway·Postgres(덧붙이기 전용 표 다섯), JDK `HttpClient`, picasso(`Middleware.resume`, `ActiveMission`, `MissionDefinitionParser`, mimic 엔진), React·TypeScript·vitest, Playwright, JUnit5 + kotlin.test, Testcontainers.

**근거 스펙:** `docs/superpowers/specs/2026-10-09-s4b-restart-recovery-design.md`(검토 1차·2차)와 요청·응답 모양 `docs/superpowers/specs/2026-10-09-s4b-json-contract.md`.

**스펙이 계획에 맡긴 것과 이 계획이 정한 것(스파이크에서 정함):**
- 순서: 서브모듈 포인터(`74e4d3d`)를 맨 앞(Task 1)에 둔다. 실행 호스트의 기동 복원이 `Middleware.resume` 을 부르므로 그 뒤의 모든 커밋이 포인터 위에서 빌드된다. 커밋은 스파이크 커밋 하나에 하나(18개)이고 메시지도 스파이크 그대로이되 다섯 곳만 바꿨다. main 이력에 남지 않아야 할 스파이크 전용 글자 넷을 뺐고(서브모듈 커밋의 스파이크 해시, 기동 복원 커밋의 포인터 미포함 불릿, 일지 커밋의 P6 대기 글귀, 주석 정정 커밋의 결함 주입 언급), 주석 정정 커밋의 불릿 하나를 형식 훅의 명사형 종결에 맞췄다. 계획의 메시지 18개는 형식 훅의 `check_commit` 을 미리 통과했다. 계획 검토가 잡은 고침 여섯은 Task 5b 로 맨 뒤에 둔다(스파이크 순서 그대로).
- 중간 커밋: Task 2 의 둘째 커밋(판단 본문 `instanceId` 필수)부터 Task 5 의 첫 커밋(`FaultIncidentTest` 의 판단 요청에 `instanceId` 싣기) 전까지는 e2e `FaultIncidentTest` 의 판단 시험이 빨갛다. 그 사이의 커밋은 이분 탐색(bisect)용일 뿐이고, e2e 는 Task 5 에서 돌린다.
- 호스트 표: `V2__execution_journal.sql` 이 덧붙이기 전용 표 다섯(`execution_journal`, `execution_journal_event`, `job_response_log`, `incident_copy`, `incident_copy_resolution`)을 트리거로 묶는다(Task 5b 의 고침으로 표 다섯 전용 함수 `mission_record_append_only()`, 거부 문구는 실행 일지·송신 기록·인시던트 사본). 일지 이벤트는 `RESTORED`·`DEFERRED`·`GAVE_UP`·`SETTLED`, `DEFERRED` 는 미룬 기동마다 한 번, `SETTLED` 는 `PHYSICALLY_DONE`·`UNVERIFIED`·`FAILED`·`ABORTED` 에만(`PARTIAL` 은 적지 않음). 사본 상세는 JSONB 가 아니라 JSON(키 순서 보존).
- 호스트 REST: `GET /host/executions` 끝에 `restore{at, rows}`(행 7칸), 실행 끝에 `restoredFrom{instanceId, executionId}`(바로 앞 인스턴스의 실행). 판정 이유 `복원 못 한 실행이 있다: <jobOrderId>`. `GET /host/incidents` 끝에 `earlierTotal`·`earlier`(20칸), 상세 `?instanceId=`(빈 값 400 `BAD_REQUEST`). 판단 본문 `instanceId` 필수(없으면 400, 다르면 409 `INSTANCE_MISMATCH`). `GET /host/job-responses`(17칸, 잠금 없이 DB 만, 처분 `SENT`·`RESTART_DUPLICATE`). 일지 쓰기 실패는 500 `JOURNAL_WRITE_FAILED`.
- 운영 서비스: 판단 본문 `instanceId` 검사는 사유보다 먼저(400 `RESOLVE_BAD_REQUEST`), 조작 기록 `request` 끝에 `instanceId`, 응답 없음 뒤 재조회는 목록의 `instanceId` 가 요청과 같을 때만 `incidents`, 다르면 `earlier` 의 그 인스턴스 사본. `GET /api/job-responses`(빈 `jobOrderId`·범위 밖 `limit` 은 400 `JOB_RESPONSE_BAD_REQUEST`, 불통 503 `HOST_SILENT`). 조작 기록 op 는 더하지 않는다.
- 화면: 실행 목록 위 띠 «재기동: 이전 인스턴스의 실행 n건을 다시 지었습니다» 와 미룬 것·포기한 것의 사유, 다시 지은 행의 `(이전 exec-k)`. 인시던트는 (인스턴스, id)로 고르고 이전 인스턴스 부분은 읽기 전용(보류 열·판단 폼 없음). 운영 영역 맨 끝 구역 `작업 응답 송신 기록`, 처분 글자 `송신`·`재기동 중복(송신 안 함)`.
- Playwright: 운영자 보류 단계 뒤 송신 기록 확인 한 단계(스펙 T11, 재기동 단계 없음). S4a 판단 단계는 화면이 상세의 `instanceId` 를 실어 바꾸지 않고 통과한다.
- 통합 시험: `RestartRecoveryTest` 6단계가 스펙 §3 의 여섯을 한 스택에서 보이고 재기동은 `E2eStack.restartHost()`. 가상 시계만 밀고 실제 시간 대기는 다시 관측 폴링(상한 10초), 확인 중 단위(3초), 송신 기록 행(상한 10초)뿐이다. 재기동 앞뒤의 새 명령 여부는 site 의 `Site.taskHistory`(엔진 잠금 아래 읽기만)로 본다. S4a `FaultIncidentTest` 의 판단 요청은 본문에 `instanceId` 를 싣는다(단언은 그대로).
- 실측: site 38 → 38, mission-host 64 → 77(Task 2) → 79(Task 5b), ops-service 231 → 241, e2e 57 → 63(`RestartRecoveryTest` 6, 약 81초), vitest 138 → 151, Playwright 1. 결함 주입 75건(호스트 H33, 운영 서비스 O14, 화면 U17, Playwright P2, 통합 I9)을 스파이크에서 돌려 I4 하나만 등가(작업 응답의 미완 사유가 이 흐름에서 인스턴스와 무관)이고 나머지는 모두 탐지했다. H28~H33·P2 는 계획 검토 고침(Task 5b)을 잡는 것이다. 통합 아홉은 그 모듈 시험으로도 돌려(I1m~I9m) 모듈 시험이 못 잡고 e2e 만 잡은 것은 없다. 호스트 H1~H27 은 스파이크에서 확정 전 P6 스냅숏(`e5011eb`, `ResumeTest` 13개)에 대고 돌렸고, 그 뒤 호스트 시험은 확정 P6 에서 통과했다. 워크트리의 대표 H5(Task 7)는 확정 `74e4d3d` 에 대고 돈다.
- 문장: 커밋 메시지는 위 다섯 곳을 바꾼 스파이크 커밋 메시지, PR 본문은 Codex 와 Fable 초안을 취합한다.

**작업 위치 규칙(필수):**
- 모든 작업은 picasso-ops 워크트리 `C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b`(브랜치 `feat/s4b-restart-recovery`)에서 한다. picasso-ops 메인 체크아웃과 다른 저장소는 건드리지 않는다(Task 8 의 병합 뒤 메인 체크아웃 갱신만 예외). 서브모듈 안에서는 `74e4d3d` 체크아웃 말고 아무것도 하지 않는다. 하위 에이전트의 Bash 는 호출마다 작업 디렉터리가 돌아가므로 명령마다 `cd <워크트리> &&` 를 붙이거나 `git -C` 를 쓴다.
- `./gradlew --stop` 금지(데몬 풀이 다른 체크아웃과 공유된다). 같은 워크트리에서 Gradle 을 겹쳐 돌리지 않는다. site·mission-host·ops-service·e2e 시험은 Testcontainers 로 Postgres 컨테이너를 띄우므로 Docker 데몬이 떠 있어야 한다. Playwright 는 compose 의 Postgres(`127.0.0.1:55432`)와 고정 포트(8781~8785, 4173)를 쓰므로 다른 Playwright·런처·결함 주입과 겹쳐 돌리지 않는다. Bash 도구의 시간 한도(600초)를 넘는 명령은 백그라운드로 돌리고 끝났다는 알림을 받은 뒤 다음 명령을 돌린다.
- `git add -A` 금지. 파일을 이름으로 더한다.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다. Expected 의 수는 `<testcase>` 수다. 수는 `PYTHONUTF8=1 python -c "import glob,xml.etree.ElementTree as E;print(sum(len(list(E.parse(f).getroot().iter('testcase'))) for f in glob.glob('C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b/MOD/build/test-results/test/*.xml')))"` 로 센다(MOD 자리에 모듈 이름). vitest 는 `npm test` 출력의 `Tests` 줄로 센다. 수가 Expected 와 다르면 멈추고 보고한다.
- 이 저장소는 LF 다(`.gitattributes` 의 `eol=lf`). 뽑아 둔 파일은 그대로 복사하고, 대조는 줄바꿈을 빼지 않고 바이트 그대로 한다.
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`(아래 메시지에 이미 들어 있다). 커밋 메시지는 heredoc 으로 쓴다(`git commit -F -` 에 표준 입력). 형식 훅이 제목이 `type(scope): 명사구` 가 아니거나, 본문 줄이 서술형(`다` 로 끝남)이거나, 트레일러가 없거나, 겹화살괄호·em-dash·굵게가 있으면 막는다. 막히면 메시지를 고치지 말고 멈추고 보고한다. `--amend` 와 `--no-verify` 금지.
- 이 계획의 코드는 스크래치 스파이크(`C:/Users/Eisen/AppData/Local/Temp/s4b`, 브랜치 `spike/s4b`, HEAD `0de3ce6`)에서 시험, 전체 빌드, Playwright, 결함 주입을 다 돌린 것이다. Task 가 끝날 때마다 커밋된 파일을 그 Task 의 마지막 스파이크 커밋과 기계 대조한다(`s4b-cmp.sh --at`). Task 5b 가 Task 2·4 의 파일 일부를 다시 고치므로 Task 끝의 대조는 스파이크 HEAD 가 아니라 그 Task 의 마지막 스파이크 커밋과 견주고, 전체는 Task 6 의 트리 대조가 스파이크 HEAD 와 견준다.
- 실행 방식: 묶음(Task 1·2 / Task 3 / Task 4 / Task 5 / Task 5b)마다 구현 하위 에이전트 1명(`model: "sonnet"`). 묶음 대조, 새 클론 빌드(Task 6), 결함 주입(Task 7), PR(Task 8)은 컨트롤러.
- **블록을 손으로 옮겨 적지 않는다.** 컨트롤러가 이 계획의 블록을 기계로 뽑아 `C:/Users/Eisen/AppData/Local/Temp/s4b-patches/` 에 두었다. 새 파일은 `s4b-patches/files/<경로>` 를 워크트리의 같은 경로로 `cp` 하고, 기존 파일은 `s4b-patches/<이름>.patch` 를 `git apply --check` 로 본 뒤 `git apply` 한다. 아래 블록은 읽고 검토하기 위한 것이다. 뽑은 파일이 없거나 `git apply --check` 가 실패하면 멈추고 보고한다.
- Step 은 순서대로 하나씩 끝내고 다음으로 간다. 다음 커밋이나 다음 Task 의 파일을 미리 복사하거나 패치하지 않는다(같은 파일을 두 커밋이 차례로 고친다).

---

## Chunk 1: 구현

### Task 0: 워크트리와 기준선(컨트롤러)

**Files:** 없음(환경)

- [ ] **Step 1: 워크트리**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" && git fetch -q origin && git log --oneline -2 docs/s4b-restart-recovery-design && git diff --name-status 8ed1b91 docs/s4b-restart-recovery-design
git -C "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" worktree add "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" -b feat/s4b-restart-recovery docs/s4b-restart-recovery-design
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git submodule update --init -q && git -C picasso log --oneline -1
```
Expected: 브랜치 `docs/s4b-restart-recovery-design` 이 이미 있다(컨트롤러가 `8ed1b91` 에서 미리 만든다. 없으면 멈추고 보고한다, 이 계획은 그 브랜치를 만들지 않는다). `8ed1b91` 과의 차이는 정확히 문서 넷의 추가다: `docs/superpowers/specs/2026-10-09-s4b-restart-recovery-design.md`, `docs/superpowers/specs/2026-10-09-s4b-json-contract.md`, `docs/superpowers/plans/2026-10-09-s4b-restart-recovery.md`(이 계획), `docs/superpowers/plans/2026-10-09-p6-resume.md`. 그 밖의 경로가 보이면 멈추고 보고한다(Task 6 의 트리 대조가 이 넷만 뺀다). 워크트리의 기준은 그 브랜치이고 서브모듈이 `195c1ee Merge pull request #85`(Task 1 에서 올린다).

- [ ] **Step 2: 기준선 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && ./gradlew :site:test :mission-host:test :ops-service:test :e2e:test -q
```
Expected: site 38, mission-host 64, ops-service 231, e2e 57, 실패 0. 백그라운드로 돌린다. 이어서 `cd ui && npm ci && npm test` 로 vitest 기준선 138.

- [ ] **Step 3: 대조 도구와 뽑은 블록**

`C:/Users/Eisen/AppData/Local/Temp/s4b-cmp.sh`, `C:/Users/Eisen/AppData/Local/Temp/s4b-final.sh`, `C:/Users/Eisen/AppData/Local/Temp/s4b-inject/inject.py` 와 `C:/Users/Eisen/AppData/Local/Temp/s4b-patches/`(패치 16개, `files/` 아래 새 파일 10개)가 있는지 본다. 없으면 멈추고 보고한다.

### Task 1: picasso 서브모듈을 P6 머지 커밋으로

**Files:**
- Modify: `picasso`

- [ ] **Step 1: 서브모듈을 `74e4d3d` 로**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git -C picasso fetch -q origin && git -C picasso checkout -q 74e4d3d && git -C picasso log --oneline -1 && git diff --submodule=short -- picasso
```
Expected: `74e4d3d Merge pull request #86 from LivingLikeKrillin/feat/p6-resume`, diff 는 `-Subproject commit 195c1ee...` 와 `+Subproject commit 74e4d3d...` 한 쌍. picasso 저장소 안의 파일은 고치지 않는다.

- [ ] **Step 2: 커밋 `chore(picasso): P6 머지 커밋으로 서브모듈 갱신`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add picasso && git commit -F - <<'EOF'
chore(picasso): P6 머지 커밋으로 서브모듈 갱신

- picasso PR #86 머지 커밋 `74e4d3d`, 재기동 뒤 실행을 다시 짓는 입구 resume 반영

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 3: 묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s4b-cmp.sh" --at 6ddc2aa picasso
```
Expected: `같음 picasso`(gitlink 를 `git ls-tree` 로 견준다).

### Task 2: 실행 호스트: 실행 일지, 기동 복원, 송신 기록, 인시던트 사본, 판단의 인스턴스

**Files:**
- Create: `mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostRecords.kt`, `mission-host/src/main/resources/db/mission/V2__execution_journal.sql`, `mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt`
- Modify: `mission-host/src/main/kotlin/dev/picasso/ops/host/mission/StoredMissionCatalog.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/store/MissionStore.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/IncidentViews.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/web/IncidentController.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/IncidentResolveTest.kt`

- [ ] **Step 1: (커밋 1/4) 새 파일 2개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/store && cp "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostRecords.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostRecords.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && mkdir -p mission-host/src/main/resources/db/mission && cp "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/files/mission-host/src/main/resources/db/mission/V2__execution_journal.sql" mission-host/src/main/resources/db/mission/V2__execution_journal.sql
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostRecords.kt`:

```kotlin
package dev.picasso.ops.host.store

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** 일지 행 이후의 일(S4b 스펙 T2·T3). */
enum class JournalEventKind { RESTORED, DEFERRED, GAVE_UP, SETTLED }

/**
 * 실행 일지 한 행(S4b 스펙 T2). [jobOrder] 는 picasso `JobOrder` 의 칸 그대로의 JSON 글자다.
 *
 * @param missionVersion 받은 때의 임무 버전. `null` 이면 코드 정의다
 * @param instanceId·executionId 받은 인스턴스와 그때의 실행 id
 */
data class JournalRow(
    val journalId: Long,
    val jobOrderId: String,
    val robotId: String,
    val jobOrder: String,
    val workMasterId: String,
    val missionVersion: Int?,
    val instanceId: String,
    val executionId: String,
    val receivedAt: Instant,
)

/** 일지 이벤트 한 행. [executionId] 는 RESTORED·SETTLED 에만, [detail] 은 RESTORED 밖에만 있다. */
data class JournalEvent(
    val eventId: Long,
    val jobOrderId: String,
    val kind: JournalEventKind,
    val instanceId: String,
    val executionId: String?,
    val detail: String?,
    val recordedAt: Instant,
)

/**
 * 작업 응답의 내용 키(S4b 스펙 T6). 인스턴스마다 달라지는 것(응답 id, 실행 id, 미완 사유 문자열, `connection`)은 뺀다.
 * 단위 id 목록은 정렬해 둔다. 두 키가 같은지는 이 값의 `equals` 다.
 */
data class ResponseContent(
    val jobOrderId: String,
    val version: Int,
    val physicalState: String,
    val requiredEvidence: String,
    val reachedEvidence: String,
    val completedUnits: List<String>,
    val unverifiedUnits: List<String>,
    val incompleteUnits: List<String>,
    val inDoubtUnits: List<String>,
    val operatorRequired: Boolean,
    val residualHold: String,
    val blockedBy: List<String>,
)

/** 송신 기록의 처분. [RESTART_DUPLICATE] 는 적었으나 송신하지 않은 것이다. */
enum class ResponseDisposition { SENT, RESTART_DUPLICATE }

data class ResponseLogRow(
    val logId: Long,
    val instanceId: String,
    val jobResponseId: String,
    val executionId: String,
    val content: ResponseContent,
    val disposition: ResponseDisposition,
    val recordedAt: Instant,
)

/** 사본의 판단 한 행. [at] 은 호스트 시계, [wallClockAt] 은 실제 시각이다. */
data class CopyResolutionRow(val decision: String, val at: Instant, val wallClockAt: Instant, val decidedById: String, val decidedByKind: String)

/** 인시던트 사본 한 행. [detail] 은 봉인 뒤 처음 적을 때의 호스트 상세 본문 JSON 글자다. */
data class IncidentCopyRow(
    val copyId: Long,
    val instanceId: String,
    val incidentId: String,
    val detail: String,
    val resolution: CopyResolutionRow?,
)

/**
 * 실행 호스트의 기록(S4b 스펙 §6.1). 실행 일지와 그 이벤트, 송신 기록, 인시던트 사본과 판단의 다섯 표다. 덧붙이기와 읽기만 있다.
 *
 * pump 뒤 기록은 [inTransaction] 하나로 묶는다(§6.2). 그 밖의 호출은 문장 하나가 한 트랜잭션이다. 호스트 잠금 아래에서 부를 수
 * 있고, 연결을 쥔 채 호스트 잠금을 기다리는 길은 없다(S3b 와 같은 잠금 순서).
 */
class HostRecords(private val jdbc: JdbcClient, private val transactions: TransactionTemplate, private val json: ObjectMapper) {

    /** [action] 을 한 트랜잭션으로 돈다. 예외가 나가면 아무것도 남지 않는다. */
    fun <T> inTransaction(action: () -> T): T = transactions.execute { action() } as T

    // ── 실행 일지

    fun journal(
        jobOrderId: String,
        robotId: String,
        jobOrder: String,
        workMasterId: String,
        missionVersion: Int?,
        instanceId: String,
        executionId: String,
    ): JournalRow =
        jdbc.sql(
            """
            INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
            VALUES (:jobOrderId, :robotId, CAST(:jobOrder AS jsonb), :workMaster, :missionVersion, :instanceId, :executionId)
            RETURNING $JOURNAL_COLUMNS
            """.trimIndent(),
        )
            .param("jobOrderId", jobOrderId)
            .param("robotId", robotId)
            .param("jobOrder", jobOrder)
            .param("workMaster", workMasterId)
            .param("missionVersion", missionVersion)
            .param("instanceId", instanceId)
            .param("executionId", executionId)
            .query { rs, _ -> journalRow(rs) }
            .single()

    fun event(jobOrderId: String, kind: JournalEventKind, instanceId: String, executionId: String?, detail: String?): JournalEvent =
        jdbc.sql(
            """
            INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, execution_id, detail)
            VALUES (:jobOrderId, :kind, :instanceId, :executionId, :detail)
            RETURNING $EVENT_COLUMNS
            """.trimIndent(),
        )
            .param("jobOrderId", jobOrderId)
            .param("kind", kind.name)
            .param("instanceId", instanceId)
            .param("executionId", executionId)
            .param("detail", detail)
            .query { rs, _ -> event(rs) }
            .single()

    /** 정착도 포기도 없는 일지 행. 받은 순서다. 기동 복원의 대상이다(T3). */
    fun openJournal(): List<JournalRow> = journalWhere(
        """
        NOT EXISTS (SELECT 1 FROM mission.execution_journal_event e
                    WHERE e.job_order_id = j.job_order_id AND e.kind IN ('SETTLED', 'GAVE_UP'))
        """.trimIndent(),
    )

    /** 포기했고 정착하지 않은 일지 행. 받은 순서다. 판정이 그 기체를 뺄지 본다(T4). */
    fun gaveUpJournal(): List<JournalRow> = journalWhere(
        """
        EXISTS (SELECT 1 FROM mission.execution_journal_event e WHERE e.job_order_id = j.job_order_id AND e.kind = 'GAVE_UP')
        AND NOT EXISTS (SELECT 1 FROM mission.execution_journal_event e WHERE e.job_order_id = j.job_order_id AND e.kind = 'SETTLED')
        """.trimIndent(),
    )

    fun journalRow(jobOrderId: String): JournalRow? =
        jdbc.sql("SELECT $JOURNAL_COLUMNS FROM mission.execution_journal j WHERE job_order_id = :jobOrderId")
            .param("jobOrderId", jobOrderId)
            .query { rs, _ -> journalRow(rs) }
            .optional()
            .orElse(null)

    /** 그 작업 지시의 이벤트. 적은 순서다. */
    fun events(jobOrderId: String): List<JournalEvent> =
        jdbc.sql("SELECT $EVENT_COLUMNS FROM mission.execution_journal_event WHERE job_order_id = :jobOrderId ORDER BY event_id")
            .param("jobOrderId", jobOrderId)
            .query { rs, _ -> event(rs) }
            .list()

    private fun journalWhere(condition: String): List<JournalRow> =
        jdbc.sql("SELECT $JOURNAL_COLUMNS FROM mission.execution_journal j WHERE $condition ORDER BY journal_id")
            .query { rs, _ -> journalRow(rs) }
            .list()

    // ── 송신 기록

    /** 그 인스턴스가 그 작업 지시에 대해 적은 행이 있는가. 없으면 다음 응답이 그 인스턴스의 첫 응답이다. */
    fun loggedIn(instanceId: String, jobOrderId: String): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM mission.job_response_log WHERE instance_id = :instanceId AND job_order_id = :jobOrderId)")
            .param("instanceId", instanceId)
            .param("jobOrderId", jobOrderId)
            .query(Boolean::class.java)
            .single()

    /** 그 작업 지시의 가장 최근 송신 행(인스턴스 무관). 재기동 중복 행은 보지 않는다. */
    fun lastSent(jobOrderId: String): ResponseLogRow? =
        jdbc.sql(
            """
            SELECT $LOG_COLUMNS FROM mission.job_response_log
            WHERE job_order_id = :jobOrderId AND disposition = 'SENT' ORDER BY log_id DESC LIMIT 1
            """.trimIndent(),
        )
            .param("jobOrderId", jobOrderId)
            .query { rs, _ -> logRow(rs) }
            .optional()
            .orElse(null)

    fun logResponse(instanceId: String, jobResponseId: String, executionId: String, content: ResponseContent, disposition: ResponseDisposition): ResponseLogRow =
        jdbc.sql(
            """
            INSERT INTO mission.job_response_log (instance_id, job_response_id, job_order_id, execution_id, version, physical_state,
                required_evidence, reached_evidence, completed_units, unverified_units, incomplete_units, in_doubt_units,
                operator_required, residual_hold, blocked_by, disposition)
            VALUES (:instanceId, :jobResponseId, :jobOrderId, :executionId, :version, :physicalState, :required, :reached,
                CAST(:completed AS jsonb), CAST(:unverified AS jsonb), CAST(:incomplete AS jsonb), CAST(:inDoubt AS jsonb),
                :operatorRequired, :residualHold, CAST(:blockedBy AS jsonb), :disposition)
            RETURNING $LOG_COLUMNS
            """.trimIndent(),
        )
            .param("instanceId", instanceId)
            .param("jobResponseId", jobResponseId)
            .param("jobOrderId", content.jobOrderId)
            .param("executionId", executionId)
            .param("version", content.version)
            .param("physicalState", content.physicalState)
            .param("required", content.requiredEvidence)
            .param("reached", content.reachedEvidence)
            .param("completed", json.writeValueAsString(content.completedUnits))
            .param("unverified", json.writeValueAsString(content.unverifiedUnits))
            .param("incomplete", json.writeValueAsString(content.incompleteUnits))
            .param("inDoubt", json.writeValueAsString(content.inDoubtUnits))
            .param("operatorRequired", content.operatorRequired)
            .param("residualHold", content.residualHold)
            .param("blockedBy", json.writeValueAsString(content.blockedBy))
            .param("disposition", disposition.name)
            .query { rs, _ -> logRow(rs) }
            .single()

    /** 송신 기록. 최근부터 많아야 [limit] 개다. [jobOrderId] 를 주면 그 작업 지시만이다. */
    fun responseLog(jobOrderId: String?, limit: Int): List<ResponseLogRow> =
        jdbc.sql(
            """
            SELECT $LOG_COLUMNS FROM mission.job_response_log
            WHERE CAST(:jobOrderId AS text) IS NULL OR job_order_id = :jobOrderId
            ORDER BY log_id DESC LIMIT :limit
            """.trimIndent(),
        )
            .param("jobOrderId", jobOrderId)
            .param("limit", limit)
            .query { rs, _ -> logRow(rs) }
            .list()

    fun responseLogCount(jobOrderId: String?): Int =
        jdbc.sql("SELECT count(*) FROM mission.job_response_log WHERE CAST(:jobOrderId AS text) IS NULL OR job_order_id = :jobOrderId")
            .param("jobOrderId", jobOrderId)
            .query(Int::class.java)
            .single()

    // ── 인시던트 사본

    fun copyIncident(instanceId: String, incidentId: String, executionId: String, jobOrderId: String, unitId: String, detail: String) {
        jdbc.sql(
            """
            INSERT INTO mission.incident_copy (instance_id, incident_id, execution_id, job_order_id, unit_id, detail)
            VALUES (:instanceId, :incidentId, :executionId, :jobOrderId, :unitId, CAST(:detail AS json))
            """.trimIndent(),
        )
            .param("instanceId", instanceId)
            .param("incidentId", incidentId)
            .param("executionId", executionId)
            .param("jobOrderId", jobOrderId)
            .param("unitId", unitId)
            .param("detail", detail)
            .update()
    }

    fun copyResolution(instanceId: String, incidentId: String, resolution: CopyResolutionRow) {
        jdbc.sql(
            """
            INSERT INTO mission.incident_copy_resolution (instance_id, incident_id, decision, decided_at, wall_clock_at, decided_by_id, decided_by_kind)
            VALUES (:instanceId, :incidentId, :decision, :at, :wallClockAt, :byId, :byKind)
            """.trimIndent(),
        )
            .param("instanceId", instanceId)
            .param("incidentId", incidentId)
            .param("decision", resolution.decision)
            .param("at", OffsetDateTime.ofInstant(resolution.at, ZoneOffset.UTC))
            .param("wallClockAt", OffsetDateTime.ofInstant(resolution.wallClockAt, ZoneOffset.UTC))
            .param("byId", resolution.decidedById)
            .param("byKind", resolution.decidedByKind)
            .update()
    }

    /** [instanceId] 가 아닌 인스턴스의 사본. 최근에 적은 것부터 많아야 [limit] 개다. */
    fun earlierCopies(instanceId: String, limit: Int): List<IncidentCopyRow> =
        jdbc.sql("$COPY_SELECT WHERE c.instance_id <> :instanceId ORDER BY c.copy_id DESC LIMIT :limit")
            .param("instanceId", instanceId)
            .param("limit", limit)
            .query { rs, _ -> copyRow(rs) }
            .list()

    fun earlierCopyCount(instanceId: String): Int =
        jdbc.sql("SELECT count(*) FROM mission.incident_copy WHERE instance_id <> :instanceId")
            .param("instanceId", instanceId)
            .query(Int::class.java)
            .single()

    fun incidentCopy(instanceId: String, incidentId: String): IncidentCopyRow? =
        jdbc.sql("$COPY_SELECT WHERE c.instance_id = :instanceId AND c.incident_id = :incidentId")
            .param("instanceId", instanceId)
            .param("incidentId", incidentId)
            .query { rs, _ -> copyRow(rs) }
            .optional()
            .orElse(null)

    private fun journalRow(rs: ResultSet) = JournalRow(
        journalId = rs.getLong("journal_id"),
        jobOrderId = rs.getString("job_order_id"),
        robotId = rs.getString("robot_id"),
        jobOrder = rs.getString("job_order"),
        workMasterId = rs.getString("work_master_id"),
        missionVersion = rs.getObject("mission_version") as Int?,
        instanceId = rs.getString("instance_id"),
        executionId = rs.getString("execution_id"),
        receivedAt = instant(rs, "received_at"),
    )

    private fun event(rs: ResultSet) = JournalEvent(
        eventId = rs.getLong("event_id"),
        jobOrderId = rs.getString("job_order_id"),
        kind = JournalEventKind.valueOf(rs.getString("kind")),
        instanceId = rs.getString("instance_id"),
        executionId = rs.getString("execution_id"),
        detail = rs.getString("detail"),
        recordedAt = instant(rs, "recorded_at"),
    )

    private fun logRow(rs: ResultSet) = ResponseLogRow(
        logId = rs.getLong("log_id"),
        instanceId = rs.getString("instance_id"),
        jobResponseId = rs.getString("job_response_id"),
        executionId = rs.getString("execution_id"),
        content = ResponseContent(
            jobOrderId = rs.getString("job_order_id"),
            version = rs.getInt("version"),
            physicalState = rs.getString("physical_state"),
            requiredEvidence = rs.getString("required_evidence"),
            reachedEvidence = rs.getString("reached_evidence"),
            completedUnits = strings(rs, "completed_units"),
            unverifiedUnits = strings(rs, "unverified_units"),
            incompleteUnits = strings(rs, "incomplete_units"),
            inDoubtUnits = strings(rs, "in_doubt_units"),
            operatorRequired = rs.getBoolean("operator_required"),
            residualHold = rs.getString("residual_hold"),
            blockedBy = strings(rs, "blocked_by"),
        ),
        disposition = ResponseDisposition.valueOf(rs.getString("disposition")),
        recordedAt = instant(rs, "recorded_at"),
    )

    private fun copyRow(rs: ResultSet) = IncidentCopyRow(
        copyId = rs.getLong("copy_id"),
        instanceId = rs.getString("instance_id"),
        incidentId = rs.getString("incident_id"),
        detail = rs.getString("detail"),
        resolution = rs.getString("decision")?.let {
            CopyResolutionRow(it, instant(rs, "decided_at"), instant(rs, "wall_clock_at"), rs.getString("decided_by_id"), rs.getString("decided_by_kind"))
        },
    )

    private fun strings(rs: ResultSet, column: String): List<String> = json.readValue(rs.getString(column))

    private fun instant(rs: ResultSet, column: String): Instant = rs.getObject(column, OffsetDateTime::class.java).toInstant()

    private companion object {
        const val JOURNAL_COLUMNS =
            "journal_id, job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id, received_at"
        const val EVENT_COLUMNS = "event_id, job_order_id, kind, instance_id, execution_id, detail, recorded_at"
        const val LOG_COLUMNS =
            "log_id, instance_id, job_response_id, job_order_id, execution_id, version, physical_state, required_evidence, " +
                "reached_evidence, completed_units, unverified_units, incomplete_units, in_doubt_units, operator_required, " +
                "residual_hold, blocked_by, disposition, recorded_at"
        const val COPY_SELECT =
            "SELECT c.copy_id, c.instance_id, c.incident_id, c.detail, r.decision, r.decided_at, r.wall_clock_at, r.decided_by_id, r.decided_by_kind " +
                "FROM mission.incident_copy c LEFT JOIN mission.incident_copy_resolution r " +
                "ON r.instance_id = c.instance_id AND r.incident_id = c.incident_id"
    }
}
```

`mission-host/src/main/resources/db/mission/V2__execution_journal.sql`:

```sql
-- 실행 일지, 송신 기록, 인시던트 사본(S4b 스펙 §6.1, T2·T6·T7). 표 다섯 모두 덧붙이기만 한다. 시각은 DB 의 clock_timestamp() 다.
-- 인스턴스는 미들웨어가 뜬 한 번(`instanceId`)이다. `exec-N`·`resp-N`·`incident-N` 은 인스턴스 안의 셈이라 늘 인스턴스와 짝짓는다.

-- 실행 일지. 새 실행으로 ACCEPTED 인 제출마다 한 행이다. 키는 작업 지시 id 다(운영 서비스는 작업 지시 id 를 다시 쓰지 않는다).
-- 작업 지시는 picasso JobOrder 의 칸 그대로의 JSON 이다. 임무 버전이 NULL 이면 코드 정의다.
-- journal_id 는 받은 순서다. 기동 복원은 이 순서로 다시 짓는다(T3).
CREATE TABLE execution_journal (
    journal_id     BIGINT      GENERATED ALWAYS AS IDENTITY UNIQUE,
    job_order_id   TEXT        PRIMARY KEY CHECK (job_order_id <> ''),
    robot_id       TEXT        NOT NULL CHECK (robot_id <> ''),
    job_order      JSONB       NOT NULL,
    work_master_id TEXT        NOT NULL CHECK (work_master_id <> ''),
    mission_version INTEGER   CHECK (mission_version > 0),
    instance_id    TEXT        NOT NULL CHECK (instance_id <> ''),
    execution_id   TEXT        NOT NULL CHECK (execution_id <> ''),
    received_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 일지 행 이후의 일. RESTORED·DEFERRED·GAVE_UP 은 기동 복원(T3), SETTLED 는 pump 뒤 기록(§6.2)이 적는다.
-- execution_id 는 RESTORED 면 새 실행, SETTLED 면 정착한 실행이다. detail 은 DEFERRED·GAVE_UP 의 사유, SETTLED 의 물리 상태다.
-- 정착도 포기도 없는 일지 행이 다시 짓기의 대상이다.
CREATE TABLE execution_journal_event (
    event_id     BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_order_id TEXT        NOT NULL REFERENCES execution_journal (job_order_id),
    kind         TEXT        NOT NULL CHECK (kind IN ('RESTORED', 'DEFERRED', 'GAVE_UP', 'SETTLED')),
    instance_id  TEXT        NOT NULL CHECK (instance_id <> ''),
    execution_id TEXT,
    detail       TEXT,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((kind IN ('RESTORED', 'SETTLED')) = (execution_id IS NOT NULL)),
    CHECK ((kind = 'RESTORED') = (detail IS NULL))
);

CREATE INDEX execution_journal_event_order ON execution_journal_event (job_order_id, event_id);

-- 송신 기록(T6). pending() 의 응답마다 한 행이다. 내용 키의 칸을 그대로 둔다. 단위 id 목록은 정렬한 JSON 배열이고,
-- incomplete_units 는 사유 없이 단위 id 만 든다. disposition 은 SENT(송신) 또는 RESTART_DUPLICATE(재기동 중복, 송신 안 함)다.
CREATE TABLE job_response_log (
    log_id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instance_id       TEXT        NOT NULL CHECK (instance_id <> ''),
    job_response_id   TEXT        NOT NULL CHECK (job_response_id <> ''),
    job_order_id      TEXT        NOT NULL CHECK (job_order_id <> ''),
    execution_id      TEXT        NOT NULL CHECK (execution_id <> ''),
    version           INTEGER     NOT NULL,
    physical_state    TEXT        NOT NULL,
    required_evidence TEXT        NOT NULL,
    reached_evidence  TEXT        NOT NULL,
    completed_units   JSONB       NOT NULL,
    unverified_units  JSONB       NOT NULL,
    incomplete_units  JSONB       NOT NULL,
    in_doubt_units    JSONB       NOT NULL,
    operator_required BOOLEAN     NOT NULL,
    residual_hold     TEXT        NOT NULL,
    blocked_by        JSONB       NOT NULL,
    disposition       TEXT        NOT NULL CHECK (disposition IN ('SENT', 'RESTART_DUPLICATE')),
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (instance_id, job_response_id)
);

CREATE INDEX job_response_log_order ON job_response_log (job_order_id, log_id);

-- 인시던트 사본(T7). 키는 (인스턴스, 인시던트 id)다. detail 은 봉인 뒤 처음 적을 때의 호스트 상세 본문(S4a 의 IncidentDetailView)이다.
-- JSONB 가 아니라 JSON 이다. 받은 글자를 그대로 두어 맵 칸(orderParameters, unitParameters 등)의 키 순서가 REST 와 같게 남는다.
-- copy_id 는 적은 순서다.
CREATE TABLE incident_copy (
    copy_id      BIGINT      GENERATED ALWAYS AS IDENTITY UNIQUE,
    instance_id  TEXT        NOT NULL CHECK (instance_id <> ''),
    incident_id  TEXT        NOT NULL CHECK (incident_id <> ''),
    execution_id TEXT        NOT NULL,
    job_order_id TEXT        NOT NULL,
    unit_id      TEXT        NOT NULL,
    detail       JSON        NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (instance_id, incident_id)
);

-- 사본의 판단. 봉인 때의 판단 칸은 낡으므로 따로 적는다. 인시던트 하나에 판단은 많아야 하나다(picasso IncidentLog).
CREATE TABLE incident_copy_resolution (
    instance_id     TEXT        NOT NULL,
    incident_id     TEXT        NOT NULL,
    decision        TEXT        NOT NULL CHECK (decision <> ''),
    decided_at      TIMESTAMPTZ NOT NULL,
    wall_clock_at   TIMESTAMPTZ NOT NULL,
    decided_by_id   TEXT        NOT NULL CHECK (decided_by_id <> ''),
    decided_by_kind TEXT        NOT NULL CHECK (decided_by_kind <> ''),
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (instance_id, incident_id),
    FOREIGN KEY (instance_id, incident_id) REFERENCES incident_copy (instance_id, incident_id)
);

-- 덧붙이기만 한다. V1 의 mission_append_only() 를 그대로 쓴다.
CREATE TRIGGER execution_journal_no_update_delete
    BEFORE UPDATE OR DELETE ON execution_journal
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER execution_journal_no_truncate
    BEFORE TRUNCATE ON execution_journal
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER execution_journal_event_no_update_delete
    BEFORE UPDATE OR DELETE ON execution_journal_event
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER execution_journal_event_no_truncate
    BEFORE TRUNCATE ON execution_journal_event
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER job_response_log_no_update_delete
    BEFORE UPDATE OR DELETE ON job_response_log
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER job_response_log_no_truncate
    BEFORE TRUNCATE ON job_response_log
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER incident_copy_no_update_delete
    BEFORE UPDATE OR DELETE ON incident_copy
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER incident_copy_no_truncate
    BEFORE TRUNCATE ON incident_copy
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER incident_copy_resolution_no_update_delete
    BEFORE UPDATE OR DELETE ON incident_copy_resolution
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER incident_copy_resolution_no_truncate
    BEFORE TRUNCATE ON incident_copy_resolution
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();
```

- [ ] **Step 2: (커밋 1/4) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task2a.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task2a.patch"
```

```diff
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/StoredMissionCatalog.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/StoredMissionCatalog.kt
index 42a8c55..4877bac 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/StoredMissionCatalog.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/StoredMissionCatalog.kt
@@ -48,6 +48,21 @@ class StoredMissionCatalog(code: List<LogicalCapability> = MissionCatalog.codeCa
         activated = activated + (workMasterId to ActiveMission(capability, version))
     }
 
+    /**
+     * 받은 때의 임무로 [ActiveMission] 을 세운다(S4b 스펙 T3). [row] 가 `null` 이면 코드 정의이고 버전이 없다. 지금 활성 버전은 보지
+     * 않는다. 코드 정의가 없는 WorkMaster 이거나 저장된 정의를 읽지 못하면 [IllegalStateException] 이고 그 문장이 포기 사유다.
+     *
+     * @param row 일지의 임무 버전으로 읽은 행. 그 WorkMaster 의 행이어야 한다
+     */
+    fun mission(workMasterId: String, row: VersionRow?): ActiveMission {
+        if (row == null) {
+            val code = coded[workMasterId] ?: throw IllegalStateException("코드 정의가 없는 WorkMaster 다: $workMasterId")
+            return ActiveMission(code, missionVersion = null)
+        }
+        check(row.workMasterId == workMasterId) { "임무 버전 행의 WorkMaster(${row.workMasterId})가 $workMasterId 가 아니다" }
+        return ActiveMission(capability(row), row.version)
+    }
+
     private fun capability(row: VersionRow): DefinedCapability {
         val where = "저장된 임무 버전 ${row.workMasterId} 버전 ${row.version}"
         val definition = when (val parsed = MissionDefinitionParser.parse(row.definition)) {
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/store/MissionStore.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/store/MissionStore.kt
index d73be91..f0e8975 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/store/MissionStore.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/store/MissionStore.kt
@@ -152,6 +152,15 @@ class MissionStore(private val jdbc: JdbcClient) {
             .query { rs, _ -> version(rs) }
             .list()
 
+    /** 버전 하나. 없으면 `null` 이다. 기동 복원이 일지의 임무 버전을 이것으로 읽는다(S4b 스펙 T3). */
+    fun version(workMasterId: String, version: Int): VersionRow? =
+        jdbc.sql("SELECT $VERSION_COLUMNS FROM mission.mission_version WHERE work_master_id = :workMaster AND version = :version")
+            .param("workMaster", workMasterId)
+            .param("version", version)
+            .query { rs, _ -> version(rs) }
+            .optional()
+            .orElse(null)
+
     /** WorkMaster 마다 가장 높은 버전 하나. 기동 때 카탈로그를 이것으로 세운다(T1). */
     fun activeVersions(): List<VersionRow> =
         jdbc.sql(
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt
index e8f1569..eb4aa26 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt
@@ -23,7 +23,7 @@ class MissionStoreTest {
     @BeforeTest
     fun freshSchema() {
         PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
-        assertEquals(1, HostSchema.flyway(dataSource).migrate().migrationsExecuted)
+        assertEquals(2, HostSchema.flyway(dataSource).migrate().migrationsExecuted)
     }
 
     private fun draft(workMaster: String = PSR) = store.saveDraft(workMaster, "{}", "lee", UUID.randomUUID())
@@ -35,7 +35,13 @@ class MissionStoreTest {
             SELECT table_name FROM information_schema.tables WHERE table_schema = 'mission' ORDER BY table_name
             """.trimIndent(),
         ) { it.getString(1) }
-        assertEquals(listOf("draft", "flyway_schema_history", "mission_version", "mock_run"), placed)
+        assertEquals(
+            listOf(
+                "draft", "execution_journal", "execution_journal_event", "flyway_schema_history", "incident_copy", "incident_copy_resolution",
+                "job_response_log", "mission_version", "mock_run",
+            ),
+            placed,
+        )
         assertNull(PostgresSupport.queryOne("SELECT to_regclass('public.mission_version')::text") { it.getString(1) })
     }
 
@@ -54,6 +60,58 @@ class MissionStoreTest {
         assertEquals(1, store.drafts(PSR, 10).size)
     }
 
+    @Test
+    fun `S4b 표 다섯도 고칠 수도 지울 수도 통째로 비울 수도 없다`() {
+        PostgresSupport.execute(
+            "INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, instance_id, execution_id) " +
+                "VALUES ('JO-1', 'r', '{}', 'InspectAsset', 'mw-1', 'exec-1')",
+        )
+        PostgresSupport.execute("INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, execution_id, detail) VALUES ('JO-1', 'SETTLED', 'mw-1', 'exec-1', 'FAILED')")
+        PostgresSupport.execute(
+            "INSERT INTO mission.job_response_log (instance_id, job_response_id, job_order_id, execution_id, version, physical_state, required_evidence, " +
+                "reached_evidence, completed_units, unverified_units, incomplete_units, in_doubt_units, operator_required, residual_hold, blocked_by, disposition) " +
+                "VALUES ('mw-1', 'resp-1', 'JO-1', 'exec-1', 1, 'FAILED', 'E0', 'E0', '[]', '[]', '[]', '[]', false, 'HOLD_KIND_EMPTY', '[]', 'SENT')",
+        )
+        PostgresSupport.execute(
+            "INSERT INTO mission.incident_copy (instance_id, incident_id, execution_id, job_order_id, unit_id, detail) VALUES ('mw-1', 'incident-1', 'exec-1', 'JO-1', 'u', '{}')",
+        )
+        PostgresSupport.execute(
+            "INSERT INTO mission.incident_copy_resolution (instance_id, incident_id, decision, decided_at, wall_clock_at, decided_by_id, decided_by_kind) " +
+                "VALUES ('mw-1', 'incident-1', 'REWORK', now(), now(), 'kim', 'PERSON')",
+        )
+        listOf(
+            "execution_journal" to "robot_id = 'x'", "execution_journal_event" to "detail = 'x'", "job_response_log" to "disposition = 'SENT'",
+            "incident_copy" to "unit_id = 'x'", "incident_copy_resolution" to "decision = 'x'",
+        ).forEach { (table, set) ->
+            listOf("UPDATE mission.$table SET $set", "DELETE FROM mission.$table", "TRUNCATE mission.$table CASCADE").forEach { sql ->
+                val e = assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) }
+                assertTrue("덧붙이기만" in e.message!!, "$sql: ${e.message}")
+            }
+        }
+        // 같은 인스턴스·응답 id 는 두 번 적지 못한다. 일지 이벤트 종류는 넷뿐이다.
+        assertFailsWith<SQLException> {
+            PostgresSupport.execute(
+                "INSERT INTO mission.job_response_log (instance_id, job_response_id, job_order_id, execution_id, version, physical_state, required_evidence, " +
+                    "reached_evidence, completed_units, unverified_units, incomplete_units, in_doubt_units, operator_required, residual_hold, blocked_by, disposition) " +
+                    "VALUES ('mw-1', 'resp-1', 'JO-1', 'exec-1', 1, 'FAILED', 'E0', 'E0', '[]', '[]', '[]', '[]', false, 'HOLD_KIND_EMPTY', '[]', 'SENT')",
+            )
+        }
+        assertFailsWith<SQLException> {
+            PostgresSupport.execute("INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, detail) VALUES ('JO-1', 'FORGOTTEN', 'mw-1', 'x')")
+        }
+    }
+
+    @Test
+    fun `임무 버전 하나를 WorkMaster 와 번호로 읽고 없으면 null 이다`() {
+        val draft = draft()
+        store.insertVersion(PSR, draft.draftId, "v1", "lee", "r", UUID.randomUUID())
+        store.insertVersion(PSR, draft.draftId, "v2", "lee", "r", UUID.randomUUID())
+        assertEquals("v1", store.version(PSR, 1)!!.definition)
+        assertEquals("v2", store.version(PSR, 2)!!.definition)
+        assertNull(store.version(PSR, 3))
+        assertNull(store.version("OtherWorkMaster", 1))
+    }
+
     @Test
     fun `버전 번호는 WorkMaster 마다 가장 큰 번호 더하기 1 이고 활성 버전은 WorkMaster 마다 가장 큰 번호다`() {
         val a = draft()
```

- [ ] **Step 3: 커밋 1/4 `feat(mission-host): 실행 일지·송신 기록·인시던트 사본 저장소`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostRecords.kt mission-host/src/main/resources/db/mission/V2__execution_journal.sql mission-host/src/main/kotlin/dev/picasso/ops/host/mission/StoredMissionCatalog.kt mission-host/src/main/kotlin/dev/picasso/ops/host/store/MissionStore.kt mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt && git commit -F - <<'EOF'
feat(mission-host): 실행 일지·송신 기록·인시던트 사본 저장소

- V2 마이그레이션: 덧붙이기 전용 표 다섯(execution_journal, execution_journal_event, job_response_log, incident_copy, incident_copy_resolution)과 V1 트리거
- HostRecords: 일지·이벤트·송신 기록·사본·판단 행의 쓰기와 읽기, pump 뒤 기록용 트랜잭션
- MissionStore.version: 임무 버전 한 행 읽기
- StoredMissionCatalog.mission: 일지의 (WorkMaster, 버전 또는 null)로 ActiveMission 세우기
- MissionStoreTest: 새 표 다섯의 갱신·삭제·비우기 거부, 버전 한 행 읽기

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 4: (커밋 2/4) 새 파일 1개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && mkdir -p mission-host/src/test/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/files/mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt" mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt
```

`mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt`:

```kotlin
package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.GHOST
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.registry.PostgresSupport
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 실행 일지, 송신 기록, 인시던트 사본, 판단의 인스턴스, 다시 짓지 못한 기체의 판정 제외(S4b 스펙 §6, T2·T4·T6·T7·T8).
 * 기동 복원 자체(RESTORED·DEFERRED 와 재기동 중복)는 [HostRestoreTest] 가 본다.
 */
class HostJournalTest {

    private val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    @Test
    fun `새 실행의 ACCEPTED 는 응답 전에 일지 행을 적고 UNASSIGNED 와 IDEMPOTENT 는 적지 않으며 정착하면 정착 이벤트를 하나 적는다`() {
        HostBench().use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            // 응답을 받은 때 이미 있다.
            val row = journal().single()
            assertEquals(
                listOf("JO-1", HUMANOID, "InspectAsset", null, bench.host.instanceId, "exec-1"),
                listOf(row["job_order_id"], row["robot_id"], row["work_master_id"], row["mission_version"], row["instance_id"], row["execution_id"]),
            )
            assertEquals(JSON.readTree(inspect("JO-1", "T1" to "bay-7")), JSON.readTree(row["job_order"] as String))
            assertTrue(events("JO-1").isEmpty())

            val unassigned = bench.post("/host/job-orders", request(rack("JO-2", "RACK-204.S01"), "candidates", QUADRUPED)).body!!
            assertEquals("UNASSIGNED", unassigned["result"].asText())

            val done = bench.driveUntil("exec-1", settled)
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            bench.eventually("정착 이벤트") { events("JO-1").isNotEmpty() }
            assertEquals(listOf(listOf("SETTLED", bench.host.instanceId, "exec-1", "PHYSICALLY_DONE")), events("JO-1"))

            val again = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("IDEMPOTENT", again["result"].asText(), again.toString())
            bench.idlePumps()
            assertEquals(listOf("JO-1"), journal().map { it["job_order_id"] })
            assertEquals(1, events("JO-1").size)

            // 송신 기록: 응답마다 한 행, 모두 송신이고 ack 했으므로 다시 적지 않는다. 최신 행이 실행의 마지막 작업 응답이다.
            val log = bench.get("/host/job-responses?jobOrderId=JO-1")
            assertEquals(listOf("instanceId", "total", "responses"), log.fieldNames().asSequence().toList())
            val rows = log["responses"].toList()
            assertTrue(rows.isNotEmpty())
            assertEquals(rows.size, log["total"].asInt())
            assertEquals(rows.size, rows.map { it["jobResponseId"].asText() }.distinct().size)
            assertTrue(rows.all { it["disposition"].asText() == "SENT" && it["instanceId"].asText() == bench.host.instanceId }, rows.toString())
            assertEquals(LOG_FIELDS, rows[0].fieldNames().asSequence().toList())
            assertEquals(bench.execution("exec-1")!!["jobResponse"]["jobResponseId"], rows[0]["jobResponseId"])
            assertEquals(
                listOf("PHYSICALLY_DONE", "E0", "E0", listOf("T1", "T1.travel"), false, "exec-1", 1),
                listOf(
                    rows[0]["physicalState"].asText(), rows[0]["requiredEvidence"].asText(), rows[0]["reachedEvidence"].asText(),
                    rows[0]["completedUnits"].map { it.asText() }, rows[0]["operatorRequired"].asBoolean(), rows[0]["executionId"].asText(),
                    rows[0]["version"].asInt(),
                ),
            )
            assertTrue(rows[0]["incompleteUnits"].isArray && rows[0]["incompleteUnits"].isEmpty)
            assertTrue(rows[0]["recordedAt"].isTextual)
            assertEquals(0, bench.get("/host/job-responses?jobOrderId=JO-2")["total"].asInt())
            assertEquals(1, bench.get("/host/job-responses?limit=1")["responses"].size())
            listOf("?limit=0", "?limit=501", "?limit=x", "?jobOrderId=").forEach { query ->
                val reply = bench.fetch("/host/job-responses$query")
                assertEquals(400, reply.status, query)
                assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), query)
            }
        }
    }

    @Test
    fun `실패한 실행에 새 버전을 내 revise 로 ACCEPTED 여도 일지 행을 더 적지 않는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val first = bench.post("/host/job-orders", request(rack("JO-1", S01), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", first["result"].asText(), first.toString())
            val running = bench.runningTask(HUMANOID)
            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.forceFault("SKILL_EXECUTION_FAILED", running) }
            assertTrue(outcome is ForceOutcome.Raised, outcome.toString())
            assertEquals("FAILED", bench.driveUntil("exec-1", settled)["physicalState"].asText())

            val revised = bench.post("/host/job-orders", request(rack("JO-1", S01).replace("\"version\":1", "\"version\":2"), "candidates", HUMANOID))
            assertEquals(200, revised.status, revised.body.toString())
            assertEquals("ACCEPTED", revised.body!!["result"].asText(), revised.body.toString())
            assertEquals("exec-1", revised.body["executionId"].asText())
            assertEquals(1, journal().size)
            assertEquals("exec-1", journal().single()["execution_id"])
        }
    }

    @Test
    fun `pump 뒤 기록은 한 트랜잭션이라 사본 쓰기가 실패하면 송신 기록도 남지 않고 ack 하지 않아 다음 pump 에 한 번씩 적는다`() {
        HostBench().use { bench ->
            PostgresSupport.execute(
                """
                CREATE FUNCTION mission.refuse_copy() RETURNS trigger AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'copy refused'; END; ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            PostgresSupport.execute("CREATE TRIGGER refuse_copy BEFORE INSERT ON mission.incident_copy FOR EACH ROW EXECUTE FUNCTION mission.refuse_copy()")
            val executionId = bench.submitHeldOrder("JO-1")
            bench.driveToHold(executionId)
            bench.idlePumps()
            assertEquals(0, count("job_response_log"))
            assertEquals(0, count("incident_copy"))

            PostgresSupport.execute("DROP TRIGGER refuse_copy ON mission.incident_copy")
            bench.eventually("사본") { count("incident_copy") == 1 }
            val incidentId = bench.get("/host/incidents")["incidents"].single()["incidentId"].asText()
            val copy = PostgresSupport.queryAll("SELECT instance_id, incident_id, execution_id, job_order_id, unit_id, detail::text FROM mission.incident_copy") {
                listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6))
            }.single()
            assertEquals(listOf(bench.host.instanceId, incidentId, executionId, "JO-1", WAIT), copy.take(5))
            assertEquals(bench.get("/host/incidents/$incidentId"), JSON.readTree(copy[5]))
            val logged = bench.get("/host/job-responses?jobOrderId=JO-1")["responses"].map { it["jobResponseId"].asText() }
            assertTrue(logged.isNotEmpty())
            assertEquals(logged.distinct(), logged)
            assertEquals(bench.execution(executionId)!!["jobResponse"]["jobResponseId"].asText(), logged.first())
            assertEquals(0, count("incident_copy_resolution"))

            assertEquals("Resolved", bench.resolve(executionId, WAIT, "REWORK", "kim").body!!["result"].asText())
            bench.eventually("판단 행") { count("incident_copy_resolution") == 1 }
            val resolution = PostgresSupport.queryAll(
                "SELECT instance_id, incident_id, decision, decided_by_id, decided_by_kind FROM mission.incident_copy_resolution",
            ) { (1..5).map { i -> it.getString(i) } }.single()
            assertEquals(listOf(bench.host.instanceId, incidentId, "REWORK", "kim", "PERSON"), resolution)
            bench.idlePumps()
            assertEquals(1, count("incident_copy"))
            assertEquals(1, count("incident_copy_resolution"))
        }
    }

    @Test
    fun `재기동 뒤 이전 인스턴스의 인시던트와 판단은 earlier 와 instanceId 상세에만 있고 그 인스턴스를 실은 판단은 409 다`() {
        HostBench().use { bench ->
            val executionId = bench.submitHeldOrder("JO-1")
            bench.driveToHold(executionId)
            assertEquals("Resolved", bench.resolve(executionId, WAIT, "REWORK", "kim").body!!["result"].asText())
            bench.eventually("판단 행") { count("incident_copy_resolution") == 1 }
            val before = bench.host.instanceId
            val beforeList = bench.get("/host/incidents")
            assertEquals(0, beforeList["earlierTotal"].asInt())
            assertTrue(beforeList["earlier"].isArray && beforeList["earlier"].isEmpty)
            val sealed = bench.get("/host/incidents/incident-1")

            bench.restartHost()
            val after = bench.host.instanceId
            assertNotEquals(before, after)
            val list = bench.get("/host/incidents")
            assertEquals(listOf("instanceId", "total", "incidents", "earlierTotal", "earlier"), list.fieldNames().asSequence().toList())
            assertEquals(after, list["instanceId"].asText())
            assertEquals(1, list["earlierTotal"].asInt())
            val earlier = list["earlier"].single()
            assertEquals(listOf("instanceId") + LIST_FIELDS, earlier.fieldNames().asSequence().toList())
            assertEquals(listOf(before, "incident-1", executionId, "JO-1"), listOf("instanceId", "incidentId", "executionId", "jobOrderId").map { earlier[it].asText() })
            assertEquals("REWORK", earlier["resolution"]["decision"].asText())
            assertEquals(JSON.readTree("""{"id":"kim","kind":"PERSON"}"""), earlier["resolution"]["decidedBy"])
            assertEquals(listOf(true, false, false), listOf(earlier["unresolved"].asBoolean(), earlier["held"].asBoolean(), earlier["confirmedWithoutEvidence"].asBoolean()))
            assertEquals(1, earlier["missionVersion"].asInt())
            assertEquals(30L, earlier["evidenceBeforeSeconds"].asLong())

            val detail = bench.get("/host/incidents/incident-1?instanceId=$before")
            assertEquals(DETAIL_FIELDS, detail.fieldNames().asSequence().toList())
            assertEquals(before, detail["instanceId"].asText())
            assertEquals(false, detail["held"].asBoolean())
            assertTrue(detail["unitState"].isNull, detail.toString())
            assertEquals("REWORK", detail["resolution"]["decision"].asText())
            assertEquals(sealed["intent"], detail["intent"])
            assertEquals(sealed["evidenceWindow"], detail["evidenceWindow"])

            listOf("", "?instanceId=$after", "?instanceId=mw-unknown").forEach { query ->
                val missing = bench.fetch("/host/incidents/incident-1$query")
                assertEquals(404, missing.status, query)
                assertEquals("INCIDENT_NOT_FOUND", missing.body!!["error"].asText())
            }
            assertEquals(400, bench.fetch("/host/incidents/incident-1?instanceId=").status)

            val stale = bench.post(
                "/host/executions/$executionId/units/$WAIT/resolve",
                JSON.writeValueAsString(linkedMapOf("decision" to "CONFIRM_DONE", "approverId" to "kim", "instanceId" to before)),
            )
            assertEquals(409, stale.status)
            assertEquals("INSTANCE_MISMATCH", stale.body!!["error"].asText())
        }
    }

    @Test
    fun `포기한 일지 행의 기체는 그 작업 지시의 고아 태스크가 끝날 때까지 판정에서 빠지고 스냅숏을 못 읽는 동안은 계속 빠진다`() {
        HostBench(gated = true).use { bench ->
            // 이전 인스턴스가 받았으나 그 임무 버전 행이 없어 다시 짓지 못하는 일지 행 둘. 하나는 고아 태스크가 도는 humanoid-01 이다.
            listOf("JO-ORPHAN" to HUMANOID, "JO-GHOST" to GHOST).forEach { (jobOrderId, robotId) -> insertJournal(jobOrderId, robotId, missionVersion = 9) }
            val started = bench.clientPort("orphan").start(HUMANOID, "JO-ORPHAN#T1.travel", 1, "navigate_to", mapOf("location" to "bay-7"))
            assertTrue(started.hasHandle(), started.toString())

            bench.restartHost()
            val restore = bench.get("/host/executions")["restore"]
            assertEquals(listOf("JO-ORPHAN", "JO-GHOST"), restore["rows"].map { it["jobOrderId"].asText() })
            assertTrue(restore["rows"].all { it["result"].asText() == "GAVE_UP" && "임무 버전 행이 없다" in it["reason"].asText() }, restore.toString())
            assertEquals(listOf("mw-before", "exec-7"), listOf(restore["rows"][0]["previousInstanceId"].asText(), restore["rows"][0]["previousExecutionId"].asText()))
            assertEquals(listOf("GAVE_UP"), events("JO-ORPHAN").map { it[0] })

            fun judged(robotId: String): JsonNode =
                bench.post("/host/eligibility", request(inspect("JO-9", "T2" to "dock-3"), "robotIds", robotId)).body!!["robots"].single()

            val orphaned = judged(HUMANOID)
            assertEquals(false, orphaned["passed"].asBoolean())
            assertEquals(listOf("복원 못 한 실행이 있다: JO-ORPHAN"), orphaned["reasons"].map { it.asText() })
            assertTrue(orphaned["runningExecutionId"].isNull)
            assertTrue(judged(GHOST)["reasons"].any { it.asText() == "복원 못 한 실행이 있다: JO-GHOST" })

            // 고아 태스크가 끝나도 스냅숏을 못 읽는 동안은 계속 뺀다.
            bench.blockedSnapshots += HUMANOID
            repeat(6) { bench.mimic.server.advance(Duration.ofSeconds(5)) }
            val terminal = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.single().machine.state }
            assertEquals(TaskState.SUCCEEDED, terminal)
            assertEquals(listOf("복원 못 한 실행이 있다: JO-ORPHAN"), judged(HUMANOID)["reasons"].map { it.asText() })

            bench.blockedSnapshots -= HUMANOID
            val released = judged(HUMANOID)
            assertEquals(true, released["passed"].asBoolean(), released.toString())
            // 풀린 행은 다시 빼지 않는다. 스냅숏을 다시 못 읽어도 그렇다.
            bench.blockedSnapshots += HUMANOID
            assertEquals(true, judged(HUMANOID)["passed"].asBoolean())
            val submitted = bench.post("/host/job-orders", request(inspect("JO-9", "T2" to "dock-3"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())

            // 다음 기동은 포기한 행을 다시 짓지 않는다.
            bench.restartHost()
            assertEquals(listOf("JO-9"), bench.get("/host/executions")["restore"]["rows"].map { it["jobOrderId"].asText() })
            assertEquals(listOf("GAVE_UP"), events("JO-ORPHAN").map { it[0] })
        }
    }

    private fun HostBench.runningTask(robotId: String): String {
        repeat(20) {
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            mimic.server.exclusive { mimic.instance(robotId)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING }?.taskId }
                ?.let { return it }
        }
        error("태스크가 RUNNING 이 되지 않았다")
    }

    private companion object {
        const val PSR = "PrepareSequencedRack"
        const val WAIT = "rack-arrival"
        const val S01 = "RACK-204.S01"

        val LOG_FIELDS = listOf(
            "instanceId", "jobResponseId", "jobOrderId", "executionId", "version", "physicalState", "requiredEvidence", "reachedEvidence",
            "completedUnits", "unverifiedUnits", "incompleteUnits", "inDoubtUnits", "operatorRequired", "residualHold", "blockedBy",
            "disposition", "recordedAt",
        )

        val LIST_FIELDS = listOf(
            "incidentId", "executionId", "jobOrderId", "robotId", "unitId", "at", "failureClass", "route", "missionVersion",
            "siteSettingsVersion", "evidenceBeforeSeconds", "evidenceAfterSeconds", "inDoubtGraceSeconds", "stallWindowSeconds",
            "unresolved", "resolution", "fault", "held", "confirmedWithoutEvidence",
        )

        val DETAIL_FIELDS = listOf(
            "instanceId", "incidentId", "executionId", "jobOrderId", "robotId", "unitId", "at", "wallClockAt", "failureClass", "route",
            "unresolved", "resolution", "held", "confirmedWithoutEvidence", "unitState", "fault", "blockedBy", "requiredEvidence",
            "reachedEvidence", "verification", "step", "evidenceWindow", "windowTruncated", "preconditionSubjects", "expectedHold",
            "observedHold", "effectMismatch", "linkBroken", "intent",
        )

        fun journal(): List<Map<String, Any?>> = PostgresSupport.queryAll(
            "SELECT job_order_id, robot_id, job_order::text, work_master_id, mission_version, instance_id, execution_id FROM mission.execution_journal ORDER BY journal_id",
        ) {
            linkedMapOf(
                "job_order_id" to it.getString(1), "robot_id" to it.getString(2), "job_order" to it.getString(3),
                "work_master_id" to it.getString(4), "mission_version" to it.getObject(5), "instance_id" to it.getString(6),
                "execution_id" to it.getString(7),
            )
        }

        /** 그 작업 지시의 일지 이벤트(종류, 인스턴스, 실행 id, 사유). */
        fun events(jobOrderId: String): List<List<String?>> = PostgresSupport.queryAll(
            "SELECT kind, instance_id, execution_id, detail FROM mission.execution_journal_event WHERE job_order_id = '$jobOrderId' ORDER BY event_id",
        ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }

        fun count(table: String): Int = PostgresSupport.queryOne("SELECT count(*) FROM mission.$table") { it.getInt(1) }

        /** 이전 인스턴스가 받은 일지 행을 직접 넣는다. 작업 지시는 InspectAsset 하나다. */
        fun insertJournal(jobOrderId: String, robotId: String, missionVersion: Int?, evidence: String = "E0") {
            val order = JSON.writeValueAsString(JSON.readTree(inspect(jobOrderId, "T1" to "bay-7", evidence = evidence)))
            PostgresSupport.execute(
                """
                INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
                VALUES ('$jobOrderId', '$robotId', '$order'::jsonb, 'InspectAsset', ${missionVersion ?: "NULL"}, 'mw-before', 'exec-7')
                """.trimIndent(),
            )
        }
    }
}

/** 운영자 보류 대기 템플릿을 초안 저장, 모의 실행, 활성화한다. 버전 번호를 돌려준다. */
internal fun HostBench.activateHold(robots: List<String> = listOf(HUMANOID, QUADRUPED)): Int {
    val definition = get("/host/missions/templates/PrepareSequencedRack")["templates"].single { it["id"].asText() == "ARRIVAL_WAIT_HOLD" }["definition"].asText()
    val saved = post("/host/missions/PrepareSequencedRack/drafts", JSON.writeValueAsString(mapOf("definition" to definition, "actor" to "lee", "requestId" to "${UUID.randomUUID()}")))
    val draftId = saved.body!!["draft"]["draftId"].asLong()
    val mock = post("/host/missions/drafts/$draftId/mock-run", JSON.writeValueAsString(mapOf("robotIds" to robots, "requestId" to "${UUID.randomUUID()}")))
    assertEquals("PASSED", mock.body!!["result"].asText(), mock.body.toString())
    val activation = post(
        "/host/missions/drafts/$draftId/activate",
        JSON.writeValueAsString(mapOf("actor" to "lee", "reason" to "보류 시연", "robotIds" to robots, "requestId" to "${UUID.randomUUID()}")),
    )
    assertEquals("ACTIVATED", activation.body!!["result"].asText(), activation.body.toString())
    return activation.body["version"].asInt()
}

/** 셀 대역을 한 번 읽힌 뒤 보류 버전을 활성화하고 슬롯 하나의 작업 지시를 낸다. 실행 id 를 돌려준다. */
internal fun HostBench.submitHeldOrder(jobOrderId: String): String {
    mimic.server.advance(Duration.ofSeconds(1))
    awaitPump()
    activateHold()
    val submitted = post("/host/job-orders", request(rack(jobOrderId, "RACK-204.S01"), "candidates", HUMANOID)).body!!
    assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
    return submitted["executionId"].asText()
}

/** 실행이 운영자 보류가 될 때까지 민다. 기한 20초이므로 가상 시간 30초(5초씩 여섯 번) 안에 서야 한다. */
internal fun HostBench.driveToHold(executionId: String): JsonNode {
    val held = driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL", "OPERATOR_HOLD"), rounds = 6)
    assertEquals("OPERATOR_HOLD", held["physicalState"].asText(), held.toString())
    return held
}

/** 지금 인스턴스를 실은 판단 요청. */
internal fun HostBench.resolve(executionId: String, unitId: String, decision: String, approverId: String): HostBench.Reply =
    post(
        "/host/executions/$executionId/units/$unitId/resolve",
        JSON.writeValueAsString(linkedMapOf("decision" to decision, "approverId" to approverId, "instanceId" to host.instanceId)),
    )
```

- [ ] **Step 5: (커밋 2/4) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task2b.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task2b.patch"
```

```diff
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/IncidentViews.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/IncidentViews.kt
index a94b87b..5518d52 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/IncidentViews.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/IncidentViews.kt
@@ -1,5 +1,6 @@
 package dev.picasso.ops.host
 
+import com.fasterxml.jackson.annotation.JsonUnwrapped
 import dev.picasso.middleware.Approver
 import dev.picasso.middleware.FaultDetail
 import dev.picasso.middleware.IncidentBundle
@@ -132,8 +133,25 @@ data class IncidentView(
     val confirmedWithoutEvidence: Boolean,
 )
 
-/** `GET /host/incidents` 의 본문. [incidents] 는 최신부터 많아야 limit 개이고 [total] 은 자르기 전의 수다. */
-data class IncidentsView(val instanceId: String, val total: Int, val incidents: List<IncidentView>)
+/**
+ * `GET /host/incidents` 의 본문. [incidents] 는 최신부터 많아야 limit 개이고 [total] 은 자르기 전의 수다.
+ *
+ * @param earlier 이전 인스턴스의 인시던트 사본(S4b 스펙 T7). 적은 순서의 역순으로 많아야 limit 개다. 보류 중이 아니다
+ * @param earlierTotal 자르기 전의 사본 수
+ */
+data class IncidentsView(
+    val instanceId: String,
+    val total: Int,
+    val incidents: List<IncidentView>,
+    val earlierTotal: Int,
+    val earlier: List<EarlierIncidentView>,
+)
+
+/** 이전 인스턴스의 인시던트 한 줄. 목록 줄의 19칸 앞에 그 인스턴스를 둔다. 상세는 이 [instanceId] 를 질의로 실어 읽는다. */
+data class EarlierIncidentView(
+    val instanceId: String,
+    @get:JsonUnwrapped val incident: IncidentView,
+)
 
 /**
  * `GET /host/incidents/{incidentId}` 의 본문(S4a 스펙 §6). 목록 줄의 칸에 근거 윈도우, 단계 위치, 필요·도달 근거 등급, 확인 결과,
@@ -218,6 +236,29 @@ internal object IncidentViews {
         )
     }
 
+    /** 상세에서 목록 줄을 다시 세운다. 사본이 쓴다. 칸의 값은 [item] 과 같은 출처(봉인 때의 번들)다. */
+    fun item(detail: IncidentDetailView): IncidentView = IncidentView(
+        incidentId = detail.incidentId,
+        executionId = detail.executionId,
+        jobOrderId = detail.jobOrderId,
+        robotId = detail.robotId,
+        unitId = detail.unitId,
+        at = detail.at,
+        failureClass = detail.failureClass,
+        route = detail.route,
+        missionVersion = detail.intent.missionVersion,
+        siteSettingsVersion = detail.intent.siteSettingsVersion,
+        evidenceBeforeSeconds = detail.intent.evidenceBeforeSeconds,
+        evidenceAfterSeconds = detail.intent.evidenceAfterSeconds,
+        inDoubtGraceSeconds = detail.intent.inDoubtGraceSeconds,
+        stallWindowSeconds = detail.intent.stallWindowSeconds,
+        unresolved = detail.unresolved,
+        resolution = detail.resolution,
+        fault = detail.fault?.let { FaultSummaryView(it.failureClass, it.errorType, it.errorHint) },
+        held = detail.held,
+        confirmedWithoutEvidence = detail.confirmedWithoutEvidence,
+    )
+
     fun detail(instanceId: String, bundle: IncidentBundle, held: Boolean, unitState: String?): IncidentDetailView {
         val intent = bundle.intent
         return IncidentDetailView(
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
index cb3bc99..6f4cca6 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
@@ -1,5 +1,9 @@
 package dev.picasso.ops.host
 
+import com.fasterxml.jackson.databind.ObjectMapper
+import com.fasterxml.jackson.module.kotlin.readValue
+import dev.picasso.contracts.v1.TaskState
+import dev.picasso.middleware.ActiveMission
 import dev.picasso.middleware.Approver
 import dev.picasso.middleware.IncidentBundle
 import dev.picasso.middleware.InspectAsset
@@ -7,6 +11,7 @@ import dev.picasso.middleware.JobOrder
 import dev.picasso.middleware.JobResponse
 import dev.picasso.middleware.Middleware
 import dev.picasso.middleware.OperatorDecision
+import dev.picasso.middleware.PhysicalState
 import dev.picasso.middleware.PrepareSequencedRack
 import dev.picasso.middleware.Route
 import dev.picasso.middleware.ResolveOutcome
@@ -14,11 +19,23 @@ import dev.picasso.middleware.RobotPort
 import dev.picasso.middleware.SiteTimingsSource
 import dev.picasso.middleware.Unassigned
 import dev.picasso.middleware.UnitState
+import dev.picasso.middleware.Verification
 import dev.picasso.ops.host.cell.CellBandClient
 import dev.picasso.ops.host.cell.CellBandSignals
 import dev.picasso.ops.host.cell.CellSnapshot
 import dev.picasso.ops.host.mission.SiteInputs
 import dev.picasso.ops.host.mission.StoredMissionCatalog
+import dev.picasso.ops.host.web.BadRequest
+import dev.picasso.ops.host.web.HostRequests
+import dev.picasso.ops.host.store.CopyResolutionRow
+import dev.picasso.ops.host.store.HostRecords
+import dev.picasso.ops.host.store.IncidentCopyRow
+import dev.picasso.ops.host.store.JournalRow
+import dev.picasso.ops.host.store.JournalEventKind
+import dev.picasso.ops.host.store.MissionStore
+import dev.picasso.ops.host.store.ResponseContent
+import dev.picasso.ops.host.store.ResponseDisposition
+import dev.picasso.ops.host.store.ResponseLogRow
 import org.slf4j.LoggerFactory
 import java.time.Duration
 import java.time.Instant
@@ -105,13 +122,88 @@ data class ExecutionView(
     val physicalState: String,
     val units: List<UnitView>,
     val jobResponse: JobResponseView?,
+    val restoredFrom: RestoredFromView?,
 )
 
+/** 다시 지은 실행의 이전 실행(S4b 스펙 §6.3). 바로 앞 인스턴스에서 그 작업 지시를 들었던 실행이다. */
+data class RestoredFromView(val instanceId: String, val executionId: String)
+
 /**
  * `GET /host/executions` 의 본문. [instanceId] 는 미들웨어가 뜬 한 번을 가리킨다. 재기동하면 바뀌고 `exec-N` 은 1부터
  * 다시 센다. [pumpedAt] 은 마지막 pump 가 셀 대역을 읽고 잠금을 잡은 뒤의 호스트 시계 값이며 아직 한 번도 안 돌았으면 `null` 이다.
  */
-data class ExecutionsView(val instanceId: String, val pumpedAt: Instant?, val executions: List<ExecutionView>)
+data class ExecutionsView(
+    val instanceId: String,
+    val pumpedAt: Instant?,
+    val executions: List<ExecutionView>,
+    val restore: RestoreView?,
+)
+
+/** 복원의 결과(S4b 스펙 T3). DEFERRED 는 pump 마다 다시 시도해 RESTORED 나 GAVE_UP 이 된다. */
+enum class RestoreResult { RESTORED, DEFERRED, GAVE_UP }
+
+/**
+ * 복원 보고의 한 행(S4b 스펙 §6.3).
+ *
+ * @param previousInstanceId·previousExecutionId 바로 앞 인스턴스에서 그 작업 지시를 들었던 실행. 앞서 다시 지었으면 그 실행이다
+ * @param executionId RESTORED 일 때 새 실행 id. 그 밖에는 `null`
+ * @param reason DEFERRED·GAVE_UP 의 사유. RESTORED 면 `null`
+ */
+data class RestoreRowView(
+    val jobOrderId: String,
+    val robotId: String,
+    val previousInstanceId: String,
+    val previousExecutionId: String,
+    val result: String,
+    val executionId: String?,
+    val reason: String?,
+)
+
+/** 이번 기동의 복원 보고. [at] 은 복원한 호스트 시각이고 [rows] 는 일지의 받은 순서다. 다시 지을 것이 없었으면 [rows] 가 비어 있다. */
+data class RestoreView(val at: Instant, val rows: List<RestoreRowView>)
+
+/**
+ * `GET /host/job-responses` 의 행 하나(S4b 스펙 T6). 내용 키의 칸과 처분이다. 단위 id 목록은 정렬돼 있고 [incompleteUnits] 는
+ * 사유 없이 단위 id 만 든다.
+ *
+ * @param disposition `SENT` 또는 `RESTART_DUPLICATE`(적었으나 송신하지 않음)
+ * @param recordedAt 적은 DB 시각(실제 시각)
+ */
+data class JobResponseLogView(
+    val instanceId: String,
+    val jobResponseId: String,
+    val jobOrderId: String,
+    val executionId: String,
+    val version: Int,
+    val physicalState: String,
+    val requiredEvidence: String,
+    val reachedEvidence: String,
+    val completedUnits: List<String>,
+    val unverifiedUnits: List<String>,
+    val incompleteUnits: List<String>,
+    val inDoubtUnits: List<String>,
+    val operatorRequired: Boolean,
+    val residualHold: String,
+    val blockedBy: List<String>,
+    val disposition: String,
+    val recordedAt: Instant,
+) {
+    companion object {
+        fun of(row: ResponseLogRow) = row.content.let {
+            JobResponseLogView(
+                row.instanceId, row.jobResponseId, it.jobOrderId, row.executionId, it.version, it.physicalState, it.requiredEvidence,
+                it.reachedEvidence, it.completedUnits, it.unverifiedUnits, it.incompleteUnits, it.inDoubtUnits, it.operatorRequired,
+                it.residualHold, it.blockedBy, row.disposition.name, row.recordedAt,
+            )
+        }
+    }
+}
+
+/** `GET /host/job-responses` 의 본문. [responses] 는 최근부터 많아야 limit 개이고 [total] 은 자르기 전의 수다. */
+data class JobResponsesView(val instanceId: String, val total: Int, val responses: List<JobResponseLogView>)
+
+/** 일지 쓰기가 실패했다(S4b 스펙 §9). 실행은 미들웨어에 남는다. 제출은 500 이다. */
+class JournalWriteFailed(val executionId: String, cause: Throwable) : RuntimeException("실행 일지를 적지 못했다: $executionId", cause)
 
 /**
  * 미들웨어 실행 호스트(S3a 스펙 §7).
@@ -161,6 +253,9 @@ class MissionHost(
     private val clock: HostClock,
     private val catalog: StoredMissionCatalog = StoredMissionCatalog(),
     private val siteTimings: SiteTimingsSource,
+    private val records: HostRecords,
+    private val store: MissionStore,
+    private val json: ObjectMapper,
 ) : AutoCloseable {
 
     private val lock = ReentrantLock(true)
@@ -171,15 +266,133 @@ class MissionHost(
     private var pumpedAt: Instant? = null
     private var latestCell: CellSnapshot? = null
 
+    // ── S4b 기록. 모두 호스트 잠금 아래에서만 읽고 쓴다.
+
+    /** 이 인스턴스에서 일지 행이 있는 실행의 작업 지시 id(제출로 적었거나 다시 지은 것). 정착 이벤트는 이것만 적는다. */
+    private val journaled = mutableSetOf<String>()
+
+    /** 이 인스턴스가 정착 이벤트를 적은 작업 지시 id. */
+    private val settledRecorded = mutableSetOf<String>()
+
+    /** 사본을 적은 인시던트 수. 미들웨어의 인시던트 목록은 덧붙이기만 하므로 이 색인 뒤가 새로 봉인된 것이다. */
+    private var copiedIncidents = 0
+
+    /** 판단 행을 적은 인시던트 id. */
+    private val copiedResolutions = mutableSetOf<String>()
+
+    /** 이번 기동의 복원 시각. [start] 전에는 `null` 이다. */
+    private var restoredAt: Instant? = null
+
+    /** 이번 기동의 복원 보고 행. 일지의 받은 순서이고, DEFERRED 행은 결과가 바뀌면 그 자리에서 갈린다. */
+    private val restoreRows = mutableListOf<RestoreRowView>()
+
+    /** 다시 지은 실행 id 에서 바로 앞 인스턴스의 실행으로. */
+    private val restoredFrom = mutableMapOf<String, RestoredFromView>()
+
+    /** 기체 스냅숏을 못 읽어 미룬 일지 행(T3). pump 마다 다시 시도하고 그동안 그 기체를 판정에서 뺀다(T4). */
+    private val deferred = mutableListOf<Deferred>()
+
+    /**
+     * 포기했고 정착하지 않은 일지 행(T4). 그 기체의 스냅숏에 그 작업 지시의 비종착 태스크가 없어질 때까지 그 기체를 판정에서 뺀다.
+     * 이전 기동에서 포기한 행도 든다. 풀린 행은 이 인스턴스에서 다시 빼지 않는다.
+     */
+    private val gaveUp = mutableListOf<JournalRow>()
+
+    /** 미룬 행. [order]·[mission] 은 복원 때 한 번 읽은 것이고 다시 시도할 때 그대로 쓴다. */
+    private class Deferred(val row: JournalRow, val order: JobOrder, val mission: ActiveMission, val index: Int)
+
     private val pumper = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-pump").apply { isDaemon = true } }
 
-    val instanceId: String get() = middleware.instanceId
+    val instanceId: String = middleware.instanceId
 
-    /** pump 를 시작한다. [MissionHostApplication] 이 빈을 만들 때 부른다. */
+    /**
+     * 실행을 복원하고 pump 를 시작한다. [MissionHostApplication] 이 빈을 만들 때 부른다. 복원이 빈 생성 안에서 끝나므로 웹 서버가
+     * 열리기 전에 판정이 도는 실행을 안다(S4b 스펙 T3). 복원 중 DB 가 실패하면 예외가 나가 기동이 멈춘다.
+     */
     fun start(period: Duration = PUMP_PERIOD): MissionHost = apply {
+        restore()
         pumper.scheduleWithFixedDelay(::pumpOnce, 0, period.toMillis(), TimeUnit.MILLISECONDS)
     }
 
+    /**
+     * 기동 복원(S4b 스펙 T3, §6.3). 정착도 포기도 없는 일지 행을 받은 순서대로 다시 짓는다. 결과는 일지 이벤트로 남고 복원 보고에
+     * 실린다. 그 뒤 포기한 행(이전 기동의 것 포함)을 판정 제외 목록으로 읽는다.
+     */
+    private fun restore() = lock.withLock {
+        restoredAt = clock.now()
+        records.openJournal().forEach { row -> restoreRow(row) }
+        gaveUp += records.gaveUpJournal()
+    }
+
+    private fun restoreRow(row: JournalRow) {
+        val previous = records.events(row.jobOrderId).lastOrNull { it.kind == JournalEventKind.RESTORED }
+            ?.let { RestoredFromView(it.instanceId, it.executionId!!) }
+            ?: RestoredFromView(row.instanceId, row.executionId)
+        val index = restoreRows.size
+        restoreRows += RestoreRowView(row.jobOrderId, row.robotId, previous.instanceId, previous.executionId, RestoreResult.GAVE_UP.name, null, null)
+        val prepared = try {
+            val order = HostRequests.jobOrder(json.readTree(row.jobOrder))
+            val version = row.missionVersion?.let {
+                store.version(row.workMasterId, it) ?: throw IllegalStateException("임무 버전 행이 없다: ${row.workMasterId} 버전 $it")
+            }
+            order to catalog.mission(row.workMasterId, version)
+        } catch (e: BadRequest) {
+            settleRestore(row, index, previous, RestoreResult.GAVE_UP, null, "일지의 작업 지시를 읽지 못했다: ${e.message}")
+            return
+        } catch (e: IllegalStateException) {
+            settleRestore(row, index, previous, RestoreResult.GAVE_UP, null, e.message ?: e.toString())
+            return
+        }
+        attempt(Deferred(row, prepared.first, prepared.second, index), previous, first = true)
+    }
+
+    /** 미룬 행을 다시 시도한다(T3). pump 가 미들웨어 pump 전에 잠금 아래에서 부른다. 이벤트를 못 적으면 다음 pump 에 다시 한다. */
+    private fun retryDeferred() {
+        deferred.toList().forEach { waiting ->
+            val previous = restoreRows[waiting.index].let { RestoredFromView(it.previousInstanceId, it.previousExecutionId) }
+            try {
+                attempt(waiting, previous, first = false)
+            } catch (e: Exception) {
+                log.warn("미룬 복원 다시 시도 실패: {} {}", waiting.row.jobOrderId, e.toString())
+            }
+        }
+    }
+
+    private fun attempt(waiting: Deferred, previous: RestoredFromView, first: Boolean) {
+        val (result, executionId, reason) = resumed(waiting)
+        if (result == RestoreResult.DEFERRED) {
+            if (first) {
+                deferred += waiting
+                settleRestore(waiting.row, waiting.index, previous, result, null, reason)
+            }
+            return
+        }
+        settleRestore(waiting.row, waiting.index, previous, result, executionId, reason)
+        deferred.remove(waiting)
+        if (result == RestoreResult.GAVE_UP) gaveUp += waiting.row
+    }
+
+    /** 결과를 일지 이벤트로 적고 보고 행을 갈아 끼운다. 이벤트를 먼저 적는다. 못 적으면 예외가 나가고 보고는 그대로다. */
+    private fun settleRestore(row: JournalRow, index: Int, previous: RestoredFromView, result: RestoreResult, executionId: String?, reason: String?) {
+        val kind = when (result) {
+            RestoreResult.RESTORED -> JournalEventKind.RESTORED
+            RestoreResult.DEFERRED -> JournalEventKind.DEFERRED
+            RestoreResult.GAVE_UP -> JournalEventKind.GAVE_UP
+        }
+        records.event(row.jobOrderId, kind, instanceId, executionId, reason)
+        restoreRows[index] = RestoreRowView(row.jobOrderId, row.robotId, previous.instanceId, previous.executionId, result.name, executionId, reason)
+        if (executionId != null) {
+            restoredFrom[executionId] = previous
+            journaled += row.jobOrderId
+        }
+    }
+
+    /** 미들웨어에 다시 넣는다. P6 `Middleware.resume` 을 기다리는 자리. */
+    private fun resumed(waiting: Deferred): Triple<RestoreResult, String?, String?> =
+        Triple(RestoreResult.GAVE_UP, null, "resume 없음")
+
+    private fun restoreView(): RestoreView? = restoredAt?.let { RestoreView(it, restoreRows.toList()) }
+
     /** pump 한 번. 예외를 삼키고 다음 주기에 다시 돈다. 스케줄러는 작업이 예외를 던지면 다음 실행을 멈춘다. */
     fun pumpOnce() {
         try {
@@ -188,8 +401,10 @@ class MissionHost(
                 signals.snapshot = cell
                 latestCell = cell
                 val at = clock.now()
+                retryDeferred()
                 middleware.pump()
                 pumpedAt = at
+                recordAfterPump()
             }
         } catch (e: Exception) {
             log.warn("pump 실패: {}", e.toString())
@@ -211,15 +426,23 @@ class MissionHost(
      *
      * 미적용 판단은 한 번만 읽어 모든 기체의 판정에 같은 값을 쓴다. 미적용이면 통과한 기체가 없어 UNASSIGNED 다. 미들웨어의 채택은
      * 기체마다 관문을 걸므로 기체가 없으면 요구 근거 등급 검사도 하지 않는다.
+     *
+     * 새 실행으로 ACCEPTED 이면 같은 잠금 안에서 응답하기 전에 실행 일지를 적는다(S4b 스펙 T2). 이미 있던 실행의 `revise` 가 낸
+     * Accepted 와 IDEMPOTENT 는 적지 않는다. pump 도 이 잠금을 잡으므로 일지에 없는 실행은 로봇 명령을 낸 적이 없다. 일지 쓰기가
+     * 실패하면 [JournalWriteFailed] 이고 실행은 미들웨어에 남는다(§9, 한계).
      */
     fun submit(order: JobOrder, candidates: List<String>): SubmitOutcome = lock.withLock {
         val applied = siteTimings.current() != null
         val judged = candidates.distinct().map { judge(order, it, applied) }
         val excluded = judged.filter { !it.passed }
+        val existed = middleware.executions().any { it.order.jobOrderId == order.jobOrderId }
         when (val submission = middleware.assign(order, judged.filter { it.passed }.map { it.robotId })) {
-            is Middleware.Submission.Accepted -> SubmitOutcome(
-                SubmitResult.ACCEPTED, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
-            )
+            is Middleware.Submission.Accepted -> {
+                if (!existed) journal(submission.execution)
+                SubmitOutcome(
+                    SubmitResult.ACCEPTED, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
+                )
+            }
             is Middleware.Submission.Idempotent -> SubmitOutcome(
                 SubmitResult.IDEMPOTENT, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
             )
@@ -233,6 +456,36 @@ class MissionHost(
         }
     }
 
+    /** 새 실행의 일지 행을 적는다. 작업 지시는 picasso `JobOrder` 의 칸 그대로의 JSON 이다. */
+    private fun journal(execution: Middleware.Execution) {
+        val order = execution.order
+        try {
+            records.journal(
+                order.jobOrderId, execution.robotId, orderJson(order), order.workMasterId, execution.missionVersion,
+                middleware.instanceId, execution.executionId,
+            )
+        } catch (e: Exception) {
+            throw JournalWriteFailed(execution.executionId, e)
+        }
+        journaled += order.jobOrderId
+    }
+
+    private fun orderJson(order: JobOrder): String = json.writeValueAsString(
+        linkedMapOf(
+            "jobOrderId" to order.jobOrderId,
+            "workMasterId" to order.workMasterId,
+            "version" to order.version,
+            "requiredEvidence" to order.requiredEvidence.name,
+            "parameters" to order.parameters,
+            "materialRequirements" to order.materialRequirements.map {
+                linkedMapOf("materialDefinitionId" to it.materialDefinitionId, "quantity" to it.quantity)
+            },
+            "equipmentRequirements" to order.equipmentRequirements.map {
+                linkedMapOf("id" to it.id, "equipmentUse" to it.equipmentUse, "properties" to it.properties)
+            },
+        ),
+    )
+
     fun executions(): ExecutionsView = lock.withLock {
         val responses = middleware.responses()
         ExecutionsView(
@@ -248,31 +501,136 @@ class MissionHost(
                     physicalState = execution.physicalState.name,
                     units = execution.units.map { UnitView(it.unitId, it.skillType, it.state.name, it.reached.name) },
                     jobResponse = responses.lastOrNull { it.executionId == execution.executionId }?.let(::view),
+                    restoredFrom = restoredFrom[execution.executionId],
                 )
             },
+            restore = restoreView(),
         )
     }
 
     /**
      * 봉인된 인시던트(S3c 스펙 §7.2, S4a 스펙 §6). 최신부터 많아야 [limit] 개다. 번들은 미들웨어 안의 값이라 호스트 잠금 아래에서
      * 옮긴다. 보류 중 여부는 자르기 전의 전부로 정한다.
+     *
+     * 이전 인스턴스의 사본은 [IncidentsView.earlier] 에 따로 싣는다(S4b 스펙 T7). 적은 순서의 역순으로 많아야 [limit] 개다. 사본은
+     * 잠금 밖에서 DB 로 읽는다.
      */
-    fun incidents(limit: Int): IncidentsView = lock.withLock {
-        val all = middleware.incidents()
-        val held = heldIncidents(all)
-        IncidentsView(
-            instanceId = middleware.instanceId,
-            total = all.size,
-            incidents = all.asReversed().take(limit).map { IncidentViews.item(it, it.incidentId in held) },
+    fun incidents(limit: Int): IncidentsView {
+        val (total, live) = lock.withLock {
+            val all = middleware.incidents()
+            val held = heldIncidents(all)
+            all.size to all.asReversed().take(limit).map { IncidentViews.item(it, it.incidentId in held) }
+        }
+        return IncidentsView(
+            instanceId = instanceId,
+            total = total,
+            incidents = live,
+            earlierTotal = records.earlierCopyCount(instanceId),
+            earlier = records.earlierCopies(instanceId, limit).map { row ->
+                EarlierIncidentView(row.instanceId, IncidentViews.item(copyDetail(row)))
+            },
         )
     }
 
-    /** 인시던트 하나의 상세(S4a 스펙 §6). 없으면 `null` 이다. 호스트 잠금 아래에서 읽는다. */
-    fun incident(incidentId: String): IncidentDetailView? = lock.withLock {
-        val bundle = middleware.incident(incidentId) ?: return@withLock null
-        val unitState = middleware.executions().firstOrNull { it.executionId == bundle.executionId }
+    /**
+     * 인시던트 하나의 상세(S4a 스펙 §6). 없으면 `null` 이다. [ofInstance] 가 없거나 지금 인스턴스면 호스트 잠금 아래에서 미들웨어의
+     * 번들을 읽는다. 이전 인스턴스면 그 인스턴스의 사본을 돌려준다(S4b 스펙 T7).
+     */
+    fun incident(incidentId: String, ofInstance: String? = null): IncidentDetailView? {
+        if (ofInstance != null && ofInstance != instanceId) return records.incidentCopy(ofInstance, incidentId)?.let(::copyDetail)
+        return lock.withLock { liveDetail(incidentId) }
+    }
+
+    private fun liveDetail(incidentId: String): IncidentDetailView? {
+        val bundle = middleware.incident(incidentId) ?: return null
+        return IncidentViews.detail(middleware.instanceId, bundle, incidentId in heldIncidents(middleware.incidents()), unitStateOf(bundle))
+    }
+
+    private fun unitStateOf(bundle: IncidentBundle): String? =
+        middleware.executions().firstOrNull { it.executionId == bundle.executionId }
             ?.units?.firstOrNull { it.unitId == bundle.unitId }?.state?.name
-        IncidentViews.detail(middleware.instanceId, bundle, incidentId in heldIncidents(middleware.incidents()), unitState)
+
+    /**
+     * 사본의 상세. 판단은 판단 행으로 다시 세우고 근거 없는 완료 확인도 그것으로 다시 계산한다. 이전 인스턴스의 인시던트는 보류 중이
+     * 아니고, 그 실행은 이 인스턴스에 없으므로 단위의 지금 상태는 `null` 이다.
+     */
+    private fun copyDetail(row: IncidentCopyRow): IncidentDetailView {
+        val stored = json.readValue<IncidentDetailView>(row.detail)
+        val resolution = row.resolution?.let { ResolutionView(it.decision, it.at, it.wallClockAt, ApproverView(it.decidedById, it.decidedByKind)) }
+        return stored.copy(
+            resolution = resolution,
+            held = false,
+            confirmedWithoutEvidence = resolution?.decision == OperatorDecision.CONFIRM_DONE.name &&
+                stored.verification != Verification.MATCHED.name,
+            unitState = null,
+        )
+    }
+
+    /** 송신 기록(S4b 스펙 T6). 최근부터 많아야 [limit] 개다. DB 만 읽고 호스트 잠금을 잡지 않는다. */
+    fun jobResponses(jobOrderId: String?, limit: Int): JobResponsesView = JobResponsesView(
+        instanceId = instanceId,
+        total = records.responseLogCount(jobOrderId),
+        responses = records.responseLog(jobOrderId, limit).map(JobResponseLogView::of),
+    )
+
+    /**
+     * pump 뒤 기록(S4b 스펙 §6.2, T6·T7). 같은 잠금 안에서 한 트랜잭션으로 송신 기록, 새로 봉인된 인시던트의 사본, 아직 판단 행이
+     * 없는 판단, 아직 정착 이벤트가 없는 정착한 실행을 적는다. 트랜잭션이 실패하면 아무것도 `ack` 하지 않고 다음 pump 에 다시 한다.
+     *
+     * 송신 기록: 새 인스턴스가 그 작업 지시에 대해 처음 내는 응답이 그 작업 지시의 가장 최근 송신 행(인스턴스 무관)과 내용 키가
+     * 같으면 재기동 중복으로 적고 송신하지 않는다. 그 밖에는 송신으로 적는다. 어느 쪽이든 적은 뒤 `ack` 한다.
+     *
+     * 정착은 pump 가 더 돌리지 않는 상태다(`PARTIAL` 은 미들웨어가 계속 돌리므로 정착으로 적지 않는다).
+     */
+    private fun recordAfterPump() {
+        val pending = middleware.pending()
+        val incidents = middleware.incidents()
+        val fresh = incidents.drop(copiedIncidents)
+        val resolved = incidents.filter { it.resolution != null && it.incidentId !in copiedResolutions }
+        val settled = middleware.executions().filter {
+            it.physicalState.isSettled && it.physicalState != PhysicalState.PARTIAL &&
+                it.order.jobOrderId in journaled && it.order.jobOrderId !in settledRecorded
+        }
+        if (pending.isEmpty() && fresh.isEmpty() && resolved.isEmpty() && settled.isEmpty()) return
+
+        val held = heldIncidents(incidents)
+        val copies = fresh.map { it to json.writeValueAsString(IncidentViews.detail(instanceId, it, it.incidentId in held, unitStateOf(it))) }
+        try {
+            records.inTransaction {
+                pending.forEach(::logResponse)
+                copies.forEach { (bundle, detail) ->
+                    records.copyIncident(instanceId, bundle.incidentId, bundle.executionId, bundle.jobOrderId, bundle.unitId, detail)
+                }
+                resolved.forEach { bundle ->
+                    val resolution = bundle.resolution!!
+                    records.copyResolution(
+                        instanceId, bundle.incidentId,
+                        CopyResolutionRow(
+                            resolution.decision.name, resolution.at, resolution.wallClockAt, resolution.decidedBy.id, resolution.decidedBy.kind.name,
+                        ),
+                    )
+                }
+                settled.forEach {
+                    records.event(it.order.jobOrderId, JournalEventKind.SETTLED, instanceId, it.executionId, it.physicalState.name)
+                }
+            }
+        } catch (e: Exception) {
+            log.warn("pump 뒤 기록 실패, 다음 pump 에 다시 한다: {}", e.toString())
+            return
+        }
+        copiedIncidents = incidents.size
+        copiedResolutions += resolved.map { it.incidentId }
+        settledRecorded += settled.map { it.order.jobOrderId }
+        pending.forEach { middleware.ack(it.jobResponseId) }
+    }
+
+    private fun logResponse(response: JobResponse) {
+        val content = contentOf(response)
+        val duplicate = !records.loggedIn(instanceId, response.jobOrderId) && records.lastSent(response.jobOrderId)?.content == content
+        records.logResponse(
+            instanceId, response.jobResponseId, response.executionId, content,
+            if (duplicate) ResponseDisposition.RESTART_DUPLICATE else ResponseDisposition.SENT,
+        )
     }
 
     /**
@@ -350,11 +708,44 @@ class MissionHost(
                 SkillFit.UNKNOWN -> add("기체 케이퍼빌리티를 못 물어봤다")
             }
             if (running != null) add("도는 실행이 있다: $running")
+            unrestoredOn(robotId).takeIf { it.isNotEmpty() }?.let { add("$UNRESTORED_REASON: ${it.joinToString(", ")}") }
             if (!applied) add(UNAPPLIED_REASON)
         }
         return HostEligibility(robotId, fit, missing, running, passed = reasons.isEmpty(), reasons = reasons)
     }
 
+    /**
+     * 이 기체에서 다시 짓지 못한 작업 지시(T4). 미룬 행은 늘 든다. 포기한 행은 그 기체의 스냅숏을 이번에 한 번 읽어, 그 작업 지시의
+     * 비종착 태스크(id 가 `jobOrderId#` 로 시작, `@rN` 이 붙은 재작업 태스크 포함)가 없으면 풀고 더 빼지 않는다. 스냅숏을 못 읽으면
+     * 계속 뺀다.
+     */
+    private fun unrestoredOn(robotId: String): List<String> {
+        val waiting = deferred.filter { it.row.robotId == robotId }.map { it.row.jobOrderId }
+        val abandoned = gaveUp.filter { it.robotId == robotId }
+        if (abandoned.isEmpty()) return waiting
+        val snapshot = robots.snapshot(robotId) ?: return waiting + abandoned.map { it.jobOrderId }
+        val still = abandoned.filter { row ->
+            snapshot.tasks.any { (taskId, state) -> taskId.startsWith("${row.jobOrderId}#") && state !in TERMINAL_TASK_STATES }
+        }
+        gaveUp.removeAll((abandoned - still.toSet()).toSet())
+        return waiting + still.map { it.jobOrderId }
+    }
+
+    private fun contentOf(response: JobResponse) = ResponseContent(
+        jobOrderId = response.jobOrderId,
+        version = response.version,
+        physicalState = response.physicalState.name,
+        requiredEvidence = response.requiredEvidence.name,
+        reachedEvidence = response.reachedEvidence.name,
+        completedUnits = response.completedUnits.sorted(),
+        unverifiedUnits = response.unverifiedUnits.sorted(),
+        incompleteUnits = response.incompleteUnits.keys.sorted(),
+        inDoubtUnits = response.inDoubtUnits.sorted(),
+        operatorRequired = response.operatorRequired,
+        residualHold = response.residualHold.kind.name,
+        blockedBy = response.blockedBy.sorted(),
+    )
+
     private fun view(response: JobResponse) = JobResponseView(
         jobResponseId = response.jobResponseId,
         version = response.version,
@@ -385,6 +776,15 @@ class MissionHost(
         /** 현장 시간값 미적용 동안 판정이 기체마다 더하는 이유(S3c 스펙 T8). 화면에 그대로 보인다. */
         const val UNAPPLIED_REASON = "현장 시간값 미적용: 실행 호스트가 현장 설정을 아직 읽지 못했다"
 
+        /** 다시 짓지 못한 실행이 있는 기체에 판정이 더하는 이유의 앞부분(S4b 스펙 T4). 뒤에 작업 지시 id 가 붙는다. */
+        const val UNRESTORED_REASON = "복원 못 한 실행이 있다"
+
+        /** 종착한 태스크 상태. 미들웨어가 단위의 종착으로 보는 것과 같다(사람을 기다리는 RETRIABLE·NEEDS_INTERVENTION 포함). */
+        private val TERMINAL_TASK_STATES = setOf(
+            TaskState.TASK_STATE_SUCCEEDED, TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_CANCELLED,
+            TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED, TaskState.TASK_STATE_NEEDS_INTERVENTION, TaskState.TASK_STATE_RETRIABLE,
+        )
+
         /** 운영자 판단의 결과 이름. picasso `ResolveOutcome` 의 이름 그대로다(S4a 스펙 T6). */
         const val RESOLVED = "Resolved"
         const val NOT_HELD = "NotHeld"
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt
index 7a5bb18..bb26c1b 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt
@@ -9,6 +9,7 @@ import dev.picasso.ops.host.mission.MissionVersions
 import dev.picasso.ops.host.mission.MockRunner
 import dev.picasso.ops.host.mission.StoredMissionCatalog
 import dev.picasso.ops.host.store.HostSchema
+import dev.picasso.ops.host.store.HostRecords
 import dev.picasso.ops.host.store.HostSchemaMigrated
 import dev.picasso.ops.host.store.MissionStore
 import dev.picasso.ops.host.timings.SiteTimingsReader
@@ -22,6 +23,8 @@ import org.springframework.context.ApplicationContextInitializer
 import org.springframework.context.ConfigurableApplicationContext
 import org.springframework.context.annotation.Bean
 import org.springframework.jdbc.core.simple.JdbcClient
+import org.springframework.jdbc.datasource.DataSourceTransactionManager
+import org.springframework.transaction.support.TransactionTemplate
 import java.nio.file.Path
 import java.time.Duration
 import javax.sql.DataSource
@@ -47,7 +50,9 @@ open class MissionHostApplication {
         return ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
     }
 
+    /** 시험은 [builder] 로 다른 포트를 먼저 넣고, 그러면 이 빈은 만들어지지 않는다. */
     @Bean
+    @ConditionalOnMissingBean(RobotPort::class)
     open fun robotPort(channel: ManagedChannel): RobotPort = ClientRobotPort(PicassoClient(channel, CLIENT_ID))
 
     @Bean
@@ -83,6 +88,16 @@ open class MissionHostApplication {
         @Value("\${host.site-timings.read-interval}") interval: Duration,
     ): SiteTimingsReader = SiteTimingsReader(jdbc, clock).start(interval)
 
+    /** 실행 일지, 송신 기록, 인시던트 사본(S4b 스펙 §6.1). pump 뒤 기록은 이 데이터 소스의 트랜잭션 하나로 묶는다. */
+    @Bean
+    open fun hostRecords(
+        jdbc: JdbcClient,
+        dataSource: DataSource,
+        json: ObjectMapper,
+        @Suppress("UNUSED_PARAMETER") migrated: HostSchemaMigrated,
+    ): HostRecords = HostRecords(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), json)
+
+    /** 빈을 만들 때 일지로 실행을 복원하고 pump 를 켠다(S4b 스펙 T3). 웹 서버는 그 뒤에 열린다. */
     @Bean(destroyMethod = "close")
     open fun missionHost(
         robots: RobotPort,
@@ -90,7 +105,10 @@ open class MissionHostApplication {
         clock: HostClock,
         catalog: StoredMissionCatalog,
         timings: SiteTimingsReader,
-    ): MissionHost = MissionHost(robots, cellBand, clock, catalog, timings).start()
+        records: HostRecords,
+        store: MissionStore,
+        json: ObjectMapper,
+    ): MissionHost = MissionHost(robots, cellBand, clock, catalog, timings, records, store, json).start()
 
     /**
      * 모의 실행기(S3b 스펙 §6.4). 프로파일과 스키마 경로는 작업 디렉터리 기준으로 푼다. `:mission-host:run` 은 저장소 루트에서
@@ -121,14 +139,16 @@ open class MissionHostApplication {
          * 설정 파일 이름을 `mission-host` 로 둔다. 같은 JVM 의 registry `application.properties`·운영 서비스 설정과 가리지 않게.
          *
          * @param clock 주면 기본 시계 대신 이것을 쓴다(통합 시험이 현장 시계를 넣는다).
+         * @param robots 주면 기본 하위 포트 대신 이것을 쓴다(호스트 시험이 기체 스냅숏을 막는 포트를 넣는다).
          */
-        fun builder(clock: HostClock? = null): SpringApplicationBuilder {
+        fun builder(clock: HostClock? = null, robots: RobotPort? = null): SpringApplicationBuilder {
             val builder = SpringApplicationBuilder(MissionHostApplication::class.java)
                 .properties("spring.config.name=mission-host")
-            if (clock != null) {
+            if (clock != null || robots != null) {
                 builder.initializers(
                     ApplicationContextInitializer<ConfigurableApplicationContext> {
-                        it.beanFactory.registerSingleton("hostClock", clock)
+                        clock?.let { c -> it.beanFactory.registerSingleton("hostClock", c) }
+                        robots?.let { r -> it.beanFactory.registerSingleton("testRobotPort", r) }
                     },
                 )
             }
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt
index 30d9846..c9f3da3 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt
@@ -4,14 +4,17 @@ import com.fasterxml.jackson.databind.JsonNode
 import com.fasterxml.jackson.databind.ObjectMapper
 import dev.picasso.ops.host.ExecutionsView
 import dev.picasso.ops.host.HostEligibility
+import dev.picasso.ops.host.JournalWriteFailed
 import dev.picasso.ops.host.MissionHost
 import dev.picasso.ops.host.cell.CellSnapshot
 import org.springframework.http.HttpStatus
 import org.springframework.http.MediaType
 import org.springframework.http.ResponseEntity
+import org.springframework.web.bind.annotation.ExceptionHandler
 import org.springframework.web.bind.annotation.GetMapping
 import org.springframework.web.bind.annotation.PostMapping
 import org.springframework.web.bind.annotation.RequestBody
+import org.springframework.web.bind.annotation.RequestParam
 import org.springframework.web.bind.annotation.RestController
 
 /** `POST /host/eligibility` 의 본문. 요청의 기체 순서이며 겹친 id 는 한 번만 나온다. */
@@ -46,6 +49,31 @@ class HostController(private val host: MissionHost, private val json: ObjectMapp
     @GetMapping("/host/cell")
     fun cell(): CellView = CellView(host.cell())
 
+    /**
+     * 송신 기록(S4b 스펙 T6). 최근부터 많아야 [limit] 개(기본 50, 1~500). [jobOrderId] 를 주면 그 작업 지시만이다. limit 이 틀리거나
+     * jobOrderId 가 빈 문자열이면 400 `BAD_REQUEST` 다.
+     */
+    @GetMapping("/host/job-responses")
+    fun jobResponses(@RequestParam(required = false) jobOrderId: String?, @RequestParam(required = false) limit: String?): ResponseEntity<Any> {
+        val count = if (limit == null) IncidentController.DEFAULT_LIMIT else limit.toIntOrNull()?.takeIf { it in 1..IncidentController.MAX_LIMIT }
+            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
+                .body(HostRejection(HostRequests.BAD_REQUEST, "limit 은 1~${IncidentController.MAX_LIMIT} 의 정수다: $limit"))
+        if (jobOrderId != null && jobOrderId.isBlank()) {
+            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(HostRequests.BAD_REQUEST, "jobOrderId 가 비어 있다"))
+        }
+        return ResponseEntity.ok(host.jobResponses(jobOrderId, count))
+    }
+
+    /** 일지 쓰기 실패(S4b 스펙 §9). 실행은 미들웨어에 남는다. */
+    @ExceptionHandler(JournalWriteFailed::class)
+    fun journalFailed(e: JournalWriteFailed): ResponseEntity<Any> =
+        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(HostRejection(JOURNAL_WRITE_FAILED, e.message ?: ""))
+
+    companion object {
+        /** 일지 쓰기 실패의 오류 이름. */
+        const val JOURNAL_WRITE_FAILED = "JOURNAL_WRITE_FAILED"
+    }
+
     private inline fun accepting(
         body: ByteArray?,
         listField: String,
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt
index 9a89a67..eb7be8d 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt
@@ -14,9 +14,9 @@ data class HostRejection(val error: String, val detail: String)
 
 /**
  * 운영자 판단 요청(S4a 스펙 T6). [requestId] 는 운영 서비스가 실은 요청 id 이며 호스트는 응답에 그대로 돌려줄 뿐 저장하지 않는다.
- * 응답 없음 뒤의 재조회는 인시던트의 판단으로 대조한다.
+ * 응답 없음 뒤의 재조회는 인시던트의 판단으로 대조한다. [instanceId] 는 운영 서비스가 상세에서 받은 인스턴스다(S4b 스펙 T8).
  */
-data class ResolveRequest(val decision: OperatorDecision, val approverId: String, val requestId: String?)
+data class ResolveRequest(val decision: OperatorDecision, val approverId: String, val requestId: String?, val instanceId: String)
 
 /** 본문을 못 받는 까닭. [error] 가 응답의 `error` 칸이다. */
 class BadRequest(val error: String, detail: String) : RuntimeException(detail)
@@ -72,8 +72,8 @@ object HostRequests {
     }
 
     /**
-     * `{decision, approverId, requestId?}` 를 읽는다. decision 은 `CONFIRM_DONE`·`REWORK` 이고 approverId 는 비어 있지 않은
-     * 문자열이다. requestId 는 없거나 `null` 이거나 UUID 문자열이다.
+     * `{decision, approverId, requestId?, instanceId}` 를 읽는다. decision 은 `CONFIRM_DONE`·`REWORK` 이고 approverId 와
+     * instanceId 는 비어 있지 않은 문자열이다(S4b 스펙 T8). requestId 는 없거나 `null` 이거나 UUID 문자열이다.
      */
     fun resolution(body: JsonNode?): ResolveRequest {
         if (body == null || !body.isObject) throw BadRequest(BAD_REQUEST, "본문이 JSON 객체가 아니다")
@@ -85,7 +85,7 @@ object HostRequests {
             node.takeIf { it.isTextual }?.asText()?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
                 ?: throw BadRequest(BAD_REQUEST, "requestId 가 UUID 문자열이 아니다")
         }
-        return ResolveRequest(decision, text(body, "approverId"), requestId)
+        return ResolveRequest(decision, text(body, "approverId"), requestId, text(body, "instanceId"))
     }
 
     /** 비어 있지 않은 문자열 칸. */
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/web/IncidentController.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/web/IncidentController.kt
index 5fbcfa7..976a7d3 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/web/IncidentController.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/web/IncidentController.kt
@@ -38,18 +38,27 @@ class IncidentController(
         return ResponseEntity.ok(host.incidents(count))
     }
 
-    /** 인시던트 하나의 상세. 없으면 404 `INCIDENT_NOT_FOUND` 다. 호스트를 재기동하면 앞 인시던트는 사라진다(S4b 의 자리). */
+    /**
+     * 인시던트 하나의 상세. 없으면 404 `INCIDENT_NOT_FOUND` 다. 질의 [instanceId] 가 없거나 지금 인스턴스면 지금 인스턴스의 것이고,
+     * 이전 인스턴스면 그 인스턴스의 사본이다(S4b 스펙 T7). 빈 문자열이면 400 `BAD_REQUEST` 다.
+     */
     @GetMapping("/host/incidents/{incidentId}")
-    fun incident(@PathVariable incidentId: String): ResponseEntity<Any> {
-        val detail = host.incident(incidentId)
+    fun incident(@PathVariable incidentId: String, @RequestParam(required = false) instanceId: String?): ResponseEntity<Any> {
+        if (instanceId != null && instanceId.isBlank()) {
+            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(HostRequests.BAD_REQUEST, "instanceId 가 비어 있다"))
+        }
+        val detail = host.incident(incidentId, instanceId)
             ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
-                .body(HostRejection(INCIDENT_NOT_FOUND, "그 인시던트가 없다: $incidentId"))
+                .body(HostRejection(INCIDENT_NOT_FOUND, "그 인시던트가 없다: $incidentId" + (instanceId?.let { " (인스턴스 $it)" } ?: "")))
         return ResponseEntity.ok(detail)
     }
 
     /**
      * 운영자 판단. 승인자는 늘 `Approver(approverId, PERSON)` 이다(ADR 43 에 따라 기본값이 없고 approverId 는 필수다). 실행이 없거나
      * 그 단위가 보류가 아니면 NotHeld 이며 404 를 내지 않는다.
+     *
+     * 본문의 `instanceId` 가 지금 인스턴스가 아니면 409 `INSTANCE_MISMATCH` 이고 판단하지 않는다(S4b 스펙 T8). `exec-N` 은 인스턴스마다
+     * 다시 세므로 이전 인스턴스의 실행 id 가 지금 인스턴스의 다른 실행을 가리킬 수 있다.
      */
     @PostMapping("/host/executions/{executionId}/units/{unitId}/resolve", consumes = [MediaType.APPLICATION_JSON_VALUE])
     fun resolve(
@@ -62,6 +71,11 @@ class IncidentController(
         } catch (e: BadRequest) {
             return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
         }
+        if (request.instanceId != host.instanceId) {
+            return ResponseEntity.status(HttpStatus.CONFLICT).body(
+                HostRejection(INSTANCE_MISMATCH, "판단 요청의 인스턴스(${request.instanceId})가 지금 인스턴스(${host.instanceId})가 아니다"),
+            )
+        }
         return ResponseEntity.ok(
             host.resolve(executionId, unitId, request.decision, Approver(request.approverId, ApproverKind.PERSON), request.requestId),
         )
@@ -87,5 +101,8 @@ class IncidentController(
 
         /** 오류 이름(S4a JSON 계약 §3). */
         const val INCIDENT_NOT_FOUND = "INCIDENT_NOT_FOUND"
+
+        /** 판단 요청의 인스턴스가 지금 인스턴스가 아니다(S4b 스펙 T8). */
+        const val INSTANCE_MISMATCH = "INSTANCE_MISMATCH"
     }
 }
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
index c7892a4..2aeb35d 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
@@ -3,7 +3,13 @@ package dev.picasso.ops.host
 import com.fasterxml.jackson.databind.JsonNode
 import com.fasterxml.jackson.databind.ObjectMapper
 import com.sun.net.httpserver.HttpServer
+import dev.picasso.client.PicassoClient
+import dev.picasso.middleware.ClientRobotPort
+import dev.picasso.middleware.RobotPort
+import dev.picasso.middleware.RobotSnapshot
 import dev.picasso.mimic.cli.MimicCli
+import io.grpc.ManagedChannel
+import io.grpc.ManagedChannelBuilder
 import dev.picasso.ops.host.timings.SiteTimingsView
 import dev.picasso.registry.PostgresSupport
 import org.springframework.boot.web.context.WebServerApplicationContext
@@ -17,6 +23,7 @@ import java.net.http.HttpResponse
 import java.nio.file.Path
 import java.time.Duration
 import java.time.Instant
+import java.util.concurrent.ConcurrentHashMap
 import java.util.concurrent.CopyOnWriteArrayList
 
 /**
@@ -35,13 +42,35 @@ import java.util.concurrent.CopyOnWriteArrayList
  * @param mockVirtualLimit 주면 모의 실행의 가상 시간 상한을 이것으로 덮는다.
  * @param readInterval 주면 현장 시간값 읽기 주기를 이것으로 덮는다. 길게 주면 기동 안의 첫 읽기만 일어난다.
  * @param timings 대역 뷰의 한 행(버전, 앞 폭, 뒤 폭, inDoubtGrace, stallWindow). `null` 이면 뷰를 만들지 않아 호스트가 미적용으로 뜬다.
+ * @param gated 참이면 호스트의 하위 포트를 [blockedSnapshots] 에 든 기체의 스냅숏을 못 읽는 포트로 넣는다(S4b 의 복원 미룸).
+ *   기동마다 새 채널과 새 `ClientRobotPort` 다(재기동한 호스트가 핸들을 처음부터 다시 받는 것과 같게).
  */
 class HostBench(
     private val mockVirtualLimit: Duration? = null,
     private val readInterval: Duration? = null,
     timings: List<Long>? = STANDARD_TIMINGS,
+    private val gated: Boolean = false,
 ) : AutoCloseable {
 
+    /** [gated] 일 때 스냅숏을 못 읽는 기체. 바꾸면 다음 호출부터 미친다. */
+    val blockedSnapshots: MutableSet<String> = ConcurrentHashMap.newKeySet()
+
+    private val channels = CopyOnWriteArrayList<ManagedChannel>()
+
+    /** mimic 에 붙는 새 포트. 시험이 미들웨어 밖에서 태스크를 직접 낼 때(고아 태스크) 쓴다. */
+    fun clientPort(clientId: String = MissionHostApplication.CLIENT_ID): ClientRobotPort {
+        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", mimic.server.port).usePlaintext().build()
+        channels += channel
+        return ClientRobotPort(PicassoClient(channel, clientId))
+    }
+
+    private fun gatedPort(): RobotPort {
+        val delegate = clientPort()
+        return object : RobotPort by delegate {
+            override fun snapshot(robotId: String): RobotSnapshot? = if (robotId in blockedSnapshots) null else delegate.snapshot(robotId)
+        }
+    }
+
     init {
         PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
         PostgresSupport.execute("DROP SCHEMA IF EXISTS ops CASCADE")
@@ -147,7 +176,7 @@ class HostBench(
     val host: MissionHost get() = context.getBean(MissionHost::class.java)
 
     /** 이 세트와 같은 인자로 호스트를 하나 띄운다. 기동이 실패하면 예외가 그대로 나간다. */
-    fun startHost(): ConfigurableApplicationContext = MissionHostApplication.builder(HostClock { now() }).run(
+    fun startHost(): ConfigurableApplicationContext = MissionHostApplication.builder(HostClock { now() }, if (gated) gatedPort() else null).run(
         *buildList {
             add("--server.port=0")
             add("--host.mimic.port=${mimic.server.port}")
@@ -212,6 +241,18 @@ class HostBench(
         error("pump 가 $target 에 이르지 않았다")
     }
 
+    /** [condition] 이 참이 될 때까지 기다린다(실제 시간 상한 5초). 가상 시계를 밀지 않는 pump 뒤 기록을 기다릴 때 쓴다. */
+    fun eventually(what: String, condition: () -> Boolean) {
+        val deadline = Instant.now().plusSeconds(5)
+        while (!condition()) {
+            check(Instant.now().isBefore(deadline)) { "5초 안에 이르지 않았다: $what" }
+            Thread.sleep(50)
+        }
+    }
+
+    /** pump [count] 번이 돌 만큼 실제 시간을 보낸다. 무엇이 «더 생기지 않음» 을 볼 때 쓴다. */
+    fun idlePumps(count: Int = 3) = Thread.sleep(MissionHost.PUMP_PERIOD.toMillis() * count + 100)
+
     /**
      * 실행이 [states] 중 하나가 될 때까지 가상 시계를 5초씩 민다. 민 뒤에는 스트림 갱신이 클라이언트에 닿을 틈을 주려고
      * pump 두 번을 기다린다.
@@ -232,6 +273,7 @@ class HostBench(
             context.close()
         } finally {
             cell.stop(0)
+            channels.forEach { it.shutdownNow() }
             mimic.server.shutdown()
         }
     }
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/IncidentResolveTest.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/IncidentResolveTest.kt
index 79ec114..de36a3a 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/IncidentResolveTest.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/IncidentResolveTest.kt
@@ -65,7 +65,9 @@ class IncidentResolveTest {
     private fun HostBench.resolve(executionId: String, unitId: String, decision: String, approverId: String, requestId: String? = null): HostBench.Reply =
         post(
             "/host/executions/$executionId/units/$unitId/resolve",
-            JSON.writeValueAsString(linkedMapOf("decision" to decision, "approverId" to approverId, "requestId" to requestId)),
+            JSON.writeValueAsString(
+                linkedMapOf("decision" to decision, "approverId" to approverId, "requestId" to requestId, "instanceId" to host.instanceId),
+            ),
         )
 
     private fun HostBench.incidents(): List<JsonNode> = get("/host/incidents")["incidents"].toList()
@@ -274,19 +276,53 @@ class IncidentResolveTest {
     @Test
     fun `판단 요청 본문이 틀리면 400 BAD_REQUEST 이고 JSON 이 아니면 415 다`() {
         HostBench().use { bench ->
+            val instance = bench.host.instanceId
             listOf(
-                """{"decision":"RELEASE","approverId":"kim"}""",
-                """{"decision":"REWORK"}""",
-                """{"decision":"REWORK","approverId":" "}""",
-                """{"decision":"REWORK","approverId":"kim","requestId":"not-a-uuid"}""",
-                """{"decision":"REWORK","approverId":"kim","requestId":7}""",
+                """{"decision":"RELEASE","approverId":"kim","instanceId":"$instance"}""",
+                """{"decision":"REWORK","instanceId":"$instance"}""",
+                """{"decision":"REWORK","approverId":" ","instanceId":"$instance"}""",
+                """{"decision":"REWORK","approverId":"kim","requestId":"not-a-uuid","instanceId":"$instance"}""",
+                """{"decision":"REWORK","approverId":"kim","requestId":7,"instanceId":"$instance"}""",
                 """["REWORK"]""",
             ).forEach { body ->
                 val reply = bench.post("/host/executions/exec-1/units/$WAIT/resolve", body)
                 assertEquals(400, reply.status, body)
                 assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), body)
             }
-            assertEquals(415, bench.post("/host/executions/exec-1/units/$WAIT/resolve", """{"decision":"REWORK","approverId":"kim"}""", contentType = "text/plain").status)
+            assertEquals(
+                415,
+                bench.post("/host/executions/exec-1/units/$WAIT/resolve", """{"decision":"REWORK","approverId":"kim","instanceId":"$instance"}""", contentType = "text/plain").status,
+            )
+        }
+    }
+
+    @Test
+    fun `판단 요청에 instanceId 가 없으면 400 이고 지금 인스턴스가 아니면 409 INSTANCE_MISMATCH 이며 판단하지 않는다`() {
+        HostBench().use { bench ->
+            val executionId = bench.submitHeldOrder()
+            bench.driveToHold(executionId)
+            val path = "/host/executions/$executionId/units/$WAIT/resolve"
+
+            listOf(
+                """{"decision":"REWORK","approverId":"kim"}""",
+                """{"decision":"REWORK","approverId":"kim","instanceId":null}""",
+                """{"decision":"REWORK","approverId":"kim","instanceId":" "}""",
+                """{"decision":"REWORK","approverId":"kim","instanceId":7}""",
+            ).forEach { body ->
+                val reply = bench.post(path, body)
+                assertEquals(400, reply.status, body)
+                assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), body)
+            }
+
+            val mismatch = bench.post(path, """{"decision":"REWORK","approverId":"kim","instanceId":"mw-earlier"}""")
+            assertEquals(409, mismatch.status, mismatch.body.toString())
+            assertEquals(listOf("error", "detail"), mismatch.body!!.fieldNames().asSequence().toList())
+            assertEquals("INSTANCE_MISMATCH", mismatch.body["error"].asText())
+            val untouched = bench.incidents().single()
+            assertTrue(untouched["resolution"].isNull && untouched["held"].asBoolean(), untouched.toString())
+            assertEquals("OPERATOR_HOLD", bench.execution(executionId)!!["units"][0]["state"].asText())
+
+            assertEquals("Resolved", bench.resolve(executionId, WAIT, "REWORK", "kim").body!!["result"].asText())
         }
     }
 
```

- [ ] **Step 6: 커밋 2/4 `feat(mission-host): 일지 쓰기, pump 뒤 기록, 이전 인스턴스 인시던트, 판단의 인스턴스`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt mission-host/src/main/kotlin/dev/picasso/ops/host/IncidentViews.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/IncidentController.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt mission-host/src/test/kotlin/dev/picasso/ops/host/IncidentResolveTest.kt && git commit -F - <<'EOF'
feat(mission-host): 일지 쓰기, pump 뒤 기록, 이전 인스턴스 인시던트, 판단의 인스턴스

- 새 실행의 ACCEPTED 제출만 응답 전 일지 행 기록(revise 의 Accepted·IDEMPOTENT 제외), 실패 시 500 JOURNAL_WRITE_FAILED
- pump 뒤 한 트랜잭션: 송신 기록(새 인스턴스 첫 응답이 최근 송신과 같으면 RESTART_DUPLICATE), 인시던트 사본, 판단 행, 정착 이벤트, 성공 뒤 ack
- GET /host/job-responses, GET /host/incidents 의 earlierTotal·earlier, 상세의 instanceId 질의
- 판단 요청 instanceId 필수(없으면 400, 다르면 409 INSTANCE_MISMATCH)
- 복원 보고(restore)·restoredFrom 칸과 DEFERRED·GAVE_UP 기체의 판정 제외(스냅숏 못 읽으면 계속 제외)
- HostBench: 스냅숏을 막는 포트, eventually·idlePumps
- 시험: HostJournalTest 다섯, IncidentResolveTest 의 instanceId 400·409

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 7: (커밋 3/4) 새 파일 1개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && mkdir -p mission-host/src/test/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/files/mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt" mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt
```

`mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt`:

```kotlin
package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.MATERIAL
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.STANDARD_CELL
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.registry.PostgresSupport
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 기동 복원과 재기동 중복(S4b 스펙 T3·T5·T6, §6.3·§6.4). 호스트만 다시 띄우고 mimic 과 DB 는 그대로다(e2e 의 재기동과 같은 배선).
 */
class HostRestoreTest {

    private val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    @Test
    fun `도는 실행을 재기동하면 같은 작업 지시가 새 인스턴스의 실행으로 다시 서고 끝난 단위는 끝난 대로 새 태스크 없이 이어 끝까지 간다`() {
        HostBench().use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", "T2" to "dock-3"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val before = bench.host.instanceId
            bench.driveUnits("exec-1") { it["T1.travel"] == "DONE" && it["T1"] == "RUNNING" }
            val tasksBefore = bench.tasks(HUMANOID)
            assertEquals(setOf("JO-1#T1.travel", "JO-1#T1"), tasksBefore.keys)

            bench.restartHost()
            val after = bench.host.instanceId
            val restore = bench.get("/host/executions")["restore"]
            assertEquals(listOf("at", "rows"), restore.fieldNames().asSequence().toList())
            val row = restore["rows"].single()
            assertEquals(RESTORE_ROW_FIELDS, row.fieldNames().asSequence().toList())
            assertEquals(
                listOf("JO-1", HUMANOID, before, "exec-1", "RESTORED", "exec-1"),
                listOf("jobOrderId", "robotId", "previousInstanceId", "previousExecutionId", "result", "executionId").map { row[it].asText() },
            )
            assertTrue(row["reason"].isNull)
            assertEquals(listOf(listOf("RESTORED", after, "exec-1", null)), events("JO-1"))

            // 가상 시계를 밀지 않고 pump 가 기체를 다시 관측하면 끝난 단위는 끝난 대로, 도는 단위는 도는 대로 선다. 새 태스크는 없다.
            bench.eventually("단위 다시 관측") { units(bench.execution("exec-1")!!) == mapOf("T1.travel" to "DONE", "T1" to "RUNNING", "T2.travel" to "PENDING", "T2" to "PENDING") }
            val execution = bench.execution("exec-1")!!
            assertEquals(JSON.readTree("""{"instanceId":"$before","executionId":"exec-1"}"""), execution["restoredFrom"])
            assertTrue(execution["missionVersion"].isNull)
            assertEquals(tasksBefore, bench.tasks(HUMANOID))

            val done = bench.driveUntil("exec-1", settled)
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            bench.eventually("정착 이벤트") { events("JO-1").size == 2 }
            assertEquals(listOf("SETTLED", after, "exec-1", "PHYSICALLY_DONE"), events("JO-1")[1])
            assertEquals(setOf("JO-1#T1.travel", "JO-1#T1", "JO-1#T2.travel", "JO-1#T2"), bench.tasks(HUMANOID).keys)

            // 정착한 일지 행은 다음 기동이 다시 짓지 않는다.
            bench.restartHost()
            val idle = bench.get("/host/executions")
            assertTrue(idle["restore"]["rows"].isEmpty && idle["executions"].isEmpty, idle.toString())
        }
    }

    @Test
    fun `받은 뒤 다른 임무 버전을 활성화하고 재기동해도 받은 때의 임무 정의로 다시 짓는다`() {
        HostBench().use { bench ->
            bench.mimic.server.advance(Duration.ofSeconds(1))
            bench.awaitPump()
            val accepted = bench.post("/host/job-orders", request(rack("JO-1", S01), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            assertEquals(1, bench.activateHold())

            bench.restartHost()
            assertEquals("RESTORED", bench.get("/host/executions")["restore"]["rows"].single()["result"].asText())
            val execution = bench.execution("exec-1")!!
            assertTrue(execution["missionVersion"].isNull, execution.toString())
            assertEquals(listOf(S01), execution["units"].map { it["unitId"].asText() })
            // 일지에는 받은 때의 임무 버전(코드 정의라 null)이 있다.
            assertNull(journalVersion("JO-1"))
        }
    }

    @Test
    fun `운영자 보류에 선 실행을 두 번 다시 띄워도 새 인스턴스의 첫 보류 응답은 재기동 중복이고 같은 인스턴스의 다음 보류는 송신이다`() {
        HostBench().use { bench ->
            val first = bench.host.instanceId
            val executionId = bench.submitHeldOrder("JO-1")
            bench.driveToHold(executionId)
            bench.eventually("첫 보류 송신") { log("JO-1").isNotEmpty() }
            assertEquals(listOf(first to "SENT"), log("JO-1").map { it[0] to it[1] })

            bench.restartHost()
            val second = bench.host.instanceId
            val restored = bench.execution("exec-1")!!
            assertEquals(1, restored["missionVersion"].asInt())
            assertEquals(listOf(WAIT, S01), restored["units"].map { it["unitId"].asText() })
            bench.driveToHold("exec-1")
            bench.eventually("둘째 인스턴스의 보류") { log("JO-1").size == 2 }
            assertEquals(second to "RESTART_DUPLICATE", log("JO-1")[1].let { it[0] to it[1] })
            assertEquals("OPERATOR_HOLD", log("JO-1")[1][2])

            bench.restartHost()
            val third = bench.host.instanceId
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals(listOf(second, "exec-1"), listOf(row["previousInstanceId"].asText(), row["previousExecutionId"].asText()))
            assertEquals(JSON.readTree("""{"instanceId":"$second","executionId":"exec-1"}"""), bench.execution("exec-1")!!["restoredFrom"])
            bench.driveToHold("exec-1")
            bench.eventually("셋째 인스턴스의 보류") { log("JO-1").size == 3 }
            assertEquals(third to "RESTART_DUPLICATE", log("JO-1")[2].let { it[0] to it[1] })

            // 같은 인스턴스 안에서 재작업 뒤 다시 선 보류는 같은 내용이어도 새 사건이라 송신한다.
            assertEquals("Resolved", bench.resolve("exec-1", WAIT, "REWORK", "kim").body!!["result"].asText())
            bench.driveToHold("exec-1")
            bench.eventually("재작업 뒤 보류") { log("JO-1").size == 4 }
            assertEquals(third to "SENT", log("JO-1")[3].let { it[0] to it[1] })
            assertEquals(log("JO-1")[0].drop(2), log("JO-1")[3].drop(2))

            val view = bench.get("/host/job-responses?jobOrderId=JO-1")["responses"]
            assertEquals(listOf("SENT", "RESTART_DUPLICATE", "RESTART_DUPLICATE", "SENT"), view.map { it["disposition"].asText() }.reversed())
            assertEquals(listOf(first, second, third, third), view.map { it["instanceId"].asText() }.reversed())
        }
    }

    @Test
    fun `기체 스냅숏을 못 읽으면 미루고 그 기체를 판정에서 빼며 pump 마다 다시 시도해 읽히면 다시 짓고 그 밖의 거부는 포기한다`() {
        HostBench(gated = true).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val before = bench.host.instanceId
            bench.driveUnits("exec-1") { it["T1.travel"] == "RUNNING" }
            // InspectAsset 의 최고 근거 등급은 E0 이라 E2 를 요구하는 일지 행은 다시 짓지 못한다.
            insertJournal("JO-E2", QUADRUPED, evidence = "E2")

            bench.blockedSnapshots += HUMANOID
            bench.restartHost()
            val rows = bench.get("/host/executions")["restore"]["rows"]
            assertEquals(listOf("JO-1" to "DEFERRED", "JO-E2" to "GAVE_UP"), rows.map { it["jobOrderId"].asText() to it["result"].asText() })
            assertTrue(rows[0]["reason"].asText().startsWith("기체 스냅숏을 못 읽어"), rows.toString())
            assertTrue(rows[0]["executionId"].isNull)
            assertTrue("요구 근거 등급" in rows[1]["reason"].asText(), rows.toString())
            assertTrue(bench.get("/host/executions")["executions"].isEmpty)

            fun reasons(robotId: String) =
                bench.post("/host/eligibility", request(inspect("JO-9", "T2" to "dock-3"), "robotIds", robotId)).body!!["robots"].single()["reasons"].map { it.asText() }
            assertEquals(listOf("복원 못 한 실행이 있다: JO-1"), reasons(HUMANOID))
            // 포기한 행의 기체에 그 작업 지시의 태스크가 없으면 곧바로 풀린다.
            assertEquals(emptyList(), reasons(QUADRUPED))

            bench.idlePumps()
            assertEquals(listOf("DEFERRED"), events("JO-1").map { it[0] })
            assertEquals("DEFERRED", bench.get("/host/executions")["restore"]["rows"][0]["result"].asText())

            bench.blockedSnapshots -= HUMANOID
            bench.eventually("미룬 행 다시 짓기") { bench.get("/host/executions")["restore"]["rows"][0]["result"].asText() == "RESTORED" }
            val after = bench.host.instanceId
            assertEquals(listOf(listOf("DEFERRED", after, null), listOf("RESTORED", after, "exec-1")), events("JO-1").map { it.take(3) })
            val row = bench.get("/host/executions")["restore"]["rows"][0]
            assertEquals(listOf("exec-1", before, "exec-1"), listOf(row["executionId"].asText(), row["previousInstanceId"].asText(), row["previousExecutionId"].asText()))
            assertTrue(row["reason"].isNull)
            assertEquals(JSON.readTree("""{"instanceId":"$before","executionId":"exec-1"}"""), bench.execution("exec-1")!!["restoredFrom"])
            assertEquals(listOf("도는 실행이 있다: exec-1"), reasons(HUMANOID))
        }
    }

    @Test
    fun `재작업한 로봇 단위를 재기동하면 @rN 태스크에 다시 붙고 새 태스크를 내지 않는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val accepted = bench.post("/host/job-orders", request(rack("JO-1", S01), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val running = bench.runningTask(HUMANOID)
            // 슬롯이 근거 윈도우 안에 채워졌으므로 스킬 실패는 실패가 아니라 운영자 보류다(S4a JSON 계약 §8).
            bench.cellBody = STANDARD_CELL.replace(
                """{"id":"$S01","occupied":false,"material":null,"observedAt":null}""",
                """{"id":"$S01","occupied":true,"material":"$MATERIAL","observedAt":null}""",
            )
            bench.awaitPump()
            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.forceFault("SKILL_EXECUTION_FAILED", running) }
            assertTrue(outcome is ForceOutcome.Raised, outcome.toString())
            bench.driveToHold("exec-1")
            assertEquals("Resolved", bench.resolve("exec-1", S01, "REWORK", "kim").body!!["result"].asText())
            bench.eventually("재작업 태스크") { "JO-1#$S01@r1" in bench.tasks(HUMANOID).keys }
            bench.eventually("재작업 단위 RUNNING") { units(bench.execution("exec-1")!!)[S01] == "RUNNING" }
            val tasksBefore = bench.tasks(HUMANOID)
            assertEquals(setOf("JO-1#$S01", "JO-1#$S01@r1"), tasksBefore.keys)

            bench.restartHost()
            assertEquals("RESTORED", bench.get("/host/executions")["restore"]["rows"].single()["result"].asText())
            bench.eventually("@r1 에 다시 붙음") { units(bench.execution("exec-1")!!)[S01] == "RUNNING" }
            bench.idlePumps()
            // 같은 태스크 둘뿐이다. @r1 은 재기동 전에 받은 그 태스크가 이어 돈다(ACCEPTED 에서 RUNNING 으로 갔을 수 있다).
            val tasksAfter = bench.tasks(HUMANOID)
            assertEquals(tasksBefore.keys, tasksAfter.keys)
            assertEquals(tasksBefore["JO-1#$S01"], tasksAfter["JO-1#$S01"])
            assertTrue(tasksAfter.getValue("JO-1#$S01@r1") in setOf(TaskState.ACCEPTED, TaskState.RUNNING), tasksAfter.toString())
        }
    }

    private fun HostBench.runningTask(robotId: String): String {
        repeat(20) {
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            mimic.server.exclusive { mimic.instance(robotId)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING }?.taskId }
                ?.let { return it }
        }
        error("태스크가 RUNNING 이 되지 않았다")
    }

    /** 실행의 단위가 [done] 을 만족할 때까지 가상 시계를 1초씩 민다(가상 120초 상한). */
    private fun HostBench.driveUnits(executionId: String, done: (Map<String, String>) -> Boolean) {
        repeat(120) {
            if (done(units(checkNotNull(execution(executionId))))) return
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
        }
        error("단위가 바라는 상태에 이르지 않았다: ${execution(executionId)}")
    }

    /** mimic 이 호스팅하는 태스크 id 와 상태. */
    private fun HostBench.tasks(robotId: String): Map<String, TaskState> =
        mimic.server.exclusive { mimic.instance(robotId)!!.tasks.all.associate { it.taskId to it.machine.state } }

    private companion object {
        const val WAIT = "rack-arrival"
        const val S01 = "RACK-204.S01"

        val RESTORE_ROW_FIELDS = listOf("jobOrderId", "robotId", "previousInstanceId", "previousExecutionId", "result", "executionId", "reason")

        fun units(execution: JsonNode): Map<String, String> = execution["units"].associate { it["unitId"].asText() to it["state"].asText() }

        fun events(jobOrderId: String): List<List<String?>> = PostgresSupport.queryAll(
            "SELECT kind, instance_id, execution_id, detail FROM mission.execution_journal_event WHERE job_order_id = '$jobOrderId' ORDER BY event_id",
        ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }

        /** 송신 기록(인스턴스, 처분, 물리 상태, 미완 단위, 운영자 필요, 도달 근거 등급). 적은 순서다. */
        fun log(jobOrderId: String): List<List<String>> = PostgresSupport.queryAll(
            """
            SELECT instance_id, disposition, physical_state, incomplete_units::text, operator_required::text, reached_evidence
            FROM mission.job_response_log WHERE job_order_id = '$jobOrderId' ORDER BY log_id
            """.trimIndent(),
        ) { (1..6).map { i -> it.getString(i) } }

        fun journalVersion(jobOrderId: String): Int? =
            PostgresSupport.queryOne("SELECT mission_version FROM mission.execution_journal WHERE job_order_id = '$jobOrderId'") { it.getObject(1) as Int? }

        fun insertJournal(jobOrderId: String, robotId: String, evidence: String) {
            val order = JSON.writeValueAsString(JSON.readTree(inspect(jobOrderId, "T1" to "bay-7", evidence = evidence)))
            PostgresSupport.execute(
                """
                INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
                VALUES ('$jobOrderId', '$robotId', '$order'::jsonb, 'InspectAsset', NULL, 'mw-before', 'exec-7')
                """.trimIndent(),
            )
        }
    }
}
```

- [ ] **Step 8: (커밋 3/4) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task2c.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task2c.patch"
```

```diff
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
index 6f4cca6..2672a9a 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
@@ -230,9 +230,11 @@ class JournalWriteFailed(val executionId: String, cause: Throwable) : RuntimeExc
  * 호스트는 스킬 적합과 도는 실행 없음만 본다. 시운전 완료와 연결 신선은 운영 서비스가 본다. 미들웨어의 배정 관문은 이
  * 넷을 보지 않으므로 제출 때 호스트가 다시 판정해 통과한 기체만 `assign` 에 넘긴다.
  *
- * ## 작업 응답
+ * ## 작업 응답과 재기동(S4b 스펙)
  *
- * 상위 시스템이 없어 `ack` 하지 않는다(스펙 §7.7). 아웃박스가 계속 자라는 것은 한계다(스펙 §12).
+ * 상위 시스템이 없어 송신 기록(`mission.job_response_log`)이 전선 자리를 대신한다. pump 뒤 같은 잠금 안에서 응답을 적고 `ack`
+ * 한다. 받은 작업 지시는 실행 일지에 적고 기동 때 [start] 가 다시 짓는다. 인시던트와 판단은 사본으로 남는다. 미들웨어 아웃박스는
+ * ack 한 응답도 들고 있어 계속 자라는 것은 그대로 한계다(S3a 스펙 §12).
  *
  * @param robots 하위 포트. 판정의 케이퍼빌리티도 이것으로 묻는다(`PicassoClient` 가 세대별로 캐시한다). 그 캐시는 잠금 밖에서
  *   안전하지 않으므로 케이퍼빌리티는 늘 [lock] 아래에서 묻는다.
@@ -387,9 +389,25 @@ class MissionHost(
         }
     }
 
-    /** 미들웨어에 다시 넣는다. P6 `Middleware.resume` 을 기다리는 자리. */
+    /**
+     * 미들웨어에 다시 넣는다(picasso `Middleware.resume`, S4b 스펙 T1). 기체 스냅숏을 못 읽은 거부는 DEFERRED, 그 밖의 거부는
+     * GAVE_UP 이다. Idempotent 는 이번 기동에서 이미 다시 지은 행을 또 부른 경우(앞선 이벤트 쓰기가 실패한 다시 시도)뿐이고
+     * 그 실행으로 RESTORED 다.
+     *
+     * @return 결과, RESTORED 의 실행 id, DEFERRED·GAVE_UP 의 사유
+     */
     private fun resumed(waiting: Deferred): Triple<RestoreResult, String?, String?> =
-        Triple(RestoreResult.GAVE_UP, null, "resume 없음")
+        when (val submission = middleware.resume(waiting.order, waiting.row.robotId, waiting.mission)) {
+            is Middleware.Submission.Accepted -> Triple(RestoreResult.RESTORED, submission.execution.executionId, null)
+            is Middleware.Submission.Idempotent -> Triple(RestoreResult.RESTORED, submission.execution.executionId, null)
+            is Middleware.Submission.Rejected ->
+                if (submission.reason.startsWith(Middleware.RESUME_SNAPSHOT_UNREADABLE)) {
+                    Triple(RestoreResult.DEFERRED, null, submission.reason)
+                } else {
+                    Triple(RestoreResult.GAVE_UP, null, submission.reason)
+                }
+            is Unassigned -> Triple(RestoreResult.GAVE_UP, null, "배정되지 않았다: ${submission.refusals}")
+        }
 
     private fun restoreView(): RestoreView? = restoredAt?.let { RestoreView(it, restoreRows.toList()) }
 
```

- [ ] **Step 9: 커밋 3/4 `feat(mission-host): 기동 복원과 재기동 중복 시험`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt && git commit -F - <<'EOF'
feat(mission-host): 기동 복원과 재기동 중복 시험

- MissionHost.start: pump 전에 정착·포기 없는 일지 행을 받은 순서로 Middleware.resume, RESTORED·DEFERRED·GAVE_UP 이벤트 기록
- 스냅숏을 못 읽은 거부(RESUME_SNAPSHOT_UNREADABLE)는 DEFERRED, pump 마다 다시 시도, 그 밖의 거부는 GAVE_UP
- 클래스 KDoc 의 작업 응답 절을 송신 기록과 재기동 기준으로 정정
- HostRestoreTest: 도는 실행 다시 짓기와 태스크 집합 유지, 받은 때의 임무 버전, 두 번 재기동의 재기동 중복과 같은 인스턴스 송신, DEFERRED 뒤 RESTORED, @r1 태스크 재부착

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 10: (커밋 4/4) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task2d.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task2d.patch"
```

```diff
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt
index cd55f12..83fc8ea 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt
@@ -54,7 +54,7 @@ class HostJournalTest {
             assertEquals(listOf("JO-1"), journal().map { it["job_order_id"] })
             assertEquals(1, events("JO-1").size)
 
-            // 송신 기록: 응답마다 한 행, 모두 송신이고 ack 했으므로 다시 적지 않는다. 최신 행이 실행의 마지막 작업 응답이다.
+            // 송신 기록: 응답마다 한 행이고 모두 송신이다. 최신 행이 실행의 마지막 작업 응답이다(ack 는 트랜잭션 시험이 본다).
             val log = bench.get("/host/job-responses?jobOrderId=JO-1")
             assertEquals(listOf("instanceId", "total", "responses"), log.fieldNames().asSequence().toList())
             val rows = log["responses"].toList()
```

- [ ] **Step 11: 커밋 4/4 `test(mission-host): 송신 기록 시험 주석의 ack 근거 정정`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt && git commit -F - <<'EOF'
test(mission-host): 송신 기록 시험 주석의 ack 근거 정정

- 첫 일지 시험의 ack 미확인, ack 확인은 pump 뒤 기록 트랜잭션 시험 몫

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 12: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && ./gradlew :mission-host:test -q
```
Expected: mission-host 77, 실패 0(`HostJournalTest` 5, `HostRestoreTest` 5 포함). 백그라운드로 돌린다(Docker, Testcontainers).

- [ ] **Step 13: 묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s4b-cmp.sh" --at 7286e98 mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostRecords.kt mission-host/src/main/resources/db/mission/V2__execution_journal.sql mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt mission-host/src/main/kotlin/dev/picasso/ops/host/mission/StoredMissionCatalog.kt mission-host/src/main/kotlin/dev/picasso/ops/host/store/MissionStore.kt mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt mission-host/src/main/kotlin/dev/picasso/ops/host/IncidentViews.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/IncidentController.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt mission-host/src/test/kotlin/dev/picasso/ops/host/IncidentResolveTest.kt
```
Expected: 15개 모두 `같음`(이 Task 의 마지막 스파이크 커밋 `7286e98` 의 파일과 바이트 대조, 뒤 Task 가 같은 파일을 다시 고칠 수 있어 HEAD 가 아니다).

### Task 3: 운영 서비스: 이전 인스턴스 인시던트 중계, 판단의 인스턴스, 송신 기록 중계

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/web/JobResponseController.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/JobResponseControllerTest.kt`
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/HoldResolutions.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/web/IncidentController.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/HostIncidentsClientTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentBench.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentControllerTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentOperationsTest.kt`

- [ ] **Step 1: (커밋) 새 파일 2개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/web && cp "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/web/JobResponseController.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/web/JobResponseController.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/JobResponseControllerTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/JobResponseControllerTest.kt
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/web/JobResponseController.kt`:

```kotlin
package dev.picasso.ops.service.web

import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostJobResponses
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 작업 응답 송신 기록 읽기(S4b 스펙 T9·§8). 호스트 `GET /host/job-responses` 본문을 그대로 넘긴다. 모드와 관계없다.
 *
 * 쿼리 `jobOrderId` 와 `limit` 은 있을 때만 호스트에 싣는다. 빈 `jobOrderId` 와 1~[MAX_LIMIT] 밖의 `limit` 은 호스트에 닿지
 * 않은 400 `JOB_RESPONSE_BAD_REQUEST` 다. 호스트가 안 닿으면 다른 호스트 읽기와 같이 503 `HOST_SILENT` 다. 새 쓰기 조작이
 * 아니므로 조작 기록에 남지 않는다.
 */
@RestController
class JobResponseController(private val host: HostJobResponses) {

    @GetMapping("/api/job-responses")
    fun jobResponses(
        @RequestParam(required = false) jobOrderId: String?,
        @RequestParam(required = false) limit: String?,
    ): ResponseEntity<Any> {
        if (jobOrderId != null && jobOrderId.isBlank()) {
            return reject(HttpStatus.BAD_REQUEST, JOB_RESPONSE_BAD_REQUEST, "jobOrderId 는 비어 있지 않은 문자열이다")
        }
        val parsed = limit?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
                ?: return reject(HttpStatus.BAD_REQUEST, JOB_RESPONSE_BAD_REQUEST, "limit 은 1~$MAX_LIMIT 의 정수다: $raw")
        }
        return when (val call = host.jobResponses(jobOrderId, parsed)) {
            is HostCall.Ok -> ResponseEntity.ok(call.value)
            is HostCall.Silent ->
                reject(HttpStatus.SERVICE_UNAVAILABLE, JobOrderController.HOST_SILENT, "실행 호스트가 답하지 않는다: ${call.cause}")
        }
    }

    companion object {
        const val JOB_RESPONSE_BAD_REQUEST = "JOB_RESPONSE_BAD_REQUEST"

        /** 호스트 송신 기록이 받는 최대(S4b 계약 H6). */
        const val MAX_LIMIT = 500
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/JobResponseControllerTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostJobResponses
import dev.picasso.ops.service.web.JobResponseController
import dev.picasso.ops.service.web.PreRejection
import org.springframework.http.ResponseEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * 작업 응답 송신 기록 중계(S4b 스펙 T9). 컨트롤러를 스프링 없이 바로 부른다. 호스트는 대역이고 받은 쿼리 인자를 적는다.
 */
class JobResponseControllerTest {

    private val json = ObjectMapper()
    private val asked = mutableListOf<Pair<String?, Int?>>()
    private var answer: HostCall<JsonNode> = HostCall.Ok(json.readTree(BODY))
    private val controller = JobResponseController(
        HostJobResponses { jobOrderId, limit ->
            asked += jobOrderId to limit
            answer
        },
    )

    private fun ResponseEntity<Any>.rejection(): PreRejection = assertIs<PreRejection>(body)

    @Test
    fun `송신 기록은 호스트 본문 그대로이고 작업 지시 id 와 limit 을 있을 때만 넘긴다`() {
        val all = controller.jobResponses(null, null)
        assertEquals(200, all.statusCode.value())
        assertSame((answer as HostCall.Ok).value, all.body)
        assertEquals(200, controller.jobResponses("JO-1", null).statusCode.value())
        assertEquals(200, controller.jobResponses("JO-1", "500").statusCode.value())
        assertEquals(200, controller.jobResponses(null, "1").statusCode.value())
        assertEquals(listOf(null to null, "JO-1" to null, "JO-1" to 500, null to 1), asked)
    }

    @Test
    fun `빈 작업 지시 id 나 범위 밖 limit 은 호스트를 부르지 않는 400 JOB_RESPONSE_BAD_REQUEST 다`() {
        listOf("" to null, " " to null, null to "0", null to "501", null to "x", null to "").forEach { (jobOrderId, limit) ->
            val reply = controller.jobResponses(jobOrderId, limit)
            assertEquals(400, reply.statusCode.value(), "$jobOrderId $limit")
            assertEquals(JobResponseController.JOB_RESPONSE_BAD_REQUEST, reply.rejection().error)
        }
        assertEquals("jobOrderId 는 비어 있지 않은 문자열이다", controller.jobResponses("", null).rejection().detail)
        assertEquals("limit 은 1~500 의 정수다: 501", controller.jobResponses(null, "501").rejection().detail)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `호스트가 안 닿으면 503 HOST_SILENT 다`() {
        answer = HostCall.Silent("응답 없음: ConnectException")
        val reply = controller.jobResponses("JO-1", null)
        assertEquals(503, reply.statusCode.value())
        assertEquals("HOST_SILENT", reply.rejection().error)
        assertEquals("실행 호스트가 답하지 않는다: 응답 없음: ConnectException", reply.rejection().detail)
    }

    companion object {
        /** 송신 기록 본문(S4b 계약 H6). 재기동 중복 한 줄과 송신 한 줄이다. */
        const val BODY = """{"instanceId":"i-2","total":2,"responses":[
            {"instanceId":"i-2","jobResponseId":"resp-1","jobOrderId":"JO-1","executionId":"exec-1","version":1,
             "physicalState":"PARTIAL","requiredEvidence":"E2","reachedEvidence":"E0","completedUnits":[],"unverifiedUnits":[],
             "inDoubtUnits":[],"incompleteUnits":["rack-arrival"],"operatorRequired":true,"residualHold":"HOLD_KIND_UNSPECIFIED",
             "blockedBy":[],"disposition":"RESTART_DUPLICATE","recordedAt":"2026-10-09T09:00:02Z"},
            {"instanceId":"i-1","jobResponseId":"resp-1","jobOrderId":"JO-1","executionId":"exec-1","version":1,
             "physicalState":"PARTIAL","requiredEvidence":"E2","reachedEvidence":"E0","completedUnits":[],"unverifiedUnits":[],
             "inDoubtUnits":[],"incompleteUnits":["rack-arrival"],"operatorRequired":true,"residualHold":"HOLD_KIND_UNSPECIFIED",
             "blockedBy":[],"disposition":"SENT","recordedAt":"2026-10-09T09:00:01Z"}]}"""
    }
}
```

- [ ] **Step 2: (커밋) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task3a.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task3a.patch"
```

```diff
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
index 9fab83f..2c2d41d 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
@@ -194,11 +194,26 @@ interface HostIncidents {
     /** `GET /host/incidents[?limit=]` 본문 그대로. [limit] 이 널이면 쿼리를 싣지 않는다(호스트 기본 50). */
     fun incidents(limit: Int? = null): HostCall<JsonNode>
 
-    /** `GET /host/incidents/{incidentId}`. */
-    fun incident(incidentId: String): HostIncident
+    /**
+     * `GET /host/incidents/{incidentId}[?instanceId=]`. [instanceId] 가 널이면 쿼리를 싣지 않는다(지금 인스턴스). 이전 인스턴스면
+     * 호스트가 그 인스턴스의 사본을 준다(S4b 계약 H4).
+     */
+    fun incident(incidentId: String, instanceId: String? = null): HostIncident
+
+    /**
+     * `POST /host/executions/{executionId}/units/{unitId}/resolve`(S4a JSON 계약 §5.1). [instanceId] 는 상세에서 받은 인스턴스이고
+     * 지금 인스턴스가 아니면 호스트가 409 `INSTANCE_MISMATCH` 로 막는다(S4b 계약 H5).
+     */
+    fun resolve(executionId: String, unitId: String, decision: String, approverId: String, instanceId: String, requestId: UUID): HostWrite
+}
 
-    /** `POST /host/executions/{executionId}/units/{unitId}/resolve`(S4a JSON 계약 §5.1). */
-    fun resolve(executionId: String, unitId: String, decision: String, approverId: String, requestId: UUID): HostWrite
+/** 작업 응답 송신 기록 읽기(S4b 스펙 T6·T9). 시험이 호스트 없이 대신 끼운다. */
+fun interface HostJobResponses {
+    /**
+     * `GET /host/job-responses[?jobOrderId=&limit=]` 본문 그대로. 널인 인자는 쿼리에 싣지 않는다(호스트 기본은 전체, 50건).
+     * 200 아님과 닿지 않음은 모름이다.
+     */
+    fun jobResponses(jobOrderId: String?, limit: Int?): HostCall<JsonNode>
 }
 
 /** 장애 주입 전달(S4a 스펙 §7, T1). 시험이 호스트 없이 대신 끼운다. */
@@ -208,7 +223,7 @@ fun interface HostFaults {
 }
 
 /**
- * 실행 호스트 REST 클라이언트(S3a 스펙 §8, S3b 스펙 §7, S3c 스펙 §8, S4a 스펙 §7). 호스트는 루프백·무인증이라 토큰을 싣지 않는다.
+ * 실행 호스트 REST 클라이언트(S3a 스펙 §8, S3b 스펙 §7, S3c 스펙 §8, S4a 스펙 §7, S4b 스펙 §7). 호스트는 루프백·무인증이라 토큰을 싣지 않는다.
  *
  * 연결 제한은 registry 와 같고 요청 제한은 더 길다. 호스트는 판정과 제출을 자기 잠금 아래에서 하며, 그 안에서 mimic 에
  * gRPC 를 부르고, mimic 은 엔진 잠금 아래에서 registry 로 태스크 관측을 동기 HTTP 로 적재한다(요청 제한 3초, 스펙 §5.3).
@@ -224,7 +239,8 @@ class HostClient(
     private val json: ObjectMapper = jacksonObjectMapper(),
     private val requestTimeout: Duration = REQUEST_TIMEOUT,
     private val mockRunTimeout: Duration = MOCK_RUN_TIMEOUT,
-) : HostReads, HostWrites, HostMissions, HostSignals, HostSiteTimings, HostIncidents, HostFaults, AutoCloseable {
+) : HostReads, HostWrites, HostMissions, HostSignals, HostSiteTimings, HostIncidents, HostFaults, HostJobResponses,
+    AutoCloseable {
 
     private val base = checkBaseUrl(baseUrl)
 
@@ -314,8 +330,9 @@ class HostClient(
      * 없다는 응답은 404 와 `INCIDENT_NOT_FOUND` 가 함께일 때만이다. 다른 404(그 경로가 없는 서버 등)는 호스트의 판단이
      * 아니므로 못 읽음이다.
      */
-    override fun incident(incidentId: String): HostIncident {
-        val request = HttpRequest.newBuilder(URI.create("$base/host/incidents/${segment(incidentId)}")).GET()
+    override fun incident(incidentId: String, instanceId: String?): HostIncident {
+        val query = instanceId?.let { "?instanceId=${segment(it)}" } ?: ""
+        val request = HttpRequest.newBuilder(URI.create("$base/host/incidents/${segment(incidentId)}$query")).GET()
         val response = when (val write = send(request, requestTimeout)) {
             is HostWrite.NoResponse -> return HostIncident.Silent(write.cause)
             is HostWrite.Answered -> write
@@ -329,14 +346,27 @@ class HostClient(
         }
     }
 
-    override fun resolve(executionId: String, unitId: String, decision: String, approverId: String, requestId: UUID): HostWrite {
+    override fun resolve(
+        executionId: String,
+        unitId: String,
+        decision: String,
+        approverId: String,
+        instanceId: String,
+        requestId: UUID,
+    ): HostWrite {
         val body = json.createObjectNode()
             .put("decision", decision)
             .put("approverId", approverId)
             .put("requestId", requestId.toString())
+            .put("instanceId", instanceId)
         return post("/host/executions/${segment(executionId)}/units/${segment(unitId)}/resolve", body)
     }
 
+    override fun jobResponses(jobOrderId: String?, limit: Int?): HostCall<JsonNode> {
+        val query = listOfNotNull(jobOrderId?.let { "jobOrderId=${segment(it)}" }, limit?.let { "limit=$it" })
+        return get("/host/job-responses" + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?"))
+    }
+
     /** 객체 본문만 받는다. 호스트의 GET 은 늘 객체를 준다(S3a JSON 계약 §5·§6, S3b JSON 계약 §4). */
     private fun get(path: String): HostCall<JsonNode> {
         val response = when (val write = send(HttpRequest.newBuilder(URI.create(base + path)).GET(), requestTimeout)) {
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/HoldResolutions.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/HoldResolutions.kt
index eda20dd..182038a 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/HoldResolutions.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/HoldResolutions.kt
@@ -51,6 +51,11 @@ data class HoldResolveOutcome(
  * 실제 시각(`wallClockAt`)이 그 시각 이후이면 반영됨이다. 같은 사람이 같은 단위를 앞서 판단한 기록은 시각 조건이 거른다.
  * «가장 최근 인시던트» 와 «보류가 아님» 은 보지 않는다. 재작업 뒤 대기가 기한을 다시 넘기면 새 보류가 서서 반영된 재작업을
  * 반영 안 됨으로 읽기 때문이다. 판단 시각 `at` 은 호스트 시계(통합 시험에서는 가상 시각)라 쓰지 않는다.
+ *
+ * 판단은 인스턴스를 싣는다(S4b 스펙 T8·T9). 화면은 인시던트 상세의 `instanceId` 를 보내고, 호스트는 지금 인스턴스가 아니면
+ * 409 `INSTANCE_MISMATCH` 로 막는다. 그 거부는 다른 호스트 4xx 와 같이 거부로 남고 원래 이름이 응답 칸에 있다. 재조회는 목록의
+ * `instanceId` 가 요청한 인스턴스와 같을 때만 `incidents` 를 본다. 다르면 호스트가 그 사이 재기동한 것이고 `exec-N` 을 다시
+ * 세므로 새 인스턴스의 같은 실행 id 를 잘못 맞출 수 있다. 그때는 `earlier` 에서 요청한 인스턴스의 사본만 본다.
  */
 class HoldResolutions(
     private val host: HostIncidents,
@@ -61,22 +66,30 @@ class HoldResolutions(
 ) {
     private val runner = HostOperationRunner(log, requeryDelay, json)
 
-    /** [decision] 은 컨트롤러가 둘 중 하나임을, [reason] 은 비어 있지 않음을 본 값이다. */
-    fun resolve(actor: Actor, executionId: String, unitId: String, decision: String, reason: String): HoldResolveOutcome {
+    /** [decision] 은 컨트롤러가 둘 중 하나임을, [instanceId]·[reason] 은 비어 있지 않음을 본 값이다. */
+    fun resolve(
+        actor: Actor,
+        executionId: String,
+        unitId: String,
+        decision: String,
+        instanceId: String,
+        reason: String,
+    ): HoldResolveOutcome {
         val request = json.createObjectNode()
             .put("op", OP)
             .put("executionId", executionId)
             .put("unitId", unitId)
             .put("decision", decision)
             .put("approverId", actor.user)
+            .put("instanceId", instanceId)
         var sentAt: Instant? = null
         val ran = runner.run(
             actor, "$executionId/$unitId", request, reason,
             accepted = { body -> resultOf(body.path("result").takeIf { it.isTextual }?.asText()) },
-            recheck = { sentAt?.let { at -> recheck(executionId, unitId, decision, actor.user, at) } },
+            recheck = { sentAt?.let { at -> recheck(executionId, unitId, decision, instanceId, actor.user, at) } },
         ) { requestId ->
             sentAt = clock.instant()
-            host.resolve(executionId, unitId, decision, actor.user, requestId)
+            host.resolve(executionId, unitId, decision, actor.user, instanceId, requestId)
         }
         val outcome = ran.body?.path("result")?.takeIf { it.isTextual }?.asText()
         return HoldResolveOutcome(
@@ -86,11 +99,23 @@ class HoldResolutions(
 
     /**
      * 목록을 못 읽으면 확인하지 못한 것이라 널이다. 반영 안 됨이면 관측으로 그 단위의 가장 최근 인시던트를 남긴다(없으면 널).
+     * 목록의 인스턴스가 [instanceId] 면 `incidents` 를, 아니면 `earlier` 의 그 인스턴스 사본을 본다.
      */
-    private fun recheck(executionId: String, unitId: String, decision: String, approver: String, sentAt: Instant): HostRecheck? {
+    private fun recheck(
+        executionId: String,
+        unitId: String,
+        decision: String,
+        instanceId: String,
+        approver: String,
+        sentAt: Instant,
+    ): HostRecheck? {
         val body = (host.incidents(REQUERY_LIMIT) as? HostCall.Ok)?.value ?: return null
-        val listed = body.path("incidents").takeIf { it.isArray } ?: return null
-        val unit = listed.filter { it.path("executionId").asText() == executionId && it.path("unitId").asText() == unitId }
+        val current = body.path("instanceId").takeIf { it.isTextual }?.asText() == instanceId
+        val listed = body.path(if (current) "incidents" else "earlier").takeIf { it.isArray } ?: return null
+        val unit = listed.filter {
+            (current || it.path("instanceId").asText() == instanceId) &&
+                it.path("executionId").asText() == executionId && it.path("unitId").asText() == unitId
+        }
         val match = unit.firstOrNull { incident ->
             val resolution = incident.path("resolution").takeIf { it.isObject } ?: return@firstOrNull false
             resolution.path("decidedBy").path("id").asText() == approver &&
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/web/IncidentController.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/web/IncidentController.kt
index ce240b0..8d78749 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/web/IncidentController.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/web/IncidentController.kt
@@ -32,6 +32,9 @@ import org.springframework.web.bind.annotation.RestController
  * `application/json` 만 받는다(다른 출처 방어는 [RobotOperationsController]).
  *
  * 두 조작은 호스트가 무엇을 답했든 200 과 조작 결과다. 현장·호스트의 거부와 판단 결과 이름은 본문에 있다.
+ *
+ * 재기동 뒤(S4b 스펙 T9): 목록은 이전 인스턴스의 사본 `earlier`·`earlierTotal` 을 그대로 넘긴다. 상세는 `instanceId` 쿼리를
+ * 그대로 넘기고(빈 값은 400 `INCIDENT_BAD_REQUEST`), 판단 본문은 `instanceId` 가 필수다(없거나 비면 400 `RESOLVE_BAD_REQUEST`).
  */
 @RestController
 class IncidentController(
@@ -54,10 +57,15 @@ class IncidentController(
     }
 
     @GetMapping("/api/incidents/{incidentId}")
-    fun incident(@PathVariable incidentId: String): ResponseEntity<Any> = when (val found = host.incident(incidentId)) {
-        is HostIncident.Found -> ResponseEntity.ok(found.body)
-        is HostIncident.NotFound -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(found.body)
-        is HostIncident.Silent -> hostSilent(found.cause)
+    fun incident(@PathVariable incidentId: String, @RequestParam(required = false) instanceId: String?): ResponseEntity<Any> {
+        if (instanceId != null && instanceId.isBlank()) {
+            return reject(HttpStatus.BAD_REQUEST, INCIDENT_BAD_REQUEST, "instanceId 는 비어 있지 않은 문자열이다")
+        }
+        return when (val found = host.incident(incidentId, instanceId)) {
+            is HostIncident.Found -> ResponseEntity.ok(found.body)
+            is HostIncident.NotFound -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(found.body)
+            is HostIncident.Silent -> hostSilent(found.cause)
+        }
     }
 
     @PostMapping("/api/faults", consumes = [MediaType.APPLICATION_JSON_VALUE])
@@ -94,8 +102,13 @@ class IncidentController(
                 HttpStatus.BAD_REQUEST, RESOLVE_BAD_REQUEST,
                 "decision 은 ${HoldResolutions.DECISIONS.joinToString(" 또는 ")} 이다",
             )
+        val instanceId = text(node, "instanceId")
+            ?: return@guarded reject(
+                HttpStatus.BAD_REQUEST, RESOLVE_BAD_REQUEST,
+                "instanceId 는 비어 있지 않은 문자열이다(인시던트 상세의 instanceId)",
+            )
         val reason = reason(node) ?: return@guarded reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "판단 사유가 없다")
-        ResponseEntity.ok(resolutions.resolve(actor, executionId, unitId, decision, reason))
+        ResponseEntity.ok(resolutions.resolve(actor, executionId, unitId, decision, instanceId, reason))
     }
 
     private fun read(body: ByteArray?): JsonNode? = body?.let { runCatching { json.readTree(it) }.getOrNull() }?.takeIf { it.isObject }
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/HostIncidentsClientTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/HostIncidentsClientTest.kt
index 007999a..1447b76 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/HostIncidentsClientTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/HostIncidentsClientTest.kt
@@ -94,6 +94,7 @@ class HostIncidentsClientTest {
         assertEquals("/host/incidents/incident-1", seen.single().rawPath)
         found.incident("a/b")
         assertEquals("/host/incidents/a%2Fb", seen.last().rawPath)
+        assertEquals(listOf(null, null), seen.map { it.rawQuery })
         stop()
         val missing = """{"error":"INCIDENT_NOT_FOUND","detail":"없다"}"""
         assertEquals(
@@ -117,24 +118,55 @@ class HostIncidentsClientTest {
     fun `판단은 실행과 단위를 경로 조각으로 인코딩해 결정과 승인자와 요청 id 를 보내고 응답을 그대로 돌려준다`() {
         val answer = """{"result":"NotHeld","detail":null,"incidentId":null,"requestId":"$requestId"}"""
         val client = serve("/host/executions/" to (200 to answer))
-        assertEquals(HostWrite.Answered(200, answer), client.resolve("exec-1", "RACK-204.S01/x", "CONFIRM_DONE", "kim", requestId))
+        assertEquals(
+            HostWrite.Answered(200, answer),
+            client.resolve("exec-1", "RACK-204.S01/x", "CONFIRM_DONE", "kim", "mw-9b1e", requestId),
+        )
         val request = seen.single()
         assertEquals("POST", request.method)
         assertEquals("/host/executions/exec-1/units/RACK-204.S01%2Fx/resolve", request.rawPath)
         assertEquals("application/json", request.contentType)
+        // 칸 순서도 계약 그대로다(S4b 계약 H5).
         assertEquals(
-            json.readTree("""{"decision":"CONFIRM_DONE","approverId":"kim","requestId":"$requestId"}"""),
-            json.readTree(request.body),
+            """{"decision":"CONFIRM_DONE","approverId":"kim","requestId":"$requestId","instanceId":"mw-9b1e"}""",
+            request.body,
         )
     }
 
+    @Test
+    fun `인시던트 상세는 instanceId 를 쿼리로 인코딩해 싣고 이전 인스턴스 사본을 찾음으로 돌려준다`() {
+        val client = serve("/host/incidents/" to (200 to """{"instanceId":"mw 3f/0c","incidentId":"incident-1","held":false}"""))
+        val found = assertIs<HostIncident.Found>(client.incident("incident-1", "mw 3f/0c"))
+        assertEquals("mw 3f/0c", found.body["instanceId"].asText())
+        assertEquals("/host/incidents/incident-1", seen.single().rawPath)
+        assertEquals("instanceId=mw%203f%2F0c", seen.single().rawQuery)
+    }
+
+    @Test
+    fun `송신 기록은 작업 지시 id 와 limit 을 있을 때만 쿼리로 싣고 객체 본문 그대로이며 200 아님은 모름이다`() {
+        val body = """{"instanceId":"i-2","total":0,"responses":[]}"""
+        val client = serve("/host/job-responses" to (200 to body))
+        assertEquals(json.readTree(body), (client.jobResponses(null, null) as HostCall.Ok).value)
+        client.jobResponses("JO 1&x", null)
+        client.jobResponses(null, 500)
+        client.jobResponses("JO-1", 20)
+        assertEquals(listOf("/host/job-responses"), seen.map { it.rawPath }.distinct())
+        assertEquals(listOf(null, "jobOrderId=JO%201%26x", "limit=500", "jobOrderId=JO-1&limit=20"), seen.map { it.rawQuery })
+        stop()
+        val refusing = serve("/host/job-responses" to (400 to """{"error":"BAD_REQUEST","detail":"x"}"""))
+        assertEquals(HostCall.Silent("HTTP 400"), refusing.jobResponses(null, 9999))
+        stop()
+        assertEquals(HostCall.Silent("본문 모양이 다르다"), serve("/host/job-responses" to (200 to "[]")).jobResponses(null, null))
+    }
+
     @Test
     fun `닿지 않는 호스트는 읽기가 모름이고 쓰기가 응답 없음이다`() {
         val client = serve()
         stop()
         assertIs<HostCall.Silent>(client.incidents())
         assertIs<HostIncident.Silent>(client.incident("incident-1"))
-        assertIs<HostWrite.NoResponse>(client.resolve("exec-1", "rack-arrival", "REWORK", "kim", requestId))
+        assertIs<HostWrite.NoResponse>(client.resolve("exec-1", "rack-arrival", "REWORK", "kim", "i-1", requestId))
+        assertIs<HostCall.Silent>(client.jobResponses(null, null))
         assertIs<HostWrite.NoResponse>(client.injectFault(json.createObjectNode()))
     }
 }
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentBench.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentBench.kt
index 3b21498..1a3cdf6 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentBench.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentBench.kt
@@ -37,6 +37,7 @@ class IncidentBench {
         val requestId: UUID? = null,
         val limit: Int? = null,
         val incidentId: String? = null,
+        val instanceId: String? = null,
     )
 
     val calls = mutableListOf<Call>()
@@ -47,13 +48,23 @@ class IncidentBench {
             return this@IncidentBench.incidents
         }
 
-        override fun incident(incidentId: String): HostIncident {
-            calls += Call("incident", incidentId = incidentId)
+        override fun incident(incidentId: String, instanceId: String?): HostIncident {
+            calls += Call("incident", incidentId = incidentId, instanceId = instanceId)
             return this@IncidentBench.incident
         }
 
-        override fun resolve(executionId: String, unitId: String, decision: String, approverId: String, requestId: UUID): HostWrite {
-            calls += Call("resolve", executionId = executionId, unitId = unitId, decision = decision, approverId = approverId, requestId = requestId)
+        override fun resolve(
+            executionId: String,
+            unitId: String,
+            decision: String,
+            approverId: String,
+            instanceId: String,
+            requestId: UUID,
+        ): HostWrite {
+            calls += Call(
+                "resolve", executionId = executionId, unitId = unitId, decision = decision, approverId = approverId,
+                requestId = requestId, instanceId = instanceId,
+            )
             onResolve()
             return resolveAnswer
         }
@@ -69,9 +80,19 @@ class IncidentBench {
     /** 호스트가 받은 쓰기 호출(읽기 둘을 뺀 것). */
     fun writes(): List<Call> = calls.filter { it.op in setOf("resolve", "injectFault") }
 
-    /** 인시던트 목록 본문. 줄은 [row] 로 만든다. */
-    fun listed(vararg rows: String): HostCall<JsonNode> =
-        HostCall.Ok(json.readTree("""{"instanceId":"i-1","total":${rows.size},"incidents":[${rows.joinToString(",")}]}"""))
+    /** 인시던트 목록 본문. 줄은 [row] 로 만든다. 이전 인스턴스 사본은 없다. */
+    fun listed(vararg rows: String): HostCall<JsonNode> = listedAt("i-1", rows.toList())
+
+    /**
+     * 인스턴스 [instanceId] 의 인시던트 목록 본문(S4b 계약 H3). [earlier] 는 이전 인스턴스 사본이고 줄은 [copy] 로 만든다.
+     */
+    fun listedAt(instanceId: String, rows: List<String>, earlier: List<String> = emptyList()): HostCall<JsonNode> =
+        HostCall.Ok(
+            json.readTree(
+                """{"instanceId":"$instanceId","total":${rows.size},"incidents":[${rows.joinToString(",")}],""" +
+                    """"earlierTotal":${earlier.size},"earlier":[${earlier.joinToString(",")}]}""",
+            ),
+        )
 
     companion object {
         const val SKILL_RAISED =
@@ -96,5 +117,10 @@ class IncidentBench {
                "at":"1970-01-01T00:00:25Z","failureClass":"SIGNAL_DEADLINE","route":"SIGNAL","missionVersion":2,"siteSettingsVersion":1,
                "evidenceBeforeSeconds":30,"evidenceAfterSeconds":10,"inDoubtGraceSeconds":null,"stallWindowSeconds":null,
                "unresolved":true,"resolution":${resolution ?: "null"},"fault":null,"held":$held,"confirmedWithoutEvidence":false}"""
+
+        /** 이전 인스턴스 사본 한 줄(S4b 계약 H3, 20칸). `instanceId` 를 앞에 두고 보류가 아니다. */
+        fun copy(instanceId: String, incidentId: String, resolution: String? = null, executionId: String = "exec-1"): String =
+            """{"instanceId":"$instanceId",""" +
+                row(incidentId, executionId = executionId, resolution = resolution, held = false).trimStart().removePrefix("{")
     }
 }
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentControllerTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentControllerTest.kt
index 5812cb6..9f15f5e 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentControllerTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentControllerTest.kt
@@ -92,9 +92,9 @@ class IncidentControllerTest {
             """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","reason":3}""",
         )
         val decisions = listOf(
-            """{"decision":"REWORK"}""",
-            """{"decision":"REWORK","reason":""}""",
-            """{"decision":"REWORK","reason":null}""",
+            """{"decision":"REWORK","instanceId":"i-1"}""",
+            """{"decision":"REWORK","instanceId":"i-1","reason":""}""",
+            """{"decision":"REWORK","instanceId":"i-1","reason":null}""",
         )
         (faults.map { inject("engineer", body = it) } + decisions.map { resolve("operator", body = it) }).forEach { reply ->
             assertEquals(400, reply.statusCode.value())
@@ -121,6 +121,27 @@ class IncidentControllerTest {
         assertEquals(emptyList(), bench.calls)
     }
 
+    @Test
+    fun `판단 본문의 instanceId 가 없거나 비었거나 문자열이 아니면 사유보다 먼저 400 RESOLVE_BAD_REQUEST 다`() {
+        listOf(
+            """{"decision":"REWORK","reason":"r"}""",
+            """{"decision":"REWORK","instanceId":" ","reason":"r"}""",
+            """{"decision":"REWORK","instanceId":null,"reason":"r"}""",
+            """{"decision":"REWORK","instanceId":3,"reason":"r"}""",
+            """{"decision":"REWORK"}""",
+        ).forEach { body ->
+            val reply = resolve("operator", body = body)
+            assertEquals(400, reply.statusCode.value(), body)
+            assertEquals(IncidentController.RESOLVE_BAD_REQUEST, reply.rejection().error, body)
+            assertEquals("instanceId 는 비어 있지 않은 문자열이다(인시던트 상세의 instanceId)", reply.rejection().detail)
+        }
+        assertEquals(emptyList(), bench.calls)
+        assertEquals(emptyList(), log.list())
+
+        assertEquals(200, resolve("operator", body = """{"decision":"REWORK","instanceId":"mw-9b1e","reason":"r"}""").statusCode.value())
+        assertEquals("mw-9b1e", bench.writes().single().instanceId)
+    }
+
     @Test
     fun `맞는 모드와 사유면 호스트에 닿고 결과는 200 본문이다`() {
         val injected = inject("engineer", body = """{"robotId":"quadruped-01","kind":"CONNECTION","state":"OFFLINE","reason":" 묵은 값 "}""")
@@ -142,7 +163,10 @@ class IncidentControllerTest {
 
     @Test
     fun `판단 본문에 승인자 칸을 실어도 승인자는 행위자 헤더다`() {
-        val reply = resolve("operator", user = "kim", body = """{"decision":"CONFIRM_DONE","reason":"설비 확인","approverId":"mallory"}""")
+        val reply = resolve(
+            "operator", user = "kim",
+            body = """{"decision":"CONFIRM_DONE","instanceId":"i-1","reason":"설비 확인","approverId":"mallory"}""",
+        )
         assertEquals(200, reply.statusCode.value())
         assertEquals("kim", bench.writes().last().approverId)
         val row = log.list().single()
@@ -152,7 +176,9 @@ class IncidentControllerTest {
 
     @Test
     fun `인시던트 읽기는 호스트 본문 그대로이고 호스트가 안 닿으면 503 HOST_SILENT 이며 상세의 404 는 그대로 넘긴다`() {
-        val listed = bench.listed(IncidentBench.row("incident-1"))
+        val listed = bench.listedAt(
+            "i-2", listOf(IncidentBench.row("incident-1")), listOf(IncidentBench.copy("i-1", "incident-1")),
+        )
         bench.incidents = listed
         val list = controller.incidents(null)
         assertEquals(200, list.statusCode.value())
@@ -174,23 +200,41 @@ class IncidentControllerTest {
 
         val detail = json.readTree("""{"instanceId":"i-1","incidentId":"incident-1"}""")
         bench.incident = HostIncident.Found(detail)
-        assertSame(detail, controller.incident("incident-1").body)
-        assertEquals("incident-1", bench.calls.last().incidentId)
+        assertSame(detail, controller.incident("incident-1", null).body)
+        assertEquals(IncidentBench.Call("incident", incidentId = "incident-1"), bench.calls.last())
 
         val missing = json.readTree("""{"error":"INCIDENT_NOT_FOUND","detail":"없는 인시던트다: incident-9"}""")
         bench.incident = HostIncident.NotFound(missing)
-        val notFound = controller.incident("incident-9")
+        val notFound = controller.incident("incident-9", null)
         assertEquals(404, notFound.statusCode.value())
         assertSame(missing, notFound.body)
 
         bench.incident = HostIncident.Silent("HTTP 500")
-        val down = controller.incident("incident-1")
+        val down = controller.incident("incident-1", null)
         assertEquals(503, down.statusCode.value())
         assertEquals("HOST_SILENT", down.rejection().error)
     }
 
+    @Test
+    fun `상세의 instanceId 쿼리는 그대로 호스트에 넘기고 빈 값이면 호스트를 부르지 않는 400 이다`() {
+        val copy = json.readTree("""{"instanceId":"i-1","incidentId":"incident-1","held":false}""")
+        bench.incident = HostIncident.Found(copy)
+        val reply = controller.incident("incident-1", "i-1")
+        assertEquals(200, reply.statusCode.value())
+        assertSame(copy, reply.body)
+        assertEquals(IncidentBench.Call("incident", incidentId = "incident-1", instanceId = "i-1"), bench.calls.single())
+
+        listOf("", "  ").forEach { raw ->
+            val blank = controller.incident("incident-1", raw)
+            assertEquals(400, blank.statusCode.value())
+            assertEquals(IncidentController.INCIDENT_BAD_REQUEST, blank.rejection().error)
+            assertEquals("instanceId 는 비어 있지 않은 문자열이다", blank.rejection().detail)
+        }
+        assertEquals(1, bench.calls.size)
+    }
+
     companion object {
         const val FAULT = """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","reason":"스킬 실패 시연"}"""
-        const val RESOLVE = """{"decision":"REWORK","reason":"보류 해소"}"""
+        const val RESOLVE = """{"decision":"REWORK","instanceId":"i-1","reason":"보류 해소"}"""
     }
 }
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentOperationsTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentOperationsTest.kt
index 8c06fef..4e3d4b1 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentOperationsTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentOperationsTest.kt
@@ -127,19 +127,27 @@ class IncidentOperationsTest {
 
     @Test
     fun `판단 Resolved 는 성공이고 승인자와 결정과 실행과 단위와 사유와 결과 이름을 기록한다`() {
-        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "랙 재배치 뒤 재작업")
+        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "랙 재배치 뒤 재작업")
         assertEquals(OperationResult.SUCCEEDED, outcome.result)
         assertEquals("Resolved", outcome.outcome)
         assertNull(outcome.confirmation)
         assertNull(outcome.rejection)
         val call = bench.writes().single()
-        assertEquals(IncidentBench.Call("resolve", executionId = "exec-1", unitId = "rack-arrival", decision = "REWORK", approverId = "kim", requestId = outcome.requestId), call)
+        assertEquals(
+            IncidentBench.Call(
+                "resolve", executionId = "exec-1", unitId = "rack-arrival", decision = "REWORK", approverId = "kim",
+                requestId = outcome.requestId, instanceId = "i-1",
+            ),
+            call,
+        )
         val row = log.list().single()
         assertEquals("exec-1/rack-arrival", row.target)
         assertEquals(Mode.OPERATOR, row.mode)
         assertEquals("랙 재배치 뒤 재작업", row.reason)
         assertEquals(
-            json.readTree("""{"op":"${HoldResolutions.OP}","executionId":"exec-1","unitId":"rack-arrival","decision":"REWORK","approverId":"kim"}"""),
+            json.readTree(
+                """{"op":"${HoldResolutions.OP}","executionId":"exec-1","unitId":"rack-arrival","decision":"REWORK","approverId":"kim","instanceId":"i-1"}""",
+            ),
             json.readTree(row.request),
         )
         assertEquals("Resolved", json.readTree(row.targetResponse)["body"]["result"].asText())
@@ -150,7 +158,7 @@ class IncidentOperationsTest {
     fun `판단 NotHeld 와 Refused 는 거부로 남기고 응답 칸에 원래 결과 이름이 있다`() {
         listOf("NotHeld" to null, "Refused" to "에이전트는 운영자 판단을 내지 못한다").forEach { (name, detail) ->
             bench.resolveAnswer = HostWrite.Answered(200, IncidentBench.resolved(null, name, detail))
-            val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "CONFIRM_DONE", "확인")
+            val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "CONFIRM_DONE", "i-1", "확인")
             assertEquals(OperationResult.REJECTED, outcome.result)
             assertEquals(name, outcome.outcome)
             assertNull(outcome.rejection)
@@ -169,7 +177,7 @@ class IncidentOperationsTest {
             row("incident-2"),
             row("incident-1", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))),
         )
-        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업")
+        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
         assertEquals(OperationResult.NO_RESPONSE, outcome.result)
         assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
         assertNull(outcome.outcome)
@@ -194,13 +202,13 @@ class IncidentOperationsTest {
         misses.forEach { miss ->
             clock.now = T0
             bench.incidents = bench.listed(miss)
-            val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업")
+            val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
             assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation, miss)
         }
         // 요청 직전 시각과 같은 판단 시각은 반영이다(경계).
         clock.now = T0
         bench.incidents = bench.listed(row("incident-1", resolution = resolution("REWORK", "kim", T0)))
-        assertEquals(OperationResult.CONFIRMED_APPLIED, resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업").confirmation)
+        assertEquals(OperationResult.CONFIRMED_APPLIED, resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation)
     }
 
     @Test
@@ -208,19 +216,19 @@ class IncidentOperationsTest {
         bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
         // 호스트가 받은 뒤(T0+10초)가 아니라 보내기 직전(T0)과 비교해야 T0+1초의 판단이 반영으로 읽힌다.
         bench.incidents = bench.listed(row("incident-1", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))))
-        assertEquals(OperationResult.CONFIRMED_APPLIED, resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업").confirmation)
+        assertEquals(OperationResult.CONFIRMED_APPLIED, resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation)
     }
 
     @Test
     fun `판단 재조회가 목록을 못 읽으면 확인 행이 없고 모르는 결과 이름은 응답 없음이다`() {
         bench.resolveAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
         bench.incidents = HostCall.Silent("응답 없음: ConnectException")
-        assertNull(resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업").confirmation)
+        assertNull(resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation)
 
         bench.resolveAnswer = HostWrite.Answered(200, IncidentBench.resolved(null, "RESOLVED"))
         bench.incidents = bench.listed()
         clock.now = T0
-        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업")
+        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
         assertEquals(OperationResult.NO_RESPONSE, outcome.result)
         assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
         assertEquals(
@@ -229,10 +237,83 @@ class IncidentOperationsTest {
         )
     }
 
+    @Test
+    fun `이전 인스턴스를 실은 판단의 호스트 409 INSTANCE_MISMATCH 는 거부이고 원래 이름이 응답 칸과 응답에 있다`() {
+        bench.resolveAnswer = HostWrite.Answered(
+            409, """{"error":"INSTANCE_MISMATCH","detail":"판단 요청의 인스턴스(i-0)가 지금 인스턴스(i-1)가 아니다"}""",
+        )
+        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-0", "재작업")
+        assertEquals(OperationResult.REJECTED, outcome.result)
+        assertEquals(HostRejection(409, "INSTANCE_MISMATCH", "판단 요청의 인스턴스(i-0)가 지금 인스턴스(i-1)가 아니다"), outcome.rejection)
+        assertNull(outcome.outcome)
+        assertNull(outcome.confirmation)
+        assertEquals("i-0", bench.writes().single().instanceId)
+        val row = log.list().single()
+        assertEquals(OperationResult.REJECTED, row.result)
+        assertEquals("INSTANCE_MISMATCH", json.readTree(row.targetResponse)["body"]["error"].asText())
+        assertEquals(409, json.readTree(row.targetResponse)["status"].asInt())
+        assertEquals("i-0", json.readTree(row.request)["instanceId"].asText())
+        // 재조회하지 않는다.
+        assertEquals(emptyList(), bench.calls.filter { it.op == "incidents" })
+    }
+
+    @Test
+    fun `판단 재조회는 목록의 인스턴스가 요청한 인스턴스와 다르면 incidents 가 아니라 earlier 의 그 인스턴스 사본만 본다`() {
+        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
+        val applied = resolution("REWORK", "kim", T0.plusSeconds(1))
+        // 재기동 뒤 새 인스턴스의 exec-1 은 다른 실행이다. 같은 실행 id·단위·판단자·결정이어도 반영으로 읽지 않는다.
+        bench.incidents = bench.listedAt("i-2", listOf(row("incident-1", resolution = applied)))
+        val newInstance = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
+        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, newInstance.confirmation)
+
+        // 이전 인스턴스 사본 가운데 요청한 인스턴스(i-1)의 것만 본다. 다른 이전 인스턴스(i-0)의 판단은 반영이 아니다.
+        clock.now = T0
+        bench.incidents = bench.listedAt("i-2", emptyList(), listOf(IncidentBench.copy("i-0", "incident-1", applied)))
+        assertEquals(
+            OperationResult.CONFIRMED_NOT_APPLIED,
+            resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation,
+        )
+
+        clock.now = T0
+        bench.incidents = bench.listedAt(
+            "i-2",
+            listOf(row("incident-1")),
+            listOf(IncidentBench.copy("i-1", "incident-2"), IncidentBench.copy("i-1", "incident-1", applied)),
+        )
+        val earlier = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
+        assertEquals(OperationResult.CONFIRMED_APPLIED, earlier.confirmation)
+        val observed = json.readTree(log.list().first().targetResponse)["observed"]
+        assertEquals("i-1", observed["instanceId"].asText())
+        assertEquals("incident-1", observed["incidentId"].asText())
+
+        // 같은 인스턴스면 지금 목록을 본다(앞 시험들과 같다).
+        clock.now = T0
+        bench.incidents = bench.listedAt("i-1", listOf(row("incident-1", resolution = applied)))
+        assertEquals(
+            OperationResult.CONFIRMED_APPLIED,
+            resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation,
+        )
+    }
+
+    @Test
+    fun `판단 재조회의 반영 안 됨 관측은 요청한 인스턴스의 그 단위 가장 최근 사본이다`() {
+        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
+        bench.incidents = bench.listedAt(
+            "i-2",
+            listOf(row("incident-9")),
+            listOf(IncidentBench.copy("i-1", "incident-3"), IncidentBench.copy("i-1", "incident-2", executionId = "exec-2")),
+        )
+        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
+        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
+        val observed = json.readTree(log.list().first().targetResponse)["observed"]
+        assertEquals("incident-3", observed["incidentId"].asText())
+        assertEquals("i-1", observed["instanceId"].asText())
+    }
+
     @Test
     fun `판단 본문이 틀렸다는 호스트 400 은 거부이고 오류 이름을 넘긴다`() {
         bench.resolveAnswer = HostWrite.Answered(400, """{"error":"BAD_REQUEST","detail":"decision 이 두 값이 아니다"}""")
-        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업")
+        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
         assertEquals(OperationResult.REJECTED, outcome.result)
         assertEquals("BAD_REQUEST", outcome.rejection!!.error)
         assertNull(outcome.outcome)
```

- [ ] **Step 3: 커밋 `feat(ops-service): 이전 인스턴스 인시던트 중계, 판단의 인스턴스, 송신 기록 중계`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add ops-service/src/main/kotlin/dev/picasso/ops/service/web/JobResponseController.kt ops-service/src/test/kotlin/dev/picasso/ops/service/JobResponseControllerTest.kt ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/HoldResolutions.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/IncidentController.kt ops-service/src/test/kotlin/dev/picasso/ops/service/HostIncidentsClientTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentBench.kt ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentControllerTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentOperationsTest.kt && git commit -F - <<'EOF'
feat(ops-service): 이전 인스턴스 인시던트 중계, 판단의 인스턴스, 송신 기록 중계

- GET /api/incidents 는 earlierTotal·earlier 를 그대로 중계, 상세는 instanceId 질의 중계(빈 값은 400 INCIDENT_BAD_REQUEST)
- 판단 본문 instanceId 필수(없음·공백·문자열 아님은 사유보다 먼저 400 RESOLVE_BAD_REQUEST), 호스트 본문 끝에 instanceId, 조작 기록 request 끝에 instanceId
- 호스트 409 INSTANCE_MISMATCH 는 기존 4xx 경로로 REJECTED 와 원래 이름
- 응답 없음 뒤 재조회는 목록 instanceId 가 요청과 같을 때만 incidents, 다르면 earlier 의 그 인스턴스 사본만 대조
- GET /api/job-responses: 호스트 GET /host/job-responses 중계, 빈 jobOrderId·범위 밖 limit 은 400 JOB_RESPONSE_BAD_REQUEST, 불통 503 HOST_SILENT
- 시험 10개 추가(조작 셋, 컨트롤러 둘, 클라이언트 둘, 송신 기록 컨트롤러 셋), 기존 판단 시험은 instanceId 를 싣도록 수정

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 4: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && ./gradlew :ops-service:check -q
```
Expected: ops-service 241, 실패 0(`check` 는 시험과 함께 운영 서비스가 picasso 를 쓰지 않는 경계 `checkNoPicassoOnMain` 을 집행한다). 백그라운드로 돌린다.

- [ ] **Step 5: 묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s4b-cmp.sh" --at df11291 ops-service/src/main/kotlin/dev/picasso/ops/service/web/JobResponseController.kt ops-service/src/test/kotlin/dev/picasso/ops/service/JobResponseControllerTest.kt ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/HoldResolutions.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/IncidentController.kt ops-service/src/test/kotlin/dev/picasso/ops/service/HostIncidentsClientTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentBench.kt ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentControllerTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentOperationsTest.kt
```
Expected: 9개 모두 `같음`(이 Task 의 마지막 스파이크 커밋 `df11291` 의 파일과 바이트 대조, 뒤 Task 가 같은 파일을 다시 고칠 수 있어 HEAD 가 아니다).

### Task 4: 화면: 복원 보고 띠, 이전 인스턴스 인시던트, 작업 응답 송신 기록, Playwright 단계

**Files:**
- Create: `ui/src/components/ExecutionRestore.test.tsx`, `ui/src/components/JobResponseLog.test.tsx`, `ui/src/components/JobResponseLog.tsx`
- Modify: `ui/src/api.ts`, `ui/src/components/ExecutionList.tsx`, `ui/src/components/IncidentSection.test.tsx`, `ui/src/components/IncidentSection.tsx`, `ui/src/components/OperationsArea.tsx`, `ui/src/labels.ts`, `ui/src/styles.css`, `ui/src/testing/fakeOps.ts`, `ui/e2e/lifecycle.spec.ts`

- [ ] **Step 1: (커밋 1/2) 새 파일 3개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/files/ui/src/components/ExecutionRestore.test.tsx" ui/src/components/ExecutionRestore.test.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/files/ui/src/components/JobResponseLog.test.tsx" ui/src/components/JobResponseLog.test.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/files/ui/src/components/JobResponseLog.tsx" ui/src/components/JobResponseLog.tsx
```

`ui/src/components/ExecutionRestore.test.tsx`:

```tsx
import { render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Execution, Session } from '../api'
import { executionsView, installFakeOps, restoreRow } from '../testing/fakeOps'
import { OperationsArea } from './OperationsArea'

const operator: Session = { mode: 'operator', user: 'kim' }

const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }

/** 실행 한 줄(S3a JSON 계약 §5, S4b 계약 H1). 기본은 새로 받은 도는 실행이다. */
function execution(partial: Partial<Execution> = {}): Execution {
  return {
    executionId: 'exec-1',
    jobOrderId: 'JO-20261009-aaaaaaaa',
    workMasterId: 'PrepareSequencedRack',
    missionVersion: 2,
    robotId: 'humanoid-01',
    physicalState: 'RUNNING',
    units: [{ unitId: 'rack-arrival', skillType: 'equipment_wait', state: 'RUNNING', reached: 'E0' }],
    jobResponse: null,
    restoredFrom: null,
    ...partial,
  }
}

const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((cell) => cell.textContent)

function open() {
  return render(<OperationsArea session={operator} onChanged={() => undefined} />)
}

describe('재기동 뒤 실행 목록', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('재기동 복원 보고는 다시 지은 수와 미룬 것·포기한 것의 사유를 실행 목록 위에 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({
      instanceId: 'mw-1',
      executions: [execution({ restoredFrom: { instanceId: 'mw-0', executionId: 'exec-3' } })],
      restore: {
        at: 'h1',
        rows: [
          restoreRow(),
          restoreRow({
            jobOrderId: 'JO-B',
            robotId: 'quadruped-01',
            previousExecutionId: 'exec-4',
            result: 'DEFERRED',
            executionId: null,
            reason: '기체 스냅숏을 못 읽어 다시 짓지 않는다: robot=quadruped-01, 재작업 횟수와 지난 설비 대기를 정할 근거가 없다',
          }),
          restoreRow({
            jobOrderId: 'JO-C',
            previousExecutionId: 'exec-5',
            result: 'GAVE_UP',
            executionId: null,
            reason: '임무 버전 행이 없다: PrepareSequencedRack 버전 4',
          }),
        ],
      },
    })
    open()
    const region = screen.getByRole('region', { name: '실행' })
    const band = await within(region).findByRole('region', { name: '재기동 복원 보고' })
    expect(band).toHaveTextContent('재기동: 이전 인스턴스의 실행 1건을 다시 지었습니다(복원 시각 h1)')
    expect(within(band).getByRole('list', { name: '다시 지은 실행' })).toHaveTextContent(
      'JO-20261009-aaaaaaaa(humanoid-01): 이전 exec-3 → exec-1',
    )
    expect(band).toHaveTextContent('미룬 실행 1건. 실행 호스트가 다시 시도하며 그동안 그 기체는 배정에서 빠집니다')
    expect(within(band).getByRole('list', { name: '미룬 실행' })).toHaveTextContent(
      'JO-B(quadruped-01, 이전 exec-4): 기체 스냅숏을 못 읽어 다시 짓지 않는다: robot=quadruped-01',
    )
    expect(band).toHaveTextContent('포기한 실행 1건. 다시 짓지 않습니다. 기체의 남은 태스크를 운영자가 확인하십시오')
    expect(within(band).getByRole('list', { name: '포기한 실행' })).toHaveTextContent(
      'JO-C(humanoid-01, 이전 exec-5): 임무 버전 행이 없다: PrepareSequencedRack 버전 4',
    )
    // 띠는 실행 목록 표 위에 있다.
    const table = within(region).getByRole('table', { name: '실행 목록' })
    expect(band.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('다시 지은 실행 행은 실행 id 옆에 이전 exec-k 를 보이고 새로 받은 실행에는 없다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({
      executions: [
        execution({ restoredFrom: { instanceId: 'mw-0', executionId: 'exec-3' } }),
        execution({ executionId: 'exec-2', jobOrderId: 'JO-NEW' }),
      ],
      restore: { at: 'h1', rows: [restoreRow()] },
    })
    open()
    const table = await screen.findByRole('table', { name: '실행 목록' })
    const [newer, restored] = within(table).getAllByRole('row').slice(1)
    expect(cells(newer)[0]).toBe('exec-2')
    expect(cells(restored)[0]).toBe('exec-1 (이전 exec-3)')
    expect(within(restored).getByTitle('이전 인스턴스 mw-0')).toHaveTextContent('(이전 exec-3)')
  })

  it('다시 지을 것이 없었거나 복원 보고가 없으면 띠가 없다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: [execution()], restore: { at: 'h1', rows: [] } })
    const { rerender } = open()
    await screen.findByRole('table', { name: '실행 목록' })
    expect(screen.queryByRole('region', { name: '재기동 복원 보고' })).toBeNull()
    expect(screen.queryByText(/이전 exec-/)).toBeNull()

    fake.executions = executionsView({ executions: [execution({ executionId: 'exec-9' })] })
    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
    await screen.findByRole('cell', { name: 'exec-9' })
    expect(screen.queryByRole('region', { name: '재기동 복원 보고' })).toBeNull()
  })
})
```

`ui/src/components/JobResponseLog.test.tsx`:

```tsx
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Execution, Session } from '../api'
import { POLL_MS } from '../poll'
import { executionsView, installFakeOps, jobResponseRow, jobResponsesView } from '../testing/fakeOps'
import type { FakeOps } from '../testing/fakeOps'
import { OperationsArea } from './OperationsArea'

const engineer: Session = { mode: 'engineer', user: 'lee' }
const operator: Session = { mode: 'operator', user: 'kim' }

const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }

const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((cell) => cell.textContent)

const responseGets = (fake: FakeOps) =>
  fake.calls.filter((call) => call.method === 'GET' && call.url.startsWith('/api/job-responses')).map((call) => call.url)

function execution(executionId: string, jobOrderId: string): Execution {
  return {
    executionId,
    jobOrderId,
    workMasterId: 'PrepareSequencedRack',
    missionVersion: 2,
    robotId: 'humanoid-01',
    physicalState: 'RUNNING',
    units: [],
    jobResponse: null,
    restoredFrom: null,
  }
}

/** 재기동 앞뒤의 같은 보류 응답 둘과 다른 작업 지시의 완료 응답 하나. 최근에 적은 것부터다. */
function threeResponses() {
  return jobResponsesView([
    jobResponseRow({ instanceId: 'mw-1', disposition: 'RESTART_DUPLICATE', recordedAt: 'w40' }),
    jobResponseRow({
      instanceId: 'mw-0',
      jobResponseId: 'resp-2',
      jobOrderId: 'JO-B',
      executionId: 'exec-2',
      physicalState: 'PHYSICALLY_DONE',
      reachedEvidence: 'E2',
      completedUnits: ['RACK-204.S01', 'rack-arrival'],
      incompleteUnits: [],
      operatorRequired: false,
      recordedAt: 'w35',
    }),
    jobResponseRow({ instanceId: 'mw-0', recordedAt: 'w30' }),
  ])
}

function open(session: Session = operator) {
  return render(<OperationsArea session={session} onChanged={() => undefined} />)
}

describe('작업 응답 송신 기록', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('송신 행과 재기동 중복 행을 최근 것부터 단위와 처분과 함께 보이고 재기동 중복 수를 센다', async () => {
    const fake = installFakeOps(emptyList)
    fake.jobResponses = { ...threeResponses(), total: 7 }
    open()
    const region = screen.getByRole('region', { name: '작업 응답 송신 기록' })
    const table = await within(region).findByRole('table', { name: '송신 기록 목록' })
    expect(region).toHaveTextContent('실행 호스트 인스턴스 mw-1, 송신 기록 7건 가운데 최신 3건, 그 가운데 재기동 중복 1건')
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows.map(cells)).toEqual([
      ['mw-1', 'resp-1', 'JO-20261009-aaaaaaaa', 'exec-1', '1', 'PARTIAL', 'E0/E2', '미완 rack-arrival', '필요', '재기동 중복(송신 안 함)', 'w40'],
      ['mw-0', 'resp-2', 'JO-B', 'exec-2', '1', 'PHYSICALLY_DONE', 'E2/E2', '완료 RACK-204.S01, rack-arrival', '-', '송신', 'w35'],
      ['mw-0', 'resp-1', 'JO-20261009-aaaaaaaa', 'exec-1', '1', 'PARTIAL', 'E0/E2', '미완 rack-arrival', '필요', '송신', 'w30'],
    ])
    expect(rows[0]).toHaveClass('duplicate')
    expect(rows[1]).not.toHaveClass('duplicate')
    expect(responseGets(fake)).toEqual(['/api/job-responses'])
  })

  it('작업 지시를 고르면 그 작업 지시로 다시 읽고 전체를 고르면 쿼리 없이 읽으며 두 모드가 같다', async () => {
    const fake = installFakeOps(emptyList)
    fake.jobResponses = threeResponses()
    fake.executions = executionsView({ executions: [execution('exec-1', 'JO-20261009-aaaaaaaa'), execution('exec-3', 'JO-NEW')] })
    open(engineer)
    const region = screen.getByRole('region', { name: '작업 응답 송신 기록' })
    await within(region).findByRole('table', { name: '송신 기록 목록' })
    const choose = within(region).getByLabelText('송신 기록의 작업 지시')
    // 실행 목록의 작업 지시(최신부터)와 읽은 송신 기록의 작업 지시를 고를 수 있다.
    expect(within(choose).getAllByRole('option').map((option) => option.textContent)).toEqual([
      '전체',
      'JO-NEW',
      'JO-20261009-aaaaaaaa',
      'JO-B',
    ])
    await userEvent.selectOptions(choose, 'JO-B')
    expect(await within(region).findByText(/송신 기록 1건 가운데 최신 1건, 그 가운데 재기동 중복 0건/)).toBeInTheDocument()
    expect(within(within(region).getByRole('table', { name: '송신 기록 목록' })).getAllByRole('row')).toHaveLength(2)
    expect(responseGets(fake).at(-1)).toBe('/api/job-responses?jobOrderId=JO-B')
    await userEvent.selectOptions(choose, '전체')
    expect(await within(region).findByText(/송신 기록 3건 가운데 최신 3건/)).toBeInTheDocument()
    expect(responseGets(fake).at(-1)).toBe('/api/job-responses')
  })

  it('주기마다 다시 읽고 실행 호스트가 503 이면 직전 값과 불통을, 한 번도 못 읽으면 모름을 보인다', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const fake = installFakeOps(emptyList)
    fake.jobResponses = threeResponses()
    const flush = async () => {
      for (let round = 0; round < 5; round++) await act(async () => undefined)
    }
    open()
    await flush()
    const region = screen.getByRole('region', { name: '작업 응답 송신 기록' })
    expect(within(region).getByRole('table', { name: '송신 기록 목록' })).toBeInTheDocument()
    const before = responseGets(fake).length
    fake.failing.add('/api/job-responses')
    await act(async () => vi.advanceTimersByTime(POLL_MS))
    await flush()
    expect(responseGets(fake)).toHaveLength(before + 1)
    expect(region).toHaveTextContent('직전 값입니다. 실행 호스트 불통: 실행 호스트가 답하지 않는다: 응답 없음: ConnectException')
    expect(within(region).getAllByRole('row')).toHaveLength(4)
  })

  it('송신 기록을 한 번도 못 읽으면 모름이고 행이 없으면 없음이다', async () => {
    const fake = installFakeOps(emptyList)
    fake.failing.add('/api/job-responses')
    const { unmount } = open()
    expect(
      await screen.findByText(
        '모름: 송신 기록을 아직 읽지 못했습니다 (실행 호스트가 답하지 않는다: 응답 없음: ConnectException)',
      ),
    ).toBeInTheDocument()
    unmount()
    fake.failing.delete('/api/job-responses')
    open()
    expect(await screen.findByText('송신 기록이 없습니다')).toBeInTheDocument()
  })

  it('운영 영역을 열기 전에는 송신 기록을 읽지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    render(<App />)
    expect(await screen.findByText('선언된 기체가 없습니다')).toBeInTheDocument()
    expect(responseGets(fake)).toEqual([])
    await userEvent.click(screen.getByRole('button', { name: '운영' }))
    expect(await screen.findByText('송신 기록이 없습니다')).toBeInTheDocument()
    expect(responseGets(fake).length).toBeGreaterThan(0)
  })
})
```

`ui/src/components/JobResponseLog.tsx`:

```tsx
import type { JobResponseLogRow, JobResponsesView } from '../api'
import { DISPOSITION_LABEL } from '../labels'
import type { HostRead } from './ExecutionList'

interface Props {
  read: HostRead<JobResponsesView>
  /** 고를 수 있는 작업 지시 id. 실행 목록과 읽은 송신 기록에서 모은 것이다. */
  jobOrderIds: string[]
  /** 고른 작업 지시. null 이면 전체다. */
  selected: string | null
  onSelect: (jobOrderId: string | null) => void
}

/** 단위 칸. 빈 묶음은 빼고, 다 비면 `-` 다. 미완 단위는 송신 기록에 사유가 없어 id 만 보인다(S4b 계약 H6). */
function units(row: JobResponseLogRow): string {
  const parts = [
    ['완료', row.completedUnits],
    ['미확인', row.unverifiedUnits],
    ['미완', row.incompleteUnits],
    ['불확실', row.inDoubtUnits],
    ['막은 결함', row.blockedBy],
  ] as const
  const shown = parts.filter(([, ids]) => ids.length > 0).map(([name, ids]) => `${name} ${ids.join(', ')}`)
  return shown.length === 0 ? '-' : shown.join('; ')
}

/**
 * 운영 영역의 «작업 응답 송신 기록» 구역(S4b 스펙 T6·T10, §8). 상위 시스템이 없어 실행 호스트의 송신 기록이 전선 자리를
 * 대신한다. 작업 지시를 고르면 그 작업 지시의 송신 행과 재기동 중복 행을 최근 것부터 보인다. 재기동 중복은 새 인스턴스가
 * 처음 낸 응답이 앞 인스턴스의 마지막 송신과 같아 보내지 않은 것이다. 읽기만 하므로 두 모드가 같다.
 */
export function JobResponseLog({ read, jobOrderIds, selected, onSelect }: Props) {
  const { value, error } = read
  const options = [...new Set([...(selected === null ? [] : [selected]), ...jobOrderIds])]
  const duplicates = value?.responses.filter((row) => row.disposition === 'RESTART_DUPLICATE').length ?? 0
  return (
    <section aria-label="작업 응답 송신 기록">
      <h2>작업 응답 송신 기록</h2>
      <label>
        송신 기록의 작업 지시{' '}
        <select value={selected ?? ''} onChange={(event) => onSelect(event.target.value === '' ? null : event.target.value)}>
          <option value="">전체</option>
          {options.map((id) => (
            <option key={id} value={id}>
              {id}
            </option>
          ))}
        </select>
      </label>
      {value === null ? (
        <p>모름: 송신 기록을 아직 읽지 못했습니다{error !== null && ` (${error})`}</p>
      ) : (
        <>
          {error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {error}</p>}
          <p>
            실행 호스트 인스턴스 {value.instanceId}, 송신 기록 {value.total}건 가운데 최신 {value.responses.length}건, 그
            가운데 재기동 중복 {duplicates}건
          </p>
          {value.responses.length === 0 ? (
            <p>송신 기록이 없습니다</p>
          ) : (
            <table aria-label="송신 기록 목록">
              <thead>
                <tr>
                  <th>인스턴스</th>
                  <th>작업 응답 id</th>
                  <th>작업 지시 id</th>
                  <th>실행 id</th>
                  <th>버전</th>
                  <th>물리 상태</th>
                  <th>근거(도달/요구)</th>
                  <th>단위</th>
                  <th>운영자 필요</th>
                  <th>처분</th>
                  <th>적은 시각</th>
                </tr>
              </thead>
              <tbody>
                {value.responses.map((row) => (
                  <tr
                    key={`${row.instanceId}/${row.jobResponseId}`}
                    className={row.disposition === 'RESTART_DUPLICATE' ? 'duplicate' : undefined}
                  >
                    <td>{row.instanceId}</td>
                    <td>{row.jobResponseId}</td>
                    <td>{row.jobOrderId}</td>
                    <td>{row.executionId}</td>
                    <td>{row.version}</td>
                    <td>{row.physicalState}</td>
                    <td>
                      {row.reachedEvidence}/{row.requiredEvidence}
                    </td>
                    <td>{units(row)}</td>
                    <td>{row.operatorRequired ? '필요' : '-'}</td>
                    <td>{DISPOSITION_LABEL[row.disposition] ?? row.disposition}</td>
                    <td>{row.recordedAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  )
}
```

- [ ] **Step 2: (커밋 1/2) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task4a.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task4a.patch"
```

```diff
diff --git a/ui/src/api.ts b/ui/src/api.ts
index b90b3cd..ea8e868 100644
--- a/ui/src/api.ts
+++ b/ui/src/api.ts
@@ -516,7 +516,10 @@ export interface JobResponse {
   connection: string
 }
 
-/** 실행 하나(S3a JSON 계약 §5). `missionVersion` 이 null 이면 코드 정의 임무다. */
+/**
+ * 실행 하나(S3a JSON 계약 §5, S4b 계약 H1). `missionVersion` 이 null 이면 코드 정의 임무다. `restoredFrom` 은 이번 기동이
+ * 다시 지은 실행의 바로 앞 인스턴스 실행이고, 새로 받은 실행이면 null 이다. 옛 호스트는 칸을 싣지 않는다.
+ */
 export interface Execution {
   executionId: string
   jobOrderId: string
@@ -526,13 +529,32 @@ export interface Execution {
   physicalState: string
   units: ExecutionUnit[]
   jobResponse: JobResponse | null
+  restoredFrom?: { instanceId: string; executionId: string } | null
+}
+
+/** 다시 짓기 결과 셋(S4b 스펙 T3). */
+export type RestoreResult = 'RESTORED' | 'DEFERRED' | 'GAVE_UP'
+
+/** 복원 보고의 행 하나(S4b 계약 H1, 7칸). `executionId` 는 RESTORED 일 때만, `reason` 은 그 밖일 때만 있다. */
+export interface RestoreRow {
+  jobOrderId: string
+  robotId: string
+  previousInstanceId: string
+  previousExecutionId: string
+  result: RestoreResult
+  executionId: string | null
+  reason: string | null
 }
 
-/** 운영 서비스의 `GET /api/executions`(실행 호스트 본문 그대로). `instanceId` 가 바뀌면 호스트가 재기동한 것이다. */
+/**
+ * 운영 서비스의 `GET /api/executions`(실행 호스트 본문 그대로). `instanceId` 가 바뀌면 호스트가 재기동한 것이다. `restore` 는
+ * 이번 기동의 복원 보고다(S4b 계약 H1). 다시 지을 것이 없었으면 `rows` 가 비어 있다.
+ */
 export interface ExecutionsView {
   instanceId: string
   pumpedAt: string | null
   executions: Execution[]
+  restore?: { at: string; rows: RestoreRow[] } | null
 }
 
 /** 셀 대역의 자리 하나(S3a JSON 계약 §1). 제시 자리의 `observedAt` 은 늘 null 이다. */
@@ -839,11 +861,21 @@ export interface IncidentRow {
   confirmedWithoutEvidence: boolean
 }
 
-/** 운영 서비스의 `GET /api/incidents`(S4a JSON 계약 §9.2). `incidents` 는 최신부터이고 `total` 은 자르기 전의 수다. */
+/** 이전 인스턴스의 인시던트 사본 한 줄(S4b 계약 H3, 20칸). 그 사본의 `instanceId` 를 앞에 둔다. 늘 보류가 아니다. */
+export interface EarlierIncidentRow extends IncidentRow {
+  instanceId: string
+}
+
+/**
+ * 운영 서비스의 `GET /api/incidents`(S4a JSON 계약 §9.2, S4b 계약 H3). `incidents` 는 지금 인스턴스의 것이고 최신부터이며
+ * `total` 은 자르기 전의 수다. `earlier` 는 재기동 앞 인스턴스들의 사본이고 최근에 적은 것부터다.
+ */
 export interface IncidentsView {
   instanceId: string
   total: number
   incidents: IncidentRow[]
+  earlierTotal?: number
+  earlier?: EarlierIncidentRow[]
 }
 
 /** 결함 원문(S4a JSON 계약 §4, 9칸). `vendorDetail`·`errorHint`·`activeUntilTime` 은 빈 문자열일 수 있다. */
@@ -930,9 +962,15 @@ export type IncidentLookup = { kind: 'found'; detail: IncidentDetail } | { kind:
 
 export const fetchIncidents = (session: Session) => getHostJson<IncidentsView>('/api/incidents', session)
 
-/** 404 `INCIDENT_NOT_FOUND` 만 없음이다. 그 밖의 실패(503 `HOST_SILENT` 포함)는 못 읽음이라 던진다. */
-export async function fetchIncident(session: Session, incidentId: string): Promise<IncidentLookup> {
-  const response = await fetch(`/api/incidents/${encodeURIComponent(incidentId)}`, { headers: actorHeaders(session) })
+/**
+ * 404 `INCIDENT_NOT_FOUND` 만 없음이다. 그 밖의 실패(503 `HOST_SILENT` 포함)는 못 읽음이라 던진다. 인스턴스를 늘 싣는다
+ * (S4b 계약 H4). 재기동 뒤 `incident-N` 을 다시 세므로, 인스턴스 없이 읽으면 같은 id 의 다른 인시던트를 읽는다.
+ */
+export async function fetchIncident(session: Session, instanceId: string, incidentId: string): Promise<IncidentLookup> {
+  const response = await fetch(
+    `/api/incidents/${encodeURIComponent(incidentId)}?instanceId=${encodeURIComponent(instanceId)}`,
+    { headers: actorHeaders(session) },
+  )
   if (response.ok) return { kind: 'found', detail: (await response.json()) as IncidentDetail }
   const body = (await response.json().catch(() => null)) as Partial<PreRejection> | null
   const detail = typeof body?.detail === 'string' && body.detail !== '' ? body.detail : `운영 서비스 응답 ${response.status}`
@@ -1012,16 +1050,56 @@ export const injectFault = (
     kind === 'CONNECTION' ? { robotId, kind, state, reason } : { robotId, kind, reason },
   )
 
-/** 승인자는 싣지 않는다. 운영 서비스가 `X-Ops-User` 로 정한다(S4a JSON 계약 §9.5, ADR 43). */
+/**
+ * 승인자는 싣지 않는다. 운영 서비스가 `X-Ops-User` 로 정한다(S4a JSON 계약 §9.5, ADR 43). 인스턴스는 상세의 `instanceId`
+ * 이고, 실행 호스트가 그 사이 재기동했으면 409 `INSTANCE_MISMATCH` 로 막는다(S4b 스펙 T8).
+ */
 export const resolveHold = (
   session: Session,
-  executionId: string,
-  unitId: string,
+  target: { instanceId: string; executionId: string; unitId: string },
   decision: HoldDecision,
   reason: string,
 ) =>
   postOps<HoldResolveOutcome>(
-    `/api/executions/${encodeURIComponent(executionId)}/units/${encodeURIComponent(unitId)}/resolve`,
+    `/api/executions/${encodeURIComponent(target.executionId)}/units/${encodeURIComponent(target.unitId)}/resolve`,
+    session,
+    { decision, instanceId: target.instanceId, reason },
+  )
+
+/** 송신 기록 행의 처분(S4b 스펙 T6). 재기동 중복은 송신하지 않은 것이다. */
+export type JobResponseDisposition = 'SENT' | 'RESTART_DUPLICATE'
+
+/** 작업 응답 송신 기록 한 행(S4b 계약 H6, 17칸). 단위 배열은 정렬됐고 `incompleteUnits` 는 사유 없는 id 다. */
+export interface JobResponseLogRow {
+  instanceId: string
+  jobResponseId: string
+  jobOrderId: string
+  executionId: string
+  version: number
+  physicalState: string
+  requiredEvidence: string
+  reachedEvidence: string
+  completedUnits: string[]
+  unverifiedUnits: string[]
+  inDoubtUnits: string[]
+  incompleteUnits: string[]
+  operatorRequired: boolean
+  residualHold: string
+  blockedBy: string[]
+  disposition: JobResponseDisposition
+  recordedAt: string
+}
+
+/** 운영 서비스의 `GET /api/job-responses`(S4b 계약 H6). `responses` 는 최근에 적은 것부터이고 `total` 은 자르기 전의 수다. */
+export interface JobResponsesView {
+  instanceId: string
+  total: number
+  responses: JobResponseLogRow[]
+}
+
+/** 작업 지시를 고르지 않으면(null) 전체다. 건수는 실행 호스트 기본(50)이다. */
+export const fetchJobResponses = (session: Session, jobOrderId: string | null) =>
+  getHostJson<JobResponsesView>(
+    jobOrderId === null ? '/api/job-responses' : `/api/job-responses?jobOrderId=${encodeURIComponent(jobOrderId)}`,
     session,
-    { decision, reason },
   )
diff --git a/ui/src/components/ExecutionList.tsx b/ui/src/components/ExecutionList.tsx
index 827241b..5d4fa09 100644
--- a/ui/src/components/ExecutionList.tsx
+++ b/ui/src/components/ExecutionList.tsx
@@ -1,4 +1,4 @@
-import type { ExecutionsView, JobResponse } from '../api'
+import type { ExecutionsView, JobResponse, RestoreRow } from '../api'
 
 /** 실행 호스트를 거친 읽기 하나. 못 읽으면 직전 값을 지우지 않고 [error] 로 불통을 표시한다. */
 export interface HostRead<T> {
@@ -15,6 +15,9 @@ interface Props {
 /**
  * 실행 목록(S3a 스펙 §9.2). 머리에 실행 호스트 인스턴스를 보인다. 호스트를 재기동하면 실행이 사라지고 `exec-N` 을 1부터
  * 다시 세므로, 인스턴스가 바뀐 것으로 구별한다. 임무 버전이 null 이면 코드 정의 임무다. 최신 실행부터 보인다.
+ *
+ * 재기동 뒤(S4b 스펙 T10, §8)에는 목록 위에 이번 기동의 복원 보고를 보이고, 다시 지은 실행의 실행 id 옆에 바로 앞 인스턴스의
+ * 실행 id 를 «이전 exec-k» 로 붙인다. 새 인스턴스의 `exec-N` 은 1부터 다시 세므로 같은 id 가 다른 실행일 수 있다.
  */
 export function ExecutionList({ read, highlight }: Props) {
   const { value, error } = read
@@ -30,6 +33,9 @@ export function ExecutionList({ read, highlight }: Props) {
         <dt>마지막 pump</dt>
         <dd>{value.pumpedAt ?? '아직 없음'}</dd>
       </dl>
+      {value.restore != null && value.restore.rows.length > 0 && (
+        <RestoreReport at={value.restore.at} rows={value.restore.rows} />
+      )}
       {value.executions.length === 0 ? (
         <p>실행이 없습니다</p>
       ) : (
@@ -52,7 +58,15 @@ export function ExecutionList({ read, highlight }: Props) {
                 key={execution.executionId}
                 className={execution.jobOrderId === highlight ? 'selected' : undefined}
               >
-                <td>{execution.executionId}</td>
+                <td>
+                  {execution.executionId}
+                  {execution.restoredFrom != null && (
+                    <span className="restored" title={`이전 인스턴스 ${execution.restoredFrom.instanceId}`}>
+                      {' '}
+                      (이전 {execution.restoredFrom.executionId})
+                    </span>
+                  )}
+                </td>
                 <td>{execution.jobOrderId}</td>
                 <td>{execution.workMasterId}</td>
                 <td>{execution.missionVersion === null ? '코드 정의' : `버전 ${execution.missionVersion}`}</td>
@@ -77,6 +91,54 @@ export function ExecutionList({ read, highlight }: Props) {
   )
 }
 
+/**
+ * 이번 기동의 복원 보고(S4b 계약 H1). 다시 지은 것, 기체 스냅숏을 못 읽어 미룬 것(실행 호스트가 pump 마다 다시 시도),
+ * 포기한 것을 나누고, 미룬 것과 포기한 것은 실행 호스트의 사유를 그대로 보인다. 포기한 실행의 기체는 고아 태스크가 끝날
+ * 때까지 배정 가능에서 빠진다.
+ */
+function RestoreReport({ at, rows }: { at: string; rows: RestoreRow[] }) {
+  const restored = rows.filter((row) => row.result === 'RESTORED')
+  const deferred = rows.filter((row) => row.result === 'DEFERRED')
+  const gaveUp = rows.filter((row) => row.result === 'GAVE_UP')
+  const unsettled = (row: RestoreRow) => `${row.jobOrderId}(${row.robotId}, 이전 ${row.previousExecutionId}): ${row.reason ?? '사유 없음'}`
+  return (
+    <section aria-label="재기동 복원 보고" className="restore">
+      <p>
+        <strong>재기동: 이전 인스턴스의 실행 {restored.length}건을 다시 지었습니다</strong>(복원 시각 {at})
+      </p>
+      {restored.length > 0 && (
+        <ul aria-label="다시 지은 실행">
+          {restored.map((row) => (
+            <li key={row.jobOrderId}>
+              {row.jobOrderId}({row.robotId}): 이전 {row.previousExecutionId} → {row.executionId}
+            </li>
+          ))}
+        </ul>
+      )}
+      {deferred.length > 0 && (
+        <>
+          <p>미룬 실행 {deferred.length}건. 실행 호스트가 다시 시도하며 그동안 그 기체는 배정에서 빠집니다</p>
+          <ul aria-label="미룬 실행">
+            {deferred.map((row) => (
+              <li key={row.jobOrderId}>{unsettled(row)}</li>
+            ))}
+          </ul>
+        </>
+      )}
+      {gaveUp.length > 0 && (
+        <>
+          <p>포기한 실행 {gaveUp.length}건. 다시 짓지 않습니다. 기체의 남은 태스크를 운영자가 확인하십시오</p>
+          <ul aria-label="포기한 실행">
+            {gaveUp.map((row) => (
+              <li key={row.jobOrderId}>{unsettled(row)}</li>
+            ))}
+          </ul>
+        </>
+      )}
+    </section>
+  )
+}
+
 /** 그 실행의 마지막 작업 응답. 상위 시스템이 없어 실행 호스트의 아웃박스에 남은 것이다(S3a 스펙 §7.7). */
 function JobResponseCell({ response }: { response: JobResponse }) {
   const incomplete = Object.entries(response.incompleteUnits)
diff --git a/ui/src/components/IncidentSection.test.tsx b/ui/src/components/IncidentSection.test.tsx
index de6a657..2f563aa 100644
--- a/ui/src/components/IncidentSection.test.tsx
+++ b/ui/src/components/IncidentSection.test.tsx
@@ -4,7 +4,7 @@ import { afterEach, describe, expect, it, vi } from 'vitest'
 import App from '../App'
 import type { FaultDetail, HoldResolveOutcome, IncidentResolution, Session } from '../api'
 import { POLL_MS, SIGNAL_SETTLE_MS } from '../poll'
-import { holdResolved, incidentDetail, incidentRow, incidentsView, installFakeOps } from '../testing/fakeOps'
+import { earlierRow, holdResolved, incidentDetail, incidentRow, incidentsView, installFakeOps } from '../testing/fakeOps'
 import type { FakeOps } from '../testing/fakeOps'
 import { OperationsArea } from './OperationsArea'
 
@@ -151,7 +151,7 @@ describe('인시던트', () => {
       ['41', 'h9', 'CELL_SIGNAL', 'signal rack_present at deadline: false', '미들웨어 기록'],
     ])
     expect(within(observed).getByText('근거 윈도우(앞 30초, 뒤 15초)')).toBeInTheDocument()
-    expect(calls(fake, 'GET', '/api/incidents/incident-1').length).toBeGreaterThan(0)
+    expect(calls(fake, 'GET', '/api/incidents/incident-1?instanceId=mw-1').length).toBeGreaterThan(0)
   })
 
   it('상세는 이 단위의 결함과 실행을 막던 결함(blockedBy) 원문을 보이고 코드 정의 임무는 코드 정의다', async () => {
@@ -322,7 +322,7 @@ describe('인시던트', () => {
     const reads = () => ({
       incidents: calls(fake, 'GET', '/api/incidents').length,
       executions: calls(fake, 'GET', '/api/executions').length,
-      detail: calls(fake, 'GET', '/api/incidents/incident-1').length,
+      detail: calls(fake, 'GET', '/api/incidents/incident-1?instanceId=mw-1').length,
     })
     const before = reads()
     fireEvent.click(within(detail).getByRole('button', { name: '재작업' }))
@@ -332,7 +332,7 @@ describe('인시던트', () => {
       'exec 1/rack/arrival 재작업: 판단이 섰습니다(incident-1)',
     )
     const post = calls(fake, 'POST', path).at(-1)!
-    expect(post.body).toEqual({ decision: 'REWORK', reason: '랙 재배치 뒤 재작업' })
+    expect(post.body).toEqual({ decision: 'REWORK', instanceId: 'mw-1', reason: '랙 재배치 뒤 재작업' })
     expect(post.headers['X-Ops-Mode']).toBe('operator')
     expect(post.headers['X-Ops-User']).toBe('kim')
     const now = reads()
@@ -413,12 +413,12 @@ describe('인시던트', () => {
     fireEvent.click(screen.getByRole('button', { name: 'incident-1 상세 보기' }))
     await flush()
     const list = calls(fake, 'GET', '/api/incidents').length
-    const detail = calls(fake, 'GET', '/api/incidents/incident-1').length
+    const detail = calls(fake, 'GET', '/api/incidents/incident-1?instanceId=mw-1').length
     expect(detail).toBe(1)
     await act(async () => vi.advanceTimersByTime(POLL_MS))
     await flush()
     expect(calls(fake, 'GET', '/api/incidents')).toHaveLength(list + 1)
-    expect(calls(fake, 'GET', '/api/incidents/incident-1')).toHaveLength(detail + 1)
+    expect(calls(fake, 'GET', '/api/incidents/incident-1?instanceId=mw-1')).toHaveLength(detail + 1)
 
     // 다시 읽은 상세가 판단을 실으면 그대로 바뀐다.
     fake.incidentDetails.set('incident-1', incidentDetail({ held: false, resolution: rework }))
@@ -469,3 +469,134 @@ describe('인시던트', () => {
     ).toBeInTheDocument()
   })
 })
+
+describe('재기동 뒤 인시던트', () => {
+  afterEach(() => {
+    vi.useRealTimers()
+    vi.unstubAllGlobals()
+  })
+
+  it('이전 인스턴스 부분은 earlier 사본을 인스턴스 열과 함께 읽기 전용으로 보이고 보류 열이 없다', async () => {
+    const fake = installFakeOps(emptyList)
+    fake.incidents = {
+      ...incidentsView([incidentRow()]),
+      earlierTotal: 4,
+      earlier: [
+        earlierRow(),
+        earlierRow({ instanceId: 'mw-00', incidentId: 'incident-2', at: 'h3', resolution: null, held: false }),
+      ],
+    }
+    open()
+    const region = screen.getByRole('region', { name: '인시던트' })
+    const earlier = await within(region).findByRole('region', { name: '이전 인스턴스' })
+    expect(earlier).toHaveTextContent('재기동 앞 인스턴스의 인시던트 4건 가운데 최신 2건. 읽기 전용이며 여기서 판단하지 않습니다')
+    const table = within(earlier).getByRole('table', { name: '이전 인스턴스 인시던트 목록' })
+    expect(within(table).getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
+      '인스턴스',
+      '인시던트',
+      '발생 시각',
+      '기체',
+      '실행 id',
+      '단위',
+      '실패 종류',
+      '경로',
+      '현장 설정 버전',
+      '임무 버전',
+      '판단',
+    ])
+    expect(within(table).getAllByRole('row').slice(1).map(cells)).toEqual([
+      ['mw-0', 'incident-1', 'h10', 'humanoid-01', 'exec-1', 'rack-arrival', 'SIGNAL_DEADLINE', 'SIGNAL', '버전 2', '버전 3', '판단됨'],
+      ['mw-00', 'incident-2', 'h3', 'humanoid-01', 'exec-1', 'rack-arrival', 'SIGNAL_DEADLINE', 'SIGNAL', '버전 2', '버전 3', '미해결'],
+    ])
+    expect(within(table).queryByText('보류 중')).toBeNull()
+    // 지금 인스턴스의 같은 id 줄은 지금 표에 따로 있다.
+    const current = within(region).getByRole('table', { name: '인시던트 목록' })
+    expect(within(current).getAllByRole('row')).toHaveLength(2)
+    expect(within(current).getByText('보류 중')).toBeInTheDocument()
+  })
+
+  it('이전 인스턴스 사본이 없으면 없음을 보이고 earlier 를 싣지 않는 목록에는 그 부분이 없다', async () => {
+    const fake = installFakeOps(emptyList)
+    fake.incidents = { ...incidentsView(), earlierTotal: 0, earlier: [] }
+    const { rerender } = open()
+    const earlier = await screen.findByRole('region', { name: '이전 인스턴스' })
+    expect(earlier).toHaveTextContent('이전 인스턴스의 인시던트가 없습니다')
+    fake.incidents = incidentsView([incidentRow()])
+    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
+    await screen.findByRole('table', { name: '인시던트 목록' })
+    expect(screen.queryByRole('region', { name: '이전 인스턴스' })).toBeNull()
+  })
+
+  it('이전 인스턴스 줄의 상세는 그 인스턴스를 실어 읽고 보류여도 판단 폼이 없으며 지금 줄의 상세와 섞이지 않는다', async () => {
+    const fake = installFakeOps(emptyList)
+    fake.incidents = { ...incidentsView([incidentRow()]), earlierTotal: 1, earlier: [earlierRow()] }
+    fake.incidentDetails.set('incident-1', incidentDetail())
+    // 사본의 held 는 늘 거짓이지만 화면은 그것에 기대지 않는다.
+    fake.earlierDetails.set('mw-0/incident-1', incidentDetail({ instanceId: 'mw-0', unitState: null, resolution: rework }))
+    open()
+    await userEvent.click(await screen.findByRole('button', { name: 'mw-0 incident-1 상세 보기' }))
+    const detail = await screen.findByRole('region', { name: '인시던트 상세' })
+    expect(within(detail).getByRole('heading', { level: 3 })).toHaveTextContent('incident-1 상세(이전 인스턴스 mw-0)')
+    expect(within(detail).getByText('이전 인스턴스의 사본입니다. 읽기 전용이며 판단하지 않습니다')).toBeInTheDocument()
+    expect(await within(detail).findByRole('region', { name: '사람의 판단' })).toHaveTextContent('사람이 판단함: kim, w20')
+    expect(field(within(detail).getByRole('region', { name: '관측' }), '단위의 지금 상태')).toBe('모름(실행 없음)')
+    expect(within(detail).queryByRole('form', { name: '운영자 판단' })).toBeNull()
+    expect(within(detail).queryByRole('button', { name: '재작업' })).toBeNull()
+    expect(calls(fake, 'GET', '/api/incidents/incident-1?instanceId=mw-0')).toHaveLength(1)
+
+    await userEvent.click(screen.getByRole('button', { name: 'incident-1 상세 보기' }))
+    expect(await within(detail).findByRole('form', { name: '운영자 판단' })).toBeInTheDocument()
+    expect(within(detail).getByRole('heading', { level: 3 })).toHaveTextContent(/^incident-1 상세$/)
+    expect(calls(fake, 'GET', '/api/incidents/incident-1?instanceId=mw-1').length).toBeGreaterThan(0)
+  })
+
+  it('고른 뒤 실행 호스트가 재기동하면 같은 id 의 새 인시던트가 아니라 고른 인스턴스의 사본을 읽고 판단 폼이 내려간다', async () => {
+    const fake = installFakeOps(emptyList)
+    fake.incidents = incidentsView([incidentRow()])
+    fake.incidentDetails.set('incident-1', incidentDetail())
+    const { rerender } = open()
+    const detail = await select('incident-1')
+    expect(await within(detail).findByRole('form', { name: '운영자 판단' })).toBeInTheDocument()
+
+    // 재기동: 새 인스턴스 mw-2 의 incident-1 은 다른 단위의 보류다. 앞 인스턴스의 것은 사본으로 남는다.
+    fake.incidents = {
+      ...incidentsView([incidentRow({ unitId: 'RACK-204.S01' })]),
+      instanceId: 'mw-2',
+      earlierTotal: 1,
+      earlier: [earlierRow({ instanceId: 'mw-1', resolution: null })],
+    }
+    fake.incidentDetails.set('incident-1', incidentDetail({ instanceId: 'mw-2', unitId: 'RACK-204.S01' }))
+    fake.earlierDetails.set('mw-1/incident-1', incidentDetail({ held: false, unitState: null }))
+    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
+    expect(await within(detail).findByText('이전 인스턴스의 사본입니다. 읽기 전용이며 판단하지 않습니다')).toBeInTheDocument()
+    expect(within(detail).getByRole('heading', { level: 3 })).toHaveTextContent('incident-1 상세(이전 인스턴스 mw-1)')
+    expect(field(within(detail).getByRole('region', { name: '관측' }), '단위')).toBe('rack-arrival')
+    expect(within(detail).queryByRole('form', { name: '운영자 판단' })).toBeNull()
+    const earlier = screen.getByRole('table', { name: '이전 인스턴스 인시던트 목록' })
+    expect(within(earlier).getAllByRole('row')[1]).toHaveClass('selected')
+    expect(within(screen.getByRole('table', { name: '인시던트 목록' })).getAllByRole('row')[1]).not.toHaveClass('selected')
+  })
+
+  it('판단은 상세의 instanceId 를 싣고 실행 호스트가 409 INSTANCE_MISMATCH 로 막으면 그 이름을 풀어 보인다', async () => {
+    const fake = installFakeOps(emptyList)
+    fake.incidents = incidentsView([incidentRow()])
+    fake.incidentDetails.set('incident-1', incidentDetail())
+    fake.answers.set(RESOLVE, {
+      status: 200,
+      body: holdResolved({
+        result: 'REJECTED',
+        outcome: null,
+        answer: null,
+        rejection: { status: 409, error: 'INSTANCE_MISMATCH', detail: '판단 요청의 인스턴스(mw-1)가 지금 인스턴스(mw-2)가 아니다' },
+      }),
+    })
+    open()
+    const detail = await select('incident-1')
+    await userEvent.type(await within(detail).findByLabelText('판단 사유'), '재작업')
+    await userEvent.click(within(detail).getByRole('button', { name: '재작업' }))
+    expect(await screen.findByRole('status', { name: '판단 결과' })).toHaveTextContent(
+      'exec-1/rack-arrival 재작업: 실행 호스트가 거부함(실행 호스트가 재기동해 인스턴스가 다름). 판단 요청의 인스턴스(mw-1)가 지금 인스턴스(mw-2)가 아니다',
+    )
+    expect(calls(fake, 'POST', RESOLVE).at(-1)!.body).toEqual({ decision: 'REWORK', instanceId: 'mw-1', reason: '재작업' })
+  })
+})
diff --git a/ui/src/components/IncidentSection.tsx b/ui/src/components/IncidentSection.tsx
index e3372c3..cde5822 100644
--- a/ui/src/components/IncidentSection.tsx
+++ b/ui/src/components/IncidentSection.tsx
@@ -2,6 +2,7 @@ import { useEffect, useState } from 'react'
 import { fetchIncident, resolveHold } from '../api'
 import type {
   Delivered,
+  EarlierIncidentRow,
   FaultDetail,
   HoldDecision,
   HoldResolveOutcome,
@@ -49,27 +50,34 @@ function judgement(row: IncidentRow): string {
  * 운영 영역의 «인시던트» 구역(S4a 스펙 §8.2·§8.3). 목록은 실행 호스트가 준 순서(최신부터)이고, 줄을 고르면 상세를 읽는다.
  * 관측(봉인 때의 근거)과 사람의 판단을 따로 보인다(운영 관리 화면 설계 제안 §9). 판단 버튼은 보류 중인 단위의 상세에만,
  * 운영자 모드에서만 있다.
+ *
+ * 재기동 뒤(S4b 스펙 T7·T8·T10): 앞 인스턴스들의 인시던트 사본을 «이전 인스턴스» 부분에 읽기 전용으로 둔다. 인시던트는
+ * (인스턴스, id)로 고르고 상세도 인스턴스를 실어 읽는다. `incident-N` 은 재기동하면 1부터 다시 세기 때문이다. 이전 인스턴스의
+ * 상세에는 판단 폼이 없다. 그 단위는 다시 지은 실행에서 새로 판단해야 한다. 지금 인스턴스의 판단은 상세의 `instanceId` 를
+ * 함께 보내고, 그 사이 재기동했으면 실행 호스트가 거부한다.
  */
 export function IncidentSection({ read, session, tick, onDecided }: Props) {
-  const [selected, setSelected] = useState<string | null>(null)
-  const [detail, setDetail] = useState<{ id: string; lookup: IncidentLookup | null; error: string | null } | null>(null)
+  const [selected, setSelected] = useState<Selection | null>(null)
+  const [detail, setDetail] = useState<{ key: string; lookup: IncidentLookup | null; error: string | null } | null>(null)
   const [busy, setBusy] = useState(false)
   const [last, setLast] = useState<{ what: string; sent: Delivered<HoldResolveOutcome> } | null>(null)
 
+  const selectedKey = selected === null ? null : keyOf(selected)
   useEffect(() => {
     if (selected === null) return
+    const key = keyOf(selected)
     let alive = true
-    fetchIncident(session, selected)
+    fetchIncident(session, selected.instanceId, selected.incidentId)
       .then((lookup) => {
-        if (alive) setDetail({ id: selected, lookup, error: null })
+        if (alive) setDetail({ key, lookup, error: null })
       })
       .catch((error: unknown) => {
         if (!alive) return
         // 못 읽으면 같은 인시던트의 직전 상세를 지우지 않는다.
         setDetail((previous) =>
-          previous !== null && previous.id === selected
+          previous !== null && previous.key === key
             ? { ...previous, error: message(error) }
-            : { id: selected, lookup: null, error: message(error) },
+            : { key, lookup: null, error: message(error) },
         )
       })
     return () => {
@@ -80,7 +88,7 @@ export function IncidentSection({ read, session, tick, onDecided }: Props) {
   const decide = (target: IncidentDetail, decision: HoldDecision, reason: string) => {
     const what = `${target.executionId}/${target.unitId} ${DECISION_LABEL[decision]}`
     setBusy(true)
-    resolveHold(session, target.executionId, target.unitId, decision, reason)
+    resolveHold(session, target, decision, reason)
       .then((sent) => setLast({ what, sent }))
       .finally(() => {
         setBusy(false)
@@ -91,7 +99,9 @@ export function IncidentSection({ read, session, tick, onDecided }: Props) {
   // 운영 서비스가 다른 모양을 주면(예: 시험 대역의 빈 배열) 목록이 없다. 없는 목록도 모름이다.
   const value = read.value !== null && Array.isArray(read.value.incidents) ? read.value : null
   const { error } = read
-  const shown = detail !== null && detail.id === selected ? detail : null
+  const shown = detail !== null && detail.key === selectedKey ? detail : null
+  // 고른 인시던트가 지금 인스턴스의 것이 아니면(이전 인스턴스 줄을 골랐거나 고른 뒤 재기동) 읽기 전용이다.
+  const earlier = selected !== null && (value === null || selected.instanceId !== value.instanceId)
 
   return (
     <section aria-label="인시던트">
@@ -108,13 +118,37 @@ export function IncidentSection({ read, session, tick, onDecided }: Props) {
           {value.incidents.length === 0 ? (
             <p>인시던트가 없습니다</p>
           ) : (
-            <IncidentTable rows={value.incidents} selected={selected} onSelect={setSelected} />
+            <IncidentTable
+              rows={value.incidents.map((row) => ({ ...row, instanceId: value.instanceId }))}
+              earlier={false}
+              selectedKey={selectedKey}
+              onSelect={setSelected}
+            />
+          )}
+          {Array.isArray(value.earlier) && (
+            <section aria-label="이전 인스턴스">
+              <h3>이전 인스턴스</h3>
+              {value.earlier.length === 0 ? (
+                <p>이전 인스턴스의 인시던트가 없습니다</p>
+              ) : (
+                <>
+                  <p>
+                    재기동 앞 인스턴스의 인시던트 {value.earlierTotal ?? value.earlier.length}건 가운데 최신{' '}
+                    {value.earlier.length}건. 읽기 전용이며 여기서 판단하지 않습니다
+                  </p>
+                  <IncidentTable rows={value.earlier} earlier selectedKey={selectedKey} onSelect={setSelected} />
+                </>
+              )}
+            </section>
           )}
         </>
       )}
       {selected !== null && (
         <section aria-label="인시던트 상세">
-          <h3>{selected} 상세</h3>
+          <h3>
+            {selected.incidentId} 상세{earlier && `(이전 인스턴스 ${selected.instanceId})`}
+          </h3>
+          {earlier && <p>이전 인스턴스의 사본입니다. 읽기 전용이며 판단하지 않습니다</p>}
           {shown === null || (shown.lookup === null && shown.error === null) ? (
             <p>상세를 읽는 중입니다</p>
           ) : shown.lookup === null ? (
@@ -127,6 +161,7 @@ export function IncidentSection({ read, session, tick, onDecided }: Props) {
               <Detail
                 detail={shown.lookup.detail}
                 session={session}
+                decidable={!earlier}
                 busy={busy}
                 onDecide={(decision, reason) => {
                   if (shown.lookup?.kind === 'found') decide(shown.lookup.detail, decision, reason)
@@ -140,19 +175,34 @@ export function IncidentSection({ read, session, tick, onDecided }: Props) {
   )
 }
 
+/** 고른 인시던트. 재기동하면 id 를 다시 세므로 인스턴스와 짝을 짓는다. */
+interface Selection {
+  instanceId: string
+  incidentId: string
+}
+
+const keyOf = (selection: Selection) => `${selection.instanceId}/${selection.incidentId}`
+
+/**
+ * 인시던트 표. 지금 인스턴스 표는 S4a 그대로이고, 이전 인스턴스 표([earlier])는 앞에 인스턴스 열을 두며 보류 열이 없다(사본은
+ * 보류 중이 아니다). 이전 인스턴스 줄의 상세 버튼 이름에는 인스턴스를 넣는다. 같은 `incident-N` 이 두 표에 있을 수 있다.
+ */
 function IncidentTable({
   rows,
-  selected,
+  earlier,
+  selectedKey,
   onSelect,
 }: {
-  rows: IncidentRow[]
-  selected: string | null
-  onSelect: (incidentId: string) => void
+  rows: EarlierIncidentRow[]
+  earlier: boolean
+  selectedKey: string | null
+  onSelect: (selection: Selection) => void
 }) {
   return (
-    <table aria-label="인시던트 목록">
+    <table aria-label={earlier ? '이전 인스턴스 인시던트 목록' : '인시던트 목록'}>
       <thead>
         <tr>
+          {earlier && <th>인스턴스</th>}
           <th>인시던트</th>
           <th>발생 시각</th>
           <th>기체</th>
@@ -163,17 +213,25 @@ function IncidentTable({
           <th>현장 설정 버전</th>
           <th>임무 버전</th>
           <th>판단</th>
-          <th>보류</th>
+          {!earlier && <th>보류</th>}
         </tr>
       </thead>
       <tbody>
         {rows.map((row) => (
           <tr
-            key={row.incidentId}
-            className={[row.incidentId === selected ? 'selected' : '', row.held ? 'held' : ''].join(' ').trim() || undefined}
+            key={keyOf(row)}
+            className={
+              [keyOf(row) === selectedKey ? 'selected' : '', !earlier && row.held ? 'held' : ''].join(' ').trim() ||
+              undefined
+            }
           >
+            {earlier && <td>{row.instanceId}</td>}
             <td>
-              <button className="link" aria-label={`${row.incidentId} 상세 보기`} onClick={() => onSelect(row.incidentId)}>
+              <button
+                className="link"
+                aria-label={earlier ? `${row.instanceId} ${row.incidentId} 상세 보기` : `${row.incidentId} 상세 보기`}
+                onClick={() => onSelect({ instanceId: row.instanceId, incidentId: row.incidentId })}
+              >
                 {row.incidentId}
               </button>
             </td>
@@ -186,7 +244,7 @@ function IncidentTable({
             <td>{settingsVersion(row.siteSettingsVersion)}</td>
             <td>{missionVersion(row.missionVersion)}</td>
             <td>{judgement(row)}</td>
-            <td>{row.held ? <strong>보류 중</strong> : '-'}</td>
+            {!earlier && <td>{row.held ? <strong>보류 중</strong> : '-'}</td>}
           </tr>
         ))}
       </tbody>
@@ -197,6 +255,8 @@ function IncidentTable({
 interface DetailProps {
   detail: IncidentDetail
   session: Session
+  /** 지금 인스턴스의 상세일 때만 참이다. 거짓이면 보류여도 판단 폼을 두지 않는다. */
+  decidable: boolean
   busy: boolean
   onDecide: (decision: HoldDecision, reason: string) => void
 }
@@ -205,7 +265,7 @@ interface DetailProps {
  * 인시던트 상세(S4a 스펙 §8.2). 판단은 «사람의 판단» 에, 봉인 때의 근거는 «관측» 에 따로 둔다. 확인 결과는 코드 이름 그대로
  * 보인다. `NOT_REQUESTED` 를 «설비 확인 안 함» 으로 풀면, 설비 슬롯을 실제로 읽은 인시던트를 틀리게 읽힌다(S4a JSON 계약 §4).
  */
-function Detail({ detail, session, busy, onDecide }: DetailProps) {
+function Detail({ detail, session, decidable, busy, onDecide }: DetailProps) {
   const { intent, step } = detail
   const position = step.at === 0 ? `계획에 없음(계획 ${step.plan.length}개)` : `${step.at}/${step.plan.length}`
   return (
@@ -321,7 +381,7 @@ function Detail({ detail, session, busy, onDecide }: DetailProps) {
           </table>
         )}
       </section>
-      {detail.held && <DecisionForm key={detail.incidentId} detail={detail} session={session} busy={busy} onDecide={onDecide} />}
+      {decidable && detail.held && <DecisionForm key={detail.incidentId} detail={detail} session={session} busy={busy} onDecide={onDecide} />}
     </>
   )
 }
diff --git a/ui/src/components/OperationsArea.tsx b/ui/src/components/OperationsArea.tsx
index cc64555..3dfac0d 100644
--- a/ui/src/components/OperationsArea.tsx
+++ b/ui/src/components/OperationsArea.tsx
@@ -1,5 +1,13 @@
 import { useEffect, useRef, useState } from 'react'
-import { checkEligibility, fetchCell, fetchExecutions, fetchIncidents, submitJobOrder, writeCellSignal } from '../api'
+import {
+  checkEligibility,
+  fetchCell,
+  fetchExecutions,
+  fetchIncidents,
+  fetchJobResponses,
+  submitJobOrder,
+  writeCellSignal,
+} from '../api'
 import type {
   CellView,
   Delivered,
@@ -7,6 +15,7 @@ import type {
   IncidentsView,
   JobOrderForm,
   JobOrderOutcome,
+  JobResponsesView,
   Session,
   SignalWriteOutcome,
 } from '../api'
@@ -21,6 +30,7 @@ import type { HostRead } from './ExecutionList'
 import { IncidentSection } from './IncidentSection'
 import { JobOrderFormView } from './JobOrderFormView'
 import { JobOrderNotice } from './JobOrderNotice'
+import { JobResponseLog } from './JobResponseLog'
 import { SignalNotice } from './SignalNotice'
 
 interface Props {
@@ -35,7 +45,7 @@ const message = (error: unknown) => (error instanceof Error ? error.message : St
 
 /**
  * «운영» 영역(S3a 스펙 §9). 작업 지시 폼, 기체별 배정 가능 표, 실행 목록, 인시던트와 운영자 판단(S4a 스펙 §8.2·§8.3),
- * 셀 대역 표시와 신호 조작(S3b 스펙 §8).
+ * 셀 대역 표시와 신호 조작(S3b 스펙 §8), 작업 응답 송신 기록(S4b 스펙 §8).
  *
  * 실행 목록·인시던트·셀·배정 가능은 실행 호스트를 거친다. 그래서 App 의 다섯 조회(`Promise.all`)와 따로, 이 영역이 열려 있을 때만
  * 읽는다(S3a 스펙 §9.3). 호스트가 멈춰도 다섯 조회가 직전 값이 되지 않게 하기 위해서다. 셋은 서로도 따로 실패하고, 못 읽으면
@@ -51,6 +61,9 @@ export function OperationsArea({ session, onChanged }: Props) {
   const [executions, setExecutions] = useState<HostRead<ExecutionsView>>({ value: null, error: null })
   const [cell, setCell] = useState<HostRead<CellView>>({ value: null, error: null })
   const [incidents, setIncidents] = useState<HostRead<IncidentsView>>({ value: null, error: null })
+  const [responses, setResponses] = useState<HostRead<JobResponsesView>>({ value: null, error: null })
+  // 송신 기록에서 고른 작업 지시. null 이면 전체다.
+  const [responseFilter, setResponseFilter] = useState<string | null>(null)
   const [eligibility, setEligibility] = useState<EligibilityRead>(NO_ELIGIBILITY)
   const [draft, setDraft] = useState<JobOrderDraft>(EMPTY_DRAFT)
   const [problem, setProblem] = useState<string | null>(null)
@@ -105,6 +118,31 @@ export function OperationsArea({ session, onChanged }: Props) {
     }
   }, [session, tick])
 
+  // 송신 기록은 고른 작업 지시가 바뀌어도 다시 읽는다. 앞 작업 지시의 늦은 답은 버린다.
+  useEffect(() => {
+    let alive = true
+    fetchJobResponses(session, responseFilter)
+      .then((value) => {
+        if (alive) setResponses({ value, error: null })
+      })
+      .catch((error: unknown) => {
+        if (alive) setResponses((previous) => ({ ...previous, error: message(error) }))
+      })
+    return () => {
+      alive = false
+    }
+  }, [session, tick, responseFilter])
+
+  const selectResponses = (jobOrderId: string | null) => {
+    // 다른 작업 지시의 행을 고른 작업 지시의 직전 값으로 보이지 않는다.
+    setResponses({ value: null, error: null })
+    setResponseFilter(jobOrderId)
+  }
+  const responseJobOrders = [
+    ...[...(executions.value?.executions ?? [])].reverse().map((execution) => execution.jobOrderId),
+    ...(responses.value?.responses ?? []).map((row) => row.jobOrderId),
+  ]
+
   const built = buildForm(draft, cell.value?.cell ?? null)
   // 폼을 글자로 비교한다. 셀을 다시 읽을 때마다 객체가 바뀌어도 같은 폼이면 다시 묻지 않는다.
   const formKey = built.kind === 'form' ? JSON.stringify(built.form) : null
@@ -210,6 +248,12 @@ export function OperationsArea({ session, onChanged }: Props) {
         {signalLast !== null && <SignalNotice what={signalLast.what} sent={signalLast.sent} />}
         <CellBand read={cell} busy={signalBusy} onWrite={writeSignal} />
       </section>
+      <JobResponseLog
+        read={responses}
+        jobOrderIds={responseJobOrders}
+        selected={responseFilter}
+        onSelect={selectResponses}
+      />
     </>
   )
 }
diff --git a/ui/src/labels.ts b/ui/src/labels.ts
index fb946a2..6c0d133 100644
--- a/ui/src/labels.ts
+++ b/ui/src/labels.ts
@@ -4,6 +4,7 @@ import type {
   FaultKind,
   HoldDecision,
   HostSubmitResult,
+  JobResponseDisposition,
   MockRunFailure,
   MockRunView,
   Owner,
@@ -90,6 +91,9 @@ export const KIND_LABEL: Record<string, string> = {
   UNSUPPORTED_MEDIA_TYPE: 'JSON 이 아닌 본문',
   FAULT_BAD_REQUEST: '장애 주입 본문 오류',
   RESOLVE_BAD_REQUEST: '판단 본문 오류',
+  // 재기동 뒤 판단(S4b 계약 H5·H7). 운영 서비스의 송신 기록 사전 거부도 둔다.
+  INSTANCE_MISMATCH: '실행 호스트가 재기동해 인스턴스가 다름',
+  JOB_RESPONSE_BAD_REQUEST: '송신 기록 요청 오류',
 }
 
 /** «시운전» 칸(P2·S1d 스펙 §8.5). 연결 칸과 합치지 않는다. */
@@ -168,6 +172,12 @@ export const FAULT_KIND_LABEL: Record<FaultKind, string> = {
   CONNECTION: '연결 상태',
 }
 
+/** 송신 기록의 처분(S4b 스펙 T6, §8). */
+export const DISPOSITION_LABEL: Record<JobResponseDisposition, string> = {
+  SENT: '송신',
+  RESTART_DUPLICATE: '재기동 중복(송신 안 함)',
+}
+
 /** 운영자 판단 둘(S4a 스펙 §8.3). 버튼 이름이기도 하다. */
 export const DECISION_LABEL: Record<HoldDecision, string> = {
   CONFIRM_DONE: '완료 확인',
diff --git a/ui/src/styles.css b/ui/src/styles.css
index 5f2d2f6..7883fa5 100644
--- a/ui/src/styles.css
+++ b/ui/src/styles.css
@@ -19,3 +19,6 @@ tr.held { background: #fbeee6; }
 tr.held strong { color: #b23a1d; }
 .asserted { border-left: 4px double #2b3f6b; padding-left: 8px; }
 .observed h4 { margin-bottom: 4px; }
+.restore { border-left: 4px solid #b26b1d; padding-left: 8px; margin: 8px 0; }
+.restored { color: #6f6f6f; }
+tr.duplicate { color: #6f6f6f; }
diff --git a/ui/src/testing/fakeOps.ts b/ui/src/testing/fakeOps.ts
index da9f71c..08356f7 100644
--- a/ui/src/testing/fakeOps.ts
+++ b/ui/src/testing/fakeOps.ts
@@ -5,6 +5,7 @@ import type {
   CellSignal,
   CellView,
   DraftView,
+  EarlierIncidentRow,
   EligibilityView,
   ExecutionsView,
   FaultInjectionOutcome,
@@ -14,12 +15,15 @@ import type {
   IncidentDetail,
   IncidentRow,
   IncidentsView,
+  JobResponseLogRow,
+  JobResponsesView,
   MissionOverview,
   MissionTemplates,
   MockRunView,
   OperationOutcome,
   PreRejection,
   ProfileListView,
+  RestoreRow,
   Revision,
   SiteSettingsView,
   RevisionView,
@@ -50,14 +54,20 @@ export interface FakeOps {
   /** 임무 개요와 템플릿(S3b). 운영 서비스가 호스트 본문을 그대로 넘기는 모양이다. */
   mission: MissionOverview
   templates: MissionTemplates
-  /** 인시던트 목록과 상세(S4a). 상세 맵에 없는 id 는 404 `INCIDENT_NOT_FOUND` 다. */
+  /**
+   * 인시던트 목록과 상세(S4a·S4b). 상세의 `instanceId` 쿼리가 목록의 인스턴스면 [incidentDetails](id 키)에서, 다른
+   * 인스턴스면 [earlierDetails](`<instanceId>/<incidentId>` 키)에서 찾는다. 없으면 404 `INCIDENT_NOT_FOUND` 다.
+   */
   incidents: IncidentsView
   incidentDetails: Map<string, IncidentDetail>
+  earlierDetails: Map<string, IncidentDetail>
+  /** 송신 기록(S4b). `jobOrderId` 쿼리가 있으면 실행 호스트처럼 그 작업 지시 행만 돌려준다. */
+  jobResponses: JobResponsesView
   /** `POST /api/job-orders/eligibility` 의 답. 폼 거부(400)를 만들려면 [eligibilityStatus] 와 본문을 바꾼다. */
   eligibility: EligibilityView | PreRejection
   eligibilityStatus: number
   /**
-   * 여기 든 경로의 GET 은 503 이다. 운영 서비스의 일부 읽기만 실패하는 경우를 만든다. 실행 호스트를 거치는 경로는 운영
+   * 여기 든 경로(쿼리를 뺀 것)의 GET 은 503 이다. 운영 서비스의 일부 읽기만 실패하는 경우를 만든다. 실행 호스트를 거치는 경로는 운영
    * 서비스처럼 `HOST_SILENT` 본문을 싣는다. 배정 가능 판정은 POST 지만 읽기이므로 여기 들면 503 이다.
    */
   failing: Set<string>
@@ -70,7 +80,7 @@ export const MISSION_PATH = '/api/missions/PrepareSequencedRack'
 export const TEMPLATES_PATH = '/api/missions/templates/PrepareSequencedRack'
 
 /** 실행 호스트를 거치는 경로. 503 일 때 운영 서비스가 `HOST_SILENT` 를 싣는다(S3a JSON 계약 §9.4, S3b JSON 계약 §10.3). */
-const HOST_PATHS = new Set(['/api/executions', '/api/cell', '/api/incidents', MISSION_PATH, TEMPLATES_PATH])
+const HOST_PATHS = new Set(['/api/executions', '/api/cell', '/api/incidents', '/api/job-responses', MISSION_PATH, TEMPLATES_PATH])
 const INCIDENT_PREFIX = '/api/incidents/'
 const ELIGIBILITY_PATH = '/api/job-orders/eligibility'
 
@@ -438,6 +448,61 @@ export function incidentDetail(partial: Partial<IncidentDetail> = {}): IncidentD
   }
 }
 
+/** 이전 인스턴스 사본 한 줄(S4b 계약 H3). 기본은 [incidentRow] 기본을 인스턴스 mw-0 에서 재작업 판단한 것이다. */
+export function earlierRow(partial: Partial<EarlierIncidentRow> = {}): EarlierIncidentRow {
+  return {
+    instanceId: 'mw-0',
+    ...incidentRow({
+      held: false,
+      resolution: { decision: 'REWORK', at: 'h20', wallClockAt: 'w20', decidedBy: { id: 'kim', kind: 'PERSON' } },
+    }),
+    ...partial,
+  }
+}
+
+/** 복원 보고 한 행(S4b 계약 H1). 기본은 mw-0 의 exec-3 을 exec-1 로 다시 지은 것이다. */
+export function restoreRow(partial: Partial<RestoreRow> = {}): RestoreRow {
+  return {
+    jobOrderId: 'JO-20261009-aaaaaaaa',
+    robotId: 'humanoid-01',
+    previousInstanceId: 'mw-0',
+    previousExecutionId: 'exec-3',
+    result: 'RESTORED',
+    executionId: 'exec-1',
+    reason: null,
+    ...partial,
+  }
+}
+
+/** 송신 기록(S4b 계약 H6). 기본은 행이 없는 지금 인스턴스 mw-1 이다. */
+export function jobResponsesView(responses: JobResponseLogRow[] = []): JobResponsesView {
+  return { instanceId: 'mw-1', total: responses.length, responses }
+}
+
+/** 송신 기록 한 행(S4b 계약 H6). 기본은 운영자 보류에 선 PARTIAL 응답을 보낸 것이다. */
+export function jobResponseRow(partial: Partial<JobResponseLogRow> = {}): JobResponseLogRow {
+  return {
+    instanceId: 'mw-1',
+    jobResponseId: 'resp-1',
+    jobOrderId: 'JO-20261009-aaaaaaaa',
+    executionId: 'exec-1',
+    version: 1,
+    physicalState: 'PARTIAL',
+    requiredEvidence: 'E2',
+    reachedEvidence: 'E0',
+    completedUnits: [],
+    unverifiedUnits: [],
+    inDoubtUnits: [],
+    incompleteUnits: ['rack-arrival'],
+    operatorRequired: true,
+    residualHold: 'HOLD_KIND_UNSPECIFIED',
+    blockedBy: [],
+    disposition: 'SENT',
+    recordedAt: 'w30',
+    ...partial,
+  }
+}
+
 /** 장애 주입 200 본문(S4a JSON 계약 §9.4). 기본은 humanoid-01 의 스킬 실패를 현장이 받아들인 것이다. */
 export function faultInjected(partial: Partial<FaultInjectionOutcome> = {}): FaultInjectionOutcome {
   return {
@@ -499,6 +564,8 @@ export function installFakeOps(
     templates: missionTemplates(),
     incidents: incidentsView(),
     incidentDetails: new Map(),
+    earlierDetails: new Map(),
+    jobResponses: jobResponsesView(),
     eligibility: eligibilityView(),
     eligibilityStatus: 200,
     failing: new Set(),
@@ -516,18 +583,31 @@ export function installFakeOps(
       }
       const method = init?.method ?? 'GET'
       fake.calls.push({ method, url, headers, body: init?.body ? JSON.parse(init.body as string) : undefined })
-      if (fake.failing.has(url) && (method === 'GET' || url === ELIGIBILITY_PATH)) {
-        const body = HOST_PATHS.has(url) || url.startsWith(INCIDENT_PREFIX)
+      const [path, query] = url.split('?')
+      const params = new URLSearchParams(query ?? '')
+      if (fake.failing.has(path) && (method === 'GET' || url === ELIGIBILITY_PATH)) {
+        const body = HOST_PATHS.has(path) || path.startsWith(INCIDENT_PREFIX)
           ? JSON.stringify({ error: 'HOST_SILENT', detail: '실행 호스트가 답하지 않는다: 응답 없음: ConnectException' })
           : ''
         return new Response(body, { status: 503 })
       }
-      if (method === 'GET' && url.startsWith(INCIDENT_PREFIX)) {
-        const found = fake.incidentDetails.get(decodeURIComponent(url.slice(INCIDENT_PREFIX.length)))
+      if (method === 'GET' && path.startsWith(INCIDENT_PREFIX)) {
+        const incidentId = decodeURIComponent(path.slice(INCIDENT_PREFIX.length))
+        const instanceId = params.get('instanceId')
+        const found =
+          instanceId === null || instanceId === fake.incidents.instanceId
+            ? fake.incidentDetails.get(incidentId)
+            : fake.earlierDetails.get(`${instanceId}/${incidentId}`)
         return found === undefined
           ? new Response(JSON.stringify({ error: 'INCIDENT_NOT_FOUND', detail: '인시던트가 없다' }), { status: 404 })
           : new Response(JSON.stringify(found), { status: 200 })
       }
+      if (method === 'GET' && path === '/api/job-responses') {
+        const jobOrderId = params.get('jobOrderId')
+        const rows = fake.jobResponses.responses.filter((row) => jobOrderId === null || row.jobOrderId === jobOrderId)
+        const body = jobOrderId === null ? fake.jobResponses : { ...fake.jobResponses, total: rows.length, responses: rows }
+        return new Response(JSON.stringify(body), { status: 200 })
+      }
       if (method === 'GET') {
         const body =
           url === '/api/robots'
```

- [ ] **Step 3: 커밋 1/2 `feat(ui): 재기동 복원 보고 띠, 이전 인스턴스 인시던트, 작업 응답 송신 기록 구역`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add ui/src/components/ExecutionRestore.test.tsx ui/src/components/JobResponseLog.test.tsx ui/src/components/JobResponseLog.tsx ui/src/api.ts ui/src/components/ExecutionList.tsx ui/src/components/IncidentSection.test.tsx ui/src/components/IncidentSection.tsx ui/src/components/OperationsArea.tsx ui/src/labels.ts ui/src/styles.css ui/src/testing/fakeOps.ts && git commit -F - <<'EOF'
feat(ui): 재기동 복원 보고 띠, 이전 인스턴스 인시던트, 작업 응답 송신 기록 구역

- 실행 목록 위 복원 보고 띠(재기동: 이전 인스턴스의 실행 n건을 다시 지었습니다, 미룬 실행·포기한 실행과 사유), 다시 지은 실행 행에 (이전 exec-k)
- 인시던트를 (인스턴스, id)로 고르고 상세를 instanceId 질의로 읽음, 고른 뒤 재기동하면 고른 인스턴스의 사본을 계속 읽음
- 인시던트 구역의 이전 인스턴스 부분: 인스턴스 열, 보류 열 없음, 읽기 전용, 상세에 판단 폼 없음
- 판단 요청 본문에 상세의 instanceId, INSTANCE_MISMATCH 거부 이름 풀이
- 운영 영역 끝에 작업 응답 송신 기록 구역: 작업 지시 고르기(전체 포함), 송신·재기동 중복 처분, 재기동 중복 건수, 불통 시 직전 값
- 시험 13개 추가(인시던트 다섯, 복원 띠 셋, 송신 기록 다섯), 기존 상세 URL·판단 본문 단언을 instanceId 로 수정

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 4: (커밋 2/2) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task4b.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task4b.patch"
```

```diff
diff --git a/ui/e2e/lifecycle.spec.ts b/ui/e2e/lifecycle.spec.ts
index 416398e..c525913 100644
--- a/ui/e2e/lifecycle.spec.ts
+++ b/ui/e2e/lifecycle.spec.ts
@@ -17,6 +17,7 @@ import { fileURLToPath } from 'node:url'
  * 이어서 S3b 의 화면 쪽(S3b 스펙 §3). 엔지니어 모드로 «임무·정책» 영역에서 데이터 정의 템플릿을 불러와 초안 저장 → 검증 → 모의
  * 실행 → 활성화(사유)하면 버전 이력에 «버전 1 (활성)» 이 보인다. 운영 영역의 셀 대역 신호 표에서 rack_present 를 켜면 신호 조작
  * 결과와 신호 값이 보인다.
+ * S4b 의 화면 쪽(S4b 스펙 T11)은 운영자 보류 단계 뒤에 송신 기록 구역이 그 작업 지시의 송신 행을 보이는 것 하나다.
  * S3c 의 화면 쪽(S3c 스펙 §3)은 S2 단계 뒤다. 엔지니어 모드로 stallWindow 를 바꾸면 버전 3 이력 행이 보이고 «실행 호스트 반영»
  * 이 버전 3 이 된다.
  * registry 를 멈추는 것은 맨 끝이다. 그 뒤로는 조작이 registry 에 닿지 않는다.
@@ -298,6 +299,16 @@ test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 reg
   await expect(deadline.getByRole('cell').nth(9)).toHaveText('판단됨')
   await expect(deadline.getByRole('cell').nth(10)).toHaveText('-')
 
+  // 작업 응답 송신 기록(S4b 스펙 T10·T11). 보류 작업 지시가 낸 응답이 송신 행으로 남는다. 재기동하지 않았으므로 재기동 중복은
+  // 없다. 재기동 단계는 통합 시험(RestartRecoveryTest)이 본다.
+  const holdJobOrder = (await holdRun.getByRole('cell').nth(1).textContent())!.trim()
+  const responseLog = page.getByRole('region', { name: '작업 응답 송신 기록' })
+  await responseLog.getByLabel('송신 기록의 작업 지시').selectOption(holdJobOrder)
+  const sentRows = responseLog.getByRole('table', { name: '송신 기록 목록' }).getByRole('row').filter({ hasText: holdJobOrder })
+  await expect(sentRows.filter({ hasText: 'PHYSICALLY_DONE' })).toContainText('송신')
+  await expect(sentRows.filter({ hasText: '재기동 중복' })).toHaveCount(0)
+  await expect(responseLog).toContainText('그 가운데 재기동 중복 0건')
+
   // registry 를 멈춘다. 런처(registry 와 mimic 이 든 프로세스)를 끈다.
   const pidFile = fileURLToPath(new URL('../../build/site.pid', import.meta.url))
   process.kill(Number(readFileSync(pidFile, 'utf8')))
```

- [ ] **Step 5: 커밋 2/2 `test(ui): Playwright 생애주기의 작업 응답 송신 기록 확인 단계`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add ui/e2e/lifecycle.spec.ts && git commit -F - <<'EOF'
test(ui): Playwright 생애주기의 작업 응답 송신 기록 확인 단계

- 운영자 보류 단계 뒤 보류 작업 지시를 송신 기록 구역에서 골라 PHYSICALLY_DONE 줄의 송신 처분과 재기동 중복 0건 확인
- 재기동 단계는 넣지 않음(스펙 T11), S4a 판단 단계는 화면이 상세의 instanceId 를 실어 그대로 통과

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 6: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && cd ui && npm ci && npm test && npm run lint && npx tsc -b && npm run build
```
Expected: vitest 151 통과, lint·tsc·build 종료 0. Playwright 는 Task 6 의 새 클론에서 돌린다.

- [ ] **Step 7: 묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s4b-cmp.sh" --at 70da136 ui/src/components/ExecutionRestore.test.tsx ui/src/components/JobResponseLog.test.tsx ui/src/components/JobResponseLog.tsx ui/src/api.ts ui/src/components/ExecutionList.tsx ui/src/components/IncidentSection.test.tsx ui/src/components/IncidentSection.tsx ui/src/components/OperationsArea.tsx ui/src/labels.ts ui/src/styles.css ui/src/testing/fakeOps.ts ui/e2e/lifecycle.spec.ts
```
Expected: 12개 모두 `같음`(이 Task 의 마지막 스파이크 커밋 `70da136` 의 파일과 바이트 대조, 뒤 Task 가 같은 파일을 다시 고칠 수 있어 HEAD 가 아니다).

### Task 5: site 태스크 갱신 로그와 통합 시험

**Files:**
- Create: `e2e/src/test/kotlin/dev/picasso/ops/e2e/RestartRecoveryTest.kt`
- Modify: `e2e/src/test/kotlin/dev/picasso/ops/e2e/FaultIncidentTest.kt`, `site/src/main/kotlin/dev/picasso/ops/site/Site.kt`

- [ ] **Step 1: (커밋 1/4) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5a.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5a.patch"
```

```diff
diff --git a/e2e/src/test/kotlin/dev/picasso/ops/e2e/FaultIncidentTest.kt b/e2e/src/test/kotlin/dev/picasso/ops/e2e/FaultIncidentTest.kt
index e943e1c..24e2748 100644
--- a/e2e/src/test/kotlin/dev/picasso/ops/e2e/FaultIncidentTest.kt
+++ b/e2e/src/test/kotlin/dev/picasso/ops/e2e/FaultIncidentTest.kt
@@ -256,7 +256,7 @@ class FaultIncidentTest {
     @Test
     @Order(5)
     fun `단계 4 운영자가 사유를 적어 재작업을 판단하면 대기가 새로 시작되고 신호를 켜면 끝나며 판단자와 사유가 남는다`() {
-        val reply = resolve(holdExecution, WAIT_UNIT, "REWORK", "랙 재배치 뒤 재작업")
+        val reply = resolve(holdExecution, WAIT_UNIT, "REWORK", "랙 재배치 뒤 재작업", instanceId = detailInstance(firstHold))
         assertEquals(200, reply.status, "${reply.body}")
         val body = reply.body!!
         assertEquals(listOf("SUCCEEDED", "Resolved", "REWORK"), listOf(body["result"], body["outcome"], body["decision"]).map { it.asText() }, "$body")
@@ -302,7 +302,7 @@ class FaultIncidentTest {
         // 앞 보류는 판단을 든 채 보류가 아니다.
         assertEquals(false, rows.single { it["incidentId"].asText() == firstHold }["held"].asBoolean(), "$rows")
 
-        val reply = resolve(executionId, WAIT_UNIT, "CONFIRM_DONE", "현장 육안으로 랙 도착 확인")
+        val reply = resolve(executionId, WAIT_UNIT, "CONFIRM_DONE", "현장 육안으로 랙 도착 확인", instanceId = detailInstance(hold["incidentId"].asText()))
         assertEquals("SUCCEEDED" to "Resolved", reply.body!!["result"].asText() to reply.body["outcome"].asText(), "${reply.body}")
         assertEquals("DONE", unit(executionId, WAIT_UNIT)["state"].asText(), "${driver.execution(executionId)}")
 
@@ -326,7 +326,7 @@ class FaultIncidentTest {
 
         val engineerResolve = resolve(holdExecution, WAIT_UNIT, "REWORK", "모드 확인", mode = "engineer")
         assertEquals(403 to "MODE_NOT_ALLOWED", engineerResolve.status to engineerResolve.body!!["error"].asText(), "${engineerResolve.body}")
-        val noReason = stack.send("POST", "/api/executions/$holdExecution/units/$WAIT_UNIT/resolve", "operator", body = """{"decision":"REWORK"}""")
+        val noReason = stack.send("POST", "/api/executions/$holdExecution/units/$WAIT_UNIT/resolve", "operator", body = """{"decision":"REWORK","instanceId":"${incidents()["instanceId"].asText()}"}""")
         assertEquals(400 to "REASON_REQUIRED", noReason.status to noReason.body!!["error"].asText(), "${noReason.body}")
         val operatorFault = stack.send("POST", "/api/faults", "operator", body = """{"robotId":"$HUMANOID","kind":"SKILL_EXECUTION_FAILED","reason":"모드 확인"}""")
         assertEquals(403 to "MODE_NOT_ALLOWED", operatorFault.status to operatorFault.body!!["error"].asText(), "${operatorFault.body}")
@@ -395,8 +395,23 @@ class FaultIncidentTest {
     private fun connection(robotId: String, state: String, reason: String): JsonNode =
         engineer("/api/faults", """{"robotId":"$robotId","kind":"CONNECTION","state":"$state","reason":"$reason"}""")
 
-    private fun resolve(executionId: String, unitId: String, decision: String, reason: String, mode: String = "operator"): E2eStack.Reply =
-        stack.send("POST", "/api/executions/$executionId/units/$unitId/resolve", mode, body = """{"decision":"$decision","reason":"$reason"}""")
+    /**
+     * 판단 요청. 본문의 `instanceId` 는 화면처럼 인시던트 상세의 값이다(S4b JSON 계약 O3). 보류가 아닌 단위를 판단하는 경우처럼
+     * 고른 인시던트가 없으면 목록의 지금 인스턴스다.
+     */
+    private fun resolve(
+        executionId: String,
+        unitId: String,
+        decision: String,
+        reason: String,
+        mode: String = "operator",
+        instanceId: String = incidents()["instanceId"].asText(),
+    ): E2eStack.Reply = stack.send(
+        "POST", "/api/executions/$executionId/units/$unitId/resolve", mode,
+        body = """{"decision":"$decision","instanceId":"$instanceId","reason":"$reason"}""",
+    )
+
+    private fun detailInstance(incidentId: String): String = stack.get("/api/incidents/$incidentId")["instanceId"].asText()
 
     private fun signal(value: String) {
         val written = stack.send("POST", "/api/cell/signals/rack_present", "operator", body = """{"value":"$value"}""")
```

- [ ] **Step 2: 커밋 1/4 `test(e2e): S4a 판단 요청의 instanceId 싣기`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add e2e/src/test/kotlin/dev/picasso/ops/e2e/FaultIncidentTest.kt && git commit -F - <<'EOF'
test(e2e): S4a 판단 요청의 instanceId 싣기

- 보류 판단은 그 인시던트 상세의 instanceId, 보류 아닌 단위의 판단은 목록의 지금 인스턴스
- 사유 없는 사전 거부 확인도 instanceId 를 실어 REASON_REQUIRED 판정 유지
- 단언은 그대로

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 3: (커밋 2/4) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b.patch"
```

```diff
diff --git a/site/src/main/kotlin/dev/picasso/ops/site/Site.kt b/site/src/main/kotlin/dev/picasso/ops/site/Site.kt
index 68fe7d7..084d0e7 100644
--- a/site/src/main/kotlin/dev/picasso/ops/site/Site.kt
+++ b/site/src/main/kotlin/dev/picasso/ops/site/Site.kt
@@ -80,6 +80,17 @@ class Site private constructor(
         mimic.server.exclusive { instance.knownSiteNames = siteNames }
     }
 
+    /**
+     * 이 기체가 호스팅하는 태스크마다 갱신 로그의 상태 이름(적은 순서, S4b 스펙 T5). 통합 시험이 재기동 앞뒤로 새 명령이 나가지
+     * 않았는지 대조할 때 쓴다. mimic 은 같은 태스크 id·리비전의 `StartTask` 에 기존 태스크를 돌려주므로, 새 명령이 나가면 새
+     * 태스크 id 나 둘째 `ACCEPTED` 로 보인다. mimic 은 RPC 마다 접수한 태스크를 집어 들어(`ACCEPTED` → `RUNNING`) 시계를 밀지
+     * 않아도 로그가 이어질 수 있다. 읽기만 하며 엔진 잠금 아래에서 돈다.
+     */
+    fun taskHistory(robotId: String): Map<String, List<String>> {
+        val instance = requireNotNull(mimic.instance(robotId)) { "이 현장에 없는 기체다: $robotId" }
+        return mimic.server.exclusive { instance.tasks.all.associate { task -> task.taskId to task.log.from(0).map { it.state.name } } }
+    }
+
     override fun close() {
         try {
             runner.close()
```

- [ ] **Step 4: 커밋 2/4 `feat(site): 기체 태스크 갱신 로그 읽기`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add site/src/main/kotlin/dev/picasso/ops/site/Site.kt && git commit -F - <<'EOF'
feat(site): 기체 태스크 갱신 로그 읽기

- Site.taskHistory: mimic 이 호스팅하는 태스크마다 갱신 로그의 상태 이름, 엔진 잠금 아래 읽기 전용
- 통합 시험이 재기동 앞뒤 새 명령 여부를 태스크 id 집합과 ACCEPTED 횟수로 대조하는 용도

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 5: (커밋 3/4) 새 파일 1개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && mkdir -p e2e/src/test/kotlin/dev/picasso/ops/e2e && cp "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/files/e2e/src/test/kotlin/dev/picasso/ops/e2e/RestartRecoveryTest.kt" e2e/src/test/kotlin/dev/picasso/ops/e2e/RestartRecoveryTest.kt
```

`e2e/src/test/kotlin/dev/picasso/ops/e2e/RestartRecoveryTest.kt`:

```kotlin
package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S4b 완료 판정의 통합 쪽(S4b 스펙 §3·§10). 두 기체를 시운전 완료로 만든 뒤 운영 서비스 REST 로 작업 지시를 내고, 같은 DB·현장을
 * 둔 채 실행 호스트만 다시 띄운다([E2eStack.restartHost]). 다시 지은 실행, 새 명령 없음, 송신 기록의 재기동 중복, 이전 인스턴스
 * 인시던트를 운영 서비스 REST 와 현장 mimic 의 태스크 로그로 본다.
 *
 * 단계와 스펙 §3 의 여섯: 단계 1 = 1, 단계 2 = 2, 단계 3 = 5, 단계 4 = 3, 단계 5 = 4, 단계 6 = 6. 단계 4~6 은 같은 보류 실행 하나를
 * 이어 쓴다.
 *
 * 순서가 있고 새 스택에서 돈다. `pick_place` 를 가진 기체가 humanoid-01 하나라 앞 실행이 끝나야 다음 작업 지시가 배정된다. 셀 대역은
 * 슬롯을 비우지 않으므로 작업 지시마다 슬롯을 따로 쓴다(단계 1 이 S01·S02, 단계 3 이 S03, 단계 4 가 S04).
 *
 * ## 임무 버전
 *
 * 버전 1 은 `ARRIVAL_WAIT`(기한 120초, ABORTED), 버전 2 는 `DATA_V1`(대기 없음), 버전 3 은 `ARRIVAL_WAIT_HOLD`(기한 20초, 운영자
 * 보류)다. 단계 3 은 버전 1 로 받은 뒤 버전 2 를 활성화하고 다시 띄워 대기 단위가 있는 버전 1 로 다시 서는 것을 본다.
 *
 * ## 시계
 *
 * 가상 시계만 민다([ExecutionDriver]). 다시 지은 실행의 단위는 첫 pump 들이 기체를 다시 관측한 뒤 끝난 대로·도는 대로 서므로
 * 시계를 밀지 않고 실제 시간으로 상태를 폴링한다([awaitUnits], S4b JSON 계약 H9). 설비 대기의 기한도 호스트 시계(현장 가상 시계)라
 * 실제 시간으로 기다리지 않는다. 연결 오래됨처럼 실제 수신 시각으로 판정하는 단계는 없다.
 *
 * ## 실패 모드
 *
 * humanoid-01 의 `pick_place` 는 넷(S01~S04)이라 시드 0 의 자연 실패(열두 번째)에 이르지 않는다. 다시 보낸 같은 태스크 id 의
 * `StartTask` 는 기존 태스크를 돌려주므로 추첨을 더 하지 않는다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RestartRecoveryTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver
        private val json = ObjectMapper()

        private const val WORK_MASTER = "PrepareSequencedRack"
        private const val MISSIONS = "/api/missions/$WORK_MASTER"
        private const val MATERIAL = "ENGINE-COVER-A"
        private const val PRESENTATION = "SEQ-IN-02.BIN-A"
        private const val WAIT_UNIT = "rack-arrival"
        private const val S01 = "RACK-204.S01"
        private const val S02 = "RACK-204.S02"
        private const val S03 = "RACK-204.S03"
        private const val S04 = "RACK-204.S04"

        /** `ARRIVAL_WAIT_HOLD` 의 대기 기한. */
        private val DEADLINE: Duration = Duration.ofSeconds(20)

        /** 다시 지은 실행의 단위가 기체를 다시 관측해 서기를 기다리는 상한(실제 시간). 호스트 시험에서는 5초 안이었다. */
        private val REOBSERVE_WAIT: Duration = Duration.ofSeconds(10)

        /** 단계 4~6 이 이어 쓰는 보류 실행. 실행 id 는 인스턴스마다 바뀌므로 작업 지시 id 로 찾는다. */
        private var heldOrder = ""

        /** 단계 5 가 재작업을 판단한 인스턴스와 그 인시던트. 단계 6 이 이전 인스턴스로 쓴다. */
        private var reworkInstance = ""
        private var reworkIncident = ""

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
            driver = ExecutionDriver(stack)
            Commissioned.complete(stack)
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }

        private fun rack(vararg slots: String): String =
            """{"workMasterId":"$WORK_MASTER","slots":[${slots.joinToString(",") { "\"$it\"" }}],""" +
                """"material":"$MATERIAL","presentation":"$PRESENTATION"}"""
    }

    @Test
    @Order(1)
    fun `단계 1 도는 실행이 있을 때 다시 띄우면 같은 작업 지시가 새 인스턴스에서 끝난 단위는 끝난 대로 도는 단위는 도는 대로 다시 서고 끝까지 가며 앞서 통과한 대기 때문에 도달 근거 등급이 E0 이다`() {
        activate("ARRIVAL_WAIT", "랙 도착 대기 도입", 1)
        signal("true")
        val (jobOrderId, executionId) = submit(rack(S01, S02))
        pushUntil(executionId, ExecutionDriver.STEP) { it[S01] == "DONE" && it[S02] == "RUNNING" }
        val before = driver.execution(executionId)
        assertEquals(listOf(Triple(WAIT_UNIT, "DONE", "E2"), Triple(S01, "DONE", "E2"), Triple(S02, "RUNNING", "E0")), units(before), "$before")
        val previous = driver.executions()["instanceId"].asText()

        val instance = restart(previous)
        val view = driver.executions()
        val row = view["restore"]["rows"].single()
        assertEquals(
            listOf(jobOrderId, HUMANOID, previous, executionId, "RESTORED"),
            listOf("jobOrderId", "robotId", "previousInstanceId", "previousExecutionId", "result").map { row[it].asText() },
            "$row",
        )
        assertTrue(row["reason"].isNull, "$row")
        val restored = view["executions"].single()
        assertEquals(jobOrderId to row["executionId"].asText(), restored["jobOrderId"].asText() to restored["executionId"].asText(), "$view")
        assertEquals(json.readTree("""{"instanceId":"$previous","executionId":"$executionId"}"""), restored["restoredFrom"], "$restored")
        assertEquals(1, restored["missionVersion"].asInt(), "$restored")
        val newExecution = restored["executionId"].asText()

        // 앞서 통과한 대기와 마지막 로봇 단위 앞의 로봇 단위는 이 인스턴스가 관측하지 않았으므로 E0 로 끝난 대로 선다.
        val reobserved = awaitUnits(newExecution, listOf(WAIT_UNIT to "DONE", S01 to "DONE", S02 to "RUNNING"))
        assertEquals(listOf(Triple(WAIT_UNIT, "DONE", "E0"), Triple(S01, "DONE", "E0"), Triple(S02, "RUNNING", "E0")), units(reobserved))

        val done = driver.drive(newExecution)
        assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
        assertEquals(listOf(Triple(WAIT_UNIT, "DONE", "E0"), Triple(S01, "DONE", "E0"), Triple(S02, "DONE", "E2")), units(done), "$done")
        assertEquals("E0" to "E2", done["jobResponse"]["reachedEvidence"].asText() to done["jobResponse"]["requiredEvidence"].asText(), "$done")

        // 재기동 전에는 응답이 없었으므로 다시 지은 실행의 완료 응답이 그 작업 지시의 첫 송신이다.
        val log = responses(jobOrderId)
        assertEquals(listOf(Triple(instance, "PHYSICALLY_DONE", "SENT")), log.map { Triple(it["instanceId"].asText(), it["physicalState"].asText(), it["disposition"].asText()) }, "$log")
        assertEquals("E0", log.single()["reachedEvidence"].asText(), "$log")
    }

    @Test
    @Order(2)
    fun `단계 2 도는 InspectAsset 을 다시 띄워도 시계를 밀기 전 기체의 태스크 로그가 그대로이고 끝까지 가도 태스크마다 ACCEPTED 가 한 번뿐이다`() {
        val (jobOrderId, executionId) = submit("""{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"},{"id":"T2","location":"dock-3"}]}""")
        pushUntil(executionId, Duration.ofSeconds(1)) { it["T1.travel"] == "DONE" && it["T1"] == "RUNNING" }
        val tasksBefore = stack.site.taskHistory(HUMANOID).filterKeys { it.startsWith("$jobOrderId#") }
        assertEquals(setOf("$jobOrderId#T1.travel", "$jobOrderId#T1"), tasksBefore.keys, "$tasksBefore")
        val clock = stack.site.now()

        restart(driver.executions()["instanceId"].asText())
        val restored = driver.executions()["executions"].single()
        val reobserved = awaitUnits(
            restored["executionId"].asText(),
            listOf("T1.travel" to "DONE", "T1" to "RUNNING", "T2.travel" to "PENDING", "T2" to "PENDING"),
        )
        // 다시 지은 실행은 같은 태스크 id·리비전으로 StartTask 를 다시 보냈다. 기체는 기존 태스크를 돌려주고 새 태스크를 만들지 않는다.
        // mimic 은 RPC 마다 접수한 태스크를 집어 들므로(ACCEPTED → RUNNING) 시계를 밀지 않아도 로그가 이어질 수는 있다. 그래서
        // 태스크 집합이 같고 재기동 전 로그가 그대로 앞에 있으며 ACCEPTED 가 다시 서지 않았음을 본다.
        assertEquals(clock, stack.site.now(), "시계를 밀지 않았다")
        val tasksRestored = stack.site.taskHistory(HUMANOID).filterKeys { it.startsWith("$jobOrderId#") }
        assertEquals(tasksBefore.keys, tasksRestored.keys, "$reobserved")
        tasksBefore.forEach { (taskId, history) ->
            val now = tasksRestored.getValue(taskId)
            assertEquals(history, now.take(history.size), "$taskId: $now")
            assertEquals(1, now.count { it == "ACCEPTED" }, "$taskId: $now")
        }

        val done = driver.drive(restored["executionId"].asText())
        assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
        val tasksAfter = stack.site.taskHistory(HUMANOID)
        assertEquals(
            listOf("T1.travel", "T1", "T2.travel", "T2").map { "$jobOrderId#$it" }.toSet(),
            tasksAfter.keys.filter { it.startsWith("$jobOrderId#") }.toSet(),
            "$tasksAfter",
        )
        // 단계 1 의 태스크까지 이 기체의 모든 태스크가 ACCEPTED 를 한 번만 지났다.
        tasksAfter.forEach { (taskId, history) -> assertEquals(1, history.count { it == "ACCEPTED" }, "$taskId: $history") }
        assertTrue(tasksAfter.keys.none { "@r" in it }, "$tasksAfter")
    }

    @Test
    @Order(3)
    fun `단계 3 작업 지시를 받은 뒤 다른 임무 버전을 활성화하고 다시 띄우면 받은 때의 임무 버전으로 다시 서고 그 버전으로 끝난다`() {
        val (jobOrderId, executionId) = submit(rack(S03))
        assertEquals(1, driver.execution(executionId)["missionVersion"].asInt())
        activate("DATA_V1", "대기 없는 정의로 되돌림", 2)
        assertEquals("DATA" to 2, stack.get(MISSIONS)["active"].let { it["source"].asText() to it["version"].asInt() })

        restart(driver.executions()["instanceId"].asText())
        val view = driver.executions()
        assertEquals(jobOrderId to "RESTORED", view["restore"]["rows"].single().let { it["jobOrderId"].asText() to it["result"].asText() }, "$view")
        val restored = view["executions"].single()
        // 지금 활성은 대기 없는 버전 2 지만 다시 지은 실행은 일지의 버전 1 이라 대기 단위가 있다.
        assertEquals(1, restored["missionVersion"].asInt(), "$restored")
        assertEquals(listOf(WAIT_UNIT, S03), restored["units"].map { it["unitId"].asText() }, "$restored")

        val done = driver.drive(restored["executionId"].asText())
        assertEquals("PHYSICALLY_DONE" to 1, done["physicalState"].asText() to done["missionVersion"].asInt(), "$done")
        assertEquals(listOf(WAIT_UNIT, S03).map { it to "DONE" }, done["units"].map { it["unitId"].asText() to it["state"].asText() }, "$done")
    }

    @Test
    @Order(4)
    fun `단계 4 운영자 보류에 선 실행을 두 번 다시 띄우면 기한이 다시 지난 뒤의 보류 응답이 매번 재기동 중복으로 적히고 송신은 한 번뿐이다`() {
        activate("ARRIVAL_WAIT_HOLD", "운영자 보류 대기 도입", 3)
        signal("false")
        val (jobOrderId, executionId) = submit(rack(S04))
        heldOrder = jobOrderId
        driver.push(ExecutionDriver.STEP)
        pushUntilHeld(executionId)
        val first = driver.executions()["instanceId"].asText()
        assertEquals(listOf(Triple(first, "OPERATOR_HOLD", "SENT")), dispositions(jobOrderId))

        val second = restart(first)
        assertEquals(jobOrderId to "RESTORED", driver.executions()["restore"]["rows"].single().let { it["jobOrderId"].asText() to it["result"].asText() })
        rehold(second)
        assertEquals(
            listOf(Triple(second, "OPERATOR_HOLD", "RESTART_DUPLICATE"), Triple(first, "OPERATOR_HOLD", "SENT")),
            dispositions(jobOrderId),
        )

        val third = restart(second)
        val row = driver.executions()["restore"]["rows"].single()
        assertEquals(listOf(second, "RESTORED"), listOf(row["previousInstanceId"].asText(), row["result"].asText()), "$row")
        rehold(third)
        val log = dispositions(jobOrderId)
        assertEquals(
            listOf(Triple(third, "OPERATOR_HOLD", "RESTART_DUPLICATE"), Triple(second, "OPERATOR_HOLD", "RESTART_DUPLICATE"), Triple(first, "OPERATOR_HOLD", "SENT")),
            log,
        )
        assertEquals(1, log.count { it.third == "SENT" }, "$log")
        // 재기동 중복은 송신한 행과 내용 키가 같다.
        val rows = responses(jobOrderId)
        val keys = listOf("version", "physicalState", "requiredEvidence", "reachedEvidence", "completedUnits", "unverifiedUnits", "incompleteUnits", "inDoubtUnits", "operatorRequired", "residualHold", "blockedBy")
        assertEquals(1, rows.map { r -> keys.map { r[it] } }.distinct().size, "$rows")
    }

    @Test
    @Order(5)
    fun `단계 5 설비 대기 보류를 재작업으로 판단한 뒤 다시 띄우면 대기가 새 기한으로 다시 서고 옛 인시던트와 그 판단은 earlier 에만 있다`() {
        val executionId = executionOf(heldOrder)
        val hold = incidents()["incidents"].single { it["executionId"].asText() == executionId && it["held"].asBoolean() }
        reworkIncident = hold["incidentId"].asText()
        reworkInstance = stack.get("/api/incidents/$reworkIncident")["instanceId"].asText()
        val reply = resolve(executionId, "REWORK", "랙 재배치 뒤 재작업", reworkInstance)
        assertEquals(listOf("SUCCEEDED", "Resolved"), listOf(reply.body!!["result"].asText(), reply.body["outcome"].asText()), "${reply.body}")
        // 재작업으로 다시 선 대기가 도는 중에 기한 전까지만 민다. 이 pump 들이 판단 행을 사본에 적는다.
        driver.push(Duration.ofSeconds(10))
        assertEquals("RUNNING", unitState(heldOrder, WAIT_UNIT), "${driver.executions()}")

        val instance = restart(reworkInstance)
        val restartedAt = stack.site.now()
        awaitUnits(executionOf(heldOrder), listOf(WAIT_UNIT to "RUNNING", S04 to "PENDING"))
        // 재작업 뒤 25초, 재기동 뒤 15초. 기한이 재작업 때부터였다면 이미 보류다.
        driver.push(Duration.ofSeconds(15))
        assertEquals("RUNNING", unitState(heldOrder, WAIT_UNIT), "${driver.executions()}")
        val held = pushUntilHeld(executionOf(heldOrder))
        assertTrue(Duration.between(restartedAt, stack.site.now()) >= DEADLINE, "재기동 뒤 기한 전에 보류가 섰다: ${stack.site.now()}")
        assertEquals("OPERATOR_HOLD", held["physicalState"].asText(), "$held")

        val list = incidents()
        assertEquals(instance, list["instanceId"].asText(), "$list")
        // 지금 인스턴스에는 새 보류 하나뿐이고 판단 든 인시던트가 없다.
        val live = list["incidents"].filter { it["jobOrderId"].asText() == heldOrder }
        assertEquals(1, live.size, "$list")
        assertEquals(listOf(true, true), listOf(live.single()["unresolved"].asBoolean(), live.single()["held"].asBoolean()), "$list")
        assertTrue(list["incidents"].all { it["resolution"].isNull }, "$list")
        // 앞 세 인스턴스의 보류는 earlier 에 최근 것부터 있고, 재작업 판단은 그 인스턴스의 사본에 붙었다.
        val earlier = list["earlier"].filter { it["jobOrderId"].asText() == heldOrder }
        assertEquals(3, earlier.size, "$list")
        assertEquals(reworkInstance to reworkIncident, earlier.first()["instanceId"].asText() to earlier.first()["incidentId"].asText(), "$list")
        val resolution = earlier.first()["resolution"]
        assertEquals(listOf("REWORK", "kim", "PERSON"), listOf(resolution["decision"], resolution["decidedBy"]["id"], resolution["decidedBy"]["kind"]).map { it.asText() }, "$list")
        assertTrue(earlier.all { !it["held"].asBoolean() && it["failureClass"].asText() == "SIGNAL_DEADLINE" }, "$list")
        assertTrue(earlier.drop(1).all { it["resolution"].isNull }, "$list")
        assertTrue(list["earlier"].none { it["instanceId"].asText() == instance }, "$list")
    }

    @Test
    @Order(6)
    fun `단계 6 재기동 뒤 이전 인스턴스의 인시던트와 판단이 보이고 이전 인스턴스를 실은 판단은 INSTANCE_MISMATCH 로 거부된다`() {
        val copy = stack.get("/api/incidents/$reworkIncident?instanceId=$reworkInstance")
        assertEquals(reworkInstance to heldOrder, copy["instanceId"].asText() to copy["jobOrderId"].asText(), "$copy")
        assertEquals("REWORK" to false, copy["resolution"]["decision"].asText() to copy["held"].asBoolean(), "$copy")
        assertTrue(copy["unitState"].isNull, "$copy")

        val executionId = executionOf(heldOrder)
        val current = incidents()["instanceId"].asText()
        assertNotEquals(reworkInstance, current)
        val stale = resolve(executionId, "CONFIRM_DONE", "이전 상세로 판단", reworkInstance)
        assertEquals(200, stale.status, "${stale.body}")
        val body = stale.body!!
        assertEquals("REJECTED", body["result"].asText(), "$body")
        assertTrue(body["outcome"].isNull && body["answer"].isNull && body["confirmation"].isNull, "$body")
        assertEquals(409 to "INSTANCE_MISMATCH", body["rejection"]["status"].asInt() to body["rejection"]["error"].asText(), "$body")
        assertTrue(reworkInstance in body["rejection"]["detail"].asText() && current in body["rejection"]["detail"].asText(), "$body")
        val logged = operations("RESOLVE_OPERATOR_HOLD").first()
        assertEquals("REJECTED" to "이전 상세로 판단", logged["result"].asText() to logged["reason"].asText(), "$logged")
        assertEquals(reworkInstance, json.readTree(logged["request"].asText())["instanceId"].asText(), "$logged")
        val target = json.readTree(logged["targetResponse"].asText())
        assertEquals(409 to "INSTANCE_MISMATCH", target["status"].asInt() to target["body"]["error"].asText(), "$logged")
        // 거부된 판단은 보류를 바꾸지 않는다.
        assertEquals("OPERATOR_HOLD", unitState(heldOrder, WAIT_UNIT))

        // 지금 인스턴스로 판단하면 받고 끝까지 간다.
        val confirmed = resolve(executionId, "CONFIRM_DONE", "현장 육안으로 랙 도착 확인", current)
        assertEquals("SUCCEEDED" to "Resolved", confirmed.body!!["result"].asText() to confirmed.body["outcome"].asText(), "${confirmed.body}")
        val done = driver.drive(executionId)
        assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
        assertEquals(listOf(Triple(WAIT_UNIT, "DONE", "E0"), Triple(S04, "DONE", "E2")), units(done), "$done")
    }

    /** 실행 호스트를 다시 띄우고 운영 서비스가 새 인스턴스를 보는지 확인한 뒤 새 인스턴스 id 를 낸다. */
    private fun restart(previous: String): String {
        stack.restartHost()
        val instance = driver.executions()["instanceId"].asText()
        assertNotEquals(previous, instance, "재기동 뒤 인스턴스가 같다")
        return instance
    }

    /** 다시 지은 보류 실행의 대기가 기한 전에는 돌고, 기한이 지나면 다시 보류가 되며, 그 보류 응답이 [instance] 의 행으로 적히기를 본다. */
    private fun rehold(instance: String) {
        val restartedAt = stack.site.now()
        val executionId = executionOf(heldOrder)
        awaitUnits(executionId, listOf(WAIT_UNIT to "RUNNING", S04 to "PENDING"))
        assertEquals("RUNNING", driver.execution(executionId)["physicalState"].asText())
        driver.push(Duration.ofSeconds(15))
        assertEquals("RUNNING", unitState(heldOrder, WAIT_UNIT), "기한 전에 보류가 섰다: ${driver.executions()}")
        pushUntilHeld(executionId)
        assertTrue(Duration.between(restartedAt, stack.site.now()) >= DEADLINE, "${stack.site.now()}")
        // 송신 기록은 응답이 난 pump 와 같은 잠금 안에서 적힌다(S4b JSON 계약 H9).
        val until = Instant.now().plus(REOBSERVE_WAIT)
        while (responses(heldOrder).none { it["instanceId"].asText() == instance }) {
            check(Instant.now().isBefore(until)) { "$instance 의 보류 응답이 송신 기록에 없다: ${responses(heldOrder)}" }
            Thread.sleep(100)
        }
    }

    /** 시계를 밀지 않고 실제 시간으로 실행의 단위가 [expected] (단위 id, 상태) 가 되기를 기다린다. */
    private fun awaitUnits(executionId: String, expected: List<Pair<String, String>>): JsonNode {
        val clock = stack.site.now()
        val until = Instant.now().plus(REOBSERVE_WAIT)
        var seen = driver.execution(executionId)
        while (seen["units"].map { it["unitId"].asText() to it["state"].asText() } != expected) {
            check(Instant.now().isBefore(until)) { "${REOBSERVE_WAIT.seconds}초 안에 $expected 가 되지 않았다: $seen" }
            Thread.sleep(100)
            seen = driver.execution(executionId)
        }
        assertEquals(clock, stack.site.now(), "다시 관측을 기다리는 동안 시계를 밀지 않는다")
        return seen
    }

    /**
     * [done] 이 단위 상태 맵에 참이 될 때까지 [step] 씩 민다. 민 뒤 확인 중(VERIFYING)인 단위가 있으면 실제 시간으로 기다린다
     * ([ExecutionDriver.drive] 와 같은 규칙).
     */
    private fun pushUntil(executionId: String, step: Duration, done: (Map<String, String>) -> Boolean) {
        repeat(ExecutionDriver.ROUNDS * 2) {
            var seen = driver.execution(executionId)
            val until = Instant.now().plus(ExecutionDriver.VERIFY_WAIT)
            while (seen["units"].any { it["state"].asText() == "VERIFYING" } && Instant.now().isBefore(until)) {
                Thread.sleep(100)
                seen = driver.execution(executionId)
            }
            if (done(seen["units"].associate { it["unitId"].asText() to it["state"].asText() })) return
            driver.push(step)
        }
        error("단위가 바라는 상태에 이르지 않았다: ${driver.execution(executionId)}")
    }

    /** 대기 단위가 운영자 보류가 될 때까지 [ExecutionDriver.STEP] 씩 민다. */
    private fun pushUntilHeld(executionId: String): JsonNode {
        repeat(8) {
            val seen = driver.execution(executionId)
            if (seen["units"].single { it["unitId"].asText() == WAIT_UNIT }["state"].asText() == "OPERATOR_HOLD") return seen
            driver.push(ExecutionDriver.STEP)
        }
        error("대기 단위가 운영자 보류가 되지 않았다: ${driver.execution(executionId)}")
    }

    /** 지금 인스턴스에서 그 작업 지시를 든 실행 id. 다시 지으면 인스턴스의 셈으로 바뀐다. */
    private fun executionOf(jobOrderId: String): String =
        driver.executions()["executions"].single { it["jobOrderId"].asText() == jobOrderId }["executionId"].asText()

    private fun unitState(jobOrderId: String, unitId: String): String =
        driver.execution(executionOf(jobOrderId))["units"].single { it["unitId"].asText() == unitId }["state"].asText()

    /** 실행의 단위를 (단위 id, 상태, 근거 등급)으로 낸다. */
    private fun units(execution: JsonNode): List<Triple<String, String, String>> =
        execution["units"].map { Triple(it["unitId"].asText(), it["state"].asText(), it["reached"].asText()) }

    /** 그 작업 지시의 송신 기록. 최근에 적은 것부터다. */
    private fun responses(jobOrderId: String): List<JsonNode> = stack.get("/api/job-responses?jobOrderId=$jobOrderId")["responses"].toList()

    /** 송신 기록을 (인스턴스, 물리 상태, 처분)으로 낸다. 최근에 적은 것부터다. */
    private fun dispositions(jobOrderId: String): List<Triple<String, String, String>> =
        responses(jobOrderId).map { Triple(it["instanceId"].asText(), it["physicalState"].asText(), it["disposition"].asText()) }

    private fun incidents(): JsonNode = stack.get("/api/incidents")

    /** 조작 기록에서 [op] 의 행만 최신부터 낸다. */
    private fun operations(op: String): List<JsonNode> =
        stack.get("/api/operations").filter { json.readTree(it["request"].asText())["op"].asText() == op }

    /** 운영자 모드로 대기 단위의 보류를 판단한다. 본문의 `instanceId` 는 화면처럼 인시던트 상세의 값이다(S4b JSON 계약 O3). */
    private fun resolve(executionId: String, decision: String, reason: String, instanceId: String): E2eStack.Reply = stack.send(
        "POST", "/api/executions/$executionId/units/$WAIT_UNIT/resolve", "operator",
        body = """{"decision":"$decision","instanceId":"$instanceId","reason":"$reason"}""",
    )

    private fun signal(value: String) {
        val written = stack.send("POST", "/api/cell/signals/rack_present", "operator", body = """{"value":"$value"}""")
        assertEquals("SUCCEEDED", written.body!!["result"].asText(), "${written.body}")
    }

    /** S3b 흐름으로 템플릿을 초안 저장, 모의 실행, 활성화한다. */
    private fun activate(template: String, reason: String, version: Int) {
        val definition = stack.get("/api/missions/templates/$WORK_MASTER")["templates"].single { it["id"].asText() == template }["definition"].asText()
        val saved = engineer("$MISSIONS/drafts", json.createObjectNode().put("definition", definition).toString())
        val draftId = saved["outcome"]["draft"]["draftId"].asLong()
        val mocked = engineer("$MISSIONS/drafts/$draftId/mock-run", "{}")
        assertEquals("PASSED", mocked["outcome"]["result"].asText(), "$mocked")
        val activated = engineer("$MISSIONS/drafts/$draftId/activate", """{"reason":"$reason"}""")
        assertEquals("ACTIVATED" to version, activated["outcome"]["result"].asText() to activated["outcome"]["version"].asInt(), "$activated")
    }

    private fun engineer(path: String, body: String): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        assertEquals(200, reply.status, "$path → ${reply.body}")
        return reply.body!!
    }

    /** 운영자 모드로 작업 지시를 내고 작업 지시 id 와 실행 id 를 낸다. 두 작업 지시 모두 humanoid-01 이 받는다. */
    private fun submit(form: String): Pair<String, String> {
        val reply = stack.send("POST", "/api/job-orders", "operator", body = form)
        assertEquals(200, reply.status, "${reply.body}")
        val outcome = reply.body!!["outcome"]
        assertEquals("ACCEPTED" to HUMANOID, outcome["result"].asText() to outcome["robotId"].asText(), "${reply.body}")
        return reply.body["jobOrderId"].asText() to outcome["executionId"].asText()
    }
}
```

- [ ] **Step 6: 커밋 3/4 `test(e2e): 호스트 재기동 복원 통합 시험`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add e2e/src/test/kotlin/dev/picasso/ops/e2e/RestartRecoveryTest.kt && git commit -F - <<'EOF'
test(e2e): 호스트 재기동 복원 통합 시험

- 단계 1: 도는 실행의 다시 짓기, restoredFrom 과 복원 보고, 끝난 단위·도는 단위 다시 관측, 앞서 통과한 대기로 도달 근거 E0
- 단계 2: InspectAsset 재기동 앞뒤 태스크 집합 유지, 태스크마다 ACCEPTED 한 번
- 단계 3: 받은 뒤 다른 임무 버전 활성화, 받은 때 버전으로 다시 짓기
- 단계 4: 운영자 보류 실행 두 번 재기동, 새 기한 뒤 보류 응답의 재기동 중복, 송신 한 번
- 단계 5: 재작업 판단 뒤 재기동, 새 기한, 옛 인시던트와 판단은 earlier 에만
- 단계 6: 이전 인스턴스 사본 상세, 이전 인스턴스 판단의 INSTANCE_MISMATCH 거부와 조작 기록
- 가상 시계만 밀고 다시 관측은 시계를 밀지 않은 채 상태 폴링

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 7: (커밋 4/4) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5d.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5d.patch"
```

```diff
diff --git a/e2e/src/test/kotlin/dev/picasso/ops/e2e/RestartRecoveryTest.kt b/e2e/src/test/kotlin/dev/picasso/ops/e2e/RestartRecoveryTest.kt
index 6863803..6e40f5e 100644
--- a/e2e/src/test/kotlin/dev/picasso/ops/e2e/RestartRecoveryTest.kt
+++ b/e2e/src/test/kotlin/dev/picasso/ops/e2e/RestartRecoveryTest.kt
@@ -196,7 +196,7 @@ class RestartRecoveryTest {
 
     @Test
     @Order(4)
-    fun `단계 4 운영자 보류에 선 실행을 두 번 다시 띄우면 기한이 다시 지난 뒤의 보류 응답이 매번 재기동 중복으로 적히고 송신은 한 번뿐이다`() {
+    fun `단계 4 운영자 보류에 선 실행을 두 번 다시 띄우면 기한이 다시 지난 뒤의 보류 응답이 매번 재기동 중복으로 적히고 송신은 한 번뿐이며 같은 인스턴스에서 재작업 뒤 다시 선 보류는 송신이다`() {
         activate("ARRIVAL_WAIT_HOLD", "운영자 보류 대기 도입", 3)
         signal("false")
         val (jobOrderId, executionId) = submit(rack(S04))
@@ -228,11 +228,21 @@ class RestartRecoveryTest {
         val rows = responses(jobOrderId)
         val keys = listOf("version", "physicalState", "requiredEvidence", "reachedEvidence", "completedUnits", "unverifiedUnits", "incompleteUnits", "inDoubtUnits", "operatorRequired", "residualHold", "blockedBy")
         assertEquals(1, rows.map { r -> keys.map { r[it] } }.distinct().size, "$rows")
+
+        // 같은 인스턴스에서 재작업 뒤 다시 선 보류는 내용이 같아도 새로 일어난 일이라 송신한다. 단계 5 가 이 보류를 재작업하고 다시
+        // 띄운다.
+        val first3 = incidents()["incidents"].single { it["held"].asBoolean() }["incidentId"].asText()
+        val reworked = resolve(heldExecution(), "REWORK", "랙 위치 확인 뒤 재작업", stack.get("/api/incidents/$first3")["instanceId"].asText())
+        assertEquals("SUCCEEDED" to "Resolved", reworked.body!!["result"].asText() to reworked.body["outcome"].asText(), "${reworked.body}")
+        pushUntilHeld(heldExecution())
+        val again = waitLogged(third, 2)
+        assertEquals(Triple(third, "OPERATOR_HOLD", "SENT"), again.first(), "$again")
+        assertEquals(2, again.count { it.third == "SENT" }, "$again")
     }
 
     @Test
     @Order(5)
-    fun `단계 5 설비 대기 보류를 재작업으로 판단한 뒤 다시 띄우면 대기가 새 기한으로 다시 서고 옛 인시던트와 그 판단은 earlier 에만 있다`() {
+    fun `단계 5 설비 대기 보류를 재작업으로 판단한 뒤 다시 띄우면 대기가 새 기한으로 다시 서고 그 보류 응답은 재기동 중복이며 옛 인시던트와 그 판단은 earlier 에만 있다`() {
         val executionId = executionOf(heldOrder)
         val hold = incidents()["incidents"].single { it["executionId"].asText() == executionId && it["held"].asBoolean() }
         reworkIncident = hold["incidentId"].asText()
@@ -252,6 +262,8 @@ class RestartRecoveryTest {
         val held = pushUntilHeld(executionOf(heldOrder))
         assertTrue(Duration.between(restartedAt, stack.site.now()) >= DEADLINE, "재기동 뒤 기한 전에 보류가 섰다: ${stack.site.now()}")
         assertEquals("OPERATOR_HOLD", held["physicalState"].asText(), "$held")
+        // 상위가 마지막으로 받은 것은 재작업 뒤 보류(단계 4 끝의 SENT)다. 내용 키가 같아 송신하지 않는다.
+        assertEquals(Triple(instance, "OPERATOR_HOLD", "RESTART_DUPLICATE"), waitLogged(instance, 1).first())
 
         val list = incidents()
         assertEquals(instance, list["instanceId"].asText(), "$list")
@@ -260,14 +272,15 @@ class RestartRecoveryTest {
         assertEquals(1, live.size, "$list")
         assertEquals(listOf(true, true), listOf(live.single()["unresolved"].asBoolean(), live.single()["held"].asBoolean()), "$list")
         assertTrue(list["incidents"].all { it["resolution"].isNull }, "$list")
-        // 앞 세 인스턴스의 보류는 earlier 에 최근 것부터 있고, 재작업 판단은 그 인스턴스의 사본에 붙었다.
+        // 앞 세 인스턴스의 보류 넷(셋째 인스턴스에 둘)은 earlier 에 최근 것부터 있고, 재작업 판단은 그 사본에 붙었다.
         val earlier = list["earlier"].filter { it["jobOrderId"].asText() == heldOrder }
-        assertEquals(3, earlier.size, "$list")
+        assertEquals(4, earlier.size, "$list")
         assertEquals(reworkInstance to reworkIncident, earlier.first()["instanceId"].asText() to earlier.first()["incidentId"].asText(), "$list")
         val resolution = earlier.first()["resolution"]
         assertEquals(listOf("REWORK", "kim", "PERSON"), listOf(resolution["decision"], resolution["decidedBy"]["id"], resolution["decidedBy"]["kind"]).map { it.asText() }, "$list")
         assertTrue(earlier.all { !it["held"].asBoolean() && it["failureClass"].asText() == "SIGNAL_DEADLINE" }, "$list")
-        assertTrue(earlier.drop(1).all { it["resolution"].isNull }, "$list")
+        assertEquals("REWORK", earlier[1]["resolution"]["decision"].asText(), "$list")
+        assertTrue(earlier.drop(2).all { it["resolution"].isNull }, "$list")
         assertTrue(list["earlier"].none { it["instanceId"].asText() == instance }, "$list")
     }
 
@@ -373,6 +386,19 @@ class RestartRecoveryTest {
         error("대기 단위가 운영자 보류가 되지 않았다: ${driver.execution(executionId)}")
     }
 
+    /** 단계 4~6 의 보류 실행의 지금 실행 id. */
+    private fun heldExecution(): String = executionOf(heldOrder)
+
+    /** 보류 실행의 송신 기록에 [instance] 의 행이 [count] 개 적히기를 기다리고 (인스턴스, 물리 상태, 처분)을 최근부터 낸다. */
+    private fun waitLogged(instance: String, count: Int): List<Triple<String, String, String>> {
+        val until = Instant.now().plus(REOBSERVE_WAIT)
+        while (dispositions(heldOrder).count { it.first == instance } < count) {
+            check(Instant.now().isBefore(until)) { "$instance 의 행이 $count 개가 아니다: ${dispositions(heldOrder)}" }
+            Thread.sleep(100)
+        }
+        return dispositions(heldOrder)
+    }
+
     /** 지금 인스턴스에서 그 작업 지시를 든 실행 id. 다시 지으면 인스턴스의 셈으로 바뀐다. */
     private fun executionOf(jobOrderId: String): String =
         driver.executions()["executions"].single { it["jobOrderId"].asText() == jobOrderId }["executionId"].asText()
```

- [ ] **Step 8: 커밋 4/4 `test(e2e): 재작업 뒤 보류의 송신과 재기동 중복 단언`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add e2e/src/test/kotlin/dev/picasso/ops/e2e/RestartRecoveryTest.kt && git commit -F - <<'EOF'
test(e2e): 재작업 뒤 보류의 송신과 재기동 중복 단언

- 단계 4: 셋째 인스턴스에서 재작업 뒤 다시 선 보류는 내용이 같아도 SENT
- 단계 5: 그 보류를 재작업하고 재기동한 뒤의 보류 응답은 RESTART_DUPLICATE
- 단계 5: earlier 의 그 작업 지시 사본 넷, 앞 둘에 REWORK 판단

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 9: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && ./gradlew :site:test :e2e:test -q
```
Expected: site 38(수는 그대로, `Site.taskHistory` 는 통합 시험이 쓴다), e2e 63, 실패 0(`RestartRecoveryTest` 6 포함, 그 클래스 약 81초, e2e 모듈 전체 약 6분). 백그라운드로 돌린다. Docker 데몬이 떠 있어야 한다(Testcontainers).

- [ ] **Step 10: 묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s4b-cmp.sh" --at 380eb1c e2e/src/test/kotlin/dev/picasso/ops/e2e/RestartRecoveryTest.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/FaultIncidentTest.kt site/src/main/kotlin/dev/picasso/ops/site/Site.kt
```
Expected: 3개 모두 `같음`(이 Task 의 마지막 스파이크 커밋 `380eb1c` 의 파일과 바이트 대조, 뒤 Task 가 같은 파일을 다시 고칠 수 있어 HEAD 가 아니다).

### Task 5b: 계획 검토 고침

**Files:**
- Modify: `mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt`, `mission-host/src/main/resources/db/mission/V2__execution_journal.sql`, `mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt`, `ui/e2e/lifecycle.spec.ts`, `ui/src/components/OperationsArea.tsx`

- [ ] **Step 1: (커밋 1/6) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b-a.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b-a.patch"
```

```diff
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
index 2672a9a..2b93d61 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
@@ -323,7 +323,8 @@ class MissionHost(
     private fun restore() = lock.withLock {
         restoredAt = clock.now()
         records.openJournal().forEach { row -> restoreRow(row) }
-        gaveUp += records.gaveUpJournal()
+        // 이번 기동에서 포기한 행은 attempt 가 이미 넣었고 그 GAVE_UP 이벤트도 일지에 있다. 작업 지시마다 한 번만 든다.
+        gaveUp += records.gaveUpJournal().filter { row -> gaveUp.none { it.jobOrderId == row.jobOrderId } }
     }
 
     private fun restoreRow(row: JournalRow) {
```

- [ ] **Step 2: 커밋 1/6 `fix(mission-host): 포기한 일지 행의 판정 제외 중복 제거`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt && git commit -F - <<'EOF'
fix(mission-host): 포기한 일지 행의 판정 제외 중복 제거

- 기동 복원에서 포기한 행을 attempt 와 포기 일지 읽기가 함께 넣던 중복
- 판정 이유에 같은 작업 지시 id 가 두 번 붙던 증상(`JO-E2, JO-E2`)
- 포기 일지 읽기에서 이미 든 작업 지시를 거르는 처리

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 3: (커밋 2/6) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b-b.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b-b.patch"
```

```diff
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
index 2b93d61..f509707 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
@@ -295,7 +295,7 @@ class MissionHost(
     private val deferred = mutableListOf<Deferred>()
 
     /**
-     * 포기했고 정착하지 않은 일지 행(T4). 그 기체의 스냅숏에 그 작업 지시의 비종착 태스크가 없어질 때까지 그 기체를 판정에서 뺀다.
+     * 포기했고 정착하지 않은 일지 행(T4). 그 기체의 스냅숏에 그 작업 지시의 종료하지 않은 태스크가 없어질 때까지 그 기체를 판정에서 뺀다.
      * 이전 기동에서 포기한 행도 든다. 풀린 행은 이 인스턴스에서 다시 빼지 않는다.
      */
     private val gaveUp = mutableListOf<JournalRow>()
@@ -735,7 +735,7 @@ class MissionHost(
 
     /**
      * 이 기체에서 다시 짓지 못한 작업 지시(T4). 미룬 행은 늘 든다. 포기한 행은 그 기체의 스냅숏을 이번에 한 번 읽어, 그 작업 지시의
-     * 비종착 태스크(id 가 `jobOrderId#` 로 시작, `@rN` 이 붙은 재작업 태스크 포함)가 없으면 풀고 더 빼지 않는다. 스냅숏을 못 읽으면
+     * 종료하지 않은 태스크(id 가 `jobOrderId#` 로 시작, `@rN` 이 붙은 재작업 태스크 포함)가 없으면 풀고 더 빼지 않는다. 스냅숏을 못 읽으면
      * 계속 뺀다.
      */
     private fun unrestoredOn(robotId: String): List<String> {
@@ -798,10 +798,10 @@ class MissionHost(
         /** 다시 짓지 못한 실행이 있는 기체에 판정이 더하는 이유의 앞부분(S4b 스펙 T4). 뒤에 작업 지시 id 가 붙는다. */
         const val UNRESTORED_REASON = "복원 못 한 실행이 있다"
 
-        /** 종착한 태스크 상태. 미들웨어가 단위의 종착으로 보는 것과 같다(사람을 기다리는 RETRIABLE·NEEDS_INTERVENTION 포함). */
+        /** 종료 상태(용어집 태스크 수명주기). 사람을 기다리는 RETRIABLE·NEEDS_INTERVENTION 은 종료하지 않은 상태라 기체를 계속 뺀다. */
         private val TERMINAL_TASK_STATES = setOf(
             TaskState.TASK_STATE_SUCCEEDED, TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_CANCELLED,
-            TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED, TaskState.TASK_STATE_NEEDS_INTERVENTION, TaskState.TASK_STATE_RETRIABLE,
+            TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED,
         )
 
         /** 운영자 판단의 결과 이름. picasso `ResolveOutcome` 의 이름 그대로다(S4a 스펙 T6). */
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt
index 0f5615e..b0cb46b 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt
@@ -1,7 +1,9 @@
 package dev.picasso.ops.host
 
 import com.fasterxml.jackson.databind.JsonNode
+import dev.picasso.contracts.v1.ParameterValue
 import dev.picasso.mimic.engine.ForceOutcome
+import dev.picasso.mimic.engine.StartOutcome
 import dev.picasso.mimic.engine.TaskState
 import dev.picasso.ops.host.HostBench.Companion.HUMANOID
 import dev.picasso.ops.host.HostBench.Companion.JSON
@@ -169,6 +171,40 @@ class HostRestoreTest {
         }
     }
 
+    @Test
+    fun `포기한 행의 작업 지시 태스크가 사람을 기다리는 NEEDS_INTERVENTION 이면 종료 상태가 아니라 그 기체를 계속 판정에서 뺀다`() {
+        HostBench().use { bench ->
+            // InspectAsset 의 최고 근거 등급은 E0 이라 E2 를 요구하는 일지 행은 포기한다. 그 작업 지시의 태스크를 기체에 직접 세운다.
+            insertJournal("JO-E2", QUADRUPED, evidence = "E2")
+            val taskId = "JO-E2#T1"
+            val location = ParameterValue.newBuilder().setKey("location").setStringValue("bay-7").build()
+            val started = bench.mimic.server.exclusive { bench.mimic.instance(QUADRUPED)!!.tasks.start(taskId, 1, "navigate_to", listOf(location)) }
+            assertTrue(started is StartOutcome.Accepted, started.toString())
+            repeat(20) {
+                if (bench.tasks(QUADRUPED)[taskId] != TaskState.RUNNING) {
+                    bench.mimic.server.advance(Duration.ofSeconds(1))
+                    bench.awaitPump()
+                }
+            }
+            assertEquals(TaskState.RUNNING, bench.tasks(QUADRUPED)[taskId])
+            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(QUADRUPED)!!.tasks.forceFault("LOCALIZATION_LOST", taskId) }
+            assertEquals(TaskState.NEEDS_INTERVENTION, (outcome as ForceOutcome.Raised).taskState, outcome.toString())
+
+            bench.restartHost()
+            assertEquals(
+                listOf("JO-E2" to "GAVE_UP"),
+                bench.get("/host/executions")["restore"]["rows"].map { it["jobOrderId"].asText() to it["result"].asText() },
+            )
+            fun reasons() =
+                bench.post("/host/eligibility", request(inspect("JO-9", "T2" to "dock-3"), "robotIds", QUADRUPED)).body!!["robots"].single()["reasons"].map { it.asText() }
+            // 스냅숏을 읽을 때마다 같다. 사람을 기다리는 태스크는 종료하지 않은 태스크라 풀지 않는다.
+            assertEquals(listOf("${MissionHost.UNRESTORED_REASON}: JO-E2"), reasons())
+            bench.idlePumps()
+            assertEquals(TaskState.NEEDS_INTERVENTION, bench.tasks(QUADRUPED)[taskId])
+            assertEquals(listOf("${MissionHost.UNRESTORED_REASON}: JO-E2"), reasons())
+        }
+    }
+
     @Test
     fun `재작업한 로봇 단위를 재기동하면 @rN 태스크에 다시 붙고 새 태스크를 내지 않는다`() {
         HostBench().use { bench ->
```

- [ ] **Step 4: 커밋 2/6 `fix(mission-host): 포기한 행을 푸는 태스크 상태를 용어집의 종료 상태 넷으로 한정`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt && git commit -F - <<'EOF'
fix(mission-host): 포기한 행을 푸는 태스크 상태를 용어집의 종료 상태 넷으로 한정

- `TERMINAL_TASK_STATES` 에서 RETRIABLE·NEEDS_INTERVENTION 제외
- 사람을 기다리는 태스크가 남은 기체의 판정 제외 유지
- 포기한 행의 태스크가 NEEDS_INTERVENTION 인 기체의 재기동 뒤 판정 시험
- 주석의 옛 이름 `종착`·`비종착` 을 `종료`·`종료하지 않은` 으로 정정

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 5: (커밋 3/6) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b-c.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b-c.patch"
```

```diff
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt
index b0cb46b..a906c95 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt
@@ -39,7 +39,9 @@ class HostRestoreTest {
 
             bench.restartHost()
             val after = bench.host.instanceId
-            val restore = bench.get("/host/executions")["restore"]
+            val body = bench.get("/host/executions")
+            assertEquals(EXECUTIONS_FIELDS, body.fieldNames().asSequence().toList())
+            val restore = body["restore"]
             assertEquals(listOf("at", "rows"), restore.fieldNames().asSequence().toList())
             val row = restore["rows"].single()
             assertEquals(RESTORE_ROW_FIELDS, row.fieldNames().asSequence().toList())
@@ -53,6 +55,8 @@ class HostRestoreTest {
             // 가상 시계를 밀지 않고 pump 가 기체를 다시 관측하면 끝난 단위는 끝난 대로, 도는 단위는 도는 대로 선다. 새 태스크는 없다.
             bench.eventually("단위 다시 관측") { units(bench.execution("exec-1")!!) == mapOf("T1.travel" to "DONE", "T1" to "RUNNING", "T2.travel" to "PENDING", "T2" to "PENDING") }
             val execution = bench.execution("exec-1")!!
+            assertEquals(EXECUTION_FIELDS, execution.fieldNames().asSequence().toList())
+            assertEquals(listOf("instanceId", "executionId"), execution["restoredFrom"].fieldNames().asSequence().toList())
             assertEquals(JSON.readTree("""{"instanceId":"$before","executionId":"exec-1"}"""), execution["restoredFrom"])
             assertTrue(execution["missionVersion"].isNull)
             assertEquals(tasksBefore, bench.tasks(HUMANOID))
@@ -268,6 +272,12 @@ class HostRestoreTest {
         const val WAIT = "rack-arrival"
         const val S01 = "RACK-204.S01"
 
+        val EXECUTIONS_FIELDS = listOf("instanceId", "pumpedAt", "executions", "restore")
+
+        val EXECUTION_FIELDS = listOf(
+            "executionId", "jobOrderId", "workMasterId", "missionVersion", "robotId", "physicalState", "units", "jobResponse", "restoredFrom",
+        )
+
         val RESTORE_ROW_FIELDS = listOf("jobOrderId", "robotId", "previousInstanceId", "previousExecutionId", "result", "executionId", "reason")
 
         fun units(execution: JsonNode): Map<String, String> = execution["units"].associate { it["unitId"].asText() to it["state"].asText() }
```

- [ ] **Step 6: 커밋 3/6 `test(mission-host): 실행 목록 본문과 실행의 칸 순서 단언`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt && git commit -F - <<'EOF'
test(mission-host): 실행 목록 본문과 실행의 칸 순서 단언

- `GET /host/executions` 본문의 칸 순서(instanceId, pumpedAt, executions, restore)
- 실행 칸 순서와 끝자리의 restoredFrom
- restoredFrom 안의 칸 순서(instanceId, executionId)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 7: (커밋 4/6) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b-d.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b-d.patch"
```

```diff
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt
index 83fc8ea..21916ea 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt
@@ -10,6 +10,7 @@ import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
 import dev.picasso.ops.host.HostBench.Companion.inspect
 import dev.picasso.ops.host.HostBench.Companion.rack
 import dev.picasso.ops.host.HostBench.Companion.request
+import dev.picasso.ops.host.web.HostController
 import dev.picasso.registry.PostgresSupport
 import java.time.Duration
 import java.util.UUID
@@ -104,6 +105,28 @@ class HostJournalTest {
         }
     }
 
+    @Test
+    fun `일지 쓰기가 실패하면 제출은 500 JOURNAL_WRITE_FAILED 이고 실행은 미들웨어에 남는다`() {
+        HostBench().use { bench ->
+            PostgresSupport.execute(
+                """
+                CREATE FUNCTION mission.refuse_journal() RETURNS trigger AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'journal refused'; END; ${'$'}${'$'} LANGUAGE plpgsql
+                """.trimIndent(),
+            )
+            PostgresSupport.execute("CREATE TRIGGER refuse_journal BEFORE INSERT ON mission.execution_journal FOR EACH ROW EXECUTE FUNCTION mission.refuse_journal()")
+            val failed = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID))
+            assertEquals(500, failed.status, failed.body.toString())
+            assertEquals(HostController.JOURNAL_WRITE_FAILED, failed.body!!["error"].asText(), failed.body.toString())
+            assertTrue("exec-1" in failed.body["detail"].asText(), failed.body.toString())
+            assertTrue(journal().isEmpty())
+            // 실행은 미들웨어에 남는다(스펙 §9, 한계). 일지에 없으니 다음 기동은 다시 짓지 않는다.
+            val execution = checkNotNull(bench.execution("exec-1")) { bench.get("/host/executions").toString() }
+            assertEquals(listOf("JO-1", HUMANOID), listOf(execution["jobOrderId"].asText(), execution["robotId"].asText()))
+            bench.restartHost()
+            assertTrue(bench.get("/host/executions")["restore"]["rows"].isEmpty)
+        }
+    }
+
     @Test
     fun `pump 뒤 기록은 한 트랜잭션이라 사본 쓰기가 실패하면 송신 기록도 남지 않고 ack 하지 않아 다음 pump 에 한 번씩 적는다`() {
         HostBench().use { bench ->
```

- [ ] **Step 8: 커밋 4/6 `test(mission-host): 일지 쓰기 실패의 제출 응답 시험`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt && git commit -F - <<'EOF'
test(mission-host): 일지 쓰기 실패의 제출 응답 시험

- 일지 삽입을 거절하는 트리거 아래의 제출 응답 500 `JOURNAL_WRITE_FAILED`
- 실패한 제출의 실행이 미들웨어에 남는 것과 빈 일지
- 다음 기동의 복원 보고에 그 실행이 없는 것

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 9: (커밋 5/6) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b-e.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b-e.patch"
```

```diff
diff --git a/mission-host/src/main/resources/db/mission/V2__execution_journal.sql b/mission-host/src/main/resources/db/mission/V2__execution_journal.sql
index e11d7ba..a313e5d 100644
--- a/mission-host/src/main/resources/db/mission/V2__execution_journal.sql
+++ b/mission-host/src/main/resources/db/mission/V2__execution_journal.sql
@@ -88,43 +88,49 @@ CREATE TABLE incident_copy_resolution (
     FOREIGN KEY (instance_id, incident_id) REFERENCES incident_copy (instance_id, incident_id)
 );
 
--- 덧붙이기만 한다. V1 의 mission_append_only() 를 그대로 쓴다.
+-- 덧붙이기만 한다. V1 의 mission_append_only() 는 임무 버전 저장을 이르므로 이 표 다섯에는 따로 둔다.
+CREATE FUNCTION mission_record_append_only() RETURNS trigger AS $$
+BEGIN
+    RAISE EXCEPTION '실행 일지·송신 기록·인시던트 사본은 덧붙이기만 한다(% %)', TG_OP, TG_TABLE_NAME;
+END;
+$$ LANGUAGE plpgsql;
+
 CREATE TRIGGER execution_journal_no_update_delete
     BEFORE UPDATE OR DELETE ON execution_journal
-    FOR EACH ROW EXECUTE FUNCTION mission_append_only();
+    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();
 
 CREATE TRIGGER execution_journal_no_truncate
     BEFORE TRUNCATE ON execution_journal
-    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();
+    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
 
 CREATE TRIGGER execution_journal_event_no_update_delete
     BEFORE UPDATE OR DELETE ON execution_journal_event
-    FOR EACH ROW EXECUTE FUNCTION mission_append_only();
+    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();
 
 CREATE TRIGGER execution_journal_event_no_truncate
     BEFORE TRUNCATE ON execution_journal_event
-    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();
+    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
 
 CREATE TRIGGER job_response_log_no_update_delete
     BEFORE UPDATE OR DELETE ON job_response_log
-    FOR EACH ROW EXECUTE FUNCTION mission_append_only();
+    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();
 
 CREATE TRIGGER job_response_log_no_truncate
     BEFORE TRUNCATE ON job_response_log
-    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();
+    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
 
 CREATE TRIGGER incident_copy_no_update_delete
     BEFORE UPDATE OR DELETE ON incident_copy
-    FOR EACH ROW EXECUTE FUNCTION mission_append_only();
+    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();
 
 CREATE TRIGGER incident_copy_no_truncate
     BEFORE TRUNCATE ON incident_copy
-    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();
+    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
 
 CREATE TRIGGER incident_copy_resolution_no_update_delete
     BEFORE UPDATE OR DELETE ON incident_copy_resolution
-    FOR EACH ROW EXECUTE FUNCTION mission_append_only();
+    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();
 
 CREATE TRIGGER incident_copy_resolution_no_truncate
     BEFORE TRUNCATE ON incident_copy_resolution
-    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();
+    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt
index eb4aa26..07c06ce 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt
@@ -85,7 +85,7 @@ class MissionStoreTest {
         ).forEach { (table, set) ->
             listOf("UPDATE mission.$table SET $set", "DELETE FROM mission.$table", "TRUNCATE mission.$table CASCADE").forEach { sql ->
                 val e = assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) }
-                assertTrue("덧붙이기만" in e.message!!, "$sql: ${e.message}")
+                assertTrue("실행 일지·송신 기록·인시던트 사본은 덧붙이기만 한다" in e.message!!, "$sql: ${e.message}")
             }
         }
         // 같은 인스턴스·응답 id 는 두 번 적지 못한다. 일지 이벤트 종류는 넷뿐이다.
```

- [ ] **Step 10: 커밋 5/6 `fix(mission-host): S4b 표 다섯의 덧붙이기 전용 트리거 함수 분리`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add mission-host/src/main/resources/db/mission/V2__execution_journal.sql mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt && git commit -F - <<'EOF'
fix(mission-host): S4b 표 다섯의 덧붙이기 전용 트리거 함수 분리

- V2 의 표 다섯 전용 함수 `mission_record_append_only()`
- 거부 문구를 임무 버전 저장이 아닌 실행 일지·송신 기록·인시던트 사본으로 정정
- 표 다섯의 거부 문구 전체를 대는 저장소 시험 단언

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 11: (커밋 6/6) 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b-f.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4b-patches/task5b-f.patch"
```

```diff
diff --git a/ui/e2e/lifecycle.spec.ts b/ui/e2e/lifecycle.spec.ts
index c525913..f5edc08 100644
--- a/ui/e2e/lifecycle.spec.ts
+++ b/ui/e2e/lifecycle.spec.ts
@@ -305,7 +305,8 @@ test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 reg
   const responseLog = page.getByRole('region', { name: '작업 응답 송신 기록' })
   await responseLog.getByLabel('송신 기록의 작업 지시').selectOption(holdJobOrder)
   const sentRows = responseLog.getByRole('table', { name: '송신 기록 목록' }).getByRole('row').filter({ hasText: holdJobOrder })
-  await expect(sentRows.filter({ hasText: 'PHYSICALLY_DONE' })).toContainText('송신')
+  // 처분 칸(열째)은 글자 그대로 송신이다. 재기동 중복의 표시(재기동 중복(송신 안 함))도 송신을 품으므로 포함 검사로는 못 가른다.
+  await expect(sentRows.filter({ hasText: 'PHYSICALLY_DONE' }).getByRole('cell').nth(9)).toHaveText('송신')
   await expect(sentRows.filter({ hasText: '재기동 중복' })).toHaveCount(0)
   await expect(responseLog).toContainText('그 가운데 재기동 중복 0건')
 
diff --git a/ui/src/components/OperationsArea.tsx b/ui/src/components/OperationsArea.tsx
index 3dfac0d..634b960 100644
--- a/ui/src/components/OperationsArea.tsx
+++ b/ui/src/components/OperationsArea.tsx
@@ -118,7 +118,7 @@ export function OperationsArea({ session, onChanged }: Props) {
     }
   }, [session, tick])
 
-  // 송신 기록은 고른 작업 지시가 바뀌어도 다시 읽는다. 앞 작업 지시의 늦은 답은 버린다.
+  // 송신 기록은 고른 작업 지시가 바뀌어도 다시 읽는다. 앞 작업 지시의 늦은 응답은 버린다.
   useEffect(() => {
     let alive = true
     fetchJobResponses(session, responseFilter)
```

- [ ] **Step 12: 커밋 6/6 `test(ui): 송신 기록 처분 칸의 글자 그대로 대조`**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && git add ui/e2e/lifecycle.spec.ts ui/src/components/OperationsArea.tsx && git commit -F - <<'EOF'
test(ui): 송신 기록 처분 칸의 글자 그대로 대조

- Playwright 송신 행 단언을 처분 칸의 정확 일치(`송신`)로 변경
- 재기동 중복 표시가 송신을 품어 포함 검사로 못 가르는 점의 주석
- 운영 영역 주석의 `늦은 답` 을 `늦은 응답` 으로 정정

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 하나. `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 에 이 커밋의 파일이 남지 않는다.

- [ ] **Step 13: 시험(mission-host)**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && ./gradlew :mission-host:test -q
```
Expected: mission-host 79, 실패 0(`HostRestoreTest` 의 포기 행 NEEDS_INTERVENTION 판정 시험과 `HostJournalTest` 의 일지 쓰기 실패 시험이 더해짐). 백그라운드로 돌린다(Docker, Testcontainers).

- [ ] **Step 14: 시험(ui)**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && cd ui && npm test && npm run lint && npx tsc -b && npm run build
```
Expected: vitest 151 통과(수는 그대로, 바뀐 것은 Playwright 단언과 주석), lint·tsc·build 종료 0. Playwright 1 은 Task 6 의 새 클론에서 돌린다.

- [ ] **Step 15: 묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s4b-cmp.sh" --at 0de3ce6 mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostRestoreTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostJournalTest.kt mission-host/src/main/resources/db/mission/V2__execution_journal.sql mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt ui/e2e/lifecycle.spec.ts ui/src/components/OperationsArea.tsx
```
Expected: 7개 모두 `같음`(이 Task 의 마지막 스파이크 커밋 `0de3ce6` 의 파일과 바이트 대조, 뒤 Task 가 같은 파일을 다시 고칠 수 있어 HEAD 가 아니다).

## Chunk 2: 검증과 병합(컨트롤러)

### Task 6: 트리 대조와 새 클론 빌드

- [ ] **Step 1: 트리와 커밋 대조**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s4b-cmp.sh" --tree
```
Expected: `같음 트리(40개 경로, 스파이크 0de3ce6)`(코드 39개와 picasso gitlink, 모드와 블롭 해시까지 스파이크 HEAD 와 견줌, docs 브랜치의 문서 넷은 뺌)와 `같음 커밋 메시지(18개)`(`docs(` 커밋을 뺀 커밋 메시지 전체가 계획의 메시지 집합, 곧 스파이크 메시지에 머리말의 다섯 곳 바꿈을 적용한 것과 같음, 기대값은 `s4b-tools/messages.txt`). 그리고 `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short` 가 비어 있다.

- [ ] **Step 2: 새 클론 전체 빌드, 화면 확인, Playwright**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s4b-final.sh"
```
워크트리 브랜치를 스크래치에 새로 클론해(서브모듈 포함) `./gradlew build`, ui 의 `npm ci && npm test && npm run lint && npx tsc -b && npm run build`, installDist 셋과 `npx playwright test` 를 차례로 돌린다. 백그라운드로 돌린다(약 25~30분). 결함 주입(Task 7)과 겹치지 않는다.
Expected: `gradle=0`, site 38, mission-host 79, ops-service 241, e2e 63(모두 `bad 0` 과 `OK`), `ui=0` 과 `vitest expect 151 OK`, `dist=0`, `pw=0` 과 `playwright expect 1 OK`, 끝의 `status --short` 가 비어 있다. 클론의 서브모듈이 `74e4d3d`.

### Task 7: 대표 결함 주입(컨트롤러)

대표 주입 5건(전체 H33·O14·U17·P2·I9 는 스파이크에서, I4 등가). 바이트 대조로 스파이크와 같은 코드이므로 모듈마다 대표 하나만 돌린다. 호스트 H1~H27 은 스파이크에서 확정 전 P6 스냅숏(`e5011eb`, `ResumeTest` 13개)에 대고 돌렸고, 그 뒤 호스트 시험은 확정 P6 에서 통과했다. 여기의 대표 H5 는 확정 `74e4d3d` 에 대고 돈다. Task 6 이 끝난 뒤에만 돌린다(Gradle·Playwright 포트가 겹친다). 실행기는 바늘을 바꿔 넣고 시험을 돌린 뒤 파일을 늘 되돌린다.

- [ ] **Step 1: 호스트·운영 서비스·화면·통합 대표 넷**

```bash
cd "C:/Users/Eisen/AppData/Local/Temp/s4b-inject" && S4B_ROOT="C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" PYTHONUTF8=1 python inject.py H5 O7 U8 I7
```
Expected: 넷 다 `DETECTED`. 백그라운드로 돌린다(약 5분).
- H5(새 인스턴스의 첫 응답만이 아니라 모든 응답을 최근 송신과 견줌): `HostRestoreTest` 의 `운영자 보류에 선 실행을 두 번 다시 띄워도 새 인스턴스의 첫 보류 응답은 재기동 중복이고 같은 인스턴스의 다음 보류는 송신이다()` 실패. 이 시험은 `Middleware.resume` 으로 두 번 다시 짓는다(H14 는 I7 과 같은 변이라 대표로 쓰지 않는다)
- O7(재조회가 재기동 뒤에도 `incidents` 만 봄): `IncidentOperationsTest` 의 `판단 재조회는 목록의 인스턴스가 요청한 인스턴스와 다르면 incidents 가 아니라 earlier 의 그 인스턴스 사본만 본다()` 와 `판단 재조회의 반영 안 됨 관측은 요청한 인스턴스의 그 단위 가장 최근 사본이다()` 실패
- U8(화면 판단 본문에 `instanceId` 를 싣지 않음): `IncidentSection.test.tsx` 의 `재기동 뒤 인시던트 > 판단은 상세의 instanceId 를 싣고 ...` 와 `인시던트 > 판단은 실행과 단위 경로로 ...` 실패
- I7(호스트 판단 REST 가 이전 인스턴스를 실은 판단을 받음): e2e `RestartRecoveryTest` 의 `단계 6 재기동 뒤 이전 인스턴스의 인시던트와 판단이 보이고 이전 인스턴스를 실은 판단은 INSTANCE_MISMATCH 로 거부된다()` 실패

- [ ] **Step 2: Playwright 대표 하나**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && ./gradlew :site:installDist :ops-service:installDist :mission-host:installDist -q
cd "C:/Users/Eisen/AppData/Local/Temp/s4b-inject" && S4B_ROOT="C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" PYTHONUTF8=1 python inject.py P1
```
Expected: P1(송신 행의 처분 글자 `송신` 을 바꿈) `DETECTED`, `1 failed`, 실패 줄 `toHaveText('송신')`(Task 5b 의 처분 칸 정확 일치 단언). 백그라운드로 돌린다(약 5분).

- [ ] **Step 3: 되돌림 확인**

```bash
git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" status --short
```
Expected: 비어 있다. 실행을 도중에 멈췄다면 바늘을 바꿔 넣은 파일 하나만 `M` 으로 남을 수 있다. 그때는 그 파일만 `git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" checkout -- <그 경로>` 로 되돌리고(다른 파일은 건드리지 않는다) `bash "C:/Users/Eisen/AppData/Local/Temp/s4b-cmp.sh" <그 경로>` 로 `같음` 을 본다.

### Task 8: 푸시, PR, 병합(컨트롤러)

- [ ] **Step 1: 푸시**

```bash
git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" log --oneline 8ed1b91..HEAD && git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" push -q -u origin feat/s4b-restart-recovery
```
Expected: docs 커밋(스펙·계약·계획·P6 계획) 위에 코드 커밋 18개(Task 1 하나, Task 2 넷, Task 3 하나, Task 4 둘, Task 5 넷, Task 5b 여섯). 커밋은 스택 그대로 둔다(스쿼시 안 함).

- [ ] **Step 2: PR**

PR 본문 문장은 Codex 와 Fable 초안을 취합한다. 브리프에 넣을 것: 용어집 새 이름, 개조식 명사형 불릿, Task 6·7 의 실측 수, 그리고 형식 훅의 PR 본문 규칙. 훅 규칙은 셋이다. 절은 `## 개요`·`## 주요 변경 사항`·`## 검증 결과` 셋뿐이고 이 순서다. 소절은 `### 1. 제목` 꼴만 쓴다(번호 없는 `### 제목` 은 막힌다). 펜스 밖의 어느 줄도 `다.` 나 `다` 로 끝나지 않는다(합니다체나 명사형). em-dash·en-dash·겹화살괄호·낫표도 막힌다. 끝 줄은 Claude Code 표기다.
- `## 개요`: 스펙 §1 의 목적(제안 §10 입증 항목 5 의 재시작 부분), 이 PR 이 더하는 것, 범위 밖(스펙 §1 표), picasso 는 서브모듈 포인터만 `195c1ee` → `74e4d3d`(P6, picasso PR #86)
- `## 주요 변경 사항`: `### 1. 문서`, `### 2. 실행 호스트`, `### 3. 운영 서비스`, `### 4. 화면`, `### 5. site·통합 시험·Playwright` 꼴의 소절(계획 검토 고침은 해당 소절에 넣는다)
- `## 검증 결과`: 새 클론 빌드의 수(Task 6, mission-host 79), 묶음 대조와 트리 대조 결과, 결함 주입(스파이크 75건 가운데 I4 등가, 워크트리 대표 5건)

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4b" && gh pr create --base main --head feat/s4b-restart-recovery --title "feat(mission-host): 호스트 재기동 뒤 실행 복원과 중복 작업 응답 막기" --body-file - <<'EOF'
## 개요

(취합한 문장)

## 주요 변경 사항

(취합한 문장)

## 검증 결과

(취합한 문장)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
```
Expected: PR URL. 괄호 자리는 취합한 문장으로 바꾼 뒤 돌린다. 형식 훅은 `--body-file -` 의 표준 입력만 읽는다. 훅이 막으면 문장을 고쳐 다시 돌린다(`--no-verify` 금지).

- [ ] **Step 3: CI 한 번 확인**

```bash
gh pr checks <PR 번호> -R LivingLikeKrillin/picasso-ops --watch --interval 60
```
Expected: `gradle`, `playwright`, `ui` 셋 다 `pass`. 백그라운드로 돌린다. 하나라도 실패하면 병합하지 않고 그 잡의 로그를 읽어 보고한다.

- [ ] **Step 4: 병합과 메인 체크아웃 갱신**

```bash
gh pr merge <PR 번호> -R LivingLikeKrillin/picasso-ops --merge
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" && git status --short && git fetch -q origin && git merge --ff-only origin/main && git submodule update --init -q && git log --oneline -1 && git -C picasso log --oneline -1
```
Expected: 머지 커밋(`--delete-branch` 쓰지 않음, 이 저장소는 자동 머지가 꺼져 있다). 메인 체크아웃 `status --short` 에 추적 파일의 변경이 없고(있으면 멈추고 보고), 병합 뒤 `main` 이 머지 커밋, 서브모듈이 `74e4d3d`. 워크트리와 브랜치는 지우지 않는다.

## 실행 결과

- 수행: picasso-ops 워크트리 `picasso-ops-wt/s4b` 에서 묶음 셋(Task 1·2, Task 3·4, Task 5·5b)을 하위 에이전트(Sonnet)가 수행, 블록은 계획에서 기계로 뽑아 둔 파일을 복사·적용, 묶음마다 커밋된 파일을 스파이크와 바이트 대조해 46개 모두 같음(15, 9, 12, 3, 7)과 서브모듈 포인터 같음, 트리(40개 경로)와 커밋 메시지 18개가 스파이크와 같음(스파이크 전용 글자 다섯 곳만 뺌), Expected 와 다른 곳 없음, README 는 워크트리에서 따로 고침
- 결함 주입: 전체 75건(호스트 33, 운영 서비스 14, 화면 17, Playwright 2, 통합 9)을 스파이크에서 돌려 74건 탐지와 등가 변이 1건(I4, 보류 응답의 미완 사유는 실패 분류라 인스턴스와 무관), 호스트 H1~H27 은 확정 전 P6 스냅숏에서 돌렸고 호스트 시험은 확정 P6 에서 통과, 워크트리는 새 클론 빌드 뒤 대표 5건(H5, O7, U8, I7, P1) 모두 탐지
- 새 클론 빌드: Gradle 시험 421 실패 0(site 38, mission-host 79, ops-service 241, e2e 63), vitest 151, lint·tsc·build 통과, installDist 셋과 Playwright 1 통과(4.3분)
- 병합: 코드 커밋을 묶음별로 남겨(스택 보존, 스쿼시 없음) picasso-ops PR #14 로 올림, 선행 picasso PR #86(P6, 머지 커밋 `74e4d3d`)
- 스파이크: 영역 넷(picasso P6, 실행 호스트, 운영 서비스·화면·Playwright, site·통합 시험)을 하위 에이전트가 차례로 지음, JSON 계약 메모를 다음 영역의 입력으로 넘김, 스펙 검토 2회(배정 관문이 이미 집어 간 자재를 결품으로 읽어 거부, 스냅숏을 못 읽으면 `@rN` 없는 id 로 다시 붙는 고장, 이전 인시던트의 `exec-k` 가 새 인스턴스의 다른 실행을 가리킴, 송신 거르기의 범위), 계획 검토 1회(문서 브랜치 선행 조건, 훅에 걸리는 커밋 메시지 한 줄, 기체를 푸는 상태를 용어집의 종료 넷으로 한정, 칸 순서와 일지 쓰기 실패 시험, V2 트리거 함수 분리, Playwright 처분 칸 정확 일치), 새 시험이 포기한 행의 판정 이유 중복을 잡아 고침
- 걸린 것: mimic 이 RPC 마다 `ACCEPTED` 를 `RUNNING` 으로 집어 들어 재기동 앞뒤 태스크 로그를 그대로 견주는 비교가 깨져 세 조건(태스크 id 집합, 재기동 전 로그가 앞에 그대로, `ACCEPTED` 한 번)으로 바꿈, Codex 사용 한도로 문장 초안을 Fable 과 Gemini 에서 받음, 긴 한국어 브리프가 Gemini 명령줄 한도에 걸려 둘로 나눠 보냄
- 다음: picasso-ops 전체 화면의 화면 설계서(디자이너 리터치용)
