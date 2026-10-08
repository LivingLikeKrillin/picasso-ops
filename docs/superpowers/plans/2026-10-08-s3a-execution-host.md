# S3a 미들웨어 실행 호스트와 작업 지시 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking. 단, 이 계획은 묶음(Task 1·2 / Task 3·4 / Task 5 / Task 6·7)마다 구현자 하나가 하고, 검토는 묶음이 끝난 뒤 컨트롤러가 기계 대조와 시험으로 한다.

**Goal:** 운영자가 화면의 작업 지시 폼으로 코드 정의 임무(InspectAsset, PrepareSequencedRack)의 작업 지시를 내면, 운영 서비스가 기체마다 배정 가능 여부와 이유를 보이고 배정 가능한 기체만 후보로 실행 호스트에 넘기며, 실행 호스트의 picasso 미들웨어가 후보 중 하나를 골라 mimic 기체에서 실행하고, 화면의 실행 목록이 실행·단위 상태와 임무 버전(«코드 정의»)을 보인다. PrepareSequencedRack 은 셀 대역이 배치 직후 슬롯을 채워 E2 로 끝난다.

**Architecture:** 새 모듈 `mission-host`(별도 프로세스, Spring Boot)가 picasso `Middleware` 를 잠금 하나와 250ms pump 로 돌리고, mimic 에 Netty(`ClientRobotPort`)로 붙으며, 셀 신호는 site 의 셀 대역을 루프백 HTTP 로 읽는다. site 는 mimic 가상 시계를 실제 시각에 맞추고 gRPC 포트를 고정하며 셀 대역(`SiteCell`)을 둔다. 운영 서비스는 picasso 를 쓰지 않는 경계를 지키며 호스트 REST 를 부르는 유일한 화면 백엔드다(배정 가능 판정·작업 지시 제출·조작 기록·실행 목록·셀 전달). 화면은 «운영» 영역을 연다.

**Tech Stack:** Kotlin, Spring Boot(BOM), JDK `HttpServer`, picasso(`Middleware`, `ClientRobotPort`, `InMemoryMissionCatalog`, `MimicServer.exclusive`), Postgres·Flyway(V3), React·TypeScript·vitest, Playwright, JUnit5 + kotlin.test.

**근거 스펙:** `docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md`(스펙 검토 2회)와 요청·응답 모양 `docs/superpowers/specs/2026-10-08-s3a-json-contract.md`(코드 주석의 «S3a JSON 계약 §N»). picasso 쪽 선행 변경은 P4(picasso PR #83, 머지 커밋 `1e3f4ae`, 계획 `docs/superpowers/plans/2026-10-08-p4-stream-engine-lock.md`)로 끝났다.

**스펙이 계획에 맡긴 것과 이 계획이 정한 것(스파이크에서 정함):**
- site: 셀 픽스처는 `SiteConfig.cell`(기본 `CellFixture.STANDARD`: 제시 자리 `SEQ-IN-02.BIN-A`=`ENGINE-COVER-A`, 슬롯 `RACK-204.S01`~`S04`)의 코드 상수다(`.env` 에 없음). 두 포트는 `SiteConfig` 기본값 0 이고 `fromEnv` 만 필수로 읽는다. `object_id` 가 제시 자리가 아니면 점유로 채우되 자재는 null, `destination` 이 픽스처 슬롯이 아니면 무시한다. 시험용 `advance(by)` 도 민 직후 훑는다.
- mission-host: JSON 이 아닌 POST 는 415(운영 서비스의 `consumes` 관례). 400 은 `UNKNOWN_WORK_MASTER`·`BAD_REQUEST`, 본문 `{error, detail}`. `GET /host/cell` 은 스냅숏이 없으면 200 `{"cell": null}`(호스트 불통 503 과 가름). 판정 행에 `passed`·`reasons`(한국어 표시 문구)를 더하고, 제출 응답의 `excluded` 는 판정 행 목록, `refusals` 는 `[{robotId, reason}]`. JDBC·Flyway 자동설정을 이름으로 끈다(통합 시험의 한 JVM 에 JDBC 가 있어 기동이 멈춘다). `pumpedAt` 은 pump 시작 때의 호스트 시계 값, 스케줄은 `scheduleWithFixedDelay`(250ms).
- 운영 서비스: 호스트 요청 제한 5초(연결 2초). 호스트가 잠금 아래에서 mimic 을 부르고 mimic 이 registry 적재에 최대 3초를 기다릴 수 있다. 호스트 4xx 는 `outcome` 을 `{result: REJECTED, rejectionReason}` 로 옮긴다. 200 인데 본문을 못 읽으면 NO_RESPONSE 로 남기고 재조회한다. 작업 지시 id 는 `JO-<UTC yyyyMMdd>-<requestId 앞 8자>`, 판정에는 `JO-DRAFT`. 판정 행의 null 이 «모름»(시운전·연결 null 은 registry 모름, `host` null 은 호스트 모름, 호스트 판정은 중첩). 오류 이름 `JOB_ORDER_BAD_REQUEST`·`UNKNOWN_WORK_MASTER`·`UNIT_ID_CONFLICT`·`NO_ELIGIBLE_ROBOT`, 호스트 불통 503 `HOST_SILENT`. `NO_ELIGIBLE_ROBOT` 의 detail 은 `id: 이유; 이유 / id: …`. 판정 대상은 그 사이트의 기체 전부(퇴역 기체는 `RETIRED`). 제출 200 본문은 `{requestId, jobOrderId, result, confirmation, outcome}`. 대상 id 64자 한도(UTF-16 길이, `inspect` 의 `target` 파라미터 한도)를 넘으면 `JOB_ORDER_BAD_REQUEST`.
- 화면: 폴링 5초(`ui/src/poll.ts`), 배정 가능 판정은 폼 변경 뒤 300ms 디바운스(폴링 때는 바로). 단위 id 겹침과 64자 한도는 화면이 따로 검사하지 않고 운영 서비스의 400 을 «작업 지시 폼 오류: …» 로 보인다. 결과 이름 ACCEPTED «배정됨», IDEMPOTENT «같은 작업 지시의 기존 실행», REJECTED «거부됨», UNASSIGNED «미배정». `deliver` 를 `Delivered<T>` 로 일반화하고 제출 응답은 `JobOrderOutcome` 으로 읽는다. 실행 목록은 최신 실행부터.
- 통합 시험: `CommissioningTest` 는 단계별 중간 상태를 단언하므로 그대로 두고, 같은 단계를 묶은 `Commissioned.complete(stack)` 를 따로 둔다(quadruped-01 명칭은 선언 전에 `Site.teach`, 끝에서 두 기체의 시운전 완료와 연결 신선을 확인). 시계 규칙은 5초씩 밀고, 밀 때마다 `pumpedAt >= Site.now()` 를 기다린 뒤 250ms 더, 확인 중(VERIFYING)인 단위가 보이면 실제 시간으로 최대 3초 기다린다. 시드 0 에서 InspectAsset 은 humanoid-01 로 가고(비용이 같으면 기체 id 순) PrepareSequencedRack 의 `pick_place` 둘이 실패 모드 없이 끝난다. 통합 시험은 운영 서비스 REST 만 쓴다.
- Playwright: 단계는 S2 단계 뒤, site 를 끄기 전. 그 시점에 humanoid-01 만 시운전 완료라 quadruped-01 은 «시운전이 끝나지 않았다» 로 불가. 제출 «배정됨», 배정된 기체 humanoid-01, 실행 목록의 «코드 정의» 행이 실제 시간으로 `PHYSICALLY_DONE` 까지(상한 90초). 런처의 실제 시각 따라잡기, `HostClock.SYSTEM`, P4 스트림 재부착을 함께 지나는 유일한 경로다.
- README: 셀 대역, 운영 영역, 띄우기(터미널 넷, mission-host 는 토큰 둘을 빼고), «알아 둘 것» 에 한계 넷(mimic gRPC 고정 포트·모든 인터페이스, 실행 호스트 REST 무인증, `pick_place` 실패율 약 6%, 운영자 보류 해소 수단 없음).
- 계획 검토(1회)가 잡아 스파이크에 더한 것: 호스트 잠금을 공정 `ReentrantLock` 으로(제출이 늦어 운영 서비스가 재조회할 때 재조회가 제출을 앞지르지 않게, 가상 스레드가 `synchronized` 를 기다리며 캐리어를 붙잡지 않게), `Site.start` 가 실패하면 연 것을 닫음, 저장소에 없는 문서를 가리키던 «계약 §N» 을 커밋되는 JSON 계약 문서로, 용어 다섯 곳, README 의 실패율 문구, Playwright 시간 한도 420초.
- 실측: 새 시험 site +8(25), mission-host 14, ops-service +37(160), e2e +3(40), ui +19(74), Playwright 1. 결함 주입 28건.
- 문장: 커밋·PR·문서 문장은 Codex 와 Fable 초안을 취합한다.

**작업 위치 규칙(필수):**
- 모든 작업은 picasso-ops 워크트리 `C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a`(브랜치 `feat/s3a-execution-host`)에서 한다. picasso-ops 메인 체크아웃과 다른 저장소는 건드리지 않는다. 하위 에이전트의 Bash 는 호출마다 작업 디렉터리가 돌아가므로 명령마다 `cd <워크트리> &&` 를 붙이거나 `git -C` 를 쓴다.
- `./gradlew --stop` 금지(데몬 풀이 다른 체크아웃과 공유된다). 같은 워크트리에서 Gradle 을 겹쳐 돌리지 않는다. site·ops-service·e2e 시험은 Testcontainers 로 JVM 마다 Postgres 컨테이너를 띄우므로 Docker 데몬이 떠 있어야 한다. Playwright 는 compose 의 Postgres(`127.0.0.1:55432`)와 고정 포트(8781~8785, 4173)를 쓰므로 다른 Playwright·런처와 겹쳐 돌리지 않는다. Bash 도구의 시간 한도(600초)를 넘는 명령은 백그라운드로 돌리고 끝났다는 알림을 받은 뒤 다음 명령을 돌린다.
- `git add -A` 금지. 파일을 이름으로 더한다.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다. Expected 의 수는 `<testcase>` 수다. 수는 `PYTHONUTF8=1 python -c "import glob,xml.etree.ElementTree as E;print(sum(len(list(E.parse(f).getroot().iter('testcase'))) for f in glob.glob('C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a/MOD/build/test-results/test/*.xml')))"` 로 센다(MOD 자리에 모듈 이름). vitest 는 `npm test` 출력의 `Tests` 줄로 센다.
- 이 저장소는 LF 다(`.gitattributes` 의 `eol=lf`). 뽑아 둔 파일은 그대로 복사한다.
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. 커밋 메시지는 heredoc(`git commit -F - <<'EOF'`)으로 쓴다. 형식 훅이 제목이 `type(scope): 명사구` 가 아니거나 트레일러가 없거나 겹화살괄호가 있으면 막는다.
- 이 계획의 코드는 스크래치 스파이크(`C:/Users/Eisen/AppData/Local/Temp/s3a`, 브랜치 `spike/s3a`, HEAD `6f372de`)에서 시험, 전체 빌드, Playwright, 결함 주입을 다 돌린 것이다. 묶음이 끝날 때마다 커밋된 파일을 스파이크와 기계 대조한다(`s3a-cmp.sh`).
- 실행 방식: 묶음(Task 1·2 / Task 3·4 / Task 5 / Task 6·7)마다 구현 하위 에이전트 1명(`model: "sonnet"`), 결함 주입(Task 8)과 검토·PR 은 컨트롤러.
- **블록을 손으로 옮겨 적지 않는다.** 컨트롤러가 이 계획의 블록을 기계로 뽑아 `C:/Users/Eisen/AppData/Local/Temp/s3a-patches/` 에 두었다. 새 파일은 `s3a-patches/files/<경로>` 를 워크트리의 같은 경로로 `cp` 하고, 기존 파일은 `s3a-patches/<이름>.patch` 를 `git apply --check` 로 본 뒤 `git apply` 한다. 아래 블록은 읽고 검토하기 위한 것이다. 뽑은 파일이 없으면 멈추고 보고한다.

---

## Chunk 1: 구현

### Task 0: 워크트리, 서브모듈, 기준선

**Files:** `picasso`(서브모듈 포인터)

- [ ] **Step 1: 워크트리와 서브모듈**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" && git fetch -q origin
git -C "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" worktree add "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" -b feat/s3a-execution-host docs/s3a-execution-host-design
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git submodule update --init -q && git -C picasso fetch -q origin main && git -C picasso checkout -q 1e3f4ae && git -C picasso log --oneline -1
```
Expected: 워크트리의 기준은 `docs/s3a-execution-host-design`(스펙·계획 커밋, 그 아래 `f7ca937`). 서브모듈이 `1e3f4ae Merge pull request #83`.

- [ ] **Step 2: 서브모듈 포인터 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git add picasso && git commit -F - <<'EOF'
chore(picasso): 서브모듈을 P4 머지 커밋으로 이동

- picasso `1e3f4ae`(PR #83, 스트림 재부착과 mimic 엔진 잠금)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **Step 3: 기준선 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && ./gradlew :site:test :ops-service:test :e2e:test -q
```
Expected: site 17, ops-service 123, e2e 37, 실패 0. 백그라운드로 돌린다.

- [ ] **Step 4: 대조 도구와 뽑은 블록**

`C:/Users/Eisen/AppData/Local/Temp/s3a-cmp.sh` 와 `C:/Users/Eisen/AppData/Local/Temp/s3a-patches/`(패치 7개, `files/` 아래 새 파일 37개)가 있는지 본다. 없으면 멈추고 보고한다.

### Task 1: site: 시계 맞춤, gRPC 포트, 셀 대역

**Files:**
- Create: `site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt`, `site/src/test/kotlin/dev/picasso/ops/site/SiteCellTest.kt`
- Modify: `.env`, `site/src/main/kotlin/dev/picasso/ops/site/Site.kt`, `site/src/main/kotlin/dev/picasso/ops/site/SiteConfig.kt`, `site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt`, `site/src/test/kotlin/dev/picasso/ops/site/SiteConfigTest.kt`, `site/src/test/kotlin/dev/picasso/ops/site/SiteTest.kt`

- [ ] **Step 1: 새 파일 2개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p site/src/main/kotlin/dev/picasso/ops/site && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt" site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p site/src/test/kotlin/dev/picasso/ops/site && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/site/src/test/kotlin/dev/picasso/ops/site/SiteCellTest.kt" site/src/test/kotlin/dev/picasso/ops/site/SiteCellTest.kt
```

`site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt`:

```kotlin
package dev.picasso.ops.site

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.picasso.mimic.cli.MimicCli
import dev.picasso.mimic.engine.TaskState
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant

/**
 * 셀 대역의 고정 픽스처(S3a 스펙 §6.3). 제시 자리는 늘 점유이고 자재가 바뀌지 않는다(공급이 끝나지 않는다).
 * 슬롯은 처음에 비어 있다.
 *
 * @param presentations 제시 자리 id 와 그 자리의 자재.
 * @param slots 슬롯 id. 순서가 `GET /cell` 의 순서다.
 */
data class CellFixture(val presentations: Map<String, String>, val slots: List<String>) {
    init {
        require(slots.distinct().size == slots.size) { "슬롯 id 가 겹친다: $slots" }
        require(presentations.keys.none { it in slots }) { "제시 자리와 슬롯이 같은 id 를 쓴다" }
    }

    companion object {
        /** 런처와 시험이 쓰는 세트. 슬롯 넷이 PrepareSequencedRack 작업 지시 하나의 단위 수 상한이다. */
        val STANDARD = CellFixture(
            presentations = linkedMapOf("SEQ-IN-02.BIN-A" to "ENGINE-COVER-A"),
            slots = listOf("RACK-204.S01", "RACK-204.S02", "RACK-204.S03", "RACK-204.S04"),
        )
    }
}

/**
 * 셀 대역의 자리 하나. [observedAt] 이 `null` 이면 시각을 주지 않는 신호이고 읽은 순간이 그 시각이다(제시 자리).
 * 슬롯은 채운 가상 시각을 든다.
 */
data class CellPlace(val id: String, val occupied: Boolean, val material: String?, val observedAt: Instant?)

/** 셀 대역의 한 순간. 불변이며 훑기마다 통째로 갈아 끼운다. `GET /cell` 의 본문 모양이다. */
data class CellSnapshot(val presentations: List<CellPlace>, val slots: List<CellPlace>)

/**
 * 셀 대역(S3a 스펙 §6.3, 결정 6, T4). 기체가 보고한 배치에서 슬롯을 채우는 **대역**이며 독립 설비 확인이 아니다.
 *
 * ## 훑기
 *
 * [scan] 은 시계를 민 직후마다 mimic 엔진 잠금(`MimicServer.exclusive`) 아래에서 기체들의 태스크를 훑는다. 새로
 * `SUCCEEDED` 가 된 `pick_place` 를 찾으면 그 `destination` 슬롯을 «점유, 자재 = `object_id` 제시 자리의 자재, 관측 시각
 * = 지금 가상 시각» 으로 채운다. 같은 태스크는 한 번만 처리한다. 슬롯을 비우지 않는다(스펙 §12).
 *
 * 발행 경로를 감싸지 않는 이유: 감싸면 mimic 의 전송 장애 주입(S4)에 셀 대역이 같이 걸린다. 시계를 미는 쪽이 현장이므로
 * 민 직후에 엔진을 직접 읽는 것이 장애와 무관하다.
 *
 * `object_id` 가 제시 자리가 아니면 자재를 모르는 채 점유로 채운다(기체가 무언가 놓았다는 보고는 맞으므로).
 * `destination` 이 픽스처의 슬롯이 아니면 셀 밖이라 무시한다.
 *
 * ## 내는 곳
 *
 * 루프백 JDK `HttpServer` 의 `GET /cell` 하나다. 처리 스레드는 [snapshot] 만 읽고 엔진에 닿지 않는다.
 *
 * @param port 0 이면 무작위(시험).
 */
class SiteCell(
    private val mimic: MimicCli.Started,
    private val fixture: CellFixture = CellFixture.STANDARD,
    port: Int = 0,
) : AutoCloseable {

    /** 이미 처리한 태스크. 기체마다 태스크 id 공간이 따로이므로 쌍으로 든다. */
    private val seen = mutableSetOf<Pair<String, String>>()

    @Volatile
    var snapshot: CellSnapshot = CellSnapshot(
        presentations = fixture.presentations.map { (id, material) -> CellPlace(id, true, material, null) },
        slots = fixture.slots.map { CellPlace(it, false, null, null) },
    )
        private set

    private val json = ObjectMapper()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0).apply {
        createContext("/cell", ::handle)
        start()
    }

    /** 열린 포트. 시험이 0 을 주면 여기서 읽는다. */
    val port: Int get() = server.address.port

    /** 시계를 민 직후에 부른다. 엔진 잠금은 재진입되므로 이미 잠금 안에서 불러도 된다. */
    fun scan() = mimic.server.exclusive {
        val filled = snapshot.slots.associateBy { it.id }.toMutableMap()
        var changed = false
        for (robotId in mimic.robotIds.sorted()) {
            val instance = mimic.instance(robotId) ?: continue
            val now = instance.clock.now()
            for (task in instance.tasks.all) {
                if (task.skillType != PICK_PLACE || task.machine.state != TaskState.SUCCEEDED) continue
                if (!seen.add(robotId to task.taskId)) continue
                val parameters = task.machine.parameters.associate { it.key to it.stringValue }
                val destination = parameters[P_DESTINATION] ?: continue
                if (destination !in filled) continue
                val material = parameters[P_OBJECT]?.let { fixture.presentations[it] }
                filled[destination] = CellPlace(destination, true, material, now)
                changed = true
            }
        }
        if (changed) {
            snapshot = snapshot.copy(slots = fixture.slots.map { filled.getValue(it) })
        }
    }

    private fun handle(exchange: HttpExchange) = try {
        respond(exchange)
    } finally {
        exchange.close()
    }

    private fun respond(exchange: HttpExchange) {
        val status: Int
        val body: ByteArray
        when {
            exchange.requestURI.path != "/cell" -> {
                status = 404
                body = ByteArray(0)
            }
            exchange.requestMethod != "GET" -> {
                exchange.responseHeaders.add("Allow", "GET")
                status = 405
                body = ByteArray(0)
            }
            else -> {
                exchange.responseHeaders.add("Content-Type", "application/json")
                status = 200
                body = json.writeValueAsBytes(wire(snapshot))
            }
        }
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.write(body)
    }

    override fun close() = server.stop(0)

    /**
     * 본문 모양. 시각은 ISO-8601 문자열이다. Jackson 의 날짜 모듈에 기대지 않고 여기서 적는다. 모듈이 클래스패스에서
     * 빠지면 시각이 숫자로 바뀌어 읽는 쪽(실행 호스트)이 조용히 못 읽는다.
     */
    private fun wire(snapshot: CellSnapshot): Map<String, Any> = linkedMapOf(
        "presentations" to snapshot.presentations.map(::wirePlace),
        "slots" to snapshot.slots.map(::wirePlace),
    )

    private fun wirePlace(place: CellPlace): Map<String, Any?> = linkedMapOf(
        "id" to place.id,
        "occupied" to place.occupied,
        "material" to place.material,
        "observedAt" to place.observedAt?.toString(),
    )

    private companion object {
        /** 계약 카탈로그의 스킬 이름과 파라미터 키. */
        const val PICK_PLACE = "pick_place"
        const val P_OBJECT = "object_id"
        const val P_DESTINATION = "destination"
    }
}
```

`site/src/test/kotlin/dev/picasso/ops/site/SiteCellTest.kt`:

```kotlin
package dev.picasso.ops.site

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.client.PicassoClient
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.mimic.cli.MimicCli
import dev.picasso.mimic.engine.TaskState
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 셀 대역(S3a 스펙 §6.3). registry 없이 mimic 하나와 셀 대역만 띄우고, 기체에 `pick_place` 를 직접 걸어 가상 시계로 끝낸다.
 * 시계를 미는 순서는 [Site.advance] 와 같다(민 직후 훑기).
 */
class SiteCellTest {

    private val root = Path.of("..").toAbsolutePath().normalize()
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    /** mimic 하나와 셀 대역, 그리고 그 mimic 에 붙은 클라이언트. */
    private inner class Bench : AutoCloseable {
        val mimic: MimicCli.Started = checkNotNull(
            MimicCli().start(
                robots = mapOf(
                    HUMANOID to root.resolve("picasso/profile/profiles/humanoid-a.json"),
                    QUADRUPED to root.resolve("picasso/profile/profiles/quadruped-b.json"),
                ),
                schema = root.resolve(SiteConfig.PROFILE_SCHEMA),
                port = 0,
                virtual = true,
                seed = 0L,
                err = System.err,
            ),
        ) { "mimic 기동 거부" }
        val cell = SiteCell(mimic)
        private val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", mimic.server.port).usePlaintext().build()
        val client = PicassoClient(channel, "site-cell-test")

        init {
            // EPOCH 에서 멀리 떨어진 시각에서 시작한다. 관측 시각을 EPOCH 로 적는 결함이 «거의 같은 값» 으로 숨지 않게 한다.
            advance(Duration.between(Instant.EPOCH, START))
        }

        fun now(): Instant = mimic.server.exclusive { mimic.instance(HUMANOID)!!.clock.now() }

        fun advance(by: Duration) = mimic.server.exclusive {
            mimic.server.advance(by)
            cell.scan()
        }

        fun pickPlace(robotId: String, taskId: String, objectId: String, destination: String) {
            val response = client.start(
                robotId, taskId, 1, "pick_place",
                listOf(
                    ParameterValue.newBuilder().setKey("object_id").setStringValue(objectId).build(),
                    ParameterValue.newBuilder().setKey("destination").setStringValue(destination).build(),
                ),
            )
            assertTrue(response.hasHandle(), "태스크 시작이 거부됐다: ${response.rejection}")
        }

        fun state(robotId: String, taskId: String): TaskState? =
            mimic.server.exclusive { mimic.instance(robotId)!!.tasks.find(taskId)?.machine?.state }

        /** 태스크가 종료할 때까지 5초씩 민다. 종료한 그 밀기 직후의 가상 시각을 돌려준다. */
        fun runToEnd(robotId: String, taskId: String): Instant {
            repeat(40) {
                advance(Duration.ofSeconds(5))
                if (state(robotId, taskId)?.isTerminal == true) return now()
            }
            error("태스크가 끝나지 않았다: ${state(robotId, taskId)}")
        }

        fun slot(id: String): CellPlace = cell.snapshot.slots.single { it.id == id }

        override fun close() {
            channel.shutdownNow()
            cell.close()
            mimic.server.shutdown()
        }
    }

    @Test
    fun `pick_place 가 성공하면 그 슬롯을 제시 자리의 자재로 채우고 관측 시각은 채운 가상 시각이다`() {
        Bench().use { bench ->
            assertEquals(false, bench.slot(S01).occupied)
            bench.pickPlace(HUMANOID, "JO-1#$S01", SOURCE, S01)

            val doneAt = bench.runToEnd(HUMANOID, "JO-1#$S01")

            assertEquals(TaskState.SUCCEEDED, bench.state(HUMANOID, "JO-1#$S01"))
            assertEquals(CellPlace(S01, true, MATERIAL, doneAt), bench.slot(S01))
            // 다른 슬롯과 제시 자리는 그대로다.
            assertTrue(bench.cell.snapshot.slots.filter { it.id != S01 }.none { it.occupied })
            assertEquals(listOf(CellPlace(SOURCE, true, MATERIAL, null)), bench.cell.snapshot.presentations)
        }
    }

    @Test
    fun `같은 태스크는 한 번만 채운다`() {
        Bench().use { bench ->
            bench.pickPlace(HUMANOID, "JO-1#$S01", SOURCE, S01)
            val doneAt = bench.runToEnd(HUMANOID, "JO-1#$S01")

            // 끝난 태스크가 엔진에 남아 있는 채로 더 민다. 다시 처리하면 관측 시각이 뒤로 옮겨진다.
            bench.advance(Duration.ofSeconds(30))
            bench.advance(Duration.ofSeconds(30))

            assertEquals(doneAt, bench.slot(S01).observedAt)
        }
    }

    @Test
    fun `셀 밖 목적지와 제시 자리가 아닌 출발지는 각자 규칙대로 다룬다`() {
        Bench().use { bench ->
            // 셀 밖 목적지: 아무 슬롯도 안 바뀐다.
            bench.pickPlace(HUMANOID, "JO-2#dock-3", SOURCE, "dock-3")
            bench.runToEnd(HUMANOID, "JO-2#dock-3")
            assertTrue(bench.cell.snapshot.slots.none { it.occupied })

            // 제시 자리가 아닌 출발지: 놓였다는 보고는 맞으므로 점유로 채우되 자재는 모른다.
            bench.pickPlace(HUMANOID, "JO-3#$S02", "box-7", S02)
            val doneAt = bench.runToEnd(HUMANOID, "JO-3#$S02")
            assertEquals(CellPlace(S02, true, null, doneAt), bench.slot(S02))
        }
    }

    @Test
    fun `GET cell 은 제시 자리와 슬롯을 내고 다른 방법과 경로는 받지 않는다`() {
        Bench().use { bench ->
            bench.pickPlace(HUMANOID, "JO-1#$S01", SOURCE, S01)
            val doneAt = bench.runToEnd(HUMANOID, "JO-1#$S01")

            val base = "http://127.0.0.1:${bench.cell.port}"
            val got = http.send(HttpRequest.newBuilder(URI.create("$base/cell")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, got.statusCode())
            assertEquals("application/json", got.headers().firstValue("Content-Type").orElse(null))
            val body = json.readTree(got.body())
            assertEquals(setOf("presentations", "slots"), body.fieldNames().asSequence().toSet())

            val source = body["presentations"].single()
            assertEquals(SOURCE, source["id"].asText())
            assertEquals(true, source["occupied"].asBoolean())
            assertEquals(MATERIAL, source["material"].asText())
            assertTrue(source["observedAt"].isNull)

            val slots = body["slots"]
            assertEquals(CellFixture.STANDARD.slots, slots.map { it["id"].asText() })
            assertEquals(doneAt.toString(), slots[0]["observedAt"].asText())
            assertEquals(MATERIAL, slots[0]["material"].asText())
            assertEquals(false, slots[1]["occupied"].asBoolean())
            assertTrue(slots[1]["material"].isNull)
            assertTrue(slots[1]["observedAt"].isNull)

            val post = http.send(
                HttpRequest.newBuilder(URI.create("$base/cell")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(405, post.statusCode())
            val other = http.send(HttpRequest.newBuilder(URI.create("$base/cells")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(404, other.statusCode())
        }
    }

    @Test
    fun `처음 스냅숏은 슬롯이 비고 제시 자리가 찬 픽스처 그대로다`() {
        Bench().use { bench ->
            val snapshot = bench.cell.snapshot
            assertEquals(CellFixture.STANDARD.slots.map { CellPlace(it, false, null, null) }, snapshot.slots)
            assertNotNull(snapshot.presentations.singleOrNull { it.id == SOURCE && it.occupied })
            assertNull(snapshot.presentations.single().observedAt)
        }
    }

    private companion object {
        const val HUMANOID = "humanoid-01"
        const val QUADRUPED = "quadruped-01"
        const val SOURCE = "SEQ-IN-02.BIN-A"
        const val MATERIAL = "ENGINE-COVER-A"
        const val S01 = "RACK-204.S01"
        const val S02 = "RACK-204.S02"
        val START: Instant = Instant.parse("2026-10-08T00:00:00Z")
    }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task1.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task1.patch"
```

```diff
diff --git a/.env b/.env
index 6a43a0e..7d602c7 100644
--- a/.env
+++ b/.env
@@ -1,10 +1,13 @@
 # picasso-ops 로컬 PoC 값. 인증을 생략하는 PoC 이며(스펙 §1) 이 기계 밖에서 쓰지 않는다.
-# 런처(site/)·운영 서비스(ops-service/)·시험·CI 가 이 파일 하나를 읽는다(스펙 §4·§6).
+# 런처(site/)·운영 서비스(ops-service/)·실행 호스트(mission-host/)·시험·CI 가 이 파일 하나를 읽는다(스펙 §4·§6).
 SITE_ID=site-01
 PICASSO_DB_URL=jdbc:postgresql://127.0.0.1:55432/picasso
 PICASSO_DB_USER=picasso
 PICASSO_DB_PASSWORD=picasso
 REGISTRY_PORT=8781
 OPS_PORT=8782
+MIMIC_GRPC_PORT=8783
+SITE_CELL_PORT=8784
+HOST_PORT=8785
 PICASSO_OPERATOR_TOKEN=local-operator-token
 PICASSO_INGEST_TOKEN=local-ingest-token
diff --git a/site/src/main/kotlin/dev/picasso/ops/site/Site.kt b/site/src/main/kotlin/dev/picasso/ops/site/Site.kt
index a7af576..a3d3bfd 100644
--- a/site/src/main/kotlin/dev/picasso/ops/site/Site.kt
+++ b/site/src/main/kotlin/dev/picasso/ops/site/Site.kt
@@ -11,23 +11,61 @@ import org.springframework.boot.builder.SpringApplicationBuilder
 import org.springframework.boot.web.context.WebServerApplicationContext
 import org.springframework.context.ConfigurableApplicationContext
 import java.time.Duration
+import java.time.Instant
 
 /**
  * 띄운 가짜 현장 하나. registry 와 mimic 을 이 프로세스 안에 둔다(스펙 §6).
  *
  * mimic CLI 를 쓰지 않는 이유는 스펙 §6 첫 문단이다. CLI 에는 시간을 진행시키는 루프가 없고,
  * registry 는 실행 jar 가 없으며 기동 때 Flyway 를 돌리지 않는다.
+ *
+ * ## 시계(S3a 스펙 §6.1, T1)
+ *
+ * 기동 직후 mimic 가상 시계를 실제 시각까지 한 번 민다. 미들웨어는 E2 시간 윈도우의 기준 시각을 mimic 응답 헤더의
+ * `state_as_of` 에서 가져오고 마감은 자기 시계로 보므로, EPOCH 에서 시작하면 둘이 어긋난다. 그 뒤 런처는 [advanceTo] 로
+ * 실제 시각을 따라잡는다. [now]·시계 밀기·셀 대역의 훑기·[teach] 는 모두 `MimicServer.exclusive` 아래에서 돈다. 가상
+ * 시계는 다른 스레드에서 읽을 때 최신 값이 보인다는 보장이 없으므로 잠금이 그 가시성도 맡는다.
  */
 class Site private constructor(
     private val registry: ConfigurableApplicationContext,
     private val mimic: MimicCli.Started,
+    private val cell: SiteCell,
     private val runner: AutoCloseable,
     val registryUrl: String,
     val robotIds: Set<String>,
 ) : AutoCloseable {
 
-    /** mimic 의 가상 시계를 민다. 상태 발행이 이 시계로 정해지고, 상태 발행이 곧 생존 보고다. */
-    fun advance(by: Duration) = mimic.server.advance(by)
+    /** 기체들이 함께 보는 가상 시계. `MimicCli` 가 시계 하나를 만들어 모든 기체에 넘긴다. */
+    private val clock = requireNotNull(mimic.instance(robotIds.first())) { "기체가 없는 현장이다" }.clock
+
+    /** mimic gRPC 가 열린 포트. 시험은 0 을 주고 여기서 읽는다(S3a 스펙 §6.2). */
+    val mimicPort: Int get() = mimic.server.port
+
+    /** 셀 대역 `GET /cell` 이 열린 루프백 포트(S3a 스펙 §6.3). */
+    val cellPort: Int get() = cell.port
+
+    /** 셀 대역의 지금 스냅숏. `GET /cell` 이 내는 것과 같다. */
+    val cellSnapshot: CellSnapshot get() = cell.snapshot
+
+    /** mimic 의 가상 시각. */
+    fun now(): Instant = mimic.server.exclusive { clock.now() }
+
+    /**
+     * mimic 의 가상 시계를 민다. 상태 발행이 이 시계로 정해지고, 상태 발행이 곧 생존 보고다. 민 직후 같은 잠금 아래에서
+     * 셀 대역이 태스크를 훑는다.
+     *
+     * 시험용으로 남긴다. 이것으로 밀면 가상 시각이 실제 시각보다 앞서며, 그때 실행 호스트도 이 현장의 시계를 써야 한다.
+     */
+    fun advance(by: Duration) = mimic.server.exclusive {
+        mimic.server.advance(by)
+        cell.scan()
+    }
+
+    /** [target] 이 가상 시각보다 뒤일 때만 그 차이만큼 민다. 같거나 앞이면 아무것도 하지 않는다(되감지 않는다). */
+    fun advanceTo(target: Instant) = mimic.server.exclusive {
+        val now = clock.now()
+        if (target.isAfter(now)) advance(Duration.between(now, target))
+    }
 
     /** registry 만 멈춘다. 운영 서비스가 «모름» 을 보이는지 볼 때 쓴다(스펙 §3 S1a). 실행기는 집기 실패를 로그에 남기며 폴링을 이어 간다. */
     fun stopRegistry() = registry.close()
@@ -38,7 +76,8 @@ class Site private constructor(
      */
     fun teach(robotId: String, siteNames: List<String>) {
         val instance = requireNotNull(mimic.instance(robotId)) { "이 현장에 없는 기체다: $robotId" }
-        instance.knownSiteNames = siteNames
+        // 읽는 쪽이 advance 안의 상태 발행이므로 같은 잠금 아래에서 쓴다.
+        mimic.server.exclusive { instance.knownSiteNames = siteNames }
     }
 
     override fun close() {
@@ -46,9 +85,13 @@ class Site private constructor(
             runner.close()
         } finally {
             try {
-                mimic.server.shutdown()
+                cell.close()
             } finally {
-                if (registry.isActive) registry.close()
+                try {
+                    mimic.server.shutdown()
+                } finally {
+                    if (registry.isActive) registry.close()
+                }
             }
         }
     }
@@ -82,7 +125,8 @@ class Site private constructor(
                 MimicCli().start(
                     robots = config.roster.associate { it.robotId to config.profile(it) },
                     schema = config.schema,
-                    port = 0,
+                    // 고정하면 인증 없는 기체 제어 API 표면의 위치가 정해진다(S3a 스펙 §6.2, §12). 실행 호스트가 붙으려면 알아야 한다.
+                    port = config.mimicPort,
                     virtual = true,
                     seed = 0L,
                     err = err,
@@ -102,14 +146,38 @@ class Site private constructor(
             // ⑤ 현장에서 티칭한 명칭을 기체에 넣는다. 명칭은 프로파일이 아니라 현장의 것이다(ADR 35).
             config.roster.forEach { mimic.instance(it.robotId)?.knownSiteNames = it.siteNames }
 
-            // ⑥ 개정판 시험 실행기. 적재 토큰을 가진 것이 이 프로세스뿐이다(P2·S1d 스펙 §7). 시험에 쓰는 mimic 은
+            // ⑥ 셀 대역. 실행 호스트가 루프백 HTTP 로 읽는다(S3a 스펙 §6.3).
+            val cell = try {
+                SiteCell(mimic, config.cell, config.cellPort)
+            } catch (e: Exception) {
+                mimic.server.shutdown()
+                registry.close()
+                throw e
+            }
+
+            // ⑦ 리비전 시험 실행기. 적재 토큰을 가진 것이 이 프로세스뿐이다(P2·S1d 스펙 §7). 시험에 쓰는 mimic 은
             // 실행기가 시험마다 따로 띄우므로 현장 기체의 보고와 상태를 바꾸지 않는다.
-            val runner = RevisionTestRunner(
-                HttpTestDesk(registryUrl, config.ingestToken),
-                RevisionSuites(config.schema),
-                RUNNER_NAME,
-            ).start(RUNNER_INTERVAL)
-            return Site(registry, mimic, runner, registryUrl, mimic.robotIds)
+            val runner = try {
+                RevisionTestRunner(
+                    HttpTestDesk(registryUrl, config.ingestToken),
+                    RevisionSuites(config.schema),
+                    RUNNER_NAME,
+                ).start(RUNNER_INTERVAL)
+            } catch (e: Exception) {
+                cell.close()
+                mimic.server.shutdown()
+                registry.close()
+                throw e
+            }
+            // ⑧ 가상 시계를 실제 시각까지 한 번 민다(S3a 스펙 §6.1). 한 번에 크게 밀어도 된다(MimicServer.advance).
+            val site = Site(registry, mimic, cell, runner, registryUrl, mimic.robotIds)
+            try {
+                site.advanceTo(Instant.now())
+            } catch (e: Exception) {
+                site.close()
+                throw e
+            }
+            return site
         }
 
         /** 실행기 이름. 시험 결과의 실행 주체로 화면에 보인다. */
diff --git a/site/src/main/kotlin/dev/picasso/ops/site/SiteConfig.kt b/site/src/main/kotlin/dev/picasso/ops/site/SiteConfig.kt
index c730e0d..296d6c0 100644
--- a/site/src/main/kotlin/dev/picasso/ops/site/SiteConfig.kt
+++ b/site/src/main/kotlin/dev/picasso/ops/site/SiteConfig.kt
@@ -8,6 +8,9 @@ data class DbConfig(val url: String, val user: String, val password: String)
  * 런처 설정. 값은 루트 `.env` 에서 환경 변수로 온다(스펙 §4). 사이트 id 와 기체 명부는 한 출처에서 나온다(스펙 §6 ④).
  *
  * @param registryPort 0 이면 무작위 포트(시험).
+ * @param mimicPort mimic gRPC 포트. 0 이면 무작위(시험). 실행 호스트가 이 포트로 붙는다(S3a 스펙 §6.2).
+ * @param cellPort 셀 대역 `GET /cell` 의 루프백 포트. 0 이면 무작위(시험).
+ * @param cell 셀 대역의 제시 자리와 슬롯. 현장마다 다를 값이라 설정에 두고, 런처는 [CellFixture.STANDARD] 를 쓴다.
  */
 data class SiteConfig(
     val root: Path,
@@ -17,6 +20,9 @@ data class SiteConfig(
     val operatorToken: String,
     val ingestToken: String,
     val roster: List<RosterEntry>,
+    val mimicPort: Int = 0,
+    val cellPort: Int = 0,
+    val cell: CellFixture = CellFixture.STANDARD,
 ) {
     val schema: Path get() = root.resolve(PROFILE_SCHEMA)
 
@@ -29,16 +35,19 @@ data class SiteConfig(
         fun fromEnv(env: Map<String, String>, root: Path): SiteConfig {
             fun need(key: String): String = env[key]?.trim()?.takeIf { it.isNotEmpty() }
                 ?: throw IllegalArgumentException("환경 변수 $key 가 없다(루트 .env 를 확인)")
-            val port = need("REGISTRY_PORT")
+            fun port(key: String): Int = need(key).let {
+                it.toIntOrNull() ?: throw IllegalArgumentException("$key 가 정수가 아니다: $it")
+            }
             return SiteConfig(
                 root = root,
                 siteId = need("SITE_ID"),
                 db = DbConfig(need("PICASSO_DB_URL"), need("PICASSO_DB_USER"), need("PICASSO_DB_PASSWORD")),
-                registryPort = port.toIntOrNull()
-                    ?: throw IllegalArgumentException("REGISTRY_PORT 가 정수가 아니다: $port"),
+                registryPort = port("REGISTRY_PORT"),
                 operatorToken = need("PICASSO_OPERATOR_TOKEN"),
                 ingestToken = need("PICASSO_INGEST_TOKEN"),
                 roster = RobotRoster.read(root.resolve(ROSTER)),
+                mimicPort = port("MIMIC_GRPC_PORT"),
+                cellPort = port("SITE_CELL_PORT"),
             )
         }
     }
diff --git a/site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt b/site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt
index 7b3480c..b69aa5c 100644
--- a/site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt
+++ b/site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt
@@ -2,13 +2,14 @@ package dev.picasso.ops.site
 
 import java.nio.file.Path
 import java.time.Duration
+import java.time.Instant
 import java.util.concurrent.Executors
 import java.util.concurrent.TimeUnit
 
 /**
- * 실제 1초마다 가상 1초를 민다(1:1, 스펙 §6 ③). 비율을 바꾸면 운영 서비스의 연결 기준 시간 버전 1 의 90초와
- * 허용 범위 하한 60초(S2 스펙 §5)도 다시 정한다.
- * 주기와 전진량이 같은 값이어야 1:1 이므로 하나로 둔다.
+ * 가상 시계를 실제 시각까지 따라잡게 미는 주기(S3a 스펙 §6.1). 밀 때마다 실제 시각까지 미므로 가상 시각은 실제 시각보다
+ * 앞서지 않고 차이는 한 주기 이내다. 그래서 시간 흐름이 1:1 이다(스펙 §6 ③). 1:1 이 깨지면 운영 서비스의 연결 기준 시간
+ * 버전 1 의 90초와 허용 범위 하한 60초(S2 스펙 §5)도 다시 정한다.
  */
 val TICK: Duration = Duration.ofSeconds(1)
 
@@ -17,13 +18,14 @@ fun main() {
     val config = SiteConfig.fromEnv(System.getenv(), root)
     val site = Site.start(config)
     println("registry: ${site.registryUrl} (site=${config.siteId})")
-    println("mimic: ${site.robotIds.sorted()}")
+    println("mimic: ${site.robotIds.sorted()} (gRPC 포트 ${site.mimicPort})")
+    println("셀 대역: http://127.0.0.1:${site.cellPort}/cell")
     // uplink 가 거절 결과를 버리고 registry 도 남기지 않아서, 선언 전 보고의 거절은 어디에도 안 보인다.
     println("선언 전 mimic 의 생존 보고는 registry 가 거절하며 이 로그에도 남지 않는다. 기체를 선언하면 보고가 붙는다")
 
     val ticker = Executors.newSingleThreadScheduledExecutor()
     ticker.scheduleAtFixedRate(
-        { runCatching { site.advance(TICK) }.onFailure { System.err.println("시간 진행 실패: $it") } },
+        { runCatching { site.advanceTo(Instant.now()) }.onFailure { System.err.println("시간 진행 실패: $it") } },
         TICK.toMillis(),
         TICK.toMillis(),
         TimeUnit.MILLISECONDS,
diff --git a/site/src/test/kotlin/dev/picasso/ops/site/SiteConfigTest.kt b/site/src/test/kotlin/dev/picasso/ops/site/SiteConfigTest.kt
index 641aca3..1464f7d 100644
--- a/site/src/test/kotlin/dev/picasso/ops/site/SiteConfigTest.kt
+++ b/site/src/test/kotlin/dev/picasso/ops/site/SiteConfigTest.kt
@@ -16,6 +16,8 @@ class SiteConfigTest {
         "PICASSO_DB_USER" to "u",
         "PICASSO_DB_PASSWORD" to "p",
         "REGISTRY_PORT" to "8781",
+        "MIMIC_GRPC_PORT" to "8783",
+        "SITE_CELL_PORT" to "8784",
         "PICASSO_OPERATOR_TOKEN" to "op",
         "PICASSO_INGEST_TOKEN" to "in",
     )
@@ -25,6 +27,9 @@ class SiteConfigTest {
         val config = SiteConfig.fromEnv(env, root)
         assertEquals("site-x", config.siteId)
         assertEquals(8781, config.registryPort)
+        assertEquals(8783, config.mimicPort)
+        assertEquals(8784, config.cellPort)
+        assertEquals(CellFixture.STANDARD, config.cell)
         assertEquals(DbConfig("jdbc:postgresql://h/db", "u", "p"), config.db)
         assertEquals(2, config.roster.size)
         assertEquals(root.resolve(SiteConfig.PROFILE_SCHEMA), config.schema)
@@ -39,6 +44,17 @@ class SiteConfigTest {
     @Test
     fun `포트가 정수가 아니면 기동하지 않는다`() {
         assertFailsWith<IllegalArgumentException> { SiteConfig.fromEnv(env + ("REGISTRY_PORT" to "x"), root) }
+        assertFailsWith<IllegalArgumentException> { SiteConfig.fromEnv(env + ("MIMIC_GRPC_PORT" to "x"), root) }
+        assertFailsWith<IllegalArgumentException> { SiteConfig.fromEnv(env + ("SITE_CELL_PORT" to "x"), root) }
+    }
+
+    @Test
+    fun `mimic 포트나 셀 대역 포트가 없으면 기동하지 않는다`() {
+        // 실행 호스트가 붙을 자리다. 빠진 채 무작위 포트로 뜨면 호스트가 엉뚱한 곳을 두드린다.
+        listOf("MIMIC_GRPC_PORT", "SITE_CELL_PORT").forEach { key ->
+            val e = assertFailsWith<IllegalArgumentException> { SiteConfig.fromEnv(env - key, root) }
+            assertTrue(key in e.message!!, e.message)
+        }
     }
 
     @Test
diff --git a/site/src/test/kotlin/dev/picasso/ops/site/SiteTest.kt b/site/src/test/kotlin/dev/picasso/ops/site/SiteTest.kt
index 31f5b28..2d16750 100644
--- a/site/src/test/kotlin/dev/picasso/ops/site/SiteTest.kt
+++ b/site/src/test/kotlin/dev/picasso/ops/site/SiteTest.kt
@@ -10,9 +10,11 @@ import java.net.http.HttpRequest
 import java.net.http.HttpResponse
 import java.nio.file.Path
 import java.time.Duration
+import java.time.Instant
 import kotlin.test.Test
 import kotlin.test.assertEquals
 import kotlin.test.assertFailsWith
+import kotlin.test.assertTrue
 
 /** 런처가 registry 와 mimic 을 띄우고, 시간을 밀면 생존 보고가 registry 에 닿는다(스펙 §6). */
 class SiteTest {
@@ -75,6 +77,38 @@ class SiteTest {
         }
     }
 
+    @Test
+    fun `기동 직후 가상 시각이 실제 시각까지 와 있고 advanceTo 는 앞으로만 민다`() {
+        PostgresSupport.reset()
+        val before = Instant.now()
+        Site.start(config()).use { site ->
+            val after = Instant.now()
+            val started = site.now()
+            assertTrue(!started.isBefore(before) && !started.isAfter(after), "기동 직후 가상 시각 $started 가 [$before, $after] 밖이다")
+
+            site.advanceTo(started.minusSeconds(60))
+            assertEquals(started, site.now(), "앞선 시각으로는 되감지 않는다")
+            site.advanceTo(started)
+            assertEquals(started, site.now())
+            site.advanceTo(started.plusSeconds(5))
+            assertEquals(started.plusSeconds(5), site.now())
+        }
+    }
+
+    @Test
+    fun `mimic 과 셀 대역이 포트를 열고 close 가 셀 대역도 닫는다`() {
+        PostgresSupport.reset()
+        val site = Site.start(config())
+        val cell = URI.create("http://127.0.0.1:${site.cellPort}/cell")
+        site.use {
+            assertTrue(site.mimicPort > 0)
+            val got = http.send(HttpRequest.newBuilder(cell).build(), HttpResponse.BodyHandlers.ofString())
+            assertEquals(200, got.statusCode(), got.body())
+            assertEquals(CellFixture.STANDARD.slots, json.readTree(got.body())["slots"].map { it["id"].asText() })
+        }
+        assertFailsWith<ConnectException> { http.send(HttpRequest.newBuilder(cell).build(), HttpResponse.BodyHandlers.ofString()) }
+    }
+
     @Test
     fun `registry 만 멈추면 그 주소가 답하지 않는다`() {
         PostgresSupport.reset()
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && ./gradlew :site:test -q
```
Expected: site 25, 실패 0.

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git add site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt site/src/test/kotlin/dev/picasso/ops/site/SiteCellTest.kt .env site/src/main/kotlin/dev/picasso/ops/site/Site.kt site/src/main/kotlin/dev/picasso/ops/site/SiteConfig.kt site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt site/src/test/kotlin/dev/picasso/ops/site/SiteConfigTest.kt site/src/test/kotlin/dev/picasso/ops/site/SiteTest.kt && git commit -F - <<'EOF'
feat(site): 가상 시계 실제 시각 맞춤, mimic gRPC 포트 고정, 셀 대역 추가

- `Site.now`·`advanceTo`, 기동 직후 실제 시각까지 밀기, 런처의 따라잡기
- `.env` 의 `MIMIC_GRPC_PORT`·`SITE_CELL_PORT`·`HOST_PORT`, 셀 대역 `SiteCell` 과 `GET /cell`

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 2: mission-host 모듈

**Files:**
- Create: `mission-host/build.gradle.kts`, `mission-host/src/main/kotlin/dev/picasso/ops/host/HostClock.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt`, `mission-host/src/main/resources/mission-host.properties`, `mission-host/src/test/kotlin/dev/picasso/ops/host/CellBandTest.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/HostEnvBoundaryTest.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/MissionHostTest.kt`
- Modify: `build.gradle.kts`, `settings.gradle.kts`

- [ ] **Step 1: 새 파일 12개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p mission-host && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/mission-host/build.gradle.kts" mission-host/build.gradle.kts
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/HostClock.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/HostClock.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/cell && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/web && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/web && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p mission-host/src/main/resources && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/mission-host/src/main/resources/mission-host.properties" mission-host/src/main/resources/mission-host.properties
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p mission-host/src/test/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/mission-host/src/test/kotlin/dev/picasso/ops/host/CellBandTest.kt" mission-host/src/test/kotlin/dev/picasso/ops/host/CellBandTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p mission-host/src/test/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt" mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p mission-host/src/test/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/mission-host/src/test/kotlin/dev/picasso/ops/host/HostEnvBoundaryTest.kt" mission-host/src/test/kotlin/dev/picasso/ops/host/HostEnvBoundaryTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p mission-host/src/test/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionHostTest.kt" mission-host/src/test/kotlin/dev/picasso/ops/host/MissionHostTest.kt
```

`mission-host/build.gradle.kts`:

```kotlin
// 실행 호스트(S3a 스펙 §7). picasso 미들웨어를 세워 mimic 기체에서 작업 지시를 실행한다. 운영 서비스가 picasso 를 쓰지 않는
// 경계(`checkNoPicassoOnMain`)를 지키도록 별도 프로세스로 둔다(결정 1). 그 검사는 이 모듈에 걸지 않는다.
plugins {
    application
}

application {
    mainClass.set("dev.picasso.ops.host.MissionHostApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
    implementation("dev.picasso:picasso")
    // picasso 가 client 를 implementation 으로 들므로 ClientRobotPort 에 넘길 PicassoClient 를 직접 의존한다.
    implementation("dev.picasso:client")

    // 운영 서비스와 같이 BOM 만 쓰고 Spring Boot 플러그인은 붙이지 않는다. 판이 picasso 카탈로그 밖으로 나가지 않는다.
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.module.kotlin)

    testImplementation(kotlin("test"))
    // 호스트 시험은 mimic 을 이 JVM 의 Netty 포트에 띄운다. 운영 배치와 같은 네트워크 채널을 지난다.
    testImplementation("dev.picasso:mimic")
    // 통합 시험은 registry·운영 서비스와 한 JVM 에 호스트를 띄우므로 JDBC 자동설정이 클래스패스에 온다. 호스트 시험도
    // 같은 조건에서 돌려 «DB 없이 뜬다» 를 매번 확인한다(MissionHostApplication 의 자동설정 제외).
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-jdbc")
    testRuntimeOnly(libs.postgresql)
}

tasks.withType<Test>().configureEach {
    // 시험이 저장소 파일을 읽는다. 선언하지 않으면 파일을 고쳐도 시험이 UP-TO-DATE 로 넘어간다.
    inputs.dir(rootProject.file("picasso/profile/profiles")).withPropertyName("profiles")
    inputs.dir(rootProject.file("picasso/profile/schema")).withPropertyName("profileSchema")
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/HostClock.kt`:

```kotlin
package dev.picasso.ops.host

import java.time.Instant

/**
 * 실행 호스트의 시계(S3a 스펙 §7.2). 미들웨어의 `now` 와 마지막 pump 의 시각이 이것으로 정해진다.
 *
 * 기본은 실제 시각이다. 런처의 현장은 가상 시계를 실제 시각까지 따라잡게 밀므로 둘이 한 주기 안으로 맞는다. 시험이
 * 현장 시계를 실제 시각보다 앞으로 밀면 이 빈을 현장 시계(`Site.now()`)로 바꿔 끼운다
 * ([MissionHostApplication.builder]). 미들웨어는 E2 시간 윈도우의 기준 시각을 mimic 응답 헤더에서 가져오고 마감은 이
 * 시계로 보므로, 둘이 어긋나면 셀 대역 신호가 윈도우 밖으로 읽힌다.
 */
fun interface HostClock {
    fun now(): Instant

    companion object {
        val SYSTEM = HostClock { Instant.now() }
    }
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt`:

```kotlin
package dev.picasso.ops.host

import dev.picasso.middleware.InspectAsset
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.JobResponse
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.Route
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.Unassigned
import dev.picasso.middleware.mission.InMemoryMissionCatalog
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.cell.CellBandSignals
import dev.picasso.ops.host.cell.CellSnapshot
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 스킬 적합 판정(S3a 스펙 §7.6). [UNKNOWN] 은 기체 케이퍼빌리티를 못 물어봤다는 뜻이고 적합이 아니다. */
enum class SkillFit { FIT, MISSING, UNKNOWN }

/**
 * 기체 하나의 호스트 판정(T3). 운영 서비스가 시운전·연결 판정과 합쳐 배정 가능을 낸다.
 *
 * @param missingSkills [skillFit] 이 [SkillFit.MISSING] 일 때 모자란 스킬. 그 밖에는 비어 있다.
 * @param runningExecutionId 이 기체에서 물리 상태가 정착하지 않은 실행. 없으면 `null`.
 * @param passed 스킬 적합이고 도는 실행이 없다.
 * @param reasons 통과하지 못한 이유. 화면에 그대로 보인다.
 */
data class HostEligibility(
    val robotId: String,
    val skillFit: SkillFit,
    val missingSkills: List<String>,
    val runningExecutionId: String?,
    val passed: Boolean,
    val reasons: List<String>,
)

/** 미들웨어 `Submission` 넷의 이름(S3a 스펙 §7.6). */
enum class SubmitResult { ACCEPTED, IDEMPOTENT, REJECTED, UNASSIGNED }

/** `assign` 이 기체마다 낸 미배정 사유. */
data class Refusal(val robotId: String, val reason: String)

/**
 * 제출의 결과.
 *
 * @param executionId·robotId ACCEPTED·IDEMPOTENT 일 때만 있다.
 * @param rejectionReason REJECTED 일 때만 있다.
 * @param refusals UNASSIGNED 일 때 `assign` 이 기체마다 낸 사유. 호스트가 먼저 뺀 기체는 여기 없고 [excluded] 에 있다.
 * @param excluded 호스트가 판정에서 빼 `assign` 에 넘기지 않은 기체와 그 판정.
 */
data class SubmitOutcome(
    val result: SubmitResult,
    val executionId: String?,
    val robotId: String?,
    val rejectionReason: String?,
    val refusals: List<Refusal>,
    val excluded: List<HostEligibility>,
)

/** 실행의 단위 하나. [reached] 는 단위가 이른 근거 등급이다. */
data class UnitView(val unitId: String, val skillType: String, val state: String, val reached: String)

/** 작업 응답 하나. 미들웨어 `JobResponse` 의 칸을 옮긴다. [residualHold] 는 남은 파지 상태의 종류 이름이다. */
data class JobResponseView(
    val jobResponseId: String,
    val version: Int,
    val physicalState: String,
    val requiredEvidence: String,
    val reachedEvidence: String,
    val completedUnits: List<String>,
    val unverifiedUnits: List<String>,
    val incompleteUnits: Map<String, String>,
    val inDoubtUnits: List<String>,
    val operatorRequired: Boolean,
    val residualHold: String,
    val blockedBy: List<String>,
    val connection: String,
)

/**
 * 실행 하나. 실행은 가변 객체라 호스트 잠금 안에서 이 모양으로 복사한다.
 *
 * @param missionVersion 임무 버전. `null` 이면 코드 정의다.
 * @param jobResponse 그 실행의 마지막 작업 응답. 아직 없으면 `null`.
 */
data class ExecutionView(
    val executionId: String,
    val jobOrderId: String,
    val workMasterId: String,
    val missionVersion: Int?,
    val robotId: String,
    val physicalState: String,
    val units: List<UnitView>,
    val jobResponse: JobResponseView?,
)

/**
 * `GET /host/executions` 의 본문. [instanceId] 는 미들웨어가 뜬 한 번을 가리킨다. 재기동하면 바뀌고 `exec-N` 은 1부터
 * 다시 센다. [pumpedAt] 은 마지막 pump 가 셀 대역을 읽고 잠금을 잡은 뒤의 호스트 시계 값이며 아직 한 번도 안 돌았으면 `null` 이다.
 */
data class ExecutionsView(val instanceId: String, val pumpedAt: Instant?, val executions: List<ExecutionView>)

/**
 * 미들웨어 실행 호스트(S3a 스펙 §7).
 *
 * ## 잠금 하나
 *
 * 미들웨어에는 스레드도 잠금도 없다. pump, 판정, 제출, 조회를 모두 [lock] 하나 아래에서 돈다. 잠금 순서는 호스트 잠금에서
 * mimic 엔진 잠금으로 한 방향뿐이다(gRPC 호출이 잠금 아래에서 나간다).
 *
 * 공정 잠금이다. 제출이 mimic 을 기다리느라 운영 서비스의 요청 제한을 넘기면 운영 서비스가 실행 목록으로 재조회하는데,
 * 먼저 기다린 제출이 먼저 잡아야 재조회가 제출보다 앞서 «반영 안 됨» 을 남기지 않는다. `synchronized` 는 순서를 보장하지
 * 않고, 가상 스레드가 그것을 기다리면 캐리어 스레드를 붙잡는다.
 *
 * ## pump
 *
 * 단일 스레드가 [PUMP_PERIOD] 마다 셀 대역 스냅숏을 **잠금 밖에서** 한 번 읽고, 잠금 아래에서 그 스냅숏을 셀 신호로 끼운 뒤
 * `pump()` 를 부른다. 셀 HTTP 가 늦어도 REST 가 그 뒤에 줄 서지 않는다.
 *
 * ## 판정(T3)
 *
 * 호스트는 스킬 적합과 도는 실행 없음만 본다. 시운전 완료와 연결 신선은 운영 서비스가 본다. 미들웨어의 배정 관문은 이
 * 넷을 보지 않으므로 제출 때 호스트가 다시 판정해 통과한 기체만 `assign` 에 넘긴다.
 *
 * ## 작업 응답
 *
 * 상위 시스템이 없어 `ack` 하지 않는다(스펙 §7.7). 아웃박스가 계속 자라는 것은 한계다(스펙 §12).
 *
 * @param robots 하류 포트. 판정의 케이퍼빌리티도 이것으로 묻는다(`PicassoClient` 가 세대별로 캐시한다).
 */
class MissionHost(
    private val robots: RobotPort,
    private val cellBand: CellBandClient,
    private val clock: HostClock,
) : AutoCloseable {

    private val lock = ReentrantLock(true)
    private val signals = CellBandSignals()

    /** 코드 정의 임무의 카탈로그. 미들웨어에 넘긴 것과 같은 참조로 스킬 적합을 판정한다. */
    private val catalog = InMemoryMissionCatalog(now = clock::now)

    private val middleware = Middleware(robots = robots, cell = signals, now = clock::now, missions = catalog)

    private var pumpedAt: Instant? = null
    private var latestCell: CellSnapshot? = null

    private val pumper = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-pump").apply { isDaemon = true } }

    val instanceId: String get() = middleware.instanceId

    /** pump 를 시작한다. [MissionHostApplication] 이 빈을 만들 때 부른다. */
    fun start(period: Duration = PUMP_PERIOD): MissionHost = apply {
        pumper.scheduleWithFixedDelay(::pumpOnce, 0, period.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** pump 한 번. 예외를 삼키고 다음 주기에 다시 돈다. 스케줄러는 작업이 예외를 던지면 다음 실행을 멈춘다. */
    fun pumpOnce() {
        try {
            val cell = cellBand.fetch()
            lock.withLock {
                signals.snapshot = cell
                latestCell = cell
                val at = clock.now()
                middleware.pump()
                pumpedAt = at
            }
        } catch (e: Exception) {
            log.warn("pump 실패: {}", e.toString())
        }
    }

    /** 기체마다 호스트 판정을 낸다. [order] 의 WorkMaster 는 부르는 쪽이 [WORK_MASTERS] 로 거른다. */
    fun eligibility(order: JobOrder, robotIds: List<String>): List<HostEligibility> = lock.withLock {
        robotIds.distinct().map { judge(order, it) }
    }

    /**
     * 판정을 다시 해 통과한 기체만 `assign` 에 넘긴다. 통과한 기체가 없어도 `assign` 을 부른다. 그때 결과는 사유 없는
     * UNASSIGNED 이고 이유는 [SubmitOutcome.excluded] 에 있다.
     *
     * 도는 실행과 같은 작업 지시 id 로 다시 내면 판정이 그 기체를 도는 실행으로 빼므로 IDEMPOTENT 가 아니라 UNASSIGNED 다.
     * 운영 서비스는 작업 지시 id 를 다시 쓰지 않는다.
     */
    fun submit(order: JobOrder, candidates: List<String>): SubmitOutcome = lock.withLock {
        val judged = candidates.distinct().map { judge(order, it) }
        val excluded = judged.filter { !it.passed }
        when (val submission = middleware.assign(order, judged.filter { it.passed }.map { it.robotId })) {
            is Middleware.Submission.Accepted -> SubmitOutcome(
                SubmitResult.ACCEPTED, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
            )
            is Middleware.Submission.Idempotent -> SubmitOutcome(
                SubmitResult.IDEMPOTENT, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
            )
            is Middleware.Submission.Rejected -> SubmitOutcome(
                SubmitResult.REJECTED, null, null, submission.reason, emptyList(), excluded,
            )
            is Unassigned -> SubmitOutcome(
                SubmitResult.UNASSIGNED, null, null, null,
                submission.refusals.map { (robotId, reason) -> Refusal(robotId, reason) }, excluded,
            )
        }
    }

    fun executions(): ExecutionsView = lock.withLock {
        val responses = middleware.responses()
        ExecutionsView(
            instanceId = middleware.instanceId,
            pumpedAt = pumpedAt,
            executions = middleware.executions().map { execution ->
                ExecutionView(
                    executionId = execution.executionId,
                    jobOrderId = execution.order.jobOrderId,
                    workMasterId = execution.order.workMasterId,
                    missionVersion = execution.missionVersion,
                    robotId = execution.robotId,
                    physicalState = execution.physicalState.name,
                    units = execution.units.map { UnitView(it.unitId, it.skillType, it.state.name, it.reached.name) },
                    jobResponse = responses.lastOrNull { it.executionId == execution.executionId }?.let(::view),
                )
            },
        )
    }

    /** 마지막 pump 가 읽은 셀 대역 스냅숏. 못 읽었으면 `null` 이다. */
    fun cell(): CellSnapshot? = lock.withLock { latestCell }

    /**
     * 스킬 적합은 지금 활성 정의로 작업 지시를 계획해, 경로가 로봇인 단위의 스킬이 기체가 선언한 스킬에 다 있는가다.
     * 도는 실행은 그 기체의 실행 중 물리 상태가 정착하지 않은 것이다. 운영자 보류에 선 실행도 도는 실행이다(스펙 §12).
     */
    private fun judge(order: JobOrder, robotId: String): HostEligibility {
        val active = requireNotNull(catalog.active(order.workMasterId)) { "카탈로그에 없는 WorkMaster 다: ${order.workMasterId}" }
        val needed = active.capability.plan(order).filter { it.route == Route.ROBOT }.map { it.skillType }.toSortedSet()
        val declared = robots.capabilities(robotId)?.skillsList?.map { it.skillType }?.toSet()
        val missing = declared?.let { (needed - it).toList() } ?: emptyList()
        val fit = when {
            declared == null -> SkillFit.UNKNOWN
            missing.isEmpty() -> SkillFit.FIT
            else -> SkillFit.MISSING
        }
        val running = middleware.executions().firstOrNull { it.robotId == robotId && !it.physicalState.isSettled }?.executionId

        val reasons = buildList {
            when (fit) {
                SkillFit.FIT -> Unit
                SkillFit.MISSING -> add("모자란 스킬: ${missing.joinToString(", ")}")
                SkillFit.UNKNOWN -> add("기체 케이퍼빌리티를 못 물어봤다")
            }
            if (running != null) add("도는 실행이 있다: $running")
        }
        return HostEligibility(robotId, fit, missing, running, passed = reasons.isEmpty(), reasons = reasons)
    }

    private fun view(response: JobResponse) = JobResponseView(
        jobResponseId = response.jobResponseId,
        version = response.version,
        physicalState = response.physicalState.name,
        requiredEvidence = response.requiredEvidence.name,
        reachedEvidence = response.reachedEvidence.name,
        completedUnits = response.completedUnits,
        unverifiedUnits = response.unverifiedUnits,
        incompleteUnits = response.incompleteUnits,
        inDoubtUnits = response.inDoubtUnits,
        operatorRequired = response.operatorRequired,
        residualHold = response.residualHold.kind.name,
        blockedBy = response.blockedBy,
        connection = response.connection,
    )

    override fun close() {
        pumper.shutdownNow()
        pumper.awaitTermination(2, TimeUnit.SECONDS)
    }

    companion object {
        private val log = LoggerFactory.getLogger(MissionHost::class.java)

        /** pump 주기(T5). 셀 대역이 채운 슬롯을 E2 마감(15초)보다 훨씬 짧게 다시 읽는다. */
        val PUMP_PERIOD: Duration = Duration.ofMillis(250)

        /** 받는 WorkMaster. DeliverContainer 는 플릿 포트 구현이 없어 받지 않는다(스펙 §1). */
        val WORK_MASTERS: Set<String> = setOf(InspectAsset.WORK_MASTER, PrepareSequencedRack.WORK_MASTER)
    }
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt`:

```kotlin
package dev.picasso.ops.host

import dev.picasso.client.PicassoClient
import dev.picasso.middleware.ClientRobotPort
import dev.picasso.ops.host.cell.CellBandClient
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean

/**
 * 실행 호스트(S3a 스펙 §7). DB 가 없다(T5).
 *
 * JDBC·Flyway 자동설정을 이름으로 끈다. 이 모듈의 클래스패스에는 없지만 통합 시험은 registry·운영 서비스와 한 JVM 에
 * 호스트를 띄우고, 그때 JDBC 가 클래스패스에 와서 데이터 소스 주소 없이 기동이 멈춘다.
 */
@SpringBootApplication(
    excludeName = [
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
open class MissionHostApplication {

    /** 기본은 실제 시각이다. 시험은 [builder] 로 다른 시계를 먼저 넣고, 그러면 이 빈은 만들어지지 않는다. */
    @Bean
    @ConditionalOnMissingBean(HostClock::class)
    open fun hostClock(): HostClock = HostClock.SYSTEM

    /** 같은 기계의 현장 mimic gRPC 에 평문으로 붙는다(S3a 스펙 §7.2). */
    @Bean(destroyMethod = "shutdownNow")
    open fun mimicChannel(@Value("\${host.mimic.port}") port: Int): ManagedChannel {
        require(port in 1..65535) { "mimic gRPC 포트(host.mimic.port, MIMIC_GRPC_PORT)가 틀리다: $port" }
        return ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    }

    @Bean
    open fun cellBand(@Value("\${host.cell.url}") url: String): CellBandClient = CellBandClient(url)

    @Bean(destroyMethod = "close")
    open fun missionHost(channel: ManagedChannel, cellBand: CellBandClient, clock: HostClock): MissionHost =
        MissionHost(ClientRobotPort(PicassoClient(channel, CLIENT_ID)), cellBand, clock).start()

    companion object {
        /** mimic 에 싣는 클라이언트 id. */
        const val CLIENT_ID = "mission-host"

        /**
         * 설정 파일 이름을 `mission-host` 로 둔다. 같은 JVM 의 registry `application.properties`·운영 서비스 설정과 가리지 않게.
         *
         * @param clock 주면 기본 시계 대신 이것을 쓴다(통합 시험이 현장 시계를 넣는다).
         */
        fun builder(clock: HostClock? = null): SpringApplicationBuilder {
            val builder = SpringApplicationBuilder(MissionHostApplication::class.java)
                .properties("spring.config.name=mission-host")
            if (clock != null) {
                builder.initializers(
                    ApplicationContextInitializer<ConfigurableApplicationContext> {
                        it.beanFactory.registerSingleton("hostClock", clock)
                    },
                )
            }
            return builder
        }
    }
}

fun main(args: Array<String>) {
    MissionHostApplication.builder().run(*args)
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt`:

```kotlin
package dev.picasso.ops.host.cell

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.CellSignals
import dev.picasso.middleware.SlotSignal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * 셀 대역의 자리 하나(현장 `GET /cell` 의 원소). [observedAt] 이 `null` 이면 시각을 주지 않는 신호이고 읽은 순간이 그
 * 시각이다(제시 자리). 슬롯은 채운 가상 시각을 든다.
 */
data class CellPlace(val id: String, val occupied: Boolean, val material: String?, val observedAt: Instant?)

/** 셀 대역의 한 순간(현장 `GET /cell` 의 본문). 제시 자리와 슬롯을 가른다. */
data class CellSnapshot(val presentations: List<CellPlace>, val slots: List<CellPlace>)

/**
 * 현장의 셀 대역을 루프백 HTTP 로 읽는다(S3a 스펙 §7.3). 실패하면 `null`(스냅숏 없음)이다. 못 읽은 것을 빈 셀로 접으면
 * 미들웨어가 «말이 없다» 를 «비었다» 로 읽는다.
 *
 * @param baseUrl 현장 셀 대역의 주소. 경로 `/cell` 을 붙여 부른다.
 */
class CellBandClient(baseUrl: String, private val timeout: Duration = Duration.ofSeconds(1)) {

    private val uri = URI.create(baseUrl.trimEnd('/') + "/cell")
    private val http = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val json = ObjectMapper()

    fun fetch(): CellSnapshot? = try {
        val response = http.send(
            HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (response.statusCode() == 200) parse(json.readTree(response.body())) else null
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: Exception) {
        null
    }

    companion object {
        /** 본문 모양이 어긋나면 예외다. [fetch] 가 그것을 «스냅숏 없음» 으로 접는다. */
        fun parse(node: JsonNode): CellSnapshot = CellSnapshot(places(node, "presentations"), places(node, "slots"))

        private fun places(node: JsonNode, field: String): List<CellPlace> {
            val array = requireNotNull(node.get(field)?.takeIf { it.isArray }) { "$field 가 배열이 아니다" }
            return array.map { place ->
                CellPlace(
                    id = requireNotNull(place.get("id")?.takeIf { it.isTextual }) { "자리 id 가 없다" }.asText(),
                    occupied = requireNotNull(place.get("occupied")?.takeIf { it.isBoolean }) { "occupied 가 없다" }.asBoolean(),
                    material = place.get("material")?.takeIf { it.isTextual }?.asText(),
                    observedAt = place.get("observedAt")?.takeIf { it.isTextual }?.asText()?.let(Instant::parse),
                )
            }
        }
    }
}

/**
 * 셀 대역 스냅숏 위의 [CellSignals](S3a 스펙 §7.4). 호스트가 pump 직전에 [snapshot] 을 갈아 끼우고, 미들웨어는 같은 잠금
 * 아래에서 읽는다.
 *
 * - [observe]: 스냅숏에서 자리 id 로 답한다. 스냅숏에 없는 자리는 `null`(말이 없다)이다.
 * - [holding]: 그 자재를 든 **제시 자리**만 낸다. 채운 슬롯을 대안 자리로 내면 미들웨어가 막 놓은 자재를 다시 집으러 보낸다.
 * - [signal]: 이름 있는 신호는 S3a 에서 다루지 않아 `null`(못 읽었다)이다. S3b 의 몫이다.
 *
 * 스냅숏이 없으면 [observe]·[holding] 모두 `null`(못 물어봄)이다.
 */
class CellBandSignals : CellSignals {

    var snapshot: CellSnapshot? = null

    override fun observe(location: String): SlotSignal? {
        val current = snapshot ?: return null
        val place = (current.presentations.asSequence() + current.slots.asSequence()).firstOrNull { it.id == location }
            ?: return null
        return SlotSignal(place.occupied, place.material, place.observedAt)
    }

    override fun holding(material: String): List<String>? =
        snapshot?.presentations?.filter { it.occupied && it.material == material }?.map { it.id }
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt`:

```kotlin
package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.host.ExecutionsView
import dev.picasso.ops.host.HostEligibility
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.cell.CellSnapshot
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** `POST /host/eligibility` 의 본문. 요청의 기체 순서이며 겹친 id 는 한 번만 나온다. */
data class EligibilityView(val robots: List<HostEligibility>)

/** `GET /host/cell` 의 본문. 마지막 pump 가 셀 대역을 못 읽었으면 [cell] 이 `null` 이다(못 물어봄). */
data class CellView(val cell: CellSnapshot?)

/**
 * 실행 호스트 REST(S3a 스펙 §7.6). 루프백이고 인증이 없다. 호출자는 운영 서비스뿐이다.
 *
 * POST 는 `application/json` 만 받는다. 브라우저의 단순 요청(폼·`text/plain`)은 사전 요청 없이 다른 출처에서 올 수 있어,
 * 운영 서비스와 같이 그것을 415 로 막는다. 같은 기계의 다른 프로세스가 운영자 모드 검사 없이 작업 지시를 낼 수 있는 것은
 * 한계다(스펙 §12).
 */
@RestController
class HostController(private val host: MissionHost, private val json: ObjectMapper) {

    @PostMapping("/host/eligibility", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun eligibility(@RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = accepting(body, "robotIds") { order, robots ->
        EligibilityView(host.eligibility(order, robots))
    }

    @PostMapping("/host/job-orders", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(@RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = accepting(body, "candidates") { order, candidates ->
        host.submit(order, candidates)
    }

    @GetMapping("/host/executions")
    fun executions(): ExecutionsView = host.executions()

    @GetMapping("/host/cell")
    fun cell(): CellView = CellView(host.cell())

    private inline fun accepting(
        body: ByteArray?,
        listField: String,
        action: (dev.picasso.middleware.JobOrder, List<String>) -> Any,
    ): ResponseEntity<Any> {
        val node: JsonNode? = body?.let { runCatching { json.readTree(it) }.getOrNull() }
        val (order, robots) = try {
            HostRequests.read(node, listField)
        } catch (e: BadRequest) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
        }
        return ResponseEntity.ok(action(order, robots))
    }
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt`:

```kotlin
package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.MaterialRequirement
import dev.picasso.ops.host.MissionHost

/** 거부한 요청의 답. 운영 서비스의 사전 거부(`PreRejection`)와 같은 모양이다. */
data class HostRejection(val error: String, val detail: String)

/** 본문을 못 받는 까닭. [error] 가 응답의 `error` 칸이다. */
class BadRequest(val error: String, detail: String) : RuntimeException(detail)

/**
 * 호스트 REST 의 요청 본문(S3a 스펙 §7.5·§7.6). 작업 지시 본문의 칸은 picasso `JobOrder` 와 같다.
 *
 * 스프링에 맡기지 않고 직접 읽는다. 맡기면 못 읽는 본문의 400 이 [HostRejection] 모양이 아니다. 칸은 엄격하게 본다.
 * 문자열 칸에 숫자가 오거나 정수 칸에 소수가 오면 없는 것으로 보고 400 이다.
 */
object HostRequests {

    const val BAD_REQUEST = "BAD_REQUEST"
    const val UNKNOWN_WORK_MASTER = "UNKNOWN_WORK_MASTER"

    /** `{jobOrder, <listField>}` 를 읽는다. [listField] 는 판정이면 `robotIds`, 제출이면 `candidates` 다. */
    fun read(body: JsonNode?, listField: String): Pair<JobOrder, List<String>> {
        if (body == null || !body.isObject) throw BadRequest(BAD_REQUEST, "본문이 JSON 객체가 아니다")
        val order = jobOrder(body.get("jobOrder"))
        val robots = strings(body.get(listField), listField)
        return order to robots
    }

    fun jobOrder(node: JsonNode?): JobOrder {
        if (node == null || !node.isObject) throw BadRequest(BAD_REQUEST, "jobOrder 가 객체가 아니다")
        val workMasterId = text(node, "workMasterId")
        if (workMasterId !in MissionHost.WORK_MASTERS) {
            throw BadRequest(UNKNOWN_WORK_MASTER, "받지 않는 WorkMaster 다: $workMasterId (받는 것: ${MissionHost.WORK_MASTERS.sorted()})")
        }
        val version = node.get("version")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()
            ?: throw BadRequest(BAD_REQUEST, "version 이 정수가 아니다")
        if (version < 1) throw BadRequest(BAD_REQUEST, "version 은 1 이상이다: $version")
        val evidence = text(node, "requiredEvidence").let { value ->
            Evidence.entries.firstOrNull { it.name == value }
                ?: throw BadRequest(BAD_REQUEST, "requiredEvidence 가 E0~E3 이 아니다: $value")
        }
        return JobOrder(
            jobOrderId = text(node, "jobOrderId"),
            workMasterId = workMasterId,
            version = version,
            requiredEvidence = evidence,
            parameters = stringMap(node.get("parameters"), "parameters"),
            materialRequirements = array(node.get("materialRequirements"), "materialRequirements").map {
                val quantity = it.get("quantity")?.takeIf { q -> q.isIntegralNumber && q.canConvertToInt() }?.asInt()
                    ?: throw BadRequest(BAD_REQUEST, "materialRequirements 의 quantity 가 정수가 아니다")
                if (quantity < 0) throw BadRequest(BAD_REQUEST, "materialRequirements 의 quantity 가 음수다: $quantity")
                MaterialRequirement(text(it, "materialDefinitionId"), quantity)
            },
            equipmentRequirements = array(node.get("equipmentRequirements"), "equipmentRequirements").map {
                EquipmentRequirement(text(it, "id"), text(it, "equipmentUse"), stringMap(it.get("properties"), "properties"))
            },
        )
    }

    /** 비어 있지 않은 문자열 칸. */
    private fun text(node: JsonNode, field: String): String =
        node.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            ?: throw BadRequest(BAD_REQUEST, "$field 가 비어 있지 않은 문자열이 아니다")

    /** 없으면 빈 목록이다. 있으면 객체의 배열이어야 한다. */
    private fun array(node: JsonNode?, field: String): List<JsonNode> {
        if (node == null || node.isNull) return emptyList()
        if (!node.isArray || node.any { !it.isObject }) throw BadRequest(BAD_REQUEST, "$field 가 객체의 배열이 아니다")
        return node.toList()
    }

    /** 없으면 빈 맵이다. 있으면 값이 전부 문자열인 객체여야 한다. */
    private fun stringMap(node: JsonNode?, field: String): Map<String, String> {
        if (node == null || node.isNull) return emptyMap()
        if (!node.isObject) throw BadRequest(BAD_REQUEST, "$field 가 객체가 아니다")
        return node.properties().associate { (key, value) ->
            if (!value.isTextual) throw BadRequest(BAD_REQUEST, "$field.$key 가 문자열이 아니다")
            key to value.asText()
        }
    }

    /** 비어 있지 않은 문자열의 배열. 필수다. */
    private fun strings(node: JsonNode?, field: String): List<String> {
        if (node == null || !node.isArray || node.any { !it.isTextual || it.asText().isBlank() }) {
            throw BadRequest(BAD_REQUEST, "$field 가 비어 있지 않은 문자열의 배열이 아니다")
        }
        return node.map { it.asText() }
    }
}
```

`mission-host/src/main/resources/mission-host.properties`:

```properties
# 실행 호스트 설정. 파일 이름이 mission-host 인 것은 같은 JVM 의 registry·운영 서비스 설정과 가리지 않기 위해서다(S3a 스펙 §7.1).
spring.application.name=picasso-mission-host
spring.threads.virtual.enabled=true

# 값은 루트 .env 가 준다. 기본값을 적지 않는다: 빠지면 기동에서 멈춘다.
server.port=${HOST_PORT}
# 이 기계 밖에 열지 않는다. 호출자는 운영 서비스뿐이고 인증이 없다(S3a 스펙 §7.6, §12).
server.address=127.0.0.1
# mimic gRPC 는 같은 기계의 현장 프로세스가 연다(S3a 스펙 §6.2). 평문이다.
host.mimic.port=${MIMIC_GRPC_PORT}
# 셀 대역 GET /cell 의 루프백 주소(S3a 스펙 §6.3).
host.cell.url=http://127.0.0.1:${SITE_CELL_PORT}
```

`mission-host/src/test/kotlin/dev/picasso/ops/host/CellBandTest.kt`:

```kotlin
package dev.picasso.ops.host

import dev.picasso.middleware.SlotSignal
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.cell.CellBandSignals
import dev.picasso.ops.host.cell.CellPlace
import dev.picasso.ops.host.cell.CellSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 셀 대역 클라이언트의 셀 신호(S3a 스펙 §7.4). */
class CellBandTest {

    private val filledAt = Instant.parse("2026-10-08T00:01:00Z")

    private val snapshot = CellSnapshot(
        presentations = listOf(CellPlace("SEQ-IN-02.BIN-A", true, "ENGINE-COVER-A", null)),
        slots = listOf(
            // 막 채운 슬롯도 같은 자재를 든다. 대안 자리로 내면 안 된다.
            CellPlace("RACK-204.S01", true, "ENGINE-COVER-A", filledAt),
            CellPlace("RACK-204.S02", false, null, null),
        ),
    )

    @Test
    fun `holding 은 그 자재를 든 제시 자리만 낸다`() {
        val signals = CellBandSignals().apply { snapshot = this@CellBandTest.snapshot }
        assertEquals(listOf("SEQ-IN-02.BIN-A"), signals.holding("ENGINE-COVER-A"))
        assertEquals(emptyList(), signals.holding("OTHER"))
    }

    @Test
    fun `observe 는 자리의 점유·자재·관측 시각을 내고 모르는 자리는 null 이다`() {
        val signals = CellBandSignals().apply { snapshot = this@CellBandTest.snapshot }
        assertEquals(SlotSignal(true, "ENGINE-COVER-A", null), signals.observe("SEQ-IN-02.BIN-A"))
        assertEquals(SlotSignal(true, "ENGINE-COVER-A", filledAt), signals.observe("RACK-204.S01"))
        assertEquals(SlotSignal(false, null, null), signals.observe("RACK-204.S02"))
        assertNull(signals.observe("dock-3"))
        assertNull(signals.signal("door-open"))
    }

    @Test
    fun `스냅숏이 없으면 observe 와 holding 모두 못 물어봄이다`() {
        val signals = CellBandSignals()
        assertNull(signals.observe("SEQ-IN-02.BIN-A"))
        assertNull(signals.holding("ENGINE-COVER-A"))
    }

    @Test
    fun `현장 본문을 읽고 모양이 어긋나면 예외다`() {
        val read = CellBandClient.parse(HostBench.JSON.readTree(HostBench.STANDARD_CELL))
        assertEquals(listOf(CellPlace("SEQ-IN-02.BIN-A", true, "ENGINE-COVER-A", null)), read.presentations)
        assertEquals(4, read.slots.size)
        val filled = CellBandClient.parse(
            HostBench.JSON.readTree(
                """{"presentations":[],"slots":[{"id":"S","occupied":true,"material":"M","observedAt":"$filledAt"}]}""",
            ),
        )
        assertEquals(CellPlace("S", true, "M", filledAt), filled.slots.single())
        assertFailsWith<IllegalArgumentException> { CellBandClient.parse(HostBench.JSON.readTree("""{"slots":[]}""")) }
    }

    @Test
    fun `닿지 않는 셀 대역은 null 이다`() {
        assertNull(CellBandClient("http://127.0.0.1:1").fetch())
    }
}
```

`mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt`:

```kotlin
package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.mimic.cli.MimicCli
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * 호스트 시험 세트. 이 JVM 의 Netty 포트에 mimic(humanoid-01·quadruped-01, 가상 시계, registry 없음)을 띄우고, 고정 본문을
 * 내는 셀 대역 대역(stub)과 실행 호스트를 띄운다. 호스트 시계는 mimic 의 가상 시계다(통합 시험이 현장 시계를 넣는 것과 같은 배선).
 *
 * 셀 대역은 현장 모듈에 의존하지 않고 같은 본문 모양을 직접 낸다. 현장 쪽 모양은 현장 시험이 본다.
 */
class HostBench : AutoCloseable {

    val mimic: MimicCli.Started = checkNotNull(
        MimicCli().start(
            robots = mapOf(
                HUMANOID to ROOT.resolve("picasso/profile/profiles/humanoid-a.json"),
                QUADRUPED to ROOT.resolve("picasso/profile/profiles/quadruped-b.json"),
            ),
            schema = ROOT.resolve("picasso/profile/schema/capability-profile.schema.json"),
            port = 0,
            virtual = true,
            seed = 0L,
            err = System.err,
        ),
    ) { "mimic 기동 거부" }

    /** 셀 대역 대역이 낼 본문. 바꾸면 다음 pump 부터 호스트가 읽는다. */
    @Volatile
    var cellBody: String = STANDARD_CELL

    private val cell: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/cell") { exchange ->
            val bytes = cellBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        start()
    }

    private val context: ConfigurableApplicationContext = MissionHostApplication.builder(HostClock { now() }).run(
        "--server.port=0",
        "--host.mimic.port=${mimic.server.port}",
        "--host.cell.url=http://127.0.0.1:${cell.address.port}",
    )

    val url = "http://127.0.0.1:${(context as WebServerApplicationContext).webServer.port}"

    fun now(): Instant = mimic.server.exclusive { mimic.instance(HUMANOID)!!.clock.now() }

    fun stopCell() = cell.stop(0)

    data class Reply(val status: Int, val body: JsonNode?)

    fun get(path: String): JsonNode {
        val response = HTTP.send(HttpRequest.newBuilder(URI.create(url + path)).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "$path: ${response.statusCode()} ${response.body()}" }
        return JSON.readTree(response.body())
    }

    fun post(path: String, body: String, contentType: String = "application/json"): Reply {
        val response = HTTP.send(
            HttpRequest.newBuilder(URI.create(url + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Reply(response.statusCode(), response.body().takeIf { it.isNotBlank() }?.let { runCatching { JSON.readTree(it) }.getOrNull() })
    }

    /** 이 실행을 찾는다. 없으면 `null`. */
    fun execution(executionId: String): JsonNode? =
        get("/host/executions")["executions"].firstOrNull { it["executionId"].asText() == executionId }

    /** 지금 가상 시각 이후에 시작한 pump 가 끝날 때까지 기다린다(실제 시간 상한 5초). */
    fun awaitPump() {
        val target = now()
        val deadline = Instant.now().plusSeconds(5)
        while (Instant.now().isBefore(deadline)) {
            val at = get("/host/executions")["pumpedAt"]
            if (!at.isNull && !Instant.parse(at.asText()).isBefore(target)) return
            Thread.sleep(50)
        }
        error("pump 가 $target 에 이르지 않았다")
    }

    /**
     * 실행이 [states] 중 하나가 될 때까지 가상 시계를 5초씩 민다. 민 뒤에는 스트림 갱신이 클라이언트에 닿을 틈을 주려고
     * pump 두 번을 기다린다.
     */
    fun driveUntil(executionId: String, states: Set<String>, rounds: Int = 40): JsonNode {
        repeat(rounds) {
            mimic.server.advance(Duration.ofSeconds(5))
            awaitPump()
            Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
            val execution = checkNotNull(execution(executionId)) { "실행이 없다: $executionId" }
            if (execution["physicalState"].asText() in states) return execution
        }
        error("실행 $executionId 가 $states 에 이르지 않았다: ${execution(executionId)}")
    }

    override fun close() {
        try {
            context.close()
        } finally {
            cell.stop(0)
            mimic.server.shutdown()
        }
    }

    companion object {
        const val HUMANOID = "humanoid-01"
        const val QUADRUPED = "quadruped-01"
        const val GHOST = "ghost-01"
        const val SOURCE = "SEQ-IN-02.BIN-A"
        const val MATERIAL = "ENGINE-COVER-A"

        val ROOT: Path = Path.of("..").toAbsolutePath().normalize()
        val HTTP: HttpClient = HttpClient.newHttpClient()
        val JSON = ObjectMapper()

        /** 현장 셀 대역 `CellFixture.STANDARD` 의 처음 모양. */
        val STANDARD_CELL = """
            {"presentations":[{"id":"$SOURCE","occupied":true,"material":"$MATERIAL","observedAt":null}],
             "slots":[{"id":"RACK-204.S01","occupied":false,"material":null,"observedAt":null},
                      {"id":"RACK-204.S02","occupied":false,"material":null,"observedAt":null},
                      {"id":"RACK-204.S03","occupied":false,"material":null,"observedAt":null},
                      {"id":"RACK-204.S04","occupied":false,"material":null,"observedAt":null}]}
        """.trimIndent()

        fun inspect(jobOrderId: String, vararg targets: Pair<String, String>, evidence: String = "E0"): String =
            """
            {"jobOrderId":"$jobOrderId","workMasterId":"InspectAsset","version":1,"requiredEvidence":"$evidence",
             "parameters":{},"materialRequirements":[],
             "equipmentRequirements":[${targets.joinToString(",") { (id, location) ->
                """{"id":"$id","equipmentUse":"inspection_target","properties":{"location":"$location"}}"""
            }}]}
            """.trimIndent()

        fun rack(jobOrderId: String, vararg slots: String): String =
            """
            {"jobOrderId":"$jobOrderId","workMasterId":"PrepareSequencedRack","version":1,"requiredEvidence":"E2",
             "parameters":{},"materialRequirements":[{"materialDefinitionId":"$MATERIAL","quantity":${slots.size}}],
             "equipmentRequirements":[${(slots.map { """{"id":"$it","equipmentUse":"destination","properties":{"material":"$MATERIAL"}}""" } +
                """{"id":"$SOURCE","equipmentUse":"source","properties":{"material":"$MATERIAL"}}""").joinToString(",")}]}
            """.trimIndent()

        fun request(order: String, field: String, vararg robots: String): String =
            """{"jobOrder":$order,"$field":[${robots.joinToString(",") { "\"$it\"" }}]}"""
    }
}
```

`mission-host/src/test/kotlin/dev/picasso/ops/host/HostEnvBoundaryTest.kt`:

```kotlin
package dev.picasso.ops.host

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 실행 호스트는 registry 를 부르지 않으므로 적재 토큰도 운영자 토큰도 받지 않는다(S3a 스펙 §7.1). */
class HostEnvBoundaryTest {

    @Test
    fun `실행 호스트는 두 토큰을 받지 않고 포트 값은 받는다`() {
        assertNull(System.getenv("PICASSO_INGEST_TOKEN"))
        assertNull(System.getenv("PICASSO_OPERATOR_TOKEN"))
        // 값을 시험에 다시 적지 않고 루트 .env 에서 읽어 맞댄다.
        val env = Files.readAllLines(HostBench.ROOT.resolve(".env"))
        listOf("HOST_PORT", "MIMIC_GRPC_PORT", "SITE_CELL_PORT").forEach { key ->
            val value = env.single { it.startsWith("$key=") }.substringAfter("=").trim()
            assertEquals(value, System.getenv(key), key)
        }
    }
}
```

`mission-host/src/test/kotlin/dev/picasso/ops/host/MissionHostTest.kt`:

```kotlin
package dev.picasso.ops.host

import dev.picasso.ops.host.HostBench.Companion.GHOST
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실행 호스트의 판정·제출·실행 목록(S3a 스펙 §7.6, §11). mimic 은 실제 Netty 포트에 뜨고 호스트는 그 포트에 gRPC 로 붙는다.
 */
class MissionHostTest {

    @Test
    fun `스킬이 모자란 기체와 케이퍼빌리티를 못 물은 기체를 가르고 이유를 낸다`() {
        HostBench().use { bench ->
            val rack = bench.post("/host/eligibility", request(rack("JO-1", "RACK-204.S01"), "robotIds", HUMANOID, QUADRUPED, GHOST))
            assertEquals(200, rack.status, rack.body.toString())
            val rows = rack.body!!["robots"].associateBy { it["robotId"].asText() }
            assertEquals(listOf(HUMANOID, QUADRUPED, GHOST), rack.body["robots"].map { it["robotId"].asText() })

            val humanoid = rows.getValue(HUMANOID)
            assertEquals("FIT", humanoid["skillFit"].asText())
            assertEquals(0, humanoid["missingSkills"].size())
            assertTrue(humanoid["runningExecutionId"].isNull)
            assertEquals(true, humanoid["passed"].asBoolean())
            assertEquals(0, humanoid["reasons"].size())

            // quadruped-01 은 pick_place 를 선언하지 않는다.
            val quadruped = rows.getValue(QUADRUPED)
            assertEquals("MISSING", quadruped["skillFit"].asText())
            assertEquals(listOf("pick_place"), quadruped["missingSkills"].map { it.asText() })
            assertEquals(false, quadruped["passed"].asBoolean())
            assertEquals(listOf("모자란 스킬: pick_place"), quadruped["reasons"].map { it.asText() })

            // mimic 이 모르는 기체는 케이퍼빌리티를 못 물어본다. 모자람이 아니라 모름이다.
            val ghost = rows.getValue(GHOST)
            assertEquals("UNKNOWN", ghost["skillFit"].asText())
            assertEquals(0, ghost["missingSkills"].size())
            assertEquals(false, ghost["passed"].asBoolean())

            // InspectAsset 은 둘 다 든다.
            val inspection = bench.post("/host/eligibility", request(inspect("JO-2", "T1" to "bay-7"), "robotIds", HUMANOID, QUADRUPED))
            assertEquals(listOf("FIT", "FIT"), inspection.body!!["robots"].map { it["skillFit"].asText() })
        }
    }

    @Test
    fun `제출은 판정을 다시 해 통과한 기체만 assign 에 넘긴다`() {
        HostBench().use { bench ->
            // 통과한 기체가 없으면 assign 의 사유는 비고 뺀 기체가 이유와 함께 나온다.
            val none = bench.post("/host/job-orders", request(rack("JO-1", "RACK-204.S01"), "candidates", QUADRUPED))
            assertEquals(200, none.status, none.body.toString())
            assertEquals("UNASSIGNED", none.body!!["result"].asText())
            assertTrue(none.body["executionId"].isNull)
            assertEquals(0, none.body["refusals"].size())
            assertEquals(listOf(QUADRUPED), none.body["excluded"].map { it["robotId"].asText() })
            assertEquals(listOf("pick_place"), none.body["excluded"][0]["missingSkills"].map { it.asText() })

            // 후보 순서가 quadruped-01 먼저여도 배정은 통과한 humanoid-01 이다.
            val accepted = bench.post(
                "/host/job-orders",
                request(rack("JO-2", "RACK-204.S01", "RACK-204.S02"), "candidates", QUADRUPED, HUMANOID),
            )
            assertEquals("ACCEPTED", accepted.body!!["result"].asText(), accepted.body.toString())
            assertEquals(HUMANOID, accepted.body["robotId"].asText())
            assertEquals("exec-1", accepted.body["executionId"].asText())
            assertTrue(accepted.body["rejectionReason"].isNull)
            assertEquals(listOf(QUADRUPED), accepted.body["excluded"].map { it["robotId"].asText() })

            val execution = bench.execution("exec-1")!!
            assertEquals("JO-2", execution["jobOrderId"].asText())
            assertEquals("PrepareSequencedRack", execution["workMasterId"].asText())
            assertEquals(HUMANOID, execution["robotId"].asText())
            // 코드 정의 임무는 버전이 없다. 칸은 있고 값이 null 이다.
            assertTrue(execution.has("missionVersion") && execution["missionVersion"].isNull, execution.toString())
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02"), execution["units"].map { it["unitId"].asText() })
            assertTrue(execution["units"].all { it["skillType"].asText() == "pick_place" && it.has("state") && it.has("reached") })
        }
    }

    @Test
    fun `도는 실행이 있는 기체는 판정과 제출에서 빠진다`() {
        HostBench().use { bench ->
            val first = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID))
            assertEquals("ACCEPTED", first.body!!["result"].asText(), first.body.toString())
            val executionId = first.body["executionId"].asText()

            val judged = bench.post("/host/eligibility", request(inspect("JO-2", "T2" to "dock-3"), "robotIds", HUMANOID))
                .body!!["robots"].single()
            assertEquals("FIT", judged["skillFit"].asText())
            assertEquals(executionId, judged["runningExecutionId"].asText())
            assertEquals(false, judged["passed"].asBoolean())
            assertEquals(listOf("도는 실행이 있다: $executionId"), judged["reasons"].map { it.asText() })

            val second = bench.post("/host/job-orders", request(inspect("JO-2", "T2" to "dock-3"), "candidates", HUMANOID))
            assertEquals("UNASSIGNED", second.body!!["result"].asText(), second.body.toString())
            assertEquals(executionId, second.body["excluded"].single()["runningExecutionId"].asText())
            assertEquals(1, bench.get("/host/executions")["executions"].size())
        }
    }

    @Test
    fun `끝난 InspectAsset 를 같은 id 로 다시 내면 IDEMPOTENT 이고 실행에 마지막 작업 응답이 붙는다`() {
        HostBench().use { bench ->
            val order = request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)
            val accepted = bench.post("/host/job-orders", order).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val executionId = accepted["executionId"].asText()

            val done = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            assertEquals(listOf("T1.travel" to "DONE", "T1" to "DONE"), done["units"].map { it["unitId"].asText() to it["state"].asText() })
            assertTrue(done["units"].all { it["reached"].asText() == "E0" })

            val response = done["jobResponse"]
            assertEquals("PHYSICALLY_DONE", response["physicalState"].asText(), response.toString())
            assertEquals("E0", response["reachedEvidence"].asText())
            assertEquals(listOf("T1.travel", "T1"), response["completedUnits"].map { it.asText() })

            val again = bench.post("/host/job-orders", order).body!!
            assertEquals("IDEMPOTENT", again["result"].asText(), again.toString())
            assertEquals(executionId, again["executionId"].asText())
            assertEquals(HUMANOID, again["robotId"].asText())
            assertEquals(1, bench.get("/host/executions")["executions"].size())
        }
    }

    @Test
    fun `요구 근거 등급이 임무의 최고 등급을 넘으면 REJECTED 와 사유를 낸다`() {
        HostBench().use { bench ->
            val rejected = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", evidence = "E2"), "candidates", HUMANOID))
            assertEquals(200, rejected.status)
            assertEquals("REJECTED", rejected.body!!["result"].asText(), rejected.body.toString())
            assertTrue(rejected.body["executionId"].isNull)
            assertTrue(rejected.body["robotId"].isNull)
            assertTrue("E2" in rejected.body["rejectionReason"].asText(), rejected.body.toString())
            assertEquals(0, bench.get("/host/executions")["executions"].size())
        }
    }

    @Test
    fun `받지 않는 WorkMaster 와 못 읽는 본문은 400 이고 JSON 이 아니면 415 다`() {
        HostBench().use { bench ->
            val deliver = inspect("JO-1", "T1" to "bay-7").replace("InspectAsset", "DeliverContainer")
            listOf("/host/eligibility" to "robotIds", "/host/job-orders" to "candidates").forEach { (path, field) ->
                val unknown = bench.post(path, request(deliver, field, HUMANOID))
                assertEquals(400, unknown.status, "$path ${unknown.body}")
                assertEquals("UNKNOWN_WORK_MASTER", unknown.body!!["error"].asText())

                val broken = bench.post(path, "{not json")
                assertEquals(400, broken.status, path)
                assertEquals("BAD_REQUEST", broken.body!!["error"].asText())

                val noId = bench.post(path, request(inspect("JO-1", "T1" to "bay-7").replace("\"jobOrderId\":\"JO-1\",", ""), field, HUMANOID))
                assertEquals(400, noId.status, path)
                assertEquals("BAD_REQUEST", noId.body!!["error"].asText())

                val noRobots = bench.post(path, """{"jobOrder":${inspect("JO-1", "T1" to "bay-7")}}""")
                assertEquals(400, noRobots.status, path)
                assertTrue(field in noRobots.body!!["detail"].asText(), noRobots.body.toString())

                val text = bench.post(path, request(inspect("JO-1", "T1" to "bay-7"), field, HUMANOID), contentType = "text/plain")
                assertEquals(415, text.status, path)
            }
            assertEquals(0, bench.get("/host/executions")["executions"].size())
        }
    }

    @Test
    fun `실행 목록은 미들웨어 인스턴스와 마지막 pump 시각을 낸다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val view = bench.get("/host/executions")
            assertTrue(view["instanceId"].asText().startsWith("mw-"), view.toString())
            assertEquals(bench.now().toString(), view["pumpedAt"].asText())
            assertEquals(0, view["executions"].size())
        }
    }

    @Test
    fun `셀 대역 스냅숏을 pump 마다 읽어 내고 못 읽으면 null 이다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val cell = bench.get("/host/cell")["cell"]
            assertEquals(listOf(HostBench.SOURCE), cell["presentations"].map { it["id"].asText() })
            assertEquals(HostBench.MATERIAL, cell["presentations"][0]["material"].asText())
            assertTrue(cell["presentations"][0]["observedAt"].isNull)
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02", "RACK-204.S03", "RACK-204.S04"), cell["slots"].map { it["id"].asText() })

            bench.stopCell()
            // 지금 대기 중인 pump 가 앞선 스냅숏을 들고 끝날 수 있어 두 번을 지나 본다.
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            bench.mimic.server.advance(java.time.Duration.ofSeconds(1))
            bench.awaitPump()
            assertTrue(bench.get("/host/cell")["cell"].isNull)
        }
    }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task2.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task2.patch"
```

```diff
diff --git a/build.gradle.kts b/build.gradle.kts
index 862a5c3..9370f3d 100644
--- a/build.gradle.kts
+++ b/build.gradle.kts
@@ -45,19 +45,25 @@ subprojects {
 
     // 토큰을 쥐는 곳은 스펙 §4 의 표대로다. 적재 토큰은 mimic 이 있는 site 만 받는다.
     // site 가 운영자 토큰도 받는 것은 같은 프로세스에서 registry(토큰을 검증하는 쪽)를 띄우기 때문이다.
-    val env = if (name == "site") dotenv else dotenv - "PICASSO_INGEST_TOKEN"
+    // 실행 호스트는 registry 를 부르지 않으므로 두 토큰 다 받지 않는다(S3a 스펙 §7.1).
+    val withheld = when (name) {
+        "site" -> emptySet()
+        "mission-host" -> setOf("PICASSO_INGEST_TOKEN", "PICASSO_OPERATOR_TOKEN")
+        else -> setOf("PICASSO_INGEST_TOKEN")
+    }
+    val env = dotenv - withheld
 
     tasks.withType<Test>().configureEach {
         useJUnitPlatform()
         environment(env)
-        // environment(...) 는 더하기만 한다. 셸에서 상속된 적재 토큰도 site 밖에서는 지운다.
-        if (project.name != "site") environment.remove("PICASSO_INGEST_TOKEN")
+        // environment(...) 는 더하기만 한다. 셸에서 상속된 토큰도 받지 않는 모듈에서는 지운다.
+        withheld.forEach { environment.remove(it) }
         // 환경 변수는 Gradle 의 시험 입력 추적에 들어가지 않는다. 선언하지 않으면 .env 를 고쳐도 UP-TO-DATE 다.
         inputs.file(rootProject.file(".env")).withPropertyName("dotenv")
     }
 
     tasks.withType<JavaExec>().configureEach {
         environment(env)
-        if (project.name != "site") environment.remove("PICASSO_INGEST_TOKEN")
+        withheld.forEach { environment.remove(it) }
     }
 }
diff --git a/settings.gradle.kts b/settings.gradle.kts
index 86bb73a..949325f 100644
--- a/settings.gradle.kts
+++ b/settings.gradle.kts
@@ -13,14 +13,18 @@ dependencyResolutionManagement {
 
 // picasso 서브프로젝트에는 Gradle group 이 없어 좌표가 자동으로 맞지 않는다(스펙 §11).
 // 쓰는 모듈만 명시한다. uplink 는 mimic 의 api 로 따라온다. harness 는 가짜 현장이 개정판 시험 실행기를 띄우려고 쓴다.
+// picasso(미들웨어)와 client 는 실행 호스트가 쓴다(S3a 스펙 §7.1).
 includeBuild("picasso") {
     dependencySubstitution {
         substitute(module("dev.picasso:registry")).using(project(":registry"))
         substitute(module("dev.picasso:mimic")).using(project(":mimic"))
         substitute(module("dev.picasso:harness")).using(project(":harness"))
+        substitute(module("dev.picasso:picasso")).using(project(":picasso"))
+        substitute(module("dev.picasso:client")).using(project(":client"))
     }
 }
 
 include("site")
 include("ops-service")
+include("mission-host")
 include("e2e")
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && ./gradlew :mission-host:test :site:test -q
```
Expected: mission-host 14, site 25, 실패 0. 백그라운드로 돌린다.

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git add mission-host/build.gradle.kts mission-host/src/main/kotlin/dev/picasso/ops/host/HostClock.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt mission-host/src/main/resources/mission-host.properties mission-host/src/test/kotlin/dev/picasso/ops/host/CellBandTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostEnvBoundaryTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/MissionHostTest.kt build.gradle.kts settings.gradle.kts && git commit -F - <<'EOF'
feat(mission-host): 미들웨어 실행 호스트 모듈과 작업 지시 REST 추가

- 잠금 하나, 250ms pump, 셀 대역 클라이언트, REST 넷(배정 가능 판정, 작업 지시, 실행 목록, 셀)
- includeBuild 치환에 picasso·client, 환경 규칙에서 토큰 둘 제외

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s3a-cmp.sh" site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt site/src/test/kotlin/dev/picasso/ops/site/SiteCellTest.kt .env site/src/main/kotlin/dev/picasso/ops/site/Site.kt site/src/main/kotlin/dev/picasso/ops/site/SiteConfig.kt site/src/main/kotlin/dev/picasso/ops/site/SiteLauncher.kt site/src/test/kotlin/dev/picasso/ops/site/SiteConfigTest.kt site/src/test/kotlin/dev/picasso/ops/site/SiteTest.kt mission-host/build.gradle.kts mission-host/src/main/kotlin/dev/picasso/ops/host/HostClock.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHostApplication.kt mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostController.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt mission-host/src/main/resources/mission-host.properties mission-host/src/test/kotlin/dev/picasso/ops/host/CellBandTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostEnvBoundaryTest.kt mission-host/src/test/kotlin/dev/picasso/ops/host/MissionHostTest.kt build.gradle.kts settings.gradle.kts
```
Expected: 22개 모두 `같음`.

### Task 3: 조작 기록 응답 칸 이름(V3)

**Files:**
- Create: `ops-service/src/main/resources/db/ops/V3__target_response.sql`
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/log/OperationLog.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/operations/OperationRunner.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterOperationsTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/OperationLogTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/ProfileOperationsTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/RobotOperationsTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt`, `e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt`

- [ ] **Step 1: 새 파일 1개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ops-service/src/main/resources/db/ops && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ops-service/src/main/resources/db/ops/V3__target_response.sql" ops-service/src/main/resources/db/ops/V3__target_response.sql
```

`ops-service/src/main/resources/db/ops/V3__target_response.sql`:

```sql
-- 조작 기록의 응답 칸 이름을 넓힌다(S3a 스펙 §8). S3a 부터 registry 가 아닌 실행 호스트의 응답도 이 칸에 남는다.
-- 칸 수와 덧붙이기 전용 트리거는 그대로다. RENAME 은 행을 고치지 않으므로 행 트리거에 걸리지 않는다.
ALTER TABLE operation_log RENAME COLUMN registry_response TO target_response;
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task3.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task3.patch"
```

```diff
diff --git a/e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt b/e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt
index dc47d46..99c170f 100644
--- a/e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt
+++ b/e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt
@@ -53,7 +53,7 @@ class SkeletonTest {
         val versions = PostgresSupport.queryAll(
             "SELECT version FROM ops.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
         ) { it.getString(1) }
-        assertEquals(listOf("1", "2"), versions)
+        assertEquals(listOf("1", "2", "3"), versions)
         assertEquals(0, stack.get("/api/operations").size())
     }
 
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/log/OperationLog.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/log/OperationLog.kt
index 1d1a759..3418f4f 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/log/OperationLog.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/log/OperationLog.kt
@@ -14,7 +14,12 @@ import java.util.UUID
  */
 enum class OperationResult { SUCCEEDED, REJECTED, NO_RESPONSE, CONFIRMED_APPLIED, CONFIRMED_NOT_APPLIED }
 
-/** 조작 기록 한 행. 칸 9개(스펙 §7.1). [request]·[registryResponse] 는 JSON 문자열이다. */
+/**
+ * 조작 기록 한 행. 칸 9개(스펙 §7.1). [request]·[targetResponse] 는 JSON 문자열이다.
+ *
+ * [targetResponse] 는 상태를 바꾸는 쪽(registry 또는 실행 호스트)의 응답이다. S2 까지는 registry 만 있어
+ * `registry_response` 였고, S3a 의 V3 마이그레이션이 이름을 바꿨다(S3a 스펙 §8).
+ */
 data class OperationRecord(
     val requestId: UUID,
     val mode: Mode,
@@ -23,7 +28,7 @@ data class OperationRecord(
     val request: String,
     val reason: String?,
     val result: OperationResult,
-    val registryResponse: String?,
+    val targetResponse: String?,
     val recordedAt: Instant,
 )
 
@@ -37,15 +42,15 @@ class OperationLog(private val jdbc: JdbcClient) {
         request: String,
         reason: String?,
         result: OperationResult,
-        registryResponse: String?,
+        targetResponse: String?,
     ) {
         jdbc.sql(
             """
             INSERT INTO ops.operation_log
-                (request_id, mode, actor_user, target, request, reason, result, registry_response)
+                (request_id, mode, actor_user, target, request, reason, result, target_response)
             VALUES
                 (:requestId, :mode, :user, :target, CAST(:request AS JSONB), :reason, :result,
-                 CAST(:registryResponse AS JSONB))
+                 CAST(:targetResponse AS JSONB))
             """.trimIndent(),
         )
             .param("requestId", requestId)
@@ -55,7 +60,7 @@ class OperationLog(private val jdbc: JdbcClient) {
             .param("request", request, Types.VARCHAR)
             .param("reason", reason, Types.VARCHAR)
             .param("result", result.name)
-            .param("registryResponse", registryResponse, Types.VARCHAR)
+            .param("targetResponse", targetResponse, Types.VARCHAR)
             .update()
     }
 
@@ -67,7 +72,7 @@ class OperationLog(private val jdbc: JdbcClient) {
         jdbc.sql(
             """
             SELECT request_id, mode, actor_user, target, request::text AS request, reason, result,
-                   registry_response::text AS registry_response, recorded_at
+                   target_response::text AS target_response, recorded_at
             FROM ops.operation_log
             ORDER BY recorded_at DESC
             LIMIT :limit
@@ -83,7 +88,7 @@ class OperationLog(private val jdbc: JdbcClient) {
                     request = rs.getString("request"),
                     reason = rs.getString("reason"),
                     result = OperationResult.valueOf(rs.getString("result")),
-                    registryResponse = rs.getString("registry_response"),
+                    targetResponse = rs.getString("target_response"),
                     recordedAt = rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                 )
             }
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/operations/OperationRunner.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/operations/OperationRunner.kt
index 33f3988..e05e188 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/operations/OperationRunner.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/operations/OperationRunner.kt
@@ -18,7 +18,7 @@ import java.util.UUID
  * 응답 없음 뒤 재조회의 판정.
  *
  * @param applied 이 조작이 반영됐는가
- * @param observed 재조회에서 본 대상의 모습. 확인 행의 `registry_response` 에 `observed` 로 남는다. 대상이 없으면 널이다
+ * @param observed 재조회에서 본 대상의 모습. 확인 행의 `target_response` 에 `observed` 로 남는다. 대상이 없으면 널이다
  */
 data class Recheck(val applied: Boolean, val observed: JsonNode?)
 
@@ -106,7 +106,7 @@ class OperationRunner(
 
     private fun parse(body: String): JsonNode? = runCatching { json.readTree(body) }.getOrNull()?.takeIf { it.isObject }
 
-    /** 조작 기록의 `registry_response` 칸. 코드와 본문을 남긴다. 본문이 JSON 이 아니면 글자로 남긴다. */
+    /** 조작 기록의 `target_response` 칸. 코드와 본문을 남긴다. 본문이 JSON 이 아니면 글자로 남긴다. */
     private fun responseJson(write: RegistryWrite.Answered): String {
         val node = json.createObjectNode().put("status", write.status)
         val body = parse(write.body)
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterOperationsTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterOperationsTest.kt
index a6e9351..b0d24dd 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterOperationsTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterOperationsTest.kt
@@ -110,7 +110,7 @@ class AdapterOperationsTest {
         assertEquals(OperationResult.NO_RESPONSE to OperationResult.CONFIRMED_APPLIED, outcome.result to outcome.confirmation)
         val rows = log.list()
         assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
-        assertTrue(rows.first().registryResponse!!.contains("\"adapter_id\": 1"), rows.first().registryResponse)
+        assertTrue(rows.first().targetResponse!!.contains("\"adapter_id\": 1"), rows.first().targetResponse)
     }
 
     @Test
@@ -148,7 +148,7 @@ class AdapterOperationsTest {
         val outcome = operations.registerInstance(lee, "i1", 11, "tcp://fleet:1")
         assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
         val confirmation = log.list().first()
-        assertTrue(confirmation.registryResponse!!.contains("\"version\": \"1.1.0\""), confirmation.registryResponse)
+        assertTrue(confirmation.targetResponse!!.contains("\"version\": \"1.1.0\""), confirmation.targetResponse)
     }
 
     @Test
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/OperationLogTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/OperationLogTest.kt
index 2821038..0ada1fb 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/OperationLogTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/OperationLogTest.kt
@@ -7,6 +7,7 @@ import dev.picasso.ops.service.log.OperationLog
 import dev.picasso.ops.service.log.OperationResult
 import dev.picasso.ops.service.store.OpsSchema
 import dev.picasso.registry.PostgresSupport
+import org.flywaydb.core.Flyway
 import org.springframework.jdbc.core.simple.JdbcClient
 import org.springframework.jdbc.datasource.DriverManagerDataSource
 import java.sql.SQLException
@@ -47,12 +48,28 @@ class OperationLogTest {
         assertEquals(
             listOf(
                 "request_id", "mode", "actor_user", "target", "request",
-                "reason", "result", "registry_response", "recorded_at",
+                "reason", "result", "target_response", "recorded_at",
             ),
             columns,
         )
     }
 
+    /** S2 까지 쌓인 행이 V3 뒤에도 같은 값으로 읽힌다. 이름만 바꾸고 칸을 새로 만들지 않았다는 뜻이다(S3a 스펙 §8). */
+    @Test
+    fun `V3 는 기존 행의 응답을 그대로 두고 칸 이름만 target_response 로 바꾼다`() {
+        OpsSchema.flyway(dataSource, cleanable = true).clean()
+        Flyway.configure().configuration(OpsSchema.flyway(dataSource).configuration).target("2").load().migrate()
+        val id = UUID.randomUUID()
+        PostgresSupport.execute(
+            "INSERT INTO ops.operation_log (request_id, mode, actor_user, target, request, result, registry_response) " +
+                "VALUES ('$id', 'OPERATOR', 'kim', 'robot r1', '{}', 'SUCCEEDED', '{\"status\": 200}')",
+        )
+        OpsSchema.flyway(dataSource).migrate()
+        val record = log.list().single()
+        assertEquals(id, record.requestId)
+        assertEquals(json.readTree("""{"status":200}"""), json.readTree(record.targetResponse))
+    }
+
     @Test
     fun `조작 한 건을 그대로 남긴다`() {
         val id = UUID.randomUUID()
@@ -65,7 +82,7 @@ class OperationLogTest {
         assertEquals(json.readTree("""{"reason":"정비"}"""), json.readTree(record.request))
         assertEquals("정비", record.reason)
         assertEquals(OperationResult.SUCCEEDED, record.result)
-        assertEquals(json.readTree("""{"status":200}"""), json.readTree(record.registryResponse))
+        assertEquals(json.readTree("""{"status":200}"""), json.readTree(record.targetResponse))
     }
 
     @Test
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/ProfileOperationsTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/ProfileOperationsTest.kt
index cf3a035..2980d13 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/ProfileOperationsTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/ProfileOperationsTest.kt
@@ -118,8 +118,8 @@ class ProfileOperationsTest {
         assertEquals(OperationResult.CONFIRMED_APPLIED, operations.submit(kim, document.toByteArray()).confirmation)
         // 확인 행에는 재조회에서 본 개정판 id 와 상태가 남는다(스펙 §8.4 마지막 문단).
         val confirmed = log.list().first()
-        assertTrue(confirmed.registryResponse!!.contains("\"profile_revision_id\": 5"), confirmed.registryResponse)
-        assertTrue(confirmed.registryResponse!!.contains("\"status\": \"VALIDATED\""), confirmed.registryResponse)
+        assertTrue(confirmed.targetResponse!!.contains("\"profile_revision_id\": 5"), confirmed.targetResponse)
+        assertTrue(confirmed.targetResponse!!.contains("\"status\": \"VALIDATED\""), confirmed.targetResponse)
 
         revisions = RegistryCall.Ok(listOf(revision(5, hash = "other")))
         assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.submit(kim, document.toByteArray()).confirmation)
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/RobotOperationsTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/RobotOperationsTest.kt
index 468b1a2..092b4fc 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/RobotOperationsTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/RobotOperationsTest.kt
@@ -72,7 +72,7 @@ class RobotOperationsTest {
         assertEquals("robot r1", row.target)
         assertEquals("정비", row.reason)
         assertEquals(OperationResult.SUCCEEDED, row.result)
-        assertTrue(row.registryResponse!!.contains("\"status\": 200"), row.registryResponse)
+        assertTrue(row.targetResponse!!.contains("\"status\": 200"), row.targetResponse)
     }
 
     @Test
@@ -112,7 +112,7 @@ class RobotOperationsTest {
         assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
         assertEquals(setOf(outcome.requestId), rows.map { it.requestId }.toSet())
         // 확인 행에는 재조회에서 본 원장 상태가 남는다.
-        assertTrue(rows.first().registryResponse!!.contains("\"status\": \"RETIRED\""), rows.first().registryResponse)
+        assertTrue(rows.first().targetResponse!!.contains("\"status\": \"RETIRED\""), rows.first().targetResponse)
     }
 
     @Test
diff --git a/ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt b/ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt
index dcb6411..67c7b6d 100644
--- a/ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt
+++ b/ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt
@@ -67,7 +67,7 @@ class SiteSettingsOperationsTest {
         assertEquals(SiteSettingsOperations.TARGET, record.target)
         assertEquals(OperationResult.SUCCEEDED, record.result)
         assertEquals("시험", record.reason)
-        assertNull(record.registryResponse)
+        assertNull(record.targetResponse)
         assertEquals(
             json.readTree("""{"op":"CHANGE_SITE_SETTINGS","baseVersion":1,"connectionThresholdSeconds":60}"""),
             json.readTree(record.request),
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && ./gradlew :ops-service:test -q
```
Expected: ops-service 124, 실패 0. 백그라운드로 돌린다(e2e 의 `SkeletonTest` 는 Task 6 에서 함께 돈다).

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git add ops-service/src/main/resources/db/ops/V3__target_response.sql ops-service/src/main/kotlin/dev/picasso/ops/service/log/OperationLog.kt ops-service/src/main/kotlin/dev/picasso/ops/service/operations/OperationRunner.kt ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterOperationsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/OperationLogTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/ProfileOperationsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RobotOperationsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt && git commit -F - <<'EOF'
refactor(ops-service): 조작 기록 응답 칸 이름을 target_response 로 변경

- V3 마이그레이션과 Kotlin 이름, 그 이름을 쓰던 시험
- e2e `SkeletonTest` 의 마이그레이션 목록 1·2·3

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 4: 작업 지시 배정 가능 판정·제출, 실행·셀 전달

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/Eligibility.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/JobOrderForm.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/JobOrderOperations.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/web/JobOrderController.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/HostClientTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderBench.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderControllerTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderEligibilityTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderFormTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderOperationsTest.kt`
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt`, `ops-service/src/main/resources/ops-service.properties`

- [ ] **Step 1: 새 파일 11개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/host && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/joborders && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/Eligibility.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/Eligibility.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/joborders && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/JobOrderForm.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/JobOrderForm.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/joborders && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/JobOrderOperations.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/JobOrderOperations.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/web && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/web/JobOrderController.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/web/JobOrderController.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/HostClientTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/HostClientTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderBench.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderBench.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderControllerTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderControllerTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderEligibilityTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderEligibilityTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderFormTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderFormTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderOperationsTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderOperationsTest.kt
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt`:

```kotlin
package dev.picasso.ops.service.host

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 실행 호스트 읽기 한 번의 결과(S3a 스펙 §8). «없음» 과 «모름» 을 접지 않는다.
 *
 * 연결 실패·시간 초과·200 아님·해석 불가 어느 것이든 값을 모른다는 점이 같아 [Silent] 하나로 둔다. 호스트에는 토큰이 없어
 * registry 의 `Unauthorized` 같은 하위 범주가 없다. 제출은 [HostWrite] 로 따로 돌려준다.
 */
sealed interface HostCall<out T> {
    data class Ok<T>(val value: T) : HostCall<T>

    data class Silent(val cause: String) : HostCall<Nothing>
}

/**
 * 제출 한 번의 결과. 응답이 오면 코드와 본문을 그대로 넘기고, 분류는 부르는 쪽이 한다(스펙 §8).
 * 응답이 오지 않으면 호스트가 받았는지 모르는 것이며, 거부와 섞지 않는다.
 */
sealed interface HostWrite {
    data class Answered(val status: Int, val body: String) : HostWrite

    data class NoResponse(val cause: String) : HostWrite
}

/** 호스트의 스킬 적합 판정. [UNKNOWN] 은 호스트가 기체 케이퍼빌리티를 못 물어봤다는 뜻이고 적합이 아니다. */
enum class HostSkillFit { FIT, MISSING, UNKNOWN }

/**
 * 호스트 판정 한 행(S3a JSON 계약 §2.2). 호스트는 스킬 적합과 도는 실행만 보고, 시운전·연결은 운영 서비스가 합친다(T3).
 *
 * @param runningExecutionId 이 기체에서 물리 상태가 정착하지 않은 실행. 없으면 널. 운영자 보류도 도는 실행이다
 * @param passed 스킬 적합이고 도는 실행이 없다
 * @param reasons 통과하지 못한 이유(화면 표시용). 통과면 비어 있다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostEligibility(
    val robotId: String,
    val skillFit: HostSkillFit,
    val missingSkills: List<String> = emptyList(),
    val runningExecutionId: String? = null,
    val passed: Boolean,
    val reasons: List<String> = emptyList(),
)

/** `POST /host/eligibility` 의 답. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostEligibilityList(val robots: List<HostEligibility>)

/** 미들웨어 제출 결과 넷의 이름(S3a JSON 계약 §4). */
enum class HostSubmitResult { ACCEPTED, IDEMPOTENT, REJECTED, UNASSIGNED }

/** `assign` 이 기체마다 낸 미배정 사유. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostRefusal(val robotId: String, val reason: String)

/**
 * `POST /host/job-orders` 의 200 본문(S3a JSON 계약 §4). 결과가 무엇이든 200 이며 결과는 [result] 에 있다.
 *
 * @param executionId·robotId ACCEPTED·IDEMPOTENT 일 때만 있다
 * @param rejectionReason REJECTED 일 때만 있다
 * @param refusals UNASSIGNED 일 때 `assign` 이 기체마다 낸 사유
 * @param excluded 호스트가 판정에서 빼 `assign` 에 넘기지 않은 기체의 판정 행
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostSubmitOutcome(
    val result: HostSubmitResult,
    val executionId: String? = null,
    val robotId: String? = null,
    val rejectionReason: String? = null,
    val refusals: List<HostRefusal> = emptyList(),
    val excluded: List<HostEligibility> = emptyList(),
)

/** `GET /host/executions` 의 실행 한 줄 중 재조회가 쓰는 칸. 나머지는 버린다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostExecutionRef(
    val executionId: String,
    val jobOrderId: String,
    val robotId: String,
    val physicalState: String,
)

/** `GET /host/executions` 를 재조회로 읽은 모양. 화면에 넘길 때는 해석하지 않고 그대로 넘긴다([HostReads.executions]). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostExecutions(val instanceId: String, val executions: List<HostExecutionRef>)

/** 호스트 읽기 셋. 시험이 호스트 없이 대신 끼운다. */
interface HostReads {
    /** 작업 지시 본문 [jobOrder] 를 [robotIds] 의 기체마다 판정한다. */
    fun eligibility(jobOrder: ObjectNode, robotIds: List<String>): HostCall<List<HostEligibility>>

    /** `GET /host/executions` 본문 그대로. 객체가 아니면 모름이다. */
    fun executions(): HostCall<JsonNode>

    /** `GET /host/cell` 본문 그대로. 셀 대역을 못 읽은 호스트는 `{"cell": null}` 을 200 으로 준다. */
    fun cell(): HostCall<JsonNode>
}

/** 호스트 제출. 시험이 호스트 없이 대신 끼운다. */
fun interface HostWrites {
    fun submit(jobOrder: ObjectNode, candidates: List<String>): HostWrite
}

/**
 * 실행 호스트 REST 클라이언트(S3a 스펙 §8). 호스트는 루프백·무인증이라 토큰을 싣지 않는다.
 *
 * 연결 제한은 registry 와 같고 요청 제한은 더 길다. 호스트는 판정과 제출을 자기 잠금 아래에서 하며, 그 안에서 mimic 에
 * gRPC 를 부르고, mimic 은 엔진 잠금 아래에서 registry 로 태스크 관측을 동기 HTTP 로 적재한다(요청 제한 3초, 스펙 §5.3).
 * 호스트가 registry 장애 한 번을 기다리는 동안 운영 서비스가 먼저 끊으면, 받은 제출을 응답 없음으로 남기게 된다.
 */
class HostClient(
    baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
    private val json: ObjectMapper = jacksonObjectMapper(),
) : HostReads, HostWrites, AutoCloseable {

    private val base = checkBaseUrl(baseUrl)

    /** JDK 21 의 HttpClient 는 닫아야 셀렉터 스레드가 끝난다. 스프링이 빈을 내릴 때 부른다. */
    override fun close() = http.close()

    override fun eligibility(jobOrder: ObjectNode, robotIds: List<String>): HostCall<List<HostEligibility>> {
        val body = json.createObjectNode()
        body.set<JsonNode>("jobOrder", jobOrder)
        body.putArray("robotIds").apply { robotIds.forEach(::add) }
        val response = when (val write = post("/host/eligibility", body)) {
            is HostWrite.NoResponse -> return HostCall.Silent(write.cause)
            is HostWrite.Answered -> write
        }
        if (response.status != 200) return HostCall.Silent("HTTP ${response.status}")
        return read(response.body) { json.readValue<HostEligibilityList?>(it)?.robots }
    }

    override fun executions(): HostCall<JsonNode> = get("/host/executions")

    override fun cell(): HostCall<JsonNode> = get("/host/cell")

    override fun submit(jobOrder: ObjectNode, candidates: List<String>): HostWrite {
        val body = json.createObjectNode()
        body.set<JsonNode>("jobOrder", jobOrder)
        body.putArray("candidates").apply { candidates.forEach(::add) }
        return post("/host/job-orders", body)
    }

    /** 객체 본문만 받는다. 호스트의 두 GET 은 늘 객체를 준다(S3a JSON 계약 §5·§6). */
    private fun get(path: String): HostCall<JsonNode> {
        val request = HttpRequest.newBuilder(URI.create(base + path)).timeout(REQUEST_TIMEOUT).GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            return HostCall.Silent("응답 없음: ${e.javaClass.simpleName}")
        }
        if (response.statusCode() != 200) return HostCall.Silent("HTTP ${response.statusCode()}")
        return read(response.body()) { body -> json.readTree(body).takeIf { it.isObject } }
    }

    private fun post(path: String, body: ObjectNode): HostWrite {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            HostWrite.Answered(response.statusCode(), response.body())
        } catch (e: IOException) {
            HostWrite.NoResponse("응답 없음: ${e.javaClass.simpleName}")
        }
    }

    /** [parse] 가 널을 내면(본문 `null`, 모양 어긋남) 값을 모르는 것이다. */
    private fun <T : Any> read(body: String, parse: (String) -> T?): HostCall<T> = try {
        parse(body)?.let { HostCall.Ok(it) } ?: HostCall.Silent("본문 모양이 다르다")
    } catch (e: JacksonException) {
        HostCall.Silent("본문 해석 실패: ${e.originalMessage}")
    }

    companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)

        /** 형식이 틀린 주소를 기동에서 잡는다. 그대로 두면 요청마다 호스트 불통으로 보인다. */
        fun checkBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
            require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                "실행 호스트 주소가 http(s)://호스트[:포트] 꼴이 아니다: '$baseUrl'"
            }
            return trimmed
        }
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/Eligibility.kt`:

```kotlin
package dev.picasso.ops.service.joborders

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostEligibility
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.robots.CommissioningState
import dev.picasso.ops.service.robots.Connection
import dev.picasso.ops.service.robots.RegistryState
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.robots.RobotView
import java.time.Clock
import java.time.Instant

/** 실행 호스트에 닿았는가. 기체별이 아니라 판정 한 번 전체의 상태다. */
enum class HostState { OK, HOST_SILENT }

/**
 * 기체 하나의 배정 가능 판정(S3a 스펙 §8, T3). 네 칸 중 시운전·연결은 운영 서비스가, 스킬 적합·도는 실행은 호스트가 판정한다.
 *
 * 널은 «모름» 이다. registry 가 답하지 않으면 [commissioning]·[connection] 이 널이고, 호스트가 답하지 않으면 [host] 가 널이다.
 * 직전 목록의 값을 대신 쓰지 않는다. 직전 목록의 연결 칸은 그 목록을 읽은 시각으로 판정한 것이라, 지금은 낡았어도 신선으로 보인다.
 *
 * @param settingsVersion [connection] 을 판정한 현장 설정 버전(근거 버전). [connection] 이 널이면 널
 * @param host 호스트 판정 행. 스킬 적합(모자란 스킬)과 도는 실행이 여기 있다
 * @param eligible 네 칸이 다 알려졌고 다 통과했다. «모름» 이 하나라도 있으면 거짓이다
 * @param reasons 배정 가능이 아닌 이유(화면 표시용). 시운전, 연결, 호스트 판정 순서다. 배정 가능이면 비어 있다
 */
data class RobotEligibility(
    val robotId: String,
    val commissioning: CommissioningState?,
    val connection: Connection?,
    val settingsVersion: Long?,
    val host: HostEligibility?,
    val eligible: Boolean,
    val reasons: List<String>,
)

/**
 * `POST /api/job-orders/eligibility` 의 답.
 *
 * @param registry 기체 목록을 읽을 때의 registry 상태. `OK` 가 아니면 시운전·연결 칸이 모두 «모름» 이다
 * @param robotsAsOf 기체 목록을 registry 에서 읽은 시각. [checkedAt] 과 다르면 직전 목록이다
 * @param host 호스트 상태. `HOST_SILENT` 면 호스트 칸이 모두 «모름» 이다
 * @param robots 널이면 기체 목록을 한 번도 읽지 못한 것(«모름»), 빈 목록은 기체가 없는 것이다
 */
data class EligibilityView(
    val checkedAt: Instant,
    val registry: RegistryState,
    val robotsAsOf: Instant?,
    val host: HostState,
    val robots: List<RobotEligibility>?,
)

/**
 * 배정 가능 판정(S3a 스펙 §8). 기체 목록은 [robots] 의 기존 판정(S1·S2 의 시운전과 연결)을 그대로 쓰고, 호스트 판정을 기체마다
 * 붙인다. 기체는 그 사이트의 목록 전부다. 퇴역 기체도 행이 있고 시운전 칸이 `RETIRED` 다.
 *
 * registry 가 답하지 않으면 직전 목록의 기체 id 로 호스트에 묻는다. 호스트 칸은 registry 와 따로 알 수 있기 때문이다.
 */
class JobOrderEligibility(
    private val robots: RobotListService,
    private val host: HostReads,
    private val clock: Clock,
) {

    fun judge(jobOrder: ObjectNode): EligibilityView {
        val list = robots.read()
        val known = list.registry == RegistryState.OK
        val ids = list.robots.orEmpty().map { it.robot.robotId }
        val hostCall = if (ids.isEmpty()) HostCall.Ok(emptyList()) else host.eligibility(jobOrder, ids)
        val hostRows = (hostCall as? HostCall.Ok)?.value?.associateBy { it.robotId }
        return EligibilityView(
            checkedAt = clock.instant(),
            registry = list.registry,
            robotsAsOf = list.robotsAsOf,
            host = if (hostRows != null) HostState.OK else HostState.HOST_SILENT,
            robots = list.robots?.map { view ->
                row(view, list.registry, known, list.settingsVersion, list.connectionThresholdSeconds, hostRows)
            },
        )
    }

    private fun row(
        view: RobotView,
        registry: RegistryState,
        known: Boolean,
        settingsVersion: Long?,
        thresholdSeconds: Long?,
        hostRows: Map<String, HostEligibility>?,
    ): RobotEligibility {
        val commissioning = if (known) view.commissioning?.state else null
        val connection = if (known) view.connection else null
        val hostRow = hostRows?.get(view.robot.robotId)
        val reasons = buildList {
            if (!known) {
                add(
                    if (registry == RegistryState.REGISTRY_UNAUTHORIZED) {
                        "운영자 토큰이 registry 와 맞지 않아 시운전·연결을 모른다"
                    } else {
                        "registry 가 답하지 않아 시운전·연결을 모른다"
                    },
                )
            } else {
                when (commissioning) {
                    CommissioningState.COMPLETE -> Unit
                    CommissioningState.INCOMPLETE -> add("시운전이 끝나지 않았다")
                    CommissioningState.RETIRED -> add("퇴역한 기체다")
                    null -> add("시운전을 모른다")
                }
                when (connection) {
                    Connection.FRESH, null -> Unit
                    Connection.STALE -> add("연결이 오래됐다(기준 ${thresholdSeconds}초, 현장 설정 버전 $settingsVersion)")
                    Connection.NO_REPORT -> add("생존 보고가 없다")
                }
            }
            when {
                hostRows == null -> add("실행 호스트가 답하지 않아 스킬 적합·도는 실행을 모른다")
                hostRow == null -> add("실행 호스트가 이 기체를 판정하지 않았다")
                else -> addAll(hostRow.reasons)
            }
        }
        val eligible = commissioning == CommissioningState.COMPLETE &&
            connection == Connection.FRESH &&
            hostRow != null && hostRow.passed
        return RobotEligibility(
            robotId = view.robot.robotId,
            commissioning = commissioning,
            connection = connection,
            settingsVersion = if (connection != null) settingsVersion else null,
            host = hostRow,
            eligible = eligible,
            reasons = reasons,
        )
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/JobOrderForm.kt`:

```kotlin
package dev.picasso.ops.service.joborders

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** 받지 않는 폼 초안. [error] 가 사전 거부의 `error` 칸이다. 호스트에 닿지 않으므로 조작 기록에 남지 않는다. */
class FormRejection(val error: String, detail: String) : RuntimeException(detail)

/** 화면 작업 지시 폼의 초안(S3a 스펙 §9.1). 임무마다 입력이 다르다. */
sealed interface JobOrderDraft {
    val workMasterId: String

    /** 이 초안이 미들웨어에서 만들 단위 id 들. 겹침 검사가 쓴다(스펙 §7.5). */
    val unitIds: List<String>
}

/**
 * InspectAsset 의 점검 대상 하나. [id] 는 기체 `inspect` 스킬의 `target` 파라미터로 가므로 [JobOrderForm.MAX_TARGET_ID_LENGTH]
 * 자를 넘지 않는다. [location] 은 기체가 아는 명칭이어야 하나 S3a 는 검사하지 않는다(스펙 §12).
 */
data class InspectionTarget(val id: String, val location: String)

/** InspectAsset 초안. 대상마다 이동 단위 `<id>.travel` 과 점검 단위 `<id>` 둘이 생긴다. */
data class InspectAssetDraft(val targets: List<InspectionTarget>) : JobOrderDraft {
    override val workMasterId: String get() = JobOrderForm.INSPECT_ASSET
    override val unitIds: List<String> get() = targets.flatMap { listOf(it.id + JobOrderForm.TRAVEL_SUFFIX, it.id) }
}

/**
 * PrepareSequencedRack 초안. 단위는 슬롯마다 하나이고 단위 id 가 슬롯 id 다.
 *
 * @param presentation 자재를 집을 제시 자리. 화면이 셀 대역의 제시 자리 목록에서 자재를 고르면 정해진다(스펙 §7.5)
 */
data class PrepareSequencedRackDraft(
    val slots: List<String>,
    val material: String,
    val presentation: String,
) : JobOrderDraft {
    override val workMasterId: String get() = JobOrderForm.PREPARE_SEQUENCED_RACK
    override val unitIds: List<String> get() = slots
}

/**
 * 폼 초안을 읽고 작업 지시 본문(JobOrder JSON)을 만든다(S3a 스펙 §7.5). 운영 서비스는 picasso 타입을 쓰지 않으므로 칸 이름만
 * picasso `JobOrder` 와 맞춘다.
 *
 * 폼 모양:
 * - InspectAsset: `{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"}]}`
 * - PrepareSequencedRack: `{"workMasterId":"PrepareSequencedRack","slots":["RACK-204.S01"],"material":"ENGINE-COVER-A",
 *   "presentation":"SEQ-IN-02.BIN-A"}`
 *
 * 그 임무가 쓰지 않는 칸은 읽지 않는다. 단위 id 가 겹치는 초안은 막는다. 겹치면 미들웨어의 태스크 id(`작업 지시 id#단위 id`)가
 * 같아져 두 단위가 한 핸들로 접힌다(스펙 §4). 장소 이름과 슬롯이 기체가 아는 명칭인지는 보지 않는다(스펙 §12).
 */
object JobOrderForm {

    const val INSPECT_ASSET = "InspectAsset"
    const val PREPARE_SEQUENCED_RACK = "PrepareSequencedRack"

    /** 화면에 내는 임무. DeliverContainer 는 플릿 포트 구현이 없어 받지 않는다(스펙 §1). */
    val WORK_MASTERS: Set<String> = setOf(INSPECT_ASSET, PREPARE_SEQUENCED_RACK)

    /** 미들웨어가 InspectAsset 의 이동 단위 id 에 붙이는 꼬리(picasso `InspectAsset.TRAVEL_SUFFIX`). */
    const val TRAVEL_SUFFIX = ".travel"

    /**
     * 점검 대상 id 의 최대 길이. 대상 id 가 기체 `inspect` 스킬의 `target` 파라미터가 되고, 기체 프로파일이 그 파라미터를 64자로
     * 묶는다(`max_length`). 넘으면 미들웨어가 배정한 뒤 기체가 StartTask 를 거부하므로 폼에서 먼저 막는다. 길이는 mimic 의
     * 파라미터 검사처럼 UTF-16 문자 수로 센다.
     */
    const val MAX_TARGET_ID_LENGTH = 64

    const val BAD_REQUEST = "JOB_ORDER_BAD_REQUEST"
    const val UNKNOWN_WORK_MASTER = "UNKNOWN_WORK_MASTER"
    const val UNIT_ID_CONFLICT = "UNIT_ID_CONFLICT"

    /** 임무마다 고정한 요구 근거 등급(스펙 §7.5). InspectAsset 의 최고 근거는 E0 이다. */
    private val REQUIRED_EVIDENCE = mapOf(INSPECT_ASSET to "E0", PREPARE_SEQUENCED_RACK to "E2")

    /** 못 읽으면 [FormRejection] 을 던진다. 판정 순서: 본문 객체 → WorkMaster → 그 임무의 칸 → 단위 id 겹침. */
    fun read(body: JsonNode?): JobOrderDraft {
        if (body == null || !body.isObject) throw FormRejection(BAD_REQUEST, "본문이 JSON 객체가 아니다")
        val workMasterId = text(body, "workMasterId")
        val draft = when (workMasterId) {
            INSPECT_ASSET -> InspectAssetDraft(
                objects(body, "targets").map { InspectionTarget(targetId(it), text(it, "location")) },
            )
            PREPARE_SEQUENCED_RACK -> PrepareSequencedRackDraft(
                strings(body, "slots"), text(body, "material"), text(body, "presentation"),
            )
            else -> throw FormRejection(UNKNOWN_WORK_MASTER, "받지 않는 임무다: $workMasterId (받는 것: ${WORK_MASTERS.sorted()})")
        }
        val conflicts = draft.unitIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        if (conflicts.isNotEmpty()) {
            throw FormRejection(UNIT_ID_CONFLICT, "단위 id 가 겹친다: ${conflicts.joinToString(", ")}")
        }
        return draft
    }

    /**
     * 작업 지시 본문. 버전은 1, 파라미터는 비어 있고, 요구 근거 등급은 임무마다 고정이다.
     *
     * PrepareSequencedRack 의 자재 요구는 슬롯 수와 같아야 미들웨어 관문의 정합 검사를 지난다(스펙 §4). 제시 자리는 하나이며
     * 미들웨어가 슬롯의 `material` 속성으로 그 자리를 찾는다.
     */
    fun jobOrder(draft: JobOrderDraft, jobOrderId: String, json: ObjectMapper): ObjectNode {
        val order = json.createObjectNode()
            .put("jobOrderId", jobOrderId)
            .put("workMasterId", draft.workMasterId)
            .put("version", 1)
            .put("requiredEvidence", REQUIRED_EVIDENCE.getValue(draft.workMasterId))
        order.putObject("parameters")
        val materials = order.putArray("materialRequirements")
        val equipment = order.putArray("equipmentRequirements")
        fun requirement(id: String, use: String, property: String, value: String) {
            equipment.addObject().put("id", id).put("equipmentUse", use).putObject("properties").put(property, value)
        }
        when (draft) {
            is InspectAssetDraft -> draft.targets.forEach { requirement(it.id, "inspection_target", "location", it.location) }
            is PrepareSequencedRackDraft -> {
                materials.addObject().put("materialDefinitionId", draft.material).put("quantity", draft.slots.size)
                draft.slots.forEach { requirement(it, "destination", "material", draft.material) }
                requirement(draft.presentation, "source", "material", draft.material)
            }
        }
        return order
    }

    /** 비어 있지 않은 문자열 칸. */
    private fun text(node: JsonNode, field: String): String =
        node.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            ?: throw FormRejection(BAD_REQUEST, "$field 가 비어 있지 않은 문자열이 아니다")

    /** 점검 대상 id. 비어 있지 않고 [MAX_TARGET_ID_LENGTH] 자 이하다. */
    private fun targetId(node: JsonNode): String {
        val id = text(node, "id")
        if (id.length > MAX_TARGET_ID_LENGTH) {
            throw FormRejection(BAD_REQUEST, "대상 id 가 ${MAX_TARGET_ID_LENGTH}자를 넘는다: ${id.length}자")
        }
        return id
    }

    /** 비어 있지 않은 객체 배열. */
    private fun objects(node: JsonNode, field: String): List<JsonNode> {
        val array = node.get(field)
        if (array == null || !array.isArray || array.isEmpty || array.any { !it.isObject }) {
            throw FormRejection(BAD_REQUEST, "$field 가 비어 있지 않은 객체의 배열이 아니다")
        }
        return array.toList()
    }

    /** 비어 있지 않은 문자열의 비어 있지 않은 배열. */
    private fun strings(node: JsonNode, field: String): List<String> {
        val array = node.get(field)
        if (array == null || !array.isArray || array.isEmpty || array.any { !it.isTextual || it.asText().isBlank() }) {
            throw FormRejection(BAD_REQUEST, "$field 가 비어 있지 않은 문자열의 배열이 아니다")
        }
        return array.map { it.asText() }
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/JobOrderOperations.kt`:

```kotlin
package dev.picasso.ops.service.joborders

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostExecutionRef
import dev.picasso.ops.service.host.HostExecutions
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.host.HostSubmitOutcome
import dev.picasso.ops.service.host.HostSubmitResult
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.host.HostWrites
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 작업 지시 제출의 200 응답(S3a 스펙 §8). 기존 `OperationOutcome` 과 모양이 달라 화면이 따로 읽는다.
 *
 * @param jobOrderId 운영 서비스가 만든 작업 지시 id. 조작 기록의 대상이고 실행 목록의 `jobOrderId` 와 같다. 화면이 제출 직후
 *   실행 목록에서 그 행을 찾는 데 쓴다
 * @param result 처음 남긴 행의 결과. 성공, 거부, 응답 없음 중 하나다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했으면 널이다
 * @param outcome 호스트 응답. 호스트가 안 닿았거나(연결 실패, 시간 초과, 5xx) 200 본문을 못 읽었으면 널이다
 */
data class JobOrderOutcome(
    val requestId: UUID,
    val jobOrderId: String,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val outcome: HostSubmitOutcome?,
)

/** 제출 한 번의 답. 후보가 없으면 호스트를 부르지 않는다. */
sealed interface JobOrderSubmission {
    data class Submitted(val outcome: JobOrderOutcome) : JobOrderSubmission

    /** 배정 가능한 기체가 없다. [detail] 은 기체별 이유를 이은 문장이다. 조작 기록에 남지 않는다. */
    data class NoEligibleRobot(val detail: String) : JobOrderSubmission
}

/**
 * 작업 지시 제출(S3a 스펙 §8). 작업 지시 id 를 만들고, 배정 가능을 다시 판정해 통과한 기체만 후보로 호스트에 넘기고, 조작 기록을
 * 직접 쓴다.
 *
 * `OperationRunner` 를 쓰지 않는 것은 결과를 가르는 자리가 달라서다. registry 는 HTTP 상태 코드로 결과를 말하지만 호스트는
 * 200 본문의 `result` 로 말한다. 응답 없음과 재조회의 규칙은 같다. 응답이 오지 않거나 5xx 면 «응답 없음» 을 남기고
 * [requeryDelay] 뒤 `GET /host/executions` 를 한 번 읽어, 그 작업 지시 id 의 실행이 있으면 반영됨, 없으면 반영 안 됨을 같은
 * 요청 id 의 새 행으로 붙인다. 재조회도 못 읽으면 행을 붙이지 않는다. 재시도는 하지 않는다.
 *
 * 200 인데 본문을 못 읽으면 응답 없음과 같이 다룬다. 호스트는 답했지만 무엇을 했는지 모르기 때문이다.
 *
 * 후보가 없으면 호스트를 부르지 않고 기록하지 않는다. 상태를 바꾸는 쪽에 닿지 않은 요청은 조작이 아니다(Guard 의 관례).
 */
class JobOrderOperations(
    private val eligibility: JobOrderEligibility,
    private val writes: HostWrites,
    private val reads: HostReads,
    private val log: OperationLog,
    private val clock: Clock,
    private val requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = jacksonObjectMapper(),
) {

    fun submit(actor: Actor, draft: JobOrderDraft): JobOrderSubmission {
        val requestId = UUID.randomUUID()
        val jobOrderId = jobOrderId(requestId)
        val order = JobOrderForm.jobOrder(draft, jobOrderId, json)
        val judged = eligibility.judge(order)
        val candidates = judged.robots.orEmpty().filter { it.eligible }.map { it.robotId }
        if (candidates.isEmpty()) return JobOrderSubmission.NoEligibleRobot(noEligibleDetail(judged))

        val request = json.createObjectNode().put("op", OP)
        request.set<JsonNode>("jobOrder", order)
        request.putArray("candidates").apply { candidates.forEach(::add) }
        val requestJson = json.writeValueAsString(request)
        fun record(result: OperationResult, response: String) =
            log.append(requestId, actor, jobOrderId, requestJson, null, result, response)

        val write = writes.submit(order, candidates)
        if (write is HostWrite.NoResponse) {
            record(OperationResult.NO_RESPONSE, json.writeValueAsString(json.createObjectNode().put("cause", write.cause)))
            return confirm(requestId, jobOrderId, ::record)
        }
        write as HostWrite.Answered
        val response = responseJson(write)
        return when (write.status) {
            in 200..299 -> {
                val outcome = parse(write.body)
                if (outcome == null) {
                    record(OperationResult.NO_RESPONSE, response)
                    return confirm(requestId, jobOrderId, ::record)
                }
                val result = resultOf(outcome.result)
                record(result, response)
                JobOrderSubmission.Submitted(JobOrderOutcome(requestId, jobOrderId, result, null, outcome))
            }
            in 400..499 -> {
                record(OperationResult.REJECTED, response)
                JobOrderSubmission.Submitted(JobOrderOutcome(requestId, jobOrderId, OperationResult.REJECTED, null, hostRefused(write)))
            }
            else -> {
                record(OperationResult.NO_RESPONSE, response)
                confirm(requestId, jobOrderId, ::record)
            }
        }
    }

    /** 응답 없음 뒤 재조회. 읽지 못하면 행을 붙이지 않고 확인 결과를 널로 둔다. */
    private fun confirm(
        requestId: UUID,
        jobOrderId: String,
        record: (OperationResult, String) -> Unit,
    ): JobOrderSubmission {
        if (!requeryDelay.isZero) Thread.sleep(requeryDelay)
        val seen = when (val call = reads.executions()) {
            is HostCall.Ok -> runCatching { json.treeToValue(call.value, HostExecutions::class.java) }.getOrNull()
            is HostCall.Silent -> null
        }
        if (seen == null) {
            return JobOrderSubmission.Submitted(JobOrderOutcome(requestId, jobOrderId, OperationResult.NO_RESPONSE, null, null))
        }
        val execution = seen.executions.firstOrNull { it.jobOrderId == jobOrderId }
        val confirmation = if (execution != null) OperationResult.CONFIRMED_APPLIED else OperationResult.CONFIRMED_NOT_APPLIED
        record(confirmation, json.writeValueAsString(observed(seen.instanceId, execution)))
        return JobOrderSubmission.Submitted(JobOrderOutcome(requestId, jobOrderId, OperationResult.NO_RESPONSE, confirmation, null))
    }

    /** 확인 행의 응답 칸. 재조회에서 본 그 실행(없으면 널)과 호스트 인스턴스를 남겨, 그 행만 읽어도 판정 근거가 보이게 한다. */
    private fun observed(instanceId: String, execution: HostExecutionRef?): JsonNode {
        val node = json.createObjectNode()
        val seen = execution?.let {
            json.createObjectNode()
                .put("instanceId", instanceId)
                .put("executionId", it.executionId)
                .put("robotId", it.robotId)
                .put("physicalState", it.physicalState)
        }
        node.set<JsonNode>("observed", seen ?: json.nullNode())
        return node
    }

    private fun parse(body: String): HostSubmitOutcome? = try {
        json.readValue(body, HostSubmitOutcome::class.java)
    } catch (e: JacksonException) {
        null
    }

    /**
     * 호스트가 4xx 로 거부했다. 본문은 `{error, detail}` 이고 제출 결과 모양이 아니므로, 화면이 한 모양으로 읽게 거부 사유에 옮긴다.
     * 운영 서비스가 폼을 먼저 검사하므로 정상 흐름에서는 나오지 않는다.
     */
    private fun hostRefused(write: HostWrite.Answered): HostSubmitOutcome {
        val body = runCatching { json.readTree(write.body) }.getOrNull()?.takeIf { it.isObject }
        val reason = listOfNotNull(
            "실행 호스트가 거부했다(HTTP ${write.status})",
            body?.get("error")?.asText(),
            body?.get("detail")?.asText(),
        ).joinToString(": ")
        return HostSubmitOutcome(HostSubmitResult.REJECTED, rejectionReason = reason)
    }

    /** 조작 기록의 응답 칸. 코드와 본문을 남긴다. 본문이 JSON 이 아니면 글자로 남긴다(`OperationRunner` 와 같은 모양). */
    private fun responseJson(write: HostWrite.Answered): String {
        val node = json.createObjectNode().put("status", write.status)
        val body = runCatching { json.readTree(write.body) }.getOrNull()?.takeIf { it.isObject }
        if (body != null) node.set<JsonNode>("body", body) else node.put("body", write.body)
        return json.writeValueAsString(node)
    }

    /** 사이트 날짜(UTC)와 요청 id 앞 8자리. 운영 서비스를 재기동해도 겹치지 않아야 호스트의 멱등 판정에 걸리지 않는다. */
    private fun jobOrderId(requestId: UUID): String =
        "JO-${DATE.format(clock.instant().atOffset(ZoneOffset.UTC))}-${requestId.toString().take(8)}"

    companion object {
        const val OP = "SUBMIT_JOB_ORDER"
        const val NO_ELIGIBLE_ROBOT = "NO_ELIGIBLE_ROBOT"
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /**
         * 호스트 결과를 조작 기록 결과로 옮긴다(스펙 §8). 작업 수락과 멱등은 작업 지시가 실행에 붙은 것이고, 거부와 미배정은 붙지 않은
         * 것이다. 멱등은 운영 서비스가 매번 새 id 를 만들어 실제로는 나오지 않지만 매핑은 둔다(스펙 §7.6).
         */
        fun resultOf(result: HostSubmitResult): OperationResult = when (result) {
            HostSubmitResult.ACCEPTED, HostSubmitResult.IDEMPOTENT -> OperationResult.SUCCEEDED
            HostSubmitResult.REJECTED, HostSubmitResult.UNASSIGNED -> OperationResult.REJECTED
        }

        /** `NO_ELIGIBLE_ROBOT` 의 detail. 기체마다 `id: 이유; 이유` 를 ` / ` 로 잇는다. */
        fun noEligibleDetail(view: EligibilityView): String {
            val robots = view.robots ?: return "기체 목록을 아직 읽지 못했다"
            if (robots.isEmpty()) return "이 사이트에 기체가 없다"
            return robots.joinToString(" / ") { "${it.robotId}: ${it.reasons.joinToString("; ")}" }
        }
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/web/JobOrderController.kt`:

```kotlin
package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.joborders.FormRejection
import dev.picasso.ops.service.joborders.JobOrderDraft
import dev.picasso.ops.service.joborders.JobOrderEligibility
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.joborders.JobOrderSubmission
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 작업 지시와 실행 API(S3a 스펙 §8). 판정과 두 조회는 모드와 관계없고, 제출은 운영자 모드만 한다.
 *
 * 폼 본문은 바이트로 받아 직접 읽는다([SiteSettingsController] 와 같은 이유). 제출은 관문을 먼저 지나므로 엔지니어 모드의
 * 깨진 본문은 400 이 아니라 403 이다. 폼 오류(`JOB_ORDER_BAD_REQUEST`·`UNKNOWN_WORK_MASTER`·`UNIT_ID_CONFLICT`)와 후보 없음
 * (`NO_ELIGIBLE_ROBOT`)은 호스트에 닿지 않은 사전 거부라 조작 기록에 남지 않는다. 쓰기 본문은 `application/json` 만 받는다
 * (다른 출처 방어는 [RobotOperationsController]). 판정은 POST 지만 부작용이 없다. 폼 초안을 본문으로 실어야 해서 POST 다.
 *
 * 제출 결과는 호스트가 무엇을 답했든 200 과 [dev.picasso.ops.service.joborders.JobOrderOutcome] 이다. 호스트의 판단은 본문에 있다.
 * 실행 목록과 셀은 호스트 본문을 그대로 넘기고, 호스트가 안 닿으면 503 과 이유다.
 */
@RestController
class JobOrderController(
    private val eligibility: JobOrderEligibility,
    private val operations: JobOrderOperations,
    private val host: HostReads,
) {
    private val json = ObjectMapper()

    @PostMapping("/api/job-orders/eligibility", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun eligibility(@RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = drafted(body) { draft ->
        ResponseEntity.ok(eligibility.judge(JobOrderForm.jobOrder(draft, DRAFT_ID, json)))
    }

    @PostMapping("/api/job-orders", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        drafted(body) { draft ->
            when (val submission = operations.submit(actor, draft)) {
                is JobOrderSubmission.Submitted -> ResponseEntity.ok(submission.outcome)
                is JobOrderSubmission.NoEligibleRobot ->
                    reject(HttpStatus.BAD_REQUEST, JobOrderOperations.NO_ELIGIBLE_ROBOT, submission.detail)
            }
        }
    }

    @GetMapping("/api/executions")
    fun executions(): ResponseEntity<Any> = forwarded(host.executions())

    @GetMapping("/api/cell")
    fun cell(): ResponseEntity<Any> = forwarded(host.cell())

    private inline fun drafted(body: ByteArray?, action: (JobOrderDraft) -> ResponseEntity<Any>): ResponseEntity<Any> {
        val node = body?.let { runCatching { json.readTree(it) }.getOrNull() }
        val draft = try {
            JobOrderForm.read(node)
        } catch (e: FormRejection) {
            return reject(HttpStatus.BAD_REQUEST, e.error, e.message ?: "")
        }
        return action(draft)
    }

    private fun forwarded(call: HostCall<JsonNode>): ResponseEntity<Any> = when (call) {
        is HostCall.Ok -> ResponseEntity.ok(call.value)
        is HostCall.Silent -> reject(HttpStatus.SERVICE_UNAVAILABLE, HOST_SILENT, "실행 호스트가 답하지 않는다: ${call.cause}")
    }

    companion object {
        const val HOST_SILENT = "HOST_SILENT"

        /** 판정에 싣는 작업 지시 id. 호스트는 판정에서 id 를 쓰지 않으며, 제출은 새 id 로 다시 판정한다. */
        const val DRAFT_ID = "JO-DRAFT"
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/HostClientTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostClient
import dev.picasso.ops.service.host.HostEligibility
import dev.picasso.ops.service.host.HostSkillFit
import dev.picasso.ops.service.host.HostWrite
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** 실행 호스트 REST 클라이언트(S3a 스펙 §8). 호스트는 JDK HttpServer 대역이다. */
class HostClientTest {

    private var server: HttpServer? = null
    private val json = ObjectMapper()
    private val order = json.createObjectNode().put("jobOrderId", "JO-1").put("workMasterId", "InspectAsset")

    /** 경로마다 받은 요청(방법, Content-Type, 본문)을 남긴다. */
    private val seen = mutableMapOf<String, Triple<String, String?, String>>()

    private fun serve(vararg routes: Pair<String, Pair<Int, String>>): HostClient {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        routes.forEach { (path, answer) ->
            s.createContext(path) { exchange ->
                seen[path] = Triple(
                    exchange.requestMethod,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestBody.readAllBytes().decodeToString(),
                )
                val bytes = answer.second.toByteArray()
                exchange.sendResponseHeaders(answer.first, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        s.start()
        server = s
        return HostClient("http://127.0.0.1:${s.address.port}/")
    }

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `판정은 작업 지시 본문과 기체 id 를 JSON 으로 보내고 판정 행을 읽는다`() {
        val client = serve(
            "/host/eligibility" to (
                200 to """{"robots":[{"robotId":"quadruped-01","skillFit":"MISSING","missingSkills":["pick_place"],
                    "runningExecutionId":null,"passed":false,"reasons":["모자란 스킬: pick_place"],"later":1}]}"""
                ),
        )
        val row = assertIs<HostCall.Ok<List<HostEligibility>>>(
            client.eligibility(order, listOf("quadruped-01")),
        ).value.single()
        assertEquals(HostSkillFit.MISSING, row.skillFit)
        assertEquals(listOf("pick_place"), row.missingSkills)
        assertEquals(false, row.passed)
        val (method, contentType, body) = seen.getValue("/host/eligibility")
        assertEquals("POST", method)
        assertEquals("application/json", contentType)
        assertEquals(json.readTree("""{"jobOrder":{"jobOrderId":"JO-1","workMasterId":"InspectAsset"},"robotIds":["quadruped-01"]}"""), json.readTree(body))
    }

    @Test
    fun `판정이 200 아님이나 모양 어긋남이면 모름이다`() {
        val client = serve("/host/eligibility" to (400 to """{"error":"BAD_REQUEST","detail":"x"}"""))
        assertEquals(HostCall.Silent("HTTP 400"), client.eligibility(order, listOf("r1")))
        stop()
        val odd = serve("/host/eligibility" to (200 to """{"robots":[{"robotId":"r1","skillFit":"MAYBE","passed":true}]}"""))
        assertIs<HostCall.Silent>(odd.eligibility(order, listOf("r1")))
    }

    @Test
    fun `제출은 후보를 실어 보내고 상태 코드와 본문을 그대로 돌려준다`() {
        val client = serve("/host/job-orders" to (200 to """{"result":"UNASSIGNED"}"""))
        assertEquals(HostWrite.Answered(200, """{"result":"UNASSIGNED"}"""), client.submit(order, listOf("humanoid-01", "quadruped-01")))
        assertEquals(
            json.readTree("""{"jobOrder":{"jobOrderId":"JO-1","workMasterId":"InspectAsset"},"candidates":["humanoid-01","quadruped-01"]}"""),
            json.readTree(seen.getValue("/host/job-orders").third),
        )
    }

    @Test
    fun `닿지 않는 호스트는 판정과 조회가 모름이고 제출이 응답 없음이다`() {
        val client = serve()
        stop()
        server = null
        assertIs<HostCall.Silent>(client.eligibility(order, listOf("r1")))
        assertIs<HostCall.Silent>(client.executions())
        assertIs<HostCall.Silent>(client.cell())
        assertIs<HostWrite.NoResponse>(client.submit(order, listOf("r1")))
    }

    @Test
    fun `실행 목록과 셀은 객체 본문 그대로이고 200 아님과 객체 아님과 해석 불가는 모름이다`() {
        val client = serve(
            "/host/executions" to (200 to """{"instanceId":"mw-1","pumpedAt":null,"executions":[]}"""),
            "/host/cell" to (200 to """{"cell":null}"""),
        )
        assertEquals(json.readTree("""{"instanceId":"mw-1","pumpedAt":null,"executions":[]}"""), (client.executions() as HostCall.Ok).value)
        assertEquals(json.readTree("""{"cell":null}"""), (client.cell() as HostCall.Ok).value)
        stop()
        val broken = serve("/host/executions" to (200 to "[]"), "/host/cell" to (503 to ""))
        assertIs<HostCall.Silent>(broken.executions())
        assertEquals(HostCall.Silent("HTTP 503"), broken.cell())
        stop()
        assertIs<HostCall.Silent>(serve("/host/executions" to (200 to "not json")).executions())
    }

    @Test
    fun `호스트 주소 형식이 틀리면 기동에서 멈춘다`() {
        assertFailsWith<IllegalArgumentException> { HostClient("127.0.0.1:8785") }
        assertEquals("http://127.0.0.1:8785", HostClient.checkBaseUrl("http://127.0.0.1:8785/"))
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderBench.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostEligibility
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.host.HostSkillFit
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.host.HostWrites
import dev.picasso.ops.service.joborders.JobOrderEligibility
import dev.picasso.ops.service.registry.CommissioningSource
import dev.picasso.ops.service.registry.RegistryBinding
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RegistrySoftware
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.settings.SiteSettingsValues
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * 배정 가능·제출 시험의 대역 세트. registry 읽기와 실행 호스트를 기존 시험처럼 인터페이스 대역으로 끼운다.
 *
 * 기본은 기체 둘(humanoid-01, quadruped-01)이 다 시운전 완료·신선이고, 호스트가 둘 다 통과로 판정하고, 제출에 ACCEPTED 로
 * 답하는 것이다. 시험이 칸을 바꿔 한 칸씩 어긋나게 한다.
 */
class JobOrderBench {

    val at: Instant = Instant.parse("2026-10-08T00:00:00Z")
    val clock: Clock = Clock.fixed(at, ZoneOffset.UTC)
    private val json = jacksonObjectMapper()

    var robots: RegistryCall<List<RegistryRobot>> = RegistryCall.Ok(listOf(robot(HUMANOID), robot(QUADRUPED)))
    var bindings: RegistryCall<List<RegistryBinding>> = RegistryCall.Ok(listOf(binding(HUMANOID), binding(QUADRUPED)))

    /** 기체 id 목록을 받아 호스트 판정을 낸다. 기본은 모두 통과다. */
    var judge: (List<String>) -> HostCall<List<HostEligibility>> = { ids -> HostCall.Ok(ids.map(::pass)) }
    var answer: HostWrite = HostWrite.Answered(200, accepted(HUMANOID))
    var executions: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"instanceId":"mw-1","pumpedAt":null,"executions":[]}"""))
    var cell: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"cell":null}"""))

    /** 호스트가 받은 판정 요청(작업 지시 본문, 기체 id). */
    val judged = mutableListOf<Pair<ObjectNode, List<String>>>()

    /** 호스트가 받은 제출(작업 지시 본문, 후보). */
    val submitted = mutableListOf<Pair<ObjectNode, List<String>>>()

    /** 호스트 대역. 읽기와 제출이 이 벤치의 칸을 쓴다. */
    inner class FakeHost : HostReads, HostWrites {
        override fun eligibility(jobOrder: ObjectNode, robotIds: List<String>): HostCall<List<HostEligibility>> {
            judged += jobOrder to robotIds
            return judge(robotIds)
        }

        override fun executions(): HostCall<JsonNode> = this@JobOrderBench.executions

        override fun cell(): HostCall<JsonNode> = this@JobOrderBench.cell

        override fun submit(jobOrder: ObjectNode, candidates: List<String>): HostWrite {
            submitted += jobOrder to candidates
            return answer
        }
    }

    val host = FakeHost()

    val robotList = RobotListService(
        { robots },
        { RegistryCall.Ok(Unit) },
        SITE,
        clock,
        { SiteSettingsValues(SETTINGS_VERSION, Duration.ofSeconds(90)) },
        commissioning = object : CommissioningSource {
            override fun bindings(siteId: String) = bindings

            override fun software(siteId: String): RegistryCall<List<RegistrySoftware>> = RegistryCall.Ok(emptyList())
        },
    )

    val eligibility = JobOrderEligibility(robotList, host, clock)

    /** 실행 목록 본문. [jobOrderIds] 마다 humanoid-01 의 실행이 하나씩 있다. */
    fun executionsWith(vararg jobOrderIds: String): HostCall<JsonNode> {
        val rows = jobOrderIds.mapIndexed { i, id ->
            """{"executionId":"exec-${i + 1}","jobOrderId":"$id","workMasterId":"InspectAsset","missionVersion":null,
               "robotId":"$HUMANOID","physicalState":"RUNNING","units":[],"jobResponse":null}"""
        }
        return HostCall.Ok(json.readTree("""{"instanceId":"mw-1","pumpedAt":null,"executions":[${rows.joinToString(",")}]}"""))
    }

    companion object {
        const val SITE = "site-01"
        const val HUMANOID = "humanoid-01"
        const val QUADRUPED = "quadruped-01"
        const val SETTINGS_VERSION = 3L

        /** 기준 90초 안에 보고한 시운전 원장 상태의 기체. */
        fun robot(id: String, status: String = "CONFIRMED", lastReportedAt: String? = "2026-10-07T23:59:50Z") =
            RegistryRobot(robotId = id, siteId = SITE, status = status, lastReportedAt = lastReportedAt)

        fun binding(id: String, siteNames: String = "CONFIRMED") = RegistryBinding(
            robotId = id, vendor = "v", model = "m", profileRevisionId = 2, revision = 1, adapterName = "acme/fleet",
            adapterVersion = "1.0.0", conformanceStatus = "UNTESTED", active = true, siteNames = siteNames,
            adapterVersionId = 7, boundBy = "engineer/kim", boundAt = "t",
        )

        fun pass(id: String) = HostEligibility(id, HostSkillFit.FIT, emptyList(), null, true, emptyList())

        fun missing(id: String, vararg skills: String) = HostEligibility(
            id, HostSkillFit.MISSING, skills.toList(), null, false, listOf("모자란 스킬: ${skills.joinToString(", ")}"),
        )

        fun running(id: String, executionId: String) =
            HostEligibility(id, HostSkillFit.FIT, emptyList(), executionId, false, listOf("도는 실행이 있다: $executionId"))

        fun accepted(robotId: String) =
            """{"result":"ACCEPTED","executionId":"exec-1","robotId":"$robotId","rejectionReason":null,"refusals":[],"excluded":[]}"""

        val INSPECT_FORM = """{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"}]}"""
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderControllerTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.JobOrderBench.Companion.HUMANOID
import dev.picasso.ops.service.JobOrderBench.Companion.INSPECT_FORM
import dev.picasso.ops.service.JobOrderBench.Companion.QUADRUPED
import dev.picasso.ops.service.JobOrderBench.Companion.missing
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.joborders.EligibilityView
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.joborders.JobOrderOutcome
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.web.JobOrderController
import dev.picasso.ops.service.web.PreRejection
import dev.picasso.registry.PostgresSupport
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * 작업 지시 API 의 관문과 사전 거부(S3a 스펙 §8·§10). 컨트롤러를 스프링 없이 바로 부른다. 관문(`guarded`)과 폼 읽기, 상태 코드의
 * 고름이 컨트롤러 안에 있기 때문이다. `application/json` 제한은 스프링의 몫이라 통합 시험이 본다.
 */
class JobOrderControllerTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = JobOrderBench()
    private val controller = JobOrderController(
        bench.eligibility,
        JobOrderOperations(bench.eligibility, bench.host, bench.host, log, bench.clock, requeryDelay = Duration.ZERO),
        bench.host,
    )
    private val json = jacksonObjectMapper()

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    private fun submit(mode: String?, form: String = INSPECT_FORM, user: String? = "kim"): ResponseEntity<Any> =
        controller.submit(mode, user, form.toByteArray())

    private fun ResponseEntity<Any>.rejection(): PreRejection = assertIs<PreRejection>(body)

    @Test
    fun `엔지니어 모드의 작업 지시 제출은 403 이고 호스트를 부르지 않고 기록하지 않는다`() {
        val reply = submit("engineer")
        assertEquals(403, reply.statusCode.value())
        assertEquals("MODE_NOT_ALLOWED", reply.rejection().error)
        // 깨진 본문도 관문이 먼저다.
        assertEquals(403, submit("engineer", form = "작업 지시").statusCode.value())
        assertEquals(emptyList(), bench.judged)
        assertEquals(emptyList(), bench.submitted)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `행위자 헤더가 없거나 틀리면 400 ACTOR_REQUIRED 다`() {
        assertEquals("ACTOR_REQUIRED", submit(null).rejection().error)
        assertEquals(400, submit(null).statusCode.value())
        assertEquals("ACTOR_REQUIRED", submit("operator", user = "김 운영").rejection().error)
        assertEquals(emptyList(), bench.submitted)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `운영자 모드 제출은 200 과 요청 id·작업 지시 id·결과·확인·호스트 응답이다`() {
        val reply = submit("operator")
        assertEquals(200, reply.statusCode.value())
        val outcome = assertIs<JobOrderOutcome>(reply.body)
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertEquals(HUMANOID, outcome.outcome!!.robotId)
        val body = json.valueToTree<JsonNode>(outcome)
        assertEquals(listOf("requestId", "jobOrderId", "result", "confirmation", "outcome"), body.fieldNames().asSequence().toList())
        assertEquals(bench.submitted.single().first["jobOrderId"].asText(), body["jobOrderId"].asText())
        assertEquals(listOf(OperationResult.SUCCEEDED), log.list().map { it.result })
    }

    @Test
    fun `후보가 없으면 400 NO_ELIGIBLE_ROBOT 이고 detail 에 기체별 이유가 있고 기록하지 않는다`() {
        bench.judge = { ids -> HostCall.Ok(ids.map { missing(it, "inspect") }) }
        val reply = submit("operator")
        assertEquals(400, reply.statusCode.value())
        val rejection = reply.rejection()
        assertEquals(JobOrderOperations.NO_ELIGIBLE_ROBOT, rejection.error)
        assertEquals("$HUMANOID: 모자란 스킬: inspect / $QUADRUPED: 모자란 스킬: inspect", rejection.detail)
        assertEquals(emptyList(), bench.submitted)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `단위 id 가 겹치는 폼은 판정과 제출 모두 400 UNIT_ID_CONFLICT 이고 호스트에 닿지 않는다`() {
        val form = """{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"a"},{"id":"T1.travel","location":"b"}]}"""
        listOf(controller.eligibility(form.toByteArray()), submit("operator", form)).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals(JobOrderForm.UNIT_ID_CONFLICT, reply.rejection().error)
        }
        assertEquals("JOB_ORDER_BAD_REQUEST", controller.eligibility(null).rejection().error)
        assertEquals(emptyList(), bench.judged)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `판정은 모드 헤더 없이 되고 판정용 작업 지시 id 로 호스트에 묻는다`() {
        val reply = controller.eligibility(INSPECT_FORM.toByteArray())
        assertEquals(200, reply.statusCode.value())
        assertEquals(listOf(HUMANOID, QUADRUPED), assertIs<EligibilityView>(reply.body).robots!!.filter { it.eligible }.map { it.robotId })
        assertEquals(JobOrderController.DRAFT_ID, bench.judged.single().first["jobOrderId"].asText())
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `실행 목록과 셀은 호스트 본문을 그대로 넘기고 호스트가 안 닿으면 503 과 이유다`() {
        val executions = bench.executionsWith("JO-1")
        bench.executions = executions
        assertSame((executions as HostCall.Ok).value, controller.executions().body)
        assertSame((bench.cell as HostCall.Ok).value, controller.cell().body)

        bench.executions = HostCall.Silent("응답 없음: ConnectException")
        bench.cell = HostCall.Silent("HTTP 500")
        val down = controller.executions()
        assertEquals(503, down.statusCode.value())
        assertEquals(PreRejection(JobOrderController.HOST_SILENT, "실행 호스트가 답하지 않는다: 응답 없음: ConnectException"), down.body)
        assertEquals(503, controller.cell().statusCode.value())
        assertEquals("실행 호스트가 답하지 않는다: HTTP 500", controller.cell().rejection().detail)
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderEligibilityTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.JobOrderBench.Companion.HUMANOID
import dev.picasso.ops.service.JobOrderBench.Companion.QUADRUPED
import dev.picasso.ops.service.JobOrderBench.Companion.SETTINGS_VERSION
import dev.picasso.ops.service.JobOrderBench.Companion.binding
import dev.picasso.ops.service.JobOrderBench.Companion.missing
import dev.picasso.ops.service.JobOrderBench.Companion.pass
import dev.picasso.ops.service.JobOrderBench.Companion.robot
import dev.picasso.ops.service.JobOrderBench.Companion.running
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.joborders.EligibilityView
import dev.picasso.ops.service.joborders.HostState
import dev.picasso.ops.service.joborders.InspectAssetDraft
import dev.picasso.ops.service.joborders.InspectionTarget
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.joborders.RobotEligibility
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.robots.CommissioningState
import dev.picasso.ops.service.robots.Connection
import dev.picasso.ops.service.robots.RegistryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 배정 가능 네 칸의 합성(S3a 스펙 §8, T3). 시운전·연결은 운영 서비스, 스킬 적합·도는 실행은 호스트가 판정한다. */
class JobOrderEligibilityTest {

    private val bench = JobOrderBench()
    private val order = JobOrderForm.jobOrder(InspectAssetDraft(listOf(InspectionTarget("T1", "bay-7"))), "JO-DRAFT", ObjectMapper())

    private fun judge(): EligibilityView = bench.eligibility.judge(order)

    private fun EligibilityView.row(id: String): RobotEligibility = robots!!.single { it.robotId == id }

    @Test
    fun `네 칸이 다 통과한 기체만 배정 가능이고 나머지는 칸마다 이유를 낸다`() {
        bench.robots = RegistryCall.Ok(
            listOf(
                robot(HUMANOID),
                robot(QUADRUPED),
                robot("unbound-01"),
                robot("stale-01", lastReportedAt = "2026-10-07T23:58:00Z"),
                robot("busy-01"),
                robot("retired-01", status = "RETIRED"),
            ),
        )
        bench.bindings = RegistryCall.Ok(
            listOf(binding(HUMANOID), binding(QUADRUPED), binding("stale-01"), binding("busy-01"), binding("retired-01")),
        )
        bench.judge = { ids ->
            HostCall.Ok(
                ids.map {
                    when (it) {
                        QUADRUPED -> missing(it, "inspect", "navigate_to")
                        "busy-01" -> running(it, "exec-4")
                        else -> pass(it)
                    }
                },
            )
        }
        val view = judge()
        assertEquals(RegistryState.OK, view.registry)
        assertEquals(HostState.OK, view.host)
        assertEquals(bench.at, view.checkedAt)
        assertEquals(listOf(HUMANOID), view.robots!!.filter { it.eligible }.map { it.robotId })

        val humanoid = view.row(HUMANOID)
        assertEquals(CommissioningState.COMPLETE, humanoid.commissioning)
        assertEquals(Connection.FRESH, humanoid.connection)
        assertEquals(SETTINGS_VERSION, humanoid.settingsVersion)
        assertEquals(pass(HUMANOID), humanoid.host)
        assertEquals(emptyList(), humanoid.reasons)

        assertEquals(listOf("모자란 스킬: inspect, navigate_to"), view.row(QUADRUPED).reasons)
        assertEquals(listOf("inspect", "navigate_to"), view.row(QUADRUPED).host!!.missingSkills)
        assertEquals(CommissioningState.INCOMPLETE, view.row("unbound-01").commissioning)
        assertEquals(listOf("시운전이 끝나지 않았다"), view.row("unbound-01").reasons)
        assertEquals(Connection.STALE, view.row("stale-01").connection)
        assertEquals(listOf("연결이 오래됐다(기준 90초, 현장 설정 버전 3)"), view.row("stale-01").reasons)
        assertEquals("exec-4", view.row("busy-01").host!!.runningExecutionId)
        assertEquals(listOf("도는 실행이 있다: exec-4"), view.row("busy-01").reasons)
        assertEquals(CommissioningState.RETIRED, view.row("retired-01").commissioning)
        assertEquals(listOf("퇴역한 기체다"), view.row("retired-01").reasons)
    }

    @Test
    fun `호스트에 작업 지시 본문과 목록의 기체 id 를 넘긴다`() {
        judge()
        assertEquals(listOf(order to listOf(HUMANOID, QUADRUPED)), bench.judged)
    }

    @Test
    fun `registry 가 답하지 않으면 직전 목록이 신선이어도 시운전·연결은 모름이고 배정 가능이 아니다`() {
        assertTrue(judge().row(HUMANOID).eligible)
        bench.robots = RegistryCall.Silent("응답 없음")
        val view = judge()
        assertEquals(RegistryState.REGISTRY_SILENT, view.registry)
        // 직전 목록의 기체는 그대로 보이고, 호스트 칸은 그 기체 id 로 따로 물어 알 수 있다.
        val humanoid = view.row(HUMANOID)
        assertNull(humanoid.commissioning)
        assertNull(humanoid.connection)
        assertNull(humanoid.settingsVersion)
        assertEquals(pass(HUMANOID), humanoid.host)
        assertEquals(false, humanoid.eligible)
        assertEquals(listOf("registry 가 답하지 않아 시운전·연결을 모른다"), humanoid.reasons)
        assertEquals(listOf(HUMANOID, QUADRUPED), bench.judged.last().second)
    }

    @Test
    fun `호스트가 답하지 않으면 호스트 칸이 모름이고 배정 가능이 아니다`() {
        bench.judge = { HostCall.Silent("응답 없음: ConnectException") }
        val view = judge()
        assertEquals(HostState.HOST_SILENT, view.host)
        val humanoid = view.row(HUMANOID)
        assertEquals(CommissioningState.COMPLETE, humanoid.commissioning)
        assertEquals(Connection.FRESH, humanoid.connection)
        assertNull(humanoid.host)
        assertEquals(false, humanoid.eligible)
        assertEquals(listOf("실행 호스트가 답하지 않아 스킬 적합·도는 실행을 모른다"), humanoid.reasons)
    }

    @Test
    fun `호스트가 어떤 기체를 판정하지 않으면 그 기체의 호스트 칸은 모름이다`() {
        bench.judge = { HostCall.Ok(listOf(pass(HUMANOID))) }
        val quadruped = judge().row(QUADRUPED)
        assertNull(quadruped.host)
        assertEquals(false, quadruped.eligible)
        assertEquals(listOf("실행 호스트가 이 기체를 판정하지 않았다"), quadruped.reasons)
    }

    @Test
    fun `기체 목록을 한 번도 못 읽었으면 기체 행이 모름이고 호스트를 부르지 않는다`() {
        bench.robots = RegistryCall.Silent("응답 없음")
        val view = judge()
        assertNull(view.robots)
        assertNull(view.robotsAsOf)
        assertEquals(HostState.OK, view.host)
        assertEquals(emptyList(), bench.judged)
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderFormTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.joborders.FormRejection
import dev.picasso.ops.service.joborders.InspectAssetDraft
import dev.picasso.ops.service.joborders.InspectionTarget
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.joborders.PrepareSequencedRackDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 폼 초안을 읽고 작업 지시 본문을 만든다(S3a 스펙 §7.5). 단위 id 가 겹치는 초안은 막는다. */
class JobOrderFormTest {

    private val json = ObjectMapper()

    private fun read(form: String) = JobOrderForm.read(json.readTree(form))

    private fun rejected(form: String?): FormRejection =
        assertFailsWith<FormRejection> { JobOrderForm.read(form?.let { runCatching { json.readTree(it) }.getOrNull() }) }

    @Test
    fun `InspectAsset 폼은 대상마다 inspection_target 장비 요구가 되고 요구 근거 등급은 E0 이다`() {
        val draft = read(
            """{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"},{"id":"T2","location":"bay-9"}],
               "slots":["무시된다"]}""",
        )
        assertEquals(InspectAssetDraft(listOf(InspectionTarget("T1", "bay-7"), InspectionTarget("T2", "bay-9"))), draft)
        assertEquals(listOf("T1.travel", "T1", "T2.travel", "T2"), draft.unitIds)
        assertEquals(
            json.readTree(
                """{"jobOrderId":"JO-1","workMasterId":"InspectAsset","version":1,"requiredEvidence":"E0","parameters":{},
                   "materialRequirements":[],
                   "equipmentRequirements":[
                     {"id":"T1","equipmentUse":"inspection_target","properties":{"location":"bay-7"}},
                     {"id":"T2","equipmentUse":"inspection_target","properties":{"location":"bay-9"}}]}""",
            ),
            JobOrderForm.jobOrder(draft, "JO-1", json),
        )
    }

    @Test
    fun `PrepareSequencedRack 폼은 슬롯마다 destination 과 제시 자리 하나의 source 가 되고 자재 수는 슬롯 수이며 요구 근거 등급은 E2 다`() {
        val draft = read(
            """{"workMasterId":"PrepareSequencedRack","slots":["RACK-204.S01","RACK-204.S02"],"material":"ENGINE-COVER-A",
               "presentation":"SEQ-IN-02.BIN-A"}""",
        )
        assertEquals(PrepareSequencedRackDraft(listOf("RACK-204.S01", "RACK-204.S02"), "ENGINE-COVER-A", "SEQ-IN-02.BIN-A"), draft)
        assertEquals(
            json.readTree(
                """{"jobOrderId":"JO-2","workMasterId":"PrepareSequencedRack","version":1,"requiredEvidence":"E2","parameters":{},
                   "materialRequirements":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":2}],
                   "equipmentRequirements":[
                     {"id":"RACK-204.S01","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},
                     {"id":"RACK-204.S02","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},
                     {"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}]}""",
            ),
            JobOrderForm.jobOrder(draft, "JO-2", json),
        )
    }

    @Test
    fun `대상 id 가 겹치면 UNIT_ID_CONFLICT 다`() {
        val e = rejected("""{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"a"},{"id":"T1","location":"b"}]}""")
        assertEquals(JobOrderForm.UNIT_ID_CONFLICT, e.error)
        assertEquals("단위 id 가 겹친다: T1, T1.travel", e.message)
    }

    @Test
    fun `대상 id 가 다른 대상의 이동 단위 id 와 같으면 UNIT_ID_CONFLICT 다`() {
        val e = rejected(
            """{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"a"},{"id":"T1.travel","location":"b"}]}""",
        )
        assertEquals(JobOrderForm.UNIT_ID_CONFLICT, e.error)
        assertEquals("단위 id 가 겹친다: T1.travel", e.message)
        // 이동 단위 id 꼴이어도 짝이 없으면 겹치지 않는다(단위는 T1.travel.travel 과 T1.travel).
        read("""{"workMasterId":"InspectAsset","targets":[{"id":"T1.travel","location":"b"}]}""")
    }

    @Test
    fun `슬롯이 겹치면 UNIT_ID_CONFLICT 다`() {
        val e = rejected(
            """{"workMasterId":"PrepareSequencedRack","slots":["S1","S2","S1"],"material":"M","presentation":"P"}""",
        )
        assertEquals(JobOrderForm.UNIT_ID_CONFLICT, e.error)
        assertEquals("단위 id 가 겹친다: S1", e.message)
    }

    @Test
    fun `대상 id 가 inspect target 파라미터 한도 64자를 넘으면 JOB_ORDER_BAD_REQUEST 다`() {
        val limit = "T".repeat(JobOrderForm.MAX_TARGET_ID_LENGTH)
        assertEquals(64, limit.length)
        read("""{"workMasterId":"InspectAsset","targets":[{"id":"$limit","location":"a"}]}""")
        val e = rejected("""{"workMasterId":"InspectAsset","targets":[{"id":"${limit}X","location":"a"}]}""")
        assertEquals(JobOrderForm.BAD_REQUEST, e.error)
        assertEquals("대상 id 가 64자를 넘는다: 65자", e.message)
    }

    @Test
    fun `받지 않는 임무는 UNKNOWN_WORK_MASTER 이고 깨진 폼은 JOB_ORDER_BAD_REQUEST 다`() {
        assertEquals(JobOrderForm.UNKNOWN_WORK_MASTER, rejected("""{"workMasterId":"DeliverContainer"}""").error)
        listOf(
            null,
            "작업 지시",
            "[]",
            """{"targets":[{"id":"T1","location":"a"}]}""",
            """{"workMasterId":"InspectAsset"}""",
            """{"workMasterId":"InspectAsset","targets":[]}""",
            """{"workMasterId":"InspectAsset","targets":[{"id":"T1"}]}""",
            """{"workMasterId":"InspectAsset","targets":[{"id":" ","location":"a"}]}""",
            """{"workMasterId":"InspectAsset","targets":["T1"]}""",
            """{"workMasterId":"PrepareSequencedRack","slots":[],"material":"M","presentation":"P"}""",
            """{"workMasterId":"PrepareSequencedRack","slots":["S1",3],"material":"M","presentation":"P"}""",
            """{"workMasterId":"PrepareSequencedRack","slots":["S1"],"presentation":"P"}""",
            """{"workMasterId":"PrepareSequencedRack","slots":["S1"],"material":"M"}""",
        ).forEach { form -> assertEquals(JobOrderForm.BAD_REQUEST, rejected(form).error, form) }
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderOperationsTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.JobOrderBench.Companion.HUMANOID
import dev.picasso.ops.service.JobOrderBench.Companion.QUADRUPED
import dev.picasso.ops.service.JobOrderBench.Companion.missing
import dev.picasso.ops.service.JobOrderBench.Companion.pass
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.host.HostSubmitResult
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.host.HostWrites
import dev.picasso.ops.service.joborders.InspectAssetDraft
import dev.picasso.ops.service.joborders.InspectionTarget
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.joborders.JobOrderOutcome
import dev.picasso.ops.service.joborders.JobOrderSubmission
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 작업 지시 제출과 조작 기록(S3a 스펙 §8). 호스트 결과를 조작 기록 결과로 옮기고, 응답이 없으면 실행 목록을 다시 읽어 반영 여부를
 * 붙인다. 호스트와 registry 는 대역이다.
 */
class JobOrderOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = JobOrderBench()
    private val operations =
        JobOrderOperations(bench.eligibility, bench.host, bench.host, log, bench.clock, requeryDelay = Duration.ZERO)
    private val kim = Actor(Mode.OPERATOR, "kim")
    private val draft = InspectAssetDraft(listOf(InspectionTarget("T1", "bay-7")))
    private val json = ObjectMapper()

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    private fun submitted(): JobOrderOutcome = assertIs<JobOrderSubmission.Submitted>(operations.submit(kim, draft)).outcome

    private fun submittedId(): String = bench.submitted.last().first["jobOrderId"].asText()

    @Test
    fun `호스트 결과 넷을 조작 기록 결과로 옮긴다`() {
        val expected = mapOf(
            HostSubmitResult.ACCEPTED to OperationResult.SUCCEEDED,
            HostSubmitResult.IDEMPOTENT to OperationResult.SUCCEEDED,
            HostSubmitResult.REJECTED to OperationResult.REJECTED,
            HostSubmitResult.UNASSIGNED to OperationResult.REJECTED,
        )
        expected.forEach { (hostResult, logged) ->
            bench.answer = HostWrite.Answered(200, """{"result":"$hostResult","refusals":[],"excluded":[]}""")
            val outcome = submitted()
            assertEquals(logged, outcome.result, "$hostResult")
            assertNull(outcome.confirmation)
            assertEquals(hostResult, outcome.outcome!!.result)
            val row = log.list().first()
            assertEquals(outcome.requestId, row.requestId)
            assertEquals(logged, row.result, "$hostResult")
        }
        assertEquals(4, log.list().size)
    }

    @Test
    fun `제출은 배정 가능한 기체만 후보로 넘기고 작업 지시 id 를 대상으로 기록한다`() {
        bench.judge = { ids -> HostCall.Ok(ids.map { if (it == QUADRUPED) missing(it, "inspect") else pass(it) }) }
        bench.answer = HostWrite.Answered(
            200,
            """{"result":"ACCEPTED","executionId":"exec-1","robotId":"$HUMANOID","rejectionReason":null,"refusals":[],"excluded":[]}""",
        )
        val outcome = submitted()
        val (order, candidates) = bench.submitted.single()
        assertEquals(listOf(HUMANOID), candidates)
        assertEquals("exec-1", outcome.outcome!!.executionId)
        assertEquals(HUMANOID, outcome.outcome!!.robotId)
        // 판정과 제출이 같은 본문(새 작업 지시 id)을 쓴다.
        assertEquals(order, bench.judged.single().first)
        val row = log.list().single()
        assertEquals(order["jobOrderId"].asText(), row.target)
        assertEquals(Mode.OPERATOR, row.mode)
        assertEquals("kim", row.user)
        assertNull(row.reason)
        val request = json.readTree(row.request)
        assertEquals(JobOrderOperations.OP, request["op"].asText())
        assertEquals(order, request["jobOrder"])
        assertEquals(json.readTree("""["$HUMANOID"]"""), request["candidates"])
        val response = json.readTree(row.targetResponse)
        assertEquals(200, response["status"].asInt())
        assertEquals("exec-1", response["body"]["executionId"].asText())
    }

    @Test
    fun `작업 지시 id 는 JO 접두와 사이트 날짜와 요청 id 앞 8자리이고 응답에도 실린다`() {
        val outcome = submitted()
        assertEquals("JO-20261008-${outcome.requestId.toString().take(8)}", submittedId())
        assertEquals(submittedId(), outcome.jobOrderId)
        assertEquals("InspectAsset", bench.submitted.single().first["workMasterId"].asText())
    }

    @Test
    fun `배정 가능한 기체가 없으면 호스트를 부르지 않고 기록하지 않는다`() {
        bench.judge = { ids -> HostCall.Ok(ids.map { missing(it, "inspect") }) }
        val submission = assertIs<JobOrderSubmission.NoEligibleRobot>(operations.submit(kim, draft))
        assertEquals("$HUMANOID: 모자란 스킬: inspect / $QUADRUPED: 모자란 스킬: inspect", submission.detail)
        assertEquals(emptyList(), bench.submitted)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `호스트의 4xx 는 거부로 남기고 사유를 결과에 옮긴다`() {
        bench.answer = HostWrite.Answered(400, """{"error":"UNKNOWN_WORK_MASTER","detail":"받지 않는 WorkMaster 다"}""")
        val outcome = submitted()
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertNull(outcome.confirmation)
        assertEquals(HostSubmitResult.REJECTED, outcome.outcome!!.result)
        assertEquals("실행 호스트가 거부했다(HTTP 400): UNKNOWN_WORK_MASTER: 받지 않는 WorkMaster 다", outcome.outcome!!.rejectionReason)
        assertEquals(listOf(OperationResult.REJECTED), log.list().map { it.result })
    }

    @Test
    fun `호스트가 닿지 않으면 응답 없음을 남기고 다시 읽어 그 작업 지시의 실행이 있으면 반영됨을 붙인다`() {
        bench.answer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        // 재조회 대역은 제출이 만든 id 를 알아야 하므로 실행 목록을 제출 뒤에 고른다.
        val host = bench.host
        val reads = object : HostReads by host {
            override fun executions() = bench.executionsWith("JO-다른-것", submittedId())
        }
        val outcome = assertIs<JobOrderSubmission.Submitted>(
            JobOrderOperations(bench.eligibility, host, reads, log, bench.clock, Duration.ZERO).submit(kim, draft),
        ).outcome
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        assertNull(outcome.outcome)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertEquals(setOf(outcome.requestId), rows.map { it.requestId }.toSet())
        assertEquals(setOf(submittedId()), rows.map { it.target }.toSet())
        assertEquals("응답 없음: HttpTimeoutException", json.readTree(rows.last().targetResponse)["cause"].asText())
        val observed = json.readTree(rows.first().targetResponse)["observed"]
        assertEquals("exec-2", observed["executionId"].asText())
        assertEquals("mw-1", observed["instanceId"].asText())
        assertEquals(HUMANOID, observed["robotId"].asText())
    }

    @Test
    fun `5xx 는 응답 없음과 같이 다루고 실행이 없으면 반영 안 됨을 붙인다`() {
        bench.answer = HostWrite.Answered(503, "")
        bench.executions = bench.executionsWith("JO-다른-것")
        val outcome = submitted()
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        assertNull(outcome.outcome)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_NOT_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertTrue(json.readTree(rows.first().targetResponse)["observed"].isNull)
        assertEquals(503, json.readTree(rows.last().targetResponse)["status"].asInt())
    }

    @Test
    fun `200 인데 본문을 못 읽으면 응답 없음으로 남기고 다시 읽는다`() {
        bench.answer = HostWrite.Answered(200, """{"result":"ASSIGNED"}""")
        bench.executions = bench.executionsWith()
        val outcome = submitted()
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        assertNull(outcome.outcome)
    }

    @Test
    fun `재조회도 실패하면 확인 행을 붙이지 않고 모름으로 둔다`() {
        bench.answer = HostWrite.NoResponse("응답 없음: ConnectException")
        bench.executions = HostCall.Silent("응답 없음: ConnectException")
        val outcome = submitted()
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertNull(outcome.confirmation)
        assertEquals(listOf(OperationResult.NO_RESPONSE), log.list().map { it.result })
    }

    @Test
    fun `재조회는 기본 값으로 쓰면 제출 뒤 1초 가까이 기다린 다음에 읽는다`() {
        var wrote = 0L
        var read = 0L
        val host = bench.host
        val writes = HostWrites { order, candidates ->
            host.submit(order, candidates)
            HostWrite.NoResponse("응답 없음").also { wrote = System.nanoTime() }
        }
        val reads = object : HostReads by host {
            override fun executions() = bench.executionsWith().also { read = System.nanoTime() }
        }
        JobOrderOperations(bench.eligibility, writes, reads, log, bench.clock).submit(kim, draft)
        assertTrue(read > wrote && Duration.ofNanos(read - wrote) >= Duration.ofMillis(900), "제출과 재조회 사이 ${(read - wrote) / 1_000_000} ms")
    }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task4.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task4.patch"
```

```diff
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
index 6984dfa..0578c46 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
@@ -3,6 +3,9 @@ package dev.picasso.ops.service
 import dev.picasso.ops.service.operations.ProfileOperations
 import dev.picasso.ops.service.profiles.ProfileListService
 import dev.picasso.ops.service.adapters.AdapterListService
+import dev.picasso.ops.service.host.HostClient
+import dev.picasso.ops.service.joborders.JobOrderEligibility
+import dev.picasso.ops.service.joborders.JobOrderOperations
 import dev.picasso.ops.service.log.OperationLog
 import dev.picasso.ops.service.operations.AdapterOperations
 import dev.picasso.ops.service.operations.RobotOperations
@@ -109,6 +112,23 @@ open class OpsApplication {
     ): SiteSettingsOperations =
         SiteSettingsOperations(settings, log, TransactionTemplate(DataSourceTransactionManager(dataSource)), clock)
 
+    /** 실행 호스트 클라이언트(S3a 스펙 §8). 주소 형식은 [HostClient.checkBaseUrl] 이 기동에서 본다. */
+    @Bean
+    open fun hostClient(@Value("\${ops.host.url}") url: String): HostClient = HostClient(url)
+
+    /** 시운전·연결은 기체 목록의 판정을 그대로 쓴다(T3). 기체 목록 빈을 같이 써서 그 직전 값도 같다. */
+    @Bean
+    open fun jobOrderEligibility(robots: RobotListService, host: HostClient, clock: Clock): JobOrderEligibility =
+        JobOrderEligibility(robots, host, clock)
+
+    @Bean
+    open fun jobOrderOperations(
+        eligibility: JobOrderEligibility,
+        host: HostClient,
+        log: OperationLog,
+        clock: Clock,
+    ): JobOrderOperations = JobOrderOperations(eligibility, host, host, log, clock)
+
     /** [migrated] 는 쓰지 않는다. 받는 것만으로 ops 마이그레이션 뒤에 이 빈이 만들어진다. */
     @Bean
     open fun operationLog(
diff --git a/ops-service/src/main/resources/ops-service.properties b/ops-service/src/main/resources/ops-service.properties
index 91b7ed9..4b674f7 100644
--- a/ops-service/src/main/resources/ops-service.properties
+++ b/ops-service/src/main/resources/ops-service.properties
@@ -12,3 +12,5 @@ spring.datasource.password=${PICASSO_DB_PASSWORD}
 ops.site-id=${SITE_ID}
 ops.registry.url=http://127.0.0.1:${REGISTRY_PORT}
 ops.registry.operator-token=${PICASSO_OPERATOR_TOKEN}
+# 실행 호스트(S3a 스펙 §8). 운영 서비스는 picasso 를 쓰지 않고 호스트 REST 만 부른다. 루프백이고 토큰이 없다.
+ops.host.url=http://127.0.0.1:${HOST_PORT}
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && ./gradlew :ops-service:test -q
```
Expected: ops-service 160, 실패 0. 백그라운드로 돌린다.

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git add ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/Eligibility.kt ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/JobOrderForm.kt ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/JobOrderOperations.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/JobOrderController.kt ops-service/src/test/kotlin/dev/picasso/ops/service/HostClientTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderBench.kt ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderControllerTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderEligibilityTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderFormTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderOperationsTest.kt ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt ops-service/src/main/resources/ops-service.properties && git commit -F - <<'EOF'
feat(ops-service): 작업 지시 배정 가능 판정·제출과 실행·셀 전달 REST 추가

- 호스트 클라이언트, 폼에서 작업 지시 본문, 배정 가능 네 칸, 운영자 모드 제출과 조작 기록·재조회
- 실행 목록·셀 전달, 제출 응답의 작업 지시 id, 대상 id 64자 한도

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s3a-cmp.sh" ops-service/src/main/resources/db/ops/V3__target_response.sql ops-service/src/main/kotlin/dev/picasso/ops/service/log/OperationLog.kt ops-service/src/main/kotlin/dev/picasso/ops/service/operations/OperationRunner.kt ops-service/src/test/kotlin/dev/picasso/ops/service/AdapterOperationsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/OperationLogTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/ProfileOperationsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RobotOperationsTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/SiteSettingsOperationsTest.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/Eligibility.kt ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/JobOrderForm.kt ops-service/src/main/kotlin/dev/picasso/ops/service/joborders/JobOrderOperations.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/JobOrderController.kt ops-service/src/test/kotlin/dev/picasso/ops/service/HostClientTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderBench.kt ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderControllerTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderEligibilityTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderFormTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/JobOrderOperationsTest.kt ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt ops-service/src/main/resources/ops-service.properties
```
Expected: 22개 모두 `같음`.

### Task 5: 화면 운영 영역

**Files:**
- Create: `ui/src/components/CellBand.tsx`, `ui/src/components/EligibilityTable.tsx`, `ui/src/components/ExecutionList.tsx`, `ui/src/components/JobOrderFormView.tsx`, `ui/src/components/JobOrderNotice.tsx`, `ui/src/components/OperationsArea.test.tsx`, `ui/src/components/OperationsArea.tsx`, `ui/src/jobOrderDraft.ts`, `ui/src/poll.ts`
- Modify: `ui/src/App.test.tsx`, `ui/src/App.tsx`, `ui/src/api.ts`, `ui/src/areas.ts`, `ui/src/labels.ts`, `ui/src/testing/fakeOps.ts`

- [ ] **Step 1: 새 파일 9개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ui/src/components/CellBand.tsx" ui/src/components/CellBand.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ui/src/components/EligibilityTable.tsx" ui/src/components/EligibilityTable.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ui/src/components/ExecutionList.tsx" ui/src/components/ExecutionList.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ui/src/components/JobOrderFormView.tsx" ui/src/components/JobOrderFormView.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ui/src/components/JobOrderNotice.tsx" ui/src/components/JobOrderNotice.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ui/src/components/OperationsArea.test.tsx" ui/src/components/OperationsArea.test.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ui/src/components/OperationsArea.tsx" ui/src/components/OperationsArea.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ui/src && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ui/src/jobOrderDraft.ts" ui/src/jobOrderDraft.ts
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p ui/src && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/ui/src/poll.ts" ui/src/poll.ts
```

`ui/src/components/CellBand.tsx`:

```tsx
import type { CellPlace, CellView } from '../api'
import type { HostRead } from './ExecutionList'

interface Props {
  read: HostRead<CellView>
}

/**
 * 셀 대역 표시(S3a 스펙 §6.3·§9.2). 슬롯은 기체가 보고한 배치에서 채우므로 독립 설비 확인이 아니다. 그래서 «셀» 이 아니라
 * «셀 대역» 이라고 적는다. 실행 호스트가 셀 대역을 못 읽으면(`cell` null) 비어 있는 셀로 접지 않고 모름이다.
 */
export function CellBand({ read }: Props) {
  const { value, error } = read
  return (
    <>
      <p className="offscreen">셀 대역: 슬롯은 기체가 보고한 배치로 채웁니다. 독립 설비 확인이 아닙니다</p>
      {value === null ? (
        <p>모름: 셀 대역을 아직 읽지 못했습니다{error !== null && ` (${error})`}</p>
      ) : (
        <>
          {error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {error}</p>}
          {value.cell === null ? (
            <p>모름: 실행 호스트가 셀 대역을 읽지 못했습니다</p>
          ) : (
            <table aria-label="셀 대역 자리">
              <thead>
                <tr>
                  <th>자리</th>
                  <th>종류</th>
                  <th>점유</th>
                  <th>자재</th>
                  <th>관측 시각</th>
                </tr>
              </thead>
              <tbody>
                {value.cell.presentations.map((place) => (
                  <Place key={place.id} place={place} kind="제시 자리" />
                ))}
                {value.cell.slots.map((place) => (
                  <Place key={place.id} place={place} kind="슬롯" />
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </>
  )
}

function Place({ place, kind }: { place: CellPlace; kind: string }) {
  return (
    <tr>
      <td>{place.id}</td>
      <td>{kind}</td>
      <td>{place.occupied ? '점유' : '비어 있음'}</td>
      <td>{place.material ?? '-'}</td>
      <td>{place.observedAt ?? '-'}</td>
    </tr>
  )
}
```

`ui/src/components/EligibilityTable.tsx`:

```tsx
import type { EligibilityView, PreRejection, RobotEligibility } from '../api'
import { COMMISSIONING_LABEL, CONNECTION_LABEL, SKILL_FIT_LABEL, kindLabel } from '../labels'

/** 배정 가능을 읽은 결과. 못 읽으면 직전 판정을 지우지 않고 [error] 로 직전 값임을 표시한다. */
export interface EligibilityRead {
  view: EligibilityView | null
  /** 운영 서비스가 폼을 막았다(400). 이때 판정이 없다. */
  refusal: PreRejection | null
  error: string | null
  /** 이 판정을 낸 폼. 지금 폼과 다르면 다시 판정하는 중이다. */
  formKey: string | null
}

interface Props {
  read: EligibilityRead
  /** 지금 폼. null 이면 덜 채운 폼이라 판정을 묻지 않는다. */
  formKey: string | null
}

/**
 * 기체별 배정 가능 표(S3a 스펙 §9.1). 시운전·연결은 운영 서비스가, 도는 실행·스킬 적합은 실행 호스트가 판정한다(T3).
 * 못 물어본 칸은 «모름» 이고 «없음» 이나 «신선» 으로 접지 않는다. 모름이 하나라도 있으면 배정 불가다.
 */
export function EligibilityTable({ read, formKey }: Props) {
  if (formKey === null) return <p>폼을 채우면 기체별 배정 가능을 봅니다</p>
  if (read.refusal !== null && read.formKey === formKey) {
    return (
      <p role="alert">
        {kindLabel(read.refusal.error)}: {read.refusal.detail}
      </p>
    )
  }
  const { view } = read
  if (view === null) {
    return <p>{read.error !== null ? `모름: 배정 가능을 읽지 못했습니다 (${read.error})` : '판정 중'}</p>
  }
  return (
    <>
      {read.error !== null && <p className="stale">직전 값입니다 ({read.error})</p>}
      {read.formKey !== formKey && <p className="stale">폼이 바뀌어 다시 판정하는 중입니다</p>}
      <p>판정 시각 {view.checkedAt}</p>
      {view.robots === null ? (
        <p>모름: 기체 목록을 아직 읽지 못했습니다</p>
      ) : view.robots.length === 0 ? (
        <p>이 사이트에 기체가 없습니다</p>
      ) : (
        <table aria-label="기체별 배정 가능">
          <thead>
            <tr>
              <th>기체</th>
              <th>시운전</th>
              <th>연결</th>
              <th>도는 실행</th>
              <th>스킬 적합</th>
              <th>배정 가능</th>
              <th>이유</th>
            </tr>
          </thead>
          <tbody>
            {view.robots.map((row) => (
              <tr key={row.robotId}>
                <td>{row.robotId}</td>
                <td>{row.commissioning === null ? '모름' : COMMISSIONING_LABEL[row.commissioning]}</td>
                <td>{connection(row)}</td>
                <td>{row.host === null ? '모름' : (row.host.runningExecutionId ?? '없음')}</td>
                <td>{skillFit(row)}</td>
                <td>{row.eligible ? '가능' : '불가'}</td>
                <td>{row.reasons.join('; ')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}

/** 연결과 그 판정의 근거 현장 설정 버전. */
function connection(row: RobotEligibility): string {
  if (row.connection === null) return '모름'
  const label = CONNECTION_LABEL[row.connection]
  return row.settingsVersion !== null ? `${label}(현장 설정 버전 ${row.settingsVersion})` : label
}

function skillFit(row: RobotEligibility): string {
  if (row.host === null) return '모름'
  if (row.host.skillFit === 'MISSING') return `${SKILL_FIT_LABEL.MISSING}: ${row.host.missingSkills.join(', ')}`
  return SKILL_FIT_LABEL[row.host.skillFit]
}
```

`ui/src/components/ExecutionList.tsx`:

```tsx
import type { ExecutionsView, JobResponse } from '../api'

/** 실행 호스트를 거친 읽기 하나. 못 읽으면 직전 값을 지우지 않고 [error] 로 불통을 표시한다. */
export interface HostRead<T> {
  value: T | null
  error: string | null
}

interface Props {
  read: HostRead<ExecutionsView>
  /** 방금 낸 작업 지시 id. 그 실행 행을 눈에 띄게 한다. */
  highlight: string | null
}

/**
 * 실행 목록(S3a 스펙 §9.2). 머리에 실행 호스트 인스턴스를 보인다. 호스트를 재기동하면 실행이 사라지고 `exec-N` 을 1부터
 * 다시 세므로, 인스턴스가 바뀐 것으로 구별한다. 임무 버전이 null 이면 코드 정의 임무다. 최신 실행부터 보인다.
 */
export function ExecutionList({ read, highlight }: Props) {
  const { value, error } = read
  if (value === null) {
    return <p>모름: 실행 목록을 아직 읽지 못했습니다{error !== null && ` (${error})`}</p>
  }
  return (
    <>
      {error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {error}</p>}
      <dl>
        <dt>실행 호스트 인스턴스</dt>
        <dd>{value.instanceId}</dd>
        <dt>마지막 pump</dt>
        <dd>{value.pumpedAt ?? '아직 없음'}</dd>
      </dl>
      {value.executions.length === 0 ? (
        <p>실행이 없습니다</p>
      ) : (
        <table aria-label="실행 목록">
          <thead>
            <tr>
              <th>실행 id</th>
              <th>작업 지시 id</th>
              <th>임무</th>
              <th>임무 버전</th>
              <th>기체</th>
              <th>물리 상태</th>
              <th>단위</th>
              <th>작업 응답</th>
            </tr>
          </thead>
          <tbody>
            {[...value.executions].reverse().map((execution) => (
              <tr
                key={execution.executionId}
                className={execution.jobOrderId === highlight ? 'selected' : undefined}
              >
                <td>{execution.executionId}</td>
                <td>{execution.jobOrderId}</td>
                <td>{execution.workMasterId}</td>
                <td>{execution.missionVersion === null ? '코드 정의' : `버전 ${execution.missionVersion}`}</td>
                <td>{execution.robotId}</td>
                <td>{execution.physicalState}</td>
                <td>
                  <ul>
                    {execution.units.map((unit) => (
                      <li key={unit.unitId}>
                        {unit.unitId} {unit.skillType}: {unit.state}, 근거 {unit.reached}
                      </li>
                    ))}
                  </ul>
                </td>
                <td>{execution.jobResponse === null ? '없음' : <JobResponseCell response={execution.jobResponse} />}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}

/** 그 실행의 마지막 작업 응답. 상위 시스템이 없어 실행 호스트의 아웃박스에 남은 것이다(S3a 스펙 §7.7). */
function JobResponseCell({ response }: { response: JobResponse }) {
  const incomplete = Object.entries(response.incompleteUnits)
  return (
    <ul>
      <li>
        {response.jobResponseId}: {response.physicalState}, 근거 {response.reachedEvidence}(요구{' '}
        {response.requiredEvidence})
      </li>
      {response.unverifiedUnits.length > 0 && <li>미확인 단위 {response.unverifiedUnits.join(', ')}</li>}
      {incomplete.length > 0 && (
        <li>미완 단위 {incomplete.map(([unitId, reason]) => `${unitId}(${reason})`).join(', ')}</li>
      )}
      {response.inDoubtUnits.length > 0 && <li>불확실 단위 {response.inDoubtUnits.join(', ')}</li>}
      {response.operatorRequired && <li>운영자 개입 필요</li>}
    </ul>
  )
}
```

`ui/src/components/JobOrderFormView.tsx`:

```tsx
import type { CellView, Mode, WorkMasterId } from '../api'
import { materialsOf, presentationFor, toggleSlot } from '../jobOrderDraft'
import type { JobOrderDraft } from '../jobOrderDraft'

interface Props {
  draft: JobOrderDraft
  /** 셀 대역. null 이면 모름이다(아직 못 읽었거나 실행 호스트가 셀 대역을 못 읽음). */
  cell: CellView['cell']
  mode: Mode
  busy: boolean
  /** 제출을 눌렀는데 보내지 않은 이유. */
  problem: string | null
  onChange: (draft: JobOrderDraft) => void
  onSubmit: () => void
}

const WORK_MASTERS: readonly WorkMasterId[] = ['InspectAsset', 'PrepareSequencedRack']

/**
 * 작업 지시 폼(S3a 스펙 §9.1). 임무마다 입력이 다르다. 입력은 모드와 관계없이 보여 엔지니어도 배정 가능을 볼 수 있고,
 * 제출만 운영자 모드에서 한다(결정 3). 장소 이름과 슬롯이 기체가 아는 명칭인지는 화면이 검사하지 않는다(스펙 §12).
 */
export function JobOrderFormView({ draft, cell, mode, busy, problem, onChange, onSubmit }: Props) {
  return (
    <form
      aria-label="작업 지시 폼"
      onSubmit={(event) => {
        event.preventDefault()
        onSubmit()
      }}
    >
      <label>
        임무
        <select
          value={draft.workMasterId}
          onChange={(event) => onChange({ ...draft, workMasterId: event.target.value as WorkMasterId })}
        >
          {WORK_MASTERS.map((id) => (
            <option key={id} value={id}>
              {id}
            </option>
          ))}
        </select>
      </label>
      {draft.workMasterId === 'InspectAsset' ? (
        <Targets draft={draft} onChange={onChange} />
      ) : (
        <RackChoice draft={draft} cell={cell} onChange={onChange} />
      )}
      {mode === 'operator' ? (
        <button type="submit" disabled={busy}>
          작업 지시 내기
        </button>
      ) : (
        <p>작업 지시는 운영자 모드에서 냅니다</p>
      )}
      {problem !== null && <p role="alert">{problem}</p>}
    </form>
  )
}

interface PartProps {
  draft: JobOrderDraft
  onChange: (draft: JobOrderDraft) => void
}

/** InspectAsset 의 점검 대상 목록. 대상마다 이동 단위와 점검 단위가 생긴다. */
function Targets({ draft, onChange }: PartProps) {
  const set = (index: number, field: 'id' | 'location', value: string) =>
    onChange({
      ...draft,
      targets: draft.targets.map((target, i) => (i === index ? { ...target, [field]: value } : target)),
    })
  return (
    <fieldset>
      <legend>점검 대상</legend>
      {draft.targets.map((target, index) => (
        <div key={index}>
          <label>
            대상 {index + 1} id
            <input value={target.id} onChange={(event) => set(index, 'id', event.target.value)} />
          </label>
          <label>
            대상 {index + 1} 장소
            <input value={target.location} onChange={(event) => set(index, 'location', event.target.value)} />
          </label>
          <button
            type="button"
            onClick={() => onChange({ ...draft, targets: draft.targets.filter((_, i) => i !== index) })}
          >
            대상 {index + 1} 빼기
          </button>
        </div>
      ))}
      <button
        type="button"
        onClick={() => onChange({ ...draft, targets: [...draft.targets, { id: '', location: '' }] })}
      >
        대상 더하기
      </button>
      <p className="offscreen">장소 이름은 기체가 아는 명칭이어야 합니다. 화면은 검사하지 않습니다</p>
    </fieldset>
  )
}

/** PrepareSequencedRack 의 슬롯과 자재. 둘 다 셀 대역에서 고른다. 제시 자리는 자재에서 정해진다. */
function RackChoice({ draft, cell, onChange }: PartProps & { cell: CellView['cell'] }) {
  if (cell === null) return <p>모름: 셀 대역을 읽지 못해 슬롯과 자재를 고를 수 없습니다</p>
  const presentation = draft.material === '' ? null : presentationFor(cell, draft.material)
  return (
    <>
      <fieldset>
        <legend>슬롯</legend>
        {cell.slots.map((slot) => (
          <label key={slot.id}>
            <input
              type="checkbox"
              checked={draft.slots.includes(slot.id)}
              onChange={() => onChange({ ...draft, slots: toggleSlot(cell, draft.slots, slot.id) })}
            />
            {slot.id}
          </label>
        ))}
      </fieldset>
      <label>
        자재
        <select value={draft.material} onChange={(event) => onChange({ ...draft, material: event.target.value })}>
          <option value="">고르십시오</option>
          {materialsOf(cell).map((material) => (
            <option key={material} value={material}>
              {material}
            </option>
          ))}
        </select>
      </label>
      <p>제시 자리 {presentation ?? '-'}</p>
    </>
  )
}
```

`ui/src/components/JobOrderNotice.tsx`:

```tsx
import type { Delivered, JobOrderOutcome } from '../api'
import { SUBMIT_RESULT_LABEL, kindLabel } from '../labels'

interface Props {
  /** 어느 임무의 작업 지시인지. 예: `InspectAsset 작업 지시` */
  what: string
  sent: Delivered<JobOrderOutcome>
}

/**
 * 작업 지시 제출의 결과(S3a 스펙 §8·§9.1). 기존 조작 결과([OutcomeNotice])와 모양이 달라 따로 읽는다. 실행 호스트의 판단은
 * 200 본문의 `outcome` 에 있으므로 상태 코드로 결과를 가르지 않는다. 운영 서비스가 먼저 막은 것(400·403)은 «막힘», 그 밖의
 * 비정상은 요청이 호스트까지 갔는지 모르므로 «결과 모름» 이다. 응답 없음은 재조회로 확인되기 전에는 성공으로도 실패로도
 * 보이지 않는다.
 */
export function JobOrderNotice({ what, sent }: Props) {
  if (sent.kind === 'refused') {
    return (
      <div role="status" aria-label="제출 결과">
        <p>
          {what}: 막힘({kindLabel(sent.refusal.error)})
        </p>
        <p>{sent.refusal.detail}</p>
      </div>
    )
  }
  if (sent.kind === 'unknown') {
    return (
      <div role="status" aria-label="제출 결과">
        <p>
          {what}: 결과 모름({sent.cause}). 실행 목록을 다시 읽어 확인하십시오
        </p>
      </div>
    )
  }
  const { jobOrderId, confirmation, outcome } = sent.outcome
  if (outcome === null) {
    return (
      <div role="status" aria-label="제출 결과">
        <p>
          {what}: {noResponse(confirmation)}
        </p>
        <dl>
          <dt>작업 지시 id</dt>
          <dd>{jobOrderId}</dd>
        </dl>
      </div>
    )
  }
  return (
    <div role="status" aria-label="제출 결과">
      <p>
        {what}: {SUBMIT_RESULT_LABEL[outcome.result]}
      </p>
      <dl>
        <dt>작업 지시 id</dt>
        <dd>{jobOrderId}</dd>
        {outcome.executionId !== null && (
          <>
            <dt>실행 id</dt>
            <dd>{outcome.executionId}</dd>
          </>
        )}
        {outcome.robotId !== null && (
          <>
            <dt>배정된 기체</dt>
            <dd>{outcome.robotId}</dd>
          </>
        )}
        {outcome.rejectionReason !== null && (
          <>
            <dt>거부 사유</dt>
            <dd>{outcome.rejectionReason}</dd>
          </>
        )}
      </dl>
      {outcome.refusals.length > 0 && (
        <>
          <p>기체별 미배정 사유</p>
          <ul aria-label="기체별 미배정 사유">
            {outcome.refusals.map((refusal) => (
              <li key={refusal.robotId}>
                {refusal.robotId}: {refusal.reason}
              </li>
            ))}
          </ul>
        </>
      )}
      {outcome.excluded.length > 0 && (
        <>
          <p>실행 호스트가 후보에서 뺀 기체</p>
          <ul aria-label="실행 호스트가 후보에서 뺀 기체">
            {outcome.excluded.map((row) => (
              <li key={row.robotId}>
                {row.robotId}: {row.reasons.join('; ')}
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

/** 응답 없음 뒤 재조회의 결과. 확인 결과가 없으면 그 재조회도 실패한 것이다. */
function noResponse(confirmation: JobOrderOutcome['confirmation']): string {
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return '응답은 없었으나 다시 읽어 보니 실행이 있음. 실행 목록에서 보십시오'
    case 'CONFIRMED_NOT_APPLIED':
      return '응답 없음. 다시 읽어 보니 실행이 없음. 다시 하려면 새로 내십시오'
    default:
      return '반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 실행 목록에서 확인하십시오'
  }
}
```

`ui/src/components/OperationsArea.test.tsx`:

```tsx
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Execution, HostEligibility, JobOrderOutcome, RobotEligibility, Session } from '../api'
import { ELIGIBILITY_DEBOUNCE_MS, POLL_MS } from '../poll'
import { eligibilityView, executionsView, installFakeOps } from '../testing/fakeOps'
import type { FakeOps } from '../testing/fakeOps'
import { OperationsArea } from './OperationsArea'

const engineer: Session = { mode: 'engineer', user: 'local' }
const operator: Session = { mode: 'operator', user: 'kim' }

const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }

/** 실행 호스트의 판정 행(S3a JSON 계약 §2.2). 기본은 통과다. */
function hostRow(robotId: string, partial: Partial<HostEligibility> = {}): HostEligibility {
  return { robotId, skillFit: 'FIT', missingSkills: [], runningExecutionId: null, passed: true, reasons: [], ...partial }
}

/** 운영 서비스의 기체 판정 행(S3a JSON 계약 §9.2). 기본은 배정 가능이다. */
function robotRow(robotId: string, partial: Partial<RobotEligibility> = {}): RobotEligibility {
  return {
    robotId,
    commissioning: 'COMPLETE',
    connection: 'FRESH',
    settingsVersion: 1,
    host: hostRow(robotId),
    eligible: true,
    reasons: [],
    ...partial,
  }
}

/** 실행 호스트 `GET /host/executions` 의 실행 하나(S3a JSON 계약 §5). 기본은 끝난 InspectAsset 코드 정의 실행이다. */
function execution(partial: Partial<Execution> = {}): Execution {
  return {
    executionId: 'exec-1',
    jobOrderId: 'JO-20261008-aaaaaaaa',
    workMasterId: 'InspectAsset',
    missionVersion: null,
    robotId: 'humanoid-01',
    physicalState: 'PHYSICALLY_DONE',
    units: [
      { unitId: 'T1.travel', skillType: 'navigate_to', state: 'DONE', reached: 'E0' },
      { unitId: 'T1', skillType: 'inspect', state: 'DONE', reached: 'E0' },
    ],
    jobResponse: {
      jobResponseId: 'resp-1',
      version: 1,
      physicalState: 'PHYSICALLY_DONE',
      requiredEvidence: 'E0',
      reachedEvidence: 'E0',
      completedUnits: ['T1.travel', 'T1'],
      unverifiedUnits: [],
      incompleteUnits: {},
      inDoubtUnits: [],
      operatorRequired: false,
      residualHold: 'HOLD_KIND_EMPTY',
      blockedBy: [],
      connection: 'CONNECTION_STATE_ONLINE',
    },
    ...partial,
  }
}

/** 제출 200 본문(S3a JSON 계약 §9.3). 기본은 humanoid-01 에 배정된 것이다. */
function submitted(partial: Partial<JobOrderOutcome> = {}): JobOrderOutcome {
  return {
    requestId: '6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a',
    jobOrderId: 'JO-20261008-6f1c2a9e',
    result: 'SUCCEEDED',
    confirmation: null,
    outcome: {
      result: 'ACCEPTED',
      executionId: 'exec-1',
      robotId: 'humanoid-01',
      rejectionReason: null,
      refusals: [],
      excluded: [],
    },
    ...partial,
  }
}

/** 칸 이름(dt) 바로 뒤의 값(dd)을 읽는다. */
function field(region: HTMLElement, name: string) {
  const term = within(region).getByText(name, { selector: 'dt' })
  return term.nextElementSibling?.textContent
}

const calls = (fake: FakeOps, method: string, url: string) =>
  fake.calls.filter((call) => call.method === method && call.url === url)

function open(session: Session = operator) {
  return render(<OperationsArea session={session} onChanged={() => undefined} />)
}

async function fillTarget(index: number, id: string, location: string) {
  const form = screen.getByRole('form', { name: '작업 지시 폼' })
  await userEvent.type(within(form).getByLabelText(`대상 ${index} id`), id)
  await userEvent.type(within(form).getByLabelText(`대상 ${index} 장소`), location)
}

async function submit() {
  await userEvent.click(screen.getByRole('button', { name: '작업 지시 내기' }))
  return screen.findByRole('status', { name: '제출 결과' })
}

/** 한 행의 칸 글자. */
const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((cell) => cell.textContent)

describe('운영 영역', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('InspectAsset 폼은 대상을 더하고 빼며 앞뒤 공백을 뗀 폼 초안을 운영자 모드로 낸다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answer = { status: 200, body: submitted() }
    open()
    await fillTarget(1, ' T1 ', 'bay-7')
    await userEvent.click(screen.getByRole('button', { name: '대상 더하기' }))
    await fillTarget(2, 'T2', 'bay-9')
    await userEvent.click(screen.getByRole('button', { name: '대상 더하기' }))
    await fillTarget(3, 'T3', 'bay-3')
    await userEvent.click(screen.getByRole('button', { name: '대상 2 빼기' }))
    await submit()
    const post = calls(fake, 'POST', '/api/job-orders').at(-1)!
    expect(post.body).toEqual({
      workMasterId: 'InspectAsset',
      targets: [
        { id: 'T1', location: 'bay-7' },
        { id: 'T3', location: 'bay-3' },
      ],
    })
    expect(post.headers['X-Ops-Mode']).toBe('operator')
    expect(post.headers['X-Ops-User']).toBe('kim')
    expect(post.headers['Content-Type']).toBe('application/json')
    // 배정 가능은 같은 폼 초안으로 묻는다. 폼이 멈춘 뒤 디바운스를 지나 묻는다.
    await waitFor(() => expect(calls(fake, 'POST', '/api/job-orders/eligibility').at(-1)?.body).toEqual(post.body))
  })

  it('PrepareSequencedRack 폼은 셀 대역 슬롯을 셀 순서로 담고 자재에서 정한 제시 자리로 낸다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answer = { status: 200, body: submitted() }
    open()
    const form = screen.getByRole('form', { name: '작업 지시 폼' })
    await userEvent.selectOptions(within(form).getByLabelText('임무'), 'PrepareSequencedRack')
    await userEvent.click(await within(form).findByLabelText('RACK-204.S03'))
    await userEvent.click(within(form).getByLabelText('RACK-204.S01'))
    await userEvent.selectOptions(within(form).getByLabelText('자재'), 'ENGINE-COVER-A')
    expect(within(form).getByText('제시 자리 SEQ-IN-02.BIN-A')).toBeInTheDocument()
    await submit()
    expect(calls(fake, 'POST', '/api/job-orders').at(-1)!.body).toEqual({
      workMasterId: 'PrepareSequencedRack',
      slots: ['RACK-204.S01', 'RACK-204.S03'],
      material: 'ENGINE-COVER-A',
      presentation: 'SEQ-IN-02.BIN-A',
    })
  })

  it('엔지니어 모드에서는 제출 대신 운영자 모드에서 낸다고 보이고 폼과 배정 가능은 그대로다', async () => {
    const fake = installFakeOps(emptyList)
    fake.eligibility = eligibilityView({ robots: [robotRow('humanoid-01')] })
    open(engineer)
    await fillTarget(1, 'T1', 'bay-7')
    expect(screen.queryByRole('button', { name: '작업 지시 내기' })).not.toBeInTheDocument()
    expect(screen.getByText('작업 지시는 운영자 모드에서 냅니다')).toBeInTheDocument()
    expect(await screen.findByRole('table', { name: '기체별 배정 가능' })).toBeInTheDocument()
  })

  it('덜 채운 폼은 배정 가능을 묻지 않고 내지도 않는다', async () => {
    const fake = installFakeOps(emptyList)
    open()
    await userEvent.type(screen.getByLabelText('대상 1 id'), 'T1')
    expect(screen.getByText('폼을 채우면 기체별 배정 가능을 봅니다')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '작업 지시 내기' }))
    expect(screen.getByRole('alert')).toHaveTextContent('점검 대상마다 대상 id 와 장소 이름을 넣으십시오')
    await userEvent.click(screen.getByRole('button', { name: '대상 1 빼기' }))
    await userEvent.click(screen.getByRole('button', { name: '작업 지시 내기' }))
    expect(screen.getByRole('alert')).toHaveTextContent('점검 대상을 하나 이상 넣으십시오')
    expect(fake.calls.filter((call) => call.method === 'POST')).toEqual([])
  })

  it('배정은 배정된 기체·실행 id·작업 지시 id 를 보이고 실행 목록의 그 행을 강조한다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answer = { status: 200, body: submitted() }
    open()
    await fillTarget(1, 'T1', 'bay-7')
    fake.executions = executionsView({ executions: [execution({ jobOrderId: 'JO-20261008-6f1c2a9e' })] })
    const notice = await submit()
    expect(notice).toHaveTextContent('InspectAsset 작업 지시: 배정됨')
    expect(field(notice, '작업 지시 id')).toBe('JO-20261008-6f1c2a9e')
    expect(field(notice, '실행 id')).toBe('exec-1')
    expect(field(notice, '배정된 기체')).toBe('humanoid-01')
    const row = await screen.findByRole('row', { name: /JO-20261008-6f1c2a9e/ })
    await waitFor(() => expect(row).toHaveClass('selected'))
  })

  it('거부는 거부 사유를, 미배정은 기체별 미배정 사유와 호스트가 뺀 기체를 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answer = {
      status: 200,
      body: submitted({
        result: 'REJECTED',
        outcome: {
          result: 'REJECTED',
          executionId: null,
          robotId: null,
          rejectionReason: '요구 근거 등급이 임무의 최고 근거보다 높다',
          refusals: [],
          excluded: [],
        },
      }),
    }
    open()
    await fillTarget(1, 'T1', 'bay-7')
    let notice = await submit()
    expect(notice).toHaveTextContent('InspectAsset 작업 지시: 거부됨')
    expect(field(notice, '거부 사유')).toBe('요구 근거 등급이 임무의 최고 근거보다 높다')
    expect(within(notice).queryByText('실행 id')).not.toBeInTheDocument()

    fake.answer = {
      status: 200,
      body: submitted({
        result: 'REJECTED',
        outcome: {
          result: 'UNASSIGNED',
          executionId: null,
          robotId: null,
          rejectionReason: null,
          refusals: [{ robotId: 'humanoid-01', reason: '작업 구역 점유' }],
          excluded: [
            hostRow('quadruped-01', {
              skillFit: 'MISSING',
              missingSkills: ['inspect'],
              passed: false,
              reasons: ['모자란 스킬: inspect'],
            }),
          ],
        },
      }),
    }
    await userEvent.click(screen.getByRole('button', { name: '작업 지시 내기' }))
    await waitFor(() => expect(screen.getByRole('status', { name: '제출 결과' })).toHaveTextContent('미배정'))
    notice = screen.getByRole('status', { name: '제출 결과' })
    expect(
      within(within(notice).getByRole('list', { name: '기체별 미배정 사유' })).getAllByRole('listitem')[0],
    ).toHaveTextContent('humanoid-01: 작업 구역 점유')
    expect(within(notice).getByRole('list', { name: '실행 호스트가 후보에서 뺀 기체' })).toHaveTextContent(
      'quadruped-01: 모자란 스킬: inspect',
    )
  })

  it('운영 서비스가 먼저 막은 400·403 은 막힘이고 기체별 이유를 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answer = {
      status: 400,
      body: {
        error: 'NO_ELIGIBLE_ROBOT',
        detail: 'humanoid-01: 연결이 오래됐다(기준 90초, 현장 설정 버전 1) / quadruped-01: 모자란 스킬: inspect',
      },
    }
    open()
    await fillTarget(1, 'T1', 'bay-7')
    let notice = await submit()
    expect(notice).toHaveTextContent('InspectAsset 작업 지시: 막힘(배정 가능한 기체 없음)')
    expect(notice).toHaveTextContent('humanoid-01: 연결이 오래됐다(기준 90초, 현장 설정 버전 1) / quadruped-01')

    fake.answer = { status: 403, body: { error: 'MODE_NOT_ALLOWED', detail: '운영자 모드만 할 수 있다' } }
    await userEvent.click(screen.getByRole('button', { name: '작업 지시 내기' }))
    await waitFor(() =>
      expect(screen.getByRole('status', { name: '제출 결과' })).toHaveTextContent('막힘(이 모드에서 할 수 없는 조작)'),
    )
    notice = screen.getByRole('status', { name: '제출 결과' })
    expect(notice).toHaveTextContent('운영자 모드만 할 수 있다')
  })

  it.each([
    ['CONFIRMED_APPLIED' as const, '응답은 없었으나 다시 읽어 보니 실행이 있음'],
    ['CONFIRMED_NOT_APPLIED' as const, '응답 없음. 다시 읽어 보니 실행이 없음. 다시 하려면 새로 내십시오'],
    [null, '반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다'],
  ])('호스트 응답 없음은 확인 결과 %s 를 따로 보인다', async (confirmation, text) => {
    const fake = installFakeOps(emptyList)
    fake.answer = { status: 200, body: submitted({ result: 'NO_RESPONSE', confirmation, outcome: null }) }
    open()
    await fillTarget(1, 'T1', 'bay-7')
    const notice = await submit()
    expect(notice).toHaveTextContent(`InspectAsset 작업 지시: ${text}`)
    expect(notice).not.toHaveTextContent('배정됨')
    expect(field(notice, '작업 지시 id')).toBe('JO-20261008-6f1c2a9e')
  })

  it('운영 서비스의 그 밖의 비정상은 결과 모름이다', async () => {
    const fake = installFakeOps(emptyList)
    fake.answer = { status: 500, body: {} }
    open()
    await fillTarget(1, 'T1', 'bay-7')
    const notice = await submit()
    expect(notice).toHaveTextContent('InspectAsset 작업 지시: 결과 모름(운영 서비스 응답 500)')
  })

  it('실행 목록은 호스트 인스턴스를 머리에 두고 최신 실행부터, 임무 버전 null 은 코드 정의로 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({
      instanceId: 'mw-3f0c',
      executions: [
        execution(),
        execution({
          executionId: 'exec-2',
          jobOrderId: 'JO-20261008-bbbbbbbb',
          workMasterId: 'PrepareSequencedRack',
          missionVersion: 3,
          physicalState: 'RUNNING',
          units: [{ unitId: 'RACK-204.S01', skillType: 'pick_place', state: 'RUNNING', reached: 'E0' }],
          jobResponse: null,
        }),
      ],
    })
    open()
    const region = screen.getByRole('region', { name: '실행' })
    const table = await within(region).findByRole('table', { name: '실행 목록' })
    expect(field(region, '실행 호스트 인스턴스')).toBe('mw-3f0c')
    const rows = within(table).getAllByRole('row').slice(1)
    expect(cells(rows[0])).toEqual([
      'exec-2',
      'JO-20261008-bbbbbbbb',
      'PrepareSequencedRack',
      '버전 3',
      'humanoid-01',
      'RUNNING',
      'RACK-204.S01 pick_place: RUNNING, 근거 E0',
      '없음',
    ])
    expect(cells(rows[1])).toEqual([
      'exec-1',
      'JO-20261008-aaaaaaaa',
      'InspectAsset',
      '코드 정의',
      'humanoid-01',
      'PHYSICALLY_DONE',
      'T1.travel navigate_to: DONE, 근거 E0T1 inspect: DONE, 근거 E0',
      'resp-1: PHYSICALLY_DONE, 근거 E0(요구 E0)',
    ])
  })

  it('배정 가능 표는 못 물어본 칸을 모름으로 보이고 배정 불가로 둔다', async () => {
    const fake = installFakeOps(emptyList)
    fake.eligibility = eligibilityView({
      registry: 'REGISTRY_SILENT',
      host: 'OK',
      robots: [
        robotRow('humanoid-01', {
          commissioning: null,
          connection: null,
          settingsVersion: null,
          host: hostRow('humanoid-01', { skillFit: 'UNKNOWN', passed: false, reasons: ['기체 케이퍼빌리티를 못 물어봤다'] }),
          eligible: false,
          reasons: ['registry 가 답하지 않아 시운전·연결을 모른다', '기체 케이퍼빌리티를 못 물어봤다'],
        }),
        robotRow('quadruped-01', {
          host: null,
          eligible: false,
          reasons: ['실행 호스트가 이 기체를 판정하지 않았다'],
        }),
        robotRow('humanoid-02', {
          host: hostRow('humanoid-02', {
            skillFit: 'MISSING',
            missingSkills: ['pick_place'],
            runningExecutionId: 'exec-4',
            passed: false,
            reasons: ['모자란 스킬: pick_place', '도는 실행이 있다: exec-4'],
          }),
          eligible: false,
          reasons: ['모자란 스킬: pick_place', '도는 실행이 있다: exec-4'],
        }),
        robotRow('humanoid-03'),
      ],
    })
    open()
    await fillTarget(1, 'T1', 'bay-7')
    const table = await screen.findByRole('table', { name: '기체별 배정 가능' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows.map(cells)).toEqual([
      [
        'humanoid-01',
        '모름',
        '모름',
        '없음',
        '모름',
        '불가',
        'registry 가 답하지 않아 시운전·연결을 모른다; 기체 케이퍼빌리티를 못 물어봤다',
      ],
      ['quadruped-01', '완료', '신선(현장 설정 버전 1)', '모름', '모름', '불가', '실행 호스트가 이 기체를 판정하지 않았다'],
      [
        'humanoid-02',
        '완료',
        '신선(현장 설정 버전 1)',
        'exec-4',
        '모자람: pick_place',
        '불가',
        '모자란 스킬: pick_place; 도는 실행이 있다: exec-4',
      ],
      ['humanoid-03', '완료', '신선(현장 설정 버전 1)', '없음', '적합', '가능', ''],
    ])
  })

  it('운영 서비스가 폼을 막으면 배정 가능 대신 그 사유를 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.eligibilityStatus = 400
    fake.eligibility = { error: 'JOB_ORDER_BAD_REQUEST', detail: '대상 id 가 64자를 넘는다: 65자' }
    open()
    await fillTarget(1, 'T'.repeat(65), 'bay-7')
    expect(await screen.findByRole('alert')).toHaveTextContent('작업 지시 폼 오류: 대상 id 가 64자를 넘는다: 65자')
    expect(screen.queryByRole('table', { name: '기체별 배정 가능' })).not.toBeInTheDocument()
  })

  it('셀 대역을 표로 보이고 호스트가 셀 대역을 못 읽으면 모름이며 슬롯을 고를 수 없다', async () => {
    const fake = installFakeOps(emptyList)
    const { rerender } = open()
    const region = screen.getByRole('region', { name: '셀 대역' })
    const table = await within(region).findByRole('table', { name: '셀 대역 자리' })
    expect(within(table).getAllByRole('row').slice(1).map(cells).slice(0, 2)).toEqual([
      ['SEQ-IN-02.BIN-A', '제시 자리', '점유', 'ENGINE-COVER-A', '-'],
      ['RACK-204.S01', '슬롯', '비어 있음', '-', '-'],
    ])

    fake.cell = { cell: null }
    // 세션이 바뀌면 다시 읽는다. 주기(5초)를 기다리지 않는다.
    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
    expect(await within(region).findByText('모름: 실행 호스트가 셀 대역을 읽지 못했습니다')).toBeInTheDocument()
    expect(within(region).queryByRole('table')).not.toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('임무'), 'PrepareSequencedRack')
    expect(screen.getByText('모름: 셀 대역을 읽지 못해 슬롯과 자재를 고를 수 없습니다')).toBeInTheDocument()
  })

  it('실행 호스트가 503 이면 실행 목록과 셀 대역은 직전 값과 불통을 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: [execution()] })
    const { rerender } = open()
    const region = screen.getByRole('region', { name: '실행' })
    await within(region).findByRole('table', { name: '실행 목록' })
    fake.failing.add('/api/executions')
    fake.failing.add('/api/cell')
    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
    expect(
      await within(region).findByText('직전 값입니다. 실행 호스트 불통: 실행 호스트가 답하지 않는다: 응답 없음: ConnectException'),
    ).toBeInTheDocument()
    expect(within(region).getByRole('row', { name: /exec-1/ })).toBeInTheDocument()
    expect(
      within(screen.getByRole('region', { name: '셀 대역' })).getByText(/직전 값입니다. 실행 호스트 불통/),
    ).toBeInTheDocument()
  })

  it('주기마다 실행 목록·셀 대역을, 폼이 채워졌을 때만 배정 가능을 다시 읽는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const fake = installFakeOps(emptyList)
    open()
    await user.type(screen.getByLabelText('대상 1 id'), 'T1')
    await user.type(screen.getByLabelText('대상 1 장소'), 'bay-7')
    await waitFor(() => expect(calls(fake, 'POST', '/api/job-orders/eligibility').length).toBeGreaterThan(0))
    const count = () => ({
      executions: calls(fake, 'GET', '/api/executions').length,
      cell: calls(fake, 'GET', '/api/cell').length,
      eligibility: calls(fake, 'POST', '/api/job-orders/eligibility').length,
    })
    let before = count()
    await act(async () => vi.advanceTimersByTime(POLL_MS))
    await waitFor(() =>
      expect(count()).toEqual({
        executions: before.executions + 1,
        cell: before.cell + 1,
        eligibility: before.eligibility + 1,
      }),
    )

    await user.clear(screen.getByLabelText('대상 1 장소'))
    before = count()
    await act(async () => vi.advanceTimersByTime(POLL_MS))
    await waitFor(() => expect(count().executions).toBe(before.executions + 1))
    expect(count().eligibility).toBe(before.eligibility)
  })

  it('폼을 바꾸면 키 입력마다 묻지 않고 멈춘 뒤 디바운스가 지나야 한 번 묻는다', async () => {
    // 시계는 손으로만 민다. userEvent 는 진짜 setTimeout 을 기다리므로 키 입력을 fireEvent 로 한 글자씩 낸다.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const fake = installFakeOps(emptyList)
    open()
    const eligibility = () => calls(fake, 'POST', '/api/job-orders/eligibility')
    const type = (label: string, text: string) => {
      for (let end = 1; end <= text.length; end++) {
        fireEvent.change(screen.getByLabelText(label), { target: { value: text.slice(0, end) } })
      }
    }
    type('대상 1 id', 'T1')
    // 장소 첫 글자부터 폼이 다 찬다. 그 뒤 글자마다 폼이 바뀐다.
    type('대상 1 장소', 'bay-7')
    await act(async () => vi.advanceTimersByTime(ELIGIBILITY_DEBOUNCE_MS - 1))
    expect(eligibility()).toEqual([])

    await act(async () => vi.advanceTimersByTime(1))
    expect(eligibility().map((call) => call.body)).toEqual([
      { workMasterId: 'InspectAsset', targets: [{ id: 'T1', location: 'bay-7' }] },
    ])

    // 주기의 다시 읽기는 디바운스를 기다리지 않는다.
    await act(async () => vi.advanceTimersByTime(POLL_MS - ELIGIBILITY_DEBOUNCE_MS))
    expect(eligibility()).toHaveLength(2)
  })

  it('운영 영역을 열기 전에는 실행 호스트를 읽지 않고 호스트 불통이 기존 다섯 읽기를 직전 값으로 만들지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.failing.add('/api/executions')
    fake.failing.add('/api/cell')
    render(<App />)
    expect(await screen.findByText('선언된 기체가 없습니다')).toBeInTheDocument()
    expect(fake.calls.filter((call) => call.url === '/api/executions' || call.url === '/api/cell')).toEqual([])

    await userEvent.click(screen.getByRole('button', { name: '운영' }))
    expect(
      await screen.findByText('모름: 실행 목록을 아직 읽지 못했습니다 (실행 호스트가 답하지 않는다: 응답 없음: ConnectException)'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/운영 서비스에 닿지 않습니다/)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '로봇·연결' }))
    expect(screen.getByText('선언된 기체가 없습니다')).toBeInTheDocument()
    expect(screen.queryByText(/직전 값입니다/)).not.toBeInTheDocument()
    expect(screen.getByText(/registry 응답 확인/)).toBeInTheDocument()
  })
})
```

`ui/src/components/OperationsArea.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { checkEligibility, fetchCell, fetchExecutions, submitJobOrder } from '../api'
import type { CellView, Delivered, ExecutionsView, JobOrderForm, JobOrderOutcome, Session } from '../api'
import { EMPTY_DRAFT, buildForm } from '../jobOrderDraft'
import type { JobOrderDraft } from '../jobOrderDraft'
import { ELIGIBILITY_DEBOUNCE_MS, POLL_MS } from '../poll'
import { CellBand } from './CellBand'
import { EligibilityTable } from './EligibilityTable'
import type { EligibilityRead } from './EligibilityTable'
import { ExecutionList } from './ExecutionList'
import type { HostRead } from './ExecutionList'
import { JobOrderFormView } from './JobOrderFormView'
import { JobOrderNotice } from './JobOrderNotice'

interface Props {
  session: Session
  /** 제출이 끝나면 부른다. 조작 기록을 다시 읽는다. */
  onChanged: () => void
}

const NO_ELIGIBILITY: EligibilityRead = { view: null, refusal: null, error: null, formKey: null }

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

/**
 * «운영» 영역(S3a 스펙 §9). 작업 지시 폼, 기체별 배정 가능 표, 실행 목록, 셀 대역 표시.
 *
 * 실행 목록·셀·배정 가능은 실행 호스트를 거친다. 그래서 App 의 다섯 조회(`Promise.all`)와 따로, 이 영역이 열려 있을 때만
 * 읽는다(S3a 스펙 §9.3). 호스트가 멈춰도 다섯 조회가 직전 값이 되지 않게 하기 위해서다. 셋은 서로도 따로 실패하고, 못 읽으면
 * 직전 값을 지우지 않고 불통을 표시한다.
 *
 * 배정 가능은 폼이 바뀔 때와 주기마다 다시 읽는다. 연결이 낡으면 표에 보이게 하기 위해서다(S3a 스펙 §9.1). 덜 채운 폼은 묻지
 * 않는다. 폼이 바뀌면 [ELIGIBILITY_DEBOUNCE_MS] 동안 더 바뀌지 않을 때 그 폼으로 한 번 묻는다. 키 입력마다 묻지 않기 위해서다.
 * 주기의 다시 읽기는 기다리지 않는다.
 */
export function OperationsArea({ session, onChanged }: Props) {
  // 주기마다, 그리고 제출이 끝나면 하나 올린다. 셋을 다시 읽는다.
  const [tick, setTick] = useState(0)
  const [executions, setExecutions] = useState<HostRead<ExecutionsView>>({ value: null, error: null })
  const [cell, setCell] = useState<HostRead<CellView>>({ value: null, error: null })
  const [eligibility, setEligibility] = useState<EligibilityRead>(NO_ELIGIBILITY)
  const [draft, setDraft] = useState<JobOrderDraft>(EMPTY_DRAFT)
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Delivered<JobOrderOutcome> } | null>(null)

  useEffect(() => {
    const timer = setInterval(() => setTick((value) => value + 1), POLL_MS)
    return () => clearInterval(timer)
  }, [])

  useEffect(() => {
    let alive = true
    // 둘을 묶지 않는다. 셀 대역만 못 읽어도 실행 목록은 새 값이어야 한다.
    fetchExecutions(session)
      .then((value) => {
        if (alive) setExecutions({ value, error: null })
      })
      .catch((error: unknown) => {
        if (alive) setExecutions((previous) => ({ ...previous, error: message(error) }))
      })
    fetchCell(session)
      .then((value) => {
        if (alive) setCell({ value, error: null })
      })
      .catch((error: unknown) => {
        if (alive) setCell((previous) => ({ ...previous, error: message(error) }))
      })
    return () => {
      alive = false
    }
  }, [session, tick])

  const built = buildForm(draft, cell.value?.cell ?? null)
  // 폼을 글자로 비교한다. 셀을 다시 읽을 때마다 객체가 바뀌어도 같은 폼이면 다시 묻지 않는다.
  const formKey = built.kind === 'form' ? JSON.stringify(built.form) : null

  // 배정 가능을 물을 폼. 폼이 멈추고 ELIGIBILITY_DEBOUNCE_MS 가 지나야 따라온다. 그 사이 표는 다시 판정하는 중으로 보인다.
  const [settledKey, setSettledKey] = useState<string | null>(null)
  useEffect(() => {
    const timer = setTimeout(() => setSettledKey(formKey), ELIGIBILITY_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [formKey])

  useEffect(() => {
    if (settledKey === null) return
    let alive = true
    checkEligibility(session, JSON.parse(settledKey) as JobOrderForm).then((sent) => {
      if (!alive) return
      if (sent.kind === 'outcome') setEligibility({ view: sent.outcome, refusal: null, error: null, formKey: settledKey })
      else if (sent.kind === 'refused') {
        setEligibility({ view: null, refusal: sent.refusal, error: null, formKey: settledKey })
      } else setEligibility((previous) => ({ ...previous, error: sent.cause }))
    })
    return () => {
      alive = false
    }
  }, [session, tick, settledKey])

  const change = (next: JobOrderDraft) => {
    setDraft(next)
    setProblem(null)
  }

  const submit = () => {
    if (built.kind !== 'form') {
      setProblem(built.problem)
      return
    }
    const what = `${built.form.workMasterId} 작업 지시`
    setBusy(true)
    submitJobOrder(session, built.form)
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        setTick((value) => value + 1)
        onChanged()
      })
  }

  const submitted = last?.sent.kind === 'outcome' ? last.sent.outcome.jobOrderId : null

  return (
    <>
      <div className="split">
        <section aria-label="작업 지시">
          <h2>작업 지시</h2>
          {last !== null && <JobOrderNotice what={last.what} sent={last.sent} />}
          <JobOrderFormView
            draft={draft}
            cell={cell.value?.cell ?? null}
            mode={session.mode}
            busy={busy}
            problem={problem}
            onChange={change}
            onSubmit={submit}
          />
        </section>
        <section aria-label="배정 가능">
          <h2>배정 가능</h2>
          <EligibilityTable read={eligibility} formKey={formKey} />
        </section>
      </div>
      <section aria-label="실행">
        <h2>실행</h2>
        <ExecutionList read={executions} highlight={submitted} />
      </section>
      <section aria-label="셀 대역">
        <h2>셀 대역</h2>
        <CellBand read={cell} />
      </section>
    </>
  )
}
```

`ui/src/jobOrderDraft.ts`:

```ts
import type { CellView, InspectionTarget, JobOrderForm, WorkMasterId } from './api'

/** 작업 지시 폼에 든 값. 임무를 바꿔도 다른 임무의 입력을 지우지 않는다. */
export interface JobOrderDraft {
  workMasterId: WorkMasterId
  /** InspectAsset 의 점검 대상. 입력 그대로이고 보낼 때 앞뒤 공백을 뗀다. */
  targets: InspectionTarget[]
  /** PrepareSequencedRack 에 고른 슬롯. 셀 대역의 슬롯 순서를 따른다. */
  slots: string[]
  /** PrepareSequencedRack 에 고른 자재. 빈 문자열이면 아직 고르지 않았다. */
  material: string
}

export const EMPTY_DRAFT: JobOrderDraft = {
  workMasterId: 'InspectAsset',
  targets: [{ id: '', location: '' }],
  slots: [],
  material: '',
}

type Cell = NonNullable<CellView['cell']>

/** 자재 선택지. 점유된 제시 자리가 든 자재이고, 같은 자재는 한 번만 낸다. */
export function materialsOf(cell: Cell): string[] {
  const materials = cell.presentations.flatMap((place) =>
    place.occupied && place.material !== null ? [place.material] : [],
  )
  return [...new Set(materials)]
}

/**
 * 자재에서 정해지는 제시 자리(S3a 스펙 §7.5). 그 자재를 든 제시 자리 중 셀 대역 목록의 처음 것이다. 채운 슬롯은 집을 자리가
 * 아니므로 보지 않는다.
 */
export function presentationFor(cell: Cell, material: string): string | null {
  return cell.presentations.find((place) => place.occupied && place.material === material)?.id ?? null
}

/** 슬롯 하나를 넣거나 뺀다. 결과는 셀 대역의 슬롯 순서다. */
export function toggleSlot(cell: Cell, chosen: string[], slot: string): string[] {
  const next = chosen.includes(slot) ? chosen.filter((id) => id !== slot) : [...chosen, slot]
  return cell.slots.map((place) => place.id).filter((id) => next.includes(id))
}

export type Built = { kind: 'form'; form: JobOrderForm } | { kind: 'incomplete'; problem: string }

/**
 * 폼 값에서 운영 서비스로 보낼 폼 초안을 만든다(S3a JSON 계약 §9.1). 덜 채운 폼은 보내지 않고 이유를 낸다. 단위 id 겹침과 대상 id
 * 길이는 운영 서비스가 판정하고 화면은 그 거부를 보인다. 같은 규칙을 두 자리에 두지 않기 위해서다.
 *
 * @param cell 셀 대역. null 이면 모름이라 PrepareSequencedRack 의 슬롯과 제시 자리를 정할 수 없다
 */
export function buildForm(draft: JobOrderDraft, cell: Cell | null): Built {
  if (draft.workMasterId === 'InspectAsset') {
    const targets = draft.targets.map((target) => ({ id: target.id.trim(), location: target.location.trim() }))
    if (targets.length === 0) return { kind: 'incomplete', problem: '점검 대상을 하나 이상 넣으십시오' }
    if (targets.some((target) => target.id === '' || target.location === '')) {
      return { kind: 'incomplete', problem: '점검 대상마다 대상 id 와 장소 이름을 넣으십시오' }
    }
    return { kind: 'form', form: { workMasterId: 'InspectAsset', targets } }
  }
  if (cell === null) return { kind: 'incomplete', problem: '셀 대역을 읽지 못해 슬롯과 자재를 고를 수 없습니다' }
  if (draft.slots.length === 0) return { kind: 'incomplete', problem: '슬롯을 하나 이상 고르십시오' }
  if (draft.material === '') return { kind: 'incomplete', problem: '자재를 고르십시오' }
  const presentation = presentationFor(cell, draft.material)
  if (presentation === null) {
    return { kind: 'incomplete', problem: `${draft.material} 를 든 제시 자리가 셀 대역에 없습니다` }
  }
  return {
    kind: 'form',
    form: { workMasterId: 'PrepareSequencedRack', slots: draft.slots, material: draft.material, presentation },
  }
}
```

`ui/src/poll.ts`:

```ts
/** 운영 서비스를 다시 읽는 주기. 다섯 조회와 «운영» 영역의 실행 호스트 읽기가 같은 주기를 쓴다. */
export const POLL_MS = 5000

/** 작업 지시 폼이 바뀐 뒤 배정 가능을 묻기까지 기다리는 시간. 그 사이 다시 바뀌면 처음부터 다시 기다린다. */
export const ELIGIBILITY_DEBOUNCE_MS = 300
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task5.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task5.patch"
```

```diff
diff --git a/ui/src/App.test.tsx b/ui/src/App.test.tsx
index 45e356f..a38d494 100644
--- a/ui/src/App.test.tsx
+++ b/ui/src/App.test.tsx
@@ -43,7 +43,7 @@ function serve(view: RobotListView, records: OperationRecord[] = []) {
 describe('App', () => {
   afterEach(() => vi.unstubAllGlobals())
 
-  it('메뉴가 5영역이고 아직 닫힌 2영역은 다음 단계로 표시한다', () => {
+  it('메뉴가 5영역이고 아직 닫힌 1영역은 다음 단계로 표시한다', () => {
     serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
     render(<App />)
     const nav = screen.getByRole('navigation', { name: '영역' })
@@ -51,7 +51,7 @@ describe('App', () => {
       '현장·자원',
       '로봇·연결',
       '임무·정책 다음 단계',
-      '운영 다음 단계',
+      '운영',
       '이력',
     ])
   })
diff --git a/ui/src/App.tsx b/ui/src/App.tsx
index 021ffed..c3f4009 100644
--- a/ui/src/App.tsx
+++ b/ui/src/App.tsx
@@ -5,11 +5,11 @@ import { AREAS } from './areas'
 import type { AreaId } from './areas'
 import { HistoryArea } from './components/HistoryArea'
 import { ModeSwitch } from './components/ModeSwitch'
+import { OperationsArea } from './components/OperationsArea'
 import { RegistryBanner } from './components/RegistryBanner'
 import { RobotsArea } from './components/RobotsArea'
 import { SiteArea } from './components/SiteArea'
-
-const POLL_MS = 5000
+import { POLL_MS } from './poll'
 
 const message = (error: unknown) => (error instanceof Error ? error.message : String(error))
 
@@ -28,7 +28,8 @@ export default function App() {
   useEffect(() => {
     let alive = true
     // 실패해도 직전 값을 지우지 않는다. 대신 opsError 로 직전 값임을 표시한다(스펙 §9). 다섯 중 하나라도 못 읽으면 다섯 다
-    // 직전 값이다(P2·S1d 스펙 §8.1, S2 스펙 §7).
+    // 직전 값이다(P2·S1d 스펙 §8.1, S2 스펙 §7). 실행 목록·셀·배정 가능은 실행 호스트를 거치므로 여기 넣지 않는다. 호스트가
+    // 멈춰도 다섯이 직전 값이 되지 않게 «운영» 영역이 따로 읽는다(S3a 스펙 §9.3).
     const load = () => {
       Promise.all([
         fetchRobots(session),
@@ -99,6 +100,9 @@ export default function App() {
             onChanged={() => setTick((value) => value + 1)}
           />
         )}
+        {current.id === 'operations' && (
+          <OperationsArea session={session} onChanged={() => setTick((value) => value + 1)} />
+        )}
         {current.id === 'history' && <HistoryArea records={records} opsError={opsError} />}
       </main>
     </>
diff --git a/ui/src/api.ts b/ui/src/api.ts
index f88abb2..1be30c1 100644
--- a/ui/src/api.ts
+++ b/ui/src/api.ts
@@ -250,15 +250,18 @@ export interface PreRejection {
 }
 
 /**
- * 조작을 보낸 결과. registry 에 닿았으면 `outcome`, 운영 서비스가 먼저 막았으면 `refused` 다.
- * 그 밖의 실패(연결 끊김, 프록시 오류, 운영 서비스 500)는 `unknown` 이다. 요청이 registry 까지 갔는지 모르므로
- * 보내지 못했다고 단정하지 않는다(스펙 §9).
+ * 요청을 보낸 결과. 운영 서비스가 2xx 로 답했으면 `outcome`, 운영 서비스가 먼저 막았으면(400·403) `refused` 다.
+ * 그 밖의 실패(연결 끊김, 프록시 오류, 운영 서비스 500)는 `unknown` 이다. 요청이 상대(registry, 실행 호스트)까지 갔는지
+ * 모르므로 보내지 못했다고 단정하지 않는다(스펙 §9).
  */
-export type Sent =
-  | { kind: 'outcome'; outcome: OperationOutcome }
+export type Delivered<T> =
+  | { kind: 'outcome'; outcome: T }
   | { kind: 'refused'; refusal: PreRejection }
   | { kind: 'unknown'; cause: string }
 
+/** registry 쓰기 조작을 보낸 결과. registry 에 닿았으면 `outcome` 이다. */
+export type Sent = Delivered<OperationOutcome>
+
 /** 사용자 이름 규칙. 운영 서비스의 `Actor` 와 같다. 헤더는 ASCII 만 실을 수 있고 `/` 는 모드와 사용자를 가르는 자리다. */
 export const USER_PATTERN = /^[A-Za-z0-9._-]{1,64}$/
 
@@ -299,10 +302,10 @@ function sendDocument(path: string, session: Session, text: string): Promise<Sen
   })
 }
 
-async function deliver(path: string, init: RequestInit): Promise<Sent> {
+async function deliver<T = OperationOutcome>(path: string, init: RequestInit): Promise<Delivered<T>> {
   try {
     const response = await fetch(path, init)
-    if (response.ok) return { kind: 'outcome', outcome: (await response.json()) as OperationOutcome }
+    if (response.ok) return { kind: 'outcome', outcome: (await response.json()) as T }
     if (response.status === 400 || response.status === 403) {
       // 스프링이 직접 막은 400 의 본문에는 detail 이 없다. 그때도 사유 칸을 비우지 않는다.
       const refusal = (await response.json()) as Partial<PreRejection>
@@ -364,3 +367,165 @@ export const bindRobot = (session: Session, robotId: string, adapterVersionId: n
   send('POST', `/api/robots/${encodeURIComponent(robotId)}/binding`, session, { adapterVersionId, profileRevisionId })
 export const recordSiteNames = (session: Session, robotId: string) =>
   send('POST', `/api/robots/${encodeURIComponent(robotId)}/site-names`, session)
+
+/** 작업 지시 폼이 내는 임무(S3a 스펙 §9.1). DeliverContainer 는 플릿 포트 구현이 없어 내지 않는다. */
+export type WorkMasterId = 'InspectAsset' | 'PrepareSequencedRack'
+
+/** InspectAsset 의 점검 대상 하나. `location` 은 기체가 아는 명칭이어야 하나 화면이 검사하지 않는다. */
+export interface InspectionTarget {
+  id: string
+  location: string
+}
+
+/**
+ * 작업 지시 폼 초안(S3a JSON 계약 §9.1). 판정과 제출이 같은 본문을 쓴다. 작업 지시 본문(작업 지시 id, 요구 근거 등급, 장비 요구)은
+ * 운영 서비스가 만든다.
+ */
+export type JobOrderForm =
+  | { workMasterId: 'InspectAsset'; targets: InspectionTarget[] }
+  | { workMasterId: 'PrepareSequencedRack'; slots: string[]; material: string; presentation: string }
+
+export type SkillFit = 'FIT' | 'MISSING' | 'UNKNOWN'
+
+/** 실행 호스트의 기체 판정(S3a JSON 계약 §2.2). `UNKNOWN` 은 호스트가 기체 케이퍼빌리티를 못 물어본 것이고 모름이다. */
+export interface HostEligibility {
+  robotId: string
+  skillFit: SkillFit
+  missingSkills: string[]
+  runningExecutionId: string | null
+  passed: boolean
+  reasons: string[]
+}
+
+/**
+ * 기체 한 대의 배정 가능 판정(S3a JSON 계약 §9.2). 시운전·연결은 운영 서비스가, 스킬 적합·도는 실행은 실행 호스트가 판정한다.
+ * null 은 모름이고, 모름이 하나라도 있으면 배정 가능이 아니다.
+ */
+export interface RobotEligibility {
+  robotId: string
+  commissioning: CommissioningState | null
+  connection: Connection | null
+  settingsVersion: number | null
+  host: HostEligibility | null
+  eligible: boolean
+  reasons: string[]
+}
+
+export type HostState = 'OK' | 'HOST_SILENT'
+
+/** 운영 서비스의 `POST /api/job-orders/eligibility`. `robots` 가 null 이면 기체 목록을 한 번도 못 읽은 모름이다. */
+export interface EligibilityView {
+  checkedAt: string
+  registry: RegistryState
+  robotsAsOf: string | null
+  host: HostState
+  robots: RobotEligibility[] | null
+}
+
+export type HostSubmitResult = 'ACCEPTED' | 'IDEMPOTENT' | 'REJECTED' | 'UNASSIGNED'
+
+/** 실행 호스트의 제출 결과(S3a JSON 계약 §4). `refusals` 는 미배정일 때 기체별 관문 사유, `excluded` 는 호스트가 판정에서 뺀 기체다. */
+export interface HostSubmitOutcome {
+  result: HostSubmitResult
+  executionId: string | null
+  robotId: string | null
+  rejectionReason: string | null
+  refusals: { robotId: string; reason: string }[]
+  excluded: HostEligibility[]
+}
+
+/**
+ * 작업 지시 제출의 200 응답(S3a JSON 계약 §9.3). 기존 [OperationOutcome] 과 모양이 달라 따로 읽는다(S3a 스펙 §8).
+ * `outcome` 은 실행 호스트의 응답이고, 호스트가 안 닿았으면 null 이다.
+ */
+export interface JobOrderOutcome {
+  requestId: string
+  jobOrderId: string
+  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
+  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
+  outcome: HostSubmitOutcome | null
+}
+
+/** 실행의 단위 하나(S3a JSON 계약 §5). `reached` 는 그 단위가 얻은 근거 등급이다. */
+export interface ExecutionUnit {
+  unitId: string
+  skillType: string
+  state: string
+  reached: string
+}
+
+/** 실행의 마지막 작업 응답(S3a JSON 계약 §5). 상위 시스템이 없어 실행 호스트의 아웃박스에 남는다. */
+export interface JobResponse {
+  jobResponseId: string
+  version: number
+  physicalState: string
+  requiredEvidence: string
+  reachedEvidence: string
+  completedUnits: string[]
+  unverifiedUnits: string[]
+  incompleteUnits: Record<string, string>
+  inDoubtUnits: string[]
+  operatorRequired: boolean
+  residualHold: string
+  blockedBy: string[]
+  connection: string
+}
+
+/** 실행 하나(S3a JSON 계약 §5). `missionVersion` 이 null 이면 코드 정의 임무다. */
+export interface Execution {
+  executionId: string
+  jobOrderId: string
+  workMasterId: string
+  missionVersion: number | null
+  robotId: string
+  physicalState: string
+  units: ExecutionUnit[]
+  jobResponse: JobResponse | null
+}
+
+/** 운영 서비스의 `GET /api/executions`(실행 호스트 본문 그대로). `instanceId` 가 바뀌면 호스트가 재기동한 것이다. */
+export interface ExecutionsView {
+  instanceId: string
+  pumpedAt: string | null
+  executions: Execution[]
+}
+
+/** 셀 대역의 자리 하나(S3a JSON 계약 §1). 제시 자리의 `observedAt` 은 늘 null 이다. */
+export interface CellPlace {
+  id: string
+  occupied: boolean
+  material: string | null
+  observedAt: string | null
+}
+
+/** 운영 서비스의 `GET /api/cell`. `cell` 이 null 이면 실행 호스트가 셀 대역을 못 읽은 모름이다. */
+export interface CellView {
+  cell: { presentations: CellPlace[]; slots: CellPlace[] } | null
+}
+
+/**
+ * 실행 호스트를 거치는 읽기. 운영 서비스가 호스트에 닿지 못하면 503 과 `{error, detail}` 이므로, 그 detail 을 오류 문구로 쓴다.
+ * 기존 다섯 읽기와 따로 실패한다(S3a 스펙 §9.3).
+ */
+async function getHostJson<T>(path: string, session: Session): Promise<T> {
+  const response = await fetch(path, { headers: actorHeaders(session) })
+  if (response.ok) return (await response.json()) as T
+  const body = (await response.json().catch(() => null)) as Partial<PreRejection> | null
+  throw new Error(
+    typeof body?.detail === 'string' && body.detail !== '' ? body.detail : `운영 서비스 응답 ${response.status}`,
+  )
+}
+
+const postJobOrderForm = <T>(path: string, session: Session, form: JobOrderForm) =>
+  deliver<T>(path, {
+    method: 'POST',
+    headers: { ...actorHeaders(session), 'Content-Type': 'application/json' },
+    body: JSON.stringify(form),
+  })
+
+export const fetchExecutions = (session: Session) => getHostJson<ExecutionsView>('/api/executions', session)
+export const fetchCell = (session: Session) => getHostJson<CellView>('/api/cell', session)
+export const checkEligibility = (session: Session, form: JobOrderForm) =>
+  postJobOrderForm<EligibilityView>('/api/job-orders/eligibility', session, form)
+export const submitJobOrder = (session: Session, form: JobOrderForm) =>
+  postJobOrderForm<JobOrderOutcome>('/api/job-orders', session, form)
diff --git a/ui/src/areas.ts b/ui/src/areas.ts
index ed016a4..abbb8ef 100644
--- a/ui/src/areas.ts
+++ b/ui/src/areas.ts
@@ -3,7 +3,7 @@ export type AreaId = 'site' | 'robots' | 'missions' | 'operations' | 'history'
 export interface Area {
   id: AreaId
   label: string
-  /** 지금 동작하는 영역. 나머지는 다음 단계로 표시한다(스펙 §8). 현장·자원은 S2 에서 열었다. */
+  /** 지금 동작하는 영역. 나머지는 다음 단계로 표시한다(스펙 §8). 현장·자원은 S2, 운영은 S3a 에서 열었다. */
   ready: boolean
 }
 
@@ -11,6 +11,6 @@ export const AREAS: readonly Area[] = [
   { id: 'site', label: '현장·자원', ready: true },
   { id: 'robots', label: '로봇·연결', ready: true },
   { id: 'missions', label: '임무·정책', ready: false },
-  { id: 'operations', label: '운영', ready: false },
+  { id: 'operations', label: '운영', ready: true },
   { id: 'history', label: '이력', ready: true },
 ]
diff --git a/ui/src/labels.ts b/ui/src/labels.ts
index 8049b84..963693f 100644
--- a/ui/src/labels.ts
+++ b/ui/src/labels.ts
@@ -1,4 +1,4 @@
-import type { CommissioningState, Connection, Owner, TestRequestState } from './api'
+import type { CommissioningState, Connection, HostSubmitResult, Owner, SkillFit, TestRequestState } from './api'
 
 /** 화면에 보이는 이름. 값은 운영 서비스의 열거형 그대로 받고, 이름만 여기서 붙인다. */
 export const CONNECTION_LABEL: Record<Connection, string> = {
@@ -46,6 +46,12 @@ export const KIND_LABEL: Record<string, string> = {
   NOTHING_TO_REGISTER: '등록할 명칭 없음',
   UNCLASSIFIED: '분류되지 않은 거부',
   SETTINGS_VERSION_CONFLICT: '현장 설정 버전 충돌',
+  JOB_ORDER_BAD_REQUEST: '작업 지시 폼 오류',
+  UNKNOWN_WORK_MASTER: '받지 않는 임무',
+  UNIT_ID_CONFLICT: '단위 id 겹침',
+  NO_ELIGIBLE_ROBOT: '배정 가능한 기체 없음',
+  MODE_NOT_ALLOWED: '이 모드에서 할 수 없는 조작',
+  ACTOR_REQUIRED: '행위자 없음',
 }
 
 /** «시운전» 칸(P2·S1d 스펙 §8.5). 연결 칸과 합치지 않는다. */
@@ -72,3 +78,18 @@ export const TEST_REQUEST_LABEL: Record<TestRequestState, string> = {
 }
 
 export const kindLabel = (kind: string) => KIND_LABEL[kind] ?? kind
+
+/** 실행 호스트의 스킬 적합(S3a 스펙 §9.1). `UNKNOWN` 은 기체 케이퍼빌리티를 못 물어본 것이다. */
+export const SKILL_FIT_LABEL: Record<SkillFit, string> = {
+  FIT: '적합',
+  MISSING: '모자람',
+  UNKNOWN: '모름',
+}
+
+/** 작업 지시 제출의 호스트 결과(S3a JSON 계약 §4). */
+export const SUBMIT_RESULT_LABEL: Record<HostSubmitResult, string> = {
+  ACCEPTED: '배정됨',
+  IDEMPOTENT: '같은 작업 지시의 기존 실행',
+  REJECTED: '거부됨',
+  UNASSIGNED: '미배정',
+}
diff --git a/ui/src/testing/fakeOps.ts b/ui/src/testing/fakeOps.ts
index 391c767..05ee923 100644
--- a/ui/src/testing/fakeOps.ts
+++ b/ui/src/testing/fakeOps.ts
@@ -2,8 +2,12 @@ import { vi } from 'vitest'
 import type {
   AdapterListView,
   Binding,
+  CellView,
+  EligibilityView,
+  ExecutionsView,
   Finding,
   OperationOutcome,
+  PreRejection,
   ProfileListView,
   Revision,
   SiteSettingsView,
@@ -28,11 +32,45 @@ export interface FakeOps {
   adapters: AdapterListView
   profiles: ProfileListView
   settings: SiteSettingsView
-  /** 여기 든 경로의 GET 은 503 이다. 운영 서비스의 일부 읽기만 실패하는 경우를 만든다. */
+  /** 실행 호스트를 거치는 읽기(S3a). 운영 서비스가 호스트 본문을 그대로 넘기는 모양이다. */
+  executions: ExecutionsView
+  cell: CellView
+  /** `POST /api/job-orders/eligibility` 의 답. 폼 거부(400)를 만들려면 [eligibilityStatus] 와 본문을 바꾼다. */
+  eligibility: EligibilityView | PreRejection
+  eligibilityStatus: number
+  /**
+   * 여기 든 경로의 GET 은 503 이다. 운영 서비스의 일부 읽기만 실패하는 경우를 만든다. 실행 호스트를 거치는 경로는 운영
+   * 서비스처럼 `HOST_SILENT` 본문을 싣는다. 배정 가능 판정은 POST 지만 읽기이므로 여기 들면 503 이다.
+   */
   failing: Set<string>
   answer: { status: number; body: unknown }
 }
 
+/** 실행 호스트를 거치는 경로. 503 일 때 운영 서비스가 `HOST_SILENT` 를 싣는다(S3a JSON 계약 §9.4). */
+const HOST_PATHS = new Set(['/api/executions', '/api/cell'])
+const ELIGIBILITY_PATH = '/api/job-orders/eligibility'
+
+/** 실행 목록. 기본은 실행이 없는 호스트 인스턴스 하나다. */
+export function executionsView(partial: Partial<ExecutionsView> = {}): ExecutionsView {
+  return { instanceId: 'mw-1', pumpedAt: 't1', executions: [], ...partial }
+}
+
+/** 셀 대역. 기본은 고정 픽스처(제시 자리 하나, 빈 슬롯 넷)다(S3a JSON 계약 §1). */
+export function cellView(): CellView {
+  const slot = (id: string) => ({ id, occupied: false, material: null, observedAt: null })
+  return {
+    cell: {
+      presentations: [{ id: 'SEQ-IN-02.BIN-A', occupied: true, material: 'ENGINE-COVER-A', observedAt: null }],
+      slots: ['RACK-204.S01', 'RACK-204.S02', 'RACK-204.S03', 'RACK-204.S04'].map(slot),
+    },
+  }
+}
+
+/** 배정 가능 판정. 기본은 기체가 없는 사이트다. */
+export function eligibilityView(partial: Partial<EligibilityView> = {}): EligibilityView {
+  return { checkedAt: 't1', registry: 'OK', robotsAsOf: 't1', host: 'OK', robots: [], ...partial }
+}
+
 /** 어댑터 목록. 기본은 제품도 인스턴스도 없는 «없음» 이다. */
 export function adapterView(partial: Partial<AdapterListView> = {}): AdapterListView {
   return { registry: 'OK', checkedAt: 't1', adapters: [], instances: [], asOf: 't1', ...partial }
@@ -159,6 +197,10 @@ export function installFakeOps(
     adapters,
     profiles,
     settings,
+    executions: executionsView(),
+    cell: cellView(),
+    eligibility: eligibilityView(),
+    eligibilityStatus: 200,
     failing: new Set(),
     answer: { status: 200, body: outcome({}) },
   }
@@ -173,8 +215,13 @@ export function installFakeOps(
       }
       const method = init?.method ?? 'GET'
       fake.calls.push({ method, url, headers, body: init?.body ? JSON.parse(init.body as string) : undefined })
+      if (fake.failing.has(url) && (method === 'GET' || url === ELIGIBILITY_PATH)) {
+        const body = HOST_PATHS.has(url)
+          ? JSON.stringify({ error: 'HOST_SILENT', detail: '실행 호스트가 답하지 않는다: 응답 없음: ConnectException' })
+          : ''
+        return new Response(body, { status: 503 })
+      }
       if (method === 'GET') {
-        if (fake.failing.has(url)) return new Response('', { status: 503 })
         const body =
           url === '/api/robots'
             ? fake.view
@@ -184,9 +231,16 @@ export function installFakeOps(
                 ? fake.profiles
                 : url === '/api/site-settings'
                   ? fake.settings
-                  : []
+                  : url === '/api/executions'
+                    ? fake.executions
+                    : url === '/api/cell'
+                      ? fake.cell
+                      : []
         return new Response(JSON.stringify(body), { status: 200 })
       }
+      if (url === ELIGIBILITY_PATH) {
+        return new Response(JSON.stringify(fake.eligibility), { status: fake.eligibilityStatus })
+      }
       return new Response(JSON.stringify(fake.answer.body), { status: fake.answer.status })
     }),
   )
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && cd ui && npm ci && npm test && npm run lint && npx tsc -b && npm run build
```
Expected: vitest 74 통과(파일 7), lint·tsc·build 종료 0.

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git add ui/src/components/CellBand.tsx ui/src/components/EligibilityTable.tsx ui/src/components/ExecutionList.tsx ui/src/components/JobOrderFormView.tsx ui/src/components/JobOrderNotice.tsx ui/src/components/OperationsArea.test.tsx ui/src/components/OperationsArea.tsx ui/src/jobOrderDraft.ts ui/src/poll.ts ui/src/App.test.tsx ui/src/App.tsx ui/src/api.ts ui/src/areas.ts ui/src/labels.ts ui/src/testing/fakeOps.ts && git commit -F - <<'EOF'
feat(ui): 운영 영역의 작업 지시 폼·배정 가능 표·실행 목록·셀 대역 표시 추가

- 운영자 모드 제출, 배정 가능 판정 300ms 디바운스, 폴링은 다섯 조회 밖에서 영역이 열렸을 때만
- 실행 목록의 코드 정의 표시, 셀 대역 표

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s3a-cmp.sh" ui/src/components/CellBand.tsx ui/src/components/EligibilityTable.tsx ui/src/components/ExecutionList.tsx ui/src/components/JobOrderFormView.tsx ui/src/components/JobOrderNotice.tsx ui/src/components/OperationsArea.test.tsx ui/src/components/OperationsArea.tsx ui/src/jobOrderDraft.ts ui/src/poll.ts ui/src/App.test.tsx ui/src/App.tsx ui/src/api.ts ui/src/areas.ts ui/src/labels.ts ui/src/testing/fakeOps.ts
```
Expected: 15개 모두 `같음`.

### Task 6: 통합 시험

**Files:**
- Create: `e2e/src/test/kotlin/dev/picasso/ops/e2e/Commissioned.kt`, `e2e/src/test/kotlin/dev/picasso/ops/e2e/JobOrderTest.kt`
- Modify: `e2e/build.gradle.kts`, `e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt`

- [ ] **Step 1: 새 파일 2개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p e2e/src/test/kotlin/dev/picasso/ops/e2e && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/e2e/src/test/kotlin/dev/picasso/ops/e2e/Commissioned.kt" e2e/src/test/kotlin/dev/picasso/ops/e2e/Commissioned.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && mkdir -p e2e/src/test/kotlin/dev/picasso/ops/e2e && cp "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/files/e2e/src/test/kotlin/dev/picasso/ops/e2e/JobOrderTest.kt" e2e/src/test/kotlin/dev/picasso/ops/e2e/JobOrderTest.kt
```

`e2e/src/test/kotlin/dev/picasso/ops/e2e/Commissioned.kt`:

```kotlin
package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * 공용 시운전 픽스처(S3a 스펙 §11). `CommissioningTest` 의 단계를 한 함수로 묶어 명부의 두 기체를 시운전 완료로 만든다.
 *
 * 단계: 기체 선언 → 보고 → 어댑터 제품·빌드 등록 → 리비전 제출 → 시험 요청(현장 실행기가 실제 시간으로 돔) → 활성화 → 바인딩 →
 * 명칭 기록. `quadruped-01` 은 명부에서 명칭을 티칭하지 않았으므로 선언 전에 현장에서 티칭한다(`Site.teach`). 그래야 첫 보고에
 * 기체가 아는 명칭이 실려 명칭을 기록하자마자 시운전이 완료된다.
 *
 * 연결 신선은 registry 수신 시각(실제 시각) 기준이고 생존 보고는 현장이 시계를 밀 때만 나간다. 실행기를 실제 시간으로 기다리는
 * 단계가 있으므로 마지막에 시계를 한 번 밀어 생존 보고를 내고 끝낸다. 이 함수는 단계마다의 중간 상태를 단언하지 않는다. 그것은
 * `CommissioningTest` 의 몫이다. 끝 상태(둘 다 시운전 완료, 연결 신선)만 확인하고 아니면 멈춘다.
 */
object Commissioned {

    const val HUMANOID = "humanoid-01"
    const val QUADRUPED = "quadruped-01"

    /** 현장에서 `quadruped-01` 에 티칭하는 명칭. `humanoid-01` 의 명부 값과 같다. */
    val QUADRUPED_SITE_NAMES = listOf("dock-3", "bay-7")

    /** 기체 → 일련번호·기종 프로파일. 명부(`site/robots.json`)와 같다. */
    private val ROBOTS = linkedMapOf(HUMANOID to ("HA-0001" to "humanoid-a"), QUADRUPED to ("QB-0001" to "quadruped-b"))

    /** 시운전에 쓴 식별자. 시험이 더 조작할 때 쓴다. */
    data class Ids(val buildId: Long, val revisions: Map<String, Long>)

    /** 상태 발행이 생존 보고다. 보고 주기(가상 30초)를 넘겨 민다. */
    private val REPORT = Duration.ofSeconds(31)

    /** 실행기를 기다리는 상한(실제 시간). */
    private val RUNNER_WAIT = Duration.ofSeconds(60)

    fun complete(stack: E2eStack): Ids {
        stack.site.teach(QUADRUPED, QUADRUPED_SITE_NAMES)
        ROBOTS.forEach { (robotId, robot) ->
            engineer(stack, "/api/robots", """{"robotId":"$robotId","serialNumber":"${robot.first}"}""")
        }
        stack.site.advance(REPORT)

        engineer(stack, "/api/adapters", """{"vendor":"acme","name":"fleet"}""")
        val adapterId = stack.get("/api/adapters")["adapters"].single()["adapterId"].asLong()
        engineer(stack, "/api/adapters/$adapterId/versions", """{"version":"1.0.0","contractSemver":"0.9.0"}""")
        val buildId = stack.get("/api/adapters")["adapters"].single()["versions"].single()["adapterVersionId"].asLong()

        ROBOTS.values.forEach { (_, model) -> engineer(stack, "/api/profile-revisions", document(model)) }
        val revisions = stack.get("/api/profiles")["revisions"].associate {
            it["revision"]["model"].asText() to it["revision"]["profileRevisionId"].asLong()
        }
        revisions.values.forEach { engineer(stack, "/api/profile-revisions/$it/test-requests") }
        val deadline = Instant.now().plus(RUNNER_WAIT)
        while (!revisions.values.all { status(stack, it) == "TESTED" }) {
            check(Instant.now().isBefore(deadline)) { "실행기가 ${RUNNER_WAIT.seconds}초 안에 TESTED 로 올리지 않았다: ${stack.get("/api/profiles")}" }
            Thread.sleep(500)
        }
        revisions.values.forEach { engineer(stack, "/api/profile-revisions/$it/activation") }

        ROBOTS.forEach { (robotId, robot) ->
            val revision = revisions.getValue(robot.second)
            engineer(stack, "/api/robots/$robotId/binding", """{"adapterVersionId":$buildId,"profileRevisionId":$revision}""")
            engineer(stack, "/api/robots/$robotId/site-names")
        }
        stack.site.advance(REPORT)

        val robots = stack.get("/api/robots")["robots"]
        ROBOTS.keys.forEach { robotId ->
            val robot = robots.single { it["robot"]["robotId"].asText() == robotId }
            check(robot["commissioning"]["state"].asText() == "COMPLETE" && robot["connection"].asText() == "FRESH") {
                "$robotId 가 시운전 완료·연결 신선이 아니다: $robot"
            }
        }
        return Ids(buildId, revisions)
    }

    private fun document(model: String): String =
        Files.readString(E2eStack.root.resolve("picasso/profile/profiles/$model.json")).replace("\r\n", "\n")

    private fun status(stack: E2eStack, revisionId: Long): String =
        stack.get("/api/profiles")["revisions"].single { it["revision"]["profileRevisionId"].asLong() == revisionId }["revision"]["status"].asText()

    /** 엔지니어 모드로 부르고 registry 가 반영했는지(SUCCEEDED) 본다. 아니면 멈춘다. */
    private fun engineer(stack: E2eStack, path: String, body: String? = null): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        check(reply.status == 200 && reply.body!!["result"].asText() == "SUCCEEDED") { "$path → ${reply.status} ${reply.body}" }
        return reply.body!!
    }
}
```

`e2e/src/test/kotlin/dev/picasso/ops/e2e/JobOrderTest.kt`:

```kotlin
package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import dev.picasso.ops.e2e.Commissioned.QUADRUPED
import dev.picasso.ops.host.MissionHost
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * S3a 완료 판정의 통합 쪽(S3a 스펙 §3·§11). 두 기체를 시운전 완료로 만든 뒤 운영 서비스 REST 로 작업 지시를 낸다. 실행 호스트가
 * 미들웨어로 mimic 기체에서 실행하고, PrepareSequencedRack 은 현장 셀 대역이 채운 슬롯으로 E2 에 이른다.
 *
 * ## 시계
 *
 * 호스트 시계는 현장 시계다(`E2eStack`). 시험은 [push] 로 가상 시계를 밀 때마다 호스트의 마지막 pump 시각(`pumpedAt`)이 민 뒤의
 * 가상 시각 이상이 될 때까지 기다리고, 한 주기를 더 기다린 뒤 상태를 읽는다. mimic 스트림 갱신은 gRPC 스레드로 비동기로 와서
 * `pumpedAt` 만으로는 그 pump 가 방금 민 전이를 봤다는 보장이 없기 때문이다. E2 마감(`doneAt + 15s`)은 단위를 끝낸 밀기
 * 뒤의 가상 시각부터 센다. 그래서 확인 중(VERIFYING)인 단위가 보이면 시계를 더 밀지 않고 실제 시간으로만 기다린다([drive]).
 *
 * ## 실패 모드
 *
 * `pick_place` 의 실패 모드(GRASP_FAILED·PAYLOAD_LOST)는 기체별 시드로 추첨된다. 현장은 시드 0 이고, 이 순서(humanoid-01 이
 * InspectAsset 의 `navigate_to`·`inspect` 다음 PrepareSequencedRack 의 `pick_place` 둘)에서는 실패 모드가 나지 않는다. 순서를
 * 바꾸면 추첨이 달라질 수 있다.
 *
 * 순서가 있다. 앞 시험의 실행이 끝나야 뒤 시험의 기체가 도는 실행 없이 배정 가능하다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class JobOrderTest {

    companion object {
        private lateinit var stack: E2eStack

        private const val MATERIAL = "ENGINE-COVER-A"
        private const val PRESENTATION = "SEQ-IN-02.BIN-A"
        private val SLOTS = listOf("RACK-204.S01", "RACK-204.S02")

        private val INSPECT = """{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"}]}"""
        private val RACK = """{"workMasterId":"PrepareSequencedRack","slots":[${SLOTS.joinToString(",") { "\"$it\"" }}],""" +
            """"material":"$MATERIAL","presentation":"$PRESENTATION"}"""

        /** 한 번에 미는 가상 시간. E2 마감 15초보다 짧아 확인 전에 마감을 넘기지 않는다. */
        private val STEP: Duration = Duration.ofSeconds(5)

        /** 밀기 상한. 가장 긴 것이 `pick_place` 둘(45초 ±10%)이다. */
        private const val ROUNDS = 60

        /** 확인 중인 단위를 실제 시간으로 기다리는 상한. 셀 대역은 다음 pump(250ms) 에 읽힌다. */
        private val VERIFY_WAIT: Duration = Duration.ofSeconds(3)

        /** 정착한 물리 상태(S3a JSON 계약 §5). */
        private val SETTLED = setOf("PHYSICALLY_DONE", "UNVERIFIED", "FAILED", "ABORTED", "PARTIAL")

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
            Commissioned.complete(stack)
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }
    }

    @Test
    @Order(1)
    fun `PrepareSequencedRack 의 배정 가능 표에서 pick_place 가 없는 기체는 스킬 적합 하나로 불가다`() {
        val reply = stack.send("POST", "/api/job-orders/eligibility", mode = null, body = RACK)
        assertEquals(200, reply.status, "${reply.body}")
        val view = reply.body!!
        assertEquals("OK" to "OK", view["registry"].asText() to view["host"].asText(), "$view")
        val rows = view["robots"].associateBy { it["robotId"].asText() }
        assertEquals(setOf(HUMANOID, QUADRUPED), rows.keys)

        val humanoid = rows.getValue(HUMANOID)
        assertEquals(true, humanoid["eligible"].asBoolean(), "$humanoid")
        assertEquals("FIT", humanoid["host"]["skillFit"].asText())

        // 시운전·연결·도는 실행은 통과하고 스킬 적합 하나로만 빠진다.
        val quadruped = rows.getValue(QUADRUPED)
        assertEquals("COMPLETE" to "FRESH", quadruped["commissioning"].asText() to quadruped["connection"].asText(), "$quadruped")
        assertEquals("MISSING", quadruped["host"]["skillFit"].asText(), "$quadruped")
        assertEquals(listOf("pick_place"), quadruped["host"]["missingSkills"].map { it.asText() })
        assertTrue(quadruped["host"]["runningExecutionId"].isNull, "$quadruped")
        assertEquals(false, quadruped["eligible"].asBoolean())
        assertEquals(listOf("모자란 스킬: pick_place"), quadruped["reasons"].map { it.asText() })
    }

    @Test
    @Order(2)
    fun `InspectAsset 작업 지시가 배정되어 끝나고 작업 응답이 붙으며 임무 버전은 코드 정의다`() {
        val submitted = submit(INSPECT)
        val outcome = submitted["outcome"]
        assertEquals("SUCCEEDED" to "ACCEPTED", submitted["result"].asText() to outcome["result"].asText(), "$submitted")
        // 둘 다 배정 가능이고 도는 실행이 없어 비용이 같다. 같으면 기체 id 순이다(`AssignmentCost.rank`). 실패 모드 추첨이
        // 기체별이므로 어느 기체가 무엇을 도는지가 정해져 있어야 한다.
        assertEquals(HUMANOID, outcome["robotId"].asText(), "$outcome")

        val execution = drive(outcome["executionId"].asText())
        assertEquals(submitted["jobOrderId"].asText(), execution["jobOrderId"].asText())
        assertEquals("InspectAsset", execution["workMasterId"].asText())
        assertTrue(execution.has("missionVersion") && execution["missionVersion"].isNull, "$execution")
        assertEquals("PHYSICALLY_DONE", execution["physicalState"].asText(), "$execution")
        assertEquals(
            listOf(Triple("T1.travel", "navigate_to", "DONE"), Triple("T1", "inspect", "DONE")),
            execution["units"].map { Triple(it["unitId"].asText(), it["skillType"].asText(), it["state"].asText()) },
        )
        val response = assertNotNull(execution["jobResponse"].takeUnless { it.isNull }, "$execution")
        assertEquals("PHYSICALLY_DONE" to "E0", response["physicalState"].asText() to response["reachedEvidence"].asText())
        assertEquals(listOf("T1.travel", "T1"), response["completedUnits"].map { it.asText() })
    }

    @Test
    @Order(3)
    fun `PrepareSequencedRack 작업 지시가 pick_place 가 있는 기체에 배정되어 셀 대역 신호로 E2 에 이른다`() {
        val submitted = submit(RACK)
        val outcome = submitted["outcome"]
        assertEquals("SUCCEEDED" to "ACCEPTED", submitted["result"].asText() to outcome["result"].asText(), "$submitted")
        // 운영 서비스가 배정 불가 기체를 후보에서 이미 뺐으므로 호스트가 다시 뺀 기체도 없다.
        assertEquals(HUMANOID, outcome["robotId"].asText(), "$outcome")
        assertEquals(0, outcome["excluded"].size(), "$outcome")
        val logged = stack.get("/api/operations").first { it["target"].asText() == submitted["jobOrderId"].asText() }
        assertEquals(listOf(HUMANOID), ObjectMapper().readTree(logged["request"].asText())["candidates"].map { it.asText() }, "$logged")

        val execution = drive(outcome["executionId"].asText())
        assertEquals("PHYSICALLY_DONE", execution["physicalState"].asText(), "$execution")
        assertEquals(
            SLOTS.map { Triple(it, "DONE", "E2") },
            execution["units"].map { Triple(it["unitId"].asText(), it["state"].asText(), it["reached"].asText()) },
            "$execution",
        )
        assertEquals("E2", execution["jobResponse"]["reachedEvidence"].asText(), "$execution")

        val slots = stack.get("/api/cell")["cell"]["slots"].associateBy { it["id"].asText() }
        SLOTS.forEach { slot ->
            val place = slots.getValue(slot)
            assertEquals(true to MATERIAL, place["occupied"].asBoolean() to place["material"].asText(), "$place")
        }
    }

    /** 운영자 모드로 작업 지시를 낸다. 운영 서비스가 호스트에 제출했으면 200 이다. */
    private fun submit(form: String): JsonNode {
        val reply = stack.send("POST", "/api/job-orders", "operator", body = form)
        assertEquals(200, reply.status, "${reply.body}")
        return reply.body!!
    }

    private fun executions(): JsonNode = stack.get("/api/executions")

    private fun execution(executionId: String): JsonNode =
        checkNotNull(executions()["executions"].firstOrNull { it["executionId"].asText() == executionId }) { "실행이 없다: $executionId" }

    /** 가상 시계를 밀고, 호스트가 민 뒤의 시각에 pump 를 시작할 때까지 기다린 뒤 한 주기 더 기다린다. */
    private fun push(by: Duration) {
        stack.site.advance(by)
        val target = stack.site.now()
        val deadline = Instant.now().plusSeconds(5)
        while (true) {
            val at = executions()["pumpedAt"]
            if (!at.isNull && !Instant.parse(at.asText()).isBefore(target)) break
            check(Instant.now().isBefore(deadline)) { "호스트 pump 가 $target 에 이르지 않았다: $at" }
            Thread.sleep(50)
        }
        Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
    }

    /**
     * 실행의 물리 상태가 정착할 때까지 [STEP] 씩 민다. 민 뒤 확인 중(VERIFYING)인 단위가 있으면 더 밀지 않고 [VERIFY_WAIT] 동안
     * 실제 시간으로 기다린다. 그 안에 정착하지 않으면(셀 신호가 없음) 다시 밀어 마감으로 간다.
     */
    private fun drive(executionId: String): JsonNode {
        repeat(ROUNDS) {
            push(STEP)
            var seen = execution(executionId)
            val until = Instant.now().plus(VERIFY_WAIT)
            while (seen["units"].any { it["state"].asText() == "VERIFYING" } && Instant.now().isBefore(until)) {
                Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
                seen = execution(executionId)
            }
            if (seen["physicalState"].asText() in SETTLED) return seen
        }
        error("실행 $executionId 가 ${ROUNDS}번 밀어도 정착하지 않았다: ${execution(executionId)}")
    }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task6.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task6.patch"
```

```diff
diff --git a/e2e/build.gradle.kts b/e2e/build.gradle.kts
index 465635e..5f97a9c 100644
--- a/e2e/build.gradle.kts
+++ b/e2e/build.gradle.kts
@@ -1,6 +1,7 @@
-// 통합 시험만 있는 모듈. 한 JVM 에 Postgres·registry·mimic·운영 서비스를 함께 띄운다(스펙 §10).
+// 통합 시험만 있는 모듈. 한 JVM 에 Postgres·registry·mimic·실행 호스트·운영 서비스를 함께 띄운다(스펙 §10, S3a 스펙 §11).
 dependencies {
     testImplementation(project(":site"))
+    testImplementation(project(":mission-host"))
     testImplementation(project(":ops-service"))
     testImplementation(testFixtures("dev.picasso:registry"))
     testImplementation(platform(libs.spring.boot.bom))
diff --git a/e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt b/e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt
index 732222d..27c79c9 100644
--- a/e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt
+++ b/e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt
@@ -2,6 +2,8 @@ package dev.picasso.ops.e2e
 
 import com.fasterxml.jackson.databind.JsonNode
 import com.fasterxml.jackson.databind.ObjectMapper
+import dev.picasso.ops.host.HostClock
+import dev.picasso.ops.host.MissionHostApplication
 import dev.picasso.ops.service.OpsApplication
 import dev.picasso.ops.site.DbConfig
 import dev.picasso.ops.site.RobotRoster
@@ -17,14 +19,20 @@ import java.net.http.HttpResponse
 import java.nio.file.Path
 
 /**
- * 통합 시험 한 벌. 한 JVM 에 Postgres(registry testFixtures)·registry·mimic·운영 서비스를 띄운다(스펙 §10).
+ * 통합 시험 한 세트. 한 JVM 에 Postgres(registry testFixtures)·registry·mimic·실행 호스트·운영 서비스를 띄운다(스펙 §10,
+ * S3a 스펙 §11).
  *
  * 시험 클래스마다 새로 띄우고 닫는다. 클래스 사이에 registry 를 멈추는 시험이 있어 공유하지 않는다.
  * DB 는 띄울 때마다 비운다. `PostgresSupport.reset()` 은 public 만 지우므로 ops 스키마는 따로 지운다.
+ *
+ * 실행 호스트의 시계는 현장 시계(`Site.now()`)다. 시험은 `Site.advance` 로 가상 시각을 실제 시각보다 앞으로 밀고, 미들웨어는
+ * E2 시간 윈도우의 기준 시각을 mimic 응답 헤더에서 가져오면서 마감은 호스트 시계로 보므로 둘이 같아야 한다(S3a 스펙 §7.2).
  */
 class E2eStack private constructor(
     val site: Site,
+    private val host: ConfigurableApplicationContext,
     private val ops: ConfigurableApplicationContext,
+    val hostUrl: String,
     val opsUrl: String,
     val siteId: String,
 ) : AutoCloseable {
@@ -46,7 +54,7 @@ class E2eStack private constructor(
 
     /** 같은 registry 에 운영자 토큰만 다른 운영 서비스를 하나 더 띄운다. 닫는 것은 부르는 쪽이다. */
     fun opsWithToken(token: String): Pair<ConfigurableApplicationContext, String> {
-        val context = startOps(site.registryUrl, token, siteId)
+        val context = startOps(site.registryUrl, token, siteId, hostUrl)
         return context to "http://127.0.0.1:${(context as WebServerApplicationContext).webServer.port}"
     }
 
@@ -54,7 +62,11 @@ class E2eStack private constructor(
         try {
             ops.close()
         } finally {
-            site.close()
+            try {
+                host.close()
+            } finally {
+                site.close()
+            }
         }
     }
 
@@ -79,13 +91,22 @@ class E2eStack private constructor(
                     roster = RobotRoster.read(root.resolve(SiteConfig.ROSTER)),
                 ),
             )
+            // 실행 호스트는 Site 다음, 운영 서비스 앞이다. 운영 서비스가 기동에서 호스트 주소를 받는다.
+            val host = try {
+                startHost(site)
+            } catch (e: Exception) {
+                site.close()
+                throw e
+            }
+            val hostUrl = "http://127.0.0.1:${(host as WebServerApplicationContext).webServer.port}"
             val ops = try {
-                startOps(site.registryUrl, OPERATOR_TOKEN, siteId)
+                startOps(site.registryUrl, OPERATOR_TOKEN, siteId, hostUrl)
             } catch (e: Exception) {
+                host.close()
                 site.close()
                 throw e
             }
-            return E2eStack(site, ops, "http://127.0.0.1:${(ops as WebServerApplicationContext).webServer.port}", siteId)
+            return E2eStack(site, host, ops, hostUrl, "http://127.0.0.1:${(ops as WebServerApplicationContext).webServer.port}", siteId)
         }
 
         /** [baseUrl] 의 운영 서비스를 화면처럼 부른다. */
@@ -117,7 +138,7 @@ class E2eStack private constructor(
 
         private fun db() = DbConfig(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
 
-        private fun startOps(registryUrl: String, token: String, siteId: String): ConfigurableApplicationContext {
+        private fun startOps(registryUrl: String, token: String, siteId: String, hostUrl: String): ConfigurableApplicationContext {
             val db = db()
             return OpsApplication.builder().run(
                 "--server.port=0",
@@ -127,9 +148,18 @@ class E2eStack private constructor(
                 "--ops.registry.url=$registryUrl",
                 "--ops.registry.operator-token=$token",
                 "--ops.site-id=$siteId",
+                "--ops.host.url=$hostUrl",
             )
         }
 
+        /** 실행 호스트를 이 현장의 mimic gRPC 포트·셀 대역에 붙이고 현장 시계로 띄운다(S3a 계약 공통 규칙). */
+        private fun startHost(site: Site): ConfigurableApplicationContext =
+            MissionHostApplication.builder(HostClock { site.now() }).run(
+                "--server.port=0",
+                "--host.mimic.port=${site.mimicPort}",
+                "--host.cell.url=http://127.0.0.1:${site.cellPort}",
+            )
+
         private fun call(method: String, url: String, headers: Map<String, String>, body: String?): Reply {
             val builder = HttpRequest.newBuilder(URI.create(url))
                 .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && ./gradlew :e2e:test -q
```
Expected: e2e 40, 실패 0(`JobOrderTest` 3 포함, 그 클래스만 약 1분). 백그라운드로 돌린다. Docker 데몬이 떠 있어야 한다(Testcontainers).

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git add e2e/src/test/kotlin/dev/picasso/ops/e2e/Commissioned.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/JobOrderTest.kt e2e/build.gradle.kts e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt && git commit -F - <<'EOF'
test(e2e): 실행 호스트를 더한 통합 스택과 공용 시운전 픽스처, 작업 지시 통합 시험 추가

- E2eStack 이 Site 다음에 mission-host 를 같은 JVM 에 띄움
- `Commissioned.complete` 로 두 기체 시운전, `JobOrderTest` 3개

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 7: Playwright, CI, README

**Files:**
- Modify: `.github/workflows/ci.yml`, `README.md`, `ui/e2e/lifecycle.spec.ts`, `ui/e2e/run-dist.mjs`, `ui/playwright.config.ts`

- [ ] **Step 1: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task7.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s3a-patches/task7.patch"
```

````diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index 26954b6..5a6c7bf 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -64,7 +64,7 @@ jobs:
           java-version: '21'
       - uses: gradle/actions/setup-gradle@v4
       - name: Build distributions
-        run: ./gradlew :site:installDist :ops-service:installDist --console=plain
+        run: ./gradlew :site:installDist :mission-host:installDist :ops-service:installDist --console=plain
       - uses: actions/setup-node@v4
         with:
           node-version: '22'
diff --git a/README.md b/README.md
index 83dfbba..f426f4e 100644
--- a/README.md
+++ b/README.md
@@ -2,16 +2,17 @@
 
 picasso 를 라이브러리로 쓰는 담는 측 저장소입니다. 로봇, 임무, 엔드포인트의 운영 가능성을 PoC 로 입증합니다. 실제 하드웨어 현장은 없습니다. 보안과 인증은 생략합니다. 운영 중 변경은 코드 수정이 아니라 관리 화면에서 처리합니다.
 
-지금 단계는 S2 현장 설정입니다. S1d 바인딩·명칭·시운전 위에 올립니다. 서브모듈 `picasso` 는 P2b 머지 커밋 `41beedb` 를 가리킵니다. 그 버전에는 P2a 시험 실행기와 P2b 리비전·바인딩 REST 가 들어 있습니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 같은 영역 왼쪽에 프로파일 구역이 있습니다. 그 구역에서 엔지니어 모드는 기종 프로파일 리비전을 파일로 골라 제출하고, 시험을 요청하고, 활성화합니다. 시험 결과는 사람이 적지 않습니다. 런처가 띄운 시험 실행기가 적습니다. 기체 상세에서 엔지니어 모드는 빌드와 활성 리비전을 골라 바인딩하고, 사이트 명칭 등록을 기록합니다. 명칭 티칭은 화면 밖 현장 작업입니다. 기체 목록에 시운전 칸이 있습니다. 값은 완료, 미완, 퇴역입니다. 완료 조건은 셋입니다. 원장 상태가 `CONFIRMED` 이고 퇴역이 아니어야 합니다. 활성 바인딩이 있어야 합니다. 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 여야 합니다. 빠진 조건은 막힘으로 보입니다. 운영자 모드는 퇴역과 복귀를 합니다. 어댑터 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 화면은 배정 가능을 쓰지 않습니다. 그것은 S3 의 몫입니다. 현장·자원 영역에는 현장 설정 구역이 있습니다. 지금 버전, 연결 기준 시간, 허용 범위(60~3600초), 버전 이력을 보입니다. 엔지니어 모드는 연결 기준 시간을 바꿉니다. 바꿀 때는 사유가 필요합니다. 바꾸면 새 버전이 생깁니다. 다음 기체 목록 읽기부터 그 값으로 연결 칸과 막힘을 판정합니다. 재기동은 필요 없습니다. 운영자 모드는 바꾸지 못합니다. 막힘 카드는 어느 현장 설정 버전으로 판정했는지를 근거 버전으로 보입니다. 기체 상세에는 연결 판정 기준이 보입니다. 기준 초와 버전입니다. 미들웨어 시간값과 인시던트 기록의 버전 번호는 S3 의 몫입니다. S1 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. P2·S1d 설계 스펙은 `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` 에 있습니다. S2 설계 스펙은 `docs/superpowers/specs/2026-10-08-s2-site-settings-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.
+지금 단계는 S3a 실행 호스트와 작업 지시입니다. S2 현장 설정 위에 올립니다. 서브모듈 `picasso` 는 P4 머지 커밋 `1e3f4ae` 를 가리킵니다. 그 버전에는 P2a 시험 실행기, P2b 리비전·바인딩 REST, P3 임무 정의 버전, P4 스트림 재부착과 mimic 엔진 잠금이 들어 있습니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 같은 영역 왼쪽에 프로파일 구역이 있습니다. 그 구역에서 엔지니어 모드는 기종 프로파일 리비전을 파일로 골라 제출하고, 시험을 요청하고, 활성화합니다. 시험 결과는 사람이 적지 않습니다. 런처가 띄운 시험 실행기가 적습니다. 기체 상세에서 엔지니어 모드는 빌드와 활성 리비전을 골라 바인딩하고, 사이트 명칭 등록을 기록합니다. 명칭 티칭은 화면 밖 현장 작업입니다. 기체 목록에 시운전 칸이 있습니다. 값은 완료, 미완, 퇴역입니다. 완료 조건은 셋입니다. 원장 상태가 `CONFIRMED` 이고 퇴역이 아니어야 합니다. 활성 바인딩이 있어야 합니다. 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 여야 합니다. 빠진 조건은 막힘으로 보입니다. 운영자 모드는 퇴역과 복귀를 합니다. 어댑터 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 현장·자원 영역에는 현장 설정 구역이 있습니다. 지금 버전, 연결 기준 시간, 허용 범위(60~3600초), 버전 이력을 보입니다. 엔지니어 모드는 연결 기준 시간을 바꿉니다. 바꿀 때는 사유가 필요합니다. 바꾸면 새 버전이 생깁니다. 다음 기체 목록 읽기부터 그 값으로 연결 칸과 막힘을 판정합니다. 재기동은 필요 없습니다. 운영자 모드는 바꾸지 못합니다. 막힘 카드는 어느 현장 설정 버전으로 판정했는지를 근거 버전으로 보입니다. 기체 상세에는 연결 판정 기준이 보입니다. 기준 초와 버전입니다. 운영 영역에서 운영자 모드는 작업 지시를 냅니다. 임무는 코드 정의 임무 2개(`InspectAsset`, `PrepareSequencedRack`)입니다. `InspectAsset` 은 점검 대상의 id 와 장소 이름을 넣습니다. `PrepareSequencedRack` 은 셀 대역의 슬롯과 자재를 고릅니다. 제시 자리는 자재에서 정해집니다. 폼을 채우면 기체별 배정 가능 표가 보입니다. 배정 가능 조건은 넷입니다. 시운전 완료, 연결 신선, 도는 실행 없음, 스킬 적합입니다. 앞의 둘은 운영 서비스가, 뒤의 둘은 실행 호스트가 판정합니다. 못 물어본 칸은 모름이고 모름이 있으면 배정 불가입니다. 운영 서비스는 배정 가능한 기체만 후보로 실행 호스트에 넘깁니다. 실행 호스트의 picasso 미들웨어가 후보 중 하나를 골라 mimic 기체에서 실행합니다. 엔지니어 모드는 작업 지시를 내지 못합니다. 실행 목록은 실행 상태, 단위 상태와 근거 등급, 작업 응답, 임무 버전을 보입니다. 코드 정의 임무의 임무 버전은 `코드 정의` 로 보입니다. 셀 대역 표는 제시 자리와 슬롯의 점유와 자재를 보입니다. 임무 버전 저장과 편집은 S3b, 미들웨어 시간값과 인시던트 기록의 현장 설정 버전은 S3c 의 몫입니다. S1 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. P2·S1d 설계 스펙은 `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` 에 있습니다. S2 설계 스펙은 `docs/superpowers/specs/2026-10-08-s2-site-settings-design.md` 에 있습니다. S3a 설계 스펙은 `docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.
 
 ## 구성
 
-- `.env`: 사이트 id(`SITE_ID`), DB 접속값, 포트, 토큰을 둡니다. 로컬 PoC 값입니다. 런처, 운영 서비스, 시험, CI 가 이 파일 하나를 읽습니다.
+- `.env`: 사이트 id(`SITE_ID`), DB 접속값, 포트, 토큰을 둡니다. 로컬 PoC 값입니다. 런처, 실행 호스트, 운영 서비스, 시험, CI 가 이 파일 하나를 읽습니다.
 - `picasso/`: picasso git 서브모듈입니다. 고정 커밋을 가리킵니다. Gradle includeBuild 로 가져옵니다. 읽기 전용입니다. picasso 쪽 변경은 picasso 저장소의 PR 로 냅니다.
-- `site/`: 가짜 현장 런처입니다(Kotlin). registry, mimic 기체, 리비전 시험 실행기(`site-runner`)를 한 프로세스에서 띄웁니다. 실제 1초마다 가상 1초를 진행합니다. 실행기는 실제 1초마다 registry 에서 시험 요청을 집습니다. 실행기는 시험마다 자기 mimic 을 따로 띄웁니다. 그래서 현장 기체의 보고와 상태를 바꾸지 않습니다. 적재 토큰은 런처만 가집니다. 그래서 시험 결과를 적을 수 있는 것도 런처뿐입니다. 기체 명부는 `site/robots.json` 입니다. 기체 명부의 `site_names` 는 현장에서 기체에 티칭한 명칭입니다. 런처가 기동 때 기체에 넣습니다. Postgres 용 compose 는 `site/compose.yaml` 입니다. 손 기동 확인은 `site/smoke.sh` 입니다.
-- `ops-service/`: 운영 서비스입니다(Kotlin, Spring Boot). 화면의 유일한 백엔드입니다. 운영자 토큰을 쥡니다. registry REST 만 부릅니다.
+- `site/`: 가짜 현장 런처입니다(Kotlin). registry, mimic 기체, 리비전 시험 실행기(`site-runner`), 셀 대역을 한 프로세스에서 띄웁니다. 기동 직후 mimic 가상 시계를 실제 시각까지 한 번 밉니다. 그 뒤 실제 1초마다 가상 시계를 실제 시각까지 따라잡게 밉니다. 그래서 가상 시각은 실제 시각보다 앞서지 않고 차이는 1초 이내입니다. mimic gRPC 는 `.env` 의 `MIMIC_GRPC_PORT` 에 엽니다. 실행 호스트가 이 포트에 붙습니다. 셀 대역은 셀 신호를 자동으로 내는 대역입니다. 제시 자리 `SEQ-IN-02.BIN-A` 는 늘 `ENGINE-COVER-A` 를 듭니다. 슬롯 `RACK-204.S01`~`S04` 는 처음에 비어 있습니다. 기체가 `pick_place` 를 성공하면 그 슬롯을 제시 자리의 자재로 채웁니다. 슬롯은 비우지 않습니다. 기체가 보고한 배치에서 채우므로 독립 설비 확인이 아닙니다. 셀 대역은 `.env` 의 `SITE_CELL_PORT` 에 루프백 `GET /cell` 로 냅니다. 실행기는 실제 1초마다 registry 에서 시험 요청을 집습니다. 실행기는 시험마다 자기 mimic 을 따로 띄웁니다. 그래서 현장 기체의 보고와 상태를 바꾸지 않습니다. 적재 토큰은 런처만 가집니다. 그래서 시험 결과를 적을 수 있는 것도 런처뿐입니다. 기체 명부는 `site/robots.json` 입니다. 기체 명부의 `site_names` 는 현장에서 기체에 티칭한 명칭입니다. 런처가 기동 때 기체에 넣습니다. Postgres 용 compose 는 `site/compose.yaml` 입니다. 손 기동 확인은 `site/smoke.sh` 입니다.
+- `mission-host/`: 실행 호스트입니다(Kotlin, Spring Boot). picasso 미들웨어를 세워 mimic 기체에서 작업 지시를 실행합니다. 250ms 마다 셀 대역을 읽고 미들웨어를 pump 합니다. 메모리만 씁니다. 재기동하면 실행이 사라집니다. 토큰을 받지 않습니다. `.env` 의 `HOST_PORT` 에 루프백으로 엽니다. 호출자는 운영 서비스뿐입니다.
+- `ops-service/`: 운영 서비스입니다(Kotlin, Spring Boot). 화면의 유일한 백엔드입니다. 운영자 토큰을 쥡니다. registry REST 와 실행 호스트 REST 를 부릅니다. picasso 모듈을 쓰지 않습니다.
 - `ui/`: 관리 화면입니다(React, Vite, TypeScript). 운영 서비스만 부릅니다.
-- `e2e/`: 통합 시험입니다. 한 JVM 에 Postgres, registry, mimic, 운영 서비스를 띄웁니다.
+- `e2e/`: 통합 시험입니다. 한 JVM 에 Postgres, registry, mimic, 실행 호스트, 운영 서비스를 띄웁니다. 실행 호스트의 시계는 현장 가상 시계이고 시험이 시계를 직접 밉니다.
 - `docs/`: 설계 스펙과 구현 계획입니다.
 
 ## 선행 도구
@@ -59,7 +60,7 @@ cd ui && npm ci && npm test
 화면 시험(Playwright)은 따로 돌립니다. 먼저 아래 2개를 한 번 합니다.
 
 ```bash
-./gradlew :site:installDist :ops-service:installDist
+./gradlew :site:installDist :mission-host:installDist :ops-service:installDist
 ```
 
 ```bash
@@ -72,7 +73,7 @@ cd ui && npx playwright install chromium
 cd ui && npx playwright test
 ```
 
-Playwright 가 Postgres, 런처, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 이어서 리비전 제출, 시험 요청, 실행기의 `TESTED` 기록, 활성화, 바인딩, 명칭 기록, `humanoid-01` 시운전 완료까지 돕니다. `quadruped-01` 의 명칭 기록이 기체가 아는 명칭 없음으로 막히는 것도 봅니다. 현장·자원 영역에서 연결 기준 시간을 120초로 바꿔 버전 2 와 이력 행을 봅니다. 운영자 모드에서는 바꾸지 못하는 것과 기체 상세의 연결 판정 기준이 버전 2 로 바뀐 것도 봅니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 1초에 가상 1초가 갑니다. 시험은 약 2분 걸립니다. 로컬 실측은 1.9분입니다. Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.
+Playwright 가 Postgres, 런처, 실행 호스트, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 이어서 리비전 제출, 시험 요청, 실행기의 `TESTED` 기록, 활성화, 바인딩, 명칭 기록, `humanoid-01` 시운전 완료까지 돕니다. `quadruped-01` 의 명칭 기록이 기체가 아는 명칭 없음으로 막히는 것도 봅니다. 현장·자원 영역에서 연결 기준 시간을 120초로 바꿔 버전 2 와 이력 행을 봅니다. 운영자 모드에서는 바꾸지 못하는 것과 기체 상세의 연결 판정 기준이 버전 2 로 바뀐 것도 봅니다. 운영 영역에서 운영자 모드로 `InspectAsset` 작업 지시를 냅니다. 시운전 완료인 `humanoid-01` 만 배정 가능이고 배정됩니다. 실행 목록에 `코드 정의` 행이 보이고 실행이 `PHYSICALLY_DONE` 으로 끝납니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 시각을 따라갑니다. 시험은 약 3분 걸립니다. 로컬 실측은 3.1분입니다. Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.
 
 ## 띄우기
 
@@ -89,10 +90,10 @@ docker compose -f site/compose.yaml --env-file .env up -d --wait
 ```
 
 ```bash
-./gradlew :site:installDist :ops-service:installDist
+./gradlew :site:installDist :mission-host:installDist :ops-service:installDist
 ```
 
-그다음 터미널 3개를 엽니다. 각 터미널에서 먼저 `.env` 를 읽습니다.
+그다음 터미널 4개를 엽니다. 각 터미널에서 먼저 `.env` 를 읽습니다.
 
 ```bash
 set -a; . ./.env; set +a
@@ -104,13 +105,19 @@ set -a; . ./.env; set +a
 site/build/install/site/bin/site
 ```
 
-터미널 2에서 운영 서비스를 띄웁니다.
+터미널 2에서 실행 호스트를 띄웁니다.
+
+```bash
+env -u PICASSO_INGEST_TOKEN -u PICASSO_OPERATOR_TOKEN mission-host/build/install/mission-host/bin/mission-host
+```
+
+터미널 3에서 운영 서비스를 띄웁니다.
 
 ```bash
 env -u PICASSO_INGEST_TOKEN ops-service/build/install/ops-service/bin/ops-service
 ```
 
-터미널 3에서 화면을 띄웁니다.
+터미널 4에서 화면을 띄웁니다.
 
 ```bash
 cd ui && npm ci && npm run dev
@@ -121,7 +128,10 @@ Gradle 의 run 작업 2개를 한 작업 트리에서 겹쳐 띄우지 않습니
 ## 알아 둘 것
 
 - 토큰은 2개입니다. 적재 토큰은 site 만 받습니다. 운영 서비스는 운영자 토큰만 받습니다. 어느 토큰도 브라우저로 가지 않습니다.
-- registry 와 운영 서비스는 `127.0.0.1` 에만 엽니다.
+- registry, 실행 호스트, 운영 서비스, 셀 대역은 `127.0.0.1` 에만 엽니다. mimic gRPC 는 picasso 가 주소를 정하므로 모든 인터페이스에 열립니다. 인증 없는 기체 제어 API 표면이고 포트가 `.env` 로 고정됩니다.
+- 실행 호스트 REST 는 인증이 없습니다. 같은 기계의 다른 프로세스는 운영자 모드 검사 없이 작업 지시를 낼 수 있습니다.
+- `pick_place` 에는 실패 모드가 있습니다(단위 하나에 약 6%). 기체별 시드로 추첨하므로 런처로 PrepareSequencedRack 을 돌리면 슬롯 넷짜리 작업 지시는 약 22% 가 어딘가에서 실패하고, 실패는 실행 목록에 보입니다. 화면 시험은 실패 모드가 없는 InspectAsset 만 돌립니다.
+- 운영자 보류를 푸는 수단이 아직 없습니다. 보류에 선 실행의 기체는 실행 호스트를 재기동할 때까지 배정 불가입니다.
 - 선언 전 mimic 의 생존 보고는 registry 가 거절합니다. 거절한 보고는 어디에도 남지 않습니다. 기체를 선언하면 보고가 붙습니다.
 - registry 가 답하지 않으면 화면은 목록을 비우지 않습니다. 직전 값과 함께 모름을 보입니다.
 - 손으로 띄운 compose 스택이 있으면 화면 시험이 시작할 때 그것을 볼륨째 내립니다. 같은 compose 프로젝트라서입니다. 데이터가 지워집니다. 남은 컨테이너는 `docker compose -f site/compose.yaml --env-file .env down -v` 로 걷습니다.
diff --git a/ui/e2e/lifecycle.spec.ts b/ui/e2e/lifecycle.spec.ts
index 6c5b669..eeb9526 100644
--- a/ui/e2e/lifecycle.spec.ts
+++ b/ui/e2e/lifecycle.spec.ts
@@ -12,6 +12,8 @@ import { fileURLToPath } from 'node:url'
  * 바인딩 → 명칭 기록 → «시운전 완료»(humanoid-01). quadruped-01 은 명칭을 티칭하지 않아 «기체가 아는 명칭 없음» 으로 막힌다.
  * 이어서 S2 의 화면 쪽(S2 스펙 §3). 현장·자원 영역에서 연결 기준 시간을 바꾸면 버전 2 와 이력 행이 보이고, 운영자 모드는
  * 바꾸지 못하며, 기체 상세가 버전 2 의 기준으로 판정한다.
+ * 이어서 S3a 의 화면 쪽(S3a 스펙 §3). 운영자 모드로 «운영» 영역에서 InspectAsset 작업 지시를 내면 시운전을 마친 humanoid-01 에
+ * 배정되고 실행 목록에 «코드 정의» 행이 보인다. 실행 호스트는 실제 시각을 쓰고 런처가 가상 시계를 실제 시각까지 따라잡게 민다.
  * registry 를 멈추는 것은 맨 끝이다. 그 뒤로는 조작이 registry 에 닿지 않는다.
  *
  * 선언 직후의 CLAIMED 는 여기서 단언하지 않는다. 실시간 1:1 시계에서는 다음 보고가 1초 안에 올 수도 있어
@@ -144,6 +146,28 @@ test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 reg
   await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
   await expect(detail.getByText('기준 120초, 현장 설정 버전 2', { exact: true })).toBeVisible()
 
+  // 운영(S3a 스펙 §3). 시운전 완료는 humanoid-01 하나다. quadruped-01 은 명칭 막힘으로 시운전 미완이라 배정 불가다.
+  await page.getByLabel('운영자').check()
+  await page.getByRole('button', { name: '운영', exact: true }).click()
+  const order = page.getByRole('form', { name: '작업 지시 폼' })
+  await order.getByLabel('임무').selectOption('InspectAsset')
+  await order.getByLabel('대상 1 id').fill('T1')
+  await order.getByLabel('대상 1 장소').fill('bay-7')
+  const eligibility = page.getByRole('table', { name: '기체별 배정 가능' })
+  await expect(eligibility.getByRole('row', { name: /^humanoid-01 / }).getByRole('cell').nth(5)).toHaveText('가능')
+  const quadrupedRow = eligibility.getByRole('row', { name: /^quadruped-01 / })
+  await expect(quadrupedRow.getByRole('cell').nth(5)).toHaveText('불가')
+  await expect(quadrupedRow).toContainText('시운전이 끝나지 않았다')
+  await order.getByRole('button', { name: '작업 지시 내기' }).click()
+  const notice = page.getByRole('status', { name: '제출 결과' })
+  await expect(notice).toContainText('InspectAsset 작업 지시: 배정됨')
+  await expect(notice.locator('dt', { hasText: '배정된 기체' }).locator('xpath=following-sibling::dd[1]')).toHaveText('humanoid-01')
+  const executions = page.getByRole('table', { name: '실행 목록' })
+  const run = executions.getByRole('row').filter({ hasText: 'InspectAsset' })
+  await expect(run).toContainText('코드 정의')
+  // 이동 20초와 점검 12초(±10%)를 실제 시간으로 돈다.
+  await expect(run).toContainText('PHYSICALLY_DONE', { timeout: 90_000 })
+
   // registry 를 멈춘다. 런처(registry 와 mimic 이 든 프로세스)를 끈다.
   const pidFile = fileURLToPath(new URL('../../build/site.pid', import.meta.url))
   process.kill(Number(readFileSync(pidFile, 'utf8')))
diff --git a/ui/e2e/run-dist.mjs b/ui/e2e/run-dist.mjs
index 536c7fd..fb63f29 100644
--- a/ui/e2e/run-dist.mjs
+++ b/ui/e2e/run-dist.mjs
@@ -1,5 +1,6 @@
-// 배포본 하나(site 또는 ops-service)를 루트 .env 를 환경 변수로 넣어 띄운다. Playwright 의 webServer 가 부르고,
-// 끝낼 때 프로세스 트리째 끈다. 배포본이 먼저 있어야 한다: ./gradlew :site:installDist :ops-service:installDist
+// 배포본 하나(site, mission-host 또는 ops-service)를 루트 .env 를 환경 변수로 넣어 띄운다. Playwright 의 webServer 가
+// 부르고, 끝낼 때 프로세스 트리째 끈다. 배포본이 먼저 있어야 한다:
+// ./gradlew :site:installDist :mission-host:installDist :ops-service:installDist
 //
 // 시작 스크립트(bin/)를 거치지 않고 java 를 바로 띄운다. Windows 의 .bat 은 클래스패스를 한 줄로 펼쳐 cmd 의
 // 줄 길이 한도를 넘는다(실측). 클래스패스 와일드카드(lib/*)는 그 한도에 걸리지 않고 셸도 필요 없다.
@@ -14,12 +15,13 @@ import { fileURLToPath } from 'node:url'
 // 각 모듈 build.gradle.kts 의 application.mainClass 와 같다. 어긋나면 기동이 바로 실패한다.
 const MAIN = {
   site: 'dev.picasso.ops.site.SiteLauncherKt',
+  'mission-host': 'dev.picasso.ops.host.MissionHostApplicationKt',
   'ops-service': 'dev.picasso.ops.service.OpsApplicationKt',
 }
 
 const root = fileURLToPath(new URL('../..', import.meta.url))
 const name = process.argv[2]
-if (!(name in MAIN)) throw new Error(`site 또는 ops-service 만 띄운다: ${name}`)
+if (!(name in MAIN)) throw new Error(`site, mission-host, ops-service 만 띄운다: ${name}`)
 
 const env = { ...process.env }
 for (const raw of readFileSync(path.join(root, '.env'), 'utf8').split(/\r?\n/)) {
@@ -29,8 +31,10 @@ for (const raw of readFileSync(path.join(root, '.env'), 'utf8').split(/\r?\n/))
   if (at < 0) throw new Error(`.env 줄에 '=' 가 없다: ${line}`)
   env[line.slice(0, at).trim()] = line.slice(at + 1).trim()
 }
-// 적재 토큰은 site 만 쥔다(스펙 §4). 셸에서 상속된 값도 지운다.
+// 적재 토큰은 site 만 쥔다(스펙 §4). 실행 호스트는 registry 를 부르지 않아 운영자 토큰도 받지 않는다(S3a 스펙 §7.1).
+// 셸에서 상속된 값도 지운다.
 if (name !== 'site') delete env.PICASSO_INGEST_TOKEN
+if (name === 'mission-host') delete env.PICASSO_OPERATOR_TOKEN
 
 const lib = path.join(root, name, 'build', 'install', name, 'lib')
 if (!existsSync(lib)) throw new Error(`배포본이 없다: ${lib} (installDist 먼저)`)
diff --git a/ui/playwright.config.ts b/ui/playwright.config.ts
index 22cb728..66a5289 100644
--- a/ui/playwright.config.ts
+++ b/ui/playwright.config.ts
@@ -3,23 +3,26 @@ import { fileURLToPath } from 'node:url'
 import { loadEnv } from 'vite'
 
 // 포트는 루트 .env 하나에서 읽는다(스펙 §4·§6). 작업 디렉터리와 상관없이 이 파일 기준으로 찾는다.
-const env = loadEnv('', fileURLToPath(new URL('..', import.meta.url)), ['OPS_', 'REGISTRY_'])
-if (!env.OPS_PORT || !env.REGISTRY_PORT) throw new Error('루트 .env 에 OPS_PORT·REGISTRY_PORT 가 없다')
+const env = loadEnv('', fileURLToPath(new URL('..', import.meta.url)), ['OPS_', 'REGISTRY_', 'HOST_'])
+if (!env.OPS_PORT || !env.REGISTRY_PORT || !env.HOST_PORT) {
+  throw new Error('루트 .env 에 OPS_PORT·REGISTRY_PORT·HOST_PORT 가 없다')
+}
 
 /** Linux 에서는 SIGTERM 으로 끝내 각 스크립트가 정리하게 한다. Windows 는 신호 없이 트리째 끈다. */
 const gracefulShutdown = { signal: 'SIGTERM' as const, timeout: 15_000 }
 
 /**
- * 실행 중인 전체 스택 앞에서 생애주기를 화면으로 한 번 돌린다(스펙 §10). 런처는 실제 1초에 가상 1초를 밀므로
- * 보고를 기다리는 단계마다 최대 60초를 기다린다.
+ * 실행 중인 전체 스택 앞에서 생애주기를 화면으로 한 번 돌린다(스펙 §10). 런처는 1초마다 가상 시계를 실제 시각까지
+ * 따라잡게 밀므로 보고를 기다리는 단계마다 최대 60초를 기다린다.
  *
- * 스택은 Postgres, 런처, 운영 서비스, 화면 순으로 띄우고 각각 준비 확인을 거친다(스펙 §10). 프로세스마다
- * webServer 항목을 따로 두어, 끝날 때 Playwright 가 프로세스 트리째 끈다. 배포본은 먼저 만들어 둔다:
- * `./gradlew :site:installDist :ops-service:installDist`
+ * 스택은 Postgres, 런처, 실행 호스트, 운영 서비스, 화면 순으로 띄우고 각각 준비 확인을 거친다(스펙 §10, S3a 스펙 §11).
+ * 실행 호스트는 런처가 연 mimic gRPC 포트와 셀 대역에 붙으므로 런처 다음이다. 프로세스마다 webServer 항목을 따로 두어,
+ * 끝날 때 Playwright 가 프로세스 트리째 끈다. 배포본은 먼저 만들어 둔다:
+ * `./gradlew :site:installDist :mission-host:installDist :ops-service:installDist`
  */
 export default defineConfig({
   testDir: './e2e',
-  timeout: 300_000,
+  timeout: 420_000,
   expect: { timeout: 60_000 },
   workers: 1,
   forbidOnly: !!process.env.CI,
@@ -43,6 +46,14 @@ export default defineConfig({
       reuseExistingServer: false,
       gracefulShutdown,
     },
+    {
+      command: 'node e2e/run-dist.mjs mission-host',
+      url: `http://127.0.0.1:${env.HOST_PORT}/host/executions`,
+      stdout: 'pipe',
+      timeout: 120_000,
+      reuseExistingServer: false,
+      gracefulShutdown,
+    },
     {
       command: 'node e2e/run-dist.mjs ops-service',
       url: `http://127.0.0.1:${env.OPS_PORT}/api/robots`,
````

- [ ] **Step 2: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && ./gradlew :site:installDist :ops-service:installDist :mission-host:installDist -q && cd ui && npx playwright test
```
Expected: Playwright 1 통과(약 3분). 백그라운드로 돌린다.

- [ ] **Step 3: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" && git add .github/workflows/ci.yml README.md ui/e2e/lifecycle.spec.ts ui/e2e/run-dist.mjs ui/playwright.config.ts && git commit -F - <<'EOF'
test(ui): Playwright 스택에 실행 호스트와 운영 영역 작업 지시 단계 추가, CI·README 갱신

- webServer 에 mission-host, lifecycle 의 운영 영역 단계
- CI installDist 에 mission-host, README 의 모듈·셀 대역·운영 영역

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s3a-cmp.sh" e2e/src/test/kotlin/dev/picasso/ops/e2e/Commissioned.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/JobOrderTest.kt e2e/build.gradle.kts e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt .github/workflows/ci.yml README.md ui/e2e/lifecycle.spec.ts ui/e2e/run-dist.mjs ui/playwright.config.ts
```
Expected: 9개 모두 `같음`.

## Chunk 2: 검증과 병합(컨트롤러)

### Task 8: 결함 주입, 새 클론 빌드, PR

- [ ] **Step 1: 트리 대조**

```bash
git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" diff --stat origin/main HEAD -- . ':!picasso' ':!docs' | tail -1 && git -C "C:/Users/Eisen/AppData/Local/Temp/s3a" diff --stat f7ca937 HEAD -- . ':!picasso' | tail -1
```
Expected: 두 줄의 파일 수와 줄 수가 같다. 그리고 워크트리 `HEAD` 의 코드 경로마다 스파이크 `HEAD` 와 `s3a-cmp.sh` 로 `같음`.

- [ ] **Step 2: 결함 주입 28건**

```bash
cd "C:/Users/Eisen/AppData/Local/Temp/s3a-inject" && S3A_ROOT="C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s3a" PYTHONUTF8=1 python inject.py
```
Expected: 모두 `탐지`. 백그라운드로 돌린다(1~2시간). 같은 워크트리에서 다른 Gradle·npm 을 겹쳐 돌리지 않는다.

| id | 주입 | 잡는 시험 |
|---|---|---|
| S1 | 셀 관측 시각을 EPOCH 로 | `SiteCellTest` 의 관측 시각 시험 |
| S2 | 이미 본 태스크 재처리 | 같은 태스크는 한 번만 채운다 |
| S3 | `advanceTo` 의 앞으로만 검사 제거 | 기동 직후 가상 시각이 실제 시각까지 와 있고 advanceTo 는 앞으로만 민다 |
| S4 | 기동 직후 실제 시각 맞춤 제거 | 같은 시험 |
| H1 | 스킬 적합 반전 | 스킬이 모자란 기체와 케이퍼빌리티를 못 물은 기체를 가르고 이유를 낸다 |
| H2 | 도는 실행 무시 | 도는 실행이 있는 기체는 판정과 제출에서 빠진다 |
| H3 | IDEMPOTENT 를 ACCEPTED 로 | 끝난 InspectAsset 를 같은 id 로 다시 내면 IDEMPOTENT |
| H4 | 임무 버전 null 을 0 으로 | 제출은 판정을 다시 해 통과한 기체만 assign 에 넘긴다 |
| H5 | `holding` 이 슬롯도 냄 | holding 은 그 자재를 든 제시 자리만 낸다 |
| O1 | registry 불통을 신선으로 | registry 가 답하지 않으면 … 배정 가능이 아니다 |
| O2 | 운영자 모드 검사 제거 | 엔지니어 모드의 작업 지시 제출은 403 |
| O3 | result 매핑 뒤바꾸기 | 호스트 결과 넷을 조작 기록 결과로 옮긴다 |
| O4 | 후보 없음 검사 제거 | 배정 가능한 기체가 없으면 호스트를 부르지 않고 기록하지 않는다 |
| O5 | 호스트 불통을 적합으로 | 호스트가 답하지 않으면 호스트 칸이 모름 |
| O6 | 5xx 를 거부로 | 5xx 는 응답 없음과 같이 다룬다 |
| O7 | `.travel` 겹침 검사 제거 | 대상 id 가 다른 대상의 이동 단위 id 와 같으면 UNIT_ID_CONFLICT |
| O8 | 후보 거르개 제거 | 제출은 배정 가능한 기체만 후보로 넘긴다 |
| O9 | 재조회 대조 제거 | 5xx … 반영 안 됨을 붙인다 |
| O10 | IDEMPOTENT 와 UNASSIGNED 만 뒤바꾸기 | 호스트 결과 넷을 조작 기록 결과로 옮긴다 |
| O11 | 대상 id 길이 비교를 한 자 늘림 | 대상 id 가 … 64자를 넘으면 JOB_ORDER_BAD_REQUEST |
| U1 | 임무 버전 null 을 숫자로 | 실행 목록은 … 임무 버전 null 은 코드 정의로 보인다 |
| U2 | 엔지니어 모드에 제출 버튼 | 엔지니어 모드에서는 제출 대신 … |
| U3 | 실행 목록 읽기를 `Promise.all` 안으로 | 운영 영역을 열기 전에는 실행 호스트를 읽지 않고 … |
| U4 | 연결 «모름» 을 «신선» 으로 | 배정 가능 표는 못 물어본 칸을 모름으로 보이고 배정 불가로 둔다 |
| U5 | 셀 null 을 빈 자리로 | 셀 대역을 표로 보이고 … 모름 |
| U6 | 디바운스 제거 | 폼을 바꾸면 키 입력마다 묻지 않고 … 한 번 묻는다 |
| I1 | 셀 대역 채우기 끄기 | PrepareSequencedRack 작업 지시가 … E2 에 이른다 |
| I2 | 호스트 스킬 적합을 늘 적합으로 | PrepareSequencedRack 의 배정 가능 표에서 pick_place 가 없는 기체는 스킬 적합 하나로 불가다 |

- [ ] **Step 3: 새 클론 전체 빌드와 Playwright**

워크트리 브랜치를 스크래치에 새로 클론해(서브모듈 포함) `./gradlew build`, ui 의 `npm ci && npm test && npm run lint && npx tsc -b && npm run build`, installDist 셋과 `npx playwright test` 를 백그라운드로 차례로 돌린다. Expected: site 25, mission-host 14, ops-service 160, e2e 40, vitest 74, Playwright 1, 실패 0.

- [ ] **Step 4: 실행 결과와 PR**

P4 계획과 이 계획 끝에 «실행 결과» 절을 더한다(문장은 Codex·Fable 취합). 코드 커밋은 묶음별로 남긴다(스택 보존, 스쿼시 안 함). 푸시, PR, CI 를 한 번 확인한 뒤 `gh pr merge --merge`(`--delete-branch` 쓰지 않음).

## 실행 결과

- 수행: picasso-ops 워크트리에서 묶음 넷(Task 1·2, Task 3·4, Task 5, Task 6·7)을 하위 에이전트(Sonnet)가 수행, 블록은 계획에서 기계로 뽑아 둔 파일을 복사·적용, 묶음마다 커밋된 파일을 스파이크와 바이트 대조해 68개 모두 같음(22, 22, 15, 9), 워크트리와 스파이크의 코드 diff 가 같음(68개 파일, +5,326/-96), Expected 와 다른 곳 없음, 묶음 2 구현자가 Task 4 파일을 먼저 복사했다가 Task 3 시험·커밋 전에 치우고 다시 복사함(Task 3 커밋은 계획대로 9개 파일)
- 결함 주입: 28건(site 4, 호스트 5, 운영 서비스 11, 화면 6, 통합 2) 모두 지정 시험이 탐지, 스파이크와 워크트리에서 두 번 실행
- 새 클론 빌드: Gradle 시험 239 실패 0(site 25, mission-host 14, ops-service 160, e2e 40), vitest 74, lint·tsc·build 통과, installDist 셋과 Playwright 1 통과(3.1분)
- 병합: 코드 커밋을 묶음별로 남겨(스택 보존, 스쿼시 없음) picasso-ops PR #10 으로 올림
- 스파이크: 영역 넷(site·mission-host, 운영 서비스, 화면, 통합 시험·Playwright·CI·README)을 하위 에이전트가 차례로 지음, JSON 계약 문서를 다음 영역의 입력으로 넘김, 계획 검토(1회)가 잡은 것은 기준 브랜치의 문서 커밋 누락, Postgres 전제(실제는 Testcontainers), 저장소에 없는 계약 문서 참조, 스펙과 계획의 어긋남 둘, 재조회와 제출의 잠금 경합(공정 잠금으로 고침), 기동 실패 정리, 용어 다섯 곳
- 걸린 것: 스파이크 첫 영역 뒤 스펙 §11 의 기다리는 조건 부족(`pumpedAt` 뒤 한 주기 더), 기동 정리로 `Site.kt` 가 바뀌어 결함 주입 하나의 바늘을 고침, 결과 주입 실행기의 개행 처리, 새 클론 빌드와 결함 주입을 겹쳐 돌리다 멈춤(시간에 민감한 시험)
- 다음: S3b(임무 버전 저장·JSON 편집기·검증·모의 실행·활성화·도는 중 전환, 이름 있는 신호를 셀 대역에), 그 뒤 S3c·S4
