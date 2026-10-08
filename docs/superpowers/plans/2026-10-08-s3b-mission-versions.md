# S3b 임무 버전 저장, 편집, 모의 실행, 활성화 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking. 단, 이 계획은 묶음(Task 1·2 / Task 3 / Task 4 / Task 5·6)마다 구현자 하나가 하고, 검토는 묶음이 끝난 뒤 컨트롤러가 기계 대조와 시험으로 한다.

**Goal:** 엔지니어가 화면의 «임무·정책» 영역에서 PrepareSequencedRack 정의를 JSON 으로 편집해 초안으로 저장하고, 검증하고, 가상 기체로 모의 실행하고, 사유를 적어 활성화한다. 활성 버전은 실행 호스트 DB 에 남아 재기동 뒤에도 같은 번호다. 도는 실행 중에 새 버전을 활성화하면 새 작업 지시부터 새 버전을 쓰고 옛 실행은 옛 버전으로 끝난다. 신호 사양에 없는 신호를 참조한 초안은 활성화에서 거부되고 화면이 부족한 조건과 바로 갈 작업을 보인다. 설비 대기 노드는 운영 영역의 셀 대역 표에서 신호를 켜면 풀린다.

**Architecture:** site 의 셀 대역이 신호 셋과 그 사양을 선언하고 루프백으로 신호 쓰기를 받는다(안전 신호는 거부). 실행 호스트는 `mission` 스키마의 추가 전용 표 셋(초안, 모의 실행, 버전)을 들고 picasso `MissionCatalog` 를 직접 구현해 재기동 때 버전 번호를 복원한다. 검증은 셀 대역 스냅숏의 신호 사양과 시운전 완료 기체들의 케이퍼빌리티로 하고, 모의 실행은 호스트 안의 별도 mimic 과 미들웨어로 이상적 현장에서 끝까지 돌리며, 활성화는 호스트 잠금 아래에서 한다. 운영 서비스는 엔지니어 모드 관문, 요청 id 재조회, 거부 카드 변환, 두 모드의 신호 조작을 맡고, 화면은 «임무·정책» 영역과 셀 대역 신호 표를 연다.

**Tech Stack:** Kotlin, Spring Boot(BOM), Postgres·Flyway(`db/mission`), JDK `HttpServer`, picasso(`MissionDefinitionParser`, `MissionValidator`, `DefinedCapability`, `MissionCatalog`, `Middleware`, harness `Harness`), React·TypeScript·vitest, Playwright, JUnit5 + kotlin.test, Testcontainers.

**근거 스펙:** `docs/superpowers/specs/2026-10-08-s3b-mission-versions-design.md`(스펙 검토 2회)와 요청·응답 모양 `docs/superpowers/specs/2026-10-08-s3b-json-contract.md`(S3a JSON 계약의 §1·§6·§7·§8·§9.5·§9.6 과 공통 규칙 JDBC 줄을 대체).

**스펙이 계획에 맡긴 것과 이 계획이 정한 것(스파이크에서 정함):**
- site: `lot_code` 처음 값 `"LOT-0001"`. `POST /cell/signals/{name}` 의 판정 순서는 형식 415 `UNSUPPORTED_MEDIA_TYPE` → 본문 400 `BAD_REQUEST` → 이름 404 `UNKNOWN_SIGNAL` → 안전 403 `SAFETY_SIGNAL_READ_ONLY` → 값 400 `SIGNAL_VALUE_INVALID`.
- 실행 호스트: 모의 실행 실패 하위 범주 다섯(스펙의 넷에 `SUBMISSION_REJECTED`, 정의의 `maxEvidence` 가 E2 미만이면 표본 작업 지시가 거부됨). `INPUT_UNKNOWN` 의 입력에 `SAMPLE_ORDER`(스냅숏에 슬롯이나 자재를 든 제시 자리가 없을 때, 모의 실행만). 모의 실행 프로파일은 저장소 파일 `mission-host/mock-run/humanoid-a.json`(하네스가 경로를 받음, 현장 프로파일과 실패 모드만 다른지를 시험이 대조). 현장 본문에 `signals` 칸이 없으면 신호 사양 «모름»(빈 목록과 구별). 모의 실행도 검증 입력을 읽는 동안만 호스트 잠금을 짧게 잡음(`PicassoClient` 케이퍼빌리티 캐시가 잠금 밖에서 안전하지 않음), 하네스 실행은 잠금 밖. 판정 순서 파싱 → WorkMaster 대조 → 입력 → 검증기(문서 수준 문제는 입력을 몰라도 REFUSED). 요청 id 재사용은 409 `REQUEST_ID_REUSED`. 거부·모름으로 끝난 모의 실행은 남기지 않음. `checkedAt` 은 호스트 시계, 행 시각은 DB `clock_timestamp()`. 표본 작업 지시는 스냅숏 앞 슬롯 둘과 자재를 든 첫 제시 자리, id `MOCK-<draftId>`. 진행 루프는 5초 밀기 + 10ms 쉬기, 가상 시간 상한 기본 `PT10M`(설정 키), 실제 시간 30초는 상수(단계 사이에서 확인). 템플릿 id `DATA_V1`·`ARRIVAL_WAIT`. 개요의 `active` 는 `{version, source: CODE|DATA, detail}`. 경로 WorkMaster 가 PrepareSequencedRack 이 아니면 400.
- 운영 서비스: 시운전 완료 기체를 모르면(registry 불통 등) 호스트를 부르지 않고 503 `COMMISSIONED_ROBOTS_UNKNOWN`(사전 거부, 기록 없음), 0대는 빈 목록. 운영 서비스 경로에 WorkMaster 를 넣음(`POST /api/missions/{wm}/drafts/{draftId}/validate|mock-run|activate`). 검증은 조작이 아니라 기록 없음. 쓰기 넷(초안 저장·모의 실행·활성화·신호 조작)은 호스트가 무엇을 답했든 200, 4xx 는 본문 `rejection`. 호스트 503 `CELL_SILENT` 는 NO_RESPONSE 와 재조회. 새 사전 거부 `MISSION_BAD_REQUEST`·`SIGNAL_BAD_REQUEST`. 재조회 대조는 그 조작의 칸이 객체일 때만 반영, 404 는 `REQUEST_NOT_FOUND` 일 때만 «없음». 2xx 인데 본문을 못 읽으면 응답 없음과 재조회. 새 `HostOperationRunner`(S3a `JobOrderOperations` 는 고치지 않음), `guarded` 의 `Set<Mode>` 변형. 조작 기록 `op` 넷 `SAVE_MISSION_DRAFT`·`MOCK_RUN_MISSION_DRAFT`·`ACTIVATE_MISSION_VERSION`·`WRITE_CELL_SIGNAL`.
- 화면: 검증·모의 실행·활성화는 저장한 초안의 글자와 편집기 글자가 같을 때만(다르면 다시 저장하라는 문구), 초안 목록의 «초안 N 열기», 편집기 처음 내용은 활성 버전 또는 DATA_V1, 운영자 모드는 읽기 전용 정의, 비교는 나란히 보기와 «같음/다름», 신호 표의 TEXT 신호는 버튼 없음, 신호 조작 뒤 곧바로와 500ms 뒤에 다시 읽기, 거부 9종과 사전 거부 이름표.
- 통합 시험: 새 시험 클래스 `MissionVersionTest`(순서 있는 4개), `E2eStack.restartHost()`(같은 포트, DB 유지), `JobOrderTest` 의 밀기·몰기를 공용 `ExecutionDriver` 로. 시드 0 에서 humanoid-01 이 `pick_place` 넷을 실패 모드 없이 끝냄(시나리오 약 23초, 클래스 약 47초). 신호를 켜기 전에는 가상 시계를 10초만 밂.
- Playwright: S3a 단계 뒤, site 를 끄기 전에 엔지니어 모드 활성화 단계와 운영자 모드 신호 켜기 단계. CI 는 바꾸지 않음(`run-dist.mjs` 가 `.env` 의 DB 값을 넘기고 루트에서 띄움).
- README: 실행 호스트 저장, 임무·정책 영역, 셀 대역 신호, 모의 실행, 한계.
- 계획 검토(1회)가 잡아 스파이크에 더한 것: 재조회가 처리보다 먼저 와서 «반영 안 됨» 을 남기는 경합(호스트가 처리 중 요청 id 를 들고 재조회에 409 `REQUEST_IN_PROGRESS`, 운영 서비스는 «확인 못 함»), 호스트의 현장 신호 쓰기 요청 제한 1초를 4초로, 화면의 다시 읽기 타이머 정리·개요 전 저장 막기·활성화 성공 때만 사유 지우기, 활성화의 기본 키 충돌을 요청 id 재사용으로 옮기던 자리 제거, 용어(명사 «답» → «응답»).
- 실측: site +7(32), mission-host +35(49), ops-service +39(199), e2e +4(44), vitest +25(99), Playwright 1. 결함 주입 57건(site 5, 호스트 15, 운영 서비스 24, 화면 10, 통합 3).
- 문장: 커밋·PR·문서 문장은 Codex 와 Fable 초안을 취합한다.

**작업 위치 규칙(필수):**
- 모든 작업은 picasso-ops 워크트리 `C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b`(브랜치 `feat/s3b-mission-versions`)에서 한다. picasso-ops 메인 체크아웃과 다른 저장소는 건드리지 않는다. 하위 에이전트의 Bash 는 호출마다 작업 디렉터리가 돌아가므로 명령마다 `cd <워크트리> &&` 를 붙이거나 `git -C` 를 쓴다.
- `./gradlew --stop` 금지(데몬 풀이 다른 체크아웃과 공유된다). 같은 워크트리에서 Gradle 을 겹쳐 돌리지 않는다. site·mission-host·ops-service·e2e 시험은 Testcontainers 로 JVM 마다 Postgres 컨테이너를 띄우므로 Docker 데몬이 떠 있어야 한다. Playwright 는 compose 의 Postgres(`127.0.0.1:55432`)와 고정 포트(8781~8785, 4173)를 쓰므로 다른 Playwright·런처와 겹쳐 돌리지 않는다. Bash 도구의 시간 한도(600초)를 넘는 명령은 백그라운드로 돌리고 끝났다는 알림을 받은 뒤 다음 명령을 돌린다.
- `git add -A` 금지. 파일을 이름으로 더한다.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다. Expected 의 수는 `<testcase>` 수다. 수는 `PYTHONUTF8=1 python -c "import glob,xml.etree.ElementTree as E;print(sum(len(list(E.parse(f).getroot().iter('testcase'))) for f in glob.glob('C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b/MOD/build/test-results/test/*.xml')))"` 로 센다(MOD 자리에 모듈 이름). vitest 는 `npm test` 출력의 `Tests` 줄로 센다.
- 이 저장소는 LF 다(`.gitattributes` 의 `eol=lf`). 뽑아 둔 파일은 그대로 복사한다.
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. 커밋 메시지는 heredoc(`git commit -F - <<'EOF'`)으로 쓴다. 형식 훅이 제목이 `type(scope): 명사구` 가 아니거나 트레일러가 없거나 겹화살괄호가 있으면 막는다.
- 이 계획의 코드는 스크래치 스파이크(`C:/Users/Eisen/AppData/Local/Temp/s3b`, 브랜치 `spike/s3b`, HEAD `2383ac3`)에서 시험, 전체 빌드, Playwright, 결함 주입을 다 돌린 것이다. 묶음이 끝날 때마다 커밋된 파일을 스파이크와 기계 대조한다(`s3b-cmp.sh`).
- 실행 방식: 묶음(Task 1·2 / Task 3 / Task 4 / Task 5·6)마다 구현 하위 에이전트 1명(`model: "sonnet"`), 결함 주입(Task 7)과 검토·PR 은 컨트롤러.
- **블록을 손으로 옮겨 적지 않는다.** 컨트롤러가 이 계획의 블록을 기계로 뽑아 `C:/Users/Eisen/AppData/Local/Temp/s3b-patches/` 에 두었다. 새 파일은 `s3b-patches/files/<경로>` 를 워크트리의 같은 경로로 `cp` 하고, 기존 파일은 `s3b-patches/<이름>.patch` 를 `git apply --check` 로 본 뒤 `git apply` 한다. 아래 블록은 읽고 검토하기 위한 것이다. 뽑은 파일이 없으면 멈추고 보고한다.
- Step 은 순서대로 하나씩 끝내고 다음으로 간다. 다음 Task 의 파일을 미리 복사하거나 패치하지 않는다.

---

## Chunk 1: 구현

### Task 0: 워크트리와 기준선(컨트롤러)

**Files:** 없음(환경)

- [ ] **Step 1: 워크트리**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" && git fetch -q origin
git -C "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" worktree add "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" -b feat/s3b-mission-versions docs/s3b-mission-versions-design
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && git submodule update --init -q && git -C picasso log --oneline -1
```
Expected: 워크트리의 기준은 `docs/s3b-mission-versions-design`(스펙·계약·계획 커밋, 그 아래 `d16b19b`). 서브모듈이 `1e3f4ae Merge pull request #83`(바꾸지 않는다).

- [ ] **Step 2: 기준선 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && ./gradlew :site:test :mission-host:test :ops-service:test :e2e:test -q
```
Expected: site 25, mission-host 14, ops-service 160, e2e 40, 실패 0. 백그라운드로 돌린다. 이어서 `cd ui && npm ci && npm test` 로 vitest 기준선 74.

- [ ] **Step 3: 대조 도구와 뽑은 블록**

`C:/Users/Eisen/AppData/Local/Temp/s3b-cmp.sh` 와 `C:/Users/Eisen/AppData/Local/Temp/s3b-patches/`(패치 6개, `files/` 아래 새 파일 36개)가 있는지 본다. 없으면 멈추고 보고한다.

### Task 1: site: 셀 대역 신호와 신호 조작

**Files:**
- Modify: `site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt`, `site/src/test/kotlin/dev/picasso/ops/site/SiteCellTest.kt`

- [ ] **Step 1: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/task1.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/task1.patch"
```

```diff
diff --git a/site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt b/site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt
index 237f9eb..8c92591 100644
--- a/site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt
+++ b/site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt
@@ -1,5 +1,6 @@
 package dev.picasso.ops.site
 
+import com.fasterxml.jackson.databind.JsonNode
 import com.fasterxml.jackson.databind.ObjectMapper
 import com.sun.net.httpserver.HttpExchange
 import com.sun.net.httpserver.HttpServer
@@ -10,23 +11,71 @@ import java.net.InetSocketAddress
 import java.time.Instant
 
 /**
- * 셀 대역의 고정 픽스처(S3a 스펙 §6.3). 제시 자리는 늘 점유이고 자재가 바뀌지 않는다(공급이 끝나지 않는다).
- * 슬롯은 처음에 비어 있다.
+ * 이름 있는 신호 값의 종류(S3b 스펙 §5.1). picasso 신호 사양의 종류와 이름이 같다. 현장은 picasso 미들웨어에 의존하지
+ * 않으므로 따로 둔다. [BOOLEAN] 이면 값은 `true`·`false` 둘이고 [TEXT] 는 어떤 문자열이든 된다.
+ */
+enum class SignalKind { BOOLEAN, TEXT }
+
+/**
+ * 셀 대역 픽스처가 선언하는 이름 있는 신호 하나(S3b 스펙 §5.1, 결정 2). 신호 사양(이름, 자리, 종류, 안전)과 처음 값을
+ * 함께 든다. 실행 호스트는 스냅숏의 신호 목록을 읽어 임무 정의를 검증하므로 신호 사양은 이 선언 하나에서 나온다.
+ *
+ * @param location 그 신호를 내는 자리. 없으면 `null` 이다.
+ * @param safety 안전 신호인가. 안전 신호는 소프트웨어에서 쓸 수 없다(ADR 32). 셀 대역이 쓰기를 거부한다.
+ * @param initial 처음 값. 값은 종류와 상관없이 문자열이다.
+ */
+data class SignalFixture(
+    val name: String,
+    val kind: SignalKind,
+    val location: String? = null,
+    val safety: Boolean = false,
+    val initial: String,
+) {
+    init {
+        require(name.isNotBlank() && '/' !in name) { "신호 이름이 비었거나 '/' 를 든다: '$name'" }
+        require(kind != SignalKind.BOOLEAN || initial in BOOLEAN_VALUES) { "BOOLEAN 신호 $name 의 처음 값이 true·false 가 아니다: $initial" }
+    }
+
+    companion object {
+        /** BOOLEAN 신호가 받는 값. picasso 검증기의 SIGNAL_VALUE_INVALID 와 같은 둘이다. */
+        val BOOLEAN_VALUES: Set<String> = setOf("true", "false")
+    }
+}
+
+/**
+ * 셀 대역의 고정 픽스처(S3a 스펙 §6.3, S3b 스펙 §5.1). 제시 자리는 늘 점유이고 자재가 바뀌지 않는다(공급이 끝나지 않는다).
+ * 슬롯은 처음에 비어 있다. 이름 있는 신호는 처음 값으로 시작하고 `POST /cell/signals/{name}` 으로만 바뀐다.
  *
  * @param presentations 제시 자리 id 와 그 자리의 자재.
  * @param slots 슬롯 id. 순서가 `GET /cell` 의 순서다.
+ * @param signals 이름 있는 신호의 선언. 순서가 `GET /cell` 의 순서다.
  */
-data class CellFixture(val presentations: Map<String, String>, val slots: List<String>) {
+data class CellFixture(
+    val presentations: Map<String, String>,
+    val slots: List<String>,
+    val signals: List<SignalFixture> = emptyList(),
+) {
     init {
         require(slots.distinct().size == slots.size) { "슬롯 id 가 겹친다: $slots" }
         require(presentations.keys.none { it in slots }) { "제시 자리와 슬롯이 같은 id 를 쓴다" }
+        require(signals.map { it.name }.distinct().size == signals.size) { "신호 이름이 겹친다: ${signals.map { it.name }}" }
     }
 
     companion object {
-        /** 런처와 시험이 쓰는 세트. 슬롯 넷이 PrepareSequencedRack 작업 지시 하나의 단위 수 상한이다. */
+        /**
+         * 런처와 시험이 쓰는 세트. 슬롯 넷이 PrepareSequencedRack 작업 지시 하나의 단위 수 상한이다.
+         *
+         * 신호 셋은 picasso 시험 픽스처 `MissionFixtures.SIGNALS` 와 같은 사양이다. `rack_present` 는 랙 자리 `RACK-204` 가
+         * 내고 처음에는 랙이 없다(`false`). `guard_closed` 는 안전 신호이고 처음에 닫혀 있다(`true`). `lot_code` 는 텍스트 신호다.
+         */
         val STANDARD = CellFixture(
             presentations = linkedMapOf("SEQ-IN-02.BIN-A" to "ENGINE-COVER-A"),
             slots = listOf("RACK-204.S01", "RACK-204.S02", "RACK-204.S03", "RACK-204.S04"),
+            signals = listOf(
+                SignalFixture("rack_present", SignalKind.BOOLEAN, location = "RACK-204", initial = "false"),
+                SignalFixture("guard_closed", SignalKind.BOOLEAN, safety = true, initial = "true"),
+                SignalFixture("lot_code", SignalKind.TEXT, initial = "LOT-0001"),
+            ),
         )
     }
 }
@@ -37,8 +86,28 @@ data class CellFixture(val presentations: Map<String, String>, val slots: List<S
  */
 data class CellPlace(val id: String, val occupied: Boolean, val material: String?, val observedAt: Instant?)
 
-/** 셀 대역의 한 순간. 불변이며 훑기마다 통째로 갈아 끼운다. `GET /cell` 의 본문 모양이다. */
-data class CellSnapshot(val presentations: List<CellPlace>, val slots: List<CellPlace>)
+/**
+ * 이름 있는 신호 하나의 지금 값(S3b 스펙 §5.2). 사양 칸 넷([name]·[location]·[kind]·[safety])은 픽스처 그대로이고
+ * [value]·[observedAt] 이 바뀐다. [observedAt] 이 `null` 이면 아직 쓴 적이 없는 처음 값이고 읽은 순간이 그 시각이다.
+ */
+data class CellSignal(
+    val name: String,
+    val location: String?,
+    val kind: SignalKind,
+    val safety: Boolean,
+    val value: String,
+    val observedAt: Instant?,
+)
+
+/** 셀 대역의 한 순간. 불변이며 훑기와 신호 쓰기마다 통째로 갈아 끼운다. `GET /cell` 의 본문 모양이다. */
+data class CellSnapshot(val presentations: List<CellPlace>, val slots: List<CellPlace>, val signals: List<CellSignal> = emptyList())
+
+/** 신호 쓰기의 결과. 거부는 그대로 HTTP 응답이 된다. */
+sealed interface SignalWrite {
+    data class Written(val signal: CellSignal) : SignalWrite
+
+    data class Refused(val status: Int, val error: String, val detail: String) : SignalWrite
+}
 
 /**
  * 셀 대역(S3a 스펙 §6.3, 결정 6, T4). 기체가 보고한 배치에서 슬롯을 채우는 **대역**이며 독립 설비 확인이 아니다.
@@ -55,9 +124,16 @@ data class CellSnapshot(val presentations: List<CellPlace>, val slots: List<Cell
  * `object_id` 가 제시 자리가 아니면 자재를 모르는 채 점유로 채운다(기체가 무언가 놓았다는 보고는 맞으므로).
  * `destination` 이 픽스처의 슬롯이 아니면 셀 밖이라 무시한다.
  *
+ * ## 이름 있는 신호(S3b 스펙 §5)
+ *
+ * 사람이 PLC 역할을 하는 정상 조작이다(결정 3). [write] 가 같은 엔진 잠금 아래에서 값을 바꾸고 관측 시각을 지금 가상
+ * 시각으로 둔다. 잠금이 훑기와 쓰기의 순서를 정하므로 한쪽이 다른 쪽의 스냅숏 교체를 덮지 않는다. 안전 신호는 쓰기를
+ * 거부한다. 실제 안전 PLC 를 소프트웨어에서 쓸 수 없는 것과 같게 현장이 집행한다(ADR 32).
+ *
  * ## 내는 곳
  *
- * 루프백 JDK `HttpServer` 의 `GET /cell` 하나다. 처리 스레드는 [snapshot] 만 읽고 엔진에 닿지 않는다.
+ * 루프백 JDK `HttpServer` 의 `GET /cell` 과 `POST /cell/signals/{name}` 이다. `GET` 처리 스레드는 [snapshot] 만 읽고
+ * 엔진에 닿지 않는다. 본문 모양과 오류 이름은 S3b JSON 계약 §1·§2 다.
  *
  * @param port 0 이면 무작위(시험).
  */
@@ -70,10 +146,14 @@ class SiteCell(
     /** 이미 처리한 태스크. 기체마다 태스크 id 공간이 따로이므로 쌍으로 든다. */
     private val seen = mutableSetOf<Pair<String, String>>()
 
+    /** 기체들이 함께 보는 가상 시계. `MimicCli` 가 시계 하나를 만들어 모든 기체에 넘긴다. */
+    private val clock = requireNotNull(mimic.robotIds.minOrNull()?.let { mimic.instance(it) }) { "기체가 없는 현장이다" }.clock
+
     @Volatile
     var snapshot: CellSnapshot = CellSnapshot(
         presentations = fixture.presentations.map { (id, material) -> CellPlace(id, true, material, null) },
         slots = fixture.slots.map { CellPlace(it, false, null, null) },
+        signals = fixture.signals.map { CellSignal(it.name, it.location, it.kind, it.safety, it.initial, null) },
     )
         private set
 
@@ -110,6 +190,26 @@ class SiteCell(
         }
     }
 
+    /**
+     * 이름 있는 신호 하나를 쓴다(S3b 스펙 §5.3). 판정 순서는 이름(404) → 안전(403) → 값(400)이다. 안전 신호는 값이 맞아도
+     * 거부한다. 같은 값으로 다시 써도 관측 시각은 지금 가상 시각으로 바뀐다(PLC 가 다시 읽은 것과 같다).
+     */
+    fun write(name: String, value: String): SignalWrite = mimic.server.exclusive { writeLocked(name, value) }
+
+    private fun writeLocked(name: String, value: String): SignalWrite {
+        val current = snapshot.signals.firstOrNull { it.name == name }
+            ?: return SignalWrite.Refused(404, UNKNOWN_SIGNAL, "셀 대역에 없는 신호다: $name")
+        if (current.safety) {
+            return SignalWrite.Refused(403, SAFETY_SIGNAL_READ_ONLY, "안전 신호 $name 은 소프트웨어에서 쓸 수 없다(ADR 32)")
+        }
+        if (current.kind == SignalKind.BOOLEAN && value !in SignalFixture.BOOLEAN_VALUES) {
+            return SignalWrite.Refused(400, SIGNAL_VALUE_INVALID, "BOOLEAN 신호 $name 은 true·false 만 받는다: '$value'")
+        }
+        val written = current.copy(value = value, observedAt = clock.now())
+        snapshot = snapshot.copy(signals = snapshot.signals.map { if (it.name == name) written else it })
+        return SignalWrite.Written(written)
+    }
+
     private fun handle(exchange: HttpExchange) = try {
         respond(exchange)
     } finally {
@@ -117,28 +217,51 @@ class SiteCell(
     }
 
     private fun respond(exchange: HttpExchange) {
-        val status: Int
-        val body: ByteArray
-        when {
-            exchange.requestURI.path != "/cell" -> {
-                status = 404
-                body = ByteArray(0)
-            }
-            exchange.requestMethod != "GET" -> {
+        val path = exchange.requestURI.path
+        val (status, body) = when {
+            path == "/cell" -> if (exchange.requestMethod == "GET") {
+                200 to json.writeValueAsBytes(wire(snapshot))
+            } else {
                 exchange.responseHeaders.add("Allow", "GET")
-                status = 405
-                body = ByteArray(0)
-            }
-            else -> {
-                exchange.responseHeaders.add("Content-Type", "application/json")
-                status = 200
-                body = json.writeValueAsBytes(wire(snapshot))
+                405 to ByteArray(0)
             }
+            path.startsWith(SIGNALS_PREFIX) && path.length > SIGNALS_PREFIX.length && '/' !in path.substring(SIGNALS_PREFIX.length) ->
+                if (exchange.requestMethod == "POST") {
+                    signal(exchange, path.substring(SIGNALS_PREFIX.length))
+                } else {
+                    exchange.responseHeaders.add("Allow", "POST")
+                    405 to ByteArray(0)
+                }
+            else -> 404 to ByteArray(0)
         }
+        if (body.isNotEmpty()) exchange.responseHeaders.add("Content-Type", "application/json")
         exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
         if (body.isNotEmpty()) exchange.responseBody.write(body)
     }
 
+    /**
+     * `POST /cell/signals/{name}` 하나. 본문은 `{"value": "<문자열>"}` 이다. 값이 문자열이 아니면(BOOLEAN 신호에 JSON
+     * 참거짓을 준 것 포함) 400 이다. 신호 값은 종류와 상관없이 늘 문자열이다.
+     *
+     * `application/json` 만 받는다. 브라우저의 단순 요청(폼·`text/plain`)은 사전 요청 없이 다른 출처에서 올 수 있다.
+     */
+    private fun signal(exchange: HttpExchange, name: String): Pair<Int, ByteArray> {
+        val contentType = exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()
+        if (!contentType.equals("application/json", ignoreCase = true)) {
+            return 415 to rejection(UNSUPPORTED_MEDIA_TYPE, "Content-Type 이 application/json 이 아니다: ${contentType ?: "없음"}")
+        }
+        val node: JsonNode? = runCatching { json.readTree(exchange.requestBody.readAllBytes()) }.getOrNull()
+        val value = node?.takeIf { it.isObject }?.get("value")?.takeIf { it.isTextual }?.asText()
+            ?: return 400 to rejection(BAD_REQUEST, "본문이 {\"value\": \"<문자열>\"} 모양이 아니다")
+        return when (val written = write(name, value)) {
+            is SignalWrite.Written -> 200 to json.writeValueAsBytes(wireSignal(written.signal))
+            is SignalWrite.Refused -> written.status to rejection(written.error, written.detail)
+        }
+    }
+
+    private fun rejection(error: String, detail: String): ByteArray =
+        json.writeValueAsBytes(linkedMapOf("error" to error, "detail" to detail))
+
     override fun close() = server.stop(0)
 
     /**
@@ -148,6 +271,7 @@ class SiteCell(
     private fun wire(snapshot: CellSnapshot): Map<String, Any> = linkedMapOf(
         "presentations" to snapshot.presentations.map(::wirePlace),
         "slots" to snapshot.slots.map(::wirePlace),
+        "signals" to snapshot.signals.map(::wireSignal),
     )
 
     private fun wirePlace(place: CellPlace): Map<String, Any?> = linkedMapOf(
@@ -157,10 +281,28 @@ class SiteCell(
         "observedAt" to place.observedAt?.toString(),
     )
 
-    private companion object {
+    private fun wireSignal(signal: CellSignal): Map<String, Any?> = linkedMapOf(
+        "name" to signal.name,
+        "location" to signal.location,
+        "kind" to signal.kind.name,
+        "safety" to signal.safety,
+        "value" to signal.value,
+        "observedAt" to signal.observedAt?.toString(),
+    )
+
+    companion object {
+        /** 오류 이름(S3b JSON 계약 §2). 실행 호스트가 같은 이름을 그대로 넘긴다. */
+        const val UNKNOWN_SIGNAL = "UNKNOWN_SIGNAL"
+        const val SAFETY_SIGNAL_READ_ONLY = "SAFETY_SIGNAL_READ_ONLY"
+        const val SIGNAL_VALUE_INVALID = "SIGNAL_VALUE_INVALID"
+        const val BAD_REQUEST = "BAD_REQUEST"
+        const val UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE"
+
+        private const val SIGNALS_PREFIX = "/cell/signals/"
+
         /** 계약 카탈로그의 스킬 이름과 파라미터 키. */
-        const val PICK_PLACE = "pick_place"
-        const val P_OBJECT = "object_id"
-        const val P_DESTINATION = "destination"
+        private const val PICK_PLACE = "pick_place"
+        private const val P_OBJECT = "object_id"
+        private const val P_DESTINATION = "destination"
     }
 }
diff --git a/site/src/test/kotlin/dev/picasso/ops/site/SiteCellTest.kt b/site/src/test/kotlin/dev/picasso/ops/site/SiteCellTest.kt
index 0bb844a..1a16c72 100644
--- a/site/src/test/kotlin/dev/picasso/ops/site/SiteCellTest.kt
+++ b/site/src/test/kotlin/dev/picasso/ops/site/SiteCellTest.kt
@@ -14,6 +14,9 @@ import java.net.http.HttpResponse
 import java.nio.file.Path
 import java.time.Duration
 import java.time.Instant
+import java.util.concurrent.CompletableFuture
+import java.util.concurrent.CountDownLatch
+import java.util.concurrent.TimeUnit
 import kotlin.test.Test
 import kotlin.test.assertEquals
 import kotlin.test.assertNotNull
@@ -21,7 +24,7 @@ import kotlin.test.assertNull
 import kotlin.test.assertTrue
 
 /**
- * 셀 대역(S3a 스펙 §6.3). registry 없이 mimic 하나와 셀 대역만 띄우고, 기체에 `pick_place` 를 직접 걸어 가상 시계로 끝낸다.
+ * 셀 대역(S3a 스펙 §6.3)과 이름 있는 신호(S3b 스펙 §5). registry 없이 mimic 하나와 셀 대역만 띄우고, 기체에 `pick_place` 를 직접 걸어 가상 시계로 끝낸다.
  * 시계를 미는 순서는 [Site.advance] 와 같다(민 직후 훑기).
  */
 class SiteCellTest {
@@ -149,7 +152,7 @@ class SiteCellTest {
             assertEquals(200, got.statusCode())
             assertEquals("application/json", got.headers().firstValue("Content-Type").orElse(null))
             val body = json.readTree(got.body())
-            assertEquals(setOf("presentations", "slots"), body.fieldNames().asSequence().toSet())
+            assertEquals(listOf("presentations", "slots", "signals"), body.fieldNames().asSequence().toList())
 
             val source = body["presentations"].single()
             assertEquals(SOURCE, source["id"].asText())
@@ -185,6 +188,161 @@ class SiteCellTest {
         }
     }
 
+    /** `POST /cell/signals/{name}` 한 번. */
+    private fun Bench.post(name: String, body: String, contentType: String = "application/json"): HttpResponse<String> =
+        http.send(
+            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${cell.port}/cell/signals/$name"))
+                .header("Content-Type", contentType)
+                .POST(HttpRequest.BodyPublishers.ofString(body))
+                .build(),
+            HttpResponse.BodyHandlers.ofString(),
+        )
+
+    private fun Bench.signal(name: String): CellSignal = cell.snapshot.signals.single { it.name == name }
+
+    @Test
+    fun `표준 픽스처는 신호 셋을 처음 값으로 내고 GET cell 에 사양과 값이 실린다`() {
+        Bench().use { bench ->
+            assertEquals(
+                listOf(
+                    CellSignal(RACK_PRESENT, "RACK-204", SignalKind.BOOLEAN, false, "false", null),
+                    CellSignal(GUARD_CLOSED, null, SignalKind.BOOLEAN, true, "true", null),
+                    CellSignal(LOT_CODE, null, SignalKind.TEXT, false, "LOT-0001", null),
+                ),
+                bench.cell.snapshot.signals,
+            )
+
+            val got = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${bench.cell.port}/cell")).build(), HttpResponse.BodyHandlers.ofString())
+            val signals = json.readTree(got.body())["signals"]
+            assertEquals(listOf(RACK_PRESENT, GUARD_CLOSED, LOT_CODE), signals.map { it["name"].asText() })
+            val rack = signals[0]
+            assertEquals(listOf("name", "location", "kind", "safety", "value", "observedAt"), rack.fieldNames().asSequence().toList())
+            assertEquals("RACK-204", rack["location"].asText())
+            assertEquals("BOOLEAN", rack["kind"].asText())
+            assertEquals(false, rack["safety"].asBoolean())
+            // 값은 종류와 상관없이 문자열이다.
+            assertTrue(rack["value"].isTextual, rack.toString())
+            assertEquals("false", rack["value"].asText())
+            assertTrue(rack["observedAt"].isNull)
+            assertTrue(signals[1]["location"].isNull)
+            assertEquals(true, signals[1]["safety"].asBoolean())
+            assertEquals("TEXT", signals[2]["kind"].asText())
+        }
+    }
+
+    @Test
+    fun `BOOLEAN 신호를 쓰면 값과 관측 시각이 지금 가상 시각으로 바뀌고 응답은 바뀐 신호 하나다`() {
+        Bench().use { bench ->
+            bench.advance(Duration.ofSeconds(7))
+            val first = bench.post(RACK_PRESENT, """{"value":"true"}""")
+            assertEquals(200, first.statusCode(), first.body())
+            assertEquals("application/json", first.headers().firstValue("Content-Type").orElse(null))
+            val body = json.readTree(first.body())
+            assertEquals(RACK_PRESENT, body["name"].asText())
+            assertEquals("true", body["value"].asText())
+            assertEquals(bench.now().toString(), body["observedAt"].asText())
+            assertEquals(CellSignal(RACK_PRESENT, "RACK-204", SignalKind.BOOLEAN, false, "true", bench.now()), bench.signal(RACK_PRESENT))
+
+            // 시계를 더 민 뒤 다시 쓰면 관측 시각이 따라온다. 다른 신호와 슬롯은 그대로다.
+            bench.advance(Duration.ofSeconds(30))
+            assertEquals(200, bench.post(RACK_PRESENT, """{"value":"false"}""").statusCode())
+            assertEquals("false", bench.signal(RACK_PRESENT).value)
+            assertEquals(bench.now(), bench.signal(RACK_PRESENT).observedAt)
+            assertEquals("LOT-0001", bench.signal(LOT_CODE).value)
+            assertTrue(bench.cell.snapshot.slots.none { it.occupied })
+        }
+    }
+
+    @Test
+    fun `BOOLEAN 신호에 true 나 false 가 아닌 값은 400 이고 값이 그대로다`() {
+        Bench().use { bench ->
+            listOf("yes", "TRUE", "1", "").forEach { value ->
+                val refused = bench.post(RACK_PRESENT, """{"value":"$value"}""")
+                assertEquals(400, refused.statusCode(), value)
+                assertEquals(SiteCell.SIGNAL_VALUE_INVALID, json.readTree(refused.body())["error"].asText())
+            }
+            assertEquals(CellSignal(RACK_PRESENT, "RACK-204", SignalKind.BOOLEAN, false, "false", null), bench.signal(RACK_PRESENT))
+        }
+    }
+
+    @Test
+    fun `안전 신호는 값이 맞아도 쓰기를 403 으로 거부하고 값이 그대로다`() {
+        Bench().use { bench ->
+            listOf("false", "true").forEach { value ->
+                val refused = bench.post(GUARD_CLOSED, """{"value":"$value"}""")
+                assertEquals(403, refused.statusCode(), refused.body())
+                assertEquals(SiteCell.SAFETY_SIGNAL_READ_ONLY, json.readTree(refused.body())["error"].asText())
+            }
+            assertEquals(CellSignal(GUARD_CLOSED, null, SignalKind.BOOLEAN, true, "true", null), bench.signal(GUARD_CLOSED))
+        }
+    }
+
+    @Test
+    fun `모르는 신호는 404 이고 TEXT 신호는 어떤 문자열이든 받는다`() {
+        Bench().use { bench ->
+            val unknown = bench.post("rack_ready", """{"value":"true"}""")
+            assertEquals(404, unknown.statusCode())
+            assertEquals(SiteCell.UNKNOWN_SIGNAL, json.readTree(unknown.body())["error"].asText())
+
+            val text = bench.post(LOT_CODE, """{"value":"LOT 7 / 두 번째"}""")
+            assertEquals(200, text.statusCode(), text.body())
+            assertEquals("LOT 7 / 두 번째", bench.signal(LOT_CODE).value)
+            assertEquals(200, bench.post(LOT_CODE, """{"value":""}""").statusCode())
+            assertEquals("", bench.signal(LOT_CODE).value)
+        }
+    }
+
+    @Test
+    fun `신호 쓰기는 JSON 객체의 문자열 값만 받고 POST 만 받으며 다른 경로는 404 다`() {
+        Bench().use { bench ->
+            listOf("""{"value":true}""", """{"value":null}""", """{"other":"true"}""", """["true"]""", "{not json").forEach { body ->
+                val reply = bench.post(RACK_PRESENT, body)
+                assertEquals(400, reply.statusCode(), body)
+                assertEquals(SiteCell.BAD_REQUEST, json.readTree(reply.body())["error"].asText(), body)
+            }
+            assertEquals(415, bench.post(RACK_PRESENT, """{"value":"true"}""", contentType = "text/plain").statusCode())
+            assertEquals("false", bench.signal(RACK_PRESENT).value)
+
+            val base = "http://127.0.0.1:${bench.cell.port}"
+            val get = http.send(HttpRequest.newBuilder(URI.create("$base/cell/signals/$RACK_PRESENT")).build(), HttpResponse.BodyHandlers.ofString())
+            assertEquals(405, get.statusCode())
+            assertEquals("POST", get.headers().firstValue("Allow").orElse(null))
+            listOf("$base/cell/signals/", "$base/cell/signals/$RACK_PRESENT/x", "$base/cell/x").forEach { url ->
+                val other = http.send(
+                    HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
+                        .POST(HttpRequest.BodyPublishers.ofString("""{"value":"true"}""")).build(),
+                    HttpResponse.BodyHandlers.ofString(),
+                )
+                assertEquals(404, other.statusCode(), url)
+            }
+        }
+    }
+
+    @Test
+    fun `신호 쓰기는 mimic 엔진 잠금을 기다린다`() {
+        Bench().use { bench ->
+            val held = CountDownLatch(1)
+            val release = CountDownLatch(1)
+            val holder = Thread {
+                bench.mimic.server.exclusive {
+                    held.countDown()
+                    release.await()
+                }
+            }.apply { start() }
+            held.await()
+            val write = CompletableFuture.supplyAsync { bench.post(RACK_PRESENT, """{"value":"true"}""").statusCode() }
+            try {
+                Thread.sleep(300)
+                assertTrue(!write.isDone, "엔진 잠금을 쥔 동안 신호 쓰기가 끝났다")
+            } finally {
+                release.countDown()
+                holder.join()
+            }
+            assertEquals(200, write.get(5, TimeUnit.SECONDS))
+            assertEquals("true", bench.signal(RACK_PRESENT).value)
+        }
+    }
+
     private companion object {
         const val HUMANOID = "humanoid-01"
         const val QUADRUPED = "quadruped-01"
@@ -192,6 +350,9 @@ class SiteCellTest {
         const val MATERIAL = "ENGINE-COVER-A"
         const val S01 = "RACK-204.S01"
         const val S02 = "RACK-204.S02"
+        const val RACK_PRESENT = "rack_present"
+        const val GUARD_CLOSED = "guard_closed"
+        const val LOT_CODE = "lot_code"
         val START: Instant = Instant.parse("2026-10-08T00:00:00Z")
     }
 }
```

- [ ] **Step 2: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && ./gradlew :site:test -q
```
Expected: site 32, 실패 0.

- [ ] **Step 3: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && git add site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt site/src/test/kotlin/dev/picasso/ops/site/SiteCellTest.kt && git commit -F - <<'EOF'
feat(site): 셀 대역 이름 있는 신호와 신호 조작 엔드포인트 추가

- 픽스처의 신호 셋과 스냅숏의 신호 칸
- 루프백 `POST /cell/signals/{name}`, 값 검사와 안전 신호 쓰기 거부

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 2: 실행 호스트: 저장, 카탈로그, 검증, 모의 실행, 활성화

**Files:**
- Create: `mission-host/mock-run/humanoid-a.json`, `mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionJudgment.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionTemplates.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionVersions.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionViews.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MockRunner.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/mission/StoredMissionCatalog.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostSchema.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/store/MissionStore.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/web/MissionController.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/web/MissionRequests.kt`, `mission-host/src/main/resources/db/mission/V1__mission_versions.sql`, `mission-host/src/main/resources/mission-templates/PrepareSequencedRack.arrival-wait.json`, `mission-host/src/main/resources/mission-templates/PrepareSequencedRack.data-v1.json`, `mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/MockRunnerTest.kt`
- Modify: `mission-host/build.gradle.kts`, `mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt`, `mission-host/src/main/resources/mission-host.properties`, `mission-host/src/test/kotlin/dev/picasso/ops/host/CellBandTest.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt`

- [ ] **Step 1: 새 파일 17개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/mock-run && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/mock-run/humanoid-a.json" mission-host/mock-run/humanoid-a.json
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/mission && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionJudgment.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionJudgment.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/mission && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionTemplates.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionTemplates.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/mission && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionVersions.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionVersions.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/mission && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionViews.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionViews.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/mission && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MockRunner.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MockRunner.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/mission && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/StoredMissionCatalog.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/mission/StoredMissionCatalog.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/store && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostSchema.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostSchema.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/store && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/store/MissionStore.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/store/MissionStore.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/web && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/web/MissionController.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/web/MissionController.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/web && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/web/MissionRequests.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/web/MissionRequests.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/main/resources/db/mission && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/main/resources/db/mission/V1__mission_versions.sql" mission-host/src/main/resources/db/mission/V1__mission_versions.sql
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/main/resources/mission-templates && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/main/resources/mission-templates/PrepareSequencedRack.arrival-wait.json" mission-host/src/main/resources/mission-templates/PrepareSequencedRack.arrival-wait.json
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/main/resources/mission-templates && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/main/resources/mission-templates/PrepareSequencedRack.data-v1.json" mission-host/src/main/resources/mission-templates/PrepareSequencedRack.data-v1.json
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/test/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt" mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/test/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt" mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p mission-host/src/test/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/mission-host/src/test/kotlin/dev/picasso/ops/host/MockRunnerTest.kt" mission-host/src/test/kotlin/dev/picasso/ops/host/MockRunnerTest.kt
```

`mission-host/mock-run/humanoid-a.json`:

```json
{
  "schema_version": "1.0.0",
  "derived_from": {
    "software_version": "1.0.0",
    "observed_at": "2026-09-05",
    "source": "참조 프로파일이다. 실물 벤더 문서에서 파생하지 않았다(설계 §7.4). 실물 기종이 오면 이 값이 진짜 파생 근거를 담아야 한다."
  },
  "vendor": "picasso-ref",
  "model": "humanoid-a",
  "revision": 2,

  "skills": [
    {
      "skill_type": "navigate_to",
      "major": 1,
      "minor": 0,
      "pause_support": "YES",
      "cancel_support": "YES",
      "parameters": [
        { "key": "location", "value_type": "STRING", "optional": false, "max_length": 256 }
      ],
      "preconditions": [
        { "subject": "HOLD", "requires": "EMPTY" }
      ]
    },
    {
      "skill_type": "pick_place",
      "major": 1,
      "minor": 2,
      "pause_support": "YES",
      "cancel_support": "NO",
      "parameters": [
        { "key": "object_id", "value_type": "STRING", "optional": false, "max_length": 64 },
        { "key": "destination", "value_type": "STRING", "optional": false, "max_length": 64 },
        { "key": "verify_grasp", "value_type": "BOOL", "optional": true },
        { "key": "grip_force", "value_type": "NUMBER", "optional": true, "min_value": 0, "max_value": 120, "unit": "N" }
      ]
    },
    {
      "skill_type": "inspect",
      "major": 1,
      "minor": 3,
      "pause_support": "YES",
      "cancel_support": "YES",
      "parameters": [
        { "key": "target", "value_type": "STRING", "optional": false, "max_length": 64 },
        { "key": "mode", "value_type": "ENUM", "optional": true, "allowed_values": ["VISUAL", "THERMAL"] }
      ]
    }
  ],

  "optional_fields": [
    { "parameter_path": "task.parameters.grip_force", "support": "SUPPORTED" },
    { "parameter_path": "task.parameters.verify_grasp", "support": "REQUIRED" }
  ],

  "publish_interval": { "min_seconds": 1, "max_seconds": 30 },
  "protocol_limits": { "max_string_length": 256, "max_array_length": 32 },
  "exclusive_control_required": true,

  "durations": [
    { "skill_type": "navigate_to", "seconds": 20 },
    { "skill_type": "pick_place", "seconds": 45, "jitter_ratio": 0.1 },
    { "skill_type": "inspect", "seconds": 12 }
  ],

  "failure_modes": [],

  "replay_buffer_size": 256
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionJudgment.kt`:

```kotlin
package dev.picasso.ops.host.mission

import dev.picasso.middleware.FloorOwnership
import dev.picasso.middleware.mission.MissionDefinition
import dev.picasso.middleware.mission.MissionDefinitionParser
import dev.picasso.middleware.mission.MissionParse
import dev.picasso.middleware.mission.MissionRefusal
import dev.picasso.middleware.mission.MissionRefusalKind
import dev.picasso.middleware.mission.MissionValidator
import dev.picasso.middleware.mission.SignalSpec
import dev.picasso.ops.host.cell.CellSnapshot
import java.time.Instant

/** 검증 입력 중 모르는 것(T7). 모름은 거부가 아니다. */
enum class UnknownInput {
    /** 셀 대역 스냅숏이 없거나 그 본문에 신호 목록이 없다. */
    SIGNAL_SPEC,

    /** 넘긴 기체 중 하나라도 케이퍼빌리티를 못 물어봤다. */
    SITE_SKILLS,

    /** 셀 대역 스냅숏에 슬롯이나 자재를 든 제시 자리가 없어 모의 실행의 표본 작업 지시를 만들 수 없다. */
    SAMPLE_ORDER,
}

/**
 * 검증 입력(S3b 스펙 §6.3, T7). 호스트 잠금 아래에서 한 번에 읽는다. 기체 케이퍼빌리티를 묻는 클라이언트가 잠금 밖에서
 * 안전하지 않고, 신호 사양과 현장 스킬이 같은 순간의 것이어야 해서다.
 *
 * @param cell 마지막 pump 가 읽은 셀 대역 스냅숏. 못 읽었으면 `null`.
 * @param skillsByRobot 넘긴 기체마다 선언한 스킬. 못 물어본 기체는 `null`.
 */
data class SiteInputs(val cell: CellSnapshot?, val skillsByRobot: Map<String, Set<String>?>) {

    /** 신호 사양 = 셀 대역 스냅숏의 신호 목록. 모르면 `null`. */
    val signals: List<SignalSpec>? get() = cell?.signalSpecs()

    /** 현장 스킬 = 넘긴 기체들의 스킬 합. 하나라도 모르면 `null` 이다. 모름을 없음으로 접으면 엉뚱한 SKILL_NOT_ON_SITE 가 난다. */
    val siteSkills: Set<String>? get() =
        if (skillsByRobot.values.any { it == null }) null else skillsByRobot.values.flatMap { it.orEmpty() }.toSortedSet()

    /** 케이퍼빌리티를 못 물어본 기체. */
    val unknownRobots: List<String> get() = skillsByRobot.filterValues { it == null }.keys.toList()

    val unknown: List<UnknownInput> get() = buildList {
        if (signals == null) add(UnknownInput.SIGNAL_SPEC)
        if (siteSkills == null) add(UnknownInput.SITE_SKILLS)
    }
}

/** 초안 하나를 지금 입력으로 본 결과. */
sealed interface Judgment {
    data class Passed(val definition: MissionDefinition) : Judgment

    /** 거부. 거부를 전부 든다. */
    data class Refused(val refusals: List<MissionRefusal>) : Judgment

    /** 입력을 몰라 판정하지 않았다(T7). [robots] 는 케이퍼빌리티를 못 물어본 기체다. */
    data class Unknown(val inputs: List<UnknownInput>, val robots: List<String>) : Judgment
}

/**
 * 초안 판정(S3b 스펙 §6.3). 순서는 문서 → WorkMaster 대조 → 입력 → 검증기다.
 *
 * 문서 수준의 문제(읽을 수 없음, WorkMaster 가 다름)는 현장 입력과 상관이 없으므로 입력을 모를 때도 거부로 낸다. 읽을 수
 * 없는 문서를 거부로 바꾸는 부분은 picasso `InMemoryMissionCatalog.activate` 의 그 자리를 그대로 옮긴다(파서는 문제 문자열
 * 목록만 준다).
 *
 * 정의 안의 `workMasterId` 가 초안의 WorkMaster(경로)와 다르면 UNREADABLE(경로 `$.workMasterId`)이다(T6). 검증기는
 * `workMasterId` 를 보지 않으므로 여기서 막지 않으면 다른 WorkMaster 의 카탈로그가 바뀐다.
 *
 * 바닥 소유는 `FloorOwnership.None` 이다. 호스트가 바닥 소유를 쥐지 않아 그 검사는 늘 통과한다(S3b 스펙 §11).
 */
object MissionJudgment {

    fun judge(workMasterId: String, text: String, inputs: SiteInputs, at: Instant): Judgment {
        val definition = when (val parsed = MissionDefinitionParser.parse(text)) {
            is MissionParse.Unreadable -> return Judgment.Refused(
                parsed.problems.map { MissionRefusal(MissionRefusalKind.UNREADABLE, null, it, DOCUMENT_SHAPE, at) },
            )
            is MissionParse.Parsed -> parsed.definition
        }
        if (definition.workMasterId != workMasterId) {
            return Judgment.Refused(
                listOf(
                    MissionRefusal(
                        MissionRefusalKind.UNREADABLE, null,
                        "$.workMasterId: 초안의 WorkMaster 와 다르다(${definition.workMasterId})", workMasterId, at,
                    ),
                ),
            )
        }
        val signals = inputs.signals
        val siteSkills = inputs.siteSkills
        if (signals == null || siteSkills == null) return Judgment.Unknown(inputs.unknown, inputs.unknownRobots)
        val refusals = MissionValidator.validate(definition, signals, FloorOwnership.None, siteSkills, at)
        return if (refusals.isEmpty()) Judgment.Passed(definition) else Judgment.Refused(refusals)
    }

    /** 읽을 수 없는 문서 거부의 기대 값. picasso 카탈로그와 같은 문구다. */
    val DOCUMENT_SHAPE = "임무 정의 문서 버전 ${MissionDefinitionParser.SCHEMA_VERSION} 의 모양"
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionTemplates.kt`:

```kotlin
package dev.picasso.ops.host.mission

import dev.picasso.middleware.PrepareSequencedRack

/** 시작용 정의 하나. [definition] 은 편집기에 그대로 넣는 글자다. */
data class MissionTemplate(val id: String, val title: String, val definition: String)

/**
 * 시작용 정의(S3b 스펙 §6.6). picasso 시험 픽스처 `MissionFixtures` 의 사본을 호스트 리소스로 둔다. 시험 소스라 이 저장소가
 * 쓸 수 없다. 칸마다 한 줄인 모양도 픽스처 그대로다.
 *
 * - [DATA_V1]: 코드 `PrepareSequencedRack` 을 데이터로 옮긴 것(버전 1 의 모양).
 * - [ARRIVAL_WAIT]: 그 앞에 랙 도착 대기(신호 `rack_present`, 기대 `true`, 기한 120초)를 둔 것(버전 2 의 모양). 사본에서
 *   `onDeadline` 만 ABORTED 로 바꿨다(픽스처 기본값은 OPERATOR_HOLD, 결정 4). 운영자 보류와 그 해소 화면은 S4 다.
 */
object MissionTemplates {

    const val DATA_V1 = "DATA_V1"
    const val ARRIVAL_WAIT = "ARRIVAL_WAIT"

    /** WorkMaster 마다 시작용 정의. 편집 대상이 PrepareSequencedRack 하나다(T6). */
    fun of(workMasterId: String): List<MissionTemplate> = when (workMasterId) {
        PrepareSequencedRack.WORK_MASTER -> listOf(
            MissionTemplate(DATA_V1, "코드 PrepareSequencedRack 을 옮긴 데이터 정의", read("PrepareSequencedRack.data-v1.json")),
            MissionTemplate(ARRIVAL_WAIT, "랙 도착 대기(rack_present = true, 기한 120초, 기한 뒤 ABORTED)", read("PrepareSequencedRack.arrival-wait.json")),
        )
        else -> emptyList()
    }

    private fun read(name: String): String =
        requireNotNull(MissionTemplates::class.java.getResourceAsStream("/mission-templates/$name")) { "템플릿 리소스가 없다: $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .trimEnd('\n')
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionVersions.kt`:

```kotlin
package dev.picasso.ops.host.mission

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.mission.DefinedCapability
import dev.picasso.ops.host.HostClock
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.store.DraftRow
import dev.picasso.ops.host.store.MissionStore
import dev.picasso.ops.host.store.MockRunRow
import org.springframework.dao.DuplicateKeyException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 그 초안이 없다. REST 가 404 로 옮긴다. */
class DraftNotFound(val draftId: Long) : RuntimeException("초안이 없다: $draftId")

/** 그 요청 id 가 이미 다른 행에 쓰였다(T9). REST 가 409 로 옮긴다. 요청 id 하나는 운영 서비스의 조작 하나다. */
class RequestIdReused(val requestId: UUID) : RuntimeException("요청 id 가 이미 쓰였다: $requestId")

/** 그 요청 id 의 조작을 아직 처리 중이다(T9). REST 가 409 로 옮긴다. 남은 행이 없다는 응답이 아니다. */
class RequestInProgress(val requestId: UUID) : RuntimeException("그 요청 id 의 조작을 아직 처리 중이다: $requestId")

/**
 * 임무 버전의 저장·검증·모의 실행·활성화(S3b 스펙 §6). REST 가 부르는 한 자리다.
 *
 * ## 잠금(T2)
 *
 * 잠금 순서는 «호스트 잠금 → DB» 한 방향뿐이다.
 *
 * - 초안 저장: 호스트 잠금을 잡지 않는다.
 * - 검증·모의 실행: 검증 입력([SiteInputs])을 읽는 동안만 호스트 잠금을 잡는다. 그동안 DB 연결을 쥐지 않는다. 모의 실행은
 *   잠금을 놓은 뒤 별도 mimic·미들웨어로 돈다(실제 시간 몇 초).
 * - 활성화: 처음부터 끝까지 호스트 잠금 아래다. 판정과 배정 사이에 활성화가 끼면 한 제출 안에서 판정 계획과 실행 계획의
 *   버전이 갈린다. 다시 검증하고, 마지막 모의 실행을 보고, `DefinedCapability` 를 먼저 만들고, 버전 행을 넣고, 마지막에
 *   카탈로그를 바꾼다. 행을 넣기 전에 케이퍼빌리티를 만드는 것은 만들다 실패한 정의가 버전 번호를 쓰지 않게 하려는 것이다.
 *
 * ## 처리 중인 요청(T9)
 *
 * 요청 id 를 받는 조작 셋(초안 저장·모의 실행·활성화)은 처음부터 끝까지 그 id 를 처리 중 집합에 둔다. 재조회([byRequest])는
 * 처리 중인 id 에 [RequestInProgress] 를 낸다. 활성화는 호스트 잠금을 기다리고 모의 실행은 실제 시간 상한 밖(하네스 기동,
 * 입력을 읽는 잠금 대기)이 있어, 운영 서비스가 응답 없음 뒤 재조회할 때 아직 행이 없을 수 있다. 그때 «남은 행 없음» 으로 응답하면
 * 운영 서비스가 반영 안 됨으로 확인한 뒤에 행이 남는다.
 *
 * 재조회는 처리 중 집합을 먼저 보고 DB 를 나중에 읽는다. 조작은 행을 남긴 뒤에 집합에서 빠지므로, 집합에 없고 DB 에도 없으면
 * 그 조작은 아직 호스트에 오지 않았거나 행을 남기지 않고 끝난 것이다. 앞의 것은 운영 서비스가 재조회 전에 기다려 줄인다.
 * 같은 요청 id 가 처리 중에 또 오면 [RequestIdReused] 다.
 *
 * @param clock 거부의 확인 시각(`checkedAt`)과 판정 시각. 호스트 시계다(시험은 현장 가상 시계).
 */
class MissionVersions(
    private val host: MissionHost,
    private val store: MissionStore,
    private val catalog: StoredMissionCatalog,
    private val runner: MockRunner,
    private val clock: HostClock,
    private val json: ObjectMapper,
) {

    /** 처리 중인 요청 id. 프로세스 안에만 있다. 재기동하면 처리 중이던 조작도 끝난 것이다. */
    private val inFlight: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun overview(workMasterId: String): MissionOverview {
        val versions = store.versions(workMasterId).map(VersionView::of)
        val activeVersion = catalog.active(workMasterId)?.missionVersion
        val active = if (activeVersion == null) {
            ActiveView(null, SOURCE_CODE, null)
        } else {
            ActiveView(activeVersion, SOURCE_DATA, versions.firstOrNull { it.version == activeVersion })
        }
        val drafts = store.drafts(workMasterId, DRAFT_LIMIT).map { DraftView.of(it, store.lastMockRun(it.draftId)?.let(::view)) }
        return MissionOverview(workMasterId, active, versions, drafts)
    }

    /** 초안은 자유롭다. 읽을 수 없는 문서도 저장한다(운영 관리 화면 설계 제안 §8.1). */
    fun saveDraft(workMasterId: String, definition: String, actor: String, requestId: UUID): DraftSavedView = handling(requestId) {
        if (store.requestIdUsed(requestId)) throw RequestIdReused(requestId)
        val row = try {
            store.saveDraft(workMasterId, definition, actor, requestId)
        } catch (_: DuplicateKeyException) {
            throw RequestIdReused(requestId)
        }
        DraftSavedView(DraftView.of(row, null))
    }

    fun validate(draftId: Long, robotIds: List<String>): ValidationView {
        val draft = draft(draftId)
        val inputs = host.siteInputs(robotIds)
        val at = clock.now()
        val judgment = MissionJudgment.judge(draft.workMasterId, draft.definition, inputs, at)
        return ValidationView(
            result = when (judgment) {
                is Judgment.Passed -> PASSED
                is Judgment.Refused -> REFUSED
                is Judgment.Unknown -> INPUT_UNKNOWN
            },
            draftId = draftId,
            workMasterId = draft.workMasterId,
            checkedAt = at,
            refusals = (judgment as? Judgment.Refused)?.refusals.orEmpty().map(RefusalView::of),
            unknown = (judgment as? Judgment.Unknown)?.let { UnknownView.of(it.inputs, it.robots) },
            inputs = InputsView.of(inputs),
        )
    }

    /**
     * 모의 실행(결정 1). 검증을 지난 초안만 돌린다. 지나지 못하면 거부 목록이나 «모름» 을 그대로 돌려주고 남기지 않는다.
     * 돈 것은 통과든 실패든 모의 실행 표에 남는다. 요청 안에서 동기로 돈다(T10).
     */
    fun mockRun(draftId: Long, robotIds: List<String>, requestId: UUID): MockRunOutcomeView =
        handling(requestId) { mockRunHandling(draftId, robotIds, requestId) }

    private fun mockRunHandling(draftId: Long, robotIds: List<String>, requestId: UUID): MockRunOutcomeView {
        val draft = draft(draftId)
        if (store.requestIdUsed(requestId)) throw RequestIdReused(requestId)
        val inputs = host.siteInputs(robotIds)
        val at = clock.now()
        fun outcome(result: String, judgment: Judgment?, row: MockRunView?) = MockRunOutcomeView(
            result = result,
            draftId = draftId,
            workMasterId = draft.workMasterId,
            checkedAt = at,
            refusals = (judgment as? Judgment.Refused)?.refusals.orEmpty().map(RefusalView::of),
            unknown = (judgment as? Judgment.Unknown)?.let { UnknownView.of(it.inputs, it.robots) },
            inputs = InputsView.of(inputs),
            mockRun = row,
        )

        val definition = when (val judgment = MissionJudgment.judge(draft.workMasterId, draft.definition, inputs, at)) {
            is Judgment.Passed -> judgment.definition
            is Judgment.Refused -> return outcome(REFUSED, judgment, null)
            is Judgment.Unknown -> return outcome(INPUT_UNKNOWN, judgment, null)
        }
        // 검증이 지났으면 스냅숏이 있다(신호 사양을 읽었다).
        val order = MockRunner.sampleOrder(draft.workMasterId, draftId, inputs.cell!!)
            ?: return outcome(INPUT_UNKNOWN, Judgment.Unknown(listOf(UnknownInput.SAMPLE_ORDER), emptyList()), null)

        val startedAt = store.now()
        val result = runner.run(definition, order)
        val row = try {
            store.saveMockRun(draftId, result.passed, json.writeValueAsString(result), requestId, startedAt)
        } catch (_: DuplicateKeyException) {
            throw RequestIdReused(requestId)
        }
        return outcome(if (result.passed) PASSED else FAILED, null, view(row))
    }

    /** 활성화(S3b 스펙 §6.5). 호스트 잠금 아래에서 처음부터 끝까지 한다. 결과 넷은 [ActivationView] 에 있다. */
    fun activate(draftId: Long, actor: String, reason: String, robotIds: List<String>, requestId: UUID): ActivationView =
        handling(requestId) {
            val draft = draft(draftId)
            if (store.requestIdUsed(requestId)) throw RequestIdReused(requestId)
            host.exclusive { activateLocked(draft, actor, reason, robotIds, requestId) }
        }

    private fun activateLocked(draft: DraftRow, actor: String, reason: String, robotIds: List<String>, requestId: UUID): ActivationView {
        val inputs = host.siteInputsLocked(robotIds)
        val at = clock.now()
        fun outcome(result: String, judgment: Judgment?, last: MockRunView?, activated: VersionView?) = ActivationView(
            result = result,
            draftId = draft.draftId,
            workMasterId = draft.workMasterId,
            checkedAt = at,
            version = activated?.version,
            refusals = (judgment as? Judgment.Refused)?.refusals.orEmpty().map(RefusalView::of),
            unknown = (judgment as? Judgment.Unknown)?.let { UnknownView.of(it.inputs, it.robots) },
            inputs = InputsView.of(inputs),
            lastMockRun = last,
            activated = activated,
        )

        // 1. 지금의 신호 사양과 현장 스킬로 다시 검증한다.
        val definition = when (val judgment = MissionJudgment.judge(draft.workMasterId, draft.definition, inputs, at)) {
            is Judgment.Passed -> judgment.definition
            is Judgment.Refused -> return outcome(REFUSED, judgment, null, null)
            is Judgment.Unknown -> return outcome(INPUT_UNKNOWN, judgment, null, null)
        }
        // 2. 그 초안의 마지막 모의 실행이 통과여야 한다. 앞 초안들의 모의 실행은 보지 않는다(S3b 스펙 §1).
        val last = store.lastMockRun(draft.draftId)
        val lastView = last?.let(::view)
        if (last?.passed != true) return outcome(MOCK_RUN_REQUIRED, null, lastView, null)

        // 3. 케이퍼빌리티 → 버전 행(번호 = 최대 + 1) → 카탈로그.
        // 버전 행의 키 충돌은 요청 id 재사용으로 옮기지 않는다. 기본 키(WorkMaster, 버전) 충돌은 호스트 잠금 아래에서 번호를
        // 매기므로 닿지 않고, 요청 id 충돌은 위에서 이미 보았고 같은 요청 id 는 처리 중 집합이 동시에 들이지 않아 닿지 않는다.
        // 그래도 나면 호스트의 결함이라 500 으로 올린다.
        val capability = DefinedCapability(definition)
        val row = store.insertVersion(draft.workMasterId, draft.draftId, draft.definition, actor, reason, requestId)
        catalog.install(row.workMasterId, capability, row.version)
        return outcome(ACTIVATED, null, lastView, VersionView.of(row))
    }

    /** 그 요청 id 로 남은 행(T9). 없으면 `null`. 그 요청을 아직 처리 중이면 [RequestInProgress] 다. */
    fun byRequest(requestId: UUID): RequestView? {
        if (requestId in inFlight) throw RequestInProgress(requestId)
        val rows = store.byRequest(requestId)
        if (!rows.found) return null
        return RequestView(
            requestId = requestId,
            draft = rows.draft?.let { DraftView.of(it, store.lastMockRun(it.draftId)?.let(::view)) },
            mockRun = rows.mockRun?.let(::view),
            version = rows.version?.let(VersionView::of),
        )
    }

    /** [action] 동안 [requestId] 를 처리 중으로 둔다. 같은 id 가 이미 처리 중이면 [RequestIdReused] 다. */
    private fun <T> handling(requestId: UUID, action: () -> T): T {
        if (!inFlight.add(requestId)) throw RequestIdReused(requestId)
        try {
            return action()
        } finally {
            inFlight.remove(requestId)
        }
    }

    private fun draft(draftId: Long): DraftRow = store.draft(draftId) ?: throw DraftNotFound(draftId)

    private fun view(row: MockRunRow) = MockRunView.of(row, json.readTree(row.result))

    companion object {
        const val PASSED = "PASSED"
        const val FAILED = "FAILED"
        const val REFUSED = "REFUSED"
        const val INPUT_UNKNOWN = "INPUT_UNKNOWN"
        const val ACTIVATED = "ACTIVATED"
        const val MOCK_RUN_REQUIRED = "MOCK_RUN_REQUIRED"

        const val SOURCE_CODE = "CODE"
        const val SOURCE_DATA = "DATA"

        /** 개요에 싣는 최근 초안 수. */
        const val DRAFT_LIMIT = 20
    }
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionViews.kt`:

```kotlin
package dev.picasso.ops.host.mission

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.mission.MissionRefusal
import dev.picasso.middleware.mission.SignalSpec
import dev.picasso.ops.host.store.DraftRow
import dev.picasso.ops.host.store.MockRunRow
import dev.picasso.ops.host.store.VersionRow
import java.time.Instant
import java.util.UUID

// 호스트 임무 REST 의 본문 모양(S3b JSON 계약 §4). 칸 이름과 순서가 계약이다.

/** 거부 하나. picasso `MissionRefusal` 의 칸에 파생 칸 둘(해결 담당, 바로 갈 작업)을 더한다. */
data class RefusalView(
    val kind: String,
    val nodeId: String?,
    val observed: String,
    val expected: String,
    val checkedAt: Instant,
    val basisVersion: Int?,
    val owner: String,
    val nextAction: String,
) {
    companion object {
        fun of(refusal: MissionRefusal) = RefusalView(
            kind = refusal.kind.name,
            nodeId = refusal.nodeId,
            observed = refusal.observed,
            expected = refusal.expected,
            checkedAt = refusal.checkedAt,
            basisVersion = refusal.basisVersion,
            owner = refusal.owner.name,
            nextAction = refusal.nextAction,
        )
    }
}

/** 신호 사양 한 줄. */
data class SignalSpecView(val name: String, val location: String?, val kind: String, val safety: Boolean) {
    companion object {
        fun of(spec: SignalSpec) = SignalSpecView(spec.name, spec.location, spec.kind.name, spec.safety)
    }
}

/**
 * 판정에 쓴 입력. 모르는 입력은 `null` 이다.
 *
 * @param robotIds 요청이 넘긴 기체(겹친 id 는 한 번).
 * @param unknownRobots 케이퍼빌리티를 못 물어본 기체.
 */
data class InputsView(
    val signals: List<SignalSpecView>?,
    val siteSkills: List<String>?,
    val robotIds: List<String>,
    val unknownRobots: List<String>,
) {
    companion object {
        fun of(inputs: SiteInputs) = InputsView(
            signals = inputs.signals?.map(SignalSpecView::of),
            siteSkills = inputs.siteSkills?.sorted(),
            robotIds = inputs.skillsByRobot.keys.toList(),
            unknownRobots = inputs.unknownRobots,
        )
    }
}

/** 판정이 «모름» 일 때 무엇을 몰랐는가(T7). */
data class UnknownView(val inputs: List<String>, val robots: List<String>, val detail: String) {
    companion object {
        fun of(inputs: List<UnknownInput>, robots: List<String>) = UnknownView(
            inputs = inputs.map { it.name },
            robots = robots,
            detail = inputs.joinToString("; ") {
                when (it) {
                    UnknownInput.SIGNAL_SPEC -> "셀 대역 스냅숏이 없어 신호 사양을 못 읽었다"
                    UnknownInput.SITE_SKILLS -> "기체 케이퍼빌리티를 못 물어봐 현장 스킬을 못 읽었다(${robots.joinToString(", ")})"
                    UnknownInput.SAMPLE_ORDER -> "셀 대역 스냅숏에 슬롯이나 자재를 든 제시 자리가 없어 표본 작업 지시를 못 만든다"
                }
            },
        )
    }
}

/** 초안 한 행. [lastMockRun] 은 그 초안의 마지막 모의 실행이며 없으면 `null`. */
data class DraftView(
    val draftId: Long,
    val workMasterId: String,
    val definition: String,
    val savedBy: String,
    val requestId: UUID,
    val savedAt: Instant,
    val lastMockRun: MockRunView?,
) {
    companion object {
        fun of(row: DraftRow, lastMockRun: MockRunView?) =
            DraftView(row.draftId, row.workMasterId, row.definition, row.savedBy, row.requestId, row.savedAt, lastMockRun)
    }
}

/** 모의 실행 한 행. [result] 는 [MockRunResult] 의 JSON 이다. */
data class MockRunView(
    val mockRunId: Long,
    val draftId: Long,
    val passed: Boolean,
    val result: JsonNode,
    val requestId: UUID,
    val startedAt: Instant,
    val finishedAt: Instant,
) {
    companion object {
        fun of(row: MockRunRow, result: JsonNode) =
            MockRunView(row.mockRunId, row.draftId, row.passed, result, row.requestId, row.startedAt, row.finishedAt)
    }
}

/** 임무 버전 한 행. */
data class VersionView(
    val workMasterId: String,
    val version: Int,
    val draftId: Long,
    val definition: String,
    val activatedBy: String,
    val reason: String,
    val requestId: UUID,
    val activatedAt: Instant,
) {
    companion object {
        fun of(row: VersionRow) =
            VersionView(row.workMasterId, row.version, row.draftId, row.definition, row.activatedBy, row.reason, row.requestId, row.activatedAt)
    }
}

/**
 * 지금 활성인 정의. [version] 이 `null` 이면 코드 정의이고([source] 가 `CODE`) [detail] 도 `null` 이다(코드라 버전 행이
 * 없다). 데이터 버전이면([source] 가 `DATA`) [detail] 에 그 버전 행이 실리고, 정의 JSON 은 그 행의 `definition` 이다.
 */
data class ActiveView(val version: Int?, val source: String, val detail: VersionView?)

/** `GET /host/missions/{workMasterId}` 의 본문. 버전은 높은 번호부터, 초안은 최근 것부터 [MissionVersions.DRAFT_LIMIT] 개. */
data class MissionOverview(
    val workMasterId: String,
    val active: ActiveView,
    val versions: List<VersionView>,
    val drafts: List<DraftView>,
)

/** `POST /host/missions/{workMasterId}/drafts` 의 본문. */
data class DraftSavedView(val draft: DraftView)

/** 검증 결과(S3b 스펙 §6.3). [result] 는 `PASSED`·`REFUSED`·`INPUT_UNKNOWN`. */
data class ValidationView(
    val result: String,
    val draftId: Long,
    val workMasterId: String,
    val checkedAt: Instant,
    val refusals: List<RefusalView>,
    val unknown: UnknownView?,
    val inputs: InputsView,
)

/**
 * 모의 실행 결과(S3b 스펙 §6.4). [result] 는 `PASSED`·`FAILED`·`REFUSED`·`INPUT_UNKNOWN`. 앞의 둘만 모의 실행 표에 남고
 * [mockRun] 이 그 행이다. 뒤의 둘은 돌지 않았으므로 [mockRun] 이 `null` 이다.
 */
data class MockRunOutcomeView(
    val result: String,
    val draftId: Long,
    val workMasterId: String,
    val checkedAt: Instant,
    val refusals: List<RefusalView>,
    val unknown: UnknownView?,
    val inputs: InputsView,
    val mockRun: MockRunView?,
)

/**
 * 활성화 결과(S3b 스펙 §6.5). [result] 는 `ACTIVATED`·`REFUSED`·`MOCK_RUN_REQUIRED`·`INPUT_UNKNOWN` 넷이다.
 *
 * @param version ACTIVATED 일 때 선 버전. 그 밖에는 `null`.
 * @param activated ACTIVATED 일 때 그 버전 행. 그 밖에는 `null`.
 * @param lastMockRun 판정에 쓴 그 초안의 마지막 모의 실행. 없거나 검증에서 멈췄으면 `null`.
 */
data class ActivationView(
    val result: String,
    val draftId: Long,
    val workMasterId: String,
    val checkedAt: Instant,
    val version: Int?,
    val refusals: List<RefusalView>,
    val unknown: UnknownView?,
    val inputs: InputsView,
    val lastMockRun: MockRunView?,
    val activated: VersionView?,
)

/** `GET /host/missions/requests/{requestId}` 의 본문(T9). 셋 중 남은 것만 `null` 이 아니다. */
data class RequestView(
    val requestId: UUID,
    val draft: DraftView?,
    val mockRun: MockRunView?,
    val version: VersionView?,
)

/** `GET /host/missions/templates/{workMasterId}` 의 본문. */
data class TemplatesView(val workMasterId: String, val templates: List<MissionTemplate>)
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MockRunner.kt`:

```kotlin
package dev.picasso.ops.host.mission

import dev.picasso.harness.Harness
import dev.picasso.middleware.CellSignals
import dev.picasso.middleware.ClientRobotPort
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.EquipmentUse
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.MaterialRequirement
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.MissionCatalog
import dev.picasso.middleware.NamedSignal
import dev.picasso.middleware.PhysicalState
import dev.picasso.middleware.SlotSignal
import dev.picasso.middleware.UnitState
import dev.picasso.middleware.Unassigned
import dev.picasso.middleware.mission.DefinedCapability
import dev.picasso.middleware.mission.MissionDefinition
import dev.picasso.middleware.mission.WaitStep
import dev.picasso.ops.host.cell.CellSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * 모의 실행 실패의 하위 범주(S3b 스펙 §6.4). 화면이 이유를 이 이름으로 가른다.
 */
enum class MockRunFailure {
    /** 정의: 같은 신호를 기대 값이 다른 대기 노드 둘이 쓴다. 이상적 현장의 신호 값을 하나로 정할 수 없다. */
    DEFINITION,

    /** 표본 작업 지시를 미들웨어가 받지 않았다(근거 등급 초과, 관문 거부). 실행이 서지 않았다. */
    SUBMISSION_REJECTED,

    /** 가상 시간 상한 안에 실행이 정착하지 않았다. */
    NOT_SETTLED,

    /** 실제 시간 상한을 넘겼다. 요청 안에서 동기로 도므로 운영 서비스의 요청 제한보다 짧게 둔다(T10). */
    WALL_CLOCK_LIMIT,

    /** 실행이 정착했으나 모든 단위 완료가 아니다(단위가 실패, 중단, 미확인으로 정착). */
    EXECUTION_FAILED,
}

/** 모의 실행의 단위 하나. [reached] 는 근거 등급이고 [failureClass] 는 실패면 그 분류다. */
data class MockUnit(
    val unitId: String,
    val route: String,
    val skillType: String,
    val state: String,
    val reached: String,
    val failureClass: String?,
)

/** 모의 실행에 쓴 표본 작업 지시의 요점. 슬롯은 셀 대역 스냅숏에서 앞에서부터 고른다. */
data class MockSample(
    val jobOrderId: String,
    val requiredEvidence: String,
    val slots: List<String>,
    val material: String,
    val presentation: String,
)

/**
 * 모의 실행 결과(S3b 스펙 §6.4). 모의 실행 표의 `result` 칸에 이 모양 그대로 JSON 으로 남는다.
 *
 * @param passed 실행이 모든 단위 완료로 정착했다.
 * @param failure 통과가 아니면 하위 범주. 통과면 `null`.
 * @param detail 실패의 사람용 설명. 통과면 `null`.
 * @param physicalState 실행의 마지막 물리 상태. 실행이 서지 않았으면 `null`.
 * @param virtualElapsedSeconds 표본 작업 지시를 낸 뒤 흐른 가상 시간(초).
 * @param wallElapsedMillis 실제로 걸린 시간(밀리초).
 */
data class MockRunResult(
    val passed: Boolean,
    val failure: MockRunFailure?,
    val detail: String?,
    val robotId: String,
    val sample: MockSample,
    val physicalState: String?,
    val units: List<MockUnit>,
    val virtualElapsedSeconds: Long,
    val wallElapsedMillis: Long,
)

/**
 * 모의 실행기(S3b 스펙 §6.4, 결정 1, T8). 호스트 프로세스 안에 별도 mimic(harness `Harness`, 가상 시계, 기체 하나)과
 * 별도 미들웨어를 세워 초안 정의로 표본 작업 지시 하나를 끝까지 돌린다. 현장 기체는 건드리지 않는다.
 *
 * - **격리**: mimic 엔진 잠금은 인스턴스마다 따로다. 현장 mimic(다른 프로세스)·호스트 미들웨어·호스트 잠금과 섞이지 않는다.
 *   하네스에 적재 지점과 핸드셰이크 보고를 붙이지 않아 registry 에 아무것도 닿지 않는다.
 * - **시계**: 미들웨어의 `now` 는 하네스의 가상 시계다. 어긋나면 E2 시간 윈도우가 갈린다.
 * - **이상적 현장**(T8): 목적지 슬롯은 처음부터 점유이고 기대 자재를 내며 관측 시각은 `null`(읽은 순간)이다. 제시 자리는
 *   `null`(침묵, 관문 통과)이다. 이름 있는 신호는 대기 노드의 기대 값을 낸다. 그래서 대기 노드는 첫 pump 에서 완료된다.
 *   기체 프로파일은 현장 humanoid 프로파일에서 실패 모드를 뺀 사본이다.
 * - **진행 루프**: pump → 정착했으면 끝 → 상한 확인 → 가상 시계를 [STEP] 민다 → 스트림 갱신이 닿을 틈([PAUSE]) → 다시 pump.
 *
 * 모의 실행은 정의가 끝까지 도는지를 보며 현장 사실(신호가 실제로 오는가, 자재가 맞는가)을 보증하지 않는다.
 *
 * @param profile 모의 실행용 기체 프로파일. 저장소 루트 기준 경로를 기동 설정이 풀어 준다.
 * @param schema 프로파일 스키마. 하네스가 기동 때 프로파일을 이것으로 검증한다.
 * @param virtualLimit 가상 시간 상한. 이 안에 정착하지 않으면 [MockRunFailure.NOT_SETTLED].
 * @param wallLimit 실제 시간 상한. 넘기면 [MockRunFailure.WALL_CLOCK_LIMIT].
 */
class MockRunner(
    private val profile: Path,
    private val schema: Path,
    private val virtualLimit: Duration,
    private val wallLimit: Duration = WALL_LIMIT,
) {
    init {
        require(Files.isRegularFile(profile)) { "모의 실행 프로파일이 없다: $profile" }
        require(Files.isRegularFile(schema)) { "프로파일 스키마가 없다: $schema" }
        require(!virtualLimit.isNegative && !virtualLimit.isZero) { "가상 시간 상한이 양수가 아니다: $virtualLimit" }
    }

    /** 검증을 지난 [definition] 으로 [order] 를 돌린다. */
    fun run(definition: MissionDefinition, order: JobOrder): MockRunResult {
        val sample = sample(order)
        val wallStart = System.nanoTime()
        fun wall(): Duration = Duration.ofNanos(System.nanoTime() - wallStart)

        val ideal = idealSignals(definition)
            ?: return MockRunResult(
                passed = false, failure = MockRunFailure.DEFINITION, detail = conflict(definition), robotId = ROBOT_ID,
                sample = sample, physicalState = null, units = emptyList(), virtualElapsedSeconds = 0, wallElapsedMillis = wall().toMillis(),
            )

        Harness(mapOf(ROBOT_ID to profile), schema = schema).use { harness ->
            val middleware = Middleware(
                robots = ClientRobotPort(harness.client(CLIENT_ID)),
                cell = IdealCell(order, ideal),
                now = { harness.clock.now() },
                missions = MissionCatalog.of(listOf(DefinedCapability(definition))),
            )
            val start = harness.clock.now()
            fun virtual(): Duration = Duration.between(start, harness.clock.now())
            fun result(failure: MockRunFailure?, detail: String?, execution: Middleware.Execution?) = MockRunResult(
                passed = failure == null,
                failure = failure,
                detail = detail,
                robotId = ROBOT_ID,
                sample = sample,
                physicalState = execution?.physicalState?.name,
                units = execution?.units.orEmpty().map {
                    MockUnit(it.unitId, it.route.name, it.skillType, it.state.name, it.reached.name, it.failureClass)
                },
                virtualElapsedSeconds = virtual().seconds,
                wallElapsedMillis = wall().toMillis(),
            )

            val execution = when (val submission = middleware.submit(order, ROBOT_ID)) {
                is Middleware.Submission.Accepted -> submission.execution
                is Middleware.Submission.Idempotent -> submission.execution
                is Middleware.Submission.Rejected ->
                    return result(MockRunFailure.SUBMISSION_REJECTED, "표본 작업 지시를 받지 않았다: ${submission.reason}", null)
                is Unassigned ->
                    return result(MockRunFailure.SUBMISSION_REJECTED, "표본 작업 지시를 배정하지 못했다: ${submission.refusals}", null)
            }

            while (true) {
                middleware.pump()
                if (execution.physicalState.isSettled) break
                if (virtual() >= virtualLimit) {
                    return result(MockRunFailure.NOT_SETTLED, "가상 시간 ${virtualLimit.seconds}초 안에 정착하지 않았다", execution)
                }
                if (wall() >= wallLimit) {
                    return result(MockRunFailure.WALL_CLOCK_LIMIT, "실제 시간 ${wallLimit.toMillis()}밀리초 안에 끝나지 않았다", execution)
                }
                harness.advance(STEP)
                Thread.sleep(PAUSE.toMillis())
            }

            val done = execution.physicalState == PhysicalState.PHYSICALLY_DONE && execution.units.all { it.state == UnitState.DONE }
            return if (done) {
                result(null, null, execution)
            } else {
                val stuck = execution.units.filter { it.state != UnitState.DONE }
                    .joinToString(", ") { "${it.unitId}=${it.state.name}${it.failureClass?.let { c -> "($c)" } ?: ""}" }
                result(MockRunFailure.EXECUTION_FAILED, "실행이 ${execution.physicalState.name} 로 정착했다: $stuck", execution)
            }
        }
    }

    /**
     * 신호마다 이상적 값. 같은 신호를 기대 값이 다른 대기 노드가 쓰면 하나로 정할 수 없어 `null` 이다.
     */
    private fun idealSignals(definition: MissionDefinition): Map<String, String>? {
        val expects = definition.steps.filterIsInstance<WaitStep>().groupBy({ it.signal }, { it.expect })
        if (expects.values.any { it.distinct().size > 1 }) return null
        return expects.mapValues { (_, values) -> values.first() }
    }

    private fun conflict(definition: MissionDefinition): String =
        definition.steps.filterIsInstance<WaitStep>().groupBy { it.signal }
            .filterValues { waits -> waits.map { it.expect }.distinct().size > 1 }
            .entries.joinToString("; ") { (signal, waits) ->
                "신호 $signal 을 기대 값이 다른 대기 노드가 쓴다: ${waits.joinToString(", ") { "${it.id}=${it.expect}" }}"
            }

    private fun sample(order: JobOrder): MockSample {
        val source = order.equipmentRequirements.first { it.equipmentUse == EquipmentUse.SOURCE }
        return MockSample(
            jobOrderId = order.jobOrderId,
            requiredEvidence = order.requiredEvidence.name,
            slots = order.equipmentRequirements.filter { it.equipmentUse == EquipmentUse.DESTINATION }.map { it.id },
            material = source.properties.getValue(EquipmentUse.PROP_MATERIAL),
            presentation = source.id,
        )
    }

    /**
     * 이상적 현장(T8). 표본 작업 지시의 목적지 슬롯은 처음부터 점유이고 그 슬롯의 기대 자재를 낸다. 관측 시각은 `null` 이라
     * 읽은 순간이 시각이므로 E2 시간 윈도우 안에 든다. 그 밖의 자리(제시 자리 포함)는 `null`(침묵)이다. 관문은 침묵을
     * 판정하지 않으므로 통과한다. 이름 있는 신호는 대기 노드의 기대 값이다.
     */
    private class IdealCell(order: JobOrder, private val ideal: Map<String, String>) : CellSignals {
        private val destinations: Map<String, String?> = order.equipmentRequirements
            .filter { it.equipmentUse == EquipmentUse.DESTINATION }
            .associate { it.id to it.properties[EquipmentUse.PROP_MATERIAL] }

        override fun observe(location: String): SlotSignal? =
            if (location in destinations) SlotSignal(true, destinations[location], null) else null

        override fun signal(name: String): NamedSignal? = ideal[name]?.let { NamedSignal(it, null) }
    }

    companion object {
        /** 모의 실행의 기체 id. 현장 기체 id 와 겹치지 않게 둔다. */
        const val ROBOT_ID = "mock-01"

        /** 하네스 mimic 에 싣는 클라이언트 id. */
        const val CLIENT_ID = "mission-host-mock-run"

        /** 표본 작업 지시의 슬롯 수. 통합 시나리오의 작업 지시 하나와 같은 둘이다. */
        const val SAMPLE_SLOTS = 2

        /** 실제 시간 상한(T10). 운영 서비스의 모의 실행 요청 제한(60초)보다 짧다. */
        val WALL_LIMIT: Duration = Duration.ofSeconds(30)

        /** 진행 루프가 한 번에 미는 가상 시간. */
        val STEP: Duration = Duration.ofSeconds(5)

        /** 민 뒤 스트림 갱신이 클라이언트에 닿을 틈. picasso 시험의 진행 루프와 같은 까닭이다. */
        val PAUSE: Duration = Duration.ofMillis(10)

        /**
         * 마지막 셀 대역 스냅숏으로 만든 표본 작업 지시(S3b 스펙 §6.4). 호스트는 현장 모듈의 픽스처를 모르므로 스냅숏의
         * 슬롯을 앞에서부터 [SAMPLE_SLOTS] 개, 자재를 든 첫 제시 자리를 쓴다. 요구 근거는 E2 이고 `materialRequirements` 는
         * 자재별 슬롯 수라 관문의 정합 검사를 지난다. 슬롯이나 자재를 든 제시 자리가 없으면 `null` 이다.
         */
        fun sampleOrder(workMasterId: String, draftId: Long, cell: CellSnapshot): JobOrder? {
            val slots = cell.slots.take(SAMPLE_SLOTS).map { it.id }
            val source = cell.presentations.firstOrNull { it.material != null } ?: return null
            if (slots.isEmpty()) return null
            val material = source.material!!
            return JobOrder(
                jobOrderId = "MOCK-$draftId",
                workMasterId = workMasterId,
                version = 1,
                requiredEvidence = Evidence.E2,
                materialRequirements = listOf(MaterialRequirement(material, slots.size)),
                equipmentRequirements = slots.map {
                    EquipmentRequirement(it, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to material))
                } + EquipmentRequirement(source.id, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to material)),
            )
        }
    }
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/mission/StoredMissionCatalog.kt`:

```kotlin
package dev.picasso.ops.host.mission

import dev.picasso.middleware.ActiveMission
import dev.picasso.middleware.LogicalCapability
import dev.picasso.middleware.MissionCatalog
import dev.picasso.middleware.mission.DefinedCapability
import dev.picasso.middleware.mission.MissionDefinitionParser
import dev.picasso.middleware.mission.MissionParse
import dev.picasso.ops.host.store.VersionRow

/**
 * 호스트의 임무 카탈로그(S3b 스펙 §6.2, T1). picasso `MissionCatalog` 를 직접 구현해 DB 의 활성 버전 번호를 그대로 싣는다.
 *
 * picasso `InMemoryMissionCatalog.activate` 는 번호를 지정할 수 없고 재기동하면 1부터 센다. 그래서 그것을 쓰지 않고
 * 번호는 저장([dev.picasso.ops.host.store.MissionStore.insertVersion])이 정하고 이 카탈로그는 그 번호를 받아 든다.
 *
 * 활성화한 적이 없는 WorkMaster 는 코드 케이퍼빌리티로 답하고 버전이 없다(`null`).
 *
 * ## 스레드
 *
 * 미들웨어가 호스트 잠금 아래에서 [active] 를 읽고 활성화도 호스트 잠금 아래에서 [install] 을 부른다. 기동 때의 [restore]
 * 는 pump 가 돌기 전이다. 그래도 화면 조회([active])가 잠금 밖에서 올 수 있어 맵을 통째로 갈아 끼운다.
 */
class StoredMissionCatalog(code: List<LogicalCapability> = MissionCatalog.codeCapabilities()) : MissionCatalog {

    private val coded: Map<String, LogicalCapability> = code.associateBy { it.workMasterId }

    @Volatile
    private var activated: Map<String, ActiveMission> = emptyMap()

    override fun active(workMasterId: String): ActiveMission? =
        activated[workMasterId] ?: coded[workMasterId]?.let { ActiveMission(it, missionVersion = null) }

    /**
     * 기동 때 DB 의 활성 버전(WorkMaster 마다 가장 높은 번호)으로 세운다. **다시 검증하지 않는다.** 활성화 때 검증을 지난
     * 정의이고, 지금의 신호 사양·현장 기체로 다시 보면 현장이 바뀐 것만으로 활성 버전이 사라진다.
     *
     * 저장된 정의를 파싱하지 못하면 예외를 던져 호스트 기동을 멈춘다(S3b 스펙 §9). 코드 정의로 물러서면 운영자는 버전
     * N 이 돈다고 알고 있는데 다른 정의가 돈다.
     */
    fun restore(rows: List<VersionRow>) {
        activated = rows.associate { row -> row.workMasterId to ActiveMission(capability(row), row.version) }
    }

    /** 활성화가 버전 행을 넣은 뒤 부른다. 다음 작업 지시부터 이 버전으로 계획한다. 도는 실행은 쥔 버전으로 끝난다. */
    fun install(workMasterId: String, capability: DefinedCapability, version: Int) {
        require(capability.workMasterId == workMasterId) { "정의의 WorkMaster(${capability.workMasterId})가 $workMasterId 가 아니다" }
        activated = activated + (workMasterId to ActiveMission(capability, version))
    }

    private fun capability(row: VersionRow): DefinedCapability {
        val where = "저장된 임무 버전 ${row.workMasterId} 버전 ${row.version}"
        val definition = when (val parsed = MissionDefinitionParser.parse(row.definition)) {
            is MissionParse.Parsed -> parsed.definition
            is MissionParse.Unreadable -> throw IllegalStateException("$where 을 읽지 못했다: ${parsed.problems.joinToString("; ")}")
        }
        check(definition.workMasterId == row.workMasterId) { "$where 의 정의가 다른 WorkMaster(${definition.workMasterId})다" }
        return try {
            DefinedCapability(definition)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("$where 로 케이퍼빌리티를 세우지 못했다: ${e.message}", e)
        }
    }
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostSchema.kt`:

```kotlin
package dev.picasso.ops.host.store

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * 실행 호스트의 `mission` 스키마 Flyway(S3b 스펙 §6.1, T3). 위치는 `classpath:db/mission`, 스키마와 이력 테이블은 `mission` 이다.
 *
 * **Spring Boot 의 Flyway 자동설정을 쓰지 않는다.** 운영 서비스 `OpsSchema` 와 같은 이유다. 자동설정의 기본 위치는
 * `classpath:db/migration` 이고, 통합 시험 JVM 에는 registry jar 가 같은 클래스패스에 있어 registry 마이그레이션을 집어 온다.
 * 위치와 스키마를 이 한 곳에 두고 기동과 시험이 같이 쓴다.
 */
object HostSchema {
    const val SCHEMA = "mission"
    const val LOCATION = "classpath:db/mission"

    fun flyway(dataSource: DataSource): Flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .locations(LOCATION)
            .load()
}

/** 기동 때 올린 마이그레이션 수. 저장소 빈이 이것에 기대어 마이그레이션 뒤에 만들어진다. */
data class HostSchemaMigrated(val migrationsExecuted: Int)
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/store/MissionStore.kt`:

```kotlin
package dev.picasso.ops.host.store

import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** 초안 한 행(S3b 스펙 §6.1). [definition] 은 받은 글자 그대로이고 읽을 수 없는 문서일 수 있다. */
data class DraftRow(
    val draftId: Long,
    val workMasterId: String,
    val definition: String,
    val savedBy: String,
    val requestId: UUID,
    val savedAt: Instant,
)

/** 모의 실행 한 행. [result] 는 JSON 글자다(모양은 S3b JSON 계약 §4.5). */
data class MockRunRow(
    val mockRunId: Long,
    val draftId: Long,
    val passed: Boolean,
    val result: String,
    val requestId: UUID,
    val startedAt: Instant,
    val finishedAt: Instant,
)

/** 임무 버전 한 행. 키는 ([workMasterId], [version]) 이다. */
data class VersionRow(
    val workMasterId: String,
    val version: Int,
    val draftId: Long,
    val definition: String,
    val activatedBy: String,
    val reason: String,
    val requestId: UUID,
    val activatedAt: Instant,
)

/** 요청 id 하나로 남은 행(T9). 셋 다 없으면 그 요청은 반영되지 않았다. */
data class RequestRows(val draft: DraftRow?, val mockRun: MockRunRow?, val version: VersionRow?) {
    val found: Boolean get() = draft != null || mockRun != null || version != null
}

/**
 * `mission` 스키마의 표 셋(S3b 스펙 §6.1). 덧붙이기와 읽기만 있다. 고치기·지우기는 스키마의 트리거가 막는다.
 *
 * 문장 하나가 한 트랜잭션이다. 호출은 짧고 연결을 쥔 채 호스트 잠금을 기다리지 않는다(T2). 활성화는 호스트 잠금 아래에서
 * 이것을 부르므로 잠금 순서는 «호스트 잠금 → DB» 한 방향뿐이다.
 */
class MissionStore(private val jdbc: JdbcClient) {

    /** DB 의 지금 시각. 모의 실행의 시작 시각을 DB 시계로 적으려고 쓴다. */
    fun now(): Instant = jdbc.sql("SELECT clock_timestamp()").query { rs, _ -> instant(rs, 1) }.single()

    /** 그 요청 id 가 표 셋 중 어디에든 이미 있는가. 요청 id 하나는 운영 서비스의 조작 하나다. */
    fun requestIdUsed(requestId: UUID): Boolean = byRequest(requestId).found

    fun saveDraft(workMasterId: String, definition: String, savedBy: String, requestId: UUID): DraftRow =
        jdbc.sql(
            """
            INSERT INTO mission.draft (work_master_id, definition, saved_by, request_id)
            VALUES (:workMaster, :definition, :savedBy, :requestId)
            RETURNING $DRAFT_COLUMNS
            """.trimIndent(),
        )
            .param("workMaster", workMasterId)
            .param("definition", definition)
            .param("savedBy", savedBy)
            .param("requestId", requestId)
            .query { rs, _ -> draft(rs) }
            .single()

    fun draft(draftId: Long): DraftRow? =
        jdbc.sql("SELECT $DRAFT_COLUMNS FROM mission.draft WHERE draft_id = :id")
            .param("id", draftId)
            .query { rs, _ -> draft(rs) }
            .optional()
            .orElse(null)

    /** 그 WorkMaster 의 최근 초안부터 [limit] 개. */
    fun drafts(workMasterId: String, limit: Int): List<DraftRow> =
        jdbc.sql(
            "SELECT $DRAFT_COLUMNS FROM mission.draft WHERE work_master_id = :workMaster ORDER BY draft_id DESC LIMIT :limit",
        )
            .param("workMaster", workMasterId)
            .param("limit", limit)
            .query { rs, _ -> draft(rs) }
            .list()

    /** 모의 실행 한 건을 남긴다. [startedAt] 은 [now] 로 잰 DB 시각이고 끝 시각은 넣는 순간의 DB 시각이다. */
    fun saveMockRun(draftId: Long, passed: Boolean, resultJson: String, requestId: UUID, startedAt: Instant): MockRunRow =
        jdbc.sql(
            """
            INSERT INTO mission.mock_run (draft_id, passed, result, request_id, started_at)
            VALUES (:draftId, :passed, CAST(:result AS jsonb), :requestId, :startedAt)
            RETURNING $MOCK_RUN_COLUMNS
            """.trimIndent(),
        )
            .param("draftId", draftId)
            .param("passed", passed)
            .param("result", resultJson)
            .param("requestId", requestId)
            .param("startedAt", OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC))
            .query { rs, _ -> mockRun(rs) }
            .single()

    /** 그 초안의 마지막 모의 실행. 활성화 관문이 이것 하나만 본다(S3b 스펙 §1). */
    fun lastMockRun(draftId: Long): MockRunRow? =
        jdbc.sql("SELECT $MOCK_RUN_COLUMNS FROM mission.mock_run WHERE draft_id = :draftId ORDER BY mock_run_id DESC LIMIT 1")
            .param("draftId", draftId)
            .query { rs, _ -> mockRun(rs) }
            .optional()
            .orElse(null)

    /**
     * 버전 행을 넣는다. 번호는 그 WorkMaster 의 가장 큰 번호 + 1 이다(없으면 1). 번호를 정하는 것과 넣는 것이 한 문장이고
     * 활성화는 호스트 잠금 아래에서만 부르므로 같은 번호를 두 번 내지 않는다. 그래도 겹치면 기본 키가 막는다.
     */
    fun insertVersion(
        workMasterId: String,
        draftId: Long,
        definition: String,
        activatedBy: String,
        reason: String,
        requestId: UUID,
    ): VersionRow =
        jdbc.sql(
            """
            INSERT INTO mission.mission_version (work_master_id, version, draft_id, definition, activated_by, reason, request_id)
            SELECT :workMaster, COALESCE(MAX(version), 0) + 1, :draftId, :definition, :activatedBy, :reason, :requestId
            FROM mission.mission_version WHERE work_master_id = :workMaster
            RETURNING $VERSION_COLUMNS
            """.trimIndent(),
        )
            .param("workMaster", workMasterId)
            .param("draftId", draftId)
            .param("definition", definition)
            .param("activatedBy", activatedBy)
            .param("reason", reason)
            .param("requestId", requestId)
            .query { rs, _ -> version(rs) }
            .single()

    /** 그 WorkMaster 의 버전 이력. 높은 번호부터. */
    fun versions(workMasterId: String): List<VersionRow> =
        jdbc.sql("SELECT $VERSION_COLUMNS FROM mission.mission_version WHERE work_master_id = :workMaster ORDER BY version DESC")
            .param("workMaster", workMasterId)
            .query { rs, _ -> version(rs) }
            .list()

    /** WorkMaster 마다 가장 높은 버전 하나. 기동 때 카탈로그를 이것으로 세운다(T1). */
    fun activeVersions(): List<VersionRow> =
        jdbc.sql(
            """
            SELECT DISTINCT ON (work_master_id) $VERSION_COLUMNS
            FROM mission.mission_version
            ORDER BY work_master_id, version DESC
            """.trimIndent(),
        )
            .query { rs, _ -> version(rs) }
            .list()

    fun byRequest(requestId: UUID): RequestRows = RequestRows(
        draft = jdbc.sql("SELECT $DRAFT_COLUMNS FROM mission.draft WHERE request_id = :requestId")
            .param("requestId", requestId).query { rs, _ -> draft(rs) }.optional().orElse(null),
        mockRun = jdbc.sql("SELECT $MOCK_RUN_COLUMNS FROM mission.mock_run WHERE request_id = :requestId")
            .param("requestId", requestId).query { rs, _ -> mockRun(rs) }.optional().orElse(null),
        version = jdbc.sql("SELECT $VERSION_COLUMNS FROM mission.mission_version WHERE request_id = :requestId")
            .param("requestId", requestId).query { rs, _ -> version(rs) }.optional().orElse(null),
    )

    private fun draft(rs: ResultSet) = DraftRow(
        draftId = rs.getLong("draft_id"),
        workMasterId = rs.getString("work_master_id"),
        definition = rs.getString("definition"),
        savedBy = rs.getString("saved_by"),
        requestId = rs.getObject("request_id", UUID::class.java),
        savedAt = instant(rs, "saved_at"),
    )

    private fun mockRun(rs: ResultSet) = MockRunRow(
        mockRunId = rs.getLong("mock_run_id"),
        draftId = rs.getLong("draft_id"),
        passed = rs.getBoolean("passed"),
        result = rs.getString("result"),
        requestId = rs.getObject("request_id", UUID::class.java),
        startedAt = instant(rs, "started_at"),
        finishedAt = instant(rs, "finished_at"),
    )

    private fun version(rs: ResultSet) = VersionRow(
        workMasterId = rs.getString("work_master_id"),
        version = rs.getInt("version"),
        draftId = rs.getLong("draft_id"),
        definition = rs.getString("definition"),
        activatedBy = rs.getString("activated_by"),
        reason = rs.getString("reason"),
        requestId = rs.getObject("request_id", UUID::class.java),
        activatedAt = instant(rs, "activated_at"),
    )

    private fun instant(rs: ResultSet, column: String): Instant = rs.getObject(column, OffsetDateTime::class.java).toInstant()

    private fun instant(rs: ResultSet, index: Int): Instant = rs.getObject(index, OffsetDateTime::class.java).toInstant()

    private companion object {
        const val DRAFT_COLUMNS = "draft_id, work_master_id, definition, saved_by, request_id, saved_at"
        const val MOCK_RUN_COLUMNS = "mock_run_id, draft_id, passed, result, request_id, started_at, finished_at"
        const val VERSION_COLUMNS = "work_master_id, version, draft_id, definition, activated_by, reason, request_id, activated_at"
    }
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/web/MissionController.kt`:

```kotlin
package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.mission.DraftNotFound
import dev.picasso.ops.host.mission.MissionTemplates
import dev.picasso.ops.host.mission.MissionVersions
import dev.picasso.ops.host.mission.RequestIdReused
import dev.picasso.ops.host.mission.RequestInProgress
import dev.picasso.ops.host.mission.TemplatesView
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 실행 호스트의 임무 버전 REST 와 신호 조작 전달(S3b 스펙 §6.6). 루프백이고 인증이 없다. 호출자는 운영 서비스뿐이다. 모드
 * 검사(엔지니어 모드만)와 조작 기록은 운영 서비스가 한다.
 *
 * POST 는 `application/json` 만 받는다(S3a 와 같은 까닭). 결과(통과·거부·모름·활성화)는 늘 200 의 본문에 있다. 상태 코드는
 * 요청이 틀렸거나(400) 대상이 없거나(404) 요청 id 가 이미 쓰였거나 그 요청을 아직 처리 중이거나(409) 현장이 안 닿을 때(503)만
 * 가른다.
 */
@RestController
class MissionController(
    private val versions: MissionVersions,
    private val cellBand: CellBandClient,
    private val json: ObjectMapper,
) {

    @GetMapping("/host/missions/{workMasterId}")
    fun overview(@PathVariable workMasterId: String): ResponseEntity<Any> = answering {
        versions.overview(MissionRequests.workMaster(workMasterId))
    }

    @GetMapping("/host/missions/templates/{workMasterId}")
    fun templates(@PathVariable workMasterId: String): ResponseEntity<Any> = answering {
        val workMaster = MissionRequests.workMaster(workMasterId)
        TemplatesView(workMaster, MissionTemplates.of(workMaster))
    }

    @PostMapping("/host/missions/{workMasterId}/drafts", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun saveDraft(@PathVariable workMasterId: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = answering {
        val workMaster = MissionRequests.workMaster(workMasterId)
        val request = MissionRequests.draft(read(body))
        versions.saveDraft(workMaster, request.definition, request.actor, request.requestId)
    }

    @PostMapping("/host/missions/drafts/{draftId}/validate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun validate(@PathVariable draftId: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = answering {
        val id = MissionRequests.draftId(draftId)
        versions.validate(id, MissionRequests.robotIds(read(body)))
    }

    @PostMapping("/host/missions/drafts/{draftId}/mock-run", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun mockRun(@PathVariable draftId: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = answering {
        val id = MissionRequests.draftId(draftId)
        val request = MissionRequests.mockRun(read(body))
        versions.mockRun(id, request.robotIds, request.requestId)
    }

    @PostMapping("/host/missions/drafts/{draftId}/activate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun activate(@PathVariable draftId: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = answering {
        val id = MissionRequests.draftId(draftId)
        val request = MissionRequests.activation(read(body))
        versions.activate(id, request.actor, request.reason, request.robotIds, request.requestId)
    }

    /**
     * 운영 서비스의 재조회(T9). 그 요청 id 로 남은 행이 없으면 404 다. 그 요청을 아직 처리 중이면(잠금을 기다리는 활성화, 도는
     * 모의 실행) 409 `REQUEST_IN_PROGRESS` 다. 행이 없다는 응답이 아니어서 운영 서비스는 확인하지 못한 것으로 둔다.
     */
    @GetMapping("/host/missions/requests/{requestId}")
    fun byRequest(@PathVariable requestId: String): ResponseEntity<Any> {
        val id = try {
            MissionRequests.requestId(requestId)
        } catch (e: BadRequest) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
        }
        val found = try {
            versions.byRequest(id)
        } catch (e: RequestInProgress) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(HostRejection(REQUEST_IN_PROGRESS, e.message ?: ""))
        } ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(HostRejection(REQUEST_NOT_FOUND, "그 요청 id 로 남은 행이 없다: $id"))
        return ResponseEntity.ok(found)
    }

    /**
     * 신호 조작을 현장 셀 대역에 넘긴다(결정 3). 현장의 응답(200·400·403·404·415)은 상태 코드와 본문 그대로 돌려준다. 안전
     * 신호 쓰기 거부도 현장이 하고 호스트는 넘기기만 한다(ADR 32). 현장이 안 닿으면 503 `CELL_SILENT` 다. 본문은 현장이
     * 본다. 바뀐 값은 다음 pump 부터 `GET /host/cell` 과 설비 대기에 보인다.
     */
    @PostMapping("/host/cell/signals/{name}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun writeSignal(@PathVariable name: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> {
        val relay = cellBand.writeSignal(name, body ?: ByteArray(0))
            ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(HostRejection(CELL_SILENT, "현장 셀 대역이 답하지 않는다"))
        val builder = ResponseEntity.status(relay.status)
        relay.contentType?.let { builder.contentType(MediaType.parseMediaType(it)) }
        return builder.body(relay.body)
    }

    private fun read(body: ByteArray?): JsonNode? = body?.let { runCatching { json.readTree(it) }.getOrNull() }

    private inline fun answering(action: () -> Any): ResponseEntity<Any> = try {
        ResponseEntity.ok(action())
    } catch (e: BadRequest) {
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
    } catch (e: DraftNotFound) {
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(HostRejection(DRAFT_NOT_FOUND, e.message ?: ""))
    } catch (e: RequestIdReused) {
        ResponseEntity.status(HttpStatus.CONFLICT).body(HostRejection(REQUEST_ID_REUSED, e.message ?: ""))
    }

    companion object {
        /** 오류 이름(S3b JSON 계약 §3). */
        const val DRAFT_NOT_FOUND = "DRAFT_NOT_FOUND"
        const val REQUEST_NOT_FOUND = "REQUEST_NOT_FOUND"
        const val REQUEST_ID_REUSED = "REQUEST_ID_REUSED"
        const val REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS"
        const val CELL_SILENT = "CELL_SILENT"
    }
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/web/MissionRequests.kt`:

```kotlin
package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.PrepareSequencedRack
import java.util.UUID

/** 초안 저장 요청. [definition] 은 글자 그대로이고 읽을 수 없는 문서여도 된다. */
data class DraftRequest(val definition: String, val actor: String, val requestId: UUID)

/** 모의 실행 요청. [robotIds] 는 운영 서비스가 넘긴 시운전 완료 기체다(현장 스킬 계산용). */
data class MockRunRequest(val robotIds: List<String>, val requestId: UUID)

/** 활성화 요청. 사유는 비어 있지 않아야 한다(T5). */
data class ActivationRequest(val actor: String, val reason: String, val robotIds: List<String>, val requestId: UUID)

/**
 * 호스트 임무 REST 의 요청 본문(S3b JSON 계약 §4). [HostRequests] 와 같이 스프링에 맡기지 않고 직접 읽는다. 칸은 엄격하게
 * 본다. 형이 틀린 칸은 없는 것으로 보고 400 이다.
 */
object MissionRequests {

    /** 편집 대상 WorkMaster(T6). 작업 지시 폼과 호스트가 두 임무로 고정이고 요구 근거 E2 도 고정이다. */
    val EDITABLE: Set<String> = setOf(PrepareSequencedRack.WORK_MASTER)

    fun workMaster(value: String): String {
        if (value !in EDITABLE) {
            throw BadRequest(HostRequests.UNKNOWN_WORK_MASTER, "편집하지 않는 WorkMaster 다: $value (편집하는 것: ${EDITABLE.sorted()})")
        }
        return value
    }

    fun draftId(value: String): Long =
        value.toLongOrNull()?.takeIf { it > 0 } ?: throw BadRequest(HostRequests.BAD_REQUEST, "초안 id 가 양의 정수가 아니다: $value")

    fun requestId(value: String?): UUID {
        if (value == null) throw BadRequest(HostRequests.BAD_REQUEST, "requestId 가 비어 있지 않은 문자열이 아니다")
        return try {
            UUID.fromString(value).also { require(it.toString() == value.lowercase()) }
        } catch (_: IllegalArgumentException) {
            throw BadRequest(HostRequests.BAD_REQUEST, "requestId 가 UUID 가 아니다: $value")
        }
    }

    fun draft(body: JsonNode?): DraftRequest {
        val node = obj(body)
        val definition = node.get("definition")?.takeIf { it.isTextual }?.asText()
            ?: throw BadRequest(HostRequests.BAD_REQUEST, "definition 이 문자열이 아니다")
        return DraftRequest(definition, text(node, "actor"), requestId(node.get("requestId")?.takeIf { it.isTextual }?.asText()))
    }

    fun robotIds(body: JsonNode?): List<String> = strings(obj(body).get("robotIds"), "robotIds")

    fun mockRun(body: JsonNode?): MockRunRequest {
        val node = obj(body)
        return MockRunRequest(strings(node.get("robotIds"), "robotIds"), requestId(node.get("requestId")?.takeIf { it.isTextual }?.asText()))
    }

    fun activation(body: JsonNode?): ActivationRequest {
        val node = obj(body)
        return ActivationRequest(
            actor = text(node, "actor"),
            reason = text(node, "reason"),
            robotIds = strings(node.get("robotIds"), "robotIds"),
            requestId = requestId(node.get("requestId")?.takeIf { it.isTextual }?.asText()),
        )
    }

    private fun obj(body: JsonNode?): JsonNode {
        if (body == null || !body.isObject) throw BadRequest(HostRequests.BAD_REQUEST, "본문이 JSON 객체가 아니다")
        return body
    }

    /** 비어 있지 않은 문자열 칸. */
    private fun text(node: JsonNode, field: String): String =
        node.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            ?: throw BadRequest(HostRequests.BAD_REQUEST, "$field 가 비어 있지 않은 문자열이 아니다")

    /** 비어 있지 않은 문자열의 배열. 필수다. 빈 배열은 된다. */
    private fun strings(node: JsonNode?, field: String): List<String> {
        if (node == null || !node.isArray || node.any { !it.isTextual || it.asText().isBlank() }) {
            throw BadRequest(HostRequests.BAD_REQUEST, "$field 가 비어 있지 않은 문자열의 배열이 아니다")
        }
        return node.map { it.asText() }
    }
}
```

`mission-host/src/main/resources/db/mission/V1__mission_versions.sql`:

```sql
-- 임무 버전 저장(S3b 스펙 §6.1, T3). 표 셋 모두 덧붙이기만 한다. 시각은 DB 의 clock_timestamp() 다(운영 서비스 조작 기록과 같음).
-- 요청 id 는 운영 서비스가 넘긴 조작의 요청 id 다. 응답을 못 받은 운영 서비스가 그것으로 다시 찾는다(T9).

-- 초안. 자유롭다: 읽을 수 없는 문서도 저장한다. 정의는 받은 글자 그대로다.
CREATE TABLE draft (
    draft_id       BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    work_master_id TEXT        NOT NULL CHECK (work_master_id <> ''),
    definition     TEXT        NOT NULL,
    saved_by       TEXT        NOT NULL CHECK (saved_by <> ''),
    request_id     UUID        NOT NULL UNIQUE,
    saved_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 모의 실행. 검증을 지난 초안만 돈다. 결과는 통과 여부, 실패 하위 범주, 물리 상태, 단위별 상태와 근거 등급, 가상 경과 시간이다.
CREATE TABLE mock_run (
    mock_run_id BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    draft_id    BIGINT      NOT NULL REFERENCES draft (draft_id),
    passed      BOOLEAN     NOT NULL,
    result      JSONB       NOT NULL,
    request_id  UUID        NOT NULL UNIQUE,
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (finished_at >= started_at)
);

CREATE INDEX mock_run_draft ON mock_run (draft_id, mock_run_id);

-- 임무 버전. WorkMaster 마다 1부터 오른다. 활성 버전은 그 WorkMaster 의 가장 큰 번호다.
-- 정의는 활성화 때 검증을 지난 초안의 글자를 그대로 옮긴다. 기동 때 이 칸을 파싱해 카탈로그를 세운다(T1).
CREATE TABLE mission_version (
    work_master_id TEXT        NOT NULL CHECK (work_master_id <> ''),
    version        INTEGER     NOT NULL CHECK (version > 0),
    draft_id       BIGINT      NOT NULL REFERENCES draft (draft_id),
    definition     TEXT        NOT NULL,
    activated_by   TEXT        NOT NULL CHECK (activated_by <> ''),
    reason         TEXT        NOT NULL CHECK (reason <> ''),
    request_id     UUID        NOT NULL UNIQUE,
    activated_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (work_master_id, version)
);

-- 덧붙이기만 한다. 운영 서비스 조작 기록·현장 설정과 같이 스키마가 막는다.
CREATE FUNCTION mission_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '임무 버전 저장은 덧붙이기만 한다(% %)', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER draft_no_update_delete
    BEFORE UPDATE OR DELETE ON draft
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

-- 행 트리거는 TRUNCATE 에서 돌지 않는다. 통째로 비우는 것도 지우는 것이다.
CREATE TRIGGER draft_no_truncate
    BEFORE TRUNCATE ON draft
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER mock_run_no_update_delete
    BEFORE UPDATE OR DELETE ON mock_run
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER mock_run_no_truncate
    BEFORE TRUNCATE ON mock_run
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER mission_version_no_update_delete
    BEFORE UPDATE OR DELETE ON mission_version
    FOR EACH ROW EXECUTE FUNCTION mission_append_only();

CREATE TRIGGER mission_version_no_truncate
    BEFORE TRUNCATE ON mission_version
    FOR EACH STATEMENT EXECUTE FUNCTION mission_append_only();
```

`mission-host/src/main/resources/mission-templates/PrepareSequencedRack.arrival-wait.json`:

```json
{
"schemaVersion": 1,
"workMasterId": "PrepareSequencedRack",
"maxEvidence": "E2",
"preferredOptionals": { "verify_grasp": "true" },
"steps": [
{"kind": "wait", "id": "rack-arrival", "signal": "rack_present", "expect": "true", "deadlineSeconds": 120, "onDeadline": "ABORTED"},
{
"kind": "unit",
"id": "place",
"skill": "pick_place",
"forEach": "destination",
"pairWith": { "equipmentUse": "source", "property": "material" },
"whenUnpaired": "NO_SOURCE_FOR_MATERIAL",
"unitId": { "from": "ITEM_ID" },
"parameters": { "object_id": { "from": "PAIRED_ID", "otherwise": "" }, "destination": { "from": "ITEM_ID" } },
"expectedIdentity": { "from": "ITEM_PROPERTY", "property": "material" },
"source": { "from": "PAIRED_ID" },
"destination": { "from": "ITEM_ID" }
}
]
}
```

`mission-host/src/main/resources/mission-templates/PrepareSequencedRack.data-v1.json`:

```json
{
"schemaVersion": 1,
"workMasterId": "PrepareSequencedRack",
"maxEvidence": "E2",
"preferredOptionals": { "verify_grasp": "true" },
"steps": [
{
"kind": "unit",
"id": "place",
"skill": "pick_place",
"forEach": "destination",
"pairWith": { "equipmentUse": "source", "property": "material" },
"whenUnpaired": "NO_SOURCE_FOR_MATERIAL",
"unitId": { "from": "ITEM_ID" },
"parameters": { "object_id": { "from": "PAIRED_ID", "otherwise": "" }, "destination": { "from": "ITEM_ID" } },
"expectedIdentity": { "from": "ITEM_PROPERTY", "property": "material" },
"source": { "from": "PAIRED_ID" },
"destination": { "from": "ITEM_ID" }
}
]
}
```

`mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt`:

```kotlin
package dev.picasso.ops.host

import dev.picasso.ops.host.store.HostSchema
import dev.picasso.ops.host.store.MissionStore
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.SQLException
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `mission` 스키마의 표 셋(S3b 스펙 §6.1, T3). 호스트를 띄우지 않고 마이그레이션과 저장만 본다. */
class MissionStoreTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val store = MissionStore(JdbcClient.create(dataSource))

    @BeforeTest
    fun freshSchema() {
        PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
        assertEquals(1, HostSchema.flyway(dataSource).migrate().migrationsExecuted)
    }

    private fun draft(workMaster: String = PSR) = store.saveDraft(workMaster, "{}", "lee", UUID.randomUUID())

    @Test
    fun `mission 마이그레이션은 mission 스키마에만 들어간다`() {
        val placed = PostgresSupport.queryAll(
            """
            SELECT table_name FROM information_schema.tables WHERE table_schema = 'mission' ORDER BY table_name
            """.trimIndent(),
        ) { it.getString(1) }
        assertEquals(listOf("draft", "flyway_schema_history", "mission_version", "mock_run"), placed)
        assertNull(PostgresSupport.queryOne("SELECT to_regclass('public.mission_version')::text") { it.getString(1) })
    }

    @Test
    fun `표 셋은 고칠 수도 지울 수도 통째로 비울 수도 없다`() {
        val draft = draft()
        store.saveMockRun(draft.draftId, true, """{"passed":true}""", UUID.randomUUID(), store.now())
        store.insertVersion(PSR, draft.draftId, "{}", "lee", "사유", UUID.randomUUID())
        listOf("draft" to "saved_by = 'x'", "mock_run" to "passed = false", "mission_version" to "reason = 'x'").forEach { (table, set) ->
            listOf("UPDATE mission.$table SET $set", "DELETE FROM mission.$table", "TRUNCATE mission.$table CASCADE").forEach { sql ->
                val e = assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) }
                assertTrue("덧붙이기만" in e.message!!, "$sql: ${e.message}")
            }
        }
        assertEquals(1, store.versions(PSR).size)
        assertEquals(1, store.drafts(PSR, 10).size)
    }

    @Test
    fun `버전 번호는 WorkMaster 마다 가장 큰 번호 더하기 1 이고 활성 버전은 WorkMaster 마다 가장 큰 번호다`() {
        val a = draft()
        val b = draft("OtherWorkMaster")
        assertEquals(1, store.insertVersion(PSR, a.draftId, "v1", "lee", "r", UUID.randomUUID()).version)
        assertEquals(2, store.insertVersion(PSR, a.draftId, "v2", "lee", "r", UUID.randomUUID()).version)
        assertEquals(1, store.insertVersion("OtherWorkMaster", b.draftId, "o1", "lee", "r", UUID.randomUUID()).version)
        assertEquals(3, store.insertVersion(PSR, a.draftId, "v3", "lee", "r", UUID.randomUUID()).version)

        assertEquals(listOf(3, 2, 1), store.versions(PSR).map { it.version })
        assertEquals(mapOf(PSR to "v3", "OtherWorkMaster" to "o1"), store.activeVersions().associate { it.workMasterId to it.definition })
    }

    @Test
    fun `마지막 모의 실행은 그 초안의 가장 늦은 행이고 요청 id 로 행을 다시 찾는다`() {
        val draft = draft()
        val other = draft()
        assertNull(store.lastMockRun(draft.draftId))
        val first = store.saveMockRun(draft.draftId, true, """{"n":1}""", UUID.randomUUID(), store.now())
        val requestId = UUID.randomUUID()
        val second = store.saveMockRun(draft.draftId, false, """{"n":2}""", requestId, store.now())
        store.saveMockRun(other.draftId, true, """{"n":3}""", UUID.randomUUID(), store.now())
        assertEquals(second.mockRunId, store.lastMockRun(draft.draftId)!!.mockRunId)
        assertTrue(second.mockRunId > first.mockRunId)
        assertEquals(false, store.lastMockRun(draft.draftId)!!.passed)

        val found = store.byRequest(requestId)
        assertEquals(second.mockRunId, found.mockRun!!.mockRunId)
        assertNull(found.draft)
        assertEquals(draft.draftId, store.byRequest(draft.requestId).draft!!.draftId)
        assertEquals(false, store.byRequest(UUID.randomUUID()).found)
    }

    private companion object {
        const val PSR = "PrepareSequencedRack"
    }
}
```

`mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt`:

```kotlin
package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.host.HostBench.Companion.GHOST
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.registry.PostgresSupport
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 실행 호스트의 임무 버전: 저장·카탈로그·검증·모의 실행·활성화 REST(S3b 스펙 §6, §10 의 호스트 행).
 *
 * 정의는 템플릿 엔드포인트에서 받아 쓴다. 틀린 변경은 그 글자를 바꿔 만든다.
 */
class MissionVersionsTest {

    private val robots = listOf(HUMANOID, QUADRUPED)

    private fun HostBench.templates(): Map<String, String> =
        get("/host/missions/templates/$PSR")["templates"].associate { it["id"].asText() to it["definition"].asText() }

    private fun HostBench.dataV1(): String = templates().getValue("DATA_V1")

    private fun HostBench.arrivalWait(): String = templates().getValue("ARRIVAL_WAIT")

    private fun HostBench.saveDraft(definition: String, requestId: UUID = UUID.randomUUID(), workMaster: String = PSR): HostBench.Reply =
        post(
            "/host/missions/$workMaster/drafts",
            JSON.writeValueAsString(mapOf("definition" to definition, "actor" to "lee", "requestId" to requestId.toString())),
        )

    private fun HostBench.draft(definition: String): Long {
        val saved = saveDraft(definition)
        assertEquals(200, saved.status, saved.body.toString())
        return saved.body!!["draft"]["draftId"].asLong()
    }

    private fun HostBench.validate(draftId: Long, ids: List<String> = robots): JsonNode {
        val reply = post("/host/missions/drafts/$draftId/validate", JSON.writeValueAsString(mapOf("robotIds" to ids)))
        assertEquals(200, reply.status, reply.body.toString())
        return reply.body!!
    }

    private fun HostBench.mockRun(draftId: Long, ids: List<String> = robots, requestId: UUID = UUID.randomUUID()): JsonNode {
        val reply = post(
            "/host/missions/drafts/$draftId/mock-run",
            JSON.writeValueAsString(mapOf("robotIds" to ids, "requestId" to requestId.toString())),
        )
        assertEquals(200, reply.status, reply.body.toString())
        return reply.body!!
    }

    private fun HostBench.activate(draftId: Long, ids: List<String> = robots, requestId: UUID = UUID.randomUUID()): HostBench.Reply =
        post(
            "/host/missions/drafts/$draftId/activate",
            JSON.writeValueAsString(mapOf("actor" to "lee", "reason" to "랙 도착 대기 도입", "robotIds" to ids, "requestId" to requestId.toString())),
        )

    /** 초안 저장 → 모의 실행 통과 → 활성화. 선 버전 번호를 돌려준다. */
    private fun HostBench.activated(definition: String): Int {
        val draftId = draft(definition)
        val mock = mockRun(draftId)
        assertEquals("PASSED", mock["result"].asText(), mock.toString())
        val activation = activate(draftId)
        assertEquals(200, activation.status, activation.body.toString())
        assertEquals("ACTIVATED", activation.body!!["result"].asText(), activation.body.toString())
        return activation.body["version"].asInt()
    }

    private fun HostBench.activeVersion(): JsonNode = get("/host/missions/$PSR")["active"]["version"]

    @Test
    fun `버전이 없으면 개요는 코드 정의를 내고 템플릿 둘은 버전 1 모양과 ABORTED 대기 버전 2 모양이다`() {
        HostBench().use { bench ->
            val overview = bench.get("/host/missions/$PSR")
            assertEquals(PSR, overview["workMasterId"].asText())
            assertTrue(overview["active"]["version"].isNull, overview.toString())
            assertEquals("CODE", overview["active"]["source"].asText())
            assertTrue(overview["active"]["detail"].isNull)
            assertEquals(0, overview["versions"].size())
            assertEquals(0, overview["drafts"].size())

            val templates = bench.get("/host/missions/templates/$PSR")["templates"]
            assertEquals(listOf("DATA_V1", "ARRIVAL_WAIT"), templates.map { it["id"].asText() })
            val wait = JSON.readTree(templates[1]["definition"].asText())["steps"][0]
            assertEquals("rack-arrival", wait["id"].asText())
            assertEquals("rack_present", wait["signal"].asText())
            assertEquals("true", wait["expect"].asText())
            assertEquals(120, wait["deadlineSeconds"].asInt())
            assertEquals("ABORTED", wait["onDeadline"].asText())
            assertEquals(1, JSON.readTree(templates[0]["definition"].asText())["steps"].size())
        }
    }

    @Test
    fun `초안은 읽을 수 없는 문서도 저장되고 검증은 UNREADABLE 과 JSON 경로를 낸다`() {
        HostBench().use { bench ->
            val broken = bench.dataV1().replace("\"skill\": \"pick_place\",", "").replace("\"maxEvidence\": \"E2\"", "\"maxEvidence\": 2")
            val requestId = UUID.randomUUID()
            val saved = bench.saveDraft(broken, requestId)
            assertEquals(200, saved.status, saved.body.toString())
            val draft = saved.body!!["draft"]
            assertEquals(broken, draft["definition"].asText())
            assertEquals("lee", draft["savedBy"].asText())
            assertEquals(requestId.toString(), draft["requestId"].asText())
            assertTrue(draft["lastMockRun"].isNull)

            val validated = bench.validate(draft["draftId"].asLong())
            assertEquals("REFUSED", validated["result"].asText(), validated.toString())
            val refusals = validated["refusals"]
            assertTrue(refusals.all { it["kind"].asText() == "UNREADABLE" && it["nodeId"].isNull }, refusals.toString())
            val observed = refusals.map { it["observed"].asText() }
            assertTrue(observed.any { it.startsWith("$.maxEvidence") }, observed.toString())
            assertTrue(observed.any { it.startsWith("$.steps[0].skill") }, observed.toString())
            assertEquals("ENGINEER", refusals[0]["owner"].asText())
            assertEquals("정의 JSON 의 틀린 칸을 고친다", refusals[0]["nextAction"].asText())

            // 읽을 수 없는 초안은 모의 실행도 활성화도 거부이고 모의 실행 표에 남지 않는다.
            val mock = bench.mockRun(draft["draftId"].asLong())
            assertEquals("REFUSED", mock["result"].asText())
            assertTrue(mock["mockRun"].isNull)
            assertEquals("REFUSED", bench.activate(draft["draftId"].asLong()).body!!["result"].asText())
            assertTrue(bench.get("/host/missions/$PSR")["drafts"].single()["lastMockRun"].isNull)
        }
    }

    @Test
    fun `정의의 WorkMaster 가 경로와 다르면 UNREADABLE 이고 경로의 WorkMaster 가 PrepareSequencedRack 이 아니면 400 이다`() {
        HostBench().use { bench ->
            val other = bench.dataV1().replace("\"workMasterId\": \"PrepareSequencedRack\"", "\"workMasterId\": \"InspectAsset\"")
            val draftId = bench.draft(other)
            val validated = bench.validate(draftId)
            assertEquals("REFUSED", validated["result"].asText(), validated.toString())
            val refusal = validated["refusals"].single()
            assertEquals("UNREADABLE", refusal["kind"].asText())
            assertTrue(refusal["observed"].asText().startsWith("$.workMasterId"), refusal.toString())
            assertEquals(PSR, refusal["expected"].asText())

            val activation = bench.activate(draftId).body!!
            assertEquals("REFUSED", activation["result"].asText())
            assertTrue(activation["version"].isNull)

            listOf("InspectAsset", "DeliverContainer").forEach { workMaster ->
                val saved = bench.saveDraft(bench.dataV1(), workMaster = workMaster)
                assertEquals(400, saved.status, workMaster)
                assertEquals("UNKNOWN_WORK_MASTER", saved.body!!["error"].asText())
                assertEquals(400, bench.fetch("/host/missions/$workMaster").status)
                assertEquals(400, bench.fetch("/host/missions/templates/$workMaster").status)
            }
        }
    }

    @Test
    fun `신호 사양에 없는 신호는 검증과 활성화에서 SIGNAL_NOT_IN_SPEC 이고 해결 담당과 바로 갈 작업을 낸다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.arrivalWait().replace("\"signal\": \"rack_present\"", "\"signal\": \"rack_ready\""))
            val validated = bench.validate(draftId)
            assertEquals("REFUSED", validated["result"].asText(), validated.toString())
            val refusal = validated["refusals"].single()
            assertEquals("SIGNAL_NOT_IN_SPEC", refusal["kind"].asText())
            assertEquals("rack-arrival", refusal["nodeId"].asText())
            assertEquals("rack_ready", refusal["observed"].asText())
            assertEquals("신호 사양의 이름 중 하나(guard_closed, lot_code, rack_present)", refusal["expected"].asText())
            assertEquals("ENGINEER", refusal["owner"].asText())
            assertEquals("신호 이름을 고치거나 신호 사양에 더한다", refusal["nextAction"].asText())
            assertTrue(refusal["basisVersion"].isNull)
            assertEquals(validated["checkedAt"].asText(), refusal["checkedAt"].asText())

            val activation = bench.activate(draftId).body!!
            assertEquals("REFUSED", activation["result"].asText(), activation.toString())
            assertEquals("SIGNAL_NOT_IN_SPEC", activation["refusals"].single()["kind"].asText())
            assertTrue(bench.activeVersion().isNull, "거부된 활성화가 버전을 세웠다")
        }
    }

    @Test
    fun `대기 노드 버전 2 모양은 셀 대역의 신호 사양으로 검증을 지나고 안전 신호 대기는 거부된다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val passed = bench.validate(bench.draft(bench.arrivalWait()))
            assertEquals("PASSED", passed["result"].asText(), passed.toString())
            assertEquals(0, passed["refusals"].size())
            assertTrue(passed["unknown"].isNull)
            val inputs = passed["inputs"]
            assertEquals(listOf("rack_present", "guard_closed", "lot_code"), inputs["signals"].map { it["name"].asText() })
            assertEquals("RACK-204", inputs["signals"][0]["location"].asText())
            assertEquals(true, inputs["signals"][1]["safety"].asBoolean())
            assertEquals(listOf("inspect", "navigate_to", "pick_place"), inputs["siteSkills"].map { it.asText() })
            assertEquals(robots, inputs["robotIds"].map { it.asText() })

            val safety = bench.validate(bench.draft(bench.arrivalWait().replace("\"signal\": \"rack_present\"", "\"signal\": \"guard_closed\"")))
            assertEquals("REFUSED", safety["result"].asText(), safety.toString())
            assertEquals(listOf("SAFETY_SIGNAL_WAIT"), safety["refusals"].map { it["kind"].asText() })

            val text = bench.validate(bench.draft(bench.arrivalWait().replace("\"signal\": \"rack_present\", \"expect\": \"true\"", "\"signal\": \"lot_code\", \"expect\": \"LOT-0002\"")))
            assertEquals("PASSED", text["result"].asText(), text.toString())
        }
    }

    @Test
    fun `신호 사양이나 기체 케이퍼빌리티를 모르면 거부가 아니라 INPUT_UNKNOWN 이고 아는 기체에 스킬이 없으면 거부다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.dataV1())

            // mimic 이 모르는 기체 하나. 모름을 없음으로 접으면 SKILL_NOT_ON_SITE 가 난다.
            val ghost = bench.validate(draftId, listOf(QUADRUPED, GHOST))
            assertEquals("INPUT_UNKNOWN", ghost["result"].asText(), ghost.toString())
            assertEquals(listOf("SITE_SKILLS"), ghost["unknown"]["inputs"].map { it.asText() })
            assertEquals(listOf(GHOST), ghost["unknown"]["robots"].map { it.asText() })
            assertEquals(0, ghost["refusals"].size())
            assertTrue(ghost["inputs"]["siteSkills"].isNull)
            assertEquals("INPUT_UNKNOWN", bench.activate(draftId, listOf(HUMANOID, GHOST)).body!!["result"].asText())
            val unknownMock = bench.mockRun(draftId, listOf(HUMANOID, GHOST))
            assertEquals("INPUT_UNKNOWN", unknownMock["result"].asText())
            assertTrue(unknownMock["mockRun"].isNull)

            // 아는 기체에 pick_place 가 없으면 그것은 모름이 아니라 거부다.
            val quadruped = bench.validate(draftId, listOf(QUADRUPED))
            assertEquals("REFUSED", quadruped["result"].asText(), quadruped.toString())
            assertEquals(listOf("SKILL_NOT_ON_SITE"), quadruped["refusals"].map { it["kind"].asText() })

            // 셀 대역 본문에 신호 목록이 없다. 빈 사양이 아니라 모르는 사양이다.
            bench.cellBody = HostBench.STANDARD_CELL.substringBefore(",\n \"signals\"") + "}"
            // 지금 대기 중인 pump 가 앞선 본문을 들고 끝날 수 있어 두 번을 지나 본다.
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            assertTrue(bench.get("/host/cell")["cell"]["signals"].isNull)
            val noSignals = bench.validate(draftId)
            assertEquals("INPUT_UNKNOWN", noSignals["result"].asText(), noSignals.toString())
            assertEquals(listOf("SIGNAL_SPEC"), noSignals["unknown"]["inputs"].map { it.asText() })

            // 셀 대역이 안 닿는다(스냅숏 없음).
            bench.cellBody = HostBench.STANDARD_CELL
            bench.stopCell()
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            assertTrue(bench.get("/host/cell")["cell"].isNull)
            val silent = bench.validate(draftId)
            assertEquals("INPUT_UNKNOWN", silent["result"].asText(), silent.toString())
            assertEquals(listOf("SIGNAL_SPEC"), silent["unknown"]["inputs"].map { it.asText() })
            assertEquals("INPUT_UNKNOWN", bench.activate(draftId).body!!["result"].asText())
        }
    }

    @Test
    fun `통과한 모의 실행 없이 활성화하면 MOCK_RUN_REQUIRED 이고 그 초안의 마지막 모의 실행만 본다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.dataV1())
            val none = bench.activate(draftId).body!!
            assertEquals("MOCK_RUN_REQUIRED", none["result"].asText(), none.toString())
            assertTrue(none["version"].isNull)
            assertTrue(none["lastMockRun"].isNull)

            // 다른 초안의 통과한 모의 실행은 이 초안의 관문을 열지 않는다.
            val otherDraft = bench.draft(bench.dataV1())
            assertEquals("PASSED", bench.mockRun(otherDraft)["result"].asText())
            assertEquals("MOCK_RUN_REQUIRED", bench.activate(draftId).body!!["result"].asText())
            assertTrue(bench.activeVersion().isNull)

            val mock = bench.mockRun(draftId)
            assertEquals("PASSED", mock["result"].asText(), mock.toString())
            val activation = bench.activate(draftId).body!!
            assertEquals("ACTIVATED", activation["result"].asText(), activation.toString())
            assertEquals(1, activation["version"].asInt())
            assertEquals(mock["mockRun"]["mockRunId"].asLong(), activation["lastMockRun"]["mockRunId"].asLong())
            assertEquals(1, bench.activeVersion().asInt())
        }
    }

    @Test
    fun `마지막 모의 실행이 실패면 앞서 통과한 모의 실행이 있어도 MOCK_RUN_REQUIRED 다`() {
        HostBench(mockVirtualLimit = java.time.Duration.ofSeconds(30)).use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.dataV1())
            // 가상 시간 상한 30초 안에 pick_place(45초)가 끝나지 않는다.
            val failed = bench.mockRun(draftId)
            assertEquals("FAILED", failed["result"].asText(), failed.toString())
            val result = failed["mockRun"]["result"]
            assertEquals(false, failed["mockRun"]["passed"].asBoolean())
            assertEquals("NOT_SETTLED", result["failure"].asText(), result.toString())
            val activation = bench.activate(draftId).body!!
            assertEquals("MOCK_RUN_REQUIRED", activation["result"].asText())
            assertEquals(false, activation["lastMockRun"]["passed"].asBoolean())
            assertEquals(failed["mockRun"]["mockRunId"].asLong(), bench.get("/host/missions/$PSR")["drafts"].single()["lastMockRun"]["mockRunId"].asLong())
        }
    }

    @Test
    fun `모의 실행은 결과를 그 초안에 붙여 남기고 단위별 상태와 근거 등급을 낸다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.arrivalWait())
            val requestId = UUID.randomUUID()
            val mock = bench.mockRun(draftId, requestId = requestId)
            assertEquals("PASSED", mock["result"].asText(), mock.toString())
            val row = mock["mockRun"]
            assertEquals(draftId, row["draftId"].asLong())
            assertEquals(true, row["passed"].asBoolean())
            assertEquals(requestId.toString(), row["requestId"].asText())
            assertFalse(java.time.Instant.parse(row["finishedAt"].asText()).isBefore(java.time.Instant.parse(row["startedAt"].asText())))
            val result = row["result"]
            assertTrue(result["failure"].isNull)
            assertEquals("PHYSICALLY_DONE", result["physicalState"].asText())
            assertEquals(listOf("rack-arrival", "RACK-204.S01", "RACK-204.S02"), result["units"].map { it["unitId"].asText() })
            assertTrue(result["units"].all { it["state"].asText() == "DONE" && it["reached"].asText() == "E2" }, result.toString())
            assertEquals("SIGNAL", result["units"][0]["route"].asText())
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02"), result["sample"]["slots"].map { it.asText() })
            assertEquals("SEQ-IN-02.BIN-A", result["sample"]["presentation"].asText())
            assertTrue(result["virtualElapsedSeconds"].asLong() > 0)

            val draft = bench.get("/host/missions/$PSR")["drafts"].single()
            assertEquals(row["mockRunId"].asLong(), draft["lastMockRun"]["mockRunId"].asLong())
            // 현장 기체는 건드리지 않는다.
            assertEquals(0, bench.get("/host/executions")["executions"].size())
            assertEquals(0, bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.size })
        }
    }

    @Test
    fun `버전 1 을 활성화하면 새 제출부터 버전 1 이고 도는 실행은 코드 정의로 끝난다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val running = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", running["result"].asText(), running.toString())
            val first = running["executionId"].asText()

            assertEquals(1, bench.activated(bench.dataV1()))
            val overview = bench.get("/host/missions/$PSR")
            assertEquals(1, overview["active"]["version"].asInt())
            assertEquals("DATA", overview["active"]["source"].asText())
            assertEquals("lee", overview["active"]["detail"]["activatedBy"].asText())
            assertEquals("랙 도착 대기 도입", overview["active"]["detail"]["reason"].asText())
            assertEquals(listOf(1), overview["versions"].map { it["version"].asInt() })

            // 도는 실행은 쥔 정의(코드)로 끝난다.
            assertTrue(bench.execution(first)!!["missionVersion"].isNull)
            bench.driveUntil(first, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertTrue(bench.execution(first)!!["missionVersion"].isNull)

            val next = bench.post("/host/job-orders", request(rack("JO-2", "RACK-204.S03"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", next["result"].asText(), next.toString())
            assertEquals(1, bench.execution(next["executionId"].asText())!!["missionVersion"].asInt())
        }
    }

    /**
     * 셀 대역 대역의 본문. 슬롯 넷은 처음부터 기대 자재로 점유(관측 시각 없음, 읽은 순간)라 E2 를 얻는다. 대역은 슬롯을
     * 채우지 않으므로 이렇게 둔다. [rackPresent] 가 랙 도착 신호의 값이다.
     */
    private fun filledCell(rackPresent: String): String = HostBench.STANDARD_CELL
        .replace("\"occupied\":false,\"material\":null", "\"occupied\":true,\"material\":\"${HostBench.MATERIAL}\"")
        .replace("\"name\":\"rack_present\",\"location\":\"RACK-204\",\"kind\":\"BOOLEAN\",\"safety\":false,\"value\":\"false\"",
            "\"name\":\"rack_present\",\"location\":\"RACK-204\",\"kind\":\"BOOLEAN\",\"safety\":false,\"value\":\"$rackPresent\"")

    private fun HostBench.nextPump() {
        mimic.server.advance(java.time.Duration.ofSeconds(1))
        awaitPump()
        mimic.server.advance(java.time.Duration.ofSeconds(1))
        awaitPump()
    }

    @Test
    fun `버전 1 로 도는 실행 중 버전 2 를 활성화하면 옛 실행은 대기 없이 끝나고 새 작업 지시는 rack_present 를 기다렸다 진행한다`() {
        HostBench().use { bench ->
            bench.cellBody = filledCell(rackPresent = "false")
            bench.nextPump()
            assertEquals(1, bench.activated(bench.dataV1()))
            val first = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01", "RACK-204.S02"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", first["result"].asText(), first.toString())
            val oldRun = first["executionId"].asText()
            assertEquals(1, bench.execution(oldRun)!!["missionVersion"].asInt())

            assertEquals(2, bench.activated(bench.arrivalWait()))
            val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")
            val old = bench.driveUntil(oldRun, settled)
            assertEquals("PHYSICALLY_DONE", old["physicalState"].asText(), old.toString())
            assertEquals(1, old["missionVersion"].asInt())
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02"), old["units"].map { it["unitId"].asText() })

            val second = bench.post("/host/job-orders", request(rack("JO-2", "RACK-204.S03", "RACK-204.S04"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", second["result"].asText(), second.toString())
            val newRun = second["executionId"].asText()
            // 신호를 켜기 전에는 기한(120초) 안에서만 민다. 대기 단위가 풀리지 않고 기체도 움직이지 않는다.
            repeat(2) {
                bench.mimic.server.advance(java.time.Duration.ofSeconds(5))
                bench.awaitPump()
            }
            val waiting = bench.execution(newRun)!!
            assertEquals(2, waiting["missionVersion"].asInt())
            assertEquals("rack-arrival", waiting["units"][0]["unitId"].asText())
            assertTrue(waiting["units"][0]["state"].asText() != "DONE", waiting.toString())
            assertEquals(2, bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.size })

            bench.cellBody = filledCell(rackPresent = "true")
            val done = bench.driveUntil(newRun, settled)
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            assertEquals(listOf("rack-arrival", "RACK-204.S03", "RACK-204.S04"), done["jobResponse"]["completedUnits"].map { it.asText() })
            assertEquals("E2", done["units"][0]["reached"].asText())

            // 시드 0 에서 humanoid-01 의 pick_place 넷이 실패 모드 없이 끝난다(S3b 스펙 §3 의 통합 시나리오가 기대는 사실).
            val tasks = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.map { it.skillType to it.machine.state.name } }
            assertEquals(List(4) { "pick_place" to "SUCCEEDED" }, tasks)
        }
    }

    @Test
    fun `버전 2 의 대기가 기한을 넘기면 실행은 ABORTED 이고 작업 응답에 SIGNAL_DEADLINE 이 남으며 기체는 다시 배정할 수 있다`() {
        HostBench().use { bench ->
            bench.cellBody = filledCell(rackPresent = "false")
            bench.nextPump()
            assertEquals(1, bench.activated(bench.arrivalWait()))
            val submitted = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01"), "candidates", HUMANOID)).body!!
            val executionId = submitted["executionId"].asText()
            val aborted = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertEquals("ABORTED", aborted["physicalState"].asText(), aborted.toString())
            assertEquals("SIGNAL_DEADLINE", aborted["jobResponse"]["incompleteUnits"]["rack-arrival"].asText(), aborted.toString())
            assertEquals(0, bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.size })

            val judged = bench.post("/host/eligibility", request(rack("JO-2", "RACK-204.S02"), "robotIds", HUMANOID)).body!!["robots"].single()
            assertEquals(true, judged["passed"].asBoolean(), judged.toString())
        }
    }

    @Test
    fun `재기동해도 활성 버전 번호가 같고 다음 활성화는 가장 큰 번호 더하기 1 이다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            assertEquals(1, bench.activated(bench.dataV1()))
            assertEquals(2, bench.activated(bench.arrivalWait()))

            bench.restartHost()
            bench.awaitPump()
            assertEquals(2, bench.activeVersion().asInt())
            val submitted = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
            val execution = bench.execution(submitted["executionId"].asText())!!
            assertEquals(2, execution["missionVersion"].asInt())
            assertEquals("rack-arrival", execution["units"][0]["unitId"].asText())

            // 재기동 뒤에도 번호는 DB 의 최대 + 1 이다. 메모리에서 1부터 세지 않는다.
            assertEquals(3, bench.activated(bench.dataV1()))
            assertEquals(listOf(3, 2, 1), bench.get("/host/missions/$PSR")["versions"].map { it["version"].asInt() })
        }
    }

    @Test
    fun `저장된 정의를 파싱하지 못하면 호스트 기동이 멈추고 이유를 남긴다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            assertEquals(1, bench.activated(bench.dataV1()))
            // 트리거는 INSERT 를 막지 않는다. 읽을 수 없는 정의를 버전 2 로 직접 넣는다.
            PostgresSupport.execute(
                "INSERT INTO mission.mission_version (work_master_id, version, draft_id, definition, activated_by, reason, request_id) " +
                    "SELECT 'PrepareSequencedRack', 2, draft_id, '{\"schemaVersion\": 1}', 'x', 'x', '${UUID.randomUUID()}' FROM mission.draft LIMIT 1",
            )
            val failure = assertFailsWith<Exception> { bench.restartHost() }
            val message = generateSequence<Throwable>(failure) { it.cause }.joinToString(" / ") { it.message.orEmpty() }
            assertTrue("PrepareSequencedRack 버전 2" in message && "읽지 못했다" in message, message)
        }
    }

    @Test
    fun `요청 id 로 초안 모의 실행 버전을 다시 찾고 없으면 404 이며 이미 쓴 요청 id 는 409 다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftRequest = UUID.randomUUID()
            val draftId = bench.saveDraft(bench.dataV1(), draftRequest).body!!["draft"]["draftId"].asLong()
            val mockRequest = UUID.randomUUID()
            val mockRunId = bench.mockRun(draftId, requestId = mockRequest)["mockRun"]["mockRunId"].asLong()
            val versionRequest = UUID.randomUUID()
            assertEquals("ACTIVATED", bench.activate(draftId, requestId = versionRequest).body!!["result"].asText())

            val draft = bench.get("/host/missions/requests/$draftRequest")
            assertEquals(draftId, draft["draft"]["draftId"].asLong())
            assertTrue(draft["mockRun"].isNull && draft["version"].isNull, draft.toString())
            val mock = bench.get("/host/missions/requests/$mockRequest")
            assertEquals(mockRunId, mock["mockRun"]["mockRunId"].asLong())
            assertTrue(mock["draft"].isNull)
            val version = bench.get("/host/missions/requests/$versionRequest")
            assertEquals(1, version["version"]["version"].asInt())
            assertEquals(PSR, version["version"]["workMasterId"].asText())

            val missing = bench.fetch("/host/missions/requests/${UUID.randomUUID()}")
            assertEquals(404, missing.status)
            assertEquals("REQUEST_NOT_FOUND", missing.body!!["error"].asText())
            assertEquals(400, bench.fetch("/host/missions/requests/not-a-uuid").status)

            // 요청 id 하나는 조작 하나다. 다른 표의 행이라도 다시 쓰면 409 이고 아무것도 남지 않는다.
            listOf(draftRequest, mockRequest, versionRequest).forEach { used ->
                assertEquals(409, bench.saveDraft(bench.dataV1(), used).status)
                val reused = bench.activate(draftId, requestId = used)
                assertEquals(409, reused.status)
                assertEquals("REQUEST_ID_REUSED", reused.body!!["error"].asText())
            }
            assertEquals(1, bench.get("/host/missions/$PSR")["drafts"].size())
            assertEquals(listOf(1), bench.get("/host/missions/$PSR")["versions"].map { it["version"].asInt() })
        }
    }

    @Test
    fun `활성화는 호스트 잠금을 쥔 동안 기다리고 놓으면 선다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.dataV1())
            assertEquals("PASSED", bench.mockRun(draftId)["result"].asText())

            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread { bench.host.exclusive { held.countDown(); release.await() } }.apply { start() }
            held.await()
            val activation = CompletableFuture.supplyAsync { bench.activate(draftId) }
            try {
                Thread.sleep(500)
                assertFalse(activation.isDone, "호스트 잠금을 쥔 동안 활성화가 끝났다: ${if (activation.isDone) activation.get() else ""}")
                assertTrue(bench.activeVersion().isNull)
            } finally {
                release.countDown()
                holder.join()
            }
            val done = activation.get(30, TimeUnit.SECONDS)
            assertEquals("ACTIVATED", done.body!!["result"].asText(), done.body.toString())
            assertEquals(1, bench.activeVersion().asInt())
        }
    }

    @Test
    fun `활성화가 호스트 잠금을 기다리는 동안 그 요청 id 를 재조회하면 409 REQUEST_IN_PROGRESS 이고 끝나면 버전 행이다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val draftId = bench.draft(bench.dataV1())
            assertEquals("PASSED", bench.mockRun(draftId)["result"].asText())

            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread { bench.host.exclusive { held.countDown(); release.await() } }.apply { start() }
            held.await()
            val requestId = UUID.randomUUID()
            val activation = CompletableFuture.supplyAsync { bench.activate(draftId, requestId = requestId) }
            try {
                // 활성화 요청이 호스트에 닿기 전에는 404 다. 닿은 뒤로는 잠금을 기다리는 동안 내내 처리 중이어야 한다.
                val deadline = Instant.now().plusSeconds(10)
                var requery = bench.fetch("/host/missions/requests/$requestId")
                while (requery.status == 404 && Instant.now().isBefore(deadline)) {
                    Thread.sleep(50)
                    requery = bench.fetch("/host/missions/requests/$requestId")
                }
                assertEquals(409, requery.status, requery.body.toString())
                assertEquals("REQUEST_IN_PROGRESS", requery.body!!["error"].asText())
                assertFalse(activation.isDone, "호스트 잠금을 쥔 동안 활성화가 끝났다")
                // 처리 중인 요청 id 를 다른 조작이 쓰면 재사용이다. 행이 아직 없어도 남기지 않는다.
                val reused = bench.saveDraft(bench.dataV1(), requestId)
                assertEquals(409, reused.status)
                assertEquals("REQUEST_ID_REUSED", reused.body!!["error"].asText())
            } finally {
                release.countDown()
                holder.join()
            }
            val done = activation.get(30, TimeUnit.SECONDS)
            assertEquals("ACTIVATED", done.body!!["result"].asText(), done.body.toString())
            assertEquals(1, bench.get("/host/missions/requests/$requestId")["version"]["version"].asInt())
            assertEquals(1, bench.get("/host/missions/$PSR")["drafts"].size())
        }
    }

    @Test
    fun `요청 본문이 틀리면 400 이고 없는 초안은 404 이며 JSON 이 아니면 415 다`() {
        HostBench().use { bench ->
            val draftId = bench.draft(bench.dataV1())
            val bad = mapOf(
                "/host/missions/$PSR/drafts" to listOf(
                    """{"actor":"lee","requestId":"${UUID.randomUUID()}"}""",
                    """{"definition":{},"actor":"lee","requestId":"${UUID.randomUUID()}"}""",
                    """{"definition":"x","actor":" ","requestId":"${UUID.randomUUID()}"}""",
                    """{"definition":"x","actor":"lee","requestId":"nope"}""",
                    "{not json",
                ),
                "/host/missions/drafts/$draftId/validate" to listOf("""{}""", """{"robotIds":[""]}""", """{"robotIds":"humanoid-01"}"""),
                "/host/missions/drafts/$draftId/mock-run" to listOf("""{"robotIds":[]}""", """{"requestId":"${UUID.randomUUID()}"}"""),
                "/host/missions/drafts/$draftId/activate" to listOf(
                    """{"actor":"lee","reason":"","robotIds":[],"requestId":"${UUID.randomUUID()}"}""",
                    """{"actor":"lee","robotIds":[],"requestId":"${UUID.randomUUID()}"}""",
                    """{"reason":"r","robotIds":[],"requestId":"${UUID.randomUUID()}"}""",
                ),
            )
            bad.forEach { (path, bodies) ->
                bodies.forEach { body ->
                    val reply = bench.post(path, body)
                    assertEquals(400, reply.status, "$path $body ${reply.body}")
                    assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), "$path $body")
                }
                assertEquals(415, bench.post(path, bodies.first(), contentType = "text/plain").status, path)
            }
            assertEquals(400, bench.post("/host/missions/drafts/x/validate", """{"robotIds":[]}""").status)
            val missing = bench.post("/host/missions/drafts/999999/validate", """{"robotIds":[]}""")
            assertEquals(404, missing.status)
            assertEquals("DRAFT_NOT_FOUND", missing.body!!["error"].asText())
            assertEquals(1, bench.get("/host/missions/$PSR")["drafts"].size())
        }
    }

    @Test
    fun `신호 조작은 현장에 그대로 넘기고 현장의 응답을 그대로 돌려주며 현장이 안 닿으면 503 이다`() {
        HostBench().use { bench ->
            val ok = bench.post("/host/cell/signals/rack_present", """{"value":"true"}""")
            assertEquals(200, ok.status)
            assertEquals("true", ok.body!!["value"].asText())
            assertEquals(Triple("rack_present", "application/json", """{"value":"true"}"""), bench.signalWrites.single())

            listOf(
                403 to """{"error":"SAFETY_SIGNAL_READ_ONLY","detail":"x"}""",
                404 to """{"error":"UNKNOWN_SIGNAL","detail":"x"}""",
                400 to """{"error":"SIGNAL_VALUE_INVALID","detail":"x"}""",
            ).forEach { (status, body) ->
                bench.signalReply = status to body
                val relayed = bench.post("/host/cell/signals/guard_closed", """{"value":"false"}""")
                assertEquals(status, relayed.status)
                assertEquals(JSON.readTree(body), relayed.body)
            }
            assertEquals(415, bench.post("/host/cell/signals/rack_present", """{"value":"true"}""", contentType = "text/plain").status)

            bench.stopCell()
            val silent = bench.post("/host/cell/signals/rack_present", """{"value":"true"}""")
            assertEquals(503, silent.status)
            assertEquals("CELL_SILENT", silent.body!!["error"].asText())
        }
    }

    @Test
    fun `GET host cell 에 신호가 실리고 미들웨어의 셀 신호는 그 값을 낸다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val signals = bench.get("/host/cell")["cell"]["signals"]
            assertEquals(listOf("rack_present", "guard_closed", "lot_code"), signals.map { it["name"].asText() })
            assertEquals(listOf("false", "true", "LOT-0001"), signals.map { it["value"].asText() })
            assertTrue(signals.all { it["value"].isTextual })
            assertEquals("BOOLEAN", signals[0]["kind"].asText())
            assertEquals(listOf("presentations", "slots", "signals"), bench.get("/host/cell")["cell"].fieldNames().asSequence().toList())
            assertEquals(listOf("name", "location", "kind", "safety", "value", "observedAt"), signals[0].fieldNames().asSequence().toList())
            assertNotNull(bench.host.cell()?.signals)
        }
    }

    private companion object {
        const val PSR = "PrepareSequencedRack"
    }
}
```

`mission-host/src/test/kotlin/dev/picasso/ops/host/MockRunnerTest.kt`:

```kotlin
package dev.picasso.ops.host

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.middleware.EquipmentUse
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.mission.DefinedCapability
import dev.picasso.middleware.mission.MissionDefinition
import dev.picasso.middleware.mission.MissionDefinitionParser
import dev.picasso.middleware.mission.MissionParse
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.mission.MissionTemplates
import dev.picasso.ops.host.mission.MockRunFailure
import dev.picasso.ops.host.mission.MockRunner
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 모의 실행기(S3b 스펙 §6.4, T8). DB·스프링 없이 하네스 mimic 과 미들웨어만 세운다. 표본 작업 지시는 현장 표준 셀 대역
 * 스냅숏으로 만든다.
 */
class MockRunnerTest {

    private val cell = CellBandClient.parse(HostBench.JSON.readTree(HostBench.STANDARD_CELL))

    private fun runner(virtualLimit: Duration = Duration.ofMinutes(10), wallLimit: Duration = MockRunner.WALL_LIMIT) =
        MockRunner(HostBench.MOCK_PROFILE, HostBench.SCHEMA, virtualLimit, wallLimit)

    private fun order(): JobOrder = checkNotNull(MockRunner.sampleOrder(PrepareSequencedRack.WORK_MASTER, 7, cell))

    private fun template(id: String): String = MissionTemplates.of(PrepareSequencedRack.WORK_MASTER).single { it.id == id }.definition

    private fun parsed(text: String): MissionDefinition = when (val p = MissionDefinitionParser.parse(text)) {
        is MissionParse.Parsed -> p.definition
        is MissionParse.Unreadable -> error("정의를 못 읽었다: ${p.problems}")
    }

    @Test
    fun `표본 작업 지시는 스냅숏의 앞 슬롯 둘과 자재를 든 제시 자리로 만들고 자재 수가 슬롯 수와 같다`() {
        val order = order()
        assertEquals("MOCK-7", order.jobOrderId)
        assertEquals(PrepareSequencedRack.WORK_MASTER, order.workMasterId)
        assertEquals(Evidence.E2, order.requiredEvidence)
        assertEquals(listOf("RACK-204.S01", "RACK-204.S02", HostBench.SOURCE), order.equipmentRequirements.map { it.id })
        assertEquals(listOf(EquipmentUse.DESTINATION, EquipmentUse.DESTINATION, EquipmentUse.SOURCE), order.equipmentRequirements.map { it.equipmentUse })
        assertTrue(order.equipmentRequirements.all { it.properties[EquipmentUse.PROP_MATERIAL] == HostBench.MATERIAL })
        assertEquals(mapOf(HostBench.MATERIAL to 2), order.materialRequirements.associate { it.materialDefinitionId to it.quantity })

        assertNull(MockRunner.sampleOrder(PrepareSequencedRack.WORK_MASTER, 7, cell.copy(slots = emptyList())))
        assertNull(MockRunner.sampleOrder(PrepareSequencedRack.WORK_MASTER, 7, cell.copy(presentations = emptyList())))
    }

    @Test
    fun `버전 1 모양은 이상적 현장에서 모든 단위가 E2 로 완료되어 통과한다`() {
        val result = runner().run(parsed(template(MissionTemplates.DATA_V1)), order())
        assertTrue(result.passed, result.toString())
        assertNull(result.failure)
        assertNull(result.detail)
        assertEquals("PHYSICALLY_DONE", result.physicalState)
        assertEquals(listOf("RACK-204.S01", "RACK-204.S02"), result.units.map { it.unitId })
        assertTrue(result.units.all { it.state == "DONE" && it.reached == "E2" && it.skillType == "pick_place" }, result.units.toString())
        assertEquals(MockRunner.ROBOT_ID, result.robotId)
        // pick_place 둘(45초 안팎)을 차례로 돈다.
        assertTrue(result.virtualElapsedSeconds in 80..300, result.toString())
    }

    @Test
    fun `대기 노드 버전 2 모양은 대기 단위가 기대 값을 읽어 완료되고 통과한다`() {
        val result = runner().run(parsed(template(MissionTemplates.ARRIVAL_WAIT)), order())
        assertTrue(result.passed, result.toString())
        assertEquals(listOf("rack-arrival", "RACK-204.S01", "RACK-204.S02"), result.units.map { it.unitId })
        val wait = result.units.first()
        assertEquals("SIGNAL", wait.route)
        assertEquals("DONE", wait.state)
        assertEquals("E2", wait.reached)
        // 대기는 첫 pump 에서 풀린다. 기한(120초)을 기다렸다면 경과가 그만큼 늘어난다.
        assertTrue(result.virtualElapsedSeconds < 120 + 90, result.toString())
    }

    @Test
    fun `가상 시간 상한을 줄이면 정착하지 않음으로 실패한다`() {
        val result = runner(virtualLimit = Duration.ofSeconds(30)).run(parsed(template(MissionTemplates.DATA_V1)), order())
        assertEquals(false, result.passed)
        assertEquals(MockRunFailure.NOT_SETTLED, result.failure, result.toString())
        assertNotNull(result.detail)
        assertTrue(result.virtualElapsedSeconds >= 30)
        assertTrue(result.units.any { it.state != "DONE" })
    }

    @Test
    fun `실제 시간 상한을 넘기면 실제 시간 상한으로 실패한다`() {
        val result = runner(wallLimit = Duration.ofMillis(1)).run(parsed(template(MissionTemplates.DATA_V1)), order())
        assertEquals(MockRunFailure.WALL_CLOCK_LIMIT, result.failure, result.toString())
        assertEquals(false, result.passed)
    }

    @Test
    fun `같은 신호를 기대 값이 다른 두 대기 노드가 쓰면 돌리지 않고 정의 실패다`() {
        val two = template(MissionTemplates.ARRIVAL_WAIT).replace(
            "\"onDeadline\": \"ABORTED\"},",
            "\"onDeadline\": \"ABORTED\"},\n{\"kind\": \"wait\", \"id\": \"rack-gone\", \"signal\": \"rack_present\", \"expect\": \"false\", \"deadlineSeconds\": 60, \"onDeadline\": \"ABORTED\"},",
        )
        val definition = parsed(two)
        assertEquals(2, definition.steps.count { it.id.startsWith("rack-") })
        val result = runner().run(definition, order())
        assertEquals(MockRunFailure.DEFINITION, result.failure, result.toString())
        assertTrue("rack_present" in result.detail!! && "rack-arrival=true" in result.detail!! && "rack-gone=false" in result.detail!!, result.detail)
        assertNull(result.physicalState)
        assertEquals(0, result.virtualElapsedSeconds)
    }

    @Test
    fun `표본 작업 지시가 정의의 최고 근거 등급을 넘으면 실행이 서지 않아 SUBMISSION_REJECTED 다`() {
        val e0 = template(MissionTemplates.DATA_V1).replace("\"maxEvidence\": \"E2\"", "\"maxEvidence\": \"E0\"")
        val result = runner().run(parsed(e0), order())
        assertEquals(MockRunFailure.SUBMISSION_REJECTED, result.failure, result.toString())
        assertNull(result.physicalState)
        assertTrue(result.units.isEmpty())
    }

    @Test
    fun `버전 1 템플릿은 코드 PrepareSequencedRack 과 같은 단위를 계획한다`() {
        val order = order()
        val defined = DefinedCapability(parsed(template(MissionTemplates.DATA_V1))).plan(order)
        val coded = PrepareSequencedRack().plan(order)
        assertEquals(coded.map { it.unitId }, defined.map { it.unitId })
        assertEquals(coded.map { it.skillType }, defined.map { it.skillType })
        assertEquals(coded.map { it.parameters }, defined.map { it.parameters })
        assertEquals(coded.map { it.expectedIdentity }, defined.map { it.expectedIdentity })
        assertEquals(coded.map { it.source }, defined.map { it.source })
        assertEquals(coded.map { it.destination }, defined.map { it.destination })
    }

    @Test
    fun `모의 실행 프로파일은 현장 humanoid 프로파일에서 실패 모드만 뺀 사본이다`() {
        val site = HostBench.JSON.readTree(Files.readString(HostBench.ROOT.resolve("picasso/profile/profiles/humanoid-a.json"))) as ObjectNode
        val mock = HostBench.JSON.readTree(Files.readString(HostBench.MOCK_PROFILE)) as ObjectNode
        assertTrue(site["failure_modes"].size() > 0, "현장 프로파일에 실패 모드가 없다. 사본을 둘 까닭을 다시 볼 것")
        assertEquals(0, mock["failure_modes"].size())
        site.remove("failure_modes")
        mock.remove("failure_modes")
        assertEquals(site, mock)
    }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/task2.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/task2.patch"
```

```diff
diff --git a/mission-host/build.gradle.kts b/mission-host/build.gradle.kts
index 84d579c..379fc42 100644
--- a/mission-host/build.gradle.kts
+++ b/mission-host/build.gradle.kts
@@ -1,5 +1,6 @@
-// 실행 호스트(S3a 스펙 §7). picasso 미들웨어를 세워 mimic 기체에서 작업 지시를 실행한다. 운영 서비스가 picasso 를 쓰지 않는
-// 경계(`checkNoPicassoOnMain`)를 지키도록 별도 프로세스로 둔다(결정 1). 그 검사는 이 모듈에 걸지 않는다.
+// 실행 호스트(S3a 스펙 §7, S3b 스펙 §6). picasso 미들웨어를 세워 mimic 기체에서 작업 지시를 실행하고, 임무 버전을 저장·검증·
+// 모의 실행·활성화한다. 운영 서비스가 picasso 를 쓰지 않는 경계(`checkNoPicassoOnMain`)를 지키도록 별도 프로세스로 둔다(결정 1).
+// 그 검사는 이 모듈에 걸지 않는다.
 plugins {
     application
 }
@@ -13,23 +14,33 @@ dependencies {
     implementation("dev.picasso:picasso")
     // picasso 가 client 를 implementation 으로 들므로 ClientRobotPort 에 넘길 PicassoClient 를 직접 의존한다.
     implementation("dev.picasso:client")
+    // 모의 실행이 프로세스 안에 별도 mimic 과 가상 시계를 띄운다(S3b 스펙 §6.4). 하네스가 in-process 로 세우고 mimic 은 그 안에서 돈다.
+    implementation("dev.picasso:harness")
+    implementation("dev.picasso:mimic")
 
     // 운영 서비스와 같이 BOM 만 쓰고 Spring Boot 플러그인은 붙이지 않는다. 판이 picasso 카탈로그 밖으로 나가지 않는다.
     implementation(platform(libs.spring.boot.bom))
     implementation(libs.spring.boot.starter.web)
     implementation(libs.jackson.module.kotlin)
+    // 임무 버전 저장(S3b 스펙 §6.1, T3). 같은 Postgres 의 자기 스키마 mission 이다.
+    implementation("org.springframework.boot:spring-boot-starter-jdbc")
+    implementation(libs.flyway.core)
+    runtimeOnly(libs.flyway.postgresql)
+    runtimeOnly(libs.postgresql)
 
     testImplementation(kotlin("test"))
-    // 호스트 시험은 mimic 을 이 JVM 의 Netty 포트에 띄운다. 운영 배치와 같은 네트워크 채널을 지난다.
-    testImplementation("dev.picasso:mimic")
-    // 통합 시험은 registry·운영 서비스와 한 JVM 에 호스트를 띄우므로 JDBC 자동설정이 클래스패스에 온다. 호스트 시험도
-    // 같은 조건에서 돌려 «DB 없이 뜬다» 를 매번 확인한다(MissionHostApplication 의 자동설정 제외).
-    testRuntimeOnly("org.springframework.boot:spring-boot-starter-jdbc")
-    testRuntimeOnly(libs.postgresql)
+    // 시험용 Postgres 는 registry 의 testFixtures 를 쓴다. 복사하지 않는다(운영 서비스와 같음).
+    testImplementation(testFixtures("dev.picasso:registry"))
+}
+
+// 저장소 루트에서 돈다. 모의 실행 프로파일·스키마 경로(host.mock-run.*)가 루트 기준이다(site 의 run 과 같음).
+tasks.named<JavaExec>("run") {
+    workingDir = rootDir
 }
 
 tasks.withType<Test>().configureEach {
     // 시험이 저장소 파일을 읽는다. 선언하지 않으면 파일을 고쳐도 시험이 UP-TO-DATE 로 넘어간다.
     inputs.dir(rootProject.file("picasso/profile/profiles")).withPropertyName("profiles")
     inputs.dir(rootProject.file("picasso/profile/schema")).withPropertyName("profileSchema")
+    inputs.dir(rootProject.file("mission-host/mock-run")).withPropertyName("mockRunProfile")
 }
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
index afb9994..38e0d2f 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
@@ -8,10 +8,11 @@ import dev.picasso.middleware.PrepareSequencedRack
 import dev.picasso.middleware.Route
 import dev.picasso.middleware.RobotPort
 import dev.picasso.middleware.Unassigned
-import dev.picasso.middleware.mission.InMemoryMissionCatalog
 import dev.picasso.ops.host.cell.CellBandClient
 import dev.picasso.ops.host.cell.CellBandSignals
 import dev.picasso.ops.host.cell.CellSnapshot
+import dev.picasso.ops.host.mission.SiteInputs
+import dev.picasso.ops.host.mission.StoredMissionCatalog
 import org.slf4j.LoggerFactory
 import java.time.Duration
 import java.time.Instant
@@ -114,6 +115,9 @@ data class ExecutionsView(val instanceId: String, val pumpedAt: Instant?, val ex
  * 미들웨어에는 스레드도 잠금도 없다. pump, 판정, 제출, 조회를 모두 [lock] 하나 아래에서 돈다. 잠금 순서는 호스트 잠금에서
  * mimic 엔진 잠금으로 한 방향뿐이다(gRPC 호출이 잠금 아래에서 나간다).
  *
+ * 임무 버전 활성화도 이 잠금 아래에서 한다([exclusive], S3b 스펙 T2). 판정과 배정 사이에 활성화가 끼면 한 제출 안에서 판정
+ * 계획과 실행 계획의 버전이 갈린다. DB 는 잠금 안에서 부를 수 있으나 그 반대(DB 연결을 쥔 채 이 잠금을 기다림)는 없다.
+ *
  * 공정 잠금이다. 제출이 mimic 을 기다리느라 운영 서비스의 요청 제한을 넘기면 운영 서비스가 실행 목록으로 재조회하는데,
  * 먼저 기다린 제출이 먼저 잡아야 재조회가 제출보다 앞서 «반영 안 됨» 을 남기지 않는다. `synchronized` 는 순서를 보장하지
  * 않고, 가상 스레드가 그것을 기다리면 캐리어 스레드를 붙잡는다.
@@ -132,20 +136,21 @@ data class ExecutionsView(val instanceId: String, val pumpedAt: Instant?, val ex
  *
  * 상위 시스템이 없어 `ack` 하지 않는다(스펙 §7.7). 아웃박스가 계속 자라는 것은 한계다(스펙 §12).
  *
- * @param robots 하류 포트. 판정의 케이퍼빌리티도 이것으로 묻는다(`PicassoClient` 가 세대별로 캐시한다).
+ * @param robots 하위 포트. 판정의 케이퍼빌리티도 이것으로 묻는다(`PicassoClient` 가 세대별로 캐시한다). 그 캐시는 잠금 밖에서
+ *   안전하지 않으므로 케이퍼빌리티는 늘 [lock] 아래에서 묻는다.
+ * @param catalog 임무 카탈로그(S3b 스펙 T1). 미들웨어에 넘긴 것과 같은 참조로 스킬 적합을 판정한다. 기동 때 DB 의 활성 버전으로
+ *   세운 것을 받는다.
  */
 class MissionHost(
     private val robots: RobotPort,
     private val cellBand: CellBandClient,
     private val clock: HostClock,
+    private val catalog: StoredMissionCatalog = StoredMissionCatalog(),
 ) : AutoCloseable {
 
     private val lock = ReentrantLock(true)
     private val signals = CellBandSignals()
 
-    /** 코드 정의 임무의 카탈로그. 미들웨어에 넘긴 것과 같은 참조로 스킬 적합을 판정한다. */
-    private val catalog = InMemoryMissionCatalog(now = clock::now)
-
     private val middleware = Middleware(robots = robots, cell = signals, now = clock::now, missions = catalog)
 
     private var pumpedAt: Instant? = null
@@ -231,6 +236,24 @@ class MissionHost(
     /** 마지막 pump 가 읽은 셀 대역 스냅숏. 못 읽었으면 `null` 이다. */
     fun cell(): CellSnapshot? = lock.withLock { latestCell }
 
+    /** [action] 을 호스트 잠금 아래에서 돈다. 임무 버전 활성화가 쓴다(S3b 스펙 T2). 잠금은 재진입된다. */
+    fun <T> exclusive(action: () -> T): T = lock.withLock(action)
+
+    /** 검증 입력을 잠금 아래에서 한 번에 읽는다(S3b 스펙 T7). 검증과 모의 실행이 쓴다. 그동안 DB 연결을 쥐지 않는다. */
+    fun siteInputs(robotIds: List<String>): SiteInputs = lock.withLock { siteInputsLocked(robotIds) }
+
+    /**
+     * 검증 입력. 신호 사양은 마지막 pump 가 읽은 셀 대역 스냅숏에서, 현장 스킬은 [robotIds] 마다 기체가 선언한 스킬에서
+     * 온다. 케이퍼빌리티를 못 물어본 기체는 `null` 이다. 이미 잠금을 쥔 쪽(활성화)만 부른다.
+     */
+    fun siteInputsLocked(robotIds: List<String>): SiteInputs {
+        check(lock.isHeldByCurrentThread) { "검증 입력은 호스트 잠금 아래에서 읽는다" }
+        return SiteInputs(
+            cell = latestCell,
+            skillsByRobot = robotIds.distinct().associateWith { id -> robots.capabilities(id)?.skillsList?.map { it.skillType }?.toSet() },
+        )
+    }
+
     /**
      * 스킬 적합은 지금 활성 정의로 작업 지시를 계획해, 경로가 로봇인 단위의 스킬이 기체가 선언한 스킬에 다 있는가다.
      * 도는 실행은 그 기체의 실행 중 물리 상태가 정착하지 않은 것이다. 운영자 보류에 선 실행도 도는 실행이다(스펙 §12).
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt
index b3b4eb1..9daef14 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt
@@ -1,8 +1,16 @@
 package dev.picasso.ops.host
 
+import com.fasterxml.jackson.databind.ObjectMapper
 import dev.picasso.client.PicassoClient
 import dev.picasso.middleware.ClientRobotPort
+import dev.picasso.middleware.RobotPort
 import dev.picasso.ops.host.cell.CellBandClient
+import dev.picasso.ops.host.mission.MissionVersions
+import dev.picasso.ops.host.mission.MockRunner
+import dev.picasso.ops.host.mission.StoredMissionCatalog
+import dev.picasso.ops.host.store.HostSchema
+import dev.picasso.ops.host.store.HostSchemaMigrated
+import dev.picasso.ops.host.store.MissionStore
 import io.grpc.ManagedChannel
 import io.grpc.ManagedChannelBuilder
 import org.springframework.beans.factory.annotation.Value
@@ -12,19 +20,18 @@ import org.springframework.boot.builder.SpringApplicationBuilder
 import org.springframework.context.ApplicationContextInitializer
 import org.springframework.context.ConfigurableApplicationContext
 import org.springframework.context.annotation.Bean
+import org.springframework.jdbc.core.simple.JdbcClient
+import java.nio.file.Path
+import java.time.Duration
+import javax.sql.DataSource
 
 /**
- * 실행 호스트(S3a 스펙 §7). DB 가 없다(T5).
+ * 실행 호스트(S3a 스펙 §7, S3b 스펙 §6). 임무 버전을 같은 Postgres 의 자기 스키마 `mission` 에 둔다(S3b T3).
  *
- * JDBC·Flyway 자동설정을 이름으로 끈다. 이 모듈의 클래스패스에는 없지만 통합 시험은 registry·운영 서비스와 한 JVM 에
- * 호스트를 띄우고, 그때 JDBC 가 클래스패스에 와서 데이터 소스 주소 없이 기동이 멈춘다.
+ * Flyway 자동설정은 이름으로 끈다. 마이그레이션은 [HostSchema] 가 맡는다. 통합 시험은 registry·운영 서비스와 한 JVM 에
+ * 호스트를 띄우고, 그때 자동설정이 켜지면 registry 마이그레이션 위치를 집어 온다.
  */
-@SpringBootApplication(
-    excludeName = [
-        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
-        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
-    ],
-)
+@SpringBootApplication(excludeName = ["org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"])
 open class MissionHostApplication {
 
     /** 기본은 실제 시각이다. 시험은 [builder] 로 다른 시계를 먼저 넣고, 그러면 이 빈은 만들어지지 않는다. */
@@ -39,12 +46,55 @@ open class MissionHostApplication {
         return ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
     }
 
+    @Bean
+    open fun robotPort(channel: ManagedChannel): RobotPort = ClientRobotPort(PicassoClient(channel, CLIENT_ID))
+
     @Bean
     open fun cellBand(@Value("\${host.cell.url}") url: String): CellBandClient = CellBandClient(url)
 
+    @Bean
+    open fun hostSchemaMigrated(dataSource: DataSource): HostSchemaMigrated =
+        HostSchemaMigrated(HostSchema.flyway(dataSource).migrate().migrationsExecuted)
+
+    /** [migrated] 는 쓰지 않는다. 받는 것만으로 mission 마이그레이션 뒤에 이 빈이 만들어진다. */
+    @Bean
+    open fun missionStore(
+        jdbc: JdbcClient,
+        @Suppress("UNUSED_PARAMETER") migrated: HostSchemaMigrated,
+    ): MissionStore = MissionStore(jdbc)
+
+    /**
+     * 기동 때 DB 의 활성 버전으로 카탈로그를 세운다(S3b 스펙 §6.2, T1). 다시 검증하지 않는다. 저장된 정의를 파싱하지 못하면
+     * 예외가 나가 기동이 멈춘다.
+     */
+    @Bean
+    open fun missionCatalog(store: MissionStore): StoredMissionCatalog =
+        StoredMissionCatalog().apply { restore(store.activeVersions()) }
+
     @Bean(destroyMethod = "close")
-    open fun missionHost(channel: ManagedChannel, cellBand: CellBandClient, clock: HostClock): MissionHost =
-        MissionHost(ClientRobotPort(PicassoClient(channel, CLIENT_ID)), cellBand, clock).start()
+    open fun missionHost(robots: RobotPort, cellBand: CellBandClient, clock: HostClock, catalog: StoredMissionCatalog): MissionHost =
+        MissionHost(robots, cellBand, clock, catalog).start()
+
+    /**
+     * 모의 실행기(S3b 스펙 §6.4). 프로파일과 스키마 경로는 작업 디렉터리 기준으로 푼다. `:mission-host:run` 은 저장소 루트에서
+     * 돌고(site 의 `run` 과 같음), 시험은 절대 경로를 실행 인자로 넘긴다. 파일이 없으면 기동에서 멈춘다.
+     */
+    @Bean
+    open fun mockRunner(
+        @Value("\${host.mock-run.profile}") profile: String,
+        @Value("\${host.mock-run.schema}") schema: String,
+        @Value("\${host.mock-run.virtual-limit}") virtualLimit: Duration,
+    ): MockRunner = MockRunner(Path.of(profile).toAbsolutePath().normalize(), Path.of(schema).toAbsolutePath().normalize(), virtualLimit)
+
+    @Bean
+    open fun missionVersions(
+        host: MissionHost,
+        store: MissionStore,
+        catalog: StoredMissionCatalog,
+        runner: MockRunner,
+        clock: HostClock,
+        json: ObjectMapper,
+    ): MissionVersions = MissionVersions(host, store, catalog, runner, clock, json)
 
     companion object {
         /** mimic 에 싣는 클라이언트 id. */
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt
index ddf3b12..9e26e21 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt
@@ -3,11 +3,16 @@ package dev.picasso.ops.host.cell
 import com.fasterxml.jackson.databind.JsonNode
 import com.fasterxml.jackson.databind.ObjectMapper
 import dev.picasso.middleware.CellSignals
+import dev.picasso.middleware.NamedSignal
 import dev.picasso.middleware.SlotSignal
+import dev.picasso.middleware.mission.SignalKind
+import dev.picasso.middleware.mission.SignalSpec
 import java.net.URI
+import java.net.URLEncoder
 import java.net.http.HttpClient
 import java.net.http.HttpRequest
 import java.net.http.HttpResponse
+import java.nio.charset.StandardCharsets
 import java.time.Duration
 import java.time.Instant
 
@@ -17,18 +22,54 @@ import java.time.Instant
  */
 data class CellPlace(val id: String, val occupied: Boolean, val material: String?, val observedAt: Instant?)
 
-/** 셀 대역의 한 순간(현장 `GET /cell` 의 본문). 제시 자리와 슬롯을 가른다. */
-data class CellSnapshot(val presentations: List<CellPlace>, val slots: List<CellPlace>)
+/**
+ * 이름 있는 신호 하나(현장 `GET /cell` 의 `signals` 원소, S3b 스펙 §5.2). 사양 칸 넷과 지금 값, 관측 시각이다. 값은 종류와
+ * 상관없이 문자열이다. [observedAt] 이 `null` 이면 현장이 시각을 주지 않은 처음 값이고 읽은 순간이 그 시각이다.
+ */
+data class CellSignal(
+    val name: String,
+    val location: String?,
+    val kind: SignalKind,
+    val safety: Boolean,
+    val value: String,
+    val observedAt: Instant?,
+) {
+    /** 신호 사양의 한 줄. 검증기가 이것을 받는다(T7). 본문 칸이 아니므로 속성이 아니라 함수다. */
+    fun spec(): SignalSpec = SignalSpec(name, location, kind, safety)
+}
+
+/**
+ * 셀 대역의 한 순간(현장 `GET /cell` 의 본문). 제시 자리와 슬롯을 가른다.
+ *
+ * [signals] 가 `null` 이면 현장 본문에 `signals` 칸이 없었다(신호를 선언하지 않는 셀 대역). 빈 목록과 다르다. 빈 목록은
+ * «신호가 하나도 없다» 는 사양이고 `null` 은 «신호 사양을 모른다» 다. 검증은 `null` 을 «못 읽음» 으로 다룬다(T7).
+ */
+data class CellSnapshot(val presentations: List<CellPlace>, val slots: List<CellPlace>, val signals: List<CellSignal>? = null) {
+    /** 신호 사양. 모르면 `null` 이다. 본문 칸이 아니므로 속성이 아니라 함수다(`GET /host/cell` 이 이 모양을 그대로 낸다). */
+    fun signalSpecs(): List<SignalSpec>? = signals?.map { it.spec() }
+}
+
+/** 신호 조작을 현장에 넘긴 결과. 현장이 답하면 그 상태 코드와 본문 그대로다. */
+data class SignalRelay(val status: Int, val contentType: String?, val body: ByteArray)
 
 /**
  * 현장의 셀 대역을 루프백 HTTP 로 읽는다(S3a 스펙 §7.3). 실패하면 `null`(스냅숏 없음)이다. 못 읽은 것을 빈 셀로 접으면
  * 미들웨어가 «말이 없다» 를 «비었다» 로 읽는다.
  *
+ * 신호 조작([writeSignal])도 이것으로 현장에 넘긴다(S3b 스펙 §6.6). 판정은 현장이 하고 호스트는 응답을 그대로 돌려준다.
+ *
  * @param baseUrl 현장 셀 대역의 주소. 경로 `/cell` 을 붙여 부른다.
+ * @param timeout 연결 제한과 셀 대역 읽기의 요청 제한
+ * @param writeTimeout 신호 조작의 요청 제한. [SIGNAL_WRITE_TIMEOUT] 의 까닭을 본다
  */
-class CellBandClient(baseUrl: String, private val timeout: Duration = Duration.ofSeconds(1)) {
+class CellBandClient(
+    baseUrl: String,
+    private val timeout: Duration = Duration.ofSeconds(1),
+    private val writeTimeout: Duration = SIGNAL_WRITE_TIMEOUT,
+) {
 
-    private val uri = URI.create(baseUrl.trimEnd('/') + "/cell")
+    private val base = baseUrl.trimEnd('/')
+    private val uri = URI.create("$base/cell")
     private val http = HttpClient.newBuilder().connectTimeout(timeout).build()
     private val json = ObjectMapper()
 
@@ -45,9 +86,54 @@ class CellBandClient(baseUrl: String, private val timeout: Duration = Duration.o
         null
     }
 
+    /**
+     * `POST /cell/signals/{name}` 을 현장에 그대로 넘긴다. 현장이 안 닿으면(연결 실패, 시간 초과) `null` 이다. 이름은 경로
+     * 조각으로 인코딩한다. 판정(404·400·403)은 현장의 몫이다.
+     */
+    fun writeSignal(name: String, body: ByteArray): SignalRelay? = try {
+        val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
+        val response = http.send(
+            HttpRequest.newBuilder(URI.create("$base/cell/signals/$encoded"))
+                .timeout(writeTimeout)
+                .header("Content-Type", "application/json")
+                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
+                .build(),
+            HttpResponse.BodyHandlers.ofByteArray(),
+        )
+        SignalRelay(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null), response.body())
+    } catch (e: InterruptedException) {
+        Thread.currentThread().interrupt()
+        null
+    } catch (_: Exception) {
+        null
+    }
+
     companion object {
-        /** 본문 모양이 어긋나면 예외다. [fetch] 가 그것을 «스냅숏 없음» 으로 접는다. */
-        fun parse(node: JsonNode): CellSnapshot = CellSnapshot(places(node, "presentations"), places(node, "slots"))
+        /**
+         * 신호 조작의 요청 제한. 현장의 신호 쓰기는 mimic 엔진 잠금을 기다리고, 엔진은 그 잠금 아래에서 registry 로 태스크
+         * 관측을 적재할 수 있다(요청 제한 3초). 1초에서 끊으면 현장이 곧 반영할 쓰기를 «현장이 안 닿음»(503)으로 돌려주게
+         * 된다. 운영 서비스의 호스트 요청 제한(5초)보다는 짧아야 호스트의 503 이 운영 서비스에 닿는다.
+         */
+        val SIGNAL_WRITE_TIMEOUT: Duration = Duration.ofSeconds(4)
+
+        /** 본문 모양이 어긋나면 예외다. [fetch] 가 그것을 «스냅숏 없음» 으로 접는다. `signals` 칸이 없으면 신호 사양을 모른다. */
+        fun parse(node: JsonNode): CellSnapshot =
+            CellSnapshot(places(node, "presentations"), places(node, "slots"), node.get("signals")?.let(::signals))
+
+        private fun signals(node: JsonNode): List<CellSignal> {
+            require(node.isArray) { "signals 가 배열이 아니다" }
+            return node.map { signal ->
+                CellSignal(
+                    name = requireNotNull(signal.get("name")?.takeIf { it.isTextual && it.asText().isNotEmpty() }) { "신호 이름이 없다" }.asText(),
+                    location = signal.get("location")?.takeIf { it.isTextual }?.asText(),
+                    kind = requireNotNull(signal.get("kind")?.takeIf { it.isTextual }) { "신호 종류가 없다" }.asText()
+                        .let { kind -> SignalKind.entries.firstOrNull { it.name == kind } ?: throw IllegalArgumentException("모르는 신호 종류다: $kind") },
+                    safety = requireNotNull(signal.get("safety")?.takeIf { it.isBoolean }) { "safety 가 없다" }.asBoolean(),
+                    value = requireNotNull(signal.get("value")?.takeIf { it.isTextual }) { "신호 값이 문자열이 아니다" }.asText(),
+                    observedAt = signal.get("observedAt")?.takeIf { it.isTextual }?.asText()?.let(Instant::parse),
+                )
+            }
+        }
 
         private fun places(node: JsonNode, field: String): List<CellPlace> {
             val array = requireNotNull(node.get(field)?.takeIf { it.isArray }) { "$field 가 배열이 아니다" }
@@ -69,9 +155,10 @@ class CellBandClient(baseUrl: String, private val timeout: Duration = Duration.o
  *
  * - [observe]: 스냅숏에서 자리 id 로 답한다. 스냅숏에 없는 자리는 `null`(말이 없다)이다.
  * - [holding]: 그 자재를 든 **제시 자리**만 낸다. 채운 슬롯을 대안 자리로 내면 미들웨어가 막 놓은 자재를 다시 집으러 보낸다.
- * - [signal]: 이름 있는 신호는 S3a 에서 다루지 않아 `null`(못 읽었다)이다. S3b 의 몫이다.
+ * - [signal]: 스냅숏의 신호에서 이름으로 값과 관측 시각을 낸다(S3b 스펙 §6.6). 모르는 이름은 `null`(못 읽었다)이고
+ *   설비 대기는 그때 기한까지 기다린다.
  *
- * 스냅숏이 없으면 [observe]·[holding] 모두 `null`(못 물어봄)이다.
+ * 스냅숏이 없으면 셋 모두 `null`(못 물어봄)이다.
  */
 class CellBandSignals : CellSignals {
 
@@ -86,4 +173,7 @@ class CellBandSignals : CellSignals {
 
     override fun holding(material: String): List<String>? =
         snapshot?.presentations?.filter { it.occupied && it.material == material }?.map { it.id }
+
+    override fun signal(name: String): NamedSignal? =
+        snapshot?.signals?.firstOrNull { it.name == name }?.let { NamedSignal(it.value, it.observedAt) }
 }
diff --git a/mission-host/src/main/resources/mission-host.properties b/mission-host/src/main/resources/mission-host.properties
index 16acbf5..de72112 100644
--- a/mission-host/src/main/resources/mission-host.properties
+++ b/mission-host/src/main/resources/mission-host.properties
@@ -10,3 +10,12 @@ server.address=127.0.0.1
 host.mimic.port=${MIMIC_GRPC_PORT}
 # 셀 대역 GET /cell 의 루프백 주소(S3a 스펙 §6.3).
 host.cell.url=http://127.0.0.1:${SITE_CELL_PORT}
+# 임무 버전 저장(S3b 스펙 §6.1). 같은 Postgres 의 자기 스키마 mission 이다. 값은 루트 .env 가 준다.
+spring.datasource.url=${PICASSO_DB_URL}
+spring.datasource.username=${PICASSO_DB_USER}
+spring.datasource.password=${PICASSO_DB_PASSWORD}
+# 모의 실행(S3b 스펙 §6.4). 경로는 작업 디렉터리 기준이다. :mission-host:run 은 저장소 루트에서 돈다.
+host.mock-run.profile=mission-host/mock-run/humanoid-a.json
+host.mock-run.schema=picasso/profile/schema/capability-profile.schema.json
+# 가상 시간 상한. 이 안에 정착하지 않으면 모의 실행은 실패(NOT_SETTLED)다. 실제 시간 상한 30초는 코드에 둔다(T10).
+host.mock-run.virtual-limit=PT10M
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/CellBandTest.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/CellBandTest.kt
index 6342610..3865aee 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/CellBandTest.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/CellBandTest.kt
@@ -1,9 +1,13 @@
 package dev.picasso.ops.host
 
+import dev.picasso.middleware.NamedSignal
 import dev.picasso.middleware.SlotSignal
+import dev.picasso.middleware.mission.SignalKind
+import dev.picasso.middleware.mission.SignalSpec
 import dev.picasso.ops.host.cell.CellBandClient
 import dev.picasso.ops.host.cell.CellBandSignals
 import dev.picasso.ops.host.cell.CellPlace
+import dev.picasso.ops.host.cell.CellSignal
 import dev.picasso.ops.host.cell.CellSnapshot
 import java.time.Instant
 import kotlin.test.Test
@@ -11,7 +15,7 @@ import kotlin.test.assertEquals
 import kotlin.test.assertFailsWith
 import kotlin.test.assertNull
 
-/** 셀 대역 클라이언트의 셀 신호(S3a 스펙 §7.4). */
+/** 셀 대역 클라이언트의 셀 신호(S3a 스펙 §7.4, S3b 스펙 §6.6). */
 class CellBandTest {
 
     private val filledAt = Instant.parse("2026-10-08T00:01:00Z")
@@ -63,6 +67,49 @@ class CellBandTest {
         assertFailsWith<IllegalArgumentException> { CellBandClient.parse(HostBench.JSON.readTree("""{"slots":[]}""")) }
     }
 
+    @Test
+    fun `signal 은 스냅숏의 신호 값과 관측 시각을 내고 모르는 이름이나 스냅숏이 없으면 null 이다`() {
+        val signals = CellBandSignals().apply {
+            snapshot = this@CellBandTest.snapshot.copy(
+                signals = listOf(
+                    CellSignal("rack_present", "RACK-204", SignalKind.BOOLEAN, false, "true", filledAt),
+                    CellSignal("lot_code", null, SignalKind.TEXT, false, "LOT-0001", null),
+                ),
+            )
+        }
+        assertEquals(NamedSignal("true", filledAt), signals.signal("rack_present"))
+        assertEquals(NamedSignal("LOT-0001", null), signals.signal("lot_code"))
+        assertNull(signals.signal("rack_ready"))
+        // 신호 목록이 없는 스냅숏(모르는 사양)과 스냅숏 없음은 둘 다 못 읽음이다.
+        assertNull(CellBandSignals().apply { snapshot = this@CellBandTest.snapshot }.signal("rack_present"))
+        assertNull(CellBandSignals().signal("rack_present"))
+    }
+
+    @Test
+    fun `현장 본문의 신호 목록을 사양과 값으로 읽고 칸이 없으면 모르는 사양이다`() {
+        val read = CellBandClient.parse(HostBench.JSON.readTree(HostBench.STANDARD_CELL))
+        assertEquals(
+            listOf(
+                SignalSpec("rack_present", "RACK-204", SignalKind.BOOLEAN, false),
+                SignalSpec("guard_closed", null, SignalKind.BOOLEAN, true),
+                SignalSpec("lot_code", null, SignalKind.TEXT, false),
+            ),
+            read.signalSpecs(),
+        )
+        assertEquals(listOf("false", "true", "LOT-0001"), read.signals!!.map { it.value })
+
+        assertNull(CellBandClient.parse(HostBench.JSON.readTree("""{"presentations":[],"slots":[]}""")).signals)
+        assertEquals(emptyList(), CellBandClient.parse(HostBench.JSON.readTree("""{"presentations":[],"slots":[],"signals":[]}""")).signalSpecs())
+        listOf(
+            """{"presentations":[],"slots":[],"signals":{}}""",
+            """{"presentations":[],"slots":[],"signals":[{"name":"a","kind":"NUMBER","safety":false,"value":"1"}]}""",
+            """{"presentations":[],"slots":[],"signals":[{"name":"a","kind":"BOOLEAN","safety":false,"value":true}]}""",
+            """{"presentations":[],"slots":[],"signals":[{"name":"a","kind":"BOOLEAN","value":"true"}]}""",
+        ).forEach { body ->
+            assertFailsWith<IllegalArgumentException>(body) { CellBandClient.parse(HostBench.JSON.readTree(body)) }
+        }
+    }
+
     @Test
     fun `닿지 않는 셀 대역은 null 이다`() {
         assertNull(CellBandClient("http://127.0.0.1:1").fetch())
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
index 5ca1094..493de3a 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
@@ -4,6 +4,7 @@ import com.fasterxml.jackson.databind.JsonNode
 import com.fasterxml.jackson.databind.ObjectMapper
 import com.sun.net.httpserver.HttpServer
 import dev.picasso.mimic.cli.MimicCli
+import dev.picasso.registry.PostgresSupport
 import org.springframework.boot.web.context.WebServerApplicationContext
 import org.springframework.context.ConfigurableApplicationContext
 import java.net.InetAddress
@@ -15,14 +16,24 @@ import java.net.http.HttpResponse
 import java.nio.file.Path
 import java.time.Duration
 import java.time.Instant
+import java.util.concurrent.CopyOnWriteArrayList
 
 /**
  * 호스트 시험 세트. 이 JVM 의 Netty 포트에 mimic(humanoid-01·quadruped-01, 가상 시계, registry 없음)을 띄우고, 고정 본문을
  * 내는 셀 대역 대역(stub)과 실행 호스트를 띄운다. 호스트 시계는 mimic 의 가상 시계다(통합 시험이 현장 시계를 넣는 것과 같은 배선).
  *
  * 셀 대역은 현장 모듈에 의존하지 않고 같은 본문 모양을 직접 낸다. 현장 쪽 모양은 현장 시험이 본다.
+ *
+ * 임무 버전 저장은 registry 시험 픽스처의 Postgres 다(S3b 스펙 §6.1). 띄울 때마다 `mission` 스키마를 지운다. 덧붙이기 전용
+ * 트리거가 DELETE·TRUNCATE 를 막으므로 스키마째 지운다. [restartHost] 는 DB 를 그대로 두고 호스트만 다시 띄운다.
+ *
+ * @param mockVirtualLimit 주면 모의 실행의 가상 시간 상한을 이것으로 덮는다.
  */
-class HostBench : AutoCloseable {
+class HostBench(private val mockVirtualLimit: Duration? = null) : AutoCloseable {
+
+    init {
+        PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
+    }
 
     val mimic: MimicCli.Started = checkNotNull(
         MimicCli().start(
@@ -42,24 +53,62 @@ class HostBench : AutoCloseable {
     @Volatile
     var cellBody: String = STANDARD_CELL
 
+    /** 셀 대역 대역이 신호 쓰기에 답할 상태 코드와 본문. */
+    @Volatile
+    var signalReply: Pair<Int, String> = 200 to """{"name":"rack_present","value":"true"}"""
+
+    /** 셀 대역 대역이 받은 신호 쓰기(경로의 이름, Content-Type, 본문). */
+    val signalWrites = CopyOnWriteArrayList<Triple<String, String?, String>>()
+
     private val cell: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
         createContext("/cell") { exchange ->
-            val bytes = cellBody.toByteArray()
+            val path = exchange.requestURI.path
+            val (status, body) = if (path.startsWith("/cell/signals/") && exchange.requestMethod == "POST") {
+                signalWrites += Triple(
+                    path.removePrefix("/cell/signals/"),
+                    exchange.requestHeaders.getFirst("Content-Type"),
+                    exchange.requestBody.readAllBytes().toString(Charsets.UTF_8),
+                )
+                signalReply
+            } else {
+                200 to cellBody
+            }
+            val bytes = body.toByteArray()
             exchange.responseHeaders.add("Content-Type", "application/json")
-            exchange.sendResponseHeaders(200, bytes.size.toLong())
+            exchange.sendResponseHeaders(status, bytes.size.toLong())
             exchange.responseBody.write(bytes)
             exchange.close()
         }
         start()
     }
 
-    private val context: ConfigurableApplicationContext = MissionHostApplication.builder(HostClock { now() }).run(
-        "--server.port=0",
-        "--host.mimic.port=${mimic.server.port}",
-        "--host.cell.url=http://127.0.0.1:${cell.address.port}",
+    private var context: ConfigurableApplicationContext = startHost()
+
+    val url: String get() = "http://127.0.0.1:${(context as WebServerApplicationContext).webServer.port}"
+
+    /** 지금 뜬 호스트의 빈. */
+    val host: MissionHost get() = context.getBean(MissionHost::class.java)
+
+    /** 이 세트와 같은 인자로 호스트를 하나 띄운다. 기동이 실패하면 예외가 그대로 나간다. */
+    fun startHost(): ConfigurableApplicationContext = MissionHostApplication.builder(HostClock { now() }).run(
+        *buildList {
+            add("--server.port=0")
+            add("--host.mimic.port=${mimic.server.port}")
+            add("--host.cell.url=http://127.0.0.1:${cell.address.port}")
+            add("--spring.datasource.url=${PostgresSupport.jdbcUrl}")
+            add("--spring.datasource.username=${PostgresSupport.username}")
+            add("--spring.datasource.password=${PostgresSupport.password}")
+            add("--host.mock-run.profile=$MOCK_PROFILE")
+            add("--host.mock-run.schema=$SCHEMA")
+            mockVirtualLimit?.let { add("--host.mock-run.virtual-limit=$it") }
+        }.toTypedArray(),
     )
 
-    val url = "http://127.0.0.1:${(context as WebServerApplicationContext).webServer.port}"
+    /** 호스트만 닫고 같은 DB 로 다시 띄운다(재기동). mimic 과 셀 대역은 그대로다. */
+    fun restartHost() {
+        context.close()
+        context = startHost()
+    }
 
     fun now(): Instant = mimic.server.exclusive { mimic.instance(HUMANOID)!!.clock.now() }
 
@@ -68,9 +117,14 @@ class HostBench : AutoCloseable {
     data class Reply(val status: Int, val body: JsonNode?)
 
     fun get(path: String): JsonNode {
+        val reply = fetch(path)
+        check(reply.status == 200) { "$path: ${reply.status} ${reply.body}" }
+        return reply.body!!
+    }
+
+    fun fetch(path: String): Reply {
         val response = HTTP.send(HttpRequest.newBuilder(URI.create(url + path)).build(), HttpResponse.BodyHandlers.ofString())
-        check(response.statusCode() == 200) { "$path: ${response.statusCode()} ${response.body()}" }
-        return JSON.readTree(response.body())
+        return Reply(response.statusCode(), response.body().takeIf { it.isNotBlank() }?.let { runCatching { JSON.readTree(it) }.getOrNull() })
     }
 
     fun post(path: String, body: String, contentType: String = "application/json"): Reply {
@@ -135,13 +189,20 @@ class HostBench : AutoCloseable {
         val HTTP: HttpClient = HttpClient.newHttpClient()
         val JSON = ObjectMapper()
 
-        /** 현장 셀 대역 `CellFixture.STANDARD` 의 처음 모양. */
+        /** 모의 실행용 프로파일과 프로파일 스키마. 호스트 시험은 절대 경로를 실행 인자로 넘긴다(S3b 스펙 §6.4). */
+        val MOCK_PROFILE: Path = ROOT.resolve("mission-host/mock-run/humanoid-a.json")
+        val SCHEMA: Path = ROOT.resolve("picasso/profile/schema/capability-profile.schema.json")
+
+        /** 현장 셀 대역 `CellFixture.STANDARD` 의 처음 모양. 신호 셋은 현장 표준 픽스처의 사양과 처음 값이다. */
         val STANDARD_CELL = """
             {"presentations":[{"id":"$SOURCE","occupied":true,"material":"$MATERIAL","observedAt":null}],
              "slots":[{"id":"RACK-204.S01","occupied":false,"material":null,"observedAt":null},
                       {"id":"RACK-204.S02","occupied":false,"material":null,"observedAt":null},
                       {"id":"RACK-204.S03","occupied":false,"material":null,"observedAt":null},
-                      {"id":"RACK-204.S04","occupied":false,"material":null,"observedAt":null}]}
+                      {"id":"RACK-204.S04","occupied":false,"material":null,"observedAt":null}],
+             "signals":[{"name":"rack_present","location":"RACK-204","kind":"BOOLEAN","safety":false,"value":"false","observedAt":null},
+                        {"name":"guard_closed","location":null,"kind":"BOOLEAN","safety":true,"value":"true","observedAt":null},
+                        {"name":"lot_code","location":null,"kind":"TEXT","safety":false,"value":"LOT-0001","observedAt":null}]}
         """.trimIndent()
 
         fun inspect(jobOrderId: String, vararg targets: Pair<String, String>, evidence: String = "E0"): String =
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && ./gradlew :mission-host:test :site:test -q
```
Expected: mission-host 49, site 32, 실패 0. 백그라운드로 돌린다(Docker, Testcontainers). e2e 는 호스트가 이제 데이터 소스 없이 뜨지 않아 Task 5 전까지 깨진 상태이며 돌리지 않는다.

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && git add mission-host/mock-run/humanoid-a.json mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionJudgment.kt mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionTemplates.kt mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionVersions.kt mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionViews.kt mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MockRunner.kt mission-host/src/main/kotlin/dev/picasso/ops/host/mission/StoredMissionCatalog.kt mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostSchema.kt mission-host/src/main/kotlin/dev/picasso/ops/host/store/MissionStore.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/MissionController.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/MissionRequests.kt mission-host/src/main/resources/db/mission/V1__mission_versions.sql mission-host/src/main/resources/mission-templates/PrepareSequencedRack.arrival-wait.json mission-host/src/main/resources/mission-templates/PrepareSequencedRack.data-v1.json mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/MockRunnerTest.kt mission-host/build.gradle.kts mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt mission-host/src/main/resources/mission-host.properties mission-host/src/test/kotlin/dev/picasso/ops/host/CellBandTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt && git commit -F - <<'EOF'
feat(mission-host): 임무 버전 저장·카탈로그·검증·모의 실행·활성화 추가

- `mission` 스키마의 추가 전용 표 셋, 호스트가 구현한 카탈로그와 재기동 복원
- 검증·모의 실행(별도 mimic, 이상적 현장)·활성화(호스트 잠금 아래)와 REST, 셀 대역 신호 읽기와 전달

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s3b-cmp.sh" site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt site/src/test/kotlin/dev/picasso/ops/site/SiteCellTest.kt mission-host/mock-run/humanoid-a.json mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionJudgment.kt mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionTemplates.kt mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionVersions.kt mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionViews.kt mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MockRunner.kt mission-host/src/main/kotlin/dev/picasso/ops/host/mission/StoredMissionCatalog.kt mission-host/src/main/kotlin/dev/picasso/ops/host/store/HostSchema.kt mission-host/src/main/kotlin/dev/picasso/ops/host/store/MissionStore.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/MissionController.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/MissionRequests.kt mission-host/src/main/resources/db/mission/V1__mission_versions.sql mission-host/src/main/resources/mission-templates/PrepareSequencedRack.arrival-wait.json mission-host/src/main/resources/mission-templates/PrepareSequencedRack.data-v1.json mission-host/src/test/kotlin/dev/picasso/ops/host/MissionStoreTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/MockRunnerTest.kt mission-host/build.gradle.kts mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt mission-host/src/main/resources/mission-host.properties mission-host/src/test/kotlin/dev/picasso/ops/host/CellBandTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
```
Expected: 26개 모두 `같음`.

### Task 3: 운영 서비스: 임무 버전 조작과 신호 조작

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/cell/CellSignalOperations.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/missions/MissionFindings.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/missions/MissionOperations.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/operations/HostOperationRunner.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/web/CellSignalController.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/web/MissionVersionController.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/CellSignalOperationsTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/HostMissionsClientTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/MissionBench.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/MissionFindingsTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/MissionOperationsTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/MissionVersionControllerTest.kt`
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt`

- [ ] **Step 1: 새 파일 12개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/cell && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/cell/CellSignalOperations.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/cell/CellSignalOperations.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/missions && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/missions/MissionFindings.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/missions/MissionFindings.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/missions && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/missions/MissionOperations.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/missions/MissionOperations.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/operations && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/operations/HostOperationRunner.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/operations/HostOperationRunner.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/web && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/web/CellSignalController.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/web/CellSignalController.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/web && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/web/MissionVersionController.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/web/MissionVersionController.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/CellSignalOperationsTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/CellSignalOperationsTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/HostMissionsClientTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/HostMissionsClientTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/MissionBench.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/MissionBench.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/MissionFindingsTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/MissionFindingsTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/MissionOperationsTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/MissionOperationsTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/MissionVersionControllerTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/MissionVersionControllerTest.kt
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/cell/CellSignalOperations.kt`:

```kotlin
package dev.picasso.ops.service.cell

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.host.HostSignals
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostOperationRunner
import dev.picasso.ops.service.operations.HostRecheck
import dev.picasso.ops.service.operations.HostRejection
import java.time.Duration
import java.util.UUID

/**
 * 신호 조작의 200 응답(S3b 스펙 §7). 작업 지시 제출·임무 조작의 응답과 같은 자리에 같은 칸을 둔다.
 *
 * @param name 조작 기록의 대상(신호 이름)
 * @param result 처음 남긴 행의 결과. 성공, 거부, 응답 없음 중 하나다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했으면 널이다
 * @param signal 바뀐 신호(현장 셀 대역의 200 본문, S3b JSON 계약 §2). 성공일 때만 있다
 * @param rejection 현장 셀 대역이나 호스트가 4xx 로 막았을 때만 있다(안전 신호 쓰기 403 `SAFETY_SIGNAL_READ_ONLY`,
 *   모르는 신호 404 `UNKNOWN_SIGNAL`, 틀린 값 400 `SIGNAL_VALUE_INVALID`)
 */
data class SignalWriteOutcome(
    val requestId: UUID,
    val name: String,
    val value: String,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val signal: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 셀 대역 신호 조작(S3b 스펙 §7, 결정 3). 사람이 PLC 역할을 하는 정상 조작이며 운영자·엔지니어 두 모드가 한다(모드 검사는
 * 컨트롤러의 몫). 호스트를 거쳐 현장 셀 대역에 쓰고 조작 기록을 직접 쓴다.
 *
 * 값 검사(BOOLEAN 은 `"true"`·`"false"`)와 안전 신호 쓰기 거부는 현장 셀 대역이 하고 운영 서비스는 그 거부를 그대로
 * 넘긴다(ADR 32: 안전 계통은 소프트웨어 계층에 통합하지 않는다). 거부는 조작 기록에 거부로 남는다.
 *
 * 호스트가 안 닿거나 5xx(현장이 안 닿은 503 `CELL_SILENT` 포함)면 «응답 없음» 을 남기고 `GET /host/cell` 을 다시 읽어
 * 그 신호의 값이 보낸 값과 같으면 반영됨, 다르면 반영 안 됨을 붙인다(T9). 셀 대역 스냅숏이나 그 신호 목록을 못 읽으면 확인
 * 행을 붙이지 않는다. 스냅숏의 신호 목록에 그 이름이 없으면 현장이 모르는 신호라 반영 안 됨이다.
 *
 * 대조는 값만 본다. 같은 값을 다시 쓴 조작이 현장에 닿지 않았어도 반영됨으로 보인다. 신호 조작은 값을 그 값으로 두는
 * 조작이라, 재조회 시점에 값이 그 값이면 조작의 뜻은 이루어진 것이다. 바뀐 값은 호스트의 다음 pump(250ms 주기)부터
 * 보이므로 [requeryDelay] 는 그보다 길어야 한다.
 */
class CellSignalOperations(
    private val signals: HostSignals,
    private val reads: HostReads,
    log: OperationLog,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = jacksonObjectMapper(),
) {
    private val runner = HostOperationRunner(log, requeryDelay, json)

    fun write(actor: Actor, name: String, value: String): SignalWriteOutcome {
        val request = json.createObjectNode().put("op", OP).put("name", name).put("value", value)
        val ran = runner.run(
            actor, name, request, null,
            accepted = { body -> if (body.path("name").isTextual) OperationResult.SUCCEEDED else null },
            recheck = { recheck(name, value) },
        ) { signals.writeSignal(name, value) }
        return SignalWriteOutcome(ran.requestId, name, value, ran.result, ran.confirmation, ran.body, ran.rejection)
    }

    private fun recheck(name: String, value: String): HostRecheck? {
        val body = (reads.cell() as? HostCall.Ok)?.value ?: return null
        val listed = body.path("cell").path("signals").takeIf { it.isArray } ?: return null
        val signal = listed.firstOrNull { it.path("name").asText() == name } ?: return HostRecheck(false, null)
        return HostRecheck(signal.path("value").takeIf { it.isTextual }?.asText() == value, signal)
    }

    companion object {
        const val OP = "WRITE_CELL_SIGNAL"
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/missions/MissionFindings.kt`:

```kotlin
package dev.picasso.ops.service.missions

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import java.time.Instant

/** 호스트 거부의 해결 담당(S3b JSON 계약 §3.1). picasso `MissionRefusal.owner` 의 이름 그대로다. */
enum class HostRefusalOwner { ENGINEER, OUTSIDE_CONSOLE }

/**
 * 호스트 거부 하나(S3b JSON 계약 §3.1 `RefusalView`). 칸 이름은 호스트 본문 그대로다. 모르는 해결 담당이 오면 본문을 못 읽은
 * 것이다. 어느 쪽에 보일지 모르는 거부를 화면 안의 일로 접지 않는다.
 *
 * @param nodeId 막힌 노드 id. 문서 전체의 문제(`UNREADABLE`)는 널이다
 * @param checkedAt 호스트가 검증한 시각(호스트 시계)
 * @param basisVersion 근거 현장 설정 버전. S3b 에서는 늘 널이다(S3c)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostMissionRefusal(
    val kind: String,
    val nodeId: String?,
    val observed: String,
    val expected: String,
    val checkedAt: Instant,
    val basisVersion: Long?,
    val owner: HostRefusalOwner,
    val nextAction: String,
)

/**
 * 호스트 거부를 화면의 거부 카드([Finding]) 모양으로 옮긴다(S3b 스펙 §7). 두 모양은 칸이 거의 같지만 뜻이 다른 칸이 있다.
 *
 * - [Finding.target] 은 널이다. 화면은 이 칸을 기체 상세 링크로 그리는데, 임무 거부의 대상은 기체가 아니라 노드다.
 * - 노드 id 는 관측값 앞에 `노드 <id>: ` 로 싣는다. 노드 id 가 널이면(문서 전체의 문제) 붙이지 않는다.
 * - 해결 담당은 ENGINEER → ENGINEER(화면 안), OUTSIDE_CONSOLE → SITE(화면 밖)다. OUTSIDE_CONSOLE 는 바닥 소유 선언이라
 *   현장 일이고 엔지니어가 화면에서 풀 수 없다.
 * - [Finding.checkedAt] 은 호스트가 검증한 시각이고, [Finding.basisVersion] 은 호스트 값을 `Long` 그대로 옮긴다.
 *
 * 종류 이름은 picasso `MissionRefusalKind` 9종 그대로다. 화면이 종류 이름표로 한국어 이름을 붙인다.
 */
object MissionFindings {

    fun of(refusal: HostMissionRefusal): Finding = Finding(
        kind = refusal.kind,
        observed = refusal.nodeId?.let { "노드 $it: ${refusal.observed}" } ?: refusal.observed,
        expected = refusal.expected,
        checkedAt = refusal.checkedAt,
        owner = when (refusal.owner) {
            HostRefusalOwner.ENGINEER -> Owner.ENGINEER
            HostRefusalOwner.OUTSIDE_CONSOLE -> Owner.SITE
        },
        inScreen = refusal.owner == HostRefusalOwner.ENGINEER,
        action = refusal.nextAction,
        target = null,
        basisVersion = refusal.basisVersion,
    )
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/missions/MissionOperations.kt`:

```kotlin
package dev.picasso.ops.service.missions

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.host.HostMissions
import dev.picasso.ops.service.host.HostRequery
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostOperationResult
import dev.picasso.ops.service.operations.HostOperationRunner
import dev.picasso.ops.service.operations.HostRecheck
import dev.picasso.ops.service.operations.HostRejection
import dev.picasso.ops.service.robots.CommissioningState
import dev.picasso.ops.service.robots.RegistryState
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.robots.RobotListView
import java.time.Duration
import java.util.UUID

/** 호스트 검증 결과의 이름(S3b JSON 계약 §4.4). */
enum class HostValidationResult { PASSED, REFUSED, INPUT_UNKNOWN }

/** 호스트 모의 실행 결과의 이름(S3b JSON 계약 §4.5). */
enum class HostMockRunResult { PASSED, FAILED, REFUSED, INPUT_UNKNOWN }

/** 호스트 활성화 결과의 이름(S3b JSON 계약 §4.6). */
enum class HostActivationResult { ACTIVATED, REFUSED, MOCK_RUN_REQUIRED, INPUT_UNKNOWN }

/**
 * 임무 조작(초안 저장, 모의 실행, 활성화)의 200 응답(S3b 스펙 §7). 작업 지시 제출의 응답과 같은 자리에 같은 칸을 둔다.
 *
 * @param workMasterId 조작 기록의 대상
 * @param result 처음 남긴 행의 결과. 성공, 거부, 응답 없음 중 하나다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했으면 널이다
 * @param outcome 호스트의 200 본문 그대로(S3b JSON 계약 §4.3·§4.5·§4.6). 호스트가 안 닿았거나 4xx 거나 본문을 못 읽었으면
 *   널이다. 결과 이름(`INPUT_UNKNOWN` 포함)은 이 본문의 `result` 에 있다
 * @param findings 호스트가 거부(`REFUSED`)했을 때 그 거부 목록을 거부 카드 모양으로 옮긴 것. 그 밖에는 빈 목록이다.
 *   `INPUT_UNKNOWN` 은 거부가 아니라 «모름» 이라 여기에 오지 않는다
 * @param rejection 호스트가 4xx 로 막았을 때(예: 없는 초안 404 `DRAFT_NOT_FOUND`)만 있다
 */
data class MissionOperationOutcome(
    val requestId: UUID,
    val workMasterId: String,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val outcome: JsonNode?,
    val findings: List<Finding>,
    val rejection: HostRejection?,
)

/** 검증의 200 응답. 검증은 아무것도 남기지 않아 조작 기록과 요청 id 가 없다. [outcome] 은 호스트 본문 그대로다. */
data class MissionValidationReply(val workMasterId: String, val outcome: JsonNode, val findings: List<Finding>)

/** 시운전 완료 기체(T7). «없음»(빈 목록)과 «모름» 을 접지 않는다. */
sealed interface CommissionedRobots {
    data class Known(val robotIds: List<String>) : CommissionedRobots

    /** [detail] 은 사전 거부의 detail 이다. */
    data class Unknown(val detail: String) : CommissionedRobots
}

/** 모의 실행·활성화 한 번의 응답. 시운전 완료 기체를 모르면 호스트를 부르지 않는다. */
sealed interface MissionSubmission {
    data class Submitted(val outcome: MissionOperationOutcome) : MissionSubmission

    /** registry 에서 시운전 완료 기체를 지금 읽지 못했다. 조작 기록에 남지 않는다. */
    data class RobotsUnknown(val detail: String) : MissionSubmission
}

/** 검증 한 번의 응답. 검증은 조작이 아니라 응답 없음과 재조회가 없다. */
sealed interface MissionValidation {
    data class Answered(val reply: MissionValidationReply) : MissionValidation

    /** 호스트가 4xx 로 막았다. 운영 서비스가 같은 상태 코드로 넘긴다. */
    data class HostRejected(val rejection: HostRejection) : MissionValidation

    /** 호스트가 안 닿았거나 5xx 거나 200 본문을 못 읽었다. */
    data class HostSilent(val cause: String) : MissionValidation

    data class RobotsUnknown(val detail: String) : MissionValidation
}

/** 호스트 판정 본문(검증·모의 실행·활성화) 중 운영 서비스가 읽는 칸. 나머지는 [MissionOperationOutcome.outcome] 으로 그대로 넘긴다. */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class HostJudgment(val result: String, val refusals: List<HostMissionRefusal>)

/**
 * 임무 버전 조작(S3b 스펙 §7). 초안 저장, 모의 실행, 활성화는 조작 기록에 남기고, 검증은 남기지 않는다(호스트도 아무것도
 * 남기지 않는다). 모드 검사와 사유 검사는 컨트롤러의 몫이다.
 *
 * 검증·모의 실행·활성화에는 시운전 완료 기체 id 를 붙인다(현장 스킬 계산용, T7). registry 에서 지금 읽은 기체 목록만 쓴다.
 * registry 가 답하지 않거나 시운전 판정이 없는 기체가 있으면 «모름» 이며 호스트를 부르지 않는다. 빈 목록이나 직전 목록으로
 * 대신하지 않는다. 빈 목록을 보내면 호스트가 현장 스킬을 «없음» 으로 읽어 엉뚱한 `SKILL_NOT_ON_SITE` 를 낸다(3값 원칙).
 *
 * 응답 없음 뒤 재조회는 요청 id 로 한다(T9). 호스트가 그 요청 id 로 남긴 행이 있고 그 행이 이 조작의 것(초안 저장은
 * `draft`, 모의 실행은 `mockRun`, 활성화는 `version`)이면 반영됨, 호스트가 남은 행이 없다고 답하면 반영 안 됨이다.
 * 모의 실행의 거부·모름과 활성화의 거부·모의 실행 없음·모름은 행을 남기지 않아 재조회에서 반영 안 됨으로 보인다.
 *
 * 호스트 결과 → 조작 기록 결과(S3b JSON 계약 §9.6): 초안 저장 200 은 성공, 모의 실행 PASSED·FAILED 는 돌았으므로 성공,
 * REFUSED·INPUT_UNKNOWN 은 거부, 활성화 ACTIVATED 는 성공, 나머지 셋은 거부다.
 */
class MissionOperations(
    private val robots: RobotListService,
    private val host: HostMissions,
    log: OperationLog,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule()),
) {
    private val runner = HostOperationRunner(log, requeryDelay, json)

    fun saveDraft(actor: Actor, workMasterId: String, definition: String): MissionOperationOutcome {
        val request = json.createObjectNode().put("op", OP_SAVE_DRAFT).put("workMasterId", workMasterId).put("definition", definition)
        val ran = runner.run(
            actor, workMasterId, request, null,
            accepted = { body -> if (body.path("draft").path("draftId").isIntegralNumber) OperationResult.SUCCEEDED else null },
            recheck = { id -> recheck(id, "draft") },
        ) { id -> host.saveDraft(workMasterId, definition, actor.user, id) }
        return outcome(workMasterId, ran, emptyList())
    }

    fun validate(workMasterId: String, draftId: Long): MissionValidation {
        val robotIds = when (val known = commissioned(robots.read())) {
            is CommissionedRobots.Unknown -> return MissionValidation.RobotsUnknown(known.detail)
            is CommissionedRobots.Known -> known.robotIds
        }
        val answered = when (val write = host.validate(draftId, robotIds)) {
            is HostWrite.NoResponse -> return MissionValidation.HostSilent(write.cause)
            is HostWrite.Answered -> write
        }
        val body = objectOrNull(answered.body)
        return when (answered.status) {
            in 200..299 -> {
                val judgment = body?.let(::judgment)?.takeIf { enumOrNull<HostValidationResult>(it.result) != null }
                    ?: return MissionValidation.HostSilent("본문 모양이 다르다")
                MissionValidation.Answered(MissionValidationReply(workMasterId, body, findings(judgment)))
            }
            in 400..499 -> MissionValidation.HostRejected(
                HostRejection(answered.status, body?.get("error")?.asText(), body?.get("detail")?.asText()),
            )
            else -> MissionValidation.HostSilent("HTTP ${answered.status}")
        }
    }

    fun mockRun(actor: Actor, workMasterId: String, draftId: Long): MissionSubmission {
        val robotIds = when (val known = commissioned(robots.read())) {
            is CommissionedRobots.Unknown -> return MissionSubmission.RobotsUnknown(known.detail)
            is CommissionedRobots.Known -> known.robotIds
        }
        val request = json.createObjectNode().put("op", OP_MOCK_RUN).put("workMasterId", workMasterId).put("draftId", draftId)
        request.putArray("robotIds").apply { robotIds.forEach(::add) }
        val ran = runner.run(
            actor, workMasterId, request, null,
            accepted = { body -> judged<HostMockRunResult>(body)?.let(::mockRunResultOf) },
            recheck = { id -> recheck(id, "mockRun") },
        ) { id -> host.mockRun(draftId, robotIds, id) }
        return MissionSubmission.Submitted(outcome(workMasterId, ran, ran.body?.let(::judgment)?.let(::findings).orEmpty()))
    }

    /** [reason] 은 컨트롤러가 앞뒤 공백을 깎고 비어 있지 않음을 본 값이다. 조작 기록의 사유 칸에 남는다. */
    fun activate(actor: Actor, workMasterId: String, draftId: Long, reason: String): MissionSubmission {
        val robotIds = when (val known = commissioned(robots.read())) {
            is CommissionedRobots.Unknown -> return MissionSubmission.RobotsUnknown(known.detail)
            is CommissionedRobots.Known -> known.robotIds
        }
        val request = json.createObjectNode().put("op", OP_ACTIVATE).put("workMasterId", workMasterId).put("draftId", draftId)
        request.putArray("robotIds").apply { robotIds.forEach(::add) }
        val ran = runner.run(
            actor, workMasterId, request, reason,
            accepted = { body -> judged<HostActivationResult>(body)?.let(::activationResultOf) },
            recheck = { id -> recheck(id, "version") },
        ) { id -> host.activate(draftId, actor.user, reason, robotIds, id) }
        return MissionSubmission.Submitted(outcome(workMasterId, ran, ran.body?.let(::judgment)?.let(::findings).orEmpty()))
    }

    /** 재조회 본문에서 이 조작의 행 칸([field])이 객체면 반영됨이다. 호스트가 못 찾았다고 답하면 반영 안 됨, 못 읽으면 널이다. */
    private fun recheck(requestId: UUID, field: String): HostRecheck? = when (val found = host.missionRequest(requestId)) {
        is HostRequery.Found -> HostRecheck(found.body.get(field)?.isObject == true, found.body)
        HostRequery.NotFound -> HostRecheck(false, null)
        is HostRequery.Silent -> null
    }

    private fun outcome(workMasterId: String, ran: HostOperationResult, findings: List<Finding>) =
        MissionOperationOutcome(ran.requestId, workMasterId, ran.result, ran.confirmation, ran.body, findings, ran.rejection)

    /** 결과 이름이 [E] 의 것이고 거부 목록을 읽을 수 있을 때만 그 결과다. 하나라도 어긋나면 본문을 못 읽은 것이다. */
    private inline fun <reified E : Enum<E>> judged(body: JsonNode): E? = judgment(body)?.let { enumOrNull<E>(it.result) }

    private fun judgment(body: JsonNode): HostJudgment? = try {
        json.treeToValue(body, HostJudgment::class.java)
    } catch (e: JacksonException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 거부만 거부 카드다. 모름(`INPUT_UNKNOWN`)과 모의 실행 없음은 거부 목록이 비어 있지만, 비어 있지 않아도 옮기지 않는다. */
    private fun findings(judgment: HostJudgment): List<Finding> =
        if (judgment.result == REFUSED) judgment.refusals.map(MissionFindings::of) else emptyList()

    private fun objectOrNull(body: String): JsonNode? = try {
        json.readTree(body)?.takeIf { it.isObject }
    } catch (e: JacksonException) {
        null
    }

    companion object {
        const val OP_SAVE_DRAFT = "SAVE_MISSION_DRAFT"
        const val OP_MOCK_RUN = "MOCK_RUN_MISSION_DRAFT"
        const val OP_ACTIVATE = "ACTIVATE_MISSION_VERSION"
        private const val REFUSED = "REFUSED"

        /** 편집 대상 WorkMaster(T6). 호스트가 받는 것과 같다. 그 밖은 호스트를 부르기 전에 막는다. */
        val EDITABLE: Set<String> = setOf(JobOrderForm.PREPARE_SEQUENCED_RACK)

        /** 시운전 완료 기체를 모르는 사전 거부(503). */
        const val COMMISSIONED_ROBOTS_UNKNOWN = "COMMISSIONED_ROBOTS_UNKNOWN"

        fun mockRunResultOf(result: HostMockRunResult): OperationResult = when (result) {
            HostMockRunResult.PASSED, HostMockRunResult.FAILED -> OperationResult.SUCCEEDED
            HostMockRunResult.REFUSED, HostMockRunResult.INPUT_UNKNOWN -> OperationResult.REJECTED
        }

        fun activationResultOf(result: HostActivationResult): OperationResult = when (result) {
            HostActivationResult.ACTIVATED -> OperationResult.SUCCEEDED
            HostActivationResult.REFUSED, HostActivationResult.MOCK_RUN_REQUIRED, HostActivationResult.INPUT_UNKNOWN ->
                OperationResult.REJECTED
        }

        /**
         * 시운전 완료 기체 id(T7). 기체 목록을 지금 registry 에서 읽었고 모든 기체의 시운전 판정이 있을 때만 안다. 시운전
         * 완료 기체가 하나도 없으면 빈 목록이다(«없음»).
         */
        fun commissioned(list: RobotListView): CommissionedRobots {
            val unknown = when {
                list.registry == RegistryState.REGISTRY_UNAUTHORIZED -> "운영자 토큰이 registry 와 맞지 않아 시운전 완료 기체를 모른다"
                list.registry != RegistryState.OK || list.robots == null -> "registry 가 답하지 않아 시운전 완료 기체를 모른다"
                list.robots.any { it.commissioning == null } -> "시운전 판정이 없는 기체가 있어 시운전 완료 기체를 모른다"
                else -> null
            }
            if (unknown != null) return CommissionedRobots.Unknown(unknown)
            return CommissionedRobots.Known(
                list.robots!!.filter { it.commissioning?.state == CommissioningState.COMPLETE }.map { it.robot.robotId },
            )
        }

        private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/operations/HostOperationRunner.kt`:

```kotlin
package dev.picasso.ops.service.operations

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import java.time.Duration
import java.util.UUID

/**
 * 호스트가 4xx 로 막은 조작의 응답. 본문 `{error, detail}` 을 옮긴다. 본문을 못 읽으면 두 칸이 널이다.
 *
 * 신호 조작에서는 현장 셀 대역의 거부(안전 신호 쓰기 등)가 호스트를 거쳐 그대로 여기에 온다(ADR 32).
 */
data class HostRejection(val status: Int, val error: String?, val detail: String?)

/**
 * 응답 없음 뒤 재조회의 판정.
 *
 * @param applied 이 조작이 반영됐는가
 * @param observed 재조회에서 본 대상의 모습. 확인 행의 `target_response` 에 `observed` 로 남는다. 대상이 없으면 널이다
 */
data class HostRecheck(val applied: Boolean, val observed: JsonNode?)

/**
 * 호스트 조작 한 번의 결과. 화면에 내는 모양은 부르는 쪽이 이것으로 만든다.
 *
 * @param result 처음 남긴 행의 결과. 성공, 거부, 응답 없음 중 하나다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했으면 널이다
 * @param body 호스트가 2xx 로 답하고 그 본문을 읽었을 때의 본문. 그 밖에는 널이다
 * @param rejection 호스트가 4xx 로 답했을 때만 있다
 */
data class HostOperationResult(
    val requestId: UUID,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val body: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 실행 호스트 조작 한 번을 보내고 조작 기록을 직접 쓴다(S3b 스펙 §7, S3a 의 작업 지시 제출과 같은 규칙).
 *
 * registry 조작의 [OperationRunner] 와 달리 결과를 200 본문의 `result` 로 가른다. 요청 id 는 여기서 만들어 호스트에 넘긴다.
 * 호스트가 그 id 를 행에 남기므로(T9) 응답이 없을 때 그 id 로 다시 찾을 수 있다.
 *
 * - 2xx 이고 [accepted] 가 본문을 읽으면 그 결과를 남긴다.
 * - 4xx 는 거부로 남긴다. 호스트가 요청을 받고 아무것도 남기지 않았다는 응답이다.
 * - 응답이 오지 않거나 5xx 거나 2xx 인데 본문을 못 읽으면 «응답 없음» 을 남기고 [requeryDelay] 뒤 [recheck] 를 한 번 불러
 *   반영 여부를 같은 요청 id 의 새 행으로 붙인다. 재조회도 못 읽으면(널) 행을 붙이지 않는다.
 *
 * 같은 요청 id 로 다시 보내지 않는다. 호스트는 이미 쓰인 요청 id 를 409 로 막고(S3b JSON 계약 §3), 다시 보내기는 새 요청
 * id 를 받는 새 조작이며 사람이 정한다.
 */
class HostOperationRunner(
    private val log: OperationLog,
    private val requeryDelay: Duration,
    private val json: ObjectMapper,
) {

    /**
     * @param target 조작 기록의 대상 칸. WorkMaster id 또는 신호 이름이다
     * @param request 조작 기록의 요청 칸. `op` 칸으로 조작을 가른다
     * @param accepted 2xx 본문을 조작 결과로 옮긴다. 본문 모양이 다르면 널을 낸다
     * @param recheck 응답 없음 뒤 반영 여부를 가른다. 읽지 못하면 널을 낸다
     * @param call 요청 id 를 받아 호스트를 한 번 부른다
     */
    fun run(
        actor: Actor,
        target: String,
        request: ObjectNode,
        reason: String?,
        accepted: (JsonNode) -> OperationResult?,
        recheck: (UUID) -> HostRecheck?,
        call: (UUID) -> HostWrite,
    ): HostOperationResult {
        val requestId = UUID.randomUUID()
        val requestJson = json.writeValueAsString(request)
        fun record(result: OperationResult, response: String) =
            log.append(requestId, actor, target, requestJson, reason, result, response)

        fun unanswered(response: String): HostOperationResult {
            record(OperationResult.NO_RESPONSE, response)
            if (!requeryDelay.isZero) Thread.sleep(requeryDelay)
            val check = recheck(requestId)
                ?: return HostOperationResult(requestId, OperationResult.NO_RESPONSE, null, null, null)
            val confirmation = if (check.applied) OperationResult.CONFIRMED_APPLIED else OperationResult.CONFIRMED_NOT_APPLIED
            val observed = json.createObjectNode()
            observed.set<JsonNode>("observed", check.observed ?: json.nullNode())
            record(confirmation, json.writeValueAsString(observed))
            return HostOperationResult(requestId, OperationResult.NO_RESPONSE, confirmation, null, null)
        }

        val write = when (val sent = call(requestId)) {
            is HostWrite.NoResponse -> return unanswered(json.writeValueAsString(json.createObjectNode().put("cause", sent.cause)))
            is HostWrite.Answered -> sent
        }
        val body = objectOrNull(write.body)
        val response = json.createObjectNode().put("status", write.status)
        if (body != null) response.set<JsonNode>("body", body) else response.put("body", write.body)
        val responseJson = json.writeValueAsString(response)
        return when (write.status) {
            in 200..299 -> {
                val result = body?.let(accepted) ?: return unanswered(responseJson)
                record(result, responseJson)
                HostOperationResult(requestId, result, null, body, null)
            }
            in 400..499 -> {
                record(OperationResult.REJECTED, responseJson)
                val rejection = HostRejection(
                    write.status,
                    body?.get("error")?.takeIf { it.isTextual }?.asText(),
                    body?.get("detail")?.takeIf { it.isTextual }?.asText(),
                )
                HostOperationResult(requestId, OperationResult.REJECTED, null, null, rejection)
            }
            else -> unanswered(responseJson)
        }
    }

    private fun objectOrNull(body: String): JsonNode? = try {
        json.readTree(body)?.takeIf { it.isObject }
    } catch (e: JacksonException) {
        null
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/web/CellSignalController.kt`:

```kotlin
package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.cell.CellSignalOperations
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 셀 대역 신호 조작 API(S3b 스펙 §7, 결정 3). 운영 영역의 조작이며 운영자·엔지니어 두 모드가 한다. 행위자 헤더가 없으면
 * 400 `ACTOR_REQUIRED` 다.
 *
 * 본문은 `{"value": "<문자열>"}` 이다. 신호 값은 종류와 상관없이 늘 문자열이다(S3b JSON 계약 공통 규칙). 본문이 객체가
 * 아니거나 `value` 가 문자열이 아니면 400 `SIGNAL_BAD_REQUEST` 로 호스트를 부르기 전에 막는다. 재조회가 대조할 값이 있어야
 * 하기 때문이다. 값이 그 신호의 종류에 맞는지와 안전 신호인지는 보지 않는다. 현장 셀 대역이 보고, 그 거부는 200 본문의
 * `rejection` 으로 그대로 넘어온다(ADR 32). 화면은 안전 신호에 버튼을 두지 않는다.
 */
@RestController
class CellSignalController(private val operations: CellSignalOperations) {
    private val json = ObjectMapper()

    @PostMapping("/api/cell/signals/{name}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun write(
        @PathVariable name: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, BOTH_MODES) { actor ->
        val value = body?.let { runCatching { json.readTree(it) }.getOrNull() }
            ?.takeIf { it.isObject }?.get("value")?.takeIf { it.isTextual }?.asText()
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, BAD_REQUEST, "value 가 문자열이 아니다(신호 값은 늘 문자열이다)")
        ResponseEntity.ok(operations.write(actor, name, value))
    }

    companion object {
        const val BAD_REQUEST = "SIGNAL_BAD_REQUEST"

        /** 사람이 PLC 역할을 하는 정상 조작이라 두 모드가 다 한다(결정 3). */
        val BOTH_MODES: Set<Mode> = setOf(Mode.OPERATOR, Mode.ENGINEER)
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/web/MissionVersionController.kt`:

```kotlin
package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostMissions
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.missions.MissionOperations
import dev.picasso.ops.service.missions.MissionSubmission
import dev.picasso.ops.service.missions.MissionValidation
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 임무 버전 API(S3b 스펙 §7). 두 읽기는 모드와 관계없고, 초안 저장·검증·모의 실행·활성화는 엔지니어 모드만 한다(T5).
 * 활성화는 사유가 있어야 한다.
 *
 * 판정 순서는 관문(헤더 400, 모드 403) → WorkMaster(400 `UNKNOWN_WORK_MASTER`) → 초안 id(400 `MISSION_BAD_REQUEST`) →
 * 본문(400 `MISSION_BAD_REQUEST`, 활성화의 사유는 400 `REASON_REQUIRED`) → 시운전 완료 기체(503
 * `COMMISSIONED_ROBOTS_UNKNOWN`)다. 모두 호스트에 닿지 않은 사전 거부라 조작 기록에 남지 않는다. 본문은 바이트로 받아 관문을
 * 지난 뒤 직접 읽는다([SiteSettingsController] 와 같은 이유: 운영자 모드의 깨진 본문은 400 이 아니라 403 이다). 쓰기 본문은
 * `application/json` 만 받는다(다른 출처 방어는 [RobotOperationsController]). 검증·모의 실행은 본문을 읽지 않지만 같은
 * 까닭으로 `application/json` 을 요구한다.
 *
 * 초안 저장·모의 실행·활성화는 호스트가 무엇을 답했든 200 과 [dev.picasso.ops.service.missions.MissionOperationOutcome]
 * 이다. 호스트의 판단(결과 이름, 거부 목록)은 본문에 있다. 검증은 조작이 아니라 호스트 본문과 거부 카드를 200 으로 내고,
 * 호스트의 4xx(없는 초안 404 `DRAFT_NOT_FOUND` 등)는 같은 상태 코드로, 호스트 불통은 503 `HOST_SILENT` 로 낸다.
 */
@RestController
class MissionVersionController(
    private val operations: MissionOperations,
    private val host: HostMissions,
) {
    private val json = ObjectMapper()

    @GetMapping("/api/missions/{workMasterId}")
    fun overview(@PathVariable workMasterId: String): ResponseEntity<Any> =
        editable(workMasterId) { forwarded(host.overview(it)) }

    @GetMapping("/api/missions/templates/{workMasterId}")
    fun templates(@PathVariable workMasterId: String): ResponseEntity<Any> =
        editable(workMasterId) { forwarded(host.templates(it)) }

    @PostMapping("/api/missions/{workMasterId}/drafts", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun saveDraft(
        @PathVariable workMasterId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = engineer(mode, user) { actor ->
        editable(workMasterId) { wm ->
            val definition = read(body)?.get("definition")?.takeIf { it.isTextual }?.asText()
                ?: return@editable reject(HttpStatus.BAD_REQUEST, BAD_REQUEST, "definition 이 문자열이 아니다(정의 JSON 은 글자로 싣는다)")
            ResponseEntity.ok(operations.saveDraft(actor, wm, definition))
        }
    }

    @PostMapping("/api/missions/{workMasterId}/drafts/{draftId}/validate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun validate(
        @PathVariable workMasterId: String,
        @PathVariable draftId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
    ): ResponseEntity<Any> = engineer(mode, user) {
        drafted(workMasterId, draftId) { wm, id ->
            when (val validation = operations.validate(wm, id)) {
                is MissionValidation.Answered -> ResponseEntity.ok(validation.reply)
                is MissionValidation.HostRejected -> ResponseEntity.status(validation.rejection.status).body(
                    PreRejection(validation.rejection.error ?: HOST_REJECTED, validation.rejection.detail ?: ""),
                )
                is MissionValidation.HostSilent -> hostSilent(validation.cause)
                is MissionValidation.RobotsUnknown -> robotsUnknown(validation.detail)
            }
        }
    }

    @PostMapping("/api/missions/{workMasterId}/drafts/{draftId}/mock-run", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun mockRun(
        @PathVariable workMasterId: String,
        @PathVariable draftId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
    ): ResponseEntity<Any> = engineer(mode, user) { actor ->
        drafted(workMasterId, draftId) { wm, id -> submitted(operations.mockRun(actor, wm, id)) }
    }

    @PostMapping("/api/missions/{workMasterId}/drafts/{draftId}/activate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun activate(
        @PathVariable workMasterId: String,
        @PathVariable draftId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = engineer(mode, user) { actor ->
        drafted(workMasterId, draftId) { wm, id ->
            val reason = read(body)?.get("reason")?.takeIf { it.isTextual }?.asText()?.trim()
            if (reason.isNullOrEmpty()) {
                return@drafted reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "활성화 사유가 없다")
            }
            submitted(operations.activate(actor, wm, id, reason))
        }
    }

    /** 임무 편집 관문. 엔지니어 모드만 받는다(T5). 네 쓰기가 모두 이것을 지난다. */
    private inline fun engineer(mode: String?, user: String?, action: (Actor) -> ResponseEntity<Any>): ResponseEntity<Any> =
        guarded(mode, user, Mode.ENGINEER, action)

    private inline fun editable(workMasterId: String, action: (String) -> ResponseEntity<Any>): ResponseEntity<Any> {
        if (workMasterId !in MissionOperations.EDITABLE) {
            return reject(
                HttpStatus.BAD_REQUEST, JobOrderForm.UNKNOWN_WORK_MASTER,
                "편집하지 않는 임무다: $workMasterId (편집하는 것: ${MissionOperations.EDITABLE.sorted()})",
            )
        }
        return action(workMasterId)
    }

    private inline fun drafted(
        workMasterId: String,
        draftId: String,
        action: (String, Long) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> = editable(workMasterId) { wm ->
        val id = draftId.toLongOrNull()?.takeIf { it > 0 }
            ?: return@editable reject(HttpStatus.BAD_REQUEST, BAD_REQUEST, "초안 id 가 양의 정수가 아니다: $draftId")
        action(wm, id)
    }

    private fun submitted(submission: MissionSubmission): ResponseEntity<Any> = when (submission) {
        is MissionSubmission.Submitted -> ResponseEntity.ok(submission.outcome)
        is MissionSubmission.RobotsUnknown -> robotsUnknown(submission.detail)
    }

    private fun read(body: ByteArray?): JsonNode? = body?.let { runCatching { json.readTree(it) }.getOrNull() }?.takeIf { it.isObject }

    private fun forwarded(call: HostCall<JsonNode>): ResponseEntity<Any> = when (call) {
        is HostCall.Ok -> ResponseEntity.ok(call.value)
        is HostCall.Silent -> hostSilent(call.cause)
    }

    private fun hostSilent(cause: String): ResponseEntity<Any> =
        reject(HttpStatus.SERVICE_UNAVAILABLE, JobOrderController.HOST_SILENT, "실행 호스트가 답하지 않는다: $cause")

    private fun robotsUnknown(detail: String): ResponseEntity<Any> =
        reject(HttpStatus.SERVICE_UNAVAILABLE, MissionOperations.COMMISSIONED_ROBOTS_UNKNOWN, detail)

    companion object {
        const val BAD_REQUEST = "MISSION_BAD_REQUEST"

        /** 현장 설정 변경과 같은 이름이다([SiteSettingsController]). */
        const val REASON_REQUIRED = "REASON_REQUIRED"

        /** 호스트 4xx 본문에 `error` 가 없을 때의 이름. 정상 흐름에서는 나오지 않는다. */
        const val HOST_REJECTED = "HOST_REJECTED"
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/CellSignalOperationsTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.cell.CellSignalOperations
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostRejection
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 셀 대역 신호 조작과 조작 기록(S3b 스펙 §7, 결정 3). 현장의 거부를 그대로 넘기고, 응답이 없으면 셀 대역 값을 다시 읽어
 * 대조한다. 호스트는 대역이다.
 */
class CellSignalOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = MissionBench()
    private val operations = CellSignalOperations(bench.host, bench.base.host, log, requeryDelay = Duration.ZERO)
    private val kim = Actor(Mode.OPERATOR, "kim")
    private val json = ObjectMapper()

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `바뀐 신호는 성공이고 신호 이름을 대상으로 이름과 값을 기록한다`() {
        val outcome = operations.write(kim, "rack_present", "true")
        assertEquals(MissionBench.Call("writeSignal", name = "rack_present", value = "true"), bench.writes().single())
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertNull(outcome.confirmation)
        assertEquals("true", outcome.signal!!["value"].asText())
        assertNull(outcome.rejection)
        val row = log.list().single()
        assertEquals(outcome.requestId, row.requestId)
        assertEquals("rack_present", row.target)
        assertEquals(Mode.OPERATOR, row.mode)
        assertEquals(
            json.readTree("""{"op":"${CellSignalOperations.OP}","name":"rack_present","value":"true"}"""),
            json.readTree(row.request),
        )
        assertEquals(OperationResult.SUCCEEDED, row.result)
    }

    @Test
    fun `현장의 거부는 거부로 남기고 오류 이름과 함께 그대로 넘긴다`() {
        val refusals = listOf(
            HostRejection(403, "SAFETY_SIGNAL_READ_ONLY", "guard_closed 는 안전 신호라 쓸 수 없다"),
            HostRejection(404, "UNKNOWN_SIGNAL", "셀 대역에 없는 신호다: rack_ready"),
            HostRejection(400, "SIGNAL_VALUE_INVALID", "BOOLEAN 신호는 true 나 false 다: TRUE"),
        )
        refusals.forEach { refusal ->
            bench.signalAnswer = HostWrite.Answered(refusal.status, """{"error":"${refusal.error}","detail":"${refusal.detail}"}""")
            val outcome = operations.write(kim, "guard_closed", "false")
            assertEquals(OperationResult.REJECTED, outcome.result)
            assertNull(outcome.confirmation)
            assertNull(outcome.signal)
            assertEquals(refusal, outcome.rejection)
        }
        assertEquals(List(3) { OperationResult.REJECTED }, log.list().map { it.result })
    }

    @Test
    fun `호스트가 안 닿으면 응답 없음 뒤 셀 대역 값을 다시 읽어 보낸 값과 같으면 반영됨이다`() {
        bench.signalAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        bench.base.cell = bench.cellWith("guard_closed" to "true", "rack_present" to "true")
        val outcome = operations.write(kim, "rack_present", "true")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        assertEquals(1, bench.writes().size)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertEquals(setOf(outcome.requestId), rows.map { it.requestId }.toSet())
        assertEquals("true", json.readTree(rows.first().targetResponse)["observed"]["value"].asText())
    }

    @Test
    fun `현장이 안 닿은 503 도 응답 없음이고 다시 읽은 값이 다르면 반영 안 됨이다`() {
        bench.signalAnswer = HostWrite.Answered(503, """{"error":"CELL_SILENT","detail":"현장 셀 대역이 답하지 않는다"}""")
        bench.base.cell = bench.cellWith("rack_present" to "false")
        val outcome = operations.write(kim, "rack_present", "true")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        assertNull(outcome.rejection)
        assertEquals(503, json.readTree(log.list().last().targetResponse)["status"].asInt())
    }

    @Test
    fun `다시 읽은 스냅숏이나 신호 목록이 없으면 확인 행이 없고 그 이름이 없으면 반영 안 됨이다`() {
        bench.signalAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
        val unknown = listOf(
            HostCall.Silent("응답 없음: ConnectException"),
            HostCall.Ok(json.readTree("""{"cell":null}""")),
            HostCall.Ok(json.readTree("""{"cell":{"presentations":[],"slots":[],"signals":null}}""")),
        )
        unknown.forEach { cell ->
            bench.base.cell = cell
            assertNull(operations.write(kim, "rack_present", "true").confirmation, "$cell")
        }
        assertEquals(List(3) { OperationResult.NO_RESPONSE }, log.list().map { it.result })

        bench.base.cell = bench.cellWith("lot_code" to "LOT-0001")
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.write(kim, "rack_present", "true").confirmation)
        assertTrue(json.readTree(log.list().first().targetResponse)["observed"].isNull)
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/HostMissionsClientTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostClient
import dev.picasso.ops.service.host.HostRequery
import dev.picasso.ops.service.host.HostWrite
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 실행 호스트 클라이언트의 임무 REST 와 신호 조작 전달(S3b 스펙 §7, T9·T10). 호스트는 JDK HttpServer 대역이다. */
class HostMissionsClientTest {

    private var server: HttpServer? = null

    /** 늦게 답하는 처리기가 서로를 막지 않게 요청마다 스레드를 쓴다. */
    private val pool = Executors.newCachedThreadPool()
    private val json = ObjectMapper()
    private val requestId = UUID.fromString("6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a")

    /** 받은 요청(방법, 인코딩된 경로, Content-Type, 본문). */
    private val seen = mutableListOf<Seen>()

    private data class Seen(val method: String, val rawPath: String, val contentType: String?, val body: String)

    /** 경로 앞부분마다 (상태 코드, 본문, 늦출 시간)으로 답한다. */
    private fun serve(vararg routes: Pair<String, Triple<Int, String, Duration>>, requestTimeout: Duration? = null, mockRunTimeout: Duration? = null): HostClient {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        routes.forEach { (path, answer) ->
            s.createContext(path) { exchange ->
                seen += Seen(
                    exchange.requestMethod,
                    exchange.requestURI.rawPath,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestBody.readAllBytes().decodeToString(),
                )
                Thread.sleep(answer.third)
                val bytes = answer.second.toByteArray()
                runCatching {
                    exchange.sendResponseHeaders(answer.first, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
        }
        s.executor = pool
        s.start()
        server = s
        val base = "http://127.0.0.1:${s.address.port}"
        return HostClient(
            base,
            requestTimeout = requestTimeout ?: HostClient.REQUEST_TIMEOUT,
            mockRunTimeout = mockRunTimeout ?: HostClient.MOCK_RUN_TIMEOUT,
        )
    }

    private fun ok(body: String, delay: Duration = Duration.ZERO) = Triple(200, body, delay)

    fun stop() {
        server?.stop(0)
    }

    @AfterTest
    fun close() {
        stop()
        pool.shutdownNow()
    }

    @Test
    fun `임무 쓰기 넷은 요청 id 와 사용자와 기체 목록을 실어 보내고 상태 코드와 본문을 그대로 돌려준다`() {
        val client = serve("/host/missions/" to ok("""{"result":"PASSED"}"""))
        val robots = listOf("humanoid-01", "quadruped-01")
        assertEquals(HostWrite.Answered(200, """{"result":"PASSED"}"""), client.saveDraft("PrepareSequencedRack", "{ 깨진", "lee", requestId))
        client.validate(3, robots)
        client.mockRun(3, robots, requestId)
        client.activate(3, "lee", "랙 도착 대기 도입", robots, requestId)
        assertEquals(
            listOf(
                "/host/missions/PrepareSequencedRack/drafts",
                "/host/missions/drafts/3/validate",
                "/host/missions/drafts/3/mock-run",
                "/host/missions/drafts/3/activate",
            ),
            seen.map { it.rawPath },
        )
        assertEquals(setOf("POST" to "application/json"), seen.map { it.method to it.contentType }.toSet())
        val id = requestId.toString()
        assertEquals(
            listOf(
                """{"definition":"{ 깨진","actor":"lee","requestId":"$id"}""",
                """{"robotIds":["humanoid-01","quadruped-01"]}""",
                """{"robotIds":["humanoid-01","quadruped-01"],"requestId":"$id"}""",
                """{"actor":"lee","reason":"랙 도착 대기 도입","robotIds":["humanoid-01","quadruped-01"],"requestId":"$id"}""",
            ).map(json::readTree),
            seen.map { json.readTree(it.body) },
        )
    }

    @Test
    fun `임무 읽기 둘은 객체 본문 그대로이고 200 아님은 모름이다`() {
        val client = serve(
            "/host/missions/templates/" to ok("""{"workMasterId":"PrepareSequencedRack","templates":[]}"""),
            "/host/missions/PrepareSequencedRack" to ok("""{"workMasterId":"PrepareSequencedRack","versions":[]}"""),
        )
        assertEquals(json.readTree("""{"workMasterId":"PrepareSequencedRack","versions":[]}"""), (client.overview("PrepareSequencedRack") as HostCall.Ok).value)
        assertEquals(json.readTree("""{"workMasterId":"PrepareSequencedRack","templates":[]}"""), (client.templates("PrepareSequencedRack") as HostCall.Ok).value)
        assertEquals(listOf("GET", "GET"), seen.map { it.method })
        stop()
        val refusing = serve("/host/missions/" to Triple(400, """{"error":"UNKNOWN_WORK_MASTER","detail":"x"}""", Duration.ZERO))
        assertEquals(HostCall.Silent("HTTP 400"), refusing.overview("InspectAsset"))
    }

    @Test
    fun `재조회는 남은 행이면 찾음, 404 REQUEST_NOT_FOUND 면 없음, 그 밖은 못 읽음이다`() {
        val found = serve("/host/missions/requests/" to ok("""{"requestId":"$requestId","draft":null,"mockRun":{"mockRunId":5},"version":null}"""))
        assertEquals(5, assertIs<HostRequery.Found>(found.missionRequest(requestId)).body["mockRun"]["mockRunId"].asInt())
        assertEquals("/host/missions/requests/$requestId", seen.single().rawPath)
        stop()
        val missing = serve("/host/missions/requests/" to Triple(404, """{"error":"REQUEST_NOT_FOUND","detail":"없다"}""", Duration.ZERO))
        assertEquals(HostRequery.NotFound, missing.missionRequest(requestId))
        stop()
        // 처리 중이라는 응답은 행이 없다는 응답이 아니다.
        val busy = serve("/host/missions/requests/" to Triple(409, """{"error":"REQUEST_IN_PROGRESS","detail":"처리 중"}""", Duration.ZERO))
        assertEquals(HostRequery.Silent("호스트가 그 요청을 아직 처리 중이다"), busy.missionRequest(requestId))
        stop()
        // 경로가 없는 서버의 404 는 호스트의 판단이 아니다.
        val elsewhere = serve("/other" to ok("{}"))
        assertEquals(HostRequery.Silent("HTTP 404"), elsewhere.missionRequest(requestId))
        stop()
        assertIs<HostRequery.Silent>(serve("/host/missions/requests/" to ok("[]")).missionRequest(requestId))
        stop()
        server = null
        assertIs<HostRequery.Silent>(elsewhere.missionRequest(requestId))
    }

    @Test
    fun `신호 조작은 이름을 경로 조각으로 인코딩해 값을 문자열로 보내고 현장의 응답을 그대로 돌려준다`() {
        val client = serve("/host/cell/signals/" to Triple(403, """{"error":"SAFETY_SIGNAL_READ_ONLY","detail":"x"}""", Duration.ZERO))
        assertEquals(
            HostWrite.Answered(403, """{"error":"SAFETY_SIGNAL_READ_ONLY","detail":"x"}"""),
            client.writeSignal("guard closed/x", "true"),
        )
        val request = seen.single()
        assertEquals("/host/cell/signals/guard%20closed%2Fx", request.rawPath)
        assertEquals(json.readTree("""{"value":"true"}"""), json.readTree(request.body))
    }

    @Test
    fun `모의 실행만 요청 제한이 길다`() {
        assertEquals(Duration.ofSeconds(60), HostClient.MOCK_RUN_TIMEOUT)
        assertEquals(Duration.ofSeconds(5), HostClient.REQUEST_TIMEOUT)
        // 제한을 줄여 같은 비율로 본다. 호스트가 1초 뒤에 답하면 모의 실행은 받고 활성화는 응답 없음이다.
        val client = serve(
            "/host/missions/" to ok("""{"result":"PASSED"}""", Duration.ofSeconds(1)),
            requestTimeout = Duration.ofMillis(300),
            mockRunTimeout = Duration.ofSeconds(5),
        )
        assertEquals(HostWrite.Answered(200, """{"result":"PASSED"}"""), client.mockRun(3, listOf("humanoid-01"), requestId))
        assertIs<HostWrite.NoResponse>(client.activate(3, "lee", "r", listOf("humanoid-01"), requestId))
        assertIs<HostWrite.NoResponse>(client.validate(3, listOf("humanoid-01")))
        assertIs<HostWrite.NoResponse>(client.saveDraft("PrepareSequencedRack", "{}", "lee", requestId))
    }

    @Test
    fun `닿지 않는 호스트는 임무 읽기가 모름이고 쓰기가 응답 없음이다`() {
        val client = serve()
        stop()
        server = null
        assertIs<HostCall.Silent>(client.overview("PrepareSequencedRack"))
        assertIs<HostWrite.NoResponse>(client.mockRun(3, emptyList(), requestId))
        assertIs<HostWrite.NoResponse>(client.writeSignal("rack_present", "true"))
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/MissionBench.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostMissions
import dev.picasso.ops.service.host.HostRequery
import dev.picasso.ops.service.host.HostSignals
import dev.picasso.ops.service.host.HostWrite
import java.util.UUID

/**
 * 임무 버전·신호 조작 시험의 대역 세트. 기체 목록(registry)은 [JobOrderBench] 의 것을 쓰고, 호스트의 임무 REST 와 신호
 * 조작을 인터페이스 대역으로 끼운다.
 *
 * 기본은 기체 둘(humanoid-01, quadruped-01)이 다 시운전 완료이고, 호스트가 쓰기마다 정상 결과로 답하는 것이다(초안 저장은
 * 초안 3, 검증은 PASSED, 모의 실행은 PASSED, 활성화는 ACTIVATED 버전 1, 신호는 바뀐 신호). 시험이 칸을 바꿔 한 칸씩
 * 어긋나게 한다.
 */
class MissionBench {

    val base = JobOrderBench()
    private val json = jacksonObjectMapper()

    var overview: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"workMasterId":"$WM","active":{"version":null,"source":"CODE","detail":null},"versions":[],"drafts":[]}"""))
    var templates: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"workMasterId":"$WM","templates":[]}"""))
    var saveAnswer: HostWrite = HostWrite.Answered(200, DRAFT_SAVED)
    var validateAnswer: HostWrite = HostWrite.Answered(200, judgment("PASSED"))
    var mockRunAnswer: HostWrite = HostWrite.Answered(200, judgment("PASSED", extra = ""","mockRun":{"mockRunId":5,"draftId":3,"passed":true}"""))
    var activateAnswer: HostWrite = HostWrite.Answered(200, judgment("ACTIVATED", extra = ""","version":1,"activated":{"version":1}"""))
    var signalAnswer: HostWrite = HostWrite.Answered(200, """{"name":"rack_present","location":"RACK-204","kind":"BOOLEAN","safety":false,"value":"true","observedAt":"2026-10-08T00:02:00Z"}""")

    /** 요청 id 를 받아 재조회 응답을 낸다. 기본은 못 읽음이다. */
    var requery: (UUID) -> HostRequery = { HostRequery.Silent("응답 없음: ConnectException") }

    /** 호스트가 받은 호출. 조작 이름과 인자. */
    val calls = mutableListOf<Call>()

    data class Call(
        val op: String,
        val workMasterId: String? = null,
        val draftId: Long? = null,
        val robotIds: List<String>? = null,
        val requestId: UUID? = null,
        val actor: String? = null,
        val reason: String? = null,
        val definition: String? = null,
        val name: String? = null,
        val value: String? = null,
    )

    /** 재조회한 요청 id. */
    val requeried = mutableListOf<UUID>()

    inner class FakeHost : HostMissions, HostSignals {
        override fun overview(workMasterId: String) = this@MissionBench.overview.also { calls += Call("overview", workMasterId) }

        override fun templates(workMasterId: String) = this@MissionBench.templates.also { calls += Call("templates", workMasterId) }

        override fun saveDraft(workMasterId: String, definition: String, actor: String, requestId: UUID): HostWrite {
            calls += Call("saveDraft", workMasterId = workMasterId, definition = definition, actor = actor, requestId = requestId)
            return saveAnswer
        }

        override fun validate(draftId: Long, robotIds: List<String>): HostWrite {
            calls += Call("validate", draftId = draftId, robotIds = robotIds)
            return validateAnswer
        }

        override fun mockRun(draftId: Long, robotIds: List<String>, requestId: UUID): HostWrite {
            calls += Call("mockRun", draftId = draftId, robotIds = robotIds, requestId = requestId)
            return mockRunAnswer
        }

        override fun activate(draftId: Long, actor: String, reason: String, robotIds: List<String>, requestId: UUID): HostWrite {
            calls += Call("activate", draftId = draftId, robotIds = robotIds, requestId = requestId, actor = actor, reason = reason)
            return activateAnswer
        }

        override fun missionRequest(requestId: UUID): HostRequery {
            requeried += requestId
            return requery(requestId)
        }

        override fun writeSignal(name: String, value: String): HostWrite {
            calls += Call("writeSignal", name = name, value = value)
            return signalAnswer
        }
    }

    val host = FakeHost()

    /** 호스트가 받은 쓰기 호출(읽기 둘을 뺀 것). */
    fun writes(): List<Call> = calls.filter { it.op !in setOf("overview", "templates") }

    /** 셀 스냅숏 본문. [signals] 는 이름과 값의 쌍이다. */
    fun cellWith(vararg signals: Pair<String, String>): HostCall<JsonNode> {
        val rows = signals.joinToString(",") { (name, value) ->
            """{"name":"$name","location":null,"kind":"BOOLEAN","safety":false,"value":"$value","observedAt":null}"""
        }
        return HostCall.Ok(json.readTree("""{"cell":{"presentations":[],"slots":[],"signals":[$rows]}}"""))
    }

    companion object {
        const val WM = "PrepareSequencedRack"

        const val DRAFT_SAVED =
            """{"draft":{"draftId":3,"workMasterId":"$WM","definition":"{}","savedBy":"lee","requestId":"x","savedAt":"t","lastMockRun":null}}"""

        /** 호스트 거부 하나(S3b JSON 계약 §3.1). */
        fun refusal(
            kind: String = "SIGNAL_NOT_IN_SPEC",
            nodeId: String? = "rack-arrival",
            observed: String = "rack_ready",
            owner: String = "ENGINEER",
            basisVersion: String = "null",
        ) = """{"kind":"$kind","nodeId":${nodeId?.let { "\"$it\"" } ?: "null"},"observed":"$observed",
            "expected":"신호 사양의 이름 중 하나(guard_closed, lot_code, rack_present)","checkedAt":"2026-10-08T00:00:01Z",
            "basisVersion":$basisVersion,"owner":"$owner","nextAction":"신호 이름을 고치거나 신호 사양에 더한다"}"""

        /** 판정 본문(검증·모의 실행·활성화 공통 칸). */
        fun judgment(result: String, refusals: List<String> = emptyList(), unknown: String = "null", extra: String = "") =
            """{"result":"$result","draftId":3,"workMasterId":"$WM","checkedAt":"2026-10-08T00:00:01Z",
               "refusals":[${refusals.joinToString(",")}],"unknown":$unknown,"inputs":{"signals":null,"siteSkills":null,
               "robotIds":[],"unknownRobots":[]}$extra}"""

        const val UNKNOWN_SKILLS =
            """{"inputs":["SITE_SKILLS"],"robots":["ghost-01"],"detail":"기체 케이퍼빌리티를 못 물어봐 현장 스킬을 못 읽었다(ghost-01)"}"""
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/MissionFindingsTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.picasso.ops.service.MissionBench.Companion.refusal
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.missions.HostMissionRefusal
import dev.picasso.ops.service.missions.MissionFindings
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 호스트 거부 → 거부 카드 변환(S3b 스펙 §7). 호스트 본문(S3b JSON 계약 §3.1)을 읽어 옮긴다. */
class MissionFindingsTest {

    private val json = jacksonObjectMapper().registerModule(JavaTimeModule())

    private fun finding(body: String): Finding = MissionFindings.of(json.readValue<HostMissionRefusal>(body))

    @Test
    fun `노드 거부는 관측값 앞에 노드 id 를 싣고 대상은 널이며 엔지니어가 화면 안에서 푼다`() {
        assertEquals(
            Finding(
                kind = "SIGNAL_NOT_IN_SPEC",
                observed = "노드 rack-arrival: rack_ready",
                expected = "신호 사양의 이름 중 하나(guard_closed, lot_code, rack_present)",
                checkedAt = Instant.parse("2026-10-08T00:00:01Z"),
                owner = Owner.ENGINEER,
                inScreen = true,
                action = "신호 이름을 고치거나 신호 사양에 더한다",
                target = null,
                basisVersion = null,
            ),
            finding(refusal()),
        )
    }

    @Test
    fun `OUTSIDE_CONSOLE 는 현장 담당이고 화면 밖이다`() {
        val outside = finding(refusal(kind = "FLOOR_UNOWNED", nodeId = "place", observed = "RACK-204", owner = "OUTSIDE_CONSOLE"))
        assertEquals(Owner.SITE, outside.owner)
        assertEquals(false, outside.inScreen)
        assertEquals("노드 place: RACK-204", outside.observed)
    }

    @Test
    fun `문서 전체의 거부는 노드 접두가 없고 근거 버전은 Long 으로 옮긴다`() {
        val whole = finding(refusal(kind = "UNREADABLE", nodeId = null, observed = "$.workMasterId: 초안의 WorkMaster 와 다르다(InspectAsset)", basisVersion = "7"))
        assertEquals("$.workMasterId: 초안의 WorkMaster 와 다르다(InspectAsset)", whole.observed)
        assertEquals(7L, whole.basisVersion)
        assertEquals(null, whole.target)
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/MissionOperationsTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.JobOrderBench.Companion.HUMANOID
import dev.picasso.ops.service.JobOrderBench.Companion.QUADRUPED
import dev.picasso.ops.service.MissionBench.Companion.UNKNOWN_SKILLS
import dev.picasso.ops.service.MissionBench.Companion.WM
import dev.picasso.ops.service.MissionBench.Companion.judgment
import dev.picasso.ops.service.MissionBench.Companion.refusal
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.host.HostClient
import dev.picasso.ops.service.host.HostMissions
import dev.picasso.ops.service.host.HostRequery
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.missions.HostActivationResult
import dev.picasso.ops.service.missions.HostMockRunResult
import dev.picasso.ops.service.missions.MissionOperationOutcome
import dev.picasso.ops.service.missions.MissionOperations
import dev.picasso.ops.service.missions.MissionSubmission
import dev.picasso.ops.service.missions.MissionValidation
import dev.picasso.ops.service.operations.HostRejection
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 임무 조작과 조작 기록(S3b 스펙 §7). 호스트 결과를 조작 기록 결과로 옮기고, 거부를 거부 카드로 옮기고, 응답이 없으면 요청
 * id 로 다시 찾는다. 호스트와 registry 는 대역이다.
 */
class MissionOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = MissionBench()
    private val operations = MissionOperations(bench.base.robotList, bench.host, log, requeryDelay = Duration.ZERO)
    private val lee = Actor(Mode.ENGINEER, "lee")
    private val json = ObjectMapper()

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    private fun mockRun(): MissionOperationOutcome = assertIs<MissionSubmission.Submitted>(operations.mockRun(lee, WM, 3)).outcome

    private fun activate(reason: String = "랙 도착 대기 도입"): MissionOperationOutcome =
        assertIs<MissionSubmission.Submitted>(operations.activate(lee, WM, 3, reason)).outcome

    @Test
    fun `모의 실행과 활성화의 호스트 결과를 조작 기록 결과로 옮기고 결과 이름은 본문에 그대로 남는다`() {
        val mockRuns = mapOf(
            HostMockRunResult.PASSED to OperationResult.SUCCEEDED,
            HostMockRunResult.FAILED to OperationResult.SUCCEEDED,
            HostMockRunResult.REFUSED to OperationResult.REJECTED,
            HostMockRunResult.INPUT_UNKNOWN to OperationResult.REJECTED,
        )
        assertEquals(HostMockRunResult.entries.toSet(), mockRuns.keys)
        mockRuns.forEach { (hostResult, logged) ->
            bench.mockRunAnswer = HostWrite.Answered(200, judgment(hostResult.name))
            val outcome = mockRun()
            assertEquals(logged, outcome.result, "$hostResult")
            assertEquals(MissionOperations.mockRunResultOf(hostResult), outcome.result)
            assertNull(outcome.confirmation)
            assertEquals(hostResult.name, outcome.outcome!!["result"].asText())
            assertEquals(logged, log.list().first().result, "$hostResult")
        }
        val activations = mapOf(
            HostActivationResult.ACTIVATED to OperationResult.SUCCEEDED,
            HostActivationResult.REFUSED to OperationResult.REJECTED,
            HostActivationResult.MOCK_RUN_REQUIRED to OperationResult.REJECTED,
            HostActivationResult.INPUT_UNKNOWN to OperationResult.REJECTED,
        )
        assertEquals(HostActivationResult.entries.toSet(), activations.keys)
        activations.forEach { (hostResult, logged) ->
            bench.activateAnswer = HostWrite.Answered(200, judgment(hostResult.name))
            val outcome = activate()
            assertEquals(logged, outcome.result, "$hostResult")
            assertEquals(MissionOperations.activationResultOf(hostResult), outcome.result)
            assertEquals(hostResult.name, outcome.outcome!!["result"].asText())
            assertEquals(logged, log.list().first().result, "$hostResult")
        }
        assertEquals(8, log.list().size)
    }

    @Test
    fun `INPUT_UNKNOWN 은 거부로 기록하지만 거부 카드가 아니라 모름이며 무엇을 몰랐는지 본문에 남는다`() {
        bench.activateAnswer = HostWrite.Answered(200, judgment("INPUT_UNKNOWN", unknown = UNKNOWN_SKILLS))
        val outcome = activate()
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals(emptyList(), outcome.findings)
        assertEquals("INPUT_UNKNOWN", outcome.outcome!!["result"].asText())
        assertEquals("SITE_SKILLS", outcome.outcome!!["unknown"]["inputs"][0].asText())
        assertEquals(OperationResult.REJECTED, log.list().single().result)
    }

    @Test
    fun `거부는 거부 카드로 옮기고 통과에는 거부 카드가 없다`() {
        bench.activateAnswer = HostWrite.Answered(
            200,
            judgment("REFUSED", listOf(refusal(), refusal(kind = "FLOOR_UNOWNED", nodeId = "place", observed = "RACK-204", owner = "OUTSIDE_CONSOLE"))),
        )
        val refused = activate()
        assertEquals(listOf("SIGNAL_NOT_IN_SPEC", "FLOOR_UNOWNED"), refused.findings.map { it.kind })
        assertEquals("노드 rack-arrival: rack_ready", refused.findings[0].observed)
        assertEquals(listOf(Owner.ENGINEER, Owner.SITE), refused.findings.map { it.owner })
        assertEquals(Instant.parse("2026-10-08T00:00:01Z"), refused.findings[0].checkedAt)

        bench.mockRunAnswer = HostWrite.Answered(200, judgment("REFUSED", listOf(refusal())))
        assertEquals(listOf("SIGNAL_NOT_IN_SPEC"), mockRun().findings.map { it.kind })
        bench.mockRunAnswer = HostWrite.Answered(200, judgment("PASSED"))
        assertEquals(emptyList(), mockRun().findings)
    }

    @Test
    fun `초안 저장은 정의와 사용자와 정규형 요청 id 를 호스트에 넘기고 WorkMaster 를 대상으로 기록한다`() {
        val outcome = operations.saveDraft(lee, WM, "{\"schemaVersion\": 1")
        val call = bench.writes().single()
        assertEquals("saveDraft", call.op)
        assertEquals("{\"schemaVersion\": 1", call.definition)
        assertEquals("lee", call.actor)
        assertEquals(outcome.requestId, call.requestId)
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(call.requestId.toString()))
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertEquals(WM, outcome.workMasterId)
        assertEquals(3, outcome.outcome!!["draft"]["draftId"].asInt())
        val row = log.list().single()
        assertEquals(outcome.requestId, row.requestId)
        assertEquals(WM, row.target)
        assertEquals(Mode.ENGINEER, row.mode)
        assertEquals("lee", row.user)
        assertNull(row.reason)
        val request = json.readTree(row.request)
        assertEquals(MissionOperations.OP_SAVE_DRAFT, request["op"].asText())
        assertEquals("{\"schemaVersion\": 1", request["definition"].asText())
        assertEquals(200, json.readTree(row.targetResponse)["status"].asInt())
    }

    @Test
    fun `활성화는 사유를 호스트와 조작 기록에 넘기고 요청 id 는 조작마다 새로 만든다`() {
        val first = activate("랙 도착 대기 도입")
        val second = activate("다시")
        val calls = bench.writes()
        assertEquals(listOf("랙 도착 대기 도입", "다시"), calls.map { it.reason })
        assertEquals(listOf(first.requestId, second.requestId), calls.map { it.requestId })
        assertTrue(first.requestId != second.requestId)
        val rows = log.list().sortedBy { it.recordedAt }
        assertEquals(listOf("랙 도착 대기 도입", "다시"), rows.map { it.reason })
        val request = json.readTree(rows.first().request)
        assertEquals(MissionOperations.OP_ACTIVATE, request["op"].asText())
        assertEquals(3, request["draftId"].asInt())
        assertEquals(json.readTree("""["$HUMANOID","$QUADRUPED"]"""), request["robotIds"])
    }

    @Test
    fun `검증과 모의 실행과 활성화는 시운전 완료 기체만 붙이고 하나도 없으면 빈 목록이다`() {
        bench.base.robots = RegistryCall.Ok(
            listOf(JobOrderBench.robot(HUMANOID), JobOrderBench.robot(QUADRUPED), JobOrderBench.robot("old-01", status = "RETIRED")),
        )
        bench.base.bindings = RegistryCall.Ok(
            listOf(JobOrderBench.binding(HUMANOID), JobOrderBench.binding(QUADRUPED, siteNames = "UNREGISTERED")),
        )
        operations.validate(WM, 3)
        mockRun()
        activate()
        assertEquals(listOf(listOf(HUMANOID), listOf(HUMANOID), listOf(HUMANOID)), bench.writes().map { it.robotIds })

        bench.calls.clear()
        bench.base.bindings = RegistryCall.Ok(emptyList())
        mockRun()
        assertEquals(listOf(emptyList<String>()), bench.writes().map { it.robotIds })
    }

    @Test
    fun `registry 가 답하지 않으면 시운전 완료 기체를 모르는 것이라 호스트를 부르지 않고 기록하지 않는다`() {
        // 한 번 읽어 직전 목록을 남긴 뒤 registry 를 끊는다. 직전 목록으로 대신하지 않아야 한다.
        bench.base.robotList.read()
        bench.base.robots = RegistryCall.Silent("응답 없음: ConnectException")
        val detail = "registry 가 답하지 않아 시운전 완료 기체를 모른다"
        assertEquals(MissionValidation.RobotsUnknown(detail), operations.validate(WM, 3))
        assertEquals(MissionSubmission.RobotsUnknown(detail), operations.mockRun(lee, WM, 3))
        assertEquals(MissionSubmission.RobotsUnknown(detail), operations.activate(lee, WM, 3, "사유"))

        bench.base.robots = RegistryCall.Unauthorized
        assertEquals(
            MissionSubmission.RobotsUnknown("운영자 토큰이 registry 와 맞지 않아 시운전 완료 기체를 모른다"),
            operations.mockRun(lee, WM, 3),
        )
        assertEquals(emptyList(), bench.writes())
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `호스트가 닿지 않으면 응답 없음을 남기고 다시 보내지 않고 같은 요청 id 로 찾아 그 조작의 행이 있으면 반영됨이다`() {
        bench.mockRunAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        bench.requery = { id ->
            HostRequery.Found(json.readTree("""{"requestId":"$id","draft":null,"mockRun":{"mockRunId":5,"passed":true},"version":null}"""))
        }
        val outcome = mockRun()
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        assertNull(outcome.outcome)
        assertEquals(1, bench.writes().size)
        assertEquals(listOf(outcome.requestId), bench.requeried)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertEquals(setOf(outcome.requestId), rows.map { it.requestId }.toSet())
        assertEquals(setOf(WM), rows.map { it.target }.toSet())
        assertEquals("응답 없음: HttpTimeoutException", json.readTree(rows.last().targetResponse)["cause"].asText())
        assertEquals(5, json.readTree(rows.first().targetResponse)["observed"]["mockRun"]["mockRunId"].asInt())
    }

    @Test
    fun `재조회에서 남은 행이 없거나 다른 조작의 행만 있으면 반영 안 됨이다`() {
        bench.activateAnswer = HostWrite.Answered(503, "")
        bench.requery = { HostRequery.NotFound }
        val missing = activate()
        assertEquals(OperationResult.NO_RESPONSE, missing.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, missing.confirmation)
        assertTrue(json.readTree(log.list().first().targetResponse)["observed"].isNull)

        bench.requery = { id ->
            HostRequery.Found(json.readTree("""{"requestId":"$id","draft":{"draftId":3},"mockRun":null,"version":null}"""))
        }
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, activate().confirmation)

        bench.saveAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.saveDraft(lee, WM, "{}").confirmation)
    }

    @Test
    fun `재조회도 못 읽으면 확인 행을 붙이지 않고 모름으로 둔다`() {
        bench.saveAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
        val outcome = operations.saveDraft(lee, WM, "{}")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertNull(outcome.confirmation)
        assertEquals(listOf(OperationResult.NO_RESPONSE), log.list().map { it.result })
    }

    /**
     * 활성화가 호스트 잠금을 기다리는 사이에 운영 서비스가 먼저 끊고 재조회하는 경합이다. 호스트는 실제 클라이언트로 부르고
     * JDK HttpServer 가 대신한다. 활성화는 요청 제한보다 늦게 응답하고, 재조회에는 처리 중(409)으로 응답한다.
     */
    @Test
    fun `재조회에서 호스트가 그 요청을 아직 처리 중이면 확인하지 못한 것이라 확인 행을 붙이지 않는다`() {
        val pool = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requeried = CopyOnWriteArrayList<String>()
        server.createContext("/host/missions/drafts/") { exchange ->
            exchange.requestBody.readAllBytes()
            Thread.sleep(1000)
            runCatching { exchange.sendResponseHeaders(503, -1) }
            exchange.close()
        }
        server.createContext("/host/missions/requests/") { exchange ->
            requeried += exchange.requestURI.rawPath
            val body = """{"error":"REQUEST_IN_PROGRESS","detail":"그 요청 id 의 조작을 아직 처리 중이다"}""".toByteArray()
            exchange.sendResponseHeaders(409, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.executor = pool
        server.start()
        try {
            HostClient("http://127.0.0.1:${server.address.port}", requestTimeout = Duration.ofMillis(300)).use { client ->
                val raced = MissionOperations(bench.base.robotList, client, log, requeryDelay = Duration.ZERO)
                val outcome = assertIs<MissionSubmission.Submitted>(raced.activate(lee, WM, 3, "랙 도착 대기 도입")).outcome
                assertEquals(OperationResult.NO_RESPONSE, outcome.result)
                assertNull(outcome.confirmation)
                assertEquals(listOf("/host/missions/requests/${outcome.requestId}"), requeried.toList())
                assertEquals(listOf(OperationResult.NO_RESPONSE), log.list().map { it.result })
            }
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    @Test
    fun `200 인데 결과 이름을 모르거나 거부를 읽지 못하면 응답 없음으로 남기고 다시 찾는다`() {
        bench.requery = { HostRequery.NotFound }
        bench.activateAnswer = HostWrite.Answered(200, judgment("PASSED"))
        assertEquals(OperationResult.NO_RESPONSE, activate().result)
        bench.mockRunAnswer = HostWrite.Answered(200, judgment("REFUSED", listOf(refusal(owner = "OPERATOR"))))
        val unreadable = mockRun()
        assertEquals(OperationResult.NO_RESPONSE, unreadable.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, unreadable.confirmation)
        assertEquals(emptyList(), unreadable.findings)
        bench.saveAnswer = HostWrite.Answered(200, """{"draft":null}""")
        assertEquals(OperationResult.NO_RESPONSE, operations.saveDraft(lee, WM, "{}").result)
    }

    @Test
    fun `호스트의 4xx 는 거부로 남기고 오류 이름을 넘긴다`() {
        bench.activateAnswer = HostWrite.Answered(404, """{"error":"DRAFT_NOT_FOUND","detail":"초안 9 가 없다"}""")
        val outcome = activate()
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertNull(outcome.confirmation)
        assertNull(outcome.outcome)
        assertEquals(HostRejection(404, "DRAFT_NOT_FOUND", "초안 9 가 없다"), outcome.rejection)
        assertEquals(emptyList(), bench.requeried)
        assertEquals(listOf(OperationResult.REJECTED), log.list().map { it.result })
    }

    @Test
    fun `검증은 기록하지 않고 호스트 본문과 거부 카드를 내며 4xx 와 불통을 가른다`() {
        val passed = assertIs<MissionValidation.Answered>(operations.validate(WM, 3)).reply
        assertEquals("PASSED", passed.outcome["result"].asText())
        assertEquals(emptyList(), passed.findings)
        assertEquals(3L, bench.writes().single().draftId)

        bench.validateAnswer = HostWrite.Answered(200, judgment("REFUSED", listOf(refusal(kind = "UNREADABLE", nodeId = null, observed = "$.steps[0].skill: 빠졌다"))))
        val refused = assertIs<MissionValidation.Answered>(operations.validate(WM, 3)).reply
        assertEquals("$.steps[0].skill: 빠졌다", refused.findings.single().observed)

        bench.validateAnswer = HostWrite.Answered(200, judgment("INPUT_UNKNOWN", unknown = UNKNOWN_SKILLS))
        assertEquals(emptyList(), assertIs<MissionValidation.Answered>(operations.validate(WM, 3)).reply.findings)

        bench.validateAnswer = HostWrite.Answered(404, """{"error":"DRAFT_NOT_FOUND","detail":"없다"}""")
        assertEquals(MissionValidation.HostRejected(HostRejection(404, "DRAFT_NOT_FOUND", "없다")), operations.validate(WM, 3))
        bench.validateAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
        assertEquals(MissionValidation.HostSilent("응답 없음: ConnectException"), operations.validate(WM, 3))
        bench.validateAnswer = HostWrite.Answered(200, judgment("ACTIVATED"))
        assertIs<MissionValidation.HostSilent>(operations.validate(WM, 3))
        assertEquals(emptyList(), log.list())
        assertEquals(emptyList(), bench.requeried)
    }

    @Test
    fun `재조회는 기본 값으로 쓰면 응답 없음 뒤 1초 가까이 기다린 다음에 찾는다`() {
        var wrote = 0L
        var read = 0L
        bench.mockRunAnswer = HostWrite.NoResponse("응답 없음")
        bench.requery = { HostRequery.NotFound.also { read = System.nanoTime() } }
        val timed = object : HostMissions by bench.host {
            override fun mockRun(draftId: Long, robotIds: List<String>, requestId: UUID) =
                bench.host.mockRun(draftId, robotIds, requestId).also { wrote = System.nanoTime() }
        }
        MissionOperations(bench.base.robotList, timed, log).mockRun(lee, WM, 3)
        assertTrue(read > wrote && Duration.ofNanos(read - wrote) >= Duration.ofMillis(900), "응답 없음과 재조회 사이 ${(read - wrote) / 1_000_000} ms")
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/MissionVersionControllerTest.kt`:

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.MissionBench.Companion.WM
import dev.picasso.ops.service.cell.CellSignalOperations
import dev.picasso.ops.service.cell.SignalWriteOutcome
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.missions.MissionOperationOutcome
import dev.picasso.ops.service.missions.MissionOperations
import dev.picasso.ops.service.missions.MissionValidationReply
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.web.CellSignalController
import dev.picasso.ops.service.web.MissionVersionController
import dev.picasso.ops.service.web.PreRejection
import dev.picasso.registry.PostgresSupport
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * 임무 버전·신호 조작 API 의 관문과 사전 거부(S3b 스펙 §7). 컨트롤러를 스프링 없이 바로 부른다. 관문(`guarded`)과 본문 읽기,
 * 상태 코드의 고름이 컨트롤러 안에 있기 때문이다. `application/json` 제한은 스프링의 몫이라 통합 시험이 본다.
 */
class MissionVersionControllerTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = MissionBench()
    private val missions = MissionVersionController(
        MissionOperations(bench.base.robotList, bench.host, log, requeryDelay = Duration.ZERO),
        bench.host,
    )
    private val signals = CellSignalController(CellSignalOperations(bench.host, bench.base.host, log, requeryDelay = Duration.ZERO))

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    private fun ResponseEntity<Any>.rejection(): PreRejection = assertIs<PreRejection>(body)

    /** 엔지니어 조작 넷을 [mode]·[user] 로 부른다. 본문은 각 조작의 정상 본문 또는 [body]. */
    private fun editAll(mode: String?, user: String? = "lee", body: String? = null): List<ResponseEntity<Any>> = listOf(
        missions.saveDraft(WM, mode, user, (body ?: """{"definition":"{}"}""").toByteArray()),
        missions.validate(WM, "3", mode, user),
        missions.mockRun(WM, "3", mode, user),
        missions.activate(WM, "3", mode, user, (body ?: """{"reason":"랙 도착 대기 도입"}""").toByteArray()),
    )

    @Test
    fun `임무 편집 넷은 운영자 모드면 403 이고 호스트를 부르지 않고 기록하지 않는다`() {
        editAll("operator").forEach { reply ->
            assertEquals(403, reply.statusCode.value())
            assertEquals("MODE_NOT_ALLOWED", reply.rejection().error)
            assertEquals("이 조작은 engineer 모드에서 한다", reply.rejection().detail)
        }
        // 깨진 본문도 관문이 먼저다.
        editAll("operator", body = "임무").forEach { assertEquals(403, it.statusCode.value()) }
        assertEquals(emptyList(), bench.writes())
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `행위자 헤더가 없거나 틀리면 넷 모두 400 ACTOR_REQUIRED 다`() {
        (editAll(null) + editAll("engineer", user = null) + editAll("engineer", user = "이 엔지")).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals("ACTOR_REQUIRED", reply.rejection().error)
        }
        assertEquals(emptyList(), bench.writes())
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `엔지니어 모드의 넷은 200 이고 검증을 뺀 셋만 기록한다`() {
        val replies = editAll("engineer")
        replies.forEach { assertEquals(200, it.statusCode.value()) }
        assertIs<MissionOperationOutcome>(replies[0].body)
        assertEquals("PASSED", assertIs<MissionValidationReply>(replies[1].body).outcome["result"].asText())
        assertEquals(OperationResult.SUCCEEDED, assertIs<MissionOperationOutcome>(replies[2].body).result)
        assertEquals(1, assertIs<MissionOperationOutcome>(replies[3].body).outcome!!["version"].asInt())
        assertEquals(listOf("saveDraft", "validate", "mockRun", "activate"), bench.writes().map { it.op })
        assertEquals(List(3) { OperationResult.SUCCEEDED }, log.list().map { it.result })
        assertEquals(setOf(WM), log.list().map { it.target }.toSet())
    }

    @Test
    fun `활성화 사유가 없거나 비었거나 공백뿐이면 400 REASON_REQUIRED 이고 호스트를 부르지 않는다`() {
        listOf("""{}""", """{"reason":""}""", """{"reason":"   "}""", """{"reason":3}""", "사유").forEach { body ->
            val reply = missions.activate(WM, "3", "engineer", "lee", body.toByteArray())
            assertEquals(400, reply.statusCode.value(), body)
            assertEquals(MissionVersionController.REASON_REQUIRED, reply.rejection().error, body)
        }
        assertEquals(400, missions.activate(WM, "3", "engineer", "lee", null).statusCode.value())
        assertEquals(emptyList(), bench.writes())
        assertEquals(emptyList(), log.list())

        missions.activate(WM, "3", "engineer", "lee", """{"reason":"  랙 도착 대기 도입 "}""".toByteArray())
        assertEquals("랙 도착 대기 도입", bench.writes().single().reason)
        assertEquals("랙 도착 대기 도입", log.list().single().reason)
    }

    @Test
    fun `편집하지 않는 WorkMaster 는 400 UNKNOWN_WORK_MASTER 이고 초안 id 와 정의가 틀리면 400 MISSION_BAD_REQUEST 다`() {
        listOf(
            missions.overview("InspectAsset"),
            missions.templates("DeliverContainer"),
            missions.saveDraft("InspectAsset", "engineer", "lee", """{"definition":"{}"}""".toByteArray()),
            missions.mockRun("InspectAsset", "3", "engineer", "lee"),
        ).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals("UNKNOWN_WORK_MASTER", reply.rejection().error)
        }
        listOf(
            missions.validate(WM, "0", "engineer", "lee"),
            missions.mockRun(WM, "x", "engineer", "lee"),
            missions.activate(WM, "-1", "engineer", "lee", """{"reason":"r"}""".toByteArray()),
            missions.saveDraft(WM, "engineer", "lee", """{"definition":{"schemaVersion":1}}""".toByteArray()),
            missions.saveDraft(WM, "engineer", "lee", null),
        ).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals(MissionVersionController.BAD_REQUEST, reply.rejection().error)
        }
        assertEquals(emptyList(), bench.calls)
        assertEquals(emptyList(), log.list())

        // 초안은 자유롭다. 빈 글자도 저장한다.
        assertEquals(200, missions.saveDraft(WM, "engineer", "lee", """{"definition":""}""".toByteArray()).statusCode.value())
        assertEquals("", bench.writes().single().definition)
    }

    @Test
    fun `읽기 둘은 모드 헤더 없이 되고 호스트 본문을 그대로 넘기며 호스트가 안 닿으면 503 이다`() {
        assertSame((bench.overview as HostCall.Ok).value, missions.overview(WM).body)
        assertSame((bench.templates as HostCall.Ok).value, missions.templates(WM).body)
        bench.overview = HostCall.Silent("응답 없음: ConnectException")
        bench.templates = HostCall.Silent("HTTP 500")
        val down = missions.overview(WM)
        assertEquals(503, down.statusCode.value())
        assertEquals(PreRejection("HOST_SILENT", "실행 호스트가 답하지 않는다: 응답 없음: ConnectException"), down.body)
        assertEquals(503, missions.templates(WM).statusCode.value())
    }

    @Test
    fun `시운전 완료 기체를 모르면 검증과 모의 실행과 활성화가 503 COMMISSIONED_ROBOTS_UNKNOWN 이다`() {
        bench.base.robots = RegistryCall.Silent("응답 없음: ConnectException")
        editAll("engineer").drop(1).forEach { reply ->
            assertEquals(503, reply.statusCode.value())
            assertEquals(MissionOperations.COMMISSIONED_ROBOTS_UNKNOWN, reply.rejection().error)
        }
        assertEquals(listOf("saveDraft"), bench.writes().map { it.op })
        assertEquals(1, log.list().size)
    }

    @Test
    fun `검증의 호스트 4xx 는 같은 상태 코드로, 불통은 503 HOST_SILENT 로 낸다`() {
        bench.validateAnswer = HostWrite.Answered(404, """{"error":"DRAFT_NOT_FOUND","detail":"초안 9 가 없다"}""")
        val missing = missions.validate(WM, "9", "engineer", "lee")
        assertEquals(404, missing.statusCode.value())
        assertEquals(PreRejection("DRAFT_NOT_FOUND", "초안 9 가 없다"), missing.body)
        bench.validateAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
        val down = missions.validate(WM, "9", "engineer", "lee")
        assertEquals(503, down.statusCode.value())
        assertEquals("HOST_SILENT", down.rejection().error)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `신호 조작은 운영자와 엔지니어 두 모드 모두 하고 행위자 헤더가 없으면 400 이다`() {
        listOf("operator" to "kim", "engineer" to "lee").forEach { (mode, user) ->
            val reply = signals.write("rack_present", mode, user, """{"value":"true"}""".toByteArray())
            assertEquals(200, reply.statusCode.value(), mode)
            assertEquals(OperationResult.SUCCEEDED, assertIs<SignalWriteOutcome>(reply.body).result)
        }
        assertEquals(listOf("kim", "lee"), log.list().sortedBy { it.recordedAt }.map { it.user })
        listOf(
            signals.write("rack_present", null, "kim", """{"value":"true"}""".toByteArray()),
            signals.write("rack_present", "operator", null, """{"value":"true"}""".toByteArray()),
        ).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals("ACTOR_REQUIRED", reply.rejection().error)
        }
        assertEquals(2, bench.writes().size)
    }

    @Test
    fun `신호 값이 문자열이 아니면 400 SIGNAL_BAD_REQUEST 이고 호스트를 부르지 않는다`() {
        listOf("""{"value":true}""", """{"value":null}""", """{}""", """["true"]""", "true").forEach { body ->
            val reply = signals.write("rack_present", "operator", "kim", body.toByteArray())
            assertEquals(400, reply.statusCode.value(), body)
            assertEquals(CellSignalController.BAD_REQUEST, reply.rejection().error, body)
        }
        assertEquals(emptyList(), bench.writes())
        assertEquals(emptyList(), log.list())
        // 값이 종류에 맞는지는 현장이 본다. 운영 서비스는 넘긴다.
        bench.signalAnswer = HostWrite.Answered(400, """{"error":"SIGNAL_VALUE_INVALID","detail":"x"}""")
        val reply = signals.write("rack_present", "operator", "kim", """{"value":"TRUE"}""".toByteArray())
        assertEquals(200, reply.statusCode.value())
        assertEquals("SIGNAL_VALUE_INVALID", assertIs<SignalWriteOutcome>(reply.body).rejection!!.error)
    }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/task3.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/task3.patch"
```

```diff
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
index 0578c46..eb1bec3 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
@@ -3,10 +3,12 @@ package dev.picasso.ops.service
 import dev.picasso.ops.service.operations.ProfileOperations
 import dev.picasso.ops.service.profiles.ProfileListService
 import dev.picasso.ops.service.adapters.AdapterListService
+import dev.picasso.ops.service.cell.CellSignalOperations
 import dev.picasso.ops.service.host.HostClient
 import dev.picasso.ops.service.joborders.JobOrderEligibility
 import dev.picasso.ops.service.joborders.JobOrderOperations
 import dev.picasso.ops.service.log.OperationLog
+import dev.picasso.ops.service.missions.MissionOperations
 import dev.picasso.ops.service.operations.AdapterOperations
 import dev.picasso.ops.service.operations.RobotOperations
 import dev.picasso.ops.service.registry.RegistryClient
@@ -129,6 +131,15 @@ open class OpsApplication {
         clock: Clock,
     ): JobOrderOperations = JobOrderOperations(eligibility, host, host, log, clock)
 
+    /** 시운전 완료 기체는 기체 목록의 판정을 그대로 쓴다(S3b 스펙 §7, T7). */
+    @Bean
+    open fun missionOperations(robots: RobotListService, host: HostClient, log: OperationLog): MissionOperations =
+        MissionOperations(robots, host, log)
+
+    @Bean
+    open fun cellSignalOperations(host: HostClient, log: OperationLog): CellSignalOperations =
+        CellSignalOperations(host, host, log)
+
     /** [migrated] 는 쓰지 않는다. 받는 것만으로 ops 마이그레이션 뒤에 이 빈이 만들어진다. */
     @Bean
     open fun operationLog(
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
index 4fbc5b3..91b2b64 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
@@ -12,7 +12,10 @@ import java.net.URI
 import java.net.http.HttpClient
 import java.net.http.HttpRequest
 import java.net.http.HttpResponse
+import java.net.URLEncoder
+import java.nio.charset.StandardCharsets
 import java.time.Duration
+import java.util.UUID
 
 /**
  * 실행 호스트 읽기 한 번의 결과(S3a 스펙 §8). «없음» 과 «모름» 을 접지 않는다.
@@ -116,17 +119,70 @@ fun interface HostWrites {
 }
 
 /**
- * 실행 호스트 REST 클라이언트(S3a 스펙 §8). 호스트는 루프백·무인증이라 토큰을 싣지 않는다.
+ * 요청 id 재조회 한 번의 결과(S3b 스펙 T9). «남은 행이 없다» 는 호스트의 응답(404 `REQUEST_NOT_FOUND`)이고 «못 읽음» 과
+ * 다르다. 앞의 것은 반영 안 됨으로 확인되고, 뒤의 것은 확인하지 못한 것이다. 호스트가 그 조작을 아직 처리 중이라는 응답
+ * (409 `REQUEST_IN_PROGRESS`)도 확인하지 못한 것이라 [Silent] 다.
+ */
+sealed interface HostRequery {
+    /** 그 요청 id 로 남은 행. 본문은 S3b JSON 계약 §4.7 의 모양 그대로다. */
+    data class Found(val body: JsonNode) : HostRequery
+
+    data object NotFound : HostRequery
+
+    data class Silent(val cause: String) : HostRequery
+}
+
+/**
+ * 호스트의 임무 버전 REST(S3b 스펙 §6.6). 시험이 호스트 없이 대신 끼운다.
+ *
+ * 두 읽기는 본문을 해석하지 않고 넘긴다. 쓰기 넷은 응답이 오면 코드와 본문을 그대로 넘기고 분류는 부르는 쪽이 한다. 검증은
+ * 아무것도 남기지 않지만 판정 결과가 200 본문에 있어 같은 모양으로 받는다. `requestId` 는 운영 서비스가 조작마다 만든
+ * 요청 id 이며 호스트가 행에 남긴다(T9).
+ */
+interface HostMissions {
+    /** `GET /host/missions/{workMasterId}` 본문 그대로. 200 아님은 모름이다. */
+    fun overview(workMasterId: String): HostCall<JsonNode>
+
+    /** `GET /host/missions/templates/{workMasterId}` 본문 그대로. */
+    fun templates(workMasterId: String): HostCall<JsonNode>
+
+    fun saveDraft(workMasterId: String, definition: String, actor: String, requestId: UUID): HostWrite
+
+    fun validate(draftId: Long, robotIds: List<String>): HostWrite
+
+    /** 호스트가 요청 안에서 동기로 돌리므로 요청 제한이 따로 길다(T10). */
+    fun mockRun(draftId: Long, robotIds: List<String>, requestId: UUID): HostWrite
+
+    fun activate(draftId: Long, actor: String, reason: String, robotIds: List<String>, requestId: UUID): HostWrite
+
+    /** `GET /host/missions/requests/{requestId}`. 응답 없음 뒤 재조회에만 쓴다. */
+    fun missionRequest(requestId: UUID): HostRequery
+}
+
+/** 셀 대역 신호 조작 전달(S3b 스펙 §7, 결정 3). 시험이 호스트 없이 대신 끼운다. */
+fun interface HostSignals {
+    /** `POST /host/cell/signals/{name}`. 호스트는 현장의 상태 코드와 본문을 그대로 돌려준다. */
+    fun writeSignal(name: String, value: String): HostWrite
+}
+
+/**
+ * 실행 호스트 REST 클라이언트(S3a 스펙 §8, S3b 스펙 §7). 호스트는 루프백·무인증이라 토큰을 싣지 않는다.
  *
  * 연결 제한은 registry 와 같고 요청 제한은 더 길다. 호스트는 판정과 제출을 자기 잠금 아래에서 하며, 그 안에서 mimic 에
  * gRPC 를 부르고, mimic 은 엔진 잠금 아래에서 registry 로 태스크 관측을 동기 HTTP 로 적재한다(요청 제한 3초, 스펙 §5.3).
  * 호스트가 registry 장애 한 번을 기다리는 동안 운영 서비스가 먼저 끊으면, 받은 제출을 응답 없음으로 남기게 된다.
+ *
+ * 모의 실행만 요청 제한이 [mockRunTimeout](60초)이다(S3b 스펙 T10). 호스트가 요청 안에서 동기로 돌리고 실제 시간 상한이
+ * 30초라, 5초에서 끊으면 돌고 있는 모의 실행을 응답 없음으로 남기게 된다. 60초를 기다리면 호스트는 이미 끝냈으므로 그래도
+ * 응답이 없을 때 재조회가 그 결과를 본다.
  */
 class HostClient(
     baseUrl: String,
     private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
     private val json: ObjectMapper = jacksonObjectMapper(),
-) : HostReads, HostWrites, AutoCloseable {
+    private val requestTimeout: Duration = REQUEST_TIMEOUT,
+    private val mockRunTimeout: Duration = MOCK_RUN_TIMEOUT,
+) : HostReads, HostWrites, HostMissions, HostSignals, AutoCloseable {
 
     private val base = checkBaseUrl(baseUrl)
 
@@ -156,32 +212,94 @@ class HostClient(
         return post("/host/job-orders", body)
     }
 
-    /** 객체 본문만 받는다. 호스트의 두 GET 은 늘 객체를 준다(S3a JSON 계약 §5·§6). */
+    override fun overview(workMasterId: String): HostCall<JsonNode> = get("/host/missions/${segment(workMasterId)}")
+
+    override fun templates(workMasterId: String): HostCall<JsonNode> = get("/host/missions/templates/${segment(workMasterId)}")
+
+    override fun saveDraft(workMasterId: String, definition: String, actor: String, requestId: UUID): HostWrite {
+        val body = json.createObjectNode()
+            .put("definition", definition)
+            .put("actor", actor)
+            .put("requestId", requestId.toString())
+        return post("/host/missions/${segment(workMasterId)}/drafts", body)
+    }
+
+    override fun validate(draftId: Long, robotIds: List<String>): HostWrite =
+        post("/host/missions/drafts/$draftId/validate", robots(json.createObjectNode(), robotIds))
+
+    override fun mockRun(draftId: Long, robotIds: List<String>, requestId: UUID): HostWrite {
+        val body = robots(json.createObjectNode(), robotIds).put("requestId", requestId.toString())
+        return post("/host/missions/drafts/$draftId/mock-run", body, mockRunTimeout)
+    }
+
+    override fun activate(draftId: Long, actor: String, reason: String, robotIds: List<String>, requestId: UUID): HostWrite {
+        val body = robots(json.createObjectNode().put("actor", actor).put("reason", reason), robotIds)
+            .put("requestId", requestId.toString())
+        return post("/host/missions/drafts/$draftId/activate", body)
+    }
+
+    /**
+     * 남은 행이 없다는 응답은 404 와 `REQUEST_NOT_FOUND` 가 함께일 때만이다. 다른 404(그 경로가 없는 서버 등)는 호스트의
+     * 판단이 아니므로 못 읽음이다. 409 `REQUEST_IN_PROGRESS` 는 호스트가 그 조작을 아직 처리 중이라는 응답이다. 행이 없다는
+     * 응답으로 접으면, 처리가 끝나 행이 남기 전에 반영 안 됨으로 확인하게 된다.
+     */
+    override fun missionRequest(requestId: UUID): HostRequery {
+        val request = HttpRequest.newBuilder(URI.create("$base/host/missions/requests/$requestId")).GET()
+        val response = when (val write = send(request, requestTimeout)) {
+            is HostWrite.NoResponse -> return HostRequery.Silent(write.cause)
+            is HostWrite.Answered -> write
+        }
+        val body = objectOrNull(response.body)
+        return when {
+            response.status == 200 && body != null -> HostRequery.Found(body)
+            response.status == 404 && body?.get("error")?.asText() == REQUEST_NOT_FOUND -> HostRequery.NotFound
+            response.status == 409 && body?.get("error")?.asText() == REQUEST_IN_PROGRESS -> HostRequery.Silent("호스트가 그 요청을 아직 처리 중이다")
+            response.status == 200 -> HostRequery.Silent("본문 모양이 다르다")
+            else -> HostRequery.Silent("HTTP ${response.status}")
+        }
+    }
+
+    override fun writeSignal(name: String, value: String): HostWrite =
+        post("/host/cell/signals/${segment(name)}", json.createObjectNode().put("value", value))
+
+    /** 객체 본문만 받는다. 호스트의 GET 은 늘 객체를 준다(S3a JSON 계약 §5·§6, S3b JSON 계약 §4). */
     private fun get(path: String): HostCall<JsonNode> {
-        val request = HttpRequest.newBuilder(URI.create(base + path)).timeout(REQUEST_TIMEOUT).GET().build()
-        val response = try {
-            http.send(request, HttpResponse.BodyHandlers.ofString())
-        } catch (e: IOException) {
-            return HostCall.Silent("응답 없음: ${e.javaClass.simpleName}")
+        val response = when (val write = send(HttpRequest.newBuilder(URI.create(base + path)).GET(), requestTimeout)) {
+            is HostWrite.NoResponse -> return HostCall.Silent(write.cause)
+            is HostWrite.Answered -> write
         }
-        if (response.statusCode() != 200) return HostCall.Silent("HTTP ${response.statusCode()}")
-        return read(response.body()) { body -> json.readTree(body).takeIf { it.isObject } }
+        if (response.status != 200) return HostCall.Silent("HTTP ${response.status}")
+        return read(response.body) { body -> json.readTree(body).takeIf { it.isObject } }
     }
 
-    private fun post(path: String, body: ObjectNode): HostWrite {
+    private fun post(path: String, body: ObjectNode, timeout: Duration = requestTimeout): HostWrite {
         val request = HttpRequest.newBuilder(URI.create(base + path))
-            .timeout(REQUEST_TIMEOUT)
             .header("Content-Type", "application/json")
             .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
-            .build()
-        return try {
-            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
-            HostWrite.Answered(response.statusCode(), response.body())
-        } catch (e: IOException) {
-            HostWrite.NoResponse("응답 없음: ${e.javaClass.simpleName}")
-        }
+        return send(request, timeout)
+    }
+
+    private fun send(request: HttpRequest.Builder, timeout: Duration): HostWrite = try {
+        val response = http.send(request.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString())
+        HostWrite.Answered(response.statusCode(), response.body())
+    } catch (e: IOException) {
+        HostWrite.NoResponse("응답 없음: ${e.javaClass.simpleName}")
     }
 
+    private fun robots(body: ObjectNode, robotIds: List<String>): ObjectNode {
+        body.putArray("robotIds").apply { robotIds.forEach(::add) }
+        return body
+    }
+
+    private fun objectOrNull(body: String): JsonNode? = try {
+        json.readTree(body)?.takeIf { it.isObject }
+    } catch (e: JacksonException) {
+        null
+    }
+
+    /** 경로 조각 하나로 인코딩한다. 신호 이름은 화면이 경로로 실어 온 값이라 그대로 붙이면 경로가 바뀔 수 있다. */
+    private fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
+
     /** [parse] 가 널을 내면(본문 `null`, 모양 어긋남) 값을 모르는 것이다. */
     private fun <T : Any> read(body: String, parse: (String) -> T?): HostCall<T> = try {
         parse(body)?.let { HostCall.Ok(it) } ?: HostCall.Silent("본문 모양이 다르다")
@@ -193,6 +311,15 @@ class HostClient(
         val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
         val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
 
+        /** 모의 실행 요청 제한(T10). 호스트의 실제 시간 상한 30초보다 길어야 한다. */
+        val MOCK_RUN_TIMEOUT: Duration = Duration.ofSeconds(60)
+
+        /** 재조회에서 남은 행이 없다는 호스트 오류 이름(S3b JSON 계약 §3). */
+        const val REQUEST_NOT_FOUND = "REQUEST_NOT_FOUND"
+
+        /** 재조회에서 호스트가 그 요청을 아직 처리 중이라는 오류 이름(S3b JSON 계약 §3). */
+        const val REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS"
+
         /** 형식이 틀린 주소를 기동에서 잡는다. 그대로 두면 요청마다 호스트 불통으로 보인다. */
         fun checkBaseUrl(baseUrl: String): String {
             val trimmed = baseUrl.trimEnd('/')
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt
index 64980ca..d7033ff 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt
@@ -14,17 +14,29 @@ data class PreRejection(val error: String, val detail: String)
 /**
  * 조작 API 의 관문. 행위자 헤더가 없거나 틀리면 400, 모드가 맞지 않으면 403 으로 registry 를 부르기 전에 막는다(스펙 §9).
  * 기체 조작과 어댑터 조작이 같이 쓴다. 교차 출처 방어에서 이 헤더가 맡는 몫은 [RobotOperationsController] 에 적었다.
+ * S3b 부터 실행 호스트 조작(임무 버전, 신호 조작)도 같이 쓴다. 막은 요청은 호스트에도 닿지 않는다.
  */
 internal inline fun guarded(
     mode: String?,
     user: String?,
     required: Mode,
     action: (Actor) -> ResponseEntity<Any>,
+): ResponseEntity<Any> = guarded(mode, user, setOf(required), action)
+
+/**
+ * 여러 모드를 받는 관문(S3b 스펙 §7). 셀 대역 신호 조작은 운영자·엔지니어 두 모드 모두 하지만, 행위자 헤더는 조작 기록의
+ * 사람 칸이라 빠지면 여전히 400 이다.
+ */
+internal inline fun guarded(
+    mode: String?,
+    user: String?,
+    allowed: Set<Mode>,
+    action: (Actor) -> ResponseEntity<Any>,
 ): ResponseEntity<Any> {
     val actor = Actor.fromHeaders(mode, user)
         ?: return reject(HttpStatus.BAD_REQUEST, "ACTOR_REQUIRED", "${Actor.MODE_HEADER}·${Actor.USER_HEADER} 헤더가 없거나 틀리다")
-    if (actor.mode != required) {
-        return reject(HttpStatus.FORBIDDEN, "MODE_NOT_ALLOWED", "이 조작은 ${required.wire} 모드에서 한다")
+    if (actor.mode !in allowed) {
+        return reject(HttpStatus.FORBIDDEN, "MODE_NOT_ALLOWED", "이 조작은 ${allowed.joinToString("·") { it.wire }} 모드에서 한다")
     }
     return action(actor)
 }
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && ./gradlew :ops-service:check -q
```
Expected: ops-service 199, 실패 0(`check` 는 시험과 함께 운영 서비스가 picasso 를 쓰지 않는 경계 `checkNoPicassoOnMain` 을 집행한다). 백그라운드로 돌린다.

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && git add ops-service/src/main/kotlin/dev/picasso/ops/service/cell/CellSignalOperations.kt ops-service/src/main/kotlin/dev/picasso/ops/service/missions/MissionFindings.kt ops-service/src/main/kotlin/dev/picasso/ops/service/missions/MissionOperations.kt ops-service/src/main/kotlin/dev/picasso/ops/service/operations/HostOperationRunner.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/CellSignalController.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/MissionVersionController.kt ops-service/src/test/kotlin/dev/picasso/ops/service/CellSignalOperationsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/HostMissionsClientTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/MissionBench.kt ops-service/src/test/kotlin/dev/picasso/ops/service/MissionFindingsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/MissionOperationsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/MissionVersionControllerTest.kt ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt && git commit -F - <<'EOF'
feat(ops-service): 임무 버전 조작과 셀 대역 신호 조작 엔드포인트 추가

- 엔지니어 모드의 초안 저장·검증·모의 실행·활성화, 요청 id 재조회, 거부 카드 변환
- 두 모드의 신호 조작, 모의 실행만 긴 요청 제한

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s3b-cmp.sh" ops-service/src/main/kotlin/dev/picasso/ops/service/cell/CellSignalOperations.kt ops-service/src/main/kotlin/dev/picasso/ops/service/missions/MissionFindings.kt ops-service/src/main/kotlin/dev/picasso/ops/service/missions/MissionOperations.kt ops-service/src/main/kotlin/dev/picasso/ops/service/operations/HostOperationRunner.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/CellSignalController.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/MissionVersionController.kt ops-service/src/test/kotlin/dev/picasso/ops/service/CellSignalOperationsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/HostMissionsClientTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/MissionBench.kt ops-service/src/test/kotlin/dev/picasso/ops/service/MissionFindingsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/MissionOperationsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/MissionVersionControllerTest.kt ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt
```
Expected: 15개 모두 `같음`.

### Task 4: 화면: 임무·정책 영역과 셀 대역 신호

**Files:**
- Create: `ui/src/components/MissionNotice.tsx`, `ui/src/components/MissionsArea.test.tsx`, `ui/src/components/MissionsArea.tsx`, `ui/src/components/MockRunReport.tsx`, `ui/src/components/SignalNotice.tsx`
- Modify: `ui/src/App.test.tsx`, `ui/src/App.tsx`, `ui/src/api.ts`, `ui/src/areas.ts`, `ui/src/components/CellBand.tsx`, `ui/src/components/OperationsArea.test.tsx`, `ui/src/components/OperationsArea.tsx`, `ui/src/labels.ts`, `ui/src/poll.ts`, `ui/src/styles.css`, `ui/src/testing/fakeOps.ts`

- [ ] **Step 1: 새 파일 5개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ui/src/components/MissionNotice.tsx" ui/src/components/MissionNotice.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ui/src/components/MissionsArea.test.tsx" ui/src/components/MissionsArea.test.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ui/src/components/MissionsArea.tsx" ui/src/components/MissionsArea.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ui/src/components/MockRunReport.tsx" ui/src/components/MockRunReport.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/ui/src/components/SignalNotice.tsx" ui/src/components/SignalNotice.tsx
```

`ui/src/components/MissionNotice.tsx`:

```tsx
import type { ReactNode } from 'react'
import type {
  Delivered,
  DraftSaved,
  Finding,
  HostActivation,
  HostMockRun,
  InputUnknown,
  MissionOperationOutcome,
  MissionValidationReply,
} from '../api'
import { kindLabel, mockRunVerdict } from '../labels'
import { FindingCard } from './FindingCard'
import { MockRunReport } from './MockRunReport'

/** 임무 조작 하나와 그 결과. 조작마다 실행 호스트 본문의 모양이 다르다(S3b JSON 계약 §10.4~§10.7). */
export type MissionSent =
  | { op: 'save'; what: string; sent: Delivered<MissionOperationOutcome<DraftSaved>> }
  | { op: 'validate'; what: string; sent: Delivered<MissionValidationReply> }
  | { op: 'mockRun'; what: string; sent: Delivered<MissionOperationOutcome<HostMockRun>> }
  | { op: 'activate'; what: string; sent: Delivered<MissionOperationOutcome<HostActivation>> }

/**
 * 임무 조작의 결과(S3b 스펙 §8, S3b JSON 계약 §10.1). 운영 서비스의 `REJECTED` 만 보고 거부 카드를 그리지 않고 호스트 본문의
 * `result` 로 보인다. 거부 카드는 `REFUSED` 뿐이고, `INPUT_UNKNOWN` 과 시운전 완료 기체를 모르는 503 은 모름, `MOCK_RUN_REQUIRED`
 * 는 따로 적는다. 응답 없음은 재조회로 확인되기 전에는 성공으로도 실패로도 보이지 않는다.
 */
export function MissionNotice({ last }: { last: MissionSent }) {
  return (
    <div role="status" aria-label="임무 조작 결과">
      {describe(last)}
    </div>
  )
}

function describe(last: MissionSent): ReactNode {
  const { what } = last
  switch (last.op) {
    case 'save':
      return delivered(what, last.sent, (operation) =>
        operated(what, operation, ({ draft }) => (
          <p>
            {what}: 초안 {draft.draftId} 저장됨
          </p>
        )),
      )
    case 'validate':
      return delivered(what, last.sent, ({ outcome, findings }) => {
        if (outcome == null) return unreadable(what)
        return judged(what, outcome.result, outcome.unknown, findings) ?? <p>{what}: 통과</p>
      })
    case 'mockRun':
      return delivered(what, last.sent, (operation) =>
        operated(what, operation, (outcome, findings) => {
          const judgment = judged(what, outcome.result, outcome.unknown, findings)
          if (judgment !== null || outcome.mockRun === null) return judgment
          return (
            <>
              <p>
                {what}: {mockRunVerdict(outcome.mockRun)}
              </p>
              <MockRunReport run={outcome.mockRun} />
            </>
          )
        }),
      )
    case 'activate':
      return delivered(what, last.sent, (operation) =>
        operated(what, operation, (outcome, findings) => {
          const judgment = judged(what, outcome.result, outcome.unknown, findings)
          if (judgment !== null) return judgment
          if (outcome.result === 'MOCK_RUN_REQUIRED') {
            return (
              <>
                <p>{what}: 막힘. 이 초안에 통과한 모의 실행이 없습니다. 모의 실행을 먼저 하십시오</p>
                {outcome.lastMockRun !== null && <p>마지막 모의 실행: {mockRunVerdict(outcome.lastMockRun)}</p>}
              </>
            )
          }
          return (
            <p>
              {what}: 버전 {outcome.version} 활성화됨. 다음 작업 지시부터 이 버전을 씁니다
            </p>
          )
        }),
      )
  }
}

/**
 * 운영 서비스가 답하지 않았거나 먼저 막은 것. 시운전 완료 기체를 몰라 실행 호스트를 부르지 않은 503 은 거부가 아니라 모름이다(S3b
 * JSON 계약 §10.1). 운영 서비스가 2xx 로 답했으면 [show] 가 그린다.
 */
function delivered<T>(what: string, sent: Delivered<T>, show: (outcome: T) => ReactNode): ReactNode {
  if (sent.kind === 'refused') {
    if (sent.refusal.error === 'COMMISSIONED_ROBOTS_UNKNOWN') {
      return (
        <p>
          {what}: 모름. {sent.refusal.detail}. 판정하지 않았습니다
        </p>
      )
    }
    return (
      <>
        <p>
          {what}: 막힘({kindLabel(sent.refusal.error)})
        </p>
        <p>{sent.refusal.detail}</p>
      </>
    )
  }
  if (sent.kind === 'unknown') {
    return (
      <p>
        {what}: 결과 모름({sent.cause}). 초안·버전 목록을 다시 읽어 확인하십시오
      </p>
    )
  }
  return show(sent.outcome)
}

/** 실행 호스트에 보낸 조작. 응답 없음과 호스트의 4xx 를 먼저 보이고, 호스트 본문이 있으면 [show] 가 그린다. */
function operated<T>(
  what: string,
  operation: MissionOperationOutcome<T>,
  show: (outcome: T, findings: Finding[]) => ReactNode,
): ReactNode {
  if (operation.result === 'NO_RESPONSE') {
    return (
      <p>
        {what}: {noResponse(operation.confirmation)}
      </p>
    )
  }
  if (operation.rejection !== null) {
    return (
      <>
        <p>
          {what}: 실행 호스트가 거부함({kindLabel(operation.rejection.error)})
        </p>
        <p>{operation.rejection.detail}</p>
      </>
    )
  }
  if (operation.outcome == null) return unreadable(what)
  return show(operation.outcome, operation.findings)
}

/** 운영 서비스가 2xx 로 답했으나 호스트 본문이 없다. 반영 여부를 모르므로 목록에서 확인하게 한다. */
function unreadable(what: string): ReactNode {
  return <p>{what}: 결과 모름. 초안·버전 목록을 다시 읽어 확인하십시오</p>
}

/** 판정 입력을 몰랐거나(모름) 정의를 거부한 결과. 둘 다 아니면 null 이고 조작마다 나머지 결과를 보인다. */
function judged(what: string, result: string, unknown: InputUnknown | null, findings: Finding[]): ReactNode | null {
  switch (result) {
    case 'INPUT_UNKNOWN':
      return (
        <p>
          {what}: 모름. {unknown?.detail ?? '판정 입력을 읽지 못했습니다'}. 판정하지 않았습니다
        </p>
      )
    case 'REFUSED':
      return (
        <>
          <p>{what}: 거부됨</p>
          {findings.map((finding, index) => (
            <FindingCard key={index} finding={finding} />
          ))}
        </>
      )
    default:
      return null
  }
}

/** 응답 없음 뒤 재조회의 결과(S3b JSON 계약 §10.1). 확인 결과가 없으면 그 재조회도 실패한 것이다. */
function noResponse(confirmation: MissionOperationOutcome<unknown>['confirmation']): string {
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return '응답은 없었으나 다시 읽어 보니 반영됨. 초안·버전 목록에서 보십시오'
    case 'CONFIRMED_NOT_APPLIED':
      return '응답 없음. 다시 읽어 보니 반영 안 됨. 다시 하려면 새로 요청하십시오'
    default:
      return '반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 초안·버전 목록에서 확인하십시오'
  }
}
```

`ui/src/components/MissionsArea.test.tsx`:

```tsx
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Finding, MissionOperationOutcome, Session } from '../api'
import {
  ARRIVAL_WAIT,
  DATA_V1,
  MISSION_PATH,
  draftRow,
  installFakeOps,
  missionOverview,
  mockRunRow,
  versionRow,
} from '../testing/fakeOps'
import type { FakeOps } from '../testing/fakeOps'
import { MissionsArea } from './MissionsArea'

const engineer: Session = { mode: 'engineer', user: 'lee' }
const operator: Session = { mode: 'operator', user: 'kim' }

const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }

const DRAFTS = `${MISSION_PATH}/drafts`
const draftPath = (draftId: number, action: string) => `${MISSION_PATH}/drafts/${draftId}/${action}`

/** 운영 서비스의 조작 200 본문(S3b JSON 계약 §10.4). 기본은 호스트가 받은 것이다. */
function operation<T>(outcome: T, partial: Partial<MissionOperationOutcome<T>> = {}): MissionOperationOutcome<T> {
  return {
    requestId: '6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a',
    workMasterId: 'PrepareSequencedRack',
    result: 'SUCCEEDED',
    confirmation: null,
    outcome,
    findings: [],
    rejection: null,
    ...partial,
  }
}

/** 실행 호스트 판정의 공통 칸(S3b JSON 계약 §4.4). */
function judgment<R extends string>(result: R, partial: object = {}) {
  return {
    result,
    draftId: 7,
    workMasterId: 'PrepareSequencedRack',
    checkedAt: 't2',
    refusals: [],
    unknown: null,
    inputs: null,
    ...partial,
  }
}

/** 운영 서비스가 호스트 거부를 옮긴 거부 카드(S3b JSON 계약 §10.2). */
const notInSpec: Finding = {
  kind: 'SIGNAL_NOT_IN_SPEC',
  observed: '노드 rack-arrival: rack_ready',
  expected: '신호 사양의 이름 중 하나(guard_closed, lot_code, rack_present)',
  checkedAt: 't2',
  owner: 'ENGINEER',
  inScreen: true,
  action: '신호 이름을 고치거나 신호 사양에 더한다',
  target: null,
  basisVersion: null,
}

const signalSpecUnknown = {
  inputs: ['SIGNAL_SPEC'],
  robots: [],
  detail: '셀 대역 스냅숏이 없어 신호 사양을 못 읽었다',
}

const posts = (fake: FakeOps, url: string) => fake.calls.filter((call) => call.method === 'POST' && call.url === url)

/** 한 행의 칸 글자. */
const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((cell) => cell.textContent)

/** 칸 이름(dt) 바로 뒤의 값(dd)을 읽는다. */
function field(region: HTMLElement, name: string) {
  const term = within(region).getByText(name, { selector: 'dt' })
  return term.nextElementSibling?.textContent
}

function open(session: Session = engineer) {
  return render(<MissionsArea session={session} onChanged={() => undefined} />)
}

const editor = () => screen.getByRole('textbox', { name: '임무 정의 JSON' }) as HTMLTextAreaElement
const notice = () => screen.findByRole('status', { name: '임무 조작 결과' })
const click = (name: string) => userEvent.click(screen.getByRole('button', { name }))

/** 초안 목록의 초안 7 을 열어 검증·모의 실행·활성화의 대상으로 둔다. */
async function openDraft(fake: FakeOps) {
  fake.mission = missionOverview({ drafts: [draftRow()] })
  open()
  await userEvent.click(await screen.findByRole('button', { name: '초안 7 열기' }))
  expect(editor().value).toBe(ARRIVAL_WAIT)
}

describe('임무·정책 영역', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('운영자 모드는 활성 버전과 버전 이력을 보이고 편집기와 조작 버튼 없이 엔지니어 모드에서 한다고 보인다', async () => {
    const fake = installFakeOps(emptyList)
    const v2 = versionRow({ version: 2, draftId: 7, definition: ARRIVAL_WAIT, reason: '랙 도착 대기 도입' })
    fake.mission = missionOverview({
      active: { version: 2, source: 'DATA', detail: v2 },
      versions: [v2, versionRow()],
      drafts: [draftRow({ lastMockRun: mockRunRow() })],
    })
    open(operator)
    const head = screen.getByRole('region', { name: '임무 PrepareSequencedRack' })
    await waitFor(() => expect(field(head, '활성 버전')).toBe('버전 2'))
    expect(field(head, '활성화')).toBe('lee t1: 랙 도착 대기 도입')
    expect(screen.getByText('임무 편집은 엔지니어 모드에서 합니다')).toBeInTheDocument()
    expect(screen.getByLabelText('활성 버전 정의')).toHaveTextContent(ARRIVAL_WAIT)
    for (const name of ['초안 저장', '검증', '모의 실행', '활성화', '초안 7 열기']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
    expect(screen.queryByRole('textbox', { name: '임무 정의 JSON' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('활성화 사유')).not.toBeInTheDocument()
    const versions = screen.getByRole('table', { name: '임무 버전 이력' })
    expect(within(versions).getAllByRole('row').slice(1).map(cells)).toEqual([
      ['버전 2 (활성)', '초안 7', 'lee', '랙 도착 대기 도입', 't1'],
      ['버전 1', '초안 1', 'lee', '데이터 정의로 전환', 't1'],
    ])
    const drafts = screen.getByRole('table', { name: '임무 초안 목록' })
    expect(cells(within(drafts).getAllByRole('row')[1]).slice(0, 3)).toEqual(['초안 7', 'local', 't1'])
    expect(within(drafts).getByText('통과 t3')).toBeInTheDocument()
  })

  it('활성 버전이 없으면 코드 정의이고 편집기는 데이터 정의 템플릿으로 시작하며 템플릿을 불러오면 내용이 바뀐다', async () => {
    installFakeOps(emptyList)
    open()
    const head = screen.getByRole('region', { name: '임무 PrepareSequencedRack' })
    await waitFor(() => expect(field(head, '활성 버전')).toBe('코드 정의'))
    await waitFor(() => expect(editor().value).toBe(DATA_V1))
    expect(screen.getByText('코드 정의는 정의 JSON 이 없습니다')).toBeInTheDocument()
    expect(screen.getByText('활성화한 버전이 없습니다. 코드 정의로 돕니다')).toBeInTheDocument()
    expect(
      screen.getByText('저장한 초안이 없습니다. 초안을 저장하면 검증·모의 실행·활성화할 수 있습니다'),
    ).toBeInTheDocument()
    for (const name of ['검증', '모의 실행', '활성화']) {
      expect(screen.getByRole('button', { name })).toBeDisabled()
    }

    await click('랙 도착 대기 템플릿 불러오기')
    expect(editor().value).toBe(ARRIVAL_WAIT)
    await click('데이터 정의 템플릿 불러오기')
    expect(editor().value).toBe(DATA_V1)
  })

  it('활성 버전이 있으면 편집기는 그 정의로 시작하고 활성 버전 정의와 나란히 같은지 보인다', async () => {
    const fake = installFakeOps(emptyList)
    const v1 = versionRow({ definition: '{"active": 1}' })
    fake.mission = missionOverview({ active: { version: 1, source: 'DATA', detail: v1 }, versions: [v1] })
    open()
    await waitFor(() => expect(editor().value).toBe('{"active": 1}'))
    expect(screen.getByLabelText('활성 버전 정의')).toHaveTextContent('{"active": 1}')
    expect(screen.getByText('활성: 버전 1, 편집기와 같음')).toBeInTheDocument()
    await userEvent.type(editor(), ' ')
    expect(screen.getByText('활성: 버전 1, 편집기와 다름')).toBeInTheDocument()
  })

  it('초안 저장 뒤 검증 거부는 거부 카드로, 모의 실행은 단위별 표로 보이고 활성화하면 버전 이력에 새 버전이 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answers.set(DRAFTS, { status: 200, body: operation({ draft: draftRow() }) })
    fake.answers.set(draftPath(7, 'validate'), {
      status: 200,
      body: { workMasterId: 'PrepareSequencedRack', outcome: judgment('REFUSED'), findings: [notInSpec] },
    })
    fake.answers.set(draftPath(7, 'mock-run'), {
      status: 200,
      body: operation({ ...judgment('PASSED'), mockRun: mockRunRow() }),
    })
    const v3 = versionRow({ version: 3, draftId: 7, definition: ARRIVAL_WAIT, reason: '랙 도착 대기 도입' })
    fake.answers.set(draftPath(7, 'activate'), {
      status: 200,
      body: operation({ ...judgment('ACTIVATED'), version: 3, lastMockRun: mockRunRow(), activated: v3 }),
    })
    open()
    await userEvent.click(await screen.findByRole('button', { name: '랙 도착 대기 템플릿 불러오기' }))

    await click('초안 저장')
    expect(await notice()).toHaveTextContent('초안 저장: 초안 7 저장됨')
    const saved = posts(fake, DRAFTS)
    expect(saved).toHaveLength(1)
    expect(saved[0].body).toEqual({ definition: ARRIVAL_WAIT })
    expect(saved[0].headers['X-Ops-Mode']).toBe('engineer')
    expect(saved[0].headers['X-Ops-User']).toBe('lee')
    expect(saved[0].headers['Content-Type']).toBe('application/json')
    expect(screen.getByText('대상: 초안 7, 마지막 모의 실행 없음')).toBeInTheDocument()

    await click('검증')
    await waitFor(async () => expect(await notice()).toHaveTextContent('초안 7 검증: 거부됨'))
    expect(posts(fake, draftPath(7, 'validate'))[0].body).toEqual({})
    const refused = await notice()
    expect(field(refused, '종류')).toBe('신호 사양에 없는 신호')
    expect(field(refused, '관측값과 기대값')).toBe(
      '노드 rack-arrival: rack_ready / 기대: 신호 사양의 이름 중 하나(guard_closed, lot_code, rack_present)',
    )
    expect(field(refused, '해결 담당')).toBe('엔지니어(화면 안): 신호 이름을 고치거나 신호 사양에 더한다')
    expect(within(refused).queryByText('바로 가기')).not.toBeInTheDocument()

    await click('모의 실행')
    await waitFor(async () => expect(await notice()).toHaveTextContent('초안 7 모의 실행: 통과'))
    const units = within(await notice()).getByRole('table', { name: '모의 실행 5 단위' })
    expect(within(units).getAllByRole('row').slice(1).map(cells)).toEqual([
      ['rack-arrival', '설비 대기', 'equipment_wait', 'DONE', 'E2', '-'],
      ['RACK-204.S01', '기체', 'pick_place', 'DONE', 'E2', '-'],
    ])
    expect(field(await notice(), '표본 작업 지시')).toBe(
      'MOCK-7, 슬롯 RACK-204.S01, RACK-204.S02, 자재 ENGINE-COVER-A, 요구 근거 E2',
    )

    fake.mission = missionOverview({
      active: { version: 3, source: 'DATA', detail: v3 },
      versions: [v3],
      drafts: [draftRow({ lastMockRun: mockRunRow() })],
    })
    await userEvent.type(screen.getByLabelText('활성화 사유'), ' 랙 도착 대기 도입 ')
    await click('활성화')
    await waitFor(async () =>
      expect(await notice()).toHaveTextContent('초안 7 활성화: 버전 3 활성화됨. 다음 작업 지시부터 이 버전을 씁니다'),
    )
    expect(posts(fake, draftPath(7, 'activate'))[0].body).toEqual({ reason: '랙 도착 대기 도입' })
    const versions = await screen.findByRole('table', { name: '임무 버전 이력' })
    expect(cells(within(versions).getAllByRole('row')[1])).toEqual([
      '버전 3 (활성)',
      '초안 7',
      'lee',
      '랙 도착 대기 도입',
      't1',
    ])
  })

  it('활성화 사유가 없거나 공백뿐이면 보내지 않고 알린다', async () => {
    const fake = installFakeOps(emptyList)
    await openDraft(fake)
    await click('활성화')
    expect(screen.getByRole('alert')).toHaveTextContent('활성화 사유를 넣으십시오')
    await userEvent.type(screen.getByLabelText('활성화 사유'), '   ')
    await click('활성화')
    expect(screen.getByRole('alert')).toHaveTextContent('활성화 사유를 넣으십시오')
    expect(posts(fake, draftPath(7, 'activate'))).toEqual([])
  })

  it('활성화 사유는 활성화가 섰을 때만 지우고 막힘이나 응답 없음이면 남긴다', async () => {
    const fake = installFakeOps(emptyList)
    const answer = (body: unknown) => fake.answers.set(draftPath(7, 'activate'), { status: 200, body })
    const reason = () => screen.getByLabelText('활성화 사유') as HTMLInputElement
    answer(
      operation(
        { ...judgment('MOCK_RUN_REQUIRED'), version: null, lastMockRun: null, activated: null },
        { result: 'REJECTED' },
      ),
    )
    await openDraft(fake)
    await userEvent.type(reason(), '랙 도착 대기 도입')
    await click('활성화')
    await waitFor(async () => expect(await notice()).toHaveTextContent('초안 7 활성화: 막힘'))
    expect(reason().value).toBe('랙 도착 대기 도입')

    answer(operation(null, { result: 'NO_RESPONSE' }))
    await click('활성화')
    await waitFor(async () => expect(await notice()).toHaveTextContent('반영되었을 수 있음'))
    expect(reason().value).toBe('랙 도착 대기 도입')

    const v1 = versionRow({ version: 1, draftId: 7, reason: '랙 도착 대기 도입' })
    answer(operation({ ...judgment('ACTIVATED'), version: 1, lastMockRun: mockRunRow(), activated: v1 }))
    await click('활성화')
    await waitFor(async () => expect(await notice()).toHaveTextContent('초안 7 활성화: 버전 1 활성화됨'))
    expect(reason().value).toBe('')
    expect(posts(fake, draftPath(7, 'activate')).map((call) => call.body)).toEqual(
      Array(3).fill({ reason: '랙 도착 대기 도입' }),
    )
  })

  it('임무 개요를 읽기 전에는 편집기의 처음 내용을 몰라 초안 저장을 막는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.failing.add(MISSION_PATH)
    const { rerender } = open()
    expect(await screen.findByText(/^모름: 임무 버전을 아직 읽지 못했습니다 \(/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '초안 저장' })).toBeDisabled()
    await userEvent.type(editor(), '{{}')
    expect(screen.getByRole('button', { name: '초안 저장' })).toBeDisabled()

    fake.failing.delete(MISSION_PATH)
    // 세션이 바뀌면 다시 읽는다. 주기(5초)를 기다리지 않는다.
    rerender(<MissionsArea session={{ ...engineer }} onChanged={() => undefined} />)
    await waitFor(() => expect(screen.getByRole('button', { name: '초안 저장' })).toBeEnabled())
    expect(posts(fake, DRAFTS)).toEqual([])
  })

  it('INPUT_UNKNOWN 과 시운전 완료 기체를 모르는 503 은 거부 카드가 아니라 모름이다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answers.set(draftPath(7, 'validate'), {
      status: 200,
      body: {
        workMasterId: 'PrepareSequencedRack',
        outcome: judgment('INPUT_UNKNOWN', { unknown: signalSpecUnknown }),
        findings: [],
      },
    })
    fake.answers.set(draftPath(7, 'activate'), {
      status: 200,
      body: operation(
        { ...judgment('INPUT_UNKNOWN', { unknown: signalSpecUnknown }), version: null, lastMockRun: null, activated: null },
        { result: 'REJECTED' },
      ),
    })
    fake.answers.set(draftPath(7, 'mock-run'), {
      status: 503,
      body: { error: 'COMMISSIONED_ROBOTS_UNKNOWN', detail: 'registry 가 답하지 않아 시운전 완료 기체를 모른다' },
    })
    await openDraft(fake)

    await click('검증')
    let shown = await notice()
    expect(shown).toHaveTextContent(
      '초안 7 검증: 모름. 셀 대역 스냅숏이 없어 신호 사양을 못 읽었다. 판정하지 않았습니다',
    )
    expect(shown).not.toHaveTextContent('거부')
    expect(within(shown).queryByText('관측값과 기대값')).not.toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('활성화 사유'), '전환')
    await click('활성화')
    await waitFor(async () => expect(await notice()).toHaveTextContent('초안 7 활성화: 모름'))
    shown = await notice()
    expect(shown).toHaveTextContent('셀 대역 스냅숏이 없어 신호 사양을 못 읽었다')
    expect(shown).not.toHaveTextContent('거부')
    expect(shown).not.toHaveTextContent('활성화됨')

    await click('모의 실행')
    await waitFor(async () =>
      expect(await notice()).toHaveTextContent(
        '초안 7 모의 실행: 모름. registry 가 답하지 않아 시운전 완료 기체를 모른다. 판정하지 않았습니다',
      ),
    )
    expect(await notice()).not.toHaveTextContent('막힘')
  })

  it('통과한 모의 실행 없이 활성화하면 따로 적고 마지막 모의 실행의 실패를 붙인다', async () => {
    const fake = installFakeOps(emptyList)
    const failed = mockRunRow({
      passed: false,
      result: { ...mockRunRow().result, passed: false, failure: 'NOT_SETTLED', detail: '정착하지 않았다' },
    })
    fake.answers.set(draftPath(7, 'activate'), {
      status: 200,
      body: operation(
        { ...judgment('MOCK_RUN_REQUIRED'), version: null, lastMockRun: failed, activated: null },
        { result: 'REJECTED' },
      ),
    })
    await openDraft(fake)
    await userEvent.type(screen.getByLabelText('활성화 사유'), '전환')
    await click('활성화')
    const shown = await notice()
    expect(shown).toHaveTextContent(
      '초안 7 활성화: 막힘. 이 초안에 통과한 모의 실행이 없습니다. 모의 실행을 먼저 하십시오',
    )
    expect(shown).toHaveTextContent('마지막 모의 실행: 실패(정착하지 않음)')
    expect(shown).not.toHaveTextContent('거부')
    expect(shown).not.toHaveTextContent('활성화됨')
  })

  it('모의 실행 실패는 하위 범주와 이유를 보이고 초안 목록에 그 초안의 마지막 모의 실행으로 남는다', async () => {
    const fake = installFakeOps(emptyList)
    const failed = mockRunRow({
      mockRunId: 6,
      passed: false,
      result: {
        ...mockRunRow().result,
        passed: false,
        failure: 'NOT_SETTLED',
        detail: '가상 시간 상한 PT10M 안에 정착하지 않았다',
        physicalState: 'RUNNING',
        units: [
          { unitId: 'rack-arrival', route: 'SIGNAL', skillType: 'equipment_wait', state: 'RUNNING', reached: 'E0', failureClass: null },
        ],
      },
    })
    fake.answers.set(draftPath(7, 'mock-run'), {
      status: 200,
      body: operation({ ...judgment('FAILED'), mockRun: failed }),
    })
    await openDraft(fake)
    fake.mission = missionOverview({ drafts: [draftRow({ lastMockRun: failed })] })
    await click('모의 실행')
    const shown = await notice()
    expect(shown).toHaveTextContent('초안 7 모의 실행: 실패(정착하지 않음)')
    expect(shown).toHaveTextContent('모의 실행 6: 실패(정착하지 않음). 가상 시간 상한 PT10M 안에 정착하지 않았다')
    expect(cells(within(within(shown).getByRole('table', { name: '모의 실행 6 단위' })).getAllByRole('row')[1])).toEqual([
      'rack-arrival',
      '설비 대기',
      'equipment_wait',
      'RUNNING',
      'E0',
      '-',
    ])
    const drafts = screen.getByRole('table', { name: '임무 초안 목록' })
    expect(await within(drafts).findByText('실패(정착하지 않음) t3')).toBeInTheDocument()
    expect(screen.getByText('대상: 초안 7, 마지막 모의 실행 실패(정착하지 않음)')).toBeInTheDocument()
  })

  it.each([
    ['CONFIRMED_APPLIED' as const, '응답은 없었으나 다시 읽어 보니 반영됨. 초안·버전 목록에서 보십시오'],
    ['CONFIRMED_NOT_APPLIED' as const, '응답 없음. 다시 읽어 보니 반영 안 됨. 다시 하려면 새로 요청하십시오'],
    [null, '반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다'],
  ])('초안 저장의 응답 없음은 확인 결과 %s 를 따로 보이고 대상 초안을 정하지 않는다', async (confirmation, text) => {
    const fake = installFakeOps(emptyList)
    fake.answers.set(DRAFTS, {
      status: 200,
      body: operation(null, { result: 'NO_RESPONSE', confirmation }),
    })
    open()
    await waitFor(() => expect(editor().value).toBe(DATA_V1))
    await click('초안 저장')
    const shown = await notice()
    expect(shown).toHaveTextContent(`초안 저장: ${text}`)
    expect(shown).not.toHaveTextContent('저장됨')
    expect(screen.getByRole('button', { name: '검증' })).toBeDisabled()
  })

  it('실행 호스트가 거부한 조작은 오류 이름과 사유를, 운영 서비스의 그 밖의 비정상은 결과 모름을 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answers.set(draftPath(7, 'mock-run'), {
      status: 200,
      body: operation(null, {
        result: 'REJECTED',
        rejection: { status: 404, error: 'DRAFT_NOT_FOUND', detail: '초안이 없다: 7' },
      }),
    })
    fake.answers.set(draftPath(7, 'validate'), { status: 500, body: {} })
    await openDraft(fake)
    await click('모의 실행')
    expect(await notice()).toHaveTextContent('초안 7 모의 실행: 실행 호스트가 거부함(없는 초안)초안이 없다: 7')
    await click('검증')
    await waitFor(async () =>
      expect(await notice()).toHaveTextContent('초안 7 검증: 결과 모름(운영 서비스 응답 500)'),
    )
  })

  it('편집기를 고치면 저장한 초안이 대상이 아니고 다시 저장해야 검증·모의 실행·활성화한다', async () => {
    const fake = installFakeOps(emptyList)
    await openDraft(fake)
    expect(screen.getByRole('button', { name: '검증' })).toBeEnabled()
    await userEvent.type(editor(), 'x')
    expect(
      screen.getByText('편집기 내용이 초안 7 의 내용과 다릅니다. 검증하려면 초안을 다시 저장하십시오'),
    ).toBeInTheDocument()
    for (const name of ['검증', '모의 실행', '활성화']) {
      expect(screen.getByRole('button', { name })).toBeDisabled()
    }
  })

  it('실행 호스트가 503 이면 임무 버전은 직전 값과 불통을 보인다', async () => {
    const fake = installFakeOps(emptyList)
    const v1 = versionRow()
    fake.mission = missionOverview({ active: { version: 1, source: 'DATA', detail: v1 }, versions: [v1] })
    const { rerender } = open()
    const head = screen.getByRole('region', { name: '임무 PrepareSequencedRack' })
    await waitFor(() => expect(field(head, '활성 버전')).toBe('버전 1'))
    fake.failing.add(MISSION_PATH)
    // 세션이 바뀌면 다시 읽는다. 주기(5초)를 기다리지 않는다.
    rerender(<MissionsArea session={{ ...engineer }} onChanged={() => undefined} />)
    expect(
      await within(head).findByText(
        '직전 값입니다. 실행 호스트 불통: 실행 호스트가 답하지 않는다: 응답 없음: ConnectException',
      ),
    ).toBeInTheDocument()
    expect(field(head, '활성 버전')).toBe('버전 1')
  })
})
```

`ui/src/components/MissionsArea.tsx`:

```tsx
import { useEffect, useState } from 'react'
import {
  EDITABLE_WORK_MASTER,
  activateMissionDraft,
  fetchMission,
  fetchMissionTemplates,
  mockRunMissionDraft,
  saveMissionDraft,
  validateMissionDraft,
} from '../api'
import type { DraftView, MissionOverview, MissionTemplate, MissionTemplates, Session } from '../api'
import { TEMPLATE_LABEL, mockRunVerdict } from '../labels'
import { POLL_MS } from '../poll'
import type { HostRead } from './ExecutionList'
import { MissionNotice } from './MissionNotice'
import type { MissionSent } from './MissionNotice'
import { MockRunReport } from './MockRunReport'

interface Props {
  session: Session
  /** 조작이 끝나면 부른다. 조작 기록을 다시 읽는다. */
  onChanged: () => void
}

/** 검증·모의 실행·활성화의 대상. 저장한 그 글자이고, 편집기가 그 글자와 다르면 대상이 아니다. */
interface Target {
  draftId: number
  definition: string
}

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

/**
 * «임무·정책» 영역(S3b 스펙 §8). PrepareSequencedRack 하나의 활성 버전, 버전 이력, 초안과 그 마지막 모의 실행을 보이고, 엔지니어
 * 모드에서 정의 JSON 을 편집해 초안 저장, 검증, 모의 실행, 활성화한다. 초안은 자유롭고 활성화만 관문이다.
 *
 * 임무 개요는 실행 호스트를 거친다. 그래서 App 의 다섯 조회와 따로, 이 영역이 열려 있을 때만 읽는다(S3a 스펙 §9.3). 못 읽으면
 * 직전 값을 지우지 않고 불통을 표시한다(S3b 스펙 §9).
 *
 * 편집기의 처음 내용은 활성 버전의 정의이고, 활성 버전이 코드 정의면 첫 템플릿(데이터 정의)이다. 손대기 전에는 그 값을 따른다.
 * 편집기는 textarea 이고 정의의 옳고 그름은 실행 호스트가 검증한다(S3b 스펙 T4). 임무 개요를 읽기 전에는 편집기의 처음 내용을
 * 모르므로(빈 글자) 초안 저장을 막는다.
 *
 * 활성화 사유는 활성화가 섰을 때(`ACTIVATED`)만 지운다. 거부·모의 실행 없음·모름·응답 없음이면 그대로 두어 고쳐 다시 보낼 수 있게
 * 한다.
 */
export function MissionsArea({ session, onChanged }: Props) {
  const workMasterId = EDITABLE_WORK_MASTER
  const [tick, setTick] = useState(0)
  const [overview, setOverview] = useState<HostRead<MissionOverview>>({ value: null, error: null })
  const [templates, setTemplates] = useState<HostRead<MissionTemplates>>({ value: null, error: null })
  const [edited, setEdited] = useState<string | null>(null)
  const [target, setTarget] = useState<Target | null>(null)
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<MissionSent | null>(null)

  useEffect(() => {
    const timer = setInterval(() => setTick((value) => value + 1), POLL_MS)
    return () => clearInterval(timer)
  }, [])

  useEffect(() => {
    let alive = true
    fetchMission(session, workMasterId)
      .then((value) => {
        if (alive) setOverview({ value, error: null })
      })
      .catch((error: unknown) => {
        if (alive) setOverview((previous) => ({ ...previous, error: message(error) }))
      })
    return () => {
      alive = false
    }
  }, [session, tick, workMasterId])

  // 템플릿은 바뀌지 않는다. 한 번 읽으면 다시 읽지 않고, 못 읽었으면 주기마다 다시 묻는다.
  const templatesKnown = templates.value !== null
  useEffect(() => {
    if (templatesKnown) return
    let alive = true
    fetchMissionTemplates(session, workMasterId)
      .then((value) => {
        if (alive) setTemplates({ value, error: null })
      })
      .catch((error: unknown) => {
        if (alive) setTemplates((previous) => ({ ...previous, error: message(error) }))
      })
    return () => {
      alive = false
    }
  }, [session, tick, workMasterId, templatesKnown])

  // 운영 서비스가 다른 모양을 주면(예: 시험 대역의 빈 배열) 칸이 없다. 없는 칸도 모름이다.
  const view = overview.value
  const known =
    view !== null && view.active != null && Array.isArray(view.versions) && Array.isArray(view.drafts) ? view : null
  const templateList = Array.isArray(templates.value?.templates) ? templates.value.templates : null
  const activeDefinition = known?.active.detail?.definition ?? null
  const initial = known === null ? null : (activeDefinition ?? templateList?.[0]?.definition ?? null)
  const text = edited ?? initial ?? ''
  const ready = target !== null && target.definition === text
  const engineer = session.mode === 'engineer'

  const run = (sent: Promise<MissionSent>) => {
    setBusy(true)
    setProblem(null)
    sent
      .then((next) => {
        setLast(next)
        if (next.op === 'save' && next.sent.kind === 'outcome') {
          const draft = next.sent.outcome.outcome?.draft
          if (draft != null) setTarget({ draftId: draft.draftId, definition: draft.definition })
        }
        if (next.op === 'activate' && next.sent.kind === 'outcome' && next.sent.outcome.outcome?.result === 'ACTIVATED') {
          setReason('')
        }
      })
      .finally(() => {
        setBusy(false)
        setTick((value) => value + 1)
        onChanged()
      })
  }

  const save = () =>
    run(
      saveMissionDraft(session, workMasterId, text).then((sent) => ({ op: 'save' as const, what: '초안 저장', sent })),
    )

  const validate = (draftId: number) =>
    run(
      validateMissionDraft(session, workMasterId, draftId).then((sent) => ({
        op: 'validate' as const,
        what: `초안 ${draftId} 검증`,
        sent,
      })),
    )

  const mockRun = (draftId: number) =>
    run(
      mockRunMissionDraft(session, workMasterId, draftId).then((sent) => ({
        op: 'mockRun' as const,
        what: `초안 ${draftId} 모의 실행`,
        sent,
      })),
    )

  const activate = (draftId: number) => {
    if (reason.trim() === '') {
      setProblem('활성화 사유를 넣으십시오')
      return
    }
    run(
      activateMissionDraft(session, workMasterId, draftId, reason.trim()).then((sent) => ({
        op: 'activate' as const,
        what: `초안 ${draftId} 활성화`,
        sent,
      })),
    )
  }

  const open = (draft: DraftView) => {
    setEdited(draft.definition)
    setTarget({ draftId: draft.draftId, definition: draft.definition })
    setProblem(null)
  }

  const targetDraft = ready ? known?.drafts.find((draft) => draft.draftId === target.draftId) : undefined

  return (
    <>
      <section aria-label={`임무 ${workMasterId}`}>
        <h2>{workMasterId}</h2>
        {known === null ? (
          <p>모름: 임무 버전을 아직 읽지 못했습니다{overview.error !== null && ` (${overview.error})`}</p>
        ) : (
          <>
            {overview.error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {overview.error}</p>}
            <dl>
              <dt>활성 버전</dt>
              <dd>{known.active.version === null ? '코드 정의' : `버전 ${known.active.version}`}</dd>
              {known.active.detail !== null && (
                <>
                  <dt>활성화</dt>
                  <dd>
                    {known.active.detail.activatedBy} {known.active.detail.activatedAt}: {known.active.detail.reason}
                  </dd>
                </>
              )}
            </dl>
          </>
        )}
      </section>
      {engineer ? (
        <section aria-label="임무 편집">
          <h2>편집</h2>
          {last !== null && <MissionNotice last={last} />}
          <Templates
            templates={templateList}
            error={templates.error}
            busy={busy}
            onLoad={(definition) => {
              setEdited(definition)
              setProblem(null)
            }}
          />
          <div className="split">
            <label className="editor">
              임무 정의 JSON
              <textarea
                rows={24}
                spellCheck={false}
                value={text}
                onChange={(event) => setEdited(event.target.value)}
              />
            </label>
            <ActiveDefinition known={known} definition={activeDefinition} text={text} />
          </div>
          <p>
            {target === null
              ? '저장한 초안이 없습니다. 초안을 저장하면 검증·모의 실행·활성화할 수 있습니다'
              : ready
                ? `대상: 초안 ${target.draftId}, 마지막 모의 실행 ${
                    targetDraft?.lastMockRun != null ? mockRunVerdict(targetDraft.lastMockRun) : '없음'
                  }`
                : `편집기 내용이 초안 ${target.draftId} 의 내용과 다릅니다. 검증하려면 초안을 다시 저장하십시오`}
          </p>
          <div className="actions">
            <button type="button" disabled={busy || known === null} onClick={save}>
              초안 저장
            </button>
            <button type="button" disabled={busy || !ready} onClick={() => target && validate(target.draftId)}>
              검증
            </button>
            <button type="button" disabled={busy || !ready} onClick={() => target && mockRun(target.draftId)}>
              모의 실행
            </button>
            <label>
              활성화 사유
              <input value={reason} onChange={(event) => setReason(event.target.value)} />
            </label>
            <button type="button" disabled={busy || !ready} onClick={() => target && activate(target.draftId)}>
              활성화
            </button>
          </div>
          {problem !== null && <p role="alert">{problem}</p>}
        </section>
      ) : (
        <section aria-label="임무 편집">
          <p>임무 편집은 엔지니어 모드에서 합니다</p>
          <ActiveDefinition known={known} definition={activeDefinition} text={null} />
        </section>
      )}
      {known !== null && (
        <>
          <section aria-label="버전 이력">
            <h2>버전 이력</h2>
            {known.versions.length === 0 ? (
              <p>활성화한 버전이 없습니다. 코드 정의로 돕니다</p>
            ) : (
              <table aria-label="임무 버전 이력">
                <thead>
                  <tr>
                    <th>버전</th>
                    <th>초안</th>
                    <th>활성화한 사람</th>
                    <th>사유</th>
                    <th>활성화 시각</th>
                  </tr>
                </thead>
                <tbody>
                  {known.versions.map((version) => (
                    <tr
                      key={version.version}
                      className={version.version === known.active.version ? 'selected' : undefined}
                    >
                      <td>
                        버전 {version.version}
                        {version.version === known.active.version && ' (활성)'}
                      </td>
                      <td>초안 {version.draftId}</td>
                      <td>{version.activatedBy}</td>
                      <td>{version.reason}</td>
                      <td>{version.activatedAt}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
          <section aria-label="초안">
            <h2>초안</h2>
            {known.drafts.length === 0 ? (
              <p>저장한 초안이 없습니다</p>
            ) : (
              <table aria-label="임무 초안 목록">
                <thead>
                  <tr>
                    <th>초안</th>
                    <th>저장한 사람</th>
                    <th>저장 시각</th>
                    <th>마지막 모의 실행</th>
                    {engineer && <th>조작</th>}
                  </tr>
                </thead>
                <tbody>
                  {known.drafts.map((draft) => (
                    <tr key={draft.draftId} className={ready && target.draftId === draft.draftId ? 'selected' : undefined}>
                      <td>초안 {draft.draftId}</td>
                      <td>{draft.savedBy}</td>
                      <td>{draft.savedAt}</td>
                      <td>
                        {draft.lastMockRun === null ? (
                          '없음'
                        ) : (
                          <details>
                            <summary>
                              {mockRunVerdict(draft.lastMockRun)} {draft.lastMockRun.finishedAt}
                            </summary>
                            <MockRunReport run={draft.lastMockRun} />
                          </details>
                        )}
                      </td>
                      {engineer && (
                        <td>
                          <button type="button" disabled={busy} onClick={() => open(draft)}>
                            초안 {draft.draftId} 열기
                          </button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}
    </>
  )
}

/** 시작용 정의 둘을 편집기에 불러오는 버튼(S3b 스펙 §8). 불러오면 편집기 내용을 바꾸고 저장하지 않는다. */
function Templates({
  templates,
  error,
  busy,
  onLoad,
}: {
  templates: MissionTemplate[] | null
  error: string | null
  busy: boolean
  onLoad: (definition: string) => void
}) {
  if (templates === null) {
    return <p>모름: 템플릿을 아직 읽지 못했습니다{error !== null && ` (${error})`}</p>
  }
  return (
    <ul aria-label="템플릿">
      {templates.map((template) => (
        <li key={template.id}>
          <button type="button" disabled={busy} onClick={() => onLoad(template.definition)}>
            {TEMPLATE_LABEL[template.id] ?? template.title} 불러오기
          </button>{' '}
          {template.title}
        </li>
      ))}
    </ul>
  )
}

/**
 * 활성 버전의 정의. 편집기 옆에 두어 나란히 비교한다(S3b 스펙 T4). 코드 정의는 정의 JSON 이 없다. [text] 가 있으면 편집기 내용이
 * 활성 버전과 같은지 적는다.
 */
function ActiveDefinition({
  known,
  definition,
  text,
}: {
  known: MissionOverview | null
  definition: string | null
  text: string | null
}) {
  if (known === null) return <p>모름: 활성 버전을 아직 읽지 못했습니다</p>
  return (
    <div>
      <p>
        {known.active.version === null ? '활성: 코드 정의' : `활성: 버전 ${known.active.version}`}
        {text !== null && definition !== null && (text === definition ? ', 편집기와 같음' : ', 편집기와 다름')}
      </p>
      {definition === null ? (
        <p>코드 정의는 정의 JSON 이 없습니다</p>
      ) : (
        <pre aria-label="활성 버전 정의">{definition}</pre>
      )}
    </div>
  )
}
```

`ui/src/components/MockRunReport.tsx`:

```tsx
import type { MockRunView } from '../api'
import { mockRunVerdict } from '../labels'

/**
 * 모의 실행 하나의 결과(S3b 스펙 §8). 통과 여부, 실패 이유, 표본 작업 지시와 단위별 표다. 모의 실행은 이상적 현장에서 정의가 끝까지
 * 도는지를 볼 뿐 현장 사실을 보증하지 않는다(S3b 스펙 T8).
 */
export function MockRunReport({ run }: { run: MockRunView }) {
  const { result } = run
  return (
    <>
      <p>
        모의 실행 {run.mockRunId}: {mockRunVerdict(run)}
        {result.detail !== null && `. ${result.detail}`}
      </p>
      <dl>
        <dt>표본 작업 지시</dt>
        <dd>
          {result.sample === null
            ? '없음'
            : `${result.sample.jobOrderId}, 슬롯 ${result.sample.slots.join(', ')}, 자재 ${result.sample.material}, 요구 근거 ${result.sample.requiredEvidence}`}
        </dd>
        <dt>물리 상태</dt>
        <dd>{result.physicalState ?? '실행이 서지 않음'}</dd>
        <dt>가상 경과</dt>
        <dd>{result.virtualElapsedSeconds}초</dd>
      </dl>
      {result.units.length > 0 && (
        <table aria-label={`모의 실행 ${run.mockRunId} 단위`}>
          <thead>
            <tr>
              <th>단위</th>
              <th>경로</th>
              <th>스킬</th>
              <th>상태</th>
              <th>근거</th>
              <th>실패 분류</th>
            </tr>
          </thead>
          <tbody>
            {result.units.map((unit) => (
              <tr key={unit.unitId}>
                <td>{unit.unitId}</td>
                <td>{unit.route === 'SIGNAL' ? '설비 대기' : '기체'}</td>
                <td>{unit.skillType}</td>
                <td>{unit.state}</td>
                <td>{unit.reached}</td>
                <td>{unit.failureClass ?? '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
```

`ui/src/components/SignalNotice.tsx`:

```tsx
import type { Delivered, SignalWriteOutcome } from '../api'
import { kindLabel } from '../labels'

interface Props {
  /** 어느 신호를 어떻게 썼는지. 예: `rack_present 켜기` */
  what: string
  sent: Delivered<SignalWriteOutcome>
}

/**
 * 신호 조작의 결과(S3b JSON 계약 §10.8). 현장의 거부(안전 신호, 틀린 값, 모르는 신호)는 200 본문의 `rejection` 이다. 응답 없음은
 * 운영 서비스가 셀 대역을 다시 읽어 값으로 대조한 결과를 보인다. 확인되기 전에는 성공으로도 실패로도 보이지 않는다.
 */
export function SignalNotice({ what, sent }: Props) {
  return (
    <p role="status" aria-label="신호 조작 결과">
      {what}: {describe(sent)}
    </p>
  )
}

function describe(sent: Delivered<SignalWriteOutcome>): string {
  if (sent.kind === 'refused') return `보내지 않음(${kindLabel(sent.refusal.error)}). ${sent.refusal.detail}`
  if (sent.kind === 'unknown') return `결과 모름(${sent.cause}). 셀 대역 신호 표에서 확인하십시오`
  const { result, confirmation, signal, rejection } = sent.outcome
  if (result === 'SUCCEEDED') return `반영됨(값 ${signal?.value ?? sent.outcome.value})`
  if (result === 'REJECTED' && rejection !== null) {
    return `현장이 거부함(${kindLabel(rejection.error)}). ${rejection.detail}`
  }
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return '응답은 없었으나 다시 읽어 보니 그 값임'
    case 'CONFIRMED_NOT_APPLIED':
      return '응답 없음. 다시 읽어 보니 그 값이 아님. 다시 하려면 새로 누르십시오'
    default:
      return '반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 셀 대역 신호 표에서 확인하십시오'
  }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/task4.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/task4.patch"
```

```diff
diff --git a/ui/src/App.test.tsx b/ui/src/App.test.tsx
index a38d494..3e83558 100644
--- a/ui/src/App.test.tsx
+++ b/ui/src/App.test.tsx
@@ -43,19 +43,33 @@ function serve(view: RobotListView, records: OperationRecord[] = []) {
 describe('App', () => {
   afterEach(() => vi.unstubAllGlobals())
 
-  it('메뉴가 5영역이고 아직 닫힌 1영역은 다음 단계로 표시한다', () => {
+  it('메뉴가 5영역이고 모두 열려 있다', () => {
     serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
     render(<App />)
     const nav = screen.getByRole('navigation', { name: '영역' })
     expect(within(nav).getAllByRole('button').map((b) => b.textContent)).toEqual([
       '현장·자원',
       '로봇·연결',
-      '임무·정책 다음 단계',
+      '임무·정책',
       '운영',
       '이력',
     ])
   })
 
+  it('임무·정책 영역을 열 때만 임무 버전을 읽는다', async () => {
+    const calls = serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
+    render(<App />)
+    expect(await screen.findByText('선언된 기체가 없습니다')).toBeInTheDocument()
+    expect(calls.filter((call) => call.url.startsWith('/api/missions'))).toEqual([])
+    await userEvent.click(screen.getByRole('button', { name: '임무·정책' }))
+    await waitFor(() =>
+      expect(calls.map((call) => call.url)).toEqual(
+        expect.arrayContaining(['/api/missions/PrepareSequencedRack', '/api/missions/templates/PrepareSequencedRack']),
+      ),
+    )
+    expect(screen.getByRole('region', { name: '임무 PrepareSequencedRack' })).toBeInTheDocument()
+  })
+
   it('목록을 읽은 적이 없으면 없음이 아니라 모름을 보인다', async () => {
     serve({ registry: 'REGISTRY_SILENT', checkedAt: 't1', robots: null, robotsAsOf: null })
     render(<App />)
diff --git a/ui/src/App.tsx b/ui/src/App.tsx
index c3f4009..21e6e81 100644
--- a/ui/src/App.tsx
+++ b/ui/src/App.tsx
@@ -4,6 +4,7 @@ import type { AdapterListView, OperationRecord, ProfileListView, RobotListView,
 import { AREAS } from './areas'
 import type { AreaId } from './areas'
 import { HistoryArea } from './components/HistoryArea'
+import { MissionsArea } from './components/MissionsArea'
 import { ModeSwitch } from './components/ModeSwitch'
 import { OperationsArea } from './components/OperationsArea'
 import { RegistryBanner } from './components/RegistryBanner'
@@ -28,8 +29,8 @@ export default function App() {
   useEffect(() => {
     let alive = true
     // 실패해도 직전 값을 지우지 않는다. 대신 opsError 로 직전 값임을 표시한다(스펙 §9). 다섯 중 하나라도 못 읽으면 다섯 다
-    // 직전 값이다(P2·S1d 스펙 §8.1, S2 스펙 §7). 실행 목록·셀·배정 가능은 실행 호스트를 거치므로 여기 넣지 않는다. 호스트가
-    // 멈춰도 다섯이 직전 값이 되지 않게 «운영» 영역이 따로 읽는다(S3a 스펙 §9.3).
+    // 직전 값이다(P2·S1d 스펙 §8.1, S2 스펙 §7). 실행 목록·셀·배정 가능·임무 버전은 실행 호스트를 거치므로 여기 넣지 않는다.
+    // 호스트가 멈춰도 다섯이 직전 값이 되지 않게 «운영»·«임무·정책» 영역이 따로 읽는다(S3a 스펙 §9.3).
     const load = () => {
       Promise.all([
         fetchRobots(session),
@@ -100,6 +101,9 @@ export default function App() {
             onChanged={() => setTick((value) => value + 1)}
           />
         )}
+        {current.id === 'missions' && (
+          <MissionsArea session={session} onChanged={() => setTick((value) => value + 1)} />
+        )}
         {current.id === 'operations' && (
           <OperationsArea session={session} onChanged={() => setTick((value) => value + 1)} />
         )}
diff --git a/ui/src/api.ts b/ui/src/api.ts
index 1be30c1..01c8981 100644
--- a/ui/src/api.ts
+++ b/ui/src/api.ts
@@ -302,11 +302,18 @@ function sendDocument(path: string, session: Session, text: string): Promise<Sen
   })
 }
 
-async function deliver<T = OperationOutcome>(path: string, init: RequestInit): Promise<Delivered<T>> {
+/** 운영 서비스가 registry·실행 호스트에 보내기 전에 막는 상태 코드(스펙 §9). */
+const preRejected = (status: number) => status === 400 || status === 403
+
+async function deliver<T = OperationOutcome>(
+  path: string,
+  init: RequestInit,
+  refused: (status: number) => boolean = preRejected,
+): Promise<Delivered<T>> {
   try {
     const response = await fetch(path, init)
     if (response.ok) return { kind: 'outcome', outcome: (await response.json()) as T }
-    if (response.status === 400 || response.status === 403) {
+    if (refused(response.status)) {
       // 스프링이 직접 막은 400 의 본문에는 detail 이 없다. 그때도 사유 칸을 비우지 않는다.
       const refusal = (await response.json()) as Partial<PreRejection>
       const detail =
@@ -498,9 +505,27 @@ export interface CellPlace {
   observedAt: string | null
 }
 
-/** 운영 서비스의 `GET /api/cell`. `cell` 이 null 이면 실행 호스트가 셀 대역을 못 읽은 모름이다. */
+export type SignalKind = 'BOOLEAN' | 'TEXT'
+
+/**
+ * 셀 대역의 이름 있는 신호 하나(S3b JSON 계약 §1). 값은 종류와 상관없이 늘 문자열이다(`"true"`). 안전 신호는 현장이 쓰기를
+ * 거부한다(ADR 32). `observedAt` 이 null 이면 현장이 시각을 주지 않은 처음 값이다.
+ */
+export interface CellSignal {
+  name: string
+  location: string | null
+  kind: SignalKind
+  safety: boolean
+  value: string
+  observedAt: string | null
+}
+
+/**
+ * 운영 서비스의 `GET /api/cell`. `cell` 이 null 이면 실행 호스트가 셀 대역을 못 읽은 모름이다. `signals` 가 null 이면 셀 대역이
+ * 신호 목록을 싣지 않은 것이고 이것도 모름이다(S3b JSON 계약 §6).
+ */
 export interface CellView {
-  cell: { presentations: CellPlace[]; slots: CellPlace[] } | null
+  cell: { presentations: CellPlace[]; slots: CellPlace[]; signals: CellSignal[] | null } | null
 }
 
 /**
@@ -529,3 +554,205 @@ export const checkEligibility = (session: Session, form: JobOrderForm) =>
   postJobOrderForm<EligibilityView>('/api/job-orders/eligibility', session, form)
 export const submitJobOrder = (session: Session, form: JobOrderForm) =>
   postJobOrderForm<JobOrderOutcome>('/api/job-orders', session, form)
+
+/** 화면이 편집하는 임무. 작업 지시 폼과 실행 호스트가 두 임무로 고정이라 하나만 편집한다(S3b 스펙 T6). */
+export const EDITABLE_WORK_MASTER = 'PrepareSequencedRack'
+
+/** 모의 실행의 단위 하나(S3b JSON 계약 §4.5). `route` 가 `SIGNAL` 이면 설비 대기 단위다. */
+export interface MockRunUnit {
+  unitId: string
+  route: 'ROBOT' | 'SIGNAL'
+  skillType: string
+  state: string
+  reached: string
+  failureClass: string | null
+}
+
+/** 모의 실행 실패의 하위 범주(S3b JSON 계약 §4.5). 통과면 null 이다. */
+export type MockRunFailure = 'DEFINITION' | 'SUBMISSION_REJECTED' | 'NOT_SETTLED' | 'WALL_CLOCK_LIMIT' | 'EXECUTION_FAILED'
+
+/**
+ * 모의 실행 결과(S3b JSON 계약 §4.5). 가상 기체 하나와 이상적 현장으로 표본 작업 지시 하나를 끝까지 돌린 것이다. 실행이 서지
+ * 않았으면(`DEFINITION`·`SUBMISSION_REJECTED`) `physicalState` 가 null 이고 `units` 가 비었다.
+ */
+export interface MockRunResult {
+  passed: boolean
+  failure: MockRunFailure | null
+  detail: string | null
+  robotId: string
+  sample: { jobOrderId: string; requiredEvidence: string; slots: string[]; material: string; presentation: string } | null
+  physicalState: string | null
+  units: MockRunUnit[]
+  virtualElapsedSeconds: number
+  wallElapsedMillis: number
+}
+
+/** 모의 실행 한 행(S3b JSON 계약 §3.3). 시각은 DB 시각이다. */
+export interface MockRunView {
+  mockRunId: number
+  draftId: number
+  passed: boolean
+  result: MockRunResult
+  requestId: string
+  startedAt: string
+  finishedAt: string
+}
+
+/** 초안 한 행(S3b JSON 계약 §3.3). `definition` 은 저장한 글자 그대로이고 읽을 수 없는 문서일 수도 있다. */
+export interface DraftView {
+  draftId: number
+  workMasterId: string
+  definition: string
+  savedBy: string
+  requestId: string
+  savedAt: string
+  lastMockRun: MockRunView | null
+}
+
+/** 임무 버전 한 행(S3b JSON 계약 §3.3). 번호는 WorkMaster 마다 1부터다. */
+export interface VersionView {
+  workMasterId: string
+  version: number
+  draftId: number
+  definition: string
+  activatedBy: string
+  reason: string
+  requestId: string
+  activatedAt: string
+}
+
+/**
+ * 운영 서비스의 `GET /api/missions/{workMasterId}`(S3b JSON 계약 §4.1). 활성 버전이 없으면 `source` 가 `CODE` 이고 정의 JSON 이
+ * 없다(코드 정의). `versions` 는 높은 번호부터, `drafts` 는 최근 것부터다.
+ */
+export interface MissionOverview {
+  workMasterId: string
+  active: { version: number | null; source: 'CODE' | 'DATA'; detail: VersionView | null }
+  versions: VersionView[]
+  drafts: DraftView[]
+}
+
+/** 시작용 정의 하나(S3b JSON 계약 §4.2). */
+export interface MissionTemplate {
+  id: string
+  title: string
+  definition: string
+}
+
+export interface MissionTemplates {
+  workMasterId: string
+  templates: MissionTemplate[]
+}
+
+/**
+ * 판정 입력을 몰라 판정하지 않은 것(S3b JSON 계약 §3.2). 거부가 아니라 모름이다. `inputs` 는 `SIGNAL_SPEC`·`SITE_SKILLS`·
+ * `SAMPLE_ORDER` 이고 `detail` 은 화면용 한국어다.
+ */
+export interface InputUnknown {
+  inputs: string[]
+  robots: string[]
+  detail: string
+}
+
+/** 실행 호스트 판정의 공통 칸(S3b JSON 계약 §4.4~§4.6). 결과는 `result` 로 가린다. 거부 목록은 운영 서비스가 `findings` 로 옮긴다. */
+interface HostJudgment<R extends string> {
+  result: R
+  draftId: number
+  workMasterId: string
+  checkedAt: string
+  unknown: InputUnknown | null
+}
+
+export type HostValidation = HostJudgment<'PASSED' | 'REFUSED' | 'INPUT_UNKNOWN'>
+
+export interface HostMockRun extends HostJudgment<'PASSED' | 'FAILED' | 'REFUSED' | 'INPUT_UNKNOWN'> {
+  mockRun: MockRunView | null
+}
+
+export interface HostActivation extends HostJudgment<'ACTIVATED' | 'REFUSED' | 'MOCK_RUN_REQUIRED' | 'INPUT_UNKNOWN'> {
+  version: number | null
+  lastMockRun: MockRunView | null
+  activated: VersionView | null
+}
+
+/** 초안 저장의 호스트 본문(S3b JSON 계약 §4.3). */
+export interface DraftSaved {
+  draft: DraftView
+}
+
+/** 호스트가 4xx 로 막은 쓰기(S3b JSON 계약 §10.1). 200 본문에 실린다. */
+export interface HostRejection {
+  status: number
+  error: string
+  detail: string
+}
+
+/**
+ * 초안 저장·모의 실행·활성화의 200 응답(S3b JSON 계약 §10.4). `result` 는 조작 기록의 결과이고 호스트의 판단은 `outcome` 에
+ * 있다. 거부 카드는 호스트 결과가 `REFUSED` 일 때만 `findings` 에 온다.
+ */
+export interface MissionOperationOutcome<T> {
+  requestId: string
+  workMasterId: string
+  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
+  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
+  outcome: T | null
+  findings: Finding[]
+  rejection: HostRejection | null
+}
+
+/** 검증의 200 응답(S3b JSON 계약 §10.5). 검증은 조작이 아니라 요청 id·결과·확인이 없다. */
+export interface MissionValidationReply {
+  workMasterId: string
+  outcome: HostValidation
+  findings: Finding[]
+}
+
+/** 신호 조작의 200 응답(S3b JSON 계약 §10.8). 현장의 거부(안전 신호, 틀린 값)는 `rejection` 에 온다. */
+export interface SignalWriteOutcome {
+  requestId: string
+  name: string
+  value: string
+  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
+  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
+  signal: CellSignal | null
+  rejection: HostRejection | null
+}
+
+/**
+ * 임무·신호 조작의 사전 거부(S3b JSON 계약 §10.1). 400·403 에 더해, 시운전 완료 기체를 몰라 실행 호스트를 부르지 않은 503
+ * (`COMMISSIONED_ROBOTS_UNKNOWN`)과 검증이 넘기는 호스트 4xx·`HOST_SILENT` 도 `{error, detail}` 본문의 사전 거부다.
+ */
+const missionPreRejected = (status: number) => (status >= 400 && status < 500) || status === 503
+
+const postMission = <T>(path: string, session: Session, body: unknown) =>
+  deliver<T>(
+    path,
+    {
+      method: 'POST',
+      headers: { ...actorHeaders(session), 'Content-Type': 'application/json' },
+      body: JSON.stringify(body),
+    },
+    missionPreRejected,
+  )
+
+const missionPath = (workMasterId: string) => `/api/missions/${encodeURIComponent(workMasterId)}`
+const draftPath = (workMasterId: string, draftId: number) => `${missionPath(workMasterId)}/drafts/${draftId}`
+
+export const fetchMission = (session: Session, workMasterId: string) =>
+  getHostJson<MissionOverview>(missionPath(workMasterId), session)
+export const fetchMissionTemplates = (session: Session, workMasterId: string) =>
+  getHostJson<MissionTemplates>(`/api/missions/templates/${encodeURIComponent(workMasterId)}`, session)
+/** 편집기의 글자 그대로 보낸다. 초안은 자유롭다. 읽을 수 없는 문서도 저장되고 검증이 거부한다(S3b 스펙 §9). */
+export const saveMissionDraft = (session: Session, workMasterId: string, definition: string) =>
+  postMission<MissionOperationOutcome<DraftSaved>>(`${missionPath(workMasterId)}/drafts`, session, { definition })
+export const validateMissionDraft = (session: Session, workMasterId: string, draftId: number) =>
+  postMission<MissionValidationReply>(`${draftPath(workMasterId, draftId)}/validate`, session, {})
+export const mockRunMissionDraft = (session: Session, workMasterId: string, draftId: number) =>
+  postMission<MissionOperationOutcome<HostMockRun>>(`${draftPath(workMasterId, draftId)}/mock-run`, session, {})
+export const activateMissionDraft = (session: Session, workMasterId: string, draftId: number, reason: string) =>
+  postMission<MissionOperationOutcome<HostActivation>>(`${draftPath(workMasterId, draftId)}/activate`, session, {
+    reason,
+  })
+export const writeCellSignal = (session: Session, name: string, value: string) =>
+  postMission<SignalWriteOutcome>(`/api/cell/signals/${encodeURIComponent(name)}`, session, { value })
diff --git a/ui/src/areas.ts b/ui/src/areas.ts
index abbb8ef..12050b4 100644
--- a/ui/src/areas.ts
+++ b/ui/src/areas.ts
@@ -3,14 +3,14 @@ export type AreaId = 'site' | 'robots' | 'missions' | 'operations' | 'history'
 export interface Area {
   id: AreaId
   label: string
-  /** 지금 동작하는 영역. 나머지는 다음 단계로 표시한다(스펙 §8). 현장·자원은 S2, 운영은 S3a 에서 열었다. */
+  /** 지금 동작하는 영역. 나머지는 다음 단계로 표시한다(스펙 §8). 현장·자원은 S2, 운영은 S3a, 임무·정책은 S3b 에서 열었다. */
   ready: boolean
 }
 
 export const AREAS: readonly Area[] = [
   { id: 'site', label: '현장·자원', ready: true },
   { id: 'robots', label: '로봇·연결', ready: true },
-  { id: 'missions', label: '임무·정책', ready: false },
+  { id: 'missions', label: '임무·정책', ready: true },
   { id: 'operations', label: '운영', ready: true },
   { id: 'history', label: '이력', ready: true },
 ]
diff --git a/ui/src/components/CellBand.tsx b/ui/src/components/CellBand.tsx
index cbd57c0..0e2b440 100644
--- a/ui/src/components/CellBand.tsx
+++ b/ui/src/components/CellBand.tsx
@@ -1,15 +1,20 @@
-import type { CellPlace, CellView } from '../api'
+import type { CellPlace, CellSignal, CellView } from '../api'
 import type { HostRead } from './ExecutionList'
 
 interface Props {
   read: HostRead<CellView>
+  busy: boolean
+  /** 신호 하나를 그 값으로 쓴다. 결과 알림과 다시 읽기는 부르는 쪽이 한다. */
+  onWrite: (name: string, value: string) => void
 }
 
 /**
  * 셀 대역 표시(S3a 스펙 §6.3·§9.2). 슬롯은 기체가 보고한 배치에서 채우므로 독립 설비 확인이 아니다. 그래서 «셀» 이 아니라
  * «셀 대역» 이라고 적는다. 실행 호스트가 셀 대역을 못 읽으면(`cell` null) 비어 있는 셀로 접지 않고 모름이다.
+ *
+ * 이름 있는 신호는 따로 표로 보인다(S3b 스펙 §8). 사람이 PLC 역할을 하는 정상 조작이라 두 모드 모두 켜고 끈다(결정 3).
  */
-export function CellBand({ read }: Props) {
+export function CellBand({ read, busy, onWrite }: Props) {
   const { value, error } = read
   return (
     <>
@@ -20,27 +25,33 @@ export function CellBand({ read }: Props) {
         <>
           {error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {error}</p>}
           {value.cell === null ? (
-            <p>모름: 실행 호스트가 셀 대역을 읽지 못했습니다</p>
+            <>
+              <p>모름: 실행 호스트가 셀 대역을 읽지 못했습니다</p>
+              <p>모름: 신호 값을 읽지 못했습니다</p>
+            </>
           ) : (
-            <table aria-label="셀 대역 자리">
-              <thead>
-                <tr>
-                  <th>자리</th>
-                  <th>종류</th>
-                  <th>점유</th>
-                  <th>자재</th>
-                  <th>관측 시각</th>
-                </tr>
-              </thead>
-              <tbody>
-                {value.cell.presentations.map((place) => (
-                  <Place key={place.id} place={place} kind="제시 자리" />
-                ))}
-                {value.cell.slots.map((place) => (
-                  <Place key={place.id} place={place} kind="슬롯" />
-                ))}
-              </tbody>
-            </table>
+            <>
+              <table aria-label="셀 대역 자리">
+                <thead>
+                  <tr>
+                    <th>자리</th>
+                    <th>종류</th>
+                    <th>점유</th>
+                    <th>자재</th>
+                    <th>관측 시각</th>
+                  </tr>
+                </thead>
+                <tbody>
+                  {value.cell.presentations.map((place) => (
+                    <Place key={place.id} place={place} kind="제시 자리" />
+                  ))}
+                  {value.cell.slots.map((place) => (
+                    <Place key={place.id} place={place} kind="슬롯" />
+                  ))}
+                </tbody>
+              </table>
+              <Signals signals={value.cell.signals} busy={busy} onWrite={onWrite} />
+            </>
           )}
         </>
       )}
@@ -48,6 +59,65 @@ export function CellBand({ read }: Props) {
   )
 }
 
+/**
+ * 신호 표(S3b 스펙 §8). 안전이 아닌 BOOLEAN 신호만 켜기·끄기를 둔다. 안전 신호는 값만 보인다. 실제 안전 PLC 를 소프트웨어에서
+ * 쓸 수 없는 것과 같게 쓰기는 현장 대역이 거부하므로(ADR 32) 화면도 버튼을 두지 않는다. 신호 목록이 없으면 신호가 없는 것으로
+ * 접지 않고 모름이다(S3b JSON 계약 §6).
+ */
+function Signals({ signals, busy, onWrite }: { signals: CellSignal[] | null } & Omit<Props, 'read'>) {
+  if (signals === null) return <p>모름: 셀 대역이 신호 목록을 싣지 않았습니다</p>
+  if (signals.length === 0) return <p>셀 대역에 신호가 없습니다</p>
+  return (
+    <table aria-label="셀 대역 신호">
+      <thead>
+        <tr>
+          <th>신호</th>
+          <th>종류</th>
+          <th>값</th>
+          <th>관측 시각</th>
+          <th>조작</th>
+        </tr>
+      </thead>
+      <tbody>
+        {signals.map((signal) => (
+          <tr key={signal.name}>
+            <td>{signal.name}</td>
+            <td>{signal.kind}</td>
+            <td>{signal.value}</td>
+            <td>{signal.observedAt ?? '-'}</td>
+            <td>
+              {signal.safety ? (
+                '안전 신호(값만 봅니다)'
+              ) : signal.kind === 'BOOLEAN' ? (
+                <>
+                  <button
+                    type="button"
+                    aria-label={`${signal.name} 켜기`}
+                    disabled={busy}
+                    onClick={() => onWrite(signal.name, 'true')}
+                  >
+                    켜기
+                  </button>
+                  <button
+                    type="button"
+                    aria-label={`${signal.name} 끄기`}
+                    disabled={busy}
+                    onClick={() => onWrite(signal.name, 'false')}
+                  >
+                    끄기
+                  </button>
+                </>
+              ) : (
+                '-'
+              )}
+            </td>
+          </tr>
+        ))}
+      </tbody>
+    </table>
+  )
+}
+
 function Place({ place, kind }: { place: CellPlace; kind: string }) {
   return (
     <tr>
diff --git a/ui/src/components/OperationsArea.test.tsx b/ui/src/components/OperationsArea.test.tsx
index 4a388c7..5447ecd 100644
--- a/ui/src/components/OperationsArea.test.tsx
+++ b/ui/src/components/OperationsArea.test.tsx
@@ -2,9 +2,16 @@ import { act, fireEvent, render, screen, waitFor, within } from '@testing-librar
 import userEvent from '@testing-library/user-event'
 import { afterEach, describe, expect, it, vi } from 'vitest'
 import App from '../App'
-import type { Execution, HostEligibility, JobOrderOutcome, RobotEligibility, Session } from '../api'
-import { ELIGIBILITY_DEBOUNCE_MS, POLL_MS } from '../poll'
-import { eligibilityView, executionsView, installFakeOps } from '../testing/fakeOps'
+import type {
+  Execution,
+  HostEligibility,
+  JobOrderOutcome,
+  RobotEligibility,
+  Session,
+  SignalWriteOutcome,
+} from '../api'
+import { ELIGIBILITY_DEBOUNCE_MS, POLL_MS, SIGNAL_SETTLE_MS } from '../poll'
+import { cellView, eligibilityView, executionsView, installFakeOps, standardSignals } from '../testing/fakeOps'
 import type { FakeOps } from '../testing/fakeOps'
 import { OperationsArea } from './OperationsArea'
 
@@ -83,6 +90,22 @@ function submitted(partial: Partial<JobOrderOutcome> = {}): JobOrderOutcome {
   }
 }
 
+/** 신호 조작 200 본문(S3b JSON 계약 §10.8). 기본은 rack_present 를 켠 것이다. */
+function written(partial: Partial<SignalWriteOutcome> = {}): SignalWriteOutcome {
+  return {
+    requestId: '6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a',
+    name: 'rack_present',
+    value: 'true',
+    result: 'SUCCEEDED',
+    confirmation: null,
+    signal: { name: 'rack_present', location: 'RACK-204', kind: 'BOOLEAN', safety: false, value: 'true', observedAt: 't2' },
+    rejection: null,
+    ...partial,
+  }
+}
+
+const RACK_PRESENT = '/api/cell/signals/rack_present'
+
 /** 칸 이름(dt) 바로 뒤의 값(dd)을 읽는다. */
 function field(region: HTMLElement, name: string) {
   const term = within(region).getByText(name, { selector: 'dt' })
@@ -436,6 +459,142 @@ describe('운영 영역', () => {
     expect(screen.getByText('모름: 셀 대역을 읽지 못해 슬롯과 자재를 고를 수 없습니다')).toBeInTheDocument()
   })
 
+  it('셀 대역 신호는 이름·값·관측 시각을 보이고 안전이 아닌 BOOLEAN 신호만 켜기·끄기가 있다', async () => {
+    const fake = installFakeOps(emptyList)
+    const signals = standardSignals()
+    signals[0] = { ...signals[0], value: 'true', observedAt: 't2' }
+    fake.cell = { cell: { ...cellView().cell!, signals } }
+    open()
+    const region = screen.getByRole('region', { name: '셀 대역' })
+    const table = await within(region).findByRole('table', { name: '셀 대역 신호' })
+    expect(within(table).getAllByRole('row').slice(1).map(cells)).toEqual([
+      ['rack_present', 'BOOLEAN', 'true', 't2', '켜기끄기'],
+      ['guard_closed', 'BOOLEAN', 'true', '-', '안전 신호(값만 봅니다)'],
+      ['lot_code', 'TEXT', 'LOT-0001', '-', '-'],
+    ])
+    expect(within(region).getAllByRole('button').map((button) => button.getAttribute('aria-label'))).toEqual([
+      'rack_present 켜기',
+      'rack_present 끄기',
+    ])
+  })
+
+  it('신호 켜기·끄기는 두 모드 모두 그 이름과 문자열 값으로 보내고 결과를 보인 뒤 셀 대역을 다시 읽는다', async () => {
+    const fake = installFakeOps(emptyList)
+    fake.answers.set(RACK_PRESENT, { status: 200, body: written() })
+    const { rerender } = open(operator)
+    const region = screen.getByRole('region', { name: '셀 대역' })
+    await userEvent.click(await within(region).findByRole('button', { name: 'rack_present 켜기' }))
+    expect(await within(region).findByRole('status', { name: '신호 조작 결과' })).toHaveTextContent(
+      'rack_present 켜기: 반영됨(값 true)',
+    )
+    let post = calls(fake, 'POST', RACK_PRESENT).at(-1)!
+    expect(post.body).toEqual({ value: 'true' })
+    expect(post.headers['X-Ops-Mode']).toBe('operator')
+    expect(post.headers['Content-Type']).toBe('application/json')
+    const reads = calls(fake, 'GET', '/api/cell').length
+    await waitFor(() => expect(calls(fake, 'GET', '/api/cell').length).toBeGreaterThanOrEqual(reads + 1))
+
+    fake.answers.set(RACK_PRESENT, {
+      status: 200,
+      body: written({ value: 'false', signal: { ...written().signal!, value: 'false' } }),
+    })
+    rerender(<OperationsArea session={engineer} onChanged={() => undefined} />)
+    await userEvent.click(within(region).getByRole('button', { name: 'rack_present 끄기' }))
+    await waitFor(() =>
+      expect(within(region).getByRole('status', { name: '신호 조작 결과' })).toHaveTextContent(
+        'rack_present 끄기: 반영됨(값 false)',
+      ),
+    )
+    post = calls(fake, 'POST', RACK_PRESENT).at(-1)!
+    expect(post.body).toEqual({ value: 'false' })
+    expect(post.headers['X-Ops-Mode']).toBe('engineer')
+  })
+
+  it.each([
+    [
+      written({
+        result: 'REJECTED',
+        signal: null,
+        rejection: { status: 400, error: 'SIGNAL_VALUE_INVALID', detail: 'BOOLEAN 신호 값은 true 나 false 다' },
+      }),
+      'rack_present 켜기: 현장이 거부함(신호 종류에 맞지 않는 값). BOOLEAN 신호 값은 true 나 false 다',
+    ],
+    [
+      written({ result: 'NO_RESPONSE', confirmation: 'CONFIRMED_APPLIED', signal: null }),
+      'rack_present 켜기: 응답은 없었으나 다시 읽어 보니 그 값임',
+    ],
+    [
+      written({ result: 'NO_RESPONSE', confirmation: 'CONFIRMED_NOT_APPLIED', signal: null }),
+      'rack_present 켜기: 응답 없음. 다시 읽어 보니 그 값이 아님',
+    ],
+    [
+      written({ result: 'NO_RESPONSE', confirmation: null, signal: null }),
+      'rack_present 켜기: 반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다',
+    ],
+  ])('현장의 거부와 응답 없음은 반영됨으로 보이지 않는다(%#)', async (body, text) => {
+    const fake = installFakeOps(emptyList)
+    fake.answers.set(RACK_PRESENT, { status: 200, body })
+    open()
+    await userEvent.click(await screen.findByRole('button', { name: 'rack_present 켜기' }))
+    const shown = await screen.findByRole('status', { name: '신호 조작 결과' })
+    expect(shown).toHaveTextContent(text)
+    expect(shown).not.toHaveTextContent('반영됨')
+  })
+
+  it('신호 조작 뒤 다시 읽기 타이머는 영역을 닫으면 치우고 닫은 뒤에 끝난 조작은 걸지 않는다', async () => {
+    // 시계는 손으로만 민다. 대역의 fetch 는 타이머를 쓰지 않으므로 act 로 약속만 흘려보낸다. 닫힌 영역의 타이머는 다시 읽기를
+    // 부르지 못하므로 읽기 수가 아니라 남은 타이머 수로 본다.
+    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
+    const fake = installFakeOps(emptyList)
+    fake.answers.set(RACK_PRESENT, { status: 200, body: written() })
+    const reads = () => calls(fake, 'GET', '/api/cell').length
+    const flush = async () => {
+      for (let round = 0; round < 5; round++) await act(async () => undefined)
+    }
+    const toggle = () => fireEvent.click(screen.getByRole('button', { name: 'rack_present 켜기' }))
+
+    // 열려 있으면 pump 가 돈 뒤 한 번 더 읽는다.
+    const first = open()
+    await flush()
+    toggle()
+    await flush()
+    expect(screen.getByRole('status', { name: '신호 조작 결과' })).toHaveTextContent('rack_present 켜기: 반영됨(값 true)')
+    const before = reads()
+    await act(async () => vi.advanceTimersByTime(SIGNAL_SETTLE_MS))
+    expect(reads()).toBe(before + 1)
+
+    // 다시 읽기가 걸린 뒤 닫으면 치운다.
+    toggle()
+    await flush()
+    first.unmount()
+    expect(vi.getTimerCount()).toBe(0)
+
+    // 조작이 끝나기 전에 닫으면 끝난 뒤에도 걸지 않는다.
+    const second = open()
+    await flush()
+    toggle()
+    second.unmount()
+    await flush()
+    expect(vi.getTimerCount()).toBe(0)
+    expect(calls(fake, 'POST', RACK_PRESENT)).toHaveLength(3)
+  })
+
+  it('셀 대역 스냅숏이 없거나 신호 목록이 없으면 신호를 모름으로 보이고 켜기·끄기가 없다', async () => {
+    const fake = installFakeOps(emptyList)
+    fake.cell = { cell: null }
+    const { rerender } = open()
+    const region = screen.getByRole('region', { name: '셀 대역' })
+    expect(await within(region).findByText('모름: 신호 값을 읽지 못했습니다')).toBeInTheDocument()
+    expect(within(region).queryByRole('table', { name: '셀 대역 신호' })).not.toBeInTheDocument()
+    expect(within(region).queryByRole('button')).not.toBeInTheDocument()
+
+    fake.cell = { cell: { ...cellView().cell!, signals: null } }
+    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
+    expect(await within(region).findByText('모름: 셀 대역이 신호 목록을 싣지 않았습니다')).toBeInTheDocument()
+    expect(within(region).queryByText('셀 대역에 신호가 없습니다')).not.toBeInTheDocument()
+    expect(within(region).queryByRole('button')).not.toBeInTheDocument()
+  })
+
   it('실행 호스트가 503 이면 실행 목록과 셀 대역은 직전 값과 불통을 보인다', async () => {
     const fake = installFakeOps(emptyList)
     fake.executions = executionsView({ executions: [execution()] })
diff --git a/ui/src/components/OperationsArea.tsx b/ui/src/components/OperationsArea.tsx
index 540655d..8a61b83 100644
--- a/ui/src/components/OperationsArea.tsx
+++ b/ui/src/components/OperationsArea.tsx
@@ -1,9 +1,17 @@
-import { useEffect, useState } from 'react'
-import { checkEligibility, fetchCell, fetchExecutions, submitJobOrder } from '../api'
-import type { CellView, Delivered, ExecutionsView, JobOrderForm, JobOrderOutcome, Session } from '../api'
+import { useEffect, useRef, useState } from 'react'
+import { checkEligibility, fetchCell, fetchExecutions, submitJobOrder, writeCellSignal } from '../api'
+import type {
+  CellView,
+  Delivered,
+  ExecutionsView,
+  JobOrderForm,
+  JobOrderOutcome,
+  Session,
+  SignalWriteOutcome,
+} from '../api'
 import { EMPTY_DRAFT, buildForm } from '../jobOrderDraft'
 import type { JobOrderDraft } from '../jobOrderDraft'
-import { ELIGIBILITY_DEBOUNCE_MS, POLL_MS } from '../poll'
+import { ELIGIBILITY_DEBOUNCE_MS, POLL_MS, SIGNAL_SETTLE_MS } from '../poll'
 import { CellBand } from './CellBand'
 import { EligibilityTable } from './EligibilityTable'
 import type { EligibilityRead } from './EligibilityTable'
@@ -11,6 +19,7 @@ import { ExecutionList } from './ExecutionList'
 import type { HostRead } from './ExecutionList'
 import { JobOrderFormView } from './JobOrderFormView'
 import { JobOrderNotice } from './JobOrderNotice'
+import { SignalNotice } from './SignalNotice'
 
 interface Props {
   session: Session
@@ -23,7 +32,7 @@ const NO_ELIGIBILITY: EligibilityRead = { view: null, refusal: null, error: null
 const message = (error: unknown) => (error instanceof Error ? error.message : String(error))
 
 /**
- * «운영» 영역(S3a 스펙 §9). 작업 지시 폼, 기체별 배정 가능 표, 실행 목록, 셀 대역 표시.
+ * «운영» 영역(S3a 스펙 §9). 작업 지시 폼, 기체별 배정 가능 표, 실행 목록, 셀 대역 표시와 신호 조작(S3b 스펙 §8).
  *
  * 실행 목록·셀·배정 가능은 실행 호스트를 거친다. 그래서 App 의 다섯 조회(`Promise.all`)와 따로, 이 영역이 열려 있을 때만
  * 읽는다(S3a 스펙 §9.3). 호스트가 멈춰도 다섯 조회가 직전 값이 되지 않게 하기 위해서다. 셋은 서로도 따로 실패하고, 못 읽으면
@@ -43,12 +52,26 @@ export function OperationsArea({ session, onChanged }: Props) {
   const [problem, setProblem] = useState<string | null>(null)
   const [busy, setBusy] = useState(false)
   const [last, setLast] = useState<{ what: string; sent: Delivered<JobOrderOutcome> } | null>(null)
+  const [signalBusy, setSignalBusy] = useState(false)
+  const [signalLast, setSignalLast] = useState<{ what: string; sent: Delivered<SignalWriteOutcome> } | null>(null)
 
   useEffect(() => {
     const timer = setInterval(() => setTick((value) => value + 1), POLL_MS)
     return () => clearInterval(timer)
   }, [])
 
+  // 신호 조작 뒤 pump 가 돈 다음에 한 번 더 읽는 타이머. 영역을 닫으면 치우고, 닫은 뒤에 끝난 조작은 타이머를 걸지 않는다.
+  const mounted = useRef(true)
+  const settle = useRef<ReturnType<typeof setTimeout> | null>(null)
+  useEffect(() => {
+    mounted.current = true
+    return () => {
+      mounted.current = false
+      if (settle.current !== null) clearTimeout(settle.current)
+      settle.current = null
+    }
+  }, [])
+
   useEffect(() => {
     let alive = true
     // 둘을 묶지 않는다. 셀 대역만 못 읽어도 실행 목록은 새 값이어야 한다.
@@ -118,6 +141,27 @@ export function OperationsArea({ session, onChanged }: Props) {
       })
   }
 
+  // 바뀐 값은 실행 호스트의 다음 pump 부터 보인다. 곧바로 한 번, pump 가 돈 뒤 한 번 더 읽는다. 조작이 잇따르면 마지막 조작
+  // 뒤의 한 번이 앞의 것을 대신한다.
+  const writeSignal = (name: string, value: string) => {
+    const what = `${name} ${value === 'true' ? '켜기' : '끄기'}`
+    setSignalBusy(true)
+    writeCellSignal(session, name, value)
+      .then((sent) => setSignalLast({ what, sent }))
+      .finally(() => {
+        setSignalBusy(false)
+        setTick((value) => value + 1)
+        if (settle.current !== null) clearTimeout(settle.current)
+        settle.current = mounted.current
+          ? setTimeout(() => {
+              settle.current = null
+              setTick((value) => value + 1)
+            }, SIGNAL_SETTLE_MS)
+          : null
+        onChanged()
+      })
+  }
+
   const submitted = last?.sent.kind === 'outcome' ? last.sent.outcome.jobOrderId : null
 
   return (
@@ -147,7 +191,8 @@ export function OperationsArea({ session, onChanged }: Props) {
       </section>
       <section aria-label="셀 대역">
         <h2>셀 대역</h2>
-        <CellBand read={cell} />
+        {signalLast !== null && <SignalNotice what={signalLast.what} sent={signalLast.sent} />}
+        <CellBand read={cell} busy={signalBusy} onWrite={writeSignal} />
       </section>
     </>
   )
diff --git a/ui/src/labels.ts b/ui/src/labels.ts
index 963693f..248d149 100644
--- a/ui/src/labels.ts
+++ b/ui/src/labels.ts
@@ -1,4 +1,13 @@
-import type { CommissioningState, Connection, HostSubmitResult, Owner, SkillFit, TestRequestState } from './api'
+import type {
+  CommissioningState,
+  Connection,
+  HostSubmitResult,
+  MockRunFailure,
+  MockRunView,
+  Owner,
+  SkillFit,
+  TestRequestState,
+} from './api'
 
 /** 화면에 보이는 이름. 값은 운영 서비스의 열거형 그대로 받고, 이름만 여기서 붙인다. */
 export const CONNECTION_LABEL: Record<Connection, string> = {
@@ -52,6 +61,26 @@ export const KIND_LABEL: Record<string, string> = {
   NO_ELIGIBLE_ROBOT: '배정 가능한 기체 없음',
   MODE_NOT_ALLOWED: '이 모드에서 할 수 없는 조작',
   ACTOR_REQUIRED: '행위자 없음',
+  // 임무 정의 거부 9종(S3b JSON 계약 §3.1). 호스트는 바닥 소유를 쥐지 않아 FLOOR_UNOWNED 를 내지 않지만 이름은 둔다.
+  UNREADABLE: '읽을 수 없는 정의',
+  DUPLICATE_NODE_ID: '노드 id 겹침',
+  DEADLINE_INVALID: '대기 기한 오류',
+  SIGNAL_NOT_IN_SPEC: '신호 사양에 없는 신호',
+  // 임무 거부(기대 값이 신호 종류에 맞지 않음)와 현장의 신호 쓰기 거부(BOOLEAN 에 틀린 값)가 같은 이름을 쓴다.
+  SIGNAL_VALUE_INVALID: '신호 종류에 맞지 않는 값',
+  FLOOR_UNOWNED: '바닥 소유 없음',
+  SKILL_NOT_IN_CONTRACT: '계약에 없는 스킬',
+  SKILL_NOT_ON_SITE: '현장에 없는 스킬',
+  SAFETY_SIGNAL_WAIT: '안전 신호 대기',
+  // 임무·신호 조작의 사전 거부와 호스트·현장의 4xx(S3b JSON 계약 §10).
+  MISSION_BAD_REQUEST: '임무 요청 오류',
+  REASON_REQUIRED: '사유 없음',
+  DRAFT_NOT_FOUND: '없는 초안',
+  REQUEST_ID_REUSED: '요청 id 재사용',
+  HOST_SILENT: '실행 호스트 불통',
+  SIGNAL_BAD_REQUEST: '신호 값 형식 오류',
+  UNKNOWN_SIGNAL: '모르는 신호',
+  SAFETY_SIGNAL_READ_ONLY: '안전 신호는 쓸 수 없음',
 }
 
 /** «시운전» 칸(P2·S1d 스펙 §8.5). 연결 칸과 합치지 않는다. */
@@ -93,3 +122,25 @@ export const SUBMIT_RESULT_LABEL: Record<HostSubmitResult, string> = {
   REJECTED: '거부됨',
   UNASSIGNED: '미배정',
 }
+
+/** 모의 실행 실패의 하위 범주(S3b JSON 계약 §4.5). */
+export const MOCK_RUN_FAILURE_LABEL: Record<MockRunFailure, string> = {
+  DEFINITION: '정의(같은 신호에 다른 기대 값)',
+  SUBMISSION_REJECTED: '표본 작업 지시 거부',
+  NOT_SETTLED: '정착하지 않음',
+  WALL_CLOCK_LIMIT: '실제 시간 상한 초과',
+  EXECUTION_FAILED: '실행 실패',
+}
+
+/** 모의 실행의 통과 여부 한 줄. 실패면 하위 범주를 붙인다(S3b JSON 계약 §4.5). */
+export function mockRunVerdict(run: MockRunView): string {
+  if (run.passed) return '통과'
+  const { failure } = run.result
+  return failure === null ? '실패' : `실패(${MOCK_RUN_FAILURE_LABEL[failure] ?? failure})`
+}
+
+/** 시작용 정의의 화면 이름(S3b JSON 계약 §4.2). 모르는 id 는 호스트가 준 제목을 쓴다. */
+export const TEMPLATE_LABEL: Record<string, string> = {
+  DATA_V1: '데이터 정의 템플릿',
+  ARRIVAL_WAIT: '랙 도착 대기 템플릿',
+}
diff --git a/ui/src/poll.ts b/ui/src/poll.ts
index ac54f2b..e4c295a 100644
--- a/ui/src/poll.ts
+++ b/ui/src/poll.ts
@@ -3,3 +3,9 @@ export const POLL_MS = 5000
 
 /** 작업 지시 폼이 바뀐 뒤 배정 가능을 묻기까지 기다리는 시간. 그 사이 다시 바뀌면 처음부터 다시 기다린다. */
 export const ELIGIBILITY_DEBOUNCE_MS = 300
+
+/**
+ * 신호 조작 뒤 셀 대역을 한 번 더 읽기까지 기다리는 시간. 바뀐 값은 실행 호스트의 다음 pump(250ms 주기)부터 보이므로 그보다 길게
+ * 둔다(S3b JSON 계약 §5).
+ */
+export const SIGNAL_SETTLE_MS = 500
diff --git a/ui/src/styles.css b/ui/src/styles.css
index 36916dd..c427a23 100644
--- a/ui/src/styles.css
+++ b/ui/src/styles.css
@@ -11,3 +11,7 @@ nav button[aria-current='page'] { font-weight: 700; }
 .link { background: none; border: none; color: #2b3f6b; text-decoration: underline; cursor: pointer; padding: 0; }
 .offscreen { color: #6f6f6f; }
 tr.selected { background: #e8ebf3; }
+.editor { display: flex; flex-direction: column; }
+.editor textarea, pre { font-family: ui-monospace, monospace; font-size: 13px; }
+pre { white-space: pre-wrap; border: 1px solid #ccc; padding: 8px; margin: 0; }
+.actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
diff --git a/ui/src/testing/fakeOps.ts b/ui/src/testing/fakeOps.ts
index 05ee923..0f0c193 100644
--- a/ui/src/testing/fakeOps.ts
+++ b/ui/src/testing/fakeOps.ts
@@ -2,10 +2,15 @@ import { vi } from 'vitest'
 import type {
   AdapterListView,
   Binding,
+  CellSignal,
   CellView,
+  DraftView,
   EligibilityView,
   ExecutionsView,
   Finding,
+  MissionOverview,
+  MissionTemplates,
+  MockRunView,
   OperationOutcome,
   PreRejection,
   ProfileListView,
@@ -15,6 +20,7 @@ import type {
   RobotListView,
   RobotView,
   TestRequestState,
+  VersionView,
 } from '../api'
 
 /** 운영 서비스 대역이 받은 요청 한 건. */
@@ -35,6 +41,9 @@ export interface FakeOps {
   /** 실행 호스트를 거치는 읽기(S3a). 운영 서비스가 호스트 본문을 그대로 넘기는 모양이다. */
   executions: ExecutionsView
   cell: CellView
+  /** 임무 개요와 템플릿(S3b). 운영 서비스가 호스트 본문을 그대로 넘기는 모양이다. */
+  mission: MissionOverview
+  templates: MissionTemplates
   /** `POST /api/job-orders/eligibility` 의 답. 폼 거부(400)를 만들려면 [eligibilityStatus] 와 본문을 바꾼다. */
   eligibility: EligibilityView | PreRejection
   eligibilityStatus: number
@@ -43,11 +52,16 @@ export interface FakeOps {
    * 서비스처럼 `HOST_SILENT` 본문을 싣는다. 배정 가능 판정은 POST 지만 읽기이므로 여기 들면 503 이다.
    */
   failing: Set<string>
+  /** 조작마다의 응답. 여기 든 경로의 POST 는 이 응답이고, 없으면 [answer] 다. */
+  answers: Map<string, { status: number; body: unknown }>
   answer: { status: number; body: unknown }
 }
 
-/** 실행 호스트를 거치는 경로. 503 일 때 운영 서비스가 `HOST_SILENT` 를 싣는다(S3a JSON 계약 §9.4). */
-const HOST_PATHS = new Set(['/api/executions', '/api/cell'])
+export const MISSION_PATH = '/api/missions/PrepareSequencedRack'
+export const TEMPLATES_PATH = '/api/missions/templates/PrepareSequencedRack'
+
+/** 실행 호스트를 거치는 경로. 503 일 때 운영 서비스가 `HOST_SILENT` 를 싣는다(S3a JSON 계약 §9.4, S3b JSON 계약 §10.3). */
+const HOST_PATHS = new Set(['/api/executions', '/api/cell', MISSION_PATH, TEMPLATES_PATH])
 const ELIGIBILITY_PATH = '/api/job-orders/eligibility'
 
 /** 실행 목록. 기본은 실행이 없는 호스트 인스턴스 하나다. */
@@ -55,14 +69,117 @@ export function executionsView(partial: Partial<ExecutionsView> = {}): Execution
   return { instanceId: 'mw-1', pumpedAt: 't1', executions: [], ...partial }
 }
 
-/** 셀 대역. 기본은 고정 픽스처(제시 자리 하나, 빈 슬롯 넷)다(S3a JSON 계약 §1). */
+/** 셀 대역 신호 셋. 고정 픽스처의 처음 값이다(S3b JSON 계약 §1). */
+export function standardSignals(): CellSignal[] {
+  return [
+    { name: 'rack_present', location: 'RACK-204', kind: 'BOOLEAN', safety: false, value: 'false', observedAt: null },
+    { name: 'guard_closed', location: null, kind: 'BOOLEAN', safety: true, value: 'true', observedAt: null },
+    { name: 'lot_code', location: null, kind: 'TEXT', safety: false, value: 'LOT-0001', observedAt: null },
+  ]
+}
+
+/** 셀 대역. 기본은 고정 픽스처(제시 자리 하나, 빈 슬롯 넷, 신호 셋)다(S3a JSON 계약 §1, S3b JSON 계약 §1). */
 export function cellView(): CellView {
   const slot = (id: string) => ({ id, occupied: false, material: null, observedAt: null })
   return {
     cell: {
       presentations: [{ id: 'SEQ-IN-02.BIN-A', occupied: true, material: 'ENGINE-COVER-A', observedAt: null }],
       slots: ['RACK-204.S01', 'RACK-204.S02', 'RACK-204.S03', 'RACK-204.S04'].map(slot),
+      signals: standardSignals(),
+    },
+  }
+}
+
+/** 템플릿 두 정의의 글자. 시험은 글자 그대로 오가는지만 본다. */
+export const DATA_V1 = '{"schemaVersion": 1, "workMasterId": "PrepareSequencedRack", "nodes": ["place"]}'
+export const ARRIVAL_WAIT =
+  '{"schemaVersion": 1, "workMasterId": "PrepareSequencedRack", "nodes": ["rack-arrival", "place"]}'
+
+/** 템플릿 둘(S3b JSON 계약 §4.2). */
+export function missionTemplates(): MissionTemplates {
+  return {
+    workMasterId: 'PrepareSequencedRack',
+    templates: [
+      { id: 'DATA_V1', title: '코드 PrepareSequencedRack 을 옮긴 데이터 정의', definition: DATA_V1 },
+      {
+        id: 'ARRIVAL_WAIT',
+        title: '랙 도착 대기(rack_present = true, 기한 120초, 기한 뒤 ABORTED)',
+        definition: ARRIVAL_WAIT,
+      },
+    ],
+  }
+}
+
+/** 임무 개요. 기본은 버전도 초안도 없는 코드 정의다(S3b JSON 계약 §4.1). */
+export function missionOverview(partial: Partial<MissionOverview> = {}): MissionOverview {
+  return {
+    workMasterId: 'PrepareSequencedRack',
+    active: { version: null, source: 'CODE', detail: null },
+    versions: [],
+    drafts: [],
+    ...partial,
+  }
+}
+
+/** 버전 한 행. 기본은 데이터 정의를 버전 1 로 올린 것이다. */
+export function versionRow(partial: Partial<VersionView> = {}): VersionView {
+  return {
+    workMasterId: 'PrepareSequencedRack',
+    version: 1,
+    draftId: 1,
+    definition: DATA_V1,
+    activatedBy: 'lee',
+    reason: '데이터 정의로 전환',
+    requestId: 'r-v1',
+    activatedAt: 't1',
+    ...partial,
+  }
+}
+
+/** 모의 실행 한 행. 기본은 단위 둘이 E2 로 완료되어 통과한 것이다. */
+export function mockRunRow(partial: Partial<MockRunView> = {}): MockRunView {
+  return {
+    mockRunId: 5,
+    draftId: 7,
+    passed: true,
+    result: {
+      passed: true,
+      failure: null,
+      detail: null,
+      robotId: 'mock-01',
+      sample: {
+        jobOrderId: 'MOCK-7',
+        requiredEvidence: 'E2',
+        slots: ['RACK-204.S01', 'RACK-204.S02'],
+        material: 'ENGINE-COVER-A',
+        presentation: 'SEQ-IN-02.BIN-A',
+      },
+      physicalState: 'PHYSICALLY_DONE',
+      units: [
+        { unitId: 'rack-arrival', route: 'SIGNAL', skillType: 'equipment_wait', state: 'DONE', reached: 'E2', failureClass: null },
+        { unitId: 'RACK-204.S01', route: 'ROBOT', skillType: 'pick_place', state: 'DONE', reached: 'E2', failureClass: null },
+      ],
+      virtualElapsedSeconds: 105,
+      wallElapsedMillis: 812,
     },
+    requestId: 'r-m5',
+    startedAt: 't2',
+    finishedAt: 't3',
+    ...partial,
+  }
+}
+
+/** 초안 한 행. 기본은 모의 실행이 없는 초안 7 이다. */
+export function draftRow(partial: Partial<DraftView> = {}): DraftView {
+  return {
+    draftId: 7,
+    workMasterId: 'PrepareSequencedRack',
+    definition: ARRIVAL_WAIT,
+    savedBy: 'local',
+    requestId: 'r-d7',
+    savedAt: 't1',
+    lastMockRun: null,
+    ...partial,
   }
 }
 
@@ -199,9 +316,12 @@ export function installFakeOps(
     settings,
     executions: executionsView(),
     cell: cellView(),
+    mission: missionOverview(),
+    templates: missionTemplates(),
     eligibility: eligibilityView(),
     eligibilityStatus: 200,
     failing: new Set(),
+    answers: new Map(),
     answer: { status: 200, body: outcome({}) },
   }
   vi.stubGlobal(
@@ -235,13 +355,18 @@ export function installFakeOps(
                     ? fake.executions
                     : url === '/api/cell'
                       ? fake.cell
-                      : []
+                      : url === MISSION_PATH
+                        ? fake.mission
+                        : url === TEMPLATES_PATH
+                          ? fake.templates
+                          : []
         return new Response(JSON.stringify(body), { status: 200 })
       }
       if (url === ELIGIBILITY_PATH) {
         return new Response(JSON.stringify(fake.eligibility), { status: fake.eligibilityStatus })
       }
-      return new Response(JSON.stringify(fake.answer.body), { status: fake.answer.status })
+      const answer = fake.answers.get(url) ?? fake.answer
+      return new Response(JSON.stringify(answer.body), { status: answer.status })
     }),
   )
   return fake
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && cd ui && npm ci && npm test && npm run lint && npx tsc -b && npm run build
```
Expected: vitest 99 통과(파일 8), lint·tsc·build 종료 0.

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && git add ui/src/components/MissionNotice.tsx ui/src/components/MissionsArea.test.tsx ui/src/components/MissionsArea.tsx ui/src/components/MockRunReport.tsx ui/src/components/SignalNotice.tsx ui/src/App.test.tsx ui/src/App.tsx ui/src/api.ts ui/src/areas.ts ui/src/components/CellBand.tsx ui/src/components/OperationsArea.test.tsx ui/src/components/OperationsArea.tsx ui/src/labels.ts ui/src/poll.ts ui/src/styles.css ui/src/testing/fakeOps.ts && git commit -F - <<'EOF'
feat(ui): 임무·정책 영역과 셀 대역 신호 조작 화면 추가

- 편집기·템플릿·초안 저장·검증·모의 실행·활성화, 거부 카드와 모름 표시, 버전 이력
- 셀 대역 신호 표와 켜기·끄기(안전 신호는 값만)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s3b-cmp.sh" ui/src/components/MissionNotice.tsx ui/src/components/MissionsArea.test.tsx ui/src/components/MissionsArea.tsx ui/src/components/MockRunReport.tsx ui/src/components/SignalNotice.tsx ui/src/App.test.tsx ui/src/App.tsx ui/src/api.ts ui/src/areas.ts ui/src/components/CellBand.tsx ui/src/components/OperationsArea.test.tsx ui/src/components/OperationsArea.tsx ui/src/labels.ts ui/src/poll.ts ui/src/styles.css ui/src/testing/fakeOps.ts
```
Expected: 16개 모두 `같음`.

### Task 5: 통합 시험

**Files:**
- Create: `e2e/src/test/kotlin/dev/picasso/ops/e2e/ExecutionDriver.kt`, `e2e/src/test/kotlin/dev/picasso/ops/e2e/MissionVersionTest.kt`
- Modify: `e2e/build.gradle.kts`, `e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt`, `e2e/src/test/kotlin/dev/picasso/ops/e2e/JobOrderTest.kt`

- [ ] **Step 1: 새 파일 2개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p e2e/src/test/kotlin/dev/picasso/ops/e2e && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/e2e/src/test/kotlin/dev/picasso/ops/e2e/ExecutionDriver.kt" e2e/src/test/kotlin/dev/picasso/ops/e2e/ExecutionDriver.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && mkdir -p e2e/src/test/kotlin/dev/picasso/ops/e2e && cp "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/files/e2e/src/test/kotlin/dev/picasso/ops/e2e/MissionVersionTest.kt" e2e/src/test/kotlin/dev/picasso/ops/e2e/MissionVersionTest.kt
```

`e2e/src/test/kotlin/dev/picasso/ops/e2e/ExecutionDriver.kt`:

```kotlin
package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.host.MissionHost
import java.time.Duration
import java.time.Instant

/**
 * 가상 시계를 밀어 실행을 정착시키는 공용 진행기(S3a 스펙 §11). `JobOrderTest` 와 `MissionVersionTest` 가 쓴다.
 *
 * 호스트 시계는 현장 시계다(`E2eStack`). [push] 로 가상 시계를 밀 때마다 호스트의 마지막 pump 시각(`pumpedAt`)이 민 뒤의 가상
 * 시각 이상이 될 때까지 기다리고, 한 주기를 더 기다린 뒤 상태를 읽는다. mimic 스트림 갱신은 gRPC 스레드로 비동기로 와서
 * `pumpedAt` 만으로는 그 pump 가 방금 민 전이를 봤다는 보장이 없기 때문이다. E2 마감(`doneAt + 15s`)은 단위를 끝낸 밀기
 * 뒤의 가상 시각부터 센다. 그래서 확인 중(VERIFYING)인 단위가 보이면 시계를 더 밀지 않고 실제 시간으로만 기다린다([drive]).
 */
class ExecutionDriver(private val stack: E2eStack) {

    fun executions(): JsonNode = stack.get("/api/executions")

    fun execution(executionId: String): JsonNode =
        checkNotNull(executions()["executions"].firstOrNull { it["executionId"].asText() == executionId }) { "실행이 없다: $executionId" }

    /** 가상 시계를 밀고, 호스트가 민 뒤의 시각에 pump 를 시작할 때까지 기다린 뒤 한 주기 더 기다린다. */
    fun push(by: Duration) {
        stack.site.advance(by)
        val target = stack.site.now()
        val deadline = Instant.now().plusSeconds(5)
        while (true) {
            val at = executions()["pumpedAt"]
            if (!at.isNull && !Instant.parse(at.asText()).isBefore(target)) break
            check(Instant.now().isBefore(deadline)) { "호스트 pump 가 $target 에 이르지 않았다: $at" }
            Thread.sleep(50)
        }
        Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
    }

    /**
     * 실행의 물리 상태가 정착할 때까지 [STEP] 씩 민다. 민 뒤 확인 중(VERIFYING)인 단위가 있으면 더 밀지 않고 [VERIFY_WAIT] 동안
     * 실제 시간으로 기다린다. 그 안에 정착하지 않으면(셀 신호가 없음) 다시 밀어 마감으로 간다.
     */
    fun drive(executionId: String): JsonNode {
        repeat(ROUNDS) {
            push(STEP)
            var seen = execution(executionId)
            val until = Instant.now().plus(VERIFY_WAIT)
            while (seen["units"].any { it["state"].asText() == "VERIFYING" } && Instant.now().isBefore(until)) {
                Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
                seen = execution(executionId)
            }
            if (seen["physicalState"].asText() in SETTLED) return seen
        }
        error("실행 $executionId 가 ${ROUNDS}번 밀어도 정착하지 않았다: ${execution(executionId)}")
    }

    companion object {
        /** 한 번에 미는 가상 시간. E2 마감 15초보다 짧아 확인 전에 마감을 넘기지 않는다. */
        val STEP: Duration = Duration.ofSeconds(5)

        /** 밀기 상한. 가장 긴 것이 `pick_place` 둘(45초 ±10%)이고, 설비 대기 기한(120초)을 넘겨 마감까지 갈 수 있어야 한다. */
        const val ROUNDS = 60

        /** 확인 중인 단위를 실제 시간으로 기다리는 상한. 셀 대역은 다음 pump(250ms) 에 읽힌다. */
        val VERIFY_WAIT: Duration = Duration.ofSeconds(3)

        /** 정착한 물리 상태(S3a JSON 계약 §5). */
        val SETTLED = setOf("PHYSICALLY_DONE", "UNVERIFIED", "FAILED", "ABORTED", "PARTIAL")
    }
}
```

`e2e/src/test/kotlin/dev/picasso/ops/e2e/MissionVersionTest.kt`:

```kotlin
package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import dev.picasso.ops.e2e.Commissioned.QUADRUPED
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
 * S3b 완료 판정의 통합 쪽(S3b 스펙 §3·§10). 두 기체를 시운전 완료로 만든 뒤 운영 서비스 REST 만으로 PrepareSequencedRack 의
 * 임무 버전을 다룬다. 신호 조작도 운영 서비스 REST 다.
 *
 * 순서가 있다. `pick_place` 를 가진 기체가 humanoid-01 하나이고 호스트 판정은 도는 실행이 있는 기체를 통과시키지 않으므로, 버전
 * 1 실행이 끝나야 둘째 작업 지시를 배정할 수 있다. 셀 대역은 슬롯을 비우지 않으므로 두 작업 지시가 슬롯 넷을 나눠 쓴다(S01·S02,
 * S03·S04).
 *
 * ## 실패 모드
 *
 * `pick_place` 의 실패 모드는 기체별 시드로 추첨된다. 현장은 시드 0 이고, 이 순서(humanoid-01 이 `pick_place` 둘을 두 번)에서는
 * 실패 모드가 나지 않는다(스파이크에서 확인). 모의 실행은 실패 모드를 뺀 별도 mimic 으로 돌아 현장 기체의 추첨을 바꾸지 않는다.
 *
 * ## 설비 대기 기한
 *
 * 버전 2 의 대기 단위는 기한 120초, 기한 뒤 ABORTED 다. 대기 단위가 시작된 뒤 신호를 켜기 전에는 가상 시계를 [WAIT_BEFORE_SIGNAL]
 * 만 민다(120초보다 훨씬 짧다). 신호 조작이 현장에 닿지 않으면 진행기가 기한을 넘겨 실행이 ABORTED 로 정착한다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MissionVersionTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver
        private val json = ObjectMapper()

        private const val WORK_MASTER = "PrepareSequencedRack"
        private const val MISSIONS = "/api/missions/$WORK_MASTER"
        private const val MATERIAL = "ENGINE-COVER-A"
        private const val PRESENTATION = "SEQ-IN-02.BIN-A"
        private val FIRST_SLOTS = listOf("RACK-204.S01", "RACK-204.S02")
        private val SECOND_SLOTS = listOf("RACK-204.S03", "RACK-204.S04")

        /** 신호를 켜기 전에 대기 단위가 기다리는 것을 보려고 미는 가상 시간. 기한 120초보다 훨씬 짧다. */
        private val WAIT_BEFORE_SIGNAL: Duration = Duration.ofSeconds(10)

        /** 템플릿 id → 정의 글자(S3b JSON 계약 §4.2). */
        private lateinit var templates: Map<String, String>

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
            driver = ExecutionDriver(stack)
            Commissioned.complete(stack)
            templates = stack.get("/api/missions/templates/$WORK_MASTER")["templates"]
                .associate { it["id"].asText() to it["definition"].asText() }
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }

        private fun rack(slots: List<String>): String =
            """{"workMasterId":"$WORK_MASTER","slots":[${slots.joinToString(",") { "\"$it\"" }}],""" +
                """"material":"$MATERIAL","presentation":"$PRESENTATION"}"""
    }

    @Test
    @Order(1)
    fun `데이터 정의를 초안 저장하고 검증하고 모의 실행을 통과시킨 뒤 사유를 적어 활성화하면 버전 1 이다`() {
        val before = stack.get(MISSIONS)
        assertEquals("CODE", before["active"]["source"].asText(), "$before")
        assertTrue(before["active"]["version"].isNull, "$before")

        val draftId = save(templates.getValue("DATA_V1"))

        val validated = engineer("$MISSIONS/drafts/$draftId/validate", "{}")
        assertEquals("PASSED", validated["outcome"]["result"].asText(), "$validated")
        assertEquals(0, validated["findings"].size(), "$validated")
        // 판정 입력은 현장의 실제 값이다. 신호 사양은 셀 대역 픽스처의 선언, 현장 스킬은 시운전 완료 기체 둘의 스킬 합이다.
        val inputs = validated["outcome"]["inputs"]
        assertEquals(listOf(HUMANOID, QUADRUPED), inputs["robotIds"].map { it.asText() }, "$inputs")
        assertEquals(listOf("rack_present", "guard_closed", "lot_code"), inputs["signals"].map { it["name"].asText() }, "$inputs")
        assertTrue(inputs["siteSkills"].any { it.asText() == "pick_place" }, "$inputs")

        val mocked = engineer("$MISSIONS/drafts/$draftId/mock-run", "{}")
        assertEquals("SUCCEEDED" to "PASSED", mocked["result"].asText() to mocked["outcome"]["result"].asText(), "$mocked")
        val run = mocked["outcome"]["mockRun"]["result"]
        assertEquals("PHYSICALLY_DONE", run["physicalState"].asText(), "$run")
        assertEquals(
            FIRST_SLOTS.map { Triple(it, "DONE", "E2") },
            run["units"].map { Triple(it["unitId"].asText(), it["state"].asText(), it["reached"].asText()) },
            "$run",
        )
        // 모의 실행은 별도 mimic 과 이상적 현장으로 돈다. 현장 셀 대역의 슬롯은 그대로 비어 있다.
        assertTrue(stack.get("/api/cell")["cell"]["slots"].none { it["occupied"].asBoolean() }, "${stack.get("/api/cell")}")

        val activated = engineer("$MISSIONS/drafts/$draftId/activate", """{"reason":"데이터 정의로 옮김"}""")
        assertEquals("SUCCEEDED" to "ACTIVATED", activated["result"].asText() to activated["outcome"]["result"].asText(), "$activated")
        assertEquals(1, activated["outcome"]["version"].asInt(), "$activated")

        val after = stack.get(MISSIONS)
        assertEquals("DATA" to 1, after["active"]["source"].asText() to after["active"]["version"].asInt(), "$after")
        val version = after["versions"].single()
        assertEquals(
            listOf(draftId.toString(), "kim", "데이터 정의로 옮김"),
            listOf(version["draftId"].asText(), version["activatedBy"].asText(), version["reason"].asText()),
            "$version",
        )
        val logged = stack.get("/api/operations").single { json.readTree(it["request"].asText())["op"].asText() == "ACTIVATE_MISSION_VERSION" }
        assertEquals(
            listOf(WORK_MASTER, "SUCCEEDED", "데이터 정의로 옮김"),
            listOf(logged["target"].asText(), logged["result"].asText(), logged["reason"].asText()),
            "$logged",
        )
    }

    @Test
    @Order(2)
    fun `신호 사양에 없는 신호를 기다리는 초안의 활성화는 SIGNAL_NOT_IN_SPEC 거부 카드로 막히고 활성 버전은 그대로다`() {
        val arrival = templates.getValue("ARRIVAL_WAIT")
        val wrong = "\"signal\": \"rack_present\""
        assertEquals(1, arrival.split(wrong).size - 1, arrival)
        val draftId = save(arrival.replace(wrong, "\"signal\": \"rack_ready\""))

        val refused = engineer("$MISSIONS/drafts/$draftId/activate", """{"reason":"잘못된 신호 이름"}""")
        assertEquals("REJECTED" to "REFUSED", refused["result"].asText() to refused["outcome"]["result"].asText(), "$refused")
        val finding = refused["findings"].single()
        assertEquals("SIGNAL_NOT_IN_SPEC", finding["kind"].asText(), "$finding")
        assertEquals("노드 rack-arrival: rack_ready", finding["observed"].asText(), "$finding")
        assertTrue("rack_present" in finding["expected"].asText(), "$finding")
        // 해결 담당은 화면 안의 엔지니어이고 바로 갈 작업이 실린다. 대상은 기체가 아니라 노드라 널이다.
        assertEquals("ENGINEER" to true, finding["owner"].asText() to finding["inScreen"].asBoolean(), "$finding")
        assertEquals("신호 이름을 고치거나 신호 사양에 더한다", finding["action"].asText(), "$finding")
        assertTrue(finding["target"].isNull, "$finding")
        assertEquals(refused["outcome"]["checkedAt"].asText(), finding["checkedAt"].asText(), "$finding")

        val after = stack.get(MISSIONS)
        assertEquals(1, after["active"]["version"].asInt(), "$after")
        assertEquals(listOf(1), after["versions"].map { it["version"].asInt() }, "$after")
    }

    @Test
    @Order(3)
    fun `버전 1 로 도는 실행 중에 버전 2 를 활성화하면 옛 실행은 버전 1 로 끝나고 새 작업 지시는 랙 도착을 기다렸다가 신호를 켜면 끝난다`() {
        // 버전 1 로 작업 지시를 내고 한 번 밀어 실행이 돌게 한다.
        val first = submit(rack(FIRST_SLOTS))
        driver.push(ExecutionDriver.STEP)
        val running = driver.execution(first)
        assertEquals(1, running["missionVersion"].asInt(), "$running")
        assertTrue(running["physicalState"].asText() !in ExecutionDriver.SETTLED, "$running")

        // 도는 중에 버전 2(랙 도착 대기, 120초, ABORTED)를 모의 실행하고 활성화한다.
        val draftId = save(templates.getValue("ARRIVAL_WAIT"))
        val mocked = engineer("$MISSIONS/drafts/$draftId/mock-run", "{}")
        assertEquals("PASSED", mocked["outcome"]["result"].asText(), "$mocked")
        val mockUnits = mocked["outcome"]["mockRun"]["result"]["units"]
        assertEquals(
            listOf("rack-arrival" to "SIGNAL") + FIRST_SLOTS.map { it to "ROBOT" },
            mockUnits.map { it["unitId"].asText() to it["route"].asText() },
            "$mockUnits",
        )
        val activated = engineer("$MISSIONS/drafts/$draftId/activate", """{"reason":"랙 도착 대기 도입"}""")
        assertEquals("ACTIVATED" to 2, activated["outcome"]["result"].asText() to activated["outcome"]["version"].asInt(), "$activated")
        val stillRunning = driver.execution(first)
        assertTrue(stillRunning["physicalState"].asText() !in ExecutionDriver.SETTLED, "활성화 때 옛 실행이 돌고 있어야 한다: $stillRunning")

        // 옛 실행은 대기 단위 없이 버전 1 로 끝난다.
        val old = driver.drive(first)
        assertEquals("PHYSICALLY_DONE" to 1, old["physicalState"].asText() to old["missionVersion"].asInt(), "$old")
        assertEquals(
            FIRST_SLOTS.map { Triple(it, "DONE", "E2") },
            old["units"].map { Triple(it["unitId"].asText(), it["state"].asText(), it["reached"].asText()) },
            "$old",
        )

        // 새 작업 지시는 버전 2 로 서고 랙 도착을 기다린다. 기한 120초보다 짧게만 밀어 기다리는 것을 본다.
        val second = submit(rack(SECOND_SLOTS))
        val waitFrom = stack.site.now()
        var waiting = driver.execution(second)
        while (Duration.between(waitFrom, stack.site.now()) < WAIT_BEFORE_SIGNAL) {
            driver.push(ExecutionDriver.STEP)
            waiting = driver.execution(second)
        }
        assertEquals(2, waiting["missionVersion"].asInt(), "$waiting")
        assertEquals(
            listOf(Triple("rack-arrival", "equipment_wait", "RUNNING")) + SECOND_SLOTS.map { Triple(it, "pick_place", "PENDING") },
            waiting["units"].map { Triple(it["unitId"].asText(), it["skillType"].asText(), it["state"].asText()) },
            "$waiting",
        )
        assertEquals("RUNNING", waiting["physicalState"].asText(), "$waiting")

        // 운영자가 운영 서비스로 rack_present 를 켠다. 현장이 지금 가상 시각으로 관측 시각을 적는다.
        val signalAt = stack.site.now()
        val written = stack.send("POST", "/api/cell/signals/rack_present", "operator", body = """{"value":"true"}""")
        assertEquals(200, written.status, "${written.body}")
        assertEquals("SUCCEEDED", written.body!!["result"].asText(), "${written.body}")
        assertEquals("true" to signalAt, written.body["signal"]["value"].asText() to Instant.parse(written.body["signal"]["observedAt"].asText()))

        // 신호가 현장에 닿지 않으면 여기서 기한을 넘겨 ABORTED 로 정착한다.
        val done = driver.drive(second)
        assertEquals("PHYSICALLY_DONE" to 2, done["physicalState"].asText() to done["missionVersion"].asInt(), "$done")
        assertEquals(
            listOf(Triple("rack-arrival", "DONE", "E2")) + SECOND_SLOTS.map { Triple(it, "DONE", "E2") },
            done["units"].map { Triple(it["unitId"].asText(), it["state"].asText(), it["reached"].asText()) },
            "$done",
        )
        assertEquals("E2", done["jobResponse"]["reachedEvidence"].asText(), "$done")

        val cell = stack.get("/api/cell")["cell"]
        assertEquals("true", cell["signals"].single { it["name"].asText() == "rack_present" }["value"].asText(), "$cell")
        val slots = cell["slots"].associateBy { it["id"].asText() }
        (FIRST_SLOTS + SECOND_SLOTS).forEach { slot ->
            assertEquals(true to MATERIAL, slots.getValue(slot)["occupied"].asBoolean() to slots.getValue(slot)["material"].asText(), "$cell")
        }
    }

    @Test
    @Order(4)
    fun `실행 호스트를 같은 포트로 다시 띄워도 활성 버전은 2 이고 새 작업 지시가 버전 2 로 선다`() {
        val instanceBefore = driver.executions()["instanceId"].asText()
        stack.restartHost()

        val after = stack.get(MISSIONS)
        assertEquals("DATA" to 2, after["active"]["source"].asText() to after["active"]["version"].asInt(), "$after")
        assertEquals(listOf(2, 1), after["versions"].map { it["version"].asInt() }, "$after")
        val executions = driver.executions()
        assertNotEquals(instanceBefore, executions["instanceId"].asText(), "$executions")
        assertEquals(0, executions["executions"].size(), "$executions")

        // 기동 때 DB 에서 세운 카탈로그가 미들웨어에 들어갔다. 새 실행이 버전 2 로 서고 대기 단위가 처음이다.
        val next = submit(rack(FIRST_SLOTS))
        driver.push(ExecutionDriver.STEP)
        val execution = driver.execution(next)
        assertEquals(2, execution["missionVersion"].asInt(), "$execution")
        assertEquals("rack-arrival", execution["units"].first()["unitId"].asText(), "$execution")
    }

    /** 엔지니어 모드로 초안을 저장하고 초안 id 를 낸다. */
    private fun save(definition: String): Long {
        val saved = engineer("$MISSIONS/drafts", json.createObjectNode().put("definition", definition).toString())
        assertEquals("SUCCEEDED", saved["result"].asText(), "$saved")
        return saved["outcome"]["draft"]["draftId"].asLong()
    }

    /** 엔지니어 모드로 부른다. 운영 서비스가 받았으면 200 이다(결과는 본문). */
    private fun engineer(path: String, body: String): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        assertEquals(200, reply.status, "$path → ${reply.body}")
        return reply.body!!
    }

    /** 운영자 모드로 작업 지시를 내고 배정된 실행 id 를 낸다. 배정 기체는 `pick_place` 를 가진 humanoid-01 하나다. */
    private fun submit(form: String): String {
        val reply = stack.send("POST", "/api/job-orders", "operator", body = form)
        assertEquals(200, reply.status, "${reply.body}")
        val outcome = reply.body!!["outcome"]
        assertEquals("ACCEPTED" to HUMANOID, outcome["result"].asText() to outcome["robotId"].asText(), "${reply.body}")
        return outcome["executionId"].asText()
    }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/task5.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/task5.patch"
```

```diff
diff --git a/e2e/build.gradle.kts b/e2e/build.gradle.kts
index 5f97a9c..600ef74 100644
--- a/e2e/build.gradle.kts
+++ b/e2e/build.gradle.kts
@@ -14,4 +14,5 @@ tasks.withType<Test>().configureEach {
     inputs.file(rootProject.file("site/robots.json")).withPropertyName("roster")
     inputs.dir(rootProject.file("picasso/profile/profiles")).withPropertyName("profiles")
     inputs.dir(rootProject.file("picasso/profile/schema")).withPropertyName("profileSchema")
+    inputs.dir(rootProject.file("mission-host/mock-run")).withPropertyName("mockRunProfile")
 }
diff --git a/e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt b/e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt
index 27c79c9..4cc48ab 100644
--- a/e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt
+++ b/e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt
@@ -17,20 +17,26 @@ import java.net.http.HttpClient
 import java.net.http.HttpRequest
 import java.net.http.HttpResponse
 import java.nio.file.Path
+import java.time.Duration
+import java.time.Instant
 
 /**
  * 통합 시험 한 세트. 한 JVM 에 Postgres(registry testFixtures)·registry·mimic·실행 호스트·운영 서비스를 띄운다(스펙 §10,
  * S3a 스펙 §11).
  *
  * 시험 클래스마다 새로 띄우고 닫는다. 클래스 사이에 registry 를 멈추는 시험이 있어 공유하지 않는다.
- * DB 는 띄울 때마다 비운다. `PostgresSupport.reset()` 은 public 만 지우므로 ops 스키마는 따로 지운다.
+ * DB 는 띄울 때마다 비운다. `PostgresSupport.reset()` 은 public 만 지우므로 ops·mission 스키마는 따로 지운다. 두 스키마의
+ * 덧붙이기 전용 트리거가 DELETE·TRUNCATE 를 막으므로 스키마째 지운다.
+ *
+ * [restartHost] 는 DB 를 지우지 않고 실행 호스트만 같은 포트로 다시 띄운다. 운영 서비스는 기동 때 호스트 주소를 한 번 받으므로
+ * 포트가 같아야 다시 띄운 호스트에 닿는다(S3b 스펙 §10 통합 행).
  *
  * 실행 호스트의 시계는 현장 시계(`Site.now()`)다. 시험은 `Site.advance` 로 가상 시각을 실제 시각보다 앞으로 밀고, 미들웨어는
  * E2 시간 윈도우의 기준 시각을 mimic 응답 헤더에서 가져오면서 마감은 호스트 시계로 보므로 둘이 같아야 한다(S3a 스펙 §7.2).
  */
 class E2eStack private constructor(
     val site: Site,
-    private val host: ConfigurableApplicationContext,
+    private var host: ConfigurableApplicationContext,
     private val ops: ConfigurableApplicationContext,
     val hostUrl: String,
     val opsUrl: String,
@@ -52,6 +58,25 @@ class E2eStack private constructor(
         contentType: String = "application/json",
     ): Reply = send(opsUrl, method, path, mode, user, body, contentType)
 
+    /**
+     * 실행 호스트만 닫고 같은 DB·현장으로 같은 포트에 다시 띄운다(재기동). 임무 버전은 DB 에 남고, 미들웨어의 실행은 새 인스턴스라
+     * 비어 있다. 닫은 포트를 곧바로 다시 여는 것이 막히면(운영체제가 아직 놓지 않음) 짧게 다시 시도한다.
+     */
+    fun restartHost() {
+        val port = (host as WebServerApplicationContext).webServer.port
+        host.close()
+        val deadline = Instant.now().plus(RESTART_WAIT)
+        while (true) {
+            try {
+                host = startHost(site, port)
+                return
+            } catch (e: Exception) {
+                if (Instant.now().isAfter(deadline)) throw IllegalStateException("실행 호스트를 포트 $port 에 다시 띄우지 못했다", e)
+                Thread.sleep(200)
+            }
+        }
+    }
+
     /** 같은 registry 에 운영자 토큰만 다른 운영 서비스를 하나 더 띄운다. 닫는 것은 부르는 쪽이다. */
     fun opsWithToken(token: String): Pair<ConfigurableApplicationContext, String> {
         val context = startOps(site.registryUrl, token, siteId, hostUrl)
@@ -73,6 +98,9 @@ class E2eStack private constructor(
     companion object {
         const val OPERATOR_TOKEN = "e2e-operator"
         val root: Path = Path.of("..").toAbsolutePath().normalize()
+
+        /** [restartHost] 가 같은 포트를 다시 여는 상한(실제 시간). */
+        private val RESTART_WAIT: Duration = Duration.ofSeconds(10)
         private val http = HttpClient.newHttpClient()
         private val json = ObjectMapper()
 
@@ -80,6 +108,7 @@ class E2eStack private constructor(
             val siteId = checkNotNull(System.getenv("SITE_ID")) { "SITE_ID 가 없다(루트 .env)" }
             PostgresSupport.reset()
             PostgresSupport.execute("DROP SCHEMA IF EXISTS ops CASCADE")
+            PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
             val site = Site.start(
                 SiteConfig(
                     root = root,
@@ -152,13 +181,25 @@ class E2eStack private constructor(
             )
         }
 
-        /** 실행 호스트를 이 현장의 mimic gRPC 포트·셀 대역에 붙이고 현장 시계로 띄운다(S3a 계약 공통 규칙). */
-        private fun startHost(site: Site): ConfigurableApplicationContext =
-            MissionHostApplication.builder(HostClock { site.now() }).run(
-                "--server.port=0",
+        /**
+         * 실행 호스트를 이 현장의 mimic gRPC 포트·셀 대역에 붙이고 현장 시계로 띄운다(S3b JSON 계약 공통 규칙). 임무 버전 저장은
+         * 같은 Postgres 이고, 모의 실행 프로파일·스키마는 절대 경로로 넘긴다(시험의 작업 디렉터리는 모듈 폴더다).
+         *
+         * @param port 0 이면 무작위다. 다시 띄울 때는 앞 호스트의 포트다.
+         */
+        private fun startHost(site: Site, port: Int = 0): ConfigurableApplicationContext {
+            val db = db()
+            return MissionHostApplication.builder(HostClock { site.now() }).run(
+                "--server.port=$port",
                 "--host.mimic.port=${site.mimicPort}",
                 "--host.cell.url=http://127.0.0.1:${site.cellPort}",
+                "--spring.datasource.url=${db.url}",
+                "--spring.datasource.username=${db.user}",
+                "--spring.datasource.password=${db.password}",
+                "--host.mock-run.profile=${root.resolve("mission-host/mock-run/humanoid-a.json")}",
+                "--host.mock-run.schema=${root.resolve(SiteConfig.PROFILE_SCHEMA)}",
             )
+        }
 
         private fun call(method: String, url: String, headers: Map<String, String>, body: String?): Reply {
             val builder = HttpRequest.newBuilder(URI.create(url))
diff --git a/e2e/src/test/kotlin/dev/picasso/ops/e2e/JobOrderTest.kt b/e2e/src/test/kotlin/dev/picasso/ops/e2e/JobOrderTest.kt
index 077b5af..5e4c194 100644
--- a/e2e/src/test/kotlin/dev/picasso/ops/e2e/JobOrderTest.kt
+++ b/e2e/src/test/kotlin/dev/picasso/ops/e2e/JobOrderTest.kt
@@ -4,14 +4,11 @@ import com.fasterxml.jackson.databind.JsonNode
 import com.fasterxml.jackson.databind.ObjectMapper
 import dev.picasso.ops.e2e.Commissioned.HUMANOID
 import dev.picasso.ops.e2e.Commissioned.QUADRUPED
-import dev.picasso.ops.host.MissionHost
 import org.junit.jupiter.api.AfterAll
 import org.junit.jupiter.api.BeforeAll
 import org.junit.jupiter.api.MethodOrderer
 import org.junit.jupiter.api.Order
 import org.junit.jupiter.api.TestMethodOrder
-import java.time.Duration
-import java.time.Instant
 import kotlin.test.Test
 import kotlin.test.assertEquals
 import kotlin.test.assertNotNull
@@ -23,10 +20,7 @@ import kotlin.test.assertTrue
  *
  * ## 시계
  *
- * 호스트 시계는 현장 시계다(`E2eStack`). 시험은 [push] 로 가상 시계를 밀 때마다 호스트의 마지막 pump 시각(`pumpedAt`)이 민 뒤의
- * 가상 시각 이상이 될 때까지 기다리고, 한 주기를 더 기다린 뒤 상태를 읽는다. mimic 스트림 갱신은 gRPC 스레드로 비동기로 와서
- * `pumpedAt` 만으로는 그 pump 가 방금 민 전이를 봤다는 보장이 없기 때문이다. E2 마감(`doneAt + 15s`)은 단위를 끝낸 밀기
- * 뒤의 가상 시각부터 센다. 그래서 확인 중(VERIFYING)인 단위가 보이면 시계를 더 밀지 않고 실제 시간으로만 기다린다([drive]).
+ * 호스트 시계는 현장 시계다(`E2eStack`). 가상 시계를 미는 규칙은 [ExecutionDriver] 에 있다.
  *
  * ## 실패 모드
  *
@@ -41,6 +35,7 @@ class JobOrderTest {
 
     companion object {
         private lateinit var stack: E2eStack
+        private lateinit var driver: ExecutionDriver
 
         private const val MATERIAL = "ENGINE-COVER-A"
         private const val PRESENTATION = "SEQ-IN-02.BIN-A"
@@ -50,22 +45,11 @@ class JobOrderTest {
         private val RACK = """{"workMasterId":"PrepareSequencedRack","slots":[${SLOTS.joinToString(",") { "\"$it\"" }}],""" +
             """"material":"$MATERIAL","presentation":"$PRESENTATION"}"""
 
-        /** 한 번에 미는 가상 시간. E2 마감 15초보다 짧아 확인 전에 마감을 넘기지 않는다. */
-        private val STEP: Duration = Duration.ofSeconds(5)
-
-        /** 밀기 상한. 가장 긴 것이 `pick_place` 둘(45초 ±10%)이다. */
-        private const val ROUNDS = 60
-
-        /** 확인 중인 단위를 실제 시간으로 기다리는 상한. 셀 대역은 다음 pump(250ms) 에 읽힌다. */
-        private val VERIFY_WAIT: Duration = Duration.ofSeconds(3)
-
-        /** 정착한 물리 상태(S3a JSON 계약 §5). */
-        private val SETTLED = setOf("PHYSICALLY_DONE", "UNVERIFIED", "FAILED", "ABORTED", "PARTIAL")
-
         @BeforeAll
         @JvmStatic
         fun up() {
             stack = E2eStack.start()
+            driver = ExecutionDriver(stack)
             Commissioned.complete(stack)
         }
 
@@ -110,7 +94,7 @@ class JobOrderTest {
         // 기체별이므로 어느 기체가 무엇을 도는지가 정해져 있어야 한다.
         assertEquals(HUMANOID, outcome["robotId"].asText(), "$outcome")
 
-        val execution = drive(outcome["executionId"].asText())
+        val execution = driver.drive(outcome["executionId"].asText())
         assertEquals(submitted["jobOrderId"].asText(), execution["jobOrderId"].asText())
         assertEquals("InspectAsset", execution["workMasterId"].asText())
         assertTrue(execution.has("missionVersion") && execution["missionVersion"].isNull, "$execution")
@@ -136,7 +120,7 @@ class JobOrderTest {
         val logged = stack.get("/api/operations").first { it["target"].asText() == submitted["jobOrderId"].asText() }
         assertEquals(listOf(HUMANOID), ObjectMapper().readTree(logged["request"].asText())["candidates"].map { it.asText() }, "$logged")
 
-        val execution = drive(outcome["executionId"].asText())
+        val execution = driver.drive(outcome["executionId"].asText())
         assertEquals("PHYSICALLY_DONE", execution["physicalState"].asText(), "$execution")
         assertEquals(
             SLOTS.map { Triple(it, "DONE", "E2") },
@@ -158,41 +142,4 @@ class JobOrderTest {
         assertEquals(200, reply.status, "${reply.body}")
         return reply.body!!
     }
-
-    private fun executions(): JsonNode = stack.get("/api/executions")
-
-    private fun execution(executionId: String): JsonNode =
-        checkNotNull(executions()["executions"].firstOrNull { it["executionId"].asText() == executionId }) { "실행이 없다: $executionId" }
-
-    /** 가상 시계를 밀고, 호스트가 민 뒤의 시각에 pump 를 시작할 때까지 기다린 뒤 한 주기 더 기다린다. */
-    private fun push(by: Duration) {
-        stack.site.advance(by)
-        val target = stack.site.now()
-        val deadline = Instant.now().plusSeconds(5)
-        while (true) {
-            val at = executions()["pumpedAt"]
-            if (!at.isNull && !Instant.parse(at.asText()).isBefore(target)) break
-            check(Instant.now().isBefore(deadline)) { "호스트 pump 가 $target 에 이르지 않았다: $at" }
-            Thread.sleep(50)
-        }
-        Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
-    }
-
-    /**
-     * 실행의 물리 상태가 정착할 때까지 [STEP] 씩 민다. 민 뒤 확인 중(VERIFYING)인 단위가 있으면 더 밀지 않고 [VERIFY_WAIT] 동안
-     * 실제 시간으로 기다린다. 그 안에 정착하지 않으면(셀 신호가 없음) 다시 밀어 마감으로 간다.
-     */
-    private fun drive(executionId: String): JsonNode {
-        repeat(ROUNDS) {
-            push(STEP)
-            var seen = execution(executionId)
-            val until = Instant.now().plus(VERIFY_WAIT)
-            while (seen["units"].any { it["state"].asText() == "VERIFYING" } && Instant.now().isBefore(until)) {
-                Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
-                seen = execution(executionId)
-            }
-            if (seen["physicalState"].asText() in SETTLED) return seen
-        }
-        error("실행 $executionId 가 ${ROUNDS}번 밀어도 정착하지 않았다: ${execution(executionId)}")
-    }
 }
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && ./gradlew :e2e:test -q
```
Expected: e2e 44, 실패 0(`MissionVersionTest` 4 포함, 그 클래스만 약 47초). 백그라운드로 돌린다. Docker 데몬이 떠 있어야 한다(Testcontainers).

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && git add e2e/src/test/kotlin/dev/picasso/ops/e2e/ExecutionDriver.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/MissionVersionTest.kt e2e/build.gradle.kts e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/JobOrderTest.kt && git commit -F - <<'EOF'
test(e2e): 임무 버전 통합 시험과 실행 호스트 같은 포트 재기동 추가

- `mission` 스키마 정리와 호스트 데이터 소스, 같은 포트 재기동
- `MissionVersionTest` 4개, 공용 `ExecutionDriver`

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 6: Playwright, README

**Files:**
- Modify: `README.md`, `ui/e2e/lifecycle.spec.ts`

- [ ] **Step 1: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/task6.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3b-patches/task6.patch"
```

````diff
diff --git a/README.md b/README.md
index f426f4e..c2d8f49 100644
--- a/README.md
+++ b/README.md
@@ -2,17 +2,17 @@
 
 picasso 를 라이브러리로 쓰는 담는 측 저장소입니다. 로봇, 임무, 엔드포인트의 운영 가능성을 PoC 로 입증합니다. 실제 하드웨어 현장은 없습니다. 보안과 인증은 생략합니다. 운영 중 변경은 코드 수정이 아니라 관리 화면에서 처리합니다.
 
-지금 단계는 S3a 실행 호스트와 작업 지시입니다. S2 현장 설정 위에 올립니다. 서브모듈 `picasso` 는 P4 머지 커밋 `1e3f4ae` 를 가리킵니다. 그 버전에는 P2a 시험 실행기, P2b 리비전·바인딩 REST, P3 임무 정의 버전, P4 스트림 재부착과 mimic 엔진 잠금이 들어 있습니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 같은 영역 왼쪽에 프로파일 구역이 있습니다. 그 구역에서 엔지니어 모드는 기종 프로파일 리비전을 파일로 골라 제출하고, 시험을 요청하고, 활성화합니다. 시험 결과는 사람이 적지 않습니다. 런처가 띄운 시험 실행기가 적습니다. 기체 상세에서 엔지니어 모드는 빌드와 활성 리비전을 골라 바인딩하고, 사이트 명칭 등록을 기록합니다. 명칭 티칭은 화면 밖 현장 작업입니다. 기체 목록에 시운전 칸이 있습니다. 값은 완료, 미완, 퇴역입니다. 완료 조건은 셋입니다. 원장 상태가 `CONFIRMED` 이고 퇴역이 아니어야 합니다. 활성 바인딩이 있어야 합니다. 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 여야 합니다. 빠진 조건은 막힘으로 보입니다. 운영자 모드는 퇴역과 복귀를 합니다. 어댑터 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 현장·자원 영역에는 현장 설정 구역이 있습니다. 지금 버전, 연결 기준 시간, 허용 범위(60~3600초), 버전 이력을 보입니다. 엔지니어 모드는 연결 기준 시간을 바꿉니다. 바꿀 때는 사유가 필요합니다. 바꾸면 새 버전이 생깁니다. 다음 기체 목록 읽기부터 그 값으로 연결 칸과 막힘을 판정합니다. 재기동은 필요 없습니다. 운영자 모드는 바꾸지 못합니다. 막힘 카드는 어느 현장 설정 버전으로 판정했는지를 근거 버전으로 보입니다. 기체 상세에는 연결 판정 기준이 보입니다. 기준 초와 버전입니다. 운영 영역에서 운영자 모드는 작업 지시를 냅니다. 임무는 코드 정의 임무 2개(`InspectAsset`, `PrepareSequencedRack`)입니다. `InspectAsset` 은 점검 대상의 id 와 장소 이름을 넣습니다. `PrepareSequencedRack` 은 셀 대역의 슬롯과 자재를 고릅니다. 제시 자리는 자재에서 정해집니다. 폼을 채우면 기체별 배정 가능 표가 보입니다. 배정 가능 조건은 넷입니다. 시운전 완료, 연결 신선, 도는 실행 없음, 스킬 적합입니다. 앞의 둘은 운영 서비스가, 뒤의 둘은 실행 호스트가 판정합니다. 못 물어본 칸은 모름이고 모름이 있으면 배정 불가입니다. 운영 서비스는 배정 가능한 기체만 후보로 실행 호스트에 넘깁니다. 실행 호스트의 picasso 미들웨어가 후보 중 하나를 골라 mimic 기체에서 실행합니다. 엔지니어 모드는 작업 지시를 내지 못합니다. 실행 목록은 실행 상태, 단위 상태와 근거 등급, 작업 응답, 임무 버전을 보입니다. 코드 정의 임무의 임무 버전은 `코드 정의` 로 보입니다. 셀 대역 표는 제시 자리와 슬롯의 점유와 자재를 보입니다. 임무 버전 저장과 편집은 S3b, 미들웨어 시간값과 인시던트 기록의 현장 설정 버전은 S3c 의 몫입니다. S1 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. P2·S1d 설계 스펙은 `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` 에 있습니다. S2 설계 스펙은 `docs/superpowers/specs/2026-10-08-s2-site-settings-design.md` 에 있습니다. S3a 설계 스펙은 `docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.
+지금 단계는 S3b 임무 버전입니다. S3a 실행 호스트와 작업 지시 위에 올립니다. 서브모듈 `picasso` 는 P4 머지 커밋 `1e3f4ae` 를 가리킵니다. 그 버전에는 P2a 시험 실행기, P2b 리비전·바인딩 REST, P3 임무 정의 버전, P4 스트림 재부착과 mimic 엔진 잠금이 들어 있습니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 같은 영역 왼쪽에 프로파일 구역이 있습니다. 그 구역에서 엔지니어 모드는 기종 프로파일 리비전을 파일로 골라 제출하고, 시험을 요청하고, 활성화합니다. 시험 결과는 사람이 적지 않습니다. 런처가 띄운 시험 실행기가 적습니다. 기체 상세에서 엔지니어 모드는 빌드와 활성 리비전을 골라 바인딩하고, 사이트 명칭 등록을 기록합니다. 명칭 티칭은 화면 밖 현장 작업입니다. 기체 목록에 시운전 칸이 있습니다. 값은 완료, 미완, 퇴역입니다. 완료 조건은 셋입니다. 원장 상태가 `CONFIRMED` 이고 퇴역이 아니어야 합니다. 활성 바인딩이 있어야 합니다. 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 여야 합니다. 빠진 조건은 막힘으로 보입니다. 운영자 모드는 퇴역과 복귀를 합니다. 어댑터 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 현장·자원 영역에는 현장 설정 구역이 있습니다. 지금 버전, 연결 기준 시간, 허용 범위(60~3600초), 버전 이력을 보입니다. 엔지니어 모드는 연결 기준 시간을 바꿉니다. 바꿀 때는 사유가 필요합니다. 바꾸면 새 버전이 생깁니다. 다음 기체 목록 읽기부터 그 값으로 연결 칸과 막힘을 판정합니다. 재기동은 필요 없습니다. 운영자 모드는 바꾸지 못합니다. 막힘 카드는 어느 현장 설정 버전으로 판정했는지를 근거 버전으로 보입니다. 기체 상세에는 연결 판정 기준이 보입니다. 기준 초와 버전입니다. 운영 영역에서 운영자 모드는 작업 지시를 냅니다. 임무는 `InspectAsset`, `PrepareSequencedRack` 2개입니다. `InspectAsset` 은 점검 대상의 id 와 장소 이름을 넣습니다. `PrepareSequencedRack` 은 셀 대역의 슬롯과 자재를 고릅니다. 제시 자리는 자재에서 정해집니다. 폼을 채우면 기체별 배정 가능 표가 보입니다. 배정 가능 조건은 넷입니다. 시운전 완료, 연결 신선, 도는 실행 없음, 스킬 적합입니다. 앞의 둘은 운영 서비스가, 뒤의 둘은 실행 호스트가 판정합니다. 못 물어본 칸은 모름이고 모름이 있으면 배정 불가입니다. 운영 서비스는 배정 가능한 기체만 후보로 실행 호스트에 넘깁니다. 실행 호스트의 picasso 미들웨어가 후보 중 하나를 골라 mimic 기체에서 실행합니다. 엔지니어 모드는 작업 지시를 내지 못합니다. 실행 목록은 실행 상태, 단위 상태와 근거 등급, 작업 응답, 임무 버전을 보입니다. 임무 버전은 코드 정의면 `코드 정의`, 데이터 버전이면 `버전 N` 으로 보입니다. 셀 대역 표는 제시 자리와 슬롯의 점유와 자재를 보입니다. 같은 영역의 셀 대역 신호 표는 이름 있는 신호의 이름, 종류, 값, 관측 시각을 보입니다. 안전이 아닌 BOOLEAN 신호는 켜기와 끄기 버튼이 있습니다. 사람이 PLC 역할을 하는 정상 조작이라 운영자 모드와 엔지니어 모드 둘 다 합니다. 안전 신호는 값만 보입니다. 임무·정책 영역은 `PrepareSequencedRack` 의 활성 버전과 버전 이력, 초안과 그 마지막 모의 실행을 보입니다. 엔지니어 모드는 정의 JSON 을 편집기에서 고쳐 초안으로 저장하고, 검증하고, 모의 실행하고, 사유를 적어 활성화합니다. 시작용 정의 2개(데이터 정의, 랙 도착 대기)를 템플릿으로 불러올 수 있습니다. 초안은 자유롭게 저장되고 활성화만 관문입니다. 활성화는 지금의 신호 사양과 시운전 완료 기체의 스킬로 다시 검증하고, 그 초안의 마지막 모의 실행이 통과여야 합니다. 신호 사양에 없는 신호를 참조하는 것 같은 거부는 거부 카드로 보이고, 해결 담당과 바로 갈 작업이 함께 보입니다. 활성화한 버전은 다음 작업 지시부터 쓰이고, 도는 실행은 생성 때의 버전으로 끝납니다. 운영자 모드는 보기만 합니다. 미들웨어 시간값과 인시던트 기록의 현장 설정 버전은 S3c 의 몫입니다. S1 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. P2·S1d 설계 스펙은 `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` 에 있습니다. S2 설계 스펙은 `docs/superpowers/specs/2026-10-08-s2-site-settings-design.md` 에 있습니다. S3a 설계 스펙은 `docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md` 에 있습니다. S3b 설계 스펙은 `docs/superpowers/specs/2026-10-08-s3b-mission-versions-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.
 
 ## 구성
 
 - `.env`: 사이트 id(`SITE_ID`), DB 접속값, 포트, 토큰을 둡니다. 로컬 PoC 값입니다. 런처, 실행 호스트, 운영 서비스, 시험, CI 가 이 파일 하나를 읽습니다.
 - `picasso/`: picasso git 서브모듈입니다. 고정 커밋을 가리킵니다. Gradle includeBuild 로 가져옵니다. 읽기 전용입니다. picasso 쪽 변경은 picasso 저장소의 PR 로 냅니다.
-- `site/`: 가짜 현장 런처입니다(Kotlin). registry, mimic 기체, 리비전 시험 실행기(`site-runner`), 셀 대역을 한 프로세스에서 띄웁니다. 기동 직후 mimic 가상 시계를 실제 시각까지 한 번 밉니다. 그 뒤 실제 1초마다 가상 시계를 실제 시각까지 따라잡게 밉니다. 그래서 가상 시각은 실제 시각보다 앞서지 않고 차이는 1초 이내입니다. mimic gRPC 는 `.env` 의 `MIMIC_GRPC_PORT` 에 엽니다. 실행 호스트가 이 포트에 붙습니다. 셀 대역은 셀 신호를 자동으로 내는 대역입니다. 제시 자리 `SEQ-IN-02.BIN-A` 는 늘 `ENGINE-COVER-A` 를 듭니다. 슬롯 `RACK-204.S01`~`S04` 는 처음에 비어 있습니다. 기체가 `pick_place` 를 성공하면 그 슬롯을 제시 자리의 자재로 채웁니다. 슬롯은 비우지 않습니다. 기체가 보고한 배치에서 채우므로 독립 설비 확인이 아닙니다. 셀 대역은 `.env` 의 `SITE_CELL_PORT` 에 루프백 `GET /cell` 로 냅니다. 실행기는 실제 1초마다 registry 에서 시험 요청을 집습니다. 실행기는 시험마다 자기 mimic 을 따로 띄웁니다. 그래서 현장 기체의 보고와 상태를 바꾸지 않습니다. 적재 토큰은 런처만 가집니다. 그래서 시험 결과를 적을 수 있는 것도 런처뿐입니다. 기체 명부는 `site/robots.json` 입니다. 기체 명부의 `site_names` 는 현장에서 기체에 티칭한 명칭입니다. 런처가 기동 때 기체에 넣습니다. Postgres 용 compose 는 `site/compose.yaml` 입니다. 손 기동 확인은 `site/smoke.sh` 입니다.
-- `mission-host/`: 실행 호스트입니다(Kotlin, Spring Boot). picasso 미들웨어를 세워 mimic 기체에서 작업 지시를 실행합니다. 250ms 마다 셀 대역을 읽고 미들웨어를 pump 합니다. 메모리만 씁니다. 재기동하면 실행이 사라집니다. 토큰을 받지 않습니다. `.env` 의 `HOST_PORT` 에 루프백으로 엽니다. 호출자는 운영 서비스뿐입니다.
+- `site/`: 가짜 현장 런처입니다(Kotlin). registry, mimic 기체, 리비전 시험 실행기(`site-runner`), 셀 대역을 한 프로세스에서 띄웁니다. 기동 직후 mimic 가상 시계를 실제 시각까지 한 번 밉니다. 그 뒤 실제 1초마다 가상 시계를 실제 시각까지 따라잡게 밉니다. 그래서 가상 시각은 실제 시각보다 앞서지 않고 차이는 1초 이내입니다. mimic gRPC 는 `.env` 의 `MIMIC_GRPC_PORT` 에 엽니다. 실행 호스트가 이 포트에 붙습니다. 셀 대역은 셀 신호를 자동으로 내는 대역입니다. 제시 자리 `SEQ-IN-02.BIN-A` 는 늘 `ENGINE-COVER-A` 를 듭니다. 슬롯 `RACK-204.S01`~`S04` 는 처음에 비어 있습니다. 기체가 `pick_place` 를 성공하면 그 슬롯을 제시 자리의 자재로 채웁니다. 슬롯은 비우지 않습니다. 기체가 보고한 배치에서 채우므로 독립 설비 확인이 아닙니다. 셀 대역은 이름 있는 신호 3개(`rack_present`, 안전 신호 `guard_closed`, `lot_code`)도 냅니다. 신호마다 사양(종류, 자리, 안전 여부)과 값을 함께 선언하고, 실행 호스트는 이 선언을 임무 정의 검증의 신호 사양으로 씁니다. 신호 값은 시계를 밀어도 바뀌지 않고 `POST /cell/signals/{name}` 으로만 바뀝니다. 안전 신호는 쓰기를 거부합니다. 재기동하면 처음 값으로 돌아갑니다. 셀 대역은 `.env` 의 `SITE_CELL_PORT` 에 루프백 `GET /cell` 과 `POST /cell/signals/{name}` 으로 냅니다. 실행기는 실제 1초마다 registry 에서 시험 요청을 집습니다. 실행기는 시험마다 자기 mimic 을 따로 띄웁니다. 그래서 현장 기체의 보고와 상태를 바꾸지 않습니다. 적재 토큰은 런처만 가집니다. 그래서 시험 결과를 적을 수 있는 것도 런처뿐입니다. 기체 명부는 `site/robots.json` 입니다. 기체 명부의 `site_names` 는 현장에서 기체에 티칭한 명칭입니다. 런처가 기동 때 기체에 넣습니다. Postgres 용 compose 는 `site/compose.yaml` 입니다. 손 기동 확인은 `site/smoke.sh` 입니다.
+- `mission-host/`: 실행 호스트입니다(Kotlin, Spring Boot). picasso 미들웨어를 세워 mimic 기체에서 작업 지시를 실행합니다. 250ms 마다 셀 대역을 읽고 미들웨어를 pump 합니다. 임무 버전(초안, 모의 실행, 버전)은 같은 Postgres 의 자기 스키마 `mission` 에 덧붙이기 전용으로 저장합니다. 재기동하면 활성 버전은 그대로이고 실행은 사라집니다. 모의 실행은 호스트 안에 별도 mimic 기체와 가상 시계를 띄워 초안 정의로 표본 작업 지시 하나를 끝까지 돌립니다. 현장 기체, 셀 대역, registry 에는 닿지 않습니다. 모의 실행 기체 프로파일(`mission-host/mock-run/humanoid-a.json`)과 프로파일 스키마 경로는 작업 디렉터리 기준이므로 저장소 루트에서 띄웁니다. 토큰을 받지 않습니다. `.env` 의 `HOST_PORT` 에 루프백으로 엽니다. 호출자는 운영 서비스뿐입니다.
 - `ops-service/`: 운영 서비스입니다(Kotlin, Spring Boot). 화면의 유일한 백엔드입니다. 운영자 토큰을 쥡니다. registry REST 와 실행 호스트 REST 를 부릅니다. picasso 모듈을 쓰지 않습니다.
 - `ui/`: 관리 화면입니다(React, Vite, TypeScript). 운영 서비스만 부릅니다.
-- `e2e/`: 통합 시험입니다. 한 JVM 에 Postgres, registry, mimic, 실행 호스트, 운영 서비스를 띄웁니다. 실행 호스트의 시계는 현장 가상 시계이고 시험이 시계를 직접 밉니다.
+- `e2e/`: 통합 시험입니다. 한 JVM 에 Postgres, registry, mimic, 실행 호스트, 운영 서비스를 띄웁니다. 실행 호스트의 시계는 현장 가상 시계이고 시험이 시계를 직접 밉니다. 임무 버전 시험은 실행 호스트만 같은 포트로 다시 띄워 활성 버전이 남는지 봅니다.
 - `docs/`: 설계 스펙과 구현 계획입니다.
 
 ## 선행 도구
@@ -73,7 +73,7 @@ cd ui && npx playwright install chromium
 cd ui && npx playwright test
 ```
 
-Playwright 가 Postgres, 런처, 실행 호스트, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 이어서 리비전 제출, 시험 요청, 실행기의 `TESTED` 기록, 활성화, 바인딩, 명칭 기록, `humanoid-01` 시운전 완료까지 돕니다. `quadruped-01` 의 명칭 기록이 기체가 아는 명칭 없음으로 막히는 것도 봅니다. 현장·자원 영역에서 연결 기준 시간을 120초로 바꿔 버전 2 와 이력 행을 봅니다. 운영자 모드에서는 바꾸지 못하는 것과 기체 상세의 연결 판정 기준이 버전 2 로 바뀐 것도 봅니다. 운영 영역에서 운영자 모드로 `InspectAsset` 작업 지시를 냅니다. 시운전 완료인 `humanoid-01` 만 배정 가능이고 배정됩니다. 실행 목록에 `코드 정의` 행이 보이고 실행이 `PHYSICALLY_DONE` 으로 끝납니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 시각을 따라갑니다. 시험은 약 3분 걸립니다. 로컬 실측은 3.1분입니다. Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.
+Playwright 가 Postgres, 런처, 실행 호스트, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 이어서 리비전 제출, 시험 요청, 실행기의 `TESTED` 기록, 활성화, 바인딩, 명칭 기록, `humanoid-01` 시운전 완료까지 돕니다. `quadruped-01` 의 명칭 기록이 기체가 아는 명칭 없음으로 막히는 것도 봅니다. 현장·자원 영역에서 연결 기준 시간을 120초로 바꿔 버전 2 와 이력 행을 봅니다. 운영자 모드에서는 바꾸지 못하는 것과 기체 상세의 연결 판정 기준이 버전 2 로 바뀐 것도 봅니다. 운영 영역에서 운영자 모드로 `InspectAsset` 작업 지시를 냅니다. 시운전 완료인 `humanoid-01` 만 배정 가능이고 배정됩니다. 실행 목록에 `코드 정의` 행이 보이고 실행이 `PHYSICALLY_DONE` 으로 끝납니다. 임무·정책 영역에서 엔지니어 모드로 데이터 정의 템플릿을 불러와 초안 저장, 검증, 모의 실행, 활성화를 하고 버전 이력에 `버전 1 (활성)` 을 봅니다. 운영 영역의 셀 대역 신호 표에서 `rack_present` 를 켜고 신호 조작 결과와 바뀐 값을 봅니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 시각을 따라갑니다. 시험은 약 3분 걸립니다. 로컬 실측은 2.5분입니다. Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.
 
 ## 띄우기
 
@@ -105,7 +105,7 @@ set -a; . ./.env; set +a
 site/build/install/site/bin/site
 ```
 
-터미널 2에서 실행 호스트를 띄웁니다.
+터미널 2에서 실행 호스트를 띄웁니다. 모의 실행 프로파일 경로 때문에 저장소 루트에서 띄웁니다.
 
 ```bash
 env -u PICASSO_INGEST_TOKEN -u PICASSO_OPERATOR_TOKEN mission-host/build/install/mission-host/bin/mission-host
@@ -129,10 +129,16 @@ Gradle 의 run 작업 2개를 한 작업 트리에서 겹쳐 띄우지 않습니
 
 - 토큰은 2개입니다. 적재 토큰은 site 만 받습니다. 운영 서비스는 운영자 토큰만 받습니다. 어느 토큰도 브라우저로 가지 않습니다.
 - registry, 실행 호스트, 운영 서비스, 셀 대역은 `127.0.0.1` 에만 엽니다. mimic gRPC 는 picasso 가 주소를 정하므로 모든 인터페이스에 열립니다. 인증 없는 기체 제어 API 표면이고 포트가 `.env` 로 고정됩니다.
-- 실행 호스트 REST 는 인증이 없습니다. 같은 기계의 다른 프로세스는 운영자 모드 검사 없이 작업 지시를 낼 수 있습니다.
-- `pick_place` 에는 실패 모드가 있습니다(단위 하나에 약 6%). 기체별 시드로 추첨하므로 런처로 PrepareSequencedRack 을 돌리면 슬롯 넷짜리 작업 지시는 약 22% 가 어딘가에서 실패하고, 실패는 실행 목록에 보입니다. 화면 시험은 실패 모드가 없는 InspectAsset 만 돌립니다.
+- 실행 호스트 REST 와 셀 대역은 인증이 없습니다. 같은 기계의 다른 프로세스는 모드 검사 없이 작업 지시를 내고, 임무 버전을 활성화하고, 신호를 쓸 수 있습니다.
+- `pick_place` 에는 실패 모드가 있습니다(단위 하나에 약 6%). 기체별 시드로 추첨하므로 런처로 PrepareSequencedRack 을 돌리면 슬롯 넷짜리 작업 지시는 약 22% 가 어딘가에서 실패하고, 실패는 실행 목록에 보입니다. 화면 시험이 현장 기체로 돌리는 작업 지시는 실패 모드가 없는 InspectAsset 뿐입니다. 모의 실행은 실패 모드를 뺀 프로파일 사본으로 돌므로 추첨이 없습니다.
 - 운영자 보류를 푸는 수단이 아직 없습니다. 보류에 선 실행의 기체는 실행 호스트를 재기동할 때까지 배정 불가입니다.
-- 선언 전 mimic 의 생존 보고는 registry 가 거절합니다. 거절한 보고는 어디에도 남지 않습니다. 기체를 선언하면 보고가 붙습니다.
+- 랙 도착 대기 템플릿의 대기 노드는 기한 120초가 지나면 실행을 중단합니다(ABORTED). 기체는 다시 배정할 수 있습니다. 런처는 가상 시계를 실제 시각에 맞춰 밀므로, 그 작업 지시를 낸 뒤 2분 안에 셀 대역 신호 표에서 `rack_present` 를 켜야 진행합니다. 셀 대역은 신호를 스스로 되돌리지 않으므로 한 번 켜면 다음 작업 지시의 대기는 바로 풀립니다.
+- 신호 사양은 셀 대역 픽스처의 코드 상수입니다. 화면에서 편집하지 못하고 버전도 없습니다. 신호는 값, 관측 시각, 모름만 보이고 신호 품질(응답 없음, 단절, 불확실)은 보이지 않습니다.
+- 임무 편집은 `PrepareSequencedRack` 하나만 합니다. 새 WorkMaster 는 만들지 못합니다. 편집기는 textarea 이고 편집기용 JSON Schema 파일은 없습니다. 정의의 옳고 그름은 실행 호스트가 검증합니다.
+- 모의 실행은 이상적 현장을 가정합니다. 목적지 슬롯은 처음부터 기대 자재를 들고, 이름 있는 신호는 대기 노드의 기대 값을 내고, 기체 실패 모드가 없습니다. 정의가 끝까지 도는지를 볼 뿐 현장 사실을 보증하지 않습니다. 요청 안에서 동기로 돌며 실제 시간 상한은 30초입니다. 활성화는 그 초안의 마지막 모의 실행만 보고, 앞 버전들의 모의 실행을 다시 돌리지 않습니다.
+- 바닥 소유는 검사하지 않습니다. 활성화 권한은 모드 검사뿐이고 명부가 없습니다.
+- 임무 버전의 표 3개는 덧붙이기 전용입니다(트리거가 UPDATE, DELETE, TRUNCATE 를 막습니다). 지우려면 스키마째 지웁니다. 화면 시험은 시작할 때 compose 볼륨째 내리므로 매번 빈 DB 에서 시작합니다.
+- 선언 전 mimic 의 생존 보고는 registry 가 거부합니다. 거부한 보고는 어디에도 남지 않습니다. 기체를 선언하면 보고가 붙습니다.
 - registry 가 답하지 않으면 화면은 목록을 비우지 않습니다. 직전 값과 함께 모름을 보입니다.
 - 손으로 띄운 compose 스택이 있으면 화면 시험이 시작할 때 그것을 볼륨째 내립니다. 같은 compose 프로젝트라서입니다. 데이터가 지워집니다. 남은 컨테이너는 `docker compose -f site/compose.yaml --env-file .env down -v` 로 걷습니다.
 - `site/robots.json` 에서 `humanoid-01` 은 `dock-3` 와 `bay-7` 을 티칭했고, `quadruped-01` 은 티칭하지 않았습니다. 그래서 `quadruped-01` 은 명칭을 기록하면 기체가 아는 명칭 없음으로 막힙니다.
diff --git a/ui/e2e/lifecycle.spec.ts b/ui/e2e/lifecycle.spec.ts
index eeb9526..198ef80 100644
--- a/ui/e2e/lifecycle.spec.ts
+++ b/ui/e2e/lifecycle.spec.ts
@@ -14,6 +14,9 @@ import { fileURLToPath } from 'node:url'
  * 바꾸지 못하며, 기체 상세가 버전 2 의 기준으로 판정한다.
  * 이어서 S3a 의 화면 쪽(S3a 스펙 §3). 운영자 모드로 «운영» 영역에서 InspectAsset 작업 지시를 내면 시운전을 마친 humanoid-01 에
  * 배정되고 실행 목록에 «코드 정의» 행이 보인다. 실행 호스트는 실제 시각을 쓰고 런처가 가상 시계를 실제 시각까지 따라잡게 민다.
+ * 이어서 S3b 의 화면 쪽(S3b 스펙 §3). 엔지니어 모드로 «임무·정책» 영역에서 데이터 정의 템플릿을 불러와 초안 저장 → 검증 → 모의
+ * 실행 → 활성화(사유)하면 버전 이력에 «버전 1 (활성)» 이 보인다. 운영 영역의 셀 대역 신호 표에서 rack_present 를 켜면 신호 조작
+ * 결과와 신호 값이 보인다.
  * registry 를 멈추는 것은 맨 끝이다. 그 뒤로는 조작이 registry 에 닿지 않는다.
  *
  * 선언 직후의 CLAIMED 는 여기서 단언하지 않는다. 실시간 1:1 시계에서는 다음 보고가 1초 안에 올 수도 있어
@@ -168,6 +171,34 @@ test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 reg
   // 이동 20초와 점검 12초(±10%)를 실제 시간으로 돈다.
   await expect(run).toContainText('PHYSICALLY_DONE', { timeout: 90_000 })
 
+  // 임무·정책(S3b 스펙 §3). 엔지니어 모드로 데이터 정의를 초안 저장 → 검증 → 모의 실행 → 활성화한다. 시운전 완료 기체는
+  // humanoid-01 하나이고 pick_place 를 가지므로 검증을 지난다. 모의 실행은 실행 호스트 안의 별도 mimic 으로 1~3초 돈다.
+  await page.getByLabel('엔지니어').check()
+  await page.getByRole('button', { name: '임무·정책' }).click()
+  const editor = page.getByRole('region', { name: '임무 편집' })
+  const missionNotice = editor.getByRole('status', { name: '임무 조작 결과' })
+  await editor.getByRole('button', { name: '데이터 정의 템플릿 불러오기' }).click()
+  await editor.getByRole('button', { name: '초안 저장' }).click()
+  await expect(missionNotice).toHaveText('초안 저장: 초안 1 저장됨')
+  await editor.getByRole('button', { name: '검증', exact: true }).click()
+  await expect(missionNotice).toHaveText('초안 1 검증: 통과')
+  await editor.getByRole('button', { name: '모의 실행', exact: true }).click()
+  await expect(missionNotice).toContainText('초안 1 모의 실행: 통과')
+  await editor.getByLabel('활성화 사유').fill('데이터 정의로 옮김')
+  await editor.getByRole('button', { name: '활성화', exact: true }).click()
+  await expect(missionNotice).toHaveText('초안 1 활성화: 버전 1 활성화됨. 다음 작업 지시부터 이 버전을 씁니다')
+  const missionVersions = page.getByRole('table', { name: '임무 버전 이력' })
+  await expect(missionVersions.getByRole('row', { name: /^버전 1 \(활성\) 초안 1 local 데이터 정의로 옮김 / })).toBeVisible()
+
+  // 셀 대역 신호(S3b 스펙 §8). 사람이 PLC 역할을 하는 정상 조작이라 운영자 모드에서도 한다.
+  await page.getByLabel('운영자').check()
+  await page.getByRole('button', { name: '운영', exact: true }).click()
+  const signals = page.getByRole('table', { name: '셀 대역 신호' })
+  await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN false / })).toBeVisible()
+  await signals.getByRole('button', { name: 'rack_present 켜기' }).click()
+  await expect(page.getByRole('status', { name: '신호 조작 결과' })).toHaveText('rack_present 켜기: 반영됨(값 true)')
+  await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN true / })).toBeVisible()
+
   // registry 를 멈춘다. 런처(registry 와 mimic 이 든 프로세스)를 끈다.
   const pidFile = fileURLToPath(new URL('../../build/site.pid', import.meta.url))
   process.kill(Number(readFileSync(pidFile, 'utf8')))
````

- [ ] **Step 2: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && ./gradlew :site:installDist :ops-service:installDist :mission-host:installDist -q && cd ui && npx playwright test
```
Expected: Playwright 1 통과(약 2.5분). 백그라운드로 돌린다.

- [ ] **Step 3: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" && git add README.md ui/e2e/lifecycle.spec.ts && git commit -F - <<'EOF'
test(ui): Playwright 생애주기에 임무·정책 단계와 셀 대역 신호 조작 추가, README 갱신

- 엔지니어 모드 활성화 단계, 운영자 모드 신호 켜기
- README 의 실행 호스트 저장·임무·정책 영역·신호·한계

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s3b-cmp.sh" e2e/src/test/kotlin/dev/picasso/ops/e2e/ExecutionDriver.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/MissionVersionTest.kt e2e/build.gradle.kts e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/JobOrderTest.kt README.md ui/e2e/lifecycle.spec.ts
```
Expected: 7개 모두 `같음`.

## Chunk 2: 검증과 병합(컨트롤러)

### Task 7: 결함 주입, 새 클론 빌드, PR

- [ ] **Step 1: 트리 대조**

```bash
git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" diff --stat origin/main HEAD -- . ':!docs' | tail -1 && git -C "C:/Users/Eisen/AppData/Local/Temp/s3b" diff --stat d16b19b HEAD | tail -1
```
Expected: 두 줄의 파일 수와 줄 수가 같다. 그리고 워크트리 `HEAD` 의 코드 경로마다 스파이크 `HEAD` 와 `s3b-cmp.sh` 로 `같음`.

- [ ] **Step 2: 결함 주입 57건**

```bash
cd "C:/Users/Eisen/AppData/Local/Temp/s3b-inject" && S3B_ROOT="C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3b" PYTHONUTF8=1 python inject.py
```
Expected: 모두 `탐지`. 백그라운드로 돌린다(1~2시간). 같은 워크트리에서 다른 Gradle·npm 을 겹쳐 돌리지 않는다. 주입 목록(site S1~S5, 호스트 H1~H15, 운영 서비스 O1~O24, 화면 U1~U10, 통합 I1~I3)과 잡는 시험 이름은 실행기에 있다.

- [ ] **Step 3: 새 클론 전체 빌드와 Playwright**

워크트리 브랜치를 스크래치에 새로 클론해(서브모듈 포함) `./gradlew build`, ui 의 `npm ci && npm test && npm run lint && npx tsc -b && npm run build`, installDist 셋과 `npx playwright test` 를 백그라운드로 차례로 돌린다. Expected: site 32, mission-host 49, ops-service 199, e2e 44, vitest 99, Playwright 1, 실패 0.

- [ ] **Step 4: 실행 결과와 PR**

이 계획 끝에 «실행 결과» 절을 더한다(문장은 Codex·Fable 취합). 코드 커밋은 묶음별로 남긴다(스택 보존, 스쿼시 안 함). 푸시, PR, CI 를 한 번 확인한 뒤 `gh pr merge --merge`(`--delete-branch` 쓰지 않음).

## 실행 결과

- 수행: picasso-ops 워크트리에서 묶음 넷(Task 1·2, Task 3, Task 4, Task 5·6)을 하위 에이전트(Sonnet)가 수행, 블록은 계획에서 기계로 뽑아 둔 파일을 복사·적용, 묶음마다 커밋된 파일을 스파이크와 바이트 대조해 64개 모두 같음(26, 15, 16, 7), 워크트리와 스파이크의 코드 diff 가 같음(64개 파일, +7,444/-214), Expected 와 다른 곳 없음
- 결함 주입: 57건(site 5, 호스트 15, 운영 서비스 24, 화면 10, 통합 3) 모두 지정 시험이 탐지, 스파이크와 워크트리에서 두 번 실행
- 새 클론 빌드: Gradle 시험 324 실패 0(site 32, mission-host 49, ops-service 199, e2e 44), vitest 99, lint·tsc·build 통과, installDist 셋과 Playwright 1 통과(2.6분)
- 병합: 코드 커밋을 묶음별로 남겨(스택 보존, 스쿼시 없음) picasso-ops PR #11 로 올림
- 스파이크: 영역 넷(site·실행 호스트, 운영 서비스, 화면, 통합 시험·Playwright·README)을 하위 에이전트가 차례로 지음, JSON 계약 문서를 다음 영역의 입력으로 넘김, 스펙 검토 2회 중 1차의 막는 지적은 `pick_place` 를 가진 기체가 humanoid-01 하나라 버전 1 실행 중 둘째 작업 지시를 낼 수 없던 시나리오 순서(순서를 바꿈), 그 밖에 재조회 키·WorkMaster 대조·`INPUT_UNKNOWN`·안전 신호의 현장 집행·모의 실행 프로파일 경로·대체하는 S3a 계약 절, 계획 검토 1회가 잡은 것은 재조회가 처리보다 먼저 와서 반영 안 됨을 남기는 경합(처리 중 409 `REQUEST_IN_PROGRESS` 로 고침)·현장 신호 쓰기 요청 제한 1초를 4초로·화면의 다시 읽기 타이머 정리·개요 전 저장 막기·활성화 성공 때만 사유 지우기·용어, 모두 스파이크에 되먹임(`2383ac3`)
- 걸린 것: 계획 틀 스크립트의 커밋 메시지 문자열에 실제 개행이 들어가 스크립트로 고침, 계획 검토가 Task 3 의 시험 단계를 `:ops-service:check` 로 넓힘, Codex 사용 한도로 스펙·계획 커밋 문장은 Fable 초안만으로 씀
- 다음: S3c(미들웨어 시간값 셋과 인시던트의 현장 설정 버전), 그 뒤 S4(장애 주입, 인시던트, 보류 해소), picasso 한계의 스키마 파일 행이 낡는다는 메모를 picasso 쪽에 넘김
