# S2 현장 설정 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 엔지니어가 화면에서 연결 기준 시간을 바꾸면 현장 설정의 새 버전이 생기고, 코드 수정과 재기동 없이 다음 기체 목록 읽기부터 그 값으로 연결 칸과 막힘을 판정하며, 막힘 카드가 근거 버전을 보인다. 범위 밖 값, 지난 기준 버전, 운영자 모드는 막힌다.

**Architecture:** ops 스키마에 덧붙이기 전용 표 `site_settings` 를 더한다(마이그레이션 `V2`, 버전 1 = 90초). 운영 서비스는 현장 설정 저장소(`SiteSettingsStore`), 변경 조작(`SiteSettingsOperations`, 새 버전 행과 조작 기록 행을 한 트랜잭션에), API(`GET`·`PUT /api/site-settings`)를 더하고, 기체 목록(`RobotListService`)이 기준 시간을 설정 파일 대신 현장 설정에서 읽어 스냅샷이 그 버전을 쥐게 한다. `Finding` 에 근거 버전(`basisVersion`)을 더한다. 화면은 현장·자원 영역을 열어 «현장 설정» 구역을 두고, 막힘 카드에 근거 버전, 기체 상세에 연결 판정 기준을 보인다.

**Tech Stack:** Kotlin 2.4.20, Spring Boot 3.4.0(`JdbcClient`, `TransactionTemplate`), Flyway, JUnit5 + kotlin.test, Testcontainers(picasso registry `testFixtures` 의 `PostgresSupport`), React 19 + Vite 8 + TypeScript, vitest 5 + Testing Library, Playwright 1.63.

**근거 스펙:** `docs/superpowers/specs/2026-10-08-s2-site-settings-design.md` §3(완료 기준), §5(저장), §6(운영 서비스), §7(화면), §8(오류 처리), §9(시험). 스펙 검토 2회(1차 막힘 3건·권고 12건, 2차 승인 뒤 권고 7건)를 반영한 버전이다. 계획 검토 1회(막힘 1건·권고 10건)를 반영했다.

**스펙이 계획에 맡긴 것과 이 계획이 정한 것:**
- 현장 설정 출처는 함수형 인터페이스 `SettingsSource` 다. `RobotListService` 생성자의 다섯째 인자가 `Duration` 에서 이것으로 바뀌므로 기존 시험 2곳(`RobotListServiceTest`, `RobotCommissioningListTest`)의 생성 인자를 고친다.
- 기체 목록은 읽기마다 현장 설정을 먼저 읽는다. 못 읽으면 예외가 그대로 나가고(`GET /api/robots` 500) 스냅샷은 바뀌지 않는다. registry 상태 칸에 값을 더하지 않는다.
- 변경 조작의 응답은 기존 `OperationOutcome` 그대로다. 버전 충돌도 200 + `REJECTED` + 거부 칸이다. 새 버전은 화면이 조작 뒤 다시 읽을 때 보인다.
- 트랜잭션 관리자는 빈으로 두지 않고 `TransactionTemplate(DataSourceTransactionManager(dataSource))` 를 조작 빈 안에서 만든다. 시험도 같은 방식으로 만든다.
- 기본 키 경로 시험은 스레드 경합에 맡기지 않는다. 다른 연결이 같은 번호를 넣고 커밋하지 않은 채 잡고 있는 동안 변경을 보내고, `pg_stat_activity` 에서 변경이 잠금을 기다리는 것을 본 뒤 커밋한다.
- 기존 시험 둘의 기대값이 바뀐다. e2e `SkeletonTest` 의 ops 마이그레이션 버전이 `1` 에서 `1, 2` 로, vitest `App.test.tsx` 의 메뉴에서 현장·자원의 «다음 단계» 가 빠진다.
- 화면에서 정한 작은 것: 이력 표의 모드는 «엔지니어» 로 보인다. 폼의 범위 검사 문구는 «연결 기준 시간은 60~3600초의 정수여야 합니다», 빈 사유는 «변경 사유를 넣으십시오». 조작 알림의 대상은 «연결 기준 시간 N초로 변경». 거부 종류 이름은 «현장 설정 버전 충돌».
- 화면 시험 대역(`fakeOps.ts`)은 `/api/site-settings` 에 기본으로 버전 1 의 90초(`settingsView()`)를 준다. 스펙 §7 이 말한 «모르는 GET 에 빈 배열» 은 다른 경로에 그대로 남고, 현장 설정 구역은 빈 배열도 «모름» 으로 견딘다(App.test 의 `serve` 가 그 경우다).
- 실측: 통합 시험의 오래됨 대기는 약 61초다(마감 90초 폴링).

**작업 위치 규칙(필수):**
- 모든 작업은 picasso-ops 워크트리 `C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design` 의 브랜치 `feat/s2-site-settings` 에서 한다. picasso-ops 메인 체크아웃(`C:\Users\Eisen\Desktop\Labs\[projects] picasso-ops`)과 picasso 저장소는 건드리지 않는다. 하위 에이전트의 Bash 는 호출마다 작업 디렉터리가 돌아가므로 명령마다 `cd <워크트리> &&` 를 붙이거나 `git -C` 를 쓴다.
- `./gradlew --stop` 금지(데몬 풀이 다른 체크아웃과 공유된다). 같은 워크트리에서 Gradle 을 겹쳐 돌리지 않는다. Bash 도구의 시간 한도(600초)를 넘는 빌드는 백그라운드로 돌리고 끝났다는 알림을 받은 뒤 다음 Gradle 을 돌린다.
- `git add -A` 금지. 파일을 이름으로 더한다.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름(Gradle), vitest 출력의 `Tests N passed`, Playwright 출력의 `N passed` 로 한다. Windows python 으로 XML 을 셀 때는 `C:/...` 경로를 쓴다.
- 이 저장소는 LF 다(`.gitattributes` 의 `eol=lf`). 새 파일은 LF 로 쓴다.
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. 커밋 메시지는 heredoc(`git commit -F - <<'EOF'`)으로 쓴다. 형식 훅이 메시지를 읽어 제목이 `type(scope): 명사구` 가 아니거나 트레일러가 없거나 겹화살괄호가 있으면 막는다.
- 이 계획의 코드와 문서는 스크래치 스파이크(`C:/Users/Eisen/AppData/Local/Temp/s2`, 브랜치 `feat/s2`, HEAD `8cf5b54`)에서 시험, Playwright, 결함 주입을 다 돌린 것이다. 묶음이 끝날 때마다 커밋된 파일을 스파이크와 기계 대조한다(Task 0 Step 3 의 `s2-cmp.sh`).
- 실행 방식: 묶음(Task 1·2·3 / Task 4·5·6)마다 구현 하위 에이전트 1명(`model: "sonnet"`), 결함 주입(Task 7)과 검토는 컨트롤러.
- **블록을 손으로 옮겨 적지 않는다.** 컨트롤러가 이 계획의 블록을 기계로 뽑아 `C:/Users/Eisen/AppData/Local/Temp/s2-patches/` 에 두었다. 새 파일은 `C:/Users/Eisen/AppData/Local/Temp/s2-patches/files/<경로>` 를 워크트리의 같은 경로로 `cp` 하고, 기존 파일은 `C:/Users/Eisen/AppData/Local/Temp/s2-patches/<이름>.patch` 를 `git apply --check` 로 본 뒤 `git apply` 한다. 아래 블록은 읽고 검토하기 위한 것이다. 뽑은 파일이 없으면 멈추고 보고한다.
- Playwright 는 compose 프로젝트 `site` 의 Postgres 를 볼륨째 내렸다 올린다. 돌리기 전에 `docker ps -a` 와 `docker volume ls` 에 `site` 프로젝트의 것이 없는지 본다. 있으면 멈추고 보고한다(사용자가 손으로 띄운 것일 수 있다).

---

## Chunk 1: 운영 서비스

### Task 0: 워크트리, 기준선, 대조 도구

**Files:** 없음(환경)

- [ ] **Step 1: 워크트리와 브랜치 확인**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design"
git branch --show-current
git status --short
git log --oneline -3
```
Expected: 브랜치 `feat/s2-site-settings`, 작업 트리 깨끗함, 맨 위 커밋 둘이 이 계획(`docs(plans)`)과 스펙(`docs(specs)`), 그 아래가 `7254521`. 다르면 멈추고 보고한다.

- [ ] **Step 1b: 서브모듈 채우기와 Docker**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design"
git -c protocol.file.allow=always submodule update --init --reference "C:/Users/Eisen/Desktop/Labs/[projects] picasso"
git submodule status
docker info --format '{{.ServerVersion}}'
```
Expected: `git submodule status` 가 ` 41beedb… picasso`(앞에 `-` 없음). gitlink 가 이미 `41beedb` 라 옮기지 않는다. Docker 가 버전을 찍는다(시험의 Postgres 가 Testcontainers 다). 실패하면 멈추고 보고한다.

- [ ] **Step 2: 기준선 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design" && ./gradlew :ops-service:test -q
```
Expected: `ops-service/build/test-results/test/*.xml` 의 시험 107개, 실패 0.

- [ ] **Step 3: 대조 도구**

`C:/Users/Eisen/AppData/Local/Temp/s2-cmp.sh` 와 `C:/Users/Eisen/AppData/Local/Temp/s2-patches/`(패치 6개, `files/` 아래 새 파일 9개)가 있는지 본다. 이 스크립트는 인자로 받은 경로마다 워크트리 파일과 스파이크 HEAD 의 파일을 `\r` 을 빼고 바이트 대조해 `같음`/`다름` 를 찍는다. 없으면 멈추고 보고한다(컨트롤러가 둔다).

### Task 1: ops 스키마와 현장 설정 저장소

**Files:**
- Create: `ops-service/src/main/resources/db/ops/V2__site_settings.sql`
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettings.kt`
- Test: `ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsStoreTest.kt`

- [ ] **Step 1: 실패하는 시험 쓰기**: `ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsStoreTest.kt`(`files/` 에서 복사)

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.settings.SiteSettingsRange
import dev.picasso.ops.service.settings.SiteSettingsStore
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.SQLException
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 현장 설정 버전 표(S2 스펙 §5). */
class SiteSettingsStoreTest {

    private val dataSource = DriverManagerDataSource(
        PostgresSupport.jdbcUrl,
        PostgresSupport.username,
        PostgresSupport.password,
    )
    private val store = SiteSettingsStore(JdbcClient.create(dataSource))
    private val lee = Actor(Mode.ENGINEER, "lee")

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `마이그레이션이 S1 설정값 90초를 버전 1 로 넣는다`() {
        val first = store.latest()
        assertEquals(1L, first.version)
        assertEquals(90, first.connectionThresholdSeconds)
        assertEquals(Mode.ENGINEER, first.mode)
        assertEquals("system", first.user)
        assertEquals("S1 설정값 이전", first.reason)
        assertEquals(Duration.ofSeconds(90), store.current().connectionThreshold)
        assertEquals(1L, store.current().version)
    }

    @Test
    fun `현재 버전은 가장 큰 버전이고 이력은 최신부터다`() {
        store.insert(2, 60, lee, "시험")
        store.insert(3, 120, lee, "되돌림")
        assertEquals(listOf(3L, 2L, 1L), store.history().map { it.version })
        assertEquals(3L, store.current().version)
        assertEquals(Duration.ofSeconds(120), store.current().connectionThreshold)
    }

    @Test
    fun `같은 버전 번호는 두 번 들어가지 않는다`() {
        store.insert(2, 60, lee, "시험")
        assertFailsWith<DuplicateKeyException> { store.insert(2, 120, lee, "다른 변경") }
        assertEquals(60, store.latest().connectionThresholdSeconds)
    }

    @Test
    fun `현장 설정은 고칠 수 없다`() {
        val e = assertFailsWith<SQLException> {
            PostgresSupport.execute("UPDATE ops.site_settings SET connection_threshold_seconds = 1")
        }
        assertTrue("덧붙이기만" in e.message!!, e.message)
    }

    @Test
    fun `현장 설정은 지울 수 없다`() {
        val e = assertFailsWith<SQLException> { PostgresSupport.execute("DELETE FROM ops.site_settings") }
        assertTrue("덧붙이기만" in e.message!!, e.message)
    }

    @Test
    fun `현장 설정은 통째로 비울 수 없다`() {
        val e = assertFailsWith<SQLException> { PostgresSupport.execute("TRUNCATE ops.site_settings") }
        assertTrue("덧붙이기만" in e.message!!, e.message)
    }

    @Test
    fun `허용 범위는 60초 이상 3600초 이하다`() {
        assertFalse(SiteSettingsRange.allows(59))
        assertTrue(SiteSettingsRange.allows(60))
        assertTrue(SiteSettingsRange.allows(3600))
        assertFalse(SiteSettingsRange.allows(3601))
        // 첫 버전의 값도 범위 안이다.
        assertTrue(SiteSettingsRange.allows(store.latest().connectionThresholdSeconds.toLong()))
    }
}
```

- [ ] **Step 2: 컴파일이 실패하는지 확인**

Run: `cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design" && ./gradlew :ops-service:compileTestKotlin -q`
Expected: `SiteSettingsStore`·`SiteSettingsRange` 를 찾지 못해 실패.

- [ ] **Step 3: 마이그레이션**: `ops-service/src/main/resources/db/ops/V2__site_settings.sql`(`files/` 에서 복사)

