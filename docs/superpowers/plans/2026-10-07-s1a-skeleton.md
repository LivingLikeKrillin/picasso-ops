# S1a 골격 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** picasso-ops 의 골격을 세운다. 서브모듈과 포함 빌드, docker compose Postgres, `site/` 런처(registry + mimic), 운영 서비스 골격(registry 클라이언트, ops 스키마, 조작 기록, `X-Actor`), 화면 골격(5영역 메뉴, 모드 전환, 이력 영역), CI 까지. 완료 판정은 스펙 §3 의 S1a 행이다. 통합 시험이 전체를 띄우고 운영 서비스의 기체 목록 조회가 빈 목록을 돌려준다. registry 를 멈추면 화면 전체 상태가 «모름» 이 된다. CI 가 초록이다.

**Architecture:** Gradle 다중 모듈(`site`, `ops-service`, `e2e`)이 picasso 를 `includeBuild` 로 가져오고 좌표 2개(`dev.picasso:registry`, `dev.picasso:mimic`)를 명시 치환한다. `site` 는 registry 스키마 Flyway, registry 기동(`SpringApplicationBuilder`), mimic 기동(`MimicCli.start`, 가상 시계)을 한 프로세스에서 하고 실제 1초마다 가상 1초를 민다. `ops-service` 는 picasso 에 의존하지 않고 registry REST 만 부르며, ops 스키마를 코드의 Flyway 로 올린다. `e2e` 는 한 JVM 에 Postgres(registry `testFixtures`)·registry·mimic·운영 서비스를 띄운다. `ui` 는 Gradle 과 묶지 않은 Vite 프로젝트다.

**Tech Stack:** Kotlin 2.4.20, Spring Boot 3.4.0(BOM 만, 플러그인 없음), Gradle 9.7.1(picasso 래퍼), PostgreSQL 16 + Flyway 10.20.1, Testcontainers(registry `testFixtures` 의 `PostgresSupport`), JUnit5 + kotlin.test, React 19 + Vite 8 + TypeScript 6 + vitest 5(판은 `create-vite@9.2.1` 의 react-ts 틀이 정한다), GitHub Actions.

**근거 스펙:** `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` §3(S1a 행), §4, §6, §7.1~7.2, §7.4(화면 전체 상태), §8, §9, §10.

**스펙과 다른 결정(이 계획이 정함):**
1. **서브모듈을 `6b1a255` 에 고정한다.** 스펙 §3 은 S1a·S1b 를 `cd688ff` 에 고정하고 P1 머지 뒤 포인터를 옮기는 커밋을 S1c 의 첫 커밋으로 적었다. P1 이 S1a 착수 전에 머지됐으므로(picasso PR #79, 머지 커밋 `6b1a255`) 처음부터 그 커밋에 고정한다. S1c 의 첫 커밋은 필요 없어진다. 스펙 정정은 Task 10 이 한다.
2. **`.env` 에 `SITE_ID` 말고도 로컬 값을 둔다.** DB 접속값, 포트 2개, 토큰 2개다. 인증을 생략하는 PoC 의 로컬 값이며(스펙 §1), 런처·운영 서비스·시험·CI 가 같은 파일을 읽는 한 출처 원칙(스펙 §4·§6)을 그대로 따른다.
3. **운영 서비스는 Spring Boot 의 Flyway 자동설정을 끄고 ops 스키마를 코드로 올린다.** 자동설정의 기본 위치(`classpath:db/migration`)는 통합 시험 JVM 에서 registry 마이그레이션을 집어 온다. 스펙 §7.1 의 위치·스키마·이력 테이블 규칙은 그대로다.
4. **Spring Boot Gradle 플러그인을 쓰지 않는다.** registry 와 같이 BOM 과 `application` 플러그인으로 띄운다. 판이 picasso 카탈로그 밖으로 나가지 않는다(스펙 §4).
5. **사용자 이름은 `[A-Za-z0-9._-]` 1~64자만 받는다.** 스펙 §7.1 의 «사용자» 에는 제약이 없다. registry 로 가는 `X-Actor` 헤더가 ASCII 만 실을 수 있고 `/` 가 모드와 사용자를 가르는 자리라서 한글 이름은 받지 않는다.
6. **S1a 에서는 `REGISTRY_UNAUTHORIZED` 가 나오지 않는다.** picasso `6b1a255` 의 운영자 토큰 관문은 `/operations` 이하만 덮고, S1a 의 유일한 읽기 `/diag/robots` 는 관문 밖이다. 토큰이 틀려도 S1a 화면은 `OK` 이고, 토큰 불일치는 S1b 의 첫 조작에서 드러난다. 401 분류 코드와 화면 표시는 S1b 가 쓰므로 S1a 에 둔다.
7. **토큰은 스펙 §4 의 표대로 나눠 준다.** `.env` 하나에 두되, 적재 토큰은 mimic 이 있는 `site` 만 받는다. `site` 는 같은 프로세스에서 registry(토큰을 검증하는 쪽)를 띄우므로 운영자 토큰도 받는다. registry 와 운영 서비스는 `127.0.0.1` 에만 연다(토큰이 공개 저장소의 `.env` 에 있다). mimic 의 gRPC 는 picasso 가 주소를 정하므로 모든 인터페이스에 열린다.
8. **선언 전 mimic 보고의 거절은 어디에도 보이지 않는다.** 스펙 §6 은 «`site/` 로그에서만 보인다» 고 적었으나, uplink 의 `IngestBridge` 가 생존 보고 결과를 `runCatching` 으로 버리고 registry 도 남기지 않는다. 런처의 안내문은 이 사실대로 쓴다. 스펙 정정은 Task 10 이 한다.

**스크래치에서 미리 확인한 것(2026-10-07, picasso `6b1a255`, Docker 26.1.4):** 이 계획의 Kotlin·TS 코드와 Task 9 의 손 기동 스크립트는 같은 내용으로 스크래치 빌드에서 돌렸다. Kotlin 시험 38개(site 12, ops-service 23, e2e 3)와 vitest 8개가 통과했다. 각 작업의 결함 주입은 적힌 실패 이름 그대로 잡혔다. 손 기동에서는 기체를 선언하고 18초 뒤 `CONFIRMED` 가 되어 1:1 시간 진행을 확인했다. 스펙 §10 이 S1a 첫 작업으로 미룬 물음 2개의 답은 다음과 같다.
- registry `testFixtures` 는 포함 빌드에서 `testFixtures("dev.picasso:registry")` 로 쓸 수 있다(좌표 치환과 맞물린다).
- `PostgresSupport.reset()` 은 기본 스키마(public)만 지운다. ops 스키마는 남는다. 그래서 e2e 는 ops 스키마를 따로 지운다(Task 7).
- 덧붙여 드러난 것: mimic 의 `slf4j-nop` 과 Spring Boot 의 logback 이 한 클래스패스에 오면 Spring 이 기동을 거부한다. 루트 빌드가 `slf4j-nop` 을 뺀다(Task 1).
- 덧붙여 드러난 것: KDoc 안에 `/operations/` 뒤 별표 둘을 쓰면 `/`+`*` 가 중첩 주석을 열어 컴파일이 깨진다. 주석에는 «`/operations` 이하» 로 쓴다.

**작업 위치 규칙(필수):**
- picasso-ops 체크아웃(`C:\Users\Eisen\Desktop\Labs\[projects] picasso-ops`)의 브랜치 `feat/s1a-skeleton` 에서 일한다. picasso 메인 체크아웃은 건드리지 않는다.
- **서브모듈 `picasso/` 안의 파일을 고치지 않는다**(스펙 §4). 읽기만 한다.
- `./gradlew --stop` 금지(데몬 풀이 사용자 체크아웃과 공유된다). 같은 작업 트리에서 Gradle 을 동시에 두 번 돌리지 않는다(시험 결과 디렉터리가 깨진다).
- `git add -A` 금지. 파일을 이름으로 더한다.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다. 아래의 «XML 확인» 이 그 명령이다.
- 새 파일은 LF 로 쓴다(`*.bat` 만 CRLF). Task 1 의 `.gitattributes` 가 집행한다.
- 작업마다 임시 커밋을 남기고(`chore(s1a): ...`), Task 10 에서 하나로 합친다. 커밋 트레일러는 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` 이다. 마지막 커밋·PR 의 문장은 사용자 지시에 따라 Fable 과 Codex 에 같은 브리프로 초안을 받아 취합한다(Gemini 한도 소진 중). 형식: 제목 `type(scope): 명사구`, 불릿 명사형, «~다» 종결 금지, em-dash·en-dash·겹화살괄호·낫표 금지.

**전제(컨트롤러가 먼저 한다):** 이 계획 파일은 picasso-ops main 에 커밋되고 origin 에 푸시되어 있다(P1 계획 `8241a70` 과 같은 방식, 푸시는 사용자 승인 뒤). 그래야 Task 0 의 `## main...origin/main` 과 «변경 없음» 이 성립한다.

**XML 확인**(모듈 이름을 바꿔 쓴다):

```bash
for f in site/build/test-results/test/*.xml; do grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"; done
for f in site/build/test-results/test/*.xml; do grep -B1 "<failure" "$f" | grep -o 'testcase name="[^"]*"'; done
```

첫 줄은 모음마다 개수, 둘째 줄은 실패한 시험 이름이다.

---

## Chunk 1: 저장소 골격과 가짜 현장

### Task 0: 브랜치와 환경 확인

**Files:** 없음(환경)

- [ ] **Step 1: 깨끗한 main 에서 브랜치 만들기**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops"
git status -sb
git switch -c feat/s1a-skeleton
```
Expected: 첫 줄 `## main...origin/main`, 변경 없음. 변경이 있으면 멈추고 보고한다.

- [ ] **Step 2: 도구 확인**

Run: `docker info --format '{{.ServerVersion}}'; java -version; node --version; npm --version`
Expected: Docker 버전 문자열, `java -version` 17 이상(Gradle 9 데몬의 조건), Node 22 이상. Docker 가 없으면 멈추고 사용자에게 알린다(시험이 Testcontainers 를 쓴다). JDK 21 은 Gradle 툴체인이 받는다.

### Task 1: 서브모듈, 래퍼, 루트 빌드

**Files:**
- Create: `.gitmodules`, `picasso`(서브모듈, `git submodule add` 가 만든다)
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`(picasso 에서 복사)
- Create: `.gitattributes`, `.env`, `settings.gradle.kts`, `build.gradle.kts`
- Modify: `.gitignore`

- [ ] **Step 1: 서브모듈 추가와 고정**

```bash
git submodule add https://github.com/LivingLikeKrillin/picasso.git picasso
git -C picasso checkout -q 6b1a255
git -C picasso log --oneline -1
```
Expected: `6b1a255 Merge pull request #79 from LivingLikeKrillin/feat/adapter-build-rest`. `Filename too long` 가 나면 `git -C picasso config core.longpaths true && git -C picasso checkout -f -q 6b1a255` 로 다시 한다(전역 설정은 바꾸지 않는다).

- [ ] **Step 2: 래퍼 복사**

```bash
cp picasso/gradlew picasso/gradlew.bat .
mkdir -p gradle && cp -r picasso/gradle/wrapper gradle/
```

- [ ] **Step 3: `.gitattributes` 쓰기**

이 기계는 `core.autocrlf=true` 다. 강제하지 않으면 새 Windows 체크아웃에서 `.env` 가 CRLF 가 되어 `. ./.env` 가 `\r` 붙은 값을 내보낸다.

```
* text=auto eol=lf
*.bat text eol=crlf
*.jar binary
```

- [ ] **Step 4: `.gitignore` 를 다음으로 바꾸기**

```
.superpowers/
.gradle/
.kotlin/
build/
```

- [ ] **Step 5: `.env` 쓰기**

```
# picasso-ops 로컬 PoC 값. 인증을 생략하는 PoC 이며(스펙 §1) 이 기계 밖에서 쓰지 않는다.
# 런처(site/)·운영 서비스(ops-service/)·시험·CI 가 이 파일 하나를 읽는다(스펙 §4·§6).
SITE_ID=site-01
PICASSO_DB_URL=jdbc:postgresql://127.0.0.1:55432/picasso
PICASSO_DB_USER=picasso
PICASSO_DB_PASSWORD=picasso
REGISTRY_PORT=8781
OPS_PORT=8782
PICASSO_OPERATOR_TOKEN=local-operator-token
PICASSO_INGEST_TOKEN=local-ingest-token
```

- [ ] **Step 6: `settings.gradle.kts` 쓰기**

`include` 줄은 모듈을 만드는 작업(Task 2·4·7)이 하나씩 더한다. 여기서는 넣지 않는다.

```kotlin
rootProject.name = "picasso-ops"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

// 판은 picasso 의 카탈로그 하나에서 온다(스펙 §4). 여기 따로 적으면 두 저장소의 판이 갈라진다.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("picasso/gradle/libs.versions.toml")) }
    }
}

// picasso 서브프로젝트에는 Gradle group 이 없어 좌표가 자동으로 맞지 않는다(스펙 §11).
// 쓰는 모듈만 명시한다. uplink 는 mimic 의 api 로 따라온다.
includeBuild("picasso") {
    dependencySubstitution {
        substitute(module("dev.picasso:registry")).using(project(":registry"))
        substitute(module("dev.picasso:mimic")).using(project(":mimic"))
    }
}
```

- [ ] **Step 7: `build.gradle.kts` 쓰기**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

/**
 * 루트 `.env`. 런처·운영 서비스의 실행 작업과 시험이 같은 값을 환경 변수로 받는다(스펙 §4).
 * Spring Boot 는 `.env` 를 스스로 읽지 않는다.
 */
val dotenv: Map<String, String> = file(".env").readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .associate { line ->
        require("=" in line) { ".env 줄에 '=' 가 없다: $line" }
        line.substringBefore("=").trim() to line.substringAfter("=").trim()
    }

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories { mavenCentral() }

    configurations.configureEach {
        // picasso 루트 build.gradle.kts 와 같은 이유다. 낮은 protobuf-java 가 이기면 컴파일이 아니라 런타임에 터진다.
        resolutionStrategy.force(
            "com.google.protobuf:protobuf-java:${rootProject.libs.versions.protobuf.get()}",
        )
        // mimic 의 slf4j-nop 과 Spring Boot 의 logback 이 함께 있으면 Spring 이 기동을 거부한다
        // (picasso registry/build.gradle.kts 의 주석). registry 와 mimic 이 한 클래스패스에 오는 곳마다 걸린다.
        exclude(group = "org.slf4j", module = "slf4j-nop")
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(21)
        // picasso 루트와 같이 언어 판을 적는다. 안 적으면 플러그인 판을 올릴 때 언어 판이 함께 움직인다.
        compilerOptions {
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        }
    }

    dependencies {
        add("testImplementation", rootProject.libs.junit.jupiter)
        add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
    }

    // 토큰을 쥐는 곳은 스펙 §4 의 표대로다. 적재 토큰은 mimic 이 있는 site 만 받는다.
    // site 가 운영자 토큰도 받는 것은 같은 프로세스에서 registry(토큰을 검증하는 쪽)를 띄우기 때문이다.
    val env = if (name == "site") dotenv else dotenv - "PICASSO_INGEST_TOKEN"

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        environment(env)
        // environment(...) 는 더하기만 한다. 셸에서 상속된 적재 토큰도 site 밖에서는 지운다.
        if (project.name != "site") environment.remove("PICASSO_INGEST_TOKEN")
        // 환경 변수는 Gradle 의 시험 입력 추적에 들어가지 않는다. 선언하지 않으면 .env 를 고쳐도 UP-TO-DATE 다.
        inputs.file(rootProject.file(".env")).withPropertyName("dotenv")
    }

    tasks.withType<JavaExec>().configureEach {
        environment(env)
        if (project.name != "site") environment.remove("PICASSO_INGEST_TOKEN")
    }
}
```

- [ ] **Step 8: 빌드가 포함 빌드를 보는지 확인**

Run: `./gradlew projects --console=plain`
Expected: `Root project 'picasso-ops'` 와 `Included builds` 아래 `picasso`. 실패하면 `.env` 파싱 오류나 카탈로그 경로부터 본다.

- [ ] **Step 9: 임시 커밋**

```bash
git add .gitmodules picasso gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties .gitattributes .gitignore .env settings.gradle.kts build.gradle.kts
git update-index --chmod=+x gradlew
git commit -m "chore(s1a): 서브모듈과 루트 빌드" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```
Expected: `git ls-files -s gradlew` 의 모드가 `100755`.

### Task 2: `site/` 기체 명부와 런처 설정

**Files:**
- Modify: `settings.gradle.kts`(끝에 `include("site")`)
- Create: `site/build.gradle.kts`, `site/robots.json`
- Create: `site/src/main/kotlin/dev/picasso/ops/site/RobotRoster.kt`, `site/src/main/kotlin/dev/picasso/ops/site/SiteConfig.kt`
- Test: `site/src/test/kotlin/dev/picasso/ops/site/RobotRosterTest.kt`, `site/src/test/kotlin/dev/picasso/ops/site/SiteConfigTest.kt`

- [ ] **Step 1: 모듈 등록과 빌드 파일**

`settings.gradle.kts` 끝에 `include("site")` 를 더한다. `site/build.gradle.kts`:

```kotlin
// 가짜 현장 런처(스펙 §6). registry 스키마 마이그레이션, registry 기동, mimic 기동과 시간 진행을 맡는다.
plugins {
    application
}

