# P2b 리비전·바인딩 조작 문 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** picasso registry 가 기동할 때 계약 스킬 종류를 채우고, 리비전 제출·목록·활성화와 기체 바인딩을 조작 문으로 열어 PR 로 올린다. 바인딩이 없는 기체·퇴역 기체·같은 조합 재요청을 막고, `/diag/bindings` 행에 빌드 id 와 명칭 기록·보고 칸을, mimic CLI 에 기체가 아는 명칭을 넣을 자리를 둔다.

**Architecture:** 서비스는 P1·P2a 와 같은 방식으로 결과를 값으로 가르는 새 메서드를 더한다(`RevisionService.submitDocument` → `Submitted`, `BindingService.activateRevision` → `Activation`, `BindingService.bindRobot` → `Binding`). 옛 메서드와 그 시험은 그대로 두고, 옛 `activate` 만 새 메서드에 위임한다(동작 변화 없음). 옛 `submit`·`bind` 는 저장(`store`)과 바인딩 기록(`rebind`)만 새 메서드와 공유한다. 새 `SkillTypeCatalog` 가 기동 동기화(`syncAtBoot`)와 스킬 종류 조회를, 새 `RevisionListing` 이 리비전 목록을 든다. 새 컨트롤러 둘(`RevisionOperationsController`·`BindingOperationsController`)이 `/operations` 아래 문 5개를 낸다. 기동 동기화는 `RegistryApplication` 의 `ApplicationRunner` 빈이 부른다.

**Tech Stack:** Kotlin 2.4.20, Spring Boot 3.4.0(MVC), PostgreSQL + Flyway(Testcontainers, registry `testFixtures` 의 `PostgresSupport`), JUnit5 + kotlin.test, `TestRestTemplate`, gRPC(mimic CLI 시험).

**근거 스펙:** picasso-ops `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` §6(P2b), §3(완료 판정), §11(시험). 2차 스펙 검토 권고 중 P2b 몫(제출 본문을 못 읽으면 registry 400, 동시 요청에서 진 쪽은 500 이 아니라 기존 것, 바인딩 멱등 검사의 순서, 멱등 200 의 감사, 동시 첫 바인딩 시험, 기존 시험의 동기화 영향)을 이 계획이 정한다.

**스펙이 계획에 맡긴 것과 이 계획이 정한 것:**
- 기동 동기화와 기존 API 표면 시험(스펙 §6.1): 기존 API 표면 시험은 스프링 컨텍스트가 뜬 뒤 시험마다 스키마를 지우고(`PostgresSupport.reset()`) 손으로 동기화한다. 그래서 기동 동기화는 그 시험에 영향이 없다. 다만 시험 JVM 에서 컨텍스트가 처음 뜰 때 스키마가 아직 없을 수 있으므로, 스키마(`skill_type` 표)가 없으면 경고를 남기고 건너뛴다. 계약 기술자를 못 읽으면 스펙대로 기동을 거부한다. 스파이크에서 기존 시험 935개(registry 371, mimic 356, harness 208)가 고치지 않고 통과했다.
- 바인딩 멱등 검사의 순서(검토 권고 5): 같은 조합 검사는 **모든 검사 뒤**다. 묶인 뒤 리비전이 대체됐으면 같은 조합이어도 409 `REVISION_NOT_ACTIVE` 다. 멱등 200 은 감사를 남기지 않는다(제출·활성화·바인딩 모두).
- 동시 요청(검토 권고 4·9): 바인딩은 기체 행을, 제출과 활성화는 기종(`capability_profile`) 행을 `FOR UPDATE` 로 잠근다.
- 제출 본문(검토 권고 3): 프로파일 문서로 못 읽으면 registry 가 400 을 낸다.
- 바인딩 본문에 두 id 중 하나가 빠지면 400 이다(스펙 표에 없던 응답).
- 옛 메서드(스펙 §6.3 «그대로 둔다»): 옛 `activate` 는 새 `activateRevision` 에 위임한다. SQL 경로를 하나로 두려는 것이며 동작은 바뀌지 않는다(이미 `ACTIVE` 면 전처럼 거부). 위임으로 `ChangePlanService` 의 활성화 경로도 기종 행을 잠그게 되며, 바깥 트랜잭션이 그 행을 잠그지 않아 교착은 없다. 옛 `submit`·`bind` 는 위임하지 않고 저장(`store`)과 바인딩 기록(`rebind`)만 공유한다.
- `/diag/bindings` 행의 새 칸은 기본값이 없다. 생성 지점이 하나뿐이고, 빈 문자열이 «모른다» 로 새는 것을 같은 파일이 경계한다.

**작업 위치 규칙(필수):**
- picasso 메인 체크아웃(`C:\Users\Eisen\Desktop\Labs\[projects] picasso`)에서 docs/ 를 고치지 않는다. khala 가 그 작업 트리의 docs/ 를 매시간 코퍼스로 읽는다. 모든 작업은 저장소 밖 워크트리 `C:/Users/Eisen/Desktop/Labs/picasso-wt/p2b-revision-binding-rest` 에서 한다.
- `./gradlew --stop` 금지(데몬 풀이 사용자 체크아웃과 공유된다). 같은 워크트리에서 Gradle 을 겹쳐 돌리지 않는다(시험 결과의 binary 가 깨진다). Bash 도구의 시간 한도(600초)를 넘는 빌드는 백그라운드로 돌리고 끝났다는 알림을 받은 뒤 다음 Gradle 을 돌린다.
- `git add -A` 금지. 파일을 이름으로 더한다.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다. Windows 의 python 으로 XML 을 셀 때는 `C:/...` 경로를 쓴다(`/c/...` 는 glob 이 0 을 돌려준다).
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. 커밋 메시지는 heredoc(`git commit -F - <<'EOF'`)으로만 쓴다. 형식 훅(`.claude/hooks/check-commit-pr-format.py`)이 `-F 파일`, `-m` 두 번, 제목이 `type(scope): 명사구` 가 아닌 것, 겹화살괄호를 막는다.
- 이 계획의 코드와 문서는 스크래치 스파이크(`C:/Users/Eisen/AppData/Local/Temp/p2b`, 브랜치 `feat/revision-binding-rest`, HEAD `b3f9117`)에서 시험과 결함 주입을 다 돌린 것이다. 묶음이 끝날 때마다 커밋된 파일을 스파이크와 기계 대조한다(Task 0 Step 4 의 `p2b-cmp.sh`).
- 실행 방식: 묶음(Task 1·2 / Task 3·5)마다 구현 하위 에이전트 1명(`model: "sonnet"`), 결함 주입(Task 4)과 검토는 컨트롤러. 새 파일은 아래 내용 그대로 쓰고(같은 폴더 기존 파일처럼 CRLF), 기존 파일은 아래 패치를 워크트리 밖 `C:/Users/Eisen/AppData/Local/Temp/p2b-patches/` 에 저장해 `git apply` 로 넣는다. 패치를 워크트리 안에 두면 `git status` 에 남고 실수로 커밋될 수 있다.

---

## Chunk 1: registry

### Task 0: 워크트리, 기준선, 대조 도구

**Files:** 없음(환경)

- [ ] **Step 1: 워크트리 만들기**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso"
git fetch origin -q
git worktree add "C:/Users/Eisen/Desktop/Labs/picasso-wt/p2b-revision-binding-rest" -b feat/revision-binding-rest origin/main
git -C "C:/Users/Eisen/Desktop/Labs/picasso-wt/p2b-revision-binding-rest" branch --unset-upstream
```
Expected: `origin/main` 이 `2370ed3`. 다르면 멈추고 보고한다(아래 패치의 기준이 `2370ed3` 다). **이후 모든 명령은 워크트리 `C:/Users/Eisen/Desktop/Labs/picasso-wt/p2b-revision-binding-rest` 에서 돈다.** 하위 에이전트의 Bash 는 호출마다 작업 디렉터리가 메인 체크아웃으로 돌아가므로 명령마다 `cd` 를 붙이거나 `git -C` 를 쓴다. 메인 체크아웃에서 `git apply`·`./gradlew`·`git commit` 이 돌면 안 된다.

- [ ] **Step 2: Docker 확인**

Run: `docker info --format '{{.ServerVersion}}'`
Expected: 버전 문자열. 안 나오면 멈춘다(registry 시험이 Testcontainers 를 쓴다).

- [ ] **Step 3: 기준선 시험**

Run: `./gradlew :registry:test :mimic:test :gate:test --continue -q`
Expected: XML 기준 실패 0(registry 371, mimic 356, gate 276). 실패가 있으면 멈추고 보고한다.

- [ ] **Step 4: 대조 도구**

`C:/Users/Eisen/AppData/Local/Temp/p2b-cmp.sh` 를 만든다.

```bash
#!/usr/bin/env bash
# usage: p2b-cmp.sh 경로...  워크트리의 커밋된 파일과 스파이크 HEAD 의 파일을 줄바꿈을 뺀 채 바이트 대조한다.
W="C:/Users/Eisen/Desktop/Labs/picasso-wt/p2b-revision-binding-rest"
S="C:/Users/Eisen/AppData/Local/Temp/p2b"
bad=0
for p in "$@"; do
  if cmp -s <(git -C "$W" show "HEAD:$p" | tr -d '\r') <(git -C "$S" show "HEAD:$p" | tr -d '\r'); then echo "같음 $p"; else echo "다름 $p"; bad=1; fi
done
exit $bad
```

### Task 1: 결과 타입, 기동 동기화, 리비전 목록

**Files:**
- Create: `registry/src/main/kotlin/dev/picasso/registry/revision/SkillTypeCatalog.kt`
- Create: `registry/src/main/kotlin/dev/picasso/registry/revision/RevisionListing.kt`
- Modify: `registry/src/main/kotlin/dev/picasso/registry/revision/RevisionService.kt`(`Submitted`, `submitDocument`, 공유 `store`, 주석 정정)
- Modify: `registry/src/main/kotlin/dev/picasso/registry/binding/BindingService.kt`(`Activation`, `Binding`, `activateRevision`, `bindRobot`, 공유 `rebind`, 옛 `activate` 위임)
- Test: `registry/src/test/kotlin/dev/picasso/registry/RevisionOperationsTest.kt`

- [ ] **Step 1: 실패하는 시험 쓰기** — 아래 내용 그대로.

```kotlin
package dev.picasso.registry

import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.Activation
import dev.picasso.registry.binding.Binding
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.binding.SiteNameRegistration
import dev.picasso.registry.binding.SiteNameStatus
import dev.picasso.registry.revision.BootSync
import dev.picasso.registry.revision.RevisionListing
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.RevisionStatus
import dev.picasso.registry.revision.SkillTypeCatalog
import dev.picasso.registry.revision.Submitted
import dev.picasso.registry.store.Db
import dev.picasso.registry.testing.TestRequestService
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 개정판·바인딩의 조작 문이 쓰는 결과 타입(picasso-ops P2·S1d 스펙 §6.2·§6.3). **재전송은 같은 답이고, 거절은 이유로
 * 갈린다.**
 *
 * 제출·활성화·바인딩이 같은 요청의 재전송에 멱등인지, 바인딩이 없는 기체·퇴역 기체·같은 조합을 막는지, 동시 요청이 500
 * 대신 차례로 처리되는지 본다. 카탈로그 기동 동기화도 여기서 본다.
 */
class RevisionOperationsTest {

