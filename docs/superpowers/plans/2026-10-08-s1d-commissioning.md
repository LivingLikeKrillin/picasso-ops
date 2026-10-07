# S1d 바인딩·명칭·시운전 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** picasso-ops 에서 엔지니어가 화면으로 개정판을 제출하고, 현장의 실행기가 시험해 `TESTED` 로 올리고, 활성화·바인딩·명칭 기록을 거쳐 기체가 «시운전 완료» 가 되는 흐름을 코드 수정 없이 돈다. 명칭을 티칭하지 않은 기체는 `SITE_NAMES_CONTRADICTED` 로 막히고, 현장에서 다시 티칭하면 풀린다.

**Architecture:** 서브모듈을 P2b 머지 커밋(`41beedb`)으로 옮긴다. `site/` 런처가 개정판 시험 실행기(`site-runner`, picasso `harness`)를 같은 프로세스에서 띄우고 `robots.json` 의 `site_names` 를 기체에 넣는다(`Site.teach` 로 재티칭). 운영 서비스는 registry 읽기 4개(`/operations/skill-types`, `/operations/profile-revisions`, `/diag/bindings`, `/diag/software`)와 조작 5개(제출, 시험 요청, 활성화, 바인딩, 명칭 기록)를 더하고, 기체 목록이 기체 → 바인딩 → 소프트웨어 대조를 셋 다 읽혀야 갱신하며, 시운전 판정(`CommissioningJudge`)과 새 막힘 4종을 계산한다. 화면은 «프로파일» 구역, 기체 목록의 «시운전» 칸, 기체 상세의 카드 3개(바인딩, 사이트 명칭, 시운전)를 더한다.

**Tech Stack:** Kotlin 2.4.20, Spring Boot 3.4.0, JUnit5 + kotlin.test, Testcontainers(picasso registry `testFixtures` 의 `PostgresSupport`), JDK `HttpServer`(registry 대역), React 19 + Vite 8 + TypeScript, vitest 5 + Testing Library, Playwright 1.63.

**근거 스펙:** `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` §7(가짜 현장), §8(운영 서비스), §9(화면), §10(오류 처리), §11(시험), §3(S1d 완료 판정). 2차 스펙 검토 권고 중 S1d 몫(시험 요청 상태의 «없음», 명칭 기록 재조회, 시험 요청 판정의 두 시계)도 이 계획이 정한다.

**스펙이 계획에 맡긴 것과 이 계획이 정한 것:**
- 시험 요청 상태는 4값(대기, 실행 중, 만료, 끝남)에 «요청 없음» 을 더한 5값이다(검토 권고 7). 만료 시각과 같은 시각은 이미 만료다. registry 가 그 순간부터 다른 실행기에 다시 집어 준다(`claim_expires_at <= now`).
- 명칭 기록의 재조회 판정은 스펙 §8.4 그대로 «명칭 상태가 `UNREGISTERED` 가 아님» 이다(검토 권고 8 의 «기록 시각 비교» 는 택하지 않았다 — 같은 기록의 재전송도 반영으로 보는 것이 멱등 규칙과 맞다).
- 시험 요청 재조회의 «보낸 시각 뒤에 만들어짐» 은 운영 서비스 시계와 registry 의 `now()` 를 맞대지만, PoC 에서 둘은 같은 기계다(스펙 §9). 열린 요청이 있으면 시각과 상관없이 반영이다.
- 기체 목록의 시운전 칸은 시운전 출처(`CommissioningSource`)를 붙인 목록에만 있다. 붙이지 않은 기존 시험의 목록은 전처럼 바인딩 칸이 없다(기존 `RobotListServiceTest` 9개를 고치지 않으려는 것).
- 제출 본문은 운영 서비스와 화면 모두 바이트(글자) 그대로 넘긴다. JSON 으로 읽히지 않는 본문도 그대로 registry 에 가서 400(`PROFILE_UNREADABLE`)이다. 비어 있으면 운영 서비스가 400(`PROFILE_REQUIRED`)으로 먼저 막는다.
- 실측으로 정한 것: 명칭 상태는 앞선 생존 보고가 이미 답했으면 기록 즉시 `CONFIRMED`/`CONTRADICTED` 다. 그래서 통합 시험은 `SITE_NAMES_UNANSWERED` 를 거치지 않고, 그 갈래는 단위 시험(`CommissioningJudgeTest`)이 본다. registry 는 `{"vendor":"x"}` 를 읽히는 문서로 보고 `DRAFT` 로 저장하므로, 읽을 수 없는 문서의 시험은 JSON 이 아닌 본문으로 한다.
- 기존 시험 셋의 기대값이 바뀐다. S1d 부터 바인딩 안 된 기체에는 `UNBOUND` 막힘이 있기 때문이다. Playwright 의 «막힘 없음» 단언 둘은 «바인딩 없음» 으로, e2e `LifecycleTest` 의 막힘 기대값 셋은 `UNBOUND` 를 넣은 값으로 바뀌고 그 시험 이름 둘도 «바인딩 말고는» 으로 고친다.
- 화면에서 정한 작은 것: 소프트웨어 대조는 «일치·불일치·보고 없음» 으로 보인다. 제출 알림의 대상은 문서에서 읽은 기종·번호(`vendor/model#revision`)이고, 읽지 못하면 파일 이름이다. `DRAFT` 의 «저장됨: 검증 실패» 와 사유는 조작 알림이 아니라 개정판 목록 행에서 펼쳐 본다(조작 결과에는 registry 의 본문이 없다). 퇴역 기체에는 바인딩 폼을 그리지 않는다. «기체가 아는 명칭 없음» 중 기체가 명칭을 지원하지 않는다고 답한 갈래는 엔지니어가 화면 안에서 본다(프로파일과 명칭 기록을 확인).
- 알려진 한계: 명칭 기록의 재조회 판정을 스펙 §8.4 글자대로 두었으므로, 명칭이 필요 없는 기체(`NOT_REQUIRED`)에 대한 기록이 응답 없이 끝나면 registry 는 409 였을 것을 재조회는 «반영됨» 으로 본다. 화면의 명칭 상태는 그대로 `NOT_REQUIRED` 라 오해는 기록 한 줄에 그친다.

**작업 위치 규칙(필수):**
- 모든 작업은 picasso-ops 워크트리 `C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s1d-commissioning` 에서 한다. picasso-ops 메인 체크아웃(`C:\Users\Eisen\Desktop\Labs\[projects] picasso-ops`)과 picasso 저장소는 건드리지 않는다. 하위 에이전트의 Bash 는 호출마다 작업 디렉터리가 돌아가므로 명령마다 `cd <워크트리> &&` 를 붙이거나 `git -C` 를 쓴다.
- `./gradlew --stop` 금지(데몬 풀이 다른 체크아웃과 공유된다). 같은 워크트리에서 Gradle 을 겹쳐 돌리지 않는다. Bash 도구의 시간 한도(600초)를 넘는 빌드는 백그라운드로 돌리고 끝났다는 알림을 받은 뒤 다음 Gradle 을 돌린다.
- `git add -A` 금지. 파일을 이름으로 더한다.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름(Gradle), vitest 출력의 `Tests N passed`, Playwright 출력의 `N passed` 로 한다. Windows python 으로 XML 을 셀 때는 `C:/...` 경로를 쓴다.
- 이 저장소는 LF 다(`.gitattributes` 의 `eol=lf`). 새 파일은 LF 로 쓴다.
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. 커밋 메시지는 heredoc(`git commit -F - <<'EOF'`)으로만 쓴다. 형식 훅이 `-F 파일`, `-m` 두 번, 제목이 `type(scope): 명사구` 가 아닌 것, 겹화살괄호를 막는다.
- 이 계획의 코드와 문서는 스크래치 스파이크(`C:/Users/Eisen/AppData/Local/Temp/s1d`, 브랜치 `feat/s1d-commissioning`, HEAD `e9b6a87`)에서 시험, Playwright, 결함 주입을 다 돌린 것이다. 묶음이 끝날 때마다 커밋된 파일을 스파이크와 기계 대조한다(Task 0 Step 5 의 `s1d-cmp.sh`).
- 실행 방식: 묶음(Task 1·2·3 / Task 4·5·6)마다 구현 하위 에이전트 1명(`model: "sonnet"`), 결함 주입(Task 7)과 검토는 컨트롤러. 새 파일은 아래 내용 그대로 쓰고, 기존 파일은 아래 패치를 워크트리 밖 `C:/Users/Eisen/AppData/Local/Temp/s1d-patches/` 에 저장해 `git apply` 로 넣는다.
- Playwright 는 compose 프로젝트 `site` 의 Postgres 를 볼륨째 내렸다 올린다. 돌리기 전에 `docker ps -a` 와 `docker volume ls` 에 `site` 프로젝트의 것이 없는지 본다. 있으면 멈추고 보고한다(사용자가 손으로 띄운 것일 수 있다).

---

## Chunk 1: 현장과 운영 서비스

### Task 0: 워크트리, 서브모듈, 기준선, 대조 도구

**Files:** 없음(환경)

- [ ] **Step 1: 워크트리 만들기**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops"
git fetch origin -q
git worktree add "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s1d-commissioning" -b feat/s1d-commissioning origin/main
git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s1d-commissioning" branch --unset-upstream
```
Expected: `origin/main` 이 `f84e095`. 다르면 멈추고 보고한다.

- [ ] **Step 2: 서브모듈 채우고 P2b 머지 커밋으로 옮기기**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s1d-commissioning"
git -c protocol.file.allow=always submodule update --init --reference "C:/Users/Eisen/Desktop/Labs/[projects] picasso"
git -C picasso fetch -q origin main
git -C picasso checkout -q 41beedb
git -C picasso log --oneline -1
```
Expected: `41beedb Merge pull request #81 ...`. 메인 체크아웃이 아니라 워크트리의 서브모듈이다.

- [ ] **Step 3: Docker 확인**

Run: `docker info --format '{{.ServerVersion}}'`
Expected: 버전 문자열.

- [ ] **Step 4: 기준선 시험** — 서브모듈을 옮긴 상태로 돌린다. 기존 시험은 P2b 위에서도 통과해야 한다.

Run: `./gradlew :site:test :ops-service:test :e2e:test --continue -q` 그리고 `cd ui && npm ci && npm test`
Expected: XML 기준 site 12, ops-service 76, e2e 20, 실패 0. vitest `Tests 30 passed`.

- [ ] **Step 5: 대조 도구**

`C:/Users/Eisen/AppData/Local/Temp/s1d-cmp.sh` 를 만든다.

```bash
#!/usr/bin/env bash
# usage: s1d-cmp.sh 경로...  워크트리의 커밋된 파일과 스파이크 HEAD 의 파일을 줄바꿈을 뺀 채 바이트 대조한다.
W="C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s1d-commissioning"
S="C:/Users/Eisen/AppData/Local/Temp/s1d"
bad=0
for p in "$@"; do
  if cmp -s <(git -C "$W" show "HEAD:$p" | tr -d '\r') <(git -C "$S" show "HEAD:$p" | tr -d '\r'); then echo "같음 $p"; else echo "다름 $p"; bad=1; fi
done
exit $bad
```

### Task 1: 현장의 실행기와 명칭 티칭

**Files:**
- Modify: `settings.gradle.kts`(좌표 치환 `dev.picasso:harness`), `site/build.gradle.kts`(harness 의존), `site/robots.json`(`site_names`), `site/src/main/kotlin/dev/picasso/ops/site/RobotRoster.kt`(`siteNames`), `site/src/main/kotlin/dev/picasso/ops/site/Site.kt`(실행기 기동·정지, 명칭 넣기, `teach`)
- Modify: `picasso`(서브모듈 포인터, Task 0 Step 2)
- Test: `site/src/test/kotlin/dev/picasso/ops/site/RosterSiteNamesTest.kt`, `site/src/test/kotlin/dev/picasso/ops/site/SiteRunnerTest.kt`

- [ ] **Step 1: 실패하는 시험 쓰기** — 두 파일.

```kotlin
package dev.picasso.ops.site

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 명부의 `site_names`(P2·S1d 스펙 §7). 현장에서 티칭한 명칭이며 런처가 기동 때 기체에 넣는다. */
class RosterSiteNamesTest {

    @Test
    fun `저장소의 명부는 humanoid-01 만 명칭을 티칭했다`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val names = RobotRoster.read(root.resolve(SiteConfig.ROSTER)).associate { it.robotId to it.siteNames }
        assertEquals(mapOf("humanoid-01" to listOf("dock-3", "bay-7"), "quadruped-01" to emptyList()), names)
    }

    @Test
    fun `칸이 없으면 티칭하지 않은 기체다`() {
        val roster = RobotRoster.parse("""[{"robot_id":"r1","serial":"S1","profile":"p.json"}]""")
        assertEquals(emptyList(), roster.single().siteNames)
    }

    @Test
    fun `빈 이름과 배열 아닌 값을 거절한다`() {
        listOf(""""site_names":["dock-3"," "]""", """"site_names":"dock-3"""").forEach { field ->
            val e = assertFailsWith<IllegalArgumentException> {
                RobotRoster.parse("""[{"robot_id":"r1","serial":"S1","profile":"p.json",$field}]""")
            }
            assertTrue("'site_names'" in e.message!!, e.message)
        }
    }
}
```

```kotlin
package dev.picasso.ops.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.registry.PostgresSupport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 런처가 개정판 시험 실행기를 함께 띄우고, 현장의 명칭 티칭이 기체의 보고로 registry 에 닿는다(P2·S1d 스펙 §7).
 *
 * 실행기는 실제 시간 1초마다 폴링하므로 이 시험은 «TESTED 가 될 때까지» 를 실제 시간으로 기다린다(상한 60초).
 */
class SiteRunnerTest {

    private val root = Path.of("..").toAbsolutePath().normalize()
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    private fun config() = SiteConfig(
        root = root,
        siteId = "site-test",
        db = DbConfig(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password),
        registryPort = 0,
        operatorToken = "op-test",
        ingestToken = "in-test",
        roster = RobotRoster.read(root.resolve(SiteConfig.ROSTER)),
    )

    private fun call(site: Site, method: String, path: String, body: String? = null): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(site.registryUrl + path))
                .header("Authorization", "Bearer op-test")
                .header("X-Actor", "engineer/site-test")
                .header("Content-Type", "application/json")
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun revision(site: Site, id: Long): JsonNode =
        json.readTree(call(site, "GET", "/operations/profile-revisions").body()).single { it["profile_revision_id"].asLong() == id }

    @Test
    fun `제출하고 시험을 요청하면 현장의 실행기가 집어 TESTED 로 올린다`() {
        PostgresSupport.reset()
        Site.start(config()).use { site ->
            val submitted = call(site, "POST", "/operations/profile-revisions", Files.readString(root.resolve("picasso/profile/profiles/humanoid-a.json")))
            assertEquals(201, submitted.statusCode(), submitted.body())
            val id = json.readTree(submitted.body())["profile_revision_id"].asLong()
            assertEquals(201, call(site, "POST", "/operations/profile-revisions/$id/test-requests").statusCode())

            val deadline = Instant.now().plusSeconds(60)
            while (revision(site, id)["status"].asText() != "TESTED" && Instant.now().isBefore(deadline)) Thread.sleep(500)

            val row = revision(site, id)
            assertEquals("TESTED", row["status"].asText(), row.toString())
            listOf("CONTRACT", "NEGATIVE", "DETERMINISM").forEach { suite ->
                assertEquals("PASS", row["suites"][suite]["result"].asText(), suite)
                assertEquals(Site.RUNNER_NAME, row["suites"][suite]["ran_by"].asText(), suite)
            }
        }
    }

    @Test
    fun `명부의 명칭이 기체 보고로 닿고 다시 티칭하면 다음 보고부터 바뀐다`() {
        PostgresSupport.reset()
        Site.start(config()).use { site ->
            val declared = call(
                site, "POST", "/operations/robots",
                """{"robot_id":"humanoid-01","site":"site-test","serial_number":"HA-0001"}""",
            )
            assertEquals(201, declared.statusCode(), declared.body())
            fun count(): Int = PostgresSupport.queryOne(
                "SELECT site_names_count FROM robot_liveness WHERE robot_id = 'humanoid-01'",
            ) { it.getInt(1) }

            site.advance(Duration.ofSeconds(31))
            assertEquals(2, count())

            site.teach("humanoid-01", listOf("dock-3", "bay-7", "rack-1"))
            site.advance(Duration.ofSeconds(31))
            assertEquals(3, count())
        }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :site:compileTestKotlin -q`
Expected: 컴파일 실패(`siteNames`, `teach`, `RUNNER_NAME` 을 모름).

- [ ] **Step 3: 패치** — 아래를 `C:/Users/Eisen/AppData/Local/Temp/s1d-patches/s1d-1.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/s1d-patches/s1d-1.patch`.

```diff
diff --git a/settings.gradle.kts b/settings.gradle.kts
index 290dc25..86bb73a 100644
--- a/settings.gradle.kts
+++ b/settings.gradle.kts
@@ -12,11 +12,12 @@ dependencyResolutionManagement {
 }
 
 // picasso 서브프로젝트에는 Gradle group 이 없어 좌표가 자동으로 맞지 않는다(스펙 §11).
-// 쓰는 모듈만 명시한다. uplink 는 mimic 의 api 로 따라온다.
+// 쓰는 모듈만 명시한다. uplink 는 mimic 의 api 로 따라온다. harness 는 가짜 현장이 개정판 시험 실행기를 띄우려고 쓴다.
 includeBuild("picasso") {
     dependencySubstitution {
         substitute(module("dev.picasso:registry")).using(project(":registry"))
         substitute(module("dev.picasso:mimic")).using(project(":mimic"))
+        substitute(module("dev.picasso:harness")).using(project(":harness"))
     }
 }
 
diff --git a/site/build.gradle.kts b/site/build.gradle.kts
index 3aa1038..585a122 100644
--- a/site/build.gradle.kts
+++ b/site/build.gradle.kts
@@ -1,4 +1,5 @@
 // 가짜 현장 런처(스펙 §6). registry 스키마 마이그레이션, registry 기동, mimic 기동과 시간 진행을 맡는다.
+// 개정판 시험 실행기도 여기서 뜬다. 적재 토큰을 가진 것이 이 모듈뿐이다(P2·S1d 스펙 §7).
 plugins {
     application
 }
@@ -11,6 +12,7 @@ application {
 dependencies {
     implementation("dev.picasso:registry")
     implementation("dev.picasso:mimic")
+    implementation("dev.picasso:harness")
 
     // registry 는 Spring Boot·Flyway 를 implementation 으로만 쓴다. 런처가 직접 부르므로 여기서도 적는다.
     implementation(platform(libs.spring.boot.bom))
diff --git a/site/robots.json b/site/robots.json
index f840ab8..46c2188 100644
--- a/site/robots.json
+++ b/site/robots.json
@@ -1,4 +1,4 @@
 [
-  { "robot_id": "humanoid-01", "serial": "HA-0001", "profile": "picasso/profile/profiles/humanoid-a.json" },
-  { "robot_id": "quadruped-01", "serial": "QB-0001", "profile": "picasso/profile/profiles/quadruped-b.json" }
+  { "robot_id": "humanoid-01", "serial": "HA-0001", "profile": "picasso/profile/profiles/humanoid-a.json", "site_names": ["dock-3", "bay-7"] },
+  { "robot_id": "quadruped-01", "serial": "QB-0001", "profile": "picasso/profile/profiles/quadruped-b.json", "site_names": [] }
 ]
diff --git a/site/src/main/kotlin/dev/picasso/ops/site/RobotRoster.kt b/site/src/main/kotlin/dev/picasso/ops/site/RobotRoster.kt
index c443483..e22c84b 100644
--- a/site/src/main/kotlin/dev/picasso/ops/site/RobotRoster.kt
+++ b/site/src/main/kotlin/dev/picasso/ops/site/RobotRoster.kt
@@ -4,8 +4,13 @@ import com.fasterxml.jackson.databind.ObjectMapper
 import java.nio.file.Files
 import java.nio.file.Path
 
-/** `site/robots.json` 의 한 줄. 현장으로 치면 기체 명판이다(스펙 §6). [profile] 은 저장소 루트 기준 경로다. */
-data class RosterEntry(val robotId: String, val serial: String, val profile: String)
+/**
+ * `site/robots.json` 의 한 줄. 현장으로 치면 기체 명판이다(스펙 §6). [profile] 은 저장소 루트 기준 경로다.
+ *
+ * @param siteNames 현장에서 이 기체에 티칭한 사이트 명칭(P2·S1d 스펙 §7). 런처가 기동 때 기체에 넣는다. 칸이 없으면
+ *   빈 목록이다 — 티칭하지 않은 기체다
+ */
+data class RosterEntry(val robotId: String, val serial: String, val profile: String, val siteNames: List<String> = emptyList())
 
 /** 기체 명부. 런처가 이것으로 mimic 을 띄우고, 운영자와 시험이 이것을 보고 기체를 선언한다(스펙 §6). */
 object RobotRoster {
@@ -21,7 +26,7 @@ object RobotRoster {
                 require(!value.isNullOrEmpty()) { "robots.json ${index}번째 기체에 '$name' 이 없거나 비었다" }
                 return value
             }
-            RosterEntry(field("robot_id"), field("serial"), field("profile"))
+            RosterEntry(field("robot_id"), field("serial"), field("profile"), siteNames(node, index))
         }
         require(entries.isNotEmpty()) { "robots.json 에 기체가 없다" }
         val duplicated = entries.groupBy { it.robotId }.filterValues { it.size > 1 }.keys
@@ -29,4 +34,15 @@ object RobotRoster {
         require(duplicated.isEmpty()) { "robots.json 에 같은 robot_id 가 두 번 있다: $duplicated" }
         return entries
     }
+
+    /** 빈 이름은 받지 않는다. registry 는 개수만 대조하므로(P2·S1d 스펙 §1) 빈 문자열이 들어가면 티칭한 것으로 세어진다. */
+    private fun siteNames(node: com.fasterxml.jackson.databind.JsonNode, index: Int): List<String> {
+        val names = node.get("site_names") ?: return emptyList()
+        require(names.isArray) { "robots.json ${index}번째 기체의 'site_names' 는 배열이어야 한다" }
+        return names.map { name ->
+            val value = name.takeIf { it.isTextual }?.asText()?.trim()
+            require(!value.isNullOrEmpty()) { "robots.json ${index}번째 기체의 'site_names' 에 빈 이름이 있다" }
+            value
+        }
+    }
 }
diff --git a/site/src/main/kotlin/dev/picasso/ops/site/Site.kt b/site/src/main/kotlin/dev/picasso/ops/site/Site.kt
index bf54dcb..a7af576 100644
--- a/site/src/main/kotlin/dev/picasso/ops/site/Site.kt
+++ b/site/src/main/kotlin/dev/picasso/ops/site/Site.kt
@@ -1,5 +1,8 @@
 package dev.picasso.ops.site
 
+import dev.picasso.harness.revision.HttpTestDesk
+import dev.picasso.harness.revision.RevisionSuites
+import dev.picasso.harness.revision.RevisionTestRunner
 import dev.picasso.mimic.cli.MimicCli
 import dev.picasso.registry.web.RegistryApplication
 import dev.picasso.uplink.report.RegistryLink
@@ -18,6 +21,7 @@ import java.time.Duration
 class Site private constructor(
     private val registry: ConfigurableApplicationContext,
     private val mimic: MimicCli.Started,
+    private val runner: AutoCloseable,
     val registryUrl: String,
     val robotIds: Set<String>,
 ) : AutoCloseable {
@@ -25,14 +29,27 @@ class Site private constructor(
     /** mimic 의 가상 시계를 민다. 상태 발행이 이 시계로 정해지고, 상태 발행이 곧 생존 보고다. */
     fun advance(by: Duration) = mimic.server.advance(by)
 
-    /** registry 만 멈춘다. 운영 서비스가 «모름» 을 보이는지 볼 때 쓴다(스펙 §3 S1a). */
+    /** registry 만 멈춘다. 운영 서비스가 «모름» 을 보이는지 볼 때 쓴다(스펙 §3 S1a). 실행기는 집기 실패를 로그에 남기며 폴링을 이어 간다. */
     fun stopRegistry() = registry.close()
 
+    /**
+     * 현장에서 이 기체에 명칭을 다시 티칭한다(P2·S1d 스펙 §7). 기체가 아는 명칭은 생존 보고마다 실리므로 다음 [advance] 의
+     * 상태 발행부터 registry 에 닿는다.
+     */
+    fun teach(robotId: String, siteNames: List<String>) {
+        val instance = requireNotNull(mimic.instance(robotId)) { "이 현장에 없는 기체다: $robotId" }
+        instance.knownSiteNames = siteNames
+    }
+
     override fun close() {
         try {
-            mimic.server.shutdown()
+            runner.close()
         } finally {
-            if (registry.isActive) registry.close()
+            try {
+                mimic.server.shutdown()
+            } finally {
+                if (registry.isActive) registry.close()
+            }
         }
     }
 
@@ -82,9 +99,25 @@ class Site private constructor(
                 registry.close()
                 error("mimic 기동 거부: $err")
             }
-            return Site(registry, mimic, registryUrl, mimic.robotIds)
+            // ⑤ 현장에서 티칭한 명칭을 기체에 넣는다. 명칭은 프로파일이 아니라 현장의 것이다(ADR 35).
+            config.roster.forEach { mimic.instance(it.robotId)?.knownSiteNames = it.siteNames }
+
+            // ⑥ 개정판 시험 실행기. 적재 토큰을 가진 것이 이 프로세스뿐이다(P2·S1d 스펙 §7). 시험에 쓰는 mimic 은
+            // 실행기가 시험마다 따로 띄우므로 현장 기체의 보고와 상태를 바꾸지 않는다.
+            val runner = RevisionTestRunner(
+                HttpTestDesk(registryUrl, config.ingestToken),
+                RevisionSuites(config.schema),
+                RUNNER_NAME,
+            ).start(RUNNER_INTERVAL)
+            return Site(registry, mimic, runner, registryUrl, mimic.robotIds)
         }
 
+        /** 실행기 이름. 시험 결과의 실행 주체로 화면에 보인다. */
+        const val RUNNER_NAME = "site-runner"
+
+        /** 실행기의 폴링 간격(실제 시간). */
+        val RUNNER_INTERVAL: Duration = Duration.ofSeconds(1)
+
         /** registry jar 의 `classpath:db/migration` 을 기본 스키마(public)에 올린다. registry 시험 픽스처와 같은 설정이다. */
         fun migrateRegistrySchema(db: DbConfig): Int =
             Flyway.configure()
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :site:test -q`
Expected: XML 기준 site 17개 실패 0(`RosterSiteNamesTest` 3, `SiteRunnerTest` 2 포함). `SiteRunnerTest` 의 실행기 시험은 실제 시간으로 `TESTED` 를 기다리며 스파이크에서 약 13초 걸렸다.