application {
    mainClass.set("dev.picasso.ops.site.SiteLauncherKt")
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
    implementation("dev.picasso:registry")
    implementation("dev.picasso:mimic")

    // registry 는 Spring Boot·Flyway 를 implementation 으로만 쓴다. 런처가 직접 부르므로 여기서도 적는다.
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.jackson.databind)

    testImplementation(kotlin("test"))
    testImplementation(testFixtures("dev.picasso:registry"))
}

// 저장소 루트에서 돈다. robots.json 의 프로파일 경로가 루트 기준이다.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.withType<Test>().configureEach {
    // 시험이 저장소 파일을 읽는다. 선언하지 않으면 파일을 고쳐도 시험이 UP-TO-DATE 로 넘어간다.
    inputs.file(rootProject.file("site/robots.json")).withPropertyName("roster")
    inputs.dir(rootProject.file("picasso/profile/profiles")).withPropertyName("profiles")
    inputs.dir(rootProject.file("picasso/profile/schema")).withPropertyName("profileSchema")
}
```

`site/robots.json`:

```json
[
  { "robot_id": "humanoid-01", "serial": "HA-0001", "profile": "picasso/profile/profiles/humanoid-a.json" },
  { "robot_id": "quadruped-01", "serial": "QB-0001", "profile": "picasso/profile/profiles/quadruped-b.json" }
]
```

- [ ] **Step 2: 실패하는 시험 쓰기**

`site/src/test/kotlin/dev/picasso/ops/site/RobotRosterTest.kt`:

```kotlin
package dev.picasso.ops.site

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RobotRosterTest {

    @Test
    fun `저장소의 robots_json 은 기체 2대이고 프로파일 파일이 실재한다`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val roster = RobotRoster.read(root.resolve(SiteConfig.ROSTER))
        assertEquals(listOf("humanoid-01", "quadruped-01"), roster.map { it.robotId })
        roster.forEach { assertTrue(Files.isRegularFile(root.resolve(it.profile)), "프로파일이 없다: ${it.profile}") }
    }

    @Test
    fun `칸 값의 앞뒤 공백을 지우고 읽는다`() {
        val roster = RobotRoster.parse("""[{"robot_id":" r1 ","serial":"S1","profile":"p.json"}]""")
        assertEquals(listOf(RosterEntry("r1", "S1", "p.json")), roster)
    }

    @Test
    fun `빈 일련번호를 거절한다`() {
        val e = assertFailsWith<IllegalArgumentException> {
            RobotRoster.parse("""[{"robot_id":"r1","serial":"  ","profile":"p.json"}]""")
        }
        assertTrue("'serial'" in e.message!!, e.message)
    }

    @Test
    fun `같은 robot_id 두 번을 거절한다`() {
        val e = assertFailsWith<IllegalArgumentException> {
            RobotRoster.parse(
                """[{"robot_id":"r1","serial":"S1","profile":"p"},{"robot_id":"r1","serial":"S2","profile":"p"}]""",
            )
        }
        assertTrue("r1" in e.message!!, e.message)
    }

    @Test
    fun `빈 명부를 거절한다`() {
        assertFailsWith<IllegalArgumentException> { RobotRoster.parse("[]") }
    }
}
```

`site/src/test/kotlin/dev/picasso/ops/site/SiteConfigTest.kt`:

```kotlin
package dev.picasso.ops.site

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SiteConfigTest {

    private val root = Path.of("..").toAbsolutePath().normalize()

    private val env = mapOf(
        "SITE_ID" to "site-x",
        "PICASSO_DB_URL" to "jdbc:postgresql://h/db",
        "PICASSO_DB_USER" to "u",
        "PICASSO_DB_PASSWORD" to "p",
        "REGISTRY_PORT" to "8781",
        "PICASSO_OPERATOR_TOKEN" to "op",
        "PICASSO_INGEST_TOKEN" to "in",
    )

    @Test
    fun `환경 변수와 명부에서 설정을 만든다`() {
        val config = SiteConfig.fromEnv(env, root)
        assertEquals("site-x", config.siteId)
        assertEquals(8781, config.registryPort)
        assertEquals(DbConfig("jdbc:postgresql://h/db", "u", "p"), config.db)
        assertEquals(2, config.roster.size)
        assertEquals(root.resolve(SiteConfig.PROFILE_SCHEMA), config.schema)
    }

    @Test
    fun `SITE_ID 가 없으면 기동하지 않는다`() {
        val e = assertFailsWith<IllegalArgumentException> { SiteConfig.fromEnv(env - "SITE_ID", root) }
        assertTrue("SITE_ID" in e.message!!, e.message)
    }

    @Test
    fun `포트가 정수가 아니면 기동하지 않는다`() {
        assertFailsWith<IllegalArgumentException> { SiteConfig.fromEnv(env + ("REGISTRY_PORT" to "x"), root) }
    }

    @Test
    fun `루트 env 의 SITE_ID 가 시험 환경 변수로 넘어온다`() {
        // Gradle 이 루트 .env 를 환경 변수로 넘긴다(루트 build.gradle.kts). 어긋나면 한 출처가 끊긴 것이다.
        val fromFile = java.nio.file.Files.readAllLines(root.resolve(".env"))
            .single { it.startsWith("SITE_ID=") }
            .substringAfter("=")
            .trim()
        assertTrue(fromFile.isNotEmpty())
        assertEquals(fromFile, System.getenv("SITE_ID"))
    }

    @Test
    fun `site 만 적재 토큰을 받는다`() {
        assertTrue(System.getenv("PICASSO_INGEST_TOKEN").isNullOrEmpty().not())
    }
}
```

- [ ] **Step 3: 시험이 실패하는지 확인**

Run: `./gradlew :site:test --tests '*RobotRosterTest*' --tests '*SiteConfigTest*' --console=plain`
Expected: `compileTestKotlin` 실패(`RobotRoster`·`SiteConfig` 없음). picasso 모듈이 처음 컴파일되므로 몇 분 걸린다.

- [ ] **Step 4: 구현**

`site/src/main/kotlin/dev/picasso/ops/site/RobotRoster.kt`:

```kotlin
package dev.picasso.ops.site

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/** `site/robots.json` 의 한 줄. 현장으로 치면 기체 명판이다(스펙 §6). [profile] 은 저장소 루트 기준 경로다. */
data class RosterEntry(val robotId: String, val serial: String, val profile: String)

/** 기체 명부. 런처가 이것으로 mimic 을 띄우고, 운영자와 시험이 이것을 보고 기체를 선언한다(스펙 §6). */
object RobotRoster {

    fun read(file: Path): List<RosterEntry> = parse(Files.readString(file))

    fun parse(json: String): List<RosterEntry> {
        val root = ObjectMapper().readTree(json)
        require(root.isArray) { "robots.json 은 배열이어야 한다" }
        val entries = root.mapIndexed { index, node ->
            fun field(name: String): String {
                val value = node.get(name)?.takeIf { it.isTextual }?.asText()?.trim()
                require(!value.isNullOrEmpty()) { "robots.json ${index}번째 기체에 '$name' 이 없거나 비었다" }
                return value
            }
            RosterEntry(field("robot_id"), field("serial"), field("profile"))
        }
        require(entries.isNotEmpty()) { "robots.json 에 기체가 없다" }
        val duplicated = entries.groupBy { it.robotId }.filterValues { it.size > 1 }.keys
        // 조용히 덮으면 기체 하나가 사라진 채 기동한다(mimic CLI 의 --robot 과 같은 이유).
        require(duplicated.isEmpty()) { "robots.json 에 같은 robot_id 가 두 번 있다: $duplicated" }
        return entries
    }
}
```

`site/src/main/kotlin/dev/picasso/ops/site/SiteConfig.kt`:

```kotlin
package dev.picasso.ops.site

import java.nio.file.Path

data class DbConfig(val url: String, val user: String, val password: String)

/**
 * 런처 설정. 값은 루트 `.env` 에서 환경 변수로 온다(스펙 §4). 사이트 id 와 기체 명부는 한 출처에서 나온다(스펙 §6 ④).
 *
 * @param registryPort 0 이면 무작위 포트(시험).
 */
data class SiteConfig(
    val root: Path,
    val siteId: String,
    val db: DbConfig,
    val registryPort: Int,
    val operatorToken: String,
    val ingestToken: String,
    val roster: List<RosterEntry>,
) {
    val schema: Path get() = root.resolve(PROFILE_SCHEMA)

    fun profile(entry: RosterEntry): Path = root.resolve(entry.profile)

    companion object {
        const val PROFILE_SCHEMA = "picasso/profile/schema/capability-profile.schema.json"
        const val ROSTER = "site/robots.json"

        fun fromEnv(env: Map<String, String>, root: Path): SiteConfig {
            fun need(key: String): String = env[key]?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("환경 변수 $key 가 없다(루트 .env 를 확인)")
            val port = need("REGISTRY_PORT")
            return SiteConfig(
                root = root,
                siteId = need("SITE_ID"),
                db = DbConfig(need("PICASSO_DB_URL"), need("PICASSO_DB_USER"), need("PICASSO_DB_PASSWORD")),
                registryPort = port.toIntOrNull()
                    ?: throw IllegalArgumentException("REGISTRY_PORT 가 정수가 아니다: $port"),
                operatorToken = need("PICASSO_OPERATOR_TOKEN"),
                ingestToken = need("PICASSO_INGEST_TOKEN"),
                roster = RobotRoster.read(root.resolve(ROSTER)),
            )
        }
    }
}
```

- [ ] **Step 5: 시험이 통과하는지 확인**

Run: `./gradlew :site:test --tests '*RobotRosterTest*' --tests '*SiteConfigTest*' --console=plain`
Expected: XML 확인에서 `RobotRosterTest` 5개, `SiteConfigTest` 5개, 실패 0.

- [ ] **Step 6: 결함 주입**

`RobotRoster.parse` 의 중복 검사 `require(duplicated.isEmpty())` 줄을 지우고 Step 5 를 다시 돈다.
Expected: XML 의 실패 이름에 `같은 robot_id 두 번을 거절한다()`. 확인 뒤 되돌리고 Step 5 를 다시 돌려 초록을 본다.

- [ ] **Step 7: 임시 커밋**

```bash
git add settings.gradle.kts site/build.gradle.kts site/robots.json site/src/main/kotlin/dev/picasso/ops/site/RobotRoster.kt site/src/main/kotlin/dev/picasso/ops/site/SiteConfig.kt site/src/test/kotlin/dev/picasso/ops/site/RobotRosterTest.kt site/src/test/kotlin/dev/picasso/ops/site/SiteConfigTest.kt
git commit -m "chore(s1a): 기체 명부와 런처 설정" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

### Task 3: `site/` 런처와 docker compose

**Files:**
- Create: `site/src/main/kotlin/dev/picasso/ops/site/Site.kt`, `site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt`, `site/compose.yaml`
- Test: `site/src/test/kotlin/dev/picasso/ops/site/SiteTest.kt`

- [ ] **Step 1: 실패하는 시험 쓰기**

`site/src/test/kotlin/dev/picasso/ops/site/SiteTest.kt`:

