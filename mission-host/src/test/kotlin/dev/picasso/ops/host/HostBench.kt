package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.mimic.cli.MimicCli
import dev.picasso.ops.host.timings.SiteTimingsView
import dev.picasso.registry.PostgresSupport
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 호스트 시험 세트. 이 JVM 의 Netty 포트에 mimic(humanoid-01·quadruped-01, 가상 시계, registry 없음)을 띄우고, 고정 본문을
 * 내는 셀 대역 대역(stub)과 실행 호스트를 띄운다. 호스트 시계는 mimic 의 가상 시계다(통합 시험이 현장 시계를 넣는 것과 같은 배선).
 *
 * 셀 대역은 현장 모듈에 의존하지 않고 같은 본문 모양을 직접 낸다. 현장 쪽 모양은 현장 시험이 본다.
 *
 * 임무 버전 저장은 registry 시험 픽스처의 Postgres 다(S3b 스펙 §6.1). 띄울 때마다 `mission` 스키마를 지운다. 덧붙이기 전용
 * 트리거가 DELETE·TRUNCATE 를 막으므로 스키마째 지운다. [restartHost] 는 DB 를 그대로 두고 호스트만 다시 띄운다.
 *
 * 현장 시간값 뷰(S3c 스펙 §7.1)는 운영 서비스의 마이그레이션이 만들지만 이 세트에는 운영 서비스가 없다. 그래서 띄울 때마다 ops
 * 스키마를 지우고, 호스트 main 의 뷰 상수([SiteTimingsView])로 `VALUES` 한 행의 대역 뷰를 만든다([timingsView]). 실제 뷰와 칸이
 * 같은지는 통합 시험이 대조한다.
 *
 * @param mockVirtualLimit 주면 모의 실행의 가상 시간 상한을 이것으로 덮는다.
 * @param readInterval 주면 현장 시간값 읽기 주기를 이것으로 덮는다. 길게 주면 기동 안의 첫 읽기만 일어난다.
 * @param timings 대역 뷰의 한 행(버전, 앞 폭, 뒤 폭, inDoubtGrace, stallWindow). `null` 이면 뷰를 만들지 않아 호스트가 미적용으로 뜬다.
 */
