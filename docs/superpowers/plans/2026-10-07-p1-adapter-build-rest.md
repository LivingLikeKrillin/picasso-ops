# P1 어댑터 제품·빌드 REST Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** picasso registry 에 어댑터 제품·빌드를 등록·조회하는 운영자 REST 3개(`POST /operations/adapters`, `POST /operations/adapters/{adapterId}/versions`, `GET /operations/adapters`)를 더해 PR 로 올린다.

**Architecture:** `AdapterService` 에 결과를 가르는 새 메서드 `declareAdapter`·`declareVersion`·`list` 를 더하고, 옛 `registerVersion` 은 새 메서드에 위임해 한 SQL 경로만 남긴다(`registerAdapter` 는 호출 18곳·harness 3파일이 있어 그대로 둔다). 새 컨트롤러 `AdapterOperationsController` 가 `/operations/adapters` 아래를 맡고, 운영자 토큰 관문(`/operations/**`)은 경로로 자동 적용된다. 문서는 `docs/commissioning.md`, 설계 일지 §15.206, 일지 번호를 대는 `docs/limits.md`, 시험 수를 적는 `CLAUDE.md`·`docs/verification.md`, 문 시험 수를 적는 `docs/verification.md`·`registry/README.md` 를 고친다.

**스펙 §5·§10 과 다른 결정(이 계획이 정함):** 스펙은 옛 `registerVersion` 의 같은 버전 재등록 거절을 바꾸고 `AdapterLifecycleTest` 의 `같은 버전을 두 번 등록하면 거부한다` 를 고친다고 적었다. 이 계획은 **옛 메서드의 그 거절을 유지**하고, 멱등(같은 내용 재요청 200)은 새 조작 문(`declareVersion`)에만 둔다. 이유: 옛 메서드는 시험·하네스가 부르고, 그 호출자들은 멱등을 기대하지 않는다. 대신 옛 `registerVersion` 의 동작이 2곳 바뀐다: 모르는 제품이 FK 예외 대신 `Rejected`, 빈 `version` 이 `Rejected`. picasso-ops 스펙의 정정은 Task 7 이 한다.

**Tech Stack:** Kotlin 2.4.20, Spring Boot 3.4.0(MVC), PostgreSQL + Flyway(Testcontainers, registry `testFixtures` 의 `PostgresSupport`), JUnit5 + kotlin.test, `TestRestTemplate`.

**근거 스펙:** picasso-ops `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` §5(P1), §11(사실 표).

**작업 위치 규칙(필수):**
- picasso 메인 체크아웃(`C:\Users\Eisen\Desktop\Labs\[projects] picasso`)에서 docs/ 를 고치지 않는다. khala 가 그 작업 트리의 docs/ 를 매시간 코퍼스로 읽는다. 모든 작업은 저장소 밖 워크트리에서 한다.
- `./gradlew --stop` 금지(데몬 풀이 사용자 체크아웃과 공유된다).
- `git add -A` 금지. 파일을 이름으로 더한다.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다.
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. 커밋·PR 문장은 저장소 형식 훅(`.claude/hooks/check-commit-pr-format.py`)을 통과해야 한다(제목 `type(scope): 명사구`, 불릿 명사형, «~다» 종결 금지, em-dash·en-dash·겹화살괄호·낫표 금지). 문서·일지·커밋·PR 의 문장은 사용자 지시에 따라 Fable 과 Codex 에 같은 브리프로 초안을 받아 취합한다(Gemini 한도 소진 중).

---

## Chunk 1: 서비스 결과 타입과 시험

### Task 0: 워크트리와 기준선

**Files:** 없음(환경)

- [ ] **Step 1: 워크트리 만들기**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso"
git fetch origin -q
git worktree add "C:/Users/Eisen/Desktop/Labs/picasso-wt/p1-adapter-rest" -b feat/adapter-build-rest origin/main
git -C "C:/Users/Eisen/Desktop/Labs/picasso-wt/p1-adapter-rest" branch --unset-upstream
```
Expected: `HEAD is now at cd688ff`(또는 그 뒤의 origin/main). 이후 모든 명령은 워크트리 경로에서 돈다.

- [ ] **Step 2: Docker 확인**

Run: `docker info --format '{{.ServerVersion}}'`
Expected: 버전 문자열. 안 나오면 멈추고 사용자에게 알린다(registry 시험은 Testcontainers 가 필요하다).

- [ ] **Step 3: 기준선 시험**

Run: `./gradlew :registry:test :harness:test :gate:test :picasso:test --continue -q`
Expected: XML 기준 네 모듈 실패 0. 실패가 있으면 멈추고 보고한다(기준선 실패를 새 변경과 섞지 않는다). `:harness:test` 는 `registerVersion` 위임이 바꾸는 호출 경로(하네스 시험 3파일)를 덮는다.

### Task 1: `declareAdapter` 와 결과 타입

**Files:**
- Modify: `registry/src/main/kotlin/dev/picasso/registry/adapter/AdapterService.kt`
- Create: `registry/src/test/kotlin/dev/picasso/registry/AdapterDeclarationTest.kt`

- [ ] **Step 1: 실패하는 시험 쓰기**

```kotlin
package dev.picasso.registry

import dev.picasso.registry.adapter.AdapterDeclared
import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.store.Db
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * P1 — 조작 문이 쓰는 결과. **새로 만들었는지 이미 있었는지를 가른다.**
 *
 * 옛 `registerAdapter` 는 `Long` 하나만 돌려줘 201 과 200 을 가를 수 없었고, 옛 `registerVersion` 은 형식 오류와
 * 중복을 `Rejected` 하나로 접었다. 표면이 400·404·409 를 따로 내려면 서비스가 먼저 갈라야 한다.
 */