```kotlin
package dev.picasso.ops.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.registry.PostgresSupport
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 런처가 registry 와 mimic 을 띄우고, 시간을 밀면 생존 보고가 registry 에 닿는다(스펙 §6). */
class SiteTest {

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

    private fun robots(site: Site): JsonNode {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("${site.registryUrl}/diag/robots?retired=true&site=site-test")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())
    }

    private fun declare(site: Site, robotId: String, serial: String) {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("${site.registryUrl}/operations/robots"))
                .header("Authorization", "Bearer op-test")
                .header("X-Actor", "engineer/site-test")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"robot_id":"$robotId","site":"site-test","serial_number":"$serial"}""",
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(201, response.statusCode(), response.body())
    }

    @Test
    fun `선언한 기체는 시간을 밀면 생존 보고로 CONFIRMED 가 된다`() {
        PostgresSupport.reset()
        Site.start(config()).use { site ->
            assertEquals(setOf("humanoid-01", "quadruped-01"), site.robotIds)
            assertEquals(0, robots(site).size())

            declare(site, "humanoid-01", "HA-0001")
            assertEquals("CLAIMED", robots(site).single()["status"].asText())

            // 프로파일의 publish_interval.max_seconds 30 을 넘겨 민다.
            site.advance(Duration.ofSeconds(31))
            val robot = robots(site).single()
            assertEquals("CONFIRMED", robot["status"].asText())
            assertEquals(false, robot["lastReportedAt"].isNull)
        }
    }

    @Test
    fun `registry 만 멈추면 그 주소가 답하지 않는다`() {
        PostgresSupport.reset()
        Site.start(config()).use { site ->
            site.stopRegistry()
            assertFailsWith<ConnectException> {
                http.send(
                    HttpRequest.newBuilder(URI.create("${site.registryUrl}/diag/robots")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }
        }
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 확인**

Run: `./gradlew :site:test --tests '*SiteTest' --console=plain`
Expected: `compileTestKotlin` 실패(`Site` 없음).

- [ ] **Step 3: 구현**

`site/src/main/kotlin/dev/picasso/ops/site/Site.kt`:

```kotlin
package dev.picasso.ops.site

import dev.picasso.mimic.cli.MimicCli
import dev.picasso.registry.web.RegistryApplication
import dev.picasso.uplink.report.RegistryLink
import org.flywaydb.core.Flyway
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.time.Duration

/**
 * 띄운 가짜 현장 하나. registry 와 mimic 을 이 프로세스 안에 둔다(스펙 §6).
 *
 * mimic CLI 를 쓰지 않는 이유는 스펙 §6 첫 문단이다. CLI 에는 시간을 진행시키는 루프가 없고,
 * registry 는 실행 jar 가 없으며 기동 때 Flyway 를 돌리지 않는다.
 */
class Site private constructor(
    private val registry: ConfigurableApplicationContext,
    private val mimic: MimicCli.Started,
    val registryUrl: String,
    val robotIds: Set<String>,
) : AutoCloseable {

    /** mimic 의 가상 시계를 민다. 상태 발행이 이 시계로 정해지고, 상태 발행이 곧 생존 보고다. */
    fun advance(by: Duration) = mimic.server.advance(by)

    /** registry 만 멈춘다. 운영 서비스가 «모름» 을 보이는지 볼 때 쓴다(스펙 §3 S1a). */
    fun stopRegistry() = registry.close()

    override fun close() {
        mimic.server.shutdown()
        if (registry.isActive) registry.close()
    }

    companion object {

        fun start(config: SiteConfig): Site {
            // ① registry 스키마는 런처가 올린다. ops 스키마는 운영 서비스의 몫이다(스펙 §6 ①, §7.1).
            migrateRegistrySchema(config.db)

            // ② registry 를 클래스패스로 띄운다. 설정은 실행 인자로만 넣는다(스펙 §10).
            val registry = SpringApplicationBuilder(RegistryApplication::class.java).run(
                "--server.port=${config.registryPort}",
                // 토큰이 공개 저장소의 .env 에 있으므로 이 기계 밖에 열지 않는다. mimic 의 gRPC 는 picasso 가
                // 주소를 정하므로(모든 인터페이스) 여기서 못 바꾼다.
                "--server.address=127.0.0.1",
                "--picasso.db.url=${config.db.url}",
                "--picasso.db.user=${config.db.user}",
                "--picasso.db.password=${config.db.password}",
                "--picasso.operator.token=${config.operatorToken}",
                "--picasso.ingest.token=${config.ingestToken}",
                "--picasso.profile.schema=${config.schema}",
            )
            val port = (registry as WebServerApplicationContext).webServer.port
            val registryUrl = "http://127.0.0.1:$port"

            // ③ mimic 을 가상 시계로 띄운다. 실시간 시계는 advance 에서 오류를 낸다(스펙 §6 ③).
            // ④ 사이트 id 는 .env 의 SITE_ID 하나에서 온다.
            val err = StringBuilder()
            val mimic = try {
                MimicCli().start(
                    robots = config.roster.associate { it.robotId to config.profile(it) },
                    schema = config.schema,
                    port = 0,
                    virtual = true,
                    seed = 0L,
                    err = err,
                    // 폴백 디렉터리는 두지 않는다. 생존 보고는 폴백이 없고, S1 은 태스크 관측을 쓰지 않는다.
                    link = RegistryLink.http(registryUrl, config.ingestToken, config.siteId, null),
                    broker = null,
                    site = config.siteId,
                )
            } catch (e: Exception) {
                registry.close()
                throw e
            }
            if (mimic == null) {
                registry.close()
                error("mimic 기동 거부: $err")
            }
            return Site(registry, mimic, registryUrl, mimic.robotIds)
        }

        /** registry jar 의 `classpath:db/migration` 을 기본 스키마(public)에 올린다. registry 시험 픽스처와 같은 설정이다. */
        fun migrateRegistrySchema(db: DbConfig): Int =
            Flyway.configure()
                .dataSource(db.url, db.user, db.password)
                .load()
                .migrate()
                .migrationsExecuted
    }
}
```

`site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt`:

```kotlin
package dev.picasso.ops.site

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 실제 1초마다 가상 1초를 민다(1:1, 스펙 §6 ③). 비율을 바꾸면 운영 서비스의 연결 기준 90초(스펙 §7.3)도 다시 정한다.
 * 주기와 전진량이 같은 값이어야 1:1 이므로 하나로 둔다.
 */
val TICK: Duration = Duration.ofSeconds(1)

fun main() {
    val root = Path.of("").toAbsolutePath()
    val config = SiteConfig.fromEnv(System.getenv(), root)
    val site = Site.start(config)
    println("registry: ${site.registryUrl} (site=${config.siteId})")
    println("mimic: ${site.robotIds.sorted()}")
    // uplink 가 거절 결과를 버리고 registry 도 남기지 않아서, 선언 전 보고의 거절은 어디에도 안 보인다.
    println("선언 전 mimic 의 생존 보고는 registry 가 거절하며 이 로그에도 남지 않는다. 기체를 선언하면 보고가 붙는다")

    val ticker = Executors.newSingleThreadScheduledExecutor()
    ticker.scheduleAtFixedRate(
        { runCatching { site.advance(TICK) }.onFailure { System.err.println("시간 진행 실패: $it") } },
        TICK.toMillis(),
        TICK.toMillis(),
        TimeUnit.MILLISECONDS,
    )
    Runtime.getRuntime().addShutdownHook(
        Thread {
            ticker.shutdownNow()
            site.close()
        },
    )
    Thread.currentThread().join()
}
```

`site/compose.yaml`:

```yaml
# registry 와 운영 서비스가 같이 쓰는 Postgres(스펙 §2 결정 3). 실행: 저장소 루트에서
#   docker compose -f site/compose.yaml --env-file .env up -d
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: picasso
      POSTGRES_USER: ${PICASSO_DB_USER:?--env-file .env 로 띄운다}
      POSTGRES_PASSWORD: ${PICASSO_DB_PASSWORD:?--env-file .env 로 띄운다}
    ports:
      - "127.0.0.1:55432:5432"
    # `up --wait` 이 initdb 뒤 재기동까지 기다리게 한다. 없으면 Flyway 가 그 사이에 붙을 수 있다.
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d picasso"]
      interval: 2s
      timeout: 3s
      retries: 30
```

- [ ] **Step 4: 시험이 통과하는지 확인**

Run: `./gradlew :site:test --console=plain`
Expected: XML 확인에서 `SiteTest` 2개, `RobotRosterTest` 5개, `SiteConfigTest` 5개(모듈 12개), 실패 0. registry 와 Spring 로그가 많이 나오는 것은 정상이다. `LoggerFactory is not a Logback LoggerContext` 가 보이면 루트 빌드의 `slf4j-nop` 제외가 빠진 것이다.

- [ ] **Step 5: 결함 주입 2건(하나씩)**

① `Site.start` 의 `virtual = true` 를 `virtual = false` 로 바꾼다(실시간 시계의 `advance` 오류).
② `link = RegistryLink.http(...)` 를 `link = RegistryLink.none()` 으로 바꾼다(보고 경로가 끊긴다).
둘 다 Expected 실패 이름: `선언한 기체는 시간을 밀면 생존 보고로 CONFIRMED 가 된다()`. 각각 되돌리고 초록을 본다.

- [ ] **Step 6: 임시 커밋**

```bash
git add site/src/main/kotlin/dev/picasso/ops/site/Site.kt site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt site/compose.yaml site/src/test/kotlin/dev/picasso/ops/site/SiteTest.kt
git commit -m "chore(s1a): 가짜 현장 런처와 compose" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Chunk 2: 운영 서비스의 저장과 registry 클라이언트

### Task 4: ops 스키마, 조작 기록, `Actor`

**Files:**
- Modify: `settings.gradle.kts`(끝에 `include("ops-service")`)
- Create: `ops-service/build.gradle.kts`
- Create: `ops-service/src/main/resources/db/ops/V1__operation_log.sql`
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/store/OpsSchema.kt`
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/actor/Actor.kt`
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/log/OperationLog.kt`
- Test: `ops-service/src/test/kotlin/dev/picasso/ops/service/ActorTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/OperationLogTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/EnvBoundaryTest.kt`

- [ ] **Step 1: 모듈 등록과 빌드 파일**

`settings.gradle.kts` 끝에 `include("ops-service")` 를 더한다. `ops-service/build.gradle.kts`:

```kotlin
// 운영 서비스(스펙 §7). 화면의 유일한 백엔드이며 운영자 토큰을 쥔다.
// **main 은 picasso 모듈에 의존하지 않는다.** registry REST 만 부른다(스펙 §4).
plugins {
    application
}

application {
    mainClass.set("dev.picasso.ops.service.OpsApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
    // registry 와 같이 BOM 만 쓰고 Spring Boot 플러그인은 붙이지 않는다. 판이 picasso 카탈로그 밖으로 나가지 않는다.
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.jackson.module.kotlin)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(kotlin("test"))
    // 시험용 Postgres 는 registry 의 testFixtures 를 쓴다. 복사하지 않는다(스펙 §10).
    testImplementation(testFixtures("dev.picasso:registry"))
}

/**
 * «main 은 picasso 모듈에 의존하지 않는다»(스펙 §4)를 빌드가 집행한다. 시험 클래스패스는 registry testFixtures 로
 * picasso 를 보므로 main 의 runtimeClasspath 만 본다. picasso 모듈은 포함 빌드 `:picasso` 의 프로젝트로 보인다.
 */
val checkNoPicassoOnMain by tasks.registering {
    val root = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    doLast {
        val seen = mutableSetOf<org.gradle.api.artifacts.component.ComponentIdentifier>()
        fun walk(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
            if (!seen.add(component.id)) return
            component.dependencies
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .forEach { walk(it.selected) }
        }
        walk(root.get())
        val leaked = seen
            .filterIsInstance<org.gradle.api.artifacts.component.ProjectComponentIdentifier>()
            .map { it.buildTreePath }
            .filter { it.startsWith(":picasso:") }
        check(leaked.isEmpty()) { "ops-service main 이 picasso 모듈에 의존한다: $leaked" }
    }
}

tasks.named("check") { dependsOn(checkNoPicassoOnMain) }
```

- [ ] **Step 2: 실패하는 시험 쓰기**

`ops-service/src/test/kotlin/dev/picasso/ops/service/ActorTest.kt`:

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ActorTest {

    @Test
    fun `X-Actor 는 모드 소문자와 사용자를 사선으로 잇는다`() {
        assertEquals("engineer/kim", Actor(Mode.ENGINEER, "kim").header())
        assertEquals("operator/lee.j", Actor(Mode.OPERATOR, "lee.j").header())
    }

    @Test
    fun `요청 헤더에서 모드와 사용자를 읽는다`() {
        assertEquals(Actor(Mode.OPERATOR, "kim"), Actor.fromHeaders(" Operator ", " kim "))
    }

    @Test
    fun `모르는 모드나 빈 사용자는 널이다`() {
        assertNull(Actor.fromHeaders("admin", "kim"))
        assertNull(Actor.fromHeaders(null, "kim"))
        assertNull(Actor.fromHeaders("engineer", " "))
        assertNull(Actor.fromHeaders("engineer", null))
    }

    @Test
    fun `헤더에 못 싣는 사용자 이름은 받지 않는다`() {
        assertNull(Actor.fromHeaders("engineer", "a/b"))
        assertNull(Actor.fromHeaders("engineer", "김"))
        assertFailsWith<IllegalArgumentException> { Actor(Mode.ENGINEER, "a b") }
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/EnvBoundaryTest.kt`(루트 빌드가 적재 토큰을 거르는지 본다):

