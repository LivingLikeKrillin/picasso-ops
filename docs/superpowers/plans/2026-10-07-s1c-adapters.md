# S1c 어댑터 등록 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** S1b 위에서 picasso P1 의 어댑터 REST 를 화면에서 쓴다. 엔지니어가 어댑터 제품을 선언하고, 그 제품의 빌드를 선언하고, 빌드를 골라 이 사이트에 인스턴스를 등록하면 목록에 `UNTESTED` 로 보인다. 완료 판정은 스펙 §3 의 S1c 행이다. 통합 시험에서 제품 등록 → 빌드 등록 → 인스턴스 등록 → 목록에 `UNTESTED` 표시가 돌고, P1·인스턴스 거절(400/404/409)이 대응표(스펙 §7.4)대로 보인다.

**Architecture:** 운영 서비스에 어댑터 조작 서비스 `AdapterOperations`, 거절 대응표 `AdapterRejections`, 목록 서비스 `AdapterListService`, 조작 API `AdapterOperationsController` 를 더하고, `RegistryClient` 에 어댑터 읽기 2개와 조작 3개를 더한다. 조작 기록과 응답 없음 뒤 재조회는 S1b 의 `RobotOperations` 에서 공용 실행기 `OperationRunner` 로 빼내 기체 조작과 어댑터 조작이 같이 쓴다. 화면은 로봇·연결 영역 왼쪽의 기체 목록 아래에 «어댑터» 구역(인스턴스, 제품·빌드, 등록 폼 3개)을 더한다. 통합 시험은 S1b 의 `E2eStack` 위에 `AdapterTest` 를 얹고, Playwright 생애주기 시험에 어댑터 흐름을 더한다.

**Tech Stack:** S1b 와 같다(Kotlin 2.4.20, Spring Boot 3.4.0 BOM, Gradle 9.7.1, PostgreSQL 16, Testcontainers, JUnit5, React 19, Vite 8, TypeScript 6, vitest 5, `@playwright/test` 1.63.0). 더하는 의존성은 없다.

**근거 스펙:** `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` §3(S1c 행), §5(P1 의 API 와 응답 모양), §7.4(대응표의 P1·인스턴스 행), §8, §9(반영 판정 표), §10. picasso 서브모듈 `6b1a255` 의 `registry/.../web/AdapterOperationsController.kt`, `registry/.../web/OperationsController.kt`(`POST /operations/adapter-instances`), `registry/.../adapter/AdapterInstanceService.kt`.

**스펙과 다른 결정(이 계획이 정함):**
1. **조작 기록과 응답 없음 뒤 재조회를 공용 실행기 `OperationRunner` 로 뺀다.** S1b 의 `RobotOperations` 안에 있던 흐름이다. 동작은 바뀌지 않으며 S1b 의 `RobotOperationsTest` 12개가 그대로 지킨다. 재조회는 «반영됐는가» 와 «무엇을 보았나» 를 함께 내는 `Recheck` 하나로 넘긴다.
2. **어댑터 조작 3가지는 엔지니어 모드이고 운영 서비스가 403 으로 집행한다**(스펙 §8 «등록과 어댑터 관련 조작은 엔지니어 모드», S1b 결정 1 의 연장). 행위자·모드 관문은 기체 조작과 같은 함수(`Guard.kt`)를 쓴다. registry 에 보내기 전에 막은 요청은 조작 기록에 남지 않는다(S1b 결정 2). 사이트 대조는 인스턴스 등록에만 있다. 제품과 빌드는 사이트에 매이지 않는다.
3. **인스턴스 등록 본문의 빌드 id 가 비면 운영 서비스가 `BUILD_REQUIRED` 400 으로 막는다.** 널이 안 되는 `Long` 칸으로 두면 Jackson 이 빈 칸을 0 으로 읽어 registry 에 보내고, registry 의 400 이 조작 기록에 남는다(스크래치 실측). 그래서 칸을 널 허용으로 받는다.
4. **거절 종류 값은 `ADAPTER_BAD_REQUEST`(제품·빌드 400), `UNKNOWN_ADAPTER`(빌드 404), `VERSION_CONFLICT`(빌드 409), `INSTANCE_BAD_REQUEST`(인스턴스 400) 이다.** 모두 엔지니어가 화면 안에서 푼다. 표에 없는 응답은 S1b 와 같이 `UNCLASSIFIED`(화면 밖)다. 409 의 관측값에 registry 가 준 기존 계약값(`existing_contract_semver`)을 싣는다. «바로 갈 링크» 칸은 비운다. 링크는 기체 상세를 여는 버튼이고, 어댑터 목록은 같은 영역에 늘 보인다.
5. **응답 없음 뒤 «반영됨» 판정을 스펙 §9 의 «목록에 있음» 보다 좁힌다.** 제품은 그 제품이 목록에 있으면 반영된 것이다(registry 는 이미 있는 제품을 그대로 둔다). 빌드는 그 버전이 같은 계약값으로 있어야 한다(같은 버전에 다른 계약값은 registry 가 409 로 거절한다). 인스턴스는 그 인스턴스가 이 사이트에 요청한 빌드와 플릿 주소로 있어야 한다(같은 id 의 재등록은 registry 가 덮어쓴다). 인스턴스 목록에는 빌드 id 가 없어 제품 목록에서 빌드의 제품 이름과 버전을 찾아 맞대며, 재조회는 두 목록을 다 읽어야 하고 하나라도 못 읽으면 확인 행을 붙이지 않는다.
6. **화면의 어댑터 읽기는 `GET /api/adapters` 하나다.** 제품·빌드(`GET /operations/adapters`)와 이 사이트의 인스턴스(`GET /diag/adapter-instances?site=`)를 같은 시각으로 읽고, 둘 다 읽혀야 새 값으로 바꾼다. 인스턴스는 빌드를 이름과 버전으로만 가리키므로 다른 시각의 둘을 섞으면 서로 맞지 않는 목록이 보일 수 있다. 인스턴스를 먼저, 제품·빌드를 나중에 읽는다. 제품과 빌드는 지워지지 않으므로 이 순서면 보이는 인스턴스의 빌드가 늘 목록에 있다(registry 의 `AdapterService.list` 가 빌드를 먼저 읽는 것과 같은 이유). 제품·빌드 읽기는 관문 안이라 토큰이 틀리면 `REGISTRY_UNAUTHORIZED` 이고 직전 값을 지킨다. 그래서 어댑터 목록은 토큰 불일치 때 «직전 값» 으로 보인다. 기체 목록은 관문 밖에서 새로 읽으므로 그렇지 않다(S1b 결정 7). 화면 전체 상태 배너는 S1b 처럼 기체 목록의 상태로 보인다. 화면은 기체 목록, 어댑터 목록, 조작 기록을 한 번에 읽으므로 셋 중 하나라도 운영 서비스가 답하지 않으면 셋 다 직전 값이 된다.
7. **화면 배치.** 로봇·연결 영역 왼쪽의 기체 목록 아래에 «어댑터» 구역을 두고, 인스턴스, 제품·빌드 순으로 보인다(스펙 §8 의 목록 순서). 등록 폼 3개(제품 선언, 빌드 선언, 인스턴스 등록)는 엔지니어 모드에서만 보이고, 운영자 모드에서는 «어댑터 등록은 엔지니어 모드에서 합니다» 를 적는다. 빌드와 인스턴스의 대상은 목록에서 고른다. 결과 알림은 기체 조작과 같은 자리(상세 위)에 보이며 «acme/fleet 1.0.0 빌드 선언: 반영됨» 처럼 대상과 조작을 함께 적는다. 적합성은 registry 값 그대로(`UNTESTED`) 보인다. 운영 서비스가 목록 칸을 주지 않으면(널이거나 없으면) «모름» 이다.
8. **조작 기록의 대상 칸은 `adapter <vendor>/<name>`, `build <adapterId>@<version>`, `instance <instanceId>` 다.** 빌드는 registry 감사 기록의 subject 와 같은 꼴이라 둘을 맞대 볼 수 있다.
9. **Playwright 생애주기 시험에 어댑터 흐름을 더한다.** 스펙 §10 의 화면 행 «위 흐름을 1회» 에 S1c 의 흐름도 넣는다. registry 정지는 맨 끝에 그대로 두고, 그때 어댑터 목록도 직전 값으로 남는지 본다. 기체 목록과 어댑터 목록이 같은 «직전 값» 문구를 쓰므로 영역별로 확인한다.

**스크래치에서 미리 확인한 것(2026-10-07, main `d94a5e6` 위, Docker 26.1.4):** 이 계획의 코드는 같은 내용으로 스크래치 빌드에서 돌렸고, 계획의 코드 블록은 그 파일에서 기계로 옮겼다. Kotlin 시험 105개(site 12, ops-service 73, e2e 20)와 vitest 30개가 통과했다. Playwright 시험 1개가 Windows 에서 통과했다(53.3초, 스택 기동 포함 1.5분). 끝난 뒤 남은 프로세스와 컨테이너는 0개였다. 각 작업의 결함 주입은 적힌 실패 이름 그대로 잡혔다(Kotlin 16건, 화면 5건, Playwright 1건). 계획 검토는 청크별 3명이 한 번 보았고 지적을 반영했다. 반영으로 바뀐 코드(어댑터 목록의 읽기 순서, 화면 시험의 첫 읽기 대기, 통합 시험의 토큰 불일치 확인)는 마지막 코드로 시험·결함 주입·Playwright 를 다시 실측했다.
- 덧붙여 드러난 것: 인스턴스 등록 본문의 빌드 id 를 널이 안 되는 `Long` 으로 받으면, 칸이 없을 때 Jackson 이 0 으로 읽는다. 스프링의 400 이 나지 않고 registry 까지 가서 조작 기록에 거절 행이 남았다(머리말 결정 3).
- 덧붙여 드러난 것: S1b 의 Playwright 마지막 확인 `getByText(/직전 값입니다/)` 는 어댑터 목록이 같은 문구를 보이자 두 요소에 걸려 실패했다(머리말 결정 9).

**작업 위치 규칙(필수):**
- picasso-ops 체크아웃(`C:\Users\Eisen\Desktop\Labs\[projects] picasso-ops`)의 브랜치 `feat/s1c-adapters` 에서 일한다(Task 0 이 기준을 정한다). picasso 메인 체크아웃은 건드리지 않는다.
- 서브모듈 `picasso/` 안의 파일을 고치지 않는다(스펙 §4).
- `./gradlew --stop` 금지. 같은 작업 트리에서 Gradle 을 동시에 두 번 돌리지 않는다.
- `git add -A` 금지. 파일을 이름으로 더한다. `--no-verify` 금지.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다. vitest 는 출력의 `Tests` 줄, Playwright 는 `N passed`·`N failed` 줄과 실패 이름으로 한다.
- 결함 주입은 하나씩 넣는다. 넣기 전에 파일을 복사해 두고, 확인 뒤 복원하고 `git diff`(또는 `cmp`)로 원래대로인지 본다. 결함 주입을 돌릴 때는 `--rerun` 을 붙인다(`:ops-service:test --rerun :e2e:test --rerun` 처럼 작업마다).
- Windows 의 파이썬 `subprocess` 에서 `bash` 는 WSL 의 bash 를 잡는다. Gradle 은 Git Bash 에서 직접 부른다.
- KDoc(`/** */`) 안에 사선 바로 뒤에 별표를 쓰지 않는다. 중첩 주석이 열려 컴파일이 깨진다. 이 계획의 코드는 이미 피했으니 그대로 옮긴다.
- 새 파일은 LF 다(`.gitattributes` 가 집행한다).
- 작업마다 임시 커밋을 남기고 Task 9 에서 하나로 합친다. 커밋은 형식 훅 때문에 반드시 이 꼴로 한다(`-m` 을 두 번 쓰면 훅이 거절한다).

```bash
git commit -F - <<'EOF'
chore(s1c): <제목>

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- 마지막 커밋·PR·문서 문장은 사용자 지시에 따라 Fable 과 Codex 에 같은 브리프로 초안을 받아 취합한다(Gemini 한도 소진 중). 형식: 제목 `type(scope): 명사구`, 불릿 명사형, «~다» 종결 금지, em-dash·en-dash·겹화살괄호·낫표 금지.

**전제:** 이 계획 파일은 브랜치 `feat/s1c-adapters` 의 첫 커밋이다(계획을 쓸 때 `origin/main` 의 `d94a5e6` 위에 만들었다). 계획 커밋은 Task 9 에서도 따로 남는다.

**XML 확인**(모듈 이름을 바꿔 쓴다):

```bash
for f in ops-service/build/test-results/test/*.xml; do grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"; done
for f in ops-service/build/test-results/test/*.xml; do grep -B1 "<failure" "$f" | grep -o 'testcase name="[^"]*"'; done
```

첫 줄은 모음마다 개수, 둘째 줄은 실패한 시험 이름이다.

---

## Chunk 1: registry 접점과 조작 실행기

### Task 0: 브랜치와 기준선

**Files:** 없음(환경)

- [ ] **Step 1: 브랜치와 기준 확인**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops"
git switch feat/s1c-adapters
git status -sb
git log --oneline -2
git fetch -q origin
git log -1 --format=%s
git merge-base --is-ancestor d94a5e6 HEAD && echo on-s1b
git submodule status
```
Expected: 작업 트리 변경 없음, `git log -1` 이 `docs(plans): ` 로 시작하는 이 계획의 커밋이고 그 아래가 `d94a5e6`(S1b 머지), `on-s1b`, 서브모듈 `6b1a255`. 계획 커밋이 없으면 멈추고 보고한다. main 이 그 뒤로 더 나갔으면 그대로 진행한다(PR 이 main 을 겨눈다). 계획 커밋의 해시를 보고에 적는다(Task 9 가 쓴다).

- [ ] **Step 2: 기준선 시험**

```bash
./gradlew build --console=plain -q
cd ui && npm ci --no-audit --no-fund && npm test && cd ..
```
Expected: XML 확인에서 site 12, ops-service 55, e2e 13, 실패 0. vitest `23 passed`. 아니면 멈추고 보고한다(기준선 실패를 새 변경과 섞지 않는다). XML 이 이 실행에서 새로 쓰였는지(시각)도 본다. Gradle 이 UP-TO-DATE 로 건너뛰었으면 `./gradlew test --rerun --console=plain -q` 로 다시 돌린다.

### Task 1: registry 어댑터 읽기와 조작

**Files:**
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt`(파일 전체를 아래로 바꾼다)
- Test: `ops-service/src/test/kotlin/dev/picasso/ops/service/RegistryAdaptersTest.kt`

registry 는 `GET /operations/adapters` 를 snake_case 로, `GET /diag/adapter-instances` 를 camelCase 로 준다(스펙 §5, picasso `AdapterInstanceRow`). 운영 서비스는 둘 다 camelCase 로 화면에 넘기므로 snake_case 는 `@JsonAlias` 로 읽기만 한다. 조작 3개는 S1b 의 기체 조작과 같은 `send` 로 운영자 토큰과 `X-Actor` 를 싣는다. 읽은 값만 바꾸는 `RegistryCall.map` 도 여기에 둔다(Task 2·3 이 쓴다).

- [ ] **Step 1: 실패하는 시험 쓰기**

`ops-service/src/test/kotlin/dev/picasso/ops/service/RegistryAdaptersTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.registry.RegistryAdapter
import dev.picasso.ops.service.registry.RegistryBuild
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryClient
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 어댑터 읽기와 조작이 registry 와 주고받는 모양(스펙 §5·§7.2). registry 는 JDK HttpServer 대역이다. */
class RegistryAdaptersTest {

    private class Seen(val method: String, val uri: String, val actor: String?, val body: String)

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
                exchange.requestBody.readBytes().decodeToString(),
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

    @Test
    fun `제품·빌드 목록은 registry 의 snake_case 를 읽는다`() {
        val body = """[{"adapter_id":1,"vendor":"acme","name":"fleet","versions":[{"adapter_version_id":10,"version":"1.0.0",
            "contract_semver":"0.9.0","conformance":"UNTESTED","registered_at":"t","registered_by":"engineer/lee"}]}]"""
        val call = RegistryClient(serve(200, body), "op-t").adapters()
        val build = RegistryBuild(10, "1.0.0", "0.9.0", "UNTESTED", "t", "engineer/lee")
        assertEquals(RegistryCall.Ok(listOf(RegistryAdapter(1, "acme", "fleet", listOf(build)))), call)
        assertEquals("GET /operations/adapters", "${seen!!.method} ${seen!!.uri}")
    }

    @Test
    fun `인스턴스 목록은 사이트로 묻는다`() {
        val body = """[{"instanceId":"i1","siteId":"site 01","fleetEndpoint":null,"registeredAt":"t","registeredBy":"engineer/lee",
            "adapter":"acme/fleet","version":"1.0.0","contractSemver":"0.9.0","conformance":"UNTESTED","discoveredRobots":0}]"""
        val call = RegistryClient(serve(200, body), "op-t").instances("site 01")
        assertEquals("UNTESTED", (call as RegistryCall.Ok).value.single().conformance)
        assertEquals("GET /diag/adapter-instances?site=site+01", "${seen!!.method} ${seen!!.uri}")
    }

