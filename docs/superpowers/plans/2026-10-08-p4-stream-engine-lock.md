# P4 스트림 이어 붙이기와 mimic 엔진 직렬화 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking. 단, 이 계획은 묶음(Task 1·2 / Task 3)마다 구현자 하나가 하고, 검토는 묶음이 끝난 뒤 컨트롤러가 기계 대조와 시험으로 한다.

**Goal:** 별도 프로세스의 미들웨어가 실제 시간으로 mimic 에 Netty 로 붙어도 태스크의 종료를 잃지 않고(기한으로 닫힌 `WatchTask` 스트림을 이어 붙임), 시간을 흘리는 스레드와 RPC 를 받는 스레드가 함께 돌아도 mimic 엔진이 깨지지 않게 한다.

**Architecture:** `TaskFollower`(client)는 받은 목록을 잠금으로 지키고 `ended` 를 낸다. `ClientRobotPort`(picasso)는 태스크마다 누적 목록과 지금 팔로워를 들고, 팔로워가 닫혔는데 마지막 갱신이 종료가 아니면 `update_index + 1` 부터 다시 붙는다. 미들웨어 코드는 그대로다. `MimicServer`(mimic)는 잠금 하나를 들고 계약 서비스 셋과 제어 채널을 `ServerInterceptor` 로 감싸 호출의 시작과 모든 콜백을, 그리고 `advance`·`settle`·`step`·`push` 를 그 잠금 아래에서 돌린다. 같은 프로세스의 읽는 쪽을 위해 `exclusive { }` 를 공개한다.

**Tech Stack:** Kotlin, grpc-java(`ServerInterceptor`, Netty shaded), JUnit5 + kotlin.test, in-process 하네스(`Harness`), `MimicCli`, picasso 게이트(`DocumentClaimsTest`, `CompletionCriterionTest`, `GroundTruthTest`), `tools/stamp.py`.

**근거 스펙:** picasso-ops `docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md` §5(스펙 검토 2회). P4 는 S3a 앞의 picasso PR 하나다(스펙 §3).

**스펙이 계획에 맡긴 것과 이 계획이 정한 것(스파이크에서 정함):**
- 종료 집합은 `TASK_STATE_SUCCEEDED`·`FAILED`·`CANCELLED`·`CANCELLED_RECOVERY_FAILED` 넷. 스트림 실패의 종류는 가르지 않는다(`WatchTaskResponse` 에 거부 칸이 없다).
- 다시 붙은 팔로워가 곧바로 받은 것도 같은 `watch` 호출에서 옮긴다(`take(f)` 를 두 번).
- 가로채기는 `startCall` 과 `onMessage`·`onHalfClose`·`onCancel`·`onComplete`·`onReady` 를 모두 잠금 아래에 둔다. 제어 채널은 `mimic.serialized(Service(...))` 로 같은 잠금을 쓴다.
- `Harness.client` 에 `deadlineSeconds: Long = 10` 을 더한다. 기본값이 그대로라 기존 호출과 picasso-ops 의 사용은 바뀌지 않는다.
- 시험 이름의 용어는 용어집 §8 새 이름(종료)이다. 새 KDoc 도 같다.
- 한계 레지스터: 내부 오픈 항목 두 행(`§15.210 · 재부착`, `§15.210 · 적재 잠금`), §15.176 행에 한 문장. 오픈 항목 70 → 72(내부 29 → 31). 한계 행 문구에 정답표 용어 «가르지 않» 이 들어가 `GroundTruthTest` 가 막아서 «구분하지 않» 으로 썼다.
- `docs/verification.md` 표 3행의 «실 회선 셋» 을 다섯으로(#81 의 `mimic/StartedInstanceTest` 가 이미 넷째였는데 고쳐지지 않았다), 근거 열에 `mimic/StartedInstanceTest` 와 `harness/ConcurrentEngineTest`.
- 계획 검토가 잡아 스파이크에 더한 것: `ClientRobotPort` KDoc 의 단일 호출자·호스트 잠금 전제(스펙 §5.2), `MimicServer.start()` 의 온라인 발행을 잠금 아래로, `ConcurrentEngineTest` 의 스트림 수 상한 2,000.
- 실측: 새 시험 5(`StreamResumeTest` 4, `ConcurrentEngineTest` 1), 전체 1,977 → 1,982. 결함 주입 7건.
- 문장: 커밋·PR·문서 문장은 Codex 와 Fable 초안을 취합한다.

**작업 위치 규칙(필수):**
- 모든 작업은 picasso 워크트리 `C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream`(브랜치 `fix/p4-stream-engine-lock`)에서 한다. picasso 메인 체크아웃(`C:/Users/Eisen/Desktop/Labs/[projects] picasso`, khala 가 docs/ 를 읽는다)과 다른 저장소는 건드리지 않는다. 하위 에이전트의 Bash 는 호출마다 작업 디렉터리가 돌아가므로 명령마다 `cd <워크트리> &&` 를 붙이거나 `git -C` 를 쓴다.
- `./gradlew --stop` 금지(데몬 풀이 다른 체크아웃과 공유된다). 같은 워크트리에서 Gradle 을 겹쳐 돌리지 않는다. Bash 도구의 시간 한도(600초)를 넘는 빌드는 백그라운드로 돌리고 끝났다는 알림을 받은 뒤 다음 Gradle 을 돌린다.
- `git add -A` 금지. 파일을 이름으로 더한다.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다. 아래 Expected 의 수는 XML 파일 수가 아니라 `<testcase>` 수다. 수는 `python -c "import glob,xml.etree.ElementTree as E;print(sum(len(list(E.parse(f).getroot().iter('testcase'))) for f in glob.glob('C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream/MOD/build/test-results/test/*.xml')))"` 로 센다(MOD 자리에 모듈 이름). 실패 이름은 같은 XML 에서 `<failure>` 가 있는 `<testcase>` 의 `name` 이다. 한글이 든 출력을 파이썬으로 찍을 때는 `PYTHONUTF8=1` 을 앞에 붙인다.
- 이 저장소의 작업 트리는 CRLF 다. 새 파일은 CRLF 로 둔다(뽑아 둔 파일은 이미 CRLF).
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. 커밋 메시지는 heredoc(`git commit -F - <<'EOF'`)으로 쓴다. 형식 훅이 메시지를 읽어 제목이 `type(scope): 명사구` 가 아니거나 트레일러가 없거나 겹화살괄호가 있으면 막는다.
- 이 계획의 코드와 문서는 스크래치 스파이크(`C:/Users/Eisen/AppData/Local/Temp/p4`, 브랜치 `spike/p4`, HEAD `e1e3357`)에서 시험, 전체 빌드, 결함 주입을 다 돌린 것이다. 묶음이 끝날 때마다 커밋된 파일을 스파이크와 기계 대조한다(Task 0 Step 3 의 `p4-cmp.sh`).
- 실행 방식: 묶음(Task 1·2 / Task 3)마다 구현 하위 에이전트 1명(`model: "sonnet"`), 결함 주입(Task 4)과 검토·PR 은 컨트롤러.
- **블록을 손으로 옮겨 적지 않는다.** 컨트롤러가 이 계획의 블록을 기계로 뽑아 `C:/Users/Eisen/AppData/Local/Temp/p4-patches/` 에 두었다. 새 파일은 `C:/Users/Eisen/AppData/Local/Temp/p4-patches/files/<경로>` 를 워크트리의 같은 경로로 `cp` 하고, 기존 파일은 `C:/Users/Eisen/AppData/Local/Temp/p4-patches/<이름>.patch` 를 `git apply --check` 로 본 뒤 `git apply` 한다. 아래 블록은 읽고 검토하기 위한 것이다. 뽑은 파일이 없으면 멈추고 보고한다.
- 문서를 고친 커밋 뒤에는 docs/ 만 바뀌었어도 `:picasso:test`(`GroundTruthTest` 가 docs 전체를 훑는다)와 `:gate:test` 를 둘 다 돌린다. 스탬프는 패치에 이미 들어 있다.

---

## Chunk 1: 코드와 문서

### Task 0: 워크트리, 기준선, 대조 도구

**Files:** 없음(환경)

- [ ] **Step 1: 워크트리 만들기**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso"
git fetch -q origin
git worktree add "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" -b fix/p4-stream-engine-lock origin/main
git -C "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" branch --unset-upstream
git -C "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" log --oneline -1
```
Expected: `origin/main` 이 `8f0cc04`. 다르면 멈추고 보고한다.

- [ ] **Step 2: 기준선 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && ./gradlew :picasso:test :harness:test :mimic:test :client:test :gate:test -q
```
Expected: picasso 343, harness 208, mimic 357, client 28, gate 276, 실패 0. 백그라운드로 돌린다.

- [ ] **Step 3: 대조 도구와 뽑은 블록**

`C:/Users/Eisen/AppData/Local/Temp/p4-cmp.sh` 와 `C:/Users/Eisen/AppData/Local/Temp/p4-patches/`(패치 3개, `files/` 아래 새 파일 2개)가 있는지 본다. 대조 도구는 인자로 받은 경로마다 워크트리 HEAD 의 파일과 스파이크 HEAD 의 파일을 `\r` 을 빼고 바이트 대조해 `같음`/`다름` 을 찍는다. 없으면 멈추고 보고한다.

### Task 1: 스트림 이어 붙이기와 팔로워 잠금

**Files:**
- Create: `picasso/src/test/kotlin/dev/picasso/middleware/StreamResumeTest.kt`
- Modify: `client/src/main/kotlin/dev/picasso/client/PicassoClient.kt`(`TaskFollower`), `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt`(`ClientRobotPort`), `harness/src/main/kotlin/dev/picasso/harness/Harness.kt`(`client` 의 `deadlineSeconds`)

- [ ] **Step 1: 시험 파일 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && cp "C:/Users/Eisen/AppData/Local/Temp/p4-patches/files/picasso/src/test/kotlin/dev/picasso/middleware/StreamResumeTest.kt" picasso/src/test/kotlin/dev/picasso/middleware/
```

```kotlin
package dev.picasso.middleware

import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskRequest
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ClientRobotPort] 가 기한으로 닫힌 `WatchTask` 스트림을 이어 붙인다.
 *
 * 스트림에는 `PicassoClient` 의 기한이 걸린다(기본 10초). 운영 호스트는 실제 시간으로 돌고 스킬은 그보다
 * 길다(`pick_place` 45초). 이어 붙이지 않으면 종료가 오기 전에 스트림이 닫혀 미들웨어가 종료를 영영 못 본다.
 * 시험은 기한을 1초로 주고 실제로 1초 넘게 기다려 스트림을 닫은 뒤 가상 시계로 태스크를 끝낸다.
 */