```kotlin
package dev.picasso.ops.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 적재 토큰은 mimic 이 있는 site 만 쥔다(스펙 §4). 운영 서비스에는 넘어오지 않는다. */
class EnvBoundaryTest {

    @Test
    fun `운영 서비스는 적재 토큰을 받지 않고 나머지 env 값은 받는다`() {
        assertNull(System.getenv("PICASSO_INGEST_TOKEN"))
        // 값을 시험에 다시 적지 않고 루트 .env 에서 읽어 맞댄다.
        val siteId = Files.readAllLines(Path.of("..").toAbsolutePath().normalize().resolve(".env"))
            .single { it.startsWith("SITE_ID=") }
            .substringAfter("=")
            .trim()
        assertEquals(siteId, System.getenv("SITE_ID"))
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/OperationLogTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.SQLException
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OperationLogTest {

    private val dataSource = DriverManagerDataSource(
        PostgresSupport.jdbcUrl,
        PostgresSupport.username,
        PostgresSupport.password,
    )
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val json = ObjectMapper()
    private val kim = Actor(Mode.OPERATOR, "kim")

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `칸은 9개다`() {
        val columns = PostgresSupport.queryAll(
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'ops' AND table_name = 'operation_log' ORDER BY ordinal_position
            """.trimIndent(),
        ) { it.getString(1) }
        assertEquals(
            listOf(
                "request_id", "mode", "actor_user", "target", "request",
                "reason", "result", "registry_response", "recorded_at",
            ),
            columns,
        )
    }

    @Test
    fun `조작 한 건을 그대로 남긴다`() {
        val id = UUID.randomUUID()
        log.append(id, kim, "robot humanoid-01", """{"reason":"정비"}""", "정비", OperationResult.SUCCEEDED, """{"status":200}""")
        val record = log.list().single()
        assertEquals(id, record.requestId)
        assertEquals(Mode.OPERATOR, record.mode)
        assertEquals("kim", record.user)
        assertEquals("robot humanoid-01", record.target)
        assertEquals(json.readTree("""{"reason":"정비"}"""), json.readTree(record.request))
        assertEquals("정비", record.reason)
        assertEquals(OperationResult.SUCCEEDED, record.result)
        assertEquals(json.readTree("""{"status":200}"""), json.readTree(record.registryResponse))
    }

    @Test
    fun `응답 없음 뒤 재조회는 같은 요청 id 로 새 행을 붙이고 처음 행을 남긴다`() {
        val id = UUID.randomUUID()
        log.append(id, kim, "robot r1", "{}", "정비", OperationResult.NO_RESPONSE, null)
        log.append(id, kim, "robot r1", "{}", "정비", OperationResult.CONFIRMED_APPLIED, null)
        val rows = log.list().filter { it.requestId == id }
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
    }

    @Test
    fun `조작 기록은 고칠 수 없다`() {
        log.append(UUID.randomUUID(), kim, "robot r1", "{}", null, OperationResult.REJECTED, null)
        val e = assertFailsWith<SQLException> { PostgresSupport.execute("UPDATE ops.operation_log SET reason = 'x'") }
        assertTrue("덧붙이기만" in e.message!!, e.message)
    }

    @Test
    fun `조작 기록은 지울 수 없다`() {
        log.append(UUID.randomUUID(), kim, "robot r1", "{}", null, OperationResult.REJECTED, null)
        val e = assertFailsWith<SQLException> { PostgresSupport.execute("DELETE FROM ops.operation_log") }
        assertTrue("덧붙이기만" in e.message!!, e.message)
    }

    @Test
    fun `조작 기록은 통째로 비울 수 없다`() {
        log.append(UUID.randomUUID(), kim, "robot r1", "{}", null, OperationResult.REJECTED, null)
        val e = assertFailsWith<SQLException> { PostgresSupport.execute("TRUNCATE ops.operation_log") }
        assertTrue("덧붙이기만" in e.message!!, e.message)
    }

    @Test
    fun `ops 마이그레이션은 ops 스키마에만 들어간다`() {
        val placed = PostgresSupport.queryOne(
            """
            SELECT to_regclass('ops.operation_log')::text, to_regclass('public.operation_log')::text,
                   to_regclass('ops.flyway_schema_history')::text
            """.trimIndent(),
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
        assertEquals(Triple("ops.operation_log", null, "ops.flyway_schema_history"), placed)
    }
}
```

- [ ] **Step 3: 시험이 실패하는지 확인**

Run: `./gradlew :ops-service:test --console=plain`
Expected: `compileTestKotlin` 실패(`Actor`·`OperationLog`·`OpsSchema` 없음). `EnvBoundaryTest` 는 컴파일되면 처음부터 통과한다(Task 1 의 루트 빌드를 본다).

- [ ] **Step 4: 구현**

`ops-service/src/main/resources/db/ops/V1__operation_log.sql`:

```sql
-- 조작 기록(스펙 §7.1). 칸은 9개다. 재조회 행도 같은 9칸을 쓴다.
CREATE TABLE operation_log (
    request_id        UUID        NOT NULL,
    mode              TEXT        NOT NULL CHECK (mode IN ('ENGINEER', 'OPERATOR')),
    actor_user        TEXT        NOT NULL CHECK (actor_user <> ''),
    target            TEXT        NOT NULL,
    request           JSONB       NOT NULL,
    reason            TEXT,
    result            TEXT        NOT NULL CHECK (result IN (
                          'SUCCEEDED', 'REJECTED', 'NO_RESPONSE',
                          'CONFIRMED_APPLIED', 'CONFIRMED_NOT_APPLIED')),
    registry_response JSONB,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX operation_log_request_id ON operation_log (request_id);

-- 덧붙이기만 한다. 고치지 않고 삭제하지 않는다(스펙 §7.1). 코드의 약속이 아니라 스키마가 막는다.
CREATE FUNCTION operation_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '조작 기록은 덧붙이기만 한다(%)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER operation_log_no_update_delete
    BEFORE UPDATE OR DELETE ON operation_log
    FOR EACH ROW EXECUTE FUNCTION operation_log_append_only();

-- 행 트리거는 TRUNCATE 에서 돌지 않는다. 통째로 비우는 것도 지우는 것이다.
CREATE TRIGGER operation_log_no_truncate
    BEFORE TRUNCATE ON operation_log
    FOR EACH STATEMENT EXECUTE FUNCTION operation_log_append_only();
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/store/OpsSchema.kt`:

```kotlin
package dev.picasso.ops.service.store

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * ops 스키마의 Flyway(스펙 §7.1). 위치는 `classpath:db/ops`, 스키마와 이력 테이블은 `ops` 다.
 *
 * **Spring Boot 의 Flyway 자동설정을 쓰지 않는다.** 자동설정의 기본 위치는 `classpath:db/migration` 이고,
 * 통합 시험 JVM 에는 registry jar 가 같은 클래스패스에 있어 registry 마이그레이션을 집어 온다.
 * 위치와 스키마를 이 한 곳에 두고 기동과 시험이 같이 쓴다.
 */
object OpsSchema {
    const val SCHEMA = "ops"
    const val LOCATION = "classpath:db/ops"

    fun flyway(dataSource: DataSource, cleanable: Boolean = false): Flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .locations(LOCATION)
            .cleanDisabled(!cleanable)
            .load()
}

/** 기동 때 올린 마이그레이션 수. 조작 기록 빈이 이것에 기대어 마이그레이션 뒤에 만들어진다. */
data class OpsSchemaMigrated(val migrationsExecuted: Int)
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/actor/Actor.kt`:

```kotlin
package dev.picasso.ops.service.actor

/** 화면의 모드(스펙 §8). 등록·어댑터 조작은 엔지니어, 퇴역·복귀는 운영자. */
enum class Mode(val wire: String) {
    ENGINEER("engineer"),
    OPERATOR("operator"),
    ;

    companion object {
        fun parse(raw: String?): Mode? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
    }
}

/**
 * 조작한 사람. 인증 없이 화면이 요청 헤더로 싣는다(스펙 §7.1).
 *
 * 사용자 이름은 `[A-Za-z0-9._-]` 만 받는다. registry 로 가는 `X-Actor` 헤더가 ASCII 만 실을 수 있고,
 * `/` 는 모드와 사용자를 가르는 자리라서다.
 */
data class Actor(val mode: Mode, val user: String) {

    init {
        require(USER.matches(user)) { "사용자 이름은 영문·숫자·._- 로 1~64자다: '$user'" }
    }

    /** registry 로 보내는 `X-Actor` 값. registry `audit_log.actor` 와 조작 기록을 맞대 보려고 이 형식을 쓴다. */
    fun header(): String = "${mode.wire}/$user"

    companion object {
        const val MODE_HEADER = "X-Ops-Mode"
        const val USER_HEADER = "X-Ops-User"
        private val USER = Regex("[A-Za-z0-9._-]{1,64}")

        /** 헤더가 없거나 틀리면 널이다. 조작 API 가 400 으로 돌려보낸다(S1b). */
        fun fromHeaders(mode: String?, user: String?): Actor? {
            val parsedMode = Mode.parse(mode) ?: return null
            val trimmed = user?.trim() ?: return null
            return if (USER.matches(trimmed)) Actor(parsedMode, trimmed) else null
        }
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/log/OperationLog.kt`:

```kotlin
package dev.picasso.ops.service.log

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 조작 결과(스펙 §7.1). 앞의 3개는 조작 행, 뒤의 2개는 «응답 없음» 뒤 재조회 행이다.
 * 재조회 행은 같은 요청 id 로 새로 붙고, 처음 행은 그대로 남는다.
 */
enum class OperationResult { SUCCEEDED, REJECTED, NO_RESPONSE, CONFIRMED_APPLIED, CONFIRMED_NOT_APPLIED }

/** 조작 기록 한 행. 칸 9개(스펙 §7.1). [request]·[registryResponse] 는 JSON 문자열이다. */
data class OperationRecord(
    val requestId: UUID,
    val mode: Mode,
    val user: String,
    val target: String,
    val request: String,
    val reason: String?,
    val result: OperationResult,
    val registryResponse: String?,
    val recordedAt: Instant,
)

/** ops 스키마의 조작 기록. 덧붙이기와 읽기만 있다. 고치기·지우기는 스키마의 트리거가 막는다. */
class OperationLog(private val jdbc: JdbcClient) {

    fun append(
        requestId: UUID,
        actor: Actor,
        target: String,
        request: String,
        reason: String?,
        result: OperationResult,
        registryResponse: String?,
    ) {
        jdbc.sql(
            """
            INSERT INTO ops.operation_log
                (request_id, mode, actor_user, target, request, reason, result, registry_response)
            VALUES
                (:requestId, :mode, :user, :target, CAST(:request AS JSONB), :reason, :result,
                 CAST(:registryResponse AS JSONB))
            """.trimIndent(),
        )
            .param("requestId", requestId)
            .param("mode", actor.mode.name)
            .param("user", actor.user)
            .param("target", target)
            .param("request", request, Types.VARCHAR)
            .param("reason", reason, Types.VARCHAR)
            .param("result", result.name)
            .param("registryResponse", registryResponse, Types.VARCHAR)
            .update()
    }

    /**
     * 최근 것부터. 순서 칸을 따로 두지 않는다(칸 9개, 스펙 §7.1). `recorded_at` 은 문장마다 `clock_timestamp()` 라
     * 실제로는 겹치지 않지만, 같은 시각이면 둘의 순서는 정하지 않는다.
     */
    fun list(limit: Int = 200): List<OperationRecord> =
        jdbc.sql(
            """
            SELECT request_id, mode, actor_user, target, request::text AS request, reason, result,
                   registry_response::text AS registry_response, recorded_at
            FROM ops.operation_log
            ORDER BY recorded_at DESC
            LIMIT :limit
            """.trimIndent(),
        )
            .param("limit", limit)
            .query { rs, _ ->
                OperationRecord(
                    requestId = rs.getObject("request_id", UUID::class.java),
                    mode = Mode.valueOf(rs.getString("mode")),
                    user = rs.getString("actor_user"),
                    target = rs.getString("target"),
                    request = rs.getString("request"),
                    reason = rs.getString("reason"),
                    result = OperationResult.valueOf(rs.getString("result")),
                    registryResponse = rs.getString("registry_response"),
                    recordedAt = rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }
            .list()
}
```

- [ ] **Step 5: 시험이 통과하는지 확인**

Run: `./gradlew :ops-service:test --console=plain`
Expected: XML 확인(`ops-service/...`)에서 `ActorTest` 4개, `OperationLogTest` 7개, `EnvBoundaryTest` 1개, 실패 0. 그리고 `./gradlew :ops-service:checkNoPicassoOnMain --console=plain` 이 성공한다.

- [ ] **Step 6: 결함 주입 4건(하나씩)**

① `V1__operation_log.sql` 에서 `CREATE TRIGGER operation_log_no_update_delete ... ;` 문 하나만 지운다(파일이 문법상 온전해야 한다). Expected 실패 이름: 정확히 `조작 기록은 고칠 수 없다()` 와 `조작 기록은 지울 수 없다()` 2개. 7개가 다 빨개지면 SQL 을 깨뜨린 것이므로 주입을 다시 한다.
② `CREATE TRIGGER operation_log_no_truncate ... ;` 문 하나만 지운다. Expected 실패 이름: `조작 기록은 통째로 비울 수 없다()`.
③ 셸에서 `export PICASSO_INGEST_TOKEN=leaked` 한 뒤, 루트 `build.gradle.kts` 의 `environment.remove("PICASSO_INGEST_TOKEN")` 두 줄을 지우고 `./gradlew :ops-service:test --tests '*EnvBoundaryTest' --rerun --console=plain` 을 돈다. Expected 실패 이름: `운영 서비스는 적재 토큰을 받지 않고 나머지 env 값은 받는다()`. **`--rerun` 이 필요하다.** 빌드 스크립트만 바꾸면 시험 입력이 그대로라 UP-TO-DATE 로 넘어가 초록이 나온다(스크래치에서 실측). 끝나면 `unset PICASSO_INGEST_TOKEN`.
④ `ops-service/build.gradle.kts` 의 `dependencies` 에 `implementation("dev.picasso:registry")` 를 더하고 `./gradlew :ops-service:checkNoPicassoOnMain --console=plain` 을 돈다. Expected: `ops-service main 이 picasso 모듈에 의존한다: [:picasso:registry, ...]` 로 실패.
각각 되돌리고 초록을 본다.

- [ ] **Step 7: 임시 커밋**

```bash
git add settings.gradle.kts ops-service/build.gradle.kts ops-service/src/main/resources/db/ops/V1__operation_log.sql ops-service/src/main/kotlin/dev/picasso/ops/service/store/OpsSchema.kt ops-service/src/main/kotlin/dev/picasso/ops/service/actor/Actor.kt ops-service/src/main/kotlin/dev/picasso/ops/service/log/OperationLog.kt ops-service/src/test/kotlin/dev/picasso/ops/service/ActorTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/OperationLogTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/EnvBoundaryTest.kt
git commit -m "chore(s1a): ops 스키마와 조작 기록" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

### Task 5: registry 클라이언트

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt`
- Test: `ops-service/src/test/kotlin/dev/picasso/ops/service/RegistryClientTest.kt`

- [ ] **Step 1: 실패하는 시험 쓰기**

JDK 의 `com.sun.net.httpserver.HttpServer` 로 registry 대역을 세운다. Docker 가 필요 없다.

