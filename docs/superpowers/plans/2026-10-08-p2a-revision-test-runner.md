# P2a 리비전 시험 실행기 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** picasso 에 리비전 시험의 «요청 → 집기 → 3종 실행 → 보고 → `TESTED`» 고리를 짓고 PR 로 올린다. registry 에 문 3개와 `V16` 을, harness 운영 코드에 실행기와 시험 3종(`CONTRACT`·`NEGATIVE`·`DETERMINISM`)을 둔다.

**Architecture:** registry 의 `TestRequestService` 가 요청(`requestTest`)·집기(`claim`)·보고(`report`)를 결과 타입으로 가르고, 새 `TestRequestController` 가 요청은 조작 문(`/operations`, 운영자 토큰), 집기·보고는 적재 문(`/ingest`, 적재 토큰)에 둔다. 보고는 실행 3행·요청의 «끝남»·승격을 한 트랜잭션으로 남기며 `BindingService` 의 기록·승격 규칙(`insertRun`·`promoteIfAllPass`)을 함께 쓴다. harness 의 `dev.picasso.harness.revision` 패키지가 `MinimalParameters`(선언에서 값 만들기)·`RevisionSuites`(3종)·`RevisionTestRunner`(폴링·보고, `HttpTestDesk`)를 든다. harness 운영 코드는 `:registry` 를 모르고 HTTP 로만 닿는다.

**Tech Stack:** Kotlin 2.4.20, Spring Boot 3.4.0(MVC), PostgreSQL + Flyway(Testcontainers, registry `testFixtures` 의 `PostgresSupport`), gRPC in-process(harness `Harness`), JDK `HttpClient`, JUnit5 + kotlin.test, `TestRestTemplate`.

**근거 스펙:** picasso-ops `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` §5(P2a), §3(완료 판정), §11(시험). 2차 스펙 검토 권고 중 P2a 몫(집은 시각 정밀도, 동시 요청, 보고 409 의 `reason`, 멱등 응답의 감사, 게이트 자리)도 이 계획이 반영한다.

**스펙과 다른 것(이 계획이 정함, 같은 커밋에서 스펙을 고쳤다):** 스펙 §11 은 «`humanoid-a` 로 두 번째 실행의 시드를 바꾸면 `DETERMINISM` FAIL» 이라 적었다. 실측하니 `java.util.Random` 은 이웃한 작은 시드의 첫 난수가 거의 같아, 시드 0~6 이 모두 `pick_place` 를 48초째 끝내고 자취가 같았다. 시드 +1 은 등가 변이이므로 주입은 먼 시드(987654321)로 한다(Task 5 H3).

**작업 위치 규칙(필수):**
- picasso 메인 체크아웃(`C:\Users\Eisen\Desktop\Labs\[projects] picasso`)에서 docs/ 를 고치지 않는다. khala 가 그 작업 트리의 docs/ 를 매시간 코퍼스로 읽는다. 모든 작업은 저장소 밖 워크트리 `C:/Users/Eisen/Desktop/Labs/picasso-wt/p2a-revision-test-runner` 에서 한다.
- `./gradlew --stop` 금지(데몬 풀이 사용자 체크아웃과 공유된다). 같은 워크트리에서 Gradle 을 겹쳐 돌리지 않는다(시험 결과의 binary 가 깨진다).
- `git add -A` 금지. 파일을 이름으로 더한다.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다.
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. 커밋 메시지는 heredoc(`git commit -F - <<'EOF'`)으로만 쓴다. 형식 훅(`.claude/hooks/check-commit-pr-format.py`)이 `-F 파일`, `-m` 두 번, 제목이 `type(scope): 명사구` 가 아닌 것을 막는다.
- 이 계획의 코드와 문서는 스크래치 스파이크(`C:/Users/Eisen/AppData/Local/Temp/p2a`, 브랜치 `feat/revision-test-runner`, HEAD `a16c1c1`)에서 시험과 결함 주입을 다 돌린 것이다. 묶음이 끝날 때마다 커밋된 파일을 스파이크와 기계 대조한다(Task 0 Step 4 의 `cmp.sh`).
- 실행 방식: 묶음(Task 1·2 / 3·4 / 5·6)마다 구현 하위 에이전트 1명(`model: "sonnet"`), 검토는 컨트롤러. 새 파일은 아래 내용 그대로 쓰고, 기존 파일은 아래 패치를 워크트리 밖 `C:/Users/Eisen/AppData/Local/Temp/p2a-patches/` 에 저장해 `git apply` 로 넣는다(CRLF 작업 트리에서 적용됨을 실측했다). 패치를 워크트리 안에 두면 `git status` 에 남고 실수로 커밋될 수 있다.

---

## Chunk 1: registry

### Task 0: 워크트리, 기준선, 대조 도구

**Files:** 없음(환경)

- [ ] **Step 1: 워크트리 만들기**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso"
git fetch origin -q
git worktree add "C:/Users/Eisen/Desktop/Labs/picasso-wt/p2a-revision-test-runner" -b feat/revision-test-runner origin/main
git -C "C:/Users/Eisen/Desktop/Labs/picasso-wt/p2a-revision-test-runner" branch --unset-upstream
```
Expected: `origin/main` 이 `6b1a255`. 다르면 멈추고 보고한다(아래 패치의 기준이 `6b1a255` 다). 이후 모든 명령은 워크트리에서 돈다.

- [ ] **Step 2: Docker 확인**

Run: `docker info --format '{{.ServerVersion}}'`
Expected: 버전 문자열. 안 나오면 멈춘다(registry·harness 시험이 Testcontainers 를 쓴다).

- [ ] **Step 3: 기준선 시험**

Run: `./gradlew :registry:test :harness:test :gate:test --continue -q`
Expected: XML 기준 실패 0(registry 351, harness 194, gate 276 — 개수가 다르면 그 수를 적어 두고 진행). 실패가 있으면 멈추고 보고한다.

- [ ] **Step 4: 대조 도구**

`C:/Users/Eisen/AppData/Local/Temp/p2a-cmp.sh` 를 만든다.

```bash
#!/usr/bin/env bash
# usage: p2a-cmp.sh 경로...  워크트리의 커밋된 파일과 스파이크 HEAD 의 파일을 줄바꿈을 뺀 채 바이트 대조한다.
W="C:/Users/Eisen/Desktop/Labs/picasso-wt/p2a-revision-test-runner"
S="C:/Users/Eisen/AppData/Local/Temp/p2a"
bad=0
for p in "$@"; do
  if cmp -s <(git -C "$W" show "HEAD:$p" | tr -d '\r') <(git -C "$S" show "HEAD:$p" | tr -d '\r'); then echo "같음 $p"; else echo "다름 $p"; bad=1; fi
done
exit $bad
```

### Task 1: 시험 요청 서비스와 V16

**Files:**
- Create: `registry/src/main/resources/db/migration/V16__test_request_completion.sql`
- Modify: `registry/src/main/kotlin/dev/picasso/registry/binding/BindingService.kt`(실행 기록·승격을 `insertRun`·`promoteIfAllPass` 로 가름)
- Replace: `registry/src/main/kotlin/dev/picasso/registry/testing/TestRequestService.kt`
- Test: `registry/src/test/kotlin/dev/picasso/registry/RevisionTestRequestTest.kt`

- [ ] **Step 1: 실패하는 시험 쓰기** — 아래 내용 그대로.

```kotlin
package dev.picasso.registry

import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import dev.picasso.registry.testing.ReportOutcome
import dev.picasso.registry.testing.SuiteRun
import dev.picasso.registry.testing.TestRequestService
import dev.picasso.registry.testing.TestRequested
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 시험 요청의 요청·집기·보고(picasso-ops P2·S1d 스펙 §5.1). **실행기가 돌린 결과만, 그 실행기가 집은 요청에만 붙는다.**
 *
 * 요청이 멱등이고, 끝난 요청이 다시 집히지 않고, 보고가 실행 3행과 «끝남» 과 승격을 한 번에 남기는지 본다.
 * 시계는 시험이 쥔다 — 만료를 실시간으로 기다리면 시험이 15분 걸린다.
 */
class RevisionTestRequestTest {

    private lateinit var db: Db
    private lateinit var requests: TestRequestService
    private var clock: Instant = Instant.parse("2026-10-08T00:00:00Z")

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        requests = TestRequestService(db, now = { clock })
        SkillTypeSync(db).sync(Fixtures.descriptor(), CONTRACT_SEMVER, "sync")
    }

    private fun validated(): Long {
        val stored = RevisionService(db, Fixtures.validator()).submit(Fixtures.good(), "op") as SubmitOutcome.Stored
        assertEquals("VALIDATED", statusOf(stored.profileRevisionId), "사유: ${stored.reasons}")
        return stored.profileRevisionId
    }

    private fun runs(contract: String = "PASS", negative: String = "PASS", determinism: String = "PASS") = listOf(
        SuiteRun("CONTRACT", contract, """{"checks":3,"failures":[]}"""),
        SuiteRun("NEGATIVE", negative, """{"checks":5,"failures":[]}"""),
        SuiteRun("DETERMINISM", determinism, """{"checks":2,"failures":[]}"""),
    )

    @Test
    fun `요청은 멱등이고 감사는 처음 한 번만 남는다`() {
        val revision = validated()

        val first = requests.requestTest(revision, "engineer/kim")
        val second = requests.requestTest(revision, "engineer/kim")

        assertIs<TestRequested.Created>(first)
        assertEquals(TestRequested.Existing(first.requestId), second)
        assertEquals(1, auditCount("TEST_REQUEST"), "멱등 응답이 일어나지 않은 요청을 감사에 남겼다")
    }

    @Test
    fun `없는 개정판과 DRAFT·REVOKED 는 시험을 요청할 수 없다`() {
        assertEquals(TestRequested.UnknownRevision, requests.requestTest(999_999, "op"))

        val revision = validated()
        listOf("DRAFT", "REVOKED").forEach { status ->
            PostgresSupport.execute("UPDATE profile_revision SET status = '$status' WHERE profile_revision_id = $revision")
            assertEquals(TestRequested.NotTestable(status), requests.requestTest(revision, "op"))
        }
    }

    @Test
    fun `셋 다 PASS 면 TESTED 이고 실행 3행에 요청 id 와 상세가 남는다`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!

        assertEquals(ReportOutcome.Recorded("TESTED"), requests.report(requestId, "site-runner", claimed.claimedAt, runs()))

        val rows = PostgresSupport.queryAll(
            "SELECT suite, request_id, ran_by, detail->>'checks' FROM revision_test_run " +
                "WHERE profile_revision_id = $revision ORDER BY suite",
        ) { "${it.getString(1)}/${it.getLong(2)}/${it.getString(3)}/${it.getString(4)}" }
        assertEquals(
            listOf("CONTRACT/$requestId/site-runner/3", "DETERMINISM/$requestId/site-runner/2", "NEGATIVE/$requestId/site-runner/5"),
            rows,
        )
    }

    @Test
    fun `하나라도 FAIL 이면 VALIDATED 에 머문다`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!

        assertEquals(
            ReportOutcome.Recorded("VALIDATED"),
            requests.report(requestId, "site-runner", claimed.claimedAt, runs(negative = "FAIL")),
        )
    }

    @Test
    fun `끝난 요청은 만료가 지나도 다시 집히지 않는다`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!
        requests.report(requestId, "site-runner", claimed.claimedAt, runs())

        clock = clock.plus(Duration.ofMinutes(16))
        assertNull(requests.claim("site-runner"), "끝난 요청이 다시 집혔다 — 같은 개정판을 끝없이 다시 돈다")
    }

    @Test
    fun `끝난 뒤의 요청은 새 요청이다`() {
        val revision = validated()
        val first = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        requests.report(first, "site-runner", requests.claim("site-runner")!!.claimedAt, runs())

        val second = requests.requestTest(revision, "op")
        assertIs<TestRequested.Created>(second)
        assertTrue(second.requestId != first, "끝난 요청을 열린 요청으로 돌려줬다")
    }

    @Test
    fun `다른 실행기의 보고는 받지 않는다`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!

        assertEquals(ReportOutcome.NotClaimer, requests.report(requestId, "other-runner", claimed.claimedAt, runs()))
    }

    @Test
    fun `같은 이름으로 다시 집은 요청에 옛 집은 시각의 보고는 받지 않는다`() {
        // 실행기 이름은 같은 이름으로 다시 뜰 수 있다. 이름만 보면 죽은 실행기의 늦은 보고가 새 실행 결과로 섞인다.
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val stale = requests.claim("site-runner")!!

        clock = clock.plus(Duration.ofMinutes(16))
        val fresh = requests.claim("site-runner")!!

        assertEquals(ReportOutcome.NotClaimer, requests.report(requestId, "site-runner", stale.claimedAt, runs()))
        assertEquals(ReportOutcome.Recorded("TESTED"), requests.report(requestId, "site-runner", fresh.claimedAt, runs()))
    }

    @Test
    fun `만료가 지났어도 다시 집히기 전이면 보고를 받는다`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!

        clock = clock.plus(Duration.ofMinutes(16))
        assertEquals(ReportOutcome.Recorded("TESTED"), requests.report(requestId, "site-runner", claimed.claimedAt, runs()))
    }

    @Test
    fun `이미 끝난 요청과 없는 요청`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!
        requests.report(requestId, "site-runner", claimed.claimedAt, runs())

        assertEquals(ReportOutcome.AlreadyCompleted, requests.report(requestId, "site-runner", claimed.claimedAt, runs()))
        assertEquals(ReportOutcome.UnknownRequest, requests.report(999_999, "site-runner", claimed.claimedAt, runs()))
    }

    @Test
    fun `스위트가 빠지거나 겹치거나 모르는 결과면 받지 않는다`() {
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val at = requests.claim("site-runner")!!.claimedAt

        assertIs<ReportOutcome.BadResults>(requests.report(requestId, "site-runner", at, runs().drop(1)))
        assertIs<ReportOutcome.BadResults>(requests.report(requestId, "site-runner", at, runs() + runs().first()))
        assertIs<ReportOutcome.BadResults>(requests.report(requestId, "site-runner", at, runs(contract = "SKIPPED")))
        assertEquals(0, PostgresSupport.queryOne("SELECT count(*) FROM revision_test_run") { it.getInt(1) })
    }

    @Test
    fun `집은 시각은 DB 에 적힌 값 그대로다`() {
        // 메모리의 시각(나노초)을 내주면 DB(마이크로초)와 어긋나 모든 보고가 거절된다.
        clock = Instant.parse("2026-10-08T00:00:00.123456789Z")
        val revision = validated()
        val requestId = (requests.requestTest(revision, "op") as TestRequested.Created).requestId
        val claimed = requests.claim("site-runner")!!

        assertEquals(Instant.parse("2026-10-08T00:00:00.123457Z"), claimed.claimedAt)
        assertEquals(ReportOutcome.Recorded("TESTED"), requests.report(requestId, "site-runner", claimed.claimedAt, runs()))
    }

    private fun statusOf(id: Long): String = PostgresSupport.queryOne(
        "SELECT status FROM profile_revision WHERE profile_revision_id = $id",
    ) { it.getString(1) }

    private fun auditCount(operation: String): Int = PostgresSupport.queryOne(
        "SELECT count(*) FROM audit_log WHERE operation = '$operation'",
    ) { it.getInt(1) }

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :registry:compileTestKotlin -q`
Expected: 컴파일 실패(`requestTest`·`report`·`TestRequested`·`SuiteRun`·`ReportOutcome` 이 없다).

- [ ] **Step 3: 마이그레이션**

```sql
-- **시험 요청에 «끝남» 이 없었다.**
--
-- V2 는 요청을 적재하고 집는 데까지만 지었다(폴링 고리는 3a-2 로 미뤘다). 그래서 집은 요청은 15분 만료가
-- 지나면 다시 집힌다. 폴러가 있었다면 결과를 보고한 요청도 끝없이 다시 돌았을 것이다. 만료는 죽은 실행기가
-- 붙든 요청을 풀려는 것이지 끝난 요청을 되살리려는 것이 아니다.
--
-- ## 불리언이 아니라 시각이다
--
-- V15 의 `retired_at` 과 같은 이유다. *"언제 끝났는가"* 는 이력을 볼 때 반드시 오는 질문이고, 널이 정상값이며
-- 그 뜻이 하나다 — 아직 안 끝났다.
--
-- ## 열린 요청은 개정판마다 하나
--
-- 같은 개정판에 열린 요청이 둘이면 실행기 둘이 같은 문서를 따로 돌리고, 결과가 경합한다. 요청 문이
-- *"열린 요청이 있으면 그것을 돌려준다"* 로 멱등이어도, 동시에 온 두 요청은 둘 다 «없음» 을 보고 넣을 수 있다.
-- 그 경합을 막는 것은 코드가 아니라 색인이다.
ALTER TABLE revision_test_request
    ADD COLUMN completed_at TIMESTAMPTZ;

CREATE UNIQUE INDEX revision_test_request_one_open
    ON revision_test_request (profile_revision_id)
    WHERE completed_at IS NULL;
```

- [ ] **Step 4: `BindingService` 패치** — 아래를 `C:/Users/Eisen/AppData/Local/Temp/p2a-patches/p2a-1.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/p2a-patches/p2a-1.patch`.

```diff
diff --git a/registry/src/main/kotlin/dev/picasso/registry/binding/BindingService.kt b/registry/src/main/kotlin/dev/picasso/registry/binding/BindingService.kt
index 1d972b3..1a11145 100644
--- a/registry/src/main/kotlin/dev/picasso/registry/binding/BindingService.kt
+++ b/registry/src/main/kotlin/dev/picasso/registry/binding/BindingService.kt
@@ -57,22 +57,51 @@ class BindingService(private val db: Db) {
         require(suite in SUITES) { "모르는 스위트다: $suite (아는 것: $SUITES)" }
         require(result in setOf("PASS", "FAIL")) { "모르는 결과다: $result" }
 
+        insertRun(c, profileRevisionId, suite, result, ranBy, requestId = null, detail = null)
+        promoteIfAllPass(c, profileRevisionId, ranBy)
+    }
+
+    /**
+     * 실행 한 행을 남긴다. 시험 요청의 보고(`TestRequestService.report`)가 같은 트랜잭션에서 세 번 부른다 —
+     * 실행 행과 요청의 «끝남» 이 따로 커밋되면, 행은 남았는데 요청은 열려 있어 다시 집히는 창이 생긴다.
+     *
+     * @param detail 스위트가 낸 상세(JSON 문자열). 없으면 널
+     */
+    internal fun insertRun(
+        c: Connection,
+        profileRevisionId: Long,
+        suite: String,
+        result: String,
+        ranBy: String,
+        requestId: Long?,
+        detail: String?,
+    ) {
         c.prepareStatement(
-            "INSERT INTO revision_test_run (profile_revision_id, suite, result, ran_by) " +
-                "VALUES (?, ?, ?, ?)",
+            "INSERT INTO revision_test_run (profile_revision_id, suite, result, ran_by, request_id, detail) " +
+                "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
         ).use {
             it.setLong(1, profileRevisionId); it.setString(2, suite)
             it.setString(3, result); it.setString(4, ranBy)
+            if (requestId == null) it.setNull(5, java.sql.Types.BIGINT) else it.setLong(5, requestId)
+            it.setString(6, detail)
             it.executeUpdate()
         }
+    }
 