    private lateinit var db: Db
    private lateinit var revisions: RevisionService
    private lateinit var bindings: BindingService
    private var build: Long = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        SkillTypeCatalog(db).syncAtBoot(Fixtures.descriptor(), CONTRACT_SEMVER)
        revisions = RevisionService(db, Fixtures.validator())
        bindings = BindingService(db)
        build = version(CONTRACT_SEMVER)
    }

    private fun version(contractSemver: String, number: String = "1.0.0"): Long {
        val adapters = AdapterService(db)
        val adapterId = adapters.registerAdapter("acme", "drv-$number", "op")
        val v = adapters.registerVersion(adapterId, number, contractSemver, "op")
        assertIs<RegisterOutcome.Registered>(v)
        return v.adapterVersionId
    }

    private fun robot(id: String) =
        PostgresSupport.execute("INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('$id','line-a','sn-$id')")

    private fun created(document: String = Fixtures.good()): Long =
        assertIs<Submitted.Created>(revisions.submitDocument(document, "engineer/kim")).profileRevisionId

    private fun passed(revision: Long) =
        BindingService.SUITE_NAMES.forEach { bindings.recordTestRun(revision, it, "PASS", "harness") }

    private fun active(document: String = Fixtures.good()): Long {
        val revision = created(document)
        passed(revision)
        assertIs<Activation.Activated>(bindings.activateRevision(revision, "engineer/kim"))
        return revision
    }

    private fun auditCount(operation: String): Int =
        PostgresSupport.queryOne("SELECT count(*) FROM audit_log WHERE operation = '$operation'") { it.getInt(1) }

    // ── 카탈로그

    @Test
    fun `기동 동기화 뒤 스킬 종류가 계약과 같고 감사 행위자는 registry 다`() {
        val names = SkillTypeCatalog(db).list().map { it.name }.toSet()
        val contract = dev.picasso.gate.model.ContractIndex.from(Fixtures.descriptor()).skillTypes().toSet()

        assertEquals(contract, names)
        assertEquals(
            listOf("registry"),
            PostgresSupport.queryAll("SELECT actor FROM audit_log WHERE operation = 'SKILL_TYPE_SYNC'") { it.getString(1) },
        )
        val keys = SkillTypeCatalog(db).list().associate { it.name to it.siteReferenceKeys }
        assertEquals(listOf("location"), keys["navigate_to"])
        assertEquals(listOf("destination"), keys["pick_place"])
    }

    @Test
    fun `기술자를 못 읽으면 기동을 거부하고 스키마가 없으면 건너뛴다`() {
        assertFailsWith<IllegalStateException> { SkillTypeCatalog(db).syncAtBoot(null, CONTRACT_SEMVER) }

        PostgresSupport.execute("DROP TABLE skill_type CASCADE")
        assertEquals(BootSync.NoSchema, SkillTypeCatalog(db).syncAtBoot(Fixtures.descriptor(), CONTRACT_SEMVER))
    }

    // ── 제출

    @Test
    fun `같은 문서의 재제출은 같은 개정판이고 감사는 한 번이다`() {
        val first = revisions.submitDocument(Fixtures.good(), "engineer/kim")
        val again = revisions.submitDocument(Fixtures.good(), "engineer/kim")

        assertIs<Submitted.Created>(first)
        assertEquals(RevisionStatus.VALIDATED, first.status, "${first.reasons}")
        assertEquals(Submitted.Existing(first.profileRevisionId, 1, RevisionStatus.VALIDATED, emptyList()), again)
        assertEquals(1, auditCount("PROFILE_REVISION_SUBMIT"))
    }

    @Test
    fun `같은 번호의 다른 문서와 낮은 번호는 단조 위반이고 읽을 수 없는 문서는 따로 갈린다`() {
        created(Fixtures.good(revision = 2))

        assertEquals(Submitted.NotMonotonic(2, 2), revisions.submitDocument(Fixtures.good(revision = 2).replace("\"seconds\": 20", "\"seconds\": 21"), "op"))
        assertEquals(Submitted.NotMonotonic(1, 2), revisions.submitDocument(Fixtures.good(revision = 1), "op"))
        assertIs<Submitted.Unreadable>(revisions.submitDocument("{not json", "op"))
    }

    @Test
    fun `검증에 실패한 문서도 저장되고 DRAFT 와 사유를 돌려준다`() {
        val outcome = assertIs<Submitted.Created>(revisions.submitDocument(Fixtures.badErrorType(), "op"))

        assertEquals(RevisionStatus.DRAFT, outcome.status)
        assertTrue(outcome.reasons.isNotEmpty())
        assertEquals(
            Submitted.Existing(outcome.profileRevisionId, 1, RevisionStatus.DRAFT, outcome.reasons),
            revisions.submitDocument(Fixtures.badErrorType(), "op"),
            "재제출이 사유를 잃었다",
        )
    }

    /**
     * 기종이 **이미 있는** 때를 본다. 기종의 첫 제출이면 기종 행의 `INSERT … ON CONFLICT DO NOTHING` 이 유일 색인에서
     * 둘째를 기다리게 해 잠금 없이도 차례가 지켜진다 — 그 경우만 보면 잠금을 지워도 초록이다(결함 주입으로 확인).
     */
    @Test
    fun `같은 문서가 동시에 두 번 와도 하나는 만들고 하나는 그것을 돌려준다`() {
        created(Fixtures.good(revision = 1))

        val outcomes = concurrently(2) { revisions.submitDocument(Fixtures.good(revision = 2), "op") }

        assertEquals(1, outcomes.count { it is Submitted.Created }, "$outcomes")
        assertEquals(1, outcomes.count { it is Submitted.Existing }, "$outcomes")
    }

    // ── 활성화

    @Test
    fun `활성화는 옛 활성을 내리고 다시 누르면 아무것도 바꾸지 않는다`() {
        val old = active()
        val next = created(Fixtures.good(revision = 2))
        passed(next)

        assertEquals(Activation.Activated(old), bindings.activateRevision(next, "op"))
        assertEquals(Activation.AlreadyActive, bindings.activateRevision(next, "op"))
        assertEquals(2, auditCount("PROFILE_REVISION_ACTIVATE"))
    }

    @Test
    fun `활성화 거절은 상태와 스위트별 최신 결과를 싣고 없는 개정판은 따로다`() {
        val revision = created()
        bindings.recordTestRun(revision, "CONTRACT", "PASS", "harness")
        bindings.recordTestRun(revision, "NEGATIVE", "FAIL", "harness")

        assertEquals(
            Activation.Refused(RevisionStatus.VALIDATED, mapOf("CONTRACT" to "PASS", "NEGATIVE" to "FAIL")),
            bindings.activateRevision(revision, "op"),
        )
        assertEquals(Activation.Unknown, bindings.activateRevision(999_999, "op"))
    }

    // ── 바인딩

    @Test
    fun `없는 기체·퇴역 기체·없는 개정판·없는 빌드를 그 순서로 가른다`() {
        val revision = active()
        assertEquals(Binding.UnknownRobot, bindings.bindRobot("ghost", 999_999, 999_999, "op"))

        robot("r1")
        PostgresSupport.execute("UPDATE robot SET retired_at = now(), retired_by = 'op', retired_reason = 'sold' WHERE robot_id = 'r1'")
        assertEquals(Binding.RobotRetired, bindings.bindRobot("r1", 999_999, 999_999, "op"))

        robot("r2")
        assertEquals(Binding.UnknownRevision, bindings.bindRobot("r2", 999_999, 999_999, "op"))
        assertEquals(Binding.UnknownBuild, bindings.bindRobot("r2", 999_999, revision, "op"))
    }

    @Test
    fun `활성이 아닌 개정판과 낮은 계약의 빌드를 거절한다`() {
        robot("r1")
        val validated = created()
        assertEquals(Binding.RevisionNotActive(RevisionStatus.VALIDATED), bindings.bindRobot("r1", build, validated, "op"))

        passed(validated)
        bindings.activateRevision(validated, "op")
        val old = version("0.0.1", number = "0.0.1")
        val refused = assertIs<Binding.ContractTooOld>(bindings.bindRobot("r1", old, validated, "op"))
        assertEquals("0.0.1", refused.contractSemver)
        assertTrue(refused.tooNew.isNotEmpty())
    }

    @Test
    fun `같은 조합을 다시 묶으면 같은 행이고 사이트 명칭 기록이 남는다`() {
        robot("r1")
        val revision = active()
        val first = assertIs<Binding.Bound>(bindings.bindRobot("r1", build, revision, "op"))
        SiteNameRegistration(db).record("r1", "engineer/kim")

        assertEquals(Binding.AlreadyBound(first.bindingId), bindings.bindRobot("r1", build, revision, "op"))
        assertTrue(SiteNameRegistration(db).statusOf("r1") != SiteNameStatus.UNREGISTERED, "재바인딩이 명칭 기록을 지웠다")
        assertEquals(1, auditCount("ROBOT_BIND"))
    }

    @Test
    fun `대체된 개정판의 같은 조합은 이미 됨이 아니라 활성 아님이다`() {
        robot("r1")
        val old = active()
        bindings.bindRobot("r1", build, old, "op")
        val next = created(Fixtures.good(revision = 2))
        passed(next)
        bindings.activateRevision(next, "op")

        assertEquals(Binding.RevisionNotActive(RevisionStatus.SUPERSEDED), bindings.bindRobot("r1", build, old, "op"))
        val moved = assertIs<Binding.Bound>(bindings.bindRobot("r1", build, next, "op"))
        assertTrue(moved.unbound != null, "옛 바인딩을 풀지 않았다")
    }

    @Test
    fun `같은 기체의 동시 첫 바인딩은 하나만 남고 500 이 나지 않는다`() {
        robot("r1")
        val revision = active()

        val outcomes = concurrently(2) { bindings.bindRobot("r1", build, revision, "op") }

        assertEquals(1, outcomes.count { it is Binding.Bound }, "$outcomes")
        assertEquals(1, outcomes.count { it is Binding.AlreadyBound }, "$outcomes")
        assertEquals(1, PostgresSupport.queryOne("SELECT count(*) FROM robot_binding WHERE unbound_at IS NULL") { it.getInt(1) })
    }

    // ── 목록

    @Test
    fun `목록은 스위트별 최신 결과와 최신 시험 요청을 함께 싣는다`() {
        val revision = created()
        bindings.recordTestRun(revision, "CONTRACT", "FAIL", "harness")
        bindings.recordTestRun(revision, "CONTRACT", "PASS", "harness")
        val requestId = TestRequestService(db).request(revision, "engineer/kim")

        val row = RevisionListing(db).list().single()

        assertEquals("fixture" to "minimal", row.vendor to row.model)
        assertEquals(RevisionStatus.VALIDATED, row.status)
        assertEquals(mapOf("CONTRACT" to "PASS"), row.suites.mapValues { it.value.result })
        assertEquals(requestId, row.latestRequest?.requestId)
        assertNull(row.latestRequest?.completedAt)
        assertEquals("engineer/kim", row.createdBy)
    }

    private fun <T> concurrently(n: Int, body: () -> T): List<T> {
        val pool = Executors.newFixedThreadPool(n)
        try {
            val barrier = CyclicBarrier(n)
            return (1..n).map { pool.submit(Callable { barrier.await(); body() }) }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        val CONTRACT_SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :registry:compileTestKotlin -q`
Expected: 컴파일 실패(`Submitted`, `Activation`, `Binding`, `SkillTypeCatalog`, `RevisionListing`, `BootSync` 를 모름).

- [ ] **Step 3: `SkillTypeCatalog`**

```kotlin
package dev.picasso.registry.revision

import dev.picasso.registry.store.Db

/** 스킬 종류 한 행. [siteReferenceKeys] 는 계약이 사이트 명칭으로 표시한 파라미터다(ADR 35). */
data class SkillTypeRow(
    val name: String,
    val major: Int,
    val introducedInSemver: String,
    val siteReferenceKeys: List<String>,
)

/** 기동 동기화의 결과. */
sealed interface BootSync {
    /** @param inserted 새로 들어온 스킬 종류 수 */
    data class Synced(val inserted: Int) : BootSync

    /** 스키마가 없다. 마이그레이션은 런처의 몫이라 여기서 돌리지 않는다. */
    data object NoSchema : BootSync
}

/**
 * 계약이 소유한 스킬 종류(§8.1). 기동 동기화와 조회를 든다.
 *
 * ## 기동 때 동기화한다(설계 §8.3 ④)
 *
 * [SkillTypeSync] 를 부르는 곳이 시험뿐이어서 운영 registry 의 `skill_type` 이 비어 있었다. 비면 개정판 제출이 선언
 * 스킬을 조용히 건너뛰고([RevisionService]), 사이트 명칭 요구 집합도 바인딩의 계약 semver 검사도 비교할 것이 없다.
 *
 * 계약 기술자를 못 읽으면 **기동을 거부한다**. 빈 카탈로그로 뜨는 것이 바로 위의 상태다. 스키마가 없으면 건너뛴다 —
 * 그 registry 는 어느 조작도 못 하므로 조용히 건너뛸 스킬도 없다.
 */
class SkillTypeCatalog(private val db: Db) {

    fun syncAtBoot(descriptor: ByteArray?, contractSemver: String): BootSync {
        checkNotNull(descriptor) { "계약 기술자(/picasso.desc)를 읽지 못했다 — 빈 카탈로그로 뜨면 제출이 스킬을 조용히 건너뛴다" }
        if (!hasSchema()) return BootSync.NoSchema
        return BootSync.Synced(SkillTypeSync(db).sync(descriptor, contractSemver, ACTOR))
    }

    fun list(): List<SkillTypeRow> = db.open().use { c ->
        c.prepareStatement(
            """
            SELECT t.name, t.major, t.introduced_in_semver,
                   COALESCE(array_agg(p.key ORDER BY p.key) FILTER (WHERE p.site_reference), '{}')
            FROM skill_type t
            LEFT JOIN skill_type_param p ON p.skill_type_id = t.skill_type_id
            GROUP BY t.skill_type_id, t.name, t.major, t.introduced_in_semver
            ORDER BY t.name, t.major
            """.trimIndent(),
        ).use { s ->
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        @Suppress("UNCHECKED_CAST")
                        val keys = (rs.getArray(4).array as Array<String>).toList()
                        add(SkillTypeRow(rs.getString(1), rs.getInt(2), rs.getString(3), keys))
                    }
                }
            }
        }
    }

    private fun hasSchema(): Boolean = db.open().use { c ->
        c.prepareStatement("SELECT to_regclass('skill_type') IS NOT NULL").use { s ->
            s.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
    }

    companion object {
        /** 기동 동기화의 감사 행위자. 사람이 아니라 registry 자신이다. */
        const val ACTOR = "registry"
    }
}
```

- [ ] **Step 4: `RevisionListing`**

```kotlin
package dev.picasso.registry.revision

import dev.picasso.registry.store.Db
import java.sql.ResultSet
import java.time.Instant

/** 스위트 하나의 최신 실행. [detail] 은 실행기가 낸 JSON 문자열이다. */
data class SuiteResult(val result: String, val ranAt: Instant, val ranBy: String, val detail: String?)

/** 개정판의 최신 시험 요청 1건. 끝났든 아니든 가장 늦게 들어온 요청이다. */
data class TestRequestRow(
    val requestId: Long,
    val requestedBy: String,
    val requestedAt: Instant,
    val claimedBy: String?,
    val claimedAt: Instant?,
    val claimExpiresAt: Instant?,
    val completedAt: Instant?,
)

/** 개정판 목록의 한 행. */
data class RevisionRow(
    val profileRevisionId: Long,
    val vendor: String,
    val model: String,
    val revision: Int,
    val status: RevisionStatus,
    val reasons: List<String>,
    val documentHash: String,
    val createdBy: String,
    val createdAt: Instant,
    val activatedBy: String?,
    val activatedAt: Instant?,
    /** 스위트 이름 → 최신 실행. 아직 돈 적이 없는 스위트는 빠진다. */
    val suites: Map<String, SuiteResult>,
    val latestRequest: TestRequestRow?,
)

/**
 * 개정판 목록(picasso-ops P2·S1d 스펙 §6.2). **화면이 개정판 하나의 처지를 한 번에 보게 한다** — 상태, 스위트마다의
 * 최신 결과, 시험 요청이 어디까지 왔는지. 따로 물으면 화면이 세 답을 조인하고, 그 사이에 실행기가 보고하면 세 반쪽이
 * 다른 시점을 말한다.
 */
class RevisionListing(private val db: Db) {