- [ ] **Step 5: 커밋**

```bash
git add picasso settings.gradle.kts site/build.gradle.kts site/robots.json site/src/main/kotlin/dev/picasso/ops/site/RobotRoster.kt site/src/main/kotlin/dev/picasso/ops/site/Site.kt site/src/test/kotlin/dev/picasso/ops/site/RosterSiteNamesTest.kt site/src/test/kotlin/dev/picasso/ops/site/SiteRunnerTest.kt
git commit -q -F - <<'EOF'
feat(site): 개정판 시험 실행기 기동과 기체 명칭 티칭

- 작업 묶음 커밋(Task 8 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 2: registry 읽기, 시운전 판정, 프로파일 목록

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/registry/ProfileRegistry.kt`(읽기·조작 타입과 이음새), `ops-service/src/main/kotlin/dev/picasso/ops/service/robots/Commissioning.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/profiles/ProfileListService.kt`
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt`(읽기 4·조작 5), `ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt`(셋 다 읽혀야 갱신)
- Test: `CommissioningJudgeTest.kt`, `ProfileListServiceTest.kt`, `RobotCommissioningListTest.kt`, `RegistryProfilesTest.kt`(모두 `ops-service/src/test/kotlin/dev/picasso/ops/service/`)

- [ ] **Step 1: 실패하는 시험 쓰기** — 네 파일.

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.registry.RegistryBinding
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.robots.CommissioningJudge
import dev.picasso.ops.service.robots.CommissioningState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 시운전 판정 세 조건(결정 6)과 새 막힘 4종(P2·S1d 스펙 §8.5)을 표로 본다. */
class CommissioningJudgeTest {

    private val at = Instant.parse("2026-10-08T00:00:00Z")

    private fun robot(status: String) = RegistryRobot(robotId = "r1", siteId = "site-01", status = status)

    private fun binding(siteNames: String, unsupported: Boolean? = null, count: Int? = null) = RegistryBinding(
        robotId = "r1", vendor = "v", model = "m", profileRevisionId = 2, revision = 1, adapterName = "acme/fleet",
        adapterVersion = "1.0.0", conformanceStatus = "UNTESTED", active = true, siteNames = siteNames,
        siteNameKeys = listOf("location"), adapterVersionId = 7, boundBy = "engineer/kim", boundAt = "t",
        siteNamesRegisteredAt = "t1", siteNamesUnsupported = unsupported, siteNamesCount = count,
    )

    private data class Row(val status: String, val binding: RegistryBinding?, val state: CommissioningState, val kinds: List<String>)

    private val table = listOf(
        Row("CONFIRMED", binding("CONFIRMED"), CommissioningState.COMPLETE, emptyList()),
        Row("CONFIRMED", binding("NOT_REQUIRED"), CommissioningState.COMPLETE, emptyList()),
        Row("CONFIRMED", null, CommissioningState.INCOMPLETE, listOf(CommissioningJudge.UNBOUND)),
        Row("CLAIMED", binding("CONFIRMED"), CommissioningState.INCOMPLETE, emptyList()),
        Row("CONFIRMED", binding("UNREGISTERED"), CommissioningState.INCOMPLETE, listOf(CommissioningJudge.SITE_NAMES_UNREGISTERED)),
        Row("CONFIRMED", binding("CLAIMED"), CommissioningState.INCOMPLETE, listOf(CommissioningJudge.SITE_NAMES_UNANSWERED)),
        Row("CONFIRMED", binding("CONTRADICTED", count = 0), CommissioningState.INCOMPLETE, listOf(CommissioningJudge.SITE_NAMES_CONTRADICTED)),
        Row("RETIRED", null, CommissioningState.RETIRED, emptyList()),
        Row("RETIRED", binding("CONFIRMED"), CommissioningState.RETIRED, emptyList()),
    )

    @Test
    fun `세 조건이 모두 맞아야 완료이고 빠진 것이 막힘으로 보인다`() {
        table.forEach { row ->
            val judged = CommissioningJudge.of(robot(row.status), row.binding)
            assertEquals(row.state, judged.state, "$row")
            assertEquals(row.kinds, CommissioningJudge.blockers(robot(row.status), row.binding, at).map { it.kind }, "$row")
        }
    }

    @Test
    fun `체크 목록은 조건마다 따로 참거짓이다`() {
        val judged = CommissioningJudge.of(robot("CLAIMED"), binding("UNREGISTERED"))
        assertEquals(Triple(false, true, false), Triple(judged.ledgerConfirmed, judged.bound, judged.siteNamesReady))
    }

    @Test
    fun `해결 담당은 바인딩·기록 빠짐이 엔지니어이고 기체의 답 문제는 현장이다`() {
        fun owner(binding: RegistryBinding?) = CommissioningJudge.blockers(robot("CONFIRMED"), binding, at).single().let { it.owner to it.inScreen }
        assertEquals(Owner.ENGINEER to true, owner(null))
        assertEquals(Owner.ENGINEER to true, owner(binding("UNREGISTERED")))
        assertEquals(Owner.SITE to false, owner(binding("CLAIMED")))
        assertEquals(Owner.SITE to false, owner(binding("CONTRADICTED", unsupported = false, count = 0)))
    }

    @Test
    fun `기체가 명칭을 지원하지 않는다고 답했으면 다시 티칭이 아니라 엔지니어가 프로파일을 본다`() {
        val finding = CommissioningJudge.blockers(robot("CONFIRMED"), binding("CONTRADICTED", unsupported = true), at).single()
        assertEquals(CommissioningJudge.SITE_NAMES_CONTRADICTED, finding.kind)
        assertEquals(Owner.ENGINEER to true, finding.owner to finding.inScreen)
        assertEquals("그 기종의 프로파일과 명칭 기록 확인", finding.action)
        assertEquals("r1" to at, finding.target to finding.checkedAt)
    }
}
```

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.profiles.ProfileListService
import dev.picasso.ops.service.profiles.TestRequestState
import dev.picasso.ops.service.registry.ProfileSource
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryCatalog
import dev.picasso.ops.service.registry.RegistryRevision
import dev.picasso.ops.service.registry.RegistryTestRequest
import dev.picasso.ops.service.robots.RegistryState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 프로파일 목록(P2·S1d 스펙 §8.1)과 시험 요청 상태 4값(§9). registry 는 대역이다. */
class ProfileListServiceTest {

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val t1 = Instant.parse("2026-10-08T00:00:00Z")
    private val t2 = Instant.parse("2026-10-08T00:00:05Z")
    private val clock = MovableClock(t1)

    private var catalog: RegistryCall<RegistryCatalog> = RegistryCall.Ok(RegistryCatalog("0.9.0"))
    private var revisions: RegistryCall<List<RegistryRevision>> = RegistryCall.Ok(emptyList())

    private val service = ProfileListService(
        object : ProfileSource {
            override fun catalog() = catalog
            override fun revisions() = revisions
        },
        clock,
    )

    private fun revision(request: RegistryTestRequest?) =
        RegistryRevision(1, "v", "m", 1, "VALIDATED", documentHash = "h", latestTestRequest = request)

    private fun request(claimedAt: String? = null, expires: String? = null, completed: String? = null) =
        RegistryTestRequest(9, "engineer/kim", "2026-10-07T23:00:00Z", claimedBy = claimedAt?.let { "site-runner" }, claimedAt = claimedAt, claimExpiresAt = expires, completedAt = completed)

    @Test
    fun `시험 요청 상태는 요청 없음·대기·실행 중·만료·끝남이다`() {
        val cases = listOf(
            null to TestRequestState.NONE,
            request() to TestRequestState.WAITING,
            request("2026-10-07T23:59:00Z", "2026-10-08T00:14:00Z") to TestRequestState.RUNNING,
            request("2026-10-07T23:40:00Z", "2026-10-07T23:55:00Z") to TestRequestState.EXPIRED,
            request("2026-10-07T23:40:00Z", "2026-10-07T23:55:00Z", "2026-10-07T23:41:00Z") to TestRequestState.DONE,
        )
        cases.forEach { (req, state) -> assertEquals(state, ProfileListService.testRequestState(revision(req), t1), "$req") }
    }

    @Test
    fun `만료 시각과 같은 시각은 이미 만료다 - registry 가 그 순간부터 다시 집어 준다`() {
        val at = Instant.parse("2026-10-08T00:14:00Z")
        assertEquals(TestRequestState.EXPIRED, ProfileListService.testRequestState(revision(request("2026-10-07T23:59:00Z", "2026-10-08T00:14:00Z")), at))
    }

    @Test
    fun `둘 다 읽히면 새 값이고 상태는 읽은 시각으로 판정한다`() {
        revisions = RegistryCall.Ok(listOf(revision(request("2026-10-07T23:59:00Z", "2026-10-08T00:00:03Z"))))
        val first = service.read()
        assertEquals(RegistryState.OK to t1, first.registry to first.asOf)
        assertEquals(TestRequestState.RUNNING, first.revisions!!.single().testRequest)

        clock.now = t2
        assertEquals(TestRequestState.EXPIRED, service.read().revisions!!.single().testRequest)
    }

    @Test
    fun `하나라도 못 읽으면 둘 다 직전 값이고 토큰 불일치는 전체 상태로 간다`() {
        assertNull(ProfileListService(object : ProfileSource {
            override fun catalog() = RegistryCall.Silent("x")
            override fun revisions() = revisions
        }, clock).read().catalog)

        service.read()
        clock.now = t2
        revisions = RegistryCall.Silent("응답 없음")
        val silent = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT to t1, silent.registry to silent.asOf)
        assertEquals("0.9.0", silent.catalog!!.contractSemver)

        catalog = RegistryCall.Unauthorized
        assertEquals(RegistryState.REGISTRY_UNAUTHORIZED, service.read().registry)
    }

    @Test
    fun `카탈로그는 읽혀도 개정판 목록이 401 이면 토큰 불일치다`() {
        revisions = RegistryCall.Unauthorized
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_UNAUTHORIZED, view.registry)
        assertNull(view.revisions)
    }
}
```

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.registry.CommissioningSource
import dev.picasso.ops.service.registry.RegistryBinding
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RegistrySoftware
import dev.picasso.ops.service.robots.CommissioningState
import dev.picasso.ops.service.robots.RegistryState
import dev.picasso.ops.service.robots.RobotListService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 기체 목록이 기체 → 바인딩 → 소프트웨어 대조를 함께 읽고 셋 다 읽혀야 새 값으로 바꾼다(P2·S1d 스펙 §8.1). */
class RobotCommissioningListTest {

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val t1 = Instant.parse("2026-10-08T00:00:00Z")
    private val t2 = Instant.parse("2026-10-08T00:00:05Z")
    private val clock = MovableClock(t1)
    private val r1 = RegistryRobot(robotId = "r1", siteId = "site-01", status = "CONFIRMED", lastReportedAt = "2026-10-07T23:59:50Z")
    private val r2 = RegistryRobot(robotId = "r2", siteId = "site-01", status = "CONFIRMED", lastReportedAt = "2026-10-07T23:59:50Z")

    private fun binding(robotId: String, siteNames: String) = RegistryBinding(
        robotId = robotId, vendor = "v", model = "m", profileRevisionId = 2, revision = 1, adapterName = "acme/fleet",
        adapterVersion = "1.0.0", conformanceStatus = "UNTESTED", active = true, siteNames = siteNames,
        adapterVersionId = 7, boundBy = "engineer/kim", boundAt = "t",
    )

    private var robots: RegistryCall<List<RegistryRobot>> = RegistryCall.Ok(listOf(r1, r2))
    private var bindings: RegistryCall<List<RegistryBinding>> = RegistryCall.Ok(listOf(binding("r1", "CONFIRMED")))
    private var software: RegistryCall<List<RegistrySoftware>> = RegistryCall.Ok(listOf(RegistrySoftware("r1", "1.0", "1.0", "MATCH")))
    private val asked = mutableListOf<String>()

    private val service = RobotListService(
        { robots },
        { RegistryCall.Ok(Unit) },
        "site-01",
        clock,
        Duration.ofSeconds(90),
        commissioning = object : CommissioningSource {
            override fun bindings(siteId: String) = bindings.also { asked += "bindings $siteId" }
            override fun software(siteId: String) = software.also { asked += "software $siteId" }
        },
    )

    @Test
    fun `셋 다 읽히면 기체마다 바인딩·시운전·소프트웨어 대조를 붙인다`() {
        val view = service.read()
        assertEquals(listOf("bindings site-01", "software site-01"), asked)

        val (one, two) = view.robots!!
        assertEquals(CommissioningState.COMPLETE, one.commissioning!!.state)
        assertEquals(7L, one.binding!!.adapterVersionId)
        assertEquals("MATCH", one.software!!.verdict)
        assertEquals(emptyList(), one.blockers)

        assertEquals(CommissioningState.INCOMPLETE, two.commissioning!!.state)
        assertNull(two.binding)
        assertEquals(listOf("UNBOUND"), two.blockers.map { it.kind })
    }

    @Test
    fun `바인딩이나 소프트웨어 대조를 못 읽으면 셋 다 직전 값이다`() {
        service.read()
        clock.now = t2
        bindings = RegistryCall.Ok(emptyList())
        software = RegistryCall.Silent("응답 없음")

        val silent = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT to t1, silent.registry to silent.robotsAsOf)
        assertEquals(7L, silent.robots!!.first().binding!!.adapterVersionId)

        software = RegistryCall.Ok(emptyList())
        bindings = RegistryCall.Silent("응답 없음")
        asked.clear()
        assertEquals(RegistryState.REGISTRY_SILENT, service.read().registry)
        assertEquals(listOf("bindings site-01"), asked)
    }

    @Test
    fun `기체 목록을 못 읽으면 바인딩을 묻지 않는다`() {
        robots = RegistryCall.Silent("응답 없음")
        assertEquals(RegistryState.REGISTRY_SILENT, service.read().registry)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `이력 행은 활성 바인딩으로 보지 않는다`() {
        bindings = RegistryCall.Ok(listOf(binding("r1", "CONFIRMED").copy(active = false)))
        val one = service.read().robots!!.first()
        assertNull(one.binding)
        assertEquals(listOf("UNBOUND"), one.blockers.map { it.kind })
    }
}
```

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryClient
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 개정판·바인딩 읽기와 조작이 registry 와 주고받는 모양(P2·S1d 스펙 §8.1·§8.2). registry 는 JDK HttpServer 대역이다. */
class RegistryProfilesTest {

    private class Seen(val method: String, val uri: String, val actor: String?, val body: ByteArray)

    private var server: HttpServer? = null
    @Volatile private var seen: Seen? = null
    private val json = ObjectMapper()

    private fun serve(status: Int, body: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange ->
            seen = Seen(
                exchange.requestMethod,
                exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""),
                exchange.requestHeaders.getFirst("X-Actor"),
                exchange.requestBody.readBytes(),
            )
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    private fun request() = "${seen!!.method} ${seen!!.uri} ${seen!!.actor}"

    @Test
    fun `카탈로그와 개정판 목록은 registry 의 snake_case 를 읽는다`() {
        val catalog = RegistryClient(
            serve(200, """{"contract_semver":"0.9.0","skill_types":[{"name":"navigate_to","major":1,"introduced_in_semver":"0.1.0","site_reference_keys":["location"]}]}"""),
            "op-t",
        ).catalog()
        val skill = (catalog as RegistryCall.Ok).value.skillTypes.single()
        assertEquals(listOf("navigate_to", "0.1.0", "location"), listOf(skill.name, skill.introducedInSemver, skill.siteReferenceKeys.single()))
        assertEquals("GET /operations/skill-types null", request())

        val revisions = RegistryClient(
            serve(
                200,
                """[{"profile_revision_id":5,"vendor":"v","model":"m","revision":2,"status":"TESTED","reasons":[],"document_hash":"h",
                "suites":{"CONTRACT":{"result":"PASS","ran_at":"t","ran_by":"site-runner","detail":{"checks":3,"failures":[]}}},
                "latest_test_request":{"request_id":9,"requested_by":"engineer/kim","requested_at":"t","claimed_by":"site-runner",
                "claimed_at":"t","claim_expires_at":"t","completed_at":"t"}}]""",
            ),
            "op-t",
        ).revisions()
        val row = (revisions as RegistryCall.Ok).value.single()
        assertEquals("site-runner" to 3, row.suites["CONTRACT"]!!.ranBy to row.suites["CONTRACT"]!!.detail!!["checks"].asInt())
        assertEquals(9L to "t", row.latestTestRequest!!.requestId to row.latestTestRequest!!.completedAt)
    }

    @Test
    fun `바인딩은 사이트의 활성 행만 묻고 줄을 읽는다`() {
        val call = RegistryClient(
            serve(
                200,
                """{"rows":[{"robotId":"r1","siteId":"site 01","vendor":"v","model":"m","profileRevisionId":5,"revision":2,
                "adapterName":"acme/fleet","adapterVersion":"1.0.0","conformanceStatus":"UNTESTED","active":true,"liveness":"REPORTING",
                "siteNames":"CONFIRMED","siteNameKeys":["location"],"adapterVersionId":7,"boundBy":"engineer/kim","boundAt":"t",
                "siteNamesRegisteredBy":"engineer/kim","siteNamesRegisteredAt":"t","siteNamesReportedAt":"t","siteNamesCount":2,
                "siteNamesUnsupported":false}],"robotsPerRevision":{"5":1}}""",
            ),
            "op-t",
        ).bindings("site 01")
        val row = (call as RegistryCall.Ok).value.single()
        assertEquals(Triple(7L, "CONFIRMED", 2), Triple(row.adapterVersionId, row.siteNames, row.siteNamesCount))
        assertEquals("GET /diag/bindings?site=site+01 null", request())
    }

    @Test
    fun `소프트웨어 대조는 사이트로 묻는다`() {
        val call = RegistryClient(serve(200, """[{"robotId":"r1","declared":null,"reported":"1.0","verdict":"UNREPORTED"}]"""), "op-t").software("site-01")
        val row = (call as RegistryCall.Ok).value.single()
        assertEquals("UNREPORTED" to null, row.verdict to row.declared)
        assertEquals("GET /diag/software?site=site-01 null", request())
    }

    @Test
    fun `제출은 본문 바이트를 다시 직렬화하지 않고 그대로 보낸다`() {
        val document = "{\n  \"vendor\" : \"v\",   \"note\": \"한글\"\n}".toByteArray()
        RegistryClient(serve(201, "{}"), "op-t").submit(document, "engineer/kim")
        assertEquals("POST /operations/profile-revisions engineer/kim", request())
        assertContentEquals(document, seen!!.body)
    }

    @Test
    fun `시험 요청·활성화·명칭 기록은 본문 없이, 바인딩은 두 id 를 싣고 보낸다`() {
        val client = RegistryClient(serve(200, "{}"), "op-t")

        client.requestTest(5, "engineer/kim")
        assertEquals("POST /operations/profile-revisions/5/test-requests engineer/kim", request())
        assertEquals(0, seen!!.body.size)

        client.activate(5, "engineer/kim")
        assertEquals("POST /operations/profile-revisions/5/activation engineer/kim", request())

        client.recordSiteNames("robot 1", "engineer/kim")
        assertEquals("POST /operations/site-names?robot=robot+1 engineer/kim", request())

        client.bind("robot 1", 7, 5, "engineer/kim")
        assertEquals("POST /operations/robots/robot%201/binding engineer/kim", request())
        assertEquals(json.readTree("""{"adapter_version_id":7,"profile_revision_id":5}"""), json.readTree(seen!!.body))
    }