class StreamResumeTest {

    private val profile = Path.of("..", "profile", "fixtures", "no-pause.json").normalize()

    private val parameters = mapOf("object_id" to "box-7", "destination" to "dock-3")

    /** 태스크를 출발시키고 첫 갱신을 받은 뒤, 기한이 지나 스트림이 닫힐 때까지 기다린다. */
    private fun Harness.startAndOutliveDeadline(port: ClientRobotPort): TaskHandle {
        val started = port.start(ROBOT, TASK, 1, "pick_place", parameters)
        assertTrue(started.hasHandle(), "태스크 시작이 거부됐다: ${started.rejection}")
        advance(Duration.ofSeconds(1))
        assertTrue(port.watch(ROBOT, started.handle).isNotEmpty(), "첫 스트림이 아무것도 못 받았다")
        Thread.sleep(DEADLINE_MS + 700)
        return started.handle
    }

    private fun Harness.watchRequests() =
        recorder.requests.count { (_, message) -> message is WatchTaskRequest }

    @Test
    fun `기한으로 닫힌 스트림 뒤의 종료를 다음 watch 가 돌려준다`() {
        Harness(mapOf(ROBOT to profile)).use { harness ->
            val port = ClientRobotPort(harness.client(deadlineSeconds = DEADLINE_MS / 1000))
            val handle = harness.startAndOutliveDeadline(port)

            harness.advance(Duration.ofSeconds(60))
            val updates = port.watch(ROBOT, handle)

            assertEquals(TaskState.TASK_STATE_SUCCEEDED, updates.last().state, "종료를 못 봤다: ${updates.map { it.state }}")
        }
    }

    @Test
    fun `이어 붙인 목록의 갱신 번호는 늘기만 한다`() {
        Harness(mapOf(ROBOT to profile)).use { harness ->
            val port = ClientRobotPort(harness.client(deadlineSeconds = DEADLINE_MS / 1000))
            val handle = harness.startAndOutliveDeadline(port)

            harness.advance(Duration.ofSeconds(60))
            val indices = port.watch(ROBOT, handle).map { it.header.updateIndex }

            assertTrue(indices.size >= 2, "이어 붙인 것이 없다: $indices")
            assertEquals(indices.sorted().distinct(), indices, "갱신 번호가 겹치거나 뒤로 갔다: $indices")
        }
    }

    @Test
    fun `종료를 본 뒤에는 다시 붙지 않는다`() {
        Harness(mapOf(ROBOT to profile)).use { harness ->
            val port = ClientRobotPort(harness.client(deadlineSeconds = DEADLINE_MS / 1000))
            val handle = harness.startAndOutliveDeadline(port)
            harness.advance(Duration.ofSeconds(60))
            assertEquals(TaskState.TASK_STATE_SUCCEEDED, port.watch(ROBOT, handle).last().state)
            val before = harness.watchRequests()

            Thread.sleep(DEADLINE_MS + 700)
            repeat(3) { port.watch(ROBOT, handle) }

            assertEquals(before, harness.watchRequests(), "종료 뒤에 스트림을 또 열었다")
        }
    }

    @Test
    fun `여러 스레드가 동시에 넣어도 팔로워는 하나도 잃지 않는다`() {
        val follower = TaskFollower()
        val threads = 8
        val each = 5_000
        val pool = Executors.newFixedThreadPool(threads)
        val go = CountDownLatch(1)
        repeat(threads) {
            pool.execute {
                go.await()
                repeat(each) { i -> follower.onNext(update(i.toLong())) }
            }
        }
        go.countDown()
        while (!pool.awaitTermination(1, TimeUnit.MILLISECONDS)) {
            follower.updates
            if (pool.isShutdown.not()) pool.shutdown()
        }

        assertEquals(threads * each, follower.updates.size, "동시에 넣은 갱신을 잃었다")
    }

    private fun update(index: Long): WatchTaskResponse =
        WatchTaskResponse.newBuilder().setHeader(MessageHeader.newBuilder().setUpdateIndex(index)).build()

    private companion object {
        const val ROBOT = "robot-1"
        const val TASK = "task-1"
        const val DEADLINE_MS = 1000L
    }
}
```

- [ ] **Step 2: 시험이 컴파일되지 않음을 본다**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && ./gradlew :picasso:compileTestKotlin -q
```
Expected: 실패. `Harness.client` 에 `deadlineSeconds` 인자가 없다는 오류.

- [ ] **Step 3: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/p4-patches/task1.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/p4-patches/task1.patch"
```

```diff
diff --git a/client/src/main/kotlin/dev/picasso/client/PicassoClient.kt b/client/src/main/kotlin/dev/picasso/client/PicassoClient.kt
index e408175..6402d50 100644
--- a/client/src/main/kotlin/dev/picasso/client/PicassoClient.kt
+++ b/client/src/main/kotlin/dev/picasso/client/PicassoClient.kt
@@ -233,21 +233,32 @@ class PicassoClient(
         )
 }
 