    fun list(): List<RevisionRow> = db.transaction { c ->
        val suites = mutableMapOf<Long, MutableMap<String, SuiteResult>>()
        c.prepareStatement(
            """
            SELECT DISTINCT ON (profile_revision_id, suite) profile_revision_id, suite, result, ran_at, ran_by, detail::text
            FROM revision_test_run ORDER BY profile_revision_id, suite, ran_at DESC, run_id DESC
            """.trimIndent(),
        ).use { s ->
            s.executeQuery().use { rs ->
                while (rs.next()) {
                    suites.getOrPut(rs.getLong(1)) { mutableMapOf() }[rs.getString(2)] =
                        SuiteResult(rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getString(6))
                }
            }
        }

        val requests = mutableMapOf<Long, TestRequestRow>()
        c.prepareStatement(
            """
            SELECT DISTINCT ON (profile_revision_id) profile_revision_id, request_id, requested_by, requested_at,
                   claimed_by, claimed_at, claim_expires_at, completed_at
            FROM revision_test_request ORDER BY profile_revision_id, requested_at DESC, request_id DESC
            """.trimIndent(),
        ).use { s ->
            s.executeQuery().use { rs ->
                while (rs.next()) {
                    requests[rs.getLong(1)] = TestRequestRow(
                        requestId = rs.getLong(2),
                        requestedBy = rs.getString(3),
                        requestedAt = rs.getTimestamp(4).toInstant(),
                        claimedBy = rs.getString(5),
                        claimedAt = rs.instant(6),
                        claimExpiresAt = rs.instant(7),
                        completedAt = rs.instant(8),
                    )
                }
            }
        }

        c.prepareStatement(
            """
            SELECT r.profile_revision_id, p.vendor, p.model, r.revision, r.status, r.validation_detail::text,
                   r.document_hash, r.created_by, r.created_at, r.activated_by, r.activated_at
            FROM profile_revision r JOIN capability_profile p ON p.profile_id = r.profile_id
            ORDER BY p.vendor, p.model, r.revision
            """.trimIndent(),
        ).use { s ->
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getLong(1)
                        add(
                            RevisionRow(
                                profileRevisionId = id,
                                vendor = rs.getString(2),
                                model = rs.getString(3),
                                revision = rs.getInt(4),
                                status = RevisionStatus.valueOf(rs.getString(5)),
                                reasons = rs.getString(6)
                                    ?.let { MAPPER.readTree(it)["reasons"]?.map { r -> r.asText() } } ?: emptyList(),
                                documentHash = rs.getString(7),
                                createdBy = rs.getString(8),
                                createdAt = rs.getTimestamp(9).toInstant(),
                                activatedBy = rs.getString(10),
                                activatedAt = rs.instant(11),
                                suites = suites[id].orEmpty(),
                                latestRequest = requests[id],
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun ResultSet.instant(column: Int): Instant? = getTimestamp(column)?.toInstant()

    private companion object {
        val MAPPER = com.fasterxml.jackson.databind.ObjectMapper()
    }
}
```

- [ ] **Step 5: 서비스 패치** — 아래를 `C:/Users/Eisen/AppData/Local/Temp/p2b-patches/p2b-1.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/p2b-patches/p2b-1.patch`.

```diff
diff --git a/registry/src/main/kotlin/dev/picasso/registry/binding/BindingService.kt b/registry/src/main/kotlin/dev/picasso/registry/binding/BindingService.kt
index 1a11145..e5a0e47 100644
--- a/registry/src/main/kotlin/dev/picasso/registry/binding/BindingService.kt
+++ b/registry/src/main/kotlin/dev/picasso/registry/binding/BindingService.kt
@@ -20,6 +20,46 @@ sealed interface BindOutcome {
     data class Refused(val detail: String) : BindOutcome
 }
 
+/** 조작 문의 활성화 결과(picasso-ops P2·S1d 스펙 §6.2). [ActivateOutcome] 과 달리 거절을 값으로 가른다. */
+sealed interface Activation {
+    /** @param superseded 내려간 옛 활성 개정판. 없으면 널. */
+    data class Activated(val superseded: Long?) : Activation
+
+    /** 이미 `ACTIVE` 다. 아무것도 바꾸지 않았다. */
+    data object AlreadyActive : Activation
+
+    /** 상태가 활성화할 수 없거나 세 스위트의 최신 결과가 모두 `PASS` 가 아니다. */
+    data class Refused(val status: RevisionStatus, val latest: Map<String, String>) : Activation
+
+    data object Unknown : Activation
+}
+
+/**
+ * 조작 문의 바인딩 결과(스펙 §6.2). [BindOutcome] 은 사유 문자열 하나라 표면이 거절을 가를 수 없었다.
+ *
+ * 순서는 검사 순서다 — 기체 있음, 퇴역 아님, 개정판 있음, 빌드 있음, 개정판 활성, 계약 semver.
+ */
+sealed interface Binding {
+    /** @param unbound 해제한 이전 바인딩. 없으면 널. */
+    data class Bound(val bindingId: Long, val unbound: Long?) : Binding
+
+    /** 같은 조합이 이미 활성이다. 행과 사이트 명칭 기록을 그대로 두었다. */
+    data class AlreadyBound(val bindingId: Long) : Binding
+
+    data object UnknownRobot : Binding
+
+    data object RobotRetired : Binding
+
+    data object UnknownRevision : Binding
+
+    data object UnknownBuild : Binding
+
+    data class RevisionNotActive(val status: RevisionStatus) : Binding
+
+    /** @param tooNew 빌드의 계약보다 새 스킬마다 `(이름, 처음 들어온 계약 semver)`. */
+    data class ContractTooOld(val contractSemver: String, val tooNew: List<Pair<String, String>>) : Binding
+}
+
 /**
  * §8.4 ③의 활성화와 §9.1의 바인딩.
  *
@@ -104,21 +144,50 @@ class BindingService(private val db: Db) {
         return promoted
     }
 
-    fun activate(profileRevisionId: Long, actor: String): ActivateOutcome = db.transaction { c ->
-        val status = statusOf(c, profileRevisionId)
-            ?: return@transaction ActivateOutcome.Refused("없는 개정판이다: $profileRevisionId")
-
-        if (status !in ACTIVATABLE) {
-            return@transaction ActivateOutcome.Refused(
-                "활성화할 수 있는 상태가 아니다: $status (가능: $ACTIVATABLE)",
+    /**
+     * [activateRevision] 에 위임한다. SQL 경로를 하나로 두려는 것이며 바뀐 동작은 없다 — 이미 `ACTIVE` 인 개정판은
+     * 전처럼 «활성화할 수 있는 상태가 아니다» 로 거부한다.
+     */
+    fun activate(profileRevisionId: Long, actor: String): ActivateOutcome =
+        when (val outcome = activateRevision(profileRevisionId, actor)) {
+            is Activation.Activated -> ActivateOutcome.Activated(outcome.superseded)
+            Activation.AlreadyActive -> ActivateOutcome.Refused(
+                "활성화할 수 있는 상태가 아니다: ${RevisionStatus.ACTIVE} (가능: $ACTIVATABLE)",
             )
-        }
-        if (!allSuitesPass(c, profileRevisionId)) {
-            return@transaction ActivateOutcome.Refused(
-                "세 스위트의 최신 실행이 모두 PASS가 아니다: ${latestResults(c, profileRevisionId)}",
+            Activation.Unknown -> ActivateOutcome.Refused("없는 개정판이다: $profileRevisionId")
+            is Activation.Refused -> ActivateOutcome.Refused(
+                if (outcome.status !in ACTIVATABLE) {
+                    "활성화할 수 있는 상태가 아니다: ${outcome.status} (가능: $ACTIVATABLE)"
+                } else {
+                    "세 스위트의 최신 실행이 모두 PASS가 아니다: ${outcome.latest}"
+                },
             )
         }
 
+    /**
+     * 조작 문의 활성화. **이미 `ACTIVE` 면 멱등이다** — 응답을 못 받은 화면이 다시 눌러도 거절로 보이지 않는다.
+     *
+     * 기종 행을 잠그고 진행한다. 같은 기종의 두 개정판이 동시에 활성화되면 둘 다 같은 «옛 활성» 을 내리고 각자를
+     * 올려 `ACTIVE` 가 둘이 되는데, 잠그면 둘째가 첫째를 옛 활성으로 본다.
+     */
+    fun activateRevision(profileRevisionId: Long, actor: String): Activation = db.transaction { c ->
+        val profileId = c.prepareStatement(
+            "SELECT profile_id FROM profile_revision WHERE profile_revision_id = ?",
+        ).use { s ->
+            s.setLong(1, profileRevisionId)
+            s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
+        } ?: return@transaction Activation.Unknown
+        c.prepareStatement("SELECT 1 FROM capability_profile WHERE profile_id = ? FOR UPDATE").use { s ->
+            s.setLong(1, profileId)
+            s.executeQuery().close()
+        }
+
+        val status = checkNotNull(statusOf(c, profileRevisionId))
+        if (status == RevisionStatus.ACTIVE) return@transaction Activation.AlreadyActive
+        if (status !in ACTIVATABLE || !allSuitesPass(c, profileRevisionId)) {
+            return@transaction Activation.Refused(status, latestResults(c, profileRevisionId))
+        }
+
         // 같은 기종의 옛 활성 개정판은 **지우지 않고** SUPERSEDED로 내린다 —
         // 롤백이 "이전 개정판 재활성화"이므로 남아 있어야 한다(§8.4 ⑥).
         val previous = c.prepareStatement(
@@ -140,7 +209,7 @@ class BindingService(private val db: Db) {
         ).use { it.setString(1, actor); it.setLong(2, profileRevisionId); it.executeUpdate() }
 
         audit(c, actor, "PROFILE_REVISION_ACTIVATE", "$profileRevisionId")
-        ActivateOutcome.Activated(previous)
+        Activation.Activated(previous)
     }
 
     /**
@@ -179,6 +248,68 @@ class BindingService(private val db: Db) {
             )
         }
 
+        val (id, unbound) = rebind(c, robotId, adapterVersionId, profileRevisionId, actor, reason)
+        BindOutcome.Bound(id, unbound)
+    }
+
+    /**
+     * 조작 문의 바인딩. [bind] 가 열어 둔 세 구멍을 막는다 — 없는 기체가 FK 위반 500 이 되고, 퇴역 기체도 묶이며,
+     * 같은 조합을 다시 묶으면 새 행이 생겨 사이트 명칭 기록이 «미등록» 으로 돌아간다.
+     *
+     * **기체 행을 잠그고 진행한다.** 같은 기체에 동시에 온 첫 바인딩 둘이 둘 다 «활성 없음» 을 보고 넣으면
+     * `robot_binding_one_active` 위반 500 이 되는데, 잠그면 둘째가 첫째를 본다.
+     *
+     * 같은 조합 검사는 **모든 검사 뒤**다. 요청이 지금 유효할 때만 «이미 됨» 이라고 답한다 — 묶인 뒤 개정판이
+     * 대체됐으면 같은 조합이어도 [Binding.RevisionNotActive] 다. 감사 `ROBOT_BIND` 는 새로 묶을 때만 남는다.
+     */
+    fun bindRobot(
+        robotId: String,
+        adapterVersionId: Long,
+        profileRevisionId: Long,
+        actor: String,
+        reason: String? = null,
+    ): Binding = db.transaction { c ->
+        val retired = c.prepareStatement("SELECT retired_at IS NOT NULL FROM robot WHERE robot_id = ? FOR UPDATE").use { s ->
+            s.setString(1, robotId)
+            s.executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) else null }
+        } ?: return@transaction Binding.UnknownRobot
+        if (retired) return@transaction Binding.RobotRetired
+
+        val status = statusOf(c, profileRevisionId) ?: return@transaction Binding.UnknownRevision
+        val contractSemver = c.prepareStatement(
+            "SELECT contract_semver FROM adapter_version WHERE adapter_version_id = ?",
+        ).use { s ->
+            s.setLong(1, adapterVersionId)
+            s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
+        } ?: return@transaction Binding.UnknownBuild
+        if (status != RevisionStatus.ACTIVE) return@transaction Binding.RevisionNotActive(status)
+
+        val tooNew = requiredSemvers(c, profileRevisionId)
+            .filter { (_, introduced) -> Semver.parse(introduced) > Semver.parse(contractSemver) }
+        if (tooNew.isNotEmpty()) return@transaction Binding.ContractTooOld(contractSemver, tooNew)
+
+        val same = c.prepareStatement(
+            "SELECT robot_binding_id FROM robot_binding " +
+                "WHERE robot_id = ? AND unbound_at IS NULL AND adapter_version_id = ? AND profile_revision_id = ?",
+        ).use { s ->
+            s.setString(1, robotId); s.setLong(2, adapterVersionId); s.setLong(3, profileRevisionId)
+            s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
+        }
+        if (same != null) return@transaction Binding.AlreadyBound(same)
+
+        val (id, unbound) = rebind(c, robotId, adapterVersionId, profileRevisionId, actor, reason)
+        Binding.Bound(id, unbound)
+    }
+
+    /** 옛 활성을 풀고 새 행을 넣는다. 두 바인딩 길([bind]·[bindRobot])이 같은 기록을 남기게 하려고 뗐다. */
+    private fun rebind(
+        c: Connection,
+        robotId: String,
+        adapterVersionId: Long,
+        profileRevisionId: Long,
+        actor: String,
+        reason: String?,
+    ): Pair<Long, Long?> {
         // **해제는 삭제가 아니라 `unbound_at`이다.** 이력이 남아야 진단 1번이
         // "이 기체는 어느 어댑터·개정판이었는가"에 답할 수 있다.
         val unbound = c.prepareStatement(
@@ -200,7 +331,7 @@ class BindingService(private val db: Db) {
         }
 
         audit(c, actor, "ROBOT_BIND", robotId)
-        BindOutcome.Bound(id, unbound)
+        return id to unbound
     }
 
     /** 지금 이 기체가 쓰는 조합. 없으면 `null`. */
diff --git a/registry/src/main/kotlin/dev/picasso/registry/revision/RevisionService.kt b/registry/src/main/kotlin/dev/picasso/registry/revision/RevisionService.kt
index c125d6e..0866572 100644
--- a/registry/src/main/kotlin/dev/picasso/registry/revision/RevisionService.kt
+++ b/registry/src/main/kotlin/dev/picasso/registry/revision/RevisionService.kt
@@ -22,6 +22,24 @@ sealed interface SubmitOutcome {
     data class Rejected(val detail: String) : SubmitOutcome
 }
 
+/**
+ * 조작 문의 제출 결과(picasso-ops P2·S1d 스펙 §6.2). [SubmitOutcome] 과 달리 **같은 문서의 재제출을 거절하지 않는다** —
+ * 응답을 못 받은 화면이 다시 보내면 같은 개정판을 돌려준다.
+ */
+sealed interface Submitted {
+    /** 새로 저장했다. 검증에 실패했으면 [status] 가 `DRAFT` 다. */
+    data class Created(val profileRevisionId: Long, val revision: Int, val status: RevisionStatus, val reasons: List<String>) : Submitted
+
+    /** 같은 기종·같은 번호·같은 문서가 이미 있다. 그 개정판이다. */
+    data class Existing(val profileRevisionId: Long, val revision: Int, val status: RevisionStatus, val reasons: List<String>) : Submitted
+
+    /** 번호가 단조 증가하지 않는다. 같은 번호에 다른 문서도 여기다. */
+    data class NotMonotonic(val received: Int, val highest: Int) : Submitted
+
+    /** 프로파일 문서로 읽을 수 없다. */
+    data class Unreadable(val detail: String) : Submitted
+}
+
 /**
  * §8.4 ①의 개정판 등록.
  *
@@ -53,6 +71,49 @@ class RevisionService(
             )
         }
 
+        store(c, profileId, parsed, documentJson, actor)
+    }
+
+    /**
+     * 조작 문의 제출. **기종 행을 잠그고 진행한다** — 같은 문서가 동시에 두 번 오면 둘 다 «최대 번호보다 크다» 를 보고
+     * 넣다가 `(profile_id, revision)` 유일 제약에 걸려 500 이 되는데, 잠그면 둘째가 첫째의 개정판을 본다.
+     *
+     * 같은 번호·같은 문서 해시면 [Submitted.Existing] 이고 감사를 남기지 않는다. 일어난 일이 없다.
+     */
+    fun submitDocument(documentJson: String, actor: String): Submitted = db.transaction { c ->
+        val parsed = ProfileDocument.parse("submitted", documentJson).getOrNull()
+            ?: return@transaction Submitted.Unreadable("프로파일을 읽을 수 없다")
+
+        val profileId = upsertProfile(c, parsed.vendor, parsed.model)
+        c.prepareStatement("SELECT 1 FROM capability_profile WHERE profile_id = ? FOR UPDATE").use { s ->
+            s.setLong(1, profileId)
+            s.executeQuery().close()
+        }
+
+        existing(c, profileId, parsed.revision)?.let { row ->
+            if (row.hash == sha256(documentJson)) {
+                return@transaction Submitted.Existing(row.id, parsed.revision, row.status, row.reasons)
+            }
+        }
+        val highest = highestRevision(c, profileId)
+        if (highest != null && parsed.revision <= highest) {
+            return@transaction Submitted.NotMonotonic(parsed.revision, highest)
+        }
+
+        val stored = store(c, profileId, parsed, documentJson, actor)
+        Submitted.Created(stored.profileRevisionId, stored.revision, stored.status, stored.reasons)
+    }
+
+    // ── 저장 (프레임워크 없이 JDBC로. §3.4의 "도메인은 프레임워크를 모른다")
+
+    /** 검증하고 저장한다. 두 제출 길([submit]·[submitDocument])이 같은 저장을 지나게 하려고 뗐다. */
+    private fun store(
+        c: Connection,
+        profileId: Long,
+        parsed: ProfileDocument,
+        documentJson: String,
+        actor: String,
+    ): SubmitOutcome.Stored {
         val baseline = activeDocument(c, profileId)
         val outcome = validator.validate(documentJson, "revision-${parsed.revision}", baseline)
 
@@ -80,10 +141,21 @@ class RevisionService(
             subject = "${parsed.vendor}/${parsed.model}#${parsed.revision}",
             after = """{"status":"$status","reasons":${reasons.size}}""",
         )
-        SubmitOutcome.Stored(id, parsed.revision, status, reasons)
+        return SubmitOutcome.Stored(id, parsed.revision, status, reasons)
     }
 
-    // ── 저장 (프레임워크 없이 JDBC로. §3.4의 "도메인은 프레임워크를 모른다")
+    private class StoredRow(val id: Long, val hash: String, val status: RevisionStatus, val reasons: List<String>)
+
+    private fun existing(c: Connection, profileId: Long, revision: Int): StoredRow? = c.prepareStatement(
+        "SELECT profile_revision_id, document_hash, status, validation_detail::text FROM profile_revision " +
+            "WHERE profile_id = ? AND revision = ?",
+    ).use { s ->
+        s.setLong(1, profileId); s.setInt(2, revision)
+        s.executeQuery().use { rs ->
+            if (!rs.next()) return@use null
+            StoredRow(rs.getLong(1), rs.getString(2), RevisionStatus.valueOf(rs.getString(3)), reasonsOf(rs.getString(4)))
+        }
+    }
 
     private fun upsertProfile(c: Connection, vendor: String, model: String): Long {
         c.prepareStatement(
@@ -152,7 +224,8 @@ class RevisionService(
      *
      * **`skill_type`에 없는 스킬은 건너뛴다.** 동기화가 아직 안 돌았거나
      * 계약에 없는 스킬인데, 여기서 만들어 넣으면 §8.1의 "이 표는 계약이
-     * 소유한다"가 깨진다. 그 부재는 바인딩이 보고 막는다.
+     * 소유한다"가 깨진다. **그 부재를 막는 곳은 없다** — 건너뛴 스킬은 사이트 명칭 요구 집합에도, 바인딩의 계약
+     * semver 검사에도 안 들어간다. 그래서 registry 가 기동할 때 동기화한다([SkillTypeCatalog.syncAtBoot]).
      */
     private fun insertSkills(c: Connection, revisionId: Long, document: ProfileDocument) {
         document.skills.forEach { skill ->
@@ -220,4 +293,11 @@ class RevisionService(
     private fun sha256(text: String): String =
         MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
             .joinToString("") { "%02x".format(it) }
+
+    private fun reasonsOf(detail: String?): List<String> =
+        detail?.let { MAPPER.readTree(it)["reasons"]?.map { r -> r.asText() } } ?: emptyList()
+
+    private companion object {
+        val MAPPER = com.fasterxml.jackson.databind.ObjectMapper()
+    }
 }
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :registry:test --tests '*RevisionOperationsTest' --tests '*AxisSeparationTest' --tests '*PreconditionTest' --tests '*SiteCatalogTest' --tests '*SiteNameRegistrationTest' --tests '*DiagnosticsTest' --tests '*RevisionLifecycleTest' --tests '*ShrinkRefusalTest' -q`
Expected: XML 기준 `RevisionOperationsTest` 14개 통과, 옛 `activate`·`bind`·`submit` 을 쓰는 기존 시험(나열한 일곱 클래스)도 실패 0. registry 전체는 Task 2 Step 5 가 돌린다.

- [ ] **Step 7: 커밋**

```bash
git add registry/src/main/kotlin/dev/picasso/registry/revision/SkillTypeCatalog.kt registry/src/main/kotlin/dev/picasso/registry/revision/RevisionListing.kt registry/src/main/kotlin/dev/picasso/registry/revision/RevisionService.kt registry/src/main/kotlin/dev/picasso/registry/binding/BindingService.kt registry/src/test/kotlin/dev/picasso/registry/RevisionOperationsTest.kt
git commit -q -F - <<'EOF'
feat(registry): 개정판 제출·활성화·바인딩 결과 타입과 기동 동기화 서비스

- 작업 묶음 커밋(Task 7 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 2: 조작 문 5개, 기동 동기화 배선, 진단 칸, 메시지

**Files:**
- Create: `registry/src/main/kotlin/dev/picasso/registry/web/RevisionOperationsController.kt`
- Create: `registry/src/main/kotlin/dev/picasso/registry/web/BindingOperationsController.kt`
- Modify: `registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt`(빈 셋 `revisions`·`revisionListing`·`skillTypes` 와 `ApplicationRunner` 빈 `catalogBootSync`)
- Modify: `registry/src/main/kotlin/dev/picasso/registry/diag/DiagnosticsService.kt`(행 칸 8개, 기본값 없음, KDoc 5값)
- Modify: `registry/src/main/kotlin/dev/picasso/registry/binding/RobotRegistration.kt`(퇴역 메시지)
- Modify: `registry/src/main/kotlin/dev/picasso/registry/web/OperationsController.kt`(모르는 기체 메시지)
- Test: `registry/src/test/kotlin/dev/picasso/registry/web/RevisionEndpointTest.kt`, `registry/src/test/kotlin/dev/picasso/registry/web/CatalogBootEndpointTest.kt`

- [ ] **Step 1: 실패하는 시험 쓰기** — 두 파일.

```kotlin
package dev.picasso.registry.web

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.ingest.LivenessService
import dev.picasso.registry.ingest.SiteNameReport
import dev.picasso.registry.revision.SkillTypeSync
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
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 개정판과 바인딩의 조작 문(picasso-ops P2·S1d 스펙 §6.2). **결과 타입이 응답 코드와 `reason` 으로 옮겨졌는가.**
 *
 * 서비스 시험은 결과를 보고, 이 시험은 그것이 문에서 어떻게 보이는지와 운영자 토큰 관문이 새 경로에도 걸렸는지 본다.
 * 관문은 경로 패턴으로 걸리므로 새 경로가 패턴 밖이면 단위 시험이 전부 초록인 채로 문이 열린다.
 */
@SpringBootTest(
    classes = [RegistryApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class RevisionEndpointTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    private lateinit var db: Db
    private var build: Long = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        db = Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        SkillTypeSync(db).sync(Fixtures.descriptor(), SEMVER, "sync")
        val adapters = AdapterService(db)
        build = (adapters.registerVersion(adapters.registerAdapter("acme", "drv", "op"), "1.0.0", SEMVER, "op") as RegisterOutcome.Registered)
            .adapterVersionId
        PostgresSupport.execute("INSERT INTO robot (robot_id, site_id, serial_number) VALUES ('r1','line-a','sn-r1')")
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

    private fun submit(document: String = Fixtures.good()) = call(HttpMethod.POST, "/operations/profile-revisions", document)

    private fun activate(id: Long) = call(HttpMethod.POST, "/operations/profile-revisions/$id/activation")

    private fun bind(robot: String, body: String) = call(HttpMethod.POST, "/operations/robots/$robot/binding", body)

    private fun field(json: String, key: String): String = Regex(""""$key"\s*:\s*"?([^",}]+)""").find(json)!!.groupValues[1]

    private fun passed(id: Long) = BindingService.SUITE_NAMES.forEach { BindingService(db).recordTestRun(id, it, "PASS", "harness") }

    private fun active(): Long {
        val id = field(submit().body!!, "profile_revision_id").toLong()
        passed(id)
        assertEquals(200, activate(id).statusCode.value())
        return id
    }

    @Test
    fun `새 경로는 모두 운영자 토큰 뒤다`() {
        listOf(
            HttpMethod.GET to "/operations/skill-types",
            HttpMethod.GET to "/operations/profile-revisions",
            HttpMethod.POST to "/operations/profile-revisions",
            HttpMethod.POST to "/operations/profile-revisions/1/activation",
            HttpMethod.POST to "/operations/robots/r1/binding",
        ).forEach { (method, path) ->
            assertEquals(401, call(method, path, "{}", token = INGEST_TOKEN).statusCode.value(), path)
        }
    }

    @Test
    fun `스킬 종류는 계약 semver 와 사이트 명칭 키를 싣는다`() {
        val r = call(HttpMethod.GET, "/operations/skill-types")

        assertEquals(200, r.statusCode.value(), r.body)
        assertTrue(r.body!!.contains("\"contract_semver\":\"$SEMVER\""), r.body)
        assertTrue(Regex(""""name":"navigate_to"[^}]*"site_reference_keys":\["location"]""").containsMatchIn(r.body!!), r.body)
    }

    @Test
    fun `제출은 새로 201, 같은 문서 200, 단조 위반 409, 못 읽으면 400 이다`() {
        val first = submit()
        val again = submit()

        assertEquals(201, first.statusCode.value(), first.body)
        assertEquals("VALIDATED", field(first.body!!, "status"), first.body)
        assertEquals(200, again.statusCode.value(), again.body)
        assertEquals(field(first.body!!, "profile_revision_id"), field(again.body!!, "profile_revision_id"))

        val conflict = submit(Fixtures.good().replace("\"seconds\": 20", "\"seconds\": 21"))
        assertEquals(409, conflict.statusCode.value(), conflict.body)
        assertEquals("1", field(conflict.body!!, "highest"))

        assertEquals(400, submit("{not json").statusCode.value())
        assertEquals(400, call(HttpMethod.POST, "/operations/profile-revisions", Fixtures.good(), actor = null).statusCode.value())
    }

    @Test
    fun `검증에 실패한 문서도 201 이고 DRAFT 와 사유를 싣는다`() {
        val r = submit(Fixtures.badErrorType())

        assertEquals(201, r.statusCode.value(), r.body)
        assertEquals("DRAFT", field(r.body!!, "status"))
        assertTrue(Regex(""""reasons":\["[^"]+""").containsMatchIn(r.body!!), r.body)
    }

    @Test
    fun `목록은 상태·스위트 결과·최신 시험 요청을 싣는다`() {
        val id = field(submit().body!!, "profile_revision_id").toLong()
        BindingService(db).recordTestRun(id, "CONTRACT", "PASS", "harness")
        call(HttpMethod.POST, "/operations/profile-revisions/$id/test-requests")

        val r = call(HttpMethod.GET, "/operations/profile-revisions")

        assertEquals(200, r.statusCode.value(), r.body)
        assertTrue(r.body!!.contains("\"status\":\"VALIDATED\""), r.body)
        assertTrue(Regex(""""CONTRACT":\{"result":"PASS"""").containsMatchIn(r.body!!), r.body)
        assertTrue(Regex(""""latest_test_request":\{"request_id":\d+,"requested_by":"engineer/kim"""").containsMatchIn(r.body!!), r.body)
    }

    @Test
    fun `활성화는 200, 다시 200, 조건 미달 409, 없으면 404 다`() {
        val id = field(submit().body!!, "profile_revision_id").toLong()
        BindingService(db).recordTestRun(id, "CONTRACT", "PASS", "harness")

        val refused = activate(id)
        assertEquals(409, refused.statusCode.value(), refused.body)
        assertEquals("VALIDATED", field(refused.body!!, "status"))
        assertTrue(refused.body!!.contains("\"suites\":{\"CONTRACT\":\"PASS\"}"), refused.body)
        assertEquals(400, call(HttpMethod.POST, "/operations/profile-revisions/$id/activation", actor = null).statusCode.value())

        passed(id)
        assertEquals(200, activate(id).statusCode.value())
        val again = activate(id)
        assertEquals(200, again.statusCode.value())
        assertTrue(again.body!!.contains("\"already\":true"), again.body)

        assertEquals(404, activate(999_999).statusCode.value())
    }

    @Test
    fun `바인딩은 201, 같은 조합 200, 404 와 409 는 reason 으로 갈린다`() {
        val revision = active()
        val body = """{"adapter_version_id":$build,"profile_revision_id":$revision}"""

        assertEquals(400, call(HttpMethod.POST, "/operations/robots/r1/binding", body, actor = null).statusCode.value())
        val first = bind("r1", body)
        assertEquals(201, first.statusCode.value(), first.body)
        val again = bind("r1", body)
        assertEquals(200, again.statusCode.value(), again.body)
        assertEquals(field(first.body!!, "robot_binding_id"), field(again.body!!, "robot_binding_id"))

        mapOf(
            bind("ghost", body) to "UNKNOWN_ROBOT",
            bind("r1", """{"adapter_version_id":$build,"profile_revision_id":999999}""") to "UNKNOWN_REVISION",
            bind("r1", """{"adapter_version_id":999999,"profile_revision_id":$revision}""") to "UNKNOWN_BUILD",
        ).forEach { (r, reason) ->
            assertEquals(404, r.statusCode.value(), r.body)
            assertEquals(reason, field(r.body!!, "reason"))
        }

        val validated = field(submit(Fixtures.good(revision = 2)).body!!, "profile_revision_id")
        val notActive = bind("r1", """{"adapter_version_id":$build,"profile_revision_id":$validated}""")
        assertEquals(409, notActive.statusCode.value(), notActive.body)
        assertEquals("REVISION_NOT_ACTIVE", field(notActive.body!!, "reason"))

        PostgresSupport.execute("UPDATE robot SET retired_at = now(), retired_by = 'op', retired_reason = 'sold' WHERE robot_id = 'r1'")
        val retired = bind("r1", body)
        assertEquals(409, retired.statusCode.value(), retired.body)
        assertEquals("ROBOT_RETIRED", field(retired.body!!, "reason"))
    }

    @Test
    fun `낮은 계약의 빌드는 409 CONTRACT_TOO_OLD 이고 빈 본문은 400 이다`() {
        val revision = active()
        val adapters = AdapterService(db)
        val old = (adapters.registerVersion(adapters.registerAdapter("acme", "old", "op"), "0.0.1", "0.0.1", "op") as RegisterOutcome.Registered)
            .adapterVersionId

        val r = bind("r1", """{"adapter_version_id":$old,"profile_revision_id":$revision}""")
        assertEquals(409, r.statusCode.value(), r.body)
        assertEquals("CONTRACT_TOO_OLD", field(r.body!!, "reason"))
        assertEquals(400, bind("r1", "{}").statusCode.value())
    }

    /** 빌드 id 를 개정판 id 와 다르게 만든다. 빈 DB 에서는 둘 다 1 이라 칸을 바꿔 읽어도 초록이었다(결함 주입으로 확인). */
    @Test
    fun `바인딩 진단 행이 빌드 id, 바인딩한 이, 명칭 기록과 보고 칸을 싣는다`() {
        val revision = active()
        val adapters = AdapterService(db)
        val other = (adapters.registerVersion(adapters.registerAdapter("acme", "drv2", "op"), "2.0.0", SEMVER, "op") as RegisterOutcome.Registered)
            .adapterVersionId
        check(other != revision) { "빌드 id 와 개정판 id 가 같다 — 칸을 바꿔 읽어도 못 가른다" }
        bind("r1", """{"adapter_version_id":$other,"profile_revision_id":$revision}""")
        call(HttpMethod.POST, "/operations/site-names?robot=r1")

        val r = call(HttpMethod.GET, "/diag/bindings", token = null)

        assertEquals(200, r.statusCode.value(), r.body)
        assertEquals("$other", field(r.body!!, "adapterVersionId"))
        assertEquals("engineer/kim", field(r.body!!, "boundBy"))
        assertTrue(Regex(""""boundAt":"\d{4}-\d{2}-\d{2}T""").containsMatchIn(r.body!!), r.body)
        assertEquals("engineer/kim", field(r.body!!, "siteNamesRegisteredBy"))
        assertTrue(Regex(""""siteNamesRegisteredAt":"\d{4}-\d{2}-\d{2}T""").containsMatchIn(r.body!!), r.body)
        assertEquals("CLAIMED", field(r.body!!, "siteNames"))
        assertTrue(r.body!!.contains("\"siteNamesReportedAt\":null"), r.body)

        // 기체가 이름 셋을 안다고 답한다. 사람의 기록과 기체의 답이 따로 보여야 한다.
        LivenessService(db).record(
            MessageHeader.newBuilder().setRobotId("r1").setCapabilityEpoch(1).build(),
            ConnectionState.CONNECTION_STATE_ONLINE,
            null,
            SiteNameReport(unsupported = false, count = 3),
        )
        val answered = call(HttpMethod.GET, "/diag/bindings", token = null).body!!
        assertEquals("CONFIRMED", field(answered, "siteNames"))
        assertEquals("3", field(answered, "siteNamesCount"))
        assertEquals("false", field(answered, "siteNamesUnsupported"))
        assertTrue(Regex(""""siteNamesReportedAt":"\d{4}-\d{2}-\d{2}T""").containsMatchIn(answered), answered)
    }

    @Test
    fun `기체 거절 메시지가 기체 id 와 퇴역 시각을 값으로 찍는다`() {
        val unknown = call(HttpMethod.DELETE, "/operations/robots/ghost/retirement")
        assertEquals(404, unknown.statusCode.value(), unknown.body)
        assertTrue(unknown.body!!.contains("모르는 기체다: ghost"), unknown.body)

        call(HttpMethod.POST, "/operations/robots/r1/retirement", """{"reason":"sold"}""")
        val again = call(HttpMethod.POST, "/operations/robots", """{"robot_id":"r1","site":"line-a","serial_number":"sn-r1"}""")
        assertEquals(409, again.statusCode.value(), again.body)
        assertTrue(Regex("""r1 은 \d{4}-\d{2}-\d{2}""").containsMatchIn(again.body!!), again.body)
    }

    private companion object {
        const val INGEST_TOKEN = "ingest-secret"
        const val OPERATOR_TOKEN = "operator-secret"
        val SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("picasso.db.url") { PostgresSupport.jdbcUrl }
            registry.add("picasso.db.user") { PostgresSupport.username }
            registry.add("picasso.db.password") { PostgresSupport.password }
            registry.add("picasso.ingest.token") { INGEST_TOKEN }
            registry.add("picasso.operator.token") { OPERATOR_TOKEN }
            registry.add("picasso.profile.schema") {
                Path.of("..").toAbsolutePath().normalize().resolve("profile/schema/capability-profile.schema.json").toString()
            }
        }
    }
}
```

```kotlin
package dev.picasso.registry.web

import dev.picasso.registry.Fixtures
import dev.picasso.registry.PostgresSupport
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * registry 가 **기동하면서** 계약 카탈로그를 넣는가(설계 §8.3 ④, picasso-ops P2·S1d 스펙 §6.1).
 *
 * 다른 표면 시험은 시험마다 스키마를 지우고 손으로 동기화하므로 기동 동기화를 못 본다. 이 시험은 기동 전에 스키마를
 * 세우고([properties]) 기동 뒤에는 아무것도 지우지 않는다 — 손으로 넣은 것이 없으니 보이는 행은 기동이 넣은 것이다.
 */
@SpringBootTest(
    classes = [RegistryApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class CatalogBootEndpointTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `기동하면 스킬 종류가 계약과 같고 감사 행위자는 registry 다`() {
        val r = rest.exchange(
            "http://localhost:$port/operations/skill-types",
            HttpMethod.GET,
            HttpEntity<String>(HttpHeaders().apply { set("Authorization", "Bearer $OPERATOR_TOKEN") }),
            String::class.java,
        )
        assertEquals(200, r.statusCode.value(), r.body)

        val contract = dev.picasso.gate.model.ContractIndex.from(Fixtures.descriptor()).skillTypes().toSet()
        val synced = PostgresSupport.queryAll("SELECT DISTINCT name FROM skill_type") { it.getString(1) }.toSet()
        assertEquals(contract, synced)
        contract.forEach { assertTrue(r.body!!.contains("\"name\":\"$it\""), "$it 이 조회에 없다: ${r.body}") }
        assertEquals(
            listOf("registry"),
            PostgresSupport.queryAll("SELECT actor FROM audit_log WHERE operation = 'SKILL_TYPE_SYNC'") { it.getString(1) },
        )
    }

    private companion object {
        const val OPERATOR_TOKEN = "operator-secret"

        // 기동 전에 스키마를 세운다. 운영에서 마이그레이션이 런처의 몫인 것과 같은 순서다. 속성 공급자 안에서 하면
        // 스프링이 그 값을 다시 읽을 때마다 지워지므로 클래스가 처음 쓰일 때 한 번만 한다.
        init {
            PostgresSupport.reset()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("picasso.db.url") { PostgresSupport.jdbcUrl }
            registry.add("picasso.db.user") { PostgresSupport.username }
            registry.add("picasso.db.password") { PostgresSupport.password }
            registry.add("picasso.operator.token") { OPERATOR_TOKEN }
        }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :registry:test --tests '*RevisionEndpointTest' --tests '*CatalogBootEndpointTest' -q`
Expected: XML 에서 `CatalogBootEndpointTest` 1개와 `RevisionEndpointTest` 대부분이 실패(문이 없음, 진단 칸 없음, 메시지가 글자 그대로). `새 경로는 모두 운영자 토큰 뒤다` 는 관문이 경로 패턴(`/operations/**`)으로 걸리므로 문이 없어도 통과할 수 있다.

- [ ] **Step 3: 컨트롤러 둘**

```kotlin
package dev.picasso.registry.web

import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.registry.binding.Activation
import dev.picasso.registry.binding.BindingService
import dev.picasso.registry.revision.RevisionListing
import dev.picasso.registry.revision.RevisionService
import dev.picasso.registry.revision.SkillTypeCatalog
import dev.picasso.registry.revision.Submitted
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 개정판의 조작 문(§8.4 ①③, picasso-ops P2·S1d 스펙 §6.2) — 스킬 종류 조회, 제출, 목록, 활성화. 관문은
 * [OperatorToken] 이 경로로 건다.
 *
 * 생긴 이유는 첫 바깥 소비자다(ADR 9). picasso-ops 의 운영 화면이 개정판을 제출하고 활성화하려 했으나 서비스가
 * 빈(bean)조차 없었다.
 */
@RestController
class RevisionOperationsController(
    private val revisions: RevisionService,
    private val listing: RevisionListing,
    private val skillTypes: SkillTypeCatalog,
    private val bindings: BindingService,
) {

    /** 계약이 아는 스킬 종류와 지금 계약 semver. 제출할 문서를 쓰는 사람이 무엇을 선언할 수 있는지 본다. */
    @GetMapping("/operations/skill-types")
    fun skillTypes(): Map<String, Any> = mapOf(
        "contract_semver" to ContractIdentity.semver,
        "skill_types" to skillTypes.list().map {
            mapOf(
                "name" to it.name,
                "major" to it.major,
                "introduced_in_semver" to it.introducedInSemver,
                "site_reference_keys" to it.siteReferenceKeys,
            )
        },
    )

    /**
     * 본문은 프로파일 문서 JSON **그대로**다. 문서 해시가 이 문자열로 매겨지므로 다시 직렬화하면 같은 문서의 재제출이
     * 다른 문서로 보인다.
     *
     * 검증에 실패해도 저장하므로 `DRAFT` 도 201 이다(§8.4 ①).
     */
    @PostMapping("/operations/profile-revisions")
    fun submit(
        @RequestBody document: String,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any>> = when (val outcome = revisions.submitDocument(document, actor)) {
        is Submitted.Created -> ResponseEntity.status(HttpStatus.CREATED)
            .body(stored(outcome.profileRevisionId, outcome.revision, outcome.status.name, outcome.reasons))
        is Submitted.Existing ->
            ResponseEntity.ok(stored(outcome.profileRevisionId, outcome.revision, outcome.status.name, outcome.reasons))
        is Submitted.NotMonotonic -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf(
                "error" to "개정판 번호가 단조 증가하지 않는다(같은 번호에 다른 문서 포함)",
                "received" to outcome.received,
                "highest" to outcome.highest,
            ),
        )
        is Submitted.Unreadable -> ResponseEntity.badRequest().body(mapOf("error" to outcome.detail))
    }

    @GetMapping("/operations/profile-revisions")
    fun list(): List<Map<String, Any?>> = listing.list().map { row ->
        mapOf(
            "profile_revision_id" to row.profileRevisionId,
            "vendor" to row.vendor,
            "model" to row.model,
            "revision" to row.revision,
            "status" to row.status.name,
            "reasons" to row.reasons,
            "document_hash" to row.documentHash,
            "created_by" to row.createdBy,
            "created_at" to row.createdAt.toString(),
            "activated_by" to row.activatedBy,
            "activated_at" to row.activatedAt?.toString(),
            "suites" to row.suites.mapValues { (_, run) ->
                mapOf(
                    "result" to run.result,
                    "ran_at" to run.ranAt.toString(),
                    "ran_by" to run.ranBy,
                    "detail" to run.detail?.let { MAPPER.readTree(it) },
                )
            },
            "latest_test_request" to row.latestRequest?.let { r ->
                mapOf(
                    "request_id" to r.requestId,
                    "requested_by" to r.requestedBy,
                    "requested_at" to r.requestedAt.toString(),
                    "claimed_by" to r.claimedBy,
                    "claimed_at" to r.claimedAt?.toString(),
                    "claim_expires_at" to r.claimExpiresAt?.toString(),
                    "completed_at" to r.completedAt?.toString(),
                )
            },
        )
    }

    /** 409 본문에 상태와 스위트별 최신 결과를 싣는다 — 화면이 «왜 안 되는가» 를 다시 묻지 않게 한다. */
    @PostMapping("/operations/profile-revisions/{profileRevisionId}/activation")
    fun activate(
        @PathVariable profileRevisionId: Long,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any?>> = when (val outcome = bindings.activateRevision(profileRevisionId, actor)) {
        is Activation.Activated -> ResponseEntity.ok(mapOf("status" to "ACTIVE", "superseded" to outcome.superseded))
        Activation.AlreadyActive -> ResponseEntity.ok(mapOf("status" to "ACTIVE", "already" to true))
        is Activation.Refused -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf(
                "error" to "활성화할 수 없다 — 상태가 TESTED·SUPERSEDED 이고 세 스위트의 최신 결과가 모두 PASS 여야 한다",
                "status" to outcome.status.name,
                "suites" to outcome.latest,
            ),
        )
        Activation.Unknown ->
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "없는 개정판이다: $profileRevisionId"))
    }

    private fun stored(id: Long, revision: Int, status: String, reasons: List<String>): Map<String, Any> =
        mapOf("profile_revision_id" to id, "revision" to revision, "status" to status, "reasons" to reasons)

    private companion object {
        val MAPPER = com.fasterxml.jackson.databind.ObjectMapper()
    }
}
```

```kotlin
package dev.picasso.registry.web

import dev.picasso.registry.binding.Binding
import dev.picasso.registry.binding.BindingService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 바인딩의 조작 문(§9.1, picasso-ops P2·S1d 스펙 §6.2). 관문은 [OperatorToken] 이 경로로 건다.
 *
 * 404 와 409 는 본문 `reason` 으로 가른다. 화면이 할 일이 거절마다 다르다 — 없는 기체는 등록부터, 퇴역 기체는 복귀부터,
 * 활성 아닌 개정판은 활성화부터, 낮은 계약은 다른 빌드다.
 */
@RestController
class BindingOperationsController(private val bindings: BindingService) {

    @PostMapping("/operations/robots/{robotId}/binding")
    fun bind(
        @PathVariable robotId: String,
        @RequestBody request: BindRequest,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any?>> {
        val build = request.adapter_version_id
        val revision = request.profile_revision_id
        if (build == null || revision == null) {
            return ResponseEntity.badRequest().body(mapOf("error" to "adapter_version_id 와 profile_revision_id 가 필요하다"))
        }
        return when (val outcome = bindings.bindRobot(robotId, build, revision, actor, request.reason)) {
            is Binding.Bound -> ResponseEntity.status(HttpStatus.CREATED)
                .body(mapOf("robot_binding_id" to outcome.bindingId, "unbound_binding_id" to outcome.unbound))
            is Binding.AlreadyBound -> ResponseEntity.ok(mapOf("robot_binding_id" to outcome.bindingId, "already" to true))
            Binding.UnknownRobot -> notFound("UNKNOWN_ROBOT", "모르는 기체다: $robotId")
            Binding.UnknownRevision -> notFound("UNKNOWN_REVISION", "없는 개정판이다: $revision")
            Binding.UnknownBuild -> notFound("UNKNOWN_BUILD", "없는 어댑터 빌드다: $build")
            Binding.RobotRetired -> conflict(mapOf("reason" to "ROBOT_RETIRED", "error" to "퇴역한 기체다 — 묶으려면 복귀시킨다"))
            is Binding.RevisionNotActive -> conflict(
                mapOf("reason" to "REVISION_NOT_ACTIVE", "error" to "활성 개정판이 아니다", "status" to outcome.status.name),
            )
            is Binding.ContractTooOld -> conflict(
                mapOf(
                    "reason" to "CONTRACT_TOO_OLD",
                    "error" to "빌드의 계약 semver 가 개정판의 스킬보다 낮다",
                    "contract_semver" to outcome.contractSemver,
                    "too_new" to outcome.tooNew.map { (skill, since) -> mapOf("skill_type" to skill, "introduced_in_semver" to since) },
                ),
            )
        }
    }

    private fun notFound(reason: String, error: String): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("reason" to reason, "error" to error))

    private fun conflict(body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(body)
}

/** `POST /operations/robots/{robotId}/binding` 의 본문. 숫자 칸이 빠지면 0 이 아니라 널로 받아 400 으로 가른다. */
data class BindRequest(
    val adapter_version_id: Long? = null,
    val profile_revision_id: Long? = null,
    val reason: String? = null,
)
```

- [ ] **Step 4: 배선·진단·메시지 패치** — 아래를 `C:/Users/Eisen/AppData/Local/Temp/p2b-patches/p2b-2.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/p2b-patches/p2b-2.patch`.

```diff
diff --git a/registry/src/main/kotlin/dev/picasso/registry/binding/RobotRegistration.kt b/registry/src/main/kotlin/dev/picasso/registry/binding/RobotRegistration.kt
index 1230308..2e32b89 100644
--- a/registry/src/main/kotlin/dev/picasso/registry/binding/RobotRegistration.kt
+++ b/registry/src/main/kotlin/dev/picasso/registry/binding/RobotRegistration.kt
@@ -258,7 +258,7 @@ class RobotRegistration(private val db: Db, private val now: () -> Instant = Ins
         // 매번 덮고, 선언이 되살리면 *"복귀" 라는 사건이 등록과 구별되지 않는다.* 복귀는 따로 누른다.
         if (existing?.retiredAt != null) {
             return RobotRegistrationOutcome.RetiredAlready(
-                "${'$'}robotId 은 ${'$'}{existing.retiredAt} 에 퇴역한 기체다 — 되돌리려면 복귀시킨다",
+                "$robotId 은 ${existing.retiredAt} 에 퇴역한 기체다 — 되돌리려면 복귀시킨다",
             )
         }
 
diff --git a/registry/src/main/kotlin/dev/picasso/registry/diag/DiagnosticsService.kt b/registry/src/main/kotlin/dev/picasso/registry/diag/DiagnosticsService.kt
index 04a9bdc..945ce10 100644
--- a/registry/src/main/kotlin/dev/picasso/registry/diag/DiagnosticsService.kt
+++ b/registry/src/main/kotlin/dev/picasso/registry/diag/DiagnosticsService.kt
@@ -46,7 +46,7 @@ data class BindingRow(
     /**
      * 이 바인딩이 **사이트의 이름들을 아는가**(ADR 35의 결정 3).
      *
-     * `NOT_REQUIRED` / `UNREGISTERED` / `REGISTERED`. §9.7 ④의
+     * `NOT_REQUIRED` / `UNREGISTERED` / `CLAIMED` / `CONFIRMED` / `CONTRADICTED`([SiteNameStatus]). §9.7 ④의
      * `conformance_status`, §15.47의 `liveness`와 같은 자리다 — **막지 않고
      * 보이게 한다.** 등록 대상은 프로파일이 선언한 스킬의 시맨틱 파라미터에서
      * 유도하므로 기종마다 손으로 적는 목록이 없다.
@@ -55,6 +55,23 @@ data class BindingRow(
 
     /** 무엇을 알아야 하는가. 비어 있으면 [siteNames]가 `NOT_REQUIRED`다. */
     val siteNameKeys: List<String>,
+
+    /**
+     * 묶인 빌드. **어댑터 이름과 버전만으로는 모자란다** — 운영 화면이 응답 없음 뒤에 «요청한 빌드로 묶였는가» 를
+     * 다시 물어 판정하려면 id 가 있어야 한다(picasso-ops P2·S1d 스펙 §6.3).
+     */
+    val adapterVersionId: Long,
+    val boundBy: String,
+    val boundAt: String,
+
+    /** 사람이 명칭을 등록했다고 적은 이·시각. [siteNames] 의 «사람의 말» 절반이다. */
+    val siteNamesRegisteredBy: String?,
+    val siteNamesRegisteredAt: String?,
+
+    /** 기체가 명칭을 답한 시각·개수·호스팅 불가. [siteNames] 의 «기체의 답» 절반이다. 널은 아직 안 물어본 것이다. */
+    val siteNamesReportedAt: String?,
+    val siteNamesCount: Int?,
+    val siteNamesUnsupported: Boolean?,
 )
 
 /**
@@ -164,7 +181,10 @@ class DiagnosticsService(
                        pr.profile_revision_id, pr.revision,
                        a.name, av.version, av.conformance_status,
                        (b.unbound_at IS NULL) AS active,
-                       l.last_reported_at, l.connection_state
+                       l.last_reported_at, l.connection_state,
+                       b.adapter_version_id, b.bound_by, b.bound_at,
+                       b.site_names_registered_by, b.site_names_registered_at,
+                       l.site_names_reported_at, l.site_names_count, l.site_names_unsupported
                 FROM robot_binding b
                 JOIN robot r               ON r.robot_id = b.robot_id
                 JOIN profile_revision pr   ON pr.profile_revision_id = b.profile_revision_id
@@ -204,6 +224,14 @@ class DiagnosticsService(
                                     // "모른다"로 읽힌다.
                                     siteNames = "",
                                     siteNameKeys = emptyList(),
+                                    adapterVersionId = rs.getLong(13),
+                                    boundBy = rs.getString(14),
+                                    boundAt = rs.getTimestamp(15).toInstant().toString(),
+                                    siteNamesRegisteredBy = rs.getString(16),
+                                    siteNamesRegisteredAt = rs.getTimestamp(17)?.toInstant()?.toString(),
+                                    siteNamesReportedAt = rs.getTimestamp(18)?.toInstant()?.toString(),
+                                    siteNamesCount = rs.getInt(19).takeUnless { rs.wasNull() },
+                                    siteNamesUnsupported = rs.getBoolean(20).takeUnless { rs.wasNull() },
                                 ),
                             )
                         }
diff --git a/registry/src/main/kotlin/dev/picasso/registry/web/OperationsController.kt b/registry/src/main/kotlin/dev/picasso/registry/web/OperationsController.kt
index 5f0aaeb..ca9c5ce 100644
--- a/registry/src/main/kotlin/dev/picasso/registry/web/OperationsController.kt
+++ b/registry/src/main/kotlin/dev/picasso/registry/web/OperationsController.kt
@@ -158,7 +158,7 @@ class OperationsController(
                 mapOf("robot" to robotId, "status" to (robots.statusOf(robotId)?.name ?: "UNKNOWN"), "was_retired" to outcome.wasRetired),
             )
         is RetirementOutcome.Unknown ->
-            ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "모르는 기체다: ${'$'}{outcome.robotId}"))
+            ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "모르는 기체다: ${outcome.robotId}"))
         is RetirementOutcome.Rejected ->
             ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to outcome.detail))
     }
diff --git a/registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt b/registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt
index 8c5f056..64f453d 100644
--- a/registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt
+++ b/registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt
@@ -15,6 +15,7 @@ import dev.picasso.registry.observe.ObservationService
 import dev.picasso.registry.revision.RevisionValidator
 import dev.picasso.registry.store.Db
 import org.springframework.beans.factory.annotation.Value
+import org.springframework.boot.ApplicationRunner
 import org.springframework.boot.autoconfigure.SpringBootApplication
 import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
 import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
@@ -156,6 +157,35 @@ open class RegistryApplication {
     open fun siteNames(db: Db): dev.picasso.registry.binding.SiteNameRegistration =
         dev.picasso.registry.binding.SiteNameRegistration(db)
 
+    /** 개정판 제출(§8.4 ①). 조작 문이 생기기 전에는 빈이 없어 시험만 직접 불렀다. */
+    @Bean
+    open fun revisions(db: Db, validator: RevisionValidator): dev.picasso.registry.revision.RevisionService =
+        dev.picasso.registry.revision.RevisionService(db, validator)
+
+    @Bean
+    open fun revisionListing(db: Db): dev.picasso.registry.revision.RevisionListing =
+        dev.picasso.registry.revision.RevisionListing(db)
+
+    @Bean
+    open fun skillTypes(db: Db): dev.picasso.registry.revision.SkillTypeCatalog =
+        dev.picasso.registry.revision.SkillTypeCatalog(db)
+
+    /**
+     * §8.3 ④ — **계약 메타데이터를 기동 때 넣는다.** 넣지 않으면 `skill_type` 이 비어 개정판 제출이 선언 스킬을 조용히
+     * 건너뛴다. 기술자를 못 읽으면 예외로 기동을 멈춘다.
+     */
+    @Bean
+    open fun catalogBootSync(skillTypes: dev.picasso.registry.revision.SkillTypeCatalog): ApplicationRunner =
+        ApplicationRunner {
+            val descriptor = RegistryApplication::class.java.getResourceAsStream("/picasso.desc")?.use { it.readBytes() }
+            val outcome = skillTypes.syncAtBoot(descriptor, dev.picasso.contracts.wire.ContractIdentity.semver)
+            val log = org.slf4j.LoggerFactory.getLogger(RegistryApplication::class.java)
+            when (outcome) {
+                is dev.picasso.registry.revision.BootSync.Synced -> log.info("계약 카탈로그 동기화: 새 스킬 종류 {}", outcome.inserted)
+                dev.picasso.registry.revision.BootSync.NoSchema -> log.warn("스키마가 없어 계약 카탈로그 동기화를 건너뛴다")
+            }
+        }
+
     /**
      * **활성화는 `BindingService`를 지난다**(§8.4 ③). 계획이 status를 직접
      * 쓰면 승인 조건(TESTED/SUPERSEDED, 세 스위트 PASS)을 안 지나는 두 번째
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :registry:test -q`
Expected: XML 기준 registry 전체 396개 실패 0(기준 371 + `RevisionOperationsTest` 14 + `RevisionEndpointTest` 10 + `CatalogBootEndpointTest` 1). 기존 시험은 고치지 않았다.

- [ ] **Step 6: 커밋과 대조**

```bash
git add registry/src/main/kotlin/dev/picasso/registry/web/RevisionOperationsController.kt registry/src/main/kotlin/dev/picasso/registry/web/BindingOperationsController.kt registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt registry/src/main/kotlin/dev/picasso/registry/diag/DiagnosticsService.kt registry/src/main/kotlin/dev/picasso/registry/binding/RobotRegistration.kt registry/src/main/kotlin/dev/picasso/registry/web/OperationsController.kt registry/src/test/kotlin/dev/picasso/registry/web/RevisionEndpointTest.kt registry/src/test/kotlin/dev/picasso/registry/web/CatalogBootEndpointTest.kt
git commit -q -F - <<'EOF'
feat(registry): 개정판·바인딩 조작 문 5개와 기동 카탈로그 동기화

- 작업 묶음 커밋(Task 7 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
bash C:/Users/Eisen/AppData/Local/Temp/p2b-cmp.sh $(git diff --name-only 2370ed3 HEAD)
```
Expected: 13줄 모두 «같음».

---

## Chunk 2: mimic, 결함 주입, 문서, PR

### Task 3: mimic CLI 의 명칭 입력 자리

**Files:**
- Modify: `mimic/src/main/kotlin/dev/picasso/mimic/cli/Main.kt`(`Started.instance`)
- Modify: `mimic/src/main/kotlin/dev/picasso/mimic/RobotInstance.kt`(`@Volatile`)
- Test: `mimic/src/test/kotlin/dev/picasso/mimic/cli/StartedInstanceTest.kt`

- [ ] **Step 1: 실패하는 시험 쓰기**

```kotlin
package dev.picasso.mimic.cli

import dev.picasso.contracts.v1.GetKnownSiteNamesRequest
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.wire.RequestHeaders
import io.grpc.ManagedChannelBuilder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CLI 로 띄운 기체에 **기체가 아는 사이트 명칭을 넣을 자리**(picasso-ops P2·S1d 스펙 §6.4).
 *
 * 담는 쪽이 현장의 명칭 티칭을 흉내 내려면 서버가 실제로 들고 있는 인스턴스를 받아야 한다. 복사본을 받으면 값을 넣어도
 * 기체의 답이 안 바뀌고, 그 차이는 registry 의 명칭 상태가 `CONTRADICTED` 로 남는 것으로만 보인다.
 */
class StartedInstanceTest {

    private val schema = Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize().toString()
    private val minimal = Path.of("..", "profile", "fixtures", "minimal.json").normalize().toString()

    @Test
    fun `기동한 기체에 넣은 명칭을 그 기체가 답한다`() {
        val cli = MimicCli()
        try {
            assertEquals(0, cli.run(arrayOf("--robot", "r1=$minimal", "--schema", schema, "--port", "0"), StringBuilder(), StringBuilder()))
            val started = checkNotNull(cli.started)

            started.instance("r1")!!.knownSiteNames = listOf("dock-a", "shelf-3")

            val channel = ManagedChannelBuilder.forAddress("127.0.0.1", started.server.port).usePlaintext().build()
            try {
                val answer = SkillServiceGrpc.newBlockingStub(channel).getKnownSiteNames(
                    GetKnownSiteNamesRequest.newBuilder()
                        .setHeader(RequestHeaders.build("picasso.v1.GetKnownSiteNamesRequest", "r1", "c1"))
                        .build(),
                )
                assertEquals(2, answer.totalCount)
                assertEquals(listOf("dock-a", "shelf-3"), answer.namesList)
            } finally {
                channel.shutdownNow()
            }
            assertNull(started.instance("ghost"))
        } finally {
            cli.started?.server?.shutdown()
        }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :mimic:compileTestKotlin -q`
Expected: 컴파일 실패(`instance` 를 모름).

- [ ] **Step 3: 패치** — 아래를 `C:/Users/Eisen/AppData/Local/Temp/p2b-patches/p2b-3.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/p2b-patches/p2b-3.patch`.

```diff
diff --git a/mimic/src/main/kotlin/dev/picasso/mimic/RobotInstance.kt b/mimic/src/main/kotlin/dev/picasso/mimic/RobotInstance.kt
index 9ef6667..9594286 100644
--- a/mimic/src/main/kotlin/dev/picasso/mimic/RobotInstance.kt
+++ b/mimic/src/main/kotlin/dev/picasso/mimic/RobotInstance.kt
@@ -88,7 +88,10 @@ class RobotInstance(
      * 요구하게 된다. 원장이 "0"과 "모른다"를 가른 것과 같은 규율이다.
      *
      * 기본값이 빈 목록인 것이 요점이다: **기동한 기체는 아무 이름도 모른다.**
+     *
+     * `@Volatile` 인 것은 쓰는 스레드(제어 채널, 담는 쪽의 런처)와 읽는 스레드(생존 보고 발행)가 다르기 때문이다.
      */
+    @Volatile
     var knownSiteNames: List<String>? = emptyList()
 
     /**
diff --git a/mimic/src/main/kotlin/dev/picasso/mimic/cli/Main.kt b/mimic/src/main/kotlin/dev/picasso/mimic/cli/Main.kt
index 3c8ac6e..ef3a0f3 100644
--- a/mimic/src/main/kotlin/dev/picasso/mimic/cli/Main.kt
+++ b/mimic/src/main/kotlin/dev/picasso/mimic/cli/Main.kt
@@ -26,7 +26,18 @@ import kotlin.system.exitProcess
  */
 class MimicCli {
 
-    class Started(val server: MimicServer, val robotIds: Set<String>)
+    /**
+     * @param instances 기체 id 별 인스턴스. 담는 쪽이 기체가 아는 사이트 명칭([RobotInstance.knownSiteNames])을 넣는
+     *   자리다 — 현장의 명칭 티칭(`commissioning.md` Step 3)을 제어 서버 없이 흉내 낸다.
+     */
+    class Started(
+        val server: MimicServer,
+        val robotIds: Set<String>,
+        private val instances: Map<String, RobotInstance> = emptyMap(),
+    ) {
+        /** 이 서버가 띄운 기체. 모르는 id 면 널이다. */
+        fun instance(robotId: String): RobotInstance? = instances[robotId]
+    }
 
     /** 기동한 서버. 프로세스가 소유하며, 시험은 이것으로 닫는다. */
     var started: Started? = null
@@ -209,6 +220,7 @@ class MimicCli {
                 link.reporter,
             ).start(),
             robots.keys,
+            built.toMap(),
         )
     }
 
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :mimic:test -q`
Expected: XML 기준 mimic 전체 357개 실패 0(`StartedInstanceTest` 1 포함).

- [ ] **Step 5: 커밋**

```bash
git add mimic/src/main/kotlin/dev/picasso/mimic/cli/Main.kt mimic/src/main/kotlin/dev/picasso/mimic/RobotInstance.kt mimic/src/test/kotlin/dev/picasso/mimic/cli/StartedInstanceTest.kt
git commit -q -F - <<'EOF'
feat(mimic): CLI 로 띄운 기체에 아는 사이트 명칭을 넣는 자리

- 작업 묶음 커밋(Task 7 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 4: 결함 주입(시험이 잡는가)

**Files:** 아래 파일들(넣고 되돌린다)

주입마다: 옛 문자열을 새 문자열로 바꾸고, 그 시험 클래스만 돌리고, XML 의 실패 시험 이름에 기대한 이름이 있는지 보고, 되돌린다. 한 번에 하나만 넣는다. 되돌린 뒤 `git status --short` 가 비어야 한다. 경로 머리 `registry/.../` 는 `registry/src/main/kotlin/dev/picasso/registry/` 다. 시험 클래스: `Ops` = `dev.picasso.registry.RevisionOperationsTest`, `Ep` = `dev.picasso.registry.web.RevisionEndpointTest`, `Boot` = `dev.picasso.registry.web.CatalogBootEndpointTest`(모두 `:registry:test --tests <클래스>`), `Mimic` = `dev.picasso.mimic.cli.StartedInstanceTest`(`:mimic:test --tests <클래스>`).

| ID | 파일 | 옛 → 새 | 클래스 | 빨개져야 할 시험 |
|---|---|---|---|---|
| B1 | `registry/.../binding/BindingService.kt` | `SELECT retired_at IS NOT NULL FROM robot WHERE robot_id = ? FOR UPDATE` → 끝의 ` FOR UPDATE` 지움 | Ops | `같은 기체의 동시 첫 바인딩은 하나만 남고 500 이 나지 않는다` |
| B2 | 같은 파일 | `} ?: return@transaction Binding.UnknownRobot` → `} ?: false` | Ops | `없는 기체·퇴역 기체·없는 개정판·없는 빌드를 그 순서로 가른다` |
| B3 | 같은 파일 | `if (retired) return@transaction Binding.RobotRetired` 줄 지움 | Ops | 위와 같음 |
| B4 | 같은 파일 | `if (same != null) return@transaction Binding.AlreadyBound(same)` 줄 지움 | Ops | `같은 조합을 다시 묶으면 같은 행이고 사이트 명칭 기록이 남는다` |
| B5 | 같은 파일 | `if (status != RevisionStatus.ACTIVE) return@transaction Binding.RevisionNotActive(status)` → 조건에 `&& status != RevisionStatus.SUPERSEDED` 더함 | Ops | `대체된 개정판의 같은 조합은 이미 됨이 아니라 활성 아님이다` |
| B6 | 같은 파일 | `val status = statusOf(c, profileRevisionId) ?: return@transaction Binding.UnknownRevision` → `?: RevisionStatus.DRAFT` | Ops | `없는 기체·퇴역 기체·없는 개정판·없는 빌드를 그 순서로 가른다` |
| S1 | `registry/.../revision/RevisionService.kt` | `if (row.hash == sha256(documentJson)) {` → `if (false) {` | Ops | `같은 문서의 재제출은 같은 개정판이고 감사는 한 번이다` |
| S2 | 같은 파일 | `submitDocument` 의 `SELECT 1 FROM capability_profile WHERE profile_id = ? FOR UPDATE` 문 4줄과 빈 줄 지움 | Ops | `같은 문서가 동시에 두 번 와도 하나는 만들고 하나는 그것을 돌려준다` |
| S3 | 같은 파일 | `Submitted.Existing(row.id, parsed.revision, row.status, row.reasons)` → 마지막 인자 `emptyList()` | Ops | `검증에 실패한 문서도 저장되고 DRAFT 와 사유를 돌려준다` |
| A1 | `registry/.../binding/BindingService.kt` | `if (status == RevisionStatus.ACTIVE) return@transaction Activation.AlreadyActive` 줄 지움 | Ops | `활성화는 옛 활성을 내리고 다시 누르면 아무것도 바꾸지 않는다` |
| A2 | 같은 파일 | `Activation.Refused(status, latestResults(c, profileRevisionId))` → `Activation.Refused(status, emptyMap())` | Ops | `활성화 거절은 상태와 스위트별 최신 결과를 싣고 없는 개정판은 따로다` |
| C1 | `registry/.../web/RegistryApplication.kt` | `val outcome = skillTypes.syncAtBoot(descriptor, dev.picasso.contracts.wire.ContractIdentity.semver)` → `val outcome: dev.picasso.registry.revision.BootSync = dev.picasso.registry.revision.BootSync.NoSchema` | Boot | `기동하면 스킬 종류가 계약과 같고 감사 행위자는 registry 다` |
| C2 | `registry/.../revision/SkillTypeCatalog.kt` | `const val ACTOR = "registry"` → `"sync"` | Ops | `기동 동기화 뒤 스킬 종류가 계약과 같고 감사 행위자는 registry 다` |
| C3 | 같은 파일 | `if (!hasSchema()) return BootSync.NoSchema` 줄 지움 | Ops | `기술자를 못 읽으면 기동을 거부하고 스키마가 없으면 건너뛴다` |
| C4 | 같은 파일 | `checkNotNull(descriptor) { … }` 줄 → `if (descriptor == null) return BootSync.NoSchema` | Ops | 위와 같음 |
| W1 | `registry/.../web/BindingOperationsController.kt` | `is Binding.AlreadyBound -> ResponseEntity.ok(` → `is Binding.AlreadyBound -> ResponseEntity.status(HttpStatus.CREATED).body(` | Ep | `바인딩은 201, 같은 조합 200, 404 와 409 는 reason 으로 갈린다` |
| W2 | 같은 파일 | `Binding.UnknownBuild -> notFound("UNKNOWN_BUILD"` → `notFound("UNKNOWN_REVISION"` | Ep | 위와 같음 |
| W3 | `registry/.../web/RevisionOperationsController.kt` | `is Submitted.Existing ->` 다음 줄 `ResponseEntity.ok(` → `ResponseEntity.status(HttpStatus.CREATED).body(` | Ep | `제출은 새로 201, 같은 문서 200, 단조 위반 409, 못 읽으면 400 이다` |
| W4 | `registry/.../diag/DiagnosticsService.kt` | `adapterVersionId = rs.getLong(13),` → `rs.getLong(5)` | Ep | `바인딩 진단 행이 빌드 id, 바인딩한 이, 명칭 기록과 보고 칸을 싣는다` |
| W5 | `registry/.../revision/RevisionListing.kt` | `ORDER BY profile_revision_id, suite, ran_at DESC, run_id DESC` → `ran_at ASC, run_id ASC` | Ops | `목록은 스위트별 최신 결과와 최신 시험 요청을 함께 싣는다` |
| W6 | `registry/.../binding/RobotRegistration.kt` | `"$robotId 은 ${existing.retiredAt} 에 퇴역한 기체다` → 옛 문자열 `"${'$'}robotId 은 ${'$'}{existing.retiredAt} 에 퇴역한 기체다` 로 되돌림 | Ep | `기체 거절 메시지가 기체 id 와 퇴역 시각을 값으로 찍는다` |
| W7 | `registry/.../web/OperationsController.kt` | `"모르는 기체다: ${outcome.robotId}"` → `${'$'}{outcome.robotId}` | Ep | 위와 같음 |
| W8 | `registry/.../diag/DiagnosticsService.kt` | `siteNamesReportedAt = rs.getTimestamp(18)` → `rs.getTimestamp(17)` | Ep | `바인딩 진단 행이 빌드 id, 바인딩한 이, 명칭 기록과 보고 칸을 싣는다` |
| W9 | 같은 파일 | `siteNamesUnsupported = rs.getBoolean(20).takeUnless { rs.wasNull() },` → `siteNamesUnsupported = null,` | Ep | 위와 같음 |
| M1 | `mimic/src/main/kotlin/dev/picasso/mimic/cli/Main.kt` | `Started(...)` 의 `built.toMap(),` 줄 지움 | Mimic | `기동한 기체에 넣은 명칭을 그 기체가 답한다` |

| D1 | `docs/commissioning.md` | `activation` 경로가 든 줄을 모두 지움(Step 2b 행, 3절 행, 4절 행) | `./gradlew :gate:test -q`(Task 5 뒤에 돌린다) | `설정 표면 목록이 바꾸는 문을 빠짐없이 적는다`(스탬프 시험도 함께 빨개진다) |

B1·S2 는 경쟁 조건에 기대는 주입이다. 잠금을 지워도 첫 스레드가 커밋한 뒤에 둘째가 닿으면 초록이 될 수 있으므로, 한 번 안 잡히면 같은 주입을 3번까지 다시 돌린다(정상 코드 쪽은 잠금이 있어 결정적이다). D1 은 경로가 문서 어디에든 남아 있으면 통과하는 시험이라, 행 하나만 지우면 등가 변이다(스파이크에서 바인딩 4절 행 하나만 지웠을 때 스탬프 시험만 빨개졌다).

스파이크에서 25건(D1 제외) 모두 기대한 이름이 빨개졌고 D1 도 기대대로였다. 처음 돌렸을 때 S2 와 W4 가 안 잡혔다. 기종의 첫 제출은 기종 행의 `INSERT … ON CONFLICT DO NOTHING` 이 둘째를 기다리게 해 잠금 없이도 차례가 지켜졌고(S2), 빈 DB 에서는 빌드 id 와 리비전 id 가 둘 다 1 이라 칸을 바꿔 읽어도 같았다(W4). 시험을 기종이 이미 있는 동시 제출과 두 id 가 갈리는 바인딩으로 고쳤고, 위 시험이 고친 버전이다.

- [ ] **Step 1: 코드 주입 25건을 하나씩** — 위 표대로(D1 은 Task 5 Step 2 뒤에 한다).
- [ ] **Step 2: 되돌림 확인**

Run: `git status --short`
Expected: 빈 출력.

### Task 5: 문서, 시험 수, 전체 빌드

**Files:**
- Modify: `docs/commissioning.md`(Step 1·2b·6b, 시운전 완료 판정, 3절·4절 표), `docs/superpowers/specs/2026-09-05-picasso-design.md`(§15.208), `docs/limits.md`(소비자 대기 1행, 번호 208), `README.md`(오픈 항목 63, 소비자 대기 10), `docs/verification.md`(1,931, `*EndpointTest` 여덟), `registry/README.md`(여덟), `CLAUDE.md`(1,931)

- [ ] **Step 1: 문서 패치** — 아래를 `C:/Users/Eisen/AppData/Local/Temp/p2b-patches/p2b-4.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/p2b-patches/p2b-4.patch`. 스탬프(`> 마지막 대조` 줄)까지 들어 있으므로 따로 `tools/stamp.py` 를 돌리지 않는다. 문장은 사용자 지시대로 Fable·Codex 초안을 취합한 것이다.

````diff
diff --git a/CLAUDE.md b/CLAUDE.md
index 52ccb68..a3b66bf 100644
--- a/CLAUDE.md
+++ b/CLAUDE.md
@@ -8,7 +8,7 @@
 ## 1. 빌드 및 테스트 명령
 
 ```bash
-# 전체 빌드 및 테스트 실행 (총 1,905개 테스트)
+# 전체 빌드 및 테스트 실행 (총 1,931개 테스트)
 ./gradlew build
 
 # 아키텍처 및 품질 게이트 검증만 실행
@@ -72,4 +72,4 @@
 - **저장소 경계 준수**: 본 저장소 밖의 다른 저장소 파일을 직접 생성하거나 수정하지 않습니다. 계층이 서로 다른 저장소에 위치하고 상호 참조하지 않는다는 사실 자체가 아키텍처 경계의 증명이며, 편의를 이유로 한 번 넘어가면 그 증명이 소멸합니다. 다른 저장소로 넘길 산출물은 `handoff/<받는 쪽>/` 에 두고 경로만 전달하며, 무엇을 반입할지는 받는 쪽이 결정합니다.
 - **한계점 및 히스토리 관리**: 미결 과제는 [`docs/limits.md`](docs/limits.md)에 기록하며, 설계 문서 `§15`의 변경 이력은 기존 항목을 삭제하지 않고 정정 내용을 누적 기록합니다.
 
-> 마지막 대조: 2026-10-08 · sha256:2150a711500d · 열림: 없음
+> 마지막 대조: 2026-10-08 · sha256:9cc3575296eb · 열림: 없음
diff --git a/README.md b/README.md
index 137dc84..0fc9722 100644
--- a/README.md
+++ b/README.md
@@ -91,7 +91,7 @@ docs/vendors/             로봇이 아닌 벤더 표면의 측정 노트 (플
 
 저장소 내 대외 문서 57종은 자동화 대조 검증을 완료한 상태입니다. 문서에 명시된 모든 기술적 주장은 자동화 테스트로 증명되거나, [`docs/limits.md`](docs/limits.md)의 오픈 항목 레지스터에 등록되어 추적 관리됩니다. 각 문서 하단의 대조 스탬프(Hash Stamp)은 본문 내용과 연결되어 있어, `CompletionCriterionTest`를 통해 임의 변경 시 스탬프 갱신을 요구합니다.
 
-한계 레지스터(`limits.md`)에 등록된 미결 항목은 **62개**(내부 28개 · 소비자 대기 9개 · 외부 25개, 의도적 제외 13개 제외)이며, 그 상세 목록과 해결 조건은 `limits.md`에 명시되어 있습니다. 특히 실물 어댑터가 넷 있다(기체 셋, 플릿 하나). 다만, 어댑터 넷 중 어느 것도 실물에 붙여 보지 못했다(C-3)는 물리적 검증 한계가 존재하며, 이는 SDK 라이선스, JVM 바인딩 부재, 플릿 실기체 인스턴스 부재 등에 기인합니다.
+한계 레지스터(`limits.md`)에 등록된 미결 항목은 **63개**(내부 28개 · 소비자 대기 10개 · 외부 25개, 의도적 제외 13개 제외)이며, 그 상세 목록과 해결 조건은 `limits.md`에 명시되어 있습니다. 특히 실물 어댑터가 넷 있다(기체 셋, 플릿 하나). 다만, 어댑터 넷 중 어느 것도 실물에 붙여 보지 못했다(C-3)는 물리적 검증 한계가 존재하며, 이는 SDK 라이선스, JVM 바인딩 부재, 플릿 실기체 인스턴스 부재 등에 기인합니다.
 
 실물 넷이 계약에 얼마나 닿나 확인한 정량 분석 결과는 [`profile/distance/`](profile/distance)에서 확인할 수 있습니다. 계약 개정판은 **0.9.0** 이다.
 
@@ -140,7 +140,7 @@ client --target <host:port> --robot <id> --requirements <file> --skill <type> [-
 | **벤더 인터페이스** | [`profile/vendors/`](profile/vendors) · [`docs/vendors/orbit.md`](docs/vendors/orbit.md) | 벤더 API 표면 분석 및 플릿 관리 인터페이스 측정 노트 |
 | **현장 전제조건** | [`docs/environment-preconditions.md`](docs/environment-preconditions.md) | 로봇 도입 현장의 인프라(도어, 바닥, 조명 등) 엔지니어링 전제조건 |
 | **벤더 매니페스트** | [`tools/vendor-manifest/README.md`](tools/vendor-manifest/README.md) | 어댑터의 사우스바운드 포트 벤더 심볼 인용 대조 검증 도구 |
-| **오픈 항목 과제 레지스터** | [`docs/limits.md`](docs/limits.md) | 미결 한계 항목 62개(내부·소비자 대기·외부) 및 해결 조건 관리 레지스터 |
+| **오픈 항목 과제 레지스터** | [`docs/limits.md`](docs/limits.md) | 미결 한계 항목 63개(내부·소비자 대기·외부) 및 해결 조건 관리 레지스터 |
 
 ## 핵심 엔지니어링 규율
 
@@ -151,4 +151,4 @@ client --target <host:port> --robot <id> --requirements <file> --skill <type> [-
 - **결함 주입(Mutation Testing)**: 테스트 케이스 작성 시 의도적 결함을 주입하여 검증 유효성을 선행 확인합니다.
 - **엄격한 실패 정책**: 사전 선언된 요구 검사 목록(`--require`)을 충족하지 못하는 경우 조용한 통과를 허용하지 않습니다.
 
-> 마지막 대조: 2026-10-08 · sha256:6dd5d46bfff0 · 열림: C-3, §15.81
+> 마지막 대조: 2026-10-08 · sha256:0e0ebe16bb61 · 열림: C-3, §15.81
diff --git a/docs/commissioning.md b/docs/commissioning.md
index 1200995..580cf7d 100644
--- a/docs/commissioning.md
+++ b/docs/commissioning.md
@@ -29,19 +29,21 @@ ISA-95 제조 통합 표준의 핵심 원칙에 따라 시스템 엔티티를 **
 | 단계 | 작업 내용 | 실행 위치 / API | 미완료 시 장애 영향 |
 |---|---|---|---|
 | **Step 0** | 인터페이스 계약 SemVer 버전 고정 | 빌드 설정 | 어댑터와 소비자 간 계약 버전 불일치 발생 |
-| **Step 1** | 사이트 및 스킬 카탈로그 초기화 | `GET /catalog` 로 조회 검증 | 로봇 케이퍼빌리티를 선언할 계약 어휘 부재 |
+| **Step 1** | 사이트 및 스킬 카탈로그 초기화 | registry 기동 시 계약 기술자(`/picasso.desc`)에서 스킬 종류 자동 동기화<br>`GET /operations/skill-types` 로 동기화 결과 조회 검증 | 로봇 케이퍼빌리티를 선언할 계약 어휘 부재. 기술자를 못 읽으면 registry 가 기동하지 않음 |
 | **Step 2** | 어댑터 제품 및 릴리스 빌드 등록 | `POST /operations/adapters`<br>`POST /operations/adapters/{adapterId}/versions` | 어댑터 인스턴스 등록 시 유효 빌드 참조 불가로 거부 |
+| **Step 2b** | 기종 프로파일 개정판 제출·시험·활성화 (시험 3종은 시험 실행기가 요청을 집어 보고) | `POST /operations/profile-revisions`<br>`POST /operations/profile-revisions/{profileRevisionId}/test-requests`<br>`POST /operations/profile-revisions/{profileRevisionId}/activation` | 활성 개정판 부재로 기체 바인딩이 `REVISION_NOT_ACTIVE` 로 거절됨 |
 | **Step 3** | **로봇 기체 내부 사이트(웨이포인트) 명칭 등록** | **현장 기체 직접 설정 작업** (Spot: Autowalk 웨이포인트 명명, Digit: add-object) | 계약이 전달하는 장소명을 로봇이 인식하지 못해 작업 요청이 `PARAMETER_INVALID` 로 거절됨 |
 | **Step 4** | 어댑터 런타임 인스턴스 등록 | `POST /operations/adapter-instances` | 기체 자동 발견 시 미승인 인스턴스 오류로 등록 차단 |
 | **Step 5** | 어댑터 인스턴스 프로세스 기동 | 배치 런처 / 컨테이너 | — |
 | **Step 6** | 로봇 기체 등록 승인 | 플릿 자동 발견: `POST /ingest/robots`<br>단일 직결 기체: `POST /operations/robots` | 레지스트리에 기체가 식별되지 않아 작업 배정 불가 |
+| **Step 6b** | 로봇 기체 바인딩(어댑터 빌드와 활성 개정판) | `POST /operations/robots/{robotId}/binding`<br>`GET /diag/bindings` 로 `adapterVersionId` 와 활성 바인딩 확인 | 사이트 명칭 요구 집합 부재로 명칭 기록이 404. 기체에 어느 어댑터·개정판으로 묶였는지 기록 부재 |
 | **Step 7** | 사이트 명칭 등록 기록 대조 | `POST /operations/site-names` 등록 후 검증 질의 | 기체 상태가 `CLAIMED` 에 머물며 명칭 오타 시 작업 실패 |
 | **Step 8** | 설비 센서 신호 연동 및 시간 윈도우 δ 설정 | `CellSignals` 인터페이스 구현 | 작업 완료 증명이 `E1` 등급으로 제한됨 |
 | **Step 9** | 소비자 요구조건 집합 등록 | `POST /requirements` | 기능 축소/변경 시 영향도 사전 계산 불가 (ADR 9) |
 
 > **주의 (Step 3의 중요성):** 3단계는 소프트웨어 배포가 아니라 현장에서 로봇 기체를 운용하며 환경 지도를 학습시키는 물리적 티칭 작업입니다. 계약은 표준 시맨틱 명칭을 전달하지만, 해당 명칭의 좌표 해석 권한은 기체 자체에 귀속됩니다 (ADR 35).
 > 
-> 시운전 완료 검증은 `GET /diag/robots` 및 `GET /diag/adapter-instances` 엔드포인트를 호출하여 모든 기체 상태가 `CONFIRMED` 로 전환되었는지 확인합니다.
+> 시운전 완료 검증은 세 조건으로 판정한다. `GET /diag/robots` 에서 원장 상태가 `CONFIRMED` 이고 퇴역이 아님, `GET /diag/bindings` 에서 활성 바인딩이 있음, `GET /operations/site-names` 에서 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 임을 확인한다.
 
 ---
 
@@ -61,7 +63,8 @@ ISA-95 제조 통합 표준의 핵심 원칙에 따라 시스템 엔티티를 **
 | **기체 퇴역(Retirement) 처리** | `POST /operations/robots/{robotId}/retirement` | 불필요 |
 | **기체 퇴역 취소(복귀)** | `DELETE /operations/robots/{robotId}/retirement` | 불필요 |
 | 사이트 명칭 등록 기록 갱신 | `POST /operations/site-names` | 불필요 |
-| 기종 케이퍼빌리티 프로파일 개정 | 레지스트리 개정 파이프라인 | 축소 개정 시 잔여 소비자 요구조건에 따라 사전 승인 검증 수반 |
+| 기체 바인딩 변경 | `POST /operations/robots/{robotId}/binding` | 불필요 (같은 조합 재요청 시 같은 바인딩 반환) |
+| 기종 케이퍼빌리티 프로파일 개정 | `POST /operations/profile-revisions`<br>`POST /operations/profile-revisions/{profileRevisionId}/test-requests`<br>`POST /operations/profile-revisions/{profileRevisionId}/activation` | 축소 개정 시 잔여 소비자 요구조건에 따라 사전 승인 검증 수반 |
 
 ### 기체 퇴역 관리 정책
 - **운영자 명시적 조작 원칙**: 어댑터 관측 시 일시적으로 기체가 검색되지 않는다고 해서 자동으로 퇴역 처리하지 않습니다. 통신 일시 단절은 관측 상태의 문제이며, 퇴역은 비즈니스적 판단입니다 (ADR 37).
@@ -76,18 +79,23 @@ ISA-95 제조 통합 표준의 핵심 원칙에 따라 시스템 엔티티를 **
 |---|---|---|
 | `POST /operations/adapter-instances` | 조작 (Operations) | 어댑터 빌드 버전, 호스트 주소, 플릿 엔드포인트 등록 |
 | `POST /operations/robots` | 조작 (Operations) | 운영자가 신규 기체를 수동으로 선언 등록 |
-| `POST /operations/robots/{robotId}/retirement` | 조작 (Operations) | 대상 기체를 가용 자원 목록에서 퇴역 처리 |
-| `DELETE /operations/robots/{robotId}/retirement` | 조작 (Operations) | 퇴역 처리된 기체의 가용 상태 복원 |
+| `POST /operations/robots/{robotId}/retirement` | 조작 (Operations) | 원장에서 기체를 퇴역으로 표시(바인딩은 풀지 않음) |
+| `DELETE /operations/robots/{robotId}/retirement` | 조작 (Operations) | 퇴역 처리된 기체를 원장에 복귀 |
 | `POST /operations/site-names` | 조작 (Operations) | 대상 기체에 사이트 명칭 세트가 등록되었음을 기록 |
 | `GET /operations/site-names` | 조작 (Operations) | 등록 기록과 기체 실제 응답 간의 대조 결과 조회 |
 | `POST /operations/adapters` | 조작 (Operations) | 어댑터 제품(vendor, name) 등록 |
 | `POST /operations/adapters/{adapterId}/versions` | 조작 (Operations) | 어댑터 빌드(version, 계약 SemVer) 등록 |
 | `GET /operations/adapters` | 조작 (Operations) | 등록된 제품과 빌드 목록 및 빌드별 적합성 상태 조회 |
+| `GET /operations/skill-types` | 조작 (Operations) | 지금 계약 semver 와 스킬 종류 목록 조회(기동 동기화 결과 검증) |
+| `POST /operations/profile-revisions` | 조작 (Operations) | 기종 프로파일 문서를 개정판으로 제출(같은 문서 재제출은 같은 개정판 반환, 번호 역행은 409) |
+| `GET /operations/profile-revisions` | 조작 (Operations) | 개정판별 상태·사유·문서 해시·스위트별 최신 결과·최신 시험 요청 조회 |
+| `POST /operations/profile-revisions/{profileRevisionId}/activation` | 조작 (Operations) | `TESTED`·`SUPERSEDED` 이고 세 스위트가 모두 PASS 인 개정판 활성화(이미 `ACTIVE` 면 200 `already`) |
 | `POST /operations/profile-revisions/{profileRevisionId}/test-requests` | 조작 (Operations) | 프로파일 개정판 시험 요청(스위트 3종) 등록 |
+| `POST /operations/robots/{robotId}/binding` | 조작 (Operations) | 기체를 어댑터 빌드와 활성 개정판에 바인딩(같은 조합 재요청은 같은 바인딩 반환, 거절 사유는 `reason`) |
 | `POST /ingest/robots` | 적재 (Ingest) | 어댑터가 플릿 관리자에서 자동 발견한 기체 정보 전송 |
 | `POST /ingest/test-requests/claim` | 적재 (Ingest) | 실행기의 시험 요청 집기(후보 문서 및 집은 시각 인출) |
 | `POST /ingest/test-requests/{requestId}/results` | 적재 (Ingest) | 실행기의 스위트 3종 결과 보고 및 `TESTED` 승격 |
-| `POST /ingest/handshake` | 적재 (Ingest) | 기동 시 어댑터 빌드 및 바인딩된 프로파일 정보 보고 |
+| `POST /ingest/handshake` | 적재 (Ingest) | 클라이언트 협상(`Negotiate`)의 요청과 응답을 보고해 소비자 요구 원장에 적재(어댑터 빌드 칸 없음, 바인딩과 대조하지 않음) |
 | `POST /ingest/liveness` | 적재 (Ingest) | 기체 주기적 하트비트(Liveness) 보고 |
 | `POST /ingest/task` | 적재 (Ingest) | 기체의 원자적 태스크 실행 관측치 수집 |
 | `POST /requirements` | 적재 (Ingest) | 상위 시스템/소비자가 요구하는 스킬 규격 집합 등록 (ADR 9) |
@@ -116,4 +124,4 @@ ISA-95 제조 통합 표준의 핵심 원칙에 따라 시스템 엔티티를 **
 - **비가역 차원 (계약)**: 인터페이스 계약(Contracts)의 변경은 소비자가 이미 생성된 stub 코드를 탑재하고 있으므로 즉각적인 롤백이 불가능합니다.
 - **가역 차원 (프로파일·어댑터·바인딩)**: 프로파일 재활성화, 이전 어댑터 재배포, 이전 바인딩 롤백을 통해 운영 중 안전하게 복구 가능합니다.
 
-> 마지막 대조: 2026-10-08 · sha256:9263c65e1df7 · 열림: §15.123, §15.106 · CLI, §15.128, §15.129
+> 마지막 대조: 2026-10-08 · sha256:1601b2dd0bc1 · 열림: §15.123, §15.106 · CLI, §15.128, §15.129
diff --git a/docs/limits.md b/docs/limits.md
index 30287d3..b2fd9a8 100644
--- a/docs/limits.md
+++ b/docs/limits.md
@@ -2,7 +2,7 @@
 
 본 문서는 `picasso` 미들웨어 아키텍처 및 구현 상에 존재하는 **알려진 한계(Known Limitations), 스코프 외 제외 항목, 기술 부채 및 해소 조건**을 체계적으로 추적 관리하기 위한 엔지니어링 레지스터입니다.
 
-설계 문서의 §15는 전체 변경 이력과 배경을 누적 기록하는 변경 이력이며 (번호가 207 까지 갔고), 본 문서는 현재 시점에서 유효한 오픈 항목 항목만을 분류하여 제공합니다. 해결 완료된 항목은 변경 이력에 `(닫힘)` 처리되고 본 레지스터에서는 정리됩니다.
+설계 문서의 §15는 전체 변경 이력과 배경을 누적 기록하는 변경 이력이며 (번호가 208 까지 갔고), 본 문서는 현재 시점에서 유효한 오픈 항목 항목만을 분류하여 제공합니다. 해결 완료된 항목은 변경 이력에 `(닫힘)` 처리되고 본 레지스터에서는 정리됩니다.
 
 ---
 
@@ -78,6 +78,7 @@
 
 | 출처 | 오픈 항목 과제 내용 | 해소 필요 조건 |
 |---|---|---|
+| §15.208 | **퇴역 기체가 `GET /catalog` 의 가용 수에 세어짐** — 퇴역은 바인딩을 풀지 않고, 카탈로그는 활성 바인딩으로 센다. 첫 소비자(picasso-ops S1d)는 `/catalog` 를 읽지 않는다 | `/catalog` 로 배정을 판단하는 소비자가 생길 때(ADR 9). 그때 퇴역 기체를 셈에서 빼거나 퇴역이 바인딩을 풀게 한다 |
 | §15.202 | **운영자 판단의 결정자가 밖으로 안 나감** — `resolve` 가 결정자를 받아 인시던트의 판단 기록에 남기지만(ADR 47), 내보내는 인시던트 줄에는 싣지 않음. 인시던트 번들을 읽는 쪽은 사람이 판단했다는 것과 무엇을 언제 판단했는지는 알지만 누구인지는 모름 | 읽는 쪽이 결정자를 쓰겠다고 할 때(ADR 9). 그때 조회 버전을 올리고 인시던트의 `approvedBy` 와 같은 `{id, kind}` 모양으로 실으며 인계 번들을 다시 산출 |
 | §15.181 | **골든셋의 설계가 코퍼스에 적재된 설계 일지에 그대로 있음** — 읽는 쪽은 코퍼스를 검색하므로 벤더 문서를 안 읽고 설계 일지를 읽어도 같은 답이 나옴. 근거로 원인을 추린 답과 설계 문서를 되읽은 답이 구별되지 않으며, 그 둘을 가르려고 만든 사건에서 그 구분이 사라짐. 실측으로는 지금 점수에 영향이 없으나(일지를 인용한 판들이 오히려 못 맞힘) 거짓 통과를 만들 수 있는 자리임. **일지는 삭제하지 않는 기록이라 뺄 수 없음**. **2026-09-23 에 한 번 넓어졌다** — §15.188 이 「사건 뒤의 탐색」 짝을 서술하면서 그 짝을 재는 항목의 정답을 그대로 적었고, 같은 문구가 용어집에도 들어갔다가 빠졌음. **2026-09-23 실측으로 좁혀짐** — 읽는 쪽의 사건 질의는 `spec`·`design_doc` 을 **필터로 빼고 있었고** 기록 다섯 판에서 그 필터가 실제로 먹었음. 일지가 근거에 든 것은 도메인 밖 질의뿐임. 그러므로 **채점 경로에서는 이 누수가 열려 있지 않았고**, 「설계 명세를 문서 단위로 빼는 것이 유일한 수단」이라고 적었던 앞 판의 문장은 과했음. 남는 위험은 그 필터가 없는 경로임 | 받는 쪽이 설계 일지를 근거로 든 답을 해당 지표에서 제외. 출처를 보고 하는 판정이므로 본 저장소에서는 막을 수 없고, 문서가 하나 더 새는 것만 시험이 막음 |
 | §15.180 | **인계하는 정답표가 「하나만 단정하면 오답」을 스스로 표현하지 못함** — 정답이 용어 목록이라 «있어야 할 말» 은 적히지만 «단정하면 안 되는 것» 은 못 적음. 원인이 둘인 인시던트의 후보를 `candidates` 로 따로 내고 사유에 채점 규칙을 적었으나, 그것을 **음성 조건으로 읽는 것은 받는 쪽 채점기의 몫**임. 그래서 후보 하나를 단정하면서 «정확히는 모른다» 를 덧붙인 응답이 용어 대조만으로는 정답으로 셈 | 받는 쪽이 `candidates` 를 음성 조건으로 읽거나, 정답표가 용어가 아닌 판정 규칙을 나르는 모양으로 바뀔 때 |
@@ -129,4 +130,4 @@
 - 하위 범주는 **해소의 주어**로 정합니다. 해소 조건에 적은 주어와 하위 범주가 어긋나면 `DocumentClaimsTest` 가 막습니다. 다만 주어를 용어로 부르지 않는 행은 기계가 못 가리므로 사람이 읽어야 합니다(§15.194).
 - 밖을 향한 문서가 드는 열림은 **132** 개다. 각 문서 하단 스탬프에 기재된 오픈 항목 ID 총합은 본 수치와 엄격히 일치해야 합니다 (`CompletionCriterionTest` 집행).
 
-> 마지막 대조: 2026-10-08 · sha256:08ccf6df75ff · 열림: 없음
+> 마지막 대조: 2026-10-08 · sha256:afcb6ec1636e · 열림: 없음
diff --git a/docs/superpowers/specs/2026-09-05-picasso-design.md b/docs/superpowers/specs/2026-09-05-picasso-design.md
index b3ff6bc..0601ce0 100644
--- a/docs/superpowers/specs/2026-09-05-picasso-design.md
+++ b/docs/superpowers/specs/2026-09-05-picasso-design.md
@@ -3098,6 +3098,18 @@ mimic/
 
     **검사 9번이 결속을 어댑터 경계 안에 가둔다.** 탐색어는 결속 파일이 선언한 최상위 이름에서 유도하므로 타입을 더하면 금지도 저절로 는다. 훑는 모듈에 `registry` 와 `profile-model` 을 넣었다 — 검사 7의 목록에 그 둘이 없어서, 없다는 이유로 결속까지 새면 같은 구멍이 두 번째로 열린다.
 
+208. **계약 스킬 종류를 기동 때 채우고 개정판 제출·활성화와 기체 바인딩의 조작 문 5개를 열었다.**
+
+    `SkillTypeSync` 를 부르는 곳이 시험 소스뿐이었다. 운영 registry 의 `skill_type` 은 비어 있었다. `skill_type` 이 비면 개정판 제출(`RevisionService` 의 `insertSkills`)이 선언 스킬을 조용히 건너뛴다. 그러면 사이트 명칭 요구 집합이 비고 바인딩의 계약 semver 검사도 비교할 것이 없다. 주석의 «그 부재는 바인딩이 보고 막는다» 는 사실이 아니었다. 개정판 제출·활성화·바인딩에 REST 가 없었고 `RevisionService` 는 빈(bean)도 없었다. 옛 `BindingService.bind` 는 없는 기체를 FK 위반 500 으로 냈고, 퇴역 기체도 묶었고, 같은 조합을 다시 묶으면 새 행을 만들어 사이트 명칭 등록 기록이 «미등록» 으로 돌아갔다. 같은 기체의 동시 첫 바인딩은 유일 색인 `robot_binding_one_active` 위반 500 이었다. `/diag/bindings` 행은 어댑터 이름과 버전만 내서 «요청한 빌드로 묶였는가» 를 id 로 판정할 수 없었다. mimic CLI 로 띄운 기체에는 기체가 아는 사이트 명칭(`knownSiteNames`)을 넣을 길이 없었다(기본값이 빈 목록이라 명칭을 기록하면 `CONTRADICTED`). 바깥 첫 소비자 picasso-ops 의 P2·S1d 설계 스펙(`docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md`, picasso-ops 저장소) §6 이 이것들을 P2b 로 정했다. 소비자가 생겨서 열었다(ADR 9).
+
+    기동 동기화(설계 §8.3 ④)를 지었다. registry 가 기동할 때 `ApplicationRunner` 가 자기 계약 기술자(`/picasso.desc`)와 `ContractIdentity.semver` 로 `SkillTypeSync` 를 부른다(`SkillTypeCatalog.syncAtBoot`). 감사 `SKILL_TYPE_SYNC` 의 행위자는 `registry` 다. 기술자를 못 읽으면 기동을 거부한다. 빈 카탈로그로 뜨면 위의 «조용히 건너뜀» 상태로 돌아가기 때문이다. 스키마가 없으면(`skill_type` 표가 없음) 경고를 남기고 건너뛴다. 마이그레이션은 런처의 몫이라 registry 가 돌리지 않고, 스키마 없는 registry 는 어느 조작도 못 하므로 조용히 건너뛸 스킬도 없다. 기존 표면 시험은 컨텍스트가 뜬 뒤 시험마다 스키마를 지우므로 영향이 없었다.
+
+    registry 에 조작 문 5개를 열었다(모두 운영자 토큰, 조작 POST 는 `X-Actor` 필수). `GET /operations/skill-types` 는 지금 계약 semver 와 스킬 종류 목록(이름, major, 처음 본 계약 semver, 사이트 명칭 키)을 낸다. `POST /operations/profile-revisions`(본문은 프로파일 문서 JSON 그대로)는 201 저장(개정판 id·번호·상태·사유, 검증 실패도 `DRAFT` 로 201), 200 같은 기종·같은 번호·같은 문서 해시의 재제출(같은 개정판), 409 번호가 단조 증가하지 않음(같은 번호의 다른 문서 포함, 본문 `received`·`highest`), 400 문서로 읽을 수 없음이다. `GET /operations/profile-revisions` 는 개정판마다 상태, 사유, 문서 해시, 제출·활성화한 이와 시각, 스위트별 최신 결과(결과·시각·실행 주체·상세), 최신 시험 요청 1건(요청·집기·만료·끝난 시각)을 낸다. `POST /operations/profile-revisions/{profileRevisionId}/activation` 은 200 활성화(내려간 옛 활성 id), 200 이미 `ACTIVE`(바꾸지 않음, 본문 `already`), 409 상태가 `TESTED`·`SUPERSEDED` 가 아니거나 세 스위트 최신 결과가 모두 PASS 가 아님(본문 `status`·`suites`), 404 없는 개정판이다. `POST /operations/robots/{robotId}/binding`(본문 `adapter_version_id`·`profile_revision_id`, 선택 `reason`)은 201 새 바인딩(해제한 이전 바인딩 id), 200 같은 조합이 이미 활성(행과 사이트 명칭 기록 유지), 404 `reason` 이 `UNKNOWN_ROBOT`·`UNKNOWN_REVISION`·`UNKNOWN_BUILD`, 409 `reason` 이 `ROBOT_RETIRED`·`REVISION_NOT_ACTIVE`·`CONTRACT_TOO_OLD`, 400 두 id 중 하나가 빠짐이다. 바인딩 검사 순서는 기체 있음 → 퇴역 아님 → 개정판 있음 → 빌드 있음 → 개정판 활성 → 계약 semver → 같은 조합이다. 같은 조합 검사를 맨 뒤에 둔 것은 요청이 지금 유효할 때만 «이미 됨» 이라 답하려는 것이다. 그래서 묶인 뒤 개정판이 대체됐으면 같은 조합이어도 409 `REVISION_NOT_ACTIVE` 다. 바인딩은 기체 행을, 제출과 활성화는 기종(`capability_profile`) 행을 잠그고 진행한다. 같은 요청이 동시에 두 번 오면 500 대신 하나는 만들고 하나는 그것을 돌려준다. 결과 타입은 P1 과 같은 방식이다. `RevisionService.submitDocument` → `Submitted`, `BindingService.activateRevision` → `Activation`, `BindingService.bindRobot` → `Binding` 이다. 옛 메서드와 그 시험은 그대로다. 옛 `activate` 는 새 메서드에 위임하며 동작이 바뀌지 않았다(이미 `ACTIVE` 면 전처럼 거부). 옛 `submit`·`bind` 는 남기고 저장과 바인딩 기록 부분만 새 메서드와 공유한다. 멱등은 새 메서드에만 있다. 감사는 새로 만들 때만 남는다.
+
+    `/diag/bindings` 행에 칸을 더했다(기존 칸은 그대로). `adapterVersionId`, `boundBy`·`boundAt`, `siteNamesRegisteredBy`·`siteNamesRegisteredAt`(사람의 기록), `siteNamesReportedAt`·`siteNamesCount`·`siteNamesUnsupported`(기체의 답, 널은 아직 안 물어봄)다. mimic 은 `MimicCli.start` 가 돌려주는 `Started` 에 `instance(robotId)` 를 더해 담는 쪽이 기체가 아는 명칭을 넣을 수 있다(현장의 명칭 티칭 흉내, 제어 서버 없이). `RobotInstance.knownSiteNames` 를 `@Volatile` 로 했다(쓰는 스레드와 생존 보고를 발행하는 스레드가 다름). 함께 고친 것은 넷이다. `RevisionService` 주석(«바인딩이 보고 막는다» 가 사실이 아님), `/diag/bindings` 행 KDoc 의 명칭 상태(3값으로 적혀 있었고 실제는 5값), 퇴역 기체 재등록 거절 메시지가 기체 id 와 퇴역 시각 대신 `$robotId`·`${existing.retiredAt}` 를 글자 그대로 찍던 것, 모르는 기체의 복귀 거절이 `${outcome.robotId}` 를 글자 그대로 찍던 것이다.
+
+    시험 26개를 더해 총수가 1,905 에서 1,931 이 됐다. 서비스 `RevisionOperationsTest` 14, 표면 `RevisionEndpointTest` 10, 기동 `CatalogBootEndpointTest` 1(기동 전에 스키마를 세우고 기동 뒤 손으로 넣지 않음), mimic `StartedInstanceTest` 1 이다. 기존 시험은 고치지 않고 통과했다. 결함 주입 25건이 모두 이름 있는 시험으로 잡혔다. 바인딩 6: 기체 행 잠금 제거, 기체 있음 검사 제거, 퇴역 검사 제거, 같은 조합 검사 제거, 대체된 개정판을 활성으로 봄, 개정판 있음 검사 제거. 제출 3: 해시 같음 판정 제거, 기종 행 잠금 제거, 재제출이 사유를 버림. 활성화 2: 이미 활성 판정 제거, 거절 본문의 스위트 결과 비움. 카탈로그 4: 기동 동기화 호출 제거, 감사 행위자 바꿈, 스키마 없음 판정 제거, 기술자 없음을 거부 대신 건너뜀. 문 9: 같은 조합 200 을 201 로, `UNKNOWN_BUILD` 를 `UNKNOWN_REVISION` 으로, 같은 문서 재제출 200 을 201 로, 진단 행의 빌드 id 를 다른 칸으로, 목록의 최신 결과를 가장 옛것으로, 퇴역 메시지를 글자 그대로로 되돌림, 모르는 기체 메시지를 글자 그대로로 되돌림, 진단 행의 명칭 보고 시각을 등록 시각 칸으로, 호스팅 불가 칸을 널로. mimic 1: 인스턴스를 넘기지 않음. 처음 돌린 주입에서 둘이 안 잡혔다. 기종의 첫 제출은 기종 행의 `INSERT … ON CONFLICT DO NOTHING` 이 둘째를 기다리게 해 잠금이 없어도 차례가 지켜졌고, 빈 DB 에서는 빌드 id 와 개정판 id 가 둘 다 1 이라 진단 칸을 바꿔 읽어도 같았다. 시험을 기종이 이미 있는 동시 제출과 두 id 가 갈리는 바인딩으로 고쳐 둘 다 잡았다. 남긴 것은 셋이다. 바인딩의 major 일치 검사(설계 §9.1)는 더하지 않았다(지금처럼 개정판 스킬의 최초 semver 와 빌드의 계약 semver 크기만 비교). 퇴역은 바인딩을 풀지 않아 퇴역 기체가 `GET /catalog` 의 가용 수에 들어간다(한계 대장 소비자 대기). 핸드셰이크와 바인딩의 대조, mimic 이 바인딩을 가져가는 것은 없다.
+
 207. **개정판 시험 3종의 뜻을 정하고 실행기를 지어 요청 → 집기 → 3종 → 보고 → `TESTED` 고리를 닫았다.**
 
     설계 §8.4 ② 가 적은 «시험 요청 적재 → harness 폴링 인출 → 가상화 계약 검증 스위트 완주 → TESTED» 고리는 지어지지 않은 채였다. `V2__testing.sql` 주석이 «폴링 고리(harness 쪽)는 3a-2다» 로 미뤘고, `harness` 의 `Suite` 열거형을 쓰는 코드가 없었으며, `TestRequestService.claim` 과 `BindingService.recordTestRun` 은 시험 소스만 불렀다. 시험 요청에 «끝남» 칸이 없어 집은 요청은 15분 만료 뒤 다시 집혔다. 바깥 첫 소비자 picasso-ops 의 P2·S1d 설계 스펙(`docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md`, picasso-ops 저장소)이 화면에서 개정판을 시험 요청하고 진짜 실행기가 시험하게 정했다(2026-10-07 사용자 결정). 실행기는 picasso-ops 의 가짜 현장(`site/`) 프로세스가 띄운다. 소비자가 생겨서 고리를 지었다(ADR 9).
diff --git a/docs/verification.md b/docs/verification.md
index 57b59bf..e5c9385 100644
--- a/docs/verification.md
+++ b/docs/verification.md
@@ -1,6 +1,6 @@
 # 시스템 검증 충실도 및 환경 신뢰도 매트릭스 (Verification & Fidelity Matrix)
 
-본 문서는 `picasso` 미들웨어 시스템의 1,905개 자동화 테스트가 **어느 구간에서 실제 외부 시스템/하드웨어와 연동되고, 어느 구간에서 모의 대역(Mock/In-process)에 의존하는지**를 명확히 구분하여 기술적 검증 신뢰도(Verification Fidelity)를 투명하게 공개하기 위해 작성되었습니다.
+본 문서는 `picasso` 미들웨어 시스템의 1,931개 자동화 테스트가 **어느 구간에서 실제 외부 시스템/하드웨어와 연동되고, 어느 구간에서 모의 대역(Mock/In-process)에 의존하는지**를 명확히 구분하여 기술적 검증 신뢰도(Verification Fidelity)를 투명하게 공개하기 위해 작성되었습니다.
 
 ---
 
@@ -30,7 +30,7 @@
 | 5 | 어댑터 ↔ 벤더 — **Orbit** | **실 회선 · 세운 상대** | **벤더 공식 명세** (OpenAPI + SDK) | 실제 HTTP/HTTPS 프로토콜(쿠키, Bearer 토큰, 상태코드)을 통과하며, 응답 서버는 `com.sun.net.httpserver` 기반 스텁 연동 | `OrbitHttpLinkTest` · `OrbitLauncherTest` |
 | 6 | 어댑터 ↔ 벤더 — **Spot · Digit · G1** | **없음** | **벤더 공식 명세** (SDK Proto·IDL·매뉴얼) | 저장소 내 벤더 독점 SDK 배제 원칙에 따라 네트워크 전송은 수행하지 않으며, `@VendorSurface` 선언과 `vendor-manifest.txt` 간의 심볼 대조 검증 수행 | `*VendorSurfaceTest` 넷 |
 | 7 | 발행(MQTT) | **실제 하드웨어** | 자체 토픽·헤더 규격 (§5.5) | Docker 컨테이너 기반 실제 Mosquitto 브로커와 연동하여 토픽 발행/구독, QoS, Last Will 정상 동작 검증 | `MqttBrokerTest` |
-| 8 | 레지스트리 HTTP API | **실제 하드웨어** | 자체 REST API | Spring Boot 임의 포트(`RANDOM_PORT`)에 실제 구동하여 `TestRestTemplate` 기반 HTTP 통합 검증 | `*EndpointTest` 여섯 |
+| 8 | 레지스트리 HTTP API | **실제 하드웨어** | 자체 REST API | Spring Boot 임의 포트(`RANDOM_PORT`)에 실제 구동하여 `TestRestTemplate` 기반 HTTP 통합 검증 | `*EndpointTest` 여덟 |
 | 9 | 레지스트리 ↔ DB | **실제 하드웨어** | 자체 DB 스키마 | Testcontainers 기반 PostgreSQL 16 컨테이너에 대해 Flyway 마이그레이션 및 외래키/CHECK 제약조건 검증 | `registry` 테스트 스위트 전체 |
 | 10 | 설비(PLC/WCS) ↔ `picasso` | **대역** | **자체 정의** | `CellMimic`을 통해 시간 윈도우 δ 기반 신호 수신 로직을 검증하나, 신호 스펙은 공장 표준 사례 기반의 자체 모델링임 | `EvidenceWindowTest` |
 | 11 | AMR 플릿 ↔ `picasso` | **대역** | **자체 정의** | `AmrFleetMimic` 기반 멱등 이송 작업 지시(Dispatch) 및 취소 정리를 검증하나, 상용 플릿 규격(VDA5050 등)과의 직접 연동은 미수행 | `DeliverContainerTest` |
@@ -45,4 +45,4 @@
 2. **로봇 인터페이스 계층의 격리성**: 어댑터 계층은 벤더 SDK 격리 원칙에 따라 매니페스트 대조를 통해 정합성을 검증하며, 실기체 직접 연동(C-3)은 환경적 제약으로 인해 오픈 항목 상태로 명시 관리됩니다.
 3. **상위 및 설비 연계 계층의 가정 기반성**: 설비(PLC) 및 AMR 플릿과의 연동 규격은 시스템적 일관성을 입증하기 위한 자체 설계 모델이며, 실제 현장 도입 시 대상 설비에 맞춘 Seam 어댑터 구현이 요구됩니다.
 
-> 마지막 대조: 2026-10-08 · sha256:e373b7b2736e · 열림: C-3
+> 마지막 대조: 2026-10-08 · sha256:98b68840f576 · 열림: C-3
diff --git a/registry/README.md b/registry/README.md
index 2cfa207..2dd362e 100644
--- a/registry/README.md
+++ b/registry/README.md
@@ -39,7 +39,7 @@
 
 - **실제 PostgreSQL 16 연동 (Testcontainers)**: 임베디드 H2 대신 실제 PostgreSQL 컨테이너 환경에서 실행되어 테이블 제약 조건, CHECK 제약, 외래키(FK) 무결성을 엄밀히 검증합니다.
 - **실제 HTTP 엔드포인트 통합 테스트**: `TestRestTemplate`과 임의 포트 바인딩을 통해 HTTP 응답 상태 코드, 이원화 토큰 인증 및 JSON 직렬화를 실제 통신 환경에서 검증합니다.
-- `web/*EndpointTest` 여섯이 전체 REST API 표면 계약을 검증하고, `binding/`, `plan/`, `ledger/` 단위 테스트가 비즈니스 규칙을 담당합니다.
+- `web/*EndpointTest` 여덟이 전체 REST API 표면 계약을 검증하고, `binding/`, `plan/`, `ledger/` 단위 테스트가 비즈니스 규칙을 담당합니다.
 - 상위 통합 검증은 `harness` 모듈에서 담당합니다: `LedgerIngestEndToEndTest` (에뮬레이터 경로), `HostIngestEndToEndTest` (어댑터 경로), `OrbitDiscoveryEndToEndTest` (동적 발견 경로).
 
 ---
@@ -50,4 +50,4 @@
 - **원장 적재의 비동기 메시지 브로커 구독기 미구현 (§15.34)**: 상태 발행은 외부 MQTT 브로커로 전달되나, 브로커로부터 이벤트를 읽어 원장에 자동 반영하는 컨슈머가 아직 구현되지 않아 현재는 프로세스 내 직접 적재 방식을 병행합니다.
 - **원장 정합성의 계약 인터페이스 경유 의존성 (§15.10)**: 모든 클라이언트가 본 인터페이스 계약을 통과할 때만 원장의 완전성이 보증됩니다.
 
-> 마지막 대조: 2026-10-08 · sha256:5957ab3ea644 · 열림: §15.5, §15.10, §15.34, §15.38
+> 마지막 대조: 2026-10-08 · sha256:c04fc7a53ee6 · 열림: §15.5, §15.10, §15.34, §15.38
````

- [ ] **Step 2: 전체 빌드** — 백그라운드로 돌린다.

Run: `./gradlew build --continue -q`
Expected: XML 기준 1,931개 실패 0(registry 396, mimic 357, gate 276 포함). 실패가 `자동화 시험의 수를 대외 문서가 맞게 적는다` 면 시험 수, `설정 표면 목록이 바꾸는 문을 빠짐없이 적는다` 면 `commissioning.md` 4절, 스탬프 관련이면 Step 1 패치가 덜 들어간 것이다.

- [ ] **Step 3: 커밋과 대조**

```bash
git add docs/commissioning.md docs/superpowers/specs/2026-09-05-picasso-design.md docs/limits.md README.md docs/verification.md registry/README.md CLAUDE.md
git commit -q -F - <<'EOF'
docs(commissioning): 개정판·바인딩 조작 문과 시운전 완료 판정, 시험 수

- 작업 묶음 커밋(Task 7 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
bash C:/Users/Eisen/AppData/Local/Temp/p2b-cmp.sh $(git diff --name-only 2370ed3 HEAD)
```
Expected: 23줄 모두 «같음».

- [ ] **Step 4: D1 주입** — Task 4 표의 D1. 되돌린 뒤 `git status --short` 가 비어야 한다.

### Task 6: 새 클론 검증

- [ ] **Step 1:** 워크트리(`C:/Users/Eisen/Desktop/Labs/picasso-wt/p2b-revision-binding-rest`)의 커밋을 짧은 경로(`C:/Users/Eisen/AppData/Local/Temp/p2b-clean`)에 `git clone -b feat/revision-binding-rest <워크트리> <짧은 경로>` 로 새로 클론해 `./gradlew build --continue -q` 를 돌린다. Expected: XML 기준 1,931개 실패 0. 커밋되지 않은 파일에 기대는 것이 없음을 본다.

### Task 7: 합치기와 PR

- [ ] **Step 1: 하나로 합치기** — 묶음 커밋 넷(Task 1·2·3·5)을 `git reset --soft 2370ed3` 으로 합치고, 커밋 메시지는 Fable·Codex 초안을 취합해 heredoc 으로 쓴다. 트리 해시가 합치기 전과 같아야 한다(`git rev-parse HEAD^{tree}` 비교).
- [ ] **Step 2: 푸시와 PR** — 사용자 승인 뒤. PR 본문은 개요 / 주요 변경 사항 / 검증 결과 세 절이며 Fable·Codex 초안 취합, 끝 줄 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. 머지 뒤에는 khala·narrator 에 메인 체크아웃을 당긴다고 예고하고 둘 다 열린 창이 없다고 답하면 당긴다.

## 실행 결과

- 수행: 묶음 둘(Task 1·2, Task 3·5)을 하위 에이전트가 수행, 묶음마다 커밋된 파일을 스크래치 코드와 바이트 대조해 23개 모두 동일, 최종 트리 해시도 동일(`9c609f0`)
- 결함 주입: 25건(바인딩 6, 제출 3, 활성화 2, 카탈로그 4, 문 9, mimic 1) 모두 지정 시험이 탐지, 문서 주입 D1 은 게이트가 탐지
- 전체 빌드: 새 클론에서 시험 XML 1,931개, 실패 0(registry 396, mimic 357, gate 276), 기존 시험은 손대지 않고 통과
- 병합: 묶음 커밋 넷을 하나로 합침(`9fba89f`, 트리 동일), picasso PR #81 의 CI `build` job 초록, 2026-10-08 05:52 KST 머지(머지 커밋 `41beedb`)
- 걸린 것: 스파이크 첫 주입에서 두 건이 안 잡혀 시험 보강(기종의 첫 제출은 `INSERT … ON CONFLICT DO NOTHING` 이 잠금 없이도 차례를 지킴, 빈 DB 에서 빌드 id 와 리비전 id 가 둘 다 1), `tools/stamp.py` 가 파일 하나씩만 받고 작업 트리를 LF 로 써서 CRLF 로 되돌림, 같은 기체 경로가 문서에 여러 번 나와 문서 주입은 경로가 든 줄을 모두 지워야 탐지
- 다음: 같은 스펙의 S1d(서브모듈을 `41beedb` 로 옮김)