    @Test
    fun `조작 3가지는 registry 의 경로와 본문 모양으로 X-Actor 를 싣고 보낸다`() {
        val client = RegistryClient(serve(201, "{}"), "op-t")
        client.declareAdapter("acme", "fleet", "engineer/lee")
        assertEquals("POST /operations/adapters engineer/lee", "${seen!!.method} ${seen!!.uri} ${seen!!.actor}")
        assertEquals(json.readTree("""{"vendor":"acme","name":"fleet"}"""), json.readTree(seen!!.body))

        client.declareBuild(1, "1.0.0", "0.9.0", "engineer/lee")
        assertEquals("POST /operations/adapters/1/versions", "${seen!!.method} ${seen!!.uri}")
        assertEquals(json.readTree("""{"version":"1.0.0","contract_semver":"0.9.0"}"""), json.readTree(seen!!.body))

        client.registerInstance("site-01", "i1", 10, null, "engineer/lee")
        assertEquals("POST /operations/adapter-instances", "${seen!!.method} ${seen!!.uri}")
        assertEquals(json.readTree("""{"instance_id":"i1","adapter_version_id":10,"site":"site-01"}"""), json.readTree(seen!!.body))

        client.registerInstance("site-01", "i1", 10, "tcp://fleet:1", "engineer/lee")
        assertEquals("tcp://fleet:1", json.readTree(seen!!.body)["fleet_endpoint"].asText())
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 확인**

Run: `./gradlew :ops-service:test --tests '*RegistryAdaptersTest' --console=plain`
Expected: `compileTestKotlin` 실패(`RegistryAdapter`·`RegistryBuild`·`adapters` 등 없음).

- [ ] **Step 3: 구현**

`ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt`(전체):

```kotlin
package dev.picasso.ops.service.registry

import com.fasterxml.jackson.annotation.JsonAlias
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
 * 값을 모른다는 점이 같다. 조작은 [RegistryWrite] 로 따로 돌려준다.
 *
 * [Unauthorized] 는 운영자 토큰 관문(`/operations` 이하) 안의 호출에서만 나온다. 목록 읽기 `/diag/robots` 는
 * 관문 밖이라 토큰을 [TokenProbe] 가 관문 안의 읽기로 따로 확인한다.
 */
sealed interface RegistryCall<out T> {
    data class Ok<T>(val value: T) : RegistryCall<T>

    data class Silent(val cause: String) : RegistryCall<Nothing>

    /** 운영 서비스의 운영자 토큰이 registry 와 맞지 않는다(스펙 §7.4). */
    data object Unauthorized : RegistryCall<Nothing>
}

/** 읽은 값만 바꾼다. 읽지 못한 결과는 그대로 둔다. */
fun <T, R> RegistryCall<T>.map(transform: (T) -> R): RegistryCall<R> = when (this) {
    is RegistryCall.Ok -> RegistryCall.Ok(transform(value))
    is RegistryCall.Silent -> this
    RegistryCall.Unauthorized -> RegistryCall.Unauthorized
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

/**
 * registry `GET /operations/adapters` 의 빌드 한 줄. registry 는 snake_case 로 주고, 운영 서비스는 camelCase 로
 * 화면에 넘긴다. [JsonAlias] 는 읽을 때만 쓰인다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryBuild(
    @JsonAlias("adapter_version_id") val adapterVersionId: Long,
    val version: String,
    @JsonAlias("contract_semver") val contractSemver: String,
    val conformance: String,
    @JsonAlias("registered_at") val registeredAt: String? = null,
    @JsonAlias("registered_by") val registeredBy: String? = null,
)

/** registry `GET /operations/adapters` 의 제품 한 줄. 빌드 목록이 안에 든다(스펙 §5). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryAdapter(
    @JsonAlias("adapter_id") val adapterId: Long,
    val vendor: String,
    val name: String,
    val versions: List<RegistryBuild> = emptyList(),
)

/** registry `GET /diag/adapter-instances` 의 한 줄. 빌드 id 는 없고 제품 이름(`vendor/name`)과 버전이 있다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryInstance(
    val instanceId: String,
    val siteId: String,
    val fleetEndpoint: String? = null,
    val registeredAt: String? = null,
    val registeredBy: String? = null,
    val adapter: String,
    val version: String,
    val contractSemver: String,
    val conformance: String,
    val discoveredRobots: Int = 0,
)

/** 어댑터 제품·빌드와 인스턴스 목록의 출처. 시험이 registry 없이 대신 끼운다. */
interface AdapterSource {
    /** 운영자 토큰 관문 안의 읽기다. 토큰이 틀리면 [RegistryCall.Unauthorized] 다. */
    fun adapters(): RegistryCall<List<RegistryAdapter>>

    fun instances(siteId: String): RegistryCall<List<RegistryInstance>>
}

/** 운영자 토큰 확인. 목록 읽기는 관문 밖이라 401 이 오지 않으므로 관문 안의 읽기를 따로 부른다. */
fun interface TokenProbe {
    fun operatorToken(): RegistryCall<Unit>
}

/**
 * 조작 한 번의 결과. 응답이 오면 코드와 본문을 그대로 넘기고, 분류는 부르는 쪽이 대응표로 한다(스펙 §7.4).
 * 응답이 오지 않으면 반영 여부를 모르는 것이며, 거절과 섞지 않는다(스펙 §9).
 */
sealed interface RegistryWrite {
    data class Answered(val status: Int, val body: String) : RegistryWrite

    data class NoResponse(val cause: String) : RegistryWrite
}

/** 기체 조작 3가지(스펙 §7.2). 시험이 registry 없이 대신 끼운다. */
interface RobotWrites {
    fun declare(siteId: String, robotId: String, serialNumber: String, displayName: String?, actor: String): RegistryWrite

    fun retire(robotId: String, reason: String, actor: String): RegistryWrite

    fun reinstate(robotId: String, actor: String): RegistryWrite
}

/** 어댑터 조작 3가지(스펙 §3 S1c). 시험이 registry 없이 대신 끼운다. */
interface AdapterWrites {
    fun declareAdapter(vendor: String, name: String, actor: String): RegistryWrite

    fun declareBuild(adapterId: Long, version: String, contractSemver: String, actor: String): RegistryWrite

    fun registerInstance(
        siteId: String,
        instanceId: String,
        adapterVersionId: Long,
        fleetEndpoint: String?,
        actor: String,
    ): RegistryWrite
}

/** registry REST 클라이언트. 운영 서비스만 운영자 토큰을 쥔다(스펙 §4). DB 에 직결하지 않는다. */
class RegistryClient(
    baseUrl: String,
    private val token: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
    private val json: ObjectMapper = jacksonObjectMapper(),
) : RobotSource, TokenProbe, RobotWrites, AdapterSource, AdapterWrites, AutoCloseable {

    private val base = checkBaseUrl(baseUrl)

    /** JDK 21 의 HttpClient 는 닫아야 셀렉터 스레드가 끝난다. 스프링이 빈을 내릴 때 부른다. */
    override fun close() = http.close()

    /** 퇴역 기체를 늘 포함한다. 기본값(`retired=false`)은 퇴역 뒤 보고와 복귀를 감춘다(스펙 §7.2). */
    override fun robots(siteId: String): RegistryCall<List<RegistryRobot>> {
        val site = URLEncoder.encode(siteId, StandardCharsets.UTF_8)
        return get("/diag/robots?retired=true&site=$site") { json.readValue<List<RegistryRobot>?>(it) }
    }

    /** 관문 안의 읽기 하나로 토큰을 본다. 읽은 값은 쓰지 않는다. */
    override fun operatorToken(): RegistryCall<Unit> = get("/operations/adapters") { }

    override fun declare(
        siteId: String,
        robotId: String,
        serialNumber: String,
        displayName: String?,
        actor: String,
    ): RegistryWrite {
        val body = json.createObjectNode()
            .put("robot_id", robotId)
            .put("site", siteId)
            .put("serial_number", serialNumber)
        if (displayName != null) body.put("display_name", displayName)
        return send("POST", "/operations/robots", actor, json.writeValueAsString(body))
    }

    override fun retire(robotId: String, reason: String, actor: String): RegistryWrite =
        send(
            "POST", "/operations/robots/${segment(robotId)}/retirement", actor,
            json.writeValueAsString(json.createObjectNode().put("reason", reason)),
        )

    override fun reinstate(robotId: String, actor: String): RegistryWrite =
        send("DELETE", "/operations/robots/${segment(robotId)}/retirement", actor, null)

    override fun adapters(): RegistryCall<List<RegistryAdapter>> =
        get("/operations/adapters") { json.readValue<List<RegistryAdapter>?>(it) }

    override fun instances(siteId: String): RegistryCall<List<RegistryInstance>> {
        val site = URLEncoder.encode(siteId, StandardCharsets.UTF_8)
        return get("/diag/adapter-instances?site=$site") { json.readValue<List<RegistryInstance>?>(it) }
    }

    override fun declareAdapter(vendor: String, name: String, actor: String): RegistryWrite =
        send(
            "POST", "/operations/adapters", actor,
            json.writeValueAsString(json.createObjectNode().put("vendor", vendor).put("name", name)),
        )

    override fun declareBuild(adapterId: Long, version: String, contractSemver: String, actor: String): RegistryWrite =
        send(
            "POST", "/operations/adapters/$adapterId/versions", actor,
            json.writeValueAsString(json.createObjectNode().put("version", version).put("contract_semver", contractSemver)),
        )

    override fun registerInstance(
        siteId: String,
        instanceId: String,
        adapterVersionId: Long,
        fleetEndpoint: String?,
        actor: String,
    ): RegistryWrite {
        val body = json.createObjectNode()
            .put("instance_id", instanceId)
            .put("adapter_version_id", adapterVersionId)
            .put("site", siteId)
        if (fleetEndpoint != null) body.put("fleet_endpoint", fleetEndpoint)
        return send("POST", "/operations/adapter-instances", actor, json.writeValueAsString(body))
    }

    /** [read] 가 널을 내면(본문 `null`) 값을 모르는 것이다. `OK` 인데 목록이 널인 보기를 만들지 않는다. */
    private fun <T : Any> get(path: String, read: (String) -> T?): RegistryCall<T> {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $token")
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

    private fun send(method: String, path: String, actor: String, body: String?): RegistryWrite {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $token")
            .header("X-Actor", actor)
            .header("Content-Type", "application/json")
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            RegistryWrite.Answered(response.statusCode(), response.body())
        } catch (e: IOException) {
            RegistryWrite.NoResponse("응답 없음: ${e.javaClass.simpleName}")
        }
    }

    private fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)

        /** 형식이 틀린 주소를 기동에서 잡는다. 그대로 두면 요청마다 500 이 되어 화면에는 운영 서비스 불통으로 보인다. */
        fun checkBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
            require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                "registry 주소가 http(s)://호스트[:포트] 꼴이 아니다: '$baseUrl'"
            }
            return trimmed
        }
    }
}
```

- [ ] **Step 4: 시험이 통과하는지 확인**

Run: `./gradlew :ops-service:test --console=plain`
Expected: XML 에서 `RegistryAdaptersTest` 3개, ops-service 모듈 전체 58개, 실패 0.

- [ ] **Step 5: 결함 주입 2건(하나씩)**

① `RegistryBuild` 의 `@JsonAlias("adapter_version_id") ` 를 지운다(registry 의 snake_case 를 못 읽는다). Expected 실패 이름: `제품·빌드 목록은 registry 의 snake_case 를 읽는다()`.
② `instances` 의 `"/diag/adapter-instances?site=$site"` 를 `"/diag/adapter-instances"` 로 바꾼다(사이트로 묻지 않는다). Expected 실패 이름: `인스턴스 목록은 사이트로 묻는다()`.
각각 `./gradlew :ops-service:test --rerun --console=plain` 으로 돌리고, 되돌리고 초록을 본다.

- [ ] **Step 6: 임시 커밋**

```bash
git add ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RegistryAdaptersTest.kt
git commit -F - <<'EOF'
chore(s1c): registry 어댑터 읽기와 조작

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 2: 조작 실행기 분리

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/operations/OperationRunner.kt`
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/operations/RobotOperations.kt`(전체)
- Test: 기존 `ops-service/src/test/kotlin/dev/picasso/ops/service/RobotOperationsTest.kt`(고치지 않는다)

동작을 바꾸지 않는 이동이다(머리말 결정 1). 새 시험은 없고 S1b 의 `RobotOperationsTest` 12개가 그대로 지킨다. `RobotOperations` 의 생성자는 그대로라 배선(`OpsApplication`)과 시험이 고칠 것이 없다.

- [ ] **Step 1: 기존 시험이 초록인지 확인**

Run: `./gradlew :ops-service:test --tests '*RobotOperationsTest' --console=plain`
Expected: XML 에서 `RobotOperationsTest` 12개, 실패 0.

- [ ] **Step 2: 실행기 쓰기와 옮기기**

`ops-service/src/main/kotlin/dev/picasso/ops/service/operations/OperationRunner.kt`:

```kotlin
package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryWrite
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 응답 없음 뒤 재조회의 판정.
 *
 * @param applied 이 조작이 반영됐는가
 * @param observed 재조회에서 본 대상의 모습. 확인 행의 `registry_response` 에 `observed` 로 남는다. 대상이 없으면 널이다
 */
data class Recheck(val applied: Boolean, val observed: JsonNode?)

/**
 * 조작 한 번을 registry 에 보내고 조작 기록에 남긴다(스펙 §7.1·§9). 기체 조작과 어댑터 조작이 같이 쓴다.
 *
 * 응답 없음(연결 실패·시간 초과·5xx)은 거절과 다르다. 요청이 닿았는지 모르므로 «응답 없음» 을 남기고 다시 읽어,
 * 반영 여부를 같은 요청 id 의 새 행으로 붙인다. 재시도는 하지 않는다. 재시도는 새 요청 id 를 받는 새 조작이며
 * 사람이 정한다.
 *
 * 재조회는 [requeryDelay] 뒤 한 번이다. 시간 초과 직후에는 registry 가 아직 커밋 중일 수 있어 바로 읽으면
 * «반영 안 됨» 을 잘못 남길 수 있다. 재조회도 실패하면 확인 행을 붙이지 않고, 화면이 목록에서 확인하게 한다.
 */
class OperationRunner(
    private val log: OperationLog,
    private val clock: Clock,
    private val requeryDelay: Duration,
    private val json: ObjectMapper,
) {

    /**
     * @param target 조작 기록의 대상 칸. 예: `robot humanoid-01`
     * @param rejection registry 가 400/404/409 등으로 답했을 때 대응표로 옮긴다(스펙 §7.4)
     * @param recheck 응답 없음 뒤 다시 읽어 반영 여부를 가른다. 읽지 못하면 [RegistryCall.Ok] 가 아닌 값을 낸다
     */
    fun run(
        actor: Actor,
        target: String,
        request: ObjectNode,
        reason: String?,
        rejection: (status: Int, body: JsonNode?, checkedAt: Instant) -> Finding,
        recheck: () -> RegistryCall<Recheck>,
        call: () -> RegistryWrite,
    ): OperationOutcome {
        val id = UUID.randomUUID()
        val requestJson = json.writeValueAsString(request)
        fun record(result: OperationResult, response: String?) =
            log.append(id, actor, target, requestJson, reason, result, response)

        val write = call()
        if (write is RegistryWrite.NoResponse) {
            record(OperationResult.NO_RESPONSE, json.writeValueAsString(json.createObjectNode().put("cause", write.cause)))
            return confirm(id, recheck, ::record, status = null)
        }
        write as RegistryWrite.Answered
        val response = responseJson(write)
        return when {
            write.status in 200..299 -> {
                record(OperationResult.SUCCEEDED, response)
                OperationOutcome(id, OperationResult.SUCCEEDED, null, null, false, write.status)
            }
            write.status == 401 -> {
                record(OperationResult.REJECTED, response)
                OperationOutcome(id, OperationResult.REJECTED, null, null, true, write.status)
            }
            // registry 가 응답했지만 반영 여부를 알 수 없다. 응답 없음과 같이 다룬다(스펙 §7.4).
            write.status >= 500 -> {
                record(OperationResult.NO_RESPONSE, response)
                confirm(id, recheck, ::record, write.status)
            }
            else -> {
                record(OperationResult.REJECTED, response)
                val finding = rejection(write.status, parse(write.body), clock.instant())
                OperationOutcome(id, OperationResult.REJECTED, null, finding, false, write.status)
            }
        }
    }

    /** 응답 없음 뒤 재조회(스펙 §9). 읽지 못하면 행을 붙이지 않고 확인 결과를 널로 둔다. */
    private fun confirm(
        id: UUID,
        recheck: () -> RegistryCall<Recheck>,
        record: (OperationResult, String?) -> Unit,
        status: Int?,
    ): OperationOutcome {
        if (!requeryDelay.isZero) Thread.sleep(requeryDelay)
        val seen = recheck()
        if (seen !is RegistryCall.Ok) return OperationOutcome(id, OperationResult.NO_RESPONSE, null, null, false, status)
        val confirmation = if (seen.value.applied) OperationResult.CONFIRMED_APPLIED else OperationResult.CONFIRMED_NOT_APPLIED
        val observed = json.createObjectNode()
        observed.set<JsonNode>("observed", seen.value.observed ?: json.nullNode())
        record(confirmation, json.writeValueAsString(observed))
        return OperationOutcome(id, OperationResult.NO_RESPONSE, confirmation, null, false, status)
    }

    private fun parse(body: String): JsonNode? = runCatching { json.readTree(body) }.getOrNull()?.takeIf { it.isObject }

    /** 조작 기록의 `registry_response` 칸. 코드와 본문을 남긴다. 본문이 JSON 이 아니면 글자로 남긴다. */
    private fun responseJson(write: RegistryWrite.Answered): String {
        val node = json.createObjectNode().put("status", write.status)
        val body = parse(write.body)
        if (body != null) node.set<JsonNode>("body", body) else node.put("body", write.body)
        return json.writeValueAsString(node)
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/operations/RobotOperations.kt`(전체):

```kotlin
package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RegistryWrite
import dev.picasso.ops.service.registry.RobotSource
import dev.picasso.ops.service.registry.RobotWrites
import dev.picasso.ops.service.registry.map
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * 조작 한 번의 답. 화면은 이것으로 결과를 보인다.
 *
 * @param result 처음 남긴 행의 결과. 성공, 거절, 응답 없음 중 하나다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했으면 널이며, 그때 화면은 반영되었을 수 있으나 확인하지 못했다고 보인다
 * @param rejection registry 가 400/404/409 로 답했을 때의 대응표 판정(스펙 §7.4)
 * @param unauthorized registry 가 401 로 답했다. 조작별이 아니라 화면 전체 상태로 보인다(스펙 §7.4)
 */
data class OperationOutcome(
    val requestId: UUID,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val rejection: Finding?,
    val unauthorized: Boolean,
    val registryStatus: Int?,
)

/**
 * 기체 선언·퇴역·복귀(스펙 §7.2). 모든 조작을 조작 기록에 남긴다(스펙 §7.1). 기록과 응답 없음 뒤 재조회는
 * [OperationRunner] 가 한다. 여기서는 조작마다 보낼 것과 «반영됨» 의 판정을 정한다.
 */
class RobotOperations(
    private val writes: RobotWrites,
    private val reads: RobotSource,
    log: OperationLog,
    private val siteId: String,
    clock: Clock,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = ObjectMapper(),
) {
    private val runner = OperationRunner(log, clock, requeryDelay, json)

    fun declare(actor: Actor, robotId: String, serialNumber: String, displayName: String?): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", RobotOp.DECLARE.name)
            .put("robot_id", robotId)
            .put("site", siteId)
            .put("serial_number", serialNumber)
            .put("display_name", displayName)
        // 선언이 반영된 기체는 선언으로 들어와 퇴역하지 않은 기체다. 목록에는 퇴역 기체와 발견된 기체도 있으므로
        // 그 기체가 목록에 있다는 것만으로는 이 선언이 반영됐다고 할 수 없다(registry 는 그 둘을 409 로 거절한다).
        // 이미 선언된 기체에 다시 선언하면 일련번호와 표시 이름의 갱신이므로 그 둘도 같아야 반영된 것이다.
        val declared = { robots: List<RegistryRobot> ->
            robots.firstOrNull { it.robotId == robotId }?.let {
                it.origin == "DECLARED" && it.status != "RETIRED" && it.serialNumber == serialNumber && it.displayName == displayName
            } == true
        }
        return run(RobotOp.DECLARE, actor, robotId, request, reason = null, applied = declared) {
            writes.declare(siteId, robotId, serialNumber, displayName, actor.header())
        }
    }

    fun retire(actor: Actor, robotId: String, reason: String): OperationOutcome {
        val request = json.createObjectNode().put("op", RobotOp.RETIRE.name).put("robot_id", robotId).put("reason", reason)
        return run(RobotOp.RETIRE, actor, robotId, request, reason, applied = { robots -> statusOf(robots, robotId) == "RETIRED" }) {
            writes.retire(robotId, reason, actor.header())
        }
    }

    fun reinstate(actor: Actor, robotId: String): OperationOutcome {
        val request = json.createObjectNode().put("op", RobotOp.REINSTATE.name).put("robot_id", robotId)
        return run(
            RobotOp.REINSTATE, actor, robotId, request, reason = null,
            applied = { robots -> statusOf(robots, robotId).let { it != null && it != "RETIRED" } },
        ) {
            writes.reinstate(robotId, actor.header())
        }
    }

    /** 확인 행에는 재조회에서 본 그 기체의 출처와 원장 상태를 남겨, 그 행만 읽어도 판정 근거가 보이게 한다. */
    private fun run(
        op: RobotOp,
        actor: Actor,
        robotId: String,
        request: ObjectNode,
        reason: String?,
        applied: (List<RegistryRobot>) -> Boolean,
        call: () -> RegistryWrite,
    ): OperationOutcome = runner.run(
        actor, "robot $robotId", request, reason,
        rejection = { status, body, at -> Rejections.of(op, status, body, at, robotId) },
        recheck = {
            reads.robots(siteId).map { robots ->
                val seen = robots.firstOrNull { it.robotId == robotId }
                Recheck(applied(robots), seen?.let { json.createObjectNode().put("origin", it.origin).put("status", it.status) })
            }
        },
        call = call,
    )

    private fun statusOf(robots: List<RegistryRobot>, robotId: String): String? = robots.firstOrNull { it.robotId == robotId }?.status
}
```

- [ ] **Step 3: 시험이 통과하는지 확인**

Run: `./gradlew :ops-service:test --console=plain`
Expected: XML 에서 `RobotOperationsTest` 12개, ops-service 모듈 전체 58개, 실패 0.

- [ ] **Step 4: 결함 주입 1건**

① `OperationRunner.confirm` 의 `if (!requeryDelay.isZero) Thread.sleep(requeryDelay)` 줄을 지운다(재조회 전 1초 대기가 사라진다). Expected 실패 이름: `재조회는 기본 값으로 쓰면 쓰기 뒤 1초 가까이 기다린 다음에 읽는다()`(`RobotOperationsTest`). 옮긴 뒤에도 S1b 의 시험이 실행기를 지키는지 보는 주입이다. 되돌리고 초록을 본다.

- [ ] **Step 5: 임시 커밋**

```bash
git add ops-service/src/main/kotlin/dev/picasso/ops/service/operations/OperationRunner.kt ops-service/src/main/kotlin/dev/picasso/ops/service/operations/RobotOperations.kt
git commit -F - <<'EOF'
chore(s1c): 조작 기록과 재조회를 공용 실행기로 분리

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

---

## Chunk 2: 어댑터 조작과 목록

### Task 3: 어댑터 거절 대응표와 조작 서비스

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/operations/AdapterRejections.kt`
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/operations/AdapterOperations.kt`
- Test: `ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterRejectionsTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterOperationsTest.kt`

대응표는 스펙 §7.4 의 P1·인스턴스 행이다(머리말 결정 4). 조작 서비스는 `OperationRunner` 위에서 조작마다 보낼 것, 대상 칸(머리말 결정 8), «반영됨» 판정(머리말 결정 5)을 정한다. `AdapterOperationsTest` 는 S1b 의 `RobotOperationsTest` 처럼 Testcontainers 의 Postgres 에 조작 기록을 남긴다.

- [ ] **Step 1: 실패하는 시험 쓰기**

`ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterRejectionsTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.operations.AdapterOp
import dev.picasso.ops.service.operations.AdapterRejections
import dev.picasso.ops.service.operations.Rejections
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 어댑터 조작의 거절 대응표(스펙 §7.4 의 P1·인스턴스 행)의 행마다 응답 코드와 본문을 넣는다. */
class AdapterRejectionsTest {

    private val at = Instant.parse("2026-10-07T00:00:00Z")
    private val json = ObjectMapper()

    private data class Row(val op: AdapterOp, val status: Int, val body: String, val kind: String, val action: String)

    private val table = listOf(
        Row(AdapterOp.DECLARE_ADAPTER, 400, """{"error":"vendor 와 name 은 비울 수 없다"}""", AdapterRejections.ADAPTER_BAD_REQUEST, "고쳐서 다시"),
        Row(AdapterOp.DECLARE_BUILD, 400, """{"error":"계약 semver가 형식이 아니다"}""", AdapterRejections.ADAPTER_BAD_REQUEST, "고쳐서 다시"),
        Row(AdapterOp.DECLARE_BUILD, 404, """{"error":"모르는 어댑터다: 9"}""", AdapterRejections.UNKNOWN_ADAPTER, "제품 목록 새로 읽기"),
        Row(
            AdapterOp.DECLARE_BUILD, 409, """{"error":"같은 버전이 다른 계약 semver 로 이미 있다","existing_contract_semver":"1.0.0"}""",
            AdapterRejections.VERSION_CONFLICT, "다른 버전 번호로",
        ),
        Row(AdapterOp.REGISTER_INSTANCE, 400, """{"error":"모르는 어댑터 빌드다: 9"}""", AdapterRejections.INSTANCE_BAD_REQUEST, "고쳐서 다시"),
    )

    @Test
    fun `대응표의 행마다 종류와 후속 행동이 맞고 엔지니어가 화면 안에서 푼다`() {
        table.forEach { row ->
            val finding = AdapterRejections.of(row.op, row.status, json.readTree(row.body), at)
            assertEquals(row.kind to row.action, finding.kind to finding.action, "$row")
            assertEquals(Owner.ENGINEER to true, finding.owner to finding.inScreen, "$row")
            assertEquals(at, finding.checkedAt)
            assertNull(finding.target)
        }
    }

    @Test
    fun `409 는 기존 계약값을 관측값에 싣는다`() {
        val conflict = AdapterRejections.of(
            AdapterOp.DECLARE_BUILD, 409, json.readTree("""{"error":"같은 버전","existing_contract_semver":"1.0.0"}"""), at,
        )
        assertEquals("HTTP 409, existing_contract_semver=1.0.0, 같은 버전", conflict.observed)
    }

    @Test
    fun `표에 없는 응답은 아는 종류로 접지 않고 화면 밖 조사로 둔다`() {
        val cases = listOf(AdapterOp.DECLARE_ADAPTER to 409, AdapterOp.DECLARE_ADAPTER to 404, AdapterOp.REGISTER_INSTANCE to 404)
        cases.forEach { (op, status) ->
            val finding = AdapterRejections.of(op, status, null, at)
            assertEquals(Rejections.UNCLASSIFIED to false, finding.kind to finding.inScreen, "$op $status")
        }
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterOperationsTest.kt`:

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.AdapterOperations
import dev.picasso.ops.service.operations.AdapterRejections
import dev.picasso.ops.service.registry.AdapterSource
import dev.picasso.ops.service.registry.AdapterWrites
import dev.picasso.ops.service.registry.RegistryAdapter
import dev.picasso.ops.service.registry.RegistryBuild
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryInstance
import dev.picasso.ops.service.registry.RegistryWrite
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 어댑터 조작이 조작 기록에 남는 모양과 응답 없음 뒤 재조회의 «반영됨» 판정(스펙 §7.1·§9). registry 는 대역이다. */
class AdapterOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val at = Instant.parse("2026-10-07T00:00:00Z")
    private val lee = Actor(Mode.ENGINEER, "lee")

    private var answer: RegistryWrite = RegistryWrite.Answered(201, """{"adapter_id":1}""")
    private var adapters: RegistryCall<List<RegistryAdapter>> = RegistryCall.Ok(emptyList())
    private var instances: RegistryCall<List<RegistryInstance>> = RegistryCall.Ok(emptyList())
    private val sent = mutableListOf<String>()

    private val writes = object : AdapterWrites {
        override fun declareAdapter(vendor: String, name: String, actor: String) =
            answer.also { sent += "declareAdapter $vendor $name $actor" }

        override fun declareBuild(adapterId: Long, version: String, contractSemver: String, actor: String) =
            answer.also { sent += "declareBuild $adapterId $version $contractSemver $actor" }

        override fun registerInstance(siteId: String, instanceId: String, adapterVersionId: Long, fleetEndpoint: String?, actor: String) =
            answer.also { sent += "registerInstance $siteId $instanceId $adapterVersionId $fleetEndpoint $actor" }
    }

    private val reads = object : AdapterSource {
        override fun adapters() = adapters
        override fun instances(siteId: String) = instances
    }

    private val operations =
        AdapterOperations(writes, reads, log, "site-01", Clock.fixed(at, ZoneOffset.UTC), requeryDelay = Duration.ZERO)

    private fun fleet(vararg builds: RegistryBuild) = RegistryAdapter(1, "acme", "fleet", builds.toList())

    private fun instance(version: String, fleetEndpoint: String? = null) = RegistryInstance(
        instanceId = "i1", siteId = "site-01", fleetEndpoint = fleetEndpoint, adapter = "acme/fleet", version = version,
        contractSemver = "0.9.0", conformance = "UNTESTED",
    )

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `제품 선언은 행위자와 함께 보내고 제품 이름을 대상으로 한 행 남긴다`() {
        val outcome = operations.declareAdapter(lee, "acme", "fleet")
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertEquals(listOf("declareAdapter acme fleet engineer/lee"), sent)
        val row = log.list().single()
        assertEquals("adapter acme/fleet", row.target)
        assertTrue(row.request.contains("\"op\": \"DECLARE_ADAPTER\""), row.request)
    }

    @Test
    fun `빌드 선언의 409 는 대응표로 옮기고 거절 행으로 남긴다`() {
        answer = RegistryWrite.Answered(409, """{"error":"같은 버전","existing_contract_semver":"1.0.0"}""")
        val outcome = operations.declareBuild(lee, 1, "1.0.0", "0.9.0")
        assertEquals(listOf("declareBuild 1 1.0.0 0.9.0 engineer/lee"), sent)
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals(AdapterRejections.VERSION_CONFLICT, outcome.rejection!!.kind)
        val row = log.list().single()
        assertEquals("build 1@1.0.0" to OperationResult.REJECTED, row.target to row.result)
    }

    @Test
    fun `인스턴스 등록은 운영 서비스의 사이트로 보낸다`() {
        operations.registerInstance(lee, "i1", 10, null)
        assertEquals(listOf("registerInstance site-01 i1 10 null engineer/lee"), sent)
        assertEquals("instance i1", log.list().single().target)
    }

    @Test
    fun `응답 없는 제품 선언은 그 제품이 목록에 있으면 반영됨이다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        adapters = RegistryCall.Ok(listOf(fleet()))
        val outcome = operations.declareAdapter(lee, "acme", "fleet")
        assertEquals(OperationResult.NO_RESPONSE to OperationResult.CONFIRMED_APPLIED, outcome.result to outcome.confirmation)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertTrue(rows.first().registryResponse!!.contains("\"adapter_id\": 1"), rows.first().registryResponse)
    }

    @Test
    fun `응답 없는 빌드 선언은 같은 버전이 같은 계약값으로 있어야 반영됨이다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        adapters = RegistryCall.Ok(listOf(fleet(RegistryBuild(10, "1.0.0", "1.0.0", "UNTESTED"))))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.declareBuild(lee, 1, "1.0.0", "0.9.0").confirmation)
        adapters = RegistryCall.Ok(listOf(fleet(RegistryBuild(10, "1.0.0", "0.9.0", "UNTESTED"))))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.declareBuild(lee, 1, "1.0.0", "0.9.0").confirmation)
    }

    @Test
    fun `응답 없는 인스턴스 등록은 그 인스턴스가 요청한 빌드와 플릿 주소로 있어야 반영됨이다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        adapters = RegistryCall.Ok(
            listOf(fleet(RegistryBuild(10, "1.0.0", "0.9.0", "UNTESTED"), RegistryBuild(11, "1.1.0", "0.9.0", "UNTESTED"))),
        )
        // 같은 id 로 다시 등록하면 registry 가 덮어쓴다. 옛 빌드나 옛 주소가 그대로면 이 등록은 반영되지 않았다.
        instances = RegistryCall.Ok(listOf(instance("1.0.0")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.registerInstance(lee, "i1", 11, null).confirmation)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.registerInstance(lee, "i1", 10, "tcp://fleet:1").confirmation)
        instances = RegistryCall.Ok(listOf(instance("1.1.0", "tcp://fleet:1")))
        val outcome = operations.registerInstance(lee, "i1", 11, "tcp://fleet:1")
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        val confirmation = log.list().first()
        assertTrue(confirmation.registryResponse!!.contains("\"version\": \"1.1.0\""), confirmation.registryResponse)
    }

    @Test
    fun `인스턴스 재조회에서 제품 목록을 못 읽으면 확인 행을 붙이지 않는다`() {
        answer = RegistryWrite.Answered(503, "")
        adapters = RegistryCall.Silent("응답 없음")
        instances = RegistryCall.Ok(listOf(instance("1.0.0")))
        val outcome = operations.registerInstance(lee, "i1", 10, null)
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertNull(outcome.confirmation)
        assertEquals(listOf(OperationResult.NO_RESPONSE), log.list().map { it.result })
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 확인**

Run: `./gradlew :ops-service:test --tests '*AdapterRejectionsTest' --tests '*AdapterOperationsTest' --console=plain`
Expected: `compileTestKotlin` 실패(`AdapterOp`·`AdapterRejections`·`AdapterOperations` 없음).

- [ ] **Step 3: 구현**

`ops-service/src/main/kotlin/dev/picasso/ops/service/operations/AdapterRejections.kt`:

```kotlin
package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import java.time.Instant