class AdapterDeclarationTest {

    private lateinit var adapters: AdapterService

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
        adapters = AdapterService(Db(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password))
    }

    private fun count(table: String): Int =
        PostgresSupport.queryOne("SELECT count(*) FROM $table") { it.getInt(1) }!!

    @Test
    fun `제품을 처음 선언하면 Created, 다시 선언하면 같은 id 로 Existing`() {
        val first = assertIs<AdapterDeclared.Created>(adapters.declareAdapter("acme", "drv", "op"))
        val again = assertIs<AdapterDeclared.Existing>(adapters.declareAdapter("acme", "drv", "op"))
        assertEquals(first.adapterId, again.adapterId)
        assertEquals(1, count("adapter"))
    }

    @Test
    fun `같은 제품의 재선언은 감사 기록을 다시 남기지 않는다`() {
        adapters.declareAdapter("acme", "drv", "op")
        adapters.declareAdapter("acme", "drv", "op")
        assertEquals(
            1,
            PostgresSupport.queryOne("SELECT count(*) FROM audit_log WHERE operation = 'ADAPTER_REGISTER'") { it.getInt(1) },
        )
    }

    @Test
    fun `vendor 나 name 이 비면 거절하고 저장하지 않는다`() {
        assertIs<AdapterDeclared.Rejected>(adapters.declareAdapter("", "drv", "op"))
        assertIs<AdapterDeclared.Rejected>(adapters.declareAdapter("acme", " ", "op"))
        assertEquals(0, count("adapter"))
    }
}
```

- [ ] **Step 2: 시험이 컴파일 실패하는지 확인**

Run: `./gradlew :registry:test --tests '*AdapterDeclarationTest*' -q`
Expected: 컴파일 실패 `Unresolved reference 'AdapterDeclared'`(아직 없다).

- [ ] **Step 3: 최소 구현**

`AdapterService.kt` 의 `RegisterOutcome` 정의 아래에 더한다:

```kotlin
/** P1 — 조작 문의 제품 선언 결과. 201(Created)과 200(Existing)을 가르려고 둔다. */
sealed interface AdapterDeclared {
    data class Created(val adapterId: Long) : AdapterDeclared

    data class Existing(val adapterId: Long) : AdapterDeclared

    data class Rejected(val detail: String) : AdapterDeclared
}
```

`AdapterService` 클래스 안, `registerAdapter` 아래에 더한다:

```kotlin
    /**
     * P1 — 조작 문의 제품 선언. [registerAdapter] 와 달리 **새로 만들었는지 이미 있었는지를 돌려준다.**
     *
     * 감사 기록은 새로 만들 때만 남긴다 — 같은 선언의 재시도가 감사 로그를 불리지 않게.
     * [registerAdapter] 는 시험·하네스 호출이 많아 그대로 둔다(반환형을 바꾸면 하네스 시험이 로컬 표준 빌드
     * 밖에서만 깨진다).
     */
    fun declareAdapter(vendor: String, name: String, actor: String): AdapterDeclared {
        if (vendor.isBlank() || name.isBlank()) return AdapterDeclared.Rejected("vendor 와 name 은 비울 수 없다")
        return db.transaction { c ->
            val inserted = c.prepareStatement(
                "INSERT INTO adapter (vendor, name) VALUES (?, ?) ON CONFLICT (vendor, name) DO NOTHING",
            ).use { it.setString(1, vendor); it.setString(2, name); it.executeUpdate() } == 1

            val id = c.prepareStatement(
                "SELECT adapter_id FROM adapter WHERE vendor = ? AND name = ?",
            ).use { s ->
                s.setString(1, vendor); s.setString(2, name)
                s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
            }
            if (inserted) {
                audit(c, actor, "ADAPTER_REGISTER", "$vendor/$name")
                AdapterDeclared.Created(id)
            } else {
                AdapterDeclared.Existing(id)
            }
        }
    }
