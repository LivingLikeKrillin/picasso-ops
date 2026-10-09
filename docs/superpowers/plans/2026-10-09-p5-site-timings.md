# P5 현장 시간값 주입과 인시던트의 현장 설정 버전 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking. 단, 이 계획은 묶음 하나(Task 1·2)를 구현자 하나가 하고, 검토는 묶음이 끝난 뒤 컨트롤러가 기계 대조와 시험으로 한다.

**Goal:** 미들웨어가 현장 시간값 한 세트(`evidenceWindow` 앞·뒤 폭, `inDoubtGrace`, `stallWindow`)와 그 설정 버전을 받아 pump 라운드마다 한 번 읽고 판정에 쓰며, 봉인하는 인시던트의 의도에 그 버전과 값을 싣는다. 값이 없으면 지금처럼 케이퍼빌리티 기본값으로 돈다.

**Architecture:** `Ports.kt` 끝에 `fun interface SiteTimingsSource`(기본 `NONE`)와 값 형 `SiteTimings`(허용 범위 상수 넷, `problems()`, `ofSeconds`)를 둔다. `Middleware` 생성자 마지막 인자 `siteTimings` 가 받고, `pump()` 첫 줄에서 라운드 스냅숏 `round` 를 한 번 채운다. 도우미 셋이 라운드 값이 있으면 그 값을, 없으면 케이퍼빌리티 getter 를 돌려주고 일곱 자리가 이를 읽는다. `IncidentLog.sealIncidents` 는 라운드 값을 인자로 받는다. `Intent` 의 새 칸 셋은 기본 null 이고, `digest()` 는 설정 버전이 있을 때만 한 줄을 더한다. 내보내기는 그대로다.

**Tech Stack:** Kotlin, JUnit5 + kotlin.test, 각본 더블(`ScriptedRobot`, 시험 파일 안), picasso 게이트(`DocumentClaimsTest`, `GroundTruthTest`), `tools/stamp.py`.

**근거 스펙:** picasso-ops `docs/superpowers/specs/2026-10-09-s3c-site-timings-design.md` §5(스펙 검토 2회). P5 는 S3c 앞의 picasso PR 하나다(스펙 §3).

**스펙이 계획에 맡긴 것과 이 계획이 정한 것(스파이크에서 정함):**
- 놓는 파일은 `Ports.kt`(생성자가 받는 포트가 모두 여기 있고 `seams.md` 색인이 이 파일을 가리킨다). `SiteTimings` 는 `ActiveMission` 처럼 포트 곁에 둔다.
- 생성자 인자 이름 `siteTimings`, 기본값 `SiteTimingsSource.NONE`, 마지막 자리.
- 허용 범위는 스펙의 제안 그대로: 앞 폭 5~120, 뒤 폭 5~120, `inDoubtGrace` 10~600, `stallWindow` 30~3600(초). 상한은 기본값의 4·8·10·12배. `stallWindow` 하한 30 은 기종 프로파일의 가장 긴 발행 간격, `inDoubtGrace` 하한 10 은 같은 참조로 다시 묻는 세 번을 마친 뒤에도 설비를 여러 번 읽을 폭. 상수는 `LongRange` 넷(`*_SECONDS`).
- `problems()` 는 `List<String>`, 문장은 칸 이름과 `:` 로 시작, 빈 목록이 통과. 버전 1 미만과 초 단위가 아닌 값도 잡는다. 생성자와 미들웨어는 검사하지 않는다(거르는 것은 소스의 일).
- 해시 줄은 의도 줄 바로 뒤에 `버전|앞|뒤|유예|정체`. 설정 버전이 없으면 줄 자체가 없다. `inDoubtGrace`·`stallWindow` 는 `Duration.toString()`(60초 배수는 `PT1M` 처럼 분으로 접힌다).
- 골든 요약: `HandoffFixtureTest.bundle().digest()` 를 `2ca7cd6` 에서 잰 값 `d511e96df06e6be67f0b4af8d73bef4a23aedab6253870c30908768358801682` 로 박는다.
- 한계 레지스터: 소비자 대기 행 `§15.212 · 현장 설정 버전 내보내기`. 해소 조건 칸에 «현장» 을 쓰면 `DocumentClaimsTest` 가 외부 하위 범주로 판정해 빨개지므로 «설정 버전» 으로만 쓴다. 오픈 항목 72 → 73, 소비자 대기 15 → 16.
- 실측: 새 시험 22(`SiteTimingsTest` 14, `SiteTimingsRangeTest` 7, `HandoffFixtureTest` 1), 전체 1,982 → 2,004(picasso 347 → 369). 결함 주입 17건.
- 계획 검토(1회)가 잡아 스파이크에 더한 것: 저장된 근거 기한과 그 라운드의 앞 폭이 함께 쓰이는 절반의 시험(주입 R5), 실행이 둘이어도 라운드에 한 번 읽음(주입 R6), `SiteTimingsSource` 를 부르는 맥락(소비자 잠금 아래, 막히지 않음, 안전한 게시)의 KDoc 과 `seams.md`, `Intent` 의 불변식(설정 버전이 없으면 유예·정체도 없음), «시간창» 용어 한 곳, §15.212 의 «1 미만의 버전».
- 문장: 커밋·PR·문서 문장은 Codex 와 Fable 초안을 취합한다.

**작업 위치 규칙(필수):**
- 모든 작업은 picasso 워크트리 `C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings`(브랜치 `feat/p5-site-timings`)에서 한다. picasso 메인 체크아웃(`C:/Users/Eisen/Desktop/Labs/[projects] picasso`, khala 가 docs/ 를 읽는다)과 다른 저장소는 건드리지 않는다. 하위 에이전트의 Bash 는 호출마다 작업 디렉터리가 돌아가므로 명령마다 `cd <워크트리> &&` 를 붙이거나 `git -C` 를 쓴다.
- `./gradlew --stop` 금지(데몬 풀이 다른 체크아웃과 공유된다). 같은 워크트리에서 Gradle 을 겹쳐 돌리지 않는다. Bash 도구의 시간 한도(600초)를 넘는 빌드는 백그라운드로 돌리고 끝났다는 알림을 받은 뒤 다음 Gradle 을 돌린다.
- `git add -A` 금지. 파일을 이름으로 더한다.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다. 아래 Expected 의 수는 `<testcase>` 수다. 수는 `PYTHONUTF8=1 python -c "import glob,xml.etree.ElementTree as E;print(sum(len(list(E.parse(f).getroot().iter('testcase'))) for f in glob.glob('C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings/MOD/build/test-results/test/*.xml')))"` 로 센다(MOD 자리에 모듈 이름).
- 이 저장소의 작업 트리는 CRLF 다. 새 파일은 CRLF 로 둔다(뽑아 둔 파일은 이미 CRLF). git bash 의 `sed -i` 는 CRLF 를 LF 로 바꾸므로 쓰지 않는다.
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. 커밋 메시지는 heredoc(`git commit -F - <<'EOF'`)으로 쓴다. 형식 훅이 제목이 `type(scope): 명사구` 가 아니거나 트레일러가 없거나 겹화살괄호가 있으면 막는다.
- 이 계획의 코드와 문서는 스크래치 스파이크(`C:/Users/Eisen/AppData/Local/Temp/p5`, 브랜치 `spike/p5`, HEAD `831d62e`)에서 시험, 전체 빌드, 결함 주입을 다 돌린 것이다. 묶음이 끝나면 커밋된 파일을 스파이크와 기계 대조한다(`p5-cmp.sh`).
- 실행 방식: 묶음(Task 1·2) 하나를 구현 하위 에이전트 1명(`model: "sonnet"`)이, 결함 주입(Task 3)과 검토·PR 은 컨트롤러가 한다.
- **블록을 손으로 옮겨 적지 않는다.** 컨트롤러가 이 계획의 블록을 기계로 뽑아 `C:/Users/Eisen/AppData/Local/Temp/p5-patches/` 에 두었다. 새 파일은 `C:/Users/Eisen/AppData/Local/Temp/p5-patches/files/<경로>` 를 워크트리의 같은 경로로 `cp` 하고, 기존 파일은 `C:/Users/Eisen/AppData/Local/Temp/p5-patches/<이름>.patch` 를 `git apply --check` 로 본 뒤 `git apply` 한다. 아래 블록은 읽고 검토하기 위한 것이다. 뽑은 파일이 없으면 멈추고 보고한다.
- 문서를 고친 커밋 뒤에는 docs/ 만 바뀌었어도 `:picasso:test`(`GroundTruthTest` 가 docs 전체를 훑는다)와 `:gate:test` 를 둘 다 돌린다. 도장은 패치에 이미 들어 있다.

---

## Chunk 1: 코드와 문서

### Task 0: 워크트리, 기준선, 대조 도구(컨트롤러)

**Files:** 없음(환경)

- [ ] **Step 1: 워크트리 만들기**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso"
git fetch -q origin
git worktree add "C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" -b feat/p5-site-timings origin/main
git -C "C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" branch --unset-upstream
git -C "C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" log --oneline -1
```
Expected: `origin/main` 이 `2ca7cd6`. 다르면 멈추고 보고한다.

- [ ] **Step 2: 기준선 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" && ./gradlew :picasso:test :gate:test -q
```
Expected: picasso 347, gate 276, 실패 0. 백그라운드로 돌린다.

- [ ] **Step 3: 대조 도구와 뽑은 블록**

`C:/Users/Eisen/AppData/Local/Temp/p5-cmp.sh` 와 `C:/Users/Eisen/AppData/Local/Temp/p5-patches/`(패치 2개, `files/` 아래 새 파일 2개)가 있는지 본다. 대조 도구는 인자로 받은 경로마다 워크트리 HEAD 의 파일과 스파이크 HEAD 의 파일을 `\r` 을 빼고 바이트 대조해 `같음`/`다름` 을 찍는다. 없으면 멈추고 보고한다.

### Task 1: 현장 시간값 주입과 인시던트의 설정 버전

**Files:**
- Create: `picasso/src/test/kotlin/dev/picasso/middleware/SiteTimingsTest.kt`, `picasso/src/test/kotlin/dev/picasso/middleware/SiteTimingsRangeTest.kt`
- Modify: `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt`, `picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt`, `picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt`, `picasso/src/main/kotlin/dev/picasso/middleware/Incident.kt`, `picasso/src/test/kotlin/dev/picasso/middleware/HandoffFixtureTest.kt`

- [ ] **Step 1: 시험 파일 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" && cp "C:/Users/Eisen/AppData/Local/Temp/p5-patches/files/picasso/src/test/kotlin/dev/picasso/middleware/SiteTimingsTest.kt" "C:/Users/Eisen/AppData/Local/Temp/p5-patches/files/picasso/src/test/kotlin/dev/picasso/middleware/SiteTimingsRangeTest.kt" picasso/src/test/kotlin/dev/picasso/middleware/
```

```kotlin
package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ProgressBasis
import dev.picasso.contracts.v1.ProgressKind
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **현장 시간값이 케이퍼빌리티 기본값을 덮는다**(§15.212).
 *
 * 미들웨어가 시간값을 읽는 자리는 일곱이다 — 정체 판정, `IN_DOUBT` 의 근거 윈도우와 유예, 완료 뒤 근거 기한, 근거 판정 둘,
 * 인시던트 봉인의 근거 윈도우. 자리마다 현장 값과 케이퍼빌리티 값이 다른 결과를 내도록 값을 골라, 어느 한 자리가 케이퍼빌리티
 * getter 로 돌아가도 그 자리의 시험이 빨개지게 한다.
 *
 * 현장 값 [SITE] 는 앞 100초 · 뒤 60초 · 유예 20초 · 정체 40초이고, 케이퍼빌리티 기본값은 30 · 15 · 60 · 300초다.
 *
 * 기체는 각본대로 답하는 더블이고 시계는 시험이 민다. 미믹을 안 쓰는 것은 완료 시각과 신호 시각을 초 단위로 맞추기 위해서다.
 */
class SiteTimingsTest {

    // ── 일곱 자리

    @Test
    fun `현장 정체 유예가 진행 정체 판정에 쓰인다`() {
        World(SITE).use { w ->
            val exec = w.submit()
            w.mw.pump()
            w.robot.push(w.task, TaskState.TASK_STATE_RUNNING, progress = 0.3)
            w.mw.pump()
            w.advance(45)
            w.mw.pump()

            // 현장 40초면 45초 뒤 정체다. 케이퍼빌리티 300초면 아직 아니다.
            assertTrue(exec.unit().progressStalled, "현장 정체 유예(40초)를 안 썼다: ${exec.unit().note}")
        }
    }