/** 어댑터 조작 3가지(스펙 §3 S1c). 제품 선언, 빌드 선언, 인스턴스 등록이다. */
enum class AdapterOp { DECLARE_ADAPTER, DECLARE_BUILD, REGISTER_INSTANCE }

/**
 * 어댑터 조작의 거절 대응표(스펙 §7.4 의 P1·인스턴스 행). 모두 엔지니어가 화면 안에서 푼다.
 *
 * 409 는 빌드 선언에만 있고 «같은 버전에 다른 계약값» 하나다. 404 도 빌드 선언에만 있고 «없는 제품» 이다.
 * 인스턴스 등록은 없는 빌드 id 도 400 으로 답한다(registry `AdapterInstanceService.register`).
 * 표에 없는 응답은 [Rejections.UNCLASSIFIED] 로 두어 엔지니어가 본다. 아는 종류로 접지 않는다.
 *
 * 바로 갈 링크([Finding.target])는 비운다. 링크는 기체 상세를 열고, 어댑터 목록은 같은 영역에 늘 보인다.
 */
object AdapterRejections {

    const val ADAPTER_BAD_REQUEST = "ADAPTER_BAD_REQUEST"
    const val UNKNOWN_ADAPTER = "UNKNOWN_ADAPTER"
    const val VERSION_CONFLICT = "VERSION_CONFLICT"
    const val INSTANCE_BAD_REQUEST = "INSTANCE_BAD_REQUEST"

    fun of(op: AdapterOp, status: Int, body: JsonNode?, checkedAt: Instant): Finding {
        val error = body?.get("error")?.asText()
        val existing = body?.get("existing_contract_semver")?.asText()

        fun finding(kind: String, action: String, inScreen: Boolean = true) =
            Finding(
                kind = kind,
                observed = listOfNotNull("HTTP $status", existing?.let { "existing_contract_semver=$it" }, error).joinToString(", "),
                expected = "201 또는 200",
                checkedAt = checkedAt,
                owner = Owner.ENGINEER,
                inScreen = inScreen,
                action = action,
                target = null,
            )

        return when {
            op != AdapterOp.REGISTER_INSTANCE && status == 400 -> finding(ADAPTER_BAD_REQUEST, "고쳐서 다시")
            op == AdapterOp.DECLARE_BUILD && status == 404 -> finding(UNKNOWN_ADAPTER, "제품 목록 새로 읽기")
            op == AdapterOp.DECLARE_BUILD && status == 409 -> finding(VERSION_CONFLICT, "다른 버전 번호로")
            op == AdapterOp.REGISTER_INSTANCE && status == 400 -> finding(INSTANCE_BAD_REQUEST, "고쳐서 다시")
            else -> finding(Rejections.UNCLASSIFIED, "registry 응답 조사", inScreen = false)
        }
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/operations/AdapterOperations.kt`:

```kotlin
package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.registry.AdapterSource
import dev.picasso.ops.service.registry.AdapterWrites
import dev.picasso.ops.service.registry.RegistryAdapter
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.map
import java.time.Clock
import java.time.Duration

/**
 * 어댑터 제품 선언, 빌드 선언, 인스턴스 등록(스펙 §3 S1c). 기록과 응답 없음 뒤 재조회는 [OperationRunner] 가 한다.
 *
 * «반영됨» 의 판정(스펙 §9)은 registry 가 같은 요청을 어떻게 다루는지에 맞춘다.
 * - 제품 선언은 이미 있으면 그대로 둔다. 그 제품이 목록에 있으면 반영된 것이다.
 * - 빌드 선언은 같은 버전에 다른 계약값이면 거절한다. 그 버전이 같은 계약값으로 있어야 반영된 것이다.
 * - 인스턴스 등록은 같은 id 면 빌드·사이트·플릿 주소를 덮어쓴다. 그 인스턴스가 이 사이트에, 요청한 빌드와
 *   플릿 주소로 있어야 반영된 것이다. 인스턴스 목록에는 빌드 id 가 없어 제품 목록에서 빌드의 제품 이름과 버전을 찾아 맞댄다.
 */
class AdapterOperations(
    private val writes: AdapterWrites,
    private val reads: AdapterSource,
    log: OperationLog,
    private val siteId: String,
    clock: Clock,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = ObjectMapper(),
) {
    private val runner = OperationRunner(log, clock, requeryDelay, json)

    fun declareAdapter(actor: Actor, vendor: String, name: String): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", AdapterOp.DECLARE_ADAPTER.name)
            .put("vendor", vendor)
            .put("name", name)
        return runner.run(
            actor, "adapter $vendor/$name", request, reason = null,
            rejection = { status, body, at -> AdapterRejections.of(AdapterOp.DECLARE_ADAPTER, status, body, at) },
            recheck = {
                reads.adapters().map { adapters ->
                    val seen = adapters.firstOrNull { it.vendor == vendor && it.name == name }
                    Recheck(seen != null, seen?.let { json.createObjectNode().put("adapter_id", it.adapterId) })
                }
            },
        ) { writes.declareAdapter(vendor, name, actor.header()) }
    }

    fun declareBuild(actor: Actor, adapterId: Long, version: String, contractSemver: String): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", AdapterOp.DECLARE_BUILD.name)
            .put("adapter_id", adapterId)
            .put("version", version)
            .put("contract_semver", contractSemver)
        return runner.run(
            actor, "build $adapterId@$version", request, reason = null,
            rejection = { status, body, at -> AdapterRejections.of(AdapterOp.DECLARE_BUILD, status, body, at) },
            recheck = {
                reads.adapters().map { adapters ->
                    val seen = adapters.firstOrNull { it.adapterId == adapterId }?.versions?.firstOrNull { it.version == version }
                    Recheck(
                        seen?.contractSemver == contractSemver,
                        seen?.let { json.createObjectNode().put("contract_semver", it.contractSemver) },
                    )
                }
            },
        ) { writes.declareBuild(adapterId, version, contractSemver, actor.header()) }
    }