```sql
-- 현장 설정 버전(S2 스펙 §5). 한 행이 설정 한 세트의 스냅샷이고, 값을 하나만 바꿔도 새 버전이 된다.
-- 현재 버전은 가장 큰 version 이다. 같은 기준 버전 위의 동시 변경은 기본 키가 하나만 받는다.
CREATE TABLE site_settings (
    version                      BIGINT      PRIMARY KEY CHECK (version > 0),
    connection_threshold_seconds INTEGER     NOT NULL CHECK (connection_threshold_seconds > 0),
    mode                         TEXT        NOT NULL CHECK (mode = 'ENGINEER'),
    actor_user                   TEXT        NOT NULL CHECK (actor_user <> ''),
    reason                       TEXT        NOT NULL CHECK (reason <> ''),
    recorded_at                  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 덧붙이기만 한다. 조작 기록과 같이 스키마가 막는다.
CREATE FUNCTION site_settings_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '현장 설정은 덧붙이기만 한다(%)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER site_settings_no_update_delete
    BEFORE UPDATE OR DELETE ON site_settings
    FOR EACH ROW EXECUTE FUNCTION site_settings_append_only();

CREATE TRIGGER site_settings_no_truncate
    BEFORE TRUNCATE ON site_settings
    FOR EACH STATEMENT EXECUTE FUNCTION site_settings_append_only();

-- S1 에서 설정 파일에 있던 값(ops.connection.threshold=90s)을 버전 1 로 옮긴다.
INSERT INTO site_settings (version, connection_threshold_seconds, mode, actor_user, reason)
VALUES (1, 90, 'ENGINEER', 'system', 'S1 설정값 이전');
```

- [ ] **Step 4: 저장소와 허용 범위**: `ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettings.kt`(`files/` 에서 복사)

```kotlin
package dev.picasso.ops.service.settings

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** 판정에 쓰는 현장 설정 한 세트(S2 스펙 §5). [version] 이 근거 버전이 된다. */
data class SiteSettingsValues(val version: Long, val connectionThreshold: Duration)

/** 지금의 현장 설정. 못 읽으면 예외를 던진다. 기본값으로 대신하지 않는다(S2 스펙 §6.4). */
fun interface SettingsSource {
    fun current(): SiteSettingsValues
}

/** 현장 설정 버전 한 행(S2 스펙 §5). */
data class SiteSettingsRecord(
    val version: Long,
    val connectionThresholdSeconds: Int,
    val mode: Mode,
    val user: String,
    val reason: String,
    val recordedAt: Instant,
) {
    fun values(): SiteSettingsValues = SiteSettingsValues(version, Duration.ofSeconds(connectionThresholdSeconds.toLong()))
}

/**
 * 연결 기준 시간의 허용 범위(S2 스펙 §5). 이 값의 주인은 picasso 가 아니라 운영 서비스라서 범위도 여기 둔다.
 *
 * 하한은 프로파일 보고 간격 상한 30초의 2배다. 보고를 한 번 놓쳐도 오래됨이 되지 않는다. 보고 간격 가까이 두면
 * 정상 기체가 신선과 오래됨을 오간다. 상한 1시간은 그보다 길면 연결 칸이 뜻을 잃는다는 판단이다.
 */
object SiteSettingsRange {
    const val MIN_CONNECTION_THRESHOLD_SECONDS = 60
    const val MAX_CONNECTION_THRESHOLD_SECONDS = 3600

    fun allows(seconds: Long): Boolean = seconds in MIN_CONNECTION_THRESHOLD_SECONDS..MAX_CONNECTION_THRESHOLD_SECONDS
}

/** ops 스키마의 현장 설정 버전 표. 덧붙이기와 읽기만 있다. 고치기·지우기는 스키마의 트리거가 막는다. */
class SiteSettingsStore(private val jdbc: JdbcClient) : SettingsSource {

    override fun current(): SiteSettingsValues = latest().values()

    /** 가장 큰 버전. 마이그레이션이 버전 1 을 넣으므로 늘 있다. */
    fun latest(): SiteSettingsRecord = history(limit = 1).single()

    /** 최신 버전부터. */
    fun history(limit: Int = 200): List<SiteSettingsRecord> =
        jdbc.sql(
            """
            SELECT version, connection_threshold_seconds, mode, actor_user, reason, recorded_at
            FROM ops.site_settings
            ORDER BY version DESC
            LIMIT :limit
            """.trimIndent(),
        )
            .param("limit", limit)
            .query { rs, _ ->
                SiteSettingsRecord(
                    version = rs.getLong("version"),
                    connectionThresholdSeconds = rs.getInt("connection_threshold_seconds"),
                    mode = Mode.valueOf(rs.getString("mode")),
                    user = rs.getString("actor_user"),
                    reason = rs.getString("reason"),
                    recordedAt = rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }
            .list()

    /** 버전 [version] 행을 넣는다. 그 번호가 이미 있으면 기본 키가 막는다(`DuplicateKeyException`). */
    fun insert(version: Long, connectionThresholdSeconds: Int, actor: Actor, reason: String) {
        jdbc.sql(
            """
            INSERT INTO ops.site_settings (version, connection_threshold_seconds, mode, actor_user, reason)
            VALUES (:version, :seconds, :mode, :user, :reason)
            """.trimIndent(),
        )
            .param("version", version)
            .param("seconds", connectionThresholdSeconds)
            .param("mode", actor.mode.name)
            .param("user", actor.user)
            .param("reason", reason)
            .update()
    }
}
```

- [ ] **Step 5: 시험 통과 확인**

Run: `cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design" && ./gradlew :ops-service:test --tests '*SiteSettingsStoreTest' -q`
Expected: XML 에 7개, 실패 0.

- [ ] **Step 6: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design"
git add ops-service/src/main/resources/db/ops/V2__site_settings.sql ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettings.kt ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsStoreTest.kt
git commit -q -F - <<'EOF'
feat(s2): 현장 설정 버전 표와 저장소 추가

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 2: 판정과 근거 버전

**Files:**
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/finding/Finding.kt`
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/robots/Blockers.kt`
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt`
- Modify(시험): `ops-service/src/test/kotlin/dev/picasso/ops/service/BlockersTest.kt`, `RobotListServiceTest.kt`, `RobotCommissioningListTest.kt`

- [ ] **Step 1: 시험 패치 넣기**

`C:/Users/Eisen/AppData/Local/Temp/s2-patches/task2-test.patch`(아래 블록과 같다)를 `git apply --check` 로 본 뒤 `git apply` 한다.

```diff
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/BlockersTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/BlockersTest.kt
index 855c84b..685165a 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/BlockersTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/BlockersTest.kt
@@ -54,6 +54,23 @@ class BlockersTest {
         assertEquals(listOf(Blockers.REPORT_STALE), kinds(robot("CONFIRMED", at.minusSeconds(91))))
     }
 