-/** 열려 있는 `WatchTask` 스트림이 준 것을 모은다. */
+/**
+ * 열려 있는 `WatchTask` 스트림이 준 것을 모은다.
+ *
+ * **쓰는 스레드와 읽는 스레드가 다르다.** 네트워크 채널에서는 gRPC 스레드가 [onNext] 를 부르고 미들웨어의
+ * pump 가 [updates] 를 읽는다. 그래서 목록은 잠금 안에서만 만지고 끝 표시는 `@Volatile` 이다. in-process
+ * 채널의 `directExecutor` 는 둘이 한 스레드라 이 결함이 시험에서 안 보였다.
+ */
 class TaskFollower : StreamObserver<WatchTaskResponse> {
 
     private val received = mutableListOf<WatchTaskResponse>()
 
-    val updates: List<WatchTaskResponse> get() = received.toList()
+    val updates: List<WatchTaskResponse> get() = synchronized(received) { received.toList() }
 
+    @Volatile
     var completed: Boolean = false
         private set
 
+    @Volatile
     var error: Throwable? = null
         private set
 
+    /** 스트림이 닫혔다(정상 종료든 오류든). 닫힌 뒤에는 아무것도 더 오지 않는다. */
+    val ended: Boolean get() = completed || error != null
+
     override fun onNext(value: WatchTaskResponse) {
-        received += value
+        synchronized(received) { received += value }
     }
 
     override fun onError(t: Throwable) {
diff --git a/harness/src/main/kotlin/dev/picasso/harness/Harness.kt b/harness/src/main/kotlin/dev/picasso/harness/Harness.kt
index 5d99600..337ebb8 100644
--- a/harness/src/main/kotlin/dev/picasso/harness/Harness.kt
+++ b/harness/src/main/kotlin/dev/picasso/harness/Harness.kt
@@ -109,8 +109,9 @@ class Harness(
     val events: dev.picasso.contracts.v1.EventServiceGrpc.EventServiceBlockingStub =
         dev.picasso.contracts.v1.EventServiceGrpc.newBlockingStub(channel)
 
-    fun client(clientId: String = "line-controller", identityOverride: String? = null) =
-        PicassoClient(channel, clientId, identityOverride)
+    /** [deadlineSeconds] 는 스트림에도 걸린다. 짧게 주면 기한으로 닫힌 스트림을 만들 수 있다. */
+    fun client(clientId: String = "line-controller", identityOverride: String? = null, deadlineSeconds: Long = 10) =
+        PicassoClient(channel, clientId, identityOverride, deadlineSeconds)
 
     /** 시간을 흘리고 그것이 만든 전이를 열린 스트림까지 민다. */
     fun advance(duration: Duration) = server.advance(duration)
diff --git a/picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt b/picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt
index 1439c50..fcb6860 100644
--- a/picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt
+++ b/picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt
@@ -8,6 +8,7 @@ import dev.picasso.contracts.v1.RejectionCode
 import dev.picasso.contracts.v1.ParameterValue
 import dev.picasso.contracts.v1.StartTaskResponse
 import dev.picasso.contracts.v1.TaskHandle
+import dev.picasso.contracts.v1.TaskState
 import dev.picasso.contracts.v1.ValueType
 import dev.picasso.contracts.v1.WatchTaskResponse
 import java.time.Instant
@@ -49,13 +50,26 @@ interface RobotPort {
     fun replay(robotId: String, from: Long): Replay?
 }
 
-/** [PicassoClient] 위의 [RobotPort]. 핸들마다 팔로워 하나를 붙여 두고 그것을 읽는다. */
+/**
+ * [PicassoClient] 위의 [RobotPort]. 태스크마다 받은 갱신을 누적해 들고, 스트림이 닫히면 다시 붙는다([watch]).
+ *
+ * 태스크별 표는 잠금 없는 맵이다. 호출자는 미들웨어 하나이고 그 미들웨어를 지키는 호스트의 잠금 아래에서
+ * 부른다고 전제한다(미들웨어 자체에 스레드가 없다).
+ */
 class ClientRobotPort(private val client: PicassoClient) : RobotPort {
 
     /** `PicassoClient` 가 이미 세대별로 캐시한다 — 여기서 또 들면 두 캐시가 어긋난다. */
     override fun capabilities(robotId: String): Capability? = runCatching { client.capabilities(robotId) }.getOrNull()
 
-    private val followers = mutableMapOf<String, TaskFollower>()
+    /** 태스크마다 지금까지 받은 갱신과 지금 열린 팔로워. 팔로워는 끊기면 바뀌고 누적 목록은 남는다. */
+    private class Followed(var follower: TaskFollower) {
+        val updates = mutableListOf<WatchTaskResponse>()
+
+        /** [follower] 에서 이미 [updates] 로 옮긴 수. */
+        var taken = 0
+    }
+
+    private val followed = mutableMapOf<String, Followed>()
 
     override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse =
         client.start(
@@ -88,8 +102,31 @@ class ClientRobotPort(private val client: PicassoClient) : RobotPort {
         }
     }
 
-    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
-        followers.getOrPut(handle.taskId) { client.follow(robotId, handle, from = 0) }.updates
+    /**
+     * 지금까지 받은 갱신 **전체**를 돌려준다(미들웨어가 마지막 원소를 상태로 읽는다).
+     *
+     * 스트림은 기한(`PicassoClient` 의 `deadlineSeconds`)이 지나거나 연결이 끊기면 닫힌다. 그때 마지막 갱신이
+     * 종료가 아니면 **그 다음 갱신 번호부터 다시 붙는다** — 계약의 `from_update_index` 다. 다시 붙지 않으면
+     * 기한보다 긴 스킬의 종료를 영영 못 본다. 이어 붙인 목록은 갱신 번호가 늘기만 한다.
+     */
+    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> {
+        val f = followed.getOrPut(handle.taskId) { Followed(client.follow(robotId, handle, from = 0)) }
+        take(f)
+        if (f.follower.ended && f.updates.lastOrNull()?.state !in TERMINAL) {
+            val next = f.updates.lastOrNull()?.let { it.header.updateIndex + 1 } ?: 0L
+            f.follower = client.follow(robotId, handle, from = next)
+            f.taken = 0
+            take(f)
+        }
+        return f.updates.toList()
+    }
+
+    /** 팔로워가 새로 받은 것을 누적 목록으로 옮긴다. */
+    private fun take(f: Followed) {
+        val fresh = f.follower.updates
+        f.updates += fresh.drop(f.taken)
+        f.taken = fresh.size
+    }
 
     override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = client.cancel(robotId, handle)
 
@@ -115,6 +152,16 @@ class ClientRobotPort(private val client: PicassoClient) : RobotPort {
     } catch (_: RuntimeException) {
         null
     }
+
+    private companion object {
+        /** 이 상태 뒤에는 갱신이 없다 — 다시 붙을 까닭이 없다. */
+        val TERMINAL = setOf(
+            TaskState.TASK_STATE_SUCCEEDED,
+            TaskState.TASK_STATE_FAILED,
+            TaskState.TASK_STATE_CANCELLED,
+            TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED,
+        )
+    }
 }
 
 // ── 하류(플릿) 포트 — D 수준 위임. 프로젝트용 계약이다.
```

- [ ] **Step 4: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && ./gradlew :picasso:test --tests '*StreamResumeTest*' -q
```
Expected: `StreamResumeTest` 4, 실패 0(약 10초, 기한을 실제로 기다린다).

- [ ] **Step 5: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && git add client/src/main/kotlin/dev/picasso/client/PicassoClient.kt picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt harness/src/main/kotlin/dev/picasso/harness/Harness.kt picasso/src/test/kotlin/dev/picasso/middleware/StreamResumeTest.kt && git commit -F - <<'EOF'
fix(client): 기한에 닫힌 태스크 스트림 재부착과 팔로워 잠금

- `TaskFollower` 받은 목록 잠금, `ended` 추가
- `ClientRobotPort.watch` 의 누적 목록과 `update_index + 1` 재부착
- `Harness.client` 의 `deadlineSeconds` 인자, `StreamResumeTest` 4개

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 2: mimic 엔진 직렬화

**Files:**
- Create: `harness/src/test/kotlin/dev/picasso/harness/ConcurrentEngineTest.kt`
- Modify: `mimic/src/main/kotlin/dev/picasso/mimic/transport/MimicServer.kt`, `mimic/src/main/kotlin/dev/picasso/mimic/control/ControlServer.kt`

- [ ] **Step 1: 시험 파일 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && cp "C:/Users/Eisen/AppData/Local/Temp/p4-patches/files/harness/src/test/kotlin/dev/picasso/harness/ConcurrentEngineTest.kt" harness/src/test/kotlin/dev/picasso/harness/
```

```kotlin
package dev.picasso.harness

import dev.picasso.client.PicassoClient
import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.mimic.cli.MimicCli
import io.grpc.ManagedChannelBuilder
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 네트워크 채널에서 시간을 흘리는 스레드와 RPC 를 받는 스레드가 엔진을 함께 만져도 깨지지 않는다.
 *
 * 다른 시험은 in-process 채널의 `directExecutor` 로 한 스레드에서 돈다. 운영 배치(별도 프로세스의 호스트가
 * Netty 로 붙고, 현장이 다른 스레드에서 시계를 민다)는 그렇지 않다. 엔진에는 잠금이 없으므로 서버가 RPC 와
 * 시간 흘리기를 한 잠금으로 줄 세워야 한다. 이 시험은 열린 스트림이 많은 태스크 하나에 시간을 잘게 밀면서
 * 다른 스레드가 같은 태스크에 스트림을 계속 여는 경우를 만든다. 시간을 미는 쪽은 열린 스트림 목록을 훑고
 * RPC 쪽은 그 목록에 넣는다.
 */
class ConcurrentEngineTest {

    private val profile = Path.of("..", "profile", "fixtures", "no-pause.json").normalize()
    private val schema = Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize()

    @Test
    fun `시간을 흘리는 동안 다른 스레드가 스트림을 열어도 엔진이 깨지지 않는다`() {
        val err = StringBuilder()
        val started = assertNotNull(
            MimicCli().start(mapOf(ROBOT to profile), schema, port = 0, virtual = true, seed = 0L, err = err),
            "mimic 기동 거부: $err",
        )
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", started.server.port).usePlaintext().build()
        try {
            val client = PicassoClient(channel, "concurrency", deadlineSeconds = 30)
            val parameters = listOf(
                ParameterValue.newBuilder().setKey("object_id").setStringValue("box-7").build(),
                ParameterValue.newBuilder().setKey("destination").setStringValue("dock-3").build(),
            )
            val handle = client.start(ROBOT, TASK, 1, "pick_place", parameters).handle

            var failure: Throwable? = null
            val followers = mutableListOf<TaskFollower>()
            val stop = AtomicBoolean(false)
            val opener = thread {
                while (!stop.get() && followers.size < MAX_STREAMS) followers += client.follow(ROBOT, handle)
            }
            try {
                repeat(STEPS) { started.server.advance(Duration.ofMillis(10)) }
            } catch (e: Throwable) {
                failure = e
            } finally {
                stop.set(true)
                opener.join(TimeUnit.SECONDS.toMillis(30))
            }

            assertEquals(null, failure, "시간을 흘리다 엔진이 깨졌다: $failure")
            assertTrue(followers.size > 10, "스트림을 거의 못 열었다(${followers.size}) — 경합이 안 생겼다")
            val broken = followers.mapNotNull { it.error }.filter { it.message?.contains("DEADLINE_EXCEEDED") != true }
            assertEquals(emptyList(), broken.map { it.toString() }, "스트림이 서버 오류로 닫혔다")
        } finally {
            channel.shutdownNow()
            started.server.shutdown()
        }
    }

    private companion object {
        const val ROBOT = "robot-1"
        const val TASK = "task-1"
        const val STEPS = 3_000

        /** 서버의 실행기가 잠금을 기다리는 호출로 스레드를 끝없이 늘리지 않게 한다. */
        const val MAX_STREAMS = 2_000
    }
}
```

- [ ] **Step 2: 시험이 실패함을 본다**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && ./gradlew :harness:test --tests '*ConcurrentEngineTest*' -q
```
Expected: 실패 1(`시간을 흘리는 동안 다른 스레드가 스트림을 열어도 엔진이 깨지지 않는다`). 판정은 실패 시험 이름으로 한다. 메시지는 경합이 어느 스레드에서 터졌느냐에 따라 «시간을 흘리다 엔진이 깨졌다»(시간을 미는 쪽, 보통 `ConcurrentModificationException`) 또는 «스트림이 서버 오류로 닫혔다»(gRPC 스레드 쪽, `UNKNOWN`)다. 메시지가 달라도 멈추지 않는다. 스레드 경합이라 드물게 통과할 수 있다. 통과하면 한 번 더 돌리고, 두 번 다 통과하면 그대로 다음 Step 으로 가되 보고에 적는다.

- [ ] **Step 3: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/p4-patches/task2.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/p4-patches/task2.patch"
```

```diff
diff --git a/mimic/src/main/kotlin/dev/picasso/mimic/control/ControlServer.kt b/mimic/src/main/kotlin/dev/picasso/mimic/control/ControlServer.kt
index 4a54ece..76a4926 100644
--- a/mimic/src/main/kotlin/dev/picasso/mimic/control/ControlServer.kt
+++ b/mimic/src/main/kotlin/dev/picasso/mimic/control/ControlServer.kt
@@ -67,7 +67,7 @@ class ControlServer(
     private val mimic: MimicServer,
     builder: ServerBuilder<*>,
 ) {
-    private val server: Server = builder.addService(Service(registry, mimic)).build()
+    private val server: Server = builder.addService(mimic.serialized(Service(registry, mimic))).build()
 
     val port: Int get() = server.port
 
diff --git a/mimic/src/main/kotlin/dev/picasso/mimic/transport/MimicServer.kt b/mimic/src/main/kotlin/dev/picasso/mimic/transport/MimicServer.kt
index 29eeba5..9014a4a 100644
--- a/mimic/src/main/kotlin/dev/picasso/mimic/transport/MimicServer.kt
+++ b/mimic/src/main/kotlin/dev/picasso/mimic/transport/MimicServer.kt
@@ -1,7 +1,15 @@
 package dev.picasso.mimic.transport
 
+import io.grpc.BindableService
+import io.grpc.ForwardingServerCallListener
+import io.grpc.Metadata
 import io.grpc.Server
 import io.grpc.ServerBuilder
+import io.grpc.ServerCall
+import io.grpc.ServerCallHandler
+import io.grpc.ServerInterceptor
+import io.grpc.ServerInterceptors
+import io.grpc.ServerServiceDefinition
 
 /**
  * 계약 표면을 세운다.
@@ -20,10 +28,27 @@ class MimicServer(
 
     private val taskService = TaskServiceImpl(registry)
 
+    /**
+     * 엔진 전체를 지키는 잠금 하나.
+     *
+     * 엔진(태스크 표·로그·시계·열린 스트림)에는 잠금이 없다. 시험은 in-process 채널의 `directExecutor` 로
+     * 한 스레드에서 돌아 문제가 안 보였다. 네트워크 채널에서는 RPC 가 gRPC 스레드에서 오고 시간은 다른
+     * 스레드가 흘리므로, 둘이 같은 태스크 표를 동시에 만진다. 그래서 RPC 와 [advance] 를 이 잠금 하나로
+     * 줄 세운다. 재진입되므로 같은 스레드에서 시간을 흘리다 RPC 를 받는 in-process 시험은 그대로다.
+     */
+    private val lock = Any()
+
+    /** 이 잠금 아래에서 [block] 을 돈다. 같은 프로세스에서 엔진 상태를 읽는 쪽(현장 대역 등)이 쓴다. */
+    fun <T> exclusive(block: () -> T): T = synchronized(lock, block)
+
+    /** [service] 의 모든 호출을 [lock] 아래로 줄 세운다. 제어 채널도 이것을 지난다. */
+    fun serialized(service: BindableService): ServerServiceDefinition =
+        ServerInterceptors.intercept(service, Serialized(lock))
+
     private val server: Server = builder
-        .addService(SkillServiceImpl(registry, reporter))
-        .addService(taskService)
-        .addService(EventServiceImpl(registry))
+        .addService(serialized(SkillServiceImpl(registry, reporter)))
+        .addService(serialized(taskService))
+        .addService(serialized(EventServiceImpl(registry)))
         .build()
 
     /**
@@ -39,7 +64,7 @@ class MimicServer(
      * 자기 전진 경로를 따로 만들면 시험과 운영이 서로 다른 코드로 시간을
      * 흘리게 되고, `harness`를 다시 설계하게 된다.
      */
-    fun advance(duration: java.time.Duration) {
+    fun advance(duration: java.time.Duration) = exclusive {
         registry.clocks.forEach { it.advance(duration) }
         taskService.settleAll()
         // §7.2의 최대 발행 간격. **스케줄러가 아니라 시계가 만든다**(§12.1).
@@ -47,7 +72,7 @@ class MimicServer(
     }
 
     /** 시계를 건드리지 않고 전이만 반영해 민다. */
-    fun settle() = taskService.settleAll()
+    fun settle() = exclusive { taskService.settleAll() }
 
     /**
      * §10.5의 `Step`. 한 기체를 한 칸 돌린다.
@@ -56,14 +81,14 @@ class MimicServer(
      * 만들면 시험과 운영이 서로 다른 코드로 상태를 움직이게 되고, 밀어내기를
      * 빠뜨리면 열린 스트림이 멈춘다.
      */
-    fun step(hosted: RobotRegistry.Hosted): Int = taskService.step(hosted)
+    fun step(hosted: RobotRegistry.Hosted): Int = exclusive { taskService.step(hosted) }
 
     /**
      * 시간을 안 흘리고 이미 생긴 전이만 민다. `ForceFault`가 쓴다 —
      * 정착시키면 `CANCELLING` 창이 닫히고, 안 밀면 열린 스트림이 그 전이를
      * 통째로 놓친다.
      */
-    fun push(hosted: RobotRegistry.Hosted) = taskService.push(hosted)
+    fun push(hosted: RobotRegistry.Hosted) = exclusive { taskService.push(hosted) }
 
     val port: Int get() = server.port
 
@@ -76,7 +101,8 @@ class MimicServer(
      */
     fun start(): MimicServer = apply {
         server.start()
-        registry.hosted.forEach { it.instance.events.announceOnline() }
+        // 포트가 열린 뒤라 RPC 가 들어올 수 있다. 온라인 발행도 같은 잠금 아래에 둔다.
+        exclusive { registry.hosted.forEach { it.instance.events.announceOnline() } }
     }
 
     fun shutdown() {
@@ -87,4 +113,22 @@ class MimicServer(
     fun awaitTermination() {
         server.awaitTermination()
     }
+
+    /** 호출의 시작과 모든 콜백(요청·반쯤 닫힘·취소·완료·준비)을 한 잠금 아래에서 돈다. */
+    private class Serialized(private val lock: Any) : ServerInterceptor {
+        override fun <ReqT : Any, RespT : Any> interceptCall(
+            call: ServerCall<ReqT, RespT>,
+            headers: Metadata,
+            next: ServerCallHandler<ReqT, RespT>,
+        ): ServerCall.Listener<ReqT> {
+            val delegate = synchronized(lock) { next.startCall(call, headers) }
+            return object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(delegate) {
+                override fun onMessage(message: ReqT) = synchronized(lock) { super.onMessage(message) }
+                override fun onHalfClose() = synchronized(lock) { super.onHalfClose() }
+                override fun onCancel() = synchronized(lock) { super.onCancel() }
+                override fun onComplete() = synchronized(lock) { super.onComplete() }
+                override fun onReady() = synchronized(lock) { super.onReady() }
+            }
+        }
+    }
 }
```

- [ ] **Step 4: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && ./gradlew :mimic:test :harness:test :picasso:test -q
```
Expected: mimic 357, harness 209, picasso 347, 실패 0. 백그라운드로 돌린다.

- [ ] **Step 5: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && git add mimic/src/main/kotlin/dev/picasso/mimic/transport/MimicServer.kt mimic/src/main/kotlin/dev/picasso/mimic/control/ControlServer.kt harness/src/test/kotlin/dev/picasso/harness/ConcurrentEngineTest.kt && git commit -F - <<'EOF'
fix(mimic): 엔진 잠금으로 RPC 와 시간 흘리기 직렬화

- `MimicServer` 의 잠금, 서비스 가로채기, `exclusive`·`serialized`
- 제어 채널도 같은 잠금, `ConcurrentEngineTest` 1개

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **Step 6: 묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/p4-cmp.sh" client/src/main/kotlin/dev/picasso/client/PicassoClient.kt picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt harness/src/main/kotlin/dev/picasso/harness/Harness.kt picasso/src/test/kotlin/dev/picasso/middleware/StreamResumeTest.kt mimic/src/main/kotlin/dev/picasso/mimic/transport/MimicServer.kt mimic/src/main/kotlin/dev/picasso/mimic/control/ControlServer.kt harness/src/test/kotlin/dev/picasso/harness/ConcurrentEngineTest.kt
```
Expected: 7개 모두 `같음`.

### Task 3: 문서

**Files:**
- Modify: `CLAUDE.md`, `README.md`, `docs/limits.md`, `docs/verification.md`, `docs/superpowers/specs/2026-09-05-picasso-design.md`

- [ ] **Step 1: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/p4-patches/task3.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/p4-patches/task3.patch"
```

````diff
diff --git a/CLAUDE.md b/CLAUDE.md
index 07b2fec..89cbc84 100644
--- a/CLAUDE.md
+++ b/CLAUDE.md
@@ -8,7 +8,7 @@
 ## 1. 빌드 및 테스트 명령
 
 ```bash
-# 전체 빌드 및 테스트 실행 (총 1,977개 테스트)
+# 전체 빌드 및 테스트 실행 (총 1,982개 테스트)
 ./gradlew build
 
 # 아키텍처 및 품질 게이트 검증만 실행
@@ -72,4 +72,4 @@
 - **저장소 경계 준수**: 본 저장소 밖의 다른 저장소 파일을 직접 생성하거나 수정하지 않습니다. 계층이 서로 다른 저장소에 위치하고 상호 참조하지 않는다는 사실 자체가 아키텍처 경계의 증명이며, 편의를 이유로 한 번 넘어가면 그 증명이 소멸합니다. 다른 저장소로 넘길 산출물은 `handoff/<받는 쪽>/` 에 두고 경로만 전달하며, 무엇을 반입할지는 받는 쪽이 결정합니다.
 - **한계점 및 히스토리 관리**: 미결 과제는 [`docs/limits.md`](docs/limits.md)에 기록하며, 설계 문서 `§15`의 변경 이력은 기존 항목을 삭제하지 않고 정정 내용을 누적 기록합니다.
 
-> 마지막 대조: 2026-10-08 · sha256:2f8b69eb87b3 · 열림: 없음
+> 마지막 대조: 2026-10-08 · sha256:259d203c9510 · 열림: 없음
diff --git a/README.md b/README.md
index cd7e13b..0caf3a4 100644
--- a/README.md
+++ b/README.md
@@ -91,7 +91,7 @@ docs/vendors/             로봇이 아닌 벤더 표면의 측정 노트 (플
 
 저장소 내 대외 문서 57종은 자동화 대조 검증을 완료한 상태입니다. 문서에 명시된 모든 기술적 주장은 자동화 테스트로 증명되거나, [`docs/limits.md`](docs/limits.md)의 오픈 항목 레지스터에 등록되어 추적 관리됩니다. 각 문서 하단의 대조 스탬프(Hash Stamp)은 본문 내용과 연결되어 있어, `CompletionCriterionTest`를 통해 임의 변경 시 스탬프 갱신을 요구합니다.
 
-한계 레지스터(`limits.md`)에 등록된 미결 항목은 **70개**(내부 29개 · 소비자 대기 15개 · 외부 26개, 의도적 제외 13개 제외)이며, 그 상세 목록과 해결 조건은 `limits.md`에 명시되어 있습니다. 특히 실물 어댑터가 넷 있다(기체 셋, 플릿 하나). 다만, 어댑터 넷 중 어느 것도 실물에 붙여 보지 못했다(C-3)는 물리적 검증 한계가 존재하며, 이는 SDK 라이선스, JVM 바인딩 부재, 플릿 실기체 인스턴스 부재 등에 기인합니다.
+한계 레지스터(`limits.md`)에 등록된 미결 항목은 **72개**(내부 31개 · 소비자 대기 15개 · 외부 26개, 의도적 제외 13개 제외)이며, 그 상세 목록과 해결 조건은 `limits.md`에 명시되어 있습니다. 특히 실물 어댑터가 넷 있다(기체 셋, 플릿 하나). 다만, 어댑터 넷 중 어느 것도 실물에 붙여 보지 못했다(C-3)는 물리적 검증 한계가 존재하며, 이는 SDK 라이선스, JVM 바인딩 부재, 플릿 실기체 인스턴스 부재 등에 기인합니다.
 
 실물 넷이 계약에 얼마나 닿나 확인한 정량 분석 결과는 [`profile/distance/`](profile/distance)에서 확인할 수 있습니다. 계약 개정판은 **0.9.0** 이다.
 
@@ -140,7 +140,7 @@ client --target <host:port> --robot <id> --requirements <file> --skill <type> [-
 | **벤더 인터페이스** | [`profile/vendors/`](profile/vendors) · [`docs/vendors/orbit.md`](docs/vendors/orbit.md) | 벤더 API 표면 분석 및 플릿 관리 인터페이스 측정 노트 |
 | **현장 전제조건** | [`docs/environment-preconditions.md`](docs/environment-preconditions.md) | 로봇 도입 현장의 인프라(도어, 바닥, 조명 등) 엔지니어링 전제조건 |
 | **벤더 매니페스트** | [`tools/vendor-manifest/README.md`](tools/vendor-manifest/README.md) | 어댑터의 사우스바운드 포트 벤더 심볼 인용 대조 검증 도구 |
-| **오픈 항목 과제 레지스터** | [`docs/limits.md`](docs/limits.md) | 미결 한계 항목 70개(내부·소비자 대기·외부) 및 해결 조건 관리 레지스터 |
+| **오픈 항목 과제 레지스터** | [`docs/limits.md`](docs/limits.md) | 미결 한계 항목 72개(내부·소비자 대기·외부) 및 해결 조건 관리 레지스터 |
 
 ## 핵심 엔지니어링 규율
 
@@ -151,4 +151,4 @@ client --target <host:port> --robot <id> --requirements <file> --skill <type> [-
 - **결함 주입(Mutation Testing)**: 테스트 케이스 작성 시 의도적 결함을 주입하여 검증 유효성을 선행 확인합니다.
 - **엄격한 실패 정책**: 사전 선언된 요구 검사 목록(`--require`)을 충족하지 못하는 경우 조용한 통과를 허용하지 않습니다.
 
-> 마지막 대조: 2026-10-08 · sha256:8df3ff12389b · 열림: C-3, §15.81
+> 마지막 대조: 2026-10-08 · sha256:918c9831a625 · 열림: C-3, §15.81
diff --git a/docs/limits.md b/docs/limits.md
index 78534d0..7e3fe5b 100644
--- a/docs/limits.md
+++ b/docs/limits.md
@@ -2,7 +2,7 @@
 
 본 문서는 `picasso` 미들웨어 아키텍처 및 구현 상에 존재하는 **알려진 한계(Known Limitations), 스코프 외 제외 항목, 기술 부채 및 해소 조건**을 체계적으로 추적 관리하기 위한 엔지니어링 레지스터입니다.
 
-설계 문서의 §15는 전체 변경 이력과 배경을 누적 기록하는 변경 이력이며 (번호가 209 까지 갔고), 본 문서는 현재 시점에서 유효한 오픈 항목 항목만을 분류하여 제공합니다. 해결 완료된 항목은 변경 이력에 `(닫힘)` 처리되고 본 레지스터에서는 정리됩니다.
+설계 문서의 §15는 전체 변경 이력과 배경을 누적 기록하는 변경 이력이며 (번호가 210 까지 갔고), 본 문서는 현재 시점에서 유효한 오픈 항목 항목만을 분류하여 제공합니다. 해결 완료된 항목은 변경 이력에 `(닫힘)` 처리되고 본 레지스터에서는 정리됩니다.
 
 ---
 
@@ -44,7 +44,7 @@
 | 출처 | 오픈 항목 과제 내용 | 해소 필요 조건 |
 |---|---|---|
 | §15.183 | **자리 경쟁이 레지스터에 안 남음** — 출발 결품은 그 자재를 든 다른 자리를 계산해 `SOURCE_MISSING` 으로 레지스터에 남기나, 목적지를 남이 잡고 있어 막힌 거부는 남기지 않음. 이 계층이 계산한 응답이 없기 때문이며(잡은 쪽이 놓기를 기다리는 것 말고 제시할 것이 없음), 그래서 밖에서는 «자리를 남이 잡았다» 와 «아예 안 봤다» 가 같은 침묵임 | 잡은 쪽의 예상 해제 시각이나 대기 시퀀스를 이 계층이 아는 날. 그 전까지는 사유 텍스트만 나감 |
-| §15.176 | **승인 창구와 실행기가 시험 소스에 있음** — `picasso` 가 라이브러리라는 성질을 지키려고 담는 쪽을 시험 곁에 뒀으며, 따라서 **배포 가능한 프로세스가 아님**. 루프백에만 붙고 식별 정보를 인증하지 않으므로(§15.3) 현장에 그대로 못 올림 | 포트 여섯을 다 드는 실제 호스트가 생길 때. 그때 `ApprovalHost` 한 파일이 대체되며 `ApprovalWire` 는 그대로임 |
+| §15.176 | **승인 창구와 실행기가 시험 소스에 있음** — `picasso` 가 라이브러리라는 성질을 지키려고 담는 쪽을 시험 곁에 뒀으며, 따라서 **배포 가능한 프로세스가 아님**. 루프백에만 붙고 식별 정보를 인증하지 않으므로(§15.3) 현장에 그대로 못 올림. picasso-ops S3a 의 실행 호스트(`mission-host`)는 기체 포트와 셀 신호만 들고 승인 창구·플릿 포트가 없어 포트 여섯을 다 드는 실제 호스트라는 해소 조건에 못 미치므로 이 행은 열린 채임(§15.210) | 포트 여섯을 다 드는 실제 호스트가 생길 때. 그때 `ApprovalHost` 한 파일이 대체되며 `ApprovalWire` 는 그대로임 |
 | §15.175 | **리비전에는 조치 시퀀스를 실을 수 없음** — 개정 경로(`revise`)가 조치 시퀀스 인자를 아예 안 받음. 그래서 개정의 연쇄 거부는 조치 탐색 기록만 남기고 제안을 세우지 않으며, 이미 실행으로 선 작업 지시(버전이 같든 다르든)의 승인은 밖의 문에서 `REMEDY_NOT_APPLIED`, 사람의 문에서 같은 사유로 거절되고 시험이 듦(ADR 46). **2026-10-01 정정: 앞 버전이 적은 «v1 에서 도달 불가» 는 거짓이었음** — 소모 뒤 리비전이 오고 기체가 든 채면 개정 거부가 `pick_place` 제안을 세웠고(막힌 단위가 `navigate_to` 인 경우를 안 봤음), 버전이 거꾸로 도착하면 승인이 개정으로 들어가 성공을 내고 조치는 안 나갔음 | `revise` 가 조치 시퀀스를 받아 앞에 세우도록 배선. 그날 개정의 거부가 다시 제안을 세울 수 있으므로 ADR 46 의 소모 판정과 멱등성 키를 함께 다시 봄 |
 | §15.34 | 관측 적재 파이프라인의 MQTT 구독기 부재 (현재 In-process 직접 호출) | 브로커 구독기가 `ObservationService.recordHeader`/`recordRejection`을 호출하도록 파이프라인 구축 |
 | §15.22 | 계약 다이제스트가 디스크립터 SHA-256에 의존 | `buf` 모듈 다이제스트 생성 파이프라인과 통합 |
@@ -72,6 +72,8 @@
 | §15.190 | **공정 간 인계 안내문이 CI 의 대조 밖에 있음** — 공개 대상이 아니라 추적하지 않으므로 체크아웃에 없고, 안내문이 대는 런 식별자의 대조가 **고치는 기계에서만 돈다**. 없을 때는 무시 목록이 그것을 이름으로 드는지만 보므로 「실수로 지웠다」는 막지만 「낡았다」는 CI 가 못 잡음 | 안내문을 다시 추적하거나, 그 값을 추적되는 파일이 함께 들 때 |
 | §15.194 | **레지스터의 하위 범주를 기계가 반만 댄다** — 해소 조건이 밖의 주어를 용어로 대면 대조가 잡으나, 용어 없이 밖에 기대는 행(다른 항목의 계열만 가리키는 행이 그렇다)은 사람이 읽어야 하위 범주를 안다. §15.185 가 용어집에서 적은 것과 같은 모양임 | 행이 스스로 주어를 선언하고 대조가 그 선언을 읽을 때. 그때까지는 용어 대조가 시끄러운 쪽만 막음 |
 | §15.209 · id 겹침 | **작업 지시의 설비 id 와 대기 노드 id 가 같으면 단위 id 가 겹침**: 대기 단위의 id 는 노드 id 이고 반복 단위의 id 는 작업 지시의 설비 id 다. 둘이 같으면 리비전(단위 id 로 접음) · 인시던트(단위 id 로 찾음) · 작업 응답에서 한쪽이 가려진다. 검증기는 정의만 보므로 설비 id 를 모르고, 정의 안의 노드 id 중복만 거부함(`DUPLICATE_NODE_ID`) | 계획 시점(해석기 또는 작업 수락)에 단위 id 겹침을 거부하는 규칙을 지을 때. 설비 id 는 작업 지시가 와야 알므로 그 자리에서만 막을 수 있음 |
+| §15.210 · 재부착 | **닫힌 스트림을 실패 종류 없이 다시 붙이고 태스크별 표를 안 지움** — `ClientRobotPort.watch` 는 스트림 실패를 기한 초과·`NOT_FOUND`·`OUT_OF_RANGE` 로 구분하지 않으므로 늘 실패하는 스트림(기체가 태스크를 모름)은 `watch` 마다 다시 열리고, 그 태스크는 미들웨어의 진행 정체 판정(`stallWindow`)이 잡을 때까지 남음. 태스크별 누적 목록과 팔로워 표는 종료 뒤에도 지우지 않아 프로세스 수명 동안 늘어남 | gRPC 상태 코드로 영구 실패를 가르고 종료 뒤 표를 정리하는 규칙을 `ClientRobotPort` 에 지을 때 |
+| §15.210 · 적재 잠금 | **mimic 의 관측 적재가 엔진 잠금 아래에서 돎** — 태스크 관측 적재(`IngestBridge`, registry 로 가는 동기 HTTP, 연결 2초·요청 3초 제한)가 `MimicServer` 의 엔진 잠금 안에서 돌므로 registry 가 느리거나 죽으면 그동안 다른 RPC 와 시간 흘리기가 그 제한만큼 기다림 | 적재를 잠금 밖의 큐로 옮겨 RPC 와 시간 흘리기가 registry 응답을 안 기다리게 할 때 |
 
 ---
 
@@ -137,4 +139,4 @@
 - 하위 범주는 **해소의 주어**로 정합니다. 해소 조건에 적은 주어와 하위 범주가 어긋나면 `DocumentClaimsTest` 가 막습니다. 다만 주어를 용어로 부르지 않는 행은 기계가 못 가리므로 사람이 읽어야 합니다(§15.194).
 - 밖을 향한 문서가 드는 열림은 **139** 개다. 각 문서 하단 스탬프에 기재된 오픈 항목 ID 총합은 본 수치와 엄격히 일치해야 합니다 (`CompletionCriterionTest` 집행).
 
-> 마지막 대조: 2026-10-08 · sha256:f9fddbf0b273 · 열림: 없음
+> 마지막 대조: 2026-10-08 · sha256:1e710b774936 · 열림: 없음
diff --git a/docs/superpowers/specs/2026-09-05-picasso-design.md b/docs/superpowers/specs/2026-09-05-picasso-design.md
index 5909ad9..62fa753 100644
--- a/docs/superpowers/specs/2026-09-05-picasso-design.md
+++ b/docs/superpowers/specs/2026-09-05-picasso-design.md
@@ -3099,6 +3099,18 @@ mimic/
 
     **검사 9번이 결속을 어댑터 경계 안에 가둔다.** 탐색어는 결속 파일이 선언한 최상위 이름에서 유도하므로 타입을 더하면 금지도 저절로 는다. 훑는 모듈에 `registry` 와 `profile-model` 을 넣었다 — 검사 7의 목록에 그 둘이 없어서, 없다는 이유로 결속까지 새면 같은 구멍이 두 번째로 열린다.
 
+210. **기한으로 닫힌 태스크 스트림을 이어 붙이고 팔로워의 받은 목록과 mimic 엔진을 잠금으로 지켜, 실제 시간과 Netty 경로에서도 종료를 관측하게 했다.**
+
+    `PicassoClient.follow` 는 `WatchTask` 스트림에도 `withDeadlineAfter(deadlineSeconds)` 를 걸었고 기본값은 10초였다. 기체 스킬의 소요 시간은 12~45초다(`navigate_to` 20초, `pick_place` 45초, `inspect` 12초). `ClientRobotPort.watch` 는 태스크마다 팔로워를 한 번 만들고(`getOrPut`, `from = 0`) 그 뒤로 다시 붙지 않았으므로, 기한이 지나 스트림이 닫히면 그 뒤에 온 종료를 미들웨어가 영영 못 봤다. `TaskFollower` 의 받은 목록은 일반 리스트였는데 네트워크 채널에서는 gRPC 스레드가 쓰고 미들웨어 pump 가 읽는다. mimic 엔진(태스크 표, 로그, 열린 스트림 표, 가상 시계)에는 잠금이 없었고 모든 태스크 RPC 가 들어올 때 정착(`settle`)을 부른다. 지금까지 시험은 전부 in-process 채널의 `directExecutor` 로 한 스레드에서 돌아 시간을 흘리는 스레드와 RPC 를 받는 스레드가 같았으므로 셋 다 드러나지 않았다.
+
+    picasso-ops 의 S3a 설계 스펙(`docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md`, picasso-ops 저장소)이 미들웨어를 별도 프로세스(`mission-host`)에서 실제 시간으로 돌리며 mimic 에 Netty 로 붙고, 현장 프로세스는 다른 스레드에서 mimic 시계를 민다. 이 경로가 처음으로 실제로 쓰이므로 고쳤다(ADR 9).
+
+    `TaskFollower` 는 받은 목록을 잠금으로 지키고 `updates` 는 잠금 안에서 복사해 돌려주며 `completed`·`error` 를 `@Volatile` 로 두고, 스트림이 닫혔는지 묻는 `ended` 를 더했다. `ClientRobotPort.watch` 는 태스크마다 누적 목록과 지금 팔로워를 들고, 부를 때마다 새로 받은 것을 누적 목록에 옮긴다. 팔로워가 닫혔고 마지막 갱신이 종료(`SUCCEEDED`·`FAILED`·`CANCELLED`·`CANCELLED_RECOVERY_FAILED`)가 아니면 마지막 갱신의 `update_index + 1`(받은 것이 없으면 0)부터 다시 `follow` 하고, 새 팔로워가 곧바로 받은 것도 같은 호출에서 옮긴다. 돌려주는 것은 누적 목록 전체의 복사다. 미들웨어(`Middleware.kt`)는 고치지 않았다. 미들웨어는 `watch` 가 매번 누적 전체 목록을 준다고 전제하고 마지막 원소만 상태로 옮기므로 그 전제가 그대로 선다. 계약의 `from_update_index` 를 쓰며 새 계약 칸은 없다. 스트림의 실패 종류(기한 초과, `NOT_FOUND`, `OUT_OF_RANGE`)는 가르지 않는다. `WatchTaskResponse` 에 거부 칸이 없고 실패는 gRPC 상태로만 온다. `MimicServer` 는 잠금 하나를 둔다. 계약 서비스 셋(Skill·Task·Event)과 제어 채널(`ControlServer`)을 `ServerInterceptor` 로 감싸 호출의 시작과 모든 콜백(요청·반쯤 닫힘·취소·완료·준비)이 그 잠금 아래에서 돌고, `advance`·`settle`·`step`·`push` 도 같은 잠금 아래에서 돈다. 같은 프로세스에서 엔진 상태를 읽는 쪽을 위해 `exclusive { }` 와 `serialized(service)` 를 공개했다. 잠금은 재진입되므로 in-process 시험은 그대로 돈다. `Harness.client` 에 `deadlineSeconds` 인자(기본 10)를 더했고 기본값이 그대로라 기존 호출은 안 바뀐다. 계약 버전과 내보내기 버전은 그대로다.
+
+    시험 5개를 더해 총수가 1,977 에서 1,982 가 됐다. `StreamResumeTest` 4개는 기한으로 닫힌 스트림 뒤의 종료를 다음 `watch` 가 돌려주는지, 이어 붙인 목록의 갱신 번호가 늘기만 하는지, 종료를 본 뒤에는 다시 붙지 않는지(서버가 받은 `WatchTaskRequest` 수로 센다), 여러 스레드가 동시에 넣어도 팔로워가 하나도 안 잃는지를 본다. 스트림 기한을 1초로 주고 실제로 1초 넘게 기다려 스트림을 닫은 뒤 가상 시계를 밀어 `pick_place` 를 종료시킨다. `ConcurrentEngineTest` 1개는 `MimicCli` 로 Netty 서버를 띄우고 한 스레드가 10ms 씩 3,000번 시간을 흘리는 동안 다른 스레드가 같은 태스크에 스트림을 계속 열어도 엔진이 안 깨지는지를 본다. 기존 시험은 고치지 않고 통과했다. 결함 주입 7건이 모두 이름 있는 시험으로 잡혔다: 이어 붙이기 제거, `+1` 제거, 종료 집합 비우기, 새 팔로워의 옮긴 수를 0 으로 안 돌림, `onNext` 의 잠금 제거, 서비스 가로채기 제거, `advance` 의 잠금 제거. 가로채기 제거는 세 번 돌려 세 번 잡혔다. 처음 짠 주입 실행기는 시험을 아예 못 돌리고 «놓침» 만 냈다(파이썬 `subprocess` 의 `bash` 가 다른 셸로 풀렸다). 결과 XML 이 없으면 «시험 결과 없음» 으로 멈추게 고쳐 다시 돌렸다. 1차 설계 검토가 엔진 잠금 부재를 막는 것으로 잡았고, 첫 스파이크는 스트림 이어 붙이기만 고쳤었다.
+
+    남긴 것은 한계 §15.210 의 행 둘이다. 실패 종류를 안 가르므로 늘 실패하는 스트림(기체가 태스크를 모름)은 `watch` 마다 다시 열리고, 그 태스크는 미들웨어의 진행 정체 판정(`stallWindow`)이 잡을 때까지 남는다. 태스크별 누적 목록과 팔로워 표를 지우지 않아 프로세스 수명 동안 는다. mimic 의 태스크 관측 적재(`IngestBridge`, registry 로 가는 동기 HTTP, 연결 2초·요청 3초 제한)는 엔진 잠금 아래에서 돌아 registry 가 느리거나 죽으면 그동안 다른 RPC 와 시간 흘리기가 기다린다. §15.176(승인 창구와 실행기가 시험 소스에 있음)은 열린 채다. picasso-ops 의 실행 호스트는 기체 포트와 셀 신호만 들고 승인 창구·플릿 포트가 없어 포트 여섯을 다 드는 실제 호스트라는 해소 조건에 못 미친다.
+
 209. **임무 정의를 검증을 지나야 활성화되는 데이터 판으로 옮기고, 설비 대기를 셋째 경로로 더했으며, 실행이 생성 때 쥔 임무 판으로 개정판까지 끝나게 했다.**
 
     임무 정의는 코드였다. `PrepareSequencedRack.plan()` 이 주문의 destination 마다 `pick_place` 단위 하나를 내고, 제시 자리는 material 로 짝을 지어 뒤엣것이 이기며, 짝이 없으면 계획 때 `FAILED`(`NO_SOURCE_FOR_MATERIAL`)였다. `Middleware` 는 케이퍼빌리티 목록을 workMasterId 로 묶은 맵을 생성 때 쥐었고 실행 중에 바꿀 길이 없었다. 임무 판 칸은 없었다. 실행은 케이퍼빌리티 객체를 쥐었으나 `submit` 이 개정판으로 가기 전에 그 맵을 다시 읽었다. 경로는 `ROBOT`·`FLEET` 둘이었고 진행 루프의 분기는 «로봇이 아니면 플릿» 이었다. 셀 신호 포트에는 이름으로 읽는 신호가 없었다. 바깥 첫 소비자 picasso-ops 의 운영 관리 화면 설계 제안 §10 입증 항목 3(임무 하나를 데이터 판으로: 모의 실행 → 활성화 → 도는 실행 중 새 판 전환 → 옛 실행은 옛 판으로 끝남)·4(신호 사양에 없는 신호를 참조하는 변경이 활성화에서 거절됨)와 P3 스펙(`docs/superpowers/specs/2026-10-08-p3-mission-definition-versions-design.md`, picasso-ops 저장소)이 이것을 정했다. 2026-10-08 사용자 결정은 임무 판을 먼저 picasso 에 짓고 picasso 시험 위에서 입증하며 노드형 스키마와 설비 대기까지 엔진에 구현하는 것이다. 소비자가 생겨서 지었다(ADR 9, ADR 50).
diff --git a/docs/verification.md b/docs/verification.md
index c248748..aa7a634 100644
--- a/docs/verification.md
+++ b/docs/verification.md
@@ -1,6 +1,6 @@
 # 시스템 검증 충실도 및 환경 신뢰도 매트릭스 (Verification & Fidelity Matrix)
 
-본 문서는 `picasso` 미들웨어 시스템의 1,977개 자동화 테스트가 **어느 구간에서 실제 외부 시스템/하드웨어와 연동되고, 어느 구간에서 모의 대역(Mock/In-process)에 의존하는지**를 명확히 구분하여 기술적 검증 신뢰도(Verification Fidelity)를 투명하게 공개하기 위해 작성되었습니다.
+본 문서는 `picasso` 미들웨어 시스템의 1,982개 자동화 테스트가 **어느 구간에서 실제 외부 시스템/하드웨어와 연동되고, 어느 구간에서 모의 대역(Mock/In-process)에 의존하는지**를 명확히 구분하여 기술적 검증 신뢰도(Verification Fidelity)를 투명하게 공개하기 위해 작성되었습니다.
 
 ---
 
@@ -25,7 +25,7 @@
 |---|---|---|---|---|---|
 | 1 | 상위(MES·WMS·SCADA) ↔ `picasso` | **없음** | **자체 정의** (ISA-95 작업 제어 기반) | 실제 상위 시스템과의 통신은 부재하며, 통합 테스트가 소비자로 기능하여 `JobOrder` 발주 및 `JobResponse` 수신 검증. 실패 시 운영자에게 나가는 인시던트 번들(생성 시점·근거 윈도우·해시 결정성)도 이 구간에서 대조 | `SequencingRackTest` · `DeliverContainerTest` · `InspectAssetTest` · `IncidentBundleTest` · `EffectMismatchTest` · `RemedyApprovalTest` · `IncidentReviewTest` · `WithholdingTest` |
 | 2 | `picasso` ↔ 계약(gRPC) | **실 코드 · 전송 없음** | **자체 계약** (proto 0.9.0) | 직렬화, 서비스 스텁, 헤더 파이프라인 전체를 실코드로 구동하되 In-process 채널 사용 | 상동 + `EventStreamTest` |
-| 3 | 계약 ↔ 에뮬레이터 | **실 코드 · 전송 없음** + **실 회선 셋** | 자체 계약 | `mimic`은 기종 프로파일 기반 결정론적 에뮬레이터임. 대부분 In-process이며 3개 경로는 실제 TCP 포트(`ServerBuilder.forPort`)를 통해 통신 | `ContractSuite` 전체 · 실 포트 테스트(`mimic/MainTest`, `harness/MqttBrokerTest`) |
+| 3 | 계약 ↔ 에뮬레이터 | **실 코드 · 전송 없음** + **실 회선 다섯** | 자체 계약 | `mimic`은 기종 프로파일 기반 결정론적 에뮬레이터임. 대부분 In-process이며 5개 경로는 실제 TCP 포트(`ServerBuilder.forPort`)를 통해 통신 | `ContractSuite` 전체 · 실 포트 테스트(`mimic/MainTest`, `mimic/StartedInstanceTest`, `harness/MqttBrokerTest`, `harness/ConcurrentEngineTest`) |
 | 4 | 계약 ↔ 어댑터 호스트 | **실 코드 · 전송 없음** + **실 TCP 둘** | 자체 계약 | Netty 기반 실소켓 통신을 검증하는 경로(`OrbitLauncherTest` 및 포트 오픈 후 ONLINE 발행 순서 검증) 외에는 In-process 구동 | `AdapterHostTest` · `HostParityTest` · `OrbitLauncherTest` |
 | 5 | 어댑터 ↔ 벤더 — **Orbit** | **실 회선 · 세운 상대** | **벤더 공식 명세** (OpenAPI + SDK) | 실제 HTTP/HTTPS 프로토콜(쿠키, Bearer 토큰, 상태코드)을 통과하며, 응답 서버는 `com.sun.net.httpserver` 기반 스텁 연동 | `OrbitHttpLinkTest` · `OrbitLauncherTest` |
 | 6 | 어댑터 ↔ 벤더 — **Spot · Digit · G1** | **없음** | **벤더 공식 명세** (SDK Proto·IDL·매뉴얼) | 저장소 내 벤더 독점 SDK 배제 원칙에 따라 네트워크 전송은 수행하지 않으며, `@VendorSurface` 선언과 `vendor-manifest.txt` 간의 심볼 대조 검증 수행 | `*VendorSurfaceTest` 넷 |
@@ -45,4 +45,4 @@
 2. **로봇 인터페이스 계층의 격리성**: 어댑터 계층은 벤더 SDK 격리 원칙에 따라 매니페스트 대조를 통해 정합성을 검증하며, 실기체 직접 연동(C-3)은 환경적 제약으로 인해 오픈 항목 상태로 명시 관리됩니다.
 3. **상위 및 설비 연계 계층의 가정 기반성**: 설비(PLC) 및 AMR 플릿과의 연동 규격은 시스템적 일관성을 입증하기 위한 자체 설계 모델이며, 실제 현장 도입 시 대상 설비에 맞춘 Seam 어댑터 구현이 요구됩니다.
 
-> 마지막 대조: 2026-10-08 · sha256:31de97d48e01 · 열림: C-3
+> 마지막 대조: 2026-10-08 · sha256:274794deafb7 · 열림: C-3
````

- [ ] **Step 2: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && ./gradlew :gate:test :picasso:test -q
```
Expected: gate 276, picasso 347, 실패 0. 백그라운드로 돌린다.

- [ ] **Step 3: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" && git add CLAUDE.md README.md docs/limits.md docs/verification.md docs/superpowers/specs/2026-09-05-picasso-design.md && git commit -F - <<'EOF'
docs(p4): 변경 이력 210 과 한계 행 둘, 시험 수 갱신

- 설계 문서 변경 이력 §15.210, 한계 레지스터 내부 오픈 항목 둘과 §15.176 문장
- 시험 수 1,982, 오픈 항목 72, 검증 표 3행의 실 회선 다섯

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **Step 4: 묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/p4-cmp.sh" CLAUDE.md README.md docs/limits.md docs/verification.md docs/superpowers/specs/2026-09-05-picasso-design.md && git -C "C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" rev-parse 'HEAD^{tree}' && git -C "C:/Users/Eisen/AppData/Local/Temp/p4" rev-parse 'HEAD^{tree}'
```
Expected: 5개 모두 `같음`, 두 트리 해시가 같다.

## Chunk 2: 검증과 병합(컨트롤러)

### Task 4: 결함 주입, 새 클론 빌드, PR

- [ ] **Step 1: 결함 주입 7건**

```bash
cd "C:/Users/Eisen/AppData/Local/Temp/p4-inject" && P4_ROOT="C:/Users/Eisen/Desktop/Labs/picasso-wt/p4-stream" PYTHONUTF8=1 python inject.py
```
Expected: J1~J5, K1·K2 모두 `탐지`, 지정한 시험 이름이 실패 목록에 있다.

| id | 주입 | 잡는 시험 |
|---|---|---|
| J1 | 재부착 조건을 거짓으로 | 기한으로 닫힌 스트림 뒤의 종료를 다음 watch 가 돌려준다 |
| J2 | `update_index + 1` 을 `update_index` 로 | 이어 붙인 목록의 갱신 번호는 늘기만 한다 |
| J3 | 종료 집합을 빈 집합으로 | 종료를 본 뒤에는 다시 붙지 않는다 |
| J4 | 새 팔로워의 옮긴 수를 0 으로 안 돌림 | 기한으로 닫힌 스트림 뒤의 종료를 다음 watch 가 돌려준다 |
| J5 | `onNext` 의 잠금 제거 | 여러 스레드가 동시에 넣어도 팔로워는 하나도 잃지 않는다 |
| K1 | 서비스 가로채기 제거 | 시간을 흘리는 동안 다른 스레드가 스트림을 열어도 엔진이 깨지지 않는다 |
| K2 | `advance` 의 잠금 제거 | 같은 시험 |

- [ ] **Step 2: 새 클론 전체 빌드**

워크트리 HEAD 를 스크래치에 새로 클론해 `./gradlew build` 를 백그라운드로 돌린다. Expected: 전체 1,982, 실패 0(picasso 347, harness 209, mimic 357, gate 276 외).

- [ ] **Step 3: 하나로 합쳐 PR**

백업 브랜치를 남기고 세 커밋을 `8f0cc04` 위에서 하나로 합친다(트리 동일 확인). 커밋·PR 문장은 Codex·Fable 초안 취합본을 쓴다. 푸시, PR, CI `build` 를 한 번 확인한 뒤 `gh pr merge --merge`(`--delete-branch` 쓰지 않음).

- [ ] **Step 4: 머지 뒤**

khala·narrator 세션에 예고하고 열린 창이 없다는 답을 받은 뒤 picasso 메인 체크아웃을 fast-forward 한다. 내보내기 버전이 그대로라 인계 번들과 «보강» 알림은 없다. 두 세션에 머지 해시를 알린다.

## 실행 결과

- 수행: picasso 워크트리에서 묶음 둘(Task 1·2, Task 3)을 하위 에이전트(Sonnet)가 수행, 블록은 계획에서 기계로 뽑아 둔 파일을 복사·적용, 묶음마다 커밋된 파일을 스파이크와 바이트 대조해 12개 모두 같음, 워크트리 트리(`856c5ab`)가 스파이크(`e1e3357`)의 트리와 동일, 계획의 `Expected` 와 다른 곳 없음, Task 2 Step 2 의 잠금 전 시험은 첫 실행에서 `ConcurrentModificationException` 으로 기대대로 실패
- 결함 주입: 7건(스트림 이어 붙이기 4, 팔로워 잠금 1, 엔진 잠금 2) 모두 지정 시험이 탐지, 스파이크와 워크트리에서 두 번 실행
- 새 클론 빌드: picasso 전체 시험 1,982 실패 0(16개 모듈, picasso 347, harness 209, mimic 357, gate 276 외)
- 병합: 구현 커밋 셋을 `8f0cc04` 위에서 하나로 합침(`9771d5e`, 트리 동일), picasso PR #83 으로 올림, CI `build` 초록, 2026-10-08 19:00 KST 머지(머지 커밋 `1e3f4ae`)
- 머지 뒤 체크아웃: khala·narrator 세션에 예고하고 열린 창이 없다는 응답을 받은 뒤 picasso 메인 체크아웃을 `1e3f4ae` 로 `fast-forward`, 두 세션에 머지 해시와 바뀐 문서 알림, 내보내기 버전이 그대로라 인계 번들과 보강 세션 알림은 없음
- 걸린 것: 1차 스펙 검토가 mimic 엔진의 잠금 부재를 막는 것으로 잡아 P4 범위 확장(첫 스파이크는 스트림 이어 붙이기만), 주입 실행기가 파이썬 `subprocess` 의 `bash` 가 다른 셸로 풀려 시험을 못 돌리고 놓침만 낸 것을 결과 XML 이 없으면 멈추게 수정, 한계 행 문구가 정답표 용어와 겹쳐 `GroundTruthTest` 가 막은 것을 다른 낱말로 교체, 계획 검토가 옛 이름을 종료로 기계 치환하며 어긋난 조사(종료을)와 검증 문서의 실 회선 수(실제로 다섯)를 잡아 정정, 스탬프 도구가 LF 로 쓴 것을 CRLF 로 되돌림
- 다음: S3a(picasso-ops: site 시계·셀 대역, `mission-host`, 운영 서비스 배정 가능·작업 지시, 화면 운영 영역, 통합 시험·Playwright)