    @Test
    fun `현장 앞 폭이 IN_DOUBT 의 물리 관측에 쓰인다`() {
        World(SITE, lookup = ExecutionLookup.NONE).use { w ->
            w.robot.loseStart = true
            w.cell.programAt(SLOT, PART, at = w.t.minusSeconds(60))
            val exec = w.submit()
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)

            w.mw.pump()
            // 요청 60초 전의 신호는 현장 앞 폭(100초) 안이라 잠정 완료다. 케이퍼빌리티 앞 폭(30초)이면 유예를 기다린다.
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state, "현장 앞 폭을 안 썼다: ${exec.unit().note}")
            assertEquals(Verification.MATCHED, exec.unit().verification)
        }
    }

    @Test
    fun `현장 IN_DOUBT 유예가 운영자 대기 시점을 정한다`() {
        World(SITE, lookup = ExecutionLookup.NONE).use { w ->
            val exec = w.inDoubt()
            w.advance(30)
            w.mw.pump()

            // 현장 유예 20초가 지났다. 케이퍼빌리티 유예(60초)면 아직 IN_DOUBT 다.
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state, "현장 유예를 안 썼다: ${exec.unit().note}")
            assertTrue(exec.unit().note!!.contains("within grace"), exec.unit().note)
        }
    }

    @Test
    fun `완료 뒤 근거 기한이 현장 뒤 폭으로 정해진다`() {
        World(SITE).use { w ->
            val exec = w.submit()
            w.mw.pump()
            val doneAt = w.t
            w.robot.push(w.task, TaskState.TASK_STATE_SUCCEEDED)
            w.mw.pump()

            assertEquals(UnitState.VERIFYING, exec.unit().state)
            assertEquals(doneAt.plusSeconds(60), exec.unit().evidenceDeadline, "근거 기한이 현장 뒤 폭(60초)이 아니다")
        }
    }

    @Test
    fun `현장 앞 폭이 완료의 근거 판정에 쓰인다`() {
        World(SITE).use { w ->
            val exec = w.submit()
            w.mw.pump()
            w.cell.programAt(SLOT, PART, at = w.t.minusSeconds(60))
            w.robot.push(w.task, TaskState.TASK_STATE_SUCCEEDED)
            w.mw.pump()

            // 완료 60초 전의 신호는 현장 앞 폭(100초) 안이다. 케이퍼빌리티 앞 폭(30초)이면 옛 신호라 세지 않는다.
            assertEquals(UnitState.DONE, exec.unit().state, "현장 앞 폭을 안 썼다: ${exec.unit().note}")
            assertEquals(Evidence.E2, exec.unit().reached)
        }
    }

    @Test
    fun `현장 앞 폭이 실패 보고 뒤의 근거 판정에 쓰인다`() {
        World(SITE).use { w ->
            val exec = w.submit()
            w.mw.pump()
            w.cell.programAt(SLOT, PART, at = w.t.minusSeconds(60))
            w.robot.push(w.task, TaskState.TASK_STATE_FAILED)
            w.mw.pump()

            // 하위는 실패라는데 설비에는 현장 앞 폭 안의 신호가 있다. 케이퍼빌리티 앞 폭이면 그냥 실패다.
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state, "현장 앞 폭을 안 썼다: ${exec.unit().note}")
        }
    }

    @Test
    fun `인시던트 의도에 봉인 라운드의 설정 버전과 시간값 넷이 실린다`() {
        World(SITE, lookup = ExecutionLookup.NONE).use { w ->
            val exec = w.inDoubt()
            w.advance(30)
            w.mw.pump()
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state)

            val intent = w.mw.incidents().single().intent
            assertEquals(7L, intent.siteSettingsVersion)
            assertEquals("PT1M40S", intent.evidenceWindowBefore, "봉인의 근거 윈도우가 현장 앞 폭이 아니다")
            assertEquals("PT1M", intent.evidenceWindowAfter, "봉인의 근거 윈도우가 현장 뒤 폭이 아니다")
            assertEquals("PT20S", intent.inDoubtGrace)
            assertEquals("PT40S", intent.stallWindow)
        }
    }

    // ── 값이 없을 때

    @Test
    fun `현장 값이 없으면 케이퍼빌리티 값으로 판정하고 설정 칸은 비어 있다`() {
        // 케이퍼빌리티가 현장 값과 같은 수를 들게 한다. 소스를 안 주면 이 값으로 판정해야 한다.
        val tuned = Tuned(PrepareSequencedRack())
        var t = T0
        val robot = ScriptedRobot { t }.also { it.executionLookup = ExecutionLookup.NONE; it.loseStart = true }
        val mw = Middleware(robot, CellMimic(now = { t }), capabilities = listOf(tuned), now = { t })
        val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order(), ROBOT)).execution
        mw.pump()
        assertEquals(UnitState.IN_DOUBT, exec.unit().state)
        t = t.plusSeconds(30)
        mw.pump()

        assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state, "케이퍼빌리티 유예(20초)를 안 썼다: ${exec.unit().note}")
        val intent = mw.incidents().single().intent
        assertNull(intent.siteSettingsVersion, "현장 값이 없는데 설정 버전이 실렸다")
        assertNull(intent.inDoubtGrace)
        assertNull(intent.stallWindow)
        assertEquals("PT1M40S", intent.evidenceWindowBefore)
        assertEquals("PT1M", intent.evidenceWindowAfter)
    }

    // ── 적용 시점

    @Test
    fun `한 라운드 안에서는 소스를 한 번만 읽는다`() {
        // 읽을 때마다 버전이 오르는 소스. 라운드마다 한 번 읽으면 봉인한 버전이 그 라운드의 번호다.
        World(null, lookup = ExecutionLookup.NONE).use { w ->
            w.source = SiteTimingsSource {
                w.reads += 1
                SiteTimings.ofSeconds(w.reads.toLong(), 100, 60, 20, 40)
            }
            val exec = w.inDoubt()
            // 다른 기체로 실행을 하나 더 세운다. 실행이 둘이어도 라운드에 한 번 읽어야 한다.
            val other = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(OTHER_ORDER, OTHER_SLOT, OTHER_BIN), OTHER_ROBOT)).execution
            w.pump()
            assertEquals(UnitState.IN_DOUBT, other.unit().state)
            w.advance(30)
            w.pump()
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state)
            assertEquals(UnitState.OPERATOR_HOLD, other.unit().state)

            assertEquals(w.pumps, w.reads, "한 라운드에 소스를 여러 번 읽었다")
            assertEquals(
                listOf(w.pumps.toLong(), w.pumps.toLong()),
                w.mw.incidents().map { it.intent.siteSettingsVersion },
                "봉인이 그 라운드의 값이 아니다",
            )
        }
    }

    @Test
    fun `바꾼 IN_DOUBT 유예가 이미 IN_DOUBT 인 단위에 다음 라운드부터 미친다`() {
        World(SiteTimings.ofSeconds(1, 30, 15, 600, 300), lookup = ExecutionLookup.NONE).use { w ->
            val exec = w.inDoubt()
            w.advance(30)
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state, "유예 600초 안인데 운영자에게 갔다")

            w.timings = SiteTimings.ofSeconds(2, 30, 15, 20, 300)
            w.mw.pump()
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state, "바꾼 유예가 도는 단위에 안 미쳤다")
            assertEquals(2L, w.mw.incidents().single().intent.siteSettingsVersion)
        }
    }

    @Test
    fun `저장된 근거 기한은 뒤 폭이 바뀌어도 그대로다`() {
        World(SiteTimings.ofSeconds(1, 30, 60, 60, 300)).use { w ->
            val exec = w.submit()
            w.mw.pump()
            val doneAt = w.t
            w.robot.push(w.task, TaskState.TASK_STATE_SUCCEEDED)
            w.mw.pump()
            assertEquals(doneAt.plusSeconds(60), exec.unit().evidenceDeadline)

            // 뒤 폭을 20초로 줄인다. 이미 완료된 단위의 기한은 완료 순간의 값(60초)이다.
            w.timings = SiteTimings.ofSeconds(2, 30, 20, 60, 300)
            w.advance(30)
            w.mw.pump()
            assertEquals(doneAt.plusSeconds(60), exec.unit().evidenceDeadline, "근거 기한을 다시 계산했다")
            assertEquals(UnitState.VERIFYING, exec.unit().state, "줄인 뒤 폭으로 기한을 당겼다")
        }
    }

    @Test
    fun `저장된 근거 기한과 그 라운드의 앞 폭이 함께 쓰인다`() {
        // 완료된 단위의 판정은 저장된 기한(완료 순간의 뒤 폭)과 그 라운드의 앞 폭을 함께 쓴다(§15.212).
        World(SiteTimings.ofSeconds(1, 30, 60, 60, 300)).use { w ->
            val exec = w.submit()
            w.mw.pump()
            val doneAt = w.t
            w.cell.programAt(SLOT, PART, at = doneAt.minusSeconds(60))
            w.robot.push(w.task, TaskState.TASK_STATE_SUCCEEDED)
            w.mw.pump()
            // 완료 60초 전의 신호는 앞 폭 30초 밖이라 아직 근거가 아니다.
            assertEquals(UnitState.VERIFYING, exec.unit().state)

            // 앞 폭만 100초로 늘린다. 뒤 폭은 그대로라 기한도 그대로다.
            w.timings = SiteTimings.ofSeconds(2, 100, 60, 60, 300)
            w.mw.pump()
            assertEquals(doneAt.plusSeconds(60), exec.unit().evidenceDeadline)
            assertEquals(UnitState.DONE, exec.unit().state, "그 라운드의 앞 폭을 안 썼다: ${exec.unit().note}")
            assertEquals(Evidence.E2, exec.unit().reached)
        }
    }

    // ── 해시와 내보내기

    @Test
    fun `설정 버전과 시간값이 해시에 든다`() {
        World(SITE, lookup = ExecutionLookup.NONE).use { w ->
            w.inDoubt()
            w.advance(30)
            w.mw.pump()
            val b = w.mw.incidents().single()
            val i = b.intent
            val changed = mapOf(
                "설정 버전" to i.copy(siteSettingsVersion = 8),
                "앞 폭" to i.copy(evidenceWindowBefore = "PT1M"),
                "뒤 폭" to i.copy(evidenceWindowAfter = "PT2M"),
                "IN_DOUBT 유예" to i.copy(inDoubtGrace = "PT21S"),
                "정체 유예" to i.copy(stallWindow = "PT41S"),
            )
            changed.forEach { (what, other) ->
                assertNotEquals(b.digest(), b.copy(intent = other).digest(), "$what 가 해시에 안 들어갔다")
            }
        }
    }

    @Test
    fun `현장 설정 칸은 내보내기에 안 실리고 내보내기 버전은 6 이다`() {
        World(SITE, lookup = ExecutionLookup.NONE).use { w ->
            w.inDoubt()
            w.advance(30)
            w.mw.pump()
            val line = LedgerExport.incidents(w.mw.incidents())
            listOf("siteSettingsVersion", "inDoubtGrace", "stallWindow").forEach {
                assertTrue(it !in line, "$it 가 내보내기에 실렸다: $line")
            }
            assertTrue("\"evidenceWindowBefore\":\"PT1M40S\"" in line, line)
            assertEquals("6", LedgerExport.SCHEMA_VERSION, "읽는 쪽이 없는데 내보내기 버전이 올랐다(ADR 9)")
        }
    }

    // ── 더블

    private class World(timings: SiteTimings?, lookup: ExecutionLookup = ExecutionLookup.CLIENT_REFERENCE) : AutoCloseable {
        var t: Instant = T0
        val robot = ScriptedRobot { t }.also { it.executionLookup = lookup }
        val cell = CellMimic(now = { t })
        var timings: SiteTimings? = timings
        var source: SiteTimingsSource = SiteTimingsSource { this.timings }
        var reads = 0
        var pumps = 0
        val mw = Middleware(robot, cell, now = { t }, siteTimings = { source.current() })
        private var order = order()
        val task get() = "${order.jobOrderId}#$SLOT"

        fun advance(seconds: Long) {
            t = t.plusSeconds(seconds)
        }

        fun submit(o: JobOrder = order()): Middleware.Execution {
            order = o
            return assertIs<Middleware.Submission.Accepted>(mw.submit(o, ROBOT)).execution
        }

        /** 시작 응답을 잃어 `IN_DOUBT` 에 선 단위. 신호는 없다. */
        fun inDoubt(): Middleware.Execution {
            robot.loseStart = true
            val exec = submit()
            pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            return exec
        }

        /** 부른 수를 센다. 소스를 읽은 수와 맞대기 위해서다. */
        fun pump() {
            pumps += 1
            mw.pump()
        }

        override fun close() = Unit
    }

    /** 각본대로 답하는 기체. 시험이 태스크의 갱신을 넣는다. 스냅숏은 늘 온라인이고 결함이 없다. */
    private class ScriptedRobot(private val now: () -> Instant) : RobotPort {
        override var executionLookup: ExecutionLookup = ExecutionLookup.CLIENT_REFERENCE
        var loseStart = false
        private val updates = mutableMapOf<String, MutableList<WatchTaskResponse>>()
        private val revisions = mutableMapOf<String, Int>()
        private var index = 0L

        fun push(taskId: String, state: TaskState, progress: Double = 0.0) {
            updates.getOrPut(taskId) { mutableListOf() } += WatchTaskResponse.newBuilder()
                .setHeader(MessageHeader.newBuilder().setStateAsOf(now().toString()).setUpdateIndex(index++))
                .setState(state)
                .setRevision(revisions.getValue(taskId))
                .setProgress(progress)
                .setProgressBasis(ProgressBasis.newBuilder().setKind(ProgressKind.PROGRESS_KIND_MEASURED).setBasis("행동 1/3"))
                .build()
        }

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            revisions[taskId] = revision
            if (loseStart) throw IllegalStateException("요청은 닿았고 응답을 잃었다")
            return StartTaskResponse.newBuilder()
                .setHandle(TaskHandle.newBuilder().setTaskId(taskId).setRevision(revision).setRobotId(robotId))
                .build()
        }

        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = updates[handle.taskId].orEmpty().toList()
        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = CancelTaskResponse.getDefaultInstance()
        override fun snapshot(robotId: String): RobotSnapshot =
            RobotSnapshot(0, emptyList(), ConnectionState.CONNECTION_STATE_ONLINE, emptyMap())
        override fun replay(robotId: String, from: Long): Replay = Replay.Events(emptyList())
    }

    /** 현장 값 [SITE] 와 같은 수를 기본값으로 드는 케이퍼빌리티. 소스가 없을 때 이 값이 쓰이는지 본다. */
    private class Tuned(inner: LogicalCapability) : LogicalCapability by inner {
        override val evidenceWindow: EvidenceWindow get() = EvidenceWindow(Duration.ofSeconds(100), Duration.ofSeconds(60))
        override val inDoubtGrace: Duration get() = Duration.ofSeconds(20)
        override val stallWindow: Duration get() = Duration.ofSeconds(40)
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val SLOT = "RACK-212.S01"
        const val BIN = "SEQ-IN-02.BIN-A"
        const val PART = "ENGINE-COVER-A"
        val T0: Instant = Instant.parse("2026-10-09T00:00:00Z")

        /** 현장 값. 케이퍼빌리티 기본값(30 · 15 · 60 · 300)과 칸마다 다르다. */
        val SITE: SiteTimings = SiteTimings.ofSeconds(7, 100, 60, 20, 40)

        /** 둘째 실행 — 다른 기체, 다른 자리. */
        const val OTHER_ROBOT = "hum-03"
        const val OTHER_ORDER = "SEQ-213"
        const val OTHER_SLOT = "RACK-213.S01"
        const val OTHER_BIN = "SEQ-IN-03.BIN-A"

        fun order(jobOrderId: String = "SEQ-212", slot: String = SLOT, bin: String = BIN) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            equipmentRequirements = listOf(
                EquipmentRequirement(slot, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
                EquipmentRequirement(bin, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
            ),
        )

        fun Middleware.Execution.unit() = units.single()
    }
}
```

```kotlin
package dev.picasso.middleware

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **허용 범위는 라이브러리가 쥔다**(운영 관리 화면 설계 제안 §11 결정 2).
 *
 * 칸마다 하한과 상한에서 한 초 안팎을 잰다. 범위 밖이면 문장이 그 칸 이름으로 시작해야 한다. 쓰는 쪽(실행 호스트)이 그 문장을
 * «적용 안 한 버전과 이유» 로 그대로 보이기 때문이다.
 */