    fun registerInstance(actor: Actor, instanceId: String, adapterVersionId: Long, fleetEndpoint: String?): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", AdapterOp.REGISTER_INSTANCE.name)
            .put("instance_id", instanceId)
            .put("adapter_version_id", adapterVersionId)
            .put("site", siteId)
            .put("fleet_endpoint", fleetEndpoint)
        return runner.run(
            actor, "instance $instanceId", request, reason = null,
            rejection = { status, body, at -> AdapterRejections.of(AdapterOp.REGISTER_INSTANCE, status, body, at) },
            recheck = {
                when (val adapters = reads.adapters()) {
                    is RegistryCall.Ok -> reads.instances(siteId).map { instances ->
                        val build = buildOf(adapters.value, adapterVersionId)
                        val seen = instances.firstOrNull { it.instanceId == instanceId }
                        val applied = seen != null && build != null && seen.adapter == build.first &&
                            seen.version == build.second && seen.fleetEndpoint == fleetEndpoint
                        val observed = seen?.let {
                            json.createObjectNode()
                                .put("adapter", it.adapter)
                                .put("version", it.version)
                                .put("fleet_endpoint", it.fleetEndpoint)
                        }
                        Recheck(applied, observed)
                    }
                    is RegistryCall.Silent -> adapters
                    RegistryCall.Unauthorized -> RegistryCall.Unauthorized
                }
            },
        ) { writes.registerInstance(siteId, instanceId, adapterVersionId, fleetEndpoint, actor.header()) }
    }

    /** 빌드 id 의 제품 이름(`vendor/name`)과 버전. 인스턴스 목록이 쓰는 꼴이다. */
    private fun buildOf(adapters: List<RegistryAdapter>, adapterVersionId: Long): Pair<String, String>? =
        adapters.firstNotNullOfOrNull { adapter ->
            adapter.versions.firstOrNull { it.adapterVersionId == adapterVersionId }?.let { "${adapter.vendor}/${adapter.name}" to it.version }
        }
}
```

- [ ] **Step 4: 시험이 통과하는지 확인**

Run: `./gradlew :ops-service:test --console=plain`
Expected: XML 에서 `AdapterRejectionsTest` 3개, `AdapterOperationsTest` 7개, ops-service 모듈 전체 68개, 실패 0.

- [ ] **Step 5: 결함 주입 5건(하나씩)**

① `AdapterRejections.of` 의 `op == AdapterOp.DECLARE_BUILD && status == 409 ->` 를 `status == 409 ->` 로 바꾼다(409 를 조작으로 가르지 않는다). Expected 실패 이름: `표에 없는 응답은 아는 종류로 접지 않고 화면 밖 조사로 둔다()`.
② `AdapterRejections.of` 의 `"registry 응답 조사", inScreen = false)` 를 `"registry 응답 조사")` 로 바꾼다(표에 없는 응답을 화면 안으로 둔다). Expected 실패 이름: `표에 없는 응답은 아는 종류로 접지 않고 화면 밖 조사로 둔다()`.
③ `AdapterOperations.registerInstance` 의 `seen.version == build.second && seen.fleetEndpoint == fleetEndpoint` 를 `seen.version == build.second` 로 바꾼다(덮어쓰기를 안 본다). Expected 실패 이름: `응답 없는 인스턴스 등록은 그 인스턴스가 요청한 빌드와 플릿 주소로 있어야 반영됨이다()`.
④ `AdapterOperations.declareBuild` 의 `seen?.contractSemver == contractSemver,` 를 `seen != null,` 로 바꾼다(계약값을 안 본다). Expected 실패 이름: `응답 없는 빌드 선언은 같은 버전이 같은 계약값으로 있어야 반영됨이다()`.
⑤ `AdapterOperations.registerInstance` 의 `is RegistryCall.Silent -> adapters` 를 `is RegistryCall.Silent -> reads.instances(siteId).map { Recheck(false, null) }` 로 바꾼다(제품 목록을 못 읽어도 판정한다). Expected 실패 이름: `인스턴스 재조회에서 제품 목록을 못 읽으면 확인 행을 붙이지 않는다()`.
각각 `./gradlew :ops-service:test --rerun --console=plain` 으로 돌리고, 되돌리고 초록을 본다.

- [ ] **Step 6: 임시 커밋**

```bash
git add ops-service/src/main/kotlin/dev/picasso/ops/service/operations/AdapterRejections.kt ops-service/src/main/kotlin/dev/picasso/ops/service/operations/AdapterOperations.kt ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterRejectionsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterOperationsTest.kt
git commit -F - <<'EOF'
chore(s1c): 어댑터 거절 대응표와 조작 서비스

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 4: 어댑터 목록, 조작 API, 배선

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/adapters/AdapterListService.kt`
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt`
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/web/AdapterOperationsController.kt`
- Modify(전체): `ops-service/src/main/kotlin/dev/picasso/ops/service/web/RobotOperationsController.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/web/ApiController.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt`
- Test: `ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterListServiceTest.kt`

목록 서비스는 머리말 결정 6 대로 두 읽기를 한 시각으로 묶는다. S1b 의 `RobotOperationsController` 안에 있던 관문(`guarded`, `reject`, `PreRejection`)을 `Guard.kt` 로 옮겨 어댑터 API 와 같이 쓴다(머리말 결정 2). 관문을 옮기므로 S1b 의 통합 시험도 다시 돌린다.

- [ ] **Step 1: 실패하는 시험 쓰기**

`ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterListServiceTest.kt`:

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.adapters.AdapterListService
import dev.picasso.ops.service.registry.AdapterSource
import dev.picasso.ops.service.registry.RegistryAdapter
import dev.picasso.ops.service.registry.RegistryBuild
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryInstance
import dev.picasso.ops.service.robots.RegistryState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 어댑터 목록은 제품·빌드와 인스턴스를 한 시각으로 읽고, registry 가 답하지 않으면 직전 값을 지킨다(스펙 §9). */
class AdapterListServiceTest {

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val t1 = Instant.parse("2026-10-07T00:00:00Z")
    private val t2 = Instant.parse("2026-10-07T00:00:05Z")
    private val clock = MovableClock(t1)
    private val build = RegistryBuild(10, "1.0.0", "0.9.0", "UNTESTED")
    private val adapter = RegistryAdapter(1, "acme", "fleet", listOf(build))
    private val instance = RegistryInstance(
        instanceId = "i1", siteId = "site-01", adapter = "acme/fleet", version = "1.0.0", contractSemver = "0.9.0",
        conformance = "UNTESTED",
    )
    private var adapters: RegistryCall<List<RegistryAdapter>> = RegistryCall.Ok(listOf(adapter))
    private var instances: RegistryCall<List<RegistryInstance>> = RegistryCall.Ok(listOf(instance))
    private var askedSite: String? = null
    private val service = AdapterListService(
        object : AdapterSource {
            override fun adapters() = adapters
            override fun instances(siteId: String) = instances.also { askedSite = siteId }
        },
        "site-01",
        clock,
    )

    @Test
    fun `둘 다 읽히면 제품·빌드와 이 사이트의 인스턴스를 같은 시각으로 낸다`() {
        val view = service.read()
        assertEquals(RegistryState.OK, view.registry)
        assertEquals(listOf(adapter), view.adapters)
        assertEquals(listOf(instance), view.instances)
        assertEquals(t1 to t1, view.checkedAt to view.asOf)
        assertEquals("site-01", askedSite)
    }

    @Test
    fun `한 번도 못 읽었으면 없음이 아니라 모름이다`() {
        adapters = RegistryCall.Silent("응답 없음")
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT, view.registry)
        assertNull(view.adapters)
        assertNull(view.instances)
        assertNull(view.asOf)
    }

    @Test
    fun `인스턴스만 못 읽어도 둘 다 직전 값을 지킨다`() {
        service.read()
        clock.now = t2
        adapters = RegistryCall.Ok(emptyList())
        instances = RegistryCall.Silent("응답 없음")
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT, view.registry)
        assertEquals(listOf(adapter), view.adapters)
        assertEquals(listOf(instance), view.instances)
        assertEquals(t2 to t1, view.checkedAt to view.asOf)
    }

    @Test
    fun `제품·빌드 읽기가 401 이면 토큰 불일치이고 직전 값을 지킨다`() {
        service.read()
        clock.now = t2
        adapters = RegistryCall.Unauthorized
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_UNAUTHORIZED, view.registry)
        assertEquals(listOf(adapter), view.adapters)
        assertEquals(t1, view.asOf)
    }

    @Test
    fun `늦게 끝난 옛 읽기는 더 새 목록을 덮지 않는다`() {
        clock.now = t2
        service.read()
        clock.now = t1
        adapters = RegistryCall.Ok(emptyList())
        val view = service.read()
        assertEquals(listOf(adapter), view.adapters)
        assertEquals(t2 to t2, view.checkedAt to view.asOf)
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 확인**

Run: `./gradlew :ops-service:test --tests '*AdapterListServiceTest' --console=plain`
Expected: `compileTestKotlin` 실패(`AdapterListService` 없음).

- [ ] **Step 3: 구현**

`ops-service/src/main/kotlin/dev/picasso/ops/service/adapters/AdapterListService.kt`:

```kotlin
package dev.picasso.ops.service.adapters

import dev.picasso.ops.service.registry.AdapterSource
import dev.picasso.ops.service.registry.RegistryAdapter
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryInstance
import dev.picasso.ops.service.robots.RegistryState
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * `GET /api/adapters` 의 답. 제품·빌드와 인스턴스를 한 번에 읽어 같은 시각으로 보인다.
 *
 * @param adapters 널이면 «모름»(한 번도 읽지 못했다). 빈 목록은 «없음». 둘을 접지 않는다(스펙 §9).
 * @param asOf [adapters]·[instances] 를 registry 에서 읽은 시각. [checkedAt] 과 다르면 직전 값이다.
 */
data class AdapterListView(
    val registry: RegistryState,
    val checkedAt: Instant,
    val adapters: List<RegistryAdapter>?,
    val instances: List<RegistryInstance>?,
    val asOf: Instant?,
)

/**
 * 어댑터 제품·빌드(`GET /operations/adapters`)와 이 사이트의 인스턴스(`GET /diag/adapter-instances`) 목록.
 * 둘 다 읽혀야 새 값으로 바꾼다. 하나라도 읽지 못하면 둘 다 직전 값을 보인다. 인스턴스는 빌드를 제품 이름과
 * 버전으로만 가리키므로, 다른 시각에 읽은 둘을 섞어 보이면 화면이 서로 맞지 않는 목록을 보일 수 있다.
 *
 * 인스턴스를 먼저, 제품·빌드를 나중에 읽는다. 제품과 빌드는 지워지지 않으므로, 이 순서면 보이는 인스턴스의 빌드가
 * 제품·빌드 목록에 늘 있다. 거꾸로 읽으면 그 사이에 등록된 인스턴스가 빌드 없이 보일 수 있다.
 *
 * 제품·빌드 읽기는 운영자 토큰 관문 안이라 토큰이 틀리면 [RegistryState.REGISTRY_UNAUTHORIZED] 다.
 */