```

- [ ] **Step 4: 시험 통과 확인**

Run: `./gradlew :registry:test --tests '*AdapterDeclarationTest*' -q`
Expected: XML `TEST-dev.picasso.registry.AdapterDeclarationTest.xml` 에 tests=3, failures=0.

- [ ] **Step 5: 커밋하지 않고 다음 Task 로**(P1 은 커밋을 Task 6 에서 한 번에 한다. 단위마다 커밋하려면 그래도 되지만 메시지는 훅 형식을 지킨다.)

### Task 2: `declareVersion` 과 옛 `registerVersion` 위임

**Files:**
- Modify: `registry/src/main/kotlin/dev/picasso/registry/adapter/AdapterService.kt`
- Modify: `registry/src/test/kotlin/dev/picasso/registry/AdapterDeclarationTest.kt`

- [ ] **Step 1: 실패하는 시험 더하기**(`AdapterDeclarationTest` 안에). 파일 머리에 import 3줄을 더한다:

```kotlin
import dev.picasso.registry.adapter.RegisterOutcome
import dev.picasso.registry.adapter.VersionDeclared
import kotlin.test.assertTrue
```

```kotlin
    private fun adapterId(): Long =
        (adapters.declareAdapter("acme", "drv", "op") as AdapterDeclared.Created).adapterId

    @Test
    fun `빌드를 처음 선언하면 Created, 같은 내용이면 같은 id 로 Existing`() {
        val id = adapterId()
        val first = assertIs<VersionDeclared.Created>(adapters.declareVersion(id, "1.0.0", SEMVER, "op"))
        val again = assertIs<VersionDeclared.Existing>(adapters.declareVersion(id, "1.0.0", SEMVER, "op"))
        assertEquals(first.adapterVersionId, again.adapterVersionId)
        assertEquals(1, count("adapter_version"))
    }

    @Test
    fun `같은 버전에 다른 계약 semver 면 Conflict 이고 기존 값을 돌려준다`() {
        val id = adapterId()
        adapters.declareVersion(id, "1.0.0", SEMVER, "op")
        val conflict = assertIs<VersionDeclared.Conflict>(adapters.declareVersion(id, "1.0.0", "9.9.9", "op"))
        assertEquals(SEMVER, conflict.existingContractSemver)
        assertEquals(1, count("adapter_version"))
    }

    @Test
    fun `계약 semver 형식이 틀리면 BadSemver 이고 저장하지 않는다`() {
        assertIs<VersionDeclared.BadSemver>(adapters.declareVersion(adapterId(), "1.0.0", "not-a-semver", "op"))
        assertEquals(0, count("adapter_version"))
    }

    @Test
    fun `모르는 제품이면 UnknownAdapter 이고 예외가 되지 않는다`() {
        val unknown = assertIs<VersionDeclared.UnknownAdapter>(adapters.declareVersion(424242, "1.0.0", SEMVER, "op"))
        assertEquals(424242, unknown.adapterId)
        assertEquals(0, count("adapter_version"))
    }

    @Test
    fun `옛 registerVersion 은 모르는 제품을 예외 대신 거절로 돌려준다`() {
        // 옛 경로는 FK 위반으로 예외를 냈다. 위임 뒤에는 거절이다 — 같은 SQL 경로 하나만 남기려고 위임했다.
        assertTrue(adapters.registerVersion(424242, "1.0.0", SEMVER, "op") is RegisterOutcome.Rejected)
    }
```

companion 에 더한다:

```kotlin
    private companion object {
        val SEMVER: String = dev.picasso.contracts.wire.ContractIdentity.semver
    }
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :registry:test --tests '*AdapterDeclarationTest*' -q`
Expected: `Unresolved reference 'VersionDeclared'`.

- [ ] **Step 3: 구현**

`AdapterDeclared` 아래에 더한다:

```kotlin
/** P1 — 조작 문의 빌드 선언 결과. 응답 코드 201·200·409·400·404 와 하나씩 맞는다. */
sealed interface VersionDeclared {
    data class Created(val adapterVersionId: Long) : VersionDeclared

    /** 같은 버전·같은 계약 semver 의 재요청. 재시도가 안전하도록 같은 id 를 돌려준다. */
    data class Existing(val adapterVersionId: Long) : VersionDeclared

    /** 같은 버전이 **다른** 계약 semver 로 이미 있다. 덮어쓰지 않는다. */
    data class Conflict(val existingContractSemver: String) : VersionDeclared

    data class BadSemver(val detail: String) : VersionDeclared

    data class UnknownAdapter(val adapterId: Long) : VersionDeclared

    /** 본문이 비었다(`version`). */
    data class Rejected(val detail: String) : VersionDeclared
}
```

`AdapterService` 안의 옛 `registerVersion` 본문 전체를 다음으로 바꾸고, 그 아래에 `declareVersion` 을 더한다:

```kotlin
    fun registerVersion(
        adapterId: Long,
        version: String,
        contractSemver: String,
        actor: String,
    ): RegisterOutcome = when (val declared = declareVersion(adapterId, version, contractSemver, actor)) {
        // **옛 계약을 지킨다** — 같은 버전 재등록은 이 경로에서 여전히 거절이다(`AdapterLifecycleTest`).
        // 멱등은 조작 문(`declareVersion`)의 성질이고, 옛 호출자는 그 변화를 모른다.
        is VersionDeclared.Created -> RegisterOutcome.Registered(declared.adapterVersionId)
        is VersionDeclared.Existing, is VersionDeclared.Conflict -> RegisterOutcome.Rejected("이미 등록된 버전이다: $version")
        is VersionDeclared.BadSemver -> RegisterOutcome.Rejected(declared.detail)
        is VersionDeclared.UnknownAdapter -> RegisterOutcome.Rejected("모르는 어댑터다: ${declared.adapterId}")
        is VersionDeclared.Rejected -> RegisterOutcome.Rejected(declared.detail)
    }

    /**
     * P1 — 조작 문의 빌드 선언. 같은 버전·같은 계약값의 재요청은 [VersionDeclared.Existing](멱등)이고,
     * 같은 버전에 다른 계약값은 [VersionDeclared.Conflict] 다 — 덮어쓰면 이미 붙은 바인딩의 뜻이 바뀐다.
     *
     * **FK 위반을 터뜨리지 않는다**([AdapterInstanceService.register] 와 같은 이유). 동시 삽입은
     * `ON CONFLICT DO NOTHING` 뒤 다시 읽어 가른다.
     */
    fun declareVersion(adapterId: Long, version: String, contractSemver: String, actor: String): VersionDeclared {
        if (version.isBlank()) return VersionDeclared.Rejected("version 은 비울 수 없다")
        runCatching { Semver.parse(contractSemver) }.onFailure {
            return VersionDeclared.BadSemver(it.message ?: "계약 semver가 형식이 아니다")
        }
        return db.transaction { c ->
            val known = c.prepareStatement("SELECT 1 FROM adapter WHERE adapter_id = ?").use { s ->
                s.setLong(1, adapterId)
                s.executeQuery().use { it.next() }
            }
            if (!known) return@transaction VersionDeclared.UnknownAdapter(adapterId)

            val inserted = c.prepareStatement(
                "INSERT INTO adapter_version (adapter_id, version, contract_semver, registered_by) " +
                    "VALUES (?, ?, ?, ?) ON CONFLICT (adapter_id, version) DO NOTHING RETURNING adapter_version_id",
            ).use { s ->
                s.setLong(1, adapterId); s.setString(2, version)
                s.setString(3, contractSemver); s.setString(4, actor)
                s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }
            if (inserted != null) {
                audit(c, actor, "ADAPTER_VERSION_REGISTER", "$adapterId@$version")
                return@transaction VersionDeclared.Created(inserted)
            }

            val (existingId, existingSemver) = c.prepareStatement(
                "SELECT adapter_version_id, contract_semver FROM adapter_version WHERE adapter_id = ? AND version = ?",
            ).use { s ->
                s.setLong(1, adapterId); s.setString(2, version)
                s.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) to rs.getString(2) }
            }
            if (existingSemver == contractSemver) VersionDeclared.Existing(existingId)
            else VersionDeclared.Conflict(existingSemver)
        }
    }