class SiteTimingsRangeTest {

    private val ok = SiteTimings.ofSeconds(1, 30, 15, 60, 300)

    /** 한 칸만 바꾼 값의 문제 목록. */
    private fun problems(field: String, seconds: Long): List<String> = when (field) {
        "evidenceWindowBefore" -> ok.copy(evidenceWindowBefore = Duration.ofSeconds(seconds))
        "evidenceWindowAfter" -> ok.copy(evidenceWindowAfter = Duration.ofSeconds(seconds))
        "inDoubtGrace" -> ok.copy(inDoubtGrace = Duration.ofSeconds(seconds))
        "stallWindow" -> ok.copy(stallWindow = Duration.ofSeconds(seconds))
        else -> error(field)
    }.problems()

    private fun assertBounds(field: String, range: LongRange) {
        assertEquals(emptyList(), problems(field, range.first), "$field 하한 ${range.first} 을 막았다")
        assertEquals(emptyList(), problems(field, range.last), "$field 상한 ${range.last} 을 막았다")
        listOf(range.first - 1, range.last + 1).forEach { outside ->
            val found = problems(field, outside)
            assertEquals(1, found.size, "$field = $outside 를 안 잡았다: $found")
            assertTrue(found.single().startsWith("$field:"), found.single())
        }
    }

    @Test
    fun `앞 폭의 하한과 상한 밖을 잡는다`() = assertBounds("evidenceWindowBefore", 5L..120L)

    @Test
    fun `뒤 폭의 하한과 상한 밖을 잡는다`() = assertBounds("evidenceWindowAfter", 5L..120L)

    @Test
    fun `IN_DOUBT 유예의 하한과 상한 밖을 잡는다`() = assertBounds("inDoubtGrace", 10L..600L)

    @Test
    fun `정체 유예의 하한과 상한 밖을 잡는다`() = assertBounds("stallWindow", 30L..3600L)

    @Test
    fun `범위 상수가 시험이 잰 범위와 같다`() {
        // 운영 서비스가 사본을 두고 통합 시험이 이 상수와 대조한다. 상수가 움직이면 그쪽도 움직여야 한다.
        assertEquals(5L..120L, SiteTimings.EVIDENCE_WINDOW_BEFORE_SECONDS)
        assertEquals(5L..120L, SiteTimings.EVIDENCE_WINDOW_AFTER_SECONDS)
        assertEquals(10L..600L, SiteTimings.IN_DOUBT_GRACE_SECONDS)
        assertEquals(30L..3600L, SiteTimings.STALL_WINDOW_SECONDS)
    }

    @Test
    fun `초 단위가 아닌 값과 1 보다 작은 버전을 잡는다`() {
        val fractional = ok.copy(inDoubtGrace = Duration.ofMillis(60_500)).problems()
        assertEquals(1, fractional.size, "$fractional")
        assertTrue(fractional.single().startsWith("inDoubtGrace:"), fractional.single())

        val unversioned = ok.copy(siteSettingsVersion = 0).problems()
        assertEquals(1, unversioned.size, "$unversioned")
        assertTrue(unversioned.single().startsWith("siteSettingsVersion:"), unversioned.single())
    }

    @Test
    fun `케이퍼빌리티 기본값이 허용 범위 안이다`() {
        // 현장 설정의 첫 버전은 이 기본값으로 채운다. 기본값이 범위 밖이면 첫 버전부터 적용되지 않는다.
        MissionCatalog.codeCapabilities().forEach { c ->
            val defaults = SiteTimings(1, c.evidenceWindow.before, c.evidenceWindow.after, c.inDoubtGrace, c.stallWindow)
            assertEquals(emptyList(), defaults.problems(), c.workMasterId)
        }
        assertEquals(emptyList(), ok.problems())
    }
}
```

- [ ] **Step 2: 시험이 컴파일되지 않음을 본다**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" && ./gradlew :picasso:compileTestKotlin -q
```
Expected: 실패. `SiteTimings`·`SiteTimingsSource` 를 찾지 못한다는 오류.

- [ ] **Step 3: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/p5-patches/task1.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/p5-patches/task1.patch"
```

```diff
diff --git a/picasso/src/main/kotlin/dev/picasso/middleware/Incident.kt b/picasso/src/main/kotlin/dev/picasso/middleware/Incident.kt
index ef90beb..ce8c100 100644
--- a/picasso/src/main/kotlin/dev/picasso/middleware/Incident.kt
+++ b/picasso/src/main/kotlin/dev/picasso/middleware/Incident.kt
@@ -31,7 +31,10 @@ data class Intent(
      * 도달 등급이 낮은 이유가 «능력의 한계» 인지 «이번에 못 받았다» 인지를 이것이 가른다.
      */
     val capabilityMaxEvidence: Evidence,
-    /** 유효 시간창의 폭. `evidenceWindow` 가 **왜 그 범위였는지**가 이 둘이다. ISO-8601. */
+    /**
+     * 유효 시간 윈도우의 폭. `evidenceWindow` 가 **왜 그 범위였는지**가 이 둘이다. ISO-8601.
+     * 현장 시간값이 있으면 봉인하는 라운드의 그 값이고, 없으면 케이퍼빌리티 값이다.
+     */
     val evidenceWindowBefore: String,
     val evidenceWindowAfter: String,
     /** 이 단위가 하려던 스킬. */
@@ -51,7 +54,26 @@ data class Intent(
      * 아직 없다(ADR 9). 읽는 쪽이 생기는 날 내보내기 버전을 올려 싣는다.
      */
     val missionVersion: Int? = null,
-)
+    /**
+     * 봉인하는 라운드에 적용한 **현장 설정의 버전**. 현장 시간값 없이 돌면 널이다. 어느 버전의 설정에서 난 인시던트인지
+     * 되짚는 자리다(운영 관리 화면 설계 제안 §9).
+     *
+     * **해시에는 이 값이 있을 때만 줄 하나가 든다** — 그래서 설정 없이 도는 인시던트의 요약은 이 칸이 생기기 전과 같다.
+     * **내보내기에는 안 실린다**([missionVersion] 과 같은 이유, ADR 9).
+     */
+    val siteSettingsVersion: Long? = null,
+    /** 봉인하는 라운드의 `IN_DOUBT` 유예. ISO-8601. [siteSettingsVersion] 이 널이면 널이다. */
+    val inDoubtGrace: String? = null,
+    /** 봉인하는 라운드의 진행 정체 유예. ISO-8601. [siteSettingsVersion] 이 널이면 널이다. */
+    val stallWindow: String? = null,
+) {
+    init {
+        // 시간값 둘은 설정 버전과 함께만 선다. 버전 없이 값만 있으면 해시에 안 들어 같은 요약의 두 인시던트가 생긴다.
+        require(siteSettingsVersion != null || (inDoubtGrace == null && stallWindow == null)) {
+            "설정 버전 없이 현장 시간값을 실었다: inDoubtGrace=$inDoubtGrace, stallWindow=$stallWindow"
+        }
+    }
+}
 
 /**
  * **관측을 얼마나 믿을 수 있나**(§15.178).
@@ -305,6 +327,10 @@ data class IncidentBundle(
                     "${intent.source.orEmpty()}|${intent.destination.orEmpty()}|${intent.expectedIdentity.orEmpty()}|" +
                     intent.missionVersion?.toString().orEmpty(),
             )
+            // 현장 설정이 있을 때만 한 줄. 없을 때 줄을 더하면 설정 없이 도는 모든 요약이 바뀐다(인계 번들 포함).
+            intent.siteSettingsVersion?.let {
+                appendLine("$it|${intent.evidenceWindowBefore}|${intent.evidenceWindowAfter}|${intent.inDoubtGrace}|${intent.stallWindow}")
+            }
             appendLine(
                 "${observation.linkBroken}|${observation.progressObservable}|${observation.progressStalled}|" +
                     observation.lateEvents.joinToString(";") {
diff --git a/picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt b/picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt
index 544071f..b0b8466 100644
--- a/picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt
+++ b/picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt
@@ -78,12 +78,14 @@ internal class IncidentLog(
      * 라운드 끝에 번들을 봉한다. **전이 순간이 아니다** — 단위가 닫히는 그 자리에서는 실행 수준의
      * 사실(무엇이 다음 단위를 막는가)이 아직 안 정해져 있고, 그것 없이 묶으면 번들이 "단위의 문제인지
      * 기체의 문제인지" 를 가르지 못한다(설계안 §4.2 넷째 줄).
+     *
+     * @param timings 봉인하는 라운드의 현장 시간값. 널이면 케이퍼빌리티 값이다. 근거 시간 윈도우와 의도의 시간값이 이것에서 나온다.
      */