    @Test
    fun `개정판 목록의 401 은 토큰 불일치다`() {
        assertEquals(RegistryCall.Unauthorized, RegistryClient(serve(401, ""), "op-t").revisions())
        assertNull((RegistryClient(serve(200, "null"), "op-t").catalog() as? RegistryCall.Ok))
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :ops-service:compileTestKotlin -q`
Expected: 컴파일 실패.

- [ ] **Step 3: 새 파일 셋**

```kotlin
package dev.picasso.ops.service.registry

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

/** registry `GET /operations/skill-types` 의 스킬 종류 한 줄(P2·S1d 스펙 §8.1). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistrySkillType(
    val name: String,
    val major: Int,
    @JsonAlias("introduced_in_semver") val introducedInSemver: String,
    @JsonAlias("site_reference_keys") val siteReferenceKeys: List<String> = emptyList(),
)

/** registry `GET /operations/skill-types` 의 답. 카탈로그는 registry 가 기동 때 계약에서 채운다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryCatalog(
    @JsonAlias("contract_semver") val contractSemver: String,
    @JsonAlias("skill_types") val skillTypes: List<RegistrySkillType> = emptyList(),
)

/** 스위트 하나의 최신 실행. [detail] 은 실행기가 낸 JSON 그대로다(`checks`·`failures`). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistrySuiteRun(
    val result: String,
    @JsonAlias("ran_at") val ranAt: String,
    @JsonAlias("ran_by") val ranBy: String,
    val detail: JsonNode? = null,
)

/** 개정판의 최신 시험 요청 1건. 끝났든 아니든 가장 늦게 들어온 요청이다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryTestRequest(
    @JsonAlias("request_id") val requestId: Long,
    @JsonAlias("requested_by") val requestedBy: String,
    @JsonAlias("requested_at") val requestedAt: String,
    @JsonAlias("claimed_by") val claimedBy: String? = null,
    @JsonAlias("claimed_at") val claimedAt: String? = null,
    @JsonAlias("claim_expires_at") val claimExpiresAt: String? = null,
    @JsonAlias("completed_at") val completedAt: String? = null,
)

/** registry `GET /operations/profile-revisions` 의 한 줄. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryRevision(
    @JsonAlias("profile_revision_id") val profileRevisionId: Long,
    val vendor: String,
    val model: String,
    val revision: Int,
    val status: String,
    val reasons: List<String> = emptyList(),
    @JsonAlias("document_hash") val documentHash: String,
    @JsonAlias("created_by") val createdBy: String? = null,
    @JsonAlias("created_at") val createdAt: String? = null,
    @JsonAlias("activated_by") val activatedBy: String? = null,
    @JsonAlias("activated_at") val activatedAt: String? = null,
    val suites: Map<String, RegistrySuiteRun> = emptyMap(),
    @JsonAlias("latest_test_request") val latestTestRequest: RegistryTestRequest? = null,
)

/**
 * registry `GET /diag/bindings` 의 활성 바인딩 한 줄. 명칭 상태는 registry 의 5값(`NOT_REQUIRED`·`UNREGISTERED`·`CLAIMED`·
 * `CONFIRMED`·`CONTRADICTED`)이다. 사람의 기록(`siteNamesRegistered*`)과 기체의 답(`siteNamesReported*`)을 따로 싣는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryBinding(
    val robotId: String,
    val vendor: String,
    val model: String,
    val profileRevisionId: Long,
    val revision: Int,
    val adapterName: String,
    val adapterVersion: String,
    val conformanceStatus: String,
    val active: Boolean,
    val siteNames: String,
    val siteNameKeys: List<String> = emptyList(),
    val adapterVersionId: Long,
    val boundBy: String,
    val boundAt: String,
    val siteNamesRegisteredBy: String? = null,
    val siteNamesRegisteredAt: String? = null,
    val siteNamesReportedAt: String? = null,
    val siteNamesCount: Int? = null,
    val siteNamesUnsupported: Boolean? = null,
)

/** registry `GET /diag/bindings` 의 답. 운영 서비스는 줄만 쓴다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryBindings(val rows: List<RegistryBinding> = emptyList())

/** registry `GET /diag/software` 의 한 줄. `verdict` 는 `MATCH`·`MISMATCH`·`UNREPORTED` 다. 시운전을 막지 않고 보이기만 한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistrySoftware(
    val robotId: String,
    val declared: String? = null,
    val reported: String? = null,
    val verdict: String,
)

/** 카탈로그와 개정판 목록의 출처. 둘 다 운영자 토큰 관문 안의 읽기다. 시험이 registry 없이 대신 끼운다. */
interface ProfileSource {
    fun catalog(): RegistryCall<RegistryCatalog>

    fun revisions(): RegistryCall<List<RegistryRevision>>
}

/** 바인딩과 소프트웨어 대조의 출처. 둘 다 관문 밖의 진단이다. 시험이 registry 없이 대신 끼운다. */
interface CommissioningSource {
    /** 이 사이트의 활성 바인딩. 이력은 빼고 읽는다. */
    fun bindings(siteId: String): RegistryCall<List<RegistryBinding>>

    fun software(siteId: String): RegistryCall<List<RegistrySoftware>>
}

/** 개정판 조작 3가지(P2·S1d 스펙 §8.2). 시험이 registry 없이 대신 끼운다. */
interface ProfileWrites {
    /** [document] 는 화면이 보낸 본문 바이트 그대로다. 다시 직렬화하면 registry 의 문서 해시가 달라진다. */
    fun submit(document: ByteArray, actor: String): RegistryWrite

    fun requestTest(profileRevisionId: Long, actor: String): RegistryWrite

    fun activate(profileRevisionId: Long, actor: String): RegistryWrite
}

/** 바인딩과 명칭 기록(P2·S1d 스펙 §8.2). 시험이 registry 없이 대신 끼운다. */
interface BindingWrites {
    fun bind(robotId: String, adapterVersionId: Long, profileRevisionId: Long, actor: String): RegistryWrite

    fun recordSiteNames(robotId: String, actor: String): RegistryWrite
}
```

```kotlin
package dev.picasso.ops.service.robots

import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.registry.RegistryBinding
import dev.picasso.ops.service.registry.RegistryRobot
import java.time.Instant

/** «시운전» 칸(P2·S1d 스펙 §8.5). 연결 칸과 합치지 않는다 — 시운전은 갖춘 조건이고 연결은 지금의 보고다. */
enum class CommissioningState { COMPLETE, INCOMPLETE, RETIRED }

/**
 * 시운전 판정 한 건. 세 조건(결정 6)을 따로 들고 있어 화면의 «시운전» 카드가 무엇이 빠졌는지 체크 목록으로 보인다.
 *
 * @param ledgerConfirmed 원장 상태가 `CONFIRMED` 다(퇴역이면 거짓). 근거는 `/diag/robots`
 * @param bound 활성 바인딩이 있다. 근거는 `/diag/bindings`
 * @param siteNamesReady 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 다. 근거는 `/diag/bindings` 의 명칭 상태
 */
data class Commissioning(
    val state: CommissioningState,
    val ledgerConfirmed: Boolean,
    val bound: Boolean,
    val siteNamesReady: Boolean,
)

/**
 * 시운전 판정과 그 막힘 4종(P2·S1d 스펙 §8.5). 모두 registry 에서 읽은 값과 그 시각만으로 정한다.
 *
 * 소프트웨어 대조와 어댑터 적합성은 판정에 넣지 않는다(결정 6). 막지 않고 보이기만 한다.
 */
object CommissioningJudge {

    const val UNBOUND = "UNBOUND"
    const val SITE_NAMES_UNREGISTERED = "SITE_NAMES_UNREGISTERED"
    const val SITE_NAMES_UNANSWERED = "SITE_NAMES_UNANSWERED"
    const val SITE_NAMES_CONTRADICTED = "SITE_NAMES_CONTRADICTED"

    /** 명칭이 «다 됐다» 로 읽히는 상태. `NOT_REQUIRED` 는 이 기체의 스킬이 명칭을 쓰지 않는 것이다. */
    private val READY = setOf("CONFIRMED", "NOT_REQUIRED")

    fun of(robot: RegistryRobot, binding: RegistryBinding?): Commissioning {
        val ledger = robot.status == "CONFIRMED"
        val names = binding != null && binding.siteNames in READY
        val state = when {
            robot.status == "RETIRED" -> CommissioningState.RETIRED
            ledger && binding != null && names -> CommissioningState.COMPLETE
            else -> CommissioningState.INCOMPLETE
        }
        return Commissioning(state, ledger, binding != null, names)
    }

    /** 퇴역 기체에는 막힘이 없다. 퇴역은 시운전에서 빠지는 것이다. */
    fun blockers(robot: RegistryRobot, binding: RegistryBinding?, at: Instant): List<Finding> {
        if (robot.status == "RETIRED") return emptyList()
        fun finding(kind: String, observed: String, expected: String, owner: Owner, inScreen: Boolean, action: String) =
            Finding(kind, observed, expected, at, owner, inScreen, action, robot.robotId)

        if (binding == null) {
            return listOf(
                finding(UNBOUND, "활성 바인딩 없음", "빌드와 활성 개정판의 바인딩", Owner.ENGINEER, true, "빌드와 활성 개정판을 골라 바인딩"),
            )
        }
        val keys = binding.siteNameKeys.joinToString(", ")
        return when (binding.siteNames) {
            "UNREGISTERED" -> listOf(
                finding(
                    SITE_NAMES_UNREGISTERED, "명칭 기록 없음(요구 키 $keys)", "명칭 등록 기록",
                    Owner.ENGINEER, true, "현장 티칭을 확인한 뒤 명칭 기록",
                ),
            )
            "CLAIMED" -> listOf(
                finding(
                    SITE_NAMES_UNANSWERED, "기록 ${binding.siteNamesRegisteredAt}, 기체 답 없음", "기체가 아는 명칭 1개 이상",
                    Owner.SITE, false, "기체 보고 확인",
                ),
            )
            "CONTRADICTED" -> listOf(
                if (binding.siteNamesUnsupported == true) {
                    finding(
                        SITE_NAMES_CONTRADICTED, "기록 ${binding.siteNamesRegisteredAt}, 기체가 명칭을 지원하지 않음",
                        "기체가 아는 명칭 1개 이상", Owner.ENGINEER, true, "그 기종의 프로파일과 명칭 기록 확인",
                    )
                } else {
                    finding(
                        SITE_NAMES_CONTRADICTED, "기록 ${binding.siteNamesRegisteredAt}, 기체가 아는 명칭 ${binding.siteNamesCount ?: 0}개",
                        "기체가 아는 명칭 1개 이상", Owner.SITE, false, "현장에서 명칭 티칭을 다시. 다음 보고로 풀림",
                    )
                },
            )
            else -> emptyList()
        }
    }
}
```

```kotlin
package dev.picasso.ops.service.profiles

import dev.picasso.ops.service.registry.ProfileSource
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryCatalog
import dev.picasso.ops.service.registry.RegistryRevision
import dev.picasso.ops.service.robots.RegistryState
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * 시험 요청 상태(P2·S1d 스펙 §9). «대기» 는 아직 안 집힌 것, «실행 중» 은 집혔고 만료 전, «만료» 는 집혔으나 만료가
 * 지나 다시 집히기를 기다리는 것, «끝남» 은 끝난 것이다. 요청이 없으면 [NONE] 이다.
 */
enum class TestRequestState { NONE, WAITING, RUNNING, EXPIRED, DONE }

/** 개정판 한 줄과 그 시험 요청 상태. 상태는 목록을 읽은 시각과 만료 시각을 비교해 정한다. */
data class RevisionView(val revision: RegistryRevision, val testRequest: TestRequestState)

/**
 * `GET /api/profiles` 의 답.
 *
 * @param catalog 널이면 «모름»(한 번도 읽지 못했다)
 * @param asOf [catalog]·[revisions] 를 registry 에서 읽은 시각. [checkedAt] 과 다르면 직전 값이다
 */
data class ProfileListView(
    val registry: RegistryState,
    val checkedAt: Instant,
    val catalog: RegistryCatalog?,
    val revisions: List<RevisionView>?,
    val asOf: Instant?,
)

/**
 * 카탈로그(`GET /operations/skill-types`)와 개정판 목록(`GET /operations/profile-revisions`). 둘 다 읽혀야 새 값으로
 * 바꾸고, 하나라도 못 읽으면 둘 다 직전 값이다(어댑터 목록과 같은 규칙). 둘 다 운영자 토큰 관문 안이라 토큰이 틀리면
 * [RegistryState.REGISTRY_UNAUTHORIZED] 다.
 */
class ProfileListService(
    private val source: ProfileSource,
    private val clock: Clock,
) {
    private data class Known(val catalog: RegistryCatalog, val revisions: List<RegistryRevision>, val at: Instant)

    private val last = AtomicReference<Known?>(null)

    fun read(): ProfileListView {
        val now = clock.instant()
        val catalog = source.catalog()
        val revisions = if (catalog is RegistryCall.Ok) source.revisions() else null
        if (catalog is RegistryCall.Ok && revisions is RegistryCall.Ok) {
            // 늦게 끝난 옛 읽기가 더 새 값을 덮지 않게 한다. 폴링과 조작 뒤 다시 읽기가 겹칠 수 있다.
            val known = last.updateAndGet { prev ->
                if (prev != null && prev.at.isAfter(now)) prev else Known(catalog.value, revisions.value, now)
            }!!
            return view(RegistryState.OK, maxOf(now, known.at), known)
        }
        val state = if (catalog == RegistryCall.Unauthorized || revisions == RegistryCall.Unauthorized) {
            RegistryState.REGISTRY_UNAUTHORIZED
        } else {
            RegistryState.REGISTRY_SILENT
        }
        return view(state, now, last.get())
    }

    private fun view(state: RegistryState, now: Instant, known: Known?): ProfileListView =
        ProfileListView(
            registry = state,
            checkedAt = now,
            catalog = known?.catalog,
            revisions = known?.revisions?.map { RevisionView(it, testRequestState(it, known.at)) },
            asOf = known?.at,
        )

    companion object {
        /**
         * 판정 시각 [at] 은 목록을 읽은 시각이다. PoC 에서 registry 와 운영 서비스는 같은 기계에 있다(스펙 §9).
         * 만료 시각과 같은 시각은 이미 만료다. registry 가 그 순간부터 다른 실행기에 다시 집어 준다(`claim_expires_at <= now`).
         */
        fun testRequestState(revision: RegistryRevision, at: Instant): TestRequestState {
            val request = revision.latestTestRequest ?: return TestRequestState.NONE
            if (request.completedAt != null) return TestRequestState.DONE
            if (request.claimedAt == null) return TestRequestState.WAITING
            val expires = request.claimExpiresAt?.let(Instant::parse) ?: return TestRequestState.RUNNING
            return if (!at.isBefore(expires)) TestRequestState.EXPIRED else TestRequestState.RUNNING
        }
    }
}
```

- [ ] **Step 4: 패치** — 아래를 `C:/Users/Eisen/AppData/Local/Temp/s1d-patches/s1d-2.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/s1d-patches/s1d-2.patch`.

```diff
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt
index fb29de2..ebae437 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt
@@ -154,7 +154,8 @@ class RegistryClient(
     private val token: String,
     private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
     private val json: ObjectMapper = jacksonObjectMapper(),
-) : RobotSource, TokenProbe, RobotWrites, AdapterSource, AdapterWrites, AutoCloseable {
+) : RobotSource, TokenProbe, RobotWrites, AdapterSource, AdapterWrites,
+    ProfileSource, CommissioningSource, ProfileWrites, BindingWrites, AutoCloseable {
 
     private val base = checkBaseUrl(baseUrl)
 
@@ -229,6 +230,43 @@ class RegistryClient(
         return send("POST", "/operations/adapter-instances", actor, json.writeValueAsString(body))
     }
 
+    override fun catalog(): RegistryCall<RegistryCatalog> =
+        get("/operations/skill-types") { json.readValue<RegistryCatalog?>(it) }
+
+    override fun revisions(): RegistryCall<List<RegistryRevision>> =
+        get("/operations/profile-revisions") { json.readValue<List<RegistryRevision>?>(it) }
+
+    /** 이력은 빼고(`history` 기본값) 이 사이트의 활성 바인딩만 읽는다. */
+    override fun bindings(siteId: String): RegistryCall<List<RegistryBinding>> {
+        val site = URLEncoder.encode(siteId, StandardCharsets.UTF_8)
+        return get("/diag/bindings?site=$site") { json.readValue<RegistryBindings?>(it)?.rows }
+    }
+
+    override fun software(siteId: String): RegistryCall<List<RegistrySoftware>> {
+        val site = URLEncoder.encode(siteId, StandardCharsets.UTF_8)
+        return get("/diag/software?site=$site") { json.readValue<List<RegistrySoftware>?>(it) }
+    }
+
+    override fun submit(document: ByteArray, actor: String): RegistryWrite =
+        sendBody("POST", "/operations/profile-revisions", actor, HttpRequest.BodyPublishers.ofByteArray(document))
+
+    override fun requestTest(profileRevisionId: Long, actor: String): RegistryWrite =
+        send("POST", "/operations/profile-revisions/$profileRevisionId/test-requests", actor, null)
+
+    override fun activate(profileRevisionId: Long, actor: String): RegistryWrite =
+        send("POST", "/operations/profile-revisions/$profileRevisionId/activation", actor, null)
+
+    override fun bind(robotId: String, adapterVersionId: Long, profileRevisionId: Long, actor: String): RegistryWrite =
+        send(
+            "POST", "/operations/robots/${segment(robotId)}/binding", actor,
+            json.writeValueAsString(
+                json.createObjectNode().put("adapter_version_id", adapterVersionId).put("profile_revision_id", profileRevisionId),
+            ),
+        )
+
+    override fun recordSiteNames(robotId: String, actor: String): RegistryWrite =
+        send("POST", "/operations/site-names?robot=${URLEncoder.encode(robotId, StandardCharsets.UTF_8)}", actor, null)
+
     /** [read] 가 널을 내면(본문 `null`) 값을 모르는 것이다. `OK` 인데 목록이 널인 보기를 만들지 않는다. */
     private fun <T : Any> get(path: String, read: (String) -> T?): RegistryCall<T> {
         val request = HttpRequest.newBuilder(URI.create(base + path))
@@ -252,13 +290,16 @@ class RegistryClient(
         }
     }
 
-    private fun send(method: String, path: String, actor: String, body: String?): RegistryWrite {
+    private fun send(method: String, path: String, actor: String, body: String?): RegistryWrite =
+        sendBody(method, path, actor, body?.let(HttpRequest.BodyPublishers::ofString))
+
+    private fun sendBody(method: String, path: String, actor: String, body: HttpRequest.BodyPublisher?): RegistryWrite {
         val request = HttpRequest.newBuilder(URI.create(base + path))
             .timeout(REQUEST_TIMEOUT)
             .header("Authorization", "Bearer $token")
             .header("X-Actor", actor)
             .header("Content-Type", "application/json")
-            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
+            .method(method, body ?: HttpRequest.BodyPublishers.noBody())
             .build()
         return try {
             val response = http.send(request, HttpResponse.BodyHandlers.ofString())
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt
index 47f31ea..66fd383 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt
@@ -1,8 +1,11 @@
 package dev.picasso.ops.service.robots
 
 import dev.picasso.ops.service.finding.Finding
+import dev.picasso.ops.service.registry.CommissioningSource
+import dev.picasso.ops.service.registry.RegistryBinding
 import dev.picasso.ops.service.registry.RegistryCall
 import dev.picasso.ops.service.registry.RegistryRobot
+import dev.picasso.ops.service.registry.RegistrySoftware
 import dev.picasso.ops.service.registry.RobotSource
 import dev.picasso.ops.service.registry.TokenProbe
 import java.time.Clock
@@ -13,11 +16,22 @@ import java.util.concurrent.atomic.AtomicReference
 /** 화면 전체 상태(스펙 §7.4). 기체별이 아니라 전역이다. */
 enum class RegistryState { OK, REGISTRY_SILENT, REGISTRY_UNAUTHORIZED }
 
-/** 기체 한 대. 원장 상태는 [robot] 의 `status` 그대로이고, 연결과 막힘은 운영 서비스가 계산한다(스펙 §7.3·§7.4). */
+/**
+ * 기체 한 대. 원장 상태는 [robot] 의 `status` 그대로이고, 연결과 막힘은 운영 서비스가 계산한다(스펙 §7.3·§7.4).
+ *
+ * [binding]·[commissioning]·[software] 는 P2·S1d 스펙 §8.1 의 칸이다. 시운전 출처([CommissioningSource])를 붙이지 않은
+ * 목록에서는 셋 다 널이다.
+ *
+ * @param binding 이 기체의 활성 바인딩. 없으면 널
+ * @param software 소프트웨어 대조. 시운전을 막지 않고 보이기만 한다
+ */
 data class RobotView(
     val robot: RegistryRobot,
     val connection: Connection,
     val blockers: List<Finding>,
+    val binding: RegistryBinding? = null,
+    val commissioning: Commissioning? = null,
+    val software: RegistrySoftware? = null,
 )
 
 /**
@@ -39,6 +53,9 @@ data class RobotListView(
  * 목록 읽기(`/diag/robots`)는 운영자 토큰 관문 밖이라 401 이 오지 않는다. 그래서 목록을 읽을 때마다
  * [token] 으로 관문 안의 읽기를 함께 불러, 토큰 불일치가 다음 폴링에 `OK` 로 덮이지 않게 한다.
  *
+ * [commissioning] 을 붙이면 기체 → 바인딩 → 소프트웨어 대조를 함께 읽고 셋 다 읽혀야 새 값으로 바꾼다(P2·S1d 스펙
+ * §8.1). 하나라도 못 읽으면 셋 다 직전 값이다. 다른 시각에 읽은 것을 섞으면 서로 맞지 않는 행이 보일 수 있다.
+ *
  * @param threshold 연결 칸의 기준 시간(스펙 §7.3). S1 에서는 설정값이다
  */
 class RobotListService(
@@ -47,38 +64,70 @@ class RobotListService(
     private val siteId: String,
     private val clock: Clock,
     private val threshold: Duration,
+    private val commissioning: CommissioningSource? = null,
 ) {
-    private data class Known(val robots: List<RegistryRobot>, val at: Instant)
+    private data class Known(
+        val robots: List<RegistryRobot>,
+        val bindings: List<RegistryBinding>?,
+        val software: List<RegistrySoftware>?,
+        val at: Instant,
+    )
 
     private val last = AtomicReference<Known?>(null)
 
     fun read(): RobotListView {
         val now = clock.instant()
-        return when (val call = source.robots(siteId)) {
-            is RegistryCall.Ok -> {
-                // 늦게 끝난 옛 읽기가 더 새 값을 덮지 않게 한다. 폴링과 조작 뒤 다시 읽기가 겹칠 수 있다.
-                val known = last.updateAndGet { prev ->
-                    if (prev != null && prev.at.isAfter(now)) prev else Known(call.value, now)
-                }!!
-                val state = when (token.operatorToken()) {
-                    RegistryCall.Unauthorized -> RegistryState.REGISTRY_UNAUTHORIZED
-                    else -> RegistryState.OK
-                }
-                // 더 새 값을 남겼으면 확인 시각도 그 시각으로 맞춘다. 화면은 두 시각이 다르면 직전 값으로 보인다.
-                view(state, maxOf(now, known.at), known)
+        val robots = source.robots(siteId)
+        if (robots !is RegistryCall.Ok) {
+            val state = if (robots == RegistryCall.Unauthorized) RegistryState.REGISTRY_UNAUTHORIZED else RegistryState.REGISTRY_SILENT
+            return view(state, now, last.get())
+        }
+        val bindings = commissioning?.bindings(siteId)
+        val software = if (bindings is RegistryCall.Ok) commissioning?.software(siteId) else null
+        if (commissioning != null && (bindings !is RegistryCall.Ok || software !is RegistryCall.Ok)) {
+            val state = if (bindings == RegistryCall.Unauthorized || software == RegistryCall.Unauthorized) {
+                RegistryState.REGISTRY_UNAUTHORIZED
+            } else {
+                RegistryState.REGISTRY_SILENT
             }
-            is RegistryCall.Silent -> view(RegistryState.REGISTRY_SILENT, now, last.get())
-            RegistryCall.Unauthorized -> view(RegistryState.REGISTRY_UNAUTHORIZED, now, last.get())
+            return view(state, now, last.get())
         }
+        val fresh = Known(
+            robots.value,
+            (bindings as? RegistryCall.Ok)?.value,
+            (software as? RegistryCall.Ok)?.value,
+            now,
+        )
+        // 늦게 끝난 옛 읽기가 더 새 값을 덮지 않게 한다. 폴링과 조작 뒤 다시 읽기가 겹칠 수 있다.
+        val known = last.updateAndGet { prev -> if (prev != null && prev.at.isAfter(now)) prev else fresh }!!
+        val state = when (token.operatorToken()) {
+            RegistryCall.Unauthorized -> RegistryState.REGISTRY_UNAUTHORIZED
+            else -> RegistryState.OK
+        }
+        // 더 새 값을 남겼으면 확인 시각도 그 시각으로 맞춘다. 화면은 두 시각이 다르면 직전 값으로 보인다.
+        return view(state, maxOf(now, known.at), known)
     }
 
     private fun view(state: RegistryState, now: Instant, known: Known?): RobotListView =
         RobotListView(
             registry = state,
             checkedAt = now,
-            robots = known?.robots?.map { robot ->
-                RobotView(robot, Blockers.connection(robot, known.at, threshold), Blockers.of(robot, known.at, threshold))
-            },
+            robots = known?.robots?.map { robot -> robotView(robot, known) },
             robotsAsOf = known?.at,
         )
+
+    private fun robotView(robot: RegistryRobot, known: Known): RobotView {
+        val connection = Blockers.connection(robot, known.at, threshold)
+        val base = Blockers.of(robot, known.at, threshold)
+        if (known.bindings == null) return RobotView(robot, connection, base)
+        val binding = known.bindings.firstOrNull { it.robotId == robot.robotId && it.active }
+        return RobotView(
+            robot = robot,
+            connection = connection,
+            blockers = base + CommissioningJudge.blockers(robot, binding, known.at),
+            binding = binding,
+            commissioning = CommissioningJudge.of(robot, binding),
+            software = known.software?.firstOrNull { it.robotId == robot.robotId },
+        )
+    }
 }
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :ops-service:test -q`
Expected: XML 기준 ops-service 95개 실패 0(기준 76 + 19). 기존 `RobotListServiceTest` 9개는 고치지 않고 통과한다.

- [ ] **Step 6: 커밋**

```bash
git add ops-service/src/main/kotlin/dev/picasso/ops/service/registry/ProfileRegistry.kt ops-service/src/main/kotlin/dev/picasso/ops/service/robots/Commissioning.kt ops-service/src/main/kotlin/dev/picasso/ops/service/profiles/ProfileListService.kt ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt ops-service/src/test/kotlin/dev/picasso/ops/service/CommissioningJudgeTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/ProfileListServiceTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RobotCommissioningListTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RegistryProfilesTest.kt
git commit -q -F - <<'EOF'
feat(ops-service): 개정판·바인딩 읽기와 시운전 판정, 막힘 4종

- 작업 묶음 커밋(Task 8 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 3: 조작 5개, 거절 대응표, 화면 API

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/operations/ProfileRejections.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/operations/ProfileOperations.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/web/ProfileOperationsController.kt`
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/web/ApiController.kt`(`GET /api/profiles`), `ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt`(빈 둘과 기체 목록의 시운전 출처)
- Test: `ProfileRejectionsTest.kt`, `ProfileOperationsTest.kt`

- [ ] **Step 1: 실패하는 시험 쓰기** — 두 파일.

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.operations.ProfileOp
import dev.picasso.ops.service.operations.ProfileRejections
import dev.picasso.ops.service.operations.Rejections
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 개정판·바인딩 조작의 거절 대응표(P2·S1d 스펙 §8.3)의 행마다 응답 코드와 본문을 넣는다. */
class ProfileRejectionsTest {

    private val at = Instant.parse("2026-10-08T00:00:00Z")
    private val json = ObjectMapper()

    private data class Row(val op: ProfileOp, val status: Int, val body: String, val kind: String, val owner: Owner, val inScreen: Boolean)

    private val table = listOf(
        Row(ProfileOp.SUBMIT, 400, """{"error":"프로파일을 읽을 수 없다"}""", ProfileRejections.PROFILE_UNREADABLE, Owner.ENGINEER, true),
        Row(ProfileOp.SUBMIT, 409, """{"received":2,"highest":2}""", ProfileRejections.REVISION_NOT_MONOTONIC, Owner.ENGINEER, true),
        Row(ProfileOp.REQUEST_TEST, 404, """{"error":"없는 개정판이다"}""", ProfileRejections.UNKNOWN_REVISION, Owner.ENGINEER, true),
        Row(ProfileOp.ACTIVATE, 404, """{"error":"없는 개정판이다"}""", ProfileRejections.UNKNOWN_REVISION, Owner.ENGINEER, true),
        Row(ProfileOp.REQUEST_TEST, 409, """{"status":"DRAFT"}""", ProfileRejections.REVISION_NOT_TESTABLE, Owner.ENGINEER, true),
        Row(ProfileOp.ACTIVATE, 409, """{"status":"VALIDATED","suites":{}}""", ProfileRejections.ACTIVATION_REFUSED, Owner.ENGINEER, true),
        Row(ProfileOp.BIND, 404, """{"reason":"UNKNOWN_ROBOT"}""", Rejections.UNKNOWN_ROBOT, Owner.ENGINEER, true),
        Row(ProfileOp.BIND, 404, """{"reason":"UNKNOWN_REVISION"}""", ProfileRejections.UNKNOWN_REVISION, Owner.ENGINEER, true),
        Row(ProfileOp.BIND, 404, """{"reason":"UNKNOWN_BUILD"}""", ProfileRejections.UNKNOWN_BUILD, Owner.ENGINEER, true),
        Row(ProfileOp.BIND, 409, """{"reason":"REVISION_NOT_ACTIVE","status":"VALIDATED"}""", ProfileRejections.REVISION_NOT_ACTIVE, Owner.ENGINEER, true),
        Row(ProfileOp.BIND, 409, """{"reason":"ROBOT_RETIRED"}""", ProfileRejections.ROBOT_RETIRED, Owner.OPERATOR, true),
        Row(ProfileOp.BIND, 409, """{"reason":"CONTRACT_TOO_OLD"}""", ProfileRejections.CONTRACT_TOO_OLD, Owner.ENGINEER, true),
        Row(ProfileOp.RECORD_SITE_NAMES, 404, """{"error":"활성 바인딩이 없다"}""", ProfileRejections.NO_ACTIVE_BINDING, Owner.ENGINEER, true),
        Row(ProfileOp.RECORD_SITE_NAMES, 409, """{"error":"등록할 것이 없다"}""", ProfileRejections.NOTHING_TO_REGISTER, Owner.NONE, true),
    )

    @Test
    fun `대응표의 행마다 종류와 해결 담당이 맞다`() {
        table.forEach { row ->
            val finding = ProfileRejections.of(row.op, row.status, json.readTree(row.body), at, "r1")
            assertEquals(Triple(row.kind, row.owner, row.inScreen), Triple(finding.kind, finding.owner, finding.inScreen), "$row")
            assertEquals(at, finding.checkedAt)
        }
    }

    @Test
    fun `바인딩·명칭 거절의 바로 가기는 그 기체이고 개정판 거절은 바로 가기가 없다`() {
        table.forEach { row ->
            val finding = ProfileRejections.of(row.op, row.status, json.readTree(row.body), at, "r1")
            if (row.op == ProfileOp.BIND || row.op == ProfileOp.RECORD_SITE_NAMES) assertEquals("r1", finding.target, "$row") else assertNull(finding.target, "$row")
        }
    }

    @Test
    fun `409 는 본문으로 가르고 관측값에 지금 최대 번호와 상태·스위트 결과를 싣는다`() {
        val notMonotonic = ProfileRejections.of(ProfileOp.SUBMIT, 409, json.readTree("""{"received":2,"highest":3}"""), at, null)
        assertEquals("HTTP 409, highest=3", notMonotonic.observed)
        val refused = ProfileRejections.of(
            ProfileOp.ACTIVATE, 409, json.readTree("""{"status":"VALIDATED","suites":{"CONTRACT":"FAIL"}}"""), at, null,
        )
        assertEquals("HTTP 409, status=VALIDATED, suites={\"CONTRACT\":\"FAIL\"}", refused.observed)
        val retired = ProfileRejections.of(ProfileOp.BIND, 409, json.readTree("""{"reason":"ROBOT_RETIRED"}"""), at, "r1")
        assertEquals("HTTP 409, reason=ROBOT_RETIRED", retired.observed)
    }

    @Test
    fun `표에 없는 응답과 모르는 reason 은 아는 종류로 접지 않고 화면 밖 조사로 둔다`() {
        listOf(
            Triple(ProfileOp.SUBMIT, 404, "{}"),
            Triple(ProfileOp.BIND, 409, """{"reason":"SOMETHING_NEW"}"""),
            Triple(ProfileOp.BIND, 404, "{}"),
            Triple(ProfileOp.ACTIVATE, 400, "{}"),
        ).forEach { (op, status, body) ->
            val finding = ProfileRejections.of(op, status, json.readTree(body), at, "r1")
            assertEquals(Rejections.UNCLASSIFIED to false, finding.kind to finding.inScreen, "$op $status $body")
        }
    }
}
```

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.ProfileOperations
import dev.picasso.ops.service.operations.ProfileRejections
import dev.picasso.ops.service.registry.BindingWrites
import dev.picasso.ops.service.registry.CommissioningSource
import dev.picasso.ops.service.registry.ProfileSource
import dev.picasso.ops.service.registry.ProfileWrites
import dev.picasso.ops.service.registry.RegistryBinding
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryCatalog
import dev.picasso.ops.service.registry.RegistryRevision
import dev.picasso.ops.service.registry.RegistrySoftware
import dev.picasso.ops.service.registry.RegistryTestRequest
import dev.picasso.ops.service.registry.RegistryWrite
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 개정판·바인딩 조작이 조작 기록에 남는 모양과 응답 없음 뒤 재조회의 «반영됨» 판정(P2·S1d 스펙 §8.2·§8.4). registry 는 대역이다. */
class ProfileOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val at = Instant.parse("2026-10-08T00:00:00Z")
    private val kim = Actor(Mode.ENGINEER, "kim")

    private var answer: RegistryWrite = RegistryWrite.NoResponse("응답 없음")
    private var revisions: RegistryCall<List<RegistryRevision>> = RegistryCall.Ok(emptyList())
    private var bindings: RegistryCall<List<RegistryBinding>> = RegistryCall.Ok(emptyList())
    private val sent = mutableListOf<String>()
    private var sentDocument: ByteArray? = null

    private val profileWrites = object : ProfileWrites {
        override fun submit(document: ByteArray, actor: String) = answer.also { sentDocument = document; sent += "submit $actor" }
        override fun requestTest(profileRevisionId: Long, actor: String) = answer.also { sent += "requestTest $profileRevisionId $actor" }
        override fun activate(profileRevisionId: Long, actor: String) = answer.also { sent += "activate $profileRevisionId $actor" }
    }
    private val bindingWrites = object : BindingWrites {
        override fun bind(robotId: String, adapterVersionId: Long, profileRevisionId: Long, actor: String) =
            answer.also { sent += "bind $robotId $adapterVersionId $profileRevisionId $actor" }
        override fun recordSiteNames(robotId: String, actor: String) = answer.also { sent += "names $robotId $actor" }
    }
    private val profiles = object : ProfileSource {
        override fun catalog() = RegistryCall.Ok(RegistryCatalog("0.9.0"))
        override fun revisions() = revisions
    }
    private val commissioning = object : CommissioningSource {
        override fun bindings(siteId: String) = bindings
        override fun software(siteId: String): RegistryCall<List<RegistrySoftware>> = RegistryCall.Ok(emptyList())
    }

    private val operations = ProfileOperations(
        profileWrites, bindingWrites, profiles, commissioning, log, "site-01", Clock.fixed(at, ZoneOffset.UTC), requeryDelay = Duration.ZERO,
    )

    private val document = """{"vendor":"picasso-ref","model":"humanoid-a","revision":2,"skills":[]}"""
    private val hash = MessageDigest.getInstance("SHA-256").digest(document.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun revision(id: Long, status: String = "VALIDATED", hash: String = this.hash, request: RegistryTestRequest? = null) =
        RegistryRevision(id, "picasso-ref", "humanoid-a", 2, status, documentHash = hash, latestTestRequest = request)

    private fun binding(build: Long, revision: Long, siteNames: String = "UNREGISTERED") = RegistryBinding(
        robotId = "r1", vendor = "picasso-ref", model = "humanoid-a", profileRevisionId = revision, revision = 2, adapterName = "acme/fleet",
        adapterVersion = "1.0.0", conformanceStatus = "UNTESTED", active = true, siteNames = siteNames, adapterVersionId = build,
        boundBy = "engineer/kim", boundAt = "t",
    )

    /** 가장 늦게 남은 행의 결과. 확인 행이 요청 행 뒤에 붙는다. */
    private fun latest() = log.list().first().result

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `제출은 본문 바이트를 그대로 넘기고 기종·번호를 대상으로 남긴다`() {
        answer = RegistryWrite.Answered(201, """{"profile_revision_id":5}""")
        val bytes = document.toByteArray()
        assertEquals(OperationResult.SUCCEEDED, operations.submit(kim, bytes).result)
        assertContentEquals(bytes, sentDocument)
        assertEquals(listOf("submit engineer/kim"), sent)
        val row = log.list().single()
        assertEquals("profile picasso-ref/humanoid-a#2", row.target)
        assertTrue(row.request.contains(hash), row.request)
    }

    @Test
    fun `대상 칸을 읽지 못하는 문서는 profile 물음표로 남기고 그대로 보낸다`() {
        answer = RegistryWrite.Answered(400, """{"error":"프로파일을 읽을 수 없다"}""")
        val outcome = operations.submit(kim, "{not json".toByteArray())
        assertEquals(ProfileRejections.PROFILE_UNREADABLE, outcome.rejection!!.kind)
        assertEquals("profile ?", log.list().single().target)
    }

    @Test
    fun `응답 없는 제출은 같은 기종·번호가 같은 문서 해시로 있어야 반영됨이다`() {
        revisions = RegistryCall.Ok(listOf(revision(5)))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.submit(kim, document.toByteArray()).confirmation)
        // 확인 행에는 재조회에서 본 개정판 id 와 상태가 남는다(스펙 §8.4 마지막 문단).
        val confirmed = log.list().first()
        assertTrue(confirmed.registryResponse!!.contains("\"profile_revision_id\": 5"), confirmed.registryResponse)
        assertTrue(confirmed.registryResponse!!.contains("\"status\": \"VALIDATED\""), confirmed.registryResponse)

        revisions = RegistryCall.Ok(listOf(revision(5, hash = "other")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.submit(kim, document.toByteArray()).confirmation)
    }

    @Test
    fun `응답 없는 시험 요청은 열린 요청이 있거나 보낸 뒤에 만든 끝난 요청이 있어야 반영됨이다`() {
        fun request(requestedAt: String, completed: String?) = RegistryTestRequest(9, "engineer/kim", requestedAt, completedAt = completed)

        revisions = RegistryCall.Ok(listOf(revision(5, request = request("2026-10-07T23:00:00Z", null))))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.requestTest(kim, 5).confirmation)

        revisions = RegistryCall.Ok(listOf(revision(5, request = request("2026-10-08T00:00:00.5Z", "2026-10-08T00:00:01Z"))))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.requestTest(kim, 5).confirmation)

        revisions = RegistryCall.Ok(listOf(revision(5, request = request("2026-10-07T23:00:00Z", "2026-10-07T23:01:00Z"))))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.requestTest(kim, 5).confirmation)
        assertEquals("revision 5", log.list().first().target)
    }

    @Test
    fun `응답 없는 활성화는 그 개정판이 ACTIVE 여야 반영됨이다`() {
        revisions = RegistryCall.Ok(listOf(revision(5, status = "ACTIVE")))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.activate(kim, 5).confirmation)
        revisions = RegistryCall.Ok(listOf(revision(5, status = "TESTED")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.activate(kim, 5).confirmation)
        assertEquals(listOf("activate 5 engineer/kim", "activate 5 engineer/kim"), sent)
    }

    @Test
    fun `응답 없는 바인딩은 그 기체의 활성 바인딩이 요청한 빌드와 개정판이어야 반영됨이다`() {
        bindings = RegistryCall.Ok(listOf(binding(7, 5)))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.bind(kim, "r1", 7, 5).confirmation)
        bindings = RegistryCall.Ok(listOf(binding(7, 4)))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.bind(kim, "r1", 7, 5).confirmation)
        bindings = RegistryCall.Ok(listOf(binding(8, 5)))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.bind(kim, "r1", 7, 5).confirmation)
        assertEquals("robot r1", log.list().first().target)
        assertEquals("bind r1 7 5 engineer/kim", sent.first())
    }

    @Test
    fun `응답 없는 명칭 기록은 그 기체의 명칭 상태가 UNREGISTERED 가 아니어야 반영됨이다`() {
        bindings = RegistryCall.Ok(listOf(binding(7, 5, "CLAIMED")))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.recordSiteNames(kim, "r1").confirmation)
        bindings = RegistryCall.Ok(listOf(binding(7, 5, "UNREGISTERED")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.recordSiteNames(kim, "r1").confirmation)
        bindings = RegistryCall.Ok(emptyList())
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.recordSiteNames(kim, "r1").confirmation)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, latest())
    }

    @Test
    fun `바인딩 거절은 그 기체로 바로 가는 대응표 판정이다`() {
        answer = RegistryWrite.Answered(409, """{"reason":"ROBOT_RETIRED","error":"퇴역한 기체다"}""")
        val outcome = operations.bind(kim, "r1", 7, 5)
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals(ProfileRejections.ROBOT_RETIRED to "r1", outcome.rejection!!.kind to outcome.rejection!!.target)
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :ops-service:compileTestKotlin -q`
Expected: 컴파일 실패(`ProfileRejections`, `ProfileOperations`, `ProfileOp` 를 모름).

- [ ] **Step 3: 새 파일 셋**

```kotlin
package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import java.time.Instant

/** 개정판·바인딩 조작 5가지(P2·S1d 스펙 §8.2). */
enum class ProfileOp { SUBMIT, REQUEST_TEST, ACTIVATE, BIND, RECORD_SITE_NAMES }

/**
 * 개정판·바인딩 조작의 거절 대응표(P2·S1d 스펙 §8.3). 409 와 바인딩의 404 는 본문으로 가르며 하나로 다루지 않는다.
 * 표에 없는 응답은 [Rejections.UNCLASSIFIED] 로 두어 엔지니어가 본다. 아는 종류로 접지 않는다.
 *
 * 개정판 거절의 바로 가기는 없다. 프로파일 구역이 같은 영역에 늘 보인다. 바인딩·명칭 거절의 바로 가기는 그 기체다.
 */
object ProfileRejections {

    const val PROFILE_UNREADABLE = "PROFILE_UNREADABLE"
    const val REVISION_NOT_MONOTONIC = "REVISION_NOT_MONOTONIC"
    const val UNKNOWN_REVISION = "UNKNOWN_REVISION"
    const val REVISION_NOT_TESTABLE = "REVISION_NOT_TESTABLE"
    const val ACTIVATION_REFUSED = "ACTIVATION_REFUSED"
    const val UNKNOWN_BUILD = "UNKNOWN_BUILD"
    const val REVISION_NOT_ACTIVE = "REVISION_NOT_ACTIVE"
    const val ROBOT_RETIRED = "ROBOT_RETIRED"
    const val CONTRACT_TOO_OLD = "CONTRACT_TOO_OLD"
    const val NO_ACTIVE_BINDING = "NO_ACTIVE_BINDING"
    const val NOTHING_TO_REGISTER = "NOTHING_TO_REGISTER"

    /** @param robotId 바인딩·명칭 기록의 대상 기체. 개정판 조작이면 널 */
    fun of(op: ProfileOp, status: Int, body: JsonNode?, checkedAt: Instant, robotId: String?): Finding {
        val reason = body?.get("reason")?.asText()
        val error = body?.get("error")?.asText()
        val observedExtra = when {
            op == ProfileOp.SUBMIT && status == 409 -> body?.get("highest")?.let { "highest=${it.asText()}" }
            op == ProfileOp.ACTIVATE && status == 409 -> listOfNotNull(
                body?.get("status")?.let { "status=${it.asText()}" },
                body?.get("suites")?.let { "suites=$it" },
            ).joinToString(", ").ifEmpty { null }
            op == ProfileOp.REQUEST_TEST && status == 409 -> body?.get("status")?.let { "status=${it.asText()}" }
            else -> null
        }
        val target = if (op == ProfileOp.BIND || op == ProfileOp.RECORD_SITE_NAMES) robotId else null

        fun finding(kind: String, action: String, owner: Owner = Owner.ENGINEER, inScreen: Boolean = true) =
            Finding(
                kind = kind,
                observed = listOfNotNull("HTTP $status", reason?.let { "reason=$it" }, observedExtra, error).joinToString(", "),
                expected = "201 또는 200",
                checkedAt = checkedAt,
                owner = owner,
                inScreen = inScreen,
                action = action,
                target = target,
            )

        return when {
            op == ProfileOp.SUBMIT && status == 400 -> finding(PROFILE_UNREADABLE, "문서를 고쳐서 다시")
            op == ProfileOp.SUBMIT && status == 409 -> finding(REVISION_NOT_MONOTONIC, "번호를 올려 다시")
            (op == ProfileOp.REQUEST_TEST || op == ProfileOp.ACTIVATE) && status == 404 ->
                finding(UNKNOWN_REVISION, "목록 새로 읽기")
            op == ProfileOp.REQUEST_TEST && status == 409 -> finding(REVISION_NOT_TESTABLE, "문서를 고쳐 새 번호로 제출")
            op == ProfileOp.ACTIVATE && status == 409 -> finding(ACTIVATION_REFUSED, "시험 요청 또는 시험 결과 확인")
            op == ProfileOp.BIND && status == 404 && reason == "UNKNOWN_ROBOT" -> finding(Rejections.UNKNOWN_ROBOT, "목록 새로 읽기")
            op == ProfileOp.BIND && status == 404 && reason == "UNKNOWN_REVISION" -> finding(UNKNOWN_REVISION, "프로파일 목록 새로 읽기")
            op == ProfileOp.BIND && status == 404 && reason == "UNKNOWN_BUILD" -> finding(UNKNOWN_BUILD, "빌드 목록 새로 읽기")
            op == ProfileOp.BIND && status == 409 && reason == "REVISION_NOT_ACTIVE" -> finding(REVISION_NOT_ACTIVE, "활성 개정판 고르기")
            op == ProfileOp.BIND && status == 409 && reason == "ROBOT_RETIRED" -> finding(ROBOT_RETIRED, "복귀 뒤 다시", Owner.OPERATOR)
            op == ProfileOp.BIND && status == 409 && reason == "CONTRACT_TOO_OLD" ->
                finding(CONTRACT_TOO_OLD, "계약 semver 가 높은 빌드 고르기")
            op == ProfileOp.RECORD_SITE_NAMES && status == 404 -> finding(NO_ACTIVE_BINDING, "바인딩 먼저")
            op == ProfileOp.RECORD_SITE_NAMES && status == 409 ->
                finding(NOTHING_TO_REGISTER, "고칠 것 없음(이 기체의 스킬은 명칭을 쓰지 않음)", Owner.NONE)
            else -> finding(Rejections.UNCLASSIFIED, "registry 응답 조사", inScreen = false)
        }
    }
}
```

```kotlin
package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.registry.BindingWrites
import dev.picasso.ops.service.registry.CommissioningSource
import dev.picasso.ops.service.registry.ProfileSource
import dev.picasso.ops.service.registry.ProfileWrites
import dev.picasso.ops.service.registry.map
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 개정판 제출·시험 요청·활성화와 바인딩·명칭 기록(P2·S1d 스펙 §8.2). 기록과 응답 없음 뒤 재조회는 [OperationRunner] 가 한다.
 *
 * «반영됨» 의 판정(스펙 §8.4)은 registry 가 같은 요청을 어떻게 다루는지에 맞춘다. 다섯 다 멱등이다(스펙 §10).
 * - 제출: 목록에 그 기종·번호가 같은 문서 해시로 있다. 해시는 registry 와 같은 값(본문 바이트의 SHA-256)이다.
 * - 시험 요청: 그 개정판에 끝나지 않은 요청이 있거나, 최신 요청이 이 조작을 보낸 시각 뒤에 만들어졌다. 실행기가 가상
 *   시계로 돌아 1초 뒤 재조회 때 이미 끝났을 수 있고, 멱등 200 은 조작 전에 만든 열린 요청을 돌려주기 때문이다.
 * - 활성화: 그 개정판이 `ACTIVE` 다.
 * - 바인딩: 그 기체의 활성 바인딩이 요청한 빌드 id 와 개정판 id 다.
 * - 명칭 기록: 그 기체의 명칭 상태가 `UNREGISTERED` 가 아니다.
 */
class ProfileOperations(
    private val profileWrites: ProfileWrites,
    private val bindingWrites: BindingWrites,
    private val profiles: ProfileSource,
    private val commissioning: CommissioningSource,
    log: OperationLog,
    private val siteId: String,
    private val clock: Clock,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = ObjectMapper(),
) {
    private val runner = OperationRunner(log, clock, requeryDelay, json)

    /**
     * [document] 를 바이트 그대로 registry 에 넘긴다. 조작 기록의 대상 칸을 채우려고 `vendor`·`model`·`revision` 만 읽고,
     * 읽지 못하면 `profile ?` 로 남긴다. 문서의 옳고 그름은 registry 가 판정한다.
     */
    fun submit(actor: Actor, document: ByteArray): OperationOutcome {
        val hash = sha256(document)
        val coordinate = runCatching { json.readTree(document) }.getOrNull()?.let { root ->
            val vendor = root.get("vendor")?.takeIf { it.isTextual }?.asText()
            val model = root.get("model")?.takeIf { it.isTextual }?.asText()
            val revision = root.get("revision")?.takeIf { it.isInt }?.asInt()
            if (vendor != null && model != null && revision != null) Triple(vendor, model, revision) else null
        }
        val request = json.createObjectNode()
            .put("op", ProfileOp.SUBMIT.name)
            .put("document_sha256", hash)
            .put("document_bytes", document.size)
        val target = coordinate?.let { (v, m, r) -> "profile $v/$m#$r" } ?: "profile ?"
        return runner.run(
            actor, target, request, reason = null,
            rejection = { status, body, at -> ProfileRejections.of(ProfileOp.SUBMIT, status, body, at, null) },
            recheck = {
                profiles.revisions().map { revisions ->
                    val seen = coordinate?.let { (v, m, r) -> revisions.firstOrNull { it.vendor == v && it.model == m && it.revision == r } }
                    Recheck(
                        seen != null && seen.documentHash == hash,
                        seen?.let { json.createObjectNode().put("profile_revision_id", it.profileRevisionId).put("status", it.status) },
                    )
                }
            },
        ) { profileWrites.submit(document, actor.header()) }
    }

    fun requestTest(actor: Actor, profileRevisionId: Long): OperationOutcome {
        val sentAt = clock.instant()
        val request = json.createObjectNode()
            .put("op", ProfileOp.REQUEST_TEST.name)
            .put("profile_revision_id", profileRevisionId)
        return runner.run(
            actor, "revision $profileRevisionId", request, reason = null,
            rejection = { status, body, at -> ProfileRejections.of(ProfileOp.REQUEST_TEST, status, body, at, null) },
            recheck = {
                profiles.revisions().map { revisions ->
                    val latest = revisions.firstOrNull { it.profileRevisionId == profileRevisionId }?.latestTestRequest
                    val applied = latest != null &&
                        (latest.completedAt == null || Instant.parse(latest.requestedAt).isAfter(sentAt))
                    Recheck(applied, latest?.let { json.createObjectNode().put("requested_at", it.requestedAt) })
                }
            },
        ) { profileWrites.requestTest(profileRevisionId, actor.header()) }
    }

    fun activate(actor: Actor, profileRevisionId: Long): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", ProfileOp.ACTIVATE.name)
            .put("profile_revision_id", profileRevisionId)
        return runner.run(
            actor, "revision $profileRevisionId", request, reason = null,
            rejection = { status, body, at -> ProfileRejections.of(ProfileOp.ACTIVATE, status, body, at, null) },
            recheck = {
                profiles.revisions().map { revisions ->
                    val seen = revisions.firstOrNull { it.profileRevisionId == profileRevisionId }
                    Recheck(seen?.status == "ACTIVE", seen?.let { json.createObjectNode().put("status", it.status) })
                }
            },
        ) { profileWrites.activate(profileRevisionId, actor.header()) }
    }

    fun bind(actor: Actor, robotId: String, adapterVersionId: Long, profileRevisionId: Long): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", ProfileOp.BIND.name)
            .put("robot_id", robotId)
            .put("adapter_version_id", adapterVersionId)
            .put("profile_revision_id", profileRevisionId)
        return runner.run(
            actor, "robot $robotId", request, reason = null,
            rejection = { status, body, at -> ProfileRejections.of(ProfileOp.BIND, status, body, at, robotId) },
            recheck = {
                commissioning.bindings(siteId).map { bindings ->
                    val seen = bindings.firstOrNull { it.robotId == robotId && it.active }
                    Recheck(
                        seen != null && seen.adapterVersionId == adapterVersionId && seen.profileRevisionId == profileRevisionId,
                        seen?.let {
                            json.createObjectNode()
                                .put("adapter_version_id", it.adapterVersionId)
                                .put("profile_revision_id", it.profileRevisionId)
                        },
                    )
                }
            },
        ) { bindingWrites.bind(robotId, adapterVersionId, profileRevisionId, actor.header()) }
    }

    fun recordSiteNames(actor: Actor, robotId: String): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", ProfileOp.RECORD_SITE_NAMES.name)
            .put("robot_id", robotId)
        return runner.run(
            actor, "robot $robotId", request, reason = null,
            rejection = { status, body, at -> ProfileRejections.of(ProfileOp.RECORD_SITE_NAMES, status, body, at, robotId) },
            recheck = {
                commissioning.bindings(siteId).map { bindings ->
                    val seen = bindings.firstOrNull { it.robotId == robotId && it.active }
                    Recheck(
                        seen != null && seen.siteNames != "UNREGISTERED",
                        seen?.let { json.createObjectNode().put("site_names", it.siteNames) },
                    )
                }
            },
        ) { bindingWrites.recordSiteNames(robotId, actor.header()) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
```

```kotlin
package dev.picasso.ops.service.web

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.operations.ProfileOperations
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * [adapterVersionId]·[profileRevisionId] 를 널로 받는 이유: 널이 안 되는 `Long` 으로 두면 본문에 칸이 없을 때 Jackson 이
 * 0 으로 읽어 그대로 registry 에 간다. 빈 칸은 운영 서비스가 먼저 막는다(`BINDING_TARGET_REQUIRED`).
 */
data class BindBody(val adapterVersionId: Long? = null, val profileRevisionId: Long? = null)

/**
 * 개정판·바인딩 조작 API(P2·S1d 스펙 §8.2). 다섯 다 엔지니어 모드에서 한다. 관문, 사전 거절을 조작 기록에 남기지 않는 것,
 * registry 의 답을 200 과 결과 본문으로 돌려주는 것은 [RobotOperationsController] 와 같다.
 *
 * 제출 본문은 바이트 그대로 받는다. 운영 서비스가 다시 직렬화하면 registry 가 매기는 문서 해시가 달라져, 같은 문서의
 * 재제출이 다른 문서로 보이고 재조회의 반영 판정도 틀린다. 본문이 없는 조작(시험 요청, 활성화, 명칭 기록)은 퇴역 복귀의
 * DELETE 처럼 커스텀 헤더만으로 다른 출처를 막는다.
 */
@RestController
class ProfileOperationsController(private val operations: ProfileOperations) {

    @PostMapping("/api/profile-revisions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) document: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        if (document == null || document.isEmpty()) {
            return@guarded reject(HttpStatus.BAD_REQUEST, "PROFILE_REQUIRED", "제출할 프로파일 문서가 없다")
        }
        ResponseEntity.ok(operations.submit(actor, document))
    }

    @PostMapping("/api/profile-revisions/{profileRevisionId}/test-requests")
    fun requestTest(
        @PathVariable profileRevisionId: Long,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        ResponseEntity.ok(operations.requestTest(actor, profileRevisionId))
    }

    @PostMapping("/api/profile-revisions/{profileRevisionId}/activation")
    fun activate(
        @PathVariable profileRevisionId: Long,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        ResponseEntity.ok(operations.activate(actor, profileRevisionId))
    }

    @PostMapping("/api/robots/{robotId}/binding", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun bind(
        @PathVariable robotId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: BindBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        val build = body.adapterVersionId
        val revision = body.profileRevisionId
        if (build == null || revision == null) {
            return@guarded reject(HttpStatus.BAD_REQUEST, "BINDING_TARGET_REQUIRED", "adapterVersionId 와 profileRevisionId 가 필요하다")
        }
        ResponseEntity.ok(operations.bind(actor, robotId, build, revision))
    }

    @PostMapping("/api/robots/{robotId}/site-names")
    fun recordSiteNames(
        @PathVariable robotId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        ResponseEntity.ok(operations.recordSiteNames(actor, robotId))
    }
}
```

- [ ] **Step 4: 패치** — 아래를 `C:/Users/Eisen/AppData/Local/Temp/s1d-patches/s1d-3.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/s1d-patches/s1d-3.patch`.

```diff
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
index 8a394b5..981a7d2 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
@@ -1,5 +1,7 @@
 package dev.picasso.ops.service
 
+import dev.picasso.ops.service.operations.ProfileOperations
+import dev.picasso.ops.service.profiles.ProfileListService
 import dev.picasso.ops.service.adapters.AdapterListService
 import dev.picasso.ops.service.log.OperationLog
 import dev.picasso.ops.service.operations.AdapterOperations
@@ -54,7 +56,7 @@ open class OpsApplication {
         siteId: SiteId,
         clock: Clock,
         @Value("\${ops.connection.threshold}") threshold: Duration,
-    ): RobotListService = RobotListService(registry, registry, siteId.value, clock, threshold)
+    ): RobotListService = RobotListService(registry, registry, siteId.value, clock, threshold, commissioning = registry)
 
     @Bean
     open fun adapterList(registry: RegistryClient, siteId: SiteId, clock: Clock): AdapterListService =
@@ -76,6 +78,17 @@ open class OpsApplication {
         clock: Clock,
     ): AdapterOperations = AdapterOperations(registry, registry, log, siteId.value, clock)
 
+    @Bean
+    open fun profileList(registry: RegistryClient, clock: Clock): ProfileListService = ProfileListService(registry, clock)
+
+    @Bean
+    open fun profileOperations(
+        registry: RegistryClient,
+        log: OperationLog,
+        siteId: SiteId,
+        clock: Clock,
+    ): ProfileOperations = ProfileOperations(registry, registry, registry, registry, log, siteId.value, clock)
+
     /** [migrated] 는 쓰지 않는다. 받는 것만으로 ops 마이그레이션 뒤에 이 빈이 만들어진다. */
     @Bean
     open fun operationLog(
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/web/ApiController.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/web/ApiController.kt
index fb74aa4..7b75c10 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/web/ApiController.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/web/ApiController.kt
@@ -4,6 +4,8 @@ import dev.picasso.ops.service.adapters.AdapterListService
 import dev.picasso.ops.service.adapters.AdapterListView
 import dev.picasso.ops.service.log.OperationLog
 import dev.picasso.ops.service.log.OperationRecord
+import dev.picasso.ops.service.profiles.ProfileListService
+import dev.picasso.ops.service.profiles.ProfileListView
 import dev.picasso.ops.service.robots.RobotListService
 import dev.picasso.ops.service.robots.RobotListView
 import org.springframework.web.bind.annotation.GetMapping
@@ -17,6 +19,7 @@ class ApiController(
     private val robots: RobotListService,
     private val adapters: AdapterListService,
     private val operations: OperationLog,
+    private val profiles: ProfileListService,
 ) {
     @GetMapping("/robots")
     fun robots(): RobotListView = robots.read()
@@ -24,6 +27,10 @@ class ApiController(
     @GetMapping("/adapters")
     fun adapters(): AdapterListView = adapters.read()
 
+    /** 카탈로그 요약과 개정판 목록(P2·S1d 스펙 §8.1). */
+    @GetMapping("/profiles")
+    fun profiles(): ProfileListView = profiles.read()
+
     @GetMapping("/operations")
     fun operations(): List<OperationRecord> = operations.list()
 }
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :ops-service:test -q`
Expected: XML 기준 ops-service 107개 실패 0.

- [ ] **Step 6: 커밋과 대조**

```bash
git add ops-service/src/main/kotlin/dev/picasso/ops/service/operations/ProfileRejections.kt ops-service/src/main/kotlin/dev/picasso/ops/service/operations/ProfileOperations.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/ProfileOperationsController.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/ApiController.kt ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt ops-service/src/test/kotlin/dev/picasso/ops/service/ProfileRejectionsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/ProfileOperationsTest.kt
git commit -q -F - <<'EOF'
feat(ops-service): 개정판·바인딩 조작 5개와 거절 대응표

- 작업 묶음 커밋(Task 8 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
bash C:/Users/Eisen/AppData/Local/Temp/s1d-cmp.sh $(git diff --name-only f84e095 HEAD -- . ':!picasso' ':!docs')
git -C picasso log --oneline -1
```
Expected: 23줄 모두 «같음», 서브모듈은 `41beedb`.

---

## Chunk 2: 통합 시험, 화면, Playwright, PR

### Task 4: 통합 시험(시운전 흐름)

**Files:**
- Test: `e2e/src/test/kotlin/dev/picasso/ops/e2e/CommissioningTest.kt`
- Modify: `e2e/src/test/kotlin/dev/picasso/ops/e2e/LifecycleTest.kt`(바인딩 안 된 기체의 `UNBOUND`)

- [ ] **Step 1: 시험 쓰기와 기존 시험 기대값** — 새 시험을 쓰고, 아래 패치를 `C:/Users/Eisen/AppData/Local/Temp/s1d-patches/s1d-e2e.patch` 로 저장해 `git apply` 한다.

```kotlin
package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.site.Site
import dev.picasso.registry.PostgresSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S1d 완료 판정(P2·S1d 스펙 §3). 운영 서비스 API 로 제출 → 시험 요청 → 실행기 3종 PASS(`TESTED`) → 활성화 → 바인딩 →
 * 명칭 기록 → 시운전 완료(`humanoid-01`). `quadruped-01` 은 명칭을 티칭하지 않아 `SITE_NAMES_CONTRADICTED` 로 막히고,
 * 현장에서 다시 티칭하면 풀린다. 거절은 대응표(§8.3)대로 보인다.
 *
 * 순서가 있다. 앞 시험이 만든 개정판·빌드·바인딩을 뒤 시험이 쓴다. 실행기는 실제 시간으로 폴링하므로 시험 결과는 실제
 * 시간으로 기다린다(상한 60초).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CommissioningTest {

    companion object {
        private const val HUMANOID = "humanoid-01"
        private const val QUADRUPED = "quadruped-01"
        private lateinit var stack: E2eStack
        private var build = 0L
        private val revisions = mutableMapOf<String, Long>()

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

    private fun document(model: String): String =
        Files.readString(E2eStack.root.resolve("picasso/profile/profiles/$model.json")).replace("\r\n", "\n")

    private fun engineer(path: String, body: String? = null): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        assertEquals(200, reply.status, "$path ${reply.body}")
        return reply.body!!
    }

    private fun rejection(path: String, body: String? = null, status: Int): JsonNode {
        val outcome = engineer(path, body)
        assertEquals("REJECTED" to status, outcome["result"].asText() to outcome["registryStatus"].asInt(), "$outcome")
        return outcome["rejection"]
    }

    private fun robot(id: String): JsonNode = stack.get("/api/robots")["robots"].single { it["robot"]["robotId"].asText() == id }

    private fun kinds(id: String): List<String> = robot(id)["blockers"].map { it["kind"].asText() }

    private fun revision(id: Long): JsonNode =
        stack.get("/api/profiles")["revisions"].single { it["revision"]["profileRevisionId"].asLong() == id }

    @Test
    @Order(1)
    fun `기체를 선언하면 바인딩이 없어 시운전 미완이고 UNBOUND 로 막힌다`() {
        listOf(HUMANOID to "HA-0001", QUADRUPED to "QB-0001").forEach { (id, serial) ->
            assertEquals(201, engineer("/api/robots", """{"robotId":"$id","serialNumber":"$serial"}""")["registryStatus"].asInt())
        }
        stack.site.advance(Duration.ofSeconds(31))

        val humanoid = robot(HUMANOID)
        assertEquals("CONFIRMED", humanoid["robot"]["status"].asText())
        assertEquals("INCOMPLETE", humanoid["commissioning"]["state"].asText())
        assertEquals(listOf("UNBOUND"), kinds(HUMANOID))
        assertEquals("ENGINEER", humanoid["blockers"].single()["owner"].asText())
        assertTrue(humanoid["binding"].isNull, "$humanoid")
    }

    @Test
    @Order(2)
    fun `제출하면 VALIDATED 로 보이고 같은 문서를 다시 내면 같은 개정판이다`() {
        build = engineer("/api/adapters", """{"vendor":"acme","name":"fleet"}""").let {
            val adapterId = stack.get("/api/adapters")["adapters"].single()["adapterId"].asLong()
            engineer("/api/adapters/$adapterId/versions", """{"version":"1.0.0","contractSemver":"0.9.0"}""")
            stack.get("/api/adapters")["adapters"].single()["versions"].single()["adapterVersionId"].asLong()
        }

        listOf("humanoid-a", "quadruped-b").forEach { model ->
            val first = engineer("/api/profile-revisions", document(model))
            assertEquals("SUCCEEDED" to 201, first["result"].asText() to first["registryStatus"].asInt(), "$first")
            val again = engineer("/api/profile-revisions", document(model))
            assertEquals("SUCCEEDED" to 200, again["result"].asText() to again["registryStatus"].asInt(), "$again")
        }

        val profiles = stack.get("/api/profiles")
        assertEquals("OK", profiles["registry"].asText())
        assertEquals("0.9.0", profiles["catalog"]["contractSemver"].asText())
        assertTrue(profiles["catalog"]["skillTypes"].size() > 0, "$profiles")
        profiles["revisions"].forEach { row ->
            assertEquals("VALIDATED", row["revision"]["status"].asText(), "$row")
            assertEquals("NONE", row["testRequest"].asText())
            revisions[row["revision"]["model"].asText()] = row["revision"]["profileRevisionId"].asLong()
        }
        assertEquals(setOf("humanoid-a", "quadruped-b"), revisions.keys)
    }

    @Test
    @Order(3)
    fun `시험 전 활성화는 ACTIVATION_REFUSED 이고 시험을 요청하면 현장 실행기가 TESTED 로 올린다`() {
        val refused = rejection("/api/profile-revisions/${revisions["humanoid-a"]}/activation", status = 409)
        assertEquals("ACTIVATION_REFUSED", refused["kind"].asText())
        assertTrue("status=VALIDATED" in refused["observed"].asText(), "$refused")

        revisions.values.forEach { id ->
            val requested = engineer("/api/profile-revisions/$id/test-requests")
            assertEquals("SUCCEEDED" to 201, requested["result"].asText() to requested["registryStatus"].asInt(), "$requested")
        }
        val deadline = Instant.now().plusSeconds(60)
        while (revisions.values.any { revision(it)["revision"]["status"].asText() != "TESTED" } && Instant.now().isBefore(deadline)) {
            Thread.sleep(500)
        }
        revisions.values.forEach { id ->
            val row = revision(id)
            assertEquals("TESTED", row["revision"]["status"].asText(), "$row")
            assertEquals("DONE", row["testRequest"].asText())
            listOf("CONTRACT", "NEGATIVE", "DETERMINISM").forEach { suite ->
                assertEquals("PASS" to Site.RUNNER_NAME, row["revision"]["suites"][suite].let { it["result"].asText() to it["ranBy"].asText() })
            }
        }
    }

    @Test
    @Order(4)
    fun `활성화와 바인딩 뒤에는 명칭 기록이 빠져 SITE_NAMES_UNREGISTERED 로 막힌다`() {
        revisions.values.forEach { id ->
            assertEquals("SUCCEEDED", engineer("/api/profile-revisions/$id/activation")["result"].asText())
            assertEquals("ACTIVE", revision(id)["revision"]["status"].asText())
        }
        mapOf(HUMANOID to "humanoid-a", QUADRUPED to "quadruped-b").forEach { (robotId, model) ->
            val bound = engineer("/api/robots/$robotId/binding", """{"adapterVersionId":$build,"profileRevisionId":${revisions[model]}}""")
            assertEquals("SUCCEEDED" to 201, bound["result"].asText() to bound["registryStatus"].asInt(), "$bound")
        }

        val humanoid = robot(HUMANOID)
        assertEquals(build, humanoid["binding"]["adapterVersionId"].asLong())
        assertEquals(revisions["humanoid-a"], humanoid["binding"]["profileRevisionId"].asLong())
        assertEquals("engineer/kim", humanoid["binding"]["boundBy"].asText())
        assertEquals(listOf("SITE_NAMES_UNREGISTERED"), kinds(HUMANOID))
        assertEquals(listOf("destination", "location"), humanoid["binding"]["siteNameKeys"].map { it.asText() })
    }

    @Test
    @Order(5)
    fun `명칭을 기록하면 티칭한 기체는 시운전 완료가 되고 티칭 안 한 기체는 CONTRADICTED 로 막힌다`() {
        // 두 기체 다 Order 1 의 보고로 이미 답했다(humanoid 2개, quadruped 0개). 그래서 기록하자마자 판정이 선다.
        listOf(HUMANOID, QUADRUPED).forEach { id ->
            assertEquals("SUCCEEDED", engineer("/api/robots/$id/site-names")["result"].asText())
        }

        val humanoid = robot(HUMANOID)
        assertEquals("COMPLETE", humanoid["commissioning"]["state"].asText(), "$humanoid")
        assertEquals(emptyList(), kinds(HUMANOID))
        assertEquals(2, humanoid["binding"]["siteNamesCount"].asInt())

        val quadruped = robot(QUADRUPED)
        assertEquals("INCOMPLETE", quadruped["commissioning"]["state"].asText())
        val blocker = quadruped["blockers"].single()
        assertEquals("SITE_NAMES_CONTRADICTED" to "SITE", blocker["kind"].asText() to blocker["owner"].asText())
        assertEquals(false, blocker["inScreen"].asBoolean())
    }

    @Test
    @Order(6)
    fun `현장에서 다시 티칭하면 기록은 그대로 둔 채 다음 보고로 풀린다`() {
        stack.site.teach(QUADRUPED, listOf("dock-3"))
        stack.site.advance(Duration.ofSeconds(31))

        val quadruped = robot(QUADRUPED)
        assertEquals("COMPLETE", quadruped["commissioning"]["state"].asText(), "$quadruped")
        assertEquals(emptyList(), kinds(QUADRUPED))
    }

    @Test
    @Order(7)
    fun `개정판 거절이 대응표대로 보인다`() {
        assertEquals("PROFILE_UNREADABLE", rejection("/api/profile-revisions", "{not json", 400)["kind"].asText())

        // 같은 번호의 다른 문서. 공백 하나라도 문서 해시가 다르다.
        val changed = document("humanoid-a") + "\n"
        val notMonotonic = rejection("/api/profile-revisions", changed, 409)
        assertEquals("REVISION_NOT_MONOTONIC", notMonotonic["kind"].asText())
        assertTrue("highest=2" in notMonotonic["observed"].asText(), "$notMonotonic")

        listOf("test-requests", "activation").forEach { op ->
            assertEquals("UNKNOWN_REVISION", rejection("/api/profile-revisions/999999/$op", status = 404)["kind"].asText())
        }

        // 읽히지만 검증에 실패한 문서는 DRAFT 로 저장된다. 거절이 아니다.
        val draft = document("humanoid-a").replace("\"revision\": 2,", "\"revision\": 3,").replace("\"PAYLOAD_LOST\"", "\"NOT_A_REGISTERED_ERROR\"")
        val saved = engineer("/api/profile-revisions", draft)
        assertEquals("SUCCEEDED" to 201, saved["result"].asText() to saved["registryStatus"].asInt(), "$saved")
        val draftId = stack.get("/api/profiles")["revisions"].single { it["revision"]["revision"].asInt() == 3 }
        assertEquals("DRAFT", draftId["revision"]["status"].asText())
        assertTrue(draftId["revision"]["reasons"].size() > 0)
        val id = draftId["revision"]["profileRevisionId"].asLong()
        assertEquals("REVISION_NOT_TESTABLE", rejection("/api/profile-revisions/$id/test-requests", status = 409)["kind"].asText())
        revisions["draft"] = id
    }

    @Test
    @Order(8)
    fun `바인딩·명칭 거절이 본문의 reason 으로 갈리고 그 기체로 바로 간다`() {
        val active = revisions["humanoid-a"]!!
        fun bind(robotId: String, buildId: Long, revisionId: Long) =
            """{"adapterVersionId":$buildId,"profileRevisionId":$revisionId}""".let { rejectionOf("/api/robots/$robotId/binding", it) }

        assertEquals("UNKNOWN_ROBOT" to "ghost", bind("ghost", build, active).let { it["kind"].asText() to it["target"].asText() })
        assertEquals("UNKNOWN_REVISION", bind(HUMANOID, build, 999_999)["kind"].asText())
        assertEquals("UNKNOWN_BUILD", bind(HUMANOID, 999_999, active)["kind"].asText())
        assertEquals("REVISION_NOT_ACTIVE", bind(HUMANOID, build, revisions["draft"]!!)["kind"].asText())

        val adapterId = stack.get("/api/adapters")["adapters"].single()["adapterId"].asLong()
        engineer("/api/adapters/$adapterId/versions", """{"version":"0.0.1","contractSemver":"0.0.1"}""")
        val old = stack.get("/api/adapters")["adapters"].single()["versions"].single { it["version"].asText() == "0.0.1" }["adapterVersionId"].asLong()
        assertEquals("CONTRACT_TOO_OLD", bind(HUMANOID, old, active)["kind"].asText())

        assertEquals("NO_ACTIVE_BINDING" to "ghost", rejectionOf("/api/robots/ghost/site-names", null).let { it["kind"].asText() to it["target"].asText() })

        val retired = stack.send("POST", "/api/robots/$QUADRUPED/retirement", "operator", body = """{"reason":"정비"}""")
        assertEquals(200, retired.status)
        val refused = bind(QUADRUPED, build, revisions["quadruped-b"]!!)
        assertEquals("ROBOT_RETIRED" to "OPERATOR", refused["kind"].asText() to refused["owner"].asText())
        assertEquals("RETIRED", robot(QUADRUPED)["commissioning"]["state"].asText())
        assertEquals(emptyList(), kinds(QUADRUPED).filter { it.startsWith("SITE_NAMES") || it == "UNBOUND" })
    }

    private fun rejectionOf(path: String, body: String?): JsonNode {
        val outcome = engineer(path, body)
        assertEquals("REJECTED", outcome["result"].asText(), "$outcome")
        return outcome["rejection"]
    }

    @Test
    @Order(9)
    fun `사전 거절은 registry 에 닿지 않고 조작 기록에 남지 않는다`() {
        val before = stack.get("/api/operations").size()

        val noTarget = stack.send("POST", "/api/robots/$HUMANOID/binding", "engineer", body = """{"adapterVersionId":$build}""")
        assertEquals(400 to "BINDING_TARGET_REQUIRED", noTarget.status to noTarget.body!!["error"].asText())
        listOf(
            "/api/profile-revisions/${revisions["humanoid-a"]}/activation" to null,
            "/api/profile-revisions/${revisions["humanoid-a"]}/test-requests" to null,
            "/api/robots/$HUMANOID/site-names" to null,
            "/api/robots/$HUMANOID/binding" to """{"adapterVersionId":$build,"profileRevisionId":${revisions["humanoid-a"]}}""",
            "/api/profile-revisions" to document("humanoid-a"),
        ).forEach { (path, body) -> assertEquals(403, stack.send("POST", path, "operator", body = body).status, path) }
        assertEquals(415, stack.send("POST", "/api/profile-revisions", "engineer", body = document("humanoid-a"), contentType = "text/plain").status)
        val empty = stack.send("POST", "/api/profile-revisions", "engineer", body = "")
        assertEquals(400, empty.status, "${empty.body}")

        assertEquals(before, stack.get("/api/operations").size())
    }

    @Test
    @Order(10)
    fun `조작 기록과 registry 감사 기록의 행위자가 같다`() {
        val targets = stack.get("/api/operations").map { it["target"].asText() }.toSet()
        assertTrue("profile picasso-ref/humanoid-a#2" in targets, "$targets")
        assertTrue("revision ${revisions["humanoid-a"]}" in targets, "$targets")
        assertTrue("robot $HUMANOID" in targets, "$targets")

        listOf("PROFILE_REVISION_SUBMIT", "TEST_REQUEST", "PROFILE_REVISION_ACTIVATE", "ROBOT_BIND", "SITE_NAMES_REGISTERED").forEach { op ->
            val actors = PostgresSupport.queryAll("SELECT DISTINCT actor FROM audit_log WHERE operation = '$op'") { it.getString(1) }
            assertEquals(listOf("engineer/kim"), actors, op)
        }
        assertNull(stack.get("/api/profiles")["revisions"].firstOrNull { it["revision"]["createdBy"].asText() != "engineer/kim" })
    }
}
```

```diff
diff --git a/e2e/src/test/kotlin/dev/picasso/ops/e2e/LifecycleTest.kt b/e2e/src/test/kotlin/dev/picasso/ops/e2e/LifecycleTest.kt
index f76bb6d..472b2ae 100644
--- a/e2e/src/test/kotlin/dev/picasso/ops/e2e/LifecycleTest.kt
+++ b/e2e/src/test/kotlin/dev/picasso/ops/e2e/LifecycleTest.kt
@@ -40,6 +40,7 @@ class LifecycleTest {
 
     private fun robot(): JsonNode = stack.get("/api/robots")["robots"].single { it["robot"]["robotId"].asText() == ROBOT }
 
+    /** S1d 부터 바인딩 안 된 기체에는 `UNBOUND` 가 붙는다(P2·S1d 스펙 §8.5). 이 시험은 바인딩을 하지 않으므로 늘 그것이 있다. */
     private fun blockers(robot: JsonNode) = robot["blockers"].map { it["kind"].asText() }
 
     /** 프로파일의 상태 발행 주기 상한 30초를 넘겨 민다. 상태 발행이 곧 생존 보고다. */
@@ -54,17 +55,17 @@ class LifecycleTest {
         val robot = robot()
         assertEquals("CLAIMED", robot["robot"]["status"].asText())
         assertEquals("NO_REPORT", robot["connection"].asText())
-        assertEquals(listOf("AWAITING_FIRST_REPORT"), blockers(robot))
+        assertEquals(listOf("AWAITING_FIRST_REPORT", "UNBOUND"), blockers(robot))
     }
 
     @Test
     @Order(2)
-    fun `보고가 오면 CONFIRMED 이고 막힘이 없다`() {
+    fun `보고가 오면 CONFIRMED 이고 바인딩 말고는 막힘이 없다`() {
         report()
         val robot = robot()
         assertEquals("CONFIRMED", robot["robot"]["status"].asText())
         assertEquals("FRESH", robot["connection"].asText())
-        assertEquals(listOf(), blockers(robot))
+        assertEquals(listOf("UNBOUND"), blockers(robot))
     }
 
     @Test
@@ -98,12 +99,12 @@ class LifecycleTest {
 
     @Test
     @Order(6)
-    fun `복귀하면 퇴역이 풀리고 막힘이 사라진다`() {
+    fun `복귀하면 퇴역이 풀리고 바인딩 말고는 막힘이 사라진다`() {
         val reply = stack.send("DELETE", "/api/robots/$ROBOT/retirement", "operator")
         assertEquals("SUCCEEDED", reply.body!!["result"].asText())
         val robot = robot()
         assertEquals("CONFIRMED", robot["robot"]["status"].asText())
-        assertEquals(listOf(), blockers(robot))
+        assertEquals(listOf("UNBOUND"), blockers(robot))
     }
 
     @Test
```

- [ ] **Step 2: 통과 확인**

Run: `./gradlew :e2e:test -q`
Expected: XML 기준 e2e 30개 실패 0(`CommissioningTest` 10 포함). 패치 전에 돌리면 `LifecycleTest` 3개가 `UNBOUND` 때문에 실패한다. `CommissioningTest` 는 Task 1~3 이 이미 지은 것을 맞대므로 처음부터 통과한다. 통과가 시험이 실제로 도는 것인지는 Task 7 의 결함 주입(K13)이 확인한다.

- [ ] **Step 3: 커밋**

```bash
git add e2e/src/test/kotlin/dev/picasso/ops/e2e/CommissioningTest.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/LifecycleTest.kt
git commit -q -F - <<'EOF'
test(e2e): 제출부터 시운전 완료까지의 통합 시험

- 작업 묶음 커밋(Task 8 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 5: 화면

**Files:**
- Create: `ui/src/components/ProfilesSection.tsx`, `ui/src/components/CommissioningCards.tsx`
- Modify: `ui/src/api.ts`(타입, 호출, 응답 처리 공통화), `ui/src/labels.ts`, `ui/src/App.tsx`(넷을 한 번에 읽기), `ui/src/components/RobotsArea.tsx`(시운전 칸, 프로파일 구역), `ui/src/components/RobotDetail.tsx`(카드 3개), `ui/src/testing/fakeOps.ts`(프로파일 대역)
- Test: `ui/src/components/ProfilesSection.test.tsx`, `ui/src/components/CommissioningCards.test.tsx`

- [ ] **Step 1: 대역과 실패하는 시험 쓰기** — 대역 패치를 `C:/Users/Eisen/AppData/Local/Temp/s1d-patches/s1d-4.patch` 로 저장하고 `git apply` 한 뒤 두 시험 파일을 쓴다.

```diff
diff --git a/ui/src/testing/fakeOps.ts b/ui/src/testing/fakeOps.ts
index 989524d..de692a0 100644
--- a/ui/src/testing/fakeOps.ts
+++ b/ui/src/testing/fakeOps.ts
@@ -1,5 +1,16 @@
 import { vi } from 'vitest'
-import type { AdapterListView, Finding, OperationOutcome, RobotListView, RobotView } from '../api'
+import type {
+  AdapterListView,
+  Binding,
+  Finding,
+  OperationOutcome,
+  ProfileListView,
+  Revision,
+  RevisionView,
+  RobotListView,
+  RobotView,
+  TestRequestState,
+} from '../api'
 
 /** 운영 서비스 대역이 받은 요청 한 건. */
 export interface Call {
@@ -14,6 +25,9 @@ export interface FakeOps {
   calls: Call[]
   view: RobotListView
   adapters: AdapterListView
+  profiles: ProfileListView
+  /** 여기 든 경로의 GET 은 503 이다. 운영 서비스의 일부 읽기만 실패하는 경우를 만든다. */
+  failing: Set<string>
   answer: { status: number; body: unknown }
 }
 
@@ -22,6 +36,66 @@ export function adapterView(partial: Partial<AdapterListView> = {}): AdapterList
   return { registry: 'OK', checkedAt: 't1', adapters: [], instances: [], asOf: 't1', ...partial }
 }
 
+/** 프로파일 목록. 기본은 카탈로그만 있고 개정판이 없는 «없음» 이다. */
+export function profileView(partial: Partial<ProfileListView> = {}): ProfileListView {
+  return {
+    registry: 'OK',
+    checkedAt: 't1',
+    catalog: { contractSemver: '0.9.0', skillTypes: [] },
+    revisions: [],
+    asOf: 't1',
+    ...partial,
+  }
+}
+
+export function revisionView(partial: Partial<Revision> = {}, testRequest: TestRequestState = 'NONE'): RevisionView {
+  return {
+    revision: {
+      profileRevisionId: 5,
+      vendor: 'picasso-ref',
+      model: 'humanoid-a',
+      revision: 2,
+      status: 'VALIDATED',
+      reasons: [],
+      documentHash: 'h',
+      createdBy: 'engineer/kim',
+      createdAt: 't0',
+      activatedBy: null,
+      activatedAt: null,
+      suites: {},
+      latestTestRequest: null,
+      ...partial,
+    },
+    testRequest,
+  }
+}
+
+/** 활성 바인딩 한 줄. 기본은 명칭이 확인된 바인딩이다. */
+export function binding(partial: Partial<Binding> = {}): Binding {
+  return {
+    robotId: 'humanoid-01',
+    vendor: 'picasso-ref',
+    model: 'humanoid-a',
+    profileRevisionId: 5,
+    revision: 2,
+    adapterName: 'acme/fleet',
+    adapterVersion: '1.0.0',
+    conformanceStatus: 'UNTESTED',
+    active: true,
+    siteNames: 'CONFIRMED',
+    siteNameKeys: ['destination', 'location'],
+    adapterVersionId: 10,
+    boundBy: 'engineer/kim',
+    boundAt: 't0',
+    siteNamesRegisteredBy: 'engineer/kim',
+    siteNamesRegisteredAt: 't0',
+    siteNamesReportedAt: 't1',
+    siteNamesCount: 2,
+    siteNamesUnsupported: false,
+    ...partial,
+  }
+}
+
 export function robotView(robotId: string, status: string, blockers: Finding[] = []): RobotView {
   return {
     robot: {
@@ -53,8 +127,12 @@ export function outcome(partial: Partial<OperationOutcome>): OperationOutcome {
 }
 
 /** 브라우저처럼 ISO-8859-1 밖의 문자가 헤더에 있으면 보내기 전에 던지는 fetch 대역을 끼운다. */
-export function installFakeOps(view: RobotListView, adapters: AdapterListView = adapterView()): FakeOps {
-  const fake: FakeOps = { calls: [], view, adapters, answer: { status: 200, body: outcome({}) } }
+export function installFakeOps(
+  view: RobotListView,
+  adapters: AdapterListView = adapterView(),
+  profiles: ProfileListView = profileView(),
+): FakeOps {
+  const fake: FakeOps = { calls: [], view, adapters, profiles, failing: new Set(), answer: { status: 200, body: outcome({}) } }
   vi.stubGlobal(
     'fetch',
     vi.fn(async (url: string, init?: RequestInit) => {
@@ -67,7 +145,15 @@ export function installFakeOps(view: RobotListView, adapters: AdapterListView =
       const method = init?.method ?? 'GET'
       fake.calls.push({ method, url, headers, body: init?.body ? JSON.parse(init.body as string) : undefined })
       if (method === 'GET') {
-        const body = url === '/api/robots' ? fake.view : url === '/api/adapters' ? fake.adapters : []
+        if (fake.failing.has(url)) return new Response('', { status: 503 })
+        const body =
+          url === '/api/robots'
+            ? fake.view
+            : url === '/api/adapters'
+              ? fake.adapters
+              : url === '/api/profiles'
+                ? fake.profiles
+                : []
         return new Response(JSON.stringify(body), { status: 200 })
       }
       return new Response(JSON.stringify(fake.answer.body), { status: fake.answer.status })
```

```tsx
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Finding, RobotListView } from '../api'
import { adapterView, installFakeOps, outcome, profileView, revisionView } from '../testing/fakeOps'

const robots: RobotListView = { registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' }

const pass = (ranBy = 'site-runner') => ({ result: 'PASS', ranAt: 't1', ranBy, detail: { checks: 3, failures: [] } })

const tested = () =>
  profileView({
    catalog: {
      contractSemver: '0.9.0',
      skillTypes: [
        { name: 'navigate_to', major: 1, introducedInSemver: '0.1.0', siteReferenceKeys: ['location'] },
        { name: 'pick_place', major: 1, introducedInSemver: '0.1.0', siteReferenceKeys: ['destination'] },
      ],
    },
    revisions: [
      revisionView({ status: 'TESTED', suites: { CONTRACT: pass(), NEGATIVE: pass(), DETERMINISM: pass() } }, 'DONE'),
      revisionView(
        {
          profileRevisionId: 6,
          model: 'quadruped-b',
          revision: 1,
          suites: {
            CONTRACT: pass(),
            NEGATIVE: {
              result: 'FAIL',
              ranAt: 't1',
              ranBy: 'site-runner',
              detail: { checks: 5, failures: [{ check: 'CANCEL_UNSUPPORTED', expected: '거절', observed: '수락' }] },
            },
          },
        },
        'RUNNING',
      ),
      revisionView({ profileRevisionId: 7, revision: 3, status: 'DRAFT', reasons: ['error_type 이 등록되지 않았다'] }),
    ],
  })

/** 운영 서비스의 `ProfileRejections.of` 가 내는 모양 그대로다. */
const refused: Finding = {
  kind: 'ACTIVATION_REFUSED',
  observed: 'HTTP 409, status=VALIDATED, suites={"CONTRACT":"PASS"}',
  expected: '201 또는 200',
  checkedAt: 't2',
  owner: 'ENGINEER',
  inScreen: true,
  action: '시험 요청 또는 시험 결과 확인',
  target: null,
}

async function section() {
  render(<App />)
  await screen.findByText('선언된 기체가 없습니다')
  return screen.getByRole('region', { name: '프로파일' })
}

const posted = (fake: ReturnType<typeof installFakeOps>) => fake.calls.find((call) => call.method === 'POST')!

describe('프로파일', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('카탈로그 한 줄과 개정판마다 상태·스위트 결과와 실행 주체·시험 요청 상태가 보인다', async () => {
    installFakeOps(robots, adapterView(), tested())
    const profiles = await section()
    expect(await within(profiles).findByText('스킬 2종, 계약 0.9.0')).toBeInTheDocument()
    const table = within(profiles).getByRole('table', { name: '개정판 목록' })
    expect(within(table).getByRole('row', { name: /humanoid-a 2 TESTED/ })).toHaveTextContent(
      'PASS (site-runner)PASS (site-runner)PASS (site-runner)끝남',
    )
    expect(within(table).getByRole('row', { name: /quadruped-b/ })).toHaveTextContent('실행 중')
  })

  it('FAIL 이면 상세를, DRAFT 이면 검증 실패 사유를 펼쳐 본다', async () => {
    installFakeOps(robots, adapterView(), tested())
    const profiles = await section()
    await userEvent.click(await within(profiles).findByText('상세'))
    expect(within(profiles).getByText('CANCEL_UNSUPPORTED: 기대 거절, 관측 수락')).toBeVisible()
    await userEvent.click(within(profiles).getByText('저장됨: 검증 실패'))
    expect(within(profiles).getByText('error_type 이 등록되지 않았다')).toBeVisible()
  })

  it('목록을 읽은 적이 없으면 모름이다', async () => {
    installFakeOps(robots, adapterView(), profileView({ registry: 'REGISTRY_SILENT', catalog: null, revisions: null, asOf: null }))
    const profiles = await section()
    expect(await within(profiles).findByText('모름: 프로파일 목록을 아직 읽지 못했습니다')).toBeInTheDocument()
  })

  it('시험 요청은 엔지니어 모드로 그 개정판의 경로에 보내고 결과가 기종·번호와 함께 보인다', async () => {
    const fake = installFakeOps(robots, adapterView(), tested())
    const profiles = await section()
    const row = within(within(profiles).getByRole('table', { name: '개정판 목록' })).getByRole('row', { name: /humanoid-a 2 TESTED/ })
    await userEvent.click(within(row).getByRole('button', { name: '시험 요청' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    expect(posted(fake).url).toBe('/api/profile-revisions/5/test-requests')
    expect(posted(fake).headers['X-Ops-Mode']).toBe('engineer')
    expect(await screen.findByText('picasso-ref/humanoid-a#2 시험 요청: 반영됨')).toBeInTheDocument()
  })

  it('활성화 거절은 상태와 스위트 결과를 관측값으로 보인다', async () => {
    const fake = installFakeOps(robots, adapterView(), tested())
    fake.answer = { status: 200, body: outcome({ result: 'REJECTED', registryStatus: 409, rejection: refused }) }
    const profiles = await section()
    const row = within(within(profiles).getByRole('table', { name: '개정판 목록' })).getByRole('row', { name: /quadruped-b/ })
    await userEvent.click(within(row).getByRole('button', { name: '활성화' }))
    const notice = (await screen.findByText('picasso-ref/quadruped-b#1 활성화: 거절됨')).closest('[role="status"]') as HTMLElement
    expect(within(notice).getByText('활성화 조건 미달')).toBeInTheDocument()
    expect(within(notice).getByText(/status=VALIDATED/)).toBeInTheDocument()
  })

  it('제출은 고른 파일의 글자를 그대로 보낸다', async () => {
    const fake = installFakeOps(robots)
    const form = within(await section()).getByRole('form', { name: '개정판 제출' })
    const text = '{\n  "vendor" : "picasso-ref",  "model": "humanoid-a", "revision": 2\n}\n'
    await userEvent.upload(within(form).getByLabelText('프로파일 문서'), new File([text], 'humanoid-a.json', { type: 'application/json' }))
    await userEvent.click(within(form).getByRole('button', { name: '제출' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const init = vi.mocked(fetch).mock.calls.find(([, options]) => options?.method === 'POST')![1]!
    expect(init.body).toBe(text)
    expect(posted(fake).url).toBe('/api/profile-revisions')
    expect(posted(fake).headers['Content-Type']).toBe('application/json')
    expect(await screen.findByText('picasso-ref/humanoid-a#2 제출: 반영됨')).toBeInTheDocument()
  })

  it('기종·번호를 읽지 못하는 문서는 알림에 파일 이름을 적고 판정은 registry 에 맡긴다', async () => {
    const fake = installFakeOps(robots)
    const form = within(await section()).getByRole('form', { name: '개정판 제출' })
    await userEvent.upload(within(form).getByLabelText('프로파일 문서'), new File(['{"vendor":"x"}'], 'broken.json', { type: 'application/json' }))
    await userEvent.click(within(form).getByRole('button', { name: '제출' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    expect(await screen.findByText('broken.json 제출: 반영됨')).toBeInTheDocument()
  })

  it('프로파일 목록만 못 읽어도 넷 다 직전 값이다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' }, adapterView(), tested())
    await section()
    fake.failing.add('/api/profiles')
    // 모드를 바꾸면 다시 읽는다. 주기(5초)를 기다리지 않는다.
    await userEvent.click(screen.getByLabelText('운영자'))
    expect(await screen.findByRole('alert')).toHaveTextContent('모름: 운영 서비스에 닿지 않습니다')
    expect(within(screen.getByRole('region', { name: '프로파일' })).getByText('직전 값입니다 (t1 기준)')).toBeInTheDocument()
  })

  it('운영자 모드에서는 제출 폼과 조작 버튼이 없고 어느 모드에서 하는지 적는다', async () => {
    installFakeOps(robots, adapterView(), tested())
    const profiles = await section()
    await userEvent.click(screen.getByLabelText('운영자'))
    expect(within(profiles).getByText('프로파일 관리는 엔지니어 모드에서 합니다')).toBeInTheDocument()
    expect(within(profiles).queryByRole('form')).not.toBeInTheDocument()
    expect(within(profiles).queryByRole('button', { name: '시험 요청' })).not.toBeInTheDocument()
  })
})
```

```tsx
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Adapter, Finding, RobotView } from '../api'
import { adapterView, binding, installFakeOps, profileView, revisionView, robotView } from '../testing/fakeOps'

const fleet: Adapter = {
  adapterId: 1,
  vendor: 'acme',
  name: 'fleet',
  versions: [
    { adapterVersionId: 10, version: '1.0.0', contractSemver: '0.9.0', conformance: 'UNTESTED', registeredAt: 't0', registeredBy: 'engineer/lee' },
  ],
}

const contradicted: Finding = {
  kind: 'SITE_NAMES_CONTRADICTED',
  observed: '기록 t0, 기체가 아는 명칭 0개',
  expected: '기체가 아는 명칭 1개 이상',
  checkedAt: 't1',
  owner: 'SITE',
  inScreen: false,
  action: '현장에서 명칭 티칭을 다시. 다음 보고로 풀림',
  target: 'quadruped-01',
}

const complete = (): RobotView => ({
  ...robotView('humanoid-01', 'CONFIRMED'),
  binding: binding(),
  commissioning: { state: 'COMPLETE', ledgerConfirmed: true, bound: true, siteNamesReady: true },
  software: { robotId: 'humanoid-01', declared: null, reported: null, verdict: 'UNREPORTED' },
})

const blocked = (): RobotView => ({
  ...robotView('quadruped-01', 'CONFIRMED', [contradicted]),
  binding: binding({ robotId: 'quadruped-01', model: 'quadruped-b', revision: 1, siteNames: 'CONTRADICTED', siteNameKeys: ['location'], siteNamesCount: 0 }),
  commissioning: { state: 'INCOMPLETE', ledgerConfirmed: true, bound: true, siteNamesReady: false },
  software: null,
})

const unbound = (): RobotView => ({
  ...robotView('humanoid-02', 'CONFIRMED'),
  binding: null,
  commissioning: { state: 'INCOMPLETE', ledgerConfirmed: true, bound: false, siteNamesReady: false },
  software: null,
})

const view = () => ({ registry: 'OK' as const, checkedAt: 't1', robots: [complete(), blocked(), unbound()], robotsAsOf: 't1' })

const profiles = () =>
  profileView({
    revisions: [
      revisionView({ status: 'ACTIVE' }),
      revisionView({ profileRevisionId: 8, revision: 3, status: 'TESTED' }),
    ],
  })

function field(region: HTMLElement, name: string) {
  const term = within(region).getByText(name, { selector: 'dt' })
  return term.nextElementSibling?.textContent
}

async function open(robotId: string, mode: '엔지니어' | '운영자' = '엔지니어') {
  render(<App />)
  await userEvent.click(screen.getByLabelText(mode))
  await userEvent.click(await screen.findByRole('button', { name: robotId }))
  return screen.getByRole('region', { name: `${robotId} 상세` })
}

describe('시운전', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('목록에 시운전 칸이 연결 칸과 따로 보인다', async () => {
    installFakeOps(view(), adapterView({ adapters: [fleet] }), profiles())
    render(<App />)
    const list = await screen.findByRole('region', { name: '기체 목록' })
    expect(await within(list).findByRole('row', { name: /humanoid-01/ })).toHaveTextContent('humanoid-01CONFIRMED신선완료0')
    expect(within(list).getByRole('row', { name: /quadruped-01/ })).toHaveTextContent('quadruped-01CONFIRMED신선미완1')
  })

  it('상세의 카드 3개가 바인딩, 사람의 기록과 기체의 답, 세 조건의 체크 목록을 보인다', async () => {
    installFakeOps(view(), adapterView({ adapters: [fleet] }), profiles())
    const detail = await open('humanoid-01')
    const bindingCard = within(detail).getByRole('region', { name: '바인딩' })
    expect(field(bindingCard, '빌드')).toBe('acme/fleet 1.0.0')
    expect(field(bindingCard, '개정판')).toBe('picasso-ref/humanoid-a#2')
    const names = within(detail).getByRole('region', { name: '사이트 명칭' })
    expect(field(names, '사람이 기록함')).toBe('engineer/kim t0')
    expect(field(names, '기체가 답함')).toBe('아는 명칭 2개 (t1)')
    expect(within(names).getByText(/명칭 티칭은 화면 밖 현장 작업입니다/)).toBeInTheDocument()
    const card = within(detail).getByRole('region', { name: '시운전' })
    expect(within(card).getByRole('heading', { name: '시운전: 완료' })).toBeInTheDocument()
    expect(within(card).getByText(/\[v\] 활성 바인딩 \(근거 \/diag\/bindings\)/)).toBeInTheDocument()
    expect(within(card).getByText(/소프트웨어 대조 보고 없음, 어댑터 적합성 UNTESTED/)).toBeInTheDocument()
  })

  it('명칭이 어긋난 기체는 막힘 카드가 현장의 화면 밖 일로 보이고 체크 목록에 빠진 조건이 보인다', async () => {
    installFakeOps(view(), adapterView({ adapters: [fleet] }), profiles())
    const detail = await open('quadruped-01')
    expect(field(detail, '종류')).toBe('기체가 아는 명칭 없음')
    expect(field(detail, '해결 담당')).toBe('현장(화면 밖): 현장에서 명칭 티칭을 다시. 다음 보고로 풀림')
    const card = within(detail).getByRole('region', { name: '시운전' })
    expect(within(card).getByText(/\[ \] 명칭 상태 CONFIRMED 또는 NOT_REQUIRED/)).toBeInTheDocument()
  })

  it('사람이 기록하지 않았으면 기체가 답했어도 기록 없음으로 보인다', async () => {
    // 기록 시각과 보고 시각이 다른 칸이다. 둘 다 있는 바인딩만 보면 두 칸을 바꿔 읽어도 같은 갈래로 간다.
    const unregistered: RobotView = {
      ...robotView('humanoid-03', 'CONFIRMED'),
      binding: binding({ robotId: 'humanoid-03', siteNames: 'UNREGISTERED', siteNamesRegisteredBy: null, siteNamesRegisteredAt: null }),
      commissioning: { state: 'INCOMPLETE', ledgerConfirmed: true, bound: true, siteNamesReady: false },
      software: null,
    }
    installFakeOps({ ...view(), robots: [unregistered] }, adapterView({ adapters: [fleet] }), profiles())
    const names = within(await open('humanoid-03')).getByRole('region', { name: '사이트 명칭' })
    expect(field(names, '사람이 기록함')).toBe('기록 없음')
    expect(field(names, '기체가 답함')).toBe('아는 명칭 2개 (t1)')
  })

  it('기체의 답은 아직 답 없음과 명칭을 지원하지 않음을 가른다', async () => {
    const robots = ['humanoid-04', 'humanoid-05'].map<RobotView>((robotId, index) => ({
      ...robotView(robotId, 'CONFIRMED'),
      binding:
        index === 0
          ? binding({ robotId, siteNames: 'CLAIMED', siteNamesReportedAt: null, siteNamesCount: null, siteNamesUnsupported: null })
          : binding({ robotId, siteNames: 'CONTRADICTED', siteNamesCount: 0, siteNamesUnsupported: true }),
      commissioning: { state: 'INCOMPLETE', ledgerConfirmed: true, bound: true, siteNamesReady: false },
      software: { robotId, declared: '1.0', reported: '1.1', verdict: 'MISMATCH' },
    }))
    installFakeOps({ ...view(), robots }, adapterView({ adapters: [fleet] }), profiles())
    const first = await open('humanoid-04')
    expect(field(within(first).getByRole('region', { name: '사이트 명칭' }), '기체가 답함')).toBe('아직 답 없음')
    expect(within(first).getByText(/소프트웨어 대조 불일치/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'humanoid-05' }))
    const second = screen.getByRole('region', { name: 'humanoid-05 상세' })
    expect(field(within(second).getByRole('region', { name: '사이트 명칭' }), '기체가 답함')).toBe('명칭을 지원하지 않음 (t1)')
  })

  it('바인딩 폼은 활성 개정판만 고르게 하고 고른 빌드·개정판 id 를 보낸다', async () => {
    const fake = installFakeOps(view(), adapterView({ adapters: [fleet] }), profiles())
    const detail = await open('humanoid-02')
    const form = within(detail).getByRole('form', { name: '바인딩' })
    const revisions = within(form).getByLabelText('개정판')
    expect(within(revisions).getAllByRole('option').map((option) => option.textContent)).toEqual(['고르십시오', 'picasso-ref/humanoid-a#2'])
    await userEvent.selectOptions(within(form).getByLabelText('빌드'), 'acme/fleet 1.0.0')
    await userEvent.selectOptions(revisions, 'picasso-ref/humanoid-a#2')
    await userEvent.click(within(form).getByRole('button', { name: '바인딩' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = fake.calls.find((call) => call.method === 'POST')!
    expect(post.url).toBe('/api/robots/humanoid-02/binding')
    expect(post.body).toEqual({ adapterVersionId: 10, profileRevisionId: 5 })
    expect(await screen.findByText('humanoid-02 바인딩: 반영됨')).toBeInTheDocument()
  })

  it('명칭 등록 기록은 본문 없이 그 기체의 경로로 보낸다', async () => {
    const fake = installFakeOps(view(), adapterView({ adapters: [fleet] }), profiles())
    const detail = await open('quadruped-01')
    await userEvent.click(within(detail).getByRole('button', { name: '명칭 등록 기록' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = fake.calls.find((call) => call.method === 'POST')!
    expect(post.url).toBe('/api/robots/quadruped-01/site-names')
    expect(post.body).toBeUndefined()
    expect(await screen.findByText('quadruped-01 명칭 기록: 반영됨')).toBeInTheDocument()
  })

  it('운영자 모드에서는 바인딩 폼과 명칭 기록 버튼이 없다', async () => {
    installFakeOps(view(), adapterView({ adapters: [fleet] }), profiles())
    const detail = await open('quadruped-01', '운영자')
    expect(within(detail).queryByRole('form', { name: '바인딩' })).not.toBeInTheDocument()
    expect(within(detail).queryByRole('button', { name: '명칭 등록 기록' })).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd ui && npx vitest run src/components/ProfilesSection.test.tsx src/components/CommissioningCards.test.tsx`
Expected: 두 파일 모두 실패(구역·카드가 없음).

- [ ] **Step 3: 새 컴포넌트 둘**

```tsx
import { useState } from 'react'
import type { FormEvent } from 'react'
import { activateRevision, requestTest, submitRevision } from '../api'
import type { ProfileListView, RevisionView, Sent, Session, SuiteRun } from '../api'
import { TEST_REQUEST_LABEL } from '../labels'

const SUITES = ['CONTRACT', 'NEGATIVE', 'DETERMINISM'] as const

interface Props {
  view: ProfileListView | null
  /** 운영 서비스에 닿지 않으면 [view] 는 직전 값이다. */
  opsError: string | null
  session: Session
  busy: boolean
  /** 조작을 보낸다. 결과 알림과 목록 다시 읽기는 부르는 쪽이 한다. */
  run: (what: string, operation: () => Promise<Sent>) => void
}

/**
 * «프로파일» 구역(P2·S1d 스펙 §9). 카탈로그 한 줄과 개정판 목록이다. 카탈로그는 registry 가 기동 때 동기화하므로 동기화
 * 버튼이 없다. 제출·시험 요청·활성화는 엔지니어 모드에서 한다. 시험 결과는 사람이 적지 않는다 — 현장의 실행기가 적는다.
 */
export function ProfilesSection({ view, opsError, session, busy, run }: Props) {
  // 운영 서비스가 다른 모양을 주면(예: 시험 대역의 빈 배열) 칸이 없다. 없는 칸도 모름이다.
  const known = view !== null && view.catalog != null && view.revisions != null ? view : null
  const engineer = session.mode === 'engineer'
  const name = (row: RevisionView) => `${row.revision.vendor}/${row.revision.model}#${row.revision.revision}`

  return (
    <section aria-label="프로파일">
      <h2>프로파일</h2>
      {known === null ? (
        <p>모름: 프로파일 목록을 아직 읽지 못했습니다</p>
      ) : (
        <>
          {(known.asOf !== known.checkedAt || opsError !== null) && (
            <p className="stale">직전 값입니다 ({known.asOf} 기준)</p>
          )}
          <p>
            스킬 {known.catalog!.skillTypes.length}종, 계약 {known.catalog!.contractSemver}
          </p>
          {known.revisions!.length === 0 ? (
            <p>제출된 개정판이 없습니다</p>
          ) : (
            <table aria-label="개정판 목록">
              <thead>
                <tr>
                  <th>기종</th>
                  <th>번호</th>
                  <th>상태</th>
                  {SUITES.map((suite) => (
                    <th key={suite}>{suite}</th>
                  ))}
                  <th>시험 요청</th>
                  <th>활성화</th>
                  {engineer && <th>조작</th>}
                </tr>
              </thead>
              <tbody>
                {known.revisions!.map((row) => (
                  <tr key={row.revision.profileRevisionId}>
                    <td>
                      {row.revision.vendor}/{row.revision.model}
                    </td>
                    <td>{row.revision.revision}</td>
                    <td>
                      {row.revision.status}
                      {row.revision.status === 'DRAFT' && (
                        <details>
                          <summary>저장됨: 검증 실패</summary>
                          <ul>
                            {row.revision.reasons.map((reason) => (
                              <li key={reason}>{reason}</li>
                            ))}
                          </ul>
                        </details>
                      )}
                    </td>
                    {SUITES.map((suite) => (
                      <td key={suite}>
                        <SuiteCell run={row.revision.suites[suite]} />
                      </td>
                    ))}
                    <td>{TEST_REQUEST_LABEL[row.testRequest]}</td>
                    <td>
                      {row.revision.activatedBy !== null
                        ? `${row.revision.activatedBy} ${row.revision.activatedAt}`
                        : ''}
                    </td>
                    {engineer && (
                      <td>
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() =>
                            run(`${name(row)} 시험 요청`, () => requestTest(session, row.revision.profileRevisionId))
                          }
                        >
                          시험 요청
                        </button>
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() =>
                            run(`${name(row)} 활성화`, () => activateRevision(session, row.revision.profileRevisionId))
                          }
                        >
                          활성화
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
      {engineer ? (
        <SubmitForm
          busy={busy}
          onSubmit={(fileName, text) => run(`${coordinate(text) ?? fileName} 제출`, () => submitRevision(session, text))}
        />
      ) : (
        <p>프로파일 관리는 엔지니어 모드에서 합니다</p>
      )}
    </section>
  )
}

/**
 * 알림에 적을 기종·번호(`vendor/model#revision`). 조작 기록의 대상 칸과 같은 꼴이다. 읽지 못하면 널이고 알림은 파일 이름을 쓴다.
 * 문서의 옳고 그름은 registry 가 판정한다.
 */
function coordinate(text: string): string | null {
  try {
    const document = JSON.parse(text) as { vendor?: unknown; model?: unknown; revision?: unknown }
    const { vendor, model, revision } = document
    return typeof vendor === 'string' && typeof model === 'string' && typeof revision === 'number'
      ? `${vendor}/${model}#${revision}`
      : null
  } catch {
    return null
  }
}

/** 스위트 결과와 실행 주체. FAIL 이면 상세를 펼쳐 본다. */
function SuiteCell({ run }: { run: SuiteRun | undefined }) {
  if (run === undefined) return <>-</>
  const failures = run.detail?.failures ?? []
  return (
    <>
      {run.result} ({run.ranBy})
      {run.result === 'FAIL' && failures.length > 0 && (
        <details>
          <summary>상세</summary>
          <ul>
            {failures.map((failure) => (
              <li key={failure.check}>
                {failure.check}: 기대 {failure.expected}, 관측 {failure.observed}
              </li>
            ))}
          </ul>
        </details>
      )}
    </>
  )
}

/** 제출 폼. 고른 파일의 글자를 그대로 보낸다. 문서의 옳고 그름은 registry 가 판정한다. */
function SubmitForm({ busy, onSubmit }: { busy: boolean; onSubmit: (fileName: string, text: string) => void }) {
  const [file, setFile] = useState<File | null>(null)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (file !== null) void file.text().then((text) => onSubmit(file.name, text))
  }

  return (
    <form aria-label="개정판 제출" onSubmit={submit}>
      <label>
        프로파일 문서
        <input type="file" accept=".json,application/json" onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
      </label>
      <button type="submit" disabled={file === null || busy}>
        제출
      </button>
    </form>
  )
}
```

```tsx
import { useState } from 'react'
import type { FormEvent } from 'react'
import type { Adapter, Mode, RevisionView, RobotView } from '../api'
import { COMMISSIONING_LABEL, SOFTWARE_LABEL } from '../labels'

interface Props {
  view: RobotView
  adapters: Adapter[]
  revisions: RevisionView[]
  mode: Mode
  busy: boolean
  onBind: (adapterVersionId: number, profileRevisionId: number) => void
  onRecordSiteNames: () => void
}

/**
 * 기체 상세의 카드 3개(P2·S1d 스펙 §9): «바인딩», «사이트 명칭», «시운전». 운영 서비스가 바인딩 칸을 싣지 않으면 그리지
 * 않는다. «시운전 완료» 와 «배정 가능» 을 섞지 않는다 — 화면은 «배정 가능» 을 쓰지 않는다(결정 5).
 */
export function CommissioningCards({ view, adapters, revisions, mode, busy, onBind, onRecordSiteNames }: Props) {
  if (view.commissioning == null) return null
  const binding = view.binding ?? null
  const commissioning = view.commissioning
  const engineer = mode === 'engineer'
  const retired = view.robot.status === 'RETIRED'

  return (
    <>
      <section aria-label="바인딩" className="card">
        <h4>바인딩</h4>
        {binding === null ? (
          <p>활성 바인딩 없음</p>
        ) : (
          <dl>
            <dt>빌드</dt>
            <dd>
              {binding.adapterName} {binding.adapterVersion}
            </dd>
            <dt>개정판</dt>
            <dd>
              {binding.vendor}/{binding.model}#{binding.revision}
            </dd>
            <dt>바인딩한 이</dt>
            <dd>
              {binding.boundBy} {binding.boundAt}
            </dd>
          </dl>
        )}
        {engineer && !retired && <BindForm adapters={adapters} revisions={revisions} busy={busy} onBind={onBind} />}
      </section>

      <section aria-label="사이트 명칭" className="card">
        <h4>사이트 명칭</h4>
        {binding === null ? (
          <p>바인딩이 없어 요구할 명칭이 없습니다</p>
        ) : (
          <dl>
            <dt>명칭 상태</dt>
            <dd>{binding.siteNames}</dd>
            <dt>요구 키</dt>
            <dd>{binding.siteNameKeys.length === 0 ? '없음' : binding.siteNameKeys.join(', ')}</dd>
            <dt>사람이 기록함</dt>
            <dd>
              {binding.siteNamesRegisteredAt === null
                ? '기록 없음'
                : `${binding.siteNamesRegisteredBy} ${binding.siteNamesRegisteredAt}`}
            </dd>
            <dt>기체가 답함</dt>
            <dd>
              {binding.siteNamesReportedAt === null
                ? '아직 답 없음'
                : binding.siteNamesUnsupported
                  ? `명칭을 지원하지 않음 (${binding.siteNamesReportedAt})`
                  : `아는 명칭 ${binding.siteNamesCount ?? 0}개 (${binding.siteNamesReportedAt})`}
            </dd>
          </dl>
        )}
        <p className="offscreen">명칭 티칭은 화면 밖 현장 작업입니다. 화면은 티칭한 사실을 기록할 뿐 대신 하지 않습니다</p>
        {engineer && binding !== null && (
          <button type="button" disabled={busy} onClick={onRecordSiteNames}>
            명칭 등록 기록
          </button>
        )}
      </section>

      <section aria-label="시운전" className="card">
        <h4>시운전: {COMMISSIONING_LABEL[commissioning.state]}</h4>
        <ul>
          <li>
            {mark(commissioning.ledgerConfirmed)} 원장 상태 CONFIRMED, 퇴역 아님 (근거 /diag/robots)
          </li>
          <li>{mark(commissioning.bound)} 활성 바인딩 (근거 /diag/bindings)</li>
          <li>
            {mark(commissioning.siteNamesReady)} 명칭 상태 CONFIRMED 또는 NOT_REQUIRED (근거 /diag/bindings)
          </li>
        </ul>
        <p>
          참고(막지 않음): 소프트웨어 대조{' '}
          {view.software == null ? '보고 없음' : (SOFTWARE_LABEL[view.software.verdict] ?? view.software.verdict)}, 어댑터 적합성{' '}
          {binding?.conformanceStatus ?? '-'}
        </p>
      </section>
    </>
  )
}

const mark = (ok: boolean) => (ok ? '[v]' : '[ ]')

interface BindProps {
  adapters: Adapter[]
  revisions: RevisionView[]
  busy: boolean
  onBind: (adapterVersionId: number, profileRevisionId: number) => void
}

/** 바인딩 폼(엔지니어 모드). 빌드는 어댑터 목록에서, 개정판은 활성 개정판에서 고른다. */
function BindForm({ adapters, revisions, busy, onBind }: BindProps) {
  const [buildId, setBuildId] = useState('')
  const [revisionId, setRevisionId] = useState('')
  const active = revisions.filter((row) => row.revision.status === 'ACTIVE')
  const ready = buildId !== '' && revisionId !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (ready) onBind(Number(buildId), Number(revisionId))
  }

  return (
    <form aria-label="바인딩" onSubmit={submit}>
      <label>
        빌드
        <select value={buildId} onChange={(event) => setBuildId(event.target.value)}>
          <option value="">고르십시오</option>
          {adapters.flatMap((adapter) =>
            adapter.versions.map((build) => (
              <option key={build.adapterVersionId} value={build.adapterVersionId}>
                {adapter.vendor}/{adapter.name} {build.version}
              </option>
            )),
          )}
        </select>
      </label>
      <label>
        개정판
        <select value={revisionId} onChange={(event) => setRevisionId(event.target.value)}>
          <option value="">고르십시오</option>
          {active.map((row) => (
            <option key={row.revision.profileRevisionId} value={row.revision.profileRevisionId}>
              {row.revision.vendor}/{row.revision.model}#{row.revision.revision}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={!ready || busy}>
        바인딩
      </button>
    </form>
  )
}
```

- [ ] **Step 4: 패치** — 아래를 `C:/Users/Eisen/AppData/Local/Temp/s1d-patches/s1d-5.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/s1d-patches/s1d-5.patch`.

```diff
diff --git a/ui/src/App.tsx b/ui/src/App.tsx
index 23ca767..c59c1ab 100644
--- a/ui/src/App.tsx
+++ b/ui/src/App.tsx
@@ -1,6 +1,6 @@
 import { useEffect, useState } from 'react'
-import { fetchAdapters, fetchOperations, fetchRobots } from './api'
-import type { AdapterListView, OperationRecord, RobotListView, Session } from './api'
+import { fetchAdapters, fetchOperations, fetchProfiles, fetchRobots } from './api'
+import type { AdapterListView, OperationRecord, ProfileListView, RobotListView, Session } from './api'
 import { AREAS } from './areas'
 import type { AreaId } from './areas'
 import { HistoryArea } from './components/HistoryArea'
@@ -17,6 +17,7 @@ export default function App() {
   const [area, setArea] = useState<AreaId>('robots')
   const [view, setView] = useState<RobotListView | null>(null)
   const [adapters, setAdapters] = useState<AdapterListView | null>(null)
+  const [profiles, setProfiles] = useState<ProfileListView | null>(null)
   const [records, setRecords] = useState<OperationRecord[] | null>(null)
   const [opsError, setOpsError] = useState<string | null>(null)
   // 조작이 끝나면 하나 올린다. 목록을 주기(5초)를 기다리지 않고 다시 읽는다.
@@ -24,13 +25,15 @@ export default function App() {
 
   useEffect(() => {
     let alive = true
-    // 실패해도 직전 값을 지우지 않는다. 대신 opsError 로 직전 값임을 표시한다(스펙 §9).
+    // 실패해도 직전 값을 지우지 않는다. 대신 opsError 로 직전 값임을 표시한다(스펙 §9). 넷 중 하나라도 못 읽으면 넷 다
+    // 직전 값이다(P2·S1d 스펙 §8.1).
     const load = () => {
-      Promise.all([fetchRobots(session), fetchAdapters(session), fetchOperations(session)])
-        .then(([nextView, nextAdapters, nextRecords]) => {
+      Promise.all([fetchRobots(session), fetchAdapters(session), fetchProfiles(session), fetchOperations(session)])
+        .then(([nextView, nextAdapters, nextProfiles, nextRecords]) => {
           if (!alive) return
           setView(nextView)
           setAdapters(nextAdapters)
+          setProfiles(nextProfiles)
           setRecords(nextRecords)
           setOpsError(null)
         })
@@ -73,6 +76,7 @@ export default function App() {
           <RobotsArea
             view={view}
             adapters={adapters}
+            profiles={profiles}
             opsError={opsError}
             session={session}
             onChanged={() => setTick((value) => value + 1)}
diff --git a/ui/src/api.ts b/ui/src/api.ts
index ef3dfc3..fe0b16a 100644
--- a/ui/src/api.ts
+++ b/ui/src/api.ts
@@ -27,11 +27,61 @@ export interface Finding {
   target: string | null
 }
 
-/** 기체 한 대. 원장 상태는 registry 값 그대로, 연결과 막힘은 운영 서비스가 계산한다(스펙 §7.3). */
+/**
+ * 활성 바인딩 한 줄(registry `/diag/bindings`). 명칭 상태는 registry 의 5값이다. 사람의 기록(`siteNamesRegistered*`)과
+ * 기체의 답(`siteNamesReported*`)을 따로 싣는다(P2·S1d 스펙 §9).
+ */
+export interface Binding {
+  robotId: string
+  vendor: string
+  model: string
+  profileRevisionId: number
+  revision: number
+  adapterName: string
+  adapterVersion: string
+  conformanceStatus: string
+  active: boolean
+  siteNames: string
+  siteNameKeys: string[]
+  adapterVersionId: number
+  boundBy: string
+  boundAt: string
+  siteNamesRegisteredBy: string | null
+  siteNamesRegisteredAt: string | null
+  siteNamesReportedAt: string | null
+  siteNamesCount: number | null
+  siteNamesUnsupported: boolean | null
+}
+
+export type CommissioningState = 'COMPLETE' | 'INCOMPLETE' | 'RETIRED'
+
+/** 시운전 판정(결정 6). 세 조건을 따로 들고 있어 «시운전» 카드가 체크 목록으로 보인다. */
+export interface Commissioning {
+  state: CommissioningState
+  ledgerConfirmed: boolean
+  bound: boolean
+  siteNamesReady: boolean
+}
+
+/** 소프트웨어 대조(`MATCH`·`MISMATCH`·`UNREPORTED`). 시운전을 막지 않고 보이기만 한다. */
+export interface Software {
+  robotId: string
+  declared: string | null
+  reported: string | null
+  verdict: string
+}
+
+/**
+ * 기체 한 대. 원장 상태는 registry 값 그대로, 연결과 막힘은 운영 서비스가 계산한다(스펙 §7.3).
+ * 바인딩·시운전·소프트웨어 대조는 S1d 의 칸이다(P2·S1d 스펙 §8.1). 운영 서비스가 싣지 않으면 없다.
+ */
 export interface RobotView {
   robot: Robot
   connection: Connection
   blockers: Finding[]
+  binding?: Binding | null
+  commissioning?: Commissioning | null
+  software?: Software | null
 }
 
 /** 운영 서비스의 `GET /api/robots`. `robots` 가 null 이면 모름, 빈 배열이면 없음이다(스펙 §9). */
@@ -83,6 +133,70 @@ export interface AdapterListView {
   asOf: string | null
 }
 
+/** 계약이 아는 스킬 종류. 카탈로그는 registry 가 기동 때 계약에서 채운다. */
+export interface SkillType {
+  name: string
+  major: number
+  introducedInSemver: string
+  siteReferenceKeys: string[]
+}
+
+export interface Catalog {
+  contractSemver: string
+  skillTypes: SkillType[]
+}
+
+/** 스위트 하나의 최신 실행. `detail` 은 실행기가 낸 JSON 그대로다(`checks`·`failures`). */
+export interface SuiteRun {
+  result: string
+  ranAt: string
+  ranBy: string
+  detail: { checks?: number; failures?: { check: string; expected: string; observed: string }[] } | null
+}
+
+export interface TestRequest {
+  requestId: number
+  requestedBy: string
+  requestedAt: string
+  claimedBy: string | null
+  claimedAt: string | null
+  claimExpiresAt: string | null
+  completedAt: string | null
+}
+
+export interface Revision {
+  profileRevisionId: number
+  vendor: string
+  model: string
+  revision: number
+  status: string
+  reasons: string[]
+  documentHash: string
+  createdBy: string | null
+  createdAt: string | null
+  activatedBy: string | null
+  activatedAt: string | null
+  suites: Record<string, SuiteRun>
+  latestTestRequest: TestRequest | null
+}
+
+/** 시험 요청 상태(P2·S1d 스펙 §9). 운영 서비스가 목록을 읽은 시각과 만료 시각으로 정한다. */
+export type TestRequestState = 'NONE' | 'WAITING' | 'RUNNING' | 'EXPIRED' | 'DONE'
+
+export interface RevisionView {
+  revision: Revision
+  testRequest: TestRequestState
+}
+
+/** 운영 서비스의 `GET /api/profiles`. 목록이 null 이면 모름이다(스펙 §9). */
+export interface ProfileListView {
+  registry: RegistryState
+  checkedAt: string
+  catalog: Catalog | null
+  revisions: RevisionView[] | null
+  asOf: string | null
+}
+
 export interface OperationRecord {
   requestId: string
   mode: 'ENGINEER' | 'OPERATOR'
@@ -137,15 +251,31 @@ async function getJson<T>(path: string, session: Session): Promise<T> {
 }
 
 async function send(method: string, path: string, session: Session, body?: unknown): Promise<Sent> {
+  return deliver(path, {
+    method,
+    headers:
+      body === undefined
+        ? actorHeaders(session)
+        : { ...actorHeaders(session), 'Content-Type': 'application/json' },
+    body: body === undefined ? undefined : JSON.stringify(body),
+  })
+}
+
+/**
+ * 프로파일 문서를 고른 파일의 글자 그대로 보낸다. 다시 직렬화하면 registry 가 매기는 문서 해시가 달라져, 같은 문서의
+ * 재제출이 다른 문서로 보인다(P2·S1d 스펙 §8.2).
+ */
+function sendDocument(path: string, session: Session, text: string): Promise<Sent> {
+  return deliver(path, {
+    method: 'POST',
+    headers: { ...actorHeaders(session), 'Content-Type': 'application/json' },
+    body: text,
+  })
+}
+
+async function deliver(path: string, init: RequestInit): Promise<Sent> {
   try {
-    const response = await fetch(path, {
-      method,
-      headers:
-        body === undefined
-          ? actorHeaders(session)
-          : { ...actorHeaders(session), 'Content-Type': 'application/json' },
-      body: body === undefined ? undefined : JSON.stringify(body),
-    })
+    const response = await fetch(path, init)
     if (response.ok) return { kind: 'outcome', outcome: (await response.json()) as OperationOutcome }
     if (response.status === 400 || response.status === 403) {
       // 스프링이 직접 막은 400 의 본문에는 detail 이 없다. 그때도 사유 칸을 비우지 않는다.
@@ -189,3 +319,15 @@ export const registerInstance = (
   adapterVersionId: number,
   fleetEndpoint: string | null,
 ) => send('POST', '/api/adapter-instances', session, { instanceId, adapterVersionId, fleetEndpoint })
+
+export const fetchProfiles = (session: Session) => getJson<ProfileListView>('/api/profiles', session)
+export const submitRevision = (session: Session, document: string) =>
+  sendDocument('/api/profile-revisions', session, document)
+export const requestTest = (session: Session, profileRevisionId: number) =>
+  send('POST', `/api/profile-revisions/${profileRevisionId}/test-requests`, session)
+export const activateRevision = (session: Session, profileRevisionId: number) =>
+  send('POST', `/api/profile-revisions/${profileRevisionId}/activation`, session)
+export const bindRobot = (session: Session, robotId: string, adapterVersionId: number, profileRevisionId: number) =>
+  send('POST', `/api/robots/${encodeURIComponent(robotId)}/binding`, session, { adapterVersionId, profileRevisionId })
+export const recordSiteNames = (session: Session, robotId: string) =>
+  send('POST', `/api/robots/${encodeURIComponent(robotId)}/site-names`, session)
diff --git a/ui/src/components/RobotDetail.tsx b/ui/src/components/RobotDetail.tsx
index dc76cb7..d69b013 100644
--- a/ui/src/components/RobotDetail.tsx
+++ b/ui/src/components/RobotDetail.tsx
@@ -1,6 +1,7 @@
 import { useState } from 'react'
-import type { Mode, RobotView } from '../api'
+import type { Adapter, Mode, RevisionView, RobotView } from '../api'
 import { CONNECTION_LABEL } from '../labels'
+import { CommissioningCards } from './CommissioningCards'
 import { FindingCard } from './FindingCard'
 
 interface Props {
@@ -9,13 +10,27 @@ interface Props {
   busy: boolean
   onRetire: (reason: string) => void
   onReinstate: () => void
+  adapters: Adapter[]
+  revisions: RevisionView[]
+  onBind: (adapterVersionId: number, profileRevisionId: number) => void
+  onRecordSiteNames: () => void
 }
 
 /**
  * 기체 상세. 상태 2칸(원장 상태, 연결)을 합치지 않고 따로 보인다(스펙 §7.3). 퇴역·복귀는 운영자 모드에서 한다(스펙 §8).
  * 퇴역 사유는 필수다. 사유가 비면 요청을 보내지 않는다.
  */
-export function RobotDetail({ view, mode, busy, onRetire, onReinstate }: Props) {
+export function RobotDetail({
+  view,
+  mode,
+  busy,
+  onRetire,
+  onReinstate,
+  adapters,
+  revisions,
+  onBind,
+  onRecordSiteNames,
+}: Props) {
   const [reason, setReason] = useState('')
   const { robot } = view
   const retired = robot.status === 'RETIRED'
@@ -44,6 +59,15 @@ export function RobotDetail({ view, mode, busy, onRetire, onReinstate }: Props)
       ) : (
         view.blockers.map((finding) => <FindingCard key={finding.kind} finding={finding} />)
       )}
+      <CommissioningCards
+        view={view}
+        adapters={adapters}
+        revisions={revisions}
+        mode={mode}
+        busy={busy}
+        onBind={onBind}
+        onRecordSiteNames={onRecordSiteNames}
+      />
       {mode !== 'operator' ? (
         <p>퇴역과 복귀는 운영자 모드에서 합니다</p>
       ) : retired ? (
diff --git a/ui/src/components/RobotsArea.tsx b/ui/src/components/RobotsArea.tsx
index 248eb34..5271605 100644
--- a/ui/src/components/RobotsArea.tsx
+++ b/ui/src/components/RobotsArea.tsx
@@ -1,24 +1,26 @@
 import { useState } from 'react'
-import { declareRobot, reinstateRobot, retireRobot } from '../api'
-import type { AdapterListView, RobotListView, Sent, Session } from '../api'
-import { CONNECTION_LABEL } from '../labels'
+import { bindRobot, declareRobot, recordSiteNames, reinstateRobot, retireRobot } from '../api'
+import type { AdapterListView, ProfileListView, RobotListView, Sent, Session } from '../api'
+import { COMMISSIONING_LABEL, CONNECTION_LABEL } from '../labels'
 import { AdaptersSection } from './AdaptersSection'
 import { DeclareForm } from './DeclareForm'
 import { OutcomeNotice } from './OutcomeNotice'
+import { ProfilesSection } from './ProfilesSection'
 import { RobotDetail } from './RobotDetail'
 
 interface Props {
   view: RobotListView | null
   adapters: AdapterListView | null
-  /** 운영 서비스에 닿지 않으면 [view]·[adapters] 는 직전 값이다. */
+  profiles: ProfileListView | null
+  /** 운영 서비스에 닿지 않으면 [view]·[adapters]·[profiles] 는 직전 값이다. */
   opsError: string | null
   session: Session
   /** 조작이 끝나면 부른다. 목록을 다시 읽는다. */
   onChanged: () => void
 }
 
-/** 로봇·연결 영역. 왼쪽 목록(기체, 어댑터)과 오른쪽 상세(스펙 §8, 결정 6). 조작 결과는 상세 위에 보인다. */
-export function RobotsArea({ view, adapters, opsError, session, onChanged }: Props) {
+/** 로봇·연결 영역. 왼쪽 목록(기체, 어댑터, 프로파일)과 오른쪽 상세(스펙 §8, 결정 6). 조작 결과는 상세 위에 보인다. */
+export function RobotsArea({ view, adapters, profiles, opsError, session, onChanged }: Props) {
   const [selected, setSelected] = useState<string | null>(null)
   const [busy, setBusy] = useState(false)
   const [last, setLast] = useState<{ what: string; sent: Sent } | null>(null)
@@ -56,6 +58,7 @@ export function RobotsArea({ view, adapters, opsError, session, onChanged }: Pro
           </p>
         </section>
         <AdaptersSection view={adapters} opsError={opsError} session={session} busy={busy} run={run} />
+        <ProfilesSection view={profiles} opsError={opsError} session={session} busy={busy} run={run} />
       </div>
       <section aria-label="상세">
         <h2>상세</h2>
@@ -78,6 +81,16 @@ export function RobotsArea({ view, adapters, opsError, session, onChanged }: Pro
             onReinstate={() =>
               run(`${current.robot.robotId} 복귀`, () => reinstateRobot(session, current.robot.robotId))
             }
+            adapters={adapters?.adapters ?? []}
+            revisions={profiles?.revisions ?? []}
+            onBind={(adapterVersionId, profileRevisionId) =>
+              run(`${current.robot.robotId} 바인딩`, () =>
+                bindRobot(session, current.robot.robotId, adapterVersionId, profileRevisionId),
+              )
+            }
+            onRecordSiteNames={() =>
+              run(`${current.robot.robotId} 명칭 기록`, () => recordSiteNames(session, current.robot.robotId))
+            }
           />
         )}
       </section>
@@ -111,11 +124,12 @@ function RobotList({ view, opsError, selected, onSelect }: ListProps) {
               <th>robot_id</th>
               <th>원장 상태</th>
               <th>연결</th>
+              <th>시운전</th>
               <th>막힘</th>
             </tr>
           </thead>
           <tbody>
-            {view.robots.map(({ robot, connection, blockers }) => (
+            {view.robots.map(({ robot, connection, blockers, commissioning }) => (
               <tr key={robot.robotId} className={robot.robotId === selected ? 'selected' : undefined}>
                 <td>
                   <button type="button" className="link" onClick={() => onSelect(robot.robotId)}>
@@ -124,6 +138,7 @@ function RobotList({ view, opsError, selected, onSelect }: ListProps) {
                 </td>
                 <td>{robot.status}</td>
                 <td>{CONNECTION_LABEL[connection]}</td>
+                <td>{commissioning != null ? COMMISSIONING_LABEL[commissioning.state] : '-'}</td>
                 <td>{blockers.length}</td>
               </tr>
             ))}
diff --git a/ui/src/labels.ts b/ui/src/labels.ts
index 1e39116..ec78a73 100644
--- a/ui/src/labels.ts
+++ b/ui/src/labels.ts
@@ -1,4 +1,4 @@
-import type { Connection, Owner } from './api'
+import type { CommissioningState, Connection, Owner, TestRequestState } from './api'
 
 /** 화면에 보이는 이름. 값은 운영 서비스의 열거형 그대로 받고, 이름만 여기서 붙인다. */
 export const CONNECTION_LABEL: Record<Connection, string> = {
@@ -29,7 +29,45 @@ export const KIND_LABEL: Record<string, string> = {
   UNKNOWN_ADAPTER: '없는 제품',
   VERSION_CONFLICT: '같은 버전에 다른 계약값',
   INSTANCE_BAD_REQUEST: '인스턴스 본문 오류',
+  UNBOUND: '바인딩 없음',
+  SITE_NAMES_UNREGISTERED: '명칭 기록 없음',
+  SITE_NAMES_UNANSWERED: '기체가 명칭에 답하지 않음',
+  SITE_NAMES_CONTRADICTED: '기체가 아는 명칭 없음',
+  PROFILE_UNREADABLE: '읽을 수 없는 문서',
+  REVISION_NOT_MONOTONIC: '개정판 번호가 오르지 않음',
+  UNKNOWN_REVISION: '없는 개정판',
+  REVISION_NOT_TESTABLE: '시험할 수 없는 상태',
+  ACTIVATION_REFUSED: '활성화 조건 미달',
+  UNKNOWN_BUILD: '없는 빌드',
+  REVISION_NOT_ACTIVE: '활성 개정판이 아님',
+  ROBOT_RETIRED: '퇴역한 기체',
+  CONTRACT_TOO_OLD: '빌드의 계약이 낮음',
+  NO_ACTIVE_BINDING: '활성 바인딩 없음',
+  NOTHING_TO_REGISTER: '등록할 명칭 없음',
   UNCLASSIFIED: '분류되지 않은 거절',
 }
 
+/** «시운전» 칸(P2·S1d 스펙 §8.5). 연결 칸과 합치지 않는다. */
+export const COMMISSIONING_LABEL: Record<CommissioningState, string> = {
+  COMPLETE: '완료',
+  INCOMPLETE: '미완',
+  RETIRED: '퇴역',
+}
+
+/** 소프트웨어 대조(P2·S1d 스펙 §9). 시운전을 막지 않고 보이기만 한다. 모르는 값은 그대로 보인다. */
+export const SOFTWARE_LABEL: Record<string, string> = {
+  MATCH: '일치',
+  MISMATCH: '불일치',
+  UNREPORTED: '보고 없음',
+}
+
+/** 시험 요청 상태(P2·S1d 스펙 §9). */
+export const TEST_REQUEST_LABEL: Record<TestRequestState, string> = {
+  NONE: '요청 없음',
+  WAITING: '대기',
+  RUNNING: '실행 중',
+  EXPIRED: '만료',
+  DONE: '끝남',
+}
+
 export const kindLabel = (kind: string) => KIND_LABEL[kind] ?? kind
```

- [ ] **Step 5: 통과 확인**

Run: `cd ui && npm test && npm run build && npx oxlint`
Expected: `Tests 47 passed`(기준 30 + 17), 빌드 성공, 린트 경고 없음.

- [ ] **Step 6: 커밋**

```bash
git add ui/src/components/ProfilesSection.tsx ui/src/components/CommissioningCards.tsx ui/src/api.ts ui/src/labels.ts ui/src/App.tsx ui/src/components/RobotsArea.tsx ui/src/components/RobotDetail.tsx ui/src/testing/fakeOps.ts ui/src/components/ProfilesSection.test.tsx ui/src/components/CommissioningCards.test.tsx
git commit -q -F - <<'EOF'
feat(ui): 프로파일 구역과 시운전 칸, 기체 상세 카드 3개

- 작업 묶음 커밋(Task 8 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 6: Playwright 와 README

**Files:**
- Modify: `ui/e2e/lifecycle.spec.ts`(시운전 흐름, 바인딩 전 `UNBOUND`), `README.md`(단계, site, Playwright, 알아 둘 것)

- [ ] **Step 1: 패치** — 아래를 `C:/Users/Eisen/AppData/Local/Temp/s1d-patches/s1d-6.patch` 로 저장하고 `git apply C:/Users/Eisen/AppData/Local/Temp/s1d-patches/s1d-6.patch`. README 문장은 사용자 지시대로 Fable·Codex 초안을 취합한 것이다.

````diff
diff --git a/README.md b/README.md
index 0d715c0..14c55c4 100644
--- a/README.md
+++ b/README.md
@@ -2,13 +2,13 @@
 
 picasso 를 라이브러리로 쓰는 담는 측 저장소입니다. 로봇, 임무, 엔드포인트의 운영 가능성을 PoC 로 입증합니다. 실물 현장은 없습니다. 보안과 인증은 생략합니다. 운영 중 변경은 코드 수정이 아니라 관리 화면에서 처리합니다.
 
-지금 단계는 S1c 어댑터 등록입니다. S1b 로봇 생애주기 위에 올립니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 운영자 모드는 퇴역과 복귀를 합니다. 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.
+지금 단계는 S1d 바인딩·명칭·시운전입니다. S1c 어댑터 등록 위에 올립니다. 서브모듈 `picasso` 는 P2b 머지 커밋 `41beedb` 를 가리킵니다. 그 판에는 P2a 시험 실행기와 P2b 개정판·바인딩 REST 가 들어 있습니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 같은 영역 왼쪽에 프로파일 구역이 있습니다. 그 구역에서 엔지니어 모드는 기종 프로파일 개정판을 파일로 골라 제출하고, 시험을 요청하고, 활성화합니다. 시험 결과는 사람이 적지 않습니다. 런처가 띄운 시험 실행기가 적습니다. 기체 상세에서 엔지니어 모드는 빌드와 활성 개정판을 골라 바인딩하고, 사이트 명칭 등록을 기록합니다. 명칭 티칭은 화면 밖 현장 작업입니다. 기체 목록에 시운전 칸이 있습니다. 값은 완료, 미완, 퇴역입니다. 완료 조건은 셋입니다. 원장 상태가 `CONFIRMED` 이고 퇴역이 아니어야 합니다. 활성 바인딩이 있어야 합니다. 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 여야 합니다. 빠진 조건은 막힘으로 보입니다. 운영자 모드는 퇴역과 복귀를 합니다. 어댑터 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 화면은 배정 가능을 쓰지 않습니다. 그것은 S3 의 몫입니다. S1 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. P2·S1d 설계 스펙은 `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.
 
 ## 구성
 
 - `.env`: 사이트 id(`SITE_ID`), DB 접속값, 포트, 토큰을 둡니다. 로컬 PoC 값입니다. 런처, 운영 서비스, 시험, CI 가 이 파일 하나를 읽습니다.
 - `picasso/`: picasso git 서브모듈입니다. 고정 커밋을 가리킵니다. Gradle includeBuild 로 가져옵니다. 읽기 전용입니다. picasso 쪽 변경은 picasso 저장소의 PR 로 냅니다.
-- `site/`: 가짜 현장 런처입니다(Kotlin). registry 와 mimic 기체를 한 프로세스에서 띄웁니다. 실제 1초마다 가상 1초를 진행합니다. 기체 명부는 `site/robots.json` 입니다. Postgres 용 compose 는 `site/compose.yaml` 입니다. 손 기동 확인은 `site/smoke.sh` 입니다.
+- `site/`: 가짜 현장 런처입니다(Kotlin). registry, mimic 기체, 개정판 시험 실행기(`site-runner`)를 한 프로세스에서 띄웁니다. 실제 1초마다 가상 1초를 진행합니다. 실행기는 실제 1초마다 registry 에서 시험 요청을 집습니다. 실행기는 시험마다 자기 mimic 을 따로 띄웁니다. 그래서 현장 기체의 보고와 상태를 바꾸지 않습니다. 적재 토큰은 런처만 가집니다. 그래서 시험 결과를 적을 수 있는 것도 런처뿐입니다. 기체 명부는 `site/robots.json` 입니다. 기체 명부의 `site_names` 는 현장에서 기체에 티칭한 명칭입니다. 런처가 기동 때 기체에 넣습니다. Postgres 용 compose 는 `site/compose.yaml` 입니다. 손 기동 확인은 `site/smoke.sh` 입니다.
 - `ops-service/`: 운영 서비스입니다(Kotlin, Spring Boot). 화면의 유일한 백엔드입니다. 운영자 토큰을 쥡니다. registry REST 만 부릅니다.
 - `ui/`: 관리 화면입니다(React, Vite, TypeScript). 운영 서비스만 부릅니다.
 - `e2e/`: 통합 시험입니다. 한 JVM 에 Postgres, registry, mimic, 운영 서비스를 띄웁니다.
@@ -72,7 +72,7 @@ cd ui && npx playwright install chromium
 cd ui && npx playwright test
 ```
 
-Playwright 가 Postgres, 런처, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 1초에 가상 1초가 갑니다. 시험은 약 1~2분 걸립니다. Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.
+Playwright 가 Postgres, 런처, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 이어서 개정판 제출, 시험 요청, 실행기의 `TESTED` 기록, 활성화, 바인딩, 명칭 기록, `humanoid-01` 시운전 완료까지 돕니다. `quadruped-01` 의 명칭 기록이 기체가 아는 명칭 없음으로 막히는 것도 봅니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 1초에 가상 1초가 갑니다. 시험은 약 2분 걸립니다. 로컬 실측은 1.9분입니다. Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.
 
 ## 띄우기
 
@@ -125,3 +125,6 @@ Gradle 의 run 작업 2개를 한 작업 트리에서 겹쳐 띄우지 않습니
 - 선언 전 mimic 의 생존 보고는 registry 가 거절합니다. 거절한 보고는 어디에도 남지 않습니다. 기체를 선언하면 보고가 붙습니다.
 - registry 가 답하지 않으면 화면은 목록을 비우지 않습니다. 직전 값과 함께 모름을 보입니다.
 - 손으로 띄운 compose 스택이 있으면 화면 시험이 시작할 때 그것을 볼륨째 내립니다. 같은 compose 프로젝트라서입니다. 데이터가 지워집니다. 남은 컨테이너는 `docker compose -f site/compose.yaml --env-file .env down -v` 로 걷습니다.
+- `site/robots.json` 에서 `humanoid-01` 은 `dock-3` 와 `bay-7` 을 티칭했고, `quadruped-01` 은 티칭하지 않았습니다. 그래서 `quadruped-01` 은 명칭을 기록하면 기체가 아는 명칭 없음으로 막힙니다.
+- registry 는 명칭의 개수만 대조합니다. 이름 자체는 대조하지 않습니다.
+- 시험 결과는 사람이 적지 않습니다. 적재 토큰은 런처만 가지므로 런처가 띄운 시험 실행기만 적습니다.
diff --git a/ui/e2e/lifecycle.spec.ts b/ui/e2e/lifecycle.spec.ts
index f75827a..e2870ff 100644
--- a/ui/e2e/lifecycle.spec.ts
+++ b/ui/e2e/lifecycle.spec.ts
@@ -8,6 +8,8 @@ import { fileURLToPath } from 'node:url'
  * 기체는 site/robots.json 의 humanoid-01 이다. 런처가 mimic 을 띄워 두었으므로 선언하면 보고가 붙는다.
  *
  * 이어서 S1c 의 화면 쪽(스펙 §3). 제품 선언 → 빌드 선언 → 인스턴스 등록 → 인스턴스 목록에 UNTESTED.
+ * 이어서 S1d 의 화면 쪽(P2·S1d 스펙 §3·§11). 개정판 제출(파일 고르기) → 시험 요청 → 현장 실행기가 TESTED → 활성화 →
+ * 바인딩 → 명칭 기록 → «시운전 완료»(humanoid-01). quadruped-01 은 명칭을 티칭하지 않아 «기체가 아는 명칭 없음» 으로 막힌다.
  * registry 를 멈추는 것은 맨 끝이다. 그 뒤로는 조작이 registry 에 닿지 않는다.
  *
  * 선언 직후의 CLAIMED 는 여기서 단언하지 않는다. 실시간 1:1 시계에서는 다음 보고가 1초 안에 올 수도 있어
@@ -27,7 +29,7 @@ test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 reg
   // 보고를 기다려 CONFIRMED 를 본다
   await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
   await expect(detail.getByText('CONFIRMED', { exact: true })).toBeVisible()
-  await expect(detail.getByText('막힘 없음', { exact: true })).toBeVisible()
+  await expect(detail.getByText('바인딩 없음', { exact: true })).toBeVisible()
 
   // 운영자 모드에서 사유를 넣어 퇴역
   await page.getByLabel('운영자').check()
@@ -42,7 +44,7 @@ test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 reg
   // 복귀
   await detail.getByRole('button', { name: '복귀' }).click()
   await expect(detail.getByText('CONFIRMED', { exact: true })).toBeVisible()
-  await expect(detail.getByText('막힘 없음', { exact: true })).toBeVisible()
+  await expect(detail.getByText('바인딩 없음', { exact: true })).toBeVisible()
 
   // 조작 기록
   await page.getByRole('button', { name: '이력' }).click()
@@ -74,6 +76,54 @@ test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 reg
   const instances = page.getByRole('table', { name: '인스턴스 목록' })
   await expect(instances.getByRole('row', { name: /fleet-gw-01/ })).toContainText('UNTESTED')
 
+  // 프로파일: 개정판 둘을 제출하고 시험을 요청하면 현장 실행기가 TESTED 로 올린다. 그 뒤 활성화.
+  const declareQuadruped = page.getByRole('form', { name: '기체 선언' })
+  await declareQuadruped.getByLabel('robot_id').fill('quadruped-01')
+  await declareQuadruped.getByLabel('일련번호').fill('QB-0001')
+  await declareQuadruped.getByRole('button', { name: '선언' }).click()
+  await expect(page.getByText('quadruped-01 선언: 반영됨', { exact: true })).toBeVisible()
+
+  const profiles = page.getByRole('region', { name: '프로파일' })
+  const revisions = profiles.getByRole('table', { name: '개정판 목록' })
+  for (const [model, revision] of [['humanoid-a', 2], ['quadruped-b', 1]] as const) {
+    const submit = profiles.getByRole('form', { name: '개정판 제출' })
+    await submit.getByLabel('프로파일 문서').setInputFiles(fileURLToPath(new URL(`../../picasso/profile/profiles/${model}.json`, import.meta.url)))
+    await submit.getByRole('button', { name: '제출' }).click()
+    await expect(page.getByText(`picasso-ref/${model}#${revision} 제출: 반영됨`, { exact: true })).toBeVisible()
+    const row = revisions.getByRole('row', { name: new RegExp(`picasso-ref/${model} ${revision}`) })
+    await row.getByRole('button', { name: '시험 요청' }).click()
+    await expect(page.getByText(`picasso-ref/${model}#${revision} 시험 요청: 반영됨`, { exact: true })).toBeVisible()
+    await expect(row).toContainText('TESTED')
+    await expect(row).toContainText('PASS (site-runner)')
+    await row.getByRole('button', { name: '활성화' }).click()
+    await expect(page.getByText(`picasso-ref/${model}#${revision} 활성화: 반영됨`, { exact: true })).toBeVisible()
+    await expect(row).toContainText('ACTIVE')
+  }
+
+  // 바인딩과 명칭 기록. humanoid-01 은 현장에서 명칭을 티칭했으므로 기록하면 시운전 완료다.
+  for (const [robotId, model, revision] of [['humanoid-01', 'humanoid-a', 2], ['quadruped-01', 'quadruped-b', 1]] as const) {
+    await page.getByRole('button', { name: robotId, exact: true }).click()
+    const robot = page.getByRole('region', { name: `${robotId} 상세` })
+    const bind = robot.getByRole('form', { name: '바인딩' })
+    await bind.getByLabel('빌드').selectOption('acme/fleet 1.0.0')
+    await bind.getByLabel('개정판').selectOption(`picasso-ref/${model}#${revision}`)
+    await bind.getByRole('button', { name: '바인딩' }).click()
+    await expect(page.getByText(`${robotId} 바인딩: 반영됨`, { exact: true })).toBeVisible()
+    await expect(robot.getByText('명칭 기록 없음', { exact: true })).toBeVisible()
+    await robot.getByRole('button', { name: '명칭 등록 기록' }).click()
+    await expect(page.getByText(`${robotId} 명칭 기록: 반영됨`, { exact: true })).toBeVisible()
+  }
+  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
+  await expect(detail.getByRole('heading', { name: '시운전: 완료' })).toBeVisible()
+  await expect(detail.getByText('막힘 없음', { exact: true })).toBeVisible()
+
+  // quadruped-01 은 명칭을 티칭하지 않았다. 사람은 기록했는데 기체가 아는 명칭이 없다.
+  await page.getByRole('button', { name: 'quadruped-01', exact: true }).click()
+  const quadruped = page.getByRole('region', { name: 'quadruped-01 상세' })
+  await expect(quadruped.getByRole('heading', { name: '시운전: 미완' })).toBeVisible()
+  await expect(quadruped.getByText('기체가 아는 명칭 없음', { exact: true })).toBeVisible()
+  await expect(quadruped.getByText(/현장\(화면 밖\): 현장에서 명칭 티칭을 다시/)).toBeVisible()
+
   // registry 를 멈춘다. 런처(registry 와 mimic 이 든 프로세스)를 끈다.
   const pidFile = fileURLToPath(new URL('../../build/site.pid', import.meta.url))
   process.kill(Number(readFileSync(pidFile, 'utf8')))
@@ -85,4 +135,5 @@ test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 reg
   const adapters = page.getByRole('region', { name: '어댑터' })
   await expect(adapters.getByText(/직전 값입니다/)).toBeVisible()
   await expect(adapters.getByRole('row', { name: /fleet-gw-01/ })).toBeVisible()
+  await expect(profiles.getByText(/직전 값입니다/)).toBeVisible()
 })
````

- [ ] **Step 2: Playwright** — 작업 위치 규칙의 compose 확인을 먼저 한다.

Run: `./gradlew :site:installDist :ops-service:installDist -q` 그리고 `cd ui && npx playwright test`
Expected: `1 passed`. 스파이크 실측 1.9분.

- [ ] **Step 3: 커밋과 대조**

```bash
git add ui/e2e/lifecycle.spec.ts README.md
git commit -q -F - <<'EOF'
test(ui): Playwright 시운전 흐름과 README 단계 설명

- 작업 묶음 커밋(Task 8 에서 하나로 합침)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
bash C:/Users/Eisen/AppData/Local/Temp/s1d-cmp.sh $(git diff --name-only f84e095 HEAD -- . ':!picasso' ':!docs')
```
Expected: 37줄 모두 «같음».

### Task 7: 결함 주입(시험이 잡는가)

**Files:** 아래 파일들(넣고 되돌린다)

주입마다: 옛 문자열을 새 문자열로 바꾸고, 그 시험만 돌리고, 실패 시험 이름에 기대한 이름이 있는지 보고, 되돌린다. 한 번에 하나만 넣는다. 되돌린 뒤 `git status --short` 가 비어야 한다. 경로 머리 `ops/` 는 `ops-service/src/main/kotlin/dev/picasso/ops/service/`, `site/` 는 `site/src/main/kotlin/dev/picasso/ops/site/`, `ui/` 는 `ui/src/` 다. Gradle 은 `./gradlew :<모듈>:test --tests <클래스> -q` 뒤 XML, vitest 는 `npx vitest run <파일> --reporter=junit --outputFile=<보고서>` 뒤 보고서의 실패 이름으로 본다.

| ID | 파일 | 옛 → 새 | 시험 | 빨개져야 할 시험 |
|---|---|---|---|---|
| K1 | `ops/robots/Commissioning.kt` | `setOf("CONFIRMED", "NOT_REQUIRED")` → `setOf("CONFIRMED")` | `CommissioningJudgeTest` | `세 조건이 모두 맞아야 완료이고 빠진 것이 막힘으로 보인다` |
| K2 | 같은 파일 | `blockers` 의 `if (robot.status == "RETIRED") return emptyList()` 줄 지움 | 같음 | 위와 같음 |
| K3 | 같은 파일 | 지원 안 함 갈래의 `Owner.ENGINEER, true` → `Owner.SITE, false` | 같음 | `기체가 명칭을 지원하지 않는다고 답했으면 다시 티칭이 아니라 엔지니어가 프로파일을 본다` |
| K4 | `ops/operations/ProfileRejections.kt` | `finding(ROBOT_RETIRED, "복귀 뒤 다시", Owner.OPERATOR)` → `Owner.OPERATOR` 인자 지움 | `ProfileRejectionsTest` | `대응표의 행마다 종류와 해결 담당이 맞다` |
| K5 | 같은 파일 | `val target = if (op == ProfileOp.BIND \|\| op == ProfileOp.RECORD_SITE_NAMES) robotId else null` → `val target = robotId` | 같음 | `바인딩·명칭 거절의 바로 가기는 그 기체이고 개정판 거절은 바로 가기가 없다` |
| K6 | `ops/robots/RobotListService.kt` | `(bindings !is RegistryCall.Ok \|\| software !is RegistryCall.Ok)` → `(bindings !is RegistryCall.Ok)` | `RobotCommissioningListTest` | `바인딩이나 소프트웨어 대조를 못 읽으면 셋 다 직전 값이다` |
| K7 | 같은 파일 | `it.robotId == robot.robotId && it.active` → `&& it.active` 지움 | 같음 | `이력 행은 활성 바인딩으로 보지 않는다` |
| K8 | `ops/profiles/ProfileListService.kt` | `if (!at.isBefore(expires))` → `if (at.isAfter(expires))` | `ProfileListServiceTest` | `만료 시각과 같은 시각은 이미 만료다 - registry 가 그 순간부터 다시 집어 준다` |
| K17 | 같은 파일 | `if (catalog == RegistryCall.Unauthorized \|\| revisions == RegistryCall.Unauthorized)` → `if (catalog == RegistryCall.Unauthorized)` | 같음 | `카탈로그는 읽혀도 개정판 목록이 401 이면 토큰 불일치다` |
| K9 | `ops/operations/ProfileOperations.kt` | `seen != null && seen.documentHash == hash,` → `seen != null,` | `ProfileOperationsTest` | `응답 없는 제출은 같은 기종·번호가 같은 문서 해시로 있어야 반영됨이다` |
| K18 | `ops/operations/ProfileOperations.kt` | 제출 재조회의 `seen?.let { json.createObjectNode().put("profile_revision_id", it.profileRevisionId).put("status", it.status) },` → `null,` | `ProfileOperationsTest` | `응답 없는 제출은 같은 기종·번호가 같은 문서 해시로 있어야 반영됨이다` |
| K10 | 같은 파일 | `(latest.completedAt == null \|\| Instant.parse(latest.requestedAt).isAfter(sentAt))` → `Instant.parse(latest.requestedAt).isAfter(sentAt)` | 같음 | `응답 없는 시험 요청은 열린 요청이 있거나 보낸 뒤에 만든 끝난 요청이 있어야 반영됨이다` |
| K11 | 같은 파일 | 바인딩 재조회의 `seen.adapterVersionId == adapterVersionId && ` 지움 | 같음 | `응답 없는 바인딩은 그 기체의 활성 바인딩이 요청한 빌드와 개정판이어야 반영됨이다` |
| K12 | `ops/registry/RegistryClient.kt` | `HttpRequest.BodyPublishers.ofByteArray(document))` → `HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(json.readTree(document))))` | `RegistryProfilesTest` | `제출은 본문 바이트를 다시 직렬화하지 않고 그대로 보낸다` |
| K13 | `ops/web/ProfileOperationsController.kt` | `val build = body.adapterVersionId` 와 다음 줄 → 둘 다 `?: 0L` 을 붙임 | `e2e` 의 `CommissioningTest` | `사전 거절은 registry 에 닿지 않고 조작 기록에 남지 않는다` |
| K14 | `site/Site.kt` | `config.roster.forEach { mimic.instance(it.robotId)?.knownSiteNames = it.siteNames }` 줄 지움 | `site` 의 `SiteRunnerTest` | `명부의 명칭이 기체 보고로 닿고 다시 티칭하면 다음 보고부터 바뀐다` |
| K15 | 같은 파일 | `).start(RUNNER_INTERVAL)` → `).let { AutoCloseable {} }` | 같음 | `제출하고 시험을 요청하면 현장의 실행기가 집어 TESTED 로 올린다` |
| K16 | `site/RobotRoster.kt` | `RosterEntry(field("robot_id"), field("serial"), field("profile"), siteNames(node, index))` → 넷째 인자 지움 | `RosterSiteNamesTest` | `저장소의 명부는 humanoid-01 만 명칭을 티칭했다` |
| U1 | `ui/components/ProfilesSection.tsx` | `{run.result} ({run.ranBy})` → `{run.result}` | `ProfilesSection.test.tsx` | `카탈로그 한 줄과 개정판마다 상태·스위트 결과와 실행 주체·시험 요청 상태가 보인다` |
| U2 | `ui/labels.ts` | `RUNNING: '실행 중'` 과 `EXPIRED: '만료'` 의 이름을 서로 바꿈 | 같음 | 위와 같음 |
| U3 | `ui/components/CommissioningCards.tsx` | `revisions.filter((row) => row.revision.status === 'ACTIVE')` → `revisions` | `CommissioningCards.test.tsx` | `바인딩 폼은 활성 개정판만 고르게 하고 고른 빌드·개정판 id 를 보낸다` |
| U4 | `ui/api.ts` | `sendDocument` 의 `body: text,` → `body: JSON.stringify(JSON.parse(text)),` | `ProfilesSection.test.tsx` | `제출은 고른 파일의 글자를 그대로 보낸다` |
| U5 | `ui/components/CommissioningCards.tsx` | `const engineer = mode === 'engineer'` → `const engineer = true` | `CommissioningCards.test.tsx` | `운영자 모드에서는 바인딩 폼과 명칭 기록 버튼이 없다` |
| U6 | 같은 파일 | «사람이 기록함» 칸의 `binding.siteNamesRegisteredAt === null` → `binding.siteNamesReportedAt === null` | 같음 | `사람이 기록하지 않았으면 기체가 답했어도 기록 없음으로 보인다` |
| U7 | `ui/components/CommissioningCards.tsx` | «기체가 답함» 칸의 `: binding.siteNamesUnsupported` → `: false` | `CommissioningCards.test.tsx` | `기체의 답은 아직 답 없음과 명칭을 지원하지 않음을 가른다` |
| U8 | `ui/App.tsx` | `fetchProfiles(session), fetchOperations(session)` 의 `fetchProfiles(session)` → `fetchProfiles(session).catch(() => null)` | `ProfilesSection.test.tsx` | `프로파일 목록만 못 읽어도 넷 다 직전 값이다` |
| P1 | `ops/OpsApplication.kt` | `RobotListService(registry, registry, siteId.value, clock, threshold, commissioning = registry)` → `, commissioning = registry` 지움 | Playwright(`npx playwright test`) | `1 failed`, 바인딩 전 `바인딩 없음` 단언에서 멈춤 |

스파이크에서 26건(K1~K18, U1~U8) 모두 기대한 이름이 빨개졌고, P1 은 Playwright 가 32행(바인딩 전 `바인딩 없음`)에서 실패했다. 처음 돌렸을 때 U6 이 안 잡혔다. 시험 대역의 기본 바인딩이 기록 시각과 보고 시각을 둘 다 가져 두 칸을 바꿔 읽어도 같은 갈래로 갔다. 사람이 기록하지 않았는데 기체가 답한 바인딩의 시험을 더했고, 위 시험이 그것이다.

- [ ] **Step 1: K1~K18 과 U1~U8 을 하나씩** — 위 표대로.
- [ ] **Step 2: P1** — Playwright 는 한 번에 약 2분이다. 배포본을 다시 만든 뒤(`:ops-service:installDist`) 돌리고, 되돌린 뒤 배포본을 다시 만든다.
- [ ] **Step 3: 되돌림 확인**

Run: `git status --short`
Expected: 빈 출력.

### Task 8: 전체 빌드, 새 클론, 합치기와 PR

- [ ] **Step 1: 전체 빌드** — 백그라운드로 돌린다.

Run: `./gradlew build --continue -q`
Expected: XML 기준 site 17, ops-service 107, e2e 30, 실패 0.

- [ ] **Step 2: 새 클론 검증** — 워크트리의 커밋을 `C:/Users/Eisen/AppData/Local/Temp/s1d-clean` 에 `git clone -b feat/s1d-commissioning <워크트리> <경로>` 로 새로 클론하고, 서브모듈을 `--reference` 로 채운 뒤 `./gradlew build --continue -q` 와 `cd ui && npm ci && npm test` 를 돌린다. Expected: 위와 같은 수, vitest `Tests 47 passed`.
- [ ] **Step 3: 하나로 합치기** — 이 계획 문서의 커밋(브랜치의 첫 커밋, S1c 처럼 구현 PR 에 함께 든다) 위의 묶음 커밋 여섯(Task 1~6)을 `git reset --soft <계획 커밋>` 으로 합치고, 커밋 메시지는 Fable·Codex 초안을 취합해 heredoc 으로 쓴다. 트리 해시가 합치기 전과 같아야 한다.
- [ ] **Step 4: 푸시와 PR** — PR 본문은 개요 / 주요 변경 사항 / 검증 결과 세 절이며 Fable·Codex 초안 취합, 끝 줄 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. CI 의 job 3개(`gradle`, `ui`, `playwright`)가 초록이면 머지한다(사용자가 2026-10-08 자율 진행과 머지를 지시).

## 실행 결과

(실행 뒤 채운다)
