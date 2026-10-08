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