+    @Test
+    fun `근거 버전은 기준 시간에 기대는 오래됨에만 싣는다`() {
+        val stale = Blockers.of(robot("CONFIRMED", at.minusSeconds(91)), at, threshold, basisVersion = 7).single()
+        assertEquals(Blockers.REPORT_STALE, stale.kind)
+        assertEquals(7L, stale.basisVersion)
+        val others = listOf(
+            robot("CLAIMED"),
+            robot("RETIRED", at.minusSeconds(5), retiredAt = at.minusSeconds(60), reportingAfterRetirement = true),
+            robot("UNREGISTERED"),
+        ).flatMap { Blockers.of(it, at, threshold, basisVersion = 7) }
+        assertEquals(
+            listOf(Blockers.AWAITING_FIRST_REPORT, Blockers.REPORTING_AFTER_RETIREMENT, Blockers.UNREGISTERED_ROW),
+            others.map { it.kind },
+        )
+        assertEquals(listOf(null, null, null), others.map { it.basisVersion })
+    }
+
     @Test
     fun `퇴역 뒤 보고는 운영자가 화면 안에서 푼다`() {
         val finding = Blockers.of(
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/RobotCommissioningListTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/RobotCommissioningListTest.kt
index e71db3f..efe9db6 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/RobotCommissioningListTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/RobotCommissioningListTest.kt
@@ -8,6 +8,7 @@ import dev.picasso.ops.service.registry.RegistrySoftware
 import dev.picasso.ops.service.robots.CommissioningState
 import dev.picasso.ops.service.robots.RegistryState
 import dev.picasso.ops.service.robots.RobotListService
+import dev.picasso.ops.service.settings.SiteSettingsValues
 import java.time.Clock
 import java.time.Duration
 import java.time.Instant
@@ -48,7 +49,7 @@ class RobotCommissioningListTest {
         { RegistryCall.Ok(Unit) },
         "site-01",
         clock,
-        Duration.ofSeconds(90),
+        { SiteSettingsValues(1, Duration.ofSeconds(90)) },
         commissioning = object : CommissioningSource {
             override fun bindings(siteId: String) = bindings.also { asked += "bindings $siteId" }
             override fun software(siteId: String) = software.also { asked += "software $siteId" }
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/RobotListServiceTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/RobotListServiceTest.kt
index 483b4ff..fc49a21 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/RobotListServiceTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/RobotListServiceTest.kt
@@ -6,6 +6,7 @@ import dev.picasso.ops.service.robots.Blockers
 import dev.picasso.ops.service.robots.Connection
 import dev.picasso.ops.service.robots.RegistryState
 import dev.picasso.ops.service.robots.RobotListService
+import dev.picasso.ops.service.settings.SiteSettingsValues
 import java.time.Clock
 import java.time.Duration
 import java.time.Instant
@@ -13,6 +14,7 @@ import java.time.ZoneId
 import java.time.ZoneOffset
 import kotlin.test.Test
 import kotlin.test.assertEquals
+import kotlin.test.assertFailsWith
 import kotlin.test.assertNull
 
 class RobotListServiceTest {
@@ -30,12 +32,14 @@ class RobotListServiceTest {
     private var next: RegistryCall<List<RegistryRobot>> = RegistryCall.Ok(emptyList())
     private var token: RegistryCall<Unit> = RegistryCall.Ok(Unit)
     private var askedSite: String? = null
+    private var settings = SiteSettingsValues(1, Duration.ofSeconds(90))
+    private var settingsDown = false
     private val service = RobotListService(
         { site -> askedSite = site; next },
         { token },
         "site-01",
         clock,
-        Duration.ofSeconds(90),
+        { if (settingsDown) error("ops DB 응답 없음") else settings },
     )
 
     @Test
@@ -114,6 +118,53 @@ class RobotListServiceTest {
         assertEquals(RegistryState.OK, service.read().registry)
     }
 
+    @Test
+    fun `연결 칸과 오래됨은 그 읽기의 현장 설정 버전으로 판정하고 근거 버전을 싣는다`() {
+        val confirmed = r1.copy(status = "CONFIRMED", lastReportedAt = t1.minusSeconds(70).toString())
+        next = RegistryCall.Ok(listOf(confirmed))
+        val before = service.read()
+        assertEquals(Connection.FRESH, before.robots!!.single().connection)
+        assertEquals(1L, before.settingsVersion)
+        assertEquals(90L, before.connectionThresholdSeconds)
+
+        settings = SiteSettingsValues(2, Duration.ofSeconds(60))
+        val after = service.read()
+        val robot = after.robots!!.single()
+        assertEquals(Connection.STALE, robot.connection)
+        assertEquals(2L, after.settingsVersion)
+        assertEquals(60L, after.connectionThresholdSeconds)
+        val stale = robot.blockers.single()
+        assertEquals(Blockers.REPORT_STALE, stale.kind)
+        assertEquals("60초 안의 보고", stale.expected)
+        assertEquals(2L, stale.basisVersion)
+    }
+
+    @Test
+    fun `현장 설정을 못 읽으면 기본값으로 판정하지 않고 실패하며 직전 스냅샷을 바꾸지 않는다`() {
+        next = RegistryCall.Ok(listOf(r1))
+        service.read()
+        clock.now = t2
+        settingsDown = true
+        next = RegistryCall.Ok(emptyList())
+        assertFailsWith<IllegalStateException> { service.read() }
+        settingsDown = false
+        next = RegistryCall.Silent("응답 없음")
+        val view = service.read()
+        assertEquals(listOf(r1), view.robots!!.map { it.robot })
+        assertEquals(t1, view.robotsAsOf)
+    }
+
+    @Test
+    fun `registry 가 침묵하면 직전 스냅샷의 현장 설정 버전을 그대로 보인다`() {
+        next = RegistryCall.Ok(listOf(r1))
+        service.read()
+        settings = SiteSettingsValues(2, Duration.ofSeconds(60))
+        next = RegistryCall.Silent("응답 없음")
+        val view = service.read()
+        assertEquals(1L, view.settingsVersion)
+        assertEquals(90L, view.connectionThresholdSeconds)
+    }
+
     @Test
     fun `늦게 끝난 옛 읽기는 더 새 목록을 덮지 않는다`() {
         clock.now = t2
```

- [ ] **Step 2: 컴파일이 실패하는지 확인**

Run: `cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design" && ./gradlew :ops-service:compileTestKotlin -q`
Expected: 실패. 없는 기호는 `Blockers.of` 의 `basisVersion` 이름 인자, `RobotListService` 다섯째 인자의 형(`Duration` 자리에 함수), `RobotListView.settingsVersion`·`connectionThresholdSeconds`, `Finding.basisVersion` 이다.

- [ ] **Step 3: 구현 패치 넣기**

`C:/Users/Eisen/AppData/Local/Temp/s2-patches/task2-main.patch`(아래 블록과 같다)를 `git apply --check` 로 본 뒤 `git apply` 한다. 이 패치만으로는 `OpsApplication.kt` 가 컴파일되지 않는다(Task 3 에서 고친다). 그래서 이 단계의 시험은 Task 3 뒤에 돈다. **이 단계에서는 Gradle 을 돌리지 않는다.** 돌리면 `OpsApplication.kt` 의 `Duration`/`SettingsSource` 형 불일치만 나온다. Task 2 안에서 `OpsApplication.kt` 를 고치지 않는다.

```diff
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/finding/Finding.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/finding/Finding.kt
index 987376d..c958c79 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/finding/Finding.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/finding/Finding.kt
@@ -10,10 +10,12 @@ enum class Owner { SITE, OPERATOR, ENGINEER, NONE }
  *
  * 화면에 내는 칸 5개와 맞춘다. 종류는 [kind], 관측값과 기대값은 [observed]·[expected], 마지막 확인 시각은
  * [checkedAt], 해결 담당은 [owner]·[inScreen], 바로 갈 링크는 [target] 이다. 링크 모양은 화면이 정한다.
+ * S2 에서 근거 버전 [basisVersion] 칸을 더했다(S2 스펙 §6.4).
  *
  * @param checkedAt 이 판정이 기댄 값을 registry 에서 읽은 시각
  * @param inScreen 화면 안에서 풀 수 있는가. 거짓이면 현장 등 화면 밖에서 풀린다
  * @param target 링크가 가리킬 기체 id. 없으면 널
+ * @param basisVersion 이 판정이 기댄 현장 설정 버전. 현장 설정에 기대지 않는 판정(registry 상태에서 나온 막힘, 조작 거부)은 널
  */
 data class Finding(
     val kind: String,
@@ -24,4 +26,5 @@ data class Finding(
     val inScreen: Boolean,
     val action: String,
     val target: String?,
+    val basisVersion: Long? = null,
 )
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/robots/Blockers.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/robots/Blockers.kt
index 4a1bf9d..d52674c 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/robots/Blockers.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/robots/Blockers.kt
@@ -28,9 +28,12 @@ object Blockers {
         return if (Duration.between(last, at) > threshold) Connection.STALE else Connection.FRESH
     }
 
-    fun of(robot: RegistryRobot, at: Instant, threshold: Duration): List<Finding> = buildList {
-        fun add(kind: String, observed: String, expected: String, owner: Owner, inScreen: Boolean, action: String) =
-            add(Finding(kind, observed, expected, at, owner, inScreen, action, robot.robotId))
+    /**
+     * @param basisVersion [threshold] 가 나온 현장 설정 버전(S2 스펙 §6.4). 기준 시간에 기대는 `REPORT_STALE` 에만 싣는다.
+     */
+    fun of(robot: RegistryRobot, at: Instant, threshold: Duration, basisVersion: Long? = null): List<Finding> = buildList {
+        fun add(kind: String, observed: String, expected: String, owner: Owner, inScreen: Boolean, action: String, basis: Long? = null) =
+            add(Finding(kind, observed, expected, at, owner, inScreen, action, robot.robotId, basis))
 
         if (robot.status == "CLAIMED" && robot.lastReportedAt == null) {
             add(AWAITING_FIRST_REPORT, "보고 0회", "생존 보고 1회 이상", Owner.SITE, false, "기체·어댑터 기동과 사이트 id 확인")
@@ -38,7 +41,7 @@ object Blockers {
         if (robot.status == "CONFIRMED" && connection(robot, at, threshold) == Connection.STALE) {
             add(
                 REPORT_STALE, "마지막 보고 ${robot.lastReportedAt}", "${threshold.seconds}초 안의 보고",
-                Owner.SITE, false, "연결 확인",
+                Owner.SITE, false, "연결 확인", basisVersion,
             )
         }
         if (robot.reportingAfterRetirement) {
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt
index 66fd383..a636712 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt
@@ -8,8 +8,9 @@ import dev.picasso.ops.service.registry.RegistryRobot
 import dev.picasso.ops.service.registry.RegistrySoftware
 import dev.picasso.ops.service.registry.RobotSource
 import dev.picasso.ops.service.registry.TokenProbe
+import dev.picasso.ops.service.settings.SettingsSource
+import dev.picasso.ops.service.settings.SiteSettingsValues
 import java.time.Clock
-import java.time.Duration
 import java.time.Instant
 import java.util.concurrent.atomic.AtomicReference
 
@@ -39,12 +40,16 @@ data class RobotView(
  *
  * @param robots 널이면 «모름»(한 번도 읽지 못했다). 빈 목록은 «없음». 둘을 접지 않는다(스펙 §9).
  * @param robotsAsOf [robots] 를 registry 에서 읽은 시각. [checkedAt] 과 다르면 직전 값이다.
+ * @param settingsVersion [robots] 의 연결 칸과 막힘을 판정한 현장 설정 버전(S2 스펙 §6.4). [robots] 가 널이면 널
+ * @param connectionThresholdSeconds 그 버전의 연결 기준 시간(초)
  */
 data class RobotListView(
     val registry: RegistryState,
     val checkedAt: Instant,
     val robots: List<RobotView>?,
     val robotsAsOf: Instant?,
+    val settingsVersion: Long? = null,
+    val connectionThresholdSeconds: Long? = null,
 )
 
 /**
@@ -56,14 +61,16 @@ data class RobotListView(
  * [commissioning] 을 붙이면 기체 → 바인딩 → 소프트웨어 대조를 함께 읽고 셋 다 읽혀야 새 값으로 바꾼다(P2·S1d 스펙
  * §8.1). 하나라도 못 읽으면 셋 다 직전 값이다. 다른 시각에 읽은 것을 섞으면 서로 맞지 않는 행이 보일 수 있다.
  *
- * @param threshold 연결 칸의 기준 시간(스펙 §7.3). S1 에서는 설정값이다
+ * 연결 칸의 기준 시간은 읽기마다 [settings] 에서 함께 읽는다(S2 스펙 §6.4). 스냅샷은 그 읽기의 현장 설정 버전을 쥐고,
+ * 연결 칸과 막힘은 늘 그 버전 하나로 판정된다. 현장 설정을 못 읽으면 예외가 그대로 나가고 스냅샷은 바뀌지 않는다.
+ * 기본값으로 대신 판정하면 어느 버전으로 판정했는지 말할 수 없다.
  */
 class RobotListService(
     private val source: RobotSource,
     private val token: TokenProbe,
     private val siteId: String,
     private val clock: Clock,
-    private val threshold: Duration,
+    private val settings: SettingsSource,
     private val commissioning: CommissioningSource? = null,
 ) {
     private data class Known(
@@ -71,12 +78,14 @@ class RobotListService(
         val bindings: List<RegistryBinding>?,
         val software: List<RegistrySoftware>?,
         val at: Instant,
+        val settings: SiteSettingsValues,
     )
 
     private val last = AtomicReference<Known?>(null)
 
     fun read(): RobotListView {
         val now = clock.instant()
+        val current = settings.current()
         val robots = source.robots(siteId)
         if (robots !is RegistryCall.Ok) {
             val state = if (robots == RegistryCall.Unauthorized) RegistryState.REGISTRY_UNAUTHORIZED else RegistryState.REGISTRY_SILENT
@@ -97,6 +106,7 @@ class RobotListService(
             (bindings as? RegistryCall.Ok)?.value,
             (software as? RegistryCall.Ok)?.value,
             now,
+            current,
         )
         // 늦게 끝난 옛 읽기가 더 새 값을 덮지 않게 한다. 폴링과 조작 뒤 다시 읽기가 겹칠 수 있다.
         val known = last.updateAndGet { prev -> if (prev != null && prev.at.isAfter(now)) prev else fresh }!!
@@ -114,11 +124,14 @@ class RobotListService(
             checkedAt = now,
             robots = known?.robots?.map { robot -> robotView(robot, known) },
             robotsAsOf = known?.at,
+            settingsVersion = known?.settings?.version,
+            connectionThresholdSeconds = known?.settings?.connectionThreshold?.seconds,
         )
 
     private fun robotView(robot: RegistryRobot, known: Known): RobotView {
+        val threshold = known.settings.connectionThreshold
         val connection = Blockers.connection(robot, known.at, threshold)
-        val base = Blockers.of(robot, known.at, threshold)
+        val base = Blockers.of(robot, known.at, threshold, known.settings.version)
         if (known.bindings == null) return RobotView(robot, connection, base)
         val binding = known.bindings.firstOrNull { it.robotId == robot.robotId && it.active }
         return RobotView(
```

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design"
git add ops-service/src/main/kotlin/dev/picasso/ops/service/finding/Finding.kt ops-service/src/main/kotlin/dev/picasso/ops/service/robots/Blockers.kt ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt ops-service/src/test/kotlin/dev/picasso/ops/service/BlockersTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RobotListServiceTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RobotCommissioningListTest.kt
git commit -q -F - <<'EOF'
feat(s2): 기체 목록의 현장 설정 버전 판정과 근거 버전 칸 추가

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 3: 변경 조작과 API

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettingsOperations.kt`
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/web/SiteSettingsController.kt`
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt`, `ops-service/src/main/resources/ops-service.properties`, `site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt`(주석)
- Test: `ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt`

- [ ] **Step 1: 실패하는 시험 쓰기**: `ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt`(`files/` 에서 복사)

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.OperationOutcome
import dev.picasso.ops.service.settings.SiteSettingsOperations
import dev.picasso.ops.service.settings.SiteSettingsStore
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 현장 설정 변경(S2 스펙 §6.2·§6.3). 새 버전 행과 조작 기록 행이 같이 들어가거나 같이 안 들어간다. */
class SiteSettingsOperationsTest {

    private val dataSource = DriverManagerDataSource(
        PostgresSupport.jdbcUrl,
        PostgresSupport.username,
        PostgresSupport.password,
    )
    private val jdbc = JdbcClient.create(dataSource)
    private val store = SiteSettingsStore(jdbc)
    private val log = OperationLog(jdbc)
    private val at = Instant.parse("2026-10-08T00:00:00Z")
    private val operations = SiteSettingsOperations(
        store, log, TransactionTemplate(DataSourceTransactionManager(dataSource)), Clock.fixed(at, ZoneOffset.UTC),
    )
    private val lee = Actor(Mode.ENGINEER, "lee")
    private val json = ObjectMapper()

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `기준 버전이 지금 버전이면 다음 버전을 넣고 조작 기록에 성공 행을 남긴다`() {
        val outcome = operations.change(lee, 1, 60, "시험")
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertNull(outcome.rejection)
        assertNull(outcome.registryStatus)
        val latest = store.latest()
        assertEquals(2L, latest.version)
        assertEquals(60, latest.connectionThresholdSeconds)
        assertEquals("lee", latest.user)
        assertEquals("시험", latest.reason)
        val record = log.list().single()
        assertEquals(outcome.requestId, record.requestId)
        assertEquals(SiteSettingsOperations.TARGET, record.target)
        assertEquals(OperationResult.SUCCEEDED, record.result)
        assertEquals("시험", record.reason)
        assertNull(record.registryResponse)
        assertEquals(
            json.readTree("""{"op":"CHANGE_SITE_SETTINGS","baseVersion":1,"connectionThresholdSeconds":60}"""),
            json.readTree(record.request),
        )
    }

    @Test
    fun `지난 기준 버전 위의 변경은 버전 충돌로 거부하고 조작 기록에 거부 행을 남긴다`() {
        operations.change(lee, 1, 60, "먼저")
        val outcome = operations.change(Actor(Mode.ENGINEER, "park"), 1, 120, "늦게")
        assertEquals(OperationResult.REJECTED, outcome.result)
        val rejection = outcome.rejection!!
        assertEquals(SiteSettingsOperations.VERSION_CONFLICT, rejection.kind)
        assertEquals("현재 버전 2", rejection.observed)
        assertEquals("기준 버전 1", rejection.expected)
        assertEquals(Owner.ENGINEER, rejection.owner)
        assertEquals(true, rejection.inScreen)
        assertEquals(at, rejection.checkedAt)
        assertNull(rejection.target)
        assertNull(rejection.basisVersion)
        assertEquals(60, store.latest().connectionThresholdSeconds)
        assertEquals(listOf(OperationResult.REJECTED, OperationResult.SUCCEEDED), log.list().map { it.result })
    }

    @Test
    fun `아직 없는 버전을 기준으로 보내면 번호를 건너뛰지 않고 거부한다`() {
        val outcome = operations.change(lee, 5, 60, "앞선 기준")
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals("현재 버전 1", outcome.rejection!!.observed)
        assertEquals(listOf(1L), store.history().map { it.version })
    }

    @Test
    fun `같은 번호를 다른 변경이 먼저 커밋하면 기본 키가 막고 버전 충돌로 거부 행을 남긴다`() {
        val pool = Executors.newSingleThreadExecutor()
        dataSource.connection.use { other ->
            other.autoCommit = false
            other.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO ops.site_settings (version, connection_threshold_seconds, mode, actor_user, reason) " +
                        "VALUES (2, 120, 'ENGINEER', 'park', '먼저')",
                )
            }
            val pending = pool.submit<OperationOutcome> { operations.change(lee, 1, 60, "늦게") }
            // 변경이 버전 2 의 기본 키 잠금을 기다릴 때까지 본다. 그 전에 커밋하면 비교 경로로 간다.
            val deadline = System.nanoTime() + 10_000_000_000
            while (PostgresSupport.queryOne(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'INSERT INTO ops.site_settings%'",
                ) { it.getInt(1) } == 0
            ) {
                check(System.nanoTime() < deadline) { "변경이 잠금을 기다리지 않았다" }
                Thread.sleep(20)
            }
            other.commit()
            val outcome = pending.get()
            assertEquals(OperationResult.REJECTED, outcome.result)
            assertEquals("현재 버전 2", outcome.rejection!!.observed)
        }
        pool.shutdownNow()
        assertEquals(120, store.latest().connectionThresholdSeconds)
        assertEquals(listOf(OperationResult.REJECTED), log.list().map { it.result })
    }

    @Test
    fun `범위 밖 값은 관문이 막으므로 여기까지 오면 계약 위반이다`() {
        assertFailsWith<IllegalArgumentException> { operations.change(lee, 1, 59, "범위 밖") }
        assertEquals(listOf(1L), store.history().map { it.version })
        assertEquals(emptyList(), log.list())
    }
}
```

- [ ] **Step 2: 변경 조작**: `ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettingsOperations.kt`(`files/` 에서 복사)

```kotlin
package dev.picasso.ops.service.settings

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.OperationOutcome
import org.springframework.dao.DuplicateKeyException
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.util.UUID

/**
 * 현장 설정 변경(S2 스펙 §6.2). registry 를 부르지 않으므로 `OperationRunner` 를 거치지 않는다. 같은 DB 라 응답 없음과
 * 재조회가 없다. 새 버전 행과 조작 기록의 `SUCCEEDED` 행은 한 트랜잭션에 들어간다.
 *
 * 범위·사유·본문 검사는 부르는 쪽(웹 관문)이 먼저 한다. 여기 오는 요청은 그것을 지난 것이다.
 */
class SiteSettingsOperations(
    private val store: SiteSettingsStore,
    private val log: OperationLog,
    private val tx: TransactionOperations,
    private val clock: Clock,
    private val json: ObjectMapper = ObjectMapper(),
) {
    private sealed interface Change {
        data object Applied : Change
        data class Conflict(val current: Long) : Change
    }

    fun change(actor: Actor, baseVersion: Long, connectionThresholdSeconds: Int, reason: String): OperationOutcome {
        require(SiteSettingsRange.allows(connectionThresholdSeconds.toLong())) { "범위 밖 값은 관문이 막는다: $connectionThresholdSeconds" }
        val requestId = UUID.randomUUID()
        val request = json.createObjectNode()
            .put("op", "CHANGE_SITE_SETTINGS")
            .put("baseVersion", baseVersion)
            .put("connectionThresholdSeconds", connectionThresholdSeconds)
            .toString()
        val change = try {
            tx.execute {
                val current = store.latest().version
                // 기준 버전이 지금 버전이 아니면 넣지 않는다. 지난 버전 위의 변경은 기본 키가 막지만, 아직 없는 버전을
                // 기준으로 보내면 번호가 건너뛴 행이 들어간다.
                if (current != baseVersion) return@execute Change.Conflict(current)
                store.insert(baseVersion + 1, connectionThresholdSeconds, actor, reason)
                log.append(requestId, actor, TARGET, request, reason, OperationResult.SUCCEEDED, null)
                Change.Applied
            }!!
        } catch (e: DuplicateKeyException) {
            // 같은 기준 버전 위의 다른 변경이 먼저 들어갔다. 이 트랜잭션은 되돌려졌다.
            Change.Conflict(store.latest().version)
        }
        return when (change) {
            Change.Applied -> OperationOutcome(requestId, OperationResult.SUCCEEDED, null, null, false, null)
            is Change.Conflict -> {
                log.append(requestId, actor, TARGET, request, reason, OperationResult.REJECTED, null)
                val rejection = Finding(
                    VERSION_CONFLICT, "현재 버전 ${change.current}", "기준 버전 $baseVersion",
                    clock.instant(), Owner.ENGINEER, true, "현재 값을 다시 읽고 다시", null,
                )
                OperationOutcome(requestId, OperationResult.REJECTED, null, rejection, false, null)
            }
        }
    }

    companion object {
        const val TARGET = "site-settings"
        const val VERSION_CONFLICT = "SETTINGS_VERSION_CONFLICT"
    }
}
```

- [ ] **Step 3: API**: `ops-service/src/main/kotlin/dev/picasso/ops/service/web/SiteSettingsController.kt`(`files/` 에서 복사)

```kotlin
package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.settings.SiteSettingsOperations
import dev.picasso.ops.service.settings.SiteSettingsRange
import dev.picasso.ops.service.settings.SiteSettingsRecord
import dev.picasso.ops.service.settings.SiteSettingsStore
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 허용 범위(초). 화면은 이 값으로 입력을 막고, 서버도 같은 값으로 막는다. */
data class SiteSettingsRangeView(val minConnectionThresholdSeconds: Int, val maxConnectionThresholdSeconds: Int)

/** `GET /api/site-settings` 의 응답(S2 스펙 §6.1). [history] 는 최신부터이며 첫 행이 [current] 다. */
data class SiteSettingsView(
    val current: SiteSettingsRecord,
    val range: SiteSettingsRangeView,
    val history: List<SiteSettingsRecord>,
)

/**
 * 현장 설정 API(S2 스펙 §6). 읽기는 모드와 관계없고, 변경은 엔지니어 모드만 한다.
 *
 * 변경 본문은 바이트로 받아 관문을 지난 뒤 직접 읽는다. 스프링에 맡기면 못 읽는 본문의 400 이 [PreRejection] 모양이 아니고,
 * 관문보다 먼저 읽혀 운영자 모드의 깨진 본문이 403 이 아니라 400 이 된다. 칸은 널 가능으로 읽는다. 빈 칸을 0 으로 읽으면
 * 누락이 버전 충돌이나 범위 밖으로 갈린다. 범위 밖, 사유 빈칸, 못 읽는 본문은 사전 거부라 조작 기록에 남지 않는다.
 * 기존 `PROFILE_REQUIRED` 와 같은 길이다. 쓰기 본문은 `application/json` 만 받는다(다른 출처 방어는 [RobotOperationsController]).
 *
 * 같은 값으로 바꿔도 새 버전이 생긴다. 버전은 값이 아니라 변경의 기록이다.
 */
@RestController
class SiteSettingsController(
    private val store: SiteSettingsStore,
    private val operations: SiteSettingsOperations,
) {
    private val json = ObjectMapper()

    @GetMapping("/api/site-settings")
    fun read(): SiteSettingsView {
        val history = store.history()
        return SiteSettingsView(
            current = history.first(),
            range = SiteSettingsRangeView(
                SiteSettingsRange.MIN_CONNECTION_THRESHOLD_SECONDS,
                SiteSettingsRange.MAX_CONNECTION_THRESHOLD_SECONDS,
            ),
            history = history,
        )
    }

    @PutMapping("/api/site-settings", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun change(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        val node = body?.let { runCatching { json.readTree(it) }.getOrNull() }
        val base = node.long("baseVersion")
        val seconds = node.long("connectionThresholdSeconds")
        if (base == null || seconds == null) {
            return@guarded reject(
                HttpStatus.BAD_REQUEST, "SETTINGS_BAD_REQUEST", "baseVersion 과 connectionThresholdSeconds 가 정수로 있어야 한다",
            )
        }
        if (!SiteSettingsRange.allows(seconds)) {
            return@guarded reject(
                HttpStatus.BAD_REQUEST, "SETTING_OUT_OF_RANGE",
                "연결 기준 시간 ${seconds}초는 범위 밖이다. " +
                    "${SiteSettingsRange.MIN_CONNECTION_THRESHOLD_SECONDS}~${SiteSettingsRange.MAX_CONNECTION_THRESHOLD_SECONDS}초여야 한다",
            )
        }
        val reason = node?.get("reason")?.takeIf { it.isTextual }?.asText()?.trim()
        if (reason.isNullOrEmpty()) {
            return@guarded reject(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "변경 사유가 없다")
        }
        ResponseEntity.ok(operations.change(actor, base, seconds.toInt(), reason))
    }

    /** 정수 칸만 받는다. 글자나 소수로 온 값은 없는 것으로 본다. */
    private fun JsonNode?.long(field: String): Long? =
        this?.get(field)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
}
```

- [ ] **Step 4: 빈 배선, 설정 파일, 주석**

`C:/Users/Eisen/AppData/Local/Temp/s2-patches/task3.patch`(아래 블록과 같다)를 `git apply --check` 로 본 뒤 `git apply` 한다.

```diff
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
index 981a7d2..6984dfa 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
@@ -8,6 +8,8 @@ import dev.picasso.ops.service.operations.AdapterOperations
 import dev.picasso.ops.service.operations.RobotOperations
 import dev.picasso.ops.service.registry.RegistryClient
 import dev.picasso.ops.service.robots.RobotListService
+import dev.picasso.ops.service.settings.SiteSettingsOperations
+import dev.picasso.ops.service.settings.SiteSettingsStore
 import dev.picasso.ops.service.store.OpsSchema
 import dev.picasso.ops.service.store.OpsSchemaMigrated
 import dev.picasso.ops.service.web.SiteId
@@ -17,8 +19,9 @@ import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
 import org.springframework.boot.builder.SpringApplicationBuilder
 import org.springframework.context.annotation.Bean
 import org.springframework.jdbc.core.simple.JdbcClient
+import org.springframework.jdbc.datasource.DataSourceTransactionManager
+import org.springframework.transaction.support.TransactionTemplate
 import java.time.Clock
-import java.time.Duration
 import javax.sql.DataSource
 
 /** 운영 서비스(스펙 §7). Flyway 자동설정을 끄는 이유는 [OpsSchema] 에 있다. */
@@ -49,14 +52,14 @@ open class OpsApplication {
         return RegistryClient(url, token)
     }
 
-    /** [threshold] 는 연결 칸의 기준 시간이다(스펙 §7.3). S2 에서 데이터로 옮긴다. */
+    /** 연결 칸의 기준 시간은 현장 설정 버전에서 읽는다(S2 스펙 §6.4). S1 에서는 설정 파일 값이었다. */
     @Bean
     open fun robotList(
         registry: RegistryClient,
         siteId: SiteId,
         clock: Clock,
-        @Value("\${ops.connection.threshold}") threshold: Duration,
-    ): RobotListService = RobotListService(registry, registry, siteId.value, clock, threshold, commissioning = registry)
+        settings: SiteSettingsStore,
+    ): RobotListService = RobotListService(registry, registry, siteId.value, clock, settings, commissioning = registry)
 
     @Bean
     open fun adapterList(registry: RegistryClient, siteId: SiteId, clock: Clock): AdapterListService =
@@ -89,6 +92,23 @@ open class OpsApplication {
         clock: Clock,
     ): ProfileOperations = ProfileOperations(registry, registry, registry, registry, log, siteId.value, clock)
 
+    /** [migrated] 는 쓰지 않는다. 받는 것만으로 ops 마이그레이션 뒤에 이 빈이 만들어진다. */
+    @Bean
+    open fun siteSettings(
+        jdbc: JdbcClient,
+        @Suppress("UNUSED_PARAMETER") migrated: OpsSchemaMigrated,
+    ): SiteSettingsStore = SiteSettingsStore(jdbc)
+
+    /** 새 버전 행과 조작 기록 행을 한 트랜잭션에 넣는다(S2 스펙 §6.2). */
+    @Bean
+    open fun siteSettingsOperations(
+        settings: SiteSettingsStore,
+        log: OperationLog,
+        dataSource: DataSource,
+        clock: Clock,
+    ): SiteSettingsOperations =
+        SiteSettingsOperations(settings, log, TransactionTemplate(DataSourceTransactionManager(dataSource)), clock)
+
     /** [migrated] 는 쓰지 않는다. 받는 것만으로 ops 마이그레이션 뒤에 이 빈이 만들어진다. */
     @Bean
     open fun operationLog(
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt
index d4854ee..64980ca 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt
@@ -5,7 +5,10 @@ import dev.picasso.ops.service.actor.Mode
 import org.springframework.http.HttpStatus
 import org.springframework.http.ResponseEntity
 
-/** registry 에 보내기 전에 막은 요청의 답. 조작 기록에 남기지 않는다. registry 에 닿지 않은 요청은 조작이 아니다. */
+/**
+ * 관문에서 막은 요청의 답. 조작 기록에 남기지 않는다. 상태를 바꾸는 쪽(registry, 또는 S2 의 현장 설정)에 닿지 않은 요청은
+ * 조작이 아니다(S2 스펙 §6.2).
+ */
 data class PreRejection(val error: String, val detail: String)
 
 /**
diff --git a/ops-service/src/main/resources/ops-service.properties b/ops-service/src/main/resources/ops-service.properties
index 7b62ebf..91b7ed9 100644
--- a/ops-service/src/main/resources/ops-service.properties
+++ b/ops-service/src/main/resources/ops-service.properties
@@ -12,6 +12,3 @@ spring.datasource.password=${PICASSO_DB_PASSWORD}
 ops.site-id=${SITE_ID}
 ops.registry.url=http://127.0.0.1:${REGISTRY_PORT}
 ops.registry.operator-token=${PICASSO_OPERATOR_TOKEN}
-
-# 연결 칸의 기준 시간(스펙 §7.3). 프로파일 보고 주기 상한 30초의 3배다. 런처의 시간 진행 비율(1:1)이 바뀌면 다시 정한다.
-ops.connection.threshold=90s
diff --git a/site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt b/site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt
index a67f43e..7b3480c 100644
--- a/site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt
+++ b/site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt
@@ -6,7 +6,8 @@ import java.util.concurrent.Executors
 import java.util.concurrent.TimeUnit
 
 /**
- * 실제 1초마다 가상 1초를 민다(1:1, 스펙 §6 ③). 비율을 바꾸면 운영 서비스의 연결 기준 90초(스펙 §7.3)도 다시 정한다.
+ * 실제 1초마다 가상 1초를 민다(1:1, 스펙 §6 ③). 비율을 바꾸면 운영 서비스의 연결 기준 시간 버전 1 의 90초와
+ * 허용 범위 하한 60초(S2 스펙 §5)도 다시 정한다.
  * 주기와 전진량이 같은 값이어야 1:1 이므로 하나로 둔다.
  */
 val TICK: Duration = Duration.ofSeconds(1)
```

- [ ] **Step 5: ops-service 시험 전체 통과 확인**

Run: `cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design" && ./gradlew :ops-service:test -q`
Expected: XML 에 123개(기존 107 + 새 16), 실패 0.

- [ ] **Step 6: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design"
git add ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettingsOperations.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/SiteSettingsController.kt ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt ops-service/src/main/resources/ops-service.properties site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt
git commit -q -F - <<'EOF'
feat(s2): 현장 설정 변경 조작과 API 추가

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **Step 7: 묶음 대조**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s2-cmp.sh" ops-service/src/main/resources/db/ops/V2__site_settings.sql ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettings.kt ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettingsOperations.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/SiteSettingsController.kt ops-service/src/main/kotlin/dev/picasso/ops/service/finding/Finding.kt ops-service/src/main/kotlin/dev/picasso/ops/service/robots/Blockers.kt ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt ops-service/src/main/resources/ops-service.properties site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsStoreTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/BlockersTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RobotListServiceTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RobotCommissioningListTest.kt
```
Expected: 16줄 모두 `같음`.

---

## Chunk 2: 통합 시험, 화면, Playwright

### Task 4: 통합 시험

**Files:**
- Create: `e2e/src/test/kotlin/dev/picasso/ops/e2e/SiteSettingsTest.kt`
- Modify: `e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt`(ops 마이그레이션 기대값)

- [ ] **Step 1: 통합 시험 쓰기**: `e2e/src/test/kotlin/dev/picasso/ops/e2e/SiteSettingsTest.kt`(`files/` 에서 복사)

```kotlin
package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S2 완료 판정(S2 스펙 §3). 연결 기준 시간을 화면 API 로 바꾸면 코드 수정과 재기동 없이 다음 기체 목록 읽기부터 그 값으로
 * 판정하고, 막힘이 근거 버전을 싣는다. 순서가 있다. 앞 시험의 버전과 기체 상태를 뒤 시험이 이어받는다.
 *
 * 오래됨은 실제 시간이 흘러야 생긴다. registry 는 보고 시각을 실제 시계로 찍고, 이 하네스에는 시간을 미는 반복 작업이
 * 없어 [report] 를 부르지 않으면 보고가 멈춘다. 그래서 (4) 는 60초 남짓 실제로 기다린다. 시험 전용 시계 이음새는 두지 않는다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SiteSettingsTest {

    companion object {
        private const val ROBOT = "humanoid-01"
        private lateinit var stack: E2eStack

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }
    }

    private fun settings(): JsonNode = stack.get("/api/site-settings")

    private fun list(): JsonNode = stack.get("/api/robots")

    private fun robot(view: JsonNode = list()): JsonNode = view["robots"].single { it["robot"]["robotId"].asText() == ROBOT }

    private fun change(base: Long, seconds: Long, reason: String = "시험", mode: String = "engineer") =
        stack.send(
            "PUT", "/api/site-settings", mode,
            body = """{"baseVersion":$base,"connectionThresholdSeconds":$seconds,"reason":"$reason"}""",
        )

    private fun settingsRows() = stack.get("/api/operations").filter { it["target"].asText() == "site-settings" }

    /** 프로파일의 상태 발행 주기 상한 30초를 넘겨 민다. 상태 발행이 곧 생존 보고다. */
    private fun report() = stack.site.advance(Duration.ofSeconds(31))

    @Test
    @Order(1)
    fun `기동 직후 현장 설정은 버전 1 의 90초다`() {
        val view = settings()
        assertEquals(1, view["current"]["version"].asLong())
        assertEquals(90, view["current"]["connectionThresholdSeconds"].asInt())
        assertEquals(60, view["range"]["minConnectionThresholdSeconds"].asInt())
        assertEquals(3600, view["range"]["maxConnectionThresholdSeconds"].asInt())
        assertEquals(listOf(1L), view["history"].map { it["version"].asLong() })
    }

    @Test
    @Order(2)
    fun `선언하고 첫 보고로 CONFIRMED 가 된 뒤 보고를 멈춘다`() {
        val reply = stack.send("POST", "/api/robots", "engineer", body = """{"robotId":"$ROBOT","serialNumber":"HA-0001"}""")
        assertEquals("SUCCEEDED", reply.body!!["result"].asText())
        report()
        val view = list()
        val robot = robot(view)
        assertEquals("CONFIRMED", robot["robot"]["status"].asText())
        assertEquals("FRESH", robot["connection"].asText())
        assertEquals(1, view["settingsVersion"].asLong())
        assertEquals(90, view["connectionThresholdSeconds"].asLong())
    }

    @Test
    @Order(3)
    fun `엔지니어가 버전 1 위에서 60초로 바꾸면 버전 2 이고 조작 기록에 성공 행이 남는다`() {
        val reply = change(1, 60, "연결 기준 줄임")
        assertEquals(200, reply.status)
        assertEquals("SUCCEEDED", reply.body!!["result"].asText())
        val view = settings()
        assertEquals(2, view["current"]["version"].asLong())
        assertEquals(60, view["current"]["connectionThresholdSeconds"].asInt())
        assertEquals("kim", view["current"]["user"].asText())
        assertEquals("연결 기준 줄임", view["current"]["reason"].asText())
        val row = settingsRows().single()
        assertEquals("SUCCEEDED", row["result"].asText())
        assertEquals("ENGINEER", row["mode"].asText())
    }

    @Test
    @Order(4)
    fun `마지막 보고가 60초보다 오래되면 근거 버전 2 의 오래됨으로 막힌다`() {
        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        var view = list()
        while (robot(view)["connection"].asText() != "STALE") {
            check(System.nanoTime() < deadline) { "90초 안에 오래됨이 되지 않았다: ${robot(view)}" }
            Thread.sleep(2_000)
            view = list()
        }
        assertEquals(2, view["settingsVersion"].asLong())
        val stale = robot(view)["blockers"].single { it["kind"].asText() == "REPORT_STALE" }
        assertEquals("60초 안의 보고", stale["expected"].asText())
        assertEquals(2, stale["basisVersion"].asLong())
        // registry 상태에서 나온 막힘은 근거 버전이 없다.
        val unbound = robot(view)["blockers"].single { it["kind"].asText() == "UNBOUND" }
        assertTrue(unbound["basisVersion"].isNull)
    }

    @Test
    @Order(5)
    fun `버전 2 위에서 3600초로 바꾸면 버전 3 이고 다음 읽기에서 오래됨이 풀린다`() {
        assertEquals("SUCCEEDED", change(2, 3600, "되돌림").body!!["result"].asText())
        val view = list()
        assertEquals(3, view["settingsVersion"].asLong())
        assertEquals(3600, view["connectionThresholdSeconds"].asLong())
        val robot = robot(view)
        assertEquals("FRESH", robot["connection"].asText())
        assertEquals(listOf("UNBOUND"), robot["blockers"].map { it["kind"].asText() })
    }

    @Test
    @Order(6)
    fun `지난 기준 버전으로 바꾸려 하면 버전 충돌로 거부되고 조작 기록에 거부 행이 남는다`() {
        val reply = change(1, 120, "늦은 변경")
        assertEquals(200, reply.status)
        assertEquals("REJECTED", reply.body!!["result"].asText())
        val rejection = reply.body!!["rejection"]
        assertEquals("SETTINGS_VERSION_CONFLICT", rejection["kind"].asText())
        assertEquals("현재 버전 3", rejection["observed"].asText())
        assertEquals(3, settings()["current"]["version"].asLong())
        assertEquals(listOf("REJECTED", "SUCCEEDED", "SUCCEEDED"), settingsRows().map { it["result"].asText() })
    }

    @Test
    @Order(7)
    fun `범위 밖 값과 운영자 모드는 보내기 전에 막히고 조작 기록에 남지 않는다`() {
        val outOfRange = change(3, 30)
        assertEquals(400, outOfRange.status)
        assertEquals("SETTING_OUT_OF_RANGE", outOfRange.body!!["error"].asText())
        val noReason = change(3, 120, reason = " ")
        assertEquals(400, noReason.status)
        assertEquals("REASON_REQUIRED", noReason.body!!["error"].asText())
        val unreadable = stack.send("PUT", "/api/site-settings", "engineer", body = "60초로")
        assertEquals(400, unreadable.status)
        assertEquals("SETTINGS_BAD_REQUEST", unreadable.body!!["error"].asText())
        val operator = change(3, 120, mode = "operator")
        assertEquals(403, operator.status)
        assertEquals("MODE_NOT_ALLOWED", operator.body!!["error"].asText())
        assertEquals(3, settings()["current"]["version"].asLong())
        assertEquals(3, settingsRows().size)
    }
}
```

- [ ] **Step 2: 기존 시험의 기대값 고치기**

`C:/Users/Eisen/AppData/Local/Temp/s2-patches/task4.patch`(아래 블록과 같다)를 `git apply --check` 로 본 뒤 `git apply` 한다.

```diff
diff --git a/e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt b/e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt
index b0abee8..dc47d46 100644
--- a/e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt
+++ b/e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt
@@ -53,7 +53,7 @@ class SkeletonTest {
         val versions = PostgresSupport.queryAll(
             "SELECT version FROM ops.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
         ) { it.getString(1) }
-        assertEquals(listOf("1"), versions)
+        assertEquals(listOf("1", "2"), versions)
         assertEquals(0, stack.get("/api/operations").size())
     }
 
```

- [ ] **Step 3: e2e 전체 통과 확인(백그라운드)**

Run: `cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design" && ./gradlew :e2e:test -q` 를 백그라운드로 돌린다(약 2분, `SiteSettingsTest` 의 오래됨 대기가 약 61초).
Expected: `e2e/build/test-results/test/*.xml` 에 37개(기존 30 + 새 7), 실패 0.

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design"
git add e2e/src/test/kotlin/dev/picasso/ops/e2e/SiteSettingsTest.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt
git commit -q -F - <<'EOF'
test(s2): 현장 설정 변경 통합 시험 추가

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 5: 화면

**Files:**
- Create: `ui/src/components/SiteArea.tsx`, `ui/src/components/SiteArea.test.tsx`
- Modify: `ui/src/api.ts`, `ui/src/areas.ts`, `ui/src/labels.ts`, `ui/src/App.tsx`, `ui/src/App.test.tsx`, `ui/src/components/FindingCard.tsx`, `ui/src/components/RobotDetail.tsx`, `ui/src/components/RobotsArea.tsx`, `ui/src/testing/fakeOps.ts`

- [ ] **Step 1: 실패하는 시험 쓰기**: `ui/src/components/SiteArea.test.tsx`(`files/` 에서 복사)

```tsx
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Finding, Session, SiteSettingsView } from '../api'
import { installFakeOps, outcome, robotView, settingsView } from '../testing/fakeOps'
import { SiteArea } from './SiteArea'

const engineer: Session = { mode: 'engineer', user: 'local' }
const operator: Session = { mode: 'operator', user: 'local' }

const version2 = {
  version: 2,
  connectionThresholdSeconds: 60,
  mode: 'ENGINEER',
  user: 'kim',
  reason: '연결 기준 줄임',
  recordedAt: 't2',
}

const twoVersions: SiteSettingsView = settingsView({
  current: version2,
  history: [version2, settingsView().current],
})

/** 운영 서비스의 `SiteSettingsOperations` 가 내는 모양 그대로다. */
const conflict: Finding = {
  kind: 'SETTINGS_VERSION_CONFLICT',
  observed: '현재 버전 2',
  expected: '기준 버전 1',
  checkedAt: 't3',
  owner: 'ENGINEER',
  inScreen: true,
  action: '현재 값을 다시 읽고 다시',
  target: null,
  basisVersion: null,
}

/** 칸 이름(dt) 바로 뒤의 값(dd)을 읽는다. */
function field(region: HTMLElement, name: string) {
  const term = within(region).getByText(name, { selector: 'dt' })
  return term.nextElementSibling?.textContent
}

async function fill(seconds: string, reason: string) {
  const form = screen.getByRole('form', { name: '현장 설정 변경' })
  await userEvent.type(within(form).getByLabelText('연결 기준 시간(초)'), seconds)
  await userEvent.type(within(form).getByLabelText('변경 사유'), reason)
  return form
}

describe('현장·자원 영역', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('현재 버전과 연결 기준 시간, 허용 범위, 버전 이력을 최신부터 보인다', () => {
    installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<SiteArea view={twoVersions} opsError={null} session={engineer} onChanged={() => undefined} />)
    const section = screen.getByRole('region', { name: '현장 설정' })
    expect(field(section, '현재 버전')).toBe('2')
    expect(field(section, '연결 기준 시간')).toBe('60초')
    expect(field(section, '허용 범위')).toBe('60~3600초')
    const rows = within(screen.getByRole('table', { name: '현장 설정 버전 이력' })).getAllByRole('row').slice(1)
    expect(rows.map((row) => row.textContent)).toEqual([
      '260초kim엔지니어연결 기준 줄임t2',
      '190초system엔지니어S1 설정값 이전t0',
    ])
  })

  it('운영자 모드에서는 변경 폼 대신 엔지니어 모드에서 한다고 보인다', () => {
    installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<SiteArea view={twoVersions} opsError={null} session={operator} onChanged={() => undefined} />)
    expect(screen.queryByRole('form', { name: '현장 설정 변경' })).not.toBeInTheDocument()
    expect(screen.getByText('현장 설정 변경은 엔지니어 모드에서 합니다')).toBeInTheDocument()
  })

  it('기준 버전은 고치기 시작한 버전으로 고정해 엔지니어 모드로 보낸다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    const { rerender } = render(
      <SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />,
    )
    const form = await fill('60', '연결 기준 줄임')
    // 고치는 사이 다른 사람이 버전 2 를 올렸다. 다시 읽은 버전이 아니라 고치기 시작한 버전 1 을 보낸다.
    rerender(<SiteArea view={twoVersions} opsError={null} session={engineer} onChanged={() => undefined} />)
    expect(within(form).getByText('기준 버전 1')).toBeInTheDocument()
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'PUT')).toBe(true))
    const put = fake.calls.find((call) => call.method === 'PUT')!
    expect(put.url).toBe('/api/site-settings')
    expect(put.body).toEqual({ baseVersion: 1, connectionThresholdSeconds: 60, reason: '연결 기준 줄임' })
    expect(put.headers['X-Ops-Mode']).toBe('engineer')
    expect(put.headers['Content-Type']).toBe('application/json')
    expect(await screen.findByText('연결 기준 시간 60초로 변경: 반영됨')).toBeInTheDocument()
  })

  it('범위 밖 값과 빈 사유는 보내지 않는다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />)
    let form = await fill('59', '짧게')
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    expect(within(form).getByRole('alert')).toHaveTextContent('연결 기준 시간은 60~3600초의 정수여야 합니다')
    await userEvent.clear(within(form).getByLabelText('연결 기준 시간(초)'))
    await userEvent.clear(within(form).getByLabelText('변경 사유'))
    form = await fill('120', ' ')
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    expect(within(form).getByRole('alert')).toHaveTextContent('변경 사유를 넣으십시오')
    expect(fake.calls.some((call) => call.method === 'PUT')).toBe(false)
  })

  it('버전 충돌 거부는 관측값과 후속 행동, 근거 버전 해당 없음으로 보인다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    fake.answer = { status: 200, body: outcome({ result: 'REJECTED', rejection: conflict }) }
    render(<SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />)
    const form = await fill('120', '늦은 변경')
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    const notice = await screen.findByRole('status')
    expect(notice).toHaveTextContent('연결 기준 시간 120초로 변경: 거절됨')
    expect(field(notice, '종류')).toBe('현장 설정 버전 충돌')
    expect(field(notice, '관측값과 기대값')).toBe('현재 버전 2 / 기대: 기준 버전 1')
    expect(field(notice, '근거 버전')).toBe('해당 없음')
    expect(field(notice, '해결 담당')).toBe('엔지니어(화면 안): 현재 값을 다시 읽고 다시')
  })

  it('현장 설정을 읽지 못했으면 모름을 보이고 폼을 내지 않는다', () => {
    render(<SiteArea view={null} opsError="닿지 않음" session={engineer} onChanged={() => undefined} />)
    expect(screen.getByText('모름: 현장 설정을 아직 읽지 못했습니다')).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: '현장 설정 변경' })).not.toBeInTheDocument()
  })

  it('현장 설정을 못 읽으면 다섯 읽기 모두 직전 값으로 남는다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    fake.failing.add('/api/site-settings')
    render(<App />)
    await userEvent.click(screen.getByRole('button', { name: '현장·자원' }))
    expect(await screen.findByText('모름: 현장 설정을 아직 읽지 못했습니다')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '로봇·연결' }))
    expect(screen.getByText('모름: 기체 목록을 아직 읽지 못했습니다')).toBeInTheDocument()
  })

  it('기체 상세는 연결 판정 기준을, 오래됨 막힘은 근거 버전을 보인다', async () => {
    const stale: Finding = {
      kind: 'REPORT_STALE',
      observed: '마지막 보고 t0',
      expected: '60초 안의 보고',
      checkedAt: 't1',
      owner: 'SITE',
      inScreen: false,
      action: '연결 확인',
      target: 'humanoid-01',
      basisVersion: 2,
    }
    installFakeOps({
      registry: 'OK',
      checkedAt: 't1',
      robots: [{ ...robotView('humanoid-01', 'CONFIRMED', [stale]), connection: 'STALE' }],
      robotsAsOf: 't1',
      settingsVersion: 2,
      connectionThresholdSeconds: 60,
    })
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'humanoid-01' }))
    const detail = screen.getByRole('region', { name: 'humanoid-01 상세' })
    expect(field(detail, '연결')).toBe('오래됨')
    expect(field(detail, '연결 판정 기준')).toBe('기준 60초, 현장 설정 버전 2')
    expect(field(detail, '근거 버전')).toBe('현장 설정 버전 2')
  })
})
```

- [ ] **Step 2: 기존 파일 패치**

`C:/Users/Eisen/AppData/Local/Temp/s2-patches/task5.patch`(아래 블록과 같다)를 `git apply --check` 로 본 뒤 `git apply` 한다.

```diff
diff --git a/ui/src/App.test.tsx b/ui/src/App.test.tsx
index 13011f2..45e356f 100644
--- a/ui/src/App.test.tsx
+++ b/ui/src/App.test.tsx
@@ -43,12 +43,12 @@ function serve(view: RobotListView, records: OperationRecord[] = []) {
 describe('App', () => {
   afterEach(() => vi.unstubAllGlobals())
 
-  it('메뉴가 5영역이고 S1 에서 닫힌 3영역은 다음 단계로 표시한다', () => {
+  it('메뉴가 5영역이고 아직 닫힌 2영역은 다음 단계로 표시한다', () => {
     serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
     render(<App />)
     const nav = screen.getByRole('navigation', { name: '영역' })
     expect(within(nav).getAllByRole('button').map((b) => b.textContent)).toEqual([
-      '현장·자원 다음 단계',
+      '현장·자원',
       '로봇·연결',
       '임무·정책 다음 단계',
       '운영 다음 단계',
diff --git a/ui/src/App.tsx b/ui/src/App.tsx
index c59c1ab..021ffed 100644
--- a/ui/src/App.tsx
+++ b/ui/src/App.tsx
@@ -1,12 +1,13 @@
 import { useEffect, useState } from 'react'
-import { fetchAdapters, fetchOperations, fetchProfiles, fetchRobots } from './api'
-import type { AdapterListView, OperationRecord, ProfileListView, RobotListView, Session } from './api'
+import { fetchAdapters, fetchOperations, fetchProfiles, fetchRobots, fetchSiteSettings } from './api'
+import type { AdapterListView, OperationRecord, ProfileListView, RobotListView, Session, SiteSettingsView } from './api'
 import { AREAS } from './areas'
 import type { AreaId } from './areas'
 import { HistoryArea } from './components/HistoryArea'
 import { ModeSwitch } from './components/ModeSwitch'
 import { RegistryBanner } from './components/RegistryBanner'
 import { RobotsArea } from './components/RobotsArea'
+import { SiteArea } from './components/SiteArea'
 
 const POLL_MS = 5000
 
@@ -19,22 +20,30 @@ export default function App() {
   const [adapters, setAdapters] = useState<AdapterListView | null>(null)
   const [profiles, setProfiles] = useState<ProfileListView | null>(null)
   const [records, setRecords] = useState<OperationRecord[] | null>(null)
+  const [settings, setSettings] = useState<SiteSettingsView | null>(null)
   const [opsError, setOpsError] = useState<string | null>(null)
   // 조작이 끝나면 하나 올린다. 목록을 주기(5초)를 기다리지 않고 다시 읽는다.
   const [tick, setTick] = useState(0)
 
   useEffect(() => {
     let alive = true
-    // 실패해도 직전 값을 지우지 않는다. 대신 opsError 로 직전 값임을 표시한다(스펙 §9). 넷 중 하나라도 못 읽으면 넷 다
-    // 직전 값이다(P2·S1d 스펙 §8.1).
+    // 실패해도 직전 값을 지우지 않는다. 대신 opsError 로 직전 값임을 표시한다(스펙 §9). 다섯 중 하나라도 못 읽으면 다섯 다
+    // 직전 값이다(P2·S1d 스펙 §8.1, S2 스펙 §7).
     const load = () => {
-      Promise.all([fetchRobots(session), fetchAdapters(session), fetchProfiles(session), fetchOperations(session)])
-        .then(([nextView, nextAdapters, nextProfiles, nextRecords]) => {
+      Promise.all([
+        fetchRobots(session),
+        fetchAdapters(session),
+        fetchProfiles(session),
+        fetchOperations(session),
+        fetchSiteSettings(session),
+      ])
+        .then(([nextView, nextAdapters, nextProfiles, nextRecords, nextSettings]) => {
           if (!alive) return
           setView(nextView)
           setAdapters(nextAdapters)
           setProfiles(nextProfiles)
           setRecords(nextRecords)
+          setSettings(nextSettings)
           setOpsError(null)
         })
         .catch((error: unknown) => {
@@ -82,6 +91,14 @@ export default function App() {
             onChanged={() => setTick((value) => value + 1)}
           />
         )}
+        {current.id === 'site' && (
+          <SiteArea
+            view={settings}
+            opsError={opsError}
+            session={session}
+            onChanged={() => setTick((value) => value + 1)}
+          />
+        )}
         {current.id === 'history' && <HistoryArea records={records} opsError={opsError} />}
       </main>
     </>
diff --git a/ui/src/api.ts b/ui/src/api.ts
index fe0b16a..f88abb2 100644
--- a/ui/src/api.ts
+++ b/ui/src/api.ts
@@ -15,7 +15,10 @@ export interface Robot {
   reportingAfterRetirement: boolean
 }
 
-/** 막힘이나 거절 한 건. 화면에 내는 칸 5개와 맞춘다(스펙 §7.4). */
+/**
+ * 막힘이나 거부 한 건. 화면에 내는 칸 5개와 맞춘다(스펙 §7.4). S2 에서 근거 버전을 더했다(S2 스펙 §6.4).
+ * 근거 버전은 현장 설정에 기대는 판정(오래됨)에만 있고, 나머지는 없거나 null 이다.
+ */
 export interface Finding {
   kind: string
   observed: string
@@ -25,6 +28,7 @@ export interface Finding {
   inScreen: boolean
   action: string
   target: string | null
+  basisVersion?: number | null
 }
 
 /**
@@ -84,12 +88,34 @@ export interface RobotView {
   software?: Software | null
 }
 
-/** 운영 서비스의 `GET /api/robots`. `robots` 가 null 이면 모름, 빈 배열이면 없음이다(스펙 §9). */
+/**
+ * 운영 서비스의 `GET /api/robots`. `robots` 가 null 이면 모름, 빈 배열이면 없음이다(스펙 §9).
+ * `settingsVersion`·`connectionThresholdSeconds` 는 연결 칸과 막힘을 판정한 현장 설정 버전과 그 기준 시간이다(S2 스펙 §6.4).
+ */
 export interface RobotListView {
   registry: RegistryState
   checkedAt: string
   robots: RobotView[] | null
   robotsAsOf: string | null
+  settingsVersion?: number | null
+  connectionThresholdSeconds?: number | null
+}
+
+/** 현장 설정 버전 한 행(S2 스펙 §5). `mode` 는 운영 서비스의 열거 값(`ENGINEER`)이다. */
+export interface SiteSettingsRecord {
+  version: number
+  connectionThresholdSeconds: number
+  mode: string
+  user: string
+  reason: string
+  recordedAt: string
+}
+
+/** 운영 서비스의 `GET /api/site-settings`(S2 스펙 §6.1). `history` 는 최신부터다. */
+export interface SiteSettingsView {
+  current: SiteSettingsRecord
+  range: { minConnectionThresholdSeconds: number; maxConnectionThresholdSeconds: number }
+  history: SiteSettingsRecord[]
 }
 
 /** 어댑터 빌드 하나. `conformance` 는 registry 값 그대로다(S1 은 `UNTESTED` 만 본다). */
@@ -295,6 +321,13 @@ async function deliver(path: string, init: RequestInit): Promise<Sent> {
 const retirementPath = (robotId: string) => `/api/robots/${encodeURIComponent(robotId)}/retirement`
 
 export const fetchRobots = (session: Session) => getJson<RobotListView>('/api/robots', session)
+export const fetchSiteSettings = (session: Session) => getJson<SiteSettingsView>('/api/site-settings', session)
+export const changeSiteSettings = (
+  session: Session,
+  baseVersion: number,
+  connectionThresholdSeconds: number,
+  reason: string,
+) => send('PUT', '/api/site-settings', session, { baseVersion, connectionThresholdSeconds, reason })
 export const fetchOperations = (session: Session) =>
   getJson<OperationRecord[]>('/api/operations', session)
 export const declareRobot = (
diff --git a/ui/src/areas.ts b/ui/src/areas.ts
index 3f82281..ed016a4 100644
--- a/ui/src/areas.ts
+++ b/ui/src/areas.ts
@@ -3,12 +3,12 @@ export type AreaId = 'site' | 'robots' | 'missions' | 'operations' | 'history'
 export interface Area {
   id: AreaId
   label: string
-  /** S1 에서 동작하는 영역. 나머지는 다음 단계로 표시한다(스펙 §8). */
+  /** 지금 동작하는 영역. 나머지는 다음 단계로 표시한다(스펙 §8). 현장·자원은 S2 에서 열었다. */
   ready: boolean
 }
 
 export const AREAS: readonly Area[] = [
-  { id: 'site', label: '현장·자원', ready: false },
+  { id: 'site', label: '현장·자원', ready: true },
   { id: 'robots', label: '로봇·연결', ready: true },
   { id: 'missions', label: '임무·정책', ready: false },
   { id: 'operations', label: '운영', ready: false },
diff --git a/ui/src/components/FindingCard.tsx b/ui/src/components/FindingCard.tsx
index d20d6a0..e321939 100644
--- a/ui/src/components/FindingCard.tsx
+++ b/ui/src/components/FindingCard.tsx
@@ -7,7 +7,10 @@ interface Props {
   onSelect?: (robotId: string) => void
 }
 
-/** 막힘이나 거절 한 건을 5칸으로 보인다(스펙 §7.4): 종류, 관측값과 기대값, 마지막 확인 시각, 해결 담당, 바로 갈 링크. */
+/**
+ * 막힘이나 거부 한 건을 보인다(스펙 §7.4): 종류, 관측값과 기대값, 마지막 확인 시각, 근거 버전(S2 스펙 §7), 해결 담당,
+ * 바로 갈 링크. 근거 버전이 없는 판정(registry 상태에서 나온 막힘, 조작 거부)은 «해당 없음» 이다.
+ */
 export function FindingCard({ finding, onSelect }: Props) {
   return (
     <dl className="finding">
@@ -19,6 +22,8 @@ export function FindingCard({ finding, onSelect }: Props) {
       </dd>
       <dt>마지막 확인</dt>
       <dd>{finding.checkedAt}</dd>
+      <dt>근거 버전</dt>
+      <dd>{finding.basisVersion != null ? `현장 설정 버전 ${finding.basisVersion}` : '해당 없음'}</dd>
       <dt>해결 담당</dt>
       <dd>
         {OWNER_LABEL[finding.owner]}({finding.inScreen ? '화면 안' : '화면 밖'}): {finding.action}
diff --git a/ui/src/components/RobotDetail.tsx b/ui/src/components/RobotDetail.tsx
index d69b013..0e65b25 100644
--- a/ui/src/components/RobotDetail.tsx
+++ b/ui/src/components/RobotDetail.tsx
@@ -6,6 +6,8 @@ import { FindingCard } from './FindingCard'
 
 interface Props {
   view: RobotView
+  /** 연결 칸을 판정한 현장 설정(S2 스펙 §7). 목록이 싣지 않으면 없다. */
+  basis?: { version: number; seconds: number } | null
   mode: Mode
   busy: boolean
   onRetire: (reason: string) => void
@@ -22,6 +24,7 @@ interface Props {
  */
 export function RobotDetail({
   view,
+  basis,
   mode,
   busy,
   onRetire,
@@ -42,6 +45,14 @@ export function RobotDetail({
         <dd>{robot.status}</dd>
         <dt>연결</dt>
         <dd>{CONNECTION_LABEL[view.connection]}</dd>
+        {basis != null && (
+          <>
+            <dt>연결 판정 기준</dt>
+            <dd>
+              기준 {basis.seconds}초, 현장 설정 버전 {basis.version}
+            </dd>
+          </>
+        )}
         <dt>마지막 보고</dt>
         <dd>{robot.lastReportedAt ?? '보고 없음'}</dd>
         {retired && (
diff --git a/ui/src/components/RobotsArea.tsx b/ui/src/components/RobotsArea.tsx
index 5271605..ae2aa46 100644
--- a/ui/src/components/RobotsArea.tsx
+++ b/ui/src/components/RobotsArea.tsx
@@ -73,6 +73,11 @@ export function RobotsArea({ view, adapters, profiles, opsError, session, onChan
           <RobotDetail
             key={current.robot.robotId}
             view={current}
+            basis={
+              view?.settingsVersion != null && view.connectionThresholdSeconds != null
+                ? { version: view.settingsVersion, seconds: view.connectionThresholdSeconds }
+                : null
+            }
             mode={session.mode}
             busy={busy}
             onRetire={(reason) =>
diff --git a/ui/src/labels.ts b/ui/src/labels.ts
index 423b1a8..8049b84 100644
--- a/ui/src/labels.ts
+++ b/ui/src/labels.ts
@@ -45,6 +45,7 @@ export const KIND_LABEL: Record<string, string> = {
   NO_ACTIVE_BINDING: '활성 바인딩 없음',
   NOTHING_TO_REGISTER: '등록할 명칭 없음',
   UNCLASSIFIED: '분류되지 않은 거부',
+  SETTINGS_VERSION_CONFLICT: '현장 설정 버전 충돌',
 }
 
 /** «시운전» 칸(P2·S1d 스펙 §8.5). 연결 칸과 합치지 않는다. */
diff --git a/ui/src/testing/fakeOps.ts b/ui/src/testing/fakeOps.ts
index de692a0..391c767 100644
--- a/ui/src/testing/fakeOps.ts
+++ b/ui/src/testing/fakeOps.ts
@@ -6,6 +6,7 @@ import type {
   OperationOutcome,
   ProfileListView,
   Revision,
+  SiteSettingsView,
   RevisionView,
   RobotListView,
   RobotView,
@@ -26,6 +27,7 @@ export interface FakeOps {
   view: RobotListView
   adapters: AdapterListView
   profiles: ProfileListView
+  settings: SiteSettingsView
   /** 여기 든 경로의 GET 은 503 이다. 운영 서비스의 일부 읽기만 실패하는 경우를 만든다. */
   failing: Set<string>
   answer: { status: number; body: unknown }
@@ -48,6 +50,24 @@ export function profileView(partial: Partial<ProfileListView> = {}): ProfileList
   }
 }
 
+/** 현장 설정. 기본은 마이그레이션이 넣는 버전 1 의 90초 하나다(S2 스펙 §5). */
+export function settingsView(partial: Partial<SiteSettingsView> = {}): SiteSettingsView {
+  const first = {
+    version: 1,
+    connectionThresholdSeconds: 90,
+    mode: 'ENGINEER',
+    user: 'system',
+    reason: 'S1 설정값 이전',
+    recordedAt: 't0',
+  }
+  return {
+    current: first,
+    range: { minConnectionThresholdSeconds: 60, maxConnectionThresholdSeconds: 3600 },
+    history: [first],
+    ...partial,
+  }
+}
+
 export function revisionView(partial: Partial<Revision> = {}, testRequest: TestRequestState = 'NONE'): RevisionView {
   return {
     revision: {
@@ -131,8 +151,17 @@ export function installFakeOps(
   view: RobotListView,
   adapters: AdapterListView = adapterView(),
   profiles: ProfileListView = profileView(),
+  settings: SiteSettingsView = settingsView(),
 ): FakeOps {
-  const fake: FakeOps = { calls: [], view, adapters, profiles, failing: new Set(), answer: { status: 200, body: outcome({}) } }
+  const fake: FakeOps = {
+    calls: [],
+    view,
+    adapters,
+    profiles,
+    settings,
+    failing: new Set(),
+    answer: { status: 200, body: outcome({}) },
+  }
   vi.stubGlobal(
     'fetch',
     vi.fn(async (url: string, init?: RequestInit) => {
@@ -153,7 +182,9 @@ export function installFakeOps(
               ? fake.adapters
               : url === '/api/profiles'
                 ? fake.profiles
-                : []
+                : url === '/api/site-settings'
+                  ? fake.settings
+                  : []
         return new Response(JSON.stringify(body), { status: 200 })
       }
       return new Response(JSON.stringify(fake.answer.body), { status: fake.answer.status })
```

- [ ] **Step 3: 현장 설정 구역**: `ui/src/components/SiteArea.tsx`(`files/` 에서 복사)

```tsx
import { useState } from 'react'
import type { FormEvent } from 'react'
import { changeSiteSettings } from '../api'
import type { Sent, Session, SiteSettingsView } from '../api'
import { OutcomeNotice } from './OutcomeNotice'

interface Props {
  view: SiteSettingsView | null
  /** 운영 서비스에 닿지 않으면 [view] 는 직전 값이다. */
  opsError: string | null
  session: Session
  /** 조작이 끝나면 부른다. 다시 읽는다. */
  onChanged: () => void
}

const MODE_LABEL: Record<string, string> = { ENGINEER: '엔지니어', OPERATOR: '운영자' }

/**
 * 현장·자원 영역의 «현장 설정» 구역(S2 스펙 §7). 지금 버전과 값, 허용 범위, 버전 이력을 보이고, 엔지니어 모드에서만 바꾼다.
 * 바꾼 값은 다음 기체 목록 읽기부터 연결 칸과 막힘의 판정에 쓰인다.
 */
export function SiteArea({ view, opsError, session, onChanged }: Props) {
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Sent } | null>(null)

  const run = (what: string, operation: () => Promise<Sent>) => {
    setBusy(true)
    operation()
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        onChanged()
      })
  }

  // 운영 서비스가 다른 모양을 주면(예: 시험 대역의 빈 배열) 칸이 없다. 없는 칸도 모름이다.
  const known = view !== null && view.current != null && Array.isArray(view.history) ? view : null

  return (
    <section aria-label="현장 설정">
      <h2>현장 설정</h2>
      {last !== null && <OutcomeNotice what={last.what} sent={last.sent} onSelect={() => undefined} />}
      {known === null ? (
        <p>모름: 현장 설정을 아직 읽지 못했습니다</p>
      ) : (
        <>
          {opsError !== null && <p className="stale">직전 값입니다</p>}
          <dl>
            <dt>현재 버전</dt>
            <dd>{known.current.version}</dd>
            <dt>연결 기준 시간</dt>
            <dd>{known.current.connectionThresholdSeconds}초</dd>
            <dt>허용 범위</dt>
            <dd>
              {known.range.minConnectionThresholdSeconds}~{known.range.maxConnectionThresholdSeconds}초
            </dd>
          </dl>
          {session.mode === 'engineer' ? (
            <SettingsForm
              view={known}
              busy={busy}
              onChange={(base, seconds, reason) =>
                run(`연결 기준 시간 ${seconds}초로 변경`, () => changeSiteSettings(session, base, seconds, reason))
              }
            />
          ) : (
            <p>현장 설정 변경은 엔지니어 모드에서 합니다</p>
          )}
          <h3>버전 이력</h3>
          <table aria-label="현장 설정 버전 이력">
            <thead>
              <tr>
                <th>버전</th>
                <th>연결 기준 시간</th>
                <th>사용자</th>
                <th>모드</th>
                <th>사유</th>
                <th>기록 시각</th>
              </tr>
            </thead>
            <tbody>
              {known.history.map((record) => (
                <tr key={record.version}>
                  <td>{record.version}</td>
                  <td>{record.connectionThresholdSeconds}초</td>
                  <td>{record.user}</td>
                  <td>{MODE_LABEL[record.mode] ?? record.mode}</td>
                  <td>{record.reason}</td>
                  <td>{record.recordedAt}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  )
}

interface FormProps {
  view: SiteSettingsView
  busy: boolean
  onChange: (baseVersion: number, seconds: number, reason: string) => void
}

/**
 * 연결 기준 시간 변경 폼. 기준 버전은 고치기 시작한 순간의 버전으로 고정한다. 5초마다 다시 읽은 버전을 보낼 때 실으면,
 * 고치는 사이 다른 사람이 올린 버전을 모르고 덮어쓰게 된다(S2 스펙 §7). 범위 밖 값과 빈 사유는 보내지 않는다.
 */
function SettingsForm({ view, busy, onChange }: FormProps) {
  const [base, setBase] = useState<number | null>(null)
  const [seconds, setSeconds] = useState('')
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const { minConnectionThresholdSeconds: min, maxConnectionThresholdSeconds: max } = view.range

  const editing = () => {
    if (base === null) setBase(view.current.version)
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const value = Number(seconds)
    if (!Number.isInteger(value) || value < min || value > max) {
      setProblem(`연결 기준 시간은 ${min}~${max}초의 정수여야 합니다`)
      return
    }
    if (reason.trim() === '') {
      setProblem('변경 사유를 넣으십시오')
      return
    }
    setProblem(null)
    onChange(base ?? view.current.version, value, reason.trim())
    // 보낸 뒤에는 다음 변경을 새 기준 버전에서 시작한다. 거부되면 알림이 지금 버전을 보인다.
    setBase(null)
    setSeconds('')
    setReason('')
  }

  return (
    <form aria-label="현장 설정 변경" onSubmit={submit}>
      <p>기준 버전 {base ?? view.current.version}</p>
      <label>
        연결 기준 시간(초)
        <input
          type="number"
          value={seconds}
          onChange={(event) => {
            editing()
            setSeconds(event.target.value)
          }}
        />
      </label>
      <label>
        변경 사유
        <input
          value={reason}
          onChange={(event) => {
            editing()
            setReason(event.target.value)
          }}
        />
      </label>
      <button type="submit" disabled={busy}>
        변경
      </button>
      {problem !== null && <p role="alert">{problem}</p>}
    </form>
  )
}
```

- [ ] **Step 4: 화면 시험과 빌드**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design/ui" && npm ci --silent && npx vitest run && npm run build
```
Expected: `Tests 55 passed (55)`, 빌드 성공.

- [ ] **Step 5: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design"
git add ui/src/components/SiteArea.tsx ui/src/components/SiteArea.test.tsx ui/src/api.ts ui/src/areas.ts ui/src/labels.ts ui/src/App.tsx ui/src/App.test.tsx ui/src/components/FindingCard.tsx ui/src/components/RobotDetail.tsx ui/src/components/RobotsArea.tsx ui/src/testing/fakeOps.ts
git commit -q -F - <<'EOF'
feat(s2): 현장 설정 화면과 근거 버전 표시 추가

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 6: Playwright 와 README

**Files:**
- Modify: `ui/e2e/lifecycle.spec.ts`, `README.md`(README 새 문장은 Fable·Codex 초안 취합)

- [ ] **Step 1: 패치 넣기**

`C:/Users/Eisen/AppData/Local/Temp/s2-patches/task6.patch`(아래 블록과 같다)를 `git apply --check` 로 본 뒤 `git apply` 한다.

````diff
diff --git a/README.md b/README.md
index b835554..83dfbba 100644
--- a/README.md
+++ b/README.md
@@ -2,7 +2,7 @@
 
 picasso 를 라이브러리로 쓰는 담는 측 저장소입니다. 로봇, 임무, 엔드포인트의 운영 가능성을 PoC 로 입증합니다. 실제 하드웨어 현장은 없습니다. 보안과 인증은 생략합니다. 운영 중 변경은 코드 수정이 아니라 관리 화면에서 처리합니다.
 
-지금 단계는 S1d 바인딩·명칭·시운전입니다. S1c 어댑터 등록 위에 올립니다. 서브모듈 `picasso` 는 P2b 머지 커밋 `41beedb` 를 가리킵니다. 그 버전에는 P2a 시험 실행기와 P2b 리비전·바인딩 REST 가 들어 있습니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 같은 영역 왼쪽에 프로파일 구역이 있습니다. 그 구역에서 엔지니어 모드는 기종 프로파일 리비전을 파일로 골라 제출하고, 시험을 요청하고, 활성화합니다. 시험 결과는 사람이 적지 않습니다. 런처가 띄운 시험 실행기가 적습니다. 기체 상세에서 엔지니어 모드는 빌드와 활성 리비전을 골라 바인딩하고, 사이트 명칭 등록을 기록합니다. 명칭 티칭은 화면 밖 현장 작업입니다. 기체 목록에 시운전 칸이 있습니다. 값은 완료, 미완, 퇴역입니다. 완료 조건은 셋입니다. 원장 상태가 `CONFIRMED` 이고 퇴역이 아니어야 합니다. 활성 바인딩이 있어야 합니다. 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 여야 합니다. 빠진 조건은 막힘으로 보입니다. 운영자 모드는 퇴역과 복귀를 합니다. 어댑터 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 화면은 배정 가능을 쓰지 않습니다. 그것은 S3 의 몫입니다. S1 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. P2·S1d 설계 스펙은 `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.
+지금 단계는 S2 현장 설정입니다. S1d 바인딩·명칭·시운전 위에 올립니다. 서브모듈 `picasso` 는 P2b 머지 커밋 `41beedb` 를 가리킵니다. 그 버전에는 P2a 시험 실행기와 P2b 리비전·바인딩 REST 가 들어 있습니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 같은 영역 왼쪽에 프로파일 구역이 있습니다. 그 구역에서 엔지니어 모드는 기종 프로파일 리비전을 파일로 골라 제출하고, 시험을 요청하고, 활성화합니다. 시험 결과는 사람이 적지 않습니다. 런처가 띄운 시험 실행기가 적습니다. 기체 상세에서 엔지니어 모드는 빌드와 활성 리비전을 골라 바인딩하고, 사이트 명칭 등록을 기록합니다. 명칭 티칭은 화면 밖 현장 작업입니다. 기체 목록에 시운전 칸이 있습니다. 값은 완료, 미완, 퇴역입니다. 완료 조건은 셋입니다. 원장 상태가 `CONFIRMED` 이고 퇴역이 아니어야 합니다. 활성 바인딩이 있어야 합니다. 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 여야 합니다. 빠진 조건은 막힘으로 보입니다. 운영자 모드는 퇴역과 복귀를 합니다. 어댑터 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 화면은 배정 가능을 쓰지 않습니다. 그것은 S3 의 몫입니다. 현장·자원 영역에는 현장 설정 구역이 있습니다. 지금 버전, 연결 기준 시간, 허용 범위(60~3600초), 버전 이력을 보입니다. 엔지니어 모드는 연결 기준 시간을 바꿉니다. 바꿀 때는 사유가 필요합니다. 바꾸면 새 버전이 생깁니다. 다음 기체 목록 읽기부터 그 값으로 연결 칸과 막힘을 판정합니다. 재기동은 필요 없습니다. 운영자 모드는 바꾸지 못합니다. 막힘 카드는 어느 현장 설정 버전으로 판정했는지를 근거 버전으로 보입니다. 기체 상세에는 연결 판정 기준이 보입니다. 기준 초와 버전입니다. 미들웨어 시간값과 인시던트 기록의 버전 번호는 S3 의 몫입니다. S1 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. P2·S1d 설계 스펙은 `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` 에 있습니다. S2 설계 스펙은 `docs/superpowers/specs/2026-10-08-s2-site-settings-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.
 
 ## 구성
 
@@ -72,7 +72,7 @@ cd ui && npx playwright install chromium
 cd ui && npx playwright test
 ```
 
-Playwright 가 Postgres, 런처, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 이어서 리비전 제출, 시험 요청, 실행기의 `TESTED` 기록, 활성화, 바인딩, 명칭 기록, `humanoid-01` 시운전 완료까지 돕니다. `quadruped-01` 의 명칭 기록이 기체가 아는 명칭 없음으로 막히는 것도 봅니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 1초에 가상 1초가 갑니다. 시험은 약 2분 걸립니다. 로컬 실측은 1.9분입니다. Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.
+Playwright 가 Postgres, 런처, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 이어서 리비전 제출, 시험 요청, 실행기의 `TESTED` 기록, 활성화, 바인딩, 명칭 기록, `humanoid-01` 시운전 완료까지 돕니다. `quadruped-01` 의 명칭 기록이 기체가 아는 명칭 없음으로 막히는 것도 봅니다. 현장·자원 영역에서 연결 기준 시간을 120초로 바꿔 버전 2 와 이력 행을 봅니다. 운영자 모드에서는 바꾸지 못하는 것과 기체 상세의 연결 판정 기준이 버전 2 로 바뀐 것도 봅니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 1초에 가상 1초가 갑니다. 시험은 약 2분 걸립니다. 로컬 실측은 1.9분입니다. Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.
 
 ## 띄우기
 
diff --git a/ui/e2e/lifecycle.spec.ts b/ui/e2e/lifecycle.spec.ts
index 4ece005..6c5b669 100644
--- a/ui/e2e/lifecycle.spec.ts
+++ b/ui/e2e/lifecycle.spec.ts
@@ -10,6 +10,8 @@ import { fileURLToPath } from 'node:url'
  * 이어서 S1c 의 화면 쪽(스펙 §3). 제품 선언 → 빌드 선언 → 인스턴스 등록 → 인스턴스 목록에 UNTESTED.
  * 이어서 S1d 의 화면 쪽(P2·S1d 스펙 §3·§11). 개정판 제출(파일 고르기) → 시험 요청 → 현장 실행기가 TESTED → 활성화 →
  * 바인딩 → 명칭 기록 → «시운전 완료»(humanoid-01). quadruped-01 은 명칭을 티칭하지 않아 «기체가 아는 명칭 없음» 으로 막힌다.
+ * 이어서 S2 의 화면 쪽(S2 스펙 §3). 현장·자원 영역에서 연결 기준 시간을 바꾸면 버전 2 와 이력 행이 보이고, 운영자 모드는
+ * 바꾸지 못하며, 기체 상세가 버전 2 의 기준으로 판정한다.
  * registry 를 멈추는 것은 맨 끝이다. 그 뒤로는 조작이 registry 에 닿지 않는다.
  *
  * 선언 직후의 CLAIMED 는 여기서 단언하지 않는다. 실시간 1:1 시계에서는 다음 보고가 1초 안에 올 수도 있어
@@ -124,6 +126,24 @@ test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 reg
   await expect(quadruped.getByText('기체가 아는 명칭 없음', { exact: true })).toBeVisible()
   await expect(quadruped.getByText(/현장\(화면 밖\): 현장에서 명칭 티칭을 다시/)).toBeVisible()
 
+  // 현장 설정(S2 스펙 §3). 엔지니어 모드에서 연결 기준 시간을 바꾸면 새 버전과 이력 행이 보이고, 기체 상세가 그 버전으로
+  // 판정한다. 런처가 30초마다 보고하므로 120초면 기체는 계속 신선하다.
+  await page.getByRole('button', { name: '현장·자원' }).click()
+  const settings = page.getByRole('region', { name: '현장 설정' })
+  const change = settings.getByRole('form', { name: '현장 설정 변경' })
+  await change.getByLabel('연결 기준 시간(초)').fill('120')
+  await change.getByLabel('변경 사유').fill('연결 기준 늘림')
+  await change.getByRole('button', { name: '변경' }).click()
+  await expect(page.getByText('연결 기준 시간 120초로 변경: 반영됨', { exact: true })).toBeVisible()
+  const versions = settings.getByRole('table', { name: '현장 설정 버전 이력' })
+  await expect(versions.getByRole('row', { name: /^2 120초 local 엔지니어 연결 기준 늘림/ })).toBeVisible()
+  await page.getByLabel('운영자').check()
+  await expect(settings.getByText('현장 설정 변경은 엔지니어 모드에서 합니다', { exact: true })).toBeVisible()
+  await page.getByLabel('엔지니어').check()
+  await page.getByRole('button', { name: '로봇·연결' }).click()
+  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
+  await expect(detail.getByText('기준 120초, 현장 설정 버전 2', { exact: true })).toBeVisible()
+
   // registry 를 멈춘다. 런처(registry 와 mimic 이 든 프로세스)를 끈다.
   const pidFile = fileURLToPath(new URL('../../build/site.pid', import.meta.url))
   process.kill(Number(readFileSync(pidFile, 'utf8')))
````

- [ ] **Step 2: Playwright(로컬)**

`docker ps -a`·`docker volume ls` 에 compose 프로젝트 `site` 의 것이 없는지 본 뒤:

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design" && ./gradlew :site:installDist :ops-service:installDist -q && cd ui && npx playwright test
```
Expected: `1 passed`(약 2분).

- [ ] **Step 3: 커밋과 묶음 대조**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design"
git add ui/e2e/lifecycle.spec.ts README.md
git commit -q -F - <<'EOF'
test(s2): Playwright 현장 설정 단계와 README 갱신

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
bash "C:/Users/Eisen/AppData/Local/Temp/s2-cmp.sh" e2e/src/test/kotlin/dev/picasso/ops/e2e/SiteSettingsTest.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt ui/src/components/SiteArea.tsx ui/src/components/SiteArea.test.tsx ui/src/api.ts ui/src/areas.ts ui/src/labels.ts ui/src/App.tsx ui/src/App.test.tsx ui/src/components/FindingCard.tsx ui/src/components/RobotDetail.tsx ui/src/components/RobotsArea.tsx ui/src/testing/fakeOps.ts ui/e2e/lifecycle.spec.ts README.md
```
Expected: 15줄 모두 `같음`.

### Task 7: 결함 주입(시험이 잡는가)

컨트롤러가 한다. `C:/Users/Eisen/AppData/Local/Temp/s2-inject/inj.py` 가 주입 하나를 넣고, 지정 시험만 돌리고, 실패 시험 이름으로 판정하고, 되돌린다.

| ID | 주입 | 잡아야 하는 시험 |
|---|---|---|
| S1 | 범위 하한 60 → 1 | `SiteSettingsStoreTest` «허용 범위는 60초 이상 3600초 이하다» |
| S2 | 기준 버전 비교 빼기 | `SiteSettingsOperationsTest` «아직 없는 버전을 기준으로 보내면…» |
| S3 | 기본 키 위반을 거부로 바꾸지 않음 | `SiteSettingsOperationsTest` «같은 번호를 다른 변경이 먼저 커밋하면 기본 키가 막고…» |
| S4 | 성공 행을 조작 기록에 안 넣음 | `SiteSettingsOperationsTest` «…성공 행을 남긴다» |
| S5 | 현장 설정 실패 때 기본값 90초로 판정 | `RobotListServiceTest` «현장 설정을 못 읽으면 기본값으로 판정하지 않고…» |
| S6 | 근거 버전을 모든 막힘에 실음 | `BlockersTest` «근거 버전은 기준 시간에 기대는 오래됨에만 싣는다» |
| S7 | 고치기·지우기 트리거 빼기 | `SiteSettingsStoreTest` «현장 설정은 고칠 수 없다» |
| S7b | 비우기 트리거 빼기 | `SiteSettingsStoreTest` «현장 설정은 통째로 비울 수 없다» |
| S8 | 기체 목록이 근거 버전을 넘기지 않음 | `RobotListServiceTest` «…근거 버전을 싣는다» |
| E1 | 운영자 모드 허용 | `SiteSettingsTest` «범위 밖 값과 운영자 모드는…» |
| E2 | 빈 사유 허용 | `SiteSettingsTest` «범위 밖 값과 운영자 모드는…» |
| U1 | 보낼 때의 버전을 기준으로 실음 | `SiteArea.test.tsx` «기준 버전은 고치기 시작한 버전으로…» |
| U2 | 화면의 범위 검사 빼기 | `SiteArea.test.tsx` «범위 밖 값과 빈 사유는 보내지 않는다» |
| U3 | 막힘 카드가 근거 버전을 안 보임 | `SiteArea.test.tsx` «…근거 버전을 보인다» |
| U4 | 현장 설정 읽기 실패를 삼킴 | `SiteArea.test.tsx` «현장 설정을 못 읽으면 다섯 읽기 모두 직전 값으로 남는다» |
| U5 | 운영자 모드에도 폼을 보임 | `SiteArea.test.tsx` «운영자 모드에서는…» |

- [ ] **Step 1:** `python "C:/Users/Eisen/AppData/Local/Temp/s2-inject/inj.py" "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design" S1 S2 S3 S4 S5 S6 S7 S7b S8 E1 E2 U1 U2 U3 U4 U5` 를 백그라운드로 돌린다. Expected: 16줄 모두 `HIT`. `MISS` 가 있으면 시험이 돌았는가, 주입이 들어갔는가, 등가 변이인가, 시험의 빈 구간인가 순서로 본다.
- [ ] **Step 2:** `git status --short` 가 비었는지 본다(주입이 모두 되돌려졌다).

### Task 8: 전체 빌드, 새 클론, 합치기와 PR

- [ ] **Step 1:** 새 클론에서 돌린다. `git clone -q -b feat/s2-site-settings "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s2-design" "C:/Users/Eisen/AppData/Local/Temp/s2-clean"` 뒤 그 클론에서 `git -c protocol.file.allow=always submodule update --init --reference "C:/Users/Eisen/Desktop/Labs/[projects] picasso"`, 루트 `.env` 를 워크트리에서 복사하고 `./gradlew test` 를 백그라운드로 돌려 XML 을 센다. Expected: site 17, ops-service 123, e2e 37, 실패 0. `ui` 에서 `npm ci && npx vitest run && npm run build` 가 `Tests 55 passed`.
- [ ] **Step 2:** 구현 커밋 여섯(Task 1~6)을 스펙·계획 커밋 위에서 하나로 합친다(`git reset --soft` 뒤 한 번 커밋, 트리 동일 확인). 백업 브랜치 `s2-pre-squash` 를 먼저 둔다.
- [ ] **Step 3:** 커밋·PR 문장은 Codex 와 Fable 초안을 취합한다(용어집 8절 새 이름). 푸시하고 PR 을 연다. CI(`gradle`·`ui`·`playwright`)를 한 번 본다.
- [ ] **Step 4:** 이 계획 끝 «실행 결과» 절을 채워 커밋한다. CI 초록이면 머지한다(`--merge`). 근거: 사용자가 2026-10-08 «중대한 의사결정이 필요한게 아니라면 구현 끝까지 자율로 실행해» 라고 지시했다.

---

## 실행 결과

(실행 뒤 채운다)