`ops-service/src/test/kotlin/dev/picasso/ops/service/RegistryClientTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryClient
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegistryClientTest {

    private var server: HttpServer? = null
    private var seenQuery: String? = null
    private var seenAuthorization: String? = null

    private fun serve(status: Int, body: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/diag/robots") { exchange ->
            seenQuery = exchange.requestURI.rawQuery
            seenAuthorization = exchange.requestHeaders.getFirst("Authorization")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `퇴역 기체를 포함해 사이트로 묻고 칸을 읽는다`() {
        val url = serve(
            200,
            """[{"robotId":"r1","siteId":"site-01","status":"RETIRED","lastReportedAt":"2026-10-07T00:00:00Z",
               "reportingAfterRetirement":true,"unknownField":1}]""",
        )
        val call = RegistryClient(url, "op-t").robots("site-01")
        val robot = assertIs<RegistryCall.Ok<List<dev.picasso.ops.service.registry.RegistryRobot>>>(call).value.single()
        assertEquals("r1", robot.robotId)
        assertEquals("RETIRED", robot.status)
        assertEquals(true, robot.reportingAfterRetirement)
        assertEquals("retired=true&site=site-01", seenQuery)
        assertEquals("Bearer op-t", seenAuthorization)
    }

    @Test
    fun `본문 null 은 모름이다`() {
        assertIs<RegistryCall.Silent>(RegistryClient(serve(200, "null"), "op-t").robots("site-01"))
    }

    /** 실제 registry 의 `/diag/robots` 는 관문 밖이라 401 을 안 낸다. 분류만 본다(S1b 의 `/operations` 호출이 쓴다). */
    @Test
    fun `401 은 토큰 불일치로 분류한다`() {
        assertEquals(RegistryCall.Unauthorized, RegistryClient(serve(401, ""), "op-t").robots("site-01"))
    }

    @Test
    fun `5xx 는 모름이다`() {
        assertIs<RegistryCall.Silent>(RegistryClient(serve(503, ""), "op-t").robots("site-01"))
    }

    @Test
    fun `해석할 수 없는 본문은 모름이다`() {
        assertIs<RegistryCall.Silent>(RegistryClient(serve(200, "not json"), "op-t").robots("site-01"))
    }

    @Test
    fun `닿지 않는 registry 는 모름이다`() {
        val url = serve(200, "[]")
        server!!.stop(0)
        server = null
        assertIs<RegistryCall.Silent>(RegistryClient(url, "op-t").robots("site-01"))
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 확인**

Run: `./gradlew :ops-service:test --tests '*RegistryClientTest' --console=plain`
Expected: `compileTestKotlin` 실패(`RegistryClient` 없음).

- [ ] **Step 3: 구현**

`ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt`:

```kotlin
package dev.picasso.ops.service.registry

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * registry 호출 한 번의 결과. «없음» 과 «모름» 을 접지 않는다(스펙 §9).
 *
 * 읽기에서는 401 이 아닌 실패를 모두 [Silent] 로 둔다. 연결 실패·시간 초과·5xx·해석 불가 어느 것이든
 * 값을 모른다는 점이 같다. 조작의 거절 대응(400/404/409)은 S1b 에서 더한다(스펙 §7.4).
 *
 * [Unauthorized] 는 운영자 토큰 관문(`/operations` 이하) 안의 호출에서만 나온다. S1a 의 유일한 읽기인
 * `/diag/robots` 는 관문 밖이라 토큰이 틀려도 401 이 오지 않는다. 토큰 불일치는 S1b 의 첫 조작에서 드러난다.
 */
sealed interface RegistryCall<out T> {
    data class Ok<T>(val value: T) : RegistryCall<T>

    data class Silent(val cause: String) : RegistryCall<Nothing>

    /** 운영 서비스의 운영자 토큰이 registry 와 맞지 않는다(스펙 §7.4). */
    data object Unauthorized : RegistryCall<Nothing>
}

/** registry `GET /diag/robots` 의 한 줄. 운영 서비스가 쓰는 칸만 읽고 나머지는 버린다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryRobot(
    val robotId: String,
    val siteId: String,
    val serialNumber: String? = null,
    val displayName: String? = null,
    val origin: String? = null,
    val registeredAt: String? = null,
    val registeredBy: String? = null,
    val lastReportedAt: String? = null,
    val status: String,
    val retiredAt: String? = null,
    val retiredBy: String? = null,
    val retiredReason: String? = null,
    val reportingAfterRetirement: Boolean = false,
)

/** 기체 목록의 출처. 시험이 registry 없이 대신 끼운다. */
fun interface RobotSource {
    fun robots(siteId: String): RegistryCall<List<RegistryRobot>>
}

/** registry REST 클라이언트. 운영 서비스만 운영자 토큰을 쥔다(스펙 §4). DB 에 직결하지 않는다. */
class RegistryClient(
    baseUrl: String,
    private val operatorToken: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
    private val json: ObjectMapper = jacksonObjectMapper(),
) : RobotSource {

    private val base = baseUrl.trimEnd('/')

    /** 퇴역 기체를 늘 포함한다. 기본값(`retired=false`)은 퇴역 뒤 보고와 복귀를 감춘다(스펙 §7.2). */
    override fun robots(siteId: String): RegistryCall<List<RegistryRobot>> {
        val site = URLEncoder.encode(siteId, StandardCharsets.UTF_8)
        return get("/diag/robots?retired=true&site=$site") { json.readValue<List<RegistryRobot>?>(it) }
    }

    /** [read] 가 널을 내면(본문 `null`) 값을 모르는 것이다. `OK` 인데 목록이 널인 보기를 만들지 않는다. */
    private fun <T : Any> get(path: String, read: (String) -> T?): RegistryCall<T> {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $operatorToken")
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            return RegistryCall.Silent("응답 없음: ${e.javaClass.simpleName}")
        }
        return when (val status = response.statusCode()) {
            in 200..299 -> try {
                read(response.body())?.let { RegistryCall.Ok(it) } ?: RegistryCall.Silent("본문이 null 이다")
            } catch (e: JacksonException) {
                RegistryCall.Silent("본문 해석 실패: ${e.originalMessage}")
            }
            401 -> RegistryCall.Unauthorized
            else -> RegistryCall.Silent("HTTP $status")
        }
    }

    companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}
```

- [ ] **Step 4: 시험이 통과하는지 확인**

Run: `./gradlew :ops-service:test --tests '*RegistryClientTest' --console=plain`
Expected: XML 에서 `RegistryClientTest` 6개, 실패 0.

- [ ] **Step 5: 결함 주입 3건(하나씩)**

① `robots` 의 경로에서 `retired=true&` 를 지운다. Expected 실패 이름: `퇴역 기체를 포함해 사이트로 묻고 칸을 읽는다()`.
② `401 -> RegistryCall.Unauthorized` 줄을 지운다(401 이 `else` 로 떨어진다). Expected 실패 이름: `401 은 토큰 불일치로 분류한다()`.
③ `read(response.body())?.let { RegistryCall.Ok(it) } ?: RegistryCall.Silent("본문이 null 이다")` 를 `RegistryCall.Ok(read(response.body())!!)` 로 바꾼다. Expected: `본문 null 은 모름이다()` 가 실패(`NullPointerException`).
각각 되돌리고 초록을 본다.

- [ ] **Step 6: 임시 커밋**

```bash
git add ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RegistryClientTest.kt
git commit -m "chore(s1a): registry 클라이언트" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Chunk 3: 운영 서비스 기동과 통합 시험

### Task 6: 기체 목록 서비스, API, 애플리케이션

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt`
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/web/ApiController.kt`
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt`
- Create: `ops-service/src/main/resources/ops-service.properties`
- Test: `ops-service/src/test/kotlin/dev/picasso/ops/service/RobotListServiceTest.kt`

- [ ] **Step 1: 실패하는 시험 쓰기**

`ops-service/src/test/kotlin/dev/picasso/ops/service/RobotListServiceTest.kt`:

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.robots.RegistryState
import dev.picasso.ops.service.robots.RobotListService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RobotListServiceTest {

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val t1 = Instant.parse("2026-10-07T00:00:00Z")
    private val t2 = Instant.parse("2026-10-07T00:00:05Z")
    private val clock = MovableClock(t1)
    private val r1 = RegistryRobot(robotId = "r1", siteId = "site-01", status = "CLAIMED")
    private var next: RegistryCall<List<RegistryRobot>> = RegistryCall.Ok(emptyList())
    private var askedSite: String? = null
    private val service = RobotListService({ site -> askedSite = site; next }, "site-01", clock)

    @Test
    fun `registry 가 답하면 목록과 읽은 시각을 낸다`() {
        next = RegistryCall.Ok(listOf(r1))
        val view = service.read()
        assertEquals(RegistryState.OK, view.registry)
        assertEquals(listOf(r1), view.robots)
        assertEquals(t1, view.robotsAsOf)
        assertEquals("site-01", askedSite)
    }

    @Test
    fun `한 번도 못 읽었으면 목록은 없음이 아니라 모름이다`() {
        next = RegistryCall.Silent("응답 없음")
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT, view.registry)
        assertNull(view.robots)
        assertNull(view.robotsAsOf)
    }

    @Test
    fun `registry 가 침묵하면 직전 목록과 그 시각을 유지한다`() {
        next = RegistryCall.Ok(listOf(r1))
        service.read()
        clock.now = t2
        next = RegistryCall.Silent("응답 없음")
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT, view.registry)
        assertEquals(t2, view.checkedAt)
        assertEquals(listOf(r1), view.robots)
        assertEquals(t1, view.robotsAsOf)
    }

    @Test
    fun `직전 값이 빈 목록이면 침묵 뒤에도 없음을 유지한다`() {
        next = RegistryCall.Ok(emptyList())
        service.read()
        next = RegistryCall.Silent("응답 없음")
        assertEquals(emptyList(), service.read().robots)
    }

    @Test
    fun `토큰 불일치는 전체 상태로 가고 직전 목록을 유지한다`() {
        next = RegistryCall.Ok(listOf(r1))
        service.read()
        next = RegistryCall.Unauthorized
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_UNAUTHORIZED, view.registry)
        assertEquals(listOf(r1), view.robots)
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 확인**

Run: `./gradlew :ops-service:test --tests '*RobotListServiceTest' --console=plain`
Expected: `compileTestKotlin` 실패(`RobotListService` 없음).

- [ ] **Step 3: 구현**

`ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt`:

```kotlin
package dev.picasso.ops.service.robots

import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RobotSource
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** 화면 전체 상태(스펙 §7.4). 기체별이 아니라 전역이다. */
enum class RegistryState { OK, REGISTRY_SILENT, REGISTRY_UNAUTHORIZED }

/**
 * `GET /api/robots` 의 답.
 *
 * @param robots 널이면 «모름»(한 번도 읽지 못했다). 빈 목록은 «없음». 둘을 접지 않는다(스펙 §9).
 * @param robotsAsOf [robots] 를 registry 에서 읽은 시각. [registry] 가 [RegistryState.OK] 가 아니면 직전 값의 시각이다.
 */
data class RobotListView(
    val registry: RegistryState,
    val checkedAt: Instant,
    val robots: List<RegistryRobot>?,
    val robotsAsOf: Instant?,
)

