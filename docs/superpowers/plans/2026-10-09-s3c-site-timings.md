# S3c 미들웨어 시간값과 인시던트의 현장 설정 버전 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking. 단, 이 계획은 묶음(Task 1·2 / Task 3 / Task 4·5)마다 구현자 하나가 하고, 검토는 묶음이 끝난 뒤 컨트롤러가 기계 대조와 시험으로 한다.

**Goal:** 엔지니어가 화면의 현장 설정 구역에서 시간값 넷(근거 윈도우 앞·뒤 폭, `inDoubtGrace`, `stallWindow`)을 바꾸면 현장 설정의 새 버전이 생기고, 실행 호스트가 1초 안에 그 버전을 읽어 다음 pump 부터 미들웨어 판정에 쓰며, 봉인되는 인시던트에 그 설정 버전과 값이 실려 호스트 REST 로 보인다.

**Architecture:** 운영 서비스가 `ops.site_settings` 에 시간값 칸 넷을 더하고(마이그레이션 V4) 현재 버전 뷰 `ops.site_timings_current` 를 연다. 쓰기는 운영 서비스 혼자이고, 범위는 picasso `SiteTimings` 범위의 사본이다. 실행 호스트는 그 뷰를 설정 주기로 호스트 잠금 밖에서 읽어 picasso `SiteTimings.problems()` 로 검사하고, 통과한 값만 미들웨어의 `siteTimings` 소스로 게시한다. 첫 읽기 전에는 미적용이며 작업 지시를 받지 않는다. 호스트는 적용 상태와 인시던트를 REST 로 내고, 운영 서비스는 적용 상태를 현장 설정 응답에 실어 화면이 «실행 호스트 반영: 버전 N» 을 보인다.

**Tech Stack:** Kotlin, Spring Boot(BOM), Postgres·Flyway(`db/ops`), JDK `HttpClient`, picasso(`SiteTimings`, `SiteTimingsSource`, `Middleware`, `Intent`), React·TypeScript·vitest, Playwright, JUnit5 + kotlin.test, Testcontainers.

**근거 스펙:** `docs/superpowers/specs/2026-10-09-s3c-site-timings-design.md`(스펙 검토 2회)와 요청·응답 모양 `docs/superpowers/specs/2026-10-09-s3c-json-contract.md`(S2 스펙 §6.1·§6.2, S2 의 조작 기록 본문, S3a JSON 계약 §2·§4 의 미적용 동안 모양을 대체). 선행 picasso 변경 P5 는 picasso PR #85(머지 커밋 `195c1ee`)이고 그 계획은 `docs/superpowers/plans/2026-10-09-p5-site-timings.md` 다.

**스펙이 계획에 맡긴 것과 이 계획이 정한 것(스파이크에서 정함):**
- 범위는 P5 상수 그대로: 앞 폭 5~120, 뒤 폭 5~120, `inDoubtGrace` 10~600, `stallWindow` 30~3600(초, 양 끝 포함).
- 본문 칸 이름 `evidenceBeforeSeconds`·`evidenceAfterSeconds`·`inDoubtGraceSeconds`·`stallWindowSeconds`. `range` 는 평평한 `min<칸>`·`max<칸>`. 칸 없음과 `null` 은 빠짐(기준 버전 값), 정수 아닌 값은 400 `SETTINGS_BAD_REQUEST`, 값 칸 다섯이 다 빠지면 400. 범위 밖 메시지는 실린 칸마다 `<칸> <값>초는 범위 밖이다. <하한>~<상한>초여야 한다` 를 `"; "` 로 잇는다(S2 의 연결 기준 시간 문장도 이 꼴).
- 버전 충돌의 조작 기록은 기준 버전 행이 있으면 그 값, 없으면 빠진 칸 `null`.
- 호스트 반영은 `GET /api/site-settings` 의 `hostTimings` 에 호스트 본문 그대로, 모르면 `null`(별도 엔드포인트 없음).
- 호스트 읽기 주기는 설정 키 `host.site-timings.read-interval`(기본 `PT1S`, 0 이하면 기동이 멈춤). 상수로 두었더니 기동이 1초를 넘기면 주기 읽기가 기동 안 첫 읽기를 대신해 그 시험이 우연히 통과했다(결함 주입 H5 가 놓침). 그래서 그 시험은 주기를 길게 준다.
- 적용 시각은 호스트 시계(통합 시험은 가상 시계). 같은 버전은 다시 적용하지 않고 버전이 다르면 작아도 적용.
- 미적용 동안 배정 가능 판정은 이유 하나를 더하고, 제출은 기체가 모두 빠져 `UNASSIGNED` 다. 제출에 따로 단락을 두었더니 등가 변이였다(주입 H2)라서 두지 않는다.
- 인시던트 목록은 기본 50, 상한 500, `total` 칸, 시간값은 초 정수.
- 통합 시험 스택은 호스트 기동 앞에 `OpsSchema.migrate(url, user, password)`(운영 서비스 main). e2e 모듈에 picasso 시험 의존을 더한다(범위 사본 대조).
- S3b 의 `basisVersion` 은 채우지 않는다(검증과 모의 실행은 현장 시간값을 쓰지 않음, T10).
- 계획 검토(1회)가 잡아 스파이크에 더한 것: 화면의 기준 버전 고정 시점(처음 고친 칸, 사유 칸 포함) 시험과 주입 U12, 읽기 작업이 `Throwable` 을 잡아 `readError` 에 남기고 계속 읽음(`VirtualMachineError` 만 다시 던짐, 주입 H12), 읽기 주기 하한 1ms(주입 H14, `SiteTimingsReaderTest` 2), 인시던트 목록의 limit 생략 경로 단언(주입 H13), 주입 O2 를 값 단언으로.
- 실측: site 32, mission-host 49 → 57, ops-service 199 → 209, e2e 44 → 50(`SiteTimingsTest` 6, 그 클래스 약 40~50초), vitest 99 → 106, Playwright 1(약 2.8분). 결함 주입 41건(운영 서비스 10, 호스트 13, 화면 12, 통합 6).
- 문장: 커밋·PR·문서 문장은 Codex 와 Fable 초안을 취합한다.

**작업 위치 규칙(필수):**
- 모든 작업은 picasso-ops 워크트리 `C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c`(브랜치 `feat/s3c-site-timings`)에서 한다. picasso-ops 메인 체크아웃과 다른 저장소는 건드리지 않는다. 하위 에이전트의 Bash 는 호출마다 작업 디렉터리가 돌아가므로 명령마다 `cd <워크트리> &&` 를 붙이거나 `git -C` 를 쓴다.
- `./gradlew --stop` 금지(데몬 풀이 다른 체크아웃과 공유된다). 같은 워크트리에서 Gradle 을 겹쳐 돌리지 않는다. site·mission-host·ops-service·e2e 시험은 Testcontainers 로 Postgres 컨테이너를 띄우므로 Docker 데몬이 떠 있어야 한다. Playwright 는 compose 의 Postgres(`127.0.0.1:55432`)와 고정 포트(8781~8785, 4173)를 쓰므로 다른 Playwright·런처와 겹쳐 돌리지 않는다. Bash 도구의 시간 한도(600초)를 넘는 명령은 백그라운드로 돌리고 끝났다는 알림을 받은 뒤 다음 명령을 돌린다.
- `git add -A` 금지. 파일을 이름으로 더한다.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다. Expected 의 수는 `<testcase>` 수다. 수는 `PYTHONUTF8=1 python -c "import glob,xml.etree.ElementTree as E;print(sum(len(list(E.parse(f).getroot().iter('testcase'))) for f in glob.glob('C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c/MOD/build/test-results/test/*.xml')))"` 로 센다(MOD 자리에 모듈 이름). vitest 는 `npm test` 출력의 `Tests` 줄로 센다.
- 이 저장소는 LF 다(`.gitattributes` 의 `eol=lf`). 뽑아 둔 파일은 그대로 복사한다.
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. 커밋 메시지는 heredoc(`git commit -F - <<'EOF'`)으로 쓴다. 형식 훅이 제목이 `type(scope): 명사구` 가 아니거나 트레일러가 없거나 겹화살괄호가 있으면 막는다.
- 이 계획의 코드는 스크래치 스파이크(`C:/Users/Eisen/AppData/Local/Temp/s3c`, 브랜치 `spike/s3c`, HEAD `4020d59`)에서 시험, 전체 빌드, Playwright, 결함 주입을 다 돌린 것이다. 묶음이 끝날 때마다 커밋된 파일을 스파이크와 기계 대조한다(`s3c-cmp.sh`).
- 실행 방식: 묶음(Task 1·2 / Task 3 / Task 4·5)마다 구현 하위 에이전트 1명(`model: "sonnet"`), 서브모듈 갱신(Task 0)과 결함 주입(Task 6)과 검토·PR 은 컨트롤러.
- **블록을 손으로 옮겨 적지 않는다.** 컨트롤러가 이 계획의 블록을 기계로 뽑아 `C:/Users/Eisen/AppData/Local/Temp/s3c-patches/` 에 두었다. 새 파일은 `s3c-patches/files/<경로>` 를 워크트리의 같은 경로로 `cp` 하고, 기존 파일은 `s3c-patches/<이름>.patch` 를 `git apply --check` 로 본 뒤 `git apply` 한다. 아래 블록은 읽고 검토하기 위한 것이다. 뽑은 파일이 없으면 멈추고 보고한다.
- Step 은 순서대로 하나씩 끝내고 다음으로 간다. 다음 Task 의 파일을 미리 복사하거나 패치하지 않는다.

---

## Chunk 1: 구현

### Task 0: 워크트리, 서브모듈, 기준선(컨트롤러)

**Files:** `picasso`(서브모듈 포인터)

- [ ] **Step 1: 워크트리**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" && git fetch -q origin
git -C "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" worktree add "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" -b feat/s3c-site-timings docs/s3c-site-timings-design
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && git submodule update --init -q && git -C picasso log --oneline -1
```
Expected: 워크트리의 기준은 `docs/s3c-site-timings-design`(스펙·계약·계획 커밋, 그 아래 `b2cce34`). 서브모듈이 `1e3f4ae Merge pull request #83`.

- [ ] **Step 2: 서브모듈을 P5 머지 커밋으로**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && git -C picasso fetch -q origin && git -C picasso checkout -q 195c1ee && git add picasso && git commit -F - <<'EOF'
chore(picasso): 서브모듈을 P5 머지 커밋 `195c1ee` 로 갱신

- picasso PR #85, 미들웨어 현장 시간값 주입과 인시던트 의도의 설정 버전 반영

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: `git -C picasso log --oneline -1` 이 `195c1ee Merge pull request #85`.

- [ ] **Step 3: 기준선 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && ./gradlew :site:test :mission-host:test :ops-service:test :e2e:test -q
```
Expected: site 32, mission-host 49, ops-service 199, e2e 44, 실패 0(서브모듈만 바뀌었고 기본값이 «없음» 이라 그대로). 백그라운드로 돌린다. 이어서 `cd ui && npm ci && npm test` 로 vitest 기준선 99.

- [ ] **Step 4: 대조 도구와 뽑은 블록**

`C:/Users/Eisen/AppData/Local/Temp/s3c-cmp.sh` 와 `C:/Users/Eisen/AppData/Local/Temp/s3c-patches/`(패치 5개, `files/` 아래 새 파일 7개)가 있는지 본다. 없으면 멈추고 보고한다.

### Task 1: 운영 서비스: 시간값 칸, 현재 버전 뷰, 변경 API, 호스트 적용 상태

**Files:**
- Create: `ops-service/src/main/resources/db/ops/V4__site_timings.sql`, `ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsControllerTest.kt`
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettings.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettingsOperations.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/store/OpsSchema.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/web/SiteSettingsController.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/HostClientTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsStoreTest.kt`

- [ ] **Step 1: 새 파일 2개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && mkdir -p ops-service/src/main/resources/db/ops && cp "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/files/ops-service/src/main/resources/db/ops/V4__site_timings.sql" ops-service/src/main/resources/db/ops/V4__site_timings.sql
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsControllerTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsControllerTest.kt
```

`ops-service/src/main/resources/db/ops/V4__site_timings.sql`:

```sql
-- 미들웨어 시간값 넷(S3c 스펙 §6.1). 현장 설정 한 세트에 칸을 더하며 버전 체계는 S2 그대로다.
-- 기존 행은 picasso 케이퍼빌리티 기본값(앞 폭 30, 뒤 폭 15, inDoubtGrace 60, stallWindow 300)으로 채운다.
-- UPDATE 로 채우지 않는다(덧붙이기 전용 트리거가 막는다). ADD COLUMN 의 DEFAULT 가 기존 행을 채우고 행 트리거에 걸리지 않는다.
-- DB 제약은 0 보다 큼만 둔다. 허용 범위는 운영 서비스의 사본 상수가 쥔다(범위를 바꿀 때마다 마이그레이션이 필요해지므로).
ALTER TABLE site_settings
    ADD COLUMN evidence_before_seconds INTEGER NOT NULL DEFAULT 30 CHECK (evidence_before_seconds > 0),
    ADD COLUMN evidence_after_seconds  INTEGER NOT NULL DEFAULT 15 CHECK (evidence_after_seconds > 0),
    ADD COLUMN in_doubt_grace_seconds  INTEGER NOT NULL DEFAULT 60 CHECK (in_doubt_grace_seconds > 0),
    ADD COLUMN stall_window_seconds    INTEGER NOT NULL DEFAULT 300 CHECK (stall_window_seconds > 0);

-- 기본값을 지운다. 새 칸을 빠뜨린 INSERT 가 조용히 기본값으로 들어가지 않고 실패한다.
ALTER TABLE site_settings
    ALTER COLUMN evidence_before_seconds DROP DEFAULT,
    ALTER COLUMN evidence_after_seconds DROP DEFAULT,
    ALTER COLUMN in_doubt_grace_seconds DROP DEFAULT,
    ALTER COLUMN stall_window_seconds DROP DEFAULT;

-- 실행 호스트가 읽는 현재 버전 뷰(S3c 스펙 §6.3, T7). 가장 큰 버전 한 행의 버전과 시간값 넷이다.
-- 호스트는 표가 아니라 이 뷰만 읽는다. 칸 이름과 형이 두 프로세스의 계약이며, 바꾸면 호스트의 상수도 바꿔야 한다.
CREATE VIEW site_timings_current AS
SELECT version, evidence_before_seconds, evidence_after_seconds, in_doubt_grace_seconds, stall_window_seconds
FROM site_settings
ORDER BY version DESC
LIMIT 1;
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsControllerTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.OperationOutcome
import dev.picasso.ops.service.settings.SiteSettingsFields
import dev.picasso.ops.service.settings.SiteSettingsOperations
import dev.picasso.ops.service.settings.SiteSettingsStore
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.web.PreRejection
import dev.picasso.ops.service.web.SiteSettingsController
import dev.picasso.registry.PostgresSupport
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 현장 설정 API 의 본문 읽기와 사전 거부, 읽기의 범위와 호스트 반영(S3c 스펙 §6.2, §8, §9). 컨트롤러를 스프링 없이 바로 부른다.
 * 관문과 본문 읽기, 상태 코드의 고름이 컨트롤러 안에 있기 때문이다.
 */
class SiteSettingsControllerTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val jdbc = JdbcClient.create(dataSource)
    private val store = SiteSettingsStore(jdbc)
    private val log = OperationLog(jdbc)
    private val json = ObjectMapper()

    /** 호스트 `GET /host/site-timings` 의 대역. 시험이 바꾼다. */
    private var hostReply: HostCall<JsonNode> = HostCall.Silent("응답 없음: ConnectException")

    private val controller = SiteSettingsController(
        store,
        SiteSettingsOperations(store, log, TransactionTemplate(DataSourceTransactionManager(dataSource)), Clock.systemUTC()),
    ) { hostReply }

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    private fun put(body: String, mode: String = "engineer"): ResponseEntity<Any> = controller.change(mode, "lee", body.toByteArray())

    private fun ResponseEntity<Any>.rejection(): PreRejection = assertIs<PreRejection>(body)

    @Test
    fun `PUT 에서 빠진 칸과 null 칸은 기준 버전의 값이다`() {
        assertEquals(
            OperationResult.SUCCEEDED,
            assertIs<OperationOutcome>(put("""{"baseVersion":1,"stallWindowSeconds":600,"reason":"정체 늦춤"}""").body).result,
        )
        assertEquals(SiteSettingsFields(90, 30, 15, 60, 600), store.latest().fields())
        put("""{"baseVersion":2,"connectionThresholdSeconds":null,"evidenceAfterSeconds":20,"inDoubtGraceSeconds":90,"reason":"뒤 폭"}""")
        assertEquals(SiteSettingsFields(90, 30, 20, 90, 600), store.latest().fields())
        // 화면처럼 다섯을 다 보내면 그 값 그대로다.
        put(
            """{"baseVersion":3,"connectionThresholdSeconds":120,"evidenceBeforeSeconds":40,"evidenceAfterSeconds":25,""" +
                """"inDoubtGraceSeconds":120,"stallWindowSeconds":900,"reason":"전부"}""",
        )
        assertEquals(SiteSettingsFields(120, 40, 25, 120, 900), store.latest().fields())
        assertEquals(listOf(4L, 3L, 2L, 1L), store.history().map { it.version })
    }

    @Test
    fun `범위 밖 칸은 400 SETTING_OUT_OF_RANGE 이고 메시지가 칸 이름과 범위를 싣는다`() {
        val one = put("""{"baseVersion":1,"inDoubtGraceSeconds":9,"reason":"짧게"}""")
        assertEquals(400, one.statusCode.value())
        assertEquals("SETTING_OUT_OF_RANGE", one.rejection().error)
        assertEquals("inDoubtGraceSeconds 9초는 범위 밖이다. 10~600초여야 한다", one.rejection().detail)

        // 여럿이면 칸 순서대로 다 싣는다. 범위 안 칸이 섞여도 하나도 넣지 않는다.
        val two = put("""{"baseVersion":1,"connectionThresholdSeconds":120,"evidenceBeforeSeconds":121,"stallWindowSeconds":29,"reason":"둘"}""")
        assertEquals(
            "evidenceBeforeSeconds 121초는 범위 밖이다. 5~120초여야 한다; stallWindowSeconds 29초는 범위 밖이다. 30~3600초여야 한다",
            two.rejection().detail,
        )
        // Int 를 넘는 정수도 정수이므로 범위 밖이다.
        assertEquals("SETTING_OUT_OF_RANGE", put("""{"baseVersion":1,"evidenceAfterSeconds":9999999999,"reason":"큼"}""").rejection().error)
        assertTrue("connectionThresholdSeconds 59초" in put("""{"baseVersion":1,"connectionThresholdSeconds":59,"reason":"S2"}""").rejection().detail)
        assertEquals(listOf(1L), store.history().map { it.version })
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `값 칸이 다 빠지거나 정수가 아니거나 기준 버전이 없으면 400 SETTINGS_BAD_REQUEST 다`() {
        listOf(
            """{"baseVersion":1,"reason":"값 없음"}""",
            """{"baseVersion":1,"connectionThresholdSeconds":null,"stallWindowSeconds":null,"reason":"다 null"}""",
            """{"baseVersion":1,"stallWindowSeconds":"600","reason":"글자"}""",
            """{"baseVersion":1,"evidenceBeforeSeconds":30.5,"reason":"소수"}""",
            """{"stallWindowSeconds":600,"reason":"기준 없음"}""",
            """[1]""",
            "60초로",
        ).forEach { body ->
            val reply = put(body)
            assertEquals(400, reply.statusCode.value(), body)
            assertEquals("SETTINGS_BAD_REQUEST", reply.rejection().error, body)
        }
        assertEquals("stallWindowSeconds 가 정수가 아니다", put("""{"baseVersion":1,"stallWindowSeconds":"600","reason":"글자"}""").rejection().detail)
        // 못 읽는 본문이 사유 빈칸보다 먼저다. 범위 밖이 사유 빈칸보다 먼저다(S2 순서).
        assertEquals("SETTINGS_BAD_REQUEST", put("""{"baseVersion":1,"reason":" "}""").rejection().error)
        assertEquals("SETTING_OUT_OF_RANGE", put("""{"baseVersion":1,"stallWindowSeconds":1,"reason":" "}""").rejection().error)
        assertEquals("REASON_REQUIRED", put("""{"baseVersion":1,"stallWindowSeconds":600,"reason":" "}""").rejection().error)
        assertEquals(403, put("""{"baseVersion":1,"stallWindowSeconds":600,"reason":"운영자"}""", mode = "operator").statusCode.value())
        assertEquals(listOf(1L), store.history().map { it.version })
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `읽기는 시간값 넷의 범위와 호스트 적용 상태를 싣고 호스트가 닿지 않으면 그 칸만 null 이다`() {
        val unknown = controller.read()
        assertNull(unknown.hostTimings)
        assertEquals(1L, unknown.current.version)
        assertEquals(SiteSettingsFields(90, 30, 15, 60, 300), unknown.current.fields())
        val range = json.valueToTree<JsonNode>(unknown.range)
        assertEquals(
            json.readTree(
                """{"minConnectionThresholdSeconds":60,"maxConnectionThresholdSeconds":3600,""" +
                    """"minEvidenceBeforeSeconds":5,"maxEvidenceBeforeSeconds":120,"minEvidenceAfterSeconds":5,"maxEvidenceAfterSeconds":120,""" +
                    """"minInDoubtGraceSeconds":10,"maxInDoubtGraceSeconds":600,"minStallWindowSeconds":30,"maxStallWindowSeconds":3600}""",
            ),
            range,
        )

        val applied = json.readTree("""{"applied":{"version":1,"evidenceBeforeSeconds":30},"readError":null}""")
        hostReply = HostCall.Ok(applied)
        assertEquals(applied, controller.read().hostTimings)
    }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/task1.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/task1.patch"
```

```diff
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
index 91b2b64..af5882e 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
@@ -165,8 +165,14 @@ fun interface HostSignals {
     fun writeSignal(name: String, value: String): HostWrite
 }
 
+/** 호스트의 현장 시간값 적용 상태(S3c 스펙 §8). 시험이 호스트 없이 대신 끼운다. */
+fun interface HostSiteTimings {
+    /** `GET /host/site-timings` 본문 그대로. 200 아님과 닿지 않음은 모름이다. */
+    fun siteTimings(): HostCall<JsonNode>
+}
+
 /**
- * 실행 호스트 REST 클라이언트(S3a 스펙 §8, S3b 스펙 §7). 호스트는 루프백·무인증이라 토큰을 싣지 않는다.
+ * 실행 호스트 REST 클라이언트(S3a 스펙 §8, S3b 스펙 §7, S3c 스펙 §8). 호스트는 루프백·무인증이라 토큰을 싣지 않는다.
  *
  * 연결 제한은 registry 와 같고 요청 제한은 더 길다. 호스트는 판정과 제출을 자기 잠금 아래에서 하며, 그 안에서 mimic 에
  * gRPC 를 부르고, mimic 은 엔진 잠금 아래에서 registry 로 태스크 관측을 동기 HTTP 로 적재한다(요청 제한 3초, 스펙 §5.3).
@@ -182,7 +188,7 @@ class HostClient(
     private val json: ObjectMapper = jacksonObjectMapper(),
     private val requestTimeout: Duration = REQUEST_TIMEOUT,
     private val mockRunTimeout: Duration = MOCK_RUN_TIMEOUT,
-) : HostReads, HostWrites, HostMissions, HostSignals, AutoCloseable {
+) : HostReads, HostWrites, HostMissions, HostSignals, HostSiteTimings, AutoCloseable {
 
     private val base = checkBaseUrl(baseUrl)
 
@@ -259,6 +265,8 @@ class HostClient(
         }
     }
 
+    override fun siteTimings(): HostCall<JsonNode> = get("/host/site-timings")
+
     override fun writeSignal(name: String, value: String): HostWrite =
         post("/host/cell/signals/${segment(name)}", json.createObjectNode().put("value", value))
 
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettings.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettings.kt
index 3187449..42808b0 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettings.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettings.kt
@@ -3,6 +3,7 @@ package dev.picasso.ops.service.settings
 import dev.picasso.ops.service.actor.Actor
 import dev.picasso.ops.service.actor.Mode
 import org.springframework.jdbc.core.simple.JdbcClient
+import java.sql.ResultSet
 import java.time.Duration
 import java.time.Instant
 import java.time.OffsetDateTime
@@ -15,29 +16,132 @@ fun interface SettingsSource {
     fun current(): SiteSettingsValues
 }
 
-/** 현장 설정 버전 한 행(S2 스펙 §5). */
+/**
+ * 현장 설정 한 세트의 값 다섯(S3c 스펙 §6). 모두 초 단위 정수다. 연결 기준 시간은 운영 서비스의 판정 값이고, 나머지 넷은
+ * 실행 호스트가 뷰로 읽어 picasso 미들웨어에 주는 시간값이다(T7).
+ */
+data class SiteSettingsFields(
+    val connectionThresholdSeconds: Int,
+    val evidenceBeforeSeconds: Int,
+    val evidenceAfterSeconds: Int,
+    val inDoubtGraceSeconds: Int,
+    val stallWindowSeconds: Int,
+)
+
+/**
+ * 변경 요청의 값 칸(S3c 스펙 §6.2). 칸마다 선택이며 널이면 빠진 것이다. 빠진 칸은 기준 버전의 값으로 채운다([resolve]).
+ * 다섯이 다 빠진 요청은 관문이 400 으로 막는다.
+ */
+data class SiteSettingsChange(
+    val connectionThresholdSeconds: Int? = null,
+    val evidenceBeforeSeconds: Int? = null,
+    val evidenceAfterSeconds: Int? = null,
+    val inDoubtGraceSeconds: Int? = null,
+    val stallWindowSeconds: Int? = null,
+) {
+    /** 칸 이름(JSON 본문 이름)과 값. 빠진 칸은 널이다. 순서는 [SiteSettingsRange.FIELDS] 와 같다. */
+    fun entries(): List<Pair<String, Int?>> = listOf(
+        SiteSettingsRange.CONNECTION_THRESHOLD to connectionThresholdSeconds,
+        SiteSettingsRange.EVIDENCE_BEFORE to evidenceBeforeSeconds,
+        SiteSettingsRange.EVIDENCE_AFTER to evidenceAfterSeconds,
+        SiteSettingsRange.IN_DOUBT_GRACE to inDoubtGraceSeconds,
+        SiteSettingsRange.STALL_WINDOW to stallWindowSeconds,
+    )
+
+    val isEmpty: Boolean get() = entries().all { it.second == null }
+
+    /** 빠진 칸을 [base] 의 값으로 채운다. */
+    fun resolve(base: SiteSettingsFields): SiteSettingsFields = SiteSettingsFields(
+        connectionThresholdSeconds = connectionThresholdSeconds ?: base.connectionThresholdSeconds,
+        evidenceBeforeSeconds = evidenceBeforeSeconds ?: base.evidenceBeforeSeconds,
+        evidenceAfterSeconds = evidenceAfterSeconds ?: base.evidenceAfterSeconds,
+        inDoubtGraceSeconds = inDoubtGraceSeconds ?: base.inDoubtGraceSeconds,
+        stallWindowSeconds = stallWindowSeconds ?: base.stallWindowSeconds,
+    )
+}
+
+/** 현장 설정 버전 한 행(S2 스펙 §5, S3c 스펙 §6.1). 값 칸 다섯은 응답 JSON 에 평평하게 실린다. */
 data class SiteSettingsRecord(
     val version: Long,
     val connectionThresholdSeconds: Int,
+    val evidenceBeforeSeconds: Int,
+    val evidenceAfterSeconds: Int,
+    val inDoubtGraceSeconds: Int,
+    val stallWindowSeconds: Int,
     val mode: Mode,
     val user: String,
     val reason: String,
     val recordedAt: Instant,
 ) {
     fun values(): SiteSettingsValues = SiteSettingsValues(version, Duration.ofSeconds(connectionThresholdSeconds.toLong()))
+
+    fun fields(): SiteSettingsFields = SiteSettingsFields(
+        connectionThresholdSeconds, evidenceBeforeSeconds, evidenceAfterSeconds, inDoubtGraceSeconds, stallWindowSeconds,
+    )
 }
 
 /**
- * 연결 기준 시간의 허용 범위(S2 스펙 §5). 이 값의 주인은 picasso 가 아니라 운영 서비스라서 범위도 여기 둔다.
+ * 현장 설정 값의 허용 범위(초).
  *
- * 하한은 프로파일 보고 간격 상한 30초의 2배다. 보고를 한 번 놓쳐도 오래됨이 되지 않는다. 보고 간격 가까이 두면
- * 정상 기체가 신선과 오래됨을 오간다. 상한 1시간은 그보다 길면 연결 칸이 뜻을 잃는다는 판단이다.
+ * 연결 기준 시간(S2 스펙 §5)은 주인이 picasso 가 아니라 운영 서비스라서 범위도 여기 둔다. 하한은 프로파일 보고 간격 상한
+ * 30초의 2배다. 보고를 한 번 놓쳐도 오래됨이 되지 않는다. 보고 간격 가까이 두면 정상 기체가 신선과 오래됨을 오간다.
+ * 상한 1시간은 그보다 길면 연결 칸이 뜻을 잃는다는 판단이다.
+ *
+ * 시간값 넷(S3c 스펙 §5.1, T3)의 범위는 picasso `SiteTimings` 범위 상수의 사본이다. 주인은 picasso 이지만 운영 서비스
+ * main 은 picasso 를 쓰지 못한다(`checkNoPicassoOnMain`). 사본이 picasso 와 같은지는 통합 시험이 대조한다(T6).
  */
 object SiteSettingsRange {
     const val MIN_CONNECTION_THRESHOLD_SECONDS = 60
     const val MAX_CONNECTION_THRESHOLD_SECONDS = 3600
 
+    /** picasso `SiteTimings.EVIDENCE_WINDOW_BEFORE_SECONDS` 의 사본. */
+    const val MIN_EVIDENCE_BEFORE_SECONDS = 5
+    const val MAX_EVIDENCE_BEFORE_SECONDS = 120
+
+    /** picasso `SiteTimings.EVIDENCE_WINDOW_AFTER_SECONDS` 의 사본. */
+    const val MIN_EVIDENCE_AFTER_SECONDS = 5
+    const val MAX_EVIDENCE_AFTER_SECONDS = 120
+
+    /** picasso `SiteTimings.IN_DOUBT_GRACE_SECONDS` 의 사본. */
+    const val MIN_IN_DOUBT_GRACE_SECONDS = 10
+    const val MAX_IN_DOUBT_GRACE_SECONDS = 600
+
+    /** picasso `SiteTimings.STALL_WINDOW_SECONDS` 의 사본. */
+    const val MIN_STALL_WINDOW_SECONDS = 30
+    const val MAX_STALL_WINDOW_SECONDS = 3600
+
+    /** 본문 칸 이름. 범위 밖 메시지가 이 이름으로 칸을 가리킨다. */
+    const val CONNECTION_THRESHOLD = "connectionThresholdSeconds"
+    const val EVIDENCE_BEFORE = "evidenceBeforeSeconds"
+    const val EVIDENCE_AFTER = "evidenceAfterSeconds"
+    const val IN_DOUBT_GRACE = "inDoubtGraceSeconds"
+    const val STALL_WINDOW = "stallWindowSeconds"
+
+    /** 칸 이름과 범위. 순서가 본문 칸의 순서이고 범위 밖 메시지의 순서다. */
+    val FIELDS: List<Pair<String, IntRange>> = listOf(
+        CONNECTION_THRESHOLD to MIN_CONNECTION_THRESHOLD_SECONDS..MAX_CONNECTION_THRESHOLD_SECONDS,
+        EVIDENCE_BEFORE to MIN_EVIDENCE_BEFORE_SECONDS..MAX_EVIDENCE_BEFORE_SECONDS,
+        EVIDENCE_AFTER to MIN_EVIDENCE_AFTER_SECONDS..MAX_EVIDENCE_AFTER_SECONDS,
+        IN_DOUBT_GRACE to MIN_IN_DOUBT_GRACE_SECONDS..MAX_IN_DOUBT_GRACE_SECONDS,
+        STALL_WINDOW to MIN_STALL_WINDOW_SECONDS..MAX_STALL_WINDOW_SECONDS,
+    )
+
+    /** 연결 기준 시간의 범위 검사. */
     fun allows(seconds: Long): Boolean = seconds in MIN_CONNECTION_THRESHOLD_SECONDS..MAX_CONNECTION_THRESHOLD_SECONDS
+
+    /** [field] 칸의 값 [value] 가 범위 밖이면 그 문장, 안이면 `null`. 문장은 칸 이름으로 시작한다. */
+    fun problem(field: String, value: Long): String? {
+        val range = FIELDS.first { it.first == field }.second
+        return if (value in range.first.toLong()..range.last.toLong()) {
+            null
+        } else {
+            "$field ${value}초는 범위 밖이다. ${range.first}~${range.last}초여야 한다"
+        }
+    }
+
+    /** [change] 에 실린 칸 중 범위 밖인 것. 칸마다 한 문장이다. 빠진 칸은 기준 버전의 값이라 보지 않는다. */
+    fun problems(change: SiteSettingsChange): List<String> =
+        change.entries().mapNotNull { (field, value) -> value?.let { problem(field, it.toLong()) } }
 }
 
 /** ops 스키마의 현장 설정 버전 표. 덧붙이기와 읽기만 있다. 고치기·지우기는 스키마의 트리거가 막는다. */
@@ -52,38 +156,63 @@ class SiteSettingsStore(private val jdbc: JdbcClient) : SettingsSource {
     fun history(limit: Int = 200): List<SiteSettingsRecord> =
         jdbc.sql(
             """
-            SELECT version, connection_threshold_seconds, mode, actor_user, reason, recorded_at
+            SELECT version, connection_threshold_seconds, evidence_before_seconds, evidence_after_seconds,
+                   in_doubt_grace_seconds, stall_window_seconds, mode, actor_user, reason, recorded_at
             FROM ops.site_settings
             ORDER BY version DESC
             LIMIT :limit
             """.trimIndent(),
         )
             .param("limit", limit)
-            .query { rs, _ ->
-                SiteSettingsRecord(
-                    version = rs.getLong("version"),
-                    connectionThresholdSeconds = rs.getInt("connection_threshold_seconds"),
-                    mode = Mode.valueOf(rs.getString("mode")),
-                    user = rs.getString("actor_user"),
-                    reason = rs.getString("reason"),
-                    recordedAt = rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
-                )
-            }
+            .query { rs, _ -> record(rs) }
             .list()
 
+    /** 버전 [version] 한 행. 없으면 `null`. */
+    fun find(version: Long): SiteSettingsRecord? =
+        jdbc.sql(
+            """
+            SELECT version, connection_threshold_seconds, evidence_before_seconds, evidence_after_seconds,
+                   in_doubt_grace_seconds, stall_window_seconds, mode, actor_user, reason, recorded_at
+            FROM ops.site_settings
+            WHERE version = :version
+            """.trimIndent(),
+        )
+            .param("version", version)
+            .query { rs, _ -> record(rs) }
+            .optional()
+            .orElse(null)
+
     /** 버전 [version] 행을 넣는다. 그 번호가 이미 있으면 기본 키가 막는다(`DuplicateKeyException`). */
-    fun insert(version: Long, connectionThresholdSeconds: Int, actor: Actor, reason: String) {
+    fun insert(version: Long, fields: SiteSettingsFields, actor: Actor, reason: String) {
         jdbc.sql(
             """
-            INSERT INTO ops.site_settings (version, connection_threshold_seconds, mode, actor_user, reason)
-            VALUES (:version, :seconds, :mode, :user, :reason)
+            INSERT INTO ops.site_settings (version, connection_threshold_seconds, evidence_before_seconds, evidence_after_seconds,
+                                           in_doubt_grace_seconds, stall_window_seconds, mode, actor_user, reason)
+            VALUES (:version, :threshold, :before, :after, :grace, :stall, :mode, :user, :reason)
             """.trimIndent(),
         )
             .param("version", version)
-            .param("seconds", connectionThresholdSeconds)
+            .param("threshold", fields.connectionThresholdSeconds)
+            .param("before", fields.evidenceBeforeSeconds)
+            .param("after", fields.evidenceAfterSeconds)
+            .param("grace", fields.inDoubtGraceSeconds)
+            .param("stall", fields.stallWindowSeconds)
             .param("mode", actor.mode.name)
             .param("user", actor.user)
             .param("reason", reason)
             .update()
     }
+
+    private fun record(rs: ResultSet) = SiteSettingsRecord(
+        version = rs.getLong("version"),
+        connectionThresholdSeconds = rs.getInt("connection_threshold_seconds"),
+        evidenceBeforeSeconds = rs.getInt("evidence_before_seconds"),
+        evidenceAfterSeconds = rs.getInt("evidence_after_seconds"),
+        inDoubtGraceSeconds = rs.getInt("in_doubt_grace_seconds"),
+        stallWindowSeconds = rs.getInt("stall_window_seconds"),
+        mode = Mode.valueOf(rs.getString("mode")),
+        user = rs.getString("actor_user"),
+        reason = rs.getString("reason"),
+        recordedAt = rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
+    )
 }
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettingsOperations.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettingsOperations.kt
index 0af07d4..f2f9e4f 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettingsOperations.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettingsOperations.kt
@@ -13,7 +13,7 @@ import java.time.Clock
 import java.util.UUID
 
 /**
- * 현장 설정 변경(S2 스펙 §6.2). registry 를 부르지 않으므로 `OperationRunner` 를 거치지 않는다. 같은 DB 라 응답 없음과
+ * 현장 설정 변경(S2 스펙 §6.2, S3c 스펙 §6.2). registry 를 부르지 않으므로 `OperationRunner` 를 거치지 않는다. 같은 DB 라 응답 없음과
  * 재조회가 없다. 새 버전 행과 조작 기록의 `SUCCEEDED` 행은 한 트랜잭션에 들어간다.
  *
  * 범위·사유·본문 검사는 부르는 쪽(웹 관문)이 먼저 한다. 여기 오는 요청은 그것을 지난 것이다.
@@ -25,39 +25,41 @@ class SiteSettingsOperations(
     private val clock: Clock,
     private val json: ObjectMapper = ObjectMapper(),
 ) {
-    private sealed interface Change {
-        data object Applied : Change
-        data class Conflict(val current: Long) : Change
+    private sealed interface Outcome {
+        data object Applied : Outcome
+        data class Conflict(val current: Long) : Outcome
     }
 
-    fun change(actor: Actor, baseVersion: Long, connectionThresholdSeconds: Int, reason: String): OperationOutcome {
-        require(SiteSettingsRange.allows(connectionThresholdSeconds.toLong())) { "범위 밖 값은 관문이 막는다: $connectionThresholdSeconds" }
+    /**
+     * [change] 의 빠진 칸을 기준 버전의 값으로 채워 다음 버전을 넣는다(S3c 스펙 §6.2). 조작 기록 본문은 `op`, 기준 버전,
+     * 채운 값 다섯이다. 버전 충돌이면 기준 버전 행이 있을 때 그 값으로 채우고, 없는 버전(앞선 기준)이면 빠진 칸은 `null` 이다.
+     */
+    fun change(actor: Actor, baseVersion: Long, change: SiteSettingsChange, reason: String): OperationOutcome {
+        require(!change.isEmpty) { "값 칸이 다 빠진 요청은 관문이 막는다" }
+        require(SiteSettingsRange.problems(change).isEmpty()) { "범위 밖 값은 관문이 막는다: ${SiteSettingsRange.problems(change)}" }
         val requestId = UUID.randomUUID()
-        val request = json.createObjectNode()
-            .put("op", "CHANGE_SITE_SETTINGS")
-            .put("baseVersion", baseVersion)
-            .put("connectionThresholdSeconds", connectionThresholdSeconds)
-            .toString()
-        val change = try {
+        val outcome = try {
             tx.execute {
-                val current = store.latest().version
+                val current = store.latest()
                 // 기준 버전이 지금 버전이 아니면 넣지 않는다. 지난 버전 위의 변경은 기본 키가 막지만, 아직 없는 버전을
                 // 기준으로 보내면 번호가 건너뛴 행이 들어간다.
-                if (current != baseVersion) return@execute Change.Conflict(current)
-                store.insert(baseVersion + 1, connectionThresholdSeconds, actor, reason)
-                log.append(requestId, actor, TARGET, request, reason, OperationResult.SUCCEEDED, null)
-                Change.Applied
+                if (current.version != baseVersion) return@execute Outcome.Conflict(current.version)
+                val fields = change.resolve(current.fields())
+                store.insert(baseVersion + 1, fields, actor, reason)
+                log.append(requestId, actor, TARGET, request(baseVersion, change, fields), reason, OperationResult.SUCCEEDED, null)
+                Outcome.Applied
             }!!
         } catch (e: DuplicateKeyException) {
             // 같은 기준 버전 위의 다른 변경이 먼저 들어갔다. 이 트랜잭션은 되돌려졌다.
-            Change.Conflict(store.latest().version)
+            Outcome.Conflict(store.latest().version)
         }
-        return when (change) {
-            Change.Applied -> OperationOutcome(requestId, OperationResult.SUCCEEDED, null, null, false, null)
-            is Change.Conflict -> {
-                log.append(requestId, actor, TARGET, request, reason, OperationResult.REJECTED, null)
+        return when (outcome) {
+            Outcome.Applied -> OperationOutcome(requestId, OperationResult.SUCCEEDED, null, null, false, null)
+            is Outcome.Conflict -> {
+                val filled = store.find(baseVersion)?.let { change.resolve(it.fields()) }
+                log.append(requestId, actor, TARGET, request(baseVersion, change, filled), reason, OperationResult.REJECTED, null)
                 val rejection = Finding(
-                    VERSION_CONFLICT, "현재 버전 ${change.current}", "기준 버전 $baseVersion",
+                    VERSION_CONFLICT, "현재 버전 ${outcome.current}", "기준 버전 $baseVersion",
                     clock.instant(), Owner.ENGINEER, true, "현재 값을 다시 읽고 다시", null,
                 )
                 OperationOutcome(requestId, OperationResult.REJECTED, null, rejection, false, null)
@@ -65,6 +67,23 @@ class SiteSettingsOperations(
         }
     }
 
+    /** 조작 기록 본문. [filled] 가 없으면 요청에 실린 칸만 값이 있고 빠진 칸은 `null` 이다. */
+    private fun request(baseVersion: Long, change: SiteSettingsChange, filled: SiteSettingsFields?): String {
+        val node = json.createObjectNode()
+            .put("op", "CHANGE_SITE_SETTINGS")
+            .put("baseVersion", baseVersion)
+        val values = filled?.let {
+            listOf(
+                it.connectionThresholdSeconds, it.evidenceBeforeSeconds, it.evidenceAfterSeconds, it.inDoubtGraceSeconds,
+                it.stallWindowSeconds,
+            )
+        } ?: change.entries().map { it.second }
+        change.entries().map { it.first }.zip(values).forEach { (field, value) ->
+            if (value == null) node.putNull(field) else node.put(field, value)
+        }
+        return node.toString()
+    }
+
     companion object {
         const val TARGET = "site-settings"
         const val VERSION_CONFLICT = "SETTINGS_VERSION_CONFLICT"
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/store/OpsSchema.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/store/OpsSchema.kt
index 41a99cf..999ccf5 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/store/OpsSchema.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/store/OpsSchema.kt
@@ -22,6 +22,21 @@ object OpsSchema {
             .locations(LOCATION)
             .cleanDisabled(!cleanable)
             .load()
+
+    /**
+     * 주소로 ops 스키마를 올린다. 통합 시험의 스택이 실행 호스트보다 먼저 부른다(S3c 스펙 §7.1). 호스트가 기동 안에서 현장
+     * 시간값 뷰를 한 번 읽으므로, 그 전에 뷰가 있어야 호스트가 미적용으로 뜨지 않는다. 시험 모듈은 Flyway 를 직접 보지 않으므로
+     * 올린 수만 돌려준다.
+     */
+    fun migrate(url: String, user: String, password: String): Int =
+        Flyway.configure()
+            .dataSource(url, user, password)
+            .schemas(SCHEMA)
+            .defaultSchema(SCHEMA)
+            .locations(LOCATION)
+            .load()
+            .migrate()
+            .migrationsExecuted
 }
 
 /** 기동 때 올린 마이그레이션 수. 조작 기록 빈이 이것에 기대어 마이그레이션 뒤에 만들어진다. */
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/web/SiteSettingsController.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/web/SiteSettingsController.kt
index 49db2f6..ac04c5e 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/web/SiteSettingsController.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/web/SiteSettingsController.kt
@@ -4,6 +4,9 @@ import com.fasterxml.jackson.databind.JsonNode
 import com.fasterxml.jackson.databind.ObjectMapper
 import dev.picasso.ops.service.actor.Actor
 import dev.picasso.ops.service.actor.Mode
+import dev.picasso.ops.service.host.HostCall
+import dev.picasso.ops.service.host.HostSiteTimings
+import dev.picasso.ops.service.settings.SiteSettingsChange
 import dev.picasso.ops.service.settings.SiteSettingsOperations
 import dev.picasso.ops.service.settings.SiteSettingsRange
 import dev.picasso.ops.service.settings.SiteSettingsRecord
@@ -17,30 +20,51 @@ import org.springframework.web.bind.annotation.RequestBody
 import org.springframework.web.bind.annotation.RequestHeader
 import org.springframework.web.bind.annotation.RestController
 
-/** 허용 범위(초). 화면은 이 값으로 입력을 막고, 서버도 같은 값으로 막는다. */
-data class SiteSettingsRangeView(val minConnectionThresholdSeconds: Int, val maxConnectionThresholdSeconds: Int)
+/** 허용 범위(초). 화면은 이 값으로 입력을 막고, 서버도 같은 값으로 막는다. 시간값 넷의 범위는 S3c 에서 더했다. */
+data class SiteSettingsRangeView(
+    val minConnectionThresholdSeconds: Int,
+    val maxConnectionThresholdSeconds: Int,
+    val minEvidenceBeforeSeconds: Int,
+    val maxEvidenceBeforeSeconds: Int,
+    val minEvidenceAfterSeconds: Int,
+    val maxEvidenceAfterSeconds: Int,
+    val minInDoubtGraceSeconds: Int,
+    val maxInDoubtGraceSeconds: Int,
+    val minStallWindowSeconds: Int,
+    val maxStallWindowSeconds: Int,
+)
 
-/** `GET /api/site-settings` 의 응답(S2 스펙 §6.1). [history] 는 최신부터이며 첫 행이 [current] 다. */
+/**
+ * `GET /api/site-settings` 의 응답(S2 스펙 §6.1, S3c 스펙 §8). [history] 는 최신부터이며 첫 행이 [current] 다.
+ *
+ * @param hostTimings 실행 호스트 `GET /host/site-timings` 본문 그대로. 호스트가 닿지 않거나 200 이 아니면 `null`(모름)이다.
+ *   본문 안의 `applied` 가 `null` 이면 호스트가 아직 적용한 버전이 없다는 응답이고 모름과 다르다.
+ */
 data class SiteSettingsView(
     val current: SiteSettingsRecord,
     val range: SiteSettingsRangeView,
     val history: List<SiteSettingsRecord>,
+    val hostTimings: JsonNode?,
 )
 
 /**
- * 현장 설정 API(S2 스펙 §6). 읽기는 모드와 관계없고, 변경은 엔지니어 모드만 한다.
+ * 현장 설정 API(S2 스펙 §6, S3c 스펙 §6.2). 읽기는 모드와 관계없고, 변경은 엔지니어 모드만 한다.
  *
  * 변경 본문은 바이트로 받아 관문을 지난 뒤 직접 읽는다. 스프링에 맡기면 못 읽는 본문의 400 이 [PreRejection] 모양이 아니고,
  * 관문보다 먼저 읽혀 운영자 모드의 깨진 본문이 403 이 아니라 400 이 된다. 칸은 널 가능으로 읽는다. 빈 칸을 0 으로 읽으면
- * 누락이 버전 충돌이나 범위 밖으로 갈린다. 범위 밖, 사유 빈칸, 못 읽는 본문은 사전 거부라 조작 기록에 남지 않는다.
+ * 누락이 버전 충돌이나 범위 밖으로 바뀐다. 범위 밖, 사유 빈칸, 못 읽는 본문은 사전 거부라 조작 기록에 남지 않는다.
  * 기존 `PROFILE_REQUIRED` 와 같은 길이다. 쓰기 본문은 `application/json` 만 받는다(다른 출처 방어는 [RobotOperationsController]).
  *
+ * 값 칸 다섯은 모두 선택이다(S3c 스펙 §6.2). 칸이 없거나 `null` 이면 빠진 것이고 기준 버전의 값으로 채운다. 칸이 있는데
+ * 정수가 아니면(글자, 소수) 빠진 것으로 보지 않고 400 이다. 빠진 것으로 보면 화면이 보낸 값이 조용히 기준 버전 값으로 바뀐다.
+ *
  * 같은 값으로 바꿔도 새 버전이 생긴다. 버전은 값이 아니라 변경의 기록이다.
  */
 @RestController
 class SiteSettingsController(
     private val store: SiteSettingsStore,
     private val operations: SiteSettingsOperations,
+    private val host: HostSiteTimings,
 ) {
     private val json = ObjectMapper()
 
@@ -52,8 +76,18 @@ class SiteSettingsController(
             range = SiteSettingsRangeView(
                 SiteSettingsRange.MIN_CONNECTION_THRESHOLD_SECONDS,
                 SiteSettingsRange.MAX_CONNECTION_THRESHOLD_SECONDS,
+                SiteSettingsRange.MIN_EVIDENCE_BEFORE_SECONDS,
+                SiteSettingsRange.MAX_EVIDENCE_BEFORE_SECONDS,
+                SiteSettingsRange.MIN_EVIDENCE_AFTER_SECONDS,
+                SiteSettingsRange.MAX_EVIDENCE_AFTER_SECONDS,
+                SiteSettingsRange.MIN_IN_DOUBT_GRACE_SECONDS,
+                SiteSettingsRange.MAX_IN_DOUBT_GRACE_SECONDS,
+                SiteSettingsRange.MIN_STALL_WINDOW_SECONDS,
+                SiteSettingsRange.MAX_STALL_WINDOW_SECONDS,
             ),
             history = history,
+            // 호스트를 못 읽어도 현장 설정 읽기는 실패시키지 않는다. 반영 칸만 모름이다.
+            hostTimings = (host.siteTimings() as? HostCall.Ok)?.value,
         )
     }
 
@@ -63,29 +97,44 @@ class SiteSettingsController(
         @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
         @RequestBody(required = false) body: ByteArray?,
     ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
-        val node = body?.let { runCatching { json.readTree(it) }.getOrNull() }
-        val base = node.long("baseVersion")
-        val seconds = node.long("connectionThresholdSeconds")
-        if (base == null || seconds == null) {
-            return@guarded reject(
-                HttpStatus.BAD_REQUEST, "SETTINGS_BAD_REQUEST", "baseVersion 과 connectionThresholdSeconds 가 정수로 있어야 한다",
-            )
+        val node = body?.let { runCatching { json.readTree(it) }.getOrNull() }?.takeIf { it.isObject }
+        val base = node?.get("baseVersion")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
+            ?: return@guarded reject(HttpStatus.BAD_REQUEST, BAD_REQUEST, "baseVersion 이 정수로 있어야 한다")
+        val values = SiteSettingsRange.FIELDS.map { (field, _) ->
+            val value = node.get(field)
+            when {
+                value == null || value.isNull -> field to null
+                value.isIntegralNumber && value.canConvertToLong() -> field to value.asLong()
+                else -> return@guarded reject(HttpStatus.BAD_REQUEST, BAD_REQUEST, "$field 가 정수가 아니다")
+            }
         }
-        if (!SiteSettingsRange.allows(seconds)) {
+        if (values.all { it.second == null }) {
             return@guarded reject(
-                HttpStatus.BAD_REQUEST, "SETTING_OUT_OF_RANGE",
-                "연결 기준 시간 ${seconds}초는 범위 밖이다. " +
-                    "${SiteSettingsRange.MIN_CONNECTION_THRESHOLD_SECONDS}~${SiteSettingsRange.MAX_CONNECTION_THRESHOLD_SECONDS}초여야 한다",
+                HttpStatus.BAD_REQUEST, BAD_REQUEST,
+                "값 칸(${SiteSettingsRange.FIELDS.joinToString(", ") { it.first }}) 중 하나 이상이 정수로 있어야 한다",
             )
         }
-        val reason = node?.get("reason")?.takeIf { it.isTextual }?.asText()?.trim()
+        val problems = values.mapNotNull { (field, value) -> value?.let { SiteSettingsRange.problem(field, it) } }
+        if (problems.isNotEmpty()) {
+            return@guarded reject(HttpStatus.BAD_REQUEST, "SETTING_OUT_OF_RANGE", problems.joinToString("; "))
+        }
+        val reason = node.get("reason")?.takeIf { it.isTextual }?.asText()?.trim()
         if (reason.isNullOrEmpty()) {
             return@guarded reject(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "변경 사유가 없다")
         }
-        ResponseEntity.ok(operations.change(actor, base, seconds.toInt(), reason))
+        // 범위 검사를 지났으므로 값은 Int 에 든다.
+        val seconds = values.toMap().mapValues { it.value?.toInt() }
+        val change = SiteSettingsChange(
+            connectionThresholdSeconds = seconds[SiteSettingsRange.CONNECTION_THRESHOLD],
+            evidenceBeforeSeconds = seconds[SiteSettingsRange.EVIDENCE_BEFORE],
+            evidenceAfterSeconds = seconds[SiteSettingsRange.EVIDENCE_AFTER],
+            inDoubtGraceSeconds = seconds[SiteSettingsRange.IN_DOUBT_GRACE],
+            stallWindowSeconds = seconds[SiteSettingsRange.STALL_WINDOW],
+        )
+        ResponseEntity.ok(operations.change(actor, base, change, reason))
     }
 
-    /** 정수 칸만 받는다. 글자나 소수로 온 값은 없는 것으로 본다. */
-    private fun JsonNode?.long(field: String): Long? =
-        this?.get(field)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
+    private companion object {
+        const val BAD_REQUEST = "SETTINGS_BAD_REQUEST"
+    }
 }
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/HostClientTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/HostClientTest.kt
index 6d9e3f6..d05488a 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/HostClientTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/HostClientTest.kt
@@ -114,6 +114,20 @@ class HostClientTest {
         assertIs<HostCall.Silent>(serve("/host/executions" to (200 to "not json")).executions())
     }
 
+    @Test
+    fun `현장 시간값 적용 상태는 GET host site-timings 본문 그대로이고 200 아님과 닿지 않음은 모름이다`() {
+        val body = """{"applied":{"version":2,"evidenceBeforeSeconds":30},"readError":null}"""
+        val client = serve("/host/site-timings" to (200 to body))
+        assertEquals(json.readTree(body), assertIs<HostCall.Ok<*>>(client.siteTimings()).value)
+        assertEquals("GET", seen.getValue("/host/site-timings").first)
+        stop()
+        assertEquals(HostCall.Silent("HTTP 404"), serve("/host/site-timings" to (404 to "")).siteTimings())
+        stop()
+        val gone = serve("/host/site-timings" to (200 to body))
+        stop()
+        assertIs<HostCall.Silent>(gone.siteTimings())
+    }
+
     @Test
     fun `호스트 주소 형식이 틀리면 기동에서 멈춘다`() {
         assertFailsWith<IllegalArgumentException> { HostClient("127.0.0.1:8785") }
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt
index 67c7b6d..5278b01 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt
@@ -7,6 +7,8 @@ import dev.picasso.ops.service.finding.Owner
 import dev.picasso.ops.service.log.OperationLog
 import dev.picasso.ops.service.log.OperationResult
 import dev.picasso.ops.service.operations.OperationOutcome
+import dev.picasso.ops.service.settings.SiteSettingsChange
+import dev.picasso.ops.service.settings.SiteSettingsFields
 import dev.picasso.ops.service.settings.SiteSettingsOperations
 import dev.picasso.ops.service.settings.SiteSettingsStore
 import dev.picasso.ops.service.store.OpsSchema
@@ -25,7 +27,10 @@ import kotlin.test.assertEquals
 import kotlin.test.assertFailsWith
 import kotlin.test.assertNull
 
-/** 현장 설정 변경(S2 스펙 §6.2·§6.3). 새 버전 행과 조작 기록 행이 같이 들어가거나 같이 안 들어간다. */
+/**
+ * 현장 설정 변경(S2 스펙 §6.2·§6.3, S3c 스펙 §6.2). 새 버전 행과 조작 기록 행이 같이 들어가거나 같이 안 들어간다.
+ * 빠진 칸은 기준 버전의 값으로 채운다.
+ */
 class SiteSettingsOperationsTest {
 
     private val dataSource = DriverManagerDataSource(
@@ -43,6 +48,8 @@ class SiteSettingsOperationsTest {
     private val lee = Actor(Mode.ENGINEER, "lee")
     private val json = ObjectMapper()
 
+    private fun threshold(seconds: Int) = SiteSettingsChange(connectionThresholdSeconds = seconds)
+
     @BeforeTest
     fun freshSchema() {
         OpsSchema.flyway(dataSource, cleanable = true).apply {
@@ -53,7 +60,7 @@ class SiteSettingsOperationsTest {
 
     @Test
     fun `기준 버전이 지금 버전이면 다음 버전을 넣고 조작 기록에 성공 행을 남긴다`() {
-        val outcome = operations.change(lee, 1, 60, "시험")
+        val outcome = operations.change(lee, 1, threshold(60), "시험")
         assertEquals(OperationResult.SUCCEEDED, outcome.result)
         assertNull(outcome.rejection)
         assertNull(outcome.registryStatus)
@@ -69,15 +76,18 @@ class SiteSettingsOperationsTest {
         assertEquals("시험", record.reason)
         assertNull(record.targetResponse)
         assertEquals(
-            json.readTree("""{"op":"CHANGE_SITE_SETTINGS","baseVersion":1,"connectionThresholdSeconds":60}"""),
+            json.readTree(
+                """{"op":"CHANGE_SITE_SETTINGS","baseVersion":1,"connectionThresholdSeconds":60,"evidenceBeforeSeconds":30,""" +
+                    """"evidenceAfterSeconds":15,"inDoubtGraceSeconds":60,"stallWindowSeconds":300}""",
+            ),
             json.readTree(record.request),
         )
     }
 
     @Test
     fun `지난 기준 버전 위의 변경은 버전 충돌로 거부하고 조작 기록에 거부 행을 남긴다`() {
-        operations.change(lee, 1, 60, "먼저")
-        val outcome = operations.change(Actor(Mode.ENGINEER, "park"), 1, 120, "늦게")
+        operations.change(lee, 1, threshold(60), "먼저")
+        val outcome = operations.change(Actor(Mode.ENGINEER, "park"), 1, threshold(120), "늦게")
         assertEquals(OperationResult.REJECTED, outcome.result)
         val rejection = outcome.rejection!!
         assertEquals(SiteSettingsOperations.VERSION_CONFLICT, rejection.kind)
@@ -94,7 +104,7 @@ class SiteSettingsOperationsTest {
 
     @Test
     fun `아직 없는 버전을 기준으로 보내면 번호를 건너뛰지 않고 거부한다`() {
-        val outcome = operations.change(lee, 5, 60, "앞선 기준")
+        val outcome = operations.change(lee, 5, threshold(60), "앞선 기준")
         assertEquals(OperationResult.REJECTED, outcome.result)
         assertEquals("현재 버전 1", outcome.rejection!!.observed)
         assertEquals(listOf(1L), store.history().map { it.version })
@@ -107,11 +117,12 @@ class SiteSettingsOperationsTest {
             other.autoCommit = false
             other.createStatement().use {
                 it.executeUpdate(
-                    "INSERT INTO ops.site_settings (version, connection_threshold_seconds, mode, actor_user, reason) " +
-                        "VALUES (2, 120, 'ENGINEER', 'park', '먼저')",
+                    "INSERT INTO ops.site_settings (version, connection_threshold_seconds, evidence_before_seconds, " +
+                        "evidence_after_seconds, in_doubt_grace_seconds, stall_window_seconds, mode, actor_user, reason) " +
+                        "VALUES (2, 120, 30, 15, 60, 300, 'ENGINEER', 'park', '먼저')",
                 )
             }
-            val pending = pool.submit<OperationOutcome> { operations.change(lee, 1, 60, "늦게") }
+            val pending = pool.submit<OperationOutcome> { operations.change(lee, 1, threshold(60), "늦게") }
             // 변경이 버전 2 의 기본 키 잠금을 기다릴 때까지 본다. 그 전에 커밋하면 비교 경로로 간다.
             val deadline = System.nanoTime() + 10_000_000_000
             while (PostgresSupport.queryOne(
@@ -132,9 +143,50 @@ class SiteSettingsOperationsTest {
     }
 
     @Test
-    fun `범위 밖 값은 관문이 막으므로 여기까지 오면 계약 위반이다`() {
-        assertFailsWith<IllegalArgumentException> { operations.change(lee, 1, 59, "범위 밖") }
+    fun `범위 밖 값과 다 빠진 요청은 관문이 막으므로 여기까지 오면 계약 위반이다`() {
+        assertFailsWith<IllegalArgumentException> { operations.change(lee, 1, threshold(59), "범위 밖") }
+        assertFailsWith<IllegalArgumentException> { operations.change(lee, 1, SiteSettingsChange(stallWindowSeconds = 3601), "범위 밖") }
+        assertFailsWith<IllegalArgumentException> { operations.change(lee, 1, SiteSettingsChange(), "빈 변경") }
         assertEquals(listOf(1L), store.history().map { it.version })
         assertEquals(emptyList(), log.list())
     }
+
+    @Test
+    fun `빠진 칸은 기준 버전의 값으로 채우고 조작 기록에 채운 값 다섯을 남긴다`() {
+        operations.change(lee, 1, SiteSettingsChange(connectionThresholdSeconds = 120, inDoubtGraceSeconds = 90), "먼저")
+        val outcome = operations.change(lee, 2, SiteSettingsChange(evidenceBeforeSeconds = 45, stallWindowSeconds = 600), "시간값")
+        assertEquals(OperationResult.SUCCEEDED, outcome.result)
+        // 기준 버전 2 의 연결 기준 시간 120 과 inDoubtGrace 90 이 남고, 뒤 폭은 버전 1 부터의 기본값 15 다.
+        assertEquals(SiteSettingsFields(120, 45, 15, 90, 600), store.latest().fields())
+        assertEquals(3L, store.latest().version)
+        assertEquals(
+            json.readTree(
+                """{"op":"CHANGE_SITE_SETTINGS","baseVersion":2,"connectionThresholdSeconds":120,"evidenceBeforeSeconds":45,""" +
+                    """"evidenceAfterSeconds":15,"inDoubtGraceSeconds":90,"stallWindowSeconds":600}""",
+            ),
+            json.readTree(log.list().first().request),
+        )
+    }
+
+    @Test
+    fun `버전 충돌의 조작 기록은 기준 버전이 있으면 그 값으로 채우고 없는 버전이면 빠진 칸이 null 이다`() {
+        operations.change(lee, 1, SiteSettingsChange(stallWindowSeconds = 600), "먼저")
+        operations.change(lee, 1, SiteSettingsChange(evidenceAfterSeconds = 20), "늦게")
+        assertEquals(
+            json.readTree(
+                """{"op":"CHANGE_SITE_SETTINGS","baseVersion":1,"connectionThresholdSeconds":90,"evidenceBeforeSeconds":30,""" +
+                    """"evidenceAfterSeconds":20,"inDoubtGraceSeconds":60,"stallWindowSeconds":300}""",
+            ),
+            json.readTree(log.list().first().request),
+        )
+        operations.change(lee, 9, SiteSettingsChange(evidenceAfterSeconds = 20), "앞선 기준")
+        assertEquals(
+            json.readTree(
+                """{"op":"CHANGE_SITE_SETTINGS","baseVersion":9,"connectionThresholdSeconds":null,"evidenceBeforeSeconds":null,""" +
+                    """"evidenceAfterSeconds":20,"inDoubtGraceSeconds":null,"stallWindowSeconds":null}""",
+            ),
+            json.readTree(log.list().first().request),
+        )
+        assertEquals(listOf(OperationResult.REJECTED, OperationResult.REJECTED, OperationResult.SUCCEEDED), log.list().map { it.result })
+    }
 }
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsStoreTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsStoreTest.kt
index 66d10bd..e00179d 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsStoreTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsStoreTest.kt
@@ -2,6 +2,7 @@ package dev.picasso.ops.service
 
 import dev.picasso.ops.service.actor.Actor
 import dev.picasso.ops.service.actor.Mode
+import dev.picasso.ops.service.settings.SiteSettingsFields
 import dev.picasso.ops.service.settings.SiteSettingsRange
 import dev.picasso.ops.service.settings.SiteSettingsStore
 import dev.picasso.ops.service.store.OpsSchema
@@ -18,7 +19,7 @@ import kotlin.test.assertFailsWith
 import kotlin.test.assertFalse
 import kotlin.test.assertTrue
 
-/** 현장 설정 버전 표(S2 스펙 §5). */
+/** 현장 설정 버전 표(S2 스펙 §5)와 시간값 칸·현재 버전 뷰(S3c 스펙 §6.1·§6.3). */
 class SiteSettingsStoreTest {
 
     private val dataSource = DriverManagerDataSource(
@@ -29,6 +30,13 @@ class SiteSettingsStoreTest {
     private val store = SiteSettingsStore(JdbcClient.create(dataSource))
     private val lee = Actor(Mode.ENGINEER, "lee")
 
+    private fun threshold(seconds: Int) = SiteSettingsFields(seconds, 30, 15, 60, 300)
+
+    private fun view(): List<List<Long>> = PostgresSupport.queryAll(
+        "SELECT version, evidence_before_seconds, evidence_after_seconds, in_doubt_grace_seconds, stall_window_seconds " +
+            "FROM ops.site_timings_current",
+    ) { rs -> (1..5).map { rs.getLong(it) } }
+
     @BeforeTest
     fun freshSchema() {
         OpsSchema.flyway(dataSource, cleanable = true).apply {
@@ -51,8 +59,8 @@ class SiteSettingsStoreTest {
 
     @Test
     fun `현재 버전은 가장 큰 버전이고 이력은 최신부터다`() {
-        store.insert(2, 60, lee, "시험")
-        store.insert(3, 120, lee, "되돌림")
+        store.insert(2, threshold(60), lee, "시험")
+        store.insert(3, threshold(120), lee, "되돌림")
         assertEquals(listOf(3L, 2L, 1L), store.history().map { it.version })
         assertEquals(3L, store.current().version)
         assertEquals(Duration.ofSeconds(120), store.current().connectionThreshold)
@@ -60,8 +68,8 @@ class SiteSettingsStoreTest {
 
     @Test
     fun `같은 버전 번호는 두 번 들어가지 않는다`() {
-        store.insert(2, 60, lee, "시험")
-        assertFailsWith<DuplicateKeyException> { store.insert(2, 120, lee, "다른 변경") }
+        store.insert(2, threshold(60), lee, "시험")
+        assertFailsWith<DuplicateKeyException> { store.insert(2, threshold(120), lee, "다른 변경") }
         assertEquals(60, store.latest().connectionThresholdSeconds)
     }
 
@@ -85,6 +93,67 @@ class SiteSettingsStoreTest {
         assertTrue("덧붙이기만" in e.message!!, e.message)
     }
 
+    @Test
+    fun `V4 는 기존 행을 picasso 기본값으로 채우고 기본값을 지워 새 칸을 빠뜨린 INSERT 를 막는다`() {
+        assertEquals(SiteSettingsFields(90, 30, 15, 60, 300), store.latest().fields())
+        val e = assertFailsWith<SQLException> {
+            PostgresSupport.execute(
+                "INSERT INTO ops.site_settings (version, connection_threshold_seconds, mode, actor_user, reason) " +
+                    "VALUES (2, 60, 'ENGINEER', 'lee', '빠뜨림')",
+            )
+        }
+        assertTrue("null value" in e.message!!, e.message)
+        // DB 제약은 0 보다 큼만 둔다. 범위는 운영 서비스 상수가 쥔다.
+        assertFailsWith<SQLException> {
+            PostgresSupport.execute(
+                "INSERT INTO ops.site_settings (version, connection_threshold_seconds, evidence_before_seconds, evidence_after_seconds, " +
+                    "in_doubt_grace_seconds, stall_window_seconds, mode, actor_user, reason) VALUES (2, 60, 0, 15, 60, 300, 'ENGINEER', 'lee', '영')",
+            )
+        }
+        store.insert(2, SiteSettingsFields(60, 121, 15, 60, 300), lee, "범위는 DB 가 보지 않는다")
+        assertEquals(121, store.latest().evidenceBeforeSeconds)
+    }
+
+    @Test
+    fun `현재 버전 뷰는 가장 큰 버전 한 행의 버전과 시간값 넷이다`() {
+        assertEquals(listOf(listOf(1L, 30L, 15L, 60L, 300L)), view())
+        store.insert(2, SiteSettingsFields(60, 40, 20, 90, 600), lee, "시간값")
+        store.insert(3, SiteSettingsFields(60, 50, 25, 120, 900), lee, "다시")
+        assertEquals(listOf(listOf(3L, 50L, 25L, 120L, 900L)), view())
+        // 칸 이름과 형이 실행 호스트와의 계약이다(S3c 스펙 T7).
+        val columns = PostgresSupport.queryAll(
+            "SELECT column_name, data_type FROM information_schema.columns " +
+                "WHERE table_schema = 'ops' AND table_name = 'site_timings_current' ORDER BY ordinal_position",
+        ) { rs -> rs.getString(1) to rs.getString(2) }
+        assertEquals(
+            listOf(
+                "version" to "bigint",
+                "evidence_before_seconds" to "integer",
+                "evidence_after_seconds" to "integer",
+                "in_doubt_grace_seconds" to "integer",
+                "stall_window_seconds" to "integer",
+            ),
+            columns,
+        )
+    }
+
+    @Test
+    fun `시간값 넷의 허용 범위는 picasso 범위의 사본이고 기본값이 범위 안이다`() {
+        val ranges = SiteSettingsRange.FIELDS.toMap()
+        assertEquals(5..120, ranges.getValue("evidenceBeforeSeconds"))
+        assertEquals(5..120, ranges.getValue("evidenceAfterSeconds"))
+        assertEquals(10..600, ranges.getValue("inDoubtGraceSeconds"))
+        assertEquals(30..3600, ranges.getValue("stallWindowSeconds"))
+        assertEquals(60..3600, ranges.getValue("connectionThresholdSeconds"))
+        assertEquals(null, SiteSettingsRange.problem("stallWindowSeconds", 3600))
+        assertEquals("stallWindowSeconds 3601초는 범위 밖이다. 30~3600초여야 한다", SiteSettingsRange.problem("stallWindowSeconds", 3601))
+        assertEquals("evidenceBeforeSeconds 4초는 범위 밖이다. 5~120초여야 한다", SiteSettingsRange.problem("evidenceBeforeSeconds", 4))
+        val first = store.latest()
+        SiteSettingsRange.FIELDS.map { it.first }
+            .zip(first.fields().let { listOf(it.connectionThresholdSeconds, it.evidenceBeforeSeconds, it.evidenceAfterSeconds, it.inDoubtGraceSeconds, it.stallWindowSeconds) })
+            .forEach { (field, value) -> assertEquals(null, SiteSettingsRange.problem(field, value.toLong()), field) }
+    }
+
     @Test
     fun `허용 범위는 60초 이상 3600초 이하다`() {
         assertFalse(SiteSettingsRange.allows(59))
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && ./gradlew :ops-service:check -q
```
Expected: ops-service 209, 실패 0(`check` 는 시험과 함께 운영 서비스가 picasso 를 쓰지 않는 경계 `checkNoPicassoOnMain` 을 집행한다). 백그라운드로 돌린다.

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && git add ops-service/src/main/resources/db/ops/V4__site_timings.sql ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsControllerTest.kt ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettings.kt ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettingsOperations.kt ops-service/src/main/kotlin/dev/picasso/ops/service/store/OpsSchema.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/SiteSettingsController.kt ops-service/src/test/kotlin/dev/picasso/ops/service/HostClientTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsStoreTest.kt && git commit -F - <<'EOF'
feat(ops-service): 현장 설정에 시간값 칸 넷과 현재 버전 뷰 추가

- 마이그레이션 V4 로 `ops.site_settings` 에 근거 윈도우 앞·뒤 폭, `inDoubtGrace`, `stallWindow`(초) 추가, 기존 행은 picasso 기본값 30·15·60·300 으로 채운 뒤 기본값 제거
- 실행 호스트가 읽는 계약으로 현재 버전 뷰 `ops.site_timings_current`, `OpsSchema.migrate` 공개
- `PUT /api/site-settings` 의 값 칸 다섯 모두 선택(빠진 칸은 기준 버전 값, 전부 빠지면 400), picasso 범위 사본으로 검사해 범위 밖이면 400 `SETTING_OUT_OF_RANGE` 에 칸 이름 기재, 조작 기록은 기준 버전으로 채운 값 다섯, `GET /api/site-settings` 에 실행 호스트 적용 상태 `hostTimings`(모르면 null)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 2: 실행 호스트: 뷰 읽기, 적용, 미적용, 적용 상태와 인시던트 REST

**Files:**
- Create: `mission-host/src/main/kotlin/dev/picasso/ops/host/timings/SiteTimingsReader.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/web/SiteTimingsController.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/SiteTimingsHostTest.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/SiteTimingsReaderTest.kt`
- Modify: `mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt`, `mission-host/src/main/resources/mission-host.properties`, `mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt`

- [ ] **Step 1: 새 파일 4개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/timings && cp "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/timings/SiteTimingsReader.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/timings/SiteTimingsReader.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/web && cp "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/web/SiteTimingsController.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/web/SiteTimingsController.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && mkdir -p mission-host/src/test/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/files/mission-host/src/test/kotlin/dev/picasso/ops/host/SiteTimingsHostTest.kt" mission-host/src/test/kotlin/dev/picasso/ops/host/SiteTimingsHostTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && mkdir -p mission-host/src/test/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/files/mission-host/src/test/kotlin/dev/picasso/ops/host/SiteTimingsReaderTest.kt" mission-host/src/test/kotlin/dev/picasso/ops/host/SiteTimingsReaderTest.kt
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/timings/SiteTimingsReader.kt`:

```kotlin
package dev.picasso.ops.host.timings

import dev.picasso.middleware.SiteTimings
import dev.picasso.middleware.SiteTimingsSource
import dev.picasso.ops.host.HostClock
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 실행 호스트가 읽는 운영 서비스의 현재 버전 뷰(S3c 스펙 §6.3, T7). 뷰의 이름과 칸(이름과 형)이 두 프로세스의 계약이다.
 *
 * 호스트 시험 세트에는 ops 스키마가 없어 이 상수로 대역 뷰를 만들고, 통합 시험은 이 상수를 실제 뷰의 `information_schema`
 * 칸과 대조한다. 그래서 상수를 main 에 둔다. 형 이름은 `information_schema.columns.data_type` 의 값이다.
 */
object SiteTimingsView {
    const val NAME = "ops.site_timings_current"

    data class Column(val name: String, val type: String)

    val COLUMNS: List<Column> = listOf(
        Column("version", "bigint"),
        Column("evidence_before_seconds", "integer"),
        Column("evidence_after_seconds", "integer"),
        Column("in_doubt_grace_seconds", "integer"),
        Column("stall_window_seconds", "integer"),
    )

    /** 읽기 질의. 칸을 이름으로 적는다. 뷰에 칸이 더해져도 읽는 칸은 같다. */
    val SELECT: String = "SELECT ${COLUMNS.joinToString(", ") { it.name }} FROM $NAME"
}

/** 범위 밖이라 적용하지 않은 버전과 그 이유. 이유는 picasso `SiteTimings.problems()` 의 문장 그대로다. */
data class RejectedTimings(val version: Long, val reasons: List<String>)

/**
 * 읽기 주기의 상태(S3c 스펙 §7.1). 통째로 바꾸는 불변 값이다.
 *
 * @param applied 적용한 시간값. 읽기 주기가 받아들여 다음 pump 부터 쓰일 값이다. `null` 이면 미적용이다
 * @param appliedAt [applied] 를 받아들인 호스트 시각
 * @param lastReadAt 마지막으로 읽기를 마친 호스트 시각. 성공과 실패를 가리지 않는다
 * @param readError 마지막 읽기가 실패했으면 그 까닭. 성공하면 지운다
 * @param rejected 마지막으로 읽은 행이 범위 밖이면 그 버전과 이유. 적용할 수 있는 행을 읽으면 지운다
 */
data class SiteTimingsState(
    val applied: SiteTimings?,
    val appliedAt: Instant?,
    val lastReadAt: Instant?,
    val readError: String?,
    val rejected: RejectedTimings?,
) {
    companion object {
        val UNAPPLIED = SiteTimingsState(null, null, null, null, null)
    }
}

/**
 * 현장 시간값 읽기 주기(S3c 스펙 §7.1, T8). 운영 서비스의 뷰를 [period] 마다 **호스트 잠금 밖에서** 읽고, picasso 범위로
 * 검사해 통과한 값만 적용 스냅숏으로 바꾼다. 미들웨어는 [current] 로 그 스냅숏을 받아 다음 pump 부터 쓴다. [current] 는 DB 를
 * 읽지 않고 스냅숏만 돌려주므로 호스트 잠금 아래에서 불려도 막히지 않는다.
 *
 * - 기동 안에서 한 번 동기로 읽는다([start]). 실패하면 미적용으로 뜬다. 기본값으로 대신하지 않는다(S2 스펙 §6.4 의 원칙).
 *   기동 순서상 호스트가 운영 서비스보다 먼저 뜰 수 있어 기동 실패로 두지 않는다
 * - 그 뒤 읽기가 실패하면 마지막으로 적용한 값을 계속 쓰고 [SiteTimingsState.readError] 에 남긴다
 * - 범위 밖 행은 적용하지 않고 마지막 값을 유지하며 [SiteTimingsState.rejected] 에 버전과 이유를 남긴다
 * - 같은 버전이면 바꾸지 않는다. 버전이 다르면 작아도 적용한다(뷰는 가장 큰 버전 하나만 보이므로 작아지는 것은 ops 스키마를
 *   다시 세운 경우뿐이고, 그때도 뷰가 지금 값이다)
 *
 * 쓰는 쪽은 읽기 주기 하나뿐이다([readOnce] 는 동기화된다).
 */
class SiteTimingsReader(
    private val jdbc: JdbcClient,
    private val clock: HostClock,
) : SiteTimingsSource, AutoCloseable {

    private val state = AtomicReference(SiteTimingsState.UNAPPLIED)

    private val reader = Executors.newSingleThreadScheduledExecutor { Thread(it, "site-timings-reader").apply { isDaemon = true } }

    override fun current(): SiteTimings? = state.get().applied

    fun state(): SiteTimingsState = state.get()

    /**
     * 한 번 동기로 읽고 그 뒤 [period] 마다 읽는다. [MissionHostApplication][dev.picasso.ops.host.MissionHostApplication] 이 빈을 만들 때
     * 부른다. 스케줄러가 밀리초로 받으므로 [period] 는 1ms 이상이어야 한다(1ms 미만은 0 으로 잘려 `scheduleWithFixedDelay` 가 거부한다).
     */
    fun start(period: Duration = READ_PERIOD): SiteTimingsReader = apply {
        require(period.toMillis() > 0) { "현장 시간값 읽기 주기(host.site-timings.read-interval)는 1ms 이상이어야 한다: $period" }
        readOnce()
        reader.scheduleWithFixedDelay(::readOnce, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * 뷰를 한 번 읽어 상태를 바꾼다. 스케줄러는 작업이 무엇이든 던지면 다음 실행을 조용히 멈추므로, `Exception` 이 아닌 `Error`
     * (드라이버 클래스를 못 찾는 `LinkageError`, 단언 실패 등)도 잡아 [SiteTimingsState.readError] 에 남기고 다음 주기에 다시 읽는다.
     *
     * `VirtualMachineError`(메모리 부족, 스택 넘침 등)만 다시 던진다. JVM 이 더 믿을 수 없는 상태라 같은 작업을 1초마다 되풀이해
     * 가릴 일이 아니고, 잡아 두면 그 오류를 다룰 바깥(스레드의 처리기, 프로세스 감시)이 보지 못한다. 그때 주기 읽기는 멈추고
     * 마지막 적용 값이 남는다.
     */
    @Synchronized
    fun readOnce() {
        val before = state.get()
        val after = try {
            next(before, read(), clock.now())
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            before.copy(lastReadAt = runCatching { clock.now() }.getOrNull() ?: before.lastReadAt, readError = describe(e))
        }
        state.set(after)
        if (after.readError != null && after.readError != before.readError) log.warn("현장 시간값 읽기 실패: {}", after.readError)
        if (after.rejected != null && after.rejected != before.rejected) log.warn("현장 시간값 버전 {} 을 적용하지 않음: {}", after.rejected.version, after.rejected.reasons)
        if (after.applied != before.applied) log.info("현장 시간값 버전 {} 적용", after.applied?.siteSettingsVersion)
    }

    private fun read(): List<SiteTimings> =
        jdbc.sql(SiteTimingsView.SELECT)
            .query { rs, _ ->
                SiteTimings.ofSeconds(
                    rs.getLong("version"),
                    rs.getLong("evidence_before_seconds"),
                    rs.getLong("evidence_after_seconds"),
                    rs.getLong("in_doubt_grace_seconds"),
                    rs.getLong("stall_window_seconds"),
                )
            }
            .list()

    private fun next(before: SiteTimingsState, rows: List<SiteTimings>, at: Instant): SiteTimingsState {
        if (rows.size != 1) return before.copy(lastReadAt = at, readError = "${SiteTimingsView.NAME} 가 행 ${rows.size} 개를 냈다(한 행이어야 한다)")
        val row = rows.single()
        if (row.siteSettingsVersion == before.applied?.siteSettingsVersion) {
            return before.copy(lastReadAt = at, readError = null, rejected = null)
        }
        val problems = row.problems()
        if (problems.isNotEmpty()) {
            return before.copy(lastReadAt = at, readError = null, rejected = RejectedTimings(row.siteSettingsVersion, problems))
        }
        return SiteTimingsState(applied = row, appliedAt = at, lastReadAt = at, readError = null, rejected = null)
    }

    private fun describe(e: Throwable): String =
        generateSequence<Throwable>(e) { it.cause }.last().let { root -> "${root.javaClass.simpleName}: ${root.message}" }

    override fun close() {
        reader.shutdownNow()
        reader.awaitTermination(2, TimeUnit.SECONDS)
    }

    companion object {
        private val log = LoggerFactory.getLogger(SiteTimingsReader::class.java)

        /** 읽기 주기 기본값(S3c 스펙 사용자 결정 3). 기동에서는 설정 키 `host.site-timings.read-interval` 이 준다. */
        val READ_PERIOD: Duration = Duration.ofSeconds(1)
    }
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/web/SiteTimingsController.kt`:

```kotlin
package dev.picasso.ops.host.web

import dev.picasso.middleware.SiteTimings
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.timings.RejectedTimings
import dev.picasso.ops.host.timings.SiteTimingsReader
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** 적용한 현장 시간값. 칸 이름은 운영 서비스 현장 설정 본문의 이름과 같고 값은 초 단위 정수다. */
data class AppliedTimingsView(
    val version: Long,
    val evidenceBeforeSeconds: Long,
    val evidenceAfterSeconds: Long,
    val inDoubtGraceSeconds: Long,
    val stallWindowSeconds: Long,
) {
    companion object {
        fun of(timings: SiteTimings) = AppliedTimingsView(
            timings.siteSettingsVersion,
            timings.evidenceWindowBefore.seconds,
            timings.evidenceWindowAfter.seconds,
            timings.inDoubtGrace.seconds,
            timings.stallWindow.seconds,
        )
    }
}

/**
 * `GET /host/site-timings` 의 본문(S3c 스펙 §7.2). 칸의 뜻은 `SiteTimingsState` 에 있다. [applied] 가 `null` 이면 미적용이다.
 */
data class SiteTimingsStateView(
    val applied: AppliedTimingsView?,
    val appliedAt: Instant?,
    val lastReadAt: Instant?,
    val readError: String?,
    val rejected: RejectedTimings?,
)

/**
 * 현장 시간값 적용 상태와 인시던트 조회(S3c 스펙 §7.2, T9). 둘 다 읽기만 한다. 운영 서비스가 적용 상태를 대신 읽어 화면에 보이고,
 * 통합 시험이 인시던트에 실린 설정 버전과 시간값을 확인한다.
 */
@RestController
class SiteTimingsController(private val host: MissionHost, private val timings: SiteTimingsReader) {

    @GetMapping("/host/site-timings")
    fun siteTimings(): SiteTimingsStateView {
        val state = timings.state()
        return SiteTimingsStateView(
            applied = state.applied?.let(AppliedTimingsView::of),
            appliedAt = state.appliedAt,
            lastReadAt = state.lastReadAt,
            readError = state.readError,
            rejected = state.rejected,
        )
    }

    /** 최신부터 많아야 [limit] 개. 정수가 아니거나 1~[MAX_LIMIT] 밖이면 400 `BAD_REQUEST` 다. */
    @GetMapping("/host/incidents")
    fun incidents(@RequestParam(required = false) limit: String?): ResponseEntity<Any> {
        val count = if (limit == null) DEFAULT_LIMIT else limit.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(HostRejection(HostRequests.BAD_REQUEST, "limit 은 1~$MAX_LIMIT 의 정수다: $limit"))
        return ResponseEntity.ok(host.incidents(count))
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
    }
}
```

`mission-host/src/test/kotlin/dev/picasso/ops/host/SiteTimingsHostTest.kt`:

```kotlin
package dev.picasso.ops.host

import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 실행 호스트의 현장 시간값 읽기와 적용(S3c 스펙 §7.1, §9, §10 의 호스트 행). 뷰는 [HostBench] 의 대역 뷰다.
 */
class SiteTimingsHostTest {

    @Test
    fun `첫 읽기 전에는 판정이 기체마다 미적용 이유를 더하고 제출은 사유 없는 UNASSIGNED 다`() {
        HostBench(timings = null).use { bench ->
            val state = bench.get("/host/site-timings")
            assertTrue(state["applied"].isNull, state.toString())
            assertTrue(state["appliedAt"].isNull, state.toString())
            assertTrue("site_timings_current" in state["readError"].asText(), state.toString())
            assertTrue(!state["lastReadAt"].isNull, state.toString())

            val judged = bench.post("/host/eligibility", request(inspect("JO-1", "T1" to "bay-7"), "robotIds", HUMANOID, QUADRUPED))
                .body!!["robots"]
            judged.forEach { row ->
                assertEquals("FIT", row["skillFit"].asText(), row.toString())
                assertEquals(false, row["passed"].asBoolean(), row.toString())
                assertEquals(listOf(MissionHost.UNAPPLIED_REASON), row["reasons"].map { it.asText() })
            }

            val submitted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("UNASSIGNED", submitted["result"].asText(), submitted.toString())
            assertEquals(0, submitted["refusals"].size())
            assertEquals(listOf(MissionHost.UNAPPLIED_REASON), submitted["excluded"].single()["reasons"].map { it.asText() })
            // 기체가 하나도 넘어가지 않으므로 요구 근거 등급을 넘는 작업 지시도 REJECTED 가 아니라 UNASSIGNED 다. 새 결과 값은 없다.
            val beyond = bench.post("/host/job-orders", request(inspect("JO-2", "T1" to "bay-7", evidence = "E2"), "candidates", HUMANOID))
                .body!!
            assertEquals("UNASSIGNED", beyond["result"].asText(), beyond.toString())
            assertEquals(0, bench.get("/host/executions")["executions"].size())
            // 시간값을 쓰지 않는 경로는 그대로다.
            assertEquals(200, bench.fetch("/host/missions/PrepareSequencedRack").status)
            assertEquals(200, bench.fetch("/host/cell").status)

            // 뷰가 생기면 1초 주기 읽기가 적용하고 같은 작업 지시가 선다.
            bench.timingsView(HostBench.STANDARD_TIMINGS)
            val applied = bench.awaitApplied(1)
            assertTrue(applied["readError"].isNull, applied.toString())
            val accepted = bench.post("/host/job-orders", request(inspect("JO-3", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
        }
    }

    @Test
    fun `기동 안의 첫 읽기로 버전과 값을 적용한다`() {
        // 주기를 한 시간으로 둔다. 기동이 1초를 넘겨도 주기 읽기가 끼지 않으므로, 적용은 기동 안의 첫 읽기에서만 온다.
        HostBench(readInterval = Duration.ofHours(1)).use { bench ->
            val first = bench.get("/host/site-timings")
            assertEquals(
                HostBench.JSON.readTree(
                    """{"version":1,"evidenceBeforeSeconds":30,"evidenceAfterSeconds":15,"inDoubtGraceSeconds":60,"stallWindowSeconds":300}""",
                ),
                first["applied"],
            )
            assertTrue(!first["appliedAt"].isNull && first["readError"].isNull && first["rejected"].isNull, first.toString())
            assertEquals(first["appliedAt"].asText(), first["lastReadAt"].asText())
        }
    }

    @Test
    fun `새 버전은 다음 주기 읽기에서 적용한다`() {
        HostBench().use { bench ->
            val first = bench.awaitApplied(1)

            // 호스트 시계는 mimic 의 가상 시계라 밀어야 적용 시각이 달라진다.
            bench.mimic.server.advance(Duration.ofSeconds(5))
            bench.timingsView(listOf(2, 40, 20, 90, 600))
            val second = bench.awaitApplied(2)
            assertEquals(listOf(40L, 20L, 90L, 600L), listOf("evidenceBeforeSeconds", "evidenceAfterSeconds", "inDoubtGraceSeconds", "stallWindowSeconds").map { second["applied"][it].asLong() })
            assertNotEquals(first["appliedAt"].asText(), second["appliedAt"].asText())
            assertTrue(!Instant.parse(second["lastReadAt"].asText()).isBefore(Instant.parse(second["appliedAt"].asText())), second.toString())
        }
    }

    @Test
    fun `범위 밖 행은 적용하지 않고 마지막 버전을 유지하며 버전과 이유를 보인다`() {
        HostBench().use { bench ->
            bench.timingsView(listOf(2, 30, 15, 60, 3601))
            val rejected = bench.awaitTimings { !it["rejected"].isNull }
            assertEquals(1, rejected["applied"]["version"].asLong(), rejected.toString())
            assertEquals(2, rejected["rejected"]["version"].asLong())
            assertEquals(listOf("stallWindow: 30~3600 초 밖이다 (3601)"), rejected["rejected"]["reasons"].map { it.asText() })
            assertTrue(rejected["readError"].isNull, rejected.toString())
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())

            // 적용할 수 있는 다음 버전을 읽으면 적용하고 거부 표시를 지운다.
            bench.timingsView(listOf(3, 30, 15, 60, 3600))
            val next = bench.awaitApplied(3)
            assertTrue(next["rejected"].isNull, next.toString())
        }
    }

    @Test
    fun `읽기가 실패하면 마지막 버전을 계속 쓰고 실패를 보이며 다시 읽히면 지운다`() {
        HostBench().use { bench ->
            bench.dropTimingsView()
            val failing = bench.awaitTimings { !it["readError"].isNull }
            assertEquals(1, failing["applied"]["version"].asLong(), failing.toString())
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())

            bench.mimic.server.advance(Duration.ofSeconds(5))
            bench.timingsView(HostBench.STANDARD_TIMINGS)
            val recovered = bench.awaitTimings { it["readError"].isNull }
            assertEquals(1, recovered["applied"]["version"].asLong(), recovered.toString())
            // 같은 버전이라 다시 적용하지 않는다.
            assertEquals(failing["appliedAt"].asText(), recovered["appliedAt"].asText())
        }
    }
}
```

`mission-host/src/test/kotlin/dev/picasso/ops/host/SiteTimingsReaderTest.kt`:

```kotlin
package dev.picasso.ops.host

import dev.picasso.ops.host.timings.SiteTimingsReader
import dev.picasso.ops.host.timings.SiteTimingsView
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 현장 시간값 읽기 주기 자체(S3c 스펙 §7.1). 호스트를 띄우지 않고 읽기 주기만 세운다. 뷰는 [SiteTimingsView] 상수의 대역이다. */
class SiteTimingsReaderTest {

    private val jdbc = JdbcClient.create(DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password))

    @BeforeTest
    fun standInView() {
        PostgresSupport.execute("DROP SCHEMA IF EXISTS ops CASCADE")
        PostgresSupport.execute("CREATE SCHEMA ops")
        val values = SiteTimingsView.COLUMNS.zip(HostBench.STANDARD_TIMINGS).joinToString(", ") { (column, value) -> "$value::${column.type}" }
        PostgresSupport.execute(
            "CREATE VIEW ${SiteTimingsView.NAME} (${SiteTimingsView.COLUMNS.joinToString(", ") { it.name }}) AS VALUES ($values)",
        )
    }

    @Test
    fun `읽기 중 Exception 이 아닌 Error 가 나도 실패로 남기고 주기 읽기를 이어 간다`() {
        // 처음 두 번은 시계가 Error 를 던진다. 기동 안의 첫 읽기가 실패한다(실패 시각을 다시 물을 때도 던진다).
        val calls = AtomicInteger()
        val clock = HostClock {
            if (calls.incrementAndGet() <= 2) throw LinkageError("시험이 던진 Error")
            Instant.parse("2026-10-09T00:00:00Z")
        }
        SiteTimingsReader(jdbc, clock).start(Duration.ofMillis(50)).use { reader ->
            val failed = reader.state()
            assertNull(failed.applied)
            assertTrue("LinkageError" in failed.readError!!, failed.readError)
            val deadline = Instant.now().plusSeconds(5)
            while (reader.state().applied == null) {
                check(Instant.now().isBefore(deadline)) { "5초 안에 주기 읽기가 이어지지 않았다: ${reader.state()}" }
                Thread.sleep(20)
            }
            assertEquals(1L, reader.state().applied!!.siteSettingsVersion)
            assertNull(reader.state().readError)
        }
    }

    @Test
    fun `읽기 주기는 1ms 이상이어야 하고 1ms 미만은 읽기 전에 거부한다`() {
        val clock = HostClock { Instant.parse("2026-10-09T00:00:00Z") }
        listOf(Duration.ZERO, Duration.ofNanos(500_000), Duration.ofSeconds(-1)).forEach { period ->
            val reader = SiteTimingsReader(jdbc, clock)
            val e = assertFailsWith<IllegalArgumentException> { reader.start(period) }
            assertTrue("1ms 이상" in e.message!!, e.message)
            // 거부는 첫 읽기 전이다.
            assertNull(reader.state().lastReadAt)
            reader.close()
        }
        SiteTimingsReader(jdbc, clock).start(Duration.ofMillis(1)).use { assertEquals(1L, it.state().applied!!.siteSettingsVersion) }
    }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/task2.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/task2.patch"
```

```diff
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
index 38e0d2f..4d102fe 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
@@ -7,6 +7,7 @@ import dev.picasso.middleware.Middleware
 import dev.picasso.middleware.PrepareSequencedRack
 import dev.picasso.middleware.Route
 import dev.picasso.middleware.RobotPort
+import dev.picasso.middleware.SiteTimingsSource
 import dev.picasso.middleware.Unassigned
 import dev.picasso.ops.host.cell.CellBandClient
 import dev.picasso.ops.host.cell.CellBandSignals
@@ -107,6 +108,36 @@ data class ExecutionView(
  */
 data class ExecutionsView(val instanceId: String, val pumpedAt: Instant?, val executions: List<ExecutionView>)
 
+/**
+ * 인시던트 하나(S3c 스펙 §7.2). 미들웨어 `IncidentBundle` 에서 통합 시험과 화면이 쓰는 칸만 옮긴다. 시간값은 picasso 의 ISO-8601
+ * 문자열(`Duration.toString()`, 60초면 `PT1M`)을 초 단위 정수로 되돌린 것이다.
+ *
+ * @param at 봉인 라운드의 미들웨어 시각(호스트 시계)
+ * @param missionVersion 임무 버전. `null` 이면 코드 정의다
+ * @param siteSettingsVersion 봉인 라운드의 현장 설정 버전. 현장 시간값 없이 봉인했으면 `null` 이다
+ * @param evidenceBeforeSeconds·evidenceAfterSeconds 봉인 라운드의 근거 윈도우 앞·뒤 폭. 늘 있다
+ * @param inDoubtGraceSeconds·stallWindowSeconds 봉인 라운드의 값. 현장 시간값 없이 봉인했으면 `null` 이다
+ */
+data class IncidentView(
+    val incidentId: String,
+    val executionId: String,
+    val jobOrderId: String,
+    val robotId: String,
+    val unitId: String,
+    val at: Instant,
+    val failureClass: String?,
+    val route: String,
+    val missionVersion: Int?,
+    val siteSettingsVersion: Long?,
+    val evidenceBeforeSeconds: Long,
+    val evidenceAfterSeconds: Long,
+    val inDoubtGraceSeconds: Long?,
+    val stallWindowSeconds: Long?,
+)
+
+/** `GET /host/incidents` 의 본문. [incidents] 는 최신부터 많아야 limit 개이고 [total] 은 자르기 전의 수다. */
+data class IncidentsView(val instanceId: String, val total: Int, val incidents: List<IncidentView>)
+
 /**
  * 미들웨어 실행 호스트(S3a 스펙 §7).
  *
@@ -140,18 +171,27 @@ data class ExecutionsView(val instanceId: String, val pumpedAt: Instant?, val ex
  *   안전하지 않으므로 케이퍼빌리티는 늘 [lock] 아래에서 묻는다.
  * @param catalog 임무 카탈로그(S3b 스펙 T1). 미들웨어에 넘긴 것과 같은 참조로 스킬 적합을 판정한다. 기동 때 DB 의 활성 버전으로
  *   세운 것을 받는다.
+ * @param siteTimings 현장 시간값(S3c 스펙 §7.1). 미들웨어에 그대로 넘긴다. `null` 을 주는 동안은 미적용이라 작업 지시를 받지 않는다.
+ *   모의 실행의 별도 미들웨어에는 주지 않는다(T10).
+ *
+ * ## 미적용(S3c 스펙 T8)
+ *
+ * 첫 읽기가 성공하기 전에는 판정이 기체마다 [UNAPPLIED_REASON] 을 더해 통과시키지 않는다. 그래서 제출은 `assign` 에 기체를 하나도
+ * 넘기지 않고, 미들웨어는 기체 없는 채택을 사유 없는 UNASSIGNED 로 돌려준다(이유는 [SubmitOutcome.excluded], S3a JSON 계약 그대로).
+ * 기본값으로 대신하지 않는다. 나머지 경로는 시간값을 쓰지 않으므로 그대로 동작한다.
  */
 class MissionHost(
     private val robots: RobotPort,
     private val cellBand: CellBandClient,
     private val clock: HostClock,
     private val catalog: StoredMissionCatalog = StoredMissionCatalog(),
+    private val siteTimings: SiteTimingsSource,
 ) : AutoCloseable {
 
     private val lock = ReentrantLock(true)
     private val signals = CellBandSignals()
 
-    private val middleware = Middleware(robots = robots, cell = signals, now = clock::now, missions = catalog)
+    private val middleware = Middleware(robots = robots, cell = signals, now = clock::now, missions = catalog, siteTimings = siteTimings)
 
     private var pumpedAt: Instant? = null
     private var latestCell: CellSnapshot? = null
@@ -183,7 +223,8 @@ class MissionHost(
 
     /** 기체마다 호스트 판정을 낸다. [order] 의 WorkMaster 는 부르는 쪽이 [WORK_MASTERS] 로 거른다. */
     fun eligibility(order: JobOrder, robotIds: List<String>): List<HostEligibility> = lock.withLock {
-        robotIds.distinct().map { judge(order, it) }
+        val applied = siteTimings.current() != null
+        robotIds.distinct().map { judge(order, it, applied) }
     }
 
     /**
@@ -192,9 +233,13 @@ class MissionHost(
      *
      * 도는 실행과 같은 작업 지시 id 로 다시 내면 판정이 그 기체를 도는 실행으로 빼므로 IDEMPOTENT 가 아니라 UNASSIGNED 다.
      * 운영 서비스는 작업 지시 id 를 다시 쓰지 않는다.
+     *
+     * 미적용 판단은 한 번만 읽어 모든 기체의 판정에 같은 값을 쓴다. 미적용이면 통과한 기체가 없어 UNASSIGNED 다. 미들웨어의 채택은
+     * 기체마다 관문을 걸므로 기체가 없으면 요구 근거 등급 검사도 하지 않는다.
      */
     fun submit(order: JobOrder, candidates: List<String>): SubmitOutcome = lock.withLock {
-        val judged = candidates.distinct().map { judge(order, it) }
+        val applied = siteTimings.current() != null
+        val judged = candidates.distinct().map { judge(order, it, applied) }
         val excluded = judged.filter { !it.passed }
         when (val submission = middleware.assign(order, judged.filter { it.passed }.map { it.robotId })) {
             is Middleware.Submission.Accepted -> SubmitOutcome(
@@ -233,6 +278,36 @@ class MissionHost(
         )
     }
 
+    /**
+     * 봉인된 인시던트(S3c 스펙 §7.2). 최신부터 많아야 [limit] 개다. 번들은 미들웨어 안의 값이라 호스트 잠금 아래에서 옮긴다.
+     */
+    fun incidents(limit: Int): IncidentsView = lock.withLock {
+        val all = middleware.incidents()
+        IncidentsView(
+            instanceId = middleware.instanceId,
+            total = all.size,
+            incidents = all.asReversed().take(limit).map { bundle ->
+                val intent = bundle.intent
+                IncidentView(
+                    incidentId = bundle.incidentId,
+                    executionId = bundle.executionId,
+                    jobOrderId = bundle.jobOrderId,
+                    robotId = bundle.robotId,
+                    unitId = bundle.unitId,
+                    at = bundle.at,
+                    failureClass = bundle.failureClass,
+                    route = bundle.route,
+                    missionVersion = intent.missionVersion,
+                    siteSettingsVersion = intent.siteSettingsVersion,
+                    evidenceBeforeSeconds = seconds(intent.evidenceWindowBefore),
+                    evidenceAfterSeconds = seconds(intent.evidenceWindowAfter),
+                    inDoubtGraceSeconds = intent.inDoubtGrace?.let(::seconds),
+                    stallWindowSeconds = intent.stallWindow?.let(::seconds),
+                )
+            },
+        )
+    }
+
     /** 마지막 pump 가 읽은 셀 대역 스냅숏. 못 읽었으면 `null` 이다. */
     fun cell(): CellSnapshot? = lock.withLock { latestCell }
 
@@ -258,7 +333,7 @@ class MissionHost(
      * 스킬 적합은 지금 활성 정의로 작업 지시를 계획해, 경로가 로봇인 단위의 스킬이 기체가 선언한 스킬에 다 있는가다.
      * 도는 실행은 그 기체의 실행 중 물리 상태가 정착하지 않은 것이다. 운영자 보류에 선 실행도 도는 실행이다(스펙 §12).
      */
-    private fun judge(order: JobOrder, robotId: String): HostEligibility {
+    private fun judge(order: JobOrder, robotId: String, applied: Boolean): HostEligibility {
         val active = requireNotNull(catalog.active(order.workMasterId)) { "카탈로그에 없는 WorkMaster 다: ${order.workMasterId}" }
         val needed = active.capability.plan(order).filter { it.route == Route.ROBOT }.map { it.skillType }.toSortedSet()
         val declared = robots.capabilities(robotId)?.skillsList?.map { it.skillType }?.toSet()
@@ -277,10 +352,14 @@ class MissionHost(
                 SkillFit.UNKNOWN -> add("기체 케이퍼빌리티를 못 물어봤다")
             }
             if (running != null) add("도는 실행이 있다: $running")
+            if (!applied) add(UNAPPLIED_REASON)
         }
         return HostEligibility(robotId, fit, missing, running, passed = reasons.isEmpty(), reasons = reasons)
     }
 
+    /** picasso 의 ISO-8601 기간 문자열을 초로 되돌린다. 60초는 `PT1M` 으로 접혀 온다. */
+    private fun seconds(iso: String): Long = Duration.parse(iso).seconds
+
     private fun view(response: JobResponse) = JobResponseView(
         jobResponseId = response.jobResponseId,
         version = response.version,
@@ -308,6 +387,9 @@ class MissionHost(
         /** pump 주기(T5). 셀 대역이 채운 슬롯을 E2 마감(15초)보다 훨씬 짧게 다시 읽는다. */
         val PUMP_PERIOD: Duration = Duration.ofMillis(250)
 
+        /** 현장 시간값 미적용 동안 판정이 기체마다 더하는 이유(S3c 스펙 T8). 화면에 그대로 보인다. */
+        const val UNAPPLIED_REASON = "현장 시간값 미적용: 실행 호스트가 현장 설정을 아직 읽지 못했다"
+
         /** 받는 WorkMaster. DeliverContainer 는 플릿 포트 구현이 없어 받지 않는다(스펙 §1). */
         val WORK_MASTERS: Set<String> = setOf(InspectAsset.WORK_MASTER, PrepareSequencedRack.WORK_MASTER)
     }
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt
index 9daef14..7a5bb18 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt
@@ -11,6 +11,7 @@ import dev.picasso.ops.host.mission.StoredMissionCatalog
 import dev.picasso.ops.host.store.HostSchema
 import dev.picasso.ops.host.store.HostSchemaMigrated
 import dev.picasso.ops.host.store.MissionStore
+import dev.picasso.ops.host.timings.SiteTimingsReader
 import io.grpc.ManagedChannel
 import io.grpc.ManagedChannelBuilder
 import org.springframework.beans.factory.annotation.Value
@@ -71,9 +72,25 @@ open class MissionHostApplication {
     open fun missionCatalog(store: MissionStore): StoredMissionCatalog =
         StoredMissionCatalog().apply { restore(store.activeVersions()) }
 
+    /**
+     * 현장 시간값 읽기 주기(S3c 스펙 §7.1). 기동 안에서 운영 서비스의 뷰를 한 번 동기로 읽고, 실패하면 미적용으로 뜬 뒤
+     * [interval](`host.site-timings.read-interval`, 기본 1초)마다 다시 읽는다. ops 스키마가 아직 없어도 기동은 멈추지 않는다.
+     */
+    @Bean(destroyMethod = "close")
+    open fun siteTimingsReader(
+        jdbc: JdbcClient,
+        clock: HostClock,
+        @Value("\${host.site-timings.read-interval}") interval: Duration,
+    ): SiteTimingsReader = SiteTimingsReader(jdbc, clock).start(interval)
+
     @Bean(destroyMethod = "close")
-    open fun missionHost(robots: RobotPort, cellBand: CellBandClient, clock: HostClock, catalog: StoredMissionCatalog): MissionHost =
-        MissionHost(robots, cellBand, clock, catalog).start()
+    open fun missionHost(
+        robots: RobotPort,
+        cellBand: CellBandClient,
+        clock: HostClock,
+        catalog: StoredMissionCatalog,
+        timings: SiteTimingsReader,
+    ): MissionHost = MissionHost(robots, cellBand, clock, catalog, timings).start()
 
     /**
      * 모의 실행기(S3b 스펙 §6.4). 프로파일과 스키마 경로는 작업 디렉터리 기준으로 푼다. `:mission-host:run` 은 저장소 루트에서
diff --git a/mission-host/src/main/resources/mission-host.properties b/mission-host/src/main/resources/mission-host.properties
index de72112..36f8877 100644
--- a/mission-host/src/main/resources/mission-host.properties
+++ b/mission-host/src/main/resources/mission-host.properties
@@ -19,3 +19,5 @@ host.mock-run.profile=mission-host/mock-run/humanoid-a.json
 host.mock-run.schema=picasso/profile/schema/capability-profile.schema.json
 # 가상 시간 상한. 이 안에 정착하지 않으면 모의 실행은 실패(NOT_SETTLED)다. 실제 시간 상한 30초는 코드에 둔다(T10).
 host.mock-run.virtual-limit=PT10M
+# 현장 시간값 읽기 주기(S3c 스펙 §7.1). 실제 시간이다. 기동 안의 첫 읽기는 주기와 관계없이 한 번 동기로 한다.
+host.site-timings.read-interval=PT1S
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
index 493de3a..3d57be8 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
@@ -4,6 +4,7 @@ import com.fasterxml.jackson.databind.JsonNode
 import com.fasterxml.jackson.databind.ObjectMapper
 import com.sun.net.httpserver.HttpServer
 import dev.picasso.mimic.cli.MimicCli
+import dev.picasso.ops.host.timings.SiteTimingsView
 import dev.picasso.registry.PostgresSupport
 import org.springframework.boot.web.context.WebServerApplicationContext
 import org.springframework.context.ConfigurableApplicationContext
@@ -27,14 +28,54 @@ import java.util.concurrent.CopyOnWriteArrayList
  * 임무 버전 저장은 registry 시험 픽스처의 Postgres 다(S3b 스펙 §6.1). 띄울 때마다 `mission` 스키마를 지운다. 덧붙이기 전용
  * 트리거가 DELETE·TRUNCATE 를 막으므로 스키마째 지운다. [restartHost] 는 DB 를 그대로 두고 호스트만 다시 띄운다.
  *
+ * 현장 시간값 뷰(S3c 스펙 §7.1)는 운영 서비스의 마이그레이션이 만들지만 이 세트에는 운영 서비스가 없다. 그래서 띄울 때마다 ops
+ * 스키마를 지우고, 호스트 main 의 뷰 상수([SiteTimingsView])로 `VALUES` 한 행의 대역 뷰를 만든다([timingsView]). 실제 뷰와 칸이
+ * 같은지는 통합 시험이 대조한다.
+ *
  * @param mockVirtualLimit 주면 모의 실행의 가상 시간 상한을 이것으로 덮는다.
+ * @param readInterval 주면 현장 시간값 읽기 주기를 이것으로 덮는다. 길게 주면 기동 안의 첫 읽기만 일어난다.
+ * @param timings 대역 뷰의 한 행(버전, 앞 폭, 뒤 폭, inDoubtGrace, stallWindow). `null` 이면 뷰를 만들지 않아 호스트가 미적용으로 뜬다.
  */
-class HostBench(private val mockVirtualLimit: Duration? = null) : AutoCloseable {
+class HostBench(
+    private val mockVirtualLimit: Duration? = null,
+    private val readInterval: Duration? = null,
+    timings: List<Long>? = STANDARD_TIMINGS,
+) : AutoCloseable {
 
     init {
         PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
+        PostgresSupport.execute("DROP SCHEMA IF EXISTS ops CASCADE")
+        timings?.let(::timingsView)
+    }
+
+    /** 대역 뷰를 [row] 한 행으로 다시 만든다. 호스트는 다음 읽기(1초 주기)에서 그 행을 읽는다. */
+    fun timingsView(row: List<Long>) {
+        require(row.size == SiteTimingsView.COLUMNS.size) { "칸이 ${SiteTimingsView.COLUMNS.size} 개여야 한다: $row" }
+        PostgresSupport.execute("CREATE SCHEMA IF NOT EXISTS ${SiteTimingsView.NAME.substringBefore('.')}")
+        dropTimingsView()
+        val values = SiteTimingsView.COLUMNS.zip(row).joinToString(", ") { (column, value) -> "$value::${column.type}" }
+        PostgresSupport.execute(
+            "CREATE VIEW ${SiteTimingsView.NAME} (${SiteTimingsView.COLUMNS.joinToString(", ") { it.name }}) AS VALUES ($values)",
+        )
     }
 
+    /** 대역 뷰를 지운다. 호스트의 다음 읽기가 실패한다. */
+    fun dropTimingsView() = PostgresSupport.execute("DROP VIEW IF EXISTS ${SiteTimingsView.NAME}")
+
+    /** `GET /host/site-timings` 가 [done] 을 만족할 때까지 기다린다(실제 시간 상한 5초). 만족한 본문을 돌려준다. */
+    fun awaitTimings(done: (JsonNode) -> Boolean): JsonNode {
+        val deadline = Instant.now().plusSeconds(5)
+        while (true) {
+            val view = get("/host/site-timings")
+            if (done(view)) return view
+            check(Instant.now().isBefore(deadline)) { "5초 안에 현장 시간값 상태가 바뀌지 않았다: $view" }
+            Thread.sleep(100)
+        }
+    }
+
+    /** 호스트가 버전 [version] 을 적용할 때까지 기다린다. */
+    fun awaitApplied(version: Long): JsonNode = awaitTimings { it["applied"]?.get("version")?.asLong() == version }
+
     val mimic: MimicCli.Started = checkNotNull(
         MimicCli().start(
             robots = mapOf(
@@ -101,6 +142,7 @@ class HostBench(private val mockVirtualLimit: Duration? = null) : AutoCloseable
             add("--host.mock-run.profile=$MOCK_PROFILE")
             add("--host.mock-run.schema=$SCHEMA")
             mockVirtualLimit?.let { add("--host.mock-run.virtual-limit=$it") }
+            readInterval?.let { add("--host.site-timings.read-interval=$it") }
         }.toTypedArray(),
     )
 
@@ -185,6 +227,9 @@ class HostBench(private val mockVirtualLimit: Duration? = null) : AutoCloseable
         const val SOURCE = "SEQ-IN-02.BIN-A"
         const val MATERIAL = "ENGINE-COVER-A"
 
+        /** 대역 뷰의 처음 행. 버전 1 과 picasso 케이퍼빌리티 기본값이며, 운영 서비스 마이그레이션이 만드는 버전 1 과 같다. */
+        val STANDARD_TIMINGS: List<Long> = listOf(1, 30, 15, 60, 300)
+
         val ROOT: Path = Path.of("..").toAbsolutePath().normalize()
         val HTTP: HttpClient = HttpClient.newHttpClient()
         val JSON = ObjectMapper()
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt
index 8f8bd1c..0169c15 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt
@@ -441,6 +441,60 @@ class MissionVersionsTest {
         }
     }
 
+    @Test
+    fun `기한을 넘긴 대기의 인시던트가 봉인 라운드의 설정 버전과 시간값을 싣고 최신부터 나온다`() {
+        HostBench(timings = listOf(7, 40, 20, 90, 600)).use { bench ->
+            assertTrue(bench.get("/host/incidents")["incidents"].isEmpty)
+            bench.cellBody = filledCell(rackPresent = "false")
+            bench.nextPump()
+            assertEquals(1, bench.activated(bench.arrivalWait()))
+            val first = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01"), "candidates", HUMANOID)).body!!["executionId"].asText()
+            bench.driveUntil(first, setOf("ABORTED"))
+
+            // 둘째 작업 지시가 대기 중일 때 버전 8 을 적용하면, 그 대기의 인시던트는 봉인 라운드의 버전 8 을 든다.
+            val second = bench.post("/host/job-orders", request(rack("JO-2", "RACK-204.S02"), "candidates", HUMANOID)).body!!["executionId"].asText()
+            bench.timingsView(listOf(8, 45, 25, 120, 900))
+            bench.awaitApplied(8)
+            bench.driveUntil(second, setOf("ABORTED"))
+
+            // limit 을 생략하면 기본 50 개까지라 둘 다 나온다.
+            val view = bench.get("/host/incidents")
+            assertTrue(view["instanceId"].asText().startsWith("mw-"), view.toString())
+            assertEquals(2, view["total"].asInt(), view.toString())
+            assertEquals(2, view["incidents"].size(), view.toString())
+            val (newest, oldest) = view["incidents"].toList()
+            assertEquals(
+                listOf(second, "JO-2", 8L, listOf(45L, 25L, 120L, 900L)),
+                listOf(newest["executionId"].asText(), newest["jobOrderId"].asText(), newest["siteSettingsVersion"].asLong(), seconds(newest)),
+            )
+            assertEquals(
+                listOf(first, "JO-1", 7L, listOf(40L, 20L, 90L, 600L)),
+                listOf(oldest["executionId"].asText(), oldest["jobOrderId"].asText(), oldest["siteSettingsVersion"].asLong(), seconds(oldest)),
+            )
+            listOf(newest, oldest).forEach { incident ->
+                assertEquals("SIGNAL_DEADLINE", incident["failureClass"].asText(), incident.toString())
+                assertEquals("rack-arrival", incident["unitId"].asText())
+                assertEquals(HUMANOID, incident["robotId"].asText())
+                assertEquals("SIGNAL", incident["route"].asText())
+                assertEquals(1, incident["missionVersion"].asInt())
+                assertTrue(incident["incidentId"].asText().startsWith("incident-"), incident.toString())
+                Instant.parse(incident["at"].asText())
+            }
+
+            assertEquals(listOf(second), bench.get("/host/incidents?limit=1")["incidents"].map { it["executionId"].asText() })
+            assertEquals(2, bench.get("/host/incidents?limit=1")["total"].asInt())
+            listOf("0", "501", "x").forEach { limit ->
+                val bad = bench.fetch("/host/incidents?limit=$limit")
+                assertEquals(400, bad.status, limit)
+                assertEquals("BAD_REQUEST", bad.body!!["error"].asText())
+            }
+        }
+    }
+
+    /** 인시던트의 시간값 넷(앞 폭, 뒤 폭, inDoubtGrace, stallWindow). */
+    private fun seconds(incident: JsonNode): List<Long> =
+        listOf("evidenceBeforeSeconds", "evidenceAfterSeconds", "inDoubtGraceSeconds", "stallWindowSeconds").map { incident[it].asLong() }
+
     @Test
     fun `재기동해도 활성 버전 번호가 같고 다음 활성화는 가장 큰 번호 더하기 1 이다`() {
         HostBench().use { bench ->
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && ./gradlew :mission-host:test -q
```
Expected: mission-host 57, 실패 0. 백그라운드로 돌린다(Docker, Testcontainers). e2e 는 Task 4 전에는 돌리지 않는다. Task 1 부터 `SkeletonTest` 가 ops 마이그레이션 목록에 V4 가 없다고 깨지고, Task 2 부터 호스트가 운영 서비스의 마이그레이션 뒤 첫 주기 읽기까지 미적용이라 작업 지시 시험이 흔들린다.

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && git add mission-host/src/main/kotlin/dev/picasso/ops/host/timings/SiteTimingsReader.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/SiteTimingsController.kt mission-host/src/test/kotlin/dev/picasso/ops/host/SiteTimingsHostTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/SiteTimingsReaderTest.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt mission-host/src/main/resources/mission-host.properties mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt && git commit -F - <<'EOF'
feat(mission-host): 현장 시간값 뷰 주기 읽기와 미들웨어 소스 전달

- 설정 키 `host.site-timings.read-interval`(기본 1초) 주기로 호스트 잠금 밖에서 뷰를 읽고 `SiteTimings.problems()` 검사 통과 값만 적용, 미들웨어에 `siteTimings` 소스로 전달
- 기동 중 동기 첫 읽기, 첫 읽기 전에는 미적용으로 배정 가능 판정 사유에 포함(제출은 UNASSIGNED), 읽기 실패와 범위 밖 행은 마지막 적용 버전 유지와 사유 표시
- `GET /host/site-timings` 와 `GET /host/incidents` 의 설정 버전과 시간값(초 정수), 호스트 시험 세트의 대역 뷰

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s3c-cmp.sh" ops-service/src/main/resources/db/ops/V4__site_timings.sql ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsControllerTest.kt ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettings.kt ops-service/src/main/kotlin/dev/picasso/ops/service/settings/SiteSettingsOperations.kt ops-service/src/main/kotlin/dev/picasso/ops/service/store/OpsSchema.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/SiteSettingsController.kt ops-service/src/test/kotlin/dev/picasso/ops/service/HostClientTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsStoreTest.kt mission-host/src/main/kotlin/dev/picasso/ops/host/timings/SiteTimingsReader.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/SiteTimingsController.kt mission-host/src/test/kotlin/dev/picasso/ops/host/SiteTimingsHostTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/SiteTimingsReaderTest.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt mission-host/src/main/resources/mission-host.properties mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt
```
Expected: 19개 모두 `같음`.

### Task 3: 화면: 현장 설정 구역의 시간값과 실행 호스트 반영

**Files:**
- Modify: `ui/src/api.ts`, `ui/src/components/SiteArea.test.tsx`, `ui/src/components/SiteArea.tsx`, `ui/src/testing/fakeOps.ts`

- [ ] **Step 1: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/task3.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/task3.patch"
```

```diff
diff --git a/ui/src/api.ts b/ui/src/api.ts
index 01c8981..2309adc 100644
--- a/ui/src/api.ts
+++ b/ui/src/api.ts
@@ -101,21 +101,63 @@ export interface RobotListView {
   connectionThresholdSeconds?: number | null
 }
 
-/** 현장 설정 버전 한 행(S2 스펙 §5). `mode` 는 운영 서비스의 열거 값(`ENGINEER`)이다. */
-export interface SiteSettingsRecord {
-  version: number
+/** 미들웨어 시간값 넷(S3c JSON 계약 공통 규칙). 초 단위 정수다. */
+export interface SiteTimingValues {
+  evidenceBeforeSeconds: number
+  evidenceAfterSeconds: number
+  inDoubtGraceSeconds: number
+  stallWindowSeconds: number
+}
+
+/** 현장 설정의 값 칸 다섯(S3c JSON 계약 §4). 화면은 PUT 에 늘 다섯을 다 싣는다. */
+export interface SiteSettingValues extends SiteTimingValues {
   connectionThresholdSeconds: number
+}
+
+/** 현장 설정 버전 한 행(S3c JSON 계약 §3). `mode` 는 운영 서비스의 열거 값(`ENGINEER`)이다. */
+export interface SiteSettingsRecord extends SiteSettingValues {
+  version: number
   mode: string
   user: string
   reason: string
   recordedAt: string
 }
 
-/** 운영 서비스의 `GET /api/site-settings`(S2 스펙 §6.1). `history` 는 최신부터다. */
+/** 허용 범위(초, 양 끝 포함). 평평한 한 객체다(S3c JSON 계약 §3·§5). */
+export interface SiteSettingsRange {
+  minConnectionThresholdSeconds: number
+  maxConnectionThresholdSeconds: number
+  minEvidenceBeforeSeconds: number
+  maxEvidenceBeforeSeconds: number
+  minEvidenceAfterSeconds: number
+  maxEvidenceAfterSeconds: number
+  minInDoubtGraceSeconds: number
+  maxInDoubtGraceSeconds: number
+  minStallWindowSeconds: number
+  maxStallWindowSeconds: number
+}
+
+/**
+ * 실행 호스트의 현장 시간값 적용 상태(S3c JSON 계약 §7). 운영 서비스가 호스트 본문을 그대로 넘긴다. `applied` 가 null 이면
+ * 미적용이고, `rejected` 는 범위 밖이라 적용하지 않은 버전과 이유다. 시각은 호스트 시계다.
+ */
+export interface HostTimings {
+  applied: ({ version: number } & SiteTimingValues) | null
+  appliedAt: string | null
+  lastReadAt: string | null
+  readError: string | null
+  rejected: { version: number; reasons: string[] } | null
+}
+
+/**
+ * 운영 서비스의 `GET /api/site-settings`(S3c JSON 계약 §3). `history` 는 최신부터다. `hostTimings` 는 호스트가 닿지 않으면
+ * null 이다.
+ */
 export interface SiteSettingsView {
   current: SiteSettingsRecord
-  range: { minConnectionThresholdSeconds: number; maxConnectionThresholdSeconds: number }
+  range: SiteSettingsRange
   history: SiteSettingsRecord[]
+  hostTimings: HostTimings | null
 }
 
 /** 어댑터 빌드 하나. `conformance` 는 registry 값 그대로다(S1 은 `UNTESTED` 만 본다). */
@@ -332,12 +374,8 @@ const retirementPath = (robotId: string) => `/api/robots/${encodeURIComponent(ro
 
 export const fetchRobots = (session: Session) => getJson<RobotListView>('/api/robots', session)
 export const fetchSiteSettings = (session: Session) => getJson<SiteSettingsView>('/api/site-settings', session)
-export const changeSiteSettings = (
-  session: Session,
-  baseVersion: number,
-  connectionThresholdSeconds: number,
-  reason: string,
-) => send('PUT', '/api/site-settings', session, { baseVersion, connectionThresholdSeconds, reason })
+export const changeSiteSettings = (session: Session, baseVersion: number, values: SiteSettingValues, reason: string) =>
+  send('PUT', '/api/site-settings', session, { baseVersion, ...values, reason })
 export const fetchOperations = (session: Session) =>
   getJson<OperationRecord[]>('/api/operations', session)
 export const declareRobot = (
diff --git a/ui/src/components/SiteArea.test.tsx b/ui/src/components/SiteArea.test.tsx
index 2409e95..d553c42 100644
--- a/ui/src/components/SiteArea.test.tsx
+++ b/ui/src/components/SiteArea.test.tsx
@@ -3,7 +3,7 @@ import userEvent from '@testing-library/user-event'
 import { afterEach, describe, expect, it, vi } from 'vitest'
 import App from '../App'
 import type { Finding, Session, SiteSettingsView } from '../api'
-import { installFakeOps, outcome, robotView, settingsView } from '../testing/fakeOps'
+import { hostTimings, installFakeOps, outcome, robotView, settingsView } from '../testing/fakeOps'
 import { SiteArea } from './SiteArea'
 
 const engineer: Session = { mode: 'engineer', user: 'local' }
@@ -12,6 +12,10 @@ const operator: Session = { mode: 'operator', user: 'local' }
 const version2 = {
   version: 2,
   connectionThresholdSeconds: 60,
+  evidenceBeforeSeconds: 45,
+  evidenceAfterSeconds: 20,
+  inDoubtGraceSeconds: 90,
+  stallWindowSeconds: 600,
   mode: 'ENGINEER',
   user: 'kim',
   reason: '연결 기준 줄임',
@@ -42,17 +46,37 @@ function field(region: HTMLElement, name: string) {
   return term.nextElementSibling?.textContent
 }
 
-async function fill(seconds: string, reason: string) {
+/** 칸은 지금 값으로 채워져 있으므로 지우고 넣는다. */
+async function set(label: string, value: string) {
   const form = screen.getByRole('form', { name: '현장 설정 변경' })
-  await userEvent.type(within(form).getByLabelText('연결 기준 시간(초)'), seconds)
+  const input = within(form).getByLabelText(label)
+  await userEvent.clear(input)
+  await userEvent.type(input, value)
+  return form
+}
+
+async function fill(seconds: string, reason: string) {
+  const form = await set('연결 기준 시간(초)', seconds)
   await userEvent.type(within(form).getByLabelText('변경 사유'), reason)
   return form
 }
 
+const submitted = (fake: { calls: { method: string; body: unknown }[] }) =>
+  fake.calls.filter((call) => call.method === 'PUT').map((call) => call.body)
+
+/** 기준 버전 1 의 값 다섯(마이그레이션이 넣는 값). */
+const version1Values = {
+  connectionThresholdSeconds: 90,
+  evidenceBeforeSeconds: 30,
+  evidenceAfterSeconds: 15,
+  inDoubtGraceSeconds: 60,
+  stallWindowSeconds: 300,
+}
+
 describe('현장·자원 영역', () => {
   afterEach(() => vi.unstubAllGlobals())
 
-  it('현재 버전과 연결 기준 시간, 허용 범위, 버전 이력을 최신부터 보인다', () => {
+  it('현재 버전과 연결 기준 시간, 허용 범위, 시간값 넷의 열을 덧붙인 버전 이력을 최신부터 보인다', () => {
     installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
     render(<SiteArea view={twoVersions} opsError={null} session={engineer} onChanged={() => undefined} />)
     const section = screen.getByRole('region', { name: '현장 설정' })
@@ -60,17 +84,49 @@ describe('현장·자원 영역', () => {
     expect(field(section, '연결 기준 시간')).toBe('60초')
     expect(field(section, '허용 범위')).toBe('60~3600초')
     const rows = within(screen.getByRole('table', { name: '현장 설정 버전 이력' })).getAllByRole('row').slice(1)
+    // 기존 칸 순서는 S2 그대로이고 시간값 넷은 끝에 붙는다(S3c 스펙 §8). Playwright 는 행 앞부분으로 찾는다.
     expect(rows.map((row) => row.textContent)).toEqual([
-      '260초kim엔지니어연결 기준 줄임t2',
-      '190초system엔지니어S1 설정값 이전t0',
+      '260초kim엔지니어연결 기준 줄임t245초20초90초600초',
+      '190초system엔지니어S1 설정값 이전t030초15초60초300초',
     ])
   })
 
+  it('시간값 넷을 결과 판정 값과 정체 표시 두 묶음으로 나눠 묶음마다 파급을 적는다', () => {
+    installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
+    render(<SiteArea view={twoVersions} opsError={null} session={engineer} onChanged={() => undefined} />)
+    const judging = screen.getByRole('region', { name: '결과 판정 값' })
+    expect(field(judging, '근거 윈도우 앞 폭')).toBe('45초')
+    expect(field(judging, '근거 윈도우 뒤 폭')).toBe('20초')
+    expect(field(judging, 'inDoubtGrace')).toBe('90초')
+    expect(within(judging).queryByText('stallWindow', { selector: 'dt' })).not.toBeInTheDocument()
+    expect(judging).toHaveTextContent('앞 폭을 늘리면 옛 신호가 완료 근거로 들어옵니다')
+    expect(judging).toHaveTextContent('뒤 폭이나 inDoubtGrace 를 줄이면 UNVERIFIED 와 운영자 대기가 늘어납니다')
+    const stall = screen.getByRole('region', { name: '정체 표시' })
+    expect(field(stall, 'stallWindow')).toBe('600초')
+    expect(within(stall).queryByText('inDoubtGrace', { selector: 'dt' })).not.toBeInTheDocument()
+    expect(stall).toHaveTextContent(
+      'stallWindow 는 정체를 사람에게 보이는 시점만 바꿉니다. 실패 판정이나 자동 조치는 바뀌지 않습니다',
+    )
+    // 폼도 같은 두 묶음이고 칸마다 허용 범위를 보인다.
+    const form = screen.getByRole('form', { name: '현장 설정 변경' })
+    const judgingInputs = within(form).getByRole('group', { name: '결과 판정 값' })
+    expect(within(judgingInputs).getByLabelText('근거 윈도우 앞 폭(초)')).toHaveValue(45)
+    expect(judgingInputs).toHaveTextContent('허용 범위 5~120초')
+    expect(judgingInputs).toHaveTextContent('허용 범위 10~600초')
+    const stallInputs = within(form).getByRole('group', { name: '정체 표시' })
+    expect(within(stallInputs).getByLabelText('stallWindow(초)')).toHaveValue(600)
+    expect(stallInputs).toHaveTextContent('허용 범위 30~3600초')
+  })
+
   it('운영자 모드에서는 변경 폼 대신 엔지니어 모드에서 한다고 보인다', () => {
     installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
     render(<SiteArea view={twoVersions} opsError={null} session={operator} onChanged={() => undefined} />)
     expect(screen.queryByRole('form', { name: '현장 설정 변경' })).not.toBeInTheDocument()
     expect(screen.getByText('현장 설정 변경은 엔지니어 모드에서 합니다')).toBeInTheDocument()
+    // 값과 묶음, 실행 호스트 반영은 운영자 모드에서도 읽는다. 입력 칸은 없다.
+    expect(field(screen.getByRole('region', { name: '정체 표시' }), 'stallWindow')).toBe('600초')
+    expect(screen.getByText('실행 호스트 반영: 버전 1')).toBeInTheDocument()
+    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument()
   })
 
   it('기준 버전은 고치기 시작한 버전으로 고정해 엔지니어 모드로 보낸다', async () => {
@@ -86,12 +142,34 @@ describe('현장·자원 영역', () => {
     await waitFor(() => expect(fake.calls.some((call) => call.method === 'PUT')).toBe(true))
     const put = fake.calls.find((call) => call.method === 'PUT')!
     expect(put.url).toBe('/api/site-settings')
-    expect(put.body).toEqual({ baseVersion: 1, connectionThresholdSeconds: 60, reason: '연결 기준 줄임' })
+    // 기준 버전 1 의 시간값을 그대로 싣는다. 다시 읽은 버전 2 의 값이 아니다.
+    expect(put.body).toEqual({ baseVersion: 1, ...version1Values, connectionThresholdSeconds: 60, reason: '연결 기준 줄임' })
     expect(put.headers['X-Ops-Mode']).toBe('engineer')
     expect(put.headers['Content-Type']).toBe('application/json')
     expect(await screen.findByText('연결 기준 시간 60초로 변경: 반영됨')).toBeInTheDocument()
   })
 
+  it('사유를 먼저 넣어도 그 순간의 기준 버전과 값 다섯을 고정하고 뒤에 고친 칸만 바꾼다', async () => {
+    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
+    const { rerender } = render(
+      <SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />,
+    )
+    // 처음 고친 것은 사유다. 이 순간 기준 버전 1 과 그 값 다섯이 고정된다.
+    const form = screen.getByRole('form', { name: '현장 설정 변경' })
+    await userEvent.type(within(form).getByLabelText('변경 사유'), '정체 표시 늦춤')
+    // 다시 읽은 버전 2 는 값 다섯이 모두 다르다. 고정한 값이 아니라 이 값이 실리면 남의 변경을 모르고 덮어쓴다.
+    rerender(<SiteArea view={twoVersions} opsError={null} session={engineer} onChanged={() => undefined} />)
+    await set('stallWindow(초)', '900')
+    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
+    await waitFor(() => expect(submitted(fake)).toHaveLength(1))
+    expect(submitted(fake)[0]).toEqual({
+      baseVersion: 1,
+      ...version1Values,
+      stallWindowSeconds: 900,
+      reason: '정체 표시 늦춤',
+    })
+  })
+
   it('범위 밖 값과 빈 사유는 보내지 않는다', async () => {
     const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
     render(<SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />)
@@ -106,6 +184,129 @@ describe('현장·자원 영역', () => {
     expect(fake.calls.some((call) => call.method === 'PUT')).toBe(false)
   })
 
+  it('시간값 넷은 칸마다 범위 밖이면 그 칸을 적고 보내지 않으며 양 끝 값은 보낸다', async () => {
+    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
+    render(<SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />)
+    const outside: [string, string, string][] = [
+      ['근거 윈도우 앞 폭(초)', '4', '근거 윈도우 앞 폭은 5~120초의 정수여야 합니다'],
+      ['근거 윈도우 뒤 폭(초)', '121', '근거 윈도우 뒤 폭은 5~120초의 정수여야 합니다'],
+      ['inDoubtGrace(초)', '9', 'inDoubtGrace 는 10~600초의 정수여야 합니다'],
+      ['stallWindow(초)', '3601', 'stallWindow 는 30~3600초의 정수여야 합니다'],
+    ]
+    const reason = () => within(screen.getByRole('form', { name: '현장 설정 변경' })).getByLabelText('변경 사유')
+    await userEvent.type(reason(), '범위 확인')
+    for (const [label, value, message] of outside) {
+      const form = await set(label, value)
+      await userEvent.click(within(form).getByRole('button', { name: '변경' }))
+      // 다른 칸은 지금 값이라 범위 안이다. 알림은 그 칸 하나만 적는다.
+      expect(within(form).getByRole('alert').textContent).toBe(message)
+      await set(label, '30')
+    }
+    let form = await set('inDoubtGrace(초)', '15.5')
+    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
+    expect(within(form).getByRole('alert').textContent).toBe('inDoubtGrace 는 10~600초의 정수여야 합니다')
+    expect(submitted(fake)).toEqual([])
+    await set('근거 윈도우 앞 폭(초)', '5')
+    await set('근거 윈도우 뒤 폭(초)', '120')
+    await set('inDoubtGrace(초)', '10')
+    form = await set('stallWindow(초)', '3600')
+    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
+    await waitFor(() => expect(submitted(fake)).toHaveLength(1))
+    expect(submitted(fake)[0]).toEqual({
+      baseVersion: 1,
+      connectionThresholdSeconds: 90,
+      evidenceBeforeSeconds: 5,
+      evidenceAfterSeconds: 120,
+      inDoubtGraceSeconds: 10,
+      stallWindowSeconds: 3600,
+      reason: '범위 확인',
+    })
+  })
+
+  it('PUT 은 바꾸지 않은 칸까지 값 다섯을 다 싣는다', async () => {
+    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
+    render(<SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />)
+    const form = await set('stallWindow(초)', '600')
+    await userEvent.type(within(form).getByLabelText('변경 사유'), '정체 표시 늦춤')
+    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
+    await waitFor(() => expect(submitted(fake)).toHaveLength(1))
+    expect(submitted(fake)[0]).toEqual({
+      baseVersion: 1,
+      ...version1Values,
+      stallWindowSeconds: 600,
+      reason: '정체 표시 늦춤',
+    })
+  })
+
+  it('변경 결과 문구는 기준 버전에서 바뀐 칸만 칸 순서대로 적는다', async () => {
+    installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
+    render(<SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />)
+    await set('stallWindow(초)', '600')
+    let form = await set('inDoubtGrace(초)', '90')
+    await userEvent.type(within(form).getByLabelText('변경 사유'), '유예 늘림')
+    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
+    expect(await screen.findByText('inDoubtGrace 90초, stallWindow 600초로 변경: 반영됨')).toBeInTheDocument()
+    form = screen.getByRole('form', { name: '현장 설정 변경' })
+    await userEvent.type(within(form).getByLabelText('변경 사유'), '같은 값')
+    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
+    expect(await screen.findByText('현장 설정 같은 값으로 새 버전 기록: 반영됨')).toBeInTheDocument()
+  })
+
+  it('실행 호스트의 적용 버전을 최신 설정 버전과 따로 보이고 호스트가 닿지 않으면 모름이다', () => {
+    const lagging = { ...twoVersions, hostTimings: hostTimings() }
+    const { rerender } = render(
+      <SiteArea view={lagging} opsError={null} session={engineer} onChanged={() => undefined} />,
+    )
+    const section = screen.getByRole('region', { name: '현장 설정' })
+    expect(field(section, '현재 버전')).toBe('2')
+    expect(within(section).getByText('실행 호스트 반영: 버전 1')).toBeInTheDocument()
+    const caught = {
+      ...twoVersions,
+      hostTimings: hostTimings({ applied: { version: 2, ...version1Values, stallWindowSeconds: 600 } }),
+    }
+    rerender(<SiteArea view={caught} opsError={null} session={engineer} onChanged={() => undefined} />)
+    expect(within(section).getByText('실행 호스트 반영: 버전 2')).toBeInTheDocument()
+    const silent = { ...twoVersions, hostTimings: null }
+    rerender(<SiteArea view={silent} opsError={null} session={engineer} onChanged={() => undefined} />)
+    expect(within(section).getByText('실행 호스트 반영: 모름')).toBeInTheDocument()
+    expect(within(section).queryByText(/실행 호스트 반영: 버전/)).not.toBeInTheDocument()
+  })
+
+  it('실행 호스트의 미적용과 읽기 실패, 범위 밖이라 적용하지 않은 버전을 보인다', () => {
+    const unapplied = {
+      ...twoVersions,
+      hostTimings: hostTimings({
+        applied: null,
+        appliedAt: null,
+        readError: 'PSQLException: relation "ops.site_timings_current" does not exist',
+      }),
+    }
+    const { rerender } = render(
+      <SiteArea view={unapplied} opsError={null} session={engineer} onChanged={() => undefined} />,
+    )
+    const section = screen.getByRole('region', { name: '현장 설정' })
+    expect(within(section).getByText('실행 호스트 반영: 미적용')).toBeInTheDocument()
+    expect(
+      within(section).getByText(
+        '실행 호스트 읽기 실패: PSQLException: relation "ops.site_timings_current" does not exist',
+      ),
+    ).toBeInTheDocument()
+    const rejected = {
+      ...twoVersions,
+      hostTimings: hostTimings({
+        rejected: { version: 2, reasons: ['stallWindow: 30~3600 초 밖이다 (3601)', 'inDoubtGrace: 10~600 초 밖이다 (9)'] },
+      }),
+    }
+    rerender(<SiteArea view={rejected} opsError={null} session={engineer} onChanged={() => undefined} />)
+    expect(within(section).getByText('실행 호스트 반영: 버전 1')).toBeInTheDocument()
+    expect(
+      within(section).getByText(
+        '실행 호스트가 적용하지 않은 버전 2(범위 밖): stallWindow: 30~3600 초 밖이다 (3601); inDoubtGrace: 10~600 초 밖이다 (9)',
+      ),
+    ).toBeInTheDocument()
+    expect(within(section).queryByText(/실행 호스트 읽기 실패/)).not.toBeInTheDocument()
+  })
+
   it('버전 충돌 거부는 관측값과 후속 행동, 근거 버전 해당 없음으로 보인다', async () => {
     const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
     fake.answer = { status: 200, body: outcome({ result: 'REJECTED', rejection: conflict }) }
diff --git a/ui/src/components/SiteArea.tsx b/ui/src/components/SiteArea.tsx
index 33a2c08..8b1f8c9 100644
--- a/ui/src/components/SiteArea.tsx
+++ b/ui/src/components/SiteArea.tsx
@@ -1,7 +1,7 @@
 import { useState } from 'react'
 import type { FormEvent } from 'react'
 import { changeSiteSettings } from '../api'
-import type { Sent, Session, SiteSettingsView } from '../api'
+import type { HostTimings, Sent, Session, SiteSettingValues, SiteSettingsRange, SiteSettingsView } from '../api'
 import { OutcomeNotice } from './OutcomeNotice'
 
 interface Props {
@@ -15,9 +15,89 @@ interface Props {
 
 const MODE_LABEL: Record<string, string> = { ENGINEER: '엔지니어', OPERATOR: '운영자' }
 
+type Field = keyof SiteSettingValues
+
+interface FieldSpec {
+  key: Field
+  /** 화면의 칸 이름. 입력 이름은 뒤에 `(초)` 를 붙인다. */
+  name: string
+  /** 범위 밖 알림의 주어(조사 포함). */
+  subject: string
+  min: keyof SiteSettingsRange
+  max: keyof SiteSettingsRange
+}
+
+const CONNECTION: FieldSpec = {
+  key: 'connectionThresholdSeconds',
+  name: '연결 기준 시간',
+  subject: '연결 기준 시간은',
+  min: 'minConnectionThresholdSeconds',
+  max: 'maxConnectionThresholdSeconds',
+}
+const BEFORE: FieldSpec = {
+  key: 'evidenceBeforeSeconds',
+  name: '근거 윈도우 앞 폭',
+  subject: '근거 윈도우 앞 폭은',
+  min: 'minEvidenceBeforeSeconds',
+  max: 'maxEvidenceBeforeSeconds',
+}
+const AFTER: FieldSpec = {
+  key: 'evidenceAfterSeconds',
+  name: '근거 윈도우 뒤 폭',
+  subject: '근거 윈도우 뒤 폭은',
+  min: 'minEvidenceAfterSeconds',
+  max: 'maxEvidenceAfterSeconds',
+}
+const IN_DOUBT: FieldSpec = {
+  key: 'inDoubtGraceSeconds',
+  name: 'inDoubtGrace',
+  subject: 'inDoubtGrace 는',
+  min: 'minInDoubtGraceSeconds',
+  max: 'maxInDoubtGraceSeconds',
+}
+const STALL: FieldSpec = {
+  key: 'stallWindowSeconds',
+  name: 'stallWindow',
+  subject: 'stallWindow 는',
+  min: 'minStallWindowSeconds',
+  max: 'maxStallWindowSeconds',
+}
+
+/** 값 칸 다섯. 순서는 운영 서비스 본문의 칸 순서(S3c JSON 계약 §4.1)와 같다. 변경 결과 문구도 이 순서다. */
+const FIELDS: readonly FieldSpec[] = [CONNECTION, BEFORE, AFTER, IN_DOUBT, STALL]
+const TIMINGS: readonly FieldSpec[] = [BEFORE, AFTER, IN_DOUBT, STALL]
+
+/**
+ * 시간값 넷의 묶음 둘(S3c 스펙 §8). 결과 판정을 바꾸는 값과 정체 표시 값은 파급이 달라 따로 보이고, 묶음마다 바꾸면 무엇이
+ * 달라지는지 적는다(운영 관리 화면 설계 제안 §9).
+ */
+const GROUPS: readonly { title: string; fields: readonly FieldSpec[]; effect: string }[] = [
+  {
+    title: '결과 판정 값',
+    fields: [BEFORE, AFTER, IN_DOUBT],
+    effect:
+      '앞 폭을 늘리면 옛 신호가 완료 근거로 들어옵니다. 뒤 폭이나 inDoubtGrace 를 줄이면 UNVERIFIED 와 운영자 대기가 늘어납니다',
+  },
+  {
+    title: '정체 표시',
+    fields: [STALL],
+    effect: 'stallWindow 는 정체를 사람에게 보이는 시점만 바꿉니다. 실패 판정이나 자동 조치는 바뀌지 않습니다',
+  },
+]
+
+/**
+ * 변경 결과 문구. 기준 버전에서 바뀐 칸만 칸 순서대로 적는다. 연결 기준 시간만 바꾸면 S2 문구(`연결 기준 시간 120초로 변경`)
+ * 그대로다. 같은 값으로 보내도 새 버전이 생기므로(S3c JSON 계약 §4) 그 경우도 따로 적는다.
+ */
+function changeSummary(before: SiteSettingValues, after: SiteSettingValues): string {
+  const changed = FIELDS.filter((f) => after[f.key] !== before[f.key]).map((f) => `${f.name} ${after[f.key]}초`)
+  return changed.length === 0 ? '현장 설정 같은 값으로 새 버전 기록' : `${changed.join(', ')}로 변경`
+}
+
 /**
- * 현장·자원 영역의 «현장 설정» 구역(S2 스펙 §7). 지금 버전과 값, 허용 범위, 버전 이력을 보이고, 엔지니어 모드에서만 바꾼다.
- * 바꾼 값은 다음 기체 목록 읽기부터 연결 칸과 막힘의 판정에 쓰인다.
+ * 현장·자원 영역의 «현장 설정» 구역(S2 스펙 §7, S3c 스펙 §8). 지금 버전과 값, 허용 범위, 실행 호스트의 적용 버전, 버전 이력을
+ * 보이고, 엔지니어 모드에서만 바꾼다. 연결 기준 시간은 다음 기체 목록 읽기부터, 시간값 넷은 실행 호스트가 읽은 다음 pump
+ * 부터 쓰인다.
  */
 export function SiteArea({ view, opsError, session, onChanged }: Props) {
   const [busy, setBusy] = useState(false)
@@ -55,12 +135,27 @@ export function SiteArea({ view, opsError, session, onChanged }: Props) {
               {known.range.minConnectionThresholdSeconds}~{known.range.maxConnectionThresholdSeconds}초
             </dd>
           </dl>
+          <HostApplied host={known.hostTimings ?? null} />
+          {GROUPS.map((group) => (
+            <section key={group.title} aria-label={group.title}>
+              <h3>{group.title}</h3>
+              <p>{group.effect}</p>
+              <dl>
+                {group.fields.map((f) => (
+                  <div key={f.key}>
+                    <dt>{f.name}</dt>
+                    <dd>{known.current[f.key]}초</dd>
+                  </div>
+                ))}
+              </dl>
+            </section>
+          ))}
           {session.mode === 'engineer' ? (
             <SettingsForm
               view={known}
               busy={busy}
-              onChange={(base, seconds, reason) =>
-                run(`연결 기준 시간 ${seconds}초로 변경`, () => changeSiteSettings(session, base, seconds, reason))
+              onChange={(base, values, reason) =>
+                run(changeSummary(base, values), () => changeSiteSettings(session, base.version, values, reason))
               }
             />
           ) : (
@@ -76,6 +171,9 @@ export function SiteArea({ view, opsError, session, onChanged }: Props) {
                 <th>모드</th>
                 <th>사유</th>
                 <th>기록 시각</th>
+                {TIMINGS.map((f) => (
+                  <th key={f.key}>{f.name}</th>
+                ))}
               </tr>
             </thead>
             <tbody>
@@ -87,6 +185,9 @@ export function SiteArea({ view, opsError, session, onChanged }: Props) {
                   <td>{MODE_LABEL[record.mode] ?? record.mode}</td>
                   <td>{record.reason}</td>
                   <td>{record.recordedAt}</td>
+                  {TIMINGS.map((f) => (
+                    <td key={f.key}>{record[f.key]}초</td>
+                  ))}
                 </tr>
               ))}
             </tbody>
@@ -97,32 +198,88 @@ export function SiteArea({ view, opsError, session, onChanged }: Props) {
   )
 }
 
+/**
+ * 실행 호스트가 적용한 현장 설정 버전(S3c JSON 계약 §3 판정 표). 운영 서비스의 최신 버전과 따로 보인다. 호스트는 1초마다
+ * 읽으므로 잠깐 다를 수 있다. 호스트가 닿지 않으면 모름이다. 읽기 실패와 범위 밖이라 적용하지 않은 버전은 적용 버전과 함께
+ * 나올 수 있어 따로 적는다.
+ */
+function HostApplied({ host }: { host: HostTimings | null }) {
+  if (host === null) return <p>실행 호스트 반영: 모름</p>
+  const applied = host.applied ?? null
+  const readError = host.readError ?? null
+  const rejected = host.rejected ?? null
+  return (
+    <>
+      <p>{applied === null ? '실행 호스트 반영: 미적용' : `실행 호스트 반영: 버전 ${applied.version}`}</p>
+      {readError !== null && <p>실행 호스트 읽기 실패: {readError}</p>}
+      {rejected !== null && (
+        <p>
+          실행 호스트가 적용하지 않은 버전 {rejected.version}(범위 밖): {rejected.reasons.join('; ')}
+        </p>
+      )}
+    </>
+  )
+}
+
+type Draft = Record<Field, string>
+
+const asDraft = (values: SiteSettingValues): Draft => ({
+  connectionThresholdSeconds: String(values.connectionThresholdSeconds),
+  evidenceBeforeSeconds: String(values.evidenceBeforeSeconds),
+  evidenceAfterSeconds: String(values.evidenceAfterSeconds),
+  inDoubtGraceSeconds: String(values.inDoubtGraceSeconds),
+  stallWindowSeconds: String(values.stallWindowSeconds),
+})
+
+type Base = SiteSettingsView['current']
+
 interface FormProps {
   view: SiteSettingsView
   busy: boolean
-  onChange: (baseVersion: number, seconds: number, reason: string) => void
+  /** [base] 는 고치기 시작한 버전의 행이고 [values] 는 폼의 값 다섯이다. */
+  onChange: (base: Base, values: SiteSettingValues, reason: string) => void
 }
 
 /**
- * 연결 기준 시간 변경 폼. 기준 버전은 고치기 시작한 순간의 버전으로 고정한다. 5초마다 다시 읽은 버전을 보낼 때 실으면,
- * 고치는 사이 다른 사람이 올린 버전을 모르고 덮어쓰게 된다(S2 스펙 §7). 범위 밖 값과 빈 사유는 보내지 않는다.
+ * 현장 설정 변경 폼. 값 다섯을 지금 값으로 채워 보이고, 보낼 때 다섯을 다 싣는다(S3c 스펙 §6.2). 기준 버전과 처음 값은 고치기
+ * 시작한 순간의 버전으로 고정한다. 5초마다 다시 읽은 버전을 보낼 때 실으면, 고치는 사이 다른 사람이 올린 버전을 모르고
+ * 덮어쓰게 된다(S2 스펙 §7). 범위 밖 값과 빈 사유는 보내지 않는다.
  */
 function SettingsForm({ view, busy, onChange }: FormProps) {
-  const [base, setBase] = useState<number | null>(null)
-  const [seconds, setSeconds] = useState('')
+  const [base, setBase] = useState<Base | null>(null)
+  const [draft, setDraft] = useState<Draft | null>(null)
   const [reason, setReason] = useState('')
   const [problem, setProblem] = useState<string | null>(null)
-  const { minConnectionThresholdSeconds: min, maxConnectionThresholdSeconds: max } = view.range
+  const shown = draft ?? asDraft(view.current)
 
-  const editing = () => {
-    if (base === null) setBase(view.current.version)
+  /** 처음 고치는 순간 기준 버전과 그 값 다섯을 잡는다. [key] 가 null 이면 사유다. */
+  const edit = (key: Field | null, value: string) => {
+    if (base === null) setBase(view.current)
+    if (key === null) {
+      if (draft === null) setDraft(shown)
+      setReason(value)
+    } else {
+      setDraft({ ...shown, [key]: value })
+    }
   }
 
   const submit = (event: FormEvent) => {
     event.preventDefault()
-    const value = Number(seconds)
-    if (!Number.isInteger(value) || value < min || value > max) {
-      setProblem(`연결 기준 시간은 ${min}~${max}초의 정수여야 합니다`)
+    const problems: string[] = []
+    const values = {} as SiteSettingValues
+    for (const f of FIELDS) {
+      const min = view.range[f.min]
+      const max = view.range[f.max]
+      const text = shown[f.key].trim()
+      const value = Number(text)
+      if (text === '' || !Number.isInteger(value) || value < min || value > max) {
+        problems.push(`${f.subject} ${min}~${max}초의 정수여야 합니다`)
+      } else {
+        values[f.key] = value
+      }
+    }
+    if (problems.length > 0) {
+      setProblem(problems.join('; '))
       return
     }
     if (reason.trim() === '') {
@@ -130,36 +287,38 @@ function SettingsForm({ view, busy, onChange }: FormProps) {
       return
     }
     setProblem(null)
-    onChange(base ?? view.current.version, value, reason.trim())
+    onChange(base ?? view.current, values, reason.trim())
     // 보낸 뒤에는 다음 변경을 새 기준 버전에서 시작한다. 거부되면 알림이 지금 버전을 보인다.
     setBase(null)
-    setSeconds('')
+    setDraft(null)
     setReason('')
   }
 
+  const input = (f: FieldSpec) => (
+    <div key={f.key}>
+      <label>
+        {f.name}(초)
+        <input type="number" value={shown[f.key]} onChange={(event) => edit(f.key, event.target.value)} />
+      </label>{' '}
+      <span>
+        허용 범위 {view.range[f.min]}~{view.range[f.max]}초
+      </span>
+    </div>
+  )
+
   return (
     <form aria-label="현장 설정 변경" onSubmit={submit}>
-      <p>기준 버전 {base ?? view.current.version}</p>
-      <label>
-        연결 기준 시간(초)
-        <input
-          type="number"
-          value={seconds}
-          onChange={(event) => {
-            editing()
-            setSeconds(event.target.value)
-          }}
-        />
-      </label>
+      <p>기준 버전 {(base ?? view.current).version}</p>
+      {input(CONNECTION)}
+      {GROUPS.map((group) => (
+        <fieldset key={group.title}>
+          <legend>{group.title}</legend>
+          {group.fields.map(input)}
+        </fieldset>
+      ))}
       <label>
         변경 사유
-        <input
-          value={reason}
-          onChange={(event) => {
-            editing()
-            setReason(event.target.value)
-          }}
-        />
+        <input value={reason} onChange={(event) => edit(null, event.target.value)} />
       </label>
       <button type="submit" disabled={busy}>
         변경
diff --git a/ui/src/testing/fakeOps.ts b/ui/src/testing/fakeOps.ts
index 0f0c193..095965e 100644
--- a/ui/src/testing/fakeOps.ts
+++ b/ui/src/testing/fakeOps.ts
@@ -8,6 +8,7 @@ import type {
   EligibilityView,
   ExecutionsView,
   Finding,
+  HostTimings,
   MissionOverview,
   MissionTemplates,
   MockRunView,
@@ -205,11 +206,18 @@ export function profileView(partial: Partial<ProfileListView> = {}): ProfileList
   }
 }
 
-/** 현장 설정. 기본은 마이그레이션이 넣는 버전 1 의 90초 하나다(S2 스펙 §5). */
+/**
+ * 현장 설정. 기본은 마이그레이션이 넣는 버전 1 하나(90초와 picasso 기본 시간값, S3c JSON 계약 §1.1)이고, 실행 호스트가 그
+ * 버전을 적용한 상태다.
+ */
 export function settingsView(partial: Partial<SiteSettingsView> = {}): SiteSettingsView {
   const first = {
     version: 1,
     connectionThresholdSeconds: 90,
+    evidenceBeforeSeconds: 30,
+    evidenceAfterSeconds: 15,
+    inDoubtGraceSeconds: 60,
+    stallWindowSeconds: 300,
     mode: 'ENGINEER',
     user: 'system',
     reason: 'S1 설정값 이전',
@@ -217,8 +225,32 @@ export function settingsView(partial: Partial<SiteSettingsView> = {}): SiteSetti
   }
   return {
     current: first,
-    range: { minConnectionThresholdSeconds: 60, maxConnectionThresholdSeconds: 3600 },
+    range: {
+      minConnectionThresholdSeconds: 60,
+      maxConnectionThresholdSeconds: 3600,
+      minEvidenceBeforeSeconds: 5,
+      maxEvidenceBeforeSeconds: 120,
+      minEvidenceAfterSeconds: 5,
+      maxEvidenceAfterSeconds: 120,
+      minInDoubtGraceSeconds: 10,
+      maxInDoubtGraceSeconds: 600,
+      minStallWindowSeconds: 30,
+      maxStallWindowSeconds: 3600,
+    },
     history: [first],
+    hostTimings: hostTimings(),
+    ...partial,
+  }
+}
+
+/** 실행 호스트의 적용 상태(S3c JSON 계약 §7). 기본은 버전 1 을 적용하고 읽기 실패와 적용하지 않은 버전이 없다. */
+export function hostTimings(partial: Partial<HostTimings> = {}): HostTimings {
+  return {
+    applied: { version: 1, evidenceBeforeSeconds: 30, evidenceAfterSeconds: 15, inDoubtGraceSeconds: 60, stallWindowSeconds: 300 },
+    appliedAt: 'h1',
+    lastReadAt: 'h2',
+    readError: null,
+    rejected: null,
     ...partial,
   }
 }
```

- [ ] **Step 2: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && cd ui && npm ci && npm test && npm run lint && npx tsc -b && npm run build
```
Expected: vitest 106 통과, lint·tsc·build 종료 0.

- [ ] **Step 3: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && git add ui/src/api.ts ui/src/components/SiteArea.test.tsx ui/src/components/SiteArea.tsx ui/src/testing/fakeOps.ts && git commit -F - <<'EOF'
feat(ui): 현장 설정 구역에 시간값 입력 넷과 실행 호스트 반영 표시

- 시간값 넷 입력에 범위 표시와 범위 밖 차단, 결과 판정 값과 정체 표시의 묶음 둘에 묶음마다 파급 설명
- PUT 은 값 다섯 전송, 결과 문구는 바뀐 칸만, 이력 행 끝에 시간값 넷
- 실행 호스트 반영 버전 표시(모름·미적용·읽기 실패·적용 안 한 버전 구분)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s3c-cmp.sh" ui/src/api.ts ui/src/components/SiteArea.test.tsx ui/src/components/SiteArea.tsx ui/src/testing/fakeOps.ts
```
Expected: 4개 모두 `같음`.

### Task 4: 통합 시험

**Files:**
- Create: `e2e/src/test/kotlin/dev/picasso/ops/e2e/SiteTimingsTest.kt`
- Modify: `e2e/build.gradle.kts`, `e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt`, `e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt`

- [ ] **Step 1: 새 파일 1개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && mkdir -p e2e/src/test/kotlin/dev/picasso/ops/e2e && cp "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/files/e2e/src/test/kotlin/dev/picasso/ops/e2e/SiteTimingsTest.kt" e2e/src/test/kotlin/dev/picasso/ops/e2e/SiteTimingsTest.kt
```

`e2e/src/test/kotlin/dev/picasso/ops/e2e/SiteTimingsTest.kt`:

```kotlin
package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.SiteTimings
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import dev.picasso.ops.host.timings.SiteTimingsReader
import dev.picasso.ops.host.timings.SiteTimingsView
import dev.picasso.ops.service.settings.SiteSettingsRange
import dev.picasso.registry.PostgresSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S3c 완료 판정의 통합 쪽(S3c 스펙 §3·§7.1·§10). 엔지니어가 운영 서비스 REST 로 시간값을 바꾸면 실행 호스트가 ops 의 현재 버전
 * 뷰를 읽어 다음 pump 부터 적용하고, 설비 대기 기한을 넘겨 봉인된 `SIGNAL_DEADLINE` 인시던트가 봉인 라운드의 설정 버전과 시간값을
 * 싣는다. 인시던트는 실행 호스트 `GET /host/incidents` 로 읽는다(S3c JSON 계약 §8).
 *
 * 순서가 있다. 앞 시험의 설정 버전과 실행을 뒤 시험이 이어받는다. 활성 임무 버전은 `ARRIVAL_WAIT` 템플릿 하나(버전 1)다.
 *
 * ## 시계
 *
 * 호스트의 읽기 주기는 실제 시간 1초이고([SiteTimingsReader.READ_PERIOD]), 기한은 현장 가상 시계로 잰다. 설정을 바꾼 뒤에는
 * 호스트가 그 버전을 적용했다고 보일 때까지 실제 시간으로만 기다리고([awaitApplied]), 그 뒤에야 가상 시계를 민다. 그래서 봉인
 * 라운드의 버전이 정해진다. 설비 대기의 기한 판정 자체는 임무 정의의 120초이고 시간값을 쓰지 않는다(스펙 §11). 이 시험이 증명하는
 * 것은 봉인 라운드의 버전과 값이 실리는 것까지다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SiteTimingsTest {

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

        /** 설비 대기 단위의 기한(`ARRIVAL_WAIT` 템플릿의 `deadlineSeconds`). */
        private val DEADLINE: Duration = Duration.ofSeconds(120)

        /** 호스트가 새 버전을 적용하기를 기다리는 상한(실제 시간). 읽기 주기 1초의 몇 배다. */
        private val APPLY_WAIT: Duration = Duration.ofSeconds(5)

        /** 시간값 넷(앞 폭, 뒤 폭, inDoubtGrace, stallWindow). 버전 1 은 picasso 기본값이다. */
        private val V1 = listOf(30L, 15L, 60L, 300L)
        private val V2 = listOf(40L, 20L, 90L, 600L)
        private val V3 = listOf(45L, 25L, 120L, 900L)
        private val FIELDS = listOf("evidenceBeforeSeconds", "evidenceAfterSeconds", "inDoubtGraceSeconds", "stallWindowSeconds")

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

        private fun rack(slots: List<String>): String =
            """{"workMasterId":"$WORK_MASTER","slots":[${slots.joinToString(",") { "\"$it\"" }}],""" +
                """"material":"$MATERIAL","presentation":"$PRESENTATION"}"""
    }

    @Test
    @Order(1)
    fun `기동 직후 현장 설정 버전 1 은 시간값 기본값이고 실행 호스트가 버전 1 을 적용했다`() {
        val view = stack.get("/api/site-settings")
        assertEquals(1L to 90, view["current"]["version"].asLong() to view["current"]["connectionThresholdSeconds"].asInt(), "$view")
        assertEquals(V1, values(view["current"]), "$view")

        val host = hostTimings()
        assertEquals(1L, host["applied"]["version"].asLong(), "$host")
        assertEquals(V1, values(host["applied"]), "$host")
        assertTrue(host["readError"].isNull && host["rejected"].isNull, "$host")
        // 운영 서비스가 호스트 적용 상태를 대신 읽어 화면에 싣는다(S3c JSON 계약 §3).
        assertEquals(1L, view["hostTimings"]["applied"]["version"].asLong(), "$view")
    }

    @Test
    @Order(2)
    fun `엔지니어가 시간값을 바꾸면 버전 2 이고 실행 호스트가 수 초 안에 버전 2 를 적용한다`() {
        val reply = change(1, V2, "정체 표시 늦춤")
        assertEquals(200 to "SUCCEEDED", reply.status to reply.body!!["result"].asText(), "${reply.body}")
        val view = stack.get("/api/site-settings")
        assertEquals(2L, view["current"]["version"].asLong(), "$view")
        assertEquals(V2, values(view["current"]), "$view")
        // 빠진 연결 기준 시간은 기준 버전의 값이다.
        assertEquals(90, view["current"]["connectionThresholdSeconds"].asInt(), "$view")

        val host = awaitApplied(2)
        assertEquals(V2, values(host["applied"]), "$host")
    }

    @Test
    @Order(3)
    fun `랙 도착 대기 버전으로 낸 작업 지시가 신호 없이 기한을 넘기면 SIGNAL_DEADLINE 인시던트가 설정 버전 2 와 그 값을 싣는다`() {
        activateArrivalWait()
        // 가상 시계를 밀기 전에 호스트의 적용 버전을 확인한다. 봉인 라운드의 버전이 2 로 정해진다.
        awaitApplied(2)

        val (jobOrderId, executionId) = submit(rack(FIRST_SLOTS))
        val waitFrom = stack.site.now()
        driver.push(ExecutionDriver.STEP)
        assertWaiting(executionId)

        // rack_present 를 켜지 않는다. 진행기가 기한 120초를 넘겨 실행이 ABORTED 로 정착한다.
        val settled = driver.drive(executionId)
        assertEquals("ABORTED" to 1, settled["physicalState"].asText() to settled["missionVersion"].asInt(), "$settled")

        val incidents = incidents()
        assertEquals(1, incidents["total"].asInt(), "$incidents")
        val incident = deadlineIncident(incidents, executionId)
        assertEquals(
            listOf(jobOrderId, HUMANOID, "rack-arrival", "SIGNAL"),
            listOf(incident["jobOrderId"].asText(), incident["robotId"].asText(), incident["unitId"].asText(), incident["route"].asText()),
            "$incident",
        )
        assertEquals(1, incident["missionVersion"].asInt(), "$incident")
        assertTrue(Duration.between(waitFrom, Instant.parse(incident["at"].asText())) >= DEADLINE, "$incident")
        assertEquals(2L, incident["siteSettingsVersion"].asLong(), "$incident")
        assertEquals(V2, values(incident), "$incident")
    }

    @Test
    @Order(4)
    fun `둘째 작업 지시가 기다리는 중에 버전 3 으로 바꾸면 기한을 넘긴 인시던트는 버전 3 을 든다`() {
        val (_, executionId) = submit(rack(SECOND_SLOTS))
        driver.push(ExecutionDriver.STEP)
        assertWaiting(executionId)

        // 기다리는 중에 바꾸고, 호스트가 적용했다고 보일 때까지 실제 시간으로만 기다린다. 가상 시계는 멈춰 있다.
        val reply = change(2, V3, "판정 유예 늘림")
        assertEquals("SUCCEEDED", reply.body!!["result"].asText(), "${reply.body}")
        awaitApplied(3)

        val settled = driver.drive(executionId)
        assertEquals("ABORTED", settled["physicalState"].asText(), "$settled")

        val incidents = incidents()
        assertEquals(2, incidents["total"].asInt(), "$incidents")
        val incident = deadlineIncident(incidents, executionId)
        assertEquals(incident, incidents["incidents"].first(), "최신부터: $incidents")
        assertEquals(3L, incident["siteSettingsVersion"].asLong(), "$incident")
        assertEquals(V3, values(incident), "$incident")
        // 앞 인시던트는 봉인 때의 버전 2 그대로다.
        val earlier = incidents["incidents"][1]
        assertEquals(2L to V2, earlier["siteSettingsVersion"].asLong() to values(earlier), "$earlier")
    }

    @Test
    @Order(5)
    fun `범위 밖 시간값은 칸 이름을 실은 SETTING_OUT_OF_RANGE 이고 운영 서비스의 범위 사본은 picasso 범위와 같다`() {
        val reply = stack.send(
            "PUT", "/api/site-settings", "engineer",
            body = """{"baseVersion":3,"inDoubtGraceSeconds":9,"stallWindowSeconds":3601,"reason":"범위 밖"}""",
        )
        assertEquals(400 to "SETTING_OUT_OF_RANGE", reply.status to reply.body!!["error"].asText(), "${reply.body}")
        assertEquals(
            "inDoubtGraceSeconds 9초는 범위 밖이다. 10~600초여야 한다; stallWindowSeconds 3601초는 범위 밖이다. 30~3600초여야 한다",
            reply.body["detail"].asText(),
        )
        assertEquals(3L, stack.get("/api/site-settings")["current"]["version"].asLong())

        // 주인은 picasso 다(스펙 T3). 운영 서비스 main 은 picasso 를 쓰지 못해 사본을 두고, 여기서 맞댄다(T6).
        val picasso = listOf(
            SiteSettingsRange.EVIDENCE_BEFORE to SiteTimings.EVIDENCE_WINDOW_BEFORE_SECONDS,
            SiteSettingsRange.EVIDENCE_AFTER to SiteTimings.EVIDENCE_WINDOW_AFTER_SECONDS,
            SiteSettingsRange.IN_DOUBT_GRACE to SiteTimings.IN_DOUBT_GRACE_SECONDS,
            SiteSettingsRange.STALL_WINDOW to SiteTimings.STALL_WINDOW_SECONDS,
        ).map { (field, range) -> field to (range.first to range.last) }
        val copy = SiteSettingsRange.FIELDS.filter { it.first != SiteSettingsRange.CONNECTION_THRESHOLD }
            .map { (field, range) -> field to (range.first.toLong() to range.last.toLong()) }
        assertEquals(picasso, copy)
        // 화면이 받는 range 칸도 같은 값이다.
        val range = stack.get("/api/site-settings")["range"]
        val served = picasso.map { (field, _) ->
            val name = field.replaceFirstChar(Char::uppercase)
            field to (range["min$name"].asLong() to range["max$name"].asLong())
        }
        assertEquals(picasso, served, "$range")
    }

    @Test
    @Order(6)
    fun `실제 현재 버전 뷰의 칸은 실행 호스트가 읽는 뷰 상수의 칸과 이름과 형이 같다`() {
        // 호스트 시험 세트는 이 상수로 대역 뷰를 만든다(S3c 스펙 §7.1). 실제 뷰와 갈라지면 대역 뷰 위의 호스트 시험이 헛돈다.
        val (schema, table) = SiteTimingsView.NAME.split(".")
        val actual = PostgresSupport.queryAll(
            "SELECT column_name, data_type FROM information_schema.columns " +
                "WHERE table_schema = '$schema' AND table_name = '$table' ORDER BY ordinal_position",
        ) { it.getString(1) to it.getString(2) }
        assertEquals(SiteTimingsView.COLUMNS.map { it.name to it.type }, actual)
    }

    /** 시간값 넷을 [FIELDS] 순서로 낸다. 운영 서비스 버전 행, 호스트 적용 값, 호스트 인시던트의 칸 이름이 같다. */
    private fun values(node: JsonNode): List<Long> = FIELDS.map { node[it].asLong() }

    private fun change(base: Long, values: List<Long>, reason: String): E2eStack.Reply {
        val body = json.createObjectNode().put("baseVersion", base).put("reason", reason)
        FIELDS.zip(values).forEach { (field, value) -> body.put(field, value) }
        return stack.send("PUT", "/api/site-settings", "engineer", body = body.toString())
    }

    private fun hostTimings(): JsonNode = E2eStack.read("${stack.hostUrl}/host/site-timings")

    private fun incidents(): JsonNode = E2eStack.read("${stack.hostUrl}/host/incidents")

    /** 실행 호스트가 [version] 을 적용했다고 보일 때까지 실제 시간으로 기다린다. 가상 시계는 밀지 않는다. */
    private fun awaitApplied(version: Long): JsonNode {
        val deadline = Instant.now().plus(APPLY_WAIT)
        var seen = hostTimings()
        while (seen["applied"].isNull || seen["applied"]["version"].asLong() != version) {
            check(Instant.now().isBefore(deadline)) { "실행 호스트가 ${APPLY_WAIT.seconds}초 안에 버전 $version 을 적용하지 않았다: $seen" }
            Thread.sleep(100)
            seen = hostTimings()
        }
        return seen
    }

    /** 실행의 첫 단위인 랙 도착 대기가 돌고 있다. */
    private fun assertWaiting(executionId: String) {
        val waiting = driver.execution(executionId)
        assertEquals("RUNNING", waiting["physicalState"].asText(), "$waiting")
        val first = waiting["units"].first()
        assertEquals(Triple("rack-arrival", "equipment_wait", "RUNNING"), Triple(first["unitId"].asText(), first["skillType"].asText(), first["state"].asText()), "$waiting")
    }

    private fun deadlineIncident(incidents: JsonNode, executionId: String): JsonNode =
        incidents["incidents"].single { it["executionId"].asText() == executionId && it["failureClass"].asText() == "SIGNAL_DEADLINE" }

    /** S3b 흐름으로 `ARRIVAL_WAIT` 템플릿을 초안 저장, 모의 실행, 활성화한다. 새 스택이라 버전 1 이다. */
    private fun activateArrivalWait() {
        val templates = stack.get("/api/missions/templates/$WORK_MASTER")["templates"]
            .associate { it["id"].asText() to it["definition"].asText() }
        val saved = engineer("$MISSIONS/drafts", json.createObjectNode().put("definition", templates.getValue("ARRIVAL_WAIT")).toString())
        val draftId = saved["outcome"]["draft"]["draftId"].asLong()
        val mocked = engineer("$MISSIONS/drafts/$draftId/mock-run", "{}")
        assertEquals("PASSED", mocked["outcome"]["result"].asText(), "$mocked")
        val activated = engineer("$MISSIONS/drafts/$draftId/activate", """{"reason":"랙 도착 대기 도입"}""")
        assertEquals("ACTIVATED" to 1, activated["outcome"]["result"].asText() to activated["outcome"]["version"].asInt(), "$activated")
    }

    private fun engineer(path: String, body: String): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        assertEquals(200, reply.status, "$path → ${reply.body}")
        return reply.body!!
    }

    /** 운영자 모드로 작업 지시를 내고 작업 지시 id 와 실행 id 를 낸다. 배정 기체는 `pick_place` 를 가진 humanoid-01 하나다. */
    private fun submit(form: String): Pair<String, String> {
        val reply = stack.send("POST", "/api/job-orders", "operator", body = form)
        assertEquals(200, reply.status, "${reply.body}")
        val outcome = reply.body!!["outcome"]
        assertEquals("ACCEPTED" to HUMANOID, outcome["result"].asText() to outcome["robotId"].asText(), "${reply.body}")
        return reply.body["jobOrderId"].asText() to outcome["executionId"].asText()
    }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/task4.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/task4.patch"
```

```diff
diff --git a/e2e/build.gradle.kts b/e2e/build.gradle.kts
index 600ef74..acf34de 100644
--- a/e2e/build.gradle.kts
+++ b/e2e/build.gradle.kts
@@ -4,6 +4,9 @@ dependencies {
     testImplementation(project(":mission-host"))
     testImplementation(project(":ops-service"))
     testImplementation(testFixtures("dev.picasso:registry"))
+    // 운영 서비스의 시간값 범위 사본을 picasso `SiteTimings` 의 범위와 맞댄다(S3c 스펙 §3 5단계, T6). mission-host 는 picasso 를
+    // implementation 으로 들어 e2e 의 컴파일 클래스패스에 내지 않는다.
+    testImplementation("dev.picasso:picasso")
     testImplementation(platform(libs.spring.boot.bom))
     testImplementation(libs.spring.boot.starter.web)
     testImplementation(libs.jackson.databind)
diff --git a/e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt b/e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt
index 4cc48ab..446a2a9 100644
--- a/e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt
+++ b/e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt
@@ -5,6 +5,7 @@ import com.fasterxml.jackson.databind.ObjectMapper
 import dev.picasso.ops.host.HostClock
 import dev.picasso.ops.host.MissionHostApplication
 import dev.picasso.ops.service.OpsApplication
+import dev.picasso.ops.service.store.OpsSchema
 import dev.picasso.ops.site.DbConfig
 import dev.picasso.ops.site.RobotRoster
 import dev.picasso.ops.site.Site
@@ -109,6 +110,9 @@ class E2eStack private constructor(
             PostgresSupport.reset()
             PostgresSupport.execute("DROP SCHEMA IF EXISTS ops CASCADE")
             PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
+            // 실행 호스트는 기동 안에서 ops 의 현장 시간값 뷰를 읽는다(S3c 스펙 §7.1). 호스트가 운영 서비스보다 먼저 뜨므로 ops 스키마를
+            // 먼저 올려 둔다. 그러지 않으면 호스트가 미적용으로 뜨고, 운영 서비스가 뜬 뒤 첫 주기 읽기까지 작업 지시를 받지 않는다.
+            OpsSchema.migrate(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
             val site = Site.start(
                 SiteConfig(
                     root = root,
diff --git a/e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt b/e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt
index 99c170f..c75f578 100644
--- a/e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt
+++ b/e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt
@@ -53,7 +53,7 @@ class SkeletonTest {
         val versions = PostgresSupport.queryAll(
             "SELECT version FROM ops.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
         ) { it.getString(1) }
-        assertEquals(listOf("1", "2", "3"), versions)
+        assertEquals(listOf("1", "2", "3", "4"), versions)
         assertEquals(0, stack.get("/api/operations").size())
     }
 
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && ./gradlew :e2e:test -q
```
Expected: e2e 50, 실패 0(`SiteTimingsTest` 6 포함, 그 클래스만 약 40~50초). 백그라운드로 돌린다. Docker 데몬이 떠 있어야 한다(Testcontainers).

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && git add e2e/src/test/kotlin/dev/picasso/ops/e2e/SiteTimingsTest.kt e2e/build.gradle.kts e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt && git commit -F - <<'EOF'
test(e2e): 현장 시간값 통합 시험 `SiteTimingsTest` 6개 추가

- 기동 직후 버전 1 호스트 적용, 버전 2 적용, 설비 대기 기한 인시던트의 버전 2 값, 대기 중 버전 3 변경 시 그 인시던트가 버전 3, 범위 밖 400 과 picasso 범위 사본 일치, 실제 뷰와 호스트 상수의 칸 대조
- 스택이 호스트 기동 전에 ops 마이그레이션 수행, `SkeletonTest` 마이그레이션 목록에 V4
- e2e 모듈에 picasso 시험 의존 추가

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 5: Playwright, README

**Files:**
- Modify: `README.md`, `ui/e2e/lifecycle.spec.ts`

- [ ] **Step 1: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/task5.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3c-patches/task5.patch"
```

````diff
diff --git a/README.md b/README.md
index c2d8f49..5bdea38 100644
--- a/README.md
+++ b/README.md
@@ -2,17 +2,17 @@
 
 picasso 를 라이브러리로 쓰는 담는 측 저장소입니다. 로봇, 임무, 엔드포인트의 운영 가능성을 PoC 로 입증합니다. 실제 하드웨어 현장은 없습니다. 보안과 인증은 생략합니다. 운영 중 변경은 코드 수정이 아니라 관리 화면에서 처리합니다.
 
-지금 단계는 S3b 임무 버전입니다. S3a 실행 호스트와 작업 지시 위에 올립니다. 서브모듈 `picasso` 는 P4 머지 커밋 `1e3f4ae` 를 가리킵니다. 그 버전에는 P2a 시험 실행기, P2b 리비전·바인딩 REST, P3 임무 정의 버전, P4 스트림 재부착과 mimic 엔진 잠금이 들어 있습니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 같은 영역 왼쪽에 프로파일 구역이 있습니다. 그 구역에서 엔지니어 모드는 기종 프로파일 리비전을 파일로 골라 제출하고, 시험을 요청하고, 활성화합니다. 시험 결과는 사람이 적지 않습니다. 런처가 띄운 시험 실행기가 적습니다. 기체 상세에서 엔지니어 모드는 빌드와 활성 리비전을 골라 바인딩하고, 사이트 명칭 등록을 기록합니다. 명칭 티칭은 화면 밖 현장 작업입니다. 기체 목록에 시운전 칸이 있습니다. 값은 완료, 미완, 퇴역입니다. 완료 조건은 셋입니다. 원장 상태가 `CONFIRMED` 이고 퇴역이 아니어야 합니다. 활성 바인딩이 있어야 합니다. 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 여야 합니다. 빠진 조건은 막힘으로 보입니다. 운영자 모드는 퇴역과 복귀를 합니다. 어댑터 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 현장·자원 영역에는 현장 설정 구역이 있습니다. 지금 버전, 연결 기준 시간, 허용 범위(60~3600초), 버전 이력을 보입니다. 엔지니어 모드는 연결 기준 시간을 바꿉니다. 바꿀 때는 사유가 필요합니다. 바꾸면 새 버전이 생깁니다. 다음 기체 목록 읽기부터 그 값으로 연결 칸과 막힘을 판정합니다. 재기동은 필요 없습니다. 운영자 모드는 바꾸지 못합니다. 막힘 카드는 어느 현장 설정 버전으로 판정했는지를 근거 버전으로 보입니다. 기체 상세에는 연결 판정 기준이 보입니다. 기준 초와 버전입니다. 운영 영역에서 운영자 모드는 작업 지시를 냅니다. 임무는 `InspectAsset`, `PrepareSequencedRack` 2개입니다. `InspectAsset` 은 점검 대상의 id 와 장소 이름을 넣습니다. `PrepareSequencedRack` 은 셀 대역의 슬롯과 자재를 고릅니다. 제시 자리는 자재에서 정해집니다. 폼을 채우면 기체별 배정 가능 표가 보입니다. 배정 가능 조건은 넷입니다. 시운전 완료, 연결 신선, 도는 실행 없음, 스킬 적합입니다. 앞의 둘은 운영 서비스가, 뒤의 둘은 실행 호스트가 판정합니다. 못 물어본 칸은 모름이고 모름이 있으면 배정 불가입니다. 운영 서비스는 배정 가능한 기체만 후보로 실행 호스트에 넘깁니다. 실행 호스트의 picasso 미들웨어가 후보 중 하나를 골라 mimic 기체에서 실행합니다. 엔지니어 모드는 작업 지시를 내지 못합니다. 실행 목록은 실행 상태, 단위 상태와 근거 등급, 작업 응답, 임무 버전을 보입니다. 임무 버전은 코드 정의면 `코드 정의`, 데이터 버전이면 `버전 N` 으로 보입니다. 셀 대역 표는 제시 자리와 슬롯의 점유와 자재를 보입니다. 같은 영역의 셀 대역 신호 표는 이름 있는 신호의 이름, 종류, 값, 관측 시각을 보입니다. 안전이 아닌 BOOLEAN 신호는 켜기와 끄기 버튼이 있습니다. 사람이 PLC 역할을 하는 정상 조작이라 운영자 모드와 엔지니어 모드 둘 다 합니다. 안전 신호는 값만 보입니다. 임무·정책 영역은 `PrepareSequencedRack` 의 활성 버전과 버전 이력, 초안과 그 마지막 모의 실행을 보입니다. 엔지니어 모드는 정의 JSON 을 편집기에서 고쳐 초안으로 저장하고, 검증하고, 모의 실행하고, 사유를 적어 활성화합니다. 시작용 정의 2개(데이터 정의, 랙 도착 대기)를 템플릿으로 불러올 수 있습니다. 초안은 자유롭게 저장되고 활성화만 관문입니다. 활성화는 지금의 신호 사양과 시운전 완료 기체의 스킬로 다시 검증하고, 그 초안의 마지막 모의 실행이 통과여야 합니다. 신호 사양에 없는 신호를 참조하는 것 같은 거부는 거부 카드로 보이고, 해결 담당과 바로 갈 작업이 함께 보입니다. 활성화한 버전은 다음 작업 지시부터 쓰이고, 도는 실행은 생성 때의 버전으로 끝납니다. 운영자 모드는 보기만 합니다. 미들웨어 시간값과 인시던트 기록의 현장 설정 버전은 S3c 의 몫입니다. S1 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. P2·S1d 설계 스펙은 `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` 에 있습니다. S2 설계 스펙은 `docs/superpowers/specs/2026-10-08-s2-site-settings-design.md` 에 있습니다. S3a 설계 스펙은 `docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md` 에 있습니다. S3b 설계 스펙은 `docs/superpowers/specs/2026-10-08-s3b-mission-versions-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.
+지금 단계는 S3c 현장 시간값입니다. S3b 임무 버전 위에 올립니다. 서브모듈 `picasso` 는 P5 머지 커밋을 가리킵니다. 그 버전에는 P2a 시험 실행기, P2b 리비전·바인딩 REST, P3 임무 정의 버전, P4 스트림 재부착과 mimic 엔진 잠금, P5 현장 시간값 주입과 인시던트의 현장 설정 버전이 들어 있습니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 같은 영역 왼쪽에 프로파일 구역이 있습니다. 그 구역에서 엔지니어 모드는 기종 프로파일 리비전을 파일로 골라 제출하고, 시험을 요청하고, 활성화합니다. 시험 결과는 사람이 적지 않습니다. 런처가 띄운 시험 실행기가 적습니다. 기체 상세에서 엔지니어 모드는 빌드와 활성 리비전을 골라 바인딩하고, 사이트 명칭 등록을 기록합니다. 명칭 티칭은 화면 밖 현장 작업입니다. 기체 목록에 시운전 칸이 있습니다. 값은 완료, 미완, 퇴역입니다. 완료 조건은 셋입니다. 원장 상태가 `CONFIRMED` 이고 퇴역이 아니어야 합니다. 활성 바인딩이 있어야 합니다. 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 여야 합니다. 빠진 조건은 막힘으로 보입니다. 운영자 모드는 퇴역과 복귀를 합니다. 어댑터 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 현장·자원 영역에는 현장 설정 구역이 있습니다. 지금 버전, 연결 기준 시간, 허용 범위(60~3600초), 버전 이력을 보입니다. 엔지니어 모드는 연결 기준 시간을 바꿉니다. 바꿀 때는 사유가 필요합니다. 바꾸면 새 버전이 생깁니다. 다음 기체 목록 읽기부터 그 값으로 연결 칸과 막힘을 판정합니다. 재기동은 필요 없습니다. 운영자 모드는 바꾸지 못합니다. 막힘 카드는 어느 현장 설정 버전으로 판정했는지를 근거 버전으로 보입니다. 같은 구역에 미들웨어 시간값 넷이 있습니다. 근거 윈도우 앞 폭(5~120초, 기본 30초), 근거 윈도우 뒤 폭(5~120초, 기본 15초), `inDoubtGrace`(10~600초, 기본 60초), `stallWindow`(30~3600초, 기본 300초)입니다. 앞의 셋은 결과 판정 값이고 `stallWindow` 는 정체 표시 시점만 바꿉니다. 화면은 두 묶음으로 나눠 묶음마다 무엇이 바뀌는지 적습니다. 엔지니어 모드만 바꿉니다. 값 하나만 바꿔도 현장 설정 세트 전체가 새 버전이고, 범위 밖 값은 화면이 막고 운영 서비스도 `SETTING_OUT_OF_RANGE` 로 거부합니다. 허용 범위의 주인은 picasso 이고 운영 서비스는 그 사본을 둡니다. 실행 호스트는 ops 스키마의 뷰 `ops.site_timings_current` 를 1초마다 읽어 다음 pump 부터 그 값으로 판정합니다. 화면은 운영 서비스의 최신 버전과 따로 «실행 호스트 반영: 버전 N» 을 보입니다. 호스트가 닿지 않으면 모름, 호스트가 아직 뷰를 읽지 못했으면 미적용입니다. 미적용 동안 실행 호스트는 작업 지시를 받지 않습니다. 배정 가능 판정이 기체마다 미적용 이유를 더하고 제출은 미배정입니다. 설비 대기가 기한을 넘겨 `SIGNAL_DEADLINE` 인시던트가 봉인되면 그 인시던트에 봉인한 라운드의 현장 설정 버전과 시간값 넷이 실립니다. 인시던트 화면은 아직 없고 실행 호스트 `GET /host/incidents` 로 읽습니다. 적용 상태는 `GET /host/site-timings` 입니다. 기체 상세에는 연결 판정 기준이 보입니다. 기준 초와 버전입니다. 운영 영역에서 운영자 모드는 작업 지시를 냅니다. 임무는 `InspectAsset`, `PrepareSequencedRack` 2개입니다. `InspectAsset` 은 점검 대상의 id 와 장소 이름을 넣습니다. `PrepareSequencedRack` 은 셀 대역의 슬롯과 자재를 고릅니다. 제시 자리는 자재에서 정해집니다. 폼을 채우면 기체별 배정 가능 표가 보입니다. 배정 가능 조건은 넷입니다. 시운전 완료, 연결 신선, 도는 실행 없음, 스킬 적합입니다. 앞의 둘은 운영 서비스가, 뒤의 둘은 실행 호스트가 판정합니다. 못 물어본 칸은 모름이고 모름이 있으면 배정 불가입니다. 운영 서비스는 배정 가능한 기체만 후보로 실행 호스트에 넘깁니다. 실행 호스트의 picasso 미들웨어가 후보 중 하나를 골라 mimic 기체에서 실행합니다. 엔지니어 모드는 작업 지시를 내지 못합니다. 실행 목록은 실행 상태, 단위 상태와 근거 등급, 작업 응답, 임무 버전을 보입니다. 임무 버전은 코드 정의면 `코드 정의`, 데이터 버전이면 `버전 N` 으로 보입니다. 셀 대역 표는 제시 자리와 슬롯의 점유와 자재를 보입니다. 같은 영역의 셀 대역 신호 표는 이름 있는 신호의 이름, 종류, 값, 관측 시각을 보입니다. 안전이 아닌 BOOLEAN 신호는 켜기와 끄기 버튼이 있습니다. 사람이 PLC 역할을 하는 정상 조작이라 운영자 모드와 엔지니어 모드 둘 다 합니다. 안전 신호는 값만 보입니다. 임무·정책 영역은 `PrepareSequencedRack` 의 활성 버전과 버전 이력, 초안과 그 마지막 모의 실행을 보입니다. 엔지니어 모드는 정의 JSON 을 편집기에서 고쳐 초안으로 저장하고, 검증하고, 모의 실행하고, 사유를 적어 활성화합니다. 시작용 정의 2개(데이터 정의, 랙 도착 대기)를 템플릿으로 불러올 수 있습니다. 초안은 자유롭게 저장되고 활성화만 관문입니다. 활성화는 지금의 신호 사양과 시운전 완료 기체의 스킬로 다시 검증하고, 그 초안의 마지막 모의 실행이 통과여야 합니다. 신호 사양에 없는 신호를 참조하는 것 같은 거부는 거부 카드로 보이고, 해결 담당과 바로 갈 작업이 함께 보입니다. 활성화한 버전은 다음 작업 지시부터 쓰이고, 도는 실행은 생성 때의 버전으로 끝납니다. 운영자 모드는 보기만 합니다. 인시던트 화면, 보류 해소, 장애 주입은 S4 의 몫입니다. S1 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. P2·S1d 설계 스펙은 `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` 에 있습니다. S2 설계 스펙은 `docs/superpowers/specs/2026-10-08-s2-site-settings-design.md` 에 있습니다. S3a 설계 스펙은 `docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md` 에 있습니다. S3b 설계 스펙은 `docs/superpowers/specs/2026-10-08-s3b-mission-versions-design.md` 에 있습니다. S3c 설계 스펙은 `docs/superpowers/specs/2026-10-09-s3c-site-timings-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.
 
 ## 구성
 
 - `.env`: 사이트 id(`SITE_ID`), DB 접속값, 포트, 토큰을 둡니다. 로컬 PoC 값입니다. 런처, 실행 호스트, 운영 서비스, 시험, CI 가 이 파일 하나를 읽습니다.
 - `picasso/`: picasso git 서브모듈입니다. 고정 커밋을 가리킵니다. Gradle includeBuild 로 가져옵니다. 읽기 전용입니다. picasso 쪽 변경은 picasso 저장소의 PR 로 냅니다.
 - `site/`: 가짜 현장 런처입니다(Kotlin). registry, mimic 기체, 리비전 시험 실행기(`site-runner`), 셀 대역을 한 프로세스에서 띄웁니다. 기동 직후 mimic 가상 시계를 실제 시각까지 한 번 밉니다. 그 뒤 실제 1초마다 가상 시계를 실제 시각까지 따라잡게 밉니다. 그래서 가상 시각은 실제 시각보다 앞서지 않고 차이는 1초 이내입니다. mimic gRPC 는 `.env` 의 `MIMIC_GRPC_PORT` 에 엽니다. 실행 호스트가 이 포트에 붙습니다. 셀 대역은 셀 신호를 자동으로 내는 대역입니다. 제시 자리 `SEQ-IN-02.BIN-A` 는 늘 `ENGINE-COVER-A` 를 듭니다. 슬롯 `RACK-204.S01`~`S04` 는 처음에 비어 있습니다. 기체가 `pick_place` 를 성공하면 그 슬롯을 제시 자리의 자재로 채웁니다. 슬롯은 비우지 않습니다. 기체가 보고한 배치에서 채우므로 독립 설비 확인이 아닙니다. 셀 대역은 이름 있는 신호 3개(`rack_present`, 안전 신호 `guard_closed`, `lot_code`)도 냅니다. 신호마다 사양(종류, 자리, 안전 여부)과 값을 함께 선언하고, 실행 호스트는 이 선언을 임무 정의 검증의 신호 사양으로 씁니다. 신호 값은 시계를 밀어도 바뀌지 않고 `POST /cell/signals/{name}` 으로만 바뀝니다. 안전 신호는 쓰기를 거부합니다. 재기동하면 처음 값으로 돌아갑니다. 셀 대역은 `.env` 의 `SITE_CELL_PORT` 에 루프백 `GET /cell` 과 `POST /cell/signals/{name}` 으로 냅니다. 실행기는 실제 1초마다 registry 에서 시험 요청을 집습니다. 실행기는 시험마다 자기 mimic 을 따로 띄웁니다. 그래서 현장 기체의 보고와 상태를 바꾸지 않습니다. 적재 토큰은 런처만 가집니다. 그래서 시험 결과를 적을 수 있는 것도 런처뿐입니다. 기체 명부는 `site/robots.json` 입니다. 기체 명부의 `site_names` 는 현장에서 기체에 티칭한 명칭입니다. 런처가 기동 때 기체에 넣습니다. Postgres 용 compose 는 `site/compose.yaml` 입니다. 손 기동 확인은 `site/smoke.sh` 입니다.
-- `mission-host/`: 실행 호스트입니다(Kotlin, Spring Boot). picasso 미들웨어를 세워 mimic 기체에서 작업 지시를 실행합니다. 250ms 마다 셀 대역을 읽고 미들웨어를 pump 합니다. 임무 버전(초안, 모의 실행, 버전)은 같은 Postgres 의 자기 스키마 `mission` 에 덧붙이기 전용으로 저장합니다. 재기동하면 활성 버전은 그대로이고 실행은 사라집니다. 모의 실행은 호스트 안에 별도 mimic 기체와 가상 시계를 띄워 초안 정의로 표본 작업 지시 하나를 끝까지 돌립니다. 현장 기체, 셀 대역, registry 에는 닿지 않습니다. 모의 실행 기체 프로파일(`mission-host/mock-run/humanoid-a.json`)과 프로파일 스키마 경로는 작업 디렉터리 기준이므로 저장소 루트에서 띄웁니다. 토큰을 받지 않습니다. `.env` 의 `HOST_PORT` 에 루프백으로 엽니다. 호출자는 운영 서비스뿐입니다.
+- `mission-host/`: 실행 호스트입니다(Kotlin, Spring Boot). picasso 미들웨어를 세워 mimic 기체에서 작업 지시를 실행합니다. 250ms 마다 셀 대역을 읽고 미들웨어를 pump 합니다. 임무 버전(초안, 모의 실행, 버전)은 같은 Postgres 의 자기 스키마 `mission` 에 덧붙이기 전용으로 저장합니다. 재기동하면 활성 버전은 그대로이고 실행과 인시던트는 사라집니다. 현장 시간값은 운영 서비스가 만든 뷰 `ops.site_timings_current` 를 1초마다 읽기 전용으로 읽습니다. 표는 읽지 않습니다. 뷰가 아직 없으면 미적용으로 뜨고 다시 읽습니다. 모의 실행은 호스트 안에 별도 mimic 기체와 가상 시계를 띄워 초안 정의로 표본 작업 지시 하나를 끝까지 돌립니다. 현장 기체, 셀 대역, registry 에는 닿지 않습니다. 모의 실행 기체 프로파일(`mission-host/mock-run/humanoid-a.json`)과 프로파일 스키마 경로는 작업 디렉터리 기준이므로 저장소 루트에서 띄웁니다. 토큰을 받지 않습니다. `.env` 의 `HOST_PORT` 에 루프백으로 엽니다. 호출자는 운영 서비스뿐입니다.
 - `ops-service/`: 운영 서비스입니다(Kotlin, Spring Boot). 화면의 유일한 백엔드입니다. 운영자 토큰을 쥡니다. registry REST 와 실행 호스트 REST 를 부릅니다. picasso 모듈을 쓰지 않습니다.
 - `ui/`: 관리 화면입니다(React, Vite, TypeScript). 운영 서비스만 부릅니다.
-- `e2e/`: 통합 시험입니다. 한 JVM 에 Postgres, registry, mimic, 실행 호스트, 운영 서비스를 띄웁니다. 실행 호스트의 시계는 현장 가상 시계이고 시험이 시계를 직접 밉니다. 임무 버전 시험은 실행 호스트만 같은 포트로 다시 띄워 활성 버전이 남는지 봅니다.
+- `e2e/`: 통합 시험입니다. 한 JVM 에 Postgres, registry, mimic, 실행 호스트, 운영 서비스를 띄웁니다. 실행 호스트의 시계는 현장 가상 시계이고 시험이 시계를 직접 밉니다. 임무 버전 시험은 실행 호스트만 같은 포트로 다시 띄워 활성 버전이 남는지 봅니다. 스택은 실행 호스트를 띄우기 전에 ops 스키마 마이그레이션을 먼저 돌립니다. 그래서 실행 호스트는 현장 설정 버전 1 을 적용한 채로 뜹니다.
 - `docs/`: 설계 스펙과 구현 계획입니다.
 
 ## 선행 도구
@@ -73,7 +73,7 @@ cd ui && npx playwright install chromium
 cd ui && npx playwright test
 ```
 
-Playwright 가 Postgres, 런처, 실행 호스트, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 이어서 리비전 제출, 시험 요청, 실행기의 `TESTED` 기록, 활성화, 바인딩, 명칭 기록, `humanoid-01` 시운전 완료까지 돕니다. `quadruped-01` 의 명칭 기록이 기체가 아는 명칭 없음으로 막히는 것도 봅니다. 현장·자원 영역에서 연결 기준 시간을 120초로 바꿔 버전 2 와 이력 행을 봅니다. 운영자 모드에서는 바꾸지 못하는 것과 기체 상세의 연결 판정 기준이 버전 2 로 바뀐 것도 봅니다. 운영 영역에서 운영자 모드로 `InspectAsset` 작업 지시를 냅니다. 시운전 완료인 `humanoid-01` 만 배정 가능이고 배정됩니다. 실행 목록에 `코드 정의` 행이 보이고 실행이 `PHYSICALLY_DONE` 으로 끝납니다. 임무·정책 영역에서 엔지니어 모드로 데이터 정의 템플릿을 불러와 초안 저장, 검증, 모의 실행, 활성화를 하고 버전 이력에 `버전 1 (활성)` 을 봅니다. 운영 영역의 셀 대역 신호 표에서 `rack_present` 를 켜고 신호 조작 결과와 바뀐 값을 봅니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 시각을 따라갑니다. 시험은 약 3분 걸립니다. 로컬 실측은 2.5분입니다. Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.
+Playwright 가 Postgres, 런처, 실행 호스트, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 이어서 리비전 제출, 시험 요청, 실행기의 `TESTED` 기록, 활성화, 바인딩, 명칭 기록, `humanoid-01` 시운전 완료까지 돕니다. `quadruped-01` 의 명칭 기록이 기체가 아는 명칭 없음으로 막히는 것도 봅니다. 현장·자원 영역에서 연결 기준 시간을 120초로 바꿔 버전 2 와 이력 행을 봅니다. 운영자 모드에서는 바꾸지 못하는 것과 기체 상세의 연결 판정 기준이 버전 2 로 바뀐 것도 봅니다. 이어서 엔지니어 모드로 `stallWindow` 를 600초로 바꿔 버전 3 이력 행과 «실행 호스트 반영: 버전 3» 을 봅니다. 운영 영역에서 운영자 모드로 `InspectAsset` 작업 지시를 냅니다. 시운전 완료인 `humanoid-01` 만 배정 가능이고 배정됩니다. 실행 목록에 `코드 정의` 행이 보이고 실행이 `PHYSICALLY_DONE` 으로 끝납니다. 임무·정책 영역에서 엔지니어 모드로 데이터 정의 템플릿을 불러와 초안 저장, 검증, 모의 실행, 활성화를 하고 버전 이력에 `버전 1 (활성)` 을 봅니다. 운영 영역의 셀 대역 신호 표에서 `rack_present` 를 켜고 신호 조작 결과와 바뀐 값을 봅니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 시각을 따라갑니다. 시험은 약 3분 걸립니다. 로컬 실측은 2.7분입니다. Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.
 
 ## 띄우기
 
@@ -111,7 +111,7 @@ site/build/install/site/bin/site
 env -u PICASSO_INGEST_TOKEN -u PICASSO_OPERATOR_TOKEN mission-host/build/install/mission-host/bin/mission-host
 ```
 
-터미널 3에서 운영 서비스를 띄웁니다.
+터미널 3에서 운영 서비스를 띄웁니다. 실행 호스트는 운영 서비스가 ops 스키마를 올리기 전까지 미적용이고 작업 지시를 받지 않습니다. 운영 서비스가 뜨면 1초 안에 현장 설정을 적용합니다.
 
 ```bash
 env -u PICASSO_INGEST_TOKEN ops-service/build/install/ops-service/bin/ops-service
@@ -136,6 +136,13 @@ Gradle 의 run 작업 2개를 한 작업 트리에서 겹쳐 띄우지 않습니
 - 신호 사양은 셀 대역 픽스처의 코드 상수입니다. 화면에서 편집하지 못하고 버전도 없습니다. 신호는 값, 관측 시각, 모름만 보이고 신호 품질(응답 없음, 단절, 불확실)은 보이지 않습니다.
 - 임무 편집은 `PrepareSequencedRack` 하나만 합니다. 새 WorkMaster 는 만들지 못합니다. 편집기는 textarea 이고 편집기용 JSON Schema 파일은 없습니다. 정의의 옳고 그름은 실행 호스트가 검증합니다.
 - 모의 실행은 이상적 현장을 가정합니다. 목적지 슬롯은 처음부터 기대 자재를 들고, 이름 있는 신호는 대기 노드의 기대 값을 내고, 기체 실패 모드가 없습니다. 정의가 끝까지 도는지를 볼 뿐 현장 사실을 보증하지 않습니다. 요청 안에서 동기로 돌며 실제 시간 상한은 30초입니다. 활성화는 그 초안의 마지막 모의 실행만 보고, 앞 버전들의 모의 실행을 다시 돌리지 않습니다.
+- 현장 시간값은 현장 전체 한 값입니다. 임무별로 덮어쓰지 못합니다.
+- 실행 호스트는 ops 스키마의 뷰 `ops.site_timings_current` 에 결합합니다. 운영 서비스가 뷰를 바꾸면 실행 호스트도 바꿔야 합니다. 통합 시험이 실제 뷰의 칸과 실행 호스트의 뷰 상수를 대조합니다.
+- 읽기 주기 1초 동안은 운영 서비스의 최신 버전과 실행 호스트의 적용 버전이 다를 수 있습니다. 화면이 둘을 따로 보입니다. 실행 호스트가 뷰를 읽지 못하면 마지막으로 적용한 버전을 계속 쓰고 읽기 실패를 보입니다. 뷰의 값이 picasso 범위 밖이면 적용하지 않고 그 버전과 이유를 보입니다.
+- 근거 기한은 단위가 완료된 순간의 뒤 폭으로 정해집니다. 그 뒤에 바꾼 뒤 폭은 이미 완료된 단위에 미치지 않습니다. 인시던트에 실리는 값은 봉인한 라운드의 값이라 근거 기한을 정한 값과 다를 수 있습니다.
+- 설비 대기의 기한 판정은 임무 정의의 `deadlineSeconds` 로 하고 시간값을 쓰지 않습니다. 시간값이 판정을 바꾸는 것은 picasso 시험이 맡고, 통합 시험은 인시던트에 버전과 값이 실리는 것까지 봅니다.
+- 모의 실행은 현장 시간값을 쓰지 않고 케이퍼빌리티 기본값으로 돕니다. 임무 검증 결과의 `basisVersion` 은 늘 비어 있습니다.
+- 인시던트는 실행 호스트 메모리에만 있습니다. 재기동하면 비어 다시 시작합니다. 새 칸(현장 설정 버전, `inDoubtGrace`, `stallWindow`)은 picasso 내보내기에 싣지 않습니다.
 - 바닥 소유는 검사하지 않습니다. 활성화 권한은 모드 검사뿐이고 명부가 없습니다.
 - 임무 버전의 표 3개는 덧붙이기 전용입니다(트리거가 UPDATE, DELETE, TRUNCATE 를 막습니다). 지우려면 스키마째 지웁니다. 화면 시험은 시작할 때 compose 볼륨째 내리므로 매번 빈 DB 에서 시작합니다.
 - 선언 전 mimic 의 생존 보고는 registry 가 거부합니다. 거부한 보고는 어디에도 남지 않습니다. 기체를 선언하면 보고가 붙습니다.
diff --git a/ui/e2e/lifecycle.spec.ts b/ui/e2e/lifecycle.spec.ts
index 198ef80..78ea2d3 100644
--- a/ui/e2e/lifecycle.spec.ts
+++ b/ui/e2e/lifecycle.spec.ts
@@ -8,7 +8,7 @@ import { fileURLToPath } from 'node:url'
  * 기체는 site/robots.json 의 humanoid-01 이다. 런처가 mimic 을 띄워 두었으므로 선언하면 보고가 붙는다.
  *
  * 이어서 S1c 의 화면 쪽(스펙 §3). 제품 선언 → 빌드 선언 → 인스턴스 등록 → 인스턴스 목록에 UNTESTED.
- * 이어서 S1d 의 화면 쪽(P2·S1d 스펙 §3·§11). 개정판 제출(파일 고르기) → 시험 요청 → 현장 실행기가 TESTED → 활성화 →
+ * 이어서 S1d 의 화면 쪽(P2·S1d 스펙 §3·§11). 리비전 제출(파일 고르기) → 시험 요청 → 현장 실행기가 TESTED → 활성화 →
  * 바인딩 → 명칭 기록 → «시운전 완료»(humanoid-01). quadruped-01 은 명칭을 티칭하지 않아 «기체가 아는 명칭 없음» 으로 막힌다.
  * 이어서 S2 의 화면 쪽(S2 스펙 §3). 현장·자원 영역에서 연결 기준 시간을 바꾸면 버전 2 와 이력 행이 보이고, 운영자 모드는
  * 바꾸지 못하며, 기체 상세가 버전 2 의 기준으로 판정한다.
@@ -17,6 +17,8 @@ import { fileURLToPath } from 'node:url'
  * 이어서 S3b 의 화면 쪽(S3b 스펙 §3). 엔지니어 모드로 «임무·정책» 영역에서 데이터 정의 템플릿을 불러와 초안 저장 → 검증 → 모의
  * 실행 → 활성화(사유)하면 버전 이력에 «버전 1 (활성)» 이 보인다. 운영 영역의 셀 대역 신호 표에서 rack_present 를 켜면 신호 조작
  * 결과와 신호 값이 보인다.
+ * S3c 의 화면 쪽(S3c 스펙 §3)은 S2 단계 뒤다. 엔지니어 모드로 stallWindow 를 바꾸면 버전 3 이력 행이 보이고 «실행 호스트 반영»
+ * 이 버전 3 이 된다.
  * registry 를 멈추는 것은 맨 끝이다. 그 뒤로는 조작이 registry 에 닿지 않는다.
  *
  * 선언 직후의 CLAIMED 는 여기서 단언하지 않는다. 실시간 1:1 시계에서는 다음 보고가 1초 안에 올 수도 있어
@@ -83,7 +85,7 @@ test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 reg
   const instances = page.getByRole('table', { name: '인스턴스 목록' })
   await expect(instances.getByRole('row', { name: /fleet-gw-01/ })).toContainText('UNTESTED')
 
-  // 프로파일: 개정판 둘을 제출하고 시험을 요청하면 현장 실행기가 TESTED 로 올린다. 그 뒤 활성화.
+  // 프로파일: 리비전 둘을 제출하고 시험을 요청하면 현장 실행기가 TESTED 로 올린다. 그 뒤 활성화.
   const declareQuadruped = page.getByRole('form', { name: '기체 선언' })
   await declareQuadruped.getByLabel('robot_id').fill('quadruped-01')
   await declareQuadruped.getByLabel('일련번호').fill('QB-0001')
@@ -149,6 +151,20 @@ test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 reg
   await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
   await expect(detail.getByText('기준 120초, 현장 설정 버전 2', { exact: true })).toBeVisible()
 
+  // 현장 시간값(S3c 스펙 §3). 엔지니어 모드에서 stallWindow 를 바꾸면 버전 3 과 시간값 열이 붙은 이력 행이 보이고, 실행 호스트가
+  // ops 의 현재 버전 뷰를 읽어 적용한 버전이 따로 보인다. 실행 호스트는 운영 서비스보다 먼저 떠 미적용으로 시작하고 운영 서비스가
+  // 마이그레이션을 마친 뒤 적용하므로 여기서는 이미 버전 2 다. 화면은 5초마다 다시 읽고 호스트는 1초마다 읽는다.
+  const hostApplied = { timeout: 15_000 }
+  await page.getByRole('button', { name: '현장·자원' }).click()
+  await expect(settings.getByText('실행 호스트 반영: 버전 2', { exact: true })).toBeVisible(hostApplied)
+  await change.getByLabel('stallWindow(초)').fill('600')
+  await change.getByLabel('변경 사유').fill('정체 표시 늦춤')
+  await change.getByRole('button', { name: '변경' }).click()
+  await expect(page.getByText('stallWindow 600초로 변경: 반영됨', { exact: true })).toBeVisible()
+  await expect(versions.getByRole('row', { name: /^3 120초 local 엔지니어 정체 표시 늦춤 .* 30초 15초 60초 600초$/ })).toBeVisible()
+  await expect(versions.getByRole('row', { name: /^2 120초 local 엔지니어 연결 기준 늘림 .* 30초 15초 60초 300초$/ })).toBeVisible()
+  await expect(settings.getByText('실행 호스트 반영: 버전 3', { exact: true })).toBeVisible(hostApplied)
+
   // 운영(S3a 스펙 §3). 시운전 완료는 humanoid-01 하나다. quadruped-01 은 명칭 막힘으로 시운전 미완이라 배정 불가다.
   await page.getByLabel('운영자').check()
   await page.getByRole('button', { name: '운영', exact: true }).click()
````

- [ ] **Step 2: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && ./gradlew :site:installDist :ops-service:installDist :mission-host:installDist -q && cd ui && npx playwright test
```
Expected: Playwright 1 통과(약 2.8분). 백그라운드로 돌린다.

- [ ] **Step 3: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" && git add README.md ui/e2e/lifecycle.spec.ts && git commit -F - <<'EOF'
test(ui): Playwright 생애주기에 `stallWindow` 변경 단계 추가

- 엔지니어 모드의 `stallWindow` 변경과 실행 호스트 반영 버전 확인 단계
- 시험 주석의 옛 용어 정정
- README 에 S3c 현장 시간값 단계와 한계 기재

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s3c-cmp.sh" e2e/src/test/kotlin/dev/picasso/ops/e2e/SiteTimingsTest.kt e2e/build.gradle.kts e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt README.md ui/e2e/lifecycle.spec.ts
```
Expected: 6개 모두 `같음`.

## Chunk 2: 검증과 병합(컨트롤러)

### Task 6: 결함 주입, 새 클론 빌드, PR

- [ ] **Step 1: 트리 대조**

```bash
git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" diff --stat origin/main HEAD -- . ':!docs' | tail -1 && git -C "C:/Users/Eisen/AppData/Local/Temp/s3c" diff --stat b2cce34 HEAD | tail -1
```
Expected: 두 줄의 파일 수와 줄 수가 같다(서브모듈 포인터 포함 30개 파일). 그리고 워크트리 `HEAD` 의 코드 경로마다 스파이크 `HEAD` 와 `s3c-cmp.sh` 로 `같음`.

- [ ] **Step 2: 결함 주입 41건**

```bash
cd "C:/Users/Eisen/AppData/Local/Temp/s3c-inject" && S3C_ROOT="C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3c" PYTHONUTF8=1 python inject.py
```
Expected: 모두 `탐지`. 백그라운드로 돌린다(약 25분). 같은 워크트리에서 다른 Gradle·npm 을 겹쳐 돌리지 않는다. 주입 목록(운영 서비스 O1~O10, 호스트 H1·H3~H14, 화면 U1~U12, 통합 I1~I6)과 잡는 시험 이름은 실행기에 있다. H2 는 등가 변이라 스파이크에서 코드를 뺐다.

- [ ] **Step 3: 새 클론 전체 빌드와 Playwright**

워크트리 브랜치를 스크래치에 새로 클론해(서브모듈 포함) `./gradlew build`, ui 의 `npm ci && npm test && npm run lint && npx tsc -b && npm run build`, installDist 셋과 `npx playwright test` 를 백그라운드로 차례로 돌린다(결함 주입과 겹치지 않는다). Expected: site 32, mission-host 57, ops-service 209, e2e 50, vitest 106, Playwright 1, 실패 0.

- [ ] **Step 4: 실행 결과와 PR**

P5 계획과 이 계획 끝에 «실행 결과» 절을 더한다(문장은 Codex·Fable 취합). 코드 커밋은 묶음별로 남긴다(스택 보존, 스쿼시 안 함). 푸시, PR, CI 를 한 번 확인한 뒤 `gh pr merge --merge`(`--delete-branch` 쓰지 않음).

## 실행 결과

- 수행: picasso-ops 워크트리에서 묶음 셋(Task 1·2, Task 3, Task 4·5)을 하위 에이전트(Sonnet)가 수행, 블록은 계획에서 기계로 뽑아 둔 파일을 복사·적용, 묶음마다 커밋된 파일을 스파이크와 바이트 대조해 29개 모두 같음(19, 4, 6), 워크트리와 스파이크의 코드 diff 가 같음(서브모듈 포함 30개 파일, +2,042/-154), Expected 와 다른 곳 없음
- 결함 주입: 41건(운영 서비스 10, 호스트 13, 화면 12, 통합 6) 모두 지정 시험이 탐지, 스파이크와 워크트리에서 두 번 실행
- 새 클론 빌드: Gradle 시험 348 실패 0(site 32, mission-host 57, ops-service 209, e2e 50), vitest 106, lint·tsc·build 통과, installDist 셋과 Playwright 1 통과(2.8분)
- 병합: 코드 커밋을 묶음별로 남겨(스택 보존, 스쿼시 없음) picasso-ops PR #12 로 올림
- 스파이크: 영역 셋(운영 서비스·실행 호스트, 화면, 통합 시험·Playwright·README)을 하위 에이전트가 차례로 지음, JSON 계약 문서를 다음 영역의 입력으로 넘김, 스펙 검토 2회(1차에서 막는 것 둘: 기동 순서, 호스트 시험 세트의 ops 스키마), 계획 검토(1회)가 잡은 것은 화면의 기준 버전 고정 시점 시험(주입 U12), 읽기 작업의 `Throwable` 처리(H12), 읽기 주기 하한(H14), 인시던트 목록 limit 생략 경로(H13), 주입 O2 를 값 단언으로 바꾼 것
- 걸린 것: 미적용 동안 제출을 따로 끊는 코드가 등가 변이라(주입 H2) 뺌, 스파이크 전체 주입에서 기동 안 첫 읽기 주입(H5)을 놓침(기동이 1초를 넘기면 주기 읽기가 먼저 돌아 시험이 우연히 통과)을 읽기 주기를 설정 키로 빼서 그 시험만 긴 주기로 돌려 고침, 스펙 §9 의 거부 표현을 `UNASSIGNED` 로 정정, `SkeletonTest` 의 마이그레이션 목록이 스펙의 바뀌는 시험 목록에서 빠져 있었음, 계획 재현 도구가 새 파일을 CRLF 로 써서 LF 저장소 대조가 어긋나 고침
- 다음: S4(장애 주입, 인시던트 화면, 보류 해소), 그 뒤 운반 임무(AMR 플릿 연동) 후보