+    /**
+     * 세 스위트의 최신 실행이 모두 `PASS` 이고 `VALIDATED` 면 `TESTED` 로 올린다. 올렸으면 참.
+     *
+     * 개별 기록([recordTestRun])과 요청 보고가 같은 규칙을 지나야 한다. 두 곳에 규칙을 두면 한쪽만 고쳐지는 날
+     * «시험은 통과했는데 상태는 아닌» 창이 다시 생긴다.
+     */
+    internal fun promoteIfAllPass(c: Connection, profileRevisionId: Long, actor: String): Boolean {
         val promoted = allSuitesPass(c, profileRevisionId) &&
             statusOf(c, profileRevisionId) == RevisionStatus.VALIDATED
         if (promoted) {
             setStatus(c, profileRevisionId, RevisionStatus.TESTED)
-            audit(c, ranBy, "PROFILE_REVISION_TESTED", "$profileRevisionId")
+            audit(c, actor, "PROFILE_REVISION_TESTED", "$profileRevisionId")
         }
-        promoted
+        return promoted
     }
 
     fun activate(profileRevisionId: Long, actor: String): ActivateOutcome = db.transaction { c ->
```

- [ ] **Step 5: `TestRequestService` 를 아래 내용으로 바꾼다.** 옛 `request` 는 `requestTest` 에 위임한다(바뀐 동작 둘: 열린 요청이 있으면 그 id, 없는 리비전·`DRAFT`·`REVOKED` 는 예외).

```kotlin
package dev.picasso.registry.testing

import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.store.Db
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 집어간 시험 요청 하나.
 *
 * @param claimedAt 집은 시각. **DB 에 적힌 값 그대로다** — 보고가 이 값을 돌려 실어야 받아 주므로, 메모리의
 *   `Instant`(나노초)를 내주면 DB(마이크로초)와 어긋나 모든 보고가 거절된다.
 */
data class ClaimedRequest(
    val requestId: Long,
    val profileRevisionId: Long,
    val documentJson: String,
    val claimedAt: Instant = Instant.EPOCH,
)

/** 시험 요청의 결과(picasso-ops P2·S1d 스펙 §5.1 의 1단계). */
sealed interface TestRequested {
    data class Created(val requestId: Long) : TestRequested

    /** 끝나지 않은 요청이 이미 있다. 그 요청이다. */
    data class Existing(val requestId: Long) : TestRequested

    data object UnknownRevision : TestRequested

    /** `DRAFT`·`REVOKED` 는 시험할 문서가 아니다. */
    data class NotTestable(val status: String) : TestRequested
}

/** 스위트 하나의 결과. [detail] 은 실행기가 낸 JSON 문자열이다. */
data class SuiteRun(val suite: String, val result: String, val detail: String?)

/** 결과 보고의 결과(스펙 §5.1 의 4단계). */
sealed interface ReportOutcome {
    /** 받았다. [status] 는 보고 뒤의 개정판 상태다. */
    data class Recorded(val status: String) : ReportOutcome

    data object UnknownRequest : ReportOutcome

    /** 집은 실행기나 집은 시각이 다르다 — 다시 집힌 요청에 죽은 실행기의 늦은 보고가 섞이지 않게 한다. */
    data object NotClaimer : ReportOutcome

    data object AlreadyCompleted : ReportOutcome

    data class BadResults(val detail: String) : ReportOutcome
}

/**
 * §8.4 ②의 시험 요청. **레지스트리는 적재만 하고 `harness`가 집어간다.**
 *
 * §3.2의 순환 회피 규칙 1이 이 모양을 강제한다 — *"`registry`는 `mimic`도
 * `harness`도 모른다."* 레지스트리가 하네스를 부르면 레지스트리가 하네스를
 * 알아야 하고, 그러면 시험 도구가 없으면 레지스트리가 안 뜨게 된다.
 *
 * ## 클레임은 만료된다
 *
 * 기본 15분. **만료가 없으면 `harness`가 죽었을 때 요청이 영구히 잡힌다** —
 * 그 개정판은 영영 `TESTED`가 못 되고, 활성화도 못 한다. 되살리는 유일한
 * 길이 사람이 DB를 고치는 것이 되면 그것은 운영 도구가 아니다.
 *
 * ## 끝난 요청은 다시 안 집힌다
 *
 * 보고([report])가 요청을 끝남으로 적는다(V16). 만료는 막힌 요청을 풀려는 것이지 끝난 요청을 되살리려는 것이
 * 아니다.
 */
class TestRequestService(
    private val db: Db,
    private val now: () -> Instant = Instant::now,
    private val claimFor: Duration = Duration.ofMinutes(15),
    private val bindings: BindingService = BindingService(db),
) {

    /**
     * 시험을 요청한다. @return 요청 id.
     *
     * [requestTest] 에 위임한다. SQL 경로를 하나로 두려는 것이다. 위임으로 바뀐 동작은 둘이다 — 열린 요청이 있으면
     * 새로 만들지 않고 그 id 를 돌려주고, 없는 개정판과 `DRAFT`·`REVOKED` 는 예외다.
     */
    fun request(profileRevisionId: Long, actor: String): Long = when (val outcome = requestTest(profileRevisionId, actor)) {
        is TestRequested.Created -> outcome.requestId
        is TestRequested.Existing -> outcome.requestId
        TestRequested.UnknownRevision -> throw IllegalArgumentException("없는 개정판이다: $profileRevisionId")
        is TestRequested.NotTestable -> throw IllegalStateException("시험할 수 없는 상태다: ${outcome.status}")
    }

    /**
     * 시험을 요청한다. **멱등이다** — 끝나지 않은 요청이 있으면 그것을 돌려준다.
     *
     * 개정판 행을 잠그고 진행한다. 같은 개정판에 동시에 온 두 요청이 둘 다 «열린 요청 없음» 을 보고 넣으면
     * V16 의 색인이 막아 500 이 되는데, 잠그면 둘째가 첫째의 요청을 본다.
     *
     * 감사 `TEST_REQUEST` 는 새로 만들 때만 남긴다. 멱등 응답은 일어난 일이 없다.
     */
    fun requestTest(profileRevisionId: Long, actor: String): TestRequested = db.transaction { c ->
        val status = c.prepareStatement(
            "SELECT status FROM profile_revision WHERE profile_revision_id = ? FOR UPDATE",
        ).use { s ->
            s.setLong(1, profileRevisionId)
            s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: return@transaction TestRequested.UnknownRevision

        if (status in UNTESTABLE) return@transaction TestRequested.NotTestable(status)

        openRequest(c, profileRevisionId)?.let { return@transaction TestRequested.Existing(it) }

        val id = c.prepareStatement(
            "INSERT INTO revision_test_request (profile_revision_id, requested_by, requested_at) " +
                "VALUES (?, ?, ?) RETURNING request_id",
        ).use { s ->
            s.setLong(1, profileRevisionId); s.setString(2, actor)
            s.setObject(3, now().atOffset(ZoneOffset.UTC))
            s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
        }
        audit(c, actor, "TEST_REQUEST", "$profileRevisionId")
        TestRequested.Created(id)
    }

    /**
     * 하나를 집어간다. **아직 안 잡혔거나 클레임이 만료된, 끝나지 않은 것**만 대상이다.
     *
     * `FOR UPDATE SKIP LOCKED`를 쓰는 이유는 하네스가 여럿일 때 같은 요청을
     * 둘이 집어가지 않게 하려는 것이다 — 그러면 같은 개정판을 두 번 돌리고
     * 결과가 경합한다.
     */
    fun claim(worker: String): ClaimedRequest? = db.transaction { c ->
        val at = now()
        val row = c.prepareStatement(
            """
            SELECT r.request_id, r.profile_revision_id, p.document::text
            FROM revision_test_request r
            JOIN profile_revision p ON p.profile_revision_id = r.profile_revision_id
            WHERE r.completed_at IS NULL
              AND (r.claimed_by IS NULL OR r.claim_expires_at <= ?)
            ORDER BY r.request_id
            LIMIT 1
            FOR UPDATE OF r SKIP LOCKED
            """.trimIndent(),
        ).use { s ->
            s.setObject(1, at.atOffset(ZoneOffset.UTC))
            s.executeQuery().use { rs ->
                if (!rs.next()) null
                else ClaimedRequest(rs.getLong(1), rs.getLong(2), rs.getString(3))
            }
        } ?: return@transaction null

        val claimedAt = c.prepareStatement(
            "UPDATE revision_test_request " +
                "SET claimed_by = ?, claimed_at = ?, claim_expires_at = ? WHERE request_id = ? " +
                "RETURNING claimed_at",
        ).use { s ->
            s.setString(1, worker)
            s.setObject(2, at.atOffset(ZoneOffset.UTC))
            s.setObject(3, at.plus(claimFor).atOffset(ZoneOffset.UTC))
            s.setLong(4, row.requestId)
            s.executeQuery().use { rs -> check(rs.next()); rs.getTimestamp(1).toInstant() }
        }
        row.copy(claimedAt = claimedAt)
    }

    /**
     * 결과 셋을 받는다. 실행 3행, 요청의 «끝남», 승격이 **한 트랜잭션**이다.
     *
     * 집은 실행기와 집은 시각이 **둘 다** 같아야 받는다. 실행기 이름은 같은 이름으로 다시 뜰 수 있으므로 이름만으로는
     * 죽은 실행기의 늦은 보고를 못 가른다. 만료가 지났어도 다른 실행기가 다시 집기 전이면 받는다 — 결과는 실제로
     * 돌린 것이고, 만료는 막힌 요청을 풀려는 것이지 결과를 무효로 하려는 것이 아니다.
     */
    fun report(requestId: Long, worker: String, claimedAt: Instant, results: List<SuiteRun>): ReportOutcome {
        badResults(results)?.let { return ReportOutcome.BadResults(it) }

        return db.transaction { c ->
            val row = c.prepareStatement(
                "SELECT profile_revision_id, claimed_by, claimed_at, completed_at " +
                    "FROM revision_test_request WHERE request_id = ? FOR UPDATE",
            ).use { s ->
                s.setLong(1, requestId)
                s.executeQuery().use { rs ->
                    if (!rs.next()) null
                    else Claim(rs.getLong(1), rs.getString(2), rs.getTimestamp(3)?.toInstant(), rs.getTimestamp(4) != null)
                }
            } ?: return@transaction ReportOutcome.UnknownRequest

            if (row.completed) return@transaction ReportOutcome.AlreadyCompleted
            if (row.claimedBy != worker || row.claimedAt != claimedAt.truncatedTo(ChronoUnit.MICROS)) {
                return@transaction ReportOutcome.NotClaimer
            }

            results.forEach { run ->
                bindings.insertRun(c, row.profileRevisionId, run.suite, run.result, worker, requestId, run.detail)
            }
            c.prepareStatement("UPDATE revision_test_request SET completed_at = ? WHERE request_id = ?").use { s ->
                s.setObject(1, now().atOffset(ZoneOffset.UTC)); s.setLong(2, requestId)
                s.executeUpdate()
            }
            bindings.promoteIfAllPass(c, row.profileRevisionId, worker)

            val status = c.prepareStatement("SELECT status FROM profile_revision WHERE profile_revision_id = ?").use { s ->
                s.setLong(1, row.profileRevisionId)
                s.executeQuery().use { rs -> check(rs.next()); rs.getString(1) }
            }
            ReportOutcome.Recorded(status)
        }
    }

    /** 지금 잡혀 있는가. 시험이 만료를 관측하는 데 쓴다. */
    fun claimedBy(requestId: Long): String? = db.open().use { c ->
        c.prepareStatement(
            "SELECT claimed_by FROM revision_test_request WHERE request_id = ?",
        ).use { s ->
            s.setLong(1, requestId)
            s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    private class Claim(val profileRevisionId: Long, val claimedBy: String?, val claimedAt: Instant?, val completed: Boolean)

    private fun openRequest(c: Connection, profileRevisionId: Long): Long? = c.prepareStatement(
        "SELECT request_id FROM revision_test_request WHERE profile_revision_id = ? AND completed_at IS NULL",
    ).use { s ->
        s.setLong(1, profileRevisionId)
        s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
    }

    /** 세 스위트가 한 번씩, 결과는 PASS 나 FAIL 이어야 한다. 어긋나면 그 까닭이다. */
    private fun badResults(results: List<SuiteRun>): String? {
        val suites = results.map { it.suite }
        if (suites.sorted() != BindingService.SUITE_NAMES.sorted()) {
            return "스위트 셋이 한 번씩 와야 한다: 받은 것=$suites, 아는 것=${BindingService.SUITE_NAMES}"
        }
        results.firstOrNull { it.result !in RESULTS }?.let { return "모르는 결과다: ${it.suite}=${it.result}" }
        return null
    }

    private fun audit(c: Connection, actor: String, operation: String, subject: String) =
        c.prepareStatement(
            "INSERT INTO audit_log (operation, actor, subject) VALUES (?, ?, ?)",
        ).use {
            it.setString(1, operation); it.setString(2, actor); it.setString(3, subject)
            it.executeUpdate()
        }

    private companion object {
        val UNTESTABLE = setOf("DRAFT", "REVOKED")
        val RESULTS = setOf("PASS", "FAIL")
    }
}
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :registry:test --tests '*RevisionTestRequestTest' --tests '*AdapterLifecycleTest' -q`
Expected: XML 기준 `RevisionTestRequestTest` 12개, `AdapterLifecycleTest` 11개 통과, 실패 0.

- [ ] **Step 7: 커밋**

```bash
git add registry/src/main/resources/db/migration/V16__test_request_completion.sql registry/src/main/kotlin/dev/picasso/registry/binding/BindingService.kt registry/src/main/kotlin/dev/picasso/registry/testing/TestRequestService.kt registry/src/test/kotlin/dev/picasso/registry/RevisionTestRequestTest.kt
git commit -q -F - <<'EOF'
feat(registry): 시험 요청의 요청·집기·보고 결과 타입과 V16 끝남 칸

- 작업 묶음 커밋(Task 9 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 2: 시험 요청 문 3개

**Files:**
- Create: `registry/src/main/kotlin/dev/picasso/registry/web/TestRequestController.kt`
- Modify: `registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt`(빈 `testRequests`)
- Test: `registry/src/test/kotlin/dev/picasso/registry/web/TestRequestEndpointTest.kt`

- [ ] **Step 1: 실패하는 시험 쓰기**

```kotlin
package dev.picasso.registry.web

import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 시험 요청의 세 문(picasso-ops P2·S1d 스펙 §5.1). **요청은 조작 토큰, 집기·보고는 적재 토큰이다.**
 *
 * 서비스 시험은 결과 타입을 보고, 이 시험은 그 타입이 응답 코드로 옮겨졌는지와 토큰이 문을 가르는지 본다. 운영자
 * 토큰으로 결과를 적을 수 있으면 «실행기만 결과를 적는다» 가 무너진다.
 */
@SpringBootTest(
    classes = [RegistryApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class TestRequestEndpointTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    private var revision: Long = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        val db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        SkillTypeSync(db).sync(Fixtures.descriptor(), SEMVER, "sync")
        revision = (RevisionService(db, Fixtures.validator()).submit(Fixtures.good(), "op") as SubmitOutcome.Stored).profileRevisionId
    }

    private fun call(method: HttpMethod, path: String, body: String? = null, token: String? = OPERATOR_TOKEN, actor: String? = "engineer/kim") =
        rest.exchange(
            "http://localhost:$port$path",
            method,
            HttpEntity(
                body,
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    token?.let { set("Authorization", "Bearer $it") }
                    actor?.let { set("X-Actor", it) }
                },
            ),
            String::class.java,
        )

    private fun request(id: Long = revision) = call(HttpMethod.POST, "/operations/profile-revisions/$id/test-requests")

    private fun claim(worker: String = "site-runner", token: String? = INGEST_TOKEN) =
        call(HttpMethod.POST, "/ingest/test-requests/claim", """{"worker":"$worker"}""", token = token, actor = null)

    private fun report(requestId: Long, claimedAt: String, results: String = RESULTS, worker: String = "site-runner", token: String? = INGEST_TOKEN) =
        call(
            HttpMethod.POST, "/ingest/test-requests/$requestId/results",
            """{"worker":"$worker","claimed_at":"$claimedAt","results":$results}""", token = token, actor = null,
        )

    private fun field(json: String, key: String): String = Regex(""""$key"\s*:\s*"?([^",}]+)""").find(json)!!.groupValues[1]

    @Test
    fun `요청은 조작 토큰 뒤이고 새로 만들면 201, 다시 요청하면 같은 요청 200 이다`() {
        assertEquals(401, call(HttpMethod.POST, "/operations/profile-revisions/$revision/test-requests", token = INGEST_TOKEN).statusCode.value())

        val first = request()
        val second = request()

        assertEquals(201, first.statusCode.value(), first.body)
        assertEquals(200, second.statusCode.value(), second.body)
        assertEquals(field(first.body!!, "request_id"), field(second.body!!, "request_id"))
    }

    @Test
    fun `X-Actor 없이 요청하면 400 이다`() {
        assertEquals(400, call(HttpMethod.POST, "/operations/profile-revisions/$revision/test-requests", actor = null).statusCode.value())
    }

    @Test
    fun `없는 개정판은 404, DRAFT 는 409 다`() {
        assertEquals(404, request(999_999).statusCode.value())

        PostgresSupport.execute("UPDATE profile_revision SET status = 'DRAFT' WHERE profile_revision_id = $revision")
        val r = request()
        assertEquals(409, r.statusCode.value(), r.body)
        assertTrue(r.body!!.contains("\"status\":\"DRAFT\""), r.body)
    }

    @Test
    fun `집기는 적재 토큰 뒤이고 집을 것이 없으면 204 다`() {
        assertEquals(401, claim(token = OPERATOR_TOKEN).statusCode.value())
        assertEquals(204, claim().statusCode.value())
    }

    @Test
    fun `집으면 요청 id, 집은 시각, 제출된 문서를 준다`() {
        request()
        val r = claim()

        assertEquals(200, r.statusCode.value(), r.body)
        assertTrue(Regex(""""claimed_at":"\d{4}-\d{2}-\d{2}T""").containsMatchIn(r.body!!), r.body)
        assertTrue(r.body!!.contains("\"profile_revision_id\":$revision"), r.body)
        assertTrue(r.body!!.contains("\\\"vendor\\\""), "문서가 문자열로 실리지 않았다: ${r.body}")
    }

    @Test
    fun `보고는 적재 토큰 뒤이고 받으면 200 과 보고 뒤의 상태다`() {
        val requestId = field(request().body!!, "request_id").toLong()
        val claimedAt = field(claim().body!!, "claimed_at")

        assertEquals(401, report(requestId, claimedAt, token = OPERATOR_TOKEN).statusCode.value())
        val r = report(requestId, claimedAt)
        assertEquals(200, r.statusCode.value(), r.body)
        assertTrue(r.body!!.contains("\"status\":\"TESTED\""), r.body)
    }

    @Test
    fun `보고의 409 는 reason 으로 갈린다`() {
        val requestId = field(request().body!!, "request_id").toLong()
        val claimedAt = field(claim().body!!, "claimed_at")

        val other = report(requestId, claimedAt, worker = "other-runner")
        assertEquals(409, other.statusCode.value(), other.body)
        assertTrue(other.body!!.contains("\"reason\":\"NOT_CLAIMER\""), other.body)

        report(requestId, claimedAt)
        val again = report(requestId, claimedAt)
        assertEquals(409, again.statusCode.value(), again.body)
        assertTrue(again.body!!.contains("\"reason\":\"COMPLETED\""), again.body)
    }

    @Test
    fun `스위트가 모자라거나 시각이 틀리면 400, 없는 요청은 404 다`() {
        val requestId = field(request().body!!, "request_id").toLong()
        val claimedAt = field(claim().body!!, "claimed_at")

        assertEquals(400, report(requestId, claimedAt, results = """[{"suite":"CONTRACT","result":"PASS"}]""").statusCode.value())
        assertEquals(400, report(requestId, "어제").statusCode.value())
        assertEquals(404, report(999_999, claimedAt).statusCode.value())
    }

    private companion object {
        const val INGEST_TOKEN = "ingest-secret"
        const val OPERATOR_TOKEN = "operator-secret"
        val SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver

        const val RESULTS = """[{"suite":"CONTRACT","result":"PASS","detail":{"checks":3,"failures":[]}},""" +
            """{"suite":"NEGATIVE","result":"PASS","detail":{"checks":5,"failures":[]}},""" +
            """{"suite":"DETERMINISM","result":"PASS","detail":{"checks":2,"failures":[]}}]"""

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("picasso.db.url") { PostgresSupport.jdbcUrl }
            registry.add("picasso.db.user") { PostgresSupport.username }
            registry.add("picasso.db.password") { PostgresSupport.password }
            registry.add("picasso.ingest.token") { INGEST_TOKEN }
            registry.add("picasso.operator.token") { OPERATOR_TOKEN }
        }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :registry:test --tests '*TestRequestEndpointTest' -q`
Expected: XML 에서 8개 모두 실패(문이 없어 404 등).

- [ ] **Step 3: 컨트롤러**

```kotlin
package dev.picasso.registry.web

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.registry.testing.ReportOutcome
import dev.picasso.registry.testing.SuiteRun
import dev.picasso.registry.testing.TestRequestService
import dev.picasso.registry.testing.TestRequested
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * §8.4 ②의 시험 요청을 문으로 낸다(picasso-ops P2·S1d 스펙 §5.1). **문이 둘로 갈린다.**
 *
 * 요청은 조작 문이다 — 사람이 *"이 개정판을 시험하라"* 고 적는다. 집기와 보고는 적재 문이다 — 기계가 관측한 것을
 * 올린다. 운영자 토큰만 쥔 쪽(운영 화면)은 결과를 적을 길이 없고, 그것을 토큰 경계가 지킨다. 관문은
 * [OperatorToken] 과 [IngestToken] 이 경로로 건다.
 *
 * 생긴 이유는 첫 바깥 소비자다(ADR 9). picasso-ops 의 가짜 현장이 실행기를 띄우고 운영 화면이 시험을 요청한다.
 */
@RestController
class TestRequestController(private val requests: TestRequestService) {

    @PostMapping("/operations/profile-revisions/{profileRevisionId}/test-requests")
    fun request(
        @PathVariable profileRevisionId: Long,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any>> = when (val outcome = requests.requestTest(profileRevisionId, actor)) {
        is TestRequested.Created -> ResponseEntity.status(HttpStatus.CREATED).body(mapOf("request_id" to outcome.requestId))
        is TestRequested.Existing -> ResponseEntity.ok(mapOf("request_id" to outcome.requestId))
        TestRequested.UnknownRevision ->
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "없는 개정판이다: $profileRevisionId"))
        is TestRequested.NotTestable -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "시험할 수 없는 상태다", "status" to outcome.status),
        )
    }

    /**
     * 하나를 집는다. 집을 것이 없으면 204 다.
     *
     * 문서는 **문자열로** 준다. 실행기가 그것을 파일로 써서 스키마 검사를 거치게 하므로, 다시 직렬화한 JSON 이 아니라
     * 제출된 문서 그대로여야 한다.
     */
    @PostMapping("/ingest/test-requests/claim")
    fun claim(@RequestBody body: ClaimBody): ResponseEntity<Map<String, Any>> {
        if (body.worker.isBlank()) return ResponseEntity.badRequest().body(mapOf("error" to "worker 가 비었다"))
        val claimed = requests.claim(body.worker) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(
            mapOf(
                "request_id" to claimed.requestId,
                "profile_revision_id" to claimed.profileRevisionId,
                "claimed_at" to claimed.claimedAt.toString(),
                "document" to claimed.documentJson,
            ),
        )
    }

    /**
     * 결과 셋을 보고한다. 409 는 본문 `reason` 으로 가른다 — `COMPLETED` 면 앞 보고가 이미 반영된 것이고,
     * `NOT_CLAIMER` 면 이 실행기의 보고가 아니다. 실행기가 둘을 다르게 다룬다(스펙 §5.2).
     */
    @PostMapping("/ingest/test-requests/{requestId}/results")
    fun report(
        @PathVariable requestId: Long,
        @RequestBody body: ReportBody,
    ): ResponseEntity<Map<String, Any>> {
        if (body.worker.isBlank()) return ResponseEntity.badRequest().body(mapOf("error" to "worker 가 비었다"))
        val claimedAt = try {
            Instant.parse(body.claimed_at)
        } catch (e: DateTimeParseException) {
            return ResponseEntity.badRequest().body(mapOf("error" to "claimed_at 이 ISO 시각이 아니다: ${body.claimed_at}"))
        }
        val runs = body.results.map { SuiteRun(it.suite, it.result, it.detail?.toString()) }
        return when (val outcome = requests.report(requestId, body.worker, claimedAt, runs)) {
            is ReportOutcome.Recorded -> ResponseEntity.ok(mapOf("status" to outcome.status))
            ReportOutcome.UnknownRequest ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "없는 요청이다: $requestId"))
            ReportOutcome.AlreadyCompleted ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "이미 끝난 요청이다", "reason" to "COMPLETED"))
            ReportOutcome.NotClaimer -> ResponseEntity.status(HttpStatus.CONFLICT).body(
                mapOf("error" to "집은 실행기나 집은 시각이 다르다", "reason" to "NOT_CLAIMER"),
            )
            is ReportOutcome.BadResults -> ResponseEntity.badRequest().body(mapOf("error" to outcome.detail))
        }
    }
}

/** `POST /ingest/test-requests/claim` 의 본문. */
data class ClaimBody(val worker: String = "")

/** `POST /ingest/test-requests/{requestId}/results` 의 본문. */
data class ReportBody(
    val worker: String = "",
    val claimed_at: String = "",
    val results: List<SuiteResultBody> = emptyList(),
)

/** 스위트 하나. [detail] 은 실행기가 정한 JSON 이며 그대로 `revision_test_run.detail` 에 들어간다. */
data class SuiteResultBody(val suite: String = "", val result: String = "", val detail: JsonNode? = null)
```

- [ ] **Step 4: 빈 패치** — `C:/Users/Eisen/AppData/Local/Temp/p2a-patches/p2a-2.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/p2a-patches/p2a-2.patch`.

```diff
diff --git a/registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt b/registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt
index a668a3f..8c5f056 100644
--- a/registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt
+++ b/registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt
@@ -69,6 +69,11 @@ open class RegistryApplication {
     @Bean
     open fun bindings(db: Db): BindingService = BindingService(db)
 
+    /** 시험 요청(§8.4 ②). 보고가 실행 행과 승격을 [BindingService] 로 남기므로 같은 빈을 쓴다. */
+    @Bean
+    open fun testRequests(db: Db, bindings: BindingService): dev.picasso.registry.testing.TestRequestService =
+        dev.picasso.registry.testing.TestRequestService(db, bindings = bindings)
+
     /** ADR 37 결정 2 의 셋째 축 — 배포된 것. */
     @Bean
     open fun adapterInstances(db: Db): dev.picasso.registry.adapter.AdapterInstanceService =
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :registry:test -q`
Expected: XML 기준 registry 전체 실패 0, `TestRequestEndpointTest` 8개 통과.

- [ ] **Step 6: 커밋과 대조**

```bash
git add registry/src/main/kotlin/dev/picasso/registry/web/TestRequestController.kt registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt registry/src/test/kotlin/dev/picasso/registry/web/TestRequestEndpointTest.kt
git commit -q -F - <<'EOF'
feat(registry): 시험 요청 조작 문과 집기·보고 적재 문

- 작업 묶음 커밋(Task 9 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
bash C:/Users/Eisen/AppData/Local/Temp/p2a-cmp.sh registry/src/main/resources/db/migration/V16__test_request_completion.sql registry/src/main/kotlin/dev/picasso/registry/binding/BindingService.kt registry/src/main/kotlin/dev/picasso/registry/testing/TestRequestService.kt registry/src/test/kotlin/dev/picasso/registry/RevisionTestRequestTest.kt registry/src/main/kotlin/dev/picasso/registry/web/TestRequestController.kt registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt registry/src/test/kotlin/dev/picasso/registry/web/TestRequestEndpointTest.kt
```
Expected: 7줄 모두 «같음».

---

## Chunk 2: harness 실행기

### Task 3: 선언에서 값 만들기와 시험 3종

**Files:**
- Modify: `harness/build.gradle.kts`(`:capability` 운영 의존, Spring Boot 시험 의존)
- Create: `harness/src/main/kotlin/dev/picasso/harness/revision/MinimalParameters.kt`
- Create: `harness/src/main/kotlin/dev/picasso/harness/revision/RevisionSuites.kt`
- Test: `harness/src/test/kotlin/dev/picasso/harness/revision/RevisionSuitesTest.kt`

- [ ] **Step 1: 빌드 패치** — `C:/Users/Eisen/AppData/Local/Temp/p2a-patches/p2a-3.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/p2a-patches/p2a-3.patch`.

```diff
diff --git a/harness/build.gradle.kts b/harness/build.gradle.kts
index 140986b..c2c79ab 100644
--- a/harness/build.gradle.kts
+++ b/harness/build.gradle.kts
@@ -11,6 +11,10 @@ dependencies {
     api(project(":contracts"))
     implementation(project(":profile-model"))
 
+    // 개정판 시험의 CONTRACT 가 능력 응답을 프로파일의 투영과 맞댄다(picasso-ops P2·S1d 스펙 §5.4). 투영을 다시 짜면
+    // 미믹이 내는 것과 시험이 기대하는 것이 같은 코드에서 나오지 않게 된다.
+    implementation(project(":capability"))
+
     // 적재 폴백이 계약 메시지를 protobuf JSON으로 적는다. 적재 표면이
     // 같은 규약으로 읽으므로 다른 규약을 쓰면 밀어 넣는 날 갈린다.
     implementation(libs.protobuf.java.util)
@@ -42,6 +46,11 @@ dependencies {
     // 시험이 DB를 얻는 방식에 두 번째 진실이 생긴다.
     testImplementation(testFixtures(project(":registry")))
 
+    // 개정판 시험 실행기가 registry 와 HTTP 로 왕복하는지 본다(RevisionRunnerEndToEndTest). registry 를 이 JVM 에
+    // 띄우려면 Spring 이 시험 클래스패스에 있어야 한다 — registry 는 그것을 implementation 으로만 든다.
+    testImplementation(platform(libs.spring.boot.bom))
+    testImplementation(libs.spring.boot.starter.web)
+
     // 실제 브로커를 띄워 §15.30의 "증명되지 않는 것"을 줄인다.
     // **여기 두는 것은 harness가 이미 Docker를 요구하기 때문이다**(§15.39) —
     // `:mimic:test`에 넣으면 in-process 결정성 스위트가 느려진다.
```

- [ ] **Step 2: 실패하는 시험 쓰기**

```kotlin
package dev.picasso.harness.revision

import dev.picasso.harness.Suite
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 개정판 시험 3종(picasso-ops P2·S1d 스펙 §5.4, ADR 49). **기준 프로파일 둘이 셋 다 통과하고, 검사가 실제로 돈다.**
 *
 * 통과만 보면 검사 0개로도 초록이다. 그래서 검사 식별자를 함께 본다 — 프로파일이 못 한다고 적은 것마다 탐침이
 * 갔는지(NEGATIVE), 선언한 스킬마다 태스크가 돌았는지(CONTRACT·DETERMINISM).
 */
class RevisionSuitesTest {

    private val schema: Path = Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize()
    private val suites = RevisionSuites(schema)

    private fun profile(name: String): Path = Path.of("..", "profile", "profiles", "$name.json").normalize()

    private fun byName(outcomes: List<SuiteOutcome>) = outcomes.associateBy { it.suite }

    @Test
    fun `humanoid-a 는 셋 다 통과한다`() {
        val outcomes = byName(suites.run(profile("humanoid-a")))

        Suite.entries.forEach { suite ->
            val outcome = outcomes.getValue(suite)
            assertTrue(outcome.passed, "$suite: ${outcome.detailJson()}")
        }
        assertEquals(5, outcomes.getValue(Suite.CONTRACT).checks, "협상·능력 조회 + 스킬 셋")
        assertEquals(3, outcomes.getValue(Suite.DETERMINISM).checks, "스킬 셋")
    }

    @Test
    fun `quadruped-b 는 셋 다 통과한다`() {
        val outcomes = byName(suites.run(profile("quadruped-b")))

        Suite.entries.forEach { suite ->
            val outcome = outcomes.getValue(suite)
            assertTrue(outcome.passed, "$suite: ${outcome.detailJson()}")
        }
        assertEquals(4, outcomes.getValue(Suite.CONTRACT).checks, "협상·능력 조회 + 스킬 둘")
        assertEquals(2, outcomes.getValue(Suite.DETERMINISM).checks, "스킬 둘")
    }

    @Test
    fun `NEGATIVE 는 프로파일이 못 한다고 적은 것마다 탐침을 보낸다`() {
        val checks = byName(suites.run(profile("humanoid-a"))).getValue(Suite.NEGATIVE).checked.map { it.removePrefix("NEGATIVE.") }

        listOf(
            "skill_absent:move_relative",
            "parameter_missing:navigate_to.location",
            "parameter_missing:pick_place.object_id",
            "parameter_above_max:pick_place.grip_force",
            "parameter_below_min:pick_place.grip_force",
            "parameter_too_long:pick_place.destination",
            "parameter_not_allowed:inspect.mode",
            "cancel_unsupported:pick_place",
            "required_optional_missing:task.parameters.verify_grasp",
        ).forEach { assertTrue(it in checks, "탐침이 없다: $it — 있는 것: $checks") }
        assertTrue(checks.none { it.startsWith("pause_unsupported") }, "pause_support 가 NO 인 스킬이 없는데 탐침이 갔다: $checks")
    }

    @Test
    fun `적재에서 거절된 문서는 셋 다 FAIL 이다`() {
        val broken = Files.createTempFile("broken-", ".json")
        Files.writeString(broken, """{"vendor":"x"}""")

        val outcomes = suites.run(broken)

        assertEquals(Suite.entries.toList(), outcomes.map { it.suite })
        outcomes.forEach {
            assertTrue(!it.passed, "${it.suite} 가 통과했다")
            assertEquals("LOAD", it.failures.single().check)
        }
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :harness:compileTestKotlin -q`
Expected: 컴파일 실패(`RevisionSuites`·`SuiteOutcome` 이 없다).

- [ ] **Step 4: `MinimalParameters`**

```kotlin
package dev.picasso.harness.revision

import dev.picasso.contracts.v1.ParameterDeclaration
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.v1.ValueType

/**
 * 선언에서 **최소 유효값**과 **어긋난 값**을 만든다(picasso-ops P2·S1d 스펙 §5.5).
 *
 * ## 기종 분기가 아니다
 *
 * [dev.picasso.harness.ContractSuite] 는 *"능력을 보고 값을 고르기 시작하면 그것이 곧 기종 분기"* 라고 적는다.
 * 그 원칙은 **클라이언트**의 것이다 — 같은 클라이언트 코드로 이기종을 다룬다(완료 기준 A-1). 이 객체는 클라이언트가
 * 아니라 시험 데이터를 만드는 쪽이다. 개정판 시험은 처음 보는 프로파일을 받으므로, 값을 손으로 적어 둘 수가 없다.
 * 경계는 ADR 49 에 있다.
 *
 * 값은 선언(키, 형, 범위, 길이, 허용 값)에서만 나온다. 기종 이름도 스킬 이름도 보지 않는다.
 */
object MinimalParameters {

    /** 프로파일이 `REQUIRED` 로 적은 선택 필드의 경로 접두사(§7.2). 키는 그 뒤다. */
    const val PATH_PREFIX = "task.parameters."

    /**
     * 수락될 최소 값 묶음. 필수 키 전부와, [requiredOptional] 에 든 선택 키를 싣는다.
     *
     * @param requiredOptional `REQUIRED` 선택 필드의 키(접두사를 뗀 것)
     */
    fun of(skill: SkillDeclaration, requiredOptional: Set<String>): List<ParameterValue> =
        skill.parametersList
            .filter { !it.optional || it.key in requiredOptional }
            .map { valid(it) }

    /** 선언을 지키는 값 하나. */
    fun valid(declaration: ParameterDeclaration): ParameterValue {
        val builder = ParameterValue.newBuilder().setKey(declaration.key)
        return when (declaration.valueType) {
            ValueType.VALUE_TYPE_STRING -> builder.setStringValue("a")
            ValueType.VALUE_TYPE_BOOL -> builder.setBoolValue(false)
            ValueType.VALUE_TYPE_ENUM -> builder.setStringValue(declaration.allowedValuesList.first())
            ValueType.VALUE_TYPE_INTEGER -> builder.setIntegerValue(inRange(declaration).let { kotlin.math.ceil(it).toLong() })
            ValueType.VALUE_TYPE_NUMBER -> builder.setNumberValue(inRange(declaration))
            ValueType.VALUE_TYPE_UNSPECIFIED, ValueType.UNRECOGNIZED ->
                error("선언의 value_type 이 미정이다: ${declaration.key}")
        }.build()
    }

    /**
     * 선언을 어기는 값들. 이름은 검사 식별자에 붙는다(`above_max`, `below_min`, `too_long`, `not_allowed`).
     * 선언이 없는 제약은 어길 수 없으므로 내지 않는다.
     */
    fun violations(declaration: ParameterDeclaration): List<Pair<String, ParameterValue>> = buildList {
        val builder = { ParameterValue.newBuilder().setKey(declaration.key) }
        when (declaration.valueType) {
            ValueType.VALUE_TYPE_NUMBER -> {
                if (declaration.hasMaxValue()) add("above_max" to builder().setNumberValue(declaration.maxValue + 1).build())
                if (declaration.hasMinValue()) add("below_min" to builder().setNumberValue(declaration.minValue - 1).build())
            }
            ValueType.VALUE_TYPE_INTEGER -> {
                if (declaration.hasMaxValue()) {
                    add("above_max" to builder().setIntegerValue(kotlin.math.floor(declaration.maxValue).toLong() + 1).build())
                }
                if (declaration.hasMinValue()) {
                    add("below_min" to builder().setIntegerValue(kotlin.math.ceil(declaration.minValue).toLong() - 1).build())
                }
            }
            ValueType.VALUE_TYPE_STRING ->
                if (declaration.hasMaxLength()) {
                    add("too_long" to builder().setStringValue("a".repeat(declaration.maxLength + 1)).build())
                }
            ValueType.VALUE_TYPE_ENUM ->
                add("not_allowed" to builder().setStringValue(declaration.allowedValuesList.joinToString("") + "_").build())
            else -> Unit
        }
    }

    /** 0 을 선언 범위 안으로 옮긴 값. 하한이 0 보다 크면 하한, 상한이 0 보다 작으면 상한이다. */
    private fun inRange(declaration: ParameterDeclaration): Double {
        var value = 0.0
        if (declaration.hasMinValue() && value < declaration.minValue) value = declaration.minValue
        if (declaration.hasMaxValue() && value > declaration.maxValue) value = declaration.maxValue
        return value
    }
}
```

- [ ] **Step 5: `RevisionSuites`**

```kotlin
package dev.picasso.harness.revision

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Message
import dev.picasso.capability.CapabilityProjection
import dev.picasso.client.PicassoClient
import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.OptionalFieldSupport
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.SkillCatalog
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.v1.Support
import dev.picasso.contracts.v1.TaskState
import dev.picasso.harness.Harness
import dev.picasso.harness.Suite
import dev.picasso.mimic.profile.FileProfileSource
import dev.picasso.mimic.profile.ProfileRejected
import dev.picasso.profile.LimitsNeeded
import dev.picasso.profile.ProfileDocument
import dev.picasso.profile.Requirement
import dev.picasso.profile.RequirementSet
import java.nio.file.Path
import java.time.Duration

/** 검사 하나가 어긋난 자리. [check] 는 `CONTRACT.capabilities` 처럼 스위트와 검사와 대상을 잇는다. */
data class SuiteFailure(val check: String, val expected: String, val observed: String)

/**
 * 스위트 하나의 결과. [checked] 는 실제로 돌린 검사의 식별자다 — PASS 도 그 수를 실어 검사가 돌았음을 보인다.
 */
data class SuiteOutcome(val suite: Suite, val checked: List<String>, val failures: List<SuiteFailure>) {
    val checks: Int get() = checked.size

    /** 검사가 하나도 안 돌았으면 통과가 아니다. */
    val passed: Boolean get() = failures.isEmpty() && checks > 0

    /** `revision_test_run.detail` 에 들어갈 JSON(스펙 §5.4). */
    fun detailJson(): String = MAPPER.writeValueAsString(
        mapOf(
            "checks" to checks,
            "failures" to failures.map { mapOf("check" to it.check, "expected" to it.expected, "observed" to it.observed) },
        ),
    )

    private companion object {
        val MAPPER = ObjectMapper()
    }
}

/**
 * 개정판 시험 3종(picasso-ops P2·S1d 스펙 §5.4, ADR 49). **후보 문서 하나로 mimic 을 띄워 돈다.**
 *
 * 문서는 파일 경로로 받아 [Harness] 와 같은 [FileProfileSource] 를 지난다. 스키마를 어긴 문서 위에 시험이 서지
 * 않게 하려는 것이다. 시나리오마다 새 [Harness] 를 띄운다 — 앞 시나리오가 남긴 상태(쥔 물체, 결함)가 다음 시나리오의
 * 전제를 바꾸지 않게 하려는 것이다.
 *
 * @param seed 모든 시나리오가 쓰는 시드. DETERMINISM 은 같은 시드로 두 번 돈다
 * @param stepLimit 태스크 하나에 미는 가상 초의 상한. 넘으면 그 시나리오는 FAIL 이다(스펙 §5.2)
 */
class RevisionSuites(
    private val schema: Path,
    private val seed: Long = 0,
    private val stepLimit: Int = 3600,
) {

    /** 세 스위트를 차례로 돈다. 문서가 적재에서 거절되면 셋 다 FAIL 이다(스펙 §5.2). */
    fun run(document: Path): List<SuiteOutcome> {
        val doc = try {
            FileProfileSource(schema).load(document)
        } catch (e: ProfileRejected) {
            val failure = SuiteFailure("LOAD", "스키마를 지나는 문서", "${e.message} ${e.findings}")
            return Suite.entries.map { SuiteOutcome(it, listOf("LOAD"), listOf(failure)) }
        }
        val expected = CapabilityProjection.of(doc)
        return listOf(
            guarded(Suite.CONTRACT) { contract(document, doc, expected) },
            guarded(Suite.NEGATIVE) { negative(document, expected) },
            guarded(Suite.DETERMINISM) { determinism(document, expected) },
        )
    }

    /** 스위트 안의 예외는 그 스위트의 FAIL 이다(스펙 §5.2). 실행기가 죽으면 같은 요청이 15분마다 다시 돈다. */
    private fun guarded(suite: Suite, body: () -> SuiteOutcome): SuiteOutcome = try {
        body()
    } catch (e: Exception) {
        SuiteOutcome(suite, listOf("${suite.name}.exception"), listOf(SuiteFailure("${suite.name}.exception", "예외 없음", "${e::class.simpleName}: ${e.message}")))
    }

    // ── CONTRACT

    private fun contract(document: Path, doc: ProfileDocument, expected: Capability): SuiteOutcome {
        val failures = mutableListOf<SuiteFailure>()
        val checked = mutableListOf<String>()

        harness(document).use { h ->
            val client = h.client(CLIENT)
            checked += "CONTRACT.negotiate"
            val negotiated = client.negotiate(ROBOT, requirements(expected, withOptional = true))
            if (!negotiated.accepted) {
                failures += SuiteFailure(
                    "CONTRACT.negotiate", "수락",
                    negotiated.rejectionsList.joinToString { "${it.code}: ${it.detail}" },
                )
            }
            checked += "CONTRACT.capabilities"
            val actual = client.capabilities(ROBOT)
            if (actual != expected) {
                failures += SuiteFailure(
                    "CONTRACT.capabilities", "프로파일의 투영",
                    "응답 스킬=${actual.skillsList.map { "${it.skillType}@${it.major}.${it.minor}" }}",
                )
            }
        }

        expected.skillsList.forEach { skill ->
            checked += "CONTRACT.task:${skill.skillType}"
            harness(document).use { h ->
                val run = runTask(h, skill, expected)
                verdict(run, skill, doc)?.let { failures += SuiteFailure("CONTRACT.task:${skill.skillType}", "종착(성공 또는 선언된 실패)", it) }
            }
        }
        return SuiteOutcome(Suite.CONTRACT, checked, failures)
    }

    /** 멈춘 상태가 계약상 올바른가. 올바르면 널, 아니면 관측값이다. */
    private fun verdict(run: TaskRun, skill: SkillDeclaration, doc: ProfileDocument): String? {
        run.rejection?.let { return "거절: $it" }
        val last = run.follower!!.updates.lastOrNull() ?: return "갱신 없음"
        return when (last.state) {
            TaskState.TASK_STATE_SUCCEEDED -> null
            in DECLARED_STOPS -> {
                val declared = doc.failureModes.filter { it.skillType == null || it.skillType == skill.skillType }.map { it.errorType }
                if (last.fault.errorType in declared) null else "선언 안 된 결함: ${last.state} ${last.fault.errorType}"
            }
            else -> "가상 ${stepLimit}초 안에 멈추지 않았다: ${last.state}"
        }
    }

    // ── NEGATIVE

    private fun negative(document: Path, expected: Capability): SuiteOutcome {
        val failures = mutableListOf<SuiteFailure>()
        val checked = mutableListOf<String>()
        val declared = expected.skillsList.map { it.skillType }.toSet()
        val requiredOptional = requiredOptionalKeys(expected)

        fun probe(check: String, expectedCode: RejectionCode, observed: () -> RejectionCode?) {
            checked += "NEGATIVE.$check"
            val code = observed()
            if (code != expectedCode) failures += SuiteFailure("NEGATIVE.$check", expectedCode.name, code?.name ?: "수락")
        }

        (catalogSkills() - declared).sorted().forEach { skill ->
            probe("skill_absent:$skill", RejectionCode.REJECTION_CODE_SKILL_ABSENT) { startRejection(document, skill, emptyList()) }
        }

        expected.skillsList.forEach { skill ->
            val minimal = MinimalParameters.of(skill, requiredOptional)
            skill.parametersList.filterNot { it.optional }.forEach { p ->
                probe("parameter_missing:${skill.skillType}.${p.key}", RejectionCode.REJECTION_CODE_PARAMETER_INVALID) {
                    startRejection(document, skill.skillType, minimal.filterNot { it.key == p.key })
                }
            }
            skill.parametersList.forEach { p ->
                MinimalParameters.violations(p).forEach { (name, bad) ->
                    probe("parameter_$name:${skill.skillType}.${p.key}", RejectionCode.REJECTION_CODE_PARAMETER_INVALID) {
                        startRejection(document, skill.skillType, minimal.filterNot { it.key == p.key } + bad)
                    }
                }
            }
            if (skill.cancelSupport == Support.SUPPORT_NO) {
                probe("cancel_unsupported:${skill.skillType}", RejectionCode.REJECTION_CODE_CANCEL_UNSUPPORTED) {
                    controlRejection(document, skill, expected) { client, handle -> client.cancel(ROBOT, handle).let { if (it.hasRejection()) it.rejection.code else null } }
                }
            }
            if (skill.pauseSupport == Support.SUPPORT_NO) {
                probe("pause_unsupported:${skill.skillType}", RejectionCode.REJECTION_CODE_PAUSE_UNSUPPORTED) {
                    controlRejection(document, skill, expected) { client, handle -> client.pause(ROBOT, handle).let { if (it.hasRejection()) it.rejection.code else null } }
                }
            }
        }

        expected.optionalFieldsList.filter { it.support == OptionalFieldSupport.OPTIONAL_FIELD_SUPPORT_REQUIRED }.forEach { field ->
            probe("required_optional_missing:${field.parameterPath}", RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING) {
                harness(document).use { h ->
                    val response = h.client(CLIENT).negotiate(ROBOT, requirements(expected, withOptional = false))
                    response.rejectionsList.map { it.code }.firstOrNull { it == RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING }
                        ?: response.rejectionsList.firstOrNull()?.code
                }
            }
        }
        return SuiteOutcome(Suite.NEGATIVE, checked, failures)
    }

    private fun startRejection(document: Path, skillType: String, parameters: List<ParameterValue>): RejectionCode? =
        harness(document).use { h ->
            val started = h.client(CLIENT).start(ROBOT, "negative", 1, skillType, parameters)
            if (started.hasRejection()) started.rejection.code else null
        }

    private fun controlRejection(
        document: Path,
        skill: SkillDeclaration,
        expected: Capability,
        control: (PicassoClient, dev.picasso.contracts.v1.TaskHandle) -> RejectionCode?,
    ): RejectionCode? = harness(document).use { h ->
        val client = h.client(CLIENT)
        val started = client.start(ROBOT, "negative", 1, skill.skillType, MinimalParameters.of(skill, requiredOptionalKeys(expected)))
        if (started.hasRejection()) error("유효한 태스크가 거절됐다: ${started.rejection.code} ${started.rejection.detail}")
        control(client, started.handle)
    }

    // ── DETERMINISM

    private fun determinism(document: Path, expected: Capability): SuiteOutcome {
        val failures = mutableListOf<SuiteFailure>()
        expected.skillsList.forEach { skill ->
            val first = harness(document, seed).use { h -> trace(h, runTask(h, skill, expected)) }
            val second = harness(document, seed).use { h -> trace(h, runTask(h, skill, expected)) }
            if (first != second) {
                val at = first.indices.firstOrNull { it >= second.size || first[it] != second[it] } ?: first.size
                failures += SuiteFailure(
                    "DETERMINISM.trace:${skill.skillType}", "같은 시드에서 같은 이벤트·가상 시각·진행률",
                    "${at}번째부터 다르다(길이 ${first.size}·${second.size})",
                )
            }
        }
        return SuiteOutcome(Suite.DETERMINISM, expected.skillsList.map { "DETERMINISM.trace:${it.skillType}" }, failures)
    }

    /**
     * 한 실행의 자취. 발행된 것 전부(상태·이벤트·연결)와 태스크 갱신이며, `session_id`·`event_id` 는 지운다 —
     * 둘 다 JVM 전역 카운터를 품어 같은 시드로도 실행마다 다르다.
     */
    private fun trace(h: Harness, run: TaskRun): List<String> =
        h.publisher.publications.map { "${it.topic} ${normalize(it.message)}" } +
            run.follower?.updates.orEmpty().map { normalize(it).toString() } +
            listOf("rejection=${run.rejection}")

    // ── 공통

    private class TaskRun(val rejection: String?, val follower: TaskFollower?)

    /** 최소 값으로 태스크를 걸고 멈출 때까지 가상 1초씩 민다. 소요 시간을 미리 알면 그것이 기종 지식이 된다. */
    private fun runTask(h: Harness, skill: SkillDeclaration, expected: Capability): TaskRun {
        val client = h.client(CLIENT)
        val started = client.start(ROBOT, "contract-${skill.skillType}", 1, skill.skillType, MinimalParameters.of(skill, requiredOptionalKeys(expected)))
        if (started.hasRejection()) return TaskRun("${started.rejection.code}: ${started.rejection.detail}", null)
        val follower = client.follow(ROBOT, started.handle)
        repeat(stepLimit) {
            if (follower.updates.lastOrNull()?.state in STOPS) return TaskRun(null, follower)
            h.advance(STEP)
        }
        return TaskRun(null, follower)
    }

    private fun harness(document: Path, seedOverride: Long = seed) = Harness(mapOf(ROBOT to document), schema, seedOverride)

    private fun requirements(expected: Capability, withOptional: Boolean) = RequirementSet(
        CLIENT,
        expected.skillsList.map { Requirement(it.skillType, it.major, it.minor) },
        if (withOptional) expected.optionalFieldsList.filter { it.support == OptionalFieldSupport.OPTIONAL_FIELD_SUPPORT_REQUIRED }.map { it.parameterPath } else emptyList(),
        LimitsNeeded(),
    )

    private fun requiredOptionalKeys(expected: Capability): Set<String> =
        expected.optionalFieldsList
            .filter { it.support == OptionalFieldSupport.OPTIONAL_FIELD_SUPPORT_REQUIRED }
            .map { it.parameterPath.removePrefix(MinimalParameters.PATH_PREFIX) }
            .toSet()

    /** 계약 카탈로그의 스킬 이름. 선언하지 않은 스킬 탐침이 이 중 프로파일에 없는 것을 쓴다. */
    private fun catalogSkills(): Set<String> =
        SkillCatalog.getDescriptor().messageTypes
            .map { it.options.getExtension(SkillCatalog.skillTypeName) }
            .filter { it.isNotBlank() }
            .toSet()

    private companion object {
        const val ROBOT = "candidate"
        const val CLIENT = "revision-test"
        val STEP: Duration = Duration.ofSeconds(1)

        /** 선언된 결함으로 멈출 수 있는 상태. 결함이 프로파일에 선언된 것이어야 올바르다. */
        val DECLARED_STOPS = setOf(TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_RETRIABLE, TaskState.TASK_STATE_NEEDS_INTERVENTION)
        val STOPS = DECLARED_STOPS + setOf(TaskState.TASK_STATE_SUCCEEDED, TaskState.TASK_STATE_CANCELLED)

        /** 같은 실행을 두 번 돌려도 달라지는 칸. JVM 전역 카운터를 품는다. */
        val VOLATILE = setOf("session_id", "event_id")

        fun normalize(message: Message): Message {
            val builder = message.toBuilder()
            message.descriptorForType.fields.forEach { field ->
                when {
                    field.name in VOLATILE -> builder.clearField(field)
                    field.javaType == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE &&
                        !field.isRepeated && message.hasField(field) ->
                        builder.setField(field, normalize(message.getField(field) as Message))
                    field.javaType == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE && field.isRepeated ->
                        builder.setField(field, (message.getField(field) as List<*>).map { normalize(it as Message) })
                }
            }
            return builder.build()
        }
    }
}
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :harness:test --tests '*RevisionSuitesTest' -q`
Expected: XML 기준 4개 통과. `humanoid-a` 한 세트가 1초 안쪽이다.

- [ ] **Step 7: 커밋**

```bash
git add harness/build.gradle.kts harness/src/main/kotlin/dev/picasso/harness/revision/MinimalParameters.kt harness/src/main/kotlin/dev/picasso/harness/revision/RevisionSuites.kt harness/src/test/kotlin/dev/picasso/harness/revision/RevisionSuitesTest.kt
git commit -q -F - <<'EOF'
feat(harness): 개정판 시험 3종과 선언에서 만드는 시험 값

- 작업 묶음 커밋(Task 9 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 4: 실행기와 registry 왕복

**Files:**
- Create: `harness/src/main/kotlin/dev/picasso/harness/revision/RevisionTestRunner.kt`
- Test: `harness/src/test/kotlin/dev/picasso/harness/revision/RevisionTestRunnerTest.kt`
- Test: `harness/src/test/kotlin/dev/picasso/harness/revision/RevisionRunnerEndToEndTest.kt`
- Modify: `gate/src/main/kotlin/dev/picasso/gate/checks/Check11OutboundScope.kt`(아웃바운드 닫힌 목록에 실행기)

- [ ] **Step 1: 실패하는 시험 쓰기** — 두 파일.

```kotlin
package dev.picasso.harness.revision

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 실행기의 폴링과 보고(picasso-ops P2·S1d 스펙 §5.1·§5.2). **registry 가 어떻게 답하든 실행기가 멈추지 않고, 같은
 * 결과를 두 번 반영하지 않는다.**
 *
 * registry 대신 답을 정해 둔 창구를 쓴다. 실제 registry 와의 왕복은 [RevisionRunnerEndToEndTest] 가 본다.
 */
class RevisionTestRunnerTest {

    private val schema: Path = Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize()
    private val humanoid: String = Files.readString(Path.of("..", "profile", "profiles", "humanoid-a.json").normalize())
    private val logs = mutableListOf<String>()

    private class ScriptedDesk(
        private val claims: ArrayDeque<() -> ClaimedTest?>,
        private val replies: ArrayDeque<() -> ReportReply>,
    ) : TestDesk {
        val reports = mutableListOf<Triple<Long, String, List<SuiteOutcome>>>()

        override fun claim(worker: String): ClaimedTest? = claims.removeFirstOrNull()?.invoke()

        override fun report(requestId: Long, worker: String, claimedAt: String, outcomes: List<SuiteOutcome>): ReportReply {
            reports += Triple(requestId, "$worker@$claimedAt", outcomes)
            return replies.removeFirst().invoke()
        }
    }

    private fun claimed() = ClaimedTest(7, 3, "2026-10-08T00:00:00.123457Z", humanoid)

    private fun runner(desk: TestDesk) = RevisionTestRunner(desk, RevisionSuites(schema), "site-runner") { logs += it }

    @Test
    fun `집은 요청의 시험 셋을 집은 실행기 이름과 집은 시각으로 보고한다`() {
        val desk = ScriptedDesk(ArrayDeque(listOf({ claimed() })), ArrayDeque(listOf({ ReportReply.Recorded("TESTED") })))

        assertTrue(runner(desk).pollOnce())

        val (requestId, claimer, outcomes) = desk.reports.single()
        assertEquals(7, requestId)
        assertEquals("site-runner@2026-10-08T00:00:00.123457Z", claimer)
        assertEquals(listOf("CONTRACT", "NEGATIVE", "DETERMINISM"), outcomes.map { it.suite.name })
        assertTrue(outcomes.all { it.passed }, outcomes.joinToString { it.detailJson() })
    }

    @Test
    fun `집을 것이 없으면 보고하지 않는다`() {
        val desk = ScriptedDesk(ArrayDeque(listOf({ null })), ArrayDeque())

        assertFalse(runner(desk).pollOnce())
        assertTrue(desk.reports.isEmpty())
    }

    @Test
    fun `집기에 실패하면 다음 폴링을 기다린다`() {
        val desk = ScriptedDesk(ArrayDeque(listOf({ throw DeskUnavailable(503, "잠시") }, { throw DeskUnavailable(401, "토큰") })), ArrayDeque())
        val runner = runner(desk)

        assertFalse(runner.pollOnce())
        assertFalse(runner.pollOnce())
        assertTrue(logs.any { "적재 토큰" in it }, "401 을 토큰 설정 오류로 남기지 않았다: $logs")
    }

    @Test
    fun `보고의 응답을 못 받으면 다시 보내고, 다시 보낸 보고가 이미 끝남이면 거기서 멈춘다`() {
        val desk = ScriptedDesk(
            ArrayDeque(listOf({ claimed() })),
            ArrayDeque(listOf({ throw DeskUnavailable(0, "끊김") }, { ReportReply.Completed })),
        )

        runner(desk).pollOnce()

        assertEquals(2, desk.reports.size)
        assertTrue(logs.any { "앞 보고가 반영됐다" in it }, "$logs")
    }

    @Test
    fun `보고를 최대 3번 다시 보내고 포기한다`() {
        val desk = ScriptedDesk(
            ArrayDeque(listOf({ claimed() })),
            ArrayDeque(List(10) { { throw DeskUnavailable(503, "잠시") } }),
        )

        runner(desk).pollOnce()

        assertEquals(1 + RevisionTestRunner.REPORT_RETRIES, desk.reports.size)
        assertTrue(logs.any { "포기" in it }, "$logs")
    }

    @Test
    fun `이 실행기가 집은 것이 아니라는 답에는 다시 보내지 않는다`() {
        val desk = ScriptedDesk(ArrayDeque(listOf({ claimed() })), ArrayDeque(listOf({ ReportReply.NotClaimer })))

        runner(desk).pollOnce()

        assertEquals(1, desk.reports.size)
    }

    @Test
    fun `적재에서 거절되는 문서도 FAIL 셋으로 보고한다`() {
        // 보고하지 않으면 같은 요청이 15분마다 다시 집히고, 화면에는 끝내 FAIL 이 안 보인다(스펙 §5.2).
        val desk = ScriptedDesk(
            ArrayDeque(listOf({ claimed().copy(document = """{"vendor":"x"}""") })),
            ArrayDeque(listOf({ ReportReply.Recorded("VALIDATED") })),
        )

        runner(desk).pollOnce()

        val outcomes = desk.reports.single().third
        assertTrue(outcomes.none { it.passed })
        assertTrue(outcomes.all { it.failures.single().check == "LOAD" })
    }
}
```

```kotlin
package dev.picasso.harness.revision

import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeSync
import dev.picasso.registry.revision.SubmitOutcome
import dev.picasso.registry.store.Db
import dev.picasso.registry.testing.TestRequestService
import dev.picasso.registry.web.RegistryApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 실행기와 registry 의 왕복(picasso-ops P2·S1d 스펙 §3 의 P2a 완료 판정). **요청 → 집기 → 3종 → 보고 → `TESTED`.**
 *
 * registry 는 이 JVM 에 띄우되 실행기는 HTTP 로만 닿는다. 설계 문서 §3.2 가 `harness ⇢ registry` 를 런타임 접근으로
 * 두었고, 실행기의 운영 코드는 `:registry` 를 모른다.
 */
class RevisionRunnerEndToEndTest {

    private lateinit var registry: ConfigurableApplicationContext
    private lateinit var db: Db
    private lateinit var baseUrl: String
    private val schema: Path = Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize()

    @BeforeTest
    fun start() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        SkillTypeSync(db).sync(Fixtures.descriptor(), dev.picasso.contracts.wire.ContractIdentity.semver, "sync")
        // harness 시험 클래스패스에는 slf4j 구현이 둘(mimic 쪽 NOP 와 Spring 의 logback) 있다. Spring 이 로깅을 세우려다
        // «LoggerFactory is not a Logback LoggerContext» 로 기동을 거부하므로 Spring 의 로깅 초기화만 끈다.
        System.setProperty("org.springframework.boot.logging.LoggingSystem", "none")
        registry = SpringApplicationBuilder(RegistryApplication::class.java).run(
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--picasso.db.url=${PostgresSupport.jdbcUrl}",
            "--picasso.db.user=${PostgresSupport.username}",
            "--picasso.db.password=${PostgresSupport.password}",
            "--picasso.operator.token=$OPERATOR",
            "--picasso.ingest.token=$INGEST",
        )
        baseUrl = "http://127.0.0.1:${(registry as WebServerApplicationContext).webServer.port}"
    }

    @AfterTest
    fun stop() = registry.close()

    private fun submitted(name: String): Long {
        val document = Files.readString(Path.of("..", "profile", "profiles", "$name.json").normalize())
        val stored = RevisionService(db, Fixtures.validator()).submit(document, "engineer/kim") as SubmitOutcome.Stored
        assertEquals("VALIDATED", statusOf(stored.profileRevisionId), "사유: ${stored.reasons}")
        return stored.profileRevisionId
    }

    private fun runner(token: String = INGEST) = RevisionTestRunner(HttpTestDesk(baseUrl, token), RevisionSuites(schema), "site-runner")

    @Test
    fun `humanoid-a 와 quadruped-b 가 요청 → 집기 → 3종 → 보고로 TESTED 가 된다`() {
        listOf("humanoid-a", "quadruped-b").forEach { name ->
            val revision = submitted(name)
            TestRequestService(db).request(revision, "engineer/kim")

            assertTrue(runner().pollOnce(), "$name: 집지 못했다")

            assertEquals("TESTED", statusOf(revision), name)
            val runs = PostgresSupport.queryAll(
                "SELECT suite, result, ran_by, request_id IS NOT NULL, (detail->>'checks')::int FROM revision_test_run " +
                    "WHERE profile_revision_id = $revision ORDER BY suite",
            ) { "${it.getString(1)}=${it.getString(2)} by ${it.getString(3)} req=${it.getBoolean(4)} checks>0=${it.getInt(5) > 0}" }
            assertEquals(
                listOf("CONTRACT", "DETERMINISM", "NEGATIVE").map { "$it=PASS by site-runner req=true checks>0=true" },
                runs,
                name,
            )
        }
        assertFalse(runner().pollOnce(), "끝난 요청이 다시 집혔다")
    }

    @Test
    fun `창구가 보고의 409 를 끝남과 남의 요청으로 가른다`() {
        // 실행기는 둘을 다르게 다룬다. 다시 보낸 보고의 «끝남» 은 앞 보고가 반영된 것이고, «남의 요청» 은 버린다(스펙 §5.2).
        TestRequestService(db).request(submitted("quadruped-b"), "engineer/kim")
        val desk = HttpTestDesk(baseUrl, INGEST)
        val claimed = desk.claim("site-runner")!!
        val outcomes = RevisionSuites(schema).run(Files.createTempFile("q-", ".json").also { Files.writeString(it, claimed.document) })

        assertEquals(ReportReply.NotClaimer, desk.report(claimed.requestId, "other-runner", claimed.claimedAt, outcomes))
        assertEquals(ReportReply.Recorded("TESTED"), desk.report(claimed.requestId, "site-runner", claimed.claimedAt, outcomes))
        assertEquals(ReportReply.Completed, desk.report(claimed.requestId, "site-runner", claimed.claimedAt, outcomes))
    }

    @Test
    fun `운영자 토큰으로는 집지 못한다`() {
        val requestId = TestRequestService(db).request(submitted("humanoid-a"), "engineer/kim")

        assertFalse(runner(token = OPERATOR).pollOnce())
        assertEquals(null, TestRequestService(db).claimedBy(requestId), "운영자 토큰으로 요청이 집혔다")
        assertEquals(0, PostgresSupport.queryOne("SELECT count(*) FROM revision_test_run") { it.getInt(1) })
    }

    private fun statusOf(id: Long): String = PostgresSupport.queryOne(
        "SELECT status FROM profile_revision WHERE profile_revision_id = $id",
    ) { it.getString(1) }

    private companion object {
        const val OPERATOR = "operator-secret"
        const val INGEST = "ingest-secret"
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :harness:compileTestKotlin -q`
Expected: 컴파일 실패(`RevisionTestRunner`·`TestDesk`·`HttpTestDesk` 가 없다).

- [ ] **Step 3: 실행기**

```kotlin
package dev.picasso.harness.revision

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 집은 요청 하나. [claimedAt] 은 registry 가 준 문자열 그대로다 — 보고가 그대로 돌려 싣는다. */
data class ClaimedTest(val requestId: Long, val profileRevisionId: Long, val claimedAt: String, val document: String)

/** 보고의 답(picasso-ops P2·S1d 스펙 §5.1·§5.2). */
sealed interface ReportReply {
    data class Recorded(val status: String) : ReportReply

    /** 이미 끝난 요청이다. 다시 보낸 보고라면 앞 보고가 반영된 것이다. */
    data object Completed : ReportReply

    /** 이 실행기가 집은 요청이 아니다. */
    data object NotClaimer : ReportReply

    /** 그 밖의 거절(400·404). 다시 보내도 달라지지 않는다. */
    data class Refused(val status: Int, val body: String) : ReportReply
}

/** 응답을 받지 못했거나 5xx·401 이다. [status] 가 0 이면 응답이 없었다. */
class DeskUnavailable(val status: Int, message: String) : IOException(message)

/**
 * registry 의 시험 요청 문(`/ingest/test-requests`). 실행기는 이것으로만 registry 에 닿는다 — 설계 문서 §3.2 가
 * `harness ⇢ registry` 를 런타임 접근으로 두었다.
 */
interface TestDesk {
    /** 집을 것이 없으면 널. */
    fun claim(worker: String): ClaimedTest?

    fun report(requestId: Long, worker: String, claimedAt: String, outcomes: List<SuiteOutcome>): ReportReply
}

/** 적재 토큰을 쥔 HTTP 창구. JDK `HttpClient` 를 쓴다(`uplink` 와 같은 선택). */
class HttpTestDesk(
    private val baseUrl: String,
    private val ingestToken: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : TestDesk {

    override fun claim(worker: String): ClaimedTest? {
        val response = post("/ingest/test-requests/claim", MAPPER.writeValueAsString(mapOf("worker" to worker)))
        return when (response.statusCode()) {
            204 -> null
            200 -> MAPPER.readTree(response.body()).let {
                ClaimedTest(it["request_id"].asLong(), it["profile_revision_id"].asLong(), it["claimed_at"].asText(), it["document"].asText())
            }
            else -> throw DeskUnavailable(response.statusCode(), "집기 실패: ${response.statusCode()} ${response.body()}")
        }
    }

    override fun report(requestId: Long, worker: String, claimedAt: String, outcomes: List<SuiteOutcome>): ReportReply {
        val body = mapOf(
            "worker" to worker,
            "claimed_at" to claimedAt,
            "results" to outcomes.map {
                mapOf("suite" to it.suite.name, "result" to if (it.passed) "PASS" else "FAIL", "detail" to MAPPER.readTree(it.detailJson()))
            },
        )
        val response = post("/ingest/test-requests/$requestId/results", MAPPER.writeValueAsString(body))
        val status = response.statusCode()
        return when {
            status == 200 -> ReportReply.Recorded(MAPPER.readTree(response.body())["status"].asText())
            status == 409 && MAPPER.readTree(response.body())["reason"]?.asText() == "COMPLETED" -> ReportReply.Completed
            status == 409 -> ReportReply.NotClaimer
            status == 400 || status == 404 -> ReportReply.Refused(status, response.body())
            else -> throw DeskUnavailable(status, "보고 실패: $status ${response.body()}")
        }
    }

    private fun post(path: String, json: String): HttpResponse<String> = try {
        http.send(
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer $ingestToken")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    } catch (e: IOException) {
        throw DeskUnavailable(0, "registry 에 닿지 않는다: ${e.message}")
    }

    private companion object {
        val MAPPER = ObjectMapper()
    }
}

/**
 * 개정판 시험 실행기(picasso-ops P2·S1d 스펙 §5). **요청을 집어 후보 문서로 3종을 돌리고 결과를 보고한다.**
 *
 * registry 를 부르는 쪽이 이것이고 그 반대는 없다(설계 문서 §3.2 순환 방지 규칙 1). picasso 안에는 이것을 상주시키는
 * `main` 이 없다 — 첫 소비자(picasso-ops 의 가짜 현장)가 프로세스 안에서 [start] 로 띄운다(ADR 9).
 *
 * 오류 처리는 스펙 §5.2 의 표 그대로다. 집기가 실패하면 다음 폴링에 다시 집는다. 보고의 응답을 못 받으면 같은 보고를
 * 최대 [REPORT_RETRIES] 번 다시 보내고, 다시 보낸 보고가 «이미 끝남» 이면 앞 보고가 반영된 것으로 본다.
 */
class RevisionTestRunner(
    private val desk: TestDesk,
    private val suites: RevisionSuites,
    private val worker: String,
    private val log: (String) -> Unit = { System.err.println("[$it]") },
) {

    /** 하나를 집어 처리한다. 집은 것이 있었으면 참. */
    fun pollOnce(): Boolean {
        val claimed = try {
            desk.claim(worker)
        } catch (e: DeskUnavailable) {
            log(if (e.status == 401) "적재 토큰이 registry 와 맞지 않는다: ${e.message}" else "집기 실패, 다음 폴링에 다시: ${e.message}")
            return false
        } ?: return false

        val file = Files.createTempFile("revision-${claimed.profileRevisionId}-", ".json")
        val outcomes = try {
            Files.writeString(file, claimed.document)
            suites.run(file)
        } finally {
            Files.deleteIfExists(file)
        }
        report(claimed, outcomes)
        return true
    }

    private fun report(claimed: ClaimedTest, outcomes: List<SuiteOutcome>) {
        repeat(REPORT_RETRIES + 1) { attempt ->
            val reply = try {
                desk.report(claimed.requestId, worker, claimed.claimedAt, outcomes)
            } catch (e: DeskUnavailable) {
                log("보고 실패(${attempt + 1}번째): ${e.message}")
                return@repeat
            }
            when (reply) {
                is ReportReply.Recorded -> log("요청 ${claimed.requestId} 보고: ${reply.status}")
                ReportReply.Completed -> log(
                    if (attempt == 0) "요청 ${claimed.requestId} 는 이미 끝났다" else "요청 ${claimed.requestId} 는 앞 보고가 반영됐다",
                )
                ReportReply.NotClaimer -> log("요청 ${claimed.requestId} 는 이 실행기가 집은 것이 아니다 — 버린다")
                is ReportReply.Refused -> log("요청 ${claimed.requestId} 보고 거절 ${reply.status}: ${reply.body}")
            }
            return
        }
        log("요청 ${claimed.requestId} 보고를 포기한다 — 만료 뒤 다시 집힌다")
    }

    /** [interval] 마다 [pollOnce] 를 부른다. 닫으면 멈춘다. */
    fun start(interval: Duration): AutoCloseable {
        val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "revision-test-runner").apply { isDaemon = true } }
        executor.scheduleWithFixedDelay(
            {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    log("실행기 오류: ${e::class.simpleName}: ${e.message}")
                }
            },
            0, interval.toMillis(), TimeUnit.MILLISECONDS,
        )
        return AutoCloseable {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    companion object {
        /** 보고의 응답을 못 받았을 때 다시 보내는 횟수(스펙 §5.2). */
        const val REPORT_RETRIES = 3
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :harness:test --tests 'dev.picasso.harness.revision.*' -q`
Expected: XML 기준 `RevisionSuitesTest` 4, `RevisionTestRunnerTest` 7, `RevisionRunnerEndToEndTest` 3 통과. 끝의 것이 registry 를 같은 JVM 에 띄운다 — Spring 이 «LoggerFactory is not a Logback LoggerContext» 로 기동을 거부하면 시험 안의 `LoggingSystem=none` 줄이 빠진 것이다.

- [ ] **Step 5: 게이트 검사 11 패치** — `C:/Users/Eisen/AppData/Local/Temp/p2a-patches/p2a-4.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/p2a-patches/p2a-4.patch`. 실행기가 `HttpClient` 를 열어 출하 소스의 아웃바운드 자리가 하나 는다.

```diff
diff --git a/gate/src/main/kotlin/dev/picasso/gate/checks/Check11OutboundScope.kt b/gate/src/main/kotlin/dev/picasso/gate/checks/Check11OutboundScope.kt
index 1b8bc7a..c7d7954 100644
--- a/gate/src/main/kotlin/dev/picasso/gate/checks/Check11OutboundScope.kt
+++ b/gate/src/main/kotlin/dev/picasso/gate/checks/Check11OutboundScope.kt
@@ -125,6 +125,8 @@ class Check11OutboundScope : GateCheck {
          *
          * `uplink` 넷은 이 저장소 자신의 `registry` 로 관측을 올린다(`/ingest` 아래). `adapter-orbit`
          * 하나는 벤더 플릿 관제와 말한다(ADR 37 · 39) — 기종 지식이 갈 수 있는 유일한 자리다.
+         * `harness` 의 개정판 시험 실행기는 이 저장소의 `registry` 에서 시험 요청을 집고 결과를 올린다(`/ingest` 아래,
+         * ADR 49).
          */
         val DECLARED = setOf(
             "uplink/src/main/kotlin/dev/picasso/uplink/report/HttpHandshakeReporter.kt",
@@ -132,6 +134,7 @@ class Check11OutboundScope : GateCheck {
             "uplink/src/main/kotlin/dev/picasso/uplink/report/HttpTaskObservations.kt",
             "uplink/src/main/kotlin/dev/picasso/uplink/report/RobotDiscovery.kt",
             "adapter-boston-dynamics-orbit/src/main/kotlin/dev/picasso/adapter/orbit/OrbitHttpLink.kt",
+            "harness/src/main/kotlin/dev/picasso/harness/revision/RevisionTestRunner.kt",
         )
     }
 }
```

- [ ] **Step 6: 커밋과 대조**

```bash
git add harness/src/main/kotlin/dev/picasso/harness/revision/RevisionTestRunner.kt harness/src/test/kotlin/dev/picasso/harness/revision/RevisionTestRunnerTest.kt harness/src/test/kotlin/dev/picasso/harness/revision/RevisionRunnerEndToEndTest.kt gate/src/main/kotlin/dev/picasso/gate/checks/Check11OutboundScope.kt
git commit -q -F - <<'EOF'
feat(harness): 개정판 시험 실행기와 registry 시험 창구

- 작업 묶음 커밋(Task 9 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
bash C:/Users/Eisen/AppData/Local/Temp/p2a-cmp.sh harness/build.gradle.kts harness/src/main/kotlin/dev/picasso/harness/revision/MinimalParameters.kt harness/src/main/kotlin/dev/picasso/harness/revision/RevisionSuites.kt harness/src/test/kotlin/dev/picasso/harness/revision/RevisionSuitesTest.kt harness/src/main/kotlin/dev/picasso/harness/revision/RevisionTestRunner.kt harness/src/test/kotlin/dev/picasso/harness/revision/RevisionTestRunnerTest.kt harness/src/test/kotlin/dev/picasso/harness/revision/RevisionRunnerEndToEndTest.kt gate/src/main/kotlin/dev/picasso/gate/checks/Check11OutboundScope.kt
```
Expected: 8줄 모두 «같음».

---

## Chunk 3: 결함 주입, 문서, PR

### Task 5: 결함 주입(시험이 잡는가)

**Files:** 아래 파일들(넣고 되돌린다)

주입마다: 옛 문자열을 새 문자열로 바꾸고, 명령을 돌리고, XML 의 실패 시험 이름에 기대한 이름이 있는지 보고, 되돌린다. 되돌린 뒤 `git diff --stat` 이 비어야 한다. 한 번에 하나만 넣는다. H1·H2·H3 은 빨개지는 시험이 같으므로 XML 의 실패 메시지가 각각 `NEGATIVE:`·`CONTRACT:`·`DETERMINISM:` 으로 시작하는지까지 본다(시험이 `$suite: <상세>` 를 메시지로 싣는다).

| ID | 파일 | 옛 → 새 | 명령 | 빨개져야 할 시험 |
|---|---|---|---|---|
| R1 | `registry/.../testing/TestRequestService.kt` | `openRequest(c, profileRevisionId)?.let { return@transaction TestRequested.Existing(it) }` 줄 지움 | `./gradlew :registry:test --tests '*RevisionTestRequestTest' -q` | `요청은 멱등이고 감사는 처음 한 번만 남는다` |
| R2 | 같은 파일 | `WHERE r.completed_at IS NULL` 과 다음 줄의 `AND (…)` → `WHERE (r.claimed_by IS NULL OR r.claim_expires_at <= ?)` | 같음 | `끝난 요청은 만료가 지나도 다시 집히지 않는다` |
| R3 | 같은 파일 | `row.claimedBy != worker \|\| row.claimedAt != claimedAt.truncatedTo(ChronoUnit.MICROS)` → `row.claimedBy != worker` | 같음 | `같은 이름으로 다시 집은 요청에 옛 집은 시각의 보고는 받지 않는다` |
| R4 | 같은 파일 | `claim` 의 `rs.getTimestamp(1).toInstant() }` → `at }` | 같음 | `집은 시각은 DB 에 적힌 값 그대로다` |
| R5 | 같은 파일 | `if (status in UNTESTABLE) return@transaction TestRequested.NotTestable(status)` 줄 지움 | 같음 | `없는 개정판과 DRAFT·REVOKED 는 시험을 요청할 수 없다` |
| R6 | 같은 파일 | `s.setObject(1, now().atOffset(ZoneOffset.UTC)); s.setLong(2, requestId)` → `s.setObject(1, null); s.setLong(2, requestId)` | 같음 | `끝난 요청은 만료가 지나도 다시 집히지 않는다` |
| R7 | `registry/.../web/TestRequestController.kt` | `"reason" to "COMPLETED"` → `"reason" to "NOT_CLAIMER"` | `./gradlew :registry:test --tests '*TestRequestEndpointTest' -q` | `보고의 409 는 reason 으로 갈린다` |
| R8 | 같은 파일 | `is TestRequested.Existing -> ResponseEntity.ok(` → `is TestRequested.Existing -> ResponseEntity.status(HttpStatus.CREATED).body(` | 같음 | `요청은 조작 토큰 뒤이고 새로 만들면 201, 다시 요청하면 같은 요청 200 이다` |
| R9 | `registry/.../testing/TestRequestService.kt` | `bindings.promoteIfAllPass(c, row.profileRevisionId, worker)` 줄 지움 | `./gradlew :registry:test --tests '*RevisionTestRequestTest' -q` | `셋 다 PASS 면 TESTED 이고 실행 3행에 요청 id 와 상세가 남는다` |
| H1 | `mimic/.../engine/TaskMachine.kt` | `if (command == TaskCommand.CANCEL && skill.cancelSupport == Support.SUPPORT_NO) {` → `if (false) {` | `./gradlew :harness:test --tests '*RevisionSuitesTest' -q` | `humanoid-a 는 셋 다 통과한다`(메시지 `NEGATIVE:`) |
| H2 | `mimic/.../transport/SkillServiceImpl.kt` | `.setCapability(hosted.instance.capability)` → `.setCapability(hosted.instance.capability.toBuilder().setProfileRevision(0).build())` | 같음 | `humanoid-a 는 셋 다 통과한다`(메시지 `CONTRACT:`) |
| H3 | `harness/.../revision/RevisionSuites.kt` | `val second = harness(document, seed).use` → `val second = harness(document, 987_654_321L).use` | 같음 | `humanoid-a 는 셋 다 통과한다`(메시지 `DETERMINISM:`). `quadruped-b` 는 지터가 없어 통과로 남는다 |
| H4 | 같은 파일 | `val VOLATILE = setOf("session_id", "event_id")` → `val VOLATILE = setOf<String>()` | 같음 | `quadruped-b 는 셋 다 통과한다` |
| H5 | `harness/.../revision/RevisionTestRunner.kt` | `repeat(REPORT_RETRIES + 1) { attempt ->` → `repeat(1) { attempt ->` | `./gradlew :harness:test --tests '*RevisionTestRunnerTest' -q` | `보고를 최대 3번 다시 보내고 포기한다` |
| H6 | `harness/.../revision/RevisionSuites.kt` | `return Suite.entries.map { SuiteOutcome(it, listOf("LOAD"), listOf(failure)) }` → `throw e` | `./gradlew :harness:test --tests '*RevisionSuitesTest' -q` | `적재에서 거절된 문서는 셋 다 FAIL 이다` |
| H7 | `harness/.../revision/RevisionTestRunner.kt` | `"claimed_at" to claimedAt,` → `"claimed_at" to java.time.Instant.now().toString(),` | `./gradlew :harness:test --tests '*RevisionRunnerEndToEndTest' -q` | `humanoid-a 와 quadruped-b 가 요청 → 집기 → 3종 → 보고로 TESTED 가 된다` |
| H8 | `harness/.../revision/RevisionSuites.kt` | `(catalogSkills() - declared).sorted()` → `emptyList<String>()` | `./gradlew :harness:test --tests '*RevisionSuitesTest' -q` | `NEGATIVE 는 프로파일이 못 한다고 적은 것마다 탐침을 보낸다` |
| H9 | `harness/.../revision/RevisionTestRunner.kt` | `status == 409 && MAPPER.readTree(response.body())["reason"]?.asText() == "COMPLETED" -> ReportReply.Completed` → `status == 409 && false -> ReportReply.Completed` | `./gradlew :harness:test --tests '*RevisionRunnerEndToEndTest' -q` | `창구가 보고의 409 를 끝남과 남의 요청으로 가른다` |
| G1 | `gate/.../checks/Check11OutboundScope.kt` | 목록의 `"harness/src/main/kotlin/dev/picasso/harness/revision/RevisionTestRunner.kt",` 줄 지움 | `./gradlew :gate:test -q`(Task 6 뒤에 돌린다 — 문서가 맞아야 이 시험 하나만 빨개진다) | `원본 트리는 모든 검사를 통과한다` |

- [ ] **Step 1: R1~R9 를 하나씩** — 위 표대로. 스파이크에서 9건 모두 기대한 이름이 빨개졌다.
- [ ] **Step 2: H1~H9 를 하나씩** — 위 표대로. H3 은 반드시 먼 시드로 넣는다(시드 +1 은 등가 변이다).
- [ ] **Step 3: 되돌림 확인**

Run: `git status --short`
Expected: 빈 출력.

### Task 6: 구성도, 문서, 시험 수, 전체 빌드

**Files:**
- Modify: `tools/diagram-gen/components.mjs`(새 간선 `harness>capability` 의 경로)
- Modify: `docs/diagrams/components.svg`, `docs/diagrams/components.dark.svg`(생성기 출력)
- Modify: `README.md`, `docs/architecture.md`(구성도 대체 문구 «간선 17개» → 18, §4b 의존 표의 `harness`, 스탬프)
- Modify: `registry/README.md`(V1~V16, `*EndpointTest` 여섯, 스탬프)
- Modify: `docs/verification.md`(시험 수 1,905, `*EndpointTest` 여섯, 스탬프)
- Modify: `CLAUDE.md`(시험 수 1,905, 스탬프)
- Modify: `docs/commissioning.md`(§4 REST 표 행 3개, 스탬프)
- Create: `docs/adr/0049-revision-tests-have-a-runner.md`
- Modify: `docs/adr/README.md`(ADR 49 행, 스탬프)
- Modify: `docs/superpowers/specs/2026-09-05-picasso-design.md`(§14 행 49, §15 변경 이력 207, 스탬프)
- Modify: `docs/limits.md`(«번호가 207 까지», 스탬프)
- Modify: `docs/glossary.md`(«네거티브 테스트» 와 리비전 `NEGATIVE` 의 구분, 스탬프)
- Modify: `harness/README.md`(실행기 불릿, 스탬프)
- Modify: `gate/src/test/kotlin/dev/picasso/gate/CompletionCriterionTest.kt`(주장 문서 64 → 65. ADR 49 가 스탬프 문서라 한 자리 는다)

- [ ] **Step 1: 생성기 패치와 구성도 다시 뽑기** — 아래를 `C:/Users/Eisen/AppData/Local/Temp/p2a-patches/p2a-5.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/p2a-patches/p2a-5.patch` 한 뒤 생성기를 돌린다.

```diff
diff --git a/tools/diagram-gen/components.mjs b/tools/diagram-gen/components.mjs
index 548ef45..0f2d523 100644
--- a/tools/diagram-gen/components.mjs
+++ b/tools/diagram-gen/components.mjs
@@ -76,6 +76,8 @@ const ROUTES = {
   'registry>gate': 'M736,268 V350',
   'harness>mimic': 'M256,164 V214',
   'harness>client': 'M192,128 H16 V378 H30',
+  // 통로(x 168~184)의 한가운데로 내려간다. picasso>capability 의 가로 구간(y 292)과 한 번 직각으로 엇갈린다.
+  'harness>capability': 'M192,148 H176 V392 H190',
   'harness>uplink': 'M320,148 H340 V384 H350',
   'orbit>adapter-host': 'M512,132 H432 V214',
   'orbit>uplink': 'M512,156 H496 V392 H482',
```

```bash
node tools/diagram-gen/components.mjs docs/diagrams/components.svg 840 .
node docs/diagrams/make-dark.mjs docs/diagrams/components.svg
```
Expected: 첫 줄 출력 `840x560 · 간선 16 경로 · contracts 13 · profile-model 7 · 수로 적은 간선 20`. 경로를 정하지 않으면 생성기가 «경로가 없는 간선: harness -> capability» 로 멈춘다.

- [ ] **Step 2: 문서 패치** — 아래를 `C:/Users/Eisen/AppData/Local/Temp/p2a-patches/p2a-6.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/p2a-patches/p2a-6.patch`. 스탬프(`> 마지막 대조` 줄)까지 들어 있으므로 따로 `tools/stamp.py` 를 돌리지 않는다. 문장은 사용자 지시대로 Fable·Codex 초안을 취합한 것이다.

````diff
diff --git a/CLAUDE.md b/CLAUDE.md
index c376ce4..52ccb68 100644
--- a/CLAUDE.md
+++ b/CLAUDE.md
@@ -8,7 +8,7 @@
 ## 1. 빌드 및 테스트 명령
 
 ```bash
-# 전체 빌드 및 테스트 실행 (총 1,871개 테스트)
+# 전체 빌드 및 테스트 실행 (총 1,905개 테스트)
 ./gradlew build
 
 # 아키텍처 및 품질 게이트 검증만 실행
@@ -72,4 +72,4 @@
 - **저장소 경계 준수**: 본 저장소 밖의 다른 저장소 파일을 직접 생성하거나 수정하지 않습니다. 계층이 서로 다른 저장소에 위치하고 상호 참조하지 않는다는 사실 자체가 아키텍처 경계의 증명이며, 편의를 이유로 한 번 넘어가면 그 증명이 소멸합니다. 다른 저장소로 넘길 산출물은 `handoff/<받는 쪽>/` 에 두고 경로만 전달하며, 무엇을 반입할지는 받는 쪽이 결정합니다.
 - **한계점 및 히스토리 관리**: 미결 과제는 [`docs/limits.md`](docs/limits.md)에 기록하며, 설계 문서 `§15`의 변경 이력은 기존 항목을 삭제하지 않고 정정 내용을 누적 기록합니다.
 
-> 마지막 대조: 2026-10-07 · sha256:ee8061826504 · 열림: 없음
+> 마지막 대조: 2026-10-08 · sha256:2150a711500d · 열림: 없음
diff --git a/README.md b/README.md
index 2c8dcad..137dc84 100644
--- a/README.md
+++ b/README.md
@@ -29,7 +29,7 @@
 
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="docs/diagrams/components.dark.svg">
-  <img alt="저장소 모듈 16개 사이의 프로덕션 의존 관계를 나타냅니다. 계약 어휘인 contracts는 13개 모듈이, profile-model은 7개 모듈이 프로덕션 의존으로 사용합니다. 해당 계약 어휘로 향하는 20개를 제외한 나머지 의존 간선 17개는 화살표로 직접 연결합니다." src="docs/diagrams/components.svg">
+  <img alt="저장소 모듈 16개 사이의 프로덕션 의존 관계를 나타냅니다. 계약 어휘인 contracts는 13개 모듈이, profile-model은 7개 모듈이 프로덕션 의존으로 사용합니다. 해당 계약 어휘로 향하는 20개를 제외한 나머지 의존 간선 18개는 화살표로 직접 연결합니다." src="docs/diagrams/components.svg">
 </picture>
 
 ```
@@ -151,4 +151,4 @@ client --target <host:port> --robot <id> --requirements <file> --skill <type> [-
 - **결함 주입(Mutation Testing)**: 테스트 케이스 작성 시 의도적 결함을 주입하여 검증 유효성을 선행 확인합니다.
 - **엄격한 실패 정책**: 사전 선언된 요구 검사 목록(`--require`)을 충족하지 못하는 경우 조용한 통과를 허용하지 않습니다.
 
-> 마지막 대조: 2026-10-06 · sha256:98206d8d2382 · 열림: C-3, §15.81
+> 마지막 대조: 2026-10-08 · sha256:6dd5d46bfff0 · 열림: C-3, §15.81
diff --git a/docs/adr/README.md b/docs/adr/README.md
index 8533f09..99ed77f 100644
--- a/docs/adr/README.md
+++ b/docs/adr/README.md
@@ -43,6 +43,7 @@
 | **46** | **승인이 소모한 제안은 (기체, 작업 지시)마다 기록하고, 다시 온 승인에 `CONSUMED` 로 그 기록을 돌려줌. `NO_PROPOSAL` 은 제안이 선 적이 없는 경우로 좁힘** | [`0046`](0046-consumed-approval-is-recorded.md) |
 | **47** | **운영자 판단(`resolve`)은 사람만 내고 누가 냈는지 인시던트에 남김. 에이전트는 거절하며, 결정자는 읽는 쪽이 생길 때까지 내보내지 않음** | [`0047`](0047-operator-decision-is-made-by-a-person.md) |
 | **48** | **작업 응답에 버전이 있는 바깥 형식을 두고(쓰기는 담는 쪽), 승인 응답과 실행 · 단계 단위 · 인스턴스 식별자로 이음. 승인 창구 버전 4** | [`0048`](0048-result-notice-has-an-outside-shape.md) |
+| **49** | **개정판 시험 3종(`CONTRACT` · `NEGATIVE` · `DETERMINISM`)의 뜻을 정하고, 실행기를 harness 운영 코드에 두어 registry 의 문 3개(요청 · 집기 · 보고)에 HTTP 로만 닿게 함. 보고는 집은 시각까지 대고, 검사 0개는 통과가 아님** | [`0049`](0049-revision-tests-have-a-runner.md) |
 
 ---
 
@@ -59,4 +60,4 @@
 
 본 결정 기록들을 관통하는 핵심 엔지니어링 원칙은 **"조용한 통과(Silent Pass)는 명시적 실패보다 치명적이다"**라는 점입니다. 검증 도구가 자원 누락이나 스키마 불일치를 조용히 묵인하면 런타임 장애로 전이되므로, 모든 거버넌스 규칙은 결함 주입 시 즉각적이고 명시적으로 실패하도록 설계되었습니다.
 
-> 마지막 대조: 2026-10-06 · sha256:1907ccd38de8 · 열림: 없음
+> 마지막 대조: 2026-10-08 · sha256:4c7125ee6bf1 · 열림: 없음
diff --git a/docs/architecture.md b/docs/architecture.md
index 671e267..2c0abaf 100644
--- a/docs/architecture.md
+++ b/docs/architecture.md
@@ -136,7 +136,7 @@ registry         ← 메타데이터 및 카탈로그 레지스트리 (무상태
 
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="diagrams/components.dark.svg">
-  <img alt="저장소 모듈 16개 사이의 프로덕션 의존 관계를 나타냅니다. 계약 어휘인 contracts는 13개 모듈이, profile-model은 7개 모듈이 프로덕션 의존으로 사용합니다. 해당 계약 어휘로 향하는 20개를 제외한 나머지 의존 간선 17개는 화살표로 직접 연결합니다." src="diagrams/components.svg">
+  <img alt="저장소 모듈 16개 사이의 프로덕션 의존 관계를 나타냅니다. 계약 어휘인 contracts는 13개 모듈이, profile-model은 7개 모듈이 프로덕션 의존으로 사용합니다. 해당 계약 어휘로 향하는 20개를 제외한 나머지 의존 간선 18개는 화살표로 직접 연결합니다." src="diagrams/components.svg">
 </picture>
 
 본 다이어그램과 의존성 표는 임의 작성된 텍스트가 아니며, 각 모듈의 `build.gradle.kts` 프로덕션 의존성 선언을 `DocumentClaimsTest`가 직접 파싱하여 정합성을 검증합니다.
@@ -152,7 +152,7 @@ capability                     → contracts · profile-model
 client                         → contracts · profile-model
 contracts                      → (없음)
 gate                           → profile-model
-harness                        → client · contracts · mimic · profile-model · uplink
+harness                        → capability · client · contracts · mimic · profile-model · uplink
 mimic                          → capability · contracts · profile-model · uplink
 picasso                        → capability · client · contracts
 profile-model                  → (없음)
@@ -185,4 +185,4 @@ uplink                         → contracts
 5. [`commissioning.md`](commissioning.md) — 현장 시운전 절차 및 운영 설정 REST API 명세
 6. [공식 설계 문서](superpowers/specs/2026-09-05-picasso-design.md) — 시스템 전체 설계 정본 스펙
 
-> 마지막 대조: 2026-10-06 · sha256:7f38d9ee948a · 열림: 시나리오 §8, §15.34, §15.5, ADR 32 · 시나리오 5, §1.3 B-1, §15.126
+> 마지막 대조: 2026-10-08 · sha256:18296c0982f2 · 열림: 시나리오 §8, §15.34, §15.5, ADR 32 · 시나리오 5, §1.3 B-1, §15.126
diff --git a/docs/commissioning.md b/docs/commissioning.md
index 698ac3f..1200995 100644
--- a/docs/commissioning.md
+++ b/docs/commissioning.md
@@ -83,7 +83,10 @@ ISA-95 제조 통합 표준의 핵심 원칙에 따라 시스템 엔티티를 **
 | `POST /operations/adapters` | 조작 (Operations) | 어댑터 제품(vendor, name) 등록 |
 | `POST /operations/adapters/{adapterId}/versions` | 조작 (Operations) | 어댑터 빌드(version, 계약 SemVer) 등록 |
 | `GET /operations/adapters` | 조작 (Operations) | 등록된 제품과 빌드 목록 및 빌드별 적합성 상태 조회 |
+| `POST /operations/profile-revisions/{profileRevisionId}/test-requests` | 조작 (Operations) | 프로파일 개정판 시험 요청(스위트 3종) 등록 |
 | `POST /ingest/robots` | 적재 (Ingest) | 어댑터가 플릿 관리자에서 자동 발견한 기체 정보 전송 |
+| `POST /ingest/test-requests/claim` | 적재 (Ingest) | 실행기의 시험 요청 집기(후보 문서 및 집은 시각 인출) |
+| `POST /ingest/test-requests/{requestId}/results` | 적재 (Ingest) | 실행기의 스위트 3종 결과 보고 및 `TESTED` 승격 |
 | `POST /ingest/handshake` | 적재 (Ingest) | 기동 시 어댑터 빌드 및 바인딩된 프로파일 정보 보고 |
 | `POST /ingest/liveness` | 적재 (Ingest) | 기체 주기적 하트비트(Liveness) 보고 |
 | `POST /ingest/task` | 적재 (Ingest) | 기체의 원자적 태스크 실행 관측치 수집 |
@@ -113,4 +116,4 @@ ISA-95 제조 통합 표준의 핵심 원칙에 따라 시스템 엔티티를 **
 - **비가역 차원 (계약)**: 인터페이스 계약(Contracts)의 변경은 소비자가 이미 생성된 stub 코드를 탑재하고 있으므로 즉각적인 롤백이 불가능합니다.
 - **가역 차원 (프로파일·어댑터·바인딩)**: 프로파일 재활성화, 이전 어댑터 재배포, 이전 바인딩 롤백을 통해 운영 중 안전하게 복구 가능합니다.
 
-> 마지막 대조: 2026-10-07 · sha256:1f529dba3f20 · 열림: §15.123, §15.106 · CLI, §15.128, §15.129
+> 마지막 대조: 2026-10-08 · sha256:9263c65e1df7 · 열림: §15.123, §15.106 · CLI, §15.128, §15.129
diff --git a/docs/glossary.md b/docs/glossary.md
index e77917f..7811df3 100644
--- a/docs/glossary.md
+++ b/docs/glossary.md
@@ -150,7 +150,7 @@ OPC UA 기반의 단위 스킬 상태 모델:
 실제 하드웨어 도입 또는 인프라 교체 시, 기존 소스 코드를 수정하지 않고 인터페이스 구현체만 교체할 수 있도록 설계된 아키텍처 결합 지점입니다 ([`docs/seams.md`](seams.md)).
 
 ### 네거티브 테스트 (Negative Tests)
-게이트 검사 규칙 자체가 정상 동작하는지 검증하기 위해, 의도적으로 결함이 주입된 데이터셋(`gate/negative/`)을 실행하여 CI 파이프라인이 정확히 실패하는지를 테스트하는 역검증 스위트입니다.
+게이트 검사 규칙 자체가 정상 동작하는지 검증하기 위해, 의도적으로 결함이 주입된 데이터셋(`gate/negative/`)을 실행하여 CI 파이프라인이 정확히 실패하는지를 테스트하는 역검증 스위트입니다. 프로파일 개정판 시험의 `NEGATIVE` 스위트는 이와 다른 것으로, 프로파일이 못 한다고 적은 것마다 탐침을 보내 정해진 거절 코드가 오는지 확인하는 개정판 시험입니다. 그 뜻은 [ADR 49](adr/0049-revision-tests-have-a-runner.md)에 적혀 있습니다.
 
 ---
 
@@ -283,4 +283,4 @@ OPC UA 기반의 단위 스킬 상태 모델:
 | 인계 | 공정 간 인계 | 실행기(로봇, 설비) 사이에서 작업물을 넘기는 것 |
 | 인계 | 핸드오프 | 다른 시스템이나 저장소로 산출물을 넘기는 것 |
 
-> 마지막 대조: 2026-10-06 · sha256:12d151ec7b2d · 열림: §15.185
+> 마지막 대조: 2026-10-08 · sha256:66574742c455 · 열림: §15.185
diff --git a/docs/limits.md b/docs/limits.md
index d1fa80e..30287d3 100644
--- a/docs/limits.md
+++ b/docs/limits.md
@@ -2,7 +2,7 @@
 
 본 문서는 `picasso` 미들웨어 아키텍처 및 구현 상에 존재하는 **알려진 한계(Known Limitations), 스코프 외 제외 항목, 기술 부채 및 해소 조건**을 체계적으로 추적 관리하기 위한 엔지니어링 레지스터입니다.
 
-설계 문서의 §15는 전체 변경 이력과 배경을 누적 기록하는 변경 이력이며 (번호가 206 까지 갔고), 본 문서는 현재 시점에서 유효한 오픈 항목 항목만을 분류하여 제공합니다. 해결 완료된 항목은 변경 이력에 `(닫힘)` 처리되고 본 레지스터에서는 정리됩니다.
+설계 문서의 §15는 전체 변경 이력과 배경을 누적 기록하는 변경 이력이며 (번호가 207 까지 갔고), 본 문서는 현재 시점에서 유효한 오픈 항목 항목만을 분류하여 제공합니다. 해결 완료된 항목은 변경 이력에 `(닫힘)` 처리되고 본 레지스터에서는 정리됩니다.
 
 ---
 
@@ -129,4 +129,4 @@
 - 하위 범주는 **해소의 주어**로 정합니다. 해소 조건에 적은 주어와 하위 범주가 어긋나면 `DocumentClaimsTest` 가 막습니다. 다만 주어를 용어로 부르지 않는 행은 기계가 못 가리므로 사람이 읽어야 합니다(§15.194).
 - 밖을 향한 문서가 드는 열림은 **132** 개다. 각 문서 하단 스탬프에 기재된 오픈 항목 ID 총합은 본 수치와 엄격히 일치해야 합니다 (`CompletionCriterionTest` 집행).
 
-> 마지막 대조: 2026-10-07 · sha256:30f9e48da8ee · 열림: 없음
+> 마지막 대조: 2026-10-08 · sha256:08ccf6df75ff · 열림: 없음
diff --git a/docs/superpowers/specs/2026-09-05-picasso-design.md b/docs/superpowers/specs/2026-09-05-picasso-design.md
index 4f8c748..b3ff6bc 100644
--- a/docs/superpowers/specs/2026-09-05-picasso-design.md
+++ b/docs/superpowers/specs/2026-09-05-picasso-design.md
@@ -1103,10 +1103,11 @@ mimic/
 | [46](../../adr/0046-consumed-approval-is-recorded.md) | 승인이 소모한 제안을 (기체, 주문)마다 기록하고, 다시 온 승인에 `CONSUMED` 로 그 기록을 돌려줌 | §15.201 |
 | [47](../../adr/0047-operator-decision-is-made-by-a-person.md) | 운영자 판단은 사람만 내고 누가 냈는지 사건에 남기며, 판단자는 읽는 쪽이 생길 때까지 내보내지 않음 | §15.202 |
 | [48](../../adr/0048-result-notice-has-an-outside-shape.md) | 결과 통보에 판이 있는 바깥 형식을 두고, 승인 답과 실행 · 걸음 단위 · 인스턴스 식별자로 이음 | §15.203 |
+| [49](../../adr/0049-revision-tests-have-a-runner.md) | 개정판 시험 3종의 뜻을 정하고, 실행기를 harness 에 두어 registry 의 문 3개(요청 · 집기 · 보고)에 HTTP 로만 닿게 함 | §15.207 |
 
 기록은 `docs/adr/`에 있고 번호가 이 표의 행 번호다. **이미 내려서 코드에 박힌 것만 쓴다** — 3a·3b가 만들 것(11~21)은 그때 쓴다. 결정하지 않은 것을 미리 적어 두면 그것이 결정처럼 보인다.
 
-> 마지막 대조: 2026-10-02 · sha256:9e82d3174e84 · 열림: C-3, §15.7, §15.126, ADR 32 · 시나리오 5, §15.4, §15.5, §15.6 · §15.11 · §15.28, §15.8, §15.9, §15.10, §15.1, §15.2, §15.33, §15.87
+> 마지막 대조: 2026-10-08 · sha256:a0f4bd977d83 · 열림: C-3, §15.7, §15.126, ADR 32 · 시나리오 5, §15.4, §15.5, §15.6 · §15.11 · §15.28, §15.8, §15.9, §15.10, §15.1, §15.2, §15.33, §15.87
 
 ## 15. 알려진 한계
 
@@ -3097,6 +3098,18 @@ mimic/
 
     **검사 9번이 결속을 어댑터 경계 안에 가둔다.** 탐색어는 결속 파일이 선언한 최상위 이름에서 유도하므로 타입을 더하면 금지도 저절로 는다. 훑는 모듈에 `registry` 와 `profile-model` 을 넣었다 — 검사 7의 목록에 그 둘이 없어서, 없다는 이유로 결속까지 새면 같은 구멍이 두 번째로 열린다.
 
+207. **개정판 시험 3종의 뜻을 정하고 실행기를 지어 요청 → 집기 → 3종 → 보고 → `TESTED` 고리를 닫았다.**
+
+    설계 §8.4 ② 가 적은 «시험 요청 적재 → harness 폴링 인출 → 가상화 계약 검증 스위트 완주 → TESTED» 고리는 지어지지 않은 채였다. `V2__testing.sql` 주석이 «폴링 고리(harness 쪽)는 3a-2다» 로 미뤘고, `harness` 의 `Suite` 열거형을 쓰는 코드가 없었으며, `TestRequestService.claim` 과 `BindingService.recordTestRun` 은 시험 소스만 불렀다. 시험 요청에 «끝남» 칸이 없어 집은 요청은 15분 만료 뒤 다시 집혔다. 바깥 첫 소비자 picasso-ops 의 P2·S1d 설계 스펙(`docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md`, picasso-ops 저장소)이 화면에서 개정판을 시험 요청하고 진짜 실행기가 시험하게 정했다(2026-10-07 사용자 결정). 실행기는 picasso-ops 의 가짜 현장(`site/`) 프로세스가 띄운다. 소비자가 생겨서 고리를 지었다(ADR 9).
+
+    registry 에 문 3개를 열었다. `POST /operations/profile-revisions/{profileRevisionId}/test-requests`(운영자 토큰, `X-Actor` 필수)는 201 새 요청, 200 끝나지 않은 요청이 이미 있음(그 요청, 멱등), 404 없는 개정판, 409 `DRAFT`·`REVOKED`(본문 `status`)다. `POST /ingest/test-requests/claim`(적재 토큰, 본문 `worker`)은 200 요청 id·개정판 id·집은 시각(`claimed_at`, DB 에 적힌 값)·후보 문서, 204 집을 것 없음이고 끝난 요청은 집지 않는다. `POST /ingest/test-requests/{requestId}/results`(적재 토큰, 본문 `worker`·`claimed_at`·`results` 3개)는 200 보고 뒤 상태, 400 스위트가 셋이 아니거나 모르는 결과·시각 형식 오류, 404 없는 요청, 409 는 본문 `reason` 으로 가른다(`COMPLETED`·`NOT_CLAIMER`). 실행 3행, 요청의 «끝남», 승격이 한 트랜잭션이다. 스키마 `V16__test_request_completion.sql` 이 `revision_test_request.completed_at` 칸과 부분 유일 색인 `revision_test_request_one_open` 을 더했다. 집은 시각까지 대는 것은 실행기 이름이 같은 이름으로 다시 뜰 수 있어 이름만으로는 죽은 실행기의 늦은 보고를 못 가르기 때문이다. 집은 시각은 DB 값(마이크로초)을 그대로 돌려준다 — 메모리 값(나노초)을 내주면 모든 보고가 거절된다.
+
+    시험 3종의 뜻을 정했다(ADR 49). `CONTRACT` 는 선언 스킬 전부와 `REQUIRED` 선택 필드로 협상하면 수락되고, 능력 조회가 `CapabilityProjection.of(문서)` 와 같으며, 선언 스킬마다 새 기체에서 태스크가 수락되고 성공이거나 선언한 결함으로 멈춘다. `NEGATIVE` 는 프로파일이 못 한다고 적은 것마다 탐침 1개를 보내 정해진 거절 코드(`SKILL_ABSENT`·`PARAMETER_INVALID`·`CANCEL_UNSUPPORTED`·`PAUSE_UNSUPPORTED`·`REQUIRED_OPTIONAL_MISSING`)가 오는지 본다. `DETERMINISM` 은 `CONTRACT` 의 태스크 시나리오를 같은 시드로 두 번, 각각 새 `Harness` 에서 돌려 발행 전부와 태스크 갱신이 같은지 댄다. 스위트가 검사를 하나도 안 돌렸으면 통과가 아니고, 상세 JSON(`checks`·`failures`)이 `revision_test_run.detail` 에 그대로 들어간다. `MinimalParameters` 가 선언에서 최소 유효값과 어긴 값을 만든다. 실행기 `RevisionTestRunner` 는 `harness` 의 운영 코드(`dev.picasso.harness.revision`)에 두고, 후보 문서를 임시 파일로 써서 기존 `Harness` 에 넘기며, registry 에는 HTTP 로만 닿는다(`HttpTestDesk`). `harness` 운영 코드는 `:registry` 에 의존하지 않고 `:capability` 의존이 하나 늘었다. 적재에서 거절된 문서는 세 스위트를 모두 FAIL(`LOAD`)로 보고하고, 보고의 응답을 못 받으면 최대 3번 다시 보내며 409 `COMPLETED` 면 앞 보고가 반영된 것으로 본다. 게이트 검사 11 목록에 `RevisionTestRunner.kt` 를 더하고 `docs/diagrams/components.svg` 에 `harness → capability` 간선을 그렸다.
+
+    시험 34개를 더해 총수가 1,871 에서 1,905 가 됐다. registry 서비스 `RevisionTestRequestTest` 12, 표면 `TestRequestEndpointTest` 8, harness `RevisionSuitesTest` 4, `RevisionTestRunnerTest` 7, `RevisionRunnerEndToEndTest` 3(registry 를 같은 JVM 에 띄우고 실행기는 HTTP 로만 닿음)이다. `humanoid-a` 와 `quadruped-b` 가 요청 → 집기 → 3종 PASS → `TESTED` 로 간다. 한 프로파일의 3종은 1초 안팎에 끝난다(humanoid-a 약 0.3초). 결함 주입 19건이 모두 이름 있는 시험으로 잡혔다. registry 9: 열린 요청 재사용 제거, 집기의 끝남 조건 제거, 보고의 집은 시각 대조 제거, 집은 시각을 메모리 값으로, `DRAFT`·`REVOKED` 거절 제거, «끝남» 기록 제거, 409 `reason` 바꿈, 멱등 200 을 201 로, 보고의 승격 제거. harness·mimic 9: mimic 의 취소 미지원 거절 제거(`NEGATIVE`), 능력 응답의 개정판 번호 바꿈(`CONTRACT`), 두 번째 실행의 시드를 먼 값으로(`DETERMINISM`, humanoid-a), 정규화 제거(`DETERMINISM`), 보고 재전송 제거, 적재 거절을 예외로 던짐, 보고의 집은 시각을 현재 시각으로, 선언 안 한 스킬 탐침 제거, 409 `COMPLETED` 판별 제거. 게이트 1: 검사 11 목록에서 실행기 제거.
+
+    실측한 덫이 둘이다. `java.util.Random` 은 이웃한 작은 시드의 첫 난수가 거의 같다. 시드 0~6 으로 humanoid-a `pick_place`(작업 시간 45초, 지터 비율 0.1)를 새 기체에서 돌리면 모두 48초째 성공하고 자취가 같았다. 그래서 «두 번째 실행의 시드를 1 올린다» 는 주입은 등가 변이였고, 먼 시드(987654321)로 넣었다. 지터를 선언하지 않은 `quadruped-b` 는 시드를 바꿔도 같은 자취다. harness 시험 클래스패스에서 registry(Spring)를 띄우면 slf4j 구현 충돌(mimic 쪽 NOP 와 Spring 의 logback)로 기동이 거부된다. 시험이 Spring 의 로깅 초기화만 끈다(`org.springframework.boot.logging.LoggingSystem=none`). harness 시험 의존에 Spring Boot(starter-web)를 더했다. 남긴 것은 셋이다. 실행기를 상주시키는 `main`(picasso 에 없고 첫 소비자가 프로세스 안에서 `start(interval)` 로 띄운다), 시험 요청의 취소(만료만 있음), mimic 의 후보 개정판 검증 모드(설계 §10.2 `--registry --profile-revision`).
+
 206. **소비자의 시운전 Step 2 를 위해 어댑터 제품·빌드 등록과 조회 조작 문을 열었다.**
 
     registry 에 어댑터 제품·빌드를 등록·조회하는 조작 문 3개를 열었다. 바깥 첫 소비자 picasso-ops(운영 화면, S1 스펙 §5)가 시운전 Step 2 를 화면에서 하려 했지만 빌드를 넣는 문이 없었다. `AdapterService.registerAdapter`·`registerVersion` 은 있었으나 부르는 컨트롤러가 없어 시험만 직접 불렀다. 어댑터 인스턴스 등록은 실재하는 `adapter_version_id` 를 요구한다. `/diag/software` 는 바인딩된 기체의 소프트웨어 대조라 빌드 목록이 아니며, `/diag/adapter-instances` 는 인스턴스가 있어야 빌드가 보였다. 소비자가 생겨서 문을 열었다(ADR 9).
diff --git a/docs/verification.md b/docs/verification.md
index f4fcf9d..57b59bf 100644
--- a/docs/verification.md
+++ b/docs/verification.md
@@ -1,6 +1,6 @@
 # 시스템 검증 충실도 및 환경 신뢰도 매트릭스 (Verification & Fidelity Matrix)
 
-본 문서는 `picasso` 미들웨어 시스템의 1,871개 자동화 테스트가 **어느 구간에서 실제 외부 시스템/하드웨어와 연동되고, 어느 구간에서 모의 대역(Mock/In-process)에 의존하는지**를 명확히 구분하여 기술적 검증 신뢰도(Verification Fidelity)를 투명하게 공개하기 위해 작성되었습니다.
+본 문서는 `picasso` 미들웨어 시스템의 1,905개 자동화 테스트가 **어느 구간에서 실제 외부 시스템/하드웨어와 연동되고, 어느 구간에서 모의 대역(Mock/In-process)에 의존하는지**를 명확히 구분하여 기술적 검증 신뢰도(Verification Fidelity)를 투명하게 공개하기 위해 작성되었습니다.
 
 ---
 
@@ -30,7 +30,7 @@
 | 5 | 어댑터 ↔ 벤더 — **Orbit** | **실 회선 · 세운 상대** | **벤더 공식 명세** (OpenAPI + SDK) | 실제 HTTP/HTTPS 프로토콜(쿠키, Bearer 토큰, 상태코드)을 통과하며, 응답 서버는 `com.sun.net.httpserver` 기반 스텁 연동 | `OrbitHttpLinkTest` · `OrbitLauncherTest` |
 | 6 | 어댑터 ↔ 벤더 — **Spot · Digit · G1** | **없음** | **벤더 공식 명세** (SDK Proto·IDL·매뉴얼) | 저장소 내 벤더 독점 SDK 배제 원칙에 따라 네트워크 전송은 수행하지 않으며, `@VendorSurface` 선언과 `vendor-manifest.txt` 간의 심볼 대조 검증 수행 | `*VendorSurfaceTest` 넷 |
 | 7 | 발행(MQTT) | **실제 하드웨어** | 자체 토픽·헤더 규격 (§5.5) | Docker 컨테이너 기반 실제 Mosquitto 브로커와 연동하여 토픽 발행/구독, QoS, Last Will 정상 동작 검증 | `MqttBrokerTest` |
-| 8 | 레지스트리 HTTP API | **실제 하드웨어** | 자체 REST API | Spring Boot 임의 포트(`RANDOM_PORT`)에 실제 구동하여 `TestRestTemplate` 기반 HTTP 통합 검증 | `*EndpointTest` 다섯 |
+| 8 | 레지스트리 HTTP API | **실제 하드웨어** | 자체 REST API | Spring Boot 임의 포트(`RANDOM_PORT`)에 실제 구동하여 `TestRestTemplate` 기반 HTTP 통합 검증 | `*EndpointTest` 여섯 |
 | 9 | 레지스트리 ↔ DB | **실제 하드웨어** | 자체 DB 스키마 | Testcontainers 기반 PostgreSQL 16 컨테이너에 대해 Flyway 마이그레이션 및 외래키/CHECK 제약조건 검증 | `registry` 테스트 스위트 전체 |
 | 10 | 설비(PLC/WCS) ↔ `picasso` | **대역** | **자체 정의** | `CellMimic`을 통해 시간 윈도우 δ 기반 신호 수신 로직을 검증하나, 신호 스펙은 공장 표준 사례 기반의 자체 모델링임 | `EvidenceWindowTest` |
 | 11 | AMR 플릿 ↔ `picasso` | **대역** | **자체 정의** | `AmrFleetMimic` 기반 멱등 이송 작업 지시(Dispatch) 및 취소 정리를 검증하나, 상용 플릿 규격(VDA5050 등)과의 직접 연동은 미수행 | `DeliverContainerTest` |
@@ -45,4 +45,4 @@
 2. **로봇 인터페이스 계층의 격리성**: 어댑터 계층은 벤더 SDK 격리 원칙에 따라 매니페스트 대조를 통해 정합성을 검증하며, 실기체 직접 연동(C-3)은 환경적 제약으로 인해 오픈 항목 상태로 명시 관리됩니다.
 3. **상위 및 설비 연계 계층의 가정 기반성**: 설비(PLC) 및 AMR 플릿과의 연동 규격은 시스템적 일관성을 입증하기 위한 자체 설계 모델이며, 실제 현장 도입 시 대상 설비에 맞춘 Seam 어댑터 구현이 요구됩니다.
 
-> 마지막 대조: 2026-10-07 · sha256:361fbb143e18 · 열림: C-3
+> 마지막 대조: 2026-10-08 · sha256:e373b7b2736e · 열림: C-3
diff --git a/gate/src/test/kotlin/dev/picasso/gate/CompletionCriterionTest.kt b/gate/src/test/kotlin/dev/picasso/gate/CompletionCriterionTest.kt
index 97b9f3b..6e395ec 100644
--- a/gate/src/test/kotlin/dev/picasso/gate/CompletionCriterionTest.kt
+++ b/gate/src/test/kotlin/dev/picasso/gate/CompletionCriterionTest.kt
@@ -6,11 +6,11 @@ import kotlin.test.assertEquals
 class CompletionCriterionTest {
 
     @Test
-    fun `주장의 자리가 예순넷이다`() {
+    fun `주장의 자리가 예순다섯이다`() {
         // **세어서 적은 것을 다시 센다.** 자리가 늘거나 줄면 이 수가 먼저 빨개지고,
         // 그때 스펙 §2 를 다시 읽어야 한다.
         val docs = ClaimSurface.documents()
-        assertEquals(64, docs.size, docs.joinToString("\n") { ClaimSurface.relative(it) })
+        assertEquals(65, docs.size, docs.joinToString("\n") { ClaimSurface.relative(it) })
     }
 
     @Test
diff --git a/harness/README.md b/harness/README.md
index 5197779..789ac20 100644
--- a/harness/README.md
+++ b/harness/README.md
@@ -13,6 +13,11 @@
 ## 2. 테스트 스위트 구성
 
 - `Harness` · `ContractSuite`: 에뮬레이터를 기동하고 제어 채널을 통해 가상 시계 및 결함을 조작하는 테스트 기반 런처.
+- `RevisionSuites` · `RevisionTestRunner`: 프로파일 개정판 시험 실행기. registry 에서 시험 요청을 집어 후보 문서를 `Harness` 에 넘기고, 가상 시계·고정 시드 in-process mimic 으로 스위트 3종을 돌려 결과를 HTTP 로 보고한다([ADR 49](../docs/adr/0049-revision-tests-have-a-runner.md)).
+  - `CONTRACT`: 선언 스킬 전부와 `REQUIRED` 선택 필드로 협상 수락, 능력 조회가 `CapabilityProjection.of(문서)` 와 일치, 선언 스킬마다 태스크가 성공하거나 선언한 결함으로 멈춤.
+  - `NEGATIVE`: 프로파일이 못 한다고 적은 것마다 탐침 1개를 보내 정해진 거절 코드(`SKILL_ABSENT`·`PARAMETER_INVALID`·`CANCEL_UNSUPPORTED`·`PAUSE_UNSUPPORTED`·`REQUIRED_OPTIONAL_MISSING`) 확인. 게이트 역검증(`gate/negative/`)과 다른 것.
+  - `DETERMINISM`: `CONTRACT` 의 태스크 시나리오를 같은 시드로 두 번 돌려 발행 전부와 태스크 갱신이 같은지 대조(`session_id`·`event_id` 는 비교 전에 제거).
+  - 검사를 하나도 안 돌린 스위트는 통과가 아니며, 상세 JSON(`checks`·`failures`)이 `revision_test_run.detail` 에 들어간다. 상주 `main` 은 없고 소비자가 `start(interval)` 로 띄운다.
 - **완료 기준별 적합성 테스트**:
   - `A1Test`: 동일한 클라이언트 코드로 상이한 케이퍼빌리티의 2개 기종 제어 검증
   - `ReconstructionTest`: 중간 구독자의 과거 이벤트 스트림 스냅샷 재구성 검증
@@ -32,4 +37,4 @@
 - **컨테이너 환경 의존성 (§15.39)**: 카나리 테스트는 실제 레지스트리 컨테이너를 구동하고, 브로커 테스트는 실제 MQTT 브로커 컨테이너(Docker) 환경을 요구합니다. 검증 신뢰성을 위해 컨테이너 부재 시 임의로 성공 처리하지 않습니다.
 - **실제 하드웨어 기체 연동 한계**: 본 하네스의 검증 성공은 소프트웨어 계약 계층까지의 정합성을 보증하며, 물리 하드웨어 실제 하드웨어에 대한 연동 검증(C-3)은 분리되어 있습니다 (상세 검증 등급: [`docs/verification.md`](../docs/verification.md)).
 
-> 마지막 대조: 2026-10-06 · sha256:65464c9fcccb · 열림: C-3, §15.39
+> 마지막 대조: 2026-10-08 · sha256:01a1f6c79704 · 열림: C-3, §15.39
diff --git a/registry/README.md b/registry/README.md
index d892bbd..2cfa207 100644
--- a/registry/README.md
+++ b/registry/README.md
@@ -24,7 +24,7 @@
 | `ingest/` · `observe/` | 상태 적재 파이프라인 — 핸드셰이크, 하트비트 생존 확인, 태스크 진행률 및 신규 기체 발견 이벤트 수신 |
 | `web/` | REST API 계층. **이원화된 토큰 체계** — 기체용 적재 토큰(`/ingest/*`) 및 운영자용 관리 토큰(`/operations/*`) 분리 |
 
-데이터베이스 스키마는 Flyway 마이그레이션 스크립트를 정본으로 유지합니다 (`src/main/resources/db/migration/`, V1~V15).
+데이터베이스 스키마는 Flyway 마이그레이션 스크립트를 정본으로 유지합니다 (`src/main/resources/db/migration/`, V1~V16).
 
 ---
 
@@ -39,7 +39,7 @@
 
 - **실제 PostgreSQL 16 연동 (Testcontainers)**: 임베디드 H2 대신 실제 PostgreSQL 컨테이너 환경에서 실행되어 테이블 제약 조건, CHECK 제약, 외래키(FK) 무결성을 엄밀히 검증합니다.
 - **실제 HTTP 엔드포인트 통합 테스트**: `TestRestTemplate`과 임의 포트 바인딩을 통해 HTTP 응답 상태 코드, 이원화 토큰 인증 및 JSON 직렬화를 실제 통신 환경에서 검증합니다.
-- `web/*EndpointTest` 다섯이 전체 REST API 표면 계약을 검증하고, `binding/`, `plan/`, `ledger/` 단위 테스트가 비즈니스 규칙을 담당합니다.
+- `web/*EndpointTest` 여섯이 전체 REST API 표면 계약을 검증하고, `binding/`, `plan/`, `ledger/` 단위 테스트가 비즈니스 규칙을 담당합니다.
 - 상위 통합 검증은 `harness` 모듈에서 담당합니다: `LedgerIngestEndToEndTest` (에뮬레이터 경로), `HostIngestEndToEndTest` (어댑터 경로), `OrbitDiscoveryEndToEndTest` (동적 발견 경로).
 
 ---
@@ -50,4 +50,4 @@
 - **원장 적재의 비동기 메시지 브로커 구독기 미구현 (§15.34)**: 상태 발행은 외부 MQTT 브로커로 전달되나, 브로커로부터 이벤트를 읽어 원장에 자동 반영하는 컨슈머가 아직 구현되지 않아 현재는 프로세스 내 직접 적재 방식을 병행합니다.
 - **원장 정합성의 계약 인터페이스 경유 의존성 (§15.10)**: 모든 클라이언트가 본 인터페이스 계약을 통과할 때만 원장의 완전성이 보증됩니다.
 
-> 마지막 대조: 2026-10-07 · sha256:0ec99b3fe140 · 열림: §15.5, §15.10, §15.34, §15.38
+> 마지막 대조: 2026-10-08 · sha256:5957ab3ea644 · 열림: §15.5, §15.10, §15.34, §15.38
````

- [ ] **Step 3: ADR 49**

```markdown
# ADR 49 — 개정판 시험 3종의 뜻을 정하고 실행기를 harness 에 둔다

- 상태: 확정 (2026-10-08)
- 관련: [ADR 9](0009-no-declaration-without-consumer.md) 소비자 존재 원칙, 설계 §8.4 · §3.2 · §12
- 변경 이력: 설계 문서 §15.207

## 맥락

프로파일 개정판은 `DRAFT → VALIDATED → TESTED → ACTIVE` 를 지난다(설계 §8.3). 활성화 조건은 상태가 `TESTED`·`SUPERSEDED` 이고 스위트 3개(`CONTRACT`·`NEGATIVE`·`DETERMINISM`)의 최신 실행이 모두 `PASS` 인 것이다(설계 §8.4 ③). 설계 §8.4 ② 는 «시험 요청 적재 → harness 폴링 인출 → 가상화 계약 검증 스위트 완주 → TESTED» 를 적었다. 설계 §3.2 순환 방지 규칙 1 은 registry 가 harness 를 부르지 않고, harness 가 폴링으로 집어 가서 돌리고 결과를 보고하게 정했다.

그 고리는 지어지지 않았다. 마이그레이션 `V2__testing.sql` 주석이 «폴링 고리(harness 쪽)는 3a-2다» 로 미뤘다. `harness` 의 `Suite` 열거형(`ContractSuite.kt`)을 쓰는 코드가 없었다. 요청을 집는 `TestRequestService.claim` 과 결과를 적는 `BindingService.recordTestRun` 은 시험 소스만 불렀다. 시험 요청에 «끝남» 칸이 없어 집은 요청은 15분 만료 뒤 다시 집혔다. `NEGATIVE`·`DETERMINISM` 이 개정판에서 무엇을 확인하는지 정한 문서가 없었다. 용어집의 «네거티브 테스트» 는 게이트 역검증(`gate/negative/`)이다.

바깥 첫 소비자는 picasso-ops(운영 화면 PoC)다. picasso-ops P2·S1d 설계 스펙(`docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md`, picasso-ops 저장소)이 화면에서 개정판을 시험 요청하고 진짜 실행기가 시험하게 정했다. 2026-10-07 사용자 결정은 사람이 PASS 를 적는 길이 아니라 진짜 실행기다. 실행기는 picasso-ops 의 가짜 현장(`site/`) 프로세스가 띄운다. 소비자가 생겨서 고리를 짓는다(ADR 9).

## 결정

**시험 3종의 뜻을 정한다.**

- `CONTRACT`(설계 §12.1 계약 테스트, §9.7 ③). 선언 스킬 전부와 `REQUIRED` 선택 필드로 협상하면 수락된다. 능력 조회(`GetCapabilities`)가 `CapabilityProjection.of(문서)` 와 같다(§12.2 #10). 선언 스킬마다 새 기체에서 태스크가 수락되고 멈춘다. 멈춘 결과는 성공이거나 프로파일이 선언한 결함으로 멈춘 것(`FAILED`·`RETRIABLE`·`NEEDS_INTERVENTION` 이고 결함 종류가 그 스킬에 선언됨)이어야 한다. 태스크마다 가상 시간 상한은 3600초다.
- `NEGATIVE`(설계 §10.4 ③ 프로토콜 한계 집행, §12.2 #7). 프로파일이 못 한다고 적은 것마다 탐침 1개를 보내 정해진 거절 코드가 오는지 본다. 선언 안 한 카탈로그 스킬은 `SKILL_ABSENT`, 필수 키 누락·선언 범위 밖 숫자·`max_length` 초과·`allowed_values` 밖 ENUM 은 `PARAMETER_INVALID`, `cancel_support: NO` 는 `CANCEL_UNSUPPORTED`, `pause_support: NO` 는 `PAUSE_UNSUPPORTED`, `REQUIRED` 선택 필드 없이 협상은 `REQUIRED_OPTIONAL_MISSING` 이다. `UNKNOWN` 지원은 시도를 허용하므로 탐침이 없다. 필수 키 탐침은 어느 프로파일에나 붙어 검사 0개로 통과하는 일이 없다.
- `DETERMINISM`(설계 §12.1 결정론적 검증). `CONTRACT` 의 태스크 시나리오를 같은 시드로 두 번, 각각 새 `Harness` 에서 돈다. 발행 전부(상태·이벤트·연결)와 태스크 갱신이 같아야 한다. 이벤트의 종류와 순서, 가상 시각, 진행률, 멈춘 상태를 댄다. `session_id`·`event_id` 는 JVM 전역 카운터를 품어 같은 시드로도 실행마다 다르므로 비교 전에 지운다.

**스위트가 검사를 하나도 안 돌렸으면 통과가 아니다.** 상세는 JSON `{"checks": <돌린 검사 수>, "failures": [{"check", "expected", "observed"}]}` 이고 `revision_test_run.detail` 에 그대로 들어간다. 검사 식별자는 `CONTRACT.capabilities`, `NEGATIVE.cancel_unsupported:pick_place` 꼴이다.

**파라미터는 선언에서 만든다.** `MinimalParameters` 가 선언(키·형·범위·길이·허용 값)에서 최소 유효값과 어긴 값을 만든다. 생성기는 기종 이름도 스킬 이름도 보지 않는다.

**실행기는 `harness` 의 운영 코드(`dev.picasso.harness.revision`)에 둔다.** `RevisionTestRunner` 가 요청을 집어 후보 문서를 임시 파일로 써서 기존 `Harness`(파일 경로만 받아 스키마 검사를 거침)에 넘기고, 가상 시계·고정 시드 in-process mimic 으로 3종을 돌려 보고한다. registry 에는 HTTP 로만 닿는다(`HttpTestDesk`, JDK `HttpClient`). `harness` 운영 코드는 여전히 `:registry` 에 의존하지 않고 `:capability` 의존이 하나 는다. picasso 에는 실행기를 상주시키는 `main` 이 없다. 첫 소비자가 프로세스 안에서 `start(interval)` 로 띄운다.

**registry 에 문 3개를 둔다.**

- `POST /operations/profile-revisions/{profileRevisionId}/test-requests`(운영자 토큰, `X-Actor` 필수). 201 새 요청. 200 끝나지 않은 요청이 이미 있음(그 요청, 멱등). 404 없는 개정판. 409 `DRAFT`·`REVOKED`(본문 `status`). 감사 `TEST_REQUEST` 는 새로 만들 때만 남는다.
- `POST /ingest/test-requests/claim`(적재 토큰, 본문 `worker`). 200 요청 id·개정판 id·집은 시각(`claimed_at`, DB 에 적힌 값)·후보 문서(문자열). 204 집을 것 없음. 끝난 요청은 집지 않는다.
- `POST /ingest/test-requests/{requestId}/results`(적재 토큰, 본문 `worker`·`claimed_at`·`results` 3개). 200 보고 뒤 상태. 400 스위트가 셋이 아니거나 모르는 결과·시각 형식 오류. 404 없는 요청. 409 는 본문 `reason` 으로 가른다(`COMPLETED` 이미 끝남, `NOT_CLAIMER` 집은 실행기나 집은 시각이 다름). 실행 3행(요청 id·상세 포함), 요청의 «끝남», 승격이 한 트랜잭션이다.

**스키마와 서비스를 맞춘다.** `V16__test_request_completion.sql` 이 `revision_test_request.completed_at` 칸과 부분 유일 색인 `revision_test_request_one_open`(개정판마다 끝나지 않은 요청 1개)을 더한다. 요청은 개정판 행을 잠그고 진행해 동시 요청의 둘째가 첫째의 요청을 본다. `TestRequestService` 에 `requestTest`(결과 타입 `TestRequested`)·`report`(결과 타입 `ReportOutcome`)를 더하고, `claim` 이 끝난 요청을 빼며 집은 시각을 돌려준다. 옛 `request` 는 `requestTest` 에 위임해 SQL 경로를 하나로 했다. `BindingService` 의 실행 기록과 승격을 `insertRun`·`promoteIfAllPass` 로 갈라 개별 기록(`recordTestRun`)과 보고가 같은 규칙을 지난다.

**실행기의 오류 처리를 정한다.** 문서가 적재(스키마)에서 거절되면 세 스위트를 모두 FAIL(`LOAD`)로 보고한다. 스위트 안의 예외는 그 스위트의 FAIL 이다. 집기에서 무응답·5xx 면 다음 폴링에 다시 집는다. 401 이면 토큰 설정 오류로 로그에 남기고 폴링을 계속한다. 보고의 응답을 못 받으면 같은 보고를 최대 3번 다시 보내고, 다시 보낸 보고가 409 `COMPLETED` 면 앞 보고가 반영된 것으로 본다. 3번 모두 실패하면 포기하고 요청은 만료 뒤 다시 집힌다.

게이트 검사 11(아웃바운드 닫힌 목록)에 `harness/src/main/kotlin/dev/picasso/harness/revision/RevisionTestRunner.kt` 를 더했다. 모듈 의존 그림(`docs/diagrams/components.svg`)에 `harness → capability` 간선을 그렸다.

## 왜 이 모양인가

- 토큰을 둘로 가른다. 요청은 조작 문이다 — 사람이 «이 개정판을 시험하라» 고 적으므로 운영자 토큰이다. 집기·보고는 적재 문이다 — 기계가 관측한 것을 올리므로 적재 토큰이다. 운영자 토큰만 쥔 쪽(운영 화면)은 시험 결과를 적을 길이 없다.
- 보고에 집은 시각까지 댄다. 실행기 이름은 같은 이름으로 다시 뜰 수 있어 이름만으로는 죽은 실행기의 늦은 보고를 못 가른다. 만료가 지났어도 다른 실행기가 다시 집기 전이면 보고를 받는다. 결과는 실제로 돌린 것이고 만료는 막힌 요청을 풀려는 것이다. 집은 시각은 DB 값(마이크로초)을 그대로 돌려준다. 메모리 값(나노초)을 내주면 모든 보고가 거절된다.
- 적재 거절도 보고한다. 보고하지 않으면 같은 요청이 15분마다 다시 집히고 화면에 FAIL 이 끝내 안 보인다.
- «끝남» 칸을 둔다. 칸이 없으면 끝난 요청이 만료 뒤 다시 집힌다. 부분 유일 색인이 개정판마다 열린 요청을 1개로 묶어 요청 문이 멱등해진다.
- 생성기는 선언을 보고 값을 고른다. `ContractSuite` 의 «능력을 보고 값을 고르기 시작하면 그것이 곧 기종 분기» 는 클라이언트의 원칙이다(같은 클라이언트 코드로 이기종, 완료 기준 A-1). 생성기는 클라이언트가 아니라 처음 보는 프로파일의 시험 데이터를 만드는 쪽이다.
- 실행기는 registry 에 HTTP 로만 닿는다. 설계 §3.2 규칙 1 대로 registry 가 harness 를 부르지 않고, 모듈 의존도 생기지 않는다.

## 고르지 않은 것

- 사람이 화면에서 PASS 를 적는 길. 운영자 토큰을 쥔 쪽이 실행 없이 PASS 를 보낼 수 있다. 2026-10-07 사용자 결정은 진짜 실행기다.
- 실행기 결과 보고를 운영자 토큰 문에 두는 길. 운영 화면이 실행 없이 결과를 적을 수 있게 된다.
- 실행기를 registry 안에 두는 길. 설계 §3.2 위반이고, 시험 도구 없이 registry 가 안 뜬다.
- `NEGATIVE` 를 빼고 2종으로 줄이는 길. 설계 §8.4 를 고쳐야 한다.

## 대가

- `harness` 시험 의존에 Spring Boot(starter-web)가 든다. harness 시험 클래스패스에서 registry(Spring)를 띄우면 slf4j 구현 충돌(mimic 쪽 NOP 와 Spring 의 logback)로 기동이 거부된다. 시험이 Spring 의 로깅 초기화만 끈다(`org.springframework.boot.logging.LoggingSystem=none`).
- `harness` 운영 코드의 모듈 의존이 `:capability` 하나 는다.
- 옛 `request` 의 동작이 둘 바뀐다. 열린 요청이 있으면 그 id 를 돌려주고, 없는 개정판·`DRAFT`·`REVOKED` 는 예외다.
- 실행기를 상주시키는 `main` 이 picasso 에 없다. 띄우는 것은 소비자 몫이다.
- 시험 요청의 취소가 없다. 만료만 있다.
- mimic 의 후보 개정판 검증 모드(설계 §10.2 `--registry --profile-revision`)는 짓지 않았다.
- `java.util.Random` 은 이웃한 작은 시드의 첫 난수가 거의 같다. 시드 0~6 으로 humanoid-a `pick_place`(작업 시간 45초, 지터 비율 0.1)를 새 기체에서 돌리면 모두 48초째 성공하고 자취가 같다. 지터를 선언하지 않은 `quadruped-b` 는 시드를 바꿔도 같은 자취다. `DETERMINISM` 의 결함 주입은 먼 시드(987654321)로 넣었다.

## 대는 것

| 주장 | 시험 |
|---|---|
| `humanoid-a` 는 `CONTRACT`·`NEGATIVE`·`DETERMINISM` 을 모두 통과한다 | `RevisionSuitesTest` · `humanoid-a 는 셋 다 통과한다` |
| `quadruped-b` 는 3종을 모두 통과한다 | `RevisionSuitesTest` · `quadruped-b 는 셋 다 통과한다` |
| `NEGATIVE` 는 프로파일이 못 한다고 적은 것마다 탐침 1개를 보낸다 | `RevisionSuitesTest` · `NEGATIVE 는 프로파일이 못 한다고 적은 것마다 탐침을 보낸다` |
| 적재에서 거절된 문서는 세 스위트가 모두 FAIL(`LOAD`)이다 | `RevisionSuitesTest` · `적재에서 거절된 문서는 셋 다 FAIL 이다` |
| 보고의 응답을 못 받으면 다시 보내고, 409 `COMPLETED` 면 앞 보고가 반영된 것으로 본다 | `RevisionTestRunnerTest` · `보고의 응답을 못 받으면 다시 보내고, 다시 보낸 보고가 이미 끝남이면 거기서 멈춘다` |
| 보고는 최대 3번 다시 보내고 포기한다 | `RevisionTestRunnerTest` · `보고를 최대 3번 다시 보내고 포기한다` |
| 요청 → 집기 → 3종 → 보고로 개정판이 `TESTED` 가 된다 | `RevisionRunnerEndToEndTest` · `humanoid-a 와 quadruped-b 가 요청 → 집기 → 3종 → 보고로 TESTED 가 된다` |
| 운영자 토큰으로는 집지 못한다 | `RevisionRunnerEndToEndTest` · `운영자 토큰으로는 집지 못한다` |
| 끝난 요청은 만료가 지나도 다시 집히지 않는다 | `RevisionTestRequestTest` · `끝난 요청은 만료가 지나도 다시 집히지 않는다` |
| 같은 이름으로 다시 집은 요청에 옛 집은 시각의 보고는 `NOT_CLAIMER` 다 | `RevisionTestRequestTest` · `같은 이름으로 다시 집은 요청에 옛 집은 시각의 보고는 받지 않는다` |
| 하나라도 FAIL 이면 `VALIDATED` 에 머문다 | `RevisionTestRequestTest` · `하나라도 FAIL 이면 VALIDATED 에 머문다` |
| 보고의 409 는 본문 `reason` 으로 갈린다 | `TestRequestEndpointTest` · `보고의 409 는 reason 으로 갈린다` |

이 표는 게이트가 이름으로 대조하지 않는다. 이름은 시험과 손으로 맞춘다.

> 마지막 대조: 2026-10-08 · sha256:f56d9885cbfc · 열림: 없음
```

- [ ] **Step 4: 전체 빌드**

Run: `./gradlew build --continue -q`
Expected: XML 기준 1,905개 실패 0(registry 371, harness 208, gate 276 포함). 스파이크 실측 10분 51초. 실패가 `주장의 자리가 …` 면 스탬프 문서 수, `자동화 시험의 수를 대외 문서가 맞게 적는다` 면 시험 수, `구성도가 그린 간선이 …` 면 Step 1 이 빠진 것이다.

- [ ] **Step 5: G1 주입** — Task 5 표의 G1. Expected: XML 에서 `원본 트리는 모든 검사를 통과한다` 하나만 실패. 되돌린 뒤 `git status --short` 에 `Check11OutboundScope.kt` 가 없어야 한다(Task 4 에서 이미 커밋했으므로 되돌림이 빠지면 여기서만 보인다).

- [ ] **Step 6: 커밋과 대조**

```bash
git add tools/diagram-gen/components.mjs docs/diagrams/components.svg docs/diagrams/components.dark.svg README.md docs/architecture.md registry/README.md docs/verification.md CLAUDE.md docs/commissioning.md docs/adr/0049-revision-tests-have-a-runner.md docs/adr/README.md docs/superpowers/specs/2026-09-05-picasso-design.md docs/limits.md docs/glossary.md harness/README.md gate/src/test/kotlin/dev/picasso/gate/CompletionCriterionTest.kt
git commit -q -F - <<'EOF'
docs(adr): ADR 49 와 시험 요청 문서, 구성도, 시험 수

- 작업 묶음 커밋(Task 9 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
bash C:/Users/Eisen/AppData/Local/Temp/p2a-cmp.sh $(git diff --name-only origin/main HEAD)
```
Expected: 31줄 모두 «같음».

### Task 7: picasso-ops 스펙 정정(이미 함)

이 계획과 같은 picasso-ops 커밋에서 스펙 §11 P2a harness 행의 시드 주입 문장을 고쳤다(이웃한 작은 시드는 등가 변이, 먼 시드로 넣음). 구현자는 picasso-ops 를 건드리지 않는다(picasso `CLAUDE.md` 의 저장소 경계).

### Task 8: 새 클론 검증

- [ ] **Step 1:** 워크트리의 커밋을 짧은 경로(`C:/Users/Eisen/AppData/Local/Temp/p2a-clean`)에 `git clone -b feat/revision-test-runner` 로 새로 클론해 `./gradlew build --continue -q` 를 돌린다. Expected: XML 기준 1,905개 실패 0. 커밋되지 않은 파일에 기대는 것이 없음을 본다.

### Task 9: 합치기와 PR

- [ ] **Step 1: 하나로 합치기** — 묶음 커밋 다섯(Task 1·2·3·4·6)을 `git reset --soft origin/main` 으로 합치고, 커밋 메시지는 Fable·Codex 초안을 취합해 heredoc 으로 쓴다. 트리 해시가 합치기 전과 같아야 한다(`git rev-parse HEAD^{tree}` 비교).
- [ ] **Step 2: 푸시와 PR** — 사용자 승인 뒤. PR 본문은 개요 / 주요 변경 사항 / 검증 결과 세 절이며 Fable·Codex 초안 취합, 끝 줄 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. 머지 뒤에는 khala·narrator 에 메인 체크아웃을 당긴다고 예고한 뒤 당긴다.

## 실행 결과

- 수행: 묶음 셋(Task 1·2, 3·4, 5·6)을 하위 에이전트가 수행, 묶음마다 커밋된 파일을 스크래치 코드와 바이트 대조해 31개 모두 동일, 최종 트리 해시도 동일(`b8a7f6b`)
- 결함 주입: 19건(registry 9, harness·mimic 9, 게이트 1) 모두 지정 시험이 탐지, `NEGATIVE`·`CONTRACT`·`DETERMINISM` 주입은 실패 메시지 앞머리까지 확인
- 전체 빌드: 새 클론에서 시험 XML 1,905개, 실패 0(registry 371, harness 208, gate 276)
- 병합: 묶음 커밋 다섯을 하나로 합침(`24c24c3`, 트리 동일), picasso PR #80 의 CI `build` job 초록(8분 47초), 2026-10-08 03:27 KST 머지(머지 커밋 `2370ed3`)
- 걸린 것: 하위 에이전트가 같은 워크트리에서 Gradle 을 겹쳐 띄워 한 번 깨짐(다시 돌려 통과), 형식 훅이 커밋 메시지의 겹화살괄호를 막음
- 다음: 같은 스펙의 P2b 계획