-    fun sealIncidents(execution: Execution) {
+    fun sealIncidents(execution: Execution, timings: SiteTimings?) {
         if (execution.pendingIncidents.isEmpty()) return
         val at = now()
         val wall = wallClock()
-        val window = execution.capability.evidenceWindow
+        val window = timings?.evidenceWindow ?: execution.capability.evidenceWindow
         val inWindow = execution.eventTrail.filter { within(it, at.minus(window.before), at.plus(window.after)) }
         execution.pendingIncidents.forEach { unitId ->
             val unit = execution.units.firstOrNull { it.unitId == unitId } ?: return@forEach
@@ -131,6 +133,10 @@ internal class IncidentLog(
                     expectedIdentity = unit.expectedIdentity,
                     // **실행이 쥔 버전이다** — 지금 활성인 버전이 아니다. 활성화가 실행 도중에 끼어도 이 실행은 옛 버전으로 돈다.
                     missionVersion = execution.missionVersion,
+                    // 봉인하는 라운드의 값이다. 근거 기한을 정한 값(단위가 완료된 라운드의 뒤 폭)과 다를 수 있다.
+                    siteSettingsVersion = timings?.siteSettingsVersion,
+                    inDoubtGrace = timings?.inDoubtGrace?.toString(),
+                    stallWindow = timings?.stallWindow?.toString(),
                 ),
                 observation = ObservationTrust(
                     linkBroken = execution.linkBroken,
diff --git a/picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt b/picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt
index 76e397b..bb3e506 100644
--- a/picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt
+++ b/picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt
@@ -108,6 +108,13 @@ class Middleware(
      * 그래서 활성 버전이 바뀌어도 도는 실행은 옛 버전으로 끝난다.
      */
     missions: MissionCatalog? = null,
+    /**
+     * 현장 시간값의 출처. 기본값은 «없음» 이고, 그때는 케이퍼빌리티 값으로 판정한다.
+     *
+     * [pump] 가 시작할 때 한 번 읽어 그 라운드의 모든 판정에 같은 값을 쓴다. 바꾼 값은 다음 pump 부터다. 단위에 저장하는 값은
+     * 근거 기한(완료 시각 + 뒤 폭) 하나뿐이라, 바꾼 `inDoubtGrace` 는 이미 `IN_DOUBT` 인 단위에도 다음 라운드부터 미친다.
+     */
+    private val siteTimings: SiteTimingsSource = SiteTimingsSource.NONE,
 ) {
     // ⚠ 생성자 인자 `missions`(널 허용)가 이 속성을 가린다 — 다른 속성의 초기화식에서 `missions` 는 인자다. 거기서는 `this.missions` 로 쓴다.
     private val missions: MissionCatalog = run {
@@ -554,8 +561,25 @@ class Middleware(
 
     // ── 구동
 
+    /**
+     * 이번 라운드의 현장 시간값. [pump] 가 시작할 때 [siteTimings] 를 한 번 읽어 둔다. 널이면 케이퍼빌리티 값이다.
+     *
+     * 라운드 안에서는 다시 읽지 않는다. 판정마다 읽으면 한 라운드 안에서 두 버전의 값이 섞인다.
+     */
+    private var round: SiteTimings? = null
+
+    /** 이 라운드의 근거 시간 윈도우. 현장 값이 있으면 그것, 없으면 케이퍼빌리티 값이다. */
+    private fun windowOf(execution: Execution): EvidenceWindow = round?.evidenceWindow ?: execution.capability.evidenceWindow
+
+    /** 이 라운드의 `IN_DOUBT` 유예. 단위에 저장하지 않는다. */
+    private fun inDoubtGraceOf(execution: Execution): Duration = round?.inDoubtGrace ?: execution.capability.inDoubtGrace
+
+    /** 이 라운드의 진행 정체 유예. */
+    private fun stallWindowOf(execution: Execution): Duration = round?.stallWindow ?: execution.capability.stallWindow
+
     /** 하류에서 온 것을 읽고 상태를 한 걸음 민다. 몇 번 불러도 같은 결과다(멱등). */
     fun pump() {
+        round = siteTimings.current()
         val live = executions.values.filter { !(it.physicalState.isSettled && it.physicalState != PhysicalState.PARTIAL) }
         live.map { it.robotId }.distinct().forEach { sync(it, live.filter { e -> e.robotId == it }) }
         executions.values.forEach { pump(it) }
@@ -636,7 +660,7 @@ class Middleware(
 
     private fun pump(execution: Execution) {
         pumpRound(execution)
-        incidentLog.sealIncidents(execution)
+        incidentLog.sealIncidents(execution, round)
     }
 
     private fun pumpRound(execution: Execution) {
@@ -791,7 +815,7 @@ class Middleware(
             return
         }
         if (unit.progressStalled) return
-        val window = execution.capability.stallWindow
+        val window = stallWindowOf(execution)
         if (Duration.between(unit.progressAt, at) < window) return
 
         unit.progressStalled = true
@@ -922,13 +946,13 @@ class Middleware(
 
         // ② 물리 관측 — 요청 시각부터의 신호만 이 요청의 것이다.
         val requestedAt = unit.requestedAt!!
-        val window = execution.capability.evidenceWindow
+        val window = windowOf(execution)
         val signal = execution.observeCell(unit)
         val observedAt = signal?.observedAt ?: now()
         val provisional = signal != null && signal.occupied &&
             (unit.expectedIdentity == null || signal.identity == unit.expectedIdentity) &&
             !observedAt.isBefore(requestedAt.minus(window.before))
-        if (!provisional && now().isBefore(requestedAt.plus(execution.capability.inDoubtGrace))) {
+        if (!provisional && now().isBefore(requestedAt.plus(inDoubtGraceOf(execution)))) {
             execution.physicalState = PhysicalState.IN_DOUBT
             return false
         }
@@ -1281,7 +1305,8 @@ class Middleware(
             return
         }
         unit.state = UnitState.VERIFYING
-        unit.evidenceDeadline = doneAt.plus(execution.capability.evidenceWindow.after)
+        // **이 순간의 뒤 폭으로 정해 저장한다.** 나중에 뒤 폭이 바뀌어도 다시 계산하지 않는다.
+        unit.evidenceDeadline = doneAt.plus(windowOf(execution).after)
         checkEvidence(execution, unit)
     }
 
@@ -1297,7 +1322,7 @@ class Middleware(
      */
     private fun checkEvidence(execution: Execution, unit: ExecutionUnit): Boolean {
         val doneAt = unit.downstreamDoneAt!!
-        val window = execution.capability.evidenceWindow
+        val window = windowOf(execution)
         val current = now()
         unit.rechecks += 1
 
@@ -1362,7 +1387,7 @@ class Middleware(
         unit.note = detail
         unit.downstreamDoneAt = at
         if (execution.order.requiredEvidence > Evidence.E1) {
-            val window = execution.capability.evidenceWindow
+            val window = windowOf(execution)
             val signal = execution.observeCell(unit)
             val observedAt = signal?.observedAt ?: now()
             val present = signal != null && signal.occupied &&
diff --git a/picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt b/picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt
index fcb6860..1f8239a 100644
--- a/picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt
+++ b/picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt
@@ -11,6 +11,7 @@ import dev.picasso.contracts.v1.TaskHandle
 import dev.picasso.contracts.v1.TaskState
 import dev.picasso.contracts.v1.ValueType
 import dev.picasso.contracts.v1.WatchTaskResponse
+import java.time.Duration
 import java.time.Instant
 
 /**
@@ -334,3 +335,100 @@ data class ActiveMission(val capability: LogicalCapability, val missionVersion:
  * 카운터가 있어야 폴링이 놓치지 않는다).
  */
 data class SlotSignal(val occupied: Boolean, val identity: String?, val observedAt: Instant? = null)
+
+// ── 현장 시간값 — 케이퍼빌리티 기본값을 덮는 현장 설정 한 세트
+
+/**
+ * 현장 시간값의 출처. 미들웨어는 [Middleware.pump] 가 시작할 때 **한 번** 읽고 그 라운드의 모든 판정에 같은 값을 쓴다.
+ *
+ * `null` 은 현장 값이 없다는 뜻이고, 그때는 케이퍼빌리티의 기본값([LogicalCapability.evidenceWindow]·
+ * [LogicalCapability.inDoubtGrace]·[LogicalCapability.stallWindow])으로 판정한다. 값을 쓰기 전에 검사하는 것은 구현의 일이다.
+ * 범위 밖 값을 받아 둔 구현은 그것을 주지 말고 마지막으로 통과한 값을 준다([SiteTimings.problems]).
+ *
+ * [current] 는 pump 안에서 불리므로 미들웨어를 지키는 소비자(호스트)의 잠금 아래에서 돈다고 전제한다(미들웨어 자체에
+ * 스레드가 없다). 그래서 막히지 않아야 하고, 이미 검사한 스냅숏을 돌려준다. 여기서 DB 를 읽지 않는다. 읽기 주기가 다른
+ * 스레드에서 값을 바꾸면 안전하게 게시한다(`@Volatile` 필드나 `AtomicReference`).
+ */
+fun interface SiteTimingsSource {
+
+    /** 지금 적용할 값. 없으면 `null`. */
+    fun current(): SiteTimings?
+
+    companion object {
+        /** 현장 값이 없다. 미들웨어의 기본값이며, 모든 판정이 케이퍼빌리티 값으로 돈다. */
+        val NONE: SiteTimingsSource = SiteTimingsSource { null }
+    }
+}
+
+/**
+ * 현장 설정 한 버전의 시간값 넷. 현장 전체에 한 값이며, 값이 있으면 모든 케이퍼빌리티의 같은 값을 덮는다.
+ *
+ * 값은 **초 단위 정수**다. 허용 범위는 이 라이브러리가 쥐고([EVIDENCE_WINDOW_BEFORE_SECONDS] 들), 쓰는 쪽은 적용 전에
+ * [problems] 로 검사한다. 생성자는 검사하지 않는다. 범위 밖 값을 읽어 «왜 적용하지 않았나» 를 보여야 하는 쪽이 있기 때문이다.
+ *
+ * @param siteSettingsVersion 현장 설정의 버전. 1 부터 오른다. 인시던트의 의도에 실린다.
+ * @param evidenceWindowBefore 근거 시간 윈도우의 앞 폭. 늘리면 옛 신호가 완료 근거로 들어온다.
+ * @param evidenceWindowAfter 근거 시간 윈도우의 뒤 폭. 줄이면 `UNVERIFIED` 가 는다. 단위가 완료될 때 근거 기한에 저장된다.
+ * @param inDoubtGrace `IN_DOUBT` 에서 물리 관측을 기다리는 유예. 줄이면 운영자 대기가 는다.
+ * @param stallWindow 진행 정체를 사람에게 보이기까지의 유예. 결과 판정은 바꾸지 않는다.
+ */
+data class SiteTimings(
+    val siteSettingsVersion: Long,
+    val evidenceWindowBefore: Duration,
+    val evidenceWindowAfter: Duration,
+    val inDoubtGrace: Duration,
+    val stallWindow: Duration,
+) {
+    /** 앞·뒤 폭을 케이퍼빌리티와 같은 모양으로. */
+    val evidenceWindow: EvidenceWindow get() = EvidenceWindow(before = evidenceWindowBefore, after = evidenceWindowAfter)
+
+    /**
+     * 허용 범위를 벗어난 칸마다 문장 하나. 비어 있으면 적용해도 된다.
+     *
+     * 문장은 칸 이름으로 시작한다(`"stallWindow: ..."`). 범위 밖 값은 적용하지 않고 마지막으로 적용한 버전을 유지한다
+     * (운영 관리 화면 설계 제안 §9). 초 단위가 아닌 값(밀리초가 남는 값)도 범위 밖으로 친다.
+     */
+    fun problems(): List<String> = buildList {
+        if (siteSettingsVersion < 1) add("siteSettingsVersion: 1 이상이어야 한다 ($siteSettingsVersion)")
+        outside("evidenceWindowBefore", evidenceWindowBefore, EVIDENCE_WINDOW_BEFORE_SECONDS)?.let(::add)
+        outside("evidenceWindowAfter", evidenceWindowAfter, EVIDENCE_WINDOW_AFTER_SECONDS)?.let(::add)
+        outside("inDoubtGrace", inDoubtGrace, IN_DOUBT_GRACE_SECONDS)?.let(::add)
+        outside("stallWindow", stallWindow, STALL_WINDOW_SECONDS)?.let(::add)
+    }
+
+    private fun outside(field: String, value: Duration, range: LongRange): String? = when {
+        value.nano != 0 -> "$field: 초 단위 정수여야 한다 ($value)"
+        value.seconds !in range -> "$field: ${range.first}~${range.last} 초 밖이다 (${value.seconds})"
+        else -> null
+    }
+
+    companion object {
+        /**
+         * 앞 폭의 허용 범위(초). 하한은 셀이 슬롯을 보고 기체가 완료를 보고하는 순서가 뒤바뀌는 것을 받기 위함이고,
+         * 상한(기본값의 4배)은 옛 신호가 완료 근거로 들어오는 파급을 묶는다. 기본값 30.
+         */
+        val EVIDENCE_WINDOW_BEFORE_SECONDS: LongRange = 5L..120L
+
+        /** 뒤 폭의 허용 범위(초). 하한은 pump 주기와 셀 폴링 주기를 넘기기 위함이고, 상한은 기본값의 8배다. 기본값 15. */
+        val EVIDENCE_WINDOW_AFTER_SECONDS: LongRange = 5L..120L
+
+        /**
+         * `IN_DOUBT` 유예의 허용 범위(초). 하한은 같은 참조로 다시 묻는 상한(pump 마다 한 번, 세 번)을 마친 뒤에도 설비를
+         * 여러 번 읽을 폭이고, 상한은 기본값의 10배다. 기본값 60.
+         */
+        val IN_DOUBT_GRACE_SECONDS: LongRange = 10L..600L
+
+        /** 진행 정체 유예의 허용 범위(초). 하한은 기종 프로파일의 가장 긴 발행 간격(30초)이고, 상한은 기본값의 12배다. 기본값 300. */
+        val STALL_WINDOW_SECONDS: LongRange = 30L..3600L
+
+        /** 초 단위 정수로 만든다. 저장소가 초 단위 정수로 드는 값을 그대로 옮길 때 쓴다. */
+        fun ofSeconds(siteSettingsVersion: Long, evidenceWindowBefore: Long, evidenceWindowAfter: Long, inDoubtGrace: Long, stallWindow: Long) =
+            SiteTimings(
+                siteSettingsVersion,
+                Duration.ofSeconds(evidenceWindowBefore),
+                Duration.ofSeconds(evidenceWindowAfter),
+                Duration.ofSeconds(inDoubtGrace),
+                Duration.ofSeconds(stallWindow),
+            )
+    }
+}
diff --git a/picasso/src/test/kotlin/dev/picasso/middleware/HandoffFixtureTest.kt b/picasso/src/test/kotlin/dev/picasso/middleware/HandoffFixtureTest.kt
index 3790ff2..61c93da 100644
--- a/picasso/src/test/kotlin/dev/picasso/middleware/HandoffFixtureTest.kt
+++ b/picasso/src/test/kotlin/dev/picasso/middleware/HandoffFixtureTest.kt
@@ -102,6 +102,14 @@ class HandoffFixtureTest {
         assertNotEquals(runIds[0], runIds[1], "두 벌의 구동 식별자가 같다")
     }
 
+    @Test
+    fun `현장 설정 없이 만든 번들의 요약이 설정 칸을 더하기 전과 같다`() {
+        // ★**커밋된 인계 요약을 지금 인코더로 다시 대는 시험이 없다** — 위의 대조는 두 구동을 서로 견줄 뿐이라, 요약의
+        //   재료가 모든 번들에서 바뀌어도 둘이 함께 바뀌어 초록이다. 그래서 설정 버전 칸을 더하기 전(`2ca7cd6`)에 잰
+        //   값을 문자열로 박는다. 설정 없이 도는 번들의 요약이 움직이면 인계 번들과 내보내기 버전이 함께 움직여야 한다.
+        assertEquals(GOLDEN_DIGEST, bundle().digest(), "현장 설정 없이 만든 번들의 요약이 바뀌었다")
+    }
+
     @Test
     fun `안내문이 대는 구동 식별자가 커밋된 한 벌의 것과 같다`() {
         // ★**한 번 낡았던 자리다.** 한 벌을 다시 산출하면서 `INDEX.txt` 의 값만 안 고쳐, 받는 쪽이
@@ -333,6 +341,9 @@ class HandoffFixtureTest {
         private val RUNS = REPLAY + RECURRENCE + AFTER_INCIDENT
         private val AT: Instant = Instant.parse("2026-09-05T00:03:21Z")
 
+        /** [bundle] 의 요약을 `2ca7cd6`(현장 설정 칸을 더하기 전)에서 잰 값. */
+        private const val GOLDEN_DIGEST = "d511e96df06e6be67f0b4af8d73bef4a23aedab6253870c30908768358801682"
+
         private val STEP = RemedyStep("pick_place", emptyList(), HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING)
 
         private val NONE = RemedyOutcome.None(
```

- [ ] **Step 4: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" && ./gradlew :picasso:test -q
```
Expected: picasso 369, 실패 0. 백그라운드로 돌린다.

- [ ] **Step 5: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" && git add picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt picasso/src/main/kotlin/dev/picasso/middleware/Incident.kt picasso/src/test/kotlin/dev/picasso/middleware/HandoffFixtureTest.kt picasso/src/test/kotlin/dev/picasso/middleware/SiteTimingsTest.kt picasso/src/test/kotlin/dev/picasso/middleware/SiteTimingsRangeTest.kt && git commit -F - <<'EOF'
feat(middleware): 현장 시간값 주입과 인시던트의 현장 설정 버전

- `SiteTimingsSource`·`SiteTimings`, 허용 범위 상수와 문제 목록
- `Middleware` 의 `siteTimings` 인자와 pump 라운드 스냅숏, 읽는 자리 일곱
- `Intent` 의 설정 버전과 시간값, 설정 있을 때만 해시 한 줄
- `SiteTimingsTest` 14개, `SiteTimingsRangeTest` 7개, 설정 없는 번들 요약 고정 1개

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 2: 문서

**Files:**
- Modify: `CLAUDE.md`, `README.md`, `docs/limits.md`, `docs/orchestration.md`, `docs/seams.md`, `docs/superpowers/specs/2026-09-05-picasso-design.md`, `docs/verification.md`, `picasso/README.md`

- [ ] **Step 1: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/p5-patches/task2.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/p5-patches/task2.patch"
```

````diff
diff --git a/CLAUDE.md b/CLAUDE.md
index 89cbc84..f809765 100644
--- a/CLAUDE.md
+++ b/CLAUDE.md
@@ -8,7 +8,7 @@
 ## 1. 빌드 및 테스트 명령
 
 ```bash
-# 전체 빌드 및 테스트 실행 (총 1,982개 테스트)
+# 전체 빌드 및 테스트 실행 (총 2,004개 테스트)
 ./gradlew build
 
 # 아키텍처 및 품질 게이트 검증만 실행
@@ -72,4 +72,4 @@
 - **저장소 경계 준수**: 본 저장소 밖의 다른 저장소 파일을 직접 생성하거나 수정하지 않습니다. 계층이 서로 다른 저장소에 위치하고 상호 참조하지 않는다는 사실 자체가 아키텍처 경계의 증명이며, 편의를 이유로 한 번 넘어가면 그 증명이 소멸합니다. 다른 저장소로 넘길 산출물은 `handoff/<받는 쪽>/` 에 두고 경로만 전달하며, 무엇을 반입할지는 받는 쪽이 결정합니다.
 - **한계점 및 히스토리 관리**: 미결 과제는 [`docs/limits.md`](docs/limits.md)에 기록하며, 설계 문서 `§15`의 변경 이력은 기존 항목을 삭제하지 않고 정정 내용을 누적 기록합니다.
 
-> 마지막 대조: 2026-10-08 · sha256:259d203c9510 · 열림: 없음
+> 마지막 대조: 2026-10-09 · sha256:f0ec1dd578be · 열림: 없음
diff --git a/README.md b/README.md
index 0caf3a4..39a66d7 100644
--- a/README.md
+++ b/README.md
@@ -91,7 +91,7 @@ docs/vendors/             로봇이 아닌 벤더 표면의 측정 노트 (플
 
 저장소 내 대외 문서 57종은 자동화 대조 검증을 완료한 상태입니다. 문서에 명시된 모든 기술적 주장은 자동화 테스트로 증명되거나, [`docs/limits.md`](docs/limits.md)의 오픈 항목 레지스터에 등록되어 추적 관리됩니다. 각 문서 하단의 대조 스탬프(Hash Stamp)은 본문 내용과 연결되어 있어, `CompletionCriterionTest`를 통해 임의 변경 시 스탬프 갱신을 요구합니다.
 
-한계 레지스터(`limits.md`)에 등록된 미결 항목은 **72개**(내부 31개 · 소비자 대기 15개 · 외부 26개, 의도적 제외 13개 제외)이며, 그 상세 목록과 해결 조건은 `limits.md`에 명시되어 있습니다. 특히 실물 어댑터가 넷 있다(기체 셋, 플릿 하나). 다만, 어댑터 넷 중 어느 것도 실물에 붙여 보지 못했다(C-3)는 물리적 검증 한계가 존재하며, 이는 SDK 라이선스, JVM 바인딩 부재, 플릿 실기체 인스턴스 부재 등에 기인합니다.
+한계 레지스터(`limits.md`)에 등록된 미결 항목은 **73개**(내부 31개 · 소비자 대기 16개 · 외부 26개, 의도적 제외 13개 제외)이며, 그 상세 목록과 해결 조건은 `limits.md`에 명시되어 있습니다. 특히 실물 어댑터가 넷 있다(기체 셋, 플릿 하나). 다만, 어댑터 넷 중 어느 것도 실물에 붙여 보지 못했다(C-3)는 물리적 검증 한계가 존재하며, 이는 SDK 라이선스, JVM 바인딩 부재, 플릿 실기체 인스턴스 부재 등에 기인합니다.
 
 실물 넷이 계약에 얼마나 닿나 확인한 정량 분석 결과는 [`profile/distance/`](profile/distance)에서 확인할 수 있습니다. 계약 개정판은 **0.9.0** 이다.
 
@@ -140,7 +140,7 @@ client --target <host:port> --robot <id> --requirements <file> --skill <type> [-
 | **벤더 인터페이스** | [`profile/vendors/`](profile/vendors) · [`docs/vendors/orbit.md`](docs/vendors/orbit.md) | 벤더 API 표면 분석 및 플릿 관리 인터페이스 측정 노트 |
 | **현장 전제조건** | [`docs/environment-preconditions.md`](docs/environment-preconditions.md) | 로봇 도입 현장의 인프라(도어, 바닥, 조명 등) 엔지니어링 전제조건 |
 | **벤더 매니페스트** | [`tools/vendor-manifest/README.md`](tools/vendor-manifest/README.md) | 어댑터의 사우스바운드 포트 벤더 심볼 인용 대조 검증 도구 |
-| **오픈 항목 과제 레지스터** | [`docs/limits.md`](docs/limits.md) | 미결 한계 항목 72개(내부·소비자 대기·외부) 및 해결 조건 관리 레지스터 |
+| **오픈 항목 과제 레지스터** | [`docs/limits.md`](docs/limits.md) | 미결 한계 항목 73개(내부·소비자 대기·외부) 및 해결 조건 관리 레지스터 |
 
 ## 핵심 엔지니어링 규율
 
@@ -151,4 +151,4 @@ client --target <host:port> --robot <id> --requirements <file> --skill <type> [-
 - **결함 주입(Mutation Testing)**: 테스트 케이스 작성 시 의도적 결함을 주입하여 검증 유효성을 선행 확인합니다.
 - **엄격한 실패 정책**: 사전 선언된 요구 검사 목록(`--require`)을 충족하지 못하는 경우 조용한 통과를 허용하지 않습니다.
 
-> 마지막 대조: 2026-10-08 · sha256:918c9831a625 · 열림: C-3, §15.81
+> 마지막 대조: 2026-10-09 · sha256:f0c95a0965db · 열림: C-3, §15.81
diff --git a/docs/limits.md b/docs/limits.md
index 1020381..2891a7b 100644
--- a/docs/limits.md
+++ b/docs/limits.md
@@ -2,7 +2,7 @@
 
 본 문서는 `picasso` 미들웨어 아키텍처 및 구현 상에 존재하는 **알려진 한계(Known Limitations), 스코프 외 제외 항목, 기술 부채 및 해소 조건**을 체계적으로 추적 관리하기 위한 엔지니어링 레지스터입니다.
 
-설계 문서의 §15는 전체 변경 이력과 배경을 누적 기록하는 변경 이력이며 (번호가 211 까지 갔고), 본 문서는 현재 시점에서 유효한 오픈 항목 항목만을 분류하여 제공합니다. 해결 완료된 항목은 변경 이력에 `(닫힘)` 처리되고 본 레지스터에서는 정리됩니다.
+설계 문서의 §15는 전체 변경 이력과 배경을 누적 기록하는 변경 이력이며 (번호가 212 까지 갔고), 본 문서는 현재 시점에서 유효한 오픈 항목 항목만을 분류하여 제공합니다. 해결 완료된 항목은 변경 이력에 `(닫힘)` 처리되고 본 레지스터에서는 정리됩니다.
 
 ---
 
@@ -81,6 +81,7 @@
 
 | 출처 | 오픈 항목 과제 내용 | 해소 필요 조건 |
 |---|---|---|
+| §15.212 · 현장 설정 버전 내보내기 | **설정 버전과 시간값이 밖으로 안 나감**: `Intent.siteSettingsVersion`·`inDoubtGrace`·`stallWindow` 는 프로세스 안 인시던트 번들에만 있고 설정 버전이 있을 때만 해시에 들지만 내보내는 인시던트 줄에는 싣지 않음(§15.209 의 `missionVersion` 과 같은 처리). 내보내기 버전은 6 그대로이고 narrator 에는 읽을 자리를 묻지 않았음 | 읽는 쪽(narrator)이 설정 버전과 시간값을 읽을 자리를 요청할 때(ADR 9). 그때 조회 버전을 올리고 의도(`intent`)의 칸으로 실으며 인계 번들을 다시 산출 |
 | §15.209 · 정의 둘 | **`DeliverContainer` · `InspectAsset` 이 코드 정의로 남음**: 데이터로 옮긴 것은 `PrepareSequencedRack` 하나이고 둘은 코드 케이퍼빌리티로 같은 카탈로그에 함께 선다. 옮기려면 반복 한 번에 노드 여럿, 단위 id 접미사(`.travel`), 작업 지시 파라미터 값(`mode`), 조건부 포함, 플릿 경로가 스키마에 더 있어야 함 | 그 둘을 데이터로 바꿔야 하는 소비자가 생길 때(ADR 9). 그때 스키마를 넓히고 코드 정의와의 동등성 시험을 같은 방식으로 붙임 |
 | §15.209 · 분기 | **임무 정의는 직선만**: 분기와 병렬이 없다. 운영 관리 화면 설계 제안 §6 · §11 결정 1 대로 5가지 의미(동시 전이 우선순위, 반복 횟수, 자원 획득 · 반납, 취소 · 재시작, 재시도 때 부작용 중복)를 먼저 정한 뒤에 연다 | 분기나 병렬을 실제로 쓰는 소비자가 생길 때(ADR 9). 그때 5가지 의미부터 정함 |
 | §15.209 · 스키마 파일 | **임무 정의의 JSON Schema 파일이 없음**: 정의의 모양과 형 규칙은 `MissionDefinitionParser` 가 소유함. picasso-ops S3b 는 편집기를 새 의존 없는 `textarea` 와 서버 검증으로 두고 편집기용 스키마 파일을 만들지 않았음(§15.211) | 소비자인 picasso-ops JSON 편집기가 스키마 파일을 요구할 때(ADR 9). 그때 파서와 같은 규칙인지 대조하는 시험을 함께 둠 |
@@ -139,4 +140,4 @@
 - 하위 범주는 **해소의 주어**로 정합니다. 해소 조건에 적은 주어와 하위 범주가 어긋나면 `DocumentClaimsTest` 가 막습니다. 다만 주어를 용어로 부르지 않는 행은 기계가 못 가리므로 사람이 읽어야 합니다(§15.194).
 - 밖을 향한 문서가 드는 열림은 **139** 개다. 각 문서 하단 스탬프에 기재된 오픈 항목 ID 총합은 본 수치와 엄격히 일치해야 합니다 (`CompletionCriterionTest` 집행).
 
-> 마지막 대조: 2026-10-09 · sha256:ac41d5ead5f7 · 열림: 없음
+> 마지막 대조: 2026-10-09 · sha256:bdd15b8264eb · 열림: 없음
diff --git a/docs/orchestration.md b/docs/orchestration.md
index 8b2be24..90e9269 100644
--- a/docs/orchestration.md
+++ b/docs/orchestration.md
@@ -215,7 +215,7 @@ flowchart TB
 | 경로 | `route` 가 `ROBOT` · `FLEET` · `SIGNAL` 중 하나입니다. `SIGNAL` 은 설비 대기이며 하위 요청 없이 이름 있는 신호를 기다린 단위이므로 책임 소재는 셀 설비와 현장 쪽입니다(버전 `6` 에서 생긴 하위 범주). **없으면 책임 소재를 가르지 못합니다** — 같은 실패라도 앞은 기체와 어댑터의 일이고 뒤는 플릿의 일이며, 다음에 누구에게 물을지가 그것으로 갈립니다 |
 | 관측 신뢰 | `observation` 이 `linkBroken` · `lateEvents` · `progressObservable` · `progressStalled` 를 싣습니다. **`progressObservable` 은 3값이며 널이 「아직 갱신을 못 봤다」입니다** — 거짓으로 접으면 못 물어본 것이 못 재는 것이 되고, 진행률을 내지 않는 기종이 언제나 정지한 것으로 보입니다 |
 | 단계 위치 | `step` 은 `at`(1부터) · `plan`(순서대로) · `completed` 입니다. **총수는 `plan` 의 길이이며 따로 싣지 않습니다** — 같은 사실을 두 칸에 두면 갈릴 자리가 생깁니다 |
-| 버전 | `schemaVersion` 이 `6` 입니다. `2` 에서 `blockedBy` 가 분류 이름의 배열에서 결함 객체의 배열로 **모양이 바뀌었으므로** 그 버전은 읽는 쪽이 보아야 하며, `3` 은 칸이 는 것뿐입니다. `4` 는 인시던트 줄을 바꾸지 않고 **조치 탐색 기록의 `outcome` 에 하위 범주를 하나 더합니다** — 칸이 느는 것과 달리 `outcome` 으로 분기하는 읽는 쪽은 모르는 값을 만나므로 그 자리만 버전을 보면 됩니다 · `5` 는 인시던트 줄에 `resolution`(사람이 그 단위에 낸 판단) 한 칸을 더합니다 — 칸이 느는 것이라 읽는 쪽이 안 보아도 깨지지 않고, **요약은 안 바뀝니다**(해시에서 빠지는 셋째 칸입니다) · `6` 은 인시던트 줄의 `route` 에 하위 범주 `SIGNAL`(설비 대기)을 하나 더합니다. 칸은 늘지 않았으나 `route` 로 분기하는 읽는 쪽은 모르는 값을 만나므로 `4` 와 같은 이유로 버전을 보아야 합니다. 인시던트의 임무 버전(`Intent.missionVersion`)은 해시에 들지만 읽는 쪽이 없어 내보내지 않습니다(ADR 9). 그래서 **모든 줄의 요약(`digest`)이 바뀝니다**(코드 케이퍼빌리티의 줄도 포함). 읽는 쪽이 `digest` 를 멱등성 키로 쓰면 다시 산출한 세트를 새 인시던트로 받습니다 |
+| 버전 | `schemaVersion` 이 `6` 입니다. `2` 에서 `blockedBy` 가 분류 이름의 배열에서 결함 객체의 배열로 **모양이 바뀌었으므로** 그 버전은 읽는 쪽이 보아야 하며, `3` 은 칸이 는 것뿐입니다. `4` 는 인시던트 줄을 바꾸지 않고 **조치 탐색 기록의 `outcome` 에 하위 범주를 하나 더합니다** — 칸이 느는 것과 달리 `outcome` 으로 분기하는 읽는 쪽은 모르는 값을 만나므로 그 자리만 버전을 보면 됩니다 · `5` 는 인시던트 줄에 `resolution`(사람이 그 단위에 낸 판단) 한 칸을 더합니다 — 칸이 느는 것이라 읽는 쪽이 안 보아도 깨지지 않고, **요약은 안 바뀝니다**(해시에서 빠지는 셋째 칸입니다) · `6` 은 인시던트 줄의 `route` 에 하위 범주 `SIGNAL`(설비 대기)을 하나 더합니다. 칸은 늘지 않았으나 `route` 로 분기하는 읽는 쪽은 모르는 값을 만나므로 `4` 와 같은 이유로 버전을 보아야 합니다. 인시던트의 임무 버전(`Intent.missionVersion`)은 해시에 들지만 읽는 쪽이 없어 내보내지 않습니다(ADR 9). 그래서 **모든 줄의 요약(`digest`)이 바뀝니다**(코드 케이퍼빌리티의 줄도 포함). 읽는 쪽이 `digest` 를 멱등성 키로 쓰면 다시 산출한 세트를 새 인시던트로 받습니다. 인시던트의 현장 설정 버전(`Intent.siteSettingsVersion`)과 그 라운드의 `inDoubtGrace`·`stallWindow` 도 내보내지 않습니다(ADR 9). 해시에는 설정 버전이 있을 때만 한 줄이 들어가므로, 설정 없이 도는 줄의 요약은 바뀌지 않고 버전도 `6` 그대로입니다 |
 | 번들의 해시 | `digest` 를 함께 적습니다. 이미 계산되는 값이며, 읽는 쪽이 같은 인시던트를 두 번 받았는지 가르는 유일한 결정적 조회 키입니다 |
 | 한 세트의 명세 | `manifest.json` 이 `schemaVersion` · `runId` · `writtenAt` · `virtualNow` · `contractSemver` · `counts` 를 싣습니다 |
 | 런 식별자 | `runId` 는 **이 내보내기에서 유일하게 결정적이지 않은 값**입니다. 나머지는 같은 시드면 같은 값이고 그것이 재현의 근거인데, 읽는 쪽이 멱등성 키를 그 값들로 들면 같은 시드의 두 번째 구동이 전부 이미 본 것으로 접혀 **아무 신호 없이** 처리가 사라집니다. 실제 시계에서 나오며 같은 순간에도 겹치지 않습니다. **결정적으로 바꾸면 그 고장이 조용히 돌아옵니다** |
@@ -369,4 +369,4 @@ flowchart TB
 
 **담는 측이 지킬 조건으로, `job-responses.jsonl` 은 그 인스턴스의 처음부터의 전체 이력이어야 하고 확인된 통보를 앞에서 잘라 내면 안 됩니다.** koshei 는 첫 줄의 `instanceId` 로 어느 구동인지를 읽기 때문입니다. 참조 실행기는 이 조건을 지킵니다(확인된 것까지 포함한 작업 응답 전체를, 개수가 늘 때만 원자적으로 다시 씁니다).
 
-> 마지막 대조: 2026-10-08 · sha256:d5f7fdb15735 · 열림: §15.143, §15.154, §15.162, §15.163, §15.3, §15.166, §15.173, §15.174, §15.175, §15.176, §15.178, §15.183, §15.184, §15.203
+> 마지막 대조: 2026-10-09 · sha256:1848f14de1b0 · 열림: §15.143, §15.154, §15.162, §15.163, §15.3, §15.166, §15.173, §15.174, §15.175, §15.176, §15.178, §15.183, §15.184, §15.203
diff --git a/docs/seams.md b/docs/seams.md
index 5e924e8..a05c868 100644
--- a/docs/seams.md
+++ b/docs/seams.md
@@ -126,12 +126,27 @@
 
 ---
 
+## 데이터 접합부: 현장 시간값 (Site Timings)
+
+**인터페이스**: `SiteTimingsSource.current(): SiteTimings?` (기본 구현은 `SiteTimingsSource.NONE`, 값 없음)
+
+값이 없으면 미들웨어는 케이퍼빌리티 기본값으로 판정합니다. 값이 있으면 `Middleware.pump()` 가 시작할 때 한 번 읽어 그 라운드의 모든 판정에 쓰고, 허용 범위는 `SiteTimings.problems()` 가 검사합니다. 저장과 읽기 주기는 picasso-ops 실행 호스트가 붙입니다(S3c).
+
+`current()` 는 pump 안에서 불리므로 미들웨어를 지키는 소비자(호스트)의 잠금 아래에서 돈다고 전제합니다(미들웨어 자체에 스레드가 없습니다). 그래서 막히지 않아야 하고, 이미 검사한 스냅숏을 돌려줍니다. 여기서 DB 를 읽지 않습니다. 읽기 주기가 다른 스레드에서 값을 바꾸면 안전하게 게시합니다(`@Volatile` 필드나 `AtomicReference`).
+
+- **데이터 공급 구현 전환 작업**:
+  - 현장 설정의 현재 버전을 읽어 `SiteTimings.problems()` 를 통과한 값만 주는 구현체를 제공합니다. 범위 밖 값은 주지 않고 마지막으로 통과한 값을 줍니다.
+- **불변 유지 대상**: 엔진 규칙(라운드마다 한 번 읽고, 단위에 저장하는 값은 근거 기한 하나), 허용 범위 상수.
+
+---
+
 ## 색인 — 자리와 인터페이스
 
 | 자리 | 인터페이스 | 어디 |
 |---|---|---|
 | 설비 | `CellSignals` | `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt` |
 | 임무 정의 | `MissionCatalog` | `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt` |
+| 현장 시간값 | `SiteTimingsSource` | `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt` |
 | 플릿 | `AmrFleetPort` | `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt` |
 | 로봇(소비자) | `RobotPort` | `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt` |
 | 벤더 — Spot | `SpotLink` | `adapter-boston-dynamics-spot/src/main/kotlin/dev/picasso/adapter/spot/SpotLink.kt` |
@@ -149,4 +164,4 @@
 
 > **검증 보증:** 본 색인 테이블의 인터페이스명 및 파일 경로는 `DocumentClaimsTest`를 통해 실제 소스 코드와 상시 대조 검증됩니다.
 
-> 마지막 대조: 2026-10-08 · sha256:38bd25e33bc2 · 열림: §15.34, C-3, §15.5, §15.156
+> 마지막 대조: 2026-10-09 · sha256:f5bb99f05515 · 열림: §15.34, C-3, §15.5, §15.156
diff --git a/docs/superpowers/specs/2026-09-05-picasso-design.md b/docs/superpowers/specs/2026-09-05-picasso-design.md
index a3fa0d0..841ef28 100644
--- a/docs/superpowers/specs/2026-09-05-picasso-design.md
+++ b/docs/superpowers/specs/2026-09-05-picasso-design.md
@@ -1108,7 +1108,7 @@ mimic/
 
 기록은 `docs/adr/`에 있고 번호가 이 표의 행 번호다. **이미 내려서 코드에 박힌 것만 쓴다** — 3a·3b가 만들 것(11~21)은 그때 쓴다. 결정하지 않은 것을 미리 적어 두면 그것이 결정처럼 보인다.
 
-> 마지막 대조: 2026-10-08 · sha256:30af927964c1 · 열림: C-3, §15.7, §15.126, ADR 32 · 시나리오 5, §15.4, §15.5, §15.6 · §15.11 · §15.28, §15.8, §15.9, §15.10, §15.1, §15.2, §15.33, §15.87
+> 마지막 대조: 2026-10-09 · sha256:30af927964c1 · 열림: C-3, §15.7, §15.126, ADR 32 · 시나리오 5, §15.4, §15.5, §15.6 · §15.11 · §15.28, §15.8, §15.9, §15.10, §15.1, §15.2, §15.33, §15.87
 
 ## 15. 알려진 한계
 
@@ -3099,6 +3099,16 @@ mimic/
 
     **검사 9번이 결속을 어댑터 경계 안에 가둔다.** 탐색어는 결속 파일이 선언한 최상위 이름에서 유도하므로 타입을 더하면 금지도 저절로 는다. 훑는 모듈에 `registry` 와 `profile-model` 을 넣었다 — 검사 7의 목록에 그 둘이 없어서, 없다는 이유로 결속까지 새면 같은 구멍이 두 번째로 열린다.
 
+212. **현장 시간값을 라운드마다 한 번 읽어 일곱 자리에 적용하고 봉인한 인시던트의 의도와 조건부 해시에 담았다.**
+
+    미들웨어의 시간 판정(정체 판정, `IN_DOUBT` 의 근거 윈도우와 유예, 완료 뒤 근거 기한, 완료와 실패 보고 뒤의 근거 판정)은 전부 케이퍼빌리티 getter 의 기본값(앞 30초, 뒤 15초, 유예 60초, 정체 300초)을 읽었고, 생성자에는 다른 값을 넘길 인자가 없었다. 인시던트의 `Intent` 에도 어느 설정으로 판정했는지 적는 칸이 없었다. picasso-ops 의 S3c 스펙(`docs/superpowers/specs/2026-10-09-s3c-site-timings-design.md`, picasso-ops 저장소) T1~T4 가 현장 값 한 세트를 바꾸면 다음 라운드부터 적용되고 인시던트에 값과 버전이 실리는 것을 요구한다. 운영 관리 화면 설계 제안(`docs/superpowers/specs/2026-10-07-ops-console-lifecycle-design.md`) §10 입증 항목 2 와 §11 결정 2(허용 범위는 라이브러리가 쥔다)의 picasso 쪽이다. 읽는 소비자가 처음 생겼으므로 지었다(ADR 9).
+
+    `Ports.kt` 에 `SiteTimingsSource.current()` 와 `NONE`, 설정 버전과 시간값 넷을 담는 `SiteTimings` 를 두고, `Middleware` 생성자의 마지막 인자로 `siteTimings: SiteTimingsSource = SiteTimingsSource.NONE` 을 받는다. `pump()` 첫 줄에서 실행 수와 무관하게 소스를 한 번 읽어 라운드 스냅숏을 만들며, 값이 없으면 케이퍼빌리티 getter 를 쓴다. 정체 판정, `IN_DOUBT` 근거 윈도우와 유예, 완료 뒤 근거 기한, 완료 근거 판정, 실패 보고 뒤 근거 판정, 인시던트 봉인의 일곱 자리가 이 값을 쓴다. 의도에 `siteSettingsVersion`·`inDoubtGrace`·`stallWindow` 를 더하고 기존 앞·뒤 폭도 봉인 라운드의 값으로 채우며, 유예와 정체 값은 기존 폭처럼 `Duration.toString()` 으로 싣는다. 설정 버전이 있을 때만 의도 줄 바로 뒤에 버전·앞·뒤·유예·정체의 해시 줄을 더하므로 설정 없이 만든 요약은 그대로이며, 계약과 계약 버전 0.9.0, 내보내기 버전 6, 인계 번들은 바뀌지 않는다. 허용 범위는 초 단위로 앞·뒤 각각 5~120, 유예 10~600, 정체 30~3600 이며, 상한은 기본값의 4·8·10·12배이고 정체 하한은 가장 긴 발행 간격 30초, 유예 하한은 같은 참조로 다시 묻는 상한 뒤에도 설비를 여러 번 읽을 폭에 근거한다. 범위는 `LongRange` 넷으로 두고 `problems()` 가 범위 밖 값·초 단위가 아닌 값·1 미만의 버전을 보고하며, 생성자와 미들웨어는 검사하지 않고 소스가 걸러 주도록 한다.
+
+    시험은 1,982개에서 2,004개로 22개 늘었고 기존 시험은 고치지 않았다. 전체 빌드와 harness·registry 실행 뒤 소스의 `@Test` 와 결과 XML 의 `<testcase>` 수가 모두 2,004개였으며 실패와 건너뜀은 없었다. `SiteTimingsTest` 14개는 일곱 자리, 값 없음, 라운드당 한 번 읽기, 다음 라운드의 유예 적용, 저장된 근거 기한 불변, 저장된 기한과 그 라운드 앞 폭의 함께 쓰임, 해시와 내보내기를 보고, `SiteTimingsRangeTest` 7개는 범위와 단위·버전·기본값을 본다. 각본대로 응답하는 `ScriptedRobot` 과 케이퍼빌리티 기본값과 칸마다 다른 시간값을 써 적용 자리를 확인했고, 일곱 자리와 적용 시점·범위·인시던트·해시·내보내기에 넣은 결함 17건(일곱 자리 7, 적용 시점 6, 범위 1, 인시던트·해시·내보내기 3)을 모두 탐지했다. `HandoffFixtureTest` 에 설정 없는 번들의 요약을 고정하는 시험 하나를 더해 변경 전 `2ca7cd6` 에서 잰 `d511e96df06e6be67f0b4af8d73bef4a23aedab6253870c30908768358801682` 와 같음을 확인했다. 설정 없는 번들의 요약을 박는 시험은 이것이 처음이다.
+
+    한계 레지스터에 §15.212 · 현장 설정 버전 내보내기 행을 남겼으며, 새 의도 칸 셋은 프로세스 안 인시던트와 해시에만 있고 내보내는 줄에는 없다. 저장하는 시간값은 완료 뒤 근거 기한 하나이며 나머지는 라운드마다 읽으므로, 완료된 단위의 근거 판정은 저장된 기한과 그 라운드의 앞·뒤 폭을 함께 쓴다. 판정 도중 설정을 바꾸면 두 버전의 값이 한 판정에 섞일 수 있는 의미를 정하고 시험으로 고정했다. picasso-ops 통합 시험의 설비 대기 기한 인시던트는 시간값으로 판정하지 않아도 같은 봉인 함수에서 라운드의 버전과 값을 받지만, picasso 시험은 이를 `IN_DOUBT` 운영자 대기 인시던트로 확인하며 설비 대기 경로의 설정 버전은 직접 보지 않는다.
+
 211. **소비자의 S3b 결정에 맞춰 스키마 파일 오픈 항목의 설명과 해소 조건을 고쳤다.**
 
     한계 §15.209 의 행은 편집기용 JSON Schema 파일을 S3 의 picasso-ops JSON 편집기에서 정하고, 그 편집기가 S3 에서 스키마 파일을 요구할 때 해소한다고 적고 있었다. picasso-ops 의 S3b 설계 스펙(`docs/superpowers/specs/2026-10-08-s3b-mission-versions-design.md`, picasso-ops 저장소)의 기술 결정 T4 가 편집기를 새 의존 없는 `textarea` 와 서버 검증으로 두고 편집기용 JSON Schema 파일은 만들지 않기로 정했다. 실행 호스트가 picasso 의 `MissionDefinitionParser` 로 파싱해 중복 키와 칸 문제를 JSON 경로로 한 번에 돌려주고, 문법 오류는 첫 자리에서 멈춘다. S3b 는 picasso-ops PR #11 로 머지됐다(머지 커밋 `b2cce34`, 2026-10-09). S3 이 스키마 파일 없이 편집기를 지었으므로 S3 에서 정한다는 예정은 낡았다.
diff --git a/docs/verification.md b/docs/verification.md
index aa7a634..3e8ea8c 100644
--- a/docs/verification.md
+++ b/docs/verification.md
@@ -1,6 +1,6 @@
 # 시스템 검증 충실도 및 환경 신뢰도 매트릭스 (Verification & Fidelity Matrix)
 
-본 문서는 `picasso` 미들웨어 시스템의 1,982개 자동화 테스트가 **어느 구간에서 실제 외부 시스템/하드웨어와 연동되고, 어느 구간에서 모의 대역(Mock/In-process)에 의존하는지**를 명확히 구분하여 기술적 검증 신뢰도(Verification Fidelity)를 투명하게 공개하기 위해 작성되었습니다.
+본 문서는 `picasso` 미들웨어 시스템의 2,004개 자동화 테스트가 **어느 구간에서 실제 외부 시스템/하드웨어와 연동되고, 어느 구간에서 모의 대역(Mock/In-process)에 의존하는지**를 명확히 구분하여 기술적 검증 신뢰도(Verification Fidelity)를 투명하게 공개하기 위해 작성되었습니다.
 
 ---
 
@@ -45,4 +45,4 @@
 2. **로봇 인터페이스 계층의 격리성**: 어댑터 계층은 벤더 SDK 격리 원칙에 따라 매니페스트 대조를 통해 정합성을 검증하며, 실기체 직접 연동(C-3)은 환경적 제약으로 인해 오픈 항목 상태로 명시 관리됩니다.
 3. **상위 및 설비 연계 계층의 가정 기반성**: 설비(PLC) 및 AMR 플릿과의 연동 규격은 시스템적 일관성을 입증하기 위한 자체 설계 모델이며, 실제 현장 도입 시 대상 설비에 맞춘 Seam 어댑터 구현이 요구됩니다.
 
-> 마지막 대조: 2026-10-08 · sha256:274794deafb7 · 열림: C-3
+> 마지막 대조: 2026-10-09 · sha256:29319747065f · 열림: C-3
diff --git a/picasso/README.md b/picasso/README.md
index cc80975..d269f05 100644
--- a/picasso/README.md
+++ b/picasso/README.md
@@ -17,7 +17,7 @@
 | `RemedyDesk.kt` | 탐색 기록·제안·보류·진단·승인 장부와 승인 판정(설계안 §6.4, ADR 43·44·45). 승인 뒤의 작업 수락은 `Middleware` 가 조율 |
 | `IncidentLog.kt` | 인시던트 번들의 봉인·조회, 사후 검토의 기록과 지표, 운영자 판단 부착(설계안 §4) |
 | `Canonical.kt` | 작업 응답·인시던트 번들·이벤트 자취가 나눠 쓰는 정준 프로젝션 셋 |
-| `Ports.kt` | 하위 시스템 연동 추상화 포트: `RobotPort` (인터페이스 계약 소비자), `CellSignals` (현장 설비 센서 신호, E2), `AmrFleetPort` (이송 플릿 연동, E1), `MissionCatalog` (WorkMaster 마다 지금 활성인 케이퍼빌리티와 임무 버전을 주는 포트, 새 작업 지시만 읽음) |
+| `Ports.kt` | 하위 시스템 연동 추상화 포트: `RobotPort` (인터페이스 계약 소비자), `CellSignals` (현장 설비 센서 신호, E2), `AmrFleetPort` (이송 플릿 연동, E1), `MissionCatalog` (WorkMaster 마다 지금 활성인 케이퍼빌리티와 임무 버전을 주는 포트, 새 작업 지시만 읽음), `SiteTimingsSource` (현장 시간값 한 세트 `SiteTimings` 를 주는 포트, pump 라운드마다 한 번 읽음, 허용 범위 상수와 검사 함수 포함) |
 | `mission/` | **임무 정의 패키지** (`dev.picasso.middleware.mission`): 임무 정의 스키마(`MissionDefinition`)와 엄격 파서(`MissionDefinitionParser`), 신호 사양(`SignalSpec`), 해석기(`DefinedCapability`), 검증기(`MissionValidator`, 5검사 + 노드 id), 메모리 카탈로그(`InMemoryMissionCatalog`, 활성화 관문). 설비 대기 진행 · 버전 고정 · 인시던트 칸 등 엔진 쪽 변경은 기존 파일에 있음 (ADR 50) |
 
 ---
@@ -60,4 +60,4 @@
 
 본 모듈의 단위/통합 테스트는 외부 상위 시스템과 현장 설비를 모사한 테스트 대역(`CellMimic`, `AmrFleetMimic`)을 기반으로 동작합니다. 따라서 테스트 스위트의 성공은 설계 가정 하에서의 시스템 일관성을 증명하며, 물리 실제 하드웨어 환경과의 실제 연동 검증 등급은 [`docs/verification.md`](../docs/verification.md)의 10·11번 항목에 명시되어 있습니다.
 
-> 마지막 대조: 2026-10-08 · sha256:aa27ad319b93 · 열림: §15.126
+> 마지막 대조: 2026-10-09 · sha256:5df4d5693c2d · 열림: §15.126
````

- [ ] **Step 2: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" && ./gradlew :gate:test :picasso:test -q
```
Expected: gate 276, picasso 369, 실패 0. 백그라운드로 돌린다.

- [ ] **Step 3: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" && git add CLAUDE.md README.md docs/limits.md docs/orchestration.md docs/seams.md docs/superpowers/specs/2026-09-05-picasso-design.md docs/verification.md picasso/README.md && git commit -F - <<'EOF'
docs(p5): 변경 이력 212 와 한계 행, 접합부 색인, 시험 수 갱신

- 설계 문서 변경 이력 §15.212, 한계 레지스터 소비자 대기 행 하나
- `seams.md` 데이터 접합부 본문과 색인, `orchestration.md` 해시 설명, `picasso/README.md` 의 `Ports.kt` 행
- 시험 수 2,004, 오픈 항목 73, 소비자 대기 16

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **Step 4: 묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/p5-cmp.sh" picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt picasso/src/main/kotlin/dev/picasso/middleware/Incident.kt picasso/src/test/kotlin/dev/picasso/middleware/HandoffFixtureTest.kt picasso/src/test/kotlin/dev/picasso/middleware/SiteTimingsTest.kt picasso/src/test/kotlin/dev/picasso/middleware/SiteTimingsRangeTest.kt CLAUDE.md README.md docs/limits.md docs/orchestration.md docs/seams.md docs/superpowers/specs/2026-09-05-picasso-design.md docs/verification.md picasso/README.md && git -C "C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" rev-parse 'HEAD^{tree}' && git -C "C:/Users/Eisen/AppData/Local/Temp/p5" rev-parse 'HEAD^{tree}'
```
Expected: 15개 모두 `같음`, 두 트리 해시가 같다.

## Chunk 2: 검증과 병합(컨트롤러)

### Task 3: 결함 주입, 새 클론 빌드, PR

- [ ] **Step 1: 결함 주입 17건**

```bash
cd "C:/Users/Eisen/AppData/Local/Temp/p5-inject" && P5_ROOT="C:/Users/Eisen/Desktop/Labs/picasso-wt/p5-site-timings" PYTHONUTF8=1 python inject.py
```
Expected: 17건 모두 `탐지`, 지정한 시험 이름이 실패 목록에 있다. 실행 뒤 워크트리가 깨끗하다.

| id | 주입 | 잡는 시험 |
|---|---|---|
| S1 | 정체 판정을 `capability.stallWindow` 로 | 현장 정체 유예가 진행 정체 판정에 쓰인다 |
| S2 | `IN_DOUBT` 근거 윈도우를 케이퍼빌리티 값으로 | 현장 앞 폭이 IN_DOUBT 의 물리 관측에 쓰인다 |
| S3 | `IN_DOUBT` 유예를 케이퍼빌리티 값으로 | 현장 IN_DOUBT 유예가 운영자 대기 시점을 정한다 |
| S4 | 완료 뒤 근거 기한을 케이퍼빌리티 뒤 폭으로 | 완료 뒤 근거 기한이 현장 뒤 폭으로 정해진다 |
| S5 | `checkEvidence` 윈도우를 케이퍼빌리티 값으로 | 현장 앞 폭이 완료의 근거 판정에 쓰인다 |
| S6 | `failWithEvidenceCheck` 윈도우를 케이퍼빌리티 값으로 | 현장 앞 폭이 실패 보고 뒤의 근거 판정에 쓰인다 |
| S7 | 봉인 윈도우를 케이퍼빌리티 값으로 | 인시던트 의도에 봉인 라운드의 설정 버전과 시간값 넷이 실린다 |
| R1 | 도우미가 소스를 판정마다 다시 읽음 | 한 라운드 안에서는 소스를 한 번만 읽는다 |
| R2 | `checkEvidence` 가 근거 기한을 다시 계산 | 저장된 근거 기한은 뒤 폭이 바뀌어도 그대로다 |
| R3 | `IN_DOUBT` 유예를 단위별로 처음 본 값으로 저장 | 바꾼 IN_DOUBT 유예가 이미 IN_DOUBT 인 단위에 다음 라운드부터 미친다 |
| R4 | 소스가 null 일 때 기본 스냅숏을 씀 | 현장 값이 없으면 케이퍼빌리티 값으로 판정하고 설정 칸은 비어 있다 |
| R5 | 완료 순간의 윈도우를 단위에 저장해 씀 | 저장된 근거 기한과 그 라운드의 앞 폭이 함께 쓰인다 |
| R6 | 소스 읽기를 실행별 pump 로 옮김 | 한 라운드 안에서는 소스를 한 번만 읽는다 |
| B1 | `stallWindow` 검사의 상한을 뺌 | 정체 유예의 하한과 상한 밖을 잡는다 |
| I1 | 봉인에서 `inDoubtGrace` 칸을 비움 | 인시던트 의도에 봉인 라운드의 설정 버전과 시간값 넷이 실린다 |
| D1 | 설정이 없어도 해시 줄을 더함 | 현장 설정 없이 만든 번들의 요약이 설정 칸을 더하기 전과 같다 |
| E1 | `SCHEMA_VERSION` 을 `"7"` 로 | 현장 설정 칸은 내보내기에 안 실리고 내보내기 버전은 6 이다 |

- [ ] **Step 2: 새 클론 전체 빌드**

워크트리 HEAD 를 스크래치에 새로 클론해 `./gradlew build` 를 백그라운드로 돌린다(결함 주입과 겹치지 않는다). Expected: 전체 2,004, 실패 0(picasso 369, gate 276 외).

- [ ] **Step 3: 하나로 합쳐 PR**

백업 브랜치를 남기고 두 커밋을 `2ca7cd6` 위에서 하나로 합친다(트리 동일 확인). 이 계획 끝에 실행 결과 절을 더해 picasso-ops 쪽 문서 브랜치에 커밋한다. 커밋·PR 문장은 Codex·Fable 초안 취합본을 쓴다. 푸시, PR, CI `build` 를 한 번 확인한 뒤 `gh pr merge --merge`(`--delete-branch` 쓰지 않음).

- [ ] **Step 4: 머지 뒤**

khala·narrator 세션에 예고하고 열린 창이 없다는 답을 받은 뒤 picasso 메인 체크아웃을 fast-forward 한다. 내보내기 버전이 그대로라 인계 번들과 «보강» 알림은 없다. 두 세션에 머지 해시를 알린다.

## 실행 결과

- 수행: picasso 워크트리 `picasso-wt/p5-site-timings` 에서 묶음 하나(Task 1·2)를 하위 에이전트(Sonnet)가 수행, 블록은 계획에서 기계로 뽑아 둔 파일을 복사·적용, 커밋된 파일 15개를 스파이크와 바이트 대조해 모두 같음, 두 트리 해시 같음, Expected 와 다른 곳 없음
- 결함 주입: 17건(일곱 자리 7, 적용 시점 6, 범위 1, 인시던트·해시·내보내기 3) 모두 지정 시험이 탐지, 스파이크와 워크트리에서 두 번 실행
- 새 클론 빌드: 전체 빌드 시험 2,004 실패 0(picasso 369, gate 276)
- 병합: 두 커밋을 하나로 합쳐(트리 동일 확인) picasso PR #85 로 올림, CI build 통과, 머지 커밋 `195c1ee`, khala·narrator 에 알린 뒤 picasso 메인 체크아웃 갱신
- 스파이크: 하위 에이전트 하나가 코드·시험·문서를 짓고 결함 주입기를 씀, 변경 이력 §15.212 와 한계 행 문장은 Codex·Fable 초안 취합, 계획 검토(1회)가 잡은 것은 저장된 근거 기한과 그 라운드의 앞 폭이 함께 쓰이는 절반의 시험(주입 R5), 실행이 둘이어도 라운드에 한 번 읽음(주입 R6), 소스를 부르는 맥락의 KDoc 과 seams, `Intent` 불변식, 용어 한 곳
- 걸린 것: 한계 행의 해소 조건 칸에 현장이라는 낱말을 쓰면 문서 대조 시험이 외부 하위 범주로 판정해 설정 버전으로 바꿔 씀, git bash 의 `sed -i` 가 CRLF 를 LF 로 바꿔 파이썬으로 치환, 형식 훅이 파일이나 변수로 넘긴 커밋 메시지·PR 본문을 못 읽어 heredoc 으로 다시 씀
- 다음: S3c(picasso-ops)