/** 기체 목록. registry 가 답하지 않으면 목록을 비우지 않고 직전 값과 시각을 보인다(스펙 §9). */
class RobotListService(
    private val source: RobotSource,
    private val siteId: String,
    private val clock: Clock,
) {
    private data class Known(val robots: List<RegistryRobot>, val at: Instant)

    private val last = AtomicReference<Known?>(null)

    fun read(): RobotListView {
        val now = clock.instant()
        return when (val call = source.robots(siteId)) {
            is RegistryCall.Ok -> {
                last.set(Known(call.value, now))
                RobotListView(RegistryState.OK, now, call.value, now)
            }
            is RegistryCall.Silent -> stale(RegistryState.REGISTRY_SILENT, now)
            RegistryCall.Unauthorized -> stale(RegistryState.REGISTRY_UNAUTHORIZED, now)
        }
    }

    private fun stale(state: RegistryState, now: Instant): RobotListView {
        val known = last.get()
        return RobotListView(state, now, known?.robots, known?.at)
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/web/ApiController.kt`:

```kotlin
package dev.picasso.ops.service.web

import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationRecord
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.robots.RobotListView
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 화면이 부르는 API. 화면은 이 서비스만 부른다(스펙 §4). S1a 는 읽기 2개다. */
@RestController
@RequestMapping("/api")
class ApiController(
    private val robots: RobotListService,
    private val operations: OperationLog,
) {
    @GetMapping("/robots")
    fun robots(): RobotListView = robots.read()

    @GetMapping("/operations")
    fun operations(): List<OperationRecord> = operations.list()
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt`:

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.registry.RegistryClient
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.store.OpsSchemaMigrated
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import javax.sql.DataSource

/** 운영 서비스(스펙 §7). Flyway 자동설정을 끄는 이유는 [OpsSchema] 에 있다. */
@SpringBootApplication(exclude = [FlywayAutoConfiguration::class])
open class OpsApplication {

    @Bean
    open fun clock(): Clock = Clock.systemUTC()

    @Bean
    open fun opsSchemaMigrated(dataSource: DataSource): OpsSchemaMigrated =
        OpsSchemaMigrated(OpsSchema.flyway(dataSource).migrate().migrationsExecuted)

    @Bean
    open fun registryClient(
        @Value("\${ops.registry.url}") url: String,
        @Value("\${ops.registry.operator-token}") token: String,
    ): RegistryClient {
        require(url.isNotBlank()) { "ops.registry.url 이 비었다" }
        // registry 는 빈 토큰을 전부 401 로 다룬다. 설정 실수를 401 이 아니라 기동에서 드러낸다.
        require(token.isNotBlank()) { "운영자 토큰(ops.registry.operator-token)이 비었다" }
        return RegistryClient(url, token)
    }

    @Bean
    open fun robotList(
        registry: RegistryClient,
        @Value("\${ops.site-id}") siteId: String,
        clock: Clock,
    ): RobotListService {
        require(siteId.isNotBlank()) { "ops.site-id(SITE_ID)가 비었다" }
        return RobotListService(registry, siteId, clock)
    }

    /** [migrated] 는 쓰지 않는다. 받는 것만으로 ops 마이그레이션 뒤에 이 빈이 만들어진다. */
    @Bean
    open fun operationLog(
        jdbc: JdbcClient,
        @Suppress("UNUSED_PARAMETER") migrated: OpsSchemaMigrated,
    ): OperationLog = OperationLog(jdbc)

    companion object {
        /** 설정 파일 이름을 `ops-service` 로 둔다. 같은 JVM 의 registry `application.properties` 와 가리지 않게(스펙 §7.1). */
        fun builder(): SpringApplicationBuilder =
            SpringApplicationBuilder(OpsApplication::class.java)
                .properties("spring.config.name=ops-service")
    }
}

fun main(args: Array<String>) {
    OpsApplication.builder().run(*args)
}
```

`ops-service/src/main/resources/ops-service.properties`:

```properties
# 운영 서비스 설정. 파일 이름이 ops-service 인 것은 registry 의 application.properties 와 가리지 않기 위해서다(스펙 §7.1).
spring.application.name=picasso-ops-service
spring.threads.virtual.enabled=true

# 값은 루트 .env 가 준다(스펙 §4). 기본값을 적지 않는다: 빠지면 기동에서 멈춘다.
server.port=${OPS_PORT}
# 이 기계 밖에 열지 않는다. 화면은 Vite 프록시로 붙는다.
server.address=127.0.0.1
spring.datasource.url=${PICASSO_DB_URL}
spring.datasource.username=${PICASSO_DB_USER}
spring.datasource.password=${PICASSO_DB_PASSWORD}
ops.site-id=${SITE_ID}
ops.registry.url=http://127.0.0.1:${REGISTRY_PORT}
ops.registry.operator-token=${PICASSO_OPERATOR_TOKEN}
```

- [ ] **Step 4: 시험이 통과하는지 확인**

Run: `./gradlew :ops-service:test --console=plain`
Expected: XML 에서 `RobotListServiceTest` 5개, 모듈 전체 23개, 실패 0. 애플리케이션 기동은 Task 7 의 e2e 가 본다.

- [ ] **Step 5: 결함 주입**

`stale` 의 반환을 `RobotListView(state, now, null, null)` 로 바꾼다(직전 값을 버린다).
Expected 실패 이름 3개: `registry 가 침묵하면 직전 목록과 그 시각을 유지한다()`, `직전 값이 빈 목록이면 침묵 뒤에도 없음을 유지한다()`, `토큰 불일치는 전체 상태로 가고 직전 목록을 유지한다()`. 되돌리고 초록을 본다.

- [ ] **Step 6: 임시 커밋**

```bash
git add ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/ApiController.kt ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt ops-service/src/main/resources/ops-service.properties ops-service/src/test/kotlin/dev/picasso/ops/service/RobotListServiceTest.kt
git commit -m "chore(s1a): 기체 목록 서비스와 운영 서비스 기동" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

### Task 7: 통합 시험(S1a 완료 판정)

**Files:**
- Modify: `settings.gradle.kts`(끝에 `include("e2e")`)
- Create: `e2e/build.gradle.kts`
- Test: `e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt`

- [ ] **Step 1: 모듈 등록과 빌드 파일**

`settings.gradle.kts` 끝에 `include("e2e")` 를 더한다. `e2e/build.gradle.kts`:

```kotlin
// 통합 시험만 있는 모듈. 한 JVM 에 Postgres·registry·mimic·운영 서비스를 함께 띄운다(스펙 §10).
dependencies {
    testImplementation(project(":site"))
    testImplementation(project(":ops-service"))
    testImplementation(testFixtures("dev.picasso:registry"))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.jackson.databind)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("site/robots.json")).withPropertyName("roster")
    inputs.dir(rootProject.file("picasso/profile/profiles")).withPropertyName("profiles")
    inputs.dir(rootProject.file("picasso/profile/schema")).withPropertyName("profileSchema")
}
```

- [ ] **Step 2: 시험 쓰기**

이 시험은 이미 있는 코드를 한데 묶어 보므로 처음부터 통과할 수 있다. 그래서 Step 4 의 결함 주입이 이 시험이 실제로 보는지의 증거다.

```kotlin
package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.OpsApplication
import dev.picasso.ops.site.DbConfig
import dev.picasso.ops.site.RobotRoster
import dev.picasso.ops.site.Site
import dev.picasso.ops.site.SiteConfig
import dev.picasso.registry.PostgresSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * S1a 완료 판정(스펙 §3). 전체를 한 JVM 에 띄우고 운영 서비스의 기체 목록이 빈 목록을 돌려준다.
 * registry 를 멈추면 화면 전체 상태가 «모름» 이 된다. 순서가 있다: 마지막 시험이 registry 를 멈춘다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkeletonTest {

    companion object {
        private const val OPERATOR_TOKEN = "e2e-operator"
        private val root: Path = Path.of("..").toAbsolutePath().normalize()
        private val siteId: String = checkNotNull(System.getenv("SITE_ID")) { "SITE_ID 가 없다(루트 .env)" }
        private val http = HttpClient.newHttpClient()
        private val json = ObjectMapper()

        private lateinit var site: Site
        private lateinit var ops: ConfigurableApplicationContext
        private lateinit var opsUrl: String

        @BeforeAll
        @JvmStatic
        fun up() {
            PostgresSupport.reset()
            PostgresSupport.execute("DROP SCHEMA IF EXISTS ops CASCADE")
            val db = DbConfig(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
            site = Site.start(
                SiteConfig(
                    root = root,
                    siteId = siteId,
                    db = db,
                    registryPort = 0,
                    operatorToken = OPERATOR_TOKEN,
                    ingestToken = "e2e-ingest",
                    roster = RobotRoster.read(root.resolve(SiteConfig.ROSTER)),
                ),
            )
            ops = OpsApplication.builder().run(
                "--server.port=0",
                "--spring.datasource.url=${db.url}",
                "--spring.datasource.username=${db.user}",
                "--spring.datasource.password=${db.password}",
                "--ops.registry.url=${site.registryUrl}",
                "--ops.registry.operator-token=$OPERATOR_TOKEN",
                "--ops.site-id=$siteId",
            )
            opsUrl = "http://127.0.0.1:${(ops as WebServerApplicationContext).webServer.port}"
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::ops.isInitialized) ops.close()
            if (::site.isInitialized) site.close()
        }
    }

    private fun get(url: String): JsonNode {
        val response = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())
    }

    /** registry REST 를 직접 부른다. 운영 서비스의 선언 API 는 S1b 의 몫이다. */
    private fun declareDirectly(robotId: String) {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("${site.registryUrl}/operations/robots"))
                .header("Authorization", "Bearer $OPERATOR_TOKEN")
                .header("X-Actor", "engineer/e2e")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"robot_id":"$robotId","site":"$siteId","serial_number":"E2E-0001"}""",
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(201, response.statusCode(), response.body())
    }

    @Test
    @Order(1)
    fun `운영 서비스의 기체 목록 조회가 빈 목록을 돌려준다`() {
        // 명부의 기체가 모두 떠 있다.
        assertEquals(RobotRoster.read(root.resolve(SiteConfig.ROSTER)).map { it.robotId }.toSet(), site.robotIds)
        // 선언 전 mimic 의 보고는 registry 가 거절하고 남기지 않는다(스펙 §6). 보고가 흘러도 목록은 비어 있다.
        repeat(2) { site.advance(Duration.ofSeconds(31)) }
        val view = get("$opsUrl/api/robots")
        assertEquals("OK", view["registry"].asText())
        assertEquals(0, view["robots"].size(), view.toString())
        assertFalse(view["robots"].isNull)
        assertFalse(view["robotsAsOf"].isNull)
    }

    @Test
    @Order(2)
    fun `ops 스키마는 운영 서비스가 올리고 조작 기록은 비어 있다`() {
        val versions = PostgresSupport.queryAll(
            "SELECT version FROM ops.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
        ) { it.getString(1) }
        assertEquals(listOf("1"), versions)
        assertEquals(0, get("$opsUrl/api/operations").size())
    }

    @Test
    @Order(3)
    fun `registry 를 멈추면 전체 상태가 모름이고 직전 목록을 지우지 않는다`() {
        declareDirectly("e2e-held-01")
        val before = get("$opsUrl/api/robots")
        assertEquals(listOf("e2e-held-01"), before["robots"].map { it["robotId"].asText() })

        site.stopRegistry()

        val after = get("$opsUrl/api/robots")
        assertEquals("REGISTRY_SILENT", after["registry"].asText())
        assertEquals(listOf("e2e-held-01"), after["robots"].map { it["robotId"].asText() })
        assertEquals(before["robotsAsOf"].asText(), after["robotsAsOf"].asText())
    }
}
```

- [ ] **Step 3: 시험이 통과하는지 확인**

Run: `./gradlew :e2e:test --console=plain`
Expected: XML 확인(`e2e/...`)에서 `SkeletonTest` 3개, 실패 0.

- [ ] **Step 4: 결함 주입 2건(하나씩)**

① Task 6 Step 5 와 같은 주입(`stale` 이 직전 값을 버린다). Run: `./gradlew :e2e:test --console=plain`.
② «모름» 자체를 접는다. `RegistryClient.get` 의 `catch (e: IOException)` 에서 `return RegistryCall.Silent(...)` 를 `@Suppress("UNCHECKED_CAST") return RegistryCall.Ok(emptyList<Any>() as T)` 로 바꾸고 `./gradlew :ops-service:test :e2e:test --continue` 를 돈다.
둘 다 Expected 실패 이름(e2e): `registry 를 멈추면 전체 상태가 모름이고 직전 목록을 지우지 않는다()`. ② 는 ops-service 의 `닿지 않는 registry 는 모름이다()` 도 빨갛다. 각각 되돌리고 초록을 본다.

- [ ] **Step 5: 임시 커밋**

```bash
git add settings.gradle.kts e2e/build.gradle.kts e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt
git commit -m "chore(s1a): 골격 통합 시험" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Chunk 4: 화면, CI, 마무리

### Task 8: 화면 골격

**Files:**
- Create: `ui/`(`create-vite` 틀) 뒤 다음을 쓴다
- Create: `ui/src/api.ts`, `ui/src/areas.ts`, `ui/src/setupTests.ts`, `ui/src/styles.css`
- Create: `ui/src/components/RegistryBanner.tsx`, `ui/src/components/RobotsArea.tsx`, `ui/src/components/HistoryArea.tsx`, `ui/src/components/ModeSwitch.tsx`
- Modify: `ui/src/App.tsx`, `ui/src/main.tsx`, `ui/vite.config.ts`, `ui/index.html`, `ui/package.json`(scripts.test)
- Delete: `ui/src/App.css`, `ui/src/index.css`, `ui/src/assets/`, `ui/README.md`, `ui/public/icons.svg`(쓰는 곳이 없다)
- Test: `ui/src/App.test.tsx`

틀의 설정은 `erasableSyntaxOnly` 와 `verbatimModuleSyntax` 를 켠다. 그래서 enum 대신 문자열 유니온을 쓰고, 타입은 `import type` 으로 가져온다.

- [ ] **Step 1: 틀 만들기와 시험 도구 설치**

```bash
npm create vite@9.2.1 ui -- --template react-ts --no-interactive
cd ui
npm install --no-audit --no-fund
npm install -D --no-audit --no-fund vitest@^5.0.3 jsdom@^29.1.1 @testing-library/react@^16.3.3 @testing-library/user-event@^14.6.7 @testing-library/jest-dom@^7.0.1
rm -rf src/App.css src/index.css src/assets README.md public/icons.svg
node -e "const p=require('./package.json');p.scripts.test='vitest run';require('fs').writeFileSync('package.json',JSON.stringify(p,null,2)+'\n')"
cd ..
```
Expected: `ui/package.json` 의 `scripts` 에 `"test": "vitest run"`, `devDependencies` 에 `vitest`·`jsdom`·`@testing-library/*`.

- [ ] **Step 2: 설정과 진입 파일**

`ui/vite.config.ts`:

```ts
/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// 화면은 운영 서비스만 부른다(스펙 §4). registry 주소와 토큰을 모른다.
// 포트는 루트 .env 하나에서 읽는다(스펙 §4·§6). 여기에 기본값을 두면 두 번째 출처가 된다.
export default defineConfig(({ mode }) => {
  const opsPort = loadEnv(mode, '..', 'OPS_').OPS_PORT
  if (!opsPort) throw new Error('루트 .env 에 OPS_PORT 가 없다')
  const proxy = { '/api': `http://127.0.0.1:${opsPort}` }
  return {
    plugins: [react()],
    server: { proxy },
    preview: { proxy },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/setupTests.ts'],
    },
  }
})
```

`ui/src/setupTests.ts`:

```ts
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(() => cleanup())
```

`ui/src/main.tsx`:

```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
```

`ui/src/styles.css`:

```css
body { font-family: system-ui, sans-serif; margin: 0 16px; }
header { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; }
nav button[aria-current='page'] { font-weight: 700; }
.banner { padding: 8px; margin: 8px 0; border: 1px solid #999; }
.banner.unknown { border-color: #b23a1d; }
.split { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.stale { color: #6f6f6f; }
```

`ui/index.html` 에서 `<html lang="en">` 을 `<html lang="ko">` 로, `<title>ui</title>` 을 `<title>picasso-ops</title>` 로 바꾼다.

- [ ] **Step 3: 실패하는 시험 쓰기**

`ui/src/App.test.tsx`:

```tsx
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { OperationRecord, RobotListView } from './api'

const robot = {
  robotId: 'humanoid-01',
  siteId: 'site-01',
  serialNumber: 'HA-0001',
  status: 'CLAIMED',
  lastReportedAt: null,
  retiredAt: null,
  reportingAfterRetirement: false,
}

function serve(view: RobotListView, records: OperationRecord[] = []) {
  const calls: { url: string; headers: Record<string, string> }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, headers: (init?.headers ?? {}) as Record<string, string> })
      const body = url === '/api/robots' ? view : records
      return new Response(JSON.stringify(body), { status: 200 })
    }),
  )
  return calls
}

describe('App', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('메뉴가 5영역이고 S1 에서 닫힌 3영역은 다음 단계로 표시한다', () => {
    serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<App />)
    const nav = screen.getByRole('navigation', { name: '영역' })
    expect(within(nav).getAllByRole('button').map((b) => b.textContent)).toEqual([
      '현장·자원 다음 단계',
      '로봇·연결',
      '임무·정책 다음 단계',
      '운영 다음 단계',
      '이력',
    ])
  })

  it('목록을 읽은 적이 없으면 없음이 아니라 모름을 보인다', async () => {
    serve({ registry: 'REGISTRY_SILENT', checkedAt: 't1', robots: null, robotsAsOf: null })
    render(<App />)
    expect(await screen.findByText('모름: 기체 목록을 아직 읽지 못했습니다')).toBeInTheDocument()
    expect(screen.queryByText('선언된 기체가 없습니다')).not.toBeInTheDocument()
  })

  it('빈 목록은 없음으로 보인다', async () => {
    serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<App />)
    expect(await screen.findByText('선언된 기체가 없습니다')).toBeInTheDocument()
  })

  it('registry 가 답하지 않으면 전체 상태가 모름이고 직전 목록을 지우지 않는다', async () => {
    serve({ registry: 'REGISTRY_SILENT', checkedAt: 't2', robots: [robot], robotsAsOf: 't1' })
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('모름: registry 가 답하지 않습니다')
    expect(await screen.findByText('humanoid-01')).toBeInTheDocument()
    expect(screen.getByText('직전 값입니다 (t1 기준)')).toBeInTheDocument()
  })

  it('운영자 토큰 거절은 전체 상태 한 자리에만 보인다', async () => {
    serve({ registry: 'REGISTRY_UNAUTHORIZED', checkedAt: 't2', robots: [robot], robotsAsOf: 't1' })
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('운영자 토큰 설정 확인')
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })

  it('모드를 바꾸면 요청 헤더의 모드가 바뀐다', async () => {
    const calls = serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<App />)
    await waitFor(() => expect(calls.length).toBeGreaterThan(0))
    expect(calls[0].headers['X-Ops-Mode']).toBe('engineer')
    await userEvent.click(screen.getByLabelText('운영자'))
    await waitFor(() => expect(calls.at(-1)?.headers['X-Ops-Mode']).toBe('operator'))
  })

  it('운영 서비스에 닿지 않으면 모름을 알리고 직전 목록을 직전 값으로 표시한다', async () => {
    let down = false
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (down) return new Response('', { status: 503 })
        const body =
          url === '/api/robots'
            ? { registry: 'OK', checkedAt: 't1', robots: [robot], robotsAsOf: 't1' }
            : []
        return new Response(JSON.stringify(body), { status: 200 })
      }),
    )
    render(<App />)
    expect(await screen.findByText('humanoid-01')).toBeInTheDocument()
    expect(screen.queryByText(/직전 값입니다/)).not.toBeInTheDocument()

    down = true
    // 모드를 바꾸면 다시 읽는다. 주기(5초)를 기다리지 않는다.
    await userEvent.click(screen.getByLabelText('운영자'))
    expect(await screen.findByRole('alert')).toHaveTextContent('모름: 운영 서비스에 닿지 않습니다')
    expect(screen.getByText('humanoid-01')).toBeInTheDocument()
    expect(screen.getByText('직전 값입니다 (t1 기준)')).toBeInTheDocument()
  })

  it('이력 영역이 조작 기록을 행위자와 함께 보인다', async () => {
    serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' }, [
      {
        requestId: 'r-1',
        mode: 'OPERATOR',
        user: 'kim',
        target: 'robot humanoid-01',
        reason: '정비',
        result: 'SUCCEEDED',
        recordedAt: 't1',
      },
    ])
    render(<App />)
    await userEvent.click(screen.getByRole('button', { name: '이력' }))
    expect(await screen.findByText('운영자/kim')).toBeInTheDocument()
    expect(screen.getByText('정비')).toBeInTheDocument()
  })
})
```

- [ ] **Step 4: 시험이 실패하는지 확인**

Run: `cd ui && npm test; cd ..`
Expected: `Failed to resolve import` 로 실패. 틀의 `App.tsx` 가 Step 1 에서 지운 `./assets/...`·`./App.css` 를 불러오기 때문이다(시험의 `import type` 은 변환에서 지워져 `./api` 오류는 나지 않는다).

- [ ] **Step 5: 구현**

`ui/src/api.ts`:

```ts
export type Mode = 'engineer' | 'operator'
export type RegistryState = 'OK' | 'REGISTRY_SILENT' | 'REGISTRY_UNAUTHORIZED'

export interface Robot {
  robotId: string
  siteId: string
  serialNumber: string | null
  status: string
  lastReportedAt: string | null
  retiredAt: string | null
  reportingAfterRetirement: boolean
}

/** 운영 서비스의 `GET /api/robots`. `robots` 가 null 이면 모름, 빈 배열이면 없음이다(스펙 §9). */
export interface RobotListView {
  registry: RegistryState
  checkedAt: string
  robots: Robot[] | null
  robotsAsOf: string | null
}

export interface OperationRecord {
  requestId: string
  mode: 'ENGINEER' | 'OPERATOR'
  user: string
  target: string
  reason: string | null
  result: string
  recordedAt: string
}

/** 인증 없이 화면이 싣는 모드와 사용자(스펙 §7.1·§8). */
export interface Session {
  mode: Mode
  user: string
}

async function getJson<T>(path: string, session: Session): Promise<T> {
  const response = await fetch(path, {
    headers: { 'X-Ops-Mode': session.mode, 'X-Ops-User': session.user },
  })
  if (!response.ok) throw new Error(`운영 서비스 응답 ${response.status}`)
  return (await response.json()) as T
}

export const fetchRobots = (session: Session) => getJson<RobotListView>('/api/robots', session)
export const fetchOperations = (session: Session) =>
  getJson<OperationRecord[]>('/api/operations', session)
```

`ui/src/areas.ts`:

```ts
export type AreaId = 'site' | 'robots' | 'missions' | 'operations' | 'history'

export interface Area {
  id: AreaId
  label: string
  /** S1 에서 동작하는 영역. 나머지는 다음 단계로 표시한다(스펙 §8). */
  ready: boolean
}

export const AREAS: readonly Area[] = [
  { id: 'site', label: '현장·자원', ready: false },
  { id: 'robots', label: '로봇·연결', ready: true },
  { id: 'missions', label: '임무·정책', ready: false },
  { id: 'operations', label: '운영', ready: false },
  { id: 'history', label: '이력', ready: true },
]
```

`ui/src/components/RegistryBanner.tsx`:

```tsx
import type { RobotListView } from '../api'

interface Props {
  view: RobotListView | null
  opsError: string | null
}

/** 화면 전체 상태. 기체 행마다 반복하지 않고 상단 한 자리에 둔다(스펙 §7.4·§8). */
export function RegistryBanner({ view, opsError }: Props) {
  if (opsError !== null) {
    return (
      <div role="alert" className="banner unknown">
        모름: 운영 서비스에 닿지 않습니다 ({opsError})
      </div>
    )
  }
  if (view === null) return <div className="banner">확인 중</div>
  switch (view.registry) {
    case 'OK':
      return <div className="banner ok">registry 응답 확인 {view.checkedAt}</div>
    case 'REGISTRY_SILENT':
      return (
        <div role="alert" className="banner unknown">
          모름: registry 가 답하지 않습니다. 해결 담당 엔지니어, registry 상태 확인. 확인 시각{' '}
          {view.checkedAt}
        </div>
      )
    case 'REGISTRY_UNAUTHORIZED':
      return (
        <div role="alert" className="banner unknown">
          모름: registry 가 운영자 토큰을 거절합니다. 해결 담당 엔지니어, 운영자 토큰 설정 확인. 확인
          시각 {view.checkedAt}
        </div>
      )
  }
}
```

`ui/src/components/RobotsArea.tsx`:

```tsx
import type { RobotListView } from '../api'

interface Props {
  view: RobotListView | null
  /** 운영 서비스에 닿지 않으면 [view] 는 직전 값이다. */
  opsError: string | null
}

/** 로봇·연결 영역. 왼쪽 목록과 오른쪽 상세(스펙 §8, 결정 6). 상세와 조작은 S1b 에서 채운다. */
export function RobotsArea({ view, opsError }: Props) {
  return (
    <div className="split">
      <section aria-label="기체 목록">
        <h2>기체</h2>
        <RobotList view={view} opsError={opsError} />
      </section>
      <section aria-label="상세">
        <h2>상세</h2>
        <p>기체를 고르면 원장 상태와 연결이 여기에 보입니다.</p>
      </section>
    </div>
  )
}

function RobotList({ view, opsError }: Props) {
  if (view === null || view.robots === null) {
    return <p>모름: 기체 목록을 아직 읽지 못했습니다</p>
  }
  // registry 가 침묵하거나 운영 서비스에 닿지 않으면 보이는 목록은 직전 값이다(스펙 §9).
  const stale = view.registry !== 'OK' || opsError !== null
  return (
    <>
      {stale && <p className="stale">직전 값입니다 ({view.robotsAsOf} 기준)</p>}
      {view.robots.length === 0 ? (
        <p>선언된 기체가 없습니다</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>robot_id</th>
              <th>원장 상태</th>
              <th>마지막 보고</th>
            </tr>
          </thead>
          <tbody>
            {view.robots.map((robot) => (
              <tr key={robot.robotId}>
                <td>{robot.robotId}</td>
                <td>{robot.status}</td>
                <td>{robot.lastReportedAt ?? '보고 없음'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
```

`ui/src/components/HistoryArea.tsx`:

```tsx
import type { OperationRecord } from '../api'

interface Props {
  records: OperationRecord[] | null
  /** 운영 서비스에 닿지 않으면 [records] 는 직전 값이다. */
  opsError: string | null
}

/** 이력 영역. 조작 기록(행위자·사유·결과)을 보인다(스펙 §8). */
export function HistoryArea({ records, opsError }: Props) {
  if (records === null) return <p>모름: 조작 기록을 아직 읽지 못했습니다</p>
  return (
    <>
      {opsError !== null && <p className="stale">직전 값입니다</p>}
      {records.length === 0 ? (
        <p>조작 기록이 없습니다</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>시각</th>
              <th>행위자</th>
              <th>대상</th>
              <th>사유</th>
              <th>결과</th>
            </tr>
          </thead>
          <tbody>
            {records.map((record) => (
              <tr key={`${record.requestId}-${record.recordedAt}`}>
                <td>{record.recordedAt}</td>
                <td>
                  {record.mode === 'ENGINEER' ? '엔지니어' : '운영자'}/{record.user}
                </td>
                <td>{record.target}</td>
                <td>{record.reason ?? ''}</td>
                <td>{record.result}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
```

`ui/src/components/ModeSwitch.tsx`:

```tsx
import type { Mode, Session } from '../api'

interface Props {
  session: Session
  onChange: (session: Session) => void
}

const MODES: readonly { mode: Mode; label: string }[] = [
  { mode: 'engineer', label: '엔지니어' },
  { mode: 'operator', label: '운영자' },
]

/** 모드 전환. 인증 없이 둔다(스펙 §8). 모드는 X-Actor 에 실려 registry 까지 간다(스펙 §7.1). */
export function ModeSwitch({ session, onChange }: Props) {
  return (
    <fieldset className="mode">
      <legend>모드</legend>
      {MODES.map(({ mode, label }) => (
        <label key={mode}>
          <input
            type="radio"
            name="mode"
            checked={session.mode === mode}
            onChange={() => onChange({ ...session, mode })}
          />
          {label}
        </label>
      ))}
      <label>
        사용자
        <input
          value={session.user}
          onChange={(event) => onChange({ ...session, user: event.target.value })}
        />
      </label>
    </fieldset>
  )
}
```

`ui/src/App.tsx`(틀의 파일을 통째로 바꾼다):

```tsx
import { useEffect, useState } from 'react'
import { fetchOperations, fetchRobots } from './api'
import type { OperationRecord, RobotListView, Session } from './api'
import { AREAS } from './areas'
import type { AreaId } from './areas'
import { HistoryArea } from './components/HistoryArea'
import { ModeSwitch } from './components/ModeSwitch'
import { RegistryBanner } from './components/RegistryBanner'
import { RobotsArea } from './components/RobotsArea'

const POLL_MS = 5000

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

export default function App() {
  const [session, setSession] = useState<Session>({ mode: 'engineer', user: 'local' })
  const [area, setArea] = useState<AreaId>('robots')
  const [view, setView] = useState<RobotListView | null>(null)
  const [records, setRecords] = useState<OperationRecord[] | null>(null)
  const [opsError, setOpsError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    // 실패해도 직전 값을 지우지 않는다. 대신 opsError 로 직전 값임을 표시한다(스펙 §9).
    const load = () => {
      Promise.all([fetchRobots(session), fetchOperations(session)])
        .then(([nextView, nextRecords]) => {
          if (!alive) return
          setView(nextView)
          setRecords(nextRecords)
          setOpsError(null)
        })
        .catch((error: unknown) => {
          if (alive) setOpsError(message(error))
        })
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [session])

  const current = AREAS.find((candidate) => candidate.id === area) ?? AREAS[1]

  return (
    <>
      <header>
        <h1>picasso-ops</h1>
        <nav aria-label="영역">
          {AREAS.map((candidate) => (
            <button
              key={candidate.id}
              aria-current={candidate.id === area ? 'page' : undefined}
              onClick={() => setArea(candidate.id)}
            >
              {candidate.label}
              {!candidate.ready && <small> 다음 단계</small>}
            </button>
          ))}
        </nav>
        <ModeSwitch session={session} onChange={setSession} />
      </header>
      <RegistryBanner view={view} opsError={opsError} />
      <main>
        {!current.ready && <p>이 영역은 다음 단계에서 엽니다.</p>}
        {current.id === 'robots' && <RobotsArea view={view} opsError={opsError} />}
        {current.id === 'history' && <HistoryArea records={records} opsError={opsError} />}
      </main>
    </>
  )
}
```

- [ ] **Step 6: 시험과 빌드가 통과하는지 확인**

Run: `cd ui && npm test && npm run build; cd ..`
Expected: `Tests  8 passed (8)`, 빌드가 `dist/` 를 만든다(`tsc -b` 가 시험 파일까지 형 검사한다). `vite.config.ts` 가 루트 `.env` 의 `OPS_PORT` 를 읽으므로 그 파일이 없으면 둘 다 멈춘다.

- [ ] **Step 7: 결함 주입 2건(하나씩)**

① `RobotList` 의 첫 분기를 `if (view === null || view.robots === null) return <p>선언된 기체가 없습니다</p>` 로 바꾼다(모름을 없음으로 접는다). Expected: `목록을 읽은 적이 없으면 없음이 아니라 모름을 보인다` 가 실패.
② `RobotList` 의 `const stale = view.registry !== 'OK' || opsError !== null` 에서 `|| opsError !== null` 을 지운다. Expected: `운영 서비스에 닿지 않으면 모름을 알리고 직전 목록을 직전 값으로 표시한다` 가 실패.
각각 되돌리고 초록을 본다.

- [ ] **Step 8: 임시 커밋**

`ui/.gitignore`(틀이 만든다)가 `node_modules`·`dist` 를 뺀다.

```bash
git add ui/.gitignore ui/.oxlintrc.json ui/index.html ui/package.json ui/package-lock.json ui/tsconfig.json ui/tsconfig.app.json ui/tsconfig.node.json ui/vite.config.ts ui/public/favicon.svg ui/src
git status --short ui
git commit -m "chore(s1a): 화면 골격" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```
Expected: 커밋 전 `git status --short ui` 의 줄이 모두 `A ` 로 시작하고 `??` 가 없다(빠진 틀 파일이 없다). `node_modules`·`dist` 는 나오지 않는다.

### Task 9: CI, README, 손으로 띄워 보기

**Files:**
- Create: `.github/workflows/ci.yml`, `README.md`, `site/smoke.sh`

- [ ] **Step 1: CI 쓰기**

`.github/workflows/ci.yml`(checkout·setup-java·setup-gradle 판은 picasso CI 와 같다). job 을 둘로 나눠 한쪽이 실패해도 다른 쪽이 돈다(스펙 §10):

```yaml
name: ci

on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

# Gradle 과 npm 을 따로 돌린다(스펙 §10). job 이 둘이라 한쪽이 실패해도 다른 쪽이 돈다.
jobs:
  gradle:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      # Testcontainers 는 러너의 Docker 를 쓴다.
      - name: Gradle build
        run: ./gradlew build --console=plain
      # 판정은 종료 코드가 아니라 XML 의 실패 시험 이름으로 한다. 실패해도 올린다.
      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results
          path: '**/build/test-results/test/*.xml'

  ui:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: ui/package-lock.json
      - name: UI test and build
        working-directory: ui
        run: |
          npm ci
          npm test
          npm run build
```

- [ ] **Step 2: 손 기동 스크립트 쓰기**

`site/smoke.sh`. `run` 작업은 끝나지 않으므로 배포본으로 띄우고, 빠져나갈 때 `trap` 으로 프로세스와 컨테이너를 정리한다. 전경 `sleep` 대신 `curl --retry` 로 기다리고, 운영 서비스는 적재 토큰을 뺀 환경으로 띄운다(스펙 §4).

```bash
#!/usr/bin/env bash
# S1a 손 기동 확인. 저장소 루트에서 돈다.
set -u
mkdir -p build
cleanup() {
  [ -n "${OPS_PID:-}" ] && kill "$OPS_PID" 2>/dev/null
  [ -n "${SITE_PID:-}" ] && kill "$SITE_PID" 2>/dev/null
  docker compose -f site/compose.yaml --env-file .env down >/dev/null 2>&1
}
trap cleanup EXIT

docker compose -f site/compose.yaml --env-file .env up -d --wait || exit 1
./gradlew :site:installDist :ops-service:installDist --console=plain -q || exit 1
set -a; . ./.env; set +a

site/build/install/site/bin/site > build/site.log 2>&1 &
SITE_PID=$!
curl -s -o /dev/null --retry 60 --retry-delay 1 --retry-all-errors "localhost:$REGISTRY_PORT/diag/robots" || exit 1

# 적재 토큰은 site 만 쥔다(스펙 §4).
env -u PICASSO_INGEST_TOKEN ops-service/build/install/ops-service/bin/ops-service > build/ops.log 2>&1 &
OPS_PID=$!
echo "robots: $(curl -s --retry 60 --retry-delay 1 --retry-all-errors "localhost:$OPS_PORT/api/robots")"
echo "operations: $(curl -s "localhost:$OPS_PORT/api/operations")"

# 1:1 시간 진행 확인. 명부의 기체 하나를 registry 에 직접 선언하고 45초 안에 CONFIRMED 가 되는지 본다.
curl -s -o /dev/null -w "declare: %{http_code}\n" -X POST "localhost:$REGISTRY_PORT/operations/robots" \
  -H "Authorization: Bearer $PICASSO_OPERATOR_TOKEN" -H "X-Actor: engineer/smoke" -H "Content-Type: application/json" \
  -d "{\"robot_id\":\"humanoid-01\",\"site\":\"$SITE_ID\",\"serial_number\":\"HA-0001\"}"
for i in $(seq 1 45); do
  if curl -s "localhost:$OPS_PORT/api/robots" | grep -q '"status":"CONFIRMED"'; then
    echo "CONFIRMED after ${i}s"
    exit 0
  fi
  sleep 1
done
echo "NOT CONFIRMED in 45s"
exit 1
```

- [ ] **Step 3: 손으로 띄워 보기**

Run(Git Bash, 저장소 루트): `bash site/smoke.sh`
Expected: `robots:` 줄에 `"registry":"OK"` 와 `"robots":[]`, `operations: []`, `declare: 201`, `CONFIRMED after Ns`(N 은 30 안팎), 종료 코드 0. 끝난 뒤 `docker ps` 에 `site-postgres` 가 없다. 실패하면 `build/site.log`·`build/ops.log` 를 읽는다(스크래치에서 이 스크립트로 확인했다).

- [ ] **Step 4: README 쓰기(컨트롤러가 한다)**

문장은 Fable 과 Codex 에 같은 브리프로 초안을 받아 취합한다. 담을 사실은 다음과 같다.
- 저장소의 일(스펙 §1 첫 문단), 구성(스펙 §4 의 트리), 근거 스펙 경로
- 선행 도구: Docker(시험·Postgres), JDK 17 이상(Gradle 이 JDK 21 툴체인을 받는다), Node 22
- 받기: `git clone --recurse-submodules https://github.com/LivingLikeKrillin/picasso-ops.git`(이미 받았으면 `git submodule update --init`). 서브모듈이 없으면 첫 빌드가 카탈로그 경로에서 멈춘다. Windows 에서 `Filename too long` 이면 `git -C picasso config core.longpaths true` 뒤 다시 체크아웃
- `picasso/` 는 읽기 전용이다(스펙 §4). picasso 쪽 변경은 picasso 저장소의 PR 로 낸다
- 시험: `./gradlew build`, `cd ui && npm ci && npm test`
- 띄우기: 한 번에 확인은 `bash site/smoke.sh`. 오래 띄울 때는 `docker compose -f site/compose.yaml --env-file .env up -d --wait` 와 `./gradlew :site:installDist :ops-service:installDist` 뒤, 터미널 3개에서 `set -a; . ./.env; set +a` 를 하고 각각 `site/build/install/site/bin/site`, `env -u PICASSO_INGEST_TOKEN ops-service/build/install/ops-service/bin/ops-service`, `cd ui && npm ci && npm run dev`. Gradle `run` 작업 둘을 한 작업 트리에서 겹쳐 띄우지 않는다
- `.env` 는 로컬 PoC 값이며 토큰은 `site` 만 둘 다, 운영 서비스는 운영자 토큰만 받는다
- 선언 전 mimic 보고는 registry 가 거절하며 어디에도 남지 않는다. 기체를 선언하면 보고가 붙는다

- [ ] **Step 5: 임시 커밋**

```bash
git add .github/workflows/ci.yml README.md site/smoke.sh
git commit -m "chore(s1a): CI 와 README" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

### Task 10: 전체 확인, 스펙 정정, PR

**Files:**
- Modify: `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md`
- Modify: `docs/superpowers/plans/2026-10-07-s1a-skeleton.md`(끝에 «실행 결과» 절)

- [ ] **Step 1: 전체 빌드와 XML**

Run: `./gradlew build --console=plain` 그리고 세 모듈 각각 XML 확인. 이어서 `cd ui && npm ci && npm test && npm run build; cd ..`
Expected: `site` 12, `ops-service` 23, `e2e` 3, 실패 0. vitest `8 passed`.

- [ ] **Step 2: 커밋된 트리만으로 도는지 확인**

Run: `git status --short`
Expected: 출력 없음(무시된 것만 남는다). 이어서 브랜치를 스크래치 디렉터리에 새로 클론해 커밋된 파일만으로 돌린다(빠뜨린 `git add` 를 잡는다).

```bash
D=C:/Users/Eisen/AppData/Local/Temp/s1a-clone
rm -rf "$D"
git clone -q --recurse-submodules -b feat/s1a-skeleton "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" "$D"
(cd "$D" && ./gradlew build --console=plain -q && cd ui && npm ci && npm test)
```
Expected: 둘 다 성공. 세션 스크래치 디렉터리가 아니라 짧은 경로를 쓰는 것은 picasso 의 긴 파일 이름 때문이다(스크래치 경로에서 `Filename too long` 을 실측했다). 원본 경로는 `C:/` 형식으로 준다(`/c/...` 형식은 `[projects]` 때문에 못 찾는다). 클론의 picasso 서브모듈이 원격 URL 을 따라 GitHub 에서 받아지는지도 이 단계가 함께 본다. 끝나면 `rm -rf "$D"`.

- [ ] **Step 3: 스펙 정정(컨트롤러가 한다)**

문장은 Fable·Codex 초안 취합이다. 고칠 사실:
- §3 표 S1a 행: `서브모듈(cd688ff 고정)` 을 `서브모듈(6b1a255 고정)` 으로. S1c 행의 «P1 머지 뒤 서브모듈을 P1 머지 커밋으로 옮김» 을 지운다.
- §3 표 아래 문단: P1 이 S1a 착수 전에 머지되어 S1a 부터 `6b1a255` 에 고정했고, 그래서 S1c 의 첫 커밋(포인터 옮기기)이 없어졌다.
- §4: 트리의 `.env` 설명과 본문에 DB 접속값·포트·토큰 2개가 함께 있음을 적는다. 토큰 표의 행을 «부르는 쪽» 과 «검증하는 쪽» 으로 고친다. 운영자 토큰은 부르는 쪽이 운영 서비스만이고 검증하는 쪽이 `site` 안의 registry 다. 적재 토큰은 부르는 쪽이 `site` 의 mimic 만이고 검증하는 쪽이 같은 registry 다. 그래서 `site` 는 토큰 2개를 다 받고, 운영 서비스는 운영자 토큰만 받는다(셸에서 상속된 적재 토큰도 지운다). registry 와 운영 서비스는 127.0.0.1 에만 연다. §4 의 «통합 시험은 같은 `.env` 를 읽어 속성으로 넣고» 는 실제와 맞춘다. e2e 는 `.env` 에서 `SITE_ID` 만 받고 DB 접속값과 토큰은 시험 값을 쓴다. compose 는 `env_file` 이 아니라 `--env-file .env` 로 값을 받는다.
- §6: «이 거절은 `site/` 로그에서만 보입니다» 를 «uplink 가 결과를 버리고 registry 도 남기지 않아 어디에도 보이지 않습니다» 로. §12 첫 후보의 비고도 같이.
- §7.1: ops 스키마는 Spring Boot 의 Flyway 자동설정이 아니라 코드(`OpsSchema`)로 올린다(이유: 자동설정 기본 위치가 통합 시험 JVM 에서 registry 마이그레이션을 집는다). 조작 기록은 행 트리거와 문장 트리거로 UPDATE·DELETE·TRUNCATE 를 막는다. 사용자 이름은 `[A-Za-z0-9._-]` 1~64자다.
- §7.4: `REGISTRY_UNAUTHORIZED` 는 `/operations` 이하 호출에서만 나온다. `/diag` 이하는 운영자 토큰 관문 밖이다.
- §10 마지막 문단: 미뤄 둔 물음 2개의 답(testFixtures 는 포함 빌드에서 쓴다, `reset()` 은 public 만 지운다).
- §11: 첫 문단의 «S1c 에서 서브모듈 포인터를 옮기면(§3)…» 문장을 지우고, 사실 표를 `6b1a255` 기준으로 다시 확인했다고 적는다. 사실 행 2개를 더한다. `/diag` 이하는 운영자 토큰 관문 밖이다(`OperatorToken.kt` 의 `GUARDED`). 생존 보고 거절은 uplink 가 버린다(`uplink/.../IngestBridge.kt`).

- [ ] **Step 4: 이 계획 끝에 «실행 결과» 절 덧붙이기**

P1 계획과 같은 모양으로 쓴다. 담을 것: 브랜치·커밋, 계획과 달라진 점, 모듈별 시험 수와 실패 0, 결함 주입 결과, 손 기동 결과(`CONFIRMED after Ns`), 깨끗한 클론 빌드 결과. CI 결과는 아직 없으므로 «CI 대기» 로 적는다.

- [ ] **Step 5: 임시 커밋을 하나로 합치기**

커밋 문장은 Fable·Codex 초안 취합이다. 기준은 로컬 main 이 아니라 분기점이다. 합치기 전후의 트리가 같은지 대조한다.

```bash
git add docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md docs/superpowers/plans/2026-10-07-s1a-skeleton.md
git commit -m "chore(s1a): 스펙 정정과 실행 결과" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
BASE=$(git merge-base origin/main HEAD)
T=$(git rev-parse 'HEAD^{tree}')
git log --oneline "$BASE"..HEAD
git reset --soft "$BASE"
git commit -F <취합한 메시지 파일>
git log --oneline "$BASE"..HEAD
test "$(git rev-parse 'HEAD^{tree}')" = "$T" && echo TREE-SAME
git status --short
```
Expected: 첫 `git log` 에 임시 커밋 10개(Task 1~9 에서 1개씩, 스펙 정정과 실행 결과 1개), 둘째 `git log` 에 커밋 1개, `TREE-SAME`, `git status --short` 출력 없음.

- [ ] **Step 6: 푸시와 PR(사용자 승인 뒤)**

푸시와 PR 생성은 사용자 승인을 받은 뒤 한다. PR 본문은 Fable·Codex 초안 취합이며 형식 훅이 `--body-file -` 만 읽는다.

```bash
git push -u origin feat/s1a-skeleton
gh pr create --repo LivingLikeKrillin/picasso-ops --base main --head feat/s1a-skeleton --title "<제목>" --body-file - < <본문 파일>
```

- [ ] **Step 7: CI 결과 한 번 읽기**

CI 를 폴링하지 않는다. PR 을 만든 뒤 앱의 PR 도구(`get_status`, 필요하면 `bind_pr`)로 CI 상태를 한 번 읽거나 사용자의 보고를 받는다. 두 job(`gradle`, `ui`)이 모두 초록이면 S1a 완료 판정(스펙 §3)의 셋째 조건이 닫힌다. 빨가면 `test-results` 아티팩트의 XML 에서 실패 이름을 읽는다. 결과는 «실행 결과» 절의 «CI 대기» 를 바꾸는 후속 커밋으로 남긴다. 머지는 사용자 승인 뒤다.