class HostBench(
    private val mockVirtualLimit: Duration? = null,
    private val readInterval: Duration? = null,
    timings: List<Long>? = STANDARD_TIMINGS,
) : AutoCloseable {

    init {
        PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
        PostgresSupport.execute("DROP SCHEMA IF EXISTS ops CASCADE")
        timings?.let(::timingsView)
    }

    /** 대역 뷰를 [row] 한 행으로 다시 만든다. 호스트는 다음 읽기(1초 주기)에서 그 행을 읽는다. */
    fun timingsView(row: List<Long>) {
        require(row.size == SiteTimingsView.COLUMNS.size) { "칸이 ${SiteTimingsView.COLUMNS.size} 개여야 한다: $row" }
        PostgresSupport.execute("CREATE SCHEMA IF NOT EXISTS ${SiteTimingsView.NAME.substringBefore('.')}")
        dropTimingsView()
        val values = SiteTimingsView.COLUMNS.zip(row).joinToString(", ") { (column, value) -> "$value::${column.type}" }
        PostgresSupport.execute(
            "CREATE VIEW ${SiteTimingsView.NAME} (${SiteTimingsView.COLUMNS.joinToString(", ") { it.name }}) AS VALUES ($values)",
        )
    }

    /** 대역 뷰를 지운다. 호스트의 다음 읽기가 실패한다. */
    fun dropTimingsView() = PostgresSupport.execute("DROP VIEW IF EXISTS ${SiteTimingsView.NAME}")

    /** `GET /host/site-timings` 가 [done] 을 만족할 때까지 기다린다(실제 시간 상한 5초). 만족한 본문을 돌려준다. */
    fun awaitTimings(done: (JsonNode) -> Boolean): JsonNode {
        val deadline = Instant.now().plusSeconds(5)
        while (true) {
            val view = get("/host/site-timings")
            if (done(view)) return view
            check(Instant.now().isBefore(deadline)) { "5초 안에 현장 시간값 상태가 바뀌지 않았다: $view" }
            Thread.sleep(100)
        }
    }

    /** 호스트가 버전 [version] 을 적용할 때까지 기다린다. */
    fun awaitApplied(version: Long): JsonNode = awaitTimings { it["applied"]?.get("version")?.asLong() == version }

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

    /** 셀 대역 대역이 신호 쓰기에 답할 상태 코드와 본문. */
    @Volatile
    var signalReply: Pair<Int, String> = 200 to """{"name":"rack_present","value":"true"}"""

    /** 셀 대역 대역이 받은 신호 쓰기(경로의 이름, Content-Type, 본문). */
    val signalWrites = CopyOnWriteArrayList<Triple<String, String?, String>>()

    private val cell: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/cell") { exchange ->
            val path = exchange.requestURI.path
            val (status, body) = if (path.startsWith("/cell/signals/") && exchange.requestMethod == "POST") {
                signalWrites += Triple(
                    path.removePrefix("/cell/signals/"),
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestBody.readAllBytes().toString(Charsets.UTF_8),
                )
                signalReply
            } else {
                200 to cellBody
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        start()
    }

    private var context: ConfigurableApplicationContext = startHost()

    val url: String get() = "http://127.0.0.1:${(context as WebServerApplicationContext).webServer.port}"

    /** 지금 뜬 호스트의 빈. */
    val host: MissionHost get() = context.getBean(MissionHost::class.java)

    /** 이 세트와 같은 인자로 호스트를 하나 띄운다. 기동이 실패하면 예외가 그대로 나간다. */
    fun startHost(): ConfigurableApplicationContext = MissionHostApplication.builder(HostClock { now() }).run(
        *buildList {
            add("--server.port=0")
            add("--host.mimic.port=${mimic.server.port}")
            add("--host.cell.url=http://127.0.0.1:${cell.address.port}")
            add("--spring.datasource.url=${PostgresSupport.jdbcUrl}")
            add("--spring.datasource.username=${PostgresSupport.username}")
            add("--spring.datasource.password=${PostgresSupport.password}")
            add("--host.mock-run.profile=$MOCK_PROFILE")
            add("--host.mock-run.schema=$SCHEMA")
            mockVirtualLimit?.let { add("--host.mock-run.virtual-limit=$it") }
            readInterval?.let { add("--host.site-timings.read-interval=$it") }
        }.toTypedArray(),
    )

    /** 호스트만 닫고 같은 DB 로 다시 띄운다(재기동). mimic 과 셀 대역은 그대로다. */
    fun restartHost() {
        context.close()
        context = startHost()
    }

    fun now(): Instant = mimic.server.exclusive { mimic.instance(HUMANOID)!!.clock.now() }

    fun stopCell() = cell.stop(0)

    data class Reply(val status: Int, val body: JsonNode?)

    fun get(path: String): JsonNode {
        val reply = fetch(path)
        check(reply.status == 200) { "$path: ${reply.status} ${reply.body}" }
        return reply.body!!
    }

    fun fetch(path: String): Reply {
        val response = HTTP.send(HttpRequest.newBuilder(URI.create(url + path)).build(), HttpResponse.BodyHandlers.ofString())
        return Reply(response.statusCode(), response.body().takeIf { it.isNotBlank() }?.let { runCatching { JSON.readTree(it) }.getOrNull() })
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

        /** 대역 뷰의 처음 행. 버전 1 과 picasso 케이퍼빌리티 기본값이며, 운영 서비스 마이그레이션이 만드는 버전 1 과 같다. */
        val STANDARD_TIMINGS: List<Long> = listOf(1, 30, 15, 60, 300)

        val ROOT: Path = Path.of("..").toAbsolutePath().normalize()
        val HTTP: HttpClient = HttpClient.newHttpClient()
        val JSON = ObjectMapper()

        /** 모의 실행용 프로파일과 프로파일 스키마. 호스트 시험은 절대 경로를 실행 인자로 넘긴다(S3b 스펙 §6.4). */
        val MOCK_PROFILE: Path = ROOT.resolve("mission-host/mock-run/humanoid-a.json")
        val SCHEMA: Path = ROOT.resolve("picasso/profile/schema/capability-profile.schema.json")

        /** 현장 셀 대역 `CellFixture.STANDARD` 의 처음 모양. 신호 셋은 현장 표준 픽스처의 사양과 처음 값이다. */
        val STANDARD_CELL = """
            {"presentations":[{"id":"$SOURCE","occupied":true,"material":"$MATERIAL","observedAt":null}],
             "slots":[{"id":"RACK-204.S01","occupied":false,"material":null,"observedAt":null},
                      {"id":"RACK-204.S02","occupied":false,"material":null,"observedAt":null},
                      {"id":"RACK-204.S03","occupied":false,"material":null,"observedAt":null},
                      {"id":"RACK-204.S04","occupied":false,"material":null,"observedAt":null}],
             "signals":[{"name":"rack_present","location":"RACK-204","kind":"BOOLEAN","safety":false,"value":"false","observedAt":null},
                        {"name":"guard_closed","location":null,"kind":"BOOLEAN","safety":true,"value":"true","observedAt":null},
                        {"name":"lot_code","location":null,"kind":"TEXT","safety":false,"value":"LOT-0001","observedAt":null}]}
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