```

- [ ] **Step 4: 새 시험과 옛 시험 모두 통과 확인**

Run: `./gradlew :registry:test --tests '*AdapterDeclarationTest*' --tests '*AdapterLifecycleTest*' -q`
Expected: `AdapterDeclarationTest` tests=8 failures=0, `AdapterLifecycleTest` failures=0(특히 `같은 버전을 두 번 등록하면 거부한다` 가 통과해야 한다 — 옛 계약 유지).

### Task 3: 목록 조회 `list`

**Files:**
- Modify: `registry/src/main/kotlin/dev/picasso/registry/adapter/AdapterService.kt`
- Modify: `registry/src/test/kotlin/dev/picasso/registry/AdapterDeclarationTest.kt`

- [ ] **Step 1: 실패하는 시험 더하기**

```kotlin
    @Test
    fun `목록은 제품마다 빌드를 담고 빌드의 적합성은 UNTESTED 로 시작한다`() {
        val id = adapterId()
        adapters.declareVersion(id, "1.0.0", SEMVER, "op")
        adapters.declareVersion(id, "1.1.0", SEMVER, "op")
        adapters.declareAdapter("zeta", "bare", "op") // 빌드 없는 제품도 목록에 있다

        val rows = adapters.list()
        assertEquals(listOf("acme/drv", "zeta/bare"), rows.map { "${it.vendor}/${it.name}" })
        val drv = rows.first()
        assertEquals(listOf("1.0.0", "1.1.0"), drv.versions.map { it.version })
        assertTrue(drv.versions.all { it.conformance == "UNTESTED" && it.contract_semver == SEMVER && it.registered_by == "op" })
        assertEquals(emptyList(), rows.last().versions)
    }
```

- [ ] **Step 2: 컴파일 실패 확인** — `Unresolved reference 'list'`.

- [ ] **Step 3: 구현**

`VersionDeclared` 아래에 응답 행을 더한다(키는 스펙 §5 의 응답 모양 그대로 snake_case):

```kotlin
/** P1 — `GET /operations/adapters` 의 행. 키 이름이 응답 JSON 의 키다. */
data class AdapterRow(
    val adapter_id: Long,
    val vendor: String,
    val name: String,
    val versions: List<AdapterVersionRow>,
)

data class AdapterVersionRow(
    val adapter_version_id: Long,
    val version: String,
    val contract_semver: String,
    val conformance: String,
    val registered_at: String,
    val registered_by: String,
)
```

`AdapterService` 안에 더한다:

```kotlin
    /**
     * P1 — 제품과 빌드 목록. **아직 아무 인스턴스에도 안 쓰인 빌드도 보인다** — `/diag/adapter-instances` 는
     * 인스턴스가 있어야 빌드가 보여서, 인스턴스 등록 화면이 고를 빌드를 찾을 길이 없었다.
     */
    fun list(): List<AdapterRow> = db.open().use { c ->
        val versions = c.prepareStatement(
            "SELECT adapter_id, adapter_version_id, version, contract_semver, conformance_status, registered_at, registered_by " +
                "FROM adapter_version ORDER BY adapter_id, version",
        ).use { s ->
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            rs.getLong(1) to AdapterVersionRow(
                                adapter_version_id = rs.getLong(2),
                                version = rs.getString(3),
                                contract_semver = rs.getString(4),
                                conformance = rs.getString(5),
                                registered_at = rs.getTimestamp(6).toInstant().toString(),
                                registered_by = rs.getString(7),
                            ),
                        )
                    }
                }
            }
        }.groupBy({ it.first }, { it.second })

        c.prepareStatement("SELECT adapter_id, vendor, name FROM adapter ORDER BY vendor, name").use { s ->
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getLong(1)
                        add(AdapterRow(id, rs.getString(2), rs.getString(3), versions[id].orEmpty()))
                    }
                }
            }
        }
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :registry:test --tests '*AdapterDeclarationTest*' -q`
Expected: tests=9 failures=0.

---

## Chunk 2: 표면, 결함 주입, 문서, PR

### Task 4: 컨트롤러와 배선

**Files:**
- Create: `registry/src/main/kotlin/dev/picasso/registry/web/AdapterOperationsController.kt`
- Modify: `registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt`(빈 1개)
- Create: `registry/src/test/kotlin/dev/picasso/registry/web/AdapterEndpointTest.kt`

- [ ] **Step 1: 실패하는 표면 시험 쓰기**

```kotlin
package dev.picasso.registry.web