class AdapterListService(
    private val source: AdapterSource,
    private val siteId: String,
    private val clock: Clock,
) {
    private data class Known(val adapters: List<RegistryAdapter>, val instances: List<RegistryInstance>, val at: Instant)

    private val last = AtomicReference<Known?>(null)

    fun read(): AdapterListView {
        val now = clock.instant()
        val instances = source.instances(siteId)
        val adapters = if (instances is RegistryCall.Ok) source.adapters() else null
        if (adapters is RegistryCall.Ok && instances is RegistryCall.Ok) {
            // 늦게 끝난 옛 읽기가 더 새 값을 덮지 않게 한다. 폴링과 조작 뒤 다시 읽기가 겹칠 수 있다.
            val known = last.updateAndGet { prev ->
                if (prev != null && prev.at.isAfter(now)) prev else Known(adapters.value, instances.value, now)
            }!!
            return view(RegistryState.OK, maxOf(now, known.at), known)
        }
        val state = if (adapters == RegistryCall.Unauthorized || instances == RegistryCall.Unauthorized) {
            RegistryState.REGISTRY_UNAUTHORIZED
        } else {
            RegistryState.REGISTRY_SILENT
        }
        return view(state, now, last.get())
    }

    private fun view(state: RegistryState, now: Instant, known: Known?): AdapterListView =
        AdapterListView(state, now, known?.adapters, known?.instances, known?.at)
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt`:

```kotlin
package dev.picasso.ops.service.web

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/** registry 에 보내기 전에 막은 요청의 답. 조작 기록에 남기지 않는다. registry 에 닿지 않은 요청은 조작이 아니다. */
data class PreRejection(val error: String, val detail: String)

/**
 * 조작 API 의 관문. 행위자 헤더가 없거나 틀리면 400, 모드가 맞지 않으면 403 으로 registry 를 부르기 전에 막는다(스펙 §9).
 * 기체 조작과 어댑터 조작이 같이 쓴다. 교차 출처 방어에서 이 헤더가 맡는 몫은 [RobotOperationsController] 에 적었다.
 */
internal inline fun guarded(
    mode: String?,
    user: String?,
    required: Mode,
    action: (Actor) -> ResponseEntity<Any>,
): ResponseEntity<Any> {
    val actor = Actor.fromHeaders(mode, user)
        ?: return reject(HttpStatus.BAD_REQUEST, "ACTOR_REQUIRED", "${Actor.MODE_HEADER}·${Actor.USER_HEADER} 헤더가 없거나 틀리다")
    if (actor.mode != required) {
        return reject(HttpStatus.FORBIDDEN, "MODE_NOT_ALLOWED", "이 조작은 ${required.wire} 모드에서 한다")
    }
    return action(actor)
}

internal fun reject(status: HttpStatus, error: String, detail: String): ResponseEntity<Any> =
    ResponseEntity.status(status).body(PreRejection(error, detail))

/** 본문에 실려 온 사이트가 운영 서비스의 사이트와 다르면 막는다. 화면은 사이트를 싣지 않는다(스펙 §9). */
internal fun siteMismatch(site: String?, siteId: SiteId): ResponseEntity<Any>? =
    if (site != null && site != siteId.value) {
        reject(HttpStatus.BAD_REQUEST, "SITE_MISMATCH", "사이트가 ${siteId.value} 가 아니다: $site")
    } else {
        null
    }
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/web/RobotOperationsController.kt`(전체. 관문을 `Guard.kt` 로 옮긴 것만 다르다):

```kotlin
package dev.picasso.ops.service.web

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.operations.OperationOutcome
import dev.picasso.ops.service.operations.RobotOperations
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** `POST /api/robots` 의 본문. [site] 는 화면이 싣지 않는다. 실려 오면 운영 서비스의 사이트와 대조한다(스펙 §9). */
data class DeclareBody(
    val robotId: String = "",
    val serialNumber: String = "",
    val displayName: String? = null,
    val site: String? = null,
)

data class RetireBody(val reason: String = "")

/**
 * 기체 조작 API(스펙 §7.2). 등록은 엔지니어 모드, 퇴역·복귀는 운영자 모드에서 한다(스펙 §8).
 *
 * registry 를 부르기 전에 3가지를 막는다([guarded]). 행위자 헤더가 없거나 틀리면 400, 모드가 맞지 않으면 403, 사이트가
 * 운영 서비스의 `SITE_ID` 와 다르면 400 이다. 쓰기 본문은 `application/json` 만 받는다. 이 저장소에 인증은 없으며,
 * 커스텀 헤더와 JSON 본문 요구가 다른 출처의 페이지가 이 API 를 부르지 못하게 하는 유일한 방어다(브라우저가
 * 사전 요청을 보내고, 스프링은 CORS 설정이 없으면 그것을 거절한다). 폼이나 `text/plain` 을 받는 쓰기를 더하면 이
 * 방어가 사라진다. DELETE 는 본문이 없어 커스텀 헤더만으로 막히고, GET 은 계속 부작용이 없어야 한다.
 * `server.address=127.0.0.1` 은 다른 기계를 막지만 DNS 재바인딩은 막지 못한다(Host 검사가 없다). 인증을 생략한
 * PoC 의 한계다.
 *
 * 사이트 대조는 선언에만 있다. 퇴역·복귀는 기체 id 로만 부르며, registry 는 사이트를 대조하지 않는다(스펙 §6 ④).
 * 본문이 없거나 JSON 으로 읽히지 않는 요청은 스프링이 400 으로 막는다. 이것도 registry 에 닿지 않아 기록되지 않는다.
 *
 * registry 가 답한 결과(거절 포함)는 200 과 [OperationOutcome] 으로 돌려준다. 이 API 의 호출은 성공했고,
 * registry 의 판단은 본문에 있다.
 */
@RestController
@RequestMapping("/api/robots")
class RobotOperationsController(
    private val operations: RobotOperations,
    private val siteId: SiteId,
) {

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun declare(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: DeclareBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        siteMismatch(body.site, siteId)
            ?: ResponseEntity.ok(operations.declare(actor, body.robotId, body.serialNumber, body.displayName))
    }

    @PostMapping("/{robotId}/retirement", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun retire(
        @PathVariable robotId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: RetireBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        ResponseEntity.ok(operations.retire(actor, robotId, body.reason))
    }

    @DeleteMapping("/{robotId}/retirement")
    fun reinstate(
        @PathVariable robotId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        ResponseEntity.ok(operations.reinstate(actor, robotId))
    }
}

/** 운영 서비스의 사이트 id(`.env` 의 `SITE_ID`). 기체 선언, 어댑터 인스턴스, 목록이 이 값 하나를 쓴다(스펙 §6 ④). */
data class SiteId(val value: String)
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/web/AdapterOperationsController.kt`:

```kotlin
package dev.picasso.ops.service.web

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.operations.AdapterOperations
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

data class DeclareAdapterBody(val vendor: String = "", val name: String = "")

data class DeclareBuildBody(val version: String = "", val contractSemver: String = "")

/**
 * [site] 는 화면이 싣지 않는다. 실려 오면 운영 서비스의 사이트와 대조한다(스펙 §9).
 *
 * [adapterVersionId] 를 널로 받는 이유: 널이 안 되는 `Long` 으로 두면 본문에 칸이 없을 때 Jackson 이 0 으로 읽어
 * 그대로 registry 에 간다. 빈 칸은 운영 서비스가 먼저 막는다.
 */
data class RegisterInstanceBody(
    val instanceId: String = "",
    val adapterVersionId: Long? = null,
    val fleetEndpoint: String? = null,
    val site: String? = null,
)

/**
 * 어댑터 조작 API(스펙 §3 S1c). 셋 다 엔지니어 모드에서 한다(스펙 §8). 관문과 교차 출처 방어, 사전 거절을 조작 기록에
 * 남기지 않는 것, registry 의 답을 200 과 결과 본문으로 돌려주는 것은 [RobotOperationsController] 와 같다.
 * 사이트 대조는 인스턴스 등록에만 있다. 제품과 빌드는 사이트에 매이지 않는다.
 */
@RestController
class AdapterOperationsController(
    private val operations: AdapterOperations,
    private val siteId: SiteId,
) {

    @PostMapping("/api/adapters", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun declareAdapter(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: DeclareAdapterBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        ResponseEntity.ok(operations.declareAdapter(actor, body.vendor, body.name))
    }

    @PostMapping("/api/adapters/{adapterId}/versions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun declareBuild(
        @PathVariable adapterId: Long,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: DeclareBuildBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        ResponseEntity.ok(operations.declareBuild(actor, adapterId, body.version, body.contractSemver))
    }

    @PostMapping("/api/adapter-instances", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun registerInstance(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: RegisterInstanceBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        val build = body.adapterVersionId
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, "BUILD_REQUIRED", "adapterVersionId 가 없다")
        siteMismatch(body.site, siteId)
            ?: ResponseEntity.ok(operations.registerInstance(actor, body.instanceId, build, body.fleetEndpoint))
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/web/ApiController.kt`(전체):

```kotlin
package dev.picasso.ops.service.web

import dev.picasso.ops.service.adapters.AdapterListService
import dev.picasso.ops.service.adapters.AdapterListView
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationRecord
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.robots.RobotListView
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 화면이 부르는 읽기 API. 화면은 이 서비스만 부른다(스펙 §4). 읽기는 부작용이 없다. */
@RestController
@RequestMapping("/api")
class ApiController(
    private val robots: RobotListService,
    private val adapters: AdapterListService,
    private val operations: OperationLog,
) {
    @GetMapping("/robots")
    fun robots(): RobotListView = robots.read()

    @GetMapping("/adapters")
    fun adapters(): AdapterListView = adapters.read()

    @GetMapping("/operations")
    fun operations(): List<OperationRecord> = operations.list()
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt`(전체):

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.adapters.AdapterListService
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.operations.AdapterOperations
import dev.picasso.ops.service.operations.RobotOperations
import dev.picasso.ops.service.registry.RegistryClient
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.store.OpsSchemaMigrated
import dev.picasso.ops.service.web.SiteId
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration
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
    open fun siteId(@Value("\${ops.site-id}") value: String): SiteId {
        require(value.isNotBlank()) { "ops.site-id(SITE_ID)가 비었다" }
        return SiteId(value)
    }

    /** 주소 형식은 [RegistryClient.checkBaseUrl] 이 기동에서 본다. */
    @Bean
    open fun registryClient(
        @Value("\${ops.registry.url}") url: String,
        @Value("\${ops.registry.operator-token}") token: String,
    ): RegistryClient {
        // registry 는 빈 토큰을 전부 401 로 다룬다. 설정 실수를 401 이 아니라 기동에서 드러낸다.
        require(token.isNotBlank()) { "운영자 토큰(ops.registry.operator-token)이 비었다" }
        return RegistryClient(url, token)
    }

    /** [threshold] 는 연결 칸의 기준 시간이다(스펙 §7.3). S2 에서 데이터로 옮긴다. */
    @Bean
    open fun robotList(
        registry: RegistryClient,
        siteId: SiteId,
        clock: Clock,
        @Value("\${ops.connection.threshold}") threshold: Duration,
    ): RobotListService = RobotListService(registry, registry, siteId.value, clock, threshold)

    @Bean
    open fun adapterList(registry: RegistryClient, siteId: SiteId, clock: Clock): AdapterListService =
        AdapterListService(registry, siteId.value, clock)

    @Bean
    open fun robotOperations(
        registry: RegistryClient,
        log: OperationLog,
        siteId: SiteId,
        clock: Clock,
    ): RobotOperations = RobotOperations(registry, registry, log, siteId.value, clock)

    @Bean
    open fun adapterOperations(
        registry: RegistryClient,
        log: OperationLog,
        siteId: SiteId,
        clock: Clock,
    ): AdapterOperations = AdapterOperations(registry, registry, log, siteId.value, clock)

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

- [ ] **Step 4: 시험이 통과하는지 확인**

Run: `./gradlew :ops-service:test :e2e:test --console=plain` 그리고 `./gradlew :ops-service:checkNoPicassoOnMain --console=plain`
Expected: XML 에서 `AdapterListServiceTest` 5개, ops-service 모듈 전체 73개(Actor 4, AdapterListService 5, AdapterOperations 7, AdapterRejections 3, Blockers 7, EnvBoundary 1, OperationLog 7, RegistryAdapters 3, RegistryClient 6, RegistryWrites 6, Rejections 3, RobotListService 9, RobotOperations 12), e2e 13개(`LifecycleTest` 10, `SkeletonTest` 3), 실패 0. 검사 성공. 어댑터 API 는 Task 5 의 e2e 가 본다.

- [ ] **Step 5: 결함 주입 3건(하나씩)**

① `AdapterListService.read` 의 `return view(state, now, last.get())` 를 `return view(state, now, null)` 로 바꾼다(직전 값을 버린다). Expected 실패 이름: `인스턴스만 못 읽어도 둘 다 직전 값을 지킨다()`, `제품·빌드 읽기가 401 이면 토큰 불일치이고 직전 값을 지킨다()`.
② `adapters == RegistryCall.Unauthorized || instances == RegistryCall.Unauthorized` 를 `false` 로 바꾼다(토큰 불일치를 침묵으로 접는다). Expected 실패 이름: `제품·빌드 읽기가 401 이면 토큰 불일치이고 직전 값을 지킨다()`.
③ `if (prev != null && prev.at.isAfter(now)) prev else` 를 `if (false) prev else` 로 바꾼다. Expected 실패 이름: `늦게 끝난 옛 읽기는 더 새 목록을 덮지 않는다()`.
각각 `./gradlew :ops-service:test --rerun --console=plain` 으로 돌리고, 되돌리고 초록을 본다.

- [ ] **Step 6: 임시 커밋**

```bash
git add ops-service/src/main/kotlin/dev/picasso/ops/service/adapters/AdapterListService.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/Guard.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/AdapterOperationsController.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/RobotOperationsController.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/ApiController.kt ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterListServiceTest.kt
git commit -F - <<'EOF'
chore(s1c): 어댑터 목록과 조작 API

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

---

## Chunk 3: 통합 시험

### Task 5: 통합 시험(S1c 완료 판정)

**Files:**
- Test: `e2e/src/test/kotlin/dev/picasso/ops/e2e/AdapterTest.kt`

S1b 의 `E2eStack` 이 스택을 띄운다. 시험 순서가 있다. 제품 선언(201, 다시 선언하면 200) → 빌드 선언 → 인스턴스 등록 → 목록에 `UNTESTED` → 거절 5가지가 대응표대로 → 사전 거절은 기록되지 않음 → 조작 기록과 registry 감사 기록의 행위자 → 틀린 토큰의 운영 서비스. 감사 기록에는 새로 만든 것만 남는다(registry 는 같은 제품의 두 번째 선언에 감사 기록을 더하지 않는다).

- [ ] **Step 1: 시험 쓰기**

`e2e/src/test/kotlin/dev/picasso/ops/e2e/AdapterTest.kt`:

```kotlin
package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.registry.PostgresSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S1c 완료 판정(스펙 §3). 운영 서비스 API 로 제품 선언 → 빌드 선언 → 인스턴스 등록 → 목록에 `UNTESTED` 표시,
 * 그리고 P1·인스턴스 거절(400/404/409)이 대응표(스펙 §7.4)대로 보이는 것. 순서가 있다. 앞 시험이 만든 제품과
 * 빌드를 뒤 시험이 쓴다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AdapterTest {

    companion object {
        private const val INSTANCE = "fleet-gw-01"
        private lateinit var stack: E2eStack
        private var adapterId = 0L
        private var buildId = 0L

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

    private fun engineer(path: String, body: String) = stack.send("POST", path, "engineer", body = body)

    private fun outcome(path: String, body: String): JsonNode {
        val reply = engineer(path, body)
        assertEquals(200, reply.status, "$path ${reply.body}")
        return reply.body!!
    }

    private fun rejection(path: String, body: String, status: Int): JsonNode {
        val outcome = outcome(path, body)
        assertEquals("REJECTED" to status, outcome["result"].asText() to outcome["registryStatus"].asInt(), "$outcome")
        return outcome["rejection"]
    }

    private fun adapters(): JsonNode = stack.get("/api/adapters")

    @Test
    @Order(1)
    fun `엔지니어가 제품을 선언하면 목록에 보이고 다시 선언해도 같은 제품이다`() {
        val first = outcome("/api/adapters", """{"vendor":"acme","name":"fleet"}""")
        assertEquals("SUCCEEDED" to 201, first["result"].asText() to first["registryStatus"].asInt())
        val again = outcome("/api/adapters", """{"vendor":"acme","name":"fleet"}""")
        assertEquals("SUCCEEDED" to 200, again["result"].asText() to again["registryStatus"].asInt())

        val listed = adapters()["adapters"].single()
        assertEquals("acme/fleet", "${listed["vendor"].asText()}/${listed["name"].asText()}")
        adapterId = listed["adapterId"].asLong()
    }

    @Test
    @Order(2)
    fun `빌드를 선언하면 UNTESTED 로 보인다`() {
        val declared = outcome("/api/adapters/$adapterId/versions", """{"version":"1.0.0","contractSemver":"0.9.0"}""")
        assertEquals("SUCCEEDED" to 201, declared["result"].asText() to declared["registryStatus"].asInt())

        val build = adapters()["adapters"].single()["versions"].single()
        assertEquals(listOf("1.0.0", "0.9.0", "UNTESTED"), listOf("version", "contractSemver", "conformance").map { build[it].asText() })
        assertEquals("engineer/kim", build["registeredBy"].asText())
        buildId = build["adapterVersionId"].asLong()
    }

    @Test
    @Order(3)
    fun `인스턴스를 등록하면 이 사이트의 인스턴스 목록에 UNTESTED 로 보인다`() {
        val registered = outcome("/api/adapter-instances", """{"instanceId":"$INSTANCE","adapterVersionId":$buildId}""")
        assertEquals("SUCCEEDED" to 201, registered["result"].asText() to registered["registryStatus"].asInt())

        val view = adapters()
        assertEquals("OK", view["registry"].asText())
        val instance = view["instances"].single()
        assertEquals(
            listOf(INSTANCE, stack.siteId, "acme/fleet", "1.0.0", "UNTESTED"),
            listOf("instanceId", "siteId", "adapter", "version", "conformance").map { instance[it].asText() },
        )
    }

    @Test
    @Order(4)
    fun `P1 과 인스턴스의 거절은 대응표대로 엔지니어가 화면 안에서 풀 종류로 보인다`() {
        val blank = rejection("/api/adapters", """{"vendor":"","name":"fleet"}""", 400)
        val semver = rejection("/api/adapters/$adapterId/versions", """{"version":"1.1.0","contractSemver":"not-semver"}""", 400)
        val unknown = rejection("/api/adapters/999999/versions", """{"version":"1.0.0","contractSemver":"0.9.0"}""", 404)
        val conflict = rejection("/api/adapters/$adapterId/versions", """{"version":"1.0.0","contractSemver":"1.0.0"}""", 409)
        val instance = rejection("/api/adapter-instances", """{"instanceId":"bad-01","adapterVersionId":999999}""", 400)

        assertEquals(
            listOf("ADAPTER_BAD_REQUEST", "ADAPTER_BAD_REQUEST", "UNKNOWN_ADAPTER", "VERSION_CONFLICT", "INSTANCE_BAD_REQUEST"),
            listOf(blank, semver, unknown, conflict, instance).map { it["kind"].asText() },
        )
        listOf(blank, semver, unknown, conflict, instance).forEach {
            assertEquals("ENGINEER" to true, it["owner"].asText() to it["inScreen"].asBoolean(), "$it")
        }
        // 409 는 이미 있는 계약값을 관측값에 싣는다. 다른 버전 번호로 다시 하라는 근거다.
        assertTrue(conflict["observed"].asText().contains("existing_contract_semver=0.9.0"), "$conflict")
        assertEquals(1, adapters()["instances"].size())
    }

    @Test
    @Order(5)
    fun `registry 에 보내기 전에 막는 요청은 조작 기록에 남지 않는다`() {
        val before = stack.get("/api/operations").size()
        val instance = """{"instanceId":"pre-01","adapterVersionId":$buildId}"""

        assertEquals(403, stack.send("POST", "/api/adapters", "operator", body = """{"vendor":"acme","name":"pre"}""").status)
        assertEquals(403, stack.send("POST", "/api/adapters/$adapterId/versions", "operator", body = """{"version":"9.0.0","contractSemver":"0.9.0"}""").status)
        assertEquals(403, stack.send("POST", "/api/adapter-instances", "operator", body = instance).status)
        assertEquals(400, stack.send("POST", "/api/adapter-instances", mode = null, body = instance).status)
        val mismatch = engineer("/api/adapter-instances", """{"instanceId":"pre-01","adapterVersionId":$buildId,"site":"other-site"}""")
        assertEquals(400 to "SITE_MISMATCH", mismatch.status to mismatch.body!!["error"].asText())
        // 빌드 id 가 없는 본문. Jackson 이 0 으로 읽어 registry 에 보내지 않게 운영 서비스가 막는다.
        val noBuild = engineer("/api/adapter-instances", """{"instanceId":"pre-01"}""")
        assertEquals(400 to "BUILD_REQUIRED", noBuild.status to noBuild.body!!["error"].asText())
        assertEquals(415, stack.send("POST", "/api/adapters", "engineer", body = """{"vendor":"acme","name":"pre"}""", contentType = "text/plain").status)

        assertEquals(before, stack.get("/api/operations").size())
        assertEquals(listOf(INSTANCE), adapters()["instances"].map { it["instanceId"].asText() })
    }

    @Test
    @Order(6)
    fun `조작 기록에 어댑터 조작이 대상과 결과와 함께 남고 registry 도 같은 행위자를 적었다`() {
        val records = stack.get("/api/operations").map { listOf(it["target"].asText(), it["mode"].asText(), it["result"].asText()) }
        assertEquals(
            listOf(
                listOf("adapter acme/fleet", "ENGINEER", "SUCCEEDED"),
                listOf("adapter acme/fleet", "ENGINEER", "SUCCEEDED"),
                listOf("build $adapterId@1.0.0", "ENGINEER", "SUCCEEDED"),
                listOf("instance $INSTANCE", "ENGINEER", "SUCCEEDED"),
                listOf("adapter /fleet", "ENGINEER", "REJECTED"),
                listOf("build $adapterId@1.1.0", "ENGINEER", "REJECTED"),
                listOf("build 999999@1.0.0", "ENGINEER", "REJECTED"),
                listOf("build $adapterId@1.0.0", "ENGINEER", "REJECTED"),
                listOf("instance bad-01", "ENGINEER", "REJECTED"),
            ),
            records.reversed(),
        )
        // 같은 제품의 두 번째 선언은 registry 가 감사 기록을 더하지 않는다. 거절도 감사 기록에 없다.
        val audit = PostgresSupport.queryAll(
            "SELECT operation, subject, actor FROM audit_log WHERE operation LIKE 'ADAPTER%' ORDER BY audit_id",
        ) { listOf(it.getString(1), it.getString(2), it.getString(3)) }
        assertEquals(
            listOf(
                listOf("ADAPTER_REGISTER", "acme/fleet", "engineer/kim"),
                listOf("ADAPTER_VERSION_REGISTER", "$adapterId@1.0.0", "engineer/kim"),
                listOf("ADAPTER_INSTANCE_REGISTERED", INSTANCE, "engineer/kim"),
            ),
            audit,
        )
    }

    @Test
    @Order(7)
    fun `운영자 토큰이 틀린 운영 서비스는 어댑터 목록을 모름과 토큰 불일치로 보인다`() {
        val (context, url) = stack.opsWithToken("wrong-token")
        context.use {
            val view = E2eStack.read("$url/api/adapters")
            assertEquals("REGISTRY_UNAUTHORIZED", view["registry"].asText())
            // 둘 다 읽혀야 새 값으로 바꾸므로 관문 밖의 인스턴스 목록도 모름이다.
            assertTrue(view["adapters"].isNull && view["instances"].isNull, "$view")
        }
    }
}
```

- [ ] **Step 2: 시험이 통과하는지 확인**

Run: `./gradlew :e2e:test --console=plain`
Expected: XML 에서 `AdapterTest` 7개, `LifecycleTest` 10개, `SkeletonTest` 3개, 실패 0. 운영 서비스 코드는 Task 4 까지 이미 있으므로 처음부터 초록이다. 시험이 실제로 무엇을 보는지는 Step 3 의 주입이 확인한다.

- [ ] **Step 3: 결함 주입 5건(하나씩)**

① `AdapterOperationsController.registerInstance` 의 `?: return@guarded reject(HttpStatus.BAD_REQUEST, "BUILD_REQUIRED", "adapterVersionId 가 없다")` 를 `?: 0L` 로 바꾼다(빈 빌드 id 를 0 으로 보낸다). Expected 실패 이름: `AdapterTest` 의 `registry 에 보내기 전에 막는 요청은 조작 기록에 남지 않는다()`, `조작 기록에 어댑터 조작이 대상과 결과와 함께 남고 registry 도 같은 행위자를 적었다()`. 뒤의 것은 빌드 id 0 으로 registry 에 간 요청의 거절 행이 조작 기록에 남아서다.
② `AdapterOperationsController` 의 `siteMismatch(body.site, siteId)` 를 `siteMismatch(null, siteId)` 로 바꾼다(인스턴스의 사이트를 대조하지 않는다). Expected 실패 이름: `AdapterTest` 의 `registry 에 보내기 전에 막는 요청은 조작 기록에 남지 않는다()`, `조작 기록에 어댑터 조작이 대상과 결과와 함께 남고 registry 도 같은 행위자를 적었다()`. 다른 사이트를 실은 등록이 이 사이트로 들어가 조작 기록에 남는다.
③ `AdapterRejections.of` 의 `finding(VERSION_CONFLICT, "다른 버전 번호로")` 를 `finding(Rejections.UNCLASSIFIED, "다른 버전 번호로")` 로 바꾼다. Expected 실패 이름: ops-service 의 `빌드 선언의 409 는 대응표로 옮기고 거절 행으로 남긴다()`, `대응표의 행마다 종류와 후속 행동이 맞고 엔지니어가 화면 안에서 푼다()`, e2e 의 `P1 과 인스턴스의 거절은 대응표대로 엔지니어가 화면 안에서 풀 종류로 보인다()`.
④ `AdapterListService.read` 의 `source.instances(siteId)` 를 `source.instances("other-site")` 로 바꾼다(다른 사이트의 인스턴스를 읽는다). Expected 실패 이름: ops-service 의 `둘 다 읽히면 제품·빌드와 이 사이트의 인스턴스를 같은 시각으로 낸다()`, e2e 의 `인스턴스를 등록하면 이 사이트의 인스턴스 목록에 UNTESTED 로 보인다()`, `P1 과 인스턴스의 거절은 대응표대로 엔지니어가 화면 안에서 풀 종류로 보인다()`, `registry 에 보내기 전에 막는 요청은 조작 기록에 남지 않는다()`.
⑤ `Guard.kt` 의 `if (actor.mode != required) {` 를 `if (false) {` 로 바꾼다(모드를 집행하지 않는다). Expected 실패 이름: `AdapterTest` 의 `registry 에 보내기 전에 막는 요청은 조작 기록에 남지 않는다()`, `조작 기록에 어댑터 조작이 대상과 결과와 함께 남고 registry 도 같은 행위자를 적었다()`, `LifecycleTest` 의 `registry 에 보내기 전에 막는 요청은 조작 기록에 남지 않는다()`. 옮긴 관문을 기체 조작과 어댑터 조작의 통합 시험이 같이 지키는지 보는 주입이다.
각각 `./gradlew :ops-service:test --rerun :e2e:test --rerun --continue --console=plain` 으로 돌리고, 되돌리고 초록을 본다.

- [ ] **Step 4: 임시 커밋**

```bash
git add e2e/src/test/kotlin/dev/picasso/ops/e2e/AdapterTest.kt
git commit -F - <<'EOF'
chore(s1c): 어댑터 통합 시험

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

---

## Chunk 4: 화면

### Task 6: 어댑터 화면

**Files:**
- Create: `ui/src/components/AdapterForms.tsx`, `ui/src/components/AdaptersSection.tsx`
- Modify(전체): `ui/src/api.ts`, `ui/src/App.tsx`, `ui/src/labels.ts`, `ui/src/components/RobotsArea.tsx`, `ui/src/components/OutcomeNotice.tsx`(주석 한 줄), `ui/src/testing/fakeOps.ts`
- Test: `ui/src/components/AdaptersSection.test.tsx`

배치와 문구는 머리말 결정 7 이다. 조작은 `RobotsArea` 의 `run` 을 받아 보내므로, 결과 알림과 목록 다시 읽기는 기체 조작과 같은 길을 쓴다. `App.test.tsx` 는 고치지 않는다. 그 시험의 대역은 `/api/robots` 가 아닌 주소에 배열(빈 배열이나 조작 기록)을 돌려주므로 `/api/adapters` 의 답에 목록 칸이 없다. 화면은 없는 목록 칸을 «모름» 으로 보이므로 그대로 통과한다. 새 시험은 첫 읽기가 끝난 뒤(기체 목록의 «선언된 기체가 없습니다» 가 보인 뒤) 확인한다. 화면은 읽기 전에도 «모름» 을 보이므로, 기다리지 않으면 읽은 값과 상관없이 통과할 수 있다.

- [ ] **Step 1: 시험 쓰기와 대역 고치기(실패하게)**

`ui/src/testing/fakeOps.ts`(전체. `/api/adapters` 를 답하고 `adapterView` 를 더한 것이 다르다):

```ts
import { vi } from 'vitest'
import type { AdapterListView, Finding, OperationOutcome, RobotListView, RobotView } from '../api'

/** 운영 서비스 대역이 받은 요청 한 건. */
export interface Call {
  method: string
  url: string
  headers: Record<string, string>
  body: unknown
}

/** 시험이 고칠 수 있는 대역의 상태. 조작마다 [answer] 를 돌려준다. */
export interface FakeOps {
  calls: Call[]
  view: RobotListView
  adapters: AdapterListView
  answer: { status: number; body: unknown }
}

/** 어댑터 목록. 기본은 제품도 인스턴스도 없는 «없음» 이다. */
export function adapterView(partial: Partial<AdapterListView> = {}): AdapterListView {
  return { registry: 'OK', checkedAt: 't1', adapters: [], instances: [], asOf: 't1', ...partial }
}

export function robotView(robotId: string, status: string, blockers: Finding[] = []): RobotView {
  return {
    robot: {
      robotId,
      siteId: 'site-01',
      serialNumber: 'SN',
      displayName: null,
      status,
      lastReportedAt: status === 'CLAIMED' ? null : 't0',
      retiredAt: status === 'RETIRED' ? 't0' : null,
      retiredReason: status === 'RETIRED' ? '정비' : null,
      reportingAfterRetirement: false,
    },
    connection: status === 'CLAIMED' ? 'NO_REPORT' : 'FRESH',
    blockers,
  }
}

export function outcome(partial: Partial<OperationOutcome>): OperationOutcome {
  return {
    requestId: 'r-1',
    result: 'SUCCEEDED',
    confirmation: null,
    rejection: null,
    unauthorized: false,
    registryStatus: 200,
    ...partial,
  }
}

/** 브라우저처럼 ISO-8859-1 밖의 문자가 헤더에 있으면 보내기 전에 던지는 fetch 대역을 끼운다. */
export function installFakeOps(view: RobotListView, adapters: AdapterListView = adapterView()): FakeOps {
  const fake: FakeOps = { calls: [], view, adapters, answer: { status: 200, body: outcome({}) } }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const headers = (init?.headers ?? {}) as Record<string, string>
      for (const value of Object.values(headers)) {
        if ([...value].some((ch) => ch.charCodeAt(0) > 0xff)) {
          throw new TypeError("Failed to execute 'fetch': String contains non ISO-8859-1 code point.")
        }
      }
      const method = init?.method ?? 'GET'
      fake.calls.push({ method, url, headers, body: init?.body ? JSON.parse(init.body as string) : undefined })
      if (method === 'GET') {
        const body = url === '/api/robots' ? fake.view : url === '/api/adapters' ? fake.adapters : []
        return new Response(JSON.stringify(body), { status: 200 })
      }
      return new Response(JSON.stringify(fake.answer.body), { status: fake.answer.status })
    }),
  )
  return fake
}
```

`ui/src/components/AdaptersSection.test.tsx`:

```tsx
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Adapter, AdapterInstance, Finding, RobotListView } from '../api'
import { adapterView, installFakeOps, outcome } from '../testing/fakeOps'

const robots: RobotListView = { registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' }

const fleet: Adapter = {
  adapterId: 1,
  vendor: 'acme',
  name: 'fleet',
  versions: [
    {
      adapterVersionId: 10,
      version: '1.0.0',
      contractSemver: '0.9.0',
      conformance: 'UNTESTED',
      registeredAt: 't0',
      registeredBy: 'engineer/lee',
    },
  ],
}

const gateway: AdapterInstance = {
  instanceId: 'fleet-gw-01',
  siteId: 'site-01',
  fleetEndpoint: null,
  registeredAt: 't0',
  registeredBy: 'engineer/lee',
  adapter: 'acme/fleet',
  version: '1.0.0',
  contractSemver: '0.9.0',
  conformance: 'UNTESTED',
  discoveredRobots: 0,
}

/** 운영 서비스의 `AdapterRejections.of` 가 내는 모양 그대로다. */
const conflict: Finding = {
  kind: 'VERSION_CONFLICT',
  observed: 'HTTP 409, existing_contract_semver=0.9.0, 같은 버전이 다른 계약 semver 로 이미 있다',
  expected: '201 또는 200',
  checkedAt: 't2',
  owner: 'ENGINEER',
  inScreen: true,
  action: '다른 버전 번호로',
  target: null,
}

const listed = () => adapterView({ adapters: [fleet], instances: [gateway] })

/** 화면을 띄우고 첫 읽기가 끝날 때까지 기다린다. 기체 목록(빈 목록)이 보이면 어댑터 목록도 함께 읽혔다. */
async function section() {
  render(<App />)
  await screen.findByText('선언된 기체가 없습니다')
  return screen.getByRole('region', { name: '어댑터' })
}

function posted(fake: ReturnType<typeof installFakeOps>) {
  return fake.calls.find((call) => call.method === 'POST')!
}

describe('어댑터', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('인스턴스와 제품·빌드 목록이 적합성 UNTESTED 와 함께 보인다', async () => {
    installFakeOps(robots, listed())
    const adapters = await section()
    const instances = await within(adapters).findByRole('table', { name: '인스턴스 목록' })
    expect(within(instances).getByRole('row', { name: /fleet-gw-01/ })).toHaveTextContent(
      'fleet-gw-01acme/fleet 1.0.00.9.0UNTESTED직결0',
    )
    const builds = within(adapters).getByRole('table', { name: '제품·빌드 목록' })
    expect(within(builds).getByRole('row', { name: /acme\/fleet/ })).toHaveTextContent('acme/fleet1.0.00.9.0UNTESTED')
  })

  it('목록을 읽은 적이 없으면 없음이 아니라 모름을 보이고, 직전 값이면 그렇게 표시한다', async () => {
    installFakeOps(robots, adapterView({ registry: 'REGISTRY_SILENT', adapters: null, instances: null, asOf: null }))
    const adapters = await section()
    expect(await within(adapters).findByText('모름: 어댑터 목록을 아직 읽지 못했습니다')).toBeInTheDocument()
    expect(within(adapters).queryByText('등록된 제품이 없습니다')).not.toBeInTheDocument()

    vi.unstubAllGlobals()
    installFakeOps(robots, adapterView({ registry: 'REGISTRY_SILENT', checkedAt: 't2', adapters: [fleet], instances: [], asOf: 't1' }))
    await userEvent.click(screen.getByLabelText('운영자'))
    expect(await within(adapters).findByText('직전 값입니다 (t1 기준)')).toBeInTheDocument()
    expect(within(adapters).getByText('등록된 인스턴스가 없습니다')).toBeInTheDocument()
  })

  it('운영자 모드에서는 등록 폼이 없고 어느 모드에서 하는지 적는다', async () => {
    installFakeOps(robots, listed())
    const adapters = await section()
    await userEvent.click(screen.getByLabelText('운영자'))
    expect(within(adapters).getByText('어댑터 등록은 엔지니어 모드에서 합니다')).toBeInTheDocument()
    expect(within(adapters).queryByRole('form')).not.toBeInTheDocument()
  })

  it('제품 선언은 엔지니어 모드로 JSON 을 보내고 결과가 제품 이름과 함께 보인다', async () => {
    const fake = installFakeOps(robots)
    const form = within(await section()).getByRole('form', { name: '제품 선언' })
    await userEvent.type(within(form).getByLabelText('vendor'), 'acme')
    await userEvent.type(within(form).getByLabelText('name'), 'fleet')
    await userEvent.click(within(form).getByRole('button', { name: '제품 선언' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = posted(fake)
    expect(post.url).toBe('/api/adapters')
    expect(post.body).toEqual({ vendor: 'acme', name: 'fleet' })
    expect(post.headers['X-Ops-Mode']).toBe('engineer')
    expect(post.headers['Content-Type']).toBe('application/json')
    expect(await screen.findByText('acme/fleet 제품 선언: 반영됨')).toBeInTheDocument()
  })

  it('빌드 선언은 고른 제품의 경로로 버전과 계약 semver 를 보낸다', async () => {
    const fake = installFakeOps(robots, listed())
    const form = within(await section()).getByRole('form', { name: '빌드 선언' })
    const button = within(form).getByRole('button', { name: '빌드 선언' })
    await userEvent.type(within(form).getByLabelText('버전'), '1.1.0')
    await userEvent.type(within(form).getByLabelText('계약 semver'), '0.9.0')
    // 제품을 고르기 전에는 보낼 수 없다.
    expect(button).toBeDisabled()
    await userEvent.selectOptions(within(form).getByLabelText('제품'), 'acme/fleet')
    await userEvent.click(button)
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = posted(fake)
    expect(post.url).toBe('/api/adapters/1/versions')
    expect(post.body).toEqual({ version: '1.1.0', contractSemver: '0.9.0' })
    expect(await screen.findByText('acme/fleet 1.1.0 빌드 선언: 반영됨')).toBeInTheDocument()
  })

  it('인스턴스 등록은 고른 빌드 id 를 보내고 빈 플릿 주소는 null 이며 사이트는 싣지 않는다', async () => {
    const fake = installFakeOps(robots, listed())
    const form = within(await section()).getByRole('form', { name: '인스턴스 등록' })
    const button = within(form).getByRole('button', { name: '인스턴스 등록' })
    await userEvent.type(within(form).getByLabelText('instance_id'), 'fleet-gw-02')
    expect(button).toBeDisabled()
    await userEvent.selectOptions(within(form).getByLabelText('빌드'), 'acme/fleet 1.0.0')
    await userEvent.click(button)
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = posted(fake)
    expect(post.url).toBe('/api/adapter-instances')
    expect(post.body).toEqual({ instanceId: 'fleet-gw-02', adapterVersionId: 10, fleetEndpoint: null })
    expect(await screen.findByText('fleet-gw-02 인스턴스 등록: 반영됨')).toBeInTheDocument()
  })

  it('409 거절은 기존 계약값과 엔지니어의 후속 행동으로 보이고 기체 상세로 가는 바로 가기는 없다', async () => {
    const fake = installFakeOps(robots, listed())
    fake.answer = { status: 200, body: outcome({ result: 'REJECTED', registryStatus: 409, rejection: conflict }) }
    const form = within(await section()).getByRole('form', { name: '빌드 선언' })
    await userEvent.selectOptions(within(form).getByLabelText('제품'), 'acme/fleet')
    await userEvent.type(within(form).getByLabelText('버전'), '1.0.0')
    await userEvent.type(within(form).getByLabelText('계약 semver'), '1.0.0')
    await userEvent.click(within(form).getByRole('button', { name: '빌드 선언' }))
    const notice = (await screen.findByText('acme/fleet 1.0.0 빌드 선언: 거절됨')).closest('[role="status"]') as HTMLElement
    expect(within(notice).getByText('같은 버전에 다른 계약값')).toBeInTheDocument()
    expect(within(notice).getByText(/existing_contract_semver=0\.9\.0/)).toBeInTheDocument()
    expect(within(notice).getByText('엔지니어(화면 안): 다른 버전 번호로')).toBeInTheDocument()
    expect(within(notice).queryByText('바로 가기')).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 2: 시험이 실패하는지 확인**

Run: `cd ui && npx tsc -b; npm test; cd ..`
Expected: `tsc` 가 `AdapterListView`·`Adapter`·`AdapterInstance` 가 `api` 에 없다고 실패한다. vitest 는 새 시험 7개가 실패하고 기존 23개는 통과한다.

- [ ] **Step 3: 구현**

`ui/src/api.ts`(전체):

```ts
export type Mode = 'engineer' | 'operator'
export type RegistryState = 'OK' | 'REGISTRY_SILENT' | 'REGISTRY_UNAUTHORIZED'
export type Connection = 'FRESH' | 'STALE' | 'NO_REPORT'
export type Owner = 'SITE' | 'OPERATOR' | 'ENGINEER' | 'NONE'

export interface Robot {
  robotId: string
  siteId: string
  serialNumber: string | null
  displayName: string | null
  status: string
  lastReportedAt: string | null
  retiredAt: string | null
  retiredReason: string | null
  reportingAfterRetirement: boolean
}

/** 막힘이나 거절 한 건. 화면에 내는 칸 5개와 맞춘다(스펙 §7.4). */
export interface Finding {
  kind: string
  observed: string
  expected: string
  checkedAt: string
  owner: Owner
  inScreen: boolean
  action: string
  target: string | null
}

/** 기체 한 대. 원장 상태는 registry 값 그대로, 연결과 막힘은 운영 서비스가 계산한다(스펙 §7.3). */
export interface RobotView {
  robot: Robot
  connection: Connection
  blockers: Finding[]
}

/** 운영 서비스의 `GET /api/robots`. `robots` 가 null 이면 모름, 빈 배열이면 없음이다(스펙 §9). */
export interface RobotListView {
  registry: RegistryState
  checkedAt: string
  robots: RobotView[] | null
  robotsAsOf: string | null
}

/** 어댑터 빌드 하나. `conformance` 는 registry 값 그대로다(S1 은 `UNTESTED` 만 본다). */
export interface AdapterBuild {
  adapterVersionId: number
  version: string
  contractSemver: string
  conformance: string
  registeredAt: string | null
  registeredBy: string | null
}

/** 어댑터 제품 하나와 그 빌드들. */
export interface Adapter {
  adapterId: number
  vendor: string
  name: string
  versions: AdapterBuild[]
}

/** 이 사이트에 등록된 어댑터 인스턴스. 빌드는 제품 이름(`vendor/name`)과 버전으로만 가리킨다. */
export interface AdapterInstance {
  instanceId: string
  siteId: string
  fleetEndpoint: string | null
  registeredAt: string | null
  registeredBy: string | null
  adapter: string
  version: string
  contractSemver: string
  conformance: string
  discoveredRobots: number
}

/** 운영 서비스의 `GET /api/adapters`. 목록이 null 이면 모름, 빈 배열이면 없음이다(스펙 §9). */
export interface AdapterListView {
  registry: RegistryState
  checkedAt: string
  adapters: Adapter[] | null
  instances: AdapterInstance[] | null
  asOf: string | null
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

/** 조작 한 번의 답. registry 의 판단은 여기에 있다(스펙 §7.4·§9). */
export interface OperationOutcome {
  requestId: string
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  rejection: Finding | null
  unauthorized: boolean
  registryStatus: number | null
}

/** 운영 서비스가 registry 에 보내기 전에 막은 요청의 답. */
export interface PreRejection {
  error: string
  detail: string
}

/**
 * 조작을 보낸 결과. registry 에 닿았으면 `outcome`, 운영 서비스가 먼저 막았으면 `refused` 다.
 * 그 밖의 실패(연결 끊김, 프록시 오류, 운영 서비스 500)는 `unknown` 이다. 요청이 registry 까지 갔는지 모르므로
 * 보내지 못했다고 단정하지 않는다(스펙 §9).
 */
export type Sent =
  | { kind: 'outcome'; outcome: OperationOutcome }
  | { kind: 'refused'; refusal: PreRejection }
  | { kind: 'unknown'; cause: string }

/** 사용자 이름 규칙. 운영 서비스의 `Actor` 와 같다. 헤더는 ASCII 만 실을 수 있고 `/` 는 모드와 사용자를 가르는 자리다. */
export const USER_PATTERN = /^[A-Za-z0-9._-]{1,64}$/

/** 인증 없이 화면이 싣는 모드와 사용자(스펙 §7.1·§8). */
export interface Session {
  mode: Mode
  user: string
}

const actorHeaders = (session: Session) => ({ 'X-Ops-Mode': session.mode, 'X-Ops-User': session.user })

async function getJson<T>(path: string, session: Session): Promise<T> {
  const response = await fetch(path, { headers: actorHeaders(session) })
  if (!response.ok) throw new Error(`운영 서비스 응답 ${response.status}`)
  return (await response.json()) as T
}

async function send(method: string, path: string, session: Session, body?: unknown): Promise<Sent> {
  try {
    const response = await fetch(path, {
      method,
      headers:
        body === undefined
          ? actorHeaders(session)
          : { ...actorHeaders(session), 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (response.ok) return { kind: 'outcome', outcome: (await response.json()) as OperationOutcome }
    if (response.status === 400 || response.status === 403) {
      // 스프링이 직접 막은 400 의 본문에는 detail 이 없다. 그때도 사유 칸을 비우지 않는다.
      const refusal = (await response.json()) as Partial<PreRejection>
      const detail =
        typeof refusal.detail === 'string' && refusal.detail !== ''
          ? refusal.detail
          : `운영 서비스가 요청을 거절했습니다(HTTP ${response.status})`
      return { kind: 'refused', refusal: { error: refusal.error ?? '', detail } }
    }
    return { kind: 'unknown', cause: `운영 서비스 응답 ${response.status}` }
  } catch (error: unknown) {
    return { kind: 'unknown', cause: error instanceof Error ? error.message : String(error) }
  }
}

const retirementPath = (robotId: string) => `/api/robots/${encodeURIComponent(robotId)}/retirement`

export const fetchRobots = (session: Session) => getJson<RobotListView>('/api/robots', session)
export const fetchOperations = (session: Session) =>
  getJson<OperationRecord[]>('/api/operations', session)
export const declareRobot = (
  session: Session,
  robotId: string,
  serialNumber: string,
  displayName: string | null,
) => send('POST', '/api/robots', session, { robotId, serialNumber, displayName })
export const retireRobot = (session: Session, robotId: string, reason: string) =>
  send('POST', retirementPath(robotId), session, { reason })
export const reinstateRobot = (session: Session, robotId: string) =>
  send('DELETE', retirementPath(robotId), session)

export const fetchAdapters = (session: Session) => getJson<AdapterListView>('/api/adapters', session)
export const declareAdapter = (session: Session, vendor: string, name: string) =>
  send('POST', '/api/adapters', session, { vendor, name })
export const declareBuild = (session: Session, adapterId: number, version: string, contractSemver: string) =>
  send('POST', `/api/adapters/${adapterId}/versions`, session, { version, contractSemver })
export const registerInstance = (
  session: Session,
  instanceId: string,
  adapterVersionId: number,
  fleetEndpoint: string | null,
) => send('POST', '/api/adapter-instances', session, { instanceId, adapterVersionId, fleetEndpoint })
```

`ui/src/labels.ts`(전체):

```ts
import type { Connection, Owner } from './api'

/** 화면에 보이는 이름. 값은 운영 서비스의 열거형 그대로 받고, 이름만 여기서 붙인다. */
export const CONNECTION_LABEL: Record<Connection, string> = {
  FRESH: '신선',
  STALE: '오래됨',
  NO_REPORT: '보고 없음',
}

export const OWNER_LABEL: Record<Owner, string> = {
  SITE: '현장',
  OPERATOR: '운영자',
  ENGINEER: '엔지니어',
  NONE: '없음',
}

/** 막힘(스펙 §7.4 상태 막힘 4종)과 조작 거절(대응표)의 이름. 모르는 종류는 값 그대로 보인다. */
export const KIND_LABEL: Record<string, string> = {
  AWAITING_FIRST_REPORT: '첫 보고 대기',
  REPORT_STALE: '보고 오래됨',
  REPORTING_AFTER_RETIREMENT: '퇴역 뒤 보고',
  UNREGISTERED_ROW: '출처 없는 행',
  BAD_REQUEST: '본문 오류',
  WRONG_DOOR: '이미 다른 문으로 들어온 기체',
  RETIRED_ALREADY: '이미 퇴역한 기체',
  RETIRE_BAD_REQUEST: '퇴역 요청 오류',
  UNKNOWN_ROBOT: '모르는 기체',
  ADAPTER_BAD_REQUEST: '제품·빌드 형식 오류',
  UNKNOWN_ADAPTER: '없는 제품',
  VERSION_CONFLICT: '같은 버전에 다른 계약값',
  INSTANCE_BAD_REQUEST: '인스턴스 본문 오류',
  UNCLASSIFIED: '분류되지 않은 거절',
}

export const kindLabel = (kind: string) => KIND_LABEL[kind] ?? kind
```

`ui/src/components/AdapterForms.tsx`:

```tsx
import { useState } from 'react'
import type { FormEvent } from 'react'
import type { Adapter } from '../api'

/** 빈칸이면 null 로 보낸다. 선택 입력의 빈칸은 «없음» 이다. */
const optional = (value: string) => (value.trim() === '' ? null : value.trim())

interface ProductProps {
  busy: boolean
  onDeclare: (vendor: string, name: string) => void
}

/** 어댑터 제품 선언(엔지니어 모드). 같은 제품을 다시 선언하면 registry 가 이미 있는 제품을 그대로 둔다. */
export function ProductForm({ busy, onDeclare }: ProductProps) {
  const [vendor, setVendor] = useState('')
  const [name, setName] = useState('')
  const ready = vendor.trim() !== '' && name.trim() !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (ready) onDeclare(vendor.trim(), name.trim())
  }

  return (
    <form aria-label="제품 선언" onSubmit={submit}>
      <label>
        vendor
        <input value={vendor} onChange={(event) => setVendor(event.target.value)} />
      </label>
      <label>
        name
        <input value={name} onChange={(event) => setName(event.target.value)} />
      </label>
      <button type="submit" disabled={!ready || busy}>
        제품 선언
      </button>
    </form>
  )
}

interface BuildProps {
  adapters: Adapter[]
  busy: boolean
  onDeclare: (adapter: Adapter, version: string, contractSemver: string) => void
}

/** 빌드 선언(엔지니어 모드). 제품은 목록에서 고른다. 계약 semver 의 형식은 registry 가 본다. */
export function BuildForm({ adapters, busy, onDeclare }: BuildProps) {
  const [adapterId, setAdapterId] = useState('')
  const [version, setVersion] = useState('')
  const [contractSemver, setContractSemver] = useState('')
  const adapter = adapters.find((candidate) => String(candidate.adapterId) === adapterId) ?? null
  const ready = adapter !== null && version.trim() !== '' && contractSemver.trim() !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (ready) onDeclare(adapter, version.trim(), contractSemver.trim())
  }

  return (
    <form aria-label="빌드 선언" onSubmit={submit}>
      <label>
        제품
        <select value={adapterId} onChange={(event) => setAdapterId(event.target.value)}>
          <option value="">고르십시오</option>
          {adapters.map((candidate) => (
            <option key={candidate.adapterId} value={candidate.adapterId}>
              {candidate.vendor}/{candidate.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        버전
        <input value={version} onChange={(event) => setVersion(event.target.value)} />
      </label>
      <label>
        계약 semver
        <input value={contractSemver} onChange={(event) => setContractSemver(event.target.value)} />
      </label>
      <button type="submit" disabled={!ready || busy}>
        빌드 선언
      </button>
    </form>
  )
}

interface InstanceProps {
  adapters: Adapter[]
  busy: boolean
  onRegister: (instanceId: string, adapterVersionId: number, fleetEndpoint: string | null) => void
}

/**
 * 인스턴스 등록(엔지니어 모드). 빌드는 목록에서 고른다. 사이트는 운영 서비스가 `SITE_ID` 로 채우므로 입력란이 없다(스펙 §9).
 * 플릿 주소는 플릿 경유일 때만 넣는다.
 */
export function InstanceForm({ adapters, busy, onRegister }: InstanceProps) {
  const [instanceId, setInstanceId] = useState('')
  const [buildId, setBuildId] = useState('')
  const [fleetEndpoint, setFleetEndpoint] = useState('')
  const ready = instanceId.trim() !== '' && buildId !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (ready) onRegister(instanceId.trim(), Number(buildId), optional(fleetEndpoint))
  }

  return (
    <form aria-label="인스턴스 등록" onSubmit={submit}>
      <label>
        instance_id
        <input value={instanceId} onChange={(event) => setInstanceId(event.target.value)} />
      </label>
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
        플릿 주소
        <input value={fleetEndpoint} onChange={(event) => setFleetEndpoint(event.target.value)} />
      </label>
      <button type="submit" disabled={!ready || busy}>
        인스턴스 등록
      </button>
    </form>
  )
}
```

`ui/src/components/AdaptersSection.tsx`:

```tsx
import { declareAdapter, declareBuild, registerInstance } from '../api'
import type { Adapter, AdapterBuild, AdapterListView, Sent, Session } from '../api'
import { BuildForm, InstanceForm, ProductForm } from './AdapterForms'

interface Props {
  view: AdapterListView | null
  /** 운영 서비스에 닿지 않으면 [view] 는 직전 값이다. */
  opsError: string | null
  session: Session
  busy: boolean
  /** 조작을 보낸다. 결과 알림과 목록 다시 읽기는 부르는 쪽이 한다. */
  run: (what: string, operation: () => Promise<Sent>) => void
}

/**
 * 어댑터 인스턴스와 제품·빌드(스펙 §8 의 로봇·연결 영역 왼쪽 목록). 등록은 엔지니어 모드에서 한다.
 * 적합성은 registry 값 그대로 보인다. S1 은 적합성을 기록하지 않으므로 모두 `UNTESTED` 다(스펙 §5).
 */
export function AdaptersSection({ view, opsError, session, busy, run }: Props) {
  // 운영 서비스가 다른 모양을 주면(예: 시험 대역의 빈 배열) 칸이 없다. 없는 칸도 모름이다.
  const known = view !== null && view.adapters != null && view.instances != null ? view : null
  const adapters = known?.adapters ?? []

  return (
    <section aria-label="어댑터">
      <h2>어댑터</h2>
      {known !== null ? (
        <AdapterLists view={known} opsError={opsError} />
      ) : (
        <p>모름: 어댑터 목록을 아직 읽지 못했습니다</p>
      )}
      {session.mode === 'engineer' ? (
        <>
          <ProductForm
            busy={busy}
            onDeclare={(vendor, name) =>
              run(`${vendor}/${name} 제품 선언`, () => declareAdapter(session, vendor, name))
            }
          />
          <BuildForm
            adapters={adapters}
            busy={busy}
            onDeclare={(adapter, version, contractSemver) =>
              run(`${adapter.vendor}/${adapter.name} ${version} 빌드 선언`, () =>
                declareBuild(session, adapter.adapterId, version, contractSemver),
              )
            }
          />
          <InstanceForm
            adapters={adapters}
            busy={busy}
            onRegister={(instanceId, adapterVersionId, fleetEndpoint) =>
              run(`${instanceId} 인스턴스 등록`, () =>
                registerInstance(session, instanceId, adapterVersionId, fleetEndpoint),
              )
            }
          />
        </>
      ) : (
        <p>어댑터 등록은 엔지니어 모드에서 합니다</p>
      )}
    </section>
  )
}

function AdapterLists({ view, opsError }: { view: AdapterListView; opsError: string | null }) {
  // 읽은 시각이 확인 시각과 다르거나 운영 서비스에 닿지 않으면 직전 값이다(스펙 §9).
  const stale = view.asOf !== view.checkedAt || opsError !== null
  const instances = view.instances ?? []
  const builds = (view.adapters ?? []).flatMap<{ adapter: Adapter; build: AdapterBuild | null }>((adapter) =>
    adapter.versions.length === 0
      ? [{ adapter, build: null }]
      : adapter.versions.map((build) => ({ adapter, build })),
  )
  return (
    <>
      {stale && <p className="stale">직전 값입니다 ({view.asOf} 기준)</p>}
      <h3>인스턴스</h3>
      {instances.length === 0 ? (
        <p>등록된 인스턴스가 없습니다</p>
      ) : (
        <table aria-label="인스턴스 목록">
          <thead>
            <tr>
              <th>instance_id</th>
              <th>빌드</th>
              <th>계약 semver</th>
              <th>적합성</th>
              <th>플릿 주소</th>
              <th>발견 기체</th>
            </tr>
          </thead>
          <tbody>
            {instances.map((instance) => (
              <tr key={instance.instanceId}>
                <td>{instance.instanceId}</td>
                <td>
                  {instance.adapter} {instance.version}
                </td>
                <td>{instance.contractSemver}</td>
                <td>{instance.conformance}</td>
                <td>{instance.fleetEndpoint ?? '직결'}</td>
                <td>{instance.discoveredRobots}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <h3>제품·빌드</h3>
      {builds.length === 0 ? (
        <p>등록된 제품이 없습니다</p>
      ) : (
        <table aria-label="제품·빌드 목록">
          <thead>
            <tr>
              <th>제품</th>
              <th>버전</th>
              <th>계약 semver</th>
              <th>적합성</th>
            </tr>
          </thead>
          <tbody>
            {builds.map(({ adapter, build }) => (
              <tr key={build === null ? `p${adapter.adapterId}` : `b${build.adapterVersionId}`}>
                <td>
                  {adapter.vendor}/{adapter.name}
                </td>
                <td>{build?.version ?? '빌드 없음'}</td>
                <td>{build?.contractSemver ?? ''}</td>
                <td>{build?.conformance ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
```

`ui/src/components/OutcomeNotice.tsx`(전체. `what` 의 주석이 어댑터 조작도 가리키게 바뀐 것만 다르다):

```tsx
import type { Sent } from '../api'
import { FindingCard } from './FindingCard'

interface Props {
  /** 어느 대상의 어느 조작인지. 예: `humanoid-01 퇴역`, `acme/fleet 1.0.0 빌드 선언` */
  what: string
  sent: Sent
  onSelect: (robotId: string) => void
}

/**
 * 마지막 조작의 결과. 응답 없음은 거절과 다르다(스펙 §9). 재조회로 확인되기 전에는 성공으로도 실패로도 보이지 않는다.
 * 운영 서비스는 응답 없음 뒤 한 번 다시 읽고 답하므로, 확인 결과가 없으면 그 재조회도 실패한 것이다.
 */
export function OutcomeNotice({ what, sent, onSelect }: Props) {
  if (sent.kind === 'refused') {
    return (
      <p role="status">
        {what}: 보내지 않음. {sent.refusal.detail}
      </p>
    )
  }
  if (sent.kind === 'unknown') {
    return (
      <p role="status">
        {what}: 결과 모름({sent.cause}). 목록을 다시 읽어 확인하십시오
      </p>
    )
  }
  const { outcome } = sent
  if (outcome.result === 'SUCCEEDED') return <p role="status">{what}: 반영됨</p>
  if (outcome.unauthorized) {
    return <p role="status">{what}: registry 가 운영자 토큰을 거절했습니다. 상단의 전체 상태를 보십시오</p>
  }
  if (outcome.result === 'REJECTED' && outcome.rejection !== null) {
    return (
      <div role="status">
        <p>{what}: 거절됨</p>
        <FindingCard finding={outcome.rejection} onSelect={onSelect} />
      </div>
    )
  }
  switch (outcome.confirmation) {
    case 'CONFIRMED_APPLIED':
      return <p role="status">{what}: 응답은 없었으나 다시 읽어 보니 반영됨</p>
    case 'CONFIRMED_NOT_APPLIED':
      return <p role="status">{what}: 응답 없음. 다시 읽어 보니 반영 안 됨. 다시 하려면 새로 요청하십시오</p>
    default:
      return (
        <p role="status">
          {what}: 반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 목록에서 확인하십시오
        </p>
      )
  }
}
```

`ui/src/components/RobotsArea.tsx`(전체):

```tsx
import { useState } from 'react'
import { declareRobot, reinstateRobot, retireRobot } from '../api'
import type { AdapterListView, RobotListView, Sent, Session } from '../api'
import { CONNECTION_LABEL } from '../labels'
import { AdaptersSection } from './AdaptersSection'
import { DeclareForm } from './DeclareForm'
import { OutcomeNotice } from './OutcomeNotice'
import { RobotDetail } from './RobotDetail'

interface Props {
  view: RobotListView | null
  adapters: AdapterListView | null
  /** 운영 서비스에 닿지 않으면 [view]·[adapters] 는 직전 값이다. */
  opsError: string | null
  session: Session
  /** 조작이 끝나면 부른다. 목록을 다시 읽는다. */
  onChanged: () => void
}

/** 로봇·연결 영역. 왼쪽 목록(기체, 어댑터)과 오른쪽 상세(스펙 §8, 결정 6). 조작 결과는 상세 위에 보인다. */
export function RobotsArea({ view, adapters, opsError, session, onChanged }: Props) {
  const [selected, setSelected] = useState<string | null>(null)
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

  const current = view?.robots?.find((candidate) => candidate.robot.robotId === selected) ?? null

  return (
    <div className="split">
      <div>
        <section aria-label="기체 목록">
          <h2>기체</h2>
          {session.mode === 'engineer' ? (
            <DeclareForm
              busy={busy}
              onDeclare={(robotId, serial, displayName) =>
                run(`${robotId} 선언`, () => declareRobot(session, robotId, serial, displayName))
              }
            />
          ) : (
            <p>선언은 엔지니어 모드에서 합니다</p>
          )}
          <RobotList view={view} opsError={opsError} selected={selected} onSelect={setSelected} />
          <p className="offscreen">
            화면 밖 작업: 로봇 내부 지도와 웨이포인트 티칭, mimic 기동(site/ 런처). 화면은 완료를 대신 체크하지 않습니다
          </p>
        </section>
        <AdaptersSection view={adapters} opsError={opsError} session={session} busy={busy} run={run} />
      </div>
      <section aria-label="상세">
        <h2>상세</h2>
        {last !== null && <OutcomeNotice what={last.what} sent={last.sent} onSelect={setSelected} />}
        {current === null ? (
          <p>
            {selected === null
              ? '기체를 고르면 원장 상태와 연결이 여기에 보입니다.'
              : `목록에 없는 기체입니다: ${selected}`}
          </p>
        ) : (
          <RobotDetail
            key={current.robot.robotId}
            view={current}
            mode={session.mode}
            busy={busy}
            onRetire={(reason) =>
              run(`${current.robot.robotId} 퇴역`, () => retireRobot(session, current.robot.robotId, reason))
            }
            onReinstate={() =>
              run(`${current.robot.robotId} 복귀`, () => reinstateRobot(session, current.robot.robotId))
            }
          />
        )}
      </section>
    </div>
  )
}

interface ListProps {
  view: RobotListView | null
  opsError: string | null
  selected: string | null
  onSelect: (robotId: string) => void
}

function RobotList({ view, opsError, selected, onSelect }: ListProps) {
  if (view === null || view.robots === null) {
    return <p>모름: 기체 목록을 아직 읽지 못했습니다</p>
  }
  // 목록을 읽은 시각이 확인 시각과 다르거나 운영 서비스에 닿지 않으면 직전 값이다(스펙 §9).
  // 토큰 불일치는 목록을 새로 읽었으므로 직전 값이 아니다.
  const stale = view.robotsAsOf !== view.checkedAt || opsError !== null
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
              <th>연결</th>
              <th>막힘</th>
            </tr>
          </thead>
          <tbody>
            {view.robots.map(({ robot, connection, blockers }) => (
              <tr key={robot.robotId} className={robot.robotId === selected ? 'selected' : undefined}>
                <td>
                  <button type="button" className="link" onClick={() => onSelect(robot.robotId)}>
                    {robot.robotId}
                  </button>
                </td>
                <td>{robot.status}</td>
                <td>{CONNECTION_LABEL[connection]}</td>
                <td>{blockers.length}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
```

`ui/src/App.tsx`(전체):

```tsx
import { useEffect, useState } from 'react'
import { fetchAdapters, fetchOperations, fetchRobots } from './api'
import type { AdapterListView, OperationRecord, RobotListView, Session } from './api'
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
  const [adapters, setAdapters] = useState<AdapterListView | null>(null)
  const [records, setRecords] = useState<OperationRecord[] | null>(null)
  const [opsError, setOpsError] = useState<string | null>(null)
  // 조작이 끝나면 하나 올린다. 목록을 주기(5초)를 기다리지 않고 다시 읽는다.
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let alive = true
    // 실패해도 직전 값을 지우지 않는다. 대신 opsError 로 직전 값임을 표시한다(스펙 §9).
    const load = () => {
      Promise.all([fetchRobots(session), fetchAdapters(session), fetchOperations(session)])
        .then(([nextView, nextAdapters, nextRecords]) => {
          if (!alive) return
          setView(nextView)
          setAdapters(nextAdapters)
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
  }, [session, tick])

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
        {current.id === 'robots' && (
          <RobotsArea
            view={view}
            adapters={adapters}
            opsError={opsError}
            session={session}
            onChanged={() => setTick((value) => value + 1)}
          />
        )}
        {current.id === 'history' && <HistoryArea records={records} opsError={opsError} />}
      </main>
    </>
  )
}
```

- [ ] **Step 4: 시험과 빌드가 통과하는지 확인**

Run: `cd ui && npx tsc -b && npm test && npm run build; cd ..`
Expected: `tsc` 출력 없음, `Tests  30 passed (30)`(기존 23개, 새 7개), 빌드 성공.

- [ ] **Step 5: 결함 주입 5건(하나씩)**

① `AdaptersSection.tsx` 의 `{session.mode === 'engineer' ? (` 를 `{true ? (` 로 바꾼다. Expected 실패: `운영자 모드에서는 등록 폼이 없고 어느 모드에서 하는지 적는다`.
② `AdapterForms.tsx` 의 `Number(buildId), optional(fleetEndpoint))` 를 `Number(buildId), fleetEndpoint as string | null)` 로 바꾼다(빈 플릿 주소를 빈 글자로 보낸다). Expected 실패: `인스턴스 등록은 고른 빌드 id 를 보내고 빈 플릿 주소는 null 이며 사이트는 싣지 않는다`.
③ `api.ts` 의 `` `/api/adapters/${adapterId}/versions` `` 를 `` `/api/adapters/${adapterId}` `` 로 바꾼다. Expected 실패: `빌드 선언은 고른 제품의 경로로 버전과 계약 semver 를 보낸다`.
④ `AdaptersSection.tsx` 의 `const stale = view.asOf !== view.checkedAt || opsError !== null` 을 `const stale = opsError !== null` 로 바꾼다. Expected 실패: `목록을 읽은 적이 없으면 없음이 아니라 모름을 보이고, 직전 값이면 그렇게 표시한다`.
⑤ `AdaptersSection.tsx` 의 `view !== null && view.adapters != null && view.instances != null ? view : null` 을 `view` 로 바꾼다(목록 칸이 없어도 «없음» 으로 보인다). Expected 실패: `목록을 읽은 적이 없으면 없음이 아니라 모름을 보이고, 직전 값이면 그렇게 표시한다`. ④ 와 같은 시험이 다른 갈래(첫 절반)에서 잡는다.
각각 `npm test` 의 `Tests` 줄이 `1 failed | 29 passed` 인지와 실패 이름을 보고, 되돌리고 `30 passed` 를 본다.

- [ ] **Step 6: 임시 커밋**

```bash
git add ui/src/api.ts ui/src/App.tsx ui/src/labels.ts ui/src/components/RobotsArea.tsx ui/src/components/OutcomeNotice.tsx ui/src/components/AdapterForms.tsx ui/src/components/AdaptersSection.tsx ui/src/components/AdaptersSection.test.tsx ui/src/testing/fakeOps.ts
git commit -F - <<'EOF'
chore(s1c): 어댑터 화면

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

---

## Chunk 5: Playwright, 마무리

### Task 7: Playwright 에 어댑터 흐름 더하기

**Files:**
- Modify: `ui/e2e/lifecycle.spec.ts`(전체)

S1b 의 생애주기 뒤, registry 를 멈추기 전에 엔지니어 모드로 제품 → 빌드 → 인스턴스를 등록하고 인스턴스 목록에 `UNTESTED` 를 본다(머리말 결정 9). registry 를 멈춘 뒤에는 기체 목록과 어댑터 목록이 각자 직전 값으로 남는지 본다.

- [ ] **Step 1: 시험 고치기**

`ui/e2e/lifecycle.spec.ts`(전체):

```ts
import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * S1b 완료 판정의 화면 쪽(스펙 §3·§10). 선언 → 보고(CONFIRMED) → 퇴역 → 퇴역 뒤 보고 감지 → 복귀 → 조작 기록,
 * 그리고 registry 를 멈추면 화면 전체 상태가 «모름» 이고 목록이 직전 값으로 남는 것(스펙 §10 마지막 문단).
 * 기체는 site/robots.json 의 humanoid-01 이다. 런처가 mimic 을 띄워 두었으므로 선언하면 보고가 붙는다.
 *
 * 이어서 S1c 의 화면 쪽(스펙 §3). 제품 선언 → 빌드 선언 → 인스턴스 등록 → 인스턴스 목록에 UNTESTED.
 * registry 를 멈추는 것은 맨 끝이다. 그 뒤로는 조작이 registry 에 닿지 않는다.
 *
 * 선언 직후의 CLAIMED 는 여기서 단언하지 않는다. 실시간 1:1 시계에서는 다음 보고가 1초 안에 올 수도 있어
 * CLAIMED 가 화면에 보이는 시간이 정해지지 않는다. CLAIMED 는 가상 시계를 직접 미는 통합 시험이 본다.
 */
test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 registry 를 멈추면 모름을 본다', async ({ page }) => {
  await page.goto('/')
  const detail = page.getByRole('region', { name: 'humanoid-01 상세' })

  // 엔지니어 모드(기본)에서 선언
  const declare = page.getByRole('form', { name: '기체 선언' })
  await declare.getByLabel('robot_id').fill('humanoid-01')
  await declare.getByLabel('일련번호').fill('HA-0001')
  await declare.getByRole('button', { name: '선언' }).click()
  await expect(page.getByText('humanoid-01 선언: 반영됨', { exact: true })).toBeVisible()

  // 보고를 기다려 CONFIRMED 를 본다
  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
  await expect(detail.getByText('CONFIRMED', { exact: true })).toBeVisible()
  await expect(detail.getByText('막힘 없음', { exact: true })).toBeVisible()

  // 운영자 모드에서 사유를 넣어 퇴역
  await page.getByLabel('운영자').check()
  await detail.getByLabel('퇴역 사유').fill('정비')
  await detail.getByRole('button', { name: '퇴역' }).click()
  await expect(detail.getByText('RETIRED', { exact: true })).toBeVisible()

  // 퇴역 뒤에도 기체가 보고하므로 막힘이 뜬다
  await expect(detail.getByText('퇴역 뒤 보고', { exact: true })).toBeVisible()
  await expect(detail.getByText('운영자(화면 안): 현장에서 기체를 내리거나 복귀')).toBeVisible()

  // 복귀
  await detail.getByRole('button', { name: '복귀' }).click()
  await expect(detail.getByText('CONFIRMED', { exact: true })).toBeVisible()
  await expect(detail.getByText('막힘 없음', { exact: true })).toBeVisible()

  // 조작 기록
  await page.getByRole('button', { name: '이력' }).click()
  await expect(page.getByRole('cell', { name: '운영자/local' })).toHaveCount(2)
  await expect(page.getByRole('cell', { name: '엔지니어/local' })).toHaveCount(1)
  await expect(page.getByRole('cell', { name: '정비' })).toBeVisible()

  // 어댑터: 엔지니어 모드에서 제품 → 빌드 → 인스턴스
  await page.getByRole('button', { name: '로봇·연결' }).click()
  await page.getByLabel('엔지니어').check()
  const product = page.getByRole('form', { name: '제품 선언' })
  await product.getByLabel('vendor').fill('acme')
  await product.getByLabel('name').fill('fleet')
  await product.getByRole('button', { name: '제품 선언' }).click()
  await expect(page.getByText('acme/fleet 제품 선언: 반영됨', { exact: true })).toBeVisible()

  const build = page.getByRole('form', { name: '빌드 선언' })
  await build.getByLabel('제품').selectOption('acme/fleet')
  await build.getByLabel('버전').fill('1.0.0')
  await build.getByLabel('계약 semver').fill('0.9.0')
  await build.getByRole('button', { name: '빌드 선언' }).click()
  await expect(page.getByText('acme/fleet 1.0.0 빌드 선언: 반영됨', { exact: true })).toBeVisible()

  const instance = page.getByRole('form', { name: '인스턴스 등록' })
  await instance.getByLabel('instance_id').fill('fleet-gw-01')
  await instance.getByLabel('빌드').selectOption('acme/fleet 1.0.0')
  await instance.getByRole('button', { name: '인스턴스 등록' }).click()
  await expect(page.getByText('fleet-gw-01 인스턴스 등록: 반영됨', { exact: true })).toBeVisible()
  const instances = page.getByRole('table', { name: '인스턴스 목록' })
  await expect(instances.getByRole('row', { name: /fleet-gw-01/ })).toContainText('UNTESTED')

  // registry 를 멈춘다. 런처(registry 와 mimic 이 든 프로세스)를 끈다.
  const pidFile = fileURLToPath(new URL('../../build/site.pid', import.meta.url))
  process.kill(Number(readFileSync(pidFile, 'utf8')))
  await page.getByRole('button', { name: '로봇·연결' }).click()
  await expect(page.getByRole('alert')).toContainText('모름: registry 가 답하지 않습니다')
  await expect(page.getByRole('button', { name: 'humanoid-01', exact: true })).toBeVisible()
  // 기체 목록과 어댑터 목록이 각자 직전 값으로 남는다.
  await expect(page.getByRole('region', { name: '기체 목록' }).getByText(/직전 값입니다/)).toBeVisible()
  const adapters = page.getByRole('region', { name: '어댑터' })
  await expect(adapters.getByText(/직전 값입니다/)).toBeVisible()
  await expect(adapters.getByRole('row', { name: /fleet-gw-01/ })).toBeVisible()
})
```

- [ ] **Step 2: 돌리기**

```bash
./gradlew :site:installDist :ops-service:installDist --console=plain -q
cd ui && npx playwright test; cd ..
docker ps -a --format '{{.Names}}' | grep site- ; echo containers-checked
```
Expected: `1 passed`(약 1.5분), 마지막 줄 앞에 `site-` 컨테이너 없음. 이어서 남은 프로세스가 없는지 본다(Windows PowerShell). 출력이 `0` 이어야 한다.

```powershell
(Get-CimInstance Win32_Process -Filter "name='java.exe'" | Where-Object { $_.CommandLine -match 'OpsApplicationKt|dev.picasso.ops.site' }).Count
```

- [ ] **Step 3: 결함 주입 1건**

① `AdaptersSection.tsx` 의 `<td>{instance.conformance}</td>` 를 `<td>{instance.contractSemver}</td>` 로 바꾼다(인스턴스의 적합성 칸에 계약값을 보인다). 배포본은 그대로 두고 Step 2 의 Playwright 만 다시 돈다(화면은 Playwright 가 빌드한다). Expected: `1 failed`, 실패 자리는 인스턴스 행의 `UNTESTED` 확인(`lifecycle.spec.ts` 의 `instances.getByRole('row', { name: /fleet-gw-01/ })` 에 `toContainText('UNTESTED')`, 받은 글은 계약값 두 번). 끝난 뒤 컨테이너와 프로세스는 남지 않는다. 되돌리고 Playwright 를 다시 돌려 `1 passed` 를 본다. 같은 주입은 vitest 의 `인스턴스와 제품·빌드 목록이 적합성 UNTESTED 와 함께 보인다` 도 잡는다.

- [ ] **Step 4: 임시 커밋**

```bash
git add ui/e2e/lifecycle.spec.ts
git commit -F - <<'EOF'
chore(s1c): Playwright 에 어댑터 흐름 추가

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 8: README

**Files:**
- Modify: `README.md`(컨트롤러가 한다)

- [ ] **Step 1: README 고치기(컨트롤러가 한다)**

문장은 Fable·Codex 초안 취합이다. 더할 사실:
- 지금 단계는 S1c(어댑터 등록). 로봇·연결 영역에서 엔지니어 모드로 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 한다. 적합성 기록은 열지 않으며 모두 `UNTESTED` 로 보인다.
- 화면 시험(Playwright)이 어댑터 흐름도 돈다.

- [ ] **Step 2: 임시 커밋**

```bash
git add README.md
git commit -F - <<'EOF'
chore(s1c): README

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 9: 전체 확인, 스펙 정정, PR

**Files:**
- Modify: `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md`
- Modify: `docs/superpowers/plans/2026-10-07-s1c-adapters.md`(끝에 «실행 결과» 절)
- Modify: `docs/superpowers/plans/2026-10-07-s1b-robot-lifecycle.md`(«실행 결과» 의 CI 줄 하나)

- [ ] **Step 1: 전체 빌드와 시험**

Run: `./gradlew build :site:installDist :ops-service:installDist --console=plain` 그리고 세 모듈 XML 확인. 이어서 `cd ui && npm ci && npm test && npm run build && npx playwright test; cd ..`
Expected: site 12, ops-service 73, e2e 20, 실패 0. vitest `30 passed`. Playwright `1 passed`.

- [ ] **Step 2: 커밋된 트리만으로 도는지 확인**

Run: `git status --short` 가 비었는지 본다. 이어서 짧은 경로에 새로 클론해 커밋된 파일만으로 돌린다(빠뜨린 `git add` 를 잡는다).

```bash
D=C:/Users/Eisen/AppData/Local/Temp/s1c-clone
rm -rf "$D"
git clone -q --recurse-submodules -b feat/s1c-adapters "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" "$D"
(cd "$D" && ./gradlew build :site:installDist :ops-service:installDist --console=plain -q && cd ui && npm ci && npm test && npx playwright test)
rm -rf "$D"
```
Expected: 모두 성공(Playwright `1 passed`).

- [ ] **Step 3: 스펙 정정(컨트롤러가 한다)**

문장은 Fable·Codex 초안 취합이다. 고칠 사실(머리말 결정과 대응):
- §3: S1c 행의 완료 판정 «P1 거절(400/404/409)» 에 인스턴스 등록 거절(400)도 들어간다는 것.
- §7.2: 어댑터 목록은 `GET /diag/adapter-instances?site=` 와 `GET /operations/adapters` 를 이 순서로 함께 읽고 둘 다 읽혀야 새 값으로 바꾼다. 순서의 이유(결정 6).
- §7.4: 대응표의 P1·인스턴스 행에 종류 값(`ADAPTER_BAD_REQUEST`, `UNKNOWN_ADAPTER`, `VERSION_CONFLICT`, `INSTANCE_BAD_REQUEST`)과 화면 열(안)을 채우고, «P1 의 행은 S1c 에서 종류 값을 정합니다» 문장을 정해진 것으로 고친다. 409 의 관측값에 기존 계약값이 들어가는 것, 어댑터 거절에는 바로 가기가 없다는 것. `UNCLASSIFIED` 행의 조작 칸을 «선언·퇴역·복귀» 에서 기체 조작과 어댑터 조작 모두로 넓힌다(결정 4).
- §8: 로봇·연결 영역의 «어댑터» 구역과 순서, 등록 폼 3개는 엔지니어 모드에서만, 운영 서비스가 어댑터 조작도 403 으로 집행, 대상은 목록에서 고른다는 것, 적합성은 registry 값 그대로(결정 2·7). «`REGISTRY_UNAUTHORIZED` 는 목록을 새로 읽었으므로 직전 값으로 표시하지 않는다» 는 기체 목록의 일이고, 어댑터 목록은 관문 안에서 읽으므로 토큰 불일치 때 직전 값으로 보인다는 예외(결정 6).
- §9: 반영 판정 표의 제품·빌드 등록 행과 어댑터 인스턴스 등록 행을 결정 5 대로 고치고 이유를 적는다. 인스턴스 재조회는 두 목록을 다 읽어야 하며 하나라도 못 읽으면 확인 행이 없다는 것. 확인 행의 `registry_response` 에 남는 것은 조작마다 다르다는 것. 기체는 출처와 원장 상태, 제품은 `adapter_id`, 빌드는 그 버전의 `contract_semver`, 인스턴스는 제품 이름·버전·플릿 주소이며, 재조회에서 대상을 못 봤으면 `null` 이다(결정 1·5). 사전 거절에 인스턴스 사이트 대조와 `BUILD_REQUIRED`(결정 2·3). 조작 기록 대상 칸 표기(결정 8).
- §10: S1c 통합 시험 행을 실제 구성으로(`AdapterTest` 7개), Playwright 가 어댑터 흐름도 돈다는 것(결정 9).
- §11: 표 앞의 기준 커밋 문장에 새 행들은 서브모듈 `6b1a255` 에서 확인했다는 것을 더한다. 행 «어댑터 제품·빌드 등록 컨트롤러 없음» 은 `cd688ff` 의 사실이며 P1(`6b1a255`)로 바뀌었음을 그 행에 적는다(행은 지우지 않는다). 사실 행 셋을 더한다. 인스턴스 등록은 같은 id 면 빌드·사이트·플릿 주소를 덮어쓰고, 없는 빌드 id 는 서비스가 거절해 400 이 된다(`registry/.../adapter/AdapterInstanceService.kt` 의 `register`, `registry/.../web/OperationsController.kt` 의 `registerInstance`). 같은 제품의 두 번째 선언은 감사 기록을 남기지 않는다(`registry/.../adapter/AdapterService.kt` 의 `declareAdapter`). `/diag/adapter-instances` 의 행에는 빌드 id 가 없고 제품 이름과 버전이 있다(`AdapterInstanceRow`).

- [ ] **Step 4: 실행 결과 절과 S1b 의 CI 줄**

이 계획 끝에 «실행 결과» 절을 덧붙인다. S1b 계획과 같은 모양이다. 담을 것: 기준 브랜치와 계획 커밋(Task 0 의 결과), 계획과 달라진 점, 모듈별 시험 수와 실패 0, 결함 주입 결과, Playwright 결과와 정리 확인, 깨끗한 클론 결과. CI 는 «PR 뒤 확인 대기».

S1b 계획(`docs/superpowers/plans/2026-10-07-s1b-robot-lifecycle.md`) «실행 결과» 의 마지막 줄 `- CI: PR 뒤 확인 대기. Linux 의 Playwright 는 로컬에서 돈 적이 없어 첫 CI 가 첫 실측` 을 PR #2 의 결과로 바꾼다. 사실: PR #2 의 CI 에서 `gradle`·`ui`·`playwright` job 3개 초록, Linux 의 Playwright `1 passed`(시험 1.0분, Playwright 가 보고한 실행 1.5분), PR #2 는 2026-10-07 머지(머지 커밋 `d94a5e6`). 문장은 위와 같이 초안 취합이다.

- [ ] **Step 5: 임시 커밋을 하나로 합치기**

커밋 문장은 Fable·Codex 초안 취합이다. 기준은 계획 커밋이다. 계획 커밋은 따로 남고, 그 위의 임시 커밋만 하나로 합친다.

```bash
git add docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md docs/superpowers/plans/2026-10-07-s1c-adapters.md docs/superpowers/plans/2026-10-07-s1b-robot-lifecycle.md
git commit -F - <<'EOF'
chore(s1c): 스펙 정정과 실행 결과

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
BASE=<Task 0 에서 적은 계획 커밋 해시>
T=$(git rev-parse 'HEAD^{tree}')
git log --oneline "$BASE"..HEAD
git reset --soft "$BASE"
git commit -F - <<'EOF'
<취합한 메시지>
EOF
git log --oneline "$BASE"..HEAD
test "$(git rev-parse 'HEAD^{tree}')" = "$T" && echo TREE-SAME
git status --short
```
Expected: 첫 `git log` 에 임시 커밋 9개(최종 검토 반영 커밋이 있으면 그만큼 더), 둘째 `git log` 에 1개, `TREE-SAME`, `git status --short` 출력 없음. `git log --oneline -3` 이 합친 커밋, 계획 커밋, main 순이다.

- [ ] **Step 6: 푸시와 PR(사용자 승인 뒤)**

PR 본문은 Fable·Codex 초안 취합이며 형식 훅이 heredoc 의 `--body-file -` 만 읽는다(파일을 `<` 로 넘기면 거절한다).

```bash
git push -u origin feat/s1c-adapters
gh pr create --repo LivingLikeKrillin/picasso-ops --base main --head feat/s1c-adapters --title "<제목>" --body-file - <<'EOF'
<본문>
EOF
```

- [ ] **Step 7: CI 결과 한 번 읽기**

CI 를 폴링하지 않는다. PR 을 만든 뒤 앱의 PR 도구로 연결하고 CI 상태를 한 번 읽는다(Auto-fix 가 켜져 있으면 실패 때 앱이 알린다).
- 세 job 이 모두 초록이면 job 로그에서 Playwright 의 `1 passed` 와 Gradle 시험 XML(아티팩트 `test-results`)을 확인한다. 이어서 이 계획 «실행 결과» 의 CI 줄을 job 결과(job 3개, Playwright 의 시험 시간과 실행 시간)로 바꾸고 후속 커밋으로 올린다. 문장은 초안 취합이다. S1c 완료 보고는 이 뒤에 한다.

```bash
git add docs/superpowers/plans/2026-10-07-s1c-adapters.md
git commit -F - <<'EOF'
docs(plans): S1c 실행 결과의 CI 확인 반영

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
git push
```
- 빨가면 `playwright` job 은 아티팩트 `playwright-report` 와 로그의 `[WebServer]` 줄을, `gradle` job 은 `test-results` 의 XML 실패 이름을 읽고 고친다. 고친 것은 새 커밋으로 올린다.
머지는 사용자 승인 뒤다.

## 실행 결과 (2026-10-07)

- 브랜치 `feat/s1c-adapters`, 계획 커밋 `33beeb9` 위에 구현. 계획 커밋은 S1b 머지 뒤의 `main` `d94a5e6` 위. 기준 브랜치 `main`, PR base `main`
- 실행 방식: 작업을 6묶음(Task 1~2, 3~4, 5, 6, 7, 8~9)으로 나눠 Task 7 까지는 묶음마다 구현자 1명, Task 8~9 는 컨트롤러. 묶음마다 커밋된 파일을 계획 작성 때 돌린 스크래치 빌드의 파일과 기계 대조, 26개 파일 모두 일치. README 와 스펙 정정 문장은 Codex·Fable 초안 취합
- 기준선: site 12, ops-service 55, e2e 13, vitest 23(Gradle 이 건너뛰어 `--rerun` 으로 다시 실행)
- 시험 결과(JUnit XML): site 12, ops-service 76(`ActorTest` 4, `AdapterListServiceTest` 6, `AdapterOperationsTest` 9, `AdapterRejectionsTest` 3, `BlockersTest` 7, `EnvBoundaryTest` 1, `OperationLogTest` 7, `RegistryAdaptersTest` 3, `RegistryClientTest` 6, `RegistryWritesTest` 6, `RejectionsTest` 3, `RobotListServiceTest` 9, `RobotOperationsTest` 12), e2e 20(`AdapterTest` 7, `LifecycleTest` 10, `SkeletonTest` 3), 실패 0. vitest 30개 통과, `tsc` 와 `npm run build` 성공. `checkNoPicassoOnMain` 성공
- Playwright(Windows): `1 passed`, 시험 53.3초, 스택 기동 포함 1.5분. 종료 뒤 스택 프로세스와 `site-` 컨테이너 0개
- 결함 주입: 계획의 22건(Kotlin 16, 화면 5, Playwright 1)과 최종 검토 반영 3건을 하나씩 넣고 되돌림. 매번 지정한 시험이 실패, 이름은 JUnit XML·vitest·Playwright 출력에서 확인. 같은 이름의 시험이 `AdapterTest` 와 `LifecycleTest` 에 있어 클래스별 XML 로 확인
- 계획과 달라진 점: 작업 중에는 없음
- 최종 코드 품질 검토(Critical 0, Important 0)에서 반영한 것:
  - Minor 시험 빈틈 3건: 인스턴스 재조회의 제품 이름 대조, 빌드 재조회의 제품 id 대조, 어댑터 목록의 읽기 순서. 앞의 둘은 픽스처가 제품 하나뿐이라 해당 조건을 지워도 통과, 읽기 순서는 시험이 호출 순서를 보지 않아 두 줄을 바꿔도 통과. 시험 3개 추가(ops-service 73 → 76), 각각 결함 주입으로 확인
  - Minor 한계 1건(코드 주석에 기록): 인스턴스 목록은 제품을 `vendor/name` 으로만 이름 짓고 registry 는 두 칸 모두 `/` 를 허용하므로 버전이 같은 두 제품(`a/b` + `c` 와 `a` + `b/c`)을 재조회에서 가를 수 없음. registry 가 인스턴스에 빌드 id 를 내야 해소 가능
- 새 클론 검증: 커밋된 파일만으로 짧은 경로에 새로 클론해 `./gradlew build`, `npm ci` 와 vitest, Playwright `1 passed`
- CI: PR 뒤 확인 대기