import dev.picasso.registry.PostgresSupport
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
 * P1 — 어댑터 제품·빌드의 조작 문. **응답 코드가 결과마다 갈리는가.**
 *
 * 서비스 시험은 결과 타입을 보고, 이 시험은 그 타입이 코드로 옮겨졌는지 본다. 400 과 409 를 접으면 운영자가
 * «고쳐서 다시» 와 «다른 버전 번호로» 를 못 가른다.
 */
@SpringBootTest(
    classes = [RegistryApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class AdapterEndpointTest {

    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    @BeforeTest
    fun reset() {
        PostgresSupport.reset()
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

    private fun declareAdapter() = call(HttpMethod.POST, "/operations/adapters", """{"vendor":"acme","name":"drv"}""")

    private fun adapterIdOf(json: String): Long = Regex(""""adapter_id"\s*:\s*(\d+)""").find(json)!!.groupValues[1].toLong()

    private fun versions(id: Long, body: String) = call(HttpMethod.POST, "/operations/adapters/$id/versions", body)

    @Test
    fun `조작 토큰 없이는 선언도 목록도 401 이다`() {
        assertEquals(401, call(HttpMethod.POST, "/operations/adapters", """{"vendor":"a","name":"b"}""", token = null).statusCode.value())
        assertEquals(401, call(HttpMethod.GET, "/operations/adapters", token = null).statusCode.value())
        assertEquals(401, call(HttpMethod.POST, "/operations/adapters", """{"vendor":"a","name":"b"}""", token = INGEST_TOKEN).statusCode.value())
    }

    @Test
    fun `X-Actor 없이 선언하면 400 이다`() {
        assertEquals(400, call(HttpMethod.POST, "/operations/adapters", """{"vendor":"a","name":"b"}""", actor = null).statusCode.value())
    }

    @Test
    fun `제품 선언은 처음 201, 다시 200 이고 같은 adapter_id 다`() {
        val first = declareAdapter()
        val again = declareAdapter()
        assertEquals(201, first.statusCode.value())
        assertEquals(200, again.statusCode.value())
        assertEquals(adapterIdOf(first.body!!), adapterIdOf(again.body!!))
        assertEquals(400, call(HttpMethod.POST, "/operations/adapters", """{"vendor":"","name":"drv"}""").statusCode.value())
    }

    @Test
    fun `빌드 선언은 처음 201, 같은 내용 재요청은 200 이다`() {
        val id = adapterIdOf(declareAdapter().body!!)
        val body = """{"version":"1.0.0","contract_semver":"$SEMVER"}"""
        val first = versions(id, body)
        val again = versions(id, body)
        assertEquals(201, first.statusCode.value())
        assertEquals(200, again.statusCode.value())
        assertEquals(
            Regex(""""adapter_version_id"\s*:\s*(\d+)""").find(first.body!!)!!.groupValues[1],
            Regex(""""adapter_version_id"\s*:\s*(\d+)""").find(again.body!!)!!.groupValues[1],
        )
    }

    @Test
    fun `같은 버전에 다른 계약값이면 409 다`() {
        val id = adapterIdOf(declareAdapter().body!!)
        versions(id, """{"version":"1.0.0","contract_semver":"$SEMVER"}""")
        val conflict = versions(id, """{"version":"1.0.0","contract_semver":"9.9.9"}""")
        assertEquals(409, conflict.statusCode.value())
        assertTrue(conflict.body!!.contains(SEMVER), "기존 계약값을 돌려주지 않는다: ${conflict.body}")
    }

    @Test
    fun `계약 semver 형식이 틀리면 400 이다`() {
        val id = adapterIdOf(declareAdapter().body!!)
        assertEquals(400, versions(id, """{"version":"1.0.0","contract_semver":"nope"}""").statusCode.value())
    }

    @Test
    fun `모르는 제품의 빌드는 404 다`() {
        assertEquals(404, versions(424242, """{"version":"1.0.0","contract_semver":"$SEMVER"}""").statusCode.value())
    }

    @Test
    fun `목록 응답이 스펙의 모양이다`() {
        val id = adapterIdOf(declareAdapter().body!!)
        versions(id, """{"version":"1.0.0","contract_semver":"$SEMVER"}""")
        val list = call(HttpMethod.GET, "/operations/adapters")
        assertEquals(200, list.statusCode.value())
        val body = list.body!!
        listOf("adapter_id", "vendor", "name", "versions", "adapter_version_id", "version", "contract_semver", "conformance", "registered_at", "registered_by")
            .forEach { key -> assertTrue(body.contains("\"$key\""), "키가 없다: $key — $body") }
        assertTrue(body.contains("\"UNTESTED\""), body)
        assertTrue(body.contains("\"engineer/kim\""), "X-Actor 가 registered_by 로 가지 않았다: $body")
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
        }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :registry:test --tests '*AdapterEndpointTest*' -q`
Expected: 401 시험을 뺀 나머지가 404(경로 없음)로 실패한다. 실패 이름이 XML 에 있어야 한다.

- [ ] **Step 3: 컨트롤러 만들기**

```kotlin
package dev.picasso.registry.web

import dev.picasso.registry.adapter.AdapterDeclared
import dev.picasso.registry.adapter.AdapterService
import dev.picasso.registry.adapter.VersionDeclared
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * P1 — 어댑터 제품·빌드의 조작 문(시운전 Step 2). **조작 문이다** — 사람이 *"이 빌드를 들였다"* 고 적는다.
 * 관문은 [OperatorToken] 이 경로로 건다.
 *
 * 생긴 이유는 첫 바깥 소비자다(ADR 9). picasso-ops 의 운영 화면이 인스턴스를 등록하려면 고를 빌드가
 * 있어야 하는데, 빌드를 넣는 문이 없어 시험만 서비스를 직접 불렀다.
 *
 * 응답 코드는 결과마다 하나다. **400 과 409 를 접지 않는다** — 앞은 «고쳐서 다시», 뒤는 «다른 버전 번호로» 다.
 */
@RestController
class AdapterOperationsController(private val adapters: AdapterService) {

    @PostMapping("/operations/adapters")
    fun declareAdapter(
        @RequestBody request: DeclareAdapterRequest,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any>> = when (val outcome = adapters.declareAdapter(request.vendor, request.name, actor)) {
        is AdapterDeclared.Created -> ResponseEntity.status(HttpStatus.CREATED).body(mapOf("adapter_id" to outcome.adapterId))
        is AdapterDeclared.Existing -> ResponseEntity.ok(mapOf("adapter_id" to outcome.adapterId))
        is AdapterDeclared.Rejected -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to outcome.detail))
    }

    @PostMapping("/operations/adapters/{adapterId}/versions")
    fun declareVersion(
        @PathVariable adapterId: Long,
        @RequestBody request: DeclareVersionRequest,
        @RequestHeader("X-Actor") actor: String,
    ): ResponseEntity<Map<String, Any>> = when (
        val outcome = adapters.declareVersion(adapterId, request.version, request.contract_semver, actor)
    ) {
        is VersionDeclared.Created ->
            ResponseEntity.status(HttpStatus.CREATED).body(mapOf("adapter_version_id" to outcome.adapterVersionId))
        is VersionDeclared.Existing -> ResponseEntity.ok(mapOf("adapter_version_id" to outcome.adapterVersionId))
        is VersionDeclared.Conflict -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "같은 버전이 다른 계약 semver 로 이미 있다", "contract_semver" to outcome.existingContractSemver),
        )
        is VersionDeclared.BadSemver -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to outcome.detail))
        is VersionDeclared.UnknownAdapter ->
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "모르는 어댑터다: ${outcome.adapterId}"))
        is VersionDeclared.Rejected -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to outcome.detail))
    }

    /** 조작 문 뒤의 읽기다 — 등록하러 온 사람이 **조작 직전에** 고를 빌드를 보는 흐름이라 같은 문에 둔다. */
    @GetMapping("/operations/adapters")
    fun list(): List<AdapterResponse> = adapters.list().map { row ->
        AdapterResponse(
            adapter_id = row.adapterId,
            vendor = row.vendor,
            name = row.name,
            versions = row.versions.map { v ->
                AdapterVersionResponse(
                    adapter_version_id = v.adapterVersionId,
                    version = v.version,
                    contract_semver = v.contractSemver,
                    conformance = v.conformance,
                    registered_at = v.registeredAt,
                    registered_by = v.registeredBy,
                )
            },
        )
    }
}

/**
 * `GET /operations/adapters` 의 행. **응답 모양(snake_case)은 이 문이 정한다** — 서비스의 `AdapterRow` 는 HTTP 를
 * 모르는 camelCase 다(같은 패키지의 `AdapterInstanceRow` 와 같은 배치). 키는 picasso-ops S1 스펙 §5 의 응답 모양 그대로다.
 */
data class AdapterResponse(
    val adapter_id: Long,
    val vendor: String,
    val name: String,
    val versions: List<AdapterVersionResponse>,
)

data class AdapterVersionResponse(
    val adapter_version_id: Long,
    val version: String,
    val contract_semver: String,
    val conformance: String,
    val registered_at: String,
    val registered_by: String,
)

/** `POST /operations/adapters` 의 본문. */
data class DeclareAdapterRequest(val vendor: String = "", val name: String = "")

/** `POST /operations/adapters/{adapterId}/versions` 의 본문. */
data class DeclareVersionRequest(val version: String = "", val contract_semver: String = "")
```

- [ ] **Step 4: 빈 배선**

`RegistryApplication.kt` 의 `adapterInstances` 빈 바로 아래에 더한다:

```kotlin
    @Bean
    open fun adapters(db: Db): dev.picasso.registry.adapter.AdapterService =
        dev.picasso.registry.adapter.AdapterService(db)
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :registry:test --tests '*AdapterEndpointTest*' --tests '*AdapterDeclarationTest*' -q`
Expected: `AdapterEndpointTest` tests=8 failures=0, `AdapterDeclarationTest` tests=9 failures=0.

### Task 5: 결함 주입(시험이 잡는가)

**Files:** 위 두 파일(되돌린다)

- [ ] **Step 1: 409 를 400 으로 바꿔 치기** — `AdapterOperationsController` 의 `VersionDeclared.Conflict` 줄에서 `HttpStatus.CONFLICT` 를 `HttpStatus.BAD_REQUEST` 로 바꾼다.

Run: `./gradlew :registry:test --tests '*AdapterEndpointTest*' -q`
Expected: XML 에서 `같은 버전에 다른 계약값이면 409 다` 가 실패. 되돌린다.

- [ ] **Step 2: 200 을 201 로 바꿔 치기** — `AdapterDeclared.Existing` 줄을 `HttpStatus.CREATED` 로.

Run: `./gradlew :registry:test --tests '*AdapterEndpointTest*' -q`
Expected: XML 에서 `제품 선언은 처음 201, 다시 200 이고 같은 adapter_id 다` 실패. 되돌린다.

- [ ] **Step 3: 서비스의 Existing 판정 지우기** — `declareVersion` 마지막 `if (existingSemver == contractSemver)` 를 `if (false)` 로.

Run: `./gradlew :registry:test --tests '*AdapterDeclarationTest*' -q`
Expected: XML 에서 `빌드를 처음 선언하면 Created, 같은 내용이면 같은 id 로 Existing` 실패. 되돌린다.

- [ ] **Step 3b: 404 를 500 으로 바꿔 치기** — `VersionDeclared.UnknownAdapter` 줄을 `HttpStatus.INTERNAL_SERVER_ERROR` 로.

Run: `./gradlew :registry:test --tests '*AdapterEndpointTest*' -q`
Expected: XML 에서 `모르는 제품의 빌드는 404 다` 실패(스펙이 막으려던 «FK 가 500 으로 새는» 모양이 걸리는지). 되돌린다.

- [ ] **Step 4: 되돌린 뒤 전체 통과 확인**

Run: `./gradlew :registry:test --tests '*Adapter*' -q`
Expected: 실패 0. `git diff --stat` 에 주입 흔적이 없다.

### Task 6: 문서와 시험 수, 검증, PR

**Files:**
- Modify: `docs/commissioning.md`(§2 Step 2 행, §3 표 행 1개, §4 표 행 3개, 도장)
- Modify: `docs/superpowers/specs/2026-09-05-picasso-design.md`(§15 일지 새 항목 206, 205 위에)
- Modify: `docs/limits.md`(5행 «번호가 205 까지 갔고» → 206, 도장)
- Modify: `CLAUDE.md`(시험 수 1,853 → 1,870, 도장)
- Modify: `docs/verification.md`(3행 시험 수 1,853 → 1,870, 33행 «`*EndpointTest` 넷» → 다섯, 도장)
- Modify: `registry/README.md`(42행 «`web/*EndpointTest` 넷이» → 다섯이, 도장)

- [ ] **Step 1: 문서 문장 초안 받기** — Fable 과 Codex 에 같은 브리프로(사실만 담는다):
  - commissioning.md §2 Step 2 의 «실행 위치 / API» 칸: `POST /operations/adapters` · `POST /operations/adapters/{adapterId}/versions`
  - §3 표 새 행: 어댑터 제품·빌드 추가 | 두 POST | 라인 정지 불필요(같은 내용 재요청은 같은 id)
  - §4 표 새 행 3개: 두 POST(조작) · `GET /operations/adapters`(조작 문 뒤의 읽기, 제품·빌드 목록과 적합성)
  - 일지 206: `206. **제목**` 꼴의 제목 한 줄(이 꼴이어야 `설계 일지의 마지막 번호를 한계 대장이 맞게 적는다` 의 정규식 `^(\d+)\. \*\*` 가 센다) + 본문 2~3문단, 205 항목 바로 위에(최신이 위). 사실: 첫 바깥 소비자 picasso-ops(S1 스펙 §5)가 근거(ADR 9), 서비스가 결과를 가르게 된 이유(옛 `registerAdapter` 는 `Long` 만, 옛 `registerVersion` 은 형식 오류·중복을 `Rejected` 하나로 접고 모르는 제품은 FK 예외), 옛 `registerVersion` 은 새 메서드에 위임하되 같은 버전 재등록 거절은 유지, `registerAdapter` 는 호출 18곳(harness 3파일) 때문에 그대로 둠, 새 시험 17개와 결함 주입 4건.
  - 금지: `handoff/narrator/ground-truth.jsonl` 의 `narrowable: false` 행의 `cause`·`candidates` 낱말 전부(지금은 «안 좁혀», «좁혀지지 않», «가르지 않», «진단 로그», «배터리 셀 불균형», «모터 드라이버 과열»)를 쓰지 않는다 — `GroundTruthTest` 가 docs/ 전체를 훑는다. 브리프를 쓰기 전에 그 파일에서 다시 뽑는다.
  취합해 반영한다. 경로 문자열은 `@...Mapping` 의 문자열과 글자 그대로 같아야 한다(`DocumentClaimsTest` 의 `설정 표면 목록이 바꾸는 문을 빠짐없이 적는다`).

- [ ] **Step 2: 수 갱신**
  - `CLAUDE.md` 11줄 `총 1,853개 테스트` 와 `docs/verification.md` 3줄 `1,853개` 를 실측으로. 새 `@Test` 는 17개(서비스 9 + 표면 8)이므로 1,870 을 기대하되, 기준이 된 main 이 그 사이 바뀌었으면 `자동화 시험의 수를 대외 문서가 맞게 적는다` 실패 메시지의 실측값을 쓴다.
  - `docs/verification.md` 33행과 `registry/README.md` 42행의 «넷» 을 «다섯» 으로(`레지스트리 문 시험의 수를 검증 근거 표가 맞게 적는다`, `모듈 문이 적은 수가 코드와 같다` 가 `*EndpointTest` 파일 수를 센다).
  - `docs/limits.md` 5행 «번호가 205 까지 갔고» 를 206 으로.

- [ ] **Step 3: 도장과 줄 끝**

```bash
python tools/stamp.py docs/commissioning.md
python tools/stamp.py CLAUDE.md
python tools/stamp.py docs/verification.md
python tools/stamp.py docs/limits.md
python tools/stamp.py registry/README.md
```
(`--open` 없이 돌리면 기존 열림 목록을 그대로 옮긴다.)
`stamp.py` 는 LF 로 쓴다. 워크트리의 이 파일들은 CRLF 이므로 diff 잡음을 막으려고 CRLF 로 되돌린다(해시는 `\r\n` 을 정규화하므로 영향 없음). 파이썬으로 `\r\n` 수와 `\n` 수가 같은지 본다. 설계 일지는 `## 15.` 아래라 도장 해시에 안 들어간다 — 도장을 다시 찍지 않는다.

- [ ] **Step 4: 전체 검증**

Run: `./gradlew :registry:test :harness:test :gate:test :picasso:test --continue -q`
Expected: XML 기준 네 모듈 실패 0. 특히 통과해야 하는 시험: `CompletionCriterionTest`(도장·해시), `DocumentClaimsTest` 의 `설정 표면 목록이 바꾸는 문을 빠짐없이 적는다`·`자동화 시험의 수를 대외 문서가 맞게 적는다`·`설계 일지의 마지막 번호를 한계 대장이 맞게 적는다`·`레지스트리 문 시험의 수를 검증 근거 표가 맞게 적는다`·`모듈 문이 적은 수가 코드와 같다`, `picasso` 의 `GroundTruthTest`(정답 누수). 하나라도 빨가면 이름으로 원인을 찾고 고친다.

- [ ] **Step 5: 커밋**(문장은 Fable·Codex 초안 취합, 훅 형식)

```bash
git add registry/src/main/kotlin/dev/picasso/registry/adapter/AdapterService.kt \
  registry/src/main/kotlin/dev/picasso/registry/web/AdapterOperationsController.kt \
  registry/src/main/kotlin/dev/picasso/registry/web/RegistryApplication.kt \
  registry/src/test/kotlin/dev/picasso/registry/AdapterDeclarationTest.kt \
  registry/src/test/kotlin/dev/picasso/registry/web/AdapterEndpointTest.kt \
  docs/commissioning.md docs/superpowers/specs/2026-09-05-picasso-design.md \
  docs/limits.md CLAUDE.md docs/verification.md registry/README.md
git commit -F - <<'EOF'
feat(registry): <취합한 제목>

- <취합한 불릿>

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
(`<…>` 자리는 실행 때 취합한 문장으로 채운다. 계획의 빈칸이 아니라 실행 시점 산출물이다. 커밋·PR 브리프에 넣을 사실: 새 REST 3개와 응답 코드, 서비스 결과 타입 `AdapterDeclared`·`VersionDeclared`, 옛 `registerVersion` 위임과 유지한 거절·바뀐 2곳, `registerAdapter` 를 그대로 둔 이유, 새 시험 17개(서비스 9·표면 8), 결함 주입 4건과 잡은 시험 이름, 고친 문서 6개(commissioning·일지 206·limits·CLAUDE·verification·registry README)와 도장, 네 모듈 시험 결과 수치, 첫 바깥 소비자 picasso-ops(ADR 9).)

- [ ] **Step 6: 푸시와 PR**

```bash
git push -u origin feat/adapter-build-rest
gh pr create --base main --title "feat(registry): <취합한 제목>" --body-file - <<'EOF'
## 개요

<취합한 합니다체 문단>

## 주요 변경 사항

- <취합한 불릿>

## 검증 결과

- <취합한 불릿>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
```
머지는 사용자 승인 뒤. 머지하면 메인 체크아웃이 main 을 당기기 **전에** khala 와 narrator 에 시각과 바뀐 docs/ 파일(commissioning.md, limits.md, verification.md, 설계 일지)을 알린다.

- [ ] **Step 7: picasso-ops 쪽 후속** — P1 머지 커밋 해시를 picasso-ops S1 의 S1c 첫 커밋(서브모듈 포인터 이동)에 쓴다.

### Task 7: picasso-ops 스펙 정정

**Files:**
- Modify: picasso-ops `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md`(§5 «기존 동작 변경» 문단, §10 시험 표 P1 행)

- [ ] **Step 1: 정정 문장 초안 받기** — Fable 과 Codex 에 같은 브리프로. 사실: 옛 `registerVersion` 의 같은 버전 재등록 거절은 유지하고 `AdapterLifecycleTest` 는 고치지 않는다, 멱등은 새 조작 문(`declareVersion`)에만, 옛 메서드가 바뀌는 2곳(모르는 제품 → `Rejected`, 빈 version → `Rejected`), 이유(옛 호출자가 멱등을 기대하지 않음), 결정 일자와 근거(P1 계획), §5 의 «`registerAdapter(` 를 부르는 시험이 14곳» 을 실측 «호출 18곳, 시험 파일 14개(그중 harness 3)» 로. 취합해 반영한다.
- [ ] **Step 2: 커밋** — picasso-ops 에서 `docs(specs): <취합한 제목>`, 같은 트레일러. P1 PR 링크를 본문 불릿에 넣는다. 푸시는 사용자 승인 뒤.

## 실행 결과 (2026-10-07)

- picasso PR #79 구현 완료, 커밋 `8b3442d`
- 계획과 달라진 응답 배치: 서비스 행 `AdapterRow`·`AdapterVersionRow` 의 camelCase 유지, 컨트롤러 응답 DTO 에서 `GET /operations/adapters` 의 snake_case 결정, 409 본문 키 `error`·`existing_contract_semver` 사용
- 계획 대비 새 시험 17→18개(`AdapterDeclarationTest` 10개, `AdapterEndpointTest` 8개), 결함 주입 4→8건 모두 이름 있는 시험이 탐지, 옛 `registerVersion` 의 위임으로 바뀐 동작 2→3곳
- 시험 결과: registry 351, harness 194, gate 276, picasso 297, 실패 0, 전체 1,871
- 작업마다 스펙 준수·코드 품질 검토 및 최종 검토 통과
- 남긴 후보: 계약 SemVer 문자열 정규화, 현재 앞뒤 공백·앞자리 0처럼 뜻이 같은 다른 표기에 409 반환
